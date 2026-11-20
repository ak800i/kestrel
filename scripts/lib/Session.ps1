# Helpers for the A71 sessions (scripts\a71-session.ps1, scripts\a71-commands.ps1 and scripts\a71-providers.ps1; plan
# 2D, design D8) and for scripts\listen-check.ps1. They read Android's dumps and logs on this computer and keep only
# numbers, intent actions, the short names of exception classes and Kestrel's own fixed descriptions, never what the
# owner said or other apps' text.

# Appends a line to the session's results: the time, the step and the text, separated by tabs, and creates the file's
# folder. The text must hold no private data, such as what Kestrel heard.
function Add-SessionResult([string]$Path, [string]$Step, [string]$Text) {
    $folder = Split-Path -Parent $Path
    if ($folder) { New-Item -ItemType Directory -Force -Path $folder | Out-Null }
    # In the invariant culture, whose time separator is always ':'.
    $time = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss', [Globalization.CultureInfo]::InvariantCulture)
    [IO.File]::AppendAllText($Path, "$time`t$Step`t$Text`n", [Text.UTF8Encoding]::new($false))
}

# Asks $Prompt until the answer, in any letter case, is a key of $Choices, and returns that key's value; any other
# answer, also an empty one, asks again with $Again, so that a stray Enter or a typo is never taken as an answer. $Read
# reads one answer; the tests give their own.
function Read-Choice([string]$Prompt, [hashtable]$Choices, [string]$Again, [scriptblock]$Read = { param($Text) Read-Host $Text }) {
    $text = $Prompt
    while ($true) {
        $answer = "$(& $Read $text)".Trim().ToLowerInvariant()
        if ($answer -and $Choices.ContainsKey($answer)) { return $Choices[$answer] }
        $text = $Again
    }
}

# A yes-or-no question of the sessions (Read-Choice): y or yes gives 'y', n or no gives 'n', in any letter case.
function Read-YesNoAnswer([string]$Prompt, [scriptblock]$Read = { param($Text) Read-Host $Text }) {
    Read-Choice -Prompt $Prompt -Choices @{ y = 'y'; yes = 'y'; n = 'n'; no = 'n' } -Again 'Type y or yes, or n or no, and press Enter' -Read $Read
}

# Takes the emulator's lock (process rule 9) by creating its file, which fails when the file exists, so that two
# processes cannot both take it; the file says who took it and when. Returns $true when it took the lock, and $false when
# the file exists.
function Enter-EmulatorLock([string]$Path, [string]$Owner) {
    $folder = Split-Path -Parent $Path
    if ($folder -and -not (Test-Path -LiteralPath $folder)) { New-Item -ItemType Directory -Force -Path $folder | Out-Null }
    $time = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss', [Globalization.CultureInfo]::InvariantCulture)
    try {
        $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    } catch {
        # PowerShell wraps the IOException of a file that exists in a MethodInvocationException.
        $failure = $_.Exception
        while ($failure -and $failure -isnot [IO.IOException]) { $failure = $failure.InnerException }
        if ($failure -and (Test-Path -LiteralPath $Path)) { return $false }
        throw
    }
    try {
        $bytes = [Text.UTF8Encoding]::new($false).GetBytes("$Owner, since $time`n")
        $stream.Write($bytes, 0, $bytes.Length)
    } finally {
        $stream.Dispose()
    }
    $true
}

# The memory of a process (PSS) in MB, from 'dumpsys meminfo <package>': its line 'TOTAL PSS:', or else the row 'TOTAL' of
# its table, both in kB. Returns $null when the dump has neither, as when the process does not run.
function Get-TotalPss([string[]]$Dump) {
    foreach ($line in $Dump) {
        if ($line -match 'TOTAL PSS:\s+([\d,]+)') { return [int][math]::Round([double]($Matches[1] -replace ',', '') / 1024) }
    }
    foreach ($line in $Dump) {
        if ($line -match '^\s*TOTAL\s+([\d,]+)\s') { return [int][math]::Round([double]($Matches[1] -replace ',', '') / 1024) }
    }
    $null
}

# The user ID of a package, from 'dumpsys package <package>', or $null. Android 13 prints 'userId=10234', and later
# versions, such as Android 17, print 'appId=10234'.
function Get-AppUserId([string[]]$Dump) {
    foreach ($line in $Dump) {
        if ($line -match '\b(?:userId|appId)=(\d+)') { return [int]$Matches[1] }
    }
    $null
}

# The user ID of $Package from 'pm list packages -U <package>', which lists every package whose name contains the text,
# such as 'package:dev.kestrel uid:10234', or $null.
function Get-ListedUserId([string[]]$List, [string]$Package) {
    foreach ($line in $List) {
        if ($line -match "^\s*package:$([regex]::Escape($Package)) uid:(\d+)") { return [int]$Matches[1] }
    }
    $null
}

# How batterystats names the UID of an app of the owner's user: 'u0a234' for the user ID 10234.
function ConvertTo-UidLabel([int]$UserId) {
    "u0a$($UserId - 10000)"
}

# An app's battery use from 'dumpsys batterystats --charged' (research r10): the estimated mAh of the UID, the
# battery's capacity, the share of the battery per hour over $Hours, in percent with two decimals, and the breakdown that
# the UID's line gives in brackets, such as 'cpu=54.2 wakelock=0.04 audio=12.3', the parts' mAh without their times
# (analysis of the A71 session of sub-project 2, item 10). Returns $null when the dump has no estimate for the UID or no
# capacity.
function Get-BatteryUse([string[]]$Dump, [string]$Uid, [double]$Hours) {
    $capacity = $null
    $used = $null
    $breakdown = ''
    foreach ($line in $Dump) {
        if ($null -eq $capacity -and $line -match '^\s*Capacity:\s*(\d+)') { $capacity = [double]$Matches[1] }
        if ($null -eq $used -and $line -match "^\s*Uid\s+$([regex]::Escape($Uid)):\s*([\d.]+)(.*)$") {
            $used = [double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture)
            # The times in brackets inside the breakdown, such as '(13m 1s 405ms)', go first.
            $parts = [regex]::Matches(($Matches[2] -replace '\([^()]*\)', ''), '(?<![\w:])([\w:]+)=([\d.]+)')
            $breakdown = @($parts | ForEach-Object { "$($_.Groups[1].Value)=$($_.Groups[2].Value)" }) -join ' '
        }
    }
    if ($null -eq $used -or -not $capacity -or $Hours -le 0) { return $null }
    [pscustomobject]@{
        MilliampHours = $used
        Capacity = $capacity
        PercentPerHour = [math]::Round($used / $Hours / $capacity * 100, 2)
        Breakdown = $breakdown
    }
}

# Why 'dumpsys batterystats --charged' gave Get-BatteryUse no estimate for the UID $Uid, as a text for the results, or
# $null when it has one. The emulator has an estimate; a device whose power profile has no values may print no section
# of estimates at all.
function Get-BatteryUseGap([string[]]$Dump, [string]$Uid) {
    if (-not ($Dump -match 'Estimated power use')) { return 'batterystats printed no estimates of power use, as on a device whose power profile has no values' }
    $capacity = @($Dump | ForEach-Object { if ($_ -match '^\s*Capacity:\s*(\d+)') { [double]$Matches[1] } }) | Select-Object -First 1
    if ($null -eq $capacity) { return 'batterystats named no battery capacity' }
    if ($capacity -eq 0) { return 'batterystats gave the battery a capacity of 0 mAh' }
    if (-not ($Dump -match "^\s*Uid\s+$([regex]::Escape($Uid)):\s*[\d.]")) { return "batterystats had no estimate for Kestrel's UID $Uid" }
    $null
}

# Remembers in the file $Path that the session ended Kestrel's process with a crash on the device $Serial at $Time, so
# that a later step, also in a later run of the session, can wait until Android restarts Kestrel after the next crash:
# Android does not restart an app that crashed twice within 2 minutes.
function Write-SessionCrash([string]$Path, [string]$Serial, [datetime]$Time) {
    $folder = Split-Path -Parent $Path
    if ($folder) { New-Item -ItemType Directory -Force -Path $folder | Out-Null }
    $utc = $Time.ToUniversalTime().ToString('o', [Globalization.CultureInfo]::InvariantCulture)
    [IO.File]::WriteAllText($Path, "$Serial`t$utc`n", [Text.UTF8Encoding]::new($false))
}

# The time of the session's last crash of Kestrel on the device $Serial, from the file of Write-SessionCrash, in UTC, or
# $null when the file is missing, unreadable or names another device.
function Read-SessionCrash([string]$Path, [string]$Serial) {
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    $parts = "$(Get-Content -LiteralPath $Path -Raw)".Trim() -split "`t"
    if ($parts.Count -ne 2 -or $parts[0] -cne $Serial) { return $null }
    $time = [datetime]::MinValue
    $styles = [Globalization.DateTimeStyles]::RoundtripKind
    if (-not [datetime]::TryParse($parts[1], [Globalization.CultureInfo]::InvariantCulture, $styles, [ref]$time)) { return $null }
    $time.ToUniversalTime()
}

# How many whole seconds to wait at $Now until $Seconds have passed since $LastCrash; 0 without a crash or when they have.
function Get-CrashWait($LastCrash, [datetime]$Now, [int]$Seconds = 125) {
    if ($null -eq $LastCrash) { return 0 }
    $left = $Seconds - ($Now.ToUniversalTime() - ([datetime]$LastCrash).ToUniversalTime()).TotalSeconds
    if ($left -le 0) { 0 } else { [int][math]::Ceiling($left) }
}

# The mode of the app op $Op in the answer of 'cmd appops get <package> <op>', such as 'allow' from
# 'RUN_ANY_IN_BACKGROUND: allow; time=+1m2s ago', or $null when the package has no entry for the op ('No operations.').
# A line of the UID's mode ('Uid mode: ...') does not count.
function Get-AppOpMode([string[]]$Answer, [string]$Op) {
    foreach ($line in $Answer) {
        if ($line -match "^\s*$([regex]::Escape($Op)):\s*(\w+)") { return $Matches[1] }
    }
    $null
}

# The mode that puts back Kestrel's battery restriction (the app op RUN_ANY_IN_BACKGROUND) as the answer of
# 'cmd appops get' showed it: 'ignore' when it was restricted, otherwise 'allow', the op's normal mode, also when the op
# had no entry. Never 'default', which Android counts as restricted too (prototype P2).
function Get-BackgroundOpRestore([string[]]$Answer) {
    if ((Get-AppOpMode -Answer $Answer -Op RUN_ANY_IN_BACKGROUND) -in 'ignore', 'deny', 'errored') { 'ignore' } else { 'allow' }
}

# Each test of the JUnit XML files in $Folder that did not pass, as 'Class#method failed' or 'Class#method skipped', with
# the class's short name; the names of tests hold no private data.
function Get-UnpassedTests([string]$Folder) {
    if (-not (Test-Path -LiteralPath $Folder)) { return @() }
    @(foreach ($file in Get-ChildItem -LiteralPath $Folder -Recurse -Filter 'TEST-*.xml') {
            [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw
            foreach ($case in @($xml.SelectNodes('//testcase'))) {
                $outcome = if ($case.SelectSingleNode('failure|error')) { 'failed' } elseif ($case.SelectSingleNode('skipped')) { 'skipped' }
                if ($outcome) { "$(($case.classname -split '\.')[-1])#$($case.name) $outcome" }
            }
        })
}

# Whether the JUnit XML files in $Folder hold the test class $Class (its short name) and each of its tests passed.
function Test-ClassPassed([string]$Folder, [string]$Class) {
    if (-not (Test-Path -LiteralPath $Folder)) { return $false }
    $cases = @(foreach ($file in Get-ChildItem -LiteralPath $Folder -Recurse -Filter 'TEST-*.xml') {
            [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw
            @($xml.SelectNodes('//testcase')) | Where-Object { ($_.classname -split '\.')[-1] -eq $Class }
        })
    $cases.Count -gt 0 -and -not @($cases | Where-Object { $_.SelectSingleNode('failure|error|skipped') })
}

# The actions of the activities that Android started, from the lines 'START u0 {act=...' of its log, each once. The rest
# of a line, such as the component of another app, is dropped.
function Get-StartedActions([string[]]$Log) {
    @($Log | ForEach-Object { if ($_ -match '\bSTART u\d+ \{act=([^\s}]+)') { $Matches[1] } } | Select-Object -Unique)
}

# Whether batterystats counted the phone as on battery, from its line 'System starts: N, currently on battery: true'
# (research r10, E5 and gap 2), or $null when the dump has no such line.
function Test-OnBattery([string[]]$Dump) {
    foreach ($line in $Dump) {
        if ($line -match 'currently on battery:\s*(true|false)') { return $Matches[1] -eq 'true' }
    }
    $null
}

# The packages in an answer of 'cmd role get-role-holders', which separates them with ';'.
function Get-RoleHolders([string]$Text) {
    @("$Text" -split '[;\s]+' | Where-Object { $_ })
}

# Kestrel's logged failures from 'logcat -s Kestrel:W': each fixed description of what failed with the short name of its
# error's class, and how often the two appeared together, in the order of the descriptions and classes. Kestrel logs
# '<what> failed: <class>' with the frames on the next lines, and never a message (design spec, section 13.5); the class
# is on the same line, and the lines of the frames and causes after it do not count.
function Get-FailureDescriptions([string[]]$Log) {
    $Log |
        ForEach-Object {
            if ($_ -match '\bKestrel\s*:\s*(.+?) failed:\s*([\w.$]+)') {
                [pscustomobject]@{ What = $Matches[1]; Class = ($Matches[2] -split '\.')[-1] }
            }
        } |
        Group-Object What, Class |
        Sort-Object Name |
        ForEach-Object { [pscustomobject]@{ What = $_.Group[0].What; Class = $_.Group[0].Class; Count = $_.Count } }
}

# Android's record of Kestrel's ListeningService in 'dumpsys activity services dev.kestrel' (analysis of the A71 session
# of sub-project 2, items 1 and 2): Listed, whether the dump has the record; Foreground, 'true' or 'false' from its line
# 'isForeground=... foregroundId=...', which Android prints only while the service is in the foreground or keeps its
# notification's ID, and 'not printed' otherwise; and Types, the foreground service types where Android prints them,
# Android 14 as 'types=00000082' and later versions as 'types=0x00000082', always as '0x82', or 'not printed', as on
# Android 13. A record's lines are indented more than its first line, which starts with '* ServiceRecord{'; from
# Android 15 on, that line ends with ' c:' and the package that last started the service, before its '}'.
function Get-ForegroundRecord([string[]]$Dump) {
    $listed = $false
    $inRecord = $false
    $foreground = 'not printed'
    $types = 'not printed'
    foreach ($line in $Dump) {
        if ($line -match '^\s*\* ServiceRecord\{') {
            $inRecord = $line -match '\sdev\.kestrel/\.app\.listen\.ListeningService[\s}]'
            if ($inRecord) { $listed = $true }
            continue
        }
        if ($line -match '^\s{0,2}\S') { $inRecord = $false }
        if (-not $inRecord) { continue }
        if ($foreground -eq 'not printed' -and $line -match '\bisForeground=(true|false)\b') { $foreground = $Matches[1] }
        if ($types -eq 'not printed' -and $line -match '\btypes=(?:0x)?([0-9a-fA-F]+)\b') {
            $types = '0x{0:X}' -f [Convert]::ToInt64($Matches[1], 16)
        }
    }
    [pscustomobject]@{ Listed = $listed; Foreground = $foreground; Types = $types }
}

# Reads an answer of the hooks voice-times, voice-counts or voice-route, such as 'now=81234 started=tile@80012', into an
# ordered table of names and values. The answers hold no words. Throws when a part is not 'name=value'.
function ConvertFrom-HookPairs([string]$Text) {
    $pairs = [ordered]@{}
    foreach ($pair in @("$Text".Trim() -split '\s+' | Where-Object { $_ })) {
        $name, $value = $pair -split '=', 2
        if (-not $name -or $null -eq $value) { throw "Not an answer of the hooks: '$Text'" }
        $pairs[$name] = $value
    }
    $pairs
}

# A mark of voice-times, such as 'wait@86500+640': Kind, At (milliseconds on the phone's clock) and SinceSpeech
# (milliseconds after the end of the latest transcribed speech, or $null), or $null for 'none'.
function ConvertFrom-TimeMark([string]$Text) {
    if ($Text -notmatch '^([\w-]+)@(\d+)(?:\+(\d+))?$') { return $null }
    $since = if ($Matches[3]) { [long]$Matches[3] } else { $null }
    [pscustomobject]@{ Kind = $Matches[1]; At = [long]$Matches[2]; SinceSpeech = $since }
}

# The memory categories of 'dumpsys meminfo <package>' that show where a process's memory stays (analysis of the A71
# session of sub-project 2, item 9): the PSS of Native Heap, .so mmap, Other mmap, Unknown and TOTAL, in MB, from the
# first number of each row of the table. Returns an ordered table, which is empty when the process does not run.
function Get-MemoryCategories([string[]]$Dump) {
    $categories = [ordered]@{}
    foreach ($line in $Dump) {
        if ($line -match '^\s*(Native Heap|\.so mmap|Other mmap|Unknown|TOTAL)\s+(\d+)\s' -and -not $categories.Contains($Matches[1])) {
            $categories[$Matches[1]] = [int][math]::Round([double]$Matches[2] / 1024)
        }
    }
    $categories
}

# The categories of Get-MemoryCategories as one text, such as 'Native Heap 412, .so mmap 37, TOTAL 980 (MB)', or
# 'not listed'.
function Format-MemoryCategories($Categories) {
    if (-not $Categories -or $Categories.Count -eq 0) { return 'not listed' }
    "$(@($Categories.Keys | ForEach-Object { "$_ $($Categories[$_])" }) -join ', ') (MB)"
}

# A duration as dumpsys prints it, such as '1h 2m 3s 456ms', in seconds.
function ConvertFrom-DumpDuration([string]$Text) {
    $seconds = 0.0
    foreach ($part in [regex]::Matches($Text, '(\d+)(ms|d|h|m|s)\b')) {
        $value = [double]$part.Groups[1].Value
        $seconds += switch ($part.Groups[2].Value) {
            'd' { $value * 86400 }
            'h' { $value * 3600 }
            'm' { $value * 60 }
            's' { $value }
            'ms' { $value / 1000 }
        }
    }
    $seconds
}

# The time that 'dumpsys batterystats --charged' counted on battery since its reset, in hours, from its line such as
# 'Time on battery: 30m 1s 234ms (100.0%) realtime, ...', so that a battery measure is timed by batterystats, or $null
# when the dump has no such line.
function Get-BatteryHours([string[]]$Dump) {
    foreach ($line in $Dump) {
        if ($line -match 'Time on battery:\s*([^(]+)\(') { return (ConvertFrom-DumpDuration $Matches[1]) / 3600 }
    }
    $null
}

# Kestrel's battery model from two measures (plan 2E): '% per hour = baseline + cost per transcribed minute x minutes',
# where the minutes are the minutes of audio that Kestrel transcribed in an hour. $Baseline is the % per hour of a
# measure in which nobody spoke; a measure with transcribed audio, $PercentPerHour with $MinutesPerHour, gives the cost.
# Returns the cost in % per hour for each transcribed minute in an hour, with three decimals, or $null when that measure
# transcribed nothing.
function Get-BatteryCost([double]$Baseline, [double]$PercentPerHour, [double]$MinutesPerHour) {
    if ($MinutesPerHour -le 0) { return $null }
    [math]::Round(($PercentPerHour - $Baseline) / $MinutesPerHour, 3)
}

# Whether the answer of '<command> | tail -c 1 | od -An -tx1' says that the command's output ended with a line break
# (0a), as the session checks One UI's 'dumpsys accessibility' (analysis, item 3): $true, $false for another last byte,
# or $null when the answer has no byte. Only that byte is read, never the output.
function Test-EndsWithLineBreak([string[]]$Answer) {
    $byte = @("$($Answer -join ' ')" -split '\s+' | Where-Object { $_ -match '^[0-9a-fA-F]{2}$' }) | Select-Object -Last 1
    if (-not $byte) { return $null }
    $byte -eq '0a'
}

# The time since boot in milliseconds from the answer of 'cat /proc/uptime', such as '12345.67 23456.78', which counts the
# time in deep sleep too, as Kestrel's SystemClock.elapsedRealtime does, so the two can be compared.
function ConvertFrom-Uptime([string]$Text) {
    if ("$Text".Trim() -notmatch '^(\d+(?:\.\d+)?)\s') { throw "Not an answer of /proc/uptime: '$Text'" }
    [long][math]::Round([double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture) * 1000)
}

# What rose between two answers of voice-counts, read with ConvertFrom-HookPairs, such as 'hit-kestrel+1 named+1
# read-name+1 utterances+1', in the order of the later answer; or 'none'. The waits and the tasks, which the steps check
# themselves, are left out.
function Get-CountRise($Before, $After) {
    $rises = foreach ($name in $After.Keys) {
        if ($name -in 'waits', 'tasks', 'lastTaskWords', 'hits') { continue }
        $was = if ($Before.Contains($name)) { [long]$Before[$name] } else { 0 }
        $rise = [long]$After[$name] - $was
        if ($rise -gt 0) { "$name+$rise" }
    }
    if ($rises) { @($rises) -join ' ' } else { 'none' }
}

# The state of a process from 'dumpsys activity processes <package>': its procState and its oom adj, such as
# 'procState=2 adj=0', or 'not listed' (analysis, item 7). Only these numbers are kept.
function Get-ProcessState([string[]]$Dump) {
    $procState = $null
    $adj = $null
    foreach ($line in $Dump) {
        if ($null -eq $procState -and $line -match '\bcurProcState=(-?\d+)') { $procState = $Matches[1] }
        if ($null -eq $adj -and $line -match '\boom adj:.*?\bcur=(-?\d+)') { $adj = $Matches[1] }
    }
    if ($null -eq $procState -and $null -eq $adj) { return 'not listed' }
    "procState=$(if ($null -ne $procState) { $procState } else { 'unknown' }) adj=$(if ($null -ne $adj) { $adj } else { 'unknown' })"
}

# The keyword spotter's setting that the A71 session keeps (plan 2E), from the rounds of its step spotter: each has
# Setting (such as 'boost=2.0 threshold=0.15'), Default ($true for Kestrel's own), Hits (how many of the owner's tries
# of "Hey Kestrel" Kestrel reacted to) and FalseHits (the hits of all three keywords in the played speech without a
# keyword, the same speech for every setting). The rule: among the settings whose false hits are at most twice the
# default's plus 2, the one with the most hits; a tie goes to fewer false hits, and then to the default. Returns $null
# when the default was not measured.
function Select-SpotterWinner([object[]]$Rounds) {
    $default = @($Rounds | Where-Object { $_.Default }) | Select-Object -First 1
    if (-not $default) { return $null }
    $limit = 2 * $default.FalseHits + 2
    @($Rounds | Where-Object { $_.FalseHits -le $limit }) |
        Sort-Object @{ Expression = { $_.Hits }; Descending = $true }, @{ Expression = { $_.FalseHits }; Descending = $false }, @{ Expression = { -not $_.Default }; Descending = $false } |
        Select-Object -First 1
}

# Turns the host audio input of the emulator kestrel-api37 on or off through its console ('avd hostmicon' and 'avd
# hostmicoff'), so that a dry run of the A71 sessions hears only the texts that it sends, and not the sound of this
# computer's microphone, which can start a command of its own during a check. The console has no command that reads
# the setting. Throws for every device but kestrel-api37, and when the console does not answer OK. Get-Adb comes from
# scripts\lib\Kestrel.ps1.
function Set-EmulatorHostMicrophone([string]$Serial, [bool]$On) {
    $adb = Get-Adb
    # Windows PowerShell 5.1 would stop at adb's first error line.
    $ErrorActionPreference = 'Continue'
    $name = "$(& $adb -s $Serial emu avd name 2>$null | Select-Object -First 1)".Trim()
    if ($Serial -notlike 'emulator-*' -or $name -ne 'kestrel-api37') { throw "$Serial is not the emulator kestrel-api37." }
    $command = if ($On) { 'hostmicon' } else { 'hostmicoff' }
    $answer = @(& $adb -s $Serial emu avd $command 2>&1 | ForEach-Object { "$_".Trim() })
    if ($answer -notcontains 'OK' -or @($answer | Where-Object { $_ -like 'KO*' }).Count -gt 0) {
        throw "The emulator's console did not take 'avd $command'."
    }
}

# The newest record of PowerShell's error list $Error, or $null when the list is empty, for Remove-NewErrorRecord.
function Get-NewestErrorRecord { if ($Error.Count -gt 0) { $Error[0] } else { $null } }

# Removes from $Error every record that is newer than $Newest (Get-NewestErrorRecord), such as a record of adb's error
# output or of a JSON parser's error, which can repeat the test PIN; PowerShell keeps such records also when a script
# catches them.
function Remove-NewErrorRecord($Newest) {
    while ($Error.Count -gt 0 -and -not [object]::ReferenceEquals($Error[0], $Newest)) { $Error.RemoveAt(0) }
}

# Runs $Command in 'adb -s $Serial shell', which reads it from its standard input, so that the command is not an
# argument of adb.exe, whose command line other processes of this user and process audits can read. Returns the exit code
# and the lines of adb's standard output; its error output is read and dropped, and nothing reaches PowerShell's error
# stream or $Error. Throws when adb does not end within $TimeoutSeconds. Needs scripts\lib\Kestrel.ps1.
function Invoke-AdbShellInput([string]$Serial, [string]$Command, [int]$TimeoutSeconds = 60) {
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = Get-Adb
    $info.Arguments = "-s $Serial shell"
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    # Windows PowerShell 5.1's .NET Framework writes the console's input encoding to the process's standard input, with
    # its byte-order mark first when that encoding has one (UTF-8 on CI), and the device's shell would not know the first
    # word; PowerShell 7 drops the mark itself. So the process starts with UTF-8 without the mark, and the console gets
    # its encoding back right after.
    $inputEncoding = $null
    try {
        if ([Console]::InputEncoding.GetPreamble().Length -gt 0) {
            $inputEncoding = [Console]::InputEncoding
            [Console]::InputEncoding = New-Object Text.UTF8Encoding $false
        }
    } catch {
        $inputEncoding = $null
    }
    try {
        $process = [Diagnostics.Process]::Start($info)
    } finally {
        if ($inputEncoding) { [Console]::InputEncoding = $inputEncoding }
    }
    try {
        $output = $process.StandardOutput.ReadToEndAsync()
        $errors = $process.StandardError.ReadToEndAsync()
        $process.StandardInput.NewLine = "`n"
        $process.StandardInput.WriteLine($Command)
        $process.StandardInput.Close()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill()
            throw "adb did not end within $TimeoutSeconds s."
        }
        $process.WaitForExit()
        $errors.Result | Out-Null
        [pscustomobject]@{ ExitCode = $process.ExitCode; Lines = @("$($output.Result)" -split "`r?`n") }
    } finally {
        $process.Dispose()
    }
}

# The owner's settings for the A71 sessions (plan 4D) from corpus\owner\a71-config.json, which git ignores, read as
# JSON, such as {"callNumber": "0641234567", "testContacts": true, "speakerTargetDb": -26, "pin": "..."}: the number
# that is fine to call (5 to 15 digits, with an optional '+'), his consent to the two test contacts, and the level in
# dBFS at which the phone should hear this computer's clips. Every other key, such as those of later plans, comes as
# it is in the file, except the test PIN: this reader, which every part but the unlock uses, leaves out the key "pin",
# which only Send-TestPin reads. A missing file or field gives no number, no consent and -26 dBFS. Throws for a number
# of another form.
function Read-A71Config([string]$Path) {
    $config = [pscustomobject]@{ CallNumber = $null; TestContacts = $false; SpeakerTargetDb = -26.0 }
    if (-not (Test-Path -LiteralPath $Path)) { return $config }
    # The parser's own error can repeat the file's text, which holds the test PIN, so it never reaches the console or $Error.
    $newest = Get-NewestErrorRecord
    $valid = $true
    $json = try { Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json } catch { $valid = $false }
    if (-not $valid) {
        Remove-NewErrorRecord $newest
        throw "$Path is not valid JSON. Write every value as in {""pin"": ""0246""}, with text in quotes."
    }
    $field = { param($Name) $json.PSObject.Properties[$Name] }
    if ((& $field 'callNumber') -and "$($json.callNumber)") {
        $number = "$($json.callNumber)" -replace '[\s-]', ''
        if ($number -notmatch '^\+?\d{5,15}$') { throw "$Path`: callNumber must have 5 to 15 digits." }
        $config.CallNumber = $number
    }
    if (& $field 'testContacts') { $config.TestContacts = $json.testContacts -eq $true }
    if ((& $field 'speakerTargetDb') -and $null -ne $json.speakerTargetDb) { $config.SpeakerTargetDb = [double]$json.speakerTargetDb }
    foreach ($property in $json.PSObject.Properties) {
        if ($property.Name -in 'pin', 'callNumber', 'testContacts', 'speakerTargetDb') { continue }
        $config | Add-Member -NotePropertyName $property.Name -NotePropertyValue $property.Value
    }
    $config
}

# Types the A71's test PIN on the phone's PIN pad and presses Enter (plan 4D). The owner allows the A71 sessions to use
# the PIN, which is the key "pin" of $ConfigPath (corpus\owner\a71-config.json), and only this function reads it, right
# before it types it; with -CheckOnly it only checks the key, without a device, as the scripts do at their start. A
# missing file or key, a file that is not JSON, or a PIN that is not 4 to 16 digits in quotes, throws a message that says
# what is wrong and never the PIN. It calls adb itself and never Invoke-SettingsCommand, whose errors repeat the whole
# command, and gives adb the command on its standard input (Invoke-AdbShellInput), so that the PIN is never an argument
# of adb.exe. The device check of Format-TestDeviceCommand runs in the same shell call, and so does a last check of the
# PIN pad (the view IDs of Get-PinPadState) right before the typing. adb's own output, which can repeat the command too,
# is dropped, the PIN and the command are cleared after use, the records of $Error that the call added are removed, and
# the only error of the typing, whatever went wrong, is "the unlock failed". Returns $true after the typing, and $false
# when the PIN pad no longer showed, so that nothing was typed. Needs scripts\lib\Kestrel.ps1.
function Send-TestPin([string]$Serial, [string]$ConfigPath, [switch]$CheckOnly) {
    $how = 'Write the A71''s test PIN as the key "pin" into corpus\owner\a71-config.json, which git ignores, for example {"pin": "<digits>"}.'
    if (-not (Test-Path -LiteralPath $ConfigPath)) { throw "The A71's test PIN is missing, because $ConfigPath does not exist. $how" }
    $pin = $null
    $command = $null
    $answer = $null
    $sent = $false
    $typed = $true
    $newest = Get-NewestErrorRecord
    try {
        # The parser's own error can repeat the PIN, as with {"pin": 0246}, so it never reaches the console or $Error.
        $valid = $true
        $json = try { Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json } catch { $valid = $false }
        if (-not $valid) {
            Remove-NewErrorRecord $newest
            throw "$ConfigPath is not valid JSON. Write every value as in {""pin"": ""0246""}, with text in quotes."
        }
        $value = $json.PSObject.Properties['pin']
        $json = $null
        if (-not $value -or -not "$($value.Value)") { throw "The A71's test PIN is missing, because $ConfigPath has no key ""pin"". $how" }
        # A number, which Windows PowerShell 5.1 reads from {"pin": 0246} as 246, would type another PIN.
        if ($value.Value -isnot [string]) { throw "The key ""pin"" of $ConfigPath must be text in quotes, such as {""pin"": ""0246""}." }
        $pin = "$($value.Value)"
        $value = $null
        if ($pin -notmatch '^\d{4,16}$') { throw "The key ""pin"" of $ConfigPath must be 4 to 16 digits." }
        if ($CheckOnly) { return }
        try {
            # Types only when the PIN pad still shows; the hierarchy stays on the phone, as in Get-PinPadState.
            $file = '/data/local/tmp/kestrel-keyguard.xml'
            $command = Format-TestDeviceCommand -Serial $Serial -Command ("uiautomator dump $file >/dev/null 2>&1; pad=`$(grep -c -e id/pinEntry -e id/keyguard_pin_view $file 2>/dev/null); rm -f $file; " +
                "if [ `"`$pad`" -gt 0 ] 2>/dev/null; then input text $pin && input keyevent KEYCODE_ENTER && echo kestrel-pin-sent; else echo kestrel-no-pad; fi")
            $answer = Invoke-AdbShellInput -Serial $Serial -Command $command
            $last = "$(@($answer.Lines | Where-Object { "$_".Trim() }) | Select-Object -Last 1)".Trim()
            if ($answer.ExitCode -eq 0 -and $last -eq 'kestrel-no-pad') {
                $typed = $false
                $sent = $true
            } else {
                $sent = $answer.ExitCode -eq 0 -and $last -eq 'kestrel-pin-sent'
            }
        } catch {
            $sent = $false
        }
    } finally {
        $pin = $null
        $command = $null
        $answer = $null
        $last = $null
        Remove-NewErrorRecord $newest
    }
    if (-not $sent) { throw 'the unlock failed' }
    $typed
}
# Whether an activity shows over the lock screen ('Occluded=true' in 'dumpsys activity activities'), such as a call's
# screen or the camera, which would get what is typed.
function Test-KeyguardOccluded([string[]]$Dump) { @($Dump | Where-Object { $_ -match '(?i)\boccluded=true\b' }).Count -gt 0 }

# Whether the lock screen's PIN pad shows on $Serial: $true only when 'dumpsys activity activities' says that the keyguard
# shows and that no activity shows over it, and the screen's hierarchy holds the PIN pad's entry (the view IDs pinEntry or
# keyguard_pin_view of com.android.systemui); $false when the keyguard does not show; $null when Android does not say it
# clearly. No dumpsys of Android 13 or 17 tells the PIN pad from the rest of the lock screen (both are SystemUI's
# notification shade window), so this reads the hierarchy, once per call: 'uiautomator dump' writes it to
# /data/local/tmp, which the shell owns and which exists before the first unlock after a restart (unlike /sdcard), and
# only the count of those two IDs is read on the phone before the file is deleted, because the hierarchy can hold the
# lock screen's notifications; nothing of it reaches this computer. Each dump unbinds every accessibility service,
# Kestrel's included, for 1 to 10 s, so Kestrel's notice that its service is off can show meanwhile. Needs
# scripts\lib\Kestrel.ps1 and scripts\lib\Oracle.ps1.
function Get-PinPadState([string]$Serial) {
    $activities = @(Invoke-SettingsCommand -Serial $Serial -Command 'dumpsys activity activities')
    $keyguard = Test-KeyguardShowing $activities
    if ($keyguard -ne $true) { return $keyguard }
    if (Test-KeyguardOccluded $activities) { return $null }
    $file = '/data/local/tmp/kestrel-keyguard.xml'
    $count = "$(@(Invoke-SettingsCommand -Serial $Serial -Command "uiautomator dump $file >/dev/null 2>&1; grep -c -e id/pinEntry -e id/keyguard_pin_view $file; rm -f $file") | Select-Object -Last 1)".Trim()
    if ($count -match '^\d+$' -and [int]$count -gt 0) { return $true }
    $null
}

# Unlocks the test phone $Serial with the owner's test PIN of $ConfigPath (Send-TestPin), also right after a restart,
# when Android asks for the PIN: it wakes the screen and, when the keyguard shows and no activity shows over it, brings
# up the PIN pad without looking (a swipe up from the screen's lower part, which Android's lock screen and One UI's both
# answer with the PIN pad, and 'wm dismiss-keyguard', which shows it for a lock screen with a PIN) unless the PIN pad
# already shows, because a swipe over the pad could type a digit, and then types the PIN only when Get-PinPadState says
# clearly that the PIN pad shows, and Send-TestPin finds it again right before the typing; a state that Android does not
# say clearly never types, so the PIN never reaches another app's field. Returns $true when the phone is unlocked, and
# $false when it typed nothing (the phone did not show its PIN pad). The PIN is typed at most once in a call, and a
# typing that did not unlock the phone within 10 s throws "the unlock failed" and stops every later unlock of the run,
# which then throws at once: One UI locks the phone for a while after 5 wrong PINs, and can reset it after 15 when that
# is switched on. Every other failure on the phone throws "the unlock failed" too, and the records of $Error that the
# call added are removed, so the PIN never reaches a result, a log, $Error or the console. Needs scripts\lib\Kestrel.ps1
# and scripts\lib\Oracle.ps1.
function Unlock-TestPhone([string]$Serial, [string]$ConfigPath) {
    # A missing file or key stops here, before a command reaches the phone.
    Send-TestPin -ConfigPath $ConfigPath -CheckOnly
    if ($script:TestPinRefused) { throw 'the unlock failed earlier in this run, so the session tries no more unlocks' }
    $newest = Get-NewestErrorRecord
    $failed = $false
    try {
        Invoke-SettingsCommand -Serial $Serial -Command 'input keyevent KEYCODE_WAKEUP' | Out-Null
        Start-Sleep -Seconds 1
        $activities = @(Invoke-SettingsCommand -Serial $Serial -Command 'dumpsys activity activities')
        $keyguard = Test-KeyguardShowing $activities
        if ($keyguard -eq $false) { return $true }
        if ($null -eq $keyguard -or (Test-KeyguardOccluded $activities)) { return $false }
        $pad = Get-PinPadState -Serial $Serial
        if ($pad -eq $false) { return $true }
        if ($pad -ne $true) {
            if ("$(Invoke-SettingsCommand -Serial $Serial -Command 'wm size')" -match '(\d+)x(\d+)') {
                $x = [int]([int]$Matches[1] / 2)
                Invoke-SettingsCommand -Serial $Serial -Command "input swipe $x $([int]([int]$Matches[2] * 0.8)) $x $([int]([int]$Matches[2] * 0.3)) 300" | Out-Null
            }
            Invoke-SettingsCommand -Serial $Serial -Command 'wm dismiss-keyguard' | Out-Null
            Start-Sleep -Seconds 2
            $pad = Get-PinPadState -Serial $Serial
            if ($pad -eq $false) { return $true }
            # Only on the PIN pad: the PIN must never reach another app's field.
            if ($pad -ne $true) { return $false }
        }
        # Set before the typing, so that also a typing that failed half-way is never repeated.
        $script:TestPinRefused = $true
        if ((Send-TestPin -Serial $Serial -ConfigPath $ConfigPath) -eq $false) {
            # The PIN pad no longer showed, and nothing was typed.
            $script:TestPinRefused = $false
            return $false
        }
        for ($second = 0; $second -lt 10; $second++) {
            Start-Sleep -Seconds 1
            if ((Test-KeyguardShowing @(Invoke-SettingsCommand -Serial $Serial -Command 'dumpsys activity activities')) -eq $false) {
                $script:TestPinRefused = $false
                return $true
            }
        }
        $failed = $true
    } catch {
        $failed = $true
    } finally {
        Remove-NewErrorRecord $newest
    }
    if ($failed) { throw 'the unlock failed' }
}

# Closes an "isn't responding" dialog that has the focus on $Serial (Get-AnrPackage), such as one of Chrome's after the
# Custom Tab tests, with its button "Close app" (the view ID aerr_close of the package android, or the text "Close app"),
# because it stays over every app, and the checks after it would fail without a reason in their lines. The dialog's
# hierarchy is read as the PIN pad's is (Get-PinPadState): 'uiautomator dump' to /data/local/tmp, of which only the
# button's bounds leave the phone. That dump unbinds every accessibility service for up to 10 s, so after a tap the
# function waits 10 s. Returns $null when no such dialog has the focus, or else the text of a result line that says what
# it did. Needs scripts\lib\Kestrel.ps1 and scripts\lib\Oracle.ps1.
function Close-AnrDialog([string]$Serial) {
    $package = Get-AnrPackage @(Invoke-SettingsCommand -Serial $Serial -Command 'dumpsys window')
    if (-not $package) { return $null }
    $file = '/data/local/tmp/kestrel-anr.xml'
    $found = @(Invoke-SettingsCommand -Serial $Serial -Command "uiautomator dump $file >/dev/null 2>&1; grep -o -E -e 'id/aerr_close[^>]*bounds=[^ >]*' -e 'text=.Close app.[^>]*bounds=[^ >]*' $file; rm -f $file")
    $button = @($found | Where-Object { "$_" -match 'bounds=.\[\d+,\d+\]\[\d+,\d+\]' }) | Select-Object -First 1
    $dialog = "an ""isn't responding"" dialog of $package had the focus"
    if (-not ($button -and "$button" -match 'bounds=.\[(\d+),(\d+)\]\[(\d+),(\d+)\]')) {
        return "$dialog, and the script found no ""Close app"" on it, so the checks after it may fail"
    }
    Invoke-SettingsCommand -Serial $Serial -Command "input tap $([int](([int]$Matches[1] + [int]$Matches[3]) / 2)) $([int](([int]$Matches[2] + [int]$Matches[4]) / 2))" | Out-Null
    Start-Sleep -Seconds 10
    if (Get-AnrPackage @(Invoke-SettingsCommand -Serial $Serial -Command 'dumpsys window')) {
        return "$dialog, and a dialog still had it after the script tapped ""Close app"", so the checks after it may fail"
    }
    "$dialog, and the script closed it with ""Close app"""
}
