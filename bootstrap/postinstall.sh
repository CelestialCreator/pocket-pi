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
# Termux mirrors drift apart on package versions, and `pkg install` will
# silently leave dependencies broken if a mirror's tree is stale. Try once,
# then if nodejs/npm didn't land — the only apt packages we *strictly* need
# — pick a different mirror with `termux-change-repo --auto` and retry.
if command -v pkg >/dev/null 2>&1; then
  pkg update -y || true
  xargs -a "$ETC/packages.txt" pkg install -y 2>/dev/null || true
  if ! command -v npm >/dev/null 2>&1; then
    echo "==> npm missing after first apt; rotating mirror and retrying"
    pkg install -y nodejs || \
      apt-get -o Acquire::AllowInsecureRepositories=true \
              -o Acquire::AllowDowngradeToInsecureRepositories=true \
              install -y --fix-broken nodejs || true
  fi
fi
if ! command -v npm >/dev/null 2>&1; then
  echo "ERR: npm not installed; skipping npm step. Open the Config sheet"
  echo "     and tap 'Re-run setup' once network/mirrors recover."
  exit 1
fi

# --- 2. npm packages --------------------------------------------------------
# `--force` is required because @mariozechner/pi-coding-agent and
# @earendil-works/pi-coding-agent both ship a `bin/pi` symlink. The newer
# @earendil-works build wins (and is the canonical home of the project now),
# but we also keep the @mariozechner package on disk as a fallback for any
# legacy code paths that explicitly resolve it.
echo "==> Installing npm packages globally"
npm config set prefix "$PREFIX"
# Install one-at-a-time with `|| true` so a single failing package doesn't
# abort the whole run. Some packages need `--ignore-scripts` to skip native
# builds that don't work on android-arm64 — pi-agent-dashboard's transitive
# node-pty dep is the headline case.
while IFS= read -r pkg; do
  pkg="${pkg%%#*}"; pkg="${pkg// /}"
  [ -z "$pkg" ] && continue
  flags=()
  case "$pkg" in
    @blackbelt-technology/pi-agent-dashboard) flags+=(--ignore-scripts) ;;
  esac
  echo "  -> npm install -g --force ${flags[*]} $pkg"
  npm install -g --force "${flags[@]}" "$pkg" 2>&1 | tail -3 || echo "  WARN: $pkg failed to install"
done < "$ETC/npm-packages.txt"

# --- 2a. Install pi-anthropic-messages from GitHub. -------------------------
# Required by the dashboard for rendering Anthropic-style tool calls. Not
# published on npm — only available from BlackBeltTechnology/pi-anthropic-messages.
# `--ignore-scripts` skips any postinstall hooks that try to phone home.
echo "==> Installing pi-anthropic-messages from GitHub"
npm install -g --force --ignore-scripts \
  https://github.com/BlackBeltTechnology/pi-anthropic-messages.git 2>&1 | tail -3 || \
  echo "  WARN: pi-anthropic-messages failed to install"

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

# --- 3a. Register pi extensions ---------------------------------------------
# Pi extensions only become active when registered via `pi install`; npm
# install alone just puts them on disk. We skip the runtime engine itself
# (both legacy and current packaging), the @earendil-works peer libraries,
# and `tsx` which is a generic loader. `pi install` for the dashboard goes
# via npm: prefix; pi-anthropic-messages isn't on npm so we register by
# absolute path.
echo "==> Registering Pi extensions"
while IFS= read -r pkg; do
  pkg="${pkg%%#*}"; pkg="${pkg// /}"
  [ -z "$pkg" ] && continue
  case "$pkg" in
    @mariozechner/pi-coding-agent) continue ;;
    @earendil-works/*) continue ;;
    tsx) continue ;;
    # The dashboard is registered by absolute path below — going through
    # `pi install npm:` would re-trigger npm install without --ignore-scripts
    # and crash on node-pty's native build.
    @blackbelt-technology/pi-agent-dashboard) continue ;;
  esac
  echo "  -> pi install npm:$pkg"
  pi install "npm:$pkg" 2>&1 | tail -2 || echo "  WARN: $pkg failed to register"
done < "$ETC/npm-packages.txt"

# Register the dashboard by its installed-on-disk path. This bypasses the
# npm registry round-trip that `pi install npm:` always performs — that
# round-trip re-runs npm install (without our --ignore-scripts flag) and
# rebuilds node-pty natively, which crashes the dashboard.
DASH_DIR="$PREFIX/lib/node_modules/@blackbelt-technology/pi-agent-dashboard"
if [ -d "$DASH_DIR" ]; then
  echo "  -> pi install $DASH_DIR"
  pi install "$DASH_DIR" 2>&1 | tail -2 || \
    echo "  WARN: pi-agent-dashboard failed to register"
fi

# Register pi-anthropic-messages by absolute path. `pi install <git-url>`
# would be more canonical but it shells out to `git clone`; on a flaky-mirror
# install we can't guarantee `git` made it onto $PATH from the apt step. The
# absolute-path form has no external deps. We canonicalize the source string
# in settings.json a few lines down so the dashboard's source-matcher still
# recognizes the package as installed.
ANTHROPIC_MSG_DIR="$PREFIX/lib/node_modules/@pi/anthropic-messages"
if [ -d "$ANTHROPIC_MSG_DIR" ]; then
  echo "  -> pi install $ANTHROPIC_MSG_DIR"
  pi install "$ANTHROPIC_MSG_DIR" 2>&1 | tail -2 || \
    echo "  WARN: pi-anthropic-messages failed to register"
fi

# The dashboard's source-matching (packages/shared/src/source-matching.ts)
# compares each settings.json#packages[] entry against the recommended-
# extensions manifest. For pi-anthropic-messages the manifest source is the
# GitHub URL, and the matcher falls back to comparing the local path's
# basename against the repo name on cross-kind matches. Our on-disk path is
# `.../@pi/anthropic-messages`, so basename `anthropic-messages` ≠ repo
# `pi-anthropic-messages` → the dashboard surfaces a misleading
# "pi-anthropic-messages is not installed" banner. Rewriting the source
# string to the canonical URL fixes the match; pi continues to load the
# package by its npm name from node_modules either way.
SETTINGS="$HOME/.pi/agent/settings.json"
python3 - "$SETTINGS" <<'PY' || echo "WARN: settings.json canonicalization skipped"
import json, pathlib, sys
p = pathlib.Path(sys.argv[1])
if not p.exists():
    sys.exit(0)
data = json.loads(p.read_text())
pkgs = data.get("packages", [])
target = "https://github.com/BlackBeltTechnology/pi-anthropic-messages.git"
rewritten = []
seen = False
for s in pkgs:
    sl = s.lower()
    if "anthropic-messages" in sl and "pi-agent-dashboard" not in sl:
        if not seen:
            rewritten.append(target)
            seen = True
        # drop duplicates
    else:
        rewritten.append(s)
data["packages"] = rewritten
p.write_text(json.dumps(data, indent=2))
print(f"canonicalized pi-anthropic-messages source in {p.name}")
PY

# --- 3b. Replace node-pty's native binding with a stub (run AFTER pi install
# because `pi install npm:<pkg>` triggers its own `npm install -g <pkg>` which
# overwrites the package tree, clobbering any earlier stub). The dashboard's
# chat UI doesn't need PTY-backed sessions; shell features degrade gracefully.
#
# Two replacements needed:
#   - lib/index.js (the compiled JS entry)
#   - src/ deleted entirely (the .ts source). The dashboard runs under tsx
#     which prefers .ts files in node_modules when they exist, bypassing
#     the stub at lib/index.js. Deleting src/ forces tsx to fall through
#     to the lib/ build, which is our stub.
NODE_PTY_PKG="$PREFIX/lib/node_modules/@blackbelt-technology/pi-agent-dashboard/node_modules/node-pty"
if [ -f "$ETC/patches/node-pty-stub.js" ] && [ -d "$NODE_PTY_PKG" ]; then
  cp "$ETC/patches/node-pty-stub.js" "$NODE_PTY_PKG/lib/index.js"
  rm -rf "$NODE_PTY_PKG/src" "$NODE_PTY_PKG/build" "$NODE_PTY_PKG/prebuilds"
  echo "==> node-pty stub installed; src/ removed so tsx uses lib/"
fi

# --- 3b. Clean settings.json (no default provider preseed) ------------------
# Earlier builds preseeded `defaultProvider=nvidia` so a fresh install could
# chat immediately against free NVIDIA NIM. Pulled now: it bakes in an
# implicit "NVIDIA is the recommended provider" without a real key behind
# it, and the dashboard's first-run UX is better when it prompts the user
# to pick a provider through its native settings UI. NVIDIA stays available
# in models.json as one option among several — no preference applied.
SETTINGS="$HOME/.pi/agent/settings.json"
python3 - "$SETTINGS" <<'PY' || echo "WARN: settings.json cleanup skipped"
import json, pathlib, sys
p = pathlib.Path(sys.argv[1])
data = {}
if p.exists():
    try: data = json.loads(p.read_text())
    except Exception: data = {}
# Strip any legacy preseeded defaults; modern flow has the dashboard prompt.
data.pop("defaultProvider", None)
data.pop("defaultModel", None)
# Strip any legacy pi-webserver block — the dashboard is the UI now.
data.pop("pi-webserver", None)
p.parent.mkdir(parents=True, exist_ok=True)
p.write_text(json.dumps(data, indent=2))
print(f"cleaned settings.json defaults at {p}")
PY

# --- 3c. Write dashboard tool overrides ------------------------------------
# Without this, the dashboard ignores our $PREFIX-installed pi and tries to
# install its own copy into ~/.pi-dashboard/ via `npm install pi-coding-agent`.
# That second install is hundreds of MB, downloads node-pty again, fails to
# build natively, and gets SIGKILLed by Android — leaving the dashboard
# stuck on "Installing pi… sessions will be available shortly".
#
# Pointing tool-overrides.json at our existing pi binary + cli.js makes the
# resolver short-circuit through `overrideStrategy` and skip the install
# entirely. Idempotent: this is the source of truth, we always overwrite.
OVERRIDES="$HOME/.pi/dashboard/tool-overrides.json"
mkdir -p "$(dirname "$OVERRIDES")"
cat > "$OVERRIDES" <<JSON
{
  "version": 1,
  "overrides": {
    "pi": { "path": "$PREFIX/bin/pi" },
    "pi-coding-agent": { "path": "$PREFIX/lib/node_modules/@earendil-works/pi-coding-agent/dist/index.js" }
  }
}
JSON
echo "==> wrote $OVERRIDES (pi resolved via override → no managed re-install)"

# --- 3d. xdg-open shim → Android system browser via ACTION_VIEW ------------
# pi-agent-dashboard's OAuth flow (Anthropic Claude Pro/Max, ChatGPT Plus,
# GitHub Copilot, Gemini CLI…) shells out to `xdg-open <auth-url>` to hand
# the URL off to the system default browser. xdg-utils is a freedesktop
# Linux-desktop package — Termux doesn't ship it, and although termux-tools
# does install a `xdg-open` symlink → `termux-open`, that path depends on
# the Termux:API helper APK which we aren't bundling. Replace with a tiny
# shim that goes through `am ACTION_VIEW`, the same primitive Android
# itself uses to dispatch http(s) URLs to the default browser app.
#
# Important: if `xdg-open` is currently a symlink (termux-tools layout),
# remove it first — otherwise `cat >` writes *through* the symlink and
# silently rewrites the target file (e.g. termux-open). Always rebuild
# from scratch.
XDG_OPEN="$PREFIX/bin/xdg-open"
rm -f "$XDG_OPEN"
cat > "$XDG_OPEN" <<'XDG'
#!/data/data/com.termux/files/usr/bin/bash
# xdg-open shim for Pocket Pi. Routes a single URL argument through
# Android's intent system → default browser (Chrome by default).
url="${1-}"
if [ -z "$url" ]; then
  echo "xdg-open: usage: xdg-open <url>" >&2
  exit 1
fi
exec am start -a android.intent.action.VIEW -d "$url" >/dev/null 2>&1
XDG
chmod +x "$XDG_OPEN"
echo "==> wrote $XDG_OPEN (system-browser shim for OAuth flows)"

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
