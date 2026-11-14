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
# tests fail or are interrupted. If the list cannot be read, the tests do not start.
$saved = foreach ($name in 'enabled_accessibility_services', 'accessibility_enabled') {
    [pscustomobject]@{ Name = $name; Value = Get-SecureSetting -Serial $Serial -Name $name }
}
$code = 1
try {
    & "$PSScriptRoot\..\gradlew.bat" --console=plain @Tasks
    $code = $LASTEXITCODE
} finally {
    # Tests that still run on the device after Gradle was interrupted would replace the list again, so they stop first.
    # Android then removes Kestrel's service from the list itself, and the restore waits for that, so that Android's
    # change does not overwrite it.
    & (Get-Adb) -s $Serial shell am force-stop dev.kestrel | Out-Null
    $deadline = (Get-Date).AddSeconds(5)
    while ((Get-Date) -lt $deadline) {
        $services = try { Get-SecureSetting -Serial $Serial -Name enabled_accessibility_services } catch { '' }
        if ($services -notlike '*dev.kestrel/*') { break }
        Start-Sleep -Milliseconds 500
    }
    foreach ($setting in $saved) { Set-SecureSetting -Serial $Serial -Name $setting.Name -Value $setting.Value }
}
exit $code
