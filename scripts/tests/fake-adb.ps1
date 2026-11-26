# A stand-in for adb in the tests of scripts\lib\Kestrel.ps1, scripts\lib\DeviceGuard.ps1 and scripts\connected-test.ps1;
# fake-adb.cmd runs it in its own process, as adb runs.
# It understands 'adb -s <serial> get-state', 'adb -s <serial> shell getprop <property>' for the properties that
# Assert-TestDevice reads, and 'adb -s <serial> shell "<command>"' for the settings commands, the 'cmd statusbar
# set-tiles', the package commands ('pm list packages', 'dumpsys package', 'pm uninstall', 'pm unstop' and
# 'am force-stop') and 'dumpsys account' that the helpers send, also inside the check of Format-TestDeviceCommand. It
# keeps the device's state in the JSON file whose path is in the environment variable KESTREL_FAKE_ADB:
#   avd         the device's ro.boot.qemu.avd_name, empty for a phone
#   model       the device's ro.product.model
#   settings    names and values of the secure settings; a missing name is an unset setting
#   keepsTiles  whether SystemUI keeps the list of Quick Settings tiles itself, as on Android 17 (observed): set-tiles
#               writes the list to sysui_qs_tiles, and SystemUI writes its own list back over a 'settings put' of that
#               setting. Otherwise SystemUI follows the setting, and set-tiles changes nothing.
#   packages    the installed packages, each with its first-install time, which 'dumpsys package' shows for user 0
#   accounts    how many Google accounts 'dumpsys account' lists
#   accessibility  Android's accessibility manager, as AOSP's AccessibilityManagerService keeps it: the components that it
#               has bound ('bound'), is binding ('binding') or holds as crashed ('crashed'), and those that it never
#               finishes binding ('neverBinds'). A write of enabled_accessibility_services that changes the list drops
#               every component that the list no longer holds from those sets, and binds each listed component that is
#               in none of them; one that never binds stays binding. A write that leaves the list as it was changes
#               nothing. 'dumpsys accessibility' and 'dumpsys activity services <component>' show the state.
#   failures    how many of the next settings commands fail, as a busy device fails them: 'Bad arguments' and no marker
#   calls       every settings command that it received and did not refuse, in order, without the check
#   refused     every settings command that the check kept from running, in order
# The serial 'missing' stands for a device that adb cannot find. Error lines go to standard error, as adb's do.
$statePath = $env:KESTREL_FAKE_ADB
if (-not $statePath -or -not (Test-Path -LiteralPath $statePath)) {
    [Console]::Error.WriteLine('fake adb: KESTREL_FAKE_ADB must name an existing state file')
    exit 1
}
$getState = $args.Count -eq 3 -and $args[0] -eq '-s' -and $args[2] -eq 'get-state'
if (-not $getState -and ($args.Count -lt 4 -or $args[0] -ne '-s' -or $args[2] -ne 'shell')) {
    [Console]::Error.WriteLine("fake adb: unsupported arguments: $($args -join ' ')")
    exit 1
}
$serial = $args[1]
if ($serial -eq 'missing') {
    [Console]::Error.WriteLine("adb.exe: device '$serial' not found")
    exit 1
}
if ($getState) {
    [Console]::Out.WriteLine('device')
    exit 0
}
# adb joins the words after 'shell' into one command line for the device's shell.
$command = $args[3..($args.Count - 1)] -join ' '

$state = [IO.File]::ReadAllText($statePath) | ConvertFrom-Json
$avd = [string]$state.avd
$model = [string]$state.model
$properties = @{ 'ro.boot.qemu.avd_name' = $avd; 'ro.product.model' = $model }
if ($command -match '^getprop (\S+)$') {
    [Console]::Out.WriteLine([string]$properties[$Matches[1]])
    exit 0
}
$settings = [ordered]@{}
foreach ($property in $state.settings.PSObject.Properties) { $settings[$property.Name] = [string]$property.Value }
$packages = [ordered]@{}
if ($state.packages) { foreach ($property in $state.packages.PSObject.Properties) { $packages[$property.Name] = [string]$property.Value } }
$accounts = [int]$state.accounts
$accessibility = [ordered]@{}
foreach ($set in 'bound', 'binding', 'crashed', 'neverBinds') {
    $accessibility[$set] = [string[]]@(@($state.accessibility.$set) | Where-Object { $_ })
}
$keepsTiles = [bool]$state.keepsTiles
$failures = [int]$state.failures
$calls = @(@($state.calls) | Where-Object { $null -ne $_ } | ForEach-Object { [string]$_ })
$refused = @(@($state.refused) | Where-Object { $null -ne $_ } | ForEach-Object { [string]$_ })

function Save-State {
    $json = ConvertTo-Json -Depth 3 -InputObject ([ordered]@{
            avd = $avd; model = $model; settings = $settings; packages = $packages; accounts = $accounts; accessibility = $accessibility
            keepsTiles = $keepsTiles; failures = $failures; calls = [string[]]$calls; refused = [string[]]$refused
        })
    [IO.File]::WriteAllText($statePath, $json)
}

function Get-EnabledServices { @("$($settings['enabled_accessibility_services'])" -split ':' | Where-Object { $_ }) }

# What the accessibility manager's settings observer does after a write of enabled_accessibility_services.
function Update-Accessibility([string[]]$Before) {
    $enabled = @(Get-EnabledServices)
    if (($Before -join ':') -ceq ($enabled -join ':')) { return }
    foreach ($set in 'bound', 'binding', 'crashed') {
        $accessibility[$set] = [string[]]@($accessibility[$set] | Where-Object { $enabled -ccontains $_ })
    }
    foreach ($component in $enabled) {
        if (@($accessibility.bound) + @($accessibility.binding) + @($accessibility.crashed) -ccontains $component) { continue }
        $set = if ($accessibility.neverBinds -ccontains $component) { 'binding' } else { 'bound' }
        $accessibility[$set] = [string[]](@($accessibility[$set]) + $component)
    }
}

function Format-ServiceSet([string[]]$Components) { '{' + (@($Components | ForEach-Object { "{$_}" }) -join ', ') + '}' }

# The check that Format-TestDeviceCommand puts around a command. The commands in the warnings, which the owner pastes,
# have none.
$accepted = $true
if ($command -match '^case \$\(getprop (\S+)\) in (\S+)\) (.*);; \*\) echo kestrel-refused;; esac$') {
    $command = $Matches[3]
    # As the device's shell matches a 'case' pattern, in which '*' stands for any text.
    $accepted = [string]$properties[$Matches[1]] -like $Matches[2]
}
if ($failures -gt 0) {
    $failures--
    $calls += $command
    Save-State
    [Console]::Error.WriteLine('Bad arguments')
    exit 255
}
if (-not $accepted) {
    $refused += $command
    Save-State
    [Console]::Out.WriteLine('kestrel-refused')
    exit 0
}
$calls += $command
# The helpers end every command with this marker; the commands in their warnings, which the owner pastes, have none.
$marker = ' && echo && echo kestrel-end'
$withMarker = $command.EndsWith($marker)
$deviceCommand = if ($withMarker) { $command.Substring(0, $command.Length - $marker.Length) } else { $command }
$lines = @()
# 'printf %s <text>' prints the text without a final line break, as One UI's 'dumpsys accessibility' ends its answer.
$partial = $null
if ($deviceCommand -match '^printf %s (\S+)$') {
    $partial = $Matches[1]
} elseif ($deviceCommand -match '^settings get secure (\S+)$') {
    $lines = @(if ($settings.Contains($Matches[1])) { $settings[$Matches[1]] } else { 'null' })
} elseif ($deviceCommand -match "^settings put secure (\S+) '") {
    # As in the device's shell, a single quote ends the quoted value, so a quote inside the value must be written '\''.
    if ($deviceCommand -notmatch "^settings put secure (\S+) '((?:[^']|'\\'')*)'$") {
        Save-State
        [Console]::Error.WriteLine('/system/bin/sh: no closing quote')
        exit 1
    }
    $before = @(Get-EnabledServices)
    # A SystemUI that keeps the list of tiles writes its own list back over the setting.
    if (-not ($keepsTiles -and $Matches[1] -eq 'sysui_qs_tiles')) { $settings[$Matches[1]] = $Matches[2].Replace("'\''", "'") }
    if ($Matches[1] -eq 'enabled_accessibility_services') { Update-Accessibility $before }
} elseif ($deviceCommand -match "^cmd statusbar set-tiles '") {
    if ($deviceCommand -notmatch "^cmd statusbar set-tiles '((?:[^']|'\\'')*)'$") {
        Save-State
        [Console]::Error.WriteLine('/system/bin/sh: no closing quote')
        exit 1
    }
    if ($keepsTiles) { $settings['sysui_qs_tiles'] = $Matches[1].Replace("'\''", "'") }
} elseif ($deviceCommand -match '^settings delete secure (\S+)$') {
    $lines = @("Deleted $(if ($settings.Contains($Matches[1])) { 1 } else { 0 }) rows")
    $before = @(Get-EnabledServices)
    $settings.Remove($Matches[1])
    if ($Matches[1] -eq 'enabled_accessibility_services') { Update-Accessibility $before }
} elseif ($deviceCommand -match '^pm list packages (\S+)$') {
    # As on the device, every package whose name contains the text.
    $text = $Matches[1]
    $lines = @($packages.Keys | Where-Object { $_.Contains($text) } | Sort-Object | ForEach-Object { "package:$_" })
} elseif ($deviceCommand -match '^dumpsys package (\S+)$') {
    if ($packages.Contains($Matches[1])) {
        $lines = @('Packages:', "  Package [$($Matches[1])] (1a2b3c):", '    versionCode=1 minSdk=31 targetSdk=37',
            '    User 0: ceDataInode=4242 installed=true hidden=false suspended=false stopped=false', '      installReason=0',
            "      firstInstallTime=$($packages[$Matches[1]])", '      uninstallReason=0')
    }
} elseif ($deviceCommand -match '^pm uninstall (\S+)$') {
    if (-not $packages.Contains($Matches[1])) {
        # The device's shell then skips the marker.
        Save-State
        [Console]::Out.WriteLine('Failure [DELETE_FAILED_INTERNAL_ERROR]')
        exit 1
    }
    $packages.Remove($Matches[1])
    $lines = @('Success')
} elseif ($deviceCommand -match '^(pm unstop|am force-stop) \S+$') {
    # Neither changes what the fake keeps.
} elseif ($deviceCommand -eq 'dumpsys account') {
    $numbers = if ($accounts -gt 0) { 1..$accounts } else { @() }
    $lines = @('Accounts: ' + $accounts) + @($numbers | ForEach-Object { "  Account {name=account$_@example.com, type=com.google}" })
} elseif ($deviceCommand -eq 'dumpsys accessibility') {
    $labels = @($accessibility.bound | ForEach-Object { "Service[label=$(($_ -split '[/.]')[-1]), feedbackType[FEEDBACK_GENERIC]]" }) -join ', '
    $lines = @('ACCESSIBILITY MANAGER (dumpsys accessibility)', 'currentUserId=0', 'User state[',
        '     attributes:{id=0, touchExplorationEnabled=false}', "     Bound services:{$labels}",
        "     Enabled services:$(Format-ServiceSet (Get-EnabledServices))", "     Binding services:$(Format-ServiceSet $accessibility.binding)",
        "     Crashed services:$(Format-ServiceSet $accessibility.crashed)", 'User state[', '     attributes:{id=10, touchExplorationEnabled=false}',
        '     Bound services:{}', '     Enabled services:{}', '     Binding services:{}', '     Crashed services:{}', '}]')
} elseif ($deviceCommand -match '^dumpsys activity services (\S+)$') {
    $lines = @('ACTIVITY MANAGER SERVICES (dumpsys activity services)')
    $lines += if ($accessibility.bound -ccontains $Matches[1]) {
        @("  * ServiceRecord{1a2b u0 $($Matches[1])}", '    app=ProcessRecord{3c4d 4242:dev.kestrel/u0a353}')
    } else { @('  (nothing)') }
} elseif ($deviceCommand -eq 'settings list secure') {
    $lines = @($settings.Keys | ForEach-Object { "$_=$($settings[$_])" })
} else {
    Save-State
    [Console]::Error.WriteLine("fake adb: unsupported shell command: $deviceCommand")
    exit 1
}
Save-State
foreach ($line in $lines) { [Console]::Out.WriteLine($line) }
if ($null -ne $partial) { [Console]::Out.Write($partial) }
# As on the device, the first echo ends a last line that has no line break, or else prints an empty line.
if ($withMarker) {
    [Console]::Out.WriteLine('')
    [Console]::Out.WriteLine('kestrel-end')
}
exit 0
