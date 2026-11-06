"""
Run the bridge as a background service — no terminal to keep open.

This is the piece that turns "I must leave a shell running forever" into
"it just works, including after a reboot".

Three platforms, honest about which is which:

  * **Linux (systemd --user)** — a real unit, `Restart=always`, enabled at
    `default.target` so it starts with your session and after reboot.
  * **macOS (launchd)** — a LaunchAgent with `RunAtLoad` + `KeepAlive`.
  * **anything else** — a detached process with a pidfile, plus a clear note
    that it will NOT survive a reboot (we do not pretend otherwise).

Everything here is reversible: `uninstall()` removes exactly what `install()`
created.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path

SERVICE_NAME = "pixelpal-bridge"

SYSTEMD_DIR = Path.home() / ".config" / "systemd" / "user"
SYSTEMD_UNIT = SYSTEMD_DIR / f"{SERVICE_NAME}.service"

LAUNCHD_DIR = Path.home() / "Library" / "LaunchAgents"
LAUNCHD_PLIST = LAUNCHD_DIR / f"com.pixelpal.{SERVICE_NAME}.plist"

PIDFILE = Path.home() / ".config" / "pixelpal" / "bridge.pid"


def _python() -> str:
    """
    The interpreter that actually has this package.

    `pixelpal-bridge` installed by pipx/uv runs inside its own venv; a unit
    pointing at /usr/bin/python3 dies with ModuleNotFoundError on every restart.
    """
    if sys.executable:
        return sys.executable
    return shutil.which("python3") or "python3"


def _run(command: list[str], *, check: bool = False) -> subprocess.CompletedProcess:
    return subprocess.run(
        command, capture_output=True, text=True, check=check, timeout=60
    )


def has_systemd() -> bool:
    if not shutil.which("systemctl"):
        return False
    result = _run(["systemctl", "--user", "is-system-running"])
    # `degraded` still means the user manager is alive and usable.
    return result.stdout.strip() in {"running", "degraded", "starting", "initializing"}


def has_launchd() -> bool:
    return sys.platform == "darwin" and shutil.which("launchctl") is not None


# ---------------------------------------------------------------------------
# systemd
# ---------------------------------------------------------------------------


def _package_root() -> str | None:
    """
    The directory to put on PYTHONPATH so `-m pixelpal_bridge.cli` resolves.

    Three install layouts exist and each needs a different answer:
      * repo checkout / pipx venv -> the interpreter already has the package,
        so no PYTHONPATH is needed.
      * install.sh (no venv) -> the package sits in
        ~/.local/share/pixelpal-bridge and is only reachable via PYTHONPATH.

    Returns None when the running interpreter can already import the package.
    """
    try:
        import pixelpal_bridge  # noqa: F401  (probe only)
        return None
    except ImportError:
        pass
    candidate = Path.home() / ".local" / "share" / "pixelpal-bridge"
    if (candidate / "pixelpal_bridge").is_dir():
        return str(candidate)
    # Last resort: the directory containing this file's parent package.
    return str(Path(__file__).resolve().parent.parent)


def _systemd_unit_text(port: int) -> str:
    python = _python()
    root = _package_root()
    # Without this, an install.sh install dies on every restart with
    # "No module named 'pixelpal_bridge'" — the wrapper script sets PYTHONPATH
    # but the unit bypasses the wrapper.
    env_line = f"Environment=PYTHONPATH={root}\n" if root else ""
    return f"""[Unit]
Description=PixelPal bridge — connects terminal coding agents to the PixelPal app
Documentation=https://github.com/pixelpal
After=network-online.target

[Service]
Type=simple
{env_line}ExecStart={python} -m pixelpal_bridge.cli serve --port {port}
Restart=always
RestartSec=3
# A bridge crash must never take the session down or spam logs.
StartLimitIntervalSec=60
StartLimitBurst=5

[Install]
WantedBy=default.target
"""


def install_systemd(port: int = 8787) -> str:
    SYSTEMD_DIR.mkdir(parents=True, exist_ok=True)
    SYSTEMD_UNIT.write_text(_systemd_unit_text(port))
    _run(["systemctl", "--user", "daemon-reload"])
    _run(["systemctl", "--user", "enable", SERVICE_NAME])
    _run(["systemctl", "--user", "restart", SERVICE_NAME])
    return f"systemd user unit at {SYSTEMD_UNIT}"


# ---------------------------------------------------------------------------
# launchd
# ---------------------------------------------------------------------------


def _launchd_plist_text(port: int) -> str:
    python = _python()
    root = _package_root()
    log_dir = Path.home() / "Library" / "Logs"
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>com.pixelpal.{SERVICE_NAME}</string>
  <key>ProgramArguments</key>
  <array>
    <string>{python}</string>
    <string>-m</string><string>pixelpal_bridge.cli</string>
    <string>serve</string>
    <string>--port</string><string>{port}</string>
  </array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PYTHONPATH</key><string>{root or ""}</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>{log_dir / 'pixelpal-bridge.log'}</string>
  <key>StandardErrorPath</key><string>{log_dir / 'pixelpal-bridge.log'}</string>
</dict>
</plist>
"""


def install_launchd(port: int = 8787) -> str:
    LAUNCHD_DIR.mkdir(parents=True, exist_ok=True)
    LAUNCHD_PLIST.write_text(_launchd_plist_text(port))
    _run(["launchctl", "unload", str(LAUNCHD_PLIST)])
    _run(["launchctl", "load", str(LAUNCHD_PLIST)])
    return f"launchd agent at {LAUNCHD_PLIST}"


# ---------------------------------------------------------------------------
# fallback: detached process
# ---------------------------------------------------------------------------


def _is_pid_running(pid: int) -> bool:
    if sys.platform == "win32":
        try:
            import ctypes
            PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
            STILL_ACTIVE = 259
            handle = ctypes.windll.kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
            if not handle:
                return False
            exit_code = ctypes.c_ulong()
            success = ctypes.windll.kernel32.GetExitCodeProcess(handle, ctypes.byref(exit_code))
            ctypes.windll.kernel32.CloseHandle(handle)
            return bool(success and exit_code.value == STILL_ACTIVE)
        except Exception:
            return False
    try:
        os.kill(pid, 0)
        return True
    except (ValueError, ProcessLookupError, OSError):
        return False


def _kill_pid(pid: int) -> None:
    if sys.platform == "win32":
        import signal
        try:
            os.kill(pid, signal.SIGTERM)
        except (OSError, SystemError):
            pass
    else:
        try:
            os.kill(pid, 15)
        except (ValueError, ProcessLookupError, OSError):
            pass


def install_detached(port: int = 8787) -> str:
    """No service manager: start a plain background process and say so."""
    PIDFILE.parent.mkdir(parents=True, exist_ok=True)
    log = PIDFILE.parent / "bridge.log"
    creationflags = 0
    start_new_session = True
    if sys.platform == "win32":
        creationflags = subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP
        start_new_session = False
    env = os.environ.copy()
    root = _package_root() or str(Path(__file__).resolve().parent.parent)
    if root:
        env["PYTHONPATH"] = f"{root}{os.pathsep}{env.get('PYTHONPATH', '')}".rstrip(os.pathsep)
    with open(log, "ab") as handle:
        process = subprocess.Popen(
            [_python(), "-m", "pixelpal_bridge.cli", "serve", "--port", str(port)],
            stdout=handle,
            stderr=handle,
            stdin=subprocess.DEVNULL,
            start_new_session=start_new_session,
            creationflags=creationflags,
            cwd=root if root else None,
            env=env,
        )
    PIDFILE.write_text(str(process.pid))
    return (
        f"background process pid {process.pid} (log: {log}) — "
        "NOT started automatically after reboot"
    )


# ---------------------------------------------------------------------------
# public API
# ---------------------------------------------------------------------------


def backend() -> str:
    if has_systemd():
        return "systemd"
    if has_launchd():
        return "launchd"
    return "detached"


def install(port: int = 8787) -> tuple[str, str]:
    """Install and start. Returns (backend, human description)."""
    kind = backend()
    if kind == "systemd":
        return kind, install_systemd(port)
    if kind == "launchd":
        return kind, install_launchd(port)
    return kind, install_detached(port)


def uninstall() -> bool:
    """Remove everything install() created. Safe to call when nothing exists."""
    removed = False

    if SYSTEMD_UNIT.exists():
        _run(["systemctl", "--user", "disable", "--now", SERVICE_NAME])
        SYSTEMD_UNIT.unlink()
        _run(["systemctl", "--user", "daemon-reload"])
        removed = True

    if LAUNCHD_PLIST.exists():
        _run(["launchctl", "unload", str(LAUNCHD_PLIST)])
        LAUNCHD_PLIST.unlink()
        removed = True

    if PIDFILE.exists():
        try:
            pid = int(PIDFILE.read_text().strip())
            _kill_pid(pid)
        except (ValueError, ProcessLookupError, OSError):
            pass
        PIDFILE.unlink()
        removed = True

    return removed


def status() -> str:
    """One short line describing the service state, for `doctor`."""
    if SYSTEMD_UNIT.exists():
        result = _run(["systemctl", "--user", "is-active", SERVICE_NAME])
        state = result.stdout.strip() or "unknown"
        return f"systemd: {state}"
    if LAUNCHD_PLIST.exists():
        result = _run(["launchctl", "list"])
        loaded = f"com.pixelpal.{SERVICE_NAME}" in result.stdout
        return f"launchd: {'loaded' if loaded else 'not loaded'}"
    if PIDFILE.exists():
        try:
            pid = int(PIDFILE.read_text().strip())
            if _is_pid_running(pid):
                return f"detached: running (pid {pid})"
            return "detached: stale pidfile"
        except (ValueError, ProcessLookupError, OSError):
            return "detached: stale pidfile"
    return "not installed"


def is_installed() -> bool:
    return SYSTEMD_UNIT.exists() or LAUNCHD_PLIST.exists() or PIDFILE.exists()
