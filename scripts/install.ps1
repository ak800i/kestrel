# Builds the debug APK and installs it with adb install -r (design spec, sections 13.6 and 15).
# The same Gradle call runs the privacy audit (section 13.2), so nothing is installed when the audit fails.
# Installing with adb avoids Android's restricted-settings block for accessibility services.
# Example: scripts\install.ps1 -Serial emulator-5554
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    # Only for the owner's decision to start daily use on the Galaxy S23+.
    [switch]$AllowDailyPhone
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial -AllowDailyPhone:$AllowDailyPhone
& "$PSScriptRoot\..\gradlew.bat" --console=plain ':app:assembleDebug' 'kestrelAudit'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Get-Adb) -s $Serial install -r (Join-Path $PSScriptRoot '..\app\build\outputs\apk\debug\app-debug.apk')
exit $LASTEXITCODE
