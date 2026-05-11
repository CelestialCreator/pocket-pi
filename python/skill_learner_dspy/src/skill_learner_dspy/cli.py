"""CLI entry-point. Reads a JSON payload from stdin, writes a proposal JSON
to stdout. Designed to be spawned by ``pi-skill-learner``.

Usage:
    python -m skill_learner_dspy reflect
"""

from __future__ import annotations

import json
import sys

from . import __version__
from .learner import propose, turns_from_payload


def cmd_reflect() -> int:
    payload = json.load(sys.stdin)
    turns = turns_from_payload(payload)
    if len(turns) < 3:
        json.dump({"name": "none", "skill_md": ""}, sys.stdout)
        return 0
    result = propose(turns)
    json.dump(result, sys.stdout)
    return 0


def cmd_version() -> int:
    print(__version__)
    return 0


def main(argv: list[str] | None = None) -> int:
    args = list(argv or sys.argv[1:])
    if not args:
        print("usage: skill_learner_dspy {reflect|version}", file=sys.stderr)
        return 2
    sub = args[0]
    if sub == "reflect":
        return cmd_reflect()
    if sub == "version":
        return cmd_version()
    print(f"unknown subcommand: {sub}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
