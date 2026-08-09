# Long-lived Grok ACP WebSocket server (backend).
# Run via Scheduled Task: GrokAgentServe
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$LogDir = Join-Path $Root "logs"
$LifeLog = Join-Path $LogDir "agent-serve-lifecycle.log"
$OutLog = Join-Path $LogDir "agent-serve.log"
$ErrLog = Join-Path $LogDir "agent-serve.err"
$ConfigPath = Join-Path $Root "config.json"

if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir | Out-Null }

function Write-Life([string]$Message) {
  $line = "{0} pid={1} {2}" -f (Get-Date -Format "o"), $PID, $Message
  Add-Content -Path $LifeLog -Value $line -Encoding utf8
}

$bind = "127.0.0.1:2419"
$secret = $env:GROK_AGENT_SECRET
if (Test-Path $ConfigPath) {
  $cfg = Get-Content $ConfigPath -Raw | ConvertFrom-Json
  if ($cfg.agent_bind) { $bind = [string]$cfg.agent_bind }
  if ($cfg.agent_secret) { $secret = [string]$cfg.agent_secret }
}
if (-not $secret) {
  Write-Life "ERROR: set agent_secret in config.json or GROK_AGENT_SECRET env"
  throw "Missing agent_secret (copy config.example.json to config.json and set secrets)"
}

# Already listening?
$port = 2419
if ($bind -match ':(\d+)$') { $port = [int]$Matches[1] }
try {
  $tcp = New-Object System.Net.Sockets.TcpClient
  $iar = $tcp.BeginConnect("127.0.0.1", $port, $null, $null)
  $ok = $iar.AsyncWaitHandle.WaitOne(300)
  if ($ok -and $tcp.Connected) {
    $tcp.Close()
    Write-Life "port $port already open - exit"
    exit 0
  }
  try { $tcp.Close() } catch {}
} catch {}

$env:GROK_AGENT_SECRET = $secret
$env:GROK_DISABLE_AUTOUPDATER = "1"
Write-Life "starting: grok agent --always-approve serve --bind $bind"
cmd /c "grok agent --always-approve serve --bind $bind --secret $secret >> `"$OutLog`" 2>> `"$ErrLog`""
$code = $LASTEXITCODE
Write-Life "agent serve exited code=$code"
exit $code
