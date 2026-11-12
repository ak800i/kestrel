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
scripts\gradle.ps1 check kestrelAudit               # unit tests, lint and the privacy audit
scripts\gradle.ps1 :build-logic:convention:check    # tests of the audit itself
```

To open the project in Android Studio, use Android Studio Rabbit 2 (2026.2.2) or newer, which supports version 9.5 of the Android Gradle plugin.

## Testing on a device

Kestrel is tested only on the `kestrel-api37` emulator and on a Galaxy A71. The scripts refuse other devices.

```powershell
scripts\create-avd.ps1                    # once: installs Android 17 and creates kestrel-api37
$serial = scripts\start-emulator.ps1      # starts the emulator and prints its adb serial
scripts\connected-test.ps1 -Serial $serial :core:privacy:connectedDebugAndroidTest :app:connectedDebugAndroidTest
scripts\install.ps1 -Serial $serial       # installs the debug build with adb install -r
```

Kestrel is installed with `adb install`, which avoids Android's block on accessibility access for apps from unknown sources. On a Samsung phone, Auto Blocker must be off while USB debugging is in use.

## Licence

Kestrel is free software under the GNU General Public License, version 3 or (at your option) any later version. See [LICENSE](LICENSE).
