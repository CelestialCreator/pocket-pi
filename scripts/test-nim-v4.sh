#!/usr/bin/env bash
# v4: NIM via models.json (the way you configure it on your host) — no
# pi-nvidia-nim extension, no OPENAI_BASE_URL hack. Just a provider entry
# in ~/.pi/agent/models.json and NVIDIA_API_KEY in env.
set -euo pipefail

export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
APP="/data/data/com.termux"
RUNAS_ENV="env LD_LIBRARY_PATH=$APP/files/usr/lib LD_PRELOAD=$APP/files/usr/lib/libtermux-exec-ld-preload.so HOME=$APP/files/home PATH=$APP/files/usr/bin"

KEY="${NIM_API_KEY:-}"
if [ -z "$KEY" ]; then
  read -s -p "NIM API key (nvapi-...): " KEY; echo
fi

echo "==> 1/5 uninstall the pi-nvidia-nim extension we mistakenly registered"
adb shell "run-as com.termux $RUNAS_ENV $APP/files/usr/bin/bash -c \
  'pi remove npm:pi-nvidia-nim 2>&1 | tail -3 ; npm uninstall -g pi-nvidia-nim 2>&1 | tail -3'" || true

echo "==> 2/5 push the corrected models.json (provider 'nvidia' with apiKey=NVIDIA_API_KEY)"
TMP="$(mktemp /tmp/pp-models.XXXXXX.json)"
trap 'rm -f "$TMP"' EXIT
cp /Users/macbookpro/Documents/work/zosma/agent/termux/config/models.json "$TMP"
cat "$TMP" | adb shell "run-as com.termux sh -c \
  'cd /data/data/com.termux && cat > files/home/.pi/agent/models.json && cat files/home/.pi/agent/models.json | head -20'"

echo "==> 3/5 plant NIM key + verify file contents"
printf '%s' "$KEY" | adb shell "run-as com.termux sh -c \
  'cd /data/data/com.termux && mkdir -p files/home/.config/nvidia && cat > files/home/.config/nvidia/api-key && chmod 600 files/home/.config/nvidia/api-key && wc -c < files/home/.config/nvidia/api-key'"

echo "==> 4/5 list models — should now show the 5 NVIDIA entries"
adb shell "run-as com.termux $RUNAS_ENV NVIDIA_API_KEY='$KEY' \
  $APP/files/usr/bin/bash -c 'pi --list-models 2>&1 | head -20'"

echo
echo "==> 5/5 smoke prompt"
adb shell "run-as com.termux $RUNAS_ENV NVIDIA_API_KEY='$KEY' \
  $APP/files/usr/bin/bash -c \
  'timeout 60 pi -p --no-session --model nvidia/meta/llama-3.3-70b-instruct \"Reply with exactly: OK\" 2>&1 | tail -5'"

unset KEY
echo
echo "==> Done. If step 5 prints OK, the stack works. The Bootstrapper.kt change"
echo "    already wired NVIDIA_API_KEY into Pi's spawn env, so the in-app chat"
echo "    will pick up the same key automatically after the next APK reinstall."
