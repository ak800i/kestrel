# Readers of Android's dumps for the A71 sessions' automatic checks (plan 4D): which app is in front, whether the phone is
# awake or locked, whether the keyboard shows, the playback state of a media session, the muted streams, the window that
# has the focus and the home app. Each keeps only a package name, a state or a number from the dump, never other text.

# The package of the activity in front, from 'dumpsys activity activities' ('topResumedActivity=ActivityRecord{1a2b u0
# com.android.settings/.Settings t12}'), or $null when no activity is resumed.
function Get-ResumedPackage([string[]]$Dump) {
    foreach ($line in $Dump) {
        if ($line -match '\btopResumedActivity=ActivityRecord\{\S+ u\d+ ([\w.]+)/') { return $Matches[1] }
    }
    foreach ($line in $Dump) {
        if ($line -match '\bmResumedActivity: ActivityRecord\{\S+ u\d+ ([\w.]+)/') { return $Matches[1] }
    }
    $null
}

# The package of the home app, from 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c
# android.intent.category.HOME', whose last line is the component, such as
# 'com.sec.android.app.launcher/com.android.launcher3.uioverrides.QuickstepLauncher', or $null.
function Get-HomePackage([string[]]$Answer) {
    $component = @($Answer | Where-Object { "$_".Trim() -match '^[\w.]+/[\w.$]+$' }) | Select-Object -Last 1
    if (-not $component) { return $null }
    ("$component".Trim() -split '/')[0]
}

# The power state, such as 'Awake' or 'Asleep', from 'dumpsys power' ('mWakefulness=Awake'), or $null.
function Get-Wakefulness([string[]]$Dump) {
    foreach ($line in $Dump) {
        if ($line -match '^\s*mWakefulness=(\w+)') { return $Matches[1] }
    }
    $null
}

# Whether the keyguard shows, from 'dumpsys activity activities' (its KeyguardController's 'mKeyguardShowing=true'), or
# $null when the dump does not say.
function Test-KeyguardShowing([string[]]$Dump) {
    foreach ($line in $Dump) {
        if ($line -match '\bmKeyguardShowing=(true|false)\b') { return $Matches[1] -eq 'true' }
    }
    $null
}

# Whether the keyboard shows, from 'dumpsys input_method' ('mInputShown=true'), or $null when the dump does not say.
function Test-InputShown([string[]]$Dump) {
    foreach ($line in $Dump) {
        if ($line -match '\bmInputShown=(true|false)\b') { return $Matches[1] -eq 'true' }
    }
    $null
}

# The playback state of the package's media session, from 'dumpsys media_session': the number in its line
# 'state=PlaybackState {state=3, ...' (2 paused, 3 playing), as Android 13 prints it, or 'state=PLAYING(3)' as Android 14
# and later print it, or $null when the package has no session. A session's lines follow its line 'package=<package>'.
function Get-PlaybackState([string[]]$Dump, [string]$Package) {
    $current = $null
    foreach ($line in $Dump) {
        if ($line -match '^\s*package=(\S+)\s*$') { $current = $Matches[1] }
        if ($current -eq $Package -and $line -match '\bstate=PlaybackState \{state=(?:[A-Z_]+\()?(\d+)') { return [int]$Matches[1] }
    }
    $null
}

# Whether a stream is muted, from 'dumpsys audio', whose list of stream volumes has a line '- STREAM_MUSIC:' and then
# '   Muted: true', or $null when the dump does not say.
function Test-StreamMuted([string[]]$Dump, [string]$Stream = 'STREAM_MUSIC') {
    $inStream = $false
    foreach ($line in $Dump) {
        if ($line -match '^- (STREAM_\w+):') {
            $inStream = $Matches[1] -eq $Stream
            continue
        }
        if ($inStream -and $line -match '^\s+Muted: (true|false)\b') { return $Matches[1] -eq 'true' }
    }
    $null
}

# The package or window name of the window that has the focus, from 'dumpsys window' ('mCurrentFocus=Window{4a8 u0
# com.android.settings/com.android.settings.Settings}'), such as 'com.android.settings', or the window's title when it
# names no package, or $null.
function Get-FocusedWindow([string[]]$Dump) {
    foreach ($line in $Dump) {
        if ($line -match '\bmCurrentFocus=Window\{\S+ u\d+ ([^/}\s]+)') { return $Matches[1] }
    }
    $null
}

# The app that an "isn't responding" dialog names when that dialog has the focus, from 'dumpsys window', whose window
# Android names 'Application Not Responding: <package>' ('mCurrentFocus=Window{5c1 u0 Application Not Responding:
# com.android.chrome}'), or $null when another window has the focus.
function Get-AnrPackage([string[]]$Dump) {
    foreach ($line in $Dump) {
        if ($line -match '\bmCurrentFocus=Window\{\S+ u\d+ Application Not Responding: ([\w.:]+)') { return $Matches[1] }
    }
    $null
}

# The hash that the hooks give for a text instead of the text: the first 16 hexadecimal characters of the SHA-256 of its
# UTF-8 bytes (plan 4D interfaces, section 1), so that a check compares what Kestrel said or showed with a fixed
# sentence without the words leaving the phone.
function Get-TextHash([string]$Text) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $bytes = $sha.ComputeHash([Text.UTF8Encoding]::new($false).GetBytes($Text)) } finally { $sha.Dispose() }
    -join ($bytes[0..7] | ForEach-Object { $_.ToString('x2') })
}

# Whether the hashes of a 'said=' or 'shown=' field of voice-state -Tag, separated by commas, hold the hash of $Text.
function Test-HashedText([string]$Hashes, [string]$Text) {
    @("$Hashes" -split ',') -contains (Get-TextHash $Text)
}
