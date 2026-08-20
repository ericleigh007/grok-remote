Prebuilt **__VERSION__** — same tree as `master`. PC zip + APK + `install.ps1`.

## Windows service (not logon tasks)

Scheduled tasks only restarted when the *wrapper* failed. If the wrapper died while the child kept the port, a restart exited 0 and nothing watched the next crash (Tailscale **502**).

Default install is the **GrokRemote** Windows service:

- LocalSystem **supervisor only**
- `grok agent serve` and the Python bridge run **as your user** (S4U / session token — **no password stored**)
- SCM restart on crash + SYSTEM watchdog every minute if `:2419` / `:8787` is dead

```powershell
irm https://github.com/ericleigh007/grok-remote/releases/latest/download/install.ps1 | iex
```

(`pwsh`, not Windows PowerShell 5.1.) Opt-in tasks: `install-startup.ps1 -UseScheduledTasks`.

## Sessions on demand

The old stdio habit of auto-resuming every `config.json` project at boot is gone. The picker lists real chats under `~/.grok/sessions`. Last-used is re-entered; otherwise you pick. **Show all** lists every on-disk session. Idle threads stay cold.

## Phone

- Optional **thinking beep** (off by default)
- TTS no longer re-reads the previous reply while the model thinks
- Top bar title is a full-width line (no one-letter wrapping)

## Sideload the APK

Not on Play Store. Phone will try to stop you.

1. PC `http://127.0.0.1:8787/pair` → **Install APK**, or `/dl`, or `grok-remote.apk` on this release
2. Samsung **Auto Blocker** off
3. Allow unknown apps for Chrome/Files
4. Play Protect → **Install anyway**

Then pair from the same `/pair` page. Details in the README.
