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
}
