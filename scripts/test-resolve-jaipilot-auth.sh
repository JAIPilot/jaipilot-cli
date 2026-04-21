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

output="$(JAIPILOT_AUTH_TOKEN= JAIPILOT_LICENSE_KEY=license-token "$SCRIPT_PATH" "")"
assert_equals "license-token" "$output" "license key env should be accepted as fallback"

tmp_bin_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_bin_dir"' EXIT
cat > "${tmp_bin_dir}/curl" <<'MOCKCURL'
#!/usr/bin/env bash
set -euo pipefail

url=""
for arg in "$@"; do
  case "$arg" in
    http://*|https://*)
      url="$arg"
      ;;
  esac
done

case "$url" in
  *token.actions.githubusercontent.com*|*idtoken*)
    printf '{"value":"github-oidc-token"}'
    ;;
  https://backend.example/functions/v1/github-actions-token)
    printf '{"jaipilot_token":"runtime-token-from-oidc","expires_at":"2099-01-01T00:00:00Z"}'
    ;;
  *)
    echo "unexpected curl url: $url" >&2
    exit 1
    ;;
esac
MOCKCURL
chmod +x "${tmp_bin_dir}/curl"

cat > "${tmp_bin_dir}/jq" <<'MOCKJQ'
#!/usr/bin/env bash
set -euo pipefail

extract_json_field() {
  local key="$1"
  sed -n "s/.*\"${key}\":\"\\([^\"]*\\)\".*/\\1/p"
}

if [ "${1:-}" = "-cn" ]; then
  shift
  repository=""
  repository_id=""
  repository_owner=""
  run_id=""
  run_attempt=""
  actor=""
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --arg)
        key="${2:-}"
        value="${3:-}"
        case "$key" in
          repository) repository="$value" ;;
          repository_id) repository_id="$value" ;;
          repository_owner) repository_owner="$value" ;;
          run_id) run_id="$value" ;;
          run_attempt) run_attempt="$value" ;;
          actor) actor="$value" ;;
        esac
        shift 3
        ;;
      *)
        shift
        ;;
    esac
  done
  printf '{"repository":"%s","repository_id":"%s","repository_owner":"%s","run_id":"%s","run_attempt":"%s","actor":"%s"}' \
    "$repository" "$repository_id" "$repository_owner" "$run_id" "$run_attempt" "$actor"
  exit 0
fi

if [ "${1:-}" = "-r" ]; then
  filter="${2:-}"
  input="$(cat)"
  case "$filter" in
    ".value // empty")
      printf '%s' "$input" | extract_json_field "value"
      ;;
    ".jaipilot_token // empty")
      printf '%s' "$input" | extract_json_field "jaipilot_token"
      ;;
    *)
      echo "unsupported jq filter: $filter" >&2
      exit 1
      ;;
  esac
  exit 0
fi

echo "unsupported jq invocation: $*" >&2
exit 1
MOCKJQ
chmod +x "${tmp_bin_dir}/jq"

output="$(
  PATH="${tmp_bin_dir}:$PATH" \
  ACTIONS_ID_TOKEN_REQUEST_URL="https://token.actions.githubusercontent.com/idtoken?api-version=2.0" \
  ACTIONS_ID_TOKEN_REQUEST_TOKEN="ghs_request_token" \
  GITHUB_REPOSITORY="example/repo" \
  GITHUB_REPOSITORY_ID="123456" \
  GITHUB_REPOSITORY_OWNER="example" \
  GITHUB_RUN_ID="42" \
  GITHUB_RUN_ATTEMPT="1" \
  GITHUB_ACTOR="octocat" \
  JAIPILOT_TOKEN_ENDPOINT="https://backend.example/functions/v1/github-actions-token" \
  JAIPILOT_AUTH_TOKEN="legacy-token" \
  "$SCRIPT_PATH" ""
)"
assert_equals "runtime-token-from-oidc" "$output" "OIDC exchange should be preferred when available"

set +e
missing_output="$(JAIPILOT_AUTH_TOKEN= JAIPILOT_LICENSE_KEY= "$SCRIPT_PATH" "" 2>&1)"
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

echo "PASS: resolve-jaipilot-auth behavior verified"
