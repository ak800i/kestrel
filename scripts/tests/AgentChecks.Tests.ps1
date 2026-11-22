# Tests of scripts\lib\AgentChecks.ps1, the agent's speech checks (design D28 of sub-project 5), with the hooks, the
# device's shell and the check runner mocked. scripts\test-scripts.ps1 runs these tests in Windows PowerShell 5.1 and in
# PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

BeforeAll {
    . "$PSScriptRoot\..\lib\Kestrel.ps1"
    . "$PSScriptRoot\..\lib\Session.ps1"
    . "$PSScriptRoot\..\lib\AutoCheck.ps1"
    . "$PSScriptRoot\..\lib\Corpus.ps1"
    . "$PSScriptRoot\..\lib\AgentChecks.ps1"
    # The library calls the hooks and the device through these, which the tests mock.
    function Invoke-Hook([string]$Command, [hashtable]$With = @{}) { }
    function Invoke-SettingsCommand([string]$Serial, [string]$Command) { }
    $Serial = 'emulator-5554'
}

Describe 'The agent''s speech checks' {
    BeforeEach {
        Mock Start-Sleep { }
    }

    It 'lists the clips of the check corpus and of the command corpus that exist' {
        $root = "$PSScriptRoot\..\..\corpus"
        $ids = @(Read-Corpus -Path "$root\commands.txt") + @(Read-Corpus -Path "$root\checks.txt") | ForEach-Object { $_.Id }
        $checks = @(Get-AgentSpeechChecks)

        $checks.Count | Should -Be 15
        @($checks | Where-Object { $_.Clip -notin $ids }).Clip | Should -BeNullOrEmpty
        @($checks | Where-Object { $_.Kind -eq 'task' }).Count | Should -Be 6
        @($checks | Where-Object { $_.Kind -eq 'answer' -and $_.Script -eq 'confirm' }).End | Should -Be @('FAILED_BY_MODEL', 'FAILED_BY_MODEL', 'DECLINED', 'CANCELLED')
        @($checks | Where-Object { $_.Kind -eq 'phrase' }).Read | Should -Be @('continue', 'deletehistory', 'remember', 'saveroutine')
    }

    It 'writes the scripted model''s replies as a JSON array through run-as, and installs them' {
        Mock Invoke-SettingsCommand { }
        Mock Invoke-Hook { 'script=installed replies=2' } -ParameterFilter { $Command -eq 'agent-script' }

        Install-AgentScript -Name 'speech-check.json' -Replies (Get-SpeechScript confirm)

        Should -Invoke Invoke-Hook -Times 1 -Exactly -ParameterFilter { $Command -eq 'agent-script' -and $With.File -eq 'speech-check.json' }
        Should -Invoke Invoke-SettingsCommand -Times 1 -Exactly -ParameterFilter {
            if ($Command -notmatch "^echo ([A-Za-z0-9+/=]+) \| base64 -d \| run-as dev\.kestrel sh -c 'mkdir -p files/agent-scripts && cat > files/agent-scripts/speech-check\.json'$") { return $false }
            # Windows PowerShell 5.1 gives the array as one object, which the assignment unpacks.
            $replies = ConvertFrom-Json -InputObject ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Matches[1])))
            $replies = @($replies)
            # The views fixture's button shows its text in capitals, and the scripted model compares labels exactly; a deleting
            # tap there would be handed over, because the fixture's screen has no title that names what it deletes.
            $replies.Count -eq 2 -and $replies[0].tool -eq 'tap' -and $replies[0].target.label -ceq 'REBUILD' -and $replies[0].arguments.category -eq 'typing_screen_text' -and $replies[1].tool -eq 'fail'
        }
        Mock Invoke-Hook { 'script=installed replies=1' } -ParameterFilter { $Command -eq 'agent-script' }
        { Install-AgentScript -Name 'speech-check.json' -Replies (Get-SpeechScript confirm) } | Should -Throw 'agent-script did not install speech-check.json.'
        @(Get-SpeechScript wait).Count | Should -Be 6
        @(Get-SpeechScript end).Count | Should -Be 1
    }

    It 'waits for the task''s end by its tag, also when the tag has no task yet' {
        $answers = [System.Collections.Generic.Queue[string]]::new([string[]]@('error', 'state=running', 'state=done end=CANCELLED steps=1 confirmed=0'))
        Mock Invoke-Hook {
            $next = $answers.Dequeue()
            if ($next -eq 'error') { throw 'error: no task has the tag k1' }
            $next
        } -ParameterFilter { $Command -eq 'agent-task' -and $With.Tag -eq 'k1' -and $With.Result }

        $result = Wait-AgentTask -Tag 'k1' -Seconds 30

        $result['end'] | Should -Be 'CANCELLED'
        Should -Invoke Invoke-Hook -Times 3 -Exactly
        { ConvertFrom-AgentTaskResult 'tag=k1 role=task' } | Should -Throw 'Not an answer of agent-task -Result*'
    }

    It 'judges a task phrase by the task that it started, an answer by the task''s end, and a phrase by Kestrel''s reading' {
        $checks = @(Get-AgentSpeechChecks)
        $task = $checks[0]
        $confirm = $checks | Where-Object { $_.Name -eq 'answer: confirm' }
        $no = $checks | Where-Object { $_.Name -eq 'answer: no' }
        $continue = $checks | Where-Object { $_.Name -eq 'Kestrel''s phrase: continue' }
        $done = [ordered]@{ state = 'done'; end = 'FAILED_BY_MODEL'; confirmed = '1' }

        (Get-AgentSpeechVerdict $task ([ordered]@{ role = 'task' }) $done).Passed | Should -BeTrue
        (Get-AgentSpeechVerdict $task ([ordered]@{ role = 'command' }) $null).Detail | Should -Be 'role=command end=none'
        (Get-AgentSpeechVerdict $confirm ([ordered]@{ role = 'none' }) $done).Passed | Should -BeTrue
        (Get-AgentSpeechVerdict $no ([ordered]@{ role = 'none' }) $done).Passed | Should -BeFalse
        (Get-AgentSpeechVerdict $no ([ordered]@{ role = 'none' }) ([ordered]@{ state = 'done'; end = 'DECLINED'; confirmed = '0' })).Passed | Should -BeTrue
        (Get-AgentSpeechVerdict $continue ([ordered]@{ read = 'continue' }) $null).Passed | Should -BeTrue
        (Get-AgentSpeechVerdict $continue ([ordered]@{ read = 'task' }) $null).Detail | Should -Be 'read=task (expected continue)'
    }

    It 'starts an answer check''s task by text before the clip, plays the clip, and cancels the task when it still asks' {
        $check = Get-AgentSpeechChecks | Where-Object { $_.Name -eq 'answer: cancel' }
        $clip = [pscustomobject]@{ Voice = 'kokoro'; Text = 'Cancel.'; Path = 'C:\corpus\generated\kokoro\c046.wav' }
        Mock Install-AgentScript { }
        Mock Invoke-SettingsCommand { }
        Mock Invoke-Hook { 'phase=running asking=confirm work=running-task handOver=none' } -ParameterFilter { $Command -eq 'agent-state' }
        Mock Invoke-Hook { 'sent heard' } -ParameterFilter { $Command -eq 'voice' }
        Mock Invoke-Hook { 'answered' } -ParameterFilter { $Command -eq 'agent-answer' }
        Mock Invoke-Hook { 'state=done end=CANCELLED confirmed=0' } -ParameterFilter { $Command -eq 'agent-task' }
        # The runner runs the check's own blocks as the real one does, and the phone did not match the clip.
        Mock Invoke-AutoCheck {
            $context = & $Before
            $waited = & $Wait 'k9' 60
            [pscustomobject]@{ Context = $context; Waited = $waited; Clip = $Clip; Line = "$Name [kokoro]: check=fail" }
        }

        $result = Invoke-AgentSpeechCheck -Check $check -Clip $clip -Play { }

        $result.Clip.Path | Should -Be 'C:\corpus\generated\kokoro\c046.wav'
        $result.Waited['end'] | Should -Be 'CANCELLED'
        Should -Invoke Install-AgentScript -Times 1 -Exactly -ParameterFilter { $Name -eq 'speech-check.json' -and @($Replies).Count -eq 2 }
        Should -Invoke Invoke-SettingsCommand -Times 1 -Exactly -ParameterFilter { $Command -eq 'am start -n dev.kestrel.testapp/.ViewsActivity' }
        Should -Invoke Invoke-Hook -Times 1 -Exactly -ParameterFilter { $Command -eq 'voice' -and $With.Named -and $With.Tag -eq $result.Context -and $With.Text -like 'Kestrel, *' }
        Should -Invoke Invoke-Hook -Times 1 -Exactly -ParameterFilter { $Command -eq 'agent-answer' -and $With.Tag -eq $result.Context -and $With.Answer -eq 'cancel' }
    }

    It 'cancels a task check''s own task when it still asks after the wait (task-5c-7-review M1)' {
        $check = (Get-AgentSpeechChecks)[0]
        Mock Install-AgentScript { }
        Mock Invoke-Hook { 'phase=running asking=free work=running-task handOver=none' } -ParameterFilter { $Command -eq 'agent-state' -and $With.Tag -eq 'k9' }
        Mock Invoke-Hook { 'answered' } -ParameterFilter { $Command -eq 'agent-answer' }
        # The task still asks when the check's 60 s have passed.
        Mock Wait-AgentTask { [ordered]@{ state = 'running' } }
        Mock Invoke-AutoCheck { [pscustomobject]@{ Context = (& $Before); Waited = (& $Wait 'k9' 60); Line = "$Name [kokoro]: check=fail" } }

        Invoke-AgentSpeechCheck -Check $check -Clip ([pscustomobject]@{ Voice = 'kokoro'; Text = 'x'; Path = 'x.wav' }) -Play { } | Out-Null

        Should -Invoke Invoke-Hook -Times 1 -Exactly -ParameterFilter { $Command -eq 'agent-answer' -and $With.Tag -eq 'k9' -and $With.Answer -eq 'cancel' }
    }

    It 'waits until Kestrel has asked the remember question before it says "no", and says it again while the task runs (task-5c-7-review M2)' {
        $check = Get-AgentSpeechChecks | Where-Object { $_.Name -eq 'Kestrel''s phrase: remember' }
        $events = [System.Collections.Generic.List[string]]::new()
        $states = [System.Collections.Generic.Queue[string]]::new([string[]]@(
                'mode=listening session=open work=running_task speaking=false', 'mode=listening session=open work=running_task speaking=true',
                'mode=listening session=open work=running_task speaking=false', 'mode=listening session=open work=running_task speaking=false',
                'mode=listening session=open work=idle speaking=false'))
        Mock Invoke-Hook {
            $next = if ($states.Count -gt 1) { $states.Dequeue() } else { $states.Peek() }
            $events.Add("state $($next -replace '^.*work=(\S+) speaking=(\S+)$', '$1 $2')")
            $next
        } -ParameterFilter { $Command -eq 'voice-state' }
        Mock Invoke-Hook { $events.Add("said $($With.Text)"); 'sent heard' } -ParameterFilter { $Command -eq 'voice' }
        Mock Invoke-AutoCheck { [pscustomobject]@{ Context = (& $Before); Waited = (& $Wait 'k9' 60); Line = "$Name [piper]: check=pass" } }

        Invoke-AgentSpeechCheck -Check $check -Clip ([pscustomobject]@{ Voice = 'piper'; Text = 'Kestrel, remember that the gate code is blue.'; Path = 'x.wav' }) -Play { } | Out-Null

        $first = $events.IndexOf('said no')
        $first | Should -BeGreaterThan $events.IndexOf('state running_task true')
        $events.IndexOf('state running_task true') | Should -BeGreaterOrEqual 0
        @($events | Where-Object { $_ -eq 'said no' }).Count | Should -Be 2
        $events[-1] | Should -Be 'state idle false'
    }

    It 'sends "no" after a phrase whose question must not stay open, and starts no task for it' {
        $check = Get-AgentSpeechChecks | Where-Object { $_.Name -eq 'Kestrel''s phrase: delete history' }
        Mock Install-AgentScript { }
        Mock Invoke-Hook { 'sent heard' } -ParameterFilter { $Command -eq 'voice' }
        Mock Invoke-AutoCheck { [pscustomobject]@{ Context = (& $Before); Waited = (& $Wait 'k9' 60); Line = "$Name [piper]: check=pass" } }

        $result = Invoke-AgentSpeechCheck -Check $check -Clip ([pscustomobject]@{ Voice = 'piper'; Text = 'Kestrel, delete history.'; Path = 'x.wav' }) -Play { }

        $result.Context | Should -BeNullOrEmpty
        $result.Waited | Should -BeNullOrEmpty
        Should -Invoke Install-AgentScript -Times 0 -Exactly
        Should -Invoke Invoke-Hook -Times 1 -Exactly -ParameterFilter { $Command -eq 'voice' -and $With.Text -eq 'no' -and $With.Tag -match '^k[0-9a-f]{12}$' }
    }
}
