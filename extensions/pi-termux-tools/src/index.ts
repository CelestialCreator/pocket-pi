// pi-termux-tools — Pi extension exposing Pocket Pi's phone surface as agent
// tools. Routes every call through the in-APK localhost HTTP bridge at
// 127.0.0.1:9998, gated by the per-launch bearer token at
// $PREFIX/etc/pocket-pi/api-token. No companion APK is required — the
// capabilities live inside Pocket Pi itself.
//
// Tool naming retains the `termux_*` prefix for backwards compat with sessions
// that already learned the old names; new capabilities use the `pocket_pi_*`
// prefix to signal they're Pocket-Pi-specific (no analogue in upstream Termux).
//
// Pi's extension contract (matching oh-pi's bg-process.ts as canonical):
//
//   export default function register(api: ExtensionAPI): void
//   api.registerTool({
//     name, label, description, parameters,
//     async execute(toolCallId, params, signal) {
//       return { content: [{ type: "text", text }], isError?: boolean };
//     },
//   });

import { readFile, mkdir, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";

// Minimal structural type for Pi's extension API. Captures only what we use.
type ToolResult = {
  content: Array<{ type: "text"; text: string }>;
  details?: Record<string, unknown>;
  isError?: boolean;
};
type ToolDefinition = {
  name: string;
  label?: string;
  description: string;
  parameters: {
    type: "object";
    properties: Record<string, unknown>;
    required?: string[];
  };
  execute(
    toolCallId: string,
    params: Record<string, unknown>,
    signal?: AbortSignal,
  ): Promise<ToolResult>;
};
export interface ExtensionAPI {
  registerTool(def: ToolDefinition): void;
  log?: (level: "info" | "warn" | "error", msg: string) => void;
}

const API_HOST = "http://127.0.0.1:9998";
const TOKEN_PATH = `${process.env.PREFIX ?? "/data/data/com.termux/files/usr"}/etc/pocket-pi/api-token`;

async function readToken(): Promise<string> {
  const token = (await readFile(TOKEN_PATH, "utf8")).trim();
  if (!token) throw new Error(`empty api-token at ${TOKEN_PATH} — is Pocket Pi running?`);
  return token;
}

async function api(path: string, body?: unknown): Promise<unknown> {
  const token = await readToken();
  const url = `${API_HOST}${path.startsWith("/") ? path : `/${path}`}`;
  const r = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: body ? JSON.stringify(body) : "{}",
  });
  if (!r.ok) {
    const text = await r.text().catch(() => "");
    throw new Error(`${r.status} ${r.statusText}: ${text || "(no body)"}`);
  }
  return r.json();
}

/** Wrap a body-producing async fn into Pi's tool-result shape. */
async function tool(handler: () => Promise<unknown>): Promise<ToolResult> {
  try {
    const out = await handler();
    const text = typeof out === "string" ? out : JSON.stringify(out);
    return { content: [{ type: "text", text: text || "ok" }] };
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e);
    return { content: [{ type: "text", text: msg }], isError: true };
  }
}

export default function register(pi: ExtensionAPI): void {
  // ---- Notifications --------------------------------------------------------
  pi.registerTool({
    name: "termux_notify",
    label: "Notify",
    description:
      "Show an Android notification. Use to surface long-task results, " +
      "skill proposals, or any signal the user should see while the app " +
      "is backgrounded. The notification is non-interactive.",
    parameters: {
      type: "object",
      properties: {
        title: { type: "string", description: "Bold notification title" },
        content: { type: "string", description: "Body text (1–3 short lines)" },
        priority: {
          type: "string",
          enum: ["min", "low", "default", "high", "max"],
          description: "Defaults to 'default'",
        },
      },
      required: ["title", "content"],
    },
    async execute(_id, args) {
      return tool(() =>
        api("/notify", {
          title: String(args.title ?? ""),
          content: String(args.content ?? ""),
          priority: args.priority ? String(args.priority) : "default",
        }),
      );
    },
  });

  // ---- TTS ------------------------------------------------------------------
  pi.registerTool({
    name: "termux_tts_speak",
    label: "TTS",
    description:
      "Speak text aloud via Android TTS. Use for hands-busy / eyes-busy " +
      "moments (driving, cooking) or when the user explicitly asked for " +
      "audio output. Keep utterances under ~30 seconds of speech.",
    parameters: {
      type: "object",
      properties: { text: { type: "string" } },
      required: ["text"],
    },
    async execute(_id, args) {
      return tool(() => api("/tts", { text: String(args.text ?? "") }));
    },
  });

  // ---- Camera ---------------------------------------------------------------
  pi.registerTool({
    name: "termux_camera_photo",
    label: "Camera Photo",
    description:
      "Capture a photo from a device camera and save it to the agent's " +
      "captures directory. Returns the absolute path. Use to OCR a " +
      "receipt, parse a whiteboard, identify an object, etc.",
    parameters: {
      type: "object",
      properties: {
        camera: {
          type: "string",
          enum: ["back", "front"],
          description: "Defaults to back",
        },
        name: {
          type: "string",
          description: "Filename (without extension); defaults to ISO timestamp",
        },
      },
    },
    async execute(_id, args) {
      return tool(() =>
        api("/camera/photo", {
          camera: args.camera === "front" ? "front" : "back",
          name: args.name ? String(args.name) : undefined,
        }),
      );
    },
  });

  // ---- Share ----------------------------------------------------------------
  pi.registerTool({
    name: "termux_share",
    label: "Share",
    description:
      "Open Android's share sheet for a file or text. Use when the user " +
      "asks to share a file produced by the agent (a generated note, " +
      "screenshot, report) with another app, or to share plain text.",
    parameters: {
      type: "object",
      properties: {
        path: { type: "string", description: "Absolute path to a file" },
        text: { type: "string", description: "Plain text to share" },
        title: { type: "string", description: "Share-sheet title" },
        type: { type: "string", description: "MIME type (defaults to text/plain or */*)" },
      },
    },
    async execute(_id, args) {
      return tool(() => {
        const body: Record<string, unknown> = {};
        if (args.path) body.path = String(args.path);
        if (args.text) body.text = String(args.text);
        if (args.title) body.title = String(args.title);
        if (args.type) body.type = String(args.type);
        if (!body.path && !body.text) throw new Error("path or text required");
        return api("/share", body);
      });
    },
  });

  // ---- Clipboard ------------------------------------------------------------
  pi.registerTool({
    name: "termux_clipboard_get",
    label: "Clipboard Get",
    description: "Read the Android clipboard (text only).",
    parameters: { type: "object", properties: {} },
    async execute() {
      return tool(() => api("/clipboard/get"));
    },
  });

  pi.registerTool({
    name: "termux_clipboard_set",
    label: "Clipboard Set",
    description: "Write text to the Android clipboard.",
    parameters: {
      type: "object",
      properties: { text: { type: "string" } },
      required: ["text"],
    },
    async execute(_id, args) {
      return tool(() => api("/clipboard/set", { text: String(args.text ?? "") }));
    },
  });

  // ---- Battery / Toast ------------------------------------------------------
  pi.registerTool({
    name: "termux_battery_status",
    label: "Battery",
    description: "Battery percent, charging state, temperature, health.",
    parameters: { type: "object", properties: {} },
    async execute() {
      return tool(() => api("/battery"));
    },
  });

  pi.registerTool({
    name: "termux_toast",
    label: "Toast",
    description: "Show a brief Android toast (auto-dismissed).",
    parameters: {
      type: "object",
      properties: { text: { type: "string" } },
      required: ["text"],
    },
    async execute(_id, args) {
      return tool(() => api("/toast", { text: String(args.text ?? "") }));
    },
  });

  // ---- Location -------------------------------------------------------------
  pi.registerTool({
    name: "termux_location",
    label: "Location",
    description:
      "Get device location. Slow (may take several seconds). Returns JSON " +
      "with latitude, longitude, accuracy, provider, and timestamp.",
    parameters: {
      type: "object",
      properties: {
        provider: {
          type: "string",
          enum: ["gps", "network", "passive", "fused"],
          description: "Defaults to fused (gps + network)",
        },
        timeoutSeconds: {
          type: "number",
          description: "Defaults to 15. Max effective ~60.",
        },
      },
    },
    async execute(_id, args) {
      return tool(() =>
        api("/location", {
          provider: args.provider ? String(args.provider) : "fused",
          timeoutSeconds: args.timeoutSeconds ? Number(args.timeoutSeconds) : 15,
        }),
      );
    },
  });

  // ---- File save (helper) ---------------------------------------------------
  pi.registerTool({
    name: "termux_save_to_downloads",
    label: "Save to Downloads",
    description:
      "Save text content into the agent's downloads folder " +
      "(~/.pi/agent/downloads/). Returns the absolute path. Use " +
      "pocket_pi_intent_send with ACTION_VIEW + a file:// data URI if you " +
      "want to open it in the user's file manager.",
    parameters: {
      type: "object",
      properties: {
        name: { type: "string", description: "Filename incl. extension" },
        content: { type: "string" },
      },
      required: ["name", "content"],
    },
    async execute(_id, args) {
      return tool(async () => {
        const dir = join(homedir(), ".pi", "agent", "downloads");
        await mkdir(dir, { recursive: true });
        const out = join(dir, String(args.name));
        await writeFile(out, String(args.content), "utf8");
        return { path: out };
      });
    },
  });

  // ---- Generic Android intent ----------------------------------------------
  pi.registerTool({
    name: "pocket_pi_intent_send",
    label: "Intent",
    description:
      "Send a generic Android intent (startActivity). Use this to invoke " +
      "any Android action by its constant — open a settings screen, dial " +
      "a number, send an SMS draft, view a file, etc. The intent is " +
      "started with FLAG_ACTIVITY_NEW_TASK from a Service context.",
    parameters: {
      type: "object",
      properties: {
        action: { type: "string", description: "Android action constant, e.g. 'android.intent.action.VIEW'" },
        data: { type: "string", description: "URI for the intent (http://…, tel:…, file://…, geo:…)" },
        type: { type: "string", description: "MIME type" },
        package: { type: "string", description: "Restrict to this package" },
        componentPackage: { type: "string" },
        componentClass: { type: "string" },
        categories: { type: "array", items: { type: "string" } },
        extras: { type: "object", description: "String/number/bool key-value pairs to attach" },
        flags: { type: "array", items: { type: "number" }, description: "Extra Intent flags (ORed in)" },
      },
      required: ["action"],
    },
    async execute(_id, args) {
      return tool(() => api("/intent", args));
    },
  });

  pi.registerTool({
    name: "pocket_pi_intent_open_url",
    label: "Open URL",
    description:
      "Open a URL in the device's default browser. Equivalent to " +
      "pocket_pi_intent_send with ACTION_VIEW + the URL.",
    parameters: {
      type: "object",
      properties: { url: { type: "string" } },
      required: ["url"],
    },
    async execute(_id, args) {
      return tool(() => api("/open-url", { url: String(args.url ?? "") }));
    },
  });

  pi.registerTool({
    name: "pocket_pi_intent_dial",
    label: "Dial",
    description:
      "Pre-fill the dialer with a phone number. Doesn't auto-call — the " +
      "user still has to tap dial. Use for hands-free 'call this number' " +
      "flows.",
    parameters: {
      type: "object",
      properties: { number: { type: "string", description: "E.164 or local format" } },
      required: ["number"],
    },
    async execute(_id, args) {
      return tool(() =>
        api("/intent", {
          action: "android.intent.action.DIAL",
          data: `tel:${String(args.number ?? "")}`,
        }),
      );
    },
  });

  pi.registerTool({
    name: "pocket_pi_intent_settings",
    label: "Settings",
    description:
      "Open a specific Android Settings screen. The screen is identified " +
      "by its action constant (e.g. 'android.settings.WIFI_SETTINGS', " +
      "'android.settings.LOCATION_SOURCE_SETTINGS', " +
      "'android.settings.APPLICATION_DETAILS_SETTINGS').",
    parameters: {
      type: "object",
      properties: { action: { type: "string", description: "Settings.ACTION_* constant" } },
      required: ["action"],
    },
    async execute(_id, args) {
      return tool(() => api("/intent", { action: String(args.action ?? "") }));
    },
  });

  // ---- Mic ------------------------------------------------------------------
  pi.registerTool({
    name: "pocket_pi_mic_record",
    label: "Mic Record",
    description:
      "Record audio from the device microphone for N seconds. Returns the " +
      "path to a .m4a (AAC) file in ~/.pi/agent/captures/. Use for voice " +
      "memos, dictation pipelines, environmental capture. The green " +
      "microphone privacy dot is shown for the duration.",
    parameters: {
      type: "object",
      properties: {
        seconds: { type: "number", description: "1–300. Defaults to 5." },
        name: { type: "string", description: "Filename without extension." },
      },
    },
    async execute(_id, args) {
      return tool(() =>
        api("/mic/record", {
          seconds: args.seconds ? Number(args.seconds) : 5,
          name: args.name ? String(args.name) : undefined,
        }),
      );
    },
  });

  // ---- Inbox (incoming intents) --------------------------------------------
  pi.registerTool({
    name: "pocket_pi_inbox_list",
    label: "Inbox List",
    description:
      "List queued incoming intents — anything the user shared to Pocket " +
      "Pi or any `pi://agent/…` deep link they tapped. Does not drain the " +
      "queue. Returns [{name, size}] sorted oldest-first.",
    parameters: { type: "object", properties: {} },
    async execute() {
      return tool(() => api("/inbox/list"));
    },
  });

  pi.registerTool({
    name: "pocket_pi_inbox_pop",
    label: "Inbox Pop",
    description:
      "Pop the oldest entry from the inbox and return its contents " +
      "({action, data, type, extras, …}). The entry is removed from the " +
      "queue. Returns {empty:true} if nothing was queued.",
    parameters: { type: "object", properties: {} },
    async execute() {
      return tool(() => api("/inbox/pop"));
    },
  });

  // ==========================================================================
  // UI automation tools — read and act on other apps' screens via the vendored
  // orb-eye AccessibilityService. All require the user to have toggled
  // "Pocket Pi" on under Settings → Accessibility. Tools return a friendly
  // error if the service isn't enabled.
  //
  // Typical flow:
  //   1. pocket_pi_ui_info        — what app is currently in focus?
  //   2. pocket_pi_ui_find        — locate target element, returns coords
  //   3. pocket_pi_ui_tap         — tap the coords (or use pocket_pi_ui_click)
  //   4. pocket_pi_ui_wait        — block until the UI updates
  //   5. pocket_pi_ui_screen      — read the new screen state
  // ==========================================================================

  pi.registerTool({
    name: "pocket_pi_ui_info",
    label: "UI: App Info",
    description:
      "Identify the app currently in focus on the device. Returns the package " +
      "name (e.g. com.whatsapp) and Activity class so the agent can decide what " +
      "to do. Cheap; call freely.",
    parameters: { type: "object", properties: {} },
    async execute() {
      return tool(() => api("/ui/info"));
    },
  });

  pi.registerTool({
    name: "pocket_pi_ui_screen",
    label: "UI: Read Screen",
    description:
      "Return a flat list of visible UI elements with text/desc/bounds + " +
      "centerX/centerY ready for tapping. Use this to understand what's on " +
      "screen before deciding to tap or type. Filter with " +
      "`scrollable=true` for scrollable container contents, `editable=true` " +
      "for input fields, or `package` to limit to one app.",
    parameters: {
      type: "object",
      properties: {
        scrollable: { type: "boolean", description: "Only elements inside a scrollable container" },
        editable: { type: "boolean", description: "Only editable fields" },
        package: { type: "string", description: "Filter to this package name" },
      },
    },
    async execute(_id, args) {
      return tool(() =>
        api("/ui/screen", {
          scrollable: args.scrollable === true,
          editable: args.editable === true,
          package: args.package ? String(args.package) : undefined,
        }),
      );
    },
  });

  pi.registerTool({
    name: "pocket_pi_ui_find",
    label: "UI: Find Element",
    description:
      "Find a specific UI element by visible text, content-description, or " +
      "resource ID. Returns its bounds and centerX/centerY ready for " +
      "pocket_pi_ui_tap. Use when you know what to look for; cheaper than " +
      "calling pocket_pi_ui_screen first.",
    parameters: {
      type: "object",
      properties: {
        text: { type: "string", description: "Visible text (substring match)" },
        desc: { type: "string", description: "Content description (substring match)" },
        id: { type: "string", description: "Android resource ID (exact match)" },
        clickable: { type: "boolean", description: "Only return clickable elements" },
        index: { type: "number", description: "Which match to return when multiple (0-based, default 0)" },
      },
    },
    async execute(_id, args) {
      return tool(() => api("/ui/find", args));
    },
  });

  pi.registerTool({
    name: "pocket_pi_ui_tap",
    label: "UI: Tap",
    description:
      "Tap at exact screen coordinates. Use after pocket_pi_ui_find returned " +
      "centerX/centerY for an element. For tapping by text/desc directly in " +
      "one call, prefer pocket_pi_ui_click.",
    parameters: {
      type: "object",
      properties: {
        x: { type: "number" },
        y: { type: "number" },
        duration: { type: "number", description: "Hold duration ms (default 100)" },
      },
      required: ["x", "y"],
    },
    async execute(_id, args) {
      return tool(() => api("/ui/tap", args));
    },
  });

  pi.registerTool({
    name: "pocket_pi_ui_click",
    label: "UI: Click by Text",
    description:
      "Click an element by visible text, content-description, resource ID, or " +
      "bounds — a single-call convenience over find+tap. Walks up to the " +
      "nearest clickable ancestor if the matched node itself isn't clickable.",
    parameters: {
      type: "object",
      properties: {
        text: { type: "string" },
        desc: { type: "string" },
        id: { type: "string" },
        bounds: { type: "string", description: "Rect.flattenToString form, e.g. '100,200,500,400'" },
      },
    },
    async execute(_id, args) {
      return tool(() => api("/ui/click", args));
    },
  });

  pi.registerTool({
    name: "pocket_pi_ui_type",
    label: "UI: Type Text",
    description:
      "Type text into the currently focused input field. By default clears " +
      "the field first; pass append=true to append, or clear=true alone to " +
      "clear without typing. CJK input supported.",
    parameters: {
      type: "object",
      properties: {
        text: { type: "string" },
        append: { type: "boolean" },
        clear: { type: "boolean" },
      },
    },
    async execute(_id, args) {
      return tool(() => api("/ui/type", args));
    },
  });

  pi.registerTool({
    name: "pocket_pi_ui_swipe",
    label: "UI: Swipe",
    description:
      "Swipe from one point to another. Use for directional gestures (e.g. " +
      "swipe down to refresh, swipe left to dismiss, navigate carousels).",
    parameters: {
      type: "object",
      properties: {
        x1: { type: "number" }, y1: { type: "number" },
        x2: { type: "number" }, y2: { type: "number" },
        duration: { type: "number", description: "Total ms for the swipe (default 300)" },
      },
      required: ["x1", "y1", "x2", "y2"],
    },
    async execute(_id, args) {
      return tool(() => api("/ui/swipe", args));
    },
  });

  pi.registerTool({
    name: "pocket_pi_ui_scroll",
    label: "UI: Scroll",
    description:
      "Scroll the first scrollable container in the current window, or the " +
      "scrollable ancestor of a target text. Direction: up/down/left/right. " +
      "Use `count` to repeat (with 300ms gap).",
    parameters: {
      type: "object",
      properties: {
        direction: { type: "string", enum: ["up", "down", "left", "right"] },
        target: { type: "string", description: "Optional: scroll the container that holds this text" },
        count: { type: "number", description: "Number of scroll actions (default 1)" },
      },
      required: ["direction"],
    },
    async execute(_id, args) {
      return tool(() => api("/ui/scroll", args));
    },
  });

  pi.registerTool({
    name: "pocket_pi_ui_long_press",
    label: "UI: Long Press",
    description: "Long-press at coordinates. Useful for context menus, drag handles, etc.",
    parameters: {
      type: "object",
      properties: {
        x: { type: "number" }, y: { type: "number" },
        duration: { type: "number", description: "Hold duration ms (default 1000)" },
      },
      required: ["x", "y"],
    },
    async execute(_id, args) {
      return tool(() => api("/ui/longpress", args));
    },
  });

  pi.registerTool({
    name: "pocket_pi_ui_gesture",
    label: "UI: Custom Gesture",
    description:
      "Composite multi-touch gestures. Supports pinch_in / pinch_out " +
      "(zoom) at a center point, or `multi` for arbitrary multi-stroke " +
      "paths (e.g. two fingers drawing simultaneously).",
    parameters: {
      type: "object",
      properties: {
        type: { type: "string", enum: ["pinch_in", "pinch_out", "multi"] },
        x: { type: "number", description: "Center X for pinch" },
        y: { type: "number", description: "Center Y for pinch" },
        distance: { type: "number", description: "Pinch span px (default 200)" },
        durationMs: { type: "number" },
        strokes: {
          type: "array",
          description:
            "For type=multi: array of {path:[[x,y],…], startMs, durationMs}",
        },
      },
      required: ["type"],
    },
    async execute(_id, args) {
      return tool(() => api("/ui/gesture", args));
    },
  });

  pi.registerTool({
    name: "pocket_pi_ui_global",
    label: "UI: System Action",
    description:
      "Perform a system-wide action: back, home, recents, notifications " +
      "(pull-down shade), quick_settings, power_dialog.",
    parameters: {
      type: "object",
      properties: {
        action: {
          type: "string",
          enum: ["back", "home", "recents", "notifications", "quick_settings", "power_dialog"],
        },
      },
      required: ["action"],
    },
    async execute(_id, args) {
      return tool(() => api("/ui/global", args));
    },
  });

  pi.registerTool({
    name: "pocket_pi_ui_wait",
    label: "UI: Wait for Change",
    description:
      "Block until the UI changes (window state change or window content " +
      "change) or the timeout elapses. Useful after a tap that triggers " +
      "navigation — avoids polling pocket_pi_ui_screen in a loop. Returns " +
      "{changed: true|false} so the agent knows whether the wait fired.",
    parameters: {
      type: "object",
      properties: {
        timeoutMs: { type: "number", description: "Max wait ms (default 5000)" },
      },
    },
    async execute(_id, args) {
      return tool(() => api("/ui/wait", args));
    },
  });

  pi.registerTool({
    name: "pocket_pi_ui_notifications",
    label: "UI: System Notifications",
    description:
      "Return the buffered system notifications captured by the accessibility " +
      "service (last 50). Each has {timestamp, package, title, body, text, " +
      "bigText}. Use `clear=true` to drain the buffer after reading; " +
      "`package=` to filter to one app; `exclude=` (comma-separated) to drop " +
      "system noise like systemui, gms.",
    parameters: {
      type: "object",
      properties: {
        package: { type: "string", description: "Filter to this package only" },
        exclude: { type: "string", description: "Comma-separated packages to exclude" },
        clear: { type: "boolean", description: "Drain the buffer after reading" },
      },
    },
    async execute(_id, args) {
      return tool(() => api("/ui/notifications", args));
    },
  });

  pi.registerTool({
    name: "pocket_pi_ui_screenshot",
    label: "UI: Screenshot",
    description:
      "Take a screenshot of the current screen as base64 PNG. Returns " +
      "`{image: 'data:image/png;base64,...', width, height}`. Requires " +
      "Android 11+ (API 30+); older devices return NOT_SUPPORTED. Use when " +
      "a vision-capable LLM needs the pixels (e.g. 'summarize this UI'); " +
      "for structured tasks pocket_pi_ui_screen is cheaper.",
    parameters: { type: "object", properties: {} },
    async execute() {
      return tool(() => api("/ui/screenshot"));
    },
  });

  pi.registerTool({
    name: "pocket_pi_ui_poll_events",
    label: "UI: Poll Events",
    description:
      "Long-poll for new accessibility events (notification_added, " +
      "window_changed) since a cursor. Returns matching events plus a new " +
      "`cursor` value to pass back on the next call. Blocks up to " +
      "`timeoutMs` (max 60000) waiting for events. Use this for proactive " +
      "agent behavior — e.g. 'wait for the next WhatsApp message and reply'.",
    parameters: {
      type: "object",
      properties: {
        since: { type: "number", description: "Last seen event seq; 0 for from-now-on" },
        timeoutMs: { type: "number", description: "Max block ms (default 30000, max 60000)" },
      },
    },
    async execute(_id, args) {
      return tool(() => api("/ui/events/poll", args));
    },
  });
}

