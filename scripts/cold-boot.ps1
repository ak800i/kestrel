# Cold-boots the emulator kestrel-api37 on -Serial, without a window, and checks that the owner's Google accounts and
# Kestrel's profiles there are as before the boot (Invoke-KestrelColdBoot in scripts\lib\DeviceGuard.ps1). Run it before
# each long device suite: kestrel-api37 degrades under long test activity, the gates refuse to start on it then
# (Assert-EmulatorHealthy), and only a cold boot brings it back. It refuses every other device, and ends with an error
# that names the reason when a step fails or the owner's data changed.
# Example: scripts\cold-boot.ps1 -Serial emulator-5554
param([Parameter(Mandatory = $true)][string]$Serial)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
. "$PSScriptRoot\lib\Session.ps1"
. "$PSScriptRoot\lib\DeviceGuard.ps1"
Use-KestrelToolchain
Invoke-KestrelColdBoot -Serial $Serial
