# Runs the device tests of Kestrel's speech engines (the package dev.kestrel.app.voice) on one test device (design spec,
# section 16.4). They need the speech models, so the script installs Kestrel, copies the models with
# scripts\push-models.ps1, and keeps Kestrel and its test APK installed afterwards, because uninstalling Kestrel deletes
# the models. The tests skip themselves when the models are missing, so a run with a skipped test fails. Afterwards the
# script prints the timings and memory use that the tests logged. After Ctrl+C, it ends with exit code 1.
# The test MicCaptureTest#silencedFollowsTheMicrophonesPrivacyToggle switches the device's microphone privacy toggle on
# and off. If the test process dies in between, every app on the device hears only silence. So on a phone (a serial that
# does not start with 'emulator-') the script leaves that test out, and on an emulator it switches the toggle off at the
# end with 'cmd sensor_privacy disable 0 microphone', also when the run failed or was interrupted. The run fails when
# that test ran on a phone or did not run on an emulator.
# Example: scripts\voice-test.ps1 -Serial emulator-5554
param([Parameter(Mandatory = $true)][string]$Serial)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
$code = 1
$ended = $false
$failed = $false
$emulator = $Serial -like 'emulator-*'
$reached = $false
try {
    Use-KestrelToolchain
    Assert-TestDevice -Serial $Serial
    $reached = $true
    & "$PSScriptRoot\install.ps1" -Serial $Serial
    $code = $LASTEXITCODE
    if ($code -eq 0) {
        & "$PSScriptRoot\push-models.ps1" -Serial $Serial
        $results = Join-Path $PSScriptRoot '..\app\build\outputs\androidTest-results\connected'
        if (Test-Path -LiteralPath $results) { Remove-Item -LiteralPath $results -Recurse -Force }
        $adb = Get-Adb
        # The tests log their measurements; clearing the log first keeps only this run's lines.
        & $adb -s $Serial logcat -c
        $toggleTest = 'dev.kestrel.app.voice.MicCaptureTest#silencedFollowsTheMicrophonesPrivacyToggle'
        $tasks = @(
            ':app:connectedDebugAndroidTest'
            '-Pandroid.testInstrumentationRunnerArguments.package=dev.kestrel.app.voice'
            '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
            # A reused configuration cache keeps the instrumentation arguments of the run that stored it, so a notClass
            # from a run on a phone would stay in later runs on an emulator, and the other way round.
            '--no-configuration-cache'
        )
        if (-not $emulator) { $tasks += "-Pandroid.testInstrumentationRunnerArguments.notClass=$toggleTest" }
        & "$PSScriptRoot\connected-test.ps1" -Serial $Serial @tasks
        $code = $LASTEXITCODE
        $counts = Get-TestResultCounts -Folder $results
        Write-Host "Voice tests: $($counts.Tests) run, $($counts.Failures) failed, $($counts.Errors) errors, $($counts.Skipped) skipped."
        & $adb -s $Serial logcat -d -s 'KestrelVoiceTest:I' | ForEach-Object { Write-Host $_ }
        if ($code -eq 0 -and ($counts.Tests -eq 0 -or $counts.Skipped -gt 0)) {
            Write-Host 'The run has no tests or has skipped tests, so it fails.'
            $code = 1
        }
        # The JUnit XML names each test as name="method" classname="class".
        $method = ($toggleTest -split '#')[1]
        $toggleRan = [bool](Test-Path -LiteralPath $results) -and
            [bool](Get-ChildItem -LiteralPath $results -Recurse -Filter 'TEST-*.xml' | Select-String -SimpleMatch "name=`"$method`"")
        if ($code -eq 0 -and $toggleRan -ne $emulator) {
            Write-Host "The test $toggleTest should run only on an emulator, so the run fails."
            $code = 1
        }
    }
    $ended = $true
} catch {
    $failed = $true
    throw
} finally {
    # A stop, such as Ctrl+C, that comes while a finally block runs ends that block at once, so the toggle is switched off
    # in a try block of its own, and the exit code is set in its finally block.
    try {
        if ($emulator -and $reached) {
            # Windows PowerShell 5.1 would stop at adb's first error line.
            $ErrorActionPreference = 'Continue'
            & (Get-Adb) -s $Serial shell cmd sensor_privacy disable 0 microphone
            if ($LASTEXITCODE -ne 0) {
                Write-Warning ("Could not switch off the microphone's privacy toggle on $Serial. " +
                    "Run: adb -s $Serial shell cmd sensor_privacy disable 0 microphone")
                $code = 1
            }
        }
    } finally {
        # After a stop, such as Ctrl+C, PowerShell would end the script with exit code 0. A failed run ends with its
        # error.
        if (-not ($ended -or $failed)) {
            # As in connected-test.ps1: pwsh -File ends a stopped script that declares parameters with exit code 0,
            # even after 'exit 1', unless the script gives the host its exit code, which it does only when nothing
            # called it.
            if (@(Get-PSCallStack).Count -eq 1) { $Host.SetShouldExit(1) }
            exit 1
        }
    }
}
exit $code
