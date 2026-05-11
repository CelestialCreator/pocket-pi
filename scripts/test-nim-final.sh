#!/usr/bin/env bash
# Final NIM smoke test using the literal pi invocation from the user's host:
#   pi -p --provider nvidia --model meta/llama-3.3-70b-instruct --no-session <prompt>
#
# Also writes the matching ~/.bashrc line so future interactive shells inside
# Termux get NVIDIA_API_KEY exported automatically.
set -euo pipefail

export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
APP="/data/data/com.termux"
RUNAS_ENV="env LD_LIBRARY_PATH=$APP/files/usr/lib LD_PRELOAD=$APP/files/usr/lib/libtermux-exec-ld-preload.so HOME=$APP/files/home PATH=$APP/files/usr/bin"

KEY="${NIM_API_KEY:-}"
if [ -z "$KEY" ]; then
  read -s -p "NIM key (nvapi-...): " KEY; echo
fi

echo "==> 1/4 plant key + add ~/.bashrc export line (mirrors host setup)"
printf '%s' "$KEY" | adb shell "run-as com.termux sh -c \
  'cd /data/data/com.termux && \
   mkdir -p files/home/.config/nvidia && \
   cat > files/home/.config/nvidia/api-key && \
   chmod 600 files/home/.config/nvidia/api-key && \
   if ! grep -q \"NVIDIA_API_KEY\" files/home/.bashrc 2>/dev/null; then \
     echo \"export NVIDIA_API_KEY=\\\$(cat ~/.config/nvidia/api-key 2>/dev/null)\" >> files/home/.bashrc; \
   fi && \
   echo planted'"

echo
echo "==> 2/4 verify key reachable + provider listed"
adb shell "run-as com.termux $RUNAS_ENV NVIDIA_API_KEY='$KEY' \
  $APP/files/usr/bin/bash -c 'echo \"key bytes: \$(printf %s \"\$NVIDIA_API_KEY\" | wc -c)\"; pi --list-models | grep nvidia'"

echo
echo "==> 3/4 smoke prompt — exact host invocation, NO timeout, NO tail"
adb shell "run-as com.termux $RUNAS_ENV NVIDIA_API_KEY='$KEY' \
  $APP/files/usr/bin/bash -c 'pi -p --provider nvidia --model meta/llama-3.3-70b-instruct --no-session \"Reply with exactly: OK\"'"

echo
echo "==> 4/4 same prompt in --mode json (this is what the APK uses)"
adb shell "run-as com.termux $RUNAS_ENV NVIDIA_API_KEY='$KEY' \
  $APP/files/usr/bin/bash -c 'pi -p --mode json --provider nvidia --model meta/llama-3.3-70b-instruct --no-session \"Reply with exactly: OK\"'"

unset KEY
echo
echo "==> Done. Step 3 should print OK as plain text. Step 4 should print one"
echo "    JSON object per line — that's the format the Compose UI consumes."
