# skill_learner_dspy

DSPy/GEPA reflection backend for `pi-skill-learner`. Talks to NVIDIA NIM
directly (no Pi extension loader involved) and emits a JSON skill proposal on
stdout.

## Why a separate Python package

`pk-pi-hermes-evolve`'s TS backend is paid (uses Claude via `pi-claude-bridge`).
This package is the **free fallback** — same shape of input/output, but the
reflection LLM call is routed to NVIDIA NIM's free tier. Lets the learner
mine skills in the background without burning Claude tokens.

## Auth

Reads the NIM API key from `~/.config/nvidia/api-key`. Pocket Pi's Settings
screen writes that file on the device.

## Usage

```bash
echo '{"turns":[...]}' | python -m skill_learner_dspy reflect
# → {"name":"...", "skill_md":"..."}
```

## License

MIT.
