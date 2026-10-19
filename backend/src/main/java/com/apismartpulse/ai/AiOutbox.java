package com.apismartpulse.ai;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Writes ai_sync_queue rows inside the caller's transaction (transactional outbox, FR-062). */
@Component
public class AiOutbox {
  public enum Entity {
    TASK,
    COMMENT
  }

  private final JdbcClient jdbc;

  public AiOutbox(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void upsert(Entity entity, long id) {
    enqueue(entity, id, "UPSERT");
  }

  public void delete(Entity entity, long id) {
    enqueue(entity, id, "DELETE");
  }

  private void enqueue(Entity entity, long id, String op) {
    // Skip if an identical job is already waiting; the processor reads current text when it runs.
    jdbc.sql(
            "INSERT INTO ai_sync_queue (entity_type, entity_id, operation)"
                + " SELECT ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM ai_sync_queue"
                + " WHERE entity_type = ? AND entity_id = ? AND operation = ? AND status = 'PENDING')")
        .params(entity.name(), id, op, entity.name(), id, op)
        .update();
  }

  /** Enqueues every task and live comment of active projects (admin re-index). */
  public int enqueueAll() {
    int tasks =
        jdbc.sql(
                "INSERT INTO ai_sync_queue (entity_type, entity_id, operation)"
                    + " SELECT 'TASK', t.id, 'UPSERT' FROM tasks t JOIN projects p ON p.id = t.project_id"
                    + " WHERE p.status = 'ACTIVE'")
            .update();
    int comments =
        jdbc.sql(
                "INSERT INTO ai_sync_queue (entity_type, entity_id, operation)"
                    + " SELECT 'COMMENT', c.id, 'UPSERT' FROM comments c JOIN tasks t ON t.id = c.task_id"
                    + " JOIN projects p ON p.id = t.project_id"
                    + " WHERE p.status = 'ACTIVE' AND c.deleted_at IS NULL")
            .update();
    return tasks + comments;
  }
}
