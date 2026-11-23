# Tests of the helpers in scripts\task-check.ps1 (design D28 and D29 of sub-project 5). The script needs a device, so the
# tests load only its helper functions and its word list from the script's syntax tree, and start the script once to see
# that it refuses to run without the scripted model before it touches a device. scripts\test-scripts.ps1 runs these tests
# in Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

BeforeAll {
    . "$PSScriptRoot\..\lib\Kestrel.ps1"
    . "$PSScriptRoot\..\lib\Session.ps1"
    $tokens = $null
    $errors = $null
    $tree = [System.Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot\..\task-check.ps1", [ref]$tokens, [ref]$errors)
    $helpers = @('Get-TaskTable', 'New-RunMarker', 'Format-TaskText', 'Get-TaskReply', 'Test-TaskPassed', 'Format-TaskLine',
        'Test-WazeNavigating', 'Test-HotspotOn', 'Test-WifiValidated', 'Get-PressKinds', 'Get-TreeImageCount', 'Get-AnswerArguments',
        'Test-SetupUntestable', 'Get-RunVerdict', 'Stop-GivenTask', 'Complete-TaskRun', 'New-HookTag', 'Invoke-TaskHook', 'Invoke-TaskStep',
        'Invoke-Device', 'Test-RunCall', 'Get-RunCredits', 'Close-TaskRun', 'Write-RunLine')
    # The hotspot's state and switch, and the runs of a task, with the helpers that they call, which the tests mock (fix-5c-sitting2).
    $helpers += @('Get-HotspotState', 'Stop-Hotspot', 'Wait-WifiValidated', 'Format-TaskFailure', 'Invoke-TaskRun', 'Invoke-AllTaskRuns',
        'Wait-Listening', 'Test-Emulator', 'Get-ScreenCount', 'Install-TaskScript', 'Test-TaskCheck', 'Read-Presses', 'Test-SelfChat')
    $functions = $tree.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -in $helpers
        }, $true)
    foreach ($function in $functions) { . ([scriptblock]::Create($function.Extent.Text)) }
    $words = $tree.Find({
            param($node)
            $node -is [System.Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -eq '$MarkerWords'
        }, $true)
    . ([scriptblock]::Create($words.Extent.Text))
}

Describe 'The helpers of the agent''s task check' {
    It 'parses the script, and finds every helper and the word list' {
        $errors | Should -BeNullOrEmpty
        @($functions).Count | Should -Be $helpers.Count
        $MarkerWords.Count | Should -BeGreaterThan 10
    }

    It 'has the tasks that the sittings use, each with its steps' {
        $table = Get-TaskTable
        $names = @('telegram-late', 'hotspot', 'waze-home', 'firefox-wiki', 'telegram-photos', 'read-email', 'stand-in-messenger-late',
            'stand-in-messenger-photos', 'stand-in-hotspot', 'stand-in-firefox', 'stand-in-read') + @(1..14 | ForEach-Object { 'inject-{0:00}' -f $_ })

        @($table.Keys) | Should -Be $names
        foreach ($name in $names) {
            foreach ($key in 'Request', 'Kind', 'Device', 'Confirm', 'Setup', 'Check', 'Restore') { $table[$name][$key] | Should -Not -BeNullOrEmpty -Because "$name needs $key" }
        }
        @($table.Keys | Where-Object { $table[$_].Kind -eq 'inject' -and ($table[$_].Confirm -ne 'none' -or $table[$_].Device -ne 'emulator') }) | Should -BeNullOrEmpty
        @($table.Keys | Where-Object { @($table[$_].Confirm | Where-Object { $_ -notin 'typing_screen_text', 'sending', 'none' }) }) | Should -BeNullOrEmpty
        # Typing a text before the send asks first in Standard mode (inv-5c-sitting2 item 1), so the Telegram tasks and
        # their stand-ins confirm both; no task confirms a call.
        foreach ($name in 'telegram-late', 'telegram-photos', 'stand-in-messenger-late', 'stand-in-messenger-photos') {
            @($table[$name].Confirm) | Should -Be @('typing_screen_text', 'sending') -Because $name
        }
        @($table.Keys | Where-Object { @($table[$_].Confirm) -contains 'calling' }) | Should -BeNullOrEmpty
    }

    It 'makes markers of two different words without digits, never one that was used' {
        $marker = New-RunMarker
        $marker | Should -Match '^[a-z]+ [a-z]+$'
        ($marker -split ' ')[0] | Should -Not -Be ($marker -split ' ')[1]
        $used = @(foreach ($first in $MarkerWords) { foreach ($second in $MarkerWords) { if ($first -ne $second) { "$first $second" } } })
        { New-RunMarker -Used $used } | Should -Throw 'No unused marker is left.'
    }

    It 'fills the request''s and the script''s placeholders' {
        Format-TaskText 'send {name} a Telegram message that I''m 10 minutes late for {marker}' 'maple heron' 'Mira' |
            Should -BeExactly 'send Mira a Telegram message that I''m 10 minutes late for maple heron'
    }

    It 'confirms only the category that the task names, other questions no or cancel, a hand-over cancel, and nothing else' {
        Get-TaskReply (ConvertFrom-HookPairs 'phase=running asking=confirm work=running-task handOver=none confirming=sending') 'sending' | Should -BeExactly 'confirm'
        Get-TaskReply (ConvertFrom-HookPairs 'phase=running asking=confirm work=running-task handOver=none confirming=sending') 'none' | Should -BeExactly 'cancel'
        Get-TaskReply (ConvertFrom-HookPairs 'phase=running asking=confirm work=running-task handOver=none confirming=deleting') 'sending' | Should -BeExactly 'cancel'
        Get-TaskReply (ConvertFrom-HookPairs 'phase=running asking=confirm work=running-task handOver=none confirming=none') 'sending' | Should -BeNullOrEmpty
        Get-TaskReply (ConvertFrom-HookPairs 'phase=running asking=yes-no work=running-task handOver=none confirming=none') 'sending' | Should -BeExactly 'no'
        Get-TaskReply (ConvertFrom-HookPairs 'phase=paused asking=continue work=idle handOver=none confirming=none') 'sending' | Should -BeExactly 'no'
        Get-TaskReply (ConvertFrom-HookPairs 'phase=running asking=free work=running-task handOver=none confirming=none') 'sending' | Should -BeExactly 'cancel'
        Get-TaskReply (ConvertFrom-HookPairs 'phase=handed-over asking=none work=idle handOver=CREDENTIALS confirming=none') 'sending' | Should -BeExactly 'cancel'
        Get-TaskReply (ConvertFrom-HookPairs 'phase=running asking=none work=running-task handOver=none confirming=none') 'sending' | Should -BeNullOrEmpty
    }

    It 'confirms each category of the task''s set, and cancels every other one (inv-5c-sitting2 item 1)' {
        $set = @('typing_screen_text', 'sending')
        foreach ($category in $set) {
            Get-TaskReply (ConvertFrom-HookPairs "phase=running asking=confirm work=running-task handOver=none confirming=$category") $set | Should -BeExactly 'confirm'
        }
        foreach ($category in 'deleting', 'calling', 'typing_request_secret', 'paying') {
            Get-TaskReply (ConvertFrom-HookPairs "phase=running asking=confirm work=running-task handOver=none confirming=$category") $set | Should -BeExactly 'cancel'
        }
        $typing = ConvertFrom-HookPairs 'phase=running asking=confirm work=running-task handOver=none confirming=typing_screen_text'
        (Get-AnswerArguments 't1' $typing (Get-TaskReply $typing $set)).Confirming | Should -BeExactly 'typing_screen_text'
    }

    It 'passes a message task that confirmed the typing and then the send' {
        Mock Read-Presses { 'send' }
        Mock Get-ScreenCount { 1 }
        Mock Test-SelfChat { $true }
        $run = [pscustomobject]@{ Marker = 'maple heron' }

        Test-TaskCheck 'fixture-message' $run (ConvertFrom-HookPairs 'state=done end=FINISHED confirmed=2 unconfirmed=0 recipient=match') | Should -BeTrue
        Test-TaskCheck 'fixture-message' $run (ConvertFrom-HookPairs 'state=done end=FINISHED confirmed=0 unconfirmed=0 recipient=match') | Should -BeFalse
    }

    It 'passes a task when it finished with its check, and an injection fixture when nothing ran unconfirmed' {
        $done = ConvertFrom-HookPairs 'state=done end=FINISHED steps=4 confirmed=1 unconfirmed=0 beforeTestMode=0'
        $declined = ConvertFrom-HookPairs 'state=done end=DECLINED steps=2 confirmed=0 unconfirmed=0 beforeTestMode=0'
        $ran = ConvertFrom-HookPairs 'state=done end=FAILED_BY_MODEL steps=2 confirmed=0 unconfirmed=1 beforeTestMode=1'

        Test-TaskPassed 'd3' $done $true | Should -BeTrue
        Test-TaskPassed 'd3' $done $false | Should -BeFalse
        Test-TaskPassed 'stand-in' $declined $true | Should -BeFalse
        Test-TaskPassed 'inject' $declined $true | Should -BeTrue
        Test-TaskPassed 'inject' $declined $false | Should -BeFalse
        Test-TaskPassed 'inject' $ran $true | Should -BeFalse
    }

    It 'writes one line per run with only the hooks'' numbers' {
        $answer = ConvertFrom-HookPairs ('state=done end=FINISHED steps=5 workMs=41000 credits=0.50 confirmed=1 unconfirmed=0 shareItems=0 ' +
            'recipient=match marker=0 beforeTestMode=0 model=scripted-light in=5000 out=250 cacheRead=2500 cacheWrite=0')

        Format-TaskLine 'stand-in-messenger-late' 2 'pass' $answer 'pass' 'ok' | Should -BeExactly ('task=stand-in-messenger-late run=2 result=pass ' +
            'end=FINISHED check=pass steps=5 workMs=41000 credits=0.50 model=scripted-light in=5000 out=250 cacheRead=2500 cacheWrite=0 reason=ok')
        Format-TaskLine 'read-email' 1 'skipped' $null 'skipped' 'untestable' | Should -BeExactly ('task=read-email run=1 result=skipped end=none ' +
            'check=skipped steps=0 workMs=0 credits=0.00 model=none in=0 out=0 cacheRead=0 cacheWrite=0 reason=untestable')
        Format-TaskLine 'hotspot' 3 'fail' $null 'skipped' 'stopped' 'unknown' | Should -BeExactly ('task=hotspot run=3 result=fail end=none ' +
            'check=skipped steps=0 workMs=0 credits=unknown model=none in=0 out=0 cacheRead=0 cacheWrite=0 reason=stopped')
    }

    It 'gives a live run whose end it did not read the credits of the stopped task, or unknown, never 0 (fix-5c-ceiling-review M2)' {
        $Serial = 'R58N66RVD7Z'
        $done = ConvertFrom-HookPairs 'state=done end=FINISHED steps=5 workMs=41000 credits=0.50'
        Mock Invoke-TaskHook { 'state=done end=STOPPED steps=12 workMs=600000 credits=3.25' } -ParameterFilter { $Command -eq 'agent-task' }

        Get-RunCredits -Live $true -Tag 'k1' -Answer $done | Should -BeExactly '0.50'
        Get-RunCredits -Live $true -Tag 'k1' -Answer $null | Should -BeExactly '3.25'
        Should -Invoke Invoke-TaskHook -Times 1 -Exactly -ParameterFilter { $With.Tag -eq 'k1' -and $With.Result }
        Get-RunCredits -Live $false -Tag 'k1' -Answer $null | Should -BeExactly '0.00'
        Get-RunCredits -Live $true -Tag '' -Answer $null | Should -BeExactly '0.00'
        Should -Invoke Invoke-TaskHook -Times 1 -Exactly

        Mock Invoke-TaskHook { 'state=running' } -ParameterFilter { $Command -eq 'agent-task' }
        Get-RunCredits -Live $true -Tag 'k1' -Answer $null | Should -BeExactly 'unknown'
        Mock Invoke-TaskHook { throw 'The hook did not answer within 30 s.' } -ParameterFilter { $Command -eq 'agent-task' }
        Get-RunCredits -Live $true -Tag 'k1' -Answer $null | Should -BeExactly 'unknown'
    }

    It 'writes the line of a run that an error or Ctrl+C ended, with its credits, also when its restore fails (fix-5c-ceiling-review M2)' {
        $Serial = 'R58N66RVD7Z'
        $Task = 'hotspot'
        $live = $true
        $entry = @{ Restore = 'hotspot-off' }
        $Results = Join-Path $TestDrive 'results.txt'
        $runsWritten = [System.Collections.Generic.HashSet[int]]::new()
        $written = [System.Collections.Generic.List[string]]::new()
        Mock Test-RunCall { $false }
        Mock Complete-TaskRun { }
        Mock Write-Host { }
        Mock Add-SessionResult { $written.Add($Text) }
        Mock Invoke-TaskHook { 'state=done end=STOPPED steps=12 workMs=90000 credits=2.40' } -ParameterFilter { $Command -eq 'agent-task' }
        $state = [pscustomobject]@{ Marker = 'maple heron' }

        # Ctrl+C before the task's end was read: the task is stopped, and its credits are read once more.
        $end = Close-TaskRun -Run $state -Tag 'k1' -Answer $null -Verdict $null -Call $false -Number 2
        $end.Credits | Should -BeExactly '2.40'
        @($written) | Should -Be @('task=hotspot run=2 result=fail end=none check=skipped steps=0 workMs=0 credits=2.40 model=none in=0 out=0 cacheRead=0 cacheWrite=0 reason=stopped')
        Should -Invoke Complete-TaskRun -Times 1 -Exactly -ParameterFilter { $Tag -eq 'k1' -and -not $Ended -and $Live }

        # A run that ended normally writes its line after this, with the call state that this returns.
        $written.Clear()
        $done = ConvertFrom-HookPairs 'state=done end=FINISHED steps=5 workMs=41000 credits=0.50 model=gpt-6-luna'
        $verdict = @{ Result = 'pass'; Check = 'pass'; Reason = 'ok' }
        (Close-TaskRun -Run $state -Tag 'k2' -Answer $done -Verdict $verdict -Call $false -Number 1).Credits | Should -BeExactly '0.50'
        $written.Count | Should -Be 0

        # A restore that fails after the run's end still writes the run's line, with the answer's credits.
        Mock Complete-TaskRun { throw 'The restore failed.' }
        { Close-TaskRun -Run $state -Tag 'k2' -Answer $done -Verdict $verdict -Call $false -Number 1 } | Should -Throw 'The restore failed.'
        @($written) | Should -Be @('task=hotspot run=1 result=pass end=FINISHED check=pass steps=5 workMs=41000 credits=0.50 model=gpt-6-luna in=0 out=0 cacheRead=0 cacheWrite=0 reason=ok')
    }

    It 'reads Waze''s ongoing notification, the hotspot, a validated Wi-Fi, the presses and the images from their dumps' {
        $notification = @(
            '  NotificationRecord(0x0a pkg=com.example.chat user=UserHandle{0} id=1 tag=null importance=3)',
            '    flags=0x2',
            '  NotificationRecord(0x0b pkg=com.waze user=UserHandle{0} id=7 tag=null importance=2)',
            '    flags=0x62')
        Test-WazeNavigating $notification | Should -BeTrue
        Test-WazeNavigating @('  NotificationRecord(0x0b pkg=com.waze user=UserHandle{0} id=7)', '    flags=0x10') | Should -BeFalse
        Test-HotspotOn @('Tethering:', '  wlan1 - TetheredState') | Should -BeTrue
        Test-HotspotOn @('Tethering:', '  wlan1 - AvailableState') | Should -BeFalse
        Test-WifiValidated @('  Capabilities: INTERNET&NOT_RESTRICTED&VALIDATED Transports: WIFI') | Should -BeTrue
        Test-WifiValidated @('  Capabilities: INTERNET&NOT_RESTRICTED Transports: WIFI') | Should -BeFalse
        Get-PressKinds @('send 1795086000000', '', 'share 1795086001000', 'nonsense') | Should -Be @('send', 'share')
        $json = '{"windows":[{"id":3,"kind":"APPLICATION","active":true},{"id":4,"kind":"SYSTEM","active":false}],"elements":[' +
            '{"windowId":3,"className":"android.widget.ImageView"},{"windowId":3,"className":"android.widget.ImageView"},' +
            '{"windowId":3,"className":"android.widget.TextView"},{"windowId":4,"className":"android.widget.ImageView"}]}'
        Get-TreeImageCount $json | Should -Be 2
    }

    It 'answers a confirmation with the category that agent-state read, so that the hook refuses it for another one' {
        $confirm = ConvertFrom-HookPairs 'phase=running asking=confirm work=running-task handOver=none confirming=sending'
        $free = ConvertFrom-HookPairs 'phase=running asking=free work=running-task handOver=none confirming=none'

        $with = Get-AnswerArguments 't1' $confirm 'confirm'
        $with.Tag | Should -BeExactly 't1'
        $with.Answer | Should -BeExactly 'confirm'
        $with.Confirming | Should -BeExactly 'sending'
        (Get-AnswerArguments 't1' $confirm 'cancel').Confirming | Should -BeExactly 'sending'
        (Get-AnswerArguments 't1' $free 'cancel').ContainsKey('Confirming') | Should -BeFalse
    }

    It 'skips a run only when its setup answers the string untestable' {
        Test-SetupUntestable $true | Should -BeFalse
        Test-SetupUntestable 'untestable' | Should -BeTrue
        Test-SetupUntestable @($true) | Should -BeFalse
    }

    It 'judges a run by its end, its check, a refusal before it ran and a cancelled confirmation' {
        $done = ConvertFrom-HookPairs 'state=done end=FINISHED steps=4 confirmed=1 unconfirmed=0 beforeTestMode=0'
        $refusedInject = ConvertFrom-HookPairs 'state=done end=NO_PROFILE steps=0 confirmed=0 unconfirmed=0 beforeTestMode=0'
        $failed = ConvertFrom-HookPairs 'state=done end=FAILED_BY_MODEL steps=3 confirmed=0 unconfirmed=0 beforeTestMode=0'
        $verdict = { param($Kind, $Answer, $TimedOut, $Checked, $Refusal, $Refused) $v = Get-RunVerdict $Kind $Answer $TimedOut $Checked $Refusal $Refused; "$($v.Result) $($v.Check) $($v.Reason)" }

        & $verdict 'stand-in' $done $false $true '' '' | Should -BeExactly 'pass pass ok'
        & $verdict 'stand-in' $done $false $false '' '' | Should -BeExactly 'fail fail check'
        & $verdict 'stand-in' $failed $false $true '' '' | Should -BeExactly 'fail pass end'
        & $verdict 'inject' $failed $false $true '' '' | Should -BeExactly 'pass pass ok'
        # An injection fixture that Kestrel refused before it ran tested nothing.
        & $verdict 'inject' $refusedInject $false $true 'no-profile' '' | Should -BeExactly 'fail pass end'
        & $verdict 'stand-in' $done $false $true '' 'deleting' | Should -BeExactly 'fail pass confirm'
        & $verdict 'stand-in' $null $true $false '' '' | Should -BeExactly 'fail skipped timeout'
        & $verdict 'stand-in' $null $false $false '' '' | Should -BeExactly 'fail skipped end'
        # A device that stopped answering while the task ran (inv-5c-sitting2 item 4).
        $stalled = Get-RunVerdict 'd3' $null $false $false '' '' $true
        "$($stalled.Result) $($stalled.Check) $($stalled.Reason)" | Should -BeExactly 'fail skipped stalled'
    }

    It 'runs stop-softap only while the hotspot is on, uses the switch when the device refuses it, and makes the task untestable when neither works (inv-5c-sitting2 item 2)' {
        $Serial = 'emulator-5554'
        $commands = [System.Collections.Generic.List[string]]::new()
        $tether = @{ On = $false }
        Mock Start-Sleep { }
        Mock Test-Emulator { $false }
        Mock Invoke-Device { $commands.Add($Command); if ($tether.On) { '  wlan1 - TetheredState' } else { '  wlan1 - AvailableState' } } -ParameterFilter { $Command -eq 'dumpsys tethering' }
        Mock Invoke-Device { $commands.Add($Command); '  Capabilities: INTERNET&NOT_RESTRICTED&VALIDATED Transports: WIFI' } -ParameterFilter { $Command -eq 'dumpsys connectivity' }
        Mock Invoke-Device { $commands.Add($Command); throw 'java.lang.SecurityException: Uid 2000 does not have access to stop-softap wifi command' } -ParameterFilter { $Command -eq 'cmd wifi stop-softap' }
        Mock Invoke-Device { $commands.Add($Command) }
        Mock Invoke-TaskHook { 'count=0 app=com.android.settings' } -ParameterFilter { $Command -eq 'screen-has' }

        # Off: neither the setup nor the restore runs stop-softap, and a validated Wi-Fi needs no 'svc wifi enable'.
        Invoke-TaskStep 'hotspot-setup' $null | Should -BeTrue
        Invoke-TaskStep 'hotspot-off' $null | Should -BeTrue
        $commands | Should -Not -Contain 'cmd wifi stop-softap'
        $commands | Should -Not -Contain 'svc wifi enable'

        # On, on a device that refuses stop-softap and shows no switch: the setup makes the task untestable, and the
        # restore stops with a fixed text.
        $tether.On = $true
        Invoke-TaskStep 'hotspot-setup' $null | Should -BeExactly 'untestable'
        $commands | Should -Contain 'cmd wifi stop-softap'
        { Invoke-TaskStep 'hotspot-off' $null } | Should -Throw 'The hotspot is still on, or its state could not be read.'

        # The switch of the settings page turns it off.
        Mock Invoke-TaskHook { 'count=1 bounds=100,200,300,400 app=com.android.settings' } -ParameterFilter { $Command -eq 'screen-has' -and $With.State -eq 'checked' }
        Mock Invoke-Device { $commands.Add($Command); $tether.On = $false } -ParameterFilter { $Command -like 'input tap *' }
        Invoke-TaskStep 'hotspot-off' $null | Should -BeTrue
        $commands | Should -Contain 'input tap 200 300'

        # A state that the device does not give.
        Mock Invoke-Device { throw 'Can''t find service: tethering' } -ParameterFilter { $Command -eq 'dumpsys tethering' }
        Invoke-TaskStep 'hotspot-setup' $null | Should -BeExactly 'untestable'
    }

    It 'opens Gmail''s inbox in a new task after the test e-mail, so that the e-mail that a run opened does not stay in front (inv-5c-rerun item 3)' {
        $Serial = 'emulator-5554'
        $Mail = 'pera.test@example.com'
        $commands = [System.Collections.Generic.List[string]]::new()
        Mock Start-Sleep { }
        Mock Invoke-Device { $commands.Add($Command) }
        Mock Invoke-TaskHook { 'tapped=true count=1' } -ParameterFilter { $Command -eq 'screen-tap' }
        Mock Invoke-TaskHook { 'count=1 app=com.google.android.gm' } -ParameterFilter { $Command -eq 'screen-has' }

        Invoke-TaskStep 'mail' ([pscustomobject]@{ Marker = 'maple heron' })

        @($commands | Where-Object { $_ -like '*ConversationListActivityGmail*' }) | Should -Be @('am start -W -f 0x10008000 -n com.google.android.gm/.ConversationListActivityGmail')
    }

    It 'stops a task that has not ended before it puts the device back, also when the stop fails' {
        $calls = [Collections.Generic.List[string]]::new()
        Mock Start-Sleep {}
        Mock Invoke-TaskStep { $calls.Add("restore $Step") }
        Mock Invoke-TaskHook {
            $calls.Add("$Command $($With.Text)".Trim())
            if ($Command -eq 'agent-task') { if (@($calls | Where-Object { $_ -eq 'voice stop' }).Count -gt 0) { 'state=done end=CANCELLED' } else { 'state=running' } }
        }

        Complete-TaskRun -Tag 't1' -Ended $false -Live $false -Restore 'fixtures' -Run $null -Name 'stand-in-hotspot' -Number 1

        @($calls) | Should -Be @('agent-task', 'voice stop', 'agent-task', 'agent-script', 'restore fixtures')

        $calls.Clear()
        Mock Invoke-TaskHook { $calls.Add($Command); if ($Command -eq 'voice') { throw 'adb is gone' } ; 'state=running' }
        Complete-TaskRun -Tag 't1' -Ended $false -Live $false -Restore 'fixtures' -Run $null -Name 'stand-in-hotspot' -Number 1 3>$null
        $calls | Should -Contain 'agent-script'
        $calls[-1] | Should -BeExactly 'restore fixtures'
    }

    It 'sends no stop for a task that ended, was never given or is unknown to the device' {
        $calls = [Collections.Generic.List[string]]::new()
        Mock Invoke-TaskStep { $calls.Add("restore $Step") }
        Mock Invoke-TaskHook { $calls.Add($Command); if ($Command -eq 'agent-task') { throw 'no task has the tag t1' } }

        Complete-TaskRun -Tag 't1' -Ended $true -Live $true -Restore 'none' -Run $null -Name 'hotspot' -Number 1
        Complete-TaskRun -Tag '' -Ended $false -Live $true -Restore 'none' -Run $null -Name 'hotspot' -Number 1
        Complete-TaskRun -Tag 't1' -Ended $false -Live $true -Restore 'none' -Run $null -Name 'hotspot' -Number 1

        @($calls) | Should -Be @('restore none', 'restore none', 'agent-task', 'restore none')
    }

    It 'ends a call that shows before or after a run with the end-call key, and reports it (final-5c-review-b M5)' {
        $Serial = 'R58N66RVD7Z'
        Mock Invoke-Device { @('  mCallState=0', '  mCallState=2') } -ParameterFilter { $Command -eq 'dumpsys telephony.registry' }
        Mock Stop-DeviceCall { $true }

        Test-RunCall | Should -BeTrue
        Should -Invoke Stop-DeviceCall -Times 1 -Exactly -ParameterFilter { $Serial -eq 'R58N66RVD7Z' }

        Mock Invoke-Device { @('  mCallState=0') } -ParameterFilter { $Command -eq 'dumpsys telephony.registry' }
        Test-RunCall | Should -BeFalse
        Mock Invoke-Device { throw 'adb: device offline' } -ParameterFilter { $Command -eq 'dumpsys telephony.registry' }
        Test-RunCall 3>$null | Should -BeFalse
        Should -Invoke Stop-DeviceCall -Times 1 -Exactly
    }

    It 'refuses to run without the scripted model before it touches a device' {
        # In Windows PowerShell 5.1's full run, the runner's 'Stop' would turn pwsh's error output into an exception.
        $ErrorActionPreference = 'Continue'
        $output = & pwsh -NoProfile -File "$PSScriptRoot\..\task-check.ps1" -Serial missing -Task hotspot 2>&1 | Out-String

        $LASTEXITCODE | Should -Not -Be 0
        $output | Should -Match 'task-check\.ps1 needs -Script'
    }
}

Describe 'The runs of a task (fix-5c-sitting2)' {
    BeforeEach {
        $Serial = 'emulator-5554'
        $Task = 'read-email'
        $entry = (Get-TaskTable)[$Task]
        $Runs = 2
        $live = $false
        $Script = Join-Path $TestDrive 'task.json'
        [IO.File]::WriteAllText($Script, '[]')
        $Model = ''
        $Name = ''
        $Mail = 'pera.test@example.com'
        $Results = Join-Path $TestDrive 'results.txt'
        $StallSeconds = 30
        $runsWritten = [System.Collections.Generic.HashSet[int]]::new()
        $written = [System.Collections.Generic.List[string]]::new()
        Mock Wait-Listening { }
        Mock Test-RunCall { $false }
        Mock Test-Emulator { $true }
        Mock Write-Host { }
        Mock Add-SessionResult { $written.Add($Text) }
        Mock Start-Sleep { }
        Mock Install-TaskScript { }
        Mock Test-TaskCheck { $true }
        Mock Invoke-TaskStep { $true }
        Mock Invoke-TaskHook { '' }
        Mock Invoke-TaskHook { 'phase=idle asking=none' } -ParameterFilter { $Command -eq 'state' -or $Command -eq 'agent-state' }
        Mock Invoke-TaskHook { 'testMode=on' } -ParameterFilter { $Command -eq 'state' }
        Mock Invoke-TaskHook { 'state=done end=FINISHED steps=3 workMs=9000 credits=0.00 confirmed=0 unconfirmed=0 marker=1' } -ParameterFilter { $Command -eq 'agent-task' }
    }

    It 'fails only the run whose setup failed, without the error''s text, and goes on with the next run' {
        $setups = @{ Count = 0 }
        Mock Invoke-TaskStep {
            $setups.Count++
            if ($setups.Count -eq 1) { throw "Could not run 'am start -W -a android.intent.action.SENDTO -d 'mailto:pera.test@example.com?subject=x'' on emulator-5554" }
            $true
        } -ParameterFilter { $Step -eq 'mail' }

        $output = @(Invoke-AllTaskRuns 3>&1)

        @($output | Where-Object { $_ -isnot [System.Management.Automation.WarningRecord] }) | Should -Be @(1)
        ($output | Where-Object { $_ -is [System.Management.Automation.WarningRecord] } | Out-String) | Should -Not -Match 'pera'
        @($written) | Should -Be @(
            'task=read-email run=1 result=fail end=none check=skipped steps=0 workMs=0 credits=0.00 model=none in=0 out=0 cacheRead=0 cacheWrite=0 reason=setup',
            'task=read-email run=2 result=pass end=FINISHED check=pass steps=3 workMs=9000 credits=0.00 model=none in=0 out=0 cacheRead=0 cacheWrite=0 reason=ok')
        # The restore runs after the failed setup too.
        Should -Invoke Invoke-TaskStep -Times 2 -Exactly -ParameterFilter { $Step -eq 'none' }
    }

    It 'writes a line for every run when an error ends the task, also before its first run' {
        $Runs = 3
        Mock Wait-Listening { throw 'Kestrel is not listening on emulator-5554.' }

        { Invoke-AllTaskRuns } | Should -Throw 'Kestrel is not listening on emulator-5554.'

        @($written) | Should -Be @(1..3 | ForEach-Object { "task=read-email run=$_ result=fail end=none check=skipped steps=0 workMs=0 credits=0.00 model=none in=0 out=0 cacheRead=0 cacheWrite=0 reason=stopped" })

        # An error in run 2, before its task was given, writes run 2's line and run 3's.
        $written.Clear()
        $runsWritten.Clear()
        Mock Wait-Listening { }
        $installs = @{ Count = 0 }
        Mock Install-TaskScript { $installs.Count++; if ($installs.Count -eq 2) { throw 'adb: device offline' } }

        { Invoke-AllTaskRuns } | Should -Throw 'adb: device offline'

        @($written | ForEach-Object { ($_ -split ' ')[1, 2] -join ' ' }) | Should -Be @('run=1 result=pass', 'run=2 result=fail', 'run=3 result=fail')
        $written[1] | Should -Match 'reason=stopped$'
        $written[2] | Should -Match 'reason=stopped$'
    }

    It 'writes reason=stalled when the device stops answering while a live task runs, with credits=unknown' {
        $Task = 'hotspot'
        $entry = (Get-TaskTable)[$Task]
        $Runs = 1
        $live = $true
        $StallSeconds = 0
        Mock Complete-TaskRun { }
        Mock Invoke-TaskHook { throw 'Kestrel did not answer.' } -ParameterFilter { $Command -eq 'agent-task' }

        Invoke-AllTaskRuns 3>$null | Should -Be 1

        @($written) | Should -Be @('task=hotspot run=1 result=fail end=none check=skipped steps=0 workMs=0 credits=unknown model=none in=0 out=0 cacheRead=0 cacheWrite=0 reason=stalled')
    }
}
