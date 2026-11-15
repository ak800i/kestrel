# Runs the device tests of Kestrel's speech engines (the package dev.kestrel.app.voice) on one test device (design spec,
# section 16.4). They need the speech models, so the script installs Kestrel, copies the models with
# scripts\push-models.ps1, and keeps Kestrel and its test app installed afterwards, because uninstalling Kestrel deletes
# the models. The tests skip themselves when the models are missing, so a run with a skipped test fails. Afterwards the
# script prints the timings and memory use that the tests logged.
# Example: scripts\voice-test.ps1 -Serial emulator-5554
param([Parameter(Mandatory = $true)][string]$Serial)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial
& "$PSScriptRoot\install.ps1" -Serial $Serial
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
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
if ($code -ne 0) { exit $code }
if ($counts.Tests -eq 0 -or $counts.Skipped -gt 0) {
    Write-Host 'The run has no tests or has skipped tests, so it fails.'
    exit 1
}
exit 0
