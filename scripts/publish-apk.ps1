# Build (optional) + copy debug APK into releases/ for bridge download serving.
#   pwsh -ExecutionPolicy Bypass -File .\scripts\publish-apk.ps1
#   pwsh -ExecutionPolicy Bypass -File .\scripts\publish-apk.ps1 -SkipBuild
param(
  [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Android = Join-Path $Root "android"
$ApkSrc = Join-Path $Android "app\build\outputs\apk\debug\app-debug.apk"
$Releases = Join-Path $Root "releases"
$ApkDst = Join-Path $Releases "grok-remote-debug.apk"
$ApkReleaseName = Join-Path $Releases "grok-remote.apk"
$Meta = Join-Path $Releases "latest.json"

$jdkCandidates = @(
  $env:JAVA_HOME,
  "C:\Users\ericl\.jdks\ms-21.0.9",
  "C:\Program Files\Android\openjdk\jdk-21.0.8",
  "C:\Program Files\Android\openjdk\jdk-17.0.14",
  "C:\Program Files\Microsoft\jdk-17.0.16.8-hotspot"
) | Where-Object { $_ -and (Test-Path $_) }
if (-not $jdkCandidates) { throw "No JDK 17+ found. Set JAVA_HOME or install a JDK." }
$env:JAVA_HOME = $jdkCandidates[0]
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
  throw "APK not found at $ApkSrc - build first"
}

New-Item -ItemType Directory -Force -Path $Releases | Out-Null
Copy-Item $ApkSrc $ApkDst -Force
Copy-Item $ApkSrc $ApkReleaseName -Force
$item = Get-Item $ApkDst
$metaObj = [ordered]@{
  filename     = $item.Name
  sizeBytes    = $item.Length
  sizeMB       = [math]::Round($item.Length / 1MB, 1)
  modifiedIso  = $item.LastWriteTime.ToString("o")
  downloadPath = "/dl/apk"
  page         = "/dl"
}
$metaObj | ConvertTo-Json | Set-Content $Meta -Encoding utf8

Write-Host ""
Write-Host "Published: $ApkDst"
Write-Host ("Size: {0:N1} MB" -f ($item.Length / 1MB))
Write-Host "Easiest: open http://127.0.0.1:8787/pair on the PC and scan Install APK QR"
Write-Host "Or on the phone (Tailscale connected):"
Write-Host "  https://<your-machine>.<your-tailnet>.ts.net/dl"
Write-Host "  or http://<tailscale-ip>:8787/dl"
Write-Host ""
Write-Host "Restart bridge if it was already running so routes are loaded:"
Write-Host "  schtasks /Run /TN GrokRemoteBridge"
