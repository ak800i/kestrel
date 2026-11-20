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

    It 'refuses voice-expect without a tag before it reaches a device' {
        $hook = Join-Path $PSScriptRoot '..\hook.ps1'
        $ErrorActionPreference = 'Continue'

        $output = (& pwsh -NoProfile -File $hook -Serial no-such-device voice-expect -Text 'Scroll down.' 2>&1) -join "`n"
        $exit = $LASTEXITCODE

        $exit | Should -Not -Be 0
        $output | Should -BeLike '*voice-expect needs -Tag; give each expectation a new tag.*'
    }

    It 'refuses a template of screen-has without a tag before it reaches a device' {
        $hook = Join-Path $PSScriptRoot '..\hook.ps1'
        $ErrorActionPreference = 'Continue'

        $output = (& pwsh -NoProfile -File $hook -Serial no-such-device screen-has -Package com.example -Template 'long press {label}' 2>&1) -join "`n"
        $exit = $LASTEXITCODE

        $exit | Should -Not -Be 0
        $output | Should -BeLike '*screen-has -Template needs -Tag; give the transcript a new tag.*'
    }

    It 'refuses clipboard without exactly one of -Save, -Restore and -Clear before it reaches a device' {
        $hook = Join-Path $PSScriptRoot '..\hook.ps1'
        $ErrorActionPreference = 'Continue'

        $both = (& pwsh -NoProfile -File $hook -Serial no-such-device clipboard -Save -Restore 2>&1) -join "`n"
        $bothExit = $LASTEXITCODE
        $none = (& pwsh -NoProfile -File $hook -Serial no-such-device clipboard 2>&1) -join "`n"
        $noneExit = $LASTEXITCODE

        $bothExit | Should -Not -Be 0
        $both | Should -BeLike '*clipboard needs exactly one of -Save, -Restore and -Clear.*'
        $noneExit | Should -Not -Be 0
        $none | Should -BeLike '*clipboard needs exactly one of -Save, -Restore and -Clear.*'
    }
}
