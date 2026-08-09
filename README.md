# Grok Remote

**Control local [Grok Build](https://x.ai) from your phone — without forking Grok, without reverse-engineering the TUI, and without exposing your agent to the public internet.**

Grok Remote is a thin, open-source remote for **your own machine**. It uses the same **official** surfaces xAI already ships:

- `grok agent serve` — long-lived Agent Client Protocol (ACP) over WebSocket  
- Session resume / history that already lives under `~/.grok`  
- Your existing login (OAuth or API key) on the PC  

Nothing here patches or reimplements Grok. If it works in the desktop agent, it works here.

---

## Screenshots

<p align="center">
  <img src="docs/images/android-chat.jpg" alt="Grok Remote Android app — chat, sessions, and streaming reply" width="300" />
  &nbsp;&nbsp;
  <img src="docs/images/pc-pair.png" alt="PC /pair page — loopback-only QR pairing (QR hidden in this capture)" width="420" />
</p>

<p align="center">
  <em>Left:</em> Android client (sessions, composer, midstream interrupt).
  &nbsp;·&nbsp;
  <em>Right:</em> PC <code>/pair</code> page — QR is loopback-only; capture shown with code redacted.
</p>

<!-- Optional future shots — drop files in docs/images/ and uncomment:
<p align="center">
  <img src="docs/images/android-thinking.png" alt="Thinking panel" width="280" />
  <img src="docs/images/android-voice.png" alt="TTS voice picker" width="280" />
  <img src="docs/images/android-pair.png" alt="In-app QR scanner" width="280" />
</p>
-->

---

## Why this exists

Grok Build is excellent at the desk. Away from the desk, you still want:

- The **same sessions** you already started on the PC  
- **Thinking + tool use** visible like the desktop client  
- **Voice** that isn’t a browser afterthought  
- **Secure** access that doesn’t open a random port on the internet  

Grok Remote is that remote: a small PC bridge + Android app (and a web fallback), all on top of **standard Grok agent serve**.

---

## Highlights

| Feature | What you get |
|---------|----------------|
| **No Grok modifications** | Uses `grok agent serve` and ACP — stock xAI CLI support |
| **Scheduled tasks** | Agent + bridge survive logon; not tied to a terminal window |
| **`/pair` on the PC** | Loopback-only QR page; phone never fetches the secret URL as a page |
| **QR login on Android** | Scan once; token stored in encrypted prefs |
| **APK over the bridge** | ` /download ` serves the latest debug APK — no USB dance for updates |
| **Multi-session** | Resume real Grok sessions (Flow, doorbell, …) with recent history in the UI |
| **TUI-shaped stream** | Thinking, tools, markdown replies, cancel + midstream interrupt |
| **STT / TTS** | System speech recognizer + system TTS with **voice picker** |

---

## Architecture

```text
┌─────────────────────┐         encrypted mesh          ┌──────────────────────────────┐
│  Android app        │ ──────────────────────────────► │  Your PC                     │
│  (or mobile browser)│   e.g. Tailscale HTTPS          │                              │
│                     │   https://pc…ts.net/            │  Scheduled tasks:            │
│  • QR pair          │                                 │   • GrokAgentServe  :2419    │
│  • chat / think     │                                 │   • GrokRemoteBridge :8787   │
│  • tools / voice    │                                 │                              │
│  • cancel / inject  │                                 │  grok agent serve  (ACP/WS)  │
└─────────────────────┘                                 │         ▲                    │
                                                        │         │ WebSocket ACP      │
                                                        │         │ 127.0.0.1 only     │
                                                        │  Python bridge (this repo)   │
                                                        │  pair · download · sessions  │
                                                        └──────────────────────────────┘
```

**Security model (single user, fixed setup):**

1. **Remote path** — private mesh (we document **Tailscale**). Other VPN / reverse-proxy setups work the same idea; we only show Tailscale end-to-end.  
2. **Agent secret** — `grok agent serve --secret` binds to **localhost**. The phone never talks to port `2419`.  
3. **Bridge token** — phone authenticates to `:8787` with a long random `remote_token`.  
4. **`/pair` is loopback-only** — QR (and token) are generated on the PC display; Tailscale clients get **403** on `/pair`.  
5. **Grok credentials stay on the PC** — OAuth / API key never leave the machine.

---

## Prerequisites

- Windows PC with **[Grok Build](https://docs.x.ai)** installed and working (`grok` on PATH, already logged in).  
- **Python 3.11+**  
- Phone: Android 8+ for the app (or any modern mobile browser for the web UI).  
- Remote access: **[Tailscale](https://tailscale.com/)** on PC + phone (free Personal plan is enough).  
  *WireGuard, ZeroTier, Cloudflare Tunnel, etc. can substitute; setup steps below are Tailscale-only.*

---

## Quick setup (PC)

### 1. Clone and configure

```powershell
git clone https://github.com/ericleigh007/grok-remote.git
cd grok-remote

python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r server\requirements.txt

copy config.example.json config.json
# Edit config.json — see below
```

**`config.json` (local only — never commit):**

| Field | Purpose |
|-------|---------|
| `remote_token` | Long random string; phone WebSocket auth |
| `agent_secret` | Same secret passed to `grok agent serve` |
| `public_host` | Your Tailscale MagicDNS HTTPS origin, e.g. `https://my-pc.tailnet.ts.net` |
| `projects[]` | Tabs to open: `name`, `cwd`, optional `session_id` to **resume** an existing Grok chat |
| `history_limit` | How many recent turns to show in the app (agent still has full context) |

```json
{
  "remote_token": "a-long-random-string",
  "agent_secret": "another-long-random-string",
  "public_host": "https://YOUR-PC.YOUR-TAILNET.ts.net",
  "prefer_tailscale_https": true,
  "default_cwd": "C:\\path\\to\\project",
  "agent_transport": "websocket",
  "agent_ws_url": "ws://127.0.0.1:2419/ws",
  "agent_bind": "127.0.0.1:2419",
  "history_limit": 40,
  "projects": [
    {
      "name": "My project",
      "cwd": "C:\\path\\to\\project",
      "session_id": "optional-uuid-from-grok-session-info",
      "replay_history": false
    }
  ],
  "always_approve": true
}
```

> `always_approve` matches unattended remote use. Deny rules / hooks on the Grok side still apply. Treat tokens like full agent keys for this machine.

### 2. Install Windows scheduled tasks

These start at logon and keep running under Task Scheduler (not inside a random terminal job):

```powershell
powershell -ExecutionPolicy Bypass -File .\install-startup.ps1
```

| Task | Role |
|------|------|
| **GrokAgentServe** | `grok agent --always-approve serve` on `127.0.0.1:2419` |
| **GrokRemoteBridge** | Phone/web bridge on `:8787` → ACP WebSocket to the agent |

Manual restart later:

```powershell
schtasks /Run /TN GrokAgentServe
schtasks /Run /TN GrokRemoteBridge
```

Uninstall:

```powershell
.\uninstall-startup.ps1
```

### 3. Tailscale (remote path)

Other private networks work; **this guide only walks through Tailscale.**

1. Install [Tailscale](https://tailscale.com/download) on the **PC** and **phone**.  
2. Sign into the **same account** (or share the PC node).  
3. Confirm both show **Connected**.  
4. Enable **Serve** for HTTPS on the PC (required for browser mic; also a clean origin for the app):

```powershell
powershell -ExecutionPolicy Bypass -File .\enable-tailscale-https.ps1
# equivalent: tailscale serve --bg http://127.0.0.1:8787
```

First time, Tailscale may open a browser to enable Serve/HTTPS on the tailnet. Prefer **Serve** (tailnet only). You do **not** need **Funnel** (public internet).

Your phone will use something like:

```text
https://YOUR-PC.YOUR-TAILNET.ts.net/
```

Find the name with:

```powershell
& "C:\Program Files\Tailscale\tailscale.exe" status
```

### 4. Pair the phone (QR — secure)

1. On the **PC only**, open:

   ```text
   http://127.0.0.1:8787/pair
   ```

   This page is **loopback-only**. Opening `/pair` over Tailscale returns **403** — by design.

2. On the phone, scan the QR with the camera (or the in-app scanner).  
   The QR embeds the **HTTPS chat URL + token**. The token is stored on the device; it is stripped from the address bar after load.

3. Bookmark the site or use the Android app (below).

### 5. Web UI (optional)

Mobile browser works for typing + streaming. Voice is best on **HTTPS** (Tailscale Serve) in Chrome.

```text
https://YOUR-PC.YOUR-TAILNET.ts.net/
```

---

## Android app

Native **Kotlin + Jetpack Compose** client in [`android/`](android/):

- Session tabs, busy indicators  
- **Thinking** (expand/collapse) and **tool** cards  
- **Markwon** markdown rendering  
- System **STT** + **TTS** with **voice picker**  
- **Cancel turn** and **send-while-busy** (interrupt + inject new instruction)  
- QR pair + encrypted token storage  

### Build once on the PC

```powershell
# SDK (first time)
powershell -ExecutionPolicy Bypass -File .\scripts\install-android-sdk.ps1

cd android
$env:JAVA_HOME = "C:\Program Files\Android\openjdk\jdk-21.0.8"   # or your JDK 17+
.\gradlew.bat :app:assembleDebug
```

### Publish APK to the bridge (no USB for updates)

```powershell
# from repo root — builds (unless -SkipBuild) and copies into releases/
powershell -ExecutionPolicy Bypass -File .\scripts\publish-apk.ps1
```

Then on the phone (Tailscale on), open Chrome:

```text
https://YOUR-PC.YOUR-TAILNET.ts.net/download
```

Tap **Download APK**, allow install from the browser, update.  
Direct file: `/download/grok-remote.apk`.

USB still works if you prefer:

```powershell
adb install -r android\app\build\outputs\apk\debug\app-debug.apk
```

---

## Day-to-day use

1. Leave the PC powered; tasks keep **agent serve** + **bridge** up after logon.  
2. Phone: Tailscale **Connected**.  
3. Open the app (or HTTPS bookmark).  
4. Switch sessions, chat, expand thinking, watch tools, cancel or inject midstream.  
5. After you ship a new APK: `publish-apk.ps1` → phone `/download` → install.

---

## Endpoints (bridge)

| Path | Purpose |
|------|---------|
| `/` | Web chat UI |
| `/pair` | **PC loopback only** — QR pairing |
| `/pair/qr.png` | QR image (loopback only) |
| `/download` | Install page for the Android APK |
| `/download/grok-remote.apk` | APK file |
| `/ws?token=…` | App / web realtime channel |
| `/api/health` | Liveness (`agentAlive`, transport, sessions) |

---

## Project layout

```text
grok-remote/
  server/           # FastAPI bridge (ACP client, history, pair, download)
  web/              # Mobile web UI
  android/          # Compose app
  scripts/          # SDK install, APK publish
  config.example.json
  install-startup.ps1   # registers GrokAgentServe + GrokRemoteBridge
  start-agent-serve.ps1
  start-background.ps1
  docs/images/      # optional screenshots for this README
```

---

## Troubleshooting

| Symptom | Check |
|---------|--------|
| Pair page “refused” | Bridge not running → `schtasks /Run /TN GrokRemoteBridge` |
| `/pair` over Tailscale is 403 | Expected — use `http://127.0.0.1:8787/pair` on the PC |
| Phone can’t load HTTPS | Tailscale Serve + both devices Connected |
| Chat connects but agent silent | `GET /api/health` → `agentAlive`; restart `GrokAgentServe` |
| Empty history in app | `history_limit` in config; session UUID must match on-disk Grok session |
| Mic fails in browser | Use HTTPS Serve URL + Chrome; or use the native app STT |
| Bad TTS voice | App top bar → voice chip → pick Neural/Natural/cloud voice |

---

## Security notes

- Keep `config.json` **out of git** (gitignored). Ship only `config.example.json`.  
- Prefer **Tailscale Serve** (private) over **Funnel** (public).  
- `remote_token` / `agent_secret` = full control of the local agent — use long random values.  
- `always_approve` is for unattended remote; tighten if that doesn’t match your risk tolerance.

---

## License / status

Personal / experimental open project. Grok Build remains product of xAI; this repo only orchestrates the **documented** CLI agent interfaces.

Contributions and screenshots welcome — especially fills for the image slots above.
