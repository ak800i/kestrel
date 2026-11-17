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
}
