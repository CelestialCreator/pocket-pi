#!/usr/bin/env bash
# The @anthropic-ai/claude-code postinstall mentioned a Node-based fallback:
#   Fallback: node /…/claude-code/cli-wrapper.cjs
# That suggests a pure-JS path that doesn't need the native binary. Try it.
#
# If cli-wrapper.cjs runs cleanly + answers a smoke prompt, we wrap it as a
# shell shim at $PREFIX/bin/claude so pi-claude-bridge sees it as `claude`.
set -euo pipefail

export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
APP="/data/data/com.termux"
RUNAS_ENV="env LD_LIBRARY_PATH=$APP/files/usr/lib LD_PRELOAD=$APP/files/usr/lib/libtermux-exec-ld-preload.so HOME=$APP/files/home PATH=$APP/files/usr/bin"

echo "==> 1/4 inspect cli-wrapper.cjs to see what it does"
adb shell "run-as com.termux $RUNAS_ENV $APP/files/usr/bin/bash -c \
  'head -40 $APP/files/usr/lib/node_modules/@anthropic-ai/claude-code/cli-wrapper.cjs 2>&1'"

echo
echo "==> 2/4 invoke cli-wrapper.cjs --version directly"
adb shell "run-as com.termux $RUNAS_ENV $APP/files/usr/bin/bash -c \
  'node $APP/files/usr/lib/node_modules/@anthropic-ai/claude-code/cli-wrapper.cjs --version 2>&1 | head -10'"

echo
echo "==> 3/4 smoke prompt via cli-wrapper.cjs (creds already planted)"
adb shell "run-as com.termux $RUNAS_ENV $APP/files/usr/bin/bash -c \
  'node $APP/files/usr/lib/node_modules/@anthropic-ai/claude-code/cli-wrapper.cjs -p \"say only OK\" 2>&1 | head -10'"

echo
echo "==> 4/4 if 2+3 worked, install a shim at \$PREFIX/bin/claude that uses cli-wrapper"
adb shell "run-as com.termux $RUNAS_ENV $APP/files/usr/bin/bash -c \
  'cat > $APP/files/usr/bin/claude.shim <<SHIM
#!$APP/files/usr/bin/bash
exec node $APP/files/usr/lib/node_modules/@anthropic-ai/claude-code/cli-wrapper.cjs \"\\\$@\"
SHIM
chmod +x $APP/files/usr/bin/claude.shim
ls -la $APP/files/usr/bin/claude.shim'"

echo
echo "==> Done."
echo "    If steps 2+3 returned a version + 'OK', tell me and I'll swap the"
echo "    'claude' symlink to point at claude.shim, reinstate pi-claude-bridge,"
echo "    and flip AGENTS.md back to claude defaults."
