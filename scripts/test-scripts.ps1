# Tests and checks Kestrel's PowerShell scripts. It runs the Pester tests in scripts\tests in Windows PowerShell 5.1 and
# in PowerShell 7, then checks every script with PSScriptAnalyzer and scripts\PSScriptAnalyzerSettings.psd1, and exits
# with 1 if anything failed. CI runs it on every push. It needs these modules, installed once for the current user:
#   in powershell and in pwsh:  Install-Module Pester -RequiredVersion 6.2.0 -Scope CurrentUser -SkipPublisherCheck
#   in pwsh:                    Install-Module PSScriptAnalyzer -RequiredVersion 1.25.0 -Scope CurrentUser
# Example: scripts\test-scripts.ps1
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseCompatibleCommands', 'Invoke-Pester/Configuration',
    Justification = 'The script loads Pester 6.2.0; the 5.1 profile knows only the Pester 3.4 that comes with Windows PowerShell.')]
param(
    # The script runs each part in a shell of its own, by starting itself with -Part.
    [ValidateSet('All', 'Tests', 'Analysis')][string]$Part = 'All'
)
$ErrorActionPreference = 'Stop'

if ($Part -eq 'Tests') {
    Import-Module Pester -RequiredVersion 6.2.0
    $configuration = New-PesterConfiguration
    $configuration.Run.Path = Join-Path $PSScriptRoot 'tests'
    # Ends the shell with the number of failed tests as its exit code.
    $configuration.Run.Exit = $true
    Invoke-Pester -Configuration $configuration
    exit 0
}

if ($Part -eq 'Analysis') {
    Import-Module PSScriptAnalyzer -RequiredVersion 1.25.0
    $settings = Import-PowerShellDataFile (Join-Path $PSScriptRoot 'PSScriptAnalyzerSettings.psd1')
    $tests = Join-Path $PSScriptRoot 'tests'
    $findings = @(foreach ($file in Get-ChildItem $PSScriptRoot -Recurse -Filter *.ps1) {
        $fileSettings = @{}
        foreach ($key in $settings.Keys) { $fileSettings[$key] = $settings[$key] }
        $rules = @{}
        foreach ($key in $settings.Rules.Keys) { $rules[$key] = $settings.Rules[$key] }
        $fileSettings.Rules = $rules
        # A script that declares '#Requires -Version 7' runs only in PowerShell 7, so the checks for 5.1 skip it.
        if (Select-String -LiteralPath $file.FullName -Pattern '^#Requires -Version 7' -Quiet) {
            $rules.Remove('PSUseCompatibleSyntax')
            $rules.Remove('PSUseCompatibleCommands')
        } elseif ($file.FullName.StartsWith($tests + [IO.Path]::DirectorySeparatorChar)) {
            # The 5.1 profile knows only the Pester 3.4 that comes with Windows PowerShell, while the tests load Pester 6.2.0.
            $rules.Remove('PSUseCompatibleCommands')
        }
        Invoke-ScriptAnalyzer -Path $file.FullName -Settings $fileSettings
    })
    foreach ($finding in $findings) { "$($finding.ScriptPath):$($finding.Line) $($finding.RuleName): $($finding.Message)" }
    "PSScriptAnalyzer found $($findings.Count) problems."
    exit $(if ($findings.Count -gt 0) { 1 } else { 0 })
}

$failed = $false
foreach ($run in @(
        @{ Shell = 'powershell'; Part = 'Tests' }
        @{ Shell = 'pwsh'; Part = 'Tests' }
        @{ Shell = 'pwsh'; Part = 'Analysis' })) {
    "== $($run.Part) in $($run.Shell)"
    & $run.Shell -NoProfile -ExecutionPolicy Bypass -File $PSCommandPath -Part $run.Part
    if ($LASTEXITCODE -ne 0) { $failed = $true }
}
if ($failed) {
    'The script tests or checks failed.'
    exit 1
}
'The script tests and checks passed.'
