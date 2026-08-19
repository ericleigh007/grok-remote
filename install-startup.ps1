# Default: Windows service (LocalSystem supervisor, children as the installing user, no password).
# Opt-in:  -UseScheduledTasks  (same supervisor, S4U task, no stored password).
#
#   pwsh -ExecutionPolicy Bypass -File .\install-startup.ps1
#   pwsh -ExecutionPolicy Bypass -File .\install-startup.ps1 -UseScheduledTasks
param(
  [switch]$UseScheduledTasks
)

$ErrorActionPreference = "Stop"
if ($PSVersionTable.PSVersion.Major -lt 7) {
  throw "PowerShell 7 (pwsh) is required. You are running Windows PowerShell $($PSVersionTable.PSVersion)."
}

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$pwshCandidates = @(
  (Get-Command pwsh -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source),
  (Join-Path $PSHOME "pwsh.exe"),
  "C:\Program Files\PowerShell\7\pwsh.exe"
) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $pwshCandidates) {
  throw "PowerShell 7 (pwsh) is required. Install from https://aka.ms/powershell and re-run."
}
$ps = $pwshCandidates

$IsAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
  [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $IsAdmin) {
  Write-Host "Elevation required to install Grok Remote (service or S4U task)..."
  $arg = "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
  if ($UseScheduledTasks) { $arg += " -UseScheduledTasks" }
  $p = Start-Process -FilePath $ps -Verb RunAs -ArgumentList $arg -Wait -PassThru
  exit $p.ExitCode
}

$Tools = Join-Path $Root "tools"
$Logs = Join-Path $Root "logs"
New-Item -ItemType Directory -Force -Path $Tools | Out-Null
New-Item -ItemType Directory -Force -Path $Logs | Out-Null

$WinSwExe = Join-Path $Tools "GrokRemote.exe"
$WinSwXml = Join-Path $Tools "GrokRemote.xml"
$StatePath = Join-Path $Tools "service-user.json"
$CsPath = Join-Path $Tools "UserProcess.cs"
$DllPath = Join-Path $Tools "GrokRemote.UserProcess.dll"
$Supervise = Join-Path $Root "supervise.ps1"
$Watchdog = Join-Path $Root "watchdog.ps1"
if (-not (Test-Path $Supervise)) { throw "Missing $Supervise" }
if (-not (Test-Path $Watchdog)) { throw "Missing $Watchdog" }
if (-not (Test-Path $CsPath)) { throw "Missing $CsPath" }

$grokExe = Join-Path $env:USERPROFILE ".grok\bin\grok.exe"
if (-not (Test-Path $grokExe)) {
  $cmd = Get-Command grok -ErrorAction SilentlyContinue
  if ($cmd) { $grokExe = $cmd.Source }
}
if (-not (Test-Path $grokExe)) { throw "grok.exe not found. Install Grok Build and log in first." }

Write-Host "Compiling user-token helper..."
if (Test-Path $DllPath) { Remove-Item $DllPath -Force }
Add-Type -Path $CsPath -OutputAssembly $DllPath -ErrorAction Stop
if (-not (Test-Path $DllPath)) { throw "Failed to compile $DllPath" }

$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$state = [ordered]@{
  userName    = $env:USERNAME
  userDomain  = $env:USERDOMAIN
  userProfile = $env:USERPROFILE
  sid         = $sid
  grokExe     = $grokExe
  installedAt = (Get-Date).ToString("o")
  mode        = $(if ($UseScheduledTasks) { "scheduled-task" } else { "service" })
}
$state | ConvertTo-Json | Set-Content -Path $StatePath -Encoding utf8

function Stop-PortOwner([int]$Port) {
  try {
    $c = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($c -and $c.OwningProcess) {
      Write-Host "Stopping pid $($c.OwningProcess) on port $Port"
      Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue
    }
  } catch {}
}

function Register-Watchdog {
  Unregister-ScheduledTask -TaskName GrokRemoteWatchdog -Confirm:$false -ErrorAction SilentlyContinue
  $wdArg = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$Watchdog`""
  $wdAction = New-ScheduledTaskAction -Execute $ps -Argument $wdArg -WorkingDirectory $Root
  $wdOnce = New-ScheduledTaskTrigger -Once -At ((Get-Date).AddMinutes(1)) -RepetitionInterval (New-TimeSpan -Minutes 1) -RepetitionDuration (New-TimeSpan -Days 9999)
  $wdBoot = New-ScheduledTaskTrigger -AtStartup
  $wdBoot.Delay = "PT1M"
  $wdSettings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 2) `
    -MultipleInstances IgnoreNew `
    -RestartCount 3 `
    -RestartInterval (New-TimeSpan -Minutes 1)
  $wdPrincipal = New-ScheduledTaskPrincipal -UserId "SYSTEM" -LogonType ServiceAccount -RunLevel Highest
  Register-ScheduledTask -TaskName GrokRemoteWatchdog -Action $wdAction `
    -Trigger @($wdBoot, $wdOnce) -Settings $wdSettings -Principal $wdPrincipal `
    -Description "Every minute: if :2419/:8787 is dead, start/restart Grok Remote" -Force | Out-Null
  Write-Host "Registered: GrokRemoteWatchdog (SYSTEM, every 1 min)"
}

function Wait-Stack {
  Write-Host "Waiting for agent serve :2419..."
  $agentUp = $false
  for ($i = 1; $i -le 40; $i++) {
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
  if ($agentUp) { Write-Host "Agent serve OK on 2419" } else { Write-Host "WARNING: 2419 not listening yet (check logs\supervisor.log)" }

  Write-Host "Waiting for bridge :8787..."
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
    Write-Host "Bridge failed to come up. Check logs\supervisor.log"
    exit 1
  }
  $ts = "C:\Program Files\Tailscale\tailscale.exe"
  if (Test-Path $ts) {
    & $ts serve --bg http://127.0.0.1:8787 2>$null | Out-Null
    Write-Host "tailscale serve ok"
  }
}

# Remove leftover split tasks from 0.1.x
foreach ($name in @("GrokAgentServe", "GrokRemoteBridge")) {
  Stop-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue
  Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue
}

if ($UseScheduledTasks) {
  Write-Host "Mode: scheduled tasks (S4U, no stored password). Prefer the service unless you opted into this."
  $existing = Get-Service -Name GrokRemote -ErrorAction SilentlyContinue
  if ($existing) {
    if (Test-Path $WinSwExe) { & $WinSwExe stop 2>$null | Out-Null; & $WinSwExe uninstall 2>$null | Out-Null }
    Stop-Service GrokRemote -Force -ErrorAction SilentlyContinue
    sc.exe delete GrokRemote | Out-Null
  }
  Stop-PortOwner 8787
  Stop-PortOwner 2419

  Unregister-ScheduledTask -TaskName GrokRemoteSupervisor -Confirm:$false -ErrorAction SilentlyContinue
  $arg = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$Supervise`""
  $action = New-ScheduledTaskAction -Execute $ps -Argument $arg -WorkingDirectory $Root
  $logon = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
  $logon.Delay = "PT20S"
  $boot = New-ScheduledTaskTrigger -AtStartup
  $boot.Delay = "PT45S"
  $repeat = New-ScheduledTaskTrigger -Once -At ((Get-Date).AddMinutes(1)) -RepetitionInterval (New-TimeSpan -Minutes 1) -RepetitionDuration (New-TimeSpan -Days 9999)
  $settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -RestartCount 999 `
    -RestartInterval (New-TimeSpan -Minutes 1) `
    -MultipleInstances IgnoreNew
  # S4U: run as this user whether or not they are logged on, without saving a password.
  $principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType S4U -RunLevel Limited
  Register-ScheduledTask -TaskName GrokRemoteSupervisor -Action $action `
    -Trigger @($logon, $boot, $repeat) -Settings $settings -Principal $principal `
    -Description "Grok Remote supervisor (agent :2419 + bridge :8787). S4U, no stored password." -Force | Out-Null
  Write-Host "Registered: GrokRemoteSupervisor (S4U)"
  Register-Watchdog
  Start-ScheduledTask -TaskName GrokRemoteSupervisor
  Wait-Stack
  Write-Host ""
  Write-Host "Installed as scheduled tasks (opt-in). Service mode is the default and more reliable."
  Write-Host "  Restart: Start-ScheduledTask GrokRemoteSupervisor"
  Write-Host "  Remove:  pwsh -ExecutionPolicy Bypass -File .\uninstall-startup.ps1"
  exit 0
}

# --- service mode (default): LocalSystem supervisor, children impersonate the user ---
Write-Host "Mode: Windows service (LocalSystem supervisor; grok + bridge run as $env:USERDOMAIN\$env:USERNAME; no password stored)."

Unregister-ScheduledTask -TaskName GrokRemoteSupervisor -Confirm:$false -ErrorAction SilentlyContinue

$existing = Get-Service -Name GrokRemote -ErrorAction SilentlyContinue
if ($existing) {
  Write-Host "Stopping existing GrokRemote service..."
  if (Test-Path $WinSwExe) { & $WinSwExe stop 2>$null | Out-Null }
  Stop-Service GrokRemote -Force -ErrorAction SilentlyContinue
  Start-Sleep -Seconds 2
}
Stop-PortOwner 8787
Stop-PortOwner 2419

$WinSwUrl = "https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW-x64.exe"
if (-not (Test-Path $WinSwExe)) {
  Write-Host "Downloading WinSW service wrapper..."
  try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}
  Invoke-WebRequest -Uri $WinSwUrl -OutFile $WinSwExe -UseBasicParsing
}
if (-not (Test-Path $WinSwExe)) { throw "WinSW missing at $WinSwExe" }

$xml = @"
<service>
  <id>GrokRemote</id>
  <name>Grok Remote</name>
  <description>LocalSystem supervisor for Grok Remote. Spawns grok agent serve and the phone bridge as the installing user via S4U/session token (no password stored). Restarts both on failure.</description>
  <executable>$ps</executable>
  <arguments>-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "$Supervise"</arguments>
  <workingdirectory>$Root</workingdirectory>
  <logpath>$Logs</logpath>
  <log mode="roll-by-size">
    <sizeThreshold>10240</sizeThreshold>
    <keepFiles>8</keepFiles>
  </log>
  <onfailure action="restart" delay="3 sec"/>
  <onfailure action="restart" delay="5 sec"/>
  <onfailure action="restart" delay="10 sec"/>
  <resetfailure>1 hour</resetfailure>
  <startmode>Automatic</startmode>
  <delayedAutoStart>true</delayedAutoStart>
  <stoptimeout>20 sec</stoptimeout>
  <stopparentprocessfirst>true</stopparentprocessfirst>
</service>
"@
Set-Content -Path $WinSwXml -Value $xml -Encoding UTF8

if ($existing) {
  Write-Host "Refreshing GrokRemote service wrapper..."
  & $WinSwExe uninstall 2>$null | Out-Null
  Start-Sleep -Seconds 1
}

Write-Host "Installing GrokRemote service..."
& $WinSwExe install
$svcCheck = Get-Service GrokRemote -ErrorAction SilentlyContinue
if (-not $svcCheck) { throw "WinSW install failed (exit $LASTEXITCODE)" }

cmd.exe /c "sc.exe config GrokRemote start= delayed-auto" | Out-Null
cmd.exe /c "sc.exe failure GrokRemote reset= 86400 actions= restart/3000/restart/3000/restart/3000" | Out-Null
cmd.exe /c "sc.exe failureflag GrokRemote 1" | Out-Null

Write-Host "Starting GrokRemote service..."
& $WinSwExe start
Start-Sleep -Seconds 2

Register-Watchdog
Wait-Stack

Write-Host ""
Write-Host "Grok Remote reliability stack:"
Write-Host "  Service   GrokRemote (LocalSystem supervisor, SCM restart on crash)"
Write-Host "  Children  grok agent serve + Python bridge as $env:USERDOMAIN\$env:USERNAME (S4U / session token, no password)"
Write-Host "  Watchdog  SYSTEM every 1 min if ports die"
Write-Host "  Pair      http://127.0.0.1:8787/pair"
Write-Host "  Status    Get-Service GrokRemote"
Write-Host "  Logs      $Logs\supervisor.log"
Write-Host "  Restart   Restart-Service GrokRemote"
Write-Host "  Tasks     pwsh -File .\install-startup.ps1 -UseScheduledTasks"
Write-Host "  Remove    pwsh -File .\uninstall-startup.ps1"
