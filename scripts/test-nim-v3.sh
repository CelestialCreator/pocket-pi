#!/usr/bin/env bash
# v3: Pi extensions need `pi install` to register, not just npm-install.
# Also check the bundled providers.md to get authoritative provider/model
# naming.
set -euo pipefail

export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
APP="/data/data/com.termux"
RUNAS_ENV="env LD_LIBRARY_PATH=$APP/files/usr/lib LD_PRELOAD=$APP/files/usr/lib/libtermux-exec-ld-preload.so HOME=$APP/files/home PATH=$APP/files/usr/bin"

KEY="${NIM_API_KEY:-}"
if [ -z "$KEY" ]; then
  read -s -p "Paste your NIM API key (nvapi-...): " KEY; echo
fi

echo "==> 1/6 read pi's bundled providers.md (NVIDIA section)"
adb shell "run-as com.termux $RUNAS_ENV $APP/files/usr/bin/bash -c \
  'grep -i -A 20 -E \"nvidia|nim\" $APP/files/usr/lib/node_modules/@mariozechner/pi-coding-agent/docs/providers.md 2>&1 | head -40'"

echo
echo "==> 2/6 register pi-nvidia-nim with Pi (this writes to ~/.pi/settings.json)"
adb shell "run-as com.termux $RUNAS_ENV $APP/files/usr/bin/bash -c \
  'pi install npm:pi-nvidia-nim 2>&1 | tail -10'"

echo
echo "==> 3/6 confirm extension is now loaded"
adb shell "run-as com.termux $RUNAS_ENV $APP/files/usr/bin/bash -c \
  'pi list 2>&1 | head -20'"

echo
echo "==> 4/6 (re-)plant NIM key + also export as multiple env-var spellings"
printf '%s' "$KEY" | adb shell "run-as com.termux sh -c \
  'cd /data/data/com.termux && mkdir -p files/home/.config/nvidia && cat > files/home/.config/nvidia/api-key && chmod 600 files/home/.config/nvidia/api-key'"

echo "==> 5/6 list models — should now show NVIDIA models"
adb shell "run-as com.termux $RUNAS_ENV \
  NVIDIA_API_KEY='$KEY' NVIDIA_NIM_API_KEY='$KEY' \
  $APP/files/usr/bin/bash -c \
  'pi --list-models 2>&1 | head -30'"

echo
echo "==> 6/6 smoke prompt"
adb shell "run-as com.termux $RUNAS_ENV \
  NVIDIA_API_KEY='$KEY' NVIDIA_NIM_API_KEY='$KEY' \
  $APP/files/usr/bin/bash -c \
  'echo \"--- as: --provider nvidia ---\"; \
   timeout 60 pi -p --no-session --provider nvidia --model meta/llama-3.3-70b-instruct \"Reply with exactly: OK\" 2>&1 | tail -5; \
   echo; echo \"--- as: --provider nvidia-nim ---\"; \
   timeout 60 pi -p --no-session --provider nvidia-nim --model meta/llama-3.3-70b-instruct \"Reply with exactly: OK\" 2>&1 | tail -5'"

unset KEY
echo
echo "==> Done. Look at step 1 (provider docs), step 5 (list-models), and step 6 (smoke)."
