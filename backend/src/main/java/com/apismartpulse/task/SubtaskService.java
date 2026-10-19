package com.apismartpulse.task;

import com.apismartpulse.common.ApiException;
import com.apismartpulse.config.CurrentUser;
import com.apismartpulse.project.ProjectService;
import com.apismartpulse.task.TaskDtos.SubtaskView;
import com.apismartpulse.task.TaskService.Located;
import java.util.HashSet;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-026: ordered checklist, max 50 per task, bulk add is all-or-nothing. */
@Service
public class SubtaskService {
  static final int MAX_SUBTASKS = 50;

  private final TaskService tasks;
  private final TaskQueries queries;
  private final JdbcClient jdbc;

  public SubtaskService(TaskService tasks, TaskQueries queries, JdbcClient jdbc) {
    this.tasks = tasks;
    this.queries = queries;
    this.jdbc = jdbc;
  }

  @Transactional
  public long add(String taskKey, List<String> titles, CurrentUser user) {
    Located l = tasks.locateWritable(taskKey, user);
    long taskId = l.task().getId();
    // Lock the task row so concurrent adds cannot exceed the limit or collide on position.
    jdbc.sql("SELECT id FROM tasks WHERE id = ? FOR UPDATE")
        .param(taskId)
        .query(Long.class)
        .single();
    List<String> clean = titles.stream().map(SubtaskService::checkTitle).toList();
    long existing =
        jdbc.sql("SELECT count(*) FROM subtasks WHERE task_id = ?")
            .param(taskId)
            .query(Long.class)
            .single();
    if (existing + clean.size() > MAX_SUBTASKS) {
      throw ApiException.conflict(
              "LIMIT_REACHED",
              "Subtask limit reached",
              "A task can have at most " + MAX_SUBTASKS + " subtasks.")
          .with("existing", existing);
    }
    int next =
        jdbc.sql("SELECT coalesce(max(position) + 1, 0) FROM subtasks WHERE task_id = ?")
            .param(taskId)
            .query(Integer.class)
            .single();
    for (String title : clean) {
      jdbc.sql("INSERT INTO subtasks (task_id, title, position) VALUES (?, ?, ?)")
          .params(taskId, title, next++)
          .update();
    }
    return taskId;
  }

  @Transactional
  public long update(long subtaskId, String title, Boolean done, CurrentUser user) {
    Located l = locate(subtaskId, user);
    if (title != null) {
      jdbc.sql("UPDATE subtasks SET title = ? WHERE id = ?")
          .params(checkTitle(title), subtaskId)
          .update();
    }
    if (done != null)
      jdbc.sql("UPDATE subtasks SET done = ? WHERE id = ?").params(done, subtaskId).update();
    return l.task().getId();
  }

  @Transactional
  public long delete(long subtaskId, CurrentUser user) {
    Located l = locate(subtaskId, user);
    jdbc.sql("DELETE FROM subtasks WHERE id = ?").param(subtaskId).update();
    return l.task().getId();
  }

  /** Reorders to exactly the given ids (positions are a deferrable unique constraint). */
  @Transactional
  public List<SubtaskView> reorder(String taskKey, List<Long> ids, CurrentUser user) {
    Located l = tasks.locateWritable(taskKey, user);
    long taskId = l.task().getId();
    List<Long> current = queries.subtasks(taskId).stream().map(SubtaskView::id).toList();
    if (ids.size() != current.size() || !new HashSet<>(ids).equals(new HashSet<>(current))) {
      throw ApiException.validation("ids", "must list every subtask of the task exactly once");
    }
    for (int i = 0; i < ids.size(); i++) {
      jdbc.sql("UPDATE subtasks SET position = ? WHERE id = ?").params(i, ids.get(i)).update();
    }
    return queries.subtasks(taskId);
  }

  private Located locate(long subtaskId, CurrentUser user) {
    Long taskId =
        jdbc.sql("SELECT task_id FROM subtasks WHERE id = ?")
            .param(subtaskId)
            .query(Long.class)
            .optional()
            .orElseThrow(ApiException::notFound);
    Located l = tasks.locate(taskId, user);
    ProjectService.requireActive(l.project());
    return l;
  }

  private static String checkTitle(String title) {
    String t = title == null ? "" : title.trim();
    if (t.isEmpty() || t.length() > 120)
      throw ApiException.validation("title", "must be 1–120 characters");
    return t;
  }
}
