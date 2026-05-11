# pi-skill-learner

Hermes-inspired closed learning loop for Pi. Watches successful turns,
proposes new Skills, dispatches reflection to either:

- **`pk-pi-hermes-evolve`** (TS path, via `pi-claude-bridge` → Claude Pro/Max) for **deep** reflection
- **`skill_learner_dspy`** (Python path, via NVIDIA NIM free tier) for **cheap** reflection

Default mode is `cheap` so background skill mining doesn't burn Claude tokens.

## Tools

| Tool | What it does |
|---|---|
| `skill_learner_propose` | Run reflection, write a candidate `SKILL.md` to `~/.pi/agent/skills/proposed/` |
| `skill_learner_status` | Show how many turns are logged and how many proposals are pending review |

## Storage layout

```
~/.pi/agent/learner/turns.jsonl              # rolling turn log
~/.pi/agent/skills/proposed/<slug>/SKILL.md  # awaiting user approval
~/.pi/agent/skills/<slug>/SKILL.md           # approved, auto-loaded by Pi
```

## License

MIT.
