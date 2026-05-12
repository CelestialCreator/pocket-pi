# Pocket Pi

A [Pi coding agent](https://pi.dev/) shipped as an Android APK. No Termux install, no shell setup — install the APK, paste an LLM key, chat.

> POC, fast-tracked. We bundle Termux's Linux runtime inside an Android app so the team can try a single-tap Pi install on a phone. Whether this approach is worth productizing (vs. building a proper native Android client) is the open question — that's what the POC is for.

## Install (team testers)

1. Grab the latest APK — **v0.2.0** — from the [Releases page](https://github.com/CelestialCreator/pocket-pi/releases/latest), or directly: [pocket-pi-v0.2.0.apk](https://github.com/CelestialCreator/pocket-pi/releases/download/v0.2.0/pocket-pi-v0.2.0.apk).
2. Sideload — tap the APK on the phone (allow install from unknown sources for your browser/file manager), or `adb install pocket-pi-v0.2.0.apk`.
3. Open the app. First launch runs the bootstrap (3–5 min on Wi-Fi: extracts Termux, installs Node + npm packages, registers Pi extensions).
4. Once the dashboard loads, tap its **⚙** in the top-right of the page chrome to add provider API keys, switch models, edit AGENTS.md, etc. NVIDIA NIM is pre-seeded (free) so you can chat immediately; OpenRouter / OpenAI / Anthropic / Groq are also wired up — paste a key (or use the Claude Pro/Max OAuth Sign-In, which opens your device's default browser) and pick a model.
5. Chat away.

If the dashboard never finishes binding, the loading screen surfaces **Restart Pi** and **Re-run setup** buttons after a 15-second stall — those re-kick the service and re-run the bootstrap installer respectively. As a last resort, force-stop the app from Android Settings and reopen; the install state on disk is preserved.

## What's inside

| Layer | Component |
|---|---|
| App shell | Android (Kotlin + Jetpack Compose) — `android/` |
| Linux runtime | Termux bootstrap (Node 25, Python, git, ripgrep, openssl) — `bootstrap/` |
| Chat UI | [`@blackbelt-technology/pi-agent-dashboard`](https://www.npmjs.com/package/@blackbelt-technology/pi-agent-dashboard) — binds `:8000` (browser UI) + `:9999` (pi extension bridge); rendered in the app WebView. Built-in slash commands, model switching, session history. |
| Agent engine | [`@earendil-works/pi-coding-agent`](https://www.npmjs.com/package/@earendil-works/pi-coding-agent), spawned as `pi --mode rpc` |
| Pi extensions | Dashboard bridge + `pi-anthropic-messages` (tool-call rendering, from GitHub) + `pi-web-access`, `pi-subagents`, `oh-pi`, `@aliou/pi-guardrails`, `pi-mcp-adapter`, `pk-pi-hermes-evolve` |
| Compose-side UI | Loading / recovery pane only (Pocket Pi splash, postinstall log tail, inline `Restart Pi` + `Re-run setup` buttons after a 15s stall). Everything else lives in the dashboard's own settings UI. |
| Providers wired | NVIDIA NIM (free), OpenRouter, OpenAI, Anthropic, Groq — keys go to `~/.config/<provider>/api-key`; the model registry lives in `~/.pi/agent/models.json`. Both are managed from the dashboard's ⚙ settings. |

## Repo layout

```
.
├── android/                  Gradle Android project for the APK
├── bootstrap/                Termux bootstrap zip generator + postinstall
│   ├── build-bootstrap.sh     Layer our payload on upstream Termux's aarch64 zip
│   ├── postinstall.sh         First-run install: apt, npm, pip, pi install loop
│   ├── npm-packages.txt       Pi engine + extensions + peer deps
│   ├── packages.txt           Termux apt packages
│   ├── pip-packages.txt       Python deps (dspy etc, best-effort)
│   └── patches/               One-shot post-update patches (e.g. hermes-evolve)
├── config/                   Baked into the bootstrap at build time
│   ├── AGENTS.md              Always-on Pi context
│   ├── models.json            Provider/model registry (NVIDIA pre-filled)
│   └── claude-bridge.json     Wrapper config (legacy; not active)
├── extensions/               Our own Pi extensions (TypeScript)
│   ├── pi-termux-tools/       Phone surface tools (TTS, notify, share, camera)
│   └── pi-skill-learner/      Hermes-style learning loop
├── python/skill_learner_dspy DSPy reflection backend
├── skills/                   Pi Skills bundled into the bootstrap
└── scripts/                  Misc dev helpers
```

## Build from source

```bash
# 1. Bootstrap zip (produces bootstrap/dist/bootstrap-aarch64.zip, ~30M)
cd bootstrap && ./build-bootstrap.sh aarch64

# 2. (Optional) the custom Pi extensions
cd ../extensions/pi-termux-tools && pnpm install && pnpm build
cd ../pi-skill-learner       && pnpm install && pnpm build

# 3. APK
cd ../../android && ./gradlew :app:assembleDebug
# Output: android/app/build/outputs/apk/debug/app-debug.apk (~67 MB)
```

The current build uses `applicationId = com.termux` so the upstream Termux bootstrap binaries (which bake in the path `/data/data/com.termux/files/usr`) work without recompiling. To ship under a real app id, run `bootstrap/rebuild-with-prefix.sh` (Docker, 4–12 h on Apple Silicon) to produce a bootstrap pinned to a custom prefix, then flip `applicationId` in `android/app/build.gradle.kts`.

## What works / what doesn't (v0.2)

| | Status |
|---|---|
| Single-APK install on aarch64 phones | ✓ |
| pi-agent-dashboard as the WebView UI (slash commands, model switcher, session history all native) | ✓ |
| NVIDIA NIM + OpenRouter end-to-end (chat, tool use, cost tracking) | ✓ |
| Recovery UI when the dashboard doesn't bind within 15s (inline Restart Pi / Re-run setup buttons) | ✓ |
| `pi-anthropic-messages` for tool-call rendering | ✓ |
| Shell-session feature inside the dashboard | not yet — `node-pty` has no android-arm64 prebuild and is stubbed; chat/files/tasks work, terminal tab will fail |
| `applicationId` ≠ `com.termux` | not yet — requires custom bootstrap rebuild |
| Old Android WebView builds (Chrome < ~120) | emulator system images ship stale WebView; real devices auto-update — confirmed working in Chrome 140+ |

## License

MIT. Third-party runtime components keep their own licenses (Termux GPL, Pi MIT) — see `LICENSE` for the list.

## Status

v0.2.0 — POC, shippable. The Termux-fork-inside-an-APK approach works: pi-agent-dashboard is the chat UI, single-tap APK install handles the rest. Whether to invest in productizing it (custom prefix bootstrap, real applicationId, signed release builds, Play Store, etc.) or rewrite this as a proper native Android client that talks to Pi over the network is the question this POC is meant to inform.
