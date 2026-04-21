#!/usr/bin/env bash
set -euo pipefail

jaipilot_auth_token="${1:-}"
default_backend_url="https://otxfylhjrlaesjagfhfi.supabase.co"
jaipilot_backend_url="${JAIPILOT_BACKEND_URL:-$default_backend_url}"
jaipilot_backend_url="${jaipilot_backend_url%/}"
jaipilot_token_endpoint="${JAIPILOT_TOKEN_ENDPOINT:-${jaipilot_backend_url}/functions/v1/github-actions-token}"
jaipilot_oidc_audience="${JAIPILOT_OIDC_AUDIENCE:-jaipilot-github-app}"

log_warn() {
  echo "WARN: $*" >&2
}

can_exchange_with_oidc() {
  [ -n "${ACTIONS_ID_TOKEN_REQUEST_URL:-}" ] &&
    [ -n "${ACTIONS_ID_TOKEN_REQUEST_TOKEN:-}" ] &&
    [ -n "${GITHUB_REPOSITORY:-}" ] &&
    [ -n "${GITHUB_REPOSITORY_ID:-}" ] &&
    [ -n "${GITHUB_REPOSITORY_OWNER:-}" ] &&
    command -v curl >/dev/null 2>&1 &&
    command -v jq >/dev/null 2>&1
}

exchange_runtime_token_with_oidc() {
  local oidc_url=""
  local oidc_response=""
  local github_oidc_token=""
  local exchange_payload=""
  local token_response=""
  local runtime_token=""

  if [[ "${ACTIONS_ID_TOKEN_REQUEST_URL}" == *\?* ]]; then
    oidc_url="${ACTIONS_ID_TOKEN_REQUEST_URL}&audience=${jaipilot_oidc_audience}"
  else
    oidc_url="${ACTIONS_ID_TOKEN_REQUEST_URL}?audience=${jaipilot_oidc_audience}"
  fi

  oidc_response="$(curl -fsSL \
    -H "Authorization: Bearer ${ACTIONS_ID_TOKEN_REQUEST_TOKEN}" \
    "$oidc_url")" || return 1

  github_oidc_token="$(printf '%s' "$oidc_response" | jq -r '.value // empty')"
  if [ -z "$github_oidc_token" ]; then
    return 1
  fi

  exchange_payload="$(jq -cn \
    --arg repository "${GITHUB_REPOSITORY}" \
    --arg repository_id "${GITHUB_REPOSITORY_ID}" \
    --arg repository_owner "${GITHUB_REPOSITORY_OWNER}" \
    --arg run_id "${GITHUB_RUN_ID:-}" \
    --arg run_attempt "${GITHUB_RUN_ATTEMPT:-}" \
    --arg actor "${GITHUB_ACTOR:-}" \
    '{repository: $repository, repository_id: $repository_id, repository_owner: $repository_owner, run_id: $run_id, run_attempt: $run_attempt, actor: $actor}')"

  token_response="$(curl -fsSL -X POST "$jaipilot_token_endpoint" \
    -H "Authorization: Bearer ${github_oidc_token}" \
    -H "Content-Type: application/json" \
    -d "$exchange_payload")" || return 1

  runtime_token="$(printf '%s' "$token_response" | jq -r '.jaipilot_token // empty')"
  if [ -z "$runtime_token" ]; then
    return 1
  fi

  printf '%s\n' "$runtime_token"
  return 0
}

if can_exchange_with_oidc; then
  if oidc_runtime_token="$(exchange_runtime_token_with_oidc)"; then
    printf '%s\n' "$oidc_runtime_token"
    exit 0
  fi
  log_warn "OIDC token exchange failed. Falling back to provided credentials."
fi

if [ -n "$jaipilot_auth_token" ]; then
  printf '%s\n' "$jaipilot_auth_token"
  exit 0
fi

if [ -n "${JAIPILOT_AUTH_TOKEN:-}" ]; then
  printf '%s\n' "$JAIPILOT_AUTH_TOKEN"
  exit 0
fi

if [ -n "${JAIPILOT_LICENSE_KEY:-}" ]; then
  printf '%s\n' "$JAIPILOT_LICENSE_KEY"
  exit 0
fi

echo "Missing JAIPilot credentials. Provide 'jaipilot-auth-token', set JAIPILOT_AUTH_TOKEN/JAIPILOT_LICENSE_KEY, or grant 'id-token: write' for OIDC token exchange." >&2
exit 1
