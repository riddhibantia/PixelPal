"""
Adapter contract tests.

Every payload here is copied VERBATIM from the official Claude Code hooks
reference (https://code.claude.com/docs/en/hooks), so if the adapter drifts
from the documented contract these tests fail. agy and Codex share this
adapter because they speak the same protocol.
"""

from __future__ import annotations

import json

import pytest

from pixelpal_bridge.adapters import claude_code
from pixelpal_bridge.model import AgentSource, EventKind

# --- verbatim from the docs ------------------------------------------------

PRE_TOOL_USE = {
    "session_id": "abc123",
    "prompt_id": "550e8400-e29b-41d4-a716-446655440000",
    "transcript_path": "/home/user/.claude/projects/.../transcript.jsonl",
    "cwd": "/home/user/my-project",
    "scratchpad_dir": "/tmp/claude-1000/-home-user-my-project/abc123/scratchpad",
    "permission_mode": "default",
    "hook_event_name": "PreToolUse",
    "tool_name": "Bash",
    "tool_input": {
        "command": "npm test",
        "description": "Run test suite",
        "timeout": 120000,
        "run_in_background": False,
    },
    "tool_use_id": "toolu_01ABC123...",
}

POST_TOOL_USE_FAILURE = {
    "session_id": "abc123",
    "transcript_path": "/Users/.../00893aaf.jsonl",
    "cwd": "/Users/...",
    "permission_mode": "default",
    "hook_event_name": "PostToolUseFailure",
    "tool_name": "Bash",
    "tool_input": {"command": "npm test", "description": "Run test suite"},
    "tool_use_id": "toolu_01ABC123...",
    "error": "Exit code 1\nError: Cannot find module 'express'",
    "is_interrupt": False,
    "duration_ms": 4187,
}

PERMISSION_REQUEST = {
    "session_id": "abc123",
    "transcript_path": "/Users/.../00893aaf.jsonl",
    "cwd": "/Users/...",
    "permission_mode": "default",
    "hook_event_name": "PermissionRequest",
    "tool_name": "Bash",
    "tool_input": {
        "command": "rm -rf node_modules",
        "description": "Remove node_modules directory",
    },
    "permission_suggestions": [{"type": "addRules", "destination": "session"}],
}

SUBAGENT_START = {
    "session_id": "abc123",
    "transcript_path": "/Users/.../00893aaf.jsonl",
    "cwd": "/Users/...",
    "hook_event_name": "SubagentStart",
    "agent_id": "agent-abc123",
    "agent_type": "Explore",
}


def only(payload, source=AgentSource.CLAUDE_CODE):
    events = claude_code.translate(payload, source)
    assert len(events) >= 1, f"no event produced for {payload.get('hook_event_name')}"
    return events[0]


class TestTranslation:
    def test_pretooluse_becomes_tool_start_with_command(self):
        e = only(PRE_TOOL_USE)
        assert e.kind is EventKind.TOOL_START
        assert e.tool == "Bash"
        assert e.detail == "npm test"
        assert e.session_id == "abc123"
        assert e.project == "my-project"

    def test_command_is_flattened_and_truncated(self):
        payload = dict(PRE_TOOL_USE)
        payload["tool_input"] = {"command": "a" * 500}
        assert len(only(payload).detail) <= 70

    def test_post_tool_use_becomes_tool_end(self):
        payload = dict(PRE_TOOL_USE, hook_event_name="PostToolUse")
        assert only(payload).kind is EventKind.TOOL_END

    def test_tool_failure_becomes_tool_failure(self):
        e = only(POST_TOOL_USE_FAILURE)
        assert e.kind is EventKind.TOOL_FAILURE
        assert "Cannot find module" in e.detail

    def test_permission_request_becomes_approval(self):
        e = only(PERMISSION_REQUEST)
        assert e.kind is EventKind.APPROVAL_REQUEST
        assert e.approval_id                       # non-empty
        assert e.detail == "rm -rf node_modules"
        assert e.tool == "Bash"

    def test_approval_id_is_stable_for_same_request(self):
        a = only(PERMISSION_REQUEST).approval_id
        b = only(dict(PERMISSION_REQUEST)).approval_id
        assert a == b, "repeated identical requests must not spam the user"

    def test_different_commands_get_different_ids(self):
        a = only(PERMISSION_REQUEST).approval_id
        other = json.loads(json.dumps(PERMISSION_REQUEST))
        other["tool_input"]["command"] = "rm -rf /"
        assert only(other).approval_id != a

    def test_session_start_and_end(self):
        assert only({"session_id": "s", "hook_event_name": "SessionStart"}).kind is EventKind.SESSION_START
        assert only({"session_id": "s", "hook_event_name": "SessionEnd"}).kind is EventKind.SESSION_END

    def test_user_prompt_submit(self):
        e = only({"session_id": "s", "hook_event_name": "UserPromptSubmit", "prompt": "fix the bug"})
        assert e.kind is EventKind.PROMPT
        assert e.detail == "fix the bug"

    def test_stop_becomes_turn_end(self):
        e = only({"session_id": "s", "hook_event_name": "Stop", "last_assistant_message": "Done."})
        assert e.kind is EventKind.TURN_END

    def test_stop_failure_becomes_error(self):
        e = only({"session_id": "s", "hook_event_name": "StopFailure", "error": "overloaded"})
        assert e.kind is EventKind.ERROR
        assert "overloaded" in e.detail

    def test_subagent_start(self):
        e = only(SUBAGENT_START)
        assert e.kind is EventKind.PROMPT
        assert "Explore" in e.detail

    def test_subagent_stop_is_not_a_turn_end(self):
        # The parent turn is still running; a subagent finishing must not
        # make the pet celebrate completion.
        assert claude_code.translate(
            {"session_id": "s", "hook_event_name": "SubagentStop"}, AgentSource.CLAUDE_CODE
        ) == []

    def test_unknown_event_is_heartbeat(self):
        e = only({"session_id": "s", "hook_event_name": "SomeFutureEvent"})
        assert e.kind is EventKind.HEARTBEAT

    def test_empty_payload_is_safe(self):
        assert claude_code.translate({}, AgentSource.CLAUDE_CODE) == []

    def test_non_dict_payload_is_safe(self):
        assert claude_code.translate("nope", AgentSource.CLAUDE_CODE) == []  # type: ignore[arg-type]


class TestInteractiveTools:
    """AskUserQuestion / ExitPlanMode block on a human -> WAITING_FOR_INPUT."""

    def test_ask_user_question_becomes_approval(self):
        payload = {
            "session_id": "s",
            "hook_event_name": "PreToolUse",
            "tool_name": "AskUserQuestion",
            "tool_input": {"questions": [{"question": "Which DB?"}]},
            "tool_use_id": "toolu_x",
        }
        e = only(payload)
        assert e.kind is EventKind.APPROVAL_REQUEST
        assert e.approval_id.endswith("toolu_x") or "toolu_x" in e.approval_id

    def test_exit_plan_mode_becomes_approval(self):
        payload = {
            "session_id": "s",
            "hook_event_name": "PreToolUse",
            "tool_name": "ExitPlanMode",
            "tool_input": {},
            "tool_use_id": "toolu_y",
        }
        assert only(payload).kind is EventKind.APPROVAL_REQUEST

    def test_post_ask_user_question_resolves(self):
        payload = {
            "session_id": "s",
            "hook_event_name": "PostToolUse",
            "tool_name": "AskUserQuestion",
            "tool_input": {},
        }
        assert only(payload).kind is EventKind.APPROVAL_RESOLVED


class TestSources:
    @pytest.mark.parametrize(
        "source,expected",
        [
            (AgentSource.CLAUDE_CODE, "claude_code"),
            (AgentSource.AGY, "agy"),
            (AgentSource.CODEX, "codex"),
        ],
    )
    def test_same_payload_works_for_all_three(self, source, expected):
        e = only(PRE_TOOL_USE, source=source)
        assert e.source.value == expected


class TestRedaction:
    def test_file_contents_never_leave(self):
        payload = {
            "session_id": "s",
            "hook_event_name": "PreToolUse",
            "tool_name": "Write",
            "tool_input": {"file_path": "/secret/api_keys.env", "content": "KEY=supersecret"},
        }
        e = only(payload)
        assert e.detail == "api_keys.env"
        assert "supersecret" not in (e.detail or "")
        assert "supersecret" not in json.dumps(e.to_dict())

    def test_unknown_tool_exposes_keys_not_values(self):
        payload = {
            "session_id": "s",
            "hook_event_name": "PreToolUse",
            "tool_name": "MysteryTool",
            "tool_input": {"password": "hunter2", "token": "abc"},
        }
        e = only(payload)
        assert "hunter2" not in (e.detail or "")
        assert "abc" not in (e.detail or "")

    def test_prompt_is_truncated(self):
        payload = {"session_id": "s", "hook_event_name": "UserPromptSubmit", "prompt": "x" * 400}
        assert len(only(payload).detail) <= 60


class TestDecisionOutput:
    def test_allow_shape(self):
        out = claude_code.build_decision_output(True)
        assert out["hookSpecificOutput"]["hookEventName"] == "PermissionRequest"
        assert out["hookSpecificOutput"]["decision"]["behavior"] == "allow"
        assert "message" not in out["hookSpecificOutput"]["decision"]

    def test_deny_shape_includes_message(self):
        out = claude_code.build_decision_output(False, "not now")
        decision = out["hookSpecificOutput"]["decision"]
        assert decision["behavior"] == "deny"
        assert decision["message"] == "not now"

    def test_matches_documented_schema(self):
        # From the docs: {"hookSpecificOutput":{"hookEventName":...,"decision":{...}}}
        out = claude_code.build_decision_output(True)
        assert set(out) == {"hookSpecificOutput"}
        assert set(out["hookSpecificOutput"]) >= {"hookEventName", "decision"}
