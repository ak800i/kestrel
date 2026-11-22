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
        'Test-PackageListed', 'Test-NetworkValidated', 'Test-HotspotOn', 'Get-TouchRuleFromProbe', 'Get-OcrMeasures', 'Get-BoundsCentre',
        'ConvertFrom-TaskLine', 'Get-CreditTotal', 'Select-TestName', 'Get-CacheShares', 'Get-CacheFields', 'Get-StandInTask', 'Format-Number', 'Get-RunSummary',
        'Get-SittingRuns')
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

    It 'reads task-check.ps1''s run lines from the results, and adds up their AI Credits' {
        $lines = @(
            "2026-12-01 10:00:00`ta71-runs`ttelegram-late on the A71, 3 runs"
            "2026-12-01 10:03:00`ttask-check`ttask=telegram-late run=1 result=pass end=FINISHED check=pass steps=9 workMs=61000 credits=1.25 model=gpt-6-luna in=42000 out=900 cacheRead=30000 cacheWrite=0 reason=ok"
            "2026-12-01 10:06:00`ttask-check`ttask=telegram-late run=2 result=fail end=STUCK check=fail steps=14 workMs=95000 credits=2.10 model=gpt-6-luna in=60000 out=1200 cacheRead=0 cacheWrite=0 reason=end"
        )
        $runs = @($lines | ForEach-Object { ConvertFrom-TaskLine $_ } | Where-Object { $_ })

        $runs.Count | Should -Be 2
        $runs[1]['end'] | Should -Be 'STUCK'
        Get-CreditTotal $runs | Should -Be 3.35
        Get-CreditTotal @() | Should -Be 0

        # Each run keeps the device of the line before it, so that the comparison reads only the emulator's runs.
        $resultsFile = Join-Path $TestDrive 'results.txt'
        [IO.File]::WriteAllLines($resultsFile, [string[]]($lines + @(
                    "2026-12-01 10:10:00`temulator-runs`thotspot on kestrel-api37, 3 runs"
                    "2026-12-01 10:12:00`ttask-check`ttask=hotspot run=1 result=pass end=FINISHED check=pass steps=5 workMs=30000 credits=0.50 model=gpt-6-luna in=20000 out=500 cacheRead=0 cacheWrite=0 reason=ok")))
        $read = @(Get-SittingRuns)
        $read.Count | Should -Be 3
        @($read | ForEach-Object { $_['device'] }) | Should -Be @('the A71', 'the A71', 'kestrel-api37')
    }

    It 'chooses a test name that has no row in the owner''s Telegram, and none when every name has one' {
        Select-TestName 'Ana' @{ Ana = 0; Mira = 1 } | Should -Be 'Ana'
        Select-TestName 'Ana' @{ Ana = 1; Mira = 1; Zora = 0 } | Should -Be 'Zora'
        Select-TestName 'John' @{ John = 2 } @('Mira') | Should -Be 'Zora'
        Select-TestName 'Ana' @{ Ana = 1; Mira = 1; Zora = 1; Ivo = 1; Teo = 1 } | Should -BeNullOrEmpty
    }

    It 'gives each model''s share of cached input, the fields to switch off, and a summary per task and model' {
        $runs = @(
            [ordered]@{ task = 'firefox-wiki'; result = 'pass'; steps = '6'; workMs = '40000'; credits = '0.80'; model = 'gpt-6-luna'; in = '30000'; cacheRead = '12000' }
            [ordered]@{ task = 'firefox-wiki'; result = 'fail'; steps = '10'; workMs = '80000'; credits = '1.20'; model = 'gpt-6-luna'; in = '50000'; cacheRead = '20000' }
            [ordered]@{ task = 'firefox-wiki'; result = 'pass'; steps = '7'; workMs = '50000'; credits = '1.00'; model = 'claude-haiku-5.5'; in = '40000'; cacheRead = '0' }
            [ordered]@{ task = 'hotspot'; result = 'skipped'; steps = '0'; workMs = '0'; credits = '0.00'; model = 'none'; in = '0'; cacheRead = '0' }
        )

        $shares = @(Get-CacheShares $runs)
        $shares.Count | Should -Be 2
        $shares[0].Share | Should -Be 40
        $shares[1].Share | Should -Be 0
        Get-CacheFields 'gpt-6-luna' | Should -Be @('responsesExplicit')
        Get-CacheFields 'claude-haiku-5.5' | Should -Be @('chatCopilotMarks', 'anthropicMarks')
        @(Get-CacheFields 'gemini-3.8-flash').Count | Should -Be 0
        @(Get-RunSummary $runs) | Should -Be @(
            'task=firefox-wiki model=gpt-6-luna passed=1 of 2 steps=8 seconds=60 credits=2'
            'task=firefox-wiki model=claude-haiku-5.5 passed=1 of 1 steps=7 seconds=50 credits=1'
            'task=hotspot model=none passed=0 of 0 steps=0 seconds=0 credits=0')
        Format-Number 1.26 1 | Should -Be '1.3'
    }

    It 'runs a stand-in with its scripted model in a dry run, and has none for Waze' {
        (Get-StandInTask 'telegram-late').Task | Should -Be 'stand-in-messenger-late'
        (Get-StandInTask 'firefox-wiki').Script | Should -Be 'tasks\stand-in-firefox.json'
        (Get-StandInTask 'inject-07').Script | Should -Be 'tasks\hostile.json'
        (Get-StandInTask 'inject-07').Task | Should -Be 'inject-07'
        Get-StandInTask 'waze-home' | Should -BeNullOrEmpty
    }
}
