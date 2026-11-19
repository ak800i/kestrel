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
#   a71-signin      the Copilot sign-in on the A71 in a Custom Tab, which the owner approves in it (3 minutes with the owner)
#   a71-live        every live case on the A71 (8 minutes)
#   a71-restart     Kestrel's process ended with 'am crash', started again, and one live case, so that the stored
#                   credential decrypts after a restart (3 minutes)
#   end             the owner deletes the emulator's Copilot profile, and decides whether the A71 keeps its profile
#                   (2 minutes with the owner)
# A failed case is recorded with its error kind, and gets one retry when corpus\owner\provider-overrides.json, which the
# controller writes before the session, names an override for that kind ({"<kind>": {"Headers": "a=b", "MessagesPath":
# "/x"}}); a failure that the retry does not fix is a finding for plan 4C's fix wave. The results go into
# corpus\owner\providers-<time>.txt, which git ignores; neither it nor the console ever hold a prompt, a reply, a code, a
# token or a key. The script refuses every device but the A71 and kestrel-api37, and -Serial must be the A71. While the
# Custom Tab tests run, the A71's screen stays on while it is plugged in ("Stay awake"), and the setting is put back at
# the end. A real session takes the emulator's lock .superpowers\sdd\emulator.lock (process rule 8a), because other work
# shares the emulator, and deletes it at the end. -DryRun runs every step on kestrel-api37 without the owner, inside the
# lock that the plan's step holds: it skips the sign-ins and the waits for the owner, runs the live cases against the hook
# provider-standin's Copilot stand-in on the device, runs the redirect check with the emulator's Chrome, and checks at the
# end that the stand-in is off and reports the default profile. -Step runs only the named steps, also as one text
# separated by commas.
# It needs PowerShell 7.3 or newer, as scripts\hook.ps1 does. After Ctrl+C, it ends with exit code 1.
# Example: scripts\a71-providers.ps1 -Serial R58N12345AB -EmulatorSerial emulator-5554
#Requires -Version 7.3
param(
    [string]$Serial,
    [string]$EmulatorSerial = 'emulator-5554',
    [switch]$DryRun,
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

# The answer of provider-standin, such as 'standin=off profile=copilot', as an ordered table.
function ConvertFrom-StandInAnswer([string]$Text) {
    $pairs = ConvertFrom-HookPairs $Text
    if (-not $pairs.Contains('standin') -or -not $pairs.Contains('profile')) { throw "Not an answer of provider-standin: '$Text'" }
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

function Read-OwnerAnswer([string]$Prompt, [string]$DryRunAnswer) {
    if ($DryRun) {
        Write-Host "[dry run] $Prompt"
        return $DryRunAnswer
    }
    (Read-Host $Prompt).Trim().ToLowerInvariant()
}

function Add-Result([string]$StepName, [string]$Text) {
    Add-SessionResult -Path $resultsFile -Step $StepName -Text $Text
    Write-Host "${StepName}: $Text"
}

function Invoke-Hook([string]$Device, [string]$Command, [hashtable]$With = @{}) {
    (& "$PSScriptRoot\hook.ps1" -Serial $Device -Command $Command @With) -join "`n"
}

# What the device's Kestrel says about the stand-in and the default profile (none, copilot or other).
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
        Invoke-Hook $Device provider-standin @{ On = 'true' } | Out-Null
        if (-not $standInDevices.Contains($Device)) { $standInDevices.Add($Device) }
    }
    $with = @{ Serial = $Device; PassThru = $true }
    if ($Case) { $with.Case = $Case }
    if ($Model) { $with.Model = $Model }
    $results = @(& "$PSScriptRoot\provider-check.ps1" @with | Where-Object { $_ -is [pscustomobject] -and $_.PSObject.Properties['Line'] })
    foreach ($result in $results) {
        Add-SessionResult -Path $resultsFile -Step $StepName -Text $result.Line
        if ($result.Passed) { continue }
        $retry = Get-RetryOverride -Overrides $overrides -ErrorText $result.Answer['error']
        if (-not $retry) { continue }
        $again = @(& "$PSScriptRoot\provider-check.ps1" -Serial $Device -Case $result.Case -Model $result.Model -PassThru @retry) |
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

function Invoke-SignIn([string]$StepName, [string]$Device, [string]$Prompt) {
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
    Add-Result $StepName $(if ($found) { 'the default profile is a Copilot profile' } else { 'failed: Kestrel has no default Copilot profile' })
}

function Invoke-EmulatorSignInStep {
    Invoke-SignIn emulator-signin $EmulatorSerial ('On kestrel-api37''s screen, Kestrel''s page "Providers and profiles" is open. Tap "Add a profile", type Copilot in the search field, tap "Copilot", then "GitHub Copilot", then "Sign in". The page shows a code of 8 letters and digits with a hyphen, such as WDJB-MJHT, in large type, with a countdown. On your computer or phone, open github.com/login/device, sign in to GitHub if it asks, type that code and approve. A few seconds after GitHub accepts, the emulator''s page shows "Checking' + [char]0x2026 + '" and then the two lists of models, with a check mark at the lightweight and at the versatile model (GPT-6 Luna and GPT-6.1 Terra when the subscription offers them). Change nothing, scroll to the end of the page and tap "Save"; the page shows the list of profiles with "Copilot (default)". Press Enter when it does, or s to skip.')
}

function Invoke-A71SignInStep {
    Invoke-SignIn a71-signin $Serial ('Now on the A71: unlock it. Kestrel''s page "Providers and profiles" is open. Tap "Add a profile", type Copilot, tap "Copilot", then "GitHub Copilot", then "Sign in". The page shows a code in large type with a countdown. Tap "Copy", then "Open the page": Chrome opens github.com/login/device inside Kestrel, with a close button (X) at its top left. Sign in to GitHub if it asks, paste the code into the field and approve. A few seconds after GitHub accepts, Kestrel''s page comes back by itself and shows "Checking' + [char]0x2026 + '", then the two lists of models; if Chrome stays in front, tap its X. Scroll to the end of the page and tap "Save"; the page shows "Copilot (default)". Press Enter when it does, or s to skip.')
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
    if ((Request-Owner 'Unlock the A71 now and leave it on its home screen. Its screen stays on while it is plugged in, until the session ends. For the next few minutes Gradle builds and installs the tests, and the A71 shows nothing new. Press Enter when the A71 shows its home screen.' 'go') -eq 'skip') {
        Add-Result a71-tests 'skipped by the owner'
        return
    }
    $strongBox = "$(& $adb -s $Serial shell pm has-feature android.hardware.strongbox_keystore)".Trim()
    Add-Result a71-tests "StrongBox: $(if ($strongBox -eq 'true') { 'yes, the credentials key takes the StrongBox path' } else { 'no, the credentials key takes the path without StrongBox' })"
    $folder = Join-Path $PSScriptRoot '..\core\privacy\build\outputs\androidTest-results\connected'
    Clear-TestResults $folder
    & "$PSScriptRoot\connected-test.ps1" -Serial $Serial ':core:privacy:connectedDebugAndroidTest' '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.privacy.CredentialStoreTest,dev.kestrel.privacy.LoopbackServerTest' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
    Add-Result a71-tests "CredentialStoreTest: $(if (Test-ClassPassed $folder CredentialStoreTest) { 'passed' } else { 'failed' }); LoopbackServerTest: $(if (Test-ClassPassed $folder LoopbackServerTest) { 'passed' } else { 'failed' })"
    if ((Request-Owner 'Watch the A71''s screen now. Kestrel''s settings open, and Chrome opens three times inside Kestrel: once at a page of Kestrel''s own, and twice at postman-echo.com, which sends Chrome on to Kestrel at once; each time the page says "Kestrel received the sign-in. You can close this tab." and closes after a few seconds. Do not touch the phone. If Chrome asks whether it may find or connect to devices on your local network, do not answer; remember it. It takes about 3 minutes. Press Enter to start.' 'go') -eq 'skip') {
        Add-Result a71-tests 'the Custom Tab tests: skipped by the owner'
        return
    }
    $results = Join-Path $PSScriptRoot '..\app\build\outputs\androidTest-results\connected'
    Clear-TestResults $results
    & "$PSScriptRoot\connected-test.ps1" -Serial $Serial ':app:connectedDebugAndroidTest' '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.providers.CustomTabSignInTest' '-Pandroid.testInstrumentationRunnerArguments.kestrelRedirectCheck=true' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
    $failed = @(Get-UnpassedTests $results | Where-Object { $_ -like 'CustomTabSignInTest#*' })
    Add-Result a71-tests "CustomTabSignInTest: $(if ($failed) { $failed -join ', ' } else { if (Test-ClassPassed $results CustomTabSignInTest) { 'passed, with the redirect to 127.0.0.1 and to localhost' } else { 'not run' } })"
    $prompt = Read-OwnerAnswer 'Did Chrome ask about devices on your local network? Type y or n and press Enter.' 'n'
    Add-Result a71-tests "Chrome asked about the local network: $(if ($prompt -eq 'y') { 'yes' } else { 'no' })"
    # connected-test.ps1 ends with 'am force-stop', and a stopped app gets no hook broadcast, so Kestrel starts again.
    Open-Providers $Serial
    Start-Sleep -Seconds 3
}

function Invoke-A71RestartStep {
    if (-not $DryRun -and (Get-StandIn $Serial)['profile'] -ne 'copilot') {
        Add-Result a71-restart 'skipped: the A71 has no default Copilot profile'
        return
    }
    if ((Request-Owner 'On the A71: Kestrel''s process ends now and starts again. For a moment the A71 may show "Kestrel has stopped" or nothing new; then Kestrel''s page "Providers and profiles" opens. Do not touch the phone. Press Enter to start.' 'go') -eq 'skip') {
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
    if ($DryRun) {
        $state = Get-StandIn $EmulatorSerial
        Add-Result end "the stand-in is $($state['standin']), and the default profile is $($state['profile'])"
        if ($state['standin'] -ne 'off') { throw 'The stand-in still runs.' }
        return
    }
    Open-Providers $EmulatorSerial
    if ((Request-Owner 'On kestrel-api37: the page "Providers and profiles" is open. Tap the profile "Copilot (default)", scroll to the end, tap "Delete profile" and then "Delete". The page shows the list again, with no profile. Press Enter when it does.' 'go') -ne 'skip') {
        Add-Result end $(if (Wait-Profile -Device $EmulatorSerial -Kind none) { 'the emulator''s Copilot profile is deleted' } else { 'failed: kestrel-api37 still has a default profile' })
    }
    $keep = Read-OwnerAnswer 'Should the A71 keep its Copilot profile for sub-project 5? Type y to keep it or n to delete it, and press Enter.' 'y'
    if ($keep -eq 'y') {
        Add-Result end 'the A71 keeps its Copilot profile'
        return
    }
    Open-Providers $Serial
    Request-Owner 'On the A71: unlock it. Kestrel''s page "Providers and profiles" is open. Tap the profile "Copilot (default)", scroll to the end, tap "Delete profile" and then "Delete". The page shows the list again, with no profile. Press Enter when it does.' 'go' | Out-Null
    Add-Result end $(if (Wait-Profile -Device $Serial -Kind none) { 'the A71''s Copilot profile is deleted' } else { 'failed: the A71 still has a default profile' })
}

$plan = @(
    @{ Name = 'install'; Minutes = 5; Title = 'install Kestrel on kestrel-api37 and the A71; Kestrel closes on both if it is open, and the A71 shows nothing else; you may leave'; Run = 'Invoke-InstallStep' }
    @{ Name = 'emulator-signin'; Minutes = 3; Title = 'the Copilot sign-in on kestrel-api37, approved on github.com'; Run = 'Invoke-EmulatorSignInStep' }
    @{ Name = 'emulator-live'; Minutes = 8; Title = 'every live case on kestrel-api37; no hands; the screens show nothing new, except that the line "This month" of the Copilot profile rises as the cases run'; Run = 'Invoke-EmulatorLiveStep' }
    @{ Name = 'a71-tests'; Minutes = 12; Title = 'the device tests and the redirect check on the A71; you watch its screen'; Run = 'Invoke-A71TestsStep' }
    @{ Name = 'a71-signin'; Minutes = 3; Title = 'the Copilot sign-in on the A71 in Chrome inside Kestrel'; Run = 'Invoke-A71SignInStep' }
    @{ Name = 'a71-live'; Minutes = 8; Title = 'every live case on the A71; no hands; the screens show nothing new, except that the line "This month" of the Copilot profile rises as the cases run'; Run = 'Invoke-A71LiveStep' }
    @{ Name = 'a71-restart'; Minutes = 3; Title = 'the stored credential after a restart of Kestrel on the A71'; Run = 'Invoke-A71RestartStep' }
    @{ Name = 'end'; Minutes = 2; Title = 'the emulator''s Copilot profile goes; you decide about the A71''s'; Run = 'Invoke-EndStep' }
)

$completed = $false
$stopped = $false
try {
    foreach ($device in @($EmulatorSerial, $Serial) | Select-Object -Unique) {
        if ((Request-Owner "The session first reads the model of the device $device, to check that it is kestrel-api37 or the Galaxy A71. The device shows nothing new. Press Enter to go ahead, or q to stop." 'go') -ne 'go') { throw 'The owner stopped the session.' }
        # Refuses every device but the A71 and kestrel-api37; the daily phone would need -AllowDailyPhone, which this
        # script never passes.
        Assert-TestDevice -Serial $device
    }
    if ($KestrelTestDevices[$EmulatorSerial].Pattern -ne 'kestrel-api37') { throw "$EmulatorSerial is not kestrel-api37." }
    # Without -DryRun, the A71's steps must not run on the emulator.
    if (-not $DryRun -and $KestrelTestDevices[$Serial].Pattern -ne 'SM-A715F*') { throw "$Serial is not the Galaxy A71." }
    # Other work shares the emulator, so the real session holds its lock (process rule 8a); the dry run runs inside the
    # plan step's lock.
    if (-not $DryRun -and (Test-Path -LiteralPath (Split-Path $lockFile))) {
        $deadline = (Get-Date).AddMinutes(60)
        while (Test-Path -LiteralPath $lockFile) {
            if ((Get-Date) -gt $deadline) { throw 'The emulator lock still exists after 60 minutes.' }
            Write-Host 'Another task uses the emulator; the session waits for it and checks again in 2 minutes.'
            Start-Sleep -Seconds 120
        }
        Set-Content -LiteralPath $lockFile -Value 'a71-providers session'
        $lockTaken = $true
    }
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
            # The scripts' and hooks' errors name only steps, files and classes, never a prompt, a reply or a key.
            Add-Result $entry.Name "failed: $($_.Exception.Message)"
        }
    }
    $completed = $true
} catch {
    $stopped = $true
    Add-Result session "stopped: $($_.Exception.Message)"
} finally {
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
    if (-not ($completed -or $stopped)) {
        if (@(Get-PSCallStack).Count -eq 1) { $Host.SetShouldExit(1) }
        exit 1
    }
}
if ($stopped) { exit 1 }
