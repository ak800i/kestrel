# Runs the device tests of the instant commands (the package dev.kestrel.app.commands) on the kestrel-api37 emulator
# (design spec, section 16.3), and refuses every other device, because the tests change the device's state. It reads that
# state first and puts it back at the end, also when the run failed or Ctrl+C interrupted it: Wi-Fi and Bluetooth, the
# ringer mode and the volumes of the call, ring, music, alarm and notification streams, the list of Quick Settings tiles,
# the lock-screen setting (it also wakes the screen and dismisses the keyguard), users called KestrelTest, raw contacts of
# the account kestrel-test, the call-log rows and images newer than the largest IDs that it saw first, the test app's
# notifications (it stops the test app), and the runtime permissions that the tests grant, which it revokes when they
# were not granted before. Kestrel's microphone and notification permissions, which the tests grant, stay granted, as
# after voice-test.ps1, because revoking a permission ends Kestrel's process. When a read of that state fails, the script
# puts back nothing. connected-test.ps1, which runs the tests, puts back the accessibility settings, the magnification's
# among them, and turning Kestrel's accessibility service off ends a magnification that it started. It does not put back
# Kestrel's own settings: the tests store the default options of the instant commands and Off as the listening mode, so
# a run leaves Kestrel off with the default command options.
# It passes the instrumentation argument kestrelCommands=true, without which every command test skips itself, so a run
# with a skipped test, or without tests, fails. Kestrel and its test APK stay installed. After Ctrl+C, it ends with exit
# code 1. Each command at the end runs only if the serial still names the emulator that the script accepted at the start;
# if one cannot run, the script warns with the command that does it by hand and ends with exit code 1.
# Example: scripts\command-test.ps1 -Serial emulator-5554
#          scripts\command-test.ps1 -Serial emulator-5554 -Class TouchCommandsTest,GestureCommandsTest#swipeUpOnANumber
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    # Test classes of dev.kestrel.app.commands by their simple names, each with an optional '#method'.
    [string[]]$Class = @()
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"

# Runs a command that puts the device back, and returns whether it worked. When it did not, it warns with the command
# that does it by hand.
function Invoke-DeviceRestore([string]$What, [string]$Command) {
    try {
        Invoke-SettingsCommand -Serial $Serial -Command $Command | Out-Null
        $true
    } catch {
        # The message is Invoke-SettingsCommand's, which holds only the command, the serial and adb's own error text.
        Write-Warning ("Could not $What on $Serial ($($_.Exception.Message)). If the emulator has another serial now, use " +
            "that one. Run: & '$((Get-Adb) -replace "'", "''")' -s $Serial shell $Command")
        $false
    }
}

# 'content' writes a refusal to stderr and still ends with exit code 0, and Invoke-SettingsCommand keeps only stdout, so
# a delete runs with its stderr joined to stdout. A delete that works prints nothing. The device's answer names only the
# provider and Java classes.
function Invoke-ContentDelete([string]$What, [string]$Arguments) {
    $command = "content delete $Arguments 2>&1"
    $answer = @(try { Invoke-SettingsCommand -Serial $Serial -Command $command } catch { $_.Exception.Message })
    if ($answer.Count -eq 0) { return $true }
    Write-Warning ("Could not $What on $Serial (the device answered '$($answer[0])'). If the emulator has another serial now, " +
        "use that one. Run: & '$((Get-Adb) -replace "'", "''")' -s $Serial shell $command")
    $false
}

# Reads one line of the device's answer, without spaces around it.
function Read-DeviceValue([string]$Command) {
    ((Invoke-SettingsCommand -Serial $Serial -Command $Command) -join "`n").Trim()
}

$code = 1
$ended = $false
$failed = $false
$saved = $null
# The streams whose volume the tests change: the call, ring, music, alarm and notification streams.
$streams = @(0, 2, 3, 4, 5)
$ringerModes = @{ '0' = 'SILENT'; '1' = 'VIBRATE'; '2' = 'NORMAL' }
# The runtime permissions that the tests may grant and the script revokes afterwards. Kestrel's microphone and
# notification permissions are not among them: they stay granted, as after voice-test.ps1.
$permissions = [ordered]@{
    'dev.kestrel' = @('android.permission.READ_CONTACTS', 'android.permission.READ_PHONE_STATE')
    'dev.kestrel.testapp' = @('android.permission.POST_NOTIFICATIONS')
}
try {
    Use-KestrelToolchain
    if ($Serial -notlike 'emulator-*') { throw "Refusing ${Serial}: the command tests run only on the kestrel-api37 emulator." }
    Assert-TestDevice -Serial $Serial
    $selection = Get-CommandTestSelection -Class $Class
    # Read whole before it counts, so that a read that fails leaves the restore nothing to put back.
    $before = [ordered]@{
        Wifi = Read-DeviceValue 'settings get global wifi_on'
        Bluetooth = Read-DeviceValue 'settings get global bluetooth_on'
        Ringer = Read-DeviceValue 'settings get global mode_ringer'
        # A Hashtable, because an [ordered] dictionary reads an integer key as a position.
        Volumes = @{}
        Tiles = Get-SecureSetting -Serial $Serial -Name 'sysui_qs_tiles'
        LockScreenOff = (Read-DeviceValue 'cmd lock_settings get-disabled') -eq 'true'
        CallLog = Get-LargestRowId -Rows (Invoke-SettingsCommand -Serial $Serial -Command 'content query --uri content://call_log/calls --projection _id')
        Images = Get-LargestRowId -Rows (Invoke-SettingsCommand -Serial $Serial -Command 'content query --uri content://media/external/images/media --projection _id')
        Granted = @{}
    }
    foreach ($stream in $streams) {
        $before.Volumes[$stream] = Get-StreamVolume -Lines (Invoke-SettingsCommand -Serial $Serial -Command "cmd media_session volume --stream $stream --get")
    }
    foreach ($package in $permissions.Keys) {
        $dump = Invoke-SettingsCommand -Serial $Serial -Command "dumpsys package $package"
        $before.Granted[$package] = @(Get-GrantedPermissions -Dump $dump -Permissions $permissions[$package])
    }
    $saved = $before
    $results = Join-Path $PSScriptRoot '..\app\build\outputs\androidTest-results\connected'
    if (Test-Path -LiteralPath $results) { Remove-Item -LiteralPath $results -Recurse -Force }
    $tasks = @(
        ':app:connectedDebugAndroidTest'
        "-Pandroid.testInstrumentationRunnerArguments.$selection"
        '-Pandroid.testInstrumentationRunnerArguments.kestrelCommands=true'
        '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
        # As in voice-test.ps1: no instrumentation argument of an earlier run may be reused by mistake.
        '--no-configuration-cache'
    )
    & "$PSScriptRoot\connected-test.ps1" -Serial $Serial @tasks
    $code = $LASTEXITCODE
    $counts = Get-TestResultCounts -Folder $results
    Write-Host "Command tests: $($counts.Tests) run, $($counts.Failures) failed, $($counts.Errors) errors, $($counts.Skipped) skipped."
    if ($code -eq 0 -and ($counts.Tests -eq 0 -or $counts.Skipped -gt 0)) {
        Write-Host 'The run has no tests or has skipped tests, so it fails.'
        $code = 1
    }
    $ended = $true
} catch {
    $failed = $true
    throw
} finally {
    # A stop, such as Ctrl+C, that comes while a finally block runs ends that block at once, so the restore runs in a try
    # block of its own, and the exit code is set in its finally block.
    try {
        if ($saved) {
            # The ringer mode first, because it changes the ring volume.
            $ringer = try { Read-DeviceValue 'settings get global mode_ringer' } catch { $null }
            if ($ringer -ne $saved.Ringer -and $ringerModes.Contains($saved.Ringer) -and
                -not (Invoke-DeviceRestore 'put the ringer mode back' "cmd audio set-ringer-mode $($ringerModes[$saved.Ringer])")) {
                $code = 1
            }
            foreach ($stream in $saved.Volumes.Keys) {
                $now = try { Get-StreamVolume -Lines (Invoke-SettingsCommand -Serial $Serial -Command "cmd media_session volume --stream $stream --get") } catch { -1 }
                if ($now -ne $saved.Volumes[$stream] -and
                    -not (Invoke-DeviceRestore "put the volume of stream $stream back" "cmd audio set-volume $stream $($saved.Volumes[$stream])")) {
                    $code = 1
                }
            }
            $wifi = try { Read-DeviceValue 'settings get global wifi_on' } catch { $null }
            if (($wifi -eq '0') -ne ($saved.Wifi -eq '0')) {
                $state = if ($saved.Wifi -eq '0') { 'disabled' } else { 'enabled' }
                if (-not (Invoke-DeviceRestore 'put Wi-Fi back' "cmd wifi set-wifi-enabled $state")) { $code = 1 }
            }
            $bluetooth = try { Read-DeviceValue 'settings get global bluetooth_on' } catch { $null }
            if (($bluetooth -eq '0') -ne ($saved.Bluetooth -eq '0')) {
                $state = if ($saved.Bluetooth -eq '0') { 'disable' } else { 'enable' }
                if (-not (Invoke-DeviceRestore 'put Bluetooth back' "cmd bluetooth_manager $state")) { $code = 1 }
            }
            $tiles = try { Get-SecureSetting -Serial $Serial -Name 'sysui_qs_tiles' } catch { $null }
            if ($tiles -cne $saved.Tiles -and -not (Set-QuickSettingsTiles -Serial $Serial -Value $saved.Tiles)) { $code = 1 }
            if ($saved.LockScreenOff -and -not (Invoke-DeviceRestore 'turn off the lock screen again' 'cmd lock_settings set-disabled true')) {
                $code = 1
            }
            $users = try { @(Get-UserIdsNamed -Lines (Invoke-SettingsCommand -Serial $Serial -Command 'pm list users') -Name 'KestrelTest') } catch { @() }
            foreach ($user in $users) {
                if (-not (Invoke-DeviceRestore "remove the test user $user" "pm remove-user $user")) { $code = 1 }
            }
            $contacts = "--uri 'content://com.android.contacts/raw_contacts?caller_is_syncadapter=true' --where account_name=\'kestrel-test\'"
            if (-not (Invoke-ContentDelete 'delete the test contacts' $contacts)) { $code = 1 }
            # Only when something is new, so that a run that made nothing needs no write.
            $callLog = try { Get-LargestRowId -Rows (Invoke-SettingsCommand -Serial $Serial -Command 'content query --uri content://call_log/calls --projection _id') } catch { $null }
            if ($callLog -ne $saved.CallLog -and
                -not (Invoke-ContentDelete 'delete the new call-log rows' "--uri content://call_log/calls --where '_id>$($saved.CallLog)'")) {
                $code = 1
            }
            $images = try { Get-LargestRowId -Rows (Invoke-SettingsCommand -Serial $Serial -Command 'content query --uri content://media/external/images/media --projection _id') } catch { $null }
            if ($images -ne $saved.Images -and
                -not (Invoke-ContentDelete 'delete the new screenshots' "--uri content://media/external/images/media --where '_id>$($saved.Images)'")) {
                $code = 1
            }
            # Stopping the test app also removes its notifications.
            if (-not (Invoke-DeviceRestore "stop the test app" 'am force-stop dev.kestrel.testapp')) { $code = 1 }
            # Last, because revoking a permission ends the app's process.
            foreach ($package in $permissions.Keys) {
                $dump = try { Invoke-SettingsCommand -Serial $Serial -Command "dumpsys package $package" } catch { @() }
                foreach ($permission in @(Get-GrantedPermissions -Dump $dump -Permissions $permissions[$package])) {
                    if ($permission -in $saved.Granted[$package]) { continue }
                    if (-not (Invoke-DeviceRestore "revoke $permission of $package" "pm revoke $package $permission")) { $code = 1 }
                }
            }
            if (-not (Invoke-DeviceRestore 'wake the screen' 'input keyevent KEYCODE_WAKEUP')) { $code = 1 }
            if (-not (Invoke-DeviceRestore 'dismiss the keyguard' 'wm dismiss-keyguard')) { $code = 1 }
        }
    } finally {
        # After a stop, such as Ctrl+C, PowerShell would end the script with exit code 0. A failed run ends with its error.
        if (-not ($ended -or $failed)) {
            # As in connected-test.ps1: pwsh -File ends a stopped script that declares parameters with exit code 0, even
            # after 'exit 1', unless the script gives the host its exit code, which it does only when nothing called it.
            if (@(Get-PSCallStack).Count -eq 1) { $Host.SetShouldExit(1) }
            exit 1
        }
    }
}
exit $code
