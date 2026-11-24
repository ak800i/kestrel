# Runs the Gradle wrapper with Android Studio's JDK and the shared Android SDK, for builds and checks on this computer.
# It refuses every task that can install, uninstall or run tests on a device, also by an abbreviated name, with or
# without ANDROID_SERIAL, and Gradle's --serial option (Assert-GradleBuildArguments in scripts\lib\Kestrel.ps1). It
# reads the command line as Gradle does, so the value of an option, such as --tests dev.kestrel.app.X, and a task that
# -x excludes are no tasks for this check. Run device tests with scripts\connected-test.ps1, which keeps Kestrel and its
# data, and install with scripts\install.ps1.
# While Gradle runs, ANDROID_SERIAL names a device that cannot exist, so that a device task that the check missed finds
# no device.
# Example: scripts\gradle.ps1 check kestrelAudit
. "$PSScriptRoot\lib\Kestrel.ps1"
Assert-GradleBuildArguments -Arguments $args
Use-KestrelToolchain
# The script runs in the caller's PowerShell, so ANDROID_SERIAL is put back afterwards.
$callerSerial = $env:ANDROID_SERIAL
$code = 1
try {
    $env:ANDROID_SERIAL = 'kestrel-no-device'
    & "$PSScriptRoot\..\gradlew.bat" --console=plain @args
    $code = $LASTEXITCODE
} finally {
    $env:ANDROID_SERIAL = $callerSerial
}
exit $code
