# Closes the emulator kestrel-api37 on -Serial, after Android has written what it holds in its memory to the emulator's
# disk (Stop-KestrelEmulator in scripts\lib\Kestrel.ps1: 'adb shell sync', then 'adb emu kill', and the wait until its
# qemu process has ended). Close kestrel-api37 with this script, not with 'adb emu kill' alone, which can lose the last
# install or the owner's data at the next cold boot. It refuses every other emulator, and leaves kestrel-api37 open when
# the sync fails or does not return. Its error then gives the one exception, as README and AGENTS.md do: a kestrel-api37
# that does not answer 'adb shell' is closed only by the operator, with 'emu kill' alone, and an agent stops and reports
# it; after that close and a cold boot the operator checks 'pm path dev.kestrel', the Google accounts and Kestrel's
# profile, and decides whether to install Kestrel again (Get-UnansweredEmulatorAdvice). It ends with an error that
# names the reason when a step fails.
# Example: scripts\stop-emulator.ps1 -Serial emulator-5554
param([Parameter(Mandatory = $true)][string]$Serial)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
$problem = Stop-KestrelEmulator -Serial $Serial
if ($problem) { throw "kestrel-api37 was not closed cleanly: $problem." }
Write-Output "kestrel-api37 ($Serial) was closed after Android wrote its disk."
