Prebuilt **__VERSION__** — same tree as `master`. PC zip + APK + **Windows desktop (ARM64 and x64)** + `install.ps1`.

The bridge still talks to stock **`grok agent serve` over WebSocket** (`:2419`). Phone and Windows-tablet clients use the bridge WebSocket (`:8787/ws`).

```powershell
irm https://github.com/ericleigh007/grok-remote/releases/latest/download/install.ps1 | iex
```

(`pwsh`, not Windows PowerShell 5.1.)

## Windows-native app (tablets, including ARM)

Leave the grok-main PC at home. On a weekend — or from the other side of the Mediterranean — open **Grok Remote** on a Windows tablet over Tailscale and keep the same sessions going.

- **WinUI 3 + WebView2** (not WPF). Touch and mouse.  
- **WinRT Natural voices**, not browser `speechSynthesis`.  
- Native **`grok-remote-desktop-win-arm64.zip`** for Snapdragon tablets, **`win-x64.zip`** for typical laptops. Extract and run `GrokRemote.Desktop.exe`.  
- First run: `https://YOUR-PC.YOUR-TAILNET.ts.net`, then the usual token.

## Exit session

Each remote tab has an **×**. That unloads the session from the phone (and the live bridge). The transcript stays on disk — pick it again from **Sessions**. Leaving the active session returns to the picker.

## Hold your place

Long thinking (or a long reply) no longer yanks the list back to the top of the bubble. If you scroll to read, that position holds. A **Latest** down-arrow appears when you are not at the end.

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
