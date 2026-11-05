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
import time
import urllib.error
import urllib.request
from pathlib import Path

from ..reducer import STALE_DROP_SECONDS

DEFAULT_PORT = 4096
CONFIG_DIR = Path.home() / ".config" / "opencode"
# One opencode server accumulates a session for every conversation ever held.
# Only the newest few can possibly be live; the rest are history.
MAX_SESSIONS = 5


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


def _session_updated_at(session: dict) -> float | None:
    """Best-effort epoch-seconds of the session's last activity.

    opencode nests timestamps under `time` ({created, updated}) and has
    changed its schema across versions, so probe the known shapes. Values
    above 1e12 are milliseconds.
    """
    candidates = []
    t = session.get("time")
    if isinstance(t, dict):
        candidates += [t.get("updated"), t.get("created")]
    candidates += [session.get("updated"), session.get("created"), session.get("lastActive")]
    for value in candidates:
        if isinstance(value, (int, float)) and value > 0:
            return value / 1000.0 if value > 10**12 else float(value)
    return None


def poll_envelopes(port: int = DEFAULT_PORT) -> list[dict]:
    """
    Convert opencode sessions into PixelPal envelopes.

    opencode is polled (not pushed) because its server API is request/response;
    the polling interval is the bridge's responsibility.

    Every envelope carries `lastEventAgeSeconds` derived from the session's
    own timestamp — without it the reducer re-stamps "now" on each poll and
    old sessions pose as live forever. A session with no timestamp that is
    not busy cannot be proven live, so it is not announced at all.
    """
    sessions = [s for s in fetch_sessions(port) if isinstance(s, dict)]
    status_map = {
        "busy": "WORKING",
        "running": "WORKING",
        "working": "WORKING",
        "idle": "IDLE",
        "ready": "IDLE",
        "error": "ERROR",
    }
    now = time.time()
    ranked = sorted(
        ((_session_updated_at(s), s) for s in sessions),
        key=lambda pair: pair[0] or 0.0,
        reverse=True,
    )
    envelopes = []
    for updated_at, session in ranked[:MAX_SESSIONS]:
        status = str(session.get("status") or "").lower()
        mapped = status_map.get(status, "IDLE")
        if updated_at is not None:
            age = max(0, int(now - updated_at))
        elif mapped == "WORKING":
            age = 0
        else:
            # History with no clock: do not advertise a dead terminal.
            continue
        if age > STALE_DROP_SECONDS:
            continue
        envelopes.append(
            {
                "source": "opencode",
                "agentName": "opencode",
                "sessionId": str(session.get("id") or "default"),
                "project": str(session.get("directory") or "").split("/")[-1],
                "status": mapped,
                "message": str(session.get("title") or status or "idle")[:80],
                "currentTask": (session.get("title") or None),
                "pendingApproval": None,
                "lastEventAgeSeconds": age,
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
