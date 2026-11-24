# Tests of scripts\connected-test.ps1 as a whole. The script runs in the test's PowerShell from a copy of the scripts
# folder in the test drive, next to a fake gradlew.bat, which writes down its arguments and ANDROID_SERIAL and changes
# the fake device as a run of the tests could. adb is fake-adb.cmd, and Kestrel's profiles, which the script reads through
# scripts\hook.ps1, come from a mock, so the tests need no build and no device. scripts\test-scripts.ps1 runs these tests
# in Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

Describe 'connected-test.ps1' {
    BeforeAll {
        $ErrorActionPreference = 'Stop'
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
        . "$PSScriptRoot\..\lib\Session.ps1"
        . "$PSScriptRoot\..\lib\DeviceGuard.ps1"
        $root = Join-Path $TestDrive 'repository'
        New-Item -ItemType Directory -Path (Join-Path $root 'scripts\lib') | Out-Null
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot '..\connected-test.ps1') -Destination (Join-Path $root 'scripts')
        foreach ($library in 'Kestrel.ps1', 'Session.ps1', 'DeviceGuard.ps1') {
            Copy-Item -LiteralPath (Join-Path $PSScriptRoot "..\lib\$library") -Destination (Join-Path $root 'scripts\lib')
        }
        $connectedTest = Join-Path $root 'scripts\connected-test.ps1'
        $arguments = Join-Path $root 'gradlew-arguments.txt'
        $gradleSerial = Join-Path $root 'gradlew-serial.txt'
        Set-Content -LiteralPath (Join-Path $root 'gradlew.bat') -Encoding Ascii -Value @(
            '@echo off'
            'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0fake-gradlew.ps1" %*'
            'exit /b %ERRORLEVEL%'
        )
        # The fake Gradle: KESTREL_FAKE_GRADLE says what the run does to the fake device.
        Set-Content -LiteralPath (Join-Path $root 'fake-gradlew.ps1') -Value @(
            '[IO.File]::WriteAllText((Join-Path $PSScriptRoot ''gradlew-arguments.txt''), ($args -join [char]10))'
            '[IO.File]::WriteAllText((Join-Path $PSScriptRoot ''gradlew-serial.txt''), "$env:ANDROID_SERIAL")'
            '$state = [IO.File]::ReadAllText($env:KESTREL_FAKE_ADB) | ConvertFrom-Json'
            'switch ($env:KESTREL_FAKE_GRADLE) {'
            '    ''uninstall'' { $state.packages.PSObject.Properties.Remove(''dev.kestrel'') }'
            '    ''reinstall'' { $state.packages.''dev.kestrel'' = ''2026-11-24 17:00:00'' }'
            '    ''test-apk'' { $state.packages | Add-Member -NotePropertyName ''dev.kestrel.test'' -NotePropertyValue ''2026-11-24 17:00:00'' }'
            '    ''account'' { $state.accounts = 0 }'
            '}'
            '[IO.File]::WriteAllText($env:KESTREL_FAKE_ADB, (ConvertTo-Json -Depth 3 -InputObject $state))'
            'exit 0'
        )
        $fakeAdb = Join-Path $PSScriptRoot 'fake-adb.cmd'
        $keep = @(
            '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
            '-Pandroid.experimental.testOptions.uninstallIncompatibleApks=false'
            '-Pandroid.testInstrumentationRunnerArguments.clearPackageData=false'
        )
        $saved = @{}
        foreach ($name in 'JAVA_HOME', 'ANDROID_HOME', 'ANDROID_AVD_HOME', 'ANDROID_SERIAL', 'KESTREL_FAKE_ADB', 'KESTREL_FAKE_GRADLE') {
            $saved[$name] = [Environment]::GetEnvironmentVariable($name)
        }
        # Use-KestrelToolchain needs a JDK and an Android SDK folder, which the fakes do not use.
        $env:JAVA_HOME = $TestDrive
        $env:ANDROID_HOME = $TestDrive
        $env:KESTREL_FAKE_ADB = Join-Path $TestDrive 'device.json'
        Mock Get-Adb { $fakeAdb }
        Mock Start-Sleep {}

        # Gives the fake device Kestrel, the fixture app and one Google account; it is kestrel-api37, or the A71 with -A71.
        function Set-FakeDevice([switch]$A71) {
            $state = [ordered]@{
                avd = $(if ($A71) { '' } else { 'kestrel-api37' }); model = $(if ($A71) { 'SM-A715F' } else { 'sdk_gphone64_x86_64' })
                settings = @{}; packages = [ordered]@{ 'dev.kestrel' = '2026-11-20 10:11:12'; 'dev.kestrel.testapp' = '2026-11-21 09:00:00' }
                accounts = 1; keepsTiles = $false; failures = 0; calls = @(); refused = @()
            }
            [IO.File]::WriteAllText($env:KESTREL_FAKE_ADB, (ConvertTo-Json -Depth 3 -InputObject $state))
        }

        # Runs the copy of the script with the arguments, after the fake Gradle's -Change, and returns its exit code, its
        # errors and its warnings.
        function Invoke-ConnectedTest([string[]]$Arguments, [string]$Change = '', [string]$Serial = 'emulator-5554') {
            $env:KESTREL_FAKE_GRADLE = $Change
            $output = @(& $connectedTest -Serial $Serial @Arguments 2>&1 3>&1)
            [pscustomobject]@{
                Code = $LASTEXITCODE
                Errors = @($output | Where-Object { $_ -is [System.Management.Automation.ErrorRecord] } | ForEach-Object { "$_" })
                Warnings = @($output | Where-Object { $_ -is [System.Management.Automation.WarningRecord] } | ForEach-Object { "$_" })
            }
        }
    }

    BeforeEach {
        Set-FakeDevice
        $KestrelTestDevices.Clear()
        Remove-Item -LiteralPath $arguments, $gradleSerial -ErrorAction SilentlyContinue
        $env:ANDROID_SERIAL = 'caller-serial'
        Mock Get-KestrelProfiles { [pscustomobject]@{ Profile = 'copilot'; Copilot = 1; Id = 'profile-1'; Credential = 'present' } }
    }

    AfterAll {
        foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name]) }
    }

    It 'passes the caller''s arguments and then the properties that keep Kestrel to Gradle, for the device only, and puts ANDROID_SERIAL back' {
        $caller = @(':app:connectedDebugAndroidTest', '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.InstallTest', '--no-configuration-cache')

        $run = Invoke-ConnectedTest -Arguments $caller

        $run.Code | Should -Be 0
        $run.Errors | Should -BeNullOrEmpty
        (Get-Content -LiteralPath $arguments) | Should -BeExactly (@('--console=plain') + $caller + $keep)
        (Get-Content -LiteralPath $gradleSerial) | Should -BeExactly 'emulator-5554'
        $env:ANDROID_SERIAL | Should -BeExactly 'caller-serial'
        Should -Invoke Get-KestrelProfiles -Times 2 -Exactly
    }

    It 'refuses an argument before any command reaches the device or Gradle' {
        { & $connectedTest -Serial emulator-5554 ':app:connectedDebugAndroidTest' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=false' } |
            Should -Throw -ExpectedMessage "Refusing the Gradle argument '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=false'. *"

        $arguments | Should -Not -Exist
        ([IO.File]::ReadAllText($env:KESTREL_FAKE_ADB) | ConvertFrom-Json).calls | Should -BeNullOrEmpty
    }

    It 'uninstalls a test APK that the run installed, and keeps the fixture app that was there before' {
        $run = Invoke-ConnectedTest -Arguments @(':app:connectedDebugAndroidTest') -Change 'test-apk'

        $run.Code | Should -Be 0
        @(([IO.File]::ReadAllText($env:KESTREL_FAKE_ADB) | ConvertFrom-Json).packages.PSObject.Properties.Name) | Sort-Object |
            Should -BeExactly @('dev.kestrel', 'dev.kestrel.testapp')
    }

    It 'ends with exit code 1 and names the loss when Kestrel was <Name>, although Gradle passed' -ForEach @(
        @{ Name = 'uninstalled'; Change = 'uninstall'; Message = 'Kestrel (dev.kestrel) was uninstalled from emulator-5554 during the tests, which deleted its data there*' }
        @{ Name = 'installed again'; Change = 'reinstall'; Message = 'Kestrel (dev.kestrel) was installed again on emulator-5554 during the tests (first installed user 0: 2026-11-20 10:11:12 before them, user 0: 2026-11-24 17:00:00 now)*' }
    ) {
        $run = Invoke-ConnectedTest -Arguments @(':app:connectedDebugAndroidTest') -Change $Change

        $run.Code | Should -Be 1
        $run.Errors | Should -HaveCount 1
        $run.Errors[0] | Should -BeLike $Message
    }

    It 'ends with exit code 1 and names the change when a Google account of kestrel-api37 went' {
        $run = Invoke-ConnectedTest -Arguments @(':app:connectedDebugAndroidTest') -Change 'account'

        $run.Code | Should -Be 1
        $run.Errors | Should -BeExactly @('The number of Google accounts on emulator-5554 changed during the tests: 1 before, 0 now.')
    }

    It 'ends with exit code 1 and names the change when Kestrel''s Copilot profile on kestrel-api37 went' {
        $script:reads = 0
        Mock Get-KestrelProfiles {
            $script:reads++
            if ($script:reads -eq 1) {
                [pscustomobject]@{ Profile = 'copilot'; Copilot = 1; Id = 'profile-1'; Credential = 'present' }
            } else {
                [pscustomobject]@{ Profile = 'none'; Copilot = 0; Id = 'none'; Credential = 'none' }
            }
        }

        $run = Invoke-ConnectedTest -Arguments @(':app:connectedDebugAndroidTest')

        $run.Code | Should -Be 1
        $run.Errors | Should -BeExactly @(
            'Kestrel''s default profile on emulator-5554 changed during the tests: copilot before, none now.'
            'The number of Kestrel''s Copilot profiles on emulator-5554 changed during the tests: 1 before, 0 now.'
            'Kestrel''s default profile on emulator-5554 was replaced during the tests: profile-1 before, none now.'
            'The credential of Kestrel''s default profile on emulator-5554 changed during the tests: present before, none now.'
        )
    }

    It 'reads Kestrel''s profiles after the tests before it stops Kestrel, so that Kestrel ends stopped' {
        # Each read writes down whether Kestrel had been stopped already; the hook would start Kestrel again after the stop.
        $reads = Join-Path $TestDrive 'reads.txt'
        Remove-Item -LiteralPath $reads -ErrorAction SilentlyContinue
        Mock Get-KestrelProfiles {
            $calls = @(([IO.File]::ReadAllText($env:KESTREL_FAKE_ADB) | ConvertFrom-Json).calls)
            Add-Content -LiteralPath (Join-Path (Split-Path $env:KESTREL_FAKE_ADB) 'reads.txt') -Value ($calls -contains 'am force-stop dev.kestrel')
            [pscustomobject]@{ Profile = 'copilot'; Copilot = 1; Id = 'profile-1'; Credential = 'present' }
        }

        $run = Invoke-ConnectedTest -Arguments @(':app:connectedDebugAndroidTest')

        $run.Code | Should -Be 0
        Get-Content -LiteralPath $reads | Should -Be @('False', 'False')
        @(([IO.File]::ReadAllText($env:KESTREL_FAKE_ADB) | ConvertFrom-Json).calls) | Should -Contain 'am force-stop dev.kestrel'
    }

    It 'says so when Kestrel''s profiles cannot be read before the tests, and then compares only the accounts' {
        Mock Get-KestrelProfiles { throw 'scripts\hook.ps1 provider-standin ended with exit code 1' }

        $run = Invoke-ConnectedTest -Arguments @(':app:connectedDebugAndroidTest')

        $run.Code | Should -Be 0
        $run.Warnings | Should -Contain ("Could not read Kestrel's profiles on emulator-5554 before the tests (scripts\hook.ps1 provider-standin ended " +
            'with exit code 1), so the script compares only the Google accounts there.')
    }

    It 'stops before Gradle runs when Kestrel on kestrel-api37 answers provider-standin in another form, as an older build does' {
        Mock Get-KestrelProfiles { ConvertFrom-StandInAnswer 'standin=off profile=copilot copilot=1' }

        { & $connectedTest -Serial emulator-5554 ':app:connectedDebugAndroidTest' } |
            Should -Throw -ExpectedMessage 'Not an answer of provider-standin*install.ps1*'

        $arguments | Should -Not -Exist
        $env:ANDROID_SERIAL | Should -BeExactly 'caller-serial'
    }

    It 'reads no owner''s data on the A71' {
        Set-FakeDevice -A71

        $run = Invoke-ConnectedTest -Arguments @(':app:connectedDebugAndroidTest') -Serial R58N66RVD7Z -Change 'account'

        $run.Code | Should -Be 0
        Should -Invoke Get-KestrelProfiles -Times 0 -Exactly
    }
}
