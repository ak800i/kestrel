# Makes the generated voice corpus (design spec, section 16.4): a test device says every utterance of
# corpus\commands.txt in one of Kestrel's voices, through the hook voice-synth, as a WAV clip of 16 kHz, and the script
# copies the clips into corpus\generated\<voice>, which git ignores. It overwrites the clips of the utterances that the
# file still has; a clip whose utterance was removed from the file stays, and scripts\voice-eval.ps1 does not measure it.
# It needs Kestrel's debug build, its speech models (scripts\push-models.ps1, with -Kokoro for -Voice kokoro) and
# PowerShell 7.3 or newer, as scripts\hook.ps1 does. The device writes each clip into Kestrel's folder
# Android/data/dev.kestrel/files/eval; the script deletes it there once it has copied it, and at the end it deletes the
# folder's clips and closes the voice that the hook keeps loaded, also when it fails or is interrupted.
# Example: scripts\make-corpus.ps1 -Serial emulator-5554 -Voice kokoro
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [ValidateSet('piper', 'kokoro')][string]$Voice = 'piper'
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
. "$PSScriptRoot\lib\Corpus.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial
$rows = @(Read-Corpus -Path (Join-Path $PSScriptRoot '..\corpus\commands.txt'))
$folder = Join-Path $PSScriptRoot "..\corpus\generated\$Voice"
New-Item -ItemType Directory -Force -Path $folder | Out-Null
$remote = Get-EvalFolder

# Runs scripts\hook.ps1 with a command and its parameters, which a hashtable names, and returns the answer as one text.
function Invoke-Hook([string]$Command, [hashtable]$With = @{}) {
    (& "$PSScriptRoot\hook.ps1" -Serial $Serial -Command $Command @With) -join "`n"
}

try {
    foreach ($row in $rows) {
        # A generated clip's name starts with gen-, so that the evaluation hook may show what Kestrel heard in it.
        Invoke-Hook voice-synth @{ Text = $row.Text; File = "gen-$($row.Id)"; Voice = $Voice } | Out-Null
        Invoke-AdbCopy -Serial $Serial -Direction pull -From "$remote/gen-$($row.Id).wav" -To (Join-Path $folder "$($row.Id).wav")
        Invoke-SettingsCommand -Serial $Serial -Command "rm -f $remote/gen-$($row.Id).wav" | Out-Null
    }
} finally {
    try {
        Invoke-Hook voice-synth @{ Close = $true } | Out-Null
    } catch {
        Write-Warning "Could not close the voice that the hook loaded ($($_.Exception.Message)); it closes when Kestrel's process ends."
    }
    try {
        Invoke-SettingsCommand -Serial $Serial -Command "rm -f $remote/*.wav" | Out-Null
    } catch {
        Write-Warning "Could not delete the clips in $remote on $Serial. Run: & '$((Get-Adb) -replace "'", "''")' -s $Serial shell 'rm -f $remote/*.wav'"
    }
}
Write-Host "Made $($rows.Count) clips in $((Resolve-Path -LiteralPath $folder).Path)."
