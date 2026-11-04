"""
Tests for the relay transport.

Firestore is mocked at the `_http_json` seam so these run offline, instantly,
and without touching the real project. The wire encoding is asserted explicitly
because the Kotlin client must decode exactly this shape.
"""

from __future__ import annotations

import json

import pytest
import urllib.error

from pixelpal_bridge import relay, relay_crypto


class FakeFirestore:
    """A tiny in-memory stand-in for the Firestore REST API."""

    def __init__(self):
        self.docs: dict[str, dict] = {}
        self.calls: list[tuple[str, str]] = []

    def url_to_path(self, url: str) -> str:
        # .../documents/relays/abc/state/current?key=...
        marker = "/documents/"
        path = url.split(marker, 1)[1].split("?", 1)[0]
        return path

    def __call__(self, url, *, method="GET", body=None, token=None):
        path = self.url_to_path(url)
        self.calls.append((method, path))
        if method == "PATCH":
            self.docs[path] = body
            return {"name": path, **body}
        if method == "DELETE":
            if path not in self.docs:
                raise urllib.error.HTTPError(url, 404, "missing", {}, None)
            del self.docs[path]
            return {}
        if method == "GET":
            if path not in self.docs:
                raise urllib.error.HTTPError(url, 404, "missing", {}, None)
            return self.docs[path]
        raise AssertionError(f"unexpected method {method}")


@pytest.fixture()
def fake(monkeypatch):
    store = FakeFirestore()
    monkeypatch.setattr(relay, "_http_json", store)
    # Auth is stubbed: the real one is exercised separately by `test_live_auth`.
    monkeypatch.setattr(
        relay.RelayClient, "_auth_token", lambda self, refresh=False: "fake-token"
    )
    monkeypatch.setattr(
        relay.RelayClient, "_call",
        lambda self, url, method="GET", body=None: store(url, method=method, body=body),
    )
    return store


def make_client(code: str | None = None) -> relay.RelayClient:
    client = relay.RelayClient.__new__(relay.RelayClient)
    client.config = relay.FirebaseConfig("test-project", "test-key")
    client.code = relay_crypto.normalize_code(code or relay_crypto.generate_code())
    client.key = relay_crypto.derive_relay_key(client.code)
    client.verifier = relay_crypto.verifier(client.key)
    client.lookup = relay_crypto.lookup_id(client.code)
    client._token = "fake-token"
    client._uid = "bridge-uid"
    return client


def patch_methods(client, store):
    client._call = lambda url, method="GET", body=None: store(url, method=method, body=body)
    client._auth_token = lambda refresh=False: "fake-token"
    return client


class TestRegistration:
    def test_register_writes_handshake(self, fake):
        client = patch_methods(make_client(), fake)
        client.register()
        doc = fake.docs[f"relays/{client.lookup}"]
        assert doc["fields"]["verifier"]["stringValue"] == client.verifier
        assert doc["fields"]["bridgeUid"]["stringValue"] == "bridge-uid"

    def test_is_registered_true_after_register(self, fake):
        client = patch_methods(make_client(), fake)
        client.register()
        assert client.is_registered()

    def test_is_registered_false_when_absent(self, fake):
        client = patch_methods(make_client(), fake)
        assert not client.is_registered()

    def test_is_registered_false_for_wrong_verifier(self, fake):
        """A stale doc from another code must not be accepted."""
        client = patch_methods(make_client(), fake)
        other = patch_methods(make_client(), fake)
        other.register()
        fake.docs[f"relays/{client.lookup}"] = fake.docs[f"relays/{other.lookup}"]
        assert not client.is_registered()

    def test_unpublish_removes_doc(self, fake):
        client = patch_methods(make_client(), fake)
        client.register()
        assert client.unpublish()
        assert f"relays/{client.lookup}" not in fake.docs

    def test_lookup_hides_the_code(self):
        code = relay_crypto.generate_code()
        client = make_client(code)
        assert client.code not in client.lookup


class TestStatePublish:
    def test_publish_writes_agents_json_string(self, fake):
        client = patch_methods(make_client(), fake)
        payload = {"agents": [{"status": "WORKING", "message": "editing"}], "count": 1}
        client.publish_agents(payload)
        doc = fake.docs[f"relays/{client.lookup}/state/current"]
        stored = json.loads(doc["fields"]["agents"]["stringValue"])
        assert stored == payload

    def test_publish_stores_as_string_not_nested_map(self, fake):
        """
        The Kotlin client decodes a JSON string field. Nested maps would need a
        different parser, so pin the encoding here.
        """
        client = patch_methods(make_client(), fake)
        client.publish_agents({"agents": []})
        doc = fake.docs[f"relays/{client.lookup}/state/current"]
        assert "stringValue" in doc["fields"]["agents"]
        assert "mapValue" not in doc["fields"]["agents"]

    def test_publish_rejects_oversized_payload(self, fake):
        client = patch_methods(make_client(), fake)
        huge = {"agents": [{"message": "x" * 250_000}]}
        with pytest.raises(relay.RelayError):
            client.publish_agents(huge)

    def test_publish_overwrites_in_place(self, fake):
        client = patch_methods(make_client(), fake)
        client.publish_agents({"agents": [], "count": 0})
        client.publish_agents({"agents": [{"a": 1}], "count": 1})
        doc = fake.docs[f"relays/{client.lookup}/state/current"]
        assert json.loads(doc["fields"]["agents"]["stringValue"])["count"] == 1


class TestVerdicts:
    def test_no_verdict_returns_none(self, fake):
        client = patch_methods(make_client(), fake)
        assert client.fetch_verdict() is None

    def test_reads_phone_verdict(self, fake):
        client = patch_methods(make_client(), fake)
        fake.docs[f"relays/{client.lookup}/inbox/current"] = relay._to_doc(
            {"approvalId": "a1", "decision": "approve"}
        )
        assert client.fetch_verdict() == {"approvalId": "a1", "decision": "approve"}

    def test_blank_doc_is_not_a_verdict(self, fake):
        client = patch_methods(make_client(), fake)
        fake.docs[f"relays/{client.lookup}/inbox/current"] = relay._to_doc({"decision": "approve"})
        assert client.fetch_verdict() is None

    def test_clear_verdict_removes_it(self, fake):
        client = patch_methods(make_client(), fake)
        fake.docs[f"relays/{client.lookup}/inbox/current"] = relay._to_doc(
            {"approvalId": "a1", "decision": "deny"}
        )
        client.clear_verdict()
        assert client.fetch_verdict() is None

    def test_clear_missing_verdict_is_silent(self, fake):
        client = patch_methods(make_client(), fake)
        client.clear_verdict()  # must not raise


class TestPollCadence:
    def test_fast_while_approval_pending(self):
        assert relay.next_poll_delay(True) == relay.FAST_POLL_SECONDS

    def test_idle_when_nothing_happening(self):
        assert relay.next_poll_delay(False) == relay.IDLE_POLL_SECONDS

    def test_errors_back_off_further(self):
        assert relay.next_poll_delay(False, errored=True) > relay.IDLE_POLL_SECONDS

    def test_pending_beats_error_backoff(self):
        """A waiting user must still get the fast path."""
        assert relay.next_poll_delay(True, errored=True) == relay.FAST_POLL_SECONDS

    def test_daily_read_budget_within_free_tier(self):
        """
        Guards the cost promise made in the README: idle polling must stay
        inside Firestore's 50k free reads/day for a 24h session.
        """
        idle_reads = 86_400 / relay.IDLE_POLL_SECONDS
        assert idle_reads < 50_000, f"idle polling would use {idle_reads:.0f} reads/day"


class TestWireEncoding:
    def test_integer_encoded_as_string(self):
        assert relay._to_doc({"n": 5})["fields"]["n"] == {"integerValue": "5"}

    def test_boolean_encoded(self):
        assert relay._to_doc({"b": True})["fields"]["b"] == {"booleanValue": True}

    def test_none_omitted(self):
        assert "x" not in relay._to_doc({"x": None})["fields"]

    def test_fields_decode_back(self):
        original = {"verifier": "abc", "lastSeen": 1700000000, "v": 1}
        assert relay._fields(relay._to_doc(original)) == original


class TestConfig:
    def test_reads_real_project_config(self):
        path = "/home/akram/projects/PixelPal/app/google-services.json"
        import os

        if not os.path.exists(path):
            pytest.skip("google-services.json not present")
        config = relay.FirebaseConfig.from_google_services(path)
        assert config.project_id
        assert config.api_key.startswith("AIza")
        assert "firestore.googleapis.com" in config.documents_url
