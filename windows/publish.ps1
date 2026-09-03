# Native publish for this machine's arch, plus the other Windows arch.
#   pwsh -File .\windows\publish.ps1
param(
  [ValidateSet("win-x64", "win-arm64", "both")]
  [string]$Rid = "both"
)

$ErrorActionPreference = "Stop"
if ($PSVersionTable.PSVersion.Major -lt 7) {
  throw "PowerShell 7 (pwsh) is required."
}

$WinDir = $PSScriptRoot
$Repo = Split-Path -Parent $WinDir
$Proj = Join-Path $WinDir "GrokRemote.Desktop\GrokRemote.Desktop.csproj"
$OutRoot = Join-Path $Repo "dist"
$Config = Join-Path $WinDir "nuget.config"

$rids = @()
if ($Rid -eq "both") {
  $rids = @("win-x64", "win-arm64")
} else {
  $rids = @($Rid)
}

foreach ($r in $rids) {
  $out = Join-Path $OutRoot "grok-remote-desktop-$r"
  Write-Host "Publishing $r -> $out"
  $platform = if ($r -eq "win-arm64") { "ARM64" } else { "x64" }
  dotnet publish $Proj -c Release -r $r --self-contained true `
    --configfile $Config `
    -p:Platform=$platform `
    -p:WindowsAppSDKSelfContained=true `
    -p:WindowsPackageType=None `
    -o $out
  if ($LASTEXITCODE -ne 0) { throw "publish $r failed: $LASTEXITCODE" }
  Write-Host "OK $out\GrokRemote.Desktop.exe"
}
