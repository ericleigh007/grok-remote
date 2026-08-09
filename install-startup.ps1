# Install durable stack:
#   1) GrokAgentServe  - long-lived `grok agent serve` (WebSocket ACP on 127.0.0.1:2419)
#   2) GrokRemoteBridge - phone UI bridge (connects to agent via WebSocket, auto-reconnects)
#
#   powershell -ExecutionPolicy Bypass -File .\install-startup.ps1
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$ps = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
$user = "$env:USERDOMAIN\$env:USERNAME"

function Register-LongTask([string]$Name, [string]$Script, [string]$Desc) {
  Unregister-ScheduledTask -TaskName $Name -Confirm:$false -ErrorAction SilentlyContinue
  $arg = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$Script`""
  $action = New-ScheduledTaskAction -Execute $ps -Argument $arg -WorkingDirectory $Root
  $logon = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
  $logon.Delay = "PT45S"
  $settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -RestartCount 999 `
    -RestartInterval (New-TimeSpan -Minutes 1) `
    -MultipleInstances IgnoreNew
  $principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
  Register-ScheduledTask -TaskName $Name -Action $action -Trigger $logon `
    -Settings $settings -Principal $principal -Description $Desc -Force | Out-Null
  Write-Host "Registered: $Name"
}

$agentScript = Join-Path $Root "start-agent-serve.ps1"
$bridgeScript = Join-Path $Root "start-background.ps1"
if (-not (Test-Path $agentScript)) { throw "Missing $agentScript" }
if (-not (Test-Path $bridgeScript)) { throw "Missing $bridgeScript" }

Register-LongTask "GrokAgentServe" $agentScript "Grok ACP WebSocket server (backend). Local only 127.0.0.1:2419"
Register-LongTask "GrokRemoteBridge" $bridgeScript "Grok Remote phone bridge UI on :8787 (ACP client over WebSocket)"

Write-Host "Starting agent serve..."
Start-ScheduledTask -TaskName GrokAgentServe
$agentUp = $false
for ($i = 1; $i -le 30; $i++) {
  Start-Sleep -Seconds 1
  try {
    $tcp = New-Object System.Net.Sockets.TcpClient
    $iar = $tcp.BeginConnect("127.0.0.1", 2419, $null, $null)
    if ($iar.AsyncWaitHandle.WaitOne(300) -and $tcp.Connected) {
      $tcp.Close(); $agentUp = $true; break
    }
    try { $tcp.Close() } catch {}
  } catch {}
}
if (-not $agentUp) {
  Write-Host "WARNING: agent serve not listening on 2419 yet (check logs\agent-serve.err)"
} else {
  Write-Host "Agent serve OK on 2419"
}

Write-Host "Starting bridge..."
Start-ScheduledTask -TaskName GrokRemoteBridge
$bridgeUp = $false
for ($i = 1; $i -le 45; $i++) {
  Start-Sleep -Seconds 1
  try {
    $h = Invoke-RestMethod http://127.0.0.1:8787/api/health -TimeoutSec 2
    if ($h.ok) {
      Write-Host ("Bridge OK: " + ($h | ConvertTo-Json -Compress))
      $bridgeUp = $true
      break
    }
  } catch {}
}
if (-not $bridgeUp) {
  Write-Host "Bridge failed to come up. Check logs\lifecycle.log and logs\bridge-run.err"
  exit 1
}

$ts = "C:\Program Files\Tailscale\tailscale.exe"
if (Test-Path $ts) {
  & $ts serve --bg http://127.0.0.1:8787 2>$null | Out-Null
  Write-Host "tailscale serve ok"
}

Write-Host ""
Write-Host "Architecture:"
Write-Host "  Phone --HTTPS--> bridge:8787 --WS ACP--> grok agent serve:2419"
Write-Host "Pair: http://127.0.0.1:8787/pair"
Write-Host "Start later:  schtasks /Run /TN GrokAgentServe & schtasks /Run /TN GrokRemoteBridge"
Write-Host "Remove:       .\uninstall-startup.ps1"
