# Settings for PSScriptAnalyzer, which scripts\test-scripts.ps1 runs over every script in this folder. The scripts must
# work in Windows PowerShell 5.1 as well as in PowerShell 7, so two rules check them against Windows PowerShell 5.1;
# test-scripts.ps1 applies those two rules only to the scripts that can run in 5.1.
@{
    IncludeDefaultRules = $true
    # ParseError: without it, PSScriptAnalyzer reports nothing for a script that does not parse.
    Severity = @('Error', 'Warning', 'ParseError')
    ExcludeRules = @(
        # Get-AccessibilitySettings and Restore-AccessibilitySettings handle a group of settings, and later scripts call
        # them by these names.
        'PSUseSingularNouns'
        # The scripts change test devices on purpose; -WhatIf and -Confirm would add nothing.
        'PSUseShouldProcessForStateChangingFunctions'
        # The scripts are run by hand and show progress and questions for the owner, which is what Write-Host is for.
        'PSAvoidUsingWriteHost'
    )
    Rules = @{
        PSUseCompatibleSyntax = @{
            Enable = $true
            TargetVersions = @('5.1')
        }
        PSUseCompatibleCommands = @{
            Enable = $true
            # Windows PowerShell 5.1 on Windows 10 Pro (build 17763): the profile for Windows 10 and 11 that
            # PSScriptAnalyzer 1.25.0 ships.
            TargetProfiles = @('win-48_x64_10.0.17763.0_5.1.17763.316_x64_4.0.30319.42000_framework')
        }
    }
}
