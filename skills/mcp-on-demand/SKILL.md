---
name: mcp-on-demand
description: Lazy-load MCP servers only when the user's request clearly needs one. MCP is OFF by default on Pocket Pi to keep tool-schema overhead off the hot path.
triggers:
  - "from github"
  - "create a jira ticket"
  - "search confluence"
  - "query the database"
  - "from notion"
---

# MCP on demand

Pocket Pi keeps `pi-mcp-adapter` installed but **does not pre-load any MCP
server**. MCP servers attach a sizeable tool schema to every turn, which on a
phone is wasted context.

When the user's request needs a specific external system, follow this protocol:

1. **Confirm the need.** If `pi-web-access` (web search, URL fetch, GitHub
   clone, PDF) can answer the question, use it instead. MCP is overkill for
   anything one-shot or read-only.

2. **Identify the right server.** Common ones the user has likely configured:
   - `github` — repo / issue / PR operations beyond raw web fetch
   - `atlassian` — Jira / Confluence reads & writes
   - `notion` — Notion reads & writes
   - `postgres` / `mongodb` — database queries (use sparingly; never
     write-mode without explicit user confirmation in the same turn)

3. **Attach for this turn only.** Use `pi-mcp-adapter`'s slash command form
   (`/mcp on <name>`) at the *start* of the response, do the work, then
   `/mcp off <name>` at the end. The user's MCP config lives in
   `~/.pi/agent/mcp-servers.json`.

4. **If the server isn't configured yet,** tell the user concisely what to
   add to `mcp-servers.json`. Do not silently fall back to a different
   approach — the user usually wants the MCP if they asked for that source.

5. **Never auto-attach a write-capable MCP server.** If the user's request
   would require writes (creating issues, modifying records), confirm in
   plain language before calling the tool.

## Why this skill exists

Pi's design philosophy is "primitives, not features": the core stays small,
extensions add capability on demand. MCP is a great primitive for crossing
into other systems, but loading every configured server on every turn is the
wrong default — especially on a phone where context tokens directly affect
latency and cost.
