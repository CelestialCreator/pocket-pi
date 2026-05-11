#!/usr/bin/env bash
# Attempt to run @anthropic-ai/claude-code on Termux by forcing the
# linux-arm64 native build (the package's normal install.cjs bails out on
# android-arm64). The linux-arm64 binary is glibc-linked; Termux uses bionic
# libc + a few compat shims (libandroid-support.so), so this MIGHT run.
#
# Three attempts in order of cleanliness:
#   1. Install the platform-specific sub-package directly via npm.
#   2. If that fails, pretend to be linux during install.cjs.
#   3. Smoke-test `claude --version` and a one-shot prompt with the OAuth
#      token already planted at ~/.claude/.credentials.json.
#
# Outcomes:
#   - "OK": Termux's bionic+shims carry the glibc binary. Subscription path lives.
#   - linker error / segfault: glibc-bionic gap; we'd need proot or a
#     custom build. Subscription path stays dead until then.
set -euo pipefail

export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
APP="/data/data/com.termux"
RUNAS_ENV="env LD_LIBRARY_PATH=$APP/files/usr/lib LD_PRELOAD=$APP/files/usr/lib/libtermux-exec-ld-preload.so HOME=$APP/files/home PATH=$APP/files/usr/bin"

if ! adb get-state >/dev/null 2>&1; then
  echo "ERR: no adb device. Start the emulator first."; exit 1
fi

echo "==> 1/4 try: install @anthropic-ai/claude-code-linux-arm64 directly"
adb shell "run-as com.termux $RUNAS_ENV $APP/files/usr/bin/bash -c \
  'npm install -g @anthropic-ai/claude-code @anthropic-ai/claude-code-linux-arm64 2>&1 | tail -5'"

echo "==> 2/4 if claude is still 'not installed', re-run install.cjs with platform override"
adb shell "run-as com.termux $RUNAS_ENV $APP/files/usr/bin/bash -c \
  'cd $APP/files/usr/lib/node_modules/@anthropic-ai/claude-code 2>/dev/null && \
   node -e \"Object.defineProperty(process, \\\"platform\\\", {value:\\\"linux\\\"}); require(\\\"./install.cjs\\\")\" 2>&1 | tail -10'"

echo "==> 3/4 inspect what landed on disk"
adb shell "run-as com.termux ls -la $APP/files/usr/bin/claude $APP/files/usr/lib/node_modules/@anthropic-ai/ 2>&1 | head -20"

echo "==> 4/4 smoke test (OAuth creds must already be planted via plant-claude-and-test.sh)"
adb shell "run-as com.termux $RUNAS_ENV $APP/files/usr/bin/bash -c \
  'echo \"--- claude --version ---\"; claude --version 2>&1 | head -5; \
   echo \"--- smoke prompt ---\"; claude -p \"say only OK\" 2>&1 | head -10'"

echo
echo "==> Done."
echo "    If you saw 'OK', the subscription path works on Termux. Reinstate"
echo "    pi-claude-bridge in npm-packages.txt + flip the AGENTS.md default."
echo "    If you saw a linker error or 'CANNOT LINK EXECUTABLE', the glibc"
echo "    build is incompatible — we'd need a proot env or a custom shim."
