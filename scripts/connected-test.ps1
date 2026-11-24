# Runs Gradle's connected (on-device) tests on one test device only, and keeps Kestrel and its data on it.
# It runs only connected test tasks, and it always passes the Gradle properties with which the Android Gradle plugin
# leaves Kestrel installed after the tests and clears none of its data ($KestrelDataProperties in scripts\lib\Kestrel.ps1).
# It refuses a caller's argument that sets one of these properties otherwise, Gradle's --serial option, and every other
# task.
# Afterwards, also when the tests fail or Ctrl+C interrupts them, it checks that the serial still names the test device,
# puts back the device's accessibility settings, and uninstalls each package that Gradle installs for the tests (the
# fixture app dev.kestrel.testapp and the test APKs, such as dev.kestrel.test) that was not installed before. Then it
# checks that Kestrel kept its data: when Kestrel was installed before the tests and is now missing, or was installed
# again, which its first-install time in 'dumpsys package' shows, it writes an error that names the loss and ends with
# exit code 1, also when the tests passed. It does not try to bring the data back. If the device check fails, or Ctrl+C
# interrupts the script while it puts these back, it warns with the commands that do it by hand. If the device check
# fails, it puts nothing back and ends with exit code 1, also when the tests passed. After Ctrl+C, it ends with exit
# code 1.
# Example: scripts\connected-test.ps1 -Serial emulator-5554 :core:privacy:connectedDebugAndroidTest
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)][string[]]$Tasks
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
$gradleArguments = Get-ConnectedTestArguments -Arguments $Tasks
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial
# The Android Gradle plugin runs connected tests only on the devices listed in ANDROID_SERIAL.
$env:ANDROID_SERIAL = $Serial
# The device tests replace the list of enabled accessibility services, and Android then clears the shortcuts of the
# services that it turns off. So the accessibility settings are put back afterwards, even when the tests fail or are
# interrupted. If they cannot be read, the tests do not start.
$saved = Get-AccessibilitySettings -Serial $Serial
# Kestrel's first-install time and the test packages that the device has before the tests. If they cannot be read, the
# tests do not start, because the script could then not tell afterwards whether Kestrel kept its data.
$packagesBefore = Get-KestrelPackageState -Serial $Serial
# The commands that put the device back by hand. As Restore-AccessibilitySettings does, they put back the list of
# services first, and they delete the list and the switch if these were not there before.
$byHand = @(foreach ($setting in @('enabled_accessibility_services', 'accessibility_enabled') + @($saved.Keys) | Select-Object -Unique) {
        $value = if ($saved.Contains($setting)) { $saved[$setting] } else { 'null' }
        if ($value -ne 'null') {
            "settings put secure $setting '$($value -replace "'", "'\''")'"
        } elseif ($setting -in 'enabled_accessibility_services', 'accessibility_enabled') {
            "settings delete secure $setting"
        }
    })
$byHand += Format-TestPackageRemoval -Keep $packagesBefore.TestPackages
$code = 1
$testsEnded = $false
$failed = $false
$restored = $false
$refusal = $null
try {
    & "$PSScriptRoot\..\gradlew.bat" --console=plain @gradleArguments
    $code = $LASTEXITCODE
    $testsEnded = $true
} catch {
    $failed = $true
    throw
} finally {
    # A stop, such as Ctrl+C, that comes while a finally block runs ends that block at once, and PowerShell then runs
    # the finally blocks around it in full. So the restore runs in a try block of its own, whose finally block warns
    # when the restore did not finish.
    try {
        # The tests take minutes, in which the device can end and another one can take its serial.
        try { Assert-TestDevice -Serial $Serial } catch { $refusal = $_.Exception.Message; throw }
        # The restore must run even when a command before it fails.
        $ErrorActionPreference = 'Continue'
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
        # A run whose tests passed still fails when the device keeps changed settings.
        if (-not (Restore-AccessibilitySettings -Serial $Serial -Saved $saved) -and $code -eq 0) { $code = 1 }
        $packagesAfter = try {
            Get-KestrelPackageState -Serial $Serial
        } catch {
            Write-Warning ("Could not read the packages of $Serial ($($_.Exception.Message)), so the script could not check that Kestrel " +
                "kept its data, nor uninstall the test packages. Run: & '$((Get-Adb) -replace "'", "''")' -s $Serial shell " +
                "'$((Format-TestPackageRemoval -Keep $packagesBefore.TestPackages) -replace "'", "''")'")
            $null
        }
        if ($null -eq $packagesAfter) {
            if ($code -eq 0) { $code = 1 }
        } else {
            $loss = Get-KestrelDataLoss -Before $packagesBefore -After $packagesAfter -Serial $Serial
            if ($loss) {
                Write-Error -Message $loss -ErrorAction Continue
                $code = 1
            }
            $installed = @($packagesAfter.TestPackages | Where-Object { $packagesBefore.TestPackages -notcontains $_ })
            if (-not (Remove-TestPackages -Serial $Serial -Packages $installed) -and $code -eq 0) { $code = 1 }
        }
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
