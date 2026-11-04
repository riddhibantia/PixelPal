"""
The reducer: the ONE place agent state is derived.

Adapters never decide state; they only translate payloads into [AgentEvent].
This keeps behaviour identical across Claude Code, Codex, OMP, opencode and
Hermes, and makes the whole state machine unit-testable with plain data.

Invariants:
  * Idempotent — a repeated event id is ignored.
  * Monotonic — an out-of-order (older) event never rewinds newer state.
  * Staleness-explicit — state decays to IDLE on its own clock, so a crashed
    agent cannot leave the pet "working" forever.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field

from .model import AgentEvent, AgentSource, EventKind, normalize_tool

# A turn that ended this long ago stops advertising COMPLETED.
COMPLETED_TTL_SECONDS = 90
# No events for this long means the agent is idle (or gone).
IDLE_AFTER_SECONDS = 300
# Detail strings are truncated to keep the wire payload small and redacted-ish.
MAX_DETAIL = 80


@dataclass
class AgentState:
    """Current state of ONE agent session."""

    source: AgentSource
    session_id: str
    project: str = ""
    status: str = "IDLE"          # PixelPal AgentState id
    message: str = "idle"
    task: str | None = None
    progress: int | None = None
    approval_id: str | None = None
    approval_action: str | None = None
    last_event_at: float = 0.0
    # Highest event timestamp ever applied: the monotonicity watermark.
    # Kept separate from last_event_at so a replayed old event cannot rewind.
    watermark: float = 0.0
    last_event_id: str = ""
    tools_completed: int = 0
    errors: int = 0

    def to_envelope(self) -> dict:
        """PixelPal's documented wire format."""
        envelope: dict = {
            "status": self.status,
            "message": self.message,
            "currentTask": self.task,
            "pendingApproval": None,
            "source": self.source.value,
            "agentName": self.source.display_name,
            "sessionId": self.session_id,
            "project": self.project,
            "lastEventAgeSeconds": max(0, int(time.time() - self.last_event_at))
            if self.last_event_at else None,
            "toolsCompleted": self.tools_completed,
        }
        if self.progress is not None:
            envelope["progress"] = self.progress
        if self.approval_id:
            envelope["pendingApproval"] = {
                "id": self.approval_id,
                "action": self.approval_action or "permission request",
                "detail": self.approval_action,
            }
        return envelope


def _short(text: str | None, limit: int = MAX_DETAIL) -> str | None:
    if not text:
        return None
    flat = " ".join(str(text).split())
    return flat if len(flat) <= limit else flat[: limit - 1] + "…"


_VERBS = {
    "Bash": "running",
    "Read": "reading",
    "Edit": "editing",
    "Write": "writing",
    "Grep": "searching",
    "Glob": "finding files",
    "List": "listing",
    "Task": "delegating",
    "Todo": "planning",
    "Web": "browsing",
    "AskUser": "asking you",
    "Plan": "planning",
}


def _working_message(tool: str | None, detail: str | None) -> str:
    if not tool:
        return "working"
    verb = _VERBS.get(tool, f"using {tool}")
    return f"{verb} {detail}" if detail else verb


def reduce_event(state: AgentState | None, event: AgentEvent) -> AgentState:
    """
    Apply one event, returning the new state.

    Pure with respect to wall-clock except for the final decay pass done by
    [decay]; callers that need determinism inject events with known timestamps.
    """
    if state is None:
        state = AgentState(source=event.source, session_id=event.session_id)

    # Idempotency + monotonicity guards.
    # 1. exact replay of the most recent event
    if event.id and event.id == state.last_event_id:
        return state
    # 2. any event older than the watermark (out-of-order arrival or replay)
    if event.at and state.watermark and event.at < state.watermark:
        return state

    state = AgentState(**{**state.__dict__})
    state.last_event_id = event.id
    if event.at:
        state.last_event_at = event.at
        state.watermark = max(state.watermark, event.at)
    else:
        state.last_event_at = time.time()
    if event.project:
        state.project = event.project

    kind = event.kind

    if kind is EventKind.SESSION_START:
        return _set(state, "IDLE", "session started", None, None)

    if kind is EventKind.SESSION_END:
        return _set(state, "DISCONNECTED", "session ended", None, None)

    if kind is EventKind.PROMPT:
        return _set(state, "WORKING", "thinking", _short(event.detail), None)

    if kind is EventKind.TOOL_START:
        tool = normalize_tool(event.tool)
        detail = _short(event.detail)
        state.task = tool
        return _set(state, "WORKING", _working_message(tool, detail), tool, None)

    if kind is EventKind.TOOL_END:
        tool = normalize_tool(event.tool)
        state.tools_completed += 1
        # Keep the tool as the current task: the agent is mid-turn and likely
        # to call another one, and repeating the name avoids a flicker to
        # "working" between every tool.
        return _set(state, "WORKING", _working_message(tool, None), tool, None)

    if kind is EventKind.TOOL_FAILURE:
        tool = normalize_tool(event.tool)
        state.errors += 1
        msg = _short(event.detail) or "tool failed"
        return _set(state, "WORKING", f"{_VERBS.get(tool, 'error')}: {msg}", tool, None)

    if kind is EventKind.MESSAGE:
        msg = _short(event.detail) or "reply ready"
        return _set(state, "WORKING", msg, state.task, None)

    if kind is EventKind.APPROVAL_REQUEST:
        action = _short(event.detail) or "permission request"
        state.approval_id = event.approval_id
        state.approval_action = action
        return _set(state, "WAITING_FOR_INPUT", f"waiting: {action}", action, None)

    if kind is EventKind.APPROVAL_RESOLVED:
        state.approval_id = None
        state.approval_action = None
        verdict = _short(event.detail) or "answered"
        return _set(state, "WORKING", verdict, state.task, None)

    if kind is EventKind.TURN_END:
        return _set(state, "COMPLETED", "turn finished", None, None)

    if kind is EventKind.ERROR:
        return _set(state, "ERROR", _short(event.detail) or "agent error", None, None)

    if kind is EventKind.HEARTBEAT:
        # Touches timestamps only, so a quiet-but-alive agent stays active.
        return state

    return state


def _set(
    state: AgentState, status: str, message: str, task: str | None, progress: int | None
) -> AgentState:
    state.status = status
    state.message = message
    state.task = task if task is not None else state.task
    state.progress = progress
    return state


def decay(state: AgentState, now: float | None = None) -> AgentState:
    """Age-based degradation. Pure; safe to call on a timer."""
    now = now or time.time()
    if not state.last_event_at:
        return state
    age = now - state.last_event_at

    if state.status in ("COMPLETED",) and age > COMPLETED_TTL_SECONDS:
        state = AgentState(**{**state.__dict__})
        return _set(state, "IDLE", "idle", None, None)

    if state.status in ("WORKING",) and age > IDLE_AFTER_SECONDS:
        state = AgentState(**{**state.__dict__})
        return _set(state, "IDLE", "no activity — agent may have exited", None, None)

    # An approval nobody answered must not look like an active prompt forever.
    if state.status == "WAITING_FOR_INPUT" and age > IDLE_AFTER_SECONDS:
        state = AgentState(**{**state.__dict__})
        state.approval_id = None
        state.approval_action = None
        return _set(state, "IDLE", "stale approval request", None, None)

    return state


# ---------------------------------------------------------------------------
# Multi-agent registry
# ---------------------------------------------------------------------------


@dataclass
class AgentRegistry:
    """
    All live agent sessions, keyed by (source, session_id).

    This is what makes PixelPal multi-agent: the app receives every session's
    envelope, not a single global status.
    """

    sessions: dict[tuple[str, str], AgentState] = field(default_factory=dict)
    _seen: set[str] = field(default_factory=set)

    def apply(self, event: AgentEvent) -> AgentState:
        key = (event.source.value, event.session_id or "default")
        if event.id:
            if event.id in self._seen:
                return self.sessions[key]
            self._seen.add(event.id)
            # Bounded memory: hooks are high-frequency.
            if len(self._seen) > 4096:
                self._seen = set(list(self._seen)[-2048:])
        current = self.sessions.get(key)
        updated = reduce_event(current, event)
        self.sessions[key] = updated
        return updated

    def tick(self, now: float | None = None) -> None:
        """Decay every session; drop sessions idle for a long time."""
        now = now or time.time()
        stale = []
        for key, state in self.sessions.items():
            self.sessions[key] = decay(state, now)
            if state.status == "DISCONNECTED" and now - state.last_event_at > 3600:
                stale.append(key)
        for key in stale:
            del self.sessions[key]

    def get(self, source: AgentSource, session_id: str) -> AgentState | None:
        return self.sessions.get((source.value, session_id or "default"))

    def active(self) -> list[AgentState]:
        """Sessions worth showing, most attention-worthy first."""
        rank = {
            "WAITING_FOR_INPUT": 0,
            "ERROR": 1,
            "WORKING": 2,
            "COMPLETED": 3,
            "ONLINE": 4,
            "IDLE": 5,
            "DISCONNECTED": 6,
        }
        states = [s for s in self.sessions.values() if s.status != "DISCONNECTED"]
        return sorted(states, key=lambda s: (rank.get(s.status, 9), -s.last_event_at))

    def to_list_payload(self) -> dict:
        """Wire format for the multi-agent endpoint."""
        return {
            "agents": [s.to_envelope() for s in self.active()],
            "count": len(self.sessions),
        }

    def primary(self) -> AgentState | None:
        """The agent the pet should embody: most attention-worthy, else newest."""
        active = self.active()
        return active[0] if active else None
