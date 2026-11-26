# The second session on the Galaxy A71 (plan 2E, made automatic by plan 4D): the checks of sub-project 2 that need the A71,
# with the measures that the session of 2026-11-17 lacked. Every time comes from the phone: Kestrel records when it was
# started, opened its session, heard the end of speech, reacted and turned itself off, and the script reads these times
# through the hook voice-times; no measure counts from this computer's clock or from an Enter. Speech comes only from the
# corpus's generated clips, which this computer's speakers play next to the phone once the script has set their loudness
# at the phone (hook H7, voice-route's frameRms), never from the owner's voice, and every other event (the shortcut, the
# button, the tile, a tap) comes from adb; Android's dumps and Kestrel's hooks check the result (process rule 12). The
# owner is needed for one item only, right after the install: he lays his Galaxy Buds next to this computer's speaker for
# the step earbuds, and puts them back into their case when the script beeps. It has one prompt, which beeps and says what
# he does and what the phone shows, and the script then says that he may leave. After listen-check's restart,
# listen-check.ps1 unlocks the A71 with the owner's test PIN, which he allows the sessions to use; this script checks at
# its start, on the A71 only, that corpus\owner\a71-config.json has it (the key "pin"), and stops with a clear message
# otherwise. The steps' estimates add up to 4 hours 18 minutes, of which about 2 minutes need the owner's hands, and he
# waits near the phone for about 5 more while the step earbuds runs. The steps:
#   install       installs Kestrel's debug build, copies the speech models with the Kokoro voice and Kokoro's int8 model,
#                 which only a voice device test loads, installs the fixture app dev.kestrel.testapp, and grants Kestrel
#                 the microphone and notification permissions, which stay granted (10 minutes)
#   earbuds       with the owner's Galaxy Buds next to the speaker and "Use the earbud microphone" on: the route of the
#                 recording (the hook voice-route), the communication device while the fixture app's player plays, so
#                 whether music then sounds like a call, "Kestrel" from the speaker next to the earbuds with the time from
#                 the end of the speech to the wait for a task, and the phone's microphone after Bluetooth went off and on
#                 again (8 minutes)
#   voice-tests   the voice device tests that may run on a phone (scripts\voice-test.ps1), among them
#                 ListeningServiceTest, KeywordTuningTest and KokoroModelsTest; the tests that did not pass are recorded by
#                 name (40 minutes)
#   failures      Kestrel's logged failures since the voice tests, by their fixed descriptions and classes, before
#                 listen-check, whose notification check makes Android refuse a start on purpose (1 minute)
#   listen-check  first whether 'dumpsys accessibility' ends with a line break (only its last byte), then
#                 scripts\listen-check.ps1 -Reboot, which prints one line for each check and unlocks the A71 with the test
#                 PIN after the restart; with Kokoro chosen and its speed not stored, one untimed Kokoro start comes first
#                 (12 minutes)
#   battery-rules without and with the battery restriction and without Kestrel's accessibility service: Android's record
#                 of the listening service, Kestrel's own view of it, when Kestrel turned itself off, every 5 s for 3
#                 minutes, timed on the phone, and the kinds of Kestrel's notices (the hook notices) (10 minutes)
#   activation    the accessibility shortcut ('input keycombination' of both volume keys), the accessibility button and
#                 the tile ('input tap' at their bounds, 'cmd statusbar click-tile') with Kestrel in the background: the
#                 time from the event to listening, timed on the phone, and whether a dialog took the focus (8 minutes)
#   assistant     with only the assistant role and Kestrel in the background: a start through the hook, "Kestrel, open
#                 settings" through the hook and from the speaker, with the time from Kestrel's request to its settings
#                 in front, and the restore after Android ended Kestrel's process; it waits until 2 minutes have passed
#                 since the session's last crash of Kestrel, which it keeps in corpus\owner\session-state.txt (5 minutes)
#   spotter       for each of three settings of the keyword spotter: "Hey Kestrel" 5 times from the speaker, in the
#                 Kokoro and the Piper voice by turns, and about 1.5 minutes of generated speech without a keyword while
#                 Kestrel is paused; the hits and false hits are counted on the phone, the script prints the setting that
#                 its rule keeps (Select-SpotterWinner), and the default comes back at the end (16 minutes)
#   voice         "Kestrel", "Hey Kestrel", "Kestrel, wake up" while paused, interruptions with pauses of 1, 1.5 and 2 s,
#                 which this computer times after each clip has ended, and "Stop Kestrel", all from the speaker: per try,
#                 the spotter's hits by keyword, the transcribed and named utterances, how each was read, and the time from
#                 the end of the speech to Kestrel's reaction, all from the phone (20 minutes)
#   barge-in      Kestrel's own voice at its microphone against this computer's "Kestrel" (plan 4E): the spotter's
#                 default setting again; the gains for "Kestrel" at -24, -18 and -12 dBFS at the phone, measured with
#                 voice-record while Kestrel is off; then, with the assistant volume (stream 11), which Kestrel's replies
#                 use, at the owner's index and at 2, 3 and 4 below it (at least 1), each read back, for each level, 4
#                 tries in which Kestrel reads a long reply, the phone reads the level of Kestrel's own voice (voice-route's
#                 frameRms) and the speaker plays "Kestrel"; then 3 replies to their end at the owner's index and the
#                 commands that started after them; and the decision that plan 4E's Task 5 follows (Get-BargeInDecision);
#                 the assistant volume comes back at the end (20 minutes)
#   memory        three starts and closes with the chosen voice and with Kokoro, with the peak PSS and the memory
#                 categories, and a cold start through the tile, which the script taps itself (12 minutes)
#   battery       three measures of 30 minutes, each timed by batterystats: a quiet room, the quiet room with one generated
#                 task from the speaker every 5 minutes, and the room-sound track of scripts\make-room-sound.ps1 at the
#                 level of a television 2 to 3 m away; with the breakdown of Kestrel's use, the audio that it transcribed,
#                 its utterances, hits and instant commands, and the model "% per hour = baseline + cost per transcribed
#                 minute x minutes"; a measure stops early when a call command starts (95 minutes)
#   failures-late Kestrel's logged failures from the end of listen-check to the end of the session (1 minute)
# The results go into corpus\owner\session-<time>.txt; git ignores it. Neither it nor the console ever hold what Kestrel
# heard: the hooks answer only counts, times, kinds and device types. Once the first step runs, the phone's screen stays
# on while the phone is plugged in. The script puts back what it changes on the phone, also when a step fails or Ctrl+C
# stops it: Kestrel's mode Off, the hooks' slow task, the keyword spotter's setting, the setting "Stay awake", the
# assistant role and its settings, Kestrel's battery restriction (as allow or ignore, never as default, which Android
# counts as restricted), Kestrel's voice, the accessibility settings, Quick Settings' tiles, Bluetooth, the assistant
# volume (stream 11), the screen's timeout, the battery's state, the fixture app when it was not installed before, and,
# through the debug hooks owner-options and notices, the setting "Use the earbud microphone" and Kestrel's notices about
# problems. It cannot put back the battery statistics, which the battery measures reset; every warning at its end says
# how to put a thing back by hand. It refuses every device but the A71 and kestrel-api37. -Unattended runs without the
# owner and skips the step earbuds. -DryRun runs the session on kestrel-api37 without the owner: it
# sends each clip's text through the voice hook in place of the speaker, plays no sound, runs listen-check.ps1 without
# -Reboot, skips voice-test.ps1 and the earbuds, shortens the waits and the battery measures to a minute, and turns the
# emulator's host audio input off, so that this computer's microphone reaches no step, and back as the emulator started
# with it at its end. -Step runs only the named steps, also as one text separated by commas.
# It needs PowerShell 7.3 or newer, as scripts\hook.ps1 does. After Ctrl+C, it ends with exit code 1.
# Example: scripts\a71-session.ps1 -Serial R58N12345AB
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [switch]$DryRun,
    [switch]$Unattended,
    [string[]]$Step
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
. "$PSScriptRoot\lib\Corpus.ps1"
. "$PSScriptRoot\lib\Session.ps1"
. "$PSScriptRoot\lib\Speaker.ps1"
. "$PSScriptRoot\lib\Oracle.ps1"
Use-KestrelToolchain

$stepNames = @('install', 'earbuds', 'voice-tests', 'failures', 'listen-check', 'battery-rules', 'activation', 'assistant',
    'spotter', 'voice', 'barge-in', 'memory', 'battery', 'failures-late')
# pwsh -File passes '-Step voice,battery' as one text, so the names are split at commas.
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
$corpusFile = Join-Path $PSScriptRoot '..\corpus\commands.txt'
$generatedRoot = Join-Path $PSScriptRoot '..\corpus\generated'
$generatedClips = Join-Path $generatedRoot 'piper'
# The lines of the corpus and of the check corpus, by id, for the clips that the speaker plays.
$clipRows = @{}
foreach ($row in @(Read-Corpus -Path $corpusFile) + @(Read-Corpus -Path (Join-Path $PSScriptRoot '..\corpus\checks.txt'))) { $clipRows[$row.Id] = $row }
$config = Read-A71Config (Join-Path $PSScriptRoot '..\corpus\owner\a71-config.json')
$testApp = 'dev.kestrel.testapp'
# The gain at which this computer's clips reach the phone at the owner's target, once Initialize-Air has measured it.
$gain = $null
$service = $KestrelAccessibilityService
$assistantRole = 'android.app.role.ASSISTANT'
$gradleUsed = $false
$prepared = $false
# The device's time, as logcat -T reads it, when the session's first step began and when listen-check ended.
$sessionStart = $null
$afterListenCheck = $null
# The shorter waits of a dry run.
$minuteScale = if ($DryRun) { 1 / 30 } else { 1 }

# One item of the owner's: this computer beeps, and the prompt says what he does and what the phone shows; Enter starts
# it, s skips it and q stops the session. A dry run answers $DryRunAnswer itself, and an unattended run skips it.
function Request-OwnerItem([string]$Prompt, [string]$DryRunAnswer = 'go') {
    if ($DryRun) {
        Write-Host "[dry run] $Prompt"
        return $DryRunAnswer
    }
    if ($Unattended) {
        Write-Host "[unattended] $Prompt"
        return 'skip'
    }
    Invoke-Beep
    $answer = "$(Read-Host $Prompt)".Trim().ToLowerInvariant()
    if ($answer -eq 'q') { throw 'The owner stopped the session.' }
    if ($answer -in 's', 'skip') { 'skip' } else { 'go' }
}

function Invoke-Beep { try { [console]::Beep(880, 300) } catch { Write-Verbose 'This console cannot beep.' } }

# Refuses every device but the A71 and kestrel-api37; the daily phone would need -AllowDailyPhone, which this script
# never passes.
Assert-TestDevice -Serial $Serial
$isEmulator = $KestrelTestDevices[$Serial].Pattern -eq 'kestrel-api37'
if ($DryRun -and -not $isEmulator) { throw 'A dry run runs only on the emulator kestrel-api37.' }
$adb = Get-Adb
# The script and listen-check.ps1 unlock the A71 with the owner's test PIN; a missing file or key stops the session here,
# before any step. Only Unlock-TestPhone and Send-TestPin read the PIN, when they type it.
$pinConfig = if ($isEmulator) { $null } else { Join-Path $owner 'a71-config.json' }
if ($pinConfig) { Send-TestPin -ConfigPath $pinConfig -CheckOnly }

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

# In a dry run, sends what the speaker would play, as if Kestrel had heard it, with a pause after each text; a text that
# starts with '+' comes with the keyword spotter's "Kestrel".
function Send-Heard([string[]]$Texts) {
    foreach ($text in $Texts) {
        Invoke-Hook voice @{ Send = 'heard'; Text = $text.TrimStart('+'); Named = $text.StartsWith('+') } | Out-Null
        Start-Sleep -Seconds 2
    }
}

# Plays the clip of a line of corpus\commands.txt or corpus\checks.txt in $Voice through this computer's speakers, at the
# gain that Initialize-Air measured, and returns when it has ended. A dry run sends the line's text through the voice
# hook instead (Send-Heard), with the keyword spotter's "Kestrel" for a line that starts with it.
function Invoke-CorpusClip([string]$Id, [string]$Voice = 'kokoro') {
    $row = $clipRows[$Id]
    if (-not $row) { throw "No line $Id in corpus\commands.txt or corpus\checks.txt." }
    if ($DryRun) {
        Send-Heard @("$(if ($row.Spotted) { '+' })$($row.Text)")
        return
    }
    Invoke-Clip -Path (Join-Path $generatedRoot "$Voice\$Id.wav") -Gain $gain | Out-Null
}

# Before the first clip from the speaker: the clips must be there and must reach the phone loudly enough (plan 4D,
# decision 4). Kestrel is paused, so that the calibration sentence, a conversation line without a keyword, starts nothing,
# and voice-route's loudest 20 ms frame (frameRms) is read around each play (Set-SpeakerLevel). Returns whether the
# speaker may play; the results say the level either way. A dry run plays nothing and needs no level.
function Initialize-Air([string]$StepName) {
    if ($DryRun -or $null -ne $gain) { return $true }
    foreach ($file in 'kokoro\c041.wav', 'piper\c041.wav', 'kokoro\k022.wav', 'piper\k022.wav') {
        if (-not (Test-Path -LiteralPath (Join-Path $generatedRoot $file))) {
            Add-Result $StepName "skipped: corpus\generated has no $file; make the clips with scripts\make-corpus.ps1, also with -List checks, on the emulator"
            return $false
        }
    }
    $row = @(Read-Corpus -Path $corpusFile | Where-Object { $_.Group -eq 'conversation' -and $_.Text -notmatch '(?i)kestrel|stop|cancel' }) | Select-Object -First 1
    $script:calibration = Join-Path $generatedRoot "kokoro\$($row.Id).wav"
    Start-Kestrel
    Invoke-Hook voice @{ Send = 'pause' } | Out-Null
    Wait-Mode paused 10 | Out-Null
    try {
        $level = Set-SpeakerLevel -TargetDb $config.SpeakerTargetDb -Measure {
            param($Gain)
            Invoke-Hook voice-route | Out-Null
            Invoke-Clip -Path $script:calibration -Gain $Gain | Out-Null
            $value = (ConvertFrom-HookPairs (Invoke-Hook voice-route))['frameRms']
            if ($value -match '^-?\d+(\.\d+)?$') { [double]::Parse($value, [Globalization.CultureInfo]::InvariantCulture) } else { $null }
        }
    } finally {
        Invoke-Hook voice @{ Send = 'resume' } | Out-Null
        Wait-Mode listening 10 | Out-Null
    }
    $heard = if ($null -eq $level.LevelDb) { 'nothing' } else { "$($level.LevelDb) dBFS" }
    Add-Result $StepName "this computer's clips reach the phone at $heard (target $($config.SpeakerTargetDb) dBFS, within 3 dB) with a gain of $([math]::Round($level.Gain, 2))"
    if (-not $level.InBand -and ($null -eq $level.LevelDb -or $level.LevelDb -lt $config.SpeakerTargetDb - 3)) {
        Add-Result $StepName 'skipped: the phone does not hear this computer loudly enough; turn up its volume and put its speakers next to the phone'
        return $false
    }
    $script:gain = $level.Gain
    $true
}

# Kestrel's state from the voice-state hook, or $null while Kestrel does not answer.
function Get-VoiceState {
    try { ConvertFrom-VoiceState (Invoke-Hook voice-state) } catch { $null }
}

# The counts of voice-counts (waits, tasks, lastTaskWords, hits, and the hits by keyword, the utterances and the reads
# where they are above 0), and the phone's own times of voice-times; see scripts\hook.ps1.
function Get-Counts { ConvertFrom-HookPairs (Invoke-Hook voice-counts) }

function Get-Times { ConvertFrom-HookPairs (Invoke-Hook voice-times) }

# The device's time since boot in milliseconds, on the clock of Kestrel's times.
function Get-DeviceUptime { ConvertFrom-Uptime ((Invoke-Checked 'cat /proc/uptime') -join ' ') }

# The device's time as logcat -T reads it.
function Get-DeviceTime { ((Invoke-Checked "date '+%m-%d %H:%M:%S.000'") -join '').Trim() }

# Waits until $Condition is true for Kestrel's state, which it gets as its argument. Returns the seconds that it waited,
# or -1 after the timeout. The seconds only bound a wait; no measure uses them.
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

function Format-Wait([double]$Seconds) { if ($Seconds -lt 0) { 'failed' } else { 'ok' } }

# Whether Kestrel's accessibility service is on now, for the lines of the battery and memory measures, because its own
# work counts in Kestrel's use (research r10, gap 6).
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

# Kestrel records only while the screen is on and the phone unlocked, and a run without the owner can start, or go on
# after another script put "Stay awake" back, with the phone locked. So before each step the script wakes the phone and,
# when it is locked, unlocks it: the emulator with 'wm dismiss-keyguard', the A71 with the owner's test PIN
# (Unlock-TestPhone, which types it only when Android says clearly that the PIN pad shows). An unlock that did not work
# stops every later unlock of the session, and the session skips its later steps, which all need an unlocked phone; the
# result line says whether the phone did not show its PIN pad, so that nothing was typed, or the unlock failed, never
# with the PIN. Returns whether the phone is unlocked and awake.
function Confirm-Unlocked {
    if ($script:unlockFailed) { return $false }
    $ready = { (Test-KeyguardShowing @(Invoke-Checked 'dumpsys activity activities')) -eq $false -and (Get-Wakefulness @(Invoke-Checked 'dumpsys power')) -eq 'Awake' }
    if (& $ready) { return $true }
    $why = 'the phone stayed locked'
    $unlocked = if ($isEmulator) {
        Invoke-Checked 'input keyevent KEYCODE_WAKEUP' | Out-Null
        Invoke-Checked 'wm dismiss-keyguard' | Out-Null
        Start-Sleep -Seconds 2
        $true
    } else {
        # $false: it typed nothing; an error: the PIN did not unlock the phone ("the unlock failed"), or the file lacks it.
        try {
            $typed = Unlock-TestPhone -Serial $Serial -ConfigPath $pinConfig
            if (-not $typed) { $why = 'the phone did not show its PIN pad clearly, so the script typed nothing' }
            $typed
        } catch {
            $why = "$($_.Exception.Message)"
            $false
        }
    }
    $unlocked = $unlocked -and (& $ready)
    if (-not $unlocked) {
        $script:unlockFailed = $true
        Add-Result session "$why; the session tries no more unlocks and skips its later steps"
    }
    $unlocked
}

# Kestrel listens, says nothing and waits for no task, so that a try starts from the same state each time.
function Wait-Quiet {
    $state = Get-VoiceState
    if (-not $state -or $state.mode -ne 'listening') { Start-Kestrel }
    # "Kestrel, stop" ends a reply, cancels a task and ends a wait for a task (design spec, section 8.4). The hook's
    # transcript does not pass the session's pipeline, so the counts of the tries do not see it.
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

# Kestrel's latest reaction from voice-times, if it came after $Since (milliseconds on the phone's clock): 'wait 640 ms
# after the end of speech', or $null.
function Get-Reaction([long]$Since, [string]$Kind = '') {
    $mark = ConvertFrom-TimeMark -Text ((Get-Times)['reacted'])
    if (-not $mark -or $mark.At -le $Since -or ($Kind -and $mark.Kind -ne $Kind)) { return $null }
    # The latency counts from the latest transcribed speech, whatever caused the reaction, so it counts only when that
    # speech ended in this try.
    $after = if ($null -ne $mark.SinceSpeech -and $mark.At - $mark.SinceSpeech -gt $Since) { "$($mark.SinceSpeech) ms after the end of the speech" } else { 'with no transcribed speech in this try' }
    "$($mark.Kind) $after"
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
# records only while the screen is on and the phone unlocked. Registered first, so that they come last.
function Initialize-Session {
    if ($script:prepared) { return }
    $script:prepared = $true
    # First of all, so that it comes last, after the restores whose changes make Kestrel post its notices about problems,
    # such as that its accessibility service is off: the script takes them away through the debug hook notices.
    Register-Restore 'Kestrel''s notices in the notification shade' 'Pull down the notification shade and swipe away Kestrel''s notices, such as that its accessibility service is off, that Android stopped its listening, or "Open Kestrel''s settings".' $null {
        Assert-TestDevice -Serial $Serial
        Invoke-Hook notices @{ Clear = $true } | Out-Null
        # A notice that Kestrel posts a moment after the restores before this one, as when its accessibility service went off.
        Start-Sleep -Seconds 2
        $left = (ConvertFrom-HookPairs (Invoke-Hook notices @{ Clear = $true }))['notices']
        if ($left -ne '0') { throw "$left of them are still in the shade" }
    } | Out-Null
    Register-Restore "Kestrel's mode Off" "Run: scripts\hook.ps1 -Serial $Serial voice -Send off" $null { Stop-Kestrel } | Out-Null
    Register-Restore "the hooks' tasks" "Run: scripts\hook.ps1 -Serial $Serial voice-task -Clear" $null {
        Invoke-Hook voice-task @{ Clear = $true } | Out-Null
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
    if ($DryRun) {
        # The emulator's microphone carries this computer's sound, which would count in the steps' measures and could
        # start commands, so a dry run turns the host audio input off. The restore puts back the state that the emulator
        # started with (Get-EmulatorHostMicrophone): off without a window, as scripts\start-emulator.ps1 starts it.
        Disable-EmulatorHostMicrophone -Serial $Serial | Out-Null
    }
    $script:sessionStart = Get-DeviceTime
}

# Installs the fixture app dev.kestrel.testapp through scripts\install.ps1 when it is not installed, and registers its
# removal; an app that was installed before stays.
function Initialize-TestApp {
    if (@(Invoke-Checked "pm list packages $testApp") -contains "package:$testApp") { return }
    Register-Restore 'the removal of the fixture app' "Run: & '$adb' -s $Serial uninstall $testApp" $null {
        Assert-TestDevice -Serial $Serial
        if (@(Invoke-Checked "pm list packages $testApp") -contains "package:$testApp") { Invoke-Checked "pm uninstall $testApp" | Out-Null }
    } | Out-Null
    $script:gradleUsed = $true
    & "$PSScriptRoot\install.ps1" -Serial $Serial -TestApp
    if ($LASTEXITCODE -ne 0) { throw 'scripts\install.ps1 -TestApp failed.' }
}

# Runs a command on the device once, for a command that a repeat would do twice, such as 'am crash': Android does not
# restart an app that crashed twice within 2 minutes. The first echo gives the marker a line of its own, also after
# output without a final line break (Invoke-SettingsCommand). It runs only on the device that Assert-TestDevice accepted.
function Invoke-Once([string]$Command) {
    $ErrorActionPreference = 'Continue'
    $output = @(& $adb -s $Serial shell (Format-TestDeviceCommand -Serial $Serial -Command "$Command && echo && echo kestrel-end") 2>&1)
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

# The command that enables the accessibility services $Services, or deletes the setting when there are none. The list is
# quoted for the device's shell.
function Get-ServicesCommand([string[]]$Services) {
    if ($Services) { "settings put secure enabled_accessibility_services '$($Services -join ':')'" } else { 'settings delete secure enabled_accessibility_services' }
}

function Get-KestrelPss { Get-TotalPss -Dump @(Invoke-Checked 'dumpsys meminfo dev.kestrel') }

function Get-KestrelMemory { Format-MemoryCategories (Get-MemoryCategories -Dump @(Invoke-Checked 'dumpsys meminfo dev.kestrel')) }

function Get-KestrelProcess { ((Invoke-Checked 'pidof dev.kestrel || true') -join '').Trim() }

# The answer of the hook voice-choice: the stored voice, whether Kokoro was chosen and Piper spoke instead the last time
# that a voice session opened because Kokoro did not load, Kokoro's real-time factor on the phone when Kestrel measured
# it above 1, and the factor that Kestrel measured. -Voice kokoro forgets the measure.
function Get-VoiceChoice([hashtable]$With = @{}) {
    $text = Invoke-Hook voice-choice $With
    if ($text -notmatch '^voice=(\w+) speed=([\d.]+) kokoroFailed=(true|false) kokoroSlow=([\d.]+|none) kokoroSpeed=([\d.]+|none)$') { throw 'Unexpected answer of voice-choice.' }
    [pscustomobject]@{ Voice = $Matches[1]; KokoroFailed = $Matches[3] -eq 'true'; KokoroSlow = $Matches[4]; KokoroSpeed = $Matches[5] }
}

# Before a step times Kestrel's start with Kokoro (the controller's decision after the review of plan 2E's Task 8): the
# first start with Kokoro after an install or a choice of Kokoro measures Kokoro's speed for about 10 s on the A71 before
# its session opens, so one untimed start and turn-off comes first while that measure is not stored, and the step
# records voice-choice's kokoroSpeed= after it, which shows that the measure was stored. When Kokoro did not load, no
# measure is stored and Piper speaks, so the later steps of this run do not try again.
$kokoroFailedToLoad = $false
function Initialize-KokoroSpeed([string]$StepName) {
    if ($script:kokoroFailedToLoad) { return }
    $choice = Get-VoiceChoice
    if ($choice.Voice -ne 'kokoro' -or $choice.KokoroSpeed -ne 'none') { return }
    Start-Kestrel
    Stop-Kestrel
    $after = Get-VoiceChoice
    if ($after.KokoroSpeed -eq 'none') {
        $script:kokoroFailedToLoad = $true
        Add-Result $StepName "an untimed start with Kokoro first: Kokoro did not load (kokoroFailed=$($after.KokoroFailed.ToString().ToLowerInvariant())), so its speed is not stored, Piper speaks in the timed starts, and the later steps do not try again"
        return
    }
    Add-Result $StepName "an untimed start with Kokoro first, so that the timed starts do not include the measure of Kokoro's speed: kokoroSpeed=$($after.KokoroSpeed)"
}

# A tap on Kestrel's tile: the tile goes into Quick Settings through SystemUI once, with a restore, and 'cmd statusbar
# click-tile' taps it while Quick Settings is open, as listen-check.ps1's tile check does (map S19 and S38).
$tileComponent = 'dev.kestrel/dev.kestrel.app.listen.KestrelTileService'
$tileRestore = $null
function Add-Tile {
    if ($script:tileRestore -or (Get-SecureSetting -Serial $Serial -Name sysui_qs_tiles) -match 'KestrelTileService') { return }
    # SystemUI removes only a tile that it has made, so the restore removes the tile that this run added.
    $script:tileRestore = Register-Restore "Quick Settings' tiles" "Run: & '$adb' -s $Serial shell cmd statusbar remove-tile $tileComponent" $null {
        Assert-TestDevice -Serial $Serial
        Invoke-Checked "cmd statusbar remove-tile $tileComponent" | Out-Null
    }
    Invoke-Checked "cmd statusbar add-tile $tileComponent" | Out-Null
}

function Invoke-TileTap {
    Add-Tile
    Invoke-Checked 'cmd statusbar expand-settings' | Out-Null
    # SystemUI makes and binds the tile a moment after it lists it, and a tap before that is lost.
    Start-Sleep -Seconds 5
    Invoke-Once "cmd statusbar click-tile $tileComponent"
    Start-Sleep -Seconds 1
    Invoke-Checked 'cmd statusbar collapse' | Out-Null
}

function Invoke-InstallStep {
    # On Android 13 an update ends Kestrel's process with the reason of the owner's stop, so Kestrel would stay off after it.
    try { Stop-Kestrel } catch { Write-Host 'Kestrel did not answer, so it is probably not installed yet.' }
    $script:gradleUsed = $true
    & "$PSScriptRoot\install.ps1" -Serial $Serial
    if ($LASTEXITCODE -ne 0) { throw 'scripts\install.ps1 failed.' }
    # With Kokoro's fp32 model, which Kestrel uses, and its int8 model, which only KokoroModelsTest loads. After
    # a71-commands.ps1's install, only these are new.
    & "$PSScriptRoot\push-models.ps1" -Serial $Serial -Kokoro
    # After a new install Kestrel has neither permission, and without the microphone it turns itself off at the start
    # (design spec, section 14.6). Both stay granted after the session, as after scripts\voice-test.ps1.
    Invoke-Checked 'pm grant dev.kestrel android.permission.RECORD_AUDIO' | Out-Null
    Invoke-Checked 'pm grant dev.kestrel android.permission.POST_NOTIFICATIONS' | Out-Null
    Initialize-TestApp
    Add-Result install 'Kestrel installed, the speech models copied with both of Kokoro''s models, the fixture app installed, and the microphone and notification permissions granted'
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
    foreach ($class in 'ListeningServiceTest', 'KeywordTuningTest', 'KokoroModelsTest', 'SynthesizerTest') {
        Add-Result voice-tests "${class}: $(if (Test-ClassPassed -Folder $results -Class $class) { 'passed' } else { 'did not pass' })"
    }
    # connected-test.ps1 ends with 'am force-stop', which leaves a record of the owner's stop, so a restore keeps Kestrel
    # off; the session starts it itself.
    Start-KestrelAfterTests
    Stop-Kestrel
}

# Starts Kestrel after device tests, which end with 'am force-stop': first its main activity, because Android may give a
# stopped app no hook broadcast, and Start-Kestrel begins with a hook.
function Start-KestrelAfterTests {
    Invoke-Checked 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
    Start-Kestrel
}

# Kestrel's logged failures in the device's log since $Since, a time as logcat -T reads it, or in the whole log.
function Add-Failures([string]$StepName, [string]$Since) {
    $command = if ($Since) { "logcat -d -T '$Since' -s Kestrel:W" } else { 'logcat -d -s Kestrel:W' }
    $failures = @(Get-FailureDescriptions -Log @(Invoke-Checked $command))
    if ($failures.Count -eq 0) {
        Add-Result $StepName 'Kestrel logged no failure in this part of the device''s log'
        return
    }
    foreach ($failure in $failures) { Add-Result $StepName "$($failure.Count) x ""$($failure.What) failed"" ($($failure.Class))" }
}

# Right after the voice tests, whose script cleared the log at its start, and before listen-check, whose notification
# check makes Android refuse a start in the background on purpose (analysis, item 4).
function Invoke-FailuresStep { Add-Failures failures $null }

# From the end of listen-check, or from the session's start when listen-check did not run in this run.
function Invoke-LateFailuresStep {
    $since = if ($script:afterListenCheck) { $script:afterListenCheck } else { $script:sessionStart }
    Add-Failures failures-late $since
}

function Invoke-ListenCheckStep {
    # Only the last byte of the dump: whether One UI's 'dumpsys accessibility' ends without a line break (analysis,
    # item 3). No text of the dump is read.
    $ends = Test-EndsWithLineBreak -Answer @(Invoke-Checked 'dumpsys accessibility | tail -c 1 | od -An -tx1')
    Add-Result listen-check "dumpsys accessibility ends with a line break: $(if ($null -eq $ends) { 'no byte read' } elseif ($ends) { 'yes' } else { 'no' })"
    # The notification check reads One UI's shade with uiautomator dump, which also holds the owner's notifications;
    # listen-check.ps1 deletes the dump after each read and never prints it. After the restart it unlocks the A71 with the
    # owner's test PIN itself.
    # listen-check.ps1's restart, role and reboot checks time Kestrel's starts.
    Initialize-KokoroSpeed listen-check
    $lines = @(& "$PSScriptRoot\listen-check.ps1" -Serial $Serial -Reboot:(-not $DryRun) *>&1 | ForEach-Object { $text = "$_"; Write-Host $text; $text })
    $code = $LASTEXITCODE
    # listen-check's restart and role checks end Kestrel's process with 'am crash'; its end is no earlier than the last.
    Write-SessionCrash -Path $stateFile -Serial $Serial -Time (Get-Date)
    $script:afterListenCheck = Get-DeviceTime
    # Each check's line holds its name, ok, failed or skipped, and what it measured on the device.
    foreach ($line in $lines | Where-Object { $_ -match '^[a-z]+\s+(ok|failed|skipped)\b' }) { Add-Result listen-check $line }
    if ($code -ne 0) { Add-Result listen-check "ended with exit code $code" }
    # listen-check turns Kestrel's accessibility service off and on, which can leave Kestrel's notice that it is off; the
    # script takes it away itself, and the session's last restore tries again when Kestrel does not answer now.
    try {
        Invoke-Hook notices @{ Clear = $true } | Out-Null
    } catch {
        Add-Result listen-check 'Kestrel''s notices: not taken away yet, because Kestrel did not answer; the session tries again at its end'
    }
}

# What Android 13 and One UI do to Kestrel's microphone service in the background after its accessibility service is
# turned off, without and with the battery restriction (analysis, items 1 and 2): Android's record of the service, which
# every version prints as 'isForeground=...', Kestrel's own view of it through Android's list of its running services,
# and when Kestrel turned itself off and why. The times come from the phone: the device's uptime when the service went
# off, and Kestrel's own time of its turn-off.
function Invoke-BatteryRulesStep {
    # Android keeps Kestrel's assistant service bound while Kestrel holds the assistant role, which most likely keeps
    # Kestrel's process from being demoted, as the accessibility service does (analysis, item 2). So when Kestrel holds the
    # role, the step takes it away for its measure and gives it back at its end, as the step assistant does.
    $roleHeld = @(Get-AssistantHolders) -contains 'dev.kestrel'
    $roleRestore = if ($roleHeld) { Register-AssistantRestore }
    $roleText = if ($roleHeld) { 'Kestrel held the assistant role, and the step took it away for its measure' } else { 'Kestrel did not hold the assistant role, as the measure needs' }
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
        # Inside the try, after all restores are registered, so that a failure gives the role back at the step's end.
        if ($roleHeld) {
            Invoke-Checked "cmd role remove-role-holder $assistantRole dev.kestrel" | Out-Null
            if (@(Get-AssistantHolders) -contains 'dev.kestrel') {
                Add-Result battery-rules 'failed: Kestrel holds the assistant role, and Android did not take it away'
                return
            }
        }
        $others = @(Get-OtherServices $accessibilityRestore.Data)
        $seconds = [int](180 * $minuteScale)
        foreach ($restricted in $false, $true) {
            $label = if ($restricted) { 'with the battery restriction' } else { 'without the battery restriction' }
            Invoke-Checked "cmd appops set dev.kestrel RUN_ANY_IN_BACKGROUND $(if ($restricted) { 'ignore' } else { 'allow' })" | Out-Null
            # Waits until Android has bound the service, also after a stop or a crash of Kestrel, which leave it crashed.
            if (-not (Connect-AccessibilityService -Serial $Serial -Services ((@($others) + $service) -join ':'))) { throw "Android did not bind Kestrel's accessibility service (see the warning above)." }
            Invoke-Checked 'settings put secure accessibility_enabled 1' | Out-Null
            Start-Kestrel
            $start = Get-ForegroundRecord -Dump @(Invoke-Checked 'dumpsys activity services dev.kestrel')
            Invoke-Checked (Get-ServicesCommand $others) | Out-Null
            $off = Get-DeviceUptime
            Write-Host "Kestrel listens in the background, $label, and its accessibility service is off now. Do not touch the phone for $([math]::Round($seconds / 60, 1)) minutes: it stays unlocked with the home screen on. The script checks every 5 s by itself."
            $lost = $null
            $record = $start
            $view = $null
            $watch = [Diagnostics.Stopwatch]::StartNew()
            while ($watch.Elapsed.TotalSeconds -lt $seconds) {
                Start-Sleep -Seconds 5
                $record = Get-ForegroundRecord -Dump @(Invoke-Checked 'dumpsys activity services dev.kestrel')
                $view = Get-Times
                if ($null -eq $lost -and $record.Foreground -ne 'true') { $lost = (Get-DeviceUptime) - $off }
                $stopped = ConvertFrom-TimeMark -Text $view['stopped']
                if ($stopped -and $stopped.At -gt $off) { break }
            }
            $state = Get-VoiceState
            $stopped = if ($view) { ConvertFrom-TimeMark -Text $view['stopped'] }
            $turnOff = if ($stopped -and $stopped.At -gt $off) { "Kestrel turned itself off for $($stopped.Kind) $($stopped.At - $off) ms after its accessibility service went off" } else { "Kestrel did not turn itself off; its mode is $(if ($state) { $state.mode } else { 'unknown' }), the microphone silenced: $(if ($state) { $state.micBusy } else { 'unknown' })" }
            $foreground = if ($view) { "foreground=$($view['foreground']) demoted=$($view['demoted'])" } else { 'not read' }
            $android = "Android's record at the start: listed $($start.Listed), isForeground $($start.Foreground), types $($start.Types); at the end: listed $($record.Listed), isForeground $($record.Foreground), types $($record.Types)"
            $lostText = if ($null -ne $lost) { "Android's record lost the foreground within $lost ms" } else { 'Android''s record kept the foreground' }
            # The kinds of Kestrel's notices in the shade (the hook notices): b for the battery restriction, a for only the
            # accessibility service, o for another kind, n for none, as the owner's letters were.
            $kinds = @("$((ConvertFrom-HookPairs (Invoke-Hook notices))['kinds'])" -split ',' | Where-Object { $_ })
            $notice = Get-NoticeLetter $kinds
            Add-Result battery-rules "${label}: $roleText; $android; $lostText; Kestrel's view at the end: $foreground; $turnOff; Kestrel's notice: $notice ($($kinds -join ','))"
            Stop-Kestrel
        }
    } finally {
        Complete-Restore $accessibilityRestore
        Complete-Restore $opRestore
        if ($roleRestore) { Complete-Restore $roleRestore }
    }
}

# The letter of the owner's old answer for the kinds of Kestrel's notices of the hook notices: b when one is about the
# battery restriction, a when the only one is about the accessibility service, n for none, and o otherwise.
function Get-NoticeLetter([string[]]$Kinds) {
    $kinds = @($Kinds | Where-Object { $_ -and $_ -ne 'none' })
    if ($kinds -contains 'battery') { 'b' } elseif ($kinds.Count -eq 0) { 'n' } elseif ($kinds.Count -eq 1 -and $kinds[0] -eq 'accessibility') { 'a' } else { 'o' }
}

# One activation event through adb (map S17 to S19), and the route that it took: the shortcut as both volume keys held for
# 3.5 s, which Android 13's key rules take also from 'input keycombination'; the button as a tap at its bounds in
# SystemUI's navigation bar; the tile through Invoke-TileTap. With -Fallback, Android's own action for the shortcut or the
# button ('cmd accessibility call-system-action' 13 or 11), for an event that started nothing.
function Invoke-Activation([string]$Name, [switch]$Fallback, [string]$Why = '') {
    if ($Fallback) {
        $action = if ($Name -eq 'shortcut') { 13 } else { 11 }
        Invoke-Checked "cmd accessibility call-system-action $action" | Out-Null
        return "Android's action $action, because $(if ($Why) { $Why } else { "Kestrel recorded no event of the $Name within 10 s" })"
    }
    switch ($Name) {
        'shortcut' { Invoke-Checked 'input keycombination -t 3500 KEYCODE_VOLUME_DOWN KEYCODE_VOLUME_UP' | Out-Null }
        'button' {
            # Right after the service went on, the hook can fail; that counts as no button.
            $button = try { ConvertFrom-HookPairs (Invoke-Hook screen-has @{ Package = 'com.android.systemui'; ViewId = 'accessibility_button' }) } catch { @{} }
            if ("$($button['bounds'])" -notmatch '^(-?\d+),(-?\d+),(-?\d+),(-?\d+)$') { return 'there is no accessibility button on the screen' }
            Invoke-Checked "input tap $([int](([int]$Matches[1] + [int]$Matches[3]) / 2)) $([int](([int]$Matches[2] + [int]$Matches[4]) / 2))" | Out-Null
        }
        default { Invoke-TileTap }
    }
    "the $Name"
}

# Whether a dialog of SystemUI or Settings takes the focus within $Seconds, as Android's dialog about the shortcut does;
# only the focused window's package or title is read (Get-FocusedWindow), never its text.
function Test-ActivationDialog([int]$Seconds = 5) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $focus = Get-FocusedWindow @(Invoke-Checked 'dumpsys window windows')
        if ($focus -in 'com.android.systemui', 'com.android.settings' -or $focus -like '*Dialog*') { return "yes ($focus)" }
        Start-Sleep -Milliseconds 500
    } while ($watch.Elapsed.TotalSeconds -lt $Seconds)
    'no'
}

# The accessibility shortcut, the accessibility button and the tile with Kestrel in the background (analysis, item 11):
# the time from Kestrel's own record of the event to its open voice session, and whether a dialog took the focus.
function Invoke-ActivationStep {
    $restore = Register-AccessibilityRestore
    try {
        # Kestrel's service must be on for the shortcut and the button; the owner's other services stay on. Android shows
        # its dialog about the shortcut only once, and this setting says that it was shown; the restore puts it back.
        if (-not (Connect-AccessibilityService -Serial $Serial -Services ((@(Get-OtherServices $restore.Data) + $service) -join ':'))) {
            throw "Android did not bind Kestrel's accessibility service (see the warning above)."
        }
        Invoke-Checked 'settings put secure accessibility_enabled 1' | Out-Null
        Invoke-Checked "settings put secure accessibility_shortcut_target_service $service" | Out-Null
        Invoke-Checked "settings put secure accessibility_button_targets $service" | Out-Null
        Invoke-Checked 'settings put secure accessibility_shortcut_dialog_shown 1' | Out-Null
        $sources = [ordered]@{ shortcut = 'accessibility'; button = 'accessibility'; tile = 'tile' }
        Add-Tile
        Initialize-KokoroSpeed activation
        foreach ($name in $sources.Keys) {
            Stop-Kestrel
            Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
            $before = ConvertFrom-TimeMark -Text ((Get-Times)['started'])
            $route = Invoke-Activation $name
            $dialog = Test-ActivationDialog
            # The fallback only when Kestrel recorded no event of this start: a start that works can take more than 15 s on
            # the A71, and a second action would pause it.
            if ($name -ne 'tile') {
                $recorded = Wait-State { param($State) $mark = ConvertFrom-TimeMark -Text ((Get-Times)['started']); $State -and $mark -and (-not $before -or $mark.At -gt $before.At) } 10
                if ($recorded -lt 0) { $route = Invoke-Activation $name -Fallback -Why $(if ($route -like 'there is no *') { $route } else { '' }) }
            }
            $on = Wait-Mode listening 120
            $times = Get-Times
            $started = ConvertFrom-TimeMark -Text $times['started']
            $opened = [long]$times['opened']
            $timed = if ($on -lt 0) {
                'did not start listening within 120 s'
            } elseif (-not $started -or ($before -and $started.At -le $before.At) -or $started.Kind -ne $sources[$name]) {
                "listening, but Kestrel recorded no event of the $($sources[$name]) for this start"
            } else {
                "listening $($opened - $started.At) ms after the event of the $($started.Kind), timed on the phone"
            }
            # The second use pauses Kestrel.
            $again = Invoke-Activation $name
            if ($name -ne 'tile' -and (Wait-Mode paused 10) -lt 0) { $again = Invoke-Activation $name -Fallback -Why $(if ($again -like 'there is no *') { $again } else { "Kestrel did not pause within 10 s of the $name" }) }
            $paused = Wait-Mode paused 15
            Add-Result activation "${name}: $timed, through $route; a dialog: $dialog; the second time, through $again, paused it: $(Format-Wait $paused)"
        }
        Stop-Kestrel
    } finally {
        Complete-Restore $restore
    }
}

function Get-AssistantHolders { Get-RoleHolders ((Invoke-Checked "cmd role get-role-holders $assistantRole") -join ' ') }

# Registers the restore of the assistant role's holders and the three settings that name the assistant's services, as
# they are now, before a step changes the role.
function Register-AssistantRestore {
    $holders = @(Get-AssistantHolders)
    $settings = [ordered]@{}
    foreach ($name in 'assistant', 'voice_interaction_service', 'voice_recognition_service') { $settings[$name] = Get-SecureSetting -Serial $Serial -Name $name }
    $byHand = "In Settings > Apps > Default apps > Digital assistant app, choose $(if ($holders) { $holders -join ', ' } else { 'None' }) again."
    Register-Restore 'the assistant role and its settings' $byHand @{ Holders = $holders; Settings = $settings } {
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
}

# With only the assistant role, without Kestrel's accessibility service and with Kestrel in the background (analysis,
# item 5): a start through the hook, "Kestrel, open settings", and the restore through the assistant service after
# Android ended Kestrel's process (design spec, sections 8.5, 8.6 and 14). None needs the owner's hands.
function Invoke-AssistantStep {
    $restore = Register-AssistantRestore
    $accessibility = Register-AccessibilityRestore
    try {
        Invoke-Checked "cmd role add-role-holder $assistantRole dev.kestrel" | Out-Null
        if (@(Get-AssistantHolders) -notcontains 'dev.kestrel') {
            Add-Result assistant 'failed: Android did not make Kestrel the assistant'
            return
        }
        Stop-Kestrel
        Invoke-Checked (Get-ServicesCommand @(Get-OtherServices $accessibility.Data)) | Out-Null
        if (-not (Wait-KestrelService off)) { throw 'Kestrel''s accessibility service did not turn off within 30 s.' }
        Write-Host 'Do not touch the phone for about 5 minutes: the script works on it by itself, and it shows Kestrel''s settings for a moment. Kestrel may speak; you need not answer.'
        Invoke-RoleAloneChecks
        Stop-Kestrel
    } finally {
        Complete-Restore $accessibility
        Complete-Restore $restore
    }
}

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
    Add-Result assistant "the role alone: a start through the hook in the background: listening, $(if ($problem) { "but $problem" } else { 'not silenced' })"
    # Kestrel acts on a request only while the screen is on.
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    Start-Sleep -Seconds 2
    $since = Get-DeviceTime
    $asked = [long](Get-Times)['settingsAsked']
    Invoke-Hook voice @{ Send = 'heard'; Text = 'Kestrel, open settings.'; Named = $true } | Out-Null
    $opened = $null
    $times = $null
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($null -eq $opened -and $watch.Elapsed.TotalSeconds -lt 10) {
        Start-Sleep -Milliseconds 500
        $times = Get-Times
        if ([long]$times['settingsAsked'] -gt $asked -and [long]$times['settingsResumed'] -ge [long]$times['settingsAsked']) {
            $opened = [long]$times['settingsResumed'] - [long]$times['settingsAsked']
        }
    }
    # Only a count of Android's lines about blocked starts in the background that name Kestrel, never their text.
    $blocked = @(Invoke-Checked "logcat -d -T '$since' -s ActivityTaskManager:W" | Where-Object { $_ -match 'Background activity start' -and $_ -match 'dev\.kestrel' }).Count
    # Kestrel's own verdict (its reaction settings-blocked after its 5 s) and its notification "Open Kestrel's settings"
    # (ProblemNotifications.SETTINGS_ID 7104), both read on the phone: of the notifications only a count leaves it.
    $askedNow = if ($times) { [long]$times['settingsAsked'] } else { 0 }
    $reaction = if ($times) { ConvertFrom-TimeMark -Text $times['reacted'] }
    $verdict = $askedNow -gt $asked -and $reaction -and $reaction.Kind -eq 'settings-blocked' -and $reaction.At -ge $askedNow
    # Only the posted notifications: their keys, such as 0|dev.kestrel|7104|null|10334, without the dump's archive.
    $offered = ((Invoke-Checked "cmd notification list | grep -c '|dev.kestrel|7104|' || true") -join '').Trim()
    $text = if ($null -ne $opened -and -not $verdict) {
        "Kestrel's settings came to the front $opened ms after Kestrel asked for them, timed on the phone"
    } elseif ($null -ne $opened) {
        "Kestrel's settings came to the front $opened ms after Kestrel asked for them, but only after Kestrel had given up after $($reaction.At - $askedNow) ms and said that Android blocked them"
    } elseif ($verdict) {
        "the settings did not come to the front within 10 s; Kestrel said that Android blocked them $($reaction.At - $askedNow) ms after it asked, and the records of its notification that opens them: $(if ($offered) { $offered } else { 'none read' })"
    } elseif ($askedNow -gt $asked) {
        'Kestrel asked for its settings, and they did not come to the front within 10 s, and Kestrel recorded no verdict'
    } else {
        'Kestrel did not ask for its settings within 10 s'
    }
    Add-Result assistant "the role alone: ""Kestrel, open settings"": $text; Android's log has $blocked blocked starts in the background of Kestrel's"
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    # The same request from the speaker, through the whole speech path (map S21).
    if (-not $DryRun -and (Initialize-Air assistant)) {
        Start-Sleep -Seconds 2
        $asked = [long](Get-Times)['settingsAsked']
        Invoke-CorpusClip c057
        $opened = $null
        $watch = [Diagnostics.Stopwatch]::StartNew()
        while ($null -eq $opened -and $watch.Elapsed.TotalSeconds -lt 15) {
            Start-Sleep -Milliseconds 500
            $times = Get-Times
            if ([long]$times['settingsAsked'] -gt $asked -and [long]$times['settingsResumed'] -ge [long]$times['settingsAsked']) {
                $opened = [long]$times['settingsResumed'] - [long]$times['settingsAsked']
            }
        }
        Add-Result assistant "the role alone: ""Kestrel, open settings"" from the speaker: $(if ($null -ne $opened) { "Kestrel's settings came to the front $opened ms after Kestrel asked for them, timed on the phone" } else { 'the settings did not come to the front within 15 s' })"
        Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    }
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
    $text = if ($again -lt 0) { 'failed: no listening within 120 s of the new start' } elseif ($problem) { "listening again, but $problem" } else { 'listening again, not silenced' }
    Add-Result assistant "the role alone: the restore through the assistant service after Android ended Kestrel's process: $text"
}

# Reads voice-route once a second for $Seconds seconds, and returns the last answer and whether any answer had the
# recording routed to a Bluetooth input.
function Watch-Route([int]$Seconds) {
    $last = $null
    $bluetooth = $false
    for ($i = 0; $i -lt $Seconds; $i++) {
        Start-Sleep -Seconds 1
        $last = ConvertFrom-HookPairs (Invoke-Hook voice-route)
        if ("$($last['routed'])" -in 'bluetooth-sco', 'ble-headset') { $bluetooth = $true }
    }
    [pscustomobject]@{ Last = $last; Bluetooth = $bluetooth }
}

function Format-Route($Route) {
    if (-not $Route.Last) { return 'no answer' }
    $parts = foreach ($name in 'earbud', 'microphone', 'holding', 'headset', 'linkedMillis', 'communication', 'routed', 'loudest') {
        if ($Route.Last.Contains($name)) { "$name=$($Route.Last[$name])" }
    }
    "$($parts -join ' '); routed to the earbuds at some second: $($Route.Bluetooth)"
}

# "Kestrel" once from the speaker (the clip c041), and Kestrel's wait for a task, timed on the phone from the end of the
# speech.
function Measure-Name {
    $before = Get-Counts
    $since = [long](Get-Times)['now']
    Invoke-CorpusClip c041
    $reaction = $null
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($null -eq $reaction -and $watch.Elapsed.TotalSeconds -lt 15) {
        Start-Sleep -Milliseconds 500
        $reaction = Get-Reaction $since 'wait'
    }
    $rise = Get-CountRise $before (Get-Counts)
    if ($reaction) { "Kestrel began to wait for a task: $reaction; counts: $rise" } else { "no wait for a task within 15 s; counts: $rise" }
}

# The earbud microphone with the owner's Galaxy Buds next to the speaker (analysis, item 6; map S23 to S26): where Android
# routes the recording, whether the fixture's music then plays through a call's route, "Kestrel" from the speaker next to
# the earbuds, and the phone's microphone after Bluetooth went off, in place of the earbuds' case.
function Invoke-EarbudStep {
    if ($DryRun) {
        Add-Result earbuds 'skipped in a dry run: it needs the owner''s Galaxy Buds'
        return
    }
    if (-not (Initialize-Air earbuds)) { return }
    Stop-Kestrel
    # Registered before the owner turns the option on, so that the script puts it back as it was through the debug hook
    # owner-options, also after a failure, a skip or Ctrl+C.
    $before = (ConvertFrom-HookPairs (Invoke-Hook owner-options))['earbudMicrophone']
    $earbud = Register-Restore 'the setting "Use the earbud microphone"' 'Turn "Use the earbud microphone" back as it was before the session, on Kestrel''s page "Listening and activation".' $before {
        param($Data)
        Assert-TestDevice -Serial $Serial
        $now = (ConvertFrom-HookPairs (Invoke-Hook owner-options @{ EarbudMicrophone = $(if ($Data -eq 'on') { 'true' } else { 'false' }) }))['earbudMicrophone']
        if ($now -ne $Data) { throw 'Kestrel did not take it back' }
    }
    # Before the owner's item, so that a build of the fixture app does not keep him waiting with his Buds.
    Initialize-TestApp
    if ((Request-OwnerItem 'Item 1 of 1, about 2 minutes: come to the A71 and this computer, take your Galaxy Buds out of their case, check that the A71 shows them as connected, and lay them next to this computer''s speaker, which stands next to the phone. The script then turns on "Use the earbud microphone" itself, plays a quiet tone on the phone and "Kestrel" from the speaker; the Buds may sound. Meanwhile the A71 shows Kestrel''s settings for a moment and the page "Media fixture" for 2 seconds, and Bluetooth turns off and on once. When this computer beeps again, in about 5 minutes, put the Buds back into their case. Press Enter when the Buds lie by the speaker, or s to skip.') -eq 'skip') {
        Add-Result earbuds 'skipped'
        Complete-Restore $earbud
        return
    }
    ConvertFrom-HookPairs (Invoke-Hook owner-options @{ EarbudMicrophone = 'true' }) | Out-Null
    # Kestrel takes the option a moment later, as from its settings.
    Start-Sleep -Seconds 2
    Start-Kestrel
    $route = Watch-Route 5
    Add-Result earbuds "the route in the 5 s after the start: $(Format-Route $route)"
    # The fixture's player plays a quiet tone; a communication device of Bluetooth's call profile while it plays means
    # that music sounds like a call (map S24).
    # The tone stops also after a failure or Ctrl+C.
    $player = Register-Restore 'the fixture''s player' "Run: & '$adb' -s $Serial shell am force-stop $testApp" $null {
        Assert-TestDevice -Serial $Serial
        Invoke-Checked "am force-stop $testApp" | Out-Null
    }
    Invoke-Checked "am start -n $testApp/.MediaActivity" | Out-Null
    Start-Sleep -Seconds 2
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    $music = Watch-Route 3
    Complete-Restore $player
    $callLike = "$($music.Last['communication'])" -match 'sco|ble'
    $named = Measure-Name
    $heard = Watch-Route 1
    Add-Result earbuds """Kestrel"" from the speaker next to the earbuds: $named; the route after the reply: $(Format-Route $heard); music played through a call's route: $(if ($callLike) { 'yes' } else { 'no' }) ($(Format-Route $music))"
    # Bluetooth off stands for the earbuds' case, and comes back on through the restore.
    $bluetooth = Register-Restore 'Bluetooth' 'Turn Bluetooth back on in Quick Settings.' $null {
        Assert-TestDevice -Serial $Serial
        Invoke-Checked 'cmd bluetooth_manager enable' | Out-Null
    }
    Invoke-Checked 'cmd bluetooth_manager disable' | Out-Null
    # Up to 15 s, until Kestrel has given the earbuds up.
    $gone = $false
    for ($i = 0; $i -lt 15 -and -not $gone; $i++) {
        $gone = (ConvertFrom-HookPairs (Invoke-Hook voice-route))['holding'] -ne 'true'
        if (-not $gone) { Start-Sleep -Seconds 1 }
    }
    if (-not $gone) { Add-Result earbuds 'Kestrel still held the earbuds 15 s after Bluetooth went off' }
    $phone = Measure-Name
    Add-Result earbuds "after Bluetooth went off, ""Kestrel"" from the speaker next to the phone: $phone"
    Complete-Restore $bluetooth
    Stop-Kestrel
    Complete-Restore $earbud
    Invoke-Beep
    if (-not $Step -or @($Step | Where-Object { $_ -notin 'install', 'earbuds' }).Count -gt 0) {
        Write-Host 'Put the Galaxy Buds back into their case now. You may leave then: the rest of the session runs without you for about 3.5 hours; the A71''s screen stays on, and it works by itself. Please do not touch it.'
    } else {
        Write-Host 'Put the Galaxy Buds back into their case now. Your item in this script is done.'
    }
}

# One measure of the voice step or the spotter step: $Tries times, $Prepare brings Kestrel into the state that each try
# needs, the speaker plays the clip $Clip once, in the Kokoro and the Piper voice by turns, and the script waits up to
# $TimeoutSeconds until $Reacted, which gets the counts from before the try and Kestrel's state, is true. For each try it
# records the rise of the counts on the phone (the spotter's hits by keyword, the transcribed and named utterances, how
# each was read) and Kestrel's latest reaction since the try began, with its time after the end of the speech, all from
# the phone. A dry run makes one try, in which it sends the clip's text through the voice hook (Invoke-CorpusClip). A
# failure, such as a Kestrel that does not come to rest, costs only this measure's lines.
function Measure-Tries(
    [string]$StepName,
    [string]$Name,
    [string]$Clip,
    [int]$Tries,
    [scriptblock]$Prepare,
    [scriptblock]$Reacted,
    [double]$TimeoutSeconds = 10
) {
    if ($DryRun) { $Tries = 1 }
    $hits = 0
    $done = 0
    $script:triesReacted = $null
    $script:triesDone = 0
    try {
        for ($try = 1; $try -le $Tries; $try++) {
            & $Prepare
            $counts = Get-Counts
            $since = [long](Get-Times)['now']
            $voice = if ($DryRun) { 'text' } elseif ($try % 2 -eq 1) { 'kokoro' } else { 'piper' }
            Invoke-CorpusClip $Clip $voice
            $done++
            $seen = $false
            $watch = [Diagnostics.Stopwatch]::StartNew()
            do {
                $state = Get-VoiceState
                $seen = $state -and (& $Reacted $counts $state)
                if (-not $seen) { Start-Sleep -Milliseconds 250 }
            } while (-not $seen -and $watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
            if ($seen) { $hits++ }
            # The pipeline's counts come once the utterance is transcribed, a moment after the reaction.
            Start-Sleep -Seconds 1
            $reaction = Get-Reaction $since
            Add-Result $StepName "${Name}, try ${try} ($voice): $(if ($seen) { 'reacted' } else { "no reaction within $TimeoutSeconds s" }); Kestrel's latest reaction: $(if ($reaction) { $reaction } else { 'none' }); counts: $(Get-CountRise $counts (Get-Counts))"
        }
    } catch {
        if ($_.Exception.Message -eq 'The owner stopped the session.') { throw }
        Add-Result $StepName "${Name}: failed after $hits of $done tries: $($_.Exception.Message)"
        return
    }
    Add-Result $StepName "${Name}: $(if ($done -eq 0) { 'skipped' } else { "$hits of $done tries" })"
    if ($done -gt 0) {
        $script:triesReacted = $hits
        $script:triesDone = $done
    }
}

# Generated clips without any of Kestrel's keywords, from the corpus's conversation and confusable lines, up to 2 minutes
# of speech, about 1.5 minutes with the corpus's clips now, which the step spotter plays for each setting of the keyword
# spotter.
function Get-PlainSpeechClips {
    $clips = [System.Collections.Generic.List[object]]::new()
    $total = 0.0
    foreach ($row in Read-Corpus -Path $corpusFile) {
        if ($row.Group -notin 'conversation', 'confusable' -or $row.Text -match '(?i)kestrel|stop|cancel') { continue }
        $file = Join-Path $generatedClips "$($row.Id).wav"
        if (-not (Test-Path -LiteralPath $file)) { continue }
        # 16 kHz, 16-bit mono after a header of 44 bytes.
        $seconds = ((Get-Item -LiteralPath $file).Length - 44) / 32000
        if ($total + $seconds -gt 120) { break }
        $total += $seconds
        $clips.Add([pscustomobject]@{ Id = $row.Id; File = $file; Seconds = $seconds })
    }
    , $clips.ToArray()
}


# Plays $Clips through this computer's speaker while Kestrel is paused, so that nothing that the clips say runs, and
# returns the minutes of speech played (Minutes) and the rise of the spotter's hits by keyword (ByKeyword: kestrel, stop
# and cancel): false hits, because no clip holds a keyword (analysis, item 8; plan 4E). A dry run plays nothing and
# returns 0 minutes and no hits.
function Measure-FalseHits([string]$Setting, [object[]]$Clips) {
    if ($DryRun) {
        Add-Result spotter "${Setting}: no speech played in a dry run"
        return [pscustomobject]@{ Minutes = 0; ByKeyword = @{ kestrel = 0; stop = 0; cancel = 0 } }
    }
    if ($Clips.Count -eq 0) {
        Add-Result spotter "${Setting}: no false hits measured, because corpus\generated\piper has no clips of conversation or confusable lines; run scripts\make-corpus.ps1 first"
        return $null
    }
    Write-Host 'Kestrel is paused now, and this computer plays about 1.5 minutes of speech without Kestrel''s name.'
    Invoke-Hook voice @{ Send = 'pause' } | Out-Null
    Wait-Mode paused 10 | Out-Null
    $before = Get-Counts
    $seconds = 0.0
    foreach ($clip in $Clips) {
        # A false "Kestrel, wake up" would make Kestrel listen, and act on the clips after it.
        $state = Get-VoiceState
        if ($state -and $state.mode -ne 'paused') { Invoke-Hook voice @{ Send = 'pause' } | Out-Null }
        Invoke-Clip -Path $clip.File -Gain $script:gain | Out-Null
        $seconds += $clip.Seconds
    }
    Start-Sleep -Seconds 2
    $after = Get-Counts
    $byKeyword = @{}
    foreach ($keyword in 'kestrel', 'stop', 'cancel') {
        $byKeyword[$keyword] = (Get-TableCount $after "hit-$keyword") - (Get-TableCount $before "hit-$keyword")
    }
    Add-Result spotter ("${Setting}: false hits in $([math]::Round($seconds / 60, 1)) minutes of played speech without a keyword, with " +
        "Kestrel paused: kestrel $($byKeyword.kestrel), stop $($byKeyword.stop), cancel $($byKeyword.cancel); counts: $(Get-CountRise $before $after)")
    [pscustomobject]@{ Minutes = $seconds / 60; ByKeyword = $byKeyword }
}

# "Hey Kestrel" with three settings of the keyword spotter (analysis, item 8): Kestrel's settings since plan 2A, and two
# that hear more. Each setting counts for every keyword spotter that loads after the hook, so the step starts Kestrel
# again for each one; the default comes back at the end.
function Invoke-SpotterStep {
    $default = Invoke-Hook voice-spotter
    $tuningRestore = Register-Restore "the keyword spotter's setting" "Run: scripts\hook.ps1 -Serial $Serial voice-spotter -Boost 2 -Threshold 0.15" $null {
        Invoke-Hook voice-spotter @{ Boost = 2; Threshold = 0.15 } | Out-Null
    }
    $rounds = [System.Collections.Generic.List[object]]::new()
    try {
        if (-not (Initialize-Air spotter)) { return }
        $clips = Get-PlainSpeechClips
        $tunings = @(@{ Boost = 2; Threshold = 0.15 }, @{ Boost = 3; Threshold = 0.1 }, @{ Boost = 4; Threshold = 0.05 })
        if ($DryRun) { $tunings = @($tunings[0], $tunings[1]) }
        foreach ($tuning in $tunings) {
            $set = Invoke-Hook voice-spotter $tuning
            Stop-Kestrel
            Start-Kestrel
            # A process that ended since the hook would measure the default under this setting's name.
            $now = Invoke-Hook voice-spotter
            if ($now -ne $set) {
                Add-Result spotter "${set}: not measured, because Kestrel's process reads $now"
                continue
            }
            $quiet = { Wait-Quiet }
            Measure-Tries spotter "$set, ""Hey Kestrel""" c042 5 $quiet { param($Counts, $State) $State -and [int](Get-Counts)['waits'] -gt [int]$Counts['waits'] } 8
            $reacted = $script:triesReacted
            $tries = $script:triesDone
            $falseHits = Measure-FalseHits $set $clips
            if ($null -eq $reacted -or $null -eq $falseHits) { continue }
            $rounds.Add([pscustomobject]@{
                    Setting = $set; Default = $tuning.Boost -eq 2 -and $tuning.Threshold -eq 0.15
                    Tries = @{ 'Hey Kestrel' = $tries }; Hits = @{ 'Hey Kestrel' = $reacted }
                    Minutes = $falseHits.Minutes; FalseHits = $falseHits.ByKeyword
                })
        }
    } finally {
        Complete-Restore $tuningRestore
        Stop-Kestrel
    }
    Add-Result spotter "the setting before the step: $default"
    $winner = Select-SpotterWinner @($rounds)
    Add-Result spotter $(if ($winner.Setting) { "the setting that the rule keeps: $($winner.Setting) ($($winner.Reason))" } else { $winner.Reason })
}

# An interruption (analysis, item 8; map S34): Kestrel reads a long reply, the speaker plays "Kestrel", and after a pause
# of $Pause s from the end of that clip, "open the weather"; this computer times the pause after PlaySync has returned, so
# it is the pause plus Windows' output delay, and the phone times Kestrel's reactions. The two tries take the Kokoro and
# the Piper voice.
function Measure-Interruption([string]$Pause) {
    # At least: the clips' silent ends and starts, and Windows' output delay, add to it.
    $name = "an interruption with a pause of at least $Pause s"
    $tries = if ($DryRun) { 1 } else { 2 }
    $done = 0
    $hits = 0
    for ($try = 1; $try -le $tries; $try++) {
        # A failure of one try, such as a Kestrel that does not come to rest, costs only this try's line, as in
        # Measure-Tries.
        try {
            Wait-Quiet
            $counts = Get-Counts
            $since = [long](Get-Times)['now']
            $voice = if ($DryRun) { 'text' } elseif ($try % 2 -eq 1) { 'kokoro' } else { 'piper' }
            $done++
            Invoke-CorpusClip k022 $voice
            $speaking = Wait-State { param($State) $State.speaking -eq 'true' } 30
            if ($speaking -lt 0) {
                Add-Result voice "${name}, try ${try}: Kestrel did not begin its reply within 30 s; counts: $(Get-CountRise $counts (Get-Counts))"
                continue
            }
            Start-Sleep -Seconds 3
            Invoke-CorpusClip c041 $voice
            Start-Sleep -Milliseconds ([int]([double]::Parse($Pause, [Globalization.CultureInfo]::InvariantCulture) * 1000))
            Invoke-CorpusClip k023 $voice
            # The condition runs in Wait-State, called from here, so it reads $counts of this try.
            $tasks = Wait-State { param($State) $State -and [int](Get-Counts)['tasks'] -ge [int]$counts['tasks'] + 2 } 30
            if ($tasks -ge 0) { $hits++ }
            Start-Sleep -Seconds 1
            $reaction = Get-Reaction $since
            Add-Result voice "${name}, try ${try} ($voice): $(if ($tasks -ge 0) { 'both tasks ran' } else { 'the second task did not run within 30 s' }); Kestrel's latest reaction: $(if ($reaction) { $reaction } else { 'none' }); counts: $(Get-CountRise $counts (Get-Counts))"
        } catch {
            if ($_.Exception.Message -eq 'The owner stopped the session.') { throw }
            Add-Result voice "${name}, try ${try}: failed: $($_.Exception.Message)"
        }
    }
    Add-Result voice "${name}: $(if ($done -eq 0) { 'skipped' } else { "$hits of $done tries" })"
}

function Invoke-VoiceStep {
    $quiet = { Wait-Quiet }
    if (-not (Initialize-Air voice)) { return }
    $waited = { param($Counts, $State) $State -and [int](Get-Counts)['waits'] -gt [int]$Counts['waits'] }
    Measure-Tries voice '"Kestrel"' c041 5 $quiet $waited 10
    Measure-Tries voice '"Hey Kestrel"' c042 5 $quiet $waited 10
    $paused = {
        Wait-Quiet
        Invoke-Hook voice @{ Send = 'pause' } | Out-Null
        Wait-Mode paused 10 | Out-Null
    }
    Measure-Tries voice '"Kestrel, wake up" while paused' c043 5 $paused { param($Counts, $State) $Counts -and $State.mode -eq 'listening' } 10
    # Each goal's round now takes a second and then answers with a long reply without any of Kestrel's keywords, which
    # Kestrel reads whole as the goal's final words and which the owner interrupts. A pause shorter than the speech
    # detector's 0.7 s joins the request to Kestrel's own voice, which the pipeline drops after an interruption
    # (analysis, item 8), so the pauses start at 1 s.
    $story = 'Here is a long reply, so that you can interrupt it. ' + ('The news of the day comes one sentence after another, at an even pace. ' * 6)
    Invoke-Hook voice-task @{ Seconds = 1; Reply = $story } | Out-Null
    try {
        foreach ($pause in '1', '1.5', '2') { Measure-Interruption $pause }
    } finally {
        Invoke-Hook voice-task @{ Clear = $true } | Out-Null
    }
    Measure-Tries voice '"Stop Kestrel"' c053 3 $quiet { param($Counts, $State) $Counts -and $State.mode -eq 'off' } 10
}

# The assistant volume, which Kestrel's replies use, and its top, from 'cmd media_session volume --stream 11 --get'.
# VoicePlayer plays with USAGE_ASSISTANT and CONTENT_TYPE_SPEECH, which Android plays on STREAM_ASSISTANT (11); One UI
# keeps that volume apart from the media volume (stream 3), which does not reach Kestrel's replies (inv-4e-stream).
function Get-AssistantVolume {
    $answer = (Invoke-Checked 'cmd media_session volume --stream 11 --get') -join ' '
    if ($answer -notmatch 'volume is (\d+) in range \[(\d+)\.\.(\d+)\]') { throw "Not an answer of cmd media_session volume: '$answer'" }
    [pscustomobject]@{ Index = [int]$Matches[1]; Max = [int]$Matches[3] }
}

# Sets the assistant volume (stream 11) to $Index, and returns the index that it reads back, so that the caller sees
# whether the set took.
function Set-AssistantVolume([int]$Index) {
    Invoke-Checked "cmd media_session volume --stream 11 --set $Index" | Out-Null
    (Get-AssistantVolume).Index
}

# The level at which the phone hears this computer's "Kestrel" (c041 in the Kokoro voice) with $Gain: Kestrel is off and
# its settings are in front, and the hook voice-record records with Kestrel's own microphone and answers the loudest
# 20 ms frame (frameRms), the measure of voice-route. Returns the level in dBFS, or $null.
function Measure-NameLevel([double]$Gain) {
    Invoke-Checked 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
    Invoke-Hook voice-record @{ File = 'level'; Seconds = 4 } | Out-Null
    Invoke-Clip -Path (Join-Path $generatedRoot 'kokoro\c041.wav') -Gain $Gain | Out-Null
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        Start-Sleep -Milliseconds 500
        $recorded = Invoke-Hook voice-record @{ Result = $true }
    } while ($recorded -eq 'recording' -and $watch.Elapsed.TotalSeconds -lt 15)
    if ($recorded -match 'silenced=false.*frameRms=(-?\d+(\.\d+)?)') { [double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture) } else { $null }
}

# One try of the step barge-in: "Kestrel, read me the news" (k022) makes Kestrel read the long reply of voice-task, the
# phone reads the level of Kestrel's own voice at its microphone for 3 s (voice-route's frameRms), and this computer plays
# "Kestrel" (c041) with $Gain, or a dry run sends it. Kestrel was interrupted when it stopped speaking and waits for a task
# within 5 s. Returns the echo's level in dBFS ($null when Kestrel did not speak), whether Kestrel was interrupted, and
# what the counts of voice-counts rose by.
function Measure-BargeInTry([double]$Gain) {
    Wait-Quiet
    $counts = Get-Counts
    Invoke-CorpusClip k022 kokoro
    if ((Wait-State { param($State) $State.speaking -eq 'true' } 30) -lt 0) {
        return [pscustomobject]@{ EchoDb = $null; Interrupted = $false; Rise = Get-CountRise $counts (Get-Counts) }
    }
    Invoke-Hook voice-route | Out-Null
    Start-Sleep -Seconds 3
    $level = (ConvertFrom-HookPairs (Invoke-Hook voice-route))['frameRms']
    $echo = if ($level -match '^-?\d+(\.\d+)?$') { [double]::Parse($level, [Globalization.CultureInfo]::InvariantCulture) } else { $null }
    if ($DryRun) { Invoke-CorpusClip c041 kokoro } else { Invoke-Clip -Path (Join-Path $generatedRoot 'kokoro\c041.wav') -Gain $Gain | Out-Null }
    $stopped = (Wait-State { param($State) $State.speaking -eq 'false' -and $State.work -eq 'awaiting_task' } 5) -ge 0
    [pscustomobject]@{ EchoDb = $echo; Interrupted = $stopped; Rise = Get-CountRise $counts (Get-Counts) }
}

# What came after Kestrel's own reply when nobody interrupted it (inv-a71-speech, section 3, "A side issue"): k022 makes
# Kestrel read the long reply of voice-task to its end, no clip plays meanwhile, and the 5 s after it show which commands
# started (voice-state -Number: kind and outcome), how long after the end of the latest transcribed speech Kestrel's
# latest reaction came, and what the pipeline heard (voice-counts). Returns whether a command started.
function Measure-ReplyTail([int]$Try) {
    Wait-Quiet
    Invoke-CorpusClip k022 kokoro
    if ((Wait-State { param($State) $State.speaking -eq 'true' } 30) -lt 0) {
        Add-Result barge-in "the reply's end, try ${Try}: Kestrel did not begin its reply within 30 s"
        return $false
    }
    $counts = Get-Counts
    $since = [long](Get-Times)['now']
    $before = [int](Get-VoiceState).command
    $ended = Wait-State { param($State) $State.speaking -eq 'false' } 90
    Start-Sleep -Seconds 5
    $latest = [int](Get-VoiceState).command
    $commands = @(for ($number = $before + 1; $number -le $latest; $number++) {
            $state = ConvertFrom-VoiceState (Invoke-Hook voice-state @{ Number = $number })
            "$($state.kind) ($($state.outcome))"
        })
    $reaction = Get-Reaction $since 'command'
    Add-Result barge-in ("the reply's end, try ${Try}: the reply $(if ($ended -ge 0) { 'ended' } else { 'did not end within 90 s' }); " +
        "$($commands.Count) commands started after it$(if ($commands.Count -gt 0) { ': ' + ($commands -join ', ') }); Kestrel's latest " +
        "command: $(if ($reaction) { $reaction } else { 'none' }); counts: $(Get-CountRise $counts (Get-Counts))")
    $commands.Count -gt 0
}

# The step barge-in (plan 4E; the owner's decision 6): see the script's header. A failed try costs only its own line.
function Invoke-BargeInStep {
    if (-not (Initialize-Air barge-in)) { return }
    # The A71 run of plan 4D may have left another setting in Kestrel's process; it counts from the next start.
    Add-Result barge-in "the keyword spotter's setting: $(Invoke-Hook voice-spotter @{ Boost = 2; Threshold = 0.15 })"
    $volume = Get-AssistantVolume
    $indices = Get-BargeInVolumes $volume.Index
    Add-Result barge-in ("the assistant volume (stream 11), which Kestrel's replies use, is $($volume.Index) of $($volume.Max) (the owner's), " +
        "so the rows measure at $($indices -join ', ')")
    $volumeRestore = Register-Restore 'the assistant volume (stream 11)' "Run: & '$adb' -s $Serial shell cmd media_session volume --stream 11 --set $($volume.Index)" $volume.Index {
        param($Data)
        Assert-TestDevice -Serial $Serial
        $back = Set-AssistantVolume $Data
        if ($back -ne $Data) { throw "the assistant volume read back as $back, not $Data" }
    }
    $story = 'Here is a long reply, so that you can interrupt it. ' + ('The news of the day comes one sentence after another, at an even pace. ' * 6)
    Invoke-Hook voice-task @{ Seconds = 1; Reply = $story } | Out-Null
    $rows = [System.Collections.Generic.List[object]]::new()
    $tries = if ($DryRun) { 1 } else { 4 }
    try {
        $levels = [System.Collections.Generic.List[object]]::new()
        Stop-Kestrel
        try {
            foreach ($target in -24, -18, -12) {
                if ($DryRun) {
                    $levels.Add([pscustomobject]@{ Target = $target; Gain = 1.0; LevelDb = $null })
                    continue
                }
                $level = Set-SpeakerLevel -TargetDb $target -Measure { param($Gain) Measure-NameLevel $Gain }
                $levels.Add([pscustomobject]@{ Target = $target; Gain = $level.Gain; LevelDb = $level.LevelDb })
                Add-Result barge-in ("""Kestrel"" for $target dBFS reaches the phone at $(if ($null -eq $level.LevelDb) { 'nothing' } else { "$($level.LevelDb) dBFS" }) with a gain of $([math]::Round($level.Gain, 2))" +
                    $(if ($level.InBand) { '' } else { '; not reached: this computer plays too quietly' }))
            }
        } finally {
            Invoke-Checked "rm -f $(Get-EvalFolder)/level.wav" | Out-Null
        }
        Start-Kestrel
        # The owner's index gives the normal row; the indices below it lower Kestrel's echo on both sides of the 15 dB
        # that the branch volume may take (Get-BargeInVolumes).
        foreach ($index in $indices) {
            $back = Set-AssistantVolume $index
            if ($back -ne $index) {
                Add-Result barge-in "assistant volume $index of $($volume.Max): failed: it read back as $back, so its rows were not measured"
                continue
            }
            foreach ($level in $levels) {
                $name = "assistant volume $index of $($volume.Max), ""Kestrel"" for $($level.Target) dBFS"
                $echoes = [System.Collections.Generic.List[double]]::new()
                $done = 0
                $interrupted = 0
                for ($try = 1; $try -le $tries; $try++) {
                    try {
                        $result = Measure-BargeInTry -Gain $level.Gain
                    } catch {
                        if ($_.Exception.Message -eq 'The owner stopped the session.') { throw }
                        Add-Result barge-in "${name}, try ${try}: failed: $($_.Exception.Message)"
                        continue
                    }
                    # Without a reply there was nothing to interrupt, so the try does not count.
                    if ($null -eq $result.EchoDb) {
                        Add-Result barge-in "${name}, try ${try}: Kestrel did not begin its reply; counts: $($result.Rise)"
                        continue
                    }
                    $done++
                    $echoes.Add($result.EchoDb)
                    if ($result.Interrupted) { $interrupted++ }
                    Add-Result barge-in "${name}, try ${try}: Kestrel's own voice at $(if ($null -ne $result.EchoDb) { "$($result.EchoDb) dBFS" } else { 'nothing' }); $(if ($result.Interrupted) { 'interrupted' } else { 'not interrupted' }); counts: $($result.Rise)"
                }
                $sorted = @($echoes | Sort-Object)
                $echo = if ($sorted.Count -gt 0) { [math]::Round($sorted[[int][math]::Floor(($sorted.Count - 1) / 2)], 1) } else { $null }
                $rows.Add([pscustomobject]@{ Volume = $index; Max = $volume.Max; ClipDb = $level.LevelDb; EchoDb = $echo; Tries = $done; Interrupted = $interrupted })
                Add-Result barge-in "${name}: Kestrel's own voice at a median $(if ($null -ne $echo) { "$echo dBFS" } else { 'nothing' }); interrupted in $interrupted of $done tries"
            }
        }
        # The replies' ends run at the owner's own assistant volume, as he hears Kestrel.
        $back = Set-AssistantVolume $volume.Index
        $tailTries = if ($DryRun) { 1 } else { 3 }
        if ($back -ne $volume.Index) {
            Add-Result barge-in "the replies' ends: failed: the assistant volume read back as $back, not $($volume.Index), so they were not measured"
        } else {
            $tails = 0
            for ($try = 1; $try -le $tailTries; $try++) {
                try {
                    if (Measure-ReplyTail $try) { $tails++ }
                } catch {
                    if ($_.Exception.Message -eq 'The owner stopped the session.') { throw }
                    Add-Result barge-in "the reply's end, try ${try}: failed: $($_.Exception.Message)"
                }
            }
            Add-Result barge-in "a command started after $tails of $tailTries replies that nobody interrupted, at the assistant volume $($volume.Index) of $($volume.Max)"
        }
    } finally {
        Invoke-Hook voice-task @{ Clear = $true } | Out-Null
        Complete-Restore $volumeRestore
    }
    $decision = Get-BargeInDecision @($rows)
    Add-Result barge-in ("decision: branch=$($decision.Branch) reductionDb=$($decision.ReductionDb) nameDb=$($decision.NameDb) " +
        "echoDb=$($decision.EchoDb) workingEchoDb=$($decision.WorkingEchoDb) marginDb=$($decision.MarginDb)")
}

# Three starts and closes from the front with the stored voice: the peak PSS while each session opens, from samples of
# 'dumpsys meminfo' as fast as adb answers them, with 0.5 s between them, and the memory categories before, while
# listening and after the close (analysis, item 9), and whether one process ran them all. A short peak can fall between
# two samples, so the line gives their number and their mean interval.
function Measure-Starts([string]$Voice) {
    $process = Get-KestrelProcess
    for ($cycle = 1; $cycle -le 3; $cycle++) {
        $idle = Get-KestrelMemory
        Invoke-Hook voice @{ Send = 'toggle' } | Out-Null
        $peak = 0
        $samples = 0
        $watch = [Diagnostics.Stopwatch]::StartNew()
        do {
            $pss = Get-KestrelPss
            $samples++
            if ($pss -gt $peak) { $peak = $pss }
            $state = Get-VoiceState
            $open = $state -and $state.mode -eq 'listening' -and $state.session -eq 'open'
            if (-not $open) { Start-Sleep -Milliseconds 500 }
        } while (-not $open -and $watch.Elapsed.TotalSeconds -lt 120)
        $interval = [math]::Round($watch.Elapsed.TotalSeconds / [math]::Max(1, $samples), 1)
        $listening = Get-KestrelMemory
        $choice = Get-VoiceChoice
        $fellBack = if ($Voice -ne 'kokoro') { '' } elseif ($choice.KokoroFailed) { '; Kokoro did not load, so Piper spoke' } elseif ($choice.KokoroSlow -ne 'none') { "; Kokoro's real-time factor on this phone is $($choice.KokoroSlow), so Piper spoke" } else { "; Kokoro spoke, with a real-time factor on this phone of $($choice.KokoroSpeed)" }
        Invoke-Hook voice @{ Send = 'off' } | Out-Null
        $closed = Wait-Mode off 60
        $after = Get-KestrelMemory
        Add-Result memory "$Voice, start ${cycle}: the session opened: $(if ($open) { 'ok' } else { 'failed' }); peak PSS while it opened $peak MB, of $samples samples about $interval s apart; before: $idle; while listening: $listening; after the close, which ended $(Format-Wait $closed): $after$fellBack; Kestrel's accessibility service: $(Get-KestrelServiceState)"
    }
    Add-Result memory "${Voice}: one process ran the three starts: $($process -and (Get-KestrelProcess) -eq $process)"
}

function Invoke-MemoryStep {
    Stop-Kestrel
    # In front, Android lets the hook start Kestrel's microphone service.
    Invoke-Checked 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
    $chosen = Get-VoiceChoice
    # Kestrel measures Kokoro's speed at the first start after the choice, at a known moment of the session.
    if ($chosen.Voice -eq 'kokoro') { Get-VoiceChoice @{ Voice = 'kokoro' } | Out-Null }
    $starts = if ($chosen.Voice -eq 'kokoro') { 'three times, for about 3 minutes' } else { 'six times, for about 5 minutes' }
    Write-Host "The phone shows Kestrel's settings while Kestrel starts and stops $starts; then the script taps Kestrel's tile once."
    Measure-Starts $chosen.Voice
    # Spec 5.3's memory target holds also for an owner who chooses Kokoro; Kestrel measures Kokoro's speed at its first
    # load on the phone, and Piper speaks where it is too slow.
    $voiceRestore = Register-Restore "Kestrel's voice" "Run: scripts\hook.ps1 -Serial $Serial voice-choice -Voice $($chosen.Voice), or choose the voice on Kestrel's page ""Voice and speech""." $chosen.Voice {
        param($Data)
        if ((Get-VoiceChoice @{ Voice = $Data }).Voice -ne $Data) { throw 'the stored voice differs from before' }
    }
    try {
        if ($chosen.Voice -ne 'kokoro') {
            Get-VoiceChoice @{ Voice = 'kokoro' } | Out-Null
            Measure-Starts kokoro
        }
    } finally {
        Complete-Restore $voiceRestore
    }
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    # A cold start: the tile starts the service in a new process, which must reach startForeground() in time. The script
    # ends Kestrel's process itself with a plain kill (SIGTERM) through run-as, so that a kill that Android refuses cannot
    # look like a new process (analysis, item 7); the A71's Android 13 refuses SIGKILL from run-as, but not SIGTERM.
    $restore = Register-AccessibilityRestore
    try {
        # The restore of Kokoro above forgets its measure, and the cold start below is timed.
        Initialize-KokoroSpeed memory
        # Before the kill: SystemUI binds the tile's service, which starts Kestrel's process, as on the owner's phone.
        Add-Tile
        Invoke-Checked (Get-ServicesCommand @(Get-OtherServices $restore.Data)) | Out-Null
        if (-not (Wait-KestrelService off)) { throw 'Kestrel''s accessibility service did not turn off within 30 s.' }
        $old = Get-KestrelProcess
        $state = Get-ProcessState -Dump @(Invoke-Checked 'dumpsys activity processes dev.kestrel')
        if ($old) {
            try {
                Invoke-Checked "run-as dev.kestrel kill $old" | Out-Null
            } catch {
                # toybox's kill says "unknown pid" for every refusal; the message names only the command and the process ID.
                Add-Result memory "a cold start through the tile: not tried, because 'run-as dev.kestrel kill' could not end Kestrel's process ($state)"
                return
            }
        }
        Start-Sleep -Seconds 2
        $now = Get-KestrelProcess
        $ended = if (-not $old) { 'no process ran' } elseif (-not $now) { 'it ended' } elseif ($now -ne $old) { 'a new process started at once' } else { 'it did not end' }
        Add-Result memory "a cold start through the tile: Kestrel's process before the kill: $state; after 'run-as dev.kestrel kill': $ended"
        # A process that Android started at once is seconds old and has loaded no model, so the tap still checks a young
        # process; only a process that did not end makes the tap pointless.
        if ($now -and $now -eq $old) {
            Add-Result memory 'a cold start through the tile: not tried, because Kestrel''s process did not end'
            return
        }
        Invoke-TileTap
        $on = Wait-Mode listening 120
        $process = Get-KestrelProcess
        $times = Get-Times
        $started = ConvertFrom-TimeMark -Text $times['started']
        $timed = if ($on -ge 0 -and $started -and $started.Kind -eq 'tile') { "listening $([long]$times['opened'] - $started.At) ms after the tap, timed on the phone" } elseif ($on -ge 0) { 'listening, but Kestrel recorded no tap of the tile in its new process' } else { 'no listening within 120 s' }
        if ($now) { $timed = "$timed, in the process that Android started at once after the kill" }
        # A service that misses Android's deadline for startForeground() ends the process.
        Start-Sleep -Seconds 15
        $same = $process -and (Get-KestrelProcess) -eq $process -and (Get-VoiceState).mode -eq 'listening'
        Add-Result memory "a cold start through the tile: $timed; the same process still listened 15 s later: $same"
        Stop-Kestrel
    } finally {
        Complete-Restore $restore
    }
}

# The generated clips that the second battery measure plays, one every 5 minutes, through this computer's speaker, from
# corpus\generated\piper, which scripts\make-corpus.ps1 makes: tasks with Kestrel's name, which the hooks' task that ends
# at once takes, so that Kestrel answers and keeps listening with the home screen in front. Never a line that turns
# Kestrel off, pauses it, opens a page, stops, sends or calls.
$batteryClipIds = @('c074', 'c078', 'c084', 'c091', 'c089', 'c082')
function Get-CommandClips {
    @(Read-Corpus -Path $corpusFile | Where-Object { $_.Id -in $batteryClipIds -and $_.Expected -eq 'Task' } |
            ForEach-Object { [pscustomobject]@{ Id = $_.Id; Text = $_.Text; File = Join-Path $generatedClips "$($_.Id).wav" } } |
            Where-Object { Test-Path -LiteralPath $_.File })
}

# One battery measure of $Minutes minutes, timed by batterystats (research r10): Kestrel listens with the screen on and
# the phone counted as on battery; $Every runs every 5 minutes, from the start. Returns Kestrel's % per hour and the
# minutes of audio that it transcribed in an hour, or $null without an estimate.
function Measure-Battery([string]$Name, [double]$Minutes, [string]$Uid, [scriptblock]$Every = {}) {
    $batteryRestore = Register-Restore "the battery's state" "Run: & '$adb' -s $Serial shell dumpsys battery reset" $null {
        Assert-TestDevice -Serial $Serial
        Invoke-Checked 'dumpsys battery reset' | Out-Null
    }
    try {
        Start-Kestrel
        $before = Get-Counts
        # Research r10: first on battery, because Android resets the statistics itself when a phone at 90 % or more goes on
        # battery, and only then the reset. The battery's level stays frozen until the battery's state is put back.
        Invoke-Checked 'dumpsys battery unplug' | Out-Null
        Invoke-Checked 'dumpsys batterystats --reset' | Out-Null
        $restarts = 0
        $played = 0
        $script:callStopped = $false
        # Instant commands run on room talk since sub-project 3, and a locked screen stops the recording while the mode
        # stays listening, so each poll also reads the command number and the screen.
        $first = Get-VoiceState
        $firstCommand = if ($first) { [long]$first['command'] } else { 0 }
        $lastCommand = $firstCommand
        $screenOff = $false
        $watch = [Diagnostics.Stopwatch]::StartNew()
        while ($watch.Elapsed.TotalMinutes -lt $Minutes) {
            if ($watch.Elapsed.TotalMinutes -ge $played * 5) {
                & $Every
                $played++
            }
            # Each poll runs in Kestrel's process, so the script polls only every 30 s.
            Start-Sleep -Seconds ([math]::Min(30, [math]::Max(1, ($Minutes - $watch.Elapsed.TotalMinutes) * 60)))
            $state = Get-VoiceState
            if ($state -and $state.Contains('command')) { $lastCommand = [long]$state['command'] }
            # The room's talk could hold a call command; the A71 has no SIM card, but the measure stops and ends any call.
            if ($state -and $state['kind'] -eq 'Call' -and [long]$state['command'] -gt $firstCommand) {
                $script:callStopped = $true
                Stop-DeviceCall -Serial $Serial | Out-Null
                break
            }
            if ($state -and $state['screenOn'] -eq 'false') { $screenOff = $true }
            if (-not $state -or $state.mode -ne 'listening') {
                # A false "Stop listening" or "Kestrel, turn off" would leave Kestrel paused or off for the rest of the
                # measure, which would then measure a Kestrel that does not listen.
                try { Start-Kestrel; $restarts++ } catch { $restarts++ }
            }
        }
        $after = Get-Counts
        # Batterystats gives an app the power of its recording only once the recording stops (seen on kestrel-api37), so
        # Kestrel turns off for the dump.
        Stop-Kestrel
        $charged = @(Invoke-Checked 'dumpsys batterystats --charged')
        $hours = Get-BatteryHours -Dump $charged
        $timedBy = 'batterystats'
        if (-not $hours) { $hours = $watch.Elapsed.TotalHours; $timedBy = 'this computer, because batterystats printed no time on battery' }
        $onBattery = Test-OnBattery -Dump $charged
        Complete-Restore $batteryRestore
        $use = if ($Uid) { Get-BatteryUse -Dump $charged -Uid $Uid -Hours $hours }
        $audio = ([long]$(if ($after.Contains('audioMillis')) { $after['audioMillis'] } else { 0 }) - [long]$(if ($before.Contains('audioMillis')) { $before['audioMillis'] } else { 0 })) / 60000
        $minutesPerHour = if ($hours -gt 0) { [math]::Round($audio / $hours, 2) } else { 0 }
        $counted = "$(if ($script:callStopped) { 'stopped early, because a call command started; ' })$([math]::Round($hours * 60, 1)) minutes timed by $timedBy; batterystats counted the phone as on battery at the end: $(if ($null -eq $onBattery) { 'not shown' } else { $onBattery }); Kestrel's accessibility service: $(Get-KestrelServiceState); restarts of listening: $restarts; instant commands that ran: $($lastCommand - $firstCommand); the screen was off at a poll: $screenOff; Kestrel transcribed $([math]::Round($audio, 2)) minutes of audio, $minutesPerHour minutes per hour; counts: $(Get-CountRise $before $after)"
        if (-not $Uid) {
            Add-Result battery "${Name}: no estimate, because neither dumpsys package nor pm list packages named Kestrel's UID; $counted"
            return $null
        }
        if (-not $use) {
            Add-Result battery "${Name}: no estimate: $(Get-BatteryUseGap -Dump $charged -Uid $Uid); $counted"
            return $null
        }
        Add-Result battery "${Name}: Kestrel (UID $Uid) used $($use.MilliampHours) mAh, with a capacity of $($use.Capacity) mAh: $($use.PercentPerHour) % per hour; its parts in mAh: $(if ($use.Breakdown) { $use.Breakdown } else { 'not printed' }); $counted"
        [pscustomobject]@{ PercentPerHour = $use.PercentPerHour; MinutesPerHour = $minutesPerHour }
    } finally {
        Complete-Restore $batteryRestore
    }
}

# Three measures of 30 minutes (analysis, item 10), and the model "% per hour = baseline + cost per transcribed minute x
# minutes" from them. The screen stays on, because Kestrel records only then, and "Stay awake" ends once Android counts
# the phone as on battery.
function Invoke-BatteryStep {
    $minutes = 30 * $minuteScale
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
    try {
        Invoke-Checked "settings put system screen_off_timeout $([int](($minutes + 10) * 60000))" | Out-Null
        $quiet = $null
        $typical = $null
        $talk = $null
        # Measure 1 needs a quiet room, which its own counts show: the audio that Kestrel transcribed stays near 0.
        $quiet = Measure-Battery 'measure 1, a quiet room' $minutes $uid
        $clips = @(Get-CommandClips)
        if (-not $DryRun -and $clips.Count -eq 0) {
            Add-Result battery 'measure 2, typical use: skipped, because corpus\generated\piper has no clips of commands with Kestrel''s name; run scripts\make-corpus.ps1 first'
        } elseif (Initialize-Air battery) {
            $every = if ($DryRun) {
                { Send-Heard "+$((Read-Corpus -Path $corpusFile | Where-Object { $_.Id -eq $batteryClipIds[0] }).Text)" }
            } else {
                {
                    Invoke-Clip -Path $clips[$script:clipIndex % $clips.Count].File -Gain $script:gain | Out-Null
                    $script:clipIndex++
                }
            }
            $script:clipIndex = 0
            Add-Result battery "measure 2 plays the clips $(@($clips | ForEach-Object { $_.Id }) -join ', ')$(if ($DryRun) { ' (in a dry run, the voice hook sends the first one''s text)' })"
            $typical = Measure-Battery 'measure 2, typical use' $minutes $uid $every
        }
        # Measure 3: the room-sound track of scripts\make-room-sound.ps1 over and over, 8 dB below a voice at 1 m, about
        # a television 2 to 3 m away (map part 7.4); a dry run plays nothing and measures a quiet room again.
        $track = Join-Path $generatedRoot 'room-sound.wav'
        if (-not $DryRun -and -not (Test-Path -LiteralPath $track)) {
            Add-Result battery 'measure 3, room talk: skipped, because corpus\generated\room-sound.wav is missing; make it with scripts\make-room-sound.ps1'
        } elseif ($DryRun -or (Initialize-Air battery)) {
            $player = if (-not $DryRun) { Start-ClipLoop -Path $track -Gain ($script:gain * [math]::Pow(10, -8 / 20)) }
            try {
                $talk = Measure-Battery 'measure 3, room talk' $minutes $uid
            } finally {
                Stop-ClipLoop $player
            }
        }
        # The cost comes from the measure with the most transcribed audio, and the other one checks the model.
        $costly = @($typical, $talk) | Where-Object { $_ } | Sort-Object MinutesPerHour -Descending | Select-Object -First 1
        $cost = if ($quiet -and $costly) { Get-BatteryCost -Baseline $quiet.PercentPerHour -PercentPerHour $costly.PercentPerHour -MinutesPerHour $costly.MinutesPerHour }
        if ($null -eq $cost) {
            Add-Result battery 'the model: not computed, because the quiet measure or a measure with transcribed audio is missing'
        } else {
            $check = @($typical, $talk) | Where-Object { $_ -and $_ -ne $costly } | Select-Object -First 1
            $predicted = if ($check) { "; for the other measure it predicts $([math]::Round($quiet.PercentPerHour + $cost * $check.MinutesPerHour, 2)) % per hour, and $($check.PercentPerHour) % per hour was measured" } else { '' }
            # The baseline includes the audio that Kestrel transcribed in the quiet room, near 0 when the room was quiet.
            Add-Result battery "the model: % per hour = $($quiet.PercentPerHour) + $cost x the minutes of audio that Kestrel transcribes in an hour$predicted; the quiet measure, the baseline, had $($quiet.MinutesPerHour) minutes of transcribed audio per hour"
        }
    } finally {
        Complete-Restore $timeoutRestore
    }
}

$plan = @(
    @{ Name = 'install'; Minutes = 10; Title = 'install Kestrel, the speech models with both of Kokoro''s models and the fixture app'; Run = 'Invoke-InstallStep' }
    @{ Name = 'earbuds'; Minutes = 8; Title = 'the earbud microphone, with the owner''s Galaxy Buds next to the speaker'; Run = 'Invoke-EarbudStep' }
    @{ Name = 'voice-tests'; Minutes = 40; Title = 'the voice device tests, among them the keyword spotter''s settings and Kokoro''s two models'; Run = 'Invoke-VoiceTestStep' }
    @{ Name = 'failures'; Minutes = 1; Title = 'Kestrel''s logged failures since the voice tests'; Run = 'Invoke-FailuresStep' }
    @{ Name = 'listen-check'; Minutes = 12; Title = 'listen-check.ps1 with a restart of the phone, which it unlocks with the test PIN'; Run = 'Invoke-ListenCheckStep' }
    @{ Name = 'battery-rules'; Minutes = 10; Title = 'Android''s battery rules without the accessibility service'; Run = 'Invoke-BatteryRulesStep' }
    @{ Name = 'activation'; Minutes = 8; Title = 'the shortcut, the button and the tile, through adb'; Run = 'Invoke-ActivationStep' }
    @{ Name = 'assistant'; Minutes = 5; Title = 'Kestrel as the assistant without its accessibility service'; Run = 'Invoke-AssistantStep' }
    @{ Name = 'spotter'; Minutes = 16; Title = '"Hey Kestrel" and played speech with three settings of the keyword spotter'; Run = 'Invoke-SpotterStep' }
    @{ Name = 'voice'; Minutes = 20; Title = 'Kestrel''s keywords, interruptions and "Stop Kestrel" from the speaker'; Run = 'Invoke-VoiceStep' }
    @{ Name = 'barge-in'; Minutes = 20; Title = 'Kestrel''s own voice at its microphone against "Kestrel" from the speaker, with the assistant volume (stream 11)'; Run = 'Invoke-BargeInStep' }
    @{ Name = 'memory'; Minutes = 12; Title = 'memory with Piper and Kokoro, and a cold start through the tile'; Run = 'Invoke-MemoryStep' }
    @{ Name = 'battery'; Minutes = 95; Title = 'three battery measures of 30 minutes'; Run = 'Invoke-BatteryStep' }
    @{ Name = 'failures-late'; Minutes = 1; Title = 'Kestrel''s logged failures since listen-check'; Run = 'Invoke-LateFailuresStep' }
)

$completed = $false
$stopped = $false
$how = @(if ($DryRun) { 'as a dry run' }; if ($Unattended) { 'without the owner' }) -join ', '
Add-Result session "started on $(if ($isEmulator) { 'kestrel-api37' } else { 'the Galaxy A71' })$(if ($how) { ", $how" })"
try {
    foreach ($entry in $plan) {
        if ($Step -and $entry.Name -notin $Step) { continue }
        Write-Host "Step $($entry.Name), about $($entry.Minutes) minutes: $($entry.Title)."
        Initialize-Session
        if (-not (Confirm-Unlocked)) {
            Add-Result $entry.Name 'skipped: the phone is locked, and the session could not unlock it'
            continue
        }
        try {
            # An "isn't responding" dialog, such as Chrome's after the providers' Custom Tab tests, would stay over every check.
            $anr = Close-AnrDialog -Serial $Serial
            if ($anr) { Add-Result $entry.Name $anr }
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
        # The restores that are left, the newest first, so that the screen, the hooks' tasks and Kestrel's mode come last.
        $left = @($pending.ToArray())
        [array]::Reverse($left)
        Invoke-Restore $left
        # The owner's rule: a finished run leaves the A71 locked, so that its screen does not burn in; the shared emulator stays awake.
        if (-not $isEmulator) {
            try { Invoke-SettingsCommand -Serial $Serial -Command 'input keyevent KEYCODE_SLEEP' | Out-Null } catch { Write-Warning "Could not lock $Serial. Press its power button." }
        }
    } finally {
        if (-not ($completed -or $stopped)) { Add-SessionResult -Path $resultsFile -Step session -Text 'stopped, as by Ctrl+C' }
        # The last messages: what the script could not put back, and how to do it by hand.
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
