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
# command that worked, which tells an empty answer from a failed command. A busy device, or one whose system is still
# starting, sometimes fails the command, so it is repeated before it fails for good with the device's last complaint.
# The command runs only on the device that Assert-TestDevice accepted for the serial, and throws at once on another one.
function Invoke-SettingsCommand([string]$Serial, [string]$Command) {
    $adb = Get-Adb
    $guarded = Format-TestDeviceCommand -Serial $Serial -Command "$Command && echo kestrel-end"
    # Keeps adb's error lines as part of the answer, so that they neither stop the script nor fill the console.
    $ErrorActionPreference = 'Continue'
    $complaint = ''
    foreach ($try in 1..3) {
        if ($try -gt 1) { Start-Sleep -Seconds 2 }
        $answer = @(& $adb -s $Serial shell $guarded 2>&1)
        $errors = @($answer | Where-Object { $_ -is [System.Management.Automation.ErrorRecord] })
        $lines = @($answer | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] } | ForEach-Object { "$_".TrimEnd("`r") })
        if ($lines.Count -gt 0 -and $lines[-1].Trim() -eq 'kestrel-end') { return $lines | Select-Object -First ($lines.Count - 1) }
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

# Puts back a secure setting that Get-SecureSetting read, deleting it for 'null', and returns whether the device took it.
# When it did not, it warns with a command that puts the setting back when it is pasted into PowerShell.
function Set-SecureSetting([string]$Serial, [string]$Name, [string]$Value) {
    # A single quote would end the device shell's quoting, so it becomes '\''.
    $command = if ($Value -eq 'null') { "settings delete secure $Name" } else { "settings put secure $Name '$($Value -replace "'", "'\''")'" }
    $reason = ''
    $now = try {
        Invoke-SettingsCommand -Serial $Serial -Command $command | Out-Null
        Get-SecureSetting -Serial $Serial -Name $Name
    } catch {
        $reason = " ($($_.Exception.Message))"
        $null
    }
    if ($now -ceq $Value) { return $true }
    $literal = { param($text) "'" + ($text -replace "'", "''") + "'" }
    Write-Warning "Could not put back the setting $Name of $Serial$reason. Run: & $(& $literal (Get-Adb)) -s $Serial shell $(& $literal $command)"
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

# Returns the arguments of a Gradle call that name a task of the Android Gradle plugin that acts on a device: installs,
# uninstalls, connected (on-device) tests and device checks. Options, which start with '-', are not tasks. Gradle also
# accepts abbreviated task names, which this check misses, so scripts\gradle.ps1 has a second guard.
function Get-DeviceTaskArgument([string[]]$Arguments) {
    @($Arguments | Where-Object { $_ -notlike '-*' -and ($_ -split ':')[-1] -match 'install|connected|devicecheck' })
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
# what it has read.
function Test-ShortcutTarget([string[]]$Dump, [string]$Package) {
    foreach ($line in $Dump) {
        if ($line -match 'shortcut key:\{([^}]*)\}') {
            return [bool](@($Matches[1] -split ',\s*') | Where-Object { $_ -like "$Package/*" })
        }
    }
    $false
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

# Returns the largest _id in the output of 'content query --uri <uri> --projection _id', whose lines read
# 'Row: 0 _id=12', or 0 when it has no row, as when the device answers 'No result found.'.
function Get-LargestRowId([string[]]$Rows) {
    $largest = [long]0
    foreach ($line in $Rows) {
        if ($line -match '\b_id=(\d+)') { $largest = [math]::Max($largest, [long]$Matches[1]) }
    }
    $largest
}