# Tests of scripts\eval.ps1's out-of-date rule and of its rebuild (design D2 of sub-project 6). The script needs Gradle
# and a device, so the tests load only its helper functions from the script's syntax tree, and build a small tree of files
# with their own write times in the test drive. scripts\test-scripts.ps1 runs these tests in Windows PowerShell 5.1 and in
# PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

BeforeAll {
    $tokens = $null
    $errors = $null
    $tree = [System.Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot\..\eval.ps1", [ref]$tokens, [ref]$errors)
    $helpers = @('Get-NewestSource', 'Test-EvalStale', 'Invoke-Gradle', 'Update-EvalTool', 'Invoke-Java', 'Invoke-EvalScript')
    $functions = $tree.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -in $helpers
        }, $true)
    foreach ($function in $functions) { . ([scriptblock]::Create($function.Extent.Text)) }

    # A repository with the tool's sources, its build output, the installed copy and the stamp of its build, every file
    # written at $Time.
    function New-Repository([datetime]$Time) {
        $root = Join-Path $TestDrive ([guid]::NewGuid().ToString('N'))
        foreach ($file in 'tools\eval\build.gradle.kts', 'tools\eval\src\main\kotlin\dev\kestrel\eval\Main.kt',
            'tools\eval\src\test\kotlin\dev\kestrel\eval\CliTest.kt', 'tools\eval\build\install\eval\lib\eval.jar',
            'tools\eval\build\eval-built.stamp', 'tools\eval\build\classes\kotlin\main\Main.class',
            'build-logic\convention\src\main\kotlin\dev\kestrel\build\JvmLibraryConventionPlugin.kt',
            'build-logic\convention\build\libs\convention.jar', 'gradle\libs.versions.toml', 'settings.gradle.kts') {
            $path = Join-Path $root $file
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
            [IO.File]::WriteAllText($path, 'x')
            (Get-Item -LiteralPath $path).LastWriteTimeUtc = $Time
        }
        $root
    }

    function Set-WriteTime([string]$Root, [string]$File, [datetime]$Time) { (Get-Item -LiteralPath (Join-Path $Root $File)).LastWriteTimeUtc = $Time }
}

Describe 'scripts\eval.ps1' {
    BeforeEach {
        $built = [datetime]::new(2026, 11, 23, 12, 0, 0, [DateTimeKind]::Utc)
        $later = $built.AddMinutes(5)
    }

    It 'parses, and finds its helpers' {
        $errors | Should -BeNullOrEmpty
        @($functions).Count | Should -Be $helpers.Count
    }

    It 'rebuilds when the installed copy or the stamp of its build is missing' {
        foreach ($file in 'tools\eval\build\install\eval\lib\eval.jar', 'tools\eval\build\eval-built.stamp') {
            $root = New-Repository $built
            Remove-Item -LiteralPath (Join-Path $root $file)

            Test-EvalStale $root | Should -BeTrue -Because $file
        }
    }

    It 'does not rebuild when nothing changed since the installed copy' {
        Test-EvalStale (New-Repository $built) | Should -BeFalse
    }

    It 'rebuilds when a source of the tool, the build logic, the version catalog or the settings is newer than the installed copy' {
        foreach ($file in 'tools\eval\src\main\kotlin\dev\kestrel\eval\Main.kt', 'tools\eval\build.gradle.kts',
            'build-logic\convention\src\main\kotlin\dev\kestrel\build\JvmLibraryConventionPlugin.kt', 'gradle\libs.versions.toml', 'settings.gradle.kts') {
            $root = New-Repository $built
            Set-WriteTime $root $file $later

            Test-EvalStale $root | Should -BeTrue -Because $file
        }
    }

    It 'does not rebuild for a newer file in the tool''s build folder or in build-logic''s build output' {
        $root = New-Repository $built
        Set-WriteTime $root 'tools\eval\build\classes\kotlin\main\Main.class' $later
        Set-WriteTime $root 'build-logic\convention\build\libs\convention.jar' $later

        Test-EvalStale $root | Should -BeFalse
    }

    It 'builds the installed copy only when it is out of date, and stops the Gradle daemon after the build, also after a failed one' {
        $calls = [System.Collections.Generic.List[string]]::new()
        Mock Invoke-Gradle { $calls.Add($Arguments -join ' '); 0 }

        Update-EvalTool (New-Repository $built) | Should -BeTrue
        $calls.Count | Should -Be 0

        $root = New-Repository $built
        Set-WriteTime $root 'tools\eval\build.gradle.kts' $later
        Update-EvalTool $root | Should -BeTrue
        @($calls) | Should -Be @(':tools:eval:installDist', '--stop')

        $calls.Clear()
        Mock Invoke-Gradle { $calls.Add($Arguments -join ' '); if ($Arguments -contains '--stop') { 0 } else { 1 } }
        $root = New-Repository $built
        Set-WriteTime $root 'tools\eval\build.gradle.kts' $later
        Update-EvalTool $root | Should -BeFalse
        @($calls) | Should -Be @(':tools:eval:installDist', '--stop')
        Test-EvalStale $root | Should -BeTrue
    }

    It 'is current after a build that left eval.jar as it was, because only a test of the tool changed' {
        # Times before now, because Update-EvalTool writes the stamp at the time of the build.
        $root = New-Repository ([datetime]::UtcNow.AddHours(-2))
        Set-WriteTime $root 'tools\eval\src\test\kotlin\dev\kestrel\eval\CliTest.kt' ([datetime]::UtcNow.AddHours(-1))
        $jar = (Get-Item -LiteralPath (Join-Path $root 'tools\eval\build\install\eval\lib\eval.jar')).LastWriteTimeUtc
        Mock Invoke-Gradle { 0 }
        Test-EvalStale $root | Should -BeTrue

        Update-EvalTool $root | Should -BeTrue

        (Get-Item -LiteralPath (Join-Path $root 'tools\eval\build\install\eval\lib\eval.jar')).LastWriteTimeUtc | Should -Be $jar
        Test-EvalStale $root | Should -BeFalse
    }

    It 'is out of date after a build during which a source was saved' {
        $root = New-Repository ([datetime]::UtcNow.AddHours(-2))
        Set-WriteTime $root 'tools\eval\build.gradle.kts' ([datetime]::UtcNow.AddHours(-1))
        Mock Invoke-Gradle {
            if ($Arguments -contains ':tools:eval:installDist') {
                Start-Sleep -Milliseconds 50
                Set-WriteTime $root 'tools\eval\src\main\kotlin\dev\kestrel\eval\Main.kt' ([datetime]::UtcNow)
                Start-Sleep -Milliseconds 50
            }
            0
        }

        Update-EvalTool $root | Should -BeTrue

        Test-EvalStale $root | Should -BeTrue
    }

    It 'ends with exit code 2 when scripts\gradle.ps1 throws' {
        $root = New-Repository $built
        Set-WriteTime $root 'tools\eval\build.gradle.kts' $later
        Mock Invoke-Gradle { throw 'Set JAVA_HOME to a JDK 21.' }

        Invoke-EvalScript $root @('list') 3>$null | Should -Be 2
    }

    It 'gives the program every argument as it was, without cmd.exe, and returns its exit code' {
        # A PowerShell like this one stands in for java.exe: it writes the arguments of its own command line, which it
        # splits by the same rules, to a file.
        $probe = Join-Path $TestDrive 'arguments.ps1'
        $written = Join-Path $TestDrive 'arguments.txt'
        $given = @('50%', '%PATH%', 'a&b|c', 'x^y<z>', 'say "hi" \"', 'C:\with space\', '', 'R*?')
        [IO.File]::WriteAllText($probe, "[Environment]::GetCommandLineArgs() | Select-Object -Last $($given.Count) | ForEach-Object { [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(`$_)) } | Set-Content -LiteralPath '$written'; exit 3")

        Invoke-Java (Get-Process -Id $PID).Path (@('-NoProfile', '-NonInteractive', '-File', $probe) + $given) $TestDrive | Should -Be 3

        $arrived = @(Get-Content -LiteralPath $written | ForEach-Object { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($_)) })
        $arrived | Should -Be $given
    }
}
