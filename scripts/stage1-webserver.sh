#!/usr/bin/env bash
# Stage 1: install pi-webserver + pi-mobile on the live emulator, register
# them with Pi, generate a random API token, write Pi's settings.json so the
# webserver autostarts on every Pi session. Verify by curl-ing the PWA.
#
# Stage 2 (next) replaces the Compose chat UI with a WebView that loads
# http://127.0.0.1:4100/mobile?token=<token>.
set -euo pipefail

export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
APP="/data/data/com.termux"
RUNAS_ENV="env LD_LIBRARY_PATH=$APP/files/usr/lib LD_PRELOAD=$APP/files/usr/lib/libtermux-exec-ld-preload.so HOME=$APP/files/home PATH=$APP/files/usr/bin"

TOKEN="$(openssl rand -hex 24)"
echo "==> generated apiToken (kept in settings.json on device): $TOKEN"

echo "==> 1/4 install + register pi-webserver and pi-mobile"
adb shell "run-as com.termux $RUNAS_ENV $APP/files/usr/bin/bash -c \
  'npm install -g @e9n/pi-webserver @e9n/pi-mobile 2>&1 | tail -5 ; \
   pi install npm:@e9n/pi-webserver 2>&1 | tail -3 ; \
   pi install npm:@e9n/pi-mobile 2>&1 | tail -3'"

echo
echo "==> 2/4 write settings.json with autostart + apiToken"
SETTINGS_JSON=$(cat <<JSON
{
  "pi-webserver": {
    "autostart": true,
    "port": 4100,
    "apiToken": "$TOKEN",
    "apiReadToken": "$TOKEN"
  }
}
JSON
)
printf '%s' "$SETTINGS_JSON" | adb shell "run-as com.termux sh -c \
  'cd /data/data/com.termux && cat > files/home/.pi/agent/settings.json && cat files/home/.pi/agent/settings.json'"

# Stash port+token in a file the Android app reads later (stage 2).
INFO_JSON=$(printf '{"port":4100,"token":"%s","url":"http://127.0.0.1:4100/mobile?token=%s"}' "$TOKEN" "$TOKEN")
printf '%s' "$INFO_JSON" | adb shell "run-as com.termux sh -c \
  'cd /data/data/com.termux && cat > files/home/.pi/agent/webserver-info.json && chmod 600 files/home/.pi/agent/webserver-info.json'"

echo
echo "==> 3/4 restart Pocket Pi so the foreground service spawns pi with autostart"
adb shell am force-stop com.termux
adb shell am start -n com.termux/com.zosma.pocketpi.MainActivity >/dev/null 2>&1
echo "    waiting 12s for pi-webserver to bind"
sleep 12

echo
echo "==> 4/4 verify the PWA is serving"
echo "--- HTTP probe from inside emulator ---"
adb shell "run-as com.termux $RUNAS_ENV $APP/files/usr/bin/bash -c \
  'curl -sS -o /dev/null -w \"status=%{http_code} url=%{url_effective}\\n\" http://127.0.0.1:4100/ ; \
   echo --- /mobile ---; \
   curl -sS -H \"Authorization: Bearer $TOKEN\" http://127.0.0.1:4100/mobile 2>&1 | head -20'"

echo
echo "==> Done. If you saw status=200/401 for / and HTML/JS for /mobile,"
echo "    pi-webserver and pi-mobile are alive. Next: stage 2 — swap the"
echo "    Compose chat for a WebView pointed at http://127.0.0.1:4100/mobile."
