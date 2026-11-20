# Tests of scripts\lib\AutoCheck.ps1, the check runner of the A71 sessions. The hooks are mocked, so no test needs a
# device. scripts\test-scripts.ps1 runs these tests in Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

BeforeAll {
    . "$PSScriptRoot\..\lib\Session.ps1"
    . "$PSScriptRoot\..\lib\AutoCheck.ps1"
    # The session scripts define this; the tests mock it.
    function Invoke-Hook([string]$Command, [hashtable]$With = @{}) { }

    # An answer of voice-state -Tag.
    function New-TaggedAnswer([string]$Tag, [string]$Role = 'command', [string]$Outcome = 'done', [string]$Phase = 'ended', [string]$Asking = 'false', [string]$Number = '7', [string]$Task = '0', [string]$Kind = 'Global') {
        "tag=$Tag role=$Role command=$Number task=$Task kind=$Kind outcome=$Outcome asking=$Asking phase=$Phase detail=none said=none shown=none read=other latest=7"
    }
}

Describe 'The check runner' {
    BeforeEach {
        Mock Start-Sleep { }
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
        Should -Invoke Invoke-Hook -Times 1 -Exactly -ParameterFilter { $Command -eq 'voice-state' }
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
