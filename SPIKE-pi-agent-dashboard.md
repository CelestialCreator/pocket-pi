# Spike: pi-agent-dashboard instead of pi-mobile

**Status: blocked — do not merge.**

## Goal

Replace `@e9n/pi-mobile` (the current WebView UI) with
`@blackbelt-technology/pi-agent-dashboard` to get built-in slash commands,
session history, live model switching, and a plugin system.

## What this branch does

- Adds `@blackbelt-technology/pi-agent-dashboard` to `bootstrap/npm-packages.txt`.
- `PiBridge.kt` spawns `pi-dashboard start` in the background alongside
  `pi --mode rpc` so port 8000 (browser UI) and 9999 (pi bridge WS) bind.
- `WebViewScreen.kt` probes 8000 first and loads `http://127.0.0.1:8000/`
  without auth (dashboard's localhost is unguarded). Falls back to pi-mobile
  on :4100 after a 20s grace if 8000 never binds.
- `postinstall.sh` now installs npm packages one-at-a-time with `|| true`
  so a single bad package doesn't abort the whole run.

## What blocks the spike

`@blackbelt-technology/pi-agent-dashboard` depends transitively on
`node-pty@1.1.0` (a native module for PTY-backed shell sessions). On
android-arm64 (Termux):

1. `node-pty` ships no `prebuilds/android-arm64`.
2. `node-gyp rebuild` runs but fails fetching node headers:
   `gyp ERR! stack TypeError: terminated at Fetch.onAborted` — TLS abort,
   likely Android's network sandbox semantics for the rebuild subprocess.

Verified on the emulator: with the resilient postinstall, the dashboard
package is the only one that fails; pi, pi-mobile, pi-webserver, and all
extensions install cleanly. The WebView then falls back to pi-mobile after
20s — i.e. we get the same UX as `main`.

## What it would take to ship the dashboard

Three options, increasing cost:

1. **Pre-bake a node-pty prebuild for android-arm64** into the bootstrap zip.
   - Build node-pty against Termux's libc/node ABI on a Linux arm64 host with
     the Termux NDK image.
   - Drop into `prebuilds/android-arm64/` inside the package post-install, so
     node-pty's resolver finds it before node-gyp triggers.
   - Pin node-pty version in npm-packages.txt to match the prebuild.
2. **Patch the dashboard to fall back to a pure-JS PTY** (likely lossy:
   shell sessions wouldn't work, only the chat surface).
3. **Run the dashboard server off-device** (laptop) and only ship the
   WebView pointing at it via zrok. Loses the on-device autonomy that's
   the point of Pocket Pi.

## Recommendation

Hold for v0.3+. The dashboard's UX wins (slash commands, history, model
picker) are real, but the native-build path needs a one-time investment
(option 1 above). For now:

- Keep this branch as the dashboard-integration scaffold.
- Keep the resilient `postinstall.sh` change (cherry-pick to main as a
  standalone fix — it's a strict improvement).
- Continue iterating pi-mobile + Compose Config on main for v0.1.x.
