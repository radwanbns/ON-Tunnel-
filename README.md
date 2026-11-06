# ON Tunnel

Android VPN application focused on V2Ray/Xray only.

- Application ID: `com.ontunnel.app`
- App name: ON Tunnel
- Launcher icon: OT
- Minimum Android: 7.0 (API 24)
- Core: `libv2ray.aar` from `2dust/AndroidLibXrayLite`, pinned to `v26.10.31`
- Build: GitHub Actions workflow in `.github/workflows/android.yml`

## Implemented in this repository

- Xray native core dependency is downloaded by CI.
- Android `VpnService` start/stop service and foreground notification.
- VLESS, VMess, Trojan URI parsing and custom JSON import by paste.
- Local server list storage.
- ABI-specific debug APK jobs for arm64-v8a, armeabi-v7a, x86_64 and x86.

## Verification status

The source integration is committed, but a successful GitHub Actions build and real-device tunnel test have not yet been confirmed. TLS, Reality, WebSocket, gRPC and custom JSON profiles should be tested against working server links before distributing a release. The current profile importer does not yet implement subscription feeds or automatic GitHub server synchronization.
