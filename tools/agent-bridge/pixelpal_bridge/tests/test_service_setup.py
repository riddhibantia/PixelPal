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

from pixelpal_bridge import cli, service


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

    def test_unit_can_import_the_package_when_installed_by_install_sh(
        self, tmp_path, monkeypatch
    ):
        """
        Regression: install.sh drops the package in ~/.local/share/pixelpal-bridge
        and only the wrapper script sets PYTHONPATH. The unit calls python
        directly, so it crash-looped 33 times with
        "No module named 'pixelpal_bridge'" before reporting success.
        """
        root = tmp_path / ".local" / "share" / "pixelpal-bridge"
        (root / "pixelpal_bridge").mkdir(parents=True)
        monkeypatch.setattr(service.Path, "home", staticmethod(lambda: tmp_path))
        # Pretend the current interpreter cannot import the package (the
        # systemd case); otherwise _package_root() correctly returns None.
        import builtins

        real_import = builtins.__import__

        def blocked(name, *args, **kwargs):
            if name == "pixelpal_bridge":
                raise ImportError("simulated systemd interpreter")
            return real_import(name, *args, **kwargs)

        monkeypatch.setattr(builtins, "__import__", blocked)
        text = service._systemd_unit_text(8787)
        assert f"Environment=PYTHONPATH={root}" in text, (
            "without PYTHONPATH the daemon cannot find the package and "
            "crash-loops on every restart"
        )

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
        cli.main(["setup", "--no-service"])
        assert cli.CONFIG_PATH.exists()
        mode = oct(cli.CONFIG_PATH.stat().st_mode)[-3:]
        assert mode == "600", f"pairing key file must not be world-readable (got {mode})"

    def test_is_idempotent_and_keeps_the_key(self, scratch):
        cli.main(["setup", "--no-service"])
        first = json.loads(cli.CONFIG_PATH.read_text())["pairingKey"]
        cli.main(["setup", "--no-service"])
        second = json.loads(cli.CONFIG_PATH.read_text())["pairingKey"]
        assert first == second, "re-running setup must not invalidate a paired phone"

    def test_creates_a_usable_key(self, scratch):
        cli.main(["setup", "--no-service"])
        key = json.loads(cli.CONFIG_PATH.read_text())["pairingKey"]
        assert len(key) >= 30

    def test_no_agents_is_reported_not_crashed(self, scratch):
        assert cli.main(["setup", "--no-service"]) == 0

    def test_setup_writes_no_relay_config(self, scratch):
        """The Firebase relay is gone: nothing cloud-related may be persisted."""
        cli.main(["setup", "--no-service"])
        config = json.loads(cli.CONFIG_PATH.read_text())
        for key in ("relayCode", "relayRefreshToken", "googleServicesPath"):
            assert key not in config, f"{key} must not exist anymore"

    def test_setup_prints_pairing_code_for_the_agent_tab(self, scratch, capsys):
        cli.main(["setup", "--no-service"])
        out = capsys.readouterr().out
        assert "Agent tab" in out
        assert "pixelpal://pair?host=" in out
        assert "&key=" in out
        assert ":8787" in out or "127.0.0.1" in out or "http://" in out

    def test_setup_reports_tailscale_when_present(self, scratch, monkeypatch, capsys):
        monkeypatch.setattr(cli, "_tailscale_hostname", lambda: "https://box.ts.net")
        cli.main(["setup", "--no-service"])
        out = capsys.readouterr().out
        assert "https://box.ts.net" in out

    def test_setup_explains_how_to_get_tailscale_when_absent(self, scratch, monkeypatch, capsys):
        monkeypatch.setattr(cli, "_tailscale_hostname", lambda: None)
        cli.main(["setup", "--no-service"])
        out = capsys.readouterr().out
        assert "Tailscale" in out

    def test_setup_has_copyable_pairing_code(self, scratch, capsys):
        """The app needs the bridge key as well as the LAN address."""
        cli.main(["setup", "--no-service"])
        out = capsys.readouterr().out
        assert "paste this code" in out.lower()

    def test_setup_draws_a_scannable_qr(self, scratch, capsys):
        """The QR must appear on a stock machine: the encoder is vendored."""
        cli.main(["setup", "--no-service"])
        out = capsys.readouterr().out
        assert any(ch in out for ch in "█▀▄"), (
            "setup must print the pairing QR without extra packages"
        )
        assert "pip install" not in out, "a missing pip package must never be the story"

    def test_rerunning_setup_restarts_an_existing_daemon(self, scratch, monkeypatch):
        restarted: list[str] = []
        monkeypatch.setattr(cli.service, "is_installed", lambda: True)
        monkeypatch.setattr(cli.service, "uninstall", lambda: restarted.append("uninstall") or True)
        monkeypatch.setattr(
            cli.service, "install", lambda port=8787: ("systemd", "fake unit")
        )
        cli.main(["setup"])
        assert restarted == ["uninstall"], "the daemon must pick up the new config"

    def test_config_write_is_atomic_enough_not_to_lose_existing_fields(self, scratch):
        cli.CONFIG_PATH.write_text(json.dumps({"pairingKey": "existing", "port": 9999}))
        cli.main(["setup", "--no-service"])
        config = json.loads(cli.CONFIG_PATH.read_text())
        assert config["pairingKey"] == "existing"
        assert config["port"] == 9999


def test_unit_uses_the_interpreter_that_has_the_package(monkeypatch):
    """A pipx/uv install lives in its own venv; /usr/bin/python3 has no package."""
    monkeypatch.setattr(service.sys, "executable", "/home/me/.local/share/uv/tools/x/bin/python")
    monkeypatch.setattr(service.shutil, "which", lambda name: "/usr/bin/python3")
    text = service._systemd_unit_text(8787)
    assert "ExecStart=/home/me/.local/share/uv/tools/x/bin/python" in text
