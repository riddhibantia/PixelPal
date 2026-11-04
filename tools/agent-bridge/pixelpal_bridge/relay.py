"""
Relay transport — bridge <-> Firestore <-> phone, **stdlib only**.

## Why a relay at all

The direct HTTP bridge only works on the same network. A relay lets the phone on
mobile data (or a different office) still see the pet and answer approvals.

## Document layout

    relays/{lookupId}                    # lookupId = sha256(normalised code)[:32]
      verifier      HMAC(relay_key, "pixelpal-handshake")
      bridgeUid     anonymous Firebase uid of the bridge
      phoneUid      anonymous Firebase uid of the phone (set when the app pairs)
      createdAt / lastSeen                  (epoch seconds)
      state/                               # single subdoc: current AgentRegistry payload
        agents      JSON string of /agents payload
        updatedAt   epoch seconds

    relays/{lookupId}/outbox/{seq}       # bridge -> phone (master doc, phone reads)
    relays/{lookupId}/inbox/{seq}        # phone -> bridge (verdicts)

The subcollections always hold ONE document (id `0`) that both sides overwrite;
that is what keeps reads cheap — a poll is a single GET, not a query.

## Cost model (documented, not hidden)

A 1-doc GET costs 1 read. The bridge polls only when it has something new or the
phone is waiting:

  * an approval is pending  -> 2 s cadence  (~43k reads/day)
  * otherwise                -> 20 s cadence (~4.3k reads/day)

The free tier is 50k reads/day, so a normal user stays comfortably inside it.
`next_poll_delay()` is the single place that decides this, and it is unit-tested.

## What is NOT sent

Only the same redacted envelopes the LAN transport already sends: tool names and
short summaries. No file contents, no prompts beyond the truncated preview. The
relay never carries the pairing code or the bridge key.
"""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.parse
import urllib.request

from . import relay_crypto

# Poll cadences (seconds).
FAST_POLL_SECONDS = 2.0
IDLE_POLL_SECONDS = 20.0

# A relay is considered dead if the bridge hasn't touched it in this long.
STALE_RELAY_SECONDS = 60 * 60 * 24 * 7

_REQUEST_TIMEOUT = 10.0


class RelayError(Exception):
    """Raised for non-recoverable relay problems (bad auth, malformed docs)."""


class RelayNotConfigured(RelayError):
    pass


# ---------------------------------------------------------------------------
# Firebase config
# ---------------------------------------------------------------------------


class FirebaseConfig:
    """The three values needed to talk to Firestore over REST."""

    def __init__(self, project_id: str, api_key: str):
        self.project_id = project_id
        self.api_key = api_key

    @property
    def documents_url(self) -> str:
        return (
            f"https://firestore.googleapis.com/v1/projects/{self.project_id}"
            "/databases/(default)/documents"
        )

    @classmethod
    def from_google_services(cls, path) -> "FirebaseConfig":
        """
        Read an Android `google-services.json`.

        The bridge lives next to the app repo, so the project's own config file
        is the single source of truth — no duplicated ids, no extra setup step.
        """
        with open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
        project_id = data["project_info"]["project_id"]
        api_key = data["client"][0]["api_key"][0]["current_key"]
        return cls(project_id, api_key)


# ---------------------------------------------------------------------------
# low-level REST helpers
# ---------------------------------------------------------------------------


def _http_json(url: str, *, method: str = "GET", body=None, token: str | None = None):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=_REQUEST_TIMEOUT) as response:
        raw = response.read()
    return json.loads(raw) if raw else {}


def _fields(doc: dict) -> dict:
    """Flatten Firestore's {type: value} encoding into plain Python."""
    out = {}
    for key, value in (doc.get("fields") or {}).items():
        if not isinstance(value, dict):
            continue
        if "stringValue" in value:
            out[key] = value["stringValue"]
        elif "integerValue" in value:
            out[key] = int(value["integerValue"])
        elif "doubleValue" in value:
            out[key] = float(value["doubleValue"])
        elif "booleanValue" in value:
            out[key] = bool(value["booleanValue"])
        elif "nullValue" in value:
            out[key] = None
        else:
            out[key] = value
    return out


def _encode(value):
    if isinstance(value, bool):
        return {"booleanValue": value}
    if isinstance(value, int):
        return {"integerValue": str(value)}
    if isinstance(value, float):
        return {"doubleValue": value}
    if value is None:
        return {"nullValue": None}
    return {"stringValue": str(value)}


def _to_doc(fields: dict) -> dict:
    return {"fields": {k: _encode(v) for k, v in fields.items() if v is not None}}


# ---------------------------------------------------------------------------
# relay client
# ---------------------------------------------------------------------------


class RelayClient:
    """
    One side of the relay (the bridge).

    The phone uses the same document contract from Kotlin; if you change the
    encoding here, change `BridgeRelayClient.kt` too.
    """

    def __init__(self, config: FirebaseConfig, normalized_code: str):
        self.config = config
        self.code = relay_crypto.normalize_code(normalized_code)
        if self.code is None:
            raise ValueError("invalid pairing code")
        self.key = relay_crypto.derive_relay_key(self.code)
        self.verifier = relay_crypto.verifier(self.key)
        self.lookup = relay_crypto.lookup_id(self.code)
        self._token: str | None = None
        self._uid: str | None = None

    # -- auth ----------------------------------------------------------

    def _auth_token(self, refresh: bool = False) -> str:
        if self._token and not refresh:
            return self._token
        url = (
            "https://identitytoolkit.googleapis.com/v1/accounts:signUp"
            f"?key={self.config.api_key}"
        )
        data = _http_json(url, method="POST", body={"returnSecureToken": True})
        self._token = data["idToken"]
        self._uid = data.get("localId")
        return self._token

    def _call(self, url: str, *, method: str = "GET", body=None):
        try:
            return _http_json(url, method=method, body=body, token=self._auth_token())
        except urllib.error.HTTPError as error:
            if error.code == 401:
                # Token expired: re-auth once, then propagate.
                return _http_json(
                    url, method=method, body=body, token=self._auth_token(refresh=True)
                )
            raise

    def _doc_url(self, *path: str) -> str:
        joined = "/".join(urllib.parse.quote(p, safe="") for p in path)
        return f"{self.config.documents_url}/{joined}?key={self.config.api_key}"

    # -- registration --------------------------------------------------

    def register(self) -> None:
        """Publish (or refresh) this relay's handshake document."""
        self._auth_token()  # ensures _uid is populated
        now = int(time.time())
        doc = _to_doc(
            {
                "verifier": self.verifier,
                "bridgeUid": self._uid,
                "lastSeen": now,
                "createdAt": now,
                "version": 1,
            }
        )
        self._call(self._doc_url("relays", self.lookup), method="PATCH", body=doc)

    def is_registered(self) -> bool:
        try:
            doc = self._call(self._doc_url("relays", self.lookup))
        except urllib.error.HTTPError:
            return False
        fields = _fields(doc)
        return relay_crypto.verify(self.key, fields.get("verifier", ""))

    def unpublish(self) -> bool:
        try:
            self._call(self._doc_url("relays", self.lookup), method="DELETE")
            return True
        except urllib.error.HTTPError:
            return False

    # -- state publish -------------------------------------------------

    def publish_agents(self, payload: dict) -> bool:
        """
        Push the current `/agents` payload.

        Stored as a JSON *string* rather than nested Firestore maps: the app
        already has a tested parser for that shape, and it keeps read cost at
        exactly one document.
        """
        blob = json.dumps(payload, separators=(",", ":"))
        if len(blob) > 200_000:
            raise RelayError("agent payload too large for the relay")
        doc = _to_doc({"agents": blob, "updatedAt": int(time.time())})
        self._call(self._doc_url("relays", self.lookup, "state", "current"),
                   method="PATCH", body=doc)
        return True

    def fetch_verdict(self) -> dict | None:
        """Read one pending approval verdict, if the phone left one."""
        try:
            doc = self._call(self._doc_url("relays", self.lookup, "inbox", "current"))
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return None
            raise
        fields = _fields(doc)
        if not fields.get("approvalId"):
            return None
        return {
            "approvalId": fields.get("approvalId"),
            "decision": fields.get("decision", "deny"),
        }

    def clear_verdict(self) -> None:
        try:
            self._call(self._doc_url("relays", self.lookup, "inbox", "current"),
                       method="DELETE")
        except urllib.error.HTTPError:
            pass


# ---------------------------------------------------------------------------
# cadence
# ---------------------------------------------------------------------------


def next_poll_delay(has_pending_approval: bool, errored: bool = False) -> float:
    """
    How long to wait before the next relay poll.

    Fast only when someone is actually blocked waiting for the answer; otherwise
    stay cheap so the free tier is never at risk. Errors back off politely.
    """
    if has_pending_approval:
        return FAST_POLL_SECONDS
    if errored:
        return IDLE_POLL_SECONDS * 3
    return IDLE_POLL_SECONDS
