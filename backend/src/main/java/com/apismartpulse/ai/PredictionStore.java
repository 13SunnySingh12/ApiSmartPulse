package com.apismartpulse.ai;

import com.apismartpulse.ai.AiServiceClient.Prediction;
import com.apismartpulse.ai.AiServiceClient.TaskFeatures;
import com.apismartpulse.config.AppProperties;
import java.sql.Array;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** task_predictions persistence and the feature query shared by model and heuristic paths. */
@Component
public class PredictionStore {
  private final JdbcClient jdbc;
  private final AppProperties props;
  private final Clock clock;

  public PredictionStore(JdbcClient jdbc, AppProperties props, Clock clock) {
    this.jdbc = jdbc;
    this.props = props;
    this.clock = clock;
  }

  public void markPending(long taskId) {
    jdbc.sql(
            "INSERT INTO task_predictions (task_id, status) VALUES (?, 'PENDING') ON CONFLICT (task_id) DO NOTHING")
        .param(taskId)
        .update();
  }

  public void saveOk(Prediction p) {
    jdbc.sql(
            """
            INSERT INTO task_predictions (task_id, status, predicted_hours, overdue_probability, risk_level,
              suggested_priority, priority_confidence, source, model_version, attempts, computed_at)
            VALUES (:id, 'OK', :hours, :prob, :risk, :prio, :conf, :source, :version, 0, now())
            ON CONFLICT (task_id) DO UPDATE SET status = 'OK', predicted_hours = :hours,
              overdue_probability = :prob, risk_level = :risk, suggested_priority = :prio,
              priority_confidence = :conf, source = :source, model_version = :version,
              attempts = 0, computed_at = now()
            """)
        .param("id", p.taskId())
        .param("hours", p.predictedHours())
        .param("prob", p.overdueProbability())
        .param("risk", p.riskLevel())
        .param("prio", p.suggestedPriority())
        .param("conf", p.priorityConfidence())
        .param("source", p.source())
        .param("version", p.modelVersion())
        .update();
  }

  /** Increments attempts for still-pending rows and returns the ids that reached {@code max}. */
  public List<Long> recordFailedAttempt(List<Long> ids, int max) {
    if (ids.isEmpty()) return List.of();
    return jdbc
        .sql(
            "UPDATE task_predictions SET attempts = attempts + 1 WHERE task_id IN (:ids) AND status = 'PENDING'"
                + " RETURNING task_id, attempts")
        .param("ids", ids)
        .query((rs, i) -> rs.getInt(2) >= max ? rs.getLong(1) : null)
        .list()
        .stream()
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  public List<Long> pendingOpenTasks(int limit) {
    return jdbc.sql(
            "SELECT p.task_id FROM task_predictions p JOIN tasks t ON t.id = p.task_id"
                + " WHERE p.status = 'PENDING' AND t.status <> 'DONE' ORDER BY p.task_id LIMIT ?")
        .param(limit)
        .query(Long.class)
        .list();
  }

  public List<Long> openTaskIds(long afterId, int limit) {
    return jdbc.sql("SELECT id FROM tasks WHERE status <> 'DONE' AND id > ? ORDER BY id LIMIT ?")
        .params(afterId, limit)
        .query(Long.class)
        .list();
  }

  /** Active model versions, e.g. "ESTIMATOR:20261016-0215,RISK:…"; empty when untrained. */
  public String activeModelSignature() {
    return jdbc.sql(
            "SELECT coalesce(string_agg(model_name || ':' || version, ',' ORDER BY model_name), '')"
                + " FROM ml_model_versions WHERE is_active")
        .query(String.class)
        .single();
  }

  /** Features for open tasks (§5.9.5 inputs); DONE tasks are skipped. */
  public List<TaskFeatures> features(List<Long> ids) {
    if (ids.isEmpty()) return List.of();
    LocalDate today = LocalDate.now(clock.withZone(props.timezone()));
    return jdbc.sql(
            """
            SELECT t.id, t.project_id, t.title, left(coalesce(t.description, ''), 2000) AS descr,
              length(coalesce(t.description, '')) AS dlen, t.priority, t.assignee_id, t.estimate_hours,
              t.due_date,
              (SELECT count(*) FROM subtasks s WHERE s.task_id = t.id) AS subs,
              (SELECT count(*) FROM task_dependencies d JOIN tasks b ON b.id = d.blocker_task_id
                 WHERE d.task_id = t.id AND b.status <> 'DONE') AS blockers,
              (SELECT array_agg(l.name ORDER BY l.name) FROM task_labels tl JOIN labels l ON l.id = tl.label_id
                 WHERE tl.task_id = t.id) AS labels,
              CASE WHEN t.assignee_id IS NULL THEN 0 ELSE
                (SELECT coalesce(sum(coalesce(o.estimate_hours, op.predicted_hours, 4)), 0)
                 FROM tasks o LEFT JOIN task_predictions op ON op.task_id = o.id
                 WHERE o.assignee_id = t.assignee_id AND o.status <> 'DONE' AND o.id <> t.id) END AS assignee_open
            FROM tasks t WHERE t.id IN (:ids) AND t.status <> 'DONE' ORDER BY t.id
            """)
        .param("ids", ids)
        .query(
            (rs, i) ->
                new TaskFeatures(
                    rs.getLong("id"),
                    rs.getLong("project_id"),
                    rs.getString("title"),
                    rs.getString("descr"),
                    rs.getString("priority"),
                    labels(rs.getArray("labels")),
                    rs.getInt("subs"),
                    rs.getInt("dlen"),
                    (Long) rs.getObject("assignee_id"),
                    rs.getBigDecimal("estimate_hours"),
                    rs.getObject("due_date", LocalDate.class),
                    rs.getInt("blockers"),
                    rs.getBigDecimal("assignee_open"),
                    today))
        .list();
  }

  /** Median actual hours of DONE tasks per priority for one project. */
  public Map<String, Double> medianByPriority(long projectId) {
    Map<String, Double> out = new HashMap<>();
    jdbc.sql(
            "SELECT priority, percentile_cont(0.5) WITHIN GROUP (ORDER BY actual_hours) FROM tasks"
                + " WHERE project_id = ? AND status = 'DONE' AND actual_hours IS NOT NULL GROUP BY priority")
        .param(projectId)
        .query((rs, i) -> out.put(rs.getString(1), rs.getDouble(2)))
        .list();
    return out;
  }

  public Double globalMedian() {
    List<Double> rows =
        jdbc.sql(
                "SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY actual_hours) FROM tasks"
                    + " WHERE status = 'DONE' AND actual_hours IS NOT NULL")
            .query(Double.class)
            .list();
    return rows.isEmpty() ? null : rows.get(0);
  }

  private static List<String> labels(Array a) throws java.sql.SQLException {
    return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
  }
}
