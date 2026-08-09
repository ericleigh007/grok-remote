# Grok Remote

Phone-friendly remote control for **local Grok Build** on your PC. No Grok source needed — talks to `grok agent stdio` over ACP.

## What you get

- **Typing chat** in a mobile browser (sideload APK later if you want)
- **Multiple sessions** (starts two if you list multiple projects; `+ Session` anytime)
- **Streaming** replies + tool activity
- **STT / TTS** via the browser (Chrome on Android works well)
- **Away from home** via **Tailscale** (recommended) + a shared secret token

## Architecture (durable)

```text
Phone  --Tailscale HTTPS-->  bridge :8787  --WebSocket ACP-->  grok agent serve :2419
         (UI + pairing)         auto-reconnect                  long-lived backend
```

This is better than a single process that spawns `grok agent stdio`:

| | **stdio child** (old) | **agent serve + WS** (current) |
|--|----------------------|--------------------------------|
| Agent dies with bridge? | Yes | **No** — separate process |
| Reconnect phone UI | Restart whole stack | Bridge reconnects WS, re-attaches sessions |
| Multi-client / future | Awkward | Natural (ACP serve) |
| Ops | One process | Two scheduled tasks |

`config.json`:

- `agent_transport`: `"websocket"` (default) or `"stdio"`
- `agent_ws_url`: `ws://127.0.0.1:2419/ws`
- `agent_secret`: shared with `grok agent serve --secret …`

## Auto-start (required for stability)

**Do not** start these from a Grok agent tool shell via `Start-Process` — use Scheduled Tasks.

```powershell
cd C:\Users\ericl\source\repos\grok-remote
powershell -ExecutionPolicy Bypass -File .\install-startup.ps1
```

Tasks:

- `GrokAgentServe` — `grok agent serve` on `127.0.0.1:2419`
- `GrokRemoteBridge` — phone UI on `:8787` (ACP WebSocket client + auto-reconnect)

```powershell
schtasks /Run /TN GrokAgentServe
schtasks /Run /TN GrokRemoteBridge
```

Logs: `logs\lifecycle.log`, `logs\agent-serve*.log`, `logs\bridge-run.*`  
Remove: `.\uninstall-startup.ps1`

Foreground UI debug only: `.\start.ps1` (still expects agent serve if transport=websocket)

## Quick start (PC)

1. Edit `config.json`:
   - Set a long random `remote_token`
   - Add projects under `projects`. To **resume** an existing Grok chat, set `session_id` (from `/session` or the session list):
     ```json
     {
       "name": "Doorbell C# Service",
       "cwd": "C:\\Users\\ericl\\source\\repos\\ISITWeb",
       "session_id": "019fb45d-fbb1-7511-b086-fa4f190ac517",
       "replay_history": false
     }
     ```
     `replay_history: false` uses `session/resume` (keeps full PC context, does not dump 400+ turns into the phone). Set `true` only if you want full history replay.
2. Start the bridge:

```powershell
.\start.ps1
```

3. Open on this machine first: `http://127.0.0.1:8787/` and paste the token.

## Away from home (Tailscale) — pair with QR (no typing)

1. Install [Tailscale](https://tailscale.com/) on the PC and the Android phone; same account. Both **Connected**.
2. Keep the PC on and `start.ps1` running.
3. **For mic / STT (required on Android Chrome):** enable Tailscale HTTPS front door once:
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\enable-tailscale-https.ps1
   # same as: tailscale serve --bg http://127.0.0.1:8787
   ```
   Phone must open **`https://<pc>.ts.net/`** (secure context). Plain `http://100.x:8787` allows typing only — **mic is blocked by the browser**.
4. **On the PC browser only**, open: [http://127.0.0.1:8787/pair](http://127.0.0.1:8787/pair)  
   (`/pair` is **loopback-only** — phone/Tailscale clients get HTTP 403.)
5. **On the phone**, scan the QR with the camera (or Google Lens).  
   The QR prefers the HTTPS Serve URL when configured. Chrome opens, stores the token, strips it from the address bar.
6. Bookmark the chat on the phone for next time (token stays in localStorage).

### Mic / voice tips

- Use **Chrome** on Android.
- **Tap** the mic once to start (button turns red / “Listening…”), **tap again** to stop — not press-and-hold.
- Allow **Microphone** when Chrome prompts (Site settings if you denied earlier).
- Web Speech uses Google’s STT cloud on Chrome — needs phone network.
- TTS (“TTS on”) usually works even on HTTP; STT does not.

Manual fallback: open `http://<pc-tailscale-ip>:8787/` and paste `remote_token` once (no mic).

Security model (single user, fixed connection):

- Tailscale encrypts the path (no port-forwarding to the public internet).
- The bridge requires the remote token on every WebSocket connect.
- Grok runs with `--always-approve` so tools work while you are away — **treat the token like a full agent key for this machine**.
- `/pair` and `/pair/qr.png` only answer on `127.0.0.1` / `::1` so the QR is not fetchable from the phone or tailnet.

## Using voice

- **Mic button**: browser speech-to-text; when recognition ends, the message auto-sends.
- **TTS on/off**: reads the completed assistant reply aloud.
- Use **Chrome** on Android for best STT support; grant microphone permission.

## API sketch

| Endpoint | Notes |
|----------|--------|
| `GET /` | Chat UI |
| `WS /ws?token=…` | Real-time events + prompts |
| `POST /api/sessions` | Create session (`X-Remote-Token` header) |
| `POST /api/prompt` | Non-WS prompt |
| `GET /api/health` | Liveness (no auth) |

## Architecture

```
Phone (Chrome) --Tailscale--> PC:8787 bridge --stdio ACP--> grok agent --always-approve
```

## Android app (native)

Kotlin + Compose client in [`android/`](android/):

```powershell
# One-time SDK (if needed)
powershell -ExecutionPolicy Bypass -File .\scripts\install-android-sdk.ps1

cd android
$env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8"
.\gradlew.bat :app:assembleDebug
# APK: app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

See [android/README.md](android/README.md). Features: thinking/tool timeline, Markwon markdown, system STT/TTS, cancel + midstream interrupt, QR pair.

## Troubleshooting

- **401 / invalid token**: match `config.json` `remote_token` (or `GROK_REMOTE_TOKEN` env).
- **Agent won't start**: ensure `grok` is on PATH (`%USERPROFILE%\.grok\bin`) and you are logged in (`grok login`).
- **Empty sessions**: check the server console for ACP errors; first session creation can take a few seconds.
- **STT missing**: use Chrome; Safari/Firefox support varies.
