-- Least-privilege grants (§6.4). Roles are created outside migrations (db/init/01-roles.sh locally)
-- so no passwords are ever committed. This migration runs as asp_owner.
GRANT USAGE ON SCHEMA public TO asp_app, asp_ai;

-- Spring Boot runtime
GRANT SELECT, INSERT, UPDATE, DELETE ON
    users, refresh_tokens, projects, project_members, tasks, subtasks, labels, task_labels,
    comments, task_dependencies, task_predictions, api_keys, ai_sync_queue
    TO asp_app;
GRANT SELECT, INSERT ON task_events TO asp_app;           -- append-only history
GRANT SELECT, DELETE ON task_embeddings TO asp_app;       -- removes embeddings with their task
GRANT SELECT ON ml_model_versions TO asp_app;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO asp_app;

-- FastAPI runtime: read task data, own embeddings and model registry, never secrets
GRANT SELECT ON
    projects, tasks, subtasks, labels, task_labels, comments, task_dependencies, task_events, project_members
    TO asp_ai;
GRANT SELECT (id, name) ON users TO asp_ai;
GRANT SELECT, INSERT, UPDATE, DELETE ON task_embeddings, ml_model_versions TO asp_ai;
GRANT SELECT ON flyway_schema_history TO asp_ai;          -- schema-version check at startup
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO asp_ai;
