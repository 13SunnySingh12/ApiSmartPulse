package com.apismartpulse.ai;

import com.apismartpulse.common.ApiException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains ai_sync_queue (§5.13): ≤ 50 due rows per run, claimed with SKIP LOCKED; backoff of
 * attempts × 30 s; FAILED after 10 attempts (visible on the Admin ML page).
 */
@Component
public class OutboxProcessor {
  private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);
  static final int MAX_ATTEMPTS = 10;

  private final JdbcClient jdbc;
  private final AiServiceClient ai;

  public OutboxProcessor(JdbcClient jdbc, AiServiceClient ai) {
    this.jdbc = jdbc;
    this.ai = ai;
  }

  record Job(long id, String entityType, long entityId, String operation, int attempts) {}

  // ponytail: holds row locks during the HTTP calls; fine for one instance, split claim/process
  // into two transactions if several backends ever share the queue.
  @Transactional
  public int processBatch() {
    if (ai.circuitOpen()) return 0;
    List<Job> jobs =
        jdbc.sql(
                "SELECT id, entity_type, entity_id, operation, attempts FROM ai_sync_queue"
                    + " WHERE status = 'PENDING' AND next_attempt_at <= now() ORDER BY id LIMIT 50"
                    + " FOR UPDATE SKIP LOCKED")
            .query(
                (rs, i) ->
                    new Job(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getLong(3),
                        rs.getString(4),
                        rs.getInt(5)))
            .list();
    int ok = 0;
    for (Job job : jobs) {
      try {
        if (job.operation().equals("UPSERT")) ai.upsertEmbedding(job.entityType(), job.entityId());
        else ai.deleteEmbedding(job.entityType(), job.entityId());
        jdbc.sql("UPDATE ai_sync_queue SET status = 'DONE', last_error = NULL WHERE id = ?")
            .param(job.id())
            .update();
        ok++;
      } catch (ApiException e) {
        int attempts = job.attempts() + 1;
        // Error code only — never task content (§6.3 last_error).
        jdbc.sql(
                "UPDATE ai_sync_queue SET attempts = ?, last_error = ?, status = ?,"
                    + " next_attempt_at = now() + make_interval(secs => ?) WHERE id = ?")
            .params(
                attempts,
                e.code(),
                attempts >= MAX_ATTEMPTS ? "FAILED" : "PENDING",
                attempts * 30,
                job.id())
            .update();
        if (ai.circuitOpen()) break;
      }
    }
    if (!jobs.isEmpty()) log.debug("Outbox: {}/{} jobs synced", ok, jobs.size());
    return ok;
  }
}
