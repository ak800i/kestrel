# Spike S1 (design spec, section 19): can Kestrel start its listening service and record when the accessibility service,
# a notification, the Quick Settings tile or a restart starts it, also while a call rings on the lock screen? Can it read
# the location while another app is in front? It needs Kestrel's debug build (scripts\install.ps1), and it prints what
# Kestrel recorded in its results: whether each step worked, never the audio or the place. The location comes from a
# test provider with a made-up place, which replaces the fused provider while the scenario runs.
# On a phone, the ringing scenario asks you to call the phone, to end the call and, if it has a PIN, to unlock it.
# Example: scripts\spike-s1.ps1 -Serial emulator-5554 -Scenario accessibility, no-service
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [ValidateSet('accessibility', 'no-service', 'notification', 'tile', 'ringing', 'location', 'boot')]
    [string[]]$Scenario = @('accessibility', 'no-service', 'notification', 'tile', 'ringing', 'location', 'boot')
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial
$adb = Get-Adb
$service = 'dev.kestrel/dev.kestrel.app.KestrelAccessibilityService'
$tile = 'dev.kestrel/dev.kestrel.app.debug.spike.SpikeTileService'
$isEmulator = $Serial -like 'emulator-*'
$caller = '0641112233'

function Invoke-Shell([string]$Command) { (& $adb -s $Serial shell $Command) -join "`n" }
function Invoke-Hook { (& "$PSScriptRoot\hook.ps1" -Serial $Serial @args) -join "`n" }

function Set-KestrelService([bool]$On) {
    if (-not $On) {
        Invoke-Shell 'settings delete secure enabled_accessibility_services' | Out-Null
        Start-Sleep -Seconds 2
        return
    }
    Invoke-Shell "settings put secure enabled_accessibility_services $service" | Out-Null
    Invoke-Shell 'settings put secure accessibility_enabled 1' | Out-Null
    for ($i = 0; $i -lt 30; $i++) {
        if ((Invoke-Hook state) -like 'screen=on*') { return }
        Start-Sleep -Seconds 1
    }
    throw "Kestrel's accessibility service did not connect within 30 s."
}

# Waits for the newest result line that matches the pattern.
function Wait-Result([string]$Pattern, [int]$TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $lines = @((Invoke-Hook spike-results) -split "`n" | Where-Object { $_ -match $Pattern })
        if ($lines) { return $lines[-1] }
        Start-Sleep -Seconds 2
    }
    "no result within $TimeoutSeconds s"
}

function Get-ScreenLayout {
    Invoke-Shell 'uiautomator dump /sdcard/spike-ui.xml' | Out-Null
    [xml]$ui = (& $adb -s $Serial exec-out cat /sdcard/spike-ui.xml) -join "`n"
    Invoke-Shell 'rm -f /sdcard/spike-ui.xml' | Out-Null
    $ui
}

# Taps the first element whose text is the given text, such as a notification's action.
function Invoke-TapText([string]$Text) {
    $node = (Get-ScreenLayout).SelectNodes('//node') | Where-Object { $_.text -ieq $Text } | Select-Object -First 1
    if (-not $node) { throw "Nothing on the screen shows '$Text'." }
    Invoke-TapNode $node
}

# Android shows a notification's actions only while it is expanded, and it expands only the first notification by
# itself. So this taps the Expand button of the notification with the given title, if it has one.
function Expand-Notification([string]$Title) {
    $path = "//node[@text='$Title']/ancestor::node[.//node[@content-desc='Expand' or @content-desc='Collapse']][1]"
    $notification = (Get-ScreenLayout).SelectSingleNode($path)
    $button = if ($notification) { $notification.SelectSingleNode(".//node[@content-desc='Expand']") }
    if ($button) {
        Invoke-TapNode $button
        Start-Sleep -Seconds 1
    }
}

function Invoke-TapNode($Node) {
    $bounds = [regex]::Match($Node.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]').Groups
    $x = ([int]$bounds[1].Value + [int]$bounds[3].Value) / 2
    $y = ([int]$bounds[2].Value + [int]$bounds[4].Value) / 2
    Invoke-Shell "input tap $x $y" | Out-Null
}

function Start-Call {
    if ($isEmulator) { & $adb -s $Serial emu gsm call $caller | Out-Null } else { Read-Host 'Call this phone now, and press Enter when it rings' | Out-Null }
}

function Stop-Call {
    if ($isEmulator) { & $adb -s $Serial emu gsm cancel $caller | Out-Null } else { Read-Host 'End the call, then press Enter' | Out-Null }
}

function Lock-Screen {
    Invoke-Shell 'input keyevent KEYCODE_SLEEP' | Out-Null
    Start-Sleep -Seconds 2
    Invoke-Shell 'input keyevent KEYCODE_WAKEUP' | Out-Null
    Start-Sleep -Seconds 2
}

function Unlock-Screen {
    Invoke-Shell 'wm dismiss-keyguard' | Out-Null
    Start-Sleep -Seconds 2
    if (Test-LockScreen) { Read-Host 'Unlock the phone, then press Enter' | Out-Null }
}

function Test-LockScreen { (Invoke-Shell 'dumpsys activity activities') -match '(?m)^\s*KeyguardShowing=true' }

function Test-Ringing { (Invoke-Shell 'dumpsys telephony.registry') -match 'mCallState=1' }

# Asks Kestrel for the location, and lets the test provider report its place a few times meanwhile.
function Get-SpikeLocation([string]$Source) {
    Invoke-Hook location -Source $Source | Out-Null
    for ($i = 0; $i -lt 4; $i++) {
        Start-Sleep -Seconds 3
        Invoke-Shell 'cmd location providers set-test-provider-location fused --location 44.8125,20.4612 --accuracy 10' | Out-Null
    }
    Wait-Result "source=$Source " 20
}

function Get-Granted([string]$Permission) {
    (Invoke-Shell 'dumpsys package dev.kestrel') -match "$([regex]::Escape($Permission)): granted=true"
}

$results = [ordered]@{}
$savedAccessibility = Get-AccessibilitySettings -Serial $Serial
$locationGranted = Get-Granted 'android.permission.ACCESS_FINE_LOCATION'
try {
    foreach ($permission in 'RECORD_AUDIO', 'POST_NOTIFICATIONS', 'ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION') {
        Invoke-Shell "pm grant dev.kestrel android.permission.$permission" | Out-Null
    }
    Invoke-Hook spike-results -Clear | Out-Null

    foreach ($name in $Scenario) {
        Write-Host "Scenario $name"
        switch ($name) {
            'accessibility' {
                # The recording starts 15 s after the hook's broadcast, while another app is in front.
                Set-KestrelService $true
                Invoke-Shell 'input keyevent KEYCODE_HOME' | Out-Null
                Invoke-Hook listen -Source accessibility -DelaySeconds 15 -Seconds 3 | Out-Null
                $results[$name] = Wait-Result 'source=accessibility ' 40
            }
            'no-service' {
                # The control: no accessibility service, nothing in front, no notification or tile.
                Set-KestrelService $false
                Invoke-Shell 'input keyevent KEYCODE_HOME' | Out-Null
                Invoke-Hook listen -Source no-service -DelaySeconds 0 -Seconds 3 | Out-Null
                $results[$name] = Wait-Result 'source=no-service ' 20
            }
            'notification' {
                Set-KestrelService $false
                Invoke-Shell 'input keyevent KEYCODE_HOME' | Out-Null
                Invoke-Hook listen-notification | Out-Null
                Invoke-Shell 'cmd statusbar expand-notifications' | Out-Null
                Start-Sleep -Seconds 2
                Expand-Notification 'Kestrel spike'
                Invoke-TapText 'Start listening'
                $results[$name] = Wait-Result 'source=notification ' 20
                # The notification removes itself after a minute; the shell cannot remove another app's notification.
                Invoke-Shell 'cmd statusbar collapse' | Out-Null
            }
            'tile' {
                Set-KestrelService $false
                Invoke-Shell 'input keyevent KEYCODE_HOME' | Out-Null
                Invoke-Shell "cmd statusbar add-tile $tile" | Out-Null
                Start-Sleep -Seconds 2
                Invoke-Shell "cmd statusbar click-tile $tile" | Out-Null
                $results[$name] = Wait-Result 'source=tile ' 20
                Invoke-Shell "cmd statusbar remove-tile $tile" | Out-Null
                Invoke-Shell 'cmd statusbar collapse' | Out-Null
            }
            'ringing' {
                # First Kestrel records while the screen is locked and a call starts to ring; then it starts recording
                # while a call rings. An emulator has no lock screen until it is turned on.
                $lockDisabled = (Invoke-Shell 'cmd lock_settings get-disabled').Trim()
                $callsSince = [long](Invoke-Shell 'date +%s').Trim() * 1000
                Invoke-Shell 'cmd lock_settings set-disabled false' | Out-Null
                try {
                    Set-KestrelService $true
                    Lock-Screen
                    $locked = Test-LockScreen
                    Invoke-Hook listen -Source ringing-already -DelaySeconds 0 -Seconds 20 | Out-Null
                    Start-Sleep -Seconds 5
                    Start-Call
                    Start-Sleep -Seconds 3
                    $rang = Test-Ringing
                    Start-Sleep -Seconds 7
                    Stop-Call
                    $line = Wait-Result 'source=ringing-already ' 30
                    $results['ringing, already recording'] = "locked=$locked ringing=$rang $line"
                    Start-Call
                    Start-Sleep -Seconds 3
                    $locked = Test-LockScreen
                    $rang = Test-Ringing
                    Invoke-Hook listen -Source ringing-start -DelaySeconds 0 -Seconds 3 | Out-Null
                    $line = Wait-Result 'source=ringing-start ' 20
                    $results['ringing, start'] = "locked=$locked ringing=$rang $line"
                    Stop-Call
                    Unlock-Screen
                } finally {
                    if ($lockDisabled -eq 'true') { Invoke-Shell 'cmd lock_settings set-disabled true' | Out-Null }
                    # The emulator's calls end up in its call log as missed calls.
                    if ($isEmulator) {
                        Invoke-Shell "content delete --uri content://call_log/calls --where `"number='$caller' and date>=$callsSince`"" | Out-Null
                    }
                }
            }
            'location' {
                # The shell may add a test provider only with the mock-location permission.
                $mockMode = if ((Invoke-Shell 'appops get com.android.shell android:mock_location') -match 'mock_location: allow') { 'allow' } else { 'default' }
                Invoke-Shell 'appops set com.android.shell android:mock_location allow' | Out-Null
                Invoke-Shell 'cmd location set-location-enabled true' | Out-Null
                Invoke-Shell 'cmd location providers add-test-provider fused' | Out-Null
                Invoke-Shell 'cmd location providers set-test-provider-enabled fused true' | Out-Null
                try {
                    Set-KestrelService $true
                    Invoke-Shell 'am start -W -n dev.kestrel/dev.kestrel.app.MainActivity' | Out-Null
                    $results['location, Kestrel in front'] = Get-SpikeLocation 'kestrel-in-front'
                    Invoke-Shell 'am start -W -a android.settings.SETTINGS' | Out-Null
                    $results['location, Settings in front'] = Get-SpikeLocation 'settings-in-front'
                    Set-KestrelService $false
                    $results['location, service off'] = Get-SpikeLocation 'service-off'
                    Invoke-Shell 'input keyevent KEYCODE_HOME' | Out-Null
                } finally {
                    Invoke-Shell 'cmd location providers remove-test-provider fused' | Out-Null
                    Invoke-Shell "appops set com.android.shell android:mock_location $mockMode" | Out-Null
                }
            }
            'boot' {
                Set-KestrelService $true
                Invoke-Hook listen-after-boot | Out-Null
                & $adb -s $Serial reboot
                & $adb -s $Serial wait-for-device
                while ((Invoke-Shell 'getprop sys.boot_completed').Trim() -ne '1') { Start-Sleep -Seconds 2 }
                $results[$name] = Wait-Result 'source=boot ' 240
            }
        }
    }
} finally {
    $null = Restore-AccessibilitySettings -Serial $Serial -Saved $savedAccessibility
    # Revoking a permission stops Kestrel's process, so this comes last.
    if (-not $locationGranted) {
        Invoke-Shell 'pm revoke dev.kestrel android.permission.ACCESS_FINE_LOCATION' | Out-Null
        Invoke-Shell 'pm revoke dev.kestrel android.permission.ACCESS_COARSE_LOCATION' | Out-Null
    }
}
$results.GetEnumerator() | ForEach-Object { '{0,-30} {1}' -f $_.Key, $_.Value }
