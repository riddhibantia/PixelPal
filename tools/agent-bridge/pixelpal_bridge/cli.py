"""
pixelpal-bridge CLI.

Commands (this is the whole developer experience):
    serve                     run the bridge
    install [--agent X]       register hooks, additively and reversibly
    uninstall                 remove exactly what install added
    doctor                    diagnose why nothing shows up
    simulate --agent X        drive a fake session through the real pipeline
    status                    print current agent envelopes and exit
"""

from __future__ import annotations

import argparse
import json
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path

from . import install as installer
from . import relay, relay_crypto, service
from .model import AgentEvent, AgentSource, EventKind
from .reducer import AgentRegistry
from .server import CONFIG_PATH, BridgeState, serve


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------


def _port_free(host: str, port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind((host, port))
            return True
        except OSError:
            return False


def _bridge_running(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.3):
            return True
    except OSError:
        return False


def _lan_ip() -> str | None:
    """Best-effort primary LAN IPv4 (no packets sent)."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("8.8.8.8", 80))
            return sock.getsockname()[0]
    except OSError:
        return None


# ---------------------------------------------------------------------------
# commands
# ---------------------------------------------------------------------------


def cmd_serve(args) -> int:
    config = {}
    if CONFIG_PATH.exists():
        try:
            config = json.loads(CONFIG_PATH.read_text())
        except (OSError, json.JSONDecodeError):
            config = {}

    port = args.port or int(config.get("port", 8787))
    host = args.host or str(config.get("host", "0.0.0.0"))
    key = config.get("pairingKey")

    if not _port_free(host, port):
        print(f"error: {host}:{port} is already in use.", file=sys.stderr)
        print("       another bridge may be running — `pixelpal-bridge status`", file=sys.stderr)
        return 1

    state = BridgeState(pairing_key=key)
    lan = _lan_ip()
    print()
    print("  PixelPal bridge")
    print(f"  local     http://127.0.0.1:{port}")
    if lan:
        print(f"  network   http://{lan}:{port}   <- use this in the app")
    print(f"  auth      {'pairing key required' if key else 'open on LAN (run `pair` to secure)'}")
    print()
    serve(host, port, state)
    return 0


def cmd_install(args) -> int:
    agents = args.agent or None
    print("Detected agents:")
    detected = installer.detected_agents()
    if not detected:
        print("  none found (looked for claude, agy, codex, omp, opencode, hermes)")
        return 1
    for name, path in detected.items():
        print(f"  • {name:12s} {path}")

    print()
    result = installer.install(agents)
    for agent, added in result.items():
        if added:
            print(f"  ✓ {agent:12s} registered {len(added)} hook(s)")
        else:
            print(f"  – {agent:12s} already installed (or nothing to do)")

    print()
    print("Next: start the bridge with `pixelpal-bridge serve`, then restart your agent.")
    print("Verify with `pixelpal-bridge doctor`.")
    return 0


def cmd_uninstall(args) -> int:
    removed = installer.uninstall_all()
    from .adapters import omp as omp_adapter
    from .adapters import opencode as oc_adapter

    removed["omp"] = omp_adapter.uninstall()
    removed["opencode"] = oc_adapter.uninstall()

    for agent, count in removed.items():
        print(f"  {'✓' if count else '–'} {agent:12s} removed {count} entry(ies)")
    return 0


def _load_or_create_config() -> dict:
    """Read ~/.config/pixelpal/bridge.json, creating it with a fresh key."""
    config: dict = {}
    if CONFIG_PATH.exists():
        try:
            config = json.loads(CONFIG_PATH.read_text())
        except (OSError, json.JSONDecodeError):
            config = {}

    changed = False
    if not config.get("pairingKey"):
        config["pairingKey"] = relay_crypto.new_bridge_key()
        changed = True
    config.setdefault("port", 8787)
    config.setdefault("host", "0.0.0.0")

    if changed or not CONFIG_PATH.exists():
        CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
        CONFIG_PATH.write_text(json.dumps(config, indent=2))
        CONFIG_PATH.chmod(0o600)
    return config


def _google_services_path() -> Path | None:
    """Find the app's google-services.json (relay needs the project id)."""
    here = Path(__file__).resolve()
    for parent in here.parents:
        candidate = parent / "app" / "google-services.json"
        if candidate.exists():
            return candidate
    return None


def _print_code(code: str) -> None:
    """Print the pairing code large and unmissable."""
    formatted = relay_crypto.format_code(relay_crypto.normalize_code(code))
    print()
    print("    ┌" + "─" * (len(formatted) + 6) + "┐")
    print(f"    │   {formatted}   │")
    print("    └" + "─" * (len(formatted) + 6) + "┘")
    print()


def cmd_setup(args) -> int:
    """
    One command. Installs hooks, starts the daemon, registers the relay, and
    prints the code to type into the app.
    """
    print()
    print("  PixelPal setup")
    print("  " + "─" * 14)
    print()

    problems: list[str] = []

    # 1. Agents ---------------------------------------------------------
    detected = installer.detected_agents()
    if not detected:
        print("  ! No supported agents found.")
        print("    Looked for: claude, agy, codex, omp, opencode, hermes")
        problems.append("no agents detected")
    else:
        added = installer.install(args.agent or None)
        for name in detected:
            entries = added.get(name) or []
            mark = "✓" if entries else "·"
            detail = f"{len(entries)} hook(s) added" if entries else "already set up"
            print(f"  {mark} {name:12s} {detail}")

    # 2. Config + key ---------------------------------------------------
    config = _load_or_create_config()
    port = args.port or int(config.get("port", 8787))
    print(f"  ✓ config       {CONFIG_PATH}")

    # 3. Daemon ---------------------------------------------------------
    if args.no_service:
        print("  · daemon       skipped (--no-service)")
    else:
        try:
            kind, description = service.install(port)
            print(f"  ✓ daemon       {description}")
            if kind == "detached":
                problems.append(
                    "no systemd/launchd — the bridge will not restart after reboot"
                )
        except Exception as error:  # noqa: BLE001 - report, never crash setup
            print(f"  ! daemon       failed: {error}")
            problems.append("daemon install failed")

    # 4. Relay ----------------------------------------------------------
    relay_code = None
    if args.no_relay:
        print("  · relay        skipped (--no-relay) — same-network only")
    else:
        config_path = _google_services_path()
        if config_path is None:
            print("  ! relay        app/google-services.json not found")
            problems.append("relay unavailable (no google-services.json)")
        else:
            try:
                firebase = relay.FirebaseConfig.from_google_services(config_path)
                relay_code = relay_crypto.generate_code()
                client = relay.RelayClient(
                    firebase, relay_code
                )
                client.register()
                print(f"  ✓ relay        {firebase.project_id}")
            except Exception as error:  # noqa: BLE001
                print(f"  ! relay        failed: {error}")
                print("                 deploy the rules first:")
                print("                 firebase deploy --only firestore:rules")
                problems.append("relay registration failed")

    # 5. The code -------------------------------------------------------
    print()
    if relay_code:
        print("  Pair your phone — type this in the app:")
        _print_code(relay_code)
        print("  Type it once. To pair another device, run `setup` again for a")
        print("  fresh code — running it does not disturb paired devices.")
    else:
        print("  Same-network only. Pair with:")
        print(f"    http://{_lan_ip() or 'YOUR-LAPTOP-IP'}:{port}")
        print(f"    key: {config['pairingKey']}")

    print(f"  Direct (LAN): http://{_lan_ip() or '127.0.0.1'}:{port}")
    print()

    if problems:
        print("  Notes:")
        for problem in problems:
            print(f"    · {problem}")
        print()

    print("  Done. Start an agent (e.g. `claude`) and your pet will wake up.")
    print()
    return 0


def cmd_status(args) -> int:
    import urllib.error
    import urllib.request

    port = args.port
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/agents", timeout=2) as resp:
            payload = json.loads(resp.read())
    except (urllib.error.URLError, OSError, ValueError):
        print(f"bridge not running on :{port}", file=sys.stderr)
        return 1

    agents = payload.get("agents", [])
    if not agents:
        print("no agents connected yet")
        return 0
    for agent in agents:
        age = agent.get("lastEventAgeSeconds")
        age_s = f"{age}s ago" if age is not None else "-"
        print(f"  {agent['agentName']:14s} {agent['status']:18s} {agent['message'][:40]:40s} {age_s}")
    return 0


def cmd_pair(args) -> int:
    """Generate (or show) the pairing key + a QR payload."""
    import secrets
    import base64

    config = {}
    if CONFIG_PATH.exists():
        try:
            config = json.loads(CONFIG_PATH.read_text())
        except (OSError, json.JSONDecodeError):
            config = {}

    if args.reset or not config.get("pairingKey"):
        config["pairingKey"] = secrets.token_urlsafe(24)
        config.setdefault("port", 8787)
        config.setdefault("host", "0.0.0.0")
        CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
        CONFIG_PATH.write_text(json.dumps(config, indent=2))
        Path(CONFIG_PATH).chmod(0o600)

    lan = _lan_ip()
    payload = {
        "v": 1,
        "key": config["pairingKey"],
        "port": config.get("port", 8787),
        "host": lan or "127.0.0.1",
    }
    uri = "pixelpal://pair?" + "&".join(f"{k}={v}" for k, v in payload.items())

    print("Pairing key stored at", CONFIG_PATH)
    print()
    print("  pair URI:", uri)
    print()
    try:
        import qrcode  # optional dependency

        qr = qrcode.QRCode(border=1)
        qr.add_data(uri)
        qr.make()
        qr.print_ascii(invert=True)
    except ImportError:
        print("  (install `qrcode` for a scannable QR: pip install qrcode)")
        print(f"  manual entry — host: {payload['host']}  port: {payload['port']}")
        print(f"  key: {config['pairingKey']}")
    return 0


def cmd_doctor(args) -> int:
    """Explain, by name, why the pet might be idle."""
    from .adapters import hermes as hermes_adapter
    from .adapters import omp as omp_adapter
    from .adapters import opencode as oc_adapter

    ok = True

    def check(condition: bool, label: str, hint: str = "") -> None:
        nonlocal ok
        mark = "✓" if condition else "✗"
        print(f"  {mark} {label}")
        if not condition:
            ok = False
            if hint:
                print(f"      {hint}")

    port = args.port
    print("PixelPal bridge doctor")
    print()

    print("Bridge")
    check(_port_free("0.0.0.0", port), f"port {port} is free",
          "another process holds it — stop it or use --port")
    check(_bridge_running(port), f"bridge responding on :{port}",
          "run `pixelpal-bridge serve` in another terminal")
    print()

    print("Agents")
    detected = installer.detected_agents()
    if not detected:
        check(False, "no supported agents found", "install claude, codex, omp, opencode or hermes")
    for name in detected:
        if name in ("claude_code", "agy", "codex"):
            path = detected[name]
            content = ""
            try:
                content = path.read_text()
            except OSError:
                pass
            check(installer.MARKER in content, f"{name} hooks installed",
                  f"run `pixelpal-bridge install --agent {name}`")
        elif name == "omp":
            check(omp_adapter.is_installed(), "omp extension installed",
                  "run `pixelpal-bridge install --agent omp`")
        elif name == "opencode":
            check(oc_adapter.is_available(), "opencode CLI present")
            check(oc_adapter.probe(), "opencode server running",
                  "start it with `opencode serve`")
        elif name == "hermes":
            check(hermes_adapter.is_available(), "hermes state DB readable",
                  "run hermes once so ~/.hermes/state.db exists")

    print()
    print("Network")
    lan = _lan_ip()
    check(lan is not None, "LAN address detected", "phone must reach this machine")
    if lan:
        print(f"      app endpoint: http://{lan}:{port}")
    config = {}
    if CONFIG_PATH.exists():
        try:
            config = json.loads(CONFIG_PATH.read_text())
        except (OSError, json.JSONDecodeError):
            pass
    check(bool(config.get("pairingKey")), "pairing key set (LAN secured)",
          "run `pixelpal-bridge pair` — without it anyone on your LAN can connect")

    print()
    print("OK" if ok else "Some checks failed — fix the ✗ items above.")
    return 0 if ok else 1


def cmd_simulate(args) -> int:
    """Drive a realistic session through the real reducer so you can see it work."""
    reg = AgentRegistry()
    try:
        source = AgentSource(args.agent)
    except ValueError:
        print(f"unknown agent '{args.agent}'", file=sys.stderr)
        return 2

    script = [
        (EventKind.SESSION_START, {}),
        (EventKind.PROMPT, {"detail": "refactor the auth module"}),
        (EventKind.TOOL_START, {"tool": "Read", "detail": "auth.ts"}),
        (EventKind.TOOL_END, {"tool": "Read"}),
        (EventKind.TOOL_START, {"tool": "Edit", "detail": "auth.ts"}),
        (EventKind.TOOL_END, {"tool": "Edit"}),
        (EventKind.TOOL_START, {"tool": "Bash", "detail": "npm test"}),
        (EventKind.TOOL_FAILURE, {"tool": "Bash", "detail": "2 tests failed"}),
        (EventKind.APPROVAL_REQUEST, {"approval_id": "sim-1", "detail": "run rm -rf dist/"}),
        (EventKind.APPROVAL_RESOLVED, {"detail": "approved"}),
        (EventKind.TURN_END, {}),
    ]

    delay = 0.0 if args.fast else 0.6
    print(f"Simulating {source.display_name} (press Ctrl-C to stop)\n")
    try:
        for kind, fields in script:
            event = AgentEvent(source=source, kind=kind, session_id="sim", **fields)
            state = reg.apply(event)
            env = state.to_envelope()
            approval = "  [APPROVAL]" if env.get("pendingApproval") else ""
            print(f"  {kind.value:18s} -> {env['status']:18s} {env['message'][:44]:44s}{approval}")
            if delay:
                time.sleep(delay)
    except KeyboardInterrupt:
        pass
    print("\nDone. The same events through `serve` drive the real pet.")
    return 0


# ---------------------------------------------------------------------------
# entry point
# ---------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="pixelpal-bridge",
        description="Connect terminal coding agents to PixelPal.",
    )
    parser.add_argument("--port", type=int, default=8787, help="bridge port (default 8787)")
    sub = parser.add_subparsers(dest="command")

    serve_p = sub.add_parser("serve", help="run the bridge")
    serve_p.add_argument("--host", default=None)
    serve_p.add_argument("--port", type=int, default=None)
    serve_p.set_defaults(func=cmd_serve)

    install_p = sub.add_parser("install", help="register agent hooks (reversible)")
    install_p.add_argument("--agent", action="append", help="limit to one agent (repeatable)")
    install_p.set_defaults(func=cmd_install)

    un_p = sub.add_parser("uninstall", help="remove the hooks install added")
    un_p.set_defaults(func=cmd_uninstall)

    setup_p = sub.add_parser("setup", help="one-shot setup: hooks + daemon + pairing code")
    setup_p.add_argument("--port", type=int, default=None)
    setup_p.add_argument("--agent", action="append", help="limit to one agent (repeatable)")
    setup_p.add_argument("--no-service", action="store_true", help="don't install the daemon")
    setup_p.add_argument("--no-relay", action="store_true", help="same-network only")
    setup_p.set_defaults(func=cmd_setup)

    status_p = sub.add_parser("status", help="print current agents")
    status_p.set_defaults(func=cmd_status)

    pair_p = sub.add_parser("pair", help="generate pairing key + QR")
    pair_p.add_argument("--reset", action="store_true", help="replace the existing key")
    pair_p.set_defaults(func=cmd_pair)

    doctor_p = sub.add_parser("doctor", help="diagnose setup problems")
    doctor_p.set_defaults(func=cmd_doctor)

    sim_p = sub.add_parser("simulate", help="run a fake agent session")
    sim_p.add_argument("--agent", default="claude_code")
    sim_p.add_argument("--fast", action="store_true", help="no delays")
    sim_p.set_defaults(func=cmd_simulate)

    args = parser.parse_args(argv)
    if not getattr(args, "func", None):
        parser.print_help()
        return 1
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
