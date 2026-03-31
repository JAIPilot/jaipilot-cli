#!/usr/bin/env sh
set -eu

REPO="skrcode/jaipilot-cli"
PREFIX="${HOME}/.local"
BIN_DIR=""
LIB_DIR=""
VERSION=""
ARCHIVE_URL=""
CHECKSUM_URL=""

usage() {
  cat <<'EOF'
Usage: install.sh [options]

Installs the latest JAIPilot release with an archive checksum verification step.

Options:
  --version <version>      Install a specific release version.
  --archive-url <url>      Override the release archive URL. Intended for testing.
  --checksum-url <url>     Override the archive checksum URL. Intended for testing.
  --prefix <dir>           Installation prefix. Default: ~/.local
  --bin-dir <dir>          Explicit bin directory. Overrides --prefix/bin.
  --lib-dir <dir>          Explicit library directory. Overrides --prefix/share/jaipilot.
  -h, --help               Show this help text.
EOF
}

die() {
  echo "$1" >&2
  exit 1
}

strip_v() {
  case "$1" in
    v*) printf '%s\n' "${1#v}" ;;
    *) printf '%s\n' "$1" ;;
  esac
}

contains_path_entry() {
  case ":$PATH:" in
    *":$1:"*) return 0 ;;
    *) return 1 ;;
  esac
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

checksum_command() {
  if command -v sha256sum >/dev/null 2>&1; then
    printf 'sha256sum\n'
    return
  fi
  if command -v shasum >/dev/null 2>&1; then
    printf 'shasum\n'
    return
  fi
  if command -v openssl >/dev/null 2>&1; then
    printf 'openssl\n'
    return
  fi
  die "Required checksum tool not found: sha256sum, shasum, or openssl"
}

compute_sha256() {
  tool=$(checksum_command)
  case "$tool" in
    sha256sum)
      sha256sum "$1" | awk '{print tolower($1)}'
      ;;
    shasum)
      shasum -a 256 "$1" | awk '{print tolower($1)}'
      ;;
    openssl)
      openssl dgst -sha256 "$1" | awk '{print tolower($NF)}'
      ;;
  esac
}

resolve_latest_version() {
  json=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest")
  version=$(printf '%s' "$json" | tr -d '\n' | sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\(v\{0,1\}[^"]*\)".*/\1/p')
  [ -n "$version" ] || die "Failed to determine the latest JAIPilot version."
  strip_v "$version"
}

resolve_archive_url() {
  if [ -n "$ARCHIVE_URL" ]; then
    printf '%s\n' "$ARCHIVE_URL"
    return
  fi

  if [ -n "$VERSION" ]; then
    resolved_version=$VERSION
  else
    resolved_version=$(resolve_latest_version)
  fi
  printf 'https://github.com/%s/releases/download/v%s/jaipilot-%s.tar.gz\n' "$REPO" "$resolved_version" "$resolved_version"
}

resolve_checksum_url() {
  if [ -n "$CHECKSUM_URL" ]; then
    printf '%s\n' "$CHECKSUM_URL"
    return
  fi
  printf '%s.sha256\n' "$1"
}

read_expected_sha256() {
  expected=$(awk 'NF {print $1; exit}' "$1" | tr '[:upper:]' '[:lower:]')
  case "$expected" in
    [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]*)
      ;;
    *)
      die "Checksum file did not contain a SHA-256 digest: $1"
      ;;
  esac
  [ "${#expected}" -eq 64 ] || die "Checksum file did not contain a SHA-256 digest: $1"
  printf '%s\n' "$expected"
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --version)
      [ "$#" -ge 2 ] || die "Missing value for --version"
      VERSION=$(strip_v "$2")
      shift 2
      ;;
    --archive-url)
      [ "$#" -ge 2 ] || die "Missing value for --archive-url"
      ARCHIVE_URL=$2
      shift 2
      ;;
    --checksum-url)
      [ "$#" -ge 2 ] || die "Missing value for --checksum-url"
      CHECKSUM_URL=$2
      shift 2
      ;;
    --prefix)
      [ "$#" -ge 2 ] || die "Missing value for --prefix"
      PREFIX=$2
      shift 2
      ;;
    --bin-dir)
      [ "$#" -ge 2 ] || die "Missing value for --bin-dir"
      BIN_DIR=$2
      shift 2
      ;;
    --lib-dir)
      [ "$#" -ge 2 ] || die "Missing value for --lib-dir"
      LIB_DIR=$2
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

[ -n "$BIN_DIR" ] || BIN_DIR="${PREFIX}/bin"
[ -n "$LIB_DIR" ] || LIB_DIR="${PREFIX}/share/jaipilot"

require_command curl
require_command tar
require_command mktemp
require_command java

ARCHIVE_URL=$(resolve_archive_url)
CHECKSUM_URL=$(resolve_checksum_url "$ARCHIVE_URL")

TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT INT TERM

ARCHIVE_PATH="$TMP_DIR/jaipilot.tar.gz"
CHECKSUM_PATH="$TMP_DIR/jaipilot.tar.gz.sha256"
curl -fsSL "$ARCHIVE_URL" -o "$ARCHIVE_PATH"
curl -fsSL "$CHECKSUM_URL" -o "$CHECKSUM_PATH"

EXPECTED_SHA256=$(read_expected_sha256 "$CHECKSUM_PATH")
ACTUAL_SHA256=$(compute_sha256 "$ARCHIVE_PATH")
[ "$EXPECTED_SHA256" = "$ACTUAL_SHA256" ] || die "SHA-256 mismatch for downloaded archive."

tar -xzf "$ARCHIVE_PATH" -C "$TMP_DIR"

EXTRACTED_DIR=$(find "$TMP_DIR" -mindepth 1 -maxdepth 1 -type d | head -n 1)
[ -n "${EXTRACTED_DIR:-}" ] || die "Failed to unpack the JAIPilot archive."
[ -f "$EXTRACTED_DIR/lib/jaipilot.jar" ] || die "Downloaded archive is missing lib/jaipilot.jar."

mkdir -p "$BIN_DIR" "$LIB_DIR"
cp "$EXTRACTED_DIR/lib/jaipilot.jar" "$LIB_DIR/jaipilot.jar"

cat > "$BIN_DIR/jaipilot" <<EOF
#!/usr/bin/env sh
set -eu
exec java -jar "$LIB_DIR/jaipilot.jar" "\$@"
EOF

chmod +x "$BIN_DIR/jaipilot"

echo "Installed JAIPilot"
echo "  Archive: $ARCHIVE_URL"
echo "  SHA-256: $ACTUAL_SHA256"
echo "  Jar: $LIB_DIR/jaipilot.jar"
echo "  Launcher: $BIN_DIR/jaipilot"

if contains_path_entry "$BIN_DIR"; then
  echo "  PATH: $BIN_DIR is already on PATH"
else
  echo "  PATH: add $BIN_DIR to your PATH"
fi

echo
echo "You can now run:"
echo "  jaipilot --help"
