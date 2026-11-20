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
    $helpers = @('Get-NoticeLetter', 'Invoke-CorpusClip', 'Initialize-Air')
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
}

Describe 'The helpers of the A71 session of sub-project 2' {
    It 'parses the script, and finds every helper' {
        $errors | Should -BeNullOrEmpty
        @($functions).Count | Should -Be $helpers.Count
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
}
