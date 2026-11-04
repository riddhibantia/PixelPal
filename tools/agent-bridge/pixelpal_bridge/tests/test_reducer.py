"""
Reducer tests: the state machine is the core of the product, so it is
specified exhaustively here. No network, no agents, no clock dependence.
"""

from __future__ import annotations

import time

import pytest

from pixelpal_bridge.model import AgentEvent, AgentSource, EventKind, normalize_tool
from pixelpal_bridge.reducer import (
    COMPLETED_TTL_SECONDS,
    IDLE_AFTER_SECONDS,
    AgentRegistry,
    AgentState,
    decay,
    reduce_event,
)


def ev(kind: EventKind, **kw) -> AgentEvent:
    kw.setdefault("source", AgentSource.CLAUDE_CODE)
    kw.setdefault("session_id", "s1")
    return AgentEvent(kind=kind, **kw)


@pytest.fixture
def t0() -> float:
    return 1_700_000_000.0


class TestBasicTransitions:
    def test_session_start_is_idle(self, t0):
        s = reduce_event(None, ev(EventKind.SESSION_START, at=t0))
        assert s.status == "IDLE"

    def test_prompt_makes_working(self, t0):
        s = reduce_event(None, ev(EventKind.PROMPT, detail="fix tests", at=t0))
        assert s.status == "WORKING"
        assert s.message == "thinking"

    def test_tool_start_names_the_action(self, t0):
        s = reduce_event(None, ev(EventKind.TOOL_START, tool="Edit", detail="Main.kt", at=t0))
        assert s.status == "WORKING"
        assert s.task == "Edit"
        assert "editing" in s.message and "Main.kt" in s.message

    def test_tool_end_counts_and_stays_working(self, t0):
        s = reduce_event(None, ev(EventKind.TOOL_START, tool="Bash", at=t0))
        s = reduce_event(s, ev(EventKind.TOOL_END, tool="Bash", at=t0 + 1))
        assert s.tools_completed == 1
        assert s.status == "WORKING"

    def test_turn_end_completes(self, t0):
        s = reduce_event(None, ev(EventKind.TURN_END, at=t0))
        assert s.status == "COMPLETED"

    def test_error_marks_error(self, t0):
        s = reduce_event(None, ev(EventKind.ERROR, detail="rate limit", at=t0))
        assert s.status == "ERROR"
        assert "rate limit" in s.message

    def test_session_end_disconnects(self, t0):
        s = reduce_event(None, ev(EventKind.SESSION_END, at=t0))
        assert s.status == "DISCONNECTED"


class TestApprovals:
    def test_request_waits_for_input(self, t0):
        s = reduce_event(
            None,
            ev(
                EventKind.APPROVAL_REQUEST,
                approval_id="a1",
                detail="rm -rf build",
                at=t0,
            ),
        )
        assert s.status == "WAITING_FOR_INPUT"
        envelope = s.to_envelope()
        assert envelope["pendingApproval"]["id"] == "a1"
        assert envelope["pendingApproval"]["action"] == "rm -rf build"

    def test_resolution_clears_approval(self, t0):
        s = reduce_event(None, ev(EventKind.APPROVAL_REQUEST, approval_id="a1", at=t0))
        s = reduce_event(s, ev(EventKind.APPROVAL_RESOLVED, at=t0 + 1))
        assert s.approval_id is None
        assert s.to_envelope()["pendingApproval"] is None
        assert s.status == "WORKING"

    def test_stale_approval_decays(self, t0):
        s = reduce_event(None, ev(EventKind.APPROVAL_REQUEST, approval_id="a1", at=t0))
        s = decay(s, now=t0 + IDLE_AFTER_SECONDS + 1)
        assert s.status == "IDLE"
        assert s.approval_id is None


class TestIdempotencyAndOrdering:
    def test_duplicate_event_is_ignored(self, t0):
        event = ev(EventKind.TOOL_START, tool="Bash", at=t0, id="dup-1")
        s = reduce_event(None, event)
        first_count = s.tools_completed
        s2 = reduce_event(s, ev(EventKind.TOOL_END, tool="Bash", at=t0 + 1, id="e2"))
        s3 = reduce_event(s2, event)  # replay of the OLD event
        assert s3.last_event_id == "e2"
        assert s3.tools_completed == s2.tools_completed == first_count + 1

    def test_out_of_order_older_event_does_not_rewind(self, t0):
        s = reduce_event(None, ev(EventKind.TOOL_START, tool="Edit", at=t0 + 100, id="new"))
        s = reduce_event(s, ev(EventKind.TURN_END, at=t0 + 50, id="old"))
        assert s.last_event_id == "new"      # older event dropped
        assert s.status == "WORKING"

    def test_repeated_tool_end_does_not_double_count(self, t0):
        s = reduce_event(None, ev(EventKind.TOOL_END, tool="Read", at=t0, id="x"))
        s = reduce_event(s, ev(EventKind.TOOL_END, tool="Read", at=t0, id="x"))
        assert s.tools_completed == 1


class TestDecay:
    def test_completed_expires_to_idle(self, t0):
        s = reduce_event(None, ev(EventKind.TURN_END, at=t0))
        assert decay(s, now=t0 + COMPLETED_TTL_SECONDS - 1).status == "COMPLETED"
        assert decay(s, now=t0 + COMPLETED_TTL_SECONDS + 1).status == "IDLE"

    def test_working_goes_idle_when_silent(self, t0):
        s = reduce_event(None, ev(EventKind.TOOL_START, tool="Bash", at=t0))
        assert decay(s, now=t0 + 10).status == "WORKING"
        assert decay(s, now=t0 + IDLE_AFTER_SECONDS + 1).status == "IDLE"

    def test_heartbeat_keeps_working_alive(self, t0):
        s = reduce_event(None, ev(EventKind.TOOL_START, tool="Bash", at=t0))
        s = reduce_event(s, ev(EventKind.HEARTBEAT, at=t0 + IDLE_AFTER_SECONDS - 5))
        assert decay(s, now=t0 + IDLE_AFTER_SECONDS + 1).status == "WORKING"


class TestToolNormalization:
    @pytest.mark.parametrize(
        "raw,expected",
        [
            ("bash", "Bash"),
            ("Bash", "Bash"),
            ("apply_patch", "Edit"),
            ("MultiEdit", "Edit"),
            ("write", "Write"),
            ("AskUserQuestion", "AskUser"),
            ("mcp__graft__Read", "Read"),
            ("todoread", "Todo"),
            ("some_future_tool", "some_future_tool"),
            (None, None),
        ],
    )
    def test_aliases(self, raw, expected):
        assert normalize_tool(raw) == expected


class TestMultiAgentRegistry:
    def test_two_agents_are_independent(self, t0):
        reg = AgentRegistry()
        reg.apply(
            AgentEvent(
                source=AgentSource.CLAUDE_CODE, kind=EventKind.TOOL_START,
                session_id="claude-1", tool="Edit", at=t0,
            )
        )
        reg.apply(
            AgentEvent(
                source=AgentSource.OMP, kind=EventKind.TURN_END,
                session_id="omp-1", at=t0,
            )
        )
        claude = reg.get(AgentSource.CLAUDE_CODE, "claude-1")
        omp = reg.get(AgentSource.OMP, "omp-1")
        assert claude.status == "WORKING"
        assert omp.status == "COMPLETED"

    def test_payload_lists_all_agents(self, t0):
        reg = AgentRegistry()
        for src, sid in ((AgentSource.CLAUDE_CODE, "a"), (AgentSource.CODEX, "b")):
            reg.apply(
                AgentEvent(source=src, kind=EventKind.TOOL_START, session_id=sid, at=t0)
            )
        payload = reg.to_list_payload()
        assert payload["count"] == 2
        assert {a["source"] for a in payload["agents"]} == {"claude_code", "codex"}

    def test_waiting_agent_sorts_first(self, t0):
        reg = AgentRegistry()
        reg.apply(
            AgentEvent(
                source=AgentSource.CLAUDE_CODE, kind=EventKind.TOOL_START,
                session_id="busy", at=t0 + 50,
            )
        )
        reg.apply(
            AgentEvent(
                source=AgentSource.CODEX, kind=EventKind.APPROVAL_REQUEST,
                session_id="waiting", approval_id="x", at=t0,
            )
        )
        # Attention outranks recency: the blocked agent is what you must see.
        assert reg.primary().source is AgentSource.CODEX

    def test_duplicate_events_across_registry_are_deduped(self, t0):
        reg = AgentRegistry()
        event = AgentEvent(
            source=AgentSource.CLAUDE_CODE, kind=EventKind.TOOL_END,
            session_id="s", tool="Read", at=t0, id="same-id",
        )
        reg.apply(event)
        reg.apply(event)
        assert reg.get(AgentSource.CLAUDE_CODE, "s").tools_completed == 1

    def test_stale_disconnected_sessions_are_evicted(self, t0):
        reg = AgentRegistry()
        reg.apply(
            AgentEvent(
                source=AgentSource.CLAUDE_CODE, kind=EventKind.SESSION_END,
                session_id="gone", at=t0,
            )
        )
        reg.tick(now=t0 + 7200)
        assert reg.get(AgentSource.CLAUDE_CODE, "gone") is None


class TestEnvelopeContract:
    def test_envelope_has_required_keys(self, t0):
        s = reduce_event(None, ev(EventKind.TOOL_START, tool="Bash", detail="npm test", at=t0))
        env = s.to_envelope()
        assert env["status"] == "WORKING"
        assert isinstance(env["message"], str)
        assert env["source"] == "claude_code"
        assert env["agentName"] == "Claude Code"
        assert env["pendingApproval"] is None

    def test_detail_is_truncated(self, t0):
        s = reduce_event(
            None,
            ev(EventKind.TOOL_START, tool="Bash", detail="x" * 500, at=t0),
        )
        assert len(s.to_envelope()["message"]) < 120
