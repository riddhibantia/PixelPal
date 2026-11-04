"""
opencode adapter.

opencode exposes two real integration surfaces (verified v-this-machine):
  * `opencode acp`   — Agent Client Protocol server (JSON-RPC over stdio)
  * `opencode serve` — HTTP API + event stream

We prefer `serve`'s event stream because it needs no process we must own:
the bridge polls `GET /event` (SSE) or `/session` for active sessions.

If neither is running, the bridge reports opencode as "not running" rather than
guessing — an absent agent is information, not an error.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import urllib.error
import urllib.request
from pathlib import Path

DEFAULT_PORT = 4096
CONFIG_DIR = Path.home() / ".config" / "opencode"


def is_available() -> bool:
    return shutil.which("opencode") is not None


def _base(port: int = DEFAULT_PORT) -> str:
    return f"http://127.0.0.1:{port}"


def probe(port: int = DEFAULT_PORT, timeout: float = 0.4) -> bool:
    """True when an opencode server is listening."""
    try:
        with urllib.request.urlopen(f"{_base(port)}/session", timeout=timeout) as resp:
            return 200 <= resp.status < 300
    except (urllib.error.URLError, OSError, ValueError):
        return False


def fetch_sessions(port: int = DEFAULT_PORT, timeout: float = 1.0) -> list[dict]:
    """Active opencode sessions, or [] when the server isn't running."""
    try:
        with urllib.request.urlopen(f"{_base(port)}/session", timeout=timeout) as resp:
            raw = resp.read()
        parsed = json.loads(raw or b"[]")
        return parsed if isinstance(parsed, list) else []
    except (urllib.error.URLError, OSError, ValueError, json.JSONDecodeError):
        return []


def poll_envelopes(port: int = DEFAULT_PORT) -> list[dict]:
    """
    Convert opencode sessions into PixelPal envelopes.

    opencode is polled (not pushed) because its server API is request/response;
    the polling interval is the bridge's responsibility.
    """
    envelopes = []
    for session in fetch_sessions(port):
        if not isinstance(session, dict):
            continue
        status = str(session.get("status") or "").lower()
        mapping = {
            "busy": "WORKING",
            "running": "WORKING",
            "working": "WORKING",
            "idle": "IDLE",
            "ready": "IDLE",
            "error": "ERROR",
        }
        envelopes.append(
            {
                "source": "opencode",
                "agentName": "opencode",
                "sessionId": str(session.get("id") or "default"),
                "project": str(session.get("directory") or "").split("/")[-1],
                "status": mapping.get(status, "IDLE"),
                "message": str(session.get("title") or status or "idle")[:80],
                "currentTask": (session.get("title") or None),
                "pendingApproval": None,
            }
        )
    return envelopes


def install() -> list[str]:
    """
    opencode needs no install step: the bridge talks to its HTTP server.

    We only write a small note so `doctor` can explain how to enable it.
    """
    note = CONFIG_DIR / "PIXELPAL.md"
    try:
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        note.write_text(
            "# PixelPal\n\n"
            "opencode is integrated by the PixelPal bridge talking to the local\n"
            "HTTP API. Start it with `opencode serve` (default port 4096) and the\n"
            "pet will show session status automatically.\n"
        )
        return [str(note)]
    except OSError:
        return []


def uninstall() -> int:
    try:
        (CONFIG_DIR / "PIXELPAL.md").unlink()
        return 1
    except OSError:
        return 0
