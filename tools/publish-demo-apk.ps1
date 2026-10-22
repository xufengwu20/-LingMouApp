# Publishes the debug APK to the desktop as LingMou_Demo.apk (ALWAYS overwrites).
#
# Guarantees:
#   - one and only one output file on the desktop: LingMou_Demo.apk
#   - never creates timestamped/hashed copies
#   - SHA256 is verified after the copy
#
# Usage:
#   powershell -File tools\publish-demo-apk.ps1              (build then publish)
#   powershell -File tools\publish-demo-apk.ps1 -SkipBuild   (publish existing APK only)
#
# ASCII-only on purpose: Windows PowerShell 5.1 mis-decodes non-ASCII .ps1 files
# unless they carry a UTF-8 BOM.
param(
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$buildScript = Join-Path $root '.toolchain\build.ps1'
$apk = Join-Path $root '.toolchain\build-app\app\outputs\apk\debug\app-debug.apk'
$dest = Join-Path $env:USERPROFILE 'Desktop\LingMou_Demo.apk'

if (-not $SkipBuild) {
    Write-Host '=== build (assembleDebug) ==='
    & "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" `
        -NoProfile -ExecutionPolicy Bypass -File $buildScript assembleDebug
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'BUILD FAILED' -ForegroundColor Red
        exit 1
    }
}

if (-not (Test-Path $apk)) {
    Write-Host "APK not found: $apk" -ForegroundColor Red
    exit 1
}

# Always overwrite the same file name; never create new files on the desktop.
Copy-Item $apk $dest -Force

$srcHash = (Get-FileHash $apk -Algorithm SHA256).Hash
$dstHash = (Get-FileHash $dest -Algorithm SHA256).Hash
$mb = [math]::Round((Get-Item $dest).Length / 1MB, 2)

Write-Host ''
Write-Host "Published: $dest ($mb MB)"
Write-Host "SHA256 match = $($srcHash -eq $dstHash)"
