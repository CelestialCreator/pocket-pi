// pi-skill-learner — Hermes-style closed learning loop for Pi.
//
// Algorithm (v0.1, deliberately simple):
//
//   1. After every assistant turn, append a compact record to
//      ~/.pi/agent/learner/turns.jsonl: { ts, user, assistant_summary,
//      tools_used[], outcome }.
//   2. On `/learn` (a slash command we register), or automatically when the
//      sliding window detects N≥3 turns matching the same intent cluster,
//      kick off a reflection job.
//   3. Reflection has two backends:
//      - TS:   `pk-pi-hermes-evolve` via claude-bridge  (paid path, deep)
//      - Py:   `skill_learner_dspy` against NVIDIA NIM  (free path, cheap)
//      The user picks per-call via `mode: 'deep' | 'cheap'`. Default cheap.
//   4. The reflection produces a candidate SKILL.md, written under
//      ~/.pi/agent/skills/proposed/<slug>/. The Compose UI surfaces it
//      as a "Save this as a skill?" card. On approval, it moves to
//      ~/.pi/agent/skills/<slug>/ and Pi auto-loads it next turn.
//
// This file is the TS half — orchestration only. The actual reflection LLM
// calls happen in the spawned children.

import { spawn } from "node:child_process";
import { appendFile, mkdir, writeFile, readFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";

type ToolSpec = {
  name: string;
  description: string;
  parameters: { type: "object"; properties: Record<string, unknown>; required?: string[] };
};
type ToolResult = { content: string } | { error: string };
type ToolHandler = (args: Record<string, unknown>) => Promise<ToolResult>;
type TurnHook = (turn: TurnRecord) => Promise<void>;
export interface PiExtensionContext {
  registerTool(spec: ToolSpec, handler: ToolHandler): void;
  // Optional — Pi exposes lifecycle hooks; we tolerate older versions where
  // these may not be wired up.
  onTurnComplete?(handler: TurnHook): void;
  log?: (level: "info" | "warn" | "error", msg: string) => void;
}

interface TurnRecord {
  ts: string;
  user: string;
  assistantSummary: string;
  toolsUsed: string[];
  outcome: "success" | "error" | "interrupted";
}

const ROOT = join(homedir(), ".pi", "agent", "learner");
const TURNS_LOG = join(ROOT, "turns.jsonl");
const PROPOSED_DIR = join(homedir(), ".pi", "agent", "skills", "proposed");

async function ensureDirs() {
  await mkdir(ROOT, { recursive: true });
  await mkdir(PROPOSED_DIR, { recursive: true });
}

async function recordTurn(t: TurnRecord) {
  await ensureDirs();
  await appendFile(TURNS_LOG, JSON.stringify(t) + "\n", "utf8");
}

async function readTurns(limit = 50): Promise<TurnRecord[]> {
  await ensureDirs();
  let raw = "";
  try {
    raw = await readFile(TURNS_LOG, "utf8");
  } catch {
    return [];
  }
  const lines = raw.trim().split("\n").filter(Boolean).slice(-limit);
  return lines.map((l) => JSON.parse(l) as TurnRecord);
}

async function reflectDeep(turns: TurnRecord[]): Promise<string> {
  // Spawn pk-pi-hermes-evolve. The bootstrap patch ensures claude-bridge is
  // re-included even though hermes-evolve uses --no-extensions internally.
  return new Promise((resolve, reject) => {
    const proc = spawn("pi-hermes-evolve", ["reflect", "--input", "-"], {
      stdio: ["pipe", "pipe", "pipe"],
    });
    let out = "";
    let err = "";
    proc.stdout.on("data", (c) => (out += c.toString()));
    proc.stderr.on("data", (c) => (err += c.toString()));
    proc.on("error", reject);
    proc.on("close", (code) => {
      if (code !== 0) reject(new Error(`hermes-evolve exit ${code}: ${err}`));
      else resolve(out);
    });
    proc.stdin.end(JSON.stringify({ turns }));
  });
}

async function reflectCheap(turns: TurnRecord[]): Promise<string> {
  // Spawn the Python DSPy backend. Talks directly to NVIDIA NIM — no
  // claude-bridge involvement.
  return new Promise((resolve, reject) => {
    const proc = spawn("python", ["-m", "skill_learner_dspy", "reflect"], {
      stdio: ["pipe", "pipe", "pipe"],
    });
    let out = "";
    let err = "";
    proc.stdout.on("data", (c) => (out += c.toString()));
    proc.stderr.on("data", (c) => (err += c.toString()));
    proc.on("error", reject);
    proc.on("close", (code) => {
      if (code !== 0) reject(new Error(`skill_learner_dspy exit ${code}: ${err}`));
      else resolve(out);
    });
    proc.stdin.end(JSON.stringify({ turns }));
  });
}

function slugify(s: string): string {
  return s
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 48) || "skill";
}

async function writeProposal(name: string, body: string): Promise<string> {
  const slug = slugify(name);
  const dir = join(PROPOSED_DIR, slug);
  await mkdir(dir, { recursive: true });
  const path = join(dir, "SKILL.md");
  await writeFile(path, body, "utf8");
  return path;
}

export default function register(ctx: PiExtensionContext): void {
  // Wire the lifecycle hook if Pi exposes it.
  ctx.onTurnComplete?.(async (turn) => {
    await recordTurn(turn).catch((e) => ctx.log?.("warn", `turn record failed: ${e}`));
  });

  ctx.registerTool(
    {
      name: "skill_learner_propose",
      description:
        "Look at recent turns and propose a new Pi Skill that captures a " +
        "repeated successful pattern. Writes the proposal under " +
        "~/.pi/agent/skills/proposed/. The user reviews and approves it from " +
        "the Pocket Pi UI. Use this when you notice the user has done the " +
        "same kind of task multiple times.",
      parameters: {
        type: "object",
        properties: {
          mode: {
            type: "string",
            enum: ["cheap", "deep"],
            description:
              "'cheap' uses NVIDIA NIM via DSPy (free); 'deep' uses Claude " +
              "via pk-pi-hermes-evolve (paid). Default 'cheap'.",
          },
          window: {
            type: "number",
            description: "How many recent turns to consider. Default 30.",
          },
        },
      },
    },
    async (args) => {
      try {
        const mode = (args.mode as string) ?? "cheap";
        const window = (args.window as number) ?? 30;
        const turns = await readTurns(window);
        if (turns.length < 3) {
          return { content: "Not enough turns yet (need ≥3) to propose a skill." };
        }
        const reflection = mode === "deep"
          ? await reflectDeep(turns)
          : await reflectCheap(turns);
        // Convention: reflection emits JSON {"name": "...", "skill_md": "..."}
        const parsed = JSON.parse(reflection) as { name: string; skill_md: string };
        const path = await writeProposal(parsed.name, parsed.skill_md);
        return {
          content: `Proposed skill written to ${path}\n\nApprove from the Pocket Pi UI to activate.`,
        };
      } catch (e: unknown) {
        return { error: e instanceof Error ? e.message : String(e) };
      }
    },
  );

  ctx.registerTool(
    {
      name: "skill_learner_status",
      description: "Show how many turns have been logged and how many proposals are pending.",
      parameters: { type: "object", properties: {} },
    },
    async () => {
      try {
        const turns = await readTurns(10_000);
        const { readdir } = await import("node:fs/promises");
        let proposals: string[] = [];
        try {
          proposals = await readdir(PROPOSED_DIR);
        } catch {}
        return {
          content:
            `turns logged: ${turns.length}\n` +
            `proposals pending: ${proposals.length}`,
        };
      } catch (e: unknown) {
        return { error: e instanceof Error ? e.message : String(e) };
      }
    },
  );
}
