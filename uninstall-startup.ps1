$ErrorActionPreference = "Continue"
foreach ($name in @("GrokRemoteBridge", "GrokAgentServe", "GrokRemoteWatchdog")) {
  Stop-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue
  Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue
  Write-Host "Removed: $name"
}
Write-Host "If ports 8787/2419 still held, end those processes from Task Manager."
