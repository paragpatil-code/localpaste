# Architecture

LocalPaste is a single-module Android app with five source files. There is no AndroidX, no DI and no database: framework Views, one foreground service, and an in-memory list.

```
app/src/main/
├── AndroidManifest.xml
├── assets/index.html                  web page (inline CSS/JS)
├── java/com/localpaste/
│   ├── MainActivity.kt                the only screen
│   ├── ServerService.kt               foreground service; owns the server's lifecycle
│   ├── PasteServer.kt                 NanoHTTPD subclass; HTTP ⇄ PasteStore
│   └── PasteStore.kt                  in-memory pastes shared by app and server
└── res/layout/                        activity_main.xml, item_paste.xml
```

## Components

```
                ┌───────────────── phone (one process) ─────────────────┐
                │                                                       │
  user ──taps──▶│  MainActivity ──start/stop──▶ ServerService           │
                │     │    ▲                       │ owns               │
                │     │    │ listener (main thread)│                    │
                │     │    │                       ▼                    │
                │     ├────┴─── add/delete ──▶ PasteStore ◀── PasteServer (NanoHTTPD)
                │     │         listener ◀──────┘   ▲            ▲      │
                │     │                              │            │      │
                └─────┼──────────────────────────────┼────────────┼──────┘
                      │                              │       HTTP │ :8080
                      ▼                              │            │
              QR / URL on screen          browser on same Wi-Fi ──┘
                                          (index.html polls every 3 s)
```

### PasteStore
A Kotlin `object` holding an `ArrayDeque` of pastes (newest first, capped at 50). Every method synchronizes on the object, because it's called both from the main thread (app) and from NanoHTTPD's request threads. After any change it posts a single `listener` callback to the main thread; `MainActivity` sets it in `onStart` and clears it in `onStop`.

Because it lives at process level rather than inside the server, pastes survive the server being switched off and on, and the app can add/delete pastes while the server is off. They don't survive the process dying (memory-only by design).

### PasteServer
A `NanoHTTPD` subclass that maps the JSON API (see README) onto `PasteStore` and serves `index.html` at `/`. Notes:
- Request bodies are read manually (`content-length` + `readFully`) and decoded as UTF-8; oversized bodies are rejected and the connection is closed, since the unread body would corrupt keep-alive.
- Responses are encoded to UTF-8 bytes by hand. NanoHTTPD's `String` overload didn't produce correct UTF-8 on Android (the HTML came back empty).
- Every request invokes `onRequest`, which `ServerService` uses to bump its idle timestamp.

### ServerService
A foreground service of type `specialUse` (not `dataSync`: that type is meant for transfers and is time-limited on Android 15+). It is the only thing that starts or stops the server.

Start (`startForegroundService` from the activity, with the Wi-Fi IP as an extra):
1. `startForeground` immediately with a "Starting…" notification, as the platform requires.
2. Read `index.html`, then try ports 8080–8090 until one binds.
3. Acquire a `WifiLock` (`FULL_LOW_LATENCY` on API 29+), arm the idle check, update the notification with the URL and a **Stop** action.
4. Publish `ServerService.url` and notify the activity's listener.

Stop (button, notification action, or idle timeout) all end in `stopSelf()` → `onDestroy`, which cancels the alarm, stops NanoHTTPD, releases the Wi-Fi lock, removes the notification and clears `url`.

Guarantees that matter for battery:
- `START_NOT_STICKY` and no boot receiver: nothing restarts the server on its own.
- No CPU wakelock. The `WAKE_LOCK` permission exists only because `WifiLock` requires it.
- **Idle timeout** uses `AlarmManager` (`ELAPSED_REALTIME_WAKEUP` with an `OnAlarmListener`) rather than a `Handler`, because `Handler` delays use uptime, which pauses while the CPU sleeps. Requests just write a `@Volatile` timestamp; when the alarm fires it either stops the service or re-arms itself for the remaining time. This keeps per-request cost to a single field write.

### MainActivity
A plain `android.app.Activity` with two areas: a scrolling area (toggle, URL, QR, paste list) and a composer (text box + Save) pinned to the bottom so it always sits above the keyboard.

- **Wi-Fi detection:** registers a `NetworkCallback` for `TRANSPORT_WIFI` with the `INTERNET` capability removed, and reads the IPv4 address from `LinkProperties`. Tracking the Wi-Fi network directly (instead of `activeNetwork`) means a Wi-Fi network without internet still works, even when the phone's default network has switched to mobile data.
- **Permissions on toggle:** `POST_NOTIFICATIONS` (API 33+, optional) and `ACCESS_LOCAL_NETWORK` (API 37+, required). Android 17 blocks LAN traffic for apps targeting API 37 without the latter; the server binds fine but every LAN connection times out.
- **Rendering:** the server state comes from `ServerService.url`/`lastError`; the paste list is rebuilt from `PasteStore.all()` on every change (at most 50 small rows, so no RecyclerView needed).
- **Edge-to-edge:** targetSdk 35+ is always edge-to-edge, so the root view is padded with the system-bar and IME insets.

### Web page (`assets/index.html`)
One self-contained page. It polls `GET /api/pastes` every 3 s **only while the tab is visible** (and immediately when it becomes visible again), and skips re-rendering if nothing changed so text selection isn't disturbed. Text is inserted with `textContent` (no HTML injection). Copy uses `navigator.clipboard` only in secure contexts; on plain HTTP it falls back to a hidden textarea + `execCommand('copy')`, and as a last resort selects the text for manual copying.

## Data flow

| Action | Path | Latency |
|---|---|---|
| Save in app | `PasteStore.add` → activity listener re-renders; browser sees it on next poll | ≤ 3 s in browser |
| Save in browser | `POST` → `PasteServer` → `PasteStore.add` → listener posted to main thread → app re-renders | immediate in app |
| Delete / Clear | same paths with `delete` / `clear` | same |

## Threading

| Thread | Runs |
|---|---|
| Main | Activity, service callbacks, idle alarm, `PasteStore` listener |
| NanoHTTPD workers | `PasteServer.serve` → `PasteStore` (synchronized) |
| Connectivity thread | none — the network callback is registered with a main-thread `Handler` |

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | open the server socket |
| `ACCESS_NETWORK_STATE` | find the Wi-Fi network and its IP |
| `ACCESS_WIFI_STATE`, `WAKE_LOCK` | Wi-Fi lock while running |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | the server's foreground service |
| `POST_NOTIFICATIONS` | show the running-server notification (runtime, API 33+) |
| `ACCESS_LOCAL_NETWORK` | let LAN devices connect (runtime, API 37+) |

## Build

AGP 8.10.1, Kotlin 2.1.21, Gradle 8.14.2 (wrapper), `compileSdk`/`targetSdk` 37, `minSdk` 26, Java 17 bytecode. AGP 8.10 is only tested up to SDK 36, so `android.suppressUnsupportedCompileSdk=37.0` is set in `gradle.properties`; upgrading AGP removes the need for it. Release builds enable R8 minification and resource shrinking.
