#!/usr/bin/env bash
# Fails the build if any workflow gains a deployment, release, publish, registry login or image
# push step (§11.10 guard, T-805). Deployment is out of scope for this project.
set -euo pipefail
cd "$(dirname "$0")/../.."

patterns=(
  'docker[[:space:]]+(login|push)'
  'docker/login-action'
  'docker/build-push-action'
  'push:[[:space:]]*true'
  'gh[[:space:]]+release'
  'actions/create-release'
  'softprops/action-gh-release'
  'npm[[:space:]]+publish'
  'twine[[:space:]]+upload'
  'mvn[^\n]*[[:space:]]deploy'
  'mvnw[^\n]*[[:space:]]deploy'
  'kubectl'
  'helm[[:space:]]'
  'terraform'
  'aws-actions/'
  'azure/(webapps|login)'
  'google-github-actions/'
  'vercel'
  'netlify'
  'heroku'
  'flyctl'
  'railway'
  'render\.com'
  'environment:[[:space:]]*(production|prod)'
  'deployments:[[:space:]]*write'
  'packages:[[:space:]]*write'
  'contents:[[:space:]]*write'
)

status=0
for p in "${patterns[@]}"; do
  if grep -RInEi --include='*.yml' --include='*.yaml' -e "$p" .github \
      | grep -vE '^\.github/workflows/[^:]+:[0-9]+:[[:space:]]*#'; then
    echo "::error::Forbidden deployment/publish pattern found: $p"
    status=1
  fi
done
[ "$status" -eq 0 ] && echo "No deployment, release, publish or registry push steps found."
exit "$status"
