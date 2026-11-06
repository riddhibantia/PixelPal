"""
Hook shim: the tiny process each agent invokes on a lifecycle event.

Design constraints, in priority order:
  1. NEVER slow the agent down  -> hard timeout, fire-and-forget, always exit 0
  2. NEVER hang the agent       -> PermissionRequest blocks only if configured
                                   and only for a bounded window
  3. Never leak content         -> only normalized short details leave the box

Registered per agent (see `install.py`). Invoked as:

    pixelpal-hook --source claude_code

Reads one JSON object on stdin.
"""

from __future__ import annotations

import json
import os
import socket
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

from .adapters import claude_code
from .model import AgentSource

# A hook that runs longer than this is worthless — the agent waits on it.
POST_TIMEOUT_SECONDS = 1.0
# PermissionRequest may wait for a human, but not forever.
APPROVAL_TIMEOUT_SECONDS = float(os.environ.get("PIXELPAL_APPROVAL_TIMEOUT", "600"))


def _bridge_addr() -> tuple[str, int]:
    """Bridge address from config, env override, or default."""
    host = os.environ.get("PIXELPAL_BRIDGE_HOST", "127.0.0.1")
    if host in ("0.0.0.0", "::", ""):
        host = "127.0.0.1"
    port = os.environ.get("PIXELPAL_BRIDGE_PORT")
    if port:
        try:
            return host, int(port)
        except ValueError:
            pass
    cfg_path = Path(
        os.environ.get("PIXELPAL_CONFIG", Path.home() / ".config" / "pixelpal" / "bridge.json")
    )
    try:
        cfg = json.loads(cfg_path.read_text())
        h = str(cfg.get("host", host))
        if h in ("0.0.0.0", "::", ""):
            h = "127.0.0.1"
        return h, int(cfg.get("port", 8787))
    except (OSError, ValueError, json.JSONDecodeError):
        return host, 8787


def _post(path: str, payload: dict, timeout: float) -> dict | None:
    """POST JSON to the bridge. Returns None on ANY failure (never raises)."""
    host, port = _bridge_addr()
    url = f"http://{host}:{port}{path}"
    data = json.dumps(payload).encode()
    req = urllib.request.Request(
        url, data=data, headers={"Content-Type": "application/json"}, method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read()
            return json.loads(body) if body else {}
    except (urllib.error.URLError, socket.timeout, OSError, ValueError, json.JSONDecodeError):
        return None


def _bridge_reachable() -> bool:
    host, port = _bridge_addr()
    try:
        with socket.create_connection((host, port), timeout=0.15):
            return True
    except OSError:
        return False


def main(argv: list[str] | None = None) -> int:
    """
    Exit code is ALWAYS 0 unless we are deliberately answering a permission
    request. A non-zero exit would make the agent show a hook error.
    """
    argv = argv if argv is not None else sys.argv[1:]

    source = AgentSource.CLAUDE_CODE
    for i, arg in enumerate(argv):
        if arg == "--source" and i + 1 < len(argv):
            try:
                source = AgentSource(argv[i + 1])
            except ValueError:
                pass

    # Fast path: no bridge running -> do nothing, cost ~1ms.
    if not _bridge_reachable():
        return 0
    if os.environ.get("PIXELPAL_HOOK_DISABLED") == "1":
        return 0

    payload = claude_code.read_payload()
    if not payload:
        return 0

    events = claude_code.translate(payload, source)
    if not events:
        return 0

    for event in events:
        _post("/events", event.to_dict(), POST_TIMEOUT_SECONDS)

    # PermissionRequest: optionally block for the user's decision.
    if (
        payload.get("hook_event_name") == "PermissionRequest"
        and os.environ.get("PIXELPAL_ANSWER_APPROVALS", "1") == "1"
    ):
        decision = _await_approval(events[0].approval_id, payload)
        if decision is not None:
            out = claude_code.build_decision_output(decision[0], decision[1])
            print(json.dumps(out))

    return 0


def _await_approval(approval_id: str | None, payload: dict) -> tuple[bool, str] | None:
    """
    Long-poll the bridge for a verdict.

    Returns (approved, message) or None to leave the flow to Claude's own
    prompt (the safe default if nobody answers in time).
    """
    if not approval_id:
        return None
    deadline = time.time() + APPROVAL_TIMEOUT_SECONDS
    delay = 0.25
    while time.time() < deadline:
        result = _post(
            "/approvals/wait",
            {"approvalId": approval_id, "timeout": 1.5},
            timeout=2.5,
        )
        if result:
            verdict = str(result.get("decision") or "").lower()
            if verdict in ("approve", "allow", "approved"):
                return True, ""
            if verdict in ("deny", "denied"):
                return False, str(result.get("message") or "Denied from PixelPal")
        time.sleep(delay)
        delay = min(delay * 1.5, 2.0)
    return None


if __name__ == "__main__":
    raise SystemExit(main())
