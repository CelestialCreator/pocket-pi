---
name: pocket-pi-android-control
description: How to drive the Android device — open apps, tap, type, swipe, read screens, react to notifications. Use whenever the user wants to do something on the phone (open WhatsApp, set a timer, change a setting, send a message, find something on screen, etc.) or when an intent-only attempt fails. Covers the full fallback chain from deep-link to accessibility-driven UI automation.
triggers:
  - "open"
  - "tap"
  - "click"
  - "swipe"
  - "scroll"
  - "type"
  - "set a timer"
  - "set an alarm"
  - "go to settings"
  - "what's on my screen"
  - "what app is open"
  - "take a screenshot"
  - "wait for notification"
  - "send a message"
  - "make a call"
  - "navigate to"
  - "search for"
  - "find on screen"
---

# Pocket Pi — Android Control

You're running inside Pocket Pi, an Android app that gives you a real phone
surface to act on. This skill is the playbook for driving the device.

Two surfaces are available to you:

1. **Intents** (cheap, fast, broad coverage) — fire an Android intent and let
   the OS route it. Good for "open this app", "open this URL", "share this
   text", "dial this number", "view this file".
2. **UI automation** (powerful, slower, requires Accessibility) — read the
   actual element tree of whatever app is on screen and tap / type / swipe.
   Good for anything intents can't express: in-app navigation, search boxes,
   timers, multi-step forms, reading what's currently displayed.

The single most important rule: **when an intent doesn't get you all the way
there, switch to UI automation. Do not give up.**

## The fallback chain

Always try in this order:

1. **Deep link** — `pocket_pi_intent_open_url` with a scheme the app
   advertises (`wa.me/`, `tg://`, `geo:`, `vnd.youtube:`, `spotify:`,
   `mailto:`, `smsto:`, `market://`). Cheapest if it works.
2. **Generic intent** — `pocket_pi_intent_send` with an `action` (open Settings
   panel, dial, share, view file). Stable Android constants only — never
   invent activity class names.
3. **Launch by package + UI drive** — `pocket_pi_intent_send` with
   `action: "android.intent.action.MAIN"` and `package: "<the.app>"`,
   then `pocket_pi_ui_screen` to see what loaded, then `pocket_pi_ui_find` +
   `pocket_pi_ui_click` (or `_tap`) to navigate.
4. **Verify** — after any state-changing action, call `pocket_pi_ui_info` or
   `pocket_pi_ui_screen` to confirm the screen actually changed. If the user
   asked "did it work?", show them what's on screen now.

If step 3 hits an unfamiliar app, **explore before acting**: call
`pocket_pi_ui_screen` and read the actual element tree. The tree tells you
the truth about what's tappable; your training data does not.

## Tool catalog

### Identification (always cheap, call freely)
- `pocket_pi_ui_info` → current package + activity. Call this whenever
  you're unsure what app is foreground.
- `pocket_pi_ui_screen` → flat list of visible elements with bounds,
  centerX, centerY, clickable/editable/scrollable flags. The truth source.
- `pocket_pi_ui_focused` → which input field has focus right now.

### Launching an app or action
- `pocket_pi_intent_open_url {url}` — deep links + http(s).
- `pocket_pi_intent_send {action, data?, package?, extras?, ...}` — anything
  routable by Android. Use Android constants only:
  `android.intent.action.VIEW`, `MAIN`, `SEND`, `DIAL`, `SENDTO`, `EDIT`, etc.
- `pocket_pi_intent_settings {screen}` — direct to a Settings panel.
- `pocket_pi_intent_dial {number}` — dial pad pre-filled (user still confirms).

### Driving the UI
- `pocket_pi_ui_find {text|desc|id, clickable?, index?}` → element + bounds.
- `pocket_pi_ui_click {text|desc|id|bounds}` → click by attribute in one
  call. Walks up to the nearest clickable ancestor automatically.
  **Prefer this over find+tap when you have a text/desc to target.**
- `pocket_pi_ui_tap {x, y}` → tap exact pixel. Use after `find` returned
  coords, or when you derived them yourself.
- `pocket_pi_ui_type {text, append?, clear?}` → type into focused field.
  Clears first by default. Make sure something is focused — tap the field
  first if not.
- `pocket_pi_ui_swipe {x1, y1, x2, y2, duration?}` — directional gestures.
- `pocket_pi_ui_scroll {direction, target?, count?}` — scroll a container.
- `pocket_pi_ui_long_press {x, y}` — context menus, drag handles.
- `pocket_pi_ui_gesture {type: "pinch_in"|"pinch_out"|"multi", ...}` — zoom,
  multi-stroke paths.
- `pocket_pi_ui_global {action: "back"|"home"|"recents"|"notifications"|
  "quick_settings"|"power_dialog"}` — system actions.
- `pocket_pi_ui_wait {timeoutMs?}` — block until UI changes after a nav tap.
  **Use this instead of polling `_screen` in a loop.**

### Output
- `pocket_pi_ui_screenshot` → base64 PNG. Use only when vision is required
  — for structured tasks `pocket_pi_ui_screen` is cheaper and more
  reliable.

### Proactive
- `pocket_pi_ui_notifications {package?, exclude?, clear?}` → last 50
  buffered system notifications.
- `pocket_pi_ui_poll_events {since, timeoutMs}` → long-poll for
  `notification` / `window_changed` events. Blocks up to 60s. Use to
  *wait* for things rather than polling.

## Worked examples

### Set a 1-minute timer in the Clock app
```
1. pocket_pi_intent_send {action: "android.intent.action.MAIN",
                          package: "com.google.android.deskclock"}
2. pocket_pi_ui_wait {timeoutMs: 2000}
3. pocket_pi_ui_click {text: "Timer"}
4. pocket_pi_ui_screen → locate the digit pad
5. tap "1", "0", "0" via pocket_pi_ui_click (each digit is a button)
6. pocket_pi_ui_click {text: "Start"}
7. pocket_pi_ui_info → confirm we're still on the Clock with timer running
```

### Send a WhatsApp message
```
1. Try deep link first: pocket_pi_intent_open_url
   {url: "https://wa.me/<phone>?text=<urlencoded>"}
2. pocket_pi_ui_wait {timeoutMs: 3000}
3. pocket_pi_ui_click {text: "Send"} or
   pocket_pi_ui_click {desc: "Send"} (icon button often has only desc)
```

### Toggle Dark theme in Settings
```
1. pocket_pi_intent_settings {screen: "display"} (or
   pocket_pi_intent_send {action: "android.settings.DISPLAY_SETTINGS"})
2. pocket_pi_ui_scroll {direction: "down", target: "Dark theme"}
3. pocket_pi_ui_click {text: "Dark theme"}
4. pocket_pi_ui_screenshot → show the user the change visually
```

### Add a contact named "Mom" with number 555-1234
```
1. pocket_pi_intent_send {action: "android.intent.action.INSERT",
                          type: "vnd.android.cursor.dir/contact",
                          extras: {name: "Mom", phone: "555-1234"}}
   Many devices respect the extras; if not, fall through:
2. pocket_pi_ui_wait {timeoutMs: 2000}
3. If the fields aren't pre-filled, pocket_pi_ui_click {text: "First name"}
   then pocket_pi_ui_type {text: "Mom"}, repeat for phone.
4. pocket_pi_ui_click {text: "Save"}
```

### React to an incoming notification
```
1. pocket_pi_ui_poll_events {since: <last seen seq>, timeoutMs: 30000}
2. For each event of type "notification", inspect {package, title, body}.
3. Decide: open the app (pocket_pi_intent_send action=MAIN package=…),
   reply (open + ui_click {text:"Reply"} + ui_type), or just summarize
   to the user.
```

### Find what's on screen
```
pocket_pi_ui_info  → "you're in com.android.settings, SettingsHomepage"
pocket_pi_ui_screen → list of visible rows ("Network", "Battery", ...)
Summarize in plain English; offer the next reasonable action.
```

## Anti-patterns — do not

- **Do not give up at step 2.** If `pocket_pi_intent_send` doesn't have a
  dedicated action for the in-app feature you need, **launch the app and
  use UI tools**. Saying "this isn't supported by the available tools" is
  wrong — the UI surface is always available.
- **Do not invent activity class names** like
  `com.android.deskclock.TimerActivity`. They vary per device + version and
  break silently. Use `package` + `action: MAIN` and let the launcher
  resolve.
- **Do not call `pocket_pi_ui_screenshot` when `pocket_pi_ui_screen` would
  work.** Screenshots cost tokens; the element tree is free and structured.
- **Do not loop on `pocket_pi_ui_screen` to wait for navigation.** Use
  `pocket_pi_ui_wait` — it blocks on the actual accessibility event.
- **Do not assume a tap succeeded.** Call `pocket_pi_ui_info` or
  `pocket_pi_ui_screen` to verify the screen changed. UI taps are advisory
  on Android — gestures can be dropped under load.
- **Do not type without focus.** `pocket_pi_ui_type` writes into whatever
  field is currently focused. If no field is focused, the call no-ops.
  Tap the field first.
- **Do not call deep links you can't verify exist.** If you're not sure
  `appname://x/y` is a real scheme, just launch the package and drive UI.

## Deep-link catalog (stable across devices)

- WhatsApp: `https://wa.me/<phone>?text=<urlencoded>` or
  `whatsapp://send?phone=<phone>&text=<urlencoded>`
- Telegram: `tg://resolve?domain=<user>` or `https://t.me/<user>`
- Maps: `geo:<lat>,<lng>?q=<query>` or `google.navigation:q=<query>`
- YouTube: `vnd.youtube://<videoId>` or `https://youtu.be/<videoId>`
- Spotify: `spotify:track:<id>` / `spotify:search:<query>`
- Email draft: `mailto:<addr>?subject=<s>&body=<b>`
- SMS draft: `smsto:<num>?body=<b>` (or `sms:<num>`)
- Play Store: `market://details?id=<package>`
- Tel pre-fill: `tel:<num>` (use `pocket_pi_intent_dial` instead)

## Settings panel constants

`android.settings.SETTINGS`, `android.settings.WIFI_SETTINGS`,
`android.settings.BLUETOOTH_SETTINGS`, `android.settings.DISPLAY_SETTINGS`,
`android.settings.SOUND_SETTINGS`,
`android.settings.LOCATION_SOURCE_SETTINGS`,
`android.settings.APPLICATION_DETAILS_SETTINGS` (needs `data: package:<pkg>`),
`android.settings.ACCESSIBILITY_SETTINGS`,
`android.settings.BATTERY_SAVER_SETTINGS`,
`android.settings.DEVICE_INFO_SETTINGS`.
`pocket_pi_intent_settings {screen}` is a convenience over these.

## One-line discovery

If asked about an app you don't know:
1. `pocket_pi_intent_send {action: "MAIN", package: "<guess>"}` — try the
   guess. If the package doesn't exist the call errors cleanly.
2. If unsure of the package, check installed apps:
   `pocket_pi_ui_global {action: "home"}` then visually find it via
   `pocket_pi_ui_screen` from the launcher.
3. Once launched, `pocket_pi_ui_screen` reveals everything you need to
   know.

The element tree is always the ground truth. When in doubt, read the
screen.
