Prebuilt **__VERSION__** so you do not have to compile the Android app or wire the PC tree by hand.

## PC (Windows)

Requires [Grok Build](https://docs.x.ai) and [PowerShell 7](https://aka.ms/powershell) (`pwsh`, not Windows PowerShell 5.1). Python 3.11+ is installed via winget if missing.

```powershell
irm https://github.com/ericleigh007/grok-remote/releases/latest/download/install.ps1 | iex
```

That copies the bridge into `%LOCALAPPDATA%\GrokRemote` (or uses your clone in-place), writes `config.json` tokens, registers **GrokAgentServe** + **GrokRemoteBridge**, and drops the APK where the bridge can serve it.

Then on the PC open `http://127.0.0.1:8787/pair`.

## Android — sideload the APK

This is not on Play Store. The phone will try to stop you.

1. Scan **Install APK** on the PC `/pair` page (or download `grok-remote.apk` from this release, or open `/dl` on the phone).
2. **Samsung Auto Blocker** (on by default on many Galaxy phones): Settings → Security and privacy → **Auto Blocker** → Off.
3. When Android says the source is not allowed: Settings → allow **Chrome** or **Files** to install unknown apps → open the APK again.
4. **Play Protect** "Blocked": More details → **Install anyway**.

Full walkthrough is in the README (Install from a release → Sideload the Android app).

## Also in this zip

`grok-remote-pc.zip` is the same tree the installer downloads: `server/`, `web/`, startup scripts, `config.example.json`.
