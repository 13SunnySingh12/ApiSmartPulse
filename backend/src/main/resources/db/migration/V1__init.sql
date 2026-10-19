-- ApiSmartPulse schema (§6.3). Enums are TEXT + CHECK; all timestamps timestamptz (UTC).
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE users (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email              TEXT        NOT NULL CHECK (length(email) <= 254),
    name               TEXT        NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
    password_hash      TEXT        NOT NULL,
    role               TEXT        NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('ADMIN', 'MANAGER', 'MEMBER')),
    active             BOOLEAN     NOT NULL DEFAULT TRUE,
    failed_login_count INT         NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0),
    locked_until       TIMESTAMPTZ,
    last_login_at      TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_users_email ON users (lower(email));

CREATE TABLE refresh_tokens (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash     CHAR(64)    NOT NULL UNIQUE,
    family_id      UUID        NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    revoked_at     TIMESTAMPTZ,
    replaced_by_id BIGINT REFERENCES refresh_tokens (id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);

CREATE TABLE projects (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    key              TEXT        NOT NULL UNIQUE CHECK (key ~ '^[A-Z]{2,6}$'),
    name             TEXT        NOT NULL CHECK (length(name) BETWEEN 3 AND 80),
    description      TEXT CHECK (length(description) <= 2000),
    status           TEXT        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    start_date       DATE,
    target_date      DATE,
    owner_id         BIGINT      NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    next_task_number INT         NOT NULL DEFAULT 1 CHECK (next_task_number >= 1),
    version          INT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at      TIMESTAMPTZ,
    CHECK (target_date IS NULL OR start_date IS NULL OR target_date >= start_date)
);

CREATE TABLE project_members (
    project_id     BIGINT       NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    user_id        BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    capacity_hours NUMERIC(4, 1) NOT NULL DEFAULT 40 CHECK (capacity_hours BETWEEN 1 AND 80),
    joined_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, user_id)
);
CREATE INDEX ix_project_members_user ON project_members (user_id);

CREATE TABLE tasks (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id     BIGINT      NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    number         INT         NOT NULL,
    title          TEXT        NOT NULL CHECK (length(title) BETWEEN 3 AND 200),
    description    TEXT CHECK (length(description) <= 10000),
    status         TEXT        NOT NULL DEFAULT 'TODO' CHECK (status IN ('TODO', 'IN_PROGRESS', 'DONE')),
    priority       TEXT        NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    due_date       DATE,
    assignee_id    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    creator_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    estimate_hours NUMERIC(5, 1) CHECK (estimate_hours BETWEEN 0.5 AND 400),
    actual_hours   NUMERIC(5, 1) CHECK (actual_hours BETWEEN 0.5 AND 400),
    started_at     TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ,
    version        INT         NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, number),
    CHECK (status <> 'DONE' OR completed_at IS NOT NULL)
);
CREATE INDEX ix_tasks_project_status ON tasks (project_id, status);
CREATE INDEX ix_tasks_project_assignee_open ON tasks (project_id, assignee_id) WHERE status <> 'DONE';
CREATE INDEX ix_tasks_project_due ON tasks (project_id, due_date);
CREATE INDEX ix_tasks_project_completed ON tasks (project_id, completed_at);
CREATE INDEX ix_tasks_fts ON tasks USING gin (to_tsvector('simple', title || ' ' || coalesce(description, '')));

CREATE TABLE subtasks (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id    BIGINT      NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    title      TEXT        NOT NULL CHECK (length(title) BETWEEN 1 AND 120),
    done       BOOLEAN     NOT NULL DEFAULT FALSE,
    position   INT         NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_subtasks_position UNIQUE (task_id, position) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX ix_subtasks_task ON subtasks (task_id);

CREATE TABLE labels (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id BIGINT      NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    name       TEXT        NOT NULL CHECK (length(name) BETWEEN 1 AND 30),
    color      TEXT        NOT NULL CHECK (color IN ('slate', 'violet', 'blue', 'teal', 'green', 'amber', 'orange', 'rose')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_labels_project_name ON labels (project_id, lower(name));

CREATE TABLE task_labels (
    task_id  BIGINT NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    label_id BIGINT NOT NULL REFERENCES labels (id) ON DELETE CASCADE,
    PRIMARY KEY (task_id, label_id)
);
CREATE INDEX ix_task_labels_label ON task_labels (label_id);

CREATE TABLE comments (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id    BIGINT      NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    author_id  BIGINT      NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    body       TEXT        NOT NULL CHECK (length(body) <= 5000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    -- soft delete replaces the body with an empty string
    CHECK (length(body) >= 1 OR deleted_at IS NOT NULL)
);
CREATE INDEX ix_comments_task_created ON comments (task_id, created_at);

CREATE TABLE task_dependencies (
    task_id         BIGINT      NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    blocker_task_id BIGINT      NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    created_by      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (task_id, blocker_task_id),
    CHECK (task_id <> blocker_task_id)
);
CREATE INDEX ix_task_dependencies_blocker ON task_dependencies (blocker_task_id);

CREATE TABLE task_events (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id     BIGINT      NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    project_id  BIGINT      NOT NULL,
    event_type  TEXT        NOT NULL CHECK (event_type IN ('CREATED', 'STATUS_CHANGED', 'ASSIGNEE_CHANGED',
                                                          'ESTIMATE_CHANGED', 'DUE_DATE_CHANGED',
                                                          'AI_SUGGESTION_APPLIED', 'DEPENDENCY_REMOVED')),
    from_value  TEXT,
    to_value    TEXT,
    actor_id    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_task_events_project_time ON task_events (project_id, occurred_at);
CREATE INDEX ix_task_events_task_time ON task_events (task_id, occurred_at);

CREATE TABLE task_predictions (
    task_id             BIGINT PRIMARY KEY REFERENCES tasks (id) ON DELETE CASCADE,
    status              TEXT NOT NULL CHECK (status IN ('PENDING', 'OK', 'FAILED')),
    predicted_hours     NUMERIC(5, 1),
    overdue_probability NUMERIC(4, 3) CHECK (overdue_probability BETWEEN 0 AND 1),
    risk_level          TEXT CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'NA')),
    suggested_priority  TEXT CHECK (suggested_priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    priority_confidence NUMERIC(4, 3) CHECK (priority_confidence BETWEEN 0 AND 1),
    source              TEXT CHECK (source IN ('MODEL', 'HEURISTIC')),
    model_version       TEXT,
    attempts            INT  NOT NULL DEFAULT 0,
    computed_at         TIMESTAMPTZ
);
CREATE INDEX ix_task_predictions_risk ON task_predictions (risk_level) WHERE status = 'OK';

CREATE TABLE api_keys (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name          TEXT        NOT NULL CHECK (length(name) BETWEEN 1 AND 50),
    prefix        CHAR(8)     NOT NULL UNIQUE,
    key_hash      CHAR(64)    NOT NULL UNIQUE,
    expires_at    TIMESTAMPTZ,
    revoked_at    TIMESTAMPTZ,
    last_used_at  TIMESTAMPTZ,
    request_count BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (expires_at IS NULL OR expires_at <= created_at + INTERVAL '365 days')
);
CREATE INDEX ix_api_keys_user ON api_keys (user_id);
CREATE UNIQUE INDEX uq_api_keys_active_name ON api_keys (user_id, lower(name)) WHERE revoked_at IS NULL;

CREATE TABLE task_embeddings (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id   BIGINT      NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    task_id      BIGINT      NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    source_type  TEXT        NOT NULL CHECK (source_type IN ('TASK', 'COMMENT')),
    source_id    BIGINT      NOT NULL,
    chunk_index  SMALLINT    NOT NULL CHECK (chunk_index >= 0),
    content      TEXT        NOT NULL,
    content_hash CHAR(64)    NOT NULL,
    embedding    vector(384) NOT NULL,
    source_date  TIMESTAMPTZ NOT NULL,
    model_name   TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_embedding_source UNIQUE (source_type, source_id, chunk_index)
);
CREATE INDEX ix_embeddings_project_date ON task_embeddings (project_id, source_date);
CREATE INDEX ix_embeddings_task ON task_embeddings (task_id);

CREATE TABLE ml_model_versions (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    model_name    TEXT    NOT NULL CHECK (model_name IN ('ESTIMATOR', 'RISK', 'PRIORITY')),
    version       TEXT    NOT NULL,
    status        TEXT    NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED_INSUFFICIENT_DATA')),
    is_active     BOOLEAN NOT NULL DEFAULT FALSE,
    training_rows INT,
    metrics       JSONB,
    artifact_path TEXT,
    error_message TEXT,
    triggered_by  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_ml_model_active ON ml_model_versions (model_name) WHERE is_active;

CREATE TABLE ai_sync_queue (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entity_type     TEXT        NOT NULL CHECK (entity_type IN ('TASK', 'COMMENT')),
    entity_id       BIGINT      NOT NULL,
    operation       TEXT        NOT NULL CHECK (operation IN ('UPSERT', 'DELETE')),
    status          TEXT        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT CHECK (length(last_error) <= 500),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_ai_sync_queue_due ON ai_sync_queue (status, next_attempt_at);
