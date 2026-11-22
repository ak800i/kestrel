# Tests of scripts\task-check.ps1's switch -Live (plan 5C), which only a sitting of scripts\a71-agent.ps1 may use. The
# script needs PowerShell 7.3, so each test starts it with pwsh, also when the tests run in Windows PowerShell 5.1.

Describe 'task-check.ps1 -Live' {
    It 'refuses -Live outside a sitting, and -Live with a script, before it reaches a device' {
        $taskCheck = Join-Path $PSScriptRoot '..\task-check.ps1'
        # Windows PowerShell 5.1 stops at a native program's error output under 'Stop', which the tests' runner sets.
        $ErrorActionPreference = 'Continue'
        $saved = $env:KESTREL_AGENT_SESSION
        try {
            Remove-Item -Path Env:KESTREL_AGENT_SESSION -ErrorAction SilentlyContinue
            $outside = (& pwsh -NoProfile -File $taskCheck -Serial no-such-device -Task hotspot -Live 2>&1) -join "`n"
            $outsideExit = $LASTEXITCODE
            $env:KESTREL_AGENT_SESSION = 'a test'
            $both = (& pwsh -NoProfile -File $taskCheck -Serial no-such-device -Task hotspot -Live -Script stand-in.json 2>&1) -join "`n"
            $bothExit = $LASTEXITCODE
        } finally {
            if ($null -eq $saved) { Remove-Item -Path Env:KESTREL_AGENT_SESSION -ErrorAction SilentlyContinue } else { $env:KESTREL_AGENT_SESSION = $saved }
        }

        $outsideExit | Should -Not -Be 0
        $outside | Should -BeLike '*task-check.ps1 -Live runs only inside a sitting of scripts\a71-agent.ps1.*'
        $bothExit | Should -Not -Be 0
        $both | Should -BeLike '*task-check.ps1 -Live calls the owner''s provider and takes no -Script.*'
    }
}
