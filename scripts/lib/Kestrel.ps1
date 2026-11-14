# Shared helpers for Kestrel's scripts. Dot-source this file; it changes only the current process.

function Use-KestrelToolchain {
    # Gradle runs on the JDK that comes with Android Studio (design spec, section 6).
    $studioJdk = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path $studioJdk) {
        $env:JAVA_HOME = $studioJdk
    } elseif (-not $env:JAVA_HOME) {
        throw 'Set JAVA_HOME to a JDK 17 or newer.'
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

# Runs a settings command on a device and returns the lines that it printed. The device prints a marker only after a
# command that worked, which tells an empty answer from a failed command. A busy device, or one whose system is still
# starting, sometimes fails the command, so it is repeated before it fails for good with the device's last complaint.
function Invoke-SettingsCommand([string]$Serial, [string]$Command) {
    $adb = Get-Adb
    # Keeps adb's error lines as part of the answer, so that they neither stop the script nor fill the console.
    $ErrorActionPreference = 'Continue'
    $complaint = ''
    foreach ($try in 1..3) {
        if ($try -gt 1) { Start-Sleep -Seconds 2 }
        $answer = @(& $adb -s $Serial shell "$Command && echo kestrel-end" 2>&1)
        $errors = @($answer | Where-Object { $_ -is [System.Management.Automation.ErrorRecord] })
        $lines = @($answer | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] } | ForEach-Object { "$_".TrimEnd("`r") })
        if ($lines.Count -gt 0 -and $lines[-1].Trim() -eq 'kestrel-end') { return $lines | Select-Object -First ($lines.Count - 1) }
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

# Puts back a secure setting that Get-SecureSetting read, deleting it for 'null', and returns whether the device took it.
# When it did not, it warns with a command that puts the setting back when it is pasted into PowerShell.
function Set-SecureSetting([string]$Serial, [string]$Name, [string]$Value) {
    $adb = Get-Adb
    $ErrorActionPreference = 'Continue'
    # A single quote would end the device shell's quoting, so it becomes '\''.
    $command = if ($Value -eq 'null') { "settings delete secure $Name" } else { "settings put secure $Name '$($Value -replace "'", "'\''")'" }
    & $adb -s $Serial shell $command 2>$null | Out-Null
    $reason = ''
    $now = try { Get-SecureSetting -Serial $Serial -Name $Name } catch { $reason = " ($($_.Exception.Message))"; $null }
    if ($now -ceq $Value) { return $true }
    $literal = { param($text) "'" + ($text -replace "'", "''") + "'" }
    Write-Warning "Could not put back the setting $Name of $Serial$reason. Run: & $(& $literal $adb) -s $Serial shell $(& $literal $command)"
    $false
}

# Reads the secure settings that belong to accessibility: the list of enabled services, the accessibility switch, and
# the shortcuts that start a service, which Android clears for a service that it turns off. Returns names and values.
function Get-AccessibilitySettings([string]$Serial) {
    $settings = [ordered]@{}
    foreach ($line in (Invoke-SettingsCommand -Serial $Serial -Command 'settings list secure')) {
        if ($line -match '^([^=]*(accessibility|touch_exploration)[^=]*)=(.*)$') { $settings[$Matches[1]] = $Matches[3] }
    }
    $settings
}

# Puts back the settings that Get-AccessibilitySettings read, and deletes those that were not there. The list of enabled
# services goes first, so that the shortcuts point to services that are on again. Returns whether everything is back.
function Restore-AccessibilitySettings([string]$Serial, $Saved) {
    try {
        $now = Get-AccessibilitySettings -Serial $Serial
    } catch {
        $before = @($Saved.Keys | ForEach-Object { "$_=$($Saved[$_])" }) -join "`n"
        Write-Warning "Could not read the accessibility settings of $Serial ($($_.Exception.Message)), so they were not put back. Before, they were:`n$before"
        return $false
    }
    $back = $true
    $names = @('enabled_accessibility_services', 'accessibility_enabled') + @(@($Saved.Keys) + @($now.Keys) | Sort-Object -Unique)
    foreach ($name in ($names | Select-Object -Unique)) {
        $value = if ($Saved.Contains($name)) { $Saved[$name] } else { 'null' }
        $current = if ($now.Contains($name)) { $now[$name] } else { 'null' }
        if ($value -cne $current -and -not (Set-SecureSetting -Serial $Serial -Name $name -Value $value)) { $back = $false }
    }
    $back
}

function Find-EmulatorSerial([string]$AvdName) {
    $adb = Get-Adb
    # Windows PowerShell 5.1 would stop at adb's first error line.
    $ErrorActionPreference = 'Continue'
    foreach ($line in (& $adb devices | Select-Object -Skip 1)) {
        if ($line -match '^(emulator-\d+)\s+device') {
            $serial = $Matches[1]
            $name = & $adb -s $serial emu avd name 2>$null | Select-Object -First 1
            if ($name -and $name.Trim() -eq $AvdName) { return $serial }
        }
    }
    return $null
}

# Kestrel is tested only on kestrel-api37 and the Galaxy A71 (SM-A715F). The Galaxy S23+ (SM-S916*) is the
# owner's daily phone and is accepted only with -AllowDailyPhone (design spec, D20). Every other device is refused.
function Assert-TestDevice([string]$Serial, [switch]$AllowDailyPhone) {
    $adb = Get-Adb
    # Windows PowerShell 5.1 would stop at adb's first error line, before the second and third check.
    $ErrorActionPreference = 'Continue'
    # A busy emulator sometimes answers a query with nothing, so a refusal is checked twice more before it counts.
    foreach ($try in 1..3) {
        if ($try -gt 1) { Start-Sleep -Seconds 2 }
        $state = "$(& $adb -s $Serial get-state 2>$null)".Trim()
        if ($state -ne 'device') { continue }
        $model = "$(& $adb -s $Serial shell getprop ro.product.model)".Trim()
        # Emulators report their AVD name here, however adb reaches them.
        $avd = "$(& $adb -s $Serial shell getprop ro.boot.qemu.avd_name)".Trim()
        if ($avd -eq 'kestrel-api37' -or $model -like 'SM-A715F*') { return }
        if ($model -like 'SM-S916*' -and $AllowDailyPhone) { return }
    }
    if ($state -ne 'device') { throw "Device $Serial is not connected." }
    throw "Refusing to use $Serial (model '$model', AVD '$avd'). Kestrel is tested only on kestrel-api37 and the Galaxy A71."
}
