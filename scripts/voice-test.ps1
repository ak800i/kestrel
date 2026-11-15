# Runs the device tests of Kestrel's speech engines (the package dev.kestrel.app.voice) on one test device (design spec,
# section 16.4). They need the speech models, so the script installs Kestrel, copies the models with
# scripts\push-models.ps1, and keeps Kestrel and its test APK installed afterwards, because uninstalling Kestrel deletes
# the models. The tests skip themselves when the models are missing, so a run with a skipped test fails. Afterwards the
# script prints the timings and memory use that the tests logged. After Ctrl+C, it ends with exit code 1.
# Example: scripts\voice-test.ps1 -Serial emulator-5554
param([Parameter(Mandatory = $true)][string]$Serial)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
$code = 1
$ended = $false
$failed = $false
try {
    Use-KestrelToolchain
    Assert-TestDevice -Serial $Serial
    & "$PSScriptRoot\install.ps1" -Serial $Serial
    $code = $LASTEXITCODE
    if ($code -eq 0) {
        & "$PSScriptRoot\push-models.ps1" -Serial $Serial
        $results = Join-Path $PSScriptRoot '..\app\build\outputs\androidTest-results\connected'
        if (Test-Path -LiteralPath $results) { Remove-Item -LiteralPath $results -Recurse -Force }
        $adb = Get-Adb
        # The tests log their measurements; clearing the log first keeps only this run's lines.
        & $adb -s $Serial logcat -c
        & "$PSScriptRoot\connected-test.ps1" -Serial $Serial ':app:connectedDebugAndroidTest' `
            '-Pandroid.testInstrumentationRunnerArguments.package=dev.kestrel.app.voice' `
            '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
        $code = $LASTEXITCODE
        $counts = Get-TestResultCounts -Folder $results
        Write-Host "Voice tests: $($counts.Tests) run, $($counts.Failures) failed, $($counts.Errors) errors, $($counts.Skipped) skipped."
        & $adb -s $Serial logcat -d -s 'KestrelVoiceTest:I' | ForEach-Object { Write-Host $_ }
        if ($code -eq 0 -and ($counts.Tests -eq 0 -or $counts.Skipped -gt 0)) {
            Write-Host 'The run has no tests or has skipped tests, so it fails.'
            $code = 1
        }
    }
    $ended = $true
} catch {
    $failed = $true
    throw
} finally {
    # After a stop, such as Ctrl+C, PowerShell would end the script with exit code 0. A failed run ends with its error.
    if (-not ($ended -or $failed)) {
        # As in connected-test.ps1: pwsh -File ends a stopped script that declares parameters with exit code 0, even
        # after 'exit 1', unless the script gives the host its exit code, which it does only when nothing called it.
        if (@(Get-PSCallStack).Count -eq 1) { $Host.SetShouldExit(1) }
        exit 1
    }
}
exit $code