# ON Tunnel

Android VPN application: V2Ray/Xray (built-in core) and OpenVPN (through the "OpenVPN for Android" engine).

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

## OpenVPN (new in 1.8)

OpenVPN profiles live next to V2Ray servers in the same list (key icon + protocol badge).

**Engine.** OpenVPN itself is *not* bundled. ON Tunnel drives the free
[OpenVPN for Android](https://play.google.com/store/apps/details?id=de.blinkt.openvpn) app (`de.blinkt.openvpn`) through its
official remote AIDL API (`app/src/main/aidl/de/blinkt/openvpn/api`). The first connection asks for two one-time permissions
(allow ON Tunnel to control it, and the Android VPN dialog). Reason: that engine is GPLv2 and its author states that embedding it in a
closed UI app needs a paid licence, while use of the AIDL API from an external app is explicitly *not* covered by the licence.

**Adding profiles.** `Add server` → *Import OpenVPN file* (.ovpn picker), *New OpenVPN profile*, or paste/clipboard (auto-detected).
Username/password are optional and embedded as an inline `auth-user-pass` block.

**Connection types** (editor → *Connection type*):

| Type | What happens |
| --- | --- |
| Direct | the `.ovpn` is passed to OpenVPN for Android unchanged (TCP or UDP) |
| HTTP payload | a local relay dials the server (optionally via an HTTP proxy), sends your payload, waits for `101`/`2xx`, then carries raw OpenVPN-over-TCP bytes |
| WebSocket | the relay performs a real RFC 6455 handshake; OpenVPN is wrapped in masked binary frames only when the server proves it is a real WebSocket server (`Sec-WebSocket-Accept`), otherwise it is carried as a raw pipe (ping/pong handled) |
| HTTP Custom (legacy 5.1.26) | behaves like the old HTTP Custom: payload sent as written, TLS certificate never validated (any SNI/front domain), any `101`/`2xx` reply or silence switches to a raw pipe, never framed |

Payload / WebSocket can use TLS with a custom **SNI** (and an optional *allow insecure* switch), a **connect-to** override (CDN edge / front
domain) and a custom Host header. Payload tags: `[crlf] [cr] [lf] [host] [port] [host_port] [ua] [ws_key] [split] [delay_split]`.
Example: `GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf][crlf]`.

How the relay works: the profile is rewritten to `proto tcp-client` + `remote 127.0.0.1 <port>`, and the relay's upstream IP is added as
`route <ip> 255.255.255.255 net_gateway` so the relay's own socket bypasses the tunnel (no loop). The server must expose OpenVPN over **TCP**
behind the payload/WebSocket endpoint (UDP cannot be wrapped).

The relay core is plain Java (`app/src/main/java/com/ontunnel/app/ovpn`) and is covered by `tools/ovpn-core-test` (run in CI).

## UI changes in 1.8

Pulsing connect button with live connection timer, status detail line, protocol badges (VLESS · WS · TLS, OpenVPN · WebSocket…),
rounded server cards, and an *Add server* bottom sheet.

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


## Fix: OpenVPN + payload stuck in CONNECTRETRY

* **HTTP payload mode is now always raw after the reply.** Previously a `101 Switching Protocols` made the relay switch to
  WebSocket framing even in HTTP-payload mode, which corrupts the stream for injector-style servers (they expect plain OpenVPN bytes
  after the 101) - OpenVPN then just kept retrying.
* **Multi-request payloads** (decoy `GET /cdn-cgi/trace` + `ACL / ... Upgrade: websocket`): every complete request gets its own reply
  read, replies to all but the last are drained (Content-Length, chunked, or length-less bodies) and ignored; only the last reply must be
  `101` or `2xx`. Explicit `[split]` fragments of a single request are written without waiting for a reply.
* **Clear errors instead of silent retries:** a 3xx/4xx/5xx final reply now closes the connection and is shown in the red error card
  ("Server rejected the payload: HTTP/1.1 400 Bad Request").
* **WebSocket mode** has a new *WebSocket framing (RFC 6455)* switch. Turn it off for servers that become a plain TCP pipe after the 101.
* New *Decoy + ACL* payload template; tests in `tools/ovpn-core-test` (`PayloadScenarios`, `OvpnCoreTest`) run in CI.


## Fix: WebSocket payload not connecting + "Retrying connection" (OpenVPN)

* **WebSocket mode no longer corrupts raw servers.** The relay always wrapped OpenVPN in WebSocket frames after a `101`. Servers used with
  HTTP Custom / injector payloads turn into a plain TCP pipe after the `101`, so every OpenVPN packet was garbage to them and OpenVPN just
  kept retrying. Framing is now **Auto** by default: framed only if the reply contains `Sec-WebSocket-Accept` (every real RFC 6455 server sends it).
  The editor has a *WebSocket framing* choice: Auto / Always framed / Raw after 101.
* **New connection type "HTTP Custom (legacy 5.1.26)"** (see table above). Extra payload tags: `[crlf*N]` `[cr*N]` `[lf*N]` `[netData]`
  `[rotate=a;b;c]` `[random=a;b;c]` `[instant_split]`.
* **The relay retries by itself.** A failed dial, a connection closed during the handshake, a timeout or a `5xx` is retried up to 3 times
  *before* OpenVPN notices (OpenVPN's first bytes just wait in the socket). Every resolved IP of the server is tried. A `3xx/4xx` answer is
  definitive and reported immediately.
* **OpenVPN never gives up.** The generated profile used `connect-retry-max 6`; after six failures OpenVPN quit. It is removed (unlimited) and
  `hand-window 120` gives slow handshakes room.
* **Automatic reconnect in the app.** If OpenVPN quits while connecting, the relay cannot start, or an attempt is still not connected after
  30 s (stuck in CONNECTRETRY/WAIT), ON Tunnel restarts the engine + relay on its own (up to 8 times, counter shown in the status line). The
  numeric noise in "Retrying connection – -2" is hidden. A user disconnect never triggers a reconnect.
* New tests: `tools/ovpn-core-test/HttpCustomScenarios.java` (run in CI).

## License

ON Tunnel is free software, released under the **GNU General Public License, version 2** (GPLv2). See [`LICENSE`](LICENSE) for the full text.

You may use, study, modify and redistribute it under the terms of that license. If you distribute a modified version (or an APK built from it),
you must make the complete corresponding source code available under the same license. This also keeps the project compatible with embedding
the OpenVPN engine from [ics-openvpn](https://github.com/schwabe/ics-openvpn), which is itself GPLv2.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
or FITNESS FOR A PARTICULAR PURPOSE.
