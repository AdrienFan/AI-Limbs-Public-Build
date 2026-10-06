import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
ASSETS = ROOT / "plugin-lab/extensions/ubuntu-system/runtime/terminal-core/src/main/assets"


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ASSETS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class DirectRoutingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.direct = load("laner_direct_test", "laner-direct.py")
        self.net = load("laner_net_test", "laner-net.py")
        self.direct.SNAPSHOT = self.root / "host.json"
        self.direct.CONFIG = self.root / "direct.conf"
        self.direct.LIBRARY = self.root / "routing.so"
        self.direct.LIBRARY.write_bytes(b"asset existence fixture")
        self.net.DIRECT_LIBRARY = str(self.direct.LIBRARY)
        self.net.HOST_LISTENERS_FILE = self.direct.SNAPSHOT
        self.net.CONFIG_FILE = self.root / "vpn.json"
        self.net.CONFIG_DIR = self.root
        self.state = {"available": True, "ports": [7890, 39065],
                      "updated_at_epoch_ms": int(time.time() * 1000),
                      "direct_proxy": {"available": True, "scheme": "socks5h",
                                       "host": "127.0.0.1", "port": 39065,
                                       "validated": True, "network_not_vpn": True,
                                       "transport": "wifi"}}
        self.save()

    def save(self):
        self.direct.SNAPSHOT.write_text(json.dumps(self.state))

    def test_direct_launch_removes_inherited_vpn_proxy(self):
        with patch.dict(os.environ, {"HTTPS_PROXY": "http://127.0.0.1:7890"}, clear=True):
            env = self.direct.direct_environment()
        self.assertEqual("DIRECT", env["LANER_EGRESS_MODE"])
        self.assertNotIn("HTTPS_PROXY", env)
        self.assertEqual(str(self.direct.LIBRARY), env["LD_PRELOAD"])
        self.assertIn("socks5 127.0.0.1 39065", self.direct.CONFIG.read_text())
        self.assertIn("localnet 127.0.0.0/255.0.0.0", self.direct.CONFIG.read_text())

    def test_unavailable_physical_route_never_uses_vpn(self):
        self.state["direct_proxy"]["available"] = False
        self.save()
        with patch.dict(os.environ, {"ALL_PROXY": "http://127.0.0.1:7890"}, clear=True):
            env = self.direct.direct_environment()
        self.assertEqual("DIRECT_UNAVAILABLE", env["LANER_EGRESS_MODE"])
        self.assertNotIn("ALL_PROXY", env)
        self.assertIn("socks5 127.0.0.1 1", self.direct.CONFIG.read_text())

    def test_stale_or_future_snapshot_is_not_accepted(self):
        for shift in (-130000, 10000):
            self.state["updated_at_epoch_ms"] = int(time.time() * 1000) + shift
            self.save()
            with self.assertRaises(ValueError):
                self.direct.direct_endpoint()

    def test_missing_library_refuses_unconfigured_launch(self):
        self.direct.LIBRARY.unlink()
        with self.assertRaisesRegex(ValueError, "library is missing"):
            self.direct.direct_environment()

    def test_vpn_discovery_excludes_direct_listener(self):
        ports, notes = self.net.load_host_listener_ports()
        self.assertEqual([7890], ports)
        self.assertIn("host_direct_proxy:excluded:39065", notes)

    def test_cached_direct_endpoint_is_rejected_for_all_schemes(self):
        for scheme in ("http", "socks5h"):
            config = self.net.default_config()
            config["proxy"] = f"{scheme}://127.0.0.1:39065"
            with patch.object(self.net, "proxy_probe") as probe, \
                 patch.object(self.net, "detect_proxy", return_value=("http://127.0.0.1:7890", [])):
                proxy, _, notes = self.net.ensure_proxy(config)
            probe.assert_not_called()
            self.assertEqual("http://127.0.0.1:7890", proxy)
            self.assertIn("cached_proxy_rejected:host_direct_proxy", notes)

    def test_explicit_vpn_preserves_other_preloads_and_drops_direct(self):
        with patch.dict(os.environ, {"LD_PRELOAD": f"{self.direct.LIBRARY} /custom.so",
                                     "PROXYCHAINS_CONF_FILE": "/direct.conf",
                                     "LANER_EGRESS_MODE": "DIRECT",
                                     "LANER_DIRECT_SCOPE": "dynamic_tcp"}, clear=True):
            env = self.net.proxy_environment("http://127.0.0.1:7890")
        self.assertEqual("/custom.so", env["LD_PRELOAD"])
        self.assertEqual("VPN", env["LANER_EGRESS_MODE"])
        self.assertNotIn("PROXYCHAINS_CONF_FILE", env)
        self.assertNotIn("LANER_DIRECT_SCOPE", env)
        self.assertEqual("http://127.0.0.1:7890", env["HTTPS_PROXY"])

    def test_nested_vpn_shell_keeps_explicit_proxy(self):
        env = {"PATH": os.environ["PATH"], "LANER_NET_ACTIVE": "1",
               "LANER_EGRESS_MODE": "VPN", "HTTPS_PROXY": "http://127.0.0.1:7890"}
        shell = subprocess.run(["/bin/bash", "--noprofile", "--norc", "-c",
                                '. "$1"; printf "%s %s" "$LANER_EGRESS_MODE" "$HTTPS_PROXY"',
                                "bash", str(ASSETS / "ai_limbs_direct_profile.sh")],
                               env=env, text=True, capture_output=True, check=True)
        self.assertEqual("VPN http://127.0.0.1:7890", shell.stdout)


if __name__ == "__main__":
    unittest.main()
