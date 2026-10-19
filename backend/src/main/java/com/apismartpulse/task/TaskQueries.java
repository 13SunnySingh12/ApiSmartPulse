package com.apismartpulse.task;

import com.apismartpulse.common.PageResponse;
import com.apismartpulse.project.ProjectDtos.UserRef;
import com.apismartpulse.task.Task.Priority;
import com.apismartpulse.task.Task.Status;
import com.apismartpulse.task.TaskDtos.LabelView;
import com.apismartpulse.task.TaskDtos.PredictionView;
import com.apismartpulse.task.TaskDtos.SubtaskSummary;
import com.apismartpulse.task.TaskDtos.SubtaskView;
import com.apismartpulse.task.TaskDtos.TaskRef;
import com.apismartpulse.task.TaskDtos.TaskSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Read-side SQL for task lists and detail extras (all parameters bound; sort whitelisted). */
@Component
public class TaskQueries {
  private static final String SELECT =
      """
      SELECT t.id, t.number, p.key AS pkey, t.title, t.status, t.priority, t.due_date,
        t.assignee_id, a.name AS aname, a.active AS aactive, t.estimate_hours, t.actual_hours,
        t.version, t.created_at, t.updated_at,
        (SELECT count(*) FROM subtasks s WHERE s.task_id = t.id) AS sub_total,
        (SELECT count(*) FROM subtasks s WHERE s.task_id = t.id AND s.done) AS sub_done,
        EXISTS (SELECT 1 FROM task_dependencies d JOIN tasks b ON b.id = d.blocker_task_id
                WHERE d.task_id = t.id AND b.status <> 'DONE') AS blocked,
        (SELECT json_agg(json_build_object('id', l.id, 'name', l.name, 'color', l.color) ORDER BY l.name)
           FROM task_labels tl JOIN labels l ON l.id = tl.label_id WHERE tl.task_id = t.id) AS labels,
        tp.status AS pstatus, tp.predicted_hours, tp.overdue_probability, tp.risk_level,
        tp.suggested_priority, tp.priority_confidence, tp.source, tp.model_version, tp.computed_at
      FROM tasks t
      JOIN projects p ON p.id = t.project_id
      LEFT JOIN users a ON a.id = t.assignee_id
      LEFT JOIN task_predictions tp ON tp.task_id = t.id
      """;

  private final JdbcClient jdbc;
  private final JsonMapper json;
  private final RowMapper<TaskSummary> summaryMapper = this::summary;

  public TaskQueries(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public PageResponse<TaskSummary> list(long projectId, TaskFilter f) {
    StringBuilder where = new StringBuilder(" WHERE t.project_id = :pid");
    Map<String, Object> params = new HashMap<>();
    params.put("pid", projectId);
    if (!f.statuses().isEmpty()) {
      where.append(" AND t.status IN (:statuses)");
      params.put("statuses", f.statuses().stream().map(Enum::name).toList());
    }
    if (f.unassigned()) where.append(" AND t.assignee_id IS NULL");
    if (f.assigneeId() != null) {
      where.append(" AND t.assignee_id = :assignee");
      params.put("assignee", f.assigneeId());
    }
    if (!f.priorities().isEmpty()) {
      where.append(" AND t.priority IN (:priorities)");
      params.put("priorities", f.priorities().stream().map(Enum::name).toList());
    }
    if (!f.risks().isEmpty()) {
      where.append(" AND tp.status = 'OK' AND tp.risk_level IN (:risks)");
      params.put("risks", f.risks());
    }
    if (f.label() != null) {
      where.append(
          " AND EXISTS (SELECT 1 FROM task_labels tl JOIN labels l ON l.id = tl.label_id"
              + " WHERE tl.task_id = t.id AND lower(l.name) = lower(:label))");
      params.put("label", f.label());
    }
    if (f.dueBefore() != null) {
      where.append(" AND t.due_date <= :dueBefore");
      params.put("dueBefore", f.dueBefore());
    }
    if (f.dueAfter() != null) {
      where.append(" AND t.due_date >= :dueAfter");
      params.put("dueAfter", f.dueAfter());
    }
    if (f.tsQuery() != null) {
      if (f.tsQuery().isEmpty()) where.append(" AND false");
      else {
        where.append(
            " AND to_tsvector('simple', t.title || ' ' || coalesce(t.description, ''))"
                + " @@ to_tsquery('simple', :tsq)");
        params.put("tsq", f.tsQuery());
      }
    }
    long total =
        jdbc.sql(
                "SELECT count(*) FROM tasks t LEFT JOIN task_predictions tp ON tp.task_id = t.id"
                    + where)
            .params(params)
            .query(Long.class)
            .single();
    params.put("limit", f.size());
    params.put("offset", (long) f.page() * f.size());
    List<TaskSummary> items =
        jdbc.sql(SELECT + where + " ORDER BY " + f.orderBy() + " LIMIT :limit OFFSET :offset")
            .params(params)
            .query(summaryMapper)
            .list();
    return PageResponse.of(items, f.page(), f.size(), total);
  }

  public TaskSummary summary(long taskId) {
    return jdbc.sql(SELECT + " WHERE t.id = ?").param(taskId).query(summaryMapper).single();
  }

  public List<SubtaskView> subtasks(long taskId) {
    return jdbc.sql(
            "SELECT id, title, done, position FROM subtasks WHERE task_id = ? ORDER BY position, id")
        .param(taskId)
        .query(
            (rs, i) ->
                new SubtaskView(rs.getLong(1), rs.getString(2), rs.getBoolean(3), rs.getInt(4)))
        .list();
  }

  /** Tasks blocking {@code taskId}. */
  public List<TaskRef> blockers(long taskId) {
    return refs(
        "SELECT p.key, b.number, b.title, b.status FROM task_dependencies d"
            + " JOIN tasks b ON b.id = d.blocker_task_id JOIN projects p ON p.id = b.project_id"
            + " WHERE d.task_id = ? ORDER BY b.number",
        taskId);
  }

  /** Tasks that {@code taskId} blocks. */
  public List<TaskRef> blocked(long taskId) {
    return refs(
        "SELECT p.key, t.number, t.title, t.status FROM task_dependencies d"
            + " JOIN tasks t ON t.id = d.task_id JOIN projects p ON p.id = t.project_id"
            + " WHERE d.blocker_task_id = ? ORDER BY t.number",
        taskId);
  }

  public List<TaskRef> unfinishedBlockers(long taskId) {
    return blockers(taskId).stream().filter(r -> r.status() != Status.DONE).toList();
  }

  public long commentCount(long taskId) {
    return jdbc.sql("SELECT count(*) FROM comments WHERE task_id = ? AND deleted_at IS NULL")
        .param(taskId)
        .query(Long.class)
        .single();
  }

  private List<TaskRef> refs(String sql, long taskId) {
    return jdbc.sql(sql)
        .param(taskId)
        .query(
            (rs, i) ->
                new TaskRef(
                    rs.getString(1) + "-" + rs.getInt(2),
                    rs.getString(3),
                    Status.valueOf(rs.getString(4))))
        .list();
  }

  private TaskSummary summary(ResultSet rs, int row) throws SQLException {
    Long assigneeId = (Long) rs.getObject("assignee_id");
    UserRef assignee =
        assigneeId == null
            ? null
            : new UserRef(
                assigneeId,
                rs.getString("aname") + (rs.getBoolean("aactive") ? "" : " (deactivated)"));
    String labelsJson = rs.getString("labels");
    List<LabelView> labels =
        labelsJson == null
            ? List.of()
            : new ArrayList<>(List.of(json.readValue(labelsJson, LabelView[].class)));
    return new TaskSummary(
        rs.getLong("id"),
        rs.getString("pkey") + "-" + rs.getInt("number"),
        rs.getString("pkey"),
        rs.getString("title"),
        Status.valueOf(rs.getString("status")),
        Priority.valueOf(rs.getString("priority")),
        rs.getObject("due_date", LocalDate.class),
        assignee,
        rs.getBigDecimal("estimate_hours"),
        rs.getBigDecimal("actual_hours"),
        labels,
        new SubtaskSummary(rs.getLong("sub_done"), rs.getLong("sub_total")),
        rs.getBoolean("blocked"),
        prediction(rs),
        rs.getInt("version"),
        instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("updated_at")));
  }

  static PredictionView prediction(ResultSet rs) throws SQLException {
    String status = rs.getString("pstatus");
    if (status == null) return PredictionView.PENDING;
    return new PredictionView(
        status,
        rs.getBigDecimal("predicted_hours"),
        rs.getBigDecimal("overdue_probability"),
        rs.getString("risk_level"),
        rs.getString("suggested_priority"),
        rs.getBigDecimal("priority_confidence"),
        rs.getString("source"),
        rs.getString("model_version"),
        instant(rs.getTimestamp("computed_at")));
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
