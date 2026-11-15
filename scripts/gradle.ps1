# Runs the Gradle wrapper with Android Studio's JDK and the shared Android SDK.
# Tasks that act on a device, such as installs and connected tests, run only when ANDROID_SERIAL names test devices
# (scripts\lib\Kestrel.ps1, Assert-TestDevice). Run device tests with scripts\connected-test.ps1 and install with
# scripts\install.ps1, which also put the device back. For any other call, ANDROID_SERIAL names a device that cannot
# exist, so that a device task that the name check misses, such as an abbreviated one, finds no device.
# Example: scripts\gradle.ps1 check kestrelAudit
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
# Gradle's --serial option would override ANDROID_SERIAL and bypass the device guard.
$serialOption = $args | Where-Object { $_ -like '--serial*' } | Select-Object -First 1
if ($serialOption) { throw "Refusing the Gradle argument '$serialOption'. Choose the device with ANDROID_SERIAL." }
$deviceTasks = @(Get-DeviceTaskArgument -Arguments $args)
if ($deviceTasks.Count -gt 0) {
    if (-not $env:ANDROID_SERIAL) {
        throw "Refusing the device task '$($deviceTasks[0])' without ANDROID_SERIAL. Run device tests with scripts\connected-test.ps1 and install with scripts\install.ps1."
    }
    foreach ($serial in $env:ANDROID_SERIAL -split ',') { Assert-TestDevice -Serial $serial.Trim() }
}
# The script runs in the caller's PowerShell, so ANDROID_SERIAL is put back afterwards.
$callerSerial = $env:ANDROID_SERIAL
$code = 1
try {
    if ($deviceTasks.Count -eq 0) { $env:ANDROID_SERIAL = 'kestrel-no-device' }
    & "$PSScriptRoot\..\gradlew.bat" --console=plain @args
    $code = $LASTEXITCODE
} finally {
    $env:ANDROID_SERIAL = $callerSerial
}
exit $code
