# pi-termux-tools

Pi extension exposing Pocket Pi's phone surface as agent tools — notifications,
intents, share, camera, mic, location, clipboard, inbox.

Routes every call through Pocket Pi's localhost HTTP bridge (`127.0.0.1:9998`,
per-launch bearer token). No Termux:API companion APK is required — every
capability lives inside the Pocket Pi APK itself.

## Tools

| Tool | What it does |
|---|---|
| `termux_notify` | Android notification (title, content, priority) |
| `termux_tts_speak` | Android TTS — speak text aloud |
| `termux_camera_photo` | Capture photo, save to `~/.pi/agent/captures/` |
| `termux_share` | Open Android share sheet for a file or text |
| `termux_clipboard_get` / `termux_clipboard_set` | Text clipboard I/O |
| `termux_battery_status` | Battery JSON (%, charging, temperature, health) |
| `termux_toast` | Brief auto-dismissed toast |
| `termux_location` | Location (gps / network / fused), JSON |
| `termux_save_to_downloads` | Write text into `~/.pi/agent/downloads/` |
| `pocket_pi_intent_send` | Generic Android intent (`startActivity`) — open any action |
| `pocket_pi_intent_open_url` | Convenience: ACTION_VIEW for a URL |
| `pocket_pi_intent_dial` | Pre-fill the dialer with a phone number |
| `pocket_pi_intent_settings` | Open a specific Settings screen |
| `pocket_pi_mic_record` | Record N seconds of audio to `~/.pi/agent/captures/<ts>.m4a` |
| `pocket_pi_inbox_list` | List queued incoming intents (shared text/images, `pi://agent/…` deep links) |
| `pocket_pi_inbox_pop` | Drain the oldest inbox entry |

## Requirements

- Pocket Pi v0.3.0+ (provides the `$PREFIX/etc/pocket-pi/api-token` file and
  the `127.0.0.1:9998` HTTP server)
- Pi v0.70.0+

## Install

```bash
pi install npm:pi-termux-tools
```

In Pocket Pi, this is bundled in the bootstrap and active on first launch.

## License

MIT.
