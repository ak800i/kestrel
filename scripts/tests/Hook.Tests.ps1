# Tests of scripts\hook.ps1's own checks of its parameters, which run before the script reaches adb or a device. The
# script needs PowerShell 7.3, so each test starts it with pwsh, also when the tests run in Windows PowerShell 5.1.

Describe 'hook.ps1' {
    It 'refuses a negative -Boost or -Threshold, which it would otherwise drop as not given' {
        $hook = Join-Path $PSScriptRoot '..\hook.ps1'
        # Windows PowerShell 5.1 stops at a native program's error output under 'Stop', which the tests' runner sets.
        $ErrorActionPreference = 'Continue'

        $boost = (& pwsh -NoProfile -File $hook -Serial no-such-device voice-spotter -Boost -1 2>&1) -join "`n"
        $boostExit = $LASTEXITCODE
        $threshold = (& pwsh -NoProfile -File $hook -Serial no-such-device voice-spotter -Threshold -0.1 2>&1) -join "`n"
        $thresholdExit = $LASTEXITCODE

        $boostExit | Should -Not -Be 0
        $boost | Should -BeLike '*-Boost -1 is negative; give a boost from 0.5 to 10.*'
        $thresholdExit | Should -Not -Be 0
        $threshold | Should -BeLike '*-Threshold -0.1 is negative; give a threshold from 0.01 to 0.5.*'
    }

    It 'refuses a tag with other characters than letters, digits, - and _ before it reaches a device' {
        $hook = Join-Path $PSScriptRoot '..\hook.ps1'
        $ErrorActionPreference = 'Continue'

        $output = (& pwsh -NoProfile -File $hook -Serial no-such-device voice -Send heard -Text 'Scroll down.' -Tag 'bad tag!' 2>&1) -join "`n"
        $exit = $LASTEXITCODE

        $exit | Should -Not -Be 0
        $output | Should -BeLike "*Cannot validate argument on parameter 'Tag'*"
    }
}
