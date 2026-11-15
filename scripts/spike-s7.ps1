# Spike S7 (design spec, section 19): does adb push copy files into Kestrel's external files folder, where the model
# files live (section 8.10), and can Kestrel read them? The script pushes a file of random bytes into a folder that adb
# creates, into the same folder after chmod 2775, and into a folder that Kestrel creates. Each time, it compares the
# SHA-256 hash that Kestrel reads with this computer's. It also checks that the file stays after the adb install -r of
# scripts\install.ps1, and it deletes the folders at the end. It needs Kestrel's debug build, built and installed with
# scripts\install.ps1.
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

# Each change is put back in the finally block of its own try, in the reverse order, so that a failed restore or a stop
# such as Ctrl+C does not skip the others. A restore that fails warns, and the script then ends with exit code 1. The
# adb install -r stays, as after scripts\install.ps1.
$restoreFailed = $false
$failed = $false
try {
    $bytes = [byte[]]::new($SizeMegabytes * 1MB)
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    [IO.File]::WriteAllBytes($local, $bytes)
    $expected = "$($bytes.Length) $((Get-FileHash $local -Algorithm SHA256).Hash.ToLowerInvariant())"
    # The folders above the spike's folders that are not there yet, deepest first, so that the script deletes them too.
    $created = @($base, $app | Where-Object { (Invoke-Shell "[ -d $_ ] && echo yes") -ne 'yes' })
    try {
        Invoke-Shell "mkdir -p $base/spike-s7-adb" | Out-Null
        $time = Send-File 'spike-s7-adb'
        '{0,-40} {1:N1} MB/s' -f 'push speed', ($SizeMegabytes / $time.TotalSeconds)
        '{0,-40} {1}' -f 'a folder that adb creates', (Test-KestrelFile 'spike-s7-adb')
        Invoke-Shell "chmod 2775 $base/spike-s7-adb" | Out-Null
        '{0,-40} {1}' -f 'the same folder after chmod 2775', (Test-KestrelFile 'spike-s7-adb')
        Test-KestrelFile 'spike-s7-kestrel' -Create 'spike-s7-kestrel' | Out-Null
        Send-File 'spike-s7-kestrel' | Out-Null
        '{0,-40} {1}' -f 'a folder that Kestrel creates', (Test-KestrelFile 'spike-s7-kestrel')
        if (Test-Path $apk) {
            & $adb -s $Serial install -r $apk | Out-Null
            '{0,-40} {1}' -f 'the same after adb install -r', (Test-KestrelFile 'spike-s7-kestrel')
        } else {
            '{0,-40} {1}' -f 'the same after adb install -r', "not checked: build $apk with scripts\install.ps1 first"
        }
    } finally {
        $cleanup = (@("rm -rf $base/spike-s7-adb $base/spike-s7-kestrel") + @($created | ForEach-Object { "rmdir $_" })) -join ' && '
        Invoke-Shell $cleanup | Out-Null
        if ($LASTEXITCODE -ne 0) {
            $restoreFailed = $true
            Write-Warning "Could not delete the spike's folders on $Serial. Run: & '$adb' -s $Serial shell '$cleanup'"
        }
    }
} catch {
    $failed = $true
    throw
} finally {
    Remove-Item $local -ErrorAction SilentlyContinue
    if (Test-Path $local) {
        $restoreFailed = $true
        Write-Warning "Could not delete $local on this computer."
    }
    # A failed run ends with its error. After a stop, such as Ctrl+C, the script would end with exit code 0.
    if ($restoreFailed -and -not $failed) { exit 1 }
}
