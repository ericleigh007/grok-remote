# Remove Grok Remote service + watchdog + leftover scheduled tasks.
# Requires elevation (UAC). Self-elevates if needed.
$ErrorActionPreference = "Continue"
if ($PSVersionTable.PSVersion.Major -lt 7) {
  throw "PowerShell 7 (pwsh) is required."
}
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$ps = @(
  (Get-Command pwsh -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source),
  (Join-Path $PSHOME "pwsh.exe"),
  "C:\Program Files\PowerShell\7\pwsh.exe"
) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1

$IsAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
  [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $IsAdmin) {
  $arg = "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
  $p = Start-Process -FilePath $ps -Verb RunAs -ArgumentList $arg -Wait -PassThru
  exit $p.ExitCode
}

$WinSwExe = Join-Path $Root "tools\GrokRemote.exe"
if (Test-Path $WinSwExe) {
  & $WinSwExe stop 2>$null | Out-Null
  & $WinSwExe uninstall 2>$null | Out-Null
}
$svc = Get-Service GrokRemote -ErrorAction SilentlyContinue
if ($svc) {
  Stop-Service GrokRemote -Force -ErrorAction SilentlyContinue
  sc.exe delete GrokRemote | Out-Null
  Write-Host "Removed service: GrokRemote"
}

foreach ($name in @("GrokRemoteBridge", "GrokAgentServe", "GrokRemoteWatchdog", "GrokRemoteSupervisor")) {
  Stop-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue
  Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue
  Write-Host "Removed task: $name"
}

Write-Host "If ports 8787/2419 are still held, Restart-Service is gone - end those processes from Task Manager."
