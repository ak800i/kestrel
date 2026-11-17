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

# The user ID of a package, from 'dumpsys package <package>' ('userId=10234'), or $null.
function Get-AppUserId([string[]]$Dump) {
    foreach ($line in $Dump) {
        if ($line -match '\buserId=(\d+)') { return [int]$Matches[1] }
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

# Kestrel's logged failures from 'logcat -s Kestrel:W': each fixed description of what failed, with how often it
# appeared, in the order of the descriptions. Kestrel logs '<what> failed: <classes and frames>' and never a message
# (design spec, section 13.5); the lines of the frames and causes after it do not count.
function Get-FailureDescriptions([string[]]$Log) {
    $Log |
        ForEach-Object { if ($_ -match '\bKestrel\s*:\s*(.+?) failed\b') { $Matches[1] } } |
        Group-Object |
        Sort-Object Name |
        ForEach-Object { [pscustomobject]@{ What = $_.Name; Count = $_.Count } }
}
