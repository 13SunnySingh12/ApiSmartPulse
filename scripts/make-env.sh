#!/usr/bin/env bash
# Writes a .env to stdout from .env.example, replacing every <…> placeholder with a random value.
# Usage:  bash scripts/make-env.sh > .env          (local: real embeddings, LLM disabled)
#         bash scripts/make-env.sh --ci > .env     (CI/E2E: mock LLM, fake embeddings)
# Generated values are never echoed elsewhere; in GitHub Actions they are masked.
set -euo pipefail
cd "$(dirname "$0")/.."

ci=false
[[ "${1:-}" == "--ci" ]] && ci=true

rand() { openssl rand -base64 48 | tr -d '\n/+=' | cut -c1-48; }

while IFS= read -r line || [[ -n "$line" ]]; do
  if [[ "$line" =~ ^([A-Z0-9_]+)=\<.*\>$ ]]; then
    name="${BASH_REMATCH[1]}"
    if [[ "$name" == "SEED_DEMO_PASSWORD" ]]; then
      value="Pulse-$(openssl rand -hex 6)-7"
    else
      value="$(rand)"
    fi
    [[ -n "${GITHUB_ACTIONS:-}" ]] && echo "::add-mask::$value" >&2
    line="$name=$value"
  fi
  if $ci; then
    case "$line" in
      LLM_PROVIDER=*) line="LLM_PROVIDER=mock" ;;
      EMBEDDINGS_BACKEND=*) line="EMBEDDINGS_BACKEND=fake" ;;
      GROQ_API_KEY=*) line="GROQ_API_KEY=" ;;
    esac
  fi
  printf '%s\n' "$line"
done < .env.example
