# Measures Kestrel's recognition on a voice corpus on a test device (design spec, sections 5.3 and 16.4): the word error
# rate, the share of utterances that Kestrel's Requests reads as intended, how long each transcription takes, how often
# the recogniser decodes a clip a second time without boost words, and the memory that Kestrel uses (PSS). -Corpus
# generated uses the clips that scripts\make-corpus.ps1 made in corpus\generated\<voice>; -Corpus owner uses the owner's
# recordings in corpus\owner\clips, which the A71 session makes and git ignores. For each clip, the script copies the clip
# into Kestrel's folder Android/data/dev.kestrel/files/eval, and the hook voice-eval transcribes it with the boost words
# of Kestrel's voice session and compares it there with the text that was to be said. For the owner's recordings the
# hook answers only counts and times, and the script prints only totals; for the generated clips, -Show also prints each
# clip that Kestrel misheard or read otherwise, with what it heard. Kestrel must be off, because its voice session would
# hold a second recogniser of about 700 MB. At the end, also when it fails or is interrupted, the script deletes the
# folder's clips on the device and closes the hook's recogniser. -NoBoost transcribes without the boost words, so that
# a second run shows what they change. It needs Kestrel's debug build, its speech models and PowerShell 7.3 or newer, as
# scripts\hook.ps1 does.
# Example: scripts\voice-eval.ps1 -Serial emulator-5554 -Corpus generated -Voice piper -Show
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][ValidateSet('generated', 'owner')][string]$Corpus,
    [ValidateSet('piper', 'kokoro')][string]$Voice = 'piper',
    [switch]$Show,
    [switch]$NoBoost
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
. "$PSScriptRoot\lib\Corpus.ps1"
if ($Show -and $Corpus -eq 'owner') { throw 'The owner''s recordings are private, so -Show works only with -Corpus generated.' }
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial
$root = Join-Path $PSScriptRoot '..\corpus'
$folder = if ($Corpus -eq 'owner') { Join-Path $root 'owner\clips' } else { Join-Path $root "generated\$Voice" }
$rows = @(Read-Corpus -Path (Join-Path $root 'commands.txt') | Where-Object { Test-Path -LiteralPath (Join-Path $folder "$($_.Id).wav") })
if ($rows.Count -eq 0) {
    $how = if ($Corpus -eq 'owner') { 'The A71 session records them.' } else { "Make them with scripts\make-corpus.ps1 -Serial $Serial -Voice $Voice." }
    throw "There are no clips in $folder. $how"
}
$remote = Get-EvalFolder
# The evaluation hook shows what Kestrel heard only in a clip whose name starts with gen-, so an owner's clip goes by
# another name.
$clip = if ($Corpus -eq 'owner') { 'clip' } else { 'gen-clip' }

# Runs scripts\hook.ps1 with a command and its parameters, which a hashtable names, and returns the answer as one text.
function Invoke-Hook([string]$Command, [hashtable]$With = @{}) {
    (& "$PSScriptRoot\hook.ps1" -Serial $Serial -Command $Command @With) -join "`n"
}

$totals = New-EvalTotals
$misheard = [System.Collections.Generic.List[string]]::new()
try {
    # Loading takes longer than a hook may run, so the hook loads in the background, and the script asks again.
    $state = Invoke-Hook voice-eval @{ Load = $true }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($state -eq 'loading' -and $watch.Elapsed.TotalSeconds -lt 300) {
        Start-Sleep -Seconds 2
        $state = Invoke-Hook voice-eval @{ Load = $true }
    }
    if ($state -notmatch '^ready words=(\d+) kept=(\d+)$') { throw "Kestrel's recogniser for the evaluation did not load: $state" }
    Write-Host "Boost words of Kestrel's voice session: $($Matches[1]), of which the recogniser keeps $($Matches[2])$(if ($NoBoost) { '; this run does not use them' })."
    foreach ($row in $rows) {
        Invoke-AdbCopy -Serial $Serial -Direction push -From (Join-Path $folder "$($row.Id).wav") -To "$remote/$clip.wav"
        $kind = if ($row.Expected -eq '-') { 'skip' } else { $row.Expected }
        $options = @{
            File = $clip
            Expected = $row.Text
            Kind = $kind
            Spotted = $row.Spotted
            AfterName = $row.Context -eq 'answer'
            Reveal = [bool]$Show
            NoBoost = [bool]$NoBoost
        }
        $answer = ConvertFrom-HookAnswer (Invoke-Hook voice-eval $options)
        Add-EvalResult -Totals $totals -Answer $answer
        $errors = [int]$answer.substitutions + [int]$answer.deletions + [int]$answer.insertions
        if ($Show -and ($errors -gt 0 -or $answer.request -eq 'wrong')) {
            $misheard.Add("$($row.Id): '$($row.Text)' was heard as '$($answer.heard)' and read as $($answer.read)")
        }
    }
} finally {
    try {
        Invoke-Hook voice-eval @{ Close = $true } | Out-Null
    } catch {
        Write-Warning "Could not close the evaluation's recogniser ($($_.Exception.Message)); it closes when Kestrel's process ends."
    }
    # For -Corpus owner, the clip on the device is one of the owner's recordings.
    try {
        Invoke-SettingsCommand -Serial $Serial -Command "rm -f $remote/*.wav" | Out-Null
    } catch {
        Write-Warning "Could not delete the clips in $remote on $Serial. Run: & '$((Get-Adb) -replace "'", "''")' -s $Serial shell 'rm -f $remote/*.wav'"
    }
}
Format-EvalSummary $totals | ForEach-Object { Write-Host $_ }
$misheard | ForEach-Object { Write-Host $_ }
