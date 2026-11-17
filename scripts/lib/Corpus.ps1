# Helpers for Kestrel's voice corpus (design spec, section 16.4): corpus\commands.txt, the clips that
# scripts\make-corpus.ps1 and the A71 session make, and the answers of the evaluation hook, which scripts\voice-eval.ps1
# adds up. Dot-source scripts\lib\Kestrel.ps1 first: Invoke-AdbCopy uses Get-Adb. Nothing here prints what Kestrel heard.

# The folder on a device in which the evaluation hooks read and write their clips: the folder 'eval' of Kestrel's
# external files folder, which adb can reach.
function Get-EvalFolder { '/sdcard/Android/data/dev.kestrel/files/eval' }

# Reads corpus\commands.txt: one utterance per line, in the columns id, group, context, spotted, expected, owner and
# text, separated by '|'. Lines that start with '#' and empty lines are skipped. Throws, with the file and the line's
# number, on a line with another number of columns, an id that is not lowercase letters, digits and '-' or that is
# listed twice, an unknown context or reading, a spotted or owner value other than 0 or 1, or an empty text.
function Read-Corpus([string]$Path) {
    $contexts = @('idle', 'answer')
    $readings = @('Name', 'WakeUp', 'Stop', 'StopNamed', 'PauseListening', 'TurnOff', 'OpenSettings', 'Task', 'Other', '-')
    $ids = @{}
    $number = 0
    foreach ($line in [IO.File]::ReadAllLines($Path, [Text.UTF8Encoding]::new($false))) {
        $number++
        if (-not $line.Trim() -or $line.TrimStart().StartsWith('#')) { continue }
        $where = "${Path}:${number}:"
        $parts = @($line -split '\|' | ForEach-Object { $_.Trim() })
        if ($parts.Count -ne 7) { throw "$where the line needs 7 columns separated by '|'." }
        $id, $group, $context, $spotted, $expected, $owner, $text = $parts
        if ($id -cnotmatch '^[a-z0-9-]+\z') { throw "$where the id must be lowercase letters, digits and '-'." }
        if ($ids.ContainsKey($id)) { throw "$where the id $id is listed twice." }
        if ($context -cnotin $contexts) { throw "$where the context must be idle or answer." }
        if ($spotted -notin '0', '1' -or $owner -notin '0', '1') { throw "$where spotted and owner must be 0 or 1." }
        if ($expected -cnotin $readings) { throw "$where unknown expected reading '$expected'." }
        if (-not $text) { throw "$where the line needs a text." }
        $ids[$id] = $true
        [pscustomobject]@{
            Id = $id
            Group = $group
            Context = $context
            Spotted = $spotted -eq '1'
            Expected = $expected
            Owner = $owner -eq '1'
            Text = $text
        }
    }
}

# How long the A71 session records an utterance of the owner, in whole seconds: half a second for each word and 2 s more,
# at least 4 s, and at most 15 s, the longest recording that the hook makes.
function Get-ClipSeconds([string]$Text) {
    $words = @($Text -split '\s+' | Where-Object { $_ }).Count
    [int][math]::Min(15, [math]::Max(4, [math]::Ceiling($words * 0.5) + 2))
}

# Copies a file to a device (push) or from it (pull) with adb, and fails with adb's own words when adb fails. adb's
# progress lines must not stop the script.
function Invoke-AdbCopy([string]$Serial, [ValidateSet('push', 'pull')][string]$Direction, [string]$From, [string]$To) {
    $ErrorActionPreference = 'Continue'
    $output = @(& (Get-Adb) -s $Serial $Direction $From $To 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "adb $Direction failed on ${Serial}: $(($output | ForEach-Object { "$_".Trim() }) -join ' ')" }
}

# Reads an answer of the evaluation hook, such as 'words=3 substitutions=0 ... twice=0', into an ordered table. The
# transcript, which the hook adds only when asked to reveal it, comes last as 'heard=...' and keeps its spaces. The error
# does not repeat the text, which could hold a transcript.
function ConvertFrom-HookAnswer([string]$Text) {
    $answer = [ordered]@{}
    $rest = "$Text".Trim()
    $at = $rest.IndexOf('heard=')
    if ($at -eq 0 -or ($at -gt 0 -and $rest[$at - 1] -eq ' ')) {
        $answer['heard'] = $rest.Substring($at + 6)
        $rest = $rest.Substring(0, $at).Trim()
    }
    foreach ($pair in @($rest -split '\s+' | Where-Object { $_ })) {
        $name, $value = $pair -split '=', 2
        if (-not $name -or $null -eq $value) { throw 'Not an answer of the evaluation hook.' }
        $answer[$name] = $value
    }
    if (-not $answer.Contains('words')) { throw 'Not an answer of the evaluation hook.' }
    $answer
}

# The totals of an evaluation, which Add-EvalResult fills and Format-EvalSummary prints.
function New-EvalTotals {
    [pscustomobject]@{
        Clips = 0
        Words = 0
        Substitutions = 0
        Deletions = 0
        Insertions = 0
        Checked = 0
        Intended = 0
        Twice = 0
        Pss = 0
        Millis = [System.Collections.Generic.List[double]]::new()
        PerSecond = [System.Collections.Generic.List[double]]::new()
    }
}

# Adds one answer of ConvertFrom-HookAnswer to the totals. An answer whose request was skipped, such as a call command's,
# does not count towards the requests.
function Add-EvalResult($Totals, $Answer) {
    $invariant = [Globalization.CultureInfo]::InvariantCulture
    $Totals.Clips++
    $Totals.Words += [int]$Answer.words
    $Totals.Substitutions += [int]$Answer.substitutions
    $Totals.Deletions += [int]$Answer.deletions
    $Totals.Insertions += [int]$Answer.insertions
    if ($Answer.request -ne 'skipped') {
        $Totals.Checked++
        if ($Answer.request -eq 'ok') { $Totals.Intended++ }
    }
    $millis = [double]::Parse([string]$Answer.millis, $invariant)
    $seconds = [double]::Parse([string]$Answer.seconds, $invariant)
    $Totals.Millis.Add($millis)
    if ($seconds -gt 0) { $Totals.PerSecond.Add($millis / $seconds) }
    $Totals.Twice += [int]$Answer.twice
    $Totals.Pss = [math]::Max($Totals.Pss, [int]$Answer.pss)
}

# A share in percent with one decimal, such as '12.5', or '-' when there is nothing to share.
function Format-Percent($Part, $Whole) {
    if ($Whole -gt 0) { [string]::Format([Globalization.CultureInfo]::InvariantCulture, '{0:0.0}', 100.0 * $Part / $Whole) } else { '-' }
}

# The lines that sum up an evaluation: the word error rate, the requests read as intended, the transcription times
# (median, 90th percentile, longest, and the longest per second of audio), the second decodings and the memory.
function Format-EvalSummary($Totals) {
    $sorted = @($Totals.Millis | Sort-Object)
    $median = 0
    $ninetieth = 0
    $longest = 0
    if ($sorted.Count -gt 0) {
        $median = $sorted[[int][math]::Floor(($sorted.Count - 1) * 0.5)]
        $ninetieth = $sorted[[int][math]::Floor(($sorted.Count - 1) * 0.9)]
        $longest = $sorted[-1]
    }
    $perSecond = if ($Totals.PerSecond.Count -gt 0) { [math]::Round(($Totals.PerSecond | Measure-Object -Maximum).Maximum) } else { 0 }
    $errors = $Totals.Substitutions + $Totals.Deletions + $Totals.Insertions
    "Clips: $($Totals.Clips); words: $($Totals.Words); word error rate: $(Format-Percent $errors $Totals.Words) % " +
        "($($Totals.Substitutions) substitutions, $($Totals.Deletions) deletions, $($Totals.Insertions) insertions)"
    "Requests read as intended: $($Totals.Intended) of $($Totals.Checked) ($(Format-Percent $Totals.Intended $Totals.Checked) %)"
    "Transcription: median $median ms, 90th percentile $ninetieth ms, longest $longest ms; at most $perSecond ms per second of audio"
    "Decoded a second time without boost words: $($Totals.Twice) of $($Totals.Clips) clips"
    "Kestrel's memory (PSS): at most $($Totals.Pss) MB"
}
