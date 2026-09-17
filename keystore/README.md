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

## CI signing

The release workflow (`.github/workflows/ci.yml`) rebuilds the keystore from
repository secrets. Set these once under
*Settings → Secrets and variables → Actions*:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 keystore/whitebooster.jks` |
| `KEYSTORE_PASSWORD` | the store password |
| `KEY_PASSWORD` | the key password |

The release job runs on `v*` tags only and never on a pull request, so a fork
cannot reach these. After building, it checks every APK's signing certificate
against a pinned SHA-256 and refuses to publish on a mismatch — rotating the
key means updating `EXPECTED_SHA256` in the workflow too.
