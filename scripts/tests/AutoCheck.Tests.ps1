# Tests of scripts\lib\AutoCheck.ps1, the check runner of the A71 sessions. The hooks are mocked, so no test needs a
# device. scripts\test-scripts.ps1 runs these tests in Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

BeforeAll {
    . "$PSScriptRoot\..\lib\Kestrel.ps1"
    . "$PSScriptRoot\..\lib\Session.ps1"
    . "$PSScriptRoot\..\lib\AutoCheck.ps1"
    # The session scripts define this; the tests mock it.
    function Invoke-Hook([string]$Command, [hashtable]$With = @{}) { }
    # The device of the session scripts, whose call state the runner reads after a countdown (mocked).
    $Serial = 'emulator-5554'

    # An answer of voice-state -Tag.
    function New-TaggedAnswer([string]$Tag, [string]$Role = 'command', [string]$Outcome = 'done', [string]$Phase = 'ended', [string]$Asking = 'false', [string]$Number = '7', [string]$Task = '0', [string]$Kind = 'Global') {
        "tag=$Tag role=$Role command=$Number task=$Task kind=$Kind outcome=$Outcome asking=$Asking phase=$Phase detail=none said=none shown=none read=other latest=7"
    }
}

Describe 'The check runner' {
    BeforeEach {
        Mock Start-Sleep { }
        # No call shows, unless a test's fake device starts one.
        Mock Invoke-SettingsCommand { 'mCallState=0' }
        $sent = [System.Collections.Generic.List[hashtable]]::new()
    }

    It 'sends the text with a new tag, waits for that tag''s command to end, and writes the test''s verdict' {
        $reads = @{ Count = 0 }
        Mock Invoke-Hook { $sent.Add($With); 'sent heard' } -ParameterFilter { $Command -eq 'voice' }
        Mock Invoke-Hook {
            $reads.Count++
            switch ($reads.Count) {
                1 { New-TaggedAnswer $With.Tag -Role pending -Outcome none -Phase pending }
                2 { New-TaggedAnswer $With.Tag -Outcome running -Phase running }
                # Kestrel records the outcome just after the phase ends.
                3 { New-TaggedAnswer $With.Tag -Outcome running -Phase ended }
                default { New-TaggedAnswer $With.Tag }
            }
        } -ParameterFilter { $Command -eq 'voice-state' }

        $result = Invoke-AutoCheck -Name 'go home' -Text 'go home' -Before { 'before' } -Verify {
            param($State, $Before)
            [pscustomobject]@{ Passed = $State['outcome'] -eq 'done' -and $Before -eq 'before'; Detail = 'home in front' }
        }

        $reads.Count | Should -Be 4
        $sent.Count | Should -Be 1
        $sent[0].Text | Should -BeExactly 'go home'
        $sent[0].Tag | Should -Match '^k[0-9a-f]{12}$'
        $result.Tag | Should -BeExactly $sent[0].Tag
        $result.Passed | Should -BeTrue
        $result.State['task'] | Should -BeExactly '0'
        $result.Line | Should -BeExactly 'go home [text]: role=command kind=Global outcome=done check=pass (home in front)'
    }

    It 'answers the command''s question with its own tag, and waits until the command ends' {
        $device = @{ Phase = 'question'; Asking = 'true' }
        Mock Invoke-Hook {
            $sent.Add($With)
            if ($sent.Count -eq 2) { $device.Phase = 'ended'; $device.Asking = 'false' }
            'sent heard'
        } -ParameterFilter { $Command -eq 'voice' }
        Mock Invoke-Hook { New-TaggedAnswer $With.Tag -Outcome $(if ($device.Phase -eq 'ended') { 'done' } else { 'running' }) -Phase $device.Phase -Asking $device.Asking } -ParameterFilter { $Command -eq 'voice-state' }

        $result = Invoke-AutoCheck -Name 'tap Delete, then 1' -Text 'tap Delete' -Answer '1' -Verify { param($State) $State['outcome'] -eq 'done' }

        $sent.Count | Should -Be 2
        $sent[1].Text | Should -BeExactly '1'
        $sent[1].Tag | Should -Not -Be $sent[0].Tag
        $result.Line | Should -BeExactly 'tap Delete, then 1 [text]: role=command kind=Global outcome=done check=pass'
    }

    It 'plays a clip that the phone expects, and records the match''s counts without words' {
        $played = [System.Collections.Generic.List[string]]::new()
        $results = @{ Count = 0 }
        Mock Invoke-Hook { $sent.Add($With); "expecting tag=$($With.Tag) seconds=$($With.Seconds)" } -ParameterFilter { $Command -eq 'voice-expect' -and -not $With.Result }
        Mock Invoke-Hook {
            $results.Count++
            if ($results.Count -eq 1) { return "waiting tag=$($With.Tag)" }
            "tag=$($With.Tag) matched=true words=2 errors=0 stray=1 split=0 speechEnded=900 closed=1600 transcribed=2100 run=true command=7 kind=Global outcome=done right=-"
        } -ParameterFilter { $Command -eq 'voice-expect' -and $With.Result }
        Mock Invoke-Hook { New-TaggedAnswer $With.Tag } -ParameterFilter { $Command -eq 'voice-state' }
        $clip = [pscustomobject]@{ Voice = 'kokoro'; Text = 'Go home.'; Path = 'c004.wav' }

        $result = Invoke-AutoCheck -Name 'go home' -Text 'go home' -Clip $clip -Play { param($Clip) $played.Add($Clip.Path) } -Verify { $true }

        $sent[0].Text | Should -BeExactly 'Go home.'
        $played | Should -Be @('c004.wav')
        $result.Heard['transcribed'] | Should -Be '2100'
        $result.Line | Should -BeExactly 'go home [kokoro]: role=command kind=Global outcome=done check=pass matched=true words=2 errors=0 stray=1 split=0'
    }

    It 'fails a clip that the phone did not match, without running the test' {
        Mock Invoke-Hook { 'expecting' } -ParameterFilter { $Command -eq 'voice-expect' -and -not $With.Result }
        Mock Invoke-Hook { "tag=$($With.Tag) matched=false words=2 errors=2 stray=0 split=0 speechEnded=0 closed=0 transcribed=0 run=true command=0 kind=none outcome=none right=-" } -ParameterFilter { $Command -eq 'voice-expect' -and $With.Result }
        $clip = [pscustomobject]@{ Voice = 'piper'; Text = 'Go home.'; Path = 'c004.wav' }

        $result = Invoke-AutoCheck -Name 'go home' -Text 'go home' -Clip $clip -Verify { throw 'not called' }

        $result.Passed | Should -BeFalse
        $result.Line | Should -BeExactly 'go home [piper]: role=- kind=- outcome=- check=fail (the phone did not match the clip) matched=false words=2 errors=2 stray=0 split=0'
        Should -Invoke Invoke-Hook -Times 0 -Exactly -ParameterFilter { $Command -eq 'voice-state' }
    }

    It 'responds at the countdown after the delay, sends through the caller''s sender, and answers a question left open with no' {
        $device = @{ Phase = 'countdown'; Asking = 'false'; Tapped = 0 }
        $senders = [System.Collections.Generic.List[string]]::new()
        Mock Invoke-Hook {
            $sent.Add($With)
            if ($With.Text -eq 'no') { $device.Phase = 'ended'; $device.Asking = 'false' }
            'sent heard'
        } -ParameterFilter { $Command -eq 'voice' }
        Mock Invoke-Hook { New-TaggedAnswer $With.Tag -Outcome $(if ($device.Phase -eq 'ended') { 'cancelled' } else { 'running' }) -Phase $device.Phase -Asking $device.Asking } -ParameterFilter { $Command -eq 'voice-state' }

        $result = Invoke-AutoCheck -Name 'call Ana Test, then a tap' -Text 'call Ana Test' -Send { param($Tag) $senders.Add($Tag) } -RespondAt countdown -RespondDelay 3 -Respond {
            # The tap ends the countdown, and the command asks a question that the check did not plan.
            $device.Tapped++
            $device.Phase = 'question'
            $device.Asking = 'true'
        } -Verify { $true }

        $senders.Count | Should -Be 1
        $device.Tapped | Should -Be 1
        Should -Invoke Start-Sleep -Times 1 -Exactly -ParameterFilter { $Milliseconds -eq 3000 }
        $sent.Count | Should -Be 1
        $sent[0].Text | Should -BeExactly 'no'
        $result.Line | Should -BeExactly 'call Ana Test, then a tap [text]: role=command kind=Global outcome=cancelled check=pass (it asked a question, which the check answered with no)'
    }
    It 'cancels a countdown that the check did not plan, or that a failed response left running, and fails the check' {
        $device = @{ Phase = 'countdown' }
        Mock Invoke-Hook {
            $sent.Add($With)
            if ($With.Text -eq 'cancel') { $device.Phase = 'ended' }
            'sent heard'
        } -ParameterFilter { $Command -eq 'voice' }
        Mock Invoke-Hook { New-TaggedAnswer $With.Tag -Outcome $(if ($device.Phase -eq 'ended') { 'cancelled' } else { 'running' }) -Phase $device.Phase } -ParameterFilter { $Command -eq 'voice-state' }

        $unplanned = Invoke-AutoCheck -Name 'open Ana' -Text 'open Ana' -Verify { $true }
        $device.Phase = 'countdown'
        $failedTap = Invoke-AutoCheck -Name 'call Ana Test, then a tap' -Text 'call Ana Test' -RespondAt countdown -Respond { throw 'The element has no bounds on the screen.' } -Verify { $true }

        @($sent | Where-Object { $_.Text -eq 'cancel' }).Count | Should -Be 2
        $unplanned.Passed | Should -BeFalse
        $unplanned.Line | Should -BeExactly 'open Ana [text]: role=command kind=Global outcome=cancelled check=fail (it counted down to a call, which the check cancelled)'
        $failedTap.Passed | Should -BeFalse
        $failedTap.Line | Should -Match 'the response failed \(The element has no bounds on the screen\.\); it counted down to a call, which the check cancelled'
    }

    It 'cancels a countdown that an answer started, and one that goes on after the planned response, and fails the check' {
        $device = @{ Phase = 'question'; Asking = 'true' }
        Mock Invoke-Hook {
            $sent.Add($With)
            # The answer picks a contact, and Kestrel counts down to the call; only "cancel" ends the countdown.
            if ($With.Text -eq 'Ana Test') { $device.Phase = 'countdown'; $device.Asking = 'false' }
            if ($With.Text -eq 'cancel') { $device.Phase = 'ended' }
            'sent heard'
        } -ParameterFilter { $Command -eq 'voice' }
        Mock Invoke-Hook { New-TaggedAnswer $With.Tag -Outcome $(if ($device.Phase -eq 'ended') { 'cancelled' } else { 'running' }) -Phase $device.Phase -Asking $device.Asking } -ParameterFilter { $Command -eq 'voice-state' }

        $answered = Invoke-AutoCheck -Name 'call Ana, then Ana Test' -Text 'call Ana' -Answer 'Ana Test' -Verify { $true }
        $device.Phase = 'countdown'
        $device.Asking = 'false'
        $taps = @{ Count = 0 }
        # A tap that returns without stopping the countdown, as when the touch watcher misses it.
        $missed = Invoke-AutoCheck -Name 'call Ana Test, then a tap' -Text 'call Ana Test' -RespondAt countdown -Respond { $taps.Count++ } -Verify { $true }

        @($sent | ForEach-Object { $_.Text }) | Should -Be @('call Ana', 'Ana Test', 'cancel', 'call Ana Test', 'cancel')
        $taps.Count | Should -Be 1
        $answered.Passed | Should -BeFalse
        $answered.Line | Should -BeExactly 'call Ana, then Ana Test [text]: role=command kind=Global outcome=cancelled check=fail (it counted down to a call, which the check cancelled)'
        $missed.Passed | Should -BeFalse
        $missed.Line | Should -BeExactly 'call Ana Test, then a tap [text]: role=command kind=Global outcome=cancelled check=fail (it counted down to a call, which the check cancelled)'
    }

    It 'sends no second cancel when the check''s own response cancels the planned countdown' {
        $device = @{ Phase = 'countdown' }
        Mock Invoke-Hook {
            $sent.Add($With)
            if ($With.Text -eq 'cancel') { $device.Phase = 'ended' }
            'sent heard'
        } -ParameterFilter { $Command -eq 'voice' }
        Mock Invoke-Hook { New-TaggedAnswer $With.Tag -Outcome $(if ($device.Phase -eq 'ended') { 'cancelled' } else { 'running' }) -Phase $device.Phase } -ParameterFilter { $Command -eq 'voice-state' }

        $result = Invoke-AutoCheck -Name 'call Ana Test, then cancel' -Text 'call Ana Test' -RespondAt countdown -Answer 'cancel' -Verify { param($State) $State['outcome'] -eq 'cancelled' }

        @($sent | Where-Object { $_.Text -eq 'cancel' }).Count | Should -Be 1
        $result.Line | Should -BeExactly 'call Ana Test, then cancel [text]: role=command kind=Global outcome=cancelled check=pass'
    }

    It 'sends cancel when a hook call fails during a countdown, and cancels a countdown before the caller''s wait' {
        $reads = @{ Count = 0 }
        Mock Invoke-Hook { $sent.Add($With); 'sent heard' } -ParameterFilter { $Command -eq 'voice' }
        Mock Invoke-Hook {
            $reads.Count++
            if ($reads.Count -eq 1) { return (New-TaggedAnswer $With.Tag -Outcome running -Phase countdown) }
            throw 'adb: device offline'
        } -ParameterFilter { $Command -eq 'voice-state' }

        # The check plans the countdown, and the read after its response fails.
        { Invoke-AutoCheck -Name 'call Ana Test, then a tap' -Text 'call Ana Test' -RespondAt countdown -Respond { } -Verify { $true } } | Should -Throw '*device offline*'

        @($sent | ForEach-Object { $_.Text }) | Should -Be @('call Ana Test', 'cancel')

        $sent.Clear()
        $device = @{ Phase = 'countdown' }
        Mock Invoke-Hook {
            $sent.Add($With)
            if ($With.Text -eq 'cancel') { $device.Phase = 'ended' }
            'sent heard'
        } -ParameterFilter { $Command -eq 'voice' }
        Mock Invoke-Hook { New-TaggedAnswer $With.Tag -Outcome $(if ($device.Phase -eq 'ended') { 'cancelled' } else { 'running' }) -Phase $device.Phase } -ParameterFilter { $Command -eq 'voice-state' }
        $waits = @{ Count = 0 }

        $result = Invoke-AutoCheck -Name 'call Ana' -Text 'Kestrel, call Ana.' -Wait { $waits.Count++ } -Verify { $true }

        $waits.Count | Should -Be 0
        @($sent | ForEach-Object { $_.Text }) | Should -Be @('Kestrel, call Ana.', 'cancel')
        $result.Line | Should -BeExactly 'call Ana [text]: role=command kind=Global outcome=cancelled check=fail (it counted down to a call, which the check cancelled)'
    }

    It 'ends a call that started when a countdown ran out before the runner''s cancel, and fails the check' {
        $device = @{ Phase = 'countdown'; Reads = 0; Call = $false }
        Mock Invoke-Hook { $sent.Add($With); 'sent heard' } -ParameterFilter { $Command -eq 'voice' }
        # The countdown runs out at the third read, as on a slow link: Kestrel presses Call before any cancel.
        Mock Invoke-Hook {
            $device.Reads++
            if ($device.Phase -eq 'countdown' -and $device.Reads -ge 3) {
                $device.Phase = 'ended'
                $device.Call = $true
            }
            New-TaggedAnswer $With.Tag -Kind Call -Outcome $(if ($device.Phase -eq 'ended') { 'done' } else { 'running' }) -Phase $device.Phase
        } -ParameterFilter { $Command -eq 'voice-state' }
        Mock Invoke-SettingsCommand {
            if ($Command -eq 'input keyevent KEYCODE_ENDCALL') {
                $device.Call = $false
                return
            }
            "mCallState=$(if ($device.Call) { 2 } else { 0 })"
        }
        $taps = @{ Count = 0 }

        # A tap that misses, so that the countdown goes on.
        $result = Invoke-AutoCheck -Name 'call Ana Test, then a tap' -Text 'call Ana Test' -RespondAt countdown -Respond { $taps.Count++ } -Verify { $true }

        $taps.Count | Should -Be 1
        $device.Call | Should -BeFalse
        Should -Invoke Invoke-SettingsCommand -Times 1 -Exactly -ParameterFilter { $Command -eq 'input keyevent KEYCODE_ENDCALL' }
        $result.Passed | Should -BeFalse
        $result.Line | Should -BeExactly 'call Ana Test, then a tap [text]: role=command kind=Call outcome=done check=fail (a call started, which the check ended)'
    }

    It 'cancels the countdown that follows a "no" that Kestrel dropped during the announcement, or a response that failed after it took effect' {
        # The states that the next reads give, one each, after which the last one stays; "no" changes nothing.
        $device = @{ Phase = 'question'; Queue = [System.Collections.Generic.Queue[string]]::new() }
        Mock Invoke-Hook {
            $sent.Add($With)
            # The answer's first read is stale, then Kestrel opens the phone app, announces the call and counts down.
            if ($With.Text -eq 'Ana Test') { foreach ($phase in 'question', 'running', 'running', 'countdown') { $device.Queue.Enqueue($phase) } }
            if ($With.Text -eq 'cancel' -and $device.Phase -eq 'countdown') { $device.Phase = 'ended' }
            'sent heard'
        } -ParameterFilter { $Command -eq 'voice' }
        Mock Invoke-Hook {
            if ($device.Queue.Count -gt 0) { $device.Phase = $device.Queue.Dequeue() }
            $asking = if ($device.Phase -eq 'question') { 'true' } else { 'false' }
            New-TaggedAnswer $With.Tag -Kind Call -Outcome $(if ($device.Phase -eq 'ended') { 'cancelled' } else { 'running' }) -Phase $device.Phase -Asking $asking
        } -ParameterFilter { $Command -eq 'voice-state' }

        $dropped = Invoke-AutoCheck -Name 'call Ana, then Ana Test' -Text 'call Ana' -Answer 'Ana Test' -Verify { $true }

        @($sent | ForEach-Object { $_.Text }) | Should -Be @('call Ana', 'Ana Test', 'no', 'cancel')
        $dropped.Passed | Should -BeFalse
        $dropped.Line | Should -BeExactly 'call Ana, then Ana Test [text]: role=command kind=Call outcome=cancelled check=fail (it counted down to a call, which the check cancelled; it asked a question, which the check answered with no)'

        $sent.Clear()
        $device.Phase = 'question'
        # A tap that picked a contact, and then failed.
        $failedTap = Invoke-AutoCheck -Name 'call Ana, then a tap' -Text 'call Ana' -Respond {
            foreach ($phase in 'running', 'countdown') { $device.Queue.Enqueue($phase) }
            throw 'adb: closed'
        } -Verify { $true }

        @($sent | ForEach-Object { $_.Text }) | Should -Be @('call Ana', 'cancel')
        $failedTap.Line | Should -BeExactly 'call Ana, then a tap [text]: role=command kind=Call outcome=cancelled check=fail (the response failed (adb: closed); it counted down to a call, which the check cancelled)'
    }

    It 'fails a check whose command did not settle in time, and refuses the state of another tag' {
        Mock Invoke-Hook { 'sent heard' } -ParameterFilter { $Command -eq 'voice' }
        Mock Invoke-Hook { New-TaggedAnswer $With.Tag -Outcome running -Phase running } -ParameterFilter { $Command -eq 'voice-state' }

        $result = Invoke-AutoCheck -Name 'go home' -Text 'go home' -TimeoutSeconds 0.5 -Verify { $true }

        $result.Passed | Should -BeFalse
        $result.Line | Should -BeExactly 'go home [text]: role=command kind=Global outcome=running check=fail (the command did not settle in 0.5 s)'
        Mock Invoke-Hook { New-TaggedAnswer 'kother' } -ParameterFilter { $Command -eq 'voice-state' }
        { Invoke-AutoCheck -Name 'go home' -Text 'go home' -Verify { $true } } | Should -Throw '*answered for another tag*'
    }

    It 'waits with the caller''s wait instead of the command''s end, such as a task''s result, for at most 10 minutes' {
        $polls = @{ Count = 0 }
        $waits = [System.Collections.Generic.List[double]]::new()
        Mock Invoke-Hook { $sent.Add($With); 'sent heard' } -ParameterFilter { $Command -eq 'voice' }
        Mock Invoke-Hook { New-TaggedAnswer $With.Tag -Role task -Number 0 -Task 3 -Kind none -Outcome none -Phase ended } -ParameterFilter { $Command -eq 'voice-state' }
        # A fake of sub-project 5's hook agent-task, which does not exist yet.
        Mock Invoke-Hook {
            $polls.Count++
            if ($polls.Count -lt 3) { "tag=$($With.Tag) state=running" } else { "tag=$($With.Tag) state=done" }
        } -ParameterFilter { $Command -eq 'agent-task' -and $With.Result }
        $wait = {
            param($Tag, $Seconds)
            $waits.Add($Seconds)
            do { $answer = ConvertFrom-HookPairs (Invoke-Hook agent-task @{ Result = $true; Tag = $Tag }) } while ($answer['state'] -eq 'running')
            $answer
        }

        $result = Invoke-AutoCheck -Name 'open the settings' -Text 'Kestrel, open the settings.' -Wait $wait -Verify {
            param($State, $Before, $Waited)
            $null -eq $Before -and $State['role'] -eq 'task' -and $State['task'] -eq '3' -and $Waited['state'] -eq 'done'
        }

        $polls.Count | Should -Be 3
        $waits | Should -Be @(600)
        $sent.Count | Should -Be 1
        $result.Waited['state'] | Should -BeExactly 'done'
        $result.Line | Should -BeExactly 'open the settings [text]: role=task kind=none outcome=none check=pass'
        # Once before the wait, until Kestrel has handled the transcript, and once after it.
        Should -Invoke Invoke-Hook -Times 2 -Exactly -ParameterFilter { $Command -eq 'voice-state' }
        { Invoke-AutoCheck -Name 'x' -Text 'x' -Wait $wait -WaitSeconds 601 -Verify { $true } } | Should -Throw
        { Invoke-AutoCheck -Name 'x' -Text 'x' -Wait $wait -Answer '1' -Verify { $true } } | Should -Throw '*cannot answer or respond*'
    }

    It 'never sends "yes, call", in any form' {
        Mock Invoke-Hook { }

        { Invoke-AutoCheck -Name 'x' -Text 'Yes, call.' -Verify { $true } } | Should -Throw '*process rule 10*'
        { Invoke-AutoCheck -Name 'x' -Text 'tap call' -Answer 'yes call Kestrel' -Verify { $true } } | Should -Throw '*process rule 10*'
        Test-YesCall 'no' | Should -BeFalse
        Should -Invoke Invoke-Hook -Times 0 -Exactly
    }
}
