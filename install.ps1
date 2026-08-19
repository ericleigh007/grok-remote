# Install the Grok Remote PC piece: Python bridge + grok agent serve.
# Default: Windows service (LocalSystem supervisor; children as your user, no password).
# Works three ways:
#   1) From a clone or extracted zip:
#        pwsh -ExecutionPolicy Bypass -File .\install.ps1
#   2) Copy into %LOCALAPPDATA%\GrokRemote (default when this is not a git checkout):
#        pwsh -ExecutionPolicy Bypass -File .\install.ps1 -InstallDir "$env:LOCALAPPDATA\GrokRemote"
#   3) One-liner from a GitHub release (no clone):
#        irm https://github.com/ericleigh007/grok-remote/releases/latest/download/install.ps1 | iex
#
# Prerequisites: Windows, Grok Build (`grok` on PATH, already logged in).
# Python 3.11+ is installed via winget if missing.
param(
  [string]$InstallDir = "",
  [string]$PublicHost = "",
  [string]$DefaultCwd = "",
  [switch]$InPlace,
  [switch]$SkipApk,
  [switch]$SkipTasks,
  [switch]$SkipPythonInstall,
  [switch]$UseScheduledTasks
)

$ErrorActionPreference = "Stop"
if ($PSVersionTable.PSVersion.Major -lt 7) {
  throw "PowerShell 7 (pwsh) is required. You are running Windows PowerShell $($PSVersionTable.PSVersion). Install https://aka.ms/powershell and run this script with pwsh."
}
try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}

$Repo = "ericleigh007/grok-remote"
$LatestBase = "https://github.com/$Repo/releases/latest/download"
$DefaultDest = Join-Path $env:LOCALAPPDATA "GrokRemote"

function Write-Step([string]$Message) { Write-Host ""; Write-Host "==> $Message" }
function Write-Ok([string]$Message) { Write-Host "    OK  $Message" }
function Write-Warn([string]$Message) { Write-Host "    !!  $Message" }

function New-Secret {
  $bytes = New-Object byte[] 32
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  $rng.GetBytes($bytes)
  $rng.Dispose()
  return [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
}

function Get-PythonTarget {
  $candidates = @(
    @{ Exe = "python"; Args = @() },
    @{ Exe = "py"; Args = @("-3") }
  )
  foreach ($c in $candidates) {
    try {
      $ver = & $c.Exe @($c.Args) -c "import sys; print('%d.%d' % (sys.version_info[0], sys.version_info[1]))" 2>$null
      if ($LASTEXITCODE -ne 0 -or -not $ver) { continue }
      $parts = ($ver.ToString().Trim() -split "\.")
      $major = [int]$parts[0]; $minor = [int]$parts[1]
      if ($major -gt 3 -or ($major -eq 3 -and $minor -ge 11)) {
        return @{ Exe = $c.Exe; Args = $c.Args; Version = "$major.$minor" }
      }
      Write-Warn "Found Python $major.$minor (need 3.11+)"
    } catch {}
  }
  return $null
}

function Refresh-Path {
  $machine = [Environment]::GetEnvironmentVariable("Path", "Machine")
  $user = [Environment]::GetEnvironmentVariable("Path", "User")
  $env:Path = "$user;$machine"
}

function Install-Python {
  $winget = Get-Command winget -ErrorAction SilentlyContinue
  if (-not $winget) {
    throw "Python 3.11+ not found and winget is unavailable. Install Python from https://www.python.org/downloads/ and re-run."
  }
  Write-Host "    Installing Python 3.12 with winget..."
  & winget install -e --id Python.Python.3.12 --accept-package-agreements --accept-source-agreements
  if ($LASTEXITCODE -ne 0) {
    throw "winget failed to install Python (exit $LASTEXITCODE). Install Python 3.11+ by hand and re-run."
  }
  Refresh-Path
  $py = Get-PythonTarget
  if (-not $py) {
    throw "Python installed but still not on PATH. Open a new PowerShell window and re-run install.ps1."
  }
  return $py
}

function Test-LooksLikeTree([string]$Dir) {
  return (Test-Path (Join-Path $Dir "server\main.py")) -and (Test-Path (Join-Path $Dir "supervise.ps1"))
}

function Get-ScriptHome {
  if ($PSScriptRoot) { return $PSScriptRoot }
  if ($MyInvocation.MyCommand.Path) { return (Split-Path -Parent $MyInvocation.MyCommand.Path) }
  return ""
}

function Get-TailscalePublicHost {
  $ts = "C:\Program Files\Tailscale\tailscale.exe"
  if (-not (Test-Path $ts)) { return "" }
  try {
    $status = & $ts status --json 2>$null | ConvertFrom-Json
    $dns = [string]$status.Self.DNSName
    if ($dns) { return "https://$($dns.TrimEnd('.'))" }
  } catch {}
  return ""
}

function Copy-Tree([string]$Source, [string]$Dest) {
  New-Item -ItemType Directory -Force -Path $Dest | Out-Null
  $names = @(
    "server",
    "web",
    "scripts",
    "config.example.json",
    "install.ps1",
    "install-startup.ps1",
    "uninstall-startup.ps1",
    "start-agent-serve.ps1",
    "start-background.ps1",
    "start.ps1",
    "enable-tailscale-https.ps1",
    "watchdog.ps1",
    "supervise.ps1",
    "VERSION",
    "README.md"
  )
  foreach ($name in $names) {
    $src = Join-Path $Source $name
    if (-not (Test-Path $src)) { continue }
    $dst = Join-Path $Dest $name
    if (Test-Path $src -PathType Container) {
      New-Item -ItemType Directory -Force -Path $dst | Out-Null
      Copy-Item -Path (Join-Path $src "*") -Destination $dst -Recurse -Force
    } else {
      Copy-Item -Path $src -Destination $dst -Force
    }
  }
  $rel = Join-Path $Dest "releases"
  New-Item -ItemType Directory -Force -Path $rel | Out-Null
  $cs = Join-Path $Source "tools\UserProcess.cs"
  if (Test-Path $cs) {
    New-Item -ItemType Directory -Force -Path (Join-Path $Dest "tools") | Out-Null
    Copy-Item $cs (Join-Path $Dest "tools\UserProcess.cs") -Force
  }
}

function Download-File([string]$Url, [string]$Dest) {
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Dest) | Out-Null
  Write-Host "    GET $Url"
  Invoke-WebRequest -Uri $Url -OutFile $Dest -UseBasicParsing
}

function Resolve-SourceTree {
  $homeDir = Get-ScriptHome
  if ($homeDir -and (Test-LooksLikeTree $homeDir)) { return $homeDir }

  Write-Step "Downloading PC package from GitHub Releases"
  $tmp = Join-Path $env:TEMP ("grok-remote-setup-" + [guid]::NewGuid().ToString("n"))
  New-Item -ItemType Directory -Force -Path $tmp | Out-Null
  $zip = Join-Path $tmp "grok-remote-pc.zip"
  Download-File "$LatestBase/grok-remote-pc.zip" $zip
  $extract = Join-Path $tmp "extract"
  Expand-Archive -Path $zip -DestinationPath $extract -Force
  $found = Get-ChildItem $extract -Recurse -Filter "supervise.ps1" | Select-Object -First 1
  if (-not $found) { throw "Downloaded zip did not contain the PC tree (missing supervise.ps1)" }
  return $found.DirectoryName
}

# --- resolve destination ---
$source = Resolve-SourceTree
$explicitDir = [bool]$InstallDir
if (-not $InstallDir) { $InstallDir = $DefaultDest }

$sourceFull = [IO.Path]::GetFullPath($source).TrimEnd("\")
$destFull = [IO.Path]::GetFullPath($InstallDir).TrimEnd("\")
$defaultFull = [IO.Path]::GetFullPath($DefaultDest).TrimEnd("\")
$isGitCheckout = Test-Path (Join-Path $sourceFull ".git")

$useInPlace = $false
if ($InPlace) { $useInPlace = $true }
elseif (-not $explicitDir -and ($isGitCheckout -or $sourceFull -eq $defaultFull)) { $useInPlace = $true }

if ($useInPlace) {
  $Dest = $sourceFull
} else {
  $Dest = $destFull
  Write-Step "Copying files to $Dest"
  Copy-Tree $sourceFull $Dest
}
Write-Ok "Install root: $Dest"

# --- prerequisites ---
Write-Step "Checking prerequisites"

$grok = Get-Command grok -ErrorAction SilentlyContinue
if (-not $grok) {
  throw "grok is not on PATH. Install Grok Build from https://docs.x.ai, log in on this PC, then re-run."
}
try { $grokVer = (& grok --version 2>&1 | Out-String).Trim() } catch { $grokVer = "grok" }
Write-Ok $grokVer

$py = Get-PythonTarget
if (-not $py) {
  if ($SkipPythonInstall) { throw "Python 3.11+ is required." }
  Write-Warn "Python 3.11+ not found"
  $py = Install-Python
}
Write-Ok "Python $($py.Version)"

$tsExe = "C:\Program Files\Tailscale\tailscale.exe"
if (Test-Path $tsExe) { Write-Ok "Tailscale present" } else { Write-Warn "Tailscale not found (optional, but the documented remote path)" }

# --- venv ---
Write-Step "Creating Python venv and installing dependencies"
$venvPy = Join-Path $Dest ".venv\Scripts\python.exe"
if (-not (Test-Path $venvPy)) {
  & $py.Exe @($py.Args) -m venv (Join-Path $Dest ".venv")
  if ($LASTEXITCODE -ne 0) { throw "python -m venv failed: $LASTEXITCODE" }
}
& $venvPy -m pip install -q --upgrade pip
& $venvPy -m pip install -q -r (Join-Path $Dest "server\requirements.txt")
if ($LASTEXITCODE -ne 0) { throw "pip install failed: $LASTEXITCODE" }
Write-Ok "venv ready ($venvPy)"

# --- config ---
Write-Step "Configuring"
$configPath = Join-Path $Dest "config.json"
$examplePath = Join-Path $Dest "config.example.json"
if (Test-Path $configPath) {
  Write-Ok "Keeping existing config.json"
} else {
  if (-not $DefaultCwd) { $DefaultCwd = $env:USERPROFILE }
  if (-not $PublicHost) { $PublicHost = Get-TailscalePublicHost }
  if (-not $PublicHost) { $PublicHost = "https://YOUR-PC.YOUR-TAILNET.ts.net" }

  $cfg = Get-Content $examplePath -Raw | ConvertFrom-Json
  $cfg.remote_token = New-Secret
  $cfg.agent_secret = New-Secret
  $cfg.public_host = $PublicHost
  $cfg.default_cwd = $DefaultCwd
  $cfg.projects = @(
    [pscustomobject]@{
      name           = "Home"
      cwd            = $DefaultCwd
      session_id     = $null
      replay_history = $false
    }
  )
  $cfg | ConvertTo-Json -Depth 8 | Set-Content -Path $configPath -Encoding utf8
  Write-Ok "Wrote config.json (generated remote_token + agent_secret)"
  Write-Ok "public_host = $PublicHost"
  Write-Ok "default_cwd = $DefaultCwd"
  if ($PublicHost -match "YOUR-") {
    Write-Warn "Edit public_host in config.json after Tailscale Serve is on"
  }
}

# --- APK for /dl ---
if (-not $SkipApk) {
  Write-Step "Publishing Android APK for phone /dl"
  $relDir = Join-Path $Dest "releases"
  New-Item -ItemType Directory -Force -Path $relDir | Out-Null
  $apkNames = @("grok-remote.apk", "grok-remote-debug.apk")
  $have = $false
  foreach ($n in $apkNames) {
    if (Test-Path (Join-Path $relDir $n)) { $have = $true; break }
  }
  $localApk = Join-Path $sourceFull "android\app\build\outputs\apk\debug\app-debug.apk"
  $srcPublished = Join-Path $sourceFull "releases\grok-remote-debug.apk"
  if (-not $have -and (Test-Path $srcPublished) -and $sourceFull -ne $Dest) {
    Copy-Item $srcPublished (Join-Path $relDir "grok-remote-debug.apk") -Force
    $have = $true
  }
  if (-not $have -and (Test-Path $localApk)) {
    Copy-Item $localApk (Join-Path $relDir "grok-remote-debug.apk") -Force
    Copy-Item $localApk (Join-Path $relDir "grok-remote.apk") -Force
    $have = $true
    Write-Ok "Copied locally built APK"
  }
  if (-not $have) {
    try {
      Download-File "$LatestBase/grok-remote.apk" (Join-Path $relDir "grok-remote.apk")
      Copy-Item (Join-Path $relDir "grok-remote.apk") (Join-Path $relDir "grok-remote-debug.apk") -Force
      $have = $true
      Write-Ok "Downloaded grok-remote.apk from GitHub Releases"
    } catch {
      Write-Warn "No APK yet ($($_.Exception.Message)). Phone /dl will be empty until you add one."
    }
  } else {
    Write-Ok "APK ready under releases\"
  }
}

# --- scheduled tasks ---
if (-not $SkipTasks) {
  Write-Step "Installing Windows service (LocalSystem supervisor, children as your user, no password)"
  $installStartup = Join-Path $Dest "install-startup.ps1"
  $pwsh = (Get-Command pwsh -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source)
  if (-not $pwsh) { throw "PowerShell 7 (pwsh) is required." }
  $instArgs = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $installStartup)
  if ($UseScheduledTasks) { $instArgs += "-UseScheduledTasks" }
  & $pwsh @instArgs
} else {
  Write-Warn "Skipped scheduled tasks (-SkipTasks)"
}

Write-Host ""
Write-Host "Grok Remote is installed."
Write-Host "  Root:  $Dest"
Write-Host "  Pair:  http://127.0.0.1:8787/pair   (PC browser only)"
Write-Host "  APK:   http://127.0.0.1:8787/dl     or scan Install APK on /pair"
Write-Host ""
Write-Host "Sideloading: Android / Samsung will block the APK until you allow the source."
Write-Host "  Samsung: Settings → Security and privacy → Auto Blocker → Off"
Write-Host "  Then:    allow Chrome/Files to install unknown apps, and Install anyway on Play Protect"
Write-Host "Details: README.md → Sideload the Android app"
Write-Host ""
$uninstall = Join-Path $Dest "uninstall-startup.ps1"
Write-Host "Uninstall tasks:  pwsh -ExecutionPolicy Bypass -File $uninstall"
