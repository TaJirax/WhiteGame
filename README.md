# WhiteGame

An Android app for finding and using a faster network path to online games:
it measures routes and DNS resolvers from the phone, detects which games are
installed, and carries traffic over a WireGuard / AmneziaWG or Xray tunnel.

Kotlin, Jetpack Compose, Hilt. `minSdk 23` (Android 6.0), `targetSdk 34`.

The Windows and Linux x64 editions share the profile parsers and network
measurements with Android. [Desktop setup and build guide](desktop/README.md)

## What it does

- **Measure** — latency, jitter and loss to game endpoints and to a list of
  public DNS resolvers, so a resolver or exit can be chosen on evidence rather
  than by reputation.
- **Cloudflare Speed Test** — open **Network → Speed** to measure download,
  upload, latency and jitter over the route available to WhiteGame.
- **Config tests** — open **Network → Configs**, paste profiles or load a saved
  one, then test HTTPS ping, download and upload through each Xray or standard
  WireGuard profile. WireGuard needs a public default route. Disconnect any
  active WhiteGame tunnel before running these tests.
- **Detect installed games** — matches packages on the device against a bundled
  catalog (`app/src/main/assets/games*.json`) and enriches them from the Play
  Store listing.
- **Tunnel** — three backends behind one control surface:
  - **WireGuard** via the bundled `libwg-go.so`
  - **AmneziaWG** (the obfuscated WireGuard fork), including 3.1 header and
    keepalive ranges
  - **Xray** (VLESS/VMess/Trojan/Shadowsocks share links) via
    AndroidLibXrayLite, run in a separate `:xray` process
- **DNS-only mode** — a VPN that routes nothing but DNS, for when only
  resolution needs to change.
- **WARP setup** — in Tunnels, open **WARP setup / endpoint scan**, review
  Cloudflare's terms, and create a profile. Keys are generated on the phone;
  only the public key is registered. On Android 7+, setup can test the endpoints
  returned by registration and save the fastest one that completes a probe.
  An existing generated profile is reused.
  Registration uses Cloudflare's consumer API and can be unavailable or refused;
  importing a provider config remains available.
- **Self-hosted setup** — register with your own WireGuard provisioning server
  over HTTPS. The phone keeps its private key; the server allocates an address
  and adds its public key to your WireGuard interface. Linux service, TLS proxy,
  and routing instructions are in [server/README.md](server/README.md).
- **Config export** — expand a saved WireGuard, AmneziaWG or WARP profile and
  tap **Export config** to display its QR code or save a `.conf` file.
- **WireGuard endpoint scans** — select a standard, single-peer full-tunnel
  profile and enter endpoint addresses, one `host:port` per line (IPv6 uses
  `[address]:port`). Tests complete HTTPS through authenticated WireGuard using
  the bundled Xray core. Disconnect the active tunnel before scanning. **Use best** saves
  the endpoint for the next connection. These times include connection setup;
  they are not gameplay ping. Android 7+ is required for this test core.
- **Share-link imports** — `wireguard://` and `wg://` links preserve dual-stack
  addresses, WARP Reserved bytes, and supported AmneziaWG settings including
  CPA and I1–I5 packet expressions. Obfuscated profiles are tested by connecting
  them; standard WireGuard probes do not discard their obfuscation settings.

Game comparisons try alternate catalog HTTPS hosts when no route reaches the
first one, then keep every route on the same chosen host. DNS rankings send
actual DNS queries and count unsuccessful or invalid replies as failed probes.

WARP's Reserved extension is supplied by a protected UDP adapter around the
bundled AmneziaWG engine, which does not implement those bytes itself. Modified
WireGuard message headers or handshake padding cannot be combined with Reserved
in this adapter. Normal Amnezia signature/junk packets pass through unchanged.
The self-hosted API provisions peers on your own configured WireGuard interface;
an ordinary manually generated key still needs server-side registration.

Protocol references for maintenance: [Xray WireGuard outbound](https://xtls.github.io/en/config/outbounds/wireguard.html),
[wgcf consumer registration implementation](https://github.com/ViRb3/wgcf/blob/master/cloudflare/api.go).

## Building

```bash
./gradlew assembleRelease          # universal APK, all four ABIs
./gradlew assembleRelease -Pabi=armeabi-v7a   # one ABI, roughly half the size
./gradlew testDebugUnitTest        # unit tests
```

`-Pabi` accepts a comma-separated list. Per-ABI builds use APK splits rather
than `abiFilters`, because AGP does not re-merge native libraries when a filter
changes and a filtered build can otherwise ship the previous run's ABIs.

## Releasing

CI builds each release. Push a branch prefixed with `verify/` to run the
Android build and desktop integration tests before publishing. Once those
checks pass, tag the commit with the matching Android and desktop version and
push the tag.

```bash
git tag v7.3.0 && git push origin v7.3.0
```

The workflow checks DNS apply and restore and authenticated WireGuard traffic on
disposable Windows and Linux runners. It also checks Xray TUN routing and cleanup
on Windows. After verifying the Android signing certificate, it publishes the
tested Windows x64 ZIP and Linux x64 tarball alongside the Android APKs.
[Android signing secrets](keystore/README.md)

Release signing material is **not** in this repository — see
[keystore/README.md](keystore/README.md). Without it the project still compiles
and tests; debug builds fall back to the local Android debug key and
`assembleRelease` produces an unsigned APK.

## Layout

| Path | What lives there |
|---|---|
| `app/src/main/java/.../connection` | WireGuard/AmneziaWG tunnel lifecycle, VPN service, DNS forwarding |
| `app/src/main/java/.../xray` | Xray config building, VPN service, latency probes |
| `app/src/main/java/.../network` | Route probing, DNS measurement, region analysis |
| `app/src/main/java/.../data` | Catalogs, subscriptions, Play Store metadata, remote endpoints |
| `app/src/main/java/.../ui` | Compose screens, components, theme |
| `app/src/main/java/org/amnezia/awg` | JNI shim — package and class names are fixed by `libwg-go.so` |
| `app/src/main/jniLibs` | Prebuilt `libwg-go.so` per ABI |
| `app/libs/libv2ray.aar` | Prebuilt Xray core (AndroidLibXrayLite) |
| `server/` | Self-hosted WireGuard provisioning API, tests, and Linux deployment configuration |

## Third-party code

This app bundles prebuilt binaries from AmneziaWG/WireGuard and Xray. Their
licenses and attribution are in [NOTICE.md](NOTICE.md). "WireGuard" is a
registered trademark of Jason A. Donenfeld; this app is not sponsored or
endorsed by WireGuard LLC or AmneziaVPN.
