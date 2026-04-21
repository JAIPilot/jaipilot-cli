#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

RELEASE_TAG="action-v1"
REMOTE_NAME="origin"
MAIN_BRANCH="main"
UPSERT_GH_RELEASE=1
RELEASE_SKIP_REASON=""
DRY_RUN=0

usage() {
  cat <<'EOF'
Usage: scripts/release-push.sh [options]

Force-updates and pushes the main action rolling release tag (`action-v1`)
to `origin/main`, then optionally creates/updates the matching GitHub release.

Options:
  --skip-release       Skip GitHub release create/edit step
  --dry-run            Print planned actions without mutating git/GitHub
  -h, --help           Show this help text

Notes:
  - If `gh` is not installed, release upsert is skipped automatically.
EOF
}

die() {
  echo "$1" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

validate_release_tag() {
  printf '%s' "$1" | grep -Eq '^action-v[0-9]+$' ||
    die "Invalid release tag '$1'. Use format action-v<major> (example: action-v1)."
}

run_or_print() {
  if [ "$DRY_RUN" -eq 1 ]; then
    echo "[dry-run] $*"
  else
    "$@"
  fi
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --skip-release)
      UPSERT_GH_RELEASE=0
      RELEASE_SKIP_REASON="--skip-release"
      shift
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "Unknown option: $1"
      ;;
  esac
done

require_command git
require_command grep
require_command date

cd "$REPO_ROOT"
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || die "Not inside a git repository."
git remote get-url "$REMOTE_NAME" >/dev/null 2>&1 || die "Remote '$REMOTE_NAME' not found."

validate_release_tag "$RELEASE_TAG"
git fetch --prune "$REMOTE_NAME" "$MAIN_BRANCH" >/dev/null 2>&1 ||
  die "Failed to fetch ${REMOTE_NAME}/${MAIN_BRANCH}."
TARGET_COMMIT=$(git rev-parse --verify "refs/remotes/${REMOTE_NAME}/${MAIN_BRANCH}^{commit}" 2>/dev/null) ||
  die "Could not resolve ${REMOTE_NAME}/${MAIN_BRANCH} to a commit."

if [ "$UPSERT_GH_RELEASE" -eq 1 ]; then
  if ! command -v gh >/dev/null 2>&1; then
    echo "Warning: gh is not installed. Skipping GitHub release upsert." >&2
    UPSERT_GH_RELEASE=0
    RELEASE_SKIP_REASON="gh not installed"
  elif [ "$DRY_RUN" -eq 0 ]; then
    gh auth status >/dev/null 2>&1 || die "gh is not authenticated. Run: gh auth login"
  fi
fi

TAG_MESSAGE="Action release ${RELEASE_TAG}"
run_or_print git tag -fa "$RELEASE_TAG" "$TARGET_COMMIT" -m "$TAG_MESSAGE"
run_or_print git push "$REMOTE_NAME" "refs/tags/${RELEASE_TAG}" --force

if [ "$UPSERT_GH_RELEASE" -eq 1 ]; then
  TARGET_SHORT=$(git rev-parse --short "$TARGET_COMMIT")
  RELEASE_TITLE="JAIPilot Action ${RELEASE_TAG}"
  RELEASE_NOTES="Rolling action-channel release for ${RELEASE_TAG}. Updated from commit ${TARGET_SHORT} on $(date -u +'%Y-%m-%d %H:%M:%S UTC')."

  if [ "$DRY_RUN" -eq 1 ]; then
    echo "[dry-run] gh release view ${RELEASE_TAG}"
    echo "[dry-run] gh release edit ${RELEASE_TAG} --target ${TARGET_COMMIT} --title '${RELEASE_TITLE}' --notes '<generated>' --prerelease"
    echo "[dry-run] gh release create ${RELEASE_TAG} --target ${TARGET_COMMIT} --title '${RELEASE_TITLE}' --notes '<generated>' --prerelease --latest=false"
  else
    if gh release view "$RELEASE_TAG" >/dev/null 2>&1; then
      gh release edit "$RELEASE_TAG" \
        --target "$TARGET_COMMIT" \
        --title "$RELEASE_TITLE" \
        --notes "$RELEASE_NOTES" \
        --prerelease
    else
      gh release create "$RELEASE_TAG" \
        --target "$TARGET_COMMIT" \
        --title "$RELEASE_TITLE" \
        --notes "$RELEASE_NOTES" \
        --prerelease \
        --latest=false
    fi
  fi
fi

echo "Published action release tag."
echo "  Tag: ${RELEASE_TAG}"
echo "  Target: ${TARGET_COMMIT}"
echo "  Source branch: ${REMOTE_NAME}/${MAIN_BRANCH}"
if [ "$UPSERT_GH_RELEASE" -eq 1 ]; then
  echo "  GitHub release: upserted"
else
  if [ -n "$RELEASE_SKIP_REASON" ]; then
    echo "  GitHub release: skipped (${RELEASE_SKIP_REASON})"
  else
    echo "  GitHub release: skipped"
  fi
fi
