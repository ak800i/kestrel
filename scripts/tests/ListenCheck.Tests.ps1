# Tests of the reboot check in scripts\listen-check.ps1 with a fake phone. The script needs a device, so the tests load
# only its functions from the script's syntax tree. scripts\test-scripts.ps1 runs these tests in Windows PowerShell 5.1
# and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

BeforeAll {
    $tokens = $null
    $errors = $null
    $tree = [System.Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot\..\listen-check.ps1", [ref]$tokens, [ref]$errors)
    $helpers = @('Wait-Reboot', 'Invoke-RebootCheck')
    $functions = $tree.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -in $helpers
        }, $true)
    foreach ($function in $functions) { . ([scriptblock]::Create($function.Extent.Text)) }
    # The phone, the hooks and the PIN, which the tests mock.
    function Stop-Kestrel { }
    function Invoke-Hook { }
    function Wait-Mode([string]$Mode, [int]$TimeoutSeconds) { }
    function Invoke-Checked([string]$Command) { }
    function Invoke-TimedProcess([string]$FilePath, [string[]]$ArgumentList, [int]$TimeoutSeconds) { }
    function Get-ShellAnswer([string]$Command) { }
    function Assert-TestDevice([string]$Serial) { }
    function Unlock-TestPhone([string]$Serial, [string]$ConfigPath) { }
    function Test-KeyguardShowing([string[]]$Dump) { }
    function Get-RestoreTime { }
    function Get-RecordingProblem($Before) { }
}

Describe 'The reboot check of listen-check.ps1 on a phone' {
    BeforeEach {
        $Serial = 'R58N12345AB'
        $isEmulator = $false
        $pinConfig = 'C:\corpus\owner\a71-config.json'
        $adb = 'adb'
        $modelsHint = ''
        $script:unlockStopped = $false
        $script:deviceLost = $false
        $script:stayedLocked = $false
        # The fake phone's clock moves 10 s at each look, so that the 5 minutes of Wait-Reboot pass at once.
        $fake = @{ Now = [datetime]'2026-11-20T12:00:00'; UserState = 'RUNNING_LOCKED' }
        Mock Get-Date { $fake.Now = $fake.Now.AddSeconds(10); $fake.Now }
        Mock Start-Sleep { }
        Mock Write-Host { }
        Mock Stop-Kestrel { }
        Mock Invoke-Hook { }
        Mock Wait-Mode { 1 }
        Mock Invoke-Checked { 'old-boot' }
        Mock Invoke-TimedProcess { [pscustomobject]@{ Completed = $true } }
        Mock Assert-TestDevice { }
        Mock Get-ShellAnswer {
            switch ($Command) {
                'cat /proc/sys/kernel/random/boot_id' { 'new-boot' }
                'getprop sys.boot_completed' { '1' }
                'am get-started-user-state 0' { $fake.UserState }
                'cat /proc/uptime' { '12.50 30.00' }
                default { '' }
            }
        }
        Mock Get-RestoreTime { '3.2 s after the phone was unlocked, timed on the phone' }
        Mock Get-RecordingProblem { }
    }

    It 'parses the script, and finds both functions' {
        $errors | Should -BeNullOrEmpty
        @($functions).Count | Should -Be $helpers.Count
    }

    It 'says that a phone that answers but stays locked was not unlocked, and does not count it as lost' {
        Mock Unlock-TestPhone { throw 'the unlock failed' }

        $line = Invoke-RebootCheck

        $line | Should -BeExactly 'failed: R58N12345AB started again but stayed locked; the unlock failed'
        $deviceLost | Should -BeFalse
        $stayedLocked | Should -BeTrue
        # One typing only: after it failed, the script tries no more unlocks.
        Should -Invoke Unlock-TestPhone -Times 1 -Exactly
    }

    It 'types no PIN after the restart when the phone is already unlocked' {
        $fake.UserState = 'RUNNING_UNLOCKED'
        Mock Test-KeyguardShowing { $false }
        Mock Unlock-TestPhone { throw 'the unlock failed earlier in this run' }

        Invoke-RebootCheck | Should -Be 'ok: listening again 3.2 s after the phone was unlocked, timed on the phone'
        Should -Invoke Unlock-TestPhone -Times 0 -Exactly
    }
}
