# ON Tunnel

Android VPN application focused on V2Ray/Xray only.

- Application ID: `com.ontunnel.app`
- App name: ON Tunnel
- Launcher icon: OT
- Minimum Android: 7.0 (API 24)
- Core: `libv2ray.aar` from `2dust/AndroidLibXrayLite`, pinned to `v26.10.31`
- Build: GitHub Actions workflow in `.github/workflows/android.yml`
- Language: **English only**

## Features

- Xray native core dependency is downloaded by CI.
- Android `VpnService` start/stop service and foreground notification.
- VLESS, VMess, Trojan URI parsing and custom JSON import by paste.
- **Full V2rayNG-style server editor** (address, port, UUID/password, flow, network: tcp/ws/grpc/httpupgrade/xhttp/h2, host, path, TLS/Reality, SNI, fingerprint, ALPN, allowInsecure, public key, short id, spiderX). Subscription servers stay locked.
- Telegram channel dialog shown on every app launch.
- Light / Dark / Auto theme (menu ⋮ → Theme), compact name-only server list, **Check update** in the menu, centered title, big connect button and a standalone **Update Servers** button.
- Local server list storage.
- **Hidden subscription update** – a hardcoded subscription URL is fetched silently on app launch and via the refresh button. The URL is **never displayed** in the UI.
- Success toast / message when new servers are added from the subscription.
- ABI-specific debug APK jobs for arm64-v8a, armeabi-v7a, x86_64 and x86.

## Subscription URL

Edit the constant in `MainActivity.kt`:

```kotlin
private const val SUBSCRIPTION_URL = "https://example.com/your-subscription-link"
```

Replace with your real subscription link. While it still contains `example.com` the auto-update is skipped.

## UI changes in 1.5

- App icon replaced with the same shield glyph shown in the connected-state notification (`ic_launcher.xml`), so the launcher icon and the VPN notification icon now match.
- Removed the standalone **Update Servers** button from the home screen. The home screen now shows only the connect button and the server list; refreshing the subscription is done from the menu ⋮ → **Check update** (also still runs automatically once on launch).

## Hardening in 1.4

- The subscription and Telegram URLs are no longer plain strings in the dex – see `SecureStrings.kt` (XOR-obfuscated, decoded only at runtime).
- `classes.dex` is now R8-minified, repackaged and stripped of debug/source info on **both** the debug build (what CI produces) and release, see `proguard-rules.pro` and `app/build.gradle.kts`. Class/method names are renamed and flattened, logging calls are stripped, so a decompiled APK is far harder to read or patch.
- `IntegrityGuard.kt` can detect a re-signed/repackaged copy of the app (someone edited the dex, rebuilt and re-signed it with a different key) and disable the VPN connect button if the signature doesn't match yours. It is **off by default** – fill in `EXPECTED_SHA256` with your release certificate's SHA-256 once you have a signed build (see the comment in that file for how to read the hash on-device).
- None of this can stop someone from modifying their own copy of an open-source Android app on their own device – no on-device scheme can. What it does is make a silently redistributed, tampered build detectable and stop it from functioning, and make the strings and logic meaningfully harder to lift with a quick decompile.

## Fixed in 1.2

`core init failed ... xray.xudp.basekey: invalid value (BaseKey must be 32 bytes)` – the XUDP key passed to `initCoreEnv` must be 32 random bytes encoded as URL-safe Base64 **without padding**. The service now generates and stores a valid key.

## Verification status

The source integration is committed, but a successful GitHub Actions build and real-device tunnel test have not yet been confirmed. TLS, Reality, WebSocket, gRPC and custom JSON profiles should be tested against working server links before distributing a release.
