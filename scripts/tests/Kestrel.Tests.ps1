# Tests of the settings helpers in scripts\lib\Kestrel.ps1, with fake-adb.cmd instead of a device. The fake adb runs in a
# process of its own, as adb does, so its error lines reach the helpers as a native program's error output, which
# Windows PowerShell 5.1 and PowerShell 7 handle differently. scripts\test-scripts.ps1 runs these tests in both shells.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSAvoidUsingInvokeExpression', '',
    Justification = 'A test runs the command that a warning asks the owner to paste.')]
param()

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
