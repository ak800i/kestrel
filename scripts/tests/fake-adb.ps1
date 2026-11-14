# A stand-in for adb in the tests of scripts\lib\Kestrel.ps1; fake-adb.cmd runs it in its own process, as adb runs.
# It understands 'adb -s <serial> shell "<command>"' for the settings commands that the helpers send, and it keeps the
# device's state in the JSON file whose path is in the environment variable KESTREL_FAKE_ADB:
#   settings  names and values of the secure settings; a missing name is an unset setting
#   failures  how many of the next commands fail, as a busy device fails them: 'Bad arguments' and no marker
#   calls     every shell command that it received, in order
# The serial 'missing' stands for a device that adb cannot find. Error lines go to standard error, as adb's do.
$statePath = $env:KESTREL_FAKE_ADB
if (-not $statePath -or -not (Test-Path -LiteralPath $statePath)) {
    [Console]::Error.WriteLine('fake adb: KESTREL_FAKE_ADB must name an existing state file')
    exit 1
}
if ($args.Count -lt 4 -or $args[0] -ne '-s' -or $args[2] -ne 'shell') {
    [Console]::Error.WriteLine("fake adb: unsupported arguments: $($args -join ' ')")
    exit 1
}
$serial = $args[1]
# adb joins the words after 'shell' into one command line for the device's shell.
$command = $args[3..($args.Count - 1)] -join ' '

$state = [IO.File]::ReadAllText($statePath) | ConvertFrom-Json
$settings = [ordered]@{}
foreach ($property in $state.settings.PSObject.Properties) { $settings[$property.Name] = [string]$property.Value }
$failures = [int]$state.failures
$calls = @(@($state.calls) | Where-Object { $null -ne $_ } | ForEach-Object { [string]$_ }) + $command

function Save-State {
    $json = ConvertTo-Json -Depth 3 -InputObject ([ordered]@{ settings = $settings; failures = $failures; calls = [string[]]$calls })
    [IO.File]::WriteAllText($statePath, $json)
}

if ($serial -eq 'missing') {
    Save-State
    [Console]::Error.WriteLine("adb.exe: device '$serial' not found")
    exit 1
}
if ($failures -gt 0) {
    $failures--
    Save-State
    [Console]::Error.WriteLine('Bad arguments')
    exit 255
}

# The helpers end every command with this marker; the commands in their warnings, which the owner pastes, have none.
$marker = ' && echo kestrel-end'
$withMarker = $command.EndsWith($marker)
$deviceCommand = if ($withMarker) { $command.Substring(0, $command.Length - $marker.Length) } else { $command }
$lines = @()
if ($deviceCommand -match '^settings get secure (\S+)$') {
    $lines = @(if ($settings.Contains($Matches[1])) { $settings[$Matches[1]] } else { 'null' })
} elseif ($deviceCommand -match "^settings put secure (\S+) '") {
    # As in the device's shell, a single quote ends the quoted value, so a quote inside the value must be written '\''.
    if ($deviceCommand -notmatch "^settings put secure (\S+) '((?:[^']|'\\'')*)'$") {
        Save-State
        [Console]::Error.WriteLine('/system/bin/sh: no closing quote')
        exit 1
    }
    $settings[$Matches[1]] = $Matches[2].Replace("'\''", "'")
} elseif ($deviceCommand -match '^settings delete secure (\S+)$') {
    $lines = @("Deleted $(if ($settings.Contains($Matches[1])) { 1 } else { 0 }) rows")
    $settings.Remove($Matches[1])
} elseif ($deviceCommand -eq 'settings list secure') {
    $lines = @($settings.Keys | ForEach-Object { "$_=$($settings[$_])" })
} else {
    Save-State
    [Console]::Error.WriteLine("fake adb: unsupported shell command: $deviceCommand")
    exit 1
}
Save-State
foreach ($line in $lines) { [Console]::Out.WriteLine($line) }
if ($withMarker) { [Console]::Out.WriteLine('kestrel-end') }
exit 0
