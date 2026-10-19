#!/usr/bin/env bash
# Prepares a throwaway database for the AI-service tests without starting Spring Boot:
# creates the three roles, then applies backend/src/main/resources/db/migration/V*.sql in order as
# asp_owner and records them in a minimal flyway_schema_history (what FastAPI's schema check reads).
# Uses the superuser from PGHOST/PGPORT/PGUSER/PGPASSWORD. Target database: TEST_DB (default apismartpulse_test),
# which is dropped and recreated — never point this at real data.
set -euo pipefail
cd "$(dirname "$0")/../.."

db="${TEST_DB:-apismartpulse_test}"
: "${DB_OWNER_PASSWORD:=ci-owner-only}" "${DB_APP_PASSWORD:=ci-app-only}" "${DB_AI_PASSWORD:=ci-ai-only}"
case "$db" in *test*|*ci*) ;; *) echo "refusing to recreate '$db' (name must contain test or ci)" >&2; exit 1 ;; esac

psql -v ON_ERROR_STOP=1 -d postgres -q <<SQL
DROP DATABASE IF EXISTS "$db";
CREATE DATABASE "$db";
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'asp_owner') THEN CREATE ROLE asp_owner LOGIN; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'asp_app') THEN CREATE ROLE asp_app LOGIN; END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'asp_ai') THEN CREATE ROLE asp_ai LOGIN; END IF;
END \$\$;
ALTER ROLE asp_owner PASSWORD '$DB_OWNER_PASSWORD';
ALTER ROLE asp_app PASSWORD '$DB_APP_PASSWORD';
ALTER ROLE asp_ai PASSWORD '$DB_AI_PASSWORD';
SQL

psql -v ON_ERROR_STOP=1 -d "$db" -q <<'SQL'
CREATE EXTENSION IF NOT EXISTS vector;
ALTER SCHEMA public OWNER TO asp_owner;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SET ROLE asp_owner;
CREATE TABLE flyway_schema_history (
  installed_rank INT PRIMARY KEY, version VARCHAR(50), description VARCHAR(200), type VARCHAR(20),
  script VARCHAR(1000), checksum INT, installed_by VARCHAR(100), installed_on TIMESTAMP DEFAULT now(),
  execution_time INT, success BOOLEAN NOT NULL);
SQL

rank=1
for f in $(ls backend/src/main/resources/db/migration/V*__*.sql | sort -V); do
  name=$(basename "$f")
  version=${name%%__*}; version=${version#V}
  { echo "SET ROLE asp_owner;"; cat "$f"; } | psql -v ON_ERROR_STOP=1 -d "$db" -q
  psql -v ON_ERROR_STOP=1 -d "$db" -q -c "SET ROLE asp_owner; INSERT INTO flyway_schema_history VALUES ($rank, '$version', '$name', 'SQL', '$name', 0, 'ci', now(), 0, true)"
  rank=$((rank + 1))
done
echo "migrated $db to V$version"
