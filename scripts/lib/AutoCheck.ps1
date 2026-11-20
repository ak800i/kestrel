# The check runner of the A71 sessions' automatic checks (plan 4D): it gives Kestrel a command as a tagged transcript
# (the text path, hook H1) or plays the command's clip through this computer's speakers and lets the phone match it to
# the expected text (the air path, hook H2), waits for the result of exactly that command by its tag, never for
# Kestrel's newest command, runs the check's own test of the device, and writes one line. Dot-source
# scripts\lib\Session.ps1 first; the caller defines Invoke-Hook([string]$Command, [hashtable]$With), as the session
# scripts do. Nothing here holds what Kestrel heard: the hooks answer only kinds, counts, times and hashes.

# A new tag for a transcript: 'k' and 12 hexadecimal characters, which hook.ps1 accepts ('^[A-Za-z0-9_-]{1,32}$').
function New-CheckTag { 'k' + [guid]::NewGuid().ToString('N').Substring(0, 12) }

# Whether a text is "yes, call" (process rule 10), which no check ever sends; the hook refuses it on a phone too.
function Test-YesCall([string]$Text) {
    ("$Text".ToLowerInvariant() -replace '[^a-z ]', ' ' -replace '\s+', ' ').Trim() -match '^(kestrel )?yes call( kestrel)?$'
}

# Reads an answer of 'voice-state -Tag', such as 'tag=k1 role=command command=7 task=0 kind=Scroll outcome=done
# asking=false phase=ended detail=none said=none shown=none read=other latest=7', into an ordered table. A transcript that
# started a task has 'role=task', 'command=0' and its task's number in 'task'; an answer without 'task' gets 'task=0'.
function ConvertFrom-TaggedState([string]$Text) {
    $state = ConvertFrom-HookPairs $Text
    foreach ($name in 'tag', 'role', 'command', 'kind', 'outcome', 'asking', 'phase') {
        if (-not $state.Contains($name)) { throw "Not an answer of voice-state -Tag: '$Text'" }
    }
    if (-not $state.Contains('task')) { $state['task'] = '0' }
    $state
}

# Whether the tagged transcript's command has settled: Kestrel handled the transcript, and its command ended, asks a
# question, or never started.
function Test-TaggedSettled($State) {
    $State['role'] -ne 'pending' -and $State['phase'] -in 'none', 'ended', 'question'
}

# Reads 'voice-state -Tag' until $Until is true for the state, at most $TimeoutSeconds, and returns the last state.
# Kestrel records the outcome of a command that ended or was cancelled just after its phase changes, so a state with
# 'phase=ended' and 'outcome=running' is read again until the outcome is there.
function Wait-TaggedState([string]$Tag, [scriptblock]$Until = { param($State) Test-TaggedSettled $State }, [double]$TimeoutSeconds = 15) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($true) {
        $state = ConvertFrom-TaggedState (Invoke-Hook voice-state @{ Tag = $Tag })
        $recorded = -not ($state['phase'] -eq 'ended' -and $state['outcome'] -eq 'running')
        if (((& $Until $state) -and $recorded) -or $watch.Elapsed.TotalSeconds -ge $TimeoutSeconds) { return $state }
        Start-Sleep -Milliseconds 250
    }
}

# Reads 'voice-expect -Result -Tag' until the expectation has ended, at most $TimeoutSeconds, and returns its answer as
# an ordered table, or $null while it still waits.
function Wait-ExpectResult([string]$Tag, [double]$TimeoutSeconds = 30) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($true) {
        $text = "$(Invoke-Hook voice-expect @{ Result = $true; Tag = $Tag })".Trim()
        if ($text -notlike 'waiting*') { return ConvertFrom-HookPairs $text }
        if ($watch.Elapsed.TotalSeconds -ge $TimeoutSeconds) { return $null }
        Start-Sleep -Milliseconds 250
    }
}

# A check's verdict from what its test returned: $true, $false, or an object with Passed and Detail, where Detail names
# only states and numbers of the device, never screen text.
function ConvertTo-Verdict($Result) {
    if ($Result -is [bool]) { return [pscustomobject]@{ Passed = $Result; Detail = '' } }
    if ($null -eq $Result) { return [pscustomobject]@{ Passed = $false; Detail = 'no test result' } }
    $detail = if ($Result.Detail) { "$($Result.Detail)" } else { '' }
    [pscustomobject]@{ Passed = [bool]$Result.Passed; Detail = $detail }
}

# One automatic check. $Before reads the device before the command, and its result goes to $Verify as its second
# argument; $Verify gets the tagged state (voice-state -Tag) and returns the verdict (ConvertTo-Verdict). Without $Clip
# the text path sends $Text as a tagged transcript, or $Send sends it with the tag as its argument (as screen-has
# -Template does); with $Clip ({ Voice, Text, Path }) the air path asks the phone to expect the clip's text
# (voice-expect) and $Play plays the clip. Once the command asks its question, or with -RespondAt countdown once it counts
# down, and $RespondDelay seconds later, $Answer is sent as a tagged transcript, or $Respond runs, such as a tap on the
# screen. A question that is still open at the end is answered "no", so that it never takes the next check's words; the
# line says so. A countdown to a call that the check did not plan, or one that goes on after a response that failed, gets
# "cancel" at once, before Kestrel can press Call, and the check fails; the line says so. With $Wait, such as a script block that polls sub-project 5's 'agent-task -Result -Tag' until a task
# ends, the runner calls $Wait with the tag and $WaitSeconds (at most 600) instead of waiting for the command's end,
# reads the tagged state after it, and gives $Verify what $Wait returned as its third argument; $Wait cannot be
# combined with $Answer or $Respond. Returns the result with the line for the results file.
function Invoke-AutoCheck(
    [string]$Name,
    [string]$Text,
    [scriptblock]$Verify,
    [scriptblock]$Before = { $null },
    [string]$Answer = '',
    [scriptblock]$Respond = $null,
    [ValidateSet('question', 'countdown')][string]$RespondAt = 'question',
    [double]$RespondDelay = 0,
    [scriptblock]$Send = $null,
    $Clip = $null,
    [scriptblock]$Play = { },
    [double]$TimeoutSeconds = 15,
    [scriptblock]$Wait = $null,
    [ValidateRange(1, 600)][double]$WaitSeconds = 600
) {
    if ((Test-YesCall $Text) -or (Test-YesCall $Answer) -or ($Clip -and (Test-YesCall $Clip.Text))) {
        throw 'No check may say "yes, call" (process rule 10).'
    }
    if ($Wait -and ($Answer -or $Respond)) { throw 'A check with -Wait cannot answer or respond (-Answer, -Respond).' }
    $context = & $Before
    $tag = New-CheckTag
    $heard = $null
    if ($Clip) {
        Invoke-Hook voice-expect @{ Text = $Clip.Text; Tag = $tag; Seconds = [int][math]::Ceiling($TimeoutSeconds + 5) } | Out-Null
        # A clip that could not play lets its expectation end first, so that it does not drop the next check's words.
        try { & $Play $Clip } catch { Wait-ExpectResult -Tag $tag -TimeoutSeconds ($TimeoutSeconds + 10) | Out-Null; throw }
        $heard = Wait-ExpectResult -Tag $tag -TimeoutSeconds ($TimeoutSeconds + 10)
    } elseif ($Send) {
        & $Send $tag
    } else {
        Invoke-Hook voice @{ Send = 'heard'; Text = $Text; Tag = $tag } | Out-Null
    }
    $state = $null
    $waited = $null
    $notes = @()
    $failed = $false
    if (-not $Clip -or ($heard -and $heard['matched'] -eq 'true')) {
        $responds = $Answer -or $Respond
        if ($Wait) {
            $waited = & $Wait $tag $WaitSeconds
            $state = Wait-TaggedState -Tag $tag -Until { $true } -TimeoutSeconds $TimeoutSeconds
        } else {
            $until = if ($responds -and $RespondAt -eq 'countdown') {
                { param($State) $State['phase'] -eq 'countdown' -or ($State['role'] -ne 'pending' -and $State['phase'] -in 'none', 'ended') }
            } else {
                # A countdown ends the wait too, so that one that the check did not plan is cancelled in time.
                { param($State) $State['phase'] -eq 'countdown' -or (Test-TaggedSettled $State) }
            }
            $state = Wait-TaggedState -Tag $tag -Until $until -TimeoutSeconds $TimeoutSeconds
        }
        $ready = if ($RespondAt -eq 'countdown') { $state['phase'] -eq 'countdown' } else { $state['asking'] -eq 'true' }
        if ($responds -and $ready) {
            if ($RespondDelay -gt 0) { Start-Sleep -Milliseconds ([int]($RespondDelay * 1000)) }
            try {
                if ($Respond) { & $Respond } else { Invoke-Hook voice @{ Send = 'heard'; Text = $Answer; Tag = (New-CheckTag) } | Out-Null }
            } catch {
                $failed = $true
                $notes += "the response failed ($(("$($_.Exception.Message)" -split "`r?`n")[0]))"
            }
            $state = if ($failed) {
                Wait-TaggedState -Tag $tag -Until { $true } -TimeoutSeconds $TimeoutSeconds
            } else {
                # Settled again also when the command asks another question, which the "no" below answers.
                Wait-TaggedState -Tag $tag -Until { param($State) $State['phase'] -ne 'countdown' -and (Test-TaggedSettled $State) } -TimeoutSeconds $TimeoutSeconds
            }
        }
        if ($state['phase'] -eq 'countdown' -and ($failed -or -not ($responds -and $RespondAt -eq 'countdown'))) {
            Invoke-Hook voice @{ Send = 'heard'; Text = 'cancel'; Tag = (New-CheckTag) } | Out-Null
            $state = Wait-TaggedState -Tag $tag -Until { param($State) $State['phase'] -in 'none', 'ended', 'question' } -TimeoutSeconds $TimeoutSeconds
            $notes += 'it counted down to a call, which the check cancelled'
            $failed = $true
        }
        if ($state['asking'] -eq 'true') {
            Invoke-Hook voice @{ Send = 'heard'; Text = 'no'; Tag = (New-CheckTag) } | Out-Null
            $state = Wait-TaggedState -Tag $tag -Until { param($State) $State['phase'] -in 'none', 'ended' } -TimeoutSeconds $TimeoutSeconds
            $notes += 'it asked a question, which the check answered with no'
        }
    }
    $verdict = if ($state) { ConvertTo-Verdict (& $Verify $state $context $waited) } else { [pscustomobject]@{ Passed = $false; Detail = 'the phone did not match the clip' } }
    if ($verdict.Detail) { $notes = @($verdict.Detail) + $notes }
    if ($failed) { $verdict.Passed = $false }
    $path = if ($Clip) { $Clip.Voice } else { 'text' }
    $command = if ($state) { "role=$($state['role']) kind=$($state['kind']) outcome=$($state['outcome'])" } else { 'role=- kind=- outcome=-' }
    $line = "$Name [$path]: $command check=$(if ($verdict.Passed) { 'pass' } else { 'fail' })"
    if ($notes) { $line += " ($($notes -join '; '))" }
    if ($Clip) {
        $line += if ($heard) {
            " matched=$($heard['matched']) words=$($heard['words']) errors=$($heard['errors']) stray=$($heard['stray']) split=$($heard['split'])"
        } else {
            ' matched=none'
        }
    }
    [pscustomobject]@{ Name = $Name; Path = $path; Tag = $tag; State = $state; Heard = $heard; Waited = $waited; Passed = $verdict.Passed; Line = $line }
}
