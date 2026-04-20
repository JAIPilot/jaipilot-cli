#!/usr/bin/env bash
set -euo pipefail

jaipilot_auth_token="${1:-}"

if [ -n "$jaipilot_auth_token" ]; then
  printf '%s\n' "$jaipilot_auth_token"
  exit 0
fi

if [ -n "${JAIPILOT_AUTH_TOKEN:-}" ]; then
  printf '%s\n' "$JAIPILOT_AUTH_TOKEN"
  exit 0
fi

echo "Missing JAIPilot credentials. Provide 'jaipilot-auth-token' or set JAIPILOT_AUTH_TOKEN." >&2
exit 1
