# Release signing

`whitebooster.jks` and `signing.properties` are **not in git** — they sign every
public WhiteGame APK, so anyone holding them can publish an update Android will
install over a real one.

To make release builds on a new machine, put both files in this directory:

```
keystore/
  whitebooster.jks        # from secure backup / password manager
  signing.properties      # storePassword= keyAlias= keyPassword=
```

`WG_STORE_PASSWORD` / `WG_KEY_PASSWORD` environment variables work instead of
`signing.properties` (useful in CI).

Without them the project still compiles and tests; debug builds fall back to the
local Android debug key and `assembleRelease` produces an unsigned APK.

If the keystore is ever lost, the app's signing identity is gone with it — a new
key means existing installs cannot upgrade, only reinstall.
