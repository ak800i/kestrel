# Spike S1 (design spec, section 19): can Kestrel start its listening service and record when the accessibility service,
# a notification, the Quick Settings tile or a restart starts it, also while a call rings on the lock screen? Can it read
# the location while another app is in front? It needs Kestrel's debug build (scripts\install.ps1), and it prints what
# Kestrel recorded in its results: whether each step worked, never the audio or the place. The location comes from a
# test provider with a made-up place, which replaces the fused provider while the scenario runs.
# On a phone, the ringing scenario asks you to call the phone, to end the call and, if it has a PIN, to unlock it.
# Afterwards, also when it fails or is interrupted, it puts back what it changed on the device. If it cannot put something
# back, it says what and ends with exit code 1.
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

# With the shade open, the screen includes other apps' notifications, so the dump goes where only the shell can read it.
function Get-ScreenLayout {
    try {
        Invoke-Shell 'uiautomator dump /data/local/tmp/spike-ui.xml' | Out-Null
        [xml]$ui = (& $adb -s $Serial exec-out cat /data/local/tmp/spike-ui.xml) -join "`n"
        $ui
    } finally {
        Invoke-Shell 'rm -f /data/local/tmp/spike-ui.xml' | Out-Null
    }
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

# Runs a command on the device and fails when it fails.
function Invoke-Checked([string]$Command) { Invoke-SettingsCommand -Serial $Serial -Command $Command }

# Returns the permissions, of those given, that Kestrel has now.
function Get-GrantedPermissions([string[]]$Permissions) {
    $package = (Invoke-Checked 'dumpsys package dev.kestrel') -join "`n"
    @($Permissions | Where-Object { $package -match "android\.permission\.$_`: granted=true" })
}

# Waits until the device has started again after a reboot, which a new boot ID shows, for at most 5 minutes.
function Wait-Reboot([string]$BootId) {
    # adb complains while the device is offline, and that must not stop the wait.
    $ErrorActionPreference = 'Continue'
    $deadline = (Get-Date).AddMinutes(5)
    do {
        Start-Sleep -Seconds 3
        $id = "$(& $adb -s $Serial shell cat /proc/sys/kernel/random/boot_id 2>$null)".Trim()
        $booted = $id -and $id -ne $BootId -and "$(& $adb -s $Serial shell getprop sys.boot_completed 2>$null)".Trim() -eq '1'
    } until ($booted -or (Get-Date) -gt $deadline)
    if (-not $booted) { throw "$Serial did not start again within 5 minutes of the reboot." }
}

# Runs one step that puts the device back. A step that fails warns and lets the steps after it run, and the script then
# ends with exit code 1.
$restoreFailed = $false
function Restore-Step([string]$What, [scriptblock]$Step) {
    try {
        & $Step | Out-Null
    } catch {
        Write-Warning "Could not put back $What on $Serial`: $($_.Exception.Message)"
        $script:restoreFailed = $true
    }
}

$results = [ordered]@{}
$permissions = 'RECORD_AUDIO', 'POST_NOTIFICATIONS', 'ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION'
$savedAccessibility = Get-AccessibilitySettings -Serial $Serial
$grantedBefore = Get-GrantedPermissions $permissions
try {
    foreach ($permission in $permissions) { Invoke-Checked "pm grant dev.kestrel android.permission.$permission" | Out-Null }
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
                $tilesBefore = Get-SecureSetting -Serial $Serial -Name sysui_qs_tiles
                try {
                    Invoke-Checked "cmd statusbar add-tile $tile" | Out-Null
                    Start-Sleep -Seconds 2
                    Invoke-Checked "cmd statusbar click-tile $tile" | Out-Null
                    $results[$name] = Wait-Result 'source=tile ' 20
                } finally {
                    Restore-Step 'the Quick Settings tiles' {
                        Invoke-Checked "cmd statusbar remove-tile $tile"
                        Start-Sleep -Seconds 2
                        if ((Get-SecureSetting -Serial $Serial -Name sysui_qs_tiles) -cne $tilesBefore -and
                            -not (Set-SecureSetting -Serial $Serial -Name sysui_qs_tiles -Value $tilesBefore)) {
                            throw 'the list of tiles differs from before'
                        }
                    }
                    Invoke-Shell 'cmd statusbar collapse' | Out-Null
                }
            }
            'ringing' {
                # First Kestrel records while the screen is locked and a call starts to ring; then it starts recording
                # while a call rings. An emulator has no lock screen until it is turned on.
                $lockDisabled = (Invoke-Checked 'cmd lock_settings get-disabled') -join ''
                $callsSince = [long]((Invoke-Checked 'date +%s') -join '') * 1000
                $calling = $false
                try {
                    Invoke-Checked 'cmd lock_settings set-disabled false' | Out-Null
                    Set-KestrelService $true
                    Lock-Screen
                    $locked = Test-LockScreen
                    Invoke-Hook listen -Source ringing-already -DelaySeconds 0 -Seconds 20 | Out-Null
                    Start-Sleep -Seconds 5
                    $calling = $true
                    Start-Call
                    Start-Sleep -Seconds 3
                    $rang = Test-Ringing
                    Start-Sleep -Seconds 7
                    Stop-Call
                    $calling = $false
                    $line = Wait-Result 'source=ringing-already ' 30
                    $results['ringing, already recording'] = "locked=$locked ringing=$rang $line"
                    $calling = $true
                    Start-Call
                    Start-Sleep -Seconds 3
                    $locked = Test-LockScreen
                    $rang = Test-Ringing
                    Invoke-Hook listen -Source ringing-start -DelaySeconds 0 -Seconds 3 | Out-Null
                    $line = Wait-Result 'source=ringing-start ' 20
                    $results['ringing, start'] = "locked=$locked ringing=$rang $line"
                } finally {
                    Restore-Step 'the call' {
                        if ($calling) {
                            Stop-Call
                            # Gives the phone app time to write the call to the call log.
                            Start-Sleep -Seconds 3
                            if (Test-Ringing) { throw 'a call still rings' }
                        }
                    }
                    Restore-Step 'the unlocked screen' { Unlock-Screen }
                    Restore-Step 'the lock-screen setting' {
                        Invoke-Checked "cmd lock_settings set-disabled $lockDisabled"
                        if (((Invoke-Checked 'cmd lock_settings get-disabled') -join '') -ne $lockDisabled) { throw "it is not $lockDisabled" }
                    }
                    # The emulator's calls end up in its call log as missed calls.
                    if ($isEmulator) {
                        Restore-Step 'the call log' {
                            Invoke-Checked "content delete --uri content://call_log/calls --where `"number='$caller' and date>=$callsSince`""
                        }
                    }
                }
            }
            'location' {
                # The shell may add a test provider only with the mock-location permission.
                $mockMode = if ((Invoke-Checked 'appops get com.android.shell android:mock_location') -match 'mock_location: allow') { 'allow' } else { 'default' }
                $locationOn = (Invoke-Checked 'cmd location is-location-enabled') -join ''
                # Android records in this setting who switched location last.
                $locationChanger = Get-SecureSetting -Serial $Serial -Name location_changer
                $providerAdded = $false
                try {
                    Invoke-Checked 'appops set com.android.shell android:mock_location allow' | Out-Null
                    Invoke-Checked 'cmd location set-location-enabled true' | Out-Null
                    $providerAdded = $true
                    Invoke-Checked 'cmd location providers add-test-provider fused' | Out-Null
                    Invoke-Checked 'cmd location providers set-test-provider-enabled fused true' | Out-Null
                    Set-KestrelService $true
                    Invoke-Shell 'am start -W -n dev.kestrel/dev.kestrel.app.MainActivity' | Out-Null
                    $results['location, Kestrel in front'] = Get-SpikeLocation 'kestrel-in-front'
                    Invoke-Shell 'am start -W -a android.settings.SETTINGS' | Out-Null
                    $results['location, Settings in front'] = Get-SpikeLocation 'settings-in-front'
                    Set-KestrelService $false
                    $results['location, service off'] = Get-SpikeLocation 'service-off'
                } finally {
                    Invoke-Shell 'input keyevent KEYCODE_HOME' | Out-Null
                    if ($providerAdded) {
                        Restore-Step 'the fused location provider' { Invoke-Checked 'cmd location providers remove-test-provider fused' }
                    }
                    Restore-Step 'the mock-location permission of the shell' {
                        Invoke-Checked "appops set com.android.shell android:mock_location $mockMode"
                    }
                    Restore-Step 'the location switch' {
                        Invoke-Checked "cmd location set-location-enabled $locationOn"
                        if (((Invoke-Checked 'cmd location is-location-enabled') -join '') -ne $locationOn) { throw "it is not $locationOn" }
                        if ((Get-SecureSetting -Serial $Serial -Name location_changer) -cne $locationChanger -and
                            -not (Set-SecureSetting -Serial $Serial -Name location_changer -Value $locationChanger)) {
                            throw 'the setting location_changer differs from before'
                        }
                    }
                }
            }
            'boot' {
                Set-KestrelService $true
                $bootId = (Invoke-Checked 'cat /proc/sys/kernel/random/boot_id') -join ''
                Invoke-Hook listen-after-boot | Out-Null
                & $adb -s $Serial reboot
                Wait-Reboot $bootId
                $results[$name] = Wait-Result 'source=boot ' 240
            }
        }
    }
} finally {
    # Without a reboot, the request to record at the next start would stay, and a later start of Kestrel would record.
    if ($Scenario -contains 'boot') {
        Restore-Step "Kestrel's files" { Invoke-Checked 'run-as dev.kestrel rm -f no_backup/spike-listen-at-next-start' }
    }
    Restore-Step 'the accessibility settings' {
        if (-not (Restore-AccessibilitySettings -Serial $Serial -Saved $savedAccessibility)) { throw 'see the warning above' }
    }
    # Revoking a permission stops Kestrel's process, so this comes last.
    foreach ($permission in $permissions | Where-Object { $_ -notin $grantedBefore }) {
        Restore-Step "Kestrel's permission $permission" {
            Invoke-Checked "pm revoke dev.kestrel android.permission.$permission"
            if (Get-GrantedPermissions $permission) { throw 'it is still granted' }
        }
    }
}
$results.GetEnumerator() | ForEach-Object { '{0,-30} {1}' -f $_.Key, $_.Value }
if ($restoreFailed) {
    Write-Warning 'The device is not as it was before. The warnings above say what to put back.'
    exit 1
}
