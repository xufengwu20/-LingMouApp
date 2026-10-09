# Installs the debug APK to the connected device and optionally follows logcat.
# ASCII-only on purpose: Windows PowerShell 5.1 mis-decodes non-ASCII .ps1 files
# unless they carry a UTF-8 BOM.
param(
    [string]$Apk,
    [switch]$Follow,
    [switch]$Uninstall
)

$ErrorActionPreference = 'Continue'

$root = Split-Path -Parent $PSScriptRoot
if (-not $Apk) {
    # 标准输出目录优先，其次是受限环境下的重定向输出目录；存在多个时取最新的那个，
    # 避免装到上一次构建留下的旧包。
    $paths = @(
        (Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'),
        (Join-Path $root '.toolchain\build-app\app\outputs\apk\debug\app-debug.apk')
    )
    $existing = @()
    foreach ($p in $paths) {
        if (Test-Path $p) { $existing += (Get-Item $p) }
    }
    if ($existing.Count -gt 0) {
        $Apk = ($existing | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
    } else {
        $Apk = $paths[0]
    }
    Write-Host "APK: $Apk"
}

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) {
    Write-Host "adb not found at $adb" -ForegroundColor Red
    Write-Host "Set ANDROID_HOME or pass a working SDK path."
    exit 1
}

$pkg = 'com.example.lingmouapp'

Write-Host "=== devices ===" -ForegroundColor Cyan
& $adb devices -l

$devices = (& $adb devices) | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' }
if (-not $devices) {
    Write-Host ''
    Write-Host "No authorized device found." -ForegroundColor Yellow
    Write-Host "  1. On the phone: Settings -> About phone -> tap Build number 7 times"
    Write-Host "  2. Settings -> Developer options -> enable USB debugging"
    Write-Host "  3. Plug in USB, then accept the 'Allow USB debugging?' prompt on the phone"
    Write-Host "  4. If needed: adb kill-server; adb start-server; adb devices"
    Write-Host "  Real devices only - this app needs a camera and a microphone."
    exit 2
}

if ($Uninstall) {
    Write-Host ''
    Write-Host "=== uninstall $pkg ===" -ForegroundColor Cyan
    & $adb uninstall $pkg
    exit $LASTEXITCODE
}

if (-not (Test-Path $Apk)) {
    Write-Host "APK not found: $Apk" -ForegroundColor Red
    Write-Host "Build it first:  .\gradlew.bat assembleDebug"
    exit 1
}

Write-Host ''
Write-Host "=== install ===" -ForegroundColor Cyan
& $adb install -r -t $Apk
if ($LASTEXITCODE -ne 0) {
    Write-Host "install failed (code $LASTEXITCODE). Try: -Uninstall then run again." -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host ''
Write-Host "=== launch ===" -ForegroundColor Cyan
& $adb shell am start -n "$pkg/.MainActivity"

if ($Follow) {
    Write-Host ''
    Write-Host "=== logcat (Ctrl+C to stop) ===" -ForegroundColor Cyan
    & $adb logcat -c
    & $adb logcat -v time `
        LingMouEngine:V AndroidRuntime:E CameraXCore:V CameraStateMachine:V `
        MLKit:V MediaSessionService:V ActivityManager:I *:S
}
