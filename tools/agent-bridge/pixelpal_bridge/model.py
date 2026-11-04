"""
Normalized agent event model.

Every adapter (Claude Code, Codex, OMP, opencode, Hermes, generic) translates
its native payload into an [AgentEvent]. Business logic lives ONLY in the
reducer — adapters are dumb translators. That separation is what makes adding
the Nth agent cheap and keeps state derivation testable without running any
agent.

Deliberately dependency-free so it can be unit-tested anywhere.
"""

from __future__ import annotations

import time
import uuid
from dataclasses import dataclass, field, asdict
from enum import Enum


class AgentSource(str, Enum):
    """Which agent produced the event."""

    CLAUDE_CODE = "claude_code"
    # `agy` is byte-compatible with Claude Code, so it shares the adapter.
    AGY = "agy"
    CODEX = "codex"
    OMP = "omp"
    OPENCODE = "opencode"
    HERMES = "hermes"
    GENERIC = "generic"

    @property
    def display_name(self) -> str:
        return _DISPLAY_NAMES.get(self, self.value)


_DISPLAY_NAMES = {
    AgentSource.CLAUDE_CODE: "Claude Code",
    AgentSource.AGY: "agy",
    AgentSource.CODEX: "Codex",
    AgentSource.OMP: "OMP",
    AgentSource.OPENCODE: "opencode",
    AgentSource.HERMES: "Hermes",
    AgentSource.GENERIC: "Agent",
}


class EventKind(str, Enum):
    """What happened. Mirrors the union of all agents' lifecycle hooks."""

    SESSION_START = "session_start"
    SESSION_END = "session_end"
    PROMPT = "prompt"
    TOOL_START = "tool_start"
    TOOL_END = "tool_end"
    TOOL_FAILURE = "tool_failure"
    MESSAGE = "message"
    APPROVAL_REQUEST = "approval_request"
    APPROVAL_RESOLVED = "approval_resolved"
    TURN_END = "turn_end"
    ERROR = "error"
    HEARTBEAT = "heartbeat"


# Canonical tool names so the pet can say "editing" regardless of agent dialect.
# Keys are lowercase with `-`/`_` stripped so "apply_patch", "applypatch" and
# "Apply-Patch" all resolve. build_aliases() below asserts this invariant.
_TOOL_ALIASES = {
    # Claude Code / agy / Codex
    "bash": "Bash", "shell": "Bash", "exec": "Bash", "command": "Bash",
    "read": "Read", "view": "Read", "cat": "Read",
    "edit": "Edit", "multiedit": "Edit", "patch": "Edit", "applypatch": "Edit",
    "write": "Write", "create": "Write",
    "grep": "Grep", "search": "Grep", "glob": "Glob", "find": "Glob", "ls": "List",
    "task": "Task", "agent": "Task", "subagent": "Task",
    "todowrite": "Todo", "todoread": "Todo",
    "askuserquestion": "AskUser", "askuser": "AskUser", "exitplanmode": "Plan",
    "webfetch": "Web", "websearch": "Web",
}


def normalize_tool(name: str | None) -> str | None:
    """Map an agent-specific tool name onto a canonical one."""
    if not name:
        return None
    key = name.strip().lower().replace("-", "").replace("_", "")
    # OpenCode/MCP tools arrive namespaced (mcp__server__tool); keep the leaf.
    leaf = name.split("__")[-1].lower().replace("-", "").replace("_", "")
    return _TOOL_ALIASES.get(key) or _TOOL_ALIASES.get(leaf) or name


@dataclass(frozen=True)
class AgentEvent:
    """
    One normalized lifecycle event.

    [id] is the idempotency key: hooks can fire twice (async + sync
    registration, retries), and replaying a duplicate must not corrupt state.
    """

    source: AgentSource
    kind: EventKind
    session_id: str
    project: str = ""
    tool: str | None = None
    detail: str | None = None
    approval_id: str | None = None
    at: float = field(default_factory=time.time)
    id: str = field(default_factory=lambda: uuid.uuid4().hex)

    def to_dict(self) -> dict:
        d = asdict(self)
        d["source"] = self.source.value
        d["kind"] = self.kind.value
        return d

    @classmethod
    def from_dict(cls, raw: dict) -> "AgentEvent":
        return cls(
            id=str(raw.get("id") or uuid.uuid4().hex),
            source=AgentSource(raw.get("source", "generic")),
            kind=EventKind(raw["kind"]),
            session_id=str(raw.get("session_id") or ""),
            project=str(raw.get("project") or ""),
            tool=raw.get("tool"),
            detail=raw.get("detail"),
            approval_id=raw.get("approval_id"),
            at=float(raw.get("at") or time.time()),
        )

    @property
    def dedupe_key(self) -> str:
        """Stable key for duplicate suppression (crash-safe across restarts)."""
        return f"{self.source.value}:{self.session_id}:{self.kind.value}:{self.id}"
