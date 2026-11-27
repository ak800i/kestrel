# Tests of scripts\lib\DeviceGuard.ps1, which keeps Kestrel's data and the owner's data on the test devices. The helpers
# that read or change a device run with fake-adb.cmd instead of a device. scripts\test-scripts.ps1 runs these tests in
# Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

BeforeAll {
    # The scripts run with 'Stop', so the tests run with it too.
    $ErrorActionPreference = 'Stop'
    . "$PSScriptRoot\..\lib\Kestrel.ps1"
    . "$PSScriptRoot\..\lib\Session.ps1"
    . "$PSScriptRoot\..\lib\DeviceGuard.ps1"
    $fakeAdb = Join-Path $PSScriptRoot 'fake-adb.cmd'

    # Gives the fake device these packages, each with its first-install time, and this number of Google accounts, and
    # makes its next commands fail as often as -Failures says. It is kestrel-api37, or the Galaxy A71 with -A71.
    function Set-FakeDevice([System.Collections.IDictionary]$Packages = @{}, [int]$Accounts = 1, [int]$Failures = 0, [switch]$A71) {
        $state = [ordered]@{
            avd = $(if ($A71) { '' } else { 'kestrel-api37' }); model = $(if ($A71) { 'SM-A715F' } else { 'sdk_gphone64_x86_64' })
            settings = @{}; packages = $Packages; accounts = $Accounts; keepsTiles = $false; failures = $Failures; calls = @(); refused = @()
        }
        [IO.File]::WriteAllText($env:KESTREL_FAKE_ADB, (ConvertTo-Json -Depth 3 -InputObject $state))
    }

    function Get-FakeDevice { [IO.File]::ReadAllText($env:KESTREL_FAKE_ADB) | ConvertFrom-Json }

    function Get-FakePackage { @((Get-FakeDevice).packages.PSObject.Properties.Name) }
}

Describe 'Get-GradleTaskKind' {
    It 'knows a connected test by its full name: <_>' -ForEach @(
        'connectedCheck', 'connectedAndroidTest', ':app:connectedDebugAndroidTest', ':core:privacy:connectedDebugAndroidTest'
    ) {
        Get-GradleTaskKind $_ | Should -BeExactly 'ConnectedTest'
    }

    It 'counts every other name that can reach a device task as a device task, also an abbreviated one: <_>' -ForEach @(
        'installDebug', ':app:installDebug', ':testapp:installDebug', 'installDebugAndroidTest', 'uninstallAll',
        ':app:uninstallDebug', 'deviceCheck', 'deviceAndroidTest', 'connectedcheck', 'UNINSTALLALL', 'iD', ':app:iD', 'inst',
        'i', 'uA', 'unA', 'cDAT', 'CDAT', 'cC', 'dC', 'de'
    ) {
        Get-GradleTaskKind $_ | Should -BeExactly 'Device'
    }

    It 'also counts as a device task a name that only starts like a device word, which matches no task of the plugin: <_>' -ForEach @(
        'c-d-a-t', 'device-x'
    ) {
        # Gradle's kebab-case matching needs a '-' in the task's name, which no task of the Android Gradle plugin has.
        Get-GradleTaskKind $_ | Should -BeExactly 'Device'
    }

    It 'counts a task that cannot reach a device as a build: <_>' -ForEach @(
        'check', 'assembleDebug', 'kestrelAudit', ':app:assembleDebugAndroidTest', 'testDebugUnitTest', 'lint', 'clean',
        'compileDebugKotlin', 'dependencies', 'init', 'help', 'wrapper', ':build-logic:convention:check', ':tools:eval:installDist',
        ':tools:catalog-sync:syncCatalog'
    ) {
        Get-GradleTaskKind $_ | Should -BeExactly 'Build'
    }
}

Describe 'Get-GradleArgumentKinds' {
    It 'reads <Name>' -ForEach @(
        @{ Name = 'a value of --tests'; Arguments = @(':app:testDebugUnitTest', '--tests', 'dev.kestrel.app.X'); Kinds = @('Build', 'Option', 'Value') }
        @{ Name = 'a value attached to --tests'; Arguments = @(':app:testDebugUnitTest', '--tests=dev.kestrel.app.X'); Kinds = @('Build', 'Option') }
        @{ Name = 'the values of the wrapper task''s options, as the foundation plan updates the wrapper'
            Arguments = @('wrapper', '--gradle-version', '9.9.1', '--distribution-type', 'bin', '--gradle-distribution-sha256-sum', 'd8a18a7a841dd5df825d35a685fad6f2d7f12ac0c280e57b67c27b4a520ba228')
            Kinds = @('Build', 'Option', 'Value', 'Option', 'Value', 'Option', 'Value')
        }
        @{ Name = 'the values of the other options of wrapper'
            Arguments = @('wrapper', '--gradle-distribution-url', 'https://services.gradle.org/distributions/gradle-9.9.1-bin.zip', '--network-timeout', '10000', '--retries', '0', '--retry-back-off-ms', '500', '--validate-url')
            Kinds = @('Build', 'Option', 'Value', 'Option', 'Value', 'Option', 'Value', 'Option', 'Value', 'Option')
        }
        @{ Name = 'a value of help''s --task'; Arguments = @('help', '--task', 'connectedCheck'); Kinds = @('Build', 'Option', 'Value') }
        @{ Name = 'a value of tasks'' --group'; Arguments = @('tasks', '--group', 'device'); Kinds = @('Build', 'Option', 'Value') }
        @{ Name = 'a device task after a task option of another task'; Arguments = @('assembleDebug', '--tests', 'installDebug', 'check', '--gradle-version', 'iD'); Kinds = @('Build', 'Option', 'Device', 'Build', 'Option', 'Device') }
        @{ Name = 'a device task after a task option before any task'; Arguments = @('--tests', 'installDebug'); Kinds = @('Option', 'Device') }
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

Describe 'Get-ConnectedTestArguments' {
    BeforeAll {
        $keep = @(
            '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
            '-Pandroid.experimental.testOptions.uninstallIncompatibleApks=false'
            '-Pandroid.testInstrumentationRunnerArguments.clearPackageData=false'
        )
    }

    It 'always passes the properties that keep Kestrel installed with its data, after the caller''s arguments' {
        Get-ConnectedTestArguments -Arguments @(':app:connectedDebugAndroidTest') | Should -BeExactly (@(':app:connectedDebugAndroidTest') + $keep)

        $arguments = @(':core:privacy:connectedDebugAndroidTest', ':app:connectedDebugAndroidTest', 'connectedCheck',
            '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.InstallTest,dev.kestrel.app.X#y', '-x', ':core:voice:connectedDebugAndroidTest',
            '--exclude-task', 'lint', '--info', '--quiet', '-q', '--stacktrace', '--console=plain', '--offline', '--continue', '--rerun-tasks',
            '--no-configuration-cache')
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

    It 'refuses every other task and option, such as one that uninstalls Kestrel or runs an init script: <_>' -ForEach @(
        'uninstallAll', ':app:uninstallDebug', 'uA', ':app:installDebug', 'cDAT', 'check', '-I', '--init-script=uninstall.gradle', '-g',
        '--gradle-user-home=x', '-p', '--include-build=x', '-Dandroid.serial=x', '-Pother=x', '-Pandroid.injected.testOnly=false', '--debug',
        '-xlint', '--exclude-task=lint'
    ) {
        $thrown = { Get-ConnectedTestArguments -Arguments @(':app:connectedDebugAndroidTest', $_) } | Should -Throw -PassThru
        $thrown.Exception.Message | Should -BeLike "Refusing the Gradle argument '$_'. connected-test.ps1 accepts only connected test tasks, *"
    }

    It 'refuses -x without the task that it excludes' {
        { Get-ConnectedTestArguments -Arguments @(':app:connectedDebugAndroidTest', '-x') } |
            Should -Throw -ExpectedMessage "Refusing the Gradle argument '-x' without the task that it excludes."
        { Get-ConnectedTestArguments -Arguments @(':app:connectedDebugAndroidTest', '-x', '--info') } |
            Should -Throw -ExpectedMessage "Refusing the Gradle argument '-x' without the task that it excludes."
    }

    It 'refuses Gradle''s --serial option' {
        { Get-ConnectedTestArguments -Arguments @(':app:connectedDebugAndroidTest', '--serial=emulator-5556') } |
            Should -Throw -ExpectedMessage "Refusing the Gradle argument '--serial=emulator-5556'. Choose the device with -Serial."
    }
}

Describe 'Get-FirstInstallTime' {
    It 'reads the time of each user that has the package, and not of a user without it, as Android 14 and newer show them' {
        $dump = @(
            'Packages:', '  Package [dev.kestrel] (1a2b3c):', '    versionCode=1 minSdk=31 targetSdk=37',
            '    User 0: ceDataInode=4242 installed=true hidden=false suspended=false stopped=false', '      firstInstallTime=2026-11-20 10:11:12',
            '    User 10: ceDataInode=0 installed=false hidden=false suspended=false stopped=true', '      firstInstallTime=1970-01-01 01:00:00'
        )

        Get-FirstInstallTime -Dump $dump | Should -BeExactly @('user 0: 2026-11-20 10:11:12')
    }

    It 'reads the one time of the package that older versions of Android show before its users' {
        Get-FirstInstallTime -Dump @('  Package [dev.kestrel] (1a2b3c):', '    firstInstallTime=2026-11-20 10:11:12', '    User 0: installed=true') |
            Should -BeExactly @('2026-11-20 10:11:12')
    }
}

Describe 'Get-KestrelInstallChange' {
    BeforeAll {
        function New-PackageState($InstallTimes) { [pscustomobject]@{ KestrelInstallTimes = $InstallTimes; TestPackages = @() } }
        $installed = New-PackageState @('user 0: 2026-11-20 10:11:12')
        $lost = 'which deleted its data there: its AI profiles, permissions, settings and the agent''s memory.'
    }

    It 'finds no change when Kestrel keeps its first-install time' {
        Get-KestrelInstallChange -Before $installed -After (New-PackageState @('user 0: 2026-11-20 10:11:12')) -Serial emulator-5554 | Should -BeNullOrEmpty
    }

    It 'names the loss when Kestrel was installed again, with a new first-install time' {
        Get-KestrelInstallChange -Before $installed -After (New-PackageState @('user 0: 2026-11-24 16:20:00')) -Serial emulator-5554 | Should -BeExactly (
            'Kestrel (dev.kestrel) was installed again on emulator-5554 during the tests (first installed user 0: 2026-11-20 10:11:12 ' +
            "before them, user 0: 2026-11-24 16:20:00 now), $lost")
    }

    It 'names the loss when Kestrel was uninstalled' {
        Get-KestrelInstallChange -Before $installed -After (New-PackageState $null) -Serial emulator-5554 |
            Should -BeExactly "Kestrel (dev.kestrel) was uninstalled from emulator-5554 during the tests, $lost"
    }

    It 'finds no change when Kestrel was not installed before the tests' {
        Get-KestrelInstallChange -Before (New-PackageState $null) -After $installed -Serial emulator-5554 | Should -BeNullOrEmpty
    }
}

Describe 'The helpers that read and change the device' {
    BeforeAll {
        Mock Get-Adb { $fakeAdb }
        Mock Start-Sleep {}
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

    Context 'Kestrel''s packages' {
        It 'reads Kestrel''s first-install time and the test packages, of which Kestrel is none' {
            Set-FakeDevice -Packages ([ordered]@{
                    'dev.kestrel' = '2026-11-20 10:11:12'; 'dev.kestrel.testapp' = '2026-11-21 09:00:00'
                    'dev.kestrel.privacy.test' = '2026-11-21 09:01:00'; 'com.android.vending' = '2026-01-01 00:00:00'
                })

            $state = Get-KestrelPackageState -Serial emulator-5554

            $state.KestrelInstallTimes | Should -BeExactly @('user 0: 2026-11-20 10:11:12')
            $state.TestPackages | Should -BeExactly @('dev.kestrel.privacy.test', 'dev.kestrel.testapp')
        }

        It 'reads no first-install time when Kestrel is not installed' {
            Set-FakeDevice -Packages @{ 'dev.kestrel.testapp' = '2026-11-21 09:00:00' }

            $state = Get-KestrelPackageState -Serial emulator-5554

            $state.KestrelInstallTimes | Should -BeNullOrEmpty
            $state.TestPackages | Should -BeExactly @('dev.kestrel.testapp')
        }

        It 'uninstalls the test packages that the run added, keeps Kestrel and those that were there, and returns $true' {
            Set-FakeDevice -Packages @{ 'dev.kestrel' = '2026-11-20 10:11:12'; 'dev.kestrel.testapp' = '2026-11-21 09:00:00' }
            $before = Get-KestrelPackageState -Serial emulator-5554
            # Gradle installs the test APKs of :app and :core:voice.
            Set-FakeDevice -Packages @{
                'dev.kestrel' = '2026-11-20 10:11:12'; 'dev.kestrel.testapp' = '2026-11-21 09:00:00'
                'dev.kestrel.test' = '2026-11-24 16:00:00'; 'dev.kestrel.voice.test' = '2026-11-24 16:00:01'
            }

            $output = @(Complete-TestPackages -Serial emulator-5554 -Before $before 2>&1)

            $output | Should -BeExactly @($true)
            Get-FakePackage | Sort-Object | Should -BeExactly @('dev.kestrel', 'dev.kestrel.testapp')
        }

        It 'writes an error that names the loss and returns $false when Kestrel was <Name>' -ForEach @(
            @{ Name = 'installed again'; After = @{ 'dev.kestrel' = '2026-11-24 16:20:00' }; Message = 'Kestrel (dev.kestrel) was installed again on emulator-5554 during the tests *' }
            @{ Name = 'uninstalled'; After = @{}; Message = 'Kestrel (dev.kestrel) was uninstalled from emulator-5554 during the tests, which deleted its data there*' }
        ) {
            Set-FakeDevice -Packages @{ 'dev.kestrel' = '2026-11-20 10:11:12' }
            $before = Get-KestrelPackageState -Serial emulator-5554
            Set-FakeDevice -Packages $After

            $output = @(Complete-TestPackages -Serial emulator-5554 -Before $before 2>&1)

            $errors = @($output | Where-Object { $_ -is [System.Management.Automation.ErrorRecord] })
            $errors | Should -HaveCount 1
            "$($errors[0])" | Should -BeLike $Message
            $output[-1] | Should -BeFalse
        }

        It 'returns $false and warns with the command that removes the test packages by hand when it cannot read them' {
            Set-FakeDevice -Packages @{ 'dev.kestrel.testapp' = '2026-11-21 09:00:00' }
            $before = Get-KestrelPackageState -Serial emulator-5554
            Set-FakeDevice -Packages @{ 'dev.kestrel.testapp' = '2026-11-21 09:00:00' } -Failures 10
            Mock Write-Warning {}

            Complete-TestPackages -Serial emulator-5554 -Before $before | Should -BeFalse

            Should -Invoke Write-Warning -Times 1 -Exactly -ParameterFilter {
                $Message -like "Could not read the packages of emulator-5554 (*), so the script could not check that Kestrel was not uninstalled or installed again, nor uninstall the test packages. Run: & '$fakeAdb' -s emulator-5554 shell 'for p in `$(pm list packages dev.kestrel); do case `${p#package:} in dev.kestrel.testapp) ;; *"
            }
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

    Context 'The owner''s data on kestrel-api37' {
        It 'knows kestrel-api37 as the device with the owner''s data, and the A71 as another' {
            Test-OwnerDataDevice -Serial emulator-5554 | Should -BeTrue

            Set-FakeDevice -A71
            Assert-TestDevice -Serial R58N66RVD7Z
            Test-OwnerDataDevice -Serial R58N66RVD7Z | Should -BeFalse
        }

        It 'refuses provider-delete on kestrel-api37, and nothing else' {
            { Assert-OwnerDataCommand -Serial emulator-5554 -Command provider-delete } |
                Should -Throw -ExpectedMessage "provider-delete was refused on emulator-5554: kestrel-api37 keeps Kestrel's Copilot profile and its credential (AGENTS.md)."
            { Assert-OwnerDataCommand -Serial emulator-5554 -Command provider-standin } | Should -Not -Throw

            Set-FakeDevice -A71
            Assert-TestDevice -Serial R58N66RVD7Z
            { Assert-OwnerDataCommand -Serial R58N66RVD7Z -Command provider-delete } | Should -Not -Throw
        }

        It 'reads the stand-in''s answer with the default profile''s kind and ID, its credential and the number of Copilot profiles' {
            $state = ConvertFrom-StandInAnswer 'standin=off profile=copilot copilot=1 id=profile-1 credential=present'

            $state['standin'] | Should -Be 'off'
            $state['profile'] | Should -Be 'copilot'
            $state['copilot'] | Should -Be '1'
            $state['id'] | Should -Be 'profile-1'
            $state['credential'] | Should -Be 'present'
            { ConvertFrom-StandInAnswer 'standin=on' } | Should -Throw 'Not an answer of provider-standin*'
            { ConvertFrom-StandInAnswer 'standin=off profile=copilot copilot=1 id=profile-1 credential=maybe' } | Should -Throw 'Not an answer of provider-standin*'
            # Older builds answer without the count, or without the ID and the credential.
            { ConvertFrom-StandInAnswer 'standin=off profile=none' } | Should -Throw 'Not an answer of provider-standin*install.ps1*'
            { ConvertFrom-StandInAnswer 'standin=off profile=copilot copilot=1' } |
                Should -Throw -ExceptionType ([System.IO.InvalidDataException]) -ExpectedMessage 'Not an answer of provider-standin*install.ps1*'
        }

        It 'reads Kestrel''s profiles through hook.ps1 provider-standin, after it unstops Kestrel' {
            Mock Invoke-TimedProcess { [pscustomobject]@{ Completed = $true; ExitCode = 0; Output = "standin=off profile=copilot copilot=1 id=profile-1 credential=present`r`n"; Id = 1 } }

            $profiles = Get-KestrelProfiles -Serial emulator-5554

            $profiles.Profile | Should -BeExactly 'copilot'
            $profiles.Copilot | Should -Be 1
            $profiles.Id | Should -BeExactly 'profile-1'
            $profiles.Credential | Should -BeExactly 'present'
            (Get-FakeDevice).calls | Should -Contain 'pm unstop dev.kestrel && echo && echo kestrel-end'
            Should -Invoke Invoke-TimedProcess -Times 1 -Exactly -ParameterFilter {
                ($ArgumentList -join ' ') -like "-NoProfile -ExecutionPolicy Bypass -File *hook.ps1 -Serial emulator-5554 -Command provider-standin"
            }
        }

        It 'throws when hook.ps1 provider-standin fails' {
            Mock Invoke-TimedProcess { [pscustomobject]@{ Completed = $true; ExitCode = 1; Output = ''; Id = 1 } }

            { Get-KestrelProfiles -Serial emulator-5554 } | Should -Throw -ExpectedMessage 'scripts\hook.ps1 provider-standin ended with exit code 1'
        }

        It 'reads the Google accounts and Kestrel''s profiles, or why the profiles could not be read' {
            Set-FakeDevice -Accounts 2
            Mock Get-KestrelProfiles { [pscustomobject]@{ Profile = 'copilot'; Copilot = 1; Id = 'profile-1'; Credential = 'present' } }

            $state = Get-OwnerDataState -Serial emulator-5554

            $state.GoogleAccounts | Should -Be 2
            $state.Profiles.Profile | Should -BeExactly 'copilot'
            Format-OwnerDataState $state 'kestrel-api37' |
                Should -BeExactly 'kestrel-api37 has 2 Google accounts; Kestrel''s default profile is copilot with its credential present, and it has 1 Copilot profiles'

            Mock Get-KestrelProfiles { throw 'scripts\hook.ps1 provider-standin ended with exit code 1' }
            $unread = Get-OwnerDataState -Serial emulator-5554
            $unread.Profiles | Should -BeNullOrEmpty
            $unread.ProfilesUnread | Should -BeExactly 'scripts\hook.ps1 provider-standin ended with exit code 1'
        }

        It 'stops when Kestrel answers provider-standin in another form, as an older build does, instead of comparing only the accounts' {
            Mock Invoke-TimedProcess { [pscustomobject]@{ Completed = $true; ExitCode = 0; Output = "standin=off profile=copilot copilot=1`r`n"; Id = 1 } }

            { Get-OwnerDataState -Serial emulator-5554 } |
                Should -Throw -ExceptionType ([System.IO.InvalidDataException]) -ExpectedMessage 'Not an answer of provider-standin*install.ps1*'
        }

        It 'says that the data could not be checked when the accounts cannot be read' {
            Set-FakeDevice -Failures 10
            $before = [pscustomobject]@{ GoogleAccounts = 1; Profiles = $null; ProfilesUnread = 'x' }

            Compare-OwnerData -Serial emulator-5554 -Before $before -During 'the tests' |
                Should -BeLike 'The owner''s data on emulator-5554 could not be read after the tests (*), so it was not checked there.'
        }

        It 'names an answer of provider-standin in another form after a run, which it could not compare' {
            $before = [pscustomobject]@{ GoogleAccounts = 1; Profiles = [pscustomobject]@{ Profile = 'copilot'; Copilot = 1; Id = 'profile-1'; Credential = 'present' }; ProfilesUnread = $null }
            Mock Invoke-TimedProcess { [pscustomobject]@{ Completed = $true; ExitCode = 0; Output = "standin=off profile=copilot copilot=1`r`n"; Id = 1 } }

            Compare-OwnerData -Serial emulator-5554 -Before $before -During 'the tests' |
                Should -BeLike 'The owner''s data on emulator-5554 could not be read after the tests (Not an answer of provider-standin*), so it was not checked there.'
        }
    }
}

Describe 'Get-OwnerDataChange' {
    BeforeAll {
        function New-OwnerData([int]$Accounts, [string]$Kind, [int]$Copilot, [string]$Unread, [string]$Id = 'profile-1', [string]$Stored = 'present') {
            $profiles = if ($Kind) { [pscustomobject]@{ Profile = $Kind; Copilot = $Copilot; Id = $Id; Credential = $Stored } } else { $null }
            [pscustomobject]@{ GoogleAccounts = $Accounts; Profiles = $profiles; ProfilesUnread = $Unread }
        }
        $before = New-OwnerData 1 'copilot' 1
    }

    It 'finds no change when the accounts and the profiles are as before' {
        Get-OwnerDataChange -Before $before -After (New-OwnerData 1 'copilot' 1) -Serial emulator-5554 -During 'the tests' | Should -BeNullOrEmpty
    }

    It 'names each change' {
        Get-OwnerDataChange -Before $before -After (New-OwnerData 0 'none' 0 -Id 'none' -Stored 'none') -Serial emulator-5554 -During 'the tests' | Should -BeExactly @(
            'The number of Google accounts on emulator-5554 changed during the tests: 1 before, 0 now.'
            'Kestrel''s default profile on emulator-5554 changed during the tests: copilot before, none now.'
            'The number of Kestrel''s Copilot profiles on emulator-5554 changed during the tests: 1 before, 0 now.'
            'Kestrel''s default profile on emulator-5554 was replaced during the tests: profile-1 before, none now.'
            'The credential of Kestrel''s default profile on emulator-5554 changed during the tests: present before, none now.'
        )
    }

    It 'catches a Copilot profile that another Copilot profile replaced, although the kind and the count stay' {
        Get-OwnerDataChange -Before $before -After (New-OwnerData 1 'copilot' 1 -Id 'profile-2') -Serial emulator-5554 -During 'the tests' |
            Should -BeExactly 'Kestrel''s default profile on emulator-5554 was replaced during the tests: profile-1 before, profile-2 now.'
    }

    It 'catches a credential that was deleted while its profile stayed' {
        Get-OwnerDataChange -Before $before -After (New-OwnerData 1 'copilot' 1 -Stored 'missing') -Serial emulator-5554 -During 'the tests' |
            Should -BeExactly 'The credential of Kestrel''s default profile on emulator-5554 changed during the tests: present before, missing now.'
    }

    It 'counts profiles that cannot be read afterwards as a change' {
        Get-OwnerDataChange -Before $before -After (New-OwnerData 1 '' 0 'exit code 1') -Serial emulator-5554 -During 'the tests' |
            Should -BeExactly 'Kestrel''s profiles on emulator-5554 could not be read after the tests (exit code 1), so its Copilot profile may be gone.'
    }

    It 'compares only the accounts when the profiles could not be read before' {
        $unreadBefore = New-OwnerData 1 '' 0 'Kestrel is not installed'

        Get-OwnerDataChange -Before $unreadBefore -After (New-OwnerData 1 'copilot' 1) -Serial emulator-5554 -During 'the tests' | Should -BeNullOrEmpty
        Get-OwnerDataChange -Before $unreadBefore -After (New-OwnerData 2 'copilot' 1) -Serial emulator-5554 -During 'the tests' |
            Should -BeExactly 'The number of Google accounts on emulator-5554 changed during the tests: 1 before, 2 now.'
    }
}

Describe 'The health of kestrel-api37' {
    BeforeAll {
        # An answer of Format-EmulatorHealthCommand's command, as kestrel-api37 gave it on 2026-11-27 (healthy: 57 ticks
        # of the sensors HAL in 10.03 s), with the HAL's CPU ticks and the load average of the test.
        function New-HealthAnswer([long]$SecondTicks = 5566, [string]$Load = '0.05 0.51 1.02 1/2920 5592', [string]$SecondPid = '487') {
            @('pid=487', 'ticks=100', 'cpus=4'
                'first=1221.23 3836.58 487 (android.hardwar) S 1 487 0 0 -1 4194560 842 0 18 0 70 5439 0 0 20 0 5 0 938 11156234240 1358'
                "second=1231.26 3875.04 $SecondPid (android.hardwar) S 1 487 0 0 -1 4194560 842 0 18 0 0 $SecondTicks 0 0 20 0 5 0 938 11156234240 1358"
                "load=$Load")
        }
        Mock Get-Adb { $fakeAdb }
        Mock Start-Sleep {}
        Mock Write-Host {}
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

    It 'reads the sensors HAL''s CPU use between the two samples, the load average over 5 minutes and the CPUs' {
        $reading = ConvertFrom-EmulatorHealthAnswer (New-HealthAnswer)

        [math]::Round($reading.SensorsCpuPercent, 2) | Should -Be 5.68
        $reading.Load | Should -Be 0.51
        $reading.Cpus | Should -Be 4
        [math]::Round($reading.Seconds, 2) | Should -Be 10.03
    }

    It 'lets a healthy kestrel-api37 start, and says how healthy it is' {
        Mock Invoke-SettingsCommand { New-HealthAnswer }

        { Assert-EmulatorHealthy -Serial emulator-5554 } | Should -Not -Throw

        Should -Invoke Write-Host -Times 1 -Exactly -ParameterFilter {
            $Object -eq 'kestrel-api37 (emulator-5554) is healthy: the sensors HAL used 5.7 % of one CPU over 10.0 s, and the load average over 5 minutes was 0.51 on 4 CPUs.'
        }
    }

    It 'refuses a degraded kestrel-api37 with each measure above its limit and the advice to cold-boot: <Name>' -ForEach @(
        @{ Name = 'the sensors HAL'; Ticks = 5509 + 476; Load = '3.00 2.00 1.00 1/2920 5592'
            Message = 'Refusing to start on emulator-5554: kestrel-api37 has degraded: the sensors HAL (android.hardware.sensors-service.multihal) used 47.5 % of one CPU over 10.0 s, above the limit of 30 %. Cold-boot it with scripts\cold-boot.ps1 -Serial emulator-5554, and then run this again.' }
        @{ Name = 'the load'; Ticks = 5566; Load = '9.00 8.53 5.95 4/2920 5592'
            Message = 'Refusing to start on emulator-5554: kestrel-api37 has degraded: the load average over 5 minutes was 8.53 on 4 CPUs, above the limit of 6. Cold-boot it with *' }
        @{ Name = 'both'; Ticks = 5509 + 900; Load = '12.39 9.64 7.36 4/2920 5592'
            Message = 'Refusing to start on emulator-5554: kestrel-api37 has degraded: the sensors HAL * above the limit of 30 %; the load average * above the limit of 6. Cold-boot it with *' }
    ) {
        Mock Invoke-SettingsCommand { New-HealthAnswer -SecondTicks $Ticks -Load $Load }

        { Assert-EmulatorHealthy -Serial emulator-5554 } | Should -Throw -ExpectedMessage $Message
    }

    It 'lets kestrel-api37 start at the limits, as in the first minutes after a cold boot' {
        # 30 % of one CPU over 10.03 s is 300.9 ticks, and 6 is the limit of the load on 4 CPUs.
        Mock Invoke-SettingsCommand { New-HealthAnswer -SecondTicks (5509 + 300) -Load '17.22 6.00 1.57 1/2920 5592' }

        { Assert-EmulatorHealthy -Serial emulator-5554 } | Should -Not -Throw
    }

    It 'refuses kestrel-api37 with the advice to cold-boot when the health cannot be read: <Name>' -ForEach @(
        @{ Name = 'no sensors HAL'; Answer = @('pid=', 'ticks=100', 'cpus=4', 'first=1221.23 3836.58', 'second=1231.26 3875.04', 'load=0.05 0.51 1.02 1/2920 5592')
            Message = '*no process ID of android.hardware.sensors-service.multihal*' }
        @{ Name = 'a HAL that started again'; Answer = 'restarted'; Message = '*no second sample of android.hardware.sensors-service.multihal under the process ID 487*' }
        @{ Name = 'no load'; Answer = 'noload'; Message = '*no load average*' }
        @{ Name = 'no answer'; Answer = 'throws'; Message = '*Could not run*' }
    ) {
        Mock Invoke-SettingsCommand {
            switch ($Answer) {
                'restarted' { New-HealthAnswer -SecondPid '912' }
                'noload' { New-HealthAnswer -Load '' }
                'throws' { throw "Could not run 'p=...' on emulator-5554." }
                default { $Answer }
            }
        }

        { Assert-EmulatorHealthy -Serial emulator-5554 } |
            Should -Throw -ExpectedMessage "Refusing to start on emulator-5554: the health of kestrel-api37 could not be read ($Message). Cold-boot it with scripts\cold-boot.ps1 -Serial emulator-5554, and then run this again."
    }

    It 'reads nothing on the A71' {
        Set-FakeDevice -A71
        Assert-TestDevice -Serial R58N66RVD7Z
        Mock Invoke-SettingsCommand { throw 'no command may reach the A71' }

        { Assert-EmulatorHealthy -Serial R58N66RVD7Z } | Should -Not -Throw

        Should -Invoke Invoke-SettingsCommand -Times 0 -Exactly
    }
}

Describe 'Invoke-KestrelColdBoot' {
    BeforeAll {
        Mock Write-Host {}
        Mock Write-Warning {}
    }

    BeforeEach {
        $steps = [System.Collections.Generic.List[string]]::new()
        $state = [pscustomobject]@{ GoogleAccounts = 1; Profiles = [pscustomobject]@{ Profile = 'copilot'; Copilot = 1; Id = 'profile-1'; Credential = 'present' }; ProfilesUnread = $null }
        Mock Assert-TestDevice { $steps.Add("Assert-TestDevice $Serial") }
        Mock Test-KestrelEmulatorDevice { $true }
        Mock Get-OwnerDataState { $steps.Add("Get-OwnerDataState $Serial"); $state }
        Mock Restart-KestrelEmulator { $steps.Add("Restart-KestrelEmulator $Serial window=$Window cold=$ColdBoot") }
        Mock Compare-OwnerData { $steps.Add("Compare-OwnerData $Serial during=$During") }
        Mock Wait-EmulatorHealthy { $steps.Add("Wait-EmulatorHealthy $Serial") }
    }

    It 'checks the device, reads the owner''s data, cold-boots without a window, compares the owner''s data and waits for a healthy emulator, in this order' {
        Invoke-KestrelColdBoot -Serial emulator-5554

        @($steps) | Should -BeExactly @(
            'Assert-TestDevice emulator-5554'
            'Get-OwnerDataState emulator-5554'
            'Restart-KestrelEmulator emulator-5554 window=False cold=True'
            'Compare-OwnerData emulator-5554 during=the cold boot'
            'Wait-EmulatorHealthy emulator-5554'
        )
        Should -Invoke Compare-OwnerData -Times 1 -Exactly -ParameterFilter { $Before -eq $state }
    }

    It 'refuses every device other than kestrel-api37 before it reads or closes anything' {
        Mock Test-KestrelEmulatorDevice { $false }

        { Invoke-KestrelColdBoot -Serial R58N66RVD7Z } | Should -Throw -ExpectedMessage 'Refusing to cold-boot R58N66RVD7Z: only kestrel-api37 is cold-booted.'

        @($steps) | Should -BeExactly @('Assert-TestDevice R58N66RVD7Z')
    }

    It 'does not close the emulator when the owner''s data cannot be read before the boot' {
        Mock Get-OwnerDataState { throw [System.IO.InvalidDataException]::new('Not an answer of provider-standin: ''x''. Install the current debug build with scripts\install.ps1.') }

        { Invoke-KestrelColdBoot -Serial emulator-5554 } | Should -Throw -ExpectedMessage 'Not an answer of provider-standin*'

        Should -Invoke Restart-KestrelEmulator -Times 0 -Exactly
    }

    It 'throws with the reason when the emulator does not come back, and compares nothing' {
        Mock Restart-KestrelEmulator { 'kestrel-api37 started again as ''emulator-5556'', not as emulator-5554' }

        { Invoke-KestrelColdBoot -Serial emulator-5554 } |
            Should -Throw -ExpectedMessage 'The cold boot of kestrel-api37 failed: kestrel-api37 started again as ''emulator-5556'', not as emulator-5554.'

        Should -Invoke Compare-OwnerData -Times 0 -Exactly
    }

    It 'throws with each change when the owner''s data changed during the boot' {
        Mock Compare-OwnerData { 'The number of Google accounts on emulator-5554 changed during the cold boot: 1 before, 0 now.' }

        { Invoke-KestrelColdBoot -Serial emulator-5554 } |
            Should -Throw -ExpectedMessage 'The number of Google accounts on emulator-5554 changed during the cold boot: 1 before, 0 now.'

        Should -Invoke Wait-EmulatorHealthy -Times 0 -Exactly
    }

    It 'refuses to boot when Kestrel''s profiles cannot be read before it, because the check after it could not tell the owner''s profile from another' {
        Mock Get-OwnerDataState { $steps.Add("Get-OwnerDataState $Serial"); [pscustomobject]@{ GoogleAccounts = 1; Profiles = $null; ProfilesUnread = 'scripts\hook.ps1 provider-standin did not end within 60 s' } }

        { Invoke-KestrelColdBoot -Serial emulator-5554 } | Should -Throw -ExpectedMessage ("Refusing to cold-boot emulator-5554: Kestrel's profiles could not be read before the boot " +
            '(scripts\hook.ps1 provider-standin did not end within 60 s), so the boot could not check that the default profile keeps its ID and its credential (AGENTS.md).*')

        @($steps) | Should -BeExactly @('Assert-TestDevice emulator-5554', 'Get-OwnerDataState emulator-5554')
    }
}

Describe 'Wait-EmulatorHealthy' {
    BeforeEach {
        Mock Start-Sleep {}
    }

    It 'reads the health until it passes, with a pause of 30 s before each reading after the first' {
        $answers = [System.Collections.Generic.Queue[object]]::new([object[]]@('kestrel-api37 has degraded: the load average over 5 minutes was 6.40 on 4 CPUs, above the limit of 6', $null))
        Mock Get-EmulatorHealthProblem { $answers.Dequeue() }

        { Wait-EmulatorHealthy -Serial emulator-5554 } | Should -Not -Throw

        Should -Invoke Get-EmulatorHealthProblem -Times 2 -Exactly -ParameterFilter { $Serial -eq 'emulator-5554' }
        Should -Invoke Start-Sleep -Times 1 -Exactly -ParameterFilter { $Seconds -eq 30 }
    }

    It 'throws with the last problem when the emulator has not passed after 5 minutes of pauses' {
        Mock Get-EmulatorHealthProblem { 'kestrel-api37 has degraded: the load average over 5 minutes was 6.40 on 4 CPUs, above the limit of 6' }

        { Wait-EmulatorHealthy -Serial emulator-5554 } | Should -Throw -ExpectedMessage ("kestrel-api37 (emulator-5554) was cold-booted, but it did not pass the gates' health check within 5 minutes: " +
            'kestrel-api37 has degraded: the load average over 5 minutes was 6.40 on 4 CPUs, above the limit of 6. Look for what loads the emulator *')

        Should -Invoke Get-EmulatorHealthProblem -Times 11 -Exactly
        Should -Invoke Start-Sleep -Times 10 -Exactly
    }
}