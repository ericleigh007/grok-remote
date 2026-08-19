Prebuilt **__VERSION__** — reliability upgrade: the PC stack is now a Windows service instead of brittle at-logon tasks.

## Increased reliability

Scheduled tasks only restarted when the *wrapper* exited with an error. If the wrapper died while Python/`grok` kept the port, a restart treated “port already open” as success and **stopped watching**. The next death stayed down until the next logon (502 from Tailscale, “cannot reach host” from the phone).

**Default install is now a Windows service:**

- **GrokRemote** runs as **LocalSystem** only as a supervisor (restart + health).
- `grok agent serve` and the Python bridge are launched **as your Windows user** via S4U or your interactive session (`CreateProcessAsUser`). **No Windows password is stored.**
- SCM restarts the supervisor on crash; a SYSTEM watchdog every minute force-starts it if :2419/:8787 is dead.

```powershell
irm https://github.com/ericleigh007/grok-remote/releases/latest/download/install.ps1 | iex
```

Opt-in tasks (same supervisor, still no stored password): `install-startup.ps1 -UseScheduledTasks`.

Then on the PC open `http://127.0.0.1:8787/pair`.

## Android — sideload the APK

This is not on Play Store. The phone will try to stop you.

1. Scan **Install APK** on the PC `/pair` page (or download `grok-remote.apk` from this release, or open `/dl` on the phone).
2. **Samsung Auto Blocker** (on by default on many Galaxy phones): Settings → Security and privacy → **Auto Blocker** → Off.
3. When Android says the source is not allowed: Settings → allow **Chrome** or **Files** to install unknown apps → open the APK again.
4. **Play Protect** "Blocked": More details → **Install anyway**.

Full walkthrough is in the README.

## Also in this zip

`grok-remote-pc.zip` is the same tree the installer downloads: `server/`, `web/`, `supervise.ps1`, startup scripts, `config.example.json`.
