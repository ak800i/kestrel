# The session on the Galaxy A71 with the owner (plan 2D, design D8; design spec, sections 5.3, 16.4 and 18.2, and spikes
# S2, S5 and S8). It takes the owner through every check of sub-project 2 that needs the A71 or the owner, in order. It
# asks before it reads the device's model and before each step, and a step that needs the owner's hands says what to do
# and waits. The steps' estimates add up to 5 hours 4 minutes, and the session takes about 5 to 5.5 hours: about 2 hours
# need the owner's hands and voice, and about 3 hours run without the owner. The owner can leave during the
# steps install and voice-tests (40 minutes or more), corpus (about 45 minutes) and room (an hour, which the phone spends
# in a room with ordinary sound). The steps:
#   install       installs Kestrel's debug build, copies the speech models with the Kokoro voice, and grants Kestrel the
#                 microphone and notification permissions, which stay granted
#   voice-tests   the voice device tests that may run on a phone (scripts\voice-test.ps1), among them spike S8
#                 (OnnxRuntimeTogetherTest), the voices' speed (SynthesizerTest) and the app names (AppNamesTest); the
#                 tests that did not pass are recorded by name
#   listen-check  scripts\listen-check.ps1 -Reboot: the start paths, "Kestrel, open settings", the restore after a crash and
#                 after a reboot; the owner unlocks the phone after the reboot
#   failures      Kestrel's logged failures since the reboot, by their fixed descriptions
#   battery-rules what Android 13 and One UI do to Kestrel in the background after its accessibility service is turned
#                 off, without and with the battery restriction, and the checklist's text for Samsung
#   reboots       three restarts of the phone while Kestrel listens: the restore at boot, the exit record and the models
#   close-start   a start during a close: the tile right after "Kestrel, turn off"
#   lock-tile     the tile on the lock screen
#   unlock        the unlock paths: PIN, fingerprint from the screen off, face and Smart Lock
#   activation    the accessibility shortcut and button, the tile and the notification's actions, with Kestrel in the
#                 background, and whether Kestrel can answer "Yes?" after each start
#   assistant     Kestrel as the assistant, with its accessibility service off: what the role alone does (a start in the
#                 background, "Kestrel, open settings" and the restore through the assistant service after Android ended
#                 Kestrel's process), the assistant gesture and the Galaxy Buds gesture (spike S2). Before its crash of
#                 Kestrel it waits, and says so, until 2 minutes have passed since the session's last crash, whose time
#                 it keeps in corpus\owner\session-state.txt, also for a later run with -Step
#   earbuds       the earbud microphone with the owner's earbuds, and replies in the earbuds
#   recordings    the owner's recordings of the voice corpus: the lines of corpus\commands.txt marked for the owner; a
#                 line that an earlier session recorded is kept unless the owner records all again
#   corpus        scripts\voice-eval.ps1 on the A71 with the owner's recordings and the generated clips (spike S5)
#   voice         the owner's voice with Kestrel: "Yes?", the waits, a dictated task, an answer of about 30 s,
#                 interruptions, a notification sound during a reply, misses of short commands, and the echo filter with
#                 the real player and spotter
#   memory        Kestrel's memory and the times of its starts and closes (spike S5), with the owner's voice and with
#                 Kokoro, "Try it" while Kestrel listens with Kokoro, and a cold start through the tile
#   room          an hour of ordinary room audio, in which the script counts the keyword spotter's hits and Kestrel's
#                 reactions, with the 30-minute battery test of research r10 in its first half (spike S5)
# The results go into corpus\owner\session-<time>.txt and the recordings into corpus\owner\clips; git ignores both. Neither
# they nor the console ever hold what Kestrel heard: the hooks answer only counts, times and states. Once the first step
# runs, the phone's screen stays on while the phone is plugged in. The script puts back what it changes on the phone,
# also when a step fails or Ctrl+C stops it: Kestrel's mode Off, the hooks' slow task, the setting "Stay awake", the
# assistant role and its settings, Kestrel's battery restriction (as allow or ignore, never as default, which Android
# counts as restricted), Kestrel's voice, the accessibility settings, the screen's timeout, the battery's state and the
# clips in Kestrel's files folder. It cannot put back the battery statistics, which the battery test resets, and it asks
# the owner to put back the settings in Kestrel that the owner changed and to swipe away the notifications that stay. It
# refuses every device but the A71 and kestrel-api37. -DryRun runs the session on kestrel-api37 without the owner: it
# answers every question itself, sends what the owner would say in the step voice through the voice hook, posts the
# notification of the step voice, which stays on the emulator, skips what needs the owner's hands otherwise, runs
# listen-check.ps1 without -Reboot, skips voice-test.ps1, records two silent clips into a folder of its own that it
# deletes, and shortens the room's hour to two minutes. -Step runs only the named steps, also as one text separated by
# commas. It needs PowerShell 7.3 or newer, as scripts\hook.ps1 does. After Ctrl+C, it ends with exit code 1.
# Example: scripts\a71-session.ps1 -Serial R58N12345AB
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [switch]$DryRun,
    [string[]]$Step
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
. "$PSScriptRoot\lib\Corpus.ps1"
. "$PSScriptRoot\lib\Session.ps1"
Use-KestrelToolchain

$stepNames = @('install', 'voice-tests', 'listen-check', 'failures', 'battery-rules', 'reboots', 'close-start', 'lock-tile', 'unlock',
    'activation', 'assistant', 'earbuds', 'recordings', 'corpus', 'voice', 'memory', 'room')
# pwsh -File passes '-Step corpus,room' as one text, so the names are split at commas.
$Step = @($Step | ForEach-Object { "$_" -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if ($Step) {
    $unknown = @($Step | Where-Object { $_ -notin $stepNames })
    if ($unknown) { throw "Unknown steps: $($unknown -join ', '). The steps are: $($stepNames -join ', ')." }
}
# Before any command reaches a device: a dry run answers the owner's questions itself, so it must never reach a phone.
if ($DryRun -and $Serial -notlike 'emulator-*') { throw 'A dry run runs only on the emulator kestrel-api37.' }

$owner = Join-Path $PSScriptRoot '..\corpus\owner'
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'
$resultsFile = if ($DryRun) { Join-Path $owner "session-dry-run-$stamp.txt" } else { Join-Path $owner "session-$stamp.txt" }
# The session's state across its runs, such as a run with -Step: the time of its last crash of Kestrel.
$stateFile = if ($DryRun) { Join-Path $owner 'session-dry-run-state.txt' } else { Join-Path $owner 'session-state.txt' }
$clipsFolder = if ($DryRun) { Join-Path $owner "dry-run-clips-$stamp" } else { Join-Path $owner 'clips' }
$corpusFile = Join-Path $PSScriptRoot '..\corpus\commands.txt'
$remote = Get-EvalFolder
$service = 'dev.kestrel/dev.kestrel.app.KestrelAccessibilityService'
$assistantRole = 'android.app.role.ASSISTANT'
$earbudsChanged = $false
$gradleUsed = $false
$prepared = $false

# Asks the owner to do something, and returns 'go' after Enter or 'skip' after s. q stops the session. In a dry run it
# answers $DryRunAnswer itself.
function Request-Owner([string]$Prompt, [string]$DryRunAnswer = 'skip') {
    if ($DryRun) {
        Write-Host "[dry run] $Prompt"
        return $DryRunAnswer
    }
    $answer = (Read-Host $Prompt).Trim().ToLowerInvariant()
    if ($answer -eq 'q') { throw 'The owner stopped the session.' }
    if ($answer -in 's', 'skip') { 'skip' } else { 'go' }
}

# Asks the owner a question, and returns the answer as typed. In a dry run it answers $DryRunAnswer itself.
function Read-OwnerAnswer([string]$Prompt, [string]$DryRunAnswer) {
    if ($DryRun) {
        Write-Host "[dry run] $Prompt"
        return $DryRunAnswer
    }
    (Read-Host $Prompt).Trim()
}

if ((Request-Owner "The session first reads the model of the device $Serial, to check that it is the Galaxy A71. Press Enter to go ahead, or q to stop." 'go') -ne 'go') {
    exit 1
}
# Refuses every device but the A71 and kestrel-api37; the daily phone would need -AllowDailyPhone, which this script
# never passes.
Assert-TestDevice -Serial $Serial
$isEmulator = $KestrelTestDevices[$Serial].Pattern -eq 'kestrel-api37'
if ($DryRun -and -not $isEmulator) { throw 'A dry run runs only on the emulator kestrel-api37.' }
$adb = Get-Adb

function Add-Result([string]$StepName, [string]$Text) {
    Add-SessionResult -Path $resultsFile -Step $StepName -Text $Text
    Write-Host "${StepName}: $Text"
}

# Runs a command on the device and fails when it fails; it runs only on the device that Assert-TestDevice accepted.
function Invoke-Checked([string]$Command) { Invoke-SettingsCommand -Serial $Serial -Command $Command }

# Runs scripts\hook.ps1 with a command and its parameters, which a hashtable names, and returns the answer as one text.
function Invoke-Hook([string]$Command, [hashtable]$With = @{}) {
    (& "$PSScriptRoot\hook.ps1" -Serial $Serial -Command $Command @With) -join "`n"
}

# In a dry run, sends what the owner would say, as if Kestrel had heard it, with a pause after each text; a text that
# starts with '+' comes with the keyword spotter's "Kestrel".
function Send-Heard([string[]]$Texts) {
    foreach ($text in $Texts) {
        Invoke-Hook voice @{ Send = 'heard'; Text = $text.TrimStart('+'); Named = $text.StartsWith('+') } | Out-Null
        Start-Sleep -Seconds 2
    }
}

# Kestrel's state from the voice-state hook, or $null while Kestrel does not answer.
function Get-VoiceState {
    try { ConvertFrom-VoiceState (Invoke-Hook voice-state) } catch { $null }
}

# Waits until $Condition is true for Kestrel's state, which it gets as its argument. Returns the seconds that it waited,
# or -1 after the timeout.
function Wait-State([scriptblock]$Condition, [double]$TimeoutSeconds) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $state = Get-VoiceState
        if ($state -and (& $Condition $state)) { return [math]::Round($watch.Elapsed.TotalSeconds, 1) }
        Start-Sleep -Milliseconds 250
    } while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    -1
}

# Waits until Kestrel is in the mode, with its voice session open, or closed for 'off'.
function Wait-Mode([string]$Mode, [double]$TimeoutSeconds) {
    $session = if ($Mode -eq 'off') { 'closed' } else { 'open' }
    Wait-State ({ param($State) $State.mode -eq $Mode -and $State.session -eq $session }.GetNewClosure()) $TimeoutSeconds
}

function Format-Wait([double]$Seconds) { if ($Seconds -lt 0) { 'failed' } else { "ok after $Seconds s" } }

# The counts of the hook voice-counts: Waits, the number of Kestrel's latest wait for a task, which rises with each wait
# and also when a wait starts again; Tasks, how many tasks Kestrel has started in its process; LastTaskWords, how many
# words the last slow task of the hooks had; and Hits, how many keyword hits its voice sessions reported, without those
# that the echo filter took for Kestrel's own voice.
function Get-Counts {
    $text = Invoke-Hook voice-counts
    if ($text -notmatch '^waits=(\d+) tasks=(\d+) lastTaskWords=(\d+) hits=(\d+)\b') { throw 'Unexpected answer of voice-counts.' }
    [pscustomobject]@{ Waits = [int]$Matches[1]; Tasks = [int]$Matches[2]; LastTaskWords = [int]$Matches[3]; Hits = [int]$Matches[4] }
}

# The longest poll of Kestrel's state in seconds, which the voice step reports as the bound of how late a time can be:
# each poll runs the device check and a broadcast through hook.ps1.
$longestPoll = 0.0

function Write-PollTime([Diagnostics.Stopwatch]$Poll) {
    $script:longestPoll = [math]::Max($script:longestPoll, [math]::Round($Poll.Elapsed.TotalSeconds, 1))
}

# Waits until the count ('Waits' or 'Tasks') is above $Before. Returns the seconds that it waited, or -1.
function Wait-Count([string]$Name, [int]$Before, [double]$TimeoutSeconds) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $poll = [Diagnostics.Stopwatch]::StartNew()
        $above = (Get-Counts).$Name -gt $Before
        Write-PollTime $poll
        if ($above) { return [math]::Round($watch.Elapsed.TotalSeconds, 1) }
        Start-Sleep -Milliseconds 250
    } while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    -1
}

# Whether Kestrel's accessibility service is on now, for the lines of the battery and memory measurements, because its
# own work counts in Kestrel's use (research r10, gap 6).
function Get-KestrelServiceState {
    $services = Get-SecureSetting -Serial $Serial -Name enabled_accessibility_services
    if (@("$services" -split ':') -contains $service) { 'on' } else { 'off' }
}

# Turns Kestrel off, which it also stores, and waits until its voice session has closed.
function Stop-Kestrel {
    Invoke-Hook voice @{ Send = 'off' } | Out-Null
    if ((Wait-Mode off 30) -lt 0) { throw 'Kestrel did not turn off within 30 s.' }
}

# Turns Kestrel on with its own microphone, from the front, where Android lets it start its microphone service, and goes
# back to the home screen. Loading the models takes up to a minute on the A71.
function Start-Kestrel {
    Invoke-Hook voice-clip @{ Clear = $true } | Out-Null
    $state = Get-VoiceState
    if ($state -and $state.mode -eq 'paused') { Invoke-Hook voice @{ Send = 'resume' } | Out-Null }
    if (-not $state -or $state.mode -eq 'off') {
        Invoke-Checked 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
        Invoke-Hook voice @{ Send = 'toggle' } | Out-Null
    }
    if ((Wait-Mode listening 120) -lt 0) { throw 'Kestrel did not start listening within 120 s.' }
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
}

# Kestrel listens, says nothing and waits for no task, so that a try starts from the same state each time.
function Wait-Quiet {
    $state = Get-VoiceState
    if (-not $state -or $state.mode -ne 'listening') { Start-Kestrel }
    # "Kestrel, stop" ends a reply, cancels a task and ends a wait for a task (design spec, section 8.4); a plain "stop"
    # while nothing runs would be an instant command.
    Invoke-Hook voice @{ Send = 'heard'; Text = 'Kestrel, stop.'; Named = $true } | Out-Null
    Start-Sleep -Seconds 1
    if ((Wait-State { param($State) $State.work -eq 'idle' -and $State.speaking -eq 'false' } 40) -lt 0) {
        throw 'Kestrel did not come to rest within 40 s.'
    }
}

# The riids of Kestrel's recordings that 'dumpsys audio' lists now.
function Get-KestrelRecording { Get-RecordingId -Dump (Invoke-Checked 'dumpsys audio') -Package dev.kestrel }

# Waits up to 5 s until Kestrel's newest recording is new and not silenced. Returns nothing then, and otherwise why not.
function Get-RecordingProblem([string[]]$Before) {
    $state = 'none'
    for ($i = 0; $i -lt 10; $i++) {
        if ($i -gt 0) { Start-Sleep -Milliseconds 500 }
        $state = Get-NewRecordingState -Dump (Invoke-Checked 'dumpsys audio') -Package dev.kestrel -Before $Before
        if ($state -eq 'not silenced') { return }
    }
    if ($state -eq 'silenced') { 'Android silenced the microphone' } else { 'no new recording of Kestrel''s showed within 5 s' }
}

# After a start, the owner says "Kestrel", and the script records whether Kestrel waited for a task and whether the owner
# heard "Yes?", because Android 13 may refuse the replies of a service that started in the background (final review of
# plan 2C-1, item 3).
function Get-YesAfterStart {
    $counts = Get-Counts
    if ((Request-Owner 'Say "Kestrel". Press Enter right after you said it, or s to skip this.') -eq 'skip') { return '"Yes?" not tried' }
    $asked = Wait-Count Waits $counts.Waits 10
    $heard = Read-OwnerAnswer 'Did you hear "Yes?"? Type y or n.' 'n'
    "after ""Kestrel"", Kestrel waited for a task: $(Format-Wait $asked); ""Yes?"" was heard: $heard"
}

# What the script has still to put back. Each restore enters the list before its change, and leaves it when it worked.
# $ByHand says how to put it back by hand, for the warning at the end.
$pending = [System.Collections.Generic.List[object]]::new()

function Register-Restore([string]$What, [string]$ByHand, $Data, [scriptblock]$Action) {
    $restore = [pscustomobject]@{ What = $What; ByHand = $ByHand; Data = $Data; Action = $Action; Reason = 'the session ended before it' }
    $pending.Add($restore)
    $restore
}

# Runs a restore that is still pending, and keeps it pending, with the reason, when it fails.
function Complete-Restore($Restore) {
    if (-not $pending.Contains($Restore)) { return }
    try {
        & $Restore.Action $Restore.Data | Out-Null
        $pending.Remove($Restore) | Out-Null
    } catch {
        $Restore.Reason = $_.Exception.Message
    }
}

# Runs the restores in order, each in the finally block of the one before, so that a stop such as Ctrl+C during one of
# them still runs the others.
function Invoke-Restore([object[]]$Restores, [int]$Index = 0) {
    if ($Index -ge $Restores.Count) { return }
    try {
        Complete-Restore $Restores[$Index]
    } finally {
        Invoke-Restore $Restores ($Index + 1)
    }
}

# Before the first step that runs: the restores that every session needs, and a screen that stays on, because Kestrel
# records only while the screen is on and the phone unlocked, and Android silences a recording of Kestrel's settings once
# they are no longer in front. Registered first, so that they come last.
function Initialize-Session {
    if ($script:prepared) { return }
    $script:prepared = $true
    Register-Restore "Kestrel's mode Off" "Run: scripts\hook.ps1 -Serial $Serial voice -Send off" $null { Stop-Kestrel } | Out-Null
    Register-Restore "the hooks' tasks" "Run: scripts\hook.ps1 -Serial $Serial voice-task -Clear" $null {
        Invoke-Hook voice-task @{ Clear = $true } | Out-Null
    } | Out-Null
    Register-Restore "the clips in Kestrel's folder files/eval" "Run: & '$adb' -s $Serial shell 'rm -f $remote/*.wav'" $null {
        Assert-TestDevice -Serial $Serial
        Invoke-Checked "rm -f $remote/*.wav" | Out-Null
    } | Out-Null
    $stayOn = ((Invoke-Checked 'settings get global stay_on_while_plugged_in') -join '').Trim()
    $byHand = "Run: & '$adb' -s $Serial shell settings put global stay_on_while_plugged_in $stayOn"
    Register-Restore 'the setting "Stay awake"' $byHand $stayOn {
        param($Data)
        Assert-TestDevice -Serial $Serial
        $command = if ($Data -in 'null', '') { 'settings delete global stay_on_while_plugged_in' } else { "settings put global stay_on_while_plugged_in $Data" }
        Invoke-Checked $command | Out-Null
    } | Out-Null
    Invoke-Checked 'svc power stayon true' | Out-Null
    Request-Owner 'The phone''s screen now stays on while the phone is plugged in. Keep the phone unlocked during the session, except when a step asks you to lock it. Press Enter to go on.' 'go' | Out-Null
}

function Invoke-InstallStep {
    # On Android 13 an update ends Kestrel's process with the reason of the owner's stop, so Kestrel would stay off after it.
    try { Stop-Kestrel } catch { Write-Host 'Kestrel did not answer, so it is probably not installed yet.' }
    $script:gradleUsed = $true
    & "$PSScriptRoot\install.ps1" -Serial $Serial
    if ($LASTEXITCODE -ne 0) { throw 'scripts\install.ps1 failed.' }
    & "$PSScriptRoot\push-models.ps1" -Serial $Serial -Kokoro
    # After a new install Kestrel has neither permission, and without the microphone it turns itself off at the start
    # (design spec, section 14.6). Both stay granted after the session, as after scripts\voice-test.ps1.
    Invoke-Checked 'pm grant dev.kestrel android.permission.RECORD_AUDIO' | Out-Null
    Invoke-Checked 'pm grant dev.kestrel android.permission.POST_NOTIFICATIONS' | Out-Null
    Add-Result install 'Kestrel installed, the speech models copied with the Kokoro voice, and the microphone and notification permissions granted'
    # On Android 13 an update ends Kestrel with the reason of the owner's stop, so the restore keeps it off; the session
    # starts it itself (final review of plan 2C-4, "For plan 2D", item 7).
    Start-Kestrel
    Add-Result install 'Kestrel started listening when the session started it'
    Stop-Kestrel
}

function Invoke-VoiceTestStep {
    if ($DryRun) {
        Add-Result voice-tests 'skipped in a dry run; the plan runs scripts\voice-test.ps1 itself'
        return
    }
    $script:gradleUsed = $true
    # An earlier run's results, such as the emulator's, must never count as this phone's, also when voice-test.ps1 fails
    # before it deletes them itself.
    $results = Join-Path $PSScriptRoot '..\app\build\outputs\androidTest-results\connected'
    if (Test-Path -LiteralPath $results) { Remove-Item -LiteralPath $results -Recurse -Force }
    # The tests log only measurements, never what was said, and voice-test.ps1 prints those lines.
    $lines = @(& "$PSScriptRoot\voice-test.ps1" -Serial $Serial *>&1 | ForEach-Object { $text = "$_"; Write-Host $text; $text })
    $code = $LASTEXITCODE
    foreach ($line in $lines | Where-Object { $_ -match '^Voice tests: |KestrelVoiceTest' }) {
        Add-Result voice-tests ($line -replace '^.*KestrelVoiceTest\s*:\s*', '')
    }
    if ($code -ne 0) { Add-Result voice-tests "ended with exit code $code; the results are in app\build\outputs\androidTest-results\connected" }
    # By name, because the next run of the device tests deletes the result files. Only the names of classes and tests.
    foreach ($test in Get-UnpassedTests -Folder $results) { Add-Result voice-tests "did not pass: $test" }
    Add-Result voice-tests "spike S8, OnnxRuntimeTogetherTest: $(if (Test-ClassPassed -Folder $results -Class OnnxRuntimeTogetherTest) { 'passed' } else { 'did not pass' })"
    # connected-test.ps1 ends with 'am force-stop', which leaves a record of the owner's stop, so a restore keeps Kestrel
    # off; the session starts it itself (final review of plan 2C-4, "For plan 2D", item 9).
    $mode = (Get-VoiceState).mode
    Start-Kestrel
    Add-Result voice-tests "after the tests Kestrel's mode was $mode, and it started listening when the session started it"
    Stop-Kestrel
}

function Invoke-ListenCheckStep {
    # The notification check reads One UI's shade with uiautomator dump, which also holds the owner's notifications;
    # listen-check.ps1 deletes the dump after each read and never prints it.
    if (-not $DryRun -and (Request-Owner 'The check restarts the phone, and it reads the notification shade, whose text it deletes at once and never shows. Unlock the phone whenever the script asks, also after the restart. Press Enter to start, or s to skip.') -eq 'skip') {
        Add-Result listen-check 'skipped'
        return
    }
    $lines = @(& "$PSScriptRoot\listen-check.ps1" -Serial $Serial -Reboot:(-not $DryRun) *>&1 | ForEach-Object { $text = "$_"; Write-Host $text; $text })
    $code = $LASTEXITCODE
    # listen-check's restart and role checks end Kestrel's process with 'am crash'; its end is no earlier than the last.
    Write-SessionCrash -Path $stateFile -Serial $Serial -Time (Get-Date)
    # Each check's line holds its name, ok, failed or skipped, and seconds.
    foreach ($line in $lines | Where-Object { $_ -match '^[a-z]+\s+(ok|failed|skipped)\b' }) { Add-Result listen-check $line }
    if ($code -ne 0) { Add-Result listen-check "ended with exit code $code" }
    # When Kestrel's accessibility service was off before the check, its notice about the service stays in the shade
    # (final review of plan 2C-4, N21).
    Request-Owner 'If Kestrel''s notice that its accessibility service is off is in the notification shade, swipe it away. Press Enter after it.' 'go' | Out-Null
}

function Invoke-FailuresStep {
    $failures = @(Get-FailureDescriptions -Log @(Invoke-Checked 'logcat -d -s Kestrel:W'))
    if ($failures.Count -eq 0) {
        Add-Result failures 'Kestrel logged no failure in the device''s log'
        return
    }
    foreach ($failure in $failures) { Add-Result failures "$($failure.Count) x ""$($failure.What) failed""" }
    # The log shows that the microphone failed, not whether listening came back a second later; ListeningTest checks that.
    if ($failures.What -contains 'The microphone') {
        Add-Result failures 'whether listening came back after each failure of the microphone is not in the log'
    }
}

function Invoke-CloseStartStep {
    Start-Kestrel
    $before = Get-KestrelRecording
    if ((Request-Owner 'Kestrel listens. Check that its tile is in Quick Settings. Say "Kestrel, turn off", and right after it, tap Kestrel''s tile. Press Enter once you tapped it, or s to skip.') -eq 'skip') {
        Add-Result close-start 'skipped'
        return
    }
    # The close comes first: the old session closes while the new start waits for it.
    $closing = Wait-State { param($State) $State.mode -ne 'listening' -or $State.session -eq 'closed' } 15
    if ($closing -lt 0) {
        Add-Result close-start 'failed: Kestrel did not turn off, so it did not hear "Kestrel, turn off"'
        return
    }
    $on = Wait-Mode listening 120
    if ($on -lt 0) {
        $mode = (Get-VoiceState).mode
        Add-Result close-start "failed: Kestrel did not listen again within 120 s; its mode is $mode"
        return
    }
    $problem = Get-RecordingProblem $before
    $listening = if ($problem) { "failed: listening again after the tap, but $problem" } else { 'ok: listening again after the tap, not silenced' }
    Add-Result close-start "$listening; $(Get-YesAfterStart)"
}

function Invoke-LockTileStep {
    Stop-Kestrel
    if ((Request-Owner 'Kestrel is off. Lock the phone with the side key, wake it to the lock screen, swipe down to Quick Settings, and tap Kestrel''s tile without unlocking. Press Enter afterwards, or s to skip.') -eq 'skip') {
        Add-Result lock-tile 'skipped'
        return
    }
    Start-Sleep -Seconds 5
    $state = Get-VoiceState
    $asked = Read-OwnerAnswer 'Did the phone ask you to unlock it before anything happened? Type y or n.' 'n'
    Add-Result lock-tile "after a tap on the lock screen, Kestrel's mode is $(if ($state) { $state.mode } else { 'unknown' }); the phone asked to unlock first: $asked"
    Request-Owner 'Unlock the phone. Press Enter after it.' 'go' | Out-Null
    Stop-Kestrel
}

function Invoke-UnlockStep {
    foreach ($method in 'a PIN', 'a fingerprint, from the screen off', 'the face', 'Smart Lock') {
        Start-Kestrel
        $before = Get-KestrelRecording
        if ((Request-Owner "Kestrel listens. Turn the screen off with the side key, wait 5 s, and unlock the phone with $method. Press Enter after the unlock, or s if the phone does not use it.") -eq 'skip') {
            Add-Result unlock "${method}: skipped"
            continue
        }
        $on = Wait-State { param($State) $State.mode -eq 'listening' -and $State.screenOn -eq 'true' -and $State.microphone -eq 'true' } 20
        if ($on -lt 0) {
            Add-Result unlock "${method}: failed: Kestrel did not record with the screen on within 20 s"
            continue
        }
        $problem = Get-RecordingProblem $before
        Add-Result unlock $(if ($problem) { "${method}: failed: $problem" } else { "${method}: ok: Kestrel records again, not silenced" })
    }
    Stop-Kestrel
}

# The accessibility settings, which a step changes and puts back.
function Register-AccessibilityRestore {
    $saved = Get-AccessibilitySettings -Serial $Serial
    Register-Restore 'the accessibility settings' 'Put back the accessibility services, the shortcut and the button in Settings > Accessibility.' $saved {
        param($Data)
        Assert-TestDevice -Serial $Serial
        if (-not (Restore-AccessibilitySettings -Serial $Serial -Saved $Data)) { throw 'see the warning above' }
    }
}

# The enabled accessibility services of the saved settings, without Kestrel's.
function Get-OtherServices($Saved) {
    $services = if ($Saved.Contains('enabled_accessibility_services')) { $Saved['enabled_accessibility_services'] } else { 'null' }
    @(@("$services" -split ':') | Where-Object { $_ -and $_ -ne 'null' -and $_ -ne $service })
}

function Invoke-ActivationStep {
    $restore = Register-AccessibilityRestore
    try {
        # Kestrel's service must be on for the shortcut and the button; the owner's other services stay on. The list is
        # quoted for the device's shell.
        $services = @(Get-OtherServices $restore.Data) + $service
        Invoke-Checked "settings put secure enabled_accessibility_services '$($services -join ':')'" | Out-Null
        Invoke-Checked 'settings put secure accessibility_enabled 1' | Out-Null
        Invoke-Checked "settings put secure accessibility_shortcut_target_service $service" | Out-Null
        Invoke-Checked "settings put secure accessibility_button_targets $service" | Out-Null
        $methods = [ordered]@{
            shortcut = 'Press and hold both volume keys for 3 s'
            button = 'Tap the accessibility button, in the navigation bar or floating over the screen'
            tile = 'Swipe down to Quick Settings and tap Kestrel''s tile; add the tile first if it is not there'
        }
        foreach ($name in $methods.Keys) {
            Stop-Kestrel
            Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
            $before = Get-KestrelRecording
            if ((Request-Owner "Kestrel is off and in the background. $($methods[$name]). Press Enter after it, or s to skip.") -eq 'skip') {
                Add-Result activation "${name}: skipped"
                continue
            }
            $on = Wait-Mode listening 120
            $problem = if ($on -ge 0) { Get-RecordingProblem $before }
            $started = if ($on -lt 0) { 'did not start listening within 120 s' } elseif ($problem) { "started after $on s, but $problem" } else { "started after $on s, not silenced" }
            $yes = if ($on -ge 0) { Get-YesAfterStart } else { '"Yes?" not tried' }
            Request-Owner "Now do it again, which should pause Kestrel: $($methods[$name]). Press Enter after it." 'go' | Out-Null
            $paused = Wait-Mode paused 15
            Add-Result activation "${name}: $started; $yes; the second time paused it: $(Format-Wait $paused)"
        }
        Start-Kestrel
        if ((Request-Owner 'Kestrel listens. Open another app, such as Settings. Pull down the notifications and tap Pause in Kestrel''s notification. Press Enter after it, or s to skip.') -eq 'skip') {
            Add-Result activation 'notification: skipped'
        } else {
            $paused = Wait-Mode paused 15
            Request-Owner 'Now tap Resume in the notification. Press Enter after it.' 'go' | Out-Null
            $resumed = Wait-Mode listening 15
            Request-Owner 'Now tap Stop in the notification. Press Enter after it.' 'go' | Out-Null
            $stopped = Wait-Mode off 30
            Add-Result activation "notification with another app in front: Pause $(Format-Wait $paused), Resume $(Format-Wait $resumed), Stop $(Format-Wait $stopped)"
        }
        Stop-Kestrel
    } finally {
        Complete-Restore $restore
    }
}

# After a start that the count of waits showed ($Waited seconds, or -1), whether Kestrel's new recording is silenced.
function Get-StartRecording([double]$Waited, [string[]]$Before) {
    if ($Waited -lt 0) { return 'not checked' }
    $problem = Get-RecordingProblem $Before
    if ($problem) { $problem } else { 'not silenced' }
}

function Get-AssistantHolders { Get-RoleHolders ((Invoke-Checked "cmd role get-role-holders $assistantRole") -join ' ') }

function Invoke-AssistantStep {
    $holders = @(Get-AssistantHolders)
    $settings = [ordered]@{}
    foreach ($name in 'assistant', 'voice_interaction_service', 'voice_recognition_service') { $settings[$name] = Get-SecureSetting -Serial $Serial -Name $name }
    $byHand = "In Settings > Apps > Default apps > Digital assistant app, choose $(if ($holders) { $holders -join ', ' } else { 'None' }) again."
    $restore = Register-Restore 'the assistant role and its settings' $byHand @{ Holders = $holders; Settings = $settings } {
        param($Data)
        Assert-TestDevice -Serial $Serial
        if ($Data.Holders -notcontains 'dev.kestrel') { Invoke-Checked "cmd role remove-role-holder $assistantRole dev.kestrel" | Out-Null }
        foreach ($holder in $Data.Holders) { Invoke-Checked "cmd role add-role-holder $assistantRole $holder" | Out-Null }
        # Android points the three settings at the holder's services a moment after the change, as voice-test.ps1 knows.
        Start-Sleep -Seconds 2
        foreach ($name in $Data.Settings.Keys) {
            if (-not (Set-SecureSetting -Serial $Serial -Name $name -Value $Data.Settings[$name])) { throw "the setting $name differs from before" }
        }
        if ((@(Get-AssistantHolders | Sort-Object) -join ',') -ne (@($Data.Holders | Sort-Object) -join ',')) {
            throw 'the holders of the assistant role differ from before'
        }
    }
    $accessibility = Register-AccessibilityRestore
    try {
        Invoke-Checked "cmd role add-role-holder $assistantRole dev.kestrel" | Out-Null
        if (@(Get-AssistantHolders) -notcontains 'dev.kestrel') {
            Add-Result assistant 'failed: Android did not make Kestrel the assistant'
            return
        }
        Stop-Kestrel
        # What the role alone does on this phone (design spec, sections 8.5, 8.6 and 14): Kestrel's accessibility service
        # is off for the whole step, which would otherwise let Kestrel start in the background and open its settings by
        # itself. The owner's other services stay on.
        $others = @(Get-OtherServices $accessibility.Data)
        Invoke-Checked $(if ($others) { "settings put secure enabled_accessibility_services '$($others -join ':')'" } else { 'settings delete secure enabled_accessibility_services' }) | Out-Null
        if (-not (Wait-KestrelService off)) { throw 'Kestrel''s accessibility service did not turn off within 30 s.' }
        Invoke-RoleAloneChecks
        Stop-Kestrel
        $counts = Get-Counts
        $before = Get-KestrelRecording
        if ((Request-Owner 'Kestrel is the assistant now, and off, and its accessibility service is off. Open another app, such as Settings. Then long-press the home button, or swipe up from a bottom corner, whichever starts the assistant. Press Enter after it, or s to skip.') -eq 'skip') {
            Add-Result assistant 'the assistant gesture: skipped'
        } else {
            # Kestrel waits for a task after the gesture, as after "Kestrel" on its own; the count rises before "Yes?" is
            # spoken, so the recording and "Yes?" are checked too (spikes S1 and S2, final review of plan 2C-1, item 3).
            $waited = Wait-Count Waits $counts.Waits 120
            $recording = Get-StartRecording $waited $before
            $yes = Read-OwnerAnswer 'Did you hear "Yes?"? Type y or n.' 'n'
            $front = Read-OwnerAnswer 'Did the app in front stay as it was, without a window of Kestrel over it? Type y or n.' 'n'
            Add-Result assistant "the assistant gesture: Kestrel listened and waited for a task: $(Format-Wait $waited); its recording: $recording; ""Yes?"" was heard: $yes; the app in front stayed: $front"
        }
        Stop-Kestrel
        $since = ((Invoke-Checked "date '+%m-%d %H:%M:%S.000'") -join '').Trim()
        $counts = Get-Counts
        $before = Get-KestrelRecording
        if ((Request-Owner 'Connect the Galaxy Buds. Kestrel is off. Press and hold the touchpad of one earbud, the gesture that starts the assistant. If Android offers a choice of apps, pick Kestrel with "Just once". Press Enter after it, or s to skip.') -eq 'skip') {
            Add-Result assistant 'the Galaxy Buds: skipped'
        } else {
            $waited = Wait-Count Waits $counts.Waits 120
            $recording = Get-StartRecording $waited $before
            $yes = Read-OwnerAnswer 'Did you hear "Yes?"? Type y or n.' 'n'
            # Only the intents' actions are kept, not the components of other apps.
            $started = @(Get-StartedActions -Log @(Invoke-Checked "logcat -d -T '$since' -s ActivityTaskManager:I"))
            $chooser = Read-OwnerAnswer 'Did Android show a choice of apps? Type y or n.' 'n'
            $actions = if ($started) { $started -join ', ' } else { 'no activity' }
            Add-Result assistant "the Galaxy Buds: the gesture started $actions; Kestrel waited for a task: $(Format-Wait $waited); its recording: $recording; ""Yes?"" was heard: $yes; a choice of apps: $chooser"
        }
        Stop-Kestrel
    } finally {
        Complete-Restore $accessibility
        Complete-Restore $restore
    }
}

# Runs a command on the device once, for a command that a repeat would do twice, such as 'am crash': Android does not
# restart an app that crashed twice within 2 minutes. It runs only on the device that Assert-TestDevice accepted.
function Invoke-Once([string]$Command) {
    $ErrorActionPreference = 'Continue'
    $output = @(& $adb -s $Serial shell (Format-TestDeviceCommand -Serial $Serial -Command "$Command && echo kestrel-end") 2>&1)
    if ("$($output | Select-Object -Last 1)".Trim() -ne 'kestrel-end') { throw "Could not run '$Command' on $Serial." }
}

# Waits up to 30 s until Kestrel sees its accessibility service 'on' or 'off', and returns whether it did.
function Wait-KestrelService([string]$State) {
    for ($i = 0; $i -lt 30; $i++) {
        if ((Invoke-Hook state) -like "screen=$State*") { return $true }
        Start-Sleep -Seconds 1
    }
    $false
}

# Waits up to $TimeoutSeconds until Kestrel's settings are in front. Returns the seconds that it waited, or -1.
function Wait-SettingsInFront([double]$TimeoutSeconds) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $activities = (Invoke-Checked 'dumpsys activity activities') -join "`n"
        if ($activities -match 'topResumedActivity=ActivityRecord\{[^}]* dev\.kestrel/\.app\.MainActivity ') { return [math]::Round($watch.Elapsed.TotalSeconds, 1) }
        Start-Sleep -Milliseconds 500
    } while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    -1
}

# Final review of plan 2D (B, I1): with only the assistant role, without Kestrel's accessibility service and with Kestrel
# in the background, a start through the hook, "Kestrel, open settings", and the restore through the assistant service's
# onReady after Android ended Kestrel's process (design spec, sections 8.5, 8.6 and 14). None needs the owner's hands.
# Android does not restart an app that crashed twice within 2 minutes, so before its crash the step waits, if needed,
# until 2 minutes have passed since the session's last crash of Kestrel, listen-check's or an earlier one of this step.
function Invoke-RoleAloneChecks {
    Invoke-Checked 'input keyevent KEYCODE_WAKEUP' | Out-Null
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    # Not the exemption of an app that was in front a moment ago (prototype P2).
    Start-Sleep -Seconds 10
    $before = Get-KestrelRecording
    Invoke-Hook voice @{ Send = 'toggle' } | Out-Null
    $on = Wait-Mode listening 120
    if ($on -lt 0) {
        Add-Result assistant 'the role alone: a start through the hook in the background: failed: no listening within 120 s'
        return
    }
    $problem = Get-RecordingProblem $before
    Add-Result assistant "the role alone: a start through the hook in the background listened after $on s, $(if ($problem) { "but $problem" } else { 'not silenced' })"
    # Kestrel acts on a request only while the screen is on.
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    Start-Sleep -Seconds 2
    Invoke-Hook voice @{ Send = 'heard'; Text = 'Kestrel, open settings.'; Named = $true } | Out-Null
    $opened = Wait-SettingsInFront 10
    Add-Result assistant "the role alone: ""Kestrel, open settings"" brought Kestrel's settings to the front: $(Format-Wait $opened)"
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    # Kestrel stores Listening a moment after its voice session opened; a crash before that write would leave Off stored.
    Start-Sleep -Seconds 2
    $wait = Get-CrashWait -LastCrash (Read-SessionCrash -Path $stateFile -Serial $Serial) -Now (Get-Date)
    if ($wait -gt 0) {
        Write-Host "Waiting $wait s, so that 2 minutes have passed since the session's last crash of Kestrel; Android does not restart an app that crashed twice within 2 minutes."
        Start-Sleep -Seconds $wait
    }
    $recording = Get-KestrelRecording
    $process = Get-KestrelProcess
    Write-SessionCrash -Path $stateFile -Serial $Serial -Time (Get-Date)
    Invoke-Once 'am crash dev.kestrel'
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $new = ''
    while ($watch.Elapsed.TotalSeconds -lt 60) {
        Start-Sleep -Seconds 1
        $new = Get-KestrelProcess
        if ($new -and $new -ne $process) { break }
    }
    if (-not $new -or $new -eq $process) {
        Add-Result assistant 'the role alone: the restore through the assistant service: failed: Kestrel''s process did not start again within 60 s of its end'
        return
    }
    $again = Wait-Mode listening 120
    $problem = if ($again -ge 0) { Get-RecordingProblem $recording }
    $text = if ($again -lt 0) { 'failed: no listening within 120 s of the new start' } elseif ($problem) { "listening again after $([math]::Round($watch.Elapsed.TotalSeconds, 1)) s, but $problem" } else { "listening again after $([math]::Round($watch.Elapsed.TotalSeconds, 1)) s, not silenced" }
    Add-Result assistant "the role alone: the restore through the assistant service after Android ended Kestrel's process: $text"
}

function Invoke-EarbudStep {
    Stop-Kestrel
    if ((Request-Owner 'Connect the Galaxy Buds. Open Kestrel''s settings, page "Listening and activation", and turn on "Use the earbud microphone". Press Enter after it, or s to skip.') -eq 'skip') {
        Add-Result earbuds 'skipped'
        return
    }
    $script:earbudsChanged = $true
    Start-Kestrel
    $counts = Get-Counts
    Request-Owner 'Kestrel listens. Leave the phone unlocked, with its screen on, in another room, or under a pillow, and say "Kestrel" into the earbuds. Press Enter after you said it.' 'go' | Out-Null
    $heard = Wait-Count Waits $counts.Waits 15
    $inEarbuds = Read-OwnerAnswer 'Did you hear "Yes?" in the earbuds? Type y or n.' 'n'
    $callSound = Read-OwnerAnswer 'While Kestrel listened, did music or Kestrel''s voice sound like a phone call? Type y or n.' 'n'
    Add-Result earbuds "Kestrel heard its name through the earbuds: $(Format-Wait $heard); ""Yes?"" in the earbuds: $inEarbuds; the sound of a call: $callSound"
    $counts = Get-Counts
    Request-Owner 'Put the earbuds into their case, so that they disconnect. Then take the phone and say "Kestrel" to it. Press Enter after you said it.' 'go' | Out-Null
    $heard = Wait-Count Waits $counts.Waits 15
    Add-Result earbuds "after the earbuds went away, Kestrel heard its name through the phone: $(Format-Wait $heard)"
    # What this step cannot tell (Task 6): whether the earbuds used LE Audio or classic Bluetooth, and what a call that
    # takes the audio over does, because the A71 has no SIM card.
    Add-Result earbuds 'not checked: whether the earbuds used LE Audio, so the LE Audio path stays unchecked, and a call that takes the audio over'
    Stop-Kestrel
    Request-Owner 'Turn "Use the earbud microphone" off again in Kestrel''s settings, unless you want to keep it. Press Enter after it.' 'go' | Out-Null
}

# Records one line of the owner into the clip $File on this computer. The recording starts before "Speak now", so that
# the first word is not cut, and the screen is woken and Kestrel's settings brought to the front first, because Android
# silences a recording otherwise. Returns the hook's answer.
function Invoke-OwnerRecording([string]$File, [int]$Seconds) {
    Invoke-Checked 'input keyevent KEYCODE_WAKEUP' | Out-Null
    Invoke-Checked 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
    $started = Invoke-Hook voice-record @{ File = 'owner'; Seconds = $Seconds }
    if ($started -ne 'recording') { throw "voice-record did not start: $started" }
    Write-Host "Speak now ($Seconds s)"
    Start-Sleep -Seconds $Seconds
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $reply = Invoke-Hook voice-record @{ Result = $true }
        if ($reply -ne 'recording') { break }
        Start-Sleep -Milliseconds 250
    } while ($watch.Elapsed.TotalSeconds -lt 10)
    Invoke-AdbCopy -Serial $Serial -Direction pull -From "$remote/owner.wav" -To $File
    Invoke-Checked "rm -f $remote/owner.wav" | Out-Null
    $reply
}

function Invoke-RecordingStep {
    $rows = @(Read-Corpus -Path $corpusFile | Where-Object { $_.Owner })
    if ($DryRun) { $rows = @($rows | Select-Object -First 2) }
    New-Item -ItemType Directory -Force -Path $clipsFolder | Out-Null
    # The lines that an earlier, interrupted session recorded stay, unless the owner records all of them again.
    $done = @($rows | Where-Object { Test-Path -LiteralPath (Join-Path $clipsFolder "$($_.Id).wav") })
    if ($done) {
        $again = Read-OwnerAnswer "$($done.Count) of the $($rows.Count) lines are recorded already. Type a to record all of them again, or press Enter to record only the others." ''
        if ($again -ne 'a') { $rows = @($rows | Where-Object { $_ -notin $done }) }
    }
    Stop-Kestrel
    if ((Request-Owner "Next come $($rows.Count) lines to say, about 30 minutes. Sit in a quiet room and hold the phone as you would to talk to it; the script keeps Kestrel's settings in front. For each line, press Enter, wait for ""Speak now"", and say the line once, as you would say it to Kestrel. Press Enter to begin, or s to skip the recordings." 'go') -eq 'skip') {
        Add-Result recordings 'skipped'
        return
    }
    $recorded = 0
    for ($i = 0; $i -lt $rows.Count; $i++) {
        $row = $rows[$i]
        $answer = Read-OwnerAnswer "[$($i + 1)/$($rows.Count)] Say: $($row.Text)    (Enter to record, s to skip the line, q to stop)" ''
        if ($answer -eq 'q') { break }
        if ($answer -eq 's') { continue }
        $seconds = if ($DryRun) { 1 } else { Get-ClipSeconds -Text $row.Text }
        $reply = Invoke-OwnerRecording -File (Join-Path $clipsFolder "$($row.Id).wav") -Seconds $seconds
        $quiet = $reply -match 'silenced=true' -or ($reply -match 'loudest (\d+) %' -and [int]$Matches[1] -lt 3)
        if ($quiet -and -not $DryRun) {
            Write-Host 'That recording was silenced or too quiet. Check that the screen is on, the phone unlocked and Kestrel''s settings in front, and say the line again.'
            $i--
            continue
        }
        if ((Read-OwnerAnswer 'Enter for the next line, or r to record this line again.' '') -eq 'r') {
            $i--
            continue
        }
        $recorded++
    }
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    Add-Result recordings "$recorded of $($rows.Count) lines recorded into $((Resolve-Path -LiteralPath $clipsFolder).Path)"
}

function Invoke-CorpusStep {
    Stop-Kestrel
    # The language that the owner chose, if any, and the list of languages, which a phone has also when the owner never
    # changed the language after the setup.
    $locale = ((Invoke-Checked 'getprop persist.sys.locale') -join '').Trim()
    $locales = ((Invoke-Checked 'settings get system system_locales') -join '').Trim()
    Add-Result corpus "the phone's language: '$locale'; its list of languages: '$locales'"
    # In a dry run, the generated clips of Piper only; the owner's recordings of a dry run are silent and go at the end.
    # The owner's recordings also run without the boost words, which shows their effect with the owner's voice (final
    # review of plan 2C-3, item 3).
    $runs = [System.Collections.Generic.List[hashtable]]::new()
    if (-not $DryRun) {
        $runs.Add(@{ Corpus = 'owner' })
        $runs.Add(@{ Corpus = 'owner'; NoBoost = $true })
    }
    $runs.Add(@{ Corpus = 'generated'; Voice = 'piper' })
    if (-not $DryRun) { $runs.Add(@{ Corpus = 'generated'; Voice = 'kokoro' }) }
    # In front, Kestrel's process keeps the evaluation's recogniser, about 1 GB, also when the phone runs short of memory.
    Invoke-Checked 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
    try {
        foreach ($run in $runs) {
            $name = "-Corpus $($run.Corpus)" + $(if ($run.Voice) { " -Voice $($run.Voice)" }) + $(if ($run.NoBoost) { ' -NoBoost' })
            try {
                # voice-eval.ps1 prints only totals for the owner's recordings, and no transcript without -Show.
                $lines = @(& "$PSScriptRoot\voice-eval.ps1" -Serial $Serial @run *>&1 | ForEach-Object { $text = "$_"; Write-Host $text; $text })
                foreach ($line in $lines | Where-Object { $_ -match '^(Boost words|Clips|Requests|Transcription|Decoded|Kestrel''s memory)' }) {
                    Add-Result corpus "${name}: $line"
                }
            } catch {
                Add-Result corpus "${name}: failed: $($_.Exception.Message)"
            }
        }
    } finally {
        Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    }
}

# Asks the owner $Tries times to say something, and counts how often Kestrel reacted within $TimeoutSeconds after Enter.
# $Prepare brings Kestrel into the state that each try needs. $Reacted gets the counts from before the try and Kestrel's
# state, and returns whether Kestrel reacted. A dry run makes one try, in which it sends $DryRunHeard (see Send-Heard)
# in place of the owner. A failure, such as a Kestrel that does not come to rest, costs only this measure's line.
function Measure-Tries(
    [string]$Name,
    [string]$Say,
    [int]$Tries,
    [scriptblock]$Prepare,
    [scriptblock]$Reacted,
    [double]$TimeoutSeconds = 10,
    [string[]]$DryRunHeard = @()
) {
    if ($DryRun) { $Tries = 1 }
    $hits = 0
    $done = 0
    $times = @()
    try {
        for ($try = 1; $try -le $Tries; $try++) {
            & $Prepare
            $counts = Get-Counts
            if ((Request-Owner "$Say  (try $try of ${Tries}: press Enter right after you said it, or s to skip the rest)" 'go') -eq 'skip') { break }
            if ($DryRun) { Send-Heard $DryRunHeard }
            $done++
            $watch = [Diagnostics.Stopwatch]::StartNew()
            do {
                $poll = [Diagnostics.Stopwatch]::StartNew()
                $state = Get-VoiceState
                $seen = $state -and (& $Reacted $counts $state)
                Write-PollTime $poll
                if ($seen) {
                    $hits++
                    $times += [math]::Round($watch.Elapsed.TotalSeconds, 1)
                    break
                }
                Start-Sleep -Milliseconds 250
            } while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
        }
    } catch {
        if ($_.Exception.Message -eq 'The owner stopped the session.') { throw }
        Add-Result voice "${Name}: failed after $hits of $done tries: $($_.Exception.Message)"
        return
    }
    $text = if ($done -eq 0) { 'skipped' } else { "$hits of $done tries" }
    # A time can be late by up to one poll, whose longest duration so far the script measured.
    if ($times) { $text += "; seconds after Enter, each up to $script:longestPoll s late from the polling: $($times -join ', ')" }
    Add-Result voice "${Name}: $text"
}

# Runs one measure of the voice step, so that a failure costs one line, '<measure>: failed: <message>', and not the rest
# of the step, which takes about 30 minutes of the owner's time.
function Invoke-Measure([string]$Name, [scriptblock]$Measure) {
    try {
        & $Measure
    } catch {
        if ($_.Exception.Message -eq 'The owner stopped the session.') { throw }
        Add-Result voice "${Name}: failed: $($_.Exception.Message)"
    }
}

# Asks the owner $Tries times to say "Kestrel", wait for "Yes?" and dictate $Text, as $How says, while each task ends a
# second later. It counts the tries that became one task with nearly all the words, those that were cut short, and those
# that started no task within $TimeoutSeconds of Enter; the hooks count the words of the last task, never the words
# themselves. A dry run makes one try, in which it sends the text through the voice hook.
function Measure-Dictation([string]$Name, [string]$Text, [string]$How, [int]$Tries, [double]$TimeoutSeconds) {
    if ($DryRun) { $Tries = 1 }
    $words = @($Text -split '\s+').Count
    $whole = 0
    $short = 0
    $none = 0
    $done = 0
    $delays = @()
    Invoke-Hook voice-task @{ Seconds = 1; Reply = 'Done.' } | Out-Null
    try {
        for ($try = 1; $try -le $Tries; $try++) {
            Wait-Quiet
            $counts = Get-Counts
            if ((Request-Owner "Say ""Kestrel"", wait for ""Yes?"", and then say $How ""$Text"". Press Enter right after the last word (try $try of $Tries), or s to skip the rest." 'go') -eq 'skip') { break }
            if ($DryRun) { Send-Heard '+Kestrel', $Text }
            $done++
            # From the end of the speech: the hold of 1.85 s and the transcription.
            $ran = Wait-Count Tasks $counts.Tasks $TimeoutSeconds
            if ($ran -lt 0) {
                $none++
                continue
            }
            $delays += $ran
            if ((Get-Counts).LastTaskWords -ge $words - 3) { $whole++ } else { $short++ }
        }
    } finally {
        Invoke-Hook voice-task @{ Clear = $true } | Out-Null
    }
    $delayText = if ($delays) { "; seconds from Enter to the task, each up to $script:longestPoll s late from the polling: $($delays -join ', ')" } else { '' }
    Add-Result voice "${Name} of $words words: $whole of $done tries became one task with at least $($words - 3) of its words, $short were cut short, and $none started no task within $TimeoutSeconds s$delayText"
}

# Design D3 as the controller changed it, and review A, M2: on a "may duck" loss of the audio focus, such as a
# notification sound, Kestrel's own player lowers its voice, and Kestrel keeps speaking. The hooks' slow task with the
# long reply must be set. The script posts a notification of the shell, whose sound plays over the reply, and the owner
# swipes it away. A dry run runs it too, so that 'cmd notification post' runs once; the shell's notification then stays
# on the emulator, because the shell cannot remove it.
function Invoke-NotificationDuckCheck {
    Wait-Quiet
    if ((Request-Owner 'Say "Kestrel, read me the news". Once Kestrel reads its long reply, press Enter: the script then posts a notification, whose sound should play over the reply. Or press s to skip.' 'go') -eq 'skip') {
        Add-Result voice 'a notification sound during a reply: skipped'
        return
    }
    if ($DryRun) { Send-Heard '+Kestrel, read me the news' }
    if ((Wait-State { param($State) $State.speaking -eq 'true' } 15) -lt 0) {
        Add-Result voice 'a notification sound during a reply: Kestrel did not speak'
        return
    }
    Invoke-Checked "cmd notification post -t 'Kestrel session' kestrel-session 'A notification of the A71 session'" | Out-Null
    Start-Sleep -Seconds 3
    $state = Get-VoiceState
    $kept = [bool]($state -and $state.speaking -eq 'true')
    $heard = Read-OwnerAnswer 'Did a notification sound play, and did Kestrel''s voice get quieter under it and then go on? Type y, n, or none if no sound played.' 'none'
    Add-Result voice "a notification sound during a reply: Kestrel still spoke 3 s later: $kept; quieter and on, by ear: $heard"
    Request-Owner 'Swipe the notification "Kestrel session" away. Press Enter after it.' 'go' | Out-Null
}

# Plays a text in Kestrel's voice through the hook voice-echo, which runs in the background, and returns its answer.
function Invoke-Echo([string]$Text, [string]$Voice) {
    $reply = Invoke-Hook voice-echo @{ Text = $Text; Voice = $Voice }
    if ($reply -ne 'playing') { return $reply }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        Start-Sleep -Seconds 1
        $reply = Invoke-Hook voice-echo @{ Result = $true }
    } while ($reply -eq 'playing' -and $watch.Elapsed.TotalSeconds -lt 180)
    $reply
}

# The result line of an echo check from the hook's answer: a run whose microphone Android silenced, or that heard nearly
# nothing, failed, because its counts would look like those of a filter that let no hit through.
function Format-EchoResult([string]$Answer) {
    if ($Answer -match '\bsilenced=true\b') { return "failed: Android silenced the microphone ($Answer)" }
    if ($Answer -match '\bloudest=(\d+)\b' -and [int]$Matches[1] -lt 3) { return "failed: the microphone heard nearly nothing ($Answer)" }
    $Answer
}

function Invoke-VoiceStep {
    $quiet = { Wait-Quiet }
    $intro = 'Next you talk to Kestrel through the phone''s own speaker and microphone, without earbuds, in a quiet room, for about 30 minutes. Each time, say what the script shows, as you would say it to Kestrel, and press Enter right after it. Press Enter to begin, or s to skip.'
    if ((Request-Owner $intro 'go') -eq 'skip') {
        Add-Result voice 'talking to Kestrel: skipped'
    } else {
        # Until Kestrel begins to wait for the task, which comes before "Yes?" is synthesized: "Yes?" is too short for the
        # polls to catch its start reliably.
        Measure-Tries '"Kestrel", until Kestrel waits for the task' 'Say "Kestrel".' 5 $quiet { param($Counts, $State) $State.speaking -eq 'true' -or (Get-Counts).Waits -gt $Counts.Waits } -DryRunHeard '+Kestrel'
        Measure-Tries '"Hey Kestrel"' 'Say "Hey Kestrel".' 5 $quiet { param($Counts, $State) $State -and (Get-Counts).Waits -gt $Counts.Waits } -DryRunHeard '+Hey Kestrel.'
        # The wait for the task ends 8 s after "Yes?" without an answer (design spec, section 8.3).
        Invoke-Measure 'the wait after "Yes?"' {
            Wait-Quiet
            $counts = Get-Counts
            if ((Request-Owner 'Say "Kestrel", and then nothing. Press Enter right after you said it.' 'go') -ne 'skip') {
                if ($DryRun) { Send-Heard '+Kestrel' }
                $asked = Wait-Count Waits $counts.Waits 10
                $ended = if ($asked -ge 0) { Wait-State { param($State) $State.work -eq 'idle' } 20 } else { -1 }
                Add-Result voice "the wait after ""Yes?"": $(if ($asked -lt 0) { 'Kestrel did not answer' } else { "ended $ended s after Kestrel began to wait" })"
            }
        }
        Measure-Tries 'a quick answer to "Yes?"' 'Say "Kestrel", and as soon as Kestrel starts to say "Yes?", say "open the weather".' 3 $quiet { param($Counts, $State) $State -and (Get-Counts).Tasks -gt $Counts.Tasks } 15 -DryRunHeard '+Kestrel', 'open the weather'
        # A long answer keeps the wait open until it ends, and Kestrel holds an answer 1.85 s after its end; a part after
        # a longer pause would be a task of its own, and the rest would be lost (design spec, section 8.3).
        Invoke-Measure 'a long dictated task' {
            Measure-Dictation 'a long dictated task' 'send a message to Ana saying that I will be late because the bus did not come and I will take a taxi' 'slowly, with the pauses that you would make:' 3 15
        }
        # An answer near the limit of 30 s: the wait for it lasts 35 s from "Yes?", which assumes that the A71 transcribes
        # such an answer within about 4 s (Orchestrator.ANSWER_MILLIS).
        Invoke-Measure 'an answer of about 30 s' {
            $answer = 'remind me tomorrow morning to call the garage about the brakes and the winter tyres, then to buy bread, milk, ' +
                'eggs and two kinds of cheese on the way home, and after that to send a message to the neighbours that the ' +
                'parcel for the third floor is with us, because the courier came early and nobody else was at home'
            Measure-Dictation 'an answer of about 30 s' $answer 'at your normal pace, in one go, without long pauses:' 2 20
        }
        # Each task now takes a second and then reads a long reply without any of Kestrel's keywords, which the owner
        # interrupts.
        $story = 'Here is a long reply, so that you can interrupt it. ' + ('The news of the day comes one sentence after another, at an even pace. ' * 6)
        Invoke-Hook voice-task @{ Seconds = 1; Reply = $story } | Out-Null
        try {
            foreach ($pause in '0.5', '1', '1.5', '2') {
                Measure-Tries "an interruption with a pause of $pause s" "Say ""Kestrel, read me the news"". While Kestrel reads its long reply, say ""Kestrel"", wait about $pause s, and say ""open the weather""." 2 $quiet { param($Counts, $State) $State -and (Get-Counts).Tasks -ge $Counts.Tasks + 2 } 30 -DryRunHeard '+Kestrel, read me the news', '+Kestrel', 'open the weather'
            }
            Invoke-Measure 'a notification sound during a reply' { Invoke-NotificationDuckCheck }
            Invoke-Hook voice-task @{ Seconds = 15; Reply = 'The task is done.' } | Out-Null
            Measure-Tries '"stop" while a task runs and Kestrel is silent' 'Say "Kestrel, open Viber", wait about 3 s, and say "stop".' 3 $quiet { param($Counts, $State) $State.work -eq 'idle' -and (Get-Counts).Tasks -gt $Counts.Tasks } 5 -DryRunHeard '+Kestrel, open Viber', 'Stop.'
        } finally {
            Invoke-Hook voice-task @{ Clear = $true } | Out-Null
        }
        Measure-Tries '"Stop listening"' 'Say "Stop listening".' 5 $quiet { param($Counts, $State) $Counts -and $State.mode -eq 'paused' } 8 -DryRunHeard 'Stop listening.'
        $paused = {
            Wait-Quiet
            Invoke-Hook voice @{ Send = 'pause' } | Out-Null
            Wait-Mode paused 10 | Out-Null
        }
        Measure-Tries '"Kestrel, wake up" while paused' 'Say "Kestrel, wake up".' 5 $paused { param($Counts, $State) $Counts -and $State.mode -eq 'listening' } 8 -DryRunHeard '+Kestrel, wake up.'
        Measure-Tries '"Kestrel, turn off"' 'Say "Kestrel, turn off".' 3 $quiet { param($Counts, $State) $Counts -and $State.mode -eq 'off' } 10 -DryRunHeard '+Kestrel, turn off.'
        Measure-Tries '"Stop Kestrel"' 'Say "Stop Kestrel".' 3 $quiet { param($Counts, $State) $Counts -and $State.mode -eq 'off' } 10 -DryRunHeard 'Stop Kestrel.'
    }
    # The echo filter with the real player and spotter: Kestrel speaks through the speaker, and its microphone hears it.
    Stop-Kestrel
    if ((Request-Owner 'Next Kestrel speaks a few sentences to itself. Keep quiet for about three minutes. Press Enter to begin, or s to skip.' 'go') -eq 'skip') {
        Add-Result voice 'the echo filter: skipped'
        return
    }
    Invoke-Checked 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
    $voices = if ($DryRun) { @('piper') } else { @('piper', 'kokoro') }
    $texts = @('Should I stop the timer?')
    if (-not $DryRun) { $texts = @("It's top-rated.", 'That movie was unstoppable.', 'I cancelled the alarm at 06:45, and the code is 4 7 1 9.') + $texts }
    foreach ($voice in $voices) {
        foreach ($text in $texts) {
            # One failure costs one line, not the rest of the step.
            $answer = try { Format-EchoResult (Invoke-Echo $text $voice) } catch { "failed: $($_.Exception.Message)" }
            Add-Result voice "the echo filter with $voice, ""$text"": $answer"
        }
    }
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
}

function Get-KestrelPss { Get-TotalPss -Dump @(Invoke-Checked 'dumpsys meminfo dev.kestrel') }

function Get-KestrelProcess { ((Invoke-Checked 'pidof dev.kestrel || true') -join '').Trim() }

# The answer of the hook voice-choice: the stored voice, and whether Kokoro was chosen and Piper spoke instead the last
# time that a voice session opened.
function Get-VoiceChoice([hashtable]$With = @{}) {
    $text = Invoke-Hook voice-choice $With
    if ($text -notmatch '^voice=(\w+) speed=([\d.]+) kokoroFailed=(true|false)\b') { throw 'Unexpected answer of voice-choice.' }
    [pscustomobject]@{ Voice = $Matches[1]; KokoroFailed = $Matches[3] -eq 'true' }
}

# Three starts and closes from the front with the stored voice, each with Kestrel's memory (PSS) before, while it listens
# and after the close, and whether one process ran them all, as a session with Kokoro must survive several turn-ons.
function Measure-Starts([string]$Voice) {
    $process = Get-KestrelProcess
    for ($cycle = 1; $cycle -le 3; $cycle++) {
        $idle = Get-KestrelPss
        Invoke-Hook voice @{ Send = 'toggle' } | Out-Null
        $opened = Wait-Mode listening 120
        $listening = Get-KestrelPss
        $fellBack = if ($Voice -eq 'kokoro' -and (Get-VoiceChoice).KokoroFailed) { '; Kokoro did not load, so Piper spoke' } else { '' }
        Invoke-Hook voice @{ Send = 'off' } | Out-Null
        $closed = Wait-Mode off 60
        $after = Get-KestrelPss
        Add-Result memory "$Voice, start ${cycle}: the session opened $(Format-Wait $opened); PSS $idle MB before, $listening MB while listening and $after MB after the close, which ended $(Format-Wait $closed)$fellBack; Kestrel's accessibility service: $(Get-KestrelServiceState)"
    }
    Add-Result memory "${Voice}: one process ran the three starts: $($process -and (Get-KestrelProcess) -eq $process)"
}

function Invoke-MemoryStep {
    Stop-Kestrel
    # In front, Android lets the hook start Kestrel's microphone service.
    Invoke-Checked 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
    $chosen = Get-VoiceChoice
    Measure-Starts $chosen.Voice
    # Spec 5.3's memory target holds also for an owner who chooses Kokoro, the larger voice (Task 7 of plan 2D, M3): the
    # step chooses Kokoro, measures, and puts the owner's choice back.
    $voiceRestore = Register-Restore "Kestrel's voice" "Run: scripts\hook.ps1 -Serial $Serial voice-choice -Voice $($chosen.Voice), or choose the voice on Kestrel's page ""Voice and speech""." $chosen.Voice {
        param($Data)
        if ((Get-VoiceChoice @{ Voice = $Data }).Voice -ne $Data) { throw 'the stored voice differs from before' }
    }
    try {
        if ($chosen.Voice -ne 'kokoro') {
            Get-VoiceChoice @{ Voice = 'kokoro' } | Out-Null
            Measure-Starts kokoro
        }
        # "Try it" with the voice of the open session speaks through the session, so that no second copy of Kokoro loads.
        Invoke-Hook voice @{ Send = 'toggle' } | Out-Null
        if ((Wait-Mode listening 120) -lt 0) { throw 'Kestrel did not start listening with Kokoro within 120 s.' }
        if ((Request-Owner 'Kestrel listens with Kokoro. Open Kestrel''s settings, the page "Voice and speech", leave Kokoro chosen at its speed, and press "Try it". Press Enter right after you pressed it, or s to skip.') -eq 'skip') {
            Add-Result memory '"Try it" with Kokoro while Kestrel listens with Kokoro: skipped'
        } else {
            $before = Get-KestrelPss
            $most = $before
            $watch = [Diagnostics.Stopwatch]::StartNew()
            while ($watch.Elapsed.TotalSeconds -lt 8) {
                $most = [math]::Max($most, [int](Get-KestrelPss))
                Start-Sleep -Milliseconds 500
            }
            $heard = Read-OwnerAnswer 'Did you hear the sample? Type y or n.' 'n'
            Add-Result memory """Try it"" with Kokoro while Kestrel listens with Kokoro: PSS $before MB before, at most $most MB during the sample; the sample was heard: $heard"
        }
        Stop-Kestrel
        Invoke-Checked 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
    } finally {
        Complete-Restore $voiceRestore
    }
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    # A cold start: the tile starts the service in a new process, which must reach startForeground() in time.
    $restore = Register-AccessibilityRestore
    try {
        if ((Request-Owner 'Next comes a cold start through the tile: the script turns Kestrel''s accessibility service off and ends Kestrel''s process, and then you tap the tile. Check that Kestrel''s tile is in Quick Settings. Press Enter to go ahead, or s to skip.') -eq 'skip') {
            Add-Result memory 'a cold start through the tile: skipped'
            return
        }
        $others = @(Get-OtherServices $restore.Data)
        $command = if ($others) { "settings put secure enabled_accessibility_services '$($others -join ':')'" } else { 'settings delete secure enabled_accessibility_services' }
        Invoke-Checked $command | Out-Null
        # Without its accessibility service, Kestrel's process in the background may be ended.
        Start-Sleep -Seconds 3
        Invoke-Checked 'am kill dev.kestrel' | Out-Null
        Start-Sleep -Seconds 2
        if (Get-KestrelProcess) {
            Add-Result memory 'a cold start through the tile: failed: Kestrel''s process did not end'
            return
        }
        Request-Owner 'Now swipe down to Quick Settings and tap Kestrel''s tile. Press Enter after it.' 'go' | Out-Null
        $on = Wait-Mode listening 120
        $process = Get-KestrelProcess
        # A service that misses Android's deadline for startForeground() ends the process.
        Start-Sleep -Seconds 15
        $same = $process -and (Get-KestrelProcess) -eq $process -and (Get-VoiceState).mode -eq 'listening'
        Add-Result memory "a cold start through the tile: listening $(Format-Wait $on); the same process still listened 15 s later: $same"
        Stop-Kestrel
    } finally {
        Complete-Restore $restore
    }
}

function Invoke-RoomStep {
    $minutes = if ($DryRun) { 2 } else { 60 }
    $batteryMinutes = if ($DryRun) { 1 } else { 30 }
    if ((Request-Owner "Next comes the room: for $minutes minutes the phone lies, unlocked and with its screen on, in a room with ordinary sound, such as talk or television, and nobody speaks to it. Kestrel listens, and the script counts how often it reacts. In the first $batteryMinutes minutes the script also measures Kestrel's battery use, which resets the phone's battery statistics. Leave the USB cable connected. Press Enter to start, or s to skip." 'go') -eq 'skip') {
        Add-Result room 'skipped'
        return
    }
    # Android 13 prints the UID as userId= and later versions as appId=; the list of packages names it too.
    $userId = Get-AppUserId -Dump @(Invoke-Checked 'dumpsys package dev.kestrel')
    if (-not $userId) { $userId = Get-ListedUserId -List @(Invoke-Checked 'pm list packages -U dev.kestrel') -Package dev.kestrel }
    $uid = if ($userId) { ConvertTo-UidLabel -UserId $userId } else { $null }
    $timeout = ((Invoke-Checked 'settings get system screen_off_timeout') -join '').Trim()
    $timeoutRestore = Register-Restore "the screen's timeout" "Run: & '$adb' -s $Serial shell settings put system screen_off_timeout $timeout" $timeout {
        param($Data)
        Assert-TestDevice -Serial $Serial
        $command = if ($Data -eq 'null') { 'settings delete system screen_off_timeout' } else { "settings put system screen_off_timeout $Data" }
        Invoke-Checked $command | Out-Null
    }
    $batteryRestore = Register-Restore "the battery's state" "Run: & '$adb' -s $Serial shell dumpsys battery reset" $null {
        Assert-TestDevice -Serial $Serial
        Invoke-Checked 'dumpsys battery reset' | Out-Null
    }
    try {
        # Kestrel records only while the screen is on, and "Stay awake" ends once Android counts the phone as on battery.
        Invoke-Checked "settings put system screen_off_timeout $(($minutes + 10) * 60000)" | Out-Null
        Start-Kestrel
        # Research r10: first on battery, because Android resets the statistics itself when a phone at 90 % or more goes on
        # battery, and only then the reset. The battery's level stays frozen until the battery's state is put back.
        Invoke-Checked 'dumpsys battery unplug' | Out-Null
        Invoke-Checked 'dumpsys batterystats --reset' | Out-Null
        $last = Get-VoiceState
        $waits = 0
        $replies = 0
        $modeChanges = [System.Collections.Generic.List[string]]::new()
        $failedRestarts = 0
        $failedStops = 0
        $batteryDone = $false
        # The tasks that Kestrel starts are counted exactly, within one process of Kestrel's; they also cover every wait
        # that room talk answered.
        $countsBefore = try { Get-Counts } catch { $null }
        $watch = [Diagnostics.Stopwatch]::StartNew()
        while ($watch.Elapsed.TotalMinutes -lt $minutes) {
            # Each poll runs in Kestrel's process, so the script polls less often during the battery test. A wait that
            # nobody answers lasts at least 8 s, so no poll misses it, but a wait that room talk answers, and a short reply,
            # may fall between two polls.
            Start-Sleep -Seconds $(if ($batteryDone) { 1 } else { 5 })
            $state = Get-VoiceState
            if ($state -and $last) {
                if ($state.work -eq 'awaiting_task' -and $last.work -ne 'awaiting_task') { $waits++ }
                if ($state.speaking -eq 'true' -and $last.speaking -ne 'true') { $replies++ }
                if ($state.mode -ne $last.mode) {
                    $modeChanges.Add("minute $([math]::Floor($watch.Elapsed.TotalMinutes)): $($last.mode) to $($state.mode)")
                    # A false "Stop listening" or "Kestrel, turn off" would leave Kestrel paused or off for the rest of
                    # the hour, and the battery test would measure a Kestrel that does not listen. A restart that fails
                    # must not cost the hour's result line.
                    try {
                        Start-Kestrel
                    } catch {
                        $failedRestarts++
                    }
                    $state = Get-VoiceState
                }
            }
            if ($state) { $last = $state }
            if (-not $batteryDone -and $watch.Elapsed.TotalMinutes -ge $batteryMinutes) {
                $batteryDone = $true
                $batteryWatch = $watch.Elapsed
                # Batterystats gives an app the power of its recording only once the recording stops (seen on
                # kestrel-api37, whose power profile has no values for the processor), so Kestrel turns off for the
                # dump, and on again afterwards.
                try { Stop-Kestrel } catch { $failedStops++ }
                $charged = @(Invoke-Checked 'dumpsys batterystats --charged')
                $use = Get-BatteryUse -Dump $charged -Uid $uid -Hours $batteryWatch.TotalHours
                # Research r10, gap 2: Samsung's code may send its own plug events, and batterystats then stops counting.
                $onBattery = Test-OnBattery -Dump $charged
                Complete-Restore $batteryRestore
                $counted = if ($null -eq $onBattery) { 'not shown' } else { $onBattery }
                $how = ("with the screen on and room talk that Kestrel transcribed, a worst case; Kestrel's accessibility service: " +
                    "$(Get-KestrelServiceState); batterystats counted the phone as on battery at the end: $counted")
                if (-not $uid) {
                    Add-Result room "battery: no estimate, because neither dumpsys package nor pm list packages named Kestrel's UID, $how"
                } elseif ($use) {
                    Add-Result room "battery: Kestrel (UID $uid) used $($use.MilliampHours) mAh in $([math]::Round($batteryWatch.TotalMinutes)) minutes, with a capacity of $($use.Capacity) mAh: $($use.PercentPerHour) % per hour, $how"
                } else {
                    Add-Result room "battery: no estimate: $(Get-BatteryUseGap -Dump $charged -Uid $uid), $how"
                }
                try { Start-Kestrel } catch { $failedRestarts++ }
                $last = Get-VoiceState
            }
        }
        $changes = if ($modeChanges.Count -gt 0) { " ($($modeChanges -join '; '); each time the script started listening again)" } else { '' }
        # A failed hook call, or a new process of Kestrel's during the hour, must not cost the hour's result line.
        $countsAfter = try { Get-Counts } catch { $null }
        $tasks = if ($countsBefore -and $countsAfter -and $countsAfter.Tasks -ge $countsBefore.Tasks) {
            $countsAfter.Tasks - $countsBefore.Tasks
        } else {
            'an unknown number of'
        }
        # Spike S5 asks for the keyword spotter's false hits, which change nothing while Kestrel listens unless the transcript
        # also starts with the name.
        $hits = if ($countsBefore -and $countsAfter -and $countsAfter.Hits -ge $countsBefore.Hits) {
            $countsAfter.Hits - $countsBefore.Hits
        } else {
            'an unknown number of'
        }
        $failed = if ($failedRestarts -gt 0) { "; $failedRestarts restarts of listening failed" } else { '' }
        if ($failedStops -gt 0) { $failed += "; the turn-off before the battery's dump failed" }
        Add-Result room ("in $minutes minutes of room audio, the keyword spotter had $hits hits, Kestrel started $tasks tasks, began " +
            "to wait for a task at least $waits times, began to speak at least $replies times and changed its mode " +
            "$($modeChanges.Count) times$changes$failed")
        Stop-Kestrel
    } finally {
        Complete-Restore $batteryRestore
        Complete-Restore $timeoutRestore
    }
}

# The type of Kestrel's foreground service, as 'dumpsys activity services' lists it; 0 means that Android demoted it.
# Android 13 prints 'types=%08X', hexadecimal digits without '0x'.
function Get-ServiceType {
    foreach ($line in @(Invoke-Checked 'dumpsys activity services dev.kestrel')) {
        if ($line -match '\bforegroundServiceType=(0x[0-9a-fA-F]+|\d+)') { return $Matches[1] }
        if ($line -match '\btypes=(?:0x)?([0-9a-fA-F]+)') { return "0x$($Matches[1])" }
    }
    'not listed'
}

# Final review of plan 2C-4, "For plan 2D", item 7: what Android 13 and One UI do to Kestrel's microphone service in the
# background after its accessibility service is turned off, without and with the battery restriction (the app op
# RUN_ANY_IN_BACKGROUND, which isBackgroundRestricted() reads), and whether the checklist names Samsung's rules.
function Invoke-BatteryRulesStep {
    $manufacturer = ((Invoke-Checked 'getprop ro.product.manufacturer') -join '').Trim()
    $samsungText = Read-OwnerAnswer 'Open Kestrel''s settings and the setup checklist. Does its battery step name Samsung''s "Never sleeping apps"? Type y or n, and then go back to the home screen.' 'n'
    Add-Result battery-rules "the manufacturer: $manufacturer; the checklist names ""Never sleeping apps"": $samsungText"
    # 'allow', also for an op that has no entry ('No operations.'), or 'ignore'; never 'default', which Android counts as
    # restricted (prototype P2).
    $before = Get-BackgroundOpRestore -Answer @(Invoke-Checked 'cmd appops get dev.kestrel RUN_ANY_IN_BACKGROUND')
    $opRestore = Register-Restore "Kestrel's battery restriction" "Run: & '$adb' -s $Serial shell cmd appops set dev.kestrel RUN_ANY_IN_BACKGROUND $(if ($before -eq 'ignore') { 'ignore' } else { 'allow' })" $before {
        param($Data)
        Assert-TestDevice -Serial $Serial
        Invoke-Checked "cmd appops set dev.kestrel RUN_ANY_IN_BACKGROUND $(if ($Data -eq 'ignore') { 'ignore' } else { 'allow' })" | Out-Null
        $now = Get-AppOpMode -Answer @(Invoke-Checked 'cmd appops get dev.kestrel RUN_ANY_IN_BACKGROUND') -Op RUN_ANY_IN_BACKGROUND
        # Android may drop an entry that is set to the op's normal mode, and then answers 'No operations.'.
        if ($now -ne $Data -and -not ($null -eq $now -and $Data -eq 'allow')) { throw "the op reads '$now', not '$Data'" }
    }
    $accessibilityRestore = Register-AccessibilityRestore
    try {
        $others = @(Get-OtherServices $accessibilityRestore.Data)
        $withKestrel = "settings put secure enabled_accessibility_services '$((@($others) + $service) -join ':')'"
        $withoutKestrel = if ($others) { "settings put secure enabled_accessibility_services '$($others -join ':')'" } else { 'settings delete secure enabled_accessibility_services' }
        $minutes = if ($DryRun) { 0.5 } else { 3 }
        foreach ($restricted in $false, $true) {
            $label = if ($restricted) { 'with the battery restriction' } else { 'without the battery restriction' }
            Invoke-Checked "cmd appops set dev.kestrel RUN_ANY_IN_BACKGROUND $(if ($restricted) { 'ignore' } else { 'allow' })" | Out-Null
            Invoke-Checked $withKestrel | Out-Null
            Invoke-Checked 'settings put secure accessibility_enabled 1' | Out-Null
            Start-Kestrel
            $typeBefore = Get-ServiceType
            Invoke-Checked $withoutKestrel | Out-Null
            Request-Owner "Kestrel listens in the background, and its accessibility service is off now, $label. Leave the phone unlocked, with its screen on, for $minutes minutes. Press Enter to start the wait." 'go' | Out-Null
            Start-Sleep -Seconds ([int]($minutes * 60))
            $state = Get-VoiceState
            $mode = if ($state) { $state.mode } else { 'unknown' }
            $busy = if ($state) { $state.micBusy } else { 'unknown' }
            $notice = Read-OwnerAnswer 'Look at the notification shade. Type b if Kestrel says that its battery use is restricted, a if it says only that its accessibility service is off, o for another text of Kestrel''s, or n for none.' 'n'
            Add-Result battery-rules "${label}: the service's type $typeBefore before and $(Get-ServiceType) after $minutes minutes; Kestrel's mode $mode; the microphone silenced: $busy; Kestrel's notice: $notice"
            Stop-Kestrel
        }
    } finally {
        Complete-Restore $accessibilityRestore
        Complete-Restore $opRestore
    }
}

# Final review of plan 2C-4, "For plan 2D", items 7 and 10: whether a restart of the A71 leaves an exit record, whether
# the restore at boot finds the models, and how often a restore at boot ends before its session opens. The phone restarts
# with adb reboot; the emulator is not restarted, because listen-check.ps1 cold-boots it instead. The restore at boot
# runs when Kestrel's accessibility service connects, so the step turns the service on, beside the owner's others, and
# puts the settings back afterwards.
function Invoke-RebootStep {
    if ($DryRun) {
        Add-Result reboots 'skipped in a dry run; on the emulator, listen-check.ps1 checks the restore after a cold boot'
        return
    }
    $restore = Register-AccessibilityRestore
    try {
        $services = @(Get-OtherServices $restore.Data) + $service
        Invoke-Checked "settings put secure enabled_accessibility_services '$($services -join ':')'" | Out-Null
        Invoke-Checked 'settings put secure accessibility_enabled 1' | Out-Null
        $tries = 3
        $done = 0
        $restored = 0
        for ($try = 1; $try -le $tries; $try++) {
            Start-Kestrel
            if ((Request-Owner "Restart $try of ${tries}: the script restarts the phone while Kestrel listens. Press Enter to restart it, or s to skip the rest.") -eq 'skip') { break }
            $done++
            # A new boot ID shows that the phone has started again; adb reboot gets a time limit, as in listen-check.ps1.
            $bootId = ((Invoke-Checked 'cat /proc/sys/kernel/random/boot_id') -join '').Trim()
            Assert-TestDevice -Serial $Serial
            $call = Invoke-TimedProcess -FilePath $adb -ArgumentList '-s', $Serial, 'reboot' -TimeoutSeconds 60
            if (-not $call.Completed) { throw "'adb reboot' did not return within 60 s." }
            $watch = [Diagnostics.Stopwatch]::StartNew()
            do {
                Start-Sleep -Seconds 2
                $id = try { ((Invoke-Checked 'cat /proc/sys/kernel/random/boot_id') -join '').Trim() } catch { '' }
                $bootDone = if ($id -and $id -ne $bootId) {
                    try { ((Invoke-Checked 'getprop sys.boot_completed') -join '').Trim() } catch { '' }
                }
                $booted = $bootDone -eq '1'
            } while (-not $booted -and $watch.Elapsed.TotalSeconds -lt 300)
            if (-not $booted) { throw 'The phone did not start again within 5 minutes of the restart.' }
            Assert-TestDevice -Serial $Serial
            Request-Owner 'Unlock the phone, and press Enter after the unlock.' 'go' | Out-Null
            $on = Wait-Mode listening 180
            if ($on -ge 0) { $restored++ }
            $mode = (Get-VoiceState).mode
            # Only the reason's number and name of the newest record, never its description. A name may hold parentheses,
            # such as 'APP CRASH(NATIVE)'.
            $exit = 'none'
            foreach ($line in @(Invoke-Checked 'dumpsys activity exit-info dev.kestrel')) {
                if ($line -match '\breason=(\d+) \((.*?)\) subreason') {
                    $exit = "$($Matches[1]) ($($Matches[2]))"
                    break
                }
            }
            $notice = Read-OwnerAnswer 'Look at the notification shade. Type m if Kestrel says that its speech models are missing, o for another text of Kestrel''s, or n for none.' 'n'
            # After the first restart: whether the restore at boot, a start from the background, records unsilenced and can
            # speak (final review of plan 2C-1, "For plan 2D", item 3). 'dumpsys audio' starts its history afresh at each
            # boot, so every recording of Kestrel's that it lists is new.
            $after = ''
            if ($try -eq 1 -and $on -ge 0) {
                $problem = Get-RecordingProblem @()
                $after = "; its recording: $(if ($problem) { $problem } else { 'not silenced' }); $(Get-YesAfterStart)"
            }
            Add-Result reboots "restart ${try}: listening again: $(Format-Wait $on); Kestrel's mode $mode; the newest exit record: $exit; Kestrel's notice: $notice$after"
        }
        Add-Result reboots "the restore at boot listened again after $restored of $done restarts"
        Stop-Kestrel
    } finally {
        Complete-Restore $restore
    }
}

$plan = @(
    @{ Name = 'install'; Minutes = 10; Title = 'install Kestrel and copy the speech models with the Kokoro voice; you may leave'; Run = 'Invoke-InstallStep' }
    @{ Name = 'voice-tests'; Minutes = 30; Title = 'the voice device tests, among them spike S8, which may take up to an hour; you may leave'; Run = 'Invoke-VoiceTestStep' }
    @{ Name = 'listen-check'; Minutes = 10; Title = 'listen-check.ps1, with a restart of the phone'; Run = 'Invoke-ListenCheckStep' }
    @{ Name = 'failures'; Minutes = 1; Title = 'Kestrel''s logged failures'; Run = 'Invoke-FailuresStep' }
    @{ Name = 'battery-rules'; Minutes = 15; Title = 'Android''s and Samsung''s battery rules without the accessibility service'; Run = 'Invoke-BatteryRulesStep' }
    @{ Name = 'reboots'; Minutes = 12; Title = 'three restarts of the phone while Kestrel listens'; Run = 'Invoke-RebootStep' }
    @{ Name = 'close-start'; Minutes = 4; Title = 'a start during a close, with your help'; Run = 'Invoke-CloseStartStep' }
    @{ Name = 'lock-tile'; Minutes = 3; Title = 'the tile on the lock screen, with your help'; Run = 'Invoke-LockTileStep' }
    @{ Name = 'unlock'; Minutes = 8; Title = 'the unlock paths, with your help'; Run = 'Invoke-UnlockStep' }
    @{ Name = 'activation'; Minutes = 12; Title = 'the shortcut, the button, the tile and the notification, with your help'; Run = 'Invoke-ActivationStep' }
    @{ Name = 'assistant'; Minutes = 11; Title = 'the assistant gesture and the Galaxy Buds gesture, with your help'; Run = 'Invoke-AssistantStep' }
    @{ Name = 'earbuds'; Minutes = 8; Title = 'the earbud microphone, with your earbuds'; Run = 'Invoke-EarbudStep' }
    @{ Name = 'recordings'; Minutes = 30; Title = 'your recordings of the voice corpus'; Run = 'Invoke-RecordingStep' }
    @{ Name = 'corpus'; Minutes = 43; Title = 'the corpus''s measurements on this phone, about 420 clips; you may leave'; Run = 'Invoke-CorpusStep' }
    @{ Name = 'voice'; Minutes = 33; Title = 'talking to Kestrel, and the echo filter'; Run = 'Invoke-VoiceStep' }
    @{ Name = 'memory'; Minutes = 14; Title = 'memory, the times of starts and closes, also with Kokoro, "Try it", and a cold start through the tile'; Run = 'Invoke-MemoryStep' }
    @{ Name = 'room'; Minutes = 60; Title = 'an hour of room audio with the battery test; you may leave once it runs'; Run = 'Invoke-RoomStep' }
)

$completed = $false
$stopped = $false
Add-Result session "started on $(if ($isEmulator) { 'kestrel-api37' } else { 'the Galaxy A71' })$(if ($DryRun) { ', as a dry run' })"
try {
    foreach ($entry in $plan) {
        if ($Step -and $entry.Name -notin $Step) { continue }
        if ((Request-Owner "Step $($entry.Name), about $($entry.Minutes) minutes: $($entry.Title). Press Enter to run it, s to skip it, or q to stop the session." 'go') -eq 'skip') {
            Add-Result $entry.Name 'skipped by the owner'
            continue
        }
        Initialize-Session
        try {
            & $entry.Run
        } catch {
            if ($_.Exception.Message -eq 'The owner stopped the session.') { throw }
            # The scripts' and hooks' errors name only steps, files and classes, never what Kestrel heard.
            Add-Result $entry.Name "failed: $($_.Exception.Message)"
        }
    }
    $completed = $true
} catch {
    $stopped = $true
    Add-Result session "stopped: $($_.Exception.Message)"
} finally {
    try {
        # The restores that are left, the newest first, so that the screen, the clips, the hooks' tasks and Kestrel's mode
        # come last.
        $left = @($pending.ToArray())
        [array]::Reverse($left)
        Invoke-Restore $left
    } finally {
        if (-not ($completed -or $stopped)) { Add-SessionResult -Path $resultsFile -Step session -Text 'stopped, as by Ctrl+C' }
        if ($DryRun -and (Test-Path -LiteralPath $clipsFolder)) { Remove-Item -LiteralPath $clipsFolder -Recurse -Force }
        if ($earbudsChanged) { Write-Host 'If you have not done it yet, turn "Use the earbud microphone" off in Kestrel''s settings, unless you want to keep it.' }
        # The restores of battery-rules, reboots, activation, assistant and memory can leave Kestrel's notices.
        if ($prepared -and -not $DryRun) { Write-Host 'Kestrel''s notices, such as that its accessibility service is off or that Android stopped its listening, may still be in the notification shade; swipe them away.' }
        foreach ($restore in $pending) { Write-Warning "Could not put back $($restore.What) on $Serial ($($restore.Reason)). $($restore.ByHand)" }
        # install.ps1 and voice-test.ps1 build through Gradle, which keeps a daemon running.
        if ($gradleUsed) { & "$PSScriptRoot\gradle.ps1" --stop | Out-Null }
        Write-Host "The results are in $((Resolve-Path -LiteralPath $resultsFile).Path)."
        # After a stop, such as Ctrl+C, PowerShell would end the script with exit code 0. pwsh -File ends a stopped script
        # that declares parameters with exit code 0, even after 'exit 1', unless the script gives the host its exit code;
        # run from a prompt, that would close the window, so the script does it only when nothing called it.
        if (-not ($completed -or $stopped)) {
            if (@(Get-PSCallStack).Count -eq 1) { $Host.SetShouldExit(1) }
            exit 1
        }
    }
}
if ($stopped -or $pending.Count -gt 0) { exit 1 }
