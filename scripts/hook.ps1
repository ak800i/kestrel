# Sends a test-hook command to Kestrel's debug build on a test device and prints the answer (design spec,
# section 16.1). Commands and their options:
#   state                                   whether the screen layer runs, test mode and the overlays
#   test-mode -On true|false                switches test mode
#   snapshot [-Out file.json]               what Kestrel reads on the screen, as JSON
#   overlay -Show numbers|unlabeled|labels|grid|none
#   overlay -Show feedback -Text 'text'     shows the feedback bar
#   tap -Key key | -Number n | -X x -Y y [-Long]
#   type -Key key | -Number n -Text 'text'
#   scroll -Direction up|down|left|right [-Key key | -Number n]
#   global -Action back|home|recent-apps|notifications|quick-settings|lock-screen|screenshot|power-menu|split-screen|play-pause
# -Number picks an element by the number that the numbers overlay shows on it.
# Example: scripts\hook.ps1 -Serial emulator-5554 tap -Number 3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('state', 'test-mode', 'snapshot', 'overlay', 'tap', 'type', 'scroll', 'global')][string]$Command,
    [string]$Key,
    [int]$Number,
    [int]$X = -1,
    [int]$Y = -1,
    [switch]$Long,
    [string]$Text,
    [string]$Direction,
    [string]$Action,
    [string]$Show,
    [ValidateSet('true', 'false')][string]$On,
    [string]$Out
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial

# The device's shell reads the command line, so every text value is quoted for it.
function ConvertTo-ShellText([string]$Value) { "'" + $Value.Replace("'", "'\''") + "'" }

$extras = @()
if ($Key) { $extras += "--es key $(ConvertTo-ShellText $Key)" }
if ($PSBoundParameters.ContainsKey('Number')) { $extras += "--ei number $Number" }
if ($X -ge 0) { $extras += "--ei x $X" }
if ($Y -ge 0) { $extras += "--ei y $Y" }
if ($Long) { $extras += '--ez long true' }
if ($PSBoundParameters.ContainsKey('Text')) { $extras += "--es text $(ConvertTo-ShellText $Text)" }
if ($Direction) { $extras += "--es direction $(ConvertTo-ShellText $Direction)" }
if ($Action) { $extras += "--es action $(ConvertTo-ShellText $Action)" }
if ($Show) { $extras += "--es show $(ConvertTo-ShellText $Show)" }
if ($On) { $extras += "--ez on $On" }

$hookAction = 'dev.kestrel.hook.' + $Command.ToUpperInvariant().Replace('-', '_')
$broadcast = "am broadcast -n dev.kestrel/dev.kestrel.app.debug.TestHookReceiver -a $hookAction $($extras -join ' ')"
# Screen text can be in any language, and adb passes it on as UTF-8.
$consoleEncoding = [Console]::OutputEncoding
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
try {
    $output = (& (Get-Adb) -s $Serial shell $broadcast) -join "`n"
} finally {
    [Console]::OutputEncoding = $consoleEncoding
}

$match = [regex]::Match($output, '(?s)Broadcast completed: result=(-?\d+)(?:, data="(.*)")?\s*$')
if (-not $match.Success) { throw "Unexpected answer from the device:`n$output" }
$data = $match.Groups[2].Value
switch ([int]$match.Groups[1].Value) {
    -1 {
        if ($Out) {
            $path = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Out)
            [IO.File]::WriteAllText($path, $data, [Text.UTF8Encoding]::new($false))
            "Saved to $path"
        } else {
            $data
        }
    }
    0 { throw 'Kestrel did not answer. Install a debug build with scripts\install.ps1.' }
    default { throw $data }
}
