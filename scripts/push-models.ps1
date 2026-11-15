# Copies Kestrel's speech models to a test device (design spec, section 8.10). It downloads the files that
# models\manifest.json lists from their upstream sources into a cache on this computer, checks their SHA-256 hashes, and
# copies the files that the device lacks into Kestrel's folder /sdcard/Android/data/dev.kestrel/files/models. Kestrel
# must be installed first (scripts\install.ps1). The first run downloads about 760 MB; later runs download nothing.
# Uninstalling Kestrel deletes the models from the device.
# Example: scripts\push-models.ps1 -Serial emulator-5554
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    # Outside the repository, so that git clean keeps it and every clone shares it.
    [string]$Cache = (Join-Path $env:LOCALAPPDATA 'Kestrel\models'),
    # Only for the owner's decision to start daily use on the Galaxy S23+.
    [switch]$AllowDailyPhone
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
. "$PSScriptRoot\lib\Models.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial -AllowDailyPhone:$AllowDailyPhone
$manifest = Read-ModelManifest -Path (Join-Path $PSScriptRoot '..\models\manifest.json')
# Before the first download, which takes a while, so that a missing Kestrel shows at once.
Assert-KestrelInstalled -Serial $Serial
Save-ModelFiles -Manifest $manifest -Cache $Cache
Push-ModelFiles -Serial $Serial -Manifest $manifest -Cache $Cache
Write-Host "The speech models are on $Serial."
