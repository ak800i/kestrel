# Runs Gradle's connected (on-device) tests on one test device only, and keeps Kestrel and the owner's data on it.
# It accepts only connected test tasks, instrumentation arguments, '-x <task>' and a few flags that change only what
# Gradle prints and caches, and it always passes the Gradle properties with which the Android Gradle plugin leaves
# Kestrel installed after the tests and clears none of its data (Get-ConnectedTestArguments and $KestrelDataProperties
# in scripts\lib\DeviceGuard.ps1). It refuses every other argument.
# On kestrel-api37 it first checks that the emulator has not degraded (Assert-EmulatorHealthy in
# scripts\lib\DeviceGuard.ps1), and stops before any test with the advice to cold-boot it when it has.
# Before the tests it reads the device's state; afterwards, also when the tests fail or Ctrl+C interrupts them, it checks
# that the serial still names the test device, and then:
#   - on kestrel-api37, it checks what AGENTS.md asks for there: that the number of Google accounts in 'dumpsys account'
#     and Kestrel's profiles, as scripts\hook.ps1 provider-standin answers (the default profile's kind and ID, whether
#     its credential is stored, and the number of Copilot profiles), did not change, so that a profile that was
#     removed or replaced, or a credential that was deleted, fails the run. When Kestrel does not answer before the
#     tests, for example because it is not installed, the script says so and compares only the accounts; when it
#     answers in another form, as an older build does, the script stops before Gradle runs and says to install the
#     current debug build, because it could not compare the profiles afterwards. The hook starts Kestrel's process, so
#     this comes before Kestrel is stopped;
#   - it stops Kestrel ('am force-stop'), which stays stopped at the end, and puts back the device's accessibility
#     settings;
#   - it checks that Kestrel was not uninstalled or installed again, either of which deletes Kestrel's data. Kestrel's
#     first-install time in 'dumpsys package' shows both, and nothing else: a 'pm clear', or a test that deletes
#     Kestrel's stores in its process, leaves the time as it was;
#   - it uninstalls each package that Gradle installed for the tests (the fixture app dev.kestrel.testapp and the test
#     APKs, such as dev.kestrel.test) and that was not installed before.
# A change of Kestrel's install or of the owner's data is written as an error that names it; the script does not try to
# bring data back. Every failed check ends the script with exit code 1, also when the tests passed. If the device check
# fails, or Ctrl+C interrupts the script while it puts the device back, it warns with the commands that do it by hand;
# if the device check fails, it puts nothing back. After Ctrl+C, it ends with exit code 1. ANDROID_SERIAL, which names
# the device for Gradle, gets the caller's value back at the end.
# Example: scripts\connected-test.ps1 -Serial emulator-5554 :core:privacy:connectedDebugAndroidTest
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)][string[]]$Tasks
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
. "$PSScriptRoot\lib\Session.ps1"
. "$PSScriptRoot\lib\DeviceGuard.ps1"
$gradleArguments = Get-ConnectedTestArguments -Arguments $Tasks
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial
# A degraded kestrel-api37 would fail the tests for reasons that are not Kestrel's, so the tests do not start on it.
Assert-EmulatorHealthy -Serial $Serial
# The device tests replace the list of enabled accessibility services, and Android then clears the shortcuts of the
# services that it turns off. So the accessibility settings are put back afterwards, even when the tests fail or are
# interrupted. If they cannot be read, the tests do not start.
$saved = Get-AccessibilitySettings -Serial $Serial
# Kestrel's first-install time and the test packages before the tests, and on kestrel-api37 the owner's data. If they
# cannot be read, the tests do not start, because the script could then not compare them afterwards.
$packagesBefore = Get-KestrelPackageState -Serial $Serial
$ownerBefore = if (Test-OwnerDataDevice -Serial $Serial) { Get-OwnerDataState -Serial $Serial } else { $null }
if ($ownerBefore -and -not $ownerBefore.Profiles) {
    Write-Warning "Could not read Kestrel's profiles on $Serial before the tests ($($ownerBefore.ProfilesUnread)), so the script compares only the Google accounts there."
}
# The commands that put the device back by hand (Get-AccessibilityRestoreCommands): the list of services first, and
# they delete the list and the switch if these were not there before.
$byHand = @(Get-AccessibilityRestoreCommands -Saved $saved)
$byHand += Format-TestPackageRemoval -Keep $packagesBefore.TestPackages
# The script runs in the caller's PowerShell, so ANDROID_SERIAL is put back afterwards.
$callerSerial = $env:ANDROID_SERIAL
$code = 1
$testsEnded = $false
$failed = $false
$restored = $false
$refusal = $null
try {
    # The Android Gradle plugin runs connected tests only on the devices listed in ANDROID_SERIAL.
    $env:ANDROID_SERIAL = $Serial
    & "$PSScriptRoot\..\gradlew.bat" --console=plain @gradleArguments
    $code = $LASTEXITCODE
    $testsEnded = $true
} catch {
    $failed = $true
    throw
} finally {
    $env:ANDROID_SERIAL = $callerSerial
    # A stop, such as Ctrl+C, that comes while a finally block runs ends that block at once, and PowerShell then runs
    # the finally blocks around it in full. So the restore runs in a try block of its own, whose finally block warns
    # when the restore did not finish.
    try {
        # The tests take minutes, in which the device can end and another one can take its serial.
        try { Assert-TestDevice -Serial $Serial } catch { $refusal = $_.Exception.Message; throw }
        # The restore must run even when a command before it fails.
        $ErrorActionPreference = 'Continue'
        # A run fails when the owner's data changed. Reading Kestrel's profiles starts Kestrel's process, so it comes
        # before the stop below, after which Kestrel stays stopped on every device.
        if ($ownerBefore) {
            $problems = @(Compare-OwnerData -Serial $Serial -Before $ownerBefore -During 'the tests')
            foreach ($problem in $problems) { Write-Error -Message $problem -ErrorAction Continue }
            if ($problems.Count -gt 0 -and $code -eq 0) { $code = 1 }
        }
        # Tests that still run on the device after Gradle was interrupted would replace the list again, so they stop
        # first. Android then removes Kestrel's service from the list itself, and the restore waits for that, so that
        # Android's change does not overwrite it.
        & (Get-Adb) -s $Serial shell am force-stop dev.kestrel | Out-Null
        $deadline = (Get-Date).AddSeconds(5)
        while ((Get-Date) -lt $deadline) {
            $services = try { Get-SecureSetting -Serial $Serial -Name enabled_accessibility_services } catch { $null }
            if ($null -ne $services -and $services -notlike '*dev.kestrel/*') { break }
            Start-Sleep -Milliseconds 500
        }
        # A run whose tests passed still fails when the device keeps changed settings, when Kestrel was uninstalled or
        # installed again, or when a test package stays.
        if (-not (Restore-AccessibilitySettings -Serial $Serial -Saved $saved) -and $code -eq 0) { $code = 1 }
        if (-not (Complete-TestPackages -Serial $Serial -Before $packagesBefore) -and $code -eq 0) { $code = 1 }
        $restored = $true
    } finally {
        if (-not $restored) {
            $command = "& '$((Get-Adb) -replace "'", "''")' -s $Serial shell '$(($byHand -join '; ') -replace "'", "''")'"
            if ($refusal) {
                Write-Warning ("The device was not put back, because $Serial no longer names it ($refusal). Run this command, with " +
                    "$Serial replaced by the serial that the test device has now: $command")
            } else {
                Write-Warning "The device $Serial was not put back, because the restore was interrupted. Run: $command"
            }
        }
        # After a stop, such as Ctrl+C, PowerShell would end the script with exit code 0. A failed run ends with its error.
        if (-not $restored -or -not ($testsEnded -or $failed)) {
            # pwsh -File ends a stopped script that declares parameters with exit code 0, even after 'exit 1', unless the
            # script gives the host its exit code. Run from a prompt, that would close the window, so the script does it
            # only when nothing called it.
            if (@(Get-PSCallStack).Count -eq 1) { $Host.SetShouldExit(1) }
            exit 1
        }
    }
}
exit $code
