# Tests of the helpers in scripts\a71-commands.ps1, the A71 session of the instant commands. The script needs a device,
# so the tests load only its helper functions from the script's syntax tree. scripts\test-scripts.ps1 runs these tests in
# Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

BeforeAll {
    # Read-YesNo asks through Read-YesNoAnswer of the sessions' library.
    . "$PSScriptRoot\..\lib\Session.ps1"
    $tokens = $null
    $errors = $null
    $tree = [System.Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot\..\a71-commands.ps1", [ref]$tokens, [ref]$errors)
    $helpers = @('Get-SessionSteps', 'Test-SimAbsent', 'Get-UserSummary', 'Get-NewScreenshotIds', 'Test-CheckNotification', 'Format-CheckResult', 'Get-Median',
        'Get-CommandNumber', 'Wait-State', 'Wait-CheckCommand', 'Read-YesNo', 'Read-RadioScreen', 'Format-RadioResult',
        'Complete-Restore', 'Invoke-Restore', 'Stop-KestrelForRecording', 'Invoke-OwnerRecording', 'Invoke-Check', 'Get-UtteranceCount')
    $functions = $tree.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -in $helpers
        }, $true)
    foreach ($function in $functions) { . ([scriptblock]::Create($function.Extent.Text)) }
    # Wait-State and Invoke-Check read Kestrel's state through this function, which the tests mock.
    function Get-VoiceState([int]$Number = 0) { }
    # Invoke-Check sends the owner's words and writes its result through these, which the tests mock.
    function Send-Heard([string]$Text) { }
    function Add-Result([string]$StepName, [string]$Text) { }
    # Invoke-OwnerRecording works on the device through these, which the tests mock.
    function Invoke-Checked([string]$Command) { }
    function Invoke-Hook([string]$Command, [hashtable]$With = @{}) { }
    function Stop-Kestrel { }
    function Invoke-AdbCopy([string]$Serial, [string]$Direction, [string]$From, [string]$To) { }
}

Describe 'The helpers of the A71 session of the instant commands' {
    It 'parses the script, and finds every helper' {
        $errors | Should -BeNullOrEmpty
        @($functions).Count | Should -Be $helpers.Count
    }

    It 'takes the steps also as one text separated by commas' {
        $names = @('install', 'screen', 'calls')

        Get-SessionSteps -Step @('screen,calls') -Names $names | Should -Be @('screen', 'calls')
        Get-SessionSteps -Step @('install', ' calls ') -Names $names | Should -Be @('install', 'calls')
        @(Get-SessionSteps -Step @() -Names $names).Count | Should -Be 0
    }

    It 'refuses a step that it does not know' {
        { Get-SessionSteps -Step @('screen,radio') -Names @('screen', 'radios') } | Should -Throw 'Unknown steps: radio.*'
    }

    It 'tells from the SIM state whether a SIM card is in the phone' {
        Test-SimAbsent -State 'ABSENT' | Should -BeTrue
        Test-SimAbsent -State 'ABSENT,ABSENT' | Should -BeTrue
        Test-SimAbsent -State 'ABSENT,' | Should -BeTrue
        # A phone that does not set the property, or a read that gave nothing, does not say that none is there.
        Test-SimAbsent -State '' | Should -BeFalse
        Test-SimAbsent -State ',' | Should -BeFalse
        Test-SimAbsent -State 'LOADED,ABSENT' | Should -BeFalse
        Test-SimAbsent -State 'PIN_REQUIRED' | Should -BeFalse
        # A phone with a SIM card can say NOT_READY while its radio starts.
        Test-SimAbsent -State 'NOT_READY' | Should -BeFalse
    }

    It 'keeps the users'' IDs, types and flags, and never their names' {
        $users = @('Users:', '	UserInfo{0:Owner:c13} running', '	UserInfo{150:Secure Folder:10001030} running')
        $dump = @(
            'Users:'
            '  UserInfo{0:Owner:c13} serialNo=0 isPrimary=true'
            '    Type: android.os.usertype.full.SYSTEM'
            '  UserInfo{150:Secure Folder:10001030} serialNo=10'
            '    Type: com.samsung.android.knox.securefolder'
        )

        $summary = @(Get-UserSummary -Users $users -Dump $dump)

        $summary | Should -Be @('id=0 type=android.os.usertype.full.SYSTEM flags=c13', 'id=150 type=com.samsung.android.knox.securefolder flags=10001030')
        ($summary -join ' ') | Should -Not -Match 'Owner'
    }

    It 'finds the new screenshots and leaves the owner''s other images' {
        $rows = @(
            'Row: 0 _id=120, _display_name=Screenshot_20261120_101500_One UI Home.png'
            'Row: 1 _id=121, _display_name=IMG_20261120_101501.jpg'
        )

        @(Get-NewScreenshotIds -Rows $rows) | Should -Be @(120)
        @(Get-NewScreenshotIds -Rows @('No result found.')).Count | Should -Be 0
    }

    It 'writes a check''s result with the kind and the outcome of the command, and the median of some times' {
        $state = [ordered]@{ mode = 'listening'; command = '7'; kind = 'Radio'; outcome = 'done'; session = 'open' }

        Format-CheckResult -Name 'turn off Wi-Fi' -Owner 'y' -State $state -Before 6 | Should -BeExactly 'turn off Wi-Fi: owner=y kind=Radio outcome=done'
        Format-CheckResult -Name 'mute' -Owner 'n' -State $null -Before 6 | Should -BeExactly 'mute: owner=n kind=- outcome=-'
        Get-Median @(300, 100, 200) | Should -Be 200
        Get-Median @(400, 100, 300, 200) | Should -Be 200
        Get-Median @() | Should -Be 0
    }

    It 'records no command for a check whose words gave none, never the command before it' {
        $state = [ordered]@{ mode = 'listening'; command = '7'; kind = 'Tap'; outcome = 'done'; session = 'open' }

        Format-CheckResult -Name 'Display' -Owner 'y' -State $state -Before 7 | Should -BeExactly 'Display: owner=y kind=- outcome=none'
    }

    It 'adds how many more commands started during the check, and the rise of the utterances, only when there were more' {
        $state = [ordered]@{ mode = 'listening'; command = '7'; kind = 'Global'; outcome = 'done'; latest = '9'; session = 'open' }

        Format-CheckResult -Name 'go home' -Owner 'y' -State $state -Before 6 -Extra 2 -Utterances '3' | Should -BeExactly 'go home: owner=y kind=Global outcome=done extra=2 utterances+3'
        Format-CheckResult -Name 'go home' -Owner 'y' -State $state -Before 6 -Extra 2 | Should -BeExactly 'go home: owner=y kind=Global outcome=done extra=2 utterances+?'
        Format-CheckResult -Name 'go home' -Owner 'y' -State $state -Before 6 -Extra 0 -Utterances '1' | Should -BeExactly 'go home: owner=y kind=Global outcome=done'
    }

    Context 'A check during which another voice starts a newer command' {
        BeforeEach {
            $DryRun = $true
            Mock Write-Host { }
            Mock Start-Sleep { }
            # Command 5 ended before the check; the check's words start command 6, and the room's sound command 7.
            $device = @{ Sent = $false }
            Mock Send-Heard { $device.Sent = $true }
            Mock Get-VoiceState {
                if (-not $device.Sent) { return [ordered]@{ mode = 'listening'; command = '5'; kind = 'Tap'; outcome = 'done'; session = 'open' } }
                if ($Number -eq 6) { return [ordered]@{ mode = 'listening'; command = '6'; kind = 'Global'; outcome = 'done'; latest = '7'; session = 'open' } }
                [ordered]@{ mode = 'listening'; command = '7'; kind = 'Bare'; outcome = 'ignored'; session = 'open' }
            }
            Mock Invoke-Hook { if ($device.Sent) { 'waits=0 tasks=0 lastTaskWords=0 hits=0 utterances=3' } else { 'waits=0 tasks=0 lastTaskWords=0 hits=0 utterances=1' } }
            Mock Add-Result { }
        }

        It 'records its own command, the first after it began, and how many more started' {
            Invoke-Check notifications 'show notifications' 'Did the notification shade open?' -Heard 'show the notifications'

            Should -Invoke Send-Heard -Times 1 -Exactly -ParameterFilter { $Text -eq 'show the notifications' }
            Should -Invoke Add-Result -Times 1 -Exactly -ParameterFilter {
                $StepName -eq 'notifications' -and $Text -eq 'show notifications: owner=dry kind=Global outcome=done extra=1 utterances+2'
            }
        }
    }

    It 'counts a command that still runs when a check starts, such as one that asks "Which one?", as the check''s own' {
        Get-CommandNumber -State ([ordered]@{ mode = 'listening'; command = '7'; outcome = 'done'; session = 'open' }) | Should -Be 7
        Get-CommandNumber -State ([ordered]@{ mode = 'listening'; command = '7'; outcome = 'running'; session = 'open' }) | Should -Be 6
    }

    It 'waits for the end of a command only when one started after the owner''s words, and says so' {
        Mock Write-Host { }
        Mock Get-VoiceState { [ordered]@{ mode = 'listening'; command = '5'; kind = 'Tap'; outcome = 'done'; session = 'open' } }
        $watch = [Diagnostics.Stopwatch]::StartNew()

        Wait-CheckCommand -Number 5 -StartSeconds 0.5 -EndSeconds 15 | Should -BeFalse

        $watch.Elapsed.TotalSeconds | Should -BeLessThan 5
        Should -Invoke Write-Host -Times 0 -Exactly

        Mock Get-VoiceState { [ordered]@{ mode = 'listening'; command = '6'; kind = 'Tap'; outcome = 'done'; session = 'open' } }

        Wait-CheckCommand -Number 5 -StartSeconds 0.5 -EndSeconds 15 | Should -BeTrue

        Should -Invoke Write-Host -Times 1 -Exactly -ParameterFilter { "$Object" -eq 'Waiting up to 15 s for Kestrel''s command to end.' }
    }

    It 'asks again until the owner''s answer is y, yes, n or no, in any letter case' {
        $answers = [System.Collections.Generic.Queue[string]]::new([string[]]@('', 'maybe', ' N ', 'Yes'))
        Mock Read-Host { $answers.Dequeue() }

        Read-YesNo 'Did the page open? Type y or n.' | Should -BeExactly 'n'
        Read-YesNo 'Did the page open? Type y or n.' | Should -BeExactly 'y'

        Should -Invoke Read-Host -Times 4 -Exactly
    }

    It 'finds the notification "Kestrel check" in the list of notifications, and only the shell''s' {
        Test-CheckNotification @('0|com.android.shell|2020|kestrel-check|2000', '0|android|19|null|1000') | Should -BeTrue
        Test-CheckNotification @('0|com.example|7|kestrel-check|10123', '0|android|19|null|1000') | Should -BeFalse
        Test-CheckNotification @() | Should -BeFalse
    }

    It 'asks which screen a radio command opened until the answer is q, p, i or n' {
        $answers = [System.Collections.Generic.Queue[string]]::new([string[]]@('', 'quick', 'y', ' P '))
        Mock Read-Host { $answers.Dequeue() }

        Read-RadioScreen 'Which screen opened?' | Should -BeExactly 'p'

        Should -Invoke Read-Host -Times 4 -Exactly
    }

    It 'writes a radio check''s line with Android''s state and the screen that opened, as one letter' {
        Format-RadioResult -Say 'turn off Bluetooth' -Done $true -Screen 'q' | Should -BeExactly 'turn off Bluetooth: Android says done screen=q'
        Format-RadioResult -Say 'turn on Wi-Fi' -Done $false -Screen 'n' | Should -BeExactly 'turn on Wi-Fi: Android says not done screen=n'
    }

    Context 'An owner''s recording while Kestrel may be on' {
        BeforeEach {
            $Serial = 'emulator-5554'
            $remote = '/sdcard/eval'
            $records = [System.Collections.Generic.List[string]]::new()
            Mock Write-Host { }
            Mock Start-Sleep { }
            Mock Invoke-Checked { }
            Mock Invoke-AdbCopy { }
            Mock Stop-Kestrel { }
            Mock Invoke-Hook { 'done 1000 ms loudest 40 % silenced=false' } -ParameterFilter { $With.Result }
        }

        It 'turns a Kestrel that is on off first, brings the settings to the front, says so and records' {
            Mock Get-VoiceState { [ordered]@{ mode = 'listening'; session = 'open' } }
            Mock Invoke-Hook { $records.Add('record'); 'recording' } -ParameterFilter { $Command -eq 'voice-record' -and -not $With.Result }

            Invoke-OwnerRecording -File 'clip.wav' -Seconds 3 | Should -BeExactly 'done 1000 ms loudest 40 % silenced=false'

            Should -Invoke Stop-Kestrel -Times 1 -Exactly
            Should -Invoke Invoke-Checked -Times 1 -Exactly -ParameterFilter { $Command -eq 'am start -n dev.kestrel/.app.MainActivity' }
            Should -Invoke Write-Host -Times 1 -Exactly -ParameterFilter { "$Object" -eq 'Kestrel was on, and the script has now turned it off.' }
            $records.Count | Should -Be 1
        }

        It 'turns Kestrel off and records again when the hook refuses because Kestrel came on after the check' {
            Mock Get-VoiceState { [ordered]@{ mode = 'off'; session = 'closed' } }
            Mock Invoke-Hook {
                $records.Add('record')
                if ($records.Count -eq 1) { throw 'turn Kestrel off first, with voice -Send off' }
                'recording'
            } -ParameterFilter { $Command -eq 'voice-record' -and -not $With.Result }

            Invoke-OwnerRecording -File 'clip.wav' -Seconds 3 | Should -BeExactly 'done 1000 ms loudest 40 % silenced=false'

            Should -Invoke Stop-Kestrel -Times 1 -Exactly
            Should -Invoke Invoke-Checked -Times 2 -Exactly -ParameterFilter { $Command -eq 'am start -n dev.kestrel/.app.MainActivity' }
            $records.Count | Should -Be 2
        }

        It 'leaves a Kestrel that is off alone, and passes on every other refusal of the hook' {
            Mock Get-VoiceState { [ordered]@{ mode = 'off'; session = 'closed' } }
            Mock Invoke-Hook { throw 'the clip name must be letters and digits' } -ParameterFilter { $Command -eq 'voice-record' -and -not $With.Result }

            { Invoke-OwnerRecording -File 'clip.wav' -Seconds 3 } | Should -Throw 'the clip name must be letters and digits'

            Should -Invoke Stop-Kestrel -Times 0 -Exactly
        }
    }

    It 'stops a step at the owner''s "q" at a restore''s prompt, and at the session''s end runs the other restores and keeps the reason' {
        $runs = [ref]0
        $first = [pscustomobject]@{ Data = $null; Action = { throw 'The owner stopped the session.' }; Reason = 'the session ended before it' }
        $second = [pscustomobject]@{ Data = $null; Action = { $runs.Value++ }; Reason = 'the session ended before it' }
        $pending = [System.Collections.Generic.List[object]]::new()
        $pending.Add($first)
        $pending.Add($second)

        { Complete-Restore $first } | Should -Throw 'The owner stopped the session.'

        { Invoke-Restore @($first, $second) } | Should -Not -Throw
        $runs.Value | Should -Be 1
        @($pending) | Should -Be @($first)
        $first.Reason | Should -BeExactly 'The owner stopped the session.'
    }
}
