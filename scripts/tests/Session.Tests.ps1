# Tests of scripts\lib\Session.ps1, which scripts\a71-session.ps1 uses. scripts\test-scripts.ps1 runs these tests in
# Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeEach with the It blocks.')]
param()

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

    It 'takes y and yes, n and no, in any letter case, and asks again for anything else' {
        $script:answers = [System.Collections.Generic.Queue[string]]::new([string[]]@('', 'maybe', 'q', 'YES'))
        $script:asked = [System.Collections.Generic.List[string]]::new()
        $read = { param($Text) $script:asked.Add($Text); $script:answers.Dequeue() }

        Read-YesNoAnswer -Prompt 'Did it work?' -Read $read | Should -BeExactly 'y'
        $script:asked.Count | Should -Be 4
        $script:asked[0] | Should -BeExactly 'Did it work?'
        $script:asked[1] | Should -BeLike 'Type y or yes, or n or no*'
        foreach ($no in 'n', ' No ', 'NO') {
            $script:answers = [System.Collections.Generic.Queue[string]]::new([string[]]@($no))
            Read-YesNoAnswer -Prompt 'Did it work?' -Read $read | Should -BeExactly 'n'
        }
        $script:answers = [System.Collections.Generic.Queue[string]]::new([string[]]@('y'))
        Read-YesNoAnswer -Prompt 'Did it work?' -Read $read | Should -BeExactly 'y'
    }

    It 'takes the emulator''s lock only when its file does not exist, and says who took it' {
        $path = Join-Path $TestDrive 'sdd\emulator.lock'

        Enter-EmulatorLock -Path $path -Owner 'a71-providers session' | Should -BeTrue
        Enter-EmulatorLock -Path $path -Owner 'another task' | Should -BeFalse

        Get-Content -LiteralPath $path -Raw | Should -Match '^a71-providers session, since \d{4}-\d\d-\d\d \d\d:\d\d:\d\d\n$'
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

Describe 'The helpers of the re-checks of plan 2E' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Session.ps1"
    }

    It 'keeps the breakdown of Kestrel''s line of batterystats, without its times' {
        $dump = @(
            '  Estimated power use (mAh):'
            '    Capacity: 4500, Computed drain: 120, actual drain: 100-110'
            '    UID u0a334: 81.4 ( cpu=54.2 (13m 1s 405ms) cpu:fg=50.1 (12m 3s) wakelock=0.04 (2s 3ms) audio=12.3 (30m 0s 0ms) CUSTOM_0=0.5 ) Including smearing: 85.1'
        )

        $use = Get-BatteryUse -Dump $dump -Uid 'u0a334' -Hours 0.5

        $use.MilliampHours | Should -Be 81.4
        $use.Breakdown | Should -BeExactly 'cpu=54.2 cpu:fg=50.1 wakelock=0.04 audio=12.3 CUSTOM_0=0.5'
    }

    It 'reads the time on battery that batterystats counted, in hours' {
        Get-BatteryHours -Dump @('  Time on battery: 30m 0s 0ms (100.0%) realtime, 29m 59s 1ms (99.9%) uptime') | Should -Be 0.5
        Get-BatteryHours -Dump @('  Time on battery: 1h 0m 36s 0ms (100.0%) realtime') | Should -Be 1.01
        Get-BatteryHours -Dump @('no such line') | Should -BeNullOrEmpty
    }

    It 'gives the cost of each transcribed minute in an hour above the quiet baseline' {
        Get-BatteryCost -Baseline 0.8 -PercentPerHour 3.7 -MinutesPerHour 29 | Should -Be 0.1
        Get-BatteryCost -Baseline 0.8 -PercentPerHour 0.9 -MinutesPerHour 0 | Should -BeNullOrEmpty
    }

    It 'reads the memory categories of a process in MB' {
        $dump = @(
            '                   Pss  Private  Private  SwapPss      Rss     Heap     Heap     Heap'
            '                 Total    Dirty    Clean    Dirty    Total     Size    Alloc     Free'
            '  Native Heap   421888   421800        0        0   425000   500000   450000    50000'
            '  Dalvik Heap    10240    10000        0        0    12000    20000    10000    10000'
            '        .so mmap    37888     1000    30000        0    80000'
            '       Other mmap   215040      100   210000        0   220000'
            '          Unknown    97280    97000        0        0    98000'
            '            TOTAL  1003520   600000   300000        0  1100000   520000   460000    60000'
            ' App Summary'
            '           TOTAL PSS:  1003520            TOTAL RSS:  1100000'
        )

        $memory = Get-MemoryCategories -Dump $dump

        Format-MemoryCategories $memory | Should -BeExactly 'Native Heap 412, .so mmap 37, Other mmap 210, Unknown 95, TOTAL 980 (MB)'
        Format-MemoryCategories (Get-MemoryCategories -Dump @('No process found for: dev.kestrel')) | Should -BeExactly 'not listed'
    }

    It 'tells whether a command''s output ended with a line break from its last byte' {
        Test-EndsWithLineBreak -Answer @(' 0a') | Should -BeTrue
        Test-EndsWithLineBreak -Answer @(' 7d') | Should -BeFalse
        Test-EndsWithLineBreak -Answer @('') | Should -BeNullOrEmpty
    }

    It 'reads the time since boot in milliseconds' {
        ConvertFrom-Uptime -Text '12345.67 23456.78' | Should -Be 12345670
        { ConvertFrom-Uptime -Text 'none' } | Should -Throw
    }

    It 'lists what rose between two answers of voice-counts, without the waits and tasks' {
        $before = ConvertFrom-HookPairs -Text 'waits=1 tasks=0 lastTaskWords=0 hits=3 hit-kestrel=3 utterances=4'
        $after = ConvertFrom-HookPairs -Text 'waits=2 tasks=0 lastTaskWords=0 hits=4 hit-kestrel=4 named=1 read-name=1 utterances=5'

        Get-CountRise $before $after | Should -BeExactly 'hit-kestrel+1 named+1 read-name+1 utterances+1'
        Get-CountRise $after $after | Should -BeExactly 'none'
    }

    It 'reads the process state and the oom adj of a process' {
        $dump = @(
            '  *APP* UID 10334 ProcessRecord{1a2b3c 4321:dev.kestrel/u0a334}'
            '    oom adj: max=1001 curRaw=0 setRaw=0 cur=0 set=0'
            '    curSchedGroup=2 setSchedGroup=2 systemNoUi=false'
            '    curProcState=2 mRepProcState=2 pssProcState=2 setProcState=2'
        )

        Get-ProcessState -Dump $dump | Should -BeExactly 'procState=2 adj=0'
        Get-ProcessState -Dump @('  (nothing)') | Should -BeExactly 'not listed'
    }

    It 'keeps the spotter''s setting with the most hits among those with few false hits' {
        $rounds = @(
            [pscustomobject]@{ Setting = 'boost=2.0 threshold=0.15'; Default = $true; Hits = 1; FalseHits = 2 }
            [pscustomobject]@{ Setting = 'boost=3.0 threshold=0.1'; Default = $false; Hits = 4; FalseHits = 5 }
            [pscustomobject]@{ Setting = 'boost=4.0 threshold=0.05'; Default = $false; Hits = 5; FalseHits = 9 }
        )
        $tie = @($rounds[0], [pscustomobject]@{ Setting = 'boost=3.0 threshold=0.1'; Default = $false; Hits = 1; FalseHits = 2 })

        (Select-SpotterWinner $rounds).Setting | Should -BeExactly 'boost=3.0 threshold=0.1'
        (Select-SpotterWinner $tie).Setting | Should -BeExactly 'boost=2.0 threshold=0.15'
        Select-SpotterWinner @($rounds[1]) | Should -BeNullOrEmpty
    }
}

Describe 'The host audio input of kestrel-api37 in the dry runs' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Session.ps1"
        # Set-EmulatorHostMicrophone runs adb through Get-Adb of scripts\lib\Kestrel.ps1; the tests give it this stand-in,
        # which answers the console's commands as the emulator does.
        function Get-Adb { 'Invoke-FakeConsole' }
        function Invoke-FakeConsole {
            $console.Calls.Add(($args -join ' '))
            if ($args[4] -eq 'name') { return @($console.Name, 'OK') }
            if ($args[4] -in 'hostmicon', 'hostmicoff') { return $console.Answer }
            @('KO:  bad sub-command')
        }
    }

    BeforeEach {
        $console = @{ Name = 'kestrel-api37'; Answer = @('OK'); Calls = [System.Collections.Generic.List[string]]::new() }
    }

    It 'turns it off and on through the emulator''s console' {
        Set-EmulatorHostMicrophone -Serial 'emulator-5554' -On $false
        Set-EmulatorHostMicrophone -Serial 'emulator-5554' -On $true

        @($console.Calls) | Should -Be @('-s emulator-5554 emu avd name', '-s emulator-5554 emu avd hostmicoff', '-s emulator-5554 emu avd name', '-s emulator-5554 emu avd hostmicon')
    }

    It 'refuses every device but kestrel-api37, before any change' {
        $console.Name = 'zoran'

        { Set-EmulatorHostMicrophone -Serial 'emulator-5554' -On $false } | Should -Throw 'emulator-5554 is not the emulator kestrel-api37.'
        { Set-EmulatorHostMicrophone -Serial 'R58N66RVD7Z' -On $false } | Should -Throw 'R58N66RVD7Z is not the emulator kestrel-api37.'
        @($console.Calls | Where-Object { $_ -like '*hostmic*' }).Count | Should -Be 0
    }

    It 'fails when the console does not take the command' {
        $console.Answer = @('KO:  bad sub-command')

        { Set-EmulatorHostMicrophone -Serial 'emulator-5554' -On $false } | Should -Throw 'The emulator''s console did not take ''avd hostmicoff''.'
    }
}

Describe 'The owner''s settings and the test PIN' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Session.ps1"
    }

    It 'reads the owner''s settings for the A71 sessions, with defaults for a missing file or field, and refuses a bad number' {
        $path = Join-Path $TestDrive 'a71-config.json'
        Set-Content -LiteralPath $path -Value '{"callNumber": "064 123-4567", "testContacts": true, "speakerTargetDb": -30, "pin": "2468", "later": {"plan": 5}}'

        $config = Read-A71Config $path

        $config.CallNumber | Should -BeExactly '0641234567'
        $config.TestContacts | Should -BeTrue
        $config.SpeakerTargetDb | Should -Be -30
        ($config | Out-String) | Should -Not -Match '2468'
        $config.PSObject.Properties.Name | Should -Not -Contain 'pin'
        $config.later.plan | Should -Be 5
        $missing = Read-A71Config (Join-Path $TestDrive 'none.json')
        $missing.CallNumber | Should -BeNullOrEmpty
        $missing.TestContacts | Should -BeFalse
        $missing.SpeakerTargetDb | Should -Be -26
        Set-Content -LiteralPath $path -Value '{"callNumber": "112"}'
        { Read-A71Config $path } | Should -Throw '*callNumber must have 5 to 15 digits.'
    }

    It 'checks the A71''s test PIN, and says what is missing without the PIN' {
        $path = Join-Path $TestDrive 'a71-config.json'
        Set-Content -LiteralPath $path -Value '{"pin": "024680", "callNumber": "0641234567"}'

        Send-TestPin -ConfigPath $path -CheckOnly | Should -BeNullOrEmpty
        { Send-TestPin -ConfigPath (Join-Path $TestDrive 'none.json') -CheckOnly } | Should -Throw '*test PIN is missing, because*does not exist*key "pin"*'
        Set-Content -LiteralPath $path -Value '{"callNumber": "0641234567"}'
        { Send-TestPin -ConfigPath $path -CheckOnly } | Should -Throw '*has no key "pin"*'
        Set-Content -LiteralPath $path -Value '{"pin": "12ab"}'
        $message = try { Send-TestPin -Serial 'R58N' -ConfigPath $path } catch { $_.Exception.Message }
        $message | Should -Match 'must be 4 to 16 digits'
        $message | Should -Not -Match '12ab'
        # A number: PowerShell 7 refuses the leading zero, and Windows PowerShell 5.1 would read another PIN.
        foreach ($text in '{"pin": 024680}', '{"pin": 2468}') {
            Set-Content -LiteralPath $path -Value $text
            $message = try { Send-TestPin -ConfigPath $path -CheckOnly } catch { $_.Exception.Message }
            $message | Should -Match 'quotes'
            $message | Should -Not -Match '2468'
        }
    }

    Context 'The unlock with the test PIN' {
        BeforeAll {
            . "$PSScriptRoot\..\lib\Kestrel.ps1"
            . "$PSScriptRoot\..\lib\Oracle.ps1"
            # A phone that Assert-TestDevice accepted, without asking a device.
            $KestrelTestDevices['R58N'] = [pscustomobject]@{ Property = 'ro.product.model'; Pattern = 'SM-A715F*' }
            # A fake adb that fails and repeats its whole command in its error, as adb can.
            $fake = Join-Path $TestDrive 'failing-adb.ps1'
            Set-Content -LiteralPath $fake -Value '[Console]::Error.WriteLine("error: closed while running: $($args -join '' '')"); exit 1'
            $fakeAdb = Join-Path $TestDrive 'failing-adb.cmd'
            Set-Content -LiteralPath $fakeAdb -Value "@echo off`r`npowershell.exe -NoProfile -ExecutionPolicy Bypass -File ""$fake"" %*`r`nexit /b %ERRORLEVEL%"
            # The owner's configuration with the test PIN.
            $config = Join-Path $TestDrive 'pin-config.json'
            Set-Content -LiteralPath $config -Value '{"pin": "2468"}'
        }

        BeforeEach {
            Mock Start-Sleep { }
            $script:TestPinRefused = $false
            # Keyguard: 'true', 'false' or 'unknown' (no line in the dump); Occluded: whether an activity shows over it;
            # Pad: whether the PIN pad's entry shows.
            $phone = @{ Keyguard = 'true'; Occluded = 'false'; Pad = $true; Typed = 0; Commands = [System.Collections.Generic.List[string]]::new() }
            Mock Invoke-SettingsCommand {
                $phone.Commands.Add($Command)
                if ($Command -eq 'dumpsys activity activities') {
                    if ($phone.Keyguard -ne 'unknown') { "  mKeyguardShowing=$($phone.Keyguard)" } else { '  (no keyguard line)' }
                    "    Occluded=$($phone.Occluded) DismissingKeyguardActivity=null at display=0"
                }
                if ($Command -like 'uiautomator dump*') { if ($phone.Pad) { '1' } else { '0' } }
                if ($Command -eq 'wm size') { 'Physical size: 1080x2400' }
            }
        }

        It 'types the PIN once, only while the lock screen''s PIN pad shows' {
            Mock Send-TestPin { if (-not $CheckOnly) { $phone.Typed++; $phone.Keyguard = 'false' } }

            Unlock-TestPhone -Serial 'R58N' -ConfigPath $config | Should -BeTrue

            $phone.Typed | Should -Be 1
            @($phone.Commands | Where-Object { $_ -match '2468' }).Count | Should -Be 0
            $phone.Commands.IndexOf('wm dismiss-keyguard') | Should -BeGreaterThan -1
            # One look at the screen's hierarchy, in the shell's folder, which exists before the first unlock.
            @($phone.Commands | Where-Object { $_ -like 'uiautomator dump *' }).Count | Should -Be 1
            @($phone.Commands | Where-Object { $_ -like 'uiautomator dump /data/local/tmp/*' }).Count | Should -Be 1
            Unlock-TestPhone -Serial 'R58N' -ConfigPath $config | Should -BeTrue
            $phone.Typed | Should -Be 1
        }

        It 'types nothing when Android does not say clearly that the PIN pad shows, or an activity shows over the lock screen' {
            Mock Send-TestPin { if (-not $CheckOnly) { $phone.Typed++ } }
            $phone.Keyguard = 'unknown'

            Unlock-TestPhone -Serial 'R58N' -ConfigPath $config | Should -BeFalse
            $phone.Keyguard = 'true'
            $phone.Occluded = 'true'
            Unlock-TestPhone -Serial 'R58N' -ConfigPath $config | Should -BeFalse
            $phone.Occluded = 'false'
            $phone.Pad = $false
            Unlock-TestPhone -Serial 'R58N' -ConfigPath $config | Should -BeFalse

            $phone.Typed | Should -Be 0
            @($phone.Commands | Where-Object { $_ -like 'input swipe *' }).Count | Should -Be 1
        }

        It 'types a PIN that did not unlock the phone only once in the run' {
            Mock Send-TestPin { if (-not $CheckOnly) { $phone.Typed++ } }

            { Unlock-TestPhone -Serial 'R58N' -ConfigPath $config } | Should -Throw 'the unlock failed'
            { Unlock-TestPhone -Serial 'R58N' -ConfigPath $config } | Should -Throw '*earlier in this run*'

            $phone.Typed | Should -Be 1
        }

        It 'says only that the unlock failed when adb fails, and no output, warning or error holds the PIN' {
            Mock Get-Adb { $fakeAdb }

            $output = & {
                try { Unlock-TestPhone -Serial 'R58N' -ConfigPath $config } catch { $_; $_.Exception.Message; $_.ScriptStackTrace }
                try { Unlock-TestPhone -Serial 'R58N' -ConfigPath $config } catch { $_; $_.Exception.Message }
            } *>&1 | Out-String

            $output | Should -Match 'the unlock failed'
            $output | Should -Not -Match '2468'
        }
    }
}
