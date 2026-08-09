# Long-running host for the Grok Remote bridge.
# MUST be started via Scheduled Task so it is not killed with a Grok agent shell:
#   schtasks /Run /TN GrokRemoteBridge
#   or:  .\install-startup.ps1
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$LogDir = Join-Path $Root "logs"
$OutLog = Join-Path $LogDir "bridge-run.log"
$ErrLog = Join-Path $LogDir "bridge-run.err"
$LifeLog = Join-Path $LogDir "lifecycle.log"
$Port = 8787

if (-not (Test-Path $LogDir)) {
  New-Item -ItemType Directory -Path $LogDir | Out-Null
}

function Write-Life([string]$Message) {
  $line = "{0} pid={1} {2}" -f (Get-Date -Format "o"), $PID, $Message
  Add-Content -Path $LifeLog -Value $line -Encoding utf8
}

function Test-PortOpen([int]$P) {
  try {
    $tcp = New-Object System.Net.Sockets.TcpClient
    $iar = $tcp.BeginConnect("127.0.0.1", $P, $null, $null)
    $ok = $iar.AsyncWaitHandle.WaitOne(400)
    $connected = $ok -and $tcp.Connected
    try { $tcp.Close() } catch {}
    return $connected
  } catch {
    return $false
  }
}

Write-Life "start-background begin"

if (Test-PortOpen $Port) {
  Write-Life "port $Port already open - exiting (no second instance)"
  exit 0
}

$py = Join-Path $Root ".venv\Scripts\python.exe"
if (-not (Test-Path $py)) {
  Write-Life "creating venv"
  python -m venv (Join-Path $Root ".venv")
}
try {
  & $py -m pip install -q -r (Join-Path $Root "server\requirements.txt")
} catch {
  Write-Life "pip warning"
}

$env:PYTHONUNBUFFERED = "1"
$env:GROK_DISABLE_AUTOUPDATER = "1"
Set-Location (Join-Path $Root "server")

Write-Life "blocking exec python main.py"
# Stay alive until python exits. Task Scheduler restarts on failure.
cmd /c "`"$py`" main.py >> `"$OutLog`" 2>> `"$ErrLog`""
$code = $LASTEXITCODE
Write-Life "python exited code=$code"
exit $code
