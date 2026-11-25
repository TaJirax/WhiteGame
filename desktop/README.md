# WhiteGame for Windows

WhiteGame for Windows targets x64 editions of Windows 10 and 11. Extract
`WhiteGame-Windows-x64-7.3.0.zip` and open `WhiteGame.exe` from the extracted
folder. The package includes Java, Xray 26.11.9, and Wintun. Keep the folders
alongside the executable. [Xray release](https://github.com/XTLS/Xray-core/releases/tag/v26.11.9)

## Tunnel engines

| Profile | Engine |
| --- | --- |
| Xray and WARP with Reserved bytes | Bundled Xray and Wintun |
| Standard WireGuard | Installed [WireGuard for Windows](https://www.wireguard.com/install/) |
| AmneziaWG | Installed standalone [AmneziaWG Windows client](https://github.com/amnezia-vpn/amneziawg-windows-client/releases) |

**Device** shows the detected engines. For a nonstandard installation, set
`WHITEGAME_WIREGUARD`, `WHITEGAME_AMNEZIAWG`, or `WHITEGAME_XRAY` to the
corresponding executable's full path and restart WhiteGame. An alternate Xray
installation needs `wintun.dll` next to `xray.exe` for device-wide routing.

Windows asks for administrator approval when WhiteGame starts a tunnel or
applies system DNS. The main window runs with normal user permissions. Profiles
and reports are saved under `%LOCALAPPDATA%\WhiteGame`.

## Using the app

- **Connect** selects a saved profile, starts or stops the tunnel, and shows its
  status and available session statistics. Windows supports device-wide
  tunneling and IP-based WireGuard split routes. Android package-based routing
  rules are Android-only.
- **Tunnels** imports config files or pasted links, manages subscriptions,
  exports WireGuard-format profiles as files or QR codes, and offers WARP and
  self-hosted enrollment. WireGuard endpoint scans use the selected profile and
  the endpoints you enter. [Self-hosted WireGuard API](../server/README.md)
- **Network → Configs** measures HTTPS ping, jitter, download, and upload through
  supported Xray and standard WireGuard or WARP profiles. Disconnect an active
  WhiteGame tunnel first. WireGuard needs an internet route in `AllowedIPs`.
  For AmneziaWG, connect a profile with an internet route and measure it from
  **Network → Speed**.
- **Network → DNS** compares resolver latency, jitter, and failed queries, and
  can apply or restore a resolver. **Network → Games** matches the catalog
  against Steam and Epic installations and installed app records. Its route
  comparisons measure publisher HTTPS endpoints. **Network → Speed** measures
  the current connection using Cloudflare. [Cloudflare Speed Test](https://speed.cloudflare.com/)
- **Device** also shows Windows and network-adapter information and opens saved
  measurement reports.

## Build from source

On x64 Windows with JDK 17, run these commands from the repository root.

```powershell
.\gradlew.bat -p desktop test
.\desktop\package.ps1
```

The packaging script checks the Xray archive's SHA-256 digest and writes the
portable ZIP to `dist\WhiteGame-Windows-x64-7.3.0.zip`. The release workflow
publishes the ZIP after running VPN and DNS tests on a disposable Windows
runner.
