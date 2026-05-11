#!/usr/bin/env bash
# Test the entire Pocket Pi stack against NVIDIA NIM (free-tier OpenAI-
# compatible inference). No Claude, no API-key billing, no native binaries.
#
# Steps:
#   1. Clean up the abandoned Claude artefacts
#   2. Prompt for your NIM key (or pull from $NIM_API_KEY)
#   3. Plant it at ~/.config/nvidia/api-key on the device
#   4. Smoke-test pi -p against NIM (OpenAI-compatible endpoint)
#   5. Restart the Pocket Pi app
#
# Run from your Mac while the emulator is online.
set -euo pipefail

export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
APP="/data/data/com.termux"
RUNAS_ENV="env LD_LIBRARY_PATH=$APP/files/usr/lib LD_PRELOAD=$APP/files/usr/lib/libtermux-exec-ld-preload.so HOME=$APP/files/home PATH=$APP/files/usr/bin"

if ! adb get-state >/dev/null 2>&1; then
  echo "ERR: no adb device. Start the emulator first."; exit 1
fi

echo "==> 1/5 cleanup: drop Claude OAuth file + uninstall claude/claude-bridge npm pkgs"
adb shell "run-as com.termux sh -c 'rm -f /data/data/com.termux/files/home/.claude/.credentials.json'" || true
adb shell "run-as com.termux $RUNAS_ENV $APP/files/usr/bin/bash -c \
  'npm uninstall -g pi-claude-bridge @anthropic-ai/claude-code 2>&1 | tail -3'" || true

echo "==> 2/5 NIM API key"
KEY="${NIM_API_KEY:-}"
if [ -z "$KEY" ]; then
  read -s -p "Paste your NVIDIA NIM API key (nvapi-...): " KEY; echo
fi
[ -z "$KEY" ] && { echo "ERR: empty NIM key"; exit 1; }

echo "==> 3/5 planting key at ~/.config/nvidia/api-key"
printf '%s' "$KEY" | adb shell "run-as com.termux sh -c \
  'cd /data/data/com.termux && mkdir -p files/home/.config/nvidia && cat > files/home/.config/nvidia/api-key && chmod 600 files/home/.config/nvidia/api-key && wc -c < files/home/.config/nvidia/api-key'"

echo "==> 4/5 smoke-testing Pi against NIM (Llama 3.3 70B)"
# Pi's OpenAI provider accepts OPENAI_BASE_URL to redirect at any
# OpenAI-compatible endpoint. NIM is OpenAI-compatible.
adb shell "run-as com.termux $RUNAS_ENV \
  OPENAI_API_KEY='$KEY' \
  OPENAI_BASE_URL='https://integrate.api.nvidia.com/v1' \
  $APP/files/usr/bin/bash -c \
  'echo \"--- pi -p smoke ---\"; \
   timeout 60 pi -p --no-session --provider openai --model meta/llama-3.3-70b-instruct \"Reply with exactly: OK\" 2>&1 | tail -10'"

echo "==> 5/5 restarting Pocket Pi app"
adb shell am force-stop com.termux
adb shell am start -n com.termux/com.zosma.pocketpi.MainActivity >/dev/null
sleep 4
adb logcat -d 2>&1 | grep -E "PiBridge|FATAL|RuntimeException|Setup failed" | tail -10

unset KEY
echo
echo "==> Done."
echo "    If step 4 returned 'OK', the stack works. Paste the NIM key into"
echo "    the in-app Settings → 'NVIDIA NIM' field next, and the chat will"
echo "    use it on its own."
