# Self-hosted WireGuard provisioning

This service registers WhiteGame phones on a WireGuard interface you operate.
The phone creates its own private key and sends only its public key over HTTPS.
The service allocates an IPv4 address, applies the peer with `wg set`, and returns
the server public key, endpoint, routes, and DNS settings. Client private keys
never pass through or reside on this server.

The server is Python standard-library code; it requires Python 3.10+ and
`wireguard-tools`. It manages peers on an **existing** Linux WireGuard interface.
It does not install a tunnel, configure forwarding, change the firewall, provide
a DNS resolver, or provision Cloudflare WARP accounts. Those are separate from
the phone's WARP setup option.

The instructions below use Debian/Ubuntu, `wg0`, `10.8.0.0/24`, a WireGuard
endpoint `vpn.example.com:51820`, and an API at `https://provision.example.com`.
Replace these values for your network. Keep this pool exclusive to this service.

## 1. Prepare the WireGuard host

Run from a checkout of this repository on the Linux host:

```sh
sudo apt-get update
sudo apt-get install python3 wireguard-tools nginx
sudo install -d -o root -g root -m 0700 /etc/wireguard
```

For a new interface, the following generates the server key directly into a
root-only config without printing it or leaving an intermediate key file. It
refuses to overwrite an existing `wg0.conf`:

```sh
sudo python3 - <<'PY'
import os
import subprocess
from pathlib import Path

os.umask(0o077)
key = subprocess.run(["wg", "genkey"], check=True, text=True, capture_output=True).stdout.strip()
with Path("/etc/wireguard/wg0.conf").open("x") as config:
    config.write("[Interface]\nAddress = 10.8.0.1/24\nListenPort = 51820\n")
    config.write("PrivateKey = " + key + "\nSaveConfig = false\n")
PY
sudo systemctl enable --now wg-quick@wg0
sudo wg show wg0 public-key
```

The first usable pool address, `10.8.0.1`, is reserved for the server. The pool
must not overlap a LAN or another VPN network. If the interface already exists,
configure its address and port yourself; do not run the generation snippet.
Read public state with `wg show wg0 public-key` and `wg show wg0 allowed-ips`.
`wg show ... dump` also contains private keys and is unnecessary here.

### Routes and firewall

Allow the configured UDP port through the host firewall and any upstream router
or cloud firewall; forward it to this host when the host is behind NAT. Allow
TCP 443 to nginx. The API binds to loopback port 8080, which should stay private.

Choose one routing policy before enrolling phones:

| Use | Provisioned `WG_ALLOWED_IPS` | Server configuration |
|---|---|---|
| Reach only this WireGuard host | `10.8.0.1/32` | No forwarding or NAT needed |
| Reach a private LAN | `10.8.0.0/24,192.168.50.0/24` | IPv4 forwarding, forwarding firewall rules, and a LAN return route to `10.8.0.0/24` through this host (or explicit LAN NAT) |
| Route IPv4 Internet traffic | `0.0.0.0/0` | IPv4 forwarding, forwarding firewall rules, and upstream routing or source NAT |

For forwarding, install the provided sysctl file:

```sh
sudo install -o root -g root -m 0644 server/deploy/90-whitegame-forwarding.conf /etc/sysctl.d/90-whitegame-forwarding.conf
sudo sysctl --system
```

For an IPv4 Internet exit, these are example **iptables** rules to integrate
with your existing firewall. First identify and replace the egress interface;
the service does not guess it. Use your firewall's equivalent persistent rules
if it is managed by nftables, firewalld, or another manager.

```sh
sudo apt-get install iptables
ip -4 route get 1.1.1.1
WG_EGRESS_IF=ens3
sudo iptables -A FORWARD -i wg0 -o "$WG_EGRESS_IF" -s 10.8.0.0/24 -j ACCEPT
sudo iptables -A FORWARD -i "$WG_EGRESS_IF" -o wg0 -d 10.8.0.0/24 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
sudo iptables -t nat -A POSTROUTING -s 10.8.0.0/24 -o "$WG_EGRESS_IF" -j MASQUERADE
```

An earlier DROP rule can still block these forwarding rules. Save them using
the host's firewall manager so they survive reboot. Do not flush an existing
firewall. These examples do not set up IPv6: the allocated tunnel address is
IPv4, and the default client routes cover IPv4 only.

## 2. Install the API service

```sh
sudo useradd --system --user-group --home-dir /nonexistent --shell /usr/sbin/nologin whitegame-provision
sudo install -d -o root -g root -m 0755 /opt/whitegame/server
sudo install -o root -g root -m 0644 server/provision.py /opt/whitegame/server/provision.py
sudo install -o root -g root -m 0600 server/deploy/provision.env.example /etc/whitegame-provision.env
sudo install -o root -g root -m 0644 server/deploy/whitegame-provision.service /etc/systemd/system/whitegame-provision.service
```

Edit `/etc/whitegame-provision.env` as root. Set `WG_ENDPOINT` to the UDP endpoint
reachable by phones, and make `WG_INTERFACE`, `WG_NETWORK`, `WG_DNS`, and
`WG_ALLOWED_IPS` match the routing policy above. DNS entries must be IP addresses
of resolvers the phone can reach under that policy. An address such as
`10.8.0.1` is a resolver only if you actually run a resolver there.

Generate two different tokens once; this writes them into the file without
printing them. Repeating this step rotates both tokens and invalidates existing
API credentials, so use it only for initial setup or deliberate rotation:

```sh
sudo python3 - <<'PY'
import os
import re
import secrets
from pathlib import Path

path = Path("/etc/whitegame-provision.env")
text = path.read_text()
for name in ("WG_ENROLLMENT_TOKEN", "WG_ADMIN_TOKEN"):
    text = re.sub(r"(?m)^" + name + r"=.*$", name + "=" + secrets.token_urlsafe(32), text)
path.write_text(text)
os.chmod(path, 0o600)
PY
sudo systemctl daemon-reload
sudo systemctl enable --now whitegame-provision
sudo systemctl status whitegame-provision
curl --fail http://127.0.0.1:8080/healthz
```

The supplied unit runs under its own user with `CAP_NET_ADMIN`, which is needed
to configure WireGuard peers through netlink. Persistent writes are limited to
its state directory under `/var/lib/whitegame-provision`; the program and environment file
are root-owned. The unit depends on `wg-quick@wg0`; update the dependency lines
as well as `WG_INTERFACE` if you use a different interface. Keep one service
instance and one SQLite database per managed interface.

## 3. Put HTTPS in front of the API

Obtain a valid certificate for `provision.example.com`, then edit
`server/deploy/nginx.conf.example` with your host name and certificate paths.
The example belongs inside nginx's `http` context; on Debian/Ubuntu:

```sh
sudo install -o root -g root -m 0644 server/deploy/nginx.conf.example /etc/nginx/sites-available/whitegame-provision
sudo ln -s /etc/nginx/sites-available/whitegame-provision /etc/nginx/sites-enabled/whitegame-provision
sudo nginx -t
sudo systemctl reload nginx
curl --fail https://provision.example.com/healthz
```

The example limits requests and body size and does not write API access logs.
It forwards the bearer header to the loopback API. There is no HTTP redirect
listener: use the HTTPS URL directly, so credentials are never sent to an HTTP
endpoint first.

For an internal-only deployment, use internal/split-horizon DNS to resolve this
name to the host's private address, and a certificate trusted by the app's
Android system trust store. A public-CA certificate for a domain you control
can still terminate on an internal host. Installing a user CA on Android alone
does not make it trusted by this app. The client verifies certificates and host
names; it has no insecure certificate bypass.

## 4. Enroll from WhiteGame

In **Tunnels → Self-hosted WireGuard setup**, enter the **Server HTTPS URL**
`https://provision.example.com` and the **Enrollment token**, then tap
**Create / refresh profile**. Use the enrollment token only on phones you authorize to
add peers. It is a shared enrollment credential, not a per-user quota system.
Keep the separate **admin** token on the server/operator side.

The app generates and retains a device key for that server before requesting
registration, validates the returned profile, and saves it alongside other
tunnels. The enrollment token is not saved. Retrying the same public key returns the
same assigned address instead of creating another peer. Connect that profile
to use it. The full-tunnel endpoint scanner can test a standard single-peer
`0.0.0.0/0` profile; LAN-only profiles are tested by connecting them.

## Configuration and API

| Variable | Meaning / default |
|---|---|
| `WG_INTERFACE` | Existing Linux interface, `wg0` |
| `WG_ENDPOINT` | Required client-reachable `host:port`; use `[IPv6]:port` for an IPv6 outer endpoint |
| `WG_NETWORK` | IPv4 allocation pool, `10.8.0.0/24`; first usable address reserved for server |
| `WG_DNS` | Comma-separated IPv4 resolver addresses, `1.1.1.1,1.0.0.1` |
| `WG_ALLOWED_IPS` | IPv4 client CIDRs, `0.0.0.0/0` |
| `WG_MTU` | Client MTU, `1280` |
| `WG_KEEPALIVE` | Client persistent keepalive seconds, `25` |
| `WG_ENROLLMENT_TOKEN` | Required enrollment bearer token, at least 32 characters |
| `WG_ADMIN_TOKEN` | Required, different admin bearer token, at least 32 characters |
| `WG_DATABASE` | SQLite file, `/var/lib/whitegame-provision/peers.sqlite3` |
| `WG_BIN` | WireGuard tool, `/usr/bin/wg` |
| `WG_BIND_HOST`, `WG_BIND_PORT` | Loopback HTTP bind, `127.0.0.1:8080` |

Requests and responses are JSON; enrollments have a maximum body size of 4096
bytes. Every API response is marked non-cacheable. The service does not accept
client-specified addresses or routes.

| Method / path | Authorization | Behavior |
|---|---|---|
| `GET /healthz` | None | Service health; contains no credentials |
| `POST /v1/peers` | Enrollment bearer | Accept `{"public_key":"<base64 key>","name":"My phone"}`; return 201 for new or 200 for repeated registration |
| `GET /v1/peers` | Admin bearer | List managed peers |
| `DELETE /v1/peers/{encoded-public-key}` | Admin bearer | Revoke a managed peer |

Enrollment response shape (keys below are descriptive placeholders):

```json
{
  "public_key": "<the phone's public key>",
  "server_public_key": "<the real wg0 public key>",
  "addresses": ["10.8.0.2/32"],
  "dns": ["1.1.1.1", "1.0.0.1"],
  "endpoint": "vpn.example.com:51820",
  "allowed_ips": ["0.0.0.0/0"],
  "mtu": 1280,
  "persistent_keepalive": 25
}
```

For administration, the included helper prompts for the admin token without
putting it in shell history or process arguments. From a repository checkout,
list managed peers and then revoke one using its public key (replace
`PUBLIC_KEY_FROM_LIST` below). A successful revoke removes its server-side
tunnel access, including access from an already saved phone profile.

A holder of the shared enrollment token can register that public key again.
Revocation removes the peer; it is not a permanent identity ban. When removing
someone's enrollment access, rotate the enrollment token, restart the API, and
then revoke their peer. Existing peers keep working when only the enrollment
token changes.

```sh
python3 server/admin.py --server https://provision.example.com list
python3 server/admin.py --server https://provision.example.com revoke 'PUBLIC_KEY_FROM_LIST'
```

## Persistence, restart, and recovery

SQLite owns managed peers and their allocated addresses. New registrations are
recorded as pending before applying them to WireGuard; pending/active records
are reconciled at service startup. Revocations are recorded before peer removal
and are retried on startup if interrupted. The API does not report a successful
enrollment before the corresponding native `wg set` succeeds. Retrying after a
timeout uses the same phone public key and therefore the same allocation.

Keep `SaveConfig = false` in `wg0.conf` and keep service-managed `[Peer]` sections
out of that file. `wg set` updates the live interface; the service's database
restores those peers after restart. `SaveConfig = true` can copy live managed
peers back into the static file and later resurrect a revoked peer when
`wg-quick` starts. This service also refuses to adopt an unmanaged peer with the
same public key or allocate an address covered by an unmanaged peer.

When you recreate or restart the interface, restart the provisioning service
afterwards to reconcile its database. The supplied unit orders its startup after
`wg-quick@wg0`, and `PartOf` propagates service-manager restart operations:

```sh
sudo systemctl restart wg-quick@wg0
sudo systemctl restart whitegame-provision
sudo journalctl -u whitegame-provision -n 50 --no-pager
```

The database is bound to the interface name, server public key, and address pool.
Changing those values against an existing database is refused at startup. Plan
an explicit migration and re-enroll clients when replacing that server identity
or pool; do not delete the database as a routine way to fix startup errors.
Endpoint, DNS, and client-route settings can change with the same database.
After restarting the API with those changes, use **Create / refresh profile**
on each phone so its saved configuration receives them.

Preserve `/etc/wireguard/wg0.conf`, `/etc/whitegame-provision.env`, and the SQLite
database together as a protected backup. To copy a consistent database while
the service runs, use SQLite's backup API rather than copying an open database:

```sh
sudo install -d -o root -g root -m 0700 /var/backups/whitegame-provision
sudo install -o root -g root -m 0600 /etc/wireguard/wg0.conf /var/backups/whitegame-provision/wg0.conf
sudo install -o root -g root -m 0600 /etc/whitegame-provision.env /var/backups/whitegame-provision/provision.env
sudo python3 - <<'PY'
import os
import sqlite3

os.umask(0o077)
with sqlite3.connect("file:/var/lib/whitegame-provision/peers.sqlite3?mode=ro", uri=True) as source:
    with sqlite3.connect("/var/backups/whitegame-provision/peers.sqlite3") as target:
        source.backup(target)
PY
```

Stop the API before restoring a database. Restore ownership to
`whitegame-provision:whitegame-provision` and mode `0600`, then start the API
after `wg0`. Restoring an older database also restores its older peer/revocation
state; review and revoke stale peers before exposing that interface again.

## Validation

Run the server regression suite from the repository root:

```sh
python3 -m unittest discover -s server/tests -v
```

The default suite uses isolated databases and a fake WireGuard command; the
real-kernel test is skipped. Admin-helper tests cover URL/token validation,
request encoding, token prompting, and refusal to forward redirects.

An additional opt-in Linux test creates a second network namespace inside a
fresh test namespace. It enrolls through the real HTTP API, configures actual
WireGuard peers, sends encrypted ping traffic, verifies restart recovery and
revocation, and cleans up its temporary interfaces. Install `wireguard-tools`,
`iproute2`, `iputils-ping`, and `util-linux`, then from the repository root run:

```sh
export WHITEGAME_PARENT_NETNS=$(readlink /proc/self/ns/net)
sudo --preserve-env=WHITEGAME_PARENT_NETNS unshare -n -- env WHITEGAME_LIVE_WG=1 \
  python3 -m unittest discover -s server/tests -p test_live_wireguard.py -v
```

This test needs Linux network-namespace capabilities. It refuses to run in the
parent namespace or one containing existing interfaces. CI runs both the
cross-platform default suite and this isolated integration test.

Production checks still require your own WireGuard host: verify startup and
restart reconciliation, enroll a test phone, inspect
`wg show wg0 latest-handshakes`, confirm the selected routes and DNS, then revoke
the peer and confirm it loses access. A successful API response proves peer
registration, not that upstream UDP, forwarding, or the game's servers are
reachable.

Reference behavior: [WireGuard quick start](https://www.wireguard.com/quickstart/),
[wg-quick configuration](https://git.zx2c4.com/wireguard-tools/about/src/man/wg-quick.8),
[systemd execution and capabilities](https://www.freedesktop.org/software/systemd/man/latest/systemd.exec.html),
[nginx HTTPS configuration](https://nginx.org/en/docs/http/configuring_https_servers.html).
