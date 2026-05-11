#!/usr/bin/env bash
# Build a Termux bootstrap zip pre-populated with Pocket Pi payload:
#   - postinstall script + npm/pip package lists
#   - the hermes-evolve patch
#   - a "skel" directory mirroring the user's $HOME, copied into place by
#     postinstall on first launch (Termux's bootstrap zip extracts only into
#     $PREFIX, never into $HOME, so HOME content has to be seeded from skel).
#
# Output: bootstrap/dist/bootstrap-${ARCH}.zip
#
# Usage:
#   ./build-bootstrap.sh aarch64       # default phones
set -euo pipefail

ARCH="${1:-aarch64}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
WORK="$HERE/work/$ARCH"
DIST="$HERE/dist"
mkdir -p "$WORK" "$DIST"

case "$ARCH" in
  aarch64|arm|i686|x86_64) ;;
  *) echo "unsupported arch: $ARCH (expected aarch64|arm|i686|x86_64)" >&2; exit 1 ;;
esac

echo "==> Fetching Termux base bootstrap for $ARCH"
TERMUX_BOOTSTRAP_URL="${TERMUX_BOOTSTRAP_URL:-https://github.com/termux/termux-packages/releases/latest/download/bootstrap-${ARCH}.zip}"
if [ ! -f "$WORK/base.zip" ]; then
  curl -fsSL -o "$WORK/base.zip" "$TERMUX_BOOTSTRAP_URL"
fi

# The base zip's contents map directly to $PREFIX. We unpack it into a staging
# tree, layer our payload at the right paths inside that tree, then re-zip.
rm -rf "$WORK/prefix"
mkdir -p "$WORK/prefix"
( cd "$WORK/prefix" && unzip -q "$WORK/base.zip" )

echo "==> Layering Pocket Pi payload"

# 1. Pocket Pi metadata + scripts go under $PREFIX/etc/pocket-pi/
mkdir -p "$WORK/prefix/etc/pocket-pi"
cp "$HERE/postinstall.sh"   "$WORK/prefix/etc/pocket-pi/postinstall.sh"
cp "$HERE/packages.txt"     "$WORK/prefix/etc/pocket-pi/packages.txt"
cp "$HERE/npm-packages.txt" "$WORK/prefix/etc/pocket-pi/npm-packages.txt"
cp "$HERE/pip-packages.txt" "$WORK/prefix/etc/pocket-pi/pip-packages.txt"
cp -R "$HERE/patches"       "$WORK/prefix/etc/pocket-pi/patches"
chmod +x "$WORK/prefix/etc/pocket-pi/postinstall.sh"
chmod +x "$WORK/prefix/etc/pocket-pi/patches/"*.sh

# 2. HOME skel — copied into the user's $HOME on first launch by postinstall.
SKEL="$WORK/prefix/etc/pocket-pi/skel"
mkdir -p "$SKEL/.pi/agent/skills/proposed" "$SKEL/.config/nvidia"
cp "$ROOT/config/AGENTS.md"          "$SKEL/.pi/agent/AGENTS.md"
cp "$ROOT/config/claude-bridge.json" "$SKEL/.pi/agent/claude-bridge.json"
cp "$ROOT/config/models.json"        "$SKEL/.pi/agent/models.json"
cp -R "$ROOT/skills/."               "$SKEL/.pi/agent/skills/"
: > "$SKEL/.config/nvidia/api-key"
chmod 600 "$SKEL/.config/nvidia/api-key"

# 3. Repack
OUT="$DIST/bootstrap-${ARCH}.zip"
rm -f "$OUT"
( cd "$WORK/prefix" && zip -qry "$OUT" . )

echo "==> Done: $OUT ($(du -h "$OUT" | cut -f1))"
