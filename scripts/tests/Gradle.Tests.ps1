# Tests of scripts\gradle.ps1. The script runs in a shell of its own from a copy of the scripts folder in the test drive,
# next to a fake gradlew.bat that writes down its arguments and ANDROID_SERIAL, so the tests need no build and no
# device. scripts\test-scripts.ps1 runs these tests in Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

Describe 'gradle.ps1' {
    BeforeAll {
        $root = Join-Path $TestDrive 'repository'
        New-Item -ItemType Directory -Path (Join-Path $root 'scripts\lib') | Out-Null
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot '..\gradle.ps1') -Destination (Join-Path $root 'scripts')
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot '..\lib\Kestrel.ps1') -Destination (Join-Path $root 'scripts\lib')
        $arguments = Join-Path $root 'gradlew-arguments.txt'
        $serial = Join-Path $root 'gradlew-serial.txt'
        Set-Content -LiteralPath (Join-Path $root 'gradlew.bat') -Encoding Ascii -Value @(
            '@echo off'
            'echo %*> "%~dp0gradlew-arguments.txt"'
            'echo %ANDROID_SERIAL%> "%~dp0gradlew-serial.txt"'
            'exit /b 0'
        )
        $shell = (Get-Process -Id $PID).Path
        # Runs gradle.ps1 as a script of its own and prints the message of a refusal on one line, which the shells would
        # wrap at the console's width when they show the error.
        $runner = Join-Path $root 'run-gradle.ps1'
        Set-Content -LiteralPath $runner -Value @(
            'try { & "$PSScriptRoot\scripts\gradle.ps1" @args } catch { "refused: $($_.Exception.Message)"; exit 1 }'
            'exit $LASTEXITCODE'
        )
        # Use-KestrelToolchain needs a JDK and an Android SDK folder, which the fake Gradle does not use.
        $saved = @{}
        foreach ($name in 'JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SERIAL') { $saved[$name] = [Environment]::GetEnvironmentVariable($name) }
        $env:JAVA_HOME = $TestDrive
        $env:ANDROID_HOME = $TestDrive

        # Runs the copy of gradle.ps1 with the arguments and with ANDROID_SERIAL set to -Serial, or unset, and returns its
        # exit code and output.
        function Invoke-Gradle([string[]]$Arguments, [string]$Serial) {
            $env:ANDROID_SERIAL = $Serial
            $output = @(& $shell -NoProfile -ExecutionPolicy Bypass -File $runner @Arguments 2>&1 | ForEach-Object { "$_" })
            [pscustomobject]@{ Code = $LASTEXITCODE; Output = $output -join "`n" }
        }
    }

    BeforeEach {
        Remove-Item -LiteralPath $arguments, $serial -ErrorAction SilentlyContinue
    }

    AfterAll {
        foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name]) }
    }

    It 'refuses <_>, with or without ANDROID_SERIAL, and does not start Gradle' -ForEach @(
        ':app:connectedDebugAndroidTest', 'connectedCheck', ':app:installDebug', 'uninstallAll', 'deviceCheck', 'cDAT', 'iD'
    ) {
        foreach ($deviceSerial in '', 'emulator-5554') {
            $run = Invoke-Gradle -Arguments @('check', $_) -Serial $deviceSerial

            $run.Code | Should -Be 1
            $run.Output | Should -BeExactly ("refused: Refusing the Gradle task '$_', which can install, uninstall or run tests on a device. Run device " +
                'tests with scripts\connected-test.ps1 and install with scripts\install.ps1.')
            $arguments | Should -Not -Exist
        }
    }

    It 'refuses Gradle''s --serial option' {
        $run = Invoke-Gradle -Arguments @('check', '--serial=emulator-5554') -Serial ''

        $run.Code | Should -Be 1
        $run.Output | Should -BeExactly "refused: Refusing the Gradle argument '--serial=emulator-5554'. gradle.ps1 reaches no device."
        $arguments | Should -Not -Exist
    }

    It 'passes a build to Gradle, with an ANDROID_SERIAL that names no device' {
        $run = Invoke-Gradle -Arguments @('check', 'kestrelAudit', ':tools:eval:installDist', '--stop') -Serial 'emulator-5554'

        $run.Code | Should -Be 0
        $run.Output | Should -BeNullOrEmpty
        (Get-Content -LiteralPath $arguments) | Should -BeExactly '--console=plain check kestrelAudit :tools:eval:installDist --stop'
        (Get-Content -LiteralPath $serial) | Should -BeExactly 'kestrel-no-device'
    }
}
