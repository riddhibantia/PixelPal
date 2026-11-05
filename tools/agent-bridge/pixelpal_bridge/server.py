"""
Bridge HTTP server.

Endpoints
    GET  /status            primary agent envelope (PixelPal's original contract)
    GET  /agents            ALL agents, ranked by attention  <- multi-agent
    GET  /health            liveness + agent count
    POST /events            hook shim ingress (one AgentEvent)
    POST /approvals/wait    long-poll for a user's approval verdict
    POST /                   app commands / approval responses (back-compat)

Security
    Pairing key required when configured: every request must carry
    `X-PixelPal-Key`. Loopback is always allowed so the hook shim never needs
    the key (it runs as the same user on the same machine).
"""

from __future__ import annotations

import hmac
import json
import os
import queue
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from .model import AgentEvent
from .reducer import AgentRegistry

CONFIG_PATH = Path(
    os.environ.get("PIXELPAL_CONFIG", Path.home() / ".config" / "pixelpal" / "bridge.json")
)


def load_config() -> dict:
    try:
        return json.loads(CONFIG_PATH.read_text())
    except (OSError, json.JSONDecodeError):
        return {}


class BridgeState:
    """Shared server state, guarded by a lock."""

    def __init__(self, pairing_key: str | None = None):
        self.registry = AgentRegistry()
        self.lock = threading.Lock()
        self.pairing_key = pairing_key
        # approvalId -> queue of verdicts {decision, message}
        self.approvals: dict[str, "queue.Queue[dict]"] = {}
        # Verdicts that arrived before anyone waited (phone answered fast).
        self.early_verdicts: dict[str, dict] = {}
        self.commands: list[dict] = []
        self.started_at = time.time()

    def submit(self, event: AgentEvent) -> None:
        with self.lock:
            self.registry.apply(event)
            if event.approval_id:
                self.approvals.setdefault(event.approval_id, queue.Queue())

    def resolve(self, approval_id: str, decision: str, message: str = "") -> bool:
        """Store a verdict. Must survive being called before any waiter exists."""
        verdict = {"decision": decision, "message": message}
        with self.lock:
            if approval_id in self.approvals:
                self.approvals[approval_id].put(verdict)
            else:
                # Nobody waiting yet: remember it for the next waiter.
                self.early_verdicts[approval_id] = verdict
        return True

    def wait_for(self, approval_id: str, timeout: float) -> dict | None:
        with self.lock:
            early = self.early_verdicts.pop(approval_id, None)
            if early is not None:
                return early
            q = self.approvals.setdefault(approval_id, queue.Queue())
        try:
            return q.get(timeout=timeout)
        except queue.Empty:
            return None

    def discard(self, approval_id: str) -> None:
        with self.lock:
            self.approvals.pop(approval_id, None)
            self.early_verdicts.pop(approval_id, None)

    def tick(self) -> None:
        with self.lock:
            self.registry.tick()


def make_handler(state: BridgeState):
    class Handler(BaseHTTPRequestHandler):
        server_version = "PixelPalBridge/2.0"
        protocol_version = "HTTP/1.1"

        # -- helpers ---------------------------------------------------
        def _json(self, obj, code: int = 200) -> None:
            body = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def _is_loopback(self) -> bool:
            host = self.client_address[0] if self.client_address else ""
            return host in ("127.0.0.1", "::1", "localhost")

        def _authorised(self) -> bool:
            if not state.pairing_key:
                return True
            if self._is_loopback():
                return True  # local hooks never need the key
            presented = self.headers.get("X-PixelPal-Key", "") or ""
            # Constant-time: a plain `==` leaks the key's prefix through timing.
            return hmac.compare_digest(
                presented.encode("utf-8"),
                state.pairing_key.encode("utf-8"),
            )

        def _body(self) -> dict:
            try:
                length = int(self.headers.get("Content-Length") or 0)
            except ValueError:
                return {}
            raw = self.rfile.read(length) if length else b""
            try:
                parsed = json.loads(raw or b"{}")
                return parsed if isinstance(parsed, dict) else {}
            except json.JSONDecodeError:
                return {}

        # -- routes ----------------------------------------------------
        def do_GET(self) -> None:  # noqa: N802
            if not self._authorised():
                self._json({"error": "unauthorized"}, 401)
                return

            path = self.path.split("?")[0].rstrip("/") or "/"

            if path in ("/", "/status"):
                with state.lock:
                    primary = state.registry.primary()
                if primary is None:
                    self._json(
                        {"status": "IDLE", "message": "no agent connected",
                         "pendingApproval": None}
                    )
                else:
                    self._json(primary.to_envelope())
                return

            if path == "/agents":
                with state.lock:
                    payload = state.registry.to_list_payload()
                self._json(payload)
                return

            if path == "/health":
                with state.lock:
                    count = len(state.registry.sessions)
                self._json(
                    {
                        "ok": True,
                        "agents": count,
                        "uptimeSeconds": int(time.time() - state.started_at),
                        "paired": bool(state.pairing_key),
                    }
                )
                return

            if path == "/commands":
                with state.lock:
                    cmds = list(state.commands)
                self._json({"commands": cmds})
                return

            self._json({"error": "not found"}, 404)

        def do_POST(self) -> None:  # noqa: N802
            if not self._authorised():
                self._json({"error": "unauthorized"}, 401)
                return

            path = self.path.split("?")[0].rstrip("/") or "/"
            payload = self._body()

            if path == "/events":
                try:
                    event = AgentEvent.from_dict(payload)
                except (ValueError, KeyError) as exc:
                    self._json({"error": f"bad event: {exc}"}, 400)
                    return
                state.submit(event)
                self._json({"ok": True}, 202)
                return

            if path == "/approvals/wait":
                approval_id = str(payload.get("approvalId") or "")
                if not approval_id:
                    self._json({"error": "approvalId required"}, 400)
                    return
                timeout = float(payload.get("timeout") or 1.5)
                verdict = state.wait_for(approval_id, min(timeout, 20.0))
                # Empty 204 breaks HTTP/1.1 keep-alive clients (BrokenPipe);
                # an explicit empty JSON object is unambiguous.
                self._json(verdict or {})
                return

            if path == "/approvals/resolve":
                approval_id = str(payload.get("approvalId") or "")
                decision = str(payload.get("decision") or "").lower()
                if not approval_id or decision not in ("approve", "deny", "allow", "denied"):
                    self._json({"error": "approvalId and decision required"}, 400)
                    return
                state.resolve(approval_id, decision, str(payload.get("message") or ""))
                self._json({"ok": True})
                return

            if path == "/":
                # Back-compat with the original PixelPal approval contract.
                approval_id = payload.get("approvalId")
                if approval_id:
                    state.resolve(str(approval_id), str(payload.get("decision") or "deny"))
                    self._json({"ok": True, "action": "approval-recorded"})
                    return
                command = str(payload.get("command") or "").strip()
                with state.lock:
                    state.commands.insert(0, {"at": time.time(), "command": command})
                    del state.commands[50:]
                self._json({"ok": True, "action": "queued"})
                return

            self._json({"error": "not found"}, 404)

        def handle_one_request(self) -> None:
            # Long-polling clients disconnect routinely; that is not an error.
            try:
                super().handle_one_request()
            except (BrokenPipeError, ConnectionResetError):
                self.close_connection = True

        def log_message(self, fmt: str, *args) -> None:
            # Quiet by default: hooks are high frequency.
            if os.environ.get("PIXELPAL_VERBOSE") == "1":
                print(f"{self.address_string()} - {fmt % args}", flush=True)

    return Handler


# Agents that expose state over an API or a database instead of firing hooks.
# They are polled; nothing else would ever see them (`doctor` only probes them).
POLL_INTERVAL_SECONDS = 5.0


def poll_loop(state: BridgeState) -> None:
    from .adapters import hermes as hermes_adapter
    from .adapters import opencode as oc_adapter

    while True:
        for poller in (oc_adapter.poll_envelopes, hermes_adapter.poll_envelopes):
            try:
                envelopes = poller()
            except Exception:  # noqa: BLE001 - a broken adapter must not stop the others
                continue
            with state.lock:
                for envelope in envelopes:
                    state.registry.upsert_envelope(envelope)
        time.sleep(POLL_INTERVAL_SECONDS)


def serve(host: str, port: int, state: BridgeState) -> None:
    server = ThreadingHTTPServer((host, port), make_handler(state))
    server.daemon_threads = True

    def decay_loop() -> None:
        while True:
            time.sleep(15)
            try:
                state.tick()
            except Exception:  # never let the ticker kill the server
                pass

    threading.Thread(target=decay_loop, daemon=True).start()
    threading.Thread(target=poll_loop, args=(state,), daemon=True).start()
    print(f"PixelPal bridge v2 on http://{host}:{port}", flush=True)
    print(f"  GET /agents   all agents   GET /status   primary", flush=True)
    print(f"  paired: {'yes' if state.pairing_key else 'no (LAN open)'}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped", flush=True)
