#!/data/data/com.zosma.pocketpi/files/usr/bin/bash
# Pocket Pi first-run setup — runs inside the device's Termux environment
# after the APK extracts the bootstrap zip into $PREFIX. The Android service
# spawns this script and streams stdout/stderr to the onboarding UI.
#
# Idempotent: safe to re-run after a Pi or extension update.
set -euo pipefail

ETC="$PREFIX/etc/pocket-pi"
LOG="$HOME/.pi/agent/postinstall.log"
mkdir -p "$(dirname "$LOG")"
exec > >(tee -a "$LOG") 2>&1

echo "==> Pocket Pi postinstall starting at $(date -u +%FT%TZ)"

# --- 0. Seed $HOME from skel on first run (don't overwrite user changes) ----
SKEL="$ETC/skel"
if [ -d "$SKEL" ]; then
  echo "==> Seeding HOME from skel"
  # cp -Rn (no-clobber) keeps any user edits, but creates anything missing.
  # `find … | cpio -pdm` would be cleaner; cp -Rn works on Termux's coreutils.
  ( cd "$SKEL" && find . -type d -exec mkdir -p "$HOME/{}" \; )
  ( cd "$SKEL" && find . -type f -exec sh -c 'dst="$HOME/$1"; [ -e "$dst" ] || cp "$1" "$dst"' _ {} \; )
  chmod 600 "$HOME/.config/nvidia/api-key" 2>/dev/null || true
fi

# --- 1. apt packages --------------------------------------------------------
if command -v pkg >/dev/null 2>&1; then
  pkg update -y
  xargs -a "$ETC/packages.txt" pkg install -y 2>/dev/null || true
fi

# --- 2. npm packages --------------------------------------------------------
# `--force` is required because @mariozechner/pi-coding-agent and
# @earendil-works/pi-coding-agent both ship a `bin/pi` symlink. The newer
# @earendil-works build wins (and is the canonical home of the project now),
# but we also keep the @mariozechner package on disk as a fallback for any
# legacy code paths that explicitly resolve it.
echo "==> Installing npm packages globally"
npm config set prefix "$PREFIX"
xargs -a "$ETC/npm-packages.txt" npm install -g --force

# --- 3. pip packages (non-fatal: dspy depends on jiter which needs Rust to
#       build from source on aarch64-linux-android, and no prebuilt wheel
#       exists. The Python reflection backend will be unavailable but the
#       agent works fine without it — the TS backend via claude-bridge is
#       the primary path. Add `rust` to packages.txt if you want this.) --
echo "==> Installing pip packages (best-effort)"
set +e
python -m pip install --upgrade pip wheel >/dev/null
python -m pip install -r "$ETC/pip-packages.txt"
pip_status=$?
set -e
if [ $pip_status -ne 0 ]; then
  echo "WARN: pip install failed (likely jiter/rust missing). Python skill"
  echo "      reflection backend will be unavailable. Continuing."
fi

# --- 3a. Wire pi-webserver + pi-mobile (the WebView UI substrate) -----------
# Pi extensions only "register" via `pi install`; npm-install just puts them
# on disk. Settings.json must merge with whatever `pi install` already wrote.
echo "==> Registering Pi extensions"
# pi install npm:<pkg> is what *registers* an extension with Pi (writes the
# package name into settings.json `packages` and loads its tool manifest).
# `npm install -g` only puts the files on disk — without the pi install step
# the tools never show up. We skip:
#   - the runtime engine itself (both legacy and current packaging)
#   - @earendil-works/pi-* peer libraries used by pi-subagents; they are
#     plain npm modules, not Pi extensions, so `pi install` would fail.
while IFS= read -r pkg; do
  pkg="${pkg%%#*}"; pkg="${pkg// /}"
  [ -z "$pkg" ] && continue
  case "$pkg" in
    @mariozechner/pi-coding-agent) continue ;;
    @earendil-works/*) continue ;;
  esac
  echo "  -> pi install npm:$pkg"
  pi install "npm:$pkg" 2>&1 | tail -2 || echo "  WARN: $pkg failed to register"
done < "$ETC/npm-packages.txt"

SETTINGS="$HOME/.pi/agent/settings.json"
INFO="$HOME/.pi/agent/webserver-info.json"
PORT="${POCKET_PI_PORT:-4100}"
TOKEN="$(python3 -c 'import secrets; print(secrets.token_hex(24))')"
python3 - "$SETTINGS" "$PORT" "$TOKEN" <<'PY'
import json, os, sys, pathlib
path, port, token = sys.argv[1], int(sys.argv[2]), sys.argv[3]
p = pathlib.Path(path)
data = {}
if p.exists():
    try: data = json.loads(p.read_text())
    except Exception: data = {}
data["pi-webserver"] = {
    "autostart": True,
    "port": port,
    "apiToken": token,
    "apiReadToken": token,
}
# Pi reads defaultProvider/defaultModel from settings.json at boot and uses
# them as the active model in the chat. Without these, pi-mobile shows no
# model picker and prompts have nowhere to go. Set sensible defaults only
# if the user hasn't picked something already.
data.setdefault("defaultProvider", "nvidia")
data.setdefault("defaultModel", "qwen/qwen3-coder-480b-a35b-instruct")
p.write_text(json.dumps(data, indent=2))
print(f"merged pi-webserver block into {path}")
PY

cat > "$INFO" <<JSON
{"port":$PORT,"token":"$TOKEN","url":"http://127.0.0.1:$PORT/mobile"}
JSON
chmod 600 "$INFO"
echo "==> webserver-info.json written ($INFO)"

# --- 4. Apply hermes-evolve patch (re-include claude-bridge under -p) -------
echo "==> Applying hermes-evolve claude-bridge re-include patch"
bash "$ETC/patches/hermes-evolve-reapply-patch.sh" || {
  echo "WARN: hermes-evolve patch failed; the TS reflection backend will not"
  echo "      work with claude-bridge until this is rerun."
}

# --- 5. Wrap npm so post-update auto-reapplies the patch --------------------
WRAPPER="$PREFIX/bin/npm-pocket-wrapper"
if [ ! -f "$WRAPPER" ]; then
  cat > "$WRAPPER" <<'WRAP'
#!/data/data/com.zosma.pocketpi/files/usr/bin/bash
real_npm="$PREFIX/libexec/npm/bin/npm-cli.js"
node "$real_npm" "$@"
status=$?
case " $* " in
  *" update "*|*" install -g "*|*" i -g "*)
    bash "$PREFIX/etc/pocket-pi/patches/hermes-evolve-reapply-patch.sh" >/dev/null 2>&1 || true
    ;;
esac
exit $status
WRAP
  chmod +x "$WRAPPER"
fi

# --- 6. Sanity check --------------------------------------------------------
echo "==> Verifying Pi install"
pi --version || { echo "ERR: pi not on PATH"; exit 1; }
node -e "console.log('node ok ' + process.version)"
python -c "import dspy; print('dspy ok ' + dspy.__version__)" || \
  echo "WARN: dspy not importable; Python reflection backend will be unavailable"

echo "==> Postinstall complete at $(date -u +%FT%TZ)"
