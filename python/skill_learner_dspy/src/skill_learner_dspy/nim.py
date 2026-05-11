"""NVIDIA NIM client. OpenAI-compatible endpoint, key from ~/.config/nvidia/api-key.

Mirrors the host setup convention so any standalone Python script can call
NIM the same way without touching pi or DSPy.
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Iterable

from openai import OpenAI

NIM_BASE_URL = "https://integrate.api.nvidia.com/v1"
DEFAULT_MODEL = "meta/llama-3.3-70b-instruct"


def _read_key() -> str:
    """Read the NIM API key from disk, with sane fallback to env."""
    key_path = Path(os.path.expanduser("~/.config/nvidia/api-key"))
    if key_path.is_file():
        key = key_path.read_text(encoding="utf-8").strip()
        if key:
            return key
    env = os.environ.get("NVIDIA_NIM_API_KEY") or os.environ.get("OPENAI_API_KEY")
    if env:
        return env
    raise RuntimeError(
        "No NVIDIA NIM API key found. Expected ~/.config/nvidia/api-key or "
        "the NVIDIA_NIM_API_KEY env var."
    )


def client() -> OpenAI:
    """Return an OpenAI client configured for NIM."""
    return OpenAI(api_key=_read_key(), base_url=NIM_BASE_URL)


def chat(
    messages: Iterable[dict],
    *,
    model: str = DEFAULT_MODEL,
    temperature: float = 0.2,
    max_tokens: int = 2048,
) -> str:
    """One-shot chat completion. Returns the assistant text."""
    rsp = client().chat.completions.create(
        model=model,
        messages=list(messages),
        temperature=temperature,
        max_tokens=max_tokens,
    )
    return rsp.choices[0].message.content or ""
