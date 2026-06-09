# Codex Monitor Android App

Native Android app for Codex Monitor.

The app connects to one or more LAN host agents:

```text
http://<computer-lan-ip>:8787/api/mobile-status?fresh=1
```

## Features

- Scan LAN devices running the Codex Monitor host agent.
- Manually add a host URL and optional access token.
- Monitor multiple Codex computers.
- Show traffic-light status, 5h / weekly quota, reset time, and token usage.
- Send heads-up notifications with vibration for done, failed, and permission-required events.
- Keep background polling with a configurable interval.

## Build with Android Studio

1. Open this `android-app/` directory in Android Studio.
2. Wait for Gradle Sync.
3. Build from:

```text
Build > Build Bundle(s) / APK(s) > Build APK(s)
```

Debug APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Release APK output depends on your signing setup. Do not publish `app-debug.apk` as a public release.

## Build from PowerShell

```powershell
.\build-apk.ps1
```

The script tries to detect Android Studio's JBR and SDK path from `local.properties`.

## Install to a Phone

With ADB:

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

For production use, install a signed release APK instead.

## Runtime Permissions

The app uses:

- `INTERNET` for LAN HTTP requests.
- `POST_NOTIFICATIONS` for Android 13+ notifications.
- `FOREGROUND_SERVICE` for background monitoring.
- `RECEIVE_BOOT_COMPLETED` to resume monitoring after reboot.

On Xiaomi / Redmi phones, also allow notification banners, vibration, autostart, and unrestricted battery usage for reliable background alerts.
