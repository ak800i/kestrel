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

    It 'refuses an agent hook''s value outside its list before it reaches a device' {
        $hook = Join-Path $PSScriptRoot '..\hook.ps1'
        $ErrorActionPreference = 'Continue'

        $rule = (& pwsh -NoProfile -File $hook -Serial no-such-device agent-touch-source -Rule SOMETIMES 2>&1) -join "`n"
        $ruleExit = $LASTEXITCODE
        $tool = (& pwsh -NoProfile -File $hook -Serial no-such-device agent-data -Tool send_sms 2>&1) -join "`n"
        $toolExit = $LASTEXITCODE
        $confirming = (& pwsh -NoProfile -File $hook -Serial no-such-device agent-answer -Tag t1 -Answer confirm -Confirming send 2>&1) -join "`n"
        $confirmingExit = $LASTEXITCODE

        $ruleExit | Should -Not -Be 0
        $rule | Should -BeLike "*Cannot validate argument on parameter 'Rule'*"
        $toolExit | Should -Not -Be 0
        $tool | Should -BeLike "*Cannot validate argument on parameter 'Tool'*"
        $confirmingExit | Should -Not -Be 0
        $confirming | Should -BeLike "*Cannot validate argument on parameter 'Confirming'*"
    }

    It 'refuses clipboard -Clear on a phone before it reaches the device' {
        $hook = Join-Path $PSScriptRoot '..\hook.ps1'
        $ErrorActionPreference = 'Continue'

        $output = (& pwsh -NoProfile -File $hook -Serial no-such-phone clipboard -Clear 2>&1) -join "`n"
        $exit = $LASTEXITCODE

        $exit | Should -Not -Be 0
        $output | Should -BeLike '*clipboard -Clear was refused on no-such-phone: only the emulator kestrel-api37 may empty the clipboard.*'
    }

    It 'refuses agent-memory -Has without -Key before it reaches a device' {
        $hook = Join-Path $PSScriptRoot '..\hook.ps1'
        $ErrorActionPreference = 'Continue'

        $output = (& pwsh -NoProfile -File $hook -Serial no-such-device agent-memory -Has 2>&1) -join "`n"
        $exit = $LASTEXITCODE

        $exit | Should -Not -Be 0
        $output | Should -BeLike '*agent-memory -Has needs -Key; give the key to look for.*'
    }

    It 'accepts network-allowlist and agent-task -Digits, passes every argument check and prints the content call' {
        $hook = Join-Path $PSScriptRoot '..\hook.ps1'
        $ErrorActionPreference = 'Continue'
        $hookCall = 'content call --uri content://dev.kestrel.hooks --method '

        # -PrintCall stops after the argument checks, before the device check, which would start adb's server.
        $allowlist = (& pwsh -NoProfile -File $hook -Serial no-such-device network-allowlist -PrintCall 2>&1) -join "`n"
        $allowlistExit = $LASTEXITCODE
        $digits = (& pwsh -NoProfile -File $hook -Serial no-such-device agent-task -Result -Tag t1 -Digits 2025550143 -PrintCall 2>&1) -join "`n"
        $digitsExit = $LASTEXITCODE

        $allowlistExit | Should -Be 0
        $allowlist | Should -BeExactly "${hookCall}NETWORK_ALLOWLIST"
        $digitsExit | Should -Be 0
        $digits | Should -BeExactly "${hookCall}AGENT_TASK --extra 'result:b:true' --extra 'tag:s:t1' --extra 'digits:s:2025550143'"
    }

    It 'accepts the hooks of the conversations and their options, passes every argument check and prints the content call' {
        $hook = Join-Path $PSScriptRoot '..\hook.ps1'
        $ErrorActionPreference = 'Continue'
        $hookCall = 'content call --uri content://dev.kestrel.hooks --method '

        # -PrintCall stops after the argument checks, before the device check, which would start adb's server, so a check
        # that wrongly refuses a valid call fails the test.
        $calls = @(
            @{ Call = @('conversation-state'); Sent = 'CONVERSATION_STATE' },
            @{ Call = @('conversation-control', '-Do', 'limits', '-Limits', '50/5/10'); Sent = "CONVERSATION_CONTROL --extra 'do:s:limits' --extra 'limits:s:50/5/10'" },
            @{ Call = @('conversation-control', '-Do', 'delete-typed'); Sent = "CONVERSATION_CONTROL --extra 'do:s:delete-typed'" },
            @{ Call = @('voice-restore'); Sent = 'VOICE_RESTORE' },
            @{
                Call = @('conversation-type', '-Text', 'Find the number of Ana Test.', '-Tag', 't1', '-Live', '-Model', 'gpt-6-luna', '-Marker', 'maple heron')
                Sent = "CONVERSATION_TYPE --extra 'text:s:Find the number of Ana Test.' --extra 'model:s:gpt-6-luna' --extra 'tag:s:t1' --extra 'live:b:true' --extra 'marker:s:maple heron'"
            },
            @{ Call = @('goal-service-state'); Sent = 'GOAL_SERVICE_STATE' },
            @{ Call = @('goal-control', '-Do', 'tap', '-Action', 'stop'); Sent = "GOAL_CONTROL --extra 'action:s:stop' --extra 'do:s:tap'" },
            @{ Call = @('goal-control', '-Do', 'send-later', '-Action', 'cancel'); Sent = "GOAL_CONTROL --extra 'action:s:cancel' --extra 'do:s:send-later'" },
            @{ Call = @('goal-control', '-Do', 'refuse-off'); Sent = "GOAL_CONTROL --extra 'do:s:refuse-off'" },
            @{ Call = @('goal-control', '-Do', 'stop-typed'); Sent = "GOAL_CONTROL --extra 'do:s:stop-typed'" },
            @{ Call = @('voice-task', '-Seconds', '5', '-Reply', 'stand-in', '-HandOver'); Sent = "VOICE_TASK --extra 'seconds:i:5' --extra 'reply:s:stand-in' --extra 'handover:b:true'" },
            @{ Call = @('agent-answer', '-Tag', 't1', '-Answer', 'yes', '-Channel', 'text'); Sent = "AGENT_ANSWER --extra 'tag:s:t1' --extra 'answer:s:yes' --extra 'channel:s:text'" },
            @{ Call = @('agent-answer', '-Tag', 't1', '-Answer', 'confirm', '-Channel', 'card'); Sent = "AGENT_ANSWER --extra 'tag:s:t1' --extra 'answer:s:confirm' --extra 'channel:s:card'" }
        )
        foreach ($case in $calls) {
            $call = $case.Call
            $output = (& pwsh -NoProfile -File $hook -Serial no-such-device @call -PrintCall 2>&1) -join "`n"
            $exit = $LASTEXITCODE

            $exit | Should -Be 0 -Because ($call -join ' ')
            $output | Should -BeExactly "$hookCall$($case.Sent)"
        }
    }

    It 'refuses a value of the conversation hooks outside its list before it reaches a device' {
        $hook = Join-Path $PSScriptRoot '..\hook.ps1'
        $ErrorActionPreference = 'Continue'

        $do = (& pwsh -NoProfile -File $hook -Serial no-such-device conversation-control -Do restart 2>&1) -join "`n"
        $doExit = $LASTEXITCODE
        $limits = (& pwsh -NoProfile -File $hook -Serial no-such-device conversation-control -Do limits -Limits 50/5 2>&1) -join "`n"
        $limitsExit = $LASTEXITCODE
        $channel = (& pwsh -NoProfile -File $hook -Serial no-such-device agent-answer -Tag t1 -Answer yes -Channel screen 2>&1) -join "`n"
        $channelExit = $LASTEXITCODE

        $doExit | Should -Not -Be 0
        $do | Should -BeLike "*Cannot validate argument on parameter 'Do'*"
        $limitsExit | Should -Not -Be 0
        $limits | Should -BeLike "*Cannot validate argument on parameter 'Limits'*"
        $channelExit | Should -Not -Be 0
        $channel | Should -BeLike "*Cannot validate argument on parameter 'Channel'*"
    }

    It 'refuses a -Do of the other hook, -Limits without -Do limits, -Action without -Do tap or send-later, -HandOver without a stand-in round, conversation-type''s options without -Tag, conversation-type without -Text, and -Do or -Channel with a command that takes neither' {
        $hook = Join-Path $PSScriptRoot '..\hook.ps1'
        $ErrorActionPreference = 'Continue'

        $control = (& pwsh -NoProfile -File $hook -Serial no-such-device conversation-control -Do touch 2>&1) -join "`n"
        $goal = (& pwsh -NoProfile -File $hook -Serial no-such-device goal-control 2>&1) -join "`n"
        $limits = (& pwsh -NoProfile -File $hook -Serial no-such-device conversation-control -Do expire -Limits 50/5/10 2>&1) -join "`n"
        $action = (& pwsh -NoProfile -File $hook -Serial no-such-device goal-control -Do touch -Action stop 2>&1) -join "`n"
        $tap = (& pwsh -NoProfile -File $hook -Serial no-such-device goal-control -Do send-later 2>&1) -join "`n"
        $handOver = (& pwsh -NoProfile -File $hook -Serial no-such-device voice-task -Clear -HandOver 2>&1) -join "`n"
        $options = (& pwsh -NoProfile -File $hook -Serial no-such-device conversation-type -Text 'Find the number of Ana Test.' -Model gpt-6-luna 2>&1) -join "`n"
        $typed = (& pwsh -NoProfile -File $hook -Serial no-such-device conversation-type -Tag t1 2>&1) -join "`n"
        $doElsewhere = (& pwsh -NoProfile -File $hook -Serial no-such-device state -Do new 2>&1) -join "`n"
        $channelElsewhere = (& pwsh -NoProfile -File $hook -Serial no-such-device agent-task -Tag t1 -Channel card 2>&1) -join "`n"

        $control | Should -BeLike '*conversation-control needs -Do new, expire, expire-typed, limits, limits-check, restore-check or delete-typed.*'
        $goal | Should -BeLike '*goal-control needs -Do tap, touch, speak, focus, refuse, refuse-off, send-later or stop-typed.*'
        $limits | Should -BeLike '*-Limits needs conversation-control -Do limits.*'
        $action | Should -BeLike '*goal-control needs -Action with -Do tap or send-later, and only with them.*'
        $tap | Should -BeLike '*goal-control needs -Action with -Do tap or send-later, and only with them.*'
        $handOver | Should -BeLike '*-HandOver needs voice-task -Seconds and -Reply.*'
        $options | Should -BeLike '*conversation-type takes -Model, -Live and -Marker only with -Tag.*'
        $typed | Should -BeLike '*conversation-type needs -Text with the words to type.*'
        $doElsewhere | Should -BeLike '*-Do needs conversation-control or goal-control.*'
        $channelElsewhere | Should -BeLike '*-Channel needs agent-answer.*'
    }

    It 'accepts agent-live, which opens and closes the live session of sitting 2, passes every argument check and prints the content call' {
        $hook = Join-Path $PSScriptRoot '..\hook.ps1'
        $ErrorActionPreference = 'Continue'

        # -PrintCall ends the hook before its device check, which would start adb's server under this pipe and hang where
        # no server runs yet, as on CI.
        $output = (& pwsh -NoProfile -File $hook -Serial no-such-device agent-live -On false -PrintCall 2>&1) -join "`n"
        $exit = $LASTEXITCODE

        $exit | Should -Be 0
        $output | Should -BeExactly "content call --uri content://dev.kestrel.hooks --method AGENT_LIVE --extra 'on:b:false'"
    }

    # The whole-plan review of plan 7G, M4: agent-script's check, which reads a script and installs nothing.
    It 'sends agent-script -File -Check as the hook''s check and refuses -Check without -File, with -Clear or with another command' {
        $hook = Join-Path $PSScriptRoot '..\hook.ps1'
        $ErrorActionPreference = 'Continue'

        # -PrintCall ends the hook before its device check, which would start adb's server.
        $check = (& pwsh -NoProfile -File $hook -Serial no-such-device agent-script -File task.json -Check -PrintCall 2>&1) -join "`n"
        $checkExit = $LASTEXITCODE
        $noFile = (& pwsh -NoProfile -File $hook -Serial no-such-device agent-script -Check 2>&1) -join "`n"
        $noFileExit = $LASTEXITCODE
        $clear = (& pwsh -NoProfile -File $hook -Serial no-such-device agent-script -File task.json -Check -Clear 2>&1) -join "`n"
        $elsewhere = (& pwsh -NoProfile -File $hook -Serial no-such-device agent-state -Tag t1 -Check 2>&1) -join "`n"

        $checkExit | Should -Be 0
        $check | Should -BeExactly "content call --uri content://dev.kestrel.hooks --method AGENT_SCRIPT --extra 'file:s:task.json' --extra 'check:b:true'"
        $noFileExit | Should -Not -Be 0
        $noFile | Should -BeLike '*-Check needs agent-script -File, without -Clear.*'
        $clear | Should -BeLike '*-Check needs agent-script -File, without -Clear.*'
        $elsewhere | Should -BeLike '*-Check needs agent-script -File, without -Clear.*'
    }
}
