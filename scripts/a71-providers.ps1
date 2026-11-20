# The owner's session of sub-project 4 (design D20, made automatic by plan 4D) on kestrel-api37 and the Galaxy A71: the
# Copilot sign-in on each, the live cases of scripts\provider-check.ps1 on each, the providers' device tests and the
# Custom Tab's redirect check with the A71's own Chrome (prototype P5's q1 and q3), the credentials after a restart of
# Kestrel's process, and the end, where the emulator's Copilot profile goes. The script taps through Kestrel's own pages
# itself, through the hook screen-has, which finds a label of Kestrel's own windows (the package dev.kestrel, never
# Chrome's or another app's) and gives only its bounds, and checks each
# device's state through the hooks; every time that it reports was measured on the device. The owner is needed only at
# the start, right after the install, for what only his GitHub account can do (map section 6): he approves the
# emulator's sign-in code in a browser on this computer (the script puts the code on this computer's clipboard and opens
# github.com/login/device, and puts the text that was on the clipboard back afterwards), and he approves the A71's
# sign-in in Chrome on the A71 (the script saves the A71's clipboard before Kestrel's "Copy" and puts it back afterwards).
# Both devices stay awake during the session, and the setting comes back at its end. Each item has one prompt, which beeps
# and says what he does and what the screens show, and the script then says that he may leave. The A71 keeps its Copilot
# profile at the end, as the owner decided on 2026-11-20. The script unlocks the A71 itself when it is locked, with the
# owner's test PIN, which he allows the sessions to use: it reads the PIN from the key "pin" of
# corpus\owner\a71-config.json only for a real session, stops at its start with a clear message when the file or the key
# is missing, types it only when Android says clearly that the PIN pad shows, never again after a typing that did not
# unlock the A71 (the steps that need an unlocked A71 are then skipped), and never writes it
# anywhere. The steps, in this order,
# and their estimates, which add up to 44 minutes, of which about 5 need the owner:
#   install         installs Kestrel's debug build on kestrel-api37 and on the A71 (5 minutes)
#   emulator-signin the Copilot sign-in on kestrel-api37, which the owner approves on github.com (3 minutes with him)
#   a71-signin      the Copilot sign-in on the A71 in a Custom Tab, which the owner approves in it, and whether Kestrel's
#                   page came back to the front by itself (3 minutes with him)
#   emulator-live   every live case on kestrel-api37 (8 minutes)
#   a71-tests       the credential store, the loopback server and the Custom Tab on the A71, with the redirect check
#                   through postman-echo.com in the A71's Chrome; whether the A71 has StrongBox (12 minutes)
#   a71-live        every live case on the A71 (8 minutes)
#   a71-restart     Kestrel's process ended with 'am crash', started again, and one live case, so that the stored
#                   credential decrypts after a restart (3 minutes)
#   end             the script deletes the emulator's Copilot profile through Kestrel's profile page; the A71 keeps its
#                   profile (2 minutes)
# The live cases spend the owner's GitHub AI Credits from his Copilot subscription: about 2 to 4 credits for each of
# emulator-live and a71-live, and far less than 1 for a71-restart's one case, so about 4 to 8 in all, a little more with
# retries; the first prompt says so. A failed case is recorded with its error kind, and gets one retry when
# corpus\owner\provider-overrides.json, which the controller writes before the session, names an override for that kind
# ({"<kind>": {"Headers": "a=b", "MessagesPath": "/x"}}); a failure that the retry does not fix is a finding. The results
# go into corpus\owner\providers-<time>.txt, which git ignores; neither it nor the console ever hold a prompt, a reply, a
# code, a token or a key, and the sign-in code stays in this script's memory and on this computer's clipboard. The script
# refuses every device but the A71 and kestrel-api37, and -Serial must be the A71. The A71's screen stays on while it is
# plugged in ("Stay awake"), and Kestrel's accessibility service is on on both devices while the script taps through its
# pages; both are put back at the end. Every way that a real session ends (its end, q, Ctrl+C, an error or a skipped step)
# deletes the owner's Copilot profile on kestrel-api37 through the debug hook provider-delete, which the script registers
# before the emulator's sign-in, and writes a result line that says whether it did; when it cannot, its last message says
# how to delete the profile by hand. A real session runs
# inside the emulator's lock .superpowers\sdd\emulator.lock (process rule 9), because other work shares the emulator: with
# -LockHeld the controller holds the lock for the session, and the script only checks that its file exists; without it,
# the script takes the lock itself, waits up to 60 minutes for another task's lock, and deletes its own at the end.
# -Unattended runs without the owner: it skips the sign-ins and the live cases (also on a Copilot profile that a device
# kept, such as the A71's), so that it spends none of his AI Credits, and says so in a result line.
# -DryRun runs every step on kestrel-api37 without the owner, inside the controller's lock, which it never touches: it taps
# through Kestrel's pages up to the button "Sign in", which it never taps, runs the live cases against the hook
# provider-standin's Copilot stand-in on the device and fails a case whose answer does not come from the stand-in, runs the
# redirect check with the emulator's Chrome, and checks at the end that the stand-in is off and reports the default profile
# and the number of Copilot profiles. -Step runs only the named steps, also as one text separated by commas.
# It needs PowerShell 7.3 or newer, as scripts\hook.ps1 does. After Ctrl+C, it ends with exit code 1.
# Example: scripts\a71-providers.ps1 -Serial R58N12345AB -EmulatorSerial emulator-5554 -LockHeld
#Requires -Version 7.3
param(
    [string]$Serial,
    [string]$EmulatorSerial = 'emulator-5554',
    [switch]$DryRun,
    [switch]$Unattended,
    [switch]$LockHeld,
    [string[]]$Step
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
. "$PSScriptRoot\lib\Session.ps1"
. "$PSScriptRoot\lib\Oracle.ps1"
Use-KestrelToolchain

$stepNames = @('install', 'emulator-signin', 'a71-signin', 'emulator-live', 'a71-tests', 'a71-live', 'a71-restart', 'end')

# The names of -Step, also as one text separated by commas, as pwsh -File passes them; an unknown name stops the script.
function Get-SessionSteps([string[]]$Step, [string[]]$Names) {
    $chosen = @($Step | ForEach-Object { "$_" -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    $unknown = @($chosen | Where-Object { $_ -notin $Names })
    if ($unknown) { throw "Unknown steps: $($unknown -join ', '). The steps are: $($Names -join ', ')." }
    $chosen
}

# The override that the controller prepared for a failed case's error kind, such as 'BadRequest' from
# 'BadRequest(status=400,code=x)', as parameters of provider-check.ps1, or $null.
function Get-RetryOverride($Overrides, [string]$ErrorText) {
    if (-not $Overrides -or -not $ErrorText) { return $null }
    $kind = ($ErrorText -split '\(')[0]
    $entry = $Overrides.PSObject.Properties | Where-Object { $_.Name -eq $kind } | Select-Object -First 1
    if (-not $entry) { return $null }
    $with = @{}
    foreach ($name in 'Headers', 'MessagesPath') {
        $value = $entry.Value.PSObject.Properties[$name]
        if ($value -and $value.Value) { $with[$name] = "$($value.Value)" }
    }
    if ($with.Count -eq 0) { return $null }
    $with
}

# The answer of provider-standin, such as 'standin=off profile=copilot copilot=1', as an ordered table: whether the
# stand-in runs, the kind of the default profile and the number of Copilot profiles.
function ConvertFrom-StandInAnswer([string]$Text) {
    $pairs = ConvertFrom-HookPairs $Text
    if (-not $pairs.Contains('standin') -or -not $pairs.Contains('profile') -or "$($pairs['copilot'])" -notmatch '^\d+$') {
        throw "Not an answer of provider-standin: '$Text'. Install the current debug build with scripts\install.ps1."
    }
    $pairs
}

# The device code on Kestrel's sign-in page, from the snapshot's JSON (the hook snapshot of Kestrel's own page), such as
# 'WDJB-MJHT', or $null. The code stays in memory; nothing prints it.
function Get-DeviceCode([string]$Snapshot) {
    $found = [regex]::Match($Snapshot, '(?<![A-Z0-9-])[A-Z0-9]{4}-[A-Z0-9]{4}(?![A-Z0-9-])')
    if ($found.Success) { $found.Value } else { $null }
}

# The centre of the bounds that screen-has gives ('left,top,right,bottom'), as 'x y' for 'input tap', or $null.
function Get-BoundsCentre([string]$Bounds) {
    if ($Bounds -notmatch '^(-?\d+),(-?\d+),(-?\d+),(-?\d+)$') { return $null }
    "$([int](([int]$Matches[1] + [int]$Matches[3]) / 2)) $([int](([int]$Matches[2] + [int]$Matches[4]) / 2))"
}

# Why a live step does not run, or $null when it runs. A dry run runs it against the stand-in; an unattended run spends
# none of the owner's AI Credits, also on a Copilot profile that a device kept; a real session needs the device's default
# profile ($DefaultProfile, the kind from provider-standin) to be a Copilot profile.
function Get-LiveSkipReason([bool]$DryRun, [bool]$Unattended, [string]$DefaultProfile, [string]$DeviceName = 'the device') {
    if ($DryRun) { return $null }
    if ($Unattended) { return 'skipped: an unattended run spends none of the owner''s AI Credits' }
    if ($DefaultProfile -ne 'copilot') { return "skipped: $DeviceName has no default Copilot profile" }
    $null
}

# Whether screen-has's answer says that Kestrel is the app in front (app=, the package of the active application window),
# so that a tap at an element's centre cannot land on another app's window that covers part of it.
function Test-KestrelInFront($Found) { "$($Found['app'])" -eq 'dev.kestrel' }

# The answer of provider-delete, such as 'deleted=1 failed=0 copilot=0', as an ordered table.
function ConvertFrom-DeleteAnswer([string]$Text) {
    $pairs = ConvertFrom-HookPairs $Text
    foreach ($name in 'deleted', 'failed', 'copilot') {
        if ("$($pairs[$name])" -notmatch '^\d+$') { throw "Not an answer of provider-delete: '$Text'. Install the current debug build with scripts\install.ps1." }
    }
    $pairs
}

$Step = @(Get-SessionSteps -Step $Step -Names $stepNames)
if ($DryRun -and $Serial -and $Serial -notlike 'emulator-*') { throw 'A dry run runs only on the emulator kestrel-api37.' }
if (-not $DryRun -and -not $Serial) { throw 'Give the A71''s serial with -Serial.' }
# In a dry run, the A71's steps run on kestrel-api37 too.
if ($DryRun) { $Serial = $EmulatorSerial }

$owner = Join-Path $PSScriptRoot '..\corpus\owner'
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'
$resultsFile = Join-Path $owner "providers-$(if ($DryRun) { 'dry-run-' })$stamp.txt"
$overridesFile = Join-Path $owner 'provider-overrides.json'
$overrides = if (Test-Path -LiteralPath $overridesFile) { Get-Content -LiteralPath $overridesFile -Raw | ConvertFrom-Json } else { $null }
$adb = Get-Adb
$gradleUsed = $false
$standInDevices = [System.Collections.Generic.List[string]]::new()
# "Stay awake" of each device as it was before the session changed it.
$stayOn = [ordered]@{}
# The owner's configuration with the A71's test PIN, only for a real session: a missing file or key stops the session
# here, before any command reaches a device. Only Unlock-TestPhone and Send-TestPin read the PIN, when they type it.
$pinConfig = if ($DryRun) { $null } else { Join-Path $owner 'a71-config.json' }
if ($pinConfig) { Send-TestPin -ConfigPath $pinConfig -CheckOnly }
$lockFile = Join-Path $PSScriptRoot '..\.superpowers\sdd\emulator.lock'
$lockTaken = $false

# One item of the owner's: this computer beeps, and the prompt says what he does and what the screens show; Enter starts
# it, s skips it and q stops the session. A dry run answers $DryRunAnswer itself, and an unattended run skips it.
function Request-OwnerItem([string]$Prompt, [string]$DryRunAnswer = 'skip') {
    if ($DryRun) {
        Write-Host "[dry run] $Prompt"
        return $DryRunAnswer
    }
    if ($Unattended) {
        Write-Host "[unattended] $Prompt"
        return 'skip'
    }
    try { [console]::Beep(880, 300) } catch { Write-Verbose 'This console cannot beep.' }
    $answer = "$(Read-Host $Prompt)".Trim().ToLowerInvariant()
    if ($answer -eq 'q') { throw 'The owner stopped the session.' }
    if ($answer -in 's', 'skip') { 'skip' } else { 'go' }
}

# Taps the first element of Kestrel's own windows (the package dev.kestrel) whose label is $Label (the hook screen-has
# compares it on the phone and gives only its bounds), scrolling down up to six times to find it. Throws when the screen
# does not show it, or when another app stays in front of Kestrel. Never another app's element: on the A71 Chrome's Custom
# Tab and its prompts, such as "Save password?", have buttons with the same labels.
function Invoke-TapLabel([string]$Device, [string]$Label) {
    for ($try = 0; $try -le 6; $try++) {
        $found = Find-KestrelElement $Device @{ Label = $Label }
        $centre = Get-BoundsCentre $found['bounds']
        if ([int]$found['count'] -gt 0 -and $centre) {
            Assert-KestrelInFront $Device $found $Label
            Invoke-SettingsCommand -Serial $Device -Command "input tap $centre" | Out-Null
            Start-Sleep -Seconds 2
            return
        }
        Invoke-Hook $Device scroll @{ Direction = 'down' } | Out-Null
        Start-Sleep -Seconds 1
    }
    throw "The screen of $(Get-DeviceName $Device) shows no ""$Label""."
}

function Get-DeviceName([string]$Device) { if ($Device -eq $EmulatorSerial) { 'kestrel-api37' } else { 'the A71' } }

# The answer of screen-has for $With among the elements of Kestrel's own windows. While such an element shows but another
# app is in front, it asks again for up to $Seconds, and then returns the last answer.
function Find-KestrelElement([string]$Device, [hashtable]$With, [int]$Seconds = 10) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($true) {
        $found = ConvertFrom-HookPairs (Invoke-Hook $Device screen-has (@{ Package = 'dev.kestrel' } + $With))
        if ([int]$found['count'] -eq 0 -or (Test-KestrelInFront $found) -or $watch.Elapsed.TotalSeconds -ge $Seconds) { return $found }
        Start-Sleep -Seconds 2
    }
}

# Throws instead of a tap while another app is in front of Kestrel.
function Assert-KestrelInFront([string]$Device, $Found, [string]$Label) {
    if (-not (Test-KestrelInFront $Found)) {
        throw "The screen of $(Get-DeviceName $Device) shows ""$Label"", but another app stayed in front of Kestrel, so the script did not tap it."
    }
}

# Waits up to $Seconds until one of Kestrel's own windows shows an element whose label is $Label. Returns whether it does.
function Wait-Label([string]$Device, [string]$Label, [int]$Seconds) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        if ([int](ConvertFrom-HookPairs (Invoke-Hook $Device screen-has @{ Package = 'dev.kestrel'; Label = $Label }))['count'] -gt 0) { return $true }
        Start-Sleep -Seconds 2
    } while ($watch.Elapsed.TotalSeconds -lt $Seconds)
    $false
}

# Kestrel's accessibility service on the device, which screen-has needs. The accessibility settings of each device are
# saved before the first change, and one restore, registered with the first, puts back those of every device.
$savedAccessibility = [ordered]@{}
function Enable-KestrelService([string]$Device) {
    if ($savedAccessibility.Contains($Device)) { return }
    if ($savedAccessibility.Count -eq 0) {
        Register-Restore 'the accessibility settings' 'Put back the accessibility services in Settings > Accessibility on kestrel-api37 and the A71.' {
            $failed = @(foreach ($device in @($savedAccessibility.Keys)) {
                    if (-not (Restore-AccessibilitySettings -Serial $device -Saved $savedAccessibility[$device])) { $device }
                })
            if ($failed) { throw "see the warning above about $($failed -join ' and ')" }
        } | Out-Null
    }
    $saved = Get-AccessibilitySettings -Serial $Device
    $savedAccessibility[$Device] = $saved
    $service = 'dev.kestrel/dev.kestrel.app.KestrelAccessibilityService'
    $enabled = if ($saved.Contains('enabled_accessibility_services')) { "$($saved['enabled_accessibility_services'])" } else { 'null' }
    $services = @(@($enabled -split ':') | Where-Object { $_ -and $_ -ne 'null' -and $_ -ne $service }) + $service
    if (-not (Set-SecureSetting -Serial $Device -Name 'enabled_accessibility_services' -Value ($services -join ':'))) { throw 'Could not turn on Kestrel''s accessibility service.' }
    if (-not (Set-SecureSetting -Serial $Device -Name 'accessibility_enabled' -Value '1')) { throw 'Could not turn on accessibility.' }
    # Android binds the service a moment after the setting.
    Start-Sleep -Seconds 3
}

# Waits up to $Seconds until Kestrel's accessibility service on the device is bound (the hook state answers 'screen=on'),
# because screen-has answers only an error until then: Android binds the service a while after the setting, and an
# unlock's 'uiautomator dump' unbinds it while it runs. Throws when it is still not bound, so that the step fails with
# that reason instead of at its first tap.
function Wait-ServiceBound([string]$Device, [int]$Seconds = 30) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($true) {
        # Kestrel's process may still be starting, and then answers no hook.
        $state = try { "$(Invoke-Hook $Device state)" } catch { '' }
        if ($state -like 'screen=on*') { return }
        if ($watch.Elapsed.TotalSeconds -ge $Seconds) { throw "Kestrel's accessibility service on $(Get-DeviceName $Device) was not bound after $Seconds s." }
        Start-Sleep -Seconds 1
    }
}

# Opens Kestrel's page "Providers and profiles" on the device and taps through the add flow up to the button "Sign in" of
# GitHub Copilot, which shows the device code; a dry run stops before that button, so that no request reaches GitHub.
function Open-CopilotSignIn([string]$Device) {
    Enable-KestrelService $Device
    Open-Providers $Device
    Wait-ServiceBound $Device
    Start-Sleep -Seconds 3
    Invoke-TapLabel $Device 'Add a profile'
    # The list of providers, scrolled until "Copilot" shows; the search field stays empty, so that its text is no match.
    Invoke-TapLabel $Device 'Copilot'
    Invoke-TapLabel $Device 'GitHub Copilot'
    if ($DryRun) {
        if (-not (Wait-Label $Device 'Sign in' 10)) { throw 'Kestrel''s page shows no "Sign in".' }
        return
    }
    Invoke-TapLabel $Device 'Sign in'
}

# Unlocks the A71 with the owner's test PIN when it shows its lock screen (Unlock-TestPhone types the PIN only when
# Android says clearly that the PIN pad shows), and returns whether it is unlocked. An unlock that did not work stops
# every later unlock of the session: the steps that need an unlocked A71 are then skipped, and the others go on. The
# emulator of a dry run has no PIN and is only woken.
function Unlock-A71 {
    if ($DryRun) {
        Invoke-SettingsCommand -Serial $Serial -Command 'input keyevent KEYCODE_WAKEUP' | Out-Null
        return $true
    }
    if ($script:unlockFailed) { return $false }
    # $false: it typed nothing; an error: the PIN did not unlock the A71 ("the unlock failed").
    $why = 'the A71 did not show its PIN pad clearly, so the script typed nothing'
    $unlocked = try { Unlock-TestPhone -Serial $Serial -ConfigPath $pinConfig } catch { $why = "$($_.Exception.Message)"; $false }
    if (-not $unlocked) {
        $script:unlockFailed = $true
        Add-Result session "$why; the session tries no more unlocks and skips the steps that need an unlocked A71"
    }
    $unlocked
}

# Deletes the device's Copilot profile through Kestrel's profile page, as the owner would: "Copilot (default)", then
# "Delete profile" and "Delete" in the dialog. Returns whether no Copilot profile is left.
function Remove-CopilotProfile([string]$Device) {
    Enable-KestrelService $Device
    Open-Providers $Device
    Start-Sleep -Seconds 3
    Invoke-TapLabel $Device 'Copilot (default)'
    Invoke-TapLabel $Device 'Delete profile'
    Invoke-TapLabel $Device 'Delete'
    Wait-NoCopilot -Device $Device
}

function Add-Result([string]$StepName, [string]$Text) {
    Add-SessionResult -Path $resultsFile -Step $StepName -Text $Text
    Write-Host "${StepName}: $Text"
}

function Invoke-Hook([string]$Device, [string]$Command, [hashtable]$With = @{}) {
    (& "$PSScriptRoot\hook.ps1" -Serial $Device -Command $Command @With) -join "`n"
}

# What the device's Kestrel says about the stand-in, the default profile (none, copilot or other) and the number of
# Copilot profiles.
function Get-StandIn([string]$Device) { ConvertFrom-StandInAnswer (Invoke-Hook $Device provider-standin) }

# Waits up to $Seconds until the default profile of the device's Kestrel is of $Kind. Returns whether it is.
function Wait-Profile([string]$Device, [string]$Kind, [int]$Seconds = 30) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        if ((Get-StandIn $Device)['profile'] -eq $Kind) { return $true }
        Start-Sleep -Seconds 2
    } while ($watch.Elapsed.TotalSeconds -lt $Seconds)
    $false
}

# Waits up to $Seconds until the device's Kestrel has no Copilot profile. Returns whether it has none.
function Wait-NoCopilot([string]$Device, [int]$Seconds = 30) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        if ((Get-StandIn $Device)['copilot'] -eq '0') { return $true }
        Start-Sleep -Seconds 2
    } while ($watch.Elapsed.TotalSeconds -lt $Seconds)
    $false
}

# What the script has still to put back on the devices. Each restore enters the list before its change and leaves it
# when it worked; one that failed keeps its reason for the message at the end, which says how to do it by hand.
$pending = [System.Collections.Generic.List[object]]::new()

function Register-Restore([string]$What, [string]$ByHand, [scriptblock]$Action) {
    $restore = [pscustomobject]@{ What = $What; ByHand = $ByHand; Action = $Action; Reason = 'the session ended before it' }
    $pending.Add($restore)
    $restore
}

# Runs a restore that is still pending, and keeps it pending, with the reason, when it fails.
function Complete-Restore($Restore) {
    if (-not $pending.Contains($Restore)) { return }
    try {
        & $Restore.Action | Out-Null
        $pending.Remove($Restore) | Out-Null
    } catch {
        $Restore.Reason = $_.Exception.Message
    }
}

# Runs the restores at the session's end, each in the finally block of the one before, so that a stop such as Ctrl+C
# during one of them still runs the others.
function Invoke-Restore([object[]]$Restores, [int]$Index = 0) {
    if ($Index -ge $Restores.Count) { return }
    try {
        Complete-Restore $Restores[$Index]
    } finally {
        Invoke-Restore $Restores ($Index + 1)
    }
}

# The restore that deletes the owner's Copilot profile on kestrel-api37 (design D20, step 8), registered before the
# emulator's sign-in and again by the end step, once: other work shares the emulator, and provider-check.ps1 or a later
# dry run there would spend the owner's AI Credits with it. Its action deletes every Copilot profile through the debug
# hook provider-delete, after it has started Kestrel, which gets no hook broadcast while it is stopped, and writes a
# result line; it fails when a Copilot profile is left, and then the result line says that it stayed.
$emulatorProfile = $null
function Register-EmulatorProfileRestore {
    if ($script:emulatorProfile) { return $script:emulatorProfile }
    $byHand = 'On kestrel-api37, the emulator''s window on this computer, use the mouse: open Kestrel''s settings, the page "Providers and profiles", click the Copilot profile, scroll to the end of its page, click "Delete profile" and then "Delete".'
    $script:emulatorProfile = Register-Restore 'the owner''s Copilot sign-in on kestrel-api37' $byHand {
        try {
            Open-Providers $EmulatorSerial
            Start-Sleep -Seconds 3
            $answer = ConvertFrom-DeleteAnswer (Invoke-Hook $EmulatorSerial provider-delete)
        } catch {
            Add-Result end 'kestrel-api37 still has the owner''s Copilot profile: Kestrel did not answer the hook provider-delete'
            throw
        }
        if ($answer['copilot'] -ne '0') {
            Add-Result end "kestrel-api37 still has the owner's Copilot profile: $($answer['failed']) deletions failed"
            throw 'a Copilot profile is left'
        }
        Add-Result end $(if ($answer['deleted'] -eq '0') { 'kestrel-api37 has no Copilot profile left' } else { 'the script deleted the owner''s Copilot profile on kestrel-api37' })
    }
    $script:emulatorProfile
}

# Opens Kestrel's page "Providers and profiles" on the device.
function Open-Providers([string]$Device) {
    # A start does not turn the screen on, and the taps need it.
    Invoke-SettingsCommand -Serial $Device -Command 'input keyevent KEYCODE_WAKEUP' | Out-Null
    Invoke-SettingsCommand -Serial $Device -Command 'am start -n dev.kestrel/.app.MainActivity --es dev.kestrel.app.extra.PAGE PROVIDERS' | Out-Null
}

# Kestrel's process ID on the device, or '' when it does not run.
function Get-KestrelPid([string]$Device) { ((Invoke-SettingsCommand -Serial $Device -Command 'pidof dev.kestrel || true') -join '').Trim() }

# Deletes an earlier run's results of the connected tests, such as the emulator's, so that they never count as the A71's.
function Clear-TestResults([string]$Folder) {
    if (Test-Path -LiteralPath $Folder) { Remove-Item -LiteralPath $Folder -Recurse -Force }
}

# The live cases on the device, with the stand-in in a dry run, and one retry with the controller's override per failure.
# Returns provider-check.ps1's results. provider-check.ps1 prints each line itself, so the lines go only to the file here.
function Invoke-Live([string]$StepName, [string]$Device, [string]$Case, [string]$Model) {
    if ($DryRun) {
        # Right after a new install, Android refused the stand-in's lookup of localhost (UnknownHostException on
        # kestrel-api37) until Kestrel's settings had been opened or a minute had passed, so the step opens them first
        # and tries the stand-in's start up to six times, 10 s apart.
        Open-Providers $Device
        for ($try = 1; ; $try++) {
            try {
                Invoke-Hook $Device provider-standin @{ On = 'true' } | Out-Null
                break
            } catch {
                if ($try -ge 6) { throw }
                Start-Sleep -Seconds 10
            }
        }
        if (-not $standInDevices.Contains($Device)) { $standInDevices.Add($Device) }
    }
    $with = @{ Serial = $Device; PassThru = $true }
    if ($Case) { $with.Case = $Case }
    if ($Model) { $with.Model = $Model }
    # A dry run's case passes only when its answer came from the stand-in, never from Copilot.
    $standIn = @{}
    if ($DryRun) { $standIn.RequireStandIn = $true }
    $results = @(& "$PSScriptRoot\provider-check.ps1" @with @standIn | Where-Object { $_ -is [pscustomobject] -and $_.PSObject.Properties['Line'] })
    foreach ($result in $results) {
        Add-SessionResult -Path $resultsFile -Step $StepName -Text $result.Line
        if ($result.Passed) { continue }
        $retry = Get-RetryOverride -Overrides $overrides -ErrorText $result.Answer['error']
        if (-not $retry) { continue }
        $again = @(& "$PSScriptRoot\provider-check.ps1" -Serial $Device -Case $result.Case -Model $result.Model -PassThru @retry @standIn) |
            Where-Object { $_ -is [pscustomobject] -and $_.PSObject.Properties['Line'] }
        # provider-check.ps1 printed the retry's line itself, so it goes only to the file here.
        foreach ($line in $again) { Add-SessionResult -Path $resultsFile -Step $StepName -Text "retry with $(@($retry.Keys) -join ' and '): $($line.Line)" }
    }
    if ($DryRun) { Invoke-Hook $Device provider-standin @{ On = 'false' } | Out-Null }
    $results
}

function Invoke-InstallStep {
    $script:gradleUsed = $true
    foreach ($device in @($EmulatorSerial, $Serial) | Select-Object -Unique) {
        & "$PSScriptRoot\install.ps1" -Serial $device
        if ($LASTEXITCODE -ne 0) { throw "scripts\install.ps1 failed on $device." }
        Add-Result install "Kestrel installed on $(if ($device -eq $EmulatorSerial) { 'kestrel-api37' } else { 'the A71' })"
    }
}

# After the owner's approval: waits until Kestrel's page shows the models (its title "Lightweight model", which only that
# page shows; "Save" is below the lists of models, out of view), up to 5 minutes, turns on "Make default", scrolls to
# "Save" and taps it itself, and checks that the default profile is a Copilot profile. On the A71 ($CustomTab), where Chrome is in front
# until the sign-in ends, it records whether Kestrel's page came back to the front by itself (design D20, step 5; map
# P10): the owner never taps Chrome's X, and when Kestrel's page has not come back within 10 minutes (the device code
# lives 15), so that the owner has time to sign in to GitHub first, the script brings it to the front itself and records
# "no".
function Complete-SignIn([string]$StepName, [string]$Device, [switch]$CustomTab) {
    $back = $false
    $limit = if ($CustomTab) { 10 } else { 5 }
    if ($CustomTab) {
        # Chrome takes the front a moment after "Open the page".
        foreach ($try in 1..10) {
            if ((Get-ResumedPackage @(Invoke-SettingsCommand -Serial $Device -Command 'dumpsys activity activities')) -ne 'dev.kestrel') { break }
            Start-Sleep -Seconds 2
        }
    }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        if ($CustomTab -and (Get-ResumedPackage @(Invoke-SettingsCommand -Serial $Device -Command 'dumpsys activity activities')) -eq 'dev.kestrel') { $back = $true }
        $saved = [int](ConvertFrom-HookPairs (Invoke-Hook $Device screen-has @{ Package = 'dev.kestrel'; Label = 'Lightweight model' }))['count'] -gt 0
        if (-not $saved) { Start-Sleep -Seconds 2 }
    } while (-not $saved -and $watch.Elapsed.TotalMinutes -lt $limit)
    if (-not $saved -and $CustomTab -and -not $back) {
        # The Custom Tab opened in Kestrel's task, so the page comes back only with the tab cleared off it.
        Invoke-SettingsCommand -Serial $Device -Command 'am start -n dev.kestrel/.app.MainActivity --activity-clear-top --activity-single-top' | Out-Null
        $saved = Wait-Label $Device 'Lightweight model' 120
    }
    if (-not $saved) {
        Add-Result $StepName 'failed: Kestrel''s page did not show the models after the code'
        return
    }
    # Kestrel makes a new profile the default only when none is the default yet, and the switch starts off; its text is
    # not the switch, so the script taps the switch's own element.
    # The switch is below the two lists of models, one row per model of the account, so the script scrolls down until it
    # shows (off or already on) or "Save", the page's last element, shows without it.
    $made = $false
    for ($try = 0; $try -le 15 -and -not $made; $try++) {
        $switch = Find-KestrelElement $Device @{ Label = 'Make default'; State = 'unchecked' }
        $centre = Get-BoundsCentre $switch['bounds']
        if ([int]$switch['count'] -gt 0 -and $centre) {
            Assert-KestrelInFront $Device $switch 'Make default'
            Invoke-SettingsCommand -Serial $Device -Command "input tap $centre" | Out-Null
            Start-Sleep -Seconds 1
            $made = $true
        } elseif ([int](ConvertFrom-HookPairs (Invoke-Hook $Device screen-has @{ Package = 'dev.kestrel'; Label = 'Make default'; State = 'checked' }))['count'] -gt 0) {
            $made = $true
        } elseif ([int](ConvertFrom-HookPairs (Invoke-Hook $Device screen-has @{ Package = 'dev.kestrel'; Label = 'Save' }))['count'] -gt 0) {
            break
        } else {
            Invoke-Hook $Device scroll @{ Direction = 'down' } | Out-Null
            Start-Sleep -Seconds 1
        }
    }
    if (-not $made) { Add-Result $StepName 'the switch "Make default" did not show, so the new profile is the default only when no other profile is' }
    Invoke-TapLabel $Device 'Save'
    $found = Wait-Profile -Device $Device -Kind copilot
    $text = if ($found) { 'the default profile is a Copilot profile' } else { 'failed: Kestrel has no default Copilot profile' }
    if ($CustomTab) { $text += "; Kestrel's page came back by itself: $(if ($back) { 'yes' } else { 'no' })" }
    Add-Result $StepName $text
}

function Invoke-EmulatorSignInStep {
    # Before the owner signs in, so that every way in which the session ends deletes the profile again.
    if (-not $DryRun) { Register-EmulatorProfileRestore | Out-Null }
    if ((Request-OwnerItem 'Item 1 of 2, about 2 minutes: the Copilot sign-in on kestrel-api37. When you press Enter, the script taps through Kestrel''s page on the emulator to its sign-in, puts the code that the page shows on this computer''s clipboard (afterwards it puts your text back; a picture or a file there is lost), and opens github.com/login/device in this computer''s browser. There, sign in to GitHub if it asks, paste the code with Ctrl+V and approve. The emulator''s page then shows "Checking' + [char]0x2026 + '" and the lists of models, and the script saves the profile itself. After the two sign-ins, the live cases spend about 4 to 8 of your GitHub AI Credits. Press Enter to start, or s to skip.' 'go') -eq 'skip') {
        Add-Result emulator-signin 'skipped, because it needs the owner''s GitHub account'
        return
    }
    Open-CopilotSignIn $EmulatorSerial
    if ($DryRun) {
        Add-Result emulator-signin 'the add flow reached "Sign in", which a dry run never taps'
        Invoke-SettingsCommand -Serial $EmulatorSerial -Command 'input keyevent KEYCODE_BACK' | Out-Null
        return
    }
    $code = $null
    foreach ($try in 1..15) {
        $code = Get-DeviceCode ((& "$PSScriptRoot\hook.ps1" -Serial $EmulatorSerial -Command snapshot) -join "`n")
        if ($code) { break }
        Start-Sleep -Seconds 2
    }
    if (-not $code) {
        Add-Result emulator-signin 'failed: Kestrel''s page showed no code within 30 s'
        return
    }
    # This computer's clipboard holds the code until the sign-in ends, and then the owner's text again (a picture or a
    # file on it is not kept, which the prompt says).
    # In the script's scope, because after an error or Ctrl+C the restore runs at the session's end, after this function.
    $script:pcClip = Get-Clipboard -Raw
    $pcRestore = Register-Restore 'this computer''s clipboard' 'Copy something of your own again on this computer; its clipboard may hold the sign-in code.' {
        # A clipboard without text gets a space, so that the code does not stay on it.
        if ($null -eq $script:pcClip) { Set-Clipboard -Value ' ' } else { Set-Clipboard -Value $script:pcClip }
        $script:pcClip = $null
    }
    Set-Clipboard -Value $code
    $code = $null
    Start-Process 'https://github.com/login/device'
    Write-Host 'The code is on this computer''s clipboard, and the browser shows github.com/login/device: paste it there with Ctrl+V and approve.'
    Complete-SignIn emulator-signin $EmulatorSerial
    Complete-Restore $pcRestore
}

function Invoke-A71SignInStep {
    if ((Request-OwnerItem 'Item 2 of 2, about 3 minutes: the Copilot sign-in on the A71, which the script unlocks itself. When you press Enter, the script taps through Kestrel''s page to its sign-in and taps "Copy" and "Open the page": Chrome opens github.com/login/device inside Kestrel. Sign in to GitHub if it asks, touch and hold the code field, tap "Paste", and tap Continue and Authorize. A few seconds after GitHub accepts, Kestrel''s page should come back by itself and show "Checking' + [char]0x2026 + '" and the lists of models. If Chrome offers to save your password, answer it yourself; the script never taps Chrome''s buttons. Do not tap Chrome''s X. You have 10 minutes from when Chrome opens: if Kestrel''s page has not come back by then, the script brings it back. The script saves the profile itself. Press Enter to start, or s to skip.' 'go') -eq 'skip') {
        Add-Result a71-signin 'skipped, because it needs the owner''s GitHub account'
        return
    }
    if ($DryRun) {
        Add-Result a71-signin 'skipped in a dry run: kestrel-api37 already showed the add flow'
        return
    }
    if (-not (Unlock-A71)) {
        Add-Result a71-signin 'skipped: the A71 is locked, and the session could not unlock it'
        return
    }
    Open-CopilotSignIn $Serial
    # "Copy" puts the code on the A71's clipboard, so the owner's clip is saved first, while Kestrel's page is in front,
    # where Android lets Kestrel read it, and put back after the sign-in (hook clipboard, plan 4D interfaces, section 6).
    $clipRestore = Register-Restore 'the A71''s clipboard' 'Copy something of your own again on the A71; its clipboard holds the sign-in code.' {
        if ((ConvertFrom-HookPairs (Invoke-Hook $Serial clipboard @{ Restore = $true }))['restored'] -eq 'failed') {
            Add-Result a71-signin 'Android refused to put the A71''s clipboard back, so it holds the sign-in code'
            throw 'Android refused to put the saved clip back'
        }
    }
    $clip = (ConvertFrom-HookPairs (Invoke-Hook $Serial clipboard @{ Save = $true }))['clip']
    if ($clip -eq 'unknown') {
        Add-Result a71-signin 'Kestrel could not read the A71''s clipboard (it was empty, or Android refused), so it saved nothing, and the clipboard keeps the sign-in code afterwards'
    } elseif ($clip -eq 'other') {
        Add-Result a71-signin 'the A71''s clipboard held a picture or a file, which Android may refuse to put back'
    }
    # The code comes from GitHub a few seconds after "Sign in", and "Copy" shows only with it.
    if (-not (Wait-Label $Serial 'Copy' 30)) {
        Add-Result a71-signin 'failed: Kestrel''s page showed no code within 30 s'
        Complete-Restore $clipRestore
        return
    }
    Invoke-TapLabel $Serial 'Copy'
    Invoke-TapLabel $Serial 'Open the page'
    Complete-SignIn a71-signin $Serial -CustomTab
    Complete-Restore $clipRestore
    try { [console]::Beep(880, 300) } catch { Write-Verbose 'This console cannot beep.' }
    Write-Host 'You may leave now. The rest of the session runs without you for about 35 minutes; please do not touch the A71 or the emulator.'
}

# The live cases need a default Copilot profile, which a real session has only after the owner's sign-in, and the owner:
# an unattended run skips them.
function Invoke-LiveStep([string]$StepName, [string]$Device) {
    $reason = Get-LiveSkipReason $DryRun $Unattended $(if (-not $DryRun -and -not $Unattended) { (Get-StandIn $Device)['profile'] })
    if ($reason) {
        Add-Result $StepName $reason
        return
    }
    Invoke-Live $StepName $Device | Out-Null
}

function Invoke-EmulatorLiveStep { Invoke-LiveStep emulator-live $EmulatorSerial }

function Invoke-A71LiveStep { Invoke-LiveStep a71-live $Serial }

# "Stay awake" on the device until the session ends, because the taps and the Custom Tab tests need an awake screen, and
# the builds and the owner's approvals take longer than the screen timeout; 'true' holds also an emulator, which runs on
# AC power. The setting comes back at the end.
function Enable-StayAwake([string]$Device) {
    if ($stayOn.Contains($Device)) { return }
    $stayOn[$Device] = ((Invoke-SettingsCommand -Serial $Device -Command 'settings get global stay_on_while_plugged_in') -join '').Trim()
    Invoke-SettingsCommand -Serial $Device -Command 'svc power stayon true' | Out-Null
}

function Invoke-A71TestsStep {
    $script:gradleUsed = $true
    Enable-StayAwake $Serial
    # The Custom Tab tests need an unlocked phone.
    if (-not (Unlock-A71)) {
        Add-Result a71-tests 'skipped: the A71 is locked, and the session could not unlock it'
        return
    }
    $strongBox = "$(& $adb -s $Serial shell pm has-feature android.hardware.strongbox_keystore)".Trim()
    Add-Result a71-tests "StrongBox: $(if ($strongBox -eq 'true') { 'yes, the credentials key takes the StrongBox path' } else { 'no, the credentials key takes the path without StrongBox' })"
    $folder = Join-Path $PSScriptRoot '..\core\privacy\build\outputs\androidTest-results\connected'
    Clear-TestResults $folder
    & "$PSScriptRoot\connected-test.ps1" -Serial $Serial ':core:privacy:connectedDebugAndroidTest' '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.privacy.CredentialStoreTest,dev.kestrel.privacy.LoopbackServerTest' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
    Add-Result a71-tests "CredentialStoreTest: $(if (Test-ClassPassed $folder CredentialStoreTest) { 'passed' } else { 'failed' }); LoopbackServerTest: $(if (Test-ClassPassed $folder LoopbackServerTest) { 'passed' } else { 'failed' })"
    $results = Join-Path $PSScriptRoot '..\app\build\outputs\androidTest-results\connected'
    Clear-TestResults $results
    & "$PSScriptRoot\connected-test.ps1" -Serial $Serial ':app:connectedDebugAndroidTest' '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.providers.CustomTabSignInTest' '-Pandroid.testInstrumentationRunnerArguments.kestrelRedirectCheck=true' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
    $failed = @(Get-UnpassedTests $results | Where-Object { $_ -like 'CustomTabSignInTest#*' })
    Add-Result a71-tests "CustomTabSignInTest: $(if ($failed) { $failed -join ', ' } else { if (Test-ClassPassed $results CustomTabSignInTest) { 'passed, with the redirect to 127.0.0.1 and to localhost' } else { 'not run' } })"
    # A prompt of Chrome's about the local network, which nobody answers here, would hold the redirect, so the redirect
    # case's result answers prototype P5's question (map P08).
    $redirect = @(Get-UnpassedTests $results | Where-Object { $_ -like 'CustomTabSignInTest#*redirect*' })
    Add-Result a71-tests "a prompt of Chrome's about the local network: $(if ($failed) { if ($redirect) { 'not known, because the redirect case did not pass' } else { 'none held the redirect' } } elseif (Test-ClassPassed $results CustomTabSignInTest) { 'none held the redirect' } else { 'not known' })"
    # On kestrel-api37 the Custom Tab tests left Chrome's "isn't responding" dialog, which a force-stop did not close and
    # which would stay over every app during the unattended sessions.
    $anr = Close-AnrDialog -Serial $Serial
    if ($anr) { Add-Result a71-tests $anr }
    # connected-test.ps1 ends with 'am force-stop', and a stopped app gets no hook broadcast, so Kestrel starts again.
    Open-Providers $Serial
    Start-Sleep -Seconds 3
}

function Invoke-A71RestartStep {
    $reason = Get-LiveSkipReason $DryRun $Unattended $(if (-not $DryRun -and -not $Unattended) { (Get-StandIn $Serial)['profile'] }) 'the A71'
    if ($reason) {
        Add-Result a71-restart $reason
        return
    }
    $before = Get-KestrelPid $Serial
    Invoke-SettingsCommand -Serial $Serial -Command 'am crash dev.kestrel' | Out-Null
    Start-Sleep -Seconds 3
    Open-Providers $Serial
    Start-Sleep -Seconds 3
    $after = Get-KestrelPid $Serial
    if (-not $before -or -not $after -or $before -eq $after) {
        Add-Result a71-restart 'failed: Kestrel''s process did not end and start again'
        return
    }
    Add-Result a71-restart 'Kestrel''s process ended with am crash and started again with another process ID'
    # As in the other live steps, Claude Haiku 5.5 stands in when the subscription does not offer GPT-6 Luna.
    $first = @(Invoke-Live a71-restart $Serial 'text' 'GPT-6 Luna')
    if ($first.Count -gt 0 -and $first[0].Answer['error'] -eq 'the-subscription-does-not-offer-this-model') {
        Invoke-Live a71-restart $Serial 'text' 'Claude Haiku 5.5' | Out-Null
    }
}

function Invoke-EndStep {
    # Registered again here, once, so that a run of the end step alone, as with -Step end, deletes the emulator's profile
    # too; in a dry run it shows that the hook answers.
    $restore = Register-EmulatorProfileRestore
    if ($DryRun) {
        $state = Get-StandIn $EmulatorSerial
        Add-Result end "the stand-in is $($state['standin']), the default profile is $($state['profile']), and kestrel-api37 has $($state['copilot']) Copilot profiles"
        if ($state['standin'] -ne 'off') { throw 'The stand-in still runs.' }
        return
    }
    # Through Kestrel's profile page, as the owner did; the hook provider-delete stays the restore when that fails.
    if ((Get-StandIn $EmulatorSerial)['copilot'] -ne '0') {
        $gone = try { Remove-CopilotProfile $EmulatorSerial } catch { $false }
        Add-Result end $(if ($gone) { 'the script deleted the emulator''s Copilot profile on its profile page' } else { 'the profile page did not delete the emulator''s Copilot profile; the hook deletes it' })
    }
    Complete-Restore $restore
    # The owner decided on 2026-11-20 that the A71 keeps its Copilot profile, for sub-project 5's agent.
    Add-Result end $(if ((Get-StandIn $Serial)['copilot'] -eq '0') { 'the A71 has no Copilot profile' } else { 'the A71 keeps its Copilot profile, as the owner decided' })
}

$plan = @(
    @{ Name = 'install'; Minutes = 5; Title = 'install Kestrel on kestrel-api37 and the A71'; Run = 'Invoke-InstallStep' }
    @{ Name = 'emulator-signin'; Minutes = 3; Title = 'the Copilot sign-in on kestrel-api37, which the owner approves on github.com'; Run = 'Invoke-EmulatorSignInStep' }
    @{ Name = 'a71-signin'; Minutes = 3; Title = 'the Copilot sign-in on the A71 in Chrome inside Kestrel, which the owner approves'; Run = 'Invoke-A71SignInStep' }
    @{ Name = 'emulator-live'; Minutes = 8; Title = 'every live case on kestrel-api37, which spends about 2 to 4 of the owner''s GitHub AI Credits'; Run = 'Invoke-EmulatorLiveStep' }
    @{ Name = 'a71-tests'; Minutes = 12; Title = 'the device tests and the redirect check on the A71'; Run = 'Invoke-A71TestsStep' }
    @{ Name = 'a71-live'; Minutes = 8; Title = 'every live case on the A71, which spends about 2 to 4 AI Credits'; Run = 'Invoke-A71LiveStep' }
    @{ Name = 'a71-restart'; Minutes = 3; Title = 'Kestrel''s process on the A71 ends and starts again, and one text case runs, which spends far less than 1 AI Credit'; Run = 'Invoke-A71RestartStep' }
    @{ Name = 'end'; Minutes = 2; Title = 'the emulator''s Copilot profile goes, and the A71''s unless the owner keeps it'; Run = 'Invoke-EndStep' }
)

$completed = $false
$stopped = $false
try {
    # The emulator's lock first, before any command reaches a device: other work shares the emulator, so the real session
    # runs inside the lock (process rule 9); the dry run runs inside the controller's lock and never touches it.
    if (-not $DryRun -and (Test-Path -LiteralPath (Split-Path $lockFile))) {
        if ($LockHeld) {
            if (-not (Test-Path -LiteralPath $lockFile)) { throw '-LockHeld says that the controller holds the emulator''s lock, but .superpowers\sdd\emulator.lock does not exist.' }
        } else {
            $deadline = (Get-Date).AddMinutes(60)
            while (-not (Enter-EmulatorLock -Path $lockFile -Owner 'a71-providers session')) {
                if ((Get-Date) -gt $deadline) { throw 'The emulator lock still exists after 60 minutes.' }
                Write-Host 'Another task holds the emulator''s lock (.superpowers\sdd\emulator.lock); the session tries again in 2 minutes. If the controller holds it for this session, stop with Ctrl+C and start again with -LockHeld.'
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
    # Without -DryRun, the A71's steps must not run on the emulator.
    if (-not $DryRun -and $KestrelTestDevices[$Serial].Pattern -ne 'SM-A715F*') { throw "$Serial is not the Galaxy A71." }
    $how = @(if ($DryRun) { 'as a dry run on kestrel-api37' }; if ($Unattended) { 'without the owner' }) -join ', '
    Add-Result session "started$(if ($how) { " $how" })"
    foreach ($device in @($EmulatorSerial, $Serial) | Select-Object -Unique) { Enable-StayAwake $device }
    foreach ($entry in $plan) {
        if ($Step -and $entry.Name -notin $Step) { continue }
        Write-Host "Step $($entry.Name), about $($entry.Minutes) minutes: $($entry.Title)."
        try {
            & $entry.Run
        } catch {
            if ($_.Exception.Message -eq 'The owner stopped the session.') { throw }
            # The scripts' and hooks' errors name only steps, files and classes, never a prompt, a reply or a key; only their
            # first line goes into the results, which hold one line per result.
            Add-Result $entry.Name "failed: $(("$($_.Exception.Message)" -split "`r?`n")[0])"
        }
    }
    $completed = $true
} catch {
    $stopped = $true
    Add-Result session "stopped: $($_.Exception.Message)"
} finally {
    # The owner's Copilot profile on kestrel-api37 goes, also after q, Ctrl+C, an error or a skipped step; each restore
    # writes its own result line.
    Invoke-Restore @($pending)
    # The stand-in of a dry run ends, also after a failure or Ctrl+C.
    foreach ($device in $standInDevices) {
        try { Invoke-Hook $device provider-standin @{ On = 'false' } | Out-Null } catch { Write-Warning "Could not end the stand-in on $device. Run: scripts\hook.ps1 -Serial $device provider-standin -On false" }
    }
    # "Stay awake" as it was before the session, on each device.
    foreach ($device in @($stayOn.Keys)) {
        $command = if ($stayOn[$device] -in 'null', '') { 'settings delete global stay_on_while_plugged_in' } else { "settings put global stay_on_while_plugged_in $($stayOn[$device])" }
        try { Invoke-SettingsCommand -Serial $device -Command $command | Out-Null } catch { Write-Warning "Could not put back ""Stay awake"" on $device. Run: & '$adb' -s $device shell $command" }
    }
    if ($lockTaken) { Remove-Item -LiteralPath $lockFile -ErrorAction SilentlyContinue }
    if (-not ($completed -or $stopped)) { Add-SessionResult -Path $resultsFile -Step session -Text 'stopped, as by Ctrl+C' }
    if ($gradleUsed) { & "$PSScriptRoot\gradle.ps1" --stop | Out-Null }
    if (Test-Path -LiteralPath $resultsFile) { Write-Host "The results are in $((Resolve-Path -LiteralPath $resultsFile).Path)." }
    # The last message: what the script could not put back, and how to do it by hand.
    foreach ($restore in $pending) { Write-Warning "Could not put back $($restore.What) ($($restore.Reason)). $($restore.ByHand)" }
    if (-not ($completed -or $stopped)) {
        if (@(Get-PSCallStack).Count -eq 1) { $Host.SetShouldExit(1) }
        exit 1
    }
}
if ($stopped) { exit 1 }
