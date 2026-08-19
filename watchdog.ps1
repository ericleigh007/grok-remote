# Independent safety net. Runs every minute as SYSTEM.
# If the GrokRemote service is stopped, start it.
# If ports 2419/8787 are still dead after a short wait, restart the service.
# This catches hung-but-alive wrappers that SCM restart-on-crash would miss.
$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$LogDir = Join-Path $Root "logs"
$LogFile = Join-Path $LogDir "watchdog.log"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

function Write-Log([string]$Message) {
  $line = "{0} {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Message
  try { Add-Content -Path $LogFile -Value $line -Encoding utf8 } catch {}
}

function Test-Listen([int]$Port) {
  try {
    $tcp = New-Object System.Net.Sockets.TcpClient
    $iar = $tcp.BeginConnect("127.0.0.1", $Port, $null, $null)
    $ok = $iar.AsyncWaitHandle.WaitOne(500)
    $connected = $ok -and $tcp.Connected
    try { $tcp.Close() } catch {}
    return [bool]$connected
  } catch {
    return $false
  }
}

function Test-Healthy {
  if (-not (Test-Listen 2419)) { return $false }
  if (-not (Test-Listen 8787)) { return $false }
  try {
    $h = Invoke-RestMethod http://127.0.0.1:8787/api/health -TimeoutSec 2
    return [bool]($h.ok -and $h.agentAlive)
  } catch {
    return $false
  }
}

function Start-Stack {
  $svc = Get-Service -Name GrokRemote -ErrorAction SilentlyContinue
  if ($svc) {
    if ($svc.Status -ne "Running") {
      Write-Log "service status=$($svc.Status) - starting"
      try { Start-Service GrokRemote } catch { Write-Log "Start-Service failed: $($_.Exception.Message)" }
    }
    return "service"
  }
  $task = Get-ScheduledTask -TaskName GrokRemoteSupervisor -ErrorAction SilentlyContinue
  if ($task) {
    Write-Log "starting scheduled task GrokRemoteSupervisor"
    try { Start-ScheduledTask -TaskName GrokRemoteSupervisor } catch { Write-Log "Start-ScheduledTask failed: $($_.Exception.Message)" }
    return "task"
  }
  Write-Log "neither GrokRemote service nor GrokRemoteSupervisor task is installed"
  return $null
}

function Restart-Stack([string]$Mode) {
  if ($Mode -eq "service") {
    Write-Log "Restart-Service GrokRemote"
    try { Restart-Service GrokRemote -Force } catch {
      Write-Log "Restart-Service failed: $($_.Exception.Message)"
      try { Start-Service GrokRemote } catch {}
    }
    return
  }
  if ($Mode -eq "task") {
    Write-Log "Stop+Start GrokRemoteSupervisor"
    try { Stop-ScheduledTask -TaskName GrokRemoteSupervisor } catch {}
    Start-Sleep -Seconds 1
    try { Start-ScheduledTask -TaskName GrokRemoteSupervisor } catch { Write-Log "Start-ScheduledTask failed: $($_.Exception.Message)" }
  }
}

$mode = Start-Stack
if (-not $mode) { exit 1 }
Start-Sleep -Seconds 8

if (Test-Healthy) { exit 0 }

Write-Log "unhealthy after start/check - waiting 10s"
Start-Sleep -Seconds 10
if (Test-Healthy) { exit 0 }

Restart-Stack $mode
Start-Sleep -Seconds 12
if (Test-Healthy) {
  Write-Log "healthy after restart"
  exit 0
}
Write-Log "still unhealthy after restart (2419=$(Test-Listen 2419) 8787=$(Test-Listen 8787))"
exit 1
