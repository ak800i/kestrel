# Cold-boots the emulator kestrel-api37 on -Serial, without a window, and checks that the owner's Google accounts and
# Kestrel's profiles there are as before the boot (Invoke-KestrelColdBoot in scripts\lib\DeviceGuard.ps1). Run it before
# each long device suite: kestrel-api37 degrades under long test activity, the gates refuse to start on it then
# (Get-EmulatorHealthRefusal), and only a cold boot brings it back. After the boot it waits until the emulator passes the
# gates' health check, for at most 5 minutes, so that a gate started after it accepts the emulator. It refuses every
# other device, and refuses to boot when Kestrel's profiles cannot be read first, because it could then not check that
# the default profile keeps its ID and its credential; for an emulator that does not answer, restart it by hand as README
# says. It ends with an error that names the reason when a step fails, the owner's data changed or the emulator stayed
# unhealthy.
# Example: scripts\cold-boot.ps1 -Serial emulator-5554
param([Parameter(Mandatory = $true)][string]$Serial)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
. "$PSScriptRoot\lib\Session.ps1"
. "$PSScriptRoot\lib\DeviceGuard.ps1"
Use-KestrelToolchain
Invoke-KestrelColdBoot -Serial $Serial
