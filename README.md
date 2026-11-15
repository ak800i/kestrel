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

- the JDK that comes with Android Studio, in `C:\Program Files\Android\Android Studio\jbr` (if it is not there, set `JAVA_HOME` to a JDK 21);
- the Android SDK with the `platforms;android-37.0` package, in the folder that `ANDROID_HOME` names (`D:\AndroidVMs\SDK` if the variable is not set).

```powershell
scripts\gradle.ps1 assembleDebug                    # build the app
scripts\gradle.ps1 check                            # unit tests, lint and the privacy audit
scripts\gradle.ps1 :build-logic:convention:check    # tests of the audit itself
```

`check` runs the privacy audit (`kestrelAudit`) for every build variant, so the debug build that the scripts install is audited too.

`scripts\test-scripts.ps1` tests the PowerShell scripts with Pester in Windows PowerShell 5.1 and in PowerShell 7, and checks them with PSScriptAnalyzer; the comment at its top lists the modules that it needs.

Gradle and the Kotlin compiler keep running in the background between builds. During a build they use about 3 GB and 2 GB of memory. They stop by themselves 15 minutes after the last build, and `scripts\gradle.ps1 --stop` stops them at once. Gradle runs them on Java 21, as `gradle/gradle-daemon-jvm.properties` asks, so a Gradle that you start from another JDK, such as the one that `JAVA_HOME` names, uses the same processes as `scripts\gradle.ps1`, as long as Android Studio's JDK is the only Java 21 on the computer.

To open the project in Android Studio, use Android Studio Rabbit 2 (2026.2.2) or newer, which supports version 9.5 of the Android Gradle plugin.

## Testing on a device

Kestrel is tested only on the `kestrel-api37` emulator and on a Galaxy A71. The scripts refuse other devices. Run the device tests with `connected-test.ps1` and install with `install.ps1`: `gradle.ps1` refuses tasks that install or test on a device unless the environment variable `ANDROID_SERIAL` names test devices, and for other tasks it hides every device from Gradle.

```powershell
scripts\create-avd.ps1                    # once: installs Android 17 and creates kestrel-api37
$serial = scripts\start-emulator.ps1      # starts the emulator and prints its adb serial
scripts\connected-test.ps1 -Serial $serial :core:privacy:connectedDebugAndroidTest :app:connectedDebugAndroidTest
scripts\install.ps1 -Serial $serial       # runs the privacy audit, then installs the debug build with adb install -r
```

Kestrel is installed with `adb install`, which avoids Android's block on accessibility access for apps from unknown sources. On a Samsung phone, Auto Blocker must be off while USB debugging is in use.

The device tests of `:app` use the fixture screens of `:testapp` (`dev.kestrel.testapp`), a separate app that Gradle installs before the tests. If it was not installed before, `connected-test.ps1` uninstalls it when the tests end. Each test that uses the screen turns Kestrel's accessibility service off and on again. The tests replace the whole list of enabled accessibility services, so other services are off while they run, and Android clears the shortcuts that start those services. `connected-test.ps1` puts the accessibility settings back when the tests end. If you stop the tests with Ctrl+C, Windows asks "Terminate batch job (Y/N)?": answer it, and the script puts the settings back before it ends. If Ctrl+C interrupts the script while it puts things back, it prints the command that puts them back by hand. After Ctrl+C, the script ends with exit code 1. After the tests, Gradle uninstalls Kestrel, which also switches its service off. Some tests touch the screen through a virtual touchscreen that Android's `uinput` tool creates: touches from `adb shell input` skip Android's accessibility input filter, while a virtual touchscreen behaves like a finger.

The scripts use the adb of the Android SDK. To use the same adb for the commands below, run this in your PowerShell window; it changes only that window's environment:

```powershell
. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb
```

`start-emulator.ps1` starts the emulator with software rendering (`-gpu swiftshader`), because with the computer's graphics card the emulator slowed down after a few hours of tests. Android inside the emulator can still slow down after many hours of tests. If the device tests become slow, or Android restarts while they run, restart the emulator with a cold boot: run `& $adb -s $serial emu kill`, wait until the emulator has closed, and then run `$serial = scripts\start-emulator.ps1 -ColdBoot`. A normal start would bring the slow state back from the emulator's Quick Boot snapshot, and restarting Android inside the emulator is not enough.

### Test hooks

Debug builds contain test hooks that only adb can use (design spec, section 16.1). `scripts\hook.ps1` sends a command and prints the answer, and the comment at the top of the script lists every command. It needs PowerShell 7.3 or newer (`pwsh`), because Windows PowerShell 5.1 drops double quotes from the text that it passes to adb. The screen commands need Kestrel's accessibility service, which you can turn on in Settings > Accessibility, or with adb (this replaces the list of enabled services):

```powershell
& $adb -s $serial shell settings put secure enabled_accessibility_services dev.kestrel/dev.kestrel.app.KestrelAccessibilityService
scripts\hook.ps1 -Serial $serial state                                 # is the screen layer running, and is test mode on?
scripts\hook.ps1 -Serial $serial snapshot -Out $env:TEMP\screen.json   # what Kestrel reads on the screen, as JSON
scripts\hook.ps1 -Serial $serial overlay -Show numbers                 # numbers on the screen's elements
scripts\hook.ps1 -Serial $serial tap -Number 3                         # taps the element with the number 3
scripts\hook.ps1 -Serial $serial test-mode -On true                    # test mode stays on during all testing (design spec, D25)
```

Release builds do not contain the hooks, and the privacy audit fails a release build that does.

### Spikes

These scripts check assumptions of the design on a test device (design spec, section 19). They need Kestrel's debug build, and the results are in section 19.1 of the design spec.

- `scripts\spike-s1.ps1 -Serial $serial` checks whether Kestrel's listening service can start and record when the accessibility service, a notification, the Quick Settings tile or a reboot starts it, also while a call rings on the lock screen, and whether Kestrel can read the location while another app is in front. It uses a stand-in listening service and a tile that only debug builds contain, and it prints whether each step worked, never the audio or the place. While it runs, it grants Kestrel the microphone, notification and location permissions, changes the accessibility services, adds a Quick Settings tile, turns the lock screen on if there is none, turns location on, lets the shell set mock locations and replaces the fused location provider with a test provider. The `boot` scenario asks Kestrel to record at its next start. On the emulator, the `ringing` scenario places calls, which end up in the call log. Afterwards, also when the script fails or is interrupted, it puts all of this back as it was before: it revokes only the permissions that Kestrel did not have, ends the calls and deletes them from the call log. If it cannot put something back, also because Ctrl+C interrupted it while it put things back, it says at the end what is left and the command that puts it back, and it ends with exit code 1. After Ctrl+C, it also ends with exit code 1. The `boot` scenario reboots the device. On a phone, the `ringing` scenario asks you to call the phone and to end the call. If the phone has a PIN, pattern or password, the `ringing` scenario asks you to unlock it at the end, and the `boot` scenario asks you to unlock it after the reboot.
- `scripts\spike-s4.ps1 -Serial $serial -Browser chrome, firefox` serves `scripts\spike-s4\page.html` from this computer with the JDK's `jwebserver`, opens it in each browser through `adb reverse`, and prints which of the page's elements Kestrel reads and whether Kestrel can type into its text fields. A browser that is not installed is skipped. The script taps through the browsers' first-run screens that it knows, and otherwise asks you to finish them. While it runs, it replaces the enabled accessibility services with Kestrel's, adds an `adb reverse` rule, runs `jwebserver` on this computer and stops each browser after its checks, also one that was running before. Afterwards, also when it fails or is interrupted, it puts back the accessibility settings, removes the rule and stops `jwebserver`. It leaves on purpose Firefox, which the plan installs on the emulator for this spike, and in the browsers the tabs and the history of the page and the choices of their first-run screens. If it cannot put something back, also because Ctrl+C interrupted it while it put things back, it says at the end what is left and how to put it back, and it ends with exit code 1. After Ctrl+C, or when Kestrel cannot scroll in a browser, it also ends with exit code 1.
- `scripts\spike-s7.ps1 -Serial $serial` pushes a file of random bytes into Kestrel's external files folder with `adb push`, where the model files will live, and checks that Kestrel reads the same bytes. While it runs, it creates folders in Kestrel's external files folder, also `Android/data/dev.kestrel` and its `files` folder if they are not there, and it writes the file in the temporary folder of this computer. It also reinstalls Kestrel with `adb install -r`, which stays, as after `scripts\install.ps1`. Afterwards, also when it fails or is interrupted, it deletes the folders that it created and the file. If it cannot delete something, also because Ctrl+C interrupted it while it deleted things, it says at the end what is left and the command that deletes it, and it ends with exit code 1. After Ctrl+C, it also ends with exit code 1.

## Setting up Kestrel

Open Kestrel from the launcher. The first entry of its settings app is the setup checklist, which says how many of its steps are done (design spec, section 15). The checklist reads the state of steps 3, 4, 5 and 8 from Android whenever Kestrel comes to the front, so it shows the changes that you make in Android's settings.

1. Install Kestrel with `scripts\install.ps1`. On a Samsung phone, Auto Blocker must be off while USB debugging is on.
2. `scripts\push-models.ps1` will copy the voice models. This step comes with Kestrel's voice features.
3. Turn on Kestrel's accessibility service. The step's button opens Android's accessibility settings.
4. Allow the microphone and notifications. The step's button asks Android for both permissions. After you refuse twice, Android no longer asks, and the button opens Kestrel's app settings instead.
5. Allow unrestricted battery use. The step's button opens Kestrel's app settings, where the battery setting is. On a Samsung phone, also add Kestrel to "Never sleeping apps" in the battery settings.
6. Optionally, make Kestrel the default digital assistant, for the side key and earbuds. This step comes with Kestrel's voice features.
7. Sign in to an AI provider and choose the models. This step comes with Kestrel's provider settings.
8. Optionally, turn off Voice Access, so that it and Kestrel do not react to the same words. While Voice Access is on, the step's button opens Android's accessibility settings, and the checklist warns while both are on.

The settings app also has these pages:

- **Safety:** test mode, which is on until you turn it off. While it is on, Kestrel's agent and routines will send and share only to Telegram's Saved Messages, WhatsApp's Message yourself, Viber's My Notes and your own email address, and they will make no calls (design spec, section 12.7). Your own instant commands are not limited. Keep test mode on while you test Kestrel. The agent comes later, so in this version test mode changes nothing.
- **Activity log** and **Network log:** what Kestrel did, and every request that it sent, encrypted and kept for 7 days. *Export both logs* writes both logs to a JSON file that you choose, which is the only way the logs leave the phone. *Delete history* wipes both logs after you confirm.
- **About:** Kestrel's version, the Android version, and the licence texts.

## Licence

Kestrel is free software under the GNU General Public License, version 3 or (at your option) any later version. See [LICENSE](LICENSE). The licences of the libraries that Kestrel uses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), and the [licences](licences) folder holds the texts of those licences. Kestrel's About page shows all of these texts.
