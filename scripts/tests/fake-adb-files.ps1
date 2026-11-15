# A stand-in for adb in the tests of scripts\lib\Models.ps1; fake-adb-files.cmd runs it in its own process, as adb runs.
# It keeps the files of a fake device in a folder on this computer and understands the commands that Push-ModelFiles
# sends: 'adb -s <serial> push <file> <device path>', and 'adb -s <serial> shell "<command>"' for these commands, each
# followed by ' && echo kestrel-end':
#   pm path dev.kestrel || true
#   am start -n dev.kestrel/dev.kestrel.app.MainActivity    (creates Kestrel's models folder, as Kestrel does when it starts)
#   test -d '<folder>' && echo yes || echo no
#   mkdir -p '<folder>' && chmod 2775 '<folder>'            (creates the folder and its parents, also when they exist)
#   stat -c %s '<file>' 2>/dev/null || echo missing
#   sha256sum '<file>'
# The JSON file whose path is in the environment variable KESTREL_FAKE_ADB holds the device's state:
#   root       the folder on this computer that stands for the device's '/'
#   installed  whether Kestrel is installed
#   modes      the mode that chmod gave each folder, in the order in which it first gave one
#   corrupt    the device paths whose push writes a copy with other content, as a broken transfer would
#   calls      every command that it received, in order, with 'push <device path>' for a push
# Error lines go to standard error, as adb's do.
$statePath = $env:KESTREL_FAKE_ADB
if (-not $statePath -or -not (Test-Path -LiteralPath $statePath)) {
    [Console]::Error.WriteLine('fake adb: KESTREL_FAKE_ADB must name an existing state file')
    exit 1
}
$state = [IO.File]::ReadAllText($statePath) | ConvertFrom-Json
$root = [string]$state.root
$modes = [ordered]@{}
foreach ($property in @($state.modes.PSObject.Properties)) { $modes[$property.Name] = [string]$property.Value }
$calls = @(@($state.calls) | Where-Object { $null -ne $_ } | ForEach-Object { [string]$_ })
$corrupt = @(@($state.corrupt) | Where-Object { $null -ne $_ } | ForEach-Object { [string]$_ })

function Save-State {
    $json = ConvertTo-Json -Depth 3 -InputObject ([ordered]@{ root = $root; installed = [bool]$state.installed; modes = $modes; corrupt = [string[]]$corrupt; calls = [string[]]$calls })
    [IO.File]::WriteAllText($statePath, $json)
}

# The folder or file on this computer that stands for a path on the device.
function Get-LocalPath([string]$DevicePath) {
    return Join-Path $root ($DevicePath.TrimStart('/') -replace '/', '\')
}

function Stop-WithError([string]$Message) {
    Save-State
    [Console]::Error.WriteLine($Message)
    exit 1
}

if ($args.Count -lt 4 -or $args[0] -ne '-s') { Stop-WithError "fake adb: unsupported arguments: $($args -join ' ')" }

if ($args[2] -eq 'push') {
    $calls += "push $($args[4])"
    $target = Get-LocalPath $args[4]
    if (-not (Test-Path -LiteralPath (Split-Path -Parent $target) -PathType Container)) {
        Stop-WithError "adb: error: failed to copy '$($args[3])' to '$($args[4])': remote couldn't create file: No such file or directory"
    }
    Copy-Item -LiteralPath $args[3] -Destination $target -Force
    if ($corrupt -contains $args[4]) { [IO.File]::AppendAllText($target, 'x') }
    Save-State
    "$($args[3]): 1 file pushed, 0 skipped."
    exit 0
}

if ($args[2] -ne 'shell') { Stop-WithError "fake adb: unsupported arguments: $($args -join ' ')" }
$command = $args[3..($args.Count - 1)] -join ' '
$calls += $command
$marker = ' && echo kestrel-end'
$withMarker = $command.EndsWith($marker)
$deviceCommand = if ($withMarker) { $command.Substring(0, $command.Length - $marker.Length) } else { $command }
$models = '/sdcard/Android/data/dev.kestrel/files/models'
if ($deviceCommand -eq 'pm path dev.kestrel || true') {
    $lines = @(if ($state.installed) { 'package:/data/app/dev.kestrel-1/base.apk' })
} elseif ($deviceCommand -eq 'am start -n dev.kestrel/dev.kestrel.app.MainActivity') {
    if ($state.installed) { New-Item -ItemType Directory -Force -Path (Get-LocalPath $models) | Out-Null }
    $lines = @('Starting: Intent { cmp=dev.kestrel/.app.MainActivity }')
} elseif ($deviceCommand -match "^test -d '([^']+)' && echo yes \|\| echo no$") {
    $lines = @(if (Test-Path -LiteralPath (Get-LocalPath $Matches[1]) -PathType Container) { 'yes' } else { 'no' })
} elseif ($deviceCommand -match "^mkdir -p '([^']+)' && chmod 2775 '([^']+)'$" -and $Matches[1] -eq $Matches[2]) {
    $folder = $Matches[1]
    $local = Get-LocalPath $folder
    if (Test-Path -LiteralPath $local -PathType Leaf) { Stop-WithError "mkdir: '$folder': File exists" }
    New-Item -ItemType Directory -Force -Path $local | Out-Null
    $modes[$folder] = '2775'
    $lines = @()
} elseif ($deviceCommand -match "^stat -c %s '([^']+)' 2>/dev/null \|\| echo missing$") {
    $local = Get-LocalPath $Matches[1]
    $lines = @(if (Test-Path -LiteralPath $local -PathType Leaf) { [string](Get-Item -LiteralPath $local).Length } else { 'missing' })
} elseif ($deviceCommand -match "^sha256sum '([^']+)'$") {
    $local = Get-LocalPath $Matches[1]
    if (-not (Test-Path -LiteralPath $local -PathType Leaf)) { Stop-WithError "sha256sum: $($Matches[1]): No such file or directory" }
    # Not Get-FileHash: started from PowerShell 7, this Windows PowerShell inherits a PSModulePath in which it cannot find it.
    $sha256 = [Security.Cryptography.SHA256]::Create()
    $stream = [IO.File]::OpenRead($local)
    try { $hash = -join ($sha256.ComputeHash($stream) | ForEach-Object { $_.ToString('x2') }) } finally { $stream.Dispose(); $sha256.Dispose() }
    $lines = @("$hash  $($Matches[1])")
} else {
    Stop-WithError "fake adb: unsupported command: $deviceCommand"
}
Save-State
foreach ($line in $lines) { $line }
if ($withMarker) { 'kestrel-end' }
exit 0
