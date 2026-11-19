# The second session on the Galaxy A71 with the owner (plan 2E): it repeats the checks of sub-project 2 that failed or
# were not valid in the session of 2026-11-17 (the controller's analysis of the session), and adds the measures that
# were missing. Every time comes from the phone: Kestrel records when it was started, opened its session, heard the end
# of speech, reacted and turned itself off, and the script reads these times through the hook voice-times; no measure
# counts from the owner's Enter. Each prompt says what the owner does and what the phone shows or does not show. It asks
# before it reads the device's model and before each step. The steps' estimates add up to 3 hours 58 minutes; about 65
# minutes need the owner's hands or voice, and the owner may leave during the steps install and voice-tests and during
# the first two battery measures. The owner does it in one sitting with scripts\a71-commands.ps1, which comes first, so
# that the owner records the voice corpus while fresh: a71-commands.ps1 with all its steps (about 2 hours, of which
# about 1.5 to 2 hours need the owner), then this script with all its steps (about 4 hours, of which about 1 hour needs
# the owner); together about 6 hours, of which about 2.5 to 3 hours need the owner. The owner may split the sitting into
# two at the boundary between the scripts. a71-commands.ps1's install copies neither Kokoro's models nor the
# microphone's permission, so this script keeps its own install step, which then finds Kestrel's build up to date. The
# steps:
#   install       installs Kestrel's debug build, copies the speech models with the Kokoro voice and Kokoro's int8
#                 model, which only a voice device test loads, and grants Kestrel the microphone and notification
#                 permissions, which stay granted
#   voice-tests   the voice device tests that may run on a phone (scripts\voice-test.ps1), among them
#                 ListeningServiceTest, which reads the foreground as Android 13 prints it, KeywordTuningTest, which
#                 feeds generated "Kestrel", "Hey Kestrel" and "Kestrel, wake up" and speech without a keyword to the
#                 keyword spotter with each of its three settings, and KokoroModelsTest, which measures Kokoro's fp32
#                 and int8 models side by side; the tests that did not pass are recorded by name
#   failures      Kestrel's logged failures since the voice tests, by their fixed descriptions and classes, before
#                 listen-check, whose notification check makes Android refuse a start on purpose
#   listen-check  first whether 'dumpsys accessibility' ends with a line break (only its last byte), then
#                 scripts\listen-check.ps1 -Reboot, which prints one line for each check; the owner unlocks the phone
#                 after the restart
#   battery-rules without and with the battery restriction and without Kestrel's accessibility service: Android's record
#                 of the listening service, Kestrel's own view of it, and when Kestrel turned itself off, every 5 s for
#                 3 minutes, timed on the phone
#   activation    the accessibility shortcut, the accessibility button and the tile with Kestrel in the background:
#                 the time from the event to listening, timed on the phone, and whether Android showed a dialog
#   assistant     with only the assistant role and Kestrel in the background: a start through the hook, "Kestrel, open
#                 settings" with the time from Kestrel's request to its settings in front, timed on the phone, or
#                 Kestrel's own verdict that Android blocked them and its notification, read on the phone, and the
#                 restore after Android ended Kestrel's process; it waits, and says so, until 2 minutes have passed
#                 since the session's last crash of Kestrel, which it keeps in corpus\owner\session-state.txt
#   earbuds       the route of the recording with the owner's earbuds (the hook voice-route), "Kestrel" into the
#                 earbuds with the time from the end of the speech to the wait for a task, and the phone's microphone
#                 after the earbuds went away
#   spotter       for each of three settings of the keyword spotter's boost and threshold: "Hey Kestrel" 5 times, and
#                 about 2 minutes of generated speech without a keyword from this computer's speaker while Kestrel is
#                 paused, the same for every setting; the hits and false hits are counted on the phone, the script
#                 prints the setting that its rule keeps (Select-SpotterWinner), and the default comes back at the end
#   voice         "Kestrel", "Hey Kestrel", "Kestrel, wake up" while paused, interruptions with pauses of about 1, 1.5
#                 and 2 s, which this window's prompts set, and "Stop Kestrel": per try, the spotter's hits by keyword,
#                 the transcribed and named utterances, how each was read, and the time from the end of the speech to
#                 Kestrel's reaction, all from the phone
#   memory        three starts and closes with the chosen voice and with Kokoro, whose speed Kestrel measures at its
#                 first start here, with the peak PSS while each session opens and the memory categories before, while
#                 listening and after each close, and a cold start through the tile after the script ended Kestrel's
#                 process itself
#   battery       three measures of 30 minutes, each timed by batterystats: a quiet room, the quiet room with one
#                 generated task for Kestrel from this computer's speaker every 5 minutes, and ordinary room talk; with
#                 the breakdown of Kestrel's use, the audio that it transcribed, its utterances, hits and instant
#                 commands, and the model "% per hour = baseline + cost per transcribed minute x minutes"
#   failures-late Kestrel's logged failures from the end of listen-check to the end of the session
# The results go into corpus\owner\session-<time>.txt; git ignores it. Neither it nor the console ever hold what Kestrel
# heard: the hooks answer only counts, times, kinds and device types. Once the first step runs, the phone's screen stays
# on while the phone is plugged in. The script puts back what it changes on the phone, also when a step fails or Ctrl+C
# stops it: Kestrel's mode Off, the hooks' slow task, the keyword spotter's setting, the setting "Stay awake", the
# assistant role and its settings, Kestrel's battery restriction (as allow or ignore, never as default, which Android
# counts as restricted), Kestrel's voice, the accessibility settings, the screen's timeout and the battery's state. It
# cannot put back the battery statistics, which the battery measures reset, and it asks the owner to turn "Use the
# earbud microphone" off again and to swipe away the notifications that stay. It refuses every device but the A71 and
# kestrel-api37. -DryRun runs the session on kestrel-api37 without the owner: it answers every question itself, sends
# what the owner would say through the voice hook, taps Kestrel's tile with 'cmd statusbar click-tile' in place of the
# owner, also for the cold start, plays no sound, skips what needs the owner's hands otherwise, runs listen-check.ps1
# without -Reboot, skips voice-test.ps1, and shortens the waits and the battery measures to a minute. -Step runs only the
# named steps, also as one text separated by commas.
# It needs PowerShell 7.3 or newer, as scripts\hook.ps1 does. After Ctrl+C, it ends with exit code 1.
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

$stepNames = @('install', 'voice-tests', 'failures', 'listen-check', 'battery-rules', 'activation', 'assistant', 'earbuds',
    'spotter', 'voice', 'memory', 'battery', 'failures-late')
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
$generatedClips = Join-Path $PSScriptRoot '..\corpus\generated\piper'
$service = 'dev.kestrel/dev.kestrel.app.KestrelAccessibilityService'
$assistantRole = 'android.app.role.ASSISTANT'
$earbudsChanged = $false
$gradleUsed = $false
$prepared = $false
# The device's time, as logcat -T reads it, when the session's first step began and when listen-check ended.
$sessionStart = $null
$afterListenCheck = $null
# The shorter waits of a dry run.
$minuteScale = if ($DryRun) { 1 / 30 } else { 1 }

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
    $script:sessionStart = Get-DeviceTime
    Request-Owner 'The phone''s screen now stays on while the phone is plugged in. Keep the phone unlocked during the whole session, unless a step asks otherwise. Press Enter to go on.' 'go' | Out-Null
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
# records voice-choice's kokoroSpeed= after it, which shows that the measure was stored.
function Initialize-KokoroSpeed([string]$StepName) {
    $choice = Get-VoiceChoice
    if ($choice.Voice -ne 'kokoro' -or $choice.KokoroSpeed -ne 'none') { return }
    Start-Kestrel
    Stop-Kestrel
    Add-Result $StepName "an untimed start with Kokoro first, so that the timed starts do not include the measure of Kokoro's speed: kokoroSpeed=$((Get-VoiceChoice).KokoroSpeed)"
}

# In a dry run, the owner's taps on Kestrel's tile: the tile goes into Quick Settings through SystemUI once, with a
# restore, and 'cmd statusbar click-tile' taps it while Quick Settings is open, as listen-check.ps1's tile check does.
$tileComponent = 'dev.kestrel/dev.kestrel.app.listen.KestrelTileService'
$tileRestore = $null
function Add-DryRunTile {
    if ($script:tileRestore -or (Get-SecureSetting -Serial $Serial -Name sysui_qs_tiles) -match 'KestrelTileService') { return }
    # SystemUI removes only a tile that it has made, so the restore removes the tile that this run added.
    $script:tileRestore = Register-Restore "Quick Settings' tiles" "Run: & '$adb' -s $Serial shell cmd statusbar remove-tile $tileComponent" $null {
        Assert-TestDevice -Serial $Serial
        Invoke-Checked "cmd statusbar remove-tile $tileComponent" | Out-Null
    }
    Invoke-Checked "cmd statusbar add-tile $tileComponent" | Out-Null
}

function Invoke-DryRunTileTap {
    Add-DryRunTile
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
    Add-Result install 'Kestrel installed, the speech models copied with both of Kokoro''s models, and the microphone and notification permissions granted'
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
    Start-Kestrel
    Stop-Kestrel
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
    # listen-check.ps1 deletes the dump after each read and never prints it.
    if (-not $DryRun -and (Request-Owner 'Next comes listen-check, about 10 minutes. Do not touch the phone while it runs: it opens and closes the notification shade, Quick Settings and Kestrel''s settings by itself, and it restarts the phone. After the restart, unlock the phone as soon as it asks for your PIN, without waiting for this window, and do nothing else. When Kestrel listens again, this window asks you once to check that the phone is unlocked and to press Enter; the time of the restart is read on the phone before that, so it does not depend on your Enter. Press Enter to start, or s to skip.') -eq 'skip') {
        Add-Result listen-check 'skipped'
        return
    }
    $lines = @(& "$PSScriptRoot\listen-check.ps1" -Serial $Serial -Reboot:(-not $DryRun) *>&1 | ForEach-Object { $text = "$_"; Write-Host $text; $text })
    $code = $LASTEXITCODE
    # listen-check's restart and role checks end Kestrel's process with 'am crash'; its end is no earlier than the last.
    Write-SessionCrash -Path $stateFile -Serial $Serial -Time (Get-Date)
    $script:afterListenCheck = Get-DeviceTime
    # Each check's line holds its name, ok, failed or skipped, and what it measured on the device.
    foreach ($line in $lines | Where-Object { $_ -match '^[a-z]+\s+(ok|failed|skipped)\b' }) { Add-Result listen-check $line }
    if ($code -ne 0) { Add-Result listen-check "ended with exit code $code" }
    Request-Owner 'If Kestrel''s notice that its accessibility service is off is in the notification shade, swipe it away. Press Enter after it.' 'go' | Out-Null
}

# What Android 13 and One UI do to Kestrel's microphone service in the background after its accessibility service is
# turned off, without and with the battery restriction (analysis, items 1 and 2): Android's record of the service, which
# every version prints as 'isForeground=...', Kestrel's own view of it through Android's list of its running services,
# and when Kestrel turned itself off and why. The times come from the phone: the device's uptime when the service went
# off, and Kestrel's own time of its turn-off.
function Invoke-BatteryRulesStep {
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
        $seconds = [int](180 * $minuteScale)
        foreach ($restricted in $false, $true) {
            $label = if ($restricted) { 'with the battery restriction' } else { 'without the battery restriction' }
            Invoke-Checked "cmd appops set dev.kestrel RUN_ANY_IN_BACKGROUND $(if ($restricted) { 'ignore' } else { 'allow' })" | Out-Null
            Invoke-Checked (Get-ServicesCommand (@($others) + $service)) | Out-Null
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
            $notice = Read-OwnerAnswer 'Pull down the notification shade and look for Kestrel''s notices. Type b if one says that Kestrel''s battery use is restricted, a if one says only that its accessibility service is off, o for another text of Kestrel''s, or n for none. Then close the shade.' 'n'
            Add-Result battery-rules "${label}: $android; $lostText; Kestrel's view at the end: $foreground; $turnOff; Kestrel's notice: $notice"
            Stop-Kestrel
        }
    } finally {
        Complete-Restore $accessibilityRestore
        Complete-Restore $opRestore
    }
}

# The accessibility shortcut, the accessibility button and the tile with Kestrel in the background (analysis, item 11):
# the time from Kestrel's own record of the event to its open voice session, and whether Android showed a dialog.
function Invoke-ActivationStep {
    $restore = Register-AccessibilityRestore
    try {
        # Kestrel's service must be on for the shortcut and the button; the owner's other services stay on. Android shows
        # its dialog about the shortcut only once, and this setting says that it was shown; the restore puts it back.
        Invoke-Checked (Get-ServicesCommand (@(Get-OtherServices $restore.Data) + $service)) | Out-Null
        Invoke-Checked 'settings put secure accessibility_enabled 1' | Out-Null
        Invoke-Checked "settings put secure accessibility_shortcut_target_service $service" | Out-Null
        Invoke-Checked "settings put secure accessibility_button_targets $service" | Out-Null
        Invoke-Checked 'settings put secure accessibility_shortcut_dialog_shown 1' | Out-Null
        $methods = [ordered]@{
            shortcut = 'Press and hold both volume keys for 3 s, once'
            button = 'Tap the accessibility button once, in the navigation bar or floating over the screen'
            tile = 'Swipe down to Quick Settings and tap Kestrel''s tile once; add the tile first if it is not there'
        }
        $sources = @{ shortcut = 'accessibility'; button = 'accessibility'; tile = 'tile' }
        # A dry run taps the tile itself, and skips the shortcut and the button, which need hands.
        if ($DryRun) { Add-DryRunTile }
        Initialize-KokoroSpeed activation
        foreach ($name in $methods.Keys) {
            Stop-Kestrel
            Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
            $before = ConvertFrom-TimeMark -Text ((Get-Times)['started'])
            $dryRunAnswer = if ($name -eq 'tile') { 'go' } else { 'skip' }
            if ((Request-Owner "Kestrel is off and in the background. $($methods[$name]). Then press Enter at once, whether or not the phone reacts. Kestrel shows nothing on the screen when it starts, only its notification. Or press s to skip." $dryRunAnswer) -eq 'skip') {
                Add-Result activation "${name}: skipped"
                continue
            }
            if ($DryRun) { Invoke-DryRunTileTap }
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
            $dialog = Read-OwnerAnswer 'Did Android show a dialog or a message before Kestrel started, for example about the shortcut? Type y or n.' 'n'
            Request-Owner "Now do it once more: $($methods[$name]). Kestrel should pause; its notification then says that Kestrel is paused. Then press Enter at once, whether or not the phone reacts." 'go' | Out-Null
            if ($DryRun) { Invoke-DryRunTileTap }
            $paused = Wait-Mode paused 15
            Add-Result activation "${name}: $timed; a dialog: $dialog; the second time paused it: $(Format-Wait $paused)"
        }
        Stop-Kestrel
    } finally {
        Complete-Restore $restore
    }
}

function Get-AssistantHolders { Get-RoleHolders ((Invoke-Checked "cmd role get-role-holders $assistantRole") -join ' ') }

# With only the assistant role, without Kestrel's accessibility service and with Kestrel in the background (analysis,
# item 5): a start through the hook, "Kestrel, open settings", and the restore through the assistant service after
# Android ended Kestrel's process (design spec, sections 8.5, 8.6 and 14). None needs the owner's hands.
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
    # Kestrel's own verdict (its reaction settings-blocked after its 2 s) and its notification "Open Kestrel's settings"
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

# "Kestrel" once, and Kestrel's wait for a task, timed on the phone from the end of the speech; nothing is timed from
# Enter.
function Measure-Name([string]$Prompt) {
    $before = Get-Counts
    $since = [long](Get-Times)['now']
    if ((Request-Owner $Prompt 'go') -eq 'skip') { return 'skipped' }
    if ($DryRun) { Send-Heard '+Kestrel' }
    $reaction = $null
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($null -eq $reaction -and $watch.Elapsed.TotalSeconds -lt 15) {
        Start-Sleep -Milliseconds 500
        $reaction = Get-Reaction $since 'wait'
    }
    $rise = Get-CountRise $before (Get-Counts)
    if ($reaction) { "Kestrel began to wait for a task: $reaction; counts: $rise" } else { "no wait for a task within 15 s; counts: $rise" }
}

# The earbud microphone with the owner's earbuds (analysis, item 6): where Android routes the recording, "Kestrel" into
# the earbuds, and the phone's microphone after the earbuds went away.
function Invoke-EarbudStep {
    Stop-Kestrel
    if ((Request-Owner 'Connect the Galaxy Buds to the phone and put them in your ears. Open Kestrel''s settings, the page "Listening and activation", and turn on "Use the earbud microphone". Press Enter when it is on, or s to skip.') -eq 'skip') {
        Add-Result earbuds 'skipped'
        return
    }
    $script:earbudsChanged = $true
    Start-Kestrel
    Write-Host 'Kestrel listens. Do not speak for 5 s; the script reads where Android sends the recording.'
    $route = Watch-Route 5
    Add-Result earbuds "the route in the 5 s after the start: $(Format-Route $route)"
    $music = Read-OwnerAnswer 'Start some music on the phone, so that it plays in the earbuds, and listen for a few seconds. Does it sound like a phone call, thin and muffled? Type y or n, or x if you cannot play music now.' 'x'
    $named = Measure-Name 'Stop the music. Put the phone under a pillow; it stays on and unlocked while it is plugged in, and you need not look at it. Say "Kestrel" once into the earbuds. Kestrel should say "Yes?" in the earbuds and wait; the script ends the wait itself. Then press Enter at once, whether or not you hear anything.'
    $heard = Watch-Route 1
    $inEarbuds = Read-OwnerAnswer 'Did you hear "Yes?" in the earbuds? Type y or n.' 'n'
    Add-Result earbuds """Kestrel"" into the earbuds: $named; the route afterwards: $(Format-Route $heard); ""Yes?"" in the earbuds: $inEarbuds; music sounded like a call: $music"
    Request-Owner 'Put the earbuds into their case, so that they disconnect, and take the phone from under the pillow. Press Enter then; the script waits until Kestrel records from the phone again.' 'go' | Out-Null
    # Up to 15 s, until Kestrel has given the earbuds up.
    $gone = $false
    for ($i = 0; $i -lt 15 -and -not $gone; $i++) {
        $gone = (ConvertFrom-HookPairs (Invoke-Hook voice-route))['holding'] -ne 'true'
        if (-not $gone) { Start-Sleep -Seconds 1 }
    }
    if (-not $gone) { Add-Result earbuds 'Kestrel still held the earbuds 15 s after they went into their case' }
    $phone = Measure-Name 'Say "Kestrel" once to the phone. Kestrel should say "Yes?" from the phone and wait; the script ends the wait itself. Then press Enter at once, whether or not the phone reacts.'
    Add-Result earbuds "after the earbuds went away, ""Kestrel"" to the phone: $phone"
    Stop-Kestrel
    Request-Owner 'Turn "Use the earbud microphone" off again in Kestrel''s settings, unless you want to keep it. Press Enter after it.' 'go' | Out-Null
}

# One measure of the voice step or the spotter step: $Tries times, $Prepare brings Kestrel into the state that each try
# needs, the owner says $Say once and presses Enter, and the script waits up to $TimeoutSeconds until $Reacted, which gets
# the counts from before the try and Kestrel's state, is true. For each try it records the rise of the counts on the
# phone (the spotter's hits by keyword, the transcribed and named utterances, how each was read) and Kestrel's latest
# reaction since the try began, with its time after the end of the speech, all from the phone; nothing is timed from
# Enter. A dry run makes one try, in which it sends $DryRunHeard (see Send-Heard) in place of the owner. A failure, such
# as a Kestrel that does not come to rest, costs only this measure's lines.
function Measure-Tries(
    [string]$StepName,
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
    $script:triesReacted = $null
    try {
        for ($try = 1; $try -le $Tries; $try++) {
            & $Prepare
            $counts = Get-Counts
            $since = [long](Get-Times)['now']
            if ((Request-Owner "$Say  (try $try of ${Tries}: say it once, then press Enter at once, whether or not the phone reacts; s skips the rest)" 'go') -eq 'skip') { break }
            if ($DryRun) { Send-Heard $DryRunHeard }
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
            Add-Result $StepName "${Name}, try ${try}: $(if ($seen) { 'reacted' } else { "no reaction within $TimeoutSeconds s" }); Kestrel's latest reaction: $(if ($reaction) { $reaction } else { 'none' }); counts: $(Get-CountRise $counts (Get-Counts))"
        }
    } catch {
        if ($_.Exception.Message -eq 'The owner stopped the session.') { throw }
        Add-Result $StepName "${Name}: failed after $hits of $done tries: $($_.Exception.Message)"
        return
    }
    Add-Result $StepName "${Name}: $(if ($done -eq 0) { 'skipped' } else { "$hits of $done tries" })"
    if ($done -gt 0) { $script:triesReacted = $hits }
}

# Generated clips without any of Kestrel's keywords, from the corpus's conversation and confusable lines, about 2 minutes
# of speech, which the step spotter plays for each setting of the keyword spotter.
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
# returns the rise of the spotter's hits of all three keywords: false hits, because no clip holds a keyword (analysis,
# item 8). A dry run plays nothing and returns 0.
function Measure-FalseHits([string]$Setting, [object[]]$Clips) {
    if ($DryRun) {
        Add-Result spotter "${Setting}: no speech played in a dry run"
        return 0
    }
    if ($Clips.Count -eq 0) {
        Add-Result spotter "${Setting}: no false hits measured, because corpus\generated\piper has no clips of conversation or confusable lines; run scripts\make-corpus.ps1 first"
        return $null
    }
    Write-Host 'Kestrel is paused now, and this computer plays about 2 minutes of speech without Kestrel''s name. Do not speak, and do not touch the phone.'
    Invoke-Hook voice @{ Send = 'pause' } | Out-Null
    Wait-Mode paused 10 | Out-Null
    $before = Get-Counts
    $seconds = 0.0
    foreach ($clip in $Clips) {
        # A false "Kestrel, wake up" would make Kestrel listen, and act on the clips after it.
        $state = Get-VoiceState
        if ($state -and $state.mode -ne 'paused') { Invoke-Hook voice @{ Send = 'pause' } | Out-Null }
        $player = [System.Media.SoundPlayer]::new($clip.File)
        try { $player.PlaySync() } finally { $player.Dispose() }
        $seconds += $clip.Seconds
    }
    Start-Sleep -Seconds 2
    $after = Get-Counts
    $rise = 0
    foreach ($name in 'hit-kestrel', 'hit-stop', 'hit-cancel') {
        $rise += [long]$(if ($after.Contains($name)) { $after[$name] } else { 0 }) - [long]$(if ($before.Contains($name)) { $before[$name] } else { 0 })
    }
    Add-Result spotter "${Setting}: $rise false hits in $([math]::Round($seconds / 60, 1)) minutes of played speech without a keyword, with Kestrel paused; counts: $(Get-CountRise $before $after)"
    $rise
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
        if ((Request-Owner 'Next come three rounds, about 16 minutes, one for each setting of the keyword spotter, in a quiet room. In each round you say "Hey Kestrel" 5 times to the phone, through its own microphone; Kestrel should say "Yes?" and wait, and the script ends each wait itself. Then this computer''s speaker plays about 2 minutes of generated speech without Kestrel''s name while Kestrel is paused, and the phone shows nothing; do not speak then. Put the phone about 1 m from this computer''s speaker, and set the speaker as loud as a voice in the room. Press Enter to begin, or s to skip.' 'go') -eq 'skip') {
            Add-Result spotter 'skipped'
            return
        }
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
            Measure-Tries spotter "$set, ""Hey Kestrel""" 'Say "Hey Kestrel" once. Kestrel should say "Yes?" and wait; the script ends the wait itself.' 5 $quiet { param($Counts, $State) $State -and [int](Get-Counts)['waits'] -gt [int]$Counts['waits'] } 8 -DryRunHeard '+Hey Kestrel.'
            $reacted = $script:triesReacted
            $falseHits = Measure-FalseHits $set $clips
            $rounds.Add([pscustomobject]@{ Setting = $set; Default = $tuning.Boost -eq 2 -and $tuning.Threshold -eq 0.15; Hits = $reacted; FalseHits = $falseHits })
        }
    } finally {
        Complete-Restore $tuningRestore
        Stop-Kestrel
    }
    Add-Result spotter "the setting before the step: $default"
    $winner = Select-SpotterWinner @($rounds | Where-Object { $null -ne $_.Hits -and $null -ne $_.FalseHits })
    Add-Result spotter $(if ($winner) { "the setting that the rule keeps: $($winner.Setting) (the most reactions to your ""Hey Kestrel"" among the settings with at most twice the default's false hits plus 2; a tie goes to fewer false hits, then to the default)" } else { 'no setting chosen, because the default setting was not measured whole' })
}

# An interruption (analysis, item 8): Kestrel reads a long reply, the owner says "Kestrel", and after a pause "open the
# weather". This window tells the owner when to say each part, so that the pause is about $Pause s: the second prompt
# comes 0.6 s plus the pause after the first, for a "Kestrel" of about 0.6 s, so the pause is approximate; the phone
# times only Kestrel's reactions.
function Measure-Interruption([string]$Pause) {
    $name = "an interruption with a pause of about $Pause s"
    $tries = if ($DryRun) { 1 } else { 2 }
    $done = 0
    $hits = 0
    for ($try = 1; $try -le $tries; $try++) {
        Wait-Quiet
        $counts = Get-Counts
        $since = [long](Get-Times)['now']
        if ((Request-Owner "$name, try $try of ${tries}: when you press Enter, say ""Kestrel, read me the news"" once. Kestrel answers and reads a long reply. Then this window says ""Kestrel"": say it once, and Kestrel stops reading. When the window then says ""open the weather"", say that once. Press Enter to begin, or s to skip the rest." 'go') -eq 'skip') { break }
        $done++
        if ($DryRun) { Send-Heard '+Kestrel, read me the news' } else { Write-Host 'Say "Kestrel, read me the news" now.' }
        $speaking = Wait-State { param($State) $State.speaking -eq 'true' } 30
        if ($speaking -lt 0) {
            Add-Result voice "${name}, try ${try}: Kestrel did not begin its reply within 30 s; counts: $(Get-CountRise $counts (Get-Counts))"
            continue
        }
        Start-Sleep -Seconds 3
        if ($DryRun) { Send-Heard '+Kestrel' } else { Write-Host 'Say "Kestrel" now.' }
        Start-Sleep -Milliseconds ([int](([double]::Parse($Pause, [Globalization.CultureInfo]::InvariantCulture) + 0.6) * 1000))
        if ($DryRun) { Send-Heard 'open the weather' } else { Write-Host 'Say "open the weather" now.' }
        # The condition runs in Wait-State, called from here, so it reads $counts of this try.
        $tasks = Wait-State { param($State) $State -and [int](Get-Counts)['tasks'] -ge [int]$counts['tasks'] + 2 } 30
        if ($tasks -ge 0) { $hits++ }
        Start-Sleep -Seconds 1
        $reaction = Get-Reaction $since
        Add-Result voice "${name}, try ${try}: $(if ($tasks -ge 0) { 'both tasks ran' } else { 'the second task did not run within 30 s' }); Kestrel's latest reaction: $(if ($reaction) { $reaction } else { 'none' }); counts: $(Get-CountRise $counts (Get-Counts))"
    }
    Add-Result voice "${name}: $(if ($done -eq 0) { 'skipped' } else { "$hits of $done tries" })"
}

function Invoke-VoiceStep {
    $quiet = { Wait-Quiet }
    if ((Request-Owner 'Next you talk to Kestrel through the phone''s own speaker and microphone, without earbuds, in a quiet room, for about 20 minutes. For each try the script shows what to say: say it once, as you would say it to Kestrel, and then press Enter at once, whether or not the phone reacts. Press Enter to begin, or s to skip.' 'go') -eq 'skip') {
        Add-Result voice 'skipped'
        return
    }
    $waited = { param($Counts, $State) $State -and [int](Get-Counts)['waits'] -gt [int]$Counts['waits'] }
    Measure-Tries voice '"Kestrel"' 'Say "Kestrel". Kestrel should say "Yes?" and wait; the script ends the wait itself.' 5 $quiet $waited 10 -DryRunHeard '+Kestrel'
    Measure-Tries voice '"Hey Kestrel"' 'Say "Hey Kestrel". Kestrel should say "Yes?" and wait; the script ends the wait itself.' 5 $quiet $waited 10 -DryRunHeard '+Hey Kestrel.'
    $paused = {
        Wait-Quiet
        Invoke-Hook voice @{ Send = 'pause' } | Out-Null
        Wait-Mode paused 10 | Out-Null
    }
    Measure-Tries voice '"Kestrel, wake up" while paused' 'Kestrel is paused. Say "Kestrel, wake up". Kestrel should listen again; its notification then says that it listens.' 5 $paused { param($Counts, $State) $Counts -and $State.mode -eq 'listening' } 10 -DryRunHeard '+Kestrel, wake up.'
    # Each task now takes a second and then reads a long reply without any of Kestrel's keywords, which the owner
    # interrupts. A pause shorter than the speech detector's 0.7 s joins the request to Kestrel's own voice, which the
    # pipeline drops after an interruption (analysis, item 8), so the pauses start at 1 s.
    $story = 'Here is a long reply, so that you can interrupt it. ' + ('The news of the day comes one sentence after another, at an even pace. ' * 6)
    Invoke-Hook voice-task @{ Seconds = 1; Reply = $story } | Out-Null
    try {
        foreach ($pause in '1', '1.5', '2') { Measure-Interruption $pause }
    } finally {
        Invoke-Hook voice-task @{ Clear = $true } | Out-Null
    }
    Measure-Tries voice '"Stop Kestrel"' 'Say "Stop Kestrel". Kestrel should turn off; its notification then goes.' 3 $quiet { param($Counts, $State) $Counts -and $State.mode -eq 'off' } 10 -DryRunHeard 'Stop Kestrel.'
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
    # The restore of Kokoro forgets its measure, and the cold start below is timed.
    Initialize-KokoroSpeed memory
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    # A cold start: the tile starts the service in a new process, which must reach startForeground() in time. The script
    # ends Kestrel's process itself, which the debug build allows, so that a kill that Android refuses cannot look like
    # a new process (analysis, item 7).
    $restore = Register-AccessibilityRestore
    try {
        if ((Request-Owner 'Next comes a cold start through the tile: the script turns Kestrel''s accessibility service off and ends Kestrel''s process, and then you tap the tile once. Check that Kestrel''s tile is in Quick Settings. Press Enter to go ahead, or s to skip.' 'go') -eq 'skip') {
            Add-Result memory 'a cold start through the tile: skipped'
            return
        }
        # Before the kill: SystemUI binds the tile's service, which starts Kestrel's process, as on the owner's phone.
        if ($DryRun) { Add-DryRunTile }
        Invoke-Checked (Get-ServicesCommand @(Get-OtherServices $restore.Data)) | Out-Null
        if (-not (Wait-KestrelService off)) { throw 'Kestrel''s accessibility service did not turn off within 30 s.' }
        $old = Get-KestrelProcess
        $state = Get-ProcessState -Dump @(Invoke-Checked 'dumpsys activity processes dev.kestrel')
        if ($old) { Invoke-Checked "run-as dev.kestrel kill -9 $old" | Out-Null }
        Start-Sleep -Seconds 2
        $now = Get-KestrelProcess
        $ended = if (-not $old) { 'no process ran' } elseif (-not $now) { 'it ended' } elseif ($now -ne $old) { 'a new process started at once' } else { 'it did not end' }
        Add-Result memory "a cold start through the tile: Kestrel's process before the kill: $state; after 'run-as dev.kestrel kill -9': $ended"
        # A process that Android started at once is seconds old and has loaded no model, so the tap still checks a young
        # process; only a process that did not end makes the tap pointless.
        if ($now -and $now -eq $old) {
            Add-Result memory 'a cold start through the tile: not tried, because Kestrel''s process did not end'
            return
        }
        Request-Owner 'Now swipe down to Quick Settings and tap Kestrel''s tile once. Kestrel shows nothing on the screen when it starts, only its notification. Then press Enter at once, whether or not the phone reacts.' 'go' | Out-Null
        if ($DryRun) { Invoke-DryRunTileTap }
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
        $counted = "$([math]::Round($hours * 60, 1)) minutes timed by $timedBy; batterystats counted the phone as on battery at the end: $(if ($null -eq $onBattery) { 'not shown' } else { $onBattery }); Kestrel's accessibility service: $(Get-KestrelServiceState); restarts of listening: $restarts; instant commands that ran: $($lastCommand - $firstCommand); the screen was off at a poll: $screenOff; Kestrel transcribed $([math]::Round($audio, 2)) minutes of audio, $minutesPerHour minutes per hour; counts: $(Get-CountRise $before $after)"
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
        if ((Request-Owner "Battery, measure 1 of 3, $minutes minutes: a quiet room. Put the phone on a table, unlocked and plugged into this computer; its screen stays on, and it shows only the home screen. Nobody speaks near it, and no television or music plays. You may leave. Press Enter to start, or s to skip this measure." 'go') -ne 'skip') {
            $quiet = Measure-Battery 'measure 1, a quiet room' $minutes $uid
        } else {
            Add-Result battery 'measure 1, a quiet room: skipped'
        }
        $clips = @(Get-CommandClips)
        if (-not $DryRun -and $clips.Count -eq 0) {
            Add-Result battery 'measure 2, typical use: skipped, because corpus\generated\piper has no clips of commands with Kestrel''s name; run scripts\make-corpus.ps1 first'
        } elseif ((Request-Owner "Battery, measure 2 of 3, $minutes minutes: the same quiet room, while this computer's speaker plays a short task for Kestrel every 5 minutes, such as ""Kestrel, what's the weather tomorrow?"". Put the phone about 1 m from the speaker, and set the speaker as loud as a voice in the room. Nobody else speaks. Kestrel answers each task and goes on listening. You may leave. Press Enter to start, or s to skip this measure." 'go') -ne 'skip') {
            $every = if ($DryRun) {
                { Send-Heard "+$((Read-Corpus -Path $corpusFile | Where-Object { $_.Id -eq $batteryClipIds[0] }).Text)" }
            } else {
                {
                    $player = [System.Media.SoundPlayer]::new($clips[$script:clipIndex % $clips.Count].File)
                    try { $player.PlaySync() } finally { $player.Dispose() }
                    $script:clipIndex++
                }
            }
            $script:clipIndex = 0
            Add-Result battery "measure 2 plays the clips $(@($clips | ForEach-Object { $_.Id }) -join ', ')$(if ($DryRun) { ' (in a dry run, the voice hook sends the first one''s text)' })"
            $typical = Measure-Battery 'measure 2, typical use' $minutes $uid $every
        } else {
            Add-Result battery 'measure 2, typical use: skipped'
        }
        if ((Request-Owner "Battery, measure 3 of 3, $minutes minutes: ordinary room sound, as in the session of 2026-11-17. Let people talk or a television play near the phone, which lies there unlocked; nobody speaks to Kestrel. Kestrel may act on what it hears, for example scroll or open an app; let it, and do not touch the phone. Press Enter once the sound plays, or s to skip this measure." 'go') -ne 'skip') {
            $talk = Measure-Battery 'measure 3, room talk' $minutes $uid
        } else {
            Add-Result battery 'measure 3, room talk: skipped'
        }
        # The cost comes from the measure with the most transcribed audio, and the other one checks the model.
        $costly = @($typical, $talk) | Where-Object { $_ } | Sort-Object MinutesPerHour -Descending | Select-Object -First 1
        $cost = if ($quiet -and $costly) { Get-BatteryCost -Baseline $quiet.PercentPerHour -PercentPerHour $costly.PercentPerHour -MinutesPerHour $costly.MinutesPerHour }
        if ($null -eq $cost) {
            Add-Result battery 'the model: not computed, because the quiet measure or a measure with transcribed audio is missing'
        } else {
            $check = @($typical, $talk) | Where-Object { $_ -and $_ -ne $costly } | Select-Object -First 1
            $predicted = if ($check) { "; for the other measure it predicts $([math]::Round($quiet.PercentPerHour + $cost * $check.MinutesPerHour, 2)) % per hour, and $($check.PercentPerHour) % per hour was measured" } else { '' }
            Add-Result battery "the model: % per hour = $($quiet.PercentPerHour) + $cost x the minutes of audio that Kestrel transcribes in an hour$predicted"
        }
    } finally {
        Complete-Restore $timeoutRestore
    }
}

$plan = @(
    @{ Name = 'install'; Minutes = 10; Title = 'install Kestrel and copy the speech models with both of Kokoro''s models; you may leave'; Run = 'Invoke-InstallStep' }
    @{ Name = 'voice-tests'; Minutes = 40; Title = 'the voice device tests, among them the keyword spotter''s settings and Kokoro''s two models; you may leave'; Run = 'Invoke-VoiceTestStep' }
    @{ Name = 'failures'; Minutes = 1; Title = 'Kestrel''s logged failures since the voice tests'; Run = 'Invoke-FailuresStep' }
    @{ Name = 'listen-check'; Minutes = 12; Title = 'listen-check.ps1 with a restart of the phone; you unlock it once'; Run = 'Invoke-ListenCheckStep' }
    @{ Name = 'battery-rules'; Minutes = 10; Title = 'Android''s battery rules without the accessibility service; you look at the shade twice'; Run = 'Invoke-BatteryRulesStep' }
    @{ Name = 'activation'; Minutes = 8; Title = 'the shortcut, the button and the tile, with your hands'; Run = 'Invoke-ActivationStep' }
    @{ Name = 'assistant'; Minutes = 5; Title = 'Kestrel as the assistant without its accessibility service; no hands'; Run = 'Invoke-AssistantStep' }
    @{ Name = 'earbuds'; Minutes = 8; Title = 'the earbud microphone, with your earbuds, some music and your voice'; Run = 'Invoke-EarbudStep' }
    @{ Name = 'spotter'; Minutes = 16; Title = '"Hey Kestrel" and played speech with three settings of the keyword spotter; you speak, then stay quiet'; Run = 'Invoke-SpotterStep' }
    @{ Name = 'voice'; Minutes = 20; Title = 'talking to Kestrel, with your voice'; Run = 'Invoke-VoiceStep' }
    @{ Name = 'memory'; Minutes = 12; Title = 'memory with Piper and Kokoro, and a cold start through the tile, which you tap once'; Run = 'Invoke-MemoryStep' }
    @{ Name = 'battery'; Minutes = 95; Title = 'three battery measures of 30 minutes; you may leave during the first two'; Run = 'Invoke-BatteryStep' }
    @{ Name = 'failures-late'; Minutes = 1; Title = 'Kestrel''s logged failures since listen-check'; Run = 'Invoke-LateFailuresStep' }
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
        # The restores that are left, the newest first, so that the screen, the hooks' tasks and Kestrel's mode come last.
        $left = @($pending.ToArray())
        [array]::Reverse($left)
        Invoke-Restore $left
    } finally {
        if (-not ($completed -or $stopped)) { Add-SessionResult -Path $resultsFile -Step session -Text 'stopped, as by Ctrl+C' }
        if ($earbudsChanged) { Write-Host 'If you have not done it yet, turn "Use the earbud microphone" off in Kestrel''s settings, unless you want to keep it.' }
        # The restores of battery-rules, activation, assistant and memory can leave Kestrel's notices.
        if ($prepared -and -not $DryRun) { Write-Host 'Kestrel''s notices, such as that its accessibility service is off, that Android stopped its listening, or "Open Kestrel''s settings", may still be in the notification shade; swipe them away.' }
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
