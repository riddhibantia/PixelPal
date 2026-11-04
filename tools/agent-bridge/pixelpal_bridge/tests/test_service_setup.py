"""
Tests for the background service and the one-shot `setup` command.

These are filesystem- and subprocess-heavy, so every test redirects the module
constants into tmp_path and stubs the systemctl/launchctl calls. Nothing here
touches the developer's real user services.
"""

from __future__ import annotations

import json
import subprocess

import pytest

from pixelpal_bridge import cli, relay_crypto, service


@pytest.fixture()
def isolated(tmp_path, monkeypatch):
    """Point every service path at a scratch directory."""
    monkeypatch.setattr(service, "SYSTEMD_DIR", tmp_path / "systemd")
    monkeypatch.setattr(service, "SYSTEMD_UNIT", tmp_path / "systemd" / "pixelpal-bridge.service")
    monkeypatch.setattr(service, "LAUNCHD_DIR", tmp_path / "launchd")
    monkeypatch.setattr(service, "LAUNCHD_PLIST", tmp_path / "launchd" / "com.pixelpal.plist")
    monkeypatch.setattr(service, "PIDFILE", tmp_path / "bridge.pid")
    monkeypatch.setattr(service, "_run", lambda *a, **k: subprocess.CompletedProcess(a, 0, "", ""))
    return tmp_path


class TestSystemdUnit:
    def test_unit_has_restart_always(self):
        text = service._systemd_unit_text(8787)
        assert "Restart=always" in text, "a crashed bridge must come back"

    def test_unit_is_enabled_at_default_target(self):
        text = service._systemd_unit_text(8787)
        assert "WantedBy=default.target" in text

    def test_unit_runs_serve_with_the_configured_port(self):
        text = service._systemd_unit_text(9555)
        assert "serve" in text and "--port 9555" in text

    def test_unit_has_no_absolute_user_specific_brittleness(self):
        """A unit that hardcodes a venv path breaks on upgrade."""
        text = service._systemd_unit_text(8787)
        assert "ExecStart=python3 " in text or "ExecStart=/" in text

    def test_install_writes_and_enables(self, isolated):
        description = service.install_systemd(8787)
        assert service.SYSTEMD_UNIT.exists()
        assert "systemd" in description

    def test_uninstall_removes_unit(self, isolated):
        service.install_systemd(8787)
        assert service.SYSTEMD_UNIT.exists()
        assert service.uninstall() is True
        assert not service.SYSTEMD_UNIT.exists()

    def test_uninstall_is_safe_when_nothing_installed(self, isolated):
        assert service.uninstall() is False


class TestLaunchd:
    def test_plist_is_well_formed_with_keepalive(self):
        text = service._launchd_plist_text(8787)
        assert "<key>KeepAlive</key><true/>" in text
        assert "<key>RunAtLoad</key><true/>" in text
        assert "com.pixelpal.pixelpal-bridge" in text

    def test_install_and_uninstall(self, isolated):
        service.install_launchd(8787)
        assert service.LAUNCHD_PLIST.exists()
        assert service.uninstall() is True
        assert not service.LAUNCHD_PLIST.exists()


class TestDetachedFallback:
    def test_detached_writes_pidfile(self, isolated, monkeypatch):
        class FakeProcess:
            pid = 4242

        monkeypatch.setattr(subprocess, "Popen", lambda *a, **k: FakeProcess())
        description = service.install_detached(8787)
        assert service.PIDFILE.exists()
        assert "NOT started automatically after reboot" in description, (
            "the fallback must be honest about its limitation"
        )


class TestStatus:
    def test_reports_not_installed(self, isolated):
        assert service.status() == "not installed"

    def test_reports_installed(self, isolated):
        service.install_systemd(8787)
        assert "systemd" in service.status()


class TestSetupCommand:
    @pytest.fixture()
    def scratch(self, tmp_path, monkeypatch):
        monkeypatch.setattr(cli, "CONFIG_PATH", tmp_path / "bridge.json")
        # No agents, no service, no relay: exercise the wiring only.
        monkeypatch.setattr(cli.installer, "detected_agents", lambda: {})
        monkeypatch.setattr(cli.installer, "install", lambda agents=None: {})
        return tmp_path

    def test_creates_config_with_private_mode(self, scratch):
        cli.main(["setup", "--no-service", "--no-relay"])
        assert cli.CONFIG_PATH.exists()
        mode = oct(cli.CONFIG_PATH.stat().st_mode)[-3:]
        assert mode == "600", f"pairing key file must not be world-readable (got {mode})"

    def test_is_idempotent_and_keeps_the_key(self, scratch):
        cli.main(["setup", "--no-service", "--no-relay"])
        first = json.loads(cli.CONFIG_PATH.read_text())["pairingKey"]
        cli.main(["setup", "--no-service", "--no-relay"])
        second = json.loads(cli.CONFIG_PATH.read_text())["pairingKey"]
        assert first == second, "re-running setup must not invalidate a paired phone"

    def test_creates_a_usable_key(self, scratch):
        cli.main(["setup", "--no-service", "--no-relay"])
        key = json.loads(cli.CONFIG_PATH.read_text())["pairingKey"]
        assert len(key) >= 30

    def test_no_agents_is_reported_not_crashed(self, scratch):
        assert cli.main(["setup", "--no-service", "--no-relay"]) == 0

    def test_relay_skipped_leaves_no_relay_code(self, scratch):
        cli.main(["setup", "--no-service", "--no-relay"])
        config = json.loads(cli.CONFIG_PATH.read_text())
        assert "relayCode" not in config

    def test_config_write_is_atomic_enough_not_to_lose_existing_fields(self, scratch):
        cli.CONFIG_PATH.write_text(json.dumps({"pairingKey": "existing", "port": 9999}))
        cli.main(["setup", "--no-service", "--no-relay"])
        config = json.loads(cli.CONFIG_PATH.read_text())
        assert config["pairingKey"] == "existing"
        assert config["port"] == 9999


class TestPairingCodePresentation:
    def test_printed_code_is_formatted_with_dashes(self, capsys):
        code = relay_crypto.generate_code()
        cli._print_code(code)
        out = capsys.readouterr().out
        assert "-" in out
        assert out.count("─") > 4, "the code should be in a visible box"

    def test_printed_code_normalizes_bad_input(self, capsys):
        code = relay_crypto.normalize_code(relay_crypto.generate_code())
        cli._print_code(code)
        out = capsys.readouterr().out
        assert relay_crypto.format_code(code) in out
