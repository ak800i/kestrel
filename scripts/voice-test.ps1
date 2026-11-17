# Runs the device tests of Kestrel's speech engines (the package dev.kestrel.app.voice) on one test device (design spec,
# section 16.4). They need the speech models, so the script installs Kestrel, copies the models with
# scripts\push-models.ps1, and keeps Kestrel and its test APK installed afterwards, because uninstalling Kestrel deletes
# the models. The tests skip themselves when the models are missing, so a run with a skipped test fails. Afterwards the
# script prints the timings and memory use that the tests logged. After Ctrl+C, it ends with exit code 1.
# The test MicCaptureTest#silencedFollowsTheMicrophonesPrivacyToggle switches the device's microphone privacy toggle on
# and off. If the test process dies in between, every app on the device hears only silence. So on a phone (a serial that
# does not start with 'emulator-') the script leaves that test out, and on an emulator it switches the toggle off at the
# end with 'cmd sensor_privacy disable 0 microphone', also when the run failed or was interrupted.
# The test ListeningServiceTest#theMicrophoneStopsWhileTheScreenIsOffAndStartsAgainAfterTheUnlock turns on the swipe lock
# screen when the device has none and unlocks it with 'wm dismiss-keyguard', which cannot unlock a PIN, pattern or
# password, so on a phone the script leaves that test out too. So does ActivationTest#theTileTogglesListening, which adds
# Kestrel's tile to Quick Settings: no script puts the list of tiles back if the test's process dies. So does
# RefusedReplyTest#aReplyThatAndroidRefusesIsShownOnTheBar, which sets Kestrel's app op TAKE_AUDIO_FOCUS to ignore and
# back to foreground, the op's normal mode on Android 17. So do AssistTest#theAssistKeyStartsListeningAndWaitsForATask and
# AssistTest#withTheAssistantRoleOpenSettingsBringsTheSettingsToTheFront, which make Kestrel the holder of the assistant
# role. The run fails when one of these six tests ran on a phone or did not run on an emulator. On an emulator the script
# turns the lock screen off again at the end, sets TAKE_AUDIO_FOCUS back to foreground, gives the assistant role and the
# settings assistant and voice_interaction_service back as they were before the run, wakes the screen and dismisses the
# keyguard, also when the run failed or was interrupted.
# Each command at the end runs only if the serial still names the device that the script accepted at the start. If a
# command cannot run, the script warns with the command that does it by hand and ends with exit code 1.
# Example: scripts\voice-test.ps1 -Serial emulator-5554
param([Parameter(Mandatory = $true)][string]$Serial)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"

# Runs a command that puts the device back, and returns whether it worked. When it did not, it warns with what is left
# and with the command that does it by hand.
function Invoke-DeviceRestore([string]$What, [string]$Command) {
    try {
        Invoke-SettingsCommand -Serial $Serial -Command $Command | Out-Null
        $true
    } catch {
        # The message is Invoke-SettingsCommand's, which holds only the command, the serial and adb's own error text.
        Write-Warning ("Could not $What on $Serial ($($_.Exception.Message)). If the test device has another serial " +
            "now, use that one. Run: & '$((Get-Adb) -replace "'", "''")' -s $Serial shell $Command")
        $false
    }
}

$code = 1
$ended = $false
$failed = $false
$emulator = $Serial -like 'emulator-*'
$reached = $false
$lockScreenOff = $false
# The holder of the assistant role before the run, or an empty text when no app held it; $null until it is read.
$assistantHolder = $null
# The settings that name the assistant's service before the run, which Android changes with the role.
$assistantSettings = [ordered]@{}
try {
    Use-KestrelToolchain
    Assert-TestDevice -Serial $Serial
    $reached = $true
    # The lock-screen test turns on a swipe lock screen and puts the setting back itself, but a test process that died in
    # between would leave it on, so on an emulator the script reads the setting first and puts it back at the end.
    if ($emulator) {
        $lockScreenOff = ((Invoke-SettingsCommand -Serial $Serial -Command 'cmd lock_settings get-disabled') -join '').Trim() -eq 'true'
        # The assistant tests make Kestrel the holder of the role and put the holder back, unless their process dies.
        $assistantHolder = ((Invoke-SettingsCommand -Serial $Serial -Command 'cmd role get-role-holders android.app.role.ASSISTANT') -join '').Trim()
        foreach ($name in 'assistant', 'voice_interaction_service') { $assistantSettings[$name] = Get-SecureSetting -Serial $Serial -Name $name }
    }
    & "$PSScriptRoot\install.ps1" -Serial $Serial
    $code = $LASTEXITCODE
    if ($code -eq 0) {
        & "$PSScriptRoot\push-models.ps1" -Serial $Serial
        $results = Join-Path $PSScriptRoot '..\app\build\outputs\androidTest-results\connected'
        if (Test-Path -LiteralPath $results) { Remove-Item -LiteralPath $results -Recurse -Force }
        $adb = Get-Adb
        # The tests log their measurements; clearing the log first keeps only this run's lines.
        & $adb -s $Serial logcat -c
        # Tests that a phone must not run: one switches the microphone's privacy toggle, one turns on a lock screen that it
        # unlocks with adb, one changes the list of Quick Settings tiles, one refuses Kestrel's audio focus, and two make
        # Kestrel the assistant.
        $emulatorOnly = @(
            'dev.kestrel.app.voice.MicCaptureTest#silencedFollowsTheMicrophonesPrivacyToggle'
            'dev.kestrel.app.voice.ListeningServiceTest#theMicrophoneStopsWhileTheScreenIsOffAndStartsAgainAfterTheUnlock'
            'dev.kestrel.app.voice.ActivationTest#theTileTogglesListening'
            'dev.kestrel.app.voice.RefusedReplyTest#aReplyThatAndroidRefusesIsShownOnTheBar'
            'dev.kestrel.app.voice.AssistTest#theAssistKeyStartsListeningAndWaitsForATask'
            'dev.kestrel.app.voice.AssistTest#withTheAssistantRoleOpenSettingsBringsTheSettingsToTheFront'
        )
        $tasks = @(
            ':app:connectedDebugAndroidTest'
            '-Pandroid.testInstrumentationRunnerArguments.package=dev.kestrel.app.voice'
            '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
            # The configuration cache is off for this run, so that no instrumentation argument of an earlier run, such as
            # the notClass of a run on a phone, can be reused by mistake.
            '--no-configuration-cache'
        )
        # notClass takes a comma-separated list.
        if (-not $emulator) { $tasks += "-Pandroid.testInstrumentationRunnerArguments.notClass=$($emulatorOnly -join ',')" }
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
        $reports = @(if (Test-Path -LiteralPath $results) { Get-ChildItem -LiteralPath $results -Recurse -Filter 'TEST-*.xml' })
        foreach ($test in $emulatorOnly) {
            $method = ($test -split '#')[1]
            $ran = [bool]($reports | Select-String -SimpleMatch "name=`"$method`"")
            if ($code -eq 0 -and $ran -ne $emulator) {
                Write-Host "The test $test should run only on an emulator, so the run fails."
                $code = 1
            }
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
            # The tests take minutes, in which the emulator can end and another one can take its serial, so each command
            # checks the device in the same adb call. Each of them can run twice without harm, as the helper may repeat it.
            if (-not (Invoke-DeviceRestore "switch off the microphone's privacy toggle" 'cmd sensor_privacy disable 0 microphone')) {
                $code = 1
            }
            # The lock-screen test leaves the screen on and unlocked and the lock screen as it found it, unless its process
            # died in between.
            if ($lockScreenOff -and -not (Invoke-DeviceRestore 'turn off the lock screen again' 'cmd lock_settings set-disabled true')) {
                $code = 1
            }
            # The refused-reply test puts the op back itself, unless its process died in between. The mode default would
            # still refuse Kestrel's audio focus (prototype P2).
            if (-not (Invoke-DeviceRestore 'give Kestrel back its audio focus' 'cmd appops set dev.kestrel TAKE_AUDIO_FOCUS foreground')) {
                $code = 1
            }
            # The assistant test puts the role back itself, unless its process died in between. The role is exclusive, so
            # adding the earlier holder removes Kestrel. Adding the app that holds the role already grants the role's
            # permissions again, so the script changes the holder only when it differs.
            if ($null -ne $assistantHolder) {
                $holderNow = try {
                    ((Invoke-SettingsCommand -Serial $Serial -Command 'cmd role get-role-holders android.app.role.ASSISTANT') -join '').Trim()
                } catch {
                    $null
                }
                if ($holderNow -ne $assistantHolder) {
                    $role = if ($assistantHolder) {
                        "cmd role add-role-holder android.app.role.ASSISTANT $assistantHolder"
                    } else {
                        'cmd role remove-role-holder android.app.role.ASSISTANT dev.kestrel'
                    }
                    if (-not (Invoke-DeviceRestore 'give the assistant role back' $role)) { $code = 1 }
                    # Android points both settings at the holder's service a moment after the change.
                    Start-Sleep -Seconds 2
                }
                foreach ($name in $assistantSettings.Keys) {
                    $now = try { Get-SecureSetting -Serial $Serial -Name $name } catch { $null }
                    if ($now -cne $assistantSettings[$name] -and -not (Set-SecureSetting -Serial $Serial -Name $name -Value $assistantSettings[$name])) {
                        $code = 1
                    }
                }
            }
            if (-not (Invoke-DeviceRestore 'wake the screen' 'input keyevent KEYCODE_WAKEUP')) { $code = 1 }
            if (-not (Invoke-DeviceRestore 'dismiss the keyguard' 'wm dismiss-keyguard')) { $code = 1 }
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
