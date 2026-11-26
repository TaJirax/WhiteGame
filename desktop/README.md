# WhiteGame desktop

WhiteGame for Windows targets x64 editions of Windows 10 and 11. Extract
`WhiteGame-Windows-x64-7.3.1.zip` and open `WhiteGame.exe` from the extracted
folder. The package includes Java, Xray 26.11.9, and Wintun. Keep the folders
alongside the executable. [Xray release](https://github.com/XTLS/Xray-core/releases/tag/v26.11.9)

On x86-64 Linux, extract `WhiteGame-Linux-x64-7.3.1.tar.gz` and run
`WhiteGame/bin/WhiteGame`. The archive includes Java and the Linux Xray binary.
Settings and profiles are saved under `$XDG_DATA_HOME/whitegame`, with
`~/.local/share/whitegame` as the default.

## Windows tunnel engines

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

## Linux networking

- Cloudflare speed tests, DNS scans, game measurements, and isolated Xray,
  WireGuard, and WARP profile tests use the same measurement code as Windows.
- Connect standard WireGuard with `wg-quick`. AmneziaWG requires
  `awg-quick` and its kernel or userspace tunnel engine. WhiteGame applies
  tunnel DNS to the tunnel adapter through `systemd-resolved` when available.
  Systems using `resolvconf` use the installed tunnel tool's DNS integration.
  [WireGuard configuration reference](https://git.zx2c4.com/wireguard-tools/about/src/man/wg-quick.8)
- **Use DNS** temporarily changes the default-route adapter through
  `systemd-resolved`. **Restore system DNS** or closing WhiteGame restores
  the previous values if another program has left the override in place.
  Linux network changes require a PolicyKit authentication agent for desktop
  approval. [resolvectl documentation](https://www.freedesktop.org/software/systemd/man/latest/resolvectl.html)
- Device-wide Xray, WARP profiles with Reserved bytes, and WhiteGame's
  dedicated kill-switch control are available on Windows. Linux can import
  and test Xray and WARP profiles in **Network → Configs**.

## Using the app

- **Connect** selects a saved profile, starts or stops the tunnel, and shows its
  status and available session statistics. Windows supports device-wide
  tunneling and IP-based WireGuard split routes. Android package-based routing
  rules are Android-only.
- **Tunnels** imports config files or pasted links and edits saved profile values, manages subscriptions,
  exports WireGuard-format profiles as files or QR codes, and offers WARP and
  self-hosted enrollment. WireGuard endpoint scans use the selected profile and
  the endpoints you enter. [Self-hosted WireGuard API](../server/README.md)
- **Network → Configs** measures HTTPS ping, jitter, download, and upload through
  supported Xray and standard WireGuard or WARP profiles. Disconnect an active
  WhiteGame tunnel first. WireGuard needs an internet route in `AllowedIPs`.
  For AmneziaWG, connect a profile with an internet route and measure it from
  **Network → Speed**.
- **Network → DNS** searches the bundled provider catalog, scans all or one
  resolver, manages custom addresses, and can apply or restore a resolver.
  **Network → Games** matches the catalog against installed Steam titles and,
  on Windows, Epic installations and installed app records. Its route
  comparisons measure publisher HTTPS endpoints. **Network → Speed** measures
  the current connection using Cloudflare. [Cloudflare Speed Test](https://speed.cloudflare.com/)
- **Device** also shows system and network-adapter information and opens saved
  measurement reports.

## Build from source

On x64 Windows with JDK 17, run these commands from the repository root.

```powershell
.\gradlew.bat -p desktop test
.\desktop\package.ps1
```

On x86-64 Linux with JDK 17, run `bash desktop/package-linux.sh` from the root.

The packaging scripts check the Xray archive's SHA-256 digest and write the
portable ZIP to `dist\WhiteGame-Windows-x64-7.3.1.zip`. The release workflow
also builds `dist/WhiteGame-Linux-x64-7.3.1.tar.gz` and publishes both packages
after the native DNS and WireGuard checks pass on disposable Windows and Linux
runners. Windows also runs an Xray TUN integration test.
