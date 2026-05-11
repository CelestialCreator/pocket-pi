#!/usr/bin/env bash
# Plant the Claude Code OAuth credentials onto the running emulator,
# trigger Claude Code's postinstall to fetch its native binary, run a
# smoke prompt, then relaunch the Pocket Pi app and tail its logs.
#
# Run this from your Mac (where the emulator is reachable via adb).
set -euo pipefail

export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
APP="/data/data/com.termux"
RUNAS_ENV="env LD_LIBRARY_PATH=$APP/files/usr/lib LD_PRELOAD=$APP/files/usr/lib/libtermux-exec-ld-preload.so HOME=$APP/files/home PATH=$APP/files/usr/bin"

# Sanity check — emulator must be online.
if ! adb get-state >/dev/null 2>&1; then
  echo "ERR: no adb device. Start the emulator first:"
  echo "  nohup \$ANDROID_HOME/emulator/emulator -avd pocket -no-snapshot -no-window > /tmp/emu.log 2>&1 &"
  exit 1
fi

echo "==> 1/5 staging credentials.json on host"
TMP="$(mktemp /tmp/pp-creds.XXXXXX.json)"
trap 'rm -f "$TMP"' EXIT
cat > "$TMP" <<'JSON'
{"claudeAiOauth":{"accessToken":"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","refreshToken":"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","expiresAt":1777493883862,"scopes":["user:file_upload","user:inference","user:mcp_servers","user:profile","user:sessions:claude_code"],"subscriptionType":"max","rateLimitTier":"default_claude_max_20x"}}
JSON

echo "==> 2/5 streaming credentials into app's private home (skip /sdcard)"
# run-as drops into / by default (read-only). Always cd into the app's
# data dir first, then use paths relative to it.
cat "$TMP" | adb shell "run-as com.termux sh -c 'cd /data/data/com.termux && mkdir -p files/home/.claude && cat > files/home/.claude/.credentials.json && chmod 600 files/home/.claude/.credentials.json && ls -la files/home/.claude/.credentials.json'"

echo "==> 3/5 running Claude Code postinstall (fetches native binary)"
INSTALL_CJS="$APP/files/usr/lib/node_modules/@anthropic-ai/claude-code/install.cjs"
adb shell "run-as com.termux $RUNAS_ENV $APP/files/usr/bin/bash -c \
  'node $INSTALL_CJS 2>&1 | tail -15'"

echo "==> 4/5 verifying claude --version + smoke prompt"
adb shell "run-as com.termux $RUNAS_ENV $APP/files/usr/bin/bash -c \
  'echo \"--- claude --version ---\"; claude --version 2>&1 | head -3; \
   echo \"--- smoke prompt ---\"; claude -p \"say only OK\" 2>&1 | head -5'"

echo "==> 5/5 restarting Pocket Pi app"
adb shell am force-stop com.termux
adb shell am start -n com.termux/com.zosma.pocketpi.MainActivity >/dev/null
sleep 4
adb logcat -d 2>&1 | grep -E "PiBridge|claude|FATAL|RuntimeException" | tail -10

echo
echo "==> Done. Tail live logs with:"
echo "    adb logcat | grep -E 'PiBridge|claude|FATAL'"
