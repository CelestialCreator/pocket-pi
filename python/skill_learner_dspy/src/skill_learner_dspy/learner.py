"""Reflection logic. Takes a list of turns, returns a Pi Skill proposal.

The reflection prompt is intentionally compact: NIM's free-tier 70B/120B
models perform best with focused prompts and explicit output schemas.
"""

from __future__ import annotations

import json
import re
import textwrap
from dataclasses import dataclass
from typing import List

from .nim import chat


@dataclass
class Turn:
    ts: str
    user: str
    assistant_summary: str
    tools_used: List[str]
    outcome: str


REFLECTION_SYSTEM = textwrap.dedent("""
    You are a meta-agent that watches another agent's recent turns and proposes
    reusable Skills when you spot a repeated successful pattern.

    A Pi Skill is a short markdown file with:
      - a frontmatter `name`, `description`, and `triggers` list
      - an instruction body the agent reads on demand

    Output STRICTLY a JSON object of shape:
      {"name": "<short kebab-case>", "skill_md": "<full SKILL.md contents>"}

    No prose outside the JSON. No code fences around the JSON.
    If you do not see a repeated successful pattern in the turns provided,
    output {"name":"none","skill_md":""}.
""").strip()


def _format_turns(turns: List[Turn]) -> str:
    out = []
    for i, t in enumerate(turns, 1):
        out.append(
            f"--- turn {i} [{t.outcome}] ---\n"
            f"USER: {t.user[:500]}\n"
            f"AGENT: {t.assistant_summary[:500]}\n"
            f"TOOLS: {', '.join(t.tools_used) or '(none)'}\n"
        )
    return "\n".join(out)


_JSON_RE = re.compile(r"\{.*\}", re.DOTALL)


def propose(turns: List[Turn], *, model: str | None = None) -> dict:
    """Run reflection. Returns {"name": str, "skill_md": str}."""
    user = "Recent turns:\n\n" + _format_turns(turns)
    raw = chat(
        [
            {"role": "system", "content": REFLECTION_SYSTEM},
            {"role": "user", "content": user},
        ],
        model=model or "meta/llama-3.3-70b-instruct",
        temperature=0.2,
        max_tokens=1500,
    )
    match = _JSON_RE.search(raw)
    if not match:
        return {"name": "none", "skill_md": ""}
    try:
        return json.loads(match.group(0))
    except json.JSONDecodeError:
        return {"name": "none", "skill_md": ""}


def turns_from_payload(payload: dict) -> List[Turn]:
    raw = payload.get("turns") or []
    out: List[Turn] = []
    for r in raw:
        out.append(
            Turn(
                ts=str(r.get("ts", "")),
                user=str(r.get("user", "")),
                assistant_summary=str(r.get("assistantSummary", r.get("assistant_summary", ""))),
                tools_used=list(r.get("toolsUsed", r.get("tools_used", []))),
                outcome=str(r.get("outcome", "success")),
            )
        )
    return out
