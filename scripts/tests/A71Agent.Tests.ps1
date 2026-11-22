# Tests of the helpers in scripts\a71-agent.ps1, the two sittings of sub-project 5. The script needs devices, so the
# tests load only its helper functions from the script's syntax tree. scripts\test-scripts.ps1 runs these tests in
# Windows PowerShell 5.1 and in PowerShell 7. They use a fake configuration with a fake PIN and never open the owner's.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

BeforeAll {
    . "$PSScriptRoot\..\lib\Kestrel.ps1"
    . "$PSScriptRoot\..\lib\Session.ps1"
    . "$PSScriptRoot\..\lib\AutoCheck.ps1"
    . "$PSScriptRoot\..\lib\AgentChecks.ps1"
    $tokens = $null
    $errors = $null
    $tree = [System.Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot\..\a71-agent.ps1", [ref]$tokens, [ref]$errors)
    $helpers = @('Get-SessionSteps', 'Get-AgentConfig', 'Read-AgentRecord', 'Set-AgentRecord', 'Get-GoogleAccountCount', 'Get-VersionName',
        'Test-PackageListed', 'Test-NetworkValidated', 'Test-HotspotOn', 'Get-TouchRuleFromProbe', 'Get-OcrMeasures', 'Get-BoundsCentre',
        'ConvertFrom-TaskLine', 'Get-CreditTotal', 'Select-TestName', 'Get-CacheShares', 'Get-CacheFields', 'Get-StandInTask', 'Format-Number', 'Get-RunSummary',
        'Get-SittingRuns')
    # The step helpers of the fix round before the sittings, and the helpers that they call, which the tests mock.
    $helpers += @('Get-DeviceName', 'Register-Restore', 'Complete-Restore', 'Invoke-Device', 'Invoke-DeviceHook', 'Invoke-ScreenTap', 'Get-ScreenCount',
        'Open-App', 'Unlock-Device', 'Set-HotspotSwitch', 'Wait-Validated', 'Add-Result', 'Test-Installed', 'Save-AgentTree', 'Read-DeviceText',
        'Format-Failure', 'Invoke-TelegramRecording', 'Get-GmailDraftCount', 'Invoke-GmailCompose', 'Restore-Hotspot', 'Register-HotspotRestore',
        'Enable-WifiSharing', 'Register-WifiSharingRestore', 'Invoke-TaskRuns', 'Invoke-TaskCheck', 'Wait-LiveTask', 'Get-SittingResultFiles', 'Add-SittingResults')
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
        $owner = $TestDrive
        $recordFile = Join-Path $TestDrive 'no-record.json'
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

Describe 'The steps of the sittings on the owner''s A71 (the fix round before them)' {
    BeforeEach {
        $EmulatorSerial = 'emulator-5554'
        $A71 = 'R58N66RVD7Z'
        $DryRun = $false
        $pending = [System.Collections.Generic.List[object]]::new()
        $config = [pscustomobject]@{ SpeakerTargetDb = -26.0; whatsappOwnName = 'Pera Test'; gmailAddress = 'pera.test@example.com'; homePlace = 'Belgrade Fortress'; testLocation = '44.8100,20.4600'; testStreet = 'Knez Mihailova' }
        $owner = $TestDrive
        $stamp = '20261122-2000'
        $recordFile = Join-Path $TestDrive "record-$([guid]::NewGuid().ToString('N')).json"
        $results = [System.Collections.Generic.List[string]]::new()
        Mock Add-Result { $results.Add("${StepName}: $Text") }
        Mock Start-Sleep { }
    }

    It 'writes a line and a record key when Telegram''s Saved Messages does not open (task-5c-9-review M1)' {
        Mock Open-App { }
        Mock Invoke-Device { }
        Mock Save-AgentTree { "$Name recorded (1 windows, 2 elements)" }
        Mock Invoke-DeviceHook { 'selfchat=yes rule=telegram' } -ParameterFilter { $Command -eq 'selfchat-check' }
        Mock Invoke-ScreenTap { $false } -ParameterFilter { $Label -eq 'Search' }

        $notes = @(Invoke-TelegramRecording $A71)

        $notes | Should -Contain 'Telegram''s search did not open, so Saved Messages is not recorded'
        (Read-AgentRecord $recordFile).telegramSelfChat | Should -Be 'not shown'

        Mock Invoke-ScreenTap { $true } -ParameterFilter { $Label -eq 'Search' }
        Mock Invoke-ScreenTap { $false } -ParameterFilter { $Label -eq 'Saved Messages' }
        @(Invoke-TelegramRecording $A71) | Should -Contain 'Telegram shows no element "Saved Messages", so it is not recorded'
        (Read-AgentRecord $recordFile).telegramSelfChat | Should -Be 'not shown'

        Mock Invoke-ScreenTap { $true } -ParameterFilter { $Label -eq 'Saved Messages' }
        @(Invoke-TelegramRecording $A71) | Should -Contain 'telegram-saved recorded (1 windows, 2 elements)'
        (Read-AgentRecord $recordFile).telegramSelfChat | Should -Be 'recorded'
    }

    It 'reads a recorded tree from the device as UTF-8 (task-5c-9-review M2)' {
        $pages = [System.Collections.Generic.List[int]]::new()
        $before = [Console]::OutputEncoding
        Mock Invoke-DeviceHook { 'tree=written windows=1 elements=2' } -ParameterFilter { $Command -eq 'agent-tree' }
        Mock Invoke-Device { $pages.Add([Console]::OutputEncoding.CodePage); '{"label":"x"}' } -ParameterFilter { $Command -like '*cat cache/agent-tree.json' }
        Mock Invoke-Device { } -ParameterFilter { $Command -like '*rm -f cache/agent-tree.json' }

        # A console whose code page is not UTF-8, as this computer's OEM code page is.
        [Console]::OutputEncoding = [Text.Encoding]::ASCII
        try {
            Save-AgentTree $A71 'gmail-compose' | Should -Be 'gmail-compose recorded (1 windows, 2 elements)'
            [Console]::OutputEncoding.CodePage | Should -Be 20127
        } finally {
            [Console]::OutputEncoding = $before
        }

        @($pages) | Should -Be @(65001)
        Get-Content -LiteralPath (Join-Path $TestDrive "trees\$stamp\a71-gmail-compose.json") -Raw | Should -Be '{"label":"x"}'
    }

    It 'counts Gmail''s drafts in its Drafts folder, and gives no count when the folder does not show or is too full (task-5c-9-review M3)' {
        Mock Invoke-Device { }
        Mock Open-App { }
        Mock Invoke-ScreenTap { $true }
        Mock Invoke-DeviceHook { 'count=2 number=none bounds=none first=none app=com.google.android.gm' } -ParameterFilter { $Command -eq 'screen-has' -and $With.Contains -eq 'Draft' }

        Get-GmailDraftCount $A71 | Should -Be 2
        Should -Invoke Invoke-ScreenTap -Times 1 -Exactly -ParameterFilter { $Label -eq 'Open navigation drawer' }
        Should -Invoke Invoke-ScreenTap -Times 1 -Exactly -ParameterFilter { $Label -eq 'Drafts' }

        Mock Invoke-DeviceHook { 'count=6 number=none bounds=none first=none app=com.google.android.gm' } -ParameterFilter { $Command -eq 'screen-has' }
        Get-GmailDraftCount $A71 | Should -BeNullOrEmpty
        Mock Invoke-DeviceHook { 'count=0 number=none bounds=none first=none app=com.android.settings' } -ParameterFilter { $Command -eq 'screen-has' }
        Get-GmailDraftCount $A71 | Should -BeNullOrEmpty
        Mock Invoke-ScreenTap { $false } -ParameterFilter { $Label -eq 'Open navigation drawer' }
        Get-GmailDraftCount $A71 | Should -BeNullOrEmpty
    }

    It 'discards Gmail''s compose screen, and calls it discarded only when Drafts did not grow (task-5c-9-review M3)' {
        $counts = [System.Collections.Generic.Queue[object]]::new([object[]]@(2, 2, 2, 3, 1, 1))
        Mock Get-GmailDraftCount { $counts.Dequeue() }
        Mock Invoke-Device { }
        Mock Invoke-ScreenTap { $true }
        Mock Get-ScreenCount { 0 }

        $kept = Invoke-GmailCompose -Device $A71 -Owner -Inside { param($Device) "inside on $Device" }
        $kept.Opened | Should -BeTrue
        $kept.Discarded | Should -BeTrue
        $kept.Inside | Should -Be "inside on $A71"
        $kept.Note | Should -Be 'Gmail''s compose screen on the A71: discarded; Drafts shows 2 as before'
        Should -Invoke Invoke-ScreenTap -Times 1 -Exactly -ParameterFilter { $Label -eq 'Discard' }

        $grew = Invoke-GmailCompose -Device $A71 -Owner -Inside { }
        $grew.Discarded | Should -BeFalse
        $grew.Note | Should -Be 'Gmail''s draft on the A71 may stay in Drafts (Drafts grew from 2 to 3); delete it by hand'

        Mock Invoke-ScreenTap { $false } -ParameterFilter { $Label -eq 'Discard' }
        (Invoke-GmailCompose -Device $A71 -Owner -Inside { }).Note | Should -Be 'Gmail''s draft on the A71 may stay in Drafts (the taps on "More options" and "Discard" missed); delete it by hand'
    }

    It 'opens no compose screen on the A71 when Drafts cannot be counted, and writes a fixed text when the mailto: command fails (task-5c-9-review M3, M5)' {
        Mock Get-GmailDraftCount { $null }
        Mock Invoke-Device { }

        $skipped = Invoke-GmailCompose -Device $A71 -Owner -Inside { }
        $skipped.Opened | Should -BeFalse
        $skipped.Note | Should -Be 'Gmail''s compose screen on the A71: not opened, because its Drafts could not be counted'
        Should -Invoke Invoke-Device -Times 0 -Exactly -ParameterFilter { $Command -like '*mailto:*' }

        Mock Get-GmailDraftCount { 1 }
        Mock Invoke-Device { throw "Could not run 'am start -a android.intent.action.SENDTO -d mailto:pera.test@example.com' on R58N66RVD7Z: error" } -ParameterFilter { $Command -like '*mailto:*' }
        $message = try { Invoke-GmailCompose -Device $A71 -Owner -Inside { } | Out-Null; '' } catch { $_.Exception.Message }
        $message | Should -BeExactly 'Gmail''s compose screen did not open on the A71.'
    }

    It 'leaves out the owner''s values and any e-mail address from an error''s line (task-5c-9-review M5, task-5c-10-review M1)' {
        $failure = { param($Message) try { throw $Message } catch { Format-Failure $_ } }

        & $failure "Could not run 'am start -d mailto:pera.test@example.com?subject=x -p com.google.android.gm' on R58N66RVD7Z: error`nmore" | Should -BeExactly "Could not run 'am start -d mailto:<address> -p com.google.android.gm' on R58N66RVD7Z: error"
        & $failure 'Waze did not find Belgrade Fortress near Knez Mihailova, nor PERA.TEST@EXAMPLE.COM' | Should -BeExactly 'Waze did not find <value> near <value>, nor <address>'
        & $failure 'The live session is still open on the A71.' | Should -BeExactly 'The live session is still open on the A71.'
    }

    It 'turns the hotspot off and Wi-Fi on through adb also when the A71 stays locked (task-5c-9-review M4)' {
        $tethering = [System.Collections.Generic.Queue[string]]::new([string[]]@('    swlan0 - TetheredState - lastError = 0', '    swlan0 - AvailableState - lastError = 0'))
        $commands = [System.Collections.Generic.List[string]]::new()
        Mock Unlock-Device { $false }
        Mock Invoke-Device { $commands.Add($Command); if ($Command -eq 'dumpsys tethering') { $tethering.Dequeue() } }
        Mock Wait-Validated { $true }

        Restore-Hotspot $A71

        @($commands) | Should -Be @('dumpsys tethering', 'cmd wifi stop-softap', 'dumpsys tethering', 'svc wifi enable')
    }

    It 'registers the hotspot''s restore before Enable-WifiSharing taps "Mobile Hotspot", and runs it afterwards (task-5c-9-review M4)' {
        $events = [System.Collections.Generic.List[string]]::new()
        Mock Register-Restore { $events.Add("register $What"); [pscustomobject]@{ What = $What } }
        Mock Complete-Restore { $events.Add("complete $($Restore.What)") }
        Mock Invoke-ScreenTap { $events.Add("tap $Label"); $true }
        Mock Invoke-Device { if ($Command -like 'input tap *') { $events.Add('tap the switch') } }
        Mock Invoke-DeviceHook { 'count=1 number=none bounds=0,0,10,10 first=none app=com.android.settings' } -ParameterFilter { $Command -eq 'screen-has' }

        Enable-WifiSharing $A71 | Should -BeTrue

        @($events) | Should -Be @('register the hotspot off and Wi-Fi on, on the A71', 'tap Mobile Hotspot', 'register Wi-Fi sharing off on the A71', 'tap the switch', 'complete the hotspot off and Wi-Fi on, on the A71')
    }
}

Describe 'The live runs of sitting 2 (the fix round before the sittings)' {
    BeforeEach {
        $EmulatorSerial = 'emulator-5554'
        $A71 = 'R58N66RVD7Z'
        $DryRun = $false
        $Step = @()
        $config = [pscustomobject]@{ gmailAddress = 'pera.test@example.com' }
        $owner = $TestDrive
        $recordFile = Join-Path $TestDrive "record-$([guid]::NewGuid().ToString('N')).json"
        $resultsFile = Join-Path $TestDrive "agent-sitting2-$([guid]::NewGuid().ToString('N')).txt"
        [IO.File]::WriteAllText($resultsFile, '')
        $credits = 0.0
        $creditCeiling = 150
        $ceilingMessage = 'The runs reached the sitting''s ceiling of 150 AI Credits.'
        $results = [System.Collections.Generic.List[string]]::new()
        Mock Add-Result { $results.Add("${StepName}: $Text") }
        Mock Start-Sleep { }
    }

    It 'writes only the error''s class and a fixed sentence when task-check.ps1 stops (task-5c-10-review M1)' {
        Mock Invoke-TaskCheck { throw "Could not run 'am start -W -a android.intent.action.SENDTO -d 'mailto:pera.test@example.com?subject=x' -p com.google.android.gm' on R58N66RVD7Z: error" }

        Invoke-TaskRuns a71-runs $A71 'read-email' 3 @{ Mail = 'pera.test@example.com' } | Out-Null

        $results | Should -Contain 'a71-runs: read-email on the A71: failed: task-check.ps1 stopped with RuntimeException before its runs ended'
        ($results -join "`n") | Should -Not -Match '@|pera'
    }

    It 'answers each question of a live task once, and survives an answer whose question has closed (task-5c-10-review M2)' {
        $done = @{ Value = $false }
        $answers = [System.Collections.Generic.List[string]]::new()
        $states = [System.Collections.Generic.Queue[string]]::new([string[]]@('asking=free', 'asking=free', 'asking=free', 'asking=none', 'asking=confirm', 'asking=confirm'))
        Mock Invoke-DeviceHook { if ($done.Value) { 'state=done end=FINISHED steps=3 credits=0.5 model=m' } else { 'state=running' } } -ParameterFilter { $Command -eq 'agent-task' }
        Mock Invoke-DeviceHook { if ($states.Count -eq 0) { $done.Value = $true; return 'phase=running asking=none' }; "phase=running $($states.Dequeue())" } -ParameterFilter { $Command -eq 'agent-state' }
        Mock Invoke-DeviceHook { 'count=1 app=dev.kestrel.testapp' } -ParameterFilter { $Command -eq 'screen-has' }
        Mock Invoke-DeviceHook {
            $answers.Add($With.Answer)
            if ($With.Answer -eq 'cancel') { throw 'error: the task of tag k1 asks nothing' }
            'answered'
        } -ParameterFilter { $Command -eq 'agent-answer' }
        Mock Stop-SpeechTask { }

        $end = Wait-LiveTask -Serial $A71 -Tag 'k1' -Answer 'yes' -App 'dev.kestrel.testapp'

        $end.Result['end'] | Should -Be 'FINISHED'
        $end.Asked | Should -Be 1
        $end.Other | Should -Be 0
        @($answers) | Should -Be @('yes', 'cancel', 'cancel')
        Should -Invoke Stop-SpeechTask -Times 0 -Exactly
    }

    It 'approves the screenshot question only for the step''s fixture app, and says no for another app (final-5c-review-b M2)' {
        $done = @{ Value = $false }
        $answers = [System.Collections.Generic.List[string]]::new()
        Mock Invoke-DeviceHook { if ($done.Value) { 'state=done end=FINISHED steps=3' } else { 'state=running' } } -ParameterFilter { $Command -eq 'agent-task' }
        Mock Invoke-DeviceHook { 'phase=running asking=free' } -ParameterFilter { $Command -eq 'agent-state' }
        Mock Invoke-DeviceHook { 'count=0 app=org.telegram.messenger' } -ParameterFilter { $Command -eq 'screen-has' }
        Mock Invoke-DeviceHook { $answers.Add($With.Answer); $done.Value = $true; 'answered' } -ParameterFilter { $Command -eq 'agent-answer' }

        $end = Wait-LiveTask -Serial $A71 -Tag 'k1' -Answer 'text:always for this app' -App 'dev.kestrel.testapp'

        @($answers) | Should -Be @('no')
        $end.Asked | Should -Be 0
        $end.Other | Should -Be 1
    }

    It 'stops the live task when the wait ends with an error, as Ctrl+C ends it (task-5c-10-review M2)' {
        Mock Invoke-DeviceHook { 'state=running' } -ParameterFilter { $Command -eq 'agent-task' }
        Mock Invoke-DeviceHook { 'phase=running asking=none' } -ParameterFilter { $Command -eq 'agent-state' }
        Mock Start-Sleep { throw 'The pipeline has been stopped.' }
        Mock Stop-SpeechTask { }

        { Wait-LiveTask -Serial $A71 -Tag 'k1' -Answer 'yes' -App 'dev.kestrel.testapp' } | Should -Throw 'The pipeline has been stopped.'

        Should -Invoke Stop-SpeechTask -Times 1 -Exactly -ParameterFilter { $Tag -eq 'k1' }
    }

    It 'reads the runs of every call of this sitting 2, also after a stop, for the summary and the cache record (task-5c-10-review M3)' {
        $first = Join-Path $TestDrive 'agent-sitting2-20261201-1000.txt'
        [IO.File]::WriteAllLines($first, [string[]]@(
                "2026-12-01 10:10:00`temulator-runs`thotspot on kestrel-api37, 3 runs"
                "2026-12-01 10:12:00`ttask`ttask=hotspot run=1 result=pass end=FINISHED check=pass steps=5 workMs=30000 credits=0.50 model=gpt-6-luna in=20000 out=500 cacheRead=0 cacheWrite=0 reason=ok"))
        Set-AgentRecord $recordFile 'sitting2Results' @('agent-sitting2-20261201-1000.txt')
        $Step = @('compare', 'cache', 'end')
        Add-SittingResults
        [IO.File]::WriteAllLines($resultsFile, [string[]]@(
                "2026-12-01 12:00:00`tcompare`thotspot on kestrel-api37, 3 runs"
                "2026-12-01 12:02:00`ttask`ttask=hotspot run=1 result=pass end=FINISHED check=pass steps=4 workMs=20000 credits=0.20 model=gemini-3.8-flash in=10000 out=300 cacheRead=0 cacheWrite=0 reason=ok"))

        $runs = @(Get-SittingRuns)

        @($runs | ForEach-Object { $_['model'] }) | Should -Be @('gpt-6-luna', 'gemini-3.8-flash')
        @($runs | ForEach-Object { $_['device'] }) | Should -Be @('kestrel-api37', 'kestrel-api37')
        @((Read-AgentRecord $recordFile).sitting2Results) | Should -Be @('agent-sitting2-20261201-1000.txt', (Split-Path -Leaf $resultsFile))

        # A call without -Step starts a new sitting 2: its credits from 0 and its results from this call's file.
        $Step = @()
        Set-AgentRecord $recordFile 'sitting2Credits' 12.5
        Add-SittingResults
        @((Read-AgentRecord $recordFile).sitting2Results) | Should -Be @((Split-Path -Leaf $resultsFile))
        (Read-AgentRecord $recordFile).sitting2Credits | Should -Be 0
    }
}