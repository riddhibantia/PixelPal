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

import base64
import json
import os
import shutil
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

from ..reducer import STALE_DROP_SECONDS

DEFAULT_PORT = 4096
CONFIG_DIR = Path.home() / ".config" / "opencode"
STATE_FILE = Path.home() / ".local" / "state" / "opencode" / "service.json"
# One opencode server accumulates a session for every conversation ever held.
# Only the newest few can possibly be live; the rest are history.
MAX_SESSIONS = 5


def is_available() -> bool:
    return shutil.which("opencode") is not None


def _service_credentials() -> tuple[str, str | None]:
    if STATE_FILE.exists():
        try:
            data = json.loads(STATE_FILE.read_text())
            url = data.get("url")
            password = data.get("password")
            if url:
                return url.rstrip("/"), password
        except Exception:
            pass
    return f"http://127.0.0.1:{DEFAULT_PORT}", None


def _running_client_count() -> int:
    if "PYTEST_CURRENT_TEST" in os.environ:
        return 0
    try:
        out = subprocess.run(
            ["pgrep", "-a", "-x", "opencode"],
            capture_output=True,
            text=True,
            timeout=0.5,
        )
        count = 0
        for line in out.stdout.splitlines():
            parts = line.strip().split(maxsplit=1)
            if len(parts) >= 2:
                cmd = parts[1].strip()
                if (cmd == "opencode" or cmd.startswith("opencode ")) and "serve" not in cmd:
                    count += 1
        return count
    except Exception:
        return 0


def _base(port: int = DEFAULT_PORT) -> str:
    return f"http://127.0.0.1:{port}"


def probe(port: int = DEFAULT_PORT, timeout: float = 0.4) -> bool:
    """True when an opencode server is listening."""
    url, password = _service_credentials()
    if port != DEFAULT_PORT:
        url = _base(port)
    headers = {}
    if password:
        auth = base64.b64encode(f"opencode:{password}".encode()).decode()
        headers["Authorization"] = f"Basic {auth}"
    for path in ["/api/session", "/session"]:
        try:
            req = urllib.request.Request(f"{url}{path}", headers=headers)
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return 200 <= resp.status < 300
        except (urllib.error.URLError, OSError, ValueError):
            continue
    return False


def fetch_sessions(port: int = DEFAULT_PORT, timeout: float = 1.0) -> list[dict]:
    """Active opencode sessions, or [] when the server isn't running."""
    url, password = _service_credentials()
    if port != DEFAULT_PORT:
        url = _base(port)
    headers = {}
    if password:
        auth = base64.b64encode(f"opencode:{password}".encode()).decode()
        headers["Authorization"] = f"Basic {auth}"

    for path in ["/api/session", "/session"]:
        try:
            req = urllib.request.Request(f"{url}{path}", headers=headers)
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read()
            parsed = json.loads(raw or b"[]")
            if isinstance(parsed, dict) and "data" in parsed:
                parsed = parsed["data"]
            if isinstance(parsed, list):
                return parsed
        except (urllib.error.URLError, OSError, ValueError, json.JSONDecodeError):
            continue
    return []


def _session_updated_at(session: dict) -> float | None:
    """Best-effort epoch-seconds of the session's last activity.

    opencode nests timestamps under `time` ({created, updated, idle, busy})
    and has changed its schema across versions, so probe the known shapes.
    Values above 1e12 are milliseconds.
    """
    candidates = []
    t = session.get("time")
    if isinstance(t, dict):
        candidates += [t.get("updated"), t.get("idle"), t.get("busy"), t.get("created")]
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
    old sessions pose as live forever. If a terminal process is actively
    running on the system, its age is capped so the live terminal is not
    discarded while the user is reading or idle.
    """
    sessions = [s for s in fetch_sessions(port) if isinstance(s, dict)]
    status_map = {
        "busy": "WORKING",
        "running": "WORKING",
        "working": "WORKING",
        "idle": "IDLE",
        "ready": "IDLE",
        "error": "ERROR",
        "failed": "ERROR",
    }
    now = time.time()
    ranked = sorted(
        ((_session_updated_at(s), s) for s in sessions),
        key=lambda pair: pair[0] or 0.0,
        reverse=True,
    )
    client_count = _running_client_count()
    envelopes = []
    for idx, (updated_at, session) in enumerate(ranked[:MAX_SESSIONS]):
        status = str(session.get("status") or session.get("outcome") or "").lower()
        t = session.get("time") if isinstance(session.get("time"), dict) else {}
        busy_time = t.get("busy") or 0
        idle_time = t.get("idle") or 0
        if busy_time > idle_time:
            mapped = "WORKING"
        elif status in status_map:
            mapped = status_map[status]
        else:
            mapped = "IDLE"

        is_running_client = idx < client_count
        if updated_at is not None:
            age = max(0, int(now - updated_at))
            if is_running_client and age > STALE_DROP_SECONDS:
                age = min(age, 60)
        elif mapped == "WORKING":
            age = 0
        elif is_running_client:
            age = 60
        else:
            # History with no clock and no running process: do not advertise.
            continue

        if age > STALE_DROP_SECONDS:
            continue

        loc = session.get("location") if isinstance(session.get("location"), dict) else {}
        directory = loc.get("directory") or session.get("directory") or ""
        project = Path(directory).name if directory else ""

        envelopes.append(
            {
                "source": "opencode",
                "agentName": "opencode",
                "sessionId": str(session.get("id") or "default"),
                "project": project,
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
