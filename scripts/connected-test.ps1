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
& "$PSScriptRoot\..\gradlew.bat" --console=plain @Tasks
exit $LASTEXITCODE
