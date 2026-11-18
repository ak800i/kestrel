# The session on the Galaxy A71 with the owner for the instant commands (plan 3C, design D16 of sub-project 3; design spec,
# sections 18.3 and 19.1). It takes the owner through every check of sub-project 3 that needs the A71 or the owner, in
# order. It asks before it reads the device's model and before each step. In each check the owner says a command to
# Kestrel, or does what the script asks, and then answers whether Kestrel did what the script describes; the script also
# records the kind and the outcome of Kestrel's latest instant command, never what Kestrel heard. The steps' estimates
# add up to 95 minutes; the session takes about 80 to 105 minutes, of which about 70 to 95 need the owner, because
# almost every step needs the owner's voice or hands, and only about 8 to 13 minutes (the install at the start, the
# transcriptions of the step timing and the restores at the end) run without the owner. The steps:
#   install        installs Kestrel's debug build and copies the speech models, turns Kestrel's accessibility service
#                  on, and records whether a SIM card is in the phone
#   screen         numbers, labels, the grid, tap, long press, scroll, "Which one?", bare labels and "require verbs"
#   swipes         the edge swipes, swipe forwards and backwards, and a slider
#   notifications  "show notifications", "expand", "collapse" and "dismiss" on a notification that the script posts
#   global         back, home, Quick Settings, recent apps, the previous app, the lock screen, the power menu, a
#                  screenshot (deleted afterwards), "open apps", and "open" with three of the owner's apps
#   profiles       the users and the profiles that the launcher API lists, as IDs, types and counts (research r21)
#   radios         Wi-Fi and Bluetooth off and on
#   sound          volumes, mute, silence, media play and pause, magnification and Circle to Search
#   text           dictation and editing with Samsung Keyboard, "show password" and "search for"
#   calls          a call to a number that the owner gives and confirms, the question "yes, call" in the phone app with
#                  1234 and with letters in the number field (answered "no"), the contacts permission at first use, calls
#                  to a contact of the owner's choice cancelled by voice and by a touch, "Which one?", and "copy this phone
#                  number"; it checks first, every time it runs, that no SIM card is in the phone (without one, Android
#                  refuses a call, research r19), and the owner confirms it; with a SIM card, or when the phone does not
#                  say that none is there, it skips the calls
#   timing         the snapshots that Kestrel builds while it listens on a busy screen, and the transcription time with the
#                  screen's labels as boost words (design spec, section 8.3)
#   recordings     the owner's recordings of the lines of corpus\commands.txt that are marked for the owner and that no
#                  earlier session recorded
# The results go into corpus\owner\commands-session-<time>.txt and the recordings into corpus\owner\clips; git ignores
# both. scripts\voice-eval.ps1 measures the recordings later on the emulator. The script puts back what it changes on the
# phone, also when a step fails or Ctrl+C stops it: Kestrel's mode Off, the accessibility settings, a magnification (which
# it ends by turning Kestrel's accessibility service off and on once), the setting "Stay awake", Wi-Fi and Bluetooth, the
# volumes of the call, ring, music, alarm and notification streams, the sound mode, the keyboard, Kestrel's contacts
# permission and the screenshot that it took; the clipboard stays as the owner leaves it. It refuses every device but the
# A71 and kestrel-api37. -DryRun runs the session on kestrel-api37 without the owner: it answers every question itself,
# sends what the owner would say through the voice hook, skips what needs the owner's hands otherwise, and records two
# silent clips into a folder of its own that it deletes. -Step runs only the named steps, also as one text separated by
# commas. It needs PowerShell 7.3 or newer, as scripts\hook.ps1 does. After Ctrl+C, it ends with exit code 1.
# Example: scripts\a71-commands.ps1 -Serial R58N12345AB
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [switch]$DryRun,
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

# Whether the answer of 'getprop gsm.sim.state' says that no SIM card is in the phone, in each of its slots. A phone
# with a SIM card can say NOT_READY or UNKNOWN while its radio starts or is off, so only ABSENT and an empty slot count.
function Test-SimAbsent([string]$State) {
    $slots = @("$State".Trim() -split ',' | ForEach-Object { $_.Trim() })
    @($slots | Where-Object { $_ -notin @('', 'ABSENT') }).Count -eq 0
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

# The IDs of the screenshots in the answer of 'content query ... --projection _id:_display_name', whose lines read
# 'Row: 0 _id=123, _display_name=Screenshot_20261120_101500.png'. Other images are the owner's, and stay.
function Get-NewScreenshotIds([string[]]$Rows) {
    foreach ($line in $Rows) {
        if ($line -match '\b_id=(\d+), _display_name=Screenshot') { [long]$Matches[1] }
    }
}

# One check's line of the results: what the owner said or did, the owner's answer, and the kind and the outcome of
# Kestrel's instant command from the voice-state hook, only when that command is newer than $Before, the number that
# Get-CommandNumber read before the check; a check whose words gave no command records 'kind=- outcome=none', never the
# command before it. Never what Kestrel heard.
function Format-CheckResult([string]$Name, [string]$Owner, $State, [int]$Before) {
    $command = if (-not $State) {
        'kind=- outcome=-'
    } elseif ([int]$State['command'] -le $Before) {
        'kind=- outcome=none'
    } else {
        "kind=$($State['kind']) outcome=$($State['outcome'])"
    }
    "${Name}: owner=$Owner $command"
}

# The number of Kestrel's latest instant command before a check. A command that still runs then, such as one that asks
# "Which one?", counts as the check's own, because the owner's words answer it.
function Get-CommandNumber($State) {
    $number = [int]$State['command']
    if ($State['outcome'] -eq 'running') { $number - 1 } else { $number }
}

# The median of some numbers, or 0 without any.
function Get-Median([double[]]$Values) {
    if (-not $Values -or $Values.Count -eq 0) { return 0 }
    $sorted = @($Values | Sort-Object)
    $sorted[[int][math]::Floor(($sorted.Count - 1) / 2)]
}

$stepNames = @('install', 'screen', 'swipes', 'notifications', 'global', 'profiles', 'radios', 'sound', 'text', 'calls', 'timing', 'recordings')
$Step = Get-SessionSteps -Step $Step -Names $stepNames
# Before any command reaches a device: a dry run answers the owner's questions itself, so it must never reach a phone.
if ($DryRun -and $Serial -notlike 'emulator-*') { throw 'A dry run runs only on the emulator kestrel-api37.' }

. "$PSScriptRoot\lib\Kestrel.ps1"
. "$PSScriptRoot\lib\Corpus.ps1"
. "$PSScriptRoot\lib\Session.ps1"
Use-KestrelToolchain

$owner = Join-Path $PSScriptRoot '..\corpus\owner'
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'
$resultsFile = if ($DryRun) { Join-Path $owner "commands-dry-run-$stamp.txt" } else { Join-Path $owner "commands-session-$stamp.txt" }
$clipsFolder = if ($DryRun) { Join-Path $owner "commands-dry-run-clips-$stamp" } else { Join-Path $owner 'clips' }
$corpusFile = Join-Path $PSScriptRoot '..\corpus\commands.txt'
$remote = Get-EvalFolder
$service = 'dev.kestrel/dev.kestrel.app.KestrelAccessibilityService'
$streams = @(0, 2, 3, 4, 5)
$simAbsent = $false
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

# Asks the owner a question until the answer is y or n, and returns it, so that a stray Enter is never taken as an
# answer. In a dry run it answers $DryRunAnswer itself.
function Read-YesNo([string]$Prompt, [string]$DryRunAnswer = 'dry') {
    if ($DryRun) {
        Write-Host "[dry run] $Prompt"
        return $DryRunAnswer
    }
    $answer = "$(Read-Host $Prompt)".Trim().ToLowerInvariant()
    while ($answer -notin 'y', 'n') { $answer = "$(Read-Host 'Type y or n')".Trim().ToLowerInvariant() }
    $answer
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

# After the owner's words: waits up to $StartSeconds for Kestrel to start a command newer than $Number, and only when one
# started, up to $EndSeconds for it to end. Returns whether one started.
function Wait-CheckCommand([int]$Number, [double]$StartSeconds = 3, [double]$EndSeconds = 15) {
    if ((Wait-State ({ param($State) [int]$State['command'] -gt $Number }.GetNewClosure()) $StartSeconds) -lt 0) { return $false }
    Write-Host "Waiting up to $EndSeconds s for Kestrel's command to end."
    Wait-State ({ param($State) [int]$State['command'] -gt $Number -and $State['outcome'] -ne 'running' }.GetNewClosure()) $EndSeconds | Out-Null
    $true
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

# Turns Kestrel on with its own microphone, from the front, where Android lets it start its microphone service, and goes
# back to the home screen. Loading the models takes up to a minute on the A71.
function Start-Kestrel {
    Invoke-Hook voice-clip @{ Clear = $true } | Out-Null
    $state = Get-VoiceState
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

# Before the first step that runs: what every step may change is saved, and the screen stays on while the phone is
# plugged in. Registered first, so that it comes back last; Kestrel's mode Off comes back after the accessibility
# settings, so that Kestrel is still on to turn off.
function Initialize-Session {
    if ($script:prepared) { return }
    $script:prepared = $true
    # Last of all, because revoking a permission ends Kestrel's process.
    Register-Restore "Kestrel's contacts permission" 'Settings > Apps > Kestrel > Permissions > Contacts' (Test-ContactsGranted) {
        param($Data)
        Assert-TestDevice -Serial $Serial
        $now = Test-ContactsGranted
        if ($now -and -not $Data) { Invoke-Checked 'pm revoke dev.kestrel android.permission.READ_CONTACTS' | Out-Null }
        if ($Data -and -not $now) { Invoke-Checked 'pm grant dev.kestrel android.permission.READ_CONTACTS' | Out-Null }
    } | Out-Null
    Register-Restore "Kestrel's mode Off" "Run: scripts\hook.ps1 -Serial $Serial voice -Send off" $null { Stop-Kestrel } | Out-Null
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
    # The sound mode first and the volumes second, so that the volumes come back first: on a phone, setting the ring
    # volume also sets the sound mode, so the sound mode's check must come last. The two restores are separate, so that
    # a failure of one does not stop the other.
    Register-Restore 'the sound mode' 'Set the sound mode back in Quick Settings: sound, vibrate or mute.' $sound.Ringer {
        param($Data)
        Assert-TestDevice -Serial $Serial
        $modes = @{ '0' = 'SILENT'; '1' = 'VIBRATE'; '2' = 'NORMAL' }
        if ((Read-DeviceValue 'settings get global mode_ringer') -eq $Data -or -not $modes.Contains($Data)) { return }
        # Android 13 has no shell command for the sound mode; there the command does nothing, and the check below warns.
        Invoke-Checked "cmd audio set-ringer-mode $($modes[$Data])" | Out-Null
        if ((Read-DeviceValue 'settings get global mode_ringer') -ne $Data) { throw 'Android did not take the sound mode from the shell' }
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
    $stayOn = Read-DeviceValue 'settings get global stay_on_while_plugged_in'
    Register-Restore 'the setting "Stay awake"' "Run: & '$adb' -s $Serial shell settings put global stay_on_while_plugged_in $stayOn" $stayOn {
        param($Data)
        Assert-TestDevice -Serial $Serial
        $command = if ($Data -in 'null', '') { 'settings delete global stay_on_while_plugged_in' } else { "settings put global stay_on_while_plugged_in $Data" }
        Invoke-Checked $command | Out-Null
    } | Out-Null
    Invoke-Checked 'svc power stayon true' | Out-Null
    # Kestrel's accessibility service, next to the owner's other services.
    $enabled = if ($accessibility.Contains('enabled_accessibility_services')) { "$($accessibility['enabled_accessibility_services'])" } else { 'null' }
    $services = @(@($enabled -split ':') | Where-Object { $_ -and $_ -ne 'null' -and $_ -ne $service }) + $service
    if (-not (Set-SecureSetting -Serial $Serial -Name 'enabled_accessibility_services' -Value ($services -join ':'))) { throw 'Could not turn on Kestrel''s accessibility service.' }
    if (-not (Set-SecureSetting -Serial $Serial -Name 'accessibility_enabled' -Value '1')) { throw 'Could not turn on accessibility.' }
    Request-Owner 'The phone''s screen now stays on while the phone is plugged in, and Kestrel''s accessibility service is on. Keep the phone unlocked during the session, except when a step asks you to lock it. Press Enter to go on.' 'go' | Out-Null
}

# Sends what the owner would say, as if Kestrel had heard it, with a pause after it.
function Send-Heard([string]$Text) {
    Invoke-Hook voice @{ Send = 'heard'; Text = $Text } | Out-Null
    Start-Sleep -Seconds 2
}

# One check: the owner prepares what $Prepare says, if anything, says $Say to Kestrel, and answers whether $Look
# happened. The result holds $Name (or else $Say), the owner's answer and the kind and the outcome of the instant command
# that the owner's words started, if they started one. A dry run sends $Heard (by default $Say) through the voice hook,
# then $Answer, if any, as the answer to Kestrel's question, and answers itself. The owner's answer, or 'skipped', stays
# in $script:lastAnswer.
function Invoke-Check([string]$StepName, [string]$Say, [string]$Look, [string]$Prepare = '', [string]$Heard = '', [string]$Name = '', [string]$Answer = '') {
    $script:lastAnswer = 'skipped'
    # What the results call the check: $Name when what the owner says is private, such as a phone number.
    $label = if ($Name) { $Name } else { $Say }
    if ($Prepare -and (Request-Owner "$Prepare Press Enter when it is ready, or s to skip this check." 'go') -eq 'skip') {
        Add-Result $StepName "${label}: skipped"
        return
    }
    $before = Get-VoiceState
    $number = if ($before) { Get-CommandNumber $before } else { 0 }
    if ($DryRun) {
        Send-Heard $(if ($Heard) { $Heard } else { $Say })
        if ($Answer) {
            Wait-State ({ param($State) [int]$State['command'] -gt $number }.GetNewClosure()) 15 | Out-Null
            # Kestrel says its question before it listens for the answer.
            Start-Sleep -Seconds 3
            Send-Heard $Answer
        }
    } elseif ((Request-Owner "Say: $Say    (then press Enter once Kestrel has reacted, or after about 5 seconds if nothing happens; s skips the check)") -eq 'skip') {
        Add-Result $StepName "${label}: skipped"
        return
    }
    # Without Kestrel's state before the check, no command can be told to be the check's own.
    $state = if ($before -and (Wait-CheckCommand -Number $number)) { Get-VoiceState } else { $before }
    $answer = Read-YesNo "$Look Type y or n." 'dry'
    $script:lastAnswer = $answer
    Add-Result $StepName (Format-CheckResult -Name $label -Owner $answer -State $state -Before $number)
}

# Whether Kestrel may read the contacts.
function Test-ContactsGranted {
    @(Get-GrantedPermissions -Dump (Invoke-Checked 'dumpsys package dev.kestrel') -Permissions @('android.permission.READ_CONTACTS')).Count -gt 0
}

# In a dry run, wakes the emulator's screen and dismisses its keyguard, as the owner would unlock the phone.
function Wait-Unlock {
    Request-Owner 'Unlock the phone as you always do, then press Enter.' 'go' | Out-Null
    if ($DryRun) {
        Invoke-Checked 'input keyevent KEYCODE_WAKEUP' | Out-Null
        Invoke-Checked 'wm dismiss-keyguard' | Out-Null
    }
}

# The largest row ID of the phone's images, so that the screenshot check deletes only newer screenshots.
function Get-LargestImageId { Get-LargestRowId -Rows (Invoke-Checked 'content query --uri content://media/external/images/media --projection _id') }

function Invoke-InstallStep {
    # On Android 13 an update ends Kestrel's process with the reason of the owner's stop, so Kestrel would stay off after it.
    try { Stop-Kestrel } catch { Write-Host 'Kestrel did not answer, so it is probably not installed yet.' }
    $script:gradleUsed = $true
    & "$PSScriptRoot\install.ps1" -Serial $Serial
    if ($LASTEXITCODE -ne 0) { throw 'scripts\install.ps1 failed.' }
    & "$PSScriptRoot\push-models.ps1" -Serial $Serial
    Add-Result install 'Kestrel installed, and the speech models copied'
    # Only recorded here; the step calls checks again right before its first call.
    $absent = Test-SimAbsent -State (Read-DeviceValue 'getprop gsm.sim.state')
    Add-Result install "SIM card: $(if ($absent) { 'none' } else { 'present, or the phone does not say that none is there' })"
    Start-Kestrel
    Add-Result install 'Kestrel listens'
}

function Invoke-ScreenStep {
    Start-Kestrel
    Invoke-Checked 'am start -a android.settings.SETTINGS' | Out-Null
    Invoke-Check screen 'show numbers' 'Did a number appear on each button and row of Settings?'
    Invoke-Check screen 'tap 3' 'Did Kestrel tap the element with the number 3?' -Prepare 'Go back to the first page of Settings, where the numbers show.'
    Invoke-Check screen 'go back' 'Did Settings go back a page?'
    Invoke-Check screen 'hide numbers' 'Did the numbers go?'
    Invoke-Check screen 'show labels' 'Did a name appear next to each button?'
    Invoke-Check screen 'hide labels' 'Did the names go?'
    Invoke-Check screen 'show grid' 'Did numbered squares cover the screen?'
    Invoke-Check screen 'more squares' 'Did the squares get smaller?'
    Invoke-Check screen 'fewer squares' 'Did the squares get bigger?'
    Invoke-Check screen 'hide grid' 'Did the squares go?'
    Invoke-Check screen 'scroll down' 'Did the list of Settings move down?'
    Invoke-Check screen 'scroll to top' 'Is the list back at its top?'
    Invoke-Check screen 'tap Display' 'Did the page Display open?'
    Invoke-Check screen 'go back' 'Is the first page of Settings back?'
    Invoke-Check screen 'Display' 'Did the page Display open, from the name alone?'
    Invoke-Check screen 'go home' 'Is the home screen in front?'
    Invoke-Check screen "long press and the name of an app under its icon on the home screen" 'Did the app''s menu open?' -Heard 'long press Phone'
    Invoke-Check screen 'go back' 'Did the menu close?'
    Invoke-Check screen "tap and a name that two buttons on the screen share" 'Did Kestrel ask "Which one?" and show numbers on just those buttons?' -Prepare 'Open a screen where two buttons have the same name, such as two "Install" or two "Delete" buttons.' -Heard 'tap Delete'
    Invoke-Check screen 'the number of the button that you want' 'Did Kestrel tap that one, and did the numbers go?' -Heard '1'
    Invoke-Checked "am start -n dev.kestrel/.app.MainActivity --es dev.kestrel.app.extra.PAGE COMMANDS" | Out-Null
    if ((Request-Owner 'Kestrel''s page "Instant commands" is open. Switch "Require verbs" on, then press Enter.' 'skip') -eq 'go') {
        Invoke-Checked 'am start -a android.settings.SETTINGS' | Out-Null
        Invoke-Check screen 'Display' 'Did nothing happen, because a name alone no longer taps?'
        Invoke-Checked "am start -n dev.kestrel/.app.MainActivity --es dev.kestrel.app.extra.PAGE COMMANDS" | Out-Null
        Request-Owner 'Switch "Require verbs" off again, then press Enter.' 'go' | Out-Null
    } elseif ($DryRun) {
        # The dry run cannot switch "Require verbs". Instead, a transcript without words, which gives Kestrel no command,
        # shows that such a check records none.
        Invoke-Check screen 'nothing, as a transcript without words' 'Did nothing happen?' -Heard '...'
    }
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
}

function Invoke-SwipeStep {
    Start-Kestrel
    Invoke-Checked 'am start -a android.settings.SETTINGS' | Out-Null
    Invoke-Check swipes 'tap Display' 'Did the page Display open?'
    Invoke-Check swipes 'swipe from the left edge' 'Did Settings go back, as with the back gesture?'
    Invoke-Check swipes 'swipe from the bottom edge' 'Is the home screen in front, as with the home gesture?'
    Invoke-Check swipes 'swipe forwards' 'Did the home screen show its next page?'
    Invoke-Check swipes 'swipe backwards' 'Did the home screen show its first page again?'
    Invoke-Checked 'am start -a android.settings.DISPLAY_SETTINGS' | Out-Null
    Invoke-Check swipes 'show numbers' 'Did a number appear on the brightness slider?'
    Invoke-Check swipes 'increment and the brightness slider''s number' 'Did the brightness go up one step?' -Heard 'increment 1'
    Invoke-Check swipes 'decrement and the same number' 'Did the brightness go back down?' -Heard 'decrement 1'
    Invoke-Check swipes 'hide numbers' 'Did the numbers go?'
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
}

function Invoke-NotificationStep {
    Start-Kestrel
    # A notification of the shell, with a long text that the shade can expand; it holds no private text.
    Invoke-Checked "cmd notification post -S bigtext -t 'Kestrel check' kestrel-check 'The first line of a long text. A second line that the expanded notification shows. A third line.'" | Out-Null
    Invoke-Check notifications 'show notifications' 'Did the notification shade open with the notification "Kestrel check"?'
    Invoke-Check notifications 'expand Kestrel check' 'Did the notification show its whole text?'
    Invoke-Check notifications 'collapse Kestrel check' 'Did it show one line again?'
    Invoke-Check notifications 'dismiss Kestrel check' 'Did the notification go?'
    Invoke-Check notifications 'go home' 'Did the shade close?'
    Request-Owner 'If the notification "Kestrel check" is still there, swipe it away, then press Enter.' 'go' | Out-Null
}

function Invoke-GlobalStep {
    Start-Kestrel
    Invoke-Checked 'am start -a android.settings.SETTINGS' | Out-Null
    Invoke-Check global 'tap Display' 'Did the page Display open?'
    Invoke-Check global 'go back' 'Did Settings go back a page?'
    Invoke-Check global 'go home' 'Is the home screen in front?'
    Invoke-Check global 'show quick settings' 'Did Quick Settings open?'
    Invoke-Check global 'home' 'Is the home screen in front again?'
    Invoke-Check global 'show recent apps' 'Did the recent apps show?'
    Invoke-Check global 'go home' 'Is the home screen in front?'
    Invoke-Check global 'show previous app' 'Did the app that you used before the current one come back?' -Prepare 'Open two of your apps, one after the other, and stay in the second.'
    Invoke-Check global 'lock screen' 'Did the screen turn off and lock?'
    Wait-Unlock
    Add-Result global "after the unlock, Kestrel's mode is $((Get-VoiceState).mode)"
    Invoke-Check global 'turn off device' 'Did the power menu open?'
    Invoke-Check global 'go back' 'Did the power menu close?'
    $images = Get-LargestImageId
    # Registered before the screenshot, so that a failure or Ctrl+C before the delete below still deletes it at the end.
    $screenshots = Register-Restore 'the deletion of the screenshot that the session took' 'Delete the newest screenshot in Gallery.' ([pscustomobject]@{ Largest = $images; Deleted = 0 }) {
        param($Data)
        Assert-TestDevice -Serial $Serial
        $new = @(Get-NewScreenshotIds -Rows (Invoke-Checked "content query --uri content://media/external/images/media --projection _id:_display_name --where _id\>$($Data.Largest)"))
        foreach ($id in $new) {
            Invoke-Checked "content delete --uri content://media/external/images/media --where _id=$id" | Out-Null
            $Data.Deleted++
        }
    }
    Invoke-Check global 'take a screenshot' 'Did the phone take a screenshot, without Kestrel''s bar on it?'
    Start-Sleep -Seconds 3
    Complete-Restore $screenshots
    if ($pending.Contains($screenshots)) {
        Add-Result global "the new screenshots are not deleted yet ($($screenshots.Reason)); the session tries again at its end"
    } else {
        Add-Result global "deleted $($screenshots.Data.Deleted) new screenshots"
    }
    Invoke-Check global 'open apps' 'Did the list of all apps open?'
    Invoke-Check global 'go home' 'Is the home screen in front?'
    foreach ($try in 1..3) {
        Invoke-Check global "open and the name of one of your apps (app $try of 3)" 'Did that app open?' -Heard 'open Settings'
        Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    }
}

function Invoke-ProfileStep {
    $answer = Read-OwnerAnswer 'Is Secure Folder on, on this phone? Type y or n (it stays as it is).' 'n'
    Add-Result profiles "Secure Folder on: $answer"
    foreach ($user in Get-UserSummary -Users (Invoke-Checked 'pm list users') -Dump (Invoke-Checked 'dumpsys user')) { Add-Result profiles "user $user" }
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
            @{ Say = 'turn off Wi-Fi'; Setting = 'wifi_on'; On = $false }
            @{ Say = 'turn on Wi-Fi'; Setting = 'wifi_on'; On = $true }
            @{ Say = 'turn off Bluetooth'; Setting = 'bluetooth_on'; On = $false }
            @{ Say = 'turn on Bluetooth'; Setting = 'bluetooth_on'; On = $true })) {
        Invoke-Check radios $check.Say 'Did Kestrel open the panel, switch the radio and close the panel again?'
        Start-Sleep -Seconds 2
        $now = (Read-DeviceValue "settings get global $($check.Setting)") -ne '0'
        Add-Result radios "$($check.Say): Android says $(if ($now -eq $check.On) { 'done' } else { 'not done' })"
    }
}

function Invoke-SoundStep {
    Start-Kestrel
    foreach ($check in @(
            @{ Say = 'volume up'; Stream = 3 }
            @{ Say = 'volume down'; Stream = 3 }
            @{ Say = 'turn the ring volume up'; Stream = 2 }
            @{ Say = 'turn the ring volume down'; Stream = 2 }
            @{ Say = 'turn the alarm volume up'; Stream = 4 }
            @{ Say = 'turn the alarm volume down'; Stream = 4 }
            @{ Say = 'turn the call volume up'; Stream = 0 }
            @{ Say = 'turn the call volume down'; Stream = 0 })) {
        $before = Get-StreamVolume -Lines (Invoke-Checked "cmd media_session volume --stream $($check.Stream) --get")
        Invoke-Check sound $check.Say 'Did the volume panel show the change?'
        $after = Get-StreamVolume -Lines (Invoke-Checked "cmd media_session volume --stream $($check.Stream) --get")
        Add-Result sound "$($check.Say): stream $($check.Stream) went from $before to $after"
    }
    Invoke-Check sound 'mute' 'Is the media volume muted?'
    Invoke-Check sound 'unmute' 'Is the media volume back?'
    Invoke-Check sound 'mute the alarm' 'Did Kestrel say that Android doesn''t let it mute the alarm volume?'
    Invoke-Check sound 'silence' 'Is the phone on vibrate?'
    Invoke-Check sound 'unmute the ringer' 'Is the sound back on?'
    Add-Result sound "the ringer mode is $(Read-DeviceValue 'settings get global mode_ringer') (2 is sound)"
    Invoke-Check sound 'media pause' 'Did the music stop?' -Prepare 'Start music in one of your music apps, then go to the home screen.'
    Invoke-Check sound 'media play' 'Did the music play again?'
    Invoke-Check sound 'media pause' 'Did the music stop?'
    # Kestrel's mode Off does not end a magnification, and the accessibility settings' restore leaves Kestrel's service on
    # when it was on before the session. Android ends the magnification that a service set when the service goes off, so
    # this restore turns Kestrel's service off and on once.
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
        Invoke-Check sound 'zoom in' 'Is the screen magnified?'
        Invoke-Check sound 'pan left' 'Did the magnified area move to the left?'
        Invoke-Check sound 'scroll right' 'Did it move to the right?'
        Invoke-Check sound 'zoom out' 'Is the screen back to normal?'
        Invoke-Check sound 'magnify' 'Is the screen magnified again?'
        Invoke-Check sound 'stop magnification' 'Is the screen back to normal?'
        if ($script:lastAnswer -eq 'y') { Remove-Restore $zoom }
    } finally {
        # Also after a failure, a skip or an answer other than y, so that the later steps run without a magnification.
        if ($pending.Contains($zoom)) {
            Complete-Restore $zoom
            # Android binds the service again a moment after the setting.
            Start-Sleep -Seconds 2
            $ended = if ($pending.Contains($zoom)) { "could not end it ($($zoom.Reason)); the session tries again at its end" } else { 'ended it' }
            Add-Result sound "turning Kestrel's accessibility service off and on, to end any magnification, $ended"
        }
    }
    Invoke-Check sound 'circle to search' 'Did Kestrel say that Circle to Search isn''t available on this phone?'
}

function Invoke-TextStep {
    Start-Kestrel
    $prepare = 'Open Samsung Notes, start a new note, and leave the cursor in it, with Samsung Keyboard showing.'
    Invoke-Check text 'hello comma how are you question mark' 'Does the note read "Hello, how are you?"?' -Prepare $prepare
    Invoke-Check text 'new line' 'Is the cursor on a new line?'
    Invoke-Check text 'capital p one two three' 'Did "P123" follow?'
    Invoke-Check text 'select all' 'Is all the text selected?'
    Invoke-Check text 'copy' 'Did the phone copy it?'
    Invoke-Check text 'go to the end' 'Is the cursor at the end?'
    Invoke-Check text 'paste' 'Is the text there twice now?'
    Invoke-Check text 'delete three words' 'Did the last three words go?'
    Invoke-Check text 'undo' 'Are they back?'
    Invoke-Check text 'redo' 'Did they go again?'
    Invoke-Check text 'select hello' 'Is "Hello" selected?'
    Invoke-Check text 'uppercase hello' 'Does it read "HELLO"?'
    Invoke-Check text 'hide keyboard' 'Did the keyboard go?'
    Invoke-Check text 'show keyboard' 'Is the keyboard back?'
    Invoke-Check text 'show password' 'Can you read the test characters in the password field now?' -Prepare 'Open the sign-in screen of one of your apps, and type only a few test characters into its password field, never your password.'
    Invoke-Check text 'search for weather tomorrow' 'Did the browser search for "weather tomorrow"?' -Prepare 'Open your browser on its start page.'
    Request-Owner 'Delete the test note and the test characters if you want, then press Enter.' 'go' | Out-Null
}

function Invoke-CallStep {
    # The SIM check runs here, right before the first call, every time the step runs: also when -Step leaves out the step
    # install, when the owner skipped it, or when it failed before its check (research r19; design D16, step 1).
    $script:simAbsent = $DryRun -or (Test-SimAbsent -State (Read-DeviceValue 'getprop gsm.sim.state'))
    if (-not $script:simAbsent) {
        Add-Result calls 'skipped: a SIM card is in the phone, or the phone does not say that none is there'
        return
    }
    if ((Read-OwnerAnswer 'Kestrel will press Call in this step. Check that no SIM card is in the phone, then type y to go on, or anything else to skip the calls.' 'y') -ne 'y') {
        Add-Result calls 'skipped by the owner'
        return
    }
    # Once, before the first call: without a SIM card, a phone still calls emergency numbers, and such a call reaches
    # real emergency services, so the owner never answers Kestrel's emergency question with yes (process rule 10).
    Write-Host ''
    Write-Host '!!! IMPORTANT, FOR THE WHOLE STEP !!!' -ForegroundColor Yellow
    Write-Host 'If Kestrel ever says "That may call an emergency number. Say ''yes, call'' to do it.", say "no".' -ForegroundColor Yellow
    Write-Host 'Never say "yes, call" in this session.' -ForegroundColor Yellow
    Write-Host ''
    Request-Owner 'Press Enter once you have read this.' 'go' | Out-Null
    # Kestrel presses Call only for a number that the owner gives and confirms, never for a made-up one that could be a
    # stranger's; the dry run calls the emulator's made-up number.
    $number = (Read-OwnerAnswer 'Type a phone number that is fine to call, such as another phone of yours, with digits only.' '0641234567') -replace '[\s-]', ''
    if ($number -notmatch '^\+?\d{5,15}$' -or (Read-OwnerAnswer "You will say ""call $number"", and Kestrel will press Call for it. Type y if that is fine." 'y') -ne 'y') {
        Add-Result calls 'call by number: skipped, no number confirmed'
        $number = $null
    }
    Start-Kestrel
    $callLog = Get-LargestRowId -Rows (Invoke-Checked 'content query --uri content://call_log/calls --projection _id')
    if ($number) {
        Invoke-Check calls "call $number" 'Did the phone app open with the number, and did Kestrel say it, count down 4 s on its bar, press Call and then say "The call didn''t start. Say it again."? The phone has no SIM card, so Android refuses the call: that is the expected result.' -Name 'call the confirmed number'
    }
    if ($DryRun) {
        # The emulator places the call; its console ends it, and the call's row goes.
        Start-Sleep -Seconds 3
        & $adb -s $Serial emu gsm cancel $number | Out-Null
        Start-Sleep -Seconds 2
        Invoke-Checked "content delete --uri content://call_log/calls --where _id\>$callLog" | Out-Null
    }
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    # The question "yes, call" in One UI's phone app (design spec, section 8.7): with a short number, which Kestrel never
    # calls itself, and then with letters in the number field, "tap call" must ask, and the owner answers "no". Never an
    # emergency number on a phone, because emergency calls go out without a SIM card, and never "yes, call"; this runs
    # only after the SIM check above found none.
    if ((Read-OwnerAnswer 'Next comes the check that Kestrel asks before it presses the phone app''s Call button for a number that it would not call: the phone app opens with 1234, you say "tap call", and you answer "no". Type y to go on, or anything else to skip it.' 'y') -eq 'y') {
        Invoke-Check calls 'call 1234' 'Did the phone app open with 1234, and did Kestrel say that you must tap Call yourself?'
        Invoke-Check calls 'tap call, and "no" when Kestrel asks' 'Did Kestrel ask you to say "yes, call", and after your "no", does the phone app still show 1234, without a call?' -Heard 'tap call' -Answer 'no'
        $field = Read-OwnerAnswer 'Say "show numbers", then type the number that Kestrel shows on the phone app''s number field (the one with 1234), or s to skip this.' 'skip'
        try {
            if ($field -match '^\d+$') {
                # Letters only, never the digits of an emergency number.
                Invoke-Hook type @{ Number = [int]$field; Text = 'abc' } | Out-Null
                Invoke-Check calls 'tap call, and "no" when Kestrel asks' 'Does the number field show "abc", did Kestrel ask you to say "yes, call", and after your "no", no call?' -Heard 'tap call' -Answer 'no'
            }
        } catch {
            if ($_.Exception.Message -eq 'The owner stopped the session.') { throw }
            # The hooks' errors name no text of the screen.
            Add-Result calls "letters in the number field: $($_.Exception.Message)"
        } finally {
            # The owner may have said "show numbers" and then skipped.
            try { Invoke-Hook overlay @{ Show = 'none' } | Out-Null } catch { Write-Host 'Kestrel did not hide its numbers.' }
        }
        Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    }
    $contact = Request-Owner 'The next checks call one of your contacts. Choose one whose number is an ordinary mobile or landline number with at least 6 digits, never an emergency, service or short number. Wherever a check says "the name", say that contact''s name. Press Enter once you have chosen one, or s to skip the checks with a contact.' 'go'
    if ($contact -eq 'skip') {
        Add-Result calls 'the checks with a contact: skipped by the owner'
    } else {
        # Revoking the permission ends Kestrel's process, so the session starts Kestrel again, after Android has brought it
        # back for its accessibility service and Kestrel has restored its mode.
        Invoke-Checked 'pm revoke dev.kestrel android.permission.READ_CONTACTS' | Out-Null
        Start-Sleep -Seconds 3
        Wait-State ({ param($State) $State['mode'] -ne 'off' }) 10 | Out-Null
        Start-Kestrel
        Invoke-Check calls 'call and the name of the contact that you chose' 'Did Kestrel say that it needs your contacts and open its settings, which ask for them? Allow it.' -Heard 'call Ana'
        Add-Result calls "contacts permission granted afterwards: $(Test-ContactsGranted)"
        Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
        Invoke-Check calls 'call and the same name, and about 3 s into the countdown "cancel"' 'Did the countdown stop, with "Cancelled" on the bar and the phone app showing the number?' -Heard 'call Ana'
        Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
        Invoke-Check calls 'call and the same name, and "stop" as soon as the countdown starts' 'Did the countdown stop?' -Heard 'call Ana'
        Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
        # The touch watcher counts a touch anywhere, and the phone app's green Call button is its largest control.
        Invoke-Check calls 'call and the same name, and during the countdown touch the clock at the top of the screen, never a button of the phone app' 'Did your touch stop the countdown? (This shows whether Kestrel sees a real finger.)' -Heard 'call Ana'
        Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
        Invoke-Check calls 'call and a first name that two of your contacts share, and when Kestrel asks "Which one?", touch the clock at the top of the screen, never a button of the phone app' 'Did Kestrel ask "Which one?" with their names, and did your touch end the question?' -Prepare 'Skip this check (s) if no two of your contacts share a first name, or if one of them has a number that is not an ordinary mobile or landline number with at least 6 digits.' -Heard 'call Ana'
        Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    }
    Invoke-Check calls 'copy this phone number' 'Paste it into a note or a message field: is it the contact''s number?' -Prepare 'Open a contact in Contacts that shows one phone number.'
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
}

function Invoke-TimingStep {
    Start-Kestrel
    $before = Invoke-Hook snapshots
    if ((Request-Owner 'Open Settings, and for about 2 minutes scroll and open pages with Kestrel, saying a command now and then ("scroll down", "tap Display", "go back"). Press Enter when you are done.' 'go') -eq 'skip') {
        Add-Result timing 'skipped by the owner'
        return
    }
    $after = Invoke-Hook snapshots
    if ($before -match 'builds=(\d+) millis=(\d+)') {
        $builds0 = [int]$Matches[1]
        $millis0 = [long]$Matches[2]
        if ($after -match 'builds=(\d+) millis=(\d+)') {
            $builds = [int]$Matches[1] - $builds0
            $millis = [long]$Matches[2] - $millis0
            $average = if ($builds -gt 0) { [math]::Round($millis / $builds) } else { 0 }
            Add-Result timing "Kestrel built $builds snapshots in $millis ms while it listened, $average ms each"
        }
    }
    # The transcription time with the busy screen's labels as boost words, against the session's words alone, on the
    # generated clips of the instant commands; the labels never leave the phone.
    $folder = Join-Path $PSScriptRoot '..\corpus\generated\piper'
    $rows = @(Read-Corpus -Path $corpusFile | Where-Object { $_.Group -eq 'appendix' -and (Test-Path -LiteralPath (Join-Path $folder "$($_.Id).wav")) } | Select-Object -First 10)
    if ($rows.Count -eq 0) {
        Add-Result timing 'transcription time not measured: no generated clips in corpus\generated\piper; make them with scripts\make-corpus.ps1 on the emulator'
        return
    }
    Stop-Kestrel
    Invoke-Checked 'am start -a android.settings.SETTINGS' | Out-Null
    Request-Owner 'Settings is open. Leave it in front, with many entries on the screen. Kestrel is off while the script measures for a few minutes, so it will not react: do not say anything or touch the phone until the next prompt. Press Enter to start.' 'go' | Out-Null
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
        Add-Result timing "transcription of $($rows.Count) command clips: median $(Get-Median $withLabels) ms with the screen's labels and the vocabulary, $(Get-Median $without) ms without them"
    } finally {
        Invoke-Hook voice-eval @{ Close = $true } | Out-Null
        Invoke-Checked "rm -f $remote/gen-timing.wav" | Out-Null
        Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    }
}

# Records the owner into a clip of up to $Seconds seconds and copies it to $File. The recording starts once the
# microphone records, the screen is woken and Kestrel's settings brought to the front first, because Android silences a
# recording otherwise. Returns the hook's answer.
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
    New-Item -ItemType Directory -Force -Path $clipsFolder | Out-Null
    # Only the lines that no earlier session recorded: plan 2D's session recorded the first 100.
    $rows = @(Read-Corpus -Path $corpusFile | Where-Object { $_.Owner -and -not (Test-Path -LiteralPath (Join-Path $clipsFolder "$($_.Id).wav")) })
    if ($DryRun) { $rows = @($rows | Select-Object -First 2) }
    Stop-Kestrel
    $minutes = [math]::Max(1, [math]::Ceiling($rows.Count * 12 / 60))
    if ((Request-Owner "Next come $($rows.Count) lines to say, about $minutes minutes. Sit in a quiet room and hold the phone as you would to talk to it; the script keeps Kestrel's settings in front. For each line, press Enter, wait for ""Speak now"", and say the line once, as you would say it to Kestrel. Press Enter to begin, or s to skip the recordings." 'go') -eq 'skip') {
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

$plan = @(
    @{ Name = 'install'; Minutes = 7; Title = 'installing Kestrel, its accessibility service and the SIM check'; Run = 'Invoke-InstallStep' }
    @{ Name = 'screen'; Minutes = 12; Title = 'numbers, labels, the grid, taps, scrolling, "Which one?" and require verbs'; Run = 'Invoke-ScreenStep' }
    @{ Name = 'swipes'; Minutes = 5; Title = 'the edge swipes, swiping pages and a slider'; Run = 'Invoke-SwipeStep' }
    @{ Name = 'notifications'; Minutes = 3; Title = 'a notification in the shade'; Run = 'Invoke-NotificationStep' }
    @{ Name = 'global'; Minutes = 9; Title = 'back, home, Quick Settings, recent apps, lock, power menu, screenshot and apps'; Run = 'Invoke-GlobalStep' }
    @{ Name = 'profiles'; Minutes = 1; Title = 'the phone''s users and profiles, as IDs and counts'; Run = 'Invoke-ProfileStep' }
    @{ Name = 'radios'; Minutes = 4; Title = 'Wi-Fi and Bluetooth'; Run = 'Invoke-RadioStep' }
    @{ Name = 'sound'; Minutes = 11; Title = 'volumes, mute, silence, media, magnification and Circle to Search'; Run = 'Invoke-SoundStep' }
    @{ Name = 'text'; Minutes = 11; Title = 'dictation and editing with Samsung Keyboard, show password and search'; Run = 'Invoke-TextStep' }
    @{ Name = 'calls'; Minutes = 12; Title = 'calls by number and name, the guard of the Call button, the contacts permission and copying a number'; Run = 'Invoke-CallStep' }
    @{ Name = 'timing'; Minutes = 6; Title = 'the snapshots while Kestrel listens, and the transcription time with labels'; Run = 'Invoke-TimingStep' }
    @{ Name = 'recordings'; Minutes = 14; Title = 'your recordings of the new command lines'; Run = 'Invoke-RecordingStep' }
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
        # The restores that are left, the newest first, so that the screen, the clips, the sound, the radios, the
        # keyboard, the accessibility settings, Kestrel's mode and its permission come last.
        $left = @($pending.ToArray())
        [array]::Reverse($left)
        Invoke-Restore $left
    } finally {
        if (-not ($completed -or $stopped)) { Add-SessionResult -Path $resultsFile -Step session -Text 'stopped, as by Ctrl+C' }
        if ($DryRun -and (Test-Path -LiteralPath $clipsFolder)) { Remove-Item -LiteralPath $clipsFolder -Recurse -Force }
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
