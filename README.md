<div align="center">

# Codex Monitor

Monitor Codex sessions across your LAN from Android.

![Android](https://img.shields.io/badge/Android-native%20Java-3DDC84?logo=android&logoColor=white)
![Python](https://img.shields.io/badge/Host-Python%203.x-3776AB?logo=python&logoColor=white)
![LAN](https://img.shields.io/badge/Network-LAN%20HTTP-0A84FF)
![Release](https://img.shields.io/github/v/release/KangYYYYYY/Codex-Monitor?include_prereleases&label=release)

</div>

Codex Monitor is a local-network Android monitor for Codex sessions. It runs a lightweight HTTP agent on each computer, then lets your phone track multiple devices, Codex activity, 5h / weekly quota, token usage, and important task events.

The project is designed for personal LAN use: no cloud service, no external account system, and no remote relay.

## Contents

- [Features](#features)
- [How It Works](#how-it-works)
- [Quick Start](#quick-start)
- [Recommended AI-Assisted Install](#recommended-ai-assisted-install)
- [Android App](#android-app)
- [Host API](#host-api)
- [Codex Hook Events](#codex-hook-events)
- [Troubleshooting](#troubleshooting)
- [Security Notes](#security-notes)

## At a Glance

| Part | Description |
| --- | --- |
| Android app | Multi-device dashboard, LAN scan, notifications, background polling |
| Host agent | Local Python HTTP server exposed on `:8787` |
| Codex hooks | Publish status updates when Codex starts, thinks, runs tools, asks permission, or finishes |
| Network model | Phone and computers stay inside the same trusted LAN |

## Features

- Monitor multiple Codex computers from one Android app.
- Scan LAN devices on port `8787`, or add a computer manually.
- Show device state with an Agent-Signal-Bar-style traffic-light indicator.
- Track Codex states such as thinking, running, done, failed, idle, and permission required.
- Display 5h and weekly remaining quota when the host agent can read it.
- Display current session token usage.
- Send Android heads-up notifications with vibration for done, failed, and permission-required events.
- Keep background polling alive with a user-configurable check interval.
- Auto-start the local HTTP agent from Codex hooks.
- Support optional LAN access token via `LOCAL_AGENT_TOKEN`.

## How It Works

```text
Codex / Codex plugin
        |
        | Hook events
        v
host/codex_mobile_hook.py
        |
        | local state file / event data
        v
server.py  -- HTTP :8787 -->  Android app
        |
        v
LAN status API
```

The phone does not talk to OpenAI directly. It polls each computer's local `server.py` agent and renders the status returned by that machine.

## Repository Layout

```text
android-app/   Native Android app source
host/          Codex hook installer, event publisher, firewall helper
hooks/         Example Codex hooks.json
server.py      LAN HTTP agent running on the computer
.env.example   Optional host configuration
README.md      Project documentation
```

Generated APKs, logs, local IDE files, `.env`, `local.properties`, Python cache, and build outputs are intentionally ignored by Git.

## Quick Start

### 1. Install the Android app

Download the APK from the GitHub release page:

```text
https://github.com/KangYYYYYY/Codex-Monitor/releases/latest
```

Install it on your Android phone, then allow notification permission when the app asks.

### 2. Prepare the host computer

Clone or copy this repository to the computer that runs Codex.

```powershell
Copy-Item .env.example .env
```

Edit `.env` if needed:

```text
LOCAL_AGENT_TOKEN=change-this-local-token
HOST=0.0.0.0
PORT=8787
```

`LOCAL_AGENT_TOKEN` is optional, but recommended on shared Wi-Fi. If you set it on the computer, enter the same token in the Android app.

### 3. Install Codex hooks

User-level install:

```powershell
.\host\install_codex_mobile_hooks.ps1 -Scope User
```

Project-level install:

```powershell
.\host\install_codex_mobile_hooks.ps1 -Scope Project
```

Restart Codex after installing hooks. The first run may ask you to review and trust the hook definition.

### 4. Start or verify the LAN agent

The hook can start `server.py` automatically when Codex starts. You can also run it manually:

```powershell
python server.py
```

Check the local endpoints:

```text
http://127.0.0.1:8787/api/health
http://127.0.0.1:8787/api/mobile-status?fresh=1
```

Then connect your phone to the same Wi-Fi and either scan the LAN or add:

```text
http://<computer-lan-ip>:8787
```

## Recommended AI-Assisted Install

For additional computers, the easiest workflow is to let Codex, Claude Code, or another local AI assistant install the hook for you.

Copy this repository to the target computer, then ask the assistant:

```text
Please install Codex Monitor's host hook on this computer.

Requirements:
1. Enter the current project directory.
2. Copy .env.example to .env if .env does not exist.
3. Run .\host\install_codex_mobile_hooks.ps1 -Scope User.
4. Confirm that %USERPROFILE%\.codex\hooks.json contains the hook entries.
5. Restart Codex or tell me to restart it.
6. Run python server.py and confirm that http://127.0.0.1:8787/api/health works.
7. If LAN access fails, help me allow Windows Firewall inbound access for port 8787.
```

This avoids common mistakes around paths, hooks, PowerShell execution policy, and firewall rules.

## Android App

Source directory:

```text
android-app/
```

Build with Android Studio:

1. Open `android-app/`.
2. Wait for Gradle Sync.
3. Use `Build > Build Bundle(s) / APK(s) > Build APK(s)`.

Build from PowerShell:

```powershell
cd android-app
.\build-apk.ps1
```

Debug APK output:

```text
android-app/app/build/outputs/apk/debug/app-debug.apk
```

Release APKs should be signed before publishing. Do not publish `app-debug.apk` as a public release.

## Host API

Default bind:

```text
0.0.0.0:8787
```

Useful endpoints:

| Endpoint | Purpose |
| --- | --- |
| `GET /api/health` | Basic health check for scanning and manual connection |
| `GET /api/mobile-status?fresh=1` | Full Android status payload |

If `LOCAL_AGENT_TOKEN` is configured, pass the same token from the Android app. Keep the token private.

## Codex Hook Events

The installer writes these Codex hook mappings:

| Codex event | Monitor state |
| --- | --- |
| `SessionStart` | Start host server and publish initial status |
| `UserPromptSubmit` | Thinking |
| `PreToolUse` | Running / writing |
| `PostToolUse` | Thinking or error |
| `PermissionRequest` | Permission required |
| `Stop` | Done |

The app also polls `/api/mobile-status?fresh=1`, so the UI can recover even if a hook event is missed.

## Windows Firewall

If the phone can reach the computer's IP but cannot open port `8787`, allow inbound LAN traffic.

Run PowerShell as administrator:

```powershell
powershell -ExecutionPolicy Bypass -File .\host\allow_firewall_8787.ps1
```

You can also manually allow Python or TCP port `8787` in Windows Defender Firewall.

## Xiaomi / Redmi Settings

For reliable notifications on Xiaomi / Redmi devices:

- Allow notifications, heads-up banners, lock-screen notifications, and vibration.
- Set battery saver to unrestricted for Codex Monitor.
- Allow autostart if available.
- Do not clear the app from recent tasks if you rely on background alerts.
- In the app settings, choose a polling interval that matches your battery and latency needs.

Without these permissions, Android or MIUI may delay background checks until the app is opened again.

## Deploy to More Computers

Only these files are needed on another computer:

```text
host/
hooks/
server.py
.env.example
README.md
```

The Android source is only needed if you want to build the APK on that computer.

Install on the target computer:

```powershell
Copy-Item .env.example .env
.\host\install_codex_mobile_hooks.ps1 -Scope User
python server.py
```

Restart Codex, trust the hooks if prompted, then add the computer from the Android app.

## Troubleshooting

### Phone cannot connect

- Confirm the phone and computer are on the same LAN.
- Open `http://<computer-ip>:8787/api/health` from the phone browser.
- Check Windows Firewall for port `8787`.
- Do not use `127.0.0.1` on the phone. That points to the phone itself.
- If you set `LOCAL_AGENT_TOKEN`, make sure the Android app uses the same token.

### Notifications appear only after opening the app

- Enable notification banners and vibration.
- Disable battery restrictions for Codex Monitor.
- Allow autostart/background running in MIUI.
- Use a shorter polling interval in the app settings.

### State sometimes looks stale

- Restart `server.py`.
- Restart Codex after installing or changing hooks.
- Open `/api/mobile-status?fresh=1` on the host to confirm what the agent is returning.

## Security Notes

This project is intended for trusted local networks.

- Prefer setting `LOCAL_AGENT_TOKEN` on shared Wi-Fi.
- Do not expose port `8787` to the public internet.
- Do not commit `.env`, local signing keys, build outputs, or logs.
- Review Codex hook definitions before trusting them.

## License

No license file is included yet. Add a `LICENSE` file before publishing this as an open-source project so users know what they are allowed to do with the code.
