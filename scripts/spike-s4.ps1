# Spike S4 (design spec, section 19): how much of a web page does Kestrel read in Chrome, Firefox and Samsung Internet,
# and can it type into the page's text fields? The script serves scripts\spike-s4\page.html from this computer with
# the JDK's jwebserver, opens it on the device through adb reverse, and compares Kestrel's snapshot with the page.
# It needs Kestrel's debug build (scripts\install.ps1) and the browsers. It taps through first-run screens whose
# buttons it knows, and otherwise asks you to finish them.
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
    $file = Join-Path $env:TEMP "kestrel-spike-s4-$PID.json"
    try {
        Invoke-Hook snapshot -Out $file | Out-Null
        Get-Content $file -Raw | ConvertFrom-Json
    } finally {
        Remove-Item $file -ErrorAction SilentlyContinue
    }
}

function Get-Names($Element) { @($Element.label, $Element.text, $Element.contentDescription, $Element.hint) | Where-Object { $_ } }

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

$savedAccessibility = Get-AccessibilitySettings -Serial $Serial
$server = Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin\jwebserver.exe') -PassThru -WindowStyle Hidden `
    -ArgumentList '-p', $port, '-b', '127.0.0.1', '-d', (Join-Path $PSScriptRoot 'spike-s4')
try {
    & $adb -s $Serial reverse "tcp:$port" "tcp:$port" | Out-Null
    Invoke-Shell "settings put secure enabled_accessibility_services $service" | Out-Null
    Invoke-Shell 'settings put secure accessibility_enabled 1' | Out-Null
    for ($i = 0; (Invoke-Hook state) -notlike 'screen=on*'; $i++) {
        if ($i -ge 30) { throw "Kestrel's accessibility service did not connect within 30 s." }
        Start-Sleep -Seconds 1
    }
    foreach ($name in $Browser) {
        $package = $packages[$name]
        if (-not ((Invoke-Shell "pm list packages $package") -match "package:$([regex]::Escape($package))$")) {
            Write-Host "$name ($package) is not installed."
            continue
        }
        Write-Host "Browser $name"
        $snapshot = Open-Page $package
        foreach ($check in $checks.GetEnumerator()) {
            $named = @($snapshot.elements | Where-Object { Test-Name $_ $check.Value.Name })
            $result = if (-not $named) { 'no' }
                elseif (-not $check.Value.Needs -or ($named | Where-Object { $_.($check.Value.Needs) })) { 'yes' }
                else { "named, not $($check.Value.Needs)" }
            '  {0,-36} {1}' -f $check.Key, $result
        }
        foreach ($field in $fields) {
            # Each field gets the page in a new tab, because the keyboard of the previous field can hide the next one.
            # A browser that has just started may not take typed text yet, so the browser keeps running.
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
        # Content below the screen is not in the snapshot until Kestrel scrolls to it. The page opens again first, so that
        # the keyboard from the typing checks does not cover it.
        Invoke-Shell "am force-stop $package" | Out-Null
        Open-Page $package | Out-Null
        $answers = @()
        while ($answers.Count -lt 8 -and -not ((Get-Snapshot).elements | Where-Object { Test-Name $_ 'Item 30' })) {
            $answers += Invoke-Hook scroll -Direction down
        }
        $found = [bool]((Get-Snapshot).elements | Where-Object { Test-Name $_ 'Item 30' })
        $how = ($answers | Sort-Object -Unique) -join ', '
        '  {0,-36} {1}' -f 'list item after scrolling down', "$(if ($found) { 'yes' } else { 'no' }), after $($answers.Count) scrolls ($how)"
        Invoke-Shell "am force-stop $package" | Out-Null
    }
} finally {
    $null = Restore-AccessibilitySettings -Serial $Serial -Saved $savedAccessibility
    & $adb -s $Serial reverse --remove "tcp:$port" 2>$null | Out-Null
    Stop-Process -Id $server.Id -ErrorAction SilentlyContinue
}
