#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
DIST_DIR="$REPO_ROOT/target/distributions"
SMOKE_DIR="$REPO_ROOT/target/smoke-install"
VERSION=""

usage() {
  cat <<'EOF'
Usage: scripts/smoke-test-install.sh [--version <version>]

Smoke-tests the install script by pointing it at a locally built JAIPilot tar.gz
archive and then running `jaipilot --version` from the installed location.
EOF
}

die() {
  echo "$1" >&2
  exit 1
}

compute_sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
    return
  fi
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
    return
  fi
  if command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 "$1" | awk '{print $NF}'
    return
  fi
  die "Required checksum tool not found: sha256sum, shasum, or openssl"
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --version)
      [ "$#" -ge 2 ] || die "Missing value for --version"
      VERSION=${2#v}
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

[ -d "$DIST_DIR" ] || die "Missing distribution directory: $DIST_DIR"

if [ -n "$VERSION" ]; then
  TAR_GZ="$DIST_DIR/jaipilot-$VERSION.tar.gz"
else
  TAR_GZ=$(ls -1t "$DIST_DIR"/jaipilot-*.tar.gz 2>/dev/null | head -n 1)
fi

[ -n "${TAR_GZ:-}" ] || die "Could not find a JAIPilot tar.gz distribution under $DIST_DIR"
[ -f "$TAR_GZ" ] || die "Missing distribution archive: $TAR_GZ"
CHECKSUM_FILE="$TAR_GZ.sha256"

printf '%s  %s\n' "$(compute_sha256 "$TAR_GZ")" "$(basename "$TAR_GZ")" > "$CHECKSUM_FILE"

rm -rf "$SMOKE_DIR"
mkdir -p "$SMOKE_DIR"

"$REPO_ROOT/install.sh" \
  --archive-url "file://$TAR_GZ" \
  --checksum-url "file://$CHECKSUM_FILE" \
  --bin-dir "$SMOKE_DIR/bin" \
  --lib-dir "$SMOKE_DIR/lib"

"$SMOKE_DIR/bin/jaipilot" --version

echo "Smoke-tested install script"
echo "  Archive: $TAR_GZ"
