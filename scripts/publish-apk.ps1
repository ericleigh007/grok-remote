# Build (optional) + copy debug APK into releases/ for bridge download serving.
#   powershell -ExecutionPolicy Bypass -File .\scripts\publish-apk.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\publish-apk.ps1 -SkipBuild
param(
  [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Android = Join-Path $Root "android"
$ApkSrc = Join-Path $Android "app\build\outputs\apk\debug\app-debug.apk"
$Releases = Join-Path $Root "releases"
$ApkDst = Join-Path $Releases "grok-remote-debug.apk"
$Meta = Join-Path $Releases "latest.json"

$env:JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "C:\Program Files\Android\openjdk\jdk-21.0.8" }
$env:ANDROID_HOME = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"

if (-not $SkipBuild) {
  Write-Host "Building debug APK..."
  Push-Location $Android
  try {
    & .\gradlew.bat :app:assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "gradle assembleDebug failed: $LASTEXITCODE" }
  } finally {
    Pop-Location
  }
}

if (-not (Test-Path $ApkSrc)) {
  throw "APK not found at $ApkSrc — build first"
}

New-Item -ItemType Directory -Force -Path $Releases | Out-Null
Copy-Item $ApkSrc $ApkDst -Force
$item = Get-Item $ApkDst
$metaObj = [ordered]@{
  filename     = $item.Name
  sizeBytes    = $item.Length
  sizeMB       = [math]::Round($item.Length / 1MB, 1)
  modifiedIso  = $item.LastWriteTime.ToString("o")
  downloadPath = "/download/grok-remote.apk"
  page         = "/download"
}
$metaObj | ConvertTo-Json | Set-Content $Meta -Encoding utf8

Write-Host ""
Write-Host "Published: $ApkDst"
Write-Host ("Size: {0:N1} MB" -f ($item.Length / 1MB))
Write-Host "On the phone (Tailscale connected), open:"
Write-Host "  https://<your-machine>.<your-tailnet>.ts.net/download"
Write-Host "  or http://<tailscale-ip>:8787/download"
Write-Host ""
Write-Host "Restart bridge if it was already running so routes are loaded:"
Write-Host "  schtasks /Run /TN GrokRemoteBridge"
