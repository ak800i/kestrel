# Tests of the helpers in scripts\lib\Kestrel.ps1. The settings helpers run with fake-adb.cmd instead of a device. The
# fake adb runs in a process of its own, as adb does, so its error lines reach the helpers as a native program's error
# output, which Windows PowerShell 5.1 and PowerShell 7 handle differently. scripts\test-scripts.ps1 runs these tests in
# both shells.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSAvoidUsingInvokeExpression', '',
    Justification = 'A test runs the command that a warning asks the owner to paste.')]
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

Describe 'Get-GradleTaskKind' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
    }

    It 'knows a connected test by its full name: <_>' -ForEach @(
        'connectedCheck', 'connectedAndroidTest', ':app:connectedDebugAndroidTest', ':core:privacy:connectedDebugAndroidTest'
    ) {
        Get-GradleTaskKind $_ | Should -BeExactly 'ConnectedTest'
    }

    It 'counts every other name that can reach a device task as a device task, also an abbreviated one: <_>' -ForEach @(
        'installDebug', ':app:installDebug', ':testapp:installDebug', 'installDebugAndroidTest', 'uninstallAll',
        ':app:uninstallDebug', 'deviceCheck', 'deviceAndroidTest', 'connectedcheck', 'UNINSTALLALL', 'iD', ':app:iD', 'inst',
        'i', 'uA', 'unA', 'cDAT', 'CDAT', 'cC', 'c-d-a-t', 'dC', 'de'
    ) {
        Get-GradleTaskKind $_ | Should -BeExactly 'Device'
    }

    It 'counts a task that cannot reach a device as a build: <_>' -ForEach @(
        'check', 'assembleDebug', 'kestrelAudit', ':app:assembleDebugAndroidTest', 'testDebugUnitTest', 'lint', 'clean',
        'compileDebugKotlin', 'dependencies', 'init', 'help', ':build-logic:convention:check', ':tools:eval:installDist',
        ':tools:catalog-sync:syncCatalog'
    ) {
        Get-GradleTaskKind $_ | Should -BeExactly 'Build'
    }
}

Describe 'Get-GradleArgumentKinds' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
    }

    It 'reads <Name>' -ForEach @(
        @{ Name = 'a value of --tests'; Arguments = @(':app:testDebugUnitTest', '--tests', 'dev.kestrel.app.X'); Kinds = @('Build', 'Option', 'Value') }
        @{ Name = 'a value attached to --tests'; Arguments = @(':app:testDebugUnitTest', '--tests=dev.kestrel.app.X'); Kinds = @('Build', 'Option') }
        @{ Name = 'a task that -x excludes'; Arguments = @('check', '-x', 'connectedCheck'); Kinds = @('Build', 'Option', 'ExcludedTask') }
        @{ Name = 'a task that --exclude-task excludes'; Arguments = @('check', '--exclude-task', 'installDebug'); Kinds = @('Build', 'Option', 'ExcludedTask') }
        @{ Name = 'a task attached to -x'; Arguments = @('check', '-xconnectedCheck', '-x=installDebug'); Kinds = @('Build', 'Option', 'Option') }
        @{ Name = 'values of -p, -P and -D'; Arguments = @('-p', 'device-folder', '-P', 'name=value', '-D', 'device.name=x', 'check'); Kinds = @('Option', 'Value', 'Option', 'Value', 'Option', 'Value', 'Build') }
        @{ Name = 'a long option after one dash'; Arguments = @('-console', 'plain', 'check'); Kinds = @('Option', 'Value', 'Build') }
        @{ Name = 'a device task after an option''s value'; Arguments = @('--warning-mode', 'all', 'installDebug', '--console=plain', 'cDAT'); Kinds = @('Option', 'Value', 'Device', 'Option', 'Device') }
        @{ Name = 'a device task after an option that takes no value'; Arguments = @('--rerun-tasks', 'installDebug', '-qs', 'uA'); Kinds = @('Option', 'Device', 'Option', 'Device') }
        @{ Name = 'a device task after an option that Gradle does not know'; Arguments = @('--unknown', 'installDebug'); Kinds = @('Option', 'Device') }
        @{ Name = 'a device task after -x when -x follows --, where it is no option of Gradle''s'; Arguments = @('--', 'check', '-x', 'installDebug'); Kinds = @('Option', 'Build', 'Option', 'Device') }
        @{ Name = 'a value of --tests after --'; Arguments = @('--', 'testDebugUnitTest', '--tests', 'dev.kestrel.app.X'); Kinds = @('Option', 'Build', 'Option', 'Value') }
        @{ Name = 'an option instead of a value'; Arguments = @('--project-dir', '--offline', 'installDebug'); Kinds = @('Option', 'Option', 'Device') }
        @{ Name = '--serial'; Arguments = @('--serial=emulator-5554', '--serial', 'check'); Kinds = @('Serial', 'Serial', 'Build') }
    ) {
        $parsed = @(Get-GradleArgumentKinds -Arguments $Arguments)
        $parsed.Argument | Should -BeExactly $Arguments
        $parsed.Kind | Should -BeExactly $Kinds
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

        # Gives the fake device these secure settings and makes its next commands fail as often as -Failures says. It is
        # kestrel-api37 unless -Avd and -Model say otherwise. With -KeepsTiles, its SystemUI keeps the list of Quick
        # Settings tiles itself, as on Android 17 (observed).
        function Set-FakeDevice([System.Collections.IDictionary]$Settings = @{}, [int]$Failures = 0, [string]$Avd = 'kestrel-api37',
            [string]$Model = 'sdk_gphone64_x86_64', [switch]$KeepsTiles) {
            $state = [ordered]@{
                avd = $Avd; model = $Model; settings = $Settings; keepsTiles = [bool]$KeepsTiles; failures = $Failures; calls = @(); refused = @()
            }
            [IO.File]::WriteAllText($env:KESTREL_FAKE_ADB, (ConvertTo-Json -Depth 3 -InputObject $state))
        }

        # Returns the fake device's settings, its remaining failures, and the settings commands that it ran and refused.
        function Get-FakeDevice {
            [IO.File]::ReadAllText($env:KESTREL_FAKE_ADB) | ConvertFrom-Json
        }
    }

    BeforeEach {
        $env:KESTREL_FAKE_ADB = Join-Path $TestDrive 'device.json'
        Set-FakeDevice
        $KestrelTestDevices.Clear()
        Assert-TestDevice -Serial emulator-5554
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
            (Get-FakeDevice).calls | Should -BeExactly (@('settings get secure x && echo && echo kestrel-end') * 3)
        }

        It 'returns the value when only the first 2 tries fail' {
            Set-FakeDevice -Settings @{ x = 'value' } -Failures 2
            Get-SecureSetting -Serial emulator-5554 -Name x | Should -BeExactly 'value'
            (Get-FakeDevice).calls | Should -HaveCount 3
        }

        It 'keeps the last line of an answer that ends without a line break, as One UI''s dumpsys accessibility does' {
            Invoke-SettingsCommand -Serial emulator-5554 -Command 'printf %s last-line' | Should -BeExactly @('last-line')
            (Get-FakeDevice).calls | Should -BeExactly @('printf %s last-line && echo && echo kestrel-end')
        }

        It 'names adb''s complaint when the device is missing' {
            # Assert-TestDevice accepted the device, which then went away.
            $KestrelTestDevices['missing'] = @{ Property = 'ro.boot.qemu.avd_name'; Pattern = 'kestrel-api37' }
            $thrown = { Get-SecureSetting -Serial missing -Name x } | Should -Throw -PassThru
            $thrown.Exception.Message | Should -BeExactly "Could not run 'settings get secure x' on missing: adb.exe: device 'missing' not found"
        }
    }

    Context 'The device check' {
        It 'runs commands only on the device that Assert-TestDevice accepted: <Name>' -ForEach @(
            @{ Name = 'kestrel-api37'; Avd = 'kestrel-api37'; Model = 'sdk_gphone64_x86_64'; Daily = $false; OtherAvd = 'zoran'; OtherModel = 'sdk_gphone64_x86_64' }
            @{ Name = 'the Galaxy A71'; Avd = ''; Model = 'SM-A715F'; Daily = $false; OtherAvd = ''; OtherModel = 'SM-S916B' }
            @{ Name = 'the Galaxy S23+ with -AllowDailyPhone'; Avd = ''; Model = 'SM-S916B'; Daily = $true; OtherAvd = ''; OtherModel = 'SM-A715F' }
        ) {
            Set-FakeDevice -Settings @{ x = 'old' } -Avd $Avd -Model $Model
            Assert-TestDevice -Serial emulator-5554 -AllowDailyPhone:$Daily
            Get-SecureSetting -Serial emulator-5554 -Name x | Should -BeExactly 'old'

            # Another device took the serial after the check.
            Set-FakeDevice -Settings @{ x = 'old' } -Avd $OtherAvd -Model $OtherModel
            $thrown = { Invoke-SettingsCommand -Serial emulator-5554 -Command 'settings delete secure x || true' } | Should -Throw -PassThru

            $thrown.Exception.Message | Should -BeExactly ("Refusing to run 'settings delete secure x || true' on emulator-5554: it is no " +
                'longer the test device that Assert-TestDevice accepted.')
            $device = Get-FakeDevice
            $device.settings.x | Should -BeExactly 'old'
            $device.calls | Should -BeNullOrEmpty
            # Once: a refusal is not tried again.
            $device.refused | Should -BeExactly @('settings delete secure x || true && echo && echo kestrel-end')
        }

        It 'throws and sends nothing when Assert-TestDevice has not accepted the serial' {
            $KestrelTestDevices.Clear()

            { Get-SecureSetting -Serial emulator-5554 -Name x } | Should -Throw -ExpectedMessage 'Run Assert-TestDevice -Serial emulator-5554 before a command on that device.'

            $device = Get-FakeDevice
            $device.calls | Should -BeNullOrEmpty
            $device.refused | Should -BeNullOrEmpty
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

    Context 'Set-QuickSettingsTiles' {
        It 'puts the list back through SystemUI, which writes its own list back over a settings put, and returns $true' {
            Set-FakeDevice -Settings @{ sysui_qs_tiles = 'internet,flashlight' } -KeepsTiles

            Set-QuickSettingsTiles -Serial emulator-5554 -Value 'internet,bt,flashlight' | Should -BeExactly $true

            $device = Get-FakeDevice
            $device.settings.sysui_qs_tiles | Should -BeExactly 'internet,bt,flashlight'
            $device.calls[0] | Should -BeExactly "cmd statusbar set-tiles 'internet,bt,flashlight' && echo && echo kestrel-end"
            $device.calls | Where-Object { $_ -like 'settings put *' } | Should -BeNullOrEmpty
        }

        It 'writes the list to the setting when SystemUI does not write it within 5 s' {
            Set-FakeDevice -Settings @{ sysui_qs_tiles = 'internet,flashlight' }

            Set-QuickSettingsTiles -Serial emulator-5554 -Value 'internet,bt,flashlight' | Should -BeExactly $true

            $device = Get-FakeDevice
            $device.settings.sysui_qs_tiles | Should -BeExactly 'internet,bt,flashlight'
            $device.calls[0] | Should -BeExactly "cmd statusbar set-tiles 'internet,bt,flashlight' && echo && echo kestrel-end"
            @($device.calls | Where-Object { $_ -like 'settings put *' }) | Should -BeExactly @("settings put secure sysui_qs_tiles 'internet,bt,flashlight' && echo && echo kestrel-end")
        }

        It 'returns $false and warns with the reason and a command that puts the list back when set-tiles fails' {
            # The device fails all 3 tries of set-tiles, and SystemUI then writes its own list back over the setting.
            Set-FakeDevice -Settings @{ sysui_qs_tiles = 'internet,flashlight' } -Failures 3 -KeepsTiles

            $output = Set-QuickSettingsTiles -Serial emulator-5554 -Value 'internet,bt,flashlight' 3>&1

            $output | Where-Object { $_ -isnot [System.Management.Automation.WarningRecord] } | Should -BeExactly $false
            $warnings = @($output | Where-Object { $_ -is [System.Management.Automation.WarningRecord] })
            $warnings | Should -HaveCount 1
            $message = $warnings[0].Message
            $message | Should -BeLike ("Could not put back the Quick Settings tiles of emulator-5554 (Could not run 'cmd statusbar set-tiles " +
                "'internet,bt,flashlight'' on emulator-5554: Bad arguments). Run: *")
            (Get-FakeDevice).settings.sysui_qs_tiles | Should -BeExactly 'internet,flashlight'

            Invoke-Expression $message.Substring($message.IndexOf('Run: ') + 'Run: '.Length)
            (Get-FakeDevice).settings.sysui_qs_tiles | Should -BeExactly 'internet,bt,flashlight'
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
            (Get-FakeDevice).calls | Should -BeExactly (@('settings list secure && echo && echo kestrel-end') * 3)
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

    It 'reads One UI''s line, which puts the list in brackets' {
        Test-ShortcutTarget -Dump @('     shortcut key:{[com.example.reader/com.example.reader.ReaderService, dev.kestrel/dev.kestrel.app.KestrelAccessibilityService]}') -Package dev.kestrel | Should -BeExactly $true
        Test-ShortcutTarget -Dump @('     shortcut key:{[dev.kestrel/dev.kestrel.app.KestrelAccessibilityService]}') -Package dev.kestrel | Should -BeExactly $true
        Test-ShortcutTarget -Dump @('     shortcut key:{[]}') -Package dev.kestrel | Should -BeExactly $false
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

Describe 'Get-IdleAllowlistKind' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
    }

    It 'returns the kinds of the package''s entries on the power-save allowlist, and none for a package that is not on it' {
        $dump = @(
            'system-excidle,com.android.providers.downloads,10045'
            'system,com.google.android.gms,10120'
            'user,dev.kestrel,10334'
            'user,dev.kestrel.testapp,10335'
        )

        @(Get-IdleAllowlistKind -Dump $dump -Package dev.kestrel) | Should -Be @('user')
        @(Get-IdleAllowlistKind -Dump $dump -Package com.google.android.gms) | Should -Be @('system')
        @(Get-IdleAllowlistKind -Dump @('system,com.google.android.gms,10120') -Package dev.kestrel).Count | Should -Be 0
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

Describe 'Get-UiNodeCentre' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
        # The notification shade as 'uiautomator dump' shows a notification with an action, shortened.
        $shade = @'
<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0"><node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.android.systemui" content-desc="" bounds="[0,0][1080,2400]"><node index="0" text="Kestrel could not start listening" resource-id="android:id/title" class="android.widget.TextView" package="com.android.systemui" content-desc="" bounds="[96,780][900,830]" /><node index="1" text="Start listening now" resource-id="" class="android.widget.TextView" package="com.android.systemui" content-desc="" bounds="[96,840][900,880]" /><node index="2" text="START LISTENING" resource-id="android:id/action0" class="android.widget.Button" package="com.android.systemui" content-desc="" bounds="[264,900][352,948]" /></node></hierarchy>
'@
    }

    It 'returns the centre of the node whose whole text is the text, in any letter case' {
        $centre = Get-UiNodeCentre -Dump $shade -Text 'Start listening'

        $centre.X | Should -Be 308
        $centre.Y | Should -Be 924
    }

    It 'returns $null when no node has exactly that text' {
        Get-UiNodeCentre -Dump $shade -Text 'Start' | Should -BeNullOrEmpty
    }

    It 'returns $null for an answer that is not XML: ''<_>''' -ForEach @('', 'ERROR: null root node returned by UiTestAutomationBridge.') {
        Get-UiNodeCentre -Dump $_ -Text 'Start listening' | Should -BeNullOrEmpty
    }
}

Describe 'Invoke-TimedProcess' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
        # The shell that runs the tests, Windows PowerShell 5.1 or PowerShell 7, stands in for adb.
        $shell = (Get-Process -Id $PID).Path
    }

    It 'returns the output and the exit code of a program that ends in time, with each argument as one word' {
        $result = Invoke-TimedProcess -FilePath $shell -ArgumentList '-NoProfile', '-Command', "[Console]::Out.Write('one two'); [Console]::Error.Write('a complaint'); exit 3" -TimeoutSeconds 60

        $result.Completed | Should -BeTrue
        $result.ExitCode | Should -Be 3
        # The error output is dropped, as adb's complaints while a device starts.
        $result.Output | Should -BeExactly 'one two'
    }

    It 'stops a program that has not ended in time, and says so' {
        $watch = [Diagnostics.Stopwatch]::StartNew()
        $result = Invoke-TimedProcess -FilePath $shell -ArgumentList '-NoProfile', '-Command', 'Start-Sleep -Seconds 60' -TimeoutSeconds 3

        $result.Completed | Should -BeFalse
        $result.ExitCode | Should -BeNullOrEmpty
        $watch.Elapsed.TotalSeconds | Should -BeLessThan 30
        Get-Process -Id $result.Id -ErrorAction SilentlyContinue | Should -BeNullOrEmpty
    }
}

Describe 'Get-EmulatorProcess' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
        $processes = @(
            [pscustomobject]@{ ProcessId = 11; CommandLine = 'qemu-system-x86_64.exe -avd zoran -no-boot-anim' }
            [pscustomobject]@{ ProcessId = 12; CommandLine = 'qemu-system-x86_64.exe -avd kestrel-api37-old -gpu swiftshader' }
            [pscustomobject]@{ ProcessId = 13; CommandLine = 'qemu-system-x86_64.exe -avd kestrel-api37 -no-boot-anim' }
            [pscustomobject]@{ ProcessId = 14; CommandLine = $null }
        )
        Mock Get-CimInstance { $processes }
    }

    It 'returns the qemu process of the AVD with exactly that name' {
        @(Get-EmulatorProcess -AvdName kestrel-api37).ProcessId | Should -Be @(13)
        @(Get-EmulatorProcess -AvdName zoran).ProcessId | Should -Be @(11)
    }

    It 'returns nothing when no qemu process runs the AVD' {
        Get-EmulatorProcess -AvdName kestrel | Should -BeNullOrEmpty
    }
}

Describe 'Get-LargestRowId' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
    }

    It 'returns the largest _id of the rows' {
        Get-LargestRowId -Rows @('Row: 0 _id=3', 'Row: 1 _id=12', 'Row: 2 _id=7') | Should -Be 12
    }

    It 'returns 0 when the query found no row' {
        Get-LargestRowId -Rows @('No result found.') | Should -Be 0
    }
}

Describe 'Get-PermissionStates' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
    }

    It 'reads the grant and the owner''s flags of each permission, and none for a permission that the dump does not show' {
        $dump = @(
            '    requested permissions:',
            '      android.permission.READ_SMS',
            '    User 0: ceDataInode=1 installed=true',
            '      runtime permissions:',
            '        android.permission.READ_SMS: granted=true, flags=[ USER_SET|USER_SENSITIVE_WHEN_GRANTED|USER_FIXED ]',
            '        android.permission.READ_CALL_LOG: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED|RESTRICTION_INSTALLER_EXEMPT]'
        )

        $states = Get-PermissionStates -Dump $dump -Permissions @('android.permission.READ_SMS', 'android.permission.READ_CALL_LOG', 'android.permission.READ_CALENDAR')

        @($states.Keys) | Should -Be @('android.permission.READ_SMS', 'android.permission.READ_CALL_LOG', 'android.permission.READ_CALENDAR')
        $states['android.permission.READ_SMS'].Granted | Should -BeTrue
        $states['android.permission.READ_SMS'].UserSet | Should -BeTrue
        $states['android.permission.READ_SMS'].UserFixed | Should -BeTrue
        foreach ($permission in 'android.permission.READ_CALL_LOG', 'android.permission.READ_CALENDAR') {
            $states[$permission].Granted | Should -BeFalse
            $states[$permission].UserSet | Should -BeFalse
            $states[$permission].UserFixed | Should -BeFalse
        }
    }
}

Describe 'Get-PermissionRestoreCommands' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
        function Get-ReadSmsState([bool]$Granted, [bool]$UserSet = $false, [bool]$UserFixed = $false) {
            [ordered]@{ 'android.permission.READ_SMS' = @{ Granted = $Granted; UserSet = $UserSet; UserFixed = $UserFixed } }
        }
    }

    It 'revokes a permission that was not granted before, and clears the owner''s flags that the change can set' {
        Get-PermissionRestoreCommands -Package dev.kestrel -Before (Get-ReadSmsState $false) -Now (Get-ReadSmsState $true) | Should -Be @(
            'pm revoke dev.kestrel android.permission.READ_SMS'
            'pm clear-permission-flags dev.kestrel android.permission.READ_SMS user-set user-fixed'
        )
    }

    It 'grants a permission that was granted before, and keeps the flags that were set before' {
        Get-PermissionRestoreCommands -Package dev.kestrel -Before (Get-ReadSmsState $true -UserSet $true) -Now (Get-ReadSmsState $false) | Should -Be @(
            'pm grant dev.kestrel android.permission.READ_SMS'
            'pm clear-permission-flags dev.kestrel android.permission.READ_SMS user-fixed'
        )
    }

    It 'only clears a flag that is new when the grant is the same' {
        Get-PermissionRestoreCommands -Package dev.kestrel -Before (Get-ReadSmsState $true) -Now (Get-ReadSmsState $true -UserSet $true) |
            Should -Be @('pm clear-permission-flags dev.kestrel android.permission.READ_SMS user-set')
    }

    It 'returns nothing when nothing changed' {
        @(Get-PermissionRestoreCommands -Package dev.kestrel -Before (Get-ReadSmsState $false -UserFixed $true) -Now (Get-ReadSmsState $false -UserFixed $true)).Count |
            Should -Be 0
    }
}

Describe 'Test-CallActive' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
    }

    It 'finds a call in any SIM slot, and none when every slot is idle' {
        Test-CallActive -Registry @('last known state:', '  mCallState=0', '  mCallState=2') | Should -BeTrue
        Test-CallActive -Registry @('  mCallState=1') | Should -BeTrue
        Test-CallActive -Registry @('  mCallState=0', 'mCallState=0') | Should -BeFalse
        # A device without telephony prints no such line.
        Test-CallActive -Registry @('Phone Id=0') | Should -BeFalse
        Test-CallActive -Registry @() | Should -BeFalse
    }
}

Describe 'Stop-DeviceCall' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
        Mock Start-Sleep {}
    }

    It 'sends no key when no call shows, because the key would turn the screen off' {
        Mock Invoke-SettingsCommand { @('  mCallState=0') }

        Stop-DeviceCall -Serial emulator-5554 | Should -BeExactly $true
        Should -Invoke Invoke-SettingsCommand -Times 0 -Exactly -ParameterFilter { $Command -like 'input keyevent*' }
    }

    It 'ends a call with the end-call key, and waits until the device shows none' {
        $device = @{ Call = '2' }
        Mock Invoke-SettingsCommand { @("  mCallState=$($device.Call)") } -ParameterFilter { $Command -eq 'dumpsys telephony.registry' }
        Mock Invoke-SettingsCommand { $device.Call = '0' } -ParameterFilter { $Command -eq 'input keyevent KEYCODE_ENDCALL' }

        Stop-DeviceCall -Serial emulator-5554 | Should -BeExactly $true
        Should -Invoke Invoke-SettingsCommand -Times 1 -Exactly -ParameterFilter { $Command -eq 'input keyevent KEYCODE_ENDCALL' }
    }

    It 'warns and returns false when the call stays' {
        Mock Invoke-SettingsCommand { @('  mCallState=2') } -ParameterFilter { $Command -eq 'dumpsys telephony.registry' }
        Mock Invoke-SettingsCommand {} -ParameterFilter { $Command -eq 'input keyevent KEYCODE_ENDCALL' }

        $output = Stop-DeviceCall -Serial emulator-5554 3>&1
        $output | Where-Object { $_ -isnot [System.Management.Automation.WarningRecord] } | Should -BeExactly $false
        @($output | Where-Object { $_ -is [System.Management.Automation.WarningRecord] }).Count | Should -Be 1
        Should -Invoke Invoke-SettingsCommand -Times 1 -Exactly -ParameterFilter { $Command -eq 'input keyevent KEYCODE_ENDCALL' }
    }

    It 'warns and returns false when it cannot read the call state' {
        Mock Invoke-SettingsCommand { throw 'Could not run the command.' }

        $output = Stop-DeviceCall -Serial emulator-5554 3>&1
        $output | Where-Object { $_ -isnot [System.Management.Automation.WarningRecord] } | Should -BeExactly $false
        @($output | Where-Object { $_ -is [System.Management.Automation.WarningRecord] }).Count | Should -Be 1
    }
}

Describe 'Get-DebugSigningEnvironment' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"

        # Sets the three variables and USERPROFILE to folders below TestDrive, or removes those that are empty, for the
        # script block, and puts them back afterwards.
        function Invoke-WithAndroidVariables([string]$UserHome, [string]$SdkHome, [string]$PrefsRoot, [scriptblock]$Script) {
            $names = 'ANDROID_USER_HOME', 'ANDROID_SDK_HOME', 'ANDROID_PREFS_ROOT', 'USERPROFILE'
            $before = @{}
            foreach ($name in $names) { $before[$name] = [Environment]::GetEnvironmentVariable($name) }
            try {
                $values = @{ ANDROID_USER_HOME = $UserHome; ANDROID_SDK_HOME = $SdkHome; ANDROID_PREFS_ROOT = $PrefsRoot; USERPROFILE = 'profile' }
                foreach ($name in $names) {
                    $value = if ($values[$name]) { Join-Path $TestDrive $values[$name] } else { $null }
                    [Environment]::SetEnvironmentVariable($name, $value)
                }
                & $Script
            } finally {
                foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $before[$name]) }
            }
        }
    }

    It 'names the debug keystore of the Android user folder with its fixed alias and passwords' {
        $folder = Join-Path $TestDrive 'android'
        New-Item -ItemType Directory -Path $folder | Out-Null
        Set-Content -LiteralPath (Join-Path $folder 'debug.keystore') -Value 'key'

        $signing = Get-DebugSigningEnvironment -AndroidUserHome $folder

        @($signing.Keys) | Should -BeExactly @('KESTREL_KEYSTORE', 'KESTREL_KEYSTORE_PASSWORD', 'KESTREL_KEY_ALIAS', 'KESTREL_KEY_PASSWORD')
        $signing.KESTREL_KEYSTORE | Should -BeExactly (Join-Path $folder 'debug.keystore')
        $signing.KESTREL_KEY_ALIAS | Should -BeExactly 'androiddebugkey'
        $signing.KESTREL_KEYSTORE_PASSWORD | Should -BeExactly 'android'
    }

    It 'throws when the folder has no debug keystore' {
        { Get-DebugSigningEnvironment -AndroidUserHome (Join-Path $TestDrive 'none') } | Should -Throw '*No debug keystore*'
    }

    # Each case has folders of its own, because TestDrive keeps the folders of the cases before it.
    It 'finds the Android user folder as the Android Gradle plugin does when <Case>' -TestCases @(
        @{ Case = 'ANDROID_USER_HOME is set'; UserHome = 'a\user'; SdkHome = ''; PrefsRoot = ''; Existing = @(); Expected = 'a\user' }
        @{ Case = 'ANDROID_USER_HOME and ANDROID_SDK_HOME name the same folder'; UserHome = 'b\.android'; SdkHome = 'b'; PrefsRoot = ''; Existing = @('b'); Expected = 'b\.android' }
        @{ Case = 'only ANDROID_SDK_HOME is set'; UserHome = ''; SdkHome = 'c'; PrefsRoot = ''; Existing = @('c'); Expected = 'c\.android' }
        @{ Case = 'only ANDROID_PREFS_ROOT is set'; UserHome = ''; SdkHome = ''; PrefsRoot = 'd'; Existing = @('d'); Expected = 'd\.android' }
        @{ Case = 'the folder of ANDROID_SDK_HOME does not exist'; UserHome = ''; SdkHome = 'e'; PrefsRoot = 'f'; Existing = @('f'); Expected = 'f\.android' }
        @{ Case = 'the folder of ANDROID_SDK_HOME does not exist and nothing else is set'; UserHome = ''; SdkHome = 'g'; PrefsRoot = ''; Existing = @(); Expected = 'profile\.android' }
        @{ Case = 'none is set'; UserHome = ''; SdkHome = ''; PrefsRoot = ''; Existing = @(); Expected = 'profile\.android' }
    ) {
        param($UserHome, $SdkHome, $PrefsRoot, $Existing, $Expected)
        foreach ($name in $Existing) { New-Item -ItemType Directory -Path (Join-Path $TestDrive $name) -Force | Out-Null }
        $folder = Join-Path $TestDrive $Expected
        New-Item -ItemType Directory -Path $folder -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $folder 'debug.keystore') -Value 'key'

        Invoke-WithAndroidVariables $UserHome $SdkHome $PrefsRoot {
            (Get-DebugSigningEnvironment).KESTREL_KEYSTORE | Should -BeExactly (Join-Path $folder 'debug.keystore')
        }
    }

    It 'throws, as the Android Gradle plugin does, when the variables name different folders' {
        New-Item -ItemType Directory -Path (Join-Path $TestDrive 'h\user'), (Join-Path $TestDrive 'i') -Force | Out-Null

        Invoke-WithAndroidVariables 'h\user' 'i' '' {
            { Get-AndroidUserHome } | Should -Throw '*name different Android user folders*'
        }
    }
}