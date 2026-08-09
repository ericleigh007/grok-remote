# Grok Remote — Android

Native Compose client for the Grok Remote bridge. Desktop-TUI-inspired: thinking panels, tool cards, Markwon markdown, on-device STT/TTS, cancel + midstream interrupt.

## Prerequisites (already scripted)

From the repo root (once):

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\install-android-sdk.ps1
```

This installs under `%LOCALAPPDATA%\Android\Sdk` and writes `android/local.properties`.

Uses JDK at `C:\Program Files\Android\openjdk\jdk-21.0.8` (or set `JAVA_HOME`).

## Build debug APK

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
cd android
.\gradlew.bat :app:assembleDebug
```

APK:

```
android\app\build\outputs\apk\debug\app-debug.apk
```

Package id: `com.xai.grokremote.debug`

## Install / update on phone (no USB)

Publish the APK into `releases/` and serve it from the bridge:

```powershell
# from repo root — builds + copies APK
powershell -ExecutionPolicy Bypass -File .\scripts\publish-apk.ps1
# bridge must be running (restart after first time routes are added)
schtasks /Run /TN GrokRemoteBridge
```

On the phone (Tailscale connected), open Chrome:

- **Easiest:** PC `http://127.0.0.1:8787/pair` → scan **Install APK** QR
- **https://&lt;your-machine&gt;.&lt;tailnet&gt;.ts.net/dl**
- or **http://&lt;pc-tailscale-ip&gt;:8787/dl**

Tap **Download APK**, allow install from browser, update.

### USB still works

```powershell
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Pairing

1. PC bridge + Tailscale Serve running  
2. PC browser: `http://127.0.0.1:8787/pair`  
3. In the app: scan the QR (or paste your MagicDNS HTTPS base URL + token)

Token is stored in EncryptedSharedPreferences.

## Features

| Feature | Implementation |
|---------|----------------|
| Sessions | Tabs; busy indicator |
| Thinking | Collapsible live thought stream |
| Tools | Tool cards with status |
| Markdown | Markwon (GFM tables, code, links) |
| STT | Android `SpeechRecognizer` (on-device / Google) |
| TTS | System `TextToSpeech` (prefer neural voices) |
| Cancel | Cancel turn while busy |
| Midstream | **Send while busy** = cancel + new prompt |

## Architecture

```text
Android app --WSS/HTTPS--> bridge :8787 --WS ACP--> grok agent serve :2419
```

The app does **not** talk to agent serve directly (secret stays on PC).
