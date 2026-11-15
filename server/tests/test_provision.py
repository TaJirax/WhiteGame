import base64
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
import http.client
import ipaddress
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from urllib.parse import quote

from server.provision import (ApiError, ApiServer, DriverError, Provisioner, Settings,
                              WireGuard, handler_for, public_key)


def key(number):
    return base64.b64encode(bytes([number]) * 32).decode("ascii")


class FakeWireGuard:
    def __init__(self):
        self.live = {}
        self.added = []
        self.removed = []
        self.fail_add = False
        self.fail_remove = False
        self.identity = key(200)

    def server_key(self):
        return self.identity

    def peers(self):
        return dict(self.live)

    def add(self, public, address):
        if self.fail_add:
            raise DriverError("fixture add failure")
        self.live[public] = [ipaddress.ip_network(address)]
        self.added.append(public)

    def remove(self, public):
        if self.fail_remove:
            raise DriverError("fixture remove failure")
        self.live.pop(public, None)
        self.removed.append(public)


class ProvisionFixture:
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.settings = Settings("wg0", "vpn.example.com:51820", ipaddress.IPv4Network("10.8.0.0/28"),
                                 ("1.1.1.1",), ("0.0.0.0/0",), "e" * 40, "a" * 40,
                                 Path(self.tmp.name) / "state" / "peers.sqlite3")
        self.driver = FakeWireGuard()
        self.app = Provisioner(self.settings, self.driver)

    def tearDown(self):
        self.app.close()
        self.tmp.cleanup()

    def restart(self):
        self.app.close()
        self.app = Provisioner(self.settings, self.driver)

    def assert_api_error(self, status, operation):
        with self.assertRaises(ApiError) as caught:
            operation()
        self.assertEqual(status, caught.exception.status)


class ProvisionTest(ProvisionFixture, unittest.TestCase):
    def test_enrollment_returns_complete_profile_and_replays_without_new_address(self):
        profile, created = self.app.enroll({"public_key": key(1), "name": "My phone"})
        self.assertTrue(created)
        self.assertEqual({"public_key": key(1), "server_public_key": key(200),
                          "addresses": ["10.8.0.2/32"], "dns": ["1.1.1.1"],
                          "endpoint": "vpn.example.com:51820", "allowed_ips": ["0.0.0.0/0"],
                          "mtu": 1280, "persistent_keepalive": 25}, profile)
        self.assertEqual((profile, False), self.app.enroll({"public_key": key(1)}))
        self.assertEqual(1, len(self.app.list_peers()["peers"]))
        self.assertEqual("My phone", self.app.list_peers()["peers"][0]["name"])
        self.restart()
        self.assertEqual((profile, False), self.app.enroll({"public_key": key(1)}))

    def test_inputs_cannot_inject_commands_or_store_private_keys(self):
        for body in [{}, [], {"public_key": "x; rm -rf /"}, {"public_key": key(1), "private_key": key(2)},
                     {"public_key": key(200)}, {"public_key": key(1), "name": "bad\nname"},
                     {"public_key": key(1), "name": "a" * 65}, {"public_key": key(1), "name": 1}]:
            with self.subTest(body=body):
                self.assert_api_error(400, lambda: self.app.enroll(body))
        self.assertEqual([], self.app.rows())
        self.assertEqual([], self.driver.added)

    def test_unmanaged_peers_and_live_routes_are_not_overwritten(self):
        self.driver.live[key(8)] = [ipaddress.ip_network("10.8.0.2/31")]
        self.assert_api_error(409, lambda: self.app.enroll({"public_key": key(8)}))
        profile, _ = self.app.enroll({"public_key": key(1)})
        self.assertEqual(["10.8.0.4/32"], profile["addresses"])
        self.assertEqual([ipaddress.ip_network("10.8.0.2/31")], self.driver.live[key(8)])
        self.driver.live[key(1)] = [ipaddress.ip_network("10.8.0.9/32")]
        self.assert_api_error(409, lambda: self.app.enroll({"public_key": key(1)}))
        self.assertEqual([ipaddress.ip_network("10.8.0.9/32")], self.driver.live[key(1)])

    def test_pool_exhaustion_excludes_network_gateway_and_broadcast(self):
        self.app.close()
        self.settings = replace(self.settings, network=ipaddress.IPv4Network("10.9.0.0/30"),
                                database=Path(self.tmp.name) / "small.sqlite3")
        self.app = Provisioner(self.settings, self.driver)
        profile, _ = self.app.enroll({"public_key": key(1)})
        self.assertEqual(["10.9.0.2/32"], profile["addresses"])
        self.assert_api_error(409, lambda: self.app.enroll({"public_key": key(2)}))

    def test_concurrent_enrollment_has_unique_addresses_and_idempotent_retries(self):
        requests = [key(i) for i in range(1, 9)] + [key(1)] * 6
        with ThreadPoolExecutor(max_workers=8) as pool:
            replies = list(pool.map(lambda public: self.app.enroll({"public_key": public}), requests))
        self.assertEqual(8, sum(created for _, created in replies))
        self.assertEqual(8, len({profile["addresses"][0] for profile, _ in replies}))
        self.assertEqual(1, len({profile["addresses"][0] for profile, _ in replies if profile["public_key"] == key(1)}))

    def test_failed_apply_retains_journal_and_address_until_retry_and_restart(self):
        self.driver.fail_add = True
        with self.assertRaises(DriverError):
            self.app.enroll({"public_key": key(1)})
        self.assertEqual("pending", self.app.rows()[0]["state"])
        self.driver.fail_add = False
        other, _ = self.app.enroll({"public_key": key(2)})
        self.assertEqual(["10.8.0.3/32"], other["addresses"])
        self.restart()
        self.assertEqual(["active", "active"], [row["state"] for row in self.app.rows()])
        original, created = self.app.enroll({"public_key": key(1)})
        self.assertFalse(created)
        self.assertEqual(["10.8.0.2/32"], original["addresses"])

    def test_restart_reapplies_peers_when_kernel_state_is_lost(self):
        self.app.enroll({"public_key": key(1)})
        self.driver.live.clear()
        self.restart()
        self.assertEqual([ipaddress.ip_network("10.8.0.2/32")], self.driver.live[key(1)])

    def test_crash_after_kernel_apply_before_activation_replays_same_reserved_address(self):
        profile, _ = self.app.enroll({"public_key": key(1)})
        with self.app.db:
            self.app.db.execute("UPDATE peers SET state='pending' WHERE public_key=?", (key(1),))
        self.restart()
        self.assertEqual("active", self.app.rows()[0]["state"])
        self.assertEqual((profile, False), self.app.enroll({"public_key": key(1)}))

    def test_failed_revoke_is_durable_and_never_resurrected_on_restart(self):
        self.app.enroll({"public_key": key(1)})
        self.driver.fail_remove = True
        with self.assertRaises(DriverError):
            self.app.revoke(key(1))
        self.assertEqual("deleting", self.app.rows()[0]["state"])
        self.assert_api_error(409, lambda: self.app.enroll({"public_key": key(1)}))
        self.driver.fail_remove = False
        self.restart()
        self.assertNotIn(key(1), self.driver.live)
        self.assertEqual([], self.app.rows())
        # Repeated DELETEs do not touch unmanaged peers.
        self.driver.live[key(8)] = [ipaddress.ip_network("192.168.4.8/32")]
        self.app.revoke(key(8))
        self.assertIn(key(8), self.driver.live)

    def test_conflict_during_reconciliation_fails_before_overwriting_live_peer(self):
        self.app.enroll({"public_key": key(1)})
        self.app.close()
        self.driver.live[key(8)] = [ipaddress.ip_network("10.8.0.2/32")]
        with self.assertRaises(ApiError):
            Provisioner(self.settings, self.driver)
        self.driver.live.pop(key(8))
        self.app = Provisioner(self.settings, self.driver)  # Failed startup released its DB lock.

    def test_database_cannot_be_attached_to_other_interface_pool_or_server_key(self):
        self.app.close()
        for settings in [replace(self.settings, interface="wg9"),
                         replace(self.settings, network=ipaddress.IPv4Network("10.9.0.0/24"))]:
            with self.assertRaises(ValueError):
                Provisioner(settings, self.driver)
        self.driver.identity = key(201)
        with self.assertRaises(ValueError):
            Provisioner(self.settings, self.driver)
        self.driver.identity = key(200)
        self.app = Provisioner(self.settings, self.driver)

    def test_only_one_process_may_own_the_database(self):
        with self.assertRaises(ValueError):
            Provisioner(self.settings, self.driver)
        self.restart()


class HttpTest(ProvisionFixture, unittest.TestCase):
    def setUp(self):
        super().setUp()
        self.server = ApiServer(("127.0.0.1", 0), handler_for(self.app))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
        super().tearDown()

    def call(self, method, path="/v1/peers", body=None, token=None, headers=None):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=3)
        request_headers = {"Content-Type": "application/json"}
        if token is not None:
            request_headers["Authorization"] = "Bearer " + token
        request_headers.update(headers or {})
        try:
            connection.request(method, path, body=body if isinstance(body, str) else json.dumps(body), headers=request_headers)
            reply = connection.getresponse()
            return reply.status, json.loads(reply.read()), dict(reply.getheaders())
        finally:
            connection.close()

    def test_http_enrollment_admin_separation_and_idempotent_revocation(self):
        enrollment, admin = self.settings.enrollment_token, self.settings.admin_token
        status, body, headers = self.call("GET", "/healthz")
        self.assertEqual((200, {"status": "ok"}), (status, body))
        self.assertEqual("no-store", headers["Cache-Control"])
        for token in [None, "wrong", admin]:
            self.assertEqual(401, self.call("POST", body={"public_key": key(1)}, token=token)[0])
        self.assertEqual([], self.app.rows())
        status, profile, _ = self.call("POST", body={"public_key": key(1), "name": "Phone"}, token=enrollment)
        self.assertEqual(201, status)
        self.assertEqual(key(1), profile["public_key"])
        self.assertEqual(200, self.call("POST", body={"public_key": key(1)}, token=enrollment)[0])
        self.assertEqual(401, self.call("GET", token=enrollment)[0])
        self.assertEqual(1, len(self.call("GET", token=admin)[1]["peers"]))
        path = "/v1/peers/" + quote(key(1), safe="")
        self.assertEqual(401, self.call("DELETE", path, token=enrollment)[0])
        self.assertEqual(200, self.call("DELETE", path, token=admin)[0])
        self.assertEqual(200, self.call("DELETE", path, token=admin)[0])
        self.assertEqual([], self.app.rows())

    def test_http_rejects_large_malformed_and_unexpected_requests(self):
        token = self.settings.enrollment_token
        cases = [("not json", {}, 400), ("x" * 4097, {}, 413),
                 ({"public_key": key(1)}, {"Content-Type": "text/plain"}, 415),
                 ({"public_key": key(1), "private_key": "SECRET"}, {}, 400)]
        for body, headers, status in cases:
            self.assertEqual(status, self.call("POST", body=body, headers=headers, token=token)[0])
        self.assertEqual(404, self.call("POST", "/unknown", token=token)[0])
        self.assertEqual([], self.app.rows())

    def test_http_driver_failure_is_retryable_without_losing_public_key(self):
        self.driver.fail_add = True
        status, body, _ = self.call("POST", body={"public_key": key(1)}, token=self.settings.enrollment_token)
        self.assertEqual(503, status)
        self.assertNotIn("fixture", str(body))
        self.driver.fail_add = False
        status, body, _ = self.call("POST", body={"public_key": key(1)}, token=self.settings.enrollment_token)
        self.assertEqual(200, status)
        self.assertEqual(["10.8.0.2/32"], body["addresses"])

    def test_readiness_and_enrollment_fail_when_live_server_key_changes(self):
        self.driver.identity = key(201)
        self.assertEqual(503, self.call("GET", "/healthz")[0])
        self.assertEqual(503, self.call("POST", body={"public_key": key(1)}, token=self.settings.enrollment_token)[0])
        self.assertEqual([], self.app.rows())
        self.assertEqual([], self.driver.added)

    def test_http_contract_matches_the_android_shared_fixture(self):
        fixture = json.loads((Path(__file__).parent / "fixtures" / "enrollment.json").read_text())
        self.app.server_public_key = fixture["server_public_key"]
        self.driver.identity = fixture["server_public_key"]
        self.app.settings = replace(self.settings, dns=tuple(fixture["dns"]))
        status, actual, _ = self.call("POST", body={"public_key": fixture["public_key"], "name": "WhiteGame"},
                                     token=self.settings.enrollment_token)
        self.assertEqual(201, status)
        self.assertEqual(fixture, actual)


class WireGuardCommandTest(unittest.TestCase):
    def test_real_subprocess_adapter_reads_only_public_state_and_sets_exact_peer(self):
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory) / "fake_wg.py"
            state = Path(directory) / "state.json"
            script.write_text("""import json,sys
from pathlib import Path
state=Path(sys.argv[1]); args=sys.argv[2:]
data=json.loads(state.read_text()) if state.exists() else {'peers':{},'commands':[]}
data['commands'].append(args)
if args == ['show','wg0','public-key']: print('""" + key(200) + """')
elif args == ['show','wg0','allowed-ips']:
    for key,ip in data['peers'].items(): print(key+'\\t'+ip)
elif len(args)==6 and args[:3]==['set','wg0','peer'] and args[4]=='allowed-ips': data['peers'][args[3]]=args[5]
elif len(args)==5 and args[:3]==['set','wg0','peer'] and args[4]=='remove': data['peers'].pop(args[3],None)
else: sys.exit(8)
state.write_text(json.dumps(data))
""")
            settings = Settings("wg0", "host.example:51820", ipaddress.IPv4Network("10.8.0.0/24"),
                                ("1.1.1.1",), ("0.0.0.0/0",), "e" * 32, "a" * 32, Path(directory) / "db")
            def runner(argv, **kwargs):
                self.assertEqual("/usr/bin/wg", argv[0])
                self.assertNotIn("shell", kwargs)
                self.assertEqual(subprocess.DEVNULL, kwargs["stdin"])
                return subprocess.run([sys.executable, str(script), str(state), *argv[1:]], **kwargs)
            driver = WireGuard(settings, runner)
            self.assertEqual(key(200), driver.server_key())
            self.assertEqual({}, driver.peers())
            driver.add(key(1), "10.8.0.2/32")
            self.assertEqual({key(1): [ipaddress.ip_network("10.8.0.2/32")]}, driver.peers())
            driver.remove(key(1))
            self.assertEqual({}, driver.peers())
            self.assertNotIn("dump", state.read_text())
            self.assertNotIn("private-key", state.read_text())

    def test_command_failures_and_timeouts_never_expose_stderr_or_subprocess_arguments(self):
        settings = Settings("wg0", "host.example:51820", ipaddress.IPv4Network("10.8.0.0/24"),
                            ("1.1.1.1",), ("0.0.0.0/0",), "e" * 32, "a" * 32, Path("unused"))
        for runner in [lambda *args, **kwargs: subprocess.CompletedProcess([], 1, "", "SECRET"),
                       lambda *args, **kwargs: (_ for _ in ()).throw(subprocess.TimeoutExpired(["SECRET"], 8))]:
            with self.assertRaises(DriverError) as caught:
                WireGuard(settings, runner).add(key(1), "10.8.0.2/32")
            self.assertNotIn("SECRET", str(caught.exception))

    def test_malformed_live_state_is_rejected(self):
        settings = Settings("wg0", "host.example:51820", ipaddress.IPv4Network("10.8.0.0/24"),
                            ("1.1.1.1",), ("0.0.0.0/0",), "e" * 32, "a" * 32, Path("unused"))
        for text in ["bad", key(1) + "\tgarbage"]:
            runner = lambda *args, **kwargs: subprocess.CompletedProcess([], 0, text, "")
            with self.assertRaises(DriverError):
                WireGuard(settings, runner).peers()


class SettingsTest(unittest.TestCase):
    def test_environment_rejects_unsafe_or_incomplete_configuration(self):
        env = dict(WG_ENDPOINT="vpn.example:51820", WG_ENROLLMENT_TOKEN="e" * 32, WG_ADMIN_TOKEN="a" * 32)
        self.assertEqual(ipaddress.IPv4Network("10.8.0.0/24"), Settings.from_env(env).network)
        for overrides in [dict(WG_INTERFACE="wg0;echo"), dict(WG_ENDPOINT="host:0"),
                          dict(WG_ENDPOINT="user:pass@host:51820"), dict(WG_ENDPOINT="host:51820/path"),
                          dict(WG_ENROLLMENT_TOKEN="short"), dict(WG_ADMIN_TOKEN="e" * 32),
                          dict(WG_ENROLLMENT_TOKEN="e" * 32 + "\0"), dict(WG_ENROLLMENT_TOKEN="e" * 32 + "\x7f"),
                          dict(WG_NETWORK="10.0.0.0/8"), dict(WG_DNS="localhost"),
                          dict(WG_ALLOWED_IPS="::/0"), dict(WG_MTU="10")]:
            with self.subTest(overrides=overrides), self.assertRaises(ValueError):
                Settings.from_env(env | overrides)

    def test_public_keys_are_canonical_and_nonzero(self):
        for value in [None, "", key(0), key(1).rstrip("="), key(1) + "\n", "!" * 44]:
            with self.assertRaises(ApiError):
                public_key(value)
        self.assertEqual(key(1), public_key(key(1)))


if __name__ == "__main__":
    unittest.main()
