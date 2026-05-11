// pi-termux-tools — Pi extension exposing Termux:API as agent tools.
//
// Each tool is a thin shell-out to a `termux-*` binary that ships with the
// Termux:API package. Pocket Pi's bootstrap pre-installs `termux-api`, so
// every tool here is available out-of-the-box on the device.
//
// Pi's extension API surface is intentionally lean. The contract this file
// implements is:
//
//   export default function register(ctx: PiExtensionContext): void
//
// where `ctx.registerTool(spec, handler)` adds a tool callable by the model.
// The shape below mirrors how pi-claude-bridge and pi-mcp-adapter declare
// their tools — see those packages for the canonical reference.

import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { mkdir, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";

const exec = promisify(execFile);

// Minimal structural type for Pi's extension context. We avoid importing from
// @mariozechner/pi-coding-agent at build time so this package can be published
// without a hard dep on a specific Pi version.
type ToolSpec = {
  name: string;
  description: string;
  parameters: { type: "object"; properties: Record<string, unknown>; required?: string[] };
};
type ToolResult = { content: string } | { error: string };
type ToolHandler = (args: Record<string, unknown>) => Promise<ToolResult>;
export interface PiExtensionContext {
  registerTool(spec: ToolSpec, handler: ToolHandler): void;
  log?: (level: "info" | "warn" | "error", msg: string) => void;
}

async function sh(cmd: string, args: string[]): Promise<string> {
  const { stdout } = await exec(cmd, args, { maxBuffer: 8 * 1024 * 1024 });
  return stdout;
}

async function safe(handler: () => Promise<string>): Promise<ToolResult> {
  try {
    const content = await handler();
    return { content: content.trim() || "ok" };
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e);
    return { error: msg };
  }
}

export default function register(ctx: PiExtensionContext): void {
  // ---- Notifications --------------------------------------------------------
  ctx.registerTool(
    {
      name: "termux_notify",
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
    },
    (args) =>
      safe(async () => {
        const title = String(args.title ?? "");
        const content = String(args.content ?? "");
        const priority = String(args.priority ?? "default");
        await sh("termux-notification", ["-t", title, "-c", content, "--priority", priority]);
        return `notified: ${title}`;
      }),
  );

  // ---- TTS ------------------------------------------------------------------
  ctx.registerTool(
    {
      name: "termux_tts_speak",
      description:
        "Speak text aloud via Android TTS. Use for hands-busy / eyes-busy " +
        "moments (driving, cooking) or when the user explicitly asked for " +
        "audio output. Keep utterances under ~30 seconds of speech.",
      parameters: {
        type: "object",
        properties: { text: { type: "string" } },
        required: ["text"],
      },
    },
    (args) =>
      safe(async () => {
        await sh("termux-tts-speak", [String(args.text ?? "")]);
        return "spoken";
      }),
  );

  // ---- Camera ---------------------------------------------------------------
  ctx.registerTool(
    {
      name: "termux_camera_photo",
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
    },
    (args) =>
      safe(async () => {
        const cam = args.camera === "front" ? "1" : "0";
        const dir = join(homedir(), ".pi", "agent", "captures");
        await mkdir(dir, { recursive: true });
        const fname = `${(args.name as string) ?? new Date().toISOString().replace(/[:.]/g, "-")}.jpg`;
        const out = join(dir, fname);
        await sh("termux-camera-photo", ["-c", cam, out]);
        return out;
      }),
  );

  // ---- Share ----------------------------------------------------------------
  ctx.registerTool(
    {
      name: "termux_share",
      description:
        "Open Android's share sheet for a file. Use when the user asks to " +
        "share a file produced by the agent (a generated note, screenshot, " +
        "report) with another app.",
      parameters: {
        type: "object",
        properties: {
          path: { type: "string", description: "Absolute path to the file" },
          title: { type: "string", description: "Share-sheet title" },
        },
        required: ["path"],
      },
    },
    (args) =>
      safe(async () => {
        const path = String(args.path);
        const flags = ["-a", "send"];
        if (args.title) flags.push("-t", String(args.title));
        flags.push(path);
        await sh("termux-share", flags);
        return `shared: ${path}`;
      }),
  );

  // ---- Clipboard ------------------------------------------------------------
  ctx.registerTool(
    {
      name: "termux_clipboard_get",
      description: "Read the Android clipboard (text only).",
      parameters: { type: "object", properties: {} },
    },
    () => safe(() => sh("termux-clipboard-get", [])),
  );

  ctx.registerTool(
    {
      name: "termux_clipboard_set",
      description: "Write text to the Android clipboard.",
      parameters: {
        type: "object",
        properties: { text: { type: "string" } },
        required: ["text"],
      },
    },
    (args) =>
      safe(async () => {
        await sh("termux-clipboard-set", [String(args.text ?? "")]);
        return "copied";
      }),
  );

  // ---- Battery / Wifi / Toast ----------------------------------------------
  ctx.registerTool(
    {
      name: "termux_battery_status",
      description: "Battery percent, charging state, temperature, health.",
      parameters: { type: "object", properties: {} },
    },
    () => safe(() => sh("termux-battery-status", [])),
  );

  ctx.registerTool(
    {
      name: "termux_toast",
      description: "Show a brief Android toast (auto-dismissed).",
      parameters: {
        type: "object",
        properties: { text: { type: "string" } },
        required: ["text"],
      },
    },
    (args) =>
      safe(async () => {
        await sh("termux-toast", [String(args.text ?? "")]);
        return "toasted";
      }),
  );

  // ---- Location -------------------------------------------------------------
  ctx.registerTool(
    {
      name: "termux_location",
      description:
        "Get device location. Slow (may take several seconds). Returns JSON " +
        "with latitude, longitude, accuracy, provider, and ISO timestamp.",
      parameters: {
        type: "object",
        properties: {
          provider: {
            type: "string",
            enum: ["gps", "network", "passive"],
            description: "Defaults to gps",
          },
        },
      },
    },
    (args) =>
      safe(async () =>
        sh("termux-location", ["-p", String(args.provider ?? "gps")]),
      ),
  );

  // ---- File save (helper) ---------------------------------------------------
  ctx.registerTool(
    {
      name: "termux_save_to_downloads",
      description:
        "Save text content into the user's Downloads folder so they can " +
        "find it from the Files app. Returns the absolute path.",
      parameters: {
        type: "object",
        properties: {
          name: { type: "string", description: "Filename incl. extension" },
          content: { type: "string" },
        },
        required: ["name", "content"],
      },
    },
    (args) =>
      safe(async () => {
        const downloads = "/storage/emulated/0/Download";
        const out = join(downloads, String(args.name));
        await writeFile(out, String(args.content), "utf8");
        return out;
      }),
  );
}
