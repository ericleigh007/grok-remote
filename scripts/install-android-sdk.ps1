# Download Android SDK command-line tools + packages needed to build grok-remote Android app.
# Safe to re-run. Uses JDK 21 if present under Program Files\Android\openjdk.
$ErrorActionPreference = "Stop"

$SdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$CmdlineZipUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
$TempDir = Join-Path $env:TEMP "android-sdk-setup"
$ZipPath = Join-Path $TempDir "commandlinetools-win.zip"

# Prefer Android-bundled JDK 21
$JavaHomeCandidates = @(
  "C:\Program Files\Android\openjdk\jdk-21.0.8",
  "C:\Program Files\Android\openjdk\jdk-17*",
  "C:\Program Files\Microsoft\jdk-17*",
  "C:\Program Files\Eclipse Adoptium\jdk-17*"
)
$JavaHome = $null
foreach ($c in $JavaHomeCandidates) {
  $hits = Get-Item $c -ErrorAction SilentlyContinue
  if ($hits) {
    $JavaHome = ($hits | Select-Object -First 1).FullName
    break
  }
}
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome "bin\java.exe"))) {
  throw "JDK 17+ required. Install Microsoft OpenJDK 17/21 or Android Studio's bundled JDK."
}

$env:JAVA_HOME = $JavaHome
$env:Path = "$(Join-Path $JavaHome 'bin');$env:Path"
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot

Write-Host "JAVA_HOME    = $JavaHome"
Write-Host "ANDROID_HOME = $SdkRoot"
$javaVer = & cmd /c "java -version 2>&1"
Write-Host "java: $($javaVer | Select-Object -First 1)"

New-Item -ItemType Directory -Force -Path $SdkRoot | Out-Null
New-Item -ItemType Directory -Force -Path $TempDir | Out-Null

$SdkManager = Join-Path $SdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
if (-not (Test-Path $SdkManager)) {
  Write-Host "Downloading Android command-line tools..."
  Invoke-WebRequest -Uri $CmdlineZipUrl -OutFile $ZipPath -UseBasicParsing
  $Extract = Join-Path $TempDir "extracted"
  if (Test-Path $Extract) { Remove-Item $Extract -Recurse -Force }
  Expand-Archive -Path $ZipPath -DestinationPath $Extract -Force

  # Zip contains cmdline-tools/{bin,lib,...} — must live under cmdline-tools/latest/
  $Dest = Join-Path $SdkRoot "cmdline-tools\latest"
  New-Item -ItemType Directory -Force -Path (Join-Path $SdkRoot "cmdline-tools") | Out-Null
  if (Test-Path $Dest) { Remove-Item $Dest -Recurse -Force }
  $Inner = Join-Path $Extract "cmdline-tools"
  if (-not (Test-Path $Inner)) {
    # some zips nest differently
    $Inner = Get-ChildItem $Extract -Directory | Select-Object -First 1 -ExpandProperty FullName
  }
  Move-Item $Inner $Dest
  $SdkManager = Join-Path $Dest "bin\sdkmanager.bat"
  if (-not (Test-Path $SdkManager)) {
    throw "sdkmanager.bat not found after extract at $Dest"
  }
  Write-Host "Installed cmdline-tools to $Dest"
} else {
  Write-Host "cmdline-tools already present"
}

function Invoke-SdkManager([string[]]$SdkArgs) {
  Write-Host ("sdkmanager " + ($SdkArgs -join " "))
  $p = Start-Process -FilePath $SdkManager -ArgumentList $SdkArgs -NoNewWindow -Wait -PassThru `
    -RedirectStandardOutput (Join-Path $TempDir "sdkmanager-out.txt") `
    -RedirectStandardError (Join-Path $TempDir "sdkmanager-err.txt")
  Get-Content (Join-Path $TempDir "sdkmanager-out.txt") -ErrorAction SilentlyContinue | Write-Host
  Get-Content (Join-Path $TempDir "sdkmanager-err.txt") -ErrorAction SilentlyContinue | Write-Host
  if ($p.ExitCode -ne 0) {
    throw "sdkmanager failed with exit $($p.ExitCode)"
  }
}

# Accept licenses non-interactively
Write-Host "Accepting licenses..."
$licensesDir = Join-Path $SdkRoot "licenses"
New-Item -ItemType Directory -Force -Path $licensesDir | Out-Null
# Standard license hashes (sdkmanager --licenses still best; also write common ones)
@"
24333f8a63b6825ea9c5514f83c2829b004d1fee
"@ | Set-Content (Join-Path $licensesDir "android-sdk-license") -Encoding ascii
@"
84831b9409646a918e30573bab4c9c91346d8abd
"@ | Set-Content (Join-Path $licensesDir "android-sdk-preview-license") -Encoding ascii

# Pipe yes into licenses for anything remaining
$yesFile = Join-Path $TempDir "yes.txt"
(1..100 | ForEach-Object { "y" }) -join "`n" | Set-Content $yesFile -Encoding ascii
cmd /c "`"$SdkManager`" --sdk_root=`"$SdkRoot`" --licenses < `"$yesFile`""

$packages = @(
  "platform-tools",
  "platforms;android-35",
  "build-tools;35.0.0",
  "emulator",
  "cmdline-tools;latest"
)

Write-Host "Installing packages..."
Invoke-SdkManager (@("--sdk_root=$SdkRoot") + $packages)

# local.properties for the Android project
$RepoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -ErrorAction SilentlyContinue
if (-not $RepoRoot) { $RepoRoot = Split-Path $PSScriptRoot -Parent }
# scripts/ is under repo root
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$AndroidDir = Join-Path $RepoRoot "android"
if (Test-Path $AndroidDir) {
  $localProps = "sdk.dir=$($SdkRoot -replace '\\','\\')"
  # Gradle wants escaped backslashes or forward slashes
  $sdkEscaped = $SdkRoot -replace '\\', '/'
  "sdk.dir=$sdkEscaped" | Set-Content (Join-Path $AndroidDir "local.properties") -Encoding ascii
  Write-Host "Wrote android/local.properties"
}

# Persist user env for future shells (user-level, no admin)
[Environment]::SetEnvironmentVariable("ANDROID_HOME", $SdkRoot, "User")
[Environment]::SetEnvironmentVariable("ANDROID_SDK_ROOT", $SdkRoot, "User")
[Environment]::SetEnvironmentVariable("JAVA_HOME", $JavaHome, "User")
Write-Host ""
Write-Host "Done."
Write-Host "  ANDROID_HOME = $SdkRoot"
Write-Host "  JAVA_HOME    = $JavaHome"
Write-Host "Open a new terminal, then:"
Write-Host "  cd $AndroidDir"
Write-Host "  .\gradlew.bat :app:assembleDebug"
Write-Host ""
Write-Host "Optional: install Android Studio for emulator UI / device manager."
Write-Host "Platform-tools adb: $(Join-Path $SdkRoot 'platform-tools\adb.exe')"
