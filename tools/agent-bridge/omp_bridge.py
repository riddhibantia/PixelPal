#!/usr/bin/env python3
"""
PixelPal <-> OMP agent bridge.

Exposes OMP v18+ (opencode-family) agent status as the JSON envelope PixelPal
polls, so the floating pet shows what your coding agent is actually doing.

Reads OMP's own on-disk state (read-only):
  ~/.omp/agent/sessions/<cwd-slug>/<timestamp>_<session-id>.jsonl

Envelope contract (see AGENTS.md "AI Agent connection"):
    {
      "status": "IDLE" | "WORKING" | "WAITING_FOR_INPUT" | "COMPLETED" | "ERROR",
      "message": str,            # one human line for the pet bubble
      "currentTask": str | null, # what it's doing right now
      "progress": int | null,    # 0-100, omitted when unknown
      "pendingApproval": {...} | null
    }

Run:
    python3 omp_bridge.py                 # serve on 0.0.0.0:8787
    python3 omp_bridge.py --once          # print one envelope and exit (testing)
    python3 omp_bridge.py --port 9000
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

OMP_HOME = Path(os.environ.get("OMP_HOME", Path.home() / ".omp" / "agent"))
SESSIONS_DIR = OMP_HOME / "sessions"

# How long a session may go without a new event before we call it idle.
STALE_AFTER_SECONDS = 180
# Don't scan sessions older than this (cheap startup).
IGNORE_OLDER_THAN_SECONDS = 60 * 60 * 24 * 3

# Tools whose presence means the agent is waiting for a human decision.
APPROVAL_HINTS = ("ask_user", "askuser", "elicit", "confirm", "approve")

_state_lock = threading.Lock()


# --------------------------------------------------------------------------
# Session discovery
# --------------------------------------------------------------------------

def _session_files() -> list[Path]:
    """All session JSONL files, newest first, skipping stale ones."""
    if not SESSIONS_DIR.is_dir():
        return []
    cutoff = time.time() - IGNORE_OLDER_THAN_SECONDS
    found: list[tuple[float, Path]] = []
    for path in SESSIONS_DIR.rglob("*.jsonl"):
        try:
            mtime = path.stat().st_mtime
        except OSError:
            continue
        if mtime >= cutoff:
            found.append((mtime, path))
    found.sort(reverse=True)
    return [p for _, p in found]


def _project_name(path: Path) -> str:
    """`-projects-turfzo-app` -> `turfzo/app` (best-effort, for the pet bubble)."""
    slug = path.parent.name
    if slug.startswith("-"):
        slug = slug[1:]
    parts = [p for p in slug.split("-") if p]
    if parts and parts[0] in ("home", "usr", "opt", "var", "tmp", "mnt", "srv"):
        parts = parts[2:] if parts[0] == "home" and len(parts) > 1 else parts[1:]
    return "/".join(parts) if parts else "unknown"


def _session_title(path: Path) -> str | None:
    """OMP writes a `{"type":"title","title":...}` header as the first line."""
    try:
        with path.open("rb") as fh:
            first = fh.readline()
        event = json.loads(first)
        if event.get("type") == "title":
            title = str(event.get("title") or "").strip()
            return title or None
    except (OSError, json.JSONDecodeError, ValueError):
        pass
    return None


def _tail_events(path: Path, max_bytes: int = 64 * 1024) -> list[dict]:
    """Parse only the tail of a transcript — sessions can be many MB."""
    try:
        size = path.stat().st_size
        with path.open("rb") as fh:
            if size > max_bytes:
                fh.seek(size - max_bytes)
                fh.readline()  # drop the partial first line
            raw = fh.read()
    except OSError:
        return []

    events: list[dict] = []
    for line in raw.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            events.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return events


# --------------------------------------------------------------------------
# Transcript -> status
# --------------------------------------------------------------------------

_CMD_RE = re.compile(r'"command"\s*:\s*"((?:[^"\\]|\\.){1,120})"')
_FILE_RE = re.compile(r'"filePath"\s*:\s*"([^"]{1,120})"')
_BASH_PREFIX = re.compile(r"^\s*(\w+)")


# OMP prefixes session titles with an emoji glyph (e.g. "🛠 BOOK: ..."); the pet
# bubble renders better without it.
_LEADING_EMOJI = re.compile(r"^[^\w(]+")


def _short(text: str, limit: int = 90) -> str:
    text = " ".join(text.split())
    return text if len(text) <= limit else text[: limit - 1] + "…"


def _clean_title(title: str) -> str:
    """Drop OMP's leading emoji glyph + any dashes it uses as separators."""
    cleaned = _LEADING_EMOJI.sub("", title).strip(" -·|:")
    return cleaned or title


def _tool_summary(name: str, details: dict, content: str) -> str:
    """One short phrase describing a tool invocation."""
    blob = json.dumps(details) if details else content

    if name in ("bash", "shell", "exec"):
        match = _CMD_RE.search(blob) or _BASH_PREFIX.search(content)
        if match:
            return _short(match.group(1))
        return "running a command"

    if name in ("read", "view", "open"):
        match = _FILE_RE.search(blob)
        return f"reading {os.path.basename(match.group(1))}" if match else "reading files"

    if name in ("write", "create", "edit", "patch", "multiedit", "apply_patch"):
        match = _FILE_RE.search(blob)
        verb = "editing" if name in ("edit", "patch", "multiedit", "apply_patch") else "writing"
        return f"{verb} {os.path.basename(match.group(1))}" if match else f"{verb} files"

    if name in ("grep", "search", "find", "glob"):
        return "searching the codebase"

    if name in ("task", "agent", "subagent"):
        return "running a subagent"

    if any(h in name.lower() for h in APPROVAL_HINTS):
        return "waiting for your answer"

    if name in ("todowrite", "todo_write", "todoread"):
        return "planning"

    return f"using {name}" if name else "working"


def _status_from_events(events: list[dict], now: float) -> dict:
    """Derive the PixelPal envelope from a transcript's recent events."""
    if not events:
        return _idle("no recent activity")

    last = events[-1]
    msg = last.get("message") or {}
    role = msg.get("role")
    ts = msg.get("timestamp") or last.get("timestamp")

    age = None
    if isinstance(ts, (int, float)):
        age = now - (ts / 1000.0 if ts > 1e11 else float(ts))
    elif isinstance(ts, str):
        try:
            parsed = datetime.fromisoformat(ts.replace("Z", "+00:00"))
            age = now - parsed.timestamp()
        except ValueError:
            age = None

    # Stop reasons win: they say the turn ended.
    stop = msg.get("stopReason")
    if stop:
        if stop in ("aborted", "interrupted"):
            return _envelope("IDLE", "interrupted — waiting for you", None, None)
        if stop in ("error", "failed"):
            detail = _short(msg.get("errorMessage") or "agent reported an error")
            return _envelope("ERROR", detail, None, None)
        if stop in ("end_turn", "stop", "completed", "stop_sequence"):
            return _envelope("COMPLETED", "turn finished", None, None)

    # An assistant turn ending in a tool call = still working.
    if role == "assistant":
        calls = msg.get("content") or []
        tool_calls = [c for c in calls if isinstance(c, dict) and c.get("type") == "toolCall"]
        if tool_calls:
            call = tool_calls[-1]
            name = call.get("toolName") or call.get("name") or ""
            summary = _tool_summary(name, call.get("args") or call.get("details") or {},
                                    json.dumps(call))
            if age is not None and age > STALE_AFTER_SECONDS:
                return _idle(f"last: {summary}")
            return _envelope("WORKING", summary, summary, 50)
        # Plain text reply, turn not closed yet -> thinking.
        if age is not None and age <= STALE_AFTER_SECONDS:
            return _envelope("WORKING", "thinking", None, None)
        return _idle("idle")

    # Tool result just landed -> agent is about to continue.
    if role in ("toolResult", "tool"):
        if age is not None and age > STALE_AFTER_SECONDS:
            return _idle("idle")
        return _envelope("WORKING", "working", None, 50)

    # A user prompt with no reply yet -> starting up.
    if role == "user":
        if age is not None and age > STALE_AFTER_SECONDS:
            return _idle("waiting for a task")
        return _envelope("WORKING", "starting", None, 10)

    return _idle("idle")


def _envelope(status: str, message: str, task: str | None, progress: int | None) -> dict:
    env = {"status": status, "message": _short(message, 120)}
    if task:
        env["currentTask"] = _short(task, 80)
    if progress is not None:
        env["progress"] = max(0, min(100, progress))
    env["pendingApproval"] = None
    return env


def _idle(reason: str) -> dict:
    return {"status": "IDLE", "message": _short(reason, 120),
            "currentTask": None, "pendingApproval": None}


# --------------------------------------------------------------------------
# Public entry points
# --------------------------------------------------------------------------

def current_status() -> dict:
    """Newest active OMP session -> envelope. Never raises."""
    with _state_lock:
        try:
            files = _session_files()
            if not files:
                return _idle("no omp session found")
            newest = files[0]
            events = _tail_events(newest)
            envelope = _status_from_events(events, time.time())
            mtime = newest.stat().st_mtime
            envelope["sessionAgeSeconds"] = int(time.time() - mtime)
            envelope["project"] = _project_name(newest)
            title = _session_title(newest)
            if title:
                title = _clean_title(title)
                envelope["sessionTitle"] = _short(title, 80)
                if not envelope.get("currentTask"):
                    envelope["currentTask"] = _short(title, 80)
            return envelope
        except Exception as exc:  # a broken read must never drop the pet offline
            return _envelope("ERROR", f"bridge read failed: {exc}", None, None)


def handle_command(payload: dict) -> dict:
    """
    Commands from the app arrive as {"command": "..."}, approvals as
    {"approvalId": ..., "decision": "approve"|"deny"}.

    This bridge is READ-ONLY by design: it never drives omp's lifecycle, so a
    stray tap on the pet can't kill a running agent. Approvals are recorded for
    a human/driver to pick up rather than auto-executed.
    """
    if "approvalId" in payload:
        record = {"at": time.time(), **payload}
        _APPROVALS.insert(0, record)
        del _APPROVALS[50:]
        print(f"[approval] {payload.get('approvalId')} -> {payload.get('decision')}", flush=True)
        return {"ok": True, "action": "approval-recorded"}

    command = str(payload.get("command", "")).strip()
    print(f"[command] {command}", flush=True)
    with _state_lock:
        _COMMANDS.insert(0, {"at": time.time(), "command": command})
        del _COMMANDS[50:]
    return {"ok": True, "action": "queued", "note": "read-only bridge; see GET /commands"}


_COMMANDS: list[dict] = []
_APPROVALS: list[dict] = []


# --------------------------------------------------------------------------
# HTTP
# --------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    server_version = "PixelPalOmpBridge/1.0"

    def _json(self, obj: dict, code: int = 200) -> None:
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        if self.path.rstrip("/") in ("", "/status"):
            self._json(current_status())
        elif self.path.rstrip("/") == "/commands":
            self._json({"commands": _COMMANDS, "approvals": _APPROVALS})
        elif self.path.rstrip("/") == "/health":
            self._json({"ok": True, "sessions": len(_session_files())})
        else:
            self._json({"error": "not found"}, 404)

    def do_POST(self) -> None:  # noqa: N802
        length = int(self.headers.get("Content-Length") or 0)
        try:
            payload = json.loads(self.rfile.read(length) or b"{}")
        except json.JSONDecodeError:
            self._json({"error": "invalid json"}, 400)
            return
        self._json(handle_command(payload if isinstance(payload, dict) else {}))

    def log_message(self, fmt: str, *args) -> None:
        sys.stderr.write(f"{self.address_string()} - {fmt % args}\n")


def main() -> int:
    parser = argparse.ArgumentParser(description="PixelPal <-> OMP bridge")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--once", action="store_true", help="print one envelope and exit")
    args = parser.parse_args()

    if args.once:
        print(json.dumps(current_status(), indent=2))
        return 0

    if not SESSIONS_DIR.is_dir():
        print(f"warning: no OMP sessions at {SESSIONS_DIR}", file=sys.stderr)

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"PixelPal OMP bridge on http://{args.host}:{args.port}  (GET /status)", flush=True)
    print(f"watching {SESSIONS_DIR}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
