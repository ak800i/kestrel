# Tests of the helpers in scripts\a71-session.ps1, the A71 session of sub-project 2. The script needs a device, so the
# tests load only its helper functions from the script's syntax tree. scripts\test-scripts.ps1 runs these tests in
# Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

BeforeAll {
    . "$PSScriptRoot\..\lib\Session.ps1"
    $tokens = $null
    $errors = $null
    $tree = [System.Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot\..\a71-session.ps1", [ref]$tokens, [ref]$errors)
    $helpers = @('Get-NoticeLetter', 'Invoke-CorpusClip', 'Initialize-Air', 'Start-KestrelAfterTests', 'Start-Kestrel',
        'Get-AssistantVolume', 'Set-AssistantVolume', 'Invoke-BargeInStep')
    $functions = $tree.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -in $helpers
        }, $true)
    foreach ($function in $functions) { . ([scriptblock]::Create($function.Extent.Text)) }
    # The helpers play, send and write through these, which the tests mock.
    function Send-Heard([string[]]$Texts) { }
    function Invoke-Clip([string]$Path, [double]$Gain) { }
    function Add-Result([string]$StepName, [string]$Text) { }
    function Invoke-Hook([string]$Command, [hashtable]$With = @{}) { }
    # Start-Kestrel reads Kestrel's state and reaches the device through these, which the tests mock.
    function Invoke-Checked([string]$Command) { }
    function Get-VoiceState { }
    function Wait-Mode([string]$Mode, [double]$TimeoutSeconds) { }
    # The step barge-in measures and restores through these, which the tests mock.
    function Register-Restore([string]$What, [string]$ByHand, $Data, [scriptblock]$Action) { }
    function Complete-Restore($Restore) { }
    function Assert-TestDevice([string]$Serial) { }
    function Stop-Kestrel { }
    function Get-EvalFolder { }
    function Set-SpeakerLevel([double]$TargetDb, [scriptblock]$Measure) { }
    function Measure-BargeInTry([double]$Gain) { }
    function Measure-ReplyTail([int]$Try) { }
}

Describe 'The helpers of the A71 session of sub-project 2' {
    It 'parses the script, and finds every helper' {
        $errors | Should -BeNullOrEmpty
        @($functions).Count | Should -Be $helpers.Count
    }

    It 'starts Kestrel after the device tests from its main activity before any hook, because a stopped app may get no broadcast' {
        $calls = [System.Collections.Generic.List[string]]::new()
        Mock Invoke-Checked { $calls.Add($Command) }
        Mock Invoke-Hook { $calls.Add("hook $Command") }
        Mock Get-VoiceState { [ordered]@{ mode = 'off'; session = 'closed' } }
        Mock Wait-Mode { 1 }

        Start-KestrelAfterTests

        $calls[0] | Should -BeExactly 'am start -n dev.kestrel/.app.MainActivity'
        @($calls | Where-Object { $_ -like 'hook *' }).Count | Should -BeGreaterThan 0
    }

    It 'gives the kinds of Kestrel''s notices the letters of the owner''s old answers' {
        Get-NoticeLetter @('accessibility', 'battery') | Should -BeExactly 'b'
        Get-NoticeLetter @('accessibility') | Should -BeExactly 'a'
        Get-NoticeLetter @('none') | Should -BeExactly 'n'
        Get-NoticeLetter @() | Should -BeExactly 'n'
        Get-NoticeLetter @('accessibility', 'stopped') | Should -BeExactly 'o'
        Get-NoticeLetter @('settings') | Should -BeExactly 'o'
    }

    Context 'The speaker''s clips' {
        BeforeEach {
            $clipRows = @{
                c041 = [pscustomobject]@{ Id = 'c041'; Text = 'Kestrel.'; Spotted = $true }
                k023 = [pscustomobject]@{ Id = 'k023'; Text = 'Open the weather.'; Spotted = $false }
            }
            $generatedRoot = 'C:\corpus\generated'
            Mock Send-Heard { }
            Mock Invoke-Clip { }
            Mock Add-Result { }
            Mock Invoke-Hook { }
        }

        It 'plays a line''s clip in the voice at the measured gain, and in a dry run sends its text, with "Kestrel" for a named line' {
            $DryRun = $false
            $gain = 0.4

            Invoke-CorpusClip c041 piper

            Should -Invoke Invoke-Clip -Times 1 -Exactly -ParameterFilter { $Path -eq 'C:\corpus\generated\piper\c041.wav' -and $Gain -eq 0.4 }
            $DryRun = $true
            Invoke-CorpusClip c041
            Invoke-CorpusClip k023
            Should -Invoke Send-Heard -Times 1 -Exactly -ParameterFilter { $Texts -ceq '+Kestrel.' }
            Should -Invoke Send-Heard -Times 1 -Exactly -ParameterFilter { $Texts -ceq 'Open the weather.' }
            { Invoke-CorpusClip c999 } | Should -Throw 'No line c999*'
        }

        It 'lets a dry run through without a level, and skips the speaker''s checks without the clips' {
            $DryRun = $true
            Initialize-Air voice | Should -BeTrue

            $DryRun = $false
            $gain = $null
            Initialize-Air voice | Should -BeFalse
            Should -Invoke Add-Result -Times 1 -Exactly -ParameterFilter { $StepName -eq 'voice' -and $Text -like 'skipped: corpus\generated has no kokoro\c041.wav*' }
            Should -Invoke Invoke-Hook -Times 0 -Exactly
        }
    }

    It 'measures barge-in with the assistant volume (stream 11) from the owner''s index down, fails a row whose volume did not take, and puts the volume back' {
        $DryRun = $true
        $Serial = 'emulator-5554'
        $adb = 'adb'
        # The phone's assistant volume, which ignores a set to 4.
        $device = @{ Assistant = 7; Commands = [System.Collections.Generic.List[string]]::new() }
        $tried = [System.Collections.Generic.List[int]]::new()
        $tailVolumes = [System.Collections.Generic.List[int]]::new()
        $results = [System.Collections.Generic.List[string]]::new()
        $restores = [System.Collections.Generic.List[object]]::new()
        Mock Invoke-Checked {
            $device.Commands.Add($Command)
            if ($Command -match '--stream 11 --set (\d+)$' -and $Matches[1] -ne '4') { $device.Assistant = [int]$Matches[1] }
            if ($Command -like '*--stream 11 --get') { "volume is $($device.Assistant) in range [0..15]" }
        }
        Mock Invoke-Hook { 'boost=2.0 threshold=0.15' }
        Mock Add-Result { $results.Add($Text) }
        Mock Initialize-Air { $true }
        Mock Start-Kestrel { }
        Mock Register-Restore {
            $restore = [pscustomobject]@{ What = $What; Data = $Data; Action = $Action }
            $restores.Add($restore)
            $restore
        }
        Mock Complete-Restore { & $Restore.Action $Restore.Data }
        Mock Measure-BargeInTry { $tried.Add($device.Assistant); [pscustomobject]@{ EchoDb = -15.0; Interrupted = $false; Rise = '' } }
        Mock Measure-ReplyTail { $tailVolumes.Add($device.Assistant); $false }

        Invoke-BargeInStep

        $tried | Select-Object -Unique | Should -Be @(7, 5, 3)
        $tried.Count | Should -Be 9
        $tailVolumes | Should -Be @(7)
        $device.Assistant | Should -Be 7
        $restores.What | Should -Be @('the assistant volume (stream 11)')
        $restores.Data | Should -Be @(7)
        @($device.Commands | Where-Object { $_ -like '*--stream 3*' }) | Should -BeNullOrEmpty
        @($results | Where-Object { $_ -like 'the assistant volume (stream 11), which Kestrel''s replies use, is 7 of 15*' }).Count | Should -Be 1
        @($results | Where-Object { $_ -like 'assistant volume 4 of 15: failed: it read back as 5*' }).Count | Should -Be 1
        $results[-1] | Should -BeLike 'decision: branch=unmeasured *'
    }
}
