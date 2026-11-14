# Starts kestrel-api37 if it is not running, waits until Android has booted and prints its adb serial.
# The emulator keeps running in its own window after this script ends.
# -ColdBoot starts Android afresh instead of from the emulator's Quick Boot snapshot. Use it after closing a slow
# emulator, because the snapshot would bring the slow state back.
param([switch]$ColdBoot)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain

$name = 'kestrel-api37'
$adb = Get-Adb
$deadline = (Get-Date).AddMinutes(5)
$serial = Find-EmulatorSerial $name
if ($ColdBoot -and $serial) { Write-Warning "$name is already running, so -ColdBoot does nothing. Close it first: & '$adb' -s $serial emu kill" }
if (-not $serial) {
    # While the emulator boots, adb lists it as offline. Look for its process before starting a second copy.
    $running = Get-CimInstance Win32_Process -Filter "Name LIKE 'qemu-system%'" |
        Where-Object { $_.CommandLine -match "-avd\s+$([regex]::Escape($name))(\s|$)" }
    $started = $null
    if ($ColdBoot -and $running) { Write-Warning "$name is still starting or closing, so -ColdBoot does nothing." }
    if (-not $running) {
        $emulator = Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'
        # Software rendering: with the computer's graphics card, the emulator slowed down after a few hours of tests,
        # and once the graphics driver crashed (plan 1B).
        $arguments = @('-avd', $name, '-no-boot-anim', '-gpu', 'swiftshader')
        if ($ColdBoot) { $arguments += '-no-snapshot-load' }
        $started = Start-Process -FilePath $emulator -ArgumentList $arguments -WindowStyle Minimized -PassThru
        $null = $started.Handle  # Keeps the exit code readable after the process ends.
    }
    do {
        Start-Sleep -Seconds 5
        if ($started -and $started.HasExited -and $started.ExitCode -ne 0) {
            throw "The emulator exited with code $($started.ExitCode) before $name appeared in adb"
        }
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
