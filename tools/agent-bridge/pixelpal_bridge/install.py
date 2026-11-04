"""
Install/uninstall agent hooks — additively and reversibly.

Rules this module enforces:
  * NEVER clobber an existing hook. Existing user hooks (moshi, graft, orca)
    are preserved; ours is appended to the event's hook list.
  * Every entry we add carries MARKER so `uninstall` removes exactly what we
    added and nothing else.
  * Every write is atomic and a timestamped backup is kept first.
  * Idempotent: running install twice does not duplicate our hook.
"""

from __future__ import annotations

import json
import os
import shutil
import sys
import time
from pathlib import Path

MARKER = "pixelpal-hook"

CLAUDE_SETTINGS = Path.home() / ".claude" / "settings.json"
CODEX_HOOKS = Path.home() / ".codex" / "hooks.json"
AGY_SETTINGS = Path.home() / ".agy" / "settings.json"

# Events worth forwarding. Deliberately NOT every event: each hook costs the
# agent a process spawn, so we only take the lifecycle transitions that change
# what the pet shows. PostToolBatch/PreCompact/FileChanged are noise here.
CLAUDE_EVENTS = [
    "SessionStart",
    "SessionEnd",
    "UserPromptSubmit",
    "PreToolUse",
    "PostToolUse",
    "PostToolUseFailure",
    "PermissionRequest",
    "Notification",
    "Stop",
    "StopFailure",
    "SubagentStart",
]

CODEX_EVENTS = [
    "SessionStart",
    "UserPromptSubmit",
    "PreToolUse",
    "PostToolUse",
    "PermissionRequest",
    "Stop",
]


def _hook_command(source: str) -> dict:
    """
    The command entry we register.

    Uses `python3 -m pixelpal_bridge.hook` so it works from a pipx install;
    `--source` tells the adapter which agent fired it.
    """
    return {
        "type": "command",
        "command": f"python3 -m pixelpal_bridge.hook --source {source} #{MARKER}",
        "timeout": 5,
    }


def _hook_command_str(source: str) -> str:
    return f"python3 -m pixelpal_bridge.hook --source {source} #{MARKER}"


def _atomic_write(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        backup = path.with_suffix(path.suffix + f".bak-pixelpal-{int(time.time())}")
        shutil.copy2(path, backup)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, indent=2) + "\n")
    os.replace(tmp, path)


def _load(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        parsed = json.loads(path.read_text())
        return parsed if isinstance(parsed, dict) else {}
    except (OSError, json.JSONDecodeError):
        return {}


def install_claude_style(path: Path, source: str, events: list[str]) -> list[str]:
    """Install into a Claude-Code-format settings file (Claude, agy, Codex)."""
    settings = _load(path)
    hooks = settings.setdefault("hooks", {})
    added: list[str] = []

    for event in events:
        groups = hooks.setdefault(event, [])
        if not isinstance(groups, list):
            continue
        # Idempotency: is our hook already registered for this event?
        already = any(
            MARKER in json.dumps(group)
            for group in groups
        )
        if already:
            continue
        groups.append({"hooks": [_hook_command(source)]})
        added.append(event)

    if added:
        _atomic_write(path, settings)
    return added


def uninstall(path: Path) -> int:
    """Remove every hook entry carrying MARKER. Returns count removed."""
    settings = _load(path)
    hooks = settings.get("hooks")
    if not isinstance(hooks, dict):
        return 0

    removed = 0
    for event in list(hooks):
        groups = hooks[event]
        if not isinstance(groups, list):
            continue
        kept = []
        for group in groups:
            if MARKER in json.dumps(group):
                removed += 1
            else:
                kept.append(group)
        if kept:
            hooks[event] = kept
        else:
            del hooks[event]

    if removed:
        if hooks:
            _atomic_write(path, settings)
        else:
            settings.pop("hooks", None)
            _atomic_write(path, settings)
    return removed


def detected_agents() -> dict[str, Path]:
    """Which agents are actually present on this machine."""
    found: dict[str, Path] = {}
    if CLAUDE_SETTINGS.exists() or shutil.which("claude"):
        found["claude_code"] = CLAUDE_SETTINGS
    if AGY_SETTINGS.exists() or shutil.which("agy"):
        found["agy"] = AGY_SETTINGS
    if CODEX_HOOKS.exists() or shutil.which("codex"):
        found["codex"] = CODEX_HOOKS
    if shutil.which("omp") or (Path.home() / ".omp" / "agent").exists():
        found["omp"] = Path.home() / ".omp" / "agent" / "extensions"
    if shutil.which("opencode"):
        found["opencode"] = Path.home() / ".config" / "opencode"
    if shutil.which("hermes"):
        found["hermes"] = Path.home() / ".hermes"
    return found


def install(agents: list[str] | None = None) -> dict[str, list[str]]:
    """Install hooks for the requested (or all detected) agents."""
    targets = detected_agents()
    if agents:
        targets = {k: v for k, v in targets.items() if k in agents}

    result: dict[str, list[str]] = {}

    if "claude_code" in targets:
        result["claude_code"] = install_claude_style(
            CLAUDE_SETTINGS, "claude_code", CLAUDE_EVENTS
        )
    if "agy" in targets:
        result["agy"] = install_claude_style(AGY_SETTINGS, "agy", CLAUDE_EVENTS)
    if "codex" in targets:
        result["codex"] = install_claude_style(CODEX_HOOKS, "codex", CODEX_EVENTS)
    if "omp" in targets:
        from .adapters import omp as omp_adapter

        result["omp"] = omp_adapter.install()
    if "opencode" in targets:
        from .adapters import opencode as oc_adapter

        result["opencode"] = oc_adapter.install()

    return result


def uninstall_all() -> dict[str, int]:
    return {
        "claude_code": uninstall(CLAUDE_SETTINGS),
        "agy": uninstall(AGY_SETTINGS),
        "codex": uninstall(CODEX_HOOKS),
    }
