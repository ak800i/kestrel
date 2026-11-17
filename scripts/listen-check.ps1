# Checks the ways in which Kestrel's listening starts without a word from the owner (design spec, sections 8.5, 8.6 and
# 14.4), on a test device with Kestrel's debug build (scripts\install.ps1) and its speech models (scripts\push-models.ps1):
#   notification  while Kestrel's accessibility service is off, Android refuses a start through the hook with Kestrel
#             in the background; Kestrel's problems notification then offers "Start listening", and a tap on it in the
#             notification shade starts listening (design spec, section 14.6; spike S1). The script finds the button
#             with 'uiautomator dump', whose file it deletes and never prints, because on a phone it holds the text of
#             the owner's notifications. When the shade shows Kestrel's notification collapsed, without its button,
#             the script first expands it with a swipe down on it. If this check fails, the problems notification stays
#             until Kestrel next listens, which the later checks bring about, or until you swipe it away
#   tile      a tap on Kestrel's Quick Settings tile starts listening while Kestrel's accessibility service is off, and a
#             second tap pauses it
#   shortcut  the accessibility shortcut ('cmd accessibility call-system-action 13') starts listening, and a second press
#             pauses it
#   settings  with the accessibility service on, 'Kestrel, open settings' (a transcript that the hook sends) brings
#             Kestrel's settings app to the front
#   call      on an emulator only, because only its console can place a call ('gsm call', 'gsm cancel'): with the
#             accessibility service on and Kestrel in the background, a call rings, Kestrel shows Ringing and Android
#             does not silence its recording; "decline" (a transcript that the hook sends) declines it, and "answer"
#             answers the next one, both through the call's heads-up notification; Kestrel shows In call, and listening
#             comes back when the call ends. Then, with the permission to answer calls, a plain "answer" answers a third
#             call through Android's call API and leaves the in-call screen behind, and "answer on speaker" answers a
#             fourth one, brings the in-call screen to the front and leaves its speaker control on (the emulator has no
#             earpiece, so its calls are on the speaker from the start). Last, with the swipe lock screen on, a call
#             that wakes the locked screen starts Kestrel's recording, which Android must not silence, and "decline"
#             declines it (design spec, section 8.7; spike S1). On a phone the check is skipped and changes nothing
#   restart   after Android ended Kestrel's process ('am crash'), listening comes back once the accessibility service has
#             connected again. Android does not restart an app that crashed twice within 2 minutes, so wait 2 minutes
#             before you run the script again.
#   reboot    with -Reboot: listening comes back after a reboot. On a phone, the script runs 'adb reboot'. On an emulator,
#             'adb reboot' has crashed or frozen this computer's emulator in about half of the runs, also soon after a
#             cold boot, so the script instead runs 'sync' on it, closes it with 'adb emu kill', waits until its qemu
#             process has ended, and starts it again with scripts\start-emulator.ps1 -ColdBoot, which must give it the
#             same serial. For Kestrel, both end its process with the device and connect its accessibility service after
#             the start. Start a run with -Reboot on an emulator from a cold boot too: run & $adb -s $serial emu kill,
#             wait until the emulator has closed, and then run $serial = scripts\start-emulator.ps1 -ColdBoot. When the
#             device does not come back ('adb reboot' or 'adb emu kill' has not returned within its limit, or the device
#             does not answer within 5 minutes), the script puts nothing back on it: it lists what is left with the
#             commands that put it back by hand, and ends with exit code 1.
# Kestrel is in the background for each check, and it listens with its own microphone. Every check except the settings
# check also checks in 'dumpsys audio' that Android did not silence Kestrel's recording. The script reads Kestrel's state
# with 'scripts\hook.ps1 voice-state', which never contains what Kestrel heard, and prints one line for each check that
# ran, also when a later check fails with an error.
# While it runs, it grants Kestrel the microphone and notification permissions, replaces the list of enabled
# accessibility services, sets the target of the accessibility shortcut, opens the notification shade for the
# notification check, and puts Kestrel's tile first in Quick Settings and opens the panel for the tile check. On an
# emulator, it also grants Kestrel the permission to read the phone state, takes away its permission to answer calls
# until the call check needs it, and turns the swipe lock screen on during the call check.
# Afterwards, also when it fails or is interrupted, it turns Kestrel off, closes the panel and puts back the tiles, the
# accessibility settings, the lock screen and the permissions, deletes the calls that it made from the call log, and
# deletes the dump of the screen. When Kestrel's accessibility service was off before the run, Kestrel's notification
# that the service is off stays afterwards, because the shell cannot remove another app's notification; swipe it away.
# If it cannot put something back, also because Ctrl+C interrupted it while it put things back, it says at the end what
# is left and how to put it back by hand. Then, after
# Ctrl+C, and when a check failed, it ends with exit code 1. With -Verbose, it names each thing that it puts back. With
# -Reboot it reboots a phone, or closes an emulator and starts it from a cold boot; a phone with a PIN, pattern or
# password asks you to unlock it after the reboot, and on a phone the script also wakes the screen and asks you to
# unlock it before it checks the recording, because Kestrel records only while the screen is on and the phone unlocked.
# It needs PowerShell 7.3 or newer, as scripts\hook.ps1 does.
# Example: scripts\listen-check.ps1 -Serial emulator-5554 -Reboot
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [switch]$Reboot
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
# No command reaches the device before this check.
Assert-TestDevice -Serial $Serial
$adb = Get-Adb
$service = 'dev.kestrel/dev.kestrel.app.KestrelAccessibilityService'
$tile = 'dev.kestrel/dev.kestrel.app.listen.KestrelTileService'
# How sysui_qs_tiles names Kestrel's tile, as add-tile writes it.
$tileSpec = 'custom(dev.kestrel/.app.listen.KestrelTileService)'
$isEmulator = $Serial -like 'emulator-*'
# Kestrel turns itself off when a speech model is missing, so a start that never comes may have this cause.
$modelsHint = '; are the speech models on the device? Run scripts\push-models.ps1'
$microphonePermission = 'android.permission.RECORD_AUDIO'
$notificationPermission = 'android.permission.POST_NOTIFICATIONS'
# Where 'uiautomator dump' writes the screen's tree on the device; Get-UiDump deletes it after each read.
$uiDumpFile = '/data/local/tmp/kestrel-ui.xml'
# The call check's permissions, and the number from which the emulator's console calls. The script deletes these calls
# from the call log afterwards, and it never prints the call log.
$phoneStatePermission = 'android.permission.READ_PHONE_STATE'
$answerCallsPermission = 'android.permission.ANSWER_PHONE_CALLS'
$testNumber = '5550175'
# The label of the in-call screen's speaker control (Step 1 of plan 2D's Task 3 looked at it), and Google's in-call
# screen in front (prototype P3).
$speakerLabel = 'Speaker'
$inCallScreenPattern = 'topResumedActivity=ActivityRecord\{[^}]* com\.google\.android\.dialer/\S*InCallActivity'

# Runs a command on the device and fails when it fails. A busy device sometimes fails a command, so it is repeated.
function Invoke-Checked([string]$Command) { Invoke-SettingsCommand -Serial $Serial -Command $Command }

# Runs a command on the device once, for the commands that toggle something, which a repeat would toggle back. Fails when
# adb or the command fails, and runs nothing when the serial no longer names the device that Assert-TestDevice accepted.
function Invoke-Once([string]$Command) {
    # adb's error lines become part of the error message. 'Continue' keeps them from stopping the script also where
    # PowerShell applies the preference to a native command's error output (PSNativeCommandUseErrorActionPreference).
    $ErrorActionPreference = 'Continue'
    $output = @(& $adb -s $Serial shell (Format-TestDeviceCommand -Serial $Serial -Command "$Command && echo kestrel-end") 2>&1)
    $last = "$($output | Select-Object -Last 1)".Trim()
    if ($last -eq 'kestrel-refused') {
        throw "Refusing to run '$Command' on ${Serial}: it is no longer the test device that Assert-TestDevice accepted."
    }
    if ($LASTEXITCODE -ne 0 -or $last -ne 'kestrel-end') { throw "Could not run '$Command' on ${Serial}: $($output -join ' ')" }
}

function Invoke-Hook { (& "$PSScriptRoot\hook.ps1" -Serial $Serial @args) -join "`n" }

# Sends a command to the emulator's console, such as 'gsm call', after checking that the serial still names
# kestrel-api37: only an emulator has a console. The console answers 'OK'.
function Invoke-Emulator([string]$Command) {
    # adb's error lines become part of the error message, as in Invoke-Once.
    $ErrorActionPreference = 'Continue'
    # Read before the if, because PSScriptAnalyzer takes a 2> in an if's condition for a comparison.
    $name = "$(& $adb -s $Serial emu avd name 2>$null)"
    if ($name -notmatch '^kestrel-api37\b') {
        throw "Refusing to send '$Command' to ${Serial}: it is no longer kestrel-api37."
    }
    $output = @(& $adb -s $Serial emu @($Command -split ' ') 2>&1)
    if ($LASTEXITCODE -ne 0 -or -not ($output -match '^OK')) { throw "The emulator did not take '$Command': $($output -join ' ')" }
}

# Whether Google's in-call screen is in front. Only the answer leaves this function, because the dump names other apps.
function Test-InCallScreen {
    ((Invoke-Checked 'dumpsys activity activities') -join "`n") -match $inCallScreenPattern
}

# Whether the in-call screen's speaker control is on: the element labelled $speakerLabel, or a parent of it, is checked.
# Only the answer leaves this function, because the dump names the call.
function Test-SpeakerOn {
    $xml = New-Object System.Xml.XmlDocument
    try { $xml.LoadXml((Get-UiDump)) } catch { return $false }
    foreach ($node in @($xml.SelectNodes('//node'))) {
        if ($node.GetAttribute('content-desc').Trim() -ne $speakerLabel -and $node.GetAttribute('text').Trim() -ne $speakerLabel) {
            continue
        }
        for ($at = $node; $at -and $at.LocalName -eq 'node'; $at = $at.ParentNode) {
            if ($at.GetAttribute('checked') -eq 'true') { return $true }
        }
    }
    $false
}

# Quotes a text for the device's shell, in which a single quote becomes '\''.
function Format-ShellText([string]$Text) { "'" + ($Text -replace "'", "'\''") + "'" }

# Waits until the condition is true, checking it every half second, and returns whether it became true in time.
function Wait-Condition([scriptblock]$Condition, [int]$TimeoutSeconds = 5) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        if (& $Condition) { return $true }
        Start-Sleep -Milliseconds 500
    } while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    [bool](& $Condition)
}

function Get-Tiles { Get-SecureSetting -Serial $Serial -Name sysui_qs_tiles }

# Writes the list of Quick Settings tiles to the setting, or deletes it for 'null'. A SystemUI before Android 15 follows it.
function Set-Tiles([string]$Value) {
    $command = if ($Value -eq 'null') { 'settings delete secure sysui_qs_tiles' } else { "settings put secure sysui_qs_tiles $(Format-ShellText $Value)" }
    Invoke-Checked $command | Out-Null
}

# Kestrel's state from the voice-state hook, or $null while Kestrel does not answer, such as while its process starts.
function Get-VoiceState {
    try { ConvertFrom-VoiceState (Invoke-Hook voice-state) } catch { $null }
}

# Waits until Kestrel is in the mode, with its voice session open, or closed for 'off'. Returns the seconds that it
# waited, or -1 after the timeout.
function Wait-Mode([string]$Mode, [int]$TimeoutSeconds) {
    $session = if ($Mode -eq 'off') { 'closed' } else { 'open' }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $state = Get-VoiceState
        if ($state -and $state.mode -eq $Mode -and $state.session -eq $session) { return [math]::Round($watch.Elapsed.TotalSeconds, 1) }
        Start-Sleep -Seconds 1
    } while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    -1
}

# Turns Kestrel off, and waits until its voice session has closed. A change to Off is also what Kestrel stores.
function Stop-Kestrel {
    Invoke-Hook voice -Send off | Out-Null
    if ((Wait-Mode off 30) -lt 0) { throw 'Kestrel did not turn off within 30 s.' }
}

# Turns Kestrel's accessibility service on, as the only enabled service, or off, and waits until Kestrel sees it.
function Set-KestrelService([bool]$On) {
    if ($On) {
        Invoke-Checked "settings put secure enabled_accessibility_services $service" | Out-Null
        Invoke-Checked 'settings put secure accessibility_enabled 1' | Out-Null
    } else {
        Invoke-Checked 'settings delete secure enabled_accessibility_services' | Out-Null
    }
    $expected = if ($On) { 'screen=on*' } else { 'screen=off*' }
    for ($i = 0; $i -lt 30; $i++) {
        if ((Invoke-Hook state) -like $expected) { return }
        Start-Sleep -Seconds 1
    }
    throw "Kestrel's accessibility service did not turn $(if ($On) { 'on' } else { 'off' }) within 30 s."
}

# The ID of Kestrel's process, or an empty string when it does not run.
function Get-KestrelProcess { ((Invoke-Checked 'pidof dev.kestrel || true') -join '').Trim() }

# Whether Kestrel holds a runtime permission, such as android.permission.RECORD_AUDIO.
function Test-KestrelPermission([string]$Permission) {
    ((Invoke-Checked 'dumpsys package dev.kestrel') -join "`n") -match "$([regex]::Escape($Permission)): granted=true"
}

# Runs a command in the device's shell with a limit of 10 s, because a frozen device can keep adb waiting without one,
# and returns its output, or an empty text when it failed or did not end in time.
function Get-ShellAnswer([string]$Command) {
    $answer = Invoke-TimedProcess -FilePath $adb -ArgumentList (@('-s', $Serial, 'shell') + @($Command -split ' ')) -TimeoutSeconds 10
    if ($answer.Completed -and $answer.ExitCode -eq 0) { $answer.Output.Trim() } else { '' }
}

# Waits until the phone has started again after a reboot, which a new boot ID shows, and until Android has unlocked the
# user's storage, without which Kestrel cannot run. On a phone with a PIN, the owner has to unlock it first. It waits for
# at most 5 minutes, and returns whether the phone came back.
function Wait-Reboot([string]$BootId) {
    $deadline = (Get-Date).AddMinutes(5)
    $lockedChecks = 0
    do {
        Start-Sleep -Seconds 3
        $id = Get-ShellAnswer 'cat /proc/sys/kernel/random/boot_id'
        $booted = $id -and $id -ne $BootId -and (Get-ShellAnswer 'getprop sys.boot_completed') -eq '1'
        $unlocked = $booted -and (Get-ShellAnswer 'am get-started-user-state 0') -match 'RUNNING_UNLOCKED'
        # Without a PIN, Android unlocks the storage a few seconds after the start.
        if ($booted -and -not $unlocked -and ++$lockedChecks -eq 2) {
            Read-Host 'Unlock the phone, then press Enter' | Out-Null
        }
    } until ($unlocked -or (Get-Date) -gt $deadline)
    $unlocked
}

# Closes the emulator kestrel-api37 and starts it again from a cold boot, in place of a reboot inside the emulator,
# which has crashed or frozen this computer's emulator. Kestrel stores each mode when it changes, so its process ends as
# in a reboot. Returns nothing when the emulator answers again with the same serial, and otherwise why the check fails.
function Restart-Emulator {
    # Kestrel's stored mode must be on the emulator's disk, and not only in Android's memory, when the emulator ends.
    Invoke-Checked 'sync' | Out-Null
    $kill = Invoke-TimedProcess -FilePath $adb -ArgumentList '-s', $Serial, 'emu', 'kill' -TimeoutSeconds 30
    if (-not $kill.Completed) { return "'adb emu kill' did not return within 30 s, so the emulator may have frozen" }
    # The emulator saves its Quick Boot snapshot before it ends.
    $deadline = (Get-Date).AddMinutes(2)
    while ((Get-EmulatorProcess 'kestrel-api37') -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 2 }
    $left = @(Get-EmulatorProcess 'kestrel-api37')
    if ($left) { return "kestrel-api37 did not close within 2 minutes of 'adb emu kill' (its qemu process has the ID $($left[0].ProcessId))" }
    try {
        $started = "$(& "$PSScriptRoot\start-emulator.ps1" -ColdBoot | Select-Object -Last 1)".Trim()
    } catch {
        return "the emulator did not start again: $($_.Exception.Message)"
    }
    if ($started -ne $Serial) { return "kestrel-api37 started again as '$started', not as $Serial" }
    # The serial now names a new emulator, so the device check runs again before any other command.
    try { Assert-TestDevice -Serial $Serial } catch { return $_.Exception.Message }
}

# The riids of Kestrel's recordings that 'dumpsys audio' lists now.
function Get-KestrelRecording { Get-RecordingId -Dump (Invoke-Checked 'dumpsys audio') -Package dev.kestrel }

# Waits up to 5 s until Kestrel's newest recording is one whose riid is not in $Before, so one that started after the
# check's action, and that Android lets record: its newest line says 'not silenced' (design spec, B5; spike S1). Returns
# nothing then, and otherwise why the check fails.
function Get-RecordingProblem([string[]]$Before) {
    $state = 'none'
    for ($i = 0; $i -lt 10; $i++) {
        if ($i -gt 0) { Start-Sleep -Milliseconds 500 }
        $state = Get-NewRecordingState -Dump (Invoke-Checked 'dumpsys audio') -Package dev.kestrel -Before $Before
        if ($state -eq 'not silenced') { return }
    }
    if ($state -eq 'silenced') { 'Android silenced the microphone' } else { "no new recording of Kestrel's showed in dumpsys audio within 5 s" }
}

# The screen's accessibility tree, as 'uiautomator dump' writes it, or an empty text when uiautomator could not read the
# screen. On a phone it holds the text of the owner's notifications, so the script only searches it, never prints it,
# and deletes the file on the device.
function Get-UiDump {
    $file = $uiDumpFile
    try {
        Invoke-Checked "uiautomator dump $file" | Out-Null
        # The dump ends without a line break, and the device's marker must start a line of its own.
        (Invoke-Checked "cat $file && echo") -join "`n"
    } catch {
        ''
    } finally {
        try { Invoke-Checked "rm -f $file" | Out-Null } catch { Write-Warning "Could not delete $file on $Serial." }
    }
}

function Invoke-NotificationCheck {
    Stop-Kestrel
    # Without the accessibility service, and with Kestrel in the background, Android refuses a start through the hook.
    Set-KestrelService $false
    # The shade and the tap need the screen on, and Kestrel records only while it is on.
    Invoke-Checked 'input keyevent KEYCODE_WAKEUP' | Out-Null
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    # Android lets an app start a foreground service for a few seconds after it was in front; prototype P2 waited 10 s.
    Start-Sleep -Seconds 10
    Invoke-Hook voice -Send toggle | Out-Null
    # Android refuses the start at once, and Kestrel turns off again. This is checked before the shade opens, so that a
    # "Start listening" that an earlier failure left cannot stand in for this one.
    Start-Sleep -Seconds 2
    $state = Get-VoiceState
    if ($state -and $state.mode -ne 'off') {
        return ('failed: Android let the hook start listening in the background without the accessibility service, ' +
            'so the check could not reach the notification')
    }
    $tapped = $null
    try {
        Invoke-Checked 'cmd statusbar expand-notifications' | Out-Null
        # The problems notification's title after a refused start (problem_title_not_started in strings.xml).
        $title = 'Kestrel could not start listening'
        $button = $null
        $notice = $null
        $read = $false
        # A dump takes a second or more, so the wait counts seconds, not dumps.
        $watch = [Diagnostics.Stopwatch]::StartNew()
        while (-not ($button -or $notice) -and $watch.Elapsed.TotalSeconds -lt 15) {
            Start-Sleep -Milliseconds 500
            $dump = Get-UiDump
            $read = $read -or [bool]$dump
            $button = Get-UiNodeCentre -Dump $dump -Text 'Start listening'
            $notice = Get-UiNodeCentre -Dump $dump -Text $title
        }
        if (-not $read) { return 'failed: uiautomator could not read the notification shade within 15 s' }
        if (-not ($button -or $notice)) {
            return 'failed: the notification shade did not show Kestrel''s problems notification within 15 s of a start that Android refused'
        }
        if (-not $button) {
            # The shade shows a notification collapsed, without its buttons, for example below other notifications. A
            # swipe down on a collapsed notification expands it; a second swipe would collapse it again.
            Invoke-Once "input swipe $($notice.X) $($notice.Y) $($notice.X) $($notice.Y + 300) 500"
            $watch.Restart()
            while (-not $button -and $watch.Elapsed.TotalSeconds -lt 5) {
                Start-Sleep -Milliseconds 500
                $button = Get-UiNodeCentre -Dump (Get-UiDump) -Text 'Start listening'
            }
            if (-not $button) {
                return 'failed: Kestrel''s problems notification showed no "Start listening", also after a swipe down on it to expand it'
            }
        }
        $before = Get-KestrelRecording
        Invoke-Once "input tap $($button.X) $($button.Y)"
        $tapped = [Diagnostics.Stopwatch]::StartNew()
        $on = Wait-Mode listening 60
        if ($on -lt 0) { return "failed: no listening within 60 s of a tap on ""Start listening""$modelsHint" }
        $problem = Get-RecordingProblem $before
        if ($problem) { return "failed: listening $on s after a tap on ""Start listening"", but $problem" }
        "ok: Android refused a start in the background, and ""Start listening"" in the notification started listening in $on s"
    } finally {
        # The later checks run with the shade closed.
        Invoke-Checked 'cmd statusbar collapse' | Out-Null
        # The tap lets Kestrel start a foreground service from the background for a while (prototype P2, Q2). The tile
        # check after this one must not use that exemption, so the check waits until 30 s have passed since the tap.
        if ($tapped) {
            $left = 30000 - $tapped.Elapsed.TotalMilliseconds
            if ($left -gt 0) { Start-Sleep -Milliseconds ([int][math]::Ceiling($left)) }
        }
    }
}

function Invoke-TileCheck {
    Stop-Kestrel
    # Without the accessibility service, only the tap's own exemption lets Kestrel start its service from the background,
    # for 15 s after the tap.
    Set-KestrelService $false
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    try {
        # First in the list, so that the tile is on the first page of Quick Settings, whose tiles SystemUI follows while the
        # panel is open; its dump lists only them. Since Android 15, SystemUI keeps the list itself and overwrites other
        # writes to the setting at once, so the tile goes in through SystemUI. A device without a list of its own gets the
        # tile at the end, after SystemUI's default tiles; if that is past the first page, the check fails.
        $others = if ($tilesBefore -in 'null', '') { @() } else { @($tilesBefore -split ',' | Where-Object { $_ -notmatch 'KestrelTileService' }) }
        $tiles = (@($tileSpec) + $others) -join ','
        if ($others.Count -eq 0) {
            Invoke-Checked "cmd statusbar add-tile $tile" | Out-Null
        } else {
            Invoke-Checked "cmd statusbar set-tiles $(Format-ShellText $tiles)" | Out-Null
        }
        # A SystemUI before Android 15 follows the setting itself.
        if (-not (Wait-Condition { (Get-Tiles) -match 'KestrelTileService' })) { Set-Tiles $tiles }
        if (-not (Wait-Condition { (Get-Tiles) -match 'KestrelTileService' })) {
            return 'failed: Quick Settings did not list Kestrel''s tile within 5 s'
        }
        Invoke-Checked 'cmd statusbar expand-settings' | Out-Null
        # SystemUI makes and binds the tile a moment after it lists it, and a tap before that is lost. It shows the tile
        # as a switch that is off (state 1, inactive) once Kestrel's tile service has told it so.
        $off = 'BooleanState\[spec=custom\([^)]*KestrelTileService\).*,secondaryLabel=Off,.*,state=1,'
        $shown = Wait-Condition -TimeoutSeconds 15 {
            ((Invoke-Checked 'dumpsys activity service com.android.systemui/.SystemUIService QSTileHost') -join "`n") -match $off
        }
        if (-not $shown) { return 'failed: SystemUI did not show Kestrel''s tile as Off within 15 s of opening Quick Settings' }
        $before = Get-KestrelRecording
        Invoke-Once "cmd statusbar click-tile $tile"
        $on = Wait-Mode listening 60
        if ($on -lt 0) { return "failed: no listening within 60 s of a tap$modelsHint" }
        $problem = Get-RecordingProblem $before
        if ($problem) { return "failed: listening $on s after a tap, but $problem" }
        Invoke-Once "cmd statusbar click-tile $tile"
        if ((Wait-Mode paused 10) -lt 0) { return "failed: listening $on s after a tap, but a second tap did not pause it" }
        "ok: listening $on s after a tap, and a second tap paused it"
    } finally {
        # The later checks run with the panel closed; the settings check looks for Kestrel's activity in front.
        Invoke-Checked 'cmd statusbar collapse' | Out-Null
    }
}

function Invoke-ShortcutCheck {
    Stop-Kestrel
    Set-KestrelService $true
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    Invoke-Checked "settings put secure accessibility_shortcut_target_service $service" | Out-Null
    # Android reads the setting a moment after it changes.
    $named = $false
    for ($i = 0; $i -lt 10 -and -not $named; $i++) {
        if ($i -gt 0) { Start-Sleep -Milliseconds 500 }
        $named = Test-ShortcutTarget -Dump (Invoke-Checked 'dumpsys accessibility') -Package dev.kestrel
    }
    if (-not $named) { return 'failed: Android did not take Kestrel as the target of the accessibility shortcut' }
    # The command's exit code is 0 even when the action did nothing, so only Kestrel's state tells.
    $before = Get-KestrelRecording
    Invoke-Once 'cmd accessibility call-system-action 13'
    $on = Wait-Mode listening 60
    if ($on -lt 0) { return "failed: no listening within 60 s of the shortcut$modelsHint" }
    $problem = Get-RecordingProblem $before
    if ($problem) { return "failed: listening $on s after the shortcut, but $problem" }
    Invoke-Once 'cmd accessibility call-system-action 13'
    if ((Wait-Mode paused 10) -lt 0) { return "failed: listening $on s after the shortcut, but a second press did not pause it" }
    "ok: listening $on s after the shortcut, and a second press paused it"
}

function Invoke-SettingsCheck {
    Stop-Kestrel
    # The accessibility service is on since the shortcut check. Without it, Android does not let Kestrel open an activity
    # from the background, and nothing tells Kestrel so.
    Invoke-Hook voice -Send toggle | Out-Null
    if ((Wait-Mode listening 60) -lt 0) { return "failed: Kestrel did not start listening before the request$modelsHint" }
    # Kestrel acts on a request only while the screen is on.
    Invoke-Checked 'input keyevent KEYCODE_WAKEUP' | Out-Null
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    Invoke-Hook voice -Send heard -Text 'Kestrel, open settings.' -Named | Out-Null
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt 10) {
        $activities = (Invoke-Checked 'dumpsys activity activities') -join "`n"
        if ($activities -match 'topResumedActivity=ActivityRecord\{[^}]* dev\.kestrel/\.app\.MainActivity ') {
            $opened = [math]::Round($watch.Elapsed.TotalSeconds, 1)
            Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
            return "ok: Kestrel's settings came to the front $opened s after 'Kestrel, open settings'"
        }
        Start-Sleep -Milliseconds 500
    }
    "failed: Kestrel's settings did not come to the front within 10 s of 'Kestrel, open settings'"
}

function Invoke-CallCheck {
    if (-not $isEmulator) { return 'skipped: only an emulator can place a call to itself' }
    Stop-Kestrel
    # The accessibility service is on since the shortcut check; Kestrel clicks the call's controls through it.
    Invoke-Hook voice -Send toggle | Out-Null
    if ((Wait-Mode listening 60) -lt 0) { return "failed: Kestrel did not start listening before the call$modelsHint" }
    Invoke-Checked 'input keyevent KEYCODE_WAKEUP' | Out-Null
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    try {
        # Without the permission to answer calls, Kestrel clicks the controls of the call's heads-up notification.
        Invoke-Emulator "gsm call $testNumber"
        if ((Wait-Mode ringing 15) -lt 0) { return 'failed: Kestrel did not show Ringing within 15 s of an incoming call' }
        # Kestrel's recording goes on from before the call, so its newest line counts. Android may silence a recording a
        # moment after the call starts to ring, so the check looks again for 3 s.
        foreach ($look in 1..6) {
            $state = Get-NewRecordingState -Dump (Invoke-Checked 'dumpsys audio') -Package dev.kestrel -Before @()
            if ($state -ne 'not silenced') { return "failed: while the call rang, Kestrel's newest recording was '$state'" }
            Start-Sleep -Milliseconds 500
        }
        Invoke-Hook voice -Send heard -Text 'Decline.' | Out-Null
        if ((Wait-Mode listening 15) -lt 0) { return 'failed: "decline" did not end the ringing call within 15 s' }
        Invoke-Emulator "gsm call $testNumber"
        if ((Wait-Mode ringing 15) -lt 0) { return 'failed: Kestrel did not show Ringing for the second call within 15 s' }
        Invoke-Hook voice -Send heard -Text 'Answer.' | Out-Null
        if ((Wait-Mode in_call 15) -lt 0) { return 'failed: "answer" did not answer the call within 15 s' }
        Invoke-Emulator "gsm cancel $testNumber"
        if ((Wait-Mode listening 15) -lt 0) { return 'failed: listening did not come back within 15 s of the end of the call' }
        # With the permission, Kestrel answers through Android's call API, which leaves the in-call screen behind
        # (prototype P3): a plain "answer" must leave it there, and "answer on speaker" must bring it to the front with its
        # speaker control on. The emulator has no earpiece, so its calls are on the speaker from the start, and Kestrel
        # must leave the control as it is.
        Invoke-Checked "pm grant dev.kestrel $answerCallsPermission" | Out-Null
        Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
        Invoke-Emulator "gsm call $testNumber"
        if ((Wait-Mode ringing 15) -lt 0) { return 'failed: Kestrel did not show Ringing for the third call within 15 s' }
        Invoke-Hook voice -Send heard -Text 'Answer.' | Out-Null
        if ((Wait-Mode in_call 15) -lt 0) { return 'failed: "answer" did not answer the third call within 15 s' }
        $plainShown = Wait-Condition -TimeoutSeconds 5 { Test-InCallScreen }
        Invoke-Emulator "gsm cancel $testNumber"
        if ($plainShown) {
            return 'failed: the in-call screen came to the front after a plain "answer", so the check cannot tell whether "answer on speaker" brought it there'
        }
        if ((Wait-Mode listening 15) -lt 0) { return 'failed: listening did not come back within 15 s of the end of the third call' }
        Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
        Invoke-Emulator "gsm call $testNumber"
        if ((Wait-Mode ringing 15) -lt 0) { return 'failed: Kestrel did not show Ringing for the fourth call within 15 s' }
        Invoke-Hook voice -Send heard -Text 'Answer on speaker.' | Out-Null
        if ((Wait-Mode in_call 15) -lt 0) { return 'failed: "answer on speaker" did not answer the call within 15 s' }
        # Kestrel waits up to 5 s for the active call, brings the in-call screen to the front, and then looks for the
        # speaker control for up to 3 s.
        $shown = Wait-Condition -TimeoutSeconds 15 { Test-InCallScreen }
        if ($shown) { Start-Sleep -Seconds 4 }
        $speakerOn = $shown -and (Test-SpeakerOn)
        Invoke-Emulator "gsm cancel $testNumber"
        if (-not $shown) { return 'failed: "answer on speaker" answered the call, but the in-call screen did not come to the front within 15 s' }
        if (-not $speakerOn) { return 'failed: "answer on speaker" brought the in-call screen to the front, but its speaker control was not on' }
        if ((Wait-Mode listening 15) -lt 0) { return 'failed: listening did not come back within 15 s of the end of the fourth call' }
        # Also on the lock screen, Kestrel uses Android's call API (spike S1's open scenario).
        Invoke-Checked 'cmd lock_settings set-disabled false' | Out-Null
        Invoke-Checked 'input keyevent KEYCODE_SLEEP' | Out-Null
        if (-not (Wait-Condition -TimeoutSeconds 10 { (Get-VoiceState).screenOn -eq 'false' })) {
            return 'failed: Kestrel did not see the screen turn off within 10 s'
        }
        $before = Get-KestrelRecording
        Invoke-Emulator "gsm call $testNumber"
        if ((Wait-Mode ringing 15) -lt 0) { return 'failed: Kestrel did not show Ringing on the lock screen within 15 s' }
        $problem = Get-RecordingProblem $before
        if ($problem) { return "failed: Ringing on the lock screen, but $problem" }
        Invoke-Hook voice -Send heard -Text 'Decline.' | Out-Null
        if ((Wait-Mode listening 15) -lt 0) { return 'failed: "decline" did not end the call on the lock screen within 15 s' }
        ('ok: "decline" and "answer" worked through the call''s notification, a plain "answer" through the call API left ' +
            'the in-call screen behind, "answer on speaker" brought it to the front with the speaker on, and "decline" ' +
            'worked with the permission to answer calls on the lock screen, where Kestrel recorded while the call rang')
    } finally {
        # A call that a failed step left goes. The later checks need the screen on and unlocked; the reboot check also
        # needs the lock screen off.
        try { Invoke-Emulator "gsm cancel $testNumber" } catch { Write-Verbose 'No call was left to end.' }
        if ($lockScreenOff) { Invoke-Checked 'cmd lock_settings set-disabled true' | Out-Null }
        Invoke-Checked 'input keyevent KEYCODE_WAKEUP' | Out-Null
        Invoke-Checked 'wm dismiss-keyguard' | Out-Null
        Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    }
}

function Invoke-RestartCheck {
    Stop-Kestrel
    # The accessibility service is on since the shortcut check, so Android lets the hook's start run in the background.
    Invoke-Hook voice -Send toggle | Out-Null
    if ((Wait-Mode listening 60) -lt 0) { return "failed: Kestrel did not start listening before its process ended$modelsHint" }
    # Kestrel stores Listening once its voice session is open, from a coroutine a moment later; a crash before that write
    # would leave Off stored.
    Start-Sleep -Seconds 2
    $recording = Get-KestrelRecording
    $before = Get-KestrelProcess
    $watch = [Diagnostics.Stopwatch]::StartNew()
    Invoke-Once 'am crash dev.kestrel'
    $after = ''
    while ($watch.Elapsed.TotalSeconds -lt 60) {
        Start-Sleep -Seconds 1
        $after = Get-KestrelProcess
        if ($after -and $after -ne $before) { break }
    }
    if (-not $after -or $after -eq $before) {
        return ("failed: Kestrel's process did not start again within 60 s of its end; Android does not restart an app that " +
            'crashed twice within 2 minutes, so wait 2 minutes before you run the script again')
    }
    if ((Wait-Mode listening 120) -lt 0) { return 'failed: Kestrel did not listen again within 120 s of its new start' }
    $again = [math]::Round($watch.Elapsed.TotalSeconds, 1)
    $problem = Get-RecordingProblem $recording
    if ($problem) { return "failed: listening again $again s after Android ended Kestrel's process, but $problem" }
    "ok: listening again $again s after Android ended Kestrel's process"
}

function Invoke-RebootCheck {
    Stop-Kestrel
    Invoke-Hook voice -Send toggle | Out-Null
    if ((Wait-Mode listening 60) -lt 0) { return "failed: Kestrel did not start listening before the reboot$modelsHint" }
    # As in the restart check: Kestrel stores Listening a moment after its voice session opened.
    Start-Sleep -Seconds 2
    $watch = [Diagnostics.Stopwatch]::StartNew()
    if ($isEmulator) {
        $problem = Restart-Emulator
        if ($problem) {
            $script:deviceLost = $true
            return "failed: $problem"
        }
    } else {
        $bootId = ((Invoke-Checked 'cat /proc/sys/kernel/random/boot_id') -join '').Trim()
        # A phone that does not come back must not keep the script waiting, so 'adb reboot' gets 60 s.
        $call = Invoke-TimedProcess -FilePath $adb -ArgumentList '-s', $Serial, 'reboot' -TimeoutSeconds 60
        if (-not $call.Completed) {
            $script:deviceLost = $true
            return "failed: 'adb reboot' did not return within 60 s"
        }
        if (-not (Wait-Reboot $bootId)) {
            $script:deviceLost = $true
            return "failed: $Serial did not start again within 5 minutes of the reboot"
        }
        try {
            Assert-TestDevice -Serial $Serial
        } catch {
            $script:deviceLost = $true
            return "failed: $($_.Exception.Message)"
        }
    }
    if ((Wait-Mode listening 240) -lt 0) { return 'failed: Kestrel did not listen again within 240 s of the device''s start' }
    $again = [math]::Round($watch.Elapsed.TotalSeconds, 1)
    # On a phone, the screen is off or locked after the reboot, and Kestrel records only while it is on and unlocked.
    if (-not $isEmulator) {
        Invoke-Checked 'input keyevent KEYCODE_WAKEUP' | Out-Null
        Read-Host 'Unlock the phone, then press Enter' | Out-Null
    }
    # 'dumpsys audio' starts its history afresh at each boot, so every recording of Kestrel's that it lists is new.
    $problem = Get-RecordingProblem @()
    if ($problem) { return "failed: listening again $again s after the reboot, but $problem" }
    "ok: listening again $again s after the reboot"
}

# What the script has still to put back. Each restore enters this list before the change that it undoes is made, and it
# leaves the list when it has worked. At the end, the script names the restores that are left and how to do them by hand.
$pending = [System.Collections.Generic.List[object]]::new()
$stopReason = 'the script was stopped'

function Register-Restore([string]$What, [string]$ByHand, [scriptblock]$Action) {
    $restore = [pscustomobject]@{ What = $What; ByHand = $ByHand; Action = $Action; Reason = $stopReason }
    $pending.Add($restore)
    $restore
}

# Runs the restores in order. A stop, such as Ctrl+C, that comes while a finally block runs ends that block at once, and
# PowerShell then runs the finally blocks around it in full and ignores further stops. So each restore runs in the
# finally block of the one before it, and a stop ends only the restore that is running.
function Invoke-Restore([object[]]$Steps, [int]$Index = 0) {
    if ($Index -ge $Steps.Count) { return }
    $restore = $Steps[$Index]
    try {
        Write-Verbose "Putting back $($restore.What)"
        & $restore.Action | Out-Null
        $pending.Remove($restore) | Out-Null
    } catch {
        $restore.Reason = $_.Exception.Message
    } finally {
        Invoke-Restore $Steps ($Index + 1)
    }
}

# Returns a PowerShell command that runs the given command on the device, for the warnings.
function Format-DeviceCommand([string]$Command) {
    "Run: & '$($adb -replace "'", "''")' -s $Serial shell '$($Command -replace "'", "''")'"
}

# Fails before anything changes when Kestrel's debug build is not installed.
Invoke-Hook voice-state | Out-Null

$results = [ordered]@{}
$savedAccessibility = Get-AccessibilitySettings -Serial $Serial
$tilesBefore = Get-SecureSetting -Serial $Serial -Name sysui_qs_tiles
$tilesByHand = if ($tilesBefore -in 'null', '') {
    Format-DeviceCommand "cmd statusbar remove-tile $tile"
} else {
    Format-DeviceCommand "cmd statusbar set-tiles $(Format-ShellText $tilesBefore)"
}
$microphoneBefore = Test-KestrelPermission $microphonePermission
$notificationsBefore = Test-KestrelPermission $notificationPermission
# The call check runs only on an emulator; on a phone none of these is read or changed.
$phoneStateBefore = $isEmulator -and (Test-KestrelPermission $phoneStatePermission)
$answerCallsBefore = $isEmulator -and (Test-KestrelPermission $answerCallsPermission)
$lockScreenOff = $isEmulator -and ((Invoke-Checked 'cmd lock_settings get-disabled') -join '').Trim() -eq 'true'
# The check's calls are newer than the newest row of the call log now. The query reads only the rows' IDs.
$callLogLargest = if ($isEmulator) { Get-LargestRowId -Rows (Invoke-Checked 'content query --uri content://call_log/calls --projection _id') } else { 0 }
$callLogDelete = "content delete --uri content://call_log/calls --where '_id>$callLogLargest AND number=$testNumber'"
# The commands that put back the accessibility settings by hand. As Restore-AccessibilitySettings does, they put back the
# list of services first, and they delete the settings that the script changes if these were not there before.
$accessibilityByHand = @(foreach ($setting in @('enabled_accessibility_services', 'accessibility_enabled', 'accessibility_shortcut_target_service') + @($savedAccessibility.Keys) | Select-Object -Unique) {
        $value = if ($savedAccessibility.Contains($setting)) { $savedAccessibility[$setting] } else { 'null' }
        if ($value -ne 'null') {
            "settings put secure $setting '$($value -replace "'", "'\''")'"
        } else {
            "settings delete secure $setting"
        }
    }) -join '; '
$completed = $false
$failed = $false
# Set by the reboot check when the device did not answer after the reboot. The script then runs no command on it.
$deviceLost = $false
$restoresAtEnd = @(
    # First, so that Kestrel stores Off and does not come back at the next start of its process.
    Register-Restore "Kestrel's mode Off" "Run: scripts\hook.ps1 -Serial $Serial voice -Send off" {
        Stop-Kestrel
    }
    Register-Restore 'the Quick Settings tiles' $tilesByHand {
        # The restores below write to the device directly, so they first check that the serial still names a test device.
        Assert-TestDevice -Serial $Serial
        Invoke-Checked 'cmd statusbar collapse' | Out-Null
        # SystemUI removes only a tile that it has made. A tile that was in the list before stays, and set-tiles below
        # puts it back in its place.
        if ($tilesBefore -notmatch 'KestrelTileService') { Invoke-Checked "cmd statusbar remove-tile $tile" | Out-Null }
        # SystemUI writes the list a moment later. A list that does not come back as it was is written back: through
        # SystemUI, which since Android 15 keeps the list itself and overwrites other writes to the setting, or else to the
        # setting, which an older SystemUI follows. set-tiles does nothing when the list equals the tiles that SystemUI has
        # made, which leave out tiles that are not installed.
        if (-not (Wait-Condition { (Get-Tiles) -ceq $tilesBefore })) {
            if ($tilesBefore -notin 'null', '') { Invoke-Checked "cmd statusbar set-tiles $(Format-ShellText $tilesBefore)" | Out-Null }
            if (-not (Wait-Condition { (Get-Tiles) -ceq $tilesBefore })) { Set-Tiles $tilesBefore }
            # SystemUI overwrites a write that it does not follow within a moment.
            Start-Sleep -Seconds 1
            if ((Get-Tiles) -cne $tilesBefore) { throw 'the list of tiles differs from before' }
        }
    }
    Register-Restore 'the accessibility settings' (Format-DeviceCommand $accessibilityByHand) {
        Assert-TestDevice -Serial $Serial
        if (-not (Restore-AccessibilitySettings -Serial $Serial -Saved $savedAccessibility)) { throw 'see the warning above' }
    }
    # Get-UiDump deletes its file after each read; this also covers a Ctrl+C that came in between. On a phone the file
    # holds the text of the owner's notifications.
    Register-Restore 'the dump of the screen' (Format-DeviceCommand "rm -f $uiDumpFile") {
        Assert-TestDevice -Serial $Serial
        Invoke-Checked "rm -f $uiDumpFile" | Out-Null
    }
    # The call check's lock screen, calls and permission to answer calls, on an emulator only. The check turns the lock
    # screen off again itself; this restore also covers a Ctrl+C.
    if ($lockScreenOff) {
        Register-Restore 'the disabled lock screen' (Format-DeviceCommand 'cmd lock_settings set-disabled true') {
            Assert-TestDevice -Serial $Serial
            Invoke-Checked 'cmd lock_settings set-disabled true' | Out-Null
        }
    }
    if ($isEmulator) {
        Register-Restore 'the call log without the test calls' (Format-DeviceCommand $callLogDelete) {
            Assert-TestDevice -Serial $Serial
            Invoke-Checked $callLogDelete | Out-Null
        }
    }
    if ($answerCallsBefore) {
        Register-Restore "Kestrel's permission to answer calls" (Format-DeviceCommand "pm grant dev.kestrel $answerCallsPermission") {
            Assert-TestDevice -Serial $Serial
            Invoke-Checked "pm grant dev.kestrel $answerCallsPermission" | Out-Null
        }
    }
    # Revoking a permission ends Kestrel's process, so these come last.
    if (-not $notificationsBefore) {
        Register-Restore "Kestrel's notification permission" (Format-DeviceCommand "pm revoke dev.kestrel $notificationPermission") {
            Assert-TestDevice -Serial $Serial
            if (Test-KestrelPermission $notificationPermission) { Invoke-Checked "pm revoke dev.kestrel $notificationPermission" | Out-Null }
            if (Test-KestrelPermission $notificationPermission) { throw 'it is still granted' }
        }
    }
    if (-not $microphoneBefore) {
        Register-Restore "Kestrel's microphone permission" (Format-DeviceCommand "pm revoke dev.kestrel $microphonePermission") {
            Assert-TestDevice -Serial $Serial
            if (Test-KestrelPermission $microphonePermission) { Invoke-Checked "pm revoke dev.kestrel $microphonePermission" | Out-Null }
            if (Test-KestrelPermission $microphonePermission) { throw 'it is still granted' }
        }
    }
    if ($isEmulator -and -not $answerCallsBefore) {
        Register-Restore "Kestrel's permission to answer calls" (Format-DeviceCommand "pm revoke dev.kestrel $answerCallsPermission") {
            Assert-TestDevice -Serial $Serial
            if (Test-KestrelPermission $answerCallsPermission) { Invoke-Checked "pm revoke dev.kestrel $answerCallsPermission" | Out-Null }
            if (Test-KestrelPermission $answerCallsPermission) { throw 'it is still granted' }
        }
    }
    if ($isEmulator -and -not $phoneStateBefore) {
        Register-Restore "Kestrel's permission to read the phone state" (Format-DeviceCommand "pm revoke dev.kestrel $phoneStatePermission") {
            Assert-TestDevice -Serial $Serial
            if (Test-KestrelPermission $phoneStatePermission) { Invoke-Checked "pm revoke dev.kestrel $phoneStatePermission" | Out-Null }
            if (Test-KestrelPermission $phoneStatePermission) { throw 'it is still granted' }
        }
    }
)
try {
    Invoke-Checked "pm grant dev.kestrel $microphonePermission" | Out-Null
    # The notification check taps Kestrel's problems notification.
    Invoke-Checked "pm grant dev.kestrel $notificationPermission" | Out-Null
    if ($isEmulator) {
        # The call check: Kestrel watches the call state only with this permission.
        Invoke-Checked "pm grant dev.kestrel $phoneStatePermission" | Out-Null
        # The call check first clicks the phone app's controls, which Kestrel does only without this permission.
        # Revoking it ends Kestrel's process, which the hook below starts again.
        if ($answerCallsBefore) { Invoke-Checked "pm revoke dev.kestrel $answerCallsPermission" | Out-Null }
    }
    # The hook's off first stops any restore in Kestrel's process and then stores Off, also while Kestrel is off, so a
    # mode that an earlier run stored cannot turn Kestrel on during a check, even if the accessibility service is on.
    Stop-Kestrel
    # Kestrel records with its own microphone, also if a hook gave it the clip microphone earlier in its process.
    Invoke-Hook voice-clip -Clear | Out-Null
    # First: a failed notification check leaves the problems notification, which the later checks' starts remove.
    $results['notification'] = Invoke-NotificationCheck
    $results['tile'] = Invoke-TileCheck
    $results['shortcut'] = Invoke-ShortcutCheck
    $results['settings'] = Invoke-SettingsCheck
    $results['call'] = Invoke-CallCheck
    $results['restart'] = Invoke-RestartCheck
    if ($Reboot) { $results['reboot'] = Invoke-RebootCheck }
    $completed = $true
} catch {
    $failed = $true
    throw
} finally {
    try {
        if ($deviceLost) {
            # A command to a device that does not answer can wait without a limit, so every restore is left for later.
            foreach ($restore in $pending) { $restore.Reason = "$Serial did not answer after the reboot" }
        } else {
            Invoke-Restore $restoresAtEnd
        }
    } finally {
        # Also when a check failed with an error, so that the lines of the checks that ran are not lost.
        $results.GetEnumerator() | ForEach-Object { Write-Host ('{0,-12} {1}' -f $_.Key, $_.Value) }
        if ($deviceLost) {
            Write-Warning "$Serial did not answer after the reboot, so the script put nothing back on it. Once it answers, put back what follows."
        }
        foreach ($restore in $pending) {
            Write-Warning "Could not put back $($restore.What) on $Serial ($($restore.Reason)). $($restore.ByHand)"
        }
        # The commands name the serial, which another emulator can have after this one ended.
        if ($pending.Count -gt 0 -and $isEmulator) {
            Write-Warning ("Before you run these commands, check with 'adb -s $Serial emu avd name' that $Serial is still " +
                'kestrel-api37. If it is not, run scripts\start-emulator.ps1, which starts kestrel-api37 only if it is not running and ' +
                "prints its serial, and use that serial instead of $Serial.")
        }
        # After a stop, such as Ctrl+C, PowerShell would end the script with exit code 0. A failed run ends with its error.
        if (-not ($completed -or $failed) -or $pending.Reason -contains $stopReason) {
            # pwsh -File ends a stopped script that declares parameters with exit code 0, even after 'exit 1', unless the
            # script gives the host its exit code. Run from a prompt, that would close the window, so the script does it
            # only when nothing called it.
            if (@(Get-PSCallStack).Count -eq 1) { $Host.SetShouldExit(1) }
            exit 1
        }
    }
}
if ($pending.Count -gt 0) {
    Write-Warning 'The device is not as it was before. The warnings above say how to put it back.'
    exit 1
}
if (@($results.Values | Where-Object { $_ -like 'failed*' }).Count -gt 0) { exit 1 }
