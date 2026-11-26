# Tests that the scripts that turn Kestrel's accessibility service off do it through Disconnect-AccessibilityService,
# which waits until Android's accessibility manager has applied the off, never with a plain write of the setting and a
# sleep (plan 7E, the whole-plan review's M1). The scripts need a device, so the tests load only their Set-KestrelService
# from the script's syntax tree. scripts\test-scripts.ps1 runs these tests in Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

Describe 'Set-KestrelService of <Script> turning the service off' -ForEach @(@{ Script = 'listen-check.ps1' }, @{ Script = 'spike-s1.ps1' }) {
    BeforeAll {
        $tokens = $null
        $errors = $null
        $tree = [System.Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot\..\$Script", [ref]$tokens, [ref]$errors)
        $function = $tree.Find({
                param($node)
                $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Set-KestrelService'
            }, $true)
        . ([scriptblock]::Create($function.Extent.Text))
        # The phone and the hooks, which the tests mock.
        function Disconnect-AccessibilityService([string]$Serial) { }
        function Connect-AccessibilityService([string]$Serial, [string]$Services) { }
        function Invoke-Checked([string]$Command) { }
        function Invoke-Hook { }
        $Serial = 'emulator-5554'
    }

    BeforeEach {
        Mock Invoke-Checked { }
        Mock Invoke-Hook { 'screen=off listening=off' }
        Mock Start-Sleep { }
    }

    It 'parses the script and finds the function' {
        $errors | Should -BeNullOrEmpty
        $function | Should -Not -BeNullOrEmpty
    }

    It 'waits for Android''s off through Disconnect-AccessibilityService, with no write of its own and no sleep' {
        Mock Disconnect-AccessibilityService { $true }

        Set-KestrelService $false

        Should -Invoke Disconnect-AccessibilityService -Times 1 -Exactly -ParameterFilter { $Serial -eq 'emulator-5554' }
        Should -Invoke Invoke-Checked -Times 0 -Exactly
        Should -Invoke Start-Sleep -Times 0 -Exactly
    }

    It 'stops when Android did not turn the service off' {
        Mock Disconnect-AccessibilityService { $false }

        { Set-KestrelService $false } | Should -Throw "Android did not turn Kestrel's accessibility service off*"
    }
}
