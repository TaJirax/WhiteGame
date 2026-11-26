"""Exercise the Linux desktop helper on a disposable GitHub-hosted runner."""

import base64
import contextlib
import os
from pathlib import Path
import selectors
import shutil
import subprocess
import tempfile
import unittest


HELPER = Path(__file__).resolve().parents[2] / "main/resources/whitegame/LinuxNetwork.sh"


def command(*args, payload=None):
    return subprocess.run(
        args, input=payload, check=True, stdout=subprocess.PIPE, text=True, timeout=20,
    ).stdout.strip()


@contextlib.contextmanager
def lease(*arguments, namespace=None, payload=None):
    prefix = ["ip", "netns", "exec", namespace] if namespace else []
    with subprocess.Popen(
        [*prefix, "/bin/bash", str(HELPER), *arguments],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True,
    ) as worker:
        try:
            if payload is not None:
                worker.stdin.write(base64.b64encode(payload.encode()).decode() + "\n")
                worker.stdin.flush()
            with selectors.DefaultSelector() as ready:
                ready.register(worker.stdout, selectors.EVENT_READ)
                if not ready.select(timeout=15) or worker.stdout.readline().strip() != "READY":
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

        adapter = "wg0123456789"
        config = (
            f"[Interface]\nPrivateKey = {client_key}\nAddress = 10.203.218.1/32\n\n"
            f"[Peer]\nPublicKey = {peer_public}\nAllowedIPs = 10.203.218.2/32\n"
            "Endpoint = 192.0.2.2:51820\n"
        )
        wg_quick = shutil.which("wg-quick")
        self.assertIsNotNone(wg_quick)
        with lease("tunnel", wg_quick, adapter, namespace=client_namespace, payload=config):
            self.assertIn(adapter, client("ip", "route", "get", "10.203.218.2"))
            client("ping", "-c", "3", "-w", "8", "10.203.218.2")
            self.assertGreater(int(client("wg", "show", adapter, "latest-handshakes").split()[1]), 0)
            _, received, transmitted = client("wg", "show", adapter, "transfer").split()
            self.assertGreater(int(received), 0)
            self.assertGreater(int(transmitted), 0)

        self.assertNotIn(adapter, client("ip", "-brief", "link", "show"))
        self.assertEqual(client("ip", "route", "show", "10.203.218.2/32"), "")


if __name__ == "__main__":
    if (os.geteuid(), os.getenv("GITHUB_ACTIONS"), os.getenv("RUNNER_ENVIRONMENT")) != (0, "true", "github-hosted"):
        raise SystemExit("Run this test as root on a disposable GitHub-hosted Linux runner.")
    unittest.main(verbosity=2)
