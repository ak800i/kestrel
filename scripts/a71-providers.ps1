# The owner's session of sub-project 4 (design D20) on kestrel-api37 and the Galaxy A71: the Copilot sign-in on each,
# the live cases of scripts\provider-check.ps1 on each, the providers' device tests and the Custom Tab's redirect check
# with the A71's own Chrome (prototype P5's q1 and q3), the credentials after a restart of Kestrel's process, and the end,
# where the emulator's Copilot profile goes. Each prompt says what the owner does, on which device, and what the screen
# shows before and after; the script then checks the device's state through the hooks instead of trusting the Enter, and
# every time that it reports was measured on the device. It asks before each step, and before it reads each device's
# model. The steps and their estimates, which add up to 44 minutes (about 45 with the owner's answers), of which about 8
# need the owner's hands:
#   install         installs Kestrel's debug build on kestrel-api37 and on the A71 (5 minutes, without the owner)
#   emulator-signin the Copilot sign-in on kestrel-api37, which the owner approves on github.com on his computer or
#                   phone (3 minutes with the owner)
#   emulator-live   every live case on kestrel-api37 (8 minutes, without the owner's hands)
#   a71-tests       the credential store, the loopback server and the Custom Tab on the A71, with the redirect check
#                   through postman-echo.com in the A71's Chrome; whether the A71 has StrongBox (12 minutes, without the
#                   owner's hands, but he watches the A71's screen for a prompt)
#   a71-signin      the Copilot sign-in on the A71 in a Custom Tab, which the owner approves in it, and whether Kestrel's
#                   page came back to the front by itself (3 minutes with the owner)
#   a71-live        every live case on the A71 (8 minutes)
#   a71-restart     Kestrel's process ended with 'am crash', started again, and one live case, so that the stored
#                   credential decrypts after a restart (3 minutes)
#   end             the owner deletes the emulator's Copilot profile, and decides whether the A71 keeps its profile
#                   (2 minutes with the owner)
# The live cases spend the owner's GitHub AI Credits from his Copilot subscription: about 2 to 4 credits for each of
# emulator-live and a71-live, and far less than 1 for a71-restart's one case, so about 4 to 8 in all, a little more with
# retries; their prompts say so before they run. A failed case is recorded with its error kind, and gets one retry when
# corpus\owner\provider-overrides.json, which the controller writes before the session, names an override for that kind
# ({"<kind>": {"Headers": "a=b", "MessagesPath": "/x"}}); a failure that the retry does not fix is a finding for plan 4C's
# fix wave. The results go into corpus\owner\providers-<time>.txt, which git ignores; neither it nor the console ever hold
# a prompt, a reply, a code, a token or a key. The script refuses every device but the A71 and kestrel-api37, and -Serial
# must be the A71. While the Custom Tab tests run, the A71's screen stays on while it is plugged in ("Stay awake"), and
# the setting is put back at the end. Every way that a real session ends (its end, q, Ctrl+C, an error, a skipped step or
# a skipped deletion) deletes the owner's Copilot profile on kestrel-api37 through the debug hook provider-delete, which
# the script registers before the emulator's sign-in, and writes a result line that says whether it did; when it cannot,
# its last message says how to delete the profile by hand, and the result line says that it stayed. The A71 keeps its
# profile only after the owner's explicit yes. A real session runs inside the emulator's lock
# .superpowers\sdd\emulator.lock (process rule 9), because other work shares the emulator, and it handles the lock before
# it sends any command to a device: with -LockHeld the controller holds the lock for the session, and the script only
# checks that its file exists; without it, the script takes the lock itself with a create that fails while the file
# exists, waits up to 60 minutes for another task's lock, and deletes its own at the end. -DryRun runs every step on
# kestrel-api37 without the owner, inside the controller's lock, which it never touches: it skips the sign-ins, the
# waits for the owner and the owner's deletion, runs the live cases against the hook provider-standin's Copilot stand-in
# on the device and fails a case whose answer does not come from the stand-in, runs the redirect check with the
# emulator's Chrome, and checks at the end that the stand-in is off and reports the default profile and the number of
# Copilot profiles. -Step runs only the named steps, also as one text separated by commas.
# It needs PowerShell 7.3 or newer, as scripts\hook.ps1 does. After Ctrl+C, it ends with exit code 1.
# Example: scripts\a71-providers.ps1 -Serial R58N12345AB -EmulatorSerial emulator-5554 -LockHeld
#Requires -Version 7.3
param(
    [string]$Serial,
    [string]$EmulatorSerial = 'emulator-5554',
    [switch]$DryRun,
    [switch]$LockHeld,
    [string[]]$Step
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
. "$PSScriptRoot\lib\Session.ps1"
Use-KestrelToolchain

$stepNames = @('install', 'emulator-signin', 'emulator-live', 'a71-tests', 'a71-signin', 'a71-live', 'a71-restart', 'end')

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
# "Stay awake" of the A71 as it was before a71-tests changed it, or $null when it was not changed.
$stayOn = $null
$lockFile = Join-Path $PSScriptRoot '..\.superpowers\sdd\emulator.lock'
$lockTaken = $false

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

# Asks the owner a yes-or-no question until the answer is y, yes, n or no (Read-YesNoAnswer), and returns 'y' or 'n'. In
# a dry run it answers $DryRunAnswer itself.
function Read-YesNo([string]$Prompt, [string]$DryRunAnswer) {
    if ($DryRun) {
        Write-Host "[dry run] $Prompt"
        return $DryRunAnswer
    }
    Read-YesNoAnswer $Prompt
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

function Invoke-SignIn([string]$StepName, [string]$Device, [string]$Prompt, [switch]$CustomTab) {
    if ($DryRun) {
        Add-Result $StepName 'skipped in a dry run: it needs the owner''s GitHub account'
        return
    }
    Open-Providers $Device
    if ((Request-Owner $Prompt) -eq 'skip') {
        Add-Result $StepName 'skipped by the owner'
        return
    }
    $found = Wait-Profile -Device $Device -Kind copilot
    $text = if ($found) { 'the default profile is a Copilot profile' } else { 'failed: Kestrel has no default Copilot profile' }
    if ($CustomTab) {
        # Design D20, step 5: after a sign-in in a Custom Tab, SignInService brings Kestrel's page back (design D10). Only
        # this sign-in checks it on the A71's Android and Chrome, and only the owner sees it.
        $back = Read-YesNo 'Did Kestrel''s page "Providers and profiles" come back to the front by itself after you approved on GitHub, without a tap on Chrome''s X? Type y or n and press Enter.' 'n'
        $text += "; Kestrel's page came back by itself: $(if ($back -eq 'y') { 'yes' } else { 'no' })"
    }
    Add-Result $StepName $text
}

function Invoke-EmulatorSignInStep {
    # Before the owner signs in, so that every way in which the session ends deletes the profile again.
    if (-not $DryRun) { Register-EmulatorProfileRestore | Out-Null }
    Invoke-SignIn emulator-signin $EmulatorSerial ('Now on kestrel-api37, the emulator''s window on this computer, which you use with the mouse: Kestrel''s page "Providers and profiles" is open in it. Click "Add a profile", type Copilot in the search field, click "Copilot", then "GitHub Copilot", then "Sign in". The page shows a code of 8 letters and digits with a hyphen, such as WDJB-MJHT, in large type, with a countdown. On this computer or on your phone, open github.com/login/device in a browser, sign in to GitHub if it asks, type that code and approve. A few seconds after GitHub accepts, the emulator''s page shows "Checking' + [char]0x2026 + '" and then two lists of models, with a check mark at the lightweight and at the versatile model (GPT-6 Luna and GPT-6.1 Terra when your subscription offers them). Change nothing, scroll to the end of the page and click "Save"; the page then shows the list of profiles with "Copilot (default)". Press Enter when it does, or s to skip.')
}

function Invoke-A71SignInStep {
    Invoke-SignIn a71-signin $Serial -CustomTab ('Now on the A71: unlock it if it is locked. Kestrel''s page "Providers and profiles" is open on it. Tap "Add a profile", type Copilot, tap "Copilot", then "GitHub Copilot", then "Sign in". The page shows a code in large type with a countdown. Tap "Copy", then "Open the page": Chrome opens github.com/login/device inside Kestrel, with a close button (X) at its top left. Sign in to GitHub if it asks, touch and hold the code field, tap "Paste", and approve. Watch the screen now: a few seconds after GitHub accepts, Kestrel''s page should come back to the front by itself and show "Checking' + [char]0x2026 + '" and then the two lists of models; the script asks you next whether it did. If Chrome stays in front, tap its X. Scroll to the end of Kestrel''s page and tap "Save"; the page shows the list of profiles with "Copilot (default)". Press Enter when it does, or s to skip.')
}

function Invoke-EmulatorLiveStep { Invoke-Live emulator-live $EmulatorSerial | Out-Null }

function Invoke-A71LiveStep { Invoke-Live a71-live $Serial | Out-Null }

function Invoke-A71TestsStep {
    $script:gradleUsed = $true
    # The Custom Tab tests need an unlocked, awake screen, and the builds before them take longer than the screen timeout.
    if ($null -eq $script:stayOn) {
        $script:stayOn = ((Invoke-SettingsCommand -Serial $Serial -Command 'settings get global stay_on_while_plugged_in') -join '').Trim()
        Invoke-SettingsCommand -Serial $Serial -Command 'svc power stayon usb' | Out-Null
    }
    if ((Request-Owner 'Unlock the A71 now and leave it on its home screen. Its screen stays on while it is plugged in, until the session ends. For the next few minutes Gradle builds and installs the tests, and the A71 shows nothing new; do not touch it until this window asks you. Press Enter when the A71 shows its home screen.' 'go') -eq 'skip') {
        Add-Result a71-tests 'skipped by the owner'
        return
    }
    $strongBox = "$(& $adb -s $Serial shell pm has-feature android.hardware.strongbox_keystore)".Trim()
    Add-Result a71-tests "StrongBox: $(if ($strongBox -eq 'true') { 'yes, the credentials key takes the StrongBox path' } else { 'no, the credentials key takes the path without StrongBox' })"
    $folder = Join-Path $PSScriptRoot '..\core\privacy\build\outputs\androidTest-results\connected'
    Clear-TestResults $folder
    & "$PSScriptRoot\connected-test.ps1" -Serial $Serial ':core:privacy:connectedDebugAndroidTest' '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.privacy.CredentialStoreTest,dev.kestrel.privacy.LoopbackServerTest' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
    Add-Result a71-tests "CredentialStoreTest: $(if (Test-ClassPassed $folder CredentialStoreTest) { 'passed' } else { 'failed' }); LoopbackServerTest: $(if (Test-ClassPassed $folder LoopbackServerTest) { 'passed' } else { 'failed' })"
    if ((Request-Owner 'Watch the A71''s screen now. Kestrel''s settings open, and Chrome opens three times inside Kestrel: once at a page of Kestrel''s own, and twice at postman-echo.com, which sends Chrome on to Kestrel at once; each time the page says "Kestrel received the sign-in. You can close this tab." and closes after a few seconds. Do not touch the phone. If Chrome asks whether it may find or connect to devices on your local network, do not answer; remember it, because this window asks you afterwards. It takes about 3 minutes. At the end the tests close Kestrel and Chrome, so the A71 shows its home screen for a moment, and then the script opens Kestrel''s page "Providers and profiles" on it. Press Enter to start.' 'go') -eq 'skip') {
        Add-Result a71-tests 'the Custom Tab tests: skipped by the owner'
        return
    }
    $results = Join-Path $PSScriptRoot '..\app\build\outputs\androidTest-results\connected'
    Clear-TestResults $results
    & "$PSScriptRoot\connected-test.ps1" -Serial $Serial ':app:connectedDebugAndroidTest' '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.providers.CustomTabSignInTest' '-Pandroid.testInstrumentationRunnerArguments.kestrelRedirectCheck=true' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
    $failed = @(Get-UnpassedTests $results | Where-Object { $_ -like 'CustomTabSignInTest#*' })
    Add-Result a71-tests "CustomTabSignInTest: $(if ($failed) { $failed -join ', ' } else { if (Test-ClassPassed $results CustomTabSignInTest) { 'passed, with the redirect to 127.0.0.1 and to localhost' } else { 'not run' } })"
    # connected-test.ps1 ends with 'am force-stop', and a stopped app gets no hook broadcast, so Kestrel starts again, as the
    # prompt before the tests says.
    Open-Providers $Serial
    $prompt = Read-YesNo 'Kestrel''s page "Providers and profiles" is now open on the A71. Did Chrome ask during the tests whether it may find or connect to devices on your local network? Type y or n and press Enter.' 'n'
    Add-Result a71-tests "Chrome asked about the local network: $(if ($prompt -eq 'y') { 'yes' } else { 'no' })"
    Start-Sleep -Seconds 3
}

function Invoke-A71RestartStep {
    if (-not $DryRun -and (Get-StandIn $Serial)['profile'] -ne 'copilot') {
        Add-Result a71-restart 'skipped: the A71 has no default Copilot profile'
        return
    }
    if ((Request-Owner 'On the A71: Kestrel''s process ends now and starts again. For a moment the A71 may show "Kestrel has stopped" or nothing new; then Kestrel''s page "Providers and profiles" opens. Do not touch the phone. Then one text case runs with the stored sign-in, which spends far less than 1 of your GitHub AI Credits, and the page shows nothing new. Press Enter to start.' 'go') -eq 'skip') {
        Add-Result a71-restart 'skipped by the owner'
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
    Open-Providers $EmulatorSerial
    if ((Request-Owner 'Now on kestrel-api37, the emulator''s window on this computer, with the mouse: Kestrel''s page "Providers and profiles" is open in it. Click the profile "Copilot (default)", scroll to the end of its page, click "Delete profile" and then "Delete" in the dialog. The page shows the list of profiles again, without the Copilot profile. Press Enter when it does. If you type s, the script deletes the profile itself through its debug hook.' 'go') -eq 'skip') {
        Add-Result end 'the owner skipped the deletion on kestrel-api37; the script deletes the profile itself'
    } else {
        Add-Result end $(if (Wait-NoCopilot -Device $EmulatorSerial) { 'the owner deleted the emulator''s Copilot profile' } else { 'failed: kestrel-api37 still has a Copilot profile; the script deletes it itself' })
    }
    Complete-Restore $restore
    $keep = Read-YesNo 'Should the A71 keep its Copilot profile? Sub-project 5, the agent, will use the A71''s default profile for its tasks. If you keep it, the profile and its GitHub sign-in stay on the A71, encrypted, until you delete it in Kestrel''s settings; if not, you delete it now. Type y to keep it, or n to delete it, and press Enter.' 'y'
    if ($keep -eq 'y') {
        Add-Result end 'the A71 keeps its Copilot profile, as the owner chose'
        return
    }
    Open-Providers $Serial
    if ((Request-Owner 'Now on the A71: unlock it if it is locked. Kestrel''s page "Providers and profiles" is open on it. Tap the profile "Copilot (default)", scroll to the end of its page, tap "Delete profile" and then "Delete" in the dialog. The page shows the list of profiles again, without the Copilot profile. Press Enter when it does, or s to keep the profile after all.' 'go') -eq 'skip') {
        Add-Result end 'the owner skipped the deletion on the A71, so the A71 keeps its Copilot profile'
        return
    }
    Add-Result end $(if (Wait-NoCopilot -Device $Serial) { 'the A71''s Copilot profile is deleted' } else { 'failed: the A71 still has a Copilot profile' })
}

$plan = @(
    @{ Name = 'install'; Minutes = 5; Title = 'install Kestrel on kestrel-api37 and the A71; Kestrel closes on both if it is open, and the A71 shows nothing else; you may leave'; Run = 'Invoke-InstallStep' }
    @{ Name = 'emulator-signin'; Minutes = 3; Title = 'you sign in to Copilot in Kestrel on kestrel-api37, the emulator''s window on this computer, with the mouse, and approve a code on github.com in a browser on this computer or your phone; the next prompt names each click'; Run = 'Invoke-EmulatorSignInStep' }
    @{ Name = 'emulator-live'; Minutes = 8; Title = 'every live case on kestrel-api37, which spends about 2 to 4 of your GitHub AI Credits; no hands; the screens show nothing new, except that the line "This month" of the Copilot profile rises as the cases run'; Run = 'Invoke-EmulatorLiveStep' }
    @{ Name = 'a71-tests'; Minutes = 12; Title = 'the device tests and the redirect check on the A71: you unlock it, then watch while Kestrel''s settings and Chrome open by themselves, and answer one question; at the end the A71 shows Kestrel''s page "Providers and profiles"'; Run = 'Invoke-A71TestsStep' }
    @{ Name = 'a71-signin'; Minutes = 3; Title = 'you sign in to Copilot in Kestrel on the A71, approve the code in Chrome inside Kestrel, and say whether Kestrel''s page came back to the front by itself'; Run = 'Invoke-A71SignInStep' }
    @{ Name = 'a71-live'; Minutes = 8; Title = 'every live case on the A71, which spends about 2 to 4 of your GitHub AI Credits; no hands; the screens show nothing new, except that the line "This month" of the Copilot profile rises as the cases run'; Run = 'Invoke-A71LiveStep' }
    @{ Name = 'a71-restart'; Minutes = 3; Title = 'Kestrel''s process on the A71 ends and starts again by itself, and then one text case runs with the stored sign-in, which spends far less than 1 AI Credit; do not touch the A71, which may show "Kestrel has stopped" for a moment and then Kestrel''s page "Providers and profiles"'; Run = 'Invoke-A71RestartStep' }
    @{ Name = 'end'; Minutes = 2; Title = 'you delete the Copilot profile on kestrel-api37 with the mouse, or the script deletes it, and then you decide whether the A71 keeps its Copilot profile'; Run = 'Invoke-EndStep' }
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
        if ((Request-Owner "The session first reads the model of the device $device, to check that it is kestrel-api37 or the Galaxy A71. The device shows nothing new. Press Enter to go ahead, or q to stop." 'go') -ne 'go') { throw 'The owner stopped the session.' }
        # Refuses every device but the A71 and kestrel-api37; the daily phone would need -AllowDailyPhone, which this
        # script never passes.
        Assert-TestDevice -Serial $device
    }
    if ($KestrelTestDevices[$EmulatorSerial].Pattern -ne 'kestrel-api37') { throw "$EmulatorSerial is not kestrel-api37." }
    # Without -DryRun, the A71's steps must not run on the emulator.
    if (-not $DryRun -and $KestrelTestDevices[$Serial].Pattern -ne 'SM-A715F*') { throw "$Serial is not the Galaxy A71." }
    Add-Result session "started$(if ($DryRun) { ' as a dry run on kestrel-api37' })"
    foreach ($entry in $plan) {
        if ($Step -and $entry.Name -notin $Step) { continue }
        if ((Request-Owner "Step $($entry.Name), about $($entry.Minutes) minutes: $($entry.Title). Press Enter to run it, s to skip it, or q to stop the session." 'go') -eq 'skip') {
            Add-Result $entry.Name 'skipped by the owner'
            continue
        }
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
    # "Stay awake" as it was before a71-tests.
    if ($null -ne $stayOn) {
        $command = if ($stayOn -in 'null', '') { 'settings delete global stay_on_while_plugged_in' } else { "settings put global stay_on_while_plugged_in $stayOn" }
        try { Invoke-SettingsCommand -Serial $Serial -Command $command | Out-Null } catch { Write-Warning "Could not put back ""Stay awake"" on $Serial. Run: & '$adb' -s $Serial shell $command" }
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
