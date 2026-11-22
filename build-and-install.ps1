# Windows version of build-and-install.sh: build the launcher APK and install it on the TV as the
# default home screen. Uses Android Studio's bundled JDK and the default SDK location unless
# JAVA_HOME / ANDROID_HOME are set.
#
# Usage: launcher\build-and-install.ps1 -TvHost TV_IP [-StockLauncher com.google.android.tvlauncher]
param(
    [string]$TvHost = $env:TV_HOST,
    [string]$StockLauncher = 'com.google.android.tvlauncher'
)
$ErrorActionPreference = 'Stop'
# Pipe plain UTF-8 to native commands; the default here prepends a BOM that corrupts the first command.
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
$env:TV_HOST = $TvHost
Set-Location $PSScriptRoot

if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $jbr = Get-ChildItem 'C:\Program Files\Android' -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName 'jbr' } | Where-Object { Test-Path "$_\bin\java.exe" } | Select-Object -First 1
    if (-not $jbr) { throw 'Set JAVA_HOME to a JDK 17+ (Android Studio ships one in its jbr folder)' }
    $env:JAVA_HOME = $jbr
}
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path local.properties)) {
    "sdk.dir=$($sdk -replace '\\', '\\')" | Out-File -Encoding ascii local.properties
}

.\gradlew.bat assembleDebug -q --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }

$apk = 'app\build\outputs\apk\debug\app-debug.apk'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "Android SDK adb not found at $adb" }
& $adb connect "$TvHost`:5555"
if ($LASTEXITCODE -ne 0) { throw 'Could not connect to the TV over ADB' }
& $adb install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed; home selection was left unchanged' }
& $adb shell cmd package set-home-activity --user 0 com.tvdroid17/.MainActivity
if ($LASTEXITCODE -ne 0) { throw 'Could not select the personalized launcher as Home' }
# Xiaomi's Android TV firmware clears third-party accessibility services after a
# package reinstall unless the service is explicitly enabled again. This service
# is what receives the global SET key before Android Settings does.
& $adb shell settings put secure enabled_accessibility_services com.tvdroid17/com.tvdroid17.SourceKeyService
& $adb shell settings put secure accessibility_enabled 1
& $adb shell pm grant com.tvdroid17 android.permission.WRITE_SECURE_SETTINGS
if ($LASTEXITCODE -ne 0) { throw 'Could not enable the SET key service' }
& $adb shell pm grant com.tvdroid17 android.permission.ACCESS_FINE_LOCATION
& $adb shell pm grant com.tvdroid17 android.permission.ACCESS_COARSE_LOCATION
& $adb shell pm grant com.tvdroid17 android.permission.NEARBY_WIFI_DEVICES
& $adb shell pm disable-user --user 0 $StockLauncher
if ($LASTEXITCODE -ne 0) { throw 'Could not disable the stock launcher' }
& $adb shell am start -n com.tvdroid17/.MainActivity

# The TMDB read token is not compiled into the APK: it is copied onto the television, where the
# launcher reads it at startup. Keep it outside the project, in ~\.config\tvdroid17\tmdb-token.
# The legacy ~\.config\tvhome\tmdb-token is still read when the new one is absent.
$tokenFile = if ($env:TMDB_TOKEN_FILE) { $env:TMDB_TOKEN_FILE } else { Join-Path $HOME '.config\tvdroid17\tmdb-token' }
if (-not (Test-Path $tokenFile)) {
    $legacy = Join-Path $HOME '.config\tvhome\tmdb-token'
    if (Test-Path $legacy) { $tokenFile = $legacy }
}
$token = ''
if (Test-Path $tokenFile) { $token = (Get-Content $tokenFile -Raw).Trim() }
if (-not $token) { $token = $env:TMDB_READ_ACCESS_TOKEN }
if ($token) {
    # Written into the app's own files, which survive an upgrade.
    $token | & $adb shell "run-as com.tvdroid17 sh -c 'cat > files/tmdb-token.txt'"
    $size = & $adb shell "run-as com.tvdroid17 wc -c files/tmdb-token.txt 2>/dev/null"
    Write-Host "TMDB token copied to the TV ($size bytes)."
} else {
    Write-Host "No TMDB token in $tokenFile : trailers and French summaries will stay off."
}
Write-Host 'Installed and selected com.tvdroid17 as the Home app.'
