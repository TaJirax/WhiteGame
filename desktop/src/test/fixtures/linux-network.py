"""Exercise the Linux desktop helper on a disposable GitHub-hosted runner."""

import base64
import contextlib
import ipaddress
import json
import os
from pathlib import Path
import selectors
import shutil
import socket
import subprocess
import sys
import tempfile
import unittest


HELPER = Path(__file__).resolve().parents[2] / "main/resources/whitegame/LinuxNetwork.sh"
XRAY_PROXY = "198.51.100.80"
XRAY_TARGETS = ("203.0.113.35", "2001:db8:203::35")
XRAY_PORT = 4430
XRAY_USER = b"whitegame-ci"
XRAY_PASSWORD = b"isolated-test"
ECHO_BYTES = 32


def command(*args, payload=None):
    return subprocess.run(
        args, input=payload, check=True, stdout=subprocess.PIPE, text=True, timeout=20,
    ).stdout.strip()


def wait_line(stream, timeout=15):
    with selectors.DefaultSelector() as ready:
        ready.register(stream, selectors.EVENT_READ)
        return stream.readline().strip() if ready.select(timeout=timeout) else ""


@contextlib.contextmanager
def lease(*arguments, namespace=None, payload=None, env=None):
    prefix = ["ip", "netns", "exec", namespace] if namespace else []
    with subprocess.Popen(
        [*prefix, "/bin/bash", str(HELPER), *arguments],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, env=env,
    ) as worker:
        try:
            if payload is not None:
                worker.stdin.write(base64.b64encode(payload.encode()).decode() + "\n")
                worker.stdin.flush()
            if wait_line(worker.stdout) != "READY":
                raise RuntimeError(f"The Linux {arguments[0]} helper did not become ready.")
            yield
        finally:
            worker.stdin.close()
            try:
                exit_code = worker.wait(timeout=15)
            except subprocess.TimeoutExpired:
                worker.kill()
                raise
            if exit_code:
                raise RuntimeError(f"The Linux {arguments[0]} helper closed with status {exit_code}.")


def socks_echo_fixture():
    """Authenticate and echo only the two reserved test destinations."""
    remaining = set(XRAY_TARGETS)
    with socket.create_server((XRAY_PROXY, 0)) as server:
        server.settimeout(25)
        print(server.getsockname()[1], flush=True)
        while remaining:
            connection, _ = server.accept()
            with connection, connection.makefile("rb") as incoming:
                connection.settimeout(5)
                version, methods = incoming.read(2)
                if version != 5 or 2 not in incoming.read(methods):
                    raise RuntimeError("Xray did not offer SOCKS5 username and password authentication.")
                connection.sendall(b"\x05\x02")

                version, length = incoming.read(2)
                username = incoming.read(length)
                password = incoming.read(int.from_bytes(incoming.read(1), "big"))
                if (version, username, password) != (1, XRAY_USER, XRAY_PASSWORD):
                    raise RuntimeError("Xray did not authenticate with the isolated peer.")
                connection.sendall(b"\x01\x00")

                version, operation, reserved, address_type = incoming.read(4)
                if (version, operation, reserved) != (5, 1, 0) or address_type not in (1, 4):
                    raise RuntimeError("Xray did not request a TCP connection to a numeric address.")
                address = str(ipaddress.ip_address(incoming.read(4 if address_type == 1 else 16)))
                port = int.from_bytes(incoming.read(2), "big")
                if address not in remaining or port != XRAY_PORT:
                    raise RuntimeError("Xray sent traffic to an unexpected test destination.")
                remaining.remove(address)
                connection.sendall(b"\x05\x00\x00\x01\x00\x00\x00\x00\x00\x00")
                payload = incoming.read(ECHO_BYTES)
                if len(payload) != ECHO_BYTES:
                    raise RuntimeError("The authenticated echo payload was incomplete.")
                connection.sendall(b"authenticated:" + payload)


def verify_tunnel_echo(host):
    payload = os.urandom(ECHO_BYTES)
    expected = b"authenticated:" + payload
    with socket.create_connection((host, XRAY_PORT), timeout=7) as connection:
        connection.sendall(payload)
        with connection.makefile("rb") as reply:
            if reply.read(len(expected)) != expected:
                raise RuntimeError("Traffic through the TUN adapter did not reach the authenticated peer.")


class LinuxNetworkIntegration(unittest.TestCase):
    def test_resolved_override_and_restore(self):
        link = "wgdns-ci"
        resolvectl = shutil.which("resolvectl")
        self.assertIsNotNone(resolvectl)
        command("systemctl", "is-active", "--quiet", "systemd-resolved")
        command("ip", "link", "add", link, "type", "dummy")
        self.addCleanup(command, "ip", "link", "delete", link)
        command("ip", "link", "set", link, "up")
        command(resolvectl, "default-route", link, "no")

        def servers():
            return command(resolvectl, "dns", link).partition(":")[2].split()

        for baseline in ("192.0.2.53", ""):
            command(resolvectl, "dns", link, baseline)
            before = servers()
            self.assertEqual(before, [baseline] if baseline else [])
            override = ["198.51.100.53", "2001:db8::53"]
            with lease("dns", resolvectl, link, *override):
                self.assertEqual(servers(), override)
            self.assertEqual(servers(), before)

    def test_wireguard_dns_is_scoped_to_its_link(self):
        adapter = "wg0123456790"
        resolver_ip, search_domain = "198.51.100.53", "fixture.whitegame.test"
        config = (
            f"[Interface]\nPrivateKey = {command('wg', 'genkey')}\nAddress = 10.203.219.1/32\n"
            f"DNS = {resolver_ip}, {search_domain}\n"
        )
        wg_quick = shutil.which("wg-quick")
        self.assertIsNotNone(wg_quick)

        def staged_configs():
            return [file for directory in (Path("/run"), Path("/etc/wireguard"))
                    for file in directory.glob(f"whitegame.*/{adapter}.conf")]

        with lease("tunnel", wg_quick, adapter, resolver_ip, "--", search_domain, payload=config):
            self.assertIn(resolver_ip, command("resolvectl", "dns", adapter))
            self.assertIn(search_domain, command("resolvectl", "domain", adapter))
            self.assertEqual(command("resolvectl", "default-route", adapter).rpartition(":")[2].strip(), "no")
            [staged] = staged_configs()
            self.assertEqual(staged.stat().st_mode & 0o077, 0)
            self.assertEqual(staged.parent.stat().st_mode & 0o077, 0)
            if staged.is_relative_to("/etc/wireguard"):
                self.assertNotIn("PrivateKey = ", staged.read_text())
        self.assertNotIn(adapter, command("ip", "-brief", "link", "show"))
        self.assertEqual(staged_configs(), [])

    def test_wireguard_authenticated_traffic_and_cleanup(self):
        client_namespace, peer_namespace = "wg-desktop-client", "wg-desktop-peer"
        for namespace in (client_namespace, peer_namespace):
            command("ip", "netns", "add", namespace)
            self.addCleanup(command, "ip", "netns", "delete", namespace)

        def peer(*args):
            return command("ip", "netns", "exec", peer_namespace, *args)

        def client(*args):
            return command("ip", "netns", "exec", client_namespace, *args)

        command("ip", "-n", client_namespace, "link", "add", "wglink0",
                "type", "veth", "peer", "name", "wglink1", "netns", peer_namespace)
        for namespace, link, ip in (
            (client_namespace, "wglink0", "192.0.2.1/30"),
            (peer_namespace, "wglink1", "192.0.2.2/30"),
        ):
            command("ip", "-n", namespace, "addr", "add", ip, "dev", link)
            command("ip", "-n", namespace, "link", "set", link, "up")

        peer_key, client_key = command("wg", "genkey"), command("wg", "genkey")
        peer_public = command("wg", "pubkey", payload=peer_key + "\n")
        client_public = command("wg", "pubkey", payload=client_key + "\n")
        # Ubuntu's wg AppArmor profile permits reading keys under /etc/wireguard.
        with tempfile.TemporaryDirectory(dir="/etc/wireguard", prefix="whitegame-ci-") as directory:
            keyfile = Path(directory) / "peer-key"
            keyfile.write_text(peer_key + "\n")
            peer("ip", "link", "add", "wgpeer", "type", "wireguard")
            peer("wg", "set", "wgpeer", "listen-port", "51820", "private-key",
                 str(keyfile), "peer", client_public, "allowed-ips", "10.203.218.1/32")
        peer("ip", "addr", "add", "10.203.218.2/32", "dev", "wgpeer")
        peer("ip", "link", "set", "wgpeer", "up")
        peer("ip", "route", "add", "10.203.218.1/32", "dev", "wgpeer")

        full_target = "203.0.113.34"
        peer("ip", "addr", "add", f"{full_target}/32", "dev", "wgpeer")
        client("ip", "route", "add", "default", "via", "192.0.2.2", "dev", "wglink0")
        baseline_routes = client("ip", "-4", "route", "show", "table", "all")
        baseline_rules = client("ip", "-4", "rule", "show")
        baseline_firewall = client("nft", "list", "tables")

        adapter = "wg0123456789"
        wg_quick = shutil.which("wg-quick")
        self.assertIsNotNone(wg_quick)
        for mode, allowed, target in (
            ("split", "10.203.218.2/32", "10.203.218.2"),
            ("IPv4 default", "0.0.0.0/0", full_target),
        ):
            with self.subTest(mode=mode):
                config = (
                    f"[Interface]\nPrivateKey = {client_key}\nAddress = 10.203.218.1/32\n\n"
                    f"[Peer]\nPublicKey = {peer_public}\nAllowedIPs = {allowed}\n"
                    "Endpoint = 192.0.2.2:51820\n"
                )
                with lease("tunnel", wg_quick, adapter, namespace=client_namespace, payload=config):
                    self.assertIn(f"dev {adapter}", client("ip", "route", "get", target))
                    client("ping", "-c", "3", "-w", "8", target)
                    self.assertGreater(int(client("wg", "show", adapter, "latest-handshakes").split()[1]), 0)
                    _, received, transmitted = client("wg", "show", adapter, "transfer").split()
                    self.assertGreater(int(received), 0)
                    self.assertGreater(int(transmitted), 0)

                self.assertNotIn(adapter, client("ip", "-brief", "link", "show"))
                self.assertEqual(client("ip", "-4", "route", "show", "table", "all"), baseline_routes)
                self.assertEqual(client("ip", "-4", "rule", "show"), baseline_rules)
                self.assertEqual(client("nft", "list", "tables"), baseline_firewall)

    def test_xray_authenticated_tun_traffic_and_cleanup(self):
        binary = os.environ.get("WHITEGAME_XRAY_CI", "")
        self.assertTrue(Path(binary).is_file() and os.access(binary, os.X_OK),
                        "Provide the checksum-verified Xray binary in WHITEGAME_XRAY_CI.")
        binary = str(Path(binary).resolve())
        runner_uid = os.environ.get("SUDO_UID", "")
        self.assertTrue(runner_uid.isdecimal() and int(runner_uid) > 0,
                        "Run the fixture with sudo from the unprivileged runner account.")
        client_ns, proxy_ns = "xray-desktop-client", "xray-desktop-peer"
        client_link, proxy_link = "xrlink0", "xrlink1"
        for namespace in (client_ns, proxy_ns):
            command("ip", "netns", "add", namespace)
            self.addCleanup(command, "ip", "netns", "delete", namespace)
            command("ip", "-n", namespace, "link", "set", "lo", "up")

        def client(*args):
            return command("ip", "netns", "exec", client_ns, *args)

        command("ip", "-n", client_ns, "link", "add", client_link, "type", "veth",
                "peer", "name", proxy_link, "netns", proxy_ns)
        for namespace, link, ipv4, ipv6 in (
            (client_ns, client_link, "192.0.2.1/30", "2001:db8:42::1/64"),
            (proxy_ns, proxy_link, "192.0.2.2/30", "2001:db8:42::2/64"),
        ):
            command("ip", "-n", namespace, "link", "set", link, "addrgenmode", "none")
            command("ip", "-n", namespace, "addr", "add", ipv4, "dev", link)
            command("ip", "-n", namespace, "-6", "addr", "add", ipv6, "dev", link, "nodad")
            command("ip", "-n", namespace, "link", "set", link, "up")
        command("ip", "-n", proxy_ns, "addr", "add", f"{XRAY_PROXY}/32", "dev", "lo")
        client("ip", "-4", "route", "add", "default", "via", "192.0.2.2", "dev", client_link)
        client("ip", "-6", "route", "add", "default", "via", "2001:db8:42::2", "dev", client_link)

        def routes_and_rules():
            return [client("ip", family, *args) for family in ("-4", "-6")
                    for args in (("route", "show", "table", "all"), ("rule", "show"))]

        adapter = "wg0123456791"
        baseline = routes_and_rules()
        for host in XRAY_TARGETS:
            self.assertIn(f"dev {client_link}", client("ip", "route", "get", host))

        with subprocess.Popen(
            ["ip", "netns", "exec", proxy_ns, sys.executable, str(Path(__file__).resolve()), "--socks-fixture"],
            stdout=subprocess.PIPE, text=True,
        ) as proxy:
            try:
                port = wait_line(proxy.stdout, timeout=5)
                self.assertTrue(port.isdecimal(), "The isolated authenticated SOCKS fixture did not start.")
                config = json.dumps({
                    "log": {"loglevel": "warning"},
                    "inbounds": [{"tag": "tun", "protocol": "tun", "settings": {
                        "name": adapter,
                        "MTU": 1500,
                        "gateway": ["198.18.0.1/30", "fd01:7767::1/64"],
                        "autoSystemRoutingTable": ["0.0.0.0/1", "128.0.0.0/1", "::/1", "8000::/1"],
                        "autoOutboundsInterface": "auto",
                    }}],
                    "outbounds": [{"tag": "proxy", "protocol": "socks", "settings": {"servers": [{
                        "address": XRAY_PROXY,
                        "port": int(port),
                        "users": [{"user": XRAY_USER.decode(), "pass": XRAY_PASSWORD.decode()}],
                    }]}}],
                })
                with lease("xray", binary, adapter, namespace=client_ns, payload=config,
                           env={**os.environ, "PKEXEC_UID": runner_uid}):
                    for host in XRAY_TARGETS:
                        self.assertIn(f"dev {adapter}", client("ip", "route", "get", host))
                        client(sys.executable, str(Path(__file__).resolve()), "--probe", host)
                    [link] = json.loads(client("ip", "-j", "-s", "link", "show", "dev", adapter))
                    stats = link.get("stats64") or link["stats"]
                    for direction in ("rx", "tx"):
                        self.assertGreater(stats[direction]["bytes"], 0)
                    self.assertEqual(proxy.wait(timeout=8), 0)

                    statuses = [dict(line.split(":", 1) for line in Path(f"/proc/{pid}/status").read_text()
                                     .splitlines() if ":" in line)
                                for pid in command("ip", "netns", "pids", client_ns).split()]
                    [xray] = [status for status in statuses if status["Name"].strip() == Path(binary).name]
                    self.assertEqual(xray["Uid"].split(), [runner_uid] * 4)
                    net_only = (1 << 12) | (1 << 13)  # CAP_NET_ADMIN and CAP_NET_RAW.
                    self.assertEqual(int(xray["CapBnd"], 16), net_only)
            finally:
                if proxy.poll() is None:
                    proxy.kill()

        self.assertNotIn(adapter, client("ip", "-brief", "link", "show"))
        self.assertEqual(routes_and_rules(), baseline)

        payload = base64.b64encode(config.encode()).decode() + "\n"

        def assert_startup_rejected():
            rejected = subprocess.run(
                ["ip", "netns", "exec", client_ns, "/bin/bash", str(HELPER), "xray", binary, adapter],
                input=payload, stdout=subprocess.PIPE, text=True, timeout=20,
            )
            self.assertNotEqual(rejected.returncode, 0)
            self.assertNotIn("READY", rejected.stdout.splitlines())

        # A conflicting second route exercises rollback after the first has been installed.
        conflict = ("128.0.0.0/1", "via", "192.0.2.2", "dev", client_link, "metric", "1")
        client("ip", "-4", "route", "add", *conflict)
        baseline = routes_and_rules()
        assert_startup_rejected()
        self.assertNotIn(adapter, client("ip", "-brief", "link", "show"))
        self.assertEqual(routes_and_rules(), baseline)
        client("ip", "-4", "route", "del", *conflict)

        # An adapter left by another owner must survive a rejected startup unchanged.
        client("ip", "link", "add", adapter, "type", "dummy")
        original_link = client("ip", "-brief", "link", "show", "dev", adapter)
        baseline = routes_and_rules()
        assert_startup_rejected()
        self.assertEqual(client("ip", "-brief", "link", "show", "dev", adapter), original_link)
        self.assertEqual(routes_and_rules(), baseline)


if __name__ == "__main__":
    if (os.geteuid(), os.getenv("GITHUB_ACTIONS"), os.getenv("RUNNER_ENVIRONMENT")) != (0, "true", "github-hosted"):
        raise SystemExit("Run this test as root on a disposable GitHub-hosted Linux runner.")
    if sys.argv[1:] == ["--socks-fixture"]:
        socks_echo_fixture()
    elif len(sys.argv) == 3 and sys.argv[1] == "--probe" and sys.argv[2] in XRAY_TARGETS:
        verify_tunnel_echo(sys.argv[2])
    else:
        unittest.main(verbosity=2)
