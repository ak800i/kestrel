# Shared helpers for Kestrel's scripts. Dot-source this file; it changes only the current process.

function Use-KestrelToolchain {
    # Gradle runs on the JDK that comes with Android Studio (design spec, section 6).
    $studioJdk = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path $studioJdk) {
        $env:JAVA_HOME = $studioJdk
    } elseif (-not $env:JAVA_HOME) {
        throw 'Set JAVA_HOME to a JDK 17 or newer.'
    }
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = 'D:\AndroidVMs\SDK' }
    if (-not (Test-Path $env:ANDROID_HOME)) { throw "Android SDK not found at $env:ANDROID_HOME" }
    # Newer emulator versions may ignore the legacy ANDROID_SDK_HOME variable, so point them at the AVDs directly.
    if (-not $env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME = Get-AvdHome }
}

function Get-AvdHome {
    if ($env:ANDROID_AVD_HOME) { return $env:ANDROID_AVD_HOME }
    if ($env:ANDROID_EMULATOR_HOME) { return (Join-Path $env:ANDROID_EMULATOR_HOME 'avd') }
    if ($env:ANDROID_USER_HOME) { return (Join-Path $env:ANDROID_USER_HOME 'avd') }
    if ($env:ANDROID_SDK_HOME) { return (Join-Path $env:ANDROID_SDK_HOME '.android\avd') }
    return (Join-Path $env:USERPROFILE '.android\avd')
}

function Get-Adb {
    return (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe')
}

function Find-EmulatorSerial([string]$AvdName) {
    $adb = Get-Adb
    foreach ($line in (& $adb devices | Select-Object -Skip 1)) {
        if ($line -match '^(emulator-\d+)\s+device') {
            $serial = $Matches[1]
            $name = & $adb -s $serial emu avd name 2>$null | Select-Object -First 1
            if ($name -and $name.Trim() -eq $AvdName) { return $serial }
        }
    }
    return $null
}

# Kestrel is tested only on kestrel-api37 and the Galaxy A71. The S23+ is the owner's daily phone (design spec, D20).
function Assert-TestDevice([string]$Serial, [switch]$AllowDailyPhone) {
    $adb = Get-Adb
    $state = & $adb -s $Serial get-state 2>$null
    if ($state -ne 'device') { throw "Device $Serial is not connected." }
    $model = (& $adb -s $Serial shell getprop ro.product.model).Trim()
    if ($model -like 'SM-S916*' -and -not $AllowDailyPhone) {
        throw "Refusing to use the Galaxy S23+ ($Serial). Kestrel is tested only on kestrel-api37 and the Galaxy A71."
    }
    if ($Serial -like 'emulator-*') {
        $avd = & $adb -s $Serial emu avd name 2>$null | Select-Object -First 1
        if (-not $avd -or $avd.Trim() -ne 'kestrel-api37') {
            throw "Refusing to use emulator '$avd' ($Serial). Only kestrel-api37 is used for testing."
        }
    }
}
