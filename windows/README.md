# Grok Remote for Windows (tablet / laptop)

**WinUI 3 + Windows App SDK** host around the Tailscale web UI. Chat/sessions stay in HTML. **TTS/STT use `Windows.Media` (WinRT)** — that is how Windows 11 Natural voices are listed and spoken, on both **x64 and ARM64**.

WPF / SAPI 5 are not used.

Touch and mouse work. Needs the **WebView2** runtime (ships with Edge).

## Why this stack

| Piece | Status |
|--------|--------|
| WPF | Maintained, not the current Windows UI. Fine for old LOB; wrong for a new shell. |
| UWP (as an app model) | Dead for new apps. |
| WinRT APIs (`Windows.Media.SpeechSynthesis`) | Current. Natural voices live here, not in `System.Speech`. |
| WinUI 3 + Windows App SDK | Current native GUI. ARM64 is a first-class RID. |
| WebView2 | Current. Same Chromium engine as Edge, native ARM64. |

## Run (dev)

From the repo, **native to this CPU**:

```powershell
pwsh -File .\windows\publish.ps1 -Rid win-arm64   # Snapdragon / ARM tablet
pwsh -File .\windows\publish.ps1 -Rid win-x64     # typical laptop
.\dist\grok-remote-desktop-win-arm64\GrokRemote.Desktop.exe
```

Or:

```powershell
dotnet run --project .\windows\GrokRemote.Desktop\GrokRemote.Desktop.csproj -c Release -r win-arm64
```

`--url https://YOUR-PC.YOUR-TAILNET.ts.net` if it is not this machine. On this PC it can read `public_host` from `config.json` and inject the token once.

## Voices

The dropdown is `SpeechSynthesizer.AllVoices`. Install more in **Settings → Time & language → Speech** (Natural voices). Changing the dropdown previews through the same API.

Mic uses `Windows.Media.SpeechRecognition` (dictation). Windows may prompt for microphone the first time.
