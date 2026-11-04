"""
Adapter for the Claude-Code-style hook contract.

Covers THREE agents from one implementation because they are protocol-
compatible (verified on this machine):

  * Claude Code  — stdin JSON, events below
  * agy          — byte-identical CLI flags to Claude Code, same hooks
  * Codex        — same event names + stdin JSON contract

Reference: https://code.claude.com/docs/en/hooks

Input arrives on stdin as one JSON object per hook invocation. We must:
  * translate it to an [AgentEvent];
  * forward it to the bridge (fire-and-forget, hard timeout);
  * for PermissionRequest, optionally block for a decision and print it.

Everything is best-effort: if the bridge is down we exit 0 immediately so the
agent never hangs. That is the hard rule — a dead companion must not break work.
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path

from ..model import AgentEvent, AgentSource, EventKind, normalize_tool

# Which tool names indicate the agent is asking the user something rather than
# requesting a permission. Claude surfaces these as tool calls, not hooks.
_INTERACTIVE_TOOLS = {"askuserquestion", "ask_user", "exitplanmode"}


def _source_from_env(default: AgentSource = AgentSource.CLAUDE_CODE) -> AgentSource:
    """Resolve which agent invoked us. Hooks are installed per agent."""
    explicit = os.environ.get("PIXELPAL_AGENT_SOURCE", "").strip().lower()
    for candidate in AgentSource:
        if explicit == candidate.value:
            return candidate
    # Fall back to argv hint (bridge installs `--source <agent>`).
    for i, arg in enumerate(sys.argv):
        if arg == "--source" and i + 1 < len(sys.argv):
            try:
                return AgentSource(sys.argv[i + 1])
            except ValueError:
                pass
    return default


def _project_name(cwd: str | None) -> str:
    if not cwd:
        return ""
    return Path(cwd).name or cwd


def _detail_for(tool_name: str, tool_input: dict | None) -> str | None:
    """Short, human-meaningful descriptor. NEVER emits file contents."""
    if not isinstance(tool_input, dict):
        return None
    tool = normalize_tool(tool_name)

    if tool == "Bash":
        cmd = tool_input.get("command") or tool_input.get("cmd")
        if isinstance(cmd, str):
            first = " ".join(cmd.split())
            return first[:70] or None
        return None
    if tool in ("Read", "Edit", "Write"):
        path = (
            tool_input.get("file_path")
            or tool_input.get("path")
            or tool_input.get("filePath")
        )
        return Path(str(path)).name if path else None
    if tool in ("Grep", "Glob"):
        pat = tool_input.get("pattern") or tool_input.get("query")
        return str(pat)[:60] if pat else None
    if tool == "Task":
        desc = tool_input.get("description") or tool_input.get("prompt")
        return str(desc)[:60] if desc else None
    # Unknown tool: expose only the key names, never values.
    keys = ", ".join(list(tool_input)[:3])
    return keys or None


def translate(payload: dict, source: AgentSource) -> list[AgentEvent]:
    """
    Claude-family payload -> normalized events.

    Returns a list because one native hook maps to at most one event today,
    but keeping the shape uniform lets future adapters emit several.
    """
    if not isinstance(payload, dict):
        return []

    hook = str(payload.get("hook_event_name") or payload.get("event") or "")
    session = str(payload.get("session_id") or payload.get("sessionId") or "-")
    project = _project_name(payload.get("cwd"))

    def ev(kind: EventKind, **kw) -> list[AgentEvent]:
        return [
            AgentEvent(
                source=source,
                kind=kind,
                session_id=session,
                project=project,
                **kw,
            )
        ]

    tool_name = str(payload.get("tool_name") or "")
    tool_input = payload.get("tool_input")
    tool = normalize_tool(tool_name) if tool_name else None
    detail = _detail_for(tool_name, tool_input)

    if hook == "SessionStart":
        return ev(EventKind.SESSION_START, detail=str(payload.get("source") or "") or None)

    if hook == "SessionEnd":
        return ev(EventKind.SESSION_END, detail=str(payload.get("reason") or "") or None)

    if hook == "UserPromptSubmit":
        return ev(EventKind.PROMPT, detail=_short_prompt(payload.get("prompt")))

    if hook == "PreToolUse":
        # AskUserQuestion / ExitPlanMode are interactive: the agent is blocked
        # on a human, which is exactly WAITING_FOR_INPUT.
        if tool_name.lower() in _INTERACTIVE_TOOLS:
            return ev(
                EventKind.APPROVAL_REQUEST,
                tool=tool,
                detail=detail or tool_name,
                approval_id=f"{session}:{payload.get('tool_use_id') or tool_name}",
            )
        return ev(EventKind.TOOL_START, tool=tool, detail=detail)

    if hook == "PostToolUse":
        if tool_name.lower() in _INTERACTIVE_TOOLS:
            return ev(EventKind.APPROVAL_RESOLVED, tool=tool, detail="answered")
        return ev(EventKind.TOOL_END, tool=tool, detail=detail)

    if hook == "PostToolUseFailure":
        return ev(
            EventKind.TOOL_FAILURE,
            tool=tool,
            detail=_first_line(payload.get("error")),
        )

    if hook == "PermissionRequest":
        return ev(
            EventKind.APPROVAL_REQUEST,
            tool=tool,
            detail=detail or tool_name or "permission request",
            approval_id=_stable_approval_id(session, tool_name, tool_input),
        )

    if hook == "Notification":
        ntype = str(payload.get("notification_type") or payload.get("matcher") or "")
        if "permission" in ntype:
            return ev(
                EventKind.APPROVAL_REQUEST,
                detail=payload.get("message") or "permission prompt",
                approval_id=f"{session}:notification",
            )
        return []  # stray notifications are noise, not state

    if hook == "Stop":
        return ev(EventKind.TURN_END, detail=_first_line(payload.get("last_assistant_message")))

    if hook == "StopFailure":
        return ev(EventKind.ERROR, detail=_first_line(payload.get("error")))

    if hook == "SubagentStart":
        return ev(EventKind.PROMPT, detail=f"subagent {payload.get('agent_type') or ''}".strip())

    if hook == "SubagentStop":
        return []  # the parent turn is still running

    # Unknown/future events: heartbeat only, so the session stays alive.
    if hook:
        return ev(EventKind.HEARTBEAT)
    return []


def _stable_approval_id(session: str, tool_name: str, tool_input) -> str:
    """
    Deterministic approval id.

    MUST NOT use Python's hash(): it is randomized per process
    (PYTHONHASHSEED), so the hook process and any other process would compute
    different ids for the same request and the verdict would never match.
    SHA-256 of the canonical input is stable everywhere.
    """
    import hashlib

    try:
        canonical = json.dumps(tool_input, sort_keys=True, default=str)
    except (TypeError, ValueError):
        canonical = str(tool_input)
    digest = hashlib.sha256(canonical.encode()).hexdigest()[:12]
    return f"{session}:{tool_name}:{digest}"


def _first_line(text) -> str | None:
    """
    Most informative single line of an error/stderr blob.

    Skips boilerplate like "Exit code 1" — the user needs the actual reason,
    and the pet bubble only has room for one line.
    """
    if not isinstance(text, str) or not text.strip():
        return None
    lines = [ln.strip() for ln in text.strip().splitlines() if ln.strip()]
    if not lines:
        return None
    boilerplate = ("exit code", "exit status", "error: exit", "process exited")
    for line in lines:
        if not line.lower().startswith(boilerplate):
            return line[:80]
    return lines[0][:80]


def _short_prompt(prompt) -> str | None:
    """Prompts are user text; keep only a short prefix (redaction-aware)."""
    if not isinstance(prompt, str) or not prompt.strip():
        return None
    return " ".join(prompt.split())[:60]


def read_payload(stream=None) -> dict:
    """Read one JSON object from stdin; tolerate empty/garbage input."""
    stream = stream or sys.stdin
    try:
        raw = stream.read()
    except (OSError, ValueError):
        return {}
    raw = (raw or "").strip()
    if not raw:
        return {}
    try:
        parsed = json.loads(raw)
        return parsed if isinstance(parsed, dict) else {}
    except json.JSONDecodeError:
        return {}


def build_decision_output(approved: bool, message: str = "") -> dict:
    """
    The PermissionRequest output that lets PixelPal answer for the user.

    Shape verified against the Claude Code hooks reference:
      {"hookSpecificOutput": {"hookEventName": "PermissionRequest",
                              "decision": {"behavior": "allow"|"deny"}}}
    """
    decision: dict = {"behavior": "allow" if approved else "deny"}
    if not approved:
        decision["message"] = message or "Denied from PixelPal"
    return {
        "hookSpecificOutput": {
            "hookEventName": "PermissionRequest",
            "decision": decision,
        }
    }
