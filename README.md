# Pocket Pi

A [Pi coding agent](https://pi.dev/) shipped as an Android APK. No Termux install, no shell setup — install the APK, paste an LLM key, chat.

> POC, fast-tracked. We bundle Termux's Linux runtime inside an Android app so the team can try a single-tap Pi install on a phone. Whether this approach is worth productizing (vs. building a proper native Android client) is the open question — that's what the POC is for.

## Install (team testers)

1. Grab the latest APK from the [Releases page](https://github.com/CelestialCreator/pocket-pi/releases/latest).
2. Sideload — tap the APK on the phone (allow install from unknown sources for your browser/file manager), or `adb install pocket-pi-vX.Y.Z.apk`.
3. Open the app. First launch runs the bootstrap (3–5 min on Wi-Fi: extracts Termux, installs Node + npm packages, registers Pi extensions).
4. When you see "Send a message to your Pi agent" — tap the ⚙ at the top right.
5. Paste at least one provider API key (NVIDIA NIM is free; OpenRouter / OpenAI / Anthropic / Groq also wired up), then tap **Save keys** → **Restart Pi**.
6. Tap **Re-run setup** once if extensions are missing (it's idempotent and safe).
7. Chat away.

If anything wedges, tap **⚙ → Re-run setup**. As a last resort, force-stop the app from Android Settings and reopen — the install state on disk is preserved.

## What's inside

| Layer | Component |
|---|---|
| App shell | Android (Kotlin + Jetpack Compose) — `android/` |
| Linux runtime | Termux bootstrap (Node 25, Python, git, ripgrep, openssl) — `bootstrap/` |
| Chat UI | [`@e9n/pi-mobile`](https://www.npmjs.com/package/@e9n/pi-mobile) PWA served by [`@e9n/pi-webserver`](https://www.npmjs.com/package/@e9n/pi-webserver), rendered in a WebView |
| Agent engine | [`@earendil-works/pi-coding-agent`](https://www.npmjs.com/package/@earendil-works/pi-coding-agent) |
| Pi extensions | `pi-web-access`, `pi-subagents`, `oh-pi`, `@aliou/pi-guardrails`, `pi-mcp-adapter`, `pk-pi-hermes-evolve` |
| Native config | Compose `ModalBottomSheet` with sections for API keys, AGENTS.md, models.json, Restart Pi, Re-run setup |
| Providers wired | NVIDIA NIM (free), OpenRouter, OpenAI, Anthropic, Groq — keys go to `~/.config/<provider>/api-key`; the model registry lives in `~/.pi/agent/models.json` |

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

## What works / what doesn't (v0.1)

| | Status |
|---|---|
| Single-APK install on aarch64 phones | ✓ |
| 8 Pi extensions registered, 17 tools online | ✓ |
| NVIDIA NIM + OpenRouter end-to-end (chat, tool use, cost tracking) | ✓ |
| Native ⚙ Config sheet — keys, AGENTS.md, models.json, restart, re-run setup | ✓ |
| Recovery UI when pi-webserver doesn't bind within 15s | ✓ |
| Slash commands `/session`, `/clear`, `/model`, `/threads` intercepted client-side | not yet — currently sent to LLM as text |
| Chat history persistence across tab switches | not yet — JSONL on disk, no resume |
| `applicationId` ≠ `com.termux` | not yet — requires custom bootstrap rebuild |
| Cosmetic phantom-icon row on some Android WebView builds | accepted — known compositor artifact, no functional impact |

## License

MIT. Third-party runtime components keep their own licenses (Termux GPL, Pi MIT) — see `LICENSE` for the list.

## Status

v0.1 — POC. The Termux-fork-inside-an-APK approach works. Whether to invest in productizing it (custom prefix bootstrap, real applicationId, signed release builds, Play Store, etc.) or rewrite this as a proper native Android client that talks to Pi over the network is the question this POC is meant to inform.
