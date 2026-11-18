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

# Reads corpus\scenes.txt: the labels that the screen would show while the owner says some lines of corpus\commands.txt,
# which the evaluation hook gives the recogniser as boost words and the matcher as the screen (design spec, section
# 16.4). It has three kinds of lines, 'scene <name> | <label>; <label>; ...', 'line <id> | <scene>' and
# 'contacts | <name>; <name>; ...', which Read-CorpusContacts reads; lines that start with '#' and empty lines are
# skipped. Returns a table from each line's id to the labels of its scene. Throws, with the file and the line's number,
# on a line of another kind, a scene without labels or listed twice, a line that names a scene that no earlier line
# lists, or an id listed twice.
function Read-CorpusScenes([string]$Path) {
    $scenes = @{}
    $lines = @{}
    $number = 0
    foreach ($line in [IO.File]::ReadAllLines($Path, [Text.UTF8Encoding]::new($false))) {
        $number++
        if (-not $line.Trim() -or $line.TrimStart().StartsWith('#')) { continue }
        $where = "${Path}:${number}:"
        $head, $rest = $line -split '\|', 2
        $match = [regex]::Match($head.Trim(), '^(?:(scene|line) ([a-z0-9-]+)|contacts)$')
        if ($null -eq $rest -or -not $match.Success) {
            throw "$where a line must be 'scene <name> | <labels>', 'line <id> | <scene>' or 'contacts | <names>'."
        }
        if (-not $match.Groups[1].Success) { continue }
        $name = $match.Groups[2].Value
        $value = $rest.Trim()
        if ($match.Groups[1].Value -eq 'scene') {
            if ($scenes.ContainsKey($name)) { throw "$where the scene $name is listed twice." }
            $labels = @($value -split ';' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
            if ($labels.Count -eq 0) { throw "$where the scene $name needs labels." }
            $scenes[$name] = $labels
        } else {
            if (-not $scenes.ContainsKey($value)) { throw "$where unknown scene '$value'." }
            if ($lines.ContainsKey($name)) { throw "$where the line $name is listed twice." }
            $lines[$name] = $scenes[$value]
        }
    }
    $lines
}

# Reads the line 'contacts | <name>; <name>; ...' of corpus\scenes.txt: the contacts that the call lines of
# corpus\commands.txt name, which the evaluation hook gives only the contact matcher, never the recogniser, because
# Kestrel's voice session has no contact names as boost words (design spec, section 16.4). Returns the names, or nothing
# without such a line. Throws, with the file and the line's number, on a second such line or one without names.
function Read-CorpusContacts([string]$Path) {
    $contacts = $null
    $number = 0
    foreach ($line in [IO.File]::ReadAllLines($Path, [Text.UTF8Encoding]::new($false))) {
        $number++
        $head, $rest = $line -split '\|', 2
        if ($null -eq $rest -or $head.Trim() -ne 'contacts') { continue }
        $where = "${Path}:${number}:"
        if ($null -ne $contacts) { throw "$where the contacts are listed twice." }
        $contacts = @($rest -split ';' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
        if ($contacts.Count -eq 0) { throw "$where the contacts need names." }
    }
    $contacts
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
        Commands = 0
        RightCommands = 0
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
    # The answer of an instant command's line says whether it leads to the correct action; '-' is any other line.
    if ($Answer.Contains('command') -and $Answer.command -ne '-') {
        $Totals.Commands++
        if ($Answer.command -eq '1') { $Totals.RightCommands++ }
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
    if ($Totals.Commands -gt 0) {
        "Commands that lead to the correct action: $($Totals.RightCommands) of $($Totals.Commands) ($(Format-Percent $Totals.RightCommands $Totals.Commands) %)"
    }
    "Transcription: median $median ms, 90th percentile $ninetieth ms, longest $longest ms; at most $perSecond ms per second of audio"
    "Decoded a second time without boost words: $($Totals.Twice) of $($Totals.Clips) clips"
    "Kestrel's memory (PSS): at most $($Totals.Pss) MB"
}

# The word error rate of a group of lines, such as the dictation or the task lines, as one line of the summary.
function Format-GroupErrors([string]$Name, $Totals) {
    $errors = $Totals.Substitutions + $Totals.Deletions + $Totals.Insertions
    "${Name}: $($Totals.Clips) clips, $($Totals.Words) words, word error rate $(Format-Percent $errors $Totals.Words) %"
}
