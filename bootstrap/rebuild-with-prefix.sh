#!/usr/bin/env bash
# Rebuild the Termux bootstrap zip with a custom prefix path baked into all
# compiled binaries (apt, dpkg, etc). This is the only way for Pocket Pi to
# ship as com.zosma.pocketpi without colliding with an existing Termux
# install on the same device.
#
# Cost: long — 4–12+ hours on Apple Silicon (Docker emulates x86_64 via QEMU
# for the toolchain). Run overnight. Reuses Docker layers across runs.
#
# Output: bootstrap/dist/bootstrap-aarch64.zip with prefix
#         /data/data/com.zosma.pocketpi/files/usr baked in.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
TPK="$HERE/termux-packages"

[ -d "$TPK" ] || {
  echo "ERR: $TPK is missing. Clone it first:"
  echo "  git clone --depth 1 https://github.com/termux/termux-packages $TPK"
  exit 1
}

PKG="${POCKET_PI_PACKAGE:-com.zosma.pocketpi}"
BASE="/data/data/$PKG/files"

# Patch termux-packages' properties.sh to use our prefix. This file is the
# single source of truth for $TERMUX_PREFIX/$TERMUX_HOME during the build.
PROPS="$TPK/scripts/properties.sh"
cp "$PROPS" "$PROPS.bak.$(date +%s)"
python3 - "$PROPS" "$PKG" "$BASE" <<'PY'
import sys, re
path, pkg, base = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path, encoding="utf-8").read()

def setv(text, name, val):
    pat = re.compile(rf'^(\s*)(?:export\s+)?{re.escape(name)}\s*=.*$', re.MULTILINE)
    if pat.search(text):
        return pat.sub(f'export {name}="{val}"', text, count=1)
    return text + f'\nexport {name}="{val}"\n'

s = setv(s, "TERMUX_APP_PACKAGE",   pkg)
s = setv(s, "TERMUX_BASE_DIR",      base)
s = setv(s, "TERMUX_PREFIX",        f"{base}/usr")
s = setv(s, "TERMUX_ANDROID_HOME",  f"{base}/home")
s = setv(s, "TERMUX__PREFIX",       f"{base}/usr")
open(path, "w", encoding="utf-8").write(s)
print("patched", path)
PY

cd "$TPK"
echo "==> Running build-bootstraps.sh in Docker for aarch64 (long)"
./scripts/run-docker.sh ./scripts/build-bootstraps.sh \
  -a aarch64 \
  --architectures aarch64 \
  --add "nodejs python git ripgrep openssl termux-api"

OUT_ZIP="$TPK/bootstrap-aarch64.zip"
[ -f "$OUT_ZIP" ] || { echo "ERR: build did not produce $OUT_ZIP"; exit 1; }

mkdir -p "$HERE/dist"
cp "$OUT_ZIP" "$HERE/dist/bootstrap-aarch64.zip"
echo "==> Custom-prefix bootstrap at $HERE/dist/bootstrap-aarch64.zip"
echo "    Flip android/app/build.gradle.kts applicationId back to '$PKG' and rebuild the APK."
