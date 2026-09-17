# Third-party notices

## libwg-go.so (app/src/main/jniLibs/*/libwg-go.so)

Prebuilt userspace tunnel engine taken from the official AmneziaWG for Android
release **v3.1.20260814** (`AmneziaWG-3.1.202060814.apk`).

- Upstream: https://github.com/amnezia-vpn/amneziawg-android
- Engine:   https://github.com/amnezia-vpn/amneziawg-go (fork of
            https://git.zx2c4.com/wireguard-go)

Licenses of the code compiled into this binary:

- amneziawg-go / wireguard-go — MIT
  Copyright (C) 2017-2023 WireGuard LLC. All Rights Reserved.
  Copyright (C) 2023-2026 AmneziaVPN.
- JNI shim (`jni.c`, `api-android.go`) — Apache-2.0
  Copyright (C) 2017-2022 Jason A. Donenfeld <Jason@zx2c4.com>.
- Go runtime and golang.org/x/sys — BSD-3-Clause
  Copyright (C) 2009 The Go Authors.

The library exports JNI entry points bound to the class `org.amnezia.awg.GoBackend`.
That class is declared in `app/src/main/java/org/amnezia/awg/GoBackend.kt`; its
package and name are fixed by the symbol names in the binary and must not change.

"WireGuard" is a registered trademark of Jason A. Donenfeld. This application is
not sponsored or endorsed by WireGuard LLC or AmneziaVPN.
