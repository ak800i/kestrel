# Kestrel Foundation 1C: Settings App, Spikes and the A71 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish sub-project 1 (Foundation): fix what the reviews of plan 1B deferred, build the settings app with its setup checklist, safety page, log pages and About page, answer spikes S1, S4 and S7 on the emulator with scripts that repeat them on any test device, and run the device tests and the spikes on the Galaxy A71 with the owner.

**Architecture:** Tasks 1–7 harden plan 1B's code: the test tools, the test hooks, crash privacy, the snapshot cache, gestures, fallback points and the overlays. Tasks 8–10 build the settings app in `:app` with Jetpack Compose. Pure decisions, such as `SetupChecklist` and `licenceParagraphs`, have JVM tests. The pages read and change Kestrel's data through a small `SettingsData` interface, so that Compose UI tests can use a fake, and `MainActivity` connects them to Android and to Kestrel's stores. Task 9 adds an export to `:core:privacy`'s `LogStore`. Tasks 11 and 12 add debug-only spike code (a stand-in listening service, a tile and test hooks) and PowerShell scripts that run spikes S1, S4 and S7 and print what they found. The results go into section 19.1 of the spec. Task 13 documents the settings app, and Task 14 is a session on the A71 with the owner.

**Tech Stack:** As in plans 1A and 1B: Gradle 9.9.1, the Android Gradle plugin 9.5.1 with built-in Kotlin 2.4.21, kotlinx.coroutines 1.11.0, kotlinx.serialization 1.13.0, Jetpack Compose (BOM 2026.09.00), JUnit 4 and AndroidX Test. New in this plan: Compose UI tests (`ui-test-junit4` with its v2 `createAndroidComposeRule`, and `ui-test-manifest`), Espresso 3.7.0, the JDK's `jwebserver` for spike S4, and Firefox 160 for Android from Mozilla, on the emulator.

**Spec:** [2026-11-12-kestrel-design.md](../specs/2026-11-12-kestrel-design.md). This is plan 1C, the last plan of sub-project 1, Foundation (spec section 18.1). It builds on plans 1A and 1B, [2026-11-12-foundation-1a-build-audit-privacy.md](2026-11-12-foundation-1a-build-audit-privacy.md) and [2026-11-13-foundation-1b-screen-layer.md](2026-11-13-foundation-1b-screen-layer.md). The items that the reviews of plan 1B deferred to this plan are listed in the progress ledger, `.superpowers/sdd/progress.md`, by their review IDs, such as (5d); each task names the items that it closes.

**Items that stay open.** Some deferred items concern the actions that the agent will use, so sub-project 3 handles them together with the safety check: (6c), a retry after a refusal can press twice when an app acted but answered `false`, and then a fallback gesture can press a third time; (6b), no test reaches the retry path; (8h), (6l) and (7c), other untested branches of the actions; and (7j) = (8i), splitting `ScreenActions.kt`. (3h), the extra snapshot builds that Kestrel's own overlays cause, goes to the commands sub-project, which shows overlays all the time. (8d) stays open, as Task 5 explains.

## Global Constraints

- Package and application ID `dev.kestrel`; repository `H:\Code\kestrel`, published as `github.com/ak800i/kestrel` (D21).
- Licence GPL-3.0-or-later for all of Kestrel; every dependency that ships in the app must have a licence that is compatible with GPL-3.0, such as Apache-2.0, MIT or BSD. Tools used only to build or test Kestrel, such as JUnit, are exempt (D28, section 17).
- `minSdk 33`, `targetSdk 37` and `compileSdk 37` (section 6).
- Gradle with the Kotlin DSL and a version catalog; Android Gradle plugin 9.5.1 and Kotlin 2.4.21 (section 17). The plugin has Kotlin built in: never apply `org.jetbrains.kotlin.android`.
- There is no dependency-injection framework; one `AppGraph` class wires the parts together by hand (section 17).
- Kestrel has no WebView, no analytics, no crash reporting and no ads, and it does not use Google Play services, Firebase, ML Kit or Android's geocoder (section 13.1). `:testapp` is never shipped, so a fixture may use a WebView.
- All network traffic from Kotlin code goes through one OkHttp client in `:core:privacy` (section 13.1).
- Screenshots, audio and credentials are never logged (section 13.5). Errors go to logcat only as class names and stack frames, never with their messages, because a message can contain screen text.
- The settings app is Activities built with Jetpack Compose (section 6). Its screens in this plan are the setup checklist, safety (test mode), the activity log, the network log and an About page with licences, versions and the status of the model files (section 15). Later sub-projects add the other screens of section 15.
- The setup checklist stays visible until everything is done. Its steps are: 1. Install Kestrel with `scripts/install.ps1`, which runs `adb install -r`; on the S23+, Samsung's Auto Blocker must be off while USB debugging is used. 2. Copy the models with `scripts/push-models.ps1`. 3. Turn on Kestrel's accessibility service. 4. Allow the microphone and notifications. 5. Allow unrestricted battery use, and add Kestrel to Samsung's "Never sleeping apps". 6. Optionally, make Kestrel the default digital assistant, for the side key and the earbuds. 7. Sign in to a provider and choose the models. 8. Optionally, turn off Voice Access, so that the two apps don't react to the same words; Kestrel shows a warning while both are on (section 15).
- `RECORD_AUDIO` and `POST_NOTIFICATIONS` are runtime permissions that the setup asks for (section 13.6).
- Both logs are encrypted and can be viewed in settings. "Kestrel, delete history" (after a spoken confirmation) or a button wipes both. The logs leave the phone only when the owner exports them to a file of their choice (section 13.5).
- Test mode is a development setting, and it is on during all testing (D25, section 12.7).
- Debug builds contain test hooks that accept commands only from callers that hold `android.permission.DUMP`, which in practice means `adb shell`. Release builds do not contain them, and the build-time audit fails a release build that does (section 16.1). The spike code of this plan is debug-only as well.
- Instrumented tests run on `kestrel-api37`, and on the A71 before each release (section 16.3). Scripts that change a device's settings for a test put them back afterwards.
- Testing happens only on the `kestrel-api37` emulator (Android 17, API 37) and the Galaxy A71. Never test on the Galaxy S23+ unless the owner asks, and never change the existing AVD `zoran` (D20, section 6).
- The Android SDK is in `D:\AndroidVMs\SDK`. Scripts are PowerShell, and environment changes stay inside the process that makes them (section 17).

## How to work in this plan

- Run every command in PowerShell 7 from the repository root, `H:\Code\kestrel`.
- Run Gradle only through `scripts\gradle.ps1`, and on-device tests only through `scripts\connected-test.ps1`. A Gradle that runs on another JDK, such as the one that `JAVA_HOME` names, starts a second pair of background processes, and each pair holds several GB of memory. When you stop working, run `scripts\gradle.ps1 --stop`.
- `scripts\start-emulator.ps1` starts `kestrel-api37` when it is not running and prints its adb serial, for example `emulator-5554`. The commands below write `<serial>` for it.
- To run one test class on the device, add `'-Pandroid.testInstrumentationRunnerArguments.class=<class name>'` after the Gradle task; several classes are separated by commas. The results are in `app/build/outputs/androidTest-results/connected/debug/TEST-*.xml`, and a failure prints `There were failing tests` with a link to the HTML report.
- After the device tests, Gradle uninstalls Kestrel and its test APK. `:testapp` stays installed. `scripts\install.ps1 -Serial <serial>` installs the debug build again, for the manual checks and the spikes.
- If the device tests become slow, or Android restarts while they run, restart the emulator with a cold boot: `& (Get-Adb) -s <serial> emu kill`, wait until it has closed, then `scripts\start-emulator.ps1 -ColdBoot` (Task 1 adds this switch). Do not run device tests in the first two minutes after the emulator boots.
- A passing Gradle command ends with `BUILD SUCCESSFUL`. A failing one ends with `BUILD FAILED` and prints the reason above it. Gradle prints Kotlin warnings as lines that start with `w:`; new code must not add any.
- Commit at the end of every task. Use a Conventional Commits subject and end every commit message with this trailer line:

  `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`

  In PowerShell: `git commit -m "<subject>" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"`.

## Things that surprised us while building this plan

The code below already handles all of these. They are written down so that nobody "fixes" them back.

- Android's own crash pre-handler writes an uncaught error's message to the crash log before any handler that an app installs can run. Kestrel therefore runs the main thread's message loop inside its own loop (`MainThreadErrors`), which turns an error into a copy without messages before Android sees it. Android then logs "Loop again would have the queued messages be executed before this one completed" once per process start; that is expected. The copy must extend `RuntimeException`: Android wraps a plain `Throwable` in two more exceptions.
- Opening Quick Settings in a test and then running `cmd statusbar collapse` leaves the shade open, because the collapse runs before the panel has finished opening. The hook test for names with hyphens uses `play-pause` instead.
- `run-as` starts in `/data/user/0/<package>`, so the snapshot hook answers with a path relative to that folder.
- On `kestrel-api37`, Android sends a windows-changed event a moment after a magnification change, so `numbersFollowAZoom` passes even without the fix of Task 4; the test exists for the A71. The magnification centre is given in unscaled coordinates. The fixture's SEND button spans the screen's width, so the zoom test compares heights.
- The emulator's default touch-and-hold delay is 400 ms, and Android lets the owner set it up to 1500 ms. SEND in the Views fixture has a long-click listener that follows Android's setting.
- An `Intent` with a `file://` link throws `FileUriExposedException` ("exposed beyond app through Intent.getData()").
- After about ten hours, Android inside the emulator slowed down (load 23–34, `dumpsys` hung, and `getprop` once answered with nothing). A normal restart brought the slow state back from the emulator's Quick Boot snapshot; a cold boot fixed it. The device guard therefore checks a refusal twice more, and `start-emulator.ps1 -ColdBoot` starts Android afresh.
- Compose's test library brings Espresso 3.5, which fails on Android 17 with `NoSuchMethodException` for `InputManager.getInstance`. Espresso 3.7.0 works.
- `createAndroidComposeRule` from `androidx.compose.ui.test.junit4` is deprecated; the tests use `androidx.compose.ui.test.junit4.v2.createAndroidComposeRule`.
- `ui-test-manifest` adds an exported, empty `androidx.activity.ComponentActivity` to the debug app, which any app could start. The debug manifest makes it not exported; the tests can still start it, because they run in Kestrel's own process.
- aapt2 rejects an unescaped apostrophe, backslash or double quote in `strings.xml` with "Invalid unicode escape sequence in string". Write `\'`, `\\` and `\"`.
- Lint's `PluralsCandidate` check flags "%1$d of %2$d steps done", so the progress text is a plural resource, chosen by the number of steps.
- `MainActivity`'s class is `dev.kestrel.app.MainActivity`, while the app's ID is `dev.kestrel`: `am start -n dev.kestrel/.MainActivity` starts nothing. Use `-n dev.kestrel/dev.kestrel.app.MainActivity`.
- Android's permission dialog says "Don’t allow" with a typographic apostrophe (U+2019).
- Revoking a runtime permission ends the app's process, and in an instrumented test that is also the test's process. The device tests therefore only grant permissions.
- `AccessibilityManager.getEnabledAccessibilityServiceList` has no package-visibility filter in AOSP, so Kestrel sees Voice Access without a `<queries>` entry. While a UiAutomation that suppresses accessibility services is connected, it returns an empty list.
- The emulator has no lock screen until `cmd lock_settings set-disabled false`. `dumpsys activity activities` then shows `KeyguardShowing=true` after the screen goes off and on.
- `adb emu geo fix` gave the fused location provider no location. The shell's test provider does: `cmd location providers add-test-provider fused`, which needs `appops set com.android.shell android:mock_location allow`.
- `KestrelApplication` creates its graph in `onCreate()`, which Android calls after the androidx.startup initializers, so the spike's initializer posts its work to the main thread.
- Lint's `ForegroundServicePermission` check looks for `RECORD_AUDIO` only in the manifest file that declares the microphone service, so the debug manifest lists it again.
- A folder that `adb shell mkdir` creates in Kestrel's external files folder belongs to the shell user with the mode 2770, and Kestrel may not read it.
- Chrome 145's first run on Android 17 shows "Stay signed out", then a notifications prompt with "No thanks". Firefox 160's shows "Continue", then Android's "Set Firefox as your default browser app?" with "Cancel", then "Not now".
- Firefox restores what was typed into a page when the same address opens again, so the S4 script gives each opening its own address. A Firefox that has just started may refuse typed text for a few seconds.
- In PowerShell, an `if` expression that returns `@()` gives `$null`, and splatting an array passes `-Name` as a plain value, so the scripts splat hashtables.
- When Android turns off an accessibility service that asks for the accessibility button, it also removes the service from the accessibility shortcuts: `accessibility_button_targets` and `accessibility_shortcut_target_service`, and on Android 17 also `accessibility_gesture_targets` and `accessibility_qs_targets`. Putting the list of services back does not bring the shortcuts back, so the scripts save and restore every secure setting whose name contains `accessibility` or `touch_exploration`.
- `am force-stop` on an app whose accessibility service is on makes Android remove that service from `enabled_accessibility_services` by itself, within about 60 ms on the emulator, and Android then writes the other names in their short form (`…/.AccessibilityMenuService`).
- After Ctrl+C in a script that runs `gradlew.bat`, Windows asks "Terminate batch job (Y/N)?", and PowerShell 7 and Windows PowerShell 5.1 both wait for the answer before they run the script's `finally` block. A second Ctrl+C at that question changes nothing, and Ctrl+C during the `finally` block does not stop it.
- In Windows PowerShell 5.1, an adb error line that is redirected with `2>$null` or `2>&1` stops a script whose `$ErrorActionPreference` is `Stop`; PowerShell 7 goes on. The settings functions and the device guard therefore set `Continue` for themselves.

## File map

| Path | Responsibility |
|---|---|
| `scripts/connected-test.ps1`, `scripts/lib/Kestrel.ps1`, `scripts/start-emulator.ps1` | Test tools that restore the device's accessibility services, a more patient device guard, and the cold boot |
| `app/src/androidTest/.../screen/ScreenServiceRule.kt`, `TestFinger.kt` | The device tests' rule and virtual finger, now fit for the A71 |
| `app/src/debug/.../debug/` | The test hooks; the snapshot hook writes to Kestrel's cache |
| `core/privacy/.../logs/MainThreadErrors.kt`, `ErrorSummary.kt`, `MessageFreeCrashHandler.kt` | Crash records without messages |
| `core/privacy/.../logs/LogStore.kt`, `LogExport.kt` | The daily purge for a long-running service, and the export of both logs |
| `core/screen/.../read/SnapshotCache.kt`, `ScreenChangeTracker.kt` | Waiting for a quiet screen without holding the cache's lock |
| `core/screen/.../act/` | Gestures that cannot hang, that follow Android's settings, and whose fallback points avoid windows above (`Uncovered.kt`) |
| `core/screen/.../overlay/` | Overlays that close completely with their session |
| `app/.../settings/` | The settings app: `SetupChecklist`, `SetupStatusReader`, `SettingsData`, `SettingsApp` and its pages |
| `app/.../MainActivity.kt` | Hosts the settings app; asks for permissions, opens Android's settings and exports the logs |
| `licences/` | The texts of the Apache License 2.0 and the Mozilla Public License 2.0, which the About page shows |
| `app/src/debug/.../debug/spike/` | The spike code: a stand-in listening service, a tile, a start-up hook and the spike's test hooks |
| `scripts/spike-s1.ps1`, `scripts/spike-s4.ps1`, `scripts/spike-s4/page.html`, `scripts/spike-s7.ps1` | The spikes, repeatable on any test device |

---

### Task 1: Test tools that restore the device and work on the A71

**Files:**
- Modify: `scripts/connected-test.ps1` (save and restore the accessibility settings)
- Modify: `scripts/lib/Kestrel.ps1` (`Assert-TestDevice` checks a refusal twice more; `Invoke-SettingsCommand`, `Get-SecureSetting`, `Set-SecureSetting`, `Get-AccessibilitySettings` and `Restore-AccessibilitySettings`)
- Modify: `scripts/start-emulator.ps1` (`-ColdBoot`, with a warning when it cannot take effect)
- Modify: `app/src/androidTest/kotlin/dev/kestrel/app/screen/ScreenServiceRule.kt` (`overlayButton` matches whole texts; new `overlayShows`)
- Modify: `app/src/androidTest/kotlin/dev/kestrel/app/screen/TestFinger.kt` (reports the `uinput` tool's log lines and closes its pipes)
- Modify: `app/src/androidTest/kotlin/dev/kestrel/app/screen/GridBarAndCardTest.kt` (uses `overlayShows`)
- Modify: `README.md`

**Interfaces:**
- Consumes: plan 1B's test tools.
- Produces: `Get-SecureSetting -Serial <serial> -Name <setting>` reads a secure setting, which may be `null` or empty, and asks twice more before it fails; `Set-SecureSetting -Serial <serial> -Name <setting> -Value <value>` puts back what it read, deleting the setting for `null`, returns whether the device took it, and warns when it did not. `Get-AccessibilitySettings -Serial <serial>` returns every secure setting whose name contains `accessibility` or `touch_exploration`, as an ordered dictionary of names and values; `Restore-AccessibilitySettings -Serial <serial> -Saved <dictionary>` puts back those that changed, the list of enabled services first, deletes those that were not there before, and returns whether everything is back; the spike scripts of Tasks 11 and 12 use both. `ScreenServiceRule.overlayButton(text: String): Rect?` returns only a clickable node whose whole text is `text`, ignoring case; `ScreenServiceRule.overlayShows(text: String): Boolean` says whether a touchable overlay shows `text` as a whole text. `scripts\start-emulator.ps1 -ColdBoot` starts the emulator with `-no-snapshot-load`.

Closes (5d), (10g) and (11d), and a problem found while this plan was built.

The device tests replace the whole list of enabled accessibility services. On the emulator that does not matter, but on the A71 it would leave the owner's other services off. Android also removes each service that it turns off from the accessibility shortcuts, and putting the list back does not bring the shortcuts back. So `connected-test.ps1` now saves every secure setting whose name contains `accessibility` or `touch_exploration` before Gradle runs, and puts back those that changed in a `finally` block, even when the tests fail or are interrupted. The list of services goes first, so that the shortcuts point to services that are on again. Android answers `null` for a setting that is not set, and a setting can also hold an empty value, which Android's Settings writes when the owner turns the last service off. A busy device sometimes gives no answer at all, and while Android's system is still starting, the `settings` command fails. So `Invoke-SettingsCommand` asks the device to print a marker only after a command that worked, and asks again while the marker is missing; if the settings still cannot be read, the tests do not start, instead of deleting the owner's settings afterwards. Its error names the device's last complaint, and adb's error lines no longer fill the console. `Set-SecureSetting` reads the value back and warns when the device did not take it, with the reason and a command that the owner can paste into PowerShell to put the value back. When a setting cannot be put back, `connected-test.ps1` ends with exit code 1, even if the tests passed. Windows PowerShell 5.1 stops a script at adb's first error line when that line is redirected, so the settings functions and the device guard set `$ErrorActionPreference` to `Continue` for themselves. Before the restore, `connected-test.ps1` stops Kestrel, because tests that still run on the device after an interrupted Gradle would replace the list again. When Kestrel stops, Android removes Kestrel's service from the list by itself, within about 60 ms on the emulator, so the script waits up to 5 seconds until the list no longer contains Kestrel's service; otherwise Android could write its own copy of the list, without Kestrel, over the restored one. After Ctrl+C, Windows asks "Terminate batch job (Y/N)?" before the restore can run, so the README tells the owner to answer it. The `uinput` tool writes its errors to Android's log, on Android 13 as on 17, so `TestFinger` adds the tool's log lines since the finger was made, without their stack frames, to its message when the virtual touchscreen does not appear, and it closes its pipes when it fails. Android's text search also finds a text that only contains the search text, such as a question that contains a button's label, so `overlayButton` accepts only a whole text. The emulator incident under "Things that surprised us" explains the device guard's second and third check and `-ColdBoot`.

This task changes test tools only, so it has no failing test of its own; Step 4 checks the tools.

- [ ] **Step 1: Restore the device's accessibility services and make the device guard patient**

Replace the whole content of `scripts/connected-test.ps1` with:

```powershell
# Runs Gradle's connected (on-device) tests on one test device only.
# Example: scripts\connected-test.ps1 -Serial emulator-5554 :core:privacy:connectedDebugAndroidTest
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)][string[]]$Tasks
)
$ErrorActionPreference = 'Stop'
# Gradle's --serial option would override ANDROID_SERIAL and bypass the device guard.
$serialOption = $Tasks | Where-Object { $_ -like '--serial*' } | Select-Object -First 1
if ($serialOption) { throw "Refusing the Gradle argument '$serialOption'. Choose the device with -Serial." }
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial
# The Android Gradle plugin runs connected tests only on the devices listed in ANDROID_SERIAL.
$env:ANDROID_SERIAL = $Serial
# The device tests replace the list of enabled accessibility services, and Android then clears the shortcuts of the
# services that it turns off. So the accessibility settings are put back afterwards, even when the tests fail or are
# interrupted. If they cannot be read, the tests do not start.
$saved = Get-AccessibilitySettings -Serial $Serial
$code = 1
try {
    & "$PSScriptRoot\..\gradlew.bat" --console=plain @Tasks
    $code = $LASTEXITCODE
} finally {
    # Tests that still run on the device after Gradle was interrupted would replace the list again, so they stop first.
    # Android then removes Kestrel's service from the list itself, and the restore waits for that, so that Android's
    # change does not overwrite it.
    & (Get-Adb) -s $Serial shell am force-stop dev.kestrel | Out-Null
    $deadline = (Get-Date).AddSeconds(5)
    while ((Get-Date) -lt $deadline) {
        $services = try { Get-SecureSetting -Serial $Serial -Name enabled_accessibility_services } catch { $null }
        if ($null -ne $services -and $services -notlike '*dev.kestrel/*') { break }
        Start-Sleep -Milliseconds 500
    }
    # A run whose tests passed still fails when the device keeps changed settings.
    if (-not (Restore-AccessibilitySettings -Serial $Serial -Saved $saved) -and $code -eq 0) { $code = 1 }
}
exit $code
```

Replace the whole content of `scripts/lib/Kestrel.ps1` with:

```powershell
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

# Runs a settings command on a device and returns the lines that it printed. The device prints a marker only after a
# command that worked, which tells an empty answer from a failed command. A busy device, or one whose system is still
# starting, sometimes fails the command, so it is repeated before it fails for good with the device's last complaint.
function Invoke-SettingsCommand([string]$Serial, [string]$Command) {
    $adb = Get-Adb
    # Keeps adb's error lines as part of the answer, so that they neither stop the script nor fill the console.
    $ErrorActionPreference = 'Continue'
    $complaint = ''
    foreach ($try in 1..3) {
        if ($try -gt 1) { Start-Sleep -Seconds 2 }
        $answer = @(& $adb -s $Serial shell "$Command && echo kestrel-end" 2>&1)
        $errors = @($answer | Where-Object { $_ -is [System.Management.Automation.ErrorRecord] })
        $lines = @($answer | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] } | ForEach-Object { "$_".TrimEnd("`r") })
        if ($lines.Count -gt 0 -and $lines[-1].Trim() -eq 'kestrel-end') { return $lines | Select-Object -First ($lines.Count - 1) }
        $complaint = (@($errors | ForEach-Object { "$_".Trim() } | Where-Object { $_ }) -join ' ')
    }
    if ($complaint) { throw "Could not run '$Command' on ${Serial}: $complaint" }
    throw "Could not run '$Command' on $Serial."
}

# Reads a secure setting of a device. Android answers 'null' for a setting that is not set, and a setting can also hold
# an empty value.
function Get-SecureSetting([string]$Serial, [string]$Name) {
    (Invoke-SettingsCommand -Serial $Serial -Command "settings get secure $Name") -join "`n"
}

# Puts back a secure setting that Get-SecureSetting read, deleting it for 'null', and returns whether the device took it.
# When it did not, it warns with a command that puts the setting back when it is pasted into PowerShell.
function Set-SecureSetting([string]$Serial, [string]$Name, [string]$Value) {
    $adb = Get-Adb
    $ErrorActionPreference = 'Continue'
    # A single quote would end the device shell's quoting, so it becomes '\''.
    $command = if ($Value -eq 'null') { "settings delete secure $Name" } else { "settings put secure $Name '$($Value -replace "'", "'\''")'" }
    & $adb -s $Serial shell $command 2>$null | Out-Null
    $reason = ''
    $now = try { Get-SecureSetting -Serial $Serial -Name $Name } catch { $reason = " ($($_.Exception.Message))"; $null }
    if ($now -ceq $Value) { return $true }
    $literal = { param($text) "'" + ($text -replace "'", "''") + "'" }
    Write-Warning "Could not put back the setting $Name of $Serial$reason. Run: & $(& $literal $adb) -s $Serial shell $(& $literal $command)"
    $false
}

# Reads the secure settings that belong to accessibility: the list of enabled services, the accessibility switch, and
# the shortcuts that start a service, which Android clears for a service that it turns off. Returns names and values.
function Get-AccessibilitySettings([string]$Serial) {
    $settings = [ordered]@{}
    foreach ($line in (Invoke-SettingsCommand -Serial $Serial -Command 'settings list secure')) {
        if ($line -match '^([^=]*(accessibility|touch_exploration)[^=]*)=(.*)$') { $settings[$Matches[1]] = $Matches[3] }
    }
    $settings
}

# Puts back the settings that Get-AccessibilitySettings read, and deletes those that were not there. The list of enabled
# services goes first, so that the shortcuts point to services that are on again. Returns whether everything is back.
function Restore-AccessibilitySettings([string]$Serial, $Saved) {
    try {
        $now = Get-AccessibilitySettings -Serial $Serial
    } catch {
        $before = @($Saved.Keys | ForEach-Object { "$_=$($Saved[$_])" }) -join "`n"
        Write-Warning "Could not read the accessibility settings of $Serial ($($_.Exception.Message)), so they were not put back. Before, they were:`n$before"
        return $false
    }
    $back = $true
    $names = @('enabled_accessibility_services', 'accessibility_enabled') + @(@($Saved.Keys) + @($now.Keys) | Sort-Object -Unique)
    foreach ($name in ($names | Select-Object -Unique)) {
        $value = if ($Saved.Contains($name)) { $Saved[$name] } else { 'null' }
        $current = if ($now.Contains($name)) { $now[$name] } else { 'null' }
        if ($value -cne $current -and -not (Set-SecureSetting -Serial $Serial -Name $name -Value $value)) { $back = $false }
    }
    $back
}

function Find-EmulatorSerial([string]$AvdName) {
    $adb = Get-Adb
    # Windows PowerShell 5.1 would stop at adb's first error line.
    $ErrorActionPreference = 'Continue'
    foreach ($line in (& $adb devices | Select-Object -Skip 1)) {
        if ($line -match '^(emulator-\d+)\s+device') {
            $serial = $Matches[1]
            $name = & $adb -s $serial emu avd name 2>$null | Select-Object -First 1
            if ($name -and $name.Trim() -eq $AvdName) { return $serial }
        }
    }
    return $null
}

# Kestrel is tested only on kestrel-api37 and the Galaxy A71 (SM-A715F). The Galaxy S23+ (SM-S916*) is the
# owner's daily phone and is accepted only with -AllowDailyPhone (design spec, D20). Every other device is refused.
function Assert-TestDevice([string]$Serial, [switch]$AllowDailyPhone) {
    $adb = Get-Adb
    # Windows PowerShell 5.1 would stop at adb's first error line, before the second and third check.
    $ErrorActionPreference = 'Continue'
    # A busy emulator sometimes answers a query with nothing, so a refusal is checked twice more before it counts.
    foreach ($try in 1..3) {
        if ($try -gt 1) { Start-Sleep -Seconds 2 }
        $state = "$(& $adb -s $Serial get-state 2>$null)".Trim()
        if ($state -ne 'device') { continue }
        $model = "$(& $adb -s $Serial shell getprop ro.product.model)".Trim()
        # Emulators report their AVD name here, however adb reaches them.
        $avd = "$(& $adb -s $Serial shell getprop ro.boot.qemu.avd_name)".Trim()
        if ($avd -eq 'kestrel-api37' -or $model -like 'SM-A715F*') { return }
        if ($model -like 'SM-S916*' -and $AllowDailyPhone) { return }
    }
    if ($state -ne 'device') { throw "Device $Serial is not connected." }
    throw "Refusing to use $Serial (model '$model', AVD '$avd'). Kestrel is tested only on kestrel-api37 and the Galaxy A71."
}
```

Replace the whole content of `scripts/start-emulator.ps1` with:

```powershell
# Starts kestrel-api37 if it is not running, waits until Android has booted and prints its adb serial.
# The emulator keeps running in its own window after this script ends.
# -ColdBoot starts Android afresh instead of from the emulator's Quick Boot snapshot. Use it after closing a slow
# emulator, because the snapshot would bring the slow state back.
param([switch]$ColdBoot)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain

$name = 'kestrel-api37'
$adb = Get-Adb
$deadline = (Get-Date).AddMinutes(5)
$serial = Find-EmulatorSerial $name
if ($ColdBoot -and $serial) { Write-Warning "$name is already running, so -ColdBoot does nothing. Close it first: & '$adb' -s $serial emu kill" }
if (-not $serial) {
    # While the emulator boots, adb lists it as offline. Look for its process before starting a second copy.
    $running = Get-CimInstance Win32_Process -Filter "Name LIKE 'qemu-system%'" |
        Where-Object { $_.CommandLine -match "-avd\s+$([regex]::Escape($name))(\s|$)" }
    $started = $null
    if ($ColdBoot -and $running) { Write-Warning "$name is still starting or closing, so -ColdBoot does nothing." }
    if (-not $running) {
        $emulator = Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'
        # Software rendering: with the computer's graphics card, the emulator slowed down after a few hours of tests,
        # and once the graphics driver crashed (plan 1B).
        $arguments = @('-avd', $name, '-no-boot-anim', '-gpu', 'swiftshader')
        if ($ColdBoot) { $arguments += '-no-snapshot-load' }
        $started = Start-Process -FilePath $emulator -ArgumentList $arguments -WindowStyle Minimized -PassThru
        $null = $started.Handle  # Keeps the exit code readable after the process ends.
    }
    do {
        Start-Sleep -Seconds 5
        if ($started -and $started.HasExited -and $started.ExitCode -ne 0) {
            throw "The emulator exited with code $($started.ExitCode) before $name appeared in adb"
        }
        $serial = Find-EmulatorSerial $name
    } until ($serial -or (Get-Date) -gt $deadline)
    if (-not $serial) { throw "$name did not appear in adb within 5 minutes" }
}
do {
    Start-Sleep -Seconds 3
    $booted = & $adb -s $serial shell getprop sys.boot_completed 2>$null
} until ("$booted".Trim() -eq '1' -or (Get-Date) -gt $deadline)
if ("$booted".Trim() -ne '1') { throw "$name ($serial) did not finish booting within 5 minutes" }
Write-Output $serial
```

- [ ] **Step 2: Fit the rule and the virtual finger for the A71**

Replace the whole content of `app/src/androidTest/kotlin/dev/kestrel/app/screen/ScreenServiceRule.kt` with:

```kotlin
package dev.kestrel.app.screen

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.model.ScreenElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.rules.ExternalResource

/**
 * Gives each test a new screen session by turning Kestrel's accessibility service off and on again, so that
 * nothing one test leaves behind affects the next. It replaces the whole list of enabled accessibility services, so
 * other services are off while the tests run, and Android clears their shortcuts; `scripts\connected-test.ps1` saves the
 * accessibility settings before the tests and puts them back afterwards.
 */
class ScreenServiceRule : ExternalResource() {
    private val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KestrelApplication).graph

    lateinit var session: ScreenSession
        private set

    val automation: UiAutomation get() = Companion.automation

    override fun before() {
        runBlocking {
            shell("settings delete secure enabled_accessibility_services")
            withTimeout(10_000) { graph.screen.first { it == null } }
            shell("settings put secure enabled_accessibility_services $SERVICE")
            shell("settings put secure accessibility_enabled 1")
            session = withTimeout(10_000) { graph.screen.filterNotNull().first() }
        }
    }

    /** Runs a command as the shell user, which may change secure settings and start other apps' activities. */
    fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    /**
     * Starts an activity of the fixture app and waits until its [title] is on the screen. The fixture app is
     * stopped first, because Android would otherwise only bring its existing task to the front.
     */
    fun open(activity: String, title: String, extras: String = ""): ScreenElement {
        shell("am start -W -S -n $FIXTURES/.$activity $extras")
        return runBlocking { awaitElement { it.label == title } }
    }

    /** Whether Android's window list has a window with [title], such as "Kestrel numbers". */
    fun showsWindow(title: String): Boolean = shell("dumpsys window windows").contains("$title}")

    /**
     * Waits until Android's input dispatcher knows the window with [title], such as "Kestrel touch watcher". A new
     * window gets touches only from then on, a moment after it was added.
     */
    suspend fun awaitInputWindow(title: String) {
        withTimeoutOrNull(5_000) {
            while (shell("dumpsys input").lineSequence().none { title in it && "displayId=" in it }) delay(50)
        } ?: throw AssertionError("Android's input dispatcher did not list the window $title within 5 s")
    }

    /**
     * The bounds of a button with [text] on one of Kestrel's touchable overlays, such as the confirmation card, as
     * another accessibility client sees it.
     */
    fun overlayButton(text: String): Rect? = overlayNode(text) { it.isClickable }?.let { Rect().also(it::getBoundsInScreen) }

    /** Whether one of Kestrel's touchable overlays shows [text] as a whole text, such as a card's question. */
    fun overlayShows(text: String): Boolean = overlayNode(text) { true } != null

    /**
     * Overlays that take no touches are not in the list of interactive windows. Android's search also finds texts that
     * only contain [text], such as a question that contains a button's label, so only a whole text counts; platform
     * buttons show their text in capitals.
     */
    private fun overlayNode(text: String, accept: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        for (window in automation.windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            window.root?.findAccessibilityNodeInfosByText(text)
                ?.firstOrNull { accept(it) && it.text?.toString().equals(text, ignoreCase = true) }
                ?.let { return it }
        }
        return null
    }

    /** Waits until an element that matches [predicate] is on the screen. */
    suspend fun awaitElement(timeoutMillis: Long = 5_000, predicate: (ScreenElement) -> Boolean): ScreenElement {
        var labels: List<String?> = emptyList()
        return withTimeoutOrNull(timeoutMillis) {
            var found: ScreenElement? = null
            while (found == null) {
                val elements = session.snapshots.current().snapshot.elements
                labels = elements.map { it.label }
                found = elements.firstOrNull(predicate)
                if (found == null) delay(100)
            }
            found
        } ?: throw AssertionError("No matching element appeared within $timeoutMillis ms. The screen showed $labels")
    }

    companion object {
        const val FIXTURES = "dev.kestrel.testapp"
        const val KESTREL = "dev.kestrel"
        private const val SERVICE = "dev.kestrel/dev.kestrel.app.KestrelAccessibilityService"

        // Connecting UiAutomation without this flag would turn off every accessibility service, Kestrel's included.
        private val automation: UiAutomation by lazy {
            InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).apply {
                serviceInfo = serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            }
        }
    }
}
```

Replace the whole content of `app/src/androidTest/kotlin/dev/kestrel/app/screen/TestFinger.kt` with:

```kotlin
package dev.kestrel.app.screen

import android.app.UiAutomation
import android.hardware.input.InputManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay

/**
 * A virtual touchscreen, made with Android's `uinput` shell tool, that touches the screen like the owner's finger.
 * Unlike `input tap`, its touches come from a touchscreen device and pass through Android's accessibility input
 * filter. The commands use numbers rather than names, because the tool on Android 13 reads only numbers.
 */
class TestFinger(private val automation: UiAutomation) : AutoCloseable {
    // toolMessages() reports only the log lines that the tool wrote after this time.
    private val startMillis = System.currentTimeMillis()

    // The tool's output, its input and its errors.
    private val pipes = automation.executeShellCommandRwe("uinput -")
    private val commands = ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).bufferedWriter()
    private var touches = 0

    init {
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val screen = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            send(register(screen.width() - 1, screen.height() - 1))
            val input = context.getSystemService(InputManager::class.java)
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (input.inputDeviceIds.none { input.getInputDevice(it)?.name == NAME }) {
                check(SystemClock.uptimeMillis() < deadline) {
                    "The virtual touchscreen did not appear within 5 s. The uinput tool said: ${toolMessages()}"
                }
                Thread.sleep(50)
            }
        } catch (e: Throwable) {
            try {
                close()
            } catch (closeError: Throwable) {
                e.addSuppressed(closeError)
            }
            throw e
        }
    }

    /** Touches the screen at ([x], [y]) for [holdMillis], then lifts the finger. */
    suspend fun tap(x: Int, y: Int, holdMillis: Long = 50) {
        touches++
        inject(EV_ABS, ABS_MT_SLOT, 0, EV_ABS, ABS_MT_TRACKING_ID, touches, EV_ABS, ABS_MT_POSITION_X, x, EV_ABS, ABS_MT_POSITION_Y, y,
            EV_ABS, ABS_MT_PRESSURE, 100, EV_KEY, BTN_TOUCH, 1, EV_SYN, SYN_REPORT, 0)
        delay(holdMillis)
        inject(EV_ABS, ABS_MT_TRACKING_ID, -1, EV_KEY, BTN_TOUCH, 0, EV_SYN, SYN_REPORT, 0)
        delay(LIFT_MILLIS)
    }

    /** Removes the virtual touchscreen: the tool removes its device when its input ends. */
    override fun close() {
        try {
            commands.close()
        } finally {
            pipes[0].close()
            pipes[2].close()
        }
    }

    /**
     * What the tool has said so far, without waiting for more. On Android 13 as on 17, the tool writes its errors to
     * Android's log and only some of them to its output, so this reads its output, its error output and its log lines
     * since this finger was made, without the log's stack frames. When they cannot be read, it says so instead.
     */
    private fun toolMessages(): String = try {
        val output = listOf(pipes[0], pipes[2]).map { pipe ->
            ParcelFileDescriptor.AutoCloseInputStream(pipe.dup()).use { String(it.readNBytes(it.available())).trim() }
        }
        val since = "${startMillis / 1000}.${(startMillis % 1000).toString().padStart(3, '0')}"
        val command = "logcat -d -T $since -s UINPUT:* UinputEvent:* UinputDevice:*"
        val log = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader()
            .use { it.readLines() }
            .filter { it.isNotBlank() && !it.startsWith("-") && "\tat " !in it }
            .takeLast(LOG_LINES)
        (output + log).filter { it.isNotEmpty() }.joinToString("\n").ifEmpty { "nothing" }
    } catch (e: Exception) {
        "nothing that could be read (${e.javaClass.name})"
    }

    private fun inject(vararg events: Int) = send("""{"id":1,"command":"inject","events":[${events.joinToString(",")}]}""")

    private fun send(command: String) {
        commands.write(command)
        commands.newLine()
        commands.flush()
    }

    private companion object {
        const val NAME = "Kestrel test finger"
        const val LIFT_MILLIS = 50L
        const val LOG_LINES = 10

        // Linux input event codes, from linux/input-event-codes.h and linux/uinput.h.
        const val EV_SYN = 0
        const val EV_KEY = 1
        const val EV_ABS = 3
        const val SYN_REPORT = 0
        const val BTN_TOUCH = 0x14a
        const val ABS_MT_SLOT = 0x2f
        const val ABS_MT_POSITION_X = 0x35
        const val ABS_MT_POSITION_Y = 0x36
        const val ABS_MT_TRACKING_ID = 0x39
        const val ABS_MT_PRESSURE = 0x3a
        const val UI_SET_EVBIT = 100
        const val UI_SET_KEYBIT = 101
        const val UI_SET_ABSBIT = 103
        const val UI_SET_PROPBIT = 110
        const val INPUT_PROP_DIRECT = 1

        fun axis(code: Int, maximum: Int) =
            """{"code":$code,"info":{"value":0,"minimum":0,"maximum":$maximum,"fuzz":0,"flat":0,"resolution":0}}"""

        fun register(maxX: Int, maxY: Int): String {
            val configuration = listOf(
                """{"type":$UI_SET_EVBIT,"data":[$EV_KEY,$EV_ABS]}""",
                """{"type":$UI_SET_KEYBIT,"data":[$BTN_TOUCH]}""",
                """{"type":$UI_SET_ABSBIT,"data":[$ABS_MT_SLOT,$ABS_MT_POSITION_X,$ABS_MT_POSITION_Y,$ABS_MT_TRACKING_ID,$ABS_MT_PRESSURE]}""",
                """{"type":$UI_SET_PROPBIT,"data":[$INPUT_PROP_DIRECT]}""",
            )
            val axes = listOf(
                axis(ABS_MT_SLOT, 9),
                axis(ABS_MT_POSITION_X, maxX),
                axis(ABS_MT_POSITION_Y, maxY),
                axis(ABS_MT_TRACKING_ID, 65535),
                axis(ABS_MT_PRESSURE, 255),
            )
            return """{"id":1,"command":"register","name":"$NAME","vid":6353,"pid":43981,"bus":"usb",""" +
                """"configuration":[${configuration.joinToString(",")}],"abs_info":[${axes.joinToString(",")}]}"""
        }
    }
}
```

Replace the whole content of `app/src/androidTest/kotlin/dev/kestrel/app/screen/GridBarAndCardTest.kt` with:

```kotlin
package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.WindowSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GridBarAndCardTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val session get() = screen.session

    private suspend fun eventually(timeoutMillis: Long = 5_000, check: suspend () -> Boolean) = withTimeout(timeoutMillis) {
        while (!check()) delay(100)
    }

    @Test
    fun theGridCoversTheScreenAndLetsTouchesThrough() = runBlocking {
        screen.open("GesturesActivity", "Gestures fixture")
        val pad = screen.awaitElement { it.label == "Gesture pad" }.bounds

        session.showGrid()

        val grid = session.grid!!
        assertEquals(4, grid.columns)
        eventually { screen.showsWindow("Kestrel grid") }
        val cell = (1..grid.cellCount).map(grid::cell).first { pad.intersect(it) == it }
        session.actions.tapAt(cell.centerX, cell.centerY)
        screen.awaitElement { it.label == "Tap" }
        session.hideGrid()
        eventually { !screen.showsWindow("Kestrel grid") }
    }

    @Test
    fun theFeedbackBarShowsTextAndFadesAway() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        session.showFeedback("Listening")

        assertEquals("Listening", session.feedbackText)
        eventually { screen.showsWindow("Kestrel feedback bar") }
        eventually(timeoutMillis = 8_000) { session.feedbackText == null && !screen.showsWindow("Kestrel feedback bar") }
    }

    @Test
    fun theCardSaysTrueWhenTheOwnerTapsConfirm() = runBlocking { answerWith("Confirm", expected = true) }

    @Test
    fun theCardSaysFalseWhenTheOwnerTapsCancel() = runBlocking { answerWith("Cancel", expected = false) }

    @Test
    fun cancellingTheQuestionRemovesTheCard() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val answer = async { session.confirm("Delete the draft?") }
        eventually { screen.showsWindow("Kestrel confirmation") }

        answer.cancel()

        eventually { !screen.showsWindow("Kestrel confirmation") }
    }

    @Test
    fun aNewQuestionAnswersTheOldOneWithFalse() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val first = async { session.confirm("Send a test message?") }
        eventually { screen.overlayButton("Confirm") != null }

        val second = async { session.confirm("Delete the draft?") }

        assertFalse(withTimeout(5_000) { first.await() })
        // Only the second card is left, so the tap reaches its Confirm button.
        eventually { screen.overlayShows("Delete the draft?") && !screen.overlayShows("Send a test message?") }
        val confirm = screen.overlayButton("Confirm")!!
        screen.shell("input tap ${confirm.centerX()} ${confirm.centerY()}")
        assertTrue(withTimeout(5_000) { second.await() })
        eventually { !screen.showsWindow("Kestrel confirmation") }
    }

    @Test
    fun closingTheSessionAnswersFalse() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val answer = async { session.confirm("Send a test message?") }
        eventually { screen.overlayButton("Confirm") != null }

        // As the service does when it disconnects. The rule's next start closes this session again.
        withContext(Dispatchers.Main) { session.close() }

        assertFalse(withTimeout(5_000) { answer.await() })
        eventually { !screen.showsWindow("Kestrel confirmation") }
    }

    @Test
    fun snapshotsLeaveTheCardOut() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val answer = async { session.confirm("Send a test message?") }
        try {
            eventually { screen.overlayButton("Confirm") != null }

            // The card is now in Android's list of interactive windows. A snapshot read after this point must still
            // leave it out, or Kestrel could tap its own Confirm button.
            session.snapshots.invalidate()
            val snapshot = session.snapshots.current().snapshot
            assertEquals(emptyList<WindowSummary>(), snapshot.windows.filter { it.packageName == ScreenServiceRule.KESTREL })
            assertEquals(emptyList<ScreenElement>(), snapshot.elements.filter { it.packageName == ScreenServiceRule.KESTREL })
        } finally {
            answer.cancel()
        }
    }

    private suspend fun CoroutineScope.answerWith(button: String, expected: Boolean) {
        screen.open("ViewsActivity", "Views fixture")
        // The question contains both buttons' labels, so the tests also check that the rule finds the buttons.
        val answer = async { session.confirm("Confirm or cancel a test message?") }
        val bounds = withTimeout(5_000) {
            var found = screen.overlayButton(button)
            while (found == null) {
                delay(100)
                found = screen.overlayButton(button)
            }
            found
        }

        screen.shell("input tap ${bounds.centerX()} ${bounds.centerY()}")

        assertEquals(expected, withTimeout(5_000) { answer.await() })
        eventually { !screen.showsWindow("Kestrel confirmation") }
    }
}
```

- [ ] **Step 3: Update the README**

In `README.md`, replace the paragraph that starts with "The device tests of `:app` use the fixture screens" with:

````markdown
The device tests of `:app` use the fixture screens of `:testapp`, a separate app that Gradle installs before the tests. Each test that uses the screen turns Kestrel's accessibility service off and on again. The tests replace the whole list of enabled accessibility services, so other services are off while they run, and Android clears the shortcuts that start those services. `connected-test.ps1` puts the accessibility settings back when the tests end. If you stop the tests with Ctrl+C, Windows asks "Terminate batch job (Y/N)?": answer it, and the script puts the settings back before it ends. After the tests, Gradle uninstalls Kestrel, which also switches its service off. Some tests touch the screen through a virtual touchscreen that Android's `uinput` tool creates: touches from `adb shell input` skip Android's accessibility input filter, while a virtual touchscreen behaves like a finger.
````

Replace the paragraph that starts with "`start-emulator.ps1` starts the emulator with software rendering" with:

````markdown
`start-emulator.ps1` starts the emulator with software rendering (`-gpu swiftshader`), because with the computer's graphics card the emulator slowed down after a few hours of tests. Android inside the emulator can still slow down after many hours of tests. If the device tests become slow, or Android restarts while they run, restart the emulator with a cold boot: run `& $adb -s $serial emu kill`, wait until the emulator has closed, and then run `$serial = scripts\start-emulator.ps1 -ColdBoot`. A normal start would bring the slow state back from the emulator's Quick Boot snapshot, and restarting Android inside the emulator is not enough.
````

- [ ] **Step 4: Check the tools**

The device guard refuses a serial that is not connected after its checks:

```powershell
$watch = [Diagnostics.Stopwatch]::StartNew()
try { scripts\connected-test.ps1 -Serial emulator-9999 :app:connectedDebugAndroidTest } catch { $_.Exception.Message }
$watch.Elapsed.TotalSeconds
```

Expected: the message `Device emulator-9999 is not connected.` and about 4 seconds.

Windows PowerShell 5.1 gives the same answer:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -Command "try { scripts\connected-test.ps1 -Serial emulator-9999 :app:connectedDebugAndroidTest } catch { `$_.Exception.Message }"
```

Expected: the message `Device emulator-9999 is not connected.`, again after about 4 seconds.

A setting can be empty, which is different from no answer, and a value may contain a single quote:

```powershell
. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb
& $adb -s <serial> shell settings put secure kestrel_probe_empty "''"
"[$(Get-SecureSetting -Serial <serial> -Name kestrel_probe_empty)]"
& $adb -s <serial> shell settings delete secure kestrel_probe_empty
"[$(Get-SecureSetting -Serial <serial> -Name kestrel_probe_empty)]"
Set-SecureSetting -Serial <serial> -Name kestrel_probe_quote -Value "it's"
Get-SecureSetting -Serial <serial> -Name kestrel_probe_quote
& $adb -s <serial> shell settings delete secure kestrel_probe_quote
```

Expected: `[]`, then `[null]`, then `True` and `it's`.

A failed query is not an empty value either. Android rejects a query without a name, and `Get-SecureSetting` gives up after it has asked three times:

```powershell
$watch = [Diagnostics.Stopwatch]::StartNew()
try { Get-SecureSetting -Serial <serial> -Name '' } catch { $_.Exception.Message }
$watch.Elapsed.TotalSeconds
```

Expected: the message `Could not run 'settings get secure ' on <serial>: Bad arguments`, and about 4 seconds.

The device tests put back the accessibility settings. Turn on a harmless system service and give it the accessibility button, run all device tests, and compare the settings afterwards (in the same PowerShell window):

```powershell
$probe = 'com.android.systemui.accessibility.accessibilitymenu/com.android.systemui.accessibility.accessibilitymenu.AccessibilityMenuService'
& $adb -s <serial> shell settings put secure enabled_accessibility_services $probe
& $adb -s <serial> shell settings put secure accessibility_button_targets $probe
Start-Sleep -Seconds 3
$before = Get-AccessibilitySettings -Serial <serial>
scripts\connected-test.ps1 -Serial <serial> :core:privacy:connectedDebugAndroidTest :app:connectedDebugAndroidTest
$after = Get-AccessibilitySettings -Serial <serial>
@($before.Keys) + @($after.Keys) | Sort-Object -Unique | Where-Object { $before[$_] -cne $after[$_] }
$before['accessibility_button_targets'] -eq $probe
& $adb -s <serial> shell settings delete secure enabled_accessibility_services
& $adb -s <serial> shell settings delete secure accessibility_button_targets
```

Expected: `BUILD SUCCESSFUL` with 7 tests in `:core:privacy` and 66 in `:app`; the comparison prints no setting name; and the last check prints `True`. While the tests run, Android clears the probe's button, so without the restore the comparison would print `accessibility_button_targets`.

- [ ] **Step 5: Commit**

```powershell
git add scripts README.md app/src/androidTest
git commit -m "test: restore the accessibility services after the device tests, and harden the test tools" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 2: Test hooks that always answer and handle large snapshots

**Files:**
- Modify: `app/src/debug/kotlin/dev/kestrel/app/debug/TestHookReceiver.kt` (always answers, and always finishes the broadcast)
- Modify: `app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt` (`SNAPSHOT` writes a file)
- Modify: `scripts/hook.ps1` (needs PowerShell 7.3; reads the snapshot file with `run-as`)
- Modify: `app/src/androidTest/kotlin/dev/kestrel/app/debug/TestHookTest.kt`
- Modify: `README.md`

**Interfaces:**
- Consumes: Task 1's `ScreenServiceRule`.
- Produces: the `SNAPSHOT` hook answers with a path relative to Kestrel's data folder, `cache/hook-snapshot.json`; `scripts\hook.ps1 snapshot` reads the file and deletes it. A hook command that throws, even with an `Error`, answers with result code 1 and a message without the error's message.

Closes (12a), (12b), (12e) and (12f).

A broadcast's answer must stay under Android's limit of about 1 MB, and a busy screen's snapshot can be larger, so the hook writes the snapshot to Kestrel's cache and answers with the file's path, which only a debug build lets adb read with `run-as`. The receiver now turns any `Throwable` into an answer and finishes the broadcast in `finally`; without an answer, adb would wait 60 s for nothing. Windows PowerShell 5.1 drops double quotes from the arguments of other programs, which would change the text that `type` types, so `hook.ps1` requires PowerShell 7.3.

- [ ] **Step 1: Write the new and changed tests**

Replace the whole content of `app/src/androidTest/kotlin/dev/kestrel/app/debug/TestHookTest.kt` with:

```kotlin
package dev.kestrel.app.debug

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.screen.model.ScreenJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TestHookTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph = (context.applicationContext as KestrelApplication).graph

    @Test
    fun adbReadsTheState() {
        assertEquals("screen=on", answer(hook("STATE")).substringBefore(' '))
    }

    @Test
    fun adbSwitchesTestMode() = runBlocking {
        val original = graph.settings.get(SettingKeys.SAFETY)
        try {
            assertEquals("testMode=off", answer(hook("TEST_MODE --ez on false")))
            assertFalse(graph.settings.get(SettingKeys.SAFETY).testMode)
            assertEquals("testMode=on", answer(hook("TEST_MODE --ez on true")))
            assertTrue(graph.settings.get(SettingKeys.SAFETY).testMode)
        } finally {
            graph.settings.set(SettingKeys.SAFETY, original)
        }
    }

    @Test
    fun adbReadsTheScreen() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        // The answer is the path of the snapshot's file in Kestrel's data folder, which hook.ps1 reads with run-as.
        val file = File(context.dataDir, answer(hook("SNAPSHOT")))

        try {
            assertTrue(ScreenJson.decode(file.readText()).elements.any { it.label == "SEND" })
        } finally {
            file.delete()
        }
    }

    @Test
    fun adbScrollsAList() = runBlocking {
        screen.open("ScrollActivity", "Scroll fixture")

        assertEquals("done: action", answer(hook("SCROLL --es direction down")))

        screen.awaitElement { it.label == "Row 25" }
        Unit
    }

    @Test
    fun adbAcceptsNamesWithHyphens() {
        // Play/pause changes nothing on the screen while no media plays, so later tests find their fixtures.
        assertEquals("done: global_action", answer(hook("GLOBAL --es action play-pause")))
    }

    @Test
    fun screenCommandsSayWhenTheServiceIsOff() = runBlocking {
        screen.shell("settings delete secure enabled_accessibility_services")
        withTimeout(10_000) { graph.screen.first { it == null } }

        val reply = hook("SNAPSHOT")

        assertTrue(reply, reply.contains("result=1, data=\"error: Kestrel's accessibility service is off\""))
    }

    @Test
    fun adbShowsNumbersAndTapsOne() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val send = screen.awaitElement { it.label == "SEND" && it.clickable }
        answer(hook("OVERLAY --es show numbers"))
        val number = screen.session.numberBadges.first { it.key == send.key }.number

        assertEquals("done: action", answer(hook("TAP --ei number $number")))

        screen.awaitElement { it.label == "Clicked Send" }
        Unit
    }

    @Test
    fun aFailedCommandSaysWhy() {
        val reply = hook("TYPE --es text hello")

        assertTrue(reply, reply.contains("result=1, data=\"error: add --es key"))
    }

    @Test
    fun anotherAppCannotSendCommands() = runBlocking {
        screen.open("HookProbeActivity", "Hook probe")

        screen.awaitElement { it.label == "Probe refused" }
        Unit
    }

    /** Sends a command the way scripts/hook.ps1 does. */
    private fun hook(command: String): String =
        screen.shell("am broadcast -n ${ScreenServiceRule.KESTREL}/dev.kestrel.app.debug.TestHookReceiver -a dev.kestrel.hook.$command")

    /** The data of a successful answer. */
    private fun answer(reply: String): String {
        assertTrue(reply, reply.contains("result=-1, data=\""))
        return reply.substringAfter("data=\"").substringBeforeLast('"')
    }
}
```

- [ ] **Step 2: Run the tests and watch one fail**

```powershell
scripts\connected-test.ps1 -Serial <serial> :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.debug.TestHookTest'
```

Expected: `adbReadsTheScreen` fails, because the old hook answers with the JSON itself, which is not a file name. The three new tests, `adbScrollsAList`, `adbAcceptsNamesWithHyphens` and `screenCommandsSayWhenTheServiceIsOff`, cover commands that plan 1B built without device tests, so they may already pass.

- [ ] **Step 3: Change the hooks**

Replace the whole content of `app/src/debug/kotlin/dev/kestrel/app/debug/TestHookReceiver.kt` with:

```kotlin
package dev.kestrel.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.kestrel.app.KestrelApplication
import dev.kestrel.privacy.logs.describeWithoutMessages
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Receives test-hook commands from adb, in debug builds only (design spec, section 16.1). Android delivers them only
 * from callers that hold `android.permission.DUMP`, which in practice means `adb shell`; `scripts/hook.ps1` sends
 * them. The answer is the broadcast's result code and data, see [HookReply].
 */
class TestHookReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as KestrelApplication).graph
        val pending = goAsync()
        graph.scope.launch {
            try {
                val reply = try {
                    withTimeout(TIMEOUT_MILLIS) { TestHooks(graph, context.cacheDir).run(intent) }
                } catch (error: Throwable) {
                    // Errors too, such as a StackOverflowError: without an answer, adb would wait 60 s for nothing.
                    HookReply.failed("the command failed: ${error.describeWithoutMessages()}")
                }
                pending.setResult(reply.code, reply.text, null)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        // Android waits 60 s for a background broadcast, and an action waits at most 3 s for the screen to settle.
        const val TIMEOUT_MILLIS = 20_000L
    }
}
```

Replace the whole content of `app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt` with:

```kotlin
package dev.kestrel.app.debug

import android.app.Activity
import android.content.Intent
import dev.kestrel.app.AppGraph
import dev.kestrel.app.SettingKeys
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.GlobalAction
import dev.kestrel.screen.act.ScrollDirection
import dev.kestrel.screen.model.ScreenJson
import dev.kestrel.screen.overlay.NumbersMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A test hook's answer, which becomes the broadcast's result code and data. */
internal data class HookReply(val code: Int, val text: String) {
    companion object {
        /** The result code of a command that failed. A command that never ran keeps the initial code, 0. */
        const val FAILED = 1

        fun ok(text: String) = HookReply(Activity.RESULT_OK, text)

        fun failed(message: String) = HookReply(FAILED, "error: $message")
    }
}

/**
 * Runs the test-hook commands (design spec, section 16.1). The intent's action is [ACTION_PREFIX] followed by the
 * command, and its extras are the arguments; `scripts/hook.ps1` lists them.
 */
internal class TestHooks(private val graph: AppGraph, private val cacheDir: File) {
    suspend fun run(intent: Intent): HookReply = try {
        HookReply.ok(answer(intent))
    } catch (error: HookError) {
        HookReply.failed(error.message)
    }

    private suspend fun answer(intent: Intent): String {
        val command = intent.action.orEmpty().removePrefix(ACTION_PREFIX)
        if (command == "STATE") return state()
        if (command == "TEST_MODE") return testMode(intent)
        val session = graph.screen.value ?: throw HookError("Kestrel's accessibility service is off")
        val actions = session.actions
        return when (command) {
            "SNAPSHOT" -> save(ScreenJson.encode(session.snapshots.current().snapshot))
            "TAP" -> done(tap(session, intent))
            "TYPE" -> done(actions.setText(target(session, intent), intent.requiredText()))
            "SCROLL" -> done(actions.scroll(intent.choice<ScrollDirection>(EXTRA_DIRECTION), optionalTarget(session, intent)))
            "GLOBAL" -> done(actions.global(intent.choice<GlobalAction>(EXTRA_ACTION)))
            "OVERLAY" -> {
                overlay(session, intent)
                state()
            }
            else -> throw HookError("unknown command '$command'")
        }
    }

    /**
     * Writes a snapshot to Kestrel's cache and answers with the file's path in Kestrel's data folder, because a
     * broadcast's answer must stay under Android's limit of about 1 MB. `scripts/hook.ps1` reads the file with
     * `run-as` and deletes it.
     */
    private suspend fun save(json: String): String = withContext(Dispatchers.IO) {
        File(cacheDir, SNAPSHOT_FILE).writeText(json)
        "${cacheDir.name}/$SNAPSHOT_FILE"
    }

    private suspend fun state(): String {
        val testMode = onOff(graph.settings.get(SettingKeys.SAFETY).testMode)
        val session = graph.screen.value ?: return "screen=off testMode=$testMode"
        return withContext(Dispatchers.Main) {
            "screen=on testMode=$testMode numbers=${session.numberBadges.size} labels=${onOff(session.labelsShowing)} " +
                "grid=${onOff(session.grid != null)} feedback=${onOff(session.feedbackText != null)}"
        }
    }

    private suspend fun testMode(intent: Intent): String {
        if (!intent.hasExtra(EXTRA_ON)) throw HookError("add --ez on true or --ez on false")
        val on = intent.getBooleanExtra(EXTRA_ON, true)
        graph.settings.set(SettingKeys.SAFETY, graph.settings.get(SettingKeys.SAFETY).copy(testMode = on))
        return "testMode=${onOff(on)}"
    }

    private suspend fun tap(session: ScreenSession, intent: Intent): ActionResult {
        val long = intent.getBooleanExtra(EXTRA_LONG, false)
        if (intent.hasExtra(EXTRA_X) || intent.hasExtra(EXTRA_Y)) {
            if (!intent.hasExtra(EXTRA_X) || !intent.hasExtra(EXTRA_Y)) throw HookError("add both --ei x and --ei y")
            val x = intent.getIntExtra(EXTRA_X, 0)
            val y = intent.getIntExtra(EXTRA_Y, 0)
            return if (long) session.actions.longPressAt(x, y) else session.actions.tapAt(x, y)
        }
        val key = target(session, intent)
        return if (long) session.actions.longPress(key) else session.actions.tap(key)
    }

    private suspend fun overlay(session: ScreenSession, intent: Intent) {
        when (intent.getStringExtra(EXTRA_SHOW)) {
            "numbers" -> session.showNumbers(NumbersMode.ALL)
            "unlabeled" -> session.showNumbers(NumbersMode.UNLABELED)
            "labels" -> session.showLabels()
            "grid" -> session.showGrid()
            "feedback" -> session.showFeedback(intent.requiredText())
            "none" -> {
                session.hideNumbers()
                session.hideLabels()
                session.hideGrid()
            }
            else -> throw HookError("add --es show with numbers, unlabeled, labels, grid, feedback or none")
        }
    }

    /** The element that the extras name: by its key, or by its number while the numbers show. */
    private suspend fun target(session: ScreenSession, intent: Intent): String =
        optionalTarget(session, intent) ?: throw HookError("add --es key <element key> or --ei number <number>")

    private suspend fun optionalTarget(session: ScreenSession, intent: Intent): String? {
        intent.getStringExtra(EXTRA_KEY)?.let { return it }
        if (!intent.hasExtra(EXTRA_NUMBER)) return null
        val number = intent.getIntExtra(EXTRA_NUMBER, 0)
        val badges = withContext(Dispatchers.Main) { session.numberBadges }
        return badges.firstOrNull { it.number == number }?.key ?: throw HookError("no element shows the number $number")
    }

    private fun done(result: ActionResult): String = when (result) {
        is ActionResult.Done -> "done: ${result.method.name.lowercase()}"
        is ActionResult.Failed -> throw HookError(result.reason)
    }

    private fun Intent.requiredText(): String = getStringExtra(EXTRA_TEXT) ?: throw HookError("add --es text <text>")

    private inline fun <reified T : Enum<T>> Intent.choice(name: String): T {
        val value = getStringExtra(name)?.replace('-', '_')
        return enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw HookError("add --es $name with one of ${enumValues<T>().joinToString { it.name.lowercase() }}")
    }

    private fun onOff(value: Boolean) = if (value) "on" else "off"

    private class HookError(override val message: String) : Exception(message)

    companion object {
        const val ACTION_PREFIX = "dev.kestrel.hook."
        const val SNAPSHOT_FILE = "hook-snapshot.json"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_X = "x"
        private const val EXTRA_Y = "y"
        private const val EXTRA_LONG = "long"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_DIRECTION = "direction"
        private const val EXTRA_ACTION = "action"
        private const val EXTRA_SHOW = "show"
        private const val EXTRA_ON = "on"
    }
}
```

Replace the whole content of `scripts/hook.ps1` with:

```powershell
# Sends a test-hook command to Kestrel's debug build on a test device and prints the answer (design spec,
# section 16.1). Commands and their options:
#   state                                   whether the screen layer runs, test mode and the overlays
#   test-mode -On true|false                switches test mode
#   snapshot [-Out file.json]               what Kestrel reads on the screen, as JSON
#   overlay -Show numbers|unlabeled|labels|grid|none
#   overlay -Show feedback -Text 'text'     shows the feedback bar
#   tap -Key key | -Number n | -X x -Y y [-Long]
#   type -Key key | -Number n -Text 'text'
#   scroll -Direction up|down|left|right [-Key key | -Number n]
#   global -Action back|home|recent-apps|notifications|quick-settings|lock-screen|screenshot|power-menu|split-screen|play-pause
# -Number picks an element by the number that the numbers overlay shows on it.
# Example: scripts\hook.ps1 -Serial emulator-5554 tap -Number 3
# Windows PowerShell 5.1 drops double quotes from the arguments of other programs, which would change the text that
# 'type' types, so the script needs PowerShell 7.3 or newer.
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('state', 'test-mode', 'snapshot', 'overlay', 'tap', 'type', 'scroll', 'global')][string]$Command,
    [string]$Key,
    [int]$Number,
    [int]$X = -1,
    [int]$Y = -1,
    [switch]$Long,
    [string]$Text,
    [string]$Direction,
    [string]$Action,
    [string]$Show,
    [ValidateSet('true', 'false')][string]$On,
    [string]$Out
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial

# The device's shell reads the command line, so every text value is quoted for it.
function ConvertTo-ShellText([string]$Value) { "'" + $Value.Replace("'", "'\''") + "'" }

# Runs adb and reads its output as UTF-8, because screen text can be in any language.
function Invoke-Adb {
    $consoleEncoding = [Console]::OutputEncoding
    [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
    try {
        (& (Get-Adb) -s $Serial @args) -join "`n"
    } finally {
        [Console]::OutputEncoding = $consoleEncoding
    }
}

$extras = @()
if ($Key) { $extras += "--es key $(ConvertTo-ShellText $Key)" }
if ($PSBoundParameters.ContainsKey('Number')) { $extras += "--ei number $Number" }
if ($X -ge 0) { $extras += "--ei x $X" }
if ($Y -ge 0) { $extras += "--ei y $Y" }
if ($Long) { $extras += '--ez long true' }
if ($PSBoundParameters.ContainsKey('Text')) { $extras += "--es text $(ConvertTo-ShellText $Text)" }
if ($Direction) { $extras += "--es direction $(ConvertTo-ShellText $Direction)" }
if ($Action) { $extras += "--es action $(ConvertTo-ShellText $Action)" }
if ($Show) { $extras += "--es show $(ConvertTo-ShellText $Show)" }
if ($On) { $extras += "--ez on $On" }

$hookAction = 'dev.kestrel.hook.' + $Command.ToUpperInvariant().Replace('-', '_')
$broadcast = "am broadcast -n dev.kestrel/dev.kestrel.app.debug.TestHookReceiver -a $hookAction $($extras -join ' ')"
$output = Invoke-Adb shell $broadcast

$match = [regex]::Match($output, '(?s)Broadcast completed: result=(-?\d+)(?:, data="(.*)")?\s*$')
if (-not $match.Success) { throw "Unexpected answer from the device:`n$output" }
$data = $match.Groups[2].Value
switch ([int]$match.Groups[1].Value) {
    -1 {
        if ($Command -eq 'snapshot') {
            # A snapshot can be larger than a broadcast's answer, so Kestrel answers with the path of a file in its own
            # storage, which only a debug build lets adb read.
            $file = $data
            $data = Invoke-Adb exec-out run-as dev.kestrel cat $file
            Invoke-Adb shell run-as dev.kestrel rm $file | Out-Null
        }
        if ($Out) {
            $path = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Out)
            [IO.File]::WriteAllText($path, $data, [Text.UTF8Encoding]::new($false))
            "Saved to $path"
        } else {
            $data
        }
    }
    0 { throw 'Kestrel did not answer. Install a debug build with scripts\install.ps1.' }
    default { throw $data }
}
```

In `README.md`, replace the paragraph that starts with "Debug builds contain test hooks that only adb can use" with:

````markdown
Debug builds contain test hooks that only adb can use (design spec, section 16.1). `scripts\hook.ps1` sends a command and prints the answer, and the comment at the top of the script lists every command. It needs PowerShell 7.3 or newer (`pwsh`), because Windows PowerShell 5.1 drops double quotes from the text that it passes to adb. The screen commands need Kestrel's accessibility service, which you can turn on in Settings > Accessibility, or with adb (this replaces the list of enabled services):
````

- [ ] **Step 4: Run the tests again**

Run the command of Step 2 again. Expected: `BUILD SUCCESSFUL`, 9 tests.

- [ ] **Step 5: Check the script by hand**

```powershell
scripts\install.ps1 -Serial <serial>
& (Get-Adb) -s <serial> shell settings put secure enabled_accessibility_services dev.kestrel/dev.kestrel.app.KestrelAccessibilityService
Start-Sleep -Seconds 4
scripts\hook.ps1 -Serial <serial> state
scripts\hook.ps1 -Serial <serial> snapshot -Out $env:TEMP\screen.json
& (Get-Adb) -s <serial> shell run-as dev.kestrel ls cache
powershell.exe -NoProfile -File scripts\hook.ps1 -Serial <serial> state
& (Get-Adb) -s <serial> shell settings delete secure enabled_accessibility_services
Remove-Item $env:TEMP\screen.json
```

Expected: `state` prints `screen=on testMode=on numbers=0 labels=off grid=off feedback=off`; `snapshot` prints `Saved to ...\screen.json`; the `ls` of Kestrel's cache no longer lists `hook-snapshot.json`; and Windows PowerShell 5.1 refuses the script with a message that it requires version 7.3.

- [ ] **Step 6: Commit**

```powershell
git add app/src/debug app/src/androidTest scripts/hook.ps1 README.md
git commit -m "test: make the adb test hooks robust" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: Crash records without messages, and a purge that survives failures

**Files:**
- Create: `core/privacy/src/main/kotlin/dev/kestrel/privacy/logs/MainThreadErrors.kt`
- Modify: `core/privacy/src/main/kotlin/dev/kestrel/privacy/logs/ErrorSummary.kt` (one entry per cause; copies keep the original class name)
- Modify: `core/privacy/src/main/kotlin/dev/kestrel/privacy/logs/MessageFreeCrashHandler.kt`
- Modify: `core/privacy/src/main/kotlin/dev/kestrel/privacy/logs/LogStore.kt` (`keepPurging`)
- Modify: `core/privacy/src/test/kotlin/dev/kestrel/privacy/logs/ErrorSummaryTest.kt`
- Modify: `core/privacy/src/test/kotlin/dev/kestrel/privacy/logs/LogStoreTest.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/KestrelApplication.kt` (installs both in `attachBaseContext`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (one logcat entry per cause)
- Modify: `app/src/main/kotlin/dev/kestrel/app/KestrelAccessibilityService.kt` (uses `keepPurging`)
- Modify: `app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt` (the `CRASH` hook)
- Modify: `scripts/hook.ps1` (`crash -Text`)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 16.1)

**Interfaces:**
- Consumes: plan 1A's `withoutMessages()`, `describeWithoutMessages()` and `MessageFreeCrashHandler`.
- Produces: `fun Throwable.describeWithoutMessagesByCause(): List<String>`, one text per error of the chain of causes; `object MainThreadErrors { fun install() }`; `suspend fun LogStore.keepPurging(onFailure: (Throwable) -> Unit): Nothing`; the debug hook `CRASH --es text <text>`.

Closes (5b) and (5c), and the review finding that the crash handler must exist before Android starts the app's content providers.

Android's own pre-handler writes an uncaught error's message to the crash log before any handler that Kestrel installs can run, and a message can contain screen text. `MainThreadErrors` runs the main thread's message loop inside Kestrel's own loop, so that an error on the main thread becomes a copy without messages before it leaves Kestrel's code. Both it and the handler are installed in `attachBaseContext`, because Android starts the app's content providers between `attachBaseContext` and `onCreate`. Logcat cuts an entry after about 4 KB, so `AppGraph.errors` writes each error of the chain of causes as its own entry. A coroutine's `delay` stops counting while the phone sleeps, so `keepPurging` checks the wall clock every hour and purges once a day; a failed purge is reported and tried again at the next check.

- [ ] **Step 1: Write the failing unit tests**

Replace the whole content of `core/privacy/src/test/kotlin/dev/kestrel/privacy/logs/ErrorSummaryTest.kt` with:

```kotlin
package dev.kestrel.privacy.logs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ErrorSummaryTest {
    @Test
    fun leavesOutMessagesButKeepsClassesFramesAndCauses() {
        val error = IllegalStateException("Message from Ana: see you at 5", IOException("token=secret"))

        val text = error.describeWithoutMessages()

        assertFalse(text, text.contains("Ana"))
        assertFalse(text, text.contains("secret"))
        assertTrue(text, text.startsWith("java.lang.IllegalStateException\n    at dev.kestrel.privacy.logs.ErrorSummaryTest"))
        assertTrue(text, text.contains("Caused by: java.io.IOException"))
    }

    @Test
    fun theCopyHasNoMessagesButKeepsClassesFramesCausesAndSuppressedErrors() {
        val error = IllegalStateException("secret A", IOException("secret B"))
        error.addSuppressed(RuntimeException("secret C"))

        val copy = error.withoutMessages()

        val text = copy.stackTraceToString()
        // Android's crash report reads the message of the error and of every cause.
        for (part in listOf(copy, copy.cause!!, copy.suppressed.single())) {
            assertNull(part.toString(), part.message)
            assertNull(part.toString(), part.localizedMessage)
        }
        assertEquals(error.stackTrace.toList(), copy.stackTrace.toList())
        assertEquals("java.lang.IllegalStateException", text.lines().first())
        assertTrue(text, text.contains(error.stackTrace.first().methodName))
        assertTrue(text, text.contains("Caused by: java.io.IOException"))
        assertTrue(text, text.contains("Suppressed: java.lang.RuntimeException"))
        assertFalse(text, text.contains("secret"))
    }

    @Test(timeout = 5_000)
    fun theCopyEndsACycleOfCauses() {
        val error = IllegalStateException("secret A")
        error.initCause(IOException("secret B", error))

        val copy = error.withoutMessages()

        val chain = generateSequence(copy) { it.cause }.take(10).map { it.toString() }.toList()
        assertEquals(listOf("java.lang.IllegalStateException", "java.io.IOException"), chain)
    }

    @Test
    fun theCrashHandlerPassesOnACopyWithoutMessages() {
        var passedOn: Throwable? = null
        val handler = MessageFreeCrashHandler { _, error -> passedOn = error }

        handler.uncaughtException(Thread.currentThread(), IllegalStateException("secret A", IOException("secret B")))

        val text = passedOn!!.stackTraceToString()
        assertEquals("java.lang.IllegalStateException", text.lines().first())
        assertTrue(text, text.contains("Caused by: java.io.IOException"))
        assertFalse(text, text.contains("secret"))
    }

    @Test
    fun describesEachErrorOfTheChainSeparately() {
        val error = IllegalStateException("secret A", IOException("secret B", RuntimeException("secret C")))

        val parts = error.describeWithoutMessagesByCause()

        assertEquals(listOf("java.lang.IllegalStateException", "Caused by: java.io.IOException", "Caused by: java.lang.RuntimeException"), parts.map { it.lines().first() })
        assertFalse(parts.joinToString(), parts.any { it.contains("secret") })
        assertEquals(error.describeWithoutMessages(), parts.joinToString("\n"))
    }

    @Test
    fun aCopyOfACopyKeepsTheOriginalClassName() {
        val copy = IllegalStateException("secret").withoutMessages().withoutMessages()

        assertEquals("java.lang.IllegalStateException", copy.toString())
        assertEquals("java.lang.IllegalStateException", copy.describeWithoutMessages().lines().first())
    }

    @Test
    fun theCrashHandlerPassesOnAPlaceholderWhenTheCopyFails() {
        val passedOn = mutableListOf<Throwable>()
        val handler = MessageFreeCrashHandler { _, error -> passedOn += error }
        // The copy needs the error's stack frames, so it cannot be made. The failure is an Error, as when memory has run out.
        val error = object : IllegalStateException("secret A") {
            override fun getStackTrace(): Array<StackTraceElement> = throw StackOverflowError("secret B")
        }

        handler.uncaughtException(Thread.currentThread(), error)

        assertEquals(1, passedOn.size)
        val text = passedOn[0].stackTraceToString()
        assertNull(passedOn[0].message)
        assertFalse(text, text.contains("secret"))
        assertFalse(text, text.contains("StackOverflowError"))
    }
}
```

Replace the whole content of `core/privacy/src/test/kotlin/dev/kestrel/privacy/logs/LogStoreTest.kt` with:

```kotlin
package dev.kestrel.privacy.logs

import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.db.LogDao
import dev.kestrel.privacy.db.LogEntryRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator

class LogStoreTest {
    private val dao = FakeLogDao()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private var now = 1_000_000_000_000L
    private val store = LogStore(dao, AesGcmFieldCipher { key }, clock = { now }, dispatcher = Dispatchers.Unconfined)

    @Test
    fun readsActivityBackNewestFirst() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "first"))
        now += 1_000
        store.append(ActivityEvent(ActivityKind.ACTION, "second", taskId = "task-1"))

        val entries = store.activity()

        assertEquals(listOf("second", "first"), entries.map { it.event.text })
        assertEquals(ActivityEvent(ActivityKind.ACTION, "second", taskId = "task-1"), entries.first().event)
        assertEquals(now, entries.first().timestampMillis)
    }

    @Test
    fun storesPayloadsEncrypted() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "a secret note"))

        val stored = dao.rows.single().payload.decodeToString(throwOnInvalidSequence = false)

        assertFalse(stored.contains("secret"))
    }

    @Test
    fun keepsTheTwoLogsApart() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "note"))
        store.append(networkEvent("api.example.com"))

        assertEquals(listOf("note"), store.activity().map { it.event.text })
        assertEquals(listOf("api.example.com"), store.network().map { it.event.host })
    }

    @Test
    fun keepsEntriesForSevenDays() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "old"))
        now += LogStore.RETENTION_MILLIS

        assertEquals(listOf("old"), store.activity().map { it.event.text })

        now += 1
        assertEquals(emptyList<String>(), store.activity().map { it.event.text })
    }

    @Test
    fun appendingRemovesExpiredEntries() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "old"))
        now += LogStore.RETENTION_MILLIS + 1

        store.append(ActivityEvent(ActivityKind.NOTE, "new"))

        assertEquals(1, dao.rows.size)
    }

    @Test
    fun keepsEntriesUpToOneDayInTheFuture() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "ahead"))
        now -= LogStore.FUTURE_TOLERANCE_MILLIS

        assertEquals(listOf("ahead"), store.activity().map { it.event.text })

        now -= 1
        assertEquals(emptyList<String>(), store.activity().map { it.event.text })
    }

    @Test
    fun appendingAfterTheClockWentBackRemovesEntriesFromTheFuture() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "written while the clock was two days ahead"))
        now -= 2 * 24 * 60 * 60 * 1000L

        store.append(ActivityEvent(ActivityKind.NOTE, "new"))

        assertEquals(listOf(now), dao.rows.map { it.timestampMillis })
    }

    @Test
    fun deleteHistoryWipesBothLogs() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "note"))
        store.append(networkEvent("api.example.com"))

        store.deleteHistory()

        assertEquals(0, dao.rows.size)
    }

    @Test
    fun runsTheCipherOnTheStoreDispatcher() = runTest {
        val threads = CopyOnWriteArrayList<Thread>()
        val cipher = AesGcmFieldCipher { key.also { threads += Thread.currentThread() } }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val store = LogStore(dao, cipher, clock = { now }, dispatcher = dispatcher)
            store.append(ActivityEvent(ActivityKind.NOTE, "note"))
            store.activity()

            assertEquals(setOf(withContext(dispatcher) { Thread.currentThread() }), threads.toSet())
        }
    }

    @Test
    fun deletesEntriesThatCanNeverBeReadAndSaysSo() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "good"))
        val cipher = AesGcmFieldCipher { key }
        val activityPlace = "log_entries/activity".encodeToByteArray()
        val networkPlace = "log_entries/network".encodeToByteArray()
        val damaged = byteArrayOf(1, 2, 3)
        val oldFormat = cipher.encrypt("""{"text":"written before 'kind' existed"}""".encodeToByteArray(), activityPlace)
        val movedFromNetworkLog = cipher.encrypt("""{"kind":"NOTE","text":"moved"}""".encodeToByteArray(), networkPlace)
        listOf(damaged, oldFormat, movedFromNetworkLog).forEachIndexed { index, payload ->
            dao.rows += LogEntryRow(id = 100L + index, log = "activity", timestampMillis = now, payload = payload)
        }

        assertEquals(listOf("good"), store.activity().map { it.event.text })
        assertEquals(emptyList<Long>(), dao.rows.map { it.id }.filter { it in 100L..102L })
        assertEquals(listOf("Deleted 3 unreadable activity log entries", "good"), store.activity().map { it.event.text })
    }

    @Test
    fun keepPurgingPurgesOnceADayByTheWallClock() = runTest {
        val job = backgroundScope.launch { store.keepPurging { throw it } }
        runCurrent()
        assertEquals(1, dao.purges)

        // The phone slept: an hour of the coroutine's time, but a whole day on the wall clock.
        now += LogStore.DAILY_PURGE_MILLIS
        advanceTimeBy(LogStore.PURGE_CHECK_MILLIS)
        runCurrent()
        assertEquals(2, dao.purges)

        // A day of hourly checks without a day passing on the wall clock purges nothing more.
        repeat(23) {
            now += LogStore.PURGE_CHECK_MILLIS / 2
            advanceTimeBy(LogStore.PURGE_CHECK_MILLIS)
            runCurrent()
        }
        assertEquals(2, dao.purges)
        job.cancel()
    }

    @Test
    fun keepPurgingTriesAgainAfterAFailure() = runTest {
        dao.failNextPurge = true
        val failures = mutableListOf<Throwable>()
        val job = backgroundScope.launch { store.keepPurging { failures += it } }
        runCurrent()
        assertEquals(listOf("the disk is full"), failures.map { it.message })

        advanceTimeBy(LogStore.PURGE_CHECK_MILLIS)
        runCurrent()

        assertEquals(2, dao.purges)
        assertEquals(1, failures.size)
        job.cancel()
    }

    private fun networkEvent(host: String) = NetworkEvent(
        method = "GET",
        host = host,
        path = "/",
        requestBytes = 0,
        responseBytes = 10,
        status = 200,
        outcome = NetworkOutcome.COMPLETED,
    )
}

/** Mimics the SQL in [LogDao]; KestrelDatabaseTest checks the real queries on a device. */
private class FakeLogDao : LogDao {
    val rows = mutableListOf<LogEntryRow>()
    private var nextId = 1L
    var purges = 0
    var failNextPurge = false

    override suspend fun insert(row: LogEntryRow): Long {
        val id = nextId++
        rows += LogEntryRow(id, row.log, row.timestampMillis, row.payload)
        return id
    }

    override suspend fun newest(log: String, limit: Int): List<LogEntryRow> =
        rows.filter { it.log == log }
            .sortedWith(compareByDescending<LogEntryRow> { it.timestampMillis }.thenByDescending { it.id })
            .take(limit)

    override suspend fun deleteOutside(oldestMillis: Long, newestMillis: Long): Int {
        purges++
        if (failNextPurge) {
            failNextPurge = false
            throw IllegalStateException("the disk is full")
        }
        val outside = rows.filter { it.timestampMillis < oldestMillis || it.timestampMillis > newestMillis }
        rows.removeAll(outside)
        return outside.size
    }

    override suspend fun delete(id: Long): Int = if (rows.removeIf { it.id == id }) 1 else 0

    override suspend fun deleteAll(): Int {
        val count = rows.size
        rows.clear()
        return count
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

```powershell
scripts\gradle.ps1 :core:privacy:testDebugUnitTest
```

Expected: `BUILD FAILED` with unresolved references to `describeWithoutMessagesByCause` and `keepPurging`.

- [ ] **Step 3: Write the crash privacy and the purge**

Create `core/privacy/src/main/kotlin/dev/kestrel/privacy/logs/MainThreadErrors.kt`:

```kotlin
package dev.kestrel.privacy.logs

import android.os.Handler
import android.os.Looper

/**
 * Makes an error that is thrown on the main thread reach Android without its message (design spec, section 13.5).
 * Android's own pre-handler logs an uncaught error, with its message, before any handler runs. So the main thread's
 * message loop runs inside a loop of Kestrel's, which catches the error and throws a copy without messages instead,
 * and Android then logs and reports only the copy. The inner loop makes Android log one warning, "Loop again would
 * have the queued messages be executed before this one completed", when it starts. Errors that happen before it
 * starts, while Android creates the app, are not covered.
 */
object MainThreadErrors {
    /** Call it once, when the app is created; the inner loop starts with the main thread's next message. */
    fun install() {
        Handler(Looper.getMainLooper()).postAtFrontOfQueue {
            try {
                Looper.loop()
            } catch (error: Throwable) {
                throw error.withoutMessages()
            }
        }
    }
}
```

Replace the whole content of `core/privacy/src/main/kotlin/dev/kestrel/privacy/logs/ErrorSummary.kt` with:

```kotlin
package dev.kestrel.privacy.logs

import java.util.IdentityHashMap

private const val MAX_CAUSES = 8
private const val MAX_FRAMES = 30

/**
 * Describes this error by its class names and stack frames only, for logcat. Messages are left out, because
 * they can contain screen text or other personal data (design spec, section 13.5).
 */
fun Throwable.describeWithoutMessages(): String = describeWithoutMessagesByCause().joinToString("\n")

/**
 * The same description as [describeWithoutMessages], as one text for each error in the chain of causes, because
 * Android's log cuts an entry after about 4 KB.
 */
fun Throwable.describeWithoutMessagesByCause(): List<String> {
    val parts = mutableListOf<String>()
    var error: Throwable? = this
    while (error != null && parts.size < MAX_CAUSES) {
        val current: Throwable = error
        parts += buildString {
            if (parts.isNotEmpty()) append("Caused by: ")
            append(current.originalClassName())
            for (frame in current.stackTrace.take(MAX_FRAMES)) append("\n    at ").append(frame)
        }
        error = current.cause?.takeIf { it !== current }
    }
    return parts
}

/**
 * Returns a copy of this error without messages, for Android's crash handler (design spec, section 13.5). The copy,
 * its causes and its suppressed errors keep their stack frames, and their [toString] gives only the original class
 * name, such as `java.lang.IllegalStateException`, while their messages are null. Like [describeWithoutMessages],
 * the copy keeps at most [MAX_CAUSES] errors of a chain of causes. A cause that leads back to an earlier error is
 * left out, so that the copy's chain of causes always ends.
 */
fun Throwable.withoutMessages(): Throwable {
    // An error maps to null while its copy is being made, so that a link back to it is left out instead of closing a cycle.
    val copies = IdentityHashMap<Throwable, Throwable?>()
    fun copyOf(error: Throwable, depth: Int): Throwable? {
        if (error in copies) return copies[error]
        copies[error] = null
        val copy = MessageFreeCopy(error.originalClassName(), error.stackTrace)
        if (depth + 1 < MAX_CAUSES) {
            error.cause?.let { copyOf(it, depth + 1) }?.let(copy::initCause)
            for (suppressed in error.suppressed) copyOf(suppressed, depth + 1)?.let(copy::addSuppressed)
        }
        copies[error] = copy
        return copy
    }
    return checkNotNull(copyOf(this, depth = 0))
}

/** The class name of the error that this error copies, if it is a copy, so that a copy of a copy keeps the original name. */
private fun Throwable.originalClassName(): String = (this as? MessageFreeCopy)?.className ?: javaClass.name

/**
 * A copy of an error that shows only the original class name and stack frames. It is a RuntimeException, because
 * Android rethrows those from the main thread as they are, while it wraps other kinds of errors in a RuntimeException.
 */
private class MessageFreeCopy(val className: String, stackTrace: Array<StackTraceElement>) : RuntimeException() {
    init {
        this.stackTrace = stackTrace
    }

    override fun toString(): String = className
}
```

Replace the whole content of `core/privacy/src/main/kotlin/dev/kestrel/privacy/logs/MessageFreeCrashHandler.kt` with:

```kotlin
package dev.kestrel.privacy.logs

/**
 * Passes every uncaught error to Android's crash handler, [platform], as a copy without messages, because a message
 * can contain screen text (design spec, section 13.5). Android's crash report, and the crash log of failures that
 * reach this handler directly, such as coroutine failures, then contain only class names and stack frames. Android's
 * crash record names the copy's own class, `MessageFreeCopy`, as the exception class, while the stack text keeps the
 * real class names.
 *
 * When the copy cannot be made, for example because the heap is full, the handler passes on a placeholder,
 * `CrashCopyFailed`, which has no message and no stack frames. It never passes on the original error, and it always
 * calls [platform], which reports the crash and ends the process.
 *
 * Android's own pre-handler logs an error that is thrown directly on a thread, with its message, before this handler
 * runs. [MainThreadErrors] closes that gap for the main thread; Kestrel's other work runs in coroutines, whose
 * failures reach this handler directly.
 */
class MessageFreeCrashHandler(private val platform: Thread.UncaughtExceptionHandler) : Thread.UncaughtExceptionHandler {
    // Made up front, because a copy can fail when the heap is full, and then there may be no memory left to make it.
    private val copyFailed: Throwable = CrashCopyFailed()

    override fun uncaughtException(thread: Thread, error: Throwable) {
        // Android's handler must run whatever happens: it reports the crash and ends the process.
        val copy = try {
            error.withoutMessages()
        } catch (e: Throwable) {
            copyFailed
        }
        platform.uncaughtException(thread, copy)
    }
}

/**
 * Stands in for an error whose copy could not be made. It has no message and no stack frames, and nothing can add a
 * cause or suppressed errors to it, so one instance serves every crash.
 */
private class CrashCopyFailed : Throwable(null, null, false, false) {
    // The same text as Throwable's own toString() gives without a message, but read up front, like the placeholder.
    private val name: String = javaClass.name

    override fun toString(): String = name
}
```

Replace the whole content of `core/privacy/src/main/kotlin/dev/kestrel/privacy/logs/LogStore.kt` with:

```kotlin
package dev.kestrel.privacy.logs

import dev.kestrel.privacy.crypto.FieldCipher
import dev.kestrel.privacy.db.LogDao
import dev.kestrel.privacy.db.LogEntryRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import javax.crypto.AEADBadTagException

data class LogEntry<T>(val id: Long, val timestampMillis: Long, val event: T)

/**
 * The encrypted activity log and network log, which keep entries for 7 days (design spec, section 13.5).
 * Its functions are safe to call from the main thread, because they encrypt, decrypt and query on [dispatcher].
 */
class LogStore(
    private val dao: LogDao,
    private val cipher: FieldCipher,
    private val clock: () -> Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var lastPurgeMillis: Long? = null

    suspend fun append(event: ActivityEvent) =
        withContext(dispatcher) { append(ACTIVITY, ActivityEvent.serializer(), event) }

    suspend fun append(event: NetworkEvent) =
        withContext(dispatcher) { append(NETWORK, NetworkEvent.serializer(), event) }

    suspend fun activity(limit: Int = DEFAULT_LIMIT): List<LogEntry<ActivityEvent>> =
        withContext(dispatcher) { read(ACTIVITY, ActivityEvent.serializer(), limit) }

    suspend fun network(limit: Int = DEFAULT_LIMIT): List<LogEntry<NetworkEvent>> =
        withContext(dispatcher) { read(NETWORK, NetworkEvent.serializer(), limit) }

    /**
     * Deletes the entries of both logs that are older than 7 days. It also deletes entries dated more than a
     * day ahead, which were written while the clock was wrong and would otherwise outlive the 7 days.
     */
    suspend fun purgeExpired(): Int = withContext(dispatcher) {
        val now = clock()
        lastPurgeMillis = now
        dao.deleteOutside(oldestMillis = now - RETENTION_MILLIS, newestMillis = now + FUTURE_TOLERANCE_MILLIS)
    }

    /**
     * Purges the logs once a day for as long as it runs, for a service that runs for days (design spec, section 13.5).
     * It checks every hour against [clock], because a coroutine's delay stops counting while the phone sleeps. A
     * failed purge goes to [onFailure], and the next check tries again.
     */
    suspend fun keepPurging(onFailure: (Throwable) -> Unit): Nothing {
        var lastPurge: Long? = null
        while (true) {
            val now = clock()
            val last = lastPurge
            // After the clock went back, the interval check alone would wait until the clock caught up again.
            if (last == null || now < last || now - last >= DAILY_PURGE_MILLIS) {
                try {
                    purgeExpired()
                    lastPurge = now
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onFailure(e)
                }
            }
            delay(PURGE_CHECK_MILLIS)
        }
    }

    /** "Delete history" wipes both logs (design spec, section 13.5). */
    suspend fun deleteHistory(): Int = withContext(dispatcher) { dao.deleteAll() }

    private suspend fun <T> append(log: String, serializer: KSerializer<T>, event: T) {
        val now = clock()
        val lastPurge = lastPurgeMillis
        // After the clock went back, the interval check alone would wait until the clock caught up again.
        if (lastPurge == null || now < lastPurge || now - lastPurge >= PURGE_INTERVAL_MILLIS) purgeExpired()
        val plaintext = json.encodeToString(serializer, event).encodeToByteArray()
        dao.insert(LogEntryRow(log = log, timestampMillis = now, payload = cipher.encrypt(plaintext, associatedData(log))))
    }

    /**
     * Returns the newest entries. An entry that can never be read is deleted, so that it cannot block the
     * whole log, and the activity log says how many were deleted.
     */
    private suspend fun <T> read(log: String, serializer: KSerializer<T>, limit: Int): List<LogEntry<T>> {
        purgeExpired()
        val entries = mutableListOf<LogEntry<T>>()
        var unreadable = 0
        for (row in dao.newest(log, limit)) {
            val event = decodeOrNull(log, serializer, row)
            if (event == null) {
                dao.delete(row.id)
                unreadable++
            } else {
                entries += LogEntry(row.id, row.timestampMillis, event)
            }
        }
        if (unreadable > 0) {
            append(ACTIVITY, ActivityEvent.serializer(), ActivityEvent(ActivityKind.ERROR, "Deleted $unreadable unreadable $log log entries"))
        }
        return entries
    }

    /** Returns null for a payload that is damaged, was encrypted for another place or with a lost key, or has an old format. */
    private fun <T> decodeOrNull(log: String, serializer: KSerializer<T>, row: LogEntryRow): T? =
        try {
            json.decodeFromString(serializer, cipher.decrypt(row.payload, associatedData(log)).decodeToString())
        } catch (e: AEADBadTagException) {
            null
        } catch (e: IllegalArgumentException) {
            // Also covers kotlinx.serialization's SerializationException, which extends IllegalArgumentException.
            null
        }

    private fun associatedData(log: String) = "log_entries/$log".encodeToByteArray()

    companion object {
        const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000

        /** An entry dated further ahead than this was written while the clock was wrong. */
        const val FUTURE_TOLERANCE_MILLIS = 24L * 60 * 60 * 1000

        const val PURGE_INTERVAL_MILLIS = 60L * 60 * 1000
        const val DAILY_PURGE_MILLIS = 24L * 60 * 60 * 1000
        const val PURGE_CHECK_MILLIS = 60L * 60 * 1000
        const val DEFAULT_LIMIT = 500
        private const val ACTIVITY = "activity"
        private const val NETWORK = "network"
    }
}
```

- [ ] **Step 4: Run the unit tests again**

Run the command of Step 2 again. Expected: `BUILD SUCCESSFUL`; `ErrorSummaryTest` runs 7 tests and `LogStoreTest` 12.

- [ ] **Step 5: Install them in the app, and add the crash hook**

Replace the whole content of `app/src/main/kotlin/dev/kestrel/app/KestrelApplication.kt` with:

```kotlin
package dev.kestrel.app

import android.app.Application
import android.content.Context
import dev.kestrel.privacy.logs.MainThreadErrors
import dev.kestrel.privacy.logs.MessageFreeCrashHandler
import kotlinx.coroutines.launch

class KestrelApplication : Application() {
    lateinit var graph: AppGraph
        private set

    // Here rather than in onCreate(), because Android starts the app's content providers between the two.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        Thread.getDefaultUncaughtExceptionHandler()?.let { Thread.setDefaultUncaughtExceptionHandler(MessageFreeCrashHandler(it)) }
        MainThreadErrors.install()
    }

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.scope.launch { graph.logs.purgeExpired() }
    }
}
```

Replace the whole content of `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` with:

```kotlin
package dev.kestrel.app

import android.content.Context
import android.util.Log
import dev.kestrel.privacy.PrivacyStore
import dev.kestrel.privacy.logs.describeWithoutMessagesByCause
import dev.kestrel.privacy.network.NetworkGateway
import dev.kestrel.privacy.network.NetworkPolicy
import dev.kestrel.privacy.settings.SettingKey
import dev.kestrel.safety.SafetySettings
import dev.kestrel.screen.ScreenSession
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Every setting that Kestrel stores. */
object SettingKeys {
    val SAFETY = SettingKey("safety", SafetySettings.serializer(), SafetySettings())
}

/** Wires Kestrel's parts together by hand; there is no dependency-injection framework (design spec, section 17). */
class AppGraph(context: Context) {
    /**
     * Handles the failures of background work: a failed job is written to logcat instead of ending the app's process.
     * Only the error's classes and stack frames are written, because a message can contain screen text, and each error
     * of the chain of causes gets its own entry, because Android's log cuts an entry after about 4 KB.
     */
    val errors = CoroutineExceptionHandler { _, error ->
        for ((index, part) in error.describeWithoutMessagesByCause().withIndex()) {
            Log.e(TAG, if (index == 0) "A background task failed: $part" else part)
        }
    }

    /** Background work, whose failures go to [errors]. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errors)

    private val store = PrivacyStore(context)
    val logs = store.logs
    val settings = store.settings
    val networkPolicy = NetworkPolicy()

    // Built on first use: building the HTTP client on the main thread made Kestrel miss Android's start-up deadline
    // on a busy emulator just after it booted.
    val network: NetworkGateway by lazy { NetworkGateway(networkPolicy) { event -> scope.launch { logs.append(event) } } }

    private val screens = MutableStateFlow<ScreenSession?>(null)

    /** Kestrel's screen layer while its accessibility service is connected, otherwise null. */
    val screen: StateFlow<ScreenSession?> = screens.asStateFlow()

    internal fun attachScreen(session: ScreenSession) {
        screens.value = session
    }

    internal fun detachScreen(session: ScreenSession) {
        screens.compareAndSet(session, null)
    }

    private companion object {
        const val TAG = "Kestrel"
    }
}
```

Replace the whole content of `app/src/main/kotlin/dev/kestrel/app/KestrelAccessibilityService.kt` with:

```kotlin
package dev.kestrel.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import dev.kestrel.screen.ScreenSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Reads the screen and acts on it for Kestrel (design spec, sections 7.2 and 9). */
class KestrelAccessibilityService : AccessibilityService() {
    private val graph: AppGraph get() = (application as KestrelApplication).graph
    private var session: ScreenSession? = null
    private var purging: Job? = null

    override fun onServiceConnected() {
        val connected = ScreenSession(this, graph.errors)
        session = connected
        graph.attachScreen(connected)
        // The service runs for days at a time, so it also keeps the logs within their 7 days (design spec, section 13.5).
        purging = graph.scope.launch {
            val context = coroutineContext
            graph.logs.keepPurging { error -> graph.errors.handleException(context, error) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        session?.onAccessibilityEvent(event)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        purging?.cancel()
        purging = null
        session?.let {
            graph.detachScreen(it)
            it.close()
        }
        session = null
    }
}
```

Replace the whole content of `app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt` with:

```kotlin
package dev.kestrel.app.debug

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import dev.kestrel.app.AppGraph
import dev.kestrel.app.SettingKeys
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.GlobalAction
import dev.kestrel.screen.act.ScrollDirection
import dev.kestrel.screen.model.ScreenJson
import dev.kestrel.screen.overlay.NumbersMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A test hook's answer, which becomes the broadcast's result code and data. */
internal data class HookReply(val code: Int, val text: String) {
    companion object {
        /** The result code of a command that failed. A command that never ran keeps the initial code, 0. */
        const val FAILED = 1

        fun ok(text: String) = HookReply(Activity.RESULT_OK, text)

        fun failed(message: String) = HookReply(FAILED, "error: $message")
    }
}

/**
 * Runs the test-hook commands (design spec, section 16.1). The intent's action is [ACTION_PREFIX] followed by the
 * command, and its extras are the arguments; `scripts/hook.ps1` lists them.
 */
internal class TestHooks(private val graph: AppGraph, private val cacheDir: File) {
    suspend fun run(intent: Intent): HookReply = try {
        HookReply.ok(answer(intent))
    } catch (error: HookError) {
        HookReply.failed(error.message)
    }

    private suspend fun answer(intent: Intent): String {
        val command = intent.action.orEmpty().removePrefix(ACTION_PREFIX)
        if (command == "STATE") return state()
        if (command == "TEST_MODE") return testMode(intent)
        if (command == "CRASH") return crash(intent.requiredText())
        val session = graph.screen.value ?: throw HookError("Kestrel's accessibility service is off")
        val actions = session.actions
        return when (command) {
            "SNAPSHOT" -> save(ScreenJson.encode(session.snapshots.current().snapshot))
            "TAP" -> done(tap(session, intent))
            "TYPE" -> done(actions.setText(target(session, intent), intent.requiredText()))
            "SCROLL" -> done(actions.scroll(intent.choice<ScrollDirection>(EXTRA_DIRECTION), optionalTarget(session, intent)))
            "GLOBAL" -> done(actions.global(intent.choice<GlobalAction>(EXTRA_ACTION)))
            "OVERLAY" -> {
                overlay(session, intent)
                state()
            }
            else -> throw HookError("unknown command '$command'")
        }
    }

    /**
     * Writes a snapshot to Kestrel's cache and answers with the file's path in Kestrel's data folder, because a
     * broadcast's answer must stay under Android's limit of about 1 MB. `scripts/hook.ps1` reads the file with
     * `run-as` and deletes it.
     */
    private suspend fun save(json: String): String = withContext(Dispatchers.IO) {
        File(cacheDir, SNAPSHOT_FILE).writeText(json)
        "${cacheDir.name}/$SNAPSHOT_FILE"
    }

    /**
     * Ends Kestrel's process with an error whose message is [text], thrown on the main thread a moment after the answer,
     * so that a check can look for [text] in Android's crash records (design spec, section 13.5).
     */
    private fun crash(text: String): String {
        Handler(Looper.getMainLooper()).postDelayed({ throw IllegalStateException(text) }, CRASH_DELAY_MILLIS)
        return "crashing"
    }

    private suspend fun state(): String {
        val testMode = onOff(graph.settings.get(SettingKeys.SAFETY).testMode)
        val session = graph.screen.value ?: return "screen=off testMode=$testMode"
        return withContext(Dispatchers.Main) {
            "screen=on testMode=$testMode numbers=${session.numberBadges.size} labels=${onOff(session.labelsShowing)} " +
                "grid=${onOff(session.grid != null)} feedback=${onOff(session.feedbackText != null)}"
        }
    }

    private suspend fun testMode(intent: Intent): String {
        if (!intent.hasExtra(EXTRA_ON)) throw HookError("add --ez on true or --ez on false")
        val on = intent.getBooleanExtra(EXTRA_ON, true)
        graph.settings.set(SettingKeys.SAFETY, graph.settings.get(SettingKeys.SAFETY).copy(testMode = on))
        return "testMode=${onOff(on)}"
    }

    private suspend fun tap(session: ScreenSession, intent: Intent): ActionResult {
        val long = intent.getBooleanExtra(EXTRA_LONG, false)
        if (intent.hasExtra(EXTRA_X) || intent.hasExtra(EXTRA_Y)) {
            if (!intent.hasExtra(EXTRA_X) || !intent.hasExtra(EXTRA_Y)) throw HookError("add both --ei x and --ei y")
            val x = intent.getIntExtra(EXTRA_X, 0)
            val y = intent.getIntExtra(EXTRA_Y, 0)
            return if (long) session.actions.longPressAt(x, y) else session.actions.tapAt(x, y)
        }
        val key = target(session, intent)
        return if (long) session.actions.longPress(key) else session.actions.tap(key)
    }

    private suspend fun overlay(session: ScreenSession, intent: Intent) {
        when (intent.getStringExtra(EXTRA_SHOW)) {
            "numbers" -> session.showNumbers(NumbersMode.ALL)
            "unlabeled" -> session.showNumbers(NumbersMode.UNLABELED)
            "labels" -> session.showLabels()
            "grid" -> session.showGrid()
            "feedback" -> session.showFeedback(intent.requiredText())
            "none" -> {
                session.hideNumbers()
                session.hideLabels()
                session.hideGrid()
            }
            else -> throw HookError("add --es show with numbers, unlabeled, labels, grid, feedback or none")
        }
    }

    /** The element that the extras name: by its key, or by its number while the numbers show. */
    private suspend fun target(session: ScreenSession, intent: Intent): String =
        optionalTarget(session, intent) ?: throw HookError("add --es key <element key> or --ei number <number>")

    private suspend fun optionalTarget(session: ScreenSession, intent: Intent): String? {
        intent.getStringExtra(EXTRA_KEY)?.let { return it }
        if (!intent.hasExtra(EXTRA_NUMBER)) return null
        val number = intent.getIntExtra(EXTRA_NUMBER, 0)
        val badges = withContext(Dispatchers.Main) { session.numberBadges }
        return badges.firstOrNull { it.number == number }?.key ?: throw HookError("no element shows the number $number")
    }

    private fun done(result: ActionResult): String = when (result) {
        is ActionResult.Done -> "done: ${result.method.name.lowercase()}"
        is ActionResult.Failed -> throw HookError(result.reason)
    }

    private fun Intent.requiredText(): String = getStringExtra(EXTRA_TEXT) ?: throw HookError("add --es text <text>")

    private inline fun <reified T : Enum<T>> Intent.choice(name: String): T {
        val value = getStringExtra(name)?.replace('-', '_')
        return enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw HookError("add --es $name with one of ${enumValues<T>().joinToString { it.name.lowercase() }}")
    }

    private fun onOff(value: Boolean) = if (value) "on" else "off"

    private class HookError(override val message: String) : Exception(message)

    companion object {
        const val ACTION_PREFIX = "dev.kestrel.hook."
        const val SNAPSHOT_FILE = "hook-snapshot.json"
        private const val CRASH_DELAY_MILLIS = 500L
        private const val EXTRA_KEY = "key"
        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_X = "x"
        private const val EXTRA_Y = "y"
        private const val EXTRA_LONG = "long"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_DIRECTION = "direction"
        private const val EXTRA_ACTION = "action"
        private const val EXTRA_SHOW = "show"
        private const val EXTRA_ON = "on"
    }
}
```

Replace the whole content of `scripts/hook.ps1` with:

```powershell
# Sends a test-hook command to Kestrel's debug build on a test device and prints the answer (design spec,
# section 16.1). Commands and their options:
#   state                                   whether the screen layer runs, test mode and the overlays
#   test-mode -On true|false                switches test mode
#   snapshot [-Out file.json]               what Kestrel reads on the screen, as JSON
#   overlay -Show numbers|unlabeled|labels|grid|none
#   overlay -Show feedback -Text 'text'     shows the feedback bar
#   tap -Key key | -Number n | -X x -Y y [-Long]
#   type -Key key | -Number n -Text 'text'
#   scroll -Direction up|down|left|right [-Key key | -Number n]
#   global -Action back|home|recent-apps|notifications|quick-settings|lock-screen|screenshot|power-menu|split-screen|play-pause
#   crash -Text 'text'                      ends Kestrel's process with an error whose message is the text, to check
#                                           that Android's crash records do not contain it
# -Number picks an element by the number that the numbers overlay shows on it.
# Example: scripts\hook.ps1 -Serial emulator-5554 tap -Number 3
# Windows PowerShell 5.1 drops double quotes from the arguments of other programs, which would change the text that
# 'type' types, so the script needs PowerShell 7.3 or newer.
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('state', 'test-mode', 'snapshot', 'overlay', 'tap', 'type', 'scroll', 'global', 'crash')][string]$Command,
    [string]$Key,
    [int]$Number,
    [int]$X = -1,
    [int]$Y = -1,
    [switch]$Long,
    [string]$Text,
    [string]$Direction,
    [string]$Action,
    [string]$Show,
    [ValidateSet('true', 'false')][string]$On,
    [string]$Out
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial

# The device's shell reads the command line, so every text value is quoted for it.
function ConvertTo-ShellText([string]$Value) { "'" + $Value.Replace("'", "'\''") + "'" }

# Runs adb and reads its output as UTF-8, because screen text can be in any language.
function Invoke-Adb {
    $consoleEncoding = [Console]::OutputEncoding
    [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
    try {
        (& (Get-Adb) -s $Serial @args) -join "`n"
    } finally {
        [Console]::OutputEncoding = $consoleEncoding
    }
}

$extras = @()
if ($Key) { $extras += "--es key $(ConvertTo-ShellText $Key)" }
if ($PSBoundParameters.ContainsKey('Number')) { $extras += "--ei number $Number" }
if ($X -ge 0) { $extras += "--ei x $X" }
if ($Y -ge 0) { $extras += "--ei y $Y" }
if ($Long) { $extras += '--ez long true' }
if ($PSBoundParameters.ContainsKey('Text')) { $extras += "--es text $(ConvertTo-ShellText $Text)" }
if ($Direction) { $extras += "--es direction $(ConvertTo-ShellText $Direction)" }
if ($Action) { $extras += "--es action $(ConvertTo-ShellText $Action)" }
if ($Show) { $extras += "--es show $(ConvertTo-ShellText $Show)" }
if ($On) { $extras += "--ez on $On" }

$hookAction = 'dev.kestrel.hook.' + $Command.ToUpperInvariant().Replace('-', '_')
$broadcast = "am broadcast -n dev.kestrel/dev.kestrel.app.debug.TestHookReceiver -a $hookAction $($extras -join ' ')"
$output = Invoke-Adb shell $broadcast

$match = [regex]::Match($output, '(?s)Broadcast completed: result=(-?\d+)(?:, data="(.*)")?\s*$')
if (-not $match.Success) { throw "Unexpected answer from the device:`n$output" }
$data = $match.Groups[2].Value
switch ([int]$match.Groups[1].Value) {
    -1 {
        if ($Command -eq 'snapshot') {
            # A snapshot can be larger than a broadcast's answer, so Kestrel answers with the path of a file in its own
            # storage, which only a debug build lets adb read.
            $file = $data
            $data = Invoke-Adb exec-out run-as dev.kestrel cat $file
            Invoke-Adb shell run-as dev.kestrel rm $file | Out-Null
        }
        if ($Out) {
            $path = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Out)
            [IO.File]::WriteAllText($path, $data, [Text.UTF8Encoding]::new($false))
            "Saved to $path"
        } else {
            $data
        }
    }
    0 { throw 'Kestrel did not answer. Install a debug build with scripts\install.ps1.' }
    default { throw $data }
}
```

In the spec, `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 16.1, replace the paragraph that starts with "Debug builds contain a broadcast receiver" with:

````markdown
Debug builds contain a broadcast receiver that accepts commands only from callers that hold `android.permission.DUMP`, which in practice means `adb shell`. It can inject a transcript or a task, read the Orchestrator's state and switch test mode. It can also read the screen, perform actions and show the overlays, for checks on real apps, and end Kestrel's process with an error, to check that Android's crash records contain no messages. Release builds do not contain it, and the build-time audit fails a release build that does.
````

- [ ] **Step 6: Check Android's crash records**

```powershell
scripts\gradle.ps1 check kestrelAudit
scripts\install.ps1 -Serial <serial>
$adb = Get-Adb
$marker = "kestrel-marker-$(Get-Random)"
& $adb -s <serial> logcat -b crash -c
scripts\hook.ps1 -Serial <serial> crash -Text $marker
Start-Sleep -Seconds 6
(& $adb -s <serial> logcat -b crash -d) -join "`n" | Select-String -SimpleMatch $marker
(& $adb -s <serial> shell dumpsys dropbox --print data_app_crash) -join "`n" | Select-String -SimpleMatch $marker
(& $adb -s <serial> logcat -b crash -d) | Select-Object -First 3
```

Expected: `BUILD SUCCESSFUL`; the two searches print nothing; and the crash log starts with `java.lang.IllegalStateException` and the original stack frames, without the marker. To see what the step guards against, comment out `MainThreadErrors.install()` in `KestrelApplication`, install again and repeat: the crash log then contains `IllegalStateException: kestrel-marker-...`, while DropBox stays clean. Undo that change afterwards.

- [ ] **Step 7: Run all device tests**

```powershell
scripts\connected-test.ps1 -Serial <serial> :core:privacy:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`, 7 tests in `:core:privacy` and 69 in `:app`.

- [ ] **Step 8: Commit**

```powershell
git add core/privacy app/src scripts/hook.ps1 docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "fix(privacy): keep error messages out of every crash record" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: Waiting for a quiet screen outside the snapshot lock, and following zooms

**Files:**
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/read/SnapshotCache.kt`
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/read/ScreenChangeTracker.kt` (`now()`)
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/act/ScreenActions.kt` (the tracker's clock; zooms report changes)
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt`
- Modify: `core/screen/src/test/kotlin/dev/kestrel/screen/read/SnapshotCacheTest.kt`
- Modify: `app/src/androidTest/kotlin/dev/kestrel/app/screen/OverlayTest.kt`

**Interfaces:**
- Consumes: plan 1B's screen layer.
- Produces: `ScreenChangeTracker.now(): Long`. `ScreenActions`' internal constructor no longer takes a `clock`; it uses the tracker's. `SnapshotCache.current()` and `invalidate()` keep their signatures.

Closes (3a), (9j), (3c), (6k), (5g), (6i), (8a) and the final review's Minor 4.

Callers waited for a quiet screen while they held the cache's lock, so on a busy screen they waited one after another. The cache now publishes one immutable entry (the snapshot, the version it was built for, and the number of invalidations) through a single volatile field, and every caller waits for quiet outside the lock. Android's magnification moves every node without an accessibility event of its own, so a zoom reports a screen change before and after it settles. Reads now run on `Dispatchers.IO`, and the KDoc of `ScreenActions` tells callers to call it from there.

- [ ] **Step 1: Write the failing unit test**

Replace the whole content of `core/screen/src/test/kotlin/dev/kestrel/screen/read/SnapshotCacheTest.kt` with:

```kotlin
package dev.kestrel.screen.read

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotCacheTest {
    @Test
    fun reusesTheSnapshotUntilTheScreenChangesAndThenWaitsForAQuietScreen() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        var builds = 0
        val cache = SnapshotCache(tracker) { ++builds }

        assertEquals(1, cache.current())
        assertEquals(1, cache.current())
        val changedAt = testScheduler.currentTime
        tracker.onScreenChanged()

        assertEquals(2, cache.current())
        assertTrue(testScheduler.currentTime - changedAt >= ScreenChangeTracker.SNAPSHOT_QUIET_MILLIS)
    }

    @Test
    fun aChangeDuringABuildMakesTheNextCallBuildAgain() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        var builds = 0
        val cache = SnapshotCache(tracker) {
            builds++
            if (builds == 1) tracker.onScreenChanged()
            builds
        }

        assertEquals(1, cache.current())
        assertEquals(2, cache.current())
    }

    @Test
    fun anInvalidateDuringABuildIsNotLost() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        var builds = 0
        lateinit var cache: SnapshotCache<Int>
        cache = SnapshotCache(tracker) {
            builds++
            // Another caller invalidates the cache while this snapshot is being built.
            if (builds == 1) cache.invalidate()
            builds
        }

        assertEquals(1, cache.current())
        assertEquals(2, cache.current())
    }

    @Test
    fun callersOnABusyScreenWaitForQuietSideBySide() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        var builds = 0
        val cache = SnapshotCache(tracker) {
            delay(100)
            ++builds
        }
        cache.current()
        // From now on the screen changes every 50 ms, as a stopwatch would.
        val ticking = launch {
            while (true) {
                tracker.onScreenChanged()
                delay(50)
            }
        }
        val started = testScheduler.currentTime

        val first = async { cache.current() }
        val second = async { cache.current() }
        first.await()
        second.await()
        ticking.cancel()

        // Each caller waits at most 3 s for quiet and then builds; the second does not wait 3 s more after the first.
        assertTrue(testScheduler.currentTime - started < 2 * ScreenChangeTracker.MAX_WAIT_MILLIS)
    }

    @Test
    fun invalidateMakesTheNextCallBuildAgain() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        var builds = 0
        val cache = SnapshotCache(tracker) { ++builds }
        cache.current()

        cache.invalidate()

        assertEquals(2, cache.current())
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```powershell
scripts\gradle.ps1 :core:screen:testDebugUnitTest --tests 'dev.kestrel.screen.read.SnapshotCacheTest'
```

Expected: `callersOnABusyScreenWaitForQuietSideBySide` fails, because the old cache makes the second caller wait until the first one has finished waiting.

- [ ] **Step 3: Change the cache and the tracker**

Replace the whole content of `core/screen/src/main/kotlin/dev/kestrel/screen/read/SnapshotCache.kt` with:

```kotlin
package dev.kestrel.screen.read

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps the last snapshot until the screen changes. After a change, it builds a new snapshot once the screen
 * has been quiet for [ScreenChangeTracker.SNAPSHOT_QUIET_MILLIS], or after [ScreenChangeTracker.MAX_WAIT_MILLIS] on a
 * screen that never goes quiet, such as a stopwatch (design spec, section 9.2).
 */
class SnapshotCache<T : Any>(private val tracker: ScreenChangeTracker, private val build: suspend () -> T) {
    /** A snapshot with the tracker's version and the number of [invalidate] calls when its build started. */
    private class Entry<T>(val value: T, val version: Long, val invalidations: Long)

    private val mutex = Mutex()

    @Volatile
    private var entry: Entry<T>? = null

    // Counts the calls of invalidate(), so that a snapshot whose build was already running is not kept.
    private val invalidations = AtomicLong()

    suspend fun current(): T {
        fresh()?.let { return it }
        // Callers wait for the quiet screen side by side, outside the lock, instead of one after another.
        tracker.awaitQuiet(ScreenChangeTracker.SNAPSHOT_QUIET_MILLIS)
        return mutex.withLock {
            // Another caller may have built a snapshot of the current screen meanwhile.
            fresh() ?: run {
                val version = tracker.version.value
                val invalidated = invalidations.get()
                build().also { entry = Entry(it, version, invalidated) }
            }
        }
    }

    /**
     * Makes the next [current] call build a new snapshot, for example before an action is retried. This also holds
     * when another caller is building a snapshot at the same moment.
     */
    fun invalidate() {
        invalidations.incrementAndGet()
    }

    /** The cached snapshot, if the screen has not changed and nobody has called [invalidate] since its build started. */
    private fun fresh(): T? =
        entry?.takeIf { it.version == tracker.version.value && it.invalidations == invalidations.get() }?.value
}
```

Replace the whole content of `core/screen/src/main/kotlin/dev/kestrel/screen/read/ScreenChangeTracker.kt` with:

```kotlin
package dev.kestrel.screen.read

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Remembers when the screen last changed, so that Kestrel can wait until it is quiet (design spec, sections 9.2
 * and 9.3). [clock] counts milliseconds and never jumps, like `SystemClock.uptimeMillis`.
 */
class ScreenChangeTracker(private val clock: () -> Long) {
    private val versions = MutableStateFlow(0L)

    @Volatile
    private var lastChangeAt = clock()

    /** Counts the changes so far. A snapshot built at one version is stale at any later version. */
    val version: StateFlow<Long> = versions.asStateFlow()

    /** The time on this tracker's clock, for [awaitQuiet]'s `since`. */
    fun now(): Long = clock()

    fun onScreenChanged() {
        lastChangeAt = clock()
        versions.update { it + 1 }
    }

    /**
     * Waits until the screen has had no change for [quietMillis], counted from [since] at the earliest, and
     * returns true. Returns false when that takes longer than [maxWaitMillis]. [since] must come from [now], because
     * only this tracker's clock fits its times.
     */
    suspend fun awaitQuiet(quietMillis: Long, since: Long = Long.MIN_VALUE, maxWaitMillis: Long = MAX_WAIT_MILLIS): Boolean {
        val deadline = clock() + maxWaitMillis
        while (true) {
            val now = clock()
            val quietFor = now - maxOf(lastChangeAt, since)
            if (quietFor >= quietMillis) return true
            if (now >= deadline) return false
            delay(minOf(quietMillis - quietFor, deadline - now))
        }
    }

    companion object {
        /** How long the screen must be quiet before Kestrel builds a new snapshot. */
        const val SNAPSHOT_QUIET_MILLIS = 150L

        /** How long the screen must be quiet after an action. */
        const val ACTION_QUIET_MILLIS = 200L

        /** The longest wait for a quiet screen. */
        const val MAX_WAIT_MILLIS = 3_000L
    }
}
```

- [ ] **Step 4: Run the unit tests again**

Run the command of Step 2 again. Expected: `BUILD SUCCESSFUL`, 5 tests.

- [ ] **Step 5: Follow zooms, and read on Dispatchers.IO**

Replace the whole content of `core/screen/src/main/kotlin/dev/kestrel/screen/act/ScreenActions.kt` with:

```kotlin
package dev.kestrel.screen.act

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.InputMethod
import android.accessibilityservice.MagnificationConfig
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.read.LiveSnapshot
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.SnapshotCache
import dev.kestrel.screen.read.shownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Kestrel's actions on the screen. Each one has a fallback (design spec, sections 9.3 and 14.3). They query and act on
 * nodes with blocking binder calls on the caller's dispatcher, so callers run them on `Dispatchers.IO`.
 */
class ScreenActions internal constructor(
    private val service: AccessibilityService,
    private val snapshots: SnapshotCache<LiveSnapshot>,
    private val gestures: GestureRunner,
    private val tracker: ScreenChangeTracker,
) {
    /** Apps that refused the set-text action or did not show the text afterwards (design spec, section 9.7). */
    private val inputMethodApps = ConcurrentHashMap.newKeySet<String>()

    /**
     * Taps the element with [key]. Kestrel looks the key up in the current snapshot, never in an older one, because a
     * list may have reused an old view for another item. It uses the click action of the element or of its nearest
     * parent that handles touches. If that node has gone or refuses the click, Kestrel looks for the element again in
     * a fresh snapshot, and if nothing offers a click, it taps the element's centre. It does not tap a disabled
     * element, because a finger's touch would stop there too.
     *
     * A key names a position, not an identity. After a list has added, removed or scrolled rows, a key from an older
     * snapshot can name a look-alike element in another row, so the caller must first check that the element is still
     * the one it saw (design spec, section 9.2).
     */
    suspend fun tap(key: String): ActionResult = press(key, AccessibilityNodeInfo.ACTION_CLICK, Gestures::tap)

    /** Long-presses the element with [key], in the same way as [tap]. */
    suspend fun longPress(key: String): ActionResult = press(key, AccessibilityNodeInfo.ACTION_LONG_CLICK, Gestures::longPress)

    suspend fun tapAt(x: Int, y: Int): ActionResult = gesture(Gestures.tap(x, y))

    suspend fun longPressAt(x: Int, y: Int): ActionResult = gesture(Gestures.longPress(x, y))

    /**
     * Replaces the text of the field with [key] by [text]. Kestrel uses the set-text action and reads the text
     * back. If the app refuses the action or the field does not show the text, Kestrel types through its
     * accessibility input method instead, and from then on does so for the whole app (design spec, section 9.7).
     * Kestrel does not type into a disabled field, and a field that has gone does not count as a refusal.
     */
    suspend fun setText(key: String, text: String): ActionResult = setText(key, text, retry = true)

    private suspend fun setText(key: String, text: String, retry: Boolean): ActionResult {
        val live = snapshots.current()
        val element = live.snapshot.element(key) ?: return NOT_ON_SCREEN
        val node = live.node(key) ?: return NOT_ON_SCREEN
        if (!element.editable) return ActionResult.Failed("The element is not a text field")
        if (!element.enabled) return DISABLED
        val app = element.packageName
        if (app == null || app !in inputMethodApps) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) && node.shows(text, element.password)) {
                return settled(ActionMethod.ACTION)
            }
            if (!node.refresh()) {
                // The field has gone, so the app refused nothing: look for it again once (design spec, section 14.3).
                if (!retry) return GONE
                snapshots.invalidate()
                return setText(key, text, retry = false)
            }
            if (!node.isEnabled) return DISABLED
            if (app != null) inputMethodApps += app
        }
        return typeWithInputMethod(element, node, text)
    }

    /**
     * Scrolls the element with [key] or, when [key] is null, a list in the top app window that has one. Kestrel uses
     * the scroll action of the element or of its nearest parent that can scroll in [direction]. Otherwise it swipes
     * across the nearest list that moves along that axis. A sideways swipe across a row can archive a message or
     * start a call, so Kestrel swipes sideways only across a list that moves sideways. Up and down, it swipes across
     * the window when no list moves that way (design spec, section 9.3).
     */
    suspend fun scroll(direction: ScrollDirection, key: String? = null): ActionResult {
        val live = snapshots.current()
        val element = when (key) {
            null -> live.scrollableFor(direction)
            else -> live.snapshot.element(key) ?: return NOT_ON_SCREEN
        }
        val node = element?.let { live.node(it.key) ?: return NOT_ON_SCREEN }
        val target = node?.selfOrParent { it.scrollAction(direction) != null }
        val action = target?.node?.scrollAction(direction)
        if (target != null && action != null && target.node.performAction(action)) {
            return settled(if (target.isSelf) ActionMethod.ACTION else ActionMethod.PARENT_ACTION)
        }
        val window = live.snapshot.windowBounds(element?.windowId)
        val list = (target ?: node?.selfOrParent { it.isScrollable && it.movesAlong(direction) })?.node?.screenBounds()
        val area = (if (list != null && window != null) list.intersect(window) else list)
            ?: window?.takeIf { direction.isVertical }
            ?: return ActionResult.Failed("Nothing on the screen scrolls that way")
        val (from, to) = SwipePaths.forScroll(area, direction)
        return gesture(Gestures.swipe(from, to))
    }

    suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int): ActionResult =
        gesture(Gestures.swipe(ScreenPoint(fromX, fromY), ScreenPoint(toX, toY)))

    /** Presses and holds at the first point, then moves to the second point, as when moving an icon. */
    suspend fun drag(fromX: Int, fromY: Int, toX: Int, toY: Int): ActionResult =
        gesture(*Gestures.drag(ScreenPoint(fromX, fromY), ScreenPoint(toX, toY)))

    /** Moves two fingers apart (when [endSpan] is larger than [startSpan]) or together around a point. */
    suspend fun pinch(centerX: Int, centerY: Int, startSpan: Int, endSpan: Int): ActionResult =
        gesture(Gestures.pinch(ScreenPoint(centerX, centerY), startSpan, endSpan))

    /** How many times Android's magnification enlarges the screen; 1 when it does not. */
    val zoomScale: Float get() = service.magnificationController.magnificationConfig?.scale ?: 1f

    /** Magnifies the whole screen [scale] times around a point, with Android's magnification (design spec, section 9.3). */
    suspend fun zoom(scale: Float, centerX: Int, centerY: Int): ActionResult {
        val config = MagnificationConfig.Builder()
            .setMode(MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN)
            .setScale(scale)
            .setCenterX(centerX.toFloat())
            .setCenterY(centerY.toFloat())
            .build()
        val done = withContext(Dispatchers.Main) { service.magnificationController.setMagnificationConfig(config, false) }
        return if (done) magnified() else ActionResult.Failed("Android refused to magnify the screen")
    }

    suspend fun resetZoom(): ActionResult {
        val done = withContext(Dispatchers.Main) { service.magnificationController.resetCurrentMagnification(false) }
        return if (done) magnified() else ActionResult.Failed("Android refused to reset the magnification")
    }

    /**
     * Magnification moves every element on the screen, and Android sends no accessibility event for it, so Kestrel
     * reports the change to the tracker itself: snapshots are built anew, and the numbers and labels follow the
     * elements. It reports it again after the wait, because a snapshot built during the wait may still show the old
     * positions.
     */
    private suspend fun magnified(): ActionResult {
        tracker.onScreenChanged()
        val result = settled(ActionMethod.MAGNIFICATION)
        tracker.onScreenChanged()
        return result
    }

    suspend fun global(action: GlobalAction): ActionResult {
        return if (service.performGlobalAction(action.id)) {
            settled(ActionMethod.GLOBAL_ACTION)
        } else {
            ActionResult.Failed("Android refused the action ${action.name}")
        }
    }

    /** Opens the app with [packageName] as its launcher icon would. */
    suspend fun openApp(packageName: String): ActionResult {
        val intent = service.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ActionResult.Failed("No app with the package $packageName can be opened")
        service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return settled(ActionMethod.INTENT)
    }

    /** Opens a link or a deep link in the app that handles it. */
    suspend fun openUri(uri: String): ActionResult {
        return try {
            service.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            settled(ActionMethod.INTENT)
        } catch (e: ActivityNotFoundException) {
            ActionResult.Failed("No app can open the link")
        }
    }

    private suspend fun press(key: String, action: Int, fallback: (Int, Int) -> GestureDescription): ActionResult {
        val node = snapshots.current().node(key) ?: return NOT_ON_SCREEN
        val target = node.touchTarget(action)
        if (target != null && !target.node.isEnabled) return DISABLED
        if (target != null && target.node.offers(action)) {
            if (target.node.performAction(action)) {
                return settled(if (target.isSelf) ActionMethod.ACTION else ActionMethod.PARENT_ACTION)
            }
        } else if (node.refresh()) {
            // The element is still there, but what a touch on it reaches does not offer the action.
            val bounds = node.screenBounds()
            return gesture(fallback(bounds.centerX, bounds.centerY))
        }
        // The node has gone or refused the action, so look for the element again (design spec, section 14.3).
        snapshots.invalidate()
        val fresh = snapshots.current()
        val element = fresh.snapshot.element(key) ?: return GONE
        val freshTarget = fresh.node(key)?.touchTarget(action)
        if (freshTarget != null && !freshTarget.node.isEnabled) return DISABLED
        if (freshTarget != null && freshTarget.node.offers(action) && freshTarget.node.performAction(action)) {
            return settled(ActionMethod.RETRY_ACTION)
        }
        return gesture(fallback(element.bounds.centerX, element.bounds.centerY))
    }

    /** Focuses the field, waits until it connects to Kestrel's input method, and replaces its text. */
    private suspend fun typeWithInputMethod(element: ScreenElement, node: AccessibilityNodeInfo, text: String): ActionResult {
        val inputMethod = service.inputMethod ?: return ActionResult.Failed("Kestrel's input method is not available")
        val before = withContext(Dispatchers.Main) { inputMethod.currentInputEditorInfo }
        val wasFocused = node.isFocused
        if (!wasFocused && !node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val connection = withContext(Dispatchers.Main) {
            withTimeoutOrNull(INPUT_START_MILLIS) {
                var ready: InputMethod.AccessibilityInputConnection? = null
                while (ready == null) {
                    val editor = inputMethod.currentInputEditorInfo
                    val started = inputMethod.currentInputStarted && editor?.packageName == element.packageName &&
                        (wasFocused || editor !== before)
                    ready = if (started) inputMethod.currentInputConnection else null
                    if (ready == null) delay(POLL_MILLIS)
                }
                ready
            }
        } ?: return ActionResult.Failed("The text field did not connect to Kestrel's input method")
        // If the app has moved the focus to another field, the input would replace that field's text instead.
        if (!(node.refresh() && node.isFocused)) return ActionResult.Failed("The text field lost the focus")
        withContext(Dispatchers.Main) {
            connection.performContextMenuAction(android.R.id.selectAll)
            connection.commitText(text, 1, null)
        }
        return if (node.shows(text, element.password)) {
            settled(ActionMethod.INPUT_METHOD)
        } else {
            ActionResult.Failed("The text field did not take the text")
        }
    }

    private suspend fun gesture(vararg parts: GestureDescription): ActionResult =
        when (gestures.perform(*parts)) {
            GestureOutcome.COMPLETED -> settled(ActionMethod.GESTURE)
            GestureOutcome.CANCELLED -> ActionResult.Failed("A touch on the screen cancelled the gesture")
            GestureOutcome.REJECTED -> ActionResult.Failed("Android refused the gesture")
        }

    /**
     * Waits until the screen has been quiet after the action, but never longer than 3 s (design spec, section 9.3).
     * Call it right after the action: the quiet time counts from the call, so that it cannot run out before the app
     * has reacted. Gestures call it once the finger has been lifted.
     */
    private suspend fun settled(method: ActionMethod): ActionResult {
        tracker.awaitQuiet(ScreenChangeTracker.ACTION_QUIET_MILLIS, since = tracker.now())
        return ActionResult.Done(method)
    }

    /**
     * Whether the field shows [expected] after a short wait. A password field shows only dots, so for it the
     * action's own result has to do.
     */
    private suspend fun AccessibilityNodeInfo.shows(expected: String, password: Boolean): Boolean {
        if (password) return true
        repeat(READ_BACK_TRIES) {
            if (refresh() && shownText().orEmpty() == expected) return true
            delay(POLL_MILLIS)
        }
        return false
    }

    private class Target(val node: AccessibilityNodeInfo, val isSelf: Boolean)

    /** This node or its nearest parent, up to [MAX_PARENT_DEPTH] levels up, that [matches]. */
    private fun AccessibilityNodeInfo.selfOrParent(matches: (AccessibilityNodeInfo) -> Boolean): Target? {
        var current: AccessibilityNodeInfo? = this
        var depth = 0
        while (current != null && depth <= MAX_PARENT_DEPTH) {
            if (matches(current)) return Target(current, isSelf = depth == 0)
            current = current.parent
            depth++
        }
        return null
    }

    /**
     * The element's node or its nearest parent that handles touches. A finger's touch stops at that view even when it
     * is disabled, so Kestrel's action stops there too.
     */
    private fun AccessibilityNodeInfo.touchTarget(action: Int): Target? =
        selfOrParent { it.isClickable || it.isLongClickable || it.offers(action) }

    private fun AccessibilityNodeInfo.offers(action: Int): Boolean = actionList.any { it.id == action }

    /** The first action that scrolls this node in [direction], if the node offers one and is enabled. */
    private fun AccessibilityNodeInfo.scrollAction(direction: ScrollDirection): Int? {
        if (!isEnabled) return null
        val offered = offeredActions()
        return ScrollActions.forDirection(direction, scrollAxis(offered)).firstOrNull { it in offered }
    }

    /** Whether a swipe in [direction] may cross this list (see [ScrollActions.movesAlong]). */
    private fun AccessibilityNodeInfo.movesAlong(direction: ScrollDirection): Boolean =
        ScrollActions.movesAlong(scrollAxis(offeredActions()), direction)

    private fun AccessibilityNodeInfo.offeredActions(): Set<Int> = actionList.mapTo(HashSet()) { it.id }

    private fun AccessibilityNodeInfo.scrollAxis(offered: Set<Int>): ScrollAxis =
        ScrollActions.axis(offered, className?.toString(), collectionInfo?.rowCount, collectionInfo?.columnCount)

    private fun AccessibilityNodeInfo.screenBounds(): IntRect = Rect().also(::getBoundsInScreen).let {
        IntRect(it.left, it.top, it.right, it.bottom)
    }

    /**
     * The list to scroll when the owner names none: in the top app window that has a list, the largest list that can
     * scroll in [direction], or else the largest that moves along that axis, or else the largest list. The snapshot
     * lists its windows from the top down.
     */
    private fun LiveSnapshot.scrollableFor(direction: ScrollDirection): ScreenElement? {
        for (window in snapshot.windows.filter { it.kind == WindowKind.APPLICATION }) {
            val lists = snapshot.elements.filter { it.scrollable && it.enabled && it.windowId == window.id }
            if (lists.isEmpty()) continue
            val canScroll = lists.filter { node(it.key)?.scrollAction(direction) != null }
            val alongTheAxis = lists.filter { node(it.key)?.movesAlong(direction) == true }
            return canScroll.ifEmpty { alongTheAxis }.ifEmpty { lists }.maxByOrNull { it.bounds.width.toLong() * it.bounds.height }
        }
        return null
    }

    /** The bounds of the window with [windowId], or of the top app window when there is no such window. */
    private fun ScreenSnapshot.windowBounds(windowId: Int?): IntRect? =
        (windows.firstOrNull { it.id == windowId } ?: windows.firstOrNull { it.kind == WindowKind.APPLICATION })?.bounds

    private companion object {
        const val MAX_PARENT_DEPTH = 20
        val NOT_ON_SCREEN = ActionResult.Failed("The element is not on the screen")
        val DISABLED = ActionResult.Failed("The element is disabled")
        val GONE = ActionResult.Failed("The element is no longer on the screen")
        const val INPUT_START_MILLIS = 2_000L
        const val READ_BACK_TRIES = 5
        const val POLL_MILLIS = 50L
    }
}
```

Replace the whole content of `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt` with:

```kotlin
package dev.kestrel.screen

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import dev.kestrel.screen.act.GestureRunner
import dev.kestrel.screen.act.ScreenActions
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.overlay.Badge
import dev.kestrel.screen.overlay.ConfirmationCard
import dev.kestrel.screen.overlay.FeedbackBar
import dev.kestrel.screen.overlay.Grid
import dev.kestrel.screen.overlay.GridOverlay
import dev.kestrel.screen.overlay.LabelsOverlay
import dev.kestrel.screen.overlay.Numbering
import dev.kestrel.screen.overlay.NumbersMode
import dev.kestrel.screen.overlay.NumbersOverlay
import dev.kestrel.screen.overlay.OverlayHost
import dev.kestrel.screen.overlay.TouchWatcher
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.ScreenEvents
import dev.kestrel.screen.read.ScreenReader
import dev.kestrel.screen.read.SnapshotCache
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Kestrel's screen layer while its accessibility service is connected (design spec, section 9). [errors] handles the
 * failures of the session's background work, and it may write only class names and stack frames (design spec, section
 * 13.5).
 */
class ScreenSession(service: AccessibilityService, private val errors: CoroutineExceptionHandler) {
    private val reader = ScreenReader(service)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errors)
    private val overlays = OverlayHost(service)
    private val numbers = NumbersOverlay(overlays)
    private val labels = LabelsOverlay(overlays)
    private val gridOverlay = GridOverlay(overlays)
    private val feedback = FeedbackBar(overlays)
    private val card = ConfirmationCard(overlays)
    internal val tracker = ScreenChangeTracker(SystemClock::uptimeMillis)
    internal val gestures = GestureRunner(service)
    private val watcher = TouchWatcher(overlays, gestures)

    /** The current snapshot. A new one is built only after the screen has changed. */
    val snapshots = SnapshotCache(tracker) { withContext(Dispatchers.IO) { reader.read() } }

    val actions = ScreenActions(service, snapshots, gestures, tracker)

    // These fields change only on the main thread.
    /** Set while "show numbers" is on: the numbers then follow the screen. "Which one?" leaves it set. */
    private var numbersMode: NumbersMode? = null

    /** The screen content when the labels appeared. They go when it changes. */
    private var labelsShownOn: List<Pair<String, IntRect>>? = null

    /** The screen content when the "Which one?" numbers appeared. They go when it changes. */
    private var whichOneShownOn: List<Pair<String, IntRect>>? = null

    /** The numbers on the screen now, for commands such as "tap 5". */
    val numberBadges: List<Badge> get() = numbers.badges

    val labelsShowing: Boolean get() = labels.isShowing

    /** The grid on the screen now, or null. "Tap 5" taps the centre of the grid's cell 5. */
    val grid: Grid? get() = gridOverlay.grid

    /** The feedback bar's text now, or null while the bar is hidden. */
    val feedbackText: String? get() = feedback.text

    /** Emits when the owner touches the screen while the touch watcher runs (design spec, section 14.1). */
    val ownerTouches: SharedFlow<Unit> get() = watcher.ownerTouches

    init {
        scope.launch {
            // collect, not collectLatest: on a screen that changes all the time, such as a stopwatch, collectLatest would
            // cancel every check before it ran. The version is a state, so the check after a slow one sees the latest
            // version, and the snapshot cache waits until the screen is quiet, but never longer than 3 s.
            tracker.version.collect {
                if (numbersMode == null && labelsShownOn == null && whichOneShownOn == null) return@collect
                // A failed check must not end the watcher, or the numbers and labels would stop following the screen.
                // The next screen change runs the next check.
                try {
                    onScreenSettled(snapshots.current().snapshot)
                } catch (e: CancellationException) {
                    // Only the watcher's own cancellation ends it. A check that something inside it cancelled, such as
                    // a timeout, failed like any other check.
                    currentCoroutineContext().ensureActive()
                    errors.handleException(currentCoroutineContext(), e)
                } catch (e: Exception) {
                    errors.handleException(currentCoroutineContext(), e)
                }
            }
        }
    }

    /** Shows numbers on the actionable elements. They follow the screen until [hideNumbers] (design spec, section 9.4). */
    suspend fun showNumbers(mode: NumbersMode = NumbersMode.ALL) = withContext(Dispatchers.Main) {
        whichOneShownOn = null
        numbersMode = mode
        numbers.show(Numbering.forScreen(snapshots.current().snapshot, mode))
    }

    /**
     * Shows numbers on the elements with [keys] only, for "Which one?". They go when the screen content changes, and
     * then the numbers of "show numbers" come back if it is on.
     */
    suspend fun showWhichOne(keys: Collection<String>) = withContext(Dispatchers.Main) {
        val snapshot = snapshots.current().snapshot
        whichOneShownOn = contentOf(snapshot)
        numbers.show(Numbering.forCandidates(snapshot.elements.filter { it.key in keys }))
    }

    suspend fun hideNumbers() = withContext(Dispatchers.Main) {
        numbersMode = null
        whichOneShownOn = null
        numbers.hide()
    }

    /** Shows names next to the actionable elements until the screen content changes (design spec, section 9.4). */
    suspend fun showLabels() = withContext(Dispatchers.Main) {
        val snapshot = snapshots.current().snapshot
        labelsShownOn = contentOf(snapshot)
        labels.show(snapshot.elements.filter { it.isActionable })
    }

    suspend fun hideLabels() = withContext(Dispatchers.Main) {
        labelsShownOn = null
        labels.hide()
    }

    /** Shows [grid], by default one over the whole screen. "More squares" shows `grid.more()` (design spec, section 9.4). */
    suspend fun showGrid(grid: Grid? = null) = withContext(Dispatchers.Main) {
        gridOverlay.show(grid ?: Grid.over(overlays.screenBounds()))
    }

    suspend fun hideGrid() = withContext(Dispatchers.Main) { gridOverlay.hide() }

    /** Shows [text] on the feedback bar, which fades after a few seconds (design spec, section 8.9). */
    suspend fun showFeedback(text: String) = withContext(Dispatchers.Main) { feedback.show(text) }

    /**
     * Asks [question] on a confirmation card and returns true when the owner taps Confirm. It returns false when the
     * owner taps Cancel, when a newer question replaces this one or when the session closes, and the card then goes at
     * once. A caller that also listens for "confirm" or "cancel", or that gives up after 15 s, cancels this call, which
     * removes the card too (design spec, section 12.4).
     */
    suspend fun confirm(question: String): Boolean = withContext(Dispatchers.Main) { card.ask(question) }

    /** Starts reporting the owner's touches in [ownerTouches], for example while a task runs. */
    suspend fun startTouchWatcher() = withContext(Dispatchers.Main) { watcher.start() }

    suspend fun stopTouchWatcher() = withContext(Dispatchers.Main) { watcher.stop() }

    /** Kestrel's accessibility service passes every event here. */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (ScreenEvents.changesScreen(event.eventType)) tracker.onScreenChanged()
    }

    /**
     * Removes the overlays and stops the session's work. The service calls it on the main thread when it disconnects.
     * It may be called more than once.
     */
    fun close() {
        scope.cancel()
        card.dismiss()
        overlays.hideAll()
    }

    private fun onScreenSettled(snapshot: ScreenSnapshot) {
        val content = contentOf(snapshot)
        if (labelsShownOn != null && content != labelsShownOn) {
            labelsShownOn = null
            labels.hide()
        }
        if (whichOneShownOn != null) {
            if (content == whichOneShownOn) return
            // The candidates belong to the screen on which "Which one?" was asked.
            whichOneShownOn = null
            if (numbersMode == null) numbers.hide()
        }
        numbersMode?.let { mode ->
            val badges = Numbering.forScreen(snapshot, mode)
            if (badges != numbers.badges) numbers.show(badges)
        }
    }

    /** What the overlays depend on: the actionable elements and where they are. Kestrel's own overlays are not part of it. */
    private fun contentOf(snapshot: ScreenSnapshot) = snapshot.elements.filter { it.isActionable }.map { it.key to it.bounds }
}
```

Replace the whole content of `app/src/androidTest/kotlin/dev/kestrel/app/screen/OverlayTest.kt` with:

```kotlin
package dev.kestrel.app.screen

import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.app.screen.ScreenServiceRule.Companion.KESTREL
import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.overlay.NumbersMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val session get() = screen.session

    private suspend fun eventually(timeoutMillis: Long = 5_000, check: suspend () -> Boolean) = withTimeout(timeoutMillis) {
        while (!check()) delay(100)
    }

    private suspend fun keyOf(label: String) = screen.awaitElement { it.label == label }.key

    private suspend fun showDialog() {
        session.actions.tap(keyOf("SHOW DIALOG"))
        screen.awaitElement { it.label == "Delete draft?" }
    }

    @Test
    fun numbersFollowAZoom() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val send = keyOf("SEND")
        session.showNumbers()
        eventually { session.numberBadges.any { it.key == send } }
        val before = session.numberBadges.first { it.key == send }.bounds

        try {
            assertEquals(ActionResult.Done(ActionMethod.MAGNIFICATION), session.actions.zoom(2f, before.centerX, before.centerY))
            eventually { session.numberBadges.firstOrNull { it.key == send }?.let { it.bounds.height > before.height } == true }
            val zoomed = session.numberBadges.first { it.key == send }.bounds

            // A change of scale while the screen is magnified adds no window, so Android sends no event for it: only
            // Kestrel's own report makes the numbers follow. The button is as wide as the screen, so the height is compared.
            // The centre is given in the screen's own, unmagnified coordinates.
            assertEquals(ActionResult.Done(ActionMethod.MAGNIFICATION), session.actions.zoom(3f, before.centerX, before.centerY))
            eventually { session.numberBadges.firstOrNull { it.key == send }?.let { it.bounds.height > zoomed.height } == true }
        } finally {
            session.actions.resetZoom()
        }
    }

    @Test
    fun numbersGoOnTheActionableElementsInReadingOrder() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        session.showNumbers()

        val badges = session.numberBadges
        assertEquals(keyOf("SEND"), badges.first().key)
        assertEquals((1..badges.size).toList(), badges.map { it.number })
        eventually { screen.showsWindow("Kestrel numbers") }
        assertTrue(session.snapshots.current().snapshot.windows.none { it.packageName == KESTREL })
    }

    @Test
    fun numbersCanBeHiddenRightAfterTheyAreShown() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        // Android attaches a new overlay's view only on the next frame. Showing and hiding the numbers in one go on the
        // main thread hides them before that frame, and the window must still go.
        repeat(2) {
            withContext(Dispatchers.Main) {
                session.showNumbers()
                session.hideNumbers()
            }
        }
        eventually { !screen.showsWindow("Kestrel numbers") }
    }

    @Test
    fun unlabeledModeNumbersOnlyTheIconWithoutAName() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        session.showNumbers(NumbersMode.UNLABELED)

        val icon = screen.awaitElement { it.viewId == "$FIXTURES:id/compose_button" }
        assertEquals(listOf(icon.key), session.numberBadges.map { it.key })
    }

    @Test
    fun touchesReachTheAppWhileNumbersAreShown() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        session.showNumbers()
        val target = screen.awaitElement { it.label == "Touch target" }

        assertEquals(ActionResult.Done(ActionMethod.GESTURE), session.actions.tapAt(target.bounds.centerX, target.bounds.centerY))
        screen.awaitElement { it.label == "Touched target" }
        Unit
    }

    @Test
    fun numbersFollowTheScreenUntilTheyAreHidden() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        session.showNumbers()

        showDialog()

        val keep = keyOf("KEEP")
        eventually { session.numberBadges.any { it.key == keep } }
        session.hideNumbers()
        eventually { !screen.showsWindow("Kestrel numbers") }
    }

    @Test
    fun whichOneNumbersOnlyTheCandidatesAndGoesWhenTheScreenChanges() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val send = keyOf("SEND")
        val rebuild = keyOf("REBUILD")

        session.showWhichOne(listOf(rebuild, send))

        assertEquals(listOf(1 to send, 2 to rebuild), session.numberBadges.map { it.number to it.key })
        showDialog()
        eventually { session.numberBadges.isEmpty() && !screen.showsWindow("Kestrel numbers") }
    }

    @Test
    fun numbersComeBackWhenWhichOneGoes() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        session.showNumbers()
        val send = keyOf("SEND")
        val rebuild = keyOf("REBUILD")

        session.showWhichOne(listOf(rebuild, send))
        assertEquals(2, session.numberBadges.size)
        showDialog()

        // "Which one?" belonged to the old screen; "show numbers" is still on, so numbers follow the new screen.
        val keep = keyOf("KEEP")
        eventually { session.numberBadges.any { it.key == keep } }
    }

    @Test
    fun labelsGoWhenTheScreenChanges() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        session.showLabels()

        assertTrue(session.labelsShowing)
        eventually { screen.showsWindow("Kestrel labels") }
        showDialog()
        eventually { !session.labelsShowing && !screen.showsWindow("Kestrel labels") }
    }

    @Test
    fun labelsGoWhenTheScreenChangesEvenIfItIsNeverQuiet() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val showDialog = keyOf("SHOW DIALOG")
        session.showLabels()
        eventually { screen.showsWindow("Kestrel labels") }

        // From now on the screen reports a change every 50 ms, as a stopwatch or a progress bar would. Every snapshot
        // and every action then waits the full 3 s for quiet, so this test waits longer.
        val busy = launch(Dispatchers.Default) {
            while (true) {
                session.onAccessibilityEvent(AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
                delay(50)
            }
        }
        try {
            session.actions.tap(showDialog)
            eventually(timeoutMillis = 20_000) { !session.labelsShowing }
        } finally {
            busy.cancel()
        }
    }

    @Test
    fun turningTheServiceOffRemovesTheOverlays() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        session.showNumbers()
        eventually { screen.showsWindow("Kestrel numbers") }

        screen.shell("settings delete secure enabled_accessibility_services")

        eventually { !screen.showsWindow("Kestrel numbers") }
    }
}
```

The new test `numbersFollowAZoom` zooms in twice and checks that the numbers follow. On `kestrel-api37` it also passes without the change to `ScreenActions`, because Android sends a windows-changed event a moment after a zoom; it guards the A71 and later versions, which need not send that event.

- [ ] **Step 6: Run the checks and all device tests**

```powershell
scripts\gradle.ps1 check kestrelAudit
scripts\connected-test.ps1 -Serial <serial> :core:privacy:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL` twice; 7 device tests in `:core:privacy` and 70 in `:app`.

- [ ] **Step 7: Commit**

```powershell
git add core/screen app/src/androidTest
git commit -m "fix(screen): wait for a quiet screen outside the snapshot lock, and follow zooms" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: Gestures that follow Android's settings and never hang

**Files:**
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/act/GestureRunner.kt` (a time limit; `TIMED_OUT`)
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/act/Gestures.kt` (the long press and the drag follow Android's settings)
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/act/ScreenActions.kt` (points outside the screen fail)
- Modify: `app/src/androidTest/kotlin/dev/kestrel/app/screen/GestureTest.kt`

**Interfaces:**
- Consumes: Task 4's `ScreenActions`.
- Produces: `GestureOutcome.TIMED_OUT`; `Gestures.drag(from: ScreenPoint, to: ScreenPoint, driftPixels: Int)`; an action at a point outside the screen returns `ActionResult.Failed("The point is outside the screen")` instead of throwing.

Closes (6g), (6h), (6j), (8c), (8e), (8f) and (11c). (8d), a finger that a failed drag leaves on the screen, stays open: it needs Android to reject a valid continuation, or to cancel it, in the moment between the parts, and a generic lift must know whether the move was sent.

A gesture whose answer never came made the caller wait forever; each part now waits at most its duration plus 2 s and then counts as `TIMED_OUT`. The screen counts as busy until the gesture's planned end plus a short settling time, so that the touch watcher does not take Kestrel's own gesture for the owner's. A long press lasts Android's touch-and-hold delay plus 400 ms, because the owner can set that delay up to 1500 ms, and the old fixed 800 ms then became a tap. The hold of a drag drifts by half of Android's touch slop instead of a fixed 10 pixels. A point outside the screen made Android throw "Path bounds must not be negative"; the action now fails with a reason.

- [ ] **Step 1: Write the failing tests**

Replace the whole content of `app/src/androidTest/kotlin/dev/kestrel/app/screen/GestureTest.kt` with:

```kotlin
package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.model.ScreenElement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GestureTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val actions get() = screen.session.actions
    private val done = ActionResult.Done(ActionMethod.GESTURE)

    private fun openPad(): ScreenElement {
        screen.open("GesturesActivity", "Gestures fixture")
        return runBlocking { screen.awaitElement { it.label == "Gesture pad" } }
    }

    private suspend fun padReports(gesture: String) = screen.awaitElement { it.label == gesture }

    /** Where the current snapshot shows the pad, without waiting for the screen to change. */
    private suspend fun padNow() = screen.session.snapshots.current().snapshot.elements.single { it.label == "Gesture pad" }.bounds

    @Test
    fun tapAndLongPressAtAPoint() = runBlocking {
        val pad = openPad().bounds

        assertEquals(done, actions.tapAt(pad.centerX, pad.centerY))
        padReports("Tap")
        assertEquals(done, actions.longPressAt(pad.centerX, pad.centerY))
        padReports("Long press")
        Unit
    }

    @Test
    fun aLongPressLastsLongerThanAndroidsTouchAndHoldDelay() = runBlocking {
        val original = screen.shell("settings get secure long_press_timeout").trim()
        try {
            // The longest touch-and-hold delay that Android's accessibility settings offer.
            screen.shell("settings put secure long_press_timeout 1500")
            screen.open("ViewsActivity", "Views fixture")
            val send = screen.awaitElement { it.label == "SEND" && it.clickable }.bounds

            assertEquals(done, actions.longPressAt(send.centerX, send.centerY))

            screen.awaitElement { it.label == "Long-pressed Send" }
            Unit
        } finally {
            if (original == "null") {
                screen.shell("settings delete secure long_press_timeout")
            } else {
                screen.shell("settings put secure long_press_timeout $original")
            }
        }
    }

    @Test
    fun aPointOutsideTheScreenFailsInsteadOfThrowing() = runBlocking {
        openPad()

        assertEquals(ActionResult.Failed("The point is outside the screen"), actions.tapAt(-5, 100))
    }

    @Test
    fun swipeAndDragAreToldApart() = runBlocking {
        val pad = openPad().bounds

        assertEquals(done, actions.swipe(pad.centerX, pad.centerY + 300, pad.centerX, pad.centerY - 300))
        padReports("Swipe up")
        assertEquals(done, actions.drag(pad.centerX - 300, pad.centerY, pad.centerX + 300, pad.centerY))
        padReports("Drag right")
        Unit
    }

    @Test
    fun pinchOutAndIn() = runBlocking {
        val pad = openPad().bounds

        assertEquals(done, actions.pinch(pad.centerX, pad.centerY, startSpan = 200, endSpan = 700))
        padReports("Pinch out")
        assertEquals(done, actions.pinch(pad.centerX, pad.centerY, startSpan = 700, endSpan = 200))
        padReports("Pinch in")
        Unit
    }

    @Test
    fun zoomMagnifiesTheScreenAndResetZoomUndoesIt() = runBlocking {
        val pad = openPad().bounds

        assertEquals(ActionResult.Done(ActionMethod.MAGNIFICATION), actions.zoom(2f, pad.centerX, pad.centerY))
        assertEquals(2f, actions.zoomScale)
        // Magnification moves every element, so the next snapshot must show the pad at its magnified size. The width
        // is compared because the fixture's layout may still move the pad up or down a little after it opens.
        assertNotEquals(pad.width, padNow().width)
        assertEquals(ActionResult.Done(ActionMethod.MAGNIFICATION), actions.resetZoom())
        assertEquals(1f, actions.zoomScale)
        assertEquals(pad.width, padNow().width)
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

```powershell
scripts\connected-test.ps1 -Serial <serial> :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.screen.GestureTest'
```

Expected: `aPointOutsideTheScreenFailsInsteadOfThrowing` fails with `IllegalArgumentException: Path bounds must not be negative`, and `aLongPressLastsLongerThanAndroidsTouchAndHoldDelay` fails because the screen shows "Clicked Send": with a touch-and-hold delay of 1500 ms, the old long press is a tap. The test sets Android's delay and restores it in `finally`.

- [ ] **Step 3: Change the gestures**

Replace the whole content of `core/screen/src/main/kotlin/dev/kestrel/screen/act/GestureRunner.kt` with:

```kotlin
package dev.kestrel.screen.act

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** What became of a gesture that Kestrel dispatched. [TIMED_OUT]: Android did not report its end in time. */
enum class GestureOutcome { COMPLETED, CANCELLED, REJECTED, TIMED_OUT }

/**
 * Runs Kestrel's own gestures one at a time (design spec, section 9.3), and remembers when they run, so that the
 * touch watcher can tell them apart from the owner's touches (section 14.1).
 */
class GestureRunner(private val service: AccessibilityService) {
    private val mutex = Mutex()

    @Volatile
    private var busyUntil = 0L

    /** Whether one of Kestrel's gestures is running, or ended less than [SETTLE_MILLIS] ago. */
    fun isRunning(): Boolean = SystemClock.uptimeMillis() < busyUntil

    /**
     * Runs the [parts] of one gesture in order, such as the hold and the move of a drag, and stops at a failed part.
     * When the caller cancels it, Android still runs the part that it has started, so the gesture counts as running
     * until that part's planned end.
     */
    suspend fun perform(vararg parts: GestureDescription): GestureOutcome = mutex.withLock {
        require(parts.isNotEmpty()) { "A gesture needs at least one part" }
        busyUntil = Long.MAX_VALUE
        var plannedEnd = 0L
        try {
            var outcome = GestureOutcome.COMPLETED
            for (part in parts) {
                plannedEnd = SystemClock.uptimeMillis() + part.durationMillis()
                outcome = dispatch(part)
                if (outcome != GestureOutcome.COMPLETED) break
            }
            outcome
        } finally {
            busyUntil = maxOf(SystemClock.uptimeMillis(), plannedEnd) + SETTLE_MILLIS
        }
    }

    /** Waits for Android's answer, but not for ever: a lost answer would otherwise keep the lock for good. */
    private suspend fun dispatch(gesture: GestureDescription): GestureOutcome = withContext(Dispatchers.Main) {
        withTimeoutOrNull(gesture.durationMillis() + ANSWER_MARGIN_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) = continuation.resume(GestureOutcome.COMPLETED)

                    override fun onCancelled(gestureDescription: GestureDescription) = continuation.resume(GestureOutcome.CANCELLED)
                }
                if (!service.dispatchGesture(gesture, callback, null)) continuation.resume(GestureOutcome.REJECTED)
            }
        } ?: GestureOutcome.TIMED_OUT
    }

    private fun GestureDescription.durationMillis(): Long =
        (0 until strokeCount).maxOf { getStroke(it).let { stroke -> stroke.startTime + stroke.duration } }

    companion object {
        /** How long after one of Kestrel's gestures its touches may still arrive at the touch watcher. */
        const val SETTLE_MILLIS = 100L

        /** How long after a gesture's planned end Kestrel still waits for Android's answer. */
        const val ANSWER_MARGIN_MILLIS = 2_000L
    }
}
```

Replace the whole content of `core/screen/src/main/kotlin/dev/kestrel/screen/act/Gestures.kt` with:

```kotlin
package dev.kestrel.screen.act

import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.view.ViewConfiguration

/** Builds Kestrel's gestures. Coordinates are screen pixels (design spec, section 9.3). */
internal object Gestures {
    private const val TAP_MILLIS = 50L
    private const val SWIPE_MILLIS = 500L
    private const val DRAG_MOVE_MILLIS = 600L
    private const val PINCH_MILLIS = 600L

    /** How much longer than Android's touch-and-hold delay a long press lasts. */
    private const val LONG_PRESS_MARGIN_MILLIS = 400L

    fun tap(x: Int, y: Int): GestureDescription = gesture(StrokeDescription(point(x, y), 0, TAP_MILLIS))

    fun longPress(x: Int, y: Int): GestureDescription = gesture(StrokeDescription(point(x, y), 0, longPressMillis()))

    fun swipe(from: ScreenPoint, to: ScreenPoint, durationMillis: Long = SWIPE_MILLIS): GestureDescription =
        gesture(StrokeDescription(line(from, to), 0, durationMillis))

    /**
     * A drag is a long press that continues into a move, so it is dispatched in two parts. Android ends a
     * continuing stroke at its last change of position, so the press drifts slowly by [driftPixels], less than a
     * touch slop, to last its whole time.
     */
    fun drag(from: ScreenPoint, to: ScreenPoint, driftPixels: Int): Array<GestureDescription> {
        val pressed = ScreenPoint(from.x + driftPixels, from.y)
        val hold = StrokeDescription(line(from, pressed), 0, longPressMillis(), true)
        val move = hold.continueStroke(line(pressed, to), 0, DRAG_MOVE_MILLIS, false)
        return arrayOf(gesture(hold), gesture(move))
    }

    /** Two fingers that move apart ([endSpan] larger than [startSpan]) or together, side by side around [center]. */
    fun pinch(center: ScreenPoint, startSpan: Int, endSpan: Int): GestureDescription {
        fun finger(side: Int) = StrokeDescription(
            line(ScreenPoint(center.x + side * startSpan / 2, center.y), ScreenPoint(center.x + side * endSpan / 2, center.y)),
            0,
            PINCH_MILLIS,
        )
        return GestureDescription.Builder().addStroke(finger(-1)).addStroke(finger(1)).build()
    }

    /** The owner can make Android's touch-and-hold delay as long as 1.5 s in the accessibility settings. */
    private fun longPressMillis(): Long = ViewConfiguration.getLongPressTimeout() + LONG_PRESS_MARGIN_MILLIS

    private fun point(x: Int, y: Int) = Path().apply { moveTo(x.toFloat(), y.toFloat()) }

    private fun line(from: ScreenPoint, to: ScreenPoint) = Path().apply {
        moveTo(from.x.toFloat(), from.y.toFloat())
        lineTo(to.x.toFloat(), to.y.toFloat())
    }

    private fun gesture(stroke: StrokeDescription): GestureDescription = GestureDescription.Builder().addStroke(stroke).build()
}
```

Replace the whole content of `core/screen/src/main/kotlin/dev/kestrel/screen/act/ScreenActions.kt` with:

```kotlin
package dev.kestrel.screen.act

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.InputMethod
import android.accessibilityservice.MagnificationConfig
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.read.LiveSnapshot
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.SnapshotCache
import dev.kestrel.screen.read.shownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Kestrel's actions on the screen. Each one has a fallback (design spec, sections 9.3 and 14.3). They query and act on
 * nodes with blocking binder calls on the caller's dispatcher, so callers run them on `Dispatchers.IO`.
 */
class ScreenActions internal constructor(
    private val service: AccessibilityService,
    private val snapshots: SnapshotCache<LiveSnapshot>,
    private val gestures: GestureRunner,
    private val tracker: ScreenChangeTracker,
) {
    /** Apps that refused the set-text action or did not show the text afterwards (design spec, section 9.7). */
    private val inputMethodApps = ConcurrentHashMap.newKeySet<String>()

    /**
     * Taps the element with [key]. Kestrel looks the key up in the current snapshot, never in an older one, because a
     * list may have reused an old view for another item. It uses the click action of the element or of its nearest
     * parent that handles touches. If that node has gone or refuses the click, Kestrel looks for the element again in
     * a fresh snapshot, and if nothing offers a click, it taps the element's centre. It does not tap a disabled
     * element, because a finger's touch would stop there too.
     *
     * A key names a position, not an identity. After a list has added, removed or scrolled rows, a key from an older
     * snapshot can name a look-alike element in another row, so the caller must first check that the element is still
     * the one it saw (design spec, section 9.2).
     */
    suspend fun tap(key: String): ActionResult = press(key, AccessibilityNodeInfo.ACTION_CLICK, Gestures::tap)

    /** Long-presses the element with [key], in the same way as [tap]. */
    suspend fun longPress(key: String): ActionResult = press(key, AccessibilityNodeInfo.ACTION_LONG_CLICK, Gestures::longPress)

    suspend fun tapAt(x: Int, y: Int): ActionResult = gestureAt(ScreenPoint(x, y), Gestures::tap)

    suspend fun longPressAt(x: Int, y: Int): ActionResult = gestureAt(ScreenPoint(x, y), Gestures::longPress)

    /**
     * Replaces the text of the field with [key] by [text]. Kestrel uses the set-text action and reads the text
     * back. If the app refuses the action or the field does not show the text, Kestrel types through its
     * accessibility input method instead, and from then on does so for the whole app (design spec, section 9.7).
     * Kestrel does not type into a disabled field, and a field that has gone does not count as a refusal.
     */
    suspend fun setText(key: String, text: String): ActionResult = setText(key, text, retry = true)

    private suspend fun setText(key: String, text: String, retry: Boolean): ActionResult {
        val live = snapshots.current()
        val element = live.snapshot.element(key) ?: return NOT_ON_SCREEN
        val node = live.node(key) ?: return NOT_ON_SCREEN
        if (!element.editable) return ActionResult.Failed("The element is not a text field")
        if (!element.enabled) return DISABLED
        val app = element.packageName
        if (app == null || app !in inputMethodApps) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) && node.shows(text, element.password)) {
                return settled(ActionMethod.ACTION)
            }
            if (!node.refresh()) {
                // The field has gone, so the app refused nothing: look for it again once (design spec, section 14.3).
                if (!retry) return GONE
                snapshots.invalidate()
                return setText(key, text, retry = false)
            }
            if (!node.isEnabled) return DISABLED
            if (app != null) inputMethodApps += app
        }
        return typeWithInputMethod(element, node, text)
    }

    /**
     * Scrolls the element with [key] or, when [key] is null, a list in the top app window that has one. Kestrel uses
     * the scroll action of the element or of its nearest parent that can scroll in [direction]. Otherwise it swipes
     * across the nearest list that moves along that axis. A sideways swipe across a row can archive a message or
     * start a call, so Kestrel swipes sideways only across a list that moves sideways. Up and down, it swipes across
     * the window when no list moves that way (design spec, section 9.3).
     */
    suspend fun scroll(direction: ScrollDirection, key: String? = null): ActionResult {
        val live = snapshots.current()
        val element = when (key) {
            null -> live.scrollableFor(direction)
            else -> live.snapshot.element(key) ?: return NOT_ON_SCREEN
        }
        val node = element?.let { live.node(it.key) ?: return NOT_ON_SCREEN }
        val target = node?.selfOrParent { it.scrollAction(direction) != null }
        val action = target?.node?.scrollAction(direction)
        if (target != null && action != null && target.node.performAction(action)) {
            return settled(if (target.isSelf) ActionMethod.ACTION else ActionMethod.PARENT_ACTION)
        }
        val window = live.snapshot.windowBounds(element?.windowId)
        val list = (target ?: node?.selfOrParent { it.isScrollable && it.movesAlong(direction) })?.node?.screenBounds()
        val area = (if (list != null && window != null) list.intersect(window) else list)
            ?: window?.takeIf { direction.isVertical }
            ?: return ActionResult.Failed("Nothing on the screen scrolls that way")
        val (from, to) = SwipePaths.forScroll(area, direction)
        return gesture(listOf(from, to)) { arrayOf(Gestures.swipe(from, to)) }
    }

    suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int): ActionResult {
        val from = ScreenPoint(fromX, fromY)
        val to = ScreenPoint(toX, toY)
        return gesture(listOf(from, to)) { arrayOf(Gestures.swipe(from, to)) }
    }

    /** Presses and holds at the first point, then moves to the second point, as when moving an icon. */
    suspend fun drag(fromX: Int, fromY: Int, toX: Int, toY: Int): ActionResult {
        val from = ScreenPoint(fromX, fromY)
        val to = ScreenPoint(toX, toY)
        // Half the touch slop, so that the press still counts as a press while it drifts.
        val drift = maxOf(1, ViewConfiguration.get(service).scaledTouchSlop / 2)
        return gesture(listOf(from, ScreenPoint(fromX + drift, fromY), to)) { Gestures.drag(from, to, drift) }
    }

    /** Moves two fingers apart (when [endSpan] is larger than [startSpan]) or together around a point. */
    suspend fun pinch(centerX: Int, centerY: Int, startSpan: Int, endSpan: Int): ActionResult {
        val fingers = listOf(startSpan, endSpan).flatMap { span ->
            listOf(ScreenPoint(centerX - span / 2, centerY), ScreenPoint(centerX + span / 2, centerY))
        }
        return gesture(fingers) { arrayOf(Gestures.pinch(ScreenPoint(centerX, centerY), startSpan, endSpan)) }
    }

    /** How many times Android's magnification enlarges the screen; 1 when it does not. */
    val zoomScale: Float get() = service.magnificationController.magnificationConfig?.scale ?: 1f

    /** Magnifies the whole screen [scale] times around a point, with Android's magnification (design spec, section 9.3). */
    suspend fun zoom(scale: Float, centerX: Int, centerY: Int): ActionResult {
        val config = MagnificationConfig.Builder()
            .setMode(MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN)
            .setScale(scale)
            .setCenterX(centerX.toFloat())
            .setCenterY(centerY.toFloat())
            .build()
        val done = withContext(Dispatchers.Main) { service.magnificationController.setMagnificationConfig(config, false) }
        return if (done) magnified() else ActionResult.Failed("Android refused to magnify the screen")
    }

    suspend fun resetZoom(): ActionResult {
        val done = withContext(Dispatchers.Main) { service.magnificationController.resetCurrentMagnification(false) }
        return if (done) magnified() else ActionResult.Failed("Android refused to reset the magnification")
    }

    /**
     * Magnification moves every element on the screen, and Android sends no accessibility event for it, so Kestrel
     * reports the change to the tracker itself: snapshots are built anew, and the numbers and labels follow the
     * elements. It reports it again after the wait, because a snapshot built during the wait may still show the old
     * positions.
     */
    private suspend fun magnified(): ActionResult {
        tracker.onScreenChanged()
        val result = settled(ActionMethod.MAGNIFICATION)
        tracker.onScreenChanged()
        return result
    }

    suspend fun global(action: GlobalAction): ActionResult {
        return if (service.performGlobalAction(action.id)) {
            settled(ActionMethod.GLOBAL_ACTION)
        } else {
            ActionResult.Failed("Android refused the action ${action.name}")
        }
    }

    /** Opens the app with [packageName] as its launcher icon would. */
    suspend fun openApp(packageName: String): ActionResult {
        val intent = service.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ActionResult.Failed("No app with the package $packageName can be opened")
        service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return settled(ActionMethod.INTENT)
    }

    /** Opens a link or a deep link in the app that handles it. */
    suspend fun openUri(uri: String): ActionResult {
        return try {
            service.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            settled(ActionMethod.INTENT)
        } catch (e: ActivityNotFoundException) {
            ActionResult.Failed("No app can open the link")
        }
    }

    private suspend fun press(key: String, action: Int, fallback: (Int, Int) -> GestureDescription): ActionResult {
        val node = snapshots.current().node(key) ?: return NOT_ON_SCREEN
        val target = node.touchTarget(action)
        if (target != null && !target.node.isEnabled) return DISABLED
        if (target != null && target.node.offers(action)) {
            if (target.node.performAction(action)) {
                return settled(if (target.isSelf) ActionMethod.ACTION else ActionMethod.PARENT_ACTION)
            }
        } else if (node.refresh()) {
            // The element is still there, but what a touch on it reaches does not offer the action.
            val bounds = node.screenBounds()
            return gestureAt(ScreenPoint(bounds.centerX, bounds.centerY), fallback)
        }
        // The node has gone or refused the action, so look for the element again (design spec, section 14.3).
        snapshots.invalidate()
        val fresh = snapshots.current()
        val element = fresh.snapshot.element(key) ?: return GONE
        val freshTarget = fresh.node(key)?.touchTarget(action)
        if (freshTarget != null && !freshTarget.node.isEnabled) return DISABLED
        if (freshTarget != null && freshTarget.node.offers(action) && freshTarget.node.performAction(action)) {
            return settled(ActionMethod.RETRY_ACTION)
        }
        return gestureAt(ScreenPoint(element.bounds.centerX, element.bounds.centerY), fallback)
    }

    /** Focuses the field, waits until it connects to Kestrel's input method, and replaces its text. */
    private suspend fun typeWithInputMethod(element: ScreenElement, node: AccessibilityNodeInfo, text: String): ActionResult {
        val inputMethod = service.inputMethod ?: return ActionResult.Failed("Kestrel's input method is not available")
        val before = withContext(Dispatchers.Main) { inputMethod.currentInputEditorInfo }
        val wasFocused = node.isFocused
        if (!wasFocused && !node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val connection = withContext(Dispatchers.Main) {
            withTimeoutOrNull(INPUT_START_MILLIS) {
                var ready: InputMethod.AccessibilityInputConnection? = null
                while (ready == null) {
                    val editor = inputMethod.currentInputEditorInfo
                    val started = inputMethod.currentInputStarted && editor?.packageName == element.packageName &&
                        (wasFocused || editor !== before)
                    ready = if (started) inputMethod.currentInputConnection else null
                    if (ready == null) delay(POLL_MILLIS)
                }
                ready
            }
        } ?: return ActionResult.Failed("The text field did not connect to Kestrel's input method")
        // If the app has moved the focus to another field, the input would replace that field's text instead.
        if (!(node.refresh() && node.isFocused)) return ActionResult.Failed("The text field lost the focus")
        withContext(Dispatchers.Main) {
            connection.performContextMenuAction(android.R.id.selectAll)
            connection.commitText(text, 1, null)
        }
        return if (node.shows(text, element.password)) {
            settled(ActionMethod.INPUT_METHOD)
        } else {
            ActionResult.Failed("The text field did not take the text")
        }
    }

    private suspend fun gestureAt(point: ScreenPoint, build: (Int, Int) -> GestureDescription): ActionResult =
        gesture(listOf(point)) { arrayOf(build(point.x, point.y)) }

    /**
     * Runs the gesture that [build] makes, after checking that its [points] lie on the screen: Android's gesture builder
     * throws for a negative point, and a gesture beyond the screen's edge reaches nothing.
     */
    private suspend fun gesture(points: List<ScreenPoint>, build: () -> Array<GestureDescription>): ActionResult {
        val screen = service.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        if (points.any { it.x < screen.left || it.x >= screen.right || it.y < screen.top || it.y >= screen.bottom }) {
            return OFF_SCREEN
        }
        return when (gestures.perform(*build())) {
            GestureOutcome.COMPLETED -> settled(ActionMethod.GESTURE)
            GestureOutcome.CANCELLED -> ActionResult.Failed("A touch on the screen cancelled the gesture")
            GestureOutcome.REJECTED -> ActionResult.Failed("Android refused the gesture")
            GestureOutcome.TIMED_OUT -> ActionResult.Failed("Android did not report the end of the gesture")
        }
    }

    /**
     * Waits until the screen has been quiet after the action, but never longer than 3 s (design spec, section 9.3).
     * Call it right after the action: the quiet time counts from the call, so that it cannot run out before the app
     * has reacted. Gestures call it once the finger has been lifted.
     */
    private suspend fun settled(method: ActionMethod): ActionResult {
        tracker.awaitQuiet(ScreenChangeTracker.ACTION_QUIET_MILLIS, since = tracker.now())
        return ActionResult.Done(method)
    }

    /**
     * Whether the field shows [expected] after a short wait. A password field shows only dots, so for it the
     * action's own result has to do.
     */
    private suspend fun AccessibilityNodeInfo.shows(expected: String, password: Boolean): Boolean {
        if (password) return true
        repeat(READ_BACK_TRIES) {
            if (refresh() && shownText().orEmpty() == expected) return true
            delay(POLL_MILLIS)
        }
        return false
    }

    private class Target(val node: AccessibilityNodeInfo, val isSelf: Boolean)

    /** This node or its nearest parent, up to [MAX_PARENT_DEPTH] levels up, that [matches]. */
    private fun AccessibilityNodeInfo.selfOrParent(matches: (AccessibilityNodeInfo) -> Boolean): Target? {
        var current: AccessibilityNodeInfo? = this
        var depth = 0
        while (current != null && depth <= MAX_PARENT_DEPTH) {
            if (matches(current)) return Target(current, isSelf = depth == 0)
            current = current.parent
            depth++
        }
        return null
    }

    /**
     * The element's node or its nearest parent that handles touches. A finger's touch stops at that view even when it
     * is disabled, so Kestrel's action stops there too.
     */
    private fun AccessibilityNodeInfo.touchTarget(action: Int): Target? =
        selfOrParent { it.isClickable || it.isLongClickable || it.offers(action) }

    private fun AccessibilityNodeInfo.offers(action: Int): Boolean = actionList.any { it.id == action }

    /** The first action that scrolls this node in [direction], if the node offers one and is enabled. */
    private fun AccessibilityNodeInfo.scrollAction(direction: ScrollDirection): Int? {
        if (!isEnabled) return null
        val offered = offeredActions()
        return ScrollActions.forDirection(direction, scrollAxis(offered)).firstOrNull { it in offered }
    }

    /** Whether a swipe in [direction] may cross this list (see [ScrollActions.movesAlong]). */
    private fun AccessibilityNodeInfo.movesAlong(direction: ScrollDirection): Boolean =
        ScrollActions.movesAlong(scrollAxis(offeredActions()), direction)

    private fun AccessibilityNodeInfo.offeredActions(): Set<Int> = actionList.mapTo(HashSet()) { it.id }

    private fun AccessibilityNodeInfo.scrollAxis(offered: Set<Int>): ScrollAxis =
        ScrollActions.axis(offered, className?.toString(), collectionInfo?.rowCount, collectionInfo?.columnCount)

    private fun AccessibilityNodeInfo.screenBounds(): IntRect = Rect().also(::getBoundsInScreen).let {
        IntRect(it.left, it.top, it.right, it.bottom)
    }

    /**
     * The list to scroll when the owner names none: in the top app window that has a list, the largest list that can
     * scroll in [direction], or else the largest that moves along that axis, or else the largest list. The snapshot
     * lists its windows from the top down.
     */
    private fun LiveSnapshot.scrollableFor(direction: ScrollDirection): ScreenElement? {
        for (window in snapshot.windows.filter { it.kind == WindowKind.APPLICATION }) {
            val lists = snapshot.elements.filter { it.scrollable && it.enabled && it.windowId == window.id }
            if (lists.isEmpty()) continue
            val canScroll = lists.filter { node(it.key)?.scrollAction(direction) != null }
            val alongTheAxis = lists.filter { node(it.key)?.movesAlong(direction) == true }
            return canScroll.ifEmpty { alongTheAxis }.ifEmpty { lists }.maxByOrNull { it.bounds.width.toLong() * it.bounds.height }
        }
        return null
    }

    /** The bounds of the window with [windowId], or of the top app window when there is no such window. */
    private fun ScreenSnapshot.windowBounds(windowId: Int?): IntRect? =
        (windows.firstOrNull { it.id == windowId } ?: windows.firstOrNull { it.kind == WindowKind.APPLICATION })?.bounds

    private companion object {
        const val MAX_PARENT_DEPTH = 20
        val NOT_ON_SCREEN = ActionResult.Failed("The element is not on the screen")
        val DISABLED = ActionResult.Failed("The element is disabled")
        val GONE = ActionResult.Failed("The element is no longer on the screen")
        val OFF_SCREEN = ActionResult.Failed("The point is outside the screen")
        const val INPUT_START_MILLIS = 2_000L
        const val READ_BACK_TRIES = 5
        const val POLL_MILLIS = 50L
    }
}
```

- [ ] **Step 4: Run the tests again**

Run the command of Step 2 again. Expected: `BUILD SUCCESSFUL`, 6 tests.

- [ ] **Step 5: Run the checks and all device tests**

```powershell
scripts\gradle.ps1 check kestrelAudit
scripts\connected-test.ps1 -Serial <serial> :core:privacy:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL` twice; 7 device tests in `:core:privacy` and 72 in `:app`.

- [ ] **Step 6: Commit**

```powershell
git add core/screen app/src/androidTest
git commit -m "fix(screen): make gestures follow Android's settings and never hang" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: Fallback points that avoid the keyboard and other windows above

**Files:**
- Create: `core/screen/src/main/kotlin/dev/kestrel/screen/act/Uncovered.kt`
- Create: `core/screen/src/test/kotlin/dev/kestrel/screen/act/UncoveredTest.kt`
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/act/ScreenActions.kt` (uses `Uncovered`; refreshed nodes that are hidden count as gone; links that Android refuses fail)
- Modify: `app/src/androidTest/kotlin/dev/kestrel/app/screen/SystemActionsTest.kt`

**Interfaces:**
- Consumes: Task 5's `ScreenActions`.
- Produces: `internal object Uncovered { fun point(bounds: IntRect, covers: List<IntRect>): ScreenPoint?; fun area(area: IntRect, covers: List<IntRect>, vertical: Boolean): IntRect? }`. A tap's fallback gesture fails with "Other windows cover the element", and a scroll's fallback swipe with "Another window covers the list", when no part is free. `openUri` and `openApp` return `ActionResult.Failed` instead of throwing.

Closes (6a), (8c) and the final review's Minor 6.

A fallback gesture went to an element's centre, even when the keyboard or a popup above covered that point, and then it acted on the window above. `Uncovered.point` returns the centre when nothing covers it, otherwise the nearest free point of an 8 × 8 grid over the element, otherwise nothing. `Uncovered.area` cuts a swipe's area at the edge of every window that crosses the swipe's middle line, and keeps the larger part. The covers are the windows of the snapshot that lie above the element's window. A refreshed node that has become hidden, or whose bounds are empty, counts as gone. A `file://` link makes Android throw `FileUriExposedException`, and a link that no app may open throws `SecurityException`; both now give a failed action.

- [ ] **Step 1: Write the failing unit tests**

Create `core/screen/src/test/kotlin/dev/kestrel/screen/act/UncoveredTest.kt`:

```kotlin
package dev.kestrel.screen.act

import dev.kestrel.screen.model.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UncoveredTest {
    private val keyboard = IntRect(0, 1400, 1080, 2400)

    @Test
    fun theCentreWhenNothingCoversIt() {
        assertEquals(ScreenPoint(540, 1100), Uncovered.point(IntRect(0, 1000, 1080, 1200), listOf(IntRect(0, 0, 1080, 100))))
    }

    @Test
    fun theNearestUncoveredPointWhenTheKeyboardCoversTheCentre() {
        // The row's centre, (540, 1400), lies on the keyboard's top edge.
        assertEquals(ScreenPoint(607, 1387), Uncovered.point(IntRect(0, 1300, 1080, 1500), listOf(keyboard)))
    }

    @Test
    fun nothingWhenTheWindowsAboveCoverTheWholeElement() {
        assertNull(Uncovered.point(IntRect(0, 1500, 1080, 1700), listOf(keyboard)))
    }

    @Test
    fun aSwipeAreaLosesThePartUnderTheKeyboard() {
        assertEquals(IntRect(0, 200, 1080, 1400), Uncovered.area(IntRect(0, 200, 1080, 2000), listOf(keyboard), vertical = true))
    }

    @Test
    fun aWindowBesideTheSwipeLineDoesNotMatter() {
        val area = IntRect(0, 200, 1080, 2000)

        assertEquals(area, Uncovered.area(area, listOf(IntRect(0, 500, 300, 700)), vertical = true))
    }

    @Test
    fun aSidewaysSwipeAreaLosesThePartUnderAPanel() {
        assertEquals(IntRect(0, 500, 800, 900), Uncovered.area(IntRect(0, 500, 1080, 900), listOf(IntRect(800, 0, 1080, 2400)), vertical = false))
    }

    @Test
    fun aWindowInTheMiddleOfTheLineLeavesTheLargerPart() {
        assertEquals(IntRect(0, 900, 1080, 2000), Uncovered.area(IntRect(0, 200, 1080, 2000), listOf(IntRect(0, 600, 1080, 900)), vertical = true))
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

```powershell
scripts\gradle.ps1 :core:screen:testDebugUnitTest --tests 'dev.kestrel.screen.act.UncoveredTest'
```

Expected: `BUILD FAILED` with an unresolved reference to `Uncovered`.

- [ ] **Step 3: Write the geometry**

Create `core/screen/src/main/kotlin/dev/kestrel/screen/act/Uncovered.kt`:

```kotlin
package dev.kestrel.screen.act

import dev.kestrel.screen.model.IntRect

/**
 * Finds the parts of an element or a list that the windows above it leave uncovered, such as the keyboard, a popup or
 * the status bar, so that a fallback gesture reaches the element instead of the window above it (design spec,
 * section 9.3).
 */
internal object Uncovered {
    /** The candidate points lie on a grid with this many rows and columns. */
    private const val GRID = 8

    /**
     * The centre of [bounds] when no rectangle of [covers] contains it. Otherwise the uncovered point of a grid over
     * [bounds] that lies nearest to the centre, or null when [covers] hide every point of the grid.
     */
    fun point(bounds: IntRect, covers: List<IntRect>): ScreenPoint? {
        if (bounds.isEmpty) return null
        val centre = ScreenPoint(bounds.centerX, bounds.centerY)
        val grid = (0 until GRID).flatMap { row ->
            (0 until GRID).map { column ->
                ScreenPoint(
                    bounds.left + (2 * column + 1) * bounds.width / (2 * GRID),
                    bounds.top + (2 * row + 1) * bounds.height / (2 * GRID),
                )
            }
        }
        return (listOf(centre) + grid.sortedBy { distanceSquared(it, centre) })
            .firstOrNull { point -> covers.none { it.contains(point) } }
    }

    /**
     * [area] without the parts that [covers] hide on the line along which a swipe crosses it: the middle line from top
     * to bottom when [vertical], otherwise the one from side to side. Each cover on that line cuts the area at its
     * edge, and the larger part is kept. Null when nothing is left.
     */
    fun area(area: IntRect, covers: List<IntRect>, vertical: Boolean): IntRect? {
        var start = if (vertical) area.top else area.left
        var end = if (vertical) area.bottom else area.right
        for (cover in covers) {
            val onTheLine = if (vertical) area.centerX in cover.left until cover.right else area.centerY in cover.top until cover.bottom
            if (!onTheLine) continue
            val coverStart = maxOf(if (vertical) cover.top else cover.left, start)
            val coverEnd = minOf(if (vertical) cover.bottom else cover.right, end)
            if (coverStart >= coverEnd) continue
            if (coverStart - start >= end - coverEnd) end = coverStart else start = coverEnd
        }
        val clear = if (vertical) IntRect(area.left, start, area.right, end) else IntRect(start, area.top, end, area.bottom)
        return clear.takeUnless { it.isEmpty }
    }

    private fun IntRect.contains(point: ScreenPoint) = point.x in left until right && point.y in top until bottom

    private fun distanceSquared(a: ScreenPoint, b: ScreenPoint): Long {
        val dx = (a.x - b.x).toLong()
        val dy = (a.y - b.y).toLong()
        return dx * dx + dy * dy
    }
}
```

Run the command of Step 2 again. Expected: `BUILD SUCCESSFUL`, 7 tests.

- [ ] **Step 4: Write the failing device test**

Replace the whole content of `app/src/androidTest/kotlin/dev/kestrel/app/screen/SystemActionsTest.kt` with:

```kotlin
package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.GlobalAction
import dev.kestrel.screen.model.WindowKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemActionsTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val actions get() = screen.session.actions

    /** Waits until no app window of the fixture app is on the screen. */
    private suspend fun fixturesAreGone() = withTimeout(5_000) {
        while (screen.session.snapshots.current().snapshot.windows.any { it.kind == WindowKind.APPLICATION && it.packageName == FIXTURES }) {
            delay(100)
        }
    }

    /**
     * Waits for the first button of the fixture app's launcher screen. The screen's title is also the app's name, and an
     * icon on the home screen can show that name too.
     */
    private suspend fun launcherScreenIsShown() {
        screen.awaitElement { it.label == "VIEWS" }
    }

    @Test
    fun backClosesADialog() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        actions.tap(screen.awaitElement { it.label == "SHOW DIALOG" }.key)
        screen.awaitElement { it.label == "Delete draft?" }

        assertEquals(ActionResult.Done(ActionMethod.GLOBAL_ACTION), actions.global(GlobalAction.BACK))
        screen.awaitElement { it.label == "Views fixture" }
        assertTrue(screen.session.snapshots.current().snapshot.elements.none { it.label == "Delete draft?" })
    }

    @Test
    fun homeLeavesTheApp() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        assertEquals(ActionResult.Done(ActionMethod.GLOBAL_ACTION), actions.global(GlobalAction.HOME))
        fixturesAreGone()
    }

    @Test
    fun openAppStartsAnAppAtItsLauncherScreen() = runBlocking {
        screen.shell("am force-stop $FIXTURES")
        fixturesAreGone()

        assertEquals(ActionResult.Done(ActionMethod.INTENT), actions.openApp(FIXTURES))
        launcherScreenIsShown()
    }

    @Test
    fun openUriOpensTheAppForALink() = runBlocking {
        screen.shell("am force-stop $FIXTURES")
        fixturesAreGone()

        assertEquals(ActionResult.Done(ActionMethod.INTENT), actions.openUri("kestrel-fixtures://start"))
        launcherScreenIsShown()
    }

    @Test
    fun aFileLinkFailsInsteadOfThrowing() = runBlocking {
        // Android does not let an app hand a file:// link to another app.
        assertEquals(ActionResult.Failed("Android does not let Kestrel open the link"), actions.openUri("file:///sdcard/Download/note.txt"))
    }

    @Test
    fun openAppFailsForAnAppThatIsNotInstalled() = runBlocking {
        assertTrue(actions.openApp("dev.kestrel.missing") is ActionResult.Failed)
    }
}
```

```powershell
scripts\connected-test.ps1 -Serial <serial> :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.screen.SystemActionsTest'
```

Expected: `aFileLinkFailsInsteadOfThrowing` fails with `FileUriExposedException: file:///sdcard/Download/note.txt exposed beyond app through Intent.getData()`.

- [ ] **Step 5: Use the geometry in the actions**

Replace the whole content of `core/screen/src/main/kotlin/dev/kestrel/screen/act/ScreenActions.kt` with:

```kotlin
package dev.kestrel.screen.act

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.InputMethod
import android.accessibilityservice.MagnificationConfig
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.FileUriExposedException
import android.os.Bundle
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import dev.kestrel.screen.read.LiveSnapshot
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.SnapshotCache
import dev.kestrel.screen.read.shownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Kestrel's actions on the screen. Each one has a fallback (design spec, sections 9.3 and 14.3). They query and act on
 * nodes with blocking binder calls on the caller's dispatcher, so callers run them on `Dispatchers.IO`.
 */
class ScreenActions internal constructor(
    private val service: AccessibilityService,
    private val snapshots: SnapshotCache<LiveSnapshot>,
    private val gestures: GestureRunner,
    private val tracker: ScreenChangeTracker,
) {
    /** Apps that refused the set-text action or did not show the text afterwards (design spec, section 9.7). */
    private val inputMethodApps = ConcurrentHashMap.newKeySet<String>()

    /**
     * Taps the element with [key]. Kestrel looks the key up in the current snapshot, never in an older one, because a
     * list may have reused an old view for another item. It uses the click action of the element or of its nearest
     * parent that handles touches. If that node has gone or refuses the click, Kestrel looks for the element again in
     * a fresh snapshot, and if nothing offers a click, it taps the element's centre. It does not tap a disabled
     * element, because a finger's touch would stop there too.
     *
     * A key names a position, not an identity. After a list has added, removed or scrolled rows, a key from an older
     * snapshot can name a look-alike element in another row, so the caller must first check that the element is still
     * the one it saw (design spec, section 9.2).
     */
    suspend fun tap(key: String): ActionResult = press(key, AccessibilityNodeInfo.ACTION_CLICK, Gestures::tap)

    /** Long-presses the element with [key], in the same way as [tap]. */
    suspend fun longPress(key: String): ActionResult = press(key, AccessibilityNodeInfo.ACTION_LONG_CLICK, Gestures::longPress)

    suspend fun tapAt(x: Int, y: Int): ActionResult = gestureAt(ScreenPoint(x, y), Gestures::tap)

    suspend fun longPressAt(x: Int, y: Int): ActionResult = gestureAt(ScreenPoint(x, y), Gestures::longPress)

    /**
     * Replaces the text of the field with [key] by [text]. Kestrel uses the set-text action and reads the text
     * back. If the app refuses the action or the field does not show the text, Kestrel types through its
     * accessibility input method instead, and from then on does so for the whole app (design spec, section 9.7).
     * Kestrel does not type into a disabled field, and a field that has gone does not count as a refusal.
     */
    suspend fun setText(key: String, text: String): ActionResult = setText(key, text, retry = true)

    private suspend fun setText(key: String, text: String, retry: Boolean): ActionResult {
        val live = snapshots.current()
        val element = live.snapshot.element(key) ?: return NOT_ON_SCREEN
        val node = live.node(key) ?: return NOT_ON_SCREEN
        if (!element.editable) return ActionResult.Failed("The element is not a text field")
        if (!element.enabled) return DISABLED
        val app = element.packageName
        if (app == null || app !in inputMethodApps) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) && node.shows(text, element.password)) {
                return settled(ActionMethod.ACTION)
            }
            if (!node.refresh()) {
                // The field has gone, so the app refused nothing: look for it again once (design spec, section 14.3).
                if (!retry) return GONE
                snapshots.invalidate()
                return setText(key, text, retry = false)
            }
            if (!node.isEnabled) return DISABLED
            if (app != null) inputMethodApps += app
        }
        return typeWithInputMethod(element, node, text)
    }

    /**
     * Scrolls the element with [key] or, when [key] is null, a list in the top app window that has one. Kestrel uses
     * the scroll action of the element or of its nearest parent that can scroll in [direction]. Otherwise it swipes
     * across the nearest list that moves along that axis. A sideways swipe across a row can archive a message or
     * start a call, so Kestrel swipes sideways only across a list that moves sideways. Up and down, it swipes across
     * the window when no list moves that way (design spec, section 9.3).
     */
    suspend fun scroll(direction: ScrollDirection, key: String? = null): ActionResult {
        val live = snapshots.current()
        val element = when (key) {
            null -> live.scrollableFor(direction)
            else -> live.snapshot.element(key) ?: return NOT_ON_SCREEN
        }
        val node = element?.let { live.node(it.key) ?: return NOT_ON_SCREEN }
        val target = node?.selfOrParent { it.scrollAction(direction) != null }
        val action = target?.node?.scrollAction(direction)
        if (target != null && action != null && target.node.performAction(action)) {
            return settled(if (target.isSelf) ActionMethod.ACTION else ActionMethod.PARENT_ACTION)
        }
        val window = live.snapshot.windowOf(element?.windowId) ?: live.snapshot.windows.firstOrNull { it.kind == WindowKind.APPLICATION }
        val list = (target ?: node?.selfOrParent { it.isScrollable && it.movesAlong(direction) })?.node?.screenBounds()
        val area = (if (list != null && window != null) list.intersect(window.bounds) else list)
            ?: window?.bounds?.takeIf { direction.isVertical }
            ?: return ActionResult.Failed("Nothing on the screen scrolls that way")
        // A swipe that started on the keyboard or on a popup above the list would act on that instead.
        val clear = Uncovered.area(area, live.snapshot.coversAbove(window), direction.isVertical)
            ?: return ActionResult.Failed("Another window covers the list")
        val (from, to) = SwipePaths.forScroll(clear, direction)
        return gesture(listOf(from, to)) { arrayOf(Gestures.swipe(from, to)) }
    }

    suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int): ActionResult {
        val from = ScreenPoint(fromX, fromY)
        val to = ScreenPoint(toX, toY)
        return gesture(listOf(from, to)) { arrayOf(Gestures.swipe(from, to)) }
    }

    /** Presses and holds at the first point, then moves to the second point, as when moving an icon. */
    suspend fun drag(fromX: Int, fromY: Int, toX: Int, toY: Int): ActionResult {
        val from = ScreenPoint(fromX, fromY)
        val to = ScreenPoint(toX, toY)
        // Half the touch slop, so that the press still counts as a press while it drifts.
        val drift = maxOf(1, ViewConfiguration.get(service).scaledTouchSlop / 2)
        return gesture(listOf(from, ScreenPoint(fromX + drift, fromY), to)) { Gestures.drag(from, to, drift) }
    }

    /** Moves two fingers apart (when [endSpan] is larger than [startSpan]) or together around a point. */
    suspend fun pinch(centerX: Int, centerY: Int, startSpan: Int, endSpan: Int): ActionResult {
        val fingers = listOf(startSpan, endSpan).flatMap { span ->
            listOf(ScreenPoint(centerX - span / 2, centerY), ScreenPoint(centerX + span / 2, centerY))
        }
        return gesture(fingers) { arrayOf(Gestures.pinch(ScreenPoint(centerX, centerY), startSpan, endSpan)) }
    }

    /** How many times Android's magnification enlarges the screen; 1 when it does not. */
    val zoomScale: Float get() = service.magnificationController.magnificationConfig?.scale ?: 1f

    /** Magnifies the whole screen [scale] times around a point, with Android's magnification (design spec, section 9.3). */
    suspend fun zoom(scale: Float, centerX: Int, centerY: Int): ActionResult {
        val config = MagnificationConfig.Builder()
            .setMode(MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN)
            .setScale(scale)
            .setCenterX(centerX.toFloat())
            .setCenterY(centerY.toFloat())
            .build()
        val done = withContext(Dispatchers.Main) { service.magnificationController.setMagnificationConfig(config, false) }
        return if (done) magnified() else ActionResult.Failed("Android refused to magnify the screen")
    }

    suspend fun resetZoom(): ActionResult {
        val done = withContext(Dispatchers.Main) { service.magnificationController.resetCurrentMagnification(false) }
        return if (done) magnified() else ActionResult.Failed("Android refused to reset the magnification")
    }

    /**
     * Magnification moves every element on the screen, and Android sends no accessibility event for it, so Kestrel
     * reports the change to the tracker itself: snapshots are built anew, and the numbers and labels follow the
     * elements. It reports it again after the wait, because a snapshot built during the wait may still show the old
     * positions.
     */
    private suspend fun magnified(): ActionResult {
        tracker.onScreenChanged()
        val result = settled(ActionMethod.MAGNIFICATION)
        tracker.onScreenChanged()
        return result
    }

    suspend fun global(action: GlobalAction): ActionResult {
        return if (service.performGlobalAction(action.id)) {
            settled(ActionMethod.GLOBAL_ACTION)
        } else {
            ActionResult.Failed("Android refused the action ${action.name}")
        }
    }

    /** Opens the app with [packageName] as its launcher icon would. */
    suspend fun openApp(packageName: String): ActionResult {
        val intent = service.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ActionResult.Failed("No app with the package $packageName can be opened")
        return try {
            service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            settled(ActionMethod.INTENT)
        } catch (e: ActivityNotFoundException) {
            ActionResult.Failed("No app with the package $packageName can be opened")
        } catch (e: SecurityException) {
            ActionResult.Failed("Android does not let Kestrel open $packageName")
        }
    }

    /** Opens a link or a deep link in the app that handles it. */
    suspend fun openUri(uri: String): ActionResult {
        return try {
            service.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            settled(ActionMethod.INTENT)
        } catch (e: ActivityNotFoundException) {
            ActionResult.Failed("No app can open the link")
        } catch (e: FileUriExposedException) {
            // Android does not let an app hand a file:// link to another app.
            ActionResult.Failed("Android does not let Kestrel open the link")
        } catch (e: SecurityException) {
            ActionResult.Failed("Android does not let Kestrel open the link")
        }
    }

    private suspend fun press(key: String, action: Int, fallback: (Int, Int) -> GestureDescription): ActionResult {
        val live = snapshots.current()
        val node = live.node(key) ?: return NOT_ON_SCREEN
        val target = node.touchTarget(action)
        if (target != null && !target.node.isEnabled) return DISABLED
        if (target != null && target.node.offers(action)) {
            if (target.node.performAction(action)) {
                return settled(if (target.isSelf) ActionMethod.ACTION else ActionMethod.PARENT_ACTION)
            }
        } else if (node.refresh() && node.isVisibleToUser) {
            // The element is still there, but what a touch on it reaches does not offer the action.
            val bounds = node.screenBounds()
            if (!bounds.isEmpty) {
                return fallbackAt(bounds, live.snapshot.coversAbove(live.snapshot.windowOf(live.snapshot.element(key)?.windowId)), fallback)
            }
        }
        // The node has gone, is hidden or refused the action, so look for the element again (design spec, section 14.3).
        snapshots.invalidate()
        val fresh = snapshots.current()
        val element = fresh.snapshot.element(key) ?: return GONE
        val freshTarget = fresh.node(key)?.touchTarget(action)
        if (freshTarget != null && !freshTarget.node.isEnabled) return DISABLED
        if (freshTarget != null && freshTarget.node.offers(action) && freshTarget.node.performAction(action)) {
            return settled(ActionMethod.RETRY_ACTION)
        }
        return fallbackAt(element.bounds, fresh.snapshot.coversAbove(fresh.snapshot.windowOf(element.windowId)), fallback)
    }

    /** A fallback gesture on the part of [bounds] that the windows above leave uncovered. */
    private suspend fun fallbackAt(bounds: IntRect, covers: List<IntRect>, build: (Int, Int) -> GestureDescription): ActionResult {
        val point = Uncovered.point(bounds, covers) ?: return COVERED
        return gestureAt(point, build)
    }

    /** Focuses the field, waits until it connects to Kestrel's input method, and replaces its text. */
    private suspend fun typeWithInputMethod(element: ScreenElement, node: AccessibilityNodeInfo, text: String): ActionResult {
        val inputMethod = service.inputMethod ?: return ActionResult.Failed("Kestrel's input method is not available")
        val before = withContext(Dispatchers.Main) { inputMethod.currentInputEditorInfo }
        val wasFocused = node.isFocused
        if (!wasFocused && !node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val connection = withContext(Dispatchers.Main) {
            withTimeoutOrNull(INPUT_START_MILLIS) {
                var ready: InputMethod.AccessibilityInputConnection? = null
                while (ready == null) {
                    val editor = inputMethod.currentInputEditorInfo
                    val started = inputMethod.currentInputStarted && editor?.packageName == element.packageName &&
                        (wasFocused || editor !== before)
                    ready = if (started) inputMethod.currentInputConnection else null
                    if (ready == null) delay(POLL_MILLIS)
                }
                ready
            }
        } ?: return ActionResult.Failed("The text field did not connect to Kestrel's input method")
        // If the app has moved the focus to another field, the input would replace that field's text instead.
        if (!(node.refresh() && node.isFocused)) return ActionResult.Failed("The text field lost the focus")
        withContext(Dispatchers.Main) {
            connection.performContextMenuAction(android.R.id.selectAll)
            connection.commitText(text, 1, null)
        }
        return if (node.shows(text, element.password)) {
            settled(ActionMethod.INPUT_METHOD)
        } else {
            ActionResult.Failed("The text field did not take the text")
        }
    }

    private suspend fun gestureAt(point: ScreenPoint, build: (Int, Int) -> GestureDescription): ActionResult =
        gesture(listOf(point)) { arrayOf(build(point.x, point.y)) }

    /**
     * Runs the gesture that [build] makes, after checking that its [points] lie on the screen: Android's gesture builder
     * throws for a negative point, and a gesture beyond the screen's edge reaches nothing.
     */
    private suspend fun gesture(points: List<ScreenPoint>, build: () -> Array<GestureDescription>): ActionResult {
        val screen = service.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        if (points.any { it.x < screen.left || it.x >= screen.right || it.y < screen.top || it.y >= screen.bottom }) {
            return OFF_SCREEN
        }
        return when (gestures.perform(*build())) {
            GestureOutcome.COMPLETED -> settled(ActionMethod.GESTURE)
            GestureOutcome.CANCELLED -> ActionResult.Failed("A touch on the screen cancelled the gesture")
            GestureOutcome.REJECTED -> ActionResult.Failed("Android refused the gesture")
            GestureOutcome.TIMED_OUT -> ActionResult.Failed("Android did not report the end of the gesture")
        }
    }

    /**
     * Waits until the screen has been quiet after the action, but never longer than 3 s (design spec, section 9.3).
     * Call it right after the action: the quiet time counts from the call, so that it cannot run out before the app
     * has reacted. Gestures call it once the finger has been lifted.
     */
    private suspend fun settled(method: ActionMethod): ActionResult {
        tracker.awaitQuiet(ScreenChangeTracker.ACTION_QUIET_MILLIS, since = tracker.now())
        return ActionResult.Done(method)
    }

    /**
     * Whether the field shows [expected] after a short wait. A password field shows only dots, so for it the
     * action's own result has to do.
     */
    private suspend fun AccessibilityNodeInfo.shows(expected: String, password: Boolean): Boolean {
        if (password) return true
        repeat(READ_BACK_TRIES) {
            if (refresh() && shownText().orEmpty() == expected) return true
            delay(POLL_MILLIS)
        }
        return false
    }

    private class Target(val node: AccessibilityNodeInfo, val isSelf: Boolean)

    /** This node or its nearest parent, up to [MAX_PARENT_DEPTH] levels up, that [matches]. */
    private fun AccessibilityNodeInfo.selfOrParent(matches: (AccessibilityNodeInfo) -> Boolean): Target? {
        var current: AccessibilityNodeInfo? = this
        var depth = 0
        while (current != null && depth <= MAX_PARENT_DEPTH) {
            if (matches(current)) return Target(current, isSelf = depth == 0)
            current = current.parent
            depth++
        }
        return null
    }

    /**
     * The element's node or its nearest parent that handles touches. A finger's touch stops at that view even when it
     * is disabled, so Kestrel's action stops there too.
     */
    private fun AccessibilityNodeInfo.touchTarget(action: Int): Target? =
        selfOrParent { it.isClickable || it.isLongClickable || it.offers(action) }

    private fun AccessibilityNodeInfo.offers(action: Int): Boolean = actionList.any { it.id == action }

    /** The first action that scrolls this node in [direction], if the node offers one and is enabled. */
    private fun AccessibilityNodeInfo.scrollAction(direction: ScrollDirection): Int? {
        if (!isEnabled) return null
        val offered = offeredActions()
        return ScrollActions.forDirection(direction, scrollAxis(offered)).firstOrNull { it in offered }
    }

    /** Whether a swipe in [direction] may cross this list (see [ScrollActions.movesAlong]). */
    private fun AccessibilityNodeInfo.movesAlong(direction: ScrollDirection): Boolean =
        ScrollActions.movesAlong(scrollAxis(offeredActions()), direction)

    private fun AccessibilityNodeInfo.offeredActions(): Set<Int> = actionList.mapTo(HashSet()) { it.id }

    private fun AccessibilityNodeInfo.scrollAxis(offered: Set<Int>): ScrollAxis =
        ScrollActions.axis(offered, className?.toString(), collectionInfo?.rowCount, collectionInfo?.columnCount)

    private fun AccessibilityNodeInfo.screenBounds(): IntRect = Rect().also(::getBoundsInScreen).let {
        IntRect(it.left, it.top, it.right, it.bottom)
    }

    /**
     * The list to scroll when the owner names none: in the top app window that has a list, the largest list that can
     * scroll in [direction], or else the largest that moves along that axis, or else the largest list. The snapshot
     * lists its windows from the top down.
     */
    private fun LiveSnapshot.scrollableFor(direction: ScrollDirection): ScreenElement? {
        for (window in snapshot.windows.filter { it.kind == WindowKind.APPLICATION }) {
            val lists = snapshot.elements.filter { it.scrollable && it.enabled && it.windowId == window.id }
            if (lists.isEmpty()) continue
            val canScroll = lists.filter { node(it.key)?.scrollAction(direction) != null }
            val alongTheAxis = lists.filter { node(it.key)?.movesAlong(direction) == true }
            return canScroll.ifEmpty { alongTheAxis }.ifEmpty { lists }.maxByOrNull { it.bounds.width.toLong() * it.bounds.height }
        }
        return null
    }

    /** The bounds of the window with [windowId], or of the top app window when there is no such window. */
    private fun ScreenSnapshot.windowOf(windowId: Int?): WindowSummary? = windows.firstOrNull { it.id == windowId }

    /** The bounds of the windows that lie above [window], such as the keyboard, a popup or the status bar. */
    private fun ScreenSnapshot.coversAbove(window: WindowSummary?): List<IntRect> =
        if (window == null) emptyList() else windows.filter { it.layer > window.layer }.map { it.bounds }

    private companion object {
        const val MAX_PARENT_DEPTH = 20
        val NOT_ON_SCREEN = ActionResult.Failed("The element is not on the screen")
        val DISABLED = ActionResult.Failed("The element is disabled")
        val GONE = ActionResult.Failed("The element is no longer on the screen")
        val OFF_SCREEN = ActionResult.Failed("The point is outside the screen")
        val COVERED = ActionResult.Failed("Other windows cover the element")
        const val INPUT_START_MILLIS = 2_000L
        const val READ_BACK_TRIES = 5
        const val POLL_MILLIS = 50L
    }
}
```

Run the command of Step 4 again. Expected: `BUILD SUCCESSFUL`.

No device test covers the covered fallback or a hidden refreshed node, because no fixture puts a window over an element without a click action. `UncoveredTest` covers the geometry, and `TapTest` and `ScrollTest` run through the changed paths.

- [ ] **Step 6: Run the checks and all device tests**

```powershell
scripts\gradle.ps1 check kestrelAudit
scripts\connected-test.ps1 -Serial <serial> :core:privacy:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL` twice; 7 device tests in `:core:privacy` and 73 in `:app`.

- [ ] **Step 7: Commit**

```powershell
git add core/screen app/src/androidTest
git commit -m "fix(screen): keep fallback gestures off the keyboard and other windows above" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 7: Overlays that close completely with their session

**Files:**
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/OverlayHost.kt` (`show` returns whether it worked; `close()`)
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/NumbersOverlay.kt`, `LabelsOverlay.kt`, `GridOverlay.kt`, `FeedbackBar.kt`, `ConfirmationCard.kt`, `TouchWatcher.kt` (state only after a successful show)
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt` (`close()` hides each overlay through its own `hide()`)
- Modify: `app/src/androidTest/kotlin/dev/kestrel/app/screen/OverlayTest.kt`, `SnapshotTest.kt`, `TouchWatcherTest.kt`

**Interfaces:**
- Consumes: Task 4's `ScreenSession`.
- Produces: `OverlayHost.show(view: View, params: LayoutParams): Boolean`; `OverlayHost.close()` replaces `hideAll()`, and a closed host shows nothing more. `ConfirmationCard.ask` answers `false` when the card cannot show. `TouchWatcher` no longer has `isWatching`.

Closes (9a), (9b), (10b), (11b), (5e), (9g), (9l) and (11a).

`OverlayHost` recorded a view as shown before `addView` had worked, so a failed show left a view that the host would later try to remove. Each overlay also kept its state, such as its badges, when the session closed, because `hideAll()` removed the windows behind the overlays' backs. Now `show` adds a view to its list only after `addView` worked, and returns `false` when Android refuses the window (`BadTokenException` or `IllegalArgumentException`). The overlays change their state only after a successful show, and `ScreenSession.close()` hides each overlay through its own `hide()` before it closes the host.

- [ ] **Step 1: Write the new and changed tests**

Replace the whole content of `app/src/androidTest/kotlin/dev/kestrel/app/screen/OverlayTest.kt` with:

```kotlin
package dev.kestrel.app.screen

import android.view.accessibility.AccessibilityEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.overlay.NumbersMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val session get() = screen.session

    private suspend fun eventually(timeoutMillis: Long = 5_000, check: suspend () -> Boolean) = withTimeout(timeoutMillis) {
        while (!check()) delay(100)
    }

    private suspend fun keyOf(label: String) = screen.awaitElement { it.label == label }.key

    private suspend fun showDialog() {
        session.actions.tap(keyOf("SHOW DIALOG"))
        screen.awaitElement { it.label == "Delete draft?" }
    }

    @Test
    fun numbersFollowAZoom() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val send = keyOf("SEND")
        session.showNumbers()
        eventually { session.numberBadges.any { it.key == send } }
        val before = session.numberBadges.first { it.key == send }.bounds

        try {
            assertEquals(ActionResult.Done(ActionMethod.MAGNIFICATION), session.actions.zoom(2f, before.centerX, before.centerY))
            eventually { session.numberBadges.firstOrNull { it.key == send }?.let { it.bounds.height > before.height } == true }
            val zoomed = session.numberBadges.first { it.key == send }.bounds

            // A change of scale while the screen is magnified adds no window, so Android sends no event for it: only
            // Kestrel's own report makes the numbers follow. The button is as wide as the screen, so the height is compared.
            // The centre is given in the screen's own, unmagnified coordinates.
            assertEquals(ActionResult.Done(ActionMethod.MAGNIFICATION), session.actions.zoom(3f, before.centerX, before.centerY))
            eventually { session.numberBadges.firstOrNull { it.key == send }?.let { it.bounds.height > zoomed.height } == true }
        } finally {
            session.actions.resetZoom()
        }
    }

    @Test
    fun numbersGoOnTheActionableElementsInReadingOrder() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        session.showNumbers()

        val badges = session.numberBadges
        assertEquals(keyOf("SEND"), badges.first().key)
        assertEquals((1..badges.size).toList(), badges.map { it.number })
        eventually { screen.showsWindow("Kestrel numbers") }
    }

    @Test
    fun numbersCanBeHiddenRightAfterTheyAreShown() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        // Android attaches a new overlay's view only on the next frame. Showing and hiding the numbers in one go on the
        // main thread hides them before that frame, and the window must still go.
        repeat(2) {
            withContext(Dispatchers.Main) {
                session.showNumbers()
                session.hideNumbers()
            }
        }
        eventually { !screen.showsWindow("Kestrel numbers") }
    }

    @Test
    fun unlabeledModeNumbersOnlyTheIconWithoutAName() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        session.showNumbers(NumbersMode.UNLABELED)

        val icon = screen.awaitElement { it.viewId == "$FIXTURES:id/compose_button" }
        assertEquals(listOf(icon.key), session.numberBadges.map { it.key })
    }

    @Test
    fun touchesReachTheAppWhileNumbersAreShown() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        session.showNumbers()
        val target = screen.awaitElement { it.label == "Touch target" }
        // Until Android's input dispatcher lists the numbers' window, a touch would pass it even if it took touches.
        screen.awaitInputWindow("Kestrel numbers")

        assertEquals(ActionResult.Done(ActionMethod.GESTURE), session.actions.tapAt(target.bounds.centerX, target.bounds.centerY))
        screen.awaitElement { it.label == "Touched target" }
        Unit
    }

    @Test
    fun numbersFollowTheScreenUntilTheyAreHidden() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        session.showNumbers()

        showDialog()

        val keep = keyOf("KEEP")
        eventually { session.numberBadges.any { it.key == keep } }
        session.hideNumbers()
        eventually { !screen.showsWindow("Kestrel numbers") }
    }

    @Test
    fun whichOneNumbersOnlyTheCandidatesAndGoesWhenTheScreenChanges() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val send = keyOf("SEND")
        val rebuild = keyOf("REBUILD")

        session.showWhichOne(listOf(rebuild, send))

        assertEquals(listOf(1 to send, 2 to rebuild), session.numberBadges.map { it.number to it.key })
        showDialog()
        eventually { session.numberBadges.isEmpty() && !screen.showsWindow("Kestrel numbers") }
    }

    @Test
    fun numbersComeBackWhenWhichOneGoes() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        session.showNumbers()
        val send = keyOf("SEND")
        val rebuild = keyOf("REBUILD")

        session.showWhichOne(listOf(rebuild, send))
        assertEquals(2, session.numberBadges.size)
        showDialog()

        // "Which one?" belonged to the old screen; "show numbers" is still on, so numbers follow the new screen.
        val keep = keyOf("KEEP")
        eventually { session.numberBadges.any { it.key == keep } }
    }

    @Test
    fun labelsGoWhenTheScreenChanges() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        session.showLabels()

        assertTrue(session.labelsShowing)
        eventually { screen.showsWindow("Kestrel labels") }
        showDialog()
        eventually { !session.labelsShowing && !screen.showsWindow("Kestrel labels") }
    }

    @Test
    fun hideLabelsRemovesThem() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        session.showLabels()
        eventually { screen.showsWindow("Kestrel labels") }

        session.hideLabels()

        assertFalse(session.labelsShowing)
        eventually { !screen.showsWindow("Kestrel labels") }
    }

    @Test
    fun labelsGoWhenTheScreenChangesEvenIfItIsNeverQuiet() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val showDialog = keyOf("SHOW DIALOG")
        session.showLabels()
        eventually { screen.showsWindow("Kestrel labels") }

        // From now on the screen reports a change every 50 ms, as a stopwatch or a progress bar would. Every snapshot
        // and every action then waits the full 3 s for quiet, so this test waits longer.
        val busy = launch(Dispatchers.Default) {
            while (true) {
                session.onAccessibilityEvent(AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
                delay(50)
            }
        }
        try {
            assertEquals(ActionResult.Done(ActionMethod.ACTION), session.actions.tap(showDialog))
            eventually(timeoutMillis = 20_000) { !session.labelsShowing }
        } finally {
            busy.cancel()
        }
    }

    @Test
    fun closeRemovesEveryOverlayAndItsState() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        session.showNumbers()
        session.showLabels()
        session.showGrid()
        session.showFeedback("Listening")
        eventually { screen.showsWindow("Kestrel numbers") && screen.showsWindow("Kestrel grid") }

        withContext(Dispatchers.Main) { session.close() }

        assertEquals(emptyList<Any>(), session.numberBadges)
        assertFalse(session.labelsShowing)
        assertNull(session.grid)
        assertNull(session.feedbackText)
        // A late command after the service has closed its session shows nothing.
        session.showNumbers()
        assertEquals(emptyList<Any>(), session.numberBadges)
        eventually { listOf("Kestrel numbers", "Kestrel labels", "Kestrel grid", "Kestrel feedback bar").none(screen::showsWindow) }
    }

    @Test
    fun turningTheServiceOffRemovesTheOverlays() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        session.showNumbers()
        eventually { screen.showsWindow("Kestrel numbers") }

        screen.shell("settings delete secure enabled_accessibility_services")

        eventually { !screen.showsWindow("Kestrel numbers") }
    }
}
```

Replace the whole content of `app/src/androidTest/kotlin/dev/kestrel/app/screen/SnapshotTest.kt` with:

```kotlin
package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.WindowKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SnapshotTest {
    @get:Rule
    val screen = ScreenServiceRule()

    @Test
    fun viewsAreReadWithTheirNamesStatesAndActions() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")

        val snapshot = screen.session.snapshots.current().snapshot
        val send = snapshot.elements.single { it.viewId == "$FIXTURES:id/send_button" }
        val compose = snapshot.elements.single { it.viewId == "$FIXTURES:id/compose_button" }
        val row = snapshot.elements.single { it.viewId == "$FIXTURES:id/contact_row" }
        val password = snapshot.elements.single { it.viewId == "$FIXTURES:id/password_field" }
        val message = snapshot.elements.single { it.viewId == "$FIXTURES:id/message_field" }

        assertEquals("SEND", send.label)
        assertTrue(send.clickable && ElementAction.CLICK in send.actions)
        assertNull(compose.label)
        assertEquals("compose", compose.suggestedName)
        assertEquals("Ana Petrović Online", row.label)
        assertTrue(password.password && password.editable)
        assertNull(password.text)
        assertEquals("Password", password.label)
        assertNull("An empty field's hint is not its text", message.text)
        assertEquals("Message", message.label)
        assertTrue(snapshot.elements.single { it.label == "Remember me" }.checkable)
        assertTrue(snapshot.windows.any { it.kind == WindowKind.APPLICATION && it.packageName == FIXTURES })
    }

    @Test
    fun keysStayTheSameInANewSnapshotOfTheSameScreen() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val first = screen.session.snapshots.current().snapshot.elements.map { it.key }

        screen.session.snapshots.invalidate()
        val second = screen.session.snapshots.current().snapshot.elements.map { it.key }

        assertEquals(first, second)
    }

    @Test
    fun composeElementsAreRead() = runBlocking {
        screen.open("ComposeActivity", "Compose fixture")

        val elements = screen.session.snapshots.current().snapshot.elements

        assertEquals(1, elements.count { it.clickable && it.label == "Compose button" })
        assertTrue(elements.any { it.checkable && it.label == "Subscribe" })
        assertTrue(elements.any { it.editable })
    }

    @Test
    fun webPageContentIsRead() = runBlocking {
        screen.open("WebActivity", "Web fixture")

        assertTrue(screen.awaitElement { it.label == "Web button" }.clickable)
        screen.awaitElement { it.label == "Web link" }
        screen.awaitElement { it.editable && it.label == "Web field" }
        Unit
    }

    @Test
    fun aSecureWindowCanStillBeRead() = runBlocking {
        screen.open("SecureActivity", "Secure fixture")

        assertTrue(screen.awaitElement { it.label == "SECRET ACTION" }.clickable)
    }

    @Test
    fun aDialogComesBeforeTheActivityBehindIt() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val showDialog = screen.session.snapshots.current().snapshot.elements.single { it.label == "SHOW DIALOG" }
        screen.shell("input tap ${showDialog.bounds.centerX} ${showDialog.bounds.centerY}")

        val title = screen.awaitElement { it.label == "Delete draft?" }

        val snapshot = screen.session.snapshots.current().snapshot
        assertEquals(title.windowId, snapshot.windows.first { it.kind == WindowKind.APPLICATION }.id)
        assertTrue(snapshot.elements.any { it.label == "KEEP" && it.windowId == title.windowId })
        screen.shell("input keyevent KEYCODE_BACK")
        Unit
    }
}
```

Replace the whole content of `app/src/androidTest/kotlin/dev/kestrel/app/screen/TouchWatcherTest.kt` with:

```kotlin
package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TouchWatcherTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val session get() = screen.session

    @Test
    fun aFingerOnAnotherAppIsReportedAndStillReachesIt() = runBlocking {
        TestFinger(screen.automation).use { finger ->
            screen.open("ViewsActivity", "Views fixture")
            val target = screen.awaitElement { it.label == "Touch target" }.bounds
            session.startTouchWatcher()
            screen.awaitInputWindow(WATCHER)
            val touch = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(3_000) { session.ownerTouches.first() } }

            finger.tap(target.centerX, target.centerY)

            touch.await()
            screen.awaitElement { it.label == "Touched target" }
        }
        Unit
    }

    @Test
    fun aStoppedWatcherReportsNothing() = runBlocking {
        TestFinger(screen.automation).use { finger ->
            screen.open("ViewsActivity", "Views fixture")
            val target = screen.awaitElement { it.label == "Touch target" }.bounds
            session.startTouchWatcher()
            screen.awaitInputWindow(WATCHER)

            session.stopTouchWatcher()

            withTimeout(5_000) { while (screen.showsWindow(WATCHER)) delay(100) }
            val touches = mutableListOf<Unit>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { session.ownerTouches.collect { touches += it } }
            finger.tap(target.centerX, target.centerY)
            screen.awaitElement { it.label == "Touched target" }
            delay(500)
            collector.cancel()
            assertTrue("touches: ${touches.size}", touches.isEmpty())
        }
    }

    @Test
    fun kestrelsOwnGesturesAreNotReported() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val target = screen.awaitElement { it.label == "Touch target" }.bounds
        session.startTouchWatcher()
        screen.awaitInputWindow(WATCHER)
        val touches = mutableListOf<Unit>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { session.ownerTouches.collect { touches += it } }

        session.actions.tapAt(target.centerX, target.centerY)
        screen.awaitElement { it.label == "Touched target" }
        delay(500)

        collector.cancel()
        assertTrue("touches: ${touches.size}", touches.isEmpty())
    }

    @Test
    fun aFingerDuringKestrelsGestureIsReported() = runBlocking {
        TestFinger(screen.automation).use { finger ->
            screen.open("GesturesActivity", "Gestures fixture")
            val pad = screen.awaitElement { it.label == "Gesture pad" }.bounds
            session.startTouchWatcher()
            screen.awaitInputWindow(WATCHER)
            val touches = Channel<Unit>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { session.ownerTouches.collect { touches.send(it) } }
            val drag = async { session.actions.drag(pad.centerX - 300, pad.centerY + 200, pad.centerX + 300, pad.centerY + 200) }
            delay(400)
            assertTrue("Kestrel's own drag was reported", touches.tryReceive().isFailure)

            finger.tap(pad.centerX, pad.centerY - 300)

            withTimeout(3_000) { touches.receive() }
            drag.await()
            collector.cancel()
        }
        Unit
    }

    private companion object {
        const val WATCHER = "Kestrel touch watcher"
    }
}
```

- [ ] **Step 2: Run them and watch one fail**

```powershell
scripts\connected-test.ps1 -Serial <serial> :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.screen.OverlayTest,dev.kestrel.app.screen.SnapshotTest,dev.kestrel.app.screen.TouchWatcherTest'
```

Expected: `closeRemovesEveryOverlayAndItsState` fails, because the session keeps its number badges after `close()`. `hideLabelsRemovesThem`, `aStoppedWatcherReportsNothing` and the other changes cover behaviour that already works, so they pass. In one run during the writing of this plan, `numbersFollowAZoom` failed after the failing close test had left a numbers window behind; it passed in every run with the code below.

- [ ] **Step 3: Change the overlays and the session**

Replace the whole content of each of these files with the content below it.

`core/screen/src/main/kotlin/dev/kestrel/screen/overlay/OverlayHost.kt`:

```kotlin
package dev.kestrel.screen.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import dev.kestrel.screen.model.IntRect

/**
 * Shows Kestrel's overlay windows. They are accessibility overlays, so Kestrel needs no "display over other apps"
 * permission, and snapshots leave them out (design spec, sections 9.2 and 9.4). Use it on the main thread.
 */
class OverlayHost(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val shown = mutableSetOf<View>()
    private var closed = false

    val context: Context get() = service

    /**
     * Shows [view], or updates its layout when it is on the screen already, and returns whether it is on the screen.
     * After [close], or once Android has disconnected the service, it shows nothing.
     */
    fun show(view: View, params: LayoutParams): Boolean {
        if (closed) return false
        if (view in shown) {
            return try {
                windowManager.updateViewLayout(view, params)
                true
            } catch (e: IllegalArgumentException) {
                // Android has removed the window already, as it does when the service disconnects.
                shown -= view
                false
            }
        }
        return try {
            windowManager.addView(view, params)
            shown += view
            true
        } catch (e: WindowManager.BadTokenException) {
            // The service has disconnected, so Android no longer lets it add windows.
            false
        }
    }

    fun hide(view: View) {
        if (!shown.remove(view)) return
        // Remove the window even when Android has not attached its view yet, which it does only on the next frame.
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            // Android has removed the window already, as it does when the service disconnects.
        }
    }

    /** The whole screen, system bars included, in screen pixels. */
    fun screenBounds(): IntRect = windowManager.maximumWindowMetrics.bounds.let { IntRect(it.left, it.top, it.right, it.bottom) }

    /** Removes every overlay for good. Later calls of [show] show nothing. */
    fun close() {
        closed = true
        shown.toList().forEach(::hide)
    }

    companion object {
        /**
         * A window over the whole screen, system bars included, that lets every touch through to the apps below.
         * The [title] names the window in Android's window list.
         */
        fun fullScreen(title: String): LayoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE or
                LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            setTitle(title)
        }
    }
}
```

`core/screen/src/main/kotlin/dev/kestrel/screen/overlay/NumbersOverlay.kt`:

```kotlin
package dev.kestrel.screen.overlay

/** Numbers on elements, for "tap 5" (design spec, section 9.4). Use it on the main thread. */
internal class NumbersOverlay(private val host: OverlayHost) {
    private val view = TagsView(host.context).apply { fillColor = NUMBER_COLOR }

    /** The numbers on the screen now; empty while the overlay is hidden. */
    @Volatile
    var badges: List<Badge> = emptyList()
        private set

    fun show(badges: List<Badge>) {
        view.tags = badges.map { Tag(it.number.toString(), it.bounds) }
        this.badges = if (host.show(view, OverlayHost.fullScreen("Kestrel numbers"))) badges else emptyList()
    }

    fun hide() {
        badges = emptyList()
        host.hide(view)
    }

    private companion object {
        const val NUMBER_COLOR = 0xE61565C0.toInt()
    }
}
```

`core/screen/src/main/kotlin/dev/kestrel/screen/overlay/LabelsOverlay.kt`:

```kotlin
package dev.kestrel.screen.overlay

import dev.kestrel.screen.model.ScreenElement

/** Names next to elements: their labels, or names derived from their view IDs (design spec, section 9.4). */
internal class LabelsOverlay(private val host: OverlayHost) {
    private val view = TagsView(host.context).apply { fillColor = LABEL_COLOR }

    /** Whether the labels are on the screen now. */
    @Volatile
    var isShowing: Boolean = false
        private set

    fun show(elements: List<ScreenElement>) {
        view.tags = elements.mapNotNull { element -> (element.label ?: element.suggestedName)?.let { Tag(it, element.bounds) } }
        isShowing = host.show(view, OverlayHost.fullScreen("Kestrel labels"))
    }

    fun hide() {
        isShowing = false
        host.hide(view)
    }

    private companion object {
        const val LABEL_COLOR = 0xE6424242.toInt()
    }
}
```

`core/screen/src/main/kotlin/dev/kestrel/screen/overlay/GridOverlay.kt`:

```kotlin
package dev.kestrel.screen.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View

/** The grid overlay (design spec, section 9.4). Use it on the main thread. */
internal class GridOverlay(private val host: OverlayHost) {
    private val view = GridView(host.context)

    /** The grid on the screen now, or null while it is hidden. */
    @Volatile
    var grid: Grid? = null
        private set

    fun show(grid: Grid) {
        view.grid = grid
        this.grid = grid.takeIf { host.show(view, OverlayHost.fullScreen("Kestrel grid")) }
    }

    fun hide() {
        grid = null
        host.hide(view)
    }
}

/** Draws the grid's lines and a number in the middle of each cell. */
internal class GridView(context: Context) : View(context) {
    var grid: Grid? = null
        set(value) {
            field = value
            invalidate()
        }

    private val origin = IntArray(2)
    private val line = Paint().apply {
        color = 0xCC1565C0.toInt()
        strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics)
    }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        val grid = grid ?: return
        getLocationOnScreen(origin)
        canvas.translate(-origin[0].toFloat(), -origin[1].toFloat())
        for (cell in 1..grid.cellCount) {
            val bounds = grid.cell(cell)
            canvas.drawLine(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.top.toFloat(), line)
            canvas.drawLine(bounds.left.toFloat(), bounds.top.toFloat(), bounds.left.toFloat(), bounds.bottom.toFloat(), line)
            val baseline = bounds.centerY - (number.ascent() + number.descent()) / 2
            canvas.drawText(cell.toString(), bounds.centerX.toFloat(), baseline, number)
        }
    }
}
```

`core/screen/src/main/kotlin/dev/kestrel/screen/overlay/FeedbackBar.kt`:

```kotlin
package dev.kestrel.screen.overlay

import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager.LayoutParams
import android.widget.TextView

/**
 * A thin bar at the top of the screen with what Kestrel heard and does. It never takes touches, and its text
 * fades after a few seconds without a new one (design spec, section 8.9). Use it on the main thread.
 */
internal class FeedbackBar(private val host: OverlayHost) {
    private val handler = Handler(Looper.getMainLooper())
    private val fade = Runnable { view.animate().alpha(0f).setDuration(FADE_MILLIS).withEndAction(::hide) }
    private val view = TextView(host.context).apply {
        setTextColor(Color.WHITE)
        setBackgroundColor(0xCC000000.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).toInt()
        setPadding(padding, padding, padding, padding)
    }

    /** The text on the bar now, or null while the bar is hidden. */
    @Volatile
    var text: String? = null
        private set

    fun show(text: String) {
        handler.removeCallbacks(fade)
        view.animate().cancel()
        view.alpha = 1f
        view.text = text
        if (host.show(view, params())) {
            this.text = text
            handler.postDelayed(fade, FADE_AFTER_MILLIS)
        } else {
            this.text = null
        }
    }

    fun hide() {
        handler.removeCallbacks(fade)
        text = null
        host.hide(view)
    }

    private fun params() = LayoutParams(
        LayoutParams.MATCH_PARENT,
        LayoutParams.WRAP_CONTENT,
        LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP
        setTitle("Kestrel feedback bar")
    }

    private companion object {
        const val FADE_AFTER_MILLIS = 4_000L
        const val FADE_MILLIS = 300L
    }
}
```

`core/screen/src/main/kotlin/dev/kestrel/screen/overlay/ConfirmationCard.kt`:

```kotlin
package dev.kestrel.screen.overlay

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager.LayoutParams
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.kestrel.screen.R
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * A card at the bottom of the screen with a question and Confirm and Cancel buttons (design spec, section 12.4).
 * Unlike the other overlays it takes touches, but only on itself. Use it on the main thread.
 */
internal class ConfirmationCard(private val host: OverlayHost) {
    private var pending: Question? = null

    /**
     * Shows [question] and returns true when the owner taps Confirm, or false for Cancel. It also returns false when a
     * newer question replaces this one or [dismiss] ends it, and its card then goes at once. Cancelling the call
     * removes the card too. When the card cannot be shown, because the session has closed, it returns false at once.
     */
    suspend fun ask(question: String): Boolean = suspendCancellableCoroutine { continuation ->
        dismiss()
        val open = Question(question, continuation)
        // Cancellation can arrive on any thread, and the window must be removed on the main thread.
        continuation.invokeOnCancellation { open.card.post { open.end(false) } }
        pending = open
        if (!host.show(open.card, params())) open.end(false)
    }

    /** Ends an open question without the owner's answer: its card goes at once, and its [ask] returns false. */
    fun dismiss() {
        pending?.end(false)
    }

    private inner class Question(question: String, private val continuation: CancellableContinuation<Boolean>) {
        val card = build(question, onConfirm = { end(true) }, onCancel = { end(false) })

        /** Removes the card and returns [answer] to the caller, unless the caller already has an answer or has cancelled. */
        fun end(answer: Boolean) {
            host.hide(card)
            // A late tap on this card, or its cancellation, may arrive after a newer question has replaced it.
            if (pending === this) pending = null
            if (continuation.isActive) continuation.resume(answer)
        }
    }

    private fun build(question: String, onConfirm: () -> Unit, onCancel: () -> Unit): LinearLayout {
        val context = host.context
        val padding = dp(16f).toInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(16f)
            }
            elevation = dp(8f)
            addView(TextView(context).apply {
                text = question
                setTextColor(Color.BLACK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            })
            addView(LinearLayout(context).apply {
                gravity = Gravity.END
                addView(Button(context).apply {
                    setText(R.string.screen_cancel)
                    setOnClickListener { onCancel() }
                })
                addView(Button(context).apply {
                    setText(R.string.screen_confirm)
                    setOnClickListener { onConfirm() }
                })
            })
        }
    }

    private fun params() = LayoutParams(
        host.context.resources.displayMetrics.widthPixels - 2 * dp(16f).toInt(),
        LayoutParams.WRAP_CONTENT,
        LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Touches outside the card go to the app below; Kestrel's touch watcher reports them (section 14.1).
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        fitInsetsTypes = WindowInsets.Type.systemBars()
        y = dp(16f).toInt()
        setTitle("Kestrel confirmation")
    }

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, host.context.resources.displayMetrics)
}
```

`core/screen/src/main/kotlin/dev/kestrel/screen/overlay/TouchWatcher.kt`:

```kotlin
package dev.kestrel.screen.overlay

import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.KeyCharacterMap
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager.LayoutParams
import dev.kestrel.screen.act.GestureRunner
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * An invisible window of one pixel that reports the owner's touches anywhere on the screen, so that a touch can
 * stop a running task (design spec, sections 9.4 and 14.1). Android tells the window about every touch that starts
 * outside it, unless the touch lands on a window above it, such as an overlay that Kestrel shows later. Use it on
 * the main thread.
 *
 * Android injects the touches of Kestrel's gestures from its virtual input device, while a finger's touches come
 * from the touchscreen, so the watcher leaves out touches from the virtual device while one of Kestrel's gestures
 * runs (spike S3). The device is the clue that works on every version: Android 13 to 15, and Android 16 before QPR2
 * depending on feature flags, mark a finger's touches as accessibility events while a gesture service is on, and from
 * Android 16 QPR2, Android no longer cancels Kestrel's gesture when a finger touches the screen (design spec, section
 * 14.1 and B35).
 */
internal class TouchWatcher(private val host: OverlayHost, private val gestures: GestureRunner) {
    private val touches = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    // On purpose: the watcher only listens for touches and offers no click action.
    @SuppressLint("ClickableViewAccessibility")
    private val view = View(host.context).apply {
        setOnTouchListener { _, event ->
            val starts = event.actionMasked == MotionEvent.ACTION_OUTSIDE || event.actionMasked == MotionEvent.ACTION_DOWN
            val kestrels = gestures.isRunning() && event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD
            if (starts && !kestrels) touches.tryEmit(Unit)
            false
        }
    }

    /** Emits when the owner touches the screen while the watcher runs. */
    val ownerTouches: SharedFlow<Unit> = touches.asSharedFlow()

    /** Starts watching. A second call only updates the window's layout. */
    fun start() {
        host.show(view, params())
    }

    fun stop() {
        host.hide(view)
    }

    private fun params() = LayoutParams(
        1,
        1,
        LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        setTitle("Kestrel touch watcher")
    }
}
```

`core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt`:

```kotlin
package dev.kestrel.screen

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import dev.kestrel.screen.act.GestureRunner
import dev.kestrel.screen.act.ScreenActions
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.overlay.Badge
import dev.kestrel.screen.overlay.ConfirmationCard
import dev.kestrel.screen.overlay.FeedbackBar
import dev.kestrel.screen.overlay.Grid
import dev.kestrel.screen.overlay.GridOverlay
import dev.kestrel.screen.overlay.LabelsOverlay
import dev.kestrel.screen.overlay.Numbering
import dev.kestrel.screen.overlay.NumbersMode
import dev.kestrel.screen.overlay.NumbersOverlay
import dev.kestrel.screen.overlay.OverlayHost
import dev.kestrel.screen.overlay.TouchWatcher
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.ScreenEvents
import dev.kestrel.screen.read.ScreenReader
import dev.kestrel.screen.read.SnapshotCache
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Kestrel's screen layer while its accessibility service is connected (design spec, section 9). [errors] handles the
 * failures of the session's background work, and it may write only class names and stack frames (design spec, section
 * 13.5).
 */
class ScreenSession(service: AccessibilityService, private val errors: CoroutineExceptionHandler) {
    private val reader = ScreenReader(service)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errors)
    private val overlays = OverlayHost(service)
    private val numbers = NumbersOverlay(overlays)
    private val labels = LabelsOverlay(overlays)
    private val gridOverlay = GridOverlay(overlays)
    private val feedback = FeedbackBar(overlays)
    private val card = ConfirmationCard(overlays)
    internal val tracker = ScreenChangeTracker(SystemClock::uptimeMillis)
    internal val gestures = GestureRunner(service)
    private val watcher = TouchWatcher(overlays, gestures)

    /** The current snapshot. A new one is built only after the screen has changed. */
    val snapshots = SnapshotCache(tracker) { withContext(Dispatchers.IO) { reader.read() } }

    val actions = ScreenActions(service, snapshots, gestures, tracker)

    // These fields change only on the main thread.
    /** Set while "show numbers" is on: the numbers then follow the screen. "Which one?" leaves it set. */
    private var numbersMode: NumbersMode? = null

    /** The screen content when the labels appeared. They go when it changes. */
    private var labelsShownOn: List<Pair<String, IntRect>>? = null

    /** The screen content when the "Which one?" numbers appeared. They go when it changes. */
    private var whichOneShownOn: List<Pair<String, IntRect>>? = null

    /** The numbers on the screen now, for commands such as "tap 5". */
    val numberBadges: List<Badge> get() = numbers.badges

    val labelsShowing: Boolean get() = labels.isShowing

    /** The grid on the screen now, or null. "Tap 5" taps the centre of the grid's cell 5. */
    val grid: Grid? get() = gridOverlay.grid

    /** The feedback bar's text now, or null while the bar is hidden. */
    val feedbackText: String? get() = feedback.text

    /** Emits when the owner touches the screen while the touch watcher runs (design spec, section 14.1). */
    val ownerTouches: SharedFlow<Unit> get() = watcher.ownerTouches

    init {
        scope.launch {
            // collect, not collectLatest: on a screen that changes all the time, such as a stopwatch, collectLatest would
            // cancel every check before it ran. The version is a state, so the check after a slow one sees the latest
            // version, and the snapshot cache waits until the screen is quiet, but never longer than 3 s.
            tracker.version.collect {
                if (numbersMode == null && labelsShownOn == null && whichOneShownOn == null) return@collect
                // A failed check must not end the watcher, or the numbers and labels would stop following the screen.
                // The next screen change runs the next check.
                try {
                    onScreenSettled(snapshots.current().snapshot)
                } catch (e: CancellationException) {
                    // Only the watcher's own cancellation ends it. A check that something inside it cancelled, such as
                    // a timeout, failed like any other check.
                    currentCoroutineContext().ensureActive()
                    errors.handleException(currentCoroutineContext(), e)
                } catch (e: Exception) {
                    errors.handleException(currentCoroutineContext(), e)
                }
            }
        }
    }

    /** Shows numbers on the actionable elements. They follow the screen until [hideNumbers] (design spec, section 9.4). */
    suspend fun showNumbers(mode: NumbersMode = NumbersMode.ALL) = withContext(Dispatchers.Main) {
        whichOneShownOn = null
        numbersMode = mode
        numbers.show(Numbering.forScreen(snapshots.current().snapshot, mode))
    }

    /**
     * Shows numbers on the elements with [keys] only, for "Which one?". They go when the screen content changes, and
     * then the numbers of "show numbers" come back if it is on.
     */
    suspend fun showWhichOne(keys: Collection<String>) = withContext(Dispatchers.Main) {
        val snapshot = snapshots.current().snapshot
        whichOneShownOn = contentOf(snapshot)
        numbers.show(Numbering.forCandidates(snapshot.elements.filter { it.key in keys }))
    }

    suspend fun hideNumbers() = withContext(Dispatchers.Main) {
        numbersMode = null
        whichOneShownOn = null
        numbers.hide()
    }

    /** Shows names next to the actionable elements until the screen content changes (design spec, section 9.4). */
    suspend fun showLabels() = withContext(Dispatchers.Main) {
        val snapshot = snapshots.current().snapshot
        labelsShownOn = contentOf(snapshot)
        labels.show(snapshot.elements.filter { it.isActionable })
    }

    suspend fun hideLabels() = withContext(Dispatchers.Main) {
        labelsShownOn = null
        labels.hide()
    }

    /** Shows [grid], by default one over the whole screen. "More squares" shows `grid.more()` (design spec, section 9.4). */
    suspend fun showGrid(grid: Grid? = null) = withContext(Dispatchers.Main) {
        gridOverlay.show(grid ?: Grid.over(overlays.screenBounds()))
    }

    suspend fun hideGrid() = withContext(Dispatchers.Main) { gridOverlay.hide() }

    /** Shows [text] on the feedback bar, which fades after a few seconds (design spec, section 8.9). */
    suspend fun showFeedback(text: String) = withContext(Dispatchers.Main) { feedback.show(text) }

    /**
     * Asks [question] on a confirmation card and returns true when the owner taps Confirm. It returns false when the
     * owner taps Cancel, when a newer question replaces this one or when the session closes, and the card then goes at
     * once. A caller that also listens for "confirm" or "cancel", or that gives up after 15 s, cancels this call, which
     * removes the card too (design spec, section 12.4).
     */
    suspend fun confirm(question: String): Boolean = withContext(Dispatchers.Main) { card.ask(question) }

    /** Starts reporting the owner's touches in [ownerTouches], for example while a task runs. */
    suspend fun startTouchWatcher() = withContext(Dispatchers.Main) { watcher.start() }

    suspend fun stopTouchWatcher() = withContext(Dispatchers.Main) { watcher.stop() }

    /** Kestrel's accessibility service passes every event here. */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (ScreenEvents.changesScreen(event.eventType)) tracker.onScreenChanged()
    }

    /**
     * Removes the overlays and stops the session's work. The service calls it on the main thread when it disconnects.
     * It may be called more than once.
     */
    fun close() {
        scope.cancel()
        card.dismiss()
        // Each overlay's own hide() also clears its state, such as the numbers or the feedback bar's pending fade.
        numbers.hide()
        labels.hide()
        gridOverlay.hide()
        feedback.hide()
        watcher.stop()
        overlays.close()
    }

    private fun onScreenSettled(snapshot: ScreenSnapshot) {
        val content = contentOf(snapshot)
        if (labelsShownOn != null && content != labelsShownOn) {
            labelsShownOn = null
            labels.hide()
        }
        if (whichOneShownOn != null) {
            if (content == whichOneShownOn) return
            // The candidates belong to the screen on which "Which one?" was asked.
            whichOneShownOn = null
            if (numbersMode == null) numbers.hide()
        }
        numbersMode?.let { mode ->
            val badges = Numbering.forScreen(snapshot, mode)
            if (badges != numbers.badges) numbers.show(badges)
        }
    }

    /** What the overlays depend on: the actionable elements and where they are. Kestrel's own overlays are not part of it. */
    private fun contentOf(snapshot: ScreenSnapshot) = snapshot.elements.filter { it.isActionable }.map { it.key to it.bounds }
}
```

- [ ] **Step 4: Run the tests again**

Run the command of Step 2 again. Expected: `BUILD SUCCESSFUL`; `OverlayTest` runs 13 tests and `TouchWatcherTest` 4.

- [ ] **Step 5: Run the checks and all device tests**

```powershell
scripts\gradle.ps1 check kestrelAudit
scripts\connected-test.ps1 -Serial <serial> :core:privacy:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL` twice; 7 device tests in `:core:privacy` and 76 in `:app`.

- [ ] **Step 6: Commit**

```powershell
git add core/screen app/src/androidTest
git commit -m "fix(screen): close every overlay and its state with the session" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 8: The settings app and the setup checklist

**Files:**
- Modify: `gradle/libs.versions.toml` and `app/build.gradle.kts` (Compose UI tests and Espresso 3.7.0)
- Modify: `app/src/main/AndroidManifest.xml` (`RECORD_AUDIO`, `POST_NOTIFICATIONS`)
- Modify: `app/src/debug/AndroidManifest.xml` (the test library's activity is not exported)
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt` (pure: the state of each step)
- Create: `app/src/main/kotlin/dev/kestrel/app/settings/SetupStatusReader.kt` (reads the state from Android)
- Create: `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt` (the pages and the home page)
- Create: `app/src/main/kotlin/dev/kestrel/app/settings/ChecklistPage.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`
- Create: `app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt`
- Create: `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`
- Create: `app/src/androidTest/kotlin/dev/kestrel/app/settings/SetupStatusReaderTest.kt`

**Interfaces:**
- Consumes: `KestrelAccessibilityService` (plan 1B).
- Produces: `enum class SetupStep`, `enum class StepState { DONE, TO_DO, LATER, OPTIONAL, WARNING }`, `data class SetupStatus(serviceOn, microphoneAllowed, notificationsAllowed, batteryUnrestricted, voiceAccessOn)`, `object SetupChecklist { fun states(status: SetupStatus): Map<SetupStep, StepState> }`, `class SetupStatusReader(context) { fun read(): SetupStatus }`, `enum class SettingsPage { HOME, CHECKLIST }`, `enum class SetupAction { ACCESSIBILITY_SETTINGS, PERMISSIONS, APP_SETTINGS }`, `@Composable fun SettingsApp(status: SetupStatus, onAction: (SetupAction) -> Unit)`, and the internal composables `PageEntry` and `PageHeader(title, onBack)`, which later pages use.

The settings app replaces the placeholder `MainActivity`. Its home page lists the pages; the first is the setup checklist, with how many of its 8 steps are done. The steps that later sub-projects bring (the models, the assistant and the provider) say "Comes later". `SetupStatusReader` reads the rest from Android: whether Kestrel's accessibility service and Voice Access (`com.google.android.apps.accessibility.voiceaccess`) are on, whether the two permissions are granted, and whether Kestrel's battery use is unrestricted. `MainActivity` reads the state again each time it comes to the front, because the owner changes these settings in other apps. Every page has a Back button, because the back gesture is hard for a voice user.

The permission button asks Android for both permissions. After two refusals Android no longer shows its dialog, so if a missing permission had no explanation (`shouldShowRequestPermissionRationale`) both before and after the request, Android showed no dialog for it, and the button opens Kestrel's app settings instead. After a first or a second refusal, the owner has just answered, so nothing else opens.

- [ ] **Step 1: Add the test libraries**

Replace the whole content of `gradle/libs.versions.toml` with:

```toml
[versions]
agp = "9.5.1"
kotlin = "2.4.21"
ksp = "2.3.13"
room = "2.8.5"
composeBom = "2026.09.00"
activityCompose = "1.13.0"
lifecycle = "2.11.0"
coreKtx = "1.19.1"
startup = "1.2.0"
coroutines = "1.11.0"
serialization = "1.13.0"
okhttp = "5.5.1"
junit = "4.13.2"
androidxTestExtJunit = "1.3.0"
androidxTestEspresso = "3.7.0"
androidxTestRunner = "1.7.0"
androidxTestRules = "1.7.0"
asm = "9.11.1"

[libraries]
android-gradleApi = { module = "com.android.tools.build:gradle-api", version.ref = "agp" }
kotlin-gradlePlugin = { module = "org.jetbrains.kotlin:kotlin-gradle-plugin", version.ref = "kotlin" }
asm = { module = "org.ow2.asm:asm", version.ref = "asm" }
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-startup-runtime = { module = "androidx.startup:startup-runtime", version.ref = "startup" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-compose-ui = { module = "androidx.compose.ui:ui" }
androidx-compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
androidx-compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
androidx-compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
androidx-room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
androidx-room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-mockwebserver3 = { module = "com.squareup.okhttp3:mockwebserver3", version.ref = "okhttp" }
junit = { module = "junit:junit", version.ref = "junit" }
kotlin-test-junit = { module = "org.jetbrains.kotlin:kotlin-test-junit", version.ref = "kotlin" }
androidx-test-ext-junit = { module = "androidx.test.ext:junit", version.ref = "androidxTestExtJunit" }
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidxTestRunner" }
androidx-test-rules = { module = "androidx.test:rules", version.ref = "androidxTestRules" }
androidx-test-espresso-core = { module = "androidx.test.espresso:espresso-core", version.ref = "androidxTestEspresso" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
room = { id = "androidx.room", version.ref = "room" }
```

Replace the whole content of `app/build.gradle.kts` with:

```kotlin
plugins {
    id("kestrel.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.kestrel.app"
    defaultConfig {
        applicationId = "dev.kestrel"
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:privacy"))
    implementation(project(":core:safety"))
    implementation(project(":core:screen"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.startup.runtime)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Compose's test library brings Espresso 3.5, which fails on Android 17: it looks for InputManager.getInstance().
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.okhttp.mockwebserver3)
}

// The instrumented tests drive the fixture screens of :testapp, so it is installed first. They also turn on
// Kestrel's accessibility service, so they run after the other modules' device tests instead of alongside them.
tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    dependsOn(":testapp:installDebug")
    mustRunAfter(":core:privacy:connectedDebugAndroidTest")
}
```

- [ ] **Step 2: Write the failing unit test**

Create `app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt`:

```kotlin
package dev.kestrel.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SetupChecklistTest {
    private val nothingSetUp = SetupStatus(
        serviceOn = false,
        microphoneAllowed = false,
        notificationsAllowed = false,
        batteryUnrestricted = false,
        voiceAccessOn = false,
    )

    @Test
    fun aNewInstallStillHasTheAndroidStepsToDo() {
        assertEquals(
            mapOf(
                SetupStep.INSTALL to StepState.DONE,
                SetupStep.COPY_MODELS to StepState.LATER,
                SetupStep.ACCESSIBILITY_SERVICE to StepState.TO_DO,
                SetupStep.MICROPHONE_AND_NOTIFICATIONS to StepState.TO_DO,
                SetupStep.BATTERY to StepState.TO_DO,
                SetupStep.DEFAULT_ASSISTANT to StepState.LATER,
                SetupStep.PROVIDER to StepState.LATER,
                SetupStep.VOICE_ACCESS to StepState.DONE,
            ),
            SetupChecklist.states(nothingSetUp),
        )
    }

    @Test
    fun thePermissionStepNeedsBothPermissions() {
        fun state(status: SetupStatus) = SetupChecklist.states(status).getValue(SetupStep.MICROPHONE_AND_NOTIFICATIONS)

        assertEquals(StepState.TO_DO, state(nothingSetUp.copy(microphoneAllowed = true)))
        assertEquals(StepState.TO_DO, state(nothingSetUp.copy(notificationsAllowed = true)))
        assertEquals(StepState.DONE, state(nothingSetUp.copy(microphoneAllowed = true, notificationsAllowed = true)))
    }

    @Test
    fun theServiceAndBatteryStepsAreDoneWhenSetUp() {
        val states = SetupChecklist.states(nothingSetUp.copy(serviceOn = true, batteryUnrestricted = true))

        assertEquals(StepState.DONE, states.getValue(SetupStep.ACCESSIBILITY_SERVICE))
        assertEquals(StepState.DONE, states.getValue(SetupStep.BATTERY))
    }

    @Test
    fun voiceAccessBesideKestrelIsAWarning() {
        fun state(status: SetupStatus) = SetupChecklist.states(status).getValue(SetupStep.VOICE_ACCESS)

        assertEquals(StepState.WARNING, state(nothingSetUp.copy(voiceAccessOn = true, serviceOn = true)))
        assertEquals(StepState.OPTIONAL, state(nothingSetUp.copy(voiceAccessOn = true)))
    }
}
```

```powershell
scripts\gradle.ps1 :app:testDebugUnitTest
```

Expected: `BUILD FAILED` with unresolved references to `SetupStatus`, `SetupChecklist`, `SetupStep` and `StepState`.

- [ ] **Step 3: Write the checklist's decisions**

Create `app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt`:

```kotlin
package dev.kestrel.app.settings

/** The steps of the setup checklist, in order (design spec, section 15). */
enum class SetupStep {
    INSTALL,
    COPY_MODELS,
    ACCESSIBILITY_SERVICE,
    MICROPHONE_AND_NOTIFICATIONS,
    BATTERY,
    DEFAULT_ASSISTANT,
    PROVIDER,
    VOICE_ACCESS,
}

/** Where a step of the setup checklist stands. */
enum class StepState {
    DONE,

    /** The owner still has to do the step. */
    TO_DO,

    /** A later sub-project adds what the step sets up. */
    LATER,

    /** The step is optional and not done. */
    OPTIONAL,

    /** Something needs the owner's attention, such as Voice Access running beside Kestrel. */
    WARNING,
}

/** What Kestrel can find out about its own setup on the phone. */
data class SetupStatus(
    val serviceOn: Boolean,
    val microphoneAllowed: Boolean,
    val notificationsAllowed: Boolean,
    val batteryUnrestricted: Boolean,
    val voiceAccessOn: Boolean,
)

/** Decides the state of each step of the setup checklist (design spec, section 15). */
object SetupChecklist {
    fun states(status: SetupStatus): Map<SetupStep, StepState> = SetupStep.entries.associateWith { step ->
        when (step) {
            // Kestrel is running, so it is installed.
            SetupStep.INSTALL -> StepState.DONE
            SetupStep.COPY_MODELS, SetupStep.DEFAULT_ASSISTANT, SetupStep.PROVIDER -> StepState.LATER
            SetupStep.ACCESSIBILITY_SERVICE -> doneIf(status.serviceOn)
            SetupStep.MICROPHONE_AND_NOTIFICATIONS -> doneIf(status.microphoneAllowed && status.notificationsAllowed)
            SetupStep.BATTERY -> doneIf(status.batteryUnrestricted)
            // Both apps would react to the same words.
            SetupStep.VOICE_ACCESS -> when {
                !status.voiceAccessOn -> StepState.DONE
                status.serviceOn -> StepState.WARNING
                else -> StepState.OPTIONAL
            }
        }
    }

    private fun doneIf(done: Boolean) = if (done) StepState.DONE else StepState.TO_DO
}
```

Run the command of Step 2 again. Expected: `BUILD SUCCESSFUL`, 4 tests.

- [ ] **Step 4: Add the permissions, the texts and the debug manifest**

Replace the whole content of `app/src/main/AndroidManifest.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <!-- Asked for in the setup checklist (design spec, sections 13.6 and 15). -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:name=".KestrelApplication"
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="Kestrel"
        android:networkSecurityConfig="@xml/network_security_config"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Reads the screen and acts on it (design spec, sections 7.2 and 9.1). -->
        <service
            android:name=".KestrelAccessibilityService"
            android:exported="true"
            android:label="Kestrel"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>

        <!-- EmojiCompat would ask Google Play services for a downloadable emoji font. -->
        <provider
            android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup"
            android:exported="false"
            tools:node="merge">
            <meta-data
                android:name="androidx.emoji2.text.EmojiCompatInitializer"
                tools:node="remove" />
        </provider>
    </application>
</manifest>
```

Replace the whole content of `app/src/debug/AndroidManifest.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <application>
        <!-- Test hooks for adb (design spec, section 16.1). Only callers that hold DUMP, in practice adb shell,
             can send commands. Release builds do not contain this receiver, and the audit checks that. -->
        <receiver
            android:name=".debug.TestHookReceiver"
            android:exported="true"
            android:permission="android.permission.DUMP" />

        <!-- Compose UI tests show their screens in this empty activity. ui-test-manifest declares it as exported, so
             any app could start it. The tests run in Kestrel's own process and can start it without that. -->
        <activity
            android:name="androidx.activity.ComponentActivity"
            android:exported="false"
            tools:replace="android:exported" />
    </application>
</manifest>
```

Replace the whole content of `app/src/main/res/values/strings.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="accessibility_service_description">Kestrel reads the screen and acts on it when you ask, so that you can use the phone by voice. What it reads stays on the phone, except what you choose to send to your own AI provider.</string>

    <string name="settings_title">Kestrel</string>
    <string name="settings_checklist">Setup checklist</string>
    <!-- The quantity is the number of steps in the checklist. -->
    <plurals name="settings_checklist_progress">
        <item quantity="one">%1$d of %2$d step done</item>
        <item quantity="other">%1$d of %2$d steps done</item>
    </plurals>
    <string name="settings_back">Back</string>

    <string name="setup_state_done">Done</string>
    <string name="setup_state_to_do">To do</string>
    <string name="setup_state_later">Comes later</string>
    <string name="setup_state_optional">Optional</string>
    <string name="setup_state_warning">Needs attention</string>

    <string name="setup_install_title">Install Kestrel</string>
    <string name="setup_install_detail">Kestrel is installed. scripts\\install.ps1 installs it with adb. On a Samsung phone, Auto Blocker must be off while USB debugging is on.</string>
    <string name="setup_models_title">Copy the voice models</string>
    <string name="setup_models_detail">scripts\\push-models.ps1 will copy the models from your computer. This step comes with Kestrel\'s voice features.</string>
    <string name="setup_service_title">Turn on Kestrel\'s accessibility service</string>
    <string name="setup_service_detail">Kestrel reads the screen and acts on it through this service.</string>
    <string name="setup_permissions_title">Allow the microphone and notifications</string>
    <string name="setup_permissions_detail">Kestrel listens for your voice and shows what it is doing in a notification.</string>
    <string name="setup_battery_title">Allow unrestricted battery use</string>
    <string name="setup_battery_detail">So that Android does not stop Kestrel. On a Samsung phone, also add Kestrel to \"Never sleeping apps\" in the battery settings.</string>
    <string name="setup_assistant_title">Make Kestrel the default digital assistant</string>
    <string name="setup_assistant_detail">Optional: for the side key and earbuds. This step comes with Kestrel\'s voice features.</string>
    <string name="setup_provider_title">Sign in to an AI provider and choose the models</string>
    <string name="setup_provider_detail">This step comes with Kestrel\'s provider settings.</string>
    <string name="setup_voice_access_title">Turn off Voice Access</string>
    <string name="setup_voice_access_detail">Optional: so that Voice Access and Kestrel do not react to the same words.</string>
    <string name="setup_voice_access_warning">Voice Access and Kestrel are both on, so they may react to the same words.</string>

    <string name="setup_open_accessibility">Open accessibility settings</string>
    <string name="setup_allow">Allow</string>
    <string name="setup_open_app_settings">Open app settings</string>
</resources>
```

- [ ] **Step 5: Write the reader, the pages and the activity**

Create `app/src/main/kotlin/dev/kestrel/app/settings/SetupStatusReader.kt`:

```kotlin
package dev.kestrel.app.settings

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.view.accessibility.AccessibilityManager
import dev.kestrel.app.KestrelAccessibilityService

/** Reads what Kestrel can find out about its own setup, for the setup checklist (design spec, section 15). */
class SetupStatusReader(private val context: Context) {
    fun read(): SetupStatus {
        val services = context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .map { ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) }
        return SetupStatus(
            serviceOn = ComponentName(context, KestrelAccessibilityService::class.java) in services,
            microphoneAllowed = granted(Manifest.permission.RECORD_AUDIO),
            notificationsAllowed = granted(Manifest.permission.POST_NOTIFICATIONS),
            batteryUnrestricted = context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName),
            voiceAccessOn = services.any { it.packageName == VOICE_ACCESS },
        )
    }

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val VOICE_ACCESS = "com.google.android.apps.accessibility.voiceaccess"
    }
}
```

Create `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt`:

```kotlin
package dev.kestrel.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kestrel.app.R

/** The pages of the settings app (design spec, section 15). Later sub-projects add their own pages. */
enum class SettingsPage { HOME, CHECKLIST }

/** What the button of a checklist step does. */
enum class SetupAction { ACCESSIBILITY_SETTINGS, PERMISSIONS, APP_SETTINGS }

/** Kestrel's settings app. Every page has a Back button, because a voice user cannot easily use the back gesture. */
@Composable
fun SettingsApp(status: SetupStatus, onAction: (SetupAction) -> Unit) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    BackHandler(enabled = page != SettingsPage.HOME) { page = SettingsPage.HOME }
    val states = SetupChecklist.states(status)
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
        when (page) {
            SettingsPage.HOME -> HomePage(states) { page = it }
            SettingsPage.CHECKLIST -> ChecklistPage(states, onAction) { page = SettingsPage.HOME }
        }
    }
}

@Composable
private fun HomePage(states: Map<SetupStep, StepState>, onOpen: (SettingsPage) -> Unit) {
    val done = states.values.count { it == StepState.DONE }
    Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
    PageEntry(
        stringResource(R.string.settings_checklist),
        pluralStringResource(R.plurals.settings_checklist_progress, states.size, done, states.size),
    ) { onOpen(SettingsPage.CHECKLIST) }
}

@Composable
private fun PageEntry(title: String, summary: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The Back button and the title at the top of every page except the home page. */
@Composable
internal fun PageHeader(title: String, onBack: () -> Unit) {
    Column {
        TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
        Text(title, style = MaterialTheme.typography.headlineSmall)
    }
}
```

Create `app/src/main/kotlin/dev/kestrel/app/settings/ChecklistPage.kt`:

```kotlin
package dev.kestrel.app.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kestrel.app.R

/** The setup checklist (design spec, section 15). */
@Composable
internal fun ChecklistPage(states: Map<SetupStep, StepState>, onAction: (SetupAction) -> Unit, onBack: () -> Unit) {
    PageHeader(stringResource(R.string.settings_checklist), onBack)
    for (step in SetupStep.entries) StepCard(step, states.getValue(step), onAction)
}

@Composable
private fun StepCard(step: SetupStep, state: StepState, onAction: (SetupAction) -> Unit) {
    val texts = texts(step)
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(texts.title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(stateLabel(state)), style = MaterialTheme.typography.labelLarge)
            val detail = if (state == StepState.WARNING && texts.warning != null) texts.warning else texts.detail
            Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium)
            if (texts.action != null && texts.actionLabel != null && state in NEEDS_ACTION) {
                Button(onClick = { onAction(texts.action) }) { Text(stringResource(texts.actionLabel)) }
            }
        }
    }
}

private class StepTexts(
    @StringRes val title: Int,
    @StringRes val detail: Int,
    val action: SetupAction? = null,
    @StringRes val actionLabel: Int? = null,
    @StringRes val warning: Int? = null,
)

private fun texts(step: SetupStep): StepTexts = when (step) {
    SetupStep.INSTALL -> StepTexts(R.string.setup_install_title, R.string.setup_install_detail)
    SetupStep.COPY_MODELS -> StepTexts(R.string.setup_models_title, R.string.setup_models_detail)
    SetupStep.ACCESSIBILITY_SERVICE -> StepTexts(
        R.string.setup_service_title,
        R.string.setup_service_detail,
        SetupAction.ACCESSIBILITY_SETTINGS,
        R.string.setup_open_accessibility,
    )
    SetupStep.MICROPHONE_AND_NOTIFICATIONS -> StepTexts(
        R.string.setup_permissions_title,
        R.string.setup_permissions_detail,
        SetupAction.PERMISSIONS,
        R.string.setup_allow,
    )
    SetupStep.BATTERY -> StepTexts(
        R.string.setup_battery_title,
        R.string.setup_battery_detail,
        SetupAction.APP_SETTINGS,
        R.string.setup_open_app_settings,
    )
    SetupStep.DEFAULT_ASSISTANT -> StepTexts(R.string.setup_assistant_title, R.string.setup_assistant_detail)
    SetupStep.PROVIDER -> StepTexts(R.string.setup_provider_title, R.string.setup_provider_detail)
    SetupStep.VOICE_ACCESS -> StepTexts(
        R.string.setup_voice_access_title,
        R.string.setup_voice_access_detail,
        SetupAction.ACCESSIBILITY_SETTINGS,
        R.string.setup_open_accessibility,
        R.string.setup_voice_access_warning,
    )
}

@StringRes
private fun stateLabel(state: StepState): Int = when (state) {
    StepState.DONE -> R.string.setup_state_done
    StepState.TO_DO -> R.string.setup_state_to_do
    StepState.LATER -> R.string.setup_state_later
    StepState.OPTIONAL -> R.string.setup_state_optional
    StepState.WARNING -> R.string.setup_state_warning
}

private val NEEDS_ACTION = setOf(StepState.TO_DO, StepState.OPTIONAL, StepState.WARNING)
```

Replace the whole content of `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt` with:

```kotlin
package dev.kestrel.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kestrel.app.settings.SettingsApp
import dev.kestrel.app.settings.SetupAction
import dev.kestrel.app.settings.SetupStatus
import dev.kestrel.app.settings.SetupStatusReader

/** Kestrel's settings app (design spec, section 15). */
class MainActivity : ComponentActivity() {
    private var status by mutableStateOf<SetupStatus?>(null)

    /** The permissions that were missing when the request started, and for which Android would show no explanation. */
    private var missingWithoutRationale = emptySet<String>()

    // After two refusals Android no longer shows its dialog. If a missing permission had no explanation both before and
    // after the request, Android showed no dialog for it, and the owner allows it in the app's settings instead.
    private val permissions = registerForActivityResult(RequestMultiplePermissions()) { results ->
        refresh()
        val noDialog = results.any { (permission, granted) ->
            !granted && permission in missingWithoutRationale && !shouldShowRequestPermissionRationale(permission)
        }
        if (noDialog) openAppSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                status?.let { SettingsApp(it, ::perform) }
            }
        }
    }

    // The owner may have changed a setting in another app, such as Android's accessibility settings.
    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        status = SetupStatusReader(this).read()
    }

    private fun perform(action: SetupAction) {
        when (action) {
            SetupAction.ACCESSIBILITY_SETTINGS -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            SetupAction.PERMISSIONS -> requestPermissions()
            SetupAction.APP_SETTINGS -> openAppSettings()
        }
    }

    private fun requestPermissions() {
        val wanted = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        missingWithoutRationale = wanted
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(it) }
            .toSet()
        permissions.launch(wanted)
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }
}
```

- [ ] **Step 6: Write the device tests and run them**

Create `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`:

```kotlin
package dev.kestrel.app.settings

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.kestrel.app.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsAppTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<SetupAction>()

    private fun show(status: SetupStatus) {
        compose.setContent { MaterialTheme { SettingsApp(status) { actions += it } } }
    }

    private fun text(@StringRes id: Int): String = compose.activity.getString(id)

    private fun progress(done: Int): String =
        compose.activity.resources.getQuantityString(R.plurals.settings_checklist_progress, 8, done, 8)

    private fun openChecklist() {
        compose.onNodeWithText(text(R.string.settings_checklist)).performClick()
        compose.onNodeWithText(text(R.string.setup_service_title)).assertExists()
    }

    @Test
    fun theHomePageCountsTheStepsDone() {
        show(NOTHING_SET_UP.copy(serviceOn = true))

        // Installing, the service, and Voice Access being off.
        compose.onNodeWithText(progress(3)).assertIsDisplayed()
    }

    @Test
    fun stepsToDoOfferTheirActions() {
        show(NOTHING_SET_UP)
        openChecklist()

        compose.onNodeWithText(text(R.string.setup_allow)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.setup_open_app_settings)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.setup_open_accessibility)).performScrollTo().performClick()

        assertEquals(
            listOf(SetupAction.PERMISSIONS, SetupAction.APP_SETTINGS, SetupAction.ACCESSIBILITY_SETTINGS),
            actions,
        )
    }

    @Test
    fun doneStepsOfferNoAction() {
        show(EVERYTHING_SET_UP)
        openChecklist()

        val labels = listOf(R.string.setup_allow, R.string.setup_open_app_settings, R.string.setup_open_accessibility)
        for (label in labels) {
            compose.onAllNodesWithText(text(label)).assertCountEquals(0)
        }
    }

    @Test
    fun voiceAccessBesideKestrelShowsTheWarning() {
        show(EVERYTHING_SET_UP.copy(voiceAccessOn = true))
        openChecklist()

        compose.onNodeWithText(text(R.string.setup_voice_access_warning)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setup_open_accessibility)).performScrollTo().performClick()
        assertEquals(listOf(SetupAction.ACCESSIBILITY_SETTINGS), actions)
    }

    @Test
    fun theBackButtonAndAndroidsBackReturnHome() {
        show(NOTHING_SET_UP)
        val progress = progress(2)

        openChecklist()
        compose.onNodeWithText(text(R.string.settings_back)).performClick()
        compose.onNodeWithText(progress).assertIsDisplayed()

        openChecklist()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(progress).assertIsDisplayed()
    }

    private companion object {
        val NOTHING_SET_UP = SetupStatus(
            serviceOn = false,
            microphoneAllowed = false,
            notificationsAllowed = false,
            batteryUnrestricted = false,
            voiceAccessOn = false,
        )
        val EVERYTHING_SET_UP = SetupStatus(
            serviceOn = true,
            microphoneAllowed = true,
            notificationsAllowed = true,
            batteryUnrestricted = true,
            voiceAccessOn = false,
        )
    }
}
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/settings/SetupStatusReaderTest.kt`:

```kotlin
package dev.kestrel.app.settings

import android.Manifest
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.screen.ScreenServiceRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SetupStatusReaderTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val reader = SetupStatusReader(context)

    @Test
    fun seesTheServiceTurnOff() = runBlocking {
        assertTrue(reader.read().serviceOn)

        screen.shell("settings delete secure enabled_accessibility_services")
        withTimeout(10_000) { (context.applicationContext as KestrelApplication).graph.screen.first { it == null } }

        assertFalse(reader.read().serviceOn)
    }

    // Revoking a permission would stop the app's process, and the tests with it, so this test only grants them.
    @Test
    fun seesTheGrantedPermissions() {
        screen.automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        screen.automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)

        val status = reader.read()
        assertTrue(status.microphoneAllowed)
        assertTrue(status.notificationsAllowed)
    }

    @Test
    fun seesUnrestrictedBatteryUse() {
        try {
            screen.shell("dumpsys deviceidle whitelist +${context.packageName}")
            assertTrue(reader.read().batteryUnrestricted)

            screen.shell("dumpsys deviceidle whitelist -${context.packageName}")
            assertFalse(reader.read().batteryUnrestricted)
        } finally {
            screen.shell("dumpsys deviceidle whitelist -${context.packageName}")
        }
    }
}
```

```powershell
scripts\connected-test.ps1 -Serial <serial> :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.package=dev.kestrel.app.settings'
```

Expected: `BUILD SUCCESSFUL`, 8 tests.

- [ ] **Step 7: Run the checks**

```powershell
scripts\gradle.ps1 check kestrelAudit
```

Expected: `BUILD SUCCESSFUL`, no `w:` line from the new files, and no lint finding in `app/build/reports/lint-results-debug.html`.

- [ ] **Step 8: Check the activity by hand**

No test covers `MainActivity`'s own code: the permission request, the settings it opens and the refresh when it comes to the front. Check them on the emulator. This helper taps the element that shows a text:

```powershell
. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; $s = '<serial>'
function Get-Texts { & $adb -s $s shell uiautomator dump /sdcard/ui.xml | Out-Null; ([xml](& $adb -s $s exec-out cat /sdcard/ui.xml)).SelectNodes('//node') | Where-Object { $_.text } }
function Invoke-Tap([string]$Text) { $n = Get-Texts | Where-Object { $_.text -eq $Text } | Select-Object -First 1; $b = [regex]::Match($n.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]').Groups; & $adb -s $s shell input tap (([int]$b[1].Value + [int]$b[3].Value) / 2) (([int]$b[2].Value + [int]$b[4].Value) / 2); Start-Sleep -Seconds 2 }
function Get-Top { (& $adb -s $s shell dumpsys activity activities | Select-String 'topResumedActivity' | Select-Object -First 1).Line.Trim() }
scripts\install.ps1 -Serial $s
& $adb -s $s shell am start -W -n dev.kestrel/dev.kestrel.app.MainActivity
(Get-Texts).text
```

Expected: `Kestrel`, `Setup checklist` and `2 of 8 steps done` (installing, and Voice Access being off).

```powershell
$no = "Don$([char]0x2019)t allow"
Invoke-Tap 'Setup checklist'; Invoke-Tap 'Allow'; Invoke-Tap $no; Invoke-Tap $no; Get-Top
Invoke-Tap 'Allow'; Invoke-Tap $no; Invoke-Tap $no; Get-Top
Invoke-Tap 'Allow'; Get-Top
```

Expected: the first two lines end with `dev.kestrel/.app.MainActivity ...`: Android showed both dialogs twice, and Kestrel stayed. The last line shows `com.android.settings/...`: Android showed no dialog the third time, so Kestrel opened its app settings.

```powershell
& $adb -s $s shell pm grant dev.kestrel android.permission.RECORD_AUDIO
& $adb -s $s shell pm grant dev.kestrel android.permission.POST_NOTIFICATIONS
& $adb -s $s shell dumpsys deviceidle whitelist +dev.kestrel
& $adb -s $s shell settings put secure enabled_accessibility_services dev.kestrel/dev.kestrel.app.KestrelAccessibilityService
& $adb -s $s shell input keyevent KEYCODE_BACK; Start-Sleep -Seconds 2
(Get-Texts).text | Where-Object { $_ -in 'Done', 'To do', 'Comes later' }
& $adb -s $s shell input keyevent KEYCODE_BACK; Start-Sleep -Seconds 2
(Get-Texts).text
```

Expected: back on the checklist, the first five states read `Done`, `Comes later`, `Done`, `Done`, `Done`; Android's back then shows the home page with `5 of 8 steps done`. Restore the emulator:

```powershell
& $adb -s $s shell settings delete secure enabled_accessibility_services
& $adb -s $s shell dumpsys deviceidle whitelist -dev.kestrel
& $adb -s $s shell rm -f /sdcard/ui.xml
& $adb -s $s uninstall dev.kestrel
```

- [ ] **Step 9: Run all device tests**

```powershell
scripts\connected-test.ps1 -Serial <serial> :core:privacy:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`, 7 tests in `:core:privacy` and 84 in `:app`.

- [ ] **Step 10: Commit**

```powershell
git add gradle/libs.versions.toml app
git commit -m "feat(app): add the settings app with the setup checklist" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 9: The safety page, the log pages and the log export

**Files:**
- Create: `core/privacy/src/main/kotlin/dev/kestrel/privacy/logs/LogExport.kt`
- Modify: `core/privacy/src/main/kotlin/dev/kestrel/privacy/logs/LogStore.kt` (`export`)
- Modify: `core/privacy/src/test/kotlin/dev/kestrel/privacy/logs/LogStoreTest.kt`
- Create: `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt`
- Create: `app/src/main/kotlin/dev/kestrel/app/settings/SafetyPage.kt`
- Create: `app/src/main/kotlin/dev/kestrel/app/settings/LogPages.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt` (three more pages; each page scrolls on its own)
- Modify: `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt` (the export)
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`
- Create: `app/src/androidTest/kotlin/dev/kestrel/app/MainActivityTest.kt`
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 12.5 and 13.5)

**Interfaces:**
- Consumes: Task 8's settings app; `LogStore` and `SettingsStore` from plan 1A; `SettingKeys.SAFETY`.
- Produces: `@Serializable data class LogExport(exportedAt: String, activity: List<Entry<ActivityEvent>>, network: List<Entry<NetworkEvent>>)` with `Entry<T>(time: String, event: T)`; `suspend fun LogStore.export(output: OutputStream)`; `interface SettingsData { val testMode: Flow<Boolean>; suspend fun setTestMode(on: Boolean); suspend fun activityLog(); suspend fun networkLog(); suspend fun deleteHistory() }` and `class StoredSettingsData(graph: AppGraph)`; `SettingsPage.SAFETY`, `ACTIVITY_LOG` and `NETWORK_LOG`; `SettingsApp(status, data, onAction, onExportLogs)`.

The safety page has the test-mode switch. Its whole row is one switch, so that the label names it for Voice Access and TalkBack. The log pages list both logs, newest entry first, with the export and "Delete history" buttons at the top; both buttons act on both logs, and deleting asks first. The pages read and change Kestrel's data through `SettingsData`, so that the Compose tests can use a fake, and `MainActivityTest` checks the real stores.

The export writes one JSON file with both logs, every entry oldest first, with times in UTC, to a file that the owner picks with Android's file picker. It reads every entry, not only the 500 that a log page shows. A failed write shows "Kestrel could not write the file."

Section 12.5 of the spec gets a rule that this page makes urgent: Kestrel's own app is always agent-free, so that a future agent cannot change Kestrel's own settings, such as test mode.

- [ ] **Step 1: Write the failing export tests**

Replace the whole content of `core/privacy/src/test/kotlin/dev/kestrel/privacy/logs/LogStoreTest.kt` with:

```kotlin
package dev.kestrel.privacy.logs

import dev.kestrel.privacy.crypto.AesGcmFieldCipher
import dev.kestrel.privacy.db.LogDao
import dev.kestrel.privacy.db.LogEntryRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator

class LogStoreTest {
    private val dao = FakeLogDao()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private var now = 1_000_000_000_000L
    private val store = LogStore(dao, AesGcmFieldCipher { key }, clock = { now }, dispatcher = Dispatchers.Unconfined)

    @Test
    fun readsActivityBackNewestFirst() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "first"))
        now += 1_000
        store.append(ActivityEvent(ActivityKind.ACTION, "second", taskId = "task-1"))

        val entries = store.activity()

        assertEquals(listOf("second", "first"), entries.map { it.event.text })
        assertEquals(ActivityEvent(ActivityKind.ACTION, "second", taskId = "task-1"), entries.first().event)
        assertEquals(now, entries.first().timestampMillis)
    }

    @Test
    fun storesPayloadsEncrypted() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "a secret note"))

        val stored = dao.rows.single().payload.decodeToString(throwOnInvalidSequence = false)

        assertFalse(stored.contains("secret"))
    }

    @Test
    fun keepsTheTwoLogsApart() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "note"))
        store.append(networkEvent("api.example.com"))

        assertEquals(listOf("note"), store.activity().map { it.event.text })
        assertEquals(listOf("api.example.com"), store.network().map { it.event.host })
    }

    @Test
    fun keepsEntriesForSevenDays() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "old"))
        now += LogStore.RETENTION_MILLIS

        assertEquals(listOf("old"), store.activity().map { it.event.text })

        now += 1
        assertEquals(emptyList<String>(), store.activity().map { it.event.text })
    }

    @Test
    fun appendingRemovesExpiredEntries() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "old"))
        now += LogStore.RETENTION_MILLIS + 1

        store.append(ActivityEvent(ActivityKind.NOTE, "new"))

        assertEquals(1, dao.rows.size)
    }

    @Test
    fun keepsEntriesUpToOneDayInTheFuture() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "ahead"))
        now -= LogStore.FUTURE_TOLERANCE_MILLIS

        assertEquals(listOf("ahead"), store.activity().map { it.event.text })

        now -= 1
        assertEquals(emptyList<String>(), store.activity().map { it.event.text })
    }

    @Test
    fun appendingAfterTheClockWentBackRemovesEntriesFromTheFuture() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "written while the clock was two days ahead"))
        now -= 2 * 24 * 60 * 60 * 1000L

        store.append(ActivityEvent(ActivityKind.NOTE, "new"))

        assertEquals(listOf(now), dao.rows.map { it.timestampMillis })
    }

    @Test
    fun deleteHistoryWipesBothLogs() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "note"))
        store.append(networkEvent("api.example.com"))

        store.deleteHistory()

        assertEquals(0, dao.rows.size)
    }

    @Test
    fun exportWritesBothLogsOldestFirstWithTimesInUtc() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "first"))
        now += 1_000
        store.append(networkEvent("api.example.com"))
        now += 1_000
        store.append(ActivityEvent(ActivityKind.ACTION, "second", taskId = "task-1"))

        val export = exported()

        assertEquals("2001-09-09T01:46:42Z", export.exportedAt)
        assertEquals(
            listOf(
                LogExport.Entry("2001-09-09T01:46:40Z", ActivityEvent(ActivityKind.NOTE, "first")),
                LogExport.Entry("2001-09-09T01:46:42Z", ActivityEvent(ActivityKind.ACTION, "second", taskId = "task-1")),
            ),
            export.activity,
        )
        assertEquals(listOf(LogExport.Entry("2001-09-09T01:46:41Z", networkEvent("api.example.com"))), export.network)
    }

    @Test
    fun exportIncludesMoreEntriesThanTheLogViewShows() = runTest {
        repeat(LogStore.DEFAULT_LIMIT + 1) { store.append(ActivityEvent(ActivityKind.NOTE, "note $it")) }

        assertEquals(LogStore.DEFAULT_LIMIT + 1, exported().activity.size)
    }

    private suspend fun exported(): LogExport {
        val output = ByteArrayOutputStream()
        store.export(output)
        return Json.decodeFromString(LogExport.serializer(), output.toByteArray().decodeToString())
    }

    @Test
    fun runsTheCipherOnTheStoreDispatcher() = runTest {
        val threads = CopyOnWriteArrayList<Thread>()
        val cipher = AesGcmFieldCipher { key.also { threads += Thread.currentThread() } }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val store = LogStore(dao, cipher, clock = { now }, dispatcher = dispatcher)
            store.append(ActivityEvent(ActivityKind.NOTE, "note"))
            store.activity()

            assertEquals(setOf(withContext(dispatcher) { Thread.currentThread() }), threads.toSet())
        }
    }

    @Test
    fun deletesEntriesThatCanNeverBeReadAndSaysSo() = runTest {
        store.append(ActivityEvent(ActivityKind.NOTE, "good"))
        val cipher = AesGcmFieldCipher { key }
        val activityPlace = "log_entries/activity".encodeToByteArray()
        val networkPlace = "log_entries/network".encodeToByteArray()
        val damaged = byteArrayOf(1, 2, 3)
        val oldFormat = cipher.encrypt("""{"text":"written before 'kind' existed"}""".encodeToByteArray(), activityPlace)
        val movedFromNetworkLog = cipher.encrypt("""{"kind":"NOTE","text":"moved"}""".encodeToByteArray(), networkPlace)
        listOf(damaged, oldFormat, movedFromNetworkLog).forEachIndexed { index, payload ->
            dao.rows += LogEntryRow(id = 100L + index, log = "activity", timestampMillis = now, payload = payload)
        }

        assertEquals(listOf("good"), store.activity().map { it.event.text })
        assertEquals(emptyList<Long>(), dao.rows.map { it.id }.filter { it in 100L..102L })
        assertEquals(listOf("Deleted 3 unreadable activity log entries", "good"), store.activity().map { it.event.text })
    }

    @Test
    fun keepPurgingPurgesOnceADayByTheWallClock() = runTest {
        val job = backgroundScope.launch { store.keepPurging { throw it } }
        runCurrent()
        assertEquals(1, dao.purges)

        // The phone slept: an hour of the coroutine's time, but a whole day on the wall clock.
        now += LogStore.DAILY_PURGE_MILLIS
        advanceTimeBy(LogStore.PURGE_CHECK_MILLIS)
        runCurrent()
        assertEquals(2, dao.purges)

        // A day of hourly checks without a day passing on the wall clock purges nothing more.
        repeat(23) {
            now += LogStore.PURGE_CHECK_MILLIS / 2
            advanceTimeBy(LogStore.PURGE_CHECK_MILLIS)
            runCurrent()
        }
        assertEquals(2, dao.purges)
        job.cancel()
    }

    @Test
    fun keepPurgingTriesAgainAfterAFailure() = runTest {
        dao.failNextPurge = true
        val failures = mutableListOf<Throwable>()
        val job = backgroundScope.launch { store.keepPurging { failures += it } }
        runCurrent()
        assertEquals(listOf("the disk is full"), failures.map { it.message })

        advanceTimeBy(LogStore.PURGE_CHECK_MILLIS)
        runCurrent()

        assertEquals(2, dao.purges)
        assertEquals(1, failures.size)
        job.cancel()
    }

    private fun networkEvent(host: String) = NetworkEvent(
        method = "GET",
        host = host,
        path = "/",
        requestBytes = 0,
        responseBytes = 10,
        status = 200,
        outcome = NetworkOutcome.COMPLETED,
    )
}

/** Mimics the SQL in [LogDao]; KestrelDatabaseTest checks the real queries on a device. */
private class FakeLogDao : LogDao {
    val rows = mutableListOf<LogEntryRow>()
    private var nextId = 1L
    var purges = 0
    var failNextPurge = false

    override suspend fun insert(row: LogEntryRow): Long {
        val id = nextId++
        rows += LogEntryRow(id, row.log, row.timestampMillis, row.payload)
        return id
    }

    override suspend fun newest(log: String, limit: Int): List<LogEntryRow> =
        rows.filter { it.log == log }
            .sortedWith(compareByDescending<LogEntryRow> { it.timestampMillis }.thenByDescending { it.id })
            .take(limit)

    override suspend fun deleteOutside(oldestMillis: Long, newestMillis: Long): Int {
        purges++
        if (failNextPurge) {
            failNextPurge = false
            throw IllegalStateException("the disk is full")
        }
        val outside = rows.filter { it.timestampMillis < oldestMillis || it.timestampMillis > newestMillis }
        rows.removeAll(outside)
        return outside.size
    }

    override suspend fun delete(id: Long): Int = if (rows.removeIf { it.id == id }) 1 else 0

    override suspend fun deleteAll(): Int {
        val count = rows.size
        rows.clear()
        return count
    }
}
```

```powershell
scripts\gradle.ps1 :core:privacy:testDebugUnitTest --tests 'dev.kestrel.privacy.logs.LogStoreTest'
```

Expected: `BUILD FAILED` with unresolved references to `LogExport` and `export`.

- [ ] **Step 2: Write the export**

Create `core/privacy/src/main/kotlin/dev/kestrel/privacy/logs/LogExport.kt`:

```kotlin
package dev.kestrel.privacy.logs

import kotlinx.serialization.Serializable

/**
 * The file that an export writes: both logs with all their entries, oldest first, and times in UTC as ISO 8601
 * (design spec, section 13.5).
 */
@Serializable
data class LogExport(
    val exportedAt: String,
    val activity: List<Entry<ActivityEvent>>,
    val network: List<Entry<NetworkEvent>>,
) {
    @Serializable
    data class Entry<T>(val time: String, val event: T)
}
```

Replace the whole content of `core/privacy/src/main/kotlin/dev/kestrel/privacy/logs/LogStore.kt` with:

```kotlin
package dev.kestrel.privacy.logs

import dev.kestrel.privacy.crypto.FieldCipher
import dev.kestrel.privacy.db.LogDao
import dev.kestrel.privacy.db.LogEntryRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.time.Instant
import javax.crypto.AEADBadTagException

data class LogEntry<T>(val id: Long, val timestampMillis: Long, val event: T)

/**
 * The encrypted activity log and network log, which keep entries for 7 days (design spec, section 13.5).
 * Its functions are safe to call from the main thread, because they encrypt, decrypt and query on [dispatcher].
 */
class LogStore(
    private val dao: LogDao,
    private val cipher: FieldCipher,
    private val clock: () -> Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val exportJson = Json { prettyPrint = true }

    @Volatile
    private var lastPurgeMillis: Long? = null

    suspend fun append(event: ActivityEvent) =
        withContext(dispatcher) { append(ACTIVITY, ActivityEvent.serializer(), event) }

    suspend fun append(event: NetworkEvent) =
        withContext(dispatcher) { append(NETWORK, NetworkEvent.serializer(), event) }

    suspend fun activity(limit: Int = DEFAULT_LIMIT): List<LogEntry<ActivityEvent>> =
        withContext(dispatcher) { read(ACTIVITY, ActivityEvent.serializer(), limit) }

    suspend fun network(limit: Int = DEFAULT_LIMIT): List<LogEntry<NetworkEvent>> =
        withContext(dispatcher) { read(NETWORK, NetworkEvent.serializer(), limit) }

    /**
     * Deletes the entries of both logs that are older than 7 days. It also deletes entries dated more than a
     * day ahead, which were written while the clock was wrong and would otherwise outlive the 7 days.
     */
    suspend fun purgeExpired(): Int = withContext(dispatcher) {
        val now = clock()
        lastPurgeMillis = now
        dao.deleteOutside(oldestMillis = now - RETENTION_MILLIS, newestMillis = now + FUTURE_TOLERANCE_MILLIS)
    }

    /**
     * Purges the logs once a day for as long as it runs, for a service that runs for days (design spec, section 13.5).
     * It checks every hour against [clock], because a coroutine's delay stops counting while the phone sleeps. A
     * failed purge goes to [onFailure], and the next check tries again.
     */
    suspend fun keepPurging(onFailure: (Throwable) -> Unit): Nothing {
        var lastPurge: Long? = null
        while (true) {
            val now = clock()
            val last = lastPurge
            // After the clock went back, the interval check alone would wait until the clock caught up again.
            if (last == null || now < last || now - last >= DAILY_PURGE_MILLIS) {
                try {
                    purgeExpired()
                    lastPurge = now
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onFailure(e)
                }
            }
            delay(PURGE_CHECK_MILLIS)
        }
    }

    /**
     * Writes both logs to [output] as a [LogExport] in JSON, with every entry, not only the newest. The logs leave the
     * phone only this way, when the owner exports them to a file of their choice (design spec, section 13.5).
     */
    suspend fun export(output: OutputStream) = withContext(dispatcher) {
        val export = LogExport(
            exportedAt = isoTime(clock()),
            activity = read(ACTIVITY, ActivityEvent.serializer(), Int.MAX_VALUE).asReversed().map(::exported),
            network = read(NETWORK, NetworkEvent.serializer(), Int.MAX_VALUE).asReversed().map(::exported),
        )
        output.write(exportJson.encodeToString(LogExport.serializer(), export).encodeToByteArray())
    }

    private fun <T> exported(entry: LogEntry<T>) = LogExport.Entry(isoTime(entry.timestampMillis), entry.event)

    private fun isoTime(millis: Long) = Instant.ofEpochMilli(millis).toString()

    /** "Delete history" wipes both logs (design spec, section 13.5). */
    suspend fun deleteHistory(): Int = withContext(dispatcher) { dao.deleteAll() }

    private suspend fun <T> append(log: String, serializer: KSerializer<T>, event: T) {
        val now = clock()
        val lastPurge = lastPurgeMillis
        // After the clock went back, the interval check alone would wait until the clock caught up again.
        if (lastPurge == null || now < lastPurge || now - lastPurge >= PURGE_INTERVAL_MILLIS) purgeExpired()
        val plaintext = json.encodeToString(serializer, event).encodeToByteArray()
        dao.insert(LogEntryRow(log = log, timestampMillis = now, payload = cipher.encrypt(plaintext, associatedData(log))))
    }

    /**
     * Returns the newest entries. An entry that can never be read is deleted, so that it cannot block the
     * whole log, and the activity log says how many were deleted.
     */
    private suspend fun <T> read(log: String, serializer: KSerializer<T>, limit: Int): List<LogEntry<T>> {
        purgeExpired()
        val entries = mutableListOf<LogEntry<T>>()
        var unreadable = 0
        for (row in dao.newest(log, limit)) {
            val event = decodeOrNull(log, serializer, row)
            if (event == null) {
                dao.delete(row.id)
                unreadable++
            } else {
                entries += LogEntry(row.id, row.timestampMillis, event)
            }
        }
        if (unreadable > 0) {
            append(ACTIVITY, ActivityEvent.serializer(), ActivityEvent(ActivityKind.ERROR, "Deleted $unreadable unreadable $log log entries"))
        }
        return entries
    }

    /** Returns null for a payload that is damaged, was encrypted for another place or with a lost key, or has an old format. */
    private fun <T> decodeOrNull(log: String, serializer: KSerializer<T>, row: LogEntryRow): T? =
        try {
            json.decodeFromString(serializer, cipher.decrypt(row.payload, associatedData(log)).decodeToString())
        } catch (e: AEADBadTagException) {
            null
        } catch (e: IllegalArgumentException) {
            // Also covers kotlinx.serialization's SerializationException, which extends IllegalArgumentException.
            null
        }

    private fun associatedData(log: String) = "log_entries/$log".encodeToByteArray()

    companion object {
        const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000

        /** An entry dated further ahead than this was written while the clock was wrong. */
        const val FUTURE_TOLERANCE_MILLIS = 24L * 60 * 60 * 1000

        const val PURGE_INTERVAL_MILLIS = 60L * 60 * 1000
        const val DAILY_PURGE_MILLIS = 24L * 60 * 60 * 1000
        const val PURGE_CHECK_MILLIS = 60L * 60 * 1000
        const val DEFAULT_LIMIT = 500
        private const val ACTIVITY = "activity"
        private const val NETWORK = "network"
    }
}
```

Run the command of Step 1 again. Expected: `BUILD SUCCESSFUL`, 14 tests.

- [ ] **Step 3: Write the pages**

Create `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt`:

```kotlin
package dev.kestrel.app.settings

import dev.kestrel.app.AppGraph
import dev.kestrel.app.SettingKeys
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.logs.LogEntry
import dev.kestrel.privacy.logs.NetworkEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** What the settings pages read and change. [StoredSettingsData] connects them to Kestrel's stores; tests use a fake. */
interface SettingsData {
    val testMode: Flow<Boolean>

    suspend fun setTestMode(on: Boolean)

    suspend fun activityLog(): List<LogEntry<ActivityEvent>>

    suspend fun networkLog(): List<LogEntry<NetworkEvent>>

    /** Wipes both logs (design spec, section 13.5). */
    suspend fun deleteHistory()
}

class StoredSettingsData(private val graph: AppGraph) : SettingsData {
    override val testMode: Flow<Boolean> = graph.settings.observe(SettingKeys.SAFETY).map { it.testMode }

    override suspend fun setTestMode(on: Boolean) {
        graph.settings.set(SettingKeys.SAFETY, graph.settings.get(SettingKeys.SAFETY).copy(testMode = on))
    }

    override suspend fun activityLog() = graph.logs.activity()

    override suspend fun networkLog() = graph.logs.network()

    override suspend fun deleteHistory() {
        graph.logs.deleteHistory()
    }
}
```

Create `app/src/main/kotlin/dev/kestrel/app/settings/SafetyPage.kt`:

```kotlin
package dev.kestrel.app.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.kestrel.app.R
import kotlinx.coroutines.launch

/** The safety page. In this version it has only test mode (design spec, section 12.7). */
@Composable
internal fun SafetyPage(data: SettingsData, onBack: () -> Unit) {
    val testMode by data.testMode.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    PageHeader(stringResource(R.string.settings_safety), onBack)
    // The whole row is one switch, so that its label names it for Voice Access and TalkBack.
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = testMode == true, enabled = testMode != null, role = Role.Switch) { on ->
                scope.launch { data.setTestMode(on) }
            }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.safety_test_mode), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = testMode == true, onCheckedChange = null, enabled = testMode != null)
    }
    Text(stringResource(R.string.safety_test_mode_detail), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.safety_later), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyMedium)
}
```

Create `app/src/main/kotlin/dev/kestrel/app/settings/LogPages.kt`:

```kotlin
package dev.kestrel.app.settings

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kestrel.app.R
import dev.kestrel.privacy.logs.ActivityKind
import dev.kestrel.privacy.logs.LogEntry
import dev.kestrel.privacy.logs.NetworkOutcome
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The activity log, newest entry first (design spec, section 13.5). */
@Composable
internal fun ActivityLogPage(data: SettingsData, onExport: () -> Unit, onBack: () -> Unit) {
    LogPage(R.string.settings_activity_log, data::activityLog, data, onExport, onBack) { entry ->
        val event = entry.event
        EntryCard(
            heading = stringResource(kindLabel(event.kind)),
            timeMillis = entry.timestampMillis,
            lines = listOfNotNull(event.text, event.taskId?.let { stringResource(R.string.log_task, it) }),
        )
    }
}

/** The network log, newest entry first (design spec, section 13.5). */
@Composable
internal fun NetworkLogPage(data: SettingsData, onExport: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    LogPage(R.string.settings_network_log, data::networkLog, data, onExport, onBack) { entry ->
        val event = entry.event
        EntryCard(
            heading = stringResource(outcomeLabel(event.outcome)),
            timeMillis = entry.timestampMillis,
            lines = listOfNotNull(
                "${event.method} ${event.host}${event.path}",
                stringResource(
                    R.string.log_network_sizes,
                    Formatter.formatShortFileSize(context, event.requestBytes),
                    Formatter.formatShortFileSize(context, event.responseBytes),
                ),
                event.status?.let { stringResource(R.string.log_network_status, it) },
                event.detail,
                event.taskId?.let { stringResource(R.string.log_task, it) },
            ),
        )
    }
}

/** A log page: the Back button, buttons that export and delete both logs (design spec, section 13.5), and the entries. */
@Composable
private fun <T> LogPage(
    @StringRes title: Int,
    load: suspend () -> List<LogEntry<T>>,
    data: SettingsData,
    onExport: () -> Unit,
    onBack: () -> Unit,
    entry: @Composable (LogEntry<T>) -> Unit,
) {
    var entries by remember { mutableStateOf<List<LogEntry<T>>?>(null) }
    var loads by remember { mutableIntStateOf(0) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(loads) { entries = load() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item { PageHeader(stringResource(title), onBack) }
        item {
            Column(Modifier.padding(vertical = 8.dp)) {
                Button(onClick = onExport) { Text(stringResource(R.string.logs_export)) }
                OutlinedButton(onClick = { confirmingDelete = true }) { Text(stringResource(R.string.logs_delete)) }
            }
        }
        val shown = entries
        when {
            shown == null -> item { Text(stringResource(R.string.logs_loading)) }
            shown.isEmpty() -> item { Text(stringResource(R.string.logs_empty)) }
            else -> items(shown, key = { it.id }) { entry(it) }
        }
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            text = { Text(stringResource(R.string.logs_delete_question)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        scope.launch {
                            data.deleteHistory()
                            loads++
                        }
                    },
                ) { Text(stringResource(R.string.logs_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.logs_delete_cancel)) }
            },
        )
    }
}

@Composable
private fun EntryCard(heading: String, timeMillis: Long, lines: List<String>) {
    val time = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .format(Instant.ofEpochMilli(timeMillis).atZone(ZoneId.systemDefault()))
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(heading, style = MaterialTheme.typography.titleSmall)
            Text(time, style = MaterialTheme.typography.labelMedium)
            for (line in lines) Text(line, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@StringRes
private fun kindLabel(kind: ActivityKind): Int = when (kind) {
    ActivityKind.ACTION -> R.string.log_kind_action
    ActivityKind.NOTE -> R.string.log_kind_note
    ActivityKind.ERROR -> R.string.log_kind_error
}

@StringRes
private fun outcomeLabel(outcome: NetworkOutcome): Int = when (outcome) {
    NetworkOutcome.COMPLETED -> R.string.log_outcome_completed
    NetworkOutcome.FAILED -> R.string.log_outcome_failed
    NetworkOutcome.BLOCKED -> R.string.log_outcome_blocked
}
```

Replace the whole content of `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt` with:

```kotlin
package dev.kestrel.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kestrel.app.R

/** The pages of the settings app (design spec, section 15). Later sub-projects add their own pages. */
enum class SettingsPage { HOME, CHECKLIST, SAFETY, ACTIVITY_LOG, NETWORK_LOG }

/** What the button of a checklist step does. */
enum class SetupAction { ACCESSIBILITY_SETTINGS, PERMISSIONS, APP_SETTINGS }

/**
 * Kestrel's settings app. Every page has a Back button, because a voice user cannot easily use the back gesture.
 * [onExportLogs] asks the owner for a file to export both logs to.
 */
@Composable
fun SettingsApp(status: SetupStatus, data: SettingsData, onAction: (SetupAction) -> Unit, onExportLogs: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    BackHandler(enabled = page != SettingsPage.HOME) { page = SettingsPage.HOME }
    val states = SetupChecklist.states(status)
    val home = { page = SettingsPage.HOME }
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        when (page) {
            SettingsPage.HOME -> ScrollingPage { HomePage(states, data) { page = it } }
            SettingsPage.CHECKLIST -> ScrollingPage { ChecklistPage(states, onAction, home) }
            SettingsPage.SAFETY -> ScrollingPage { SafetyPage(data, home) }
            SettingsPage.ACTIVITY_LOG -> ActivityLogPage(data, onExportLogs, home)
            SettingsPage.NETWORK_LOG -> NetworkLogPage(data, onExportLogs, home)
        }
    }
}

/** A page that scrolls as a whole. The log pages scroll their lists themselves. */
@Composable
private fun ScrollingPage(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), content = content)
}

@Composable
private fun HomePage(states: Map<SetupStep, StepState>, data: SettingsData, onOpen: (SettingsPage) -> Unit) {
    val done = states.values.count { it == StepState.DONE }
    val testMode by data.testMode.collectAsState(initial = null)
    val logsSummary = stringResource(R.string.settings_logs_summary)
    Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
    PageEntry(
        stringResource(R.string.settings_checklist),
        pluralStringResource(R.plurals.settings_checklist_progress, states.size, done, states.size),
    ) { onOpen(SettingsPage.CHECKLIST) }
    PageEntry(stringResource(R.string.settings_safety), testModeSummary(testMode)) { onOpen(SettingsPage.SAFETY) }
    PageEntry(stringResource(R.string.settings_activity_log), logsSummary) { onOpen(SettingsPage.ACTIVITY_LOG) }
    PageEntry(stringResource(R.string.settings_network_log), logsSummary) { onOpen(SettingsPage.NETWORK_LOG) }
}

@Composable
private fun testModeSummary(on: Boolean?): String = when (on) {
    true -> stringResource(R.string.settings_test_mode_on)
    false -> stringResource(R.string.settings_test_mode_off)
    null -> ""
}

@Composable
private fun PageEntry(title: String, summary: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The Back button and the title at the top of every page except the home page. */
@Composable
internal fun PageHeader(title: String, onBack: () -> Unit) {
    Column {
        TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
        Text(title, style = MaterialTheme.typography.headlineSmall)
    }
}
```

Replace the whole content of `app/src/main/res/values/strings.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="accessibility_service_description">Kestrel reads the screen and acts on it when you ask, so that you can use the phone by voice. What it reads stays on the phone, except what you choose to send to your own AI provider.</string>

    <string name="settings_title">Kestrel</string>
    <string name="settings_checklist">Setup checklist</string>
    <!-- The quantity is the number of steps in the checklist. -->
    <plurals name="settings_checklist_progress">
        <item quantity="one">%1$d of %2$d step done</item>
        <item quantity="other">%1$d of %2$d steps done</item>
    </plurals>
    <string name="settings_back">Back</string>
    <string name="settings_safety">Safety</string>
    <string name="settings_test_mode_on">Test mode is on</string>
    <string name="settings_test_mode_off">Test mode is off</string>
    <string name="settings_activity_log">Activity log</string>
    <string name="settings_network_log">Network log</string>
    <string name="settings_logs_summary">Encrypted, and kept for 7 days</string>

    <string name="safety_test_mode">Test mode</string>
    <string name="safety_test_mode_detail">While test mode is on, Kestrel sends and shares only to Telegram\'s Saved Messages, WhatsApp\'s Message yourself, Viber\'s My Notes and your own email address, and it makes no calls. Keep it on while you test Kestrel.</string>
    <string name="safety_later">The safety mode, agent-free apps and screenshot approvals come with Kestrel\'s agent.</string>

    <string name="logs_export">Export both logs</string>
    <string name="logs_delete">Delete history</string>
    <string name="logs_delete_question">Delete the activity log and the network log? This cannot be undone.</string>
    <string name="logs_delete_confirm">Delete</string>
    <string name="logs_delete_cancel">Cancel</string>
    <string name="logs_loading">Reading the log…</string>
    <string name="logs_empty">No entries in the last 7 days.</string>
    <string name="logs_exported">Kestrel exported both logs.</string>
    <string name="logs_export_failed">Kestrel could not write the file.</string>
    <string name="log_kind_action">Action</string>
    <string name="log_kind_note">Note</string>
    <string name="log_kind_error">Error</string>
    <string name="log_outcome_completed">Completed</string>
    <string name="log_outcome_failed">Failed</string>
    <string name="log_outcome_blocked">Blocked</string>
    <string name="log_task">Task %1$s</string>
    <string name="log_network_sizes">Sent %1$s, received %2$s</string>
    <string name="log_network_status">Status %1$d</string>

    <string name="setup_state_done">Done</string>
    <string name="setup_state_to_do">To do</string>
    <string name="setup_state_later">Comes later</string>
    <string name="setup_state_optional">Optional</string>
    <string name="setup_state_warning">Needs attention</string>

    <string name="setup_install_title">Install Kestrel</string>
    <string name="setup_install_detail">Kestrel is installed. scripts\\install.ps1 installs it with adb. On a Samsung phone, Auto Blocker must be off while USB debugging is on.</string>
    <string name="setup_models_title">Copy the voice models</string>
    <string name="setup_models_detail">scripts\\push-models.ps1 will copy the models from your computer. This step comes with Kestrel\'s voice features.</string>
    <string name="setup_service_title">Turn on Kestrel\'s accessibility service</string>
    <string name="setup_service_detail">Kestrel reads the screen and acts on it through this service.</string>
    <string name="setup_permissions_title">Allow the microphone and notifications</string>
    <string name="setup_permissions_detail">Kestrel listens for your voice and shows what it is doing in a notification.</string>
    <string name="setup_battery_title">Allow unrestricted battery use</string>
    <string name="setup_battery_detail">So that Android does not stop Kestrel. On a Samsung phone, also add Kestrel to \"Never sleeping apps\" in the battery settings.</string>
    <string name="setup_assistant_title">Make Kestrel the default digital assistant</string>
    <string name="setup_assistant_detail">Optional: for the side key and earbuds. This step comes with Kestrel\'s voice features.</string>
    <string name="setup_provider_title">Sign in to an AI provider and choose the models</string>
    <string name="setup_provider_detail">This step comes with Kestrel\'s provider settings.</string>
    <string name="setup_voice_access_title">Turn off Voice Access</string>
    <string name="setup_voice_access_detail">Optional: so that Voice Access and Kestrel do not react to the same words.</string>
    <string name="setup_voice_access_warning">Voice Access and Kestrel are both on, so they may react to the same words.</string>

    <string name="setup_open_accessibility">Open accessibility settings</string>
    <string name="setup_allow">Allow</string>
    <string name="setup_open_app_settings">Open app settings</string>
</resources>
```

Replace the whole content of `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt` with:

```kotlin
package dev.kestrel.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.kestrel.app.settings.SettingsApp
import dev.kestrel.app.settings.SetupAction
import dev.kestrel.app.settings.SetupStatus
import dev.kestrel.app.settings.SetupStatusReader
import dev.kestrel.app.settings.StoredSettingsData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate

/** Kestrel's settings app (design spec, section 15). */
class MainActivity : ComponentActivity() {
    private val graph by lazy { (application as KestrelApplication).graph }
    private val data by lazy { StoredSettingsData(graph) }
    private var status by mutableStateOf<SetupStatus?>(null)

    private val exportLogs = registerForActivityResult(CreateDocument("application/json")) { uri -> uri?.let(::export) }

    /** The permissions that were missing when the request started, and for which Android would show no explanation. */
    private var missingWithoutRationale = emptySet<String>()

    // After two refusals Android no longer shows its dialog. If a missing permission had no explanation both before and
    // after the request, Android showed no dialog for it, and the owner allows it in the app's settings instead.
    private val permissions = registerForActivityResult(RequestMultiplePermissions()) { results ->
        refresh()
        val noDialog = results.any { (permission, granted) ->
            !granted && permission in missingWithoutRationale && !shouldShowRequestPermissionRationale(permission)
        }
        if (noDialog) openAppSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                status?.let { SettingsApp(it, data, ::perform) { exportLogs.launch("kestrel-logs-${LocalDate.now()}.json") } }
            }
        }
    }

    // The owner may have changed a setting in another app, such as Android's accessibility settings.
    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        status = SetupStatusReader(this).read()
    }

    private fun perform(action: SetupAction) {
        when (action) {
            SetupAction.ACCESSIBILITY_SETTINGS -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            SetupAction.PERMISSIONS -> requestPermissions()
            SetupAction.APP_SETTINGS -> openAppSettings()
        }
    }

    private fun requestPermissions() {
        val wanted = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        missingWithoutRationale = wanted
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(it) }
            .toSet()
        permissions.launch(wanted)
    }

    /** Writes both logs to the file that the owner chose. The logs leave the phone only this way (design spec, 13.5). */
    private fun export(uri: Uri) {
        lifecycleScope.launch(graph.errors) {
            val written = withContext(Dispatchers.IO) {
                try {
                    val output = contentResolver.openOutputStream(uri, "w") ?: return@withContext false
                    output.use { graph.logs.export(it) }
                    true
                } catch (e: IOException) {
                    false
                } catch (e: SecurityException) {
                    false
                }
            }
            val message = if (written) R.string.logs_exported else R.string.logs_export_failed
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }
}
```

- [ ] **Step 4: Write the device tests and run them**

Replace the whole content of `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt` with:

```kotlin
package dev.kestrel.app.settings

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.kestrel.app.R
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.logs.ActivityKind
import dev.kestrel.privacy.logs.LogEntry
import dev.kestrel.privacy.logs.NetworkEvent
import dev.kestrel.privacy.logs.NetworkOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class SettingsAppTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<SetupAction>()
    private val data = FakeSettingsData()
    private var exports = 0

    private fun show(status: SetupStatus) {
        compose.setContent { MaterialTheme { SettingsApp(status, data, { actions += it }) { exports++ } } }
    }

    private fun text(@StringRes id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun progress(done: Int): String =
        compose.activity.resources.getQuantityString(R.plurals.settings_checklist_progress, 8, done, 8)

    private fun openChecklist() {
        compose.onNodeWithText(text(R.string.settings_checklist)).performClick()
        compose.onNodeWithText(text(R.string.setup_service_title)).assertExists()
    }

    @Test
    fun theHomePageCountsTheStepsDone() {
        show(NOTHING_SET_UP.copy(serviceOn = true))

        // Installing, the service, and Voice Access being off.
        compose.onNodeWithText(progress(3)).assertIsDisplayed()
    }

    @Test
    fun stepsToDoOfferTheirActions() {
        show(NOTHING_SET_UP)
        openChecklist()

        compose.onNodeWithText(text(R.string.setup_allow)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.setup_open_app_settings)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.setup_open_accessibility)).performScrollTo().performClick()

        assertEquals(
            listOf(SetupAction.PERMISSIONS, SetupAction.APP_SETTINGS, SetupAction.ACCESSIBILITY_SETTINGS),
            actions,
        )
    }

    @Test
    fun doneStepsOfferNoAction() {
        show(EVERYTHING_SET_UP)
        openChecklist()

        val labels = listOf(R.string.setup_allow, R.string.setup_open_app_settings, R.string.setup_open_accessibility)
        for (label in labels) {
            compose.onAllNodesWithText(text(label)).assertCountEquals(0)
        }
    }

    @Test
    fun voiceAccessBesideKestrelShowsTheWarning() {
        show(EVERYTHING_SET_UP.copy(voiceAccessOn = true))
        openChecklist()

        compose.onNodeWithText(text(R.string.setup_voice_access_warning)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setup_open_accessibility)).performScrollTo().performClick()
        assertEquals(listOf(SetupAction.ACCESSIBILITY_SETTINGS), actions)
    }

    @Test
    fun theBackButtonAndAndroidsBackReturnHome() {
        show(NOTHING_SET_UP)
        val progress = progress(2)

        openChecklist()
        compose.onNodeWithText(text(R.string.settings_back)).performClick()
        compose.onNodeWithText(progress).assertIsDisplayed()

        openChecklist()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(progress).assertIsDisplayed()
    }

    @Test
    fun theSafetyPageSwitchesTestMode() {
        show(NOTHING_SET_UP)
        compose.onNodeWithText(text(R.string.settings_test_mode_on)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_safety)).performClick()

        compose.onNodeWithText(text(R.string.safety_test_mode)).assertIsOn().performClick()

        compose.onNodeWithText(text(R.string.safety_test_mode)).assertIsOff()
        assertFalse(data.testMode.value)
    }

    @Test
    fun theLogPagesShowTheirEntries() {
        data.activity = listOf(
            LogEntry(2, 2_000, ActivityEvent(ActivityKind.ACTION, "Tapped Send", taskId = "task-1")),
            LogEntry(1, 1_000, ActivityEvent(ActivityKind.NOTE, "Started")),
        )
        data.network = listOf(
            LogEntry(3, 3_000, NetworkEvent("POST", "api.example.com", "/v1/chat", 2_048, 512, 200, NetworkOutcome.COMPLETED)),
        )
        show(NOTHING_SET_UP)

        compose.onNodeWithText(text(R.string.settings_activity_log)).performClick()
        compose.onNodeWithText("Tapped Send").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.log_task, "task-1")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.log_kind_note)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_back)).performClick()

        compose.onNodeWithText(text(R.string.settings_network_log)).performClick()
        compose.onNodeWithText("POST api.example.com/v1/chat").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.log_network_status, 200)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.log_outcome_completed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.logs_export)).performClick()
        assertEquals(1, exports)
    }

    @Test
    fun deleteHistoryAsksFirst() {
        data.activity = listOf(LogEntry(1, 1_000, ActivityEvent(ActivityKind.NOTE, "Started")))
        show(NOTHING_SET_UP)
        compose.onNodeWithText(text(R.string.settings_activity_log)).performClick()

        compose.onNodeWithText(text(R.string.logs_delete)).performClick()
        compose.onNodeWithText(text(R.string.logs_delete_cancel)).performClick()
        compose.onNodeWithText("Started").assertIsDisplayed()
        assertEquals(0, data.deletions)

        compose.onNodeWithText(text(R.string.logs_delete)).performClick()
        compose.onNodeWithText(text(R.string.logs_delete_question)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.logs_delete_confirm)).performClick()

        compose.onNodeWithText(text(R.string.logs_empty)).assertIsDisplayed()
        assertEquals(1, data.deletions)
    }

    private class FakeSettingsData : SettingsData {
        override val testMode = MutableStateFlow(true)
        var activity = emptyList<LogEntry<ActivityEvent>>()
        var network = emptyList<LogEntry<NetworkEvent>>()
        var deletions = 0

        override suspend fun setTestMode(on: Boolean) {
            testMode.value = on
        }

        override suspend fun activityLog() = activity

        override suspend fun networkLog() = network

        override suspend fun deleteHistory() {
            deletions++
            activity = emptyList()
            network = emptyList()
        }
    }

    private companion object {
        val NOTHING_SET_UP = SetupStatus(
            serviceOn = false,
            microphoneAllowed = false,
            notificationsAllowed = false,
            batteryUnrestricted = false,
            voiceAccessOn = false,
        )
        val EVERYTHING_SET_UP = SetupStatus(
            serviceOn = true,
            microphoneAllowed = true,
            notificationsAllowed = true,
            batteryUnrestricted = true,
            voiceAccessOn = false,
        )
    }
}
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/MainActivityTest.kt`:

```kotlin
package dev.kestrel.app

import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.SemanticsMatcher
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.logs.ActivityKind
import dev.kestrel.privacy.logs.NetworkEvent
import dev.kestrel.privacy.logs.NetworkOutcome
import dev.kestrel.safety.SafetySettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Checks that the settings pages change Kestrel's real stores, which work on another thread than the pages. */
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = (compose.activity.application as KestrelApplication).graph

    private fun text(@StringRes id: Int): String = compose.activity.getString(id)

    private fun await(matcher: SemanticsMatcher) {
        compose.waitUntil(5_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    @After
    fun turnTestModeBackOn() = runBlocking {
        graph.settings.set(SettingKeys.SAFETY, SafetySettings(testMode = true))
    }

    @Test
    fun theSafetySwitchTurnsTestModeOff() {
        runBlocking { graph.settings.set(SettingKeys.SAFETY, SafetySettings(testMode = true)) }
        compose.onNodeWithText(text(R.string.settings_safety)).performClick()
        await(hasText(text(R.string.safety_test_mode)) and isOn())

        compose.onNodeWithText(text(R.string.safety_test_mode)).performClick()

        compose.waitUntil(5_000) { runBlocking { !graph.settings.get(SettingKeys.SAFETY).testMode } }
        compose.onNodeWithText(text(R.string.safety_test_mode)).assertIsOff()
    }

    @Test
    fun deleteHistoryWipesBothLogs() {
        runBlocking {
            graph.logs.append(ActivityEvent(ActivityKind.NOTE, "A note from MainActivityTest"))
            graph.logs.append(NetworkEvent("GET", "example.com", "/", 0, 0, outcome = NetworkOutcome.BLOCKED))
        }
        compose.onNodeWithText(text(R.string.settings_activity_log)).performClick()
        await(hasText("A note from MainActivityTest"))

        compose.onNodeWithText(text(R.string.logs_delete)).performClick()
        compose.onNodeWithText(text(R.string.logs_delete_confirm)).performClick()

        await(hasText(text(R.string.logs_empty)))
        runBlocking {
            assertTrue(graph.logs.activity().isEmpty())
            assertTrue(graph.logs.network().isEmpty())
        }
    }
}
```

```powershell
scripts\connected-test.ps1 -Serial <serial> :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.MainActivityTest,dev.kestrel.app.settings.SettingsAppTest,dev.kestrel.app.settings.SetupStatusReaderTest'
```

Expected: `BUILD SUCCESSFUL`, 13 tests. `MainActivityTest` waits with `waitUntil`, because Kestrel's stores work on another thread than the pages.

- [ ] **Step 5: Add the two rules to the spec**

In the spec, `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 12.5, after the line that starts with "- Kestrel matches apps by package name", add this line:

````markdown
- Kestrel's own app is always agent-free and is not on the editable list, so that the agent cannot change Kestrel's settings, such as test mode or this list.
````

In section 13.5, replace the line that starts with "- The logs leave the phone only when" with:

````markdown
- The logs leave the phone only when the owner exports them to a file of their choice. The export is one JSON file with both logs and all their entries, oldest first, with times in UTC.
````

- [ ] **Step 6: Run the checks**

```powershell
scripts\gradle.ps1 check kestrelAudit
```

Expected: `BUILD SUCCESSFUL`, no `w:` line from the new files, and no lint finding.

- [ ] **Step 7: Check the export by hand**

```powershell
. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; $s = '<serial>'
function Get-Texts { & $adb -s $s shell uiautomator dump /sdcard/ui.xml | Out-Null; ([xml](& $adb -s $s exec-out cat /sdcard/ui.xml)).SelectNodes('//node') | Where-Object { $_.text } }
function Invoke-Tap([string]$Text) { $n = Get-Texts | Where-Object { $_.text -eq $Text } | Select-Object -First 1; $b = [regex]::Match($n.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]').Groups; & $adb -s $s shell input tap (([int]$b[1].Value + [int]$b[3].Value) / 2) (([int]$b[2].Value + [int]$b[4].Value) / 2); Start-Sleep -Seconds 2 }
scripts\install.ps1 -Serial $s
& $adb -s $s shell am start -W -n dev.kestrel/dev.kestrel.app.MainActivity
Invoke-Tap 'Activity log'; Invoke-Tap 'Export both logs'; (Get-Texts).text
```

Expected: Android's file picker shows the folder "Downloads" and the file name `kestrel-logs-<today's date>.json` with a `SAVE` button.

```powershell
Invoke-Tap 'SAVE'; Start-Sleep -Seconds 1
& $adb -s $s exec-out screencap -p > $env:TEMP\export.png
& $adb -s $s shell "cat /sdcard/Download/kestrel-logs-*.json"
```

Expected: the screenshot shows the message "Kestrel exported both logs.", and the file contains `"exportedAt"` with a UTC time and the empty lists `"activity": []` and `"network": []`. Restore the emulator:

```powershell
& $adb -s $s shell "rm -f /sdcard/Download/kestrel-logs-*.json /sdcard/ui.xml"
& $adb -s $s uninstall dev.kestrel
Remove-Item $env:TEMP\export.png
```

- [ ] **Step 8: Run all device tests**

```powershell
scripts\connected-test.ps1 -Serial <serial> :core:privacy:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`, 7 tests in `:core:privacy` and 89 in `:app`.

- [ ] **Step 9: Commit**

```powershell
git add core/privacy app docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(app): add the safety page, the log pages and the log export" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 10: The About page and the bundled licence texts

**Files:**
- Create: `licences/Apache-2.0.txt` and `licences/MPL-2.0.txt` (downloaded)
- Modify: `THIRD_PARTY_NOTICES.md`
- Modify: `app/build.gradle.kts` (copies the texts into the app's assets)
- Create: `app/src/main/kotlin/dev/kestrel/app/settings/AboutPage.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt` (`version`, `licenceText`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt` (the About page and its licence pages)
- Modify: `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/test/kotlin/dev/kestrel/app/settings/LicenceParagraphsTest.kt`
- Modify: `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt` and `app/src/androidTest/kotlin/dev/kestrel/app/MainActivityTest.kt`

**Interfaces:**
- Consumes: Task 9's `SettingsData` and pages.
- Produces: `enum class Licence(val asset: String) { KESTREL, NOTICES, APACHE, MPL }`; `internal fun licenceParagraphs(text: String): List<String>`; `SettingsData.version: String` and `suspend fun SettingsData.licenceText(licence: Licence): String`; `StoredSettingsData(graph, context)`; `SettingsPage.ABOUT`. The app's assets contain `licences/LICENSE`, `licences/THIRD_PARTY_NOTICES.md`, `licences/Apache-2.0.txt` and `licences/MPL-2.0.txt` in every build variant.

The About page shows Kestrel's version, the Android version, a note that the status of the model files comes with the voice features, and four licence texts: Kestrel's licence, the third-party notices, and the Apache License 2.0 and the Mozilla Public License 2.0, under which the libraries in the release build are licensed. The Apache License asks redistributors to give recipients a copy of the licence, so the texts ship in the app instead of being only linked. A task per build variant copies them from the repository into generated assets, through the Android Gradle plugin's variant API.

The licence texts wrap their lines at about 70 characters, so the page joins the lines of each paragraph and lets the screen wrap them; lines of tables and lists keep their own lines.

- [ ] **Step 1: Download the licence texts**

```powershell
New-Item -ItemType Directory -Force licences | Out-Null
Invoke-WebRequest -UseBasicParsing https://www.apache.org/licenses/LICENSE-2.0.txt -OutFile licences\Apache-2.0.txt
Invoke-WebRequest -UseBasicParsing https://www.mozilla.org/media/MPL/2.0/index.txt -OutFile licences\MPL-2.0.txt
Get-FileHash licences\*.txt -Algorithm SHA256 | ForEach-Object { "$($_.Hash.ToLowerInvariant()) $(Split-Path $_.Path -Leaf)" }
```

Expected:

```text
cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30 Apache-2.0.txt
3f3d9e0024b1921b067d6f7f88deb4a60cbe7a78e76c64e3f1d7fc3b779b9d04 MPL-2.0.txt
```

If a hash differs, stop and report it.

In `THIRD_PARTY_NOTICES.md`, replace the paragraph that starts with "The text of the Apache License 2.0 is at" with:

````markdown
The texts of the Apache License 2.0 and the Mozilla Public License 2.0 are in the `licences` folder, copied from https://www.apache.org/licenses/LICENSE-2.0.txt and https://www.mozilla.org/media/MPL/2.0/index.txt. Kestrel's About page shows them, together with this file and Kestrel's own licence.
````

- [ ] **Step 2: Bundle the texts**

Replace the whole content of `app/build.gradle.kts` with:

```kotlin
import javax.inject.Inject

plugins {
    id("kestrel.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.kestrel.app"
    defaultConfig {
        applicationId = "dev.kestrel"
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:privacy"))
    implementation(project(":core:safety"))
    implementation(project(":core:screen"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.startup.runtime)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Compose's test library brings Espresso 3.5, which fails on Android 17: it looks for InputManager.getInstance().
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.okhttp.mockwebserver3)
}

/**
 * Copies Kestrel's licence, the third-party notices and the licence texts in `licences` into the app's assets, so that
 * the About page can show them (design spec, section 15).
 */
abstract class LicenceAssets : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val texts: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun copy() {
        files.sync {
            from(texts)
            into(outputDirectory.dir("licences"))
        }
    }
}

androidComponents {
    onVariants { variant ->
        val licences = tasks.register<LicenceAssets>("${variant.name}LicenceAssets") {
            val root = rootProject.layout.projectDirectory
            texts.from(root.file("LICENSE"), root.file("THIRD_PARTY_NOTICES.md"), root.dir("licences").asFileTree)
        }
        variant.sources.assets?.addGeneratedSourceDirectory(licences, LicenceAssets::outputDirectory)
    }
}

// The instrumented tests drive the fixture screens of :testapp, so it is installed first. They also turn on
// Kestrel's accessibility service, so they run after the other modules' device tests instead of alongside them.
tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    dependsOn(":testapp:installDebug")
    mustRunAfter(":core:privacy:connectedDebugAndroidTest")
}
```

```powershell
scripts\gradle.ps1 :app:assembleDebug
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apk = [IO.Compression.ZipFile]::OpenRead((Resolve-Path app\build\outputs\apk\debug\app-debug.apk))
$apk.Entries | Where-Object FullName -like 'assets/licences/*' | ForEach-Object FullName
$apk.Dispose()
```

Expected: `BUILD SUCCESSFUL` and four entries: `assets/licences/Apache-2.0.txt`, `assets/licences/LICENSE`, `assets/licences/MPL-2.0.txt` and `assets/licences/THIRD_PARTY_NOTICES.md`.

- [ ] **Step 3: Write the failing unit test**

Create `app/src/test/kotlin/dev/kestrel/app/settings/LicenceParagraphsTest.kt`:

```kotlin
package dev.kestrel.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class LicenceParagraphsTest {
    @Test
    fun joinsTheWrappedLinesOfAParagraph() {
        val text = "    GNU GENERAL PUBLIC LICENSE\n  Version 3, 29 June 2007\n\n Everyone is permitted to copy\n and distribute it.\n"

        assertEquals(
            listOf("GNU GENERAL PUBLIC LICENSE Version 3, 29 June 2007", "Everyone is permitted to copy and distribute it."),
            licenceParagraphs(text),
        )
    }

    @Test
    fun keepsTheLinesOfTablesAndLists() {
        assertEquals(listOf("| A | B |\n|---|---|", "- one\n- two"), licenceParagraphs("| A | B |\n|---|---|\n\n- one\n- two"))
    }

    @Test
    fun skipsExtraBlankLinesAndWindowsLineEnds() {
        assertEquals(listOf("first", "second"), licenceParagraphs("\r\nfirst\r\n\r\n  \r\n\r\nsecond\r\n"))
    }
}
```

```powershell
scripts\gradle.ps1 :app:testDebugUnitTest
```

Expected: `BUILD FAILED` with an unresolved reference to `licenceParagraphs`.

- [ ] **Step 4: Write the About page**

Create `app/src/main/kotlin/dev/kestrel/app/settings/AboutPage.kt`:

```kotlin
package dev.kestrel.app.settings

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kestrel.app.R

/** The texts that the About page shows. The build copies them from the repository into the app's assets. */
enum class Licence(val asset: String) {
    KESTREL("licences/LICENSE"),
    NOTICES("licences/THIRD_PARTY_NOTICES.md"),
    APACHE("licences/Apache-2.0.txt"),
    MPL("licences/MPL-2.0.txt"),
}

/** The About page: versions, the status of the model files and the licences (design spec, section 15). */
@Composable
internal fun AboutPage(data: SettingsData, onOpen: (Licence) -> Unit, onBack: () -> Unit) {
    PageHeader(stringResource(R.string.settings_about), onBack)
    Text(stringResource(R.string.about_version, data.version), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.about_android, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
    Text(stringResource(R.string.about_models), Modifier.padding(vertical = 8.dp))
    Text(stringResource(R.string.about_licences), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
    for (licence in Licence.entries) {
        val texts = texts(licence)
        PageEntry(stringResource(texts.first), stringResource(texts.second)) { onOpen(licence) }
    }
}

/** One licence text, in paragraphs. */
@Composable
internal fun LicencePage(licence: Licence, data: SettingsData, onBack: () -> Unit) {
    var paragraphs by remember(licence) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(licence) { paragraphs = licenceParagraphs(data.licenceText(licence)) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item { PageHeader(stringResource(texts(licence).first), onBack) }
        val shown = paragraphs
        if (shown == null) {
            item { Text(stringResource(R.string.about_reading)) }
        } else {
            items(shown) { Text(it, Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/**
 * Splits a text into paragraphs at blank lines. The licence texts wrap their lines at about 70 characters, so the
 * lines of a paragraph are joined and the screen wraps them; tables and lists keep their own lines.
 */
internal fun licenceParagraphs(text: String): List<String> =
    text.split(BLANK_LINE).mapNotNull { paragraph ->
        val lines = paragraph.lines().map(String::trim).filter(String::isNotEmpty)
        when {
            lines.isEmpty() -> null
            lines.any { line -> LINE_STARTS.any(line::startsWith) } -> lines.joinToString("\n")
            else -> lines.joinToString(" ")
        }
    }

private val BLANK_LINE = Regex("\\n\\s*\\n")
private val LINE_STARTS = listOf("|", "- ", "* ")

/** The title and summary of a licence on the About page. */
private fun texts(licence: Licence): Pair<Int, Int> = when (licence) {
    Licence.KESTREL -> R.string.about_licence_kestrel to R.string.about_licence_kestrel_summary
    Licence.NOTICES -> R.string.about_licence_notices to R.string.about_licence_notices_summary
    Licence.APACHE -> R.string.about_licence_apache to R.string.about_licence_apache_summary
    Licence.MPL -> R.string.about_licence_mpl to R.string.about_licence_mpl_summary
}
```

Replace the whole content of `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt` with:

```kotlin
package dev.kestrel.app.settings

import android.content.Context
import dev.kestrel.app.AppGraph
import dev.kestrel.app.SettingKeys
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.logs.LogEntry
import dev.kestrel.privacy.logs.NetworkEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** What the settings pages read and change. [StoredSettingsData] connects them to Kestrel's stores; tests use a fake. */
interface SettingsData {
    val testMode: Flow<Boolean>

    suspend fun setTestMode(on: Boolean)

    suspend fun activityLog(): List<LogEntry<ActivityEvent>>

    suspend fun networkLog(): List<LogEntry<NetworkEvent>>

    /** Wipes both logs (design spec, section 13.5). */
    suspend fun deleteHistory()

    /** Kestrel's version name and code, such as "0.1.0 (1)". */
    val version: String

    suspend fun licenceText(licence: Licence): String
}

class StoredSettingsData(private val graph: AppGraph, private val context: Context) : SettingsData {
    override val testMode: Flow<Boolean> = graph.settings.observe(SettingKeys.SAFETY).map { it.testMode }

    override suspend fun setTestMode(on: Boolean) {
        graph.settings.set(SettingKeys.SAFETY, graph.settings.get(SettingKeys.SAFETY).copy(testMode = on))
    }

    override suspend fun activityLog() = graph.logs.activity()

    override suspend fun networkLog() = graph.logs.network()

    override suspend fun deleteHistory() {
        graph.logs.deleteHistory()
    }

    override val version: String by lazy {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }

    override suspend fun licenceText(licence: Licence): String = withContext(Dispatchers.IO) {
        context.assets.open(licence.asset).bufferedReader().use { it.readText() }
    }
}
```

Replace the whole content of `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt` with:

```kotlin
package dev.kestrel.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kestrel.app.R

/** The pages of the settings app (design spec, section 15). Later sub-projects add their own pages. */
enum class SettingsPage { HOME, CHECKLIST, SAFETY, ACTIVITY_LOG, NETWORK_LOG, ABOUT }

/** What the button of a checklist step does. */
enum class SetupAction { ACCESSIBILITY_SETTINGS, PERMISSIONS, APP_SETTINGS }

/**
 * Kestrel's settings app. Every page has a Back button, because a voice user cannot easily use the back gesture.
 * [onExportLogs] asks the owner for a file to export both logs to.
 */
@Composable
fun SettingsApp(status: SetupStatus, data: SettingsData, onAction: (SetupAction) -> Unit, onExportLogs: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    // A licence text opens on top of the About page, and Back returns there.
    var licence by rememberSaveable { mutableStateOf<Licence?>(null) }
    BackHandler(enabled = page != SettingsPage.HOME) { if (licence != null) licence = null else page = SettingsPage.HOME }
    val states = SetupChecklist.states(status)
    val home = { page = SettingsPage.HOME }
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        when (page) {
            SettingsPage.HOME -> ScrollingPage { HomePage(states, data) { page = it } }
            SettingsPage.CHECKLIST -> ScrollingPage { ChecklistPage(states, onAction, home) }
            SettingsPage.SAFETY -> ScrollingPage { SafetyPage(data, home) }
            SettingsPage.ACTIVITY_LOG -> ActivityLogPage(data, onExportLogs, home)
            SettingsPage.NETWORK_LOG -> NetworkLogPage(data, onExportLogs, home)
            SettingsPage.ABOUT -> when (val shown = licence) {
                null -> ScrollingPage { AboutPage(data, { licence = it }, home) }
                else -> LicencePage(shown, data) { licence = null }
            }
        }
    }
}

/** A page that scrolls as a whole. The log pages scroll their lists themselves. */
@Composable
private fun ScrollingPage(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), content = content)
}

@Composable
private fun HomePage(states: Map<SetupStep, StepState>, data: SettingsData, onOpen: (SettingsPage) -> Unit) {
    val done = states.values.count { it == StepState.DONE }
    val testMode by data.testMode.collectAsState(initial = null)
    val logsSummary = stringResource(R.string.settings_logs_summary)
    Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
    PageEntry(
        stringResource(R.string.settings_checklist),
        pluralStringResource(R.plurals.settings_checklist_progress, states.size, done, states.size),
    ) { onOpen(SettingsPage.CHECKLIST) }
    PageEntry(stringResource(R.string.settings_safety), testModeSummary(testMode)) { onOpen(SettingsPage.SAFETY) }
    PageEntry(stringResource(R.string.settings_activity_log), logsSummary) { onOpen(SettingsPage.ACTIVITY_LOG) }
    PageEntry(stringResource(R.string.settings_network_log), logsSummary) { onOpen(SettingsPage.NETWORK_LOG) }
    PageEntry(stringResource(R.string.settings_about), stringResource(R.string.settings_about_summary, data.version)) {
        onOpen(SettingsPage.ABOUT)
    }
}

@Composable
private fun testModeSummary(on: Boolean?): String = when (on) {
    true -> stringResource(R.string.settings_test_mode_on)
    false -> stringResource(R.string.settings_test_mode_off)
    null -> ""
}

@Composable
internal fun PageEntry(title: String, summary: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(summary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The Back button and the title at the top of every page except the home page. */
@Composable
internal fun PageHeader(title: String, onBack: () -> Unit) {
    Column {
        TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
        Text(title, style = MaterialTheme.typography.headlineSmall)
    }
}
```

Replace the whole content of `app/src/main/res/values/strings.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="accessibility_service_description">Kestrel reads the screen and acts on it when you ask, so that you can use the phone by voice. What it reads stays on the phone, except what you choose to send to your own AI provider.</string>

    <string name="settings_title">Kestrel</string>
    <string name="settings_checklist">Setup checklist</string>
    <!-- The quantity is the number of steps in the checklist. -->
    <plurals name="settings_checklist_progress">
        <item quantity="one">%1$d of %2$d step done</item>
        <item quantity="other">%1$d of %2$d steps done</item>
    </plurals>
    <string name="settings_back">Back</string>
    <string name="settings_safety">Safety</string>
    <string name="settings_test_mode_on">Test mode is on</string>
    <string name="settings_test_mode_off">Test mode is off</string>
    <string name="settings_activity_log">Activity log</string>
    <string name="settings_network_log">Network log</string>
    <string name="settings_logs_summary">Encrypted, and kept for 7 days</string>
    <string name="settings_about">About</string>
    <string name="settings_about_summary">Version %1$s, and the licences</string>

    <string name="about_version">Kestrel %1$s</string>
    <string name="about_android">Android %1$s (API %2$d)</string>
    <string name="about_models">The status of the voice model files comes with Kestrel\'s voice features.</string>
    <string name="about_licences">Licences</string>
    <string name="about_licence_kestrel">Kestrel\'s licence</string>
    <string name="about_licence_kestrel_summary">GNU General Public License, version 3 or later</string>
    <string name="about_licence_notices">Third-party notices</string>
    <string name="about_licence_notices_summary">The libraries in Kestrel and their licences</string>
    <string name="about_licence_apache">Apache License 2.0</string>
    <string name="about_licence_apache_summary">The licence of most of these libraries</string>
    <string name="about_licence_mpl">Mozilla Public License 2.0</string>
    <string name="about_licence_mpl_summary">The licence of the Public Suffix List in OkHttp</string>
    <string name="about_reading">Reading the text…</string>

    <string name="safety_test_mode">Test mode</string>
    <string name="safety_test_mode_detail">While test mode is on, Kestrel sends and shares only to Telegram\'s Saved Messages, WhatsApp\'s Message yourself, Viber\'s My Notes and your own email address, and it makes no calls. Keep it on while you test Kestrel.</string>
    <string name="safety_later">The safety mode, agent-free apps and screenshot approvals come with Kestrel\'s agent.</string>

    <string name="logs_export">Export both logs</string>
    <string name="logs_delete">Delete history</string>
    <string name="logs_delete_question">Delete the activity log and the network log? This cannot be undone.</string>
    <string name="logs_delete_confirm">Delete</string>
    <string name="logs_delete_cancel">Cancel</string>
    <string name="logs_loading">Reading the log…</string>
    <string name="logs_empty">No entries in the last 7 days.</string>
    <string name="logs_exported">Kestrel exported both logs.</string>
    <string name="logs_export_failed">Kestrel could not write the file.</string>
    <string name="log_kind_action">Action</string>
    <string name="log_kind_note">Note</string>
    <string name="log_kind_error">Error</string>
    <string name="log_outcome_completed">Completed</string>
    <string name="log_outcome_failed">Failed</string>
    <string name="log_outcome_blocked">Blocked</string>
    <string name="log_task">Task %1$s</string>
    <string name="log_network_sizes">Sent %1$s, received %2$s</string>
    <string name="log_network_status">Status %1$d</string>

    <string name="setup_state_done">Done</string>
    <string name="setup_state_to_do">To do</string>
    <string name="setup_state_later">Comes later</string>
    <string name="setup_state_optional">Optional</string>
    <string name="setup_state_warning">Needs attention</string>

    <string name="setup_install_title">Install Kestrel</string>
    <string name="setup_install_detail">Kestrel is installed. scripts\\install.ps1 installs it with adb. On a Samsung phone, Auto Blocker must be off while USB debugging is on.</string>
    <string name="setup_models_title">Copy the voice models</string>
    <string name="setup_models_detail">scripts\\push-models.ps1 will copy the models from your computer. This step comes with Kestrel\'s voice features.</string>
    <string name="setup_service_title">Turn on Kestrel\'s accessibility service</string>
    <string name="setup_service_detail">Kestrel reads the screen and acts on it through this service.</string>
    <string name="setup_permissions_title">Allow the microphone and notifications</string>
    <string name="setup_permissions_detail">Kestrel listens for your voice and shows what it is doing in a notification.</string>
    <string name="setup_battery_title">Allow unrestricted battery use</string>
    <string name="setup_battery_detail">So that Android does not stop Kestrel. On a Samsung phone, also add Kestrel to \"Never sleeping apps\" in the battery settings.</string>
    <string name="setup_assistant_title">Make Kestrel the default digital assistant</string>
    <string name="setup_assistant_detail">Optional: for the side key and earbuds. This step comes with Kestrel\'s voice features.</string>
    <string name="setup_provider_title">Sign in to an AI provider and choose the models</string>
    <string name="setup_provider_detail">This step comes with Kestrel\'s provider settings.</string>
    <string name="setup_voice_access_title">Turn off Voice Access</string>
    <string name="setup_voice_access_detail">Optional: so that Voice Access and Kestrel do not react to the same words.</string>
    <string name="setup_voice_access_warning">Voice Access and Kestrel are both on, so they may react to the same words.</string>

    <string name="setup_open_accessibility">Open accessibility settings</string>
    <string name="setup_allow">Allow</string>
    <string name="setup_open_app_settings">Open app settings</string>
</resources>
```

Replace the whole content of `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt` with:

```kotlin
package dev.kestrel.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.kestrel.app.settings.SettingsApp
import dev.kestrel.app.settings.SetupAction
import dev.kestrel.app.settings.SetupStatus
import dev.kestrel.app.settings.SetupStatusReader
import dev.kestrel.app.settings.StoredSettingsData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate

/** Kestrel's settings app (design spec, section 15). */
class MainActivity : ComponentActivity() {
    private val graph by lazy { (application as KestrelApplication).graph }
    private val data by lazy { StoredSettingsData(graph, applicationContext) }
    private var status by mutableStateOf<SetupStatus?>(null)

    private val exportLogs = registerForActivityResult(CreateDocument("application/json")) { uri -> uri?.let(::export) }

    /** The permissions that were missing when the request started, and for which Android would show no explanation. */
    private var missingWithoutRationale = emptySet<String>()

    // After two refusals Android no longer shows its dialog. If a missing permission had no explanation both before and
    // after the request, Android showed no dialog for it, and the owner allows it in the app's settings instead.
    private val permissions = registerForActivityResult(RequestMultiplePermissions()) { results ->
        refresh()
        val noDialog = results.any { (permission, granted) ->
            !granted && permission in missingWithoutRationale && !shouldShowRequestPermissionRationale(permission)
        }
        if (noDialog) openAppSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                status?.let { SettingsApp(it, data, ::perform) { exportLogs.launch("kestrel-logs-${LocalDate.now()}.json") } }
            }
        }
    }

    // The owner may have changed a setting in another app, such as Android's accessibility settings.
    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        status = SetupStatusReader(this).read()
    }

    private fun perform(action: SetupAction) {
        when (action) {
            SetupAction.ACCESSIBILITY_SETTINGS -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            SetupAction.PERMISSIONS -> requestPermissions()
            SetupAction.APP_SETTINGS -> openAppSettings()
        }
    }

    private fun requestPermissions() {
        val wanted = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        missingWithoutRationale = wanted
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(it) }
            .toSet()
        permissions.launch(wanted)
    }

    /** Writes both logs to the file that the owner chose. The logs leave the phone only this way (design spec, 13.5). */
    private fun export(uri: Uri) {
        lifecycleScope.launch(graph.errors) {
            val written = withContext(Dispatchers.IO) {
                try {
                    val output = contentResolver.openOutputStream(uri, "w") ?: return@withContext false
                    output.use { graph.logs.export(it) }
                    true
                } catch (e: IOException) {
                    false
                } catch (e: SecurityException) {
                    false
                }
            }
            val message = if (written) R.string.logs_exported else R.string.logs_export_failed
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }
}
```

Run the command of Step 3 again. Expected: `BUILD SUCCESSFUL`; `LicenceParagraphsTest` runs 3 tests and `SetupChecklistTest` 4.

- [ ] **Step 5: Write the device tests and run them**

Replace the whole content of `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt` with:

```kotlin
package dev.kestrel.app.settings

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.kestrel.app.R
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.logs.ActivityKind
import dev.kestrel.privacy.logs.LogEntry
import dev.kestrel.privacy.logs.NetworkEvent
import dev.kestrel.privacy.logs.NetworkOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class SettingsAppTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<SetupAction>()
    private val data = FakeSettingsData()
    private var exports = 0

    private fun show(status: SetupStatus) {
        compose.setContent { MaterialTheme { SettingsApp(status, data, { actions += it }) { exports++ } } }
    }

    private fun text(@StringRes id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun progress(done: Int): String =
        compose.activity.resources.getQuantityString(R.plurals.settings_checklist_progress, 8, done, 8)

    private fun openChecklist() {
        compose.onNodeWithText(text(R.string.settings_checklist)).performClick()
        compose.onNodeWithText(text(R.string.setup_service_title)).assertExists()
    }

    @Test
    fun theHomePageCountsTheStepsDone() {
        show(NOTHING_SET_UP.copy(serviceOn = true))

        // Installing, the service, and Voice Access being off.
        compose.onNodeWithText(progress(3)).assertIsDisplayed()
    }

    @Test
    fun stepsToDoOfferTheirActions() {
        show(NOTHING_SET_UP)
        openChecklist()

        compose.onNodeWithText(text(R.string.setup_allow)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.setup_open_app_settings)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.setup_open_accessibility)).performScrollTo().performClick()

        assertEquals(
            listOf(SetupAction.PERMISSIONS, SetupAction.APP_SETTINGS, SetupAction.ACCESSIBILITY_SETTINGS),
            actions,
        )
    }

    @Test
    fun doneStepsOfferNoAction() {
        show(EVERYTHING_SET_UP)
        openChecklist()

        val labels = listOf(R.string.setup_allow, R.string.setup_open_app_settings, R.string.setup_open_accessibility)
        for (label in labels) {
            compose.onAllNodesWithText(text(label)).assertCountEquals(0)
        }
    }

    @Test
    fun voiceAccessBesideKestrelShowsTheWarning() {
        show(EVERYTHING_SET_UP.copy(voiceAccessOn = true))
        openChecklist()

        compose.onNodeWithText(text(R.string.setup_voice_access_warning)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setup_open_accessibility)).performScrollTo().performClick()
        assertEquals(listOf(SetupAction.ACCESSIBILITY_SETTINGS), actions)
    }

    @Test
    fun theBackButtonAndAndroidsBackReturnHome() {
        show(NOTHING_SET_UP)
        val progress = progress(2)

        openChecklist()
        compose.onNodeWithText(text(R.string.settings_back)).performClick()
        compose.onNodeWithText(progress).assertIsDisplayed()

        openChecklist()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(progress).assertIsDisplayed()
    }

    @Test
    fun theSafetyPageSwitchesTestMode() {
        show(NOTHING_SET_UP)
        compose.onNodeWithText(text(R.string.settings_test_mode_on)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_safety)).performClick()

        compose.onNodeWithText(text(R.string.safety_test_mode)).assertIsOn().performClick()

        compose.onNodeWithText(text(R.string.safety_test_mode)).assertIsOff()
        assertFalse(data.testMode.value)
    }

    @Test
    fun theLogPagesShowTheirEntries() {
        data.activity = listOf(
            LogEntry(2, 2_000, ActivityEvent(ActivityKind.ACTION, "Tapped Send", taskId = "task-1")),
            LogEntry(1, 1_000, ActivityEvent(ActivityKind.NOTE, "Started")),
        )
        data.network = listOf(
            LogEntry(3, 3_000, NetworkEvent("POST", "api.example.com", "/v1/chat", 2_048, 512, 200, NetworkOutcome.COMPLETED)),
        )
        show(NOTHING_SET_UP)

        compose.onNodeWithText(text(R.string.settings_activity_log)).performClick()
        compose.onNodeWithText("Tapped Send").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.log_task, "task-1")).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.log_kind_note)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.settings_back)).performClick()

        compose.onNodeWithText(text(R.string.settings_network_log)).performClick()
        compose.onNodeWithText("POST api.example.com/v1/chat").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.log_network_status, 200)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.log_outcome_completed)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.logs_export)).performClick()
        assertEquals(1, exports)
    }

    @Test
    fun deleteHistoryAsksFirst() {
        data.activity = listOf(LogEntry(1, 1_000, ActivityEvent(ActivityKind.NOTE, "Started")))
        show(NOTHING_SET_UP)
        compose.onNodeWithText(text(R.string.settings_activity_log)).performClick()

        compose.onNodeWithText(text(R.string.logs_delete)).performClick()
        compose.onNodeWithText(text(R.string.logs_delete_cancel)).performClick()
        compose.onNodeWithText("Started").assertIsDisplayed()
        assertEquals(0, data.deletions)

        compose.onNodeWithText(text(R.string.logs_delete)).performClick()
        compose.onNodeWithText(text(R.string.logs_delete_question)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.logs_delete_confirm)).performClick()

        compose.onNodeWithText(text(R.string.logs_empty)).assertIsDisplayed()
        assertEquals(1, data.deletions)
    }

    @Test
    fun theAboutPageShowsTheVersionAndOpensALicence() {
        show(NOTHING_SET_UP)
        compose.onNodeWithText(text(R.string.settings_about_summary, "9.9.9 (99)")).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.about_version, "9.9.9 (99)")).assertIsDisplayed()

        compose.onNodeWithText(text(R.string.about_licence_apache)).performScrollTo().performClick()

        compose.onNodeWithText("The first paragraph, wrapped.").assertIsDisplayed()
        assertEquals(listOf(Licence.APACHE), data.opened)
        compose.onNodeWithText(text(R.string.settings_back)).performClick()
        compose.onNodeWithText(text(R.string.about_version, "9.9.9 (99)")).assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText(progress(2)).assertIsDisplayed()
    }

    private class FakeSettingsData : SettingsData {
        override val testMode = MutableStateFlow(true)
        override val version = "9.9.9 (99)"
        var activity = emptyList<LogEntry<ActivityEvent>>()
        var network = emptyList<LogEntry<NetworkEvent>>()
        var deletions = 0
        val opened = mutableListOf<Licence>()

        override suspend fun licenceText(licence: Licence): String {
            opened += licence
            return "The first paragraph,\nwrapped.\n\nThe second paragraph."
        }

        override suspend fun setTestMode(on: Boolean) {
            testMode.value = on
        }

        override suspend fun activityLog() = activity

        override suspend fun networkLog() = network

        override suspend fun deleteHistory() {
            deletions++
            activity = emptyList()
            network = emptyList()
        }
    }

    private companion object {
        val NOTHING_SET_UP = SetupStatus(
            serviceOn = false,
            microphoneAllowed = false,
            notificationsAllowed = false,
            batteryUnrestricted = false,
            voiceAccessOn = false,
        )
        val EVERYTHING_SET_UP = SetupStatus(
            serviceOn = true,
            microphoneAllowed = true,
            notificationsAllowed = true,
            batteryUnrestricted = true,
            voiceAccessOn = false,
        )
    }
}
```

Replace the whole content of `app/src/androidTest/kotlin/dev/kestrel/app/MainActivityTest.kt` with:

```kotlin
package dev.kestrel.app

import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.SemanticsMatcher
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.logs.ActivityKind
import dev.kestrel.privacy.logs.NetworkEvent
import dev.kestrel.privacy.logs.NetworkOutcome
import dev.kestrel.safety.SafetySettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Checks that the settings pages change Kestrel's real stores, which work on another thread than the pages. */
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val graph get() = (compose.activity.application as KestrelApplication).graph

    private fun text(@StringRes id: Int): String = compose.activity.getString(id)

    private fun await(matcher: SemanticsMatcher) {
        compose.waitUntil(5_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    @After
    fun turnTestModeBackOn() = runBlocking {
        graph.settings.set(SettingKeys.SAFETY, SafetySettings(testMode = true))
    }

    @Test
    fun theSafetySwitchTurnsTestModeOff() {
        runBlocking { graph.settings.set(SettingKeys.SAFETY, SafetySettings(testMode = true)) }
        compose.onNodeWithText(text(R.string.settings_safety)).performClick()
        await(hasText(text(R.string.safety_test_mode)) and isOn())

        compose.onNodeWithText(text(R.string.safety_test_mode)).performClick()

        compose.waitUntil(5_000) { runBlocking { !graph.settings.get(SettingKeys.SAFETY).testMode } }
        compose.onNodeWithText(text(R.string.safety_test_mode)).assertIsOff()
    }

    @Test
    fun theAboutPageShowsTheTextsThatTheBuildBundles() {
        compose.onNodeWithText(text(R.string.settings_about)).performScrollTo().performClick()
        val expected = mapOf(
            R.string.about_licence_kestrel to "GNU GENERAL PUBLIC LICENSE",
            R.string.about_licence_notices to "Kestrel's release build includes the libraries below",
            R.string.about_licence_apache to "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION",
            R.string.about_licence_mpl to "Mozilla Public License Version 2.0",
        )
        for ((title, start) in expected) {
            compose.onNodeWithText(text(title)).performScrollTo().performClick()
            await(hasText(start, substring = true))
            compose.onNodeWithText(text(R.string.settings_back)).performClick()
        }
    }

    @Test
    fun deleteHistoryWipesBothLogs() {
        runBlocking {
            graph.logs.append(ActivityEvent(ActivityKind.NOTE, "A note from MainActivityTest"))
            graph.logs.append(NetworkEvent("GET", "example.com", "/", 0, 0, outcome = NetworkOutcome.BLOCKED))
        }
        compose.onNodeWithText(text(R.string.settings_activity_log)).performClick()
        await(hasText("A note from MainActivityTest"))

        compose.onNodeWithText(text(R.string.logs_delete)).performClick()
        compose.onNodeWithText(text(R.string.logs_delete_confirm)).performClick()

        await(hasText(text(R.string.logs_empty)))
        runBlocking {
            assertTrue(graph.logs.activity().isEmpty())
            assertTrue(graph.logs.network().isEmpty())
        }
    }
}
```

```powershell
scripts\connected-test.ps1 -Serial <serial> :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.MainActivityTest,dev.kestrel.app.settings.SettingsAppTest,dev.kestrel.app.settings.SetupStatusReaderTest'
```

Expected: `BUILD SUCCESSFUL`, 15 tests. `theAboutPageShowsTheTextsThatTheBuildBundles` opens each of the four texts in the installed app.

- [ ] **Step 6: Run the checks and all device tests**

```powershell
scripts\gradle.ps1 check kestrelAudit
scripts\connected-test.ps1 -Serial <serial> :core:privacy:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL` twice, no lint finding; 7 device tests in `:core:privacy` and 91 in `:app`.

- [ ] **Step 7: Commit**

```powershell
git add licences THIRD_PARTY_NOTICES.md app
git commit -m "feat(app): add the About page with the versions and the bundled licence texts" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 11: Spike S1, the listening service's start paths and the location

**Files:**
- Create: `app/src/debug/kotlin/dev/kestrel/app/debug/spike/SpikeListening.kt` (starts the stand-in service; keeps the results)
- Create: `app/src/debug/kotlin/dev/kestrel/app/debug/spike/SpikeListeningService.kt` (the stand-in for `ListeningService`)
- Create: `app/src/debug/kotlin/dev/kestrel/app/debug/spike/SpikeTileService.kt` (a Quick Settings tile)
- Create: `app/src/debug/kotlin/dev/kestrel/app/debug/spike/SpikeStartup.kt` (a recording after the next start of the process)
- Create: `app/src/debug/kotlin/dev/kestrel/app/debug/spike/SpikeHooks.kt` (the spike's test hooks)
- Modify: `app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt` and `TestHookReceiver.kt` (hand the spike's commands on)
- Modify: `app/src/debug/AndroidManifest.xml` (the spike's permissions and components)
- Modify: `scripts/hook.ps1` (five commands)
- Create: `scripts/spike-s1.ps1`
- Modify: `README.md` and `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 19.1)

**Interfaces:**
- Consumes: the test hooks; `AppGraph.screen` and `AppGraph.scope`.
- Produces: `TestHooks(graph: AppGraph, context: Context)`; the debug hooks `LISTEN [--es source <name>] [--ei delay <s>] [--ei seconds <s>]`, `LISTEN_NOTIFICATION`, `LISTEN_AFTER_BOOT`, `LOCATION [--es source <name>] [--ei delay <s>]` and `SPIKE_RESULTS [--ez clear true]`, with the `hook.ps1` commands `listen`, `listen-notification`, `listen-after-boot`, `location` and `spike-results`. Sub-project 2 replaces the spike's service and tile with the real `ListeningService` and `KestrelTileService`.

Spike S1 asks whether `ListeningService` can start and record when the accessibility service, the assistant session, the notification or the tile starts it, also after a reboot and while a call rings on the lock screen, and whether the accessibility service can use the location while another app is in front (spec section 19). The assistant session needs the assistant role, so it is checked with S2 in sub-project 2. Android may give a background app silence instead of an error, so the stand-in records for a few seconds and reports, once a second, whether Android silenced it (`AudioRecordingConfiguration.isClientSilenced()`). The results say only whether each step worked, never what was recorded or where the device is.

The hook starts the recording 15 s after its own broadcast, so that the broadcast cannot be what allowed the start. The script tests each start path with and without Kestrel's accessibility service, and a start with neither the service nor a tap shows that Android refuses it. Debug builds only contain this code; Step 3 checks that the release build has none of it.

- [ ] **Step 1: Write the stand-in and the spike's hooks**

Create `app/src/debug/kotlin/dev/kestrel/app/debug/spike/SpikeListening.kt`:

```kotlin
package dev.kestrel.app.debug.spike

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import java.io.File
import java.time.Instant

/**
 * Spike S1 (design spec, section 19), in debug builds only: can Kestrel start a microphone foreground service and record
 * when the accessibility service, a notification, the tile or a restart starts it, and can it read the location while
 * another app is in front? `scripts/spike-s1.ps1` runs the checks. The results go to a file that
 * `scripts/hook.ps1 spike-results` reads, and they say only whether each step worked, never what was recorded or where
 * the device is. Sub-project 2 replaces this package with the real ListeningService and tile.
 */
internal object SpikeListening {
    fun start(context: Context, source: String, seconds: Int) {
        try {
            context.startForegroundService(SpikeListeningService.intent(context, source, seconds))
        } catch (e: ForegroundServiceStartNotAllowedException) {
            record(context, "listen source=$source start=not-allowed")
        } catch (e: SecurityException) {
            record(context, "listen source=$source start=SecurityException")
        }
    }

    fun record(context: Context, line: String) = synchronized(this) {
        resultsFile(context).appendText("${Instant.now()} $line\n")
    }

    fun results(context: Context): String = synchronized(this) {
        resultsFile(context).takeIf(File::exists)?.readText().orEmpty()
    }

    fun clearResults(context: Context) = synchronized(this) { resultsFile(context).delete() }

    /** Asks for a recording when Kestrel's process starts next, which a reboot causes. */
    fun requestListenAtNextStart(context: Context) = nextStartFlag(context).writeText("")

    fun takeListenAtNextStart(context: Context): Boolean = nextStartFlag(context).delete()

    private fun resultsFile(context: Context) = File(context.noBackupFilesDir, "spike-results.txt")

    private fun nextStartFlag(context: Context) = File(context.noBackupFilesDir, "spike-listen-at-next-start")
}
```

Create `app/src/debug/kotlin/dev/kestrel/app/debug/spike/SpikeListeningService.kt`:

```kotlin
package dev.kestrel.app.debug.spike

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * A stand-in for ListeningService (design spec, section 6) for spike S1: a foreground service of the types microphone
 * and media playback that records for a few seconds and reports what Android allowed. A background app can get
 * silence instead of an error, so it also reports, once a second, whether Android silenced the recording.
 */
class SpikeListeningService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val source = intent?.getStringExtra(EXTRA_SOURCE) ?: "unknown"
        val seconds = intent?.getIntExtra(EXTRA_SECONDS, DEFAULT_SECONDS) ?: DEFAULT_SECONDS
        try {
            startForeground(NOTIFICATION_ID, notification(), FOREGROUND_TYPES)
        } catch (e: RuntimeException) {
            // From Android 14, an app that may not use the microphone in the background gets a SecurityException here.
            SpikeListening.record(this, "listen source=$source startForeground=${e.javaClass.simpleName}")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        thread(name = "SpikeRecording") {
            SpikeListening.record(this, "listen source=$source ${record(seconds)}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    // The spike script grants RECORD_AUDIO before it starts the service.
    @SuppressLint("MissingPermission")
    private fun record(seconds: Int): String {
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum, RATE),
            )
        } catch (e: RuntimeException) {
            return "recorder=${e.javaClass.simpleName}"
        }
        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) return "recorder=uninitialized"
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) return "recording=false"
            val buffer = ShortArray(RATE / 10)
            var frames = 0L
            var loudest = 0
            val silenced = StringBuilder()
            repeat(seconds * 10) { tenth ->
                val read = recorder.read(buffer, 0, buffer.size)
                for (i in 0 until read) loudest = maxOf(loudest, abs(buffer[i].toInt()))
                if (read > 0) frames += read
                // One letter a second: y if Android silenced the recording, n if not, ? if it did not say.
                if (tenth % 10 == 9) silenced.append(silencedLetter(recorder))
            }
            return "recording=true frames=$frames loudest=$loudest silenced=$silenced"
        } catch (e: RuntimeException) {
            return "recording=${e.javaClass.simpleName}"
        } finally {
            recorder.release()
        }
    }

    private fun silencedLetter(recorder: AudioRecord): Char = when (recorder.activeRecordingConfiguration?.isClientSilenced) {
        true -> 'y'
        false -> 'n'
        null -> '?'
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Spike recording", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Kestrel spike is recording for a few seconds")
            .build()
    }

    companion object {
        private const val RATE = 16_000
        private const val DEFAULT_SECONDS = 3
        private const val NOTIFICATION_ID = 7_001
        private const val CHANNEL = "spike-recording"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_SECONDS = "seconds"
        private const val FOREGROUND_TYPES =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

        fun intent(context: Context, source: String, seconds: Int): Intent =
            Intent(context, SpikeListeningService::class.java)
                .putExtra(EXTRA_SOURCE, source)
                .putExtra(EXTRA_SECONDS, seconds)
    }
}
```

Create `app/src/debug/kotlin/dev/kestrel/app/debug/spike/SpikeTileService.kt`:

```kotlin
package dev.kestrel.app.debug.spike

import android.service.quicksettings.TileService

/** A Quick Settings tile for spike S1 that starts a short recording when it is tapped. */
class SpikeTileService : TileService() {
    override fun onClick() {
        SpikeListening.start(applicationContext, source = "tile", seconds = 3)
    }
}
```

Create `app/src/debug/kotlin/dev/kestrel/app/debug/spike/SpikeStartup.kt`:

```kotlin
package dev.kestrel.app.debug.spike

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.startup.Initializer
import dev.kestrel.app.KestrelApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs when Kestrel's process starts. If the hook asked for it, Kestrel starts a recording 15 s after its accessibility
 * service connects, as it would after a reboot (spike S1).
 */
class SpikeStartup : Initializer<Unit> {
    override fun create(context: Context) {
        val app = context.applicationContext
        if (!SpikeListening.takeListenAtNextStart(app)) return
        // The app's graph exists only after Application.onCreate(), which Android calls after the initializers.
        Handler(Looper.getMainLooper()).post {
            val graph = (app as KestrelApplication).graph
            graph.scope.launch {
                val session = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) { graph.screen.first { it != null } }
                if (session == null) {
                    SpikeListening.record(app, "listen source=boot start=service-not-connected")
                } else {
                    delay(START_DELAY_MILLIS)
                    SpikeListening.start(app, source = "boot", seconds = 3)
                }
            }
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 180_000L
        const val START_DELAY_MILLIS = 15_000L
    }
}
```

Create `app/src/debug/kotlin/dev/kestrel/app/debug/spike/SpikeHooks.kt`:

```kotlin
package dev.kestrel.app.debug.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import dev.kestrel.app.AppGraph
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** The test-hook commands of spike S1. `scripts/hook.ps1` lists them. */
internal class SpikeHooks(private val context: Context, private val graph: AppGraph) {
    fun run(command: String, intent: Intent): String = when (command) {
        "LISTEN" -> listen(intent)
        "LISTEN_NOTIFICATION" -> listenNotification()
        "LISTEN_AFTER_BOOT" -> {
            SpikeListening.requestListenAtNextStart(context)
            "Kestrel records 15 s after its accessibility service connects at the next start of its process"
        }
        "LOCATION" -> location(intent)
        else -> results(intent)
    }

    /** Starts a recording after a delay, so that the hook's own broadcast is long over when it starts. */
    private fun listen(intent: Intent): String {
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: "hook"
        val delaySeconds = intent.getIntExtra(EXTRA_DELAY, DEFAULT_DELAY_SECONDS)
        val seconds = intent.getIntExtra(EXTRA_SECONDS, DEFAULT_SECONDS)
        graph.scope.launch {
            delay(delaySeconds * 1_000L)
            SpikeListening.start(context, source, seconds)
        }
        return "recording in $delaySeconds s for $seconds s"
    }

    private fun listenNotification(): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Spike", NotificationManager.IMPORTANCE_HIGH))
        val start = PendingIntent.getForegroundService(
            context,
            0,
            SpikeListeningService.intent(context, "notification", DEFAULT_SECONDS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Kestrel spike")
            .addAction(Notification.Action.Builder(null, ACTION_LABEL, start).build())
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        return "posted a notification with the action '$ACTION_LABEL'"
    }

    private fun location(intent: Intent): String {
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: "hook"
        val delaySeconds = intent.getIntExtra(EXTRA_DELAY, 0)
        graph.scope.launch {
            delay(delaySeconds * 1_000L)
            SpikeListening.record(context, "location source=$source ${locate()}")
        }
        return "locating in $delaySeconds s"
    }

    /** Says only whether a location came, never where the device is. */
    private suspend fun locate(): String {
        val manager = context.getSystemService(LocationManager::class.java)
        if (!manager.isLocationEnabled) return "result=location-off"
        val provider = if (manager.hasProvider(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER
        return try {
            val location = withTimeoutOrNull(LOCATION_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { continuation.resume(it) }
                }
            }
            "provider=$provider result=${if (location == null) "none" else "found"}"
        } catch (e: SecurityException) {
            "provider=$provider result=SecurityException"
        }
    }

    private fun results(intent: Intent): String {
        if (intent.getBooleanExtra(EXTRA_CLEAR, false)) {
            SpikeListening.clearResults(context)
            return "cleared"
        }
        return SpikeListening.results(context).ifEmpty { "none yet" }
    }

    companion object {
        val COMMANDS = setOf("LISTEN", "LISTEN_NOTIFICATION", "LISTEN_AFTER_BOOT", "LOCATION", "SPIKE_RESULTS")
        private const val CHANNEL = "spike"
        private const val NOTIFICATION_ID = 7_002
        private const val ACTION_LABEL = "Start listening"
        private const val DEFAULT_DELAY_SECONDS = 15
        private const val DEFAULT_SECONDS = 3
        private const val LOCATION_TIMEOUT_MILLIS = 15_000L
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_DELAY = "delay"
        private const val EXTRA_SECONDS = "seconds"
        private const val EXTRA_CLEAR = "clear"
    }
}
```

- [ ] **Step 2: Hand the commands on, and declare the components**

Replace the whole content of `app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt` with:

```kotlin
package dev.kestrel.app.debug

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import dev.kestrel.app.AppGraph
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.debug.spike.SpikeHooks
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.GlobalAction
import dev.kestrel.screen.act.ScrollDirection
import dev.kestrel.screen.model.ScreenJson
import dev.kestrel.screen.overlay.NumbersMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A test hook's answer, which becomes the broadcast's result code and data. */
internal data class HookReply(val code: Int, val text: String) {
    companion object {
        /** The result code of a command that failed. A command that never ran keeps the initial code, 0. */
        const val FAILED = 1

        fun ok(text: String) = HookReply(Activity.RESULT_OK, text)

        fun failed(message: String) = HookReply(FAILED, "error: $message")
    }
}

/**
 * Runs the test-hook commands (design spec, section 16.1). The intent's action is [ACTION_PREFIX] followed by the
 * command, and its extras are the arguments; `scripts/hook.ps1` lists them.
 */
internal class TestHooks(private val graph: AppGraph, private val context: Context) {
    private val cacheDir: File get() = context.cacheDir

    suspend fun run(intent: Intent): HookReply = try {
        HookReply.ok(answer(intent))
    } catch (error: HookError) {
        HookReply.failed(error.message)
    }

    private suspend fun answer(intent: Intent): String {
        val command = intent.action.orEmpty().removePrefix(ACTION_PREFIX)
        if (command == "STATE") return state()
        if (command == "TEST_MODE") return testMode(intent)
        if (command == "CRASH") return crash(intent.requiredText())
        if (command in SpikeHooks.COMMANDS) return SpikeHooks(context, graph).run(command, intent)
        val session = graph.screen.value ?: throw HookError("Kestrel's accessibility service is off")
        val actions = session.actions
        return when (command) {
            "SNAPSHOT" -> save(ScreenJson.encode(session.snapshots.current().snapshot))
            "TAP" -> done(tap(session, intent))
            "TYPE" -> done(actions.setText(target(session, intent), intent.requiredText()))
            "SCROLL" -> done(actions.scroll(intent.choice<ScrollDirection>(EXTRA_DIRECTION), optionalTarget(session, intent)))
            "GLOBAL" -> done(actions.global(intent.choice<GlobalAction>(EXTRA_ACTION)))
            "OVERLAY" -> {
                overlay(session, intent)
                state()
            }
            else -> throw HookError("unknown command '$command'")
        }
    }

    /**
     * Writes a snapshot to Kestrel's cache and answers with the file's path in Kestrel's data folder, because a
     * broadcast's answer must stay under Android's limit of about 1 MB. `scripts/hook.ps1` reads the file with
     * `run-as` and deletes it.
     */
    private suspend fun save(json: String): String = withContext(Dispatchers.IO) {
        File(cacheDir, SNAPSHOT_FILE).writeText(json)
        "${cacheDir.name}/$SNAPSHOT_FILE"
    }

    /**
     * Ends Kestrel's process with an error whose message is [text], thrown on the main thread a moment after the answer,
     * so that a check can look for [text] in Android's crash records (design spec, section 13.5).
     */
    private fun crash(text: String): String {
        Handler(Looper.getMainLooper()).postDelayed({ throw IllegalStateException(text) }, CRASH_DELAY_MILLIS)
        return "crashing"
    }

    private suspend fun state(): String {
        val testMode = onOff(graph.settings.get(SettingKeys.SAFETY).testMode)
        val session = graph.screen.value ?: return "screen=off testMode=$testMode"
        return withContext(Dispatchers.Main) {
            "screen=on testMode=$testMode numbers=${session.numberBadges.size} labels=${onOff(session.labelsShowing)} " +
                "grid=${onOff(session.grid != null)} feedback=${onOff(session.feedbackText != null)}"
        }
    }

    private suspend fun testMode(intent: Intent): String {
        if (!intent.hasExtra(EXTRA_ON)) throw HookError("add --ez on true or --ez on false")
        val on = intent.getBooleanExtra(EXTRA_ON, true)
        graph.settings.set(SettingKeys.SAFETY, graph.settings.get(SettingKeys.SAFETY).copy(testMode = on))
        return "testMode=${onOff(on)}"
    }

    private suspend fun tap(session: ScreenSession, intent: Intent): ActionResult {
        val long = intent.getBooleanExtra(EXTRA_LONG, false)
        if (intent.hasExtra(EXTRA_X) || intent.hasExtra(EXTRA_Y)) {
            if (!intent.hasExtra(EXTRA_X) || !intent.hasExtra(EXTRA_Y)) throw HookError("add both --ei x and --ei y")
            val x = intent.getIntExtra(EXTRA_X, 0)
            val y = intent.getIntExtra(EXTRA_Y, 0)
            return if (long) session.actions.longPressAt(x, y) else session.actions.tapAt(x, y)
        }
        val key = target(session, intent)
        return if (long) session.actions.longPress(key) else session.actions.tap(key)
    }

    private suspend fun overlay(session: ScreenSession, intent: Intent) {
        when (intent.getStringExtra(EXTRA_SHOW)) {
            "numbers" -> session.showNumbers(NumbersMode.ALL)
            "unlabeled" -> session.showNumbers(NumbersMode.UNLABELED)
            "labels" -> session.showLabels()
            "grid" -> session.showGrid()
            "feedback" -> session.showFeedback(intent.requiredText())
            "none" -> {
                session.hideNumbers()
                session.hideLabels()
                session.hideGrid()
            }
            else -> throw HookError("add --es show with numbers, unlabeled, labels, grid, feedback or none")
        }
    }

    /** The element that the extras name: by its key, or by its number while the numbers show. */
    private suspend fun target(session: ScreenSession, intent: Intent): String =
        optionalTarget(session, intent) ?: throw HookError("add --es key <element key> or --ei number <number>")

    private suspend fun optionalTarget(session: ScreenSession, intent: Intent): String? {
        intent.getStringExtra(EXTRA_KEY)?.let { return it }
        if (!intent.hasExtra(EXTRA_NUMBER)) return null
        val number = intent.getIntExtra(EXTRA_NUMBER, 0)
        val badges = withContext(Dispatchers.Main) { session.numberBadges }
        return badges.firstOrNull { it.number == number }?.key ?: throw HookError("no element shows the number $number")
    }

    private fun done(result: ActionResult): String = when (result) {
        is ActionResult.Done -> "done: ${result.method.name.lowercase()}"
        is ActionResult.Failed -> throw HookError(result.reason)
    }

    private fun Intent.requiredText(): String = getStringExtra(EXTRA_TEXT) ?: throw HookError("add --es text <text>")

    private inline fun <reified T : Enum<T>> Intent.choice(name: String): T {
        val value = getStringExtra(name)?.replace('-', '_')
        return enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw HookError("add --es $name with one of ${enumValues<T>().joinToString { it.name.lowercase() }}")
    }

    private fun onOff(value: Boolean) = if (value) "on" else "off"

    private class HookError(override val message: String) : Exception(message)

    companion object {
        const val ACTION_PREFIX = "dev.kestrel.hook."
        const val SNAPSHOT_FILE = "hook-snapshot.json"
        private const val CRASH_DELAY_MILLIS = 500L
        private const val EXTRA_KEY = "key"
        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_X = "x"
        private const val EXTRA_Y = "y"
        private const val EXTRA_LONG = "long"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_DIRECTION = "direction"
        private const val EXTRA_ACTION = "action"
        private const val EXTRA_SHOW = "show"
        private const val EXTRA_ON = "on"
    }
}
```

Replace the whole content of `app/src/debug/kotlin/dev/kestrel/app/debug/TestHookReceiver.kt` with:

```kotlin
package dev.kestrel.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.kestrel.app.KestrelApplication
import dev.kestrel.privacy.logs.describeWithoutMessages
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Receives test-hook commands from adb, in debug builds only (design spec, section 16.1). Android delivers them only
 * from callers that hold `android.permission.DUMP`, which in practice means `adb shell`; `scripts/hook.ps1` sends
 * them. The answer is the broadcast's result code and data, see [HookReply].
 */
class TestHookReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as KestrelApplication).graph
        val pending = goAsync()
        graph.scope.launch {
            try {
                val reply = try {
                    withTimeout(TIMEOUT_MILLIS) { TestHooks(graph, context.applicationContext).run(intent) }
                } catch (error: Throwable) {
                    // Errors too, such as a StackOverflowError: without an answer, adb would wait 60 s for nothing.
                    HookReply.failed("the command failed: ${error.describeWithoutMessages()}")
                }
                pending.setResult(reply.code, reply.text, null)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        // Android waits 60 s for a background broadcast, and an action waits at most 3 s for the screen to settle.
        const val TIMEOUT_MILLIS = 20_000L
    }
}
```

Replace the whole content of `app/src/debug/AndroidManifest.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Spike S1 (design spec, section 19): the stand-in listening service and the location check. RECORD_AUDIO is in
         the main manifest too; lint checks the microphone service type against this file alone. -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

    <application>
        <!-- Test hooks for adb (design spec, section 16.1). Only callers that hold DUMP, in practice adb shell,
             can send commands. Release builds do not contain this receiver, and the audit checks that. -->
        <receiver
            android:name=".debug.TestHookReceiver"
            android:exported="true"
            android:permission="android.permission.DUMP" />

        <!-- Compose UI tests show their screens in this empty activity. ui-test-manifest declares it as exported, so
             any app could start it. The tests run in Kestrel's own process and can start it without that. -->
        <activity
            android:name="androidx.activity.ComponentActivity"
            android:exported="false"
            tools:replace="android:exported" />

        <!-- Spike S1. Sub-project 2 replaces these with the real ListeningService and tile. -->
        <service
            android:name=".debug.spike.SpikeListeningService"
            android:exported="false"
            android:foregroundServiceType="microphone|mediaPlayback" />
        <service
            android:name=".debug.spike.SpikeTileService"
            android:exported="true"
            android:icon="@android:drawable/ic_btn_speak_now"
            android:label="Kestrel spike"
            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
            <intent-filter>
                <action android:name="android.service.quicksettings.action.QS_TILE" />
            </intent-filter>
        </service>
        <provider
            android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup"
            android:exported="false"
            tools:node="merge">
            <meta-data
                android:name="dev.kestrel.app.debug.spike.SpikeStartup"
                android:value="androidx.startup" />
        </provider>
    </application>
</manifest>
```

Replace the whole content of `scripts/hook.ps1` with:

```powershell
# Sends a test-hook command to Kestrel's debug build on a test device and prints the answer (design spec,
# section 16.1). Commands and their options:
#   state                                   whether the screen layer runs, test mode and the overlays
#   test-mode -On true|false                switches test mode
#   snapshot [-Out file.json]               what Kestrel reads on the screen, as JSON
#   overlay -Show numbers|unlabeled|labels|grid|none
#   overlay -Show feedback -Text 'text'     shows the feedback bar
#   tap -Key key | -Number n | -X x -Y y [-Long]
#   type -Key key | -Number n -Text 'text'
#   scroll -Direction up|down|left|right [-Key key | -Number n]
#   global -Action back|home|recent-apps|notifications|quick-settings|lock-screen|screenshot|power-menu|split-screen|play-pause
#   crash -Text 'text'                      ends Kestrel's process with an error whose message is the text, to check
#                                           that Android's crash records do not contain it
# Spike S1 (scripts\spike-s1.ps1 runs these):
#   listen [-Source name] [-DelaySeconds n] [-Seconds n]
#                                           starts the stand-in listening service after n seconds (15 by default)
#   listen-notification                     posts a notification whose action starts it
#   listen-after-boot                       starts it 15 s after the accessibility service connects at the next
#                                           start of Kestrel's process, which a reboot causes
#   location [-Source name] [-DelaySeconds n]
#                                           asks Android for the current location
#   spike-results [-Clear]                  what the spike found: whether each step worked, never the audio or the place
# -Number picks an element by the number that the numbers overlay shows on it.
# Example: scripts\hook.ps1 -Serial emulator-5554 tap -Number 3
# Windows PowerShell 5.1 drops double quotes from the arguments of other programs, which would change the text that
# 'type' types, so the script needs PowerShell 7.3 or newer.
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('state', 'test-mode', 'snapshot', 'overlay', 'tap', 'type', 'scroll', 'global', 'crash', 'listen',
        'listen-notification', 'listen-after-boot', 'location', 'spike-results')][string]$Command,
    [string]$Key,
    [int]$Number,
    [int]$X = -1,
    [int]$Y = -1,
    [switch]$Long,
    [string]$Text,
    [string]$Direction,
    [string]$Action,
    [string]$Show,
    [ValidateSet('true', 'false')][string]$On,
    [string]$Out,
    [string]$Source,
    [int]$DelaySeconds = -1,
    [int]$Seconds = -1,
    [switch]$Clear
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial

# The device's shell reads the command line, so every text value is quoted for it.
function ConvertTo-ShellText([string]$Value) { "'" + $Value.Replace("'", "'\''") + "'" }

# Runs adb and reads its output as UTF-8, because screen text can be in any language.
function Invoke-Adb {
    $consoleEncoding = [Console]::OutputEncoding
    [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
    try {
        (& (Get-Adb) -s $Serial @args) -join "`n"
    } finally {
        [Console]::OutputEncoding = $consoleEncoding
    }
}

$extras = @()
if ($Key) { $extras += "--es key $(ConvertTo-ShellText $Key)" }
if ($PSBoundParameters.ContainsKey('Number')) { $extras += "--ei number $Number" }
if ($X -ge 0) { $extras += "--ei x $X" }
if ($Y -ge 0) { $extras += "--ei y $Y" }
if ($Long) { $extras += '--ez long true' }
if ($PSBoundParameters.ContainsKey('Text')) { $extras += "--es text $(ConvertTo-ShellText $Text)" }
if ($Direction) { $extras += "--es direction $(ConvertTo-ShellText $Direction)" }
if ($Action) { $extras += "--es action $(ConvertTo-ShellText $Action)" }
if ($Show) { $extras += "--es show $(ConvertTo-ShellText $Show)" }
if ($On) { $extras += "--ez on $On" }
if ($Source) { $extras += "--es source $(ConvertTo-ShellText $Source)" }
if ($DelaySeconds -ge 0) { $extras += "--ei delay $DelaySeconds" }
if ($Seconds -gt 0) { $extras += "--ei seconds $Seconds" }
if ($Clear) { $extras += '--ez clear true' }

$hookAction = 'dev.kestrel.hook.' + $Command.ToUpperInvariant().Replace('-', '_')
$broadcast = "am broadcast -n dev.kestrel/dev.kestrel.app.debug.TestHookReceiver -a $hookAction $($extras -join ' ')"
$output = Invoke-Adb shell $broadcast

$match = [regex]::Match($output, '(?s)Broadcast completed: result=(-?\d+)(?:, data="(.*)")?\s*$')
if (-not $match.Success) { throw "Unexpected answer from the device:`n$output" }
$data = $match.Groups[2].Value
switch ([int]$match.Groups[1].Value) {
    -1 {
        if ($Command -eq 'snapshot') {
            # A snapshot can be larger than a broadcast's answer, so Kestrel answers with the path of a file in its own
            # storage, which only a debug build lets adb read.
            $file = $data
            $data = Invoke-Adb exec-out run-as dev.kestrel cat $file
            Invoke-Adb shell run-as dev.kestrel rm $file | Out-Null
        }
        if ($Out) {
            $path = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Out)
            [IO.File]::WriteAllText($path, $data, [Text.UTF8Encoding]::new($false))
            "Saved to $path"
        } else {
            $data
        }
    }
    0 { throw 'Kestrel did not answer. Install a debug build with scripts\install.ps1.' }
    default { throw $data }
}
```

- [ ] **Step 3: Check the build, and that the release build has none of it**

```powershell
scripts\gradle.ps1 check kestrelAudit
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($variant in 'release', 'debug') {
    $apk = Get-ChildItem "app\build\outputs\apk\$variant" -Filter *.apk | Select-Object -First 1
    $zip = [IO.Compression.ZipFile]::OpenRead($apk.FullName)
    $classes = foreach ($entry in $zip.Entries | Where-Object FullName -like '*.dex') {
        $stream = [IO.MemoryStream]::new(); $entry.Open().CopyTo($stream)
        [regex]::Matches([Text.Encoding]::ASCII.GetString($stream.ToArray()), 'Ldev/kestrel/app/debug/[A-Za-z/$]+;') | ForEach-Object Value
    }
    $zip.Dispose()
    "$variant : $(@($classes | Sort-Object -Unique).Count) debug classes, of which spike: $(@($classes | Where-Object { $_ -like '*spike*' } | Sort-Object -Unique).Count)"
}
```

Expected: `BUILD SUCCESSFUL` without lint findings; `release : 0 debug classes, of which spike: 0`, and the debug build lists the spike's classes.

- [ ] **Step 4: Write the spike's script**

Create `scripts/spike-s1.ps1`:

```powershell
# Spike S1 (design spec, section 19): can Kestrel start its listening service and record when the accessibility service,
# a notification, the Quick Settings tile or a restart starts it, also while a call rings on the lock screen? Can it read
# the location while another app is in front? It needs Kestrel's debug build (scripts\install.ps1), and it prints what
# Kestrel recorded in its results: whether each step worked, never the audio or the place. The location comes from a
# test provider with a made-up place, which replaces the fused provider while the scenario runs.
# On a phone, the ringing scenario asks you to call the phone, to end the call and, if it has a PIN, to unlock it.
# Example: scripts\spike-s1.ps1 -Serial emulator-5554 -Scenario accessibility, no-service
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [ValidateSet('accessibility', 'no-service', 'notification', 'tile', 'ringing', 'location', 'boot')]
    [string[]]$Scenario = @('accessibility', 'no-service', 'notification', 'tile', 'ringing', 'location', 'boot')
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial
$adb = Get-Adb
$service = 'dev.kestrel/dev.kestrel.app.KestrelAccessibilityService'
$tile = 'dev.kestrel/dev.kestrel.app.debug.spike.SpikeTileService'
$isEmulator = $Serial -like 'emulator-*'
$caller = '0641112233'

function Invoke-Shell([string]$Command) { (& $adb -s $Serial shell $Command) -join "`n" }
function Invoke-Hook { (& "$PSScriptRoot\hook.ps1" -Serial $Serial @args) -join "`n" }

function Set-KestrelService([bool]$On) {
    if (-not $On) {
        Invoke-Shell 'settings delete secure enabled_accessibility_services' | Out-Null
        Start-Sleep -Seconds 2
        return
    }
    Invoke-Shell "settings put secure enabled_accessibility_services $service" | Out-Null
    Invoke-Shell 'settings put secure accessibility_enabled 1' | Out-Null
    for ($i = 0; $i -lt 30; $i++) {
        if ((Invoke-Hook state) -like 'screen=on*') { return }
        Start-Sleep -Seconds 1
    }
    throw "Kestrel's accessibility service did not connect within 30 s."
}

# Waits for the newest result line that matches the pattern.
function Wait-Result([string]$Pattern, [int]$TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $lines = @((Invoke-Hook spike-results) -split "`n" | Where-Object { $_ -match $Pattern })
        if ($lines) { return $lines[-1] }
        Start-Sleep -Seconds 2
    }
    "no result within $TimeoutSeconds s"
}

# Taps the first element whose text is the given text, such as a notification's action.
function Invoke-TapText([string]$Text) {
    Invoke-Shell 'uiautomator dump /sdcard/spike-ui.xml' | Out-Null
    [xml]$ui = (& $adb -s $Serial exec-out cat /sdcard/spike-ui.xml) -join "`n"
    Invoke-Shell 'rm -f /sdcard/spike-ui.xml' | Out-Null
    $node = $ui.SelectNodes('//node') | Where-Object { $_.text -ieq $Text } | Select-Object -First 1
    if (-not $node) { throw "Nothing on the screen shows '$Text'." }
    $bounds = [regex]::Match($node.bounds, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]').Groups
    $x = ([int]$bounds[1].Value + [int]$bounds[3].Value) / 2
    $y = ([int]$bounds[2].Value + [int]$bounds[4].Value) / 2
    Invoke-Shell "input tap $x $y" | Out-Null
}

function Start-Call {
    if ($isEmulator) { & $adb -s $Serial emu gsm call $caller | Out-Null } else { Read-Host 'Call this phone now, and press Enter when it rings' | Out-Null }
}

function Stop-Call {
    if ($isEmulator) { & $adb -s $Serial emu gsm cancel $caller | Out-Null } else { Read-Host 'End the call, then press Enter' | Out-Null }
}

function Lock-Screen {
    Invoke-Shell 'input keyevent KEYCODE_SLEEP' | Out-Null
    Start-Sleep -Seconds 2
    Invoke-Shell 'input keyevent KEYCODE_WAKEUP' | Out-Null
    Start-Sleep -Seconds 2
}

function Unlock-Screen {
    Invoke-Shell 'wm dismiss-keyguard' | Out-Null
    Start-Sleep -Seconds 2
    if (Test-LockScreen) { Read-Host 'Unlock the phone, then press Enter' | Out-Null }
}

function Test-LockScreen { (Invoke-Shell 'dumpsys activity activities') -match '(?m)^\s*KeyguardShowing=true' }

function Test-Ringing { (Invoke-Shell 'dumpsys telephony.registry') -match 'mCallState=1' }

# Asks Kestrel for the location, and lets the test provider report its place a few times meanwhile.
function Get-SpikeLocation([string]$Source) {
    Invoke-Hook location -Source $Source | Out-Null
    for ($i = 0; $i -lt 4; $i++) {
        Start-Sleep -Seconds 3
        Invoke-Shell 'cmd location providers set-test-provider-location fused --location 44.8125,20.4612 --accuracy 10' | Out-Null
    }
    Wait-Result "source=$Source " 20
}

function Get-Granted([string]$Permission) {
    (Invoke-Shell 'dumpsys package dev.kestrel') -match "$([regex]::Escape($Permission)): granted=true"
}

$results = [ordered]@{}
$savedAccessibility = Get-AccessibilitySettings -Serial $Serial
$locationGranted = Get-Granted 'android.permission.ACCESS_FINE_LOCATION'
try {
    foreach ($permission in 'RECORD_AUDIO', 'POST_NOTIFICATIONS', 'ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION') {
        Invoke-Shell "pm grant dev.kestrel android.permission.$permission" | Out-Null
    }
    Invoke-Hook spike-results -Clear | Out-Null

    foreach ($name in $Scenario) {
        Write-Host "Scenario $name"
        switch ($name) {
            'accessibility' {
                # The recording starts 15 s after the hook's broadcast, while another app is in front.
                Set-KestrelService $true
                Invoke-Shell 'input keyevent KEYCODE_HOME' | Out-Null
                Invoke-Hook listen -Source accessibility -DelaySeconds 15 -Seconds 3 | Out-Null
                $results[$name] = Wait-Result 'source=accessibility ' 40
            }
            'no-service' {
                # The control: no accessibility service, nothing in front, no notification or tile.
                Set-KestrelService $false
                Invoke-Shell 'input keyevent KEYCODE_HOME' | Out-Null
                Invoke-Hook listen -Source no-service -DelaySeconds 0 -Seconds 3 | Out-Null
                $results[$name] = Wait-Result 'source=no-service ' 20
            }
            'notification' {
                Set-KestrelService $false
                Invoke-Shell 'input keyevent KEYCODE_HOME' | Out-Null
                Invoke-Hook listen-notification | Out-Null
                Invoke-Shell 'cmd statusbar expand-notifications' | Out-Null
                Start-Sleep -Seconds 2
                Invoke-TapText 'Start listening'
                $results[$name] = Wait-Result 'source=notification ' 20
                Invoke-Shell 'cmd statusbar collapse' | Out-Null
                Invoke-Shell 'cmd notification cancel_all' | Out-Null
            }
            'tile' {
                Set-KestrelService $false
                Invoke-Shell 'input keyevent KEYCODE_HOME' | Out-Null
                Invoke-Shell "cmd statusbar add-tile $tile" | Out-Null
                Start-Sleep -Seconds 2
                Invoke-Shell "cmd statusbar click-tile $tile" | Out-Null
                $results[$name] = Wait-Result 'source=tile ' 20
                Invoke-Shell "cmd statusbar remove-tile $tile" | Out-Null
                Invoke-Shell 'cmd statusbar collapse' | Out-Null
            }
            'ringing' {
                # First Kestrel records while the screen is locked and a call starts to ring; then it starts recording
                # while a call rings. An emulator has no lock screen until it is turned on.
                $lockDisabled = (Invoke-Shell 'cmd lock_settings get-disabled').Trim()
                Invoke-Shell 'cmd lock_settings set-disabled false' | Out-Null
                try {
                    Set-KestrelService $true
                    Lock-Screen
                    $locked = Test-LockScreen
                    Invoke-Hook listen -Source ringing-already -DelaySeconds 0 -Seconds 20 | Out-Null
                    Start-Sleep -Seconds 5
                    Start-Call
                    Start-Sleep -Seconds 3
                    $rang = Test-Ringing
                    Start-Sleep -Seconds 7
                    Stop-Call
                    $line = Wait-Result 'source=ringing-already ' 30
                    $results['ringing, already recording'] = "locked=$locked ringing=$rang $line"
                    Start-Call
                    Start-Sleep -Seconds 3
                    $locked = Test-LockScreen
                    $rang = Test-Ringing
                    Invoke-Hook listen -Source ringing-start -DelaySeconds 0 -Seconds 3 | Out-Null
                    $line = Wait-Result 'source=ringing-start ' 20
                    $results['ringing, start'] = "locked=$locked ringing=$rang $line"
                    Stop-Call
                    Unlock-Screen
                } finally {
                    if ($lockDisabled -eq 'true') { Invoke-Shell 'cmd lock_settings set-disabled true' | Out-Null }
                }
            }
            'location' {
                # The shell may add a test provider only with the mock-location permission.
                $mockMode = if ((Invoke-Shell 'appops get com.android.shell android:mock_location') -match 'mock_location: allow') { 'allow' } else { 'default' }
                Invoke-Shell 'appops set com.android.shell android:mock_location allow' | Out-Null
                Invoke-Shell 'cmd location set-location-enabled true' | Out-Null
                Invoke-Shell 'cmd location providers add-test-provider fused' | Out-Null
                Invoke-Shell 'cmd location providers set-test-provider-enabled fused true' | Out-Null
                try {
                    Set-KestrelService $true
                    Invoke-Shell 'am start -W -n dev.kestrel/dev.kestrel.app.MainActivity' | Out-Null
                    $results['location, Kestrel in front'] = Get-SpikeLocation 'kestrel-in-front'
                    Invoke-Shell 'am start -W -a android.settings.SETTINGS' | Out-Null
                    $results['location, Settings in front'] = Get-SpikeLocation 'settings-in-front'
                    Set-KestrelService $false
                    $results['location, service off'] = Get-SpikeLocation 'service-off'
                    Invoke-Shell 'input keyevent KEYCODE_HOME' | Out-Null
                } finally {
                    Invoke-Shell 'cmd location providers remove-test-provider fused' | Out-Null
                    Invoke-Shell "appops set com.android.shell android:mock_location $mockMode" | Out-Null
                }
            }
            'boot' {
                Set-KestrelService $true
                Invoke-Hook listen-after-boot | Out-Null
                & $adb -s $Serial reboot
                & $adb -s $Serial wait-for-device
                while ((Invoke-Shell 'getprop sys.boot_completed').Trim() -ne '1') { Start-Sleep -Seconds 2 }
                $results[$name] = Wait-Result 'source=boot ' 240
            }
        }
    }
} finally {
    $null = Restore-AccessibilitySettings -Serial $Serial -Saved $savedAccessibility
    # Revoking a permission stops Kestrel's process, so this comes last.
    if (-not $locationGranted) {
        Invoke-Shell 'pm revoke dev.kestrel android.permission.ACCESS_FINE_LOCATION' | Out-Null
        Invoke-Shell 'pm revoke dev.kestrel android.permission.ACCESS_COARSE_LOCATION' | Out-Null
    }
}
$results.GetEnumerator() | ForEach-Object { '{0,-30} {1}' -f $_.Key, $_.Value }
```

- [ ] **Step 5: Run the spike on the emulator**

```powershell
scripts\install.ps1 -Serial <serial>
scripts\spike-s1.ps1 -Serial <serial>
```

The `boot` scenario reboots the emulator, so the whole run takes about 5 minutes. Expected, apart from the times at the start of each result:

```text
accessibility                  ... listen source=accessibility recording=true frames=48000 loudest=8 silenced=nnn
no-service                     ... listen source=no-service start=not-allowed
notification                   ... listen source=notification recording=true frames=48000 loudest=8 silenced=nnn
tile                           ... listen source=tile recording=true frames=48000 loudest=8 silenced=nnn
ringing, already recording     locked=True ringing=True ... listen source=ringing-already recording=true frames=320000 loudest=8 silenced=nnnnnnnnnnnnnnnnnnnn
ringing, start                 locked=True ringing=True ... listen source=ringing-start recording=true frames=48000 loudest=8 silenced=nnn
location, Kestrel in front     ... location source=kestrel-in-front provider=fused result=found
location, Settings in front    ... location source=settings-in-front provider=fused result=found
location, service off          ... location source=service-off provider=fused result=none
boot                           ... listen source=boot recording=true frames=48000 loudest=8 silenced=nnn
```

`loudest` is the loudest sample of the emulator's microphone, which is nearly silent; another number is fine. If any other part of a result differs, stop and report the script's output: the spec text of Step 6 describes these results.

Afterwards, check that the script restored the emulator: `settings get secure enabled_accessibility_services` prints `null`, `cmd lock_settings get-disabled` prints `true`, and `appops get com.android.shell android:mock_location` prints `default` (Android shows `No operations` or `MOCK_LOCATION: default`).

- [ ] **Step 6: Document the spike and its results**

In `README.md`, insert this section before the line `## Licence`:

````markdown
### Spike S1

`scripts\spike-s1.ps1 -Serial $serial` checks whether Kestrel's listening service can start and record when the accessibility service, a notification, the Quick Settings tile or a reboot starts it, also while a call rings on the lock screen, and whether Kestrel can read the location while another app is in front (design spec, section 19). It uses a stand-in listening service and a tile that only debug builds contain, and it prints whether each step worked, never the audio or the place. While it runs, it changes the accessibility services, the lock-screen setting and the location provider, and it puts them back afterwards. The `boot` scenario reboots the device. On a phone, the `ringing` scenario asks you to call the phone and to end the call.
````

In the spec, section 19.1, after the line that starts with "- **S3 on the emulator (plan 1B).**", add this line:

````markdown
- **S1 on the emulator (plan 1C).** A stand-in for `ListeningService`, a foreground service of the types microphone and media playback, started and recorded in every case that S1 asks about except the assistant session, which needs the assistant role and is checked with S2 in sub-project 2. With Kestrel's accessibility service on, Kestrel started it while another app was in front, 15 s after a test hook, and after a reboot, 15 s after the accessibility service connected. A tap on a notification action or on a Quick Settings tile started it even with the accessibility service off, while a start from the background without the service was refused. While the screen was locked and a call rang, a recording that was already running went on, and a new one started. Android never silenced a recording (`AudioRecordingConfiguration.isClientSilenced()` stayed false). With the accessibility service on, Kestrel got the location while the Settings app was in front; with the service off, no location came. `scripts\spike-s1.ps1` repeats these checks. The run on the A71 is still open (plan 1C).
````

- [ ] **Step 7: Run all device tests**

```powershell
scripts\connected-test.ps1 -Serial <serial> :core:privacy:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`, 7 tests in `:core:privacy` and 91 in `:app`.

- [ ] **Step 8: Commit**

```powershell
git add app/src/debug scripts README.md docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "test: add spike S1, a stand-in listening service with its start paths and a location check" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 12: Spikes S4 and S7, and a touch count for S3

**Files:**
- Modify: `app/src/debug/kotlin/dev/kestrel/app/debug/spike/SpikeHooks.kt` (`FILES` and `WATCH`)
- Modify: `scripts/hook.ps1` (`files` and `watch`)
- Create: `scripts/spike-s7.ps1`
- Create: `scripts/spike-s4/page.html` and `scripts/spike-s4.ps1`
- Modify: `README.md` and `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 19.1)

**Interfaces:**
- Consumes: Task 11's spike hooks.
- Produces: the debug hooks `FILES [--es create <folder>]`, which lists the files in Kestrel's external files folder with their sizes and SHA-256 hashes and the folders that Kestrel may not read, and `WATCH [--ei seconds <s>]`, which counts the owner's touches that the touch watcher reports (10 s by default, at most 15); the `hook.ps1` commands `files [-Create folder]` and `watch [-Seconds n]`.

Spike S7 asks whether `adb push` into `Android/data/dev.kestrel/files` works on Android 13 and 17, because `scripts/push-models.ps1` (sub-project 2) will copy the model files there. The script pushes a file of random bytes into a folder that adb creates, into the same folder after `chmod 2775`, and into a folder that Kestrel creates, and compares the hash that Kestrel reads with the computer's.

Spike S4 asks how complete the tree is in Chrome, Firefox and Samsung Internet with Kestrel's event types, and which apps accept set-text. The script serves a test page from this computer with the JDK's `jwebserver`, opens it on the device through `adb reverse`, and checks which of the page's sixteen kinds of element Kestrel reads, whether Kestrel can type into its four kinds of text field, and whether scrolling brings the page's last item into the snapshot. Samsung Internet, the messaging apps and the keyboards are checked on the A71 in Task 14.

`WATCH` is for spike S3 on the A71: the owner taps the screen while it counts.

- [ ] **Step 1: Add the hooks**

Replace the whole content of `app/src/debug/kotlin/dev/kestrel/app/debug/spike/SpikeHooks.kt` with:

```kotlin
package dev.kestrel.app.debug.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import dev.kestrel.app.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume

/** The test-hook commands of spikes S1, S3 and S7. `scripts/hook.ps1` lists them. */
internal class SpikeHooks(private val context: Context, private val graph: AppGraph) {
    suspend fun run(command: String, intent: Intent): String = when (command) {
        "FILES" -> files(intent)
        "LISTEN" -> listen(intent)
        "LISTEN_NOTIFICATION" -> listenNotification()
        "LISTEN_AFTER_BOOT" -> {
            SpikeListening.requestListenAtNextStart(context)
            "Kestrel records 15 s after its accessibility service connects at the next start of its process"
        }
        "LOCATION" -> location(intent)
        "WATCH" -> watch(intent)
        else -> results(intent)
    }

    /** Starts a recording after a delay, so that the hook's own broadcast is long over when it starts. */
    private fun listen(intent: Intent): String {
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: "hook"
        val delaySeconds = intent.getIntExtra(EXTRA_DELAY, DEFAULT_DELAY_SECONDS)
        val seconds = intent.getIntExtra(EXTRA_SECONDS, DEFAULT_SECONDS)
        graph.scope.launch {
            delay(delaySeconds * 1_000L)
            SpikeListening.start(context, source, seconds)
        }
        return "recording in $delaySeconds s for $seconds s"
    }

    private fun listenNotification(): String {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Spike", NotificationManager.IMPORTANCE_HIGH))
        val start = PendingIntent.getForegroundService(
            context,
            0,
            SpikeListeningService.intent(context, "notification", DEFAULT_SECONDS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Kestrel spike")
            .addAction(Notification.Action.Builder(null, ACTION_LABEL, start).build())
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        return "posted a notification with the action '$ACTION_LABEL'"
    }

    private fun location(intent: Intent): String {
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: "hook"
        val delaySeconds = intent.getIntExtra(EXTRA_DELAY, 0)
        graph.scope.launch {
            delay(delaySeconds * 1_000L)
            SpikeListening.record(context, "location source=$source ${locate()}")
        }
        return "locating in $delaySeconds s"
    }

    /** Says only whether a location came, never where the device is. */
    private suspend fun locate(): String {
        val manager = context.getSystemService(LocationManager::class.java)
        if (!manager.isLocationEnabled) return "result=location-off"
        val provider = if (manager.hasProvider(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER
        return try {
            val location = withTimeoutOrNull(LOCATION_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    manager.getCurrentLocation(provider, signal, context.mainExecutor) { continuation.resume(it) }
                }
            }
            "provider=$provider result=${if (location == null) "none" else "found"}"
        } catch (e: SecurityException) {
            "provider=$provider result=SecurityException"
        }
    }

    /**
     * Lists the files in Kestrel's external files folder, where `adb push` puts the model files (spike S7), with their
     * sizes and SHA-256 hashes, and the folders that Kestrel may not read. With `--es create <folder>`, Kestrel first
     * creates that folder in it.
     */
    private suspend fun files(intent: Intent): String = withContext(Dispatchers.IO) {
        intent.getStringExtra(EXTRA_CREATE)?.let { context.getExternalFilesDir(it) }
        val root = context.getExternalFilesDir(null) ?: return@withContext "no external files folder"
        val lines = mutableListOf<String>()
        val files = root.walkTopDown().onFail { folder, _ -> lines += "${folder.relativeTo(root).invariantSeparatorsPath}/ unreadable" }
        for (file in files) {
            if (file.isFile) lines += "${file.relativeTo(root).invariantSeparatorsPath} ${file.length()} ${sha256(file)}"
        }
        lines.joinToString("\n").ifEmpty { "no files" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Counts the owner's touches that the touch watcher reports for a few seconds, so that spike S3 can be checked with
     * a real finger. Kestrel's own gestures do not count.
     */
    private suspend fun watch(intent: Intent): String {
        val session = graph.screen.value ?: return "Kestrel's accessibility service is off"
        val seconds = intent.getIntExtra(EXTRA_SECONDS, DEFAULT_WATCH_SECONDS).coerceIn(1, MAX_WATCH_SECONDS)
        session.startTouchWatcher()
        try {
            var touches = 0
            withTimeoutOrNull(seconds * 1_000L) { session.ownerTouches.collect { touches++ } }
            return "owner touches in $seconds s: $touches"
        } finally {
            session.stopTouchWatcher()
        }
    }

    private fun results(intent: Intent): String {
        if (intent.getBooleanExtra(EXTRA_CLEAR, false)) {
            SpikeListening.clearResults(context)
            return "cleared"
        }
        return SpikeListening.results(context).ifEmpty { "none yet" }
    }

    companion object {
        val COMMANDS = setOf("FILES", "LISTEN", "LISTEN_NOTIFICATION", "LISTEN_AFTER_BOOT", "LOCATION", "SPIKE_RESULTS", "WATCH")
        private const val BUFFER_BYTES = 1 shl 16
        private const val CHANNEL = "spike"
        private const val NOTIFICATION_ID = 7_002
        private const val ACTION_LABEL = "Start listening"
        private const val DEFAULT_DELAY_SECONDS = 15
        private const val DEFAULT_SECONDS = 3
        private const val DEFAULT_WATCH_SECONDS = 10

        // The hook's answer must come within the receiver's 20 s.
        private const val MAX_WATCH_SECONDS = 15
        private const val LOCATION_TIMEOUT_MILLIS = 15_000L
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_DELAY = "delay"
        private const val EXTRA_SECONDS = "seconds"
        private const val EXTRA_CLEAR = "clear"
        private const val EXTRA_CREATE = "create"
    }
}
```

Replace the whole content of `scripts/hook.ps1` with:

```powershell
# Sends a test-hook command to Kestrel's debug build on a test device and prints the answer (design spec,
# section 16.1). Commands and their options:
#   state                                   whether the screen layer runs, test mode and the overlays
#   test-mode -On true|false                switches test mode
#   snapshot [-Out file.json]               what Kestrel reads on the screen, as JSON
#   overlay -Show numbers|unlabeled|labels|grid|none
#   overlay -Show feedback -Text 'text'     shows the feedback bar
#   tap -Key key | -Number n | -X x -Y y [-Long]
#   type -Key key | -Number n -Text 'text'
#   scroll -Direction up|down|left|right [-Key key | -Number n]
#   global -Action back|home|recent-apps|notifications|quick-settings|lock-screen|screenshot|power-menu|split-screen|play-pause
#   crash -Text 'text'                      ends Kestrel's process with an error whose message is the text, to check
#                                           that Android's crash records do not contain it
# Spike S1 (scripts\spike-s1.ps1 runs these):
#   listen [-Source name] [-DelaySeconds n] [-Seconds n]
#                                           starts the stand-in listening service after n seconds (15 by default)
#   listen-notification                     posts a notification whose action starts it
#   listen-after-boot                       starts it 15 s after the accessibility service connects at the next
#                                           start of Kestrel's process, which a reboot causes
#   location [-Source name] [-DelaySeconds n]
#                                           asks Android for the current location
#   spike-results [-Clear]                  what the spike found: whether each step worked, never the audio or the place
# Spike S3 with a real finger:
#   watch [-Seconds n]                      counts the touches of the owner that the touch watcher reports during n
#                                           seconds (10 by default, at most 15); Kestrel's own gestures do not count
# Spike S7 (scripts\spike-s7.ps1 runs it):
#   files [-Create folder]                  the files in Kestrel's external files folder, with sizes and SHA-256
#                                           hashes, and the folders that Kestrel may not read; -Create makes Kestrel
#                                           create that folder in it first
# -Number picks an element by the number that the numbers overlay shows on it.
# Example: scripts\hook.ps1 -Serial emulator-5554 tap -Number 3
# Windows PowerShell 5.1 drops double quotes from the arguments of other programs, which would change the text that
# 'type' types, so the script needs PowerShell 7.3 or newer.
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('state', 'test-mode', 'snapshot', 'overlay', 'tap', 'type', 'scroll', 'global', 'crash', 'listen',
        'listen-notification', 'listen-after-boot', 'location', 'spike-results', 'files', 'watch')][string]$Command,
    [string]$Key,
    [int]$Number,
    [int]$X = -1,
    [int]$Y = -1,
    [switch]$Long,
    [string]$Text,
    [string]$Direction,
    [string]$Action,
    [string]$Show,
    [ValidateSet('true', 'false')][string]$On,
    [string]$Out,
    [string]$Source,
    [int]$DelaySeconds = -1,
    [int]$Seconds = -1,
    [switch]$Clear,
    [string]$Create
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial

# The device's shell reads the command line, so every text value is quoted for it.
function ConvertTo-ShellText([string]$Value) { "'" + $Value.Replace("'", "'\''") + "'" }

# Runs adb and reads its output as UTF-8, because screen text can be in any language.
function Invoke-Adb {
    $consoleEncoding = [Console]::OutputEncoding
    [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
    try {
        (& (Get-Adb) -s $Serial @args) -join "`n"
    } finally {
        [Console]::OutputEncoding = $consoleEncoding
    }
}

$extras = @()
if ($Key) { $extras += "--es key $(ConvertTo-ShellText $Key)" }
if ($PSBoundParameters.ContainsKey('Number')) { $extras += "--ei number $Number" }
if ($X -ge 0) { $extras += "--ei x $X" }
if ($Y -ge 0) { $extras += "--ei y $Y" }
if ($Long) { $extras += '--ez long true' }
if ($PSBoundParameters.ContainsKey('Text')) { $extras += "--es text $(ConvertTo-ShellText $Text)" }
if ($Direction) { $extras += "--es direction $(ConvertTo-ShellText $Direction)" }
if ($Action) { $extras += "--es action $(ConvertTo-ShellText $Action)" }
if ($Show) { $extras += "--es show $(ConvertTo-ShellText $Show)" }
if ($On) { $extras += "--ez on $On" }
if ($Source) { $extras += "--es source $(ConvertTo-ShellText $Source)" }
if ($DelaySeconds -ge 0) { $extras += "--ei delay $DelaySeconds" }
if ($Seconds -gt 0) { $extras += "--ei seconds $Seconds" }
if ($Clear) { $extras += '--ez clear true' }
if ($Create) { $extras += "--es create $(ConvertTo-ShellText $Create)" }

$hookAction = 'dev.kestrel.hook.' + $Command.ToUpperInvariant().Replace('-', '_')
$broadcast = "am broadcast -n dev.kestrel/dev.kestrel.app.debug.TestHookReceiver -a $hookAction $($extras -join ' ')"
$output = Invoke-Adb shell $broadcast

$match = [regex]::Match($output, '(?s)Broadcast completed: result=(-?\d+)(?:, data="(.*)")?\s*$')
if (-not $match.Success) { throw "Unexpected answer from the device:`n$output" }
$data = $match.Groups[2].Value
switch ([int]$match.Groups[1].Value) {
    -1 {
        if ($Command -eq 'snapshot') {
            # A snapshot can be larger than a broadcast's answer, so Kestrel answers with the path of a file in its own
            # storage, which only a debug build lets adb read.
            $file = $data
            $data = Invoke-Adb exec-out run-as dev.kestrel cat $file
            Invoke-Adb shell run-as dev.kestrel rm $file | Out-Null
        }
        if ($Out) {
            $path = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Out)
            [IO.File]::WriteAllText($path, $data, [Text.UTF8Encoding]::new($false))
            "Saved to $path"
        } else {
            $data
        }
    }
    0 { throw 'Kestrel did not answer. Install a debug build with scripts\install.ps1.' }
    default { throw $data }
}
```

```powershell
scripts\gradle.ps1 check kestrelAudit
scripts\install.ps1 -Serial <serial>
```

Expected: `BUILD SUCCESSFUL` without lint findings.

- [ ] **Step 2: Check the touch count**

The touch watcher counts touches from `adb shell input tap` on another app's window (spec section 19.1, S3).

```powershell
$adb = Get-Adb
& $adb -s <serial> shell settings put secure enabled_accessibility_services dev.kestrel/dev.kestrel.app.KestrelAccessibilityService
Start-Sleep -Seconds 4
& $adb -s <serial> shell am start -W -a android.settings.SETTINGS
$hook = (Resolve-Path scripts\hook.ps1).Path
$job = Start-ThreadJob { & $using:hook -Serial <serial> watch -Seconds 6 }
Start-Sleep -Seconds 3; & $adb -s <serial> shell input tap 540 1500; Start-Sleep -Seconds 1; & $adb -s <serial> shell input tap 540 1700
Receive-Job $job -Wait -AutoRemoveJob
scripts\hook.ps1 -Serial <serial> watch -Seconds 4
& $adb -s <serial> shell settings delete secure enabled_accessibility_services
```

Expected: `owner touches in 6 s: 2`, then `owner touches in 4 s: 0`.

- [ ] **Step 3: Write spike S7's script and run it**

Create `scripts/spike-s7.ps1`:

```powershell
# Spike S7 (design spec, section 19): does adb push copy files into Kestrel's external files folder, where the model
# files live (section 8.10), and can Kestrel read them? The script pushes a file of random bytes into a folder that adb
# creates, into the same folder after chmod 2775, and into a folder that Kestrel creates. Each time, it compares the
# SHA-256 hash that Kestrel reads with this computer's. It also checks that the file stays after the adb install -r of
# scripts\install.ps1, and it deletes the folders at the end. It needs Kestrel's debug build, built and installed with
# scripts\install.ps1.
# Example: scripts\spike-s7.ps1 -Serial emulator-5554
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [ValidateRange(1, 1024)][int]$SizeMegabytes = 64
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial
$adb = Get-Adb
$base = '/sdcard/Android/data/dev.kestrel/files'
$name = 'spike-s7.bin'
$apk = Join-Path $PSScriptRoot '..\app\build\outputs\apk\debug\app-debug.apk'
$local = Join-Path $env:TEMP "kestrel-$name"

function Invoke-Shell([string]$Command) { (& $adb -s $Serial shell $Command) -join "`n" }

function Send-File([string]$Folder) {
    $time = Measure-Command { & $adb -s $Serial push $local "$base/$Folder/$name" | Out-Null }
    if ($LASTEXITCODE -ne 0) { throw 'adb push failed.' }
    $time
}

# Whether Kestrel reads the file in the folder with the same size and hash as this computer.
function Test-KestrelFile([string]$Folder, [string]$Create) {
    $options = @{}
    if ($Create) { $options.Create = $Create }
    $listing = (& "$PSScriptRoot\hook.ps1" -Serial $Serial files @options) -join "`n"
    $lines = $listing -split "`n"
    if ($lines -contains "$Folder/ unreadable") { return 'no, Kestrel may not read the folder' }
    $line = $lines | Where-Object { $_ -like "$Folder/$name *" } | Select-Object -First 1
    if (-not $line) { return 'no, Kestrel does not see the file' }
    if (($line -split ' ', 2)[1] -eq $expected) { 'yes' } else { "no, Kestrel reads '$line'" }
}

$bytes = [byte[]]::new($SizeMegabytes * 1MB)
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
[IO.File]::WriteAllBytes($local, $bytes)
$expected = "$($bytes.Length) $((Get-FileHash $local -Algorithm SHA256).Hash.ToLowerInvariant())"
try {
    Invoke-Shell "mkdir -p $base/spike-s7-adb" | Out-Null
    $time = Send-File 'spike-s7-adb'
    '{0,-40} {1:N1} MB/s' -f 'push speed', ($SizeMegabytes / $time.TotalSeconds)
    '{0,-40} {1}' -f 'a folder that adb creates', (Test-KestrelFile 'spike-s7-adb')
    Invoke-Shell "chmod 2775 $base/spike-s7-adb" | Out-Null
    '{0,-40} {1}' -f 'the same folder after chmod 2775', (Test-KestrelFile 'spike-s7-adb')
    Test-KestrelFile 'spike-s7-kestrel' -Create 'spike-s7-kestrel' | Out-Null
    Send-File 'spike-s7-kestrel' | Out-Null
    '{0,-40} {1}' -f 'a folder that Kestrel creates', (Test-KestrelFile 'spike-s7-kestrel')
    if (Test-Path $apk) {
        & $adb -s $Serial install -r $apk | Out-Null
        '{0,-40} {1}' -f 'the same after adb install -r', (Test-KestrelFile 'spike-s7-kestrel')
    } else {
        '{0,-40} {1}' -f 'the same after adb install -r', "not checked: build $apk with scripts\install.ps1 first"
    }
} finally {
    Invoke-Shell "rm -rf $base/spike-s7-adb $base/spike-s7-kestrel" | Out-Null
    Remove-Item $local -ErrorAction SilentlyContinue
}
```

```powershell
scripts\spike-s7.ps1 -Serial <serial>
& (Get-Adb) -s <serial> shell ls /sdcard/Android/data/dev.kestrel/files
```

Expected (the speed varies):

```text
push speed                               25.0 MB/s
a folder that adb creates                no, Kestrel may not read the folder
the same folder after chmod 2775         yes
a folder that Kestrel creates            yes
the same after adb install -r            yes
```

and the `ls` prints nothing: the script deleted its folders.

- [ ] **Step 4: Write spike S4's page and script**

Create `scripts/spike-s4/page.html`:

```html
<!DOCTYPE html>
<html lang="en">
<!-- Spike S4 (design spec, section 19): scripts\spike-s4.ps1 opens this page in a browser and checks which of its
     elements Kestrel can read and type into. -->
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Kestrel browser check</title>
<style>
  body { font-family: sans-serif; margin: 16px; }
  .custom, .clicky { display: inline-block; padding: 12px; margin: 4px 0; background: #ddd; }
  .box { border: 1px solid #888; min-height: 48px; padding: 8px; }
  li { padding: 8px 0; }
</style>
</head>
<body>
<h1>Kestrel browser check</h1>
<p>This page checks what Kestrel can read in a browser.</p>
<p><a href="#items">Next page</a></p>
<p>
  <button type="button">Send message</button>
  <button type="button" aria-label="Search">
    <svg width="24" height="24" viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="10" cy="10" r="7" fill="none" stroke="black" stroke-width="2"/>
      <line x1="15" y1="15" x2="22" y2="22" stroke="black" stroke-width="2"/>
    </svg>
  </button>
</p>
<p><label for="name">Name</label> <input id="name" type="text"></p>
<p><input type="email" placeholder="Email address"></p>
<p><input type="checkbox" id="terms"> <label for="terms">I agree</label></p>
<p><select aria-label="Country"><option>Serbia</option><option>Croatia</option></select></p>
<p><img alt="A kestrel in flight" width="48" height="48"
        src="data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='48' height='48'><rect width='48' height='48' fill='%23a52'/></svg>"></p>
<div class="custom" role="button" tabindex="0">Custom button</div>
<div class="clicky" onclick="this.textContent = 'Clicked div'">Clickable div</div>
<p><canvas id="canvas" width="200" height="40"></canvas></p>
<div class="box" contenteditable="true" role="textbox" aria-label="Message box"></div>
<p><textarea aria-label="Notes" rows="2"></textarea></p>
<ul id="items"></ul>
<script>
  const context = document.getElementById('canvas').getContext('2d');
  context.font = '20px sans-serif';
  context.fillText('Canvas text', 4, 28);
  const list = document.getElementById('items');
  for (let i = 1; i <= 30; i++) {
    const item = document.createElement('li');
    item.textContent = 'Item ' + i;
    list.appendChild(item);
  }
</script>
</body>
</html>
```

Create `scripts/spike-s4.ps1`:

```powershell
# Spike S4 (design spec, section 19): how much of a web page does Kestrel read in Chrome, Firefox and Samsung Internet,
# and can it type into the page's text fields? The script serves scripts\spike-s4\page.html from this computer with
# the JDK's jwebserver, opens it on the device through adb reverse, and compares Kestrel's snapshot with the page.
# It needs Kestrel's debug build (scripts\install.ps1) and the browsers. It taps through first-run screens whose
# buttons it knows, and otherwise asks you to finish them.
# Example: scripts\spike-s4.ps1 -Serial emulator-5554 -Browser chrome, firefox
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [ValidateSet('chrome', 'firefox', 'samsung')][string[]]$Browser = @('chrome', 'firefox')
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial
$adb = Get-Adb
$port = 8765
$url = "http://localhost:$port/page.html"
$packages = @{ chrome = 'com.android.chrome'; firefox = 'org.mozilla.firefox'; samsung = 'com.sec.android.app.sbrowser' }
$service = 'dev.kestrel/dev.kestrel.app.KestrelAccessibilityService'
$heading = 'Kestrel browser check'
# Buttons of the browsers' first-run screens and prompts, in the order in which the script prefers them. Cancel declines
# Android's question whether the browser should become the default one.
$skipButtons = @('Stay signed out', 'Use without an account', 'No thanks', 'No, thanks', 'Not now', 'Cancel', 'Skip',
    'Got it', 'Maybe later', 'Not interested', 'Start browsing', 'Accept & continue', 'Continue')

function Invoke-Shell([string]$Command) { (& $adb -s $Serial shell $Command) -join "`n" }
function Invoke-Hook { (& "$PSScriptRoot\hook.ps1" -Serial $Serial @args) -join "`n" }

function Get-Snapshot {
    $file = Join-Path $env:TEMP "kestrel-spike-s4-$PID.json"
    try {
        Invoke-Hook snapshot -Out $file | Out-Null
        Get-Content $file -Raw | ConvertFrom-Json
    } finally {
        Remove-Item $file -ErrorAction SilentlyContinue
    }
}

function Get-Names($Element) { @($Element.label, $Element.text, $Element.contentDescription, $Element.hint) | Where-Object { $_ } }

function Test-Name($Element, [string]$Name) { (Get-Names $Element) -contains $Name }

# Opens the page and waits until Kestrel reads its heading. Each opening gets its own address, so that a browser does not
# restore what an earlier run typed into the page.
function Open-Page([string]$Package) {
    $address = "$($url)?run=$(Get-Random)"
    Invoke-Shell "am start -W -a android.intent.action.VIEW -d $address $Package" | Out-Null
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Seconds 2
        $snapshot = Get-Snapshot
        if ($snapshot.elements | Where-Object { Test-Name $_ $heading }) { return $snapshot }
        $skip = $null
        foreach ($text in $skipButtons) {
            $skip = $snapshot.elements | Where-Object { $_.clickable -and (Test-Name $_ $text) } | Select-Object -First 1
            if ($skip) { break }
        }
        if ($skip) {
            Invoke-Hook tap -Key $skip.key | Out-Null
        } else {
            # After its first-run screens, a browser may show its start page instead of the page.
            Invoke-Shell "am start -W -a android.intent.action.VIEW -d $address $Package" | Out-Null
        }
    }
    Read-Host "Finish the browser's first-run screens, open $url on the device, then press Enter" | Out-Null
    Get-Snapshot
}

# Each check finds an element on the page by its name, and by what Kestrel should be able to do with it.
$checks = [ordered]@{
    'heading'                           = @{ Name = $heading; Needs = '' }
    'paragraph'                         = @{ Name = 'This page checks what Kestrel can read in a browser.'; Needs = '' }
    'link'                              = @{ Name = 'Next page'; Needs = 'clickable' }
    'button'                            = @{ Name = 'Send message'; Needs = 'clickable' }
    'icon button with aria-label'       = @{ Name = 'Search'; Needs = 'clickable' }
    'text field with a label'           = @{ Name = 'Name'; Needs = 'editable' }
    'text field with a placeholder'     = @{ Name = 'Email address'; Needs = 'editable' }
    'checkbox with a label'             = @{ Name = 'I agree'; Needs = 'checkable' }
    'drop-down list'                    = @{ Name = 'Country'; Needs = 'clickable' }
    'image with alt text'               = @{ Name = 'A kestrel in flight'; Needs = '' }
    'element with role=button'          = @{ Name = 'Custom button'; Needs = 'clickable' }
    'element with only a click handler' = @{ Name = 'Clickable div'; Needs = 'clickable' }
    'text drawn on a canvas'            = @{ Name = 'Canvas text'; Needs = '' }
    'list item below the fold'          = @{ Name = 'Item 30'; Needs = '' }
    'editable div'                      = @{ Name = 'Message box'; Needs = 'editable' }
    'text area'                         = @{ Name = 'Notes'; Needs = 'editable' }
}
$fields = @('Name', 'Email address', 'Message box', 'Notes')

$savedAccessibility = Get-AccessibilitySettings -Serial $Serial
$server = Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin\jwebserver.exe') -PassThru -WindowStyle Hidden `
    -ArgumentList '-p', $port, '-b', '127.0.0.1', '-d', (Join-Path $PSScriptRoot 'spike-s4')
try {
    & $adb -s $Serial reverse "tcp:$port" "tcp:$port" | Out-Null
    Invoke-Shell "settings put secure enabled_accessibility_services $service" | Out-Null
    Invoke-Shell 'settings put secure accessibility_enabled 1' | Out-Null
    for ($i = 0; (Invoke-Hook state) -notlike 'screen=on*'; $i++) {
        if ($i -ge 30) { throw "Kestrel's accessibility service did not connect within 30 s." }
        Start-Sleep -Seconds 1
    }
    foreach ($name in $Browser) {
        $package = $packages[$name]
        if (-not ((Invoke-Shell "pm list packages $package") -match "package:$([regex]::Escape($package))$")) {
            Write-Host "$name ($package) is not installed."
            continue
        }
        Write-Host "Browser $name"
        $snapshot = Open-Page $package
        foreach ($check in $checks.GetEnumerator()) {
            $named = @($snapshot.elements | Where-Object { Test-Name $_ $check.Value.Name })
            $result = if (-not $named) { 'no' }
                elseif (-not $check.Value.Needs -or ($named | Where-Object { $_.($check.Value.Needs) })) { 'yes' }
                else { "named, not $($check.Value.Needs)" }
            '  {0,-36} {1}' -f $check.Key, $result
        }
        foreach ($field in $fields) {
            # Each field gets the page in a new tab, because the keyboard of the previous field can hide the next one.
            # A browser that has just started may not take typed text yet, so the browser keeps running.
            Open-Page $package | Out-Null
            Start-Sleep -Seconds 2
            $snapshot = Get-Snapshot
            $text = "Kestrel $($fields.IndexOf($field) + 1)"
            $target = $snapshot.elements | Where-Object { $_.editable -and (Test-Name $_ $field) } | Select-Object -First 1
            $answer = if (-not $target) { 'no editable field' } else {
                try { Invoke-Hook type -Key $target.key -Text $text } catch { $_.Exception.Message }
            }
            Start-Sleep -Seconds 1
            $after = (Get-Snapshot).elements | Where-Object { $_.editable -and $_.text -eq $text } | Select-Object -First 1
            $shows = if ($after) { "shows the text, named '$($after.label)'" } else { 'does not show the text' }
            '  {0,-36} {1}; the field {2}' -f "type into '$field'", $answer, $shows
        }
        # Content below the screen is not in the snapshot until Kestrel scrolls to it. The page opens again first, so that
        # the keyboard from the typing checks does not cover it.
        Invoke-Shell "am force-stop $package" | Out-Null
        Open-Page $package | Out-Null
        $answers = @()
        while ($answers.Count -lt 8 -and -not ((Get-Snapshot).elements | Where-Object { Test-Name $_ 'Item 30' })) {
            $answers += Invoke-Hook scroll -Direction down
        }
        $found = [bool]((Get-Snapshot).elements | Where-Object { Test-Name $_ 'Item 30' })
        $how = ($answers | Sort-Object -Unique) -join ', '
        '  {0,-36} {1}' -f 'list item after scrolling down', "$(if ($found) { 'yes' } else { 'no' }), after $($answers.Count) scrolls ($how)"
        Invoke-Shell "am force-stop $package" | Out-Null
    }
} finally {
    $null = Restore-AccessibilitySettings -Serial $Serial -Saved $savedAccessibility
    & $adb -s $Serial reverse --remove "tcp:$port" 2>$null | Out-Null
    Stop-Process -Id $server.Id -ErrorAction SilentlyContinue
}
```

- [ ] **Step 5: Install Firefox on the emulator**

The emulator has Chrome. Download Firefox 160 for Android from Mozilla, check that Mozilla signed it, and install it:

```powershell
$firefox = Join-Path $env:TEMP firefox-160.0-x86_64.apk
Invoke-WebRequest -UseBasicParsing https://ftp.mozilla.org/pub/fenix/releases/160.0/android/fenix-160.0-android-x86_64/fenix-160.0.multi.android-x86_64.apk -OutFile $firefox
$buildTools = Get-ChildItem (Join-Path $env:ANDROID_HOME 'build-tools') -Directory | Sort-Object Name -Descending | Select-Object -First 1
& (Join-Path $buildTools.FullName 'apksigner.bat') verify --print-certs $firefox | Select-String 'Signer #1 certificate (DN|SHA-256)'
& (Get-Adb) -s <serial> install -r $firefox
Remove-Item $firefox
```

Expected: the signer is `CN=Release Engineering, OU=Release Engineering, O=Mozilla Corporation, L=Mountain View, ST=California, C=US` with the SHA-256 digest `a78b62a5165b4494b2fead9e76a280d22d937fee6251aece599446b2ea319b04`, and adb prints `Success`. If the signer differs, stop and report it, and do not install the file.

- [ ] **Step 6: Run spike S4 on the emulator**

```powershell
scripts\spike-s4.ps1 -Serial <serial> -Browser chrome, firefox
```

The script taps through the browsers' first-run screens. Expected, for both browsers: `yes` for every kind of element except `text drawn on a canvas` and `list item below the fold`, which say `no`; and for each of the four text fields `done: action; the field shows the text, named '<the field's name>'`. The last line differs: Chrome says `list item after scrolling down       yes, after 4 scrolls (done: action)` (sometimes 5 scrolls, with a last `done: gesture`), and Firefox says `list item after scrolling down       no, after 8 scrolls (done: gesture)`. If anything else differs, stop and report the script's output.

- [ ] **Step 7: Document the spikes and their results**

In `README.md`, replace the section that starts with `### Spike S1`, up to the line `## Licence`, with:

````markdown
### Spikes

These scripts check assumptions of the design on a test device (design spec, section 19). They need Kestrel's debug build, and the results are in section 19.1 of the design spec.

- `scripts\spike-s1.ps1 -Serial $serial` checks whether Kestrel's listening service can start and record when the accessibility service, a notification, the Quick Settings tile or a reboot starts it, also while a call rings on the lock screen, and whether Kestrel can read the location while another app is in front. It uses a stand-in listening service and a tile that only debug builds contain, and it prints whether each step worked, never the audio or the place. While it runs, it changes the accessibility services, the lock-screen setting and the location provider, and it puts them back afterwards. The `boot` scenario reboots the device. On a phone, the `ringing` scenario asks you to call the phone and to end the call.
- `scripts\spike-s4.ps1 -Serial $serial -Browser chrome, firefox` serves `scripts\spike-s4\page.html` from this computer with the JDK's `jwebserver`, opens it in each browser through `adb reverse`, and prints which of the page's elements Kestrel reads and whether Kestrel can type into its text fields. A browser that is not installed is skipped. The script taps through the browsers' first-run screens that it knows, and otherwise asks you to finish them.
- `scripts\spike-s7.ps1 -Serial $serial` pushes a file of random bytes into Kestrel's external files folder with `adb push`, where the model files will live, and checks that Kestrel reads the same bytes. It deletes the file afterwards.
````

In the spec, section 19.1, after the line that starts with "- **S1 on the emulator (plan 1C).**", add these two lines:

````markdown
- **S4 on the emulator (plan 1C).** `scripts\spike-s4.ps1` serves a test page from the PC and compares Kestrel's snapshot with it. In Chrome 145 and Firefox 160, Kestrel read every element of the page that was on the screen, with the right role: the heading and the text, a link, buttons including an icon button named by `aria-label`, text fields named by a label or by a placeholder, a checkbox, a drop-down list, an image's alt text, an element with `role="button"`, an element with only a click handler (as clickable), an editable `div` and a text area. Text drawn on a canvas was missing, as expected, so it needs OCR. Content below the screen is not in the snapshot until Kestrel scrolls to it. Kestrel's `type` set the text of all four kinds of text field through the accessibility action, and each field kept its name afterwards. Chrome takes Kestrel's scroll action. Firefox does not, so Kestrel swipes, and once Firefox hides its toolbar while scrolling, it reports the strip where the toolbar was (about 170 px) as invisible: Kestrel did not see the last item of the page, although it was on the screen. Firefox also reports content under the keyboard as invisible, and a Firefox that had just started refused typed text for a few seconds. Samsung Internet, the messaging apps and the two keyboards are checked on the A71 (plan 1C).
- **S7 on the emulator (plan 1C).** `adb push` copied a 64 MB file into Kestrel's external files folder at 20–30 MB/s, and the file stayed after `adb install -r`. Kestrel read it with the same SHA-256 hash when Kestrel had created the folder, or when the folder that adb created got `chmod 2775`. A folder that `adb shell mkdir` creates belongs to the shell user with the mode 2770, so Kestrel may not read it. `scripts\spike-s7.ps1` repeats the check. The run on the A71 is still open (plan 1C).
````

- [ ] **Step 8: Run all device tests**

```powershell
scripts\connected-test.ps1 -Serial <serial> :core:privacy:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

Expected: `BUILD SUCCESSFUL`, 7 tests in `:core:privacy` and 91 in `:app`.

- [ ] **Step 9: Commit**

```powershell
git add app/src/debug scripts README.md docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "test: add spikes S4 and S7, a browser test page, a check of pushed files and a touch count for S3" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 13: The setup documentation and the final checks

**Files:**
- Modify: `README.md` (setting up Kestrel; the licence texts)

**Interfaces:**
- Consumes: everything above.
- Produces: no code.

The spec asks the README to explain the setup checklist (section 18). While this plan was written, the first boot of a new AVD made like `kestrel-api37`, with software rendering, took 94 s on the owner's computer, well within the 5 minutes that `start-emulator.ps1` waits, so the script's deadline stays (item (13d)).

- [ ] **Step 1: Explain the setup in the README**

In `README.md`, insert this section before the line `## Licence`:

````markdown
## Setting up Kestrel

Open Kestrel from the launcher. The first entry of its settings app is the setup checklist, which says how many of its steps are done (design spec, section 15). The checklist reads each step's state from Android whenever Kestrel comes to the front, so it shows the changes that you make in Android's settings.

1. Install Kestrel with `scripts\install.ps1`. On a Samsung phone, Auto Blocker must be off while USB debugging is on.
2. Copy the voice models with `scripts\push-models.ps1`. This step comes with Kestrel's voice features.
3. Turn on Kestrel's accessibility service. The step's button opens Android's accessibility settings.
4. Allow the microphone and notifications. The step's button asks Android for both permissions. After you refuse twice, Android no longer asks, and the button opens Kestrel's app settings instead.
5. Allow unrestricted battery use. The step's button opens Kestrel's app settings, where the battery setting is. On a Samsung phone, also add Kestrel to "Never sleeping apps" in the battery settings.
6. Optionally, make Kestrel the default digital assistant, for the side key and earbuds. This step comes with Kestrel's voice features.
7. Sign in to an AI provider and choose the models. This step comes with Kestrel's provider settings.
8. Optionally, turn off Voice Access, so that it and Kestrel do not react to the same words. The checklist warns while both are on.

The settings app also has these pages:

- **Safety:** test mode. While it is on, Kestrel sends and shares only to your own self-chats and your own email address, and it makes no calls. Keep it on while you test Kestrel.
- **Activity log** and **Network log:** what Kestrel did, and every request that it sent, encrypted and kept for 7 days. *Export both logs* writes both logs to a JSON file that you choose, which is the only way the logs leave the phone. *Delete history* wipes both logs after you confirm.
- **About:** Kestrel's version, the Android version, and the licence texts.
````

In the section `## Licence`, replace the paragraph that starts with "Kestrel is free software" with:

````markdown
Kestrel is free software under the GNU General Public License, version 3 or (at your option) any later version. See [LICENSE](LICENSE). The licences of the libraries that Kestrel uses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), and the [licences](licences) folder holds the texts of those licences. Kestrel's About page shows all of these texts.
````

- [ ] **Step 2: Run the checks that CI runs**

```powershell
scripts\gradle.ps1 :build-logic:convention:check check kestrelAudit
```

Expected: `BUILD SUCCESSFUL`. The release build's libraries are unchanged since plan 1B, so `THIRD_PARTY_NOTICES.md` needs no other change; check it with `Get-Content app\build\reports\kestrel-audit\dependencies-release.txt | Measure-Object -Line`, which counts the same 111 lines as at the end of plan 1B.

- [ ] **Step 3: Commit, push and watch CI**

```powershell
git add README.md
git commit -m "docs: explain the settings app and the setup checklist in the README" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
git push origin main
Start-Sleep -Seconds 15
gh run list --branch main --limit 1 --json databaseId,headSha --jq '.[0] | "\(.databaseId) \(.headSha)"'
gh run watch --exit-status (gh run list --branch main --limit 1 --json databaseId --jq '.[0].databaseId')
```

The first `gh` command prints the run's ID and commit; the commit must be the one that you pushed (`git rev-parse HEAD`). If it is an older one, wait a little and run both commands again.

Expected: the CI run of the pushed commit ends with `completed` and `success`.

- [ ] **Step 4: Free the memory**

```powershell
scripts\gradle.ps1 --stop
```

---

### Task 14: The Galaxy A71 session with the owner

**Files:**
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 19.1, the A71's results)
- Modify: any file that a failure on the A71 shows to be wrong; such a change gets its own test and its own commit, like the tasks above.

**Interfaces:**
- Consumes: everything above.
- Produces: the A71's results in section 19.1.

This task needs the owner and the Galaxy A71 (model `SM-A715F*`, Android 13). Ask the owner with your question tool when to start, and do every step that touches a messaging app only with the owner's consent. Kestrel never sends a message in this task: it only types into a draft in the owner's own self-chat, and the owner deletes the draft. Test mode stays on (D25).

- [ ] **Step 1: Connect the A71 and install Kestrel**

The owner turns on USB debugging on the A71 and connects it. Then:

```powershell
& (Get-Adb) devices -l
scripts\install.ps1 -Serial <A71 serial>
```

Expected: adb lists the A71 with `model:SM_A715F`, and `install.ps1` prints `Success`. The device guard accepts the A71 and refuses any other phone.

- [ ] **Step 2: Run all device tests on the A71**

```powershell
$before = & (Get-Adb) -s <A71 serial> shell settings list secure
scripts\connected-test.ps1 -Serial <A71 serial> :core:privacy:connectedDebugAndroidTest :app:connectedDebugAndroidTest
Compare-Object $before (& (Get-Adb) -s <A71 serial> shell settings list secure)
```

Expected: 7 tests in `:core:privacy` and 91 in `:app`. These tests run Kestrel's virtual finger (`uinput`) and the zoom test `numbersFollowAZoom` on Android 13 for the first time. For each failure, find out whether the code or the test assumes Android 17 behaviour, fix it with a test, and run the tests on both devices again. `Compare-Object` shows the secure settings that differ after the tests. `connected-test.ps1` puts back every setting whose name contains `accessibility` or `touch_exploration`, so none of those may appear. If a setting appears that the tests changed, such as a Samsung shortcut setting with another name, put it back by hand, add its name to the filter in `Get-AccessibilitySettings` with a comment, and run this step again. Settings that change by themselves, such as time stamps, can be ignored.

- [ ] **Step 3: Spike S3 with a real finger**

```powershell
$saved = Get-AccessibilitySettings -Serial <A71 serial>
& (Get-Adb) -s <A71 serial> shell settings put secure enabled_accessibility_services dev.kestrel/dev.kestrel.app.KestrelAccessibilityService
& (Get-Adb) -s <A71 serial> shell am start -W -a android.settings.SETTINGS
scripts\hook.ps1 -Serial <A71 serial> watch -Seconds 10
```

While the command runs, the owner taps the screen three times. Expected: `owner touches in 10 s: 3`. Then the owner keeps one finger on an empty part of the screen while this runs:

```powershell
scripts\hook.ps1 -Serial <A71 serial> tap -X 540 -Y 1200 -Long
```

Write down Kestrel's answer. On Android 13, Android is expected to cancel Kestrel's gesture, so the answer is expected to be a failure (spec section 19.1, S3, and B35). Then put the A71's accessibility settings back; the command prints `True` when everything is back:

```powershell
Restore-AccessibilitySettings -Serial <A71 serial> -Saved $saved
```

- [ ] **Step 4: Spikes S1 and S7 on the A71**

```powershell
scripts\spike-s1.ps1 -Serial <A71 serial>
scripts\spike-s7.ps1 -Serial <A71 serial>
```

The ringing scenario asks the owner to call the A71 from another phone, and to end the call when the script says so. If the A71 has a PIN, the script asks the owner to unlock it afterwards. The location scenario replaces the A71's fused location provider with a test provider at a made-up place while it runs.

- [ ] **Step 5: Spike S4 on the A71**

Install Firefox for the A71's processor, `fenix-160.0.multi.android-arm64-v8a.apk` from `https://ftp.mozilla.org/pub/fenix/releases/160.0/android/fenix-160.0-android-arm64-v8a/`, only if the owner agrees, after the same signature check as in Task 12, Step 5. Then:

```powershell
scripts\spike-s4.ps1 -Serial <A71 serial> -Browser chrome, firefox, samsung
```

Then, with the owner's consent, check set-text in the messaging apps that the A71 has (WhatsApp, Viber, Telegram, Gmail), first with Samsung Keyboard and then with Gboard. The owner opens the self-chat (Telegram's "Saved Messages", WhatsApp's "Message yourself", Viber's "My Notes") or a new email draft, and taps the message field. Then:

```powershell
scripts\hook.ps1 -Serial <A71 serial> snapshot -Out $env:TEMP\screen.json
# find the message field's key in the file: an element with "editable": true
scripts\hook.ps1 -Serial <A71 serial> type -Key '<the field's key>' -Text 'Kestrel set-text check'
Remove-Item $env:TEMP\screen.json
```

Write down the answer for each app and keyboard: `done: action` (set-text), `done: input_method` (Kestrel's input method) or an error. The owner deletes the draft. Switch keyboards with `ime list -s` and `ime set <id>`, and switch back to the owner's keyboard at the end.

- [ ] **Step 6: Check the Voice Access warning, if the A71 has Voice Access**

If Voice Access (`com.google.android.apps.accessibility.voiceaccess`) is installed, the owner turns it on next to Kestrel's service, and the setup checklist of Kestrel's settings app shows "Needs attention" with the warning for step 8. The owner turns Voice Access off again, unless they want it on.

- [ ] **Step 7: Write the results into the spec**

In section 19.1, extend the S1, S3, S4 and S7 paragraphs with the A71's results, in the same style as the emulator's results: what worked, what did not, and what that means for the design. If a result contradicts the design, say so and name the fallback of section 19 that the design now uses. Commit, push and watch CI as in Task 13, Step 3:

```powershell
git add docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "docs: record the A71 results of spikes S1, S3, S4 and S7" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

- [ ] **Step 8: Free the memory**

```powershell
scripts\gradle.ps1 --stop
```

