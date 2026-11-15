# Spike S7 (design spec, section 19): does adb push copy files into Kestrel's external files folder, where the model
# files live (section 8.10), and can Kestrel read them? The script pushes a file of random bytes into a folder that adb
# creates, into the same folder after chmod 2775, and into a folder that Kestrel creates. Each time, it compares the
# SHA-256 hash that Kestrel reads with this computer's. It also checks that the file stays after the adb install -r of
# scripts\install.ps1. It needs Kestrel's debug build, built and installed with scripts\install.ps1.
# Afterwards, also when it fails or is interrupted, it deletes the spike's folders on the device, with the folders above
# them that it created, and the file on this computer. If it cannot delete something, also because Ctrl+C interrupted it
# while it deleted things, it says at the end what is left and how to delete it by hand. Then, and after Ctrl+C, it ends
# with exit code 1. With -Verbose, it names each thing that it deletes.
# Example: scripts\spike-s7.ps1 -Serial emulator-5554
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [ValidateRange(1, 1024)][int]$SizeMegabytes = 64
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial
$adb = Get-Adb
$app = '/sdcard/Android/data/dev.kestrel'
$base = "$app/files"
$name = 'spike-s7.bin'
$apk = Join-Path $PSScriptRoot '..\app\build\outputs\apk\debug\app-debug.apk'
$local = Join-Path $env:TEMP "kestrel-$name"

function Invoke-Shell([string]$Command) { (& $adb -s $Serial shell $Command) -join "`n" }

function Send-File([string]$Folder) {
    $time = Measure-Command { & $adb -s $Serial push $local "$base/$Folder/$name" | Out-Null }
    if ($LASTEXITCODE -ne 0) { throw 'adb push failed.' }
    $time
}

# Whether Kestrel reads the file in the folder with the same size and hash as this computer.
function Test-KestrelFile([string]$Folder, [string]$Create) {
    $options = @{}
    if ($Create) { $options.Create = $Create }
    $listing = (& "$PSScriptRoot\hook.ps1" -Serial $Serial files @options) -join "`n"
    $lines = $listing -split "`n"
    if ($lines -contains "$Folder/ unreadable") { return 'no, Kestrel may not read the folder' }
    $line = $lines | Where-Object { $_ -like "$Folder/$name *" } | Select-Object -First 1
    if (-not $line) { return 'no, Kestrel does not see the file' }
    if (($line -split ' ', 2)[1] -eq $expected) { 'yes' } else { "no, Kestrel reads '$line'" }
}

# What the script has still to put back on the device and this computer. Each restore enters this list before the change
# that it undoes is made, and it leaves the list when it has worked. At the end, the script names the restores that are
# left and how to do them by hand. The adb install -r stays, as after scripts\install.ps1.
$pending = [System.Collections.Generic.List[object]]::new()
$stopReason = 'the script was stopped'

function Register-Restore([string]$What, [string]$ByHand, [scriptblock]$Action) {
    $restore = [pscustomobject]@{ What = $What; ByHand = $ByHand; Action = $Action; Reason = $stopReason }
    $pending.Add($restore)
    $restore
}

# Runs the restores in order. A stop, such as Ctrl+C, that comes while a finally block runs ends that block at once, and
# PowerShell then runs the finally blocks around it in full and ignores further stops. So each restore runs in the
# finally block of the one before it, and a stop ends only the restore that is running.
function Invoke-Restore([object[]]$Steps, [int]$Index = 0) {
    if ($Index -ge $Steps.Count) { return }
    $restore = $Steps[$Index]
    try {
        Write-Verbose "Trying to $($restore.What)"
        & $restore.Action | Out-Null
        $pending.Remove($restore) | Out-Null
    } catch {
        $restore.Reason = $_.Exception.Message
    } finally {
        Invoke-Restore $Steps ($Index + 1)
    }
}

# Runs a command on the device and fails with the device's complaint when the command fails.
function Invoke-Checked([string]$Command) { Invoke-SettingsCommand -Serial $Serial -Command $Command }

# Returns a PowerShell command that runs the given command on the device, for the warnings.
function Format-DeviceCommand([string]$Command) {
    "Run: & '$($adb -replace "'", "''")' -s $Serial shell '$($Command -replace "'", "''")'"
}

# The folders above the spike's folders that are not there yet, deepest first, so that the script deletes them too.
$created = @($base, $app | Where-Object { (Invoke-Shell "[ -d $_ ] && echo yes") -ne 'yes' })
$cleanup = (@("rm -rf $base/spike-s7-adb $base/spike-s7-kestrel") + @($created | ForEach-Object { "if [ -d $_ ]; then rmdir $_; fi" })) -join ' && '
$completed = $false
$failed = $false
$restoresAtEnd = @(
    Register-Restore "delete the spike's folders on $Serial" (Format-DeviceCommand $cleanup) {
        Invoke-Checked $cleanup
    }
    Register-Restore "delete $local on this computer" "Run: Remove-Item -LiteralPath '$($local -replace "'", "''")'" {
        Remove-Item -LiteralPath $local -ErrorAction SilentlyContinue
        if (Test-Path -LiteralPath $local) { throw 'it is still there' }
    }
)
try {
    $bytes = [byte[]]::new($SizeMegabytes * 1MB)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    [IO.File]::WriteAllBytes($local, $bytes)
    $expected = "$($bytes.Length) $((Get-FileHash $local -Algorithm SHA256).Hash.ToLowerInvariant())"
    Invoke-Checked "mkdir -p $base/spike-s7-adb" | Out-Null
    $time = Send-File 'spike-s7-adb'
    '{0,-40} {1:N1} MB/s' -f 'push speed', ($SizeMegabytes / $time.TotalSeconds)
    '{0,-40} {1}' -f 'a folder that adb creates', (Test-KestrelFile 'spike-s7-adb')
    Invoke-Checked "chmod 2775 $base/spike-s7-adb" | Out-Null
    '{0,-40} {1}' -f 'the same folder after chmod 2775', (Test-KestrelFile 'spike-s7-adb')
    Test-KestrelFile 'spike-s7-kestrel' -Create 'spike-s7-kestrel' | Out-Null
    Send-File 'spike-s7-kestrel' | Out-Null
    '{0,-40} {1}' -f 'a folder that Kestrel creates', (Test-KestrelFile 'spike-s7-kestrel')
    if (Test-Path $apk) {
        & $adb -s $Serial install -r $apk | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'adb install -r failed.' }
        '{0,-40} {1}' -f 'the same after adb install -r', (Test-KestrelFile 'spike-s7-kestrel')
    } else {
        '{0,-40} {1}' -f 'the same after adb install -r', "not checked: build $apk with scripts\install.ps1 first"
    }
    $completed = $true
} catch {
    $failed = $true
    throw
} finally {
    try {
        Invoke-Restore $restoresAtEnd
    } finally {
        foreach ($restore in $pending) {
            Write-Warning "Could not $($restore.What) ($($restore.Reason)). $($restore.ByHand)"
        }
        # After a stop, such as Ctrl+C, PowerShell would end the script with exit code 0. A failed run ends with its error.
        if (-not ($completed -or $failed) -or $pending.Reason -contains $stopReason) {
            # pwsh -File ends a stopped script that declares parameters with exit code 0, even after 'exit 1', unless the
            # script gives the host its exit code. Run from a prompt, that would close the window, so the script does it
            # only when nothing called it.
            if (@(Get-PSCallStack).Count -eq 1) { $Host.SetShouldExit(1) }
            exit 1
        }
    }
}
if ($pending.Count -gt 0) {
    Write-Warning 'The device or this computer is not as it was before. The warnings above say how to put it back.'
    exit 1
}
