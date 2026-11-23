# Builds the debug APK and installs it with adb install -r (design spec, sections 13.6 and 15).
# The same Gradle call runs the privacy audit (section 13.2), so nothing is installed when the audit fails.
# Installing with adb avoids Android's restricted-settings block for accessibility services.
# -TestApp builds and installs the fixture app dev.kestrel.testapp instead, which the A71 sessions' automatic checks use
# (plan 4D); the session that installs it also uninstalls it.
# -Release builds the release APK instead, signed with this computer's debug key (Get-DebugSigningEnvironment), and
# installs it over the debug build, which keeps Kestrel's data. It has no test hooks, so put the debug build back with
# this script afterwards. Only the release workflow signs with the release key (section 17).
# Example: scripts\install.ps1 -Serial emulator-5554
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    # Only for the owner's decision to start daily use on the Galaxy S23+.
    [switch]$AllowDailyPhone,
    [switch]$TestApp,
    [switch]$Release
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial -AllowDailyPhone:$AllowDailyPhone
if ($TestApp) {
    & "$PSScriptRoot\..\gradlew.bat" --console=plain ':testapp:assembleDebug'
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & (Get-Adb) -s $Serial install -r (Join-Path $PSScriptRoot '..\testapp\build\outputs\apk\debug\testapp-debug.apk')
    exit $LASTEXITCODE
}
if ($Release) {
    $signing = Get-DebugSigningEnvironment
    $before = @{}
    foreach ($name in $signing.Keys) { $before[$name] = [Environment]::GetEnvironmentVariable($name) }
    try {
        foreach ($name in $signing.Keys) { Set-Item -Path "env:$name" -Value $signing[$name] }
        & "$PSScriptRoot\..\gradlew.bat" --console=plain ':app:assembleRelease' 'kestrelAudit'
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } finally {
        # A script runs in its caller's process, so the caller's window gets its own values back.
        foreach ($name in $signing.Keys) { [Environment]::SetEnvironmentVariable($name, $before[$name]) }
    }
    & (Get-Adb) -s $Serial install -r (Join-Path $PSScriptRoot '..\app\build\outputs\apk\release\app-release.apk')
    exit $LASTEXITCODE
}
& "$PSScriptRoot\..\gradlew.bat" --console=plain ':app:assembleDebug' 'kestrelAudit'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Get-Adb) -s $Serial install -r (Join-Path $PSScriptRoot '..\app\build\outputs\apk\debug\app-debug.apk')
exit $LASTEXITCODE
