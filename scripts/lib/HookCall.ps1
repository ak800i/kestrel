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

# Reads what 'content call' printed, its error output included, as the hook's reply: Code 0 with the answer in Text, or
# Code 1 with the reason. TestHookProvider answers with a Bundle of exactly the keys code and text, which 'content'
# prints as 'Result: Bundle[{code=<code>, text=<text>}]', code first; the text is all that stands between 'text=' and the
# last '}]', so it may hold any character, also '}]' and line breaks. Throws when Kestrel has no hooks, as a release build
# or no Kestrel at all, and for any other output, such as a refusal.
function ConvertFrom-HookCallOutput([string]$Output) {
    $match = [regex]::Match($Output, '(?s)\AResult: Bundle\[\{code=(-?\d+), text=(.*)\}\]\s*\z')
    if ($match.Success) { return [pscustomobject]@{ Code = [int]$match.Groups[1].Value; Text = $match.Groups[2].Value } }
    if ($Output -match 'Could not find provider: dev\.kestrel\.hooks') { throw 'Kestrel did not answer: it has no test hooks. Install a debug build with scripts\install.ps1.' }
    throw "Unexpected answer from the device:`n$Output"
}
