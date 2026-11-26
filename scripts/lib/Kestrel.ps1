# Shared helpers for Kestrel's scripts. Dot-source this file; it changes only the current process.

function Use-KestrelToolchain {
    # Gradle runs on the JDK that comes with Android Studio (design spec, section 6).
    $studioJdk = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path $studioJdk) {
        $env:JAVA_HOME = $studioJdk
    } elseif (-not $env:JAVA_HOME) {
        throw 'Set JAVA_HOME to a JDK 21.'
    }
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = 'D:\AndroidVMs\SDK' }
    if (-not (Test-Path $env:ANDROID_HOME)) { throw "Android SDK not found at $env:ANDROID_HOME" }
    # Newer emulator versions may ignore the legacy ANDROID_SDK_HOME variable, so point them at the AVDs directly.
    if (-not $env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME = Get-AvdHome }
}

function Get-AvdHome {
    if ($env:ANDROID_AVD_HOME) { return $env:ANDROID_AVD_HOME }
    if ($env:ANDROID_EMULATOR_HOME) { return (Join-Path $env:ANDROID_EMULATOR_HOME 'avd') }
    if ($env:ANDROID_USER_HOME) { return (Join-Path $env:ANDROID_USER_HOME 'avd') }
    if ($env:ANDROID_SDK_HOME) { return (Join-Path $env:ANDROID_SDK_HOME '.android\avd') }
    return (Join-Path $env:USERPROFILE '.android\avd')
}

function Get-Adb {
    return (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe')
}

# The serials that Assert-TestDevice accepted, each with the device property and the pattern that the device matched.
# A script dot-sources this file in its own scope, so each script has its own table.
$KestrelTestDevices = @{}

# Returns a command for the device's shell that runs the command only if the device still matches what Assert-TestDevice
# accepted for the serial, checked in the same shell, and that prints 'kestrel-refused' otherwise. Another device can
# take the serial of an emulator that ended, so the check and the command must not be two calls of adb. The 'case' keeps
# a command that contains '||' from running after a failed check. It has no double quotes, which Windows PowerShell 5.1
# would not pass on to adb; the word after 'case' is not split, also when the property is empty.
function Format-TestDeviceCommand([string]$Serial, [string]$Command) {
    $device = if ($KestrelTestDevices) { $KestrelTestDevices[$Serial] }
    if (-not $device) { throw "Run Assert-TestDevice -Serial $Serial before a command on that device." }
    "case `$(getprop $($device.Property)) in $($device.Pattern)) $Command;; *) echo kestrel-refused;; esac"
}

# Runs a settings command on a device and returns the lines that it printed. The device prints a marker only after a
# command that worked, which tells an empty answer from a failed command. It prints a line break before the marker, so
# that the marker has a line of its own also after an answer that ends without one, as One UI's 'dumpsys accessibility'
# does (A71 session of sub-project 2, item 3); after an answer that ends with a line break, that line break leaves an
# empty line, which is dropped. A busy device, or one whose system is still starting, sometimes fails the command, so it
# is repeated before it fails for good with the device's last complaint. The command runs only on the device that
# Assert-TestDevice accepted for the serial, and throws at once on another one.
function Invoke-SettingsCommand([string]$Serial, [string]$Command) {
    $adb = Get-Adb
    $guarded = Format-TestDeviceCommand -Serial $Serial -Command "$Command && echo && echo kestrel-end"
    # Keeps adb's error lines as part of the answer, so that they neither stop the script nor fill the console.
    $ErrorActionPreference = 'Continue'
    $complaint = ''
    foreach ($try in 1..3) {
        if ($try -gt 1) { Start-Sleep -Seconds 2 }
        $answer = @(& $adb -s $Serial shell $guarded 2>&1)
        $errors = @($answer | Where-Object { $_ -is [System.Management.Automation.ErrorRecord] })
        $lines = @($answer | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] } | ForEach-Object { "$_".TrimEnd("`r") })
        if ($lines.Count -gt 0 -and $lines[-1].Trim() -eq 'kestrel-end') {
            $count = $lines.Count - 1
            if ($count -gt 0 -and $lines[$count - 1] -eq '') { $count-- }
            return $lines | Select-Object -First $count
        }
        if ($lines.Count -gt 0 -and $lines[-1].Trim() -eq 'kestrel-refused') {
            throw "Refusing to run '$Command' on ${Serial}: it is no longer the test device that Assert-TestDevice accepted."
        }
        $complaint = (@($errors | ForEach-Object { "$_".Trim() } | Where-Object { $_ }) -join ' ')
    }
    if ($complaint) { throw "Could not run '$Command' on ${Serial}: $complaint" }
    throw "Could not run '$Command' on $Serial."
}

# Reads a secure setting of a device. Android answers 'null' for a setting that is not set, and a setting can also hold
# an empty value.
function Get-SecureSetting([string]$Serial, [string]$Name) {
    (Invoke-SettingsCommand -Serial $Serial -Command "settings get secure $Name") -join "`n"
}

# The device shell's command that gives the secure setting the value that Get-SecureSetting read, deleting it for 'null'.
function Format-SecureSettingCommand([string]$Name, [string]$Value) {
    # A single quote would end the device shell's quoting, so it becomes '\''.
    if ($Value -eq 'null') { "settings delete secure $Name" } else { "settings put secure $Name '$($Value -replace "'", "'\''")'" }
}

# A command that runs the device shell's command on the device when it is pasted into PowerShell.
function Format-PasteCommand([string]$Serial, [string]$Command) {
    $literal = { param($text) "'" + ($text -replace "'", "''") + "'" }
    "& $(& $literal (Get-Adb)) -s $Serial shell $(& $literal $Command)"
}

# Puts back a secure setting that Get-SecureSetting read, deleting it for 'null', and returns whether the device took it.
# When it did not, it warns with a command that puts the setting back when it is pasted into PowerShell.
function Set-SecureSetting([string]$Serial, [string]$Name, [string]$Value) {
    $command = Format-SecureSettingCommand -Name $Name -Value $Value
    $reason = ''
    $now = try {
        Invoke-SettingsCommand -Serial $Serial -Command $command | Out-Null
        Get-SecureSetting -Serial $Serial -Name $Name
    } catch {
        $reason = " ($($_.Exception.Message))"
        $null
    }
    if ($now -ceq $Value) { return $true }
    Write-Warning "Could not put back the setting $Name of $Serial$reason. Run: $(Format-PasteCommand -Serial $Serial -Command $command)"
    $false
}

# Puts back the list of Quick Settings tiles that Get-SecureSetting read from sysui_qs_tiles, and returns whether the
# device took it. On Android 17 (observed), SystemUI keeps the list itself and writes it back a moment after another write
# to the setting, so the list goes through SystemUI's 'cmd statusbar set-tiles', after which SystemUI writes the setting.
# When that command fails, or the setting does not show the list within 5 s, the list goes to the setting, which a
# SystemUI that does not keep the list follows. 'null' and an empty value, which set-tiles cannot take, go to the setting
# only. When the list does not come back, it warns with a command that puts it back when it is pasted into PowerShell.
function Set-QuickSettingsTiles([string]$Serial, [string]$Value) {
    $name = 'sysui_qs_tiles'
    if ($Value -in 'null', '') { return (Set-SecureSetting -Serial $Serial -Name $name -Value $Value) }
    # A single quote would end the device shell's quoting, so it becomes '\''.
    $list = "'$($Value -replace "'", "'\''")'"
    $command = "cmd statusbar set-tiles $list"
    $reason = 'SystemUI did not write the list within 5 s'
    try {
        Invoke-SettingsCommand -Serial $Serial -Command $command | Out-Null
        foreach ($try in 1..10) {
            Start-Sleep -Milliseconds 500
            if ((Get-SecureSetting -Serial $Serial -Name $name) -ceq $Value) { return $true }
        }
    } catch {
        $reason = $_.Exception.Message
    }
    try {
        Invoke-SettingsCommand -Serial $Serial -Command "settings put secure $name $list" | Out-Null
        # A SystemUI that keeps the list writes it back over the setting a moment later.
        Start-Sleep -Seconds 1
        if ((Get-SecureSetting -Serial $Serial -Name $name) -ceq $Value) { return $true }
    } catch {
        $reason = "$reason; $($_.Exception.Message)"
    }
    $literal = { param($text) "'" + ($text -replace "'", "''") + "'" }
    Write-Warning "Could not put back the Quick Settings tiles of $Serial ($reason). Run: & $(& $literal (Get-Adb)) -s $Serial shell $(& $literal $command)"
    $false
}

# Reads the secure settings that belong to accessibility, and returns their names and values. They include the list of
# enabled services and the accessibility switch. They include the shortcuts that start a service, which Android clears
# for a service that it turns off. They also include the touch-and-hold delay (long_press_timeout), which GestureTest
# changes, and which Android's Settings shows under Accessibility.
function Get-AccessibilitySettings([string]$Serial) {
    $settings = [ordered]@{}
    foreach ($line in (Invoke-SettingsCommand -Serial $Serial -Command 'settings list secure')) {
        $name, $value = $line -split '=', 2
        if ($null -ne $value -and $name -match 'accessibility|touch_exploration|^long_press_timeout$') { $settings[$name] = $value }
    }
    $settings
}

# Puts back the settings that Get-AccessibilitySettings read, and deletes those that were not there. The list of enabled
# services goes first, so that the shortcuts point to services that are on again. When that list holds Kestrel's
# service, it goes back through Connect-AccessibilityService, which also binds Kestrel's service again after a stop or a
# crash of Kestrel, when the list itself may be unchanged. Android changes other settings itself while it applies the
# list: it clears the shortcut and button targets of a service that it unbinds, and it writes accessibility_enabled. So
# the other settings are read again after the list, and compared with that read. Returns whether everything is back.
function Restore-AccessibilitySettings([string]$Serial, $Saved) {
    $warnUnread = {
        param($Reason)
        $before = @($Saved.Keys | ForEach-Object { "$_=$($Saved[$_])" }) -join "`n"
        Write-Warning "Could not read the accessibility settings of $Serial ($Reason), so they were not put back. Before, they were:`n$before"
    }
    $list = 'enabled_accessibility_services'
    try { $now = Get-AccessibilitySettings -Serial $Serial } catch { & $warnUnread $_.Exception.Message; return $false }
    $back = $true
    $value = if ($Saved.Contains($list)) { $Saved[$list] } else { 'null' }
    $current = if ($now.Contains($list)) { $now[$list] } else { 'null' }
    if (Test-ServiceListed -Services $value -Component $KestrelAccessibilityService) {
        if (-not (Connect-AccessibilityService -Serial $Serial -Services $value)) { $back = $false }
    } elseif ($value -cne $current) {
        # When Kestrel runs now, Android unbinds it a moment after the list, and only then clears its targets, which the
        # read below must see.
        $running = Get-AccessibilityServiceState -Serial $Serial
        if (-not (Set-SecureSetting -Serial $Serial -Name $list -Value $value)) { $back = $false }
        elseif ($running -and -not (Test-ServiceOff $running)) {
            $wait = Wait-AccessibilityServiceState -Serial $Serial -Component $KestrelAccessibilityService -Until { param($State) Test-ServiceOff $State } -TimeoutSeconds 30
            if (-not $wait.Reached) {
                Write-Warning "Android did not turn $KestrelAccessibilityService off on $Serial within 30 s ($(Format-ServiceState $wait.State)), so the settings that it changes then may not be back."
                $back = $false
            }
        }
    }
    try { $now = Get-AccessibilitySettings -Serial $Serial } catch { & $warnUnread $_.Exception.Message; return $false }
    $names = @('accessibility_enabled') + @(@($Saved.Keys) + @($now.Keys) | Sort-Object -Unique) | Where-Object { $_ -ne $list }
    foreach ($name in ($names | Select-Object -Unique)) {
        $value = if ($Saved.Contains($name)) { $Saved[$name] } else { 'null' }
        $current = if ($now.Contains($name)) { $now[$name] } else { 'null' }
        if ($value -cne $current -and -not (Set-SecureSetting -Serial $Serial -Name $name -Value $value)) { $back = $false }
    }
    $back
}

# The device shell's filter for the answer of 'dumpsys accessibility' that succeeds when the accessibility manager lists
# the component as enabled, binding or crashed. It reads only those lines, because the lines of the shortcut targets
# name the component in the same form.
function Format-ServiceListedFilter([string]$Component) {
    "grep -E '^ *(Enabled|Binding|Crashed) services:' | grep -qF '{$Component}'"
}

# The commands for the device's shell that put back the accessibility settings that Get-AccessibilitySettings read, for a
# warning that the owner pastes when a script could not put them back itself. As Restore-AccessibilitySettings does, they
# put back the list of services first. When that list holds Kestrel's service, they first take the service out of the
# list and wait up to 30 s until 'dumpsys accessibility' names it no more, so that the list's return binds it also when
# a stop or a crash of Kestrel left it crashed (Connect-AccessibilityService); the settings after the list, which include
# the shortcut and button targets that Android clears for a service that it unbinds, then go back too. A setting in
# $Delete that was not there before is deleted. Returns the commands in their order.
function Get-AccessibilityRestoreCommands($Saved, [string[]]$Delete = @('enabled_accessibility_services', 'accessibility_enabled')) {
    $list = 'enabled_accessibility_services'
    foreach ($name in (@($list, 'accessibility_enabled') + @($Delete) + @($Saved.Keys) | Select-Object -Unique)) {
        $value = if ($Saved.Contains($name)) { "$($Saved[$name])" } else { 'null' }
        if ($name -eq $list -and (Test-ServiceListed -Services $value -Component $KestrelAccessibilityService)) {
            $others = @($value -split ':' | Where-Object { $_ -and $_ -cne $KestrelAccessibilityService }) -join ':'
            Format-SecureSettingCommand -Name $list -Value $(if ($others) { $others } else { 'null' })
            "i=0; while [ `$i -lt 30 ] && dumpsys accessibility | $(Format-ServiceListedFilter $KestrelAccessibilityService); do sleep 1; i=`$((i+1)); done"
        }
        if ($value -ne 'null' -or $name -in $Delete) { Format-SecureSettingCommand -Name $name -Value $value }
    }
}
$KestrelAccessibilityService = 'dev.kestrel/dev.kestrel.app.KestrelAccessibilityService'

# Whether the value of the setting enabled_accessibility_services ('null', empty, or components separated by ':') lists
# the component.
function Test-ServiceListed([string]$Services, [string]$Component) {
    @("$Services" -split ':') -ccontains $Component
}

# The state of an accessibility service in the answer of 'dumpsys accessibility': whether Android's accessibility
# manager has it enabled, is binding it, or holds it as crashed. AOSP's AccessibilityUserState.dump prints a block
# 'User state[' for each user, which starts with 'attributes:{id=<user>, ...' and has the lines 'Enabled services:{...}',
# 'Binding services:{...}' and 'Crashed services:{...}', each with every component's full name in braces. Only the block of
# the user that the line 'currentUserId=<user>' names counts, or the first block when that line is missing. Returns $null
# when the answer has no such block, so that an answer that could not be read never counts as a service that is off.
# 'Bound services:{...}' names only the services' labels, so Test-ServiceRecordBound tells whether the service is bound.
# The device tests' reader, ManagedService in app\src\debug, follows the same rule, and both are tested with the same
# sample dumps (app\src\testDebug\resources\accessibility-dumps).
function ConvertFrom-AccessibilityDump([string[]]$Dump, [string]$Component) {
    $current = $null
    foreach ($line in $Dump) { if ($line -match '^\s*currentUserId=(\d+)\s*$') { $current = $Matches[1]; break } }
    $blocks = [Collections.Generic.List[object]]::new()
    foreach ($line in $Dump) {
        if ($line -match '^\s*User state\[') { $blocks.Add([Collections.Generic.List[string]]::new()) }
        elseif ($blocks.Count -gt 0) { $blocks[$blocks.Count - 1].Add($line.Trim()) }
    }
    $block = $null
    foreach ($candidate in $blocks) {
        if ($null -eq $current -or ($candidate.Count -gt 0 -and $candidate[0] -match "^attributes:\{id=$current,")) { $block = $candidate; break }
    }
    if ($null -eq $block) { return $null }
    $listed = { param($Name) @($block | Where-Object { $_.StartsWith("$Name services:{") -and $_.Contains("{$Component}") }).Count -gt 0 }
    [pscustomobject]@{ Enabled = & $listed 'Enabled'; Binding = & $listed 'Binding'; Crashed = & $listed 'Crashed' }
}

# Whether the answer of 'dumpsys activity services <component>' shows the service running in a process, which for an
# accessibility service means that Android's accessibility manager has bound it.
function Test-ServiceRecordBound([string[]]$Dump) {
    @($Dump | Where-Object { $_ -match '\bapp=ProcessRecord\{' }).Count -gt 0
}

# The state of an accessibility service on the device (ConvertFrom-AccessibilityDump, and whether it is bound), or $null
# when the device's answers could not be read.
function Get-AccessibilityServiceState([string]$Serial, [string]$Component = $KestrelAccessibilityService) {
    try {
        $state = ConvertFrom-AccessibilityDump -Dump @(Invoke-SettingsCommand -Serial $Serial -Command 'dumpsys accessibility') -Component $Component
        if (-not $state) { return $null }
        $bound = Test-ServiceRecordBound @(Invoke-SettingsCommand -Serial $Serial -Command "dumpsys activity services $Component")
        $state | Add-Member -NotePropertyName Bound -NotePropertyValue $bound -PassThru
    } catch {
        $null
    }
}

# Whether the state shows an accessibility service that is off in every list of the accessibility manager and unbound.
function Test-ServiceOff($State) { $State -and -not ($State.Enabled -or $State.Binding -or $State.Crashed -or $State.Bound) }

# Whether the state shows an accessibility service that is enabled and bound, and that the manager neither still binds
# nor holds as crashed.
function Test-ServiceConnected($State) { $State -and $State.Enabled -and $State.Bound -and -not ($State.Binding -or $State.Crashed) }

function Format-ServiceState($State) {
    if (-not $State) { return 'its state could not be read' }
    "enabled=$($State.Enabled) binding=$($State.Binding) crashed=$($State.Crashed) bound=$($State.Bound)"
}

# The API level of the device's Android (ro.build.version.sdk), or $null when it could not be read.
function Get-DeviceSdk([string]$Serial) {
    try { [int]"$(Invoke-SettingsCommand -Serial $Serial -Command 'getprop ro.build.version.sdk')".Trim() } catch { $null }
}

# Android 14 (API 34) and later drop a service that is no longer enabled from the accessibility manager's binding set
# (AOSP's AccessibilityUserState.removeDisabledServicesFromTemporaryStatesLocked). Android 13's settings observer runs
# updateCrashedServicesIfNeededLocked instead, which drops it only from the crashed set, so there a binding ends only
# when the bind finishes (AccessibilityServiceConnection.initializeService), the package is updated, removed or force
# stopped, or the user changes; no write of the list ends it.
$OffEndsBindingSdk = 34

# Reads the accessibility service's state once a second until $Until accepts it or $TimeoutSeconds have passed, and
# returns the last state with whether $Until accepted it.
function Wait-AccessibilityServiceState([string]$Serial, [string]$Component, [scriptblock]$Until, [int]$TimeoutSeconds) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $state = Get-AccessibilityServiceState -Serial $Serial -Component $Component
        if (& $Until $state) { return [pscustomobject]@{ Reached = $true; State = $state } }
        Start-Sleep -Seconds 1
    } while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    [pscustomobject]@{ Reached = $false; State = $state }
}

# Reads the list of enabled accessibility services. Returns $null after a warning when the device does not answer, so
# that the callers report a failure instead of throwing in the middle of a restore.
function Read-ServiceList([string]$Serial, [string]$Then) {
    try {
        Get-SecureSetting -Serial $Serial -Name enabled_accessibility_services
    } catch {
        Write-Warning "Could not read the accessibility services of $Serial ($($_.Exception.Message)), so $Then"
        $null
    }
}

# Whether two values of enabled_accessibility_services name the same services. Android's accessibility manager reads
# the list as a set, so a change of order alone is no change for it.
function Test-SameServiceSet([string]$A, [string]$B) {
    $set = { param($Value) @("$Value" -split ':' | Where-Object { $_ -and $_ -ne 'null' } | Sort-Object -Unique -CaseSensitive) -join ':' }
    (& $set $A) -ceq (& $set $B)
}

# The explanation for a service that Android still binds after an off on Android 13 ($OffEndsBindingSdk).
function Format-BindingNote([int]$Sdk) {
    "Android $Sdk ends a binding only when the bind finishes, or when the app is updated or force stopped; turning the service off does not end it."
}

# Takes the component out of the list of enabled accessibility services, keeping every other service, and waits up to
# $TimeoutSeconds until Android's accessibility manager has applied that: it lists the component neither as enabled,
# binding nor crashed, and the service is unbound. The manager's settings observer drops a service that is no longer
# enabled from its crashed set, and from Android 14 on also from its binding set ($OffEndsBindingSdk); on Android 13 a
# binding ends only when the bind finishes, and the warning says so. Android also clears the shortcut and button targets
# of a service that it unbinds (AOSP's removeShortcutTargetForUnboundServiceLocked), which Restore-AccessibilitySettings
# puts back. Returns whether the service is off; otherwise it warns with the state.
function Disconnect-AccessibilityService([string]$Serial, [string]$Component = $KestrelAccessibilityService, [int]$TimeoutSeconds = 30) {
    $services = Read-ServiceList -Serial $Serial -Then "$Component was not turned off."
    if ($null -eq $services) { return $false }
    $others = @("$services" -split ':' | Where-Object { $_ -and $_ -ne 'null' -and $_ -cne $Component }) -join ':'
    $without = if ($others) { $others } else { 'null' }
    if ($services -cne $without -and -not (Set-SecureSetting -Serial $Serial -Name enabled_accessibility_services -Value $without)) { return $false }
    $wait = Wait-AccessibilityServiceState -Serial $Serial -Component $Component -Until { param($State) Test-ServiceOff $State } -TimeoutSeconds $TimeoutSeconds
    if (-not $wait.Reached) {
        $sdk = Get-DeviceSdk -Serial $Serial
        $note = if ($wait.State -and $wait.State.Binding -and $sdk -and $sdk -lt $OffEndsBindingSdk) { " $(Format-BindingNote $sdk)" } else { '' }
        Write-Warning "Android did not turn $Component off on $Serial within $TimeoutSeconds s ($(Format-ServiceState $wait.State)).$note"
    }
    $wait.Reached
}

# Puts the list of enabled accessibility services to $Services, which lists the component, and makes sure that Android
# has bound the component then. Android's accessibility manager binds an enabled service only when the set of enabled
# services changes, and never a service that it holds as crashed or binding (AOSP's
# AccessibilityManagerService.updateServicesLocked). A stop of the app, such as the Stop of Android's "Active apps"
# ('cmd activity stop-app'), or a crash, leaves its service crashed while the list still names it, and writing the same
# list again changes nothing. So the service goes off first (Disconnect-AccessibilityService) only when Android holds it
# as crashed or binding, or when the list already names it and Android does not run it; an off of a bound service would
# make Android clear its shortcut and button targets, and a bound service stays bound when the list changes around it.
# On Android 13 a binding does not end with an off ($OffEndsBindingSdk), so there the function waits for the bind
# instead. Then the list goes back, also when the off did not apply, and the function waits up to $TimeoutSeconds until
# the service is bound. Returns whether it is bound with the list as $Services; otherwise, also when the device does
# not answer, it warns with what it saw and what to do, and returns $false.
function Connect-AccessibilityService([string]$Serial, [string]$Services, [string]$Component = $KestrelAccessibilityService, [int]$TimeoutSeconds = 30) {
    if (-not (Test-ServiceListed -Services $Services -Component $Component)) { throw "The list '$Services' does not hold $Component." }
    $putBack = Format-PasteCommand -Serial $Serial -Command (Format-SecureSettingCommand -Name enabled_accessibility_services -Value $Services)
    $now = Read-ServiceList -Serial $Serial -Then "the list was not put back. Run: $putBack"
    if ($null -eq $now) { return $false }
    $state = Get-AccessibilityServiceState -Serial $Serial -Component $Component
    $sdk = $null
    if ($state -and $state.Binding) { $sdk = Get-DeviceSdk -Serial $Serial }
    $bindingEndsWithOff = -not ($sdk -and $sdk -lt $OffEndsBindingSdk)
    $stuck = $state -and ($state.Crashed -or ($state.Binding -and $bindingEndsWithOff) -or
        (-not $state.Binding -and -not $state.Bound -and (Test-SameServiceSet $now $Services)))
    $off = $true
    if ($stuck) { $off = Disconnect-AccessibilityService -Serial $Serial -Component $Component -TimeoutSeconds $TimeoutSeconds }
    if (($stuck -or $now -cne $Services) -and -not (Set-SecureSetting -Serial $Serial -Name enabled_accessibility_services -Value $Services)) { return $false }
    if (-not $off) { return $false }
    $wait = Wait-AccessibilityServiceState -Serial $Serial -Component $Component -Until { param($State) Test-ServiceConnected $State } -TimeoutSeconds $TimeoutSeconds
    if (-not $wait.Reached) {
        $note = if ($sdk -and -not $bindingEndsWithOff) { " $(Format-BindingNote $sdk)" } else { '' }
        Write-Warning ("Android did not bind $Component on $Serial within $TimeoutSeconds s after the list went back " +
            "($(Format-ServiceState $wait.State)).$note Turn it off and on in Settings > Accessibility.")
    }
    $wait.Reached
}
# The AVD name in the answer of an emulator's console to 'avd name': the answer's first line, trimmed (the console ends
# it with 'OK' on a line of its own). $Answer is adb's output as lines, or as one text; without an answer it is ''.
function ConvertFrom-AvdNameAnswer($Answer) {
    "$(@($Answer | ForEach-Object { "$_" -split '\r?\n' }) | Select-Object -First 1)".Trim()
}

# Whether an emulator console's answer to 'avd name' names exactly kestrel-api37, and not, for example,
# kestrel-api37-old.
function Test-KestrelAvdAnswer($Answer) { (ConvertFrom-AvdNameAnswer $Answer) -eq 'kestrel-api37' }

function Find-EmulatorSerial([string]$AvdName) {
    $adb = Get-Adb
    # Windows PowerShell 5.1 would stop at adb's first error line.
    $ErrorActionPreference = 'Continue'
    foreach ($line in (& $adb devices | Select-Object -Skip 1)) {
        if ($line -match '^(emulator-\d+)\s+device') {
            $serial = $Matches[1]
            $name = ConvertFrom-AvdNameAnswer @(& $adb -s $serial emu avd name 2>$null)
            if ($name -and $name -eq $AvdName) { return $serial }
        }
    }
    return $null
}

# Kestrel is tested only on kestrel-api37 and the Galaxy A71 (SM-A715F). The Galaxy S23+ (SM-S916*) is the
# owner's daily phone and is accepted only with -AllowDailyPhone (design spec, D20). Every other device is refused.
# It remembers what it accepted, so that Invoke-SettingsCommand runs its commands only on that device.
function Assert-TestDevice([string]$Serial, [switch]$AllowDailyPhone) {
    $adb = Get-Adb
    # Windows PowerShell 5.1 would stop at adb's first error line, before the second and third check.
    $ErrorActionPreference = 'Continue'
    $KestrelTestDevices.Remove($Serial)
    # A busy emulator sometimes answers a query with nothing, so a refusal is checked twice more before it counts.
    foreach ($try in 1..3) {
        if ($try -gt 1) { Start-Sleep -Seconds 2 }
        $state = "$(& $adb -s $Serial get-state 2>$null)".Trim()
        if ($state -ne 'device') { continue }
        $model = "$(& $adb -s $Serial shell getprop ro.product.model)".Trim()
        # Emulators report their AVD name here, however adb reaches them.
        $avd = "$(& $adb -s $Serial shell getprop ro.boot.qemu.avd_name)".Trim()
        $device = if ($avd -eq 'kestrel-api37') {
            @{ Property = 'ro.boot.qemu.avd_name'; Pattern = 'kestrel-api37' }
        } elseif ($model -like 'SM-A715F*') {
            @{ Property = 'ro.product.model'; Pattern = 'SM-A715F*' }
        } elseif ($model -like 'SM-S916*' -and $AllowDailyPhone) {
            @{ Property = 'ro.product.model'; Pattern = 'SM-S916*' }
        }
        if ($device) {
            $KestrelTestDevices[$Serial] = $device
            return
        }
    }
    if ($state -ne 'device') { throw "Device $Serial is not connected." }
    throw "Refusing to use $Serial (model '$model', AVD '$avd'). Kestrel is tested only on kestrel-api37 and the Galaxy A71."
}

# Adds up the results that Gradle's connected tests wrote as JUnit XML files below a folder: the numbers of tests,
# failures, errors and skipped tests. A folder that does not exist counts no tests.
function Get-TestResultCounts([string]$Folder) {
    $counts = [ordered]@{ Tests = 0; Failures = 0; Errors = 0; Skipped = 0 }
    if (Test-Path -LiteralPath $Folder) {
        foreach ($file in Get-ChildItem -LiteralPath $Folder -Recurse -Filter 'TEST-*.xml') {
            [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw
            foreach ($suite in @($xml.SelectNodes('//testsuite'))) {
                $counts.Tests += [int]$suite.tests
                $counts.Failures += [int]$suite.failures
                $counts.Errors += [int]$suite.errors
                $counts.Skipped += [int]$suite.skipped
            }
        }
    }
    return [pscustomobject]$counts
}

# Reads the answer of the voice-state hook, such as 'mode=listening work=idle ... session=open clips=off', into an ordered
# table of names and values. The answer never contains what Kestrel heard. Throws when the text is not such an answer.
function ConvertFrom-VoiceState([string]$Text) {
    $state = [ordered]@{}
    foreach ($pair in @("$Text".Trim() -split '\s+' | Where-Object { $_ })) {
        $name, $value = $pair -split '=', 2
        if (-not $name -or $null -eq $value) { throw "Not an answer of the voice-state hook: '$Text'" }
        $state[$name] = $value
    }
    if (-not ($state.Contains('mode') -and $state.Contains('session'))) { throw "Not an answer of the voice-state hook: '$Text'" }
    $state
}

# Returns whether the output of 'dumpsys accessibility' names a service of the package as a target of the accessibility
# shortcut, in its line 'shortcut key:{...}'. Android reads the setting a moment after it changes, and this line shows
# what it has read. One UI puts the list in brackets: 'shortcut key:{[a/b, c/d]}'.
function Test-ShortcutTarget([string[]]$Dump, [string]$Package) {
    foreach ($line in $Dump) {
        if ($line -match 'shortcut key:\{([^}]*)\}') {
            return [bool](@($Matches[1].Trim('[', ']', ' ') -split ',\s*') | Where-Object { $_ -like "$Package/*" })
        }
    }
    $false
}

# Returns the kinds of the package's entries in the output of 'dumpsys deviceidle whitelist', whose lines are
# '<kind>,<package>,<uid>': 'user' for the owner's choice of unrestricted battery use, which the shell can take away, and
# 'system' or 'system-excidle' for the system's own, which it cannot. Each kind lets the app start a foreground service
# from the background.
function Get-IdleAllowlistKind([string[]]$Dump, [string]$Package) {
    @(foreach ($line in $Dump) {
            if ($line.Trim() -match '^([a-z-]+),([^,]+),\d+$' -and $Matches[2] -eq $Package) { $Matches[1] }
        })
}

# Reads the package's lines 'rec start riid:<riid> ... silenced pack:<package>' and 'rec update ...' from the output of
# 'dumpsys audio', oldest first, and returns the riid of each and whether it says 'silenced' or 'not silenced'. Each
# recording has its own riid. On kestrel-api37, a recording sometimes has a 'rec update' line and no 'rec start' line.
function Get-RecordingLine([string[]]$Dump, [string]$Package) {
    $pattern = '\brec (?:start|update) riid:(?<riid>\d+) .*? (?<state>not silenced|silenced) pack:' + [regex]::Escape($Package) + '(?:\s|$)'
    foreach ($line in $Dump) {
        if ($line -match $pattern) { [pscustomobject]@{ Id = $Matches['riid']; Silenced = $Matches['state'] -eq 'silenced' } }
    }
}

# Returns the riids of the package's recordings in the output of 'dumpsys audio', each once.
function Get-RecordingId([string[]]$Dump, [string]$Package) {
    @(Get-RecordingLine -Dump $Dump -Package $Package | ForEach-Object { $_.Id } | Select-Object -Unique)
}

# Returns whether the package's newest recording in the output of 'dumpsys audio' is a new one, whose riid is not in
# $Before, and whether Android silences it: 'not silenced', 'silenced', or 'none' when the newest line of the package
# belongs to a recording in $Before or no line names the package.
function Get-NewRecordingState([string[]]$Dump, [string]$Package, [string[]]$Before) {
    $newest = Get-RecordingLine -Dump $Dump -Package $Package | Select-Object -Last 1
    if (-not $newest -or $newest.Id -in @($Before)) { return 'none' }
    if ($newest.Silenced) { 'silenced' } else { 'not silenced' }
}

# Returns the centre of the first node of a 'uiautomator dump' whose whole text is the text, in any letter case, because
# some versions of Android show a notification's buttons in capitals: an object with X and Y. Returns $null when no node
# has that text, or when the dump is not XML, as when uiautomator could not read the screen. A dump can hold the text of
# the owner's notifications, so the scripts only search it and never print it.
function Get-UiNodeCentre([string]$Dump, [string]$Text) {
    $xml = New-Object System.Xml.XmlDocument
    try { $xml.LoadXml($Dump) } catch { return $null }
    foreach ($node in @($xml.SelectNodes('//node'))) {
        if ($node.GetAttribute('text') -eq $Text -and $node.GetAttribute('bounds') -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
            return [pscustomobject]@{
                X = [int][math]::Floor(([int]$Matches[1] + [int]$Matches[3]) / 2)
                Y = [int][math]::Floor(([int]$Matches[2] + [int]$Matches[4]) / 2)
            }
        }
    }
    $null
}

# Runs a program, such as adb, and waits at most $TimeoutSeconds for it to end, because an adb call to a frozen emulator
# can wait without a limit. Each argument reaches the program as one word. Returns an object: Completed, whether the
# program ended in time; ExitCode, null when it did not; Output, its standard output as one text; and Id, the ID of its
# process. A program that has not ended in time is stopped. Its error output is dropped, as adb's complaints while a
# device starts.
function Invoke-TimedProcess([string]$FilePath, [string[]]$ArgumentList = @(), [int]$TimeoutSeconds) {
    # Windows splits a command line into words at spaces outside double quotes; a backslash before a quote escapes it.
    $words = foreach ($argument in $ArgumentList) {
        if ($argument -and $argument -notmatch '[\s"]') { $argument } else { '"' + (($argument -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"' }
    }
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $FilePath
    $info.Arguments = @($words) -join ' '
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::Start($info)
    try {
        # Both streams are read while the program runs, so that a full pipe never stops it.
        $output = $process.StandardOutput.ReadToEndAsync()
        $null = $process.StandardError.ReadToEndAsync()
        $completed = $process.WaitForExit($TimeoutSeconds * 1000)
        if (-not $completed) {
            # Kill() only starts the end of the process, so the function waits for it: a program that has not ended in
            # time is stopped when the function returns.
            try {
                $process.Kill()
                $null = $process.WaitForExit(5000)
            } catch {
                Write-Verbose "Could not stop $FilePath ($($_.Exception.Message))."
            }
        }
        # A process that adb starts, such as its server, can keep the output open after adb has ended.
        $text = if ($completed -and $output.Wait(5000)) { $output.Result } else { '' }
        [pscustomobject]@{ Completed = $completed; ExitCode = $(if ($completed) { $process.ExitCode } else { $null }); Output = $text; Id = $process.Id }
    } finally {
        $process.Dispose()
    }
}

# Returns the qemu process that runs the emulator with the AVD $AvdName, or nothing. While an emulator starts or closes,
# adb does not list it, but its process runs.
function Get-EmulatorProcess([string]$AvdName) {
    Get-CimInstance Win32_Process -Filter "Name LIKE 'qemu-system%'" |
        Where-Object { $_.CommandLine -match "-avd\s+$([regex]::Escape($AvdName))(\s|$)" }
}

# Whether the emulator's setting "Enable clipboard sharing" (Extended controls > Settings) is on, from its stored $Value:
# the emulator treats a missing value as on (winsys-qt.cpp reads it with the default true), and only 'false' as off.
function ConvertFrom-ClipboardSharingValue($Value) {
    return ($null -eq $Value) -or ("$Value" -ne 'false')
}

# Whether the emulator's setting "Enable clipboard sharing" is on now. It is one setting for every AVD of this Windows
# user, and only an emulator with a window uses it.
function Test-EmulatorClipboardSharing {
    $value = (Get-ItemProperty 'HKCU:\Software\Android Open Source Project\Emulator\set' -Name clipboardSharing `
            -ErrorAction SilentlyContinue).clipboardSharing
    ConvertFrom-ClipboardSharingValue $value
}

# The window type in the answer of the emulator's console to 'avd windowtype' ('windowtype=headless' for an emulator
# started with -no-window, 'windowtype=qtwindow' for one with its window). Throws for any other answer.
function ConvertFrom-WindowTypeAnswer([string[]]$Lines) {
    foreach ($line in $Lines) {
        if ("$line".Trim() -match '^windowtype=(headless|qtwindow)$') { return $Matches[1] }
    }
    throw "Not an answer of the emulator's console to 'avd windowtype': '$($Lines -join ' ')'"
}

# Whether the emulator $Serial runs without a window ('headless') or with one ('qtwindow'), as its console answers.
function Get-EmulatorWindowType([string]$Serial) {
    $adb = Get-Adb
    # Windows PowerShell 5.1 would stop at adb's first error line.
    $ErrorActionPreference = 'Continue'
    ConvertFrom-WindowTypeAnswer @(& $adb -s $Serial emu avd windowtype 2>$null)
}

# The warning for a running emulator whose window type is $WindowType (Get-EmulatorWindowType), when
# scripts\start-emulator.ps1 was asked for a window ($Window) or not, and the clipboard sharing is $ClipboardSharing;
# $null when it runs as asked. $CloseCommand closes the emulator.
function Get-EmulatorWindowWarning([string]$AvdName, [string]$WindowType, [bool]$Window, [bool]$ClipboardSharing,
    [string]$CloseCommand) {
    $headless = $WindowType -eq 'headless'
    if ($Window -and $headless) {
        return "$AvdName runs without a window. To get one, close it with $CloseCommand and run " +
            'scripts\start-emulator.ps1 -Window.'
    }
    if (-not $Window -and -not $headless -and $ClipboardSharing) {
        return "$AvdName runs with its window while the emulator's setting 'Enable clipboard sharing' is on, so the " +
            "emulator links Android's clipboard to Windows' clipboard, and the clipboard tests are unreliable. Close it with " +
            "$CloseCommand and run scripts\start-emulator.ps1 without -Window."
    }
    return $null
}

# Closes the emulator kestrel-api37 on $Serial and starts it again through scripts\start-emulator.ps1, with its window
# when $Window, and from a cold boot when $ColdBoot. It closes nothing unless the console names kestrel-api37. A start
# that is not cold brings Android back from the Quick Boot snapshot that the emulator saves when it closes, so its apps
# and settings are as they were. Returns nothing when kestrel-api37 answers again with the same serial and the asked
# window type, and otherwise why not.
function Restart-KestrelEmulator([string]$Serial, [bool]$Window, [switch]$ColdBoot) {
    $adb = Get-Adb
    $name = Invoke-TimedProcess -FilePath $adb -ArgumentList '-s', $Serial, 'emu', 'avd', 'name' -TimeoutSeconds 30
    if (-not $name.Completed -or -not (Test-KestrelAvdAnswer $name.Output)) {
        return "$Serial is not the emulator kestrel-api37, so it was not closed"
    }
    $kill = Invoke-TimedProcess -FilePath $adb -ArgumentList '-s', $Serial, 'emu', 'kill' -TimeoutSeconds 30
    if (-not $kill.Completed) { return "'adb emu kill' did not return within 30 s, so the emulator may have frozen" }
    # The emulator saves its Quick Boot snapshot before it ends.
    $deadline = (Get-Date).AddMinutes(2)
    while ((Get-EmulatorProcess 'kestrel-api37') -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 2 }
    $left = @(Get-EmulatorProcess 'kestrel-api37')
    if ($left) { return "kestrel-api37 did not close within 2 minutes of 'adb emu kill' (its qemu process has the ID $($left[0].ProcessId))" }
    try {
        $started = "$(& "$PSScriptRoot\..\start-emulator.ps1" -ColdBoot:$ColdBoot -Window:$Window | Select-Object -Last 1)".Trim()
    } catch {
        return "the emulator did not start again: $($_.Exception.Message)"
    }
    if ($started -ne $Serial) { return "kestrel-api37 started again as '$started', not as $Serial" }
    # The serial now names a new emulator, so the device check runs again before any other command.
    try { Assert-TestDevice -Serial $Serial } catch { return $_.Exception.Message }
    $type = Get-EmulatorWindowType $Serial
    $wanted = if ($Window) { 'qtwindow' } else { 'headless' }
    if ($type -ne $wanted) { return "kestrel-api37 started again as $type, not as $wanted" }
}
# Reads the index of a stream's volume from the answer of 'cmd media_session volume --stream <stream> --get', whose line
# reads '[V] volume is 7 in range [0..25]'. Throws when the answer has no such line.
function Get-StreamVolume([string[]]$Lines) {
    foreach ($line in $Lines) {
        if ($line -match 'volume is (\d+) in range') { return [int]$Matches[1] }
    }
    throw "Not an answer of cmd media_session volume: '$($Lines -join ' ')'"
}

# Returns the IDs of the users called [Name] in the output of 'pm list users', whose lines read
# 'UserInfo{10:KestrelTest:1030}', such as the work profile that the command tests make.
function Get-UserIdsNamed([string[]]$Lines, [string]$Name) {
    foreach ($line in $Lines) {
        if ($line -match 'UserInfo\{(\d+):([^:}]*):' -and $Matches[2] -ceq $Name) { [int]$Matches[1] }
    }
}

# Returns the permissions of the list that the output of 'dumpsys package <package>' shows as granted, each once, in the
# order of the list. A package that is not installed has none.
function Get-GrantedPermissions([string[]]$Dump, [string[]]$Permissions) {
    $text = $Dump -join "`n"
    foreach ($permission in $Permissions) {
        if ($text -match "$([regex]::Escape($permission)): granted=true") { $permission }
    }
}

# Reads, from the output of 'dumpsys package <package>', whether each runtime permission of the list is granted, and
# whether it has the flags USER_SET and USER_FIXED. A permission that the output does not show, as for a package that is
# not installed, is not granted and has neither flag. Returns an ordered dictionary from the permission to its state.
function Get-PermissionStates([string[]]$Dump, [string[]]$Permissions) {
    $text = $Dump -join "`n"
    $states = [ordered]@{}
    foreach ($permission in $Permissions) {
        $state = @{ Granted = $false; UserSet = $false; UserFixed = $false }
        if ($text -match "$([regex]::Escape($permission)): granted=(true|false)(?:, flags=\[([^\]]*)\])?") {
            $flags = @("$($Matches[2])" -split '\|' | ForEach-Object { $_.Trim() })
            $state = @{ Granted = $Matches[1] -eq 'true'; UserSet = 'USER_SET' -in $flags; UserFixed = 'USER_FIXED' -in $flags }
        }
        $states[$permission] = $state
    }
    $states
}

# Returns the device commands that put the runtime permissions of a package back from the states $Now to the states
# $Before, both read by Get-PermissionStates: a grant or a revoke where the grant differs, then a clear of the flags
# USER_SET and USER_FIXED that were not set before, when one is set now or the grant changes, which can set them. A
# revoke ends the package's process.
function Get-PermissionRestoreCommands([string]$Package, $Before, $Now) {
    foreach ($permission in $Before.Keys) {
        $was = $Before[$permission]
        $is = $Now[$permission]
        $changed = $is.Granted -ne $was.Granted
        if ($changed) { "pm $(if ($was.Granted) { 'grant' } else { 'revoke' }) $Package $permission" }
        $flags = @(
            if (-not $was.UserSet -and ($changed -or $is.UserSet)) { 'user-set' }
            if (-not $was.UserFixed -and ($changed -or $is.UserFixed)) { 'user-fixed' }
        )
        if ($flags.Count -gt 0) { "pm clear-permission-flags $Package $permission $($flags -join ' ')" }
    }
}

# The instrumentation argument that selects command device tests: the whole package dev.kestrel.app.commands, or the
# classes of [Class], each a simple class name with an optional '#method'. Throws for any other name, so that no name can
# reach other packages or the shell.
function Get-CommandTestSelection([string[]]$Class) {
    if ($Class.Count -eq 0) { return 'package=dev.kestrel.app.commands' }
    $names = foreach ($name in $Class) {
        if ($name -cnotmatch '^[A-Z][A-Za-z0-9]*(#[a-z][A-Za-z0-9]*)?$') { throw "Not a test class of dev.kestrel.app.commands: '$name'" }
        "dev.kestrel.app.commands.$name"
    }
    "class=$($names -join ',')"
}

# Returns the largest _id in the output of 'content query --uri <uri> --projection _id', whose lines read
# 'Row: 0 _id=12', or 0 when it has no row, as when the device answers 'No result found.'.
function Get-LargestRowId([string[]]$Rows) {
    $largest = [long]0
    foreach ($line in $Rows) {
        if ($line -match '\b_id=(\d+)') { $largest = [math]::Max($largest, [long]$Matches[1]) }
    }
    $largest
}

# Whether the output of 'dumpsys telephony.registry' shows a call in one of its SIM slots: a line 'mCallState=N' whose N
# is not 0, Android's CALL_STATE_IDLE (1 rings, 2 is a call that is dialled or active).
function Test-CallActive([string[]]$Registry) {
    @($Registry | Where-Object { "$_".Trim() -match '^mCallState=(\d+)$' -and $Matches[1] -ne '0' }).Count -gt 0
}

# Ends a call that the device shows, with the end-call key, which hangs up an ordinary call through Android, and returns
# whether the device shows no call afterwards, within 10 s. The emulator's console ('gsm cancel') cannot end a call that
# the phone placed: on kestrel-api37 (observed) Android kept such a call active for minutes after the console dropped it.
# Android does not end an emergency call with this key, and no test or script places one. It sends the key only while a
# call shows, because without one the key turns the screen off. It warns when a call stays, or when it cannot check.
function Stop-DeviceCall([string]$Serial) {
    try {
        if (-not (Test-CallActive -Registry (Invoke-SettingsCommand -Serial $Serial -Command 'dumpsys telephony.registry'))) { return $true }
        Invoke-SettingsCommand -Serial $Serial -Command 'input keyevent KEYCODE_ENDCALL' | Out-Null
        foreach ($try in 1..20) {
            Start-Sleep -Milliseconds 500
            if (-not (Test-CallActive -Registry (Invoke-SettingsCommand -Serial $Serial -Command 'dumpsys telephony.registry'))) { return $true }
        }
        Write-Warning "A call is still active on $Serial. End it on the phone app's screen."
    } catch {
        Write-Warning "Could not end the call on ${Serial}: $($_.Exception.Message) End it on the phone app's screen."
    }
    $false
}

# Returns the Android user folder, which holds the debug keystore, as the Android Gradle plugin finds it, so that a release
# build signed by Get-DebugSigningEnvironment has the debug build's key. The plugin takes ANDROID_USER_HOME itself, and
# the folder .android in the deprecated ANDROID_PREFS_ROOT and ANDROID_SDK_HOME, but only when their own folder exists. It
# refuses to build when these name different folders, and so does this function. Without any of them, the folder is the
# user profile's .android.
function Get-AndroidUserHome {
    $folders = @(
        if ($env:ANDROID_USER_HOME) { $env:ANDROID_USER_HOME }
        foreach ($name in 'ANDROID_PREFS_ROOT', 'ANDROID_SDK_HOME') {
            $parent = [Environment]::GetEnvironmentVariable($name)
            if ($parent -and (Test-Path -LiteralPath $parent -PathType Container)) { Join-Path $parent '.android' }
        }
    )
    # Windows compares paths without case, and Sort-Object -Unique does too.
    $distinct = @($folders | ForEach-Object { [IO.Path]::GetFullPath($_).TrimEnd('\') } | Sort-Object -Unique)
    if ($distinct.Count -gt 1) {
        throw ("ANDROID_USER_HOME, ANDROID_PREFS_ROOT and ANDROID_SDK_HOME name different Android user folders ($($distinct -join ', ')), " +
            'so the Android Gradle plugin refuses to build. Keep only ANDROID_USER_HOME.')
    }
    if ($folders.Count -gt 0) { return $folders[0] }
    Join-Path $env:USERPROFILE '.android'
}

# Returns the variables with which Gradle signs Kestrel's release build (design spec, section 17), set to this computer's
# debug key: the keystore that the Android Gradle plugin creates in the Android user folder, with its fixed alias and
# passwords. A release build signed with it installs over the debug build with adb install -r and keeps Kestrel's data.
# The release key never comes here.
function Get-DebugSigningEnvironment([string]$AndroidUserHome = $(Get-AndroidUserHome)) {
    $keystore = Join-Path $AndroidUserHome 'debug.keystore'
    if (-not (Test-Path -LiteralPath $keystore -PathType Leaf)) {
        throw "No debug keystore at $keystore. Build the debug app once, for example with scripts\install.ps1."
    }
    [ordered]@{
        KESTREL_KEYSTORE          = $keystore
        KESTREL_KEYSTORE_PASSWORD = 'android'
        KESTREL_KEY_ALIAS         = 'androiddebugkey'
        KESTREL_KEY_PASSWORD      = 'android'
    }
}