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
        'Enable-WifiSharing', 'Register-WifiSharingRestore', 'Invoke-TaskRuns', 'Invoke-TaskCheck', 'Wait-LiveTask', 'Get-SittingResultFiles', 'Add-SittingResults',
        'Get-VoiceState', 'Register-ListeningRestore', 'Register-SittingEndRestores', 'Test-GmailAccount', 'Test-StepCalls', 'Get-StandInText',
        'Get-SpentCredits', 'Assert-CreditsLeft', 'Invoke-LookCloserStep', 'Get-A71', 'Invoke-SittingSteps', 'Invoke-EndStep2', 'Invoke-Restore',
        'Get-UnknownCreditCount', 'Test-SittingCreditsUnknown')
    # The owner's decisions for the sittings (fix-5c-owner), and the helpers that their steps call, which the tests mock.
    $helpers += @('Get-AccountCount', 'Test-EmulatorOwnAccount', 'Test-TelegramDevice', 'Test-TelegramSignedIn', 'Get-MissingApps', 'Test-WazeNavigating',
        'Test-WazeHome', 'Set-EmulatorFix', 'Start-TestLocation', 'Invoke-EmulatorAccountItem', 'Invoke-OwnerItem', 'Invoke-OwnerStep', 'Set-OwnerNames',
        'Request-OwnerItem', 'Install-FromPlay', 'Start-Listening', 'Invoke-FingerCheck', 'Invoke-OwnerStep2', 'Get-DefaultProfileKind', 'Test-CompareModel',
        'Invoke-RecordingsStep', 'Invoke-EmulatorTasks', 'Invoke-EmulatorRunsStep', 'Invoke-CompareStep')
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

    It 'gives the stand-in microphone a one-word clip without its full stop, which Kestrel''s own voice says as another word' {
        Get-StandInText 'No.' | Should -BeExactly 'No'
        Get-StandInText 'Kestrel, delete history.' | Should -BeExactly 'Kestrel, delete history.'
        Get-StandInText 'Yes' | Should -BeExactly 'Yes'
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

        $kept = Invoke-GmailCompose -Device $A71 -OwnerAccount -Inside { param($Device) "inside on $Device, trees in $owner" }
        $kept.Opened | Should -BeTrue
        $kept.Discarded | Should -BeTrue
        # The block reads the script's own $owner, the folder of the results, as Save-AgentTree does.
        $kept.Inside | Should -Be "inside on $A71, trees in $TestDrive"
        $kept.Note | Should -Be 'Gmail''s compose screen on the A71: discarded; Drafts shows 2 as before'
        Should -Invoke Invoke-ScreenTap -Times 1 -Exactly -ParameterFilter { $Label -eq 'Discard' }

        $grew = Invoke-GmailCompose -Device $A71 -OwnerAccount -Inside { }
        $grew.Discarded | Should -BeFalse
        $grew.Note | Should -Be 'Gmail''s draft on the A71 may stay in Drafts (Drafts grew from 2 to 3); delete it by hand'

        Mock Invoke-ScreenTap { $false } -ParameterFilter { $Label -eq 'Discard' }
        (Invoke-GmailCompose -Device $A71 -OwnerAccount -Inside { }).Note | Should -Be 'Gmail''s draft on the A71 may stay in Drafts (the taps on "More options" and "Discard" missed); delete it by hand'
    }

    It 'opens no compose screen on the A71 when Drafts cannot be counted, and writes a fixed text when the mailto: command fails (task-5c-9-review M3, M5)' {
        Mock Get-GmailDraftCount { $null }
        Mock Invoke-Device { }

        $skipped = Invoke-GmailCompose -Device $A71 -OwnerAccount -Inside { }
        $skipped.Opened | Should -BeFalse
        $skipped.Note | Should -Be 'Gmail''s compose screen on the A71: not opened, because its Drafts could not be counted'
        Should -Invoke Invoke-Device -Times 0 -Exactly -ParameterFilter { $Command -like '*mailto:*' }

        Mock Get-GmailDraftCount { 1 }
        Mock Invoke-Device { throw "Could not run 'am start -a android.intent.action.SENDTO -d mailto:pera.test@example.com' on R58N66RVD7Z: error" } -ParameterFilter { $Command -like '*mailto:*' }
        $message = try { Invoke-GmailCompose -Device $A71 -OwnerAccount -Inside { } | Out-Null; '' } catch { $_.Exception.Message }
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
        $script:credits = 0.0
        $creditCeiling = 150
        $ceilingMessage = 'The runs reached the sitting''s ceiling of 150 AI Credits.'
        $unknownMessage = 'A run''s AI Credits are unknown, because the script did not read its end, so the sitting stops as at its ceiling of 150 AI Credits.'
        $resultsGoneMessage = 'A results file of an earlier call of this sitting 2, which the record lists, is gone, so the sitting''s AI Credits are unknown and no run starts.'
        $noRecordMessage = 'The record lists no results of an earlier call of this sitting 2, so the sitting''s AI Credits are unknown and no run starts; a new sitting 2 is a call without -Step.'
        $script:sittingRecordMissing = $false
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

    It 'starts no run on a resume once the earlier calls'' results hold the ceiling, also when the record missed their credits (fix-5c-scripts-review I1)' {
        $first = Join-Path $TestDrive 'agent-sitting2-20261201-0900.txt'
        [IO.File]::WriteAllLines($first, [string[]]@(
                "2026-12-01 09:10:00`ta71-runs`thotspot on the A71, 3 runs"
                "2026-12-01 09:12:00`ttask`ttask=hotspot run=1 result=pass end=FINISHED check=pass steps=5 workMs=30000 credits=60.00 model=gpt-6-luna in=20000 out=500 cacheRead=0 cacheWrite=0 reason=ok"
                "2026-12-01 09:14:00`ttask`ttask=hotspot run=2 result=pass end=FINISHED check=pass steps=5 workMs=30000 credits=60.00 model=gpt-6-luna in=20000 out=500 cacheRead=0 cacheWrite=0 reason=ok"
                "2026-12-01 09:30:00`tlook-closer`tthe A71, the answer yes: end=FINISHED steps=3 credits=30.00 model=gpt-6-luna; the screenshot question came 1 times, another app's free question 0 times (answered no); ""Details shown"" shows 1 times (pass); spent=30.00"))
        Set-AgentRecord $recordFile 'sitting2Results' @('agent-sitting2-20261201-0900.txt')
        $Step = @('prepare', 'emulator-runs')
        Add-SittingResults
        $script:credits = 0.0
        Mock Invoke-TaskCheck { }

        { Invoke-TaskRuns emulator-runs $EmulatorSerial 'hotspot' } | Should -Throw $ceilingMessage

        Should -Invoke Invoke-TaskCheck -Times 0 -Exactly
        (Read-AgentRecord $recordFile).sitting2Credits | Should -Be 150
    }

    It 'starts a run below the ceiling, whose cost shows only after it, and no run after it (the plan''s rule: the sitting stops when its credits reach 150)' {
        $script:credits = 149.5
        Mock Invoke-TaskCheck { Add-Content -LiteralPath $resultsFile "2026-12-01 12:02:00`ttask`ttask=hotspot run=1 result=pass end=FINISHED check=pass steps=4 workMs=20000 credits=1.00 model=gpt-6-luna in=10000 out=300 cacheRead=0 cacheWrite=0 reason=ok" }

        { Invoke-TaskRuns emulator-runs $EmulatorSerial 'hotspot' 1 } | Should -Throw $ceilingMessage
        { Invoke-TaskRuns emulator-runs $EmulatorSerial 'firefox-wiki' 1 } | Should -Throw $ceilingMessage

        Should -Invoke Invoke-TaskCheck -Times 1 -Exactly
        (Read-AgentRecord $recordFile).sitting2Credits | Should -Be 150.5
    }

    It 'gives look_closer no live task once the ceiling is reached (fix-5c-scripts-review I1)' {
        $Serial = $A71
        $script:credits = 150.0
        Mock Unlock-Device { $true }
        Mock Invoke-Device { }
        Mock Invoke-DeviceHook { 'approvals=0' }
        Mock Register-Restore { [pscustomobject]@{ What = $What } }
        Mock Complete-Restore { }
        Mock Wait-LiveTask { [pscustomobject]@{ Result = $null; Asked = 0; Other = 0; Stopped = $null } }
        Mock Get-ScreenCount { 0 }

        { Invoke-LookCloserStep } | Should -Throw $ceilingMessage

        Should -Invoke Invoke-DeviceHook -Times 0 -Exactly -ParameterFilter { $Command -eq 'voice' }
    }

    It 'starts no run when a results file that the record lists is gone, instead of counting it as 0 (fix-5c-ceiling-review M1)' {
        Set-AgentRecord $recordFile 'sitting2Results' @('agent-sitting2-gone.txt')
        Set-AgentRecord $recordFile 'sitting2Credits' 10.0
        $Step = @('prepare', 'emulator-runs')
        Add-SittingResults
        Mock Invoke-TaskCheck { }

        { Invoke-TaskRuns emulator-runs $EmulatorSerial 'hotspot' } | Should -Throw $resultsGoneMessage

        Should -Invoke Invoke-TaskCheck -Times 0 -Exactly
    }

    It 'starts no run in a call with -Step whose record lists no earlier call, and starts at 0 in a call without -Step (fix-5c-ceiling-review M1)' {
        Mock Invoke-TaskCheck { Add-Content -LiteralPath $resultsFile "2026-12-01 12:02:00`ttask`ttask=hotspot run=1 result=pass end=FINISHED check=pass steps=4 workMs=20000 credits=1.00 model=gpt-6-luna in=10000 out=300 cacheRead=0 cacheWrite=0 reason=ok" }
        $Step = @('prepare', 'emulator-runs')
        Add-SittingResults

        { Invoke-TaskRuns emulator-runs $EmulatorSerial 'hotspot' 1 } | Should -Throw $noRecordMessage

        Should -Invoke Invoke-TaskCheck -Times 0 -Exactly
        # The record does not start a list from this call, so a later call with -Step stops too.
        (Read-AgentRecord $recordFile).PSObject.Properties['sitting2Results'] | Should -BeNullOrEmpty

        $Step = @()
        Add-SittingResults
        Invoke-TaskRuns emulator-runs $EmulatorSerial 'hotspot' 1 | Out-Null
        Should -Invoke Invoke-TaskCheck -Times 1 -Exactly
        (Read-AgentRecord $recordFile).sitting2Credits | Should -Be 1
    }

    It 'stops at a run whose AI Credits are unknown as at the ceiling, and starts no run after it, also on a resume (fix-5c-ceiling-review M2)' {
        $Step = @()
        Add-SittingResults
        Mock Invoke-TaskCheck { Add-Content -LiteralPath $resultsFile "2026-12-01 12:02:00`ttask`ttask=hotspot run=1 result=fail end=none check=skipped steps=0 workMs=0 credits=unknown model=none in=0 out=0 cacheRead=0 cacheWrite=0 reason=stopped" }

        { Invoke-TaskRuns emulator-runs $EmulatorSerial 'hotspot' 3 } | Should -Throw $unknownMessage
        $Step = @('prepare', 'firefox-wiki')
        Add-SittingResults
        { Invoke-TaskRuns emulator-runs $EmulatorSerial 'firefox-wiki' 3 } | Should -Throw $unknownMessage

        Should -Invoke Invoke-TaskCheck -Times 1 -Exactly
        $results | Should -Contain 'emulator-runs: hotspot on kestrel-api37: 0 of 1 runs passed; the sitting''s AI Credits so far: 0, and 1 runs'' AI Credits are unknown'
        @(Get-RunSummary @(Get-SittingRuns)) | Should -Be @('task=hotspot model=none passed=0 of 1 steps=0 seconds=0 credits=0 creditsUnknown=1')
    }

    It 'stops look_closer after a live task whose AI Credits it could not read, as at the ceiling (fix-5c-ceiling-review M2)' {
        $Serial = $A71
        $Step = @()
        Add-SittingResults
        Mock Unlock-Device { $true }
        Mock Invoke-Device { }
        Mock Invoke-DeviceHook { 'approvals=0' }
        Mock Register-Restore { [pscustomobject]@{ What = $What } }
        Mock Complete-Restore { }
        Mock New-CheckTag { 'k1' }
        Mock Wait-LiveTask { [pscustomobject]@{ Result = $null; Asked = 0; Other = 0; Stopped = $null } }
        Mock Get-ScreenCount { 0 }
        Mock Add-Result { $results.Add("${StepName}: $Text"); Add-SessionResult -Path $resultsFile -Step $StepName -Text $Text }

        { Invoke-LookCloserStep } | Should -Throw $unknownMessage

        Should -Invoke Invoke-DeviceHook -Times 1 -Exactly -ParameterFilter { $Command -eq 'voice' }
        @($results | Where-Object { $_ -like 'look-closer: *' })[-1] | Should -BeLike '*; spent=unknown'
        { Assert-CreditsLeft } | Should -Throw $unknownMessage
    }

    It 'writes look_closer''s spending as unknown when an error or Ctrl+C ends the wait for its live task (fix-5c-ceiling-review M2)' {
        $Serial = $A71
        $Step = @()
        Add-SittingResults
        Mock Unlock-Device { $true }
        Mock Invoke-Device { }
        Mock Invoke-DeviceHook { 'approvals=0' }
        Mock Register-Restore { [pscustomobject]@{ What = $What } }
        Mock Complete-Restore { }
        Mock New-CheckTag { 'k1' }
        Mock Wait-LiveTask { throw 'The pipeline has been stopped.' }
        Mock Add-Result { $results.Add("${StepName}: $Text"); Add-SessionResult -Path $resultsFile -Step $StepName -Text $Text }

        { Invoke-LookCloserStep } | Should -Throw 'The pipeline has been stopped.'

        @($results) | Should -Contain 'look-closer: kestrel-api37, the answer yes: the step stopped before it read the task''s end; spent=unknown'
        { Assert-CreditsLeft } | Should -Throw $unknownMessage
    }
}

Describe 'The restores and checks of every call of a sitting (the fix round before the sittings)' {
    BeforeEach {
        $EmulatorSerial = 'emulator-5554'
        $A71 = 'R58N66RVD7Z'
        $Serial = $A71
        $DryRun = $false
        $Step = @()
        $config = [pscustomobject]@{ gmailAddress = 'pera.test@example.com'; emulatorGmailAddress = 'kestrel.test@example.com' }
        $recordFile = Join-Path $TestDrive "record-$([guid]::NewGuid().ToString('N')).json"
        $listening = @{}
        $registered = [System.Collections.Generic.List[string]]::new()
        Mock Register-Restore { $registered.Add($What); [pscustomobject]@{ What = $What; Data = $Data } }
        $results = [System.Collections.Generic.List[string]]::new()
        Mock Add-Result { $results.Add("${StepName}: $Text") }
        Mock Start-Sleep { }
    }

    It 'registers the restores of Kestrel''s mode and microphone once per device, before any step starts Kestrel (final-5c-review-b M1)' {
        Mock Get-VoiceState { [ordered]@{ mode = 'off'; session = 'closed' } }

        Register-ListeningRestore $A71
        Register-ListeningRestore $A71

        @($registered) | Should -Be @('Kestrel''s own microphone on the A71', 'Kestrel''s mode Off on the A71')
        $listening.ContainsKey($A71) | Should -BeTrue

        Mock Get-VoiceState { [ordered]@{ mode = 'listening'; session = 'open' } }
        Register-ListeningRestore $EmulatorSerial
        @($registered) | Should -Be @('Kestrel''s own microphone on the A71', 'Kestrel''s mode Off on the A71', 'Kestrel''s own microphone on kestrel-api37')
    }

    It 'registers the end''s restores of kestrel-api37 only in the step end, and runs them first there (fix-5c-scripts-review M1)' {
        Set-AgentRecord $recordFile 'playDisabled' $true
        Set-AgentRecord $recordFile 'emulatorAccount' $true
        $pending = [System.Collections.Generic.List[object]]::new()
        $pending.Add([pscustomobject]@{ What = 'the live session on the A71' })
        $done = [System.Collections.Generic.List[string]]::new()
        Mock Register-Restore { $registered.Add($What); $restore = [pscustomobject]@{ What = $What }; $pending.Add($restore); $restore }
        Mock Complete-Restore { $done.Add($Restore.What) }
        Mock Invoke-Device { }

        Invoke-EndStep2

        @($registered) | Should -Be @('the Play Store on kestrel-api37', 'the test Google account on kestrel-api37')
        @($done) | Should -Be @('the test Google account on kestrel-api37', 'the Play Store on kestrel-api37', 'the live session on the A71')
    }

    It 'runs only the step end after the ceiling, and then stops with the ceiling''s sentence (fix-5c-scripts-review I1, M1)' {
        $ceilingMessage = 'The runs reached the sitting''s ceiling of 150 AI Credits.'
        $ran = [System.Collections.Generic.List[string]]::new()
        Mock Test-StepCalls { $false }
        Mock Write-Host { }
        $entries = @(
            @{ Name = 'a71-runs'; Run = { $ran.Add('a71-runs'); throw $ceilingMessage } }
            @{ Name = 'cache'; Run = { $ran.Add('cache') } }
            @{ Name = 'end'; Run = { $ran.Add('end') } }
        )

        { Invoke-SittingSteps $entries } | Should -Throw $ceilingMessage

        @($ran) | Should -Be @('a71-runs', 'end')
        $results | Should -Contain "a71-runs: stopped: $ceilingMessage"

        # A call with -Step without end stops at once, and keeps the emulator's profile and account for the next call.
        $ran.Clear()
        $Step = @('a71-runs', 'cache')
        { Invoke-SittingSteps $entries } | Should -Throw $ceilingMessage
        @($ran) | Should -Be @('a71-runs')
    }

    It 'runs only the step end also after a run whose AI Credits are unknown or a record that lost a call, as after the ceiling (fix-5c-ceiling-review M1, M2)' {
        $unknownMessage = 'A run''s AI Credits are unknown, because the script did not read its end, so the sitting stops as at its ceiling of 150 AI Credits.'
        $resultsGoneMessage = 'A results file of an earlier call of this sitting 2, which the record lists, is gone, so the sitting''s AI Credits are unknown and no run starts.'
        $ran = [System.Collections.Generic.List[string]]::new()
        Mock Test-StepCalls { $false }
        Mock Write-Host { }
        foreach ($message in $unknownMessage, $resultsGoneMessage) {
            $ran.Clear()
            $entries = @(
                @{ Name = 'a71-runs'; Run = { $ran.Add('a71-runs'); throw $message } }
                @{ Name = 'cache'; Run = { $ran.Add('cache') } }
                @{ Name = 'end'; Run = { $ran.Add('end') } }
            )

            { Invoke-SittingSteps $entries } | Should -Throw $message

            @($ran) | Should -Be @('a71-runs', 'end')
            $results | Should -Contain "a71-runs: stopped: $message"
        }
    }

    It 'checks Gmail''s account on the A71 only when sitting 1 saw its discard work there, with Drafts counted around it (final-5c-review-b M4)' {
        Mock Invoke-GmailCompose { [pscustomobject]@{ Opened = $true; Discarded = $true; Inside = (& $Inside $Device); Note = 'discarded' } }
        Mock Get-ScreenCount { 2 }

        Test-GmailAccount $A71 ([pscustomobject]@{ gmailDiscardA71 = 'left' }) | Should -BeFalse
        Should -Invoke Invoke-GmailCompose -Times 0 -Exactly
        $results | Should -Contain 'prepare: Gmail''s account on the A71: not checked, because sitting 1 did not see Gmail''s discard work there'

        Test-GmailAccount $A71 ([pscustomobject]@{ gmailDiscardA71 = 'ok' }) | Should -BeTrue
        Should -Invoke Invoke-GmailCompose -Times 1 -Exactly -ParameterFilter { $Device -eq $A71 -and $OwnerAccount }
        Should -Invoke Get-ScreenCount -Times 1 -Exactly -ParameterFilter { $With.Contains -eq 'pera.test@example.com' }
    }

    It 'ends a call that shows before or after a step with the end-call key, and fails the step (final-5c-review-b M5)' {
        Mock Invoke-Device { @('  mCallState=2', '  mCallState=0') } -ParameterFilter { $Device -eq $A71 -and $Command -eq 'dumpsys telephony.registry' }
        Mock Invoke-Device { @('  mCallState=0') } -ParameterFilter { $Device -eq $EmulatorSerial -and $Command -eq 'dumpsys telephony.registry' }
        Mock Stop-DeviceCall { $true }

        Test-StepCalls 'injection' 'after' | Should -BeTrue

        Should -Invoke Stop-DeviceCall -Times 1 -Exactly -ParameterFilter { $Serial -eq $A71 }
        @($results) | Should -Be @('injection: failed: a call showed on the A71 after the step; the script ended it with the end-call key')

        Mock Invoke-Device { @('  mCallState=0') } -ParameterFilter { $Command -eq 'dumpsys telephony.registry' }
        Test-StepCalls 'injection' 'before' | Should -BeFalse
        Should -Invoke Stop-DeviceCall -Times 1 -Exactly
    }
}
Describe 'The owner''s decisions for the sittings (fix-5c-owner)' {
    BeforeEach {
        $EmulatorSerial = 'emulator-5554'
        $A71 = 'R58N66RVD7Z'
        $Serial = $A71
        $DryRun = $false
        $Step = @()
        # The emulator holds the owner's own Google account: emulatorGmailAddress equals gmailAddress.
        $config = [pscustomobject]@{ whatsappOwnName = ''; gmailAddress = 'pera.test@example.com'; emulatorGmailAddress = 'Pera.Test@example.com'; homePlace = 'Belgrade Fortress'; testLocation = '44.8100,20.4600'; testStreet = 'Knez Mihailova' }
        $owner = $TestDrive
        $recordFile = Join-Path $TestDrive "record-$([guid]::NewGuid().ToString('N')).json"
        $pending = [System.Collections.Generic.List[object]]::new()
        $registered = [System.Collections.Generic.List[object]]::new()
        Mock Register-Restore { $restore = [pscustomobject]@{ What = $What; Data = $Data; Action = $Action }; $registered.Add($restore); $restore }
        Mock Complete-Restore { }
        $results = [System.Collections.Generic.List[string]]::new()
        Mock Add-Result { $results.Add("${StepName}: $Text") }
        Mock Start-Sleep { }
        Mock Write-Host { }
        $commands = [System.Collections.Generic.List[string]]::new()
        Mock Invoke-Device { $commands.Add("$Device $Command") }
        Mock Invoke-Device { '    Account {name=someone@example.com, type=com.google}' } -ParameterFilter { $Device -eq $EmulatorSerial -and $Command -eq 'dumpsys account' }
        Mock Invoke-Device { '      android.permission.POST_NOTIFICATIONS: granted=true' } -ParameterFilter { $Command -eq 'dumpsys package com.google.android.gm' }
    }

    It 'takes an empty or missing whatsappOwnName, because WhatsApp is skipped, and then sets no WhatsApp name in test mode' {
        $path = Join-Path $TestDrive 'a71-config.json'
        $fake = [ordered]@{ pin = '97531'; whatsappOwnName = ''; gmailAddress = 'pera.test@example.com'; homePlace = 'Belgrade Fortress'; testLocation = '44.8100,20.4600'; testStreet = 'Knez Mihailova' }
        [IO.File]::WriteAllText($path, ($fake | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
        "$((Get-AgentConfig $path).whatsappOwnName)" | Should -Be ''
        $fake.Remove('whatsappOwnName')
        [IO.File]::WriteAllText($path, ($fake | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
        "$((Get-AgentConfig $path).whatsappOwnName)" | Should -Be ''

        Mock Invoke-DeviceHook { "ownerEmails=0 viberSelfChatName=unset whatsappOwnName=$(if ($With.WhatsappOwnName) { 'set' } else { 'unset' }) selfChatAliases=0" } -ParameterFilter { $Command -eq 'agent-settings' }
        Set-OwnerNames $A71
        Should -Invoke Invoke-DeviceHook -Times 1 -Exactly -ParameterFilter { $Command -eq 'agent-settings' -and $With.ContainsKey('WhatsappOwnName') -and $With.WhatsappOwnName -eq '' }

        $config.whatsappOwnName = 'Pera Test'
        Set-OwnerNames $A71
        Should -Invoke Invoke-DeviceHook -Times 1 -Exactly -ParameterFilter { $Command -eq 'agent-settings' -and $With.WhatsappOwnName -eq 'Pera Test' }
    }

    It 'counts Telegram''s accounts by their type only, and never lists WhatsApp among the apps that the owner installs' {
        $accounts = @('    Account {name=+381601234567, type=org.telegram.messenger}', '    Account {name=someone@example.com, type=com.google}')
        Get-AccountCount $accounts 'org.telegram.messenger' | Should -Be 1
        Get-AccountCount $accounts 'com.whatsapp' | Should -Be 0

        Mock Test-Installed { $false }
        @(Get-MissingApps $A71) | Should -Be @('org.telegram.messenger', 'com.waze', 'com.google.android.gm')
    }

    It 'checks Waze''s Home with its deep link to Home and its navigation notification, and stops Waze afterwards' {
        Mock Test-Installed { $true }
        Mock Set-EmulatorFix { }
        Mock Start-TestLocation { [pscustomobject]@{ What = 'the test location on the A71' } }
        Mock Invoke-Device { $commands.Add("$Device $Command"); @('  NotificationRecord(0x0b pkg=com.waze user=UserHandle{0} id=7)', '    flags=0x62') } -ParameterFilter { $Command -eq 'dumpsys notification' }

        Test-WazeHome $EmulatorSerial | Should -BeTrue
        $commands | Should -Contain "$EmulatorSerial am start -a android.intent.action.VIEW -d 'waze://?favorite=home&navigate=yes' -p com.waze"
        $commands[-1] | Should -Be "$EmulatorSerial am force-stop com.waze"
        Should -Invoke Set-EmulatorFix -Times 1 -Exactly

        Mock Invoke-Device { $commands.Add("$Device $Command"); @('  NotificationRecord(0x0b pkg=com.waze user=UserHandle{0} id=7)', '    flags=0x10') } -ParameterFilter { $Command -eq 'dumpsys notification' }
        $commands.Clear()
        Test-WazeHome $A71 | Should -BeFalse
        $commands[-1] | Should -Be "$A71 am force-stop com.waze"
        Should -Invoke Start-TestLocation -Times 1 -Exactly
        Should -Invoke Complete-Restore -Times 1 -Exactly
    }

    It 'asks the owner of sitting 1 only for the touch when Telegram, the apps, Waze''s Home and the emulator''s own account are there, and sets up the emulator without him' {
        Set-AgentRecord $recordFile 'emulatorPlay' $true
        Mock Unlock-Device { $true }
        Mock Get-MissingApps { @() }
        Mock Test-Installed { $true }
        Mock Test-TelegramSignedIn { $true }
        Mock Test-WazeHome { $true }
        Mock Request-OwnerItem { 'go' }
        Mock Install-FromPlay { $true }
        Mock Start-Listening { }
        Mock Invoke-FingerCheck { 'A' }

        Invoke-OwnerStep

        Should -Invoke Request-OwnerItem -Times 0 -Exactly
        Should -Invoke Install-FromPlay -Times 0 -Exactly
        Should -Invoke Invoke-FingerCheck -Times 1 -Exactly
        $commands | Should -Contain "$EmulatorSerial pm revoke com.google.android.gm android.permission.POST_NOTIFICATIONS"
        $commands | Should -Contain "$EmulatorSerial pm disable-user --user 0 com.android.vending"
        $record = Read-AgentRecord $recordFile
        $record.playDisabled | Should -BeTrue
        $record.gmailNotificationsOff | Should -BeTrue
        $record.emulatorAccount | Should -Not -BeTrue
        ($results -join "`n") | Should -Not -Match 'failed|WhatsApp on'
    }

    It 'never asks to sign the owner''s own account in on kestrel-api37, and changes nothing there in a dry run' {
        Set-AgentRecord $recordFile 'emulatorPlay' $true
        Mock Request-OwnerItem { 'go' }
        Mock Invoke-Device { $commands.Add("$Device $Command") } -ParameterFilter { $Device -eq $EmulatorSerial -and $Command -eq 'dumpsys account' }

        Invoke-EmulatorAccountItem

        Should -Invoke Request-OwnerItem -Times 0 -Exactly
        $commands | Should -Not -Contain "$EmulatorSerial pm disable-user --user 0 com.android.vending"

        Mock Invoke-Device { '    Account {name=someone@example.com, type=com.google}' } -ParameterFilter { $Device -eq $EmulatorSerial -and $Command -eq 'dumpsys account' }
        $DryRun = $true
        Invoke-EmulatorAccountItem
        Should -Invoke Request-OwnerItem -Times 0 -Exactly
        @($commands | Where-Object { $_ -match 'pm (revoke|disable-user|uninstall)' }) | Should -BeNullOrEmpty
        (Read-AgentRecord $recordFile).playDisabled | Should -Not -BeTrue
    }

    It 'never deletes the emulator''s Copilot profile, signs out its own account or uninstalls Gmail at the end of sitting 2, and keeps the other restores' {
        Set-AgentRecord $recordFile 'playDisabled' $true
        Set-AgentRecord $recordFile 'gmailNotificationsOff' $true
        Set-AgentRecord $recordFile 'emulatorAccount' $true
        Set-AgentRecord $recordFile 'installedEmulator' @('com.waze', 'com.google.android.gm')
        Mock Invoke-DeviceHook { 'deleted=1 failed=0 copilot=0' }
        Mock Test-Installed { $true }

        Register-SittingEndRestores

        @($registered | ForEach-Object { $_.What }) | Should -Be @('the Play Store on kestrel-api37', 'Gmail''s notifications on kestrel-api37', 'the apps that sitting 1 installed on kestrel-api37')
        foreach ($restore in $registered) { & $restore.Action $restore.Data | Out-Null }
        $commands | Should -Contain "$EmulatorSerial pm uninstall com.waze"
        $commands | Should -Contain "$EmulatorSerial pm grant com.google.android.gm android.permission.POST_NOTIFICATIONS"
        @($commands | Where-Object { $_ -match 'uninstall com\.google\.android\.gm|uninstall com\.android\.vending|SYNC_SETTINGS' }) | Should -BeNullOrEmpty
        Should -Invoke Invoke-DeviceHook -Times 0 -Exactly -ParameterFilter { $Command -eq 'provider-delete' }

        # A test account of another address, which sitting 1 signed in, still leaves; a dry run removes nothing.
        $registered.Clear()
        $config.emulatorGmailAddress = 'kestrel.test@example.com'
        Register-SittingEndRestores
        @($registered | ForEach-Object { $_.What }) | Should -Contain 'the test Google account on kestrel-api37'
        $registered.Clear()
        $DryRun = $true
        Register-SittingEndRestores
        @($registered | ForEach-Object { $_.What }) | Should -Not -Contain 'the test Google account on kestrel-api37'
        @($registered | ForEach-Object { $_.What }) | Should -Not -Contain 'the apps that sitting 1 installed on kestrel-api37'
    }

    It 'asks nothing in sitting 2''s owner step when both devices already have a Copilot profile, and asks only for a device without one' {
        Mock Unlock-Device { $true }
        Mock Request-OwnerItem { 'go' }
        Mock Test-CompareModel { $true }
        Mock Invoke-DeviceHook { 'standin=off profile=copilot copilot=1' } -ParameterFilter { $Command -eq 'provider-standin' }

        Invoke-OwnerStep2

        Should -Invoke Request-OwnerItem -Times 0 -Exactly
        $results | Should -Contain 'owner: kestrel-api37: the default profile is already a Copilot profile, so the owner is not asked'
        $results | Should -Contain 'owner: the A71: the default profile is already a Copilot profile, so the owner is not asked'

        Mock Get-DefaultProfileKind { if ($Device -eq $A71) { 'none' } else { 'copilot' } }
        Mock Request-OwnerItem { 'skip' }
        Invoke-OwnerStep2
        Should -Invoke Request-OwnerItem -Times 1 -Exactly -ParameterFilter { $Prompt -match 'on the A71' }
        Should -Invoke Request-OwnerItem -Times 1 -Exactly
    }

    It 'skips Telegram on kestrel-api37 and WhatsApp on both devices in the recordings, and opens Gmail on the emulator''s own account only with Drafts counted' {
        Mock Unlock-Device { $true }
        Mock Test-Installed { $true }
        Mock Invoke-TelegramRecording { 'telegram recorded' }
        Mock Open-App { }
        Mock Save-AgentTree { "$Name recorded" }
        Mock Invoke-GmailCompose { [pscustomobject]@{ Opened = $true; Discarded = $true; Inside = $null; Note = 'discarded' } }

        # A dry run, whose A71 steps run on kestrel-api37.
        $DryRun = $true
        $Serial = $EmulatorSerial
        Invoke-RecordingsStep

        Should -Invoke Invoke-TelegramRecording -Times 0 -Exactly
        Should -Invoke Open-App -Times 0 -Exactly -ParameterFilter { $Package -eq 'com.whatsapp' }
        Should -Invoke Invoke-GmailCompose -Times 0 -Exactly
        $results | Should -Contain 'recordings: Telegram''s recordings: not run on kestrel-api37 (the owner''s choice)'
        $results | Should -Contain 'recordings: WhatsApp''s recordings: skipped on both devices (the owner''s choice)'
        (Read-AgentRecord $recordFile).whatsappSelfChat | Should -Be 'skipped'

        $DryRun = $false
        $Serial = $A71
        Invoke-RecordingsStep
        Should -Invoke Invoke-TelegramRecording -Times 1 -Exactly -ParameterFilter { $Device -eq $A71 }
        Should -Invoke Open-App -Times 0 -Exactly -ParameterFilter { $Package -eq 'com.whatsapp' }
        Should -Invoke Invoke-GmailCompose -Times 1 -Exactly -ParameterFilter { $Device -eq $EmulatorSerial -and $OwnerAccount }
    }

    It 'runs the emulator''s e-mail task on its own account, says that Telegram''s tasks do not run there, and compares only tasks without Telegram or WhatsApp' {
        Mock Test-Installed { $true }
        Mock Invoke-TaskRuns { @() }

        Invoke-EmulatorRunsStep

        Should -Invoke Invoke-TaskRuns -Times 1 -Exactly -ParameterFilter { $Task -eq 'read-email' -and $Device -eq $EmulatorSerial -and $With.Mail -eq 'Pera.Test@example.com' }
        $results | Should -Contain 'emulator-runs: telegram-late and telegram-photos: not run on kestrel-api37 (the owner''s choice)'

        Mock Get-SittingRuns { @() }
        Mock Test-CompareModel { $true }
        Invoke-CompareStep
        $results | Should -Contain 'compare: the comparison runs hotspot, waze-home and firefox-wiki, which need neither Telegram nor WhatsApp'
        Should -Invoke Invoke-TaskRuns -Times 3 -Exactly -ParameterFilter { $StepName -eq 'compare' }
        Should -Invoke Invoke-TaskRuns -Times 0 -Exactly -ParameterFilter { $StepName -eq 'compare' -and $Task -in 'telegram-late', 'telegram-photos', 'read-email' }
    }
}
