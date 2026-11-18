# Tests of the helpers in scripts\a71-commands.ps1, the A71 session of the instant commands. The script needs a device,
# so the tests load only its helper functions from the script's syntax tree. scripts\test-scripts.ps1 runs these tests in
# Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

BeforeAll {
    $tokens = $null
    $errors = $null
    $tree = [System.Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot\..\a71-commands.ps1", [ref]$tokens, [ref]$errors)
    $helpers = @('Get-SessionSteps', 'Test-SimAbsent', 'Get-UserSummary', 'Get-NewScreenshotIds', 'Format-CheckResult', 'Get-Median')
    $functions = $tree.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -in $helpers
        }, $true)
    foreach ($function in $functions) { . ([scriptblock]::Create($function.Extent.Text)) }
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
        Test-SimAbsent -State '' | Should -BeTrue
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

        Format-CheckResult -Name 'turn off Wi-Fi' -Owner 'y' -State $state | Should -BeExactly 'turn off Wi-Fi: owner=y kind=Radio outcome=done'
        Format-CheckResult -Name 'mute' -Owner 'n' -State $null | Should -BeExactly 'mute: owner=n kind=- outcome=-'
        Get-Median @(300, 100, 200) | Should -Be 200
        Get-Median @(400, 100, 300, 200) | Should -Be 200
        Get-Median @() | Should -Be 0
    }
}
