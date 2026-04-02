#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/onboard-action-repo.sh \
    --repo <owner/repo> \
    [--action-repo <owner/repo>] \
    [--action-ref <ref>] \
    [--license-key <key>] \
    [--workflow-path <path>] \
    [--commit-message <message>] \
    [--dry-run]

Examples:
  scripts/onboard-action-repo.sh --repo my-org/spring-framework-petclinic --action-ref action-v1
  scripts/onboard-action-repo.sh --repo my-org/spring-framework-petclinic --license-key "$JAIPILOT_LICENSE_KEY"

Notes:
  - Requires GitHub CLI (`gh`) authenticated with permissions to edit target repo content.
  - If --license-key is omitted, the script does not change repository secrets.
USAGE
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

repo_from_remote() {
  local remote_url
  remote_url="$(git config --get remote.origin.url 2>/dev/null || true)"
  if [[ "$remote_url" =~ github\.com[:/]([^/]+/[^/.]+)(\.git)?$ ]]; then
    echo "${BASH_REMATCH[1]}"
    return 0
  fi
  return 1
}

TARGET_REPO=""
ACTION_REPO=""
ACTION_REF="action-v1"
LICENSE_KEY=""
WORKFLOW_PATH=".github/workflows/jaipilot-generate.yml"
COMMIT_MESSAGE="chore: add JAIPilot generate workflow"
DRY_RUN="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo)
      TARGET_REPO="${2:-}"
      shift 2
      ;;
    --action-repo)
      ACTION_REPO="${2:-}"
      shift 2
      ;;
    --action-ref)
      ACTION_REF="${2:-}"
      shift 2
      ;;
    --license-key)
      LICENSE_KEY="${2:-}"
      shift 2
      ;;
    --workflow-path)
      WORKFLOW_PATH="${2:-}"
      shift 2
      ;;
    --commit-message)
      COMMIT_MESSAGE="${2:-}"
      shift 2
      ;;
    --dry-run)
      DRY_RUN="true"
      shift 1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "$TARGET_REPO" ]]; then
  echo "--repo is required." >&2
  usage
  exit 1
fi

if [[ -z "$ACTION_REPO" ]]; then
  if ACTION_REPO="$(repo_from_remote)"; then
    :
  else
    echo "Could not infer --action-repo from git remote. Pass --action-repo explicitly." >&2
    exit 1
  fi
fi

require_cmd gh
require_cmd base64

if ! gh auth status >/dev/null 2>&1; then
  echo "gh is not authenticated. Run: gh auth login" >&2
  exit 1
fi

DEFAULT_BRANCH="$(gh api "repos/${TARGET_REPO}" --jq '.default_branch')"
ACTION_USES="${ACTION_REPO}@${ACTION_REF}"

WORKFLOW_CONTENT="$(cat <<'YAML'
name: JAIPilot Generate

on:
  pull_request:
    types:
      - opened
      - synchronize
      - reopened

permissions:
  contents: write
  pull-requests: write

concurrency:
  group: jaipilot-generate-${{ github.event.pull_request.number }}
  cancel-in-progress: true

jobs:
  generate-tests:
    if: ${{ github.actor != 'github-actions[bot]' && github.event.pull_request.head.repo.full_name == github.repository }}
    runs-on: ubuntu-latest
    steps:
      - name: Checkout PR branch
        uses: actions/checkout@v4
        with:
          ref: ${{ github.head_ref }}

      - name: Run JAIPilot generate and push changes
        uses: __ACTION_USES__
        with:
          jaipilot-license-key: ${{ secrets.JAIPILOT_LICENSE_KEY }}
YAML
)"

WORKFLOW_CONTENT="${WORKFLOW_CONTENT/__ACTION_USES__/$ACTION_USES}"
CONTENT_B64="$(printf '%s' "$WORKFLOW_CONTENT" | base64 | tr -d '\n')"

EXISTING_SHA="$(gh api "repos/${TARGET_REPO}/contents/${WORKFLOW_PATH}?ref=${DEFAULT_BRANCH}" --jq '.sha' 2>/dev/null || true)"

echo "Target repo: ${TARGET_REPO}"
echo "Default branch: ${DEFAULT_BRANCH}"
echo "Action reference: ${ACTION_USES}"
echo "Workflow path: ${WORKFLOW_PATH}"
if [[ -n "$EXISTING_SHA" ]]; then
  echo "Workflow exists already and will be updated."
else
  echo "Workflow does not exist and will be created."
fi

if [[ "$DRY_RUN" == "true" ]]; then
  echo
  echo "Dry run enabled. No changes were sent to GitHub."
  exit 0
fi

API_ARGS=(
  -X PUT
  "repos/${TARGET_REPO}/contents/${WORKFLOW_PATH}"
  -f "message=${COMMIT_MESSAGE}"
  -f "content=${CONTENT_B64}"
  -f "branch=${DEFAULT_BRANCH}"
)
if [[ -n "$EXISTING_SHA" ]]; then
  API_ARGS+=(-f "sha=${EXISTING_SHA}")
fi

gh api "${API_ARGS[@]}" >/dev/null
echo "Workflow committed: ${WORKFLOW_PATH}"

if [[ -n "$LICENSE_KEY" ]]; then
  printf '%s' "$LICENSE_KEY" | gh secret set JAIPILOT_LICENSE_KEY -R "$TARGET_REPO" -b-
  echo "Secret set: JAIPILOT_LICENSE_KEY"
else
  echo "Secret unchanged. To set it manually:"
  echo "  gh secret set JAIPILOT_LICENSE_KEY -R ${TARGET_REPO}"
fi

echo "Onboarding complete."
