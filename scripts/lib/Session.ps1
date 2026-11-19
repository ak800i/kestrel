# Helpers for scripts\a71-session.ps1 (plan 2D, design D8). They read Android's dumps and logs on this computer and keep
# only numbers, intent actions and Kestrel's own fixed descriptions, never what the owner said or other apps' text.

# Appends a line to the session's results: the time, the step and the text, separated by tabs, and creates the file's
# folder. The text must hold no private data, such as what Kestrel heard.
function Add-SessionResult([string]$Path, [string]$Step, [string]$Text) {
    $folder = Split-Path -Parent $Path
    if ($folder) { New-Item -ItemType Directory -Force -Path $folder | Out-Null }
    # In the invariant culture, whose time separator is always ':'.
    $time = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss', [Globalization.CultureInfo]::InvariantCulture)
    [IO.File]::AppendAllText($Path, "$time`t$Step`t$Text`n", [Text.UTF8Encoding]::new($false))
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
# battery's capacity, and the share of the battery per hour over $Hours, in percent with two decimals. Returns $null
# when the dump has no estimate for the UID or no capacity.
function Get-BatteryUse([string[]]$Dump, [string]$Uid, [double]$Hours) {
    $capacity = $null
    $used = $null
    foreach ($line in $Dump) {
        if ($null -eq $capacity -and $line -match '^\s*Capacity:\s*(\d+)') { $capacity = [double]$Matches[1] }
        if ($null -eq $used -and $line -match "^\s*Uid\s+$([regex]::Escape($Uid)):\s*([\d.]+)") {
            $used = [double]::Parse($Matches[1], [Globalization.CultureInfo]::InvariantCulture)
        }
    }
    if ($null -eq $used -or -not $capacity -or $Hours -le 0) { return $null }
    [pscustomobject]@{
        MilliampHours = $used
        Capacity = $capacity
        PercentPerHour = [math]::Round($used / $Hours / $capacity * 100, 2)
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
