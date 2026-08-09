# Keep Grok Remote listening on 8787. Safe to run every minute.
$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$LogDir = Join-Path $Root "logs"
$LogFile = Join-Path $LogDir "watchdog.log"
$Port = 8787

if (-not (Test-Path $LogDir)) {
  New-Item -ItemType Directory -Path $LogDir | Out-Null
}

function Write-Log([string]$Message) {
  $line = "{0} {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Message
  Add-Content -Path $LogFile -Value $line -Encoding utf8
}

function Test-Listening {
  try {
    $tcp = New-Object System.Net.Sockets.TcpClient
    $iar = $tcp.BeginConnect("127.0.0.1", $Port, $null, $null)
    $ok = $iar.AsyncWaitHandle.WaitOne(500)
    if ($ok -and $tcp.Connected) {
      $tcp.Close()
      return $true
    }
    try { $tcp.Close() } catch {}
  } catch {}
  return $false
}

if (Test-Listening) {
  exit 0
}

Write-Log "Port $Port not listening — starting bridge"

$py = Join-Path $Root ".venv\Scripts\python.exe"
if (-not (Test-Path $py)) {
  Write-Log "Missing venv python at $py"
  exit 1
}

$serverDir = Join-Path $Root "server"
$outLog = Join-Path $LogDir "bridge-run.log"
$errLog = Join-Path $LogDir "bridge-run.err"

# Detached start so the scheduled task can exit while python keeps running
$cmd = "cd /d `"$serverDir`" && set PYTHONUNBUFFERED=1 && set GROK_DISABLE_AUTOUPDATER=1 && `"$py`" main.py >> `"$outLog`" 2>> `"$errLog`""
Start-Process -FilePath "cmd.exe" -ArgumentList "/c", $cmd -WindowStyle Hidden | Out-Null

Start-Sleep -Seconds 10
if (Test-Listening) {
  Write-Log "Bridge is up"
  $ts = "C:\Program Files\Tailscale\tailscale.exe"
  if (Test-Path $ts) {
    try { & $ts serve --bg "http://127.0.0.1:$Port" 2>$null | Out-Null } catch {}
  }
  exit 0
}

Write-Log "Bridge failed to start (port still closed). See bridge-run.err"
exit 1
