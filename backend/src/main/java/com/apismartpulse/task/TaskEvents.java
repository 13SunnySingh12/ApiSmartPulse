package com.apismartpulse.task;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Append-only task history (task_events). Joins the caller's transaction. */
@Component
public class TaskEvents {
  public enum Type {
    CREATED,
    STATUS_CHANGED,
    ASSIGNEE_CHANGED,
    ESTIMATE_CHANGED,
    DUE_DATE_CHANGED,
    AI_SUGGESTION_APPLIED,
    DEPENDENCY_REMOVED
  }

  public record EventView(
      String type,
      String fromValue,
      String toValue,
      Long actorId,
      String actorName,
      Instant occurredAt) {}

  private final JdbcClient jdbc;

  public TaskEvents(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void record(long taskId, long projectId, Type type, Object from, Object to, Long actorId) {
    jdbc.sql(
            "INSERT INTO task_events (task_id, project_id, event_type, from_value, to_value, actor_id)"
                + " VALUES (?, ?, ?, ?, ?, ?)")
        .params(
            taskId,
            projectId,
            type.name(),
            from == null ? null : from.toString(),
            to == null ? null : to.toString(),
            actorId)
        .update();
  }

  public List<EventView> recent(long taskId, int limit) {
    return jdbc.sql(
            "SELECT e.event_type, e.from_value, e.to_value, e.actor_id, u.name, u.active, e.occurred_at"
                + " FROM task_events e LEFT JOIN users u ON u.id = e.actor_id"
                + " WHERE e.task_id = ? ORDER BY e.occurred_at DESC, e.id DESC LIMIT ?")
        .params(taskId, limit)
        .query(
            (rs, i) ->
                new EventView(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    (Long) rs.getObject(4),
                    rs.getString(5) == null
                        ? null
                        : rs.getString(5) + (rs.getBoolean(6) ? "" : " (deactivated)"),
                    rs.getTimestamp(7).toInstant()))
        .list();
  }
}
