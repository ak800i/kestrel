# Tests of the helpers in scripts\lib\Kestrel.ps1. The settings helpers run with fake-adb.cmd instead of a device. The
# fake adb runs in a process of its own, as adb does, so its error lines reach the helpers as a native program's error
# output, which Windows PowerShell 5.1 and PowerShell 7 handle differently. scripts\test-scripts.ps1 runs these tests in
# both shells.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSAvoidUsingInvokeExpression', '',
    Justification = 'A test runs the command that a warning asks the owner to paste.')]
param()

Describe 'Get-DeviceTaskArgument' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
    }

    It 'returns the argument that names a device task: <_>' -ForEach @(
        'installDebug', ':app:installDebug', ':testapp:installDebug', 'installDebugAndroidTest', 'uninstallAll',
        ':app:uninstallDebug', 'connectedCheck', 'connectedAndroidTest', ':app:connectedDebugAndroidTest', 'deviceCheck',
        ':core:privacy:connectedDebugAndroidTest'
    ) {
        Get-DeviceTaskArgument -Arguments @('check', $_, '--console=plain') | Should -BeExactly @($_)
    }

    It 'returns nothing for tasks and options that do not reach a device: <_>' -ForEach @(
        'check', 'assembleDebug', 'kestrelAudit', ':app:assembleDebugAndroidTest', 'testDebugUnitTest', 'lint', '--stop',
        '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.InstallTest'
    ) {
        Get-DeviceTaskArgument -Arguments @($_) | Should -BeNullOrEmpty
    }
}

Describe 'The settings helpers' {
    BeforeAll {
        # The scripts run with 'Stop', under which Windows PowerShell 5.1 stops at adb's first error line unless the
        # helpers protect themselves, so the tests run with it too.
        $ErrorActionPreference = 'Stop'
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
        $fakeAdb = Join-Path $PSScriptRoot 'fake-adb.cmd'
        Mock Get-Adb { $fakeAdb }
        Mock Start-Sleep {}

        # Gives the fake device these secure settings and makes its next commands fail as often as -Failures says.
        function Set-FakeDevice([System.Collections.IDictionary]$Settings = @{}, [int]$Failures = 0) {
            $state = [ordered]@{ settings = $Settings; failures = $Failures; calls = @() }
            [IO.File]::WriteAllText($env:KESTREL_FAKE_ADB, (ConvertTo-Json -Depth 3 -InputObject $state))
        }

        # Returns the fake device's settings, its remaining failures and the shell commands that it received, in order.
        function Get-FakeDevice {
            [IO.File]::ReadAllText($env:KESTREL_FAKE_ADB) | ConvertFrom-Json
        }
    }

    BeforeEach {
        $env:KESTREL_FAKE_ADB = Join-Path $TestDrive 'device.json'
        Set-FakeDevice
    }

    AfterAll {
        Remove-Item Env:\KESTREL_FAKE_ADB -ErrorAction SilentlyContinue
    }

    Context 'Get-SecureSetting' {
        It 'returns null for an unset setting' {
            Get-SecureSetting -Serial emulator-5554 -Name x | Should -BeExactly 'null'
        }

        It 'returns an empty string, not null, for an empty value' {
            Set-FakeDevice -Settings @{ x = '' }
            $value = Get-SecureSetting -Serial emulator-5554 -Name x
            $value | Should -BeOfType [string]
            $value | Should -BeExactly ''
        }

        It 'throws the device''s complaint after 3 failed tries' {
            Set-FakeDevice -Settings @{ x = 'value' } -Failures 10
            $thrown = { Get-SecureSetting -Serial emulator-5554 -Name x } | Should -Throw -PassThru
            $thrown.Exception.Message | Should -BeExactly "Could not run 'settings get secure x' on emulator-5554: Bad arguments"
            (Get-FakeDevice).calls | Should -BeExactly (@('settings get secure x && echo kestrel-end') * 3)
        }

        It 'returns the value when only the first 2 tries fail' {
            Set-FakeDevice -Settings @{ x = 'value' } -Failures 2
            Get-SecureSetting -Serial emulator-5554 -Name x | Should -BeExactly 'value'
            (Get-FakeDevice).calls | Should -HaveCount 3
        }

        It 'names adb''s complaint when the device is missing' {
            $thrown = { Get-SecureSetting -Serial missing -Name x } | Should -Throw -PassThru
            $thrown.Exception.Message | Should -BeExactly "Could not run 'settings get secure x' on missing: adb.exe: device 'missing' not found"
        }
    }

    Context 'Set-SecureSetting' {
        It 'stores a value with a single quote and returns $true' {
            Set-FakeDevice -Settings @{ x = 'old' }
            Set-SecureSetting -Serial emulator-5554 -Name x -Value "it's" | Should -BeExactly $true
            (Get-FakeDevice).settings.x | Should -BeExactly "it's"
        }

        It 'deletes the setting for null and returns $true' {
            Set-FakeDevice -Settings @{ x = 'old' }
            Set-SecureSetting -Serial emulator-5554 -Name x -Value 'null' | Should -BeExactly $true
            (Get-FakeDevice).settings.PSObject.Properties.Name | Should -Not -Contain 'x'
        }

        It 'returns $false and warns with the reason and a command that stores the value when the device fails' {
            # The device fails all 3 tries of the put, and then answers again.
            Set-FakeDevice -Settings @{ x = 'old' } -Failures 3
            $output = Set-SecureSetting -Serial emulator-5554 -Name x -Value "it's" 3>&1
            $output | Where-Object { $_ -isnot [System.Management.Automation.WarningRecord] } | Should -BeExactly $false
            $warnings = @($output | Where-Object { $_ -is [System.Management.Automation.WarningRecord] })
            $warnings | Should -HaveCount 1
            $message = $warnings[0].Message
            $message | Should -BeLike "Could not put back the setting x of emulator-5554 (Could not run 'settings put secure x 'it'\''s'' on emulator-5554: Bad arguments). Run: *"

            Invoke-Expression $message.Substring($message.IndexOf('Run: ') + 'Run: '.Length)
            (Get-FakeDevice).settings.x | Should -BeExactly "it's"
            (Get-FakeDevice).calls[-1] | Should -BeExactly "settings put secure x 'it'\''s'"
        }
    }

    Context 'Restore-AccessibilitySettings' {
        It 'writes back only the changed settings, the list of services first, and returns $true' {
            $reader = 'com.example.reader/com.example.reader.ReaderService'
            $kestrel = 'dev.kestrel/dev.kestrel.app.KestrelAccessibilityService'
            # The settings are saved from the device, as connected-test.ps1 saves them. So the test also fails when
            # Get-AccessibilitySettings misses a setting, such as the touch-and-hold delay.
            Set-FakeDevice -Settings ([ordered]@{
                accessibility_button_targets   = $reader
                accessibility_enabled          = '1'
                enabled_accessibility_services = $reader
                long_press_timeout             = '400'
                touch_exploration_enabled      = '0'
            })
            $saved = Get-AccessibilitySettings -Serial emulator-5554
            Set-FakeDevice -Settings ([ordered]@{
                # Android cleared the shortcut when the tests turned the reader off, the tests added a setting, and
                # GestureTest lengthened the touch-and-hold delay.
                accessibility_button_targets   = ''
                accessibility_enabled          = '0'
                accessibility_qs_targets       = $kestrel
                default_input_method           = 'com.example.keyboard/.Keyboard'
                enabled_accessibility_services = $kestrel
                long_press_timeout             = '1500'
                touch_exploration_enabled      = '0'
            })

            Restore-AccessibilitySettings -Serial emulator-5554 -Saved $saved | Should -BeExactly $true

            $device = Get-FakeDevice
            $device.settings.enabled_accessibility_services | Should -BeExactly $reader
            $device.settings.accessibility_enabled | Should -BeExactly '1'
            $device.settings.accessibility_button_targets | Should -BeExactly $reader
            $device.settings.PSObject.Properties.Name | Should -Not -Contain 'accessibility_qs_targets'
            $device.settings.default_input_method | Should -BeExactly 'com.example.keyboard/.Keyboard'
            $device.settings.long_press_timeout | Should -BeExactly '400'
            $written = @($device.calls | Where-Object { $_ -match '^settings (put|delete) ' } | ForEach-Object { ($_ -split ' ')[3] })
            $written[0..1] | Should -BeExactly @('enabled_accessibility_services', 'accessibility_enabled')
            $written | Sort-Object | Should -BeExactly @(
                'accessibility_button_targets', 'accessibility_enabled', 'accessibility_qs_targets', 'enabled_accessibility_services',
                'long_press_timeout')
        }

        It 'returns $false and warns with the saved values when the device cannot be read' {
            Set-FakeDevice -Failures 3
            $saved = [ordered]@{
                enabled_accessibility_services = 'com.example.reader/com.example.reader.ReaderService'
                accessibility_enabled          = '1'
            }

            $output = Restore-AccessibilitySettings -Serial emulator-5554 -Saved $saved 3>&1

            $output | Where-Object { $_ -isnot [System.Management.Automation.WarningRecord] } | Should -BeExactly $false
            $warnings = @($output | Where-Object { $_ -is [System.Management.Automation.WarningRecord] })
            $warnings | Should -HaveCount 1
            $lines = $warnings[0].Message -split "`n"
            $lines[0] | Should -BeLike 'Could not read the accessibility settings of emulator-5554 (*: Bad arguments), so they were not put back.*'
            $lines | Should -Contain 'enabled_accessibility_services=com.example.reader/com.example.reader.ReaderService'
            $lines | Should -Contain 'accessibility_enabled=1'
            (Get-FakeDevice).calls | Should -BeExactly (@('settings list secure && echo kestrel-end') * 3)
        }
    }
}

Describe 'Get-TestResultCounts' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
    }

    It 'adds up the suites of every result file below the folder' {
        $folder = Join-Path $TestDrive 'results'
        New-Item -ItemType Directory -Path (Join-Path $folder 'device') | Out-Null
        Set-Content -LiteralPath (Join-Path $folder 'TEST-a.xml') -Value '<?xml version="1.0"?><testsuite name="a" tests="3" failures="1" errors="0" skipped="1"><testcase name="x"/></testsuite>'
        Set-Content -LiteralPath (Join-Path $folder 'device\TEST-b.xml') -Value '<?xml version="1.0"?><testsuite name="b" tests="2" failures="0" errors="1" skipped="0"/>'

        $counts = Get-TestResultCounts -Folder $folder

        $counts.Tests | Should -Be 5
        $counts.Failures | Should -Be 1
        $counts.Errors | Should -Be 1
        $counts.Skipped | Should -Be 1
    }

    It 'counts no tests in a folder that does not exist' {
        (Get-TestResultCounts -Folder (Join-Path $TestDrive 'none')).Tests | Should -Be 0
    }

    It 'counts each suite once in a file whose root holds the totals, as the Android Gradle plugin writes it' {
        $folder = Join-Path $TestDrive 'agp'
        New-Item -ItemType Directory -Path $folder | Out-Null
        Set-Content -LiteralPath (Join-Path $folder 'TEST-device.xml') -Value ('<?xml version="1.0"?><testsuites tests="7" failures="1" errors="1" skipped="2">' +
            '<testsuite name="a" tests="4" failures="1" errors="0" skipped="2"/><testsuite name="b" tests="3" failures="0" errors="1" skipped="0"/></testsuites>')

        $counts = Get-TestResultCounts -Folder $folder

        $counts.Tests | Should -Be 7
        $counts.Failures | Should -Be 1
        $counts.Errors | Should -Be 1
        $counts.Skipped | Should -Be 2
    }
}

Describe 'ConvertFrom-VoiceState' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
    }

    It 'reads every name and value of the hook''s answer' {
        $state = ConvertFrom-VoiceState 'mode=listening work=idle speaking=false microphone=true recognition=all screenOn=true micBusy=false session=open clips=off'

        $state.mode | Should -BeExactly 'listening'
        $state.session | Should -BeExactly 'open'
        $state.clips | Should -BeExactly 'off'
        @($state.Keys) | Should -HaveCount 9
    }

    It 'throws for a text that is not the hook''s answer: ''<_>''' -ForEach @('', 'error: the command failed', 'mode=off work=idle') {
        $text = $_
        { ConvertFrom-VoiceState $text } | Should -Throw -ExpectedMessage "Not an answer of the voice-state hook: '$text'"
    }
}

Describe 'Test-ShortcutTarget' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
    }

    It 'returns $true when the shortcut''s line names a service of the package' {
        $dump = @(
            '     button mode: 1'
            '     shortcut key:{com.example.reader/com.example.reader.ReaderService, dev.kestrel/dev.kestrel.app.KestrelAccessibilityService}'
            '     button:{}'
        )

        Test-ShortcutTarget -Dump $dump -Package dev.kestrel | Should -BeExactly $true
    }

    It 'returns $false when <Name>' -ForEach @(
        @{ Name = 'the line names another package'; Dump = @('     shortcut key:{com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService}') }
        @{ Name = 'the line names a package that only starts like it'; Dump = @('     shortcut key:{dev.kestrel.testapp/dev.kestrel.testapp.Service}') }
        @{ Name = 'the line names no service'; Dump = @('     shortcut key:{}') }
        @{ Name = 'there is no such line'; Dump = @('     button:{}') }
    ) {
        Test-ShortcutTarget -Dump $Dump -Package dev.kestrel | Should -BeExactly $false
    }
}

Describe 'Test-RecordingSilenced' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
    }

    It 'returns $false when the newest recording of the package is not silenced' {
        $dump = @(
            '11-16 10:21:56:811 rec start riid:1831 uid:10331 session:2001 src:VOICE_RECOGNITION silenced pack:dev.kestrel'
            '11-16 10:21:57:022 rec start riid:1839 uid:10331 session:2009 src:VOICE_RECOGNITION not silenced pack:dev.kestrel'
            '11-16 10:21:57:123 rec stop riid:1839 uid:10331 session:2009 src:VOICE_RECOGNITION silenced pack:dev.kestrel'
            '11-16 10:21:57:200 rec start riid:1840 uid:10200 session:2010 src:MIC silenced pack:dev.kestrel.testapp'
        )

        Test-RecordingSilenced -Dump $dump -Package dev.kestrel | Should -BeExactly $false
    }

    It 'returns $true when Android silenced the newest recording of the package' {
        $dump = @(
            '11-16 10:21:57:022 rec start riid:1839 uid:10331 session:2009 src:VOICE_RECOGNITION not silenced pack:dev.kestrel'
            '11-16 10:21:58:410 rec update riid:1839 uid:10331 session:2009 src:VOICE_RECOGNITION silenced pack:dev.kestrel'
        )

        Test-RecordingSilenced -Dump $dump -Package dev.kestrel | Should -BeExactly $true
    }

    It 'returns $null when no recording names the package' {
        $dump = @(
            '11-16 10:21:57:200 rec start riid:1840 uid:10200 session:2010 src:MIC not silenced pack:dev.kestrel.testapp'
            'some other line'
        )

        Test-RecordingSilenced -Dump $dump -Package dev.kestrel | Should -BeNullOrEmpty
    }
}

Describe 'Get-RecordingId' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
    }

    It 'returns the riid of each recording of the package once, from its rec start and rec update lines' {
        $dump = @(
            '11-16 16:02:10:101 rec start riid:111 uid:10331 session:105 src:VOICE_RECOGNITION not silenced pack:dev.kestrel'
            '11-16 16:02:10:350 rec update riid:111 uid:10331 session:105 src:VOICE_RECOGNITION not silenced pack:dev.kestrel'
            '11-16 16:02:31:002 rec stop riid:111 uid:10331 session:105 src:VOICE_RECOGNITION not silenced pack:dev.kestrel'
            '11-16 16:02:33:540 rec update riid:119 uid:10331 session:113 src:VOICE_RECOGNITION not silenced pack:dev.kestrel'
            '11-16 16:02:34:000 rec start riid:120 uid:10200 session:114 src:MIC not silenced pack:dev.kestrel.testapp'
            '11-16 16:02:35:000 rec stop riid:121 uid:10331 session:115 src:VOICE_RECOGNITION not silenced pack:dev.kestrel'
        )

        Get-RecordingId -Dump $dump -Package dev.kestrel | Should -BeExactly @('111', '119')
    }

    It 'returns nothing when no recording names the package' {
        $dump = @(
            '11-16 16:02:34:000 rec start riid:120 uid:10200 session:114 src:MIC not silenced pack:dev.kestrel.testapp'
            'some other line'
        )

        @(Get-RecordingId -Dump $dump -Package dev.kestrel).Count | Should -Be 0
    }
}

Describe 'Get-NewRecordingState' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
    }

    It 'returns ''not silenced'' for a new recording that has only a rec update line' {
        # As kestrel-api37 showed it: the new recording 119 has no rec start line.
        $dump = @(
            '11-16 16:02:31:002 rec stop riid:111 uid:10331 session:105 src:VOICE_RECOGNITION not silenced pack:dev.kestrel'
            '11-16 16:02:33:540 rec update riid:119 uid:10331 session:113 src:VOICE_RECOGNITION not silenced pack:dev.kestrel'
        )

        Get-NewRecordingState -Dump $dump -Package dev.kestrel -Before @('111') | Should -BeExactly 'not silenced'
    }

    It 'returns ''silenced'' when Android silenced the new recording' {
        $dump = @(
            '11-16 16:02:33:100 rec start riid:119 uid:10331 session:113 src:VOICE_RECOGNITION not silenced pack:dev.kestrel'
            '11-16 16:02:33:540 rec update riid:119 uid:10331 session:113 src:VOICE_RECOGNITION silenced pack:dev.kestrel'
        )

        Get-NewRecordingState -Dump $dump -Package dev.kestrel -Before @('111') | Should -BeExactly 'silenced'
    }

    It 'returns ''not silenced'' for a recording when there was none before' {
        $dump = @('11-16 16:02:33:540 rec start riid:119 uid:10331 session:113 src:VOICE_RECOGNITION not silenced pack:dev.kestrel')

        Get-NewRecordingState -Dump $dump -Package dev.kestrel -Before @() | Should -BeExactly 'not silenced'
    }

    It 'returns ''none'' when <Name>' -ForEach @(
        @{
            Name = 'the newest line belongs to a recording that was there before'
            Dump = @(
                '11-16 16:02:10:101 rec start riid:111 uid:10331 session:105 src:VOICE_RECOGNITION not silenced pack:dev.kestrel'
                '11-16 16:02:10:350 rec update riid:111 uid:10331 session:105 src:VOICE_RECOGNITION not silenced pack:dev.kestrel'
            )
        }
        @{
            Name = 'only a recording of a package that starts like it is new'
            Dump = @(
                '11-16 16:02:10:101 rec start riid:111 uid:10331 session:105 src:VOICE_RECOGNITION not silenced pack:dev.kestrel'
                '11-16 16:02:34:000 rec start riid:120 uid:10200 session:114 src:MIC not silenced pack:dev.kestrel.testapp'
            )
        }
        @{
            Name = 'no line names the package'
            Dump = @('some other line')
        }
    ) {
        Get-NewRecordingState -Dump $Dump -Package dev.kestrel -Before @('111') | Should -BeExactly 'none'
    }
}
