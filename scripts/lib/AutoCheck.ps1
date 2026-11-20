# The check runner of the A71 sessions' automatic checks (plan 4D): it gives Kestrel a command as a tagged transcript
# (the text path, hook H1) or plays the command's clip through this computer's speakers and lets the phone match it to
# the expected text (the air path, hook H2), waits for the result of exactly that command by its tag, never for
# Kestrel's newest command, runs the check's own test of the device, and writes one line. Dot-source
# scripts\lib\Kestrel.ps1 and scripts\lib\Session.ps1 first; the caller defines Invoke-Hook([string]$Command,
# [hashtable]$With) and $Serial, the device that Assert-TestDevice accepted, as the session scripts do. Nothing here holds
# what Kestrel heard: the hooks answer only kinds, counts, times and hashes.

# A new tag for a transcript: 'k' and 12 hexadecimal characters, which hook.ps1 accepts ('^[A-Za-z0-9_-]{1,32}$').
function New-CheckTag { 'k' + [guid]::NewGuid().ToString('N').Substring(0, 12) }

# Whether a text is "yes, call" (process rule 10), which no check ever sends; the hook refuses it on a phone too.
function Test-YesCall([string]$Text) {
    ("$Text".ToLowerInvariant() -replace '[^a-z ]', ' ' -replace '\s+', ' ').Trim() -match '^(kestrel )?yes call( kestrel)?$'
}

# Reads an answer of 'voice-state -Tag', such as 'tag=k1 role=command command=7 task=0 kind=Scroll outcome=done
# asking=false phase=ended detail=none said=none shown=none read=other latest=7', into an ordered table. A transcript that
# started a task has 'role=task', 'command=0' and its task's number in 'task'; an answer without 'task' gets 'task=0'.
# With $Tag, an answer for another tag throws, because it is not the state of the check's own transcript.
function ConvertFrom-TaggedState([string]$Text, [string]$Tag = '') {
    $state = ConvertFrom-HookPairs $Text
    foreach ($name in 'tag', 'role', 'command', 'kind', 'outcome', 'asking', 'phase') {
        if (-not $state.Contains($name)) { throw "Not an answer of voice-state -Tag: '$Text'" }
    }
    if ($Tag -and $state['tag'] -cne $Tag) { throw "voice-state -Tag $Tag answered for another tag." }
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
# 'phase=ended' and 'outcome=running' is read again until the outcome is there. With $Report, a hashtable, it sets
# $Report.State to each state that it reads, $Report.TimedOut to whether the time ran out before $Until was true, and
# $Report.Countdown to $true once it reads a countdown to a call.
function Wait-TaggedState([string]$Tag, [scriptblock]$Until = { param($State) Test-TaggedSettled $State }, [double]$TimeoutSeconds = 15, [hashtable]$Report = $null) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    if ($Report) { $Report.TimedOut = $false }
    while ($true) {
        $state = ConvertFrom-TaggedState (Invoke-Hook voice-state @{ Tag = $Tag }) -Tag $Tag
        if ($Report) {
            $Report.State = $state
            if ($state['phase'] -eq 'countdown') { $Report.Countdown = $true }
        }
        $recorded = -not ($state['phase'] -eq 'ended' -and $state['outcome'] -eq 'running')
        if ((& $Until $state) -and $recorded) { return $state }
        if ($watch.Elapsed.TotalSeconds -ge $TimeoutSeconds) {
            if ($Report) { $Report.TimedOut = $true }
            return $state
        }
        Start-Sleep -Milliseconds 250
    }
}

# After a check whose command counted down to a call, or was a call: whether a call started on $Serial, from Android's
# call state ('mCallState' of 'dumpsys telephony.registry', Test-CallActive). A call that started is ended at once with
# the end-call key, on the A71 and on the emulator alike. Returns $null without a call, 'ended' when the call ended within
# 10 s, and 'active' when it did not.
function Stop-StartedCall {
    if (-not (Test-CallActive -Registry @(Invoke-SettingsCommand -Serial $Serial -Command 'dumpsys telephony.registry'))) { return $null }
    Invoke-SettingsCommand -Serial $Serial -Command 'input keyevent KEYCODE_ENDCALL' | Out-Null
    foreach ($try in 1..20) {
        Start-Sleep -Milliseconds 500
        if (-not (Test-CallActive -Registry @(Invoke-SettingsCommand -Serial $Serial -Command 'dumpsys telephony.registry'))) { return 'ended' }
    }
    'active'
}

# Reads Kestrel's state ('voice-state' without a tag) until it no longer speaks, at most $TimeoutSeconds, and then waits
# 300 ms more, because the listening pipeline resumes on the next frame. Kestrel transcribes nothing while it speaks, and
# cuts the part of an utterance that began during its reply, so a clip that plays then is lost. Returns whether Kestrel
# stopped speaking in time.
function Wait-NotSpeaking([double]$TimeoutSeconds = 15) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ((ConvertFrom-VoiceState (Invoke-Hook voice-state))['speaking'] -ne 'false') {
        if ($watch.Elapsed.TotalSeconds -ge $TimeoutSeconds) { return $false }
        Start-Sleep -Milliseconds 250
    }
    Start-Sleep -Milliseconds 300
    $true
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
# -Template does); with $Clip ({ Voice, Text, Path }) the air path waits until Kestrel no longer speaks (Wait-NotSpeaking,
# at most $QuietSeconds, after which the check fails without its clip, and the line says so), then asks the phone to
# expect the clip's text (voice-expect) and $Play plays the clip. Once the command asks its question, or with -RespondAt
# countdown once it counts down, and $RespondDelay seconds later, $Answer is sent as a tagged transcript, or $Respond
# runs, such as a tap on the screen. $RespondDelay counts from the moment that the runner sees the countdown, which is up
# to a second after it began, so with -RespondAt countdown it must leave room in Kestrel's 4 s countdown. A question that
# is still open at the end is answered "no", so that it never takes the next check's words; the line says so. A planned
# response to a question has up to 5 s to end it; when the runner still has to answer "no" after a planned response, the
# check fails, because the "no" ends a question as a touch does. No countdown to a call may end in a
# call: a countdown that the check did not plan, such as one after an answer, one that goes on 1 s after the check's
# planned response (also when that response was its own "cancel"), and one that goes on after a response that failed,
# gets "cancel" at once, before Kestrel can press Call, and the check fails; the line says so. Every wait of the runner,
# also the one after a response that failed and the one after its own "no", ends on a countdown. After a check whose
# command counted down or was a call, the runner reads Android's call state (Stop-StartedCall): a call that started is
# ended at once, and the check fails; the line says so. When a hook call or a test fails while the last state that the
# runner read was a countdown, the runner sends "cancel" before it passes the failure on, and ends a call that started. A
# wait for the command that runs out of time fails the check, and the line says so. With $Wait, such as a
# script block that polls sub-project 5's 'agent-task -Result -Tag' until a task ends, the runner first waits until
# Kestrel has handled the transcript (a task, or a command that settled or counts down, which it cancels without calling
# $Wait), then calls $Wait with the tag and $WaitSeconds (at most 600) instead of waiting for the command's end, reads the
# tagged state after it, and gives $Verify what $Wait returned as its third argument; $Wait cannot be combined with
# $Answer or $Respond. Returns the result with the line for the results file.
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
    [ValidateRange(1, 600)][double]$WaitSeconds = 600,
    [double]$QuietSeconds = 15
) {
    if ((Test-YesCall $Text) -or (Test-YesCall $Answer) -or ($Clip -and (Test-YesCall $Clip.Text))) {
        throw 'No check may say "yes, call" (process rule 10).'
    }
    if ($Wait -and ($Answer -or $Respond)) { throw 'A check with -Wait cannot answer or respond (-Answer, -Respond).' }
    $context = & $Before
    $tag = New-CheckTag
    $heard = $null
    $quiet = $true
    if ($Clip) {
        # Kestrel transcribes nothing while it speaks, such as the reply of the check before, so the clip waits for its end.
        $quiet = Wait-NotSpeaking -TimeoutSeconds $QuietSeconds
        if ($quiet) {
            Invoke-Hook voice-expect @{ Text = $Clip.Text; Tag = $tag; Seconds = [int][math]::Ceiling($TimeoutSeconds + 5) } | Out-Null
            # A clip that could not play lets its expectation end first, so that it does not drop the next check's words.
            try { & $Play $Clip } catch { Wait-ExpectResult -Tag $tag -TimeoutSeconds ($TimeoutSeconds + 10) | Out-Null; throw }
            $heard = Wait-ExpectResult -Tag $tag -TimeoutSeconds ($TimeoutSeconds + 10)
        }
    } elseif ($Send) {
        & $Send $tag
    } else {
        Invoke-Hook voice @{ Send = 'heard'; Text = $Text; Tag = $tag } | Out-Null
    }
    $state = $null
    $waited = $null
    $notes = @()
    $failed = $false
    # The last state that a wait read, and whether a wait for the command ran out of time.
    $run = @{ Report = @{ State = $null; TimedOut = $false; Countdown = $false }; TimedOut = $false }
    # Waits for the tagged state; a wait for the command's end ($Settle) that runs out of time is marked.
    $read = {
        param([scriptblock]$Until, [double]$Seconds = $TimeoutSeconds, [bool]$Settle = $true)
        $found = Wait-TaggedState -Tag $tag -Until $Until -TimeoutSeconds $Seconds -Report $run.Report
        if ($Settle -and $run.Report.TimedOut) { $run.TimedOut = $true }
        $found
    }
    # A countdown ends every wait, so that one that the check did not plan is cancelled in time.
    $settledOrCountdown = { param($State) $State['phase'] -eq 'countdown' -or (Test-TaggedSettled $State) }
    try {
        if (-not $Clip -or ($heard -and $heard['matched'] -eq 'true')) {
            $responds = $Answer -or $Respond
            if ($Wait) {
                # A transcript that starts a command instead of a task could count down to a call while $Wait polls.
                $state = & $read { param($State) $State['role'] -eq 'task' -or (Test-TaggedSettled $State) -or $State['phase'] -eq 'countdown' } $TimeoutSeconds $false
                if ($state['phase'] -ne 'countdown') {
                    $waited = & $Wait $tag $WaitSeconds
                    $state = & $read $settledOrCountdown $TimeoutSeconds $false
                }
            } else {
                $state = & $read $settledOrCountdown
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
                    # A response that took effect before it failed, such as a tap that picked a contact, can still lead to
                    # a countdown, which this wait ends on.
                    & $read $settledOrCountdown
                } elseif ($RespondAt -eq 'countdown') {
                    # The planned response has 1 s to stop the countdown; one that goes on gets "cancel" below.
                    $graced = & $read { param($State) $State['phase'] -ne 'countdown' } 1 $false
                    if ($graced['phase'] -eq 'countdown') { $graced } else { & $read $settledOrCountdown }
                } else {
                    # The planned response has up to 5 s to end the question, because a read right after it can still show
                    # the question before Kestrel has taken the answer or the touch. Then settled again also when the
                    # command asks another question, which the "no" below answers, or when the answer starts a countdown,
                    # which the check did not plan.
                    $left = & $read { param($State) $State['asking'] -ne 'true' -or $State['phase'] -eq 'countdown' } 5 $false
                    if ($left['asking'] -eq 'true' -and $left['phase'] -ne 'countdown') { $left } else { & $read $settledOrCountdown }
                }
            }
            # Until the command has settled without either: a countdown gets "cancel", and a question gets "no". Every wait
            # here ends on a countdown too, also the one after "no", which Kestrel drops while it opens the phone app and
            # announces a call, so that the countdown that follows is cancelled in time.
            $cancelled = $false
            $answeredNo = $false
            foreach ($round in 1..6) {
                if ($state['phase'] -eq 'countdown') {
                    Invoke-Hook voice @{ Send = 'heard'; Text = 'cancel'; Tag = (New-CheckTag) } | Out-Null
                    $cancelled = $true
                    $failed = $true
                    # A countdown that still shows 2 s later gets "cancel" again.
                    $state = & $read { param($State) $State['phase'] -ne 'countdown' } 2 $false
                    if ($state['phase'] -ne 'countdown') { $state = & $read $settledOrCountdown }
                } elseif ($state['asking'] -eq 'true') {
                    Invoke-Hook voice @{ Send = 'heard'; Text = 'no'; Tag = (New-CheckTag) } | Out-Null
                    $answeredNo = $true
                    $state = & $read { param($State) $State['phase'] -in 'none', 'ended', 'countdown' }
                } else {
                    break
                }
            }
            if ($cancelled) { $notes += 'it counted down to a call, which the check cancelled' }
            if ($answeredNo -and $responds -and $ready) {
                # The planned response did not end the question, so the "no" may have ended it as the response should have.
                $notes += 'it still asked a question after the planned response, which the check answered with no'
                $failed = $true
            } elseif ($answeredNo) {
                $notes += 'it asked a question, which the check answered with no'
            }
            # A countdown that ran out between two reads ends in a call, which is ended at once.
            if ($run.Report.Countdown -or $state['kind'] -eq 'Call') {
                $call = Stop-StartedCall
                if ($call) {
                    $notes += if ($call -eq 'ended') { 'a call started, which the check ended' } else { 'a call started, and it is still active; end it on the phone' }
                    $failed = $true
                }
            }
            if ($run.TimedOut) {
                $notes += "the command did not settle in $TimeoutSeconds s"
                $failed = $true
            }
        }
        $verdict = if ($state) {
            ConvertTo-Verdict (& $Verify $state $context $waited)
        } else {
            $why = if ($quiet) { 'the phone did not match the clip' } else { "Kestrel still spoke after $QuietSeconds s, so the clip did not play" }
            [pscustomobject]@{ Passed = $false; Detail = $why }
        }
    } catch {
        # A hook call or a test that failed during a countdown must not leave it running to a call.
        if ($run.Report.State -and $run.Report.State['phase'] -eq 'countdown') {
            try {
                Invoke-Hook voice @{ Send = 'heard'; Text = 'cancel'; Tag = (New-CheckTag) } | Out-Null
            } catch {
                Write-Verbose 'The cancel after a failure during a countdown failed too.'
            }
        }
        if ($run.Report.Countdown) {
            try {
                Stop-StartedCall | Out-Null
            } catch {
                Write-Verbose 'The check of the call state after a failure failed too.'
            }
        }
        throw
    }
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
