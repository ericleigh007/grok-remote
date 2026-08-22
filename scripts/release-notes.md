Prebuilt **__VERSION__** — same tree as `master`. PC zip + APK + `install.ps1`.

The bridge still talks to stock **`grok agent serve` over WebSocket** (`:2419`). Phone clients use the bridge WebSocket (`:8787/ws`).

```powershell
irm https://github.com/ericleigh007/grok-remote/releases/latest/download/install.ps1 | iex
```

(`pwsh`, not Windows PowerShell 5.1.)

## Catch up

The desktop TUI and `grok agent serve` are two processes. They share `~/.grok/sessions` on disk, not a live stream. The green **emergency-exit running man** button reloads that disk transcript onto the phone (then best-effort ACP `session/resume`).

## Session search

The picker search box matches titles, folders, config aliases, and (3+ characters) transcripts. You do not need **Show all** first.

## Phone prompts + keyboard

`ask_user_question` is relayed to the phone. If the app is backgrounded, a notification fires. The composer stays above the IME.

## Watchdog

Ports only (`:2419` / `:8787`). A false `agentAlive` no longer restarts the whole service (that dropped live Tailscale WebSockets — HTTP 101 then 502).

## Sideload the APK

Not on Play Store. Phone will try to stop you.

1. PC `http://127.0.0.1:8787/pair` → **Install APK**, or `/dl`, or `grok-remote.apk` on this release
2. Samsung **Auto Blocker** off
3. Allow unknown apps for Chrome/Files
4. Play Protect → **Install anyway**

Then pair from the same `/pair` page. Details in the README.
