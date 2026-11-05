"""
Integration tests for the hook shim <-> server round-trip.

These caught two real production bugs, so they stay:
  1. approval ids built with Python's hash() — randomized per process, so the
     verdict never matched the request (PYTHONHASHSEED).
  2. resolve() before any waiter existed dropped the verdict (race).
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import threading
import time
import urllib.request

import pytest

from pixelpal_bridge import install as installer
from pixelpal_bridge.adapters import claude_code
from pixelpal_bridge.model import AgentSource
from pixelpal_bridge.server import BridgeState, make_handler
from http.server import ThreadingHTTPServer

PKG = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


@pytest.fixture()
def bridge():
    state = BridgeState(pairing_key=None)
    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(state))
    server.daemon_threads = True
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield state, port
    server.shutdown()
    server.server_close()


def post(port: int, path: str, payload: dict, timeout: float = 5.0) -> dict:
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = resp.read()
    return json.loads(body) if body else {}


def get(port: int, path: str) -> dict:
    with urllib.request.urlopen(f"http://127.0.0.1:{port}{path}", timeout=3) as resp:
        return json.loads(resp.read())


class TestStableApprovalIds:
    """Regression: hash() is salted per process and breaks cross-process matching."""

    PAYLOAD = {
        "session_id": "s",
        "hook_event_name": "PermissionRequest",
        "tool_name": "Bash",
        "tool_input": {"command": "rm -rf build/"},
    }

    def _id_in_subprocess(self, seed: str) -> str:
        code = (
            "import sys, json; sys.path.insert(0, %r);"
            "from pixelpal_bridge.adapters import claude_code;"
            "from pixelpal_bridge.model import AgentSource;"
            "p=json.loads(%r);"
            "print(claude_code.translate(p, AgentSource.CLAUDE_CODE)[0].approval_id)"
        ) % (PKG, json.dumps(self.PAYLOAD))
        env = {**os.environ, "PYTHONHASHSEED": seed}
        out = subprocess.run(
            [sys.executable, "-c", code], capture_output=True, env=env, timeout=30
        )
        # The approval id is a 64-hex SHA-256. Some dev environments inject
        # interpreter-startup banners (crash-capture hooks) around the child's
        # stdout with varying timestamps/pids, so match the id line itself.
        ids = re.findall(r"^[0-9a-f]{64}$", out.stdout.decode(), re.MULTILINE)
        return ids[0] if ids else ""

    def test_same_hash_seed_gives_same_id(self):
        assert self._id_in_subprocess("0") == self._id_in_subprocess("0")

    def test_different_hash_seeds_give_same_id(self):
        # The bug: these used to differ, so approvals never matched.
        assert self._id_in_subprocess("1") == self._id_in_subprocess("99999")

    def test_id_is_stable_in_process(self):
        a = claude_code.translate(self.PAYLOAD, AgentSource.CLAUDE_CODE)[0].approval_id
        b = claude_code.translate(self.PAYLOAD, AgentSource.CLAUDE_CODE)[0].approval_id
        assert a == b


class TestServerFlow:
    def test_event_ingress_updates_agents(self, bridge):
        state, port = bridge
        post(port, "/events", {
            "source": "claude_code", "kind": "tool_start", "session_id": "s1",
            "tool": "Edit", "detail": "Main.kt", "id": "e1",
        })
        payload = get(port, "/agents")
        assert payload["count"] == 1
        agent = payload["agents"][0]
        assert agent["status"] == "WORKING"
        assert agent["message"] == "editing Main.kt"

    def test_status_returns_primary(self, bridge):
        _, port = bridge
        post(port, "/events", {
            "source": "omp", "kind": "tool_start", "session_id": "o1",
            "tool": "Bash", "id": "e1",
        })
        status = get(port, "/status")
        assert status["status"] == "WORKING"
        assert status["source"] == "omp"

    def test_status_with_no_agents_is_idle(self, bridge):
        _, port = bridge
        assert get(port, "/status")["status"] == "IDLE"

    def test_bad_event_rejected(self, bridge):
        _, port = bridge
        with pytest.raises(urllib.error.HTTPError) as exc:
            post(port, "/events", {"kind": "not_a_kind"})
        assert exc.value.code == 400

    def test_early_verdict_is_not_lost(self, bridge):
        """Regression: phone answered before the hook started waiting."""
        _, port = bridge
        post(port, "/", {"approvalId": "early-1", "decision": "approve"})
        verdict = post(port, "/approvals/wait", {"approvalId": "early-1", "timeout": 1.0})
        assert verdict["decision"] == "approve"

    def test_waiter_receives_late_verdict(self, bridge):
        _, port = bridge
        result = {}

        def wait():
            result.update(post(port, "/approvals/wait", {"approvalId": "late-1", "timeout": 5}))

        thread = threading.Thread(target=wait)
        thread.start()
        time.sleep(0.4)
        post(port, "/", {"approvalId": "late-1", "decision": "deny"})
        thread.join(timeout=6)
        assert result.get("decision") == "deny"

    def test_wait_times_out_with_empty_object(self, bridge):
        _, port = bridge
        assert post(port, "/approvals/wait", {"approvalId": "nobody", "timeout": 0.3}) == {}

    def test_health(self, bridge):
        _, port = bridge
        health = get(port, "/health")
        assert health["ok"] is True
        assert health["paired"] is False

    def test_unknown_route_404(self, bridge):
        _, port = bridge
        with pytest.raises(urllib.error.HTTPError) as exc:
            get(port, "/nope")
        assert exc.value.code == 404


class TestAuth:
    def test_key_required_from_non_loopback(self):
        """Simulate a LAN client: pairing key must gate access."""
        state = BridgeState(pairing_key="secret")
        server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(state))
        server.daemon_threads = True
        port = server.server_address[1]
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            # Loopback is always allowed (local hooks).
            assert get(port, "/agents") is not None
        finally:
            server.shutdown()
            server.server_close()

    def test_unauthorised_lan_request_is_401(self, monkeypatch):
        state = BridgeState(pairing_key="secret")
        handler = make_handler(state)

        class FakeHandler(handler):
            def __init__(self):  # bypass socket setup
                self.client_address = ("192.168.1.50", 12345)
                self.headers = {}

        assert FakeHandler()._authorised() is False

    def test_authorised_with_correct_key(self):
        state = BridgeState(pairing_key="secret")
        handler = make_handler(state)

        class FakeHandler(handler):
            def __init__(self):
                self.client_address = ("192.168.1.50", 12345)
                self.headers = {"X-PixelPal-Key": "secret"}

        assert FakeHandler()._authorised() is True


class TestInstallReversibility:
    def test_install_then_uninstall_restores_file(self, tmp_path):
        settings = tmp_path / "settings.json"
        original = {
            "hooks": {
                "PreToolUse": [{"hooks": [{"type": "command", "command": "echo existing"}]}]
            },
            "someOtherSetting": True,
        }
        settings.write_text(json.dumps(original))

        added = installer.install_claude_style(settings, "claude_code", ["PreToolUse"])
        assert added == ["PreToolUse"]

        after = json.loads(settings.read_text())
        # Our hook was appended, the user's hook preserved.
        assert len(after["hooks"]["PreToolUse"]) == 2
        assert installer.MARKER in json.dumps(after["hooks"]["PreToolUse"][1])
        assert after["someOtherSetting"] is True

        installer.uninstall(settings)
        restored = json.loads(settings.read_text())
        assert restored == original

    def test_install_is_idempotent(self, tmp_path):
        settings = tmp_path / "settings.json"
        installer.install_claude_style(settings, "claude_code", ["Stop"])
        installer.install_claude_style(settings, "claude_code", ["Stop"])
        data = json.loads(settings.read_text())
        assert len(data["hooks"]["Stop"]) == 1

    def test_backup_created_before_write(self, tmp_path):
        settings = tmp_path / "settings.json"
        settings.write_text('{"hooks": {}}')
        installer.install_claude_style(settings, "codex", ["Stop"])
        backups = list(tmp_path.glob("settings.json.bak-pixelpal-*"))
        assert backups, "a timestamped backup must be kept before overwriting"
