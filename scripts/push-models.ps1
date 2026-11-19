# Copies Kestrel's speech models to a test device (design spec, section 8.10). It downloads the files that
# models\manifest.json lists into a cache on this computer, from the addresses in the manifest: sherpa-onnx's releases on
# GitHub (github.com) and Hugging Face (huggingface.co), which redirect to their own download servers. It checks the
# files' SHA-256 hashes. Then it copies the files that the device lacks, and those whose size or hash differs on the
# device, into Kestrel's folder /sdcard/Android/data/dev.kestrel/files/models. Kestrel must be installed first
# (scripts\install.ps1). Kestrel creates that folder when it starts, so the first run after an install may open Kestrel
# on the device. The first run downloads about 760 MB; later runs download nothing. With -Kokoro, it also copies the
# optional Kokoro voice (design spec, section 8.2), about 354 MB, and Kokoro's int8 model, 114 MB more, which only a voice
# device test loads until the A71 session has compared it (plan 2E). Uninstalling Kestrel deletes the models from
# the device.
# Example: scripts\push-models.ps1 -Serial emulator-5554 -Kokoro
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    # Outside the repository, so that git clean keeps it and every clone shares it.
    [string]$Cache = (Join-Path $env:LOCALAPPDATA 'Kestrel\models'),
    # Also the optional Kokoro voice.
    [switch]$Kokoro,
    # Only for the owner's decision to start daily use on the Galaxy S23+.
    [switch]$AllowDailyPhone
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
. "$PSScriptRoot\lib\Models.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial -AllowDailyPhone:$AllowDailyPhone
$manifest = Select-ModelManifest -Manifest (Read-ModelManifest -Path (Join-Path $PSScriptRoot '..\models\manifest.json')) -IncludeOptional:$Kokoro
# Before the first download, which takes a while, so that a missing Kestrel shows at once.
Assert-KestrelInstalled -Serial $Serial
Save-ModelFiles -Manifest $manifest -Cache $Cache
Push-ModelFiles -Serial $Serial -Manifest $manifest -Cache $Cache
Write-Host "The speech models are on $Serial."
