# Kestrel

Kestrel is a private, voice-first assistant for Android. It is meant to replace Google's Voice Access on the owner's phone. It keeps the direct commands that Voice Access has, such as "scroll down", "tap Send" or "show numbers", and it adds whole tasks in one sentence, such as "Kestrel, send Ana a Viber message that I'm 10 minutes late". An agent on the phone then performs the task through Android's accessibility service.

> **Status:** early development. Sub-project 1 (Foundation) is in progress. Kestrel cannot listen or run tasks yet.

## Privacy rules

- Nothing leaves the phone except the traffic to the LLM provider that the owner configures. Speech recognition, voice output, OCR and all storage run on the phone, and there is no telemetry of any kind.
- All network traffic goes through one HTTP client with a host allowlist. Every request is written to an encrypted network log that the owner can view and delete.
- A build-time audit fails the build when a dependency is a known telemetry or advertising library, when a licence is not compatible with GPL-3.0, when code outside the network gateway uses network APIs, or when a native library imports socket functions.

The full design is in [docs/superpowers/specs/2026-11-12-kestrel-design.md](docs/superpowers/specs/2026-11-12-kestrel-design.md).

## Building

Kestrel builds on Windows with PowerShell. You need:

- the JDK that comes with Android Studio, in `C:\Program Files\Android\Android Studio\jbr` (if it is not there, set `JAVA_HOME` to JDK 17 or newer);
- the Android SDK with the `platforms;android-37.0` package, in the folder that `ANDROID_HOME` names (`D:\AndroidVMs\SDK` if the variable is not set).

```powershell
scripts\gradle.ps1 assembleDebug                    # build the app
scripts\gradle.ps1 check                            # unit tests, lint and the privacy audit
scripts\gradle.ps1 :build-logic:convention:check    # tests of the audit itself
```

`check` runs the privacy audit (`kestrelAudit`) for every build variant, so the debug build that the scripts install is audited too.

`scripts\test-scripts.ps1` tests the PowerShell scripts with Pester in Windows PowerShell 5.1 and in PowerShell 7, and checks them with PSScriptAnalyzer; the comment at its top lists the modules that it needs.

Gradle and the Kotlin compiler keep running in the background between builds. During a build they use about 3 GB and 2 GB of memory. They stop by themselves 15 minutes after the last build, and `scripts\gradle.ps1 --stop` stops them at once. Run Gradle only through `scripts\gradle.ps1`: a Gradle that runs on another JDK, such as the one that `JAVA_HOME` names, starts a second pair of these processes.

To open the project in Android Studio, use Android Studio Rabbit 2 (2026.2.2) or newer, which supports version 9.5 of the Android Gradle plugin.

## Testing on a device

Kestrel is tested only on the `kestrel-api37` emulator and on a Galaxy A71. The scripts refuse other devices.

```powershell
scripts\create-avd.ps1                    # once: installs Android 17 and creates kestrel-api37
$serial = scripts\start-emulator.ps1      # starts the emulator and prints its adb serial
scripts\connected-test.ps1 -Serial $serial :core:privacy:connectedDebugAndroidTest :app:connectedDebugAndroidTest
scripts\install.ps1 -Serial $serial       # runs the privacy audit, then installs the debug build with adb install -r
```

Kestrel is installed with `adb install`, which avoids Android's block on accessibility access for apps from unknown sources. On a Samsung phone, Auto Blocker must be off while USB debugging is in use.

The device tests of `:app` use the fixture screens of `:testapp`, a separate app that Gradle installs before the tests. Each test that uses the screen turns Kestrel's accessibility service off and on again. The tests replace the whole list of enabled accessibility services, so other services are off while they run, and Android clears the shortcuts that start those services. `connected-test.ps1` puts the accessibility settings back when the tests end. If you stop the tests with Ctrl+C, Windows asks "Terminate batch job (Y/N)?": answer it, and the script puts the settings back before it ends. After the tests, Gradle uninstalls Kestrel, which also switches its service off. Some tests touch the screen through a virtual touchscreen that Android's `uinput` tool creates: touches from `adb shell input` skip Android's accessibility input filter, while a virtual touchscreen behaves like a finger.

The scripts use the adb of the Android SDK. To use the same adb for the commands below, run this in your PowerShell window; it changes only that window's environment:

```powershell
. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb
```

`start-emulator.ps1` starts the emulator with software rendering (`-gpu swiftshader`), because with the computer's graphics card the emulator slowed down after a few hours of tests. Android inside the emulator can still slow down after many hours of tests. If the device tests become slow, or Android restarts while they run, restart the emulator with a cold boot: run `& $adb -s $serial emu kill`, wait until the emulator has closed, and then run `$serial = scripts\start-emulator.ps1 -ColdBoot`. A normal start would bring the slow state back from the emulator's Quick Boot snapshot, and restarting Android inside the emulator is not enough.

### Test hooks

Debug builds contain test hooks that only adb can use (design spec, section 16.1). `scripts\hook.ps1` sends a command and prints the answer, and the comment at the top of the script lists every command. The screen commands need Kestrel's accessibility service, which you can turn on in Settings > Accessibility, or with adb (this replaces the list of enabled services):

```powershell
& $adb -s $serial shell settings put secure enabled_accessibility_services dev.kestrel/dev.kestrel.app.KestrelAccessibilityService
scripts\hook.ps1 -Serial $serial state                                 # is the screen layer running, and is test mode on?
scripts\hook.ps1 -Serial $serial snapshot -Out $env:TEMP\screen.json   # what Kestrel reads on the screen, as JSON
scripts\hook.ps1 -Serial $serial overlay -Show numbers                 # numbers on the screen's elements
scripts\hook.ps1 -Serial $serial tap -Number 3                         # taps the element with the number 3
scripts\hook.ps1 -Serial $serial test-mode -On true                    # test mode stays on during all testing (design spec, D25)
```

Release builds do not contain the hooks, and the privacy audit fails a release build that does.

## Licence

Kestrel is free software under the GNU General Public License, version 3 or (at your option) any later version. See [LICENSE](LICENSE). The licences of the libraries that Kestrel uses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
