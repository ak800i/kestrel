# Runs Gradle's connected (on-device) tests on one test device only.
# Example: scripts\connected-test.ps1 -Serial emulator-5554 :core:privacy:connectedDebugAndroidTest
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)][string[]]$Tasks
)
$ErrorActionPreference = 'Stop'
# Gradle's --serial option would override ANDROID_SERIAL and bypass the device guard.
$serialOption = $Tasks | Where-Object { $_ -like '--serial*' } | Select-Object -First 1
if ($serialOption) { throw "Refusing the Gradle argument '$serialOption'. Choose the device with -Serial." }
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial
# The Android Gradle plugin runs connected tests only on the devices listed in ANDROID_SERIAL.
$env:ANDROID_SERIAL = $Serial
# The device tests replace the list of enabled accessibility services, so it is put back afterwards, even when the
# tests fail or are interrupted.
$adb = Get-Adb
$saved = foreach ($name in 'enabled_accessibility_services', 'accessibility_enabled') {
    [pscustomobject]@{ Name = $name; Value = "$(& $adb -s $Serial shell settings get secure $name)".Trim() }
}
$code = 1
try {
    & "$PSScriptRoot\..\gradlew.bat" --console=plain @Tasks
    $code = $LASTEXITCODE
} finally {
    foreach ($setting in $saved) {
        if ($setting.Value -in '', 'null') {
            & $adb -s $Serial shell settings delete secure $setting.Name | Out-Null
        } else {
            & $adb -s $Serial shell settings put secure $setting.Name "'$($setting.Value)'" | Out-Null
        }
    }
}
exit $code
