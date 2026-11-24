# Tests of the helpers in scripts\lib\Kestrel.ps1 with which scripts\connected-test.ps1 keeps Kestrel and its data on the
# test device. The package helpers run with fake-adb.cmd instead of a device. scripts\test-scripts.ps1 runs these tests
# in Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

Describe 'Get-ConnectedTestArguments' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
        $keep = @(
            '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
            '-Pandroid.experimental.testOptions.uninstallIncompatibleApks=false'
            '-Pandroid.testInstrumentationRunnerArguments.clearPackageData=false'
        )
    }

    It 'always passes the properties that keep Kestrel installed with its data, after the caller''s arguments' {
        Get-ConnectedTestArguments -Arguments @(':app:connectedDebugAndroidTest') | Should -BeExactly (@(':app:connectedDebugAndroidTest') + $keep)

        $arguments = @(':core:privacy:connectedDebugAndroidTest', ':app:connectedDebugAndroidTest', 'connectedCheck',
            '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.InstallTest', '--no-configuration-cache')
        Get-ConnectedTestArguments -Arguments $arguments | Should -BeExactly ($arguments + $keep)
    }

    It 'passes a property once when the caller sets it as the script does' {
        Get-ConnectedTestArguments -Arguments @(':app:connectedDebugAndroidTest', '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true') |
            Should -BeExactly (@(':app:connectedDebugAndroidTest') + $keep)
    }

    It 'refuses an argument that sets one of these properties otherwise: <_>' -ForEach @(
        '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=false'
        '--project-prop=android.injected.androidTest.leaveApksInstalledAfterRun=false'
        '-Dorg.gradle.project.android.injected.androidTest.leaveApksInstalledAfterRun=false'
        'android.injected.androidTest.leaveApksInstalledAfterRun=false'
        '-Pandroid.experimental.testOptions.uninstallIncompatibleApks=true'
        '-Pandroid.testInstrumentationRunnerArguments.clearPackageData=true'
    ) {
        $thrown = { Get-ConnectedTestArguments -Arguments @(':app:connectedDebugAndroidTest', $_) } | Should -Throw -PassThru
        $thrown.Exception.Message | Should -BeLike "Refusing the Gradle argument '$_'. connected-test.ps1 itself sets *, so that the run keeps Kestrel and its data."
    }

    It 'refuses a task that is not a connected test, such as one that uninstalls Kestrel: <_>' -ForEach @(
        'uninstallAll', ':app:uninstallDebug', 'uA', ':app:installDebug', 'cDAT', 'check'
    ) {
        { Get-ConnectedTestArguments -Arguments @(':app:connectedDebugAndroidTest', $_) } |
            Should -Throw -ExpectedMessage "Refusing the Gradle argument '$_'. connected-test.ps1 runs only connected test tasks, such as :app:connectedDebugAndroidTest."
    }

    It 'refuses Gradle''s --serial option' {
        { Get-ConnectedTestArguments -Arguments @(':app:connectedDebugAndroidTest', '--serial=emulator-5556') } |
            Should -Throw -ExpectedMessage "Refusing the Gradle argument '--serial=emulator-5556'. Choose the device with -Serial."
    }
}

Describe 'Get-KestrelDataLoss' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
        function New-PackageState($InstallTimes) { [pscustomobject]@{ KestrelInstallTimes = $InstallTimes; TestPackages = @() } }
        $installed = New-PackageState @('2026-11-20 10:11:12')
        $lost = 'so its data there is gone: its AI profiles, permissions, settings and the agent''s memory.'
    }

    It 'finds no loss when Kestrel keeps its first-install time' {
        Get-KestrelDataLoss -Before $installed -After (New-PackageState @('2026-11-20 10:11:12')) -Serial emulator-5554 | Should -BeNullOrEmpty
    }

    It 'names the loss when Kestrel was installed again, with a new first-install time' {
        Get-KestrelDataLoss -Before $installed -After (New-PackageState @('2026-11-24 16:20:00')) -Serial emulator-5554 | Should -BeExactly (
            'Kestrel (dev.kestrel) was installed again on emulator-5554 during the tests (first installed 2026-11-20 10:11:12 ' +
            "before them, 2026-11-24 16:20:00 now), $lost")
    }

    It 'names the loss when Kestrel was uninstalled' {
        Get-KestrelDataLoss -Before $installed -After (New-PackageState $null) -Serial emulator-5554 |
            Should -BeExactly "Kestrel (dev.kestrel) was uninstalled from emulator-5554 during the tests, $lost"
    }

    It 'finds no loss when Kestrel was not installed before the tests' {
        Get-KestrelDataLoss -Before (New-PackageState $null) -After $installed -Serial emulator-5554 | Should -BeNullOrEmpty
    }
}

Describe 'Kestrel''s packages on the device' {
    BeforeAll {
        # The scripts run with 'Stop', so the tests run with it too.
        $ErrorActionPreference = 'Stop'
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
        $fakeAdb = Join-Path $PSScriptRoot 'fake-adb.cmd'
        Mock Get-Adb { $fakeAdb }
        Mock Start-Sleep {}

        # Gives the fake kestrel-api37 these packages, each with its first-install time, and makes its next commands fail
        # as often as -Failures says.
        function Set-FakeDevice([System.Collections.IDictionary]$Packages, [int]$Failures = 0) {
            $state = [ordered]@{
                avd = 'kestrel-api37'; model = 'sdk_gphone64_x86_64'; settings = @{}; packages = $Packages; keepsTiles = $false
                failures = $Failures; calls = @(); refused = @()
            }
            [IO.File]::WriteAllText($env:KESTREL_FAKE_ADB, (ConvertTo-Json -Depth 3 -InputObject $state))
        }

        function Get-FakePackage {
            @(([IO.File]::ReadAllText($env:KESTREL_FAKE_ADB) | ConvertFrom-Json).packages.PSObject.Properties.Name)
        }
    }

    BeforeEach {
        $env:KESTREL_FAKE_ADB = Join-Path $TestDrive 'device.json'
        Set-FakeDevice -Packages @{}
        $KestrelTestDevices.Clear()
        Assert-TestDevice -Serial emulator-5554
    }

    AfterAll {
        Remove-Item Env:\KESTREL_FAKE_ADB -ErrorAction SilentlyContinue
    }

    It 'reads Kestrel''s first-install time and the test packages, of which Kestrel is none' {
        Set-FakeDevice -Packages ([ordered]@{
                'dev.kestrel' = '2026-11-20 10:11:12'; 'dev.kestrel.testapp' = '2026-11-21 09:00:00'
                'dev.kestrel.privacy.test' = '2026-11-21 09:01:00'; 'com.android.vending' = '2026-01-01 00:00:00'
            })

        $state = Get-KestrelPackageState -Serial emulator-5554

        $state.KestrelInstallTimes | Should -BeExactly @('2026-11-20 10:11:12')
        $state.TestPackages | Should -BeExactly @('dev.kestrel.privacy.test', 'dev.kestrel.testapp')
    }

    It 'reads no first-install time when Kestrel is not installed' {
        Set-FakeDevice -Packages @{ 'dev.kestrel.testapp' = '2026-11-21 09:00:00' }

        $state = Get-KestrelPackageState -Serial emulator-5554

        $state.KestrelInstallTimes | Should -BeNullOrEmpty
        $state.TestPackages | Should -BeExactly @('dev.kestrel.testapp')
    }

    It 'uninstalls the test APKs that the tests installed, and keeps Kestrel and the test packages that were there before' {
        Set-FakeDevice -Packages @{ 'dev.kestrel' = '2026-11-20 10:11:12'; 'dev.kestrel.testapp' = '2026-11-21 09:00:00' }
        $before = Get-KestrelPackageState -Serial emulator-5554
        # Gradle installs the test APKs of :app and :core:voice.
        Set-FakeDevice -Packages @{
            'dev.kestrel' = '2026-11-20 10:11:12'; 'dev.kestrel.testapp' = '2026-11-21 09:00:00'
            'dev.kestrel.test' = '2026-11-24 16:00:00'; 'dev.kestrel.voice.test' = '2026-11-24 16:00:01'
        }
        $after = Get-KestrelPackageState -Serial emulator-5554
        $installed = @($after.TestPackages | Where-Object { $before.TestPackages -notcontains $_ })

        Remove-TestPackages -Serial emulator-5554 -Packages $installed | Should -BeTrue

        Get-FakePackage | Sort-Object | Should -BeExactly @('dev.kestrel', 'dev.kestrel.testapp')
        Get-KestrelDataLoss -Before $before -After $after -Serial emulator-5554 | Should -BeNullOrEmpty
    }

    It 'refuses to uninstall Kestrel itself' {
        Set-FakeDevice -Packages @{ 'dev.kestrel' = '2026-11-20 10:11:12' }

        { Remove-TestPackages -Serial emulator-5554 -Packages @('dev.kestrel') } | Should -Throw -ExpectedMessage 'dev.kestrel is not a test package of Kestrel.'

        Get-FakePackage | Should -BeExactly @('dev.kestrel')
    }

    It 'returns $false and warns with the command that uninstalls a test APK by hand when the device fails' {
        Set-FakeDevice -Packages @{ 'dev.kestrel.test' = '2026-11-24 16:00:00' } -Failures 10
        Mock Write-Warning {}

        Remove-TestPackages -Serial emulator-5554 -Packages @('dev.kestrel.test') | Should -BeFalse

        Should -Invoke Write-Warning -Times 1 -Exactly -ParameterFilter {
            $Message -eq ("Could not uninstall dev.kestrel.test from emulator-5554 (Could not run 'pm uninstall dev.kestrel.test' on " +
                "emulator-5554: Bad arguments). Run: & '$fakeAdb' -s emulator-5554 uninstall dev.kestrel.test")
        }
        Get-FakePackage | Should -BeExactly @('dev.kestrel.test')
    }
}
