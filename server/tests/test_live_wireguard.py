"""Opt-in API/kernel test; runs only inside a fresh, isolated Linux network namespace.

Example (from the repository root, with wireguard-tools installed):
  export WHITEGAME_PARENT_NETNS=$(readlink /proc/self/ns/net)
  sudo --preserve-env=WHITEGAME_PARENT_NETNS unshare -n -- env WHITEGAME_LIVE_WG=1 \
    python3 -m unittest discover -s server/tests -p test_live_wireguard.py -v

WG_TEST_BIN may point to a locally built wg binary. No host interfaces, routes,
firewalls, system configuration or long-lived services are modified.
"""
from __future__ import annotations

import http.client
import ipaddress
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
from urllib.parse import quote

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from provision import ApiServer, Provisioner, Settings, handler_for


@unittest.skipUnless(os.environ.get("WHITEGAME_LIVE_WG") == "1", "opt-in isolated Linux WireGuard test")
class LiveWireGuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not sys.platform.startswith("linux"):
            raise unittest.SkipTest("Linux network namespaces are required")
        cls.wg = os.environ.get("WG_TEST_BIN") or shutil.which("wg")
        for executable in (cls.wg, shutil.which("ip"), shutil.which("ping"), shutil.which("unshare"), shutil.which("nsenter")):
            if not executable:
                raise unittest.SkipTest("wg, ip, ping, unshare and nsenter must be installed")
        parent = os.environ.get("WHITEGAME_PARENT_NETNS")
        current = os.readlink("/proc/self/ns/net")
        if not parent or parent == current:
            raise AssertionError("Refusing live test outside an explicitly isolated network namespace")
        links = json.loads(subprocess.check_output(["ip", "-j", "link", "show"], text=True))
        if {link["ifname"] for link in links} != {"lo"}:
            raise AssertionError("Refusing live test in a namespace containing existing interfaces")

    def command(self, *args, input=None):
        return subprocess.run(args, input=input, text=True, capture_output=True, check=True, timeout=10).stdout.strip()

    def keypair(self):
        private = self.command(self.wg, "genkey")
        return private, self.command(self.wg, "pubkey", input=private + "\n")

    def test_api_installs_real_peer_survives_restart_transfers_and_revokes(self):
        enrollment = "enroll-live-test-" + "e" * 32
        admin = "admin-live-test-" + "a" * 32
        interface, client_interface = "wgprovision0", "wgclient0"
        server = worker = provisioner = child = None
        created_links = []
        with tempfile.TemporaryDirectory(prefix="whitegame-kernel-test-") as temp:
            directory = Path(temp)
            server_private, server_public = self.keypair()
            client_private, client_public = self.keypair()
            server_key = directory / "server.key"
            client_key = directory / "client.key"
            server_key.write_text(server_private + "\n")
            client_key.write_text(client_private + "\n")
            server_key.chmod(0o600)
            client_key.chmod(0o600)
            try:
                self.command("ip", "link", "set", "lo", "up")
                self.command("ip", "link", "add", "dev", interface, "type", "wireguard")
                created_links.append(interface)
                self.command(self.wg, "set", interface, "private-key", str(server_key), "listen-port", "0")
                self.command("ip", "address", "add", "10.80.0.1/24", "dev", interface)
                self.command("ip", "link", "set", interface, "up")
                port = self.command(self.wg, "show", interface, "listen-port")
                settings = Settings(
                    interface=interface, endpoint=f"127.0.0.1:{port}",
                    network=ipaddress.IPv4Network("10.80.0.0/24"), dns=("1.1.1.1",),
                    allowed_ips=("0.0.0.0/0",), enrollment_token=enrollment, admin_token=admin,
                    database=directory / "peers.sqlite3", wg_bin=self.wg)

                def start_api():
                    nonlocal server, worker, provisioner
                    provisioner = Provisioner(settings)
                    server = ApiServer(("127.0.0.1", 0), handler_for(provisioner))
                    worker = threading.Thread(target=server.serve_forever, daemon=True)
                    worker.start()

                def stop_api():
                    nonlocal server, worker, provisioner
                    if server is not None:
                        server.shutdown()
                        server.server_close()
                        worker.join(timeout=3)
                        server = worker = None
                    if provisioner is not None:
                        provisioner.close()
                        provisioner = None

                def request(method, target="/v1/peers", body=None, token=enrollment):
                    connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=5)
                    try:
                        connection.request(method, target, body=json.dumps(body) if body is not None else None,
                                           headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
                        response = connection.getresponse()
                        return response.status, json.loads(response.read())
                    finally:
                        connection.close()

                start_api()
                self.assertEqual(401, request("POST", body={"public_key": client_public}, token="wrong")[0])
                status, profile = request("POST", body={"public_key": client_public, "name": "Live kernel client"})
                self.assertEqual(201, status)
                self.assertEqual(server_public, profile["server_public_key"])
                self.assertEqual(["10.80.0.2/32"], profile["addresses"])
                self.assertEqual(client_public + "\t10.80.0.2/32", self.command(self.wg, "show", interface, "allowed-ips"))
                self.assertEqual((200, profile), request("POST", body={"public_key": client_public}))

                # Create the client socket in this isolated underlay, then move its interface
                # into a second namespace. Traffic must traverse WireGuard, not a local route.
                self.command("ip", "link", "add", "dev", client_interface, "type", "wireguard")
                created_links.append(client_interface)
                self.command(self.wg, "set", client_interface, "private-key", str(client_key),
                             "peer", server_public, "endpoint", profile["endpoint"],
                             "allowed-ips", "10.80.0.1/32")
                child_code = r'''
import json, os, subprocess, sys
print(json.dumps({"namespace":os.readlink("/proc/self/ns/net")}), flush=True)
for line in sys.stdin:
    action = json.loads(line)
    if action == "configure":
        for args in (("ip","link","set","lo","up"),
                     ("ip","address","add","10.80.0.2/32","dev","wgclient0"),
                     ("ip","link","set","wgclient0","up"),
                     ("ip","route","add","10.80.0.1/32","dev","wgclient0")):
            subprocess.run(args, check=True, capture_output=True)
        print(json.dumps({"configured":True}), flush=True)
    elif action == "ping":
        result = subprocess.run(("ping","-n","-c","1","-W","3","10.80.0.1"), capture_output=True)
        print(json.dumps({"returncode":result.returncode}), flush=True)
'''
                child = subprocess.Popen(["unshare", "-n", "--", sys.executable, "-u", "-c", child_code],
                                         stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                ready = json.loads(child.stdout.readline())
                self.assertNotEqual(os.readlink("/proc/self/ns/net"), ready["namespace"])
                self.command("ip", "link", "set", client_interface, "netns", str(child.pid))
                created_links.remove(client_interface)

                def child_action(action):
                    child.stdin.write(json.dumps(action) + "\n")
                    child.stdin.flush()
                    return json.loads(child.stdout.readline())

                self.assertTrue(child_action("configure")["configured"])
                self.assertEqual(0, child_action("ping")["returncode"], "Encrypted peer traffic did not pass")
                self.assertNotEqual("0", self.command(self.wg, "show", interface, "latest-handshakes").split()[1])

                # Simulate the interface losing runtime peers while the API is stopped.
                # SQLite must restore the exact allocation on API restart.
                stop_api()
                self.command(self.wg, "set", interface, "peer", client_public, "remove")
                self.assertEqual("", self.command(self.wg, "show", interface, "allowed-ips"))
                start_api()
                self.assertEqual((200, profile), request("POST", body={"public_key": client_public}))
                # A real client would renegotiate after its previous session expires.
                # Reset that session now to keep the test bounded to one handshake.
                self.command("nsenter", "-t", str(child.pid), "-n", "--", self.wg,
                             "set", client_interface, "peer", server_public, "remove")
                self.command("nsenter", "-t", str(child.pid), "-n", "--", self.wg,
                             "set", client_interface, "peer", server_public, "endpoint", profile["endpoint"],
                             "allowed-ips", "10.80.0.1/32")
                self.assertEqual(0, child_action("ping")["returncode"])
                self.assertEqual(1, len(request("GET", token=admin)[1]["peers"]))
                path = "/v1/peers/" + quote(client_public, safe="")
                self.assertEqual(401, request("DELETE", path, token=enrollment)[0])
                self.assertEqual(200, request("DELETE", path, token=admin)[0])
                self.assertEqual("", self.command(self.wg, "show", interface, "allowed-ips"))
                self.assertNotEqual(0, child_action("ping")["returncode"], "Revoked peer still passed traffic")
                stop_api()
                start_api()
                self.assertEqual([], request("GET", token=admin)[1]["peers"])
                self.assertEqual("", self.command(self.wg, "show", interface, "allowed-ips"))
            finally:
                if child is not None:
                    if child.stdin is not None:
                        child.stdin.close()
                    try:
                        child.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        child.kill()
                        child.wait(timeout=5)
                    child.stdout.close()
                    child.stderr.close()
                if server is not None:
                    server.shutdown()
                    server.server_close()
                    worker.join(timeout=3)
                if provisioner is not None:
                    provisioner.close()
                for link in reversed(created_links):
                    self.command("ip", "link", "delete", "dev", link)


if __name__ == "__main__":
    unittest.main()
