# A stand-in for adb in the tests of scripts\lib\Models.ps1; fake-adb-files.cmd runs it in its own process, as adb runs.
# It keeps the files of a fake device in a folder on this computer and understands the commands that Push-ModelFiles
# sends: 'adb -s <serial> push <file> <device path>', and 'adb -s <serial> shell "<command>"' for these commands, each
# followed by ' && echo && echo kestrel-end' and inside the check of Format-TestDeviceCommand, for which it is kestrel-api37:
#   pm path dev.kestrel || true
#   am start -n dev.kestrel/dev.kestrel.app.MainActivity    (creates Kestrel's models folder, as Kestrel does when it starts)
#   test -d '<folder>' && echo yes || echo no
#   mkdir -p '<folder>'                                      (creates the missing folders of the path, owned by the shell)
#   if [ ! -d '<folder>' ]; then echo missing; elif [ -O '<folder>' ]; then echo shell; elif [ -w '<folder>' ] && [ -x '<folder>' ]; then echo writable; else echo closed; fi
#   chmod 2775 '<folder>'                                    (only on a folder that the shell owns, as on the device)
#   stat -c '%U:%G %a' '<folder>'
#   stat -c %s '<file>' 2>/dev/null || echo missing
#   sha256sum '<file>'
# The JSON file whose path is in the environment variable KESTREL_FAKE_ADB holds the device's state:
#   root       the folder on this computer that stands for the device's '/'
#   installed  whether Kestrel is installed
#   folders    each folder's owner, 'shell' or 'kestrel', and mode, in the order in which they came to be. As on
#              kestrel-api37, a folder that the shell creates has the mode 2770, Kestrel's folders have the group
#              ext_data_rw, of which the shell is a member, and the shell may create files only where it owns the folder
#              or the group may write.
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
$folders = [ordered]@{}
foreach ($property in @($state.folders.PSObject.Properties)) {
    $folders[$property.Name] = [ordered]@{ owner = [string]$property.Value.owner; mode = [string]$property.Value.mode }
}
$calls = @(@($state.calls) | Where-Object { $null -ne $_ } | ForEach-Object { [string]$_ })
$corrupt = @(@($state.corrupt) | Where-Object { $null -ne $_ } | ForEach-Object { [string]$_ })

function Save-State {
    $json = ConvertTo-Json -Depth 3 -InputObject ([ordered]@{ root = $root; installed = [bool]$state.installed; folders = $folders; corrupt = [string[]]$corrupt; calls = [string[]]$calls })
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

# The owner and mode of a folder of the fake device.
function Get-Folder([string]$DevicePath) {
    $folder = $folders[$DevicePath]
    if (-not $folder) { Stop-WithError "fake adb: the test gave $DevicePath no owner" }
    return $folder
}

# Whether the shell may create files in a folder: as its owner, or through the group ext_data_rw.
function Test-ShellWrites([string]$DevicePath) {
    $folder = Get-Folder $DevicePath
    return $folder.owner -eq 'shell' -or $folder.mode[2] -in '3', '7'
}

if ($args.Count -lt 4 -or $args[0] -ne '-s') { Stop-WithError "fake adb: unsupported arguments: $($args -join ' ')" }

if ($args[2] -eq 'push') {
    $calls += "push $($args[4])"
    $target = Get-LocalPath $args[4]
    if (-not (Test-Path -LiteralPath (Split-Path -Parent $target) -PathType Container)) {
        Stop-WithError "adb: error: failed to copy '$($args[3])' to '$($args[4])': remote couldn't create file: No such file or directory"
    }
    if (-not (Test-ShellWrites ($args[4] -replace '/[^/]+$', ''))) {
        Stop-WithError "adb: error: failed to copy '$($args[3])' to '$($args[4])': remote couldn't create file: Permission denied"
    }
    Copy-Item -LiteralPath $args[3] -Destination $target -Force
    if ($corrupt -contains $args[4]) { [IO.File]::AppendAllText($target, 'x') }
    Save-State
    "$($args[3]): 1 file pushed, 0 skipped."
    exit 0
}

if ($args[2] -ne 'shell') { Stop-WithError "fake adb: unsupported arguments: $($args -join ' ')" }
$command = $args[3..($args.Count - 1)] -join ' '
# The check that Format-TestDeviceCommand puts around a command. The fake device is kestrel-api37.
if ($command -match '^case \$\(getprop (\S+)\) in (\S+)\) (.*);; \*\) echo kestrel-refused;; esac$') {
    $command = $Matches[3]
    $property = @{ 'ro.boot.qemu.avd_name' = 'kestrel-api37'; 'ro.product.model' = 'sdk_gphone64_x86_64' }[$Matches[1]]
    if ([string]$property -notlike $Matches[2]) {
        Save-State
        'kestrel-refused'
        exit 0
    }
}
$calls += $command
$marker = ' && echo && echo kestrel-end'
$withMarker = $command.EndsWith($marker)
$deviceCommand = if ($withMarker) { $command.Substring(0, $command.Length - $marker.Length) } else { $command }
$models = '/sdcard/Android/data/dev.kestrel/files/models'
if ($deviceCommand -eq 'pm path dev.kestrel || true') {
    $lines = @(if ($state.installed) { 'package:/data/app/dev.kestrel-1/base.apk' })
} elseif ($deviceCommand -eq 'am start -n dev.kestrel/dev.kestrel.app.MainActivity') {
    if ($state.installed -and -not $folders.Contains($models)) {
        New-Item -ItemType Directory -Force -Path (Get-LocalPath $models) | Out-Null
        $folders[$models] = [ordered]@{ owner = 'kestrel'; mode = '2770' }
    }
    $lines = @('Starting: Intent { cmp=dev.kestrel/.app.MainActivity }')
} elseif ($deviceCommand -match "^test -d '([^']+)' && echo yes \|\| echo no$") {
    $lines = @(if (Test-Path -LiteralPath (Get-LocalPath $Matches[1]) -PathType Container) { 'yes' } else { 'no' })
} elseif ($deviceCommand -match "^mkdir -p '([^']+)'$") {
    $segments = $Matches[1].TrimStart('/') -split '/'
    for ($count = 1; $count -le $segments.Count; $count++) {
        $folder = '/' + ($segments[0..($count - 1)] -join '/')
        $local = Get-LocalPath $folder
        if (Test-Path -LiteralPath $local -PathType Leaf) { Stop-WithError "mkdir: '$folder': File exists" }
        if (Test-Path -LiteralPath $local -PathType Container) { continue }
        $parent = $folder -replace '/[^/]+$', ''
        if ($folders.Contains($parent) -and -not (Test-ShellWrites $parent)) { Stop-WithError "mkdir: '$folder': Permission denied" }
        New-Item -ItemType Directory -Path $local | Out-Null
        $folders[$folder] = [ordered]@{ owner = 'shell'; mode = '2770' }
    }
    $lines = @()
} elseif ($deviceCommand -match "^if \[ ! -d '([^']+)' \]; then echo missing; " -and $deviceCommand -eq ("if [ ! -d '{0}' ]; then echo missing; elif [ -O '{0}' ]; then echo shell; elif [ -w '{0}' ] && [ -x '{0}' ]; then echo writable; else echo closed; fi" -f $Matches[1])) {
    $folder = $Matches[1]
    $lines = @(if (-not (Test-Path -LiteralPath (Get-LocalPath $folder) -PathType Container)) {
            'missing'
        } elseif ((Get-Folder $folder).owner -eq 'shell') {
            'shell'
        } elseif (Test-ShellWrites $folder) {
            'writable'
        } else {
            'closed'
        })
} elseif ($deviceCommand -match "^chmod 2775 '([^']+)'$") {
    $folder = Get-Folder $Matches[1]
    if ($folder.owner -ne 'shell') { Stop-WithError "chmod: chmod '$($Matches[1])' to 42775: Operation not permitted" }
    $folder.mode = '2775'
    $lines = @()
} elseif ($deviceCommand -match "^stat -c '%U:%G %a' '([^']+)'$") {
    $folder = Get-Folder $Matches[1]
    $user = @{ shell = 'shell'; kestrel = 'u0_a353' }[$folder.owner]
    $lines = @("${user}:ext_data_rw $($folder.mode)")
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
# As on the device, the first echo prints an empty line after an answer that ends with a line break.
if ($withMarker) {
    ''
    'kestrel-end'
}
exit 0
