# Enable Tailscale Serve so the phone can use HTTPS (required for mic / Web Speech).
# Free on Personal plan. One-time admin consent may open in the browser.
#
#   powershell -ExecutionPolicy Bypass -File .\enable-tailscale-https.ps1
$ErrorActionPreference = "Continue"
$ts = "C:\Program Files\Tailscale\tailscale.exe"
if (-not (Test-Path $ts)) {
  throw "Tailscale not found at $ts"
}

Write-Host "Enabling: tailscale serve --bg http://127.0.0.1:8787"
Write-Host "(If Serve is not enabled on the tailnet yet, Tailscale will print a login URL — open it once.)"
Write-Host ""

# Run with a timeout so we don't hang forever waiting for browser consent
$job = Start-Job -ScriptBlock {
  param($tsPath)
  & $tsPath serve --bg http://127.0.0.1:8787 2>&1 | Out-String
} -ArgumentList $ts

$finished = Wait-Job $job -Timeout 25
if (-not $finished) {
  Stop-Job $job -ErrorAction SilentlyContinue
  Remove-Job $job -Force -ErrorAction SilentlyContinue
  Write-Host ""
  Write-Host "Timed out waiting for Serve (usually needs one-time enable in the browser)."
  Write-Host "1) Open Tailscale admin and enable Serve / HTTPS if prompted."
  Write-Host "   Or re-run:  & '$ts' serve --bg http://127.0.0.1:8787"
  Write-Host "2) Look for a URL like: https://login.tailscale.com/f/serve?node=..."
  Write-Host "3) After enabling, run this script again."
  Write-Host ""
  # Still show current status
  & $ts serve status 2>&1
  exit 2
}

$output = Receive-Job $job
Remove-Job $job -Force -ErrorAction SilentlyContinue
Write-Host $output

Write-Host ""
& $ts serve status 2>&1
Write-Host ""

try {
  $status = & $ts status --json | ConvertFrom-Json
  $dns = ($status.Self.DNSName -replace '\.$', '')
  Write-Host "Phone HTTPS URL (after re-pair QR):"
  Write-Host "  https://$dns/"
  Write-Host ""
  Write-Host "Next: open http://127.0.0.1:8787/pair on this PC and scan the QR again."
  Write-Host "(Mic works on the https://…ts.net URL in Chrome — not on http://100.x)"
} catch {
  Write-Host "Could not resolve MagicDNS name: $_"
}
