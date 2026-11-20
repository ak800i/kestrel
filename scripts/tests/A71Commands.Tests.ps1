# Tests of the helpers in scripts\a71-commands.ps1, the A71 session of the instant commands. The script needs a device,
# so the tests load only its helper functions from the script's syntax tree. scripts\test-scripts.ps1 runs these tests in
# Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

BeforeAll {
    . "$PSScriptRoot\..\lib\Session.ps1"
    . "$PSScriptRoot\..\lib\Corpus.ps1"
    $tokens = $null
    $errors = $null
    $tree = [System.Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot\..\a71-commands.ps1", [ref]$tokens, [ref]$errors)
    $helpers = @('Get-SessionSteps', 'Test-SimAbsent', 'Get-UserSummary', 'Test-SecureFolder', 'Get-NewScreenshotIds', 'Get-Percentile',
        'Get-GridCells', 'ConvertFrom-Magnification', 'Get-VolumeRange', 'Select-InstalledApp', 'New-AirTotals', 'Add-AirResult',
        'Format-AirTotals', 'Request-OwnerItem', 'Complete-Restore', 'Invoke-Restore', 'Get-PassClip', 'Add-PassCount', 'Test-Command')
    $functions = $tree.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -in $helpers
        }, $true)
    foreach ($function in $functions) { . ([scriptblock]::Create($function.Extent.Text)) }
    # Test-Command runs a check and writes its result through these, which the tests mock.
    function Invoke-AutoCheck($Name, $Text, $Verify, $Before, $Answer, $Respond, $RespondAt, $RespondDelay, $Send, $Clip, $Play, $TimeoutSeconds) { }
    function Add-Result([string]$StepName, [string]$Text) { }
    # Request-OwnerItem beeps through this; the tests stay silent.
    function Invoke-Beep { }
}

Describe 'The helpers of the A71 session of the instant commands' {
    It 'parses the script, and finds every helper' {
        $errors | Should -BeNullOrEmpty
        @($functions).Count | Should -Be $helpers.Count
    }

    It 'takes the steps also as one text separated by commas, and refuses one that it does not know' {
        $names = @('install', 'owner', 'calls')

        Get-SessionSteps -Step @('owner,calls') -Names $names | Should -Be @('owner', 'calls')
        Get-SessionSteps -Step @('install', ' calls ') -Names $names | Should -Be @('install', 'calls')
        @(Get-SessionSteps -Step @() -Names $names).Count | Should -Be 0
        { Get-SessionSteps -Step @('screen,radio') -Names @('screen', 'radios') } | Should -Throw 'Unknown steps: radio.*'
    }

    It 'tells from the SIM state whether a SIM card is in the phone' {
        Test-SimAbsent -State 'ABSENT' | Should -BeTrue
        Test-SimAbsent -State 'ABSENT,ABSENT' | Should -BeTrue
        Test-SimAbsent -State 'ABSENT,' | Should -BeTrue
        # A phone that does not set the property, or a read that gave nothing, does not say that none is there.
        Test-SimAbsent -State '' | Should -BeFalse
        Test-SimAbsent -State 'LOADED,ABSENT' | Should -BeFalse
        # A phone with a SIM card can say NOT_READY while its radio starts.
        Test-SimAbsent -State 'NOT_READY' | Should -BeFalse
    }

    It 'keeps the users'' IDs, types and flags, never their names, and finds Secure Folder among them' {
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
        Test-SecureFolder $summary | Should -BeTrue
        Test-SecureFolder @($summary[0]) | Should -BeFalse
    }

    It 'finds the new screenshots and leaves the owner''s other images' {
        $rows = @(
            'Row: 0 _id=120, _display_name=Screenshot_20261120_101500_One UI Home.png'
            'Row: 1 _id=121, _display_name=IMG_20261120_101501.jpg'
        )

        @(Get-NewScreenshotIds -Rows $rows) | Should -Be @(120)
        @(Get-NewScreenshotIds -Rows @('No result found.')).Count | Should -Be 0
    }

    It 'reads the grid''s size, the magnification and a stream''s range, and takes the percentiles of some times' {
        Get-GridCells '8x4' | Should -Be 32
        Get-GridCells 'none' | Should -Be 0
        $zoom = ConvertFrom-Magnification '2.50@540,1200'
        $zoom.Scale | Should -Be 2.5
        $zoom.X | Should -Be 540
        (ConvertFrom-Magnification 'none').Scale | Should -Be 1
        $volume = Get-VolumeRange @('[V] volume is 7 in range [1..15]')
        $volume.Index | Should -Be 7
        $volume.Min | Should -Be 1
        $volume.Max | Should -Be 15
        { Get-VolumeRange @('error') } | Should -Throw 'Not an answer of cmd media_session volume*'
        Get-Percentile @(300, 100, 200) 0.5 | Should -Be 200
        Get-Percentile @(1..10) 0.9 | Should -Be 9
        Get-Percentile @() 0.5 | Should -Be 0
    }

    It 'chooses the first app of the candidates that the device lists' {
        $listed = @('package:com.google.android.deskclock', 'package:com.google.android.deskclock.extra')

        Select-InstalledApp -Listed $listed -Candidates @('com.sec.android.app.clockpackage', 'com.google.android.deskclock') | Should -BeExactly 'com.google.android.deskclock'
        Select-InstalledApp -Listed @() -Candidates @('com.sec.android.app.clockpackage') | Should -BeNullOrEmpty
    }

    It 'adds up the corpus pass of one voice, with the ids of the lines that missed, and no words' {
        $totals = New-AirTotals
        Add-AirResult -Totals $totals -Id 'c001' -Answer ([ordered]@{ matched = 'true'; words = '2'; errors = '0'; stray = '1'; split = '0'; closed = '1000'; transcribed = '1700'; right = '1' })
        Add-AirResult -Totals $totals -Id 'c002' -Answer ([ordered]@{ matched = 'true'; words = '2'; errors = '1'; stray = '0'; split = '1'; closed = '5000'; transcribed = '5900'; right = '0' })
        Add-AirResult -Totals $totals -Id 'c041' -Answer ([ordered]@{ matched = 'false'; words = '1'; errors = '1'; stray = '0'; split = '0'; closed = '0'; transcribed = '0'; right = '-' })
        Add-AirResult -Totals $totals -Id 'c003' -Answer $null

        Format-AirTotals 'kokoro' $totals | Should -BeExactly ('kokoro: 4 clips, 2 matched; word error rate 40.0 % (2 errors in 5 words); commands right 1 of 2 (50.0 %); ' +
            '1 stray and 1 split utterances; transcription median 700 ms, 90th percentile 700 ms; not matched or wrong: c002, c041, c003')
    }

    Context 'A check on the text pass and on the air pass' {
        BeforeEach {
            $DryRun = $false
            $generated = 'C:\corpus\generated'
            $clipTexts = @{ c004 = 'Go home.' }
            $passCounts = [ordered]@{}
            $play = { }
            # The clips of the air pass exist.
            Mock Test-Path { $true } -ParameterFilter { $LiteralPath -like 'C:\corpus\generated\*' }
            Mock Add-Result { }
            Mock Invoke-AutoCheck { [pscustomobject]@{ Passed = $true; Line = "$Name [x]: role=command kind=Global outcome=done check=pass" } }
        }

        It 'sends the text on the text pass, plays the clip on the air pass, and skips a check without a clip there' {
            $pass = 'text'
            Test-Command screen 'go home' c004 { $true }
            $pass = 'kokoro'
            Test-Command screen 'go home' c004 { $true }
            Test-Command screen 'long press {label}' '' -Send { } -Verify { $true }

            Should -Invoke Invoke-AutoCheck -Times 1 -Exactly -ParameterFilter { $Text -eq 'go home' -and -not $Clip }
            Should -Invoke Invoke-AutoCheck -Times 1 -Exactly -ParameterFilter { $Clip.Text -eq 'Go home.' -and $Clip.Path -eq 'C:\corpus\generated\kokoro\c004.wav' -and $Clip.Voice -eq 'kokoro' }
            Should -Invoke Invoke-AutoCheck -Times 2 -Exactly
            Should -Invoke Add-Result -Times 2 -Exactly -ParameterFilter { $StepName -eq 'screen' }
            $passCounts['text'].Run | Should -Be 1
            $passCounts['kokoro'].Passed | Should -Be 1
            $pass = 'text'
        }

        It 'writes a failed check''s first line, and counts it as failed' {
            $pass = 'text'
            Mock Invoke-AutoCheck { throw "error: Kestrel's accessibility service is off`nmore" }

            Test-Command screen 'show numbers' c014 { $true }

            Should -Invoke Add-Result -Times 1 -Exactly -ParameterFilter { $Text -eq "show numbers [text]: failed: error: Kestrel's accessibility service is off" }
            $passCounts['text'].Run | Should -Be 1
            $passCounts['text'].Passed | Should -Be 0
        }
    }

    It 'asks the owner for his item only when he is there' {
        Mock Read-Host { 'q' }
        Mock Write-Host { }
        $DryRun = $false
        $Unattended = $true

        Request-OwnerItem 'Unlock the A71.' | Should -BeExactly 'skip'
        Should -Invoke Read-Host -Times 0 -Exactly

        $Unattended = $false
        $DryRun = $true
        Request-OwnerItem 'Unlock the A71.' | Should -BeExactly 'go'
        Should -Invoke Read-Host -Times 0 -Exactly

        $DryRun = $false
        { Request-OwnerItem 'Unlock the A71.' } | Should -Throw 'The owner stopped the session.'
        Should -Invoke Read-Host -Times 1 -Exactly
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
