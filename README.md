# Pocket Pi

A [Pi coding agent](https://pi.dev/) shipped as an Android APK. No Termux install, no shell setup — install the APK, paste an LLM key (or sign in to Claude Pro/Max), chat.

Pocket Pi is a thin Android wrapper around two upstream projects that do the real work:

- **[Pi coding agent](https://github.com/mariozechner/pi-coding-agent)** by [Mario Zechner](https://github.com/mariozechner) — the underlying agent engine. The canonical home is now [earendil-works/pi-coding-agent](https://github.com/earendil-works/pi-coding-agent).
- **[pi-agent-dashboard](https://github.com/BlackBeltTechnology/pi-agent-dashboard)** by [BlackBelt Technology](https://github.com/BlackBeltTechnology) — the web chat UI rendered inside the APK's WebView (slash commands, session history, model switcher, provider settings, OAuth flows). [pi-anthropic-messages](https://github.com/BlackBeltTechnology/pi-anthropic-messages) (also BlackBelt) is the Anthropic protocol bridge that makes Claude Pro/Max OAuth tokens usable from Pi.

What Pocket Pi adds is the packaging: a Termux runtime, postinstall script, an Android service that supervises `pi --mode rpc` + the dashboard's Node server, and a Compose WebView with a small recovery UI for when the bootstrap stalls.

> POC, fast-tracked. We bundle Termux's Linux runtime inside an Android app so anyone can try a single-tap Pi install on a phone. Whether this approach is worth productizing (vs. building a proper native Android client) is the open question — that's what the POC is for.

## Install

1. Grab the latest APK — **v0.2.1** — from the [Releases page](https://github.com/CelestialCreator/pocket-pi/releases/latest), or directly: [pocket-pi-v0.2.1.apk](https://github.com/CelestialCreator/pocket-pi/releases/download/v0.2.1/pocket-pi-v0.2.1.apk) (40 MB, aarch64 only).
2. Sideload — tap the APK on the phone (allow install from unknown sources for your browser/file manager), or `adb install pocket-pi-v0.2.1.apk`.
3. Open the app. First launch runs the bootstrap (3–5 min on Wi-Fi: extracts Termux, installs Node + npm packages, registers Pi extensions).
4. When the dashboard loads, tap its **⚙** (top-right of the page chrome) → **Providers** → add at least one provider. See [Providers — what works](#providers--what-works) below.
5. Pick a model, chat away.

If the dashboard never finishes binding, the loading screen surfaces **Restart Pi** and **Re-run setup** buttons after a 15-second stall — those re-kick the service and re-run the bootstrap installer respectively. As a last resort, force-stop the app from Android Settings and reopen; the install state on disk is preserved.

## Providers — what works

The dashboard's Providers UI lists **two** sections: `SUBSCRIPTIONS (OAUTH)` and `API KEYS`. Not everything in the OAuth list works end-to-end on Pocket Pi today — the OAuth flow stores credentials, but actually *using* those credentials requires a Pi-side protocol bridge for each vendor. Only Anthropic's bridge is bundled.

| Provider | OAuth Sign-In | API Key | Notes |
|---|---|---|---|
| **Anthropic** (Claude Pro/Max) | ✓ end-to-end | ✓ | OAuth uses your `claude.ai` subscription quota via the bundled [`pi-anthropic-messages`](https://github.com/BlackBeltTechnology/pi-anthropic-messages) bridge. Sign-In opens your phone's default browser via an `xdg-open` shim → Android `ACTION_VIEW`. |
| **OpenAI** | — | ✓ | API key from `platform.openai.com`. |
| **Google Gemini** (AI Studio key) | — | ✓ | API key from `aistudio.google.com/app/apikey`. Recommended path for Gemini on Pocket Pi. |
| **Google Gemini CLI** OAuth | partial — Sign-In completes but unusable | n/a | Requires `GOOGLE_CLOUD_PROJECT` env var and a Pi-side `gemini-cli` bridge; neither is wired up. Use the AI Studio key instead. |
| **ChatGPT Plus/Pro (Codex)** OAuth | Sign-In completes but unusable | — | Codex protocol bridge not bundled. |
| **GitHub Copilot** OAuth | Sign-In completes but unusable | — | Copilot protocol bridge not bundled. |
| **Antigravity** OAuth | Sign-In completes but no models | — | No model catalog without a bridge. |
| **Groq** | — | ✓ | API key from `console.groq.com`. |
| **Mistral / xAI / Z.ai / OpenRouter / NVIDIA NIM** | — | ✓ | All use standard API-key paste. |

If you want to use Claude Pro/Max OAuth on Pocket Pi but prefer signing in on a different device, the manual flow works too: grab the auth URL the dashboard would have opened, sign in on your laptop browser, copy the `http://localhost:53692/callback?…` redirect URL out of the laptop's address bar, and open it in your phone's Chrome — the phone's Chrome will hit Pocket Pi's on-device callback server and finish the exchange.

## What's inside

| Layer | Component | Upstream |
|---|---|---|
| App shell | Android (Kotlin + Jetpack Compose) — `android/` | Pocket Pi |
| Linux runtime | Termux bootstrap (Node 25, Python, git, ripgrep, openssl) — `bootstrap/` | [Termux](https://termux.dev/) |
| Chat UI | [`@blackbelt-technology/pi-agent-dashboard`](https://www.npmjs.com/package/@blackbelt-technology/pi-agent-dashboard) — binds `:8000` (browser UI) + `:9999` (pi extension bridge); rendered in the app WebView. Slash commands, model switching, session history, provider settings, OAuth. | [BlackBelt Technology](https://github.com/BlackBeltTechnology/pi-agent-dashboard) |
| Agent engine | [`@earendil-works/pi-coding-agent`](https://www.npmjs.com/package/@earendil-works/pi-coding-agent), spawned as `pi --mode rpc` | [Mario Zechner](https://github.com/mariozechner/pi-coding-agent) / [earendil-works](https://github.com/earendil-works/pi-coding-agent) |
| Pi extensions | [`pi-anthropic-messages`](https://github.com/BlackBeltTechnology/pi-anthropic-messages) (Claude Pro/Max OAuth + tool-call rendering) + `pi-web-access`, `pi-subagents`, `oh-pi`, `@aliou/pi-guardrails`, `pi-mcp-adapter`, `pk-pi-hermes-evolve` | various (see `bootstrap/npm-packages.txt`) |
| Compose-side UI | Loading / recovery pane only (Pocket Pi splash, postinstall log tail, inline `Restart Pi` + `Re-run setup` buttons after a 15s stall). Everything else lives in the dashboard's own settings UI. | Pocket Pi |
| Native bridges | `xdg-open` shim (postinstall) → Android `ACTION_VIEW` so the dashboard's OAuth flows open the device's default browser. Compose-side `PocketPi.notify/share/openExternal/toast` JS interface for the WebView. | Pocket Pi |

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

## What works / what doesn't (v0.2.1)

| | Status |
|---|---|
| Single-APK install on aarch64 phones | ✓ |
| pi-agent-dashboard as the WebView UI (slash commands, model switcher, session history all native) | ✓ |
| API-key chat for OpenAI / Anthropic API / Google Gemini (AI Studio) / Groq / Mistral / xAI / NVIDIA NIM / OpenRouter (tool use, cost tracking) | ✓ |
| Claude Pro/Max **OAuth** Sign-In → device default browser → on-device callback | ✓ |
| Recovery UI when the dashboard doesn't bind within 15s (inline Restart Pi / Re-run setup buttons) | ✓ |
| Other OAuth providers (Gemini CLI, ChatGPT Codex, GitHub Copilot, Antigravity) | sign-in completes but no models — Pi-side protocol bridges not bundled. Use the API-key path instead. |
| Shell-session feature inside the dashboard | not yet — `node-pty` has no android-arm64 prebuild and is stubbed; chat/files/tasks work, terminal tab will fail |
| `applicationId` ≠ `com.termux` | not yet — requires custom bootstrap rebuild |
| Old Android WebView builds (Chrome < ~120) | emulator system images ship stale WebView; real devices auto-update — confirmed working in Chrome 140+ |

## Credits

Pocket Pi is just packaging. The actual agent engine and the chat UI are someone else's work — Pocket Pi wouldn't exist without:

- **Pi coding agent** — [Mario Zechner](https://github.com/mariozechner) (original author) and the [earendil-works](https://github.com/earendil-works/pi-coding-agent) maintainers. The runtime that powers every chat turn, tool call, and skill in this app. See [pi.dev](https://pi.dev/).
- **pi-agent-dashboard** + **pi-anthropic-messages** — [BlackBelt Technology](https://github.com/BlackBeltTechnology). The web chat UI rendered inside the APK's WebView, plus the Anthropic protocol bridge that makes Claude Pro/Max OAuth work end-to-end.
- **Termux** — the Termux maintainers. The Linux-on-Android runtime that lets us ship Node, Python, and the rest inside a single APK without root or a custom ROM.

Pi extension ecosystem authors are credited individually in each package's `package.json`; see `bootstrap/npm-packages.txt` for the install list.

## License

MIT for Pocket Pi's own source. Third-party runtime components keep their own licenses (Termux GPL, Pi MIT, pi-agent-dashboard's license, etc.) — see each upstream repo for details, and `LICENSE` for the Pocket Pi terms.

## Status

v0.2.1 — POC, shippable. The Termux-fork-inside-an-APK approach works: pi-agent-dashboard is the chat UI, single-tap APK install handles the rest. Whether to invest in productizing it (custom prefix bootstrap, real applicationId, signed release builds, Play Store, etc.) or rewrite this as a proper native Android client that talks to Pi over the network is the question this POC is meant to inform.
