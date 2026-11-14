# Spike S1 (design spec, section 19): can Kestrel start its listening service and record when the accessibility service,
# a notification, the Quick Settings tile or a restart starts it, also while a call rings on the lock screen? Can it read
# the location while another app is in front? It needs Kestrel's debug build (scripts\install.ps1), and it prints what
# Kestrel recorded in its results: whether each step worked, never the audio or the place. The location comes from a
# test provider with a made-up place, which replaces the fused provider while the scenario runs.
# On a phone, the ringing scenario asks you to call the phone, to end the call and, if it has a PIN, to unlock it. With a
# PIN, the boot scenario also asks you to unlock it after the reboot.
# Afterwards, also when it fails or is interrupted, it puts back what it changed on the device. If it cannot put something
# back, also because Ctrl+C interrupted it while it put things back, it says at the end what is left and how to put it
# back by hand. Then, and after Ctrl+C, it ends with exit code 1. With -Verbose, it names each thing that it puts back.
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
    $restores = @(
        Register-Restore 'the folder /data/local/tmp' (Format-DeviceCommand 'rm -f /data/local/tmp/spike-ui.xml') {
            Invoke-Checked 'rm -f /data/local/tmp/spike-ui.xml'
        }
    )
    try {
        Invoke-Shell 'uiautomator dump /data/local/tmp/spike-ui.xml' | Out-Null
        [xml]$ui = (& $adb -s $Serial exec-out cat /data/local/tmp/spike-ui.xml) -join "`n"
        $ui
    } finally {
        Invoke-Restore $restores
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

# Returns the shell's mock-location mode: allow, ignore, deny, foreground or default.
function Get-MockLocationMode {
    $answer = (Invoke-Checked 'appops get com.android.shell android:mock_location') -join "`n"
    if ($answer -match '(?m)^\s*MOCK_LOCATION: (\w+)') { return $Matches[1] }
    # For an app-op that the shell has never used, Android names the mode that it then has.
    if ($answer -match '(?m)^Default mode: (\w+)') { return $Matches[1] }
    throw "Could not read the shell's mock-location mode from: $answer"
}

# Waits until no call rings, for at most 5 s.
function Wait-CallEnd {
    for ($i = 0; $i -lt 5; $i++) {
        if (-not (Test-Ringing)) { return }
        Start-Sleep -Seconds 1
    }
    throw 'a call still rings'
}

# Counts the calls from the test number in the call log since the given time, in milliseconds.
function Get-TestCallCount([long]$Since) {
    $rows = Invoke-Checked "content query --uri content://call_log/calls --projection _id --where `"number='$caller' and date>=$Since`""
    @($rows | Where-Object { $_ -like 'Row: *' }).Count
}

# Waits until the device has started again after a reboot, which a new boot ID shows, and until Android has unlocked the
# user's storage, without which Kestrel cannot run. On a phone with a PIN, the owner has to unlock it first. It waits for
# at most 5 minutes.
function Wait-Reboot([string]$BootId) {
    # adb complains while the device is offline, and that must not stop the wait.
    $ErrorActionPreference = 'Continue'
    $deadline = (Get-Date).AddMinutes(5)
    $lockedChecks = 0
    do {
        Start-Sleep -Seconds 3
        $id = "$(& $adb -s $Serial shell cat /proc/sys/kernel/random/boot_id 2>$null)".Trim()
        $booted = $id -and $id -ne $BootId -and "$(& $adb -s $Serial shell getprop sys.boot_completed 2>$null)".Trim() -eq '1'
        $unlocked = $booted -and "$(& $adb -s $Serial shell am get-started-user-state 0 2>$null)" -match 'RUNNING_UNLOCKED'
        # Without a PIN, Android unlocks the storage a few seconds after the start.
        if ($booted -and -not $unlocked -and ++$lockedChecks -eq 2 -and -not $isEmulator) {
            Read-Host 'Unlock the phone, then press Enter' | Out-Null
        }
    } until ($unlocked -or (Get-Date) -gt $deadline)
    if (-not $unlocked) { throw "$Serial did not start again within 5 minutes of the reboot." }
}

# What the script has still to put back. Each restore enters this list before the change that it undoes is made, and it
# leaves the list when it has worked. At the end, the script names the restores that are left and how to do them by hand.
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
        Write-Verbose "Putting back $($restore.What)"
        & $restore.Action | Out-Null
        $pending.Remove($restore) | Out-Null
    } catch {
        $restore.Reason = $_.Exception.Message
    } finally {
        Invoke-Restore $Steps ($Index + 1)
    }
}

# Returns a PowerShell command that runs the given command on the device, for the warnings.
function Format-DeviceCommand([string]$Command) {
    "Run: & '$($adb -replace "'", "''")' -s $Serial shell '$($Command -replace "'", "''")'"
}

$results = [ordered]@{}
$permissions = 'RECORD_AUDIO', 'POST_NOTIFICATIONS', 'ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION'
$savedAccessibility = Get-AccessibilitySettings -Serial $Serial
$grantedBefore = Get-GrantedPermissions $permissions
$notGrantedBefore = @($permissions | Where-Object { $_ -notin $grantedBefore })
$revokeByHand = @($notGrantedBefore | ForEach-Object { "pm revoke dev.kestrel android.permission.$_" }) -join '; '
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
$flagFile = 'no_backup/spike-listen-at-next-start'
$flagRequested = $false
$completed = $false
$failed = $false
$restoresAtEnd = @(
    # Without a reboot, the request to record at the next start would stay, and a later start of Kestrel would record.
    if ($Scenario -contains 'boot') {
        Register-Restore "Kestrel's request to record at its next start" (Format-DeviceCommand "run-as dev.kestrel rm -f $flagFile") {
            if ($flagRequested) { Invoke-Checked "run-as dev.kestrel rm -f $flagFile" }
        }
    }
    Register-Restore 'the accessibility settings' (Format-DeviceCommand $accessibilityByHand) {
        if (-not (Restore-AccessibilitySettings -Serial $Serial -Saved $savedAccessibility)) { throw 'see the warning above' }
    }
    # Revoking a permission stops Kestrel's process, so this comes last.
    if ($notGrantedBefore) {
        Register-Restore "Kestrel's permissions" (Format-DeviceCommand $revokeByHand) {
            $granted = @(Get-GrantedPermissions $notGrantedBefore)
            foreach ($permission in $granted) { Invoke-Checked "pm revoke dev.kestrel android.permission.$permission" | Out-Null }
            $still = if ($granted) { @(Get-GrantedPermissions $granted) } else { @() }
            if ($still) { throw "$($still -join ', ') still granted" }
        }
    }
)
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
                # The notification removes itself after a minute; the shell cannot remove another app's notification.
                $restores = @(
                    Register-Restore 'the notification shade' (Format-DeviceCommand 'cmd statusbar collapse') {
                        Invoke-Shell 'cmd statusbar collapse'
                    }
                )
                try {
                    Invoke-Shell 'cmd statusbar expand-notifications' | Out-Null
                    Start-Sleep -Seconds 2
                    Expand-Notification 'Kestrel spike'
                    Invoke-TapText 'Start listening'
                    $results[$name] = Wait-Result 'source=notification ' 20
                } finally {
                    Invoke-Restore $restores
                }
            }
            'tile' {
                Set-KestrelService $false
                Invoke-Shell 'input keyevent KEYCODE_HOME' | Out-Null
                $tilesBefore = Get-SecureSetting -Serial $Serial -Name sysui_qs_tiles
                $restores = @(
                    Register-Restore 'the Quick Settings tiles' (Format-DeviceCommand "cmd statusbar remove-tile $tile") {
                        Invoke-Checked "cmd statusbar remove-tile $tile"
                        Start-Sleep -Seconds 2
                        if ((Get-SecureSetting -Serial $Serial -Name sysui_qs_tiles) -cne $tilesBefore -and
                            -not (Set-SecureSetting -Serial $Serial -Name sysui_qs_tiles -Value $tilesBefore)) {
                            throw 'the list of tiles differs from before'
                        }
                    }
                    Register-Restore 'the notification shade' (Format-DeviceCommand 'cmd statusbar collapse') {
                        Invoke-Shell 'cmd statusbar collapse'
                    }
                )
                try {
                    Invoke-Checked "cmd statusbar add-tile $tile" | Out-Null
                    Start-Sleep -Seconds 2
                    Invoke-Checked "cmd statusbar click-tile $tile" | Out-Null
                    $results[$name] = Wait-Result 'source=tile ' 20
                } finally {
                    Invoke-Restore $restores
                }
            }
            'ringing' {
                # First Kestrel records while the screen is locked and a call starts to ring; then it starts recording
                # while a call rings. An emulator has no lock screen until it is turned on. A phone with a PIN, pattern
                # or password has one, and Android 13 refuses to read the setting there without the credential, so the
                # script changes the setting only when it answers that there is no lock screen.
                $noLockScreen = "$(& $adb -s $Serial shell cmd lock_settings get-disabled 2>$null)".Trim() -eq 'true'
                $callsSince = [long]((Invoke-Checked 'date +%s') -join '') * 1000
                $callLogCommand = "content delete --uri content://call_log/calls --where `"number='$caller' and date>=$callsSince`""
                $calls = 0
                $calling = $false
                $lockChanged = $false
                $restores = @(
                    Register-Restore 'the call' $(if ($isEmulator) { "Run: & '$($adb -replace "'", "''")' -s $Serial emu gsm cancel $caller" } else { 'End the call.' }) {
                        if ($calling) {
                            Stop-Call
                            Wait-CallEnd
                        }
                    }
                    Register-Restore 'the unlocked screen' $(if ($isEmulator) { Format-DeviceCommand 'wm dismiss-keyguard' } else { 'Unlock the phone.' }) {
                        Unlock-Screen
                    }
                    if ($noLockScreen) {
                        Register-Restore 'the lock-screen setting' (Format-DeviceCommand 'cmd lock_settings set-disabled true') {
                            if ($lockChanged) {
                                Invoke-Checked 'cmd lock_settings set-disabled true'
                                if (((Invoke-Checked 'cmd lock_settings get-disabled') -join '') -ne 'true') { throw 'it is not true' }
                            }
                        }
                    }
                    # The emulator's calls end up in its call log as missed calls.
                    if ($isEmulator) {
                        Register-Restore 'the call log' (Format-DeviceCommand $callLogCommand) {
                            # The phone app writes a call to the call log only after the call has ended.
                            if (Test-Ringing) { throw 'a call still rings' }
                            for ($i = 0; $i -lt 10 -and (Get-TestCallCount $callsSince) -lt $calls; $i++) { Start-Sleep -Seconds 1 }
                            Invoke-Checked $callLogCommand
                            if ((Get-TestCallCount $callsSince) -gt 0) { throw "this run's calls are still in the call log" }
                        }
                    }
                )
                try {
                    if ($noLockScreen) {
                        $lockChanged = $true
                        Invoke-Checked 'cmd lock_settings set-disabled false' | Out-Null
                    }
                    Set-KestrelService $true
                    Lock-Screen
                    $locked = Test-LockScreen
                    Invoke-Hook listen -Source ringing-already -DelaySeconds 0 -Seconds 20 | Out-Null
                    Start-Sleep -Seconds 5
                    $calls++
                    $calling = $true
                    Start-Call
                    Start-Sleep -Seconds 3
                    $rang = Test-Ringing
                    Start-Sleep -Seconds 7
                    Stop-Call
                    $calling = $false
                    $line = Wait-Result 'source=ringing-already ' 30
                    $results['ringing, already recording'] = "locked=$locked ringing=$rang $line"
                    $calls++
                    $calling = $true
                    Start-Call
                    Start-Sleep -Seconds 3
                    $locked = Test-LockScreen
                    $rang = Test-Ringing
                    Invoke-Hook listen -Source ringing-start -DelaySeconds 0 -Seconds 3 | Out-Null
                    $line = Wait-Result 'source=ringing-start ' 20
                    $results['ringing, start'] = "locked=$locked ringing=$rang $line"
                } finally {
                    Invoke-Restore $restores
                }
            }
            'location' {
                # The shell may add a test provider only with the mock-location permission.
                $mockMode = Get-MockLocationMode
                $locationOn = (Invoke-Checked 'cmd location is-location-enabled') -join ''
                # Android records in this setting who switched location last.
                $locationChanger = Get-SecureSetting -Serial $Serial -Name location_changer
                $changerCommand = if ($locationChanger -eq 'null') { 'settings delete secure location_changer' } else { "settings put secure location_changer '$locationChanger'" }
                # Android removes the test provider only while the shell has the mock-location permission.
                $removeProvider = 'appops set com.android.shell android:mock_location allow; cmd location providers remove-test-provider fused; ' +
                "appops set com.android.shell android:mock_location $mockMode"
                $providerAdded = $false
                $restores = @(
                    Register-Restore 'the home screen' (Format-DeviceCommand 'input keyevent KEYCODE_HOME') {
                        Invoke-Shell 'input keyevent KEYCODE_HOME'
                    }
                    Register-Restore 'the fused location provider' (Format-DeviceCommand $removeProvider) {
                        if ($providerAdded) { Invoke-Checked 'cmd location providers remove-test-provider fused' }
                    }
                    Register-Restore 'the mock-location permission of the shell' (Format-DeviceCommand "appops set com.android.shell android:mock_location $mockMode") {
                        Invoke-Checked "appops set com.android.shell android:mock_location $mockMode"
                        if ((Get-MockLocationMode) -ne $mockMode) { throw "it is not $mockMode" }
                    }
                    Register-Restore 'the location switch' (Format-DeviceCommand "cmd location set-location-enabled $locationOn; $changerCommand") {
                        Invoke-Checked "cmd location set-location-enabled $locationOn"
                        if (((Invoke-Checked 'cmd location is-location-enabled') -join '') -ne $locationOn) { throw "it is not $locationOn" }
                        if ((Get-SecureSetting -Serial $Serial -Name location_changer) -cne $locationChanger -and
                            -not (Set-SecureSetting -Serial $Serial -Name location_changer -Value $locationChanger)) {
                            throw 'the setting location_changer differs from before'
                        }
                    }
                )
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
                    Invoke-Restore $restores
                }
            }
            'boot' {
                Set-KestrelService $true
                $bootId = (Invoke-Checked 'cat /proc/sys/kernel/random/boot_id') -join ''
                $flagRequested = $true
                Invoke-Hook listen-after-boot | Out-Null
                & $adb -s $Serial reboot
                Wait-Reboot $bootId
                $results[$name] = Wait-Result 'source=boot ' 240
            }
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
            Write-Warning "Could not put back $($restore.What) on $Serial ($($restore.Reason)). $($restore.ByHand)"
        }
        # After a stop, such as Ctrl+C, PowerShell would end the script with exit code 0. A failed run ends with its error.
        if (-not ($completed -or $failed) -or $pending.Reason -contains $stopReason) { exit 1 }
    }
}
$results.GetEnumerator() | ForEach-Object { '{0,-30} {1}' -f $_.Key, $_.Value }
if ($pending.Count -gt 0) {
    Write-Warning 'The device is not as it was before. The warnings above say how to put it back.'
    exit 1
}
