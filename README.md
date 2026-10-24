# LocalPaste

A tiny Android pastebin that runs on your local Wi-Fi. Turn it on in the app, open the URL (or scan the QR code) on any device on the same network, and paste text back and forth between the phone and the browser.

- **Two-way:** paste in the app → shows up in the browser; paste in the browser → shows up in the app.
- **Copy / Delete / Clear all** in both the app and the web page.
- **Offline-friendly:** a single self-contained HTML page, no CDNs, no internet needed — only the same Wi-Fi.
- **Battery-conscious:** the server runs only when you switch it on, and stops itself after 30 minutes without requests.
- **Small:** Kotlin + framework Views, no AndroidX. Dependencies: [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) and [ZXing core](https://github.com/zxing/zxing) (QR code).

## Using it

1. Connect the phone to Wi-Fi and open **LocalPaste**.
2. Tap the big **OFF** button. Grant the permissions it asks for:
   - **Notifications** — optional; shows the running-server notification with a *Stop* button.
   - **Nearby devices / local network** (Android 17+) — **required**; without it other devices can't connect.
3. The button turns green and shows a URL such as `http://192.168.1.202:8080` and a QR code.
4. On another device on the same Wi-Fi, open that URL (or scan the QR code).
5. Type or paste text and hit **Save** — in the app or on the web page. Both sides show the same list.

Turn it off from the app or with the notification's **Stop** action. It also stops by itself after 30 minutes with no HTTP requests (an open, visible browser tab counts as activity; a background tab doesn't).

> **Note:** pastes live in memory only (the 50 most recent). They survive turning the server off and on, but are lost when the app process is closed. Anyone on the same Wi-Fi can read and delete them — there is no password.

## Building

Requirements: Android SDK with platform 37, and JDK 17+ (Android Studio's bundled JDK in `<Android Studio>/jbr` works). Gradle itself is not needed — the wrapper is included.

```sh
# local.properties (not committed) must point to your SDK, e.g.
#   sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk

adb devices                 # phone listed as "device" (USB debugging on)
./gradlew installDebug      # build + install   (Windows: .\gradlew installDebug)
adb shell am start -n com.localpaste/.MainActivity
```

Or open the folder in Android Studio and press **Run**. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## HTTP API

All endpoints return JSON (UTF-8). Paste objects look like `{"id": 3, "text": "…", "time": 1792840139044}` (`time` = epoch ms).

| Method | Path | Body | Result |
|---|---|---|---|
| `GET` | `/` | | The web page |
| `GET` | `/api/pastes` | | Array of pastes, newest first |
| `POST` | `/api/pastes` | `{"text": "…"}` (max 1 MB) | `201` with the created paste |
| `DELETE` | `/api/pastes/{id}` | | `{"ok": true}` or `404` |
| `DELETE` | `/api/pastes` | | `{"ok": true}` — clears all |

```sh
curl http://192.168.1.202:8080/api/pastes
curl -X POST -H 'Content-Type: application/json' -d '{"text":"hello"}' http://192.168.1.202:8080/api/pastes
```

## Configuration

| What | Where |
|---|---|
| Idle auto-stop (30 min) | `ServerService.IDLE_TIMEOUT_MS` |
| Ports tried (8080–8090) | `ServerService.PORTS` |
| Max pastes kept (50) | `PasteStore.MAX_PASTES` |
| Max paste size (1 MB) | `PasteServer.MAX_BODY_BYTES` |
| Web polling interval (3 s) | `setInterval` at the bottom of `app/src/main/assets/index.html` |

## Troubleshooting

- **Other devices time out but the app says ON** — on Android 17+, check *Settings › Apps › LocalPaste › Permissions › Nearby devices* is allowed. Also make sure the router doesn't have "AP/client isolation" (common on guest networks).
- **"Not connected to Wi-Fi"** — the app needs a Wi-Fi network with an IPv4 address; mobile data and hotspots aren't used.
- **Copy button doesn't work in the browser** — the page is plain HTTP, so it falls back to `execCommand('copy')`; if the browser blocks that too, the text is selected so you can copy it manually.

See [ARCHITECTURE.md](ARCHITECTURE.md) for how it fits together.
