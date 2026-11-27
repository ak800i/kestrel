# Closes the emulator kestrel-api37 on -Serial, after Android has written what it holds in its memory to the emulator's
# disk (Stop-KestrelEmulator in scripts\lib\Kestrel.ps1: 'adb shell sync', then 'adb emu kill', and the wait until its
# qemu process has ended). Close kestrel-api37 with this script, not with 'adb emu kill' alone, which can lose the last
# install or the owner's data at the next cold boot. It refuses every other emulator, and leaves kestrel-api37 open when
# the sync fails; for an emulator that does not answer, README says how to close it by hand. It ends with an error that
# names the reason when a step fails.
# Example: scripts\stop-emulator.ps1 -Serial emulator-5554
param([Parameter(Mandatory = $true)][string]$Serial)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
$problem = Stop-KestrelEmulator -Serial $Serial
if ($problem) { throw "kestrel-api37 was not closed cleanly: $problem." }
Write-Output "kestrel-api37 ($Serial) was closed after Android wrote its disk."
