"""
Hermes adapter.

Hermes has NO hook system we can rely on (`--accept-hooks` gates *shell* hooks,
not status callbacks), and its gateway targets chat platforms. Its reliable
integration surface is its own SQLite state: `~/.hermes/state.db`, which has
`sessions` and `messages` tables with `role`, `tool_calls`, `finish_reason`
and `timestamp`.

Reading the DB directly is legitimate here because it's Hermes' own
persistence layer, opened READ-ONLY with an immutable flag so we can never
corrupt or lock the running agent.

Honest limitation, documented rather than hidden: DB polling is inherently
coarser than a push hook. Expect ~1-2s latency, not sub-second.
"""

from __future__ import annotations

import json
import shutil
import sqlite3
import time
from pathlib import Path

from ..reducer import STALE_DROP_SECONDS

STATE_DB = Path.home() / ".hermes" / "state.db"

# A DB row is history, not a live signal: without this, a session that ended
# hours ago would still read "WORKING" forever because its last message says so.
STALE_AFTER_SECONDS = 180


def is_available() -> bool:
    return shutil.which("hermes") is not None or STATE_DB.exists()


def _connect(path: Path = STATE_DB) -> sqlite3.Connection | None:
    """Read-only, immutable connection. Never blocks or mutates the writer."""
    if not path.exists():
        return None
    try:
        uri = f"file:{path}?mode=ro"
        conn = sqlite3.connect(uri, uri=True, timeout=0.5)
        conn.row_factory = sqlite3.Row
        return conn
    except sqlite3.Error:
        return None


def _recent_sessions(conn: sqlite3.Connection, limit: int = 5) -> list[str]:
    try:
        rows = conn.execute(
            "SELECT session_id FROM messages "
            "GROUP BY session_id ORDER BY MAX(timestamp) DESC LIMIT ?",
            (limit,),
        ).fetchall()
        return [r["session_id"] for r in rows if r["session_id"]]
    except sqlite3.Error:
        return []


def _last_messages(conn: sqlite3.Connection, session_id: str, limit: int = 4) -> list[dict]:
    try:
        rows = conn.execute(
            "SELECT role, content, tool_calls, tool_name, finish_reason, timestamp "
            "FROM messages WHERE session_id = ? ORDER BY timestamp DESC LIMIT ?",
            (session_id, limit),
        ).fetchall()
        return [dict(r) for r in reversed(rows)]
    except sqlite3.Error:
        return []


_STOP_REASONS_COMPLETED = {"stop", "end_turn", "completed", "stop_sequence"}


def _first_tool_call(raw: str | None) -> tuple[str | None, str | None]:
    """Extract (tool_name, short_detail) from an assistant tool_calls blob."""
    if not raw:
        return None, None
    try:
        parsed = json.loads(raw)
    except (json.JSONDecodeError, TypeError):
        return None, None
    if not isinstance(parsed, list) or not parsed:
        return None, None
    call = parsed[-1]
    if not isinstance(call, dict):
        return None, None
    fn = call.get("function") if isinstance(call.get("function"), dict) else call
    name = str(fn.get("name") or "") or None
    args = fn.get("arguments")
    detail = None
    if isinstance(args, str):
        try:
            args = json.loads(args)
        except json.JSONDecodeError:
            args = None
    if isinstance(args, dict):
        for key in ("command", "file_path", "path", "pattern", "query", "description"):
            if args.get(key):
                detail = " ".join(str(args[key]).split())[:60]
                break
    return name, detail


def poll_envelopes(limit: int = 5) -> list[dict]:
    """
    Hermes sessions -> PixelPal envelopes.

    Returns [] when Hermes isn't running/installed, which callers treat as
    "agent absent", not as an error.
    """
    conn = _connect()
    if conn is None:
        return []
    try:
        envelopes = []
        for session_id in _recent_sessions(conn, limit):
            messages = _last_messages(conn, session_id)
            if not messages:
                continue
            envelopes.append(_envelope_for(session_id, messages))
        return [e for e in envelopes if e]
    finally:
        conn.close()


def _age_of(message: dict) -> float | None:
    """Seconds since the message was written, or None if unknown."""
    ts = message.get("timestamp")
    if not isinstance(ts, (int, float)):
        return None
    # Hermes stores epoch seconds as REAL.
    return max(0.0, time.time() - float(ts))


def _envelope_for(session_id: str, messages: list[dict]) -> dict | None:
    last = messages[-1]
    age = _age_of(last)
    # Same rule as the reducer: no activity for 15 minutes is a dead
    # terminal, not a status. Re-announcing it every poll is exactly the
    # "graveyard of stale shells" the phone should never show.
    if age is None or age > STALE_DROP_SECONDS:
        return None
    role = str(last.get("role") or "")
    finish = (last.get("finish_reason") or "").lower()

    base = {
        "source": "hermes",
        "agentName": "Hermes",
        "sessionId": session_id,
        "project": "",
        "pendingApproval": None,
        # Carry the real age so the reducer doesn't re-stamp "now" on every
        # poll and keep a dead session looking live.
        "lastEventAgeSeconds": int(age) if age is not None else None,
    }

    status, message, task = _derive(role, last, finish)

    # DB polling cannot observe "the agent stopped thinking"; if the newest row
    # is old, the honest answer is idle rather than a stale WORKING.
    if status == "WORKING" and age is not None and age > STALE_AFTER_SECONDS:
        return {**base, "status": "IDLE",
                "message": "no recent activity" if not task else f"last: {task}",
                "currentTask": task}
    return {**base, "status": status, "message": message, "currentTask": task}


def _derive(role: str, last: dict, finish: str) -> tuple[str, str, str | None]:
    """Pure role/finish -> (status, message, task)."""
    if role == "assistant":
        if finish in _STOP_REASONS_COMPLETED:
            return "COMPLETED", "turn finished", None
        tool, detail = _first_tool_call(last.get("tool_calls"))
        if tool:
            label = f"using {tool}"
            if detail:
                label = f"{label} {detail}"
            return "WORKING", label[:80], tool
        text = str(last.get("content") or "").strip()
        return "WORKING", (text.splitlines()[0][:80] if text else "thinking"), None

    if role == "tool":
        name = last.get("tool_name") or "tool"
        return "WORKING", f"{name} finished", str(name)

    if role == "user":
        text = str(last.get("content") or "").strip()
        return "WORKING", (text.splitlines()[0][:60] if text else "starting"), None

    return "IDLE", "idle", None


def install() -> list[str]:
    """Nothing to install — Hermes is read from its own state DB."""
    return []


def uninstall() -> int:
    return 0
