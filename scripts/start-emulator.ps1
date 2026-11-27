# Starts kestrel-api37 if it is not running, waits until Android has booted and prints its adb serial.
# The emulator keeps running after this script ends.
# -ColdBoot starts Android afresh instead of from the emulator's Quick Boot snapshot. Use it after closing a slow
# emulator, because the snapshot would bring the slow state back. scripts\cold-boot.ps1 does both for a running
# kestrel-api37, with the owner's data checked around the boot.
# The emulator runs without a window (-no-window), because only its window links Android's clipboard to Windows'
# clipboard. The window does so while the emulator's setting "Enable clipboard sharing" is on, and that setting is one
# for every AVD of this Windows user (zoran too); the emulator has no command-line option, feature or AVD setting for it.
# Android's side of the link sends each clip to Windows on a thread of its own, and the window sends Windows' clip back,
# so an older clip can replace a newer one a moment later (ClipboardHookTest failed so in plan 7E's Task 8), and every
# clip of the tests would also replace the owner's Windows clipboard. -Window starts the emulator with its window, for a
# plan step in which the owner works in it; close it afterwards and start it again without -Window. When kestrel-api37
# already runs, the script warns if it runs otherwise than asked: its console tells the window type ('avd windowtype').
param([switch]$ColdBoot, [switch]$Window)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain

$name = 'kestrel-api37'
$adb = Get-Adb
$deadline = (Get-Date).AddMinutes(5)
$serial = Find-EmulatorSerial $name
if ($ColdBoot -and $serial) { Write-Warning "$name is already running, so -ColdBoot does nothing. Close it first: & '$adb' -s $serial emu kill" }
$started = $null
if (-not $serial) {
    # While the emulator boots, adb lists it as offline. Look for its process before starting a second copy.
    $running = Get-EmulatorProcess $name
    if ($ColdBoot -and $running) { Write-Warning "$name is still starting or closing, so -ColdBoot does nothing." }
    if (-not $running) {
        $emulator = Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'
        # Software rendering: with the computer's graphics card, the emulator slowed down after a few hours of tests,
        # and once the graphics driver crashed (plan 1B).
        # After a crash, the emulator would ask for consent to send a crash report and wait for the answer before it
        # starts Android. Kestrel's tests send nothing to Google, so it neither asks nor sends, and it sends no usage
        # metrics either.
        $arguments = @('-avd', $name, '-no-boot-anim', '-gpu', 'swiftshader', '-crash-report-mode', 'never', '-no-metrics')
        if ($ColdBoot) { $arguments += '-no-snapshot-load' }
        if (-not $Window) { $arguments += '-no-window' }
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
# An emulator that this script did not start may run otherwise than asked, also one that was still booting.
if (-not $started) {
    $warning = Get-EmulatorWindowWarning -AvdName $name -WindowType (Get-EmulatorWindowType $serial) -Window $Window.IsPresent `
        -ClipboardSharing (Test-EmulatorClipboardSharing) -CloseCommand "& '$adb' -s $serial emu kill"
    if ($warning) { Write-Warning $warning }
}
# Windows PowerShell 5.1 would stop at adb's first error line, such as "device offline" while Android starts.
$ErrorActionPreference = 'Continue'
do {
    Start-Sleep -Seconds 3
    $booted = & $adb -s $serial shell getprop sys.boot_completed 2>$null
} until ("$booted".Trim() -eq '1' -or (Get-Date) -gt $deadline)
if ("$booted".Trim() -ne '1') { throw "$name ($serial) did not finish booting within 5 minutes" }
Write-Output $serial
