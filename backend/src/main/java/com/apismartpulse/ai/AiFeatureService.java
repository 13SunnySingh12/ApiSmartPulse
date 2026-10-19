package com.apismartpulse.ai;

import com.apismartpulse.ai.AiServiceClient.AskResult;
import com.apismartpulse.ai.AiServiceClient.SimilarResult;
import com.apismartpulse.ai.AiServiceClient.SplitResult;
import com.apismartpulse.common.ApiException;
import com.apismartpulse.config.AppProperties;
import com.apismartpulse.config.CurrentUser;
import com.apismartpulse.project.Project;
import com.apismartpulse.project.ProjectService;
import com.apismartpulse.ratelimit.RateLimiters;
import com.apismartpulse.task.TaskService;
import com.apismartpulse.task.TaskService.Located;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** User-facing AI helpers (FR-060..064) and admin ML actions (FR-052). All advisory (BR-012). */
@Service
public class AiFeatureService {
  private final AiServiceClient ai;
  private final ProjectService projects;
  private final TaskService tasks;
  private final RateLimiters limiters;
  private final AiOutbox outbox;
  private final JdbcClient jdbc;
  private final AppProperties props;

  public AiFeatureService(
      AiServiceClient ai,
      ProjectService projects,
      TaskService tasks,
      RateLimiters limiters,
      AiOutbox outbox,
      JdbcClient jdbc,
      AppProperties props) {
    this.ai = ai;
    this.projects = projects;
    this.tasks = tasks;
    this.limiters = limiters;
    this.outbox = outbox;
    this.jdbc = jdbc;
    this.props = props;
  }

  public AskResult ask(
      String projectKey, String question, LocalDate from, LocalDate to, CurrentUser user) {
    Project p = projects.requireVisible(projectKey, user);
    String q = question == null ? "" : question.trim();
    if (q.length() < 3 || q.length() > 500)
      throw ApiException.validation("question", "must be 3–500 characters");
    if (from != null && to != null) {
      if (to.isBefore(from)) throw ApiException.validation("to", "must be on or after from");
      if (ChronoUnit.DAYS.between(from, to) > 366) {
        throw ApiException.badRequest("RANGE_TOO_LARGE", "The date range can be at most 366 days.");
      }
    }
    aiLimit(user);
    return ai.ask(p.getId(), q, from, to);
  }

  public SimilarResult duplicates(
      String projectKey, String title, String description, CurrentUser user) {
    Project p = projects.requireVisible(projectKey, user);
    RateLimiters.require(
        limiters.duplicateCheck, "u" + user.id(), "RATE_LIMITED", "Too many duplicate checks.");
    String text = title.trim() + "\n" + (description == null ? "" : description);
    return ai.similar(p.getId(), text.length() > 2000 ? text.substring(0, 2000) : text, 3);
  }

  public SplitResult split(String taskKey, CurrentUser user) {
    Located l = tasks.locateWritable(taskKey, user);
    BigDecimal hours = l.task().getEstimateHours();
    if (hours == null) {
      hours =
          jdbc.sql(
                  "SELECT predicted_hours FROM task_predictions WHERE task_id = ? AND status = 'OK'")
              .param(l.task().getId())
              .query(BigDecimal.class)
              .optional()
              .orElse(null);
    }
    if (hours == null || hours.doubleValue() < props.splitMinHours()) {
      throw ApiException.badRequest(
          "TASK_TOO_SMALL",
          "Split suggestions are for tasks estimated at "
              + (int) props.splitMinHours()
              + " h or more.");
    }
    aiLimit(user);
    return ai.split(l.task().getId());
  }

  // ── admin ML (FR-052) ───────────────────────────────────────────────────────

  public AiServiceClient.TrainResult train(CurrentUser user) {
    return ai.train(user.id());
  }

  public int reindex() {
    return outbox.enqueueAll();
  }

  public record ModelRun(
      long id,
      String modelName,
      String version,
      String status,
      boolean active,
      Integer trainingRows,
      String metrics,
      String errorMessage,
      java.time.Instant startedAt,
      java.time.Instant finishedAt) {}

  public record ModelsStatus(
      List<ModelRun> active, List<ModelRun> runs, Map<String, Long> outbox) {}

  public ModelsStatus models() {
    String select =
        "SELECT id, model_name, version, status, is_active, training_rows, metrics::text, error_message,"
            + " started_at, finished_at FROM ml_model_versions";
    org.springframework.jdbc.core.RowMapper<ModelRun> mapper =
        (rs, i) ->
            new ModelRun(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getBoolean(5),
                (Integer) rs.getObject(6),
                rs.getString(7),
                rs.getString(8),
                rs.getTimestamp(9).toInstant(),
                rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant());
    List<ModelRun> active =
        jdbc.sql(select + " WHERE is_active ORDER BY model_name").query(mapper).list();
    List<ModelRun> runs =
        jdbc.sql(select + " ORDER BY started_at DESC, id DESC LIMIT 10").query(mapper).list();
    Map<String, Long> counts =
        new java.util.TreeMap<>(Map.of("PENDING", 0L, "DONE", 0L, "FAILED", 0L));
    jdbc.sql("SELECT status, count(*) FROM ai_sync_queue GROUP BY status")
        .query((rs, i) -> counts.put(rs.getString(1), rs.getLong(2)))
        .list();
    return new ModelsStatus(active, runs, counts);
  }

  private void aiLimit(CurrentUser user) {
    RateLimiters.require(
        limiters.ai,
        "u" + user.id(),
        "AI_RATE_LIMITED",
        "You've used your AI requests for now — try again shortly.");
  }
}
