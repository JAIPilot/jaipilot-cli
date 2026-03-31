#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
TARGET_DIR="$REPO_ROOT/target"
DIST_DIR="$TARGET_DIR/distributions"
STAGING_ROOT="$TARGET_DIR/bundled-dist"
VERSION=""
CLASSIFIER=""

usage() {
  cat <<'EOF'
Usage: scripts/build-bundled-dist.sh --version <version> [--classifier <platform>]

Builds a platform-specific JAIPilot distribution with a bundled Java runtime image.
EOF
}

die() {
  echo "$1" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

resolve_os() {
  case "$(uname -s)" in
    Linux) printf 'linux\n' ;;
    Darwin) printf 'macos\n' ;;
    *) die "Unsupported operating system for bundled runtime packaging: $(uname -s)" ;;
  esac
}

resolve_arch() {
  case "$(uname -m)" in
    x86_64|amd64) printf 'x64\n' ;;
    arm64|aarch64) printf 'aarch64\n' ;;
    *) die "Unsupported architecture for bundled runtime packaging: $(uname -m)" ;;
  esac
}

resolve_classifier() {
  if [ -n "$CLASSIFIER" ]; then
    printf '%s\n' "$CLASSIFIER"
    return
  fi
  printf '%s-%s\n' "$(resolve_os)" "$(resolve_arch)"
}

append_module() {
  modules=$1
  candidate=$2
  case ",$modules," in
    *",$candidate,"*) printf '%s\n' "$modules" ;;
    *)
      if [ -n "$modules" ]; then
        printf '%s,%s\n' "$modules" "$candidate"
      else
        printf '%s\n' "$candidate"
      fi
      ;;
  esac
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --version)
      [ "$#" -ge 2 ] || die "Missing value for --version"
      VERSION=${2#v}
      shift 2
      ;;
    --classifier)
      [ "$#" -ge 2 ] || die "Missing value for --classifier"
      CLASSIFIER=$2
      shift 2
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

[ -n "$VERSION" ] || die "--version is required"

require_command jdeps
require_command jlink
require_command tar

SHADOW_JAR="$TARGET_DIR/jaipilot-cli-$VERSION-all.jar"
[ -f "$SHADOW_JAR" ] || die "Missing shaded jar: $SHADOW_JAR. Run ./mvnw package first."

PLATFORM_CLASSIFIER=$(resolve_classifier)
MODULES=$(jdeps --multi-release 17 --ignore-missing-deps --print-module-deps "$SHADOW_JAR")
MODULES=$(append_module "$MODULES" "jdk.crypto.ec")

APP_NAME="jaipilot-$VERSION-$PLATFORM_CLASSIFIER"
APP_DIR="$STAGING_ROOT/$APP_NAME"
RUNTIME_DIR="$TARGET_DIR/runtime-image-$PLATFORM_CLASSIFIER"
ARCHIVE_PATH="$DIST_DIR/$APP_NAME.tar.gz"

rm -rf "$APP_DIR" "$RUNTIME_DIR"
mkdir -p "$APP_DIR/bin" "$APP_DIR/lib" "$DIST_DIR"

jlink \
  --add-modules "$MODULES" \
  --output "$RUNTIME_DIR" \
  --strip-debug \
  --no-header-files \
  --no-man-pages \
  --compress=zip-6

cp "$REPO_ROOT/src/main/dist/bin/jaipilot" "$APP_DIR/bin/jaipilot"
chmod +x "$APP_DIR/bin/jaipilot"
cp "$SHADOW_JAR" "$APP_DIR/lib/jaipilot.jar"
cp -R "$RUNTIME_DIR" "$APP_DIR/runtime"

rm -f "$ARCHIVE_PATH"
COPYFILE_DISABLE=1 COPY_EXTENDED_ATTRIBUTES_DISABLE=1 tar -czf "$ARCHIVE_PATH" -C "$STAGING_ROOT" "$APP_NAME"

echo "Built bundled distribution"
echo "  Classifier: $PLATFORM_CLASSIFIER"
echo "  Runtime modules: $MODULES"
echo "  Archive: $ARCHIVE_PATH"
