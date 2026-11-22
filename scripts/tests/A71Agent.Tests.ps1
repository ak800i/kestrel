# Tests of the helpers in scripts\a71-agent.ps1, the two sittings of sub-project 5. The script needs devices, so the
# tests load only its helper functions from the script's syntax tree. scripts\test-scripts.ps1 runs these tests in
# Windows PowerShell 5.1 and in PowerShell 7. They use a fake configuration with a fake PIN and never open the owner's.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

BeforeAll {
    . "$PSScriptRoot\..\lib\Session.ps1"
    $tokens = $null
    $errors = $null
    $tree = [System.Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot\..\a71-agent.ps1", [ref]$tokens, [ref]$errors)
    $helpers = @('Get-SessionSteps', 'Get-AgentConfig', 'Read-AgentRecord', 'Set-AgentRecord', 'Get-GoogleAccountCount', 'Get-VersionName',
        'Test-PackageListed', 'Test-NetworkValidated', 'Test-HotspotOn', 'Get-TouchRuleFromProbe', 'Get-OcrMeasures', 'Get-BoundsCentre')
    $functions = $tree.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -in $helpers
        }, $true)
    foreach ($function in $functions) { . ([scriptblock]::Create($function.Extent.Text)) }
}

Describe 'The helpers of the sittings of sub-project 5' {
    It 'parses the script, and finds every helper' {
        $errors | Should -BeNullOrEmpty
        @($functions).Count | Should -Be $helpers.Count
    }

    It 'takes the steps also as one text separated by commas, and refuses an unknown one' {
        $names = @('prepare', 'owner', 'speech', 'end')

        Get-SessionSteps -Step @('owner,speech') -Names $names | Should -Be @('owner', 'speech')
        @(Get-SessionSteps -Step @() -Names $names).Count | Should -Be 0
        { Get-SessionSteps -Step @('owner,radio') -Names $names } | Should -Throw 'Unknown steps: radio.*'
    }

    It 'reads the owner''s answers without the key pin, and never puts the PIN or a value into a message (review C3-I1)' {
        $path = Join-Path $TestDrive 'a71-config.json'
        $fake = [ordered]@{ pin = '97531'; whatsappOwnName = 'Pera Test'; gmailAddress = 'pera.test@example.com'; homePlace = 'Belgrade Fortress'; testLocation = '44.8100,20.4600'; testStreet = 'Knez Mihailova' }
        [IO.File]::WriteAllText($path, ($fake | ConvertTo-Json), [Text.UTF8Encoding]::new($false))

        $config = Get-AgentConfig $path

        $config.whatsappOwnName | Should -Be 'Pera Test'
        $config.testLocation | Should -Be '44.8100,20.4600'
        $config.PSObject.Properties['pin'] | Should -BeNullOrEmpty
        ($config | ConvertTo-Json) | Should -Not -Match '97531'
        ($config | Out-String) | Should -Not -Match '97531'

        $fake.Remove('gmailAddress')
        $fake.testStreet = ''
        [IO.File]::WriteAllText($path, ($fake | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
        $message = try { Get-AgentConfig $path; '' } catch { $_.Exception.Message }
        $message | Should -BeExactly 'corpus\owner\a71-config.json lacks the keys gmailAddress, testStreet, which the sittings of sub-project 5 need.'

        $fake.gmailAddress = 'pera.test@example.com'
        $fake.testStreet = 'Knez Mihailova'
        $fake.testLocation = 'Belgrade'
        [IO.File]::WriteAllText($path, ($fake | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
        $message = try { Get-AgentConfig $path; '' } catch { $_.Exception.Message }
        $message | Should -Match 'testLocation'
        $message | Should -Not -Match '97531|Belgrade'
    }

    It 'keeps the record of a sitting in its file, value by value' {
        $path = Join-Path $TestDrive 'record.json'

        @((Read-AgentRecord $path).PSObject.Properties).Count | Should -Be 0
        Set-AgentRecord $path 'touchBranch' 'B'
        Set-AgentRecord $path 'hotspot' 'limit'
        Set-AgentRecord $path 'touchBranch' 'A'

        $record = Read-AgentRecord $path
        $record.touchBranch | Should -Be 'A'
        $record.hotspot | Should -Be 'limit'
    }

    It 'counts the Google accounts by their type only, and reads versions and packages' {
        $accounts = @('Accounts: 3', '    Account {name=someone@example.com, type=com.google}', '    Account {name=second@example.com, type=com.google}', '    Account {name=x, type=com.samsung.android.mobileservice}')

        Get-GoogleAccountCount $accounts | Should -Be 2
        Get-VersionName @('Packages:', '    versionCode=763200000 minSdk=29', '    versionName=145.0.7632.0', '    versionName=1.0') | Should -Be '145.0.7632.0'
        Get-VersionName @('Unable to find package') | Should -Be 'none'
        Test-PackageListed @('package:com.waze') 'com.waze' | Should -BeTrue
        Test-PackageListed @('package:com.waze.extra') 'com.waze' | Should -BeFalse
    }

    It 'finds a validated Wi-Fi network and a running hotspot in Android''s dumps' {
        $validated = '  NetworkAgentInfo{network{100}  handle{429}  ni{WIFI CONNECTED extra: } nc{[ Transports: WIFI Capabilities: NOT_METERED&INTERNET&NOT_RESTRICTED&TRUSTED&NOT_VPN&VALIDATED&NOT_ROAMING ]}}'
        $partial = '  NetworkAgentInfo{network{101}  handle{430}  ni{WIFI CONNECTED extra: } nc{[ Transports: WIFI Capabilities: NOT_METERED&INTERNET&NOT_RESTRICTED ]}}'

        Test-NetworkValidated @('Active default network: 100', $validated) | Should -BeTrue
        Test-NetworkValidated @($partial) | Should -BeFalse
        Test-HotspotOn @('Tether state:', '    swlan0 - TetheredState - lastError = 0') | Should -BeTrue
        Test-HotspotOn @('Tether state:', '    wlan1 - LocalHotspotState - lastError = 0') | Should -BeTrue
        Test-HotspotOn @('Tether state:', '    wlan1 - AvailableState - lastError = 0') | Should -BeFalse
    }

    It 'chooses the touch rule from the probe''s lines, and reads the OCR measures' {
        $quiet = @('I KestrelProbe: case=click sdk=33 touches=0 device=none source=none tool=none flags=none delayMs=none', 'I KestrelProbe: case=gesture sdk=33 touches=1 device=-1 source=4098 tool=1 flags=0x0 delayMs=12')
        $echo = @('I KestrelProbe: case=click sdk=37 touches=1 device=0 source=4098 tool=1 flags=0x800 delayMs=40')
        $ocr = @('I KestrelOcr: ocr image=full limit=960 threads=2 loadMs=410 detectMs=520 recogniseMs=830 lines=12 pssKb=198000', 'I KestrelOcr: something else')

        Get-TouchRuleFromProbe $quiet $true | Should -Be 'NONE'
        Get-TouchRuleFromProbe $echo $true | Should -Be 'CREDITS'
        Get-TouchRuleFromProbe @('nothing') $true | Should -BeNullOrEmpty
        # A probe that failed checked no echo's kind or delay, so its lines choose no rule.
        Get-TouchRuleFromProbe $echo $false | Should -BeNullOrEmpty
        Get-TouchRuleFromProbe $quiet $false | Should -BeNullOrEmpty
        @(Get-OcrMeasures $ocr) | Should -Be @('image=full limit=960 threads=2 loadMs=410 detectMs=520 recogniseMs=830 lines=12 pssKb=198000')
        Get-BoundsCentre '100,200,300,400' | Should -BeExactly '200 300'
        Get-BoundsCentre 'none' | Should -BeNullOrEmpty
    }
}
