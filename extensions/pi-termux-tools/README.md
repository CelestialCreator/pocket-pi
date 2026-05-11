# pi-termux-tools

Pi extension exposing Termux:API as agent tools.

## Tools

| Tool | What it does |
|---|---|
| `termux_notify` | Android notification (title, content, priority) |
| `termux_tts_speak` | Android TTS — speak text aloud |
| `termux_camera_photo` | Capture photo, save to `~/.pi/agent/captures/` |
| `termux_share` | Open Android share sheet for a file |
| `termux_clipboard_get` / `termux_clipboard_set` | Text clipboard I/O |
| `termux_battery_status` | Battery JSON (%, charging, temperature, health) |
| `termux_toast` | Brief auto-dismissed toast |
| `termux_location` | GPS / network / passive location, JSON |
| `termux_save_to_downloads` | Write text into the user's Downloads folder |

## Requirements

- Termux:API package installed: `pkg install termux-api`
- Termux:API Android app from F-Droid or GitHub (not Google Play — the Play
  build is deprecated)
- Pi v0.70.0+

## Install

```bash
pi install npm:pi-termux-tools
```

Or, in Pocket Pi, this is bundled in the bootstrap and active on first launch.

## License

MIT.
