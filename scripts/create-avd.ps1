# Installs the Android 17 system image and creates the kestrel-api37 emulator (design spec, section 6).
# The image needs emulator 37.2.11 or newer, so sdkmanager also updates the shared emulator package;
# the owner approved this on 12 November 2026. Other AVDs, such as zoran, are never changed.
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain

$name = 'kestrel-api37'
$image = 'system-images;android-37.0;google_apis_playstore;x86_64'
$tools = Join-Path $env:ANDROID_HOME 'cmdline-tools\latest\bin'

& "$tools\sdkmanager.bat" --install $image 'platforms;android-37.0' 'emulator'
if ($LASTEXITCODE -ne 0) { throw 'sdkmanager failed' }

$avdHome = Get-AvdHome
$avdDir = Join-Path $avdHome "$name.avd"
if (Test-Path $avdDir) {
    Write-Host "$name already exists in $avdHome"
} else {
    'no' | & "$tools\avdmanager.bat" create avd --name $name --package $image --device pixel_8 --sdcard 512M
    if ($LASTEXITCODE -ne 0) { throw 'avdmanager failed' }
    # More memory and storage than the defaults, for the test apps and the speech models of later sub-projects.
    $config = Join-Path $avdDir 'config.ini'
    $settings = [ordered]@{ 'hw.ramSize' = '4096'; 'disk.dataPartition.size' = '16G'; 'hw.keyboard' = 'yes' }
    $lines = Get-Content $config | Where-Object { $key = ($_ -split '=', 2)[0].Trim(); -not $settings.Contains($key) }
    $lines += $settings.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }
    Set-Content -Path $config -Value $lines -Encoding ascii
}

$avds = & (Join-Path $env:ANDROID_HOME 'emulator\emulator.exe') -list-avds
Write-Host "AVDs: $($avds -join ', ')"
if ($avds -notcontains $name) { throw "$name is missing from the AVD list" }
