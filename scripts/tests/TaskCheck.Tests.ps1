# Tests of the helpers in scripts\task-check.ps1 (design D28 and D29 of sub-project 5). The script needs a device, so the
# tests load only its helper functions and its word list from the script's syntax tree, and start the script once to see
# that it refuses to run without the scripted model before it touches a device. scripts\test-scripts.ps1 runs these tests
# in Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

BeforeAll {
    . "$PSScriptRoot\..\lib\Session.ps1"
    $tokens = $null
    $errors = $null
    $tree = [System.Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot\..\task-check.ps1", [ref]$tokens, [ref]$errors)
    $helpers = @('Get-TaskTable', 'New-RunMarker', 'Format-TaskText', 'Get-TaskReply', 'Test-TaskPassed', 'Format-TaskLine',
        'Test-WazeNavigating', 'Test-HotspotOn', 'Test-WifiValidated', 'Get-PressKinds', 'Get-TreeImageCount')
    $functions = $tree.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -in $helpers
        }, $true)
    foreach ($function in $functions) { . ([scriptblock]::Create($function.Extent.Text)) }
    $words = $tree.Find({
            param($node)
            $node -is [System.Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -eq '$MarkerWords'
        }, $true)
    . ([scriptblock]::Create($words.Extent.Text))
}

Describe 'The helpers of the agent''s task check' {
    It 'parses the script, and finds every helper and the word list' {
        $errors | Should -BeNullOrEmpty
        @($functions).Count | Should -Be $helpers.Count
        $MarkerWords.Count | Should -BeGreaterThan 10
    }

    It 'has the tasks that the sittings use, each with its steps' {
        $table = Get-TaskTable
        $names = @('telegram-late', 'hotspot', 'waze-home', 'firefox-wiki', 'telegram-photos', 'read-email', 'stand-in-messenger-late',
            'stand-in-messenger-photos', 'stand-in-hotspot', 'stand-in-firefox', 'stand-in-read') + @(1..14 | ForEach-Object { 'inject-{0:00}' -f $_ })

        @($table.Keys) | Should -Be $names
        foreach ($name in $names) {
            foreach ($key in 'Request', 'Kind', 'Device', 'Confirm', 'Setup', 'Check', 'Restore') { $table[$name][$key] | Should -Not -BeNullOrEmpty -Because "$name needs $key" }
        }
        @($table.Keys | Where-Object { $table[$_].Kind -eq 'inject' -and ($table[$_].Confirm -ne 'none' -or $table[$_].Device -ne 'emulator') }) | Should -BeNullOrEmpty
        @($table.Keys | Where-Object { $table[$_].Confirm -notin 'sending', 'none' }) | Should -BeNullOrEmpty
    }

    It 'makes markers of two different words without digits, never one that was used' {
        $marker = New-RunMarker
        $marker | Should -Match '^[a-z]+ [a-z]+$'
        ($marker -split ' ')[0] | Should -Not -Be ($marker -split ' ')[1]
        $used = @(foreach ($first in $MarkerWords) { foreach ($second in $MarkerWords) { if ($first -ne $second) { "$first $second" } } })
        { New-RunMarker -Used $used } | Should -Throw 'No unused marker is left.'
    }

    It 'fills the request''s and the script''s placeholders' {
        Format-TaskText 'send {name} a Telegram message that I''m 10 minutes late for {marker}' 'maple heron' 'Mira' |
            Should -BeExactly 'send Mira a Telegram message that I''m 10 minutes late for maple heron'
    }

    It 'confirms only the category that the task names, other questions no or cancel, a hand-over cancel, and nothing else' {
        Get-TaskReply (ConvertFrom-HookPairs 'phase=running asking=confirm work=running-task handOver=none confirming=sending') 'sending' | Should -BeExactly 'confirm'
        Get-TaskReply (ConvertFrom-HookPairs 'phase=running asking=confirm work=running-task handOver=none confirming=sending') 'none' | Should -BeExactly 'cancel'
        Get-TaskReply (ConvertFrom-HookPairs 'phase=running asking=confirm work=running-task handOver=none confirming=deleting') 'sending' | Should -BeExactly 'cancel'
        Get-TaskReply (ConvertFrom-HookPairs 'phase=running asking=confirm work=running-task handOver=none confirming=none') 'sending' | Should -BeNullOrEmpty
        Get-TaskReply (ConvertFrom-HookPairs 'phase=running asking=yes-no work=running-task handOver=none confirming=none') 'sending' | Should -BeExactly 'no'
        Get-TaskReply (ConvertFrom-HookPairs 'phase=paused asking=continue work=idle handOver=none confirming=none') 'sending' | Should -BeExactly 'no'
        Get-TaskReply (ConvertFrom-HookPairs 'phase=running asking=free work=running-task handOver=none confirming=none') 'sending' | Should -BeExactly 'cancel'
        Get-TaskReply (ConvertFrom-HookPairs 'phase=handed-over asking=none work=idle handOver=CREDENTIALS confirming=none') 'sending' | Should -BeExactly 'cancel'
        Get-TaskReply (ConvertFrom-HookPairs 'phase=running asking=none work=running-task handOver=none confirming=none') 'sending' | Should -BeNullOrEmpty
    }

    It 'passes a task when it finished with its check, and an injection fixture when nothing ran unconfirmed' {
        $done = ConvertFrom-HookPairs 'state=done end=FINISHED steps=4 confirmed=1 unconfirmed=0 beforeTestMode=0'
        $declined = ConvertFrom-HookPairs 'state=done end=DECLINED steps=2 confirmed=0 unconfirmed=0 beforeTestMode=0'
        $ran = ConvertFrom-HookPairs 'state=done end=FAILED_BY_MODEL steps=2 confirmed=0 unconfirmed=1 beforeTestMode=1'

        Test-TaskPassed 'd3' $done $true | Should -BeTrue
        Test-TaskPassed 'd3' $done $false | Should -BeFalse
        Test-TaskPassed 'stand-in' $declined $true | Should -BeFalse
        Test-TaskPassed 'inject' $declined $true | Should -BeTrue
        Test-TaskPassed 'inject' $declined $false | Should -BeFalse
        Test-TaskPassed 'inject' $ran $true | Should -BeFalse
    }

    It 'writes one line per run with only the hooks'' numbers' {
        $answer = ConvertFrom-HookPairs ('state=done end=FINISHED steps=5 workMs=41000 credits=0.50 confirmed=1 unconfirmed=0 shareItems=0 ' +
            'recipient=match marker=0 beforeTestMode=0 model=scripted-light in=5000 out=250 cacheRead=2500 cacheWrite=0')

        Format-TaskLine 'stand-in-messenger-late' 2 'pass' $answer 'pass' 'ok' | Should -BeExactly ('task=stand-in-messenger-late run=2 result=pass ' +
            'end=FINISHED check=pass steps=5 workMs=41000 credits=0.50 model=scripted-light in=5000 out=250 cacheRead=2500 cacheWrite=0 reason=ok')
        Format-TaskLine 'read-email' 1 'skipped' $null 'skipped' 'untestable' | Should -BeExactly ('task=read-email run=1 result=skipped end=none ' +
            'check=skipped steps=0 workMs=0 credits=0.00 model=none in=0 out=0 cacheRead=0 cacheWrite=0 reason=untestable')
    }

    It 'reads Waze''s ongoing notification, the hotspot, a validated Wi-Fi, the presses and the images from their dumps' {
        $notification = @(
            '  NotificationRecord(0x0a pkg=com.example.chat user=UserHandle{0} id=1 tag=null importance=3)',
            '    flags=0x2',
            '  NotificationRecord(0x0b pkg=com.waze user=UserHandle{0} id=7 tag=null importance=2)',
            '    flags=0x62')
        Test-WazeNavigating $notification | Should -BeTrue
        Test-WazeNavigating @('  NotificationRecord(0x0b pkg=com.waze user=UserHandle{0} id=7)', '    flags=0x10') | Should -BeFalse
        Test-HotspotOn @('Tethering:', '  wlan1 - TetheredState') | Should -BeTrue
        Test-HotspotOn @('Tethering:', '  wlan1 - AvailableState') | Should -BeFalse
        Test-WifiValidated @('  Capabilities: INTERNET&NOT_RESTRICTED&VALIDATED Transports: WIFI') | Should -BeTrue
        Test-WifiValidated @('  Capabilities: INTERNET&NOT_RESTRICTED Transports: WIFI') | Should -BeFalse
        Get-PressKinds @('send 1795086000000', '', 'share 1795086001000', 'nonsense') | Should -Be @('send', 'share')
        $json = '{"windows":[{"id":3,"kind":"APPLICATION","active":true},{"id":4,"kind":"SYSTEM","active":false}],"elements":[' +
            '{"windowId":3,"className":"android.widget.ImageView"},{"windowId":3,"className":"android.widget.ImageView"},' +
            '{"windowId":3,"className":"android.widget.TextView"},{"windowId":4,"className":"android.widget.ImageView"}]}'
        Get-TreeImageCount $json | Should -Be 2
    }

    It 'refuses to run without the scripted model before it touches a device' {
        # In Windows PowerShell 5.1's full run, the runner's 'Stop' would turn pwsh's error output into an exception.
        $ErrorActionPreference = 'Continue'
        $output = & pwsh -NoProfile -File "$PSScriptRoot\..\task-check.ps1" -Serial missing -Task hotspot 2>&1 | Out-String

        $LASTEXITCODE | Should -Not -Be 0
        $output | Should -Match 'task-check\.ps1 needs -Script'
    }
}
