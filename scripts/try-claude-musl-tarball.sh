#!/usr/bin/env bash
# Bypass npm's libc check and fetch the @anthropic-ai/claude-code-linux-arm64-musl
# tarball directly from npm's registry, drop the binary onto disk, and try
# to exec it on Termux. The musl build is more self-contained than glibc and
# stands a better chance of running on Android's bionic libc.
#
# If musl exec fails too, the only remaining option is proot-distro (a
# glibc-userland chroot inside Termux). I'll script that as the next step.
set -euo pipefail

export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
APP="/data/data/com.termux"
RUNAS_ENV="env LD_LIBRARY_PATH=$APP/files/usr/lib LD_PRELOAD=$APP/files/usr/lib/libtermux-exec-ld-preload.so HOME=$APP/files/home PATH=$APP/files/usr/bin"

echo "==> 1/5 fetch tarball URL from npm registry"
TGZ_URL=$(curl -fsSL https://registry.npmjs.org/@anthropic-ai/claude-code-linux-arm64-musl \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);v=d["dist-tags"]["latest"];print(d["versions"][v]["dist"]["tarball"])')
echo "    → $TGZ_URL"

echo "==> 2/5 download + push to device"
curl -fsSL -o /tmp/claude-musl.tgz "$TGZ_URL"
adb push /tmp/claude-musl.tgz /sdcard/claude-musl.tgz
rm /tmp/claude-musl.tgz

echo "==> 3/5 extract into the claude-code package's expected node_modules location"
adb shell "run-as com.termux $RUNAS_ENV $APP/files/usr/bin/bash -c \
  'cat /sdcard/claude-musl.tgz | (cd $APP/files/usr/lib/node_modules/@anthropic-ai && \
     mkdir -p claude-code-linux-arm64-musl && \
     cd claude-code-linux-arm64-musl && tar xz --strip-components=1) && \
   chmod +x $APP/files/usr/lib/node_modules/@anthropic-ai/claude-code-linux-arm64-musl/bin/claude && \
   ls -lh $APP/files/usr/lib/node_modules/@anthropic-ai/claude-code-linux-arm64-musl/bin/claude'"
adb shell rm /sdcard/claude-musl.tgz

echo "==> 4/5 inspect ELF + try direct exec"
adb shell "run-as com.termux $RUNAS_ENV $APP/files/usr/bin/bash -c \
  'echo \"--- file type ---\"; \
   $APP/files/usr/bin/file $APP/files/usr/lib/node_modules/@anthropic-ai/claude-code-linux-arm64-musl/bin/claude 2>&1 | head -3; \
   echo \"--- direct exec ---\"; \
   $APP/files/usr/lib/node_modules/@anthropic-ai/claude-code-linux-arm64-musl/bin/claude --version 2>&1 | head -5'"

echo
echo "==> 5/5 lie about process.platform so cli-wrapper picks the musl pkg"
adb shell "run-as com.termux $RUNAS_ENV $APP/files/usr/bin/bash -c \
  'node -e \"
    Object.defineProperty(process, \\\"platform\\\", { value: \\\"linux\\\" });
    require(\\\"$APP/files/usr/lib/node_modules/@anthropic-ai/claude-code/cli-wrapper.cjs\\\");
  \" --version 2>&1 | head -10'"

echo
echo "==> Done."
echo "    Step 4 'direct exec' result tells us everything:"
echo "      - prints version    → bionic+shims carry the musl binary; we shim it in"
echo "      - linker error      → musl/bionic gap; need proot-distro"
echo "      - segfault          → ditto"
