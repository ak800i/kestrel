# Copies Kestrel's speech models and the OCR models of the agent's look_closer to a test device (design spec, section
# 8.10; design D20 of sub-project 5), the OCR models from ModelScope, Hugging Face and GitHub. It downloads the files that
# models\manifest.json lists into a cache on this computer, from the addresses in the manifest: sherpa-onnx's releases on
# GitHub (github.com) and Hugging Face (huggingface.co), which redirect to their own download servers. It checks the
# files' SHA-256 hashes. Then it copies the files that the device lacks, and those whose size or hash differs on the
# device, into Kestrel's folder /sdcard/Android/data/dev.kestrel/files/models. Kestrel must be installed first
# (scripts\install.ps1). Kestrel creates that folder when it starts, so the first run after an install may open Kestrel
# on the device. The first run downloads about 760 MB; later runs download nothing. With -Kokoro, it also copies the
# optional Kokoro voice (design spec, section 8.2), about 354 MB, and Kokoro's int8 model, 114 MB more, which only a voice
# device test loads until the A71 session has compared it (plan 2E). Uninstalling Kestrel deletes the models from
# the device.
# The folder is shared with Kestrel's own download of the models (step 2 of the setup checklist), and both keep to this
# contract (design spec, section 8.10):
# - On Android 17, Kestrel reads a folder or a file that the shell owns only through the bits for other users (spike S7).
#   So the script creates each missing folder with the mode 2775, gives that mode to the folders that the shell owns,
#   and adb push writes each file with the mode 666. A folder that Kestrel created, when it started or with its own
#   download, belongs to Kestrel with the group ext_data_rw and the mode 2770: Kestrel reads it as its owner, and the
#   shell, which is in that group, writes into it. Only its owner may change its mode, so the script leaves the mode
#   alone and stops with an error when the group may not write into the folder. It never deletes anything of Kestrel's,
#   except a model file of the wrong size or hash, which the copy replaces as Kestrel's download would.
# - Kestrel counts a model file as ready when its size and SHA-256 hash match the manifest that comes with the app,
#   whoever copied it. Its download fetches only the files that were missing or damaged when it started, and it moves a
#   file into place only after the file's size and hash matched, so it never puts a partial or stale file over a copy.
# - The download keeps its part files in the folder .download, which belongs to Kestrel. A cancelled or interrupted
#   download leaves them there, together with the folders of the files that it had already placed. Nothing resumes it
#   by itself: only the next tap on Download does, which fetches only the files that are still missing or damaged, and
#   continues their parts with an HTTP range. The script never touches .download. A part whose file the script copied
#   stays unused and costs only space; a later download that needs the file again continues the part and checks the
#   file's hash. Each time that its settings open, Kestrel checks the model files again, so it counts the copies as
#   ready, and the checklist's step 2 is done without another download once the copies hold every model that the step
#   asks for: with -Kokoro when the owner chose Kokoro there.
# - The mode 2775 lets only the shell create files in a folder that the script created. Kestrel's download cannot place
#   a file there, so it stops with a storage failure when such a folder lacks a file; run this script again instead.
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
Write-Host "The speech and OCR models are on $Serial."
