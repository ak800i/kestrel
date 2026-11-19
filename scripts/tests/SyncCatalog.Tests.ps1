# Tests of scripts\sync-catalog.ps1. git is fake-git.cmd and Gradle is a fake script in the test's folder, so the tests
# need no network and no build. scripts\test-scripts.ps1 runs them in Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

Describe 'sync-catalog.ps1' {
    BeforeAll {
        $sync = Join-Path $PSScriptRoot '..\sync-catalog.ps1'
        $fakeGit = Join-Path $PSScriptRoot 'fake-git.cmd'
        $commit = 'e6113549fe8800b4c736c5e8a131b47f2e015143'
        $repository = 'https://github.com/openclaw/openclaw.git'

        # Makes the fake git fail at the command -Fail, and forgets its calls.
        function Set-FakeGit([string]$Fail = '') {
            [IO.File]::WriteAllText($env:KESTREL_FAKE_GIT, (ConvertTo-Json -InputObject ([ordered]@{ fail = $Fail; calls = @() })))
        }

        function Get-GitCall {
            @(([IO.File]::ReadAllText($env:KESTREL_FAKE_GIT) | ConvertFrom-Json).calls)
        }

        # A fake scripts\gradle.ps1 for the folder -Root: it records its arguments, prints a count as the sync does, writes
        # the catalog and ends with -ExitCode.
        function New-FakeGradle([string]$Root, [int]$ExitCode = 0) {
            $path = Join-Path $TestDrive 'fake-gradle.ps1'
            $arguments = Join-Path $TestDrive 'gradle-arguments.txt'
            $catalog = Join-Path $Root 'catalog'
            Set-Content -LiteralPath $path -Value @(
                "[IO.File]::WriteAllText('$arguments', (`$args -join [char]10))"
                "if ($ExitCode -ne 0) { exit $ExitCode }"
                "'Providers: 72'"
                "New-Item -ItemType Directory -Force -Path '$catalog' | Out-Null"
                "[IO.File]::WriteAllText('$(Join-Path $catalog 'providers.json')', '{}')"
                'exit 0'
            )
            $path
        }

        function Get-GradleArgument {
            [IO.File]::ReadAllText((Join-Path $TestDrive 'gradle-arguments.txt')) -split "`n"
        }
    }

    BeforeEach {
        $root = Join-Path $TestDrive ([guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $root | Out-Null
        $lock = Join-Path $root 'catalog\openclaw.lock'
        $env:KESTREL_FAKE_GIT = Join-Path $TestDrive 'git.json'
        Set-FakeGit
        Remove-Item -LiteralPath (Join-Path $TestDrive 'gradle-arguments.txt') -ErrorAction SilentlyContinue
    }

    It 'makes the sparse checkout of the commit, runs the sync and writes the lock' {
        $gradle = New-FakeGradle -Root $root

        $output = @(& $sync -Commit $commit -Git $fakeGit -Gradle $gradle -Root $root 6>$null)

        Get-GitCall | Should -Be @(
            'init --quiet'
            "remote add origin $repository"
            'sparse-checkout set --no-cone extensions/*/openclaw.plugin.json'
            "fetch --depth 1 --filter=blob:none origin $commit"
            'checkout --force FETCH_HEAD'
        )
        Get-GradleArgument | Should -Be @(':tools:catalog-sync:syncCatalog', "-Pcheckout=$(Join-Path $root "build\openclaw\$commit")", "-Pcommit=$commit")
        $output | Should -Contain 'Providers: 72'
        [IO.File]::ReadAllBytes($lock) | Should -Be ([Text.Encoding]::ASCII.GetBytes("$commit`n"))
    }

    It 'reuses the checkout on a second run and reads the commit from the lock' {
        $gradle = New-FakeGradle -Root $root
        $null = & $sync -Commit $commit -Git $fakeGit -Gradle $gradle -Root $root 6>$null
        Set-FakeGit

        $null = & $sync -Git $fakeGit -Gradle $gradle -Root $root 6>$null

        (Get-GitCall)[0] | Should -Be "remote set-url origin $repository"
        (Get-GitCall).Count | Should -Be 4
        Get-GradleArgument | Should -Contain "-Pcommit=$commit"
    }

    It 'refuses a commit that is not a 40-character hash, before any git call' {
        $gradle = New-FakeGradle -Root $root

        { & $sync -Commit 'e6113549' -Git $fakeGit -Gradle $gradle -Root $root 6>$null } |
            Should -Throw -ExpectedMessage "The commit must be a 40-character hash, not 'e6113549'."
        @(Get-GitCall).Count | Should -Be 0
    }

    It 'stops when git fails, and neither syncs nor writes the lock' {
        $gradle = New-FakeGradle -Root $root
        Set-FakeGit -Fail 'fetch'

        { & $sync -Commit $commit -Git $fakeGit -Gradle $gradle -Root $root 6>$null } | Should -Throw -ExpectedMessage 'git fetch failed with exit code 128.'
        Test-Path -LiteralPath (Join-Path $TestDrive 'gradle-arguments.txt') | Should -BeFalse
        Test-Path -LiteralPath $lock | Should -BeFalse
    }

    It 'removes a repository that it could not set up, so that the next run starts again' {
        $gradle = New-FakeGradle -Root $root
        Set-FakeGit -Fail 'remote'

        { & $sync -Commit $commit -Git $fakeGit -Gradle $gradle -Root $root 6>$null } | Should -Throw -ExpectedMessage 'git remote failed with exit code 128.'
        Test-Path -LiteralPath (Join-Path $root "build\openclaw\$commit\.git") | Should -BeFalse
        Test-Path -LiteralPath $lock | Should -BeFalse
    }

    It 'stops when the sync fails, and does not write the lock' {
        $gradle = New-FakeGradle -Root $root -ExitCode 1

        { & $sync -Commit $commit -Git $fakeGit -Gradle $gradle -Root $root 6>$null } | Should -Throw -ExpectedMessage 'The sync failed with exit code 1*'
        Test-Path -LiteralPath $lock | Should -BeFalse
    }
}
