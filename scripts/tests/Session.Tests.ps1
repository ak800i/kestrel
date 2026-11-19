# Tests of scripts\lib\Session.ps1, which scripts\a71-session.ps1 uses. scripts\test-scripts.ps1 runs these tests in
# Windows PowerShell 5.1 and in PowerShell 7.

Describe 'The helpers of the A71 session' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Session.ps1"
    }

    It 'appends the time, the step and the text to the results, and creates their folder' {
        $path = Join-Path $TestDrive 'owner\session.txt'

        Add-SessionResult -Path $path -Step 'memory' -Text 'start 1: session open after 6.2 s'
        Add-SessionResult -Path $path -Step 'room' -Text '0 waits for a task'

        $lines = @(Get-Content -LiteralPath $path)
        $lines.Count | Should -Be 2
        $lines[0] | Should -Match '^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\tmemory\tstart 1: session open after 6\.2 s$'
        ($lines[1] -split "`t")[1] | Should -BeExactly 'room'
    }

    It 'reads the memory of a process from the line TOTAL PSS, in MB' {
        $dump = @('** MEMINFO in pid 4321 [dev.kestrel] **', '           TOTAL    99999    88888', '            TOTAL PSS:   245,678            TOTAL RSS:   300,000')

        Get-TotalPss -Dump $dump | Should -Be 240
    }

    It 'reads it from the row TOTAL of the table when there is no such line' {
        Get-TotalPss -Dump @('                 Pss  Private', '           TOTAL   152345   140000      123') | Should -Be 149
        Get-TotalPss -Dump @('No process found for: dev.kestrel') | Should -BeNullOrEmpty
    }

    It 'names the UID of a package as batterystats does' {
        $userId = Get-AppUserId -Dump @('Packages:', '  Package [dev.kestrel] (1a2b3c):', '    userId=10234')

        $userId | Should -Be 10234
        ConvertTo-UidLabel -UserId $userId | Should -BeExactly 'u0a234'
    }

    It 'reads the UID also as Android 17 prints it, and from the list of packages' {
        Get-AppUserId -Dump @('  Package [dev.kestrel] (1a2b3c):', '    appId=10331') | Should -Be 10331
        $list = @('package:dev.kestrel.test uid:10332', 'package:dev.kestrel uid:10331', 'package:dev.kestrel.testapp uid:10303')
        Get-ListedUserId -List $list -Package 'dev.kestrel' | Should -Be 10331
        Get-ListedUserId -List @('package:dev.kestrel.test uid:10332') -Package 'dev.kestrel' | Should -BeNullOrEmpty
    }

    It 'says why batterystats gave no estimate for the UID' {
        $estimates = @('  Estimated power use (mAh):', '    Capacity: 3000, Computed drain: 0, actual drain: 0')

        Get-BatteryUseGap -Dump @('Statistics since last charge:') -Uid 'u0a331' | Should -Match 'no estimates of power use'
        Get-BatteryUseGap -Dump @('  Estimated power use (mAh):') -Uid 'u0a331' | Should -Match 'no battery capacity'
        Get-BatteryUseGap -Dump ($estimates + '    UID u0a196: 0.132') -Uid 'u0a331' | Should -Match "no estimate for Kestrel's UID u0a331"
        Get-BatteryUseGap -Dump ($estimates + '    UID u0a331: 0.00339 bg: 0.00000700') -Uid 'u0a331' | Should -BeNullOrEmpty
        Get-BatteryUseGap -Dump @('  Estimated power use (mAh):', '    Capacity: 0, Computed drain: 0', '    UID u0a331: 0.1') -Uid 'u0a331' |
            Should -Match 'capacity of 0 mAh'
    }

    It 'remembers the last crash of Kestrel for the device, and how long to wait after it' {
        $path = Join-Path $TestDrive 'owner\session-state.txt'
        $crash = [datetime]::new(2026, 11, 17, 10, 0, 0, [DateTimeKind]::Utc)

        Read-SessionCrash -Path $path -Serial 'R58N12345AB' | Should -BeNullOrEmpty
        Write-SessionCrash -Path $path -Serial 'R58N12345AB' -Time $crash

        Read-SessionCrash -Path $path -Serial 'R58N12345AB' | Should -Be $crash
        Read-SessionCrash -Path $path -Serial 'emulator-5554' | Should -BeNullOrEmpty
        Get-CrashWait -LastCrash $crash -Now $crash.AddSeconds(5) | Should -Be 120
        Get-CrashWait -LastCrash $crash -Now $crash.AddSeconds(130) | Should -Be 0
        Get-CrashWait -LastCrash $null -Now $crash | Should -Be 0
    }

    It 'reads the mode of an app op from the package''s line only' {
        Get-AppOpMode -Answer @('No operations.', 'Default mode: allow') -Op RUN_ANY_IN_BACKGROUND | Should -BeNullOrEmpty
        Get-AppOpMode -Answer @('RUN_ANY_IN_BACKGROUND: allow; time=+1m2s ago') -Op RUN_ANY_IN_BACKGROUND | Should -BeExactly 'allow'
        Get-AppOpMode -Answer @('Uid mode: RUN_ANY_IN_BACKGROUND: ignore', 'RUN_ANY_IN_BACKGROUND: allow') -Op RUN_ANY_IN_BACKGROUND |
            Should -BeExactly 'allow'
    }

    It 'puts the battery restriction back as allow or ignore, never as default' {
        Get-BackgroundOpRestore -Answer @('No operations.') | Should -BeExactly 'allow'
        Get-BackgroundOpRestore -Answer @('RUN_ANY_IN_BACKGROUND: default') | Should -BeExactly 'allow'
        Get-BackgroundOpRestore -Answer @('RUN_ANY_IN_BACKGROUND: allow; time=+5s ago') | Should -BeExactly 'allow'
        Get-BackgroundOpRestore -Answer @('RUN_ANY_IN_BACKGROUND: ignore; rejectTime=+2s ago') | Should -BeExactly 'ignore'
    }

    It 'names the tests that did not pass, and tells whether a class passed' {
        $folder = Join-Path $TestDrive 'connected'
        New-Item -ItemType Directory -Force -Path $folder | Out-Null
        $xml = @(
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<testsuite name="all" tests="4" failures="1" errors="0" skipped="1">'
            '  <testcase name="loadsBoth" classname="dev.kestrel.app.voice.OnnxRuntimeTogetherTest" time="1.0" />'
            '  <testcase name="hearsIt" classname="dev.kestrel.app.voice.EvalHookTest" time="1.0"><failure message="m">trace</failure></testcase>'
            '  <testcase name="speaks" classname="dev.kestrel.app.voice.SynthesizerTest" time="1.0"><skipped /></testcase>'
            '  <testcase name="counts" classname="dev.kestrel.app.voice.AppNamesTest" time="1.0" />'
            '</testsuite>'
        ) -join "`n"
        Set-Content -LiteralPath (Join-Path $folder 'TEST-device.xml') -Value $xml

        Get-UnpassedTests -Folder $folder | Should -Be @('EvalHookTest#hearsIt failed', 'SynthesizerTest#speaks skipped')
        Test-ClassPassed -Folder $folder -Class 'OnnxRuntimeTogetherTest' | Should -BeTrue
        Test-ClassPassed -Folder $folder -Class 'EvalHookTest' | Should -BeFalse
        Test-ClassPassed -Folder $folder -Class 'MissingTest' | Should -BeFalse
        @(Get-UnpassedTests -Folder (Join-Path $TestDrive 'none')).Count | Should -Be 0
    }

    It 'computes the share of the battery per hour from the estimate of batterystats' {
        $dump = @(
            'Estimated power use (mAh):'
            '    Capacity: 4500, Computed drain: 120, actual drain: 0-45'
            '    UID u0a111: 40.0 ( cpu=40.0 )'
            '    UID u0a234: 12.5 ( cpu=10.1 wakelock=0.0 audio=2.4 )'
        )

        $use = Get-BatteryUse -Dump $dump -Uid 'u0a234' -Hours 0.5

        $use.MilliampHours | Should -Be 12.5
        $use.Capacity | Should -Be 4500
        $use.PercentPerHour | Should -Be 0.56
        Get-BatteryUse -Dump $dump -Uid 'u0a999' -Hours 0.5 | Should -BeNullOrEmpty
    }

    It 'keeps only the actions of the started activities, each once' {
        $log = @(
            '11-16 22:31:36.449  1000  1234 I ActivityTaskManager: START u0 {act=android.intent.action.VOICE_COMMAND flg=0x10000000 cmp=dev.kestrel/.app.assist.AssistActivity} from uid 1002'
            '11-16 22:31:37.000  1000  1234 I ActivityTaskManager: START u0 {act=android.intent.action.VOICE_COMMAND flg=0x10000000 cmp=com.example/.Other} from uid 1002'
            '11-16 22:31:38.000  1000  1234 I ActivityTaskManager: START u0 {flg=0x10200000 cmp=com.example/.Main} from uid 10123'
            '11-16 22:31:39.000  1000  1234 I ActivityTaskManager: Displayed com.example/.Main'
        )

        Get-StartedActions -Log $log | Should -Be @('android.intent.action.VOICE_COMMAND')
    }

    It 'tells from dumpsys batterystats whether Android counted the phone as on battery' {
        Test-OnBattery -Dump @('Statistics since last charge:', '  System starts: 0, currently on battery: true') | Should -BeTrue
        Test-OnBattery -Dump @('  System starts: 0, currently on battery: false') | Should -BeFalse
        Test-OnBattery -Dump @('Can''t find service: batterystats') | Should -BeNullOrEmpty
    }

    It 'splits the holders of a role' {
        Get-RoleHolders -Text 'com.google.android.googlequicksearchbox' | Should -Be @('com.google.android.googlequicksearchbox')
        Get-RoleHolders -Text 'a.b;c.d' | Should -Be @('a.b', 'c.d')
        @(Get-RoleHolders -Text '').Count | Should -Be 0
    }

    It 'counts Kestrel''s logged failures by their fixed descriptions' {
        $log = @(
            '--------- beginning of main'
            '11-16 22:31:36.449  4321  4400 W Kestrel : The microphone failed: IllegalStateException at dev.kestrel.voice.listen.MicCapture.read'
            '11-16 22:31:36.450  4321  4400 W Kestrel : at dev.kestrel.app.listen.Listening.restartMicrophone'
            '11-16 22:32:00.000  4321  4400 W Kestrel : The microphone failed: IllegalStateException at dev.kestrel.voice.listen.MicCapture.read'
            '11-16 22:33:00.000  4321  4401 E Kestrel : A background task failed: IOException at dev.kestrel.privacy.Store.write'
        )

        $failures = @(Get-FailureDescriptions -Log $log)

        $failures.Count | Should -Be 2
        $failures[0].What | Should -BeExactly 'A background task'
        $failures[0].Count | Should -Be 1
        $failures[1].What | Should -BeExactly 'The microphone'
        $failures[1].Count | Should -Be 2
    }

    It 'keeps the class of each logged failure, as listen-check''s refused start shows it' {
        $log = @(
            '11-17 13:40:31.112  4321  4321 W Kestrel : Starting the listening service failed: android.app.ForegroundServiceStartNotAllowedException'
            '11-17 13:40:31.113  4321  4321 W Kestrel :     at android.app.Service.startForeground(Service.java:775)'
            '11-17 13:41:02.000  4321  4400 W Kestrel : Starting the listening service failed: java.lang.SecurityException'
        )

        $failures = @(Get-FailureDescriptions -Log $log)

        $failures.Count | Should -Be 2
        $failures[0].What | Should -BeExactly 'Starting the listening service'
        $failures[0].Class | Should -BeExactly 'ForegroundServiceStartNotAllowedException'
        $failures[1].Class | Should -BeExactly 'SecurityException'
        $failures[1].Count | Should -Be 1
    }
}

Describe 'The app ops that the scripts set' {
    # Android counts the mode 'default' of RUN_ANY_IN_BACKGROUND and TAKE_AUDIO_FOCUS as restricted (prototype P2), so a
    # script that puts one back must name the op's normal mode, allow or foreground, or ignore.
    It 'sets RUN_ANY_IN_BACKGROUND and TAKE_AUDIO_FOCUS only to allow, ignore or foreground' {
        $scripts = @(Get-ChildItem -Path "$PSScriptRoot\..", "$PSScriptRoot\..\lib" -Filter '*.ps1' -File)
        $lines = @($scripts | Select-String -Pattern 'appops set \S+ (RUN_ANY_IN_BACKGROUND|TAKE_AUDIO_FOCUS)\b(.*)$')

        $lines.Count | Should -BeGreaterThan 0
        foreach ($line in $lines) {
            $modes = @([regex]::Matches($line.Matches[0].Groups[2].Value, '\b(allow|ignore|foreground|default|deny|errored)\b') | ForEach-Object { $_.Value })
            $where = "$($line.Filename):$($line.LineNumber)"
            $modes.Count | Should -BeGreaterThan 0 -Because "$where must name the mode itself"
            @($modes | Where-Object { $_ -notin 'allow', 'ignore', 'foreground' }) | Should -BeNullOrEmpty -Because "$where must not set another mode"
        }
    }
}

Describe 'Android''s record of the listening service' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Session.ps1"
    }

    It 'reads the foreground from Android 13''s record, which prints no types' {
        $dump = @(
            'ACTIVITY MANAGER SERVICES (dumpsys activity services)'
            '  User 0 active services:'
            '  * ServiceRecord{b2c1d3e u0 dev.kestrel/.app.listen.ListeningService}'
            '    intent={cmp=dev.kestrel/.app.listen.ListeningService}'
            '    isForeground=true foregroundId=7101 foregroundNoti=Notification(channel=listening flags=0x6a actions=2 vis=PRIVATE)'
            '    createTime=-3m2s1ms startingBgTimeout=--'
        )

        $record = Get-ForegroundRecord -Dump $dump

        $record.Listed | Should -BeTrue
        $record.Foreground | Should -BeExactly 'true'
        $record.Types | Should -BeExactly 'not printed'
    }

    It 'reads the record as Android 14 and Android 15 to 17 print it' {
        $fourteen = Get-ForegroundRecord -Dump @(
            '  * ServiceRecord{1 u0 dev.kestrel/.app.listen.ListeningService}'
            '    isForeground=false foregroundId=7101 types=00000082 foregroundNoti=null'
        )
        # From Android 15 on, the first line names the package that last started the service before its '}'.
        $later = Get-ForegroundRecord -Dump @(
            '  * ServiceRecord{2 u0 dev.kestrel/.app.listen.ListeningService c:dev.kestrel}'
            '    intent={cmp=dev.kestrel/.app.listen.ListeningService}'
            '    isForeground=true foregroundId=7101 types=0x00000082 foregroundNoti=Notification(channel=listening flags=0x6a actions=2 vis=PRIVATE)'
        )

        $fourteen.Foreground | Should -BeExactly 'false'
        $fourteen.Types | Should -BeExactly '0x82'
        $later.Listed | Should -BeTrue
        $later.Foreground | Should -BeExactly 'true'
        $later.Types | Should -BeExactly '0x82'
    }

    It 'says when the record is missing, and reads no other service''s record' {
        $record = Get-ForegroundRecord -Dump @(
            '  * ServiceRecord{3 u0 dev.kestrel/.app.KestrelAccessibilityService}'
            '    isForeground=true foregroundId=1 foregroundNoti=null types=0x00000001'
            '  Connection bindings to services:'
        )

        $record.Listed | Should -BeFalse
        $record.Foreground | Should -BeExactly 'not printed'
        $record.Types | Should -BeExactly 'not printed'
    }
}

Describe 'The answers of the hooks voice-times and voice-counts' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Session.ps1"
    }

    It 'reads names and values, and the marks of the times' {
        $times = ConvertFrom-HookPairs -Text 'now=81234 started=tile@80012 opened=86010 reacted=wait@86500+640 stopped=none'

        $times['now'] | Should -BeExactly '81234'
        (ConvertFrom-TimeMark -Text $times['started']).Kind | Should -BeExactly 'tile'
        (ConvertFrom-TimeMark -Text $times['started']).At | Should -Be 80012
        (ConvertFrom-TimeMark -Text $times['started']).SinceSpeech | Should -BeNullOrEmpty
        (ConvertFrom-TimeMark -Text $times['reacted']).SinceSpeech | Should -Be 640
        ConvertFrom-TimeMark -Text $times['stopped'] | Should -BeNullOrEmpty
        { ConvertFrom-HookPairs -Text 'waits=1 broken' } | Should -Throw
    }
}
