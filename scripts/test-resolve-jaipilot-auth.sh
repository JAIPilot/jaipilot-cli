#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPT_PATH="${SCRIPT_DIR}/resolve-jaipilot-auth.sh"

assert_equals() {
  local expected="$1"
  local actual="$2"
  local message="$3"
  if [ "$expected" != "$actual" ]; then
    echo "FAIL: ${message}" >&2
    echo "  expected: ${expected}" >&2
    echo "  actual:   ${actual}" >&2
    exit 1
  fi
}

output="$(JAIPILOT_AUTH_TOKEN=env-token "$SCRIPT_PATH" auth-token)"
assert_equals "auth-token" "$output" "auth token input should win over env fallback"

output="$(JAIPILOT_AUTH_TOKEN=env-token "$SCRIPT_PATH" "")"
assert_equals "env-token" "$output" "env token should be final fallback"

set +e
missing_output="$(JAIPILOT_AUTH_TOKEN= "$SCRIPT_PATH" "" 2>&1)"
missing_status=$?
set -e

if [ "$missing_status" -eq 0 ]; then
  echo "FAIL: missing credential path should fail" >&2
  exit 1
fi

case "$missing_output" in
  *"Missing JAIPilot credentials"*)
    ;;
  *)
    echo "FAIL: missing credential message did not match expectation" >&2
    echo "Actual: $missing_output" >&2
    exit 1
    ;;
esac

echo "PASS: resolve-jaipilot-auth auth-token-only behavior verified"
