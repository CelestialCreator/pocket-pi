#!/usr/bin/env bash
# v2: Pi's built-in `openai` provider ignores OPENAI_BASE_URL — that's why
# v1 returned empty. Use the pi-nvidia-nim extension instead, which is the
# proper NIM integration.
set -euo pipefail

export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
APP="/data/data/com.termux"
RUNAS_ENV="env LD_LIBRARY_PATH=$APP/files/usr/lib LD_PRELOAD=$APP/files/usr/lib/libtermux-exec-ld-preload.so HOME=$APP/files/home PATH=$APP/files/usr/bin"

KEY="${NIM_API_KEY:-}"
if [ -z "$KEY" ]; then
  read -s -p "Paste your NIM API key (nvapi-...): " KEY; echo
fi

echo "==> 1/5 install pi-nvidia-nim extension"
adb shell "run-as com.termux $RUNAS_ENV $APP/files/usr/bin/bash -c \
  'npm install -g pi-nvidia-nim 2>&1 | tail -3'"

echo "==> 2/5 list models the extension exposes"
adb shell "run-as com.termux $RUNAS_ENV $APP/files/usr/bin/bash -c \
  'pi --list-models 2>&1 | grep -iE \"nvidia|nim|llama-3.3|qwen\" | head -10'"

echo "==> 3/5 (re-)plant NIM key at ~/.config/nvidia/api-key"
printf '%s' "$KEY" | adb shell "run-as com.termux sh -c \
  'cd /data/data/com.termux && mkdir -p files/home/.config/nvidia && cat > files/home/.config/nvidia/api-key && chmod 600 files/home/.config/nvidia/api-key && wc -c < files/home/.config/nvidia/api-key'"

echo "==> 4/5 smoke prompt — try via pi's nvidia provider"
# pi-nvidia-nim should register itself as the 'nvidia-nim' or 'nvidia' provider.
# We try a few common forms. NVIDIA_NIM_API_KEY env var also works as fallback.
adb shell "run-as com.termux $RUNAS_ENV \
  NVIDIA_NIM_API_KEY='$KEY' \
  NVIDIA_API_KEY='$KEY' \
  $APP/files/usr/bin/bash -c \
  'echo \"--- attempt: --provider nvidia-nim ---\"; \
   timeout 60 pi -p --no-session --provider nvidia-nim --model meta/llama-3.3-70b-instruct \"Reply with exactly: OK\" 2>&1 | tail -5; \
   echo; echo \"--- attempt: --model nvidia-nim/meta/llama-3.3-70b-instruct ---\"; \
   timeout 60 pi -p --no-session --model nvidia-nim/meta/llama-3.3-70b-instruct \"Reply with exactly: OK\" 2>&1 | tail -5; \
   echo; echo \"--- attempt: --provider nvidia ---\"; \
   timeout 60 pi -p --no-session --provider nvidia --model meta/llama-3.3-70b-instruct \"Reply with exactly: OK\" 2>&1 | tail -5'"

echo "==> 5/5 if all attempts failed: dump pi --list-models so we can see real provider names"
adb shell "run-as com.termux $RUNAS_ENV $APP/files/usr/bin/bash -c \
  'pi --list-models 2>&1 | head -40'"

unset KEY
echo
echo "==> Done. Tell me which attempt printed OK (or 'none' + the --list-models output)."
