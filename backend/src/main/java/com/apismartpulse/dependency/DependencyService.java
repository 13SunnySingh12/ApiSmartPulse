package com.apismartpulse.dependency;

import com.apismartpulse.common.ApiException;
import com.apismartpulse.config.CurrentUser;
import com.apismartpulse.task.TaskEvents;
import com.apismartpulse.task.TaskKey;
import com.apismartpulse.task.TaskService;
import com.apismartpulse.task.TaskService.Located;
import java.sql.Array;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** "B is blocked by A" (FR-029, BR-007): same project, no cycles, at most 20 blockers. */
@Service
public class DependencyService {
  static final int MAX_BLOCKERS = 20;

  private final TaskService tasks;
  private final TaskEvents events;
  private final JdbcClient jdbc;

  public DependencyService(TaskService tasks, TaskEvents events, JdbcClient jdbc) {
    this.tasks = tasks;
    this.events = events;
    this.jdbc = jdbc;
  }

  @Transactional
  public long add(String taskKey, String blockerKey, CurrentUser user) {
    Located blocked = tasks.locateWritable(taskKey, user);
    TaskKey bk;
    try {
      bk = TaskKey.parse(blockerKey);
    } catch (ApiException e) {
      throw ApiException.validation("blockerKey", "must be a task key such as PULSE-7");
    }
    if (!bk.projectKey().equals(blocked.project().getKey())) {
      throw ApiException.badRequest(
          "CROSS_PROJECT_DEPENDENCY", "A blocker must be in the same project.");
    }
    Located blocker;
    try {
      blocker = tasks.locate(blockerKey, user);
    } catch (ApiException e) {
      throw ApiException.validation("blockerKey", "no such task in this project");
    }
    long a = blocked.task().getId();
    long b = blocker.task().getId();
    if (a == b) throw ApiException.badRequest("SELF_DEPENDENCY", "A task cannot block itself.");

    // Serialise dependency changes per project so two concurrent inserts cannot form a cycle.
    jdbc.sql("SELECT id FROM projects WHERE id = ? FOR UPDATE")
        .param(blocked.project().getId())
        .query(Long.class)
        .single();
    long count =
        jdbc.sql("SELECT count(*) FROM task_dependencies WHERE task_id = ?")
            .param(a)
            .query(Long.class)
            .single();
    if (count >= MAX_BLOCKERS) {
      throw ApiException.conflict(
          "LIMIT_REACHED", "Blocker limit reached", "A task can have at most 20 blockers.");
    }
    List<Long> path = cyclePath(a, b);
    if (path != null) {
      Map<Long, String> keys = keys(path, blocked.project().getKey());
      List<String> cycle = Stream.concat(Stream.of(a), path.stream()).map(keys::get).toList();
      throw ApiException.conflict(
              "DEPENDENCY_CYCLE",
              "Dependency cycle",
              "That blocker would create a cycle: " + String.join(" → ", cycle))
          .with("cycle", cycle);
    }
    jdbc.sql(
            "INSERT INTO task_dependencies (task_id, blocker_task_id, created_by) VALUES (?, ?, ?)"
                + " ON CONFLICT DO NOTHING")
        .params(a, b, user.id())
        .update();
    return a;
  }

  @Transactional
  public long remove(String taskKey, String blockerKey, CurrentUser user) {
    Located blocked = tasks.locateWritable(taskKey, user);
    Located blocker = tasks.locate(blockerKey, user);
    int n =
        jdbc.sql("DELETE FROM task_dependencies WHERE task_id = ? AND blocker_task_id = ?")
            .params(blocked.task().getId(), blocker.task().getId())
            .update();
    if (n == 0) throw ApiException.notFound();
    events.record(
        blocked.task().getId(),
        blocked.project().getId(),
        TaskEvents.Type.DEPENDENCY_REMOVED,
        blocker.key(),
        null,
        user.id());
    return blocked.task().getId();
  }

  /**
   * If making {@code blocker} block {@code blocked} closes a loop, returns the chain from the
   * blocker back to the blocked task (§6.3 recursive CTE), else null.
   */
  List<Long> cyclePath(long blocked, long blocker) {
    return jdbc.sql(
            """
            WITH RECURSIVE chain(id, path) AS (
              SELECT d.blocker_task_id, ARRAY[:blocker::bigint, d.blocker_task_id]
              FROM task_dependencies d WHERE d.task_id = :blocker
              UNION ALL
              SELECT d.blocker_task_id, c.path || d.blocker_task_id
              FROM task_dependencies d JOIN chain c ON d.task_id = c.id
              WHERE NOT d.blocker_task_id = ANY(c.path)
            )
            SELECT path FROM chain WHERE id = :blocked LIMIT 1
            """)
        .param("blocked", blocked)
        .param("blocker", blocker)
        .query((rs, i) -> toList(rs.getArray(1)))
        .optional()
        .orElse(null);
  }

  private Map<Long, String> keys(List<Long> ids, String projectKey) {
    return jdbc
        .sql("SELECT id, number FROM tasks WHERE id IN (:ids)")
        .param("ids", ids)
        .query((rs, i) -> Map.entry(rs.getLong(1), projectKey + "-" + rs.getInt(2)))
        .list()
        .stream()
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  private static List<Long> toList(Array array) throws java.sql.SQLException {
    return Arrays.asList((Long[]) array.getArray());
  }
}
