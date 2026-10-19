#!/usr/bin/env bash
# CI must never call a paid or live LLM (NFR-013): fail if any workflow or CI compose file selects
# a real provider or references a provider key secret.
set -euo pipefail
cd "$(dirname "$0")/../.."

files=$(ls .github/workflows/*.yml docker-compose.ci.yml 2>/dev/null)
if grep -nEi 'LLM_PROVIDER[:=[:space:]]+"?(groq|gemini)' $files; then
  echo "::error::A CI file selects a live LLM provider. Use LLM_PROVIDER=mock."
  exit 1
fi
if grep -nE 'secrets\.(GROQ|GEMINI)_API_KEY' $files; then
  echo "::error::A CI file references a live LLM API key secret."
  exit 1
fi
echo "CI uses mock/none LLM providers only."
