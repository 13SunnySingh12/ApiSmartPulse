#!/usr/bin/env bash
# Writes .env.neon: overrides that point the local Compose stack at a Neon branch and switch sign-in
# to Neon Auth. Values that aren't secret (hosts, Auth URL) come from arguments; the three database
# role passwords are generated here and never printed. Run once per branch, then create the roles
# with `python scripts/neon-roles-sql.py | <run in the Neon SQL editor or via the Neon MCP>`.
#
#   bash scripts/make-neon-env.sh <endpoint-host> <neon-auth-base-url> > .env.neon
set -euo pipefail

host="${1:?usage: make-neon-env.sh <endpoint-host> <neon-auth-base-url>}"
auth_url="${2:?usage: make-neon-env.sh <endpoint-host> <neon-auth-base-url>}"
case "$auth_url" in https://*) ;; *) echo "Auth URL must be https" >&2; exit 1 ;; esac
origin="$(printf '%s' "$auth_url" | sed -E 's#^(https://[^/]+).*#\1#')"
pw() { openssl rand -base64 36 | tr -d '/+=\n' | cut -c1-40; }

cat <<EOF
# Neon overrides (gitignored). Loaded after .env by docker-compose.neon.yml.
APP_ENV=neon
AUTH_PROVIDER=neon
NEON_AUTH_URL=${auth_url}
NEON_AUTH_ORIGIN=${origin}
VITE_AUTH_PROVIDER=neon
VITE_NEON_AUTH_URL=${auth_url}
# Direct (non-pooled) endpoint: Flyway needs a session; pools stay small (Hikari 10, AI service 8).
JDBC_DATABASE_URL=jdbc:postgresql://${host}/neondb?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory
AI_DATABASE_URL=postgresql://${host}/neondb?sslmode=verify-full&sslrootcert=/etc/ssl/certs/ca-certificates.crt
DB_OWNER_PASSWORD=$(pw)
DB_APP_PASSWORD=$(pw)
DB_AI_PASSWORD=$(pw)
EOF
