# Runs the live cases of sub-project 4 (design D20) through the debug hook provider-live on one test device, and prints
# one line for each case: the case, the model, ok or failed, whether the answer matched, the style, the tokens, the
# credits, whether Kestrel knew the price, and the time that the device measured. Each case starts on the device and the
# script asks for its answer every 2 s, up to -TimeoutSeconds (4 minutes, longer than a tool case's two calls with their
# retries); after that it waits until the device has ended the case before it starts the next. Without -Case it runs the
# whole plan: for each
# Copilot model of the session, a short text, a tool call with its result, and an image; JSON actions with the
# lightweight model; and last the allowance. A model that the subscription does not offer is replaced by the next one of
# its list (GPT-6 Luna, then Claude Haiku 5.5; Gemini 3.8 Flash, then Gemini 4 Hydrogen); when Claude Haiku 5.5 stands in
# for the lightweight model, its own text, tool and image cases are not run twice. A case fails when the device
# reports a failure, when its answer did not match, or when Kestrel did not know the model's price. -Headers
# ('name=value;name=value') and -MessagesPath are the debug overrides for a retry within the owner's session. With
# provider-standin on, the cases go to the stand-in on the device and make no request off it. -PassThru returns one
# object for each case, for scripts\a71-providers.ps1. It exits with 1 when a case failed. It never prints a prompt, a
# reply, a code, a token or a key: the hook answers only outcomes, counts and times.
# It needs PowerShell 7.3 or newer, because it runs scripts\hook.ps1.
# Example: scripts\provider-check.ps1 -Serial emulator-5554 -Case text -Model 'GPT-6 Luna'
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [ValidateSet('text', 'tool', 'image', 'json', 'allowance')][string]$Case,
    [string]$Model,
    [string]$Headers,
    [string]$MessagesPath,
    [int]$TimeoutSeconds = 240,
    [switch]$PassThru
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain

# The session's cases (design D20): each entry is a case and the models to try in order.
function Get-LivePlan([string]$Case, [string]$Model) {
    if ($Case) { return @([pscustomobject]@{ Case = $Case; Models = @(if ($Model) { $Model }) }) }
    $lightweight = @('GPT-6 Luna', 'Claude Haiku 5.5')
    $gemini = @('Gemini 3.8 Flash', 'Gemini 4 Hydrogen')
    $plan = foreach ($entry in @(
            @{ Models = $lightweight; Cases = @('text', 'tool', 'image', 'json') }
            @{ Models = @('GPT-6.1 Terra'); Cases = @('text', 'tool', 'image') }
            @{ Models = @('Claude Haiku 5.5'); Cases = @('text', 'tool', 'image') }
            @{ Models = $gemini; Cases = @('text', 'tool', 'image') }
        )) {
        foreach ($name in $entry.Cases) { [pscustomobject]@{ Case = $name; Models = $entry.Models } }
    }
    @($plan) + [pscustomobject]@{ Case = 'allowance'; Models = @() }
}

# The answer of provider-live -Result, such as 'case=text outcome=ok matched=true ...', as an ordered table.
function ConvertFrom-LiveAnswer([string]$Text) {
    $pairs = [ordered]@{}
    foreach ($pair in @("$Text".Trim() -split '\s+' | Where-Object { $_ })) {
        $name, $value = $pair -split '=', 2
        if (-not $name -or $null -eq $value) { throw "Not an answer of provider-live: '$Text'" }
        $pairs[$name] = $value
    }
    if (-not $pairs.Contains('outcome')) { throw "Not an answer of provider-live: '$Text'" }
    $pairs
}

# Whether a case passed: the device reported ok, the answer matched, and Kestrel knew the price (design D20). The
# stand-in skips the allowance, which counts as passed.
function Test-LivePassed($Answer) {
    if ($Answer['outcome'] -eq 'skipped') { return $true }
    if ($Answer['outcome'] -ne 'ok') { return $false }
    if ($Answer.Contains('matched') -and $Answer['matched'] -ne 'true') { return $false }
    if ($Answer.Contains('priceKnown') -and $Answer['priceKnown'] -ne 'true') { return $false }
    $true
}

# Whether the device said that the subscription does not offer the model, so that the next model of the list is tried.
function Test-ModelMissing($Answer) { $Answer['outcome'] -eq 'failed' -and $Answer['error'] -eq 'the-subscription-does-not-offer-this-model' }

# One line of the results, with only what the hook answered.
function Format-LiveLine([string]$Case, [string]$Model, $Answer) {
    $verdict = if ($Answer['outcome'] -eq 'skipped') { 'skipped' } elseif (Test-LivePassed $Answer) { 'ok' } else { 'failed' }
    $rest = @($Answer.Keys | Where-Object { $_ -notin 'case', 'outcome' } | ForEach-Object { "$_=$($Answer[$_])" }) -join ' '
    "$Case$(if ($Model) { " ($Model)" }): $verdict $rest".TrimEnd()
}

# Starts a case on the device and waits for its answer, up to $TimeoutSeconds.
function Invoke-LiveCase([string]$Case, [string]$Model, [string]$Headers, [string]$MessagesPath, [int]$TimeoutSeconds) {
    $with = @{ Case = $Case }
    if ($Model) { $with.Model = $Model }
    if ($Headers) { $with.Headers = $Headers }
    if ($MessagesPath) { $with.MessagesPath = $MessagesPath }
    $started = (& "$PSScriptRoot\hook.ps1" -Serial $Serial -Command provider-live @with) -join "`n"
    if ($started -ne 'started') { throw "provider-live did not start the case: $started" }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        Start-Sleep -Seconds 2
        $answer = (& "$PSScriptRoot\hook.ps1" -Serial $Serial -Command provider-live -Result) -join "`n"
        if ($answer -ne 'running') { return ConvertFrom-LiveAnswer $answer }
    }
    # The case still runs on the device, and the next start would be refused while it does, so wait for its end.
    $drain = [Diagnostics.Stopwatch]::StartNew()
    while ($drain.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        Start-Sleep -Seconds 2
        if (((& "$PSScriptRoot\hook.ps1" -Serial $Serial -Command provider-live -Result) -join "`n") -ne 'running') { break }
    }
    [ordered]@{ case = $Case; outcome = 'failed'; error = 'timeout' }
}

Assert-TestDevice -Serial $Serial
$failed = 0
$done = @{}
$results = foreach ($entry in Get-LivePlan -Case $Case -Model $Model) {
    $models = @($entry.Models)
    if ($models.Count -eq 0) { $models = @('') }
    # A case that already ran with this model, as Claude Haiku 5.5's when it stood in for GPT-6 Luna, counts once.
    if ($models.Count -eq 1 -and $done.ContainsKey("$($entry.Case)|$($models[0])")) { continue }
    $answer = $null
    $used = $null
    foreach ($name in $models) {
        $answer = Invoke-LiveCase -Case $entry.Case -Model $name -Headers $Headers -MessagesPath $MessagesPath -TimeoutSeconds $TimeoutSeconds
        $used = $name
        if (-not (Test-ModelMissing $answer)) { break }
    }
    $done["$($entry.Case)|$used"] = $true
    $line = Format-LiveLine -Case $entry.Case -Model $used -Answer $answer
    Write-Host $line
    $passed = Test-LivePassed $answer
    if (-not $passed) { $failed++ }
    [pscustomobject]@{ Case = $entry.Case; Model = $used; Passed = $passed; Answer = $answer; Line = $line }
}
if ($PassThru) { $results }
if ($failed -gt 0) { exit 1 }
