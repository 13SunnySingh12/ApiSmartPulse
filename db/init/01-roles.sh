#!/usr/bin/env bash
# Local only. Runs once, when the Postgres volume is first created (docker-entrypoint-initdb.d).
# Creates the three least-privilege roles (§6.4). Passwords come from the container environment;
# none are committed. Table grants are applied later by Flyway (V2__roles_and_grants.sql).
set -euo pipefail

: "${DB_OWNER_PASSWORD:?DB_OWNER_PASSWORD is required}"
: "${DB_APP_PASSWORD:?DB_APP_PASSWORD is required}"
: "${DB_AI_PASSWORD:?DB_AI_PASSWORD is required}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v owner_pw="$DB_OWNER_PASSWORD" -v app_pw="$DB_APP_PASSWORD" -v ai_pw="$DB_AI_PASSWORD" <<'SQL'
CREATE ROLE asp_owner LOGIN PASSWORD :'owner_pw';
CREATE ROLE asp_app   LOGIN PASSWORD :'app_pw';
CREATE ROLE asp_ai    LOGIN PASSWORD :'ai_pw';
CREATE EXTENSION IF NOT EXISTS vector;
ALTER SCHEMA public OWNER TO asp_owner;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO asp_app, asp_ai;
SQL
