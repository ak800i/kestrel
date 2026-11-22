# The agent's speech checks (design D28 of sub-project 5) for the check runner Invoke-AutoCheck: each check plays one clip
# of the agent's phrases through this computer's speakers, with the scripted model installed, and checks on the phone
# through the debug hooks what the phrase did. A task phrase must start a task (hook H2 and agent-task -Result -Tag); an
# answer must answer the question of a task that the check started by text before the clip (agent-state and
# agent-task -Result -Tag); and Kestrel's own phrases must be read as their requests (voice-state -Tag's read). The
# results inform and do not gate (design D27). Dot-source scripts\lib\Kestrel.ps1, scripts\lib\Session.ps1 and
# scripts\lib\AutoCheck.ps1 first; the caller defines Invoke-Hook([string]$Command, [hashtable]$With) and $Serial, the
# device that Assert-TestDevice accepted, as the check runner needs. Nothing here holds what Kestrel heard: the hooks
# answer only kinds, counts and states.

# The fixture on which the answer checks' tasks act: :testapp's views fixture, whose button "Rebuild" changes nothing
# that matters.
function Get-SpeechFixture { 'dev.kestrel.testapp/.ViewsActivity' }

# The scripted model's replies for a check's task, as plan 5A's ScriptedModel reads them: 'end' fails the task at its
# first reply, without any screen action; 'confirm' taps the fixture's "Rebuild" as a deleting action, which Standard
# mode confirms and test mode does not block, and then fails the task; 'wait' only waits, six times 10 s.
function Get-SpeechScript([ValidateSet('end', 'confirm', 'wait')][string]$Kind) {
    $end = @{ tool = 'fail'; arguments = @{ reason = 'The speech check ends here.' } }
    $replies = switch ($Kind) {
        'end' { @($end) }
        'confirm' { @(@{ tool = 'tap'; target = @{ label = 'Rebuild' }; arguments = @{ reason = 'The speech check asks to confirm.'; category = 'deleting' } }, $end) }
        'wait' { @(1..6 | ForEach-Object { @{ tool = 'wait'; arguments = @{ seconds = 10; reason = 'The speech check waits.' } } }) }
    }
    $replies
}

# Copies the scripted model's replies as a JSON array into Kestrel's folder files/agent-scripts on the device, through
# run-as, and installs them with the hook agent-script (plan 5B's format). The JSON goes as base64, so that no quote of
# it reaches the device's shell. Throws when the hook does not answer that it installed all of them.
function Install-AgentScript([string]$Name, [object[]]$Replies) {
    $json = ConvertTo-Json -InputObject @($Replies) -Depth 8 -Compress
    $base64 = [Convert]::ToBase64String([Text.UTF8Encoding]::new($false).GetBytes($json))
    Invoke-SettingsCommand -Serial $Serial -Command "echo $base64 | base64 -d | run-as dev.kestrel sh -c 'mkdir -p files/agent-scripts && cat > files/agent-scripts/$Name'" | Out-Null
    $answer = ConvertFrom-HookPairs (Invoke-Hook agent-script @{ File = $Name })
    if ($answer['script'] -ne 'installed' -or $answer['replies'] -ne "$(@($Replies).Count)") { throw "agent-script did not install $Name." }
}

# Reads an answer of 'agent-task -Result -Tag', such as 'state=done end=FINISHED steps=4 ...', into an ordered table.
function ConvertFrom-AgentTaskResult([string]$Text) {
    $result = ConvertFrom-HookPairs $Text
    if ($result['state'] -notin 'running', 'done') { throw "Not an answer of agent-task -Result: '$Text'" }
    $result
}

# Reads 'agent-task -Result -Tag' until the task with the tag has ended, at most $Seconds, and returns its last answer, or
# $null when no task has the tag yet. A hook call that fails, as before Kestrel has linked the tag to its task, counts as
# no answer.
function Wait-AgentTask([string]$Tag, [double]$Seconds = 600) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $result = $null
    while ($true) {
        try { $result = ConvertFrom-AgentTaskResult (Invoke-Hook agent-task @{ Result = $true; Tag = $Tag }) } catch { Write-Verbose 'agent-task did not answer yet.' }
        if ($result -and $result['state'] -eq 'done') { return $result }
        if ($watch.Elapsed.TotalSeconds -ge $Seconds) { return $result }
        Start-Sleep -Milliseconds 500
    }
}

# Reads 'agent-state -Tag' until $Until is true for its answer, at most $Seconds, and returns the last answer, or $null.
function Wait-AgentState([string]$Tag, [scriptblock]$Until, [double]$Seconds = 60) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $state = $null
    while ($true) {
        try { $state = ConvertFrom-HookPairs (Invoke-Hook agent-state @{ Tag = $Tag }) } catch { Write-Verbose 'agent-state did not answer yet.' }
        if ($state -and (& $Until $state)) { return $state }
        if ($watch.Elapsed.TotalSeconds -ge $Seconds) { return $state }
        Start-Sleep -Milliseconds 500
    }
}

# The speech checks, in the order in which they run: the six task phrases of design D3 (corpus\checks.txt k024 to k029),
# the answers to a task's confirmation and "stop" (k030, k031 and corpus\commands.txt's c071, c046 and c045), and
# Kestrel's own phrases (c090, c087, k032 and k033). Kind 'task' starts a task that the scripted model ends at once; 'answer'
# first starts a task by text whose scripted model asks to confirm ($Asking 'confirm') or only waits ('running'), and
# then expects the task's end ($End) and its count of confirmed actions ($Confirmed); 'phrase' expects Kestrel's reading
# of the transcript ($Read), and $Then is the text that the check sends afterwards, so that a question of Kestrel's
# never takes the next check's words.
function Get-AgentSpeechChecks {
    $task = { param($Name, $Clip) [pscustomobject]@{ Name = "task phrase: $Name"; Clip = $Clip; Kind = 'task'; Script = 'end'; Asking = ''; End = ''; Confirmed = ''; Read = ''; Then = '' } }
    $answer = { param($Name, $Clip, $Script, $Asking, $End, $Confirmed) [pscustomobject]@{ Name = "answer: $Name"; Clip = $Clip; Kind = 'answer'; Script = $Script; Asking = $Asking; End = $End; Confirmed = $Confirmed; Read = ''; Then = '' } }
    $phrase = { param($Name, $Clip, $Read, $Then) [pscustomobject]@{ Name = "Kestrel's phrase: $Name"; Clip = $Clip; Kind = 'phrase'; Script = ''; Asking = ''; End = ''; Confirmed = ''; Read = $Read; Then = $Then } }
    @(
        & $task 'the Viber message' 'k024'
        & $task 'the hotspot' 'k025'
        & $task 'Waze' 'k026'
        & $task 'Firefox' 'k027'
        & $task 'yesterday''s photos' 'k028'
        & $task 'the latest e-mail' 'k029'
        & $answer 'confirm' 'k030' 'confirm' 'confirm' 'FAILED_BY_MODEL' '1'
        & $answer 'yes' 'k031' 'confirm' 'confirm' 'FAILED_BY_MODEL' '1'
        & $answer 'no' 'c071' 'confirm' 'confirm' 'DECLINED' '0'
        & $answer 'cancel' 'c046' 'confirm' 'confirm' 'CANCELLED' '0'
        & $answer 'stop' 'c045' 'wait' 'running' 'CANCELLED' '0'
        & $phrase 'continue' 'c090' 'continue' ''
        & $phrase 'delete history' 'c087' 'deletehistory' 'no'
        & $phrase 'remember' 'k032' 'remember' 'no'
        & $phrase 'save a routine' 'k033' 'saveroutine' ''
    )
}

# A speech check's verdict (ConvertTo-Verdict's form) from the tagged state of the clip's transcript ($State, from
# voice-state -Tag) and the end of the check's task ($Result, from agent-task -Result -Tag, or $null).
function Get-AgentSpeechVerdict($Check, $State, $Result) {
    $ended = $Result -and $Result['state'] -eq 'done'
    $end = if ($Result) { "$($Result['end'])" } else { 'none' }
    switch ($Check.Kind) {
        'task' {
            $passed = $State['role'] -eq 'task' -and $ended
            [pscustomobject]@{ Passed = $passed; Detail = "role=$($State['role']) end=$end" }
        }
        'answer' {
            $confirmed = if ($Result) { "$($Result['confirmed'])" } else { 'none' }
            $passed = $ended -and $end -eq $Check.End -and $confirmed -eq $Check.Confirmed
            [pscustomobject]@{ Passed = $passed; Detail = "end=$end confirmed=$confirmed (expected end=$($Check.End) confirmed=$($Check.Confirmed))" }
        }
        default {
            [pscustomobject]@{ Passed = $State['read'] -eq $Check.Read; Detail = "read=$($State['read']) (expected $($Check.Read))" }
        }
    }
}

# Ends the check's task when it still runs, as after a clip that the phone did not match: an open question gets
# "cancel" through agent-answer, and a task without a question gets "stop" as a tagged transcript.
function Stop-SpeechTask([string]$Tag) {
    $state = Wait-AgentState -Tag $Tag -Until { $true } -Seconds 0
    if (-not $state -or $state['phase'] -eq 'ended') { return }
    if ($state['asking'] -ne 'none') {
        Invoke-Hook agent-answer @{ Tag = $Tag; Answer = 'cancel' } | Out-Null
    } else {
        Invoke-Hook voice @{ Send = 'heard'; Text = 'stop'; Tag = (New-CheckTag) } | Out-Null
    }
    Wait-AgentTask -Tag $Tag -Seconds 30 | Out-Null
}

# Runs one speech check with Invoke-AutoCheck and its clip ($Clip: Voice, Text, Path), which $Play plays. An answer
# check first opens the fixture, installs its script and starts its task by text, and waits until the task asks
# ($Check.Asking); a task check installs its script before the clip. Afterwards it ends a task that still runs and sends
# the phrase's $Then. Returns Invoke-AutoCheck's result, whose Line goes into the results.
function Invoke-AgentSpeechCheck($Check, $Clip, [scriptblock]$Play) {
    $setup = @{ Tag = '' }
    $before = {
        if ($Check.Script) { Install-AgentScript -Name 'speech-check.json' -Replies (Get-SpeechScript $Check.Script) }
        if ($Check.Kind -ne 'answer') { return $null }
        Invoke-SettingsCommand -Serial $Serial -Command "am start -n $(Get-SpeechFixture)" | Out-Null
        Start-Sleep -Seconds 2
        $setup.Tag = New-CheckTag
        Invoke-Hook voice @{ Send = 'heard'; Text = 'Kestrel, tap Rebuild on the views fixture.'; Named = $true; Tag = $setup.Tag } | Out-Null
        $asking = $Check.Asking
        $state = Wait-AgentState -Tag $setup.Tag -Seconds 60 -Until {
            param($State)
            if ($asking -eq 'running') { $State['phase'] -eq 'running' -and $State['asking'] -eq 'none' } else { $State['asking'] -eq $asking }
        }
        $ready = if ($asking -eq 'running') { $state -and $state['phase'] -eq 'running' } else { $state -and $state['asking'] -eq $asking }
        if (-not $ready) { throw "the check's task did not reach '$asking' within 60 s" }
        $setup.Tag
    }
    $wait = {
        param($Tag, $Seconds)
        if ($Check.Kind -eq 'task') { Wait-AgentTask -Tag $Tag -Seconds ([math]::Min($Seconds, 60)) }
        elseif ($Check.Kind -eq 'answer') { Wait-AgentTask -Tag $setup.Tag -Seconds ([math]::Min($Seconds, 60)) }
        else { $null }
    }
    # The runner gives the tagged state, the context of -Before and what -Wait returned; the verdict needs the first and the last.
    $verify = { param($State) Get-AgentSpeechVerdict -Check $Check -State $State -Result $args[1] }
    try {
        Invoke-AutoCheck -Name $Check.Name -Text '' -Clip $Clip -Play $Play -Before $before -Wait $wait -WaitSeconds 60 -Verify $verify
    } finally {
        if ($setup.Tag) { Stop-SpeechTask $setup.Tag }
        if ($Check.Then) { Invoke-Hook voice @{ Send = 'heard'; Text = $Check.Then; Tag = (New-CheckTag) } | Out-Null }
    }
}
