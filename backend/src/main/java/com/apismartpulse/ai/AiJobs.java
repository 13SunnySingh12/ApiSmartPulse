package com.apismartpulse.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduled background work (§5.13). Disabled in tests, which call the services directly. */
@Component
@ConditionalOnProperty(name = "app.jobs-enabled", havingValue = "true", matchIfMissing = true)
public class AiJobs {
  private static final Logger log = LoggerFactory.getLogger(AiJobs.class);

  private final OutboxProcessor outbox;
  private final PredictionService predictions;
  private final JdbcClient jdbc;

  public AiJobs(OutboxProcessor outbox, PredictionService predictions, JdbcClient jdbc) {
    this.outbox = outbox;
    this.predictions = predictions;
    this.jdbc = jdbc;
  }

  @Scheduled(fixedDelay = 10_000, initialDelay = 15_000)
  void syncEmbeddings() {
    run("outbox", outbox::processBatch);
  }

  @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
  void retryPredictions() {
    run("prediction retry", predictions::retryPending);
  }

  @Scheduled(cron = "0 0 2 * * *", zone = "${app.timezone}")
  void nightlyRescore() {
    run("nightly rescore", predictions::rescoreAll);
  }

  @Scheduled(cron = "0 0 3 * * *", zone = "${app.timezone}")
  public void cleanup() {
    int tokensDeleted =
        jdbc.sql("DELETE FROM refresh_tokens WHERE expires_at < now() - interval '30 days'")
            .update();
    int outboxDeleted =
        jdbc.sql(
                "DELETE FROM ai_sync_queue WHERE status = 'DONE' AND created_at < now() - interval '7 days'")
            .update();
    int keysDeleted =
        jdbc.sql("DELETE FROM api_keys WHERE revoked_at < now() - interval '90 days'").update();
    log.info(
        "Cleanup removed {} refresh tokens, {} outbox rows, {} revoked keys",
        tokensDeleted,
        outboxDeleted,
        keysDeleted);
  }

  private static void run(String name, java.util.function.IntSupplier job) {
    try {
      job.getAsInt();
    } catch (RuntimeException e) {
      log.warn("Background job '{}' failed: {}", name, e.getClass().getSimpleName());
    }
  }
}
