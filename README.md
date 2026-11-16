# Kestrel

Kestrel is a private, voice-first assistant for Android. It is meant to replace Google's Voice Access on the owner's phone. It keeps the direct commands that Voice Access has, such as "scroll down", "tap Send" or "show numbers", and it adds whole tasks in one sentence, such as "Kestrel, send Ana a Viber message that I'm 10 minutes late". An agent on the phone then performs the task through Android's accessibility service.

> **Status:** early development. Kestrel listens for its name and its own commands, such as "Kestrel, turn off", but it cannot run tasks yet.

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

The first build downloads sherpa-onnx's Android library (39 MB), which contains the speech engines, from sherpa-onnx's GitHub release. Gradle checks its SHA-256 hash against `gradle/verification-metadata.xml`. The library contains its own copy of ONNX Runtime, so a new version of sherpa-onnx needs a new audit of that copy (`audit/rules.txt`) and a new hash.

`scripts\test-scripts.ps1` tests the PowerShell scripts with Pester in Windows PowerShell 5.1 and in PowerShell 7, and checks them with PSScriptAnalyzer; the comment at its top lists the modules that it needs.

Gradle and the Kotlin compiler keep running in the background between builds. During a build they use about 3 GB and 2 GB of memory. They stop by themselves 15 minutes after the last build, and `scripts\gradle.ps1 --stop` stops them at once. Gradle runs them on Java 21, as `gradle/gradle-daemon-jvm.properties` asks, so a Gradle that you start from another JDK, such as the one that `JAVA_HOME` names, uses the same processes as `scripts\gradle.ps1`, as long as Android Studio's JDK is the only Java 21 on the computer.

To open the project in Android Studio, use Android Studio Rabbit 2 (2026.2.2) or newer, which supports version 9.5 of the Android Gradle plugin.

## Testing on a device

Kestrel is tested only on the `kestrel-api37` emulator and on a Galaxy A71. The scripts refuse other devices. An emulator's serial can pass to another emulator that starts after it ended, so the commands with which the scripts read and change settings check, in the same `adb shell` call, that the device is still the one that the script accepted. Run the device tests with `connected-test.ps1` and install with `install.ps1`: `gradle.ps1` refuses tasks that install or test on a device unless the environment variable `ANDROID_SERIAL` names test devices, and for other tasks it hides every device from Gradle.

```powershell
scripts\create-avd.ps1                    # once: installs Android 17 and creates kestrel-api37
$serial = scripts\start-emulator.ps1      # starts the emulator and prints its adb serial
scripts\connected-test.ps1 -Serial $serial :core:privacy:connectedDebugAndroidTest :core:voice:connectedDebugAndroidTest :app:connectedDebugAndroidTest
scripts\install.ps1 -Serial $serial       # runs the privacy audit, then installs the debug build with adb install -r
```

The device tests of the speech engines need the speech models on the device. `scripts\voice-test.ps1 -Serial $serial` installs Kestrel, copies the models with `push-models.ps1`, runs the tests of the package `dev.kestrel.app.voice`, and keeps Kestrel and its test APK installed, because uninstalling Kestrel deletes the models. It fails when a test was skipped, and it prints the timings and the memory use that the tests measured. One test, `MicCaptureTest#silencedFollowsTheMicrophonesPrivacyToggle`, switches the device's microphone privacy toggle on and off. It skips itself on a phone, where the script also leaves it out, and on an emulator the script switches the toggle off at the end, so that a test process that died cannot leave the microphone silenced. Some of these tests start Kestrel's listening service, switch the emulator's screen off and on with `input keyevent`, turn the emulator's swipe lock screen on (`cmd lock_settings set-disabled false`) and off again, and grant Kestrel the microphone and notification permissions, which stay granted. `ActivationTest` sets the targets of the accessibility shortcut and button and puts them back. Its tile test puts Kestrel's tile first in Quick Settings and opens the panel, and then removes the tile and closes the panel again. It changes the list of tiles, which no script puts back if the test's process dies, so it skips itself on a phone. The lock-screen test unlocks with `wm dismiss-keyguard`, which cannot unlock a PIN, pattern or password, so like the privacy-toggle test it skips itself on a phone, and `voice-test.ps1` leaves all three tests out there (the third is `ActivationTest`'s tile test, above). On an emulator the script turns the lock screen off again at the end, in case the test's process died before it could. `connected-test.ps1` runs these tests too. They skip themselves when the models are missing, but the models stay on the device after `voice-test.ps1`, so `connected-test.ps1` then runs the voice tests as well; at the end Gradle uninstalls Kestrel, which deletes the models.

Kestrel is installed with `adb install`, which avoids Android's block on accessibility access for apps from unknown sources. On a Samsung phone, Auto Blocker must be off while USB debugging is in use.

The device tests of `:app` use the fixture screens of `:testapp` (`dev.kestrel.testapp`), a separate app that Gradle installs before the tests. If it was not installed before, `connected-test.ps1` uninstalls it when the tests end. Each test that uses the screen turns Kestrel's accessibility service off and on again. The tests replace the whole list of enabled accessibility services, so other services are off while they run, and Android clears the shortcuts that start those services. `connected-test.ps1` puts the accessibility settings back when the tests end. If you stop the tests with Ctrl+C, Windows asks "Terminate batch job (Y/N)?": answer it, and the script puts the settings back before it ends. If Ctrl+C interrupts the script while it puts things back, it prints the command that puts them back by hand. After Ctrl+C, the script ends with exit code 1. After the tests, Gradle uninstalls Kestrel and its test APK, which also switches Kestrel's service off. A run stopped with Ctrl+C during the tests leaves both installed; `connected-test.ps1` stops Kestrel, and you can remove them with `& $adb -s $serial uninstall dev.kestrel.test` and `& $adb -s $serial uninstall dev.kestrel`, after setting `$adb` as the next paragraph shows. Some tests touch the screen through a virtual touchscreen that Android's `uinput` tool creates: touches from `adb shell input` skip Android's accessibility input filter, while a virtual touchscreen behaves like a finger.

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

Kestrel's Quick Settings tile starts listening; add it when you edit the Quick Settings panel. Once Kestrel's accessibility service is on, the accessibility button and the accessibility shortcut start it too, when Android's accessibility settings name Kestrel as their target. Each of them toggles between listening and paused, or starts Kestrel when it is off. While Kestrel's accessibility service is off, the first press of the accessibility shortcut only turns the service on, and the next press toggles listening. When you turn the service on, Android adds Kestrel to the accessibility button by itself, and a tap on the button toggles listening. "Kestrel, turn off" or "Stop Kestrel" turns it off. While Kestrel's accessibility service is on, "Kestrel, open settings" opens this app; without the service, Android does not let Kestrel open it from the background. Listening needs the speech models (`push-models.ps1`), the microphone permission and, for the notification, the notification permission (the setup checklist asks for both). While Kestrel listens, its notification shows whether it listens or is paused, and its Pause or Resume and Stop actions work. Kestrel stops recording while the screen is off or the phone locked, and goes on after the unlock. When Android ends Kestrel's process, or the phone restarts, Kestrel comes back in the mode that it had, listening or paused, as soon as its accessibility service connects again. In a debug build the hooks start and drive listening too. A start from a hook needs Kestrel in front or its accessibility service on, because Android lets an app in the background start a microphone service only then.

```powershell
scripts\hook.ps1 -Serial $serial voice -Send toggle                     # starts listening, or pauses it
scripts\hook.ps1 -Serial $serial voice-state                            # the Orchestrator's state, never what was said
scripts\hook.ps1 -Serial $serial voice -Send heard -Text 'Kestrel' -Named   # as if Kestrel had heard its name
scripts\hook.ps1 -Serial $serial voice-clip -Text 'Kestrel, wake up.'   # says the text into a stand-in microphone
scripts\hook.ps1 -Serial $serial voice-clip -Clear                      # gives Kestrel its own microphone back
scripts\hook.ps1 -Serial $serial voice -Send off                        # turns Kestrel off and stores Off, so that it does not come back at its next start
```

`voice-clip` makes Kestrel hear only such clips from its next start until `voice-clip -Clear` or the end of its process. After `voice-clip -Clear`, Kestrel uses its own microphone again from its next start.

`scripts\listen-check.ps1 -Serial $serial` checks the ways in which listening starts without a word, with Kestrel in the background: a tap on the tile while the accessibility service is off, the accessibility shortcut, "Kestrel, open settings" (a transcript that the hook sends) while the service is on, and the return of listening after Android ended Kestrel's process (`am crash`) and, with `-Reboot`, after a reboot. In every check except the settings check, it also checks in `dumpsys audio` that Android did not silence Kestrel's recording. On the emulator, start a run with `-Reboot` from a cold boot (`& $adb -s $serial emu kill`, then `scripts\start-emulator.ps1 -ColdBoot`): on this computer, `adb reboot` has crashed and frozen an emulator that had run for many hours. The reboot check fails when `adb reboot` has not returned within 60 s. It needs Kestrel's debug build and the speech models, prints one line for each check that ran and never what Kestrel heard, and needs PowerShell 7.3 or newer, as `hook.ps1` does. While it runs, it grants Kestrel the microphone permission, replaces the list of enabled accessibility services, sets the target of the accessibility shortcut, and puts Kestrel's tile first in Quick Settings and opens the panel for the tile check. Afterwards, also when it fails or is interrupted, it turns Kestrel off, closes the panel and puts the rest back. It ends with exit code 1 when a check failed, when it could not put something back (it then prints the command that does it by hand), or after Ctrl+C. Android does not restart an app that crashed twice within 2 minutes, so wait 2 minutes before you run the script again.

Release builds do not contain the hooks, and the privacy audit fails a release build that does.

### Spikes

These scripts check assumptions of the design on a test device (design spec, section 19). They need Kestrel's debug build, and the results are in section 19.1 of the design spec.

- `scripts\spike-s1.ps1 -Serial $serial` checks whether Kestrel can read the location while another app is in front, the one part of spike S1 that still uses the debug hooks. It prints whether a location came, never the place. While it runs, it grants Kestrel the location permissions, changes the accessibility services, turns location on, lets the shell set mock locations and replaces the fused location provider with a test provider. Afterwards, also when the script fails or is interrupted, it puts all of this back as it was before, and it revokes only the permissions that Kestrel did not have. Before it turns the accessibility service on, it turns Kestrel off, and Kestrel stays off afterwards. Android may still keep the made-up place as the last known location, until the device gets a real location or restarts. If it cannot put something back, also because Ctrl+C interrupted it while it put things back, it says at the end what is left and the command that puts it back, and it ends with exit code 1. After Ctrl+C, it also ends with exit code 1.
- `scripts\spike-s4.ps1 -Serial $serial -Browser chrome, firefox` serves `scripts\spike-s4\page.html` from this computer with the JDK's `jwebserver`, opens it in each browser through `adb reverse`, and prints which of the page's elements Kestrel reads and whether Kestrel can type into its text fields. A browser that is not installed is skipped. The script taps through the browsers' first-run screens that it knows, and otherwise asks you to finish them. While it runs, it replaces the enabled accessibility services with Kestrel's, adds an `adb reverse` rule, runs `jwebserver` on this computer and stops each browser after its checks, also one that was running before. Before it turns the accessibility service on, it turns Kestrel off, and Kestrel stays off afterwards. Afterwards, also when it fails or is interrupted, it puts back the accessibility settings, removes the rule and stops `jwebserver`. It leaves on purpose Firefox, which the plan installs on the emulator for this spike, and in the browsers the tabs and the history of the page and the choices of their first-run screens. If it cannot put something back, also because Ctrl+C interrupted it while it put things back, it says at the end what is left and how to put it back, and it ends with exit code 1. After Ctrl+C, or when Kestrel cannot scroll in a browser, it also ends with exit code 1.
- `scripts\spike-s7.ps1 -Serial $serial` pushes a file of random bytes into Kestrel's external files folder with `adb push`, where the model files will live, and checks that Kestrel reads the same bytes. While it runs, it creates folders in Kestrel's external files folder, also `Android/data/dev.kestrel` and its `files` folder if they are not there, and it writes the file in the temporary folder of this computer. It also reinstalls Kestrel with `adb install -r`, which stays, as after `scripts\install.ps1`. Afterwards, also when it fails or is interrupted, it deletes the folders that it created and the file. If it cannot delete something, also because Ctrl+C interrupted it while it deleted things, it says at the end what is left and the command that deletes it, and it ends with exit code 1. After Ctrl+C, it also ends with exit code 1.
- Spike S8, whether sherpa-onnx's own ONNX Runtime and onnxruntime-android work in one process, is the device test `OnnxRuntimeTogetherTest`, which `scripts\voice-test.ps1 -Serial $serial` runs with the other voice tests.

## Setting up Kestrel

Open Kestrel from the launcher. The first entry of its settings app is the setup checklist, which says how many of its steps are done (design spec, section 15). Whenever Kestrel comes to the front, the checklist reads the state of steps 3, 4, 5 and 8 from Android, so it shows the changes that you make in Android's settings. Kestrel checks the speech model files for step 2 then too, and also whenever the checklist or the About page opens.

1. Install Kestrel with `scripts\install.ps1`. On a Samsung phone, Auto Blocker must be off while USB debugging is on.
2. Copy the speech models with `scripts\push-models.ps1 -Serial $serial`. Kestrel must be installed first. The first run downloads about 760 MB into `%LOCALAPPDATA%\Kestrel\models`, from the addresses in `models\manifest.json`: sherpa-onnx's releases on GitHub and Hugging Face, which redirect to their own download servers. It checks each file's SHA-256 hash against the manifest. Then it copies the files that the phone lacks, and replaces those whose size or hash differs, into Kestrel's folder `Android/data/dev.kestrel/files/models`; on the first run, these are about 740 MB. Kestrel checks them against the same hashes. Kestrel creates that folder when it starts, so the first run after an install may open Kestrel on the phone; open the checklist again to see step 2's new state. Uninstalling Kestrel deletes the models from the phone.
3. Turn on Kestrel's accessibility service. The step's button opens Android's accessibility settings.
4. Allow the microphone and notifications. The step's button asks Android for both permissions. After you refuse twice, Android no longer asks, and the button opens Kestrel's app settings instead.
5. Allow unrestricted battery use. The step's button opens Kestrel's app settings, where the battery setting is. On a Samsung phone, also add Kestrel to "Never sleeping apps" in the battery settings.
6. Optionally, make Kestrel the default digital assistant, for the side key and earbuds. This step comes with Kestrel's voice features.
7. Sign in to an AI provider and choose the models. This step comes with Kestrel's provider settings.
8. Optionally, turn off Voice Access, so that it and Kestrel do not react to the same words. While Voice Access is on, the step's button opens Android's accessibility settings, and the checklist warns while both are on.

The settings app also has these pages:

- **Safety:** test mode, which is on until you turn it off. While it is on, Kestrel's agent and routines will send and share only to Telegram's Saved Messages, WhatsApp's Message yourself, Viber's My Notes and your own email address, and they will make no calls (design spec, section 12.7). Your own instant commands are not limited. Keep test mode on while you test Kestrel. The agent comes later, so in this version test mode changes nothing.
- **Activity log** and **Network log:** what Kestrel did, and every request that it sent, encrypted and kept for 7 days. *Export both logs* writes both logs to a JSON file that you choose, which is the only way the logs leave the phone. *Delete history* wipes both logs after you confirm.
- **About:** Kestrel's version, the Android version, whether each speech model is ready, and the licence texts.

## Licence

Kestrel is free software under the GNU General Public License, version 3 or (at your option) any later version. See [LICENSE](LICENSE). The licences of the libraries that Kestrel uses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), and the [licences](licences) folder holds the texts of those licences. Kestrel's About page shows all of these texts.
