"""opencode adapter: only provably-live sessions may be announced.

The bug these pin: envelopes without `lastEventAgeSeconds` made the reducer
re-stamp every poll as "now", so a 26-session graveyard of old terminals
showed up as "26 idle · just now" on the phone — forever.
"""

from __future__ import annotations

import time

from pixelpal_bridge.reducer import STALE_DROP_SECONDS


def _session(sid: str, *, updated=None, status=None, title="t", directory=None, parent_id=None) -> dict:
    s = {"id": sid, "directory": directory or f"/p/{sid}", "title": title}
    if updated is not None:
        s["time"] = {"created": updated, "updated": updated}
    if status is not None:
        s["status"] = status
    if parent_id is not None:
        s["parentID"] = parent_id
    return s


class TestOpenCodeAge:
    def test_recent_session_carries_small_age(self, monkeypatch):
        from pixelpal_bridge.adapters import opencode

        now = 1_700_000_000.0
        monkeypatch.setattr(opencode.time, "time", lambda: now)
        monkeypatch.setattr(
            opencode, "fetch_sessions",
            lambda port=None: [_session("s1", updated=int((now - 30) * 1000))],
        )
        envelopes = opencode.poll_envelopes()
        assert len(envelopes) == 1
        assert envelopes[0]["lastEventAgeSeconds"] == 30
        assert envelopes[0]["sessionId"] == "s1"

    def test_old_session_is_not_announced(self, monkeypatch):
        from pixelpal_bridge.adapters import opencode

        now = 1_700_000_000.0
        monkeypatch.setattr(opencode.time, "time", lambda: now)
        monkeypatch.setattr(
            opencode, "fetch_sessions",
            lambda port=None: [
                _session("old", updated=int((now - STALE_DROP_SECONDS - 60) * 1000))
            ],
        )
        assert opencode.poll_envelopes() == []

    def test_timestampless_idle_session_is_not_announced(self, monkeypatch):
        from pixelpal_bridge.adapters import opencode

        monkeypatch.setattr(opencode, "fetch_sessions", lambda port=None: [_session("x")])
        assert opencode.poll_envelopes() == []

    def test_timestampless_busy_session_is_live(self, monkeypatch):
        from pixelpal_bridge.adapters import opencode

        monkeypatch.setattr(
            opencode, "fetch_sessions",
            lambda port=None: [_session("x", status="busy")],
        )
        envelopes = opencode.poll_envelopes()
        assert len(envelopes) == 1
        assert envelopes[0]["status"] == "WORKING"
        assert envelopes[0]["lastEventAgeSeconds"] == 0

    def test_seconds_and_millis_both_parse(self, monkeypatch):
        from pixelpal_bridge.adapters import opencode

        now = 1_700_000_000.0
        monkeypatch.setattr(opencode.time, "time", lambda: now)
        monkeypatch.setattr(
            opencode, "fetch_sessions",
            lambda port=None: [
                _session("ms", updated=int((now - 10) * 1000)),
                _session("s", updated=int(now - 20)),
            ],
        )
        ages = {e["sessionId"]: e["lastEventAgeSeconds"] for e in opencode.poll_envelopes()}
        assert ages["ms"] == 10
        assert ages["s"] == 20

    def test_list_is_capped_to_newest(self, monkeypatch):
        from pixelpal_bridge.adapters import opencode

        now = 1_700_000_000.0
        monkeypatch.setattr(opencode.time, "time", lambda: now)
        sessions = [
            _session(f"s{i}", updated=int((now - i * 60) * 1000)) for i in range(12)
        ]
        monkeypatch.setattr(opencode, "fetch_sessions", lambda port=None: sessions)
        envelopes = opencode.poll_envelopes()
        assert len(envelopes) == opencode.MAX_SESSIONS
        assert envelopes[0]["sessionId"] == "s0"

    def test_running_client_keeps_idle_session_alive(self, monkeypatch):
        from pixelpal_bridge.adapters import opencode

        now = 1_700_000_000.0
        monkeypatch.setattr(opencode.time, "time", lambda: now)
        monkeypatch.setattr(opencode, "_running_client_count", lambda: 1)
        monkeypatch.setattr(
            opencode, "fetch_sessions",
            lambda port=None: [
                _session("live_terminal", updated=int((now - STALE_DROP_SECONDS - 60) * 1000))
            ],
        )
        envelopes = opencode.poll_envelopes()
        assert len(envelopes) == 1
        assert envelopes[0]["sessionId"] == "live_terminal"
        assert envelopes[0]["lastEventAgeSeconds"] == 60

    def test_subagent_sessions_are_filtered_out(self, monkeypatch):
        from pixelpal_bridge.adapters import opencode

        now = 1_700_000_000.0
        monkeypatch.setattr(opencode.time, "time", lambda: now)
        monkeypatch.setattr(
            opencode, "fetch_sessions",
            lambda port=None: [
                _session("main", updated=int((now - 10) * 1000), directory="/p/repo"),
                _session("sub1", updated=int((now - 5) * 1000), directory="/p/repo", parent_id="main"),
                _session("sub2", updated=int((now - 2) * 1000), directory="/p/repo", parent_id="main"),
            ],
        )
        envelopes = opencode.poll_envelopes()
        assert len(envelopes) == 1
        assert envelopes[0]["sessionId"] == "main"

    def test_duplicate_repo_sessions_keep_only_newest_or_working(self, monkeypatch):
        from pixelpal_bridge.adapters import opencode

        now = 1_700_000_000.0
        monkeypatch.setattr(opencode.time, "time", lambda: now)
        monkeypatch.setattr(
            opencode, "fetch_sessions",
            lambda port=None: [
                _session("old_idle", updated=int((now - 100) * 1000), directory="/p/myrepo"),
                _session("new_working", updated=int((now - 50) * 1000), status="busy", directory="/p/myrepo"),
            ],
        )
        envelopes = opencode.poll_envelopes()
        assert len(envelopes) == 1
        assert envelopes[0]["sessionId"] == "new_working"
        assert envelopes[0]["status"] == "WORKING"
