# WhiteGame

An Android app for finding and using a faster network path to online games:
it measures routes and DNS resolvers from the phone, detects which games are
installed, and carries traffic over a WireGuard / AmneziaWG or Xray tunnel.

Kotlin, Jetpack Compose, Hilt. `minSdk 23` (Android 6.0), `targetSdk 34`.

## What it does

- **Measure** — latency, jitter and loss to game endpoints and to a list of
  public DNS resolvers, so a resolver or exit can be chosen on evidence rather
  than by reputation.
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

## Building

```bash
./gradlew assembleRelease          # universal APK, all four ABIs
./gradlew assembleRelease -Pabi=armeabi-v7a   # one ABI, roughly half the size
./gradlew testDebugUnitTest        # unit tests
```

`-Pabi` accepts a comma-separated list. Per-ABI builds use APK splits rather
than `abiFilters`, because AGP does not re-merge native libraries when a filter
changes and a filtered build can otherwise ship the previous run's ABIs.

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

## Third-party code

This app bundles prebuilt binaries from AmneziaWG/WireGuard and Xray. Their
licenses and attribution are in [NOTICE.md](NOTICE.md). "WireGuard" is a
registered trademark of Jason A. Donenfeld; this app is not sponsored or
endorsed by WireGuard LLC or AmneziaVPN.
