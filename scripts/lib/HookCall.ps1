# The debug hooks' transport (design spec, section 16.1; plan 7G's amendment "The hooks' transport"): each hook is
# 'content call --uri content://dev.kestrel.hooks --method <COMMAND> --extra <name>:<type>:<value> ...' in the device's
# shell, which Kestrel's debug-only TestHookProvider answers on a binder thread of its process, so that a hook that waits
# for one of Kestrel's broadcasts never holds Kestrel's broadcasts back. scripts\hook.ps1 sends it; :tools:eval's
# HookClient builds and reads the same call. The functions run in Windows PowerShell 5.1 too, for the tests.

$KestrelHookUri = 'content://dev.kestrel.hooks'

# The device's shell reads the command line, so every text value is quoted for it.
function ConvertTo-ShellText([string]$Value) { "'" + $Value.Replace("'", "'\''") + "'" }

# One argument of a hook: content call's typed extra '<name>:<type>:<value>', of the type s (text), b (true or false), i
# (a whole number) or f (a decimal number), with '\' and ':' in the value escaped as 'content' reads them, quoted for the
# device's shell.
function Format-HookExtra {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][ValidateSet('s', 'b', 'i', 'f')][string]$Type,
        [AllowEmptyString()][string]$Value
    )
    '--extra ' + (ConvertTo-ShellText ("${Name}:${Type}:" + $Value.Replace('\', '\\').Replace(':', '\:')))
}

# The device command of the hook -Command, such as goal-control, whose method is its name in capitals with '_' for '-'
# (GOAL_CONTROL), with the arguments from Format-HookExtra.
function Format-HookCall([string]$Command, [string[]]$Extras = @()) {
    $method = $Command.ToUpperInvariant().Replace('-', '_')
    (@("content call --uri $KestrelHookUri --method $method") + $Extras) -join ' '
}

# Stops with an error whose FullyQualifiedErrorId names the hook call's outcome, so that a caller can tell them apart:
# KestrelHookSilent, KestrelHookCallerRefused or KestrelHookLost.
function Stop-HookCall([string]$ErrorId, [string]$Message) {
    throw [Management.Automation.ErrorRecord]::new([InvalidOperationException]::new($Message), $ErrorId, [Management.Automation.ErrorCategory]::InvalidResult, $null)
}

# Reads what 'content call' printed, its error output included, as the hook's reply: Code 0 with the answer in Text, or
# Code 1, Kestrel's own error answer, with its reason. TestHookProvider answers with a Bundle of exactly the keys code and
# text, which 'content' prints as 'Result: Bundle[{code=<code>, text=<text>}]', code first; the text is all that stands
# between 'text=' and the last '}]', so it may hold any character, also '}]' and line breaks. Every other output is one of
# content's errors, told apart as :tools:eval's HookClient.outcome tells them: a missing provider, as a release build or
# no Kestrel at all (KestrelHookSilent); a SecurityException, a refused caller (KestrelHookCallerRefused); and any other
# failure of the call, such as a DeadObjectException when Kestrel's process ended during the call, or an unknown code,
# which is lost: Kestrel may have acted, so the caller reads the state back before it sends a hook that acts again
# (KestrelHookLost).
function ConvertFrom-HookCallOutput([string]$Output) {
    $match = [regex]::Match($Output, '(?s)\AResult: Bundle\[\{code=(-?\d+), text=(.*)\}\]\s*\z')
    if ($match.Success) {
        $code = [int]$match.Groups[1].Value
        if ($code -eq 0 -or $code -eq 1) { return [pscustomobject]@{ Code = $code; Text = $match.Groups[2].Value } }
        Stop-HookCall KestrelHookLost "The hook's call was lost: its answer had the unknown code $code. Kestrel may have acted: read its state back before you send the hook again.`n$Output"
    }
    if ($Output -match 'Could not find provider: dev\.kestrel\.hooks') { Stop-HookCall KestrelHookSilent 'Kestrel did not answer: it has no test hooks. Install a debug build with scripts\install.ps1.' }
    $refusal = [regex]::Match($Output, '(?m)^java\.lang\.SecurityException\b.*$')
    if ($refusal.Success) { Stop-HookCall KestrelHookCallerRefused "Kestrel refused the hook's caller: $($refusal.Value.Trim()). Only adb's shell (uid 2000) and root (uid 0) may use its test hooks." }
    Stop-HookCall KestrelHookLost "The hook's call was lost: it reached the device but no answer of Kestrel's came back, as when Kestrel's process ended during the call. Kestrel may have acted: read its state back before you send the hook again.`n$Output"
}