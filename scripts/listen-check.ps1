# Checks the ways in which Kestrel's listening starts without a word from the owner (design spec, sections 8.5, 8.6 and
# 14.4), on a test device with Kestrel's debug build (scripts\install.ps1) and its speech models (scripts\push-models.ps1):
#   tile      a tap on Kestrel's Quick Settings tile starts listening while Kestrel's accessibility service is off, and a
#             second tap pauses it
#   shortcut  the accessibility shortcut ('cmd accessibility call-system-action 13') starts listening, and a second press
#             pauses it
#   settings  with the accessibility service on, 'Kestrel, open settings' (a transcript that the hook sends) brings
#             Kestrel's settings app to the front
#   restart   after Android ended Kestrel's process ('am crash'), listening comes back once the accessibility service has
#             connected again. Android does not restart an app that crashed twice within 2 minutes, so wait 2 minutes
#             before you run the script again.
#   reboot    with -Reboot: listening comes back after a reboot
# Kestrel is in the background for each check, and it listens with its own microphone. The tile, shortcut and restart
# checks also check in 'dumpsys audio' that Android did not silence Kestrel's recording. The script reads Kestrel's state
# with 'scripts\hook.ps1 voice-state', which never contains what Kestrel heard, and prints one line per check, also when
# a check fails with an error.
# While it runs, it grants Kestrel the microphone permission, replaces the list of enabled accessibility services, sets
# the target of the accessibility shortcut, and puts Kestrel's tile first in Quick Settings and opens the panel for the
# tile check. Afterwards, also when it fails or is interrupted, it turns Kestrel off, closes the panel and puts back the
# tiles, the accessibility settings and the permission. If it cannot put something back, also because Ctrl+C interrupted
# it while it put things back, it says at the end what is left and how to put it back by hand. Then, after Ctrl+C, and
# when a check failed, it ends with exit code 1. With -Verbose, it names each thing that it puts back. With -Reboot it
# reboots the device; a phone with a PIN, pattern or password asks you to unlock it after the reboot. It needs
# PowerShell 7.3 or newer, as scripts\hook.ps1 does.
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

# Runs a command on the device and fails when it fails. A busy device sometimes fails a command, so it is repeated.
function Invoke-Checked([string]$Command) { Invoke-SettingsCommand -Serial $Serial -Command $Command }

# Runs a command on the device once, for the commands that toggle something, which a repeat would toggle back. Fails when
# adb or the command fails.
function Invoke-Once([string]$Command) {
    # adb's error lines become part of the error message. 'Continue' keeps them from stopping the script also where
    # PowerShell applies the preference to a native command's error output (PSNativeCommandUseErrorActionPreference).
    $ErrorActionPreference = 'Continue'
    $output = @(& $adb -s $Serial shell $Command 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Could not run '$Command' on ${Serial}: $($output -join ' ')" }
}

function Invoke-Hook { (& "$PSScriptRoot\hook.ps1" -Serial $Serial @args) -join "`n" }

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

function Test-MicrophonePermission {
    ((Invoke-Checked 'dumpsys package dev.kestrel') -join "`n") -match 'android\.permission\.RECORD_AUDIO: granted=true'
}

# Waits until the device has started again after a reboot, which a new boot ID shows, and until Android has unlocked the
# user's storage, without which Kestrel cannot run. On a phone with a PIN, the owner has to unlock it first. It waits for
# at most 5 minutes.
function Wait-Reboot([string]$BootId) {
    # adb complains while the device is offline, and that must not stop the wait.
    $ErrorActionPreference = 'Continue'
    $deadline = (Get-Date).AddMinutes(5)
    $lockedChecks = 0
    do {
        Start-Sleep -Seconds 3
        $id = "$(& $adb -s $Serial shell cat /proc/sys/kernel/random/boot_id 2>$null)".Trim()
        $booted = $id -and $id -ne $BootId -and "$(& $adb -s $Serial shell getprop sys.boot_completed 2>$null)".Trim() -eq '1'
        $unlocked = $booted -and "$(& $adb -s $Serial shell am get-started-user-state 0 2>$null)" -match 'RUNNING_UNLOCKED'
        # Without a PIN, Android unlocks the storage a few seconds after the start.
        if ($booted -and -not $unlocked -and ++$lockedChecks -eq 2 -and -not $isEmulator) {
            Read-Host 'Unlock the phone, then press Enter' | Out-Null
        }
    } until ($unlocked -or (Get-Date) -gt $deadline)
    if (-not $unlocked) { throw "$Serial did not start again within 5 minutes of the reboot." }
}

# Kestrel's lines 'rec start ...' and 'rec update ...' in 'dumpsys audio', with the riid of the recording. Each recording
# has its own riid. On kestrel-api37, a recording sometimes begins with a 'rec update' line and has no 'rec start' line.
$recordingLine = '\brec (?:start|update) riid:(?<riid>\d+) .* pack:dev\.kestrel(?:\s|$)'

# The riids of Kestrel's recordings that 'dumpsys audio' lists now.
function Get-RecordingId {
    @(@(Invoke-Checked 'dumpsys audio') | ForEach-Object { if ($_ -match $recordingLine) { $Matches['riid'] } } | Select-Object -Unique)
}

# Waits up to 5 s until Kestrel's newest recording is one whose riid is not in $Before, so one that started after the
# check's action, and returns whether Android let it record: its newest line says 'not silenced' (design spec, B5;
# spike S1).
function Test-Recording([string[]]$Before) {
    for ($i = 0; $i -lt 10; $i++) {
        if ($i -gt 0) { Start-Sleep -Milliseconds 500 }
        $dump = @(Invoke-Checked 'dumpsys audio')
        $newest = ($dump -match $recordingLine) | Select-Object -Last 1
        if (-not $newest -or -not ($newest -match $recordingLine) -or $Matches['riid'] -in $Before) { continue }
        if ((Test-RecordingSilenced -Dump $dump -Package dev.kestrel) -eq $false) { return $true }
    }
    $false
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
        # tile at the end, where it is first too.
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
        $before = Get-RecordingId
        Invoke-Once "cmd statusbar click-tile $tile"
        $on = Wait-Mode listening 60
        if ($on -lt 0) { return "failed: no listening within 60 s of a tap$modelsHint" }
        if (-not (Test-Recording $before)) { return "failed: listening $on s after a tap, but Android silenced the microphone" }
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
    $before = Get-RecordingId
    Invoke-Once 'cmd accessibility call-system-action 13'
    $on = Wait-Mode listening 60
    if ($on -lt 0) { return "failed: no listening within 60 s of the shortcut$modelsHint" }
    if (-not (Test-Recording $before)) {
        return "failed: listening $on s after the shortcut, but Android silenced the microphone"
    }
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

function Invoke-RestartCheck {
    Stop-Kestrel
    # The accessibility service is on since the shortcut check, so Android lets the hook's start run in the background.
    Invoke-Hook voice -Send toggle | Out-Null
    if ((Wait-Mode listening 60) -lt 0) { return "failed: Kestrel did not start listening before its process ended$modelsHint" }
    # Kestrel stores Listening once its voice session is open, from a coroutine a moment later; a crash before that write
    # would leave Off stored.
    Start-Sleep -Seconds 2
    $recording = Get-RecordingId
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
    if (-not (Test-Recording $recording)) {
        return "failed: listening again $again s after Android ended Kestrel's process, but Android silenced the microphone"
    }
    "ok: listening again $again s after Android ended Kestrel's process"
}

function Invoke-RebootCheck {
    Stop-Kestrel
    Invoke-Hook voice -Send toggle | Out-Null
    if ((Wait-Mode listening 60) -lt 0) { return "failed: Kestrel did not start listening before the reboot$modelsHint" }
    # As in the restart check: Kestrel stores Listening a moment after its voice session opened.
    Start-Sleep -Seconds 2
    $bootId = ((Invoke-Checked 'cat /proc/sys/kernel/random/boot_id') -join '').Trim()
    $watch = [Diagnostics.Stopwatch]::StartNew()
    & $adb -s $Serial reboot | Out-Null
    Wait-Reboot $bootId
    if ((Wait-Mode listening 240) -lt 0) { return 'failed: Kestrel did not listen again within 240 s of the device''s start' }
    "ok: listening again $([math]::Round($watch.Elapsed.TotalSeconds, 1)) s after the reboot"
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
$microphoneBefore = Test-MicrophonePermission
# The commands that put back the accessibility settings by hand. As Restore-AccessibilitySettings does, they put back the
# list of services first, and they delete the list and the switch if these were not there before.
$accessibilityByHand = @(foreach ($setting in @('enabled_accessibility_services', 'accessibility_enabled') + @($savedAccessibility.Keys) | Select-Object -Unique) {
        $value = if ($savedAccessibility.Contains($setting)) { $savedAccessibility[$setting] } else { 'null' }
        if ($value -ne 'null') {
            "settings put secure $setting '$($value -replace "'", "'\''")'"
        } elseif ($setting -in 'enabled_accessibility_services', 'accessibility_enabled') {
            "settings delete secure $setting"
        }
    }) -join '; '
$completed = $false
$failed = $false
$restoresAtEnd = @(
    # First, so that Kestrel stores Off and does not come back at the next start of its process.
    Register-Restore "Kestrel's mode Off" "Run: scripts\hook.ps1 -Serial $Serial voice -Send off" {
        Stop-Kestrel
    }
    Register-Restore 'the Quick Settings tiles' $tilesByHand {
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
        if (-not (Restore-AccessibilitySettings -Serial $Serial -Saved $savedAccessibility)) { throw 'see the warning above' }
    }
    # Revoking a permission ends Kestrel's process, so this comes last.
    if (-not $microphoneBefore) {
        Register-Restore "Kestrel's microphone permission" (Format-DeviceCommand 'pm revoke dev.kestrel android.permission.RECORD_AUDIO') {
            if (Test-MicrophonePermission) { Invoke-Checked 'pm revoke dev.kestrel android.permission.RECORD_AUDIO' | Out-Null }
            if (Test-MicrophonePermission) { throw 'it is still granted' }
        }
    }
)
try {
    Invoke-Checked 'pm grant dev.kestrel android.permission.RECORD_AUDIO' | Out-Null
    # The hook's off first stops any restore in Kestrel's process and then stores Off, also while Kestrel is off, so a
    # mode that an earlier run stored cannot turn Kestrel on during a check, even if the accessibility service is on.
    Stop-Kestrel
    # Kestrel records with its own microphone, also if a hook gave it the clip microphone earlier in its process.
    Invoke-Hook voice-clip -Clear | Out-Null
    $results['tile'] = Invoke-TileCheck
    $results['shortcut'] = Invoke-ShortcutCheck
    $results['settings'] = Invoke-SettingsCheck
    $results['restart'] = Invoke-RestartCheck
    if ($Reboot) { $results['reboot'] = Invoke-RebootCheck }
    $completed = $true
} catch {
    $failed = $true
    throw
} finally {
    try {
        Invoke-Restore $restoresAtEnd
    } finally {
        # Also when a check failed with an error, so that the lines of the checks that ran are not lost.
        $results.GetEnumerator() | ForEach-Object { Write-Host ('{0,-9} {1}' -f $_.Key, $_.Value) }
        foreach ($restore in $pending) {
            Write-Warning "Could not put back $($restore.What) on $Serial ($($restore.Reason)). $($restore.ByHand)"
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
