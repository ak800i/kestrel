# The A71 session of the instant commands (plan 3C, made automatic by plan 4D; design spec, sections 18.3 and 19.1). Each
# check gives Kestrel its command as a transcript with a tag of its own (the text pass, hook H1), or in the air pass plays
# the command's generated clip through this computer's speakers, which stand next to the phone, and lets the phone match
# it to the expected text (hook H2). It then reads the result of exactly that command by its tag, never Kestrel's newest
# command, and checks on the device what the command did, through the hooks screen-has, field and state and Android's
# dumps, never by asking the owner (process rule 12). During the text pass Kestrel hears only a silent stand-in
# microphone (voice-clip -Quiet), so that no sound of the room starts a command. When the phone is locked, and after
# "lock screen", the script unlocks the A71 itself with the owner's test PIN, which he allows the sessions to use: it
# reads the PIN from the key "pin" of corpus\owner\a71-config.json only on the A71, stops at its start with a clear
# message when the file or the key is missing, types it only when Android says clearly that the PIN pad shows, never
# types it again after a typing that did not unlock the phone (the session then skips its later steps), and never writes
# it anywhere. The owner is needed only in the step owner, right after the install: one touch of his finger on the clock
# during a call's countdown (spike S3), which no script can do, and then, while he is still there, the other checks that
# let Kestrel count down to a call, because a countdown that is not cancelled ends with Kestrel pressing Call, which must
# never happen on the A71 while the owner is away (AGENTS.md; process rules 10 and 13). It has one prompt, which beeps and
# says what he does and what the phone shows, and the script then says that he may leave. Kestrel never presses Call on
# purpose: the check runner cancels every countdown that a check did not plan, and scripts\command-test.ps1 checks
# Kestrel's press of Call on the emulator. The steps, about 140
# minutes in all, of which about 3 need the owner:
#   install        installs Kestrel's debug build, its speech models and the fixture app dev.kestrel.testapp, and records
#                  whether a SIM card is in the phone (8 minutes)
#   owner          the owner's finger on the clock during a call's countdown, and on the A71 also the countdowns that
#                  "cancel", "stop" and a tap on the clock end (3 minutes)
#   screen         numbers, labels, the grid, taps, a long press, scrolling, "Which one?", a bare label and "Require
#                  verbs" (6 minutes)
#   swipes         the edge swipes, swiping the home screen's pages, and a slider (3 minutes)
#   notifications  "show notifications", "expand", "collapse" and "dismiss" on Kestrel's notification "Kestrel check"
#                  (2 minutes)
#   global         back, home, Quick Settings, recent apps, the previous app, "lock screen" and the unlock with the test
#                  PIN, the power menu, a screenshot (deleted afterwards), "open apps", and "open" with Samsung's
#                  Calculator, Clock and Calendar (5 minutes)
#   profiles       the users and the profiles that the launcher API lists, as IDs, types and counts, and whether Secure
#                  Folder is on (1 minute)
#   radios         Wi-Fi and Bluetooth off and on, with the route that each command took (2 minutes)
#   sound          volumes, mute, silence, media play and pause on the fixture app's player, magnification and Circle to
#                  Search (6 minutes)
#   text           dictation and editing with the phone's keyboard (Samsung Keyboard on the A71) in the fixture app's
#                  fields, "show password" and "search for" (4 minutes)
#   calls          only without a SIM card: the question "yes, call" with 1234 and with letters in the number field, answered
#                  "no"; and, with the owner's consent in corpus\owner\a71-config.json, the contacts permission at first
#                  use, in a dry run the calls to the test contact "Kestreltest One" cancelled by voice and by a tap (on the
#                  A71 they ran in the step owner), "Which one?" for "Kestreltest One" and "Kestreltest Two", and "copy
#                  this phone number"; a contact of the owner's whose name starts with "Kestreltest" skips them
#                  (6 minutes)
#   timing         the snapshots that Kestrel builds during 20 commands in Settings, and the transcription time with the
#                  screen's labels as boost words (design spec, section 8.3) (6 minutes)
#   air            first the loudness at the phone of this computer's clips (hook H7) and the transcription time through
#                  the air, from the end of the end-of-speech pause to the final text, timed on the phone (spec 5.3);
#                  then the steps screen to text again with the Kokoro clips and with the Piper clips (45 minutes)
#   corpus         every line of corpus\commands.txt that is marked for the owner, in both voices: first fed in directly
#                  to the phone's recogniser with Kestrel off (voice-eval), then through the air, only transcribed and
#                  resolved on the phone (voice-expect -NoRun), never acted on, both judged with the line's group, its
#                  scene's labels and the contacts: whether it matched, its word errors, whether its command is right, the
#                  stray and split utterances and the times; the commands' shares fed in and through the air with their
#                  Wilson intervals, the one-word lines apart (design spec, section 5.3); and the lines that only the air
#                  lost, played again with Kestrel off, recorded (voice-record) and fed in (45 minutes)
# The results go into corpus\owner\commands-session-<time>.txt, which git ignores; neither it nor the console ever hold what
# Kestrel heard or screen text: the hooks answer only kinds, counts, flags, numbers and hashes. The script never sends "yes,
# call" (process rule 10), answers every question that a check did not plan with "no", cancels every countdown that a
# check did not plan. It puts back what it changes, also when a step fails or Ctrl+C stops it: Kestrel's notices, its contacts
# permission, its mode Off, its own microphone, the accessibility settings, the keyboard, Wi-Fi and Bluetooth, the sound
# mode (through Kestrel's own "silence" or "unmute the ringer" where Android 13 has no shell command for it), the volumes,
# the brightness, a magnification, the notification "Kestrel check", "Require verbs", the screenshots that it took, a call,
# the test contacts, the owner's clipboard around the checks that copy (hook clipboard), the fixture app when it was not
# installed before, and the setting "Stay awake"; every warning at its end says how to put a thing back by hand. It
# refuses every device but the A71 and kestrel-api37. -Unattended runs without the owner and records his items as
# skipped. -DryRun runs the session on
# kestrel-api37: it unlocks with 'wm dismiss-keyguard' and needs no PIN, taps the clock in place of the owner's finger, deletes
# the rows of its calls in the call log, and plays no sound: its air pass and corpus pass say each
# clip's text into Kestrel's stand-in microphone (voice-clip -Text), through the same speech path on the phone, and its
# host audio input is off, and back as the emulator started with it at its end. -Step runs only the named steps, also as
# one text separated by commas.
# It needs PowerShell 7.3 or newer, as scripts\hook.ps1 does. After Ctrl+C, it ends with exit code 1.
# Example: scripts\a71-commands.ps1 -Serial R58N12345AB
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [switch]$DryRun,
    [switch]$Unattended,
    [string[]]$Step
)
$ErrorActionPreference = 'Stop'

# The steps that -Step names, split at commas, as pwsh -File passes '-Step text,calls' as one text. Throws for a name that
# is no step.
function Get-SessionSteps([string[]]$Step, [string[]]$Names) {
    $chosen = @($Step | ForEach-Object { "$_" -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    $unknown = @($chosen | Where-Object { $_ -notin $Names })
    if ($unknown) { throw "Unknown steps: $($unknown -join ', '). The steps are: $($Names -join ', ')." }
    $chosen
}

# Whether the answer of 'getprop gsm.sim.state' says that no SIM card is in the phone: at least one slot says ABSENT, and
# no slot says anything else. A phone with a SIM card can say NOT_READY or UNKNOWN while its radio starts or is off, and
# an empty answer (a phone that does not set the property, or a read that gave nothing) says nothing, so neither counts.
function Test-SimAbsent([string]$State) {
    $slots = @("$State".Trim() -split ',' | ForEach-Object { $_.Trim() })
    @($slots | Where-Object { $_ -eq 'ABSENT' }).Count -gt 0 -and @($slots | Where-Object { $_ -notin @('', 'ABSENT') }).Count -eq 0
}

# The users of 'pm list users', with their types from 'dumpsys user', as 'id=10 type=android.os.usertype.profile.MANAGED
# flags=1030'. A user's name, which can be the owner's, is left out.
function Get-UserSummary([string[]]$Users, [string[]]$Dump) {
    $types = @{}
    $current = $null
    foreach ($line in $Dump) {
        if ($line -match 'UserInfo\{(\d+):') { $current = $Matches[1] }
        if ($null -ne $current -and $line -match '^\s*Type:\s*(\S+)') {
            $types[$current] = $Matches[1]
            $current = $null
        }
    }
    foreach ($line in $Users) {
        if ($line -match 'UserInfo\{(\d+):.*:([0-9a-fA-F]+)\}') {
            $type = if ($types.ContainsKey($Matches[1])) { $types[$Matches[1]] } else { 'unknown' }
            "id=$($Matches[1]) type=$type flags=$($Matches[2])"
        }
    }
}

# Whether the users of Get-UserSummary hold Samsung's Secure Folder: a user whose type names Knox's Secure Folder.
function Test-SecureFolder([string[]]$Users) {
    @($Users | Where-Object { $_ -match 'type=\S*(securefolder|knox)' }).Count -gt 0
}

# The IDs of the screenshots in the answer of 'content query ... --projection _id:_display_name', whose lines read
# 'Row: 0 _id=123, _display_name=Screenshot_20261120_101500.png'. Other images are the owner's, and stay.
function Get-NewScreenshotIds([string[]]$Rows) {
    foreach ($line in $Rows) {
        if ($line -match '\b_id=(\d+), _display_name=Screenshot') { [long]$Matches[1] }
    }
}

# The value at the share $Share (0.5 for the median, 0.9 for the 90th percentile) of some numbers, as voice-eval's summary
# takes it, or 0 without any.
function Get-Percentile([double[]]$Values, [double]$Share) {
    if (-not $Values -or $Values.Count -eq 0) { return 0 }
    $sorted = @($Values | Sort-Object)
    $sorted[[int][math]::Floor(($sorted.Count - 1) * $Share)]
}

# The number of squares of a grid size of the hook state, such as 32 for '8x4', or 0 for 'none'.
function Get-GridCells([string]$Size) {
    if ($Size -notmatch '^(\d+)x(\d+)$') { return 0 }
    [int]$Matches[1] * [int]$Matches[2]
}

# The magnification of the hook state, such as '2.00@540,1200', as its scale and centre; 'none' is the scale 1.
function ConvertFrom-Magnification([string]$Text) {
    if ($Text -notmatch '^([\d.]+)@(-?\d+),(-?\d+)$') { return [pscustomobject]@{ Scale = 1.0; X = $null; Y = $null } }
    [pscustomobject]@{ Scale = [double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture); X = [int]$Matches[2]; Y = [int]$Matches[3] }
}

# A stream's volume and its range from 'cmd media_session volume --stream <stream> --get', whose line reads '[V] volume is
# 7 in range [0..25]'. Throws when the answer has no such line.
function Get-VolumeRange([string[]]$Lines) {
    foreach ($line in $Lines) {
        if ($line -match 'volume is (\d+) in range \[(\d+)\.\.(\d+)\]') {
            return [pscustomobject]@{ Index = [int]$Matches[1]; Min = [int]$Matches[2]; Max = [int]$Matches[3] }
        }
    }
    throw "Not an answer of cmd media_session volume: '$($Lines -join ' ')'"
}

# The first of $Candidates that the answers of 'pm list packages <package>' list, or $null.
function Select-InstalledApp([string[]]$Listed, [string[]]$Candidates) {
    foreach ($candidate in $Candidates) {
        if ($Listed -contains "package:$candidate") { return $candidate }
    }
    $null
}

# New totals of the corpus pass for one voice.
function New-AirTotals {
    [pscustomobject]@{
        Clips = 0; Matched = 0; Words = 0; Errors = 0; Commands = 0; Right = 0; Stray = 0; Split = 0
        Millis = [System.Collections.Generic.List[double]]::new()
        Missed = [System.Collections.Generic.List[string]]::new()
    }
}

# Adds an answer of 'voice-expect -Result' (ConvertFrom-HookPairs) for the line $Id to the totals of the corpus pass. A
# line that did not match, or whose command was wrong, is listed by its id; a line's id and text are public.
function Add-AirResult($Totals, $Answer, [string]$Id) {
    $Totals.Clips++
    if (-not $Answer) {
        $Totals.Missed.Add($Id)
        return
    }
    $Totals.Words += [int]$Answer['words']
    $Totals.Errors += [int]$Answer['errors']
    $Totals.Stray += [int]$Answer['stray']
    $Totals.Split += [int]$Answer['split']
    if ($Answer['matched'] -eq 'true') {
        $Totals.Matched++
        $Totals.Millis.Add([double]$Answer['transcribed'] - [double]$Answer['closed'])
    }
    if ($Answer['right'] -in '0', '1') {
        $Totals.Commands++
        if ($Answer['right'] -eq '1') { $Totals.Right++ }
    }
    if ($Answer['matched'] -ne 'true' -or $Answer['right'] -eq '0') { $Totals.Missed.Add($Id) }
}

# The line of the results for the corpus pass of one voice.
function Format-AirTotals([string]$Voice, $Totals) {
    $missed = if ($Totals.Missed.Count -gt 0) { $Totals.Missed -join ', ' } else { 'none' }
    "${Voice}: $($Totals.Clips) clips, $($Totals.Matched) matched; word error rate $(Format-Percent $Totals.Errors $Totals.Words) % " +
    "($($Totals.Errors) errors in $($Totals.Words) words); commands right $($Totals.Right) of $($Totals.Commands) " +
    "($(Format-Percent $Totals.Right $Totals.Commands) %); $($Totals.Stray) stray and $($Totals.Split) split utterances; " +
    "transcription median $(Get-Percentile $Totals.Millis 0.5) ms, 90th percentile $(Get-Percentile $Totals.Millis 0.9) ms; " +
    "not matched or wrong: $missed"
}

$stepNames = @('install', 'owner', 'screen', 'swipes', 'notifications', 'global', 'profiles', 'radios', 'sound', 'text', 'calls',
    'timing', 'air', 'corpus')
$Step = Get-SessionSteps -Step $Step -Names $stepNames
# Before any command reaches a device: a dry run answers for the owner, so it must never reach a phone.
if ($DryRun -and $Serial -notlike 'emulator-*') { throw 'A dry run runs only on the emulator kestrel-api37.' }

. "$PSScriptRoot\lib\Kestrel.ps1"
. "$PSScriptRoot\lib\Corpus.ps1"
. "$PSScriptRoot\lib\Session.ps1"
. "$PSScriptRoot\lib\Speaker.ps1"
. "$PSScriptRoot\lib\Oracle.ps1"
. "$PSScriptRoot\lib\AutoCheck.ps1"
Use-KestrelToolchain

$owner = Join-Path $PSScriptRoot '..\corpus\owner'
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'
$resultsFile = if ($DryRun) { Join-Path $owner "commands-dry-run-$stamp.txt" } else { Join-Path $owner "commands-session-$stamp.txt" }
# A dry run gives its test contacts the emulator's made-up number, as plan 3C's dry runs did.
$config = if ($DryRun) {
    [pscustomobject]@{ CallNumber = '0641234567'; TestContacts = $true; KeepA71Profile = $null; SpeakerTargetDb = -26.0 }
} else {
    Read-A71Config (Join-Path $owner 'a71-config.json')
}
$corpusFile = Join-Path $PSScriptRoot '..\corpus\commands.txt'
# The labels of the corpus lines' scenes and the contacts, which the step corpus gives the hooks as voice-eval does.
$corpusScenes = Read-CorpusScenes -Path (Join-Path $PSScriptRoot '..\corpus\scenes.txt')
$corpusContacts = @(Read-CorpusContacts -Path (Join-Path $PSScriptRoot '..\corpus\scenes.txt'))
# The clips that the step corpus leaves out, by voice, with the reason (plan 4E).
$leftOut = @{
    kokoro = @{}
    piper = @{ c163 = 'Piper''s clip says its number as whole numbers, not digit by digit (plan 3C, Task 7)' }
}
$clipTexts = @{}
foreach ($row in @(Read-Corpus -Path $corpusFile) + @(Read-Corpus -Path (Join-Path $PSScriptRoot '..\corpus\checks.txt'))) { $clipTexts[$row.Id] = $row.Text }
$generated = Join-Path $PSScriptRoot '..\corpus\generated'
$synthFile = Join-Path $owner 'commands-synth.wav'
$remote = Get-EvalFolder
$service = 'dev.kestrel/dev.kestrel.app.KestrelAccessibilityService'
$testApp = 'dev.kestrel.testapp'
$streams = @(0, 2, 3, 4, 5)
$gradleUsed = $false
$prepared = $false
# Values that a check keeps for a later one, such as the title hash of Settings' first page; the checks' tests read them
# here, because a test runs inside the runner.
$marks = @{}
# The pass: 'text', or the voice of the air pass (kokoro or piper, or clip in a dry run), and its counts.
$pass = 'text'
$passCounts = [ordered]@{}
# The microphone that Kestrel hears: 'quiet' (the silent stand-in) or 'own'; $null when not known.
$microphone = $null
# The gain at which this computer's clips reach the phone at the owner's target, and the clock's offset, once the air
# step has measured them.
$gain = $null
$clock = $null
$lastPlay = $null
$play = if ($DryRun) {
    # The clip's text in Kestrel's voice into its stand-in microphone, through the whole speech path on the phone.
    { param($Clip) Invoke-Hook voice-clip @{ Text = $Clip.Text } | Out-Null }
} else {
    { param($Clip) $script:lastPlay = Invoke-Clip -Path $Clip.Path -Gain $script:gain }
}

# Refuses every device but the A71 and kestrel-api37; the daily phone would need -AllowDailyPhone, which this script
# never passes.
Assert-TestDevice -Serial $Serial
$isEmulator = $KestrelTestDevices[$Serial].Pattern -eq 'kestrel-api37'
if ($DryRun -and -not $isEmulator) { throw 'A dry run runs only on the emulator kestrel-api37.' }
$adb = Get-Adb
# The owner's configuration with the A71's test PIN, only on the A71: a missing file or key stops the session here,
# before any step. Only Unlock-TestPhone and Send-TestPin read the PIN, when they type it.
$pinConfig = if ($isEmulator) { $null } else { Join-Path $owner 'a71-config.json' }
if ($pinConfig) { Send-TestPin -ConfigPath $pinConfig -CheckOnly }

# One item of the owner's step: this computer beeps, and the prompt says what he does and what the phone shows; Enter
# starts it, s skips it and q stops the session. A dry run answers $DryRunAnswer itself, and an unattended run skips it.
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

function Add-Result([string]$StepName, [string]$Text) {
    Add-SessionResult -Path $resultsFile -Step $StepName -Text $Text
    Write-Host "${StepName}: $Text"
}

# Runs a command on the device and fails when it fails; it runs only on the device that Assert-TestDevice accepted.
function Invoke-Checked([string]$Command) { Invoke-SettingsCommand -Serial $Serial -Command $Command }

# Reads one line of the device's answer, without spaces around it.
function Read-DeviceValue([string]$Command) { ((Invoke-Checked $Command) -join "`n").Trim() }

# Runs scripts\hook.ps1 with a command and its parameters, which a hashtable names, and returns the answer as one text.
function Invoke-Hook([string]$Command, [hashtable]$With = @{}) {
    (& "$PSScriptRoot\hook.ps1" -Serial $Serial -Command $Command @With) -join "`n"
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

# Turns Kestrel off, which it also stores, and waits until its voice session has closed.
function Stop-Kestrel {
    Invoke-Hook voice @{ Send = 'off' } | Out-Null
    if ((Wait-Mode off 30) -lt 0) { throw 'Kestrel did not turn off within 30 s.' }
}

# Wakes and unlocks the phone when needed, turns Kestrel on, from the front, where Android lets it start its microphone
# service, and goes back to the home screen.
# The text pass, and every pass of a dry run, makes Kestrel hear only the silent stand-in microphone (voice-clip -Quiet),
# so that no sound of the room starts a command; the air pass gives Kestrel its own microphone back (voice-clip -Clear). A
# change of the microphone counts from Kestrel's next start, so Kestrel turns off first. Loading the models takes up to a
# minute on the A71.
function Start-Kestrel {
    # Kestrel records only while the screen is on and the phone unlocked, and a run without the owner can start, or go on
    # after a restore of "Stay awake", with the phone locked.
    if ((Test-KeyguardShowing @(Invoke-Checked 'dumpsys activity activities')) -ne $false -or (Get-Wakefulness @(Invoke-Checked 'dumpsys power')) -ne 'Awake') {
        if (-not (Unlock-Phone)) { throw 'The phone is locked, and the session could not unlock it.' }
    }
    $wanted = if ($script:pass -eq 'text' -or $DryRun) { 'quiet' } else { 'own' }
    $state = Get-VoiceState
    if ($script:microphone -ne $wanted) {
        if ($state -and $state.mode -ne 'off') {
            Stop-Kestrel
            $state = Get-VoiceState
        }
        Invoke-Hook voice-clip $(if ($wanted -eq 'quiet') { @{ Quiet = $true } } else { @{ Clear = $true } }) | Out-Null
        $script:microphone = $wanted
    }
    if ($state -and $state.mode -eq 'paused') { Invoke-Hook voice @{ Send = 'resume' } | Out-Null }
    if (-not $state -or $state.mode -eq 'off') {
        Invoke-Checked 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
        Invoke-Hook voice @{ Send = 'toggle' } | Out-Null
        # Kestrel's own restore after its process ended may have started listening just before the toggle, which then
        # paused it.
        Start-Sleep -Seconds 1
        $after = Get-VoiceState
        if ($after -and $after.mode -eq 'paused') { Invoke-Hook voice @{ Send = 'resume' } | Out-Null }
    }
    if ((Wait-Mode listening 120) -lt 0) { throw 'Kestrel did not start listening within 120 s.' }
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
}

# What the script has still to put back. Each restore enters the list before its change, and leaves it when it worked, or
# when a step undid the change itself (Remove-Restore). $ByHand says how to put it back by hand, for the warning at the end.
$pending = [System.Collections.Generic.List[object]]::new()

function Register-Restore([string]$What, [string]$ByHand, $Data, [scriptblock]$Action) {
    $restore = [pscustomobject]@{ What = $What; ByHand = $ByHand; Data = $Data; Action = $Action; Reason = 'the session ended before it' }
    $pending.Add($restore)
    $restore
}

# Leaves out a restore whose change a step has undone itself.
function Remove-Restore($Restore) { $pending.Remove($Restore) | Out-Null }

# Runs a restore that is still pending, and keeps it pending, with the reason, when it fails. The owner's "q" at a prompt
# stops the session, as it does in a step; at the session's end (-Ending) the restore keeps it as its reason instead, so
# that the other restores still run and the script ends with its warning, not with an error.
function Complete-Restore($Restore, [switch]$Ending) {
    if (-not $pending.Contains($Restore)) { return }
    try {
        & $Restore.Action $Restore.Data | Out-Null
        $pending.Remove($Restore) | Out-Null
    } catch {
        $Restore.Reason = $_.Exception.Message
        if ($_.Exception.Message -eq 'The owner stopped the session.' -and -not $Ending) { throw }
    }
}

# Runs the restores at the session's end in order, each in the finally block of the one before, so that a stop such as
# Ctrl+C during one of them still runs the others.
function Invoke-Restore([object[]]$Restores, [int]$Index = 0) {
    if ($Index -ge $Restores.Count) { return }
    try {
        Complete-Restore $Restores[$Index] -Ending
    } finally {
        Invoke-Restore $Restores ($Index + 1)
    }
}

# Whether Kestrel may read the contacts.
function Test-ContactsGranted {
    @(Get-GrantedPermissions -Dump (Invoke-Checked 'dumpsys package dev.kestrel') -Permissions @('android.permission.READ_CONTACTS')).Count -gt 0
}

# Before the first step that runs: what every step may change is saved, Kestrel's accessibility service is on, and the
# screen stays on while the phone is plugged in. Registered first, so that it comes back last; Kestrel's mode Off comes
# back after the accessibility settings, so that Kestrel is still on to turn off, and the sound mode before it, so that
# Kestrel's own command can set it.
function Initialize-Session {
    if ($script:prepared) { return }
    $script:prepared = $true
    # First of all, so that it comes back last, after the restores whose changes make Kestrel post its notices about
    # problems, such as the accessibility settings: the script takes them away through the debug hook notices.
    Register-Restore 'Kestrel''s notices in the notification shade' 'Pull down the notification shade and swipe away Kestrel''s notices, such as that its accessibility service is off.' $null {
        Assert-TestDevice -Serial $Serial
        Invoke-Hook notices @{ Clear = $true } | Out-Null
        # A notice that Kestrel posts a moment after the restores before this one, as when its accessibility service went off.
        Start-Sleep -Seconds 2
        $left = (ConvertFrom-HookPairs (Invoke-Hook notices @{ Clear = $true }))['notices']
        if ($left -ne '0') { throw "$left of them are still in the shade" }
    } | Out-Null
    # Second, so that it comes back just before the notices and after Kestrel's mode Off, because revoking a permission
    # ends Kestrel's process; the notices' hook then starts the process again, which is harmless.
    Register-Restore "Kestrel's contacts permission" 'Settings > Apps > Kestrel > Permissions > Contacts' (Test-ContactsGranted) {
        param($Data)
        Assert-TestDevice -Serial $Serial
        $now = Test-ContactsGranted
        if ($now -and -not $Data) { Invoke-Checked 'pm revoke dev.kestrel android.permission.READ_CONTACTS' | Out-Null }
        if ($Data -and -not $now) { Invoke-Checked 'pm grant dev.kestrel android.permission.READ_CONTACTS' | Out-Null }
    } | Out-Null
    Register-Restore "Kestrel's mode Off" "Run: scripts\hook.ps1 -Serial $Serial voice -Send off" $null { Stop-Kestrel } | Out-Null
    Register-Restore "Kestrel's own microphone" "Run: scripts\hook.ps1 -Serial $Serial voice-clip -Clear" $null {
        Invoke-Hook voice-clip @{ Clear = $true } | Out-Null
    } | Out-Null
    $accessibility = Get-AccessibilitySettings -Serial $Serial
    Register-Restore 'the accessibility settings' 'Put back the accessibility services, the shortcut and the button in Settings > Accessibility.' $accessibility {
        param($Data)
        Assert-TestDevice -Serial $Serial
        if (-not (Restore-AccessibilitySettings -Serial $Serial -Saved $Data)) { throw 'see the warning above' }
    } | Out-Null
    $keyboard = Read-DeviceValue 'settings get secure default_input_method'
    Register-Restore 'the keyboard' 'Settings > General management > Keyboard list and default' $keyboard {
        param($Data)
        Assert-TestDevice -Serial $Serial
        if ($Data -notin 'null', '' -and (Read-DeviceValue 'settings get secure default_input_method') -ne $Data) {
            Invoke-Checked "ime set $Data" | Out-Null
        }
    } | Out-Null
    $radios = [ordered]@{ Wifi = Read-DeviceValue 'settings get global wifi_on'; Bluetooth = Read-DeviceValue 'settings get global bluetooth_on' }
    Register-Restore 'Wi-Fi and Bluetooth' 'Turn Wi-Fi and Bluetooth back as they were in Quick Settings.' $radios {
        param($Data)
        Assert-TestDevice -Serial $Serial
        $wifi = if ($Data.Wifi -eq '0') { 'disabled' } else { 'enabled' }
        Invoke-Checked "cmd wifi set-wifi-enabled $wifi" | Out-Null
        $bluetooth = if ($Data.Bluetooth -eq '0') { 'disable' } else { 'enable' }
        Invoke-Checked "cmd bluetooth_manager $bluetooth" | Out-Null
    } | Out-Null
    $sound = [ordered]@{ Ringer = Read-DeviceValue 'settings get global mode_ringer'; Volumes = [ordered]@{} }
    # The streams are keys as texts: an ordered dictionary takes an integer as a position, not as a key.
    foreach ($stream in $streams) {
        $sound.Volumes["$stream"] = Get-StreamVolume -Lines (Invoke-Checked "cmd media_session volume --stream $stream --get")
    }
    # The sound mode first and the volumes second, so that the volumes come back first: on a phone, setting the ring volume
    # also sets the sound mode, so the sound mode's check must come last. Android 13 has no shell command for the sound
    # mode, so there Kestrel's own command sets it (map C64), which Kestrel may do through AudioManager.
    Register-Restore 'the sound mode' 'Set the sound mode back in Quick Settings: sound, vibrate or mute.' $sound.Ringer {
        param($Data)
        Assert-TestDevice -Serial $Serial
        $modes = @{ '0' = 'SILENT'; '1' = 'VIBRATE'; '2' = 'NORMAL' }
        if ((Read-DeviceValue 'settings get global mode_ringer') -eq $Data -or -not $modes.Contains($Data)) { return }
        Invoke-Checked "cmd audio set-ringer-mode $($modes[$Data])" | Out-Null
        $command = @{ '1' = 'silence'; '2' = 'unmute the ringer' }
        if ((Read-DeviceValue 'settings get global mode_ringer') -ne $Data -and $command.Contains($Data)) {
            # A step such as timing may have left Kestrel off.
            Start-Kestrel
            Invoke-Hook voice @{ Send = 'heard'; Text = $command[$Data]; Tag = (New-CheckTag) } | Out-Null
            Start-Sleep -Seconds 3
        }
        if ((Read-DeviceValue 'settings get global mode_ringer') -ne $Data) { throw 'neither the shell nor Kestrel could set the sound mode' }
    } | Out-Null
    Register-Restore 'the volumes' 'Set the volumes back in Settings > Sounds and vibration > Volume.' $sound.Volumes {
        param($Data)
        Assert-TestDevice -Serial $Serial
        foreach ($stream in $Data.Keys) { Invoke-Checked "cmd media_session volume --stream $stream --set $($Data[$stream])" | Out-Null }
    } | Out-Null
    Register-Restore "the clips in Kestrel's folder files/eval" "Run: & '$adb' -s $Serial shell 'rm -f $remote/*.wav'" $null {
        Assert-TestDevice -Serial $Serial
        Invoke-Checked "rm -f $remote/*.wav" | Out-Null
    } | Out-Null
    Register-Restore 'the clip that voice-synth made on this computer' "Delete $synthFile." $null {
        if (Test-Path -LiteralPath $synthFile) { Remove-Item -LiteralPath $synthFile -Force }
    } | Out-Null
    $stayOn = Read-DeviceValue 'settings get global stay_on_while_plugged_in'
    Register-Restore 'the setting "Stay awake"' "Run: & '$adb' -s $Serial shell settings put global stay_on_while_plugged_in $stayOn" $stayOn {
        param($Data)
        Assert-TestDevice -Serial $Serial
        $command = if ($Data -in 'null', '') { 'settings delete global stay_on_while_plugged_in' } else { "settings put global stay_on_while_plugged_in $Data" }
        Invoke-Checked $command | Out-Null
    } | Out-Null
    Invoke-Checked 'svc power stayon true' | Out-Null
    if ($DryRun) {
        # The emulator's microphone carries this computer's sound. The restore puts back the state that the emulator
        # started with (Get-EmulatorHostMicrophone): off without a window, as scripts\start-emulator.ps1 starts it.
        Disable-EmulatorHostMicrophone -Serial $Serial | Out-Null
    }
    # Kestrel's accessibility service, next to the owner's other services.
    $enabled = if ($accessibility.Contains('enabled_accessibility_services')) { "$($accessibility['enabled_accessibility_services'])" } else { 'null' }
    $services = @(@($enabled -split ':') | Where-Object { $_ -and $_ -ne 'null' -and $_ -ne $service }) + $service
    if (-not (Set-SecureSetting -Serial $Serial -Name 'enabled_accessibility_services' -Value ($services -join ':'))) { throw 'Could not turn on Kestrel''s accessibility service.' }
    if (-not (Set-SecureSetting -Serial $Serial -Name 'accessibility_enabled' -Value '1')) { throw 'Could not turn on accessibility.' }
    $marks.Home = Get-HomePackage @(Invoke-Checked 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME')
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

# The verdict of a check's test: whether it passed, and what it tested, never screen text.
function New-Verdict([bool]$Passed, [string]$Detail = '') { [pscustomobject]@{ Passed = $Passed; Detail = $Detail } }

# The package of the activity in front.
function Get-Front { Get-ResumedPackage @(Invoke-Checked 'dumpsys activity activities') }

# The answer of the hook screen-has with the filters of $With (plan 4D interfaces, section 5), and its count.
function Get-Screen([hashtable]$With = @{}) { ConvertFrom-HookPairs (Invoke-Hook screen-has $With) }

function Get-Count([hashtable]$With) { [int](Get-Screen $With)['count'] }

# Reads screen-has with the filters of $With until it counts an element, at most $Seconds, because a page that was just
# opened builds its screen a moment later, and returns the last answer.
function Wait-Element([hashtable]$With, [double]$Seconds = 5) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($true) {
        $answer = Get-Screen $With
        if ([int]$answer['count'] -gt 0 -or $watch.Elapsed.TotalSeconds -ge $Seconds) { return $answer }
        Start-Sleep -Milliseconds 250
    }
}

# The screen layer's state: numbers, labels, the grid and its size, the magnification and the keyboard.
function Get-LayerState { ConvertFrom-HookPairs (Invoke-Hook state) }

# The focused text field, or with $ViewId the first field whose view ID holds it, compared on the phone with $Expected.
function Get-Field([string]$Expected, [string]$ViewId = '') {
    $with = @{ Expected = $Expected }
    if ($ViewId) { $with.ViewId = $ViewId }
    ConvertFrom-HookPairs (Invoke-Hook field $with)
}

function Test-Field([string]$Expected) { New-Verdict ((Get-Field $Expected)['equal'] -eq 'true') 'the field''s text' }

# Sets the fixture's field $ViewId to $Text through the hook type (Android's action that sets a field's text, which puts
# the cursor at its end), so that a check of the step text starts from its known text, whatever an earlier dictation
# left in the field. It finds the field by the number that the numbers overlay gives it.
function Set-FixtureField([string]$ViewId, [string]$Text) {
    Invoke-Hook overlay @{ Show = 'numbers' } | Out-Null
    try {
        $number = (Wait-Element @{ Package = $testApp; ViewId = $ViewId })['number']
        if ($number -notmatch '^\d+$') { throw "The field $ViewId has no number of the overlay." }
        Invoke-Hook type @{ Number = [int]$number; Text = $Text } | Out-Null
    } finally {
        Invoke-Hook overlay @{ Show = 'none' } | Out-Null
    }
}

function Get-Volume([int]$Stream) { Get-VolumeRange @(Invoke-Checked "cmd media_session volume --stream $Stream --get") }

# One UI's temporary brightness from 0 to 1 in the output of 'dumpsys display' (mTemporaryScreenBrightness), which its
# brightness slider sets for an accessibility step and does not save, or $null when the screen has none (NaN, or no
# such line, as on AOSP, whose slider saves each step).
function Get-TemporaryBrightness([string[]]$Dump) {
    foreach ($line in $Dump) {
        if ($line -match '^\s*mTemporaryScreenBrightness=(\d+(?:\.\d+)?)\s*$') { return [double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture) }
    }
    $null
}

# The brightness that the screen shows, from 0 to 1: One UI's temporary brightness when it has one, else the saved
# setting screen_brightness (0 to 255), which One UI's temporary brightness starts from.
function ConvertTo-Brightness([string[]]$Dump, [string]$Setting) {
    $temporary = Get-TemporaryBrightness $Dump
    if ($null -ne $temporary) { return $temporary }
    if ($Setting -notmatch '^\d+$') { throw 'Android did not give the brightness.' }
    [int]$Setting / 255
}

function Get-Brightness { ConvertTo-Brightness @(Invoke-Checked 'dumpsys display') (Read-DeviceValue 'settings get system screen_brightness') }

# Moves a stream's volume one step away from the end that a command up ($Up) or down would hit, so that the command can
# change it; the ring volume stays above its lowest step, which would change the sound mode.
function Set-VolumeRoom([int]$Stream, [bool]$Up) {
    $volume = Get-Volume $Stream
    $floor = if ($Stream -eq 2) { $volume.Min + 1 } else { $volume.Min }
    $to = if ($Up -and $volume.Index -ge $volume.Max) { $volume.Max - 1 } elseif (-not $Up -and $volume.Index -le $floor) { $floor + 1 } else { $null }
    if ($null -ne $to) { Invoke-Checked "cmd media_session volume --stream $Stream --set $to" | Out-Null }
}

# Whether Kestrel said the fixed sentence for the tagged command (said= of voice-state -Tag holds hashes only).
function Test-Said($State, [string]$Text) { Test-HashedText -Hashes $State['said'] -Text $Text }

function Test-Home { New-Verdict ((Get-Front) -eq $marks.Home) 'the home screen in front' }

function Test-NewSettingsPage {
    $title = (Get-Screen)['appTitle']
    New-Verdict ((Get-Front) -eq 'com.android.settings' -and $title -ne $marks.FirstTitle -and $title -ne 'none') 'a new page of Settings'
}

function Test-FirstSettingsPage { New-Verdict ((Get-Screen)['appTitle'] -eq $marks.FirstTitle) 'the first page of Settings' }

# While the numbers show, the number of a row on Settings' first page that opens a page of Settings itself: One UI's
# "Connections", AOSP's "Network & internet", or else "Display"; $null when none has one.
function Get-SettingsRowNumber {
    foreach ($label in 'Connections', 'Network & internet', 'Display') {
        $number = (Get-Screen @{ Package = 'com.android.settings'; Contains = $label; State = 'clickable' })['number']
        if ("$number" -match '^\d+$') { return $number }
    }
    $null
}

# The first visible row (its hash) of Settings' own list and the list's bounds, which the scroll checks compare, or
# 'none'. Only Settings' elements count: an install of Kestrel while its accessibility service is on makes Android add it
# to the accessibility button's targets, and kestrel-api37 then shows SystemUI's floating accessibility menu on the right
# edge, whose list came first on the screen, and which a scroll of Settings does not move. On One UI, the first scroll
# only collapses the large title above the list, which moves the list's top and keeps its first row.
function Get-ListRow {
    $list = Get-Screen @{ Package = 'com.android.settings'; State = 'scrollable' }
    if ("$($list['first'])" -in '', 'none') { return 'none' }
    "$($list['first'])|$($list['bounds'])"
}

# "scroll down": Settings' list shows another first row, or has moved, since before ($marks.ListTop); $marks.ListMoved
# says so for "scroll to top".
function Test-ScrolledDown {
    $row = Get-ListRow
    $marks.ListMoved = $row -notin '', 'none' -and $marks.ListTop -notin '', 'none' -and $row -ne $marks.ListTop
    New-Verdict $marks.ListMoved 'another first row, or the list moved'
}

# "scroll to top": the first row and the list's bounds of before "scroll down" again, which proves the scroll back only
# when "scroll down" moved the list; otherwise the check counts as failed and says that it was not checked.
function Test-ScrolledToTop {
    if (-not $marks.ListMoved) { return New-Verdict $false 'not checked, because "scroll down" did not move the list' }
    New-Verdict ((Get-ListRow) -eq $marks.ListTop) 'the first row and the list where they were'
}

function Test-NoCall { -not (Test-CallActive -Registry (Invoke-Checked 'dumpsys telephony.registry')) }

function Test-Cancelled($State) {
    New-Verdict ($State['outcome'] -eq 'cancelled' -and (Test-HashedText -Hashes $State['shown'] -Text 'Cancelled') -and (Test-NoCall)) 'cancelled, with "Cancelled" on the bar, and no call'
}

# Opens Settings on its first page, in a new task, so that an earlier check's page does not stay.
function Open-Settings([string]$Action = 'android.settings.SETTINGS') {
    Invoke-Checked "am start -a $Action -f 0x10008000" | Out-Null
    Start-Sleep -Seconds 2
}

# Opens a page of the fixture app, such as EditActivity, with the extras that follow it.
function Open-Fixture([string]$Activity, [string]$Extras = '') {
    Invoke-Checked ("am start -n $testApp/.$Activity $Extras".Trim()) | Out-Null
    Start-Sleep -Seconds 2
}

# Taps the centre of the bounds that screen-has gave ('left,top,right,bottom').
function Invoke-TapAt([string]$Bounds) {
    if ($Bounds -notmatch '^(-?\d+),(-?\d+),(-?\d+),(-?\d+)$') { throw 'The element has no bounds on the screen.' }
    Invoke-Checked "input tap $([int](([int]$Matches[1] + [int]$Matches[3]) / 2)) $([int](([int]$Matches[2] + [int]$Matches[4]) / 2))" | Out-Null
}

# Taps the status bar's clock, or, when Kestrel's snapshot has no clock node (kestrel-api37 has none), the middle of the
# top 3 % of the screen, which is the status bar; $script:clockTarget says which, for the check's line. It does not wait,
# because it must act inside Kestrel's 4 s countdown.
function Invoke-ClockTap {
    $bounds = (Get-Screen @{ Package = 'com.android.systemui'; ViewId = 'clock' })['bounds']
    if ($bounds -match '^(-?\d+),(-?\d+),(-?\d+),(-?\d+)$') {
        $script:clockTarget = 'the clock'
        Invoke-TapAt $bounds
        return
    }
    $size = Read-DeviceValue 'wm size'
    if ($size -notmatch '(\d+)x(\d+)\s*$') { throw 'The screen has no clock node, and wm size gave no size.' }
    $script:clockTarget = 'the status bar, because the snapshot has no clock node'
    Invoke-Checked "input tap $([int]([int]$Matches[1] / 2)) $([int]([int]$Matches[2] * 0.015))" | Out-Null
}

# A check's verdict with the target of its tap on the clock (Invoke-ClockTap) added to its detail.
function Add-ClockTarget($Verdict) {
    $Verdict.Detail = "$($Verdict.Detail); the tap's target: $(if ($script:clockTarget) { $script:clockTarget } else { 'none, because no tap ran' })"
    $Verdict
}

function Invoke-Home { Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null }

function Invoke-Beep { try { [console]::Beep(880, 300) } catch { Write-Verbose 'This console cannot beep.' } }

# The clip of a check for the air pass: the clip of the line $Id of corpus\commands.txt or corpus\checks.txt in the pass's
# voice, or for 'synth' a clip of $Say that voice-synth makes now in that voice; a dry run needs only the text.
function Get-PassClip([string]$Id, [string]$Say) {
    if ($Id -eq 'synth') {
        if ($DryRun) { return [pscustomobject]@{ Voice = $pass; Text = $Say; Path = $null } }
        Invoke-Hook voice-synth @{ Text = $Say; File = 'gen-synth'; Voice = $pass } | Out-Null
        Invoke-AdbCopy -Serial $Serial -Direction pull -From "$remote/gen-synth.wav" -To $synthFile
        Invoke-Checked "rm -f $remote/gen-synth.wav" | Out-Null
        # voice-synth keeps its voice loaded in Kestrel's process; the recogniser needs that memory.
        Invoke-Hook voice-synth @{ Close = $true } | Out-Null
        return [pscustomobject]@{ Voice = $pass; Text = $Say; Path = $synthFile }
    }
    if (-not $clipTexts.ContainsKey($Id)) { throw "No line $Id in corpus\commands.txt or corpus\checks.txt." }
    $path = Join-Path $generated "$pass\$Id.wav"
    if (-not $DryRun -and -not (Test-Path -LiteralPath $path)) { throw "The clip $Id of $pass is missing; make it with scripts\make-corpus.ps1." }
    [pscustomobject]@{ Voice = $pass; Text = $clipTexts[$Id]; Path = $path }
}

# Counts a check of the current pass, for the lines at the session's end.
function Add-PassCount([bool]$Passed) {
    if (-not $passCounts.Contains($pass)) { $passCounts[$pass] = [pscustomobject]@{ Run = 0; Passed = 0 } }
    $passCounts[$pass].Run++
    if ($Passed) { $passCounts[$pass].Passed++ }
}

# One check of a step on the current pass (Invoke-AutoCheck): the text pass sends $Say, or $Send sends it; the air pass
# plays the clip $Clip (Get-PassClip) instead. A check without a clip (''), or one that $Send sends, runs only in the text
# pass. $Verify gets the tagged state and what $Before read; a test that needs only the second reads it as $args[1]. Its
# line goes into the results, and the pass's counts rise.
function Test-Command(
    [string]$StepName,
    [string]$Say,
    [string]$Clip,
    [scriptblock]$Verify,
    [scriptblock]$Before = { $null },
    [string]$Name = '',
    [string]$Answer = '',
    [scriptblock]$Respond = $null,
    [string]$RespondAt = 'question',
    [double]$RespondDelay = 0,
    [scriptblock]$Send = $null,
    [double]$TimeoutSeconds = 15
) {
    $label = if ($Name) { $Name } else { $Say }
    # The screen can show a command's effect a moment after the command has settled, so a test that did not pass is read
    # again every 250 ms for up to 3 s before it counts. The tests only read the device. The name differs from Verify,
    # because the runner calls the wrapper inside its own scope, where $Verify is the wrapper itself.
    $checkVerify = $Verify
    $retried = {
        param($State, $Context, $Waited)
        $watch = [Diagnostics.Stopwatch]::StartNew()
        while ($true) {
            $verdict = ConvertTo-Verdict (& $checkVerify $State $Context $Waited)
            if ($verdict.Passed -or $watch.Elapsed.TotalSeconds -ge 3) { return $verdict }
            Start-Sleep -Milliseconds 250
        }
    }
    $with = @{ Name = $label; Text = $Say; Verify = $retried; Before = $Before; Answer = $Answer; RespondAt = $RespondAt; RespondDelay = $RespondDelay; TimeoutSeconds = $TimeoutSeconds }
    if ($Respond) { $with.Respond = $Respond }
    if ($pass -eq 'text') {
        if ($Send) { $with.Send = $Send }
    } elseif (-not $Clip -or $Send) {
        return
    }
    try {
        # A clip that cannot be made fails only its own check.
        if ($pass -ne 'text') {
            $with.Clip = Get-PassClip -Id $Clip -Say $Say
            $with.Play = $play
        }
        $result = Invoke-AutoCheck @with
    } catch {
        if ($_.Exception.Message -eq 'The owner stopped the session.') { throw }
        # The hooks' and scripts' errors name only commands, files and classes, never what Kestrel heard.
        Add-Result $StepName "$label [$pass]: failed: $(("$($_.Exception.Message)" -split "`r?`n")[0])"
        Add-PassCount $false
        return
    }
    Add-Result $StepName $result.Line
    Add-PassCount $result.Passed
}

# Saves the owner's clipboard in Kestrel's memory before a check that copies (hook clipboard, plan 4D interfaces, section
# 6), and registers its return; Android 13 lets only the app in front read the clipboard, so Kestrel's settings come to
# the front first. When Kestrel could not read it, the result line says that the clipboard may keep the check's test text.
function Save-Clipboard([string]$StepName) {
    Invoke-Checked 'am start -n dev.kestrel/dev.kestrel.app.MainActivity' | Out-Null
    foreach ($try in 1..20) {
        if ((ConvertFrom-HookPairs (Invoke-Hook voice-times))['settingsInFront'] -eq 'true') { break }
        Start-Sleep -Milliseconds 500
    }
    $saved = ConvertFrom-HookPairs (Invoke-Hook clipboard @{ Save = $true })
    # 'restored=false' means that Kestrel held no copy (its save read nothing), which the line below has said already;
    # 'restored=failed' keeps the copy in Kestrel and the check's text on the clipboard. A restore never empties it.
    $restore = Register-Restore 'the owner''s clipboard' 'Copy something of your own again; the clipboard holds the session''s test text.' $StepName {
        param($Data)
        Assert-TestDevice -Serial $Serial
        if ((ConvertFrom-HookPairs (Invoke-Hook clipboard @{ Restore = $true }))['restored'] -eq 'failed') {
            Add-Result $Data 'Android refused to put the owner''s clipboard back, so it may hold the check''s test text'
            throw 'Android refused to put the saved clip back'
        }
    }
    # 'held' is a copy that an earlier save still holds, which this restore puts back; it is no failure.
    if ($saved['clip'] -eq 'unknown') {
        Add-Result $StepName 'Kestrel could not read the clipboard (it was empty, or Android refused), so it saved nothing, and the clipboard may hold the check''s test text afterwards'
    } elseif ($saved['clip'] -eq 'other') {
        Add-Result $StepName 'the clipboard held a picture or a file, which Android may refuse to put back'
    }
    Invoke-Home
    $restore
}

# Unlocks the phone: the emulator with 'wm dismiss-keyguard', the A71 with the owner's test PIN (Unlock-TestPhone, which
# types it only when Android says clearly that the PIN pad shows, and at most once after a typing that did not work).
# Returns whether the phone is unlocked and awake. An unlock that did not work stops every later unlock of the session,
# and the session then skips its later steps, which all need an unlocked phone; the result line says whether the phone
# did not show its PIN pad, so that nothing was typed, or the unlock failed, never with the PIN.
function Unlock-Phone {
    if ($script:unlockFailed) { return $false }
    $why = 'the phone stayed locked'
    $unlocked = if ($isEmulator) {
        Invoke-Checked 'input keyevent KEYCODE_WAKEUP' | Out-Null
        Invoke-Checked 'wm dismiss-keyguard' | Out-Null
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
    $unlocked = $unlocked -and (Wait-Unlocked 30)
    if (-not $unlocked) {
        $script:unlockFailed = $true
        Add-Result session "$why; the session tries no more unlocks and skips the steps that need an unlocked phone"
    }
    $unlocked
}

# Waits up to $Seconds until the phone is awake and its keyguard is gone. Returns whether it is.
function Wait-Unlocked([int]$Seconds) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $activities = @(Invoke-Checked 'dumpsys activity activities')
        if ((Test-KeyguardShowing $activities) -eq $false -and (Get-Wakefulness @(Invoke-Checked 'dumpsys power')) -eq 'Awake') { return $true }
        Start-Sleep -Seconds 1
    } while ($watch.Elapsed.TotalSeconds -lt $Seconds)
    $false
}

# The first name of the test contacts, which no contact of the owner's should have: "call Kestreltest" must find only the
# two test contacts and ask "Which one?". A contact whose name a check says exactly wins over first names, and the A71 has
# a contact named "Ana", which made Kestrel count down to it instead of asking.
$testFirstName = 'Kestreltest'

# Why the checks with the test contacts cannot run, from the rows of the phone's raw contacts whose name starts with the
# test contacts' first name (projection account_name): a contact that is not the session's own would answer "call
# Kestreltest" itself, or be offered with them. $null when no such contact is there. It never reads a contact's name.
function Get-ContactClash([string[]]$Rows) {
    $others = @($Rows | Where-Object { $_ -match '^Row: \d+ ' -and $_ -notmatch '\baccount_name=kestrel-test(,|\s*$)' })
    if ($others.Count -gt 0) { "$($others.Count) of the phone's other contacts have a name that starts with ""$testFirstName""" }
}

function Read-ContactClash {
    Get-ContactClash @(Invoke-Checked "content query --uri content://com.android.contacts/raw_contacts --projection account_name --where deleted=0\ AND\ display_name\ LIKE\ \'$testFirstName%\'")
}

# Adds the test contacts "Kestreltest One" and "Kestreltest Two", both with the owner's number, to the local account
# kestrel-test, which has no sync adapter, so that they never leave the phone, and registers their deletion;
# command-test.ps1 deletes the same account on the emulator. Returns the contact ID of "Kestreltest One". The owner's own
# contacts are never used.
function Add-TestContacts {
    if ($script:contactsRestore -and $pending.Contains($script:contactsRestore)) { return $script:testContact }
    $where = "account_name=\'kestrel-test\'"
    $script:contactsRestore = Register-Restore 'the deletion of the test contacts' "Run: & '$adb' -s $Serial shell content delete --uri 'content://com.android.contacts/raw_contacts?caller_is_syncadapter=true' --where ""account_name='kestrel-test'""" $where {
        # The filter comes as the restore's data: a restore runs where this function's variables are gone.
        param($Data)
        Assert-TestDevice -Serial $Serial
        if (-not $Data) { throw 'the restore has no filter for the test contacts' }
        Invoke-Checked "content delete --uri 'content://com.android.contacts/raw_contacts?caller_is_syncadapter=true' --where $Data" | Out-Null
    }
    try {
        foreach ($name in "$testFirstName One", "$testFirstName Two") {
            Invoke-Checked 'content insert --uri content://com.android.contacts/raw_contacts --bind account_type:s:kestrel-test --bind account_name:s:kestrel-test' | Out-Null
            $id = Get-LargestRowId -Rows (Invoke-Checked "content query --uri content://com.android.contacts/raw_contacts --projection _id --where $where")
            Invoke-Checked "content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$id --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:'$name'" | Out-Null
            Invoke-Checked "content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$id --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:$($config.CallNumber) --bind data2:i:2" | Out-Null
        }
        $rows = @(Invoke-Checked "content query --uri content://com.android.contacts/raw_contacts --projection _id:contact_id --where $where")
    } catch {
        # The device's error repeats the command, which holds the owner's number, so the session says only what failed.
        throw 'Could not add the test contacts.'
    }
    $first = @($rows | Where-Object { $_ -match '\b_id=(\d+), contact_id=(\d+)' } | ForEach-Object { [pscustomobject]@{ Raw = [long]$Matches[1]; Contact = [long]$Matches[2] } } | Sort-Object Raw) | Select-Object -First 1
    if (-not $first) { throw 'Android did not list the test contacts.' }
    $script:testContact = $first.Contact
    $script:testContact
}

# Whether the calls' checks may run now: no SIM card is in the phone, and the owner's settings give a number that is fine
# to call. Returns $null when they may, or the reason why not.
function Get-CallBlock {
    if (-not ($DryRun -or (Test-SimAbsent -State (Read-DeviceValue 'getprop gsm.sim.state')))) { return 'a SIM card is in the phone, or the phone does not say that none is there' }
    if (-not $config.CallNumber) { return 'corpus\owner\a71-config.json names no number that is fine to call' }
    $null
}

function Invoke-InstallStep {
    # On Android 13 an update ends Kestrel's process with the reason of the owner's stop, so Kestrel would stay off after it.
    try { Stop-Kestrel } catch { Write-Host 'Kestrel did not answer, so it is probably not installed yet.' }
    $script:gradleUsed = $true
    & "$PSScriptRoot\install.ps1" -Serial $Serial
    if ($LASTEXITCODE -ne 0) { throw 'scripts\install.ps1 failed.' }
    & "$PSScriptRoot\push-models.ps1" -Serial $Serial
    Initialize-TestApp
    Add-Result install 'Kestrel installed, the speech models copied, and the fixture app installed'
    $absent = Test-SimAbsent -State (Read-DeviceValue 'getprop gsm.sim.state')
    Add-Result install "SIM card: $(if ($absent) { 'none' } else { 'present, or the phone does not say that none is there' })"
    $script:microphone = $null
    Start-Kestrel
    Add-Result install 'Kestrel listens'
}

# The checks that let Kestrel count down to a call and end the countdown (map C113, C114, C116): "cancel" and "stop" as
# soon as the countdown shows, because it lasts 4 s and each read of the hooks takes about half a second, and a tap on
# the clock through Android's input system. A countdown that none of them ends ends with Kestrel pressing Call, so on the
# A71 they run only in the step owner, while the owner is there. Needs the test contacts and Kestrel's contacts permission.
function Invoke-CountdownChecks([string]$StepName) {
    Test-Command $StepName 'call Kestreltest One' '' -Name 'call Kestreltest One, then "cancel" as soon as the countdown shows' -Answer 'cancel' -RespondAt countdown -TimeoutSeconds 20 { param($State) Test-Cancelled $State }
    Invoke-Home
    Test-Command $StepName 'call Kestreltest One' '' -Name 'call Kestreltest One, then "stop" as the countdown starts' -Answer 'stop' -RespondAt countdown -TimeoutSeconds 20 { param($State) Test-Cancelled $State }
    Invoke-Home
    # A tap through Android's input system; the owner's finger is the step owner's first check.
    $script:clockTarget = $null
    Test-Command $StepName 'call Kestreltest One' '' -Name 'call Kestreltest One, then a tap on the clock during the countdown' -RespondAt countdown -Respond { Invoke-ClockTap } -TimeoutSeconds 20 { param($State) Add-ClockTarget (Test-Cancelled $State) }
    Invoke-Home
}

# The owner's item (map section 6): his finger on the clock during a call's countdown (C115), which only a real finger can
# check (spike S3, design spec 14.1), and on the A71, while he is still there, the other countdowns (Invoke-CountdownChecks):
# a countdown that is not cancelled ends with Kestrel pressing Call, which must never happen while he is away. The
# unlocks of map C49 need him no longer: the test PIN does them.
function Invoke-OwnerStep {
    Start-Kestrel
    $block = Get-CallBlock
    if (-not $block -and -not $config.TestContacts) { $block = 'corpus\owner\a71-config.json does not give the owner''s consent to the test contacts' }
    if (-not $block) { $block = Read-ContactClash }
    if ($block) {
        Add-Result owner "the checks during a call's countdown: skipped: $block"
    } elseif ((Request-OwnerItem 'Item 1 of 1, about 3 minutes, of which 1 with your hands: come to the A71, which shows its home screen. When you press Enter, Kestrel calls the test contact "Kestreltest One", opens the phone app and counts down 4 seconds on its bar. As soon as the countdown shows on Kestrel''s bar, touch the clock at the top of the A71''s screen with one finger, never a button of the phone app; this computer beeps about half a second after the countdown shows. Touch it at once, within about 2 seconds of when it shows; if you are later, the session cancels the countdown itself, and Kestrel does not call. The countdown should stop, and Kestrel''s bar should say "Cancelled". Then stay next to the A71 for about 2 minutes without touching it: Kestrel calls "Kestreltest One" three more times, and the session ends each countdown itself, by voice and with a tap. The A71 has no SIM card, so no call can start. (Later checks copy test text; the session saves your clipboard first and puts it back afterwards.) Press Enter to start, or s to skip.') -eq 'skip') {
        Add-Result owner 'the checks during a call''s countdown: skipped'
    } else {
        Add-TestContacts | Out-Null
        Invoke-Checked 'pm grant dev.kestrel android.permission.READ_CONTACTS' | Out-Null
        # Only the dry run deletes the call-log rows of its calls, so only it reads where the call log ends.
        $callLog = if ($DryRun) { Get-LargestRowId -Rows (Invoke-Checked 'content query --uri content://call_log/calls --projection _id') }
        $call = Register-Restore 'the end of the call that the session placed' 'End the call on the phone app''s screen.' $null {
            Assert-TestDevice -Serial $Serial
            if (-not (Stop-DeviceCall -Serial $Serial)) { throw 'a call is still active' }
        }
        # On the A71 the response waits up to 1.2 s after the beep for the owner's touch to end the countdown, and cancels
        # it itself when it still runs: with the runner's reads and the beep, his touch must end the countdown about 1.5 to
        # 2.5 s after it shows, so the prompt asks for it at once, within about 2 s, and the cancel still comes before Kestrel's 4 s run out. The response runs inside the runner
        # (Invoke-AutoCheck), whose variable $tag is the check's tag.
        $touch = if ($DryRun) {
            { Invoke-ClockTap }
        } else {
            {
                Invoke-Beep
                Write-Host 'Touch the clock at the top of the A71''s screen now.'
                $after = Wait-TaggedState -Tag $tag -Until { param($State) $State['phase'] -ne 'countdown' } -TimeoutSeconds 1.2
                if ($after['phase'] -eq 'countdown') {
                    Invoke-Hook voice @{ Send = 'heard'; Text = 'cancel'; Tag = (New-CheckTag) } | Out-Null
                    throw 'no touch ended the countdown in time, so the script cancelled it'
                }
            }
        }
        $script:clockTarget = $null
        Test-Command owner 'call Kestreltest One' '' -Name 'call Kestreltest One, then the owner''s finger on the clock' -RespondAt countdown -Respond $touch -TimeoutSeconds 20 { param($State) $verdict = Test-Cancelled $State; if ($DryRun) { Add-ClockTarget $verdict } else { $verdict } }
        Invoke-Home
        # The dry run has these in the step calls.
        if (-not $DryRun) { Invoke-CountdownChecks owner }
        Complete-Restore $call
        if ($DryRun) { Invoke-Checked "content delete --uri content://call_log/calls --where _id\>$callLog" | Out-Null }
        Invoke-Home
    }
    if (-not $DryRun -and -not $Unattended) {
        Invoke-Beep
        # Only when this run goes on without him; the owner's guide runs -Step owner first and has more items for him.
        if (-not $Step -or @($Step | Where-Object { $_ -notin 'install', 'owner' }).Count -gt 0) {
            Write-Host 'You may leave now. The rest of the session runs without you for about 2 hours; the A71''s screen stays on, and it works by itself. Please do not touch it.'
        } else {
            Write-Host 'Your item in this script is done. Please stay for your next item.'
        }
    }
}

function Invoke-ScreenStep {
    Start-Kestrel
    Open-Settings
    $marks.FirstTitle = (Get-Screen)['appTitle']
    Test-Command screen 'show numbers' c014 { New-Verdict ([int](Get-LayerState)['numbers'] -gt 0) 'numbers on the screen' }
    # The number of a row that opens a page of Settings itself: One UI's first numbers belong to its edge panel, the
    # search button and the row of the Samsung account, whose page is another app's and belongs to the owner's account.
    # The clip k001 says a fixed number, so the air pass says this one through voice-synth.
    $row = Get-SettingsRowNumber
    if ($row) {
        Test-Command screen "tap $row" synth -Name 'tap the number of a row of Settings' { Test-NewSettingsPage }
        Test-Command screen 'go back' c003 { Test-FirstSettingsPage }
    } else {
        Add-Result screen 'tap the number of a row of Settings: skipped, because no row of Settings that the check knows had a number'
    }
    Test-Command screen 'hide numbers' c015 { New-Verdict ([int](Get-LayerState)['numbers'] -eq 0) 'no numbers' }
    Test-Command screen 'show labels' c016 { New-Verdict ((Get-LayerState)['labels'] -eq 'on') 'labels on' }
    Test-Command screen 'hide labels' c141 { New-Verdict ((Get-LayerState)['labels'] -eq 'off') 'labels off' }
    Test-Command screen 'show grid' c025 { $layer = Get-LayerState; New-Verdict ($layer['grid'] -eq 'on' -and (Get-GridCells $layer['gridSize']) -gt 0) "a grid of $($layer['gridSize'])" }
    Test-Command screen 'more squares' c192 -Before { Get-GridCells (Get-LayerState)['gridSize'] } -Verify {
        $Before = $args[1]
        $now = Get-GridCells (Get-LayerState)['gridSize']
        New-Verdict ($now -gt $Before) "$Before squares, then $now"
    }
    Test-Command screen 'fewer squares' c193 -Before { Get-GridCells (Get-LayerState)['gridSize'] } -Verify {
        $Before = $args[1]
        $now = Get-GridCells (Get-LayerState)['gridSize']
        New-Verdict ($now -lt $Before -and $now -gt 0) "$Before squares, then $now"
    }
    Test-Command screen 'hide grid' c194 { New-Verdict ((Get-LayerState)['grid'] -eq 'off') 'no grid' }
    $marks.ListTop = Get-ListRow
    $marks.ListMoved = $false
    Test-Command screen 'scroll down' c022 { Test-ScrolledDown }
    Test-Command screen 'scroll to top' c023 { Test-ScrolledToTop }
    Test-Command screen 'tap Display' k002 { Test-NewSettingsPage }
    Test-Command screen 'go back' c003 { Test-FirstSettingsPage }
    Test-Command screen 'Display' k003 { Test-NewSettingsPage }
    Test-Command screen 'go home' c004 { Test-Home }
    # The label stays on the phone: screen-has sends "long press <label>" with the tag itself (map C21).
    Test-Command screen 'long press {label}' '' -Name 'long press an app on the home screen' -Send {
        param($Tag)
        Invoke-Hook screen-has @{ Package = $marks.Home; ClassName = 'TextView'; State = 'clickable'; Template = 'long press {label}'; Tag = $Tag } | Out-Null
    } -Verify { New-Verdict ((Get-Count @{ Package = $marks.Home; Label = 'App info' }) -gt 0) 'the app''s menu with "App info"' }
    Test-Command screen 'go back' c003 { New-Verdict ((Get-Count @{ Package = $marks.Home; Label = 'App info' }) -eq 0) 'the menu closed' }
    # Kestrel's own test screen with two buttons named "Delete" (debug builds only) stands for a screen where two buttons
    # share a name; the answer goes as text also in the air pass.
    foreach ($which in @(@{ Number = '1'; Text = 'You tapped the first button.' }, @{ Number = '2'; Text = 'You tapped the second button.' })) {
        $marks.Twin = $which
        Invoke-Checked 'am start -n dev.kestrel/dev.kestrel.app.debug.TwinButtonsActivity' | Out-Null
        Start-Sleep -Seconds 2
        Test-Command screen 'tap Delete' k004 -Name "tap Delete, then $($which.Number)" -Answer $which.Number -Verify {
            New-Verdict ((Get-Count @{ Label = $marks.Twin.Text }) -gt 0 -and [int](Get-LayerState)['numbers'] -eq 0) 'that button tapped, and its numbers gone'
        }
    }
    Invoke-Home
    # "Require verbs" through the debug hook owner-options, registered before the change, so that a failure or a stop puts
    # it back as it was before the step.
    $before = (ConvertFrom-HookPairs (Invoke-Hook owner-options))['requireVerbs']
    $verbs = Register-Restore 'the setting "Require verbs"' 'Switch "Require verbs" back on Kestrel''s page "Instant commands" as it was before the session.' $before {
        param($Data)
        Assert-TestDevice -Serial $Serial
        $now = (ConvertFrom-HookPairs (Invoke-Hook owner-options @{ RequireVerbs = $(if ($Data -eq 'on') { 'true' } else { 'false' }) }))['requireVerbs']
        if ($now -ne $Data) { throw 'Kestrel did not take it back' }
    }
    if ((ConvertFrom-HookPairs (Invoke-Hook owner-options @{ RequireVerbs = 'true' }))['requireVerbs'] -eq 'on') {
        # Kestrel takes a change a moment later, as from its settings.
        Start-Sleep -Seconds 2
        Open-Settings
        Test-Command screen 'Display' k003 -Name 'Display, with "Require verbs" on' { New-Verdict ((Get-Screen)['appTitle'] -eq $marks.FirstTitle) 'no page opened' }
    } else {
        Add-Result screen 'Display, with "Require verbs" on: skipped, because Kestrel did not take "Require verbs"'
    }
    Complete-Restore $verbs
    Invoke-Home
}

function Invoke-SwipeStep {
    Start-Kestrel
    Open-Settings
    $marks.FirstTitle = (Get-Screen)['appTitle']
    Test-Command swipes 'tap Display' k002 { Test-NewSettingsPage }
    # With the navigation bar's buttons, Kestrel says why the swipe does nothing (plan 4D, Task 5), which passes too.
    Test-Command swipes 'swipe from the left edge' c190 {
        param($State)
        New-Verdict ((Test-FirstSettingsPage).Passed -or (Test-Said $State 'The navigation bar has buttons, so this swipe doesn''t go back. Say ''go back''.')) 'the first page of Settings, or Kestrel''s word on the buttons'
    }
    Test-Command swipes 'swipe from the bottom edge' k005 {
        param($State)
        New-Verdict ((Test-Home).Passed -or (Test-Said $State 'The navigation bar has buttons, so this swipe doesn''t go home. Say ''go home''.')) 'the home screen, or Kestrel''s word on the buttons'
    }
    Invoke-Home
    # The workspace shows only its current page, so the first app on it tells the pages apart.
    $marks.HomePage = (Get-Screen @{ Package = $marks.Home; ViewId = 'workspace' })['first']
    Test-Command swipes 'swipe forwards' c024 { New-Verdict ((Get-Screen @{ Package = $marks.Home; ViewId = 'workspace' })['first'] -ne $marks.HomePage) 'another page of the home screen' }
    Test-Command swipes 'swipe backwards' c189 { New-Verdict ((Get-Screen @{ Package = $marks.Home; ViewId = 'workspace' })['first'] -eq $marks.HomePage) 'the first page again' }
    Open-Settings 'android.settings.DISPLAY_SETTINGS'
    $marks.Slider = 'none'
    Test-Command swipes 'show numbers' c014 {
        $marks.Slider = (Get-Screen @{ ClassName = 'SeekBar' })['number']
        New-Verdict ($marks.Slider -match '^\d+$') 'a number on the brightness slider'
    }
    if ($marks.Slider -match '^\d+$') {
        # Registered before the change, so that a failure or a stop between the two checks still puts it back. One UI
        # applies the slider's accessibility steps as a temporary brightness, which it does not save and which stays after
        # the page closes; a write of the setting ends it, so the restore always writes another value and then the saved
        # one.
        $light = Register-Restore 'the brightness' 'Set the brightness back with the slider in Quick Settings.' (Read-DeviceValue 'settings get system screen_brightness') {
            param($Data)
            Assert-TestDevice -Serial $Serial
            if ($Data -notmatch '^\d+$') { throw 'Android did not give the brightness before the step' }
            Invoke-Checked "settings put system screen_brightness $(if ([int]$Data -ge 255) { [int]$Data - 1 } else { [int]$Data + 1 })" | Out-Null
            Invoke-Checked "settings put system screen_brightness $Data" | Out-Null
            if ((Read-DeviceValue 'settings get system screen_brightness') -ne $Data) { throw 'the setting differs from before' }
            # The display takes the setting a moment after it changes.
            $watch = [Diagnostics.Stopwatch]::StartNew()
            while ($null -ne (Get-TemporaryBrightness @(Invoke-Checked 'dumpsys display'))) {
                if ($watch.Elapsed.TotalSeconds -ge 3) { throw 'the screen keeps a temporary brightness' }
                Start-Sleep -Milliseconds 250
            }
        }
        Test-Command swipes "increment $($marks.Slider)" synth -Name 'increment and the slider''s number' -Before { Get-Brightness } -Verify {
            $Before = $args[1]
            New-Verdict ((Get-Brightness) -gt $Before) 'the brightness rose'
        }
        Test-Command swipes "decrement $($marks.Slider)" synth -Name 'decrement and the slider''s number' -Before { Get-Brightness } -Verify {
            $Before = $args[1]
            New-Verdict ((Get-Brightness) -lt $Before) 'the brightness fell'
        }
        Complete-Restore $light
    }
    Test-Command swipes 'hide numbers' c015 { New-Verdict ([int](Get-LayerState)['numbers'] -eq 0) 'no numbers' }
    Invoke-Home
}

function Invoke-NotificationStep {
    Start-Kestrel
    # Kestrel's own notification "Kestrel check" (hook check-notice), which the restore takes away if Kestrel did not.
    $notice = Register-Restore 'the notification "Kestrel check"' "Run: scripts\hook.ps1 -Serial $Serial check-notice -On false" $null {
        Assert-TestDevice -Serial $Serial
        if ((ConvertFrom-HookPairs (Invoke-Hook check-notice @{ On = 'false' }))['checkNotice'] -ne 'gone') { throw 'it is still in the notification shade' }
    }
    Invoke-Hook check-notice @{ On = 'true' } | Out-Null
    $marks.Row = @{ Package = 'com.android.systemui'; Contains = 'kestrel check' }
    Test-Command notifications 'show notifications' c005 { New-Verdict ((Get-Count $marks.Row) -gt 0) '"Kestrel check" in the shade' }
    Test-Command notifications 'expand Kestrel check' k006 { New-Verdict ((Get-Count ($marks.Row + @{ State = 'collapsible' })) -gt 0) 'the notification offers to collapse' }
    Test-Command notifications 'collapse Kestrel check' k007 { New-Verdict ((Get-Count ($marks.Row + @{ State = 'expandable' })) -gt 0) 'the notification offers to expand' }
    Test-Command notifications 'dismiss Kestrel check' k008 { New-Verdict ((ConvertFrom-HookPairs (Invoke-Hook check-notice))['checkNotice'] -eq 'gone') 'the notification gone' }
    Test-Command notifications 'go home' c004 { Test-Home }
    Complete-Restore $notice
}

function Invoke-GlobalStep {
    Start-Kestrel
    Initialize-TestApp
    Open-Settings
    $marks.FirstTitle = (Get-Screen)['appTitle']
    Test-Command global 'tap Display' k002 { Test-NewSettingsPage }
    Test-Command global 'go back' c003 { Test-FirstSettingsPage }
    Test-Command global 'go home' c004 { Test-Home }
    # AOSP's tiles have view IDs with qs_tile; One UI's have none, but their labels have tile_label.
    Test-Command global 'show quick settings' c006 {
        New-Verdict ((Get-Count @{ Package = 'com.android.systemui'; ViewId = 'qs_tile' }) -gt 0 -or (Get-Count @{ Package = 'com.android.systemui'; ViewId = 'tile_label' }) -gt 0) 'Quick Settings'' tiles'
    }
    Test-Command global 'home' c119 { Test-Home }
    # The recent apps are part of the home app on Android 13 and One UI, and hide the home screen's pages.
    Test-Command global 'show recent apps' c007 { New-Verdict ((Get-Front) -eq $marks.Home -and (Get-Count @{ Package = $marks.Home; ViewId = 'workspace' }) -eq 0) 'the recent apps, without the home screen''s pages' }
    Test-Command global 'go home' c004 { Test-Home }
    Open-Settings
    Open-Fixture 'FixturesActivity'
    Test-Command global 'show previous app' c008 { New-Verdict ((Get-Front) -eq 'com.android.settings') 'Settings in front again' }
    Invoke-Home
    # The test PIN unlocks the phone afterwards (map C48, C49), and the mode then shows whether Kestrel listens again.
    Test-Command global 'lock screen' c009 { New-Verdict ((Get-Wakefulness @(Invoke-Checked 'dumpsys power')) -in 'Asleep', 'Dozing') 'the screen off' }
    $unlocked = Unlock-Phone
    Add-Result global "the unlock after 'lock screen': $(if ($unlocked) { 'unlocked' } else { 'still locked' }); Kestrel's mode afterwards: $((Get-VoiceState).mode)"
    if (-not $unlocked) { throw 'The phone stayed locked after "lock screen".' }
    # Kestrel's voice session opens again a while after the unlock, and the air pass's next check needs it.
    Start-Kestrel
    Invoke-Home
    Test-Command global 'turn off device' c125 { New-Verdict ((Get-Count @{ Package = 'com.android.systemui'; Label = 'Power off' }) -gt 0) 'the power menu' }
    Test-Command global 'go back' c003 { New-Verdict ((Get-Count @{ Package = 'com.android.systemui'; Label = 'Power off' }) -eq 0) 'the power menu closed' }
    # A screen with nothing private on it, and every new screenshot deleted, also after a failure or Ctrl+C.
    Invoke-Checked 'am start -n dev.kestrel/dev.kestrel.app.debug.TwinButtonsActivity' | Out-Null
    Start-Sleep -Seconds 2
    $largest = Get-LargestRowId -Rows (Invoke-Checked 'content query --uri content://media/external/images/media --projection _id')
    $screenshots = Register-Restore 'the deletion of the screenshots that the session took' 'Delete the newest screenshots in Gallery.' ([pscustomobject]@{ Largest = $largest; Deleted = 0 }) {
        param($Data)
        Assert-TestDevice -Serial $Serial
        foreach ($id in @(Get-NewScreenshotIds -Rows (Invoke-Checked "content query --uri content://media/external/images/media --projection _id:_display_name --where _id\>$($Data.Largest)"))) {
            Invoke-Checked "content delete --uri content://media/external/images/media --where _id=$id" | Out-Null
            $Data.Deleted++
        }
    }
    $marks.Largest = $largest
    Test-Command global 'take a screenshot' c010 {
        # The screenshot takes a moment to reach the gallery's list.
        Start-Sleep -Seconds 3
        $new = @(Get-NewScreenshotIds -Rows (Invoke-Checked "content query --uri content://media/external/images/media --projection _id:_display_name --where _id\>$($marks.Largest)"))
        New-Verdict ($new.Count -gt 0) 'a new screenshot'
    }
    Complete-Restore $screenshots
    $deleted = if ($pending.Contains($screenshots)) { "not deleted yet ($($screenshots.Reason)); the session tries again at its end" } else { "deleted: $($screenshots.Data.Deleted)" }
    Add-Result global "the new screenshots: $deleted"
    Invoke-Home
    Test-Command global 'open apps' c002 { New-Verdict ((Get-Count @{ Package = $marks.Home; ViewId = 'workspace' }) -eq 0 -and (Get-Count @{ Package = $marks.Home; State = 'clickable' }) -gt 10) 'the list of all apps' }
    Test-Command global 'go home' c004 { Test-Home }
    # Samsung's own apps on the A71, and Google's on the emulator, never the owner's.
    foreach ($app in @(
            @{ Name = 'Calculator'; Clip = 'k019'; Candidates = @('com.sec.android.app.popupcalculator', 'com.google.android.calculator') }
            @{ Name = 'Clock'; Clip = 'k020'; Candidates = @('com.sec.android.app.clockpackage', 'com.google.android.deskclock') }
            @{ Name = 'Calendar'; Clip = 'k021'; Candidates = @('com.samsung.android.calendar', 'com.google.android.calendar') })) {
        $listed = @(foreach ($candidate in $app.Candidates) { Invoke-Checked "pm list packages $candidate" })
        $marks.App = Select-InstalledApp -Listed $listed -Candidates $app.Candidates
        if (-not $marks.App) {
            Add-Result global "open $($app.Name): skipped, because the device has no such app"
            continue
        }
        Test-Command global "open $($app.Name)" $app.Clip { New-Verdict ((Get-Front) -eq $marks.App) 'the app in front' }
        Invoke-Home
    }
}

function Invoke-ProfileStep {
    $users = @(Get-UserSummary -Users (Invoke-Checked 'pm list users') -Dump (Invoke-Checked 'dumpsys user'))
    Add-Result profiles "Secure Folder: $(if (Test-SecureFolder $users) { 'on' } else { 'off' })"
    foreach ($user in $users) { Add-Result profiles "user $user" }
    # The launcher API's profiles, as Kestrel sees them: IDs, numbers of apps and, from Android 15, types.
    Add-Result profiles "launcher API: $(Invoke-Hook profiles)"
}

function Invoke-RadioStep {
    # Turning Wi-Fi off would cut a connection by wireless debugging, and the session with it.
    if ($Serial -match ':|\._adb-tls-') {
        Add-Result radios 'skipped: the phone is connected by wireless debugging; connect it by USB for this step'
        return
    }
    Start-Kestrel
    foreach ($check in @(
            @{ Say = 'turn off Wi-Fi'; Clip = 'k009'; Setting = 'wifi_on'; On = $false }
            @{ Say = 'turn on Wi-Fi'; Clip = 'c142'; Setting = 'wifi_on'; On = $true }
            @{ Say = 'turn off Bluetooth'; Clip = 'c018'; Setting = 'bluetooth_on'; On = $false }
            @{ Say = 'turn on Bluetooth'; Clip = 'c144'; Setting = 'bluetooth_on'; On = $true })) {
        $marks.Radio = $check
        Test-Command radios $check.Say $check.Clip -TimeoutSeconds 25 {
            param($State)
            # Android writes the setting a moment after the change.
            $on = $null
            for ($i = 0; $i -lt 10; $i++) {
                $on = (Read-DeviceValue "settings get global $($marks.Radio.Setting)") -ne '0'
                if ($on -eq $marks.Radio.On) { break }
                Start-Sleep -Milliseconds 500
            }
            New-Verdict ($on -eq $marks.Radio.On) "Android's $($marks.Radio.Setting), through the route $($State['detail'])"
        }
    }
}

function Invoke-SoundStep {
    Start-Kestrel
    Initialize-TestApp
    # The sound mode Sound: on a phone, a ring volume above 0 sets it (map C64). The restores put back both.
    $ring = Get-Volume 2
    Invoke-Checked "cmd media_session volume --stream 2 --set $([math]::Max($ring.Index, $ring.Min + 1))" | Out-Null
    Add-Result sound "before the step, the ringer mode is $(Read-DeviceValue 'settings get global mode_ringer') (2 is sound)"
    foreach ($check in @(
            @{ Say = 'volume up'; Clip = 'k010'; Stream = 3; Up = $true }
            @{ Say = 'volume down'; Clip = 'c146'; Stream = 3; Up = $false }
            @{ Say = 'turn the ring volume up'; Clip = 'k011'; Stream = 2; Up = $true }
            @{ Say = 'turn the ring volume down'; Clip = 'k012'; Stream = 2; Up = $false }
            @{ Say = 'turn the alarm volume up'; Clip = 'k013'; Stream = 4; Up = $true }
            @{ Say = 'turn the alarm volume down'; Clip = 'c148'; Stream = 4; Up = $false }
            @{ Say = 'turn the call volume up'; Clip = 'k014'; Stream = 0; Up = $true }
            @{ Say = 'turn the call volume down'; Clip = 'k015'; Stream = 0; Up = $false })) {
        $marks.Volume = $check
        Test-Command sound $check.Say $check.Clip -Before {
            Set-VolumeRoom $marks.Volume.Stream $marks.Volume.Up
            (Get-Volume $marks.Volume.Stream).Index
        } -Verify {
            $Before = $args[1]
            $now = (Get-Volume $marks.Volume.Stream).Index
            $moved = if ($marks.Volume.Up) { $now -gt $Before } else { $now -lt $Before }
            New-Verdict $moved "stream $($marks.Volume.Stream) from $Before to $now"
        }
    }
    Test-Command sound 'mute' c151 { New-Verdict ((Test-StreamMuted @(Invoke-Checked 'dumpsys audio')) -eq $true) 'the media stream muted' }
    Test-Command sound 'unmute' c152 { New-Verdict ((Test-StreamMuted @(Invoke-Checked 'dumpsys audio')) -eq $false) 'the media stream not muted' }
    Test-Command sound 'mute the alarm' k016 { param($State) New-Verdict ($State['outcome'] -eq 'failed' -and (Test-Said $State 'Android doesn''t let me mute the alarm volume.')) 'Kestrel said that Android does not let it' }
    Test-Command sound 'silence' c154 { New-Verdict ((Read-DeviceValue 'settings get global mode_ringer') -eq '1') 'the phone on vibrate' }
    Test-Command sound 'unmute the ringer' k017 { New-Verdict ((Read-DeviceValue 'settings get global mode_ringer') -eq '2') 'the sound back on' }
    # The fixture app's player: a quiet tone with an active media session, which plays from its start.
    Open-Fixture 'MediaActivity'
    Invoke-Home
    try {
        Test-Command sound 'media pause' c012 { New-Verdict ((Get-PlaybackState @(Invoke-Checked 'dumpsys media_session') $testApp) -eq 2) 'the fixture''s player paused' }
        Test-Command sound 'media play' c132 { New-Verdict ((Get-PlaybackState @(Invoke-Checked 'dumpsys media_session') $testApp) -eq 3) 'the fixture''s player playing' }
        Test-Command sound 'media pause' c012 { New-Verdict ((Get-PlaybackState @(Invoke-Checked 'dumpsys media_session') $testApp) -eq 2) 'the fixture''s player paused' }
    } finally {
        Invoke-Checked "am force-stop $testApp" | Out-Null
    }
    # Kestrel's mode Off does not end a magnification, and Android ends the magnification that a service set when the
    # service goes off, so this restore turns Kestrel's service off and on once.
    $services = Read-DeviceValue 'settings get secure enabled_accessibility_services'
    $zoom = Register-Restore 'the end of the magnification' 'If the screen is still magnified, turn Kestrel off and on again in Settings > Accessibility > Installed apps.' $services {
        param($Data)
        Assert-TestDevice -Serial $Serial
        $without = @("$Data" -split ':' | Where-Object { $_ -and $_ -ne 'null' -and $_ -ne $service }) -join ':'
        $off = if ($without) { "settings put secure enabled_accessibility_services '$without'" } else { 'settings delete secure enabled_accessibility_services' }
        Invoke-Checked $off | Out-Null
        Start-Sleep -Seconds 1
        if (-not (Set-SecureSetting -Serial $Serial -Name 'enabled_accessibility_services' -Value $Data)) { throw 'Android did not turn Kestrel''s accessibility service on again' }
    }
    try {
        Test-Command sound 'zoom in' c034 { New-Verdict ((ConvertFrom-Magnification (Get-LayerState)['magnification']).Scale -gt 1) 'the screen magnified' }
        Test-Command sound 'pan left' c285 -Before { (ConvertFrom-Magnification (Get-LayerState)['magnification']).X } -Verify {
            $Before = $args[1]
            New-Verdict ((ConvertFrom-Magnification (Get-LayerState)['magnification']).X -lt $Before) 'the magnified area moved left'
        }
        Test-Command sound 'scroll right' c186 -Before { (ConvertFrom-Magnification (Get-LayerState)['magnification']).X } -Verify {
            $Before = $args[1]
            New-Verdict ((ConvertFrom-Magnification (Get-LayerState)['magnification']).X -gt $Before) 'the magnified area moved right'
        }
        Test-Command sound 'zoom out' c284 { New-Verdict ((ConvertFrom-Magnification (Get-LayerState)['magnification']).Scale -le 1) 'the screen at its normal size' }
        Test-Command sound 'magnify' c282 { New-Verdict ((ConvertFrom-Magnification (Get-LayerState)['magnification']).Scale -gt 1) 'the screen magnified' }
        Test-Command sound 'stop magnification' c287 { New-Verdict ((ConvertFrom-Magnification (Get-LayerState)['magnification']).Scale -le 1) 'the screen at its normal size' }
        if ((ConvertFrom-Magnification (Get-LayerState)['magnification']).Scale -le 1) { Remove-Restore $zoom }
    } finally {
        # Also after a failure, so that the later steps run without a magnification.
        if ($pending.Contains($zoom)) {
            Complete-Restore $zoom
            # Android binds the service again a moment after the setting.
            Start-Sleep -Seconds 2
            # A fixed text: the restore's reason can hold the list of the owner's other accessibility services, which the
            # results file never holds; the warning at the end of the session names it.
            $ended = if ($pending.Contains($zoom)) { 'could not end it; the session tries again at its end' } else { 'ended it' }
            Add-Result sound "turning Kestrel's accessibility service off and on, to end any magnification, $ended"
        }
    }
    # Turning the service off and on can pause or stop listening.
    Start-Kestrel
    Test-Command sound 'circle to search' c130 { param($State) New-Verdict ($State['outcome'] -eq 'failed' -and (Test-Said $State 'Circle to Search isn''t available on this phone.')) 'Kestrel said that Circle to Search is not available' }
}

function Invoke-TextStep {
    Start-Kestrel
    Initialize-TestApp
    # Samsung Keyboard on the A71, which the keyboard's restore puts back as the owner had it; the emulator keeps its own.
    $samsung = @(Invoke-Checked 'ime list -s') | Where-Object { $_ -like 'com.sec.android.inputmethod/*' } | Select-Object -First 1
    if ($samsung) { Invoke-Checked "ime set $samsung" | Out-Null }
    # "copy" puts test text on the clipboard, so the owner's clipboard is saved first and put back at the step's end.
    $clipboard = Save-Clipboard text
    # The fixture's field "Notes", which takes several lines.
    Open-Fixture 'EditActivity'
    Invoke-TapAt (Wait-Element @{ Package = $testApp; ViewId = 'text_notes' })['bounds']
    Start-Sleep -Seconds 1
    $one = "HELLO, how are you?`nP123"
    # Each check of a text starts from its known text (Set-FixtureField), so that one misheard dictation fails only its
    # own check; the checks of a selection, a copy, the cursor and undo build on the check before them.
    Test-Command text 'hello comma how are you question mark' c311 { Test-Field 'Hello, how are you?' }
    Test-Command text 'new line' c279 -Before { Set-FixtureField text_notes 'Hello, how are you?' } { Test-Field "Hello, how are you?`n" }
    Test-Command text 'capital p one two three' c314 -Before { Set-FixtureField text_notes "Hello, how are you?`n" } { Test-Field "Hello, how are you?`nP123" }
    Test-Command text 'select hello' k018 -Before { Set-FixtureField text_notes "Hello, how are you?`nP123" } { $field = Get-Field ''; New-Verdict ($field['start'] -eq '0' -and $field['end'] -eq '5') '"Hello" selected' }
    Test-Command text 'uppercase hello' c220 -Before { Set-FixtureField text_notes "Hello, how are you?`nP123" } { Test-Field "HELLO, how are you?`nP123" }
    Test-Command text 'select all' c257 -Before { Set-FixtureField text_notes $one } { $field = Get-Field ''; New-Verdict ($field['start'] -eq '0' -and $field['end'] -eq $field['length'] -and [int]$field['length'] -gt 0) 'all the text selected' }
    Test-Command text 'copy' c031 { param($State) New-Verdict ($State['outcome'] -eq 'done') 'done; the paste checks what it copied' }
    Test-Command text 'go to the end' c244 { $field = Get-Field ''; New-Verdict ($field['start'] -eq $field['length'] -and $field['end'] -eq $field['length']) 'the cursor at the end' }
    Test-Command text 'paste' c032 -Before { Set-FixtureField text_notes $one } { Test-Field ($one + $one) }
    $marks.Pasted = [int](Get-Field '')['length']
    Test-Command text 'delete three words' c237 {
        $marks.Shorter = [int](Get-Field '')['length']
        New-Verdict ($marks.Shorter -lt $marks.Pasted) 'a shorter text'
    }
    Test-Command text 'undo' c028 { Test-Field ($one + $one) }
    Test-Command text 'redo' c205 { New-Verdict ([int](Get-Field '')['length'] -eq $marks.Shorter) 'the shorter text again' }
    Test-Command text 'hide keyboard' c033 { New-Verdict ((Test-InputShown @(Invoke-Checked 'dumpsys input_method')) -eq $false -and (Get-LayerState)['keyboard'] -eq 'hidden') 'no keyboard' }
    Test-Command text 'show keyboard' c276 { New-Verdict ((Test-InputShown @(Invoke-Checked 'dumpsys input_method')) -eq $true) 'the keyboard' }
    # Three test letters in the fixture's password field, never a password.
    Invoke-TapAt (Wait-Element @{ Package = $testApp; ViewId = 'text_password' })['bounds']
    Invoke-Checked 'input text abc' | Out-Null
    Test-Command text 'show password' c160 { New-Verdict ((Get-Count @{ Package = $testApp; ViewId = 'text_show_password'; State = 'checked' }) -gt 0) '"Show password" checked' }
    # "search for" in the fixture's address bar, which behaves as a browser's.
    Open-Fixture 'SearchActivity' '--ez address true'
    Test-Command text 'search for weather tomorrow' c162 { New-Verdict ((Get-Field 'weather tomorrow' 'text_address')['equal'] -eq 'true') 'the search in the address bar' }
    Invoke-Checked "am force-stop $testApp" | Out-Null
    Invoke-Home
    Complete-Restore $clipboard
}

function Invoke-CallStep {
    # The checks here run right before the first call, every time the step runs (research r19; design D16, step 1).
    $block = Get-CallBlock
    if ($block) {
        Add-Result calls "skipped: $block"
        return
    }
    Start-Kestrel
    Initialize-TestApp
    # Only the dry run deletes the call-log rows of its calls, so only it reads where the call log ends.
    $callLog = if ($DryRun) { Get-LargestRowId -Rows (Invoke-Checked 'content query --uri content://call_log/calls --projection _id') }
    # Kestrel never presses Call on purpose (process rules 10 and 13), and the check runner cancels every countdown that a
    # check did not plan, so a check that lets the countdown run out could never pass.
    Add-Result calls 'call the confirmed number: skipped: the runner cancels countdowns, so Kestrel never presses Call here; scripts\command-test.ps1 checks its press of Call on the emulator'
    # The question "yes, call" in the phone app (design spec, section 8.7), answered "no": with a short number, which
    # Kestrel never calls itself, and then with letters in the number field. Never an emergency number, and never "yes,
    # call" (process rule 10).
    Test-Command calls 'call 1234' '' { param($State) New-Verdict ((Test-Said $State 'I opened the phone app with 1 2 3 4. Tap Call yourself.') -and (Get-Field '1234' 'digits')['equal'] -eq 'true') 'the phone app with 1234, and Kestrel said to tap Call' }
    Test-Command calls 'tap call' '' -Name 'tap call, then no' -Answer 'no' { param($State) New-Verdict ($State['outcome'] -eq 'cancelled' -and (Test-NoCall)) 'cancelled, and no call' }
    Invoke-Hook overlay @{ Show = 'numbers' } | Out-Null
    try {
        $field = (Get-Screen @{ ViewId = 'digits' })['number']
        # Letters only, never the digits of an emergency number.
        if ($field -match '^\d+$') { Invoke-Hook type @{ Number = [int]$field; Text = 'abc' } | Out-Null }
    } finally {
        Invoke-Hook overlay @{ Show = 'none' } | Out-Null
    }
    if ($field -match '^\d+$') {
        Test-Command calls 'tap call' '' -Name 'letters in the number field, tap call, then no' -Answer 'no' { param($State) New-Verdict ($State['outcome'] -eq 'cancelled' -and (Test-NoCall)) 'cancelled, and no call' }
    } else {
        Add-Result calls 'letters in the number field: skipped, because the number field has no number of the overlay'
    }
    Invoke-Home
    if (-not $config.TestContacts) {
        Add-Result calls 'the checks with the test contacts: skipped, because corpus\owner\a71-config.json does not give the owner''s consent'
        return
    }
    $clash = Read-ContactClash
    if ($clash) {
        Add-Result calls "the checks with the test contacts: skipped, because $clash"
        return
    }
    $contact = Add-TestContacts
    # Revoking the permission ends Kestrel's process, and with it the silent microphone, so Kestrel starts again after
    # Android has brought it back for its accessibility service.
    Invoke-Checked 'pm revoke dev.kestrel android.permission.READ_CONTACTS' | Out-Null
    Start-Sleep -Seconds 3
    Wait-State { param($State) $State['mode'] -ne 'off' } 10 | Out-Null
    $script:microphone = $null
    Start-Kestrel
    Test-Command calls 'call Kestreltest One' '' -Name 'call Kestreltest One, the contacts permission at first use' -TimeoutSeconds 20 {
        # Kestrel opens its settings, which ask Android for the permission; the script allows it in Android's dialog.
        $dialog = $null
        # In any package: the permission controller's package on One UI is not known yet.
        foreach ($try in 1..20) {
            $dialog = Get-Screen @{ ViewId = 'permission_allow_button' }
            if ([int]$dialog['count'] -gt 0) { break }
            Start-Sleep -Milliseconds 500
        }
        if ($dialog -and [int]$dialog['count'] -gt 0) { Invoke-TapAt $dialog['bounds'] }
        Start-Sleep -Seconds 2
        New-Verdict (Test-ContactsGranted) 'Android''s dialog, and the permission granted'
    }
    # Without the dialog's "Allow" the later checks would only hear that Kestrel needs the permission; the restore puts the
    # permission back as it was.
    if (-not (Test-ContactsGranted)) { Invoke-Checked 'pm grant dev.kestrel android.permission.READ_CONTACTS' | Out-Null }
    Invoke-Home
    $call = Register-Restore 'the end of the call that the session placed' 'End the call on the phone app''s screen.' $null {
        Assert-TestDevice -Serial $Serial
        if (-not (Stop-DeviceCall -Serial $Serial)) { throw 'a call is still active' }
    }
    if ($DryRun) {
        Invoke-CountdownChecks calls
    } else {
        Add-Result calls 'the countdowns that "cancel", "stop" and a tap end: in the step owner, while the owner was at the A71'
    }
    $script:clockTarget = $null
    Test-Command calls "call $testFirstName" '' -Name '"Which one?" for the two test contacts, ended by a tap on the clock' -Respond { Invoke-ClockTap } -TimeoutSeconds 20 {
        param($State)
        Add-ClockTarget (New-Verdict ($State['outcome'] -eq 'cancelled' -and (Test-NoCall)) 'the question ended, and no call')
    }
    Complete-Restore $call
    Invoke-Home
    # The test contact's page in the phone's contacts app, and the number pasted into the fixture's field "Paste here".
    $clipboard = Save-Clipboard calls
    Invoke-Checked "am start -a android.intent.action.VIEW -d content://com.android.contacts/contacts/$contact" | Out-Null
    Start-Sleep -Seconds 2
    Test-Command calls 'copy this phone number' '' { param($State) New-Verdict ($State['outcome'] -eq 'done') 'done; the paste checks what it copied' }
    Open-Fixture 'EditActivity'
    Invoke-TapAt (Wait-Element @{ Package = $testApp; ViewId = 'text_paste' })['bounds']
    Test-Command calls 'paste' '' -Name 'paste the copied number' {
        $field = Get-Field $config.CallNumber 'text_paste'
        # The contacts app may show the number with spaces, which the copy keeps.
        New-Verdict ($field['equal'] -eq 'true' -or [int]$field['length'] -ge $config.CallNumber.Length) 'the number in the field'
    }
    Invoke-Checked "am force-stop $testApp" | Out-Null
    Invoke-Home
    Complete-Restore $clipboard
    Complete-Restore $script:contactsRestore
    # The emulator's calls of this step ended through Android; their rows in the call log go.
    if ($DryRun) { Invoke-Checked "content delete --uri content://call_log/calls --where _id\>$callLog" | Out-Null }
}

function Invoke-TimingStep {
    Start-Kestrel
    Open-Settings
    $marks.FirstTitle = (Get-Screen)['appTitle']
    $before = Invoke-Hook snapshots
    foreach ($round in 1..5) {
        Test-Command timing 'tap Display' '' -Name "tap Display ($round of 5)" { Test-NewSettingsPage }
        Test-Command timing 'go back' '' -Name "go back ($round of 5)" { Test-FirstSettingsPage }
        Test-Command timing 'scroll down' '' -Name "scroll down ($round of 5)" { New-Verdict ((Get-Front) -eq 'com.android.settings') 'Settings in front' }
        Test-Command timing 'scroll to top' '' -Name "scroll to top ($round of 5)" { New-Verdict ((Get-Front) -eq 'com.android.settings') 'Settings in front' }
    }
    $after = Invoke-Hook snapshots
    if ($before -match 'builds=(\d+) millis=(\d+)') {
        $builds0 = [int]$Matches[1]
        $millis0 = [long]$Matches[2]
        if ($after -match 'builds=(\d+) millis=(\d+)') {
            $builds = [int]$Matches[1] - $builds0
            $millis = [long]$Matches[2] - $millis0
            $average = if ($builds -gt 0) { [math]::Round($millis / $builds) } else { 0 }
            Add-Result timing "Kestrel built $builds snapshots in $millis ms during 20 commands, $average ms each"
        }
    }
    # The transcription time with the busy screen's labels as boost words, against the session's words alone, on the
    # generated clips of the instant commands; the labels never leave the phone.
    $folder = Join-Path $generated 'piper'
    $rows = @(Read-Corpus -Path $corpusFile | Where-Object { $_.Group -eq 'appendix' -and (Test-Path -LiteralPath (Join-Path $folder "$($_.Id).wav")) } | Select-Object -First 10)
    if ($rows.Count -eq 0) {
        Add-Result timing 'transcription time not measured: no generated clips in corpus\generated\piper; make them with scripts\make-corpus.ps1 on the emulator'
        return
    }
    Stop-Kestrel
    Open-Settings
    try {
        $state = Invoke-Hook voice-eval @{ Load = $true }
        $watch = [Diagnostics.Stopwatch]::StartNew()
        while ($state -eq 'loading' -and $watch.Elapsed.TotalSeconds -lt 300) {
            Start-Sleep -Seconds 2
            $state = Invoke-Hook voice-eval @{ Load = $true }
        }
        if ($state -notmatch '^ready') { throw "the recogniser did not load: $state" }
        $withLabels = [System.Collections.Generic.List[double]]::new()
        $without = [System.Collections.Generic.List[double]]::new()
        foreach ($row in $rows) {
            Invoke-AdbCopy -Serial $Serial -Direction push -From (Join-Path $folder "$($row.Id).wav") -To "$remote/gen-timing.wav"
            $options = @{ File = 'gen-timing'; Expected = $row.Text; Kind = 'skip'; Group = $row.Group; ScreenLabels = $true }
            $withLabels.Add([double](ConvertFrom-HookAnswer (Invoke-Hook voice-eval $options)).millis)
            $without.Add([double](ConvertFrom-HookAnswer (Invoke-Hook voice-eval @{ File = 'gen-timing'; Expected = $row.Text; Kind = 'skip' })).millis)
        }
        Add-Result timing "transcription of $($rows.Count) command clips: median $(Get-Percentile $withLabels 0.5) ms with the screen's labels and the vocabulary, $(Get-Percentile $without 0.5) ms without them"
    } finally {
        Invoke-Hook voice-eval @{ Close = $true } | Out-Null
        Invoke-Checked "rm -f $remote/gen-timing.wav" | Out-Null
        Invoke-Home
    }
}

# The gain at which the phone hears this computer's clips at the owner's target (hook H7): Kestrel is paused, so that the
# calibration sentence, a conversation line without a keyword, starts nothing, and voice-route's loudest 20 ms frame
# (frameRms) is read around each play.
function Measure-SpeakerLevel {
    $row = @(Read-Corpus -Path $corpusFile | Where-Object { $_.Group -eq 'conversation' -and $_.Text -notmatch '(?i)kestrel|stop|cancel' }) | Select-Object -First 1
    $marks.Calibration = Join-Path $generated "kokoro\$($row.Id).wav"
    Invoke-Hook voice @{ Send = 'pause' } | Out-Null
    Wait-Mode paused 10 | Out-Null
    try {
        Set-SpeakerLevel -TargetDb $config.SpeakerTargetDb -Measure {
            param($Gain)
            Invoke-Hook voice-route | Out-Null
            Invoke-Clip -Path $marks.Calibration -Gain $Gain | Out-Null
            $level = (ConvertFrom-HookPairs (Invoke-Hook voice-route))['frameRms']
            if ($level -match '^-?\d+(\.\d+)?$') { [double]::Parse($level, [Globalization.CultureInfo]::InvariantCulture) } else { $null }
        }
    } finally {
        Invoke-Hook voice @{ Send = 'resume' } | Out-Null
        Wait-Mode listening 10 | Out-Null
    }
}

# Before the first check through the air: this computer's clips must reach the phone loudly enough (decision 4 of plan
# 4D). Returns whether they do; the result says the level either way. A dry run plays no sound and needs no level.
function Initialize-Air([string]$StepName) {
    if ($DryRun -or $null -ne $script:gain) { return $true }
    foreach ($voice in 'kokoro', 'piper') {
        if (-not (Test-Path -LiteralPath (Join-Path $generated "$voice\k001.wav"))) {
            Add-Result $StepName "skipped: corpus\generated\$voice has no clips of corpus\checks.txt; make them with scripts\make-corpus.ps1 -List checks on the emulator"
            return $false
        }
    }
    $level = Measure-SpeakerLevel
    $heard = if ($null -eq $level.LevelDb) { 'nothing' } else { "$($level.LevelDb) dBFS" }
    Add-Result $StepName "this computer's clips reach the phone at $heard (target $($config.SpeakerTargetDb) dBFS, within 3 dB) with a gain of $([math]::Round($level.Gain, 2))"
    if (-not $level.InBand -and ($null -eq $level.LevelDb -or $level.LevelDb -lt $config.SpeakerTargetDb - 3)) {
        Add-Result $StepName 'skipped: the phone does not hear this computer loudly enough; turn up its volume and put its speakers next to the phone'
        return $false
    }
    $script:gain = $level.Gain
    $script:clock = Get-PhoneClock { Read-DeviceValue 'cat /proc/uptime' }
    $true
}

# Spec 5.3's transcription time through the air (map C120): 10 command clips with Settings in front, only transcribed and
# resolved on the phone (-NoRun), timed by the phone from the end of the end-of-speech pause to the final text. On this
# computer, the end of each clip's speech, on the phone's clock, also shows how late the speech detector finds it.
function Measure-AirTranscription {
    Open-Settings
    $rows = @(Read-Corpus -Path $corpusFile | Where-Object { $_.Group -eq 'appendix' } | Select-Object -First 10)
    $times = [System.Collections.Generic.List[double]]::new()
    $lags = [System.Collections.Generic.List[double]]::new()
    foreach ($row in $rows) {
        $tag = New-CheckTag
        Invoke-Hook voice-expect @{ Text = $row.Text; Tag = $tag; Seconds = 15; NoRun = $true } | Out-Null
        $clip = Get-PassClip -Id $row.Id -Say $row.Text
        $script:lastPlay = $null
        & $script:play $clip
        $answer = Wait-ExpectResult -Tag $tag -TimeoutSeconds 25
        Start-Sleep -Milliseconds 1500
        if (-not $answer -or $answer['matched'] -ne 'true') { continue }
        $times.Add([double]$answer['transcribed'] - [double]$answer['closed'])
        if ($script:lastPlay -and $script:clock) {
            $speech = Get-ClipSpeechEnd $clip.Path
            $ended = $script:lastPlay.EndMs - ($speech.LengthMs - $speech.SpeechEndMs) + $script:clock.OffsetMs
            $lags.Add([double]$answer['speechEnded'] - $ended)
        }
    }
    Add-Result air "transcription time through the air with Settings in front, from the end of the end-of-speech pause to the final text, timed on the phone: $($times.Count) of $($rows.Count) clips matched; median $(Get-Percentile $times 0.5) ms, 90th percentile $(Get-Percentile $times 0.9) ms"
    if ($lags.Count -gt 0) {
        Add-Result air "the speech detector marked the end of the speech a median $(Get-Percentile $lags 0.5) ms after the clip's speech ended on this computer (converted to the phone's clock, within $($script:clock.ErrorMs) ms, without Windows' output delay)"
    }
    Invoke-Home
}

function Invoke-AirStep {
    $voices = if ($DryRun) { @('clip') } else { @('kokoro', 'piper') }
    $script:pass = $voices[0]
    try {
        Start-Kestrel
        if (-not (Initialize-Air air)) { return }
        Measure-AirTranscription
        foreach ($voice in $voices) {
            $script:pass = $voice
            foreach ($run in 'Invoke-ScreenStep', 'Invoke-SwipeStep', 'Invoke-NotificationStep', 'Invoke-GlobalStep', 'Invoke-RadioStep', 'Invoke-SoundStep', 'Invoke-TextStep') {
                try {
                    & $run
                } catch {
                    if ($_.Exception.Message -eq 'The owner stopped the session.') { throw }
                    Add-Result air "${voice}: $run failed: $(("$($_.Exception.Message)" -split "`r?`n")[0])"
                }
            }
            $counts = $script:passCounts[$voice]
            if ($counts) { Add-Result air "${voice}: $($counts.Passed) of $($counts.Run) checks passed through the air" }
        }
    } finally {
        $script:pass = 'text'
    }
}

# Loads the recogniser of the hook voice-eval, which takes up to a minute on the A71. Kestrel must be off, because its
# voice session would hold a second recogniser.
function Initialize-EvalRecognizer {
    $state = Invoke-Hook voice-eval @{ Load = $true }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($state -eq 'loading' -and $watch.Elapsed.TotalSeconds -lt 300) {
        Start-Sleep -Seconds 2
        $state = Invoke-Hook voice-eval @{ Load = $true }
    }
    if ($state -notmatch '^ready') { throw "the recogniser of voice-eval did not load: $state" }
}

# The direct pass of the step corpus (plan 4E): the clips of $Rows in $Voice, fed in directly to the phone's recogniser
# with Kestrel off, as scripts\voice-eval.ps1 feeds them on the emulator (the hook voice-eval), each judged with its
# group, its scene's labels and the contacts. Returns the commands' totals (New-CommandTotals).
function Measure-DirectLines([string]$Voice, [object[]]$Rows) {
    $totals = New-CommandTotals
    Stop-Kestrel
    try {
        Initialize-EvalRecognizer
        foreach ($row in $Rows) {
            Invoke-AdbCopy -Serial $Serial -Direction push -From (Join-Path $generated "$Voice\$($row.Id).wav") -To "$remote/gen-direct.wav"
            $options = @{ File = 'gen-direct'; Expected = $row.Text; Kind = 'skip' } + (Get-CorpusLineOptions -Row $row -Scenes $corpusScenes -Contacts $corpusContacts)
            $answer = ConvertFrom-HookAnswer (Invoke-Hook voice-eval $options)
            Add-CommandVerdict -Totals $totals -Id $row.Id -Text $row.Text -Right $answer['command']
        }
    } finally {
        Invoke-Hook voice-eval @{ Close = $true } | Out-Null
        Invoke-Checked "rm -f $remote/gen-direct.wav" | Out-Null
    }
    $totals
}

# The recorded pass of the step corpus (inv-a71-speech, step 4): each of $Rows, which were right fed in directly and
# wrong through the air, plays through the air again in $Voice while Kestrel is off and its settings are in front, where
# Android lets the hook voice-record record, and the recording is fed in to the phone's recogniser (voice-eval). A line
# that is right then was lost in Kestrel's live pipeline, and one that is wrong again was lost in the room's sound. The
# recordings stay on the phone and are deleted there; the result holds only counts.
function Measure-RecordedLines([string]$Voice, [object[]]$Rows) {
    if ($Rows.Count -eq 0) { return }
    $totals = New-CommandTotals
    Stop-Kestrel
    try {
        Initialize-EvalRecognizer
        foreach ($row in $Rows) {
            $clip = Get-PassClip -Id $row.Id -Say $row.Text
            # 16 kHz, 16-bit mono after a header of 44 bytes, and 1.5 s more for this computer's output delay.
            $seconds = [int][math]::Min(15, [math]::Ceiling(((Get-Item -LiteralPath $clip.Path).Length - 44) / 32000 + 1.5))
            Invoke-Checked 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
            Invoke-Hook voice-record @{ File = 'recorded'; Seconds = $seconds } | Out-Null
            & $script:play $clip
            $watch = [Diagnostics.Stopwatch]::StartNew()
            do {
                Start-Sleep -Milliseconds 500
                $recorded = Invoke-Hook voice-record @{ Result = $true }
            } while ($recorded -eq 'recording' -and $watch.Elapsed.TotalSeconds -lt $seconds + 10)
            # A recording that Android silenced, or that did not end, holds nothing to judge.
            if ($recorded -notmatch 'silenced=false') { continue }
            $options = @{ File = 'recorded'; Expected = $row.Text; Kind = 'skip' } + (Get-CorpusLineOptions -Row $row -Scenes $corpusScenes -Contacts $corpusContacts)
            $answer = ConvertFrom-HookAnswer (Invoke-Hook voice-eval $options)
            Add-CommandVerdict -Totals $totals -Id $row.Id -Text $row.Text -Right $answer['command']
        }
    } finally {
        Invoke-Hook voice-eval @{ Close = $true } | Out-Null
        Invoke-Checked "rm -f $remote/recorded.wav" | Out-Null
        Invoke-Home
    }
    $judged = $totals.Total + $totals.OneTotal
    $right = $totals.Right + $totals.OneRight
    Add-Result corpus ("${Voice}: of the $($Rows.Count) lines that were right fed in directly and wrong through the air, $judged were " +
        "recorded through the air with Kestrel off and fed in: $right of them were right (lost in Kestrel's live pipeline) and " +
        "$($judged - $right) wrong again (lost in the room's sound)")
}

# The owner's lines of the corpus in both voices (map C121; plan 4E): first fed in directly to the phone's recogniser,
# then through the air, where the phone only transcribes and resolves each one, so that "Call Mom." or "Kestrel, stop"
# never acts; both judge each line with its group, its scene's labels and the contacts, as scripts\voice-eval.ps1 does on
# the emulator. The results hold only counts, the lines' ids and the comparison of the two passes (design spec, section
# 5.3), and then the recorded pass of the lines that only the air lost. A dry run feeds the Kokoro clips in directly and
# records nothing, because its air pass says the texts into Kestrel's stand-in microphone.
function Invoke-CorpusStep {
    $voices = if ($DryRun) { @('clip') } else { @('kokoro', 'piper') }
    $rows = @(Read-Corpus -Path $corpusFile | Where-Object { $_.Owner })
    if ($DryRun) { $rows = @($rows | Select-Object -First 3) }
    $script:pass = $voices[0]
    try {
        Start-Kestrel
        if (-not (Initialize-Air corpus)) { return }
        foreach ($voice in $voices) {
            $script:pass = $voice
            $clipVoice = if ($DryRun) { 'kokoro' } else { $voice }
            foreach ($id in $leftOut[$clipVoice].Keys) { Add-Result corpus "${voice}: $id left out, because $($leftOut[$clipVoice][$id])" }
            $lines = @($rows | Where-Object { -not $leftOut[$clipVoice].ContainsKey($_.Id) })
            $direct = Measure-DirectLines -Voice $clipVoice -Rows $lines
            Start-Kestrel
            $totals = New-AirTotals
            $air = New-CommandTotals
            foreach ($row in $lines) {
                $tag = New-CheckTag
                $options = @{ Text = $row.Text; Tag = $tag; Seconds = 15; NoRun = $true } + (Get-CorpusLineOptions -Row $row -Scenes $corpusScenes -Contacts $corpusContacts)
                Invoke-Hook voice-expect $options | Out-Null
                & $script:play (Get-PassClip -Id $row.Id -Say $row.Text)
                $answer = Wait-ExpectResult -Tag $tag -TimeoutSeconds 25
                Add-AirResult -Totals $totals -Answer $answer -Id $row.Id
                # A line without an answer is wrong when its command counts.
                $right = if ($answer) { $answer['right'] } elseif ($direct.Verdicts.ContainsKey($row.Id)) { '0' } else { '-' }
                Add-CommandVerdict -Totals $air -Id $row.Id -Text $row.Text -Right $right
                # Late parts of the clip that matched would count as stray parts of the next line.
                Start-Sleep -Milliseconds 1500
            }
            Add-Result corpus (Format-AirTotals $voice $totals)
            Add-Result corpus (Format-CommandComparison $voice $direct $air)
            if (-not $DryRun) {
                # A failure of one voice's recorded pass must not cost the other voice's passes.
                try {
                    Measure-RecordedLines -Voice $voice -Rows @($lines | Where-Object { $direct.Verdicts[$_.Id] -eq '1' -and $air.Verdicts[$_.Id] -eq '0' })
                } catch {
                    Add-Result corpus "${voice}: the recorded pass failed: $($_.Exception.Message)"
                }
            }
        }
    } finally {
        $script:pass = 'text'
    }
}

$plan = @(
    @{ Name = 'install'; Minutes = 8; Run = 'Invoke-InstallStep' }
    @{ Name = 'owner'; Minutes = 3; Run = 'Invoke-OwnerStep' }
    @{ Name = 'screen'; Minutes = 6; Run = 'Invoke-ScreenStep' }
    @{ Name = 'swipes'; Minutes = 3; Run = 'Invoke-SwipeStep' }
    @{ Name = 'notifications'; Minutes = 2; Run = 'Invoke-NotificationStep' }
    @{ Name = 'global'; Minutes = 5; Run = 'Invoke-GlobalStep' }
    @{ Name = 'profiles'; Minutes = 1; Run = 'Invoke-ProfileStep' }
    @{ Name = 'radios'; Minutes = 2; Run = 'Invoke-RadioStep' }
    @{ Name = 'sound'; Minutes = 6; Run = 'Invoke-SoundStep' }
    @{ Name = 'text'; Minutes = 4; Run = 'Invoke-TextStep' }
    @{ Name = 'calls'; Minutes = 6; Run = 'Invoke-CallStep' }
    @{ Name = 'timing'; Minutes = 6; Run = 'Invoke-TimingStep' }
    @{ Name = 'air'; Minutes = 45; Run = 'Invoke-AirStep' }
    @{ Name = 'corpus'; Minutes = 45; Run = 'Invoke-CorpusStep' }
)

$completed = $false
$stopped = $false
$how = @(if ($DryRun) { 'as a dry run' }; if ($Unattended) { 'without the owner' }) -join ', '
Add-Result session "started on $(if ($isEmulator) { 'kestrel-api37' } else { 'the Galaxy A71' })$(if ($how) { ", $how" })"
try {
    foreach ($entry in $plan) {
        if ($Step -and $entry.Name -notin $Step) { continue }
        # After an unlock that did not work the session tries no more unlocks, and every later step needs an unlocked phone.
        if ($script:unlockFailed) {
            Add-Result $entry.Name 'skipped: the session could not unlock the phone earlier, and this step needs an unlocked phone'
            continue
        }
        Write-Host "Step $($entry.Name), about $($entry.Minutes) minutes."
        Initialize-Session
        try {
            # An "isn't responding" dialog, such as Chrome's after the providers' Custom Tab tests, would stay over every check.
            $anr = Close-AnrDialog -Serial $Serial
            if ($anr) { Add-Result $entry.Name $anr }
            & $entry.Run
        } catch {
            if ($_.Exception.Message -eq 'The owner stopped the session.') { throw }
            # The scripts' and hooks' errors name only steps, files and classes, never what Kestrel heard.
            Add-Result $entry.Name "failed: $(("$($_.Exception.Message)" -split "`r?`n")[0])"
        }
    }
    $completed = $true
} catch {
    $stopped = $true
    Add-Result session "stopped: $($_.Exception.Message)"
} finally {
    try {
        foreach ($name in $passCounts.Keys) { Add-SessionResult -Path $resultsFile -Step session -Text "${name}: $($passCounts[$name].Passed) of $($passCounts[$name].Run) checks passed" }
        # The restores that are left, the newest first, so that the screen, the clips, the sound, the radios, the keyboard,
        # the accessibility settings, Kestrel's microphone and mode, its permission and its notices come last.
        $left = @($pending.ToArray())
        [array]::Reverse($left)
        Invoke-Restore $left
        # The owner's rule: a finished run leaves the A71 locked, so that its screen does not burn in; the shared emulator stays awake.
        if (-not $isEmulator) {
            try { Invoke-SettingsCommand -Serial $Serial -Command 'input keyevent KEYCODE_SLEEP' | Out-Null } catch { Write-Warning "Could not lock $Serial. Press its power button." }
        }
    } finally {
        if (-not ($completed -or $stopped)) { Add-SessionResult -Path $resultsFile -Step session -Text 'stopped, as by Ctrl+C' }
        foreach ($restore in $pending) { Write-Warning "Could not put back $($restore.What) on $Serial ($($restore.Reason)). $($restore.ByHand)" }
        # install.ps1 builds through Gradle, which keeps a daemon running.
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
