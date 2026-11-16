# Spike S4 (design spec, section 19): how much of a web page does Kestrel read in Chrome, Firefox and Samsung Internet,
# and can it type into the page's text fields? The script serves scripts\spike-s4\page.html from this computer with
# the JDK's jwebserver, opens it on the device through adb reverse, and compares Kestrel's snapshot with the page.
# It needs Kestrel's debug build (scripts\install.ps1) and the browsers. It taps through first-run screens whose
# buttons it knows, and otherwise asks you to finish them.
# Afterwards, also when it fails or is interrupted, it puts back the accessibility settings, removes the adb reverse rule,
# stops jwebserver and stops each browser that it checked. If it cannot put something back, also because Ctrl+C
# interrupted it while it put things back, it says at the end what is left and how to put it back by hand. Then, and
# after Ctrl+C, it ends with exit code 1, as it does when Kestrel's scroll fails in a browser. With -Verbose, it names
# each thing that it puts back.
# Example: scripts\spike-s4.ps1 -Serial emulator-5554 -Browser chrome, firefox
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [ValidateSet('chrome', 'firefox', 'samsung')][string[]]$Browser = @('chrome', 'firefox')
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial
$adb = Get-Adb
$port = 8765
$url = "http://localhost:$port/page.html"
$packages = @{ chrome = 'com.android.chrome'; firefox = 'org.mozilla.firefox'; samsung = 'com.sec.android.app.sbrowser' }
$service = 'dev.kestrel/dev.kestrel.app.KestrelAccessibilityService'
$heading = 'Kestrel browser check'
# Buttons of the browsers' first-run screens and prompts, in the order in which the script prefers them. Cancel declines
# Android's question whether the browser should become the default one.
$skipButtons = @('Stay signed out', 'Use without an account', 'No thanks', 'No, thanks', 'Not now', 'Cancel', 'Skip',
    'Got it', 'Maybe later', 'Not interested', 'Start browsing', 'Accept & continue', 'Continue')

function Invoke-Shell([string]$Command) { (& $adb -s $Serial shell $Command) -join "`n" }
function Invoke-Hook { (& "$PSScriptRoot\hook.ps1" -Serial $Serial @args) -join "`n" }

function Get-Snapshot {
    # Without -Out, hook.ps1 returns the JSON, so the screen's text is not written to this computer's disk.
    Invoke-Hook snapshot | ConvertFrom-Json
}

function Get-Names($Element) {
    @($Element.label, $Element.text, $Element.contentDescription, $Element.hint, $Element.supplementalDescription) | Where-Object { $_ }
}

function Test-Name($Element, [string]$Name) { (Get-Names $Element) -contains $Name }

# Opens the page and waits until Kestrel reads its heading. Each opening gets its own address, so that a browser does not
# restore what an earlier run typed into the page.
function Open-Page([string]$Package) {
    $address = "$($url)?run=$(Get-Random)"
    Invoke-Shell "am start -W -a android.intent.action.VIEW -d $address $Package" | Out-Null
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Seconds 2
        $snapshot = Get-Snapshot
        if ($snapshot.elements | Where-Object { Test-Name $_ $heading }) { return $snapshot }
        $skip = $null
        foreach ($text in $skipButtons) {
            $skip = $snapshot.elements | Where-Object { $_.clickable -and (Test-Name $_ $text) } | Select-Object -First 1
            if ($skip) { break }
        }
        if ($skip) {
            Invoke-Hook tap -Key $skip.key | Out-Null
        } else {
            # After its first-run screens, a browser may show its start page instead of the page.
            Invoke-Shell "am start -W -a android.intent.action.VIEW -d $address $Package" | Out-Null
        }
    }
    Read-Host "Finish the browser's first-run screens, open $url on the device, then press Enter" | Out-Null
    Get-Snapshot
}

# Each check finds an element on the page by its name, and by what Kestrel should be able to do with it.
$checks = [ordered]@{
    'heading'                           = @{ Name = $heading; Needs = '' }
    'paragraph'                         = @{ Name = 'This page checks what Kestrel can read in a browser.'; Needs = '' }
    'link'                              = @{ Name = 'Next page'; Needs = 'clickable' }
    'button'                            = @{ Name = 'Send message'; Needs = 'clickable' }
    'icon button with aria-label'       = @{ Name = 'Search'; Needs = 'clickable' }
    'text field with a label'           = @{ Name = 'Name'; Needs = 'editable' }
    'text field with a placeholder'     = @{ Name = 'Email address'; Needs = 'editable' }
    'checkbox with a label'             = @{ Name = 'I agree'; Needs = 'checkable' }
    'drop-down list'                    = @{ Name = 'Country'; Needs = 'clickable' }
    'image with alt text'               = @{ Name = 'A kestrel in flight'; Needs = '' }
    'element with role=button'          = @{ Name = 'Custom button'; Needs = 'clickable' }
    'element with only a click handler' = @{ Name = 'Clickable div'; Needs = 'clickable' }
    'text drawn on a canvas'            = @{ Name = 'Canvas text'; Needs = '' }
    'list item below the fold'          = @{ Name = 'Item 30'; Needs = '' }
    'editable div'                      = @{ Name = 'Message box'; Needs = 'editable' }
    'text area'                         = @{ Name = 'Notes'; Needs = 'editable' }
}
$fields = @('Name', 'Email address', 'Message box', 'Notes')

# What the script has still to put back on the device and this computer. Each restore enters this list before the change
# that it undoes is made, and it leaves the list when it has worked. At the end, the script names the restores that are
# left and how to do them by hand. The browsers keep the tabs and the history of the page, and the choices of their
# first-run screens, because only clearing a browser's data would remove them.
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
        Write-Verbose "Trying to $($restore.What)"
        & $restore.Action | Out-Null
        $pending.Remove($restore) | Out-Null
    } catch {
        $restore.Reason = $_.Exception.Message
    } finally {
        Invoke-Restore $Steps ($Index + 1)
    }
}

# Runs a command on the device and fails with the device's complaint when the command fails.
function Invoke-Checked([string]$Command) { Invoke-SettingsCommand -Serial $Serial -Command $Command }

# Returns a PowerShell command that runs the given adb arguments, for the warnings.
function Format-AdbCommand([string]$Arguments) { "Run: & '$($adb -replace "'", "''")' -s $Serial $Arguments" }

# Returns a PowerShell command that runs the given command on the device, for the warnings.
function Format-DeviceCommand([string]$Command) { Format-AdbCommand "shell '$($Command -replace "'", "''")'" }

$savedAccessibility = Get-AccessibilitySettings -Serial $Serial
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
$server = $null
$reverseAdded = $false
$scrollFailed = @()
$completed = $false
$failed = $false
$restoresAtEnd = @(
    Register-Restore "put back the accessibility settings of $Serial" (Format-DeviceCommand $accessibilityByHand) {
        if (-not (Restore-AccessibilitySettings -Serial $Serial -Saved $savedAccessibility)) { throw 'see the warning above' }
    }
    Register-Restore "remove the adb reverse rule tcp:$port of $Serial" (Format-AdbCommand "reverse --remove tcp:$port") {
        if ($reverseAdded) {
            $rules = & $adb -s $Serial reverse --list
            if ($LASTEXITCODE -ne 0) { throw 'adb reverse --list failed' }
            if ($rules -match "\btcp:$port\b") {
                & $adb -s $Serial reverse --remove "tcp:$port" 2>$null | Out-Null
                if ($LASTEXITCODE -ne 0) { throw "adb reverse --remove tcp:$port failed" }
            }
        }
    }
    ($serverRestore = Register-Restore 'stop jwebserver on this computer' 'Stop jwebserver.exe in the Task Manager.' {
            if ($server -and -not $server.HasExited) {
                $server.Kill()
                if (-not $server.WaitForExit(10000)) { throw 'it still runs' }
            }
        })
)
try {
    $folder = Join-Path $PSScriptRoot 'spike-s4'
    # Start-Process does not quote the arguments, so a folder with a space needs quotes.
    $server = Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin\jwebserver.exe') -PassThru -WindowStyle Hidden `
        -ArgumentList '-p', $port, '-b', '127.0.0.1', '-d', "`"$folder`""
    $serverRestore.ByHand = "Stop jwebserver.exe with the process ID $($server.Id) in the Task Manager."
    Start-Sleep -Seconds 1
    if ($server.HasExited) { throw "jwebserver ended at once with exit code $($server.ExitCode). Is port $port in use?" }
    $reverseAdded = $true
    & $adb -s $Serial reverse "tcp:$port" "tcp:$port" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "adb reverse tcp:$port failed." }
    # When its accessibility service connects, Kestrel would bring back a listening mode that it stored, and this script
    # never turns it off. The hook's off stops that and stores Off.
    Invoke-Hook voice -Send off | Out-Null
    Invoke-Checked "settings put secure enabled_accessibility_services $service" | Out-Null
    Invoke-Checked 'settings put secure accessibility_enabled 1' | Out-Null
    for ($i = 0; (Invoke-Hook state) -notlike 'screen=on*'; $i++) {
        if ($i -ge 30) { throw "Kestrel's accessibility service did not connect within 30 s." }
        Start-Sleep -Seconds 1
    }
    foreach ($name in $Browser) {
        $package = $packages[$name]
        if (@((Invoke-Shell "pm list packages $package") -split "`n" | ForEach-Object { $_.Trim() }) -notcontains "package:$package") {
            Write-Host "$name ($package) is not installed."
            continue
        }
        Write-Host "Browser $name"
        # The script stops the browser after its checks, also one that was running before.
        $restores = @(
            Register-Restore "stop $name on $Serial" (Format-DeviceCommand "am force-stop $package") {
                Invoke-Checked "am force-stop $package"
            }
        )
        try {
            $snapshot = Open-Page $package
            foreach ($check in $checks.GetEnumerator()) {
                $named = @($snapshot.elements | Where-Object { Test-Name $_ $check.Value.Name })
                $result = if (-not $named) { 'no' }
                    elseif (-not $check.Value.Needs -or ($named | Where-Object { $_.($check.Value.Needs) })) { 'yes' }
                    else { "named, not $($check.Value.Needs)" }
                '  {0,-36} {1}' -f $check.Key, $result
            }
            foreach ($field in $fields) {
                # Each field gets the page in a new tab, because the keyboard of the previous field can hide the
                # next one. A browser that has just started may not take typed text yet, so the browser keeps
                # running.
                Open-Page $package | Out-Null
                Start-Sleep -Seconds 2
                $snapshot = Get-Snapshot
                $text = "Kestrel $($fields.IndexOf($field) + 1)"
                $target = $snapshot.elements | Where-Object { $_.editable -and (Test-Name $_ $field) } | Select-Object -First 1
                $answer = if (-not $target) { 'no editable field' } else {
                    try { Invoke-Hook type -Key $target.key -Text $text } catch { $_.Exception.Message }
                }
                Start-Sleep -Seconds 1
                $after = (Get-Snapshot).elements | Where-Object { $_.editable -and $_.text -eq $text } | Select-Object -First 1
                $shows = if ($after) { "shows the text, named '$($after.label)'" } else { 'does not show the text' }
                '  {0,-36} {1}; the field {2}' -f "type into '$field'", $answer, $shows
            }
            # Content below the screen is not in the snapshot until Kestrel scrolls to it. The page opens again
            # first, so that the keyboard from the typing checks does not cover it.
            Invoke-Checked "am force-stop $package" | Out-Null
            Open-Page $package | Out-Null
            $answers = @()
            $scrollError = $null
            while (-not $scrollError -and $answers.Count -lt 8 -and -not ((Get-Snapshot).elements | Where-Object { Test-Name $_ 'Item 30' })) {
                try { $answers += Invoke-Hook scroll -Direction down } catch { $scrollError = $_.Exception.Message }
            }
            if ($scrollError) {
                # The browsers after this one are still checked, and the script ends with exit code 1.
                $scrollFailed += $name
                '  {0,-36} {1}' -f 'list item after scrolling down', "the scroll failed after $($answers.Count) scrolls: $scrollError"
            } else {
                $found = [bool]((Get-Snapshot).elements | Where-Object { Test-Name $_ 'Item 30' })
                $how = ($answers | Sort-Object -Unique) -join ', '
                '  {0,-36} {1}' -f 'list item after scrolling down', "$(if ($found) { 'yes' } else { 'no' }), after $($answers.Count) scrolls ($how)"
            }
        } finally {
            Invoke-Restore $restores
        }
    }
    $completed = $true
} catch {
    $failed = $true
    throw
} finally {
    try {
        Invoke-Restore $restoresAtEnd
    } finally {
        foreach ($restore in $pending) {
            Write-Warning "Could not $($restore.What) ($($restore.Reason)). $($restore.ByHand)"
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
    Write-Warning 'The device or this computer is not as it was before. The warnings above say how to put it back.'
    exit 1
}
if ($scrollFailed) {
    Write-Warning "Kestrel's scroll failed in $($scrollFailed -join ', ')."
    exit 1
}
