# Tests of the helpers in scripts\provider-check.ps1. The script needs a device, so the tests load only its helper
# functions from the script's syntax tree. scripts\test-scripts.ps1 runs these tests in Windows PowerShell 5.1 and in
# PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

BeforeAll {
    $tokens = $null
    $errors = $null
    $tree = [System.Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot\..\provider-check.ps1", [ref]$tokens, [ref]$errors)
    $helpers = @('Get-LivePlan', 'ConvertFrom-LiveAnswer', 'Test-LivePassed', 'Test-ModelMissing', 'Format-LiveLine')
    $functions = $tree.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -in $helpers
        }, $true)
    foreach ($function in $functions) { . ([scriptblock]::Create($function.Extent.Text)) }
}

Describe 'The helpers of the live cases of sub-project 4' {
    It 'parses the script, and finds every helper' {
        $errors | Should -BeNullOrEmpty
        @($functions).Count | Should -Be $helpers.Count
    }

    It 'plans each model''s cases, JSON actions with the lightweight model only, and the allowance last' {
        $plan = @(Get-LivePlan -Case '' -Model '')

        $plan.Count | Should -Be 14
        $plan[0].Case | Should -Be 'text'
        $plan[0].Models | Should -Be @('GPT-6 Luna', 'Claude Haiku 5.5')
        @($plan | Where-Object { $_.Case -eq 'json' }).Count | Should -Be 1
        ($plan | Where-Object { $_.Case -eq 'image' } | Select-Object -Last 1).Models | Should -Be @('Gemini 3.8 Flash', 'Gemini 4 Hydrogen')
        $plan[-1].Case | Should -Be 'allowance'
        $single = @(Get-LivePlan -Case 'tool' -Model 'Claude Haiku 5.5')
        $single.Count | Should -Be 1
        $single[0].Models | Should -Be @('Claude Haiku 5.5')
    }

    It 'reads an answer and passes it only when it matched with a known price' {
        $answer = ConvertFrom-LiveAnswer 'case=tool outcome=ok matched=true style=ANTHROPIC input=210 output=12 estimated=false credits=0.110 priceKnown=true millis=2310'

        $answer['style'] | Should -Be 'ANTHROPIC'
        Test-LivePassed $answer | Should -BeTrue
        Test-LivePassed (ConvertFrom-LiveAnswer 'case=tool outcome=ok matched=false priceKnown=true') | Should -BeFalse
        Test-LivePassed (ConvertFrom-LiveAnswer 'case=text outcome=ok matched=true priceKnown=false') | Should -BeFalse
        Test-LivePassed (ConvertFrom-LiveAnswer 'case=allowance outcome=ok quotas=2 premium=70 chat=100 millis=410') | Should -BeTrue
        { ConvertFrom-LiveAnswer 'running' } | Should -Throw 'Not an answer of provider-live*'
    }

    It 'tries the next model only when the subscription does not offer one, and writes one line per case' {
        Test-ModelMissing (ConvertFrom-LiveAnswer 'case=text outcome=failed error=the-subscription-does-not-offer-this-model') | Should -BeTrue
        Test-ModelMissing (ConvertFrom-LiveAnswer 'case=text outcome=failed error=Timeout') | Should -BeFalse

        Format-LiveLine -Case 'text' -Model 'GPT-6 Luna' -Answer (ConvertFrom-LiveAnswer 'case=text outcome=ok matched=true millis=900') |
            Should -BeExactly 'text (GPT-6 Luna): ok matched=true millis=900'
        Format-LiveLine -Case 'allowance' -Model '' -Answer (ConvertFrom-LiveAnswer 'case=allowance outcome=failed error=Unreachable') |
            Should -BeExactly 'allowance: failed error=Unreachable'
        $skipped = ConvertFrom-LiveAnswer 'case=allowance outcome=skipped standin=on'
        Test-LivePassed $skipped | Should -BeTrue
        Format-LiveLine -Case 'allowance' -Model '' -Answer $skipped | Should -BeExactly 'allowance: skipped standin=on'
    }
}
