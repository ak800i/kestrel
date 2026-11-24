# Tests of the helpers in scripts\a71-providers.ps1, the owner's session of sub-project 4. The script needs devices, so
# the tests load only its helper functions from the script's syntax tree. scripts\test-scripts.ps1 runs these tests in
# Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

BeforeAll {
    . "$PSScriptRoot\..\lib\Kestrel.ps1"
    . "$PSScriptRoot\..\lib\Session.ps1"
    . "$PSScriptRoot\..\lib\DeviceGuard.ps1"
    $tokens = $null
    $errors = $null
    $tree = [System.Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot\..\a71-providers.ps1", [ref]$tokens, [ref]$errors)
    $helpers = @('Get-SessionSteps', 'Get-RetryOverride', 'Get-BoundsCentre', 'Get-LiveSkipReason', 'Test-KestrelInFront',
        'Get-DeviceName', 'Wait-ServiceBound')
    $functions = $tree.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -in $helpers
        }, $true)
    foreach ($function in $functions) { . ([scriptblock]::Create($function.Extent.Text)) }
    # The script's lists of steps.
    $lists = $tree.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.AssignmentStatementAst] -and "$($node.Left)" -in '$stepNames', '$refusedSteps'
        }, $false)
    foreach ($list in $lists) { . ([scriptblock]::Create($list.Extent.Text)) }
    # Wait-ServiceBound asks Kestrel through this, which the tests mock.
    function Invoke-Hook([string]$Device, [string]$Command, [hashtable]$With = @{}) { }
}

Describe 'The helpers of the owner''s session of sub-project 4' {
    It 'parses the script, and finds every helper' {
        $errors | Should -BeNullOrEmpty
        @($functions).Count | Should -Be $helpers.Count
    }

    It 'takes the steps also as one text separated by commas, and refuses an unknown one' {
        $names = @('install', 'emulator-live', 'end')

        Get-SessionSteps -Step @('install,end') -Names $names | Should -Be @('install', 'end')
        @(Get-SessionSteps -Step @() -Names $names).Count | Should -Be 0
        { Get-SessionSteps -Step @('install,radio') -Names $names } | Should -Throw 'Unknown steps: radio.*'
    }

    It 'finds the controller''s override for a failure''s error kind' {
        $overrides = '{"Malformed": {"MessagesPath": "/v1/messages"}, "BadRequest": {"Headers": "Openai-Intent=conversation-edits"}, "Timeout": {}}' | ConvertFrom-Json

        (Get-RetryOverride -Overrides $overrides -ErrorText 'Malformed').MessagesPath | Should -Be '/v1/messages'
        (Get-RetryOverride -Overrides $overrides -ErrorText 'BadRequest(status=400,code=x)').Headers | Should -Be 'Openai-Intent=conversation-edits'
        Get-RetryOverride -Overrides $overrides -ErrorText 'Timeout' | Should -BeNullOrEmpty
        Get-RetryOverride -Overrides $overrides -ErrorText 'Unreachable' | Should -BeNullOrEmpty
        Get-RetryOverride -Overrides $null -ErrorText 'Malformed' | Should -BeNullOrEmpty
    }

    It 'refuses the step emulator-signin, because kestrel-api37 keeps the owner''s Copilot profile' {
        $stepNames | Should -Not -Contain 'emulator-signin'
        { Get-SessionSteps -Step @('install,emulator-signin') -Names $stepNames -Refused $refusedSteps } |
            Should -Throw 'The step emulator-signin was refused: kestrel-api37 keeps the owner''s Copilot profile, and no script signs Kestrel in there (AGENTS.md)*'
    }

    It 'never deletes a profile and never signs Kestrel in on kestrel-api37' {
        # Outside its comments, the script names no hook that deletes a profile, and starts no sign-in on the emulator.
        $code = @($tokens | Where-Object { $_.Kind -ne 'Comment' } | ForEach-Object { $_.Text }) -join ' '
        $code | Should -Not -Match 'provider-delete'
        $code | Should -Not -Match 'Delete profile'
        $tree.Extent.Text | Should -Not -Match 'Open-CopilotSignIn \$EmulatorSerial'
    }

    It 'finds the centre of an element''s bounds' {
        Get-BoundsCentre '100,200,300,400' | Should -BeExactly '200 300'
        Get-BoundsCentre 'none' | Should -BeNullOrEmpty
    }

    It 'skips the live cases in an unattended run, also on a kept Copilot profile, but not in a dry run' {
        Get-LiveSkipReason -DryRun $false -Unattended $true -DefaultProfile 'copilot' | Should -BeExactly 'skipped: an unattended run spends none of the owner''s AI Credits'
        Get-LiveSkipReason -DryRun $true -Unattended $true -DefaultProfile '' | Should -BeNullOrEmpty
        Get-LiveSkipReason -DryRun $false -Unattended $false -DefaultProfile 'copilot' | Should -BeNullOrEmpty
        Get-LiveSkipReason -DryRun $false -Unattended $false -DefaultProfile 'none' -DeviceName 'the A71' | Should -BeExactly 'skipped: the A71 has no default Copilot profile'
    }

    It 'taps only while Kestrel is the app in front' {
        Test-KestrelInFront ([ordered]@{ count = '1'; app = 'dev.kestrel' }) | Should -BeTrue
        Test-KestrelInFront ([ordered]@{ count = '1'; app = 'com.android.chrome' }) | Should -BeFalse
        Test-KestrelInFront ([ordered]@{ count = '1'; app = 'none' }) | Should -BeFalse
        Test-KestrelInFront ([ordered]@{ count = '1' }) | Should -BeFalse
    }

    It 'waits until Kestrel''s accessibility service is bound, and fails the step when it stays unbound' {
        Mock Start-Sleep { }
        $EmulatorSerial = 'emulator-5554'
        # Kestrel's process still starts, then its service is not bound yet, then it is.
        $answers = [System.Collections.Generic.Queue[string]]::new([string[]]@('error', 'screen=off testMode=on', 'screen=on testMode=on numbers=0'))
        Mock Invoke-Hook {
            $next = $answers.Dequeue()
            if ($next -eq 'error') { throw 'Kestrel did not answer.' }
            $next
        } -ParameterFilter { $Command -eq 'state' }

        { Wait-ServiceBound 'emulator-5554' } | Should -Not -Throw
        Should -Invoke Invoke-Hook -Times 3 -Exactly

        Mock Invoke-Hook { 'screen=off testMode=on' } -ParameterFilter { $Command -eq 'state' }
        { Wait-ServiceBound 'emulator-5554' -Seconds 0 } | Should -Throw "Kestrel's accessibility service on kestrel-api37 was not bound after 0 s."
    }
}
