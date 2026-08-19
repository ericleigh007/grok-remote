# Owns grok agent serve (:2419) and the phone bridge (:8787).
# Never exits on child failure — restarts the dead/hung process.
# Run by the GrokRemote Windows service (WinSW). Do not use as a one-shot.
$ErrorActionPreference = "Continue"
if ($PSVersionTable.PSVersion.Major -lt 7) {
  throw "PowerShell 7 (pwsh) is required."
}

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$LogDir = Join-Path $Root "logs"
$LifeLog = Join-Path $LogDir "supervisor.log"
$StatePath = Join-Path $Root "tools\service-user.json"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $Root "tools") | Out-Null

function Write-Life([string]$Message) {
  $line = "{0} pid={1} {2}" -f (Get-Date -Format "o"), $PID, $Message
  try { Add-Content -Path $LifeLog -Value $line -Encoding utf8 } catch {}
}

$mutex = $null
try {
  $mutex = New-Object System.Threading.Mutex($false, "Global\GrokRemoteSupervisor")
  if (-not $mutex.WaitOne(0)) {
    Write-Life "another supervisor holds the mutex - exiting"
    exit 0
  }
} catch {
  Write-Life "mutex warning: $($_.Exception.Message)"
}

$userProfile = $env:USERPROFILE
$grokExe = Join-Path $userProfile ".grok\bin\grok.exe"
$script:UserName = $env:USERNAME
$script:UserDomain = $env:USERDOMAIN
$script:UserSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
if (Test-Path $StatePath) {
  try {
    $st = Get-Content $StatePath -Raw | ConvertFrom-Json
    if ($st.userProfile) { $userProfile = [string]$st.userProfile }
    if ($st.grokExe -and (Test-Path $st.grokExe)) { $grokExe = [string]$st.grokExe }
    if ($st.userName) { $script:UserName = [string]$st.userName }
    if ($st.userDomain) { $script:UserDomain = [string]$st.userDomain }
    if ($st.sid) { $script:UserSid = [string]$st.sid }
  } catch {}
}

$script:AsSystem = $false
$dll = Join-Path $Root "tools\GrokRemote.UserProcess.dll"
if (Test-Path $dll) {
  try {
    Add-Type -Path $dll
    $script:AsSystem = [GrokRemote.UserProcess]::CurrentProcessIsSystem()
  } catch {
    Write-Life "UserProcess.dll load failed: $($_.Exception.Message)"
  }
}
$env:USERPROFILE = $userProfile
$env:HOME = $userProfile
$env:APPDATA = Join-Path $userProfile "AppData\Roaming"
$env:LOCALAPPDATA = Join-Path $userProfile "AppData\Local"
$env:GROK_DISABLE_AUTOUPDATER = "1"
$env:PYTHONUNBUFFERED = "1"
$env:Path = "$(Join-Path $userProfile '.grok\bin');$env:Path"

$ConfigPath = Join-Path $Root "config.json"
$py = Join-Path $Root ".venv\Scripts\python.exe"
$serverDir = Join-Path $Root "server"
$ts = "C:\Program Files\Tailscale\tailscale.exe"

$script:AgentProc = $null
$script:BridgeProc = $null
$script:AgentDeadChecks = 0
$script:LastTs = [datetime]::MinValue
$script:Stopping = $false

function Test-Listen([int]$Port) {
  try {
    $tcp = New-Object System.Net.Sockets.TcpClient
    $iar = $tcp.BeginConnect("127.0.0.1", $Port, $null, $null)
    $ok = $iar.AsyncWaitHandle.WaitOne(400)
    $connected = $ok -and $tcp.Connected
    try { $tcp.Close() } catch {}
    return [bool]$connected
  } catch {
    return $false
  }
}

function Get-ListenPid([int]$Port) {
  try {
    $c = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
      Select-Object -First 1
    if ($c) { return [int]$c.OwningProcess }
  } catch {}
  return $null
}

function Stop-Tree([Nullable[int]]$ProcId) {
  if (-not $ProcId -or $ProcId -le 0) { return }
  try {
    $children = Get-CimInstance Win32_Process -Filter "ParentProcessId=$ProcId" -ErrorAction SilentlyContinue
    foreach ($ch in $children) { Stop-Tree ([int]$ch.ProcessId) }
  } catch {}
  try { Stop-Process -Id $ProcId -Force -ErrorAction SilentlyContinue } catch {}
}

function Stop-Port([int]$Port) {
  $owner = Get-ListenPid $Port
  if ($owner) {
    Write-Life "killing pid $owner on port $Port"
    Stop-Tree $owner
  }
  Start-Sleep -Milliseconds 400
}

function Stop-Tracked($proc) {
  if ($null -eq $proc) { return }
  try {
    if (-not $proc.HasExited) { Stop-Tree ([int]$proc.Id) }
  } catch {}
}

function Read-Config {
  $bind = "127.0.0.1:2419"
  $secret = $env:GROK_AGENT_SECRET
  $agentPort = 2419
  $bridgePort = 8787
  if (Test-Path $ConfigPath) {
    $cfg = Get-Content $ConfigPath -Raw | ConvertFrom-Json
    if ($cfg.agent_bind) { $bind = [string]$cfg.agent_bind }
    if ($cfg.agent_secret) { $secret = [string]$cfg.agent_secret }
    if ($cfg.bind_port) { $bridgePort = [int]$cfg.bind_port }
  }
  if ($bind -match ':(\d+)$') { $agentPort = [int]$Matches[1] }
  if (-not $secret) { throw "Missing agent_secret in config.json" }
  return [pscustomobject]@{
    Bind       = $bind
    Secret     = $secret
    AgentPort  = $agentPort
    BridgePort = $bridgePort
  }
}

function Start-LoggedProcess([string]$File, [string]$Arguments, [string]$WorkDir, [string]$OutLog, [string]$ErrLog) {
  foreach ($f in @($OutLog, $ErrLog)) {
    if (Test-Path $f) {
      try { [IO.File]::Open($f, 'Open', 'ReadWrite', 'ReadWrite').Close() } catch {}
    }
  }
  $p = Start-Process -FilePath $File -ArgumentList $Arguments -WorkingDirectory $WorkDir `
    -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput $OutLog -RedirectStandardError $ErrLog
  return $p
}

function Start-AsInstalledUser([string]$File, [string]$Arguments, [string]$WorkDir, [string]$OutLog, [string]$ErrLog) {
  if (-not $script:AsSystem) {
    return Start-LoggedProcess $File $Arguments $WorkDir $OutLog $ErrLog
  }
  $tok = [GrokRemote.UserProcess]::AcquireToken($script:UserDomain, $script:UserName, $script:UserSid)
  if ($tok -eq [IntPtr]::Zero) {
    throw "No token for $($script:UserDomain)\$($script:UserName): $([GrokRemote.UserProcess]::LastError)"
  }
  try {
    $id = [GrokRemote.UserProcess]::Start($tok, $File, $Arguments, $WorkDir, $OutLog, $ErrLog)
    Write-Life "CreateProcessAsUser pid=$id via $([GrokRemote.UserProcess]::LastError)"
    return Get-Process -Id $id
  } finally {
    [GrokRemote.UserProcess]::CloseToken($tok)
  }
}

function Ensure-Venv {
  if (Test-Path $py) { return }
  Write-Life "creating venv"
  $launcher = Get-Command python -ErrorAction SilentlyContinue
  if (-not $launcher) { throw "python not on PATH; cannot create venv" }
  & $launcher.Source -m venv (Join-Path $Root ".venv")
  & $py -m pip install -q --upgrade pip
  & $py -m pip install -q -r (Join-Path $Root "server\requirements.txt")
}

function Agent-Alive($proc) {
  if ($null -eq $proc) { return $false }
  try { return -not $proc.HasExited } catch { return $false }
}

function Ensure-Agent($cfg) {
  $alive = Agent-Alive $script:AgentProc
  $listen = Test-Listen $cfg.AgentPort
  if ($alive -and $listen) { return }

  Write-Life "agent needs restart (processAlive=$alive listen=$listen)"
  Stop-Tracked $script:AgentProc
  $script:AgentProc = $null
  Stop-Port $cfg.AgentPort

  if (-not (Test-Path $grokExe)) { throw "grok.exe not found at $grokExe" }
  $env:GROK_AGENT_SECRET = $cfg.Secret
  $args = "agent --always-approve serve --bind $($cfg.Bind) --secret $($cfg.Secret)"
  $script:AgentProc = Start-AsInstalledUser $grokExe $args $Root `
    (Join-Path $LogDir "agent-serve.out") (Join-Path $LogDir "agent-serve.err")
  Write-Life "started grok agent serve pid=$($script:AgentProc.Id) as $($script:UserDomain)\$($script:UserName)"
}

function Ensure-Bridge($cfg) {
  $alive = Agent-Alive $script:BridgeProc
  $listen = Test-Listen $cfg.BridgePort
  if ($alive -and $listen) { return }

  Write-Life "bridge needs restart (processAlive=$alive listen=$listen)"
  Stop-Tracked $script:BridgeProc
  $script:BridgeProc = $null
  Stop-Port $cfg.BridgePort
  Ensure-Venv

  $script:BridgeProc = Start-AsInstalledUser $py "main.py" $serverDir `
    (Join-Path $LogDir "bridge-run.log") (Join-Path $LogDir "bridge-run.err")
  Write-Life "started bridge pid=$($script:BridgeProc.Id) as $($script:UserDomain)\$($script:UserName)"
}

function Check-AgentHealth($cfg) {
  if (-not (Test-Listen $cfg.BridgePort)) { return }
  try {
    $h = Invoke-RestMethod "http://127.0.0.1:$($cfg.BridgePort)/api/health" -TimeoutSec 2
    if ($h.agentAlive) {
      $script:AgentDeadChecks = 0
      return
    }
  } catch {}
  $script:AgentDeadChecks++
  if ($script:AgentDeadChecks -ge 4) {
    Write-Life "health agentAlive=false x$($script:AgentDeadChecks) - restarting agent"
    Stop-Tracked $script:AgentProc
    $script:AgentProc = $null
    Stop-Port $cfg.AgentPort
    $script:AgentDeadChecks = 0
  }
}

function Ensure-Tailscale($cfg) {
  if (-not (Test-Path $ts)) { return }
  if ((Get-Date) - $script:LastTs -lt [TimeSpan]::FromSeconds(60)) { return }
  $script:LastTs = Get-Date
  try { & $ts serve --bg "http://127.0.0.1:$($cfg.BridgePort)" 2>$null | Out-Null } catch {}
}

function Stop-All {
  $script:Stopping = $true
  Write-Life "supervisor stopping - killing children"
  Stop-Tracked $script:BridgeProc
  Stop-Tracked $script:AgentProc
  try {
    $cfg = Read-Config
    Stop-Port $cfg.BridgePort
    Stop-Port $cfg.AgentPort
  } catch {}
}

Register-EngineEvent PowerShell.Exiting -Action { Stop-All } | Out-Null
try {
  [Console]::TreatControlCAsInput = $false
} catch {}
$null = Register-ObjectEvent -InputObject ([AppDomain]::CurrentDomain) -EventName ProcessExit -Action { } -ErrorAction SilentlyContinue

Write-Life "supervisor start root=$Root user=$($script:UserDomain)\$($script:UserName) sid=$($script:UserSid) system=$($script:AsSystem) grok=$grokExe"
Ensure-Venv

try {
  while (-not $script:Stopping) {
    try {
      $cfg = Read-Config
      Ensure-Agent $cfg
      Start-Sleep -Seconds 1
      Ensure-Bridge $cfg
      Check-AgentHealth $cfg
      Ensure-Tailscale $cfg
    } catch {
      Write-Life "loop error: $($_.Exception.Message)"
    }
    Start-Sleep -Seconds 5
  }
} finally {
  Stop-All
  if ($mutex) { try { $mutex.ReleaseMutex() } catch {}; $mutex.Dispose() }
  Write-Life "supervisor exit"
}
