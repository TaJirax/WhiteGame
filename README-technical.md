# WhiteGame technical documentation

[Back to WhiteGame](README.md)

## Platforms

| Platform | Application and network engines |
| --- | --- |
| Android | Kotlin, Jetpack Compose, and Hilt. Android 6.0 and later (`minSdk 23`, `targetSdk 34`). The bundled `libwg-go.so` runs WireGuard and AmneziaWG. Xray uses AndroidLibXrayLite in a separate `:xray` process and requires Android 7.0 or later. |
| Windows x64 | The Kotlin/Swing app bundles Java, Xray, and Wintun. Device-wide WireGuard and AmneziaWG use their installed Windows clients. |
| Linux x64 | The Kotlin/Swing app bundles Java and Xray for device-wide TUN and isolated tests. WireGuard and AmneziaWG use the installed `wg-quick` and `awg-quick` tools. System DNS changes use `systemd-resolved`. |

The desktop and Android apps share profile parsing, config export, and network
measurement code. Desktop engine installation, privilege prompts, profile
storage, and build commands are covered in the desktop guide.
[Desktop setup and build guide](desktop/README.md)

## Build and test

Use JDK 17. The Android build also requires Android SDK 34. Run these commands
from the repository root.

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
./gradlew assembleRelease
./gradlew assembleRelease -Pabi=armeabi-v7a,arm64-v8a
```

The default release builds one APK for ARM and x86, with both 32-bit and 64-bit
ABIs. `-Pabi` accepts a comma-separated list and produces a split APK for each
requested ABI. The split configuration keeps incremental builds from reusing
native libraries from a previous ABI selection. Use `gradlew.bat` on Windows.

Debug builds can use the local Android debug key. A release build requires the
release key to produce a signed APK that can update an existing WhiteGame
installation. [Android signing setup](keystore/README.md)

### Desktop

On Windows x64, build and test using PowerShell.

```powershell
.\gradlew.bat -p desktop test
.\desktop\package.ps1
```

On Linux x64, run the packaging script. It runs the desktop tests and packages
the app.

```bash
bash desktop/package-linux.sh
```

Both packaging scripts verify the downloaded Xray archive's SHA-256 digest.
The resulting archives are under `dist/`. The provisioning server has its own
Python regression suite and an isolated WireGuard integration test.
[Server validation](server/README.md#validation)

## Release pipeline

1. Align Android's `versionName` and the desktop version. Increment Android's
   `versionCode` for the update. [Android settings](app/build.gradle.kts)
   · [Desktop settings](desktop/build.gradle.kts)
2. Push a `verify/` branch and wait for the Android and desktop jobs to pass.
3. Tag the verified commit with `v` followed by the version and push the tag.
   GitHub Actions builds the release and checks each APK signing certificate
   against the pinned SHA-256 digest. It publishes the universal Android APK,
   both ARM APKs, the Windows x64 ZIP, and the Linux x64 tarball.
   [Release workflow](.github/workflows/ci.yml)

CI runs Android tests and lint, desktop regression tests, and native tests for
DNS, authenticated WireGuard traffic, and Xray TUN routing and cleanup. The
native checks cover Windows Server 2022 and Ubuntu 24.04 and 26.04. GitHub-hosted
runners isolate the network changes from developer machines. [CI results](https://github.com/TaJirax/WhiteGame/actions)

## Networking and measurements

- **Speed and config tests.** The app measures download, upload, latency, and
  jitter using Cloudflare's public speed-test endpoints. Isolated config speed
  tests use Xray for Xray profiles and for an authenticated userspace WireGuard
  tunnel. WireGuard needs an internet route in `AllowedIPs` and an endpoint for
  every peer. Disconnect the active WhiteGame tunnel before running isolated
  config tests. To measure AmneziaWG, connect the profile and open
  **Network → Speed**. [Cloudflare Speed Test](https://speed.cloudflare.com/)
  · [Xray WireGuard outbound](https://xtls.github.io/en/config/outbounds/wireguard.html)
- **Game and DNS tests.** Android checks installed packages against built-in
  game catalogs and can enrich matches with Google Play metadata. Desktop
  scans installed Steam titles and, on Windows,
  Epic Games titles and registered apps that match the catalog. Game
  comparisons measure HTTPS connection setup and use one reachable catalog
  host for all routes. DNS rankings send real DNS queries and count invalid or
  missing responses as failures. Android's DNS-only mode uses a local VPN to
  route DNS. Desktop changes and restores the system resolver.
  [Bundled game catalogs](app/src/main/assets)
- **Endpoint scans.** Select a standard single-peer full-tunnel WireGuard
  profile. Enter one `host:port` or `[IPv6]:port` per line. Each probe completes
  HTTPS over authenticated WireGuard using Xray. **Use best** saves the
  selected endpoint for the next connection. The measured time includes
  handshake and HTTPS connection setup. On Android the test requires
  Android 7.0 or later.
- **Profiles and export.** Android's Xray engine handles VLESS, VMess, Trojan,
  and Shadowsocks links. WhiteGame imports `wireguard://` and `wg://` links
  with dual-stack addresses, WARP Reserved bytes, and supported AmneziaWG
  settings, including CPA and I1–I5 packet expressions. The bundled Android
  AmneziaWG engine supports 3.1 header and keepalive settings. Export a saved
  WireGuard, AmneziaWG, or WARP profile as a `.conf` file or QR code.
- **Cloudflare WARP.** **Tunnels → WARP setup / endpoint scan** opens the terms,
  generates keys locally, and registers the public key. The app can test
  endpoints returned by registration on Android 7.0 and later and save the
  fastest endpoint that completes a probe. WhiteGame reuses an existing
  generated profile. Manual profile import is available if registration
  fails. On Android, a protected UDP adapter adds WARP's three Reserved
  bytes. Reserved requires standard WireGuard message headers and zero
  handshake padding. AmneziaWG signature and junk packets pass through
  the adapter unchanged. [WARP registration reference](https://github.com/ViRb3/wgcf/blob/master/cloudflare/api.go)
- **Self-hosted WireGuard.** The app generates and retains its private key,
  then registers its public key over HTTPS. The provisioning service assigns
  an address and registers the peer on your WireGuard interface. To use a
  manually generated key, register its public key on the server.
  [Server deployment and API guide](server/README.md)

## Source layout

| Path | Contents |
| --- | --- |
| [Android connection](app/src/main/java/com/whitegame/app/connection) | WireGuard and AmneziaWG lifecycle, DNS forwarding, provisioning, and profile import and export |
| [Android Xray](app/src/main/java/com/whitegame/app/xray) | Xray config building, VPN service, and probes |
| [Network measurement](app/src/main/java/com/whitegame/app/network) | Route and DNS probes, Cloudflare speed tests, and region analysis |
| [Catalogs and subscriptions](app/src/main/java/com/whitegame/app/data) | Bundled and remote catalogs, subscriptions, and Play Store metadata |
| [Android UI](app/src/main/java/com/whitegame/app/ui) | Compose screens, components, and theme |
| [JNI shim](app/src/main/java/org/amnezia/awg) | The native engine uses this package and class name for its JNI symbols. |
| [Android native libraries](app/src/main/jniLibs) · [Xray AAR](app/libs/libv2ray.aar) | Prebuilt WireGuard and AmneziaWG libraries and AndroidLibXrayLite |
| [Desktop](desktop) | Kotlin/Swing app, system network integration, desktop tests, and packaging scripts |
| [Provisioning server](server) | WireGuard enrollment API, tests, and Linux deployment configuration |

## Third-party code

See the repository's third-party notice for the bundled Android WireGuard and
AmneziaWG libraries. Desktop packages include the upstream Xray license, and the
Windows package includes the Wintun license. WireGuard is a registered
trademark of Jason A. Donenfeld. [Third-party notices](NOTICE.md)
· [Desktop Xray release](https://github.com/XTLS/Xray-core/releases/tag/v26.11.9)
