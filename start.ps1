# Start Grok Remote bridge (multi-session mobile UI over Tailscale)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Root

if (-not (Test-Path "$Root\.venv")) {
  Write-Host "Creating venv..."
  python -m venv .venv
}
& "$Root\.venv\Scripts\Activate.ps1"
python -m pip install -q -r server\requirements.txt

# Prefer a real token from env if set
if ($env:GROK_REMOTE_TOKEN) {
  Write-Host "Using GROK_REMOTE_TOKEN from environment"
}

Write-Host ""
Write-Host "Starting Grok Remote on http://0.0.0.0:8787/"
Write-Host "PAIR PHONE (no typing): open http://127.0.0.1:8787/pair on this PC and scan the QR"
Write-Host "Tailscale must be connected on PC + phone first."
Write-Host ""

Set-Location "$Root\server"
python main.py
