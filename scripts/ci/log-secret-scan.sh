#!/usr/bin/env bash
# QA-SEC-12 / NFR-006: after a full run, service logs must not contain passwords, JWTs, refresh
# tokens or API keys. Usage: log-secret-scan.sh <log-file>   (reads secret values from the env).
set -euo pipefail
log="${1:?usage: log-secret-scan.sh <log-file>}"
[ -s "$log" ] || { echo "::error::$log is missing or empty"; exit 1; }

status=0
pattern() {
  if grep -qE -e "$1" "$log"; then
    echo "::error::Logs match forbidden pattern: $2"
    status=1
  fi
}
# Matches are reported by name only, never printed.
value() {
  local v="${!1:-}"
  if [ -n "$v" ] && grep -qF -e "$v" "$log"; then
    echo "::error::Logs contain the value of $1"
    status=1
  fi
}

pattern 'eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.' 'JWT'
pattern 'asp_refresh=[A-Za-z0-9_-]{16,}' 'refresh token cookie'
pattern 'asp_[0-9a-f]{8}_[A-Za-z0-9_-]{43}' 'full API key'
pattern 'gsk_[A-Za-z0-9]{20,}' 'Groq API key'
pattern '"password"[[:space:]]*:[[:space:]]*"[^"]' 'password field'
for name in SEED_DEMO_PASSWORD JWT_SECRET INTERNAL_SERVICE_TOKEN POSTGRES_SUPERUSER_PASSWORD \
  DB_OWNER_PASSWORD DB_APP_PASSWORD DB_AI_PASSWORD GROQ_API_KEY; do
  value "$name"
done
[ "$status" -eq 0 ] && echo "No secrets found in $log ($(wc -l < "$log") lines)."
exit "$status"
