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
- Dark, modern UI with a centered title, a big connect button and a standalone **Update Servers** button.
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

## Fixed in 1.2

`core init failed ... xray.xudp.basekey: invalid value (BaseKey must be 32 bytes)` – the XUDP key passed to `initCoreEnv` must be 32 random bytes encoded as URL-safe Base64 **without padding**. The service now generates and stores a valid key.

## Verification status

The source integration is committed, but a successful GitHub Actions build and real-device tunnel test have not yet been confirmed. TLS, Reality, WebSocket, gRPC and custom JSON profiles should be tested against working server links before distributing a release.
