# The two sittings of sub-project 5 (design D30) on the Galaxy A71 and kestrel-api37. Sitting 1 records and needs no
# model: before the owner's step the script installs Kestrel and the fixture app, copies the models with the OCR files,
# turns test mode on, sets the owner's names and checks which accounts and apps the A71 has; the owner then installs the
# missing apps from Play while he watches, signs in to Telegram, links WhatsApp, signs the test Google account in on
# kestrel-api37, sets Waze's Home on both devices and touches the A71's screen once (about 10 to 25 minutes of his
# hands); then, without him, the script prepares the devices, runs the A71's device tests, probes the hotspot, records the
# structure of the self-chats, the pickers and SystemUI, and plays the speech checks of scripts\lib\AgentChecks.ps1
# through this computer's speakers. Sitting 2 calls the owner's Copilot: the owner signs Kestrel in to Copilot on
# kestrel-api37 (and on the A71 when sitting 1's device tests deleted its profile; 3 to 6 minutes of his hands), and then,
# without him, the script prepares both devices again, opens the devices' live sessions (hook agent-live) and runs
# scripts\task-check.ps1 -Live for the six tasks of design D3 on the A71 and the emulator's tasks on kestrel-api37, 3 runs
# each, the comparison with -CompareModel, look_closer on the OCR fixture, the 14 injection fixtures and the cache record;
# it stops when the sitting's AI Credits reach 150. Each sitting is one call with -Sitting 1 or 2; -Step runs only the
# named steps, also as one text separated by commas. A sitting 2 that stopped goes on with a call with -Step and the
# steps that remain, with prepare first (only prepare opens the live sessions); such a call counts on from the AI
# Credits of the earlier calls and reads their results too, and only a call that runs the step end deletes the
# emulator's Copilot profile and removes its test account, so that the next call needs no new sign-in. A new sitting 2
# is a call without -Step. Before and after each step the script reads both devices' call state, and ends a call with
# the end-call key.
# The A71's test PIN is typed only by Unlock-TestPhone (scripts\lib\Session.ps1), which alone reads the key "pin" of
# corpus\owner\a71-config.json; this script reads the file only through Read-A71Config, which leaves the key out. The
# script also needs the keys whatsappOwnName, gmailAddress, homePlace, testLocation ("<latitude>,<longitude>") and
# testStreet; sitting 2 also reads emulatorGmailAddress, the test account's address on kestrel-api37, for the e-mail
# task there, which it skips without it. It refuses every device but the A71 and kestrel-api37, keeps its results in
# corpus\owner (which git
# ignores), and never prints a prompt, a reply, screen text, a message, an e-mail, a notification or a word that Kestrel
# heard: the hooks answer only counts, kinds, states and hashes. Every change is registered with its restore before it is
# made, and the restores run however the sitting ends (its end, q, Ctrl+C, an error); the last lines name what could not
# be put back and how to do it by hand; the fixture app leaves the A71 again when the sitting installed it. Each sitting
# first closes a live session (agent-live) that an earlier run may have left open. The A71 stays awake during the sitting
# and is locked at its end. A real sitting
# runs inside the emulator's lock (process rule 9): with -LockHeld the controller holds .superpowers\sdd\emulator.lock
# and the script only checks that it exists; without it, the script takes the lock itself, waits up to 60 minutes, and
# deletes its own lock at the end. -DryRun runs every step on kestrel-api37 inside the controller's lock, which it never
# touches: it answers the owner's items itself, uses a fake configuration, plays the clips through Kestrel's stand-in
# microphone and runs the stand-in variants of the tasks with the scripted model, so that no request reaches a provider.
# It needs PowerShell 7.3 or newer, as scripts\hook.ps1 does. After Ctrl+C, it ends with exit code 1.
# Example: scripts\a71-agent.ps1 -Serial R58N66RVD7Z -EmulatorSerial emulator-5554 -Sitting 1 -LockHeld
#Requires -Version 7.3
param(
    [string]$Serial,
    [string]$EmulatorSerial = 'emulator-5554',
    [ValidateSet('1', '2')][string]$Sitting = '1',
    # The lightweight model of sitting 2's comparison (design D32 item 35), as Copilot names it. Plan 4D's providers run
    # passed GPT-6 Luna, GPT-6.1 Terra and Gemini 3.8 Flash on both devices and got 404 for Claude Haiku 5.5.
    [string]$CompareModel = 'gemini-3.8-flash',
    [switch]$DryRun,
    [switch]$LockHeld,
    [string[]]$Step
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
. "$PSScriptRoot\lib\Session.ps1"
. "$PSScriptRoot\lib\Oracle.ps1"
. "$PSScriptRoot\lib\Corpus.ps1"
. "$PSScriptRoot\lib\Speaker.ps1"
. "$PSScriptRoot\lib\AutoCheck.ps1"
. "$PSScriptRoot\lib\AgentChecks.ps1"
Use-KestrelToolchain

$stepNames = @{
    '1' = @('prepare', 'owner', 'setup', 'device-tests', 'hotspot', 'recordings', 'speech', 'end')
    '2' = @('owner', 'prepare', 'a71-runs', 'emulator-runs', 'compare', 'look-closer', 'injection', 'cache', 'end')
}

# The names of -Step, also as one text separated by commas, as pwsh -File passes them; an unknown name stops the script.
function Get-SessionSteps([string[]]$Step, [string[]]$Names) {
    $chosen = @($Step | ForEach-Object { "$_" -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    $unknown = @($chosen | Where-Object { $_ -notin $Names })
    if ($unknown) { throw "Unknown steps: $($unknown -join ', '). The steps are: $($Names -join ', ')." }
    $chosen
}

# The owner's answers that the sittings need, from corpus\owner\a71-config.json through Read-A71Config, which leaves out
# the key "pin": the name that WhatsApp shows with "(You)", the address of Gmail's account on the A71, the public place
# that Waze's Home is, the test location of the A71 ("<latitude>,<longitude>", a public place a few kilometres from
# Home) and the street that Waze shows there. A missing key, or a test location of another form, throws a message that
# names only the keys; no value reaches a message.
function Get-AgentConfig([string]$Path) {
    $config = Read-A71Config -Path $Path
    $missing = @(foreach ($key in 'whatsappOwnName', 'gmailAddress', 'homePlace', 'testLocation', 'testStreet') {
            if (-not $config.PSObject.Properties[$key] -or -not "$($config.$key)".Trim()) { $key }
        })
    if ($missing) { throw "corpus\owner\a71-config.json lacks the keys $($missing -join ', '), which the sittings of sub-project 5 need." }
    if ("$($config.testLocation)" -notmatch '^-?\d{1,2}\.\d+,-?\d{1,3}\.\d+$') { throw 'The key testLocation of corpus\owner\a71-config.json must be "<latitude>,<longitude>", such as "44.8100,20.4600".' }
    $config
}

# The script's own record of what a sitting found and what sitting 2 needs (the touch branch and rule, the hotspot's
# result, whether Waze shows the test location, whether the A71 keeps its Copilot profile, whether Telegram's Saved
# Messages was recorded and Gmail's discard worked on the A71, the apps that the owner's step installed, Play's state and
# the browsers' versions on kestrel-api37, and sitting 2's AI Credits and results files), in a JSON file of
# corpus\owner. It holds no name, text or address.
function Read-AgentRecord([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return [pscustomobject]@{} }
    Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
}

function Set-AgentRecord([string]$Path, [string]$Name, $Value) {
    $record = Read-AgentRecord $Path
    $record | Add-Member -NotePropertyName $Name -NotePropertyValue $Value -Force
    [IO.File]::WriteAllText($Path, ($record | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
}

# The number of Google accounts in 'dumpsys account', from the type only, never a name.
function Get-GoogleAccountCount([string[]]$Dump) {
    @($Dump | Where-Object { $_ -match '^\s*Account \{.*type=com\.google\}' }).Count
}

# The first versionName in 'dumpsys package <package>', or 'none'.
function Get-VersionName([string[]]$Dump) {
    foreach ($line in $Dump) { if ($line -match '^\s*versionName=(\S+)') { return $Matches[1] } }
    'none'
}

# Whether 'pm list packages <package>' lists exactly that package.
function Test-PackageListed([string[]]$List, [string]$Package) { @($List | ForEach-Object { "$_".Trim() }) -contains "package:$Package" }

# Whether 'dumpsys connectivity' shows a Wi-Fi network that Android validated.
function Test-NetworkValidated([string[]]$Dump) {
    @($Dump | Where-Object { $_ -match 'NetworkAgentInfo' -and $_ -match 'Transports: WIFI' -and $_ -match '\bVALIDATED\b' }).Count -gt 0
}

# Whether 'dumpsys tethering' lists a Wi-Fi interface (wlan, swlan or ap) in TetheredState or LocalHotspotState (r60).
function Test-HotspotOn([string[]]$Dump) {
    @($Dump | Where-Object { $_ -match '^\s*(s?wlan\d+|ap\d+)\s+-\s+(TetheredState|LocalHotspotState)\b' }).Count -gt 0
}

# The touch rule that D19's probe calls for (plan 5B's OwnActionsTest, logcat tag KestrelProbe): CREDITS when a node
# action of Kestrel's (a click, long click, scroll, set text, focus or refused click) reached the watcher, NONE when none
# did. FIELD needs API 37 (plan 5B), so it is never chosen here. The probe's test checks the echoes' kinds and delays on
# every API level (plan 5B's final fixes), so only a probe that passed ($Passed) chooses a rule. $null when the probe
# failed or wrote no line.
function Get-TouchRuleFromProbe([string[]]$Lines, [bool]$Passed) {
    if (-not $Passed) { return $null }
    $cases = @($Lines | Where-Object { $_ -match 'case=(\S+) .*touches=(\d+)' } | ForEach-Object {
            if ("$_" -match 'case=(\S+) .*touches=(\d+)') { [pscustomobject]@{ Case = $Matches[1]; Touches = [int]$Matches[2] } }
        })
    if (-not $cases) { return $null }
    $echoes = @($cases | Where-Object { $_.Case -in 'click', 'long-click', 'scroll', 'set-text', 'ime-focus', 'refused-click' -and $_.Touches -gt 0 })
    if ($echoes) { 'CREDITS' } else { 'NONE' }
}

# The OCR test's measures (plan 5C's OcrDeviceTest, logcat tag KestrelOcr), one short text per line, numbers only.
function Get-OcrMeasures([string[]]$Lines) {
    @($Lines | Where-Object { $_ -match 'ocr (image=\S+ limit=\d+ threads=\d+ loadMs=\d+ detectMs=\d+ recogniseMs=\d+ lines=\d+ pssKb=\d+)' } | ForEach-Object {
            if ("$_" -match 'ocr (image=\S+ limit=\d+ threads=\d+ loadMs=\d+ detectMs=\d+ recogniseMs=\d+ lines=\d+ pssKb=\d+)') { $Matches[1] }
        })
}

# The centre of the bounds that screen-has gives ('left,top,right,bottom'), as 'x y' for 'input tap', or $null.
function Get-BoundsCentre([string]$Bounds) {
    if ($Bounds -notmatch '^(-?\d+),(-?\d+),(-?\d+),(-?\d+)$') { return $null }
    "$([int](([int]$Matches[1] + [int]$Matches[3]) / 2)) $([int](([int]$Matches[2] + [int]$Matches[4]) / 2))"
}

# A run's line of scripts\task-check.ps1 in the results file ('<time><tab><step><tab>task=<name> run=<i> result=...'), as
# an ordered table, or $null for another line.
function ConvertFrom-TaskLine([string]$Line) {
    $text = ("$Line" -split "`t")[-1]
    if ($text -notmatch '^task=\S+ run=\d+ ') { return $null }
    ConvertFrom-HookPairs $text
}

# The AI Credits of the runs' lines, in the invariant culture.
function Get-CreditTotal([object[]]$Runs) {
    $total = 0.0
    foreach ($run in $Runs) {
        if ($run -and "$($run['credits'])" -match '^\d+(\.\d+)?$') { $total += [double]::Parse($run['credits'], [Globalization.CultureInfo]::InvariantCulture) }
    }
    $total
}

# The first name for a test variant (design D30 step 16; review C3-M5): $Default when the owner's Telegram chat list has
# no row with it ($Rows: name to the count that screen-has found), else the first of Mira, Zora, Ivo and Teo that has no
# row and is not in $Taken; $null when every name has a row.
function Select-TestName([string]$Default, [hashtable]$Rows, [string[]]$Taken = @()) {
    foreach ($name in @($Default, 'Mira', 'Zora', 'Ivo', 'Teo')) {
        if ($name -notin $Taken -and [int]$Rows[$name] -eq 0) { return $name }
    }
    $null
}

# The share of input tokens that each model read from the cache (design D5), from the runs' lines: one text per model,
# with the model's ID, its tokens and the share in percent.
function Get-CacheShares([object[]]$Runs) {
    $models = [ordered]@{}
    foreach ($run in $Runs) {
        if (-not $run -or "$($run['model'])" -in '', 'none') { continue }
        $model = "$($run['model'])"
        if (-not $models.Contains($model)) { $models[$model] = [pscustomobject]@{ Model = $model; Input = 0L; CacheRead = 0L } }
        $models[$model].Input += [long]$run['in']
        $models[$model].CacheRead += [long]$run['cacheRead']
    }
    foreach ($entry in $models.Values) {
        $share = if ($entry.Input -gt 0) { [math]::Round(100.0 * $entry.CacheRead / $entry.Input, 1) } else { 0 }
        [pscustomobject]@{ Model = $entry.Model; Input = $entry.Input; CacheRead = $entry.CacheRead; Share = $share }
    }
}

# The cache fields of agent-cache that a Copilot model uses (design D5): the explicit fields of /responses for OpenAI's
# models, the markers of /chat/completions and /messages for Claude, and none for Gemini, which caches only implicitly.
function Get-CacheFields([string]$Model) {
    if ($Model -match '^(gpt|o\d)') { return @('responsesExplicit') }
    if ($Model -match '^claude') { return @('chatCopilotMarks', 'anthropicMarks') }
    @()
}

# The task of scripts\task-check.ps1 that a dry run runs instead of a live task, with its script; $null when the live
# task has no stand-in, as Waze.
function Get-StandInTask([string]$Task) {
    $map = @{
        'telegram-late' = 'stand-in-messenger-late'; 'telegram-photos' = 'stand-in-messenger-photos'; 'read-email' = 'stand-in-read'
        'hotspot' = 'stand-in-hotspot'; 'firefox-wiki' = 'stand-in-firefox'
    }
    if ($Task -match '^inject-\d{2}$') { return [pscustomobject]@{ Task = $Task; Script = 'tasks\hostile.json' } }
    if (-not $map.ContainsKey($Task)) { return $null }
    [pscustomobject]@{ Task = $map[$Task]; Script = "tasks\$($map[$Task]).json" }
}

# A number with at most $Digits decimals, in the invariant culture, for the results.
function Format-Number([double]$Value, [int]$Digits = 1) { [math]::Round($Value, $Digits).ToString([Globalization.CultureInfo]::InvariantCulture) }

# One line per task and model of the runs, for design D32 item 35's comparison: the runs that passed of those that ran,
# the mean steps and working time, and the AI Credits in all.
function Get-RunSummary([object[]]$Runs) {
    $groups = [ordered]@{}
    foreach ($run in $Runs) {
        if (-not $run) { continue }
        $key = "$($run['task']) $($run['model'])"
        if (-not $groups.Contains($key)) { $groups[$key] = [System.Collections.Generic.List[object]]::new() }
        $groups[$key].Add($run)
    }
    foreach ($list in $groups.Values) {
        $ran = @($list | Where-Object { $_['result'] -ne 'skipped' })
        $passed = @($list | Where-Object { $_['result'] -eq 'pass' }).Count
        $steps = if ($ran) { (@($ran | ForEach-Object { [double]$_['steps'] }) | Measure-Object -Average).Average } else { 0 }
        $seconds = if ($ran) { (@($ran | ForEach-Object { [double]$_['workMs'] / 1000 }) | Measure-Object -Average).Average } else { 0 }
        "task=$($list[0]['task']) model=$($list[0]['model']) passed=$passed of $($ran.Count) steps=$(Format-Number $steps) seconds=$(Format-Number $seconds) credits=$(Format-Number (Get-CreditTotal $list) 2)"
    }
}

$Step = @(Get-SessionSteps -Step $Step -Names $stepNames[$Sitting])
if ($DryRun -and $Serial -and $Serial -notlike 'emulator-*') { throw 'A dry run runs only on the emulator kestrel-api37.' }
if (-not $DryRun -and -not $Serial) { throw 'Give the A71''s serial with -Serial.' }
# In a dry run, the A71's steps run on kestrel-api37 too.
if ($DryRun) { $Serial = $EmulatorSerial }
$owner = Join-Path $PSScriptRoot '..\corpus\owner'
New-Item -ItemType Directory -Force -Path $owner | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'
$resultsFile = Join-Path $owner "agent-sitting$Sitting-$(if ($DryRun) { 'dry-run-' })$stamp.txt"
$recordFile = Join-Path $owner "a71-agent-record$(if ($DryRun) { '-dry-run' }).json"
$generated = Join-Path $PSScriptRoot '..\corpus\generated'
$adb = Get-Adb
$gradleUsed = $false
# A real sitting reads the owner's answers, and checks that the test PIN is there, before any command reaches a device;
# a dry run uses a fake configuration and never opens the owner's file.
$pinConfig = if ($DryRun) { $null } else { Join-Path $owner 'a71-config.json' }
$config = if ($DryRun) {
    [pscustomobject]@{ SpeakerTargetDb = -26.0; whatsappOwnName = 'Test Owner'; gmailAddress = 'kestrel.test@example.com'; homePlace = 'Belgrade Fortress'; testLocation = '44.8100,20.4600'; testStreet = 'Test Street'; emulatorGmailAddress = 'kestrel.test@example.com' }
} else {
    Get-AgentConfig $pinConfig
}
if ($pinConfig) { Send-TestPin -ConfigPath $pinConfig -CheckOnly }
$lockFile = Join-Path $PSScriptRoot '..\.superpowers\sdd\emulator.lock'
$lockTaken = $false
# "Stay awake" of each device as it was before the sitting changed it.
$stayOn = [ordered]@{}
# Sitting 2: the AI Credits that its runs spent, and its ceiling (Q5), at which the sitting stops; the first names of
# the test variants (design D30 step 16); and whether Gmail on the A71 shows the address of gmailAddress.
$credits = 0.0
$creditCeiling = 150
$ceilingMessage = 'The runs reached the sitting''s ceiling of 150 AI Credits.'
$testNames = @{ Ana = 'Ana'; John = 'John' }
$gmailChecked = $false
# A call with -Step after a stop counts on from the AI Credits that the earlier calls of this sitting 2 kept in the
# record (Q5); a call without -Step starts a new sitting 2 and sets them to 0 (Add-SittingResults).
$savedCredits = (Read-AgentRecord $recordFile).sitting2Credits
if ($Sitting -eq '2' -and $null -ne $savedCredits) { $credits = [double]$savedCredits }
$compareRefused = "The owner's Copilot account does not list the model $CompareModel, so the comparison does not run; give -CompareModel one of the models that Kestrel's page ""Providers and profiles"" shows for the Copilot profile."
# Whether a dry run registered the restore of kestrel-api37's touch rule and branch (Register-TouchSourceRestore).
$touchSourceSaved = $false

# The first line of an error, for the results: any e-mail address, a mailto: URI and each text value of the owner's
# configuration are left out, because a command that fails, such as Gmail's mailto:, names them in its error
# (task-5c-9-review M5, task-5c-10-review M1).
function Format-Failure($ErrorRecord) {
    $text = ("$($ErrorRecord.Exception.Message)" -split "`r?`n")[0]
    $text = $text -replace '[\w.+-]+@[\w-]+(\.[\w-]+)+', '<address>' -replace 'mailto:\S+', 'mailto:<address>'
    foreach ($property in @($config.PSObject.Properties)) {
        $value = if ($property.Value -is [string]) { $property.Value.Trim() } else { '' }
        if ($value.Length -ge 3) { $text = [regex]::Replace($text, [regex]::Escape($value), '<value>', 'IgnoreCase') }
    }
    $text
}

function Get-DeviceName([string]$Device) { if ($Device -eq $EmulatorSerial) { 'kestrel-api37' } else { 'the A71' } }

function Add-Result([string]$StepName, [string]$Text) {
    Add-SessionResult -Path $resultsFile -Step $StepName -Text $Text
    Write-Host "${StepName}: $Text"
}

# Runs scripts\hook.ps1 on a device and returns the answer as one text.
function Invoke-DeviceHook([string]$Device, [string]$Command, [hashtable]$With = @{}) {
    (& "$PSScriptRoot\hook.ps1" -Serial $Device -Command $Command @With) -join "`n"
}

# The form that the check runner (scripts\lib\AutoCheck.ps1) and the speech checks call: the device is $Serial of the
# caller, which a step that runs them on kestrel-api37 sets for its own call.
function Invoke-Hook([string]$Command, [hashtable]$With = @{}) { Invoke-DeviceHook $Serial $Command $With }

function Invoke-Device([string]$Device, [string]$Command) { Invoke-SettingsCommand -Serial $Device -Command $Command }

# One item of the owner's: this computer beeps, and the prompt says what he does and what the phone shows; Enter starts
# it, s skips it and q stops the sitting. A dry run answers $DryRunAnswer itself.
function Request-OwnerItem([string]$Prompt, [string]$DryRunAnswer = 'go') {
    if ($DryRun) {
        Write-Host "[dry run] $Prompt"
        return $DryRunAnswer
    }
    try { [console]::Beep(880, 300) } catch { Write-Verbose 'This console cannot beep.' }
    $answer = "$(Read-Host $Prompt)".Trim().ToLowerInvariant()
    if ($answer -eq 'q') { throw 'The owner stopped the sitting.' }
    if ($answer -in 's', 'skip') { 'skip' } else { 'go' }
}

# What the script has still to put back. Each restore enters the list before its change and leaves it when it worked;
# one that failed keeps its reason for the message at the end, which says how to do it by hand.
$pending = [System.Collections.Generic.List[object]]::new()

function Register-Restore([string]$What, [string]$ByHand, $Data, [scriptblock]$Action) {
    $restore = [pscustomobject]@{ What = $What; ByHand = $ByHand; Data = $Data; Action = $Action; Reason = 'the sitting ended before it' }
    $pending.Add($restore)
    $restore
}

# Runs a restore that is still pending, with its data, and keeps it pending, with the reason, when it fails.
function Complete-Restore($Restore) {
    if (-not $pending.Contains($Restore)) { return }
    try {
        & $Restore.Action $Restore.Data | Out-Null
        $pending.Remove($Restore) | Out-Null
    } catch {
        $Restore.Reason = Format-Failure $_
    }
}

# Runs the restores at the sitting's end, newest first, each in the finally block of the one before, so that a stop such
# as Ctrl+C during one of them still runs the others.
function Invoke-Restore([object[]]$Restores, [int]$Index = 0) {
    if ($Index -ge $Restores.Count) { return }
    try {
        Complete-Restore $Restores[$Index]
    } finally {
        Invoke-Restore $Restores ($Index + 1)
    }
}

# Unlocks the A71 with the owner's test PIN through Unlock-TestPhone (scripts\lib\Session.ps1), and returns whether it
# is unlocked. An unlock that did not work stops every later unlock of the sitting. The emulator is only woken.
function Unlock-Device([string]$Device) {
    if ($Device -eq $EmulatorSerial) {
        Invoke-Device $Device 'input keyevent KEYCODE_WAKEUP' | Out-Null
        return $true
    }
    if ($script:unlockFailed) { return $false }
    $why = 'the A71 did not show its PIN pad clearly, so the script typed nothing'
    $unlocked = try { Unlock-TestPhone -Serial $Device -ConfigPath $pinConfig } catch { $why = Format-Failure $_; $false }
    if (-not $unlocked) {
        $script:unlockFailed = $true
        Add-Result session "$why; the sitting tries no more unlocks and skips the steps that need an unlocked A71"
    }
    $unlocked
}

# "Stay awake" on the device until the sitting ends; the setting comes back at the end, also after a failure or Ctrl+C
# (process rule 15).
function Enable-StayAwake([string]$Device) {
    if ($stayOn.Contains($Device)) { return }
    $stayOn[$Device] = ((Invoke-Device $Device 'settings get global stay_on_while_plugged_in') -join '').Trim()
    Invoke-Device $Device 'svc power stayon true' | Out-Null
}

# Kestrel's state from the voice-state hook, or $null while Kestrel does not answer.
function Get-VoiceState([string]$Device) {
    try { ConvertFrom-VoiceState (Invoke-DeviceHook $Device voice-state) } catch { $null }
}

# Waits until Kestrel is in the mode, with its voice session open, or closed for 'off'. Returns whether it is.
function Wait-Mode([string]$Device, [string]$Mode, [double]$Seconds) {
    $session = if ($Mode -eq 'off') { 'closed' } else { 'open' }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $state = Get-VoiceState $Device
        if ($state -and $state.mode -eq $Mode -and $state.session -eq $session) { return $true }
        Start-Sleep -Milliseconds 500
    } while ($watch.Elapsed.TotalSeconds -lt $Seconds)
    $false
}

# The restores of Kestrel's mode as it was and of its own microphone on the device, registered once per device. Every
# call registers them at its start, before any step that can start Kestrel listening, such as device-tests in a call
# with -Step (final-5c-review-b M1), and Start-Listening registers them too when they are missing. Kestrel's answer is
# awaited up to 10 s, because Android binds its accessibility service a moment after the setting.
$listening = @{}
function Register-ListeningRestore([string]$Device) {
    if ($listening.ContainsKey($Device)) { return }
    $before = $null
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while (-not $before -and $watch.Elapsed.TotalSeconds -lt 10) {
        $before = Get-VoiceState $Device
        if (-not $before) { Start-Sleep -Seconds 1 }
    }
    $listening[$Device] = $null
    Register-Restore "Kestrel's own microphone on $(Get-DeviceName $Device)" "Run: scripts\hook.ps1 -Serial $Device voice-clip -Clear, then turn Kestrel off and on." @{ Device = $Device; Listened = [bool]($before -and $before.mode -ne 'off') } {
        param($Data)
        Invoke-DeviceHook $Data.Device voice-clip @{ Clear = $true } | Out-Null
        # The microphone comes back from Kestrel's next start, so a Kestrel that listened before the sitting starts again.
        if ($Data.Listened) {
            Invoke-DeviceHook $Data.Device voice @{ Send = 'off' } | Out-Null
            if (-not (Wait-Mode $Data.Device off 30)) { throw 'Kestrel did not turn off within 30 s' }
            Invoke-Device $Data.Device 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
            Invoke-DeviceHook $Data.Device voice @{ Send = 'toggle' } | Out-Null
            Start-Sleep -Seconds 1
            $after = Get-VoiceState $Data.Device
            if ($after -and $after.mode -eq 'paused') { Invoke-DeviceHook $Data.Device voice @{ Send = 'resume' } | Out-Null }
        }
    } | Out-Null
    if (-not $before -or $before.mode -eq 'off') {
        Register-Restore "Kestrel's mode Off on $(Get-DeviceName $Device)" "Run: scripts\hook.ps1 -Serial $Device voice -Send off" $Device {
            param($Device)
            Invoke-DeviceHook $Device voice @{ Send = 'off' } | Out-Null
        } | Out-Null
    }
}

# Kestrel listening on the device, from the front, with the silent stand-in microphone ('quiet', so that no sound of the
# room reaches it during text-driven runs) or its own microphone ('own', for the speech checks). A change of the
# microphone counts from Kestrel's next start, so Kestrel turns off first.
function Start-Listening([string]$Device, [ValidateSet('quiet', 'own')][string]$Microphone) {
    Register-ListeningRestore $Device
    if ($listening[$Device] -eq $Microphone -and (Wait-Mode $Device listening 1)) { return }
    $state = Get-VoiceState $Device
    if ($state -and $state.mode -ne 'off') {
        Invoke-DeviceHook $Device voice @{ Send = 'off' } | Out-Null
        if (-not (Wait-Mode $Device off 30)) { throw "Kestrel on $(Get-DeviceName $Device) did not turn off within 30 s." }
    }
    Invoke-DeviceHook $Device voice-clip $(if ($Microphone -eq 'quiet') { @{ Quiet = $true } } else { @{ Clear = $true } }) | Out-Null
    $listening[$Device] = $Microphone
    Invoke-Device $Device 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
    Invoke-DeviceHook $Device voice @{ Send = 'toggle' } | Out-Null
    Start-Sleep -Seconds 1
    $after = Get-VoiceState $Device
    if ($after -and $after.mode -eq 'paused') { Invoke-DeviceHook $Device voice @{ Send = 'resume' } | Out-Null }
    if (-not (Wait-Mode $Device listening 120)) { throw "Kestrel on $(Get-DeviceName $Device) did not start listening within 120 s." }
    Invoke-Device $Device 'input keyevent KEYCODE_HOME' | Out-Null
}

# Kestrel's runtime permissions for the tasks (design D29: granted with pm grant, revoked at the end): the photos and
# videos, and the location. Only those that Kestrel did not have are granted, and only those are revoked again.
function Grant-AgentPermissions([string]$Device) {
    $wanted = @('android.permission.READ_MEDIA_IMAGES', 'android.permission.READ_MEDIA_VIDEO', 'android.permission.ACCESS_FINE_LOCATION', 'android.permission.ACCESS_COARSE_LOCATION')
    $had = @(Get-GrantedPermissions -Dump (Invoke-Device $Device 'dumpsys package dev.kestrel') -Permissions $wanted)
    $grant = @($wanted | Where-Object { $_ -notin $had })
    if (-not $grant) { return }
    Register-Restore "Kestrel's runtime permissions on $(Get-DeviceName $Device)" "Settings > Apps > Kestrel > Permissions: set Photos and videos and Location to Don't allow." @{ Device = $Device; Grant = $grant } {
        param($Data)
        foreach ($permission in $Data.Grant) { Invoke-Device $Data.Device "pm revoke dev.kestrel $permission" | Out-Null }
    } | Out-Null
    foreach ($permission in $grant) { Invoke-Device $Device "pm grant dev.kestrel $permission" | Out-Null }
}

# Notification access for Kestrel's listener (design D9), revoked again at the end of each sitting.
function Enable-NotificationAccess([string]$Device) {
    $component = 'dev.kestrel/dev.kestrel.datatools.notifications.KestrelNotificationListener'
    Register-Restore "notification access for Kestrel on $(Get-DeviceName $Device)" 'Settings > Notifications > Device and app notifications (or Notification access): turn Kestrel off.' $Device {
        param($Device)
        Invoke-Device $Device 'cmd notification disallow_listener dev.kestrel/dev.kestrel.datatools.notifications.KestrelNotificationListener' | Out-Null
    } | Out-Null
    Invoke-Device $Device "cmd notification allow_listener $component" | Out-Null
}

# The owner's names for test mode's self-chat rules (design D18), through agent-settings; ownerEmails stays empty, so
# that test mode blocks every e-mail of the agent's (review B3-N2).
function Set-OwnerNames([string]$Device) {
    $answer = ConvertFrom-HookPairs (Invoke-DeviceHook $Device agent-settings @{ WhatsappOwnName = "$($config.whatsappOwnName)" })
    if ($answer['whatsappOwnName'] -ne 'set' -or $answer['ownerEmails'] -ne '0') { throw "agent-settings did not take the owner's name on $(Get-DeviceName $Device)." }
}

# Test mode on (design D18; process rule 10), checked through the hook state.
function Enable-TestMode([string]$Device) {
    Invoke-DeviceHook $Device test-mode @{ On = 'true' } | Out-Null
    if ((Invoke-DeviceHook $Device state) -notmatch '(^|\s)testMode=on(\s|$)') { throw "Test mode is not on on $(Get-DeviceName $Device)." }
}

# Closes the device's live session (hook agent-live -On false, which stores AGENT_LIVE_SESSION as off; design D28),
# checked through its answer. A device without Kestrel has none. Kestrel's activity starts first, because a stopped app
# gets no hook broadcast.
function Close-LiveSession([string]$Device) {
    if (-not (Test-Installed $Device 'dev.kestrel')) { return }
    Invoke-Device $Device 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
    Start-Sleep -Seconds 2
    if ((Invoke-DeviceHook $Device agent-live @{ On = 'false' }) -notmatch '(^|\s)live=off(\s|$)') { throw "The live session is still open on $(Get-DeviceName $Device)." }
    Invoke-Device $Device 'input keyevent KEYCODE_HOME' | Out-Null
}

# Whether the package is installed on the device.
function Test-Installed([string]$Device, [string]$Package) { Test-PackageListed @(Invoke-Device $Device "pm list packages $Package") $Package }

# Starts an app's launcher activity, or opens a URI in it.
function Open-App([string]$Device, [string]$Package) { Invoke-Device $Device "monkey -p $Package -c android.intent.category.LAUNCHER 1" | Out-Null }

# Taps the element whose label is $Label, compared on the device (hook screen-tap, the script's own action). The hook
# answers tapped=false error=refused, without the label, for what the agent would never press without the owner:
# anything in Kestrel's own app, a phone app or a contacts app, and in any app a call, emergency or SOS control (also
# SystemUI's emergency controls); a refusal ends the wait at once. Returns whether it tapped, waiting up to $Seconds for
# the element.
function Invoke-ScreenTap([string]$Device, [string]$Label, [int]$Seconds = 10) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $answer = ConvertFrom-HookPairs (Invoke-DeviceHook $Device screen-tap @{ Label = $Label })
        if ($answer['tapped'] -eq 'true') {
            Start-Sleep -Seconds 2
            return $true
        }
        if ($answer['error'] -eq 'refused') { return $false }
        Start-Sleep -Seconds 1
    } while ($watch.Elapsed.TotalSeconds -lt $Seconds)
    $false
}

# The count of reachable elements that match the filters (hook H3, screen-has), and only the count.
function Get-ScreenCount([string]$Device, [hashtable]$With) { [int](ConvertFrom-HookPairs (Invoke-DeviceHook $Device screen-has $With))['count'] }

# Installs an app from its Play page on the device: the script opens the page and taps Install (the script's own tap),
# and waits up to 5 minutes for the package. Returns whether it is installed.
function Install-FromPlay([string]$Device, [string]$Package) {
    Invoke-Device $Device "am start -a android.intent.action.VIEW -d market://details?id=$Package" | Out-Null
    Start-Sleep -Seconds 5
    Invoke-ScreenTap $Device 'Install' 20 | Out-Null
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalMinutes -lt 5) {
        if (Test-Installed $Device $Package) { return $true }
        Start-Sleep -Seconds 5
    }
    $false
}

# The shell as the A71's mock-location app (as scripts\spike-s1.ps1 does), with test providers for gps, network and
# fused at the test location, and a background job that sends a fresh fix every second (design D30 step 8; Q4). The
# restore stops the job, removes the providers and puts back the shell's mock-location mode and the location switch.
function Start-TestLocation([string]$Device) {
    $mode = 'default'
    if ("$((Invoke-Device $Device 'appops get com.android.shell android:mock_location') -join "`n")" -match '(?m)MOCK_LOCATION: (\w+)') { $mode = $Matches[1] }
    $enabled = ((Invoke-Device $Device 'cmd location is-location-enabled') -join '').Trim()
    $providers = 'gps network fused'
    $undo = "appops set com.android.shell android:mock_location allow; for p in $providers; do cmd location providers remove-test-provider `$p; done; appops set com.android.shell android:mock_location $mode; cmd location set-location-enabled $enabled"
    $restore = Register-Restore "the test location on $(Get-DeviceName $Device)" "Run: & '$adb' -s $Device shell '$undo'" @{ Device = $Device; Undo = $undo } {
        param($Data)
        if ($script:locationJob) {
            Stop-Job $script:locationJob
            Remove-Job $script:locationJob -Force
            $script:locationJob = $null
        }
        Invoke-Device $Data.Device $Data.Undo | Out-Null
    }
    Invoke-Device $Device "appops set com.android.shell android:mock_location allow; cmd location set-location-enabled true; for p in $providers; do cmd location providers add-test-provider `$p; cmd location providers set-test-provider-enabled `$p true; done" | Out-Null
    $fix = Format-TestDeviceCommand -Serial $Device -Command "for p in $providers; do cmd location providers set-test-provider-location `$p --location $($config.testLocation) --accuracy 5; done"
    $script:locationJob = Start-Job -ScriptBlock {
        while ($true) {
            & $using:adb -s $using:Device shell $using:fix 2>&1 | Out-Null
            Start-Sleep -Seconds 1
        }
    }
    $restore
}

# Runs a command on the device and reads its output as UTF-8, as scripts\hook.ps1 does: kotlinx.serialization writes a
# label with a letter outside ASCII, such as a Serbian name, as UTF-8, and PowerShell would otherwise read adb's output
# with the console's code page (task-5c-9-review M2).
function Read-DeviceText([string]$Device, [string]$Command) {
    $consoleEncoding = [Console]::OutputEncoding
    [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
    try {
        Invoke-Device $Device $Command
    } finally {
        [Console]::OutputEncoding = $consoleEncoding
    }
}

# Saves the structure that the hook agent-tree recorded on the device (design D28: classes, view IDs, bounds, flags and
# states, and only safe labels) into corpus\owner\trees, and deletes it on the device.
function Save-AgentTree([string]$Device, [string]$Name) {
    $answer = ConvertFrom-HookPairs (Invoke-DeviceHook $Device agent-tree)
    if ($answer['tree'] -ne 'written') { throw "agent-tree wrote nothing for $Name." }
    $folder = Join-Path $owner "trees\$stamp"
    New-Item -ItemType Directory -Force -Path $folder | Out-Null
    $json = (Read-DeviceText $Device 'run-as dev.kestrel cat cache/agent-tree.json') -join "`n"
    Invoke-Device $Device 'run-as dev.kestrel rm -f cache/agent-tree.json' | Out-Null
    $file = Join-Path $folder "$(if ($Device -eq $EmulatorSerial) { 'emulator' } else { 'a71' })-$Name.json"
    [IO.File]::WriteAllText($file, $json, [Text.UTF8Encoding]::new($false))
    "$Name recorded ($($answer['windows']) windows, $($answer['elements']) elements)"
}

# Deletes an earlier run's results of the connected tests, so that they never count as this run's.
function Clear-TestResults([string]$Folder) {
    if (Test-Path -LiteralPath $Folder) { Remove-Item -LiteralPath $Folder -Recurse -Force }
}

# Kestrel's accessibility service on the device, which the agent and the hooks of the screen need. The accessibility
# settings are saved before the change, and a restore puts them back.
function Enable-KestrelService([string]$Device) {
    $saved = Get-AccessibilitySettings -Serial $Device
    Register-Restore "the accessibility settings of $(Get-DeviceName $Device)" 'Put back the accessibility services in Settings > Accessibility.' @{ Device = $Device; Saved = $saved } {
        param($Data)
        if (-not (Restore-AccessibilitySettings -Serial $Data.Device -Saved $Data.Saved)) { throw 'see the warning above' }
    } | Out-Null
    $service = 'dev.kestrel/dev.kestrel.app.KestrelAccessibilityService'
    $enabled = if ($saved.Contains('enabled_accessibility_services')) { "$($saved['enabled_accessibility_services'])" } else { 'null' }
    $services = @(@($enabled -split ':') | Where-Object { $_ -and $_ -ne 'null' -and $_ -ne $service }) + $service
    if (-not (Set-SecureSetting -Serial $Device -Name 'enabled_accessibility_services' -Value ($services -join ':'))) { throw 'Could not turn on Kestrel''s accessibility service.' }
    if (-not (Set-SecureSetting -Serial $Device -Name 'accessibility_enabled' -Value '1')) { throw 'Could not turn on accessibility.' }
    # Android binds the service a moment after the setting.
    Start-Sleep -Seconds 3
}
# The A71's (or, in a dry run, the emulator's) device: the step functions below take it from here.
function Get-A71 { $Serial }

# Before sitting 1, without the owner (design D30): both builds, the models with the OCR files, test mode, the owner's
# names, and what the owner's step needs to know.
function Invoke-PrepareStep {
    $script:gradleUsed = $true
    foreach ($device in @($EmulatorSerial, (Get-A71)) | Select-Object -Unique) {
        & "$PSScriptRoot\install.ps1" -Serial $device
        if ($LASTEXITCODE -ne 0) { throw "scripts\install.ps1 failed on $(Get-DeviceName $device)." }
        # The fixture app adds a share target ("Messenger fixture") to the A71's share sheets, so the sitting uninstalls it
        # again from the A71 when it was not there before; kestrel-api37 keeps it.
        if ($device -ne $EmulatorSerial -and -not (Test-Installed $device 'dev.kestrel.testapp')) {
            Register-Restore "the fixture app uninstalled from $(Get-DeviceName $device)" "Run: & '$adb' -s $device uninstall dev.kestrel.testapp" $device {
                param($Device)
                Invoke-Device $Device 'pm uninstall dev.kestrel.testapp' | Out-Null
                if (Test-Installed $Device 'dev.kestrel.testapp') { throw 'the fixture app is still installed' }
            } | Out-Null
        }
        & "$PSScriptRoot\install.ps1" -Serial $device -TestApp
        if ($LASTEXITCODE -ne 0) { throw "scripts\install.ps1 -TestApp failed on $(Get-DeviceName $device)." }
        Invoke-Device $device 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
        Close-LiveSession $device
        & "$PSScriptRoot\push-models.ps1" -Serial $device
        if ($LASTEXITCODE -ne 0) { throw "scripts\push-models.ps1 failed on $(Get-DeviceName $device)." }
        Enable-TestMode $device
        Set-OwnerNames $device
        Add-Result prepare "$(Get-DeviceName $device): Kestrel and the fixture app installed, the models with the OCR files copied, test mode on, the owner's name set"
    }
    $a71 = Get-A71
    $accounts = Get-GoogleAccountCount @(Invoke-Device $a71 'dumpsys account')
    $missing = @(foreach ($package in 'com.whatsapp', 'com.waze', 'com.google.android.gm') { if (-not (Test-Installed $a71 $package)) { $package } })
    Set-AgentRecord $recordFile 'missingA71' $missing
    Add-Result prepare "the A71 has $accounts Google accounts; Telegram $(if (Test-Installed $a71 'org.telegram.messenger') { 'is' } else { 'is not' }) installed; missing from Play: $(if ($missing) { $missing -join ', ' } else { 'none' })"
    $play = (Test-Installed $EmulatorSerial 'com.android.vending') -and -not (Test-PackageListed @(Invoke-Device $EmulatorSerial 'pm list packages -d com.android.vending') 'com.android.vending')
    Set-AgentRecord $recordFile 'emulatorPlay' $play
    $chrome = Get-VersionName @(Invoke-Device $EmulatorSerial 'dumpsys package com.android.chrome')
    $webView = Get-VersionName @(Invoke-Device $EmulatorSerial 'dumpsys package com.google.android.webview')
    Set-AgentRecord $recordFile 'chromeVersion' $chrome
    Set-AgentRecord $recordFile 'webViewVersion' $webView
    Add-Result prepare "kestrel-api37: the Play Store $(if ($play) { 'is there' } else { 'is missing or disabled, so Waze and the e-mail task run on the A71 only' }); Chrome $chrome, Android System WebView $webView"
}

# A real sitting keeps the A71's touch rule and branch for sitting 2; a dry run sets them on kestrel-api37 only for its
# own run, so it registers, once, the restore of the rule and branch that the emulator had.
function Register-TouchSourceRestore([string]$Device) {
    if (-not $DryRun -or $script:touchSourceSaved) { return }
    $before = ConvertFrom-HookPairs (Invoke-DeviceHook $Device agent-touch-source)
    $script:touchSourceSaved = $true
    Register-Restore "the touch rule and branch of $(Get-DeviceName $Device)" "Run: scripts\hook.ps1 -Serial $Device agent-touch-source -Rule $($before['rule']) -Branch $($before['branch'])" @{ Device = $Device; Rule = $before['rule']; Branch = $before['branch'] } {
        param($Data)
        # connected-test.ps1 can have stopped Kestrel, and a stopped app gets no hook broadcast.
        Invoke-Device $Data.Device 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
        Start-Sleep -Seconds 2
        $now = ConvertFrom-HookPairs (Invoke-DeviceHook $Data.Device agent-touch-source @{ Rule = $Data.Rule; Branch = $Data.Branch })
        if ($now['rule'] -ne $Data.Rule -or $now['branch'] -ne $Data.Branch) { throw 'the touch source did not take the rule and branch' }
        Invoke-Device $Data.Device 'input keyevent KEYCODE_HOME' | Out-Null
    } | Out-Null
}

# The real finger touch of design D19 (step 7): a task whose scripted model only waits runs on :testapp's fixtures, and
# only a cancel that comes after the prompt counts. Branch A: the touch watcher saw the finger. Otherwise branch B, where
# a tap on a fixture button must cancel the task through OwnerSignal. The branch goes to agent-touch-source and the record.
function Invoke-FingerCheck([string]$Device) {
    Register-TouchSourceRestore $Device
    $source = ConvertFrom-HookPairs (Invoke-DeviceHook $Device agent-touch-source)
    $branch = $null
    $skipped = $false
    foreach ($try in 'A', 'B') {
        # Each try runs on its own branch, so that a cancel by OwnerSignal (branch B) never counts as the watcher's.
        Invoke-DeviceHook $Device agent-touch-source @{ Rule = $source['rule']; Branch = $try } | Out-Null
        Invoke-Device $Device 'am start -n dev.kestrel.testapp/.FixturesActivity' | Out-Null
        $prompt = if ($try -eq 'A') {
            'Item 5, about 2 minutes: press Enter when you are at the A71. A few seconds later this computer beeps; then touch the middle of the A71''s screen once. The bar shows "Cancelled" within a second.'
        } else {
            'The watcher did not see that touch. Press Enter; when this computer beeps, tap the button "Views" on the A71''s screen once. The bar shows "Cancelled" within a second.'
        }
        if ((Request-OwnerItem $prompt) -eq 'skip') {
            $skipped = $true
            break
        }
        # The task starts after his Enter, with a script of its own, so that only a touch after the prompt can end it.
        Install-AgentScript -Name 'finger.json' -Replies (Get-SpeechScript wait)
        $tag = New-CheckTag
        Invoke-DeviceHook $Device voice @{ Send = 'heard'; Text = 'Kestrel, wait on this screen.'; Named = $true; Tag = $tag } | Out-Null
        $state = Wait-AgentState -Tag $tag -Seconds 30 -Until { param($State) $State['phase'] -eq 'running' }
        if (-not $state -or $state['phase'] -ne 'running') { throw 'the fixture task did not start' }
        # The task runs a moment before its touch watcher's window shows, and a touch before that ends nothing (the first
        # dry run lost branch A so, right after the install); only the window's title is compared, nothing is printed.
        $watch = [Diagnostics.Stopwatch]::StartNew()
        while (-not @(Invoke-Device $Device 'dumpsys window windows' | Where-Object { $_ -match 'Kestrel touch watcher' }) -and $watch.Elapsed.TotalSeconds -lt 10) { Start-Sleep -Milliseconds 500 }
        Start-Sleep -Seconds 1
        if ($DryRun) {
            # The emulator's touch through Android's input system stands in for the finger.
            $size = "$(Invoke-Device $Device 'wm size')"
            if ($size -match '(\d+)x(\d+)') { Invoke-Device $Device "input tap $([int]$Matches[1] / 2) $([int]$Matches[2] / 2)" | Out-Null }
        } else {
            try { [console]::Beep(1320, 400) } catch { Write-Verbose 'This console cannot beep.' }
        }
        $result = Wait-AgentTask -Tag $tag -Seconds 15
        if ($result -and $result['state'] -eq 'done' -and $result['end'] -eq 'CANCELLED') {
            $branch = $try
            break
        }
        Stop-SpeechTask $tag
    }
    Invoke-DeviceHook $Device agent-script @{ Clear = $true } | Out-Null
    if (-not $branch) {
        Invoke-DeviceHook $Device agent-touch-source @{ Rule = $source['rule']; Branch = 'A' } | Out-Null
        return $(if ($skipped) { 'skipped' } else { 'none' })
    }
    Set-AgentRecord $recordFile 'touchBranch' $branch
    $branch
}

# Runs one item of the owner's step on its own: a failure is recorded and the next item comes, so that his later items,
# the finger check and "You may leave now" still come; only his q stops the step.
function Invoke-OwnerItem([string]$Name, [scriptblock]$Body) {
    try {
        & $Body
    } catch {
        if ($_.Exception.Message -eq 'The owner stopped the sitting.') { throw }
        Add-Result owner "${Name}: failed: $(Format-Failure $_)"
    }
}

# The owner's step at the start of sitting 1 (design D30 steps 2 to 7): the A71 unlocked and awake, the missing apps
# from Play while he watches, Telegram's sign-in, WhatsApp's link, the test Google account on kestrel-api37 with Play's
# auto-update off, Waze's first screens and Home on both devices, and the real finger touch.
function Invoke-OwnerStep {
    $a71 = Get-A71
    if (-not (Unlock-Device $a71)) { throw 'the A71 is locked, and the sitting could not unlock it' }
    $record = Read-AgentRecord $recordFile
    $missing = @($record.missingA71 | Where-Object { $_ })
    Invoke-OwnerItem 'Item 1' {
        $installed = @()
        if ($missing -and (Request-OwnerItem "Item 1, 0 to 6 minutes: the script now installs $($missing -join ', ') on the A71 from Play with your account; watch the A71, and press Enter to start (s skips)." 'skip') -eq 'go') {
            foreach ($package in $missing) {
                if (Install-FromPlay $a71 $package) { $installed += $package }
            }
            Add-Result owner "installed from Play on the A71: $(if ($installed) { $installed -join ', ' } else { 'none' })"
        }
        Set-AgentRecord $recordFile 'installedA71' $installed
    }
    Invoke-OwnerItem 'Item 2' {
        if ((Request-OwnerItem 'Item 2, about 4 minutes: on the A71, open Telegram and sign in with your number and the code that Telegram shows on the S23+ (never "Send SMS"), and your Two-Step Verification password if it asks. Press Enter when Telegram shows its chat list (s skips).' 'skip') -eq 'go') {
            Open-App $a71 'org.telegram.messenger'
            Start-Sleep -Seconds 5
            $front = (ConvertFrom-HookPairs (Invoke-DeviceHook $a71 screen-has @{ Package = 'org.telegram.messenger' }))['app'] -eq 'org.telegram.messenger'
            $login = Get-ScreenCount $a71 @{ Package = 'org.telegram.messenger'; Contains = 'phone number' }
            Add-Result owner "Telegram on the A71: $(if ($front -and $login -eq 0) { 'signed in' } else { 'not signed in' })"
        }
    }
    Invoke-OwnerItem 'Item 3' {
        if ((Request-OwnerItem 'Item 3, about 3 minutes: on the S23+, open WhatsApp > Linked devices > Link a device, and scan the QR code that WhatsApp shows on the A71 (open WhatsApp on the A71 first). Press Enter when the A71 shows WhatsApp''s chat list (s skips).' 'skip') -eq 'go') {
            Open-App $a71 'com.whatsapp'
            Start-Sleep -Seconds 5
            $front = (ConvertFrom-HookPairs (Invoke-DeviceHook $a71 screen-has @{ Package = 'com.whatsapp' }))['app'] -eq 'com.whatsapp'
            $login = Get-ScreenCount $a71 @{ Package = 'com.whatsapp'; Contains = 'link with phone number' }
            Add-Result owner "WhatsApp on the A71: $(if ($front -and $login -eq 0) { 'linked' } else { 'not linked' })"
        }
    }
    Invoke-OwnerItem 'Item 3b' {
        if ($record.emulatorPlay -and (Request-OwnerItem 'Item 3b, 2 to 5 minutes: in the emulator''s window on this computer, use the mouse: Settings > Passwords and accounts > Add account > Google, and sign in with the test Google account. Then open the Play Store, tap your profile picture, Settings > Network preferences > Auto-update apps, choose "Don''t auto-update apps" and tap Done. Press Enter when that is done (s skips).' 'skip') -eq 'go') {
            if ((Get-GoogleAccountCount @(Invoke-Device $EmulatorSerial 'dumpsys account')) -lt 1) {
                Add-Result owner 'kestrel-api37 has no Google account, so Waze and the e-mail task run on the A71 only'
            } else {
                # Recorded first, so that sitting 2 removes the account also when an install below fails.
                Set-AgentRecord $recordFile 'emulatorAccount' $true
                $installedEmulator = @(foreach ($package in 'com.waze', 'com.google.android.gm') {
                        if (-not (Test-Installed $EmulatorSerial $package) -and (Install-FromPlay $EmulatorSerial $package)) { $package }
                    })
                Set-AgentRecord $recordFile 'installedEmulator' $installedEmulator
                # Gmail's notifications stay off on the emulator, so that no mail of the test account shows in the shade.
                if (Test-Installed $EmulatorSerial 'com.google.android.gm') { Invoke-Device $EmulatorSerial 'pm revoke com.google.android.gm android.permission.POST_NOTIFICATIONS' | Out-Null }
                # The Play Store stays disabled until the end of sitting 2, so that it never updates Chrome or WebView.
                Invoke-Device $EmulatorSerial 'pm disable-user --user 0 com.android.vending' | Out-Null
                Set-AgentRecord $recordFile 'playDisabled' $true
                Add-Result owner "kestrel-api37: the test account is signed in; installed $(if ($installedEmulator) { $installedEmulator -join ', ' } else { 'nothing' }); Gmail's notifications off; the Play Store disabled until the end of sitting 2"
            }
        }
    }
    Invoke-OwnerItem 'Item 4' {
        if ((Request-OwnerItem "Item 4, about 4 minutes: open Waze on the A71, pass its first screens, allow the location, and set Home to $($config.homePlace); then do the same in the emulator's window with the mouse, if Waze is there. Press Enter when both show Waze's map (s skips)." 'skip') -eq 'go') {
            Add-Result owner "Waze's Home set by the owner on the A71$(if (Test-Installed $EmulatorSerial 'com.waze') { ' and kestrel-api37' })"
            foreach ($device in @($EmulatorSerial, $a71) | Select-Object -Unique) { Invoke-Device $device 'am force-stop com.waze' | Out-Null }
        }
    }
    Start-Listening $a71 'quiet'
    $branch = Invoke-FingerCheck $a71
    Add-Result owner "the real finger: $(switch ($branch) { 'A' { 'branch A, the touch watcher saw it' } 'B' { 'branch B, the watcher did not see it, and OwnerSignal cancelled the task' } 'skipped' { 'skipped' } default { 'neither the watcher nor OwnerSignal cancelled the task; branch A kept, a finding for the controller' } })"
    if (-not $DryRun) {
        try { [console]::Beep(880, 300) } catch { Write-Verbose 'This console cannot beep.' }
        Write-Host 'You may leave now. The rest of sitting 1 runs without you for about 1 3/4 hours; please do not touch the A71 or the emulator.'
    }
}

# Design D30 step 8: the runtime permissions, notification access, the Waze check of the A71's test location, and
# Firefox past its first screens.
function Invoke-SetupStep {
    $a71 = Get-A71
    foreach ($device in @($EmulatorSerial, $a71) | Select-Object -Unique) {
        Grant-AgentPermissions $device
        Enable-NotificationAccess $device
        Invoke-Device $device 'am start -a android.intent.action.VIEW -d https://en.wikipedia.org -p org.mozilla.firefox' | Out-Null
        Start-Sleep -Seconds 8
        $toolbar = Get-ScreenCount $device @{ Package = 'org.mozilla.firefox'; ViewId = 'toolbar' }
        Invoke-Device $device 'am force-stop org.mozilla.firefox' | Out-Null
        Add-Result setup "$(Get-DeviceName $device): runtime permissions granted, notification access on, Firefox $(if ($toolbar -gt 0) { 'shows its toolbar' } else { 'shows no toolbar, maybe its first screens; finish them by hand before sitting 2' })"
    }
    if ($DryRun -or -not (Test-Installed $a71 'com.waze')) {
        Set-AgentRecord $recordFile 'wazeA71' 'skipped'
        Add-Result setup "the A71's test location: $(if ($DryRun) { 'not checked in a dry run' } else { 'Waze is not installed, so the A71''s Waze runs are skipped' })"
        return
    }
    $restore = Start-TestLocation $a71
    try {
        Open-App $a71 'com.waze'
        Start-Sleep -Seconds 20
        $shown = Get-ScreenCount $a71 @{ Package = 'com.waze'; Contains = "$($config.testStreet)" }
    } finally {
        Invoke-Device $a71 'am force-stop com.waze' | Out-Null
        Complete-Restore $restore
    }
    Set-AgentRecord $recordFile 'wazeA71' $(if ($shown -gt 0) { 'ok' } else { 'skipped' })
    Add-Result setup "the A71's test location: $(if ($shown -gt 0) { 'Waze shows it' } else { 'Waze does not show it (it may read Play services'' own location), so the A71''s Waze runs are skipped; the controller asks the owner' })"
}

# Design D30 step 9: the A71's device tests that may run there (D27), which may delete Kestrel's data there (Q6); then
# what a run may have deleted is set again.
function Invoke-DeviceTestsStep {
    $script:gradleUsed = $true
    $a71 = Get-A71
    if (-not (Unlock-Device $a71)) { throw 'the A71 is locked, and the sitting could not unlock it' }
    Add-Result device-tests 'the device tests may delete Kestrel''s profile, memory and routines on the A71 (the owner agreed, Q6)'
    $classes = 'dev.kestrel.app.agent.eyes.OcrDeviceTest', 'dev.kestrel.app.screen.ScreenCaptureTest', 'dev.kestrel.app.screen.OwnActionsTest',
    'dev.kestrel.app.screen.SystemUiFilterTest', 'dev.kestrel.app.agent.AgentLocationTest', 'dev.kestrel.app.agent.NotificationListenerTest'
    $folder = Join-Path $PSScriptRoot '..\app\build\outputs\androidTest-results\connected'
    Clear-TestResults $folder
    # AgentLocationTest grants Kestrel the location and cannot take it back; this grant's restore revokes what Kestrel did
    # not have before the sitting.
    Grant-AgentPermissions $a71
    Register-TouchSourceRestore $a71
    Invoke-Device $a71 'logcat -c' | Out-Null
    & "$PSScriptRoot\connected-test.ps1" -Serial $a71 ':app:connectedDebugAndroidTest' "-Pandroid.testInstrumentationRunnerArguments.class=$($classes -join ',')" '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
    $failed = @(Get-UnpassedTests $folder)
    foreach ($class in $classes) {
        $name = ($class -split '\.')[-1]
        $verdict = if (@($failed | Where-Object { $_ -like "$name#*" })) { "failed: $(@($failed | Where-Object { $_ -like "$name#*" }) -join ', ')" } elseif (Test-ClassPassed $folder $name) { 'passed' } else { 'not run' }
        Add-Result device-tests "${name}: $verdict"
    }
    # The probe's lines choose the touch rule only when its test, which checks the echoes' kinds and delays, passed.
    $probeFailed = @($failed | Where-Object { $_ -like 'OwnActionsTest#probeRecordsWhatKestrelsActionsLookLikeToTheWatcher *' })
    $probeRan = (Test-ClassPassed $folder 'OwnActionsTest') -or @($failed | Where-Object { $_ -like 'OwnActionsTest#*' })
    $probePassed = $probeRan -and -not $probeFailed
    Clear-TestResults $folder
    $log = @(Invoke-Device $a71 'logcat -d -s KestrelOcr:I KestrelProbe:I KestrelSystemUi:I')
    [IO.File]::WriteAllLines((Join-Path $owner "agent-logcat-$(if ($DryRun) { 'dry-run-' })$stamp.txt"), [string[]]$log, [Text.UTF8Encoding]::new($false))
    foreach ($measure in Get-OcrMeasures $log) { Add-Result device-tests "OCR: $measure" }
    $probeLines = @($log | Where-Object { $_ -match 'KestrelProbe' })
    $rule = Get-TouchRuleFromProbe $probeLines $probePassed
    if ($rule) { Set-AgentRecord $recordFile 'touchRule' $rule }
    $ruleNote = if ($rule) { $rule } elseif (-not $probePassed) { 'none chosen, because the probe did not pass; the rule stays as it was, a finding for the controller' } else { 'no probe lines' }
    $record = Read-AgentRecord $recordFile
    $touchRule = if ($record.touchRule) { $record.touchRule } else { (ConvertFrom-HookPairs (Invoke-DeviceHook $a71 agent-touch-source))['rule'] }
    $branch = if ($record.touchBranch) { $record.touchBranch } else { 'A' }
    # connected-test.ps1 ends with 'am force-stop', and a stopped app gets no hook broadcast, so Kestrel starts again.
    Invoke-Device $a71 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
    Start-Sleep -Seconds 3
    Invoke-DeviceHook $a71 agent-touch-source @{ Rule = $touchRule; Branch = $branch } | Out-Null
    Enable-TestMode $a71
    Set-OwnerNames $a71
    # The clip microphone ended with Kestrel's process, so Kestrel listens again with the silent one (design D30).
    $listening[$a71] = $null
    Start-Listening $a71 'quiet'
    & "$PSScriptRoot\push-models.ps1" -Serial $a71
    if ($LASTEXITCODE -ne 0) { throw 'scripts\push-models.ps1 failed on the A71.' }
    $copilot = (ConvertFrom-HookPairs (Invoke-DeviceHook $a71 provider-standin))['copilot']
    Set-AgentRecord $recordFile 'copilotA71' ($copilot -ne '0')
    Add-Result device-tests "the probe's touch rule: $ruleNote; set again: rule $touchRule, branch $branch, test mode, the owner's name, the models; Kestrel's Copilot profile on the A71: $(if ($copilot -ne '0') { 'kept' } else { 'gone, so sitting 2''s owner step signs in again' })"
}

# Turns the hotspot switch of the page in front on or off with an input tap at its bounds (the script's own tap): the
# first switch whose label is one of the hotspot's names (One UI's "Mobile Hotspot", AOSP's "Wi-Fi hotspot" and "Use
# Wi-Fi hotspot"). Returns whether it tapped.
function Set-HotspotSwitch([string]$Device, [bool]$On) {
    $state = if ($On) { 'unchecked' } else { 'checked' }
    foreach ($label in 'Mobile Hotspot', 'Wi-Fi hotspot', 'Use Wi-Fi hotspot', 'Hotspot') {
        $found = ConvertFrom-HookPairs (Invoke-DeviceHook $Device screen-has @{ Label = $label; ClassName = 'Switch'; State = $state })
        $centre = Get-BoundsCentre $found['bounds']
        if ([int]$found['count'] -gt 0 -and $centre) {
            Invoke-Device $Device "input tap $centre" | Out-Null
            Start-Sleep -Seconds 10
            return $true
        }
    }
    $false
}

# Waits up to $Seconds for a validated Wi-Fi network. Returns whether there is one.
function Wait-Validated([string]$Device, [int]$Seconds = 60) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        if (Test-NetworkValidated @(Invoke-Device $Device 'dumpsys connectivity')) { return $true }
        Start-Sleep -Seconds 3
    } while ($watch.Elapsed.TotalSeconds -lt $Seconds)
    $false
}

# Turns the device's hotspot off and Wi-Fi on again. The settings page's switch needs an unlocked phone, but
# 'cmd wifi stop-softap' and 'svc wifi enable' work on a locked one, so they run also when the unlock failed
# (task-5c-9-review M4; plan 4D's review I1).
function Restore-Hotspot([string]$Device) {
    if (Unlock-Device $Device) {
        Invoke-Device $Device 'am start -a android.settings.TETHER_SETTINGS' | Out-Null
        Start-Sleep -Seconds 3
        Set-HotspotSwitch $Device $false | Out-Null
    }
    # A switch that the page did not show, or a hotspot that Wi-Fi sharing keeps validated, must not count as off.
    if (Test-HotspotOn @(Invoke-Device $Device 'dumpsys tethering')) {
        Invoke-Device $Device 'cmd wifi stop-softap' | Out-Null
        Start-Sleep -Seconds 3
    }
    if (Test-HotspotOn @(Invoke-Device $Device 'dumpsys tethering')) { throw 'the hotspot is still on' }
    Invoke-Device $Device 'svc wifi enable' | Out-Null
    if (-not (Wait-Validated $Device 60)) { throw 'Wi-Fi has no validated network again' }
}

# Registers the restore that turns the device's hotspot off and Wi-Fi on (Restore-Hotspot), and returns it.
function Register-HotspotRestore([string]$Device) {
    Register-Restore "the hotspot off and Wi-Fi on, on $(Get-DeviceName $Device)" 'Settings > Connections > Mobile Hotspot and Tethering: turn Mobile Hotspot off; then turn Wi-Fi on.' $Device {
        param($Device)
        Restore-Hotspot $Device
    }
}

# Turns on One UI's "Wi-Fi sharing" on the page of Mobile Hotspot (Q7 allows it), so that the hotspot can share the
# Wi-Fi network, and registers its restore. The tap on "Mobile Hotspot" may reach the hotspot's own switch, so the
# hotspot's restore is registered before it and runs at the end (task-5c-9-review M4). Returns whether it turned Wi-Fi
# sharing on.
function Enable-WifiSharing([string]$Device) {
    $hotspot = Register-HotspotRestore $Device
    try {
        Invoke-Device $Device 'am start -a android.settings.TETHER_SETTINGS' | Out-Null
        Start-Sleep -Seconds 3
        Invoke-ScreenTap $Device 'Mobile Hotspot' 5 | Out-Null
        $found = ConvertFrom-HookPairs (Invoke-DeviceHook $Device screen-has @{ Label = 'Wi-Fi sharing'; ClassName = 'Switch'; State = 'unchecked' })
        $centre = Get-BoundsCentre $found['bounds']
        if (-not ([int]$found['count'] -gt 0 -and $centre)) { return $false }
        Register-WifiSharingRestore $Device | Out-Null
        Invoke-Device $Device "input tap $centre" | Out-Null
        Start-Sleep -Seconds 2
        $true
    } finally {
        Complete-Restore $hotspot
    }
}

# The restore of Wi-Fi sharing, which only the settings page can turn off, so it needs an unlocked phone.
function Register-WifiSharingRestore([string]$Device) {
    Register-Restore "Wi-Fi sharing off on $(Get-DeviceName $Device)" 'Settings > Connections > Mobile Hotspot and Tethering > Mobile Hotspot: turn Wi-Fi sharing off.' $Device {
        param($Device)
        if (-not (Unlock-Device $Device)) { throw 'the A71 is locked, and only its settings page turns Wi-Fi sharing off' }
        Invoke-Device $Device 'am start -a android.settings.TETHER_SETTINGS' | Out-Null
        Start-Sleep -Seconds 3
        Invoke-ScreenTap $Device 'Mobile Hotspot' 5 | Out-Null
        $now = ConvertFrom-HookPairs (Invoke-DeviceHook $Device screen-has @{ Label = 'Wi-Fi sharing'; ClassName = 'Switch'; State = 'checked' })
        $at = Get-BoundsCentre $now['bounds']
        if ([int]$now['count'] -gt 0 -and $at) {
            Invoke-Device $Device "input tap $at" | Out-Null
            Start-Sleep -Seconds 2
        }
        $off = ConvertFrom-HookPairs (Invoke-DeviceHook $Device screen-has @{ Label = 'Wi-Fi sharing'; ClassName = 'Switch'; State = 'unchecked' })
        if ([int]$off['count'] -eq 0) { throw 'Wi-Fi sharing may still be on' }
    }
}
# Design D30 step 10: the hotspot probe on the A71 (r60; review C I3). With Wi-Fi validated, the script turns the
# hotspot on through its settings page, reads the tethering state and whether the network stays validated, and turns it
# off again; when Wi-Fi dropped and the page offers "Wi-Fi sharing", which Q7 allows, it turns that on and tries once
# more. The result goes to the record: ok, or limit (the hotspot runs on the A71 are then skipped).
function Invoke-HotspotStep {
    $a71 = Get-A71
    if (-not (Wait-Validated $a71 30)) {
        Set-AgentRecord $recordFile 'hotspot' 'limit'
        Add-Result hotspot 'skipped: Wi-Fi has no validated network, so the hotspot runs on the A71 are skipped'
        return
    }
    $result = 'limit'
    $sharing = $false
    foreach ($try in 1, 2) {
        $restore = Register-HotspotRestore $a71
        Invoke-Device $a71 'am start -a android.settings.TETHER_SETTINGS' | Out-Null
        Start-Sleep -Seconds 3
        if (-not (Set-HotspotSwitch $a71 $true)) {
            Complete-Restore $restore
            Add-Result hotspot 'the hotspot page shows no switch that the script knows'
            break
        }
        $on = Test-HotspotOn @(Invoke-Device $a71 'dumpsys tethering')
        $validated = Test-NetworkValidated @(Invoke-Device $a71 'dumpsys connectivity')
        Add-Result hotspot "try ${try}: the hotspot is $(if ($on) { 'on' } else { 'off' }), the network $(if ($validated) { 'stays validated' } else { 'is not validated' })"
        Complete-Restore $restore
        if ($on -and $validated) {
            $result = 'ok'
            break
        }
        if ($try -eq 2) { break }
        if (-not (Enable-WifiSharing $a71)) { break }
        $sharing = $true
        Add-Result hotspot 'Wi-Fi sharing turned on for the second try (Q7)'
    }
    Set-AgentRecord $recordFile 'hotspot' $result
    Set-AgentRecord $recordFile 'wifiSharing' $sharing
    Add-Result hotspot "the A71's hotspot: $(if ($result -eq 'ok') { 'testable' } else { 'a limit of the device; its runs are skipped' })"
}
# Telegram's chat list, its Saved Messages chat (found by search) and its share picker, through agent-tree. A tap that
# misses writes its own line, and the record's telegramSelfChat says whether Saved Messages was recorded, from which the
# controller pins Telegram's self-chat rule (task-5c-9-review M1). Returns the lines for the results.
function Invoke-TelegramRecording([string]$Device) {
    $notes = @()
    Open-App $Device 'org.telegram.messenger'
    Start-Sleep -Seconds 4
    $notes += Save-AgentTree $Device 'telegram-chats'
    $saved = 'not shown'
    if (Invoke-ScreenTap $Device 'Search') {
        Invoke-Device $Device 'input text Saved\ Messages' | Out-Null
        Start-Sleep -Seconds 3
        if (Invoke-ScreenTap $Device 'Saved Messages') {
            $notes += Save-AgentTree $Device 'telegram-saved'
            $notes += "Telegram's Saved Messages: $(Invoke-DeviceHook $Device selfchat-check)"
            $saved = 'recorded'
        } else {
            $notes += 'Telegram shows no element "Saved Messages", so it is not recorded'
        }
    } else {
        $notes += 'Telegram''s search did not open, so Saved Messages is not recorded'
    }
    Set-AgentRecord $recordFile 'telegramSelfChat' $saved
    Invoke-Device $Device 'am force-stop org.telegram.messenger' | Out-Null
    # Telegram's share picker, opened by a text share from the shell and closed with back, without sending.
    Invoke-Device $Device "am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT 'Kestrel test' -p org.telegram.messenger" | Out-Null
    Start-Sleep -Seconds 4
    $notes += Save-AgentTree $Device 'telegram-share'
    Invoke-Device $Device 'input keyevent KEYCODE_BACK' | Out-Null
    Invoke-Device $Device 'am force-stop org.telegram.messenger' | Out-Null
    $notes
}

# The number of drafts that Gmail's Drafts folder shows (hook H3, a count only; design D30 step 11): Gmail opens, then its
# navigation drawer and Drafts, and the elements that hold the word "Draft", as each draft's row does, are counted (H3
# compares whole words, so the title "Drafts" and "Nothing in Drafts" do not count). $null when the folder did not show,
# or when it shows so many that a new draft could push another one off the screen.
function Get-GmailDraftCount([string]$Device) {
    $gmail = 'com.google.android.gm'
    try {
        Invoke-Device $Device "am force-stop $gmail" | Out-Null
        Open-App $Device $gmail
        Start-Sleep -Seconds 5
        if (-not (Invoke-ScreenTap $Device 'Open navigation drawer' 10)) { return $null }
        if (-not (Invoke-ScreenTap $Device 'Drafts' 5)) { return $null }
        Start-Sleep -Seconds 3
        $found = ConvertFrom-HookPairs (Invoke-DeviceHook $Device screen-has @{ Package = $gmail; Contains = 'Draft' })
        if ($found['app'] -ne $gmail -or [int]$found['count'] -ge 6) { return $null }
        [int]$found['count']
    } finally {
        Invoke-Device $Device "am force-stop $gmail" | Out-Null
    }
}

# Gmail's compose screen with a mailto: to the owner's address (design D30 steps 11 and 16): $Inside runs with the device
# while it shows, and the screen's own "More options" and "Discard" close it (and "Discard" once more when Gmail asks).
# Drafts is counted before and after; on the A71 (-Owner) the screen opens only when Drafts could be counted first
# (task-5c-9-review M3). A failed mailto: command throws a fixed text, because its own text holds the address (M5).
# Returns Opened, Discarded (both taps worked, and Drafts was counted before and after and did not grow), Inside (what
# $Inside returned) and Note, a line for the results with counts only.
function Invoke-GmailCompose([string]$Device, [scriptblock]$Inside, [switch]$Owner) {
    $gmail = 'com.google.android.gm'
    $name = Get-DeviceName $Device
    $before = Get-GmailDraftCount $Device
    if ($Owner -and $null -eq $before) {
        return [pscustomobject]@{ Opened = $false; Discarded = $false; Inside = $null; Note = "Gmail's compose screen on ${name}: not opened, because its Drafts could not be counted" }
    }
    $tapped = $false
    $inner = $null
    try {
        try {
            Invoke-Device $Device "am start -a android.intent.action.SENDTO -d mailto:$($config.gmailAddress) -p $gmail" | Out-Null
        } catch {
            throw "Gmail's compose screen did not open on $name."
        }
        Start-Sleep -Seconds 5
        $inner = & $Inside $Device
        $tapped = (Invoke-ScreenTap $Device 'More options' 5) -and (Invoke-ScreenTap $Device 'Discard' 5)
        if ($tapped -and (Get-ScreenCount $Device @{ Package = $gmail; Label = 'Discard' }) -gt 0) { Invoke-ScreenTap $Device 'Discard' 5 | Out-Null }
    } finally {
        Invoke-Device $Device "am force-stop $gmail" | Out-Null
    }
    $after = Get-GmailDraftCount $Device
    $discarded = $tapped -and $null -ne $before -and $null -ne $after -and $after -le $before
    $note = if ($discarded) {
        "Gmail's compose screen on ${name}: discarded; Drafts shows $after as before"
    } else {
        $why = if (-not $tapped) { 'the taps on "More options" and "Discard" missed' } elseif ($null -eq $before -or $null -eq $after) { 'Drafts could not be counted' } else { "Drafts grew from $before to $after" }
        "Gmail's draft on $name may stay in Drafts ($why); delete it by hand"
    }
    [pscustomobject]@{ Opened = $true; Discarded = $discarded; Inside = $inner; Note = $note }
}

# Design D30 step 11: the structure of the real self-chats, the chat lists and the pickers (never an ordinary chat), and
# of SystemUI's shade and a heads-up notification, recorded through agent-tree into corpus\owner\trees.
function Invoke-RecordingsStep {
    $a71 = Get-A71
    if (-not (Unlock-Device $a71)) { throw 'the A71 is locked, and the sitting could not unlock it' }
    $notes = @()
    if (Test-Installed $a71 'org.telegram.messenger') { $notes += Invoke-TelegramRecording $a71 }
    if (Test-Installed $a71 'com.whatsapp') {
        Open-App $a71 'com.whatsapp'
        Start-Sleep -Seconds 4
        $notes += Save-AgentTree $a71 'whatsapp-chats'
        if (Invoke-ScreenTap $a71 "$($config.whatsappOwnName) (You)") {
            $notes += Save-AgentTree $a71 'whatsapp-self'
            $notes += "WhatsApp's own chat: $(Invoke-DeviceHook $a71 selfchat-check)"
            Set-AgentRecord $recordFile 'whatsappSelfChat' 'recorded'
        } else {
            Set-AgentRecord $recordFile 'whatsappSelfChat' 'not shown'
            $notes += 'WhatsApp shows no row "<owner> (You)", so WhatsApp is not testable on this device'
        }
        Invoke-Device $a71 'am force-stop com.whatsapp' | Out-Null
    }
    # The emulator, with the test account, comes first; the A71's compose screen opens only when the discard worked there.
    $emulatorDiscarded = $false
    foreach ($device in @($EmulatorSerial, $a71) | Select-Object -Unique) {
        if (Test-Installed $device 'com.google.android.gm') {
            if ($device -ne $EmulatorSerial -and -not $emulatorDiscarded) {
                $notes += "Gmail's compose screen on $(Get-DeviceName $device): skipped, because its discard did not work on kestrel-api37"
                Set-AgentRecord $recordFile 'gmailDiscardA71' 'not opened'
            } else {
                $compose = Invoke-GmailCompose -Device $device -Owner:($device -ne $EmulatorSerial) -Inside { param($Device) Save-AgentTree $Device 'gmail-compose' }
                if ($compose.Inside) { $notes += $compose.Inside }
                $notes += $compose.Note
                if ($device -eq $EmulatorSerial) {
                    $emulatorDiscarded = $compose.Discarded
                } else {
                    Set-AgentRecord $recordFile 'gmailDiscardA71' $(if ($compose.Discarded) { 'ok' } elseif ($compose.Opened) { 'left' } else { 'not opened' })
                }
            }
        }
        Invoke-Device $device 'cmd statusbar expand-notifications' | Out-Null
        Start-Sleep -Seconds 2
        $notes += Save-AgentTree $device 'systemui-shade'
        Invoke-Device $device 'cmd statusbar collapse' | Out-Null
        # The fixture app posts nothing without the notification permission, which otherwise only a device test grants; a
        # grant that the sitting made is revoked again at its end.
        $notify = 'android.permission.POST_NOTIFICATIONS'
        if (-not @(Get-GrantedPermissions -Dump (Invoke-Device $device 'dumpsys package dev.kestrel.testapp') -Permissions $notify)) {
            Register-Restore "the fixture app's notification permission on $(Get-DeviceName $device)" "Run: & '$adb' -s $device shell pm revoke dev.kestrel.testapp $notify" $device {
                param($Device)
                # An uninstalled fixture app has no permission left to revoke.
                if (Test-Installed $Device 'dev.kestrel.testapp') { Invoke-Device $Device 'pm revoke dev.kestrel.testapp android.permission.POST_NOTIFICATIONS' | Out-Null }
            } | Out-Null
        }
        Invoke-Device $device "pm grant dev.kestrel.testapp $notify" | Out-Null
        Invoke-Device $device 'am start -n dev.kestrel.testapp/.NotificationActivity --ez headsUp true' | Out-Null
        Start-Sleep -Seconds 1
        $notes += Save-AgentTree $device 'systemui-heads-up'
        Invoke-Device $device 'am start -n dev.kestrel.testapp/.NotificationActivity --ez cancel true' | Out-Null
        Invoke-Device $device 'input keyevent KEYCODE_HOME' | Out-Null
    }
    foreach ($note in $notes) { Add-Result recordings $note }
}

# The gain at which the phone hears this computer's clips at the owner's level (hook H7), as plan 4D's sessions measure
# it, with Kestrel paused, so that the calibration line starts nothing.
function Measure-Gain([string]$Device) {
    $row = @(Read-Corpus -Path (Join-Path $PSScriptRoot '..\corpus\commands.txt') | Where-Object { $_.Group -eq 'conversation' -and $_.Text -notmatch '(?i)kestrel|stop|cancel' }) | Select-Object -First 1
    $clip = Join-Path $generated "kokoro\$($row.Id).wav"
    Invoke-DeviceHook $Device voice @{ Send = 'pause' } | Out-Null
    Wait-Mode $Device paused 10 | Out-Null
    try {
        Set-SpeakerLevel -TargetDb $config.SpeakerTargetDb -Measure {
            param($Gain)
            Invoke-DeviceHook $Device voice-route | Out-Null
            Invoke-Clip -Path $clip -Gain $Gain | Out-Null
            $level = (ConvertFrom-HookPairs (Invoke-DeviceHook $Device voice-route))['frameRms']
            if ($level -match '^-?\d+(\.\d+)?$') { [double]::Parse($level, [Globalization.CultureInfo]::InvariantCulture) } else { $null }
        }
    } finally {
        Invoke-DeviceHook $Device voice @{ Send = 'resume' } | Out-Null
        Wait-Mode $Device listening 10 | Out-Null
    }
}

# The speech checks of one device (scripts\lib\AgentChecks.ps1) in both voices, with Kestrel on its own microphone and
# :testapp in front; a dry run plays each clip's text into Kestrel's stand-in microphone instead. The fact "gate code"
# is removed again afterwards, and the scripted model too.
function Invoke-SpeechOn([string]$Serial) {
    $texts = @{}
    foreach ($row in @(Read-Corpus -Path (Join-Path $PSScriptRoot '..\corpus\commands.txt')) + @(Read-Corpus -Path (Join-Path $PSScriptRoot '..\corpus\checks.txt'))) { $texts[$row.Id] = $row.Text }
    Start-Listening $Serial $(if ($DryRun) { 'quiet' } else { 'own' })
    $gain = 1.0
    if (-not $DryRun) {
        $level = Measure-Gain $Serial
        Add-Result speech "$(Get-DeviceName $Serial): the clips reach the phone at $(if ($null -eq $level.LevelDb) { 'nothing' } else { "$($level.LevelDb) dBFS" }) (target $($config.SpeakerTargetDb)) with a gain of $([math]::Round($level.Gain, 2))"
        $gain = $level.Gain
        if (-not $level.InBand -and ($null -eq $level.LevelDb -or $level.LevelDb -lt $config.SpeakerTargetDb - 3)) {
            Add-Result speech "$(Get-DeviceName $Serial): skipped: the phone does not hear this computer loudly enough; turn up its volume and put its speakers next to the phone"
            return
        }
    }
    $play = if ($DryRun) { { param($Clip) Invoke-Hook voice-clip @{ Text = $Clip.Text } | Out-Null } } else { { param($Clip) Invoke-Clip -Path $Clip.Path -Gain $gain | Out-Null } }
    $passed = 0
    $run = 0
    try {
        foreach ($voice in 'kokoro', 'piper') {
            foreach ($check in Get-AgentSpeechChecks) {
                $path = Join-Path $generated "$voice\$($check.Clip).wav"
                if (-not $DryRun -and -not (Test-Path -LiteralPath $path)) {
                    Add-Result speech "$($check.Name) [$voice]: skipped: no clip $($check.Clip); make the clips with scripts\make-corpus.ps1, also with -List checks"
                    continue
                }
                Invoke-Device $Serial 'am start -n dev.kestrel.testapp/.FixturesActivity' | Out-Null
                $run++
                try {
                    $result = Invoke-AgentSpeechCheck -Check $check -Clip ([pscustomobject]@{ Voice = $voice; Text = $texts[$check.Clip]; Path = $path }) -Play $play
                    if ($result.Passed) { $passed++ }
                    Add-Result speech "$(Get-DeviceName $Serial): $($result.Line)"
                } catch {
                    Add-Result speech "$(Get-DeviceName $Serial): $($check.Name) [$voice]: failed: $(Format-Failure $_)"
                }
            }
        }
    } finally {
        Invoke-Hook agent-script @{ Clear = $true } | Out-Null
        $keys = (ConvertFrom-HookPairs (Invoke-Hook agent-memory @{ Remove = $true; Key = 'gate code' }))['keys']
        Add-Result speech "$(Get-DeviceName $Serial): $passed of $run speech checks passed; the fact 'gate code' removed, $keys keys left in memory"
    }
}

# Design D30 step 12: the speech checks (D28) on the A71 with the emulator's microphone off, then on kestrel-api37 with
# its host microphone on for that part only. They inform and do not gate (D27).
function Invoke-SpeechStep {
    $a71 = Get-A71
    if (-not $DryRun) {
        if (-not (Unlock-Device $a71)) { throw 'the A71 is locked, and the sitting could not unlock it' }
        Set-EmulatorHostMicrophone -Serial $EmulatorSerial -On $false
        Invoke-SpeechOn $a71
        Start-Listening $a71 'quiet'
        $restore = Register-Restore 'the emulator''s host microphone off' "Run: & '$adb' -s $EmulatorSerial emu avd hostmicoff" $EmulatorSerial { param($Device) Set-EmulatorHostMicrophone -Serial $Device -On $false }
        Set-EmulatorHostMicrophone -Serial $EmulatorSerial -On $true
        try { Invoke-SpeechOn $EmulatorSerial } finally { Complete-Restore $restore }
        Start-Listening $EmulatorSerial 'quiet'
    } else {
        Invoke-SpeechOn $EmulatorSerial
    }
}

# Design D30 step 13: the restores run at the sitting's end; Play's disabled state on kestrel-api37 stays until the end
# of sitting 2 (review C3-M4), and the fixture app leaves the A71 when the sitting installed it.
function Invoke-EndStep {
    $record = Read-AgentRecord $recordFile
    Add-Result end "the record for sitting 2: touch branch $(if ($record.touchBranch) { $record.touchBranch } else { 'A' }), rule $(if ($record.touchRule) { $record.touchRule } else { 'the default' }), hotspot $(if ($record.hotspot) { $record.hotspot } else { 'not probed' }), Waze on the A71 $(if ($record.wazeA71) { $record.wazeA71 } else { 'not checked' }), WhatsApp's own chat $(if ($record.whatsappSelfChat) { $record.whatsappSelfChat } else { 'not recorded' }); the Play Store on kestrel-api37 stays disabled until the end of sitting 2"
}

# Sitting 2's steps (design D30 steps 14 to 23).

# Runs scripts\task-check.ps1 with the arguments, its output on the console.
function Invoke-TaskCheck([hashtable]$Arguments) { & "$PSScriptRoot\task-check.ps1" @Arguments | Out-Host }

# Runs scripts\task-check.ps1 for a task on a device: -Live in a real sitting, or the task's stand-in with its scripted
# model in a dry run (one run), so that no request reaches a provider. The runs' lines go into this sitting's results
# file, from which they are read back; their AI Credits are added, and the sitting stops at its ceiling. Returns the
# runs' lines as tables.
function Invoke-TaskRuns([string]$StepName, [string]$Device, [string]$Task, [int]$Runs = 3, [hashtable]$With = @{}) {
    $arguments = @{ Serial = $Device; Task = $Task; Runs = $Runs; Results = $resultsFile } + $With
    if ($DryRun) {
        $standIn = Get-StandInTask $Task
        if (-not $standIn) {
            Add-Result $StepName "$Task on $(Get-DeviceName $Device): skipped in a dry run, because it has no stand-in"
            return @()
        }
        $arguments.Task = $standIn.Task
        $arguments.Script = Join-Path $PSScriptRoot $standIn.Script
        $arguments.Runs = 1
        $arguments.Remove('Mail')
    } else {
        $arguments.Live = $true
    }
    Add-Result $StepName "$($arguments.Task) on $(Get-DeviceName $Device), $($arguments.Runs) runs"
    $before = @(Get-Content -LiteralPath $resultsFile).Count
    # A task whose setup or check fails is recorded, and the step goes on with its next task (design D27). Its error can
    # hold the owner's address, as read-email's mailto: command does, so only its class is written (task-5c-10-review M1).
    try {
        Invoke-TaskCheck $arguments
    } catch {
        Add-Result $StepName "$($arguments.Task) on $(Get-DeviceName $Device): failed: task-check.ps1 stopped with $($_.Exception.GetType().Name) before its runs ended"
    }
    # PowerShell's names ignore case, so this list may not be called $runs, which is the parameter -Runs.
    $lines = @(Get-Content -LiteralPath $resultsFile | Select-Object -Skip $before | ForEach-Object { ConvertFrom-TaskLine $_ } | Where-Object { $_ })
    $script:credits += Get-CreditTotal $lines
    Set-AgentRecord $recordFile 'sitting2Credits' $script:credits
    Add-Result $StepName "$($arguments.Task) on $(Get-DeviceName $Device): $(@($lines | Where-Object { $_['result'] -eq 'pass' }).Count) of $($lines.Count) runs passed; the sitting's AI Credits so far: $(Format-Number $script:credits 2)"
    if ($script:credits -ge $creditCeiling) { throw $ceilingMessage }
    $lines
}

# A sitting 2 can take more than one call: a call without -Step starts it, and a call with -Step goes on after a stop
# (task-5c-10-review M3; final-5c-review-b M3). A new sitting 2 counts its AI Credits from 0 (Q5) and its results from
# this call's file; a call with -Step adds its file to the record's list and counts on from the record's credits.
function Add-SittingResults {
    $file = Split-Path -Leaf $resultsFile
    if (-not $Step) {
        $script:credits = 0.0
        Set-AgentRecord $recordFile 'sitting2Credits' $script:credits
        Set-AgentRecord $recordFile 'sitting2Results' @($file)
        return
    }
    $files = @(@((Read-AgentRecord $recordFile).sitting2Results | Where-Object { $_ }) + $file | Select-Object -Unique)
    Set-AgentRecord $recordFile 'sitting2Results' $files
}

# The results files of this sitting 2 that exist: those of its earlier calls, which the record lists, and this call's.
function Get-SittingResultFiles {
    $names = @((Read-AgentRecord $recordFile).sitting2Results | Where-Object { $_ })
    @(@($names | ForEach-Object { Join-Path $owner $_ }) + $resultsFile | Select-Object -Unique | Where-Object { Test-Path -LiteralPath $_ })
}

# The runs' lines of this sitting 2 so far, read from the results files of all its calls, each with the device of the line
# '<task> on <device>, <n> runs' that Invoke-TaskRuns writes before them.
function Get-SittingRuns {
    @(foreach ($file in Get-SittingResultFiles) {
            $device = 'none'
            foreach ($line in Get-Content -LiteralPath $file) {
                if ("$line" -match "`t\S+ on (the A71|kestrel-api37), \d+ runs$") {
                    $device = $Matches[1]
                    continue
                }
                $run = ConvertFrom-TaskLine $line
                if ($run) {
                    $run['device'] = $device
                    $run
                }
            }
        })
}

# Whether the default profile on kestrel-api37 lists the comparison's model in the model list that the owner's
# Copilot account sent at the sign-in (hook agent-live -Model).
function Test-CompareModel {
    (ConvertFrom-HookPairs (Invoke-DeviceHook $EmulatorSerial agent-live @{ Model = $CompareModel }))['model'] -eq 'listed'
}

# Install-AgentScript (scripts\lib\AgentChecks.ps1), which installs on the caller's $Serial, on the device $Serial.
function Install-AgentScriptOn([string]$Serial, [string]$Name, [object[]]$Replies) {
    Write-Verbose "The scripted model goes to $Serial."
    Install-AgentScript -Name $Name -Replies $Replies
}

# Waits for the end of a live task that the step sent with its tag, at most $Seconds. A FREE question while $App is in
# front is the screenshot question of look_closer, which asks for the app in front (design D20): it gets $Answer. A FREE
# question in front of another app gets "no", so that no screenshot of it is sent and no approval for it is stored
# (final-5c-review-b M2), and any other question gets "cancel". Each question is answered once (task-5c-10-review M2):
# an answer whose question had closed is no answer, and the next answer waits until the task asks again. Returns the
# task's last answer, how often the screenshot question came (Asked) and how often another FREE question came (Other).
# A task that still runs at the end, also after an error or Ctrl+C, is stopped, so that it spends no more credits.
function Wait-LiveTask([string]$Serial, [string]$Tag, [string]$Answer, [string]$App, [int]$Seconds = 600) {
    $asked = 0
    $other = 0
    $answered = $false
    $ended = $false
    $watch = [Diagnostics.Stopwatch]::StartNew()
    try {
        while ($watch.Elapsed.TotalSeconds -lt $Seconds) {
            $result = try { ConvertFrom-AgentTaskResult (Invoke-DeviceHook $Serial agent-task @{ Result = $true; Tag = $Tag }) } catch { $null }
            if ($result -and $result['state'] -eq 'done') {
                $ended = $true
                return [pscustomobject]@{ Result = $result; Asked = $asked; Other = $other; Stopped = $null }
            }
            $state = try { ConvertFrom-HookPairs (Invoke-DeviceHook $Serial agent-state @{ Tag = $Tag }) } catch { $null }
            $asking = if ($state) { "$($state['asking'])" } else { '' }
            if ($asking -in '', 'none') {
                $answered = $false
            } elseif (-not $answered) {
                $reply = 'cancel'
                $screenshot = $false
                if ($asking -eq 'free') {
                    $front = try { (ConvertFrom-HookPairs (Invoke-DeviceHook $Serial screen-has @{ Package = $App }))['app'] } catch { $null }
                    $screenshot = $front -eq $App
                    $reply = if ($screenshot) { $Answer } else { 'no' }
                }
                try {
                    Invoke-DeviceHook $Serial agent-answer @{ Tag = $Tag; Answer = $reply } | Out-Null
                    $answered = $true
                    if ($screenshot) { $asked++ } elseif ($asking -eq 'free') { $other++ }
                    Start-Sleep -Seconds 3
                } catch {
                    Write-Verbose 'The question had ended or changed.'
                }
            }
            Start-Sleep -Seconds 1
        }
        Stop-SpeechTask $Tag
        $ended = $true
        # A task that the step stopped spent AI Credits too, which count towards the sitting's ceiling (Q5).
        $stopped = $null
        for ($try = 0; $try -lt 10 -and -not $stopped; $try++) {
            Start-Sleep -Seconds 1
            $last = try { ConvertFrom-AgentTaskResult (Invoke-DeviceHook $Serial agent-task @{ Result = $true; Tag = $Tag }) } catch { $null }
            if ($last -and $last['state'] -eq 'done') { $stopped = $last }
        }
        [pscustomobject]@{ Result = $null; Asked = $asked; Other = $other; Stopped = $stopped }
    } finally {
        if (-not $ended) {
            try { Stop-SpeechTask $Tag } catch { Write-Warning "Could not stop the live task on $(Get-DeviceName $Serial). Say ""Kestrel, stop"" to it, or turn Kestrel off." }
        }
    }
}

# Revokes the screenshot approvals of the OCR fixture's app (design D20, D26), which look_closer's runs may have stored
# with "always for this app", through the hook agent-approvals, so that the owner's other approvals stay. Returns whether
# no more approvals are left than the $Before that the device had before the step.
function Revoke-ScreenshotApprovals([string]$Device, [int]$Before) {
    Invoke-Device $Device 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
    Start-Sleep -Seconds 2
    $answer = ConvertFrom-HookPairs (Invoke-DeviceHook $Device agent-approvals @{ Revoke = 'dev.kestrel.testapp' })
    Invoke-Device $Device 'input keyevent KEYCODE_HOME' | Out-Null
    [int]$answer['approvals'] -le $Before
}

# The restores of sitting 2's end on kestrel-api37 (design D30 step 23): the owner's Copilot profile deleted through the
# hook provider-delete (review C I6), the Play Store enabled again, and the test Google account and the apps that sitting 1
# installed there removed. A call that runs the step end, as a call without -Step does, registers them at its start, so
# that every way in which it ends runs them; a call with -Step that does not run end keeps them for the next call, so that
# a resume needs neither a new sign-in nor a new count of AI Credits (final-5c-review-b M3). The account's restore is
# registered after the Play Store's, so that it runs before it, and after the accessibility settings', which screen-tap
# needs.
function Register-SittingEndRestores {
    if ($Step -and 'end' -notin $Step) {
        Add-Result session 'the owner''s Copilot profile, the test account and its apps, and the disabled Play Store stay on kestrel-api37 until a call that runs the step end'
        return
    }
    Register-Restore 'the owner''s Copilot profile on kestrel-api37' 'On kestrel-api37, the emulator''s window, use the mouse: Kestrel''s settings > Providers and profiles > the Copilot profile > Delete profile > Delete.' $EmulatorSerial {
        param($Device)
        Invoke-Device $Device 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
        Start-Sleep -Seconds 3
        $answer = ConvertFrom-HookPairs (Invoke-DeviceHook $Device provider-delete)
        if ($answer['copilot'] -ne '0') { throw 'a Copilot profile is left' }
        Add-Result end 'the owner''s Copilot profile on kestrel-api37 is deleted'
    } | Out-Null
    $record = Read-AgentRecord $recordFile
    if ($record.playDisabled) {
        Register-Restore 'the Play Store on kestrel-api37' "Run: & '$adb' -s $EmulatorSerial shell pm enable com.android.vending" $EmulatorSerial {
            param($Device)
            Invoke-Device $Device 'pm enable com.android.vending' | Out-Null
            Set-AgentRecord $recordFile 'playDisabled' $false
        } | Out-Null
    }
    if (-not $DryRun -and ($record.emulatorAccount -or @($record.installedEmulator | Where-Object { $_ }).Count -gt 0)) {
        Register-Restore 'the test Google account and the apps of sitting 1 on kestrel-api37' 'On kestrel-api37, the emulator''s window: Settings > Passwords and accounts > the test account > Remove account; then uninstall Waze and Gmail.' $record {
            param($Record)
            $address = if ($config.PSObject.Properties['emulatorGmailAddress']) { "$($config.emulatorGmailAddress)" } else { '' }
            if ($Record.emulatorAccount) {
                Invoke-Device $EmulatorSerial 'am start -a android.settings.SYNC_SETTINGS' | Out-Null
                Start-Sleep -Seconds 3
                if ($address -and (Invoke-ScreenTap $EmulatorSerial $address) -and (Invoke-ScreenTap $EmulatorSerial 'Remove account') -and (Invoke-ScreenTap $EmulatorSerial 'Remove account')) { Start-Sleep -Seconds 5 }
            }
            foreach ($package in @($Record.installedEmulator | Where-Object { $_ })) {
                if (Test-Installed $EmulatorSerial $package) { Invoke-Device $EmulatorSerial "pm uninstall $package" | Out-Null }
            }
            Set-AgentRecord $recordFile 'installedEmulator' @()
            $left = Get-GoogleAccountCount @(Invoke-Device $EmulatorSerial 'dumpsys account')
            Add-Result end "kestrel-api37 has $left Google accounts; the apps of sitting 1 are uninstalled"
            if ($left -gt 0) { throw "$left Google accounts are left" }
            # The emulator's Waze and e-mail runs need the account, so a later call no longer runs them.
            Set-AgentRecord $recordFile 'emulatorAccount' $false
        } | Out-Null
    }
}

# Design D30 step 14: the owner signs Kestrel in to Copilot on kestrel-api37, and on the A71 when sitting 1's device
# tests deleted its profile there. The restore that deletes the emulator's Copilot profile is registered at the start of
# the call (Register-SittingEndRestores).
function Invoke-OwnerStep2 {
    $a71 = Get-A71
    $items = @(
        [pscustomobject]@{ Device = $EmulatorSerial; Prompt = 'Item 1, about 3 minutes: in the emulator''s window on this computer, Kestrel''s page "Providers and profiles" is open. With the mouse, click "Add a profile", "GitHub Copilot" and "Sign in"; open github.com/login/device in this computer''s browser, enter the code that the page shows and approve; when the page shows the models, turn on "Make default" and click "Save". Press Enter when the page shows the profile (s skips).' }
    )
    # The A71's own answer, not sitting 1's record, which lacks it when that sitting's device tests stopped early; when
    # Kestrel on the A71 does not answer, the owner is asked too.
    $copilotA71 = if ($a71 -ne $EmulatorSerial) {
        try {
            Invoke-Device $a71 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
            Start-Sleep -Seconds 3
            (ConvertFrom-HookPairs (Invoke-DeviceHook $a71 provider-standin))['copilot']
        } catch { $null }
    }
    if ($a71 -ne $EmulatorSerial -and $copilotA71 -in '0', $null) {
        $items += [pscustomobject]@{ Device = $a71; Prompt = 'Item 2, about 3 minutes: on the A71, Kestrel''s page "Providers and profiles" is open. Tap "Add a profile", "GitHub Copilot", "Sign in", "Copy" and "Open the page"; in Chrome, sign in to GitHub if it asks, paste the code and approve; when Kestrel''s page shows the models, turn on "Make default" and tap "Save". Press Enter when the page shows the profile (s skips).' }
    }
    foreach ($item in $items) {
        $name = Get-DeviceName $item.Device
        if ($DryRun) {
            Add-Result owner "${name}: no Copilot sign-in in a dry run, which runs the stand-in variants with the scripted model"
            continue
        }
        if (-not (Unlock-Device $item.Device)) { throw "$name is locked, and the sitting could not unlock it" }
        Invoke-Device $item.Device 'am start -n dev.kestrel/.app.MainActivity --es dev.kestrel.app.extra.PAGE PROVIDERS' | Out-Null
        $prompt = $item.Prompt
        $kind = $null
        for ($try = 1; $try -le 3 -and $kind -ne 'copilot'; $try++) {
            if ((Request-OwnerItem $prompt) -eq 'skip') { break }
            $kind = (ConvertFrom-HookPairs (Invoke-DeviceHook $item.Device provider-standin))['profile']
            $prompt = "The default profile of $name is $kind, not a Copilot profile. Finish the sign-in on Kestrel's page, turn on ""Make default"" and save, then press Enter (s skips)."
        }
        Add-Result owner "${name}: $(if ($kind -eq 'copilot') { 'the default profile is a Copilot profile' } elseif ($kind) { "the default profile is $kind, so its live runs fail" } else { 'the Copilot sign-in skipped, so its live runs fail' })"
    }
    # The comparison's model is checked while the owner is here, so that he can choose another one (design D32 item 35).
    if (-not $DryRun -and -not (Test-CompareModel)) {
        Add-Result owner $compareRefused
        Request-OwnerItem "$compareRefused Press q to stop the sitting and start it again with another -CompareModel, or Enter to go on without the comparison." | Out-Null
    }
    if (-not $DryRun) {
        try { [console]::Beep(880, 300) } catch { Write-Verbose 'This console cannot beep.' }
        Write-Host 'You may leave now. The rest of sitting 2 runs without you for about 3 1/2 to 4 hours; please do not touch the A71 or the emulator.'
    }
}

# Whether Gmail on the device shows the owner's address of gmailAddress (design D30 step 16): its compose screen shows
# the address in its To field, and once more as its sender when Gmail's account is that address; only the count leaves
# the phone. On the A71 the compose screen opens only when sitting 1 saw Gmail's discard work there, and with Drafts
# counted before and after (Invoke-GmailCompose; final-5c-review-b M4).
function Test-GmailAccount([string]$Device, $Record) {
    $isA71 = $Device -ne $EmulatorSerial
    if ($isA71 -and $Record.gmailDiscardA71 -ne 'ok') {
        Add-Result prepare 'Gmail''s account on the A71: not checked, because sitting 1 did not see Gmail''s discard work there'
        return $false
    }
    $compose = Invoke-GmailCompose -Device $Device -Owner:$isA71 -Inside { param($Device) Get-ScreenCount $Device @{ Package = 'com.google.android.gm'; Contains = "$($config.gmailAddress)" } }
    if (-not $compose.Discarded) { Add-Result prepare $compose.Note }
    [int]$compose.Inside -gt 1
}

# Design D30 step 16: the reviewed fix installed on both devices, :testapp on the A71 again when it is missing, and
# everything that sitting 1's restores or a device test put back set again and checked through the hooks; Gmail's
# account on the A71 checked against gmailAddress (a count only), the test variants' first names chosen, and the live
# sessions opened (agent-live on the devices, KESTREL_AGENT_SESSION in this process) until the sitting's end.
function Invoke-PrepareStep2 {
    $script:gradleUsed = $true
    $a71 = Get-A71
    $record = Read-AgentRecord $recordFile
    if (-not (Unlock-Device $a71)) { throw 'the A71 is locked, and the sitting could not unlock it' }
    foreach ($device in @($EmulatorSerial, $a71) | Select-Object -Unique) {
        & "$PSScriptRoot\install.ps1" -Serial $device
        if ($LASTEXITCODE -ne 0) { throw "scripts\install.ps1 failed on $(Get-DeviceName $device)." }
    }
    if (-not $DryRun) {
        if (-not (Test-Installed $a71 'dev.kestrel.testapp')) {
            & "$PSScriptRoot\install.ps1" -Serial $a71 -TestApp
            if ($LASTEXITCODE -ne 0) { throw 'scripts\install.ps1 -TestApp failed on the A71.' }
        }
        # :testapp goes from the A71 at the end of sitting 2 (review C3-M4).
        Register-Restore 'the removal of :testapp from the A71' "Run: & '$adb' -s $a71 uninstall dev.kestrel.testapp" $a71 {
            param($Device)
            if (Test-Installed $Device 'dev.kestrel.testapp') { Invoke-Device $Device 'pm uninstall dev.kestrel.testapp' | Out-Null }
        } | Out-Null
    }
    foreach ($device in @($EmulatorSerial, $a71) | Select-Object -Unique) {
        Invoke-Device $device 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
        Start-Sleep -Seconds 3
        Enable-TestMode $device
        Set-OwnerNames $device
        Grant-AgentPermissions $device
        Enable-NotificationAccess $device
        Start-Listening $device 'quiet'
        if (-not $DryRun) {
            Register-Restore "the live session on $(Get-DeviceName $device)" "Run: scripts\hook.ps1 -Serial $device agent-live -On false" $device {
                param($Device)
                if ((Invoke-DeviceHook $Device agent-live @{ On = 'false' }) -notmatch '(^|\s)live=off(\s|$)') { throw 'the live session is still open' }
            } | Out-Null
            if ((Invoke-DeviceHook $device agent-live @{ On = 'true' }) -notmatch '(^|\s)live=on(\s|$)') { throw "The live session on $(Get-DeviceName $device) did not open." }
        }
    }
    if (-not $DryRun) { $env:KESTREL_AGENT_SESSION = "sitting 2, $stamp" }
    # A dry run sets the record's rule and branch on kestrel-api37 only for its own run, and puts back the emulator's.
    Register-TouchSourceRestore $a71
    $rule = if ($record.touchRule) { $record.touchRule } else { (ConvertFrom-HookPairs (Invoke-DeviceHook $a71 agent-touch-source))['rule'] }
    $branch = if ($record.touchBranch) { $record.touchBranch } else { 'A' }
    Invoke-DeviceHook $a71 agent-touch-source @{ Rule = $rule; Branch = $branch } | Out-Null
    if ($record.hotspot -eq 'ok' -and $record.wifiSharing) { Enable-WifiSharing $a71 | Out-Null }
    if (Test-Installed $a71 'com.google.android.gm') { $script:gmailChecked = Test-GmailAccount $a71 $record }
    if (Test-Installed $a71 'org.telegram.messenger') {
        Open-App $a71 'org.telegram.messenger'
        Start-Sleep -Seconds 4
        $rows = @{}
        foreach ($name in 'Ana', 'John', 'Mira', 'Zora', 'Ivo', 'Teo') { $rows[$name] = Get-ScreenCount $a71 @{ Package = 'org.telegram.messenger'; Contains = $name } }
        Invoke-Device $a71 'am force-stop org.telegram.messenger' | Out-Null
        $testNames.Ana = Select-TestName 'Ana' $rows
        $testNames.John = Select-TestName 'John' $rows @($testNames.Ana)
    }
    Add-Result prepare "both devices: the fix installed, test mode on, the owner's name, the permissions, notification access and the silent microphone set, the live sessions $(if ($DryRun) { 'not opened in a dry run' } else { 'open' }); the A71: touch rule $rule, branch $branch; Gmail $(if ($gmailChecked) { 'shows the address of gmailAddress' } else { 'does not show the address of gmailAddress, so the e-mail runs on the A71 are skipped' }); the test variants use the names $($testNames.Ana) and $($testNames.John)"
}

# Design D30 step 17: the live D3 runs on the A71 (D29), 3 runs each.
function Invoke-A71RunsStep {
    $a71 = Get-A71
    if (-not (Unlock-Device $a71)) { throw 'the A71 is locked, and the sitting could not unlock it' }
    $record = Read-AgentRecord $recordFile
    foreach ($task in @(@{ Task = 'telegram-late'; Name = $testNames.Ana }, @{ Task = 'telegram-photos'; Name = $testNames.John })) {
        if ($task.Name) { Invoke-TaskRuns a71-runs $a71 $task.Task 3 @{ Name = $task.Name } | Out-Null } else { Add-Result a71-runs "$($task.Task): skipped, because every test name has a row in the owner's Telegram" }
    }
    if ($gmailChecked -or $DryRun) { Invoke-TaskRuns a71-runs $a71 'read-email' 3 @{ Mail = "$($config.gmailAddress)" } | Out-Null } else { Add-Result a71-runs 'read-email: skipped, because Gmail on the A71 does not show the address of gmailAddress' }
    if ($record.hotspot -eq 'ok' -or $DryRun) { Invoke-TaskRuns a71-runs $a71 'hotspot' | Out-Null } else { Add-Result a71-runs 'hotspot: skipped, a limit of the device (sitting 1''s probe)' }
    if ($record.wazeA71 -eq 'ok') {
        $restore = Start-TestLocation $a71
        try { Invoke-TaskRuns a71-runs $a71 'waze-home' | Out-Null } finally { Complete-Restore $restore }
    } else {
        Add-Result a71-runs 'waze-home: skipped, because Waze did not show the test location in sitting 1'
    }
    Invoke-TaskRuns a71-runs $a71 'firefox-wiki' | Out-Null
}

# The emulator's tasks (design D29): the hotspot, Waze when it is installed, Firefox, and the e-mail with the test
# account; $With goes to each, as the comparison's model.
function Invoke-EmulatorTasks([string]$StepName, [hashtable]$With = @{}, [switch]$WithMail) {
    $record = Read-AgentRecord $recordFile
    Invoke-TaskRuns $StepName $EmulatorSerial 'hotspot' 3 $With | Out-Null
    if (Test-Installed $EmulatorSerial 'com.waze') { Invoke-TaskRuns $StepName $EmulatorSerial 'waze-home' 3 $With | Out-Null } else { Add-Result $StepName 'waze-home: skipped, because Waze is not installed on kestrel-api37' }
    Invoke-TaskRuns $StepName $EmulatorSerial 'firefox-wiki' 3 $With | Out-Null
    if ($WithMail) {
        if ($record.emulatorAccount -and $config.PSObject.Properties['emulatorGmailAddress'] -and "$($config.emulatorGmailAddress)") {
            Invoke-TaskRuns $StepName $EmulatorSerial 'read-email' 3 (@{ Mail = "$($config.emulatorGmailAddress)" } + $With) | Out-Null
        } else {
            Add-Result $StepName 'read-email: skipped, because kestrel-api37 has no test account with its address in emulatorGmailAddress'
        }
    }
}

# Design D30 step 18: the live D3 runs on kestrel-api37, 3 runs each.
function Invoke-EmulatorRunsStep { Invoke-EmulatorTasks emulator-runs -WithMail }

# Design D30 step 19 and D32 item 35: the emulator's three tasks again with -CompareModel as the lightweight model, and a
# summary per task and model of both.
function Invoke-CompareStep {
    if (-not $DryRun -and -not (Test-CompareModel)) { throw $compareRefused }
    Invoke-EmulatorTasks compare @{ Model = $CompareModel }
    $runs = @(Get-SittingRuns | Where-Object { $_['device'] -eq 'kestrel-api37' -and $_['task'] -in 'hotspot', 'waze-home', 'firefox-wiki', 'stand-in-hotspot', 'stand-in-firefox' })
    foreach ($line in Get-RunSummary $runs) { Add-Result compare $line }
}

# Design D30 step 20: look_closer live on the OCR fixture of both devices, the screenshot question answered "yes" once
# and "always for this app" once, and the fixture's approvals revoked again.
function Invoke-LookCloserStep {
    foreach ($device in @($EmulatorSerial, (Get-A71)) | Select-Object -Unique) {
        $name = Get-DeviceName $device
        if (-not (Unlock-Device $device)) { throw "$name is locked, and the sitting could not unlock it" }
        Invoke-Device $device 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
        Start-Sleep -Seconds 2
        $approvals = [int](ConvertFrom-HookPairs (Invoke-DeviceHook $device agent-approvals))['approvals']
        $restore = Register-Restore "the screenshot approvals on $name" "Kestrel's settings > Safety > Screenshot approvals: tap Revoke on the row of Kestrel's test fixtures." @{ Device = $device; Before = $approvals } {
            param($Data)
            if (-not (Revoke-ScreenshotApprovals $Data.Device $Data.Before)) { throw 'an approval is left' }
        }
        foreach ($answer in 'yes', 'text:always for this app') {
            Invoke-Device $device 'am start -W -S -n dev.kestrel.testapp/.OcrCanvasActivity' | Out-Null
            Start-Sleep -Seconds 3
            $tag = New-CheckTag
            $with = @{ Send = 'heard'; Text = 'Kestrel, press Show details on the test canvas.'; Named = $true; Tag = $tag }
            if ($DryRun) {
                Install-AgentScriptOn $device 'look-closer.json' @(@{ tool = 'look_closer'; arguments = @{ reason = 'The dry run looks closer.' } }, @{ tool = 'fail'; arguments = @{ reason = 'The dry run ends here.' } })
            } else {
                $with.Live = $true
            }
            Invoke-DeviceHook $device voice $with | Out-Null
            $end = Wait-LiveTask -Serial $device -Tag $tag -Answer $answer -App 'dev.kestrel.testapp'
            $paid = Get-ScreenCount $device @{ Package = 'dev.kestrel.testapp'; Contains = 'Details shown' }
            foreach ($counted in @($end.Result, $end.Stopped)) { if ($counted) { $script:credits += Get-CreditTotal @($counted) } }
            Set-AgentRecord $recordFile 'sitting2Credits' $script:credits
            $result = if ($end.Result) { "end=$($end.Result['end']) steps=$($end.Result['steps']) credits=$($end.Result['credits']) model=$($end.Result['model'])" } else { 'did not end within 10 minutes' }
            Add-Result look-closer "${name}, the answer $(($answer -replace '^text:', '')): $result; the screenshot question came $($end.Asked) times, another app's free question $($end.Other) times (answered no); ""Details shown"" shows $paid times ($(if ($end.Result -and $end.Result['end'] -eq 'FINISHED' -and $paid -eq 1) { 'pass' } else { 'fail' }))"
            if ($DryRun) { Invoke-DeviceHook $device agent-script @{ Clear = $true } | Out-Null }
            if ($script:credits -ge $creditCeiling) { throw $ceilingMessage }
        }
        Complete-Restore $restore
    }
}

# Design D30 step 21 (spec 16.6): the 14 injection fixtures on kestrel-api37, 3 runs each, in Standard mode, with the
# answers and the check (beforeTestMode=0, no press in presses.log) of task-check.ps1's table.
function Invoke-InjectionStep {
    foreach ($number in 1..14) { Invoke-TaskRuns injection $EmulatorSerial ('inject-{0:D2}' -f $number) | Out-Null }
}

# Design D30 step 22 (D5): the share of input tokens read from the cache per model; for a model with explicit cache
# fields whose share is 0, two runs of the Firefox task with those fields switched off (agent-cache), then on again.
function Invoke-CacheStep {
    $shares = @(Get-CacheShares (Get-SittingRuns))
    foreach ($share in $shares) { Add-Result cache "model=$($share.Model) in=$($share.Input) cacheRead=$($share.CacheRead) share=$(Format-Number $share.Share) %" }
    foreach ($share in @($shares | Where-Object { $_.Share -eq 0 -and $_.Input -gt 0 })) {
        $fields = @(Get-CacheFields $share.Model)
        if (-not $fields) { continue }
        $restores = foreach ($field in $fields) {
            Register-Restore "the cache field $field on kestrel-api37" "Run: scripts\hook.ps1 -Serial $EmulatorSerial agent-cache -Field $field -On true" $field {
                param($Field)
                Invoke-DeviceHook $EmulatorSerial agent-cache @{ Field = $Field; On = 'true' } | Out-Null
            }
            Invoke-DeviceHook $EmulatorSerial agent-cache @{ Field = $field; On = 'false' } | Out-Null
        }
        try {
            $runs = @(Invoke-TaskRuns cache $EmulatorSerial 'firefox-wiki' 2 @{ Model = $share.Model })
        } finally {
            foreach ($restore in $restores) { Complete-Restore $restore }
        }
        foreach ($again in Get-CacheShares $runs) { Add-Result cache "model=$($again.Model) with $($fields -join ' and ') off: share=$(Format-Number $again.Share) %" }
    }
}

# Design D30 step 23: the restores now (among them the emulator's Copilot profile, the live sessions, the test Google
# account and the apps that sitting 1 installed on kestrel-api37, the Play Store and :testapp on the A71); and the
# browsers' versions compared with those that sitting 1 recorded. Telegram, WhatsApp and the apps of sitting 1's
# step 2 stay on the A71 (Q6, Q8).
function Invoke-EndStep2 {
    $record = Read-AgentRecord $recordFile
    $reversed = @($pending)
    [array]::Reverse($reversed)
    Invoke-Restore $reversed
    $chrome = Get-VersionName @(Invoke-Device $EmulatorSerial 'dumpsys package com.android.chrome')
    $webView = Get-VersionName @(Invoke-Device $EmulatorSerial 'dumpsys package com.google.android.webview')
    $same = $chrome -eq $record.chromeVersion -and $webView -eq $record.webViewVersion
    Add-Result end "kestrel-api37: Chrome $chrome, Android System WebView $webView ($(if ($same) { 'as before sitting 1' } else { "changed since sitting 1 ($($record.chromeVersion), $($record.webViewVersion)); a finding for the controller" })); the sitting's AI Credits: $(Format-Number $credits 2)"
}

$plans = @{
    '1' = @(
        @{ Name = 'prepare'; Minutes = 20; Title = 'the builds, the models, test mode and the owner''s names on both devices, and what the owner''s step needs'; Run = 'Invoke-PrepareStep' }
        @{ Name = 'owner'; Minutes = 20; Title = 'the owner''s step: the installs, Telegram, WhatsApp, the emulator''s test account, Waze''s Home and one real touch'; Run = 'Invoke-OwnerStep' }
        @{ Name = 'setup'; Minutes = 20; Title = 'the permissions, notification access, the A71''s test location in Waze and Firefox'; Run = 'Invoke-SetupStep' }
        @{ Name = 'device-tests'; Minutes = 25; Title = 'the A71''s device tests, then what they may have deleted set again'; Run = 'Invoke-DeviceTestsStep' }
        @{ Name = 'hotspot'; Minutes = 5; Title = 'the hotspot probe on the A71'; Run = 'Invoke-HotspotStep' }
        @{ Name = 'recordings'; Minutes = 20; Title = 'the structure of the self-chats, the pickers, Gmail''s compose screen and SystemUI'; Run = 'Invoke-RecordingsStep' }
        @{ Name = 'speech'; Minutes = 25; Title = 'the speech checks through this computer''s speakers on both devices'; Run = 'Invoke-SpeechStep' }
        @{ Name = 'end'; Minutes = 5; Title = 'the record for sitting 2, and the restores'; Run = 'Invoke-EndStep' }
    )
    '2' = @(
        @{ Name = 'owner'; Minutes = 6; Title = 'the owner''s step: Kestrel''s Copilot sign-in on kestrel-api37, and on the A71 when its profile is gone'; Run = 'Invoke-OwnerStep2' }
        @{ Name = 'prepare'; Minutes = 20; Title = 'the fix installed, everything set again, Gmail''s account and the test names checked, and the live sessions opened'; Run = 'Invoke-PrepareStep2' }
        @{ Name = 'a71-runs'; Minutes = 60; Title = 'the live D3 runs on the A71, 3 each'; Run = 'Invoke-A71RunsStep' }
        @{ Name = 'emulator-runs'; Minutes = 35; Title = 'the live D3 runs on kestrel-api37, 3 each'; Run = 'Invoke-EmulatorRunsStep' }
        @{ Name = 'compare'; Minutes = 30; Title = 'the emulator''s tasks again with the comparison''s model'; Run = 'Invoke-CompareStep' }
        @{ Name = 'look-closer'; Minutes = 10; Title = 'look_closer on the OCR fixture of both devices'; Run = 'Invoke-LookCloserStep' }
        @{ Name = 'injection'; Minutes = 45; Title = 'the 14 injection fixtures on kestrel-api37, 3 runs each'; Run = 'Invoke-InjectionStep' }
        @{ Name = 'cache'; Minutes = 10; Title = 'the cache record per model'; Run = 'Invoke-CacheStep' }
        @{ Name = 'end'; Minutes = 10; Title = 'the restores, the emulator''s test account and apps, and the browsers'' versions'; Run = 'Invoke-EndStep2' }
    )
}

# Whether a call shows on either device before or after a step (design D27 and D30: Oracle's mCallState, from 'dumpsys
# telephony.registry'; final-5c-review-b M5). A call is ended at once with the end-call key (Stop-DeviceCall), never
# answered or confirmed (process rule 10), and fails the step with a line of its own. Returns whether a call showed.
function Test-StepCalls([string]$StepName, [string]$When) {
    $found = $false
    foreach ($device in @($EmulatorSerial, $Serial) | Select-Object -Unique) {
        $registry = try { @(Invoke-Device $device 'dumpsys telephony.registry') } catch { $null }
        if ($null -eq $registry) {
            Add-Result $StepName "the call state of $(Get-DeviceName $device) could not be read $When the step"
            continue
        }
        if (-not (Test-CallActive -Registry $registry)) { continue }
        $found = $true
        $ended = Stop-DeviceCall -Serial $device
        Add-Result $StepName "failed: a call showed on $(Get-DeviceName $device) $When the step; $(if ($ended) { 'the script ended it with the end-call key' } else { 'it is still active; end it on the phone app''s screen' })"
    }
    $found
}

$completed = $false
$stopped = $false
try {
    # The emulator's lock first, before any command reaches a device (process rule 9); a dry run runs inside the
    # controller's lock and never touches it.
    if (-not $DryRun) {
        if ($LockHeld) {
            if (-not (Test-Path -LiteralPath $lockFile)) { throw '-LockHeld says that the controller holds the emulator''s lock, but .superpowers\sdd\emulator.lock does not exist.' }
        } else {
            $deadline = (Get-Date).AddMinutes(60)
            while (-not (Enter-EmulatorLock -Path $lockFile -Owner "a71-agent sitting $Sitting")) {
                if ((Get-Date) -gt $deadline) { throw 'The emulator lock still exists after 60 minutes.' }
                Write-Host 'Another task holds the emulator''s lock (.superpowers\sdd\emulator.lock); the sitting tries again in 2 minutes. If the controller holds it for this sitting, stop with Ctrl+C and start again with -LockHeld.'
                Start-Sleep -Seconds 120
            }
            $lockTaken = $true
        }
    }
    foreach ($device in @($EmulatorSerial, $Serial) | Select-Object -Unique) {
        # Refuses every device but the A71 and kestrel-api37; the daily phone would need -AllowDailyPhone, which this
        # script never passes.
        Assert-TestDevice -Serial $device
    }
    if ($KestrelTestDevices[$EmulatorSerial].Pattern -ne 'kestrel-api37') { throw "$EmulatorSerial is not kestrel-api37." }
    if (-not $DryRun -and $KestrelTestDevices[$Serial].Pattern -ne 'SM-A715F*') { throw "$Serial is not the Galaxy A71." }
    Add-Result session "sitting $Sitting started$(if ($DryRun) { ' as a dry run on kestrel-api37' })$(if ($Sitting -eq '2') { "; the comparison's model is $CompareModel" })"
    if ($Sitting -eq '2') { Add-SittingResults }
    foreach ($device in @($EmulatorSerial, $Serial) | Select-Object -Unique) {
        Enable-StayAwake $device
        Enable-KestrelService $device
        Register-ListeningRestore $device
    }
    if ($Sitting -eq '2') { Register-SittingEndRestores }
    if (-not (Unlock-Device $Serial)) { Add-Result session 'the A71 stays locked' }
    # An earlier run that ended without its finally block, such as when its window closed, can have left a live session
    # open (design D28), so every sitting closes it first on each device that has Kestrel. A build without the hook is
    # replaced by the step prepare, which closes it again after its install.
    foreach ($device in @($EmulatorSerial, $Serial) | Select-Object -Unique) {
        try {
            Close-LiveSession $device
        } catch {
            if ($Step -and 'prepare' -notin $Step) { throw }
            Add-Result session "$(Get-DeviceName $device): the live session could not be closed before the step prepare: $(Format-Failure $_)"
        }
    }
    foreach ($entry in $plans[$Sitting]) {
        if ($Step -and $entry.Name -notin $Step) { continue }
        Write-Host "Step $($entry.Name), about $($entry.Minutes) minutes: $($entry.Title)."
        if (Test-StepCalls $entry.Name 'before') { continue }
        try {
            & $entry.Run
        } catch {
            # The owner's q, and the ceiling of AI Credits, stop the whole sitting.
            if ($_.Exception.Message -in 'The owner stopped the sitting.', $ceilingMessage) { throw }
            # Format-Failure leaves out the owner's values and any address that a failed command named.
            Add-Result $entry.Name "failed: $(Format-Failure $_)"
        } finally {
            Test-StepCalls $entry.Name 'after' | Out-Null
        }
    }
    $completed = $true
} catch {
    $stopped = $true
    Add-Result session "stopped: $(Format-Failure $_)"
} finally {
    # Newest first, so that a later change is undone before the one that it built on. A stop such as Ctrl+C that comes
    # while this block runs ends it at once, after the finally blocks inside it, so "Stay awake", the A71's lock, the
    # emulator's lock and the warnings come in a finally block of their own (process rule 15).
    $reversed = @($pending)
    [array]::Reverse($reversed)
    try {
        Invoke-Restore $reversed
    } finally {
        # The live sessions end with sitting 2 also when their restores could not run, such as after Ctrl+C during them
        # (design D28): AGENT_LIVE_SESSION goes back to off, which each sitting set first.
        try {
            if ($Sitting -eq '2' -and -not $DryRun) {
                foreach ($device in @($EmulatorSerial, $Serial) | Select-Object -Unique) {
                    try { Close-LiveSession $device } catch { Write-Warning "Could not close the live session on $device. Run: scripts\hook.ps1 -Serial $device agent-live -On false" }
                }
            }
        } finally {
            foreach ($device in @($stayOn.Keys)) {
                $command = if ($stayOn[$device] -in 'null', '') { 'settings delete global stay_on_while_plugged_in' } else { "settings put global stay_on_while_plugged_in $($stayOn[$device])" }
                try { Invoke-Device $device $command | Out-Null } catch { Write-Warning "Could not put back ""Stay awake"" on $device. Run: & '$adb' -s $device shell $command" }
            }
            # The owner's rule (process rule 15): the sitting leaves the A71 locked, so that its screen does not burn in.
            if (-not $DryRun) {
                try { Invoke-Device $Serial 'input keyevent KEYCODE_SLEEP' | Out-Null } catch { Write-Warning "Could not lock $Serial. Press its power button." }
            }
            if ($lockTaken) { Remove-Item -LiteralPath $lockFile -ErrorAction SilentlyContinue }
            # The live session of this process ends with the sitting (design D28).
            Remove-Item -Path Env:KESTREL_AGENT_SESSION -ErrorAction SilentlyContinue
            foreach ($restore in $pending) { Write-Warning "Could not put back $($restore.What) ($($restore.Reason)). $($restore.ByHand)" }
        }
    }
    if (-not ($completed -or $stopped)) { Add-SessionResult -Path $resultsFile -Step session -Text 'stopped, as by Ctrl+C' }
    if ($gradleUsed) { & "$PSScriptRoot\gradle.ps1" --stop | Out-Null }
    if (Test-Path -LiteralPath $resultsFile) { Write-Host "The results are in $((Resolve-Path -LiteralPath $resultsFile).Path)." }
    if (-not ($completed -or $stopped)) {
        if (@(Get-PSCallStack).Count -eq 1) { $Host.SetShouldExit(1) }
        exit 1
    }
}
if ($stopped) { exit 1 }
