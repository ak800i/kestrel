# Starts kestrel-api37 if it is not running, waits until Android has booted and prints its adb serial.
# The emulator keeps running in its own window after this script ends.
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain

$name = 'kestrel-api37'
$adb = Get-Adb
$deadline = (Get-Date).AddMinutes(5)
$serial = Find-EmulatorSerial $name
if (-not $serial) {
    # While the emulator boots, adb lists it as offline. Look for its process before starting a second copy.
    $running = Get-CimInstance Win32_Process -Filter "Name LIKE 'qemu-system%'" |
        Where-Object { $_.CommandLine -match "-avd\s+$([regex]::Escape($name))(\s|$)" }
    if (-not $running) {
        $emulator = Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'
        Start-Process -FilePath $emulator -ArgumentList '-avd', $name, '-no-boot-anim' -WindowStyle Minimized
    }
    do {
        Start-Sleep -Seconds 5
        $serial = Find-EmulatorSerial $name
    } until ($serial -or (Get-Date) -gt $deadline)
    if (-not $serial) { throw "$name did not appear in adb within 5 minutes" }
}
do {
    Start-Sleep -Seconds 3
    $booted = & $adb -s $serial shell getprop sys.boot_completed 2>$null
} until ("$booted".Trim() -eq '1' -or (Get-Date) -gt $deadline)
if ("$booted".Trim() -ne '1') { throw "$name ($serial) did not finish booting within 5 minutes" }
Write-Output $serial
