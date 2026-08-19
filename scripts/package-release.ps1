# Build release artifacts and (optionally) publish them to GitHub.
#   pwsh -ExecutionPolicy Bypass -File .\scripts\package-release.ps1
#   pwsh -ExecutionPolicy Bypass -File .\scripts\package-release.ps1 -SkipBuild -SkipUpload
param(
  [string]$Version = "",
  [switch]$SkipBuild,
  [switch]$SkipUpload,
  [switch]$Draft
)

$ErrorActionPreference = "Stop"
if ($PSVersionTable.PSVersion.Major -lt 7) {
  throw "PowerShell 7 (pwsh) is required. You are running Windows PowerShell $($PSVersionTable.PSVersion)."
}
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$VersionFile = Join-Path $Root "VERSION"
if (-not $Version) {
  if (-not (Test-Path $VersionFile)) { throw "VERSION file missing and -Version not set" }
  $Version = (Get-Content $VersionFile -Raw).Trim()
}
if ($Version -notmatch '^\d+\.\d+\.\d+') { throw "Bad version: $Version" }

$Tag = "v$Version"
$Dist = Join-Path $Root "dist"
$Stage = Join-Path $Dist "grok-remote-pc-$Version"
$Zip = Join-Path $Dist "grok-remote-pc.zip"
$ApkOut = Join-Path $Dist "grok-remote.apk"

Write-Host "Packaging $Tag"

if (-not $SkipBuild) {
  $publish = Join-Path $Root "scripts\publish-apk.ps1"
  $pwsh = (Get-Command pwsh -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source)
  if (-not $pwsh) { throw "PowerShell 7 (pwsh) is required." }
  & $pwsh -NoProfile -ExecutionPolicy Bypass -File $publish
  if ($LASTEXITCODE -ne 0) { throw "publish-apk.ps1 failed: $LASTEXITCODE" }
}

$apkSrcCandidates = @(
  (Join-Path $Root "releases\grok-remote.apk"),
  (Join-Path $Root "releases\grok-remote-debug.apk"),
  (Join-Path $Root "android\app\build\outputs\apk\debug\app-debug.apk")
)
$apkSrc = $apkSrcCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $apkSrc) { throw "No APK found. Build first or pass -SkipBuild only after a prior publish-apk.ps1." }

if (Test-Path $Dist) { Remove-Item $Dist -Recurse -Force }
New-Item -ItemType Directory -Force -Path $Stage | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $Stage "releases") | Out-Null

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
  "tools",
  "VERSION",
  "README.md"
)
foreach ($name in $names) {
  $src = Join-Path $Root $name
  if (-not (Test-Path $src)) { throw "Missing $src" }
  Copy-Item $src (Join-Path $Stage $name) -Recurse -Force
}

# Strip Python caches copied from server/
Get-ChildItem (Join-Path $Stage "server") -Recurse -Directory -Filter "__pycache__" | Remove-Item -Recurse -Force
$stageTools = Join-Path $Stage "tools"
if (Test-Path $stageTools) {
  Get-ChildItem $stageTools -File | Where-Object { $_.Extension -match '\.(exe|dll|json|xml)$' } | Remove-Item -Force
}

Copy-Item $apkSrc $ApkOut -Force
Copy-Item $apkSrc (Join-Path $Stage "releases\grok-remote.apk") -Force

if (Test-Path $Zip) { Remove-Item $Zip -Force }
Compress-Archive -Path $Stage -DestinationPath $Zip -Force

$apkItem = Get-Item $ApkOut
$zipItem = Get-Item $Zip
Write-Host ""
Write-Host "Artifacts:"
Write-Host ("  {0}  ({1:N1} MB)" -f $zipItem.FullName, ($zipItem.Length / 1MB))
Write-Host ("  {0}  ({1:N1} MB)" -f $apkItem.FullName, ($apkItem.Length / 1MB))
Write-Host ("  {0}" -f (Join-Path $Root "install.ps1"))

if ($SkipUpload) {
  Write-Host "SkipUpload set - not creating a GitHub release."
  exit 0
}

$gh = Get-Command gh -ErrorAction SilentlyContinue
if (-not $gh) { throw "gh CLI not found. Install GitHub CLI or re-run with -SkipUpload." }

$notesTemplate = Join-Path $PSScriptRoot "release-notes.md"
if (-not (Test-Path $notesTemplate)) { throw "Missing $notesTemplate" }
$notesPath = Join-Path $Dist "RELEASE_NOTES.md"
$notesBody = (Get-Content $notesTemplate -Raw).Replace("__VERSION__", $Tag)
Set-Content -Path $notesPath -Value $notesBody -Encoding utf8

$existing = $null
$existing = & gh release view $Tag --repo ericleigh007/grok-remote 2>$null
if ($LASTEXITCODE -eq 0 -and $existing) {
  Write-Host "Release $Tag already exists - uploading assets"
  & gh release upload $Tag $Zip $ApkOut (Join-Path $Root "install.ps1") --repo ericleigh007/grok-remote --clobber
} else {
  $createArgs = @(
    "release", "create", $Tag,
    $Zip, $ApkOut, (Join-Path $Root "install.ps1"),
    "--repo", "ericleigh007/grok-remote",
    "--title", "Grok Remote $Tag",
    "--notes-file", $notesPath
  )
  if ($Draft) { $createArgs += "--draft" }
  & gh @createArgs
}
if ($LASTEXITCODE -ne 0) { throw "gh release failed: $LASTEXITCODE" }
Write-Host ("Published https://github.com/ericleigh007/grok-remote/releases/tag/{0}" -f $Tag)
