#!/usr/bin/env bash
# SEC-040 / QA-SEC-15: the built frontend must not contain provider keys or server secrets.
# Usage: bundle-secret-check.sh <dist-dir>
set -euo pipefail
dist="${1:?usage: bundle-secret-check.sh <dist-dir>}"
[ -d "$dist" ] || { echo "::error::$dist does not exist"; exit 1; }

status=0
check() {
  if grep -RIlE -e "$1" "$dist"; then
    echo "::error::Build output matches forbidden pattern: $2"
    status=1
  fi
}
check 'gsk_[A-Za-z0-9]{20,}' 'Groq API key'
check 'AIza[0-9A-Za-z_-]{35}' 'Google API key'
check 'asp_[0-9a-f]{8}_[A-Za-z0-9_-]{43}' 'ApiSmartPulse API key'
check '(GROQ|GEMINI)_API_KEY|JWT_SECRET|INTERNAL_SERVICE_TOKEN|DB_[A-Z]+_PASSWORD|SEED_DEMO_PASSWORD' 'server-side variable name'
check '-----BEGIN [A-Z ]*PRIVATE KEY-----' 'private key'
[ "$status" -eq 0 ] && echo "No secrets found in $dist."
exit "$status"
