# Spike S1 (design spec, section 19), its location check: can Kestrel read the location while another app is in front?
# scripts\listen-check.ps1 checks how Kestrel's real listening service starts. This script needs Kestrel's debug build
# (scripts\install.ps1), and it prints what Kestrel recorded in its results: whether a
# location came, never the place. The location comes from a test provider with a made-up place, which replaces the fused
# provider while the script runs.
# Before it turns Kestrel's accessibility service on, it turns Kestrel off, and Kestrel stays off afterwards.
# Afterwards, also when it fails or is interrupted, it puts back what it changed on the device. If it cannot put something
# back, also because Ctrl+C interrupted it while it put things back, it says at the end what is left and how to put it
# back by hand. Then, and after Ctrl+C, it ends with exit code 1. With -Verbose, it names each thing that it puts back.
# Example: scripts\spike-s1.ps1 -Serial emulator-5554
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial
$adb = Get-Adb
$service = $KestrelAccessibilityService

function Invoke-Shell([string]$Command) { (& $adb -s $Serial shell $Command) -join "`n" }
function Invoke-Hook { (& "$PSScriptRoot\hook.ps1" -Serial $Serial @args) -join "`n" }
# Runs a command on the device that Assert-TestDevice accepted, checked in the same adb call, and fails when it fails.
function Invoke-Checked([string]$Command) { Invoke-SettingsCommand -Serial $Serial -Command $Command }

# Turns Kestrel's accessibility service on, and waits until Kestrel's session is open, or off, and waits until Android's
# accessibility manager has applied the off (Disconnect-AccessibilityService, which keeps every other service).
function Set-KestrelService([bool]$On) {
    if (-not $On) {
        if (-not (Disconnect-AccessibilityService -Serial $Serial)) { throw "Android did not turn Kestrel's accessibility service off (see the warning above)." }
        return
    }
    # Waits until Android has bound the service, also after a stop or a crash of Kestrel, which leave it crashed.
    if (-not (Connect-AccessibilityService -Serial $Serial -Services $service)) { throw "Android did not bind Kestrel's accessibility service (see the warning above)." }
    Invoke-Checked 'settings put secure accessibility_enabled 1' | Out-Null
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

# Asks Kestrel for the location, and lets the test provider report its place a few times meanwhile.
function Get-SpikeLocation([string]$Source) {
    Invoke-Hook location -Source $Source | Out-Null
    for ($i = 0; $i -lt 4; $i++) {
        Start-Sleep -Seconds 3
        Invoke-Shell 'cmd location providers set-test-provider-location fused --location 44.8125,20.4612 --accuracy 10' | Out-Null
    }
    Wait-Result "source=$Source " 20
}

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
$permissions = 'ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION'
$savedAccessibility = Get-AccessibilitySettings -Serial $Serial
$grantedBefore = Get-GrantedPermissions $permissions
$notGrantedBefore = @($permissions | Where-Object { $_ -notin $grantedBefore })
$revokeByHand = @($notGrantedBefore | ForEach-Object { "pm revoke dev.kestrel android.permission.$_" }) -join '; '
# The commands that put back the accessibility settings by hand (Get-AccessibilityRestoreCommands): the list of services
# first, and they delete the list and the switch if these were not there before.
$accessibilityByHand = @(Get-AccessibilityRestoreCommands -Saved $savedAccessibility) -join '; '
$completed = $false
$failed = $false
$restoresAtEnd = @(
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
        # When its accessibility service connects, Kestrel would bring back a listening mode that it stored, and this
        # script never turns it off. The hook's off stops that and stores Off.
        Invoke-Hook voice -Send off | Out-Null
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
    Write-Host "Android may keep the made-up place as the device's last known location until the device gets a real one or restarts."
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
        if (-not ($completed -or $failed) -or $pending.Reason -contains $stopReason) {
            # pwsh -File ends a stopped script that declares parameters with exit code 0, even after 'exit 1', unless the
            # script gives the host its exit code. Run from a prompt, that would close the window, so the script does it
            # only when nothing called it.
            if (@(Get-PSCallStack).Count -eq 1) { $Host.SetShouldExit(1) }
            exit 1
        }
    }
}
$results.GetEnumerator() | ForEach-Object { '{0,-30} {1}' -f $_.Key, $_.Value }
if ($pending.Count -gt 0) {
    Write-Warning 'The device is not as it was before. The warnings above say how to put it back.'
    exit 1
}
