# Tests of the helpers in scripts\a71-providers.ps1, the owner's session of sub-project 4. The script needs devices, so
# the tests load only its helper functions from the script's syntax tree. scripts\test-scripts.ps1 runs these tests in
# Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

BeforeAll {
    . "$PSScriptRoot\..\lib\Session.ps1"
    $tokens = $null
    $errors = $null
    $tree = [System.Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot\..\a71-providers.ps1", [ref]$tokens, [ref]$errors)
    $helpers = @('Get-SessionSteps', 'Get-RetryOverride', 'ConvertFrom-StandInAnswer')
    $functions = $tree.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -in $helpers
        }, $true)
    foreach ($function in $functions) { . ([scriptblock]::Create($function.Extent.Text)) }
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

    It 'reads the stand-in''s answer with the kind of the default profile' {
        $state = ConvertFrom-StandInAnswer 'standin=off profile=copilot'

        $state['standin'] | Should -Be 'off'
        $state['profile'] | Should -Be 'copilot'
        { ConvertFrom-StandInAnswer 'standin=on' } | Should -Throw 'Not an answer of provider-standin*'
    }
}
