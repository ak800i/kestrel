# Voice 2C-4: Feedback and Failures Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every way in which listening stops or cannot start visible: say why in plain words on the feedback bar and in a notification, offer the one action that helps ("Start listening", or Android's accessibility settings), and stop the restore after a restart from bringing back a Kestrel that the owner stopped or that crashed soon after it came back.

**Architecture:** `Listening` turns off with a `StopReason` instead of a fixed text; a small `ListeningConditions` interface tells it whether the microphone is allowed, whether the battery use is Restricted and whether Android took the foreground from the service, and `StopNotices` posts the reason as a notification whose "Start listening" starts `ListeningService` with a new action that `Listening.onStartAction` adopts. The listening notification's own actions become broadcasts to a non-exported receiver. The setup checklist tells "on but not running" and "Restricted" apart, and a notification follows Kestrel's accessibility service when it goes off. The restore reads the newest exit record through `ExitRecords`, and the stored mode keeps the times of its write and of the last restore. The Orchestrator shows "Yes?" while it waits for a task, says why it cannot open the settings, and counts only real empty transcripts for a hint, because `VoiceSession` sends `Event.NothingHeard` for dropped and failed utterances.

**Tech Stack:** Kotlin 2.4.21, kotlinx.coroutines 1.11.0 (kotlinx-coroutines-test for the JVM tests), Android's `NotificationManager`, `PendingIntent`, `ActivityManager` (`isBackgroundRestricted()`, `getHistoricalProcessExitReasons()`) and `Service.getForegroundServiceType()` at compileSdk 37, Jetpack Compose for the settings pages, JUnit 4, Pester 6.2.0, device tests on the `kestrel-api37` emulator through `scripts\voice-test.ps1` and `scripts\connected-test.ps1`.

## Global Constraints

- Package and application ID `dev.kestrel`; licence GPL-3.0-or-later; no new dependency that ships in the app.
- `minSdk 33`, `targetSdk 37`, `compileSdk 37`.
- No dependency-injection framework; `AppGraph` wires by hand.
- Never log audio, transcripts, replies, boost words, app labels, screen text, exception messages or the description of an exit record (section 13.5); `toString` of anything that carries the owner's words prints only lengths or counts.
- Test only on `kestrel-api37` (emulator-5554), and check it in the same command as each device command; never on the S23+ or the AVD `zoran`; never send commands to the A71. Gradle only through `scripts\gradle.ps1` (stopped with `--stop` at the end of each task), device tests only through `scripts\voice-test.ps1` or `scripts\connected-test.ps1` with `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`, installs only through `scripts\install.ps1`. Device tests and scripts restore every setting and app op they change (`TAKE_AUDIO_FOCUS` to `foreground`, `RUN_ANY_IN_BACKGROUND` to `allow`) and keep the APKs installed.
- Scripts run on Windows PowerShell 5.1 and PowerShell 7; `scripts\test-scripts.ps1` passes after every script change.
- The repository uses LF line endings.
- The plan files are frozen once committed; a later difference goes into `.superpowers/sdd/progress.md`.

## Before you start

- Plan 2C-3 is complete at `75b21bc`. This plan was written against that commit. The current totals: `:app` JVM 150 tests (`ListeningTest` 63, `RulesTest` 38, `OrchestratorTest` 9, `RequestsTest` 24, `SetupChecklistTest` 7, `BoostWordSourceTest` 6, `LicenceParagraphsTest` 3), `:core:voice` JVM 116, voice device tests 63 (`ListeningServiceTest` 3, `VoiceSessionFailuresTest` 7), the other device tests of `:app` 99 (among them `SettingsAppTest` 15, `SetupStatusReaderTest` 3 and `MainActivityTest` 4), Pester 91 per shell. This plan changes no file of `:core:voice`, so `:core:voice` stays at 116.
- Before each task, check that every text that the task replaces exists, and exists once, in its file, and compare the counts with the ones that the earlier tasks left: before Task 1, the ones above; before Task 2, `:app` 155 (`ListeningTest` 67, `RulesTest` 39) and 63 voice device tests; before Task 3, `:app` 160 (`ListeningTest` 72) and 64; before Task 4, `:app` 160 and 65; before Task 5, `:app` 165 (`ListeningTest` 75, `SetupChecklistTest` 9), 65, and 103 other device tests (`SettingsAppTest` 17, `SetupStatusReaderTest` 4, `AccessibilityNoticeTest` 1); before Task 6, `:app` 177 (`ListeningTest` 87, `RulesTest` 39) and 65; before Task 7, `:app` 180 (`RulesTest` 42), 65 and Pester 91. `:core:voice` stays at 116. If a text is missing or a count differs, stop and ask the controller.
- What the listening classes do now (`.superpowers/sdd/progress.md`, plans 2C-1 to 2C-3): `Listening` (in `AppGraph.listening`) owns the Orchestrator on one single-threaded dispatcher (`thread`). When the mode leaves Off, `follow()` calls `start()`, which asks Android for `ListeningService` at once; once the service reports (`onForeground`), `open()` opens a `VoiceSession` under the `lifecycle` lock. `Effect.StopService` runs `shutdown()`, which closes the session and then stops the service unless a newer start was asked for. A failure turns Kestrel off through `turnOff(what, error, text)`, which shows the text in place of "Off" on the feedback bar; the bar exists only while Kestrel's accessibility service runs. The notification's Pause, Resume and Stop start the service again with an action. When the accessibility service first connects in a process in which Kestrel has not been on, the restore stores Off and sends `Event.Start` in the stored mode. `VoiceSession` turns a dropped or failed utterance into `Event.Heard("", named = false)`.
- Facts from prototype P2 (`.superpowers/sdd/research/p2-prototype-2c3.md`) that this plan relies on: a broadcast from a notification action leaves about 9 s for the microphone, while `PendingIntent.getForegroundService` gets it; with battery use "Restricted" and without Kestrel's accessibility service, Android takes the foreground from a service that the tile started about 30 s after the Quick Settings panel closes, silences the microphone 5 s later and stops the service 60 s after that, without an exception or callback; `TAKE_AUDIO_FOCUS ignore` makes replies `REFUSED`, and only `foreground` gives the focus back (`default` still refuses); `RUN_ANY_IN_BACKGROUND` must go back to `allow`, because `default` still counts as restricted; a force stop and "Stop app" leave `REASON_USER_REQUESTED`, a crash `REASON_CRASH`, a reboot no record of its own, and the records' times are wall-clock milliseconds; turning the accessibility service off runs `onUnbind`, and listening goes on.
- Run Gradle only through `scripts\gradle.ps1`, and stop it with `scripts\gradle.ps1 --stop` at the end of each task.
- Run the voice device tests only through `scripts\voice-test.ps1 -Serial emulator-5554`; it installs Kestrel, copies the models and keeps both installed. It runs only the package `dev.kestrel.app.voice`. Tasks 3, 4 and 7 also run device test classes through `scripts\connected-test.ps1`, always with `'-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'`: Task 3 one voice test before its change, Task 4 `SettingsAppTest`, `SetupStatusReaderTest`, `AccessibilityNoticeTest` and `MainActivityTest`, and Task 7 every device test of `:app` outside the voice package. Task 7 runs `scripts\listen-check.ps1` with `pwsh`, because it needs PowerShell 7.3, as `scripts\hook.ps1` does.
- Every device command of this plan starts with a check of the emulator, in the same command: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' };` and then the command itself. If the check throws, or no emulator runs, start the emulator with `scripts\start-emulator.ps1`, check that the serial that it prints belongs to `kestrel-api37`, and use that serial in place of `emulator-5554`. `voice-test.ps1`, `connected-test.ps1` and `listen-check.ps1` also refuse other devices themselves (`Assert-TestDevice`).
- Before each task, run `git status --short`. If a file that the task changes (its **Files** list) already shows changes that are not the task's own, stop and ask the controller, because the task's `git add` would commit them too.
- Every commit message ends with the line `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.

## Not in this plan

Plan 2D: the A71 session (Samsung's battery rules and "Never sleeping apps", which Kestrel cannot read; One UI's notification shade, tiles and `uiautomator` dumps for `listen-check.ps1`; every start path, the start from a notification action and the exit records on Android 13), the player's focus listener, calls, earbuds and the assistant role. On Android 13, an update of the app also ends its process with `REASON_USER_REQUESTED` (the reference of `REASON_PACKAGE_STATE_CHANGE` says so), so on the A71 Kestrel stays off after every `scripts\install.ps1` that ran while it listened. Plan 2D handles it, for example by counting such a record as an update when `PackageManager.getPackageInfo(packageName, 0).lastUpdateTime` lies within a few seconds of the record's time. Plan 2D also checks a start while a session closes on a device (final review of plan 2C-3, "For plan 2C-4", item 8, and "For plan 2D", item 4), and that the restore after a reboot finds the models (item 9).

A notification for every transient problem: a busy microphone stays on the feedback bar only, and listening resumes on its own. A microphone that fails while an answer to "Yes?" is held loses the answer without a word, and the wait for the task runs on for up to 35 s (final review of plan 2C-3, "For plan 2C-4", item 4); the microphone starts again after 1 s, the owner can say the task again within the wait, and plan 2D measures whether this happens at all on the A71.

An age limit for the restore (the final review of plan 2C-2 asked to consider one): a phone that was off for days should still come back listening after its reboot, and the exit records cannot tell a phone that was off from an accessibility service that was off, so the restore has no limit. A force stop, which also turns the accessibility service off, keeps Kestrel off through its exit record, unless the phone restarts soon after: P2 found that a reboot can lose the records of the 30 minutes before it.

A device check that the problems notification goes once Kestrel listens again: the JVM tests cover the call, and one more dump of the shade in `listen-check.ps1` would only repeat them.

## Threads

| Call | Thread | Notes |
|---|---|---|
| `Listening.onStartAction(instance)` | The main thread (`ListeningService.onStartCommand`); it launches on the controller's thread | Sets `STARTING` and `requested` together and clears `offReason` when the service state is `STOPPED`, as `start()` does, sends `Event.Start()`, then adopts the instance as `foreground()` does, without waiting for the `lifecycle` lock. A shutdown whose stop runs on the main thread after the action's `onStartCommand` but before `onStartAction`'s coroutine can still stop that service; the owner then sees "Android stopped Kestrel's listening" and taps again. The window is the controller's queue right after a close |
| `StopNotices.show(reason)` and `clear()` | The controller's thread | `NotificationManager` may be called from any thread; `show` posts nothing without `POST_NOTIFICATIONS`. `Listening` posts after it has sent the turn-off, and reports a failure of either call by its classes |
| `ProblemNotifications.accessibilityServiceOff()` and `accessibilityServiceOn()` | The main thread (`KestrelAccessibilityService.onUnbind` and `onServiceConnected`) | |
| `ListeningConditions.microphoneAllowed()`, `backgroundRestricted()` and `demoted(service)` | The controller's thread | Binder calls into Android's services, which may run on any thread |
| `NotificationActionReceiver.onReceive` | The main thread | Only sends `Event.Pause`, `Event.Resume` or `Event.TurnOff` |
| `ExitRecords.newest()` | The restore's coroutine on the controller's thread; `AndroidExitRecords` switches to `Dispatchers.IO` | Read after the stored mode and before the restore's write of Off; `wasOn` and the mode are checked again after it |
| `Listening.restoredAt` | Written on the controller's thread (the restore and `noteMode`), read by the mode's writer on `scope` | `@Volatile` |
| `ModeMemory.write(mode, writtenAt, restoredAt)` | The mode's writer on `scope`, and the restore on the controller's thread | As before: the restore writes before anything else can write |

## File structure

| File | Responsibility |
|---|---|
| `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt` | `ListeningTexts`, `StopReason`, `ListeningConditions`, `StopNotices`, `StoredMode`; the reasons by cause, the problems notification, `onStartAction`, the battery check and the restore with exit records |
| `app/src/main/kotlin/dev/kestrel/app/listen/ProblemNotifications.kt` | New: the problems notification with "Start listening" (Task 2), and the notification about the accessibility service (Task 4) |
| `app/src/main/kotlin/dev/kestrel/app/listen/NotificationActionReceiver.kt` | New: the listening notification's Pause, Resume and Stop as broadcasts |
| `app/src/main/kotlin/dev/kestrel/app/listen/ExitRecords.kt` | New: `ExitReason`, `ExitRecord`, `ExitRecords` and `AndroidExitRecords` |
| `app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt` | `ACTION_START`, `startIntent`, `openKestrel`; the actions as broadcasts |
| `app/src/main/kotlin/dev/kestrel/app/listen/ListeningSetting.kt` | The stored mode with `writtenAt` and `restoredAt` |
| `app/src/main/kotlin/dev/kestrel/app/listen/VoiceSession.kt` | `Event.NothingHeard` for dropped and failed utterances |
| `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt` | `Feedback.AwaitingTask`, `Event.SettingsUnavailable`, `Reply.SettingsNeedService`, `Event.NothingHeard`, `Feedback.NoWords` and the count of empty transcripts |
| `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` | Wires the conditions, the notifications, the exit records and the clock; `openSettings` returns false without the accessibility service |
| `app/src/main/kotlin/dev/kestrel/app/KestrelAccessibilityService.kt` | Posts and removes the notification about the accessibility service |
| `app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt`, `SetupStatusReader.kt`, `ChecklistPage.kt`, `SettingsApp.kt` | The service "on but not running", the Restricted battery use, the Samsung text |
| `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml` | The receiver; the new texts |
| `scripts/lib/Kestrel.ps1`, `scripts/listen-check.ps1`, `scripts/voice-test.ps1` | `Get-UiNodeCentre`; the notification check; the refused-reply test on emulators only |
| `docs/superpowers/specs/2026-11-12-kestrel-design.md`, `README.md` | Sections 8.5, 8.6, 8.9, 13.5, 14.6, 15 and 19.1; the README's listening, setup and script paragraphs |

Tests: `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, `app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt` (JVM, changed); `app/src/androidTest/kotlin/dev/kestrel/app/voice/ListeningServiceTest.kt` and `VoiceSessionFailuresTest.kt` (voice device tests, changed); `app/src/androidTest/kotlin/dev/kestrel/app/voice/RefusedReplyTest.kt` (voice device test, new); `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt` and `SetupStatusReaderTest.kt` (device, changed); `app/src/androidTest/kotlin/dev/kestrel/app/AccessibilityNoticeTest.kt` (device, new); `scripts/tests/Kestrel.Tests.ps1` (Pester, changed).

---

### Task 1: Why Kestrel stopped, by cause

**Files:**
- Modify: `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt` (`Event`, `Reply`, `Feedback`, `Rules.next`, `spotted`, `askForTask`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt` (`ListeningTexts`, new `ListeningConditions` and `StopReason`, the class's KDoc and constructor, `onForegroundFailed`, `follow`, `start`, `destroyed`, `open`, `restartMicrophone`, `turnOff`, new `stopBecause`, `openSettingsOrSayWhy`, `say`, `text`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (imports, `openSettings`, `conditions`)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 8.9 and 14.6), `README.md` (the paragraph on listening)
- Test: `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`

**Interfaces:**
- Consumes: `Listening(scope, thread, sessions, services, bar, report, openSettings, memory, microphones)`, `ListeningTexts`, `Rules.next(state, event)`, `Effect.Show`, `Effect.Say`, `Reply.Yes`, `Reply.Text`, `AppGraph.screen`.
- Produces: `internal interface ListeningConditions { fun microphoneAllowed(): Boolean }`; `internal enum class StopReason(val text: String)` with `BACKGROUND_START`, `MICROPHONE_PERMISSION`, `MODELS_MISSING`, `ENGINES_FAILED`, `MICROPHONE_FAILED`, `STOPPED_BY_ANDROID`; the constructor parameters `openSettings: () -> Boolean` and `conditions: ListeningConditions`; the texts `ListeningTexts.BACKGROUND_START`, `MICROPHONE_PERMISSION`, `ENGINES_FAILED`, `STOPPED_BY_ANDROID` and `SETTINGS_NEED_SERVICE` (and no `CANNOT_START`); `Feedback.AwaitingTask`, `Event.SettingsUnavailable`, `Reply.SettingsNeedService`; the private functions `turnOff(what: String, error: Exception, reason: StopReason)` and `stopBecause(reason: StopReason)`. Tasks 2, 4 and 5 add entries and parameters to these.

Final review of plan 2C-1, item 3, and the final review of plan 2C-2, M-3. Today every start that fails says "Kestrel could not start listening. Start it from the notification or the tile.", although no notification exists while Kestrel is off, and nothing says why. Now `Listening.turnOff` gets a `StopReason`, which the place of the failure decides: a refused start from the background (a failure of `startForegroundService()` or a `SecurityException` from `startForeground()` while the microphone is allowed, because the start then came too late after a tap), a missing microphone permission (a `SecurityException` while the microphone is not allowed: Android 14 and later throw it at `startForeground()`, and on the A71's Android 13 the microphone does not start), missing models, any other failure while the session opens (also an `Error`, such as a native library that does not load), a failed microphone, and a stop by Android (`onServiceDestroyed`), which before turned Kestrel off without a reason. `Listening` cannot name `ForegroundServiceStartNotAllowedException` itself, because the JVM tests cannot build Android's classes; on API 33 and later it is the only failure of `startForegroundService()` besides a `SecurityException`, so every failure that is not a `SecurityException` without the permission counts as a refused start. Every reason goes through `stopBecause`, which sends `Event.TurnOff`, so `shutdown()` still cancels the microphone's restart (final review of plan 2C-3, "For plan 2C-4", item 3). While a turn-off for an earlier reason is on its way, that reason stays, because the earlier failure caused the later one, as when Android destroys a service whose newer start failed. "Mic busy" becomes "Microphone blocked", which also fits the privacy toggle and Android's own silencing. The bar shows "Yes?" at the start of every wait for a task (section 8.9: "any question that Kestrel is asking"): after "Kestrel" on its own, and also after an interruption with "Kestrel", where Kestrel says no "Yes?" but waits for the task all the same (item 5 of the same list); a refused or failed "Yes?" is not shown a second time. "Kestrel, open settings" while the accessibility service is off: Android blocks the activity start without an exception, so `openSettings` returns false when `AppGraph.screen` is null, and the Orchestrator says why with a spoken reply, because the voice works without the service.

- [ ] **Step 1: Write the failing tests**

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, in the test `theNameAloneAsksAndTheNextUtteranceIsTheTask`, replace

```kotlin
        assertEquals(listOf(Effect.Show(Feedback.Heard("Kestrel?")), Effect.WaitForTask(), Effect.Say(Reply.Yes)), asked.effects)
```

with

```kotlin
        // The bar shows "Yes?" at the start of the wait for the task (section 8.9).
        assertEquals(
            listOf(
                Effect.Show(Feedback.Heard("Kestrel?")),
                Effect.Show(Feedback.AwaitingTask),
                Effect.WaitForTask(),
                Effect.Say(Reply.Yes),
            ),
            asked.effects,
        )
```

In the test `theNameWhileKestrelSpeaksEndsTheSpeechAndWaitsForARequest`, replace

```kotlin
        assertEquals(listOf(Effect.StopSaying, Effect.WaitForTask()), step.effects)
```

with

```kotlin
        assertEquals(listOf(Effect.StopSaying, Effect.Show(Feedback.AwaitingTask), Effect.WaitForTask()), step.effects)
```

In the test `whileAwaitingATaskKestrelsOwnRequestsCount`, replace

```kotlin
        assertEquals(listOf(shown("Kestrel?"), Effect.WaitForTask(), Effect.Say(Reply.Yes)), again.effects)
```

with

```kotlin
        assertEquals(
            listOf(shown("Kestrel?"), Effect.Show(Feedback.AwaitingTask), Effect.WaitForTask(), Effect.Say(Reply.Yes)),
            again.effects,
        )
```

Then add this test right after the test `kestrelOpenSettingsOpensThemWhileListeningOrPaused`, by replacing

```kotlin
        // Nothing may start while the screen is off (section 14.4).
        val dark = listening.copy(screenOn = false)
        assertEquals(Step(dark), Rules.next(dark, Event.Heard("Kestrel, open settings.", named = true)))
    }
```

with

```kotlin
        // Nothing may start while the screen is off (section 14.4).
        val dark = listening.copy(screenOn = false)
        assertEquals(Step(dark), Rules.next(dark, Event.Heard("Kestrel, open settings.", named = true)))
    }

    @Test
    fun kestrelSaysWhyItCannotOpenItsSettings() {
        val step = Rules.next(listening, Event.SettingsUnavailable)

        assertEquals(listOf(Effect.Say(Reply.SettingsNeedService)), step.effects)
        assertEquals(1, step.state.speeches)
        // A late report changes nothing once Kestrel is off.
        assertEquals(Step(State()), Rules.next(State(), Event.SettingsUnavailable))
    }
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace

```kotlin
    private var settingsFailure: Exception? = null
```

with

```kotlin
    private var settingsFailure: Exception? = null
    private var settingsCanOpen = true
    private val conditions = FakeConditions()
```

In the test `kestrelSaysYesAndTheEndOfTheReplyTurnsRecognitionOnAgain`, replace

```kotlin
        // A reply that was spoken is not shown.
        assertEquals(listOf(ListeningTexts.LISTENING, "Kestrel"), shown)
```

with

```kotlin
        // The bar shows "Yes?" at the start of the wait for the task (section 8.9), and not again when it was spoken.
        assertEquals(listOf(ListeningTexts.LISTENING, "Kestrel", ListeningTexts.YES), shown)
```

In the test `aServiceThatCannotStartTurnsKestrelOffAndSaysWhy`, replace

```kotlin
        // The reason takes the place of "Off", which would replace it at once.
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.CANNOT_START), shown)
```

with

```kotlin
        // The reason takes the place of "Off", which would replace it at once. Android refused a start in the
        // background.
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.BACKGROUND_START), shown)
```

Add these four tests right after the test `settingsThatCannotOpenAreReportedAndShown`, by replacing

```kotlin
        assertEquals("Opening the settings", reports.single().first)
        assertEquals(ListeningTexts.CANNOT_OPEN_SETTINGS, shown.last())
        assertEquals(Mode.LISTENING, listening.state.value.mode)
    }
```

with

```kotlin
        assertEquals("Opening the settings", reports.single().first)
        assertEquals(ListeningTexts.CANNOT_OPEN_SETTINGS, shown.last())
        assertEquals(Mode.LISTENING, listening.state.value.mode)
    }

    @Test
    fun kestrelOpenSettingsWithoutTheAccessibilityServiceSaysWhy() = runTest {
        // Android blocks the start from the background without an exception, and the bar does not exist.
        settingsCanOpen = false
        val listening = listeningWithSession()
        log.clear()

        listening.send(Event.Heard("Kestrel, open settings.", named = true))
        runCurrent()

        // Recognition is off while Kestrel says why.
        assertEquals(listOf("open settings", "recognition NONE"), log)
        assertEquals(listOf(ListeningTexts.SETTINGS_NEED_SERVICE), sessions.opened.single().said)
        assertTrue(reports.isEmpty())
    }

    @Test
    fun aServiceThatCannotEnterTheForegroundWithoutTheMicrophonePermissionAsksForIt() = runTest {
        // Android 14 and later refuse a microphone service without the permission at startForeground().
        conditions.microphone = false
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()

        listening.onForegroundFailed(SecurityException("Starting FGS with type microphone requires permissions"))
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.MICROPHONE_PERMISSION), shown)
    }

    @Test
    fun aMicrophoneThatDoesNotStartWithoutThePermissionAsksForIt() = runTest {
        // Android 13, as on the A71, starts the service, and the microphone fails instead.
        conditions.microphone = false
        sessions.microphoneFailure = SecurityException("Kestrel may not record audio")
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()

        listening.onForeground(service)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.MICROPHONE_PERMISSION), shown)
    }

    @Test
    fun speechEnginesThatCannotLoadSaySo() = runTest {
        sessions.failure = IllegalStateException("The recogniser could not load")
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()

        listening.onForeground(service)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.ENGINES_FAILED), shown)
        assertEquals("Opening the voice session", reports.single().first)
    }
```

In the test `aServiceThatCannotEnterTheForegroundTurnsKestrelOffAndSaysWhy`, replace

```kotlin
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.CANNOT_START), shown)
        assertTrue(reports.single().second is SecurityException)
```

with

```kotlin
        // With the microphone allowed, the start came too late after a tap (prototype P2), as a start in the
        // background.
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.BACKGROUND_START), shown)
        assertTrue(reports.single().second is SecurityException)
```

In the test `anUnexpectedEndOfTheServiceTurnsKestrelOff`, replace

```kotlin
        listening.onServiceDestroyed(service)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf("close", "closed", "stop"), log)
    }
```

with

```kotlin
        listening.onServiceDestroyed(service)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf("close", "closed", "stop"), log)
        assertEquals(ListeningTexts.STOPPED_BY_ANDROID, shown.last())
    }
```

In the test `aStartThatAndroidRefusesWhileASessionClosesStopsTheServiceAfterTheClose`, replace

```kotlin
        assertEquals(ListeningTexts.CANNOT_START, shown.last())
        assertEquals(listOf("Starting the listening service"), reports.map { it.first })
```

with

```kotlin
        assertEquals(ListeningTexts.BACKGROUND_START, shown.last())
        assertEquals(listOf("Starting the listening service"), reports.map { it.first })
```

In the test `aRunningServiceThatCannotReenterTheForegroundTurnsKestrelOffAndSaysWhy`, replace

```kotlin
        assertEquals(ListeningTexts.CANNOT_START, shown.last())
        assertEquals(listOf("close", "closed", "stop"), log)
```

with

```kotlin
        assertEquals(ListeningTexts.BACKGROUND_START, shown.last())
        assertEquals(listOf("close", "closed", "stop"), log)
```

In the same test, let Android destroy the service before the Orchestrator has turned Kestrel off, by replacing

```kotlin
        // A notification action's start failed; the service stops itself, and Android destroys it.
        listening.onForegroundFailed(SecurityException("No microphone permission"))
        runCurrent()
        listening.onServiceDestroyed(service)
        runCurrent()
```

with

```kotlin
        // A newer start, such as "Start listening", failed; the service stops itself, and Android destroys it before
        // the Orchestrator has turned Kestrel off. The first reason stays.
        listening.onForegroundFailed(SecurityException("No microphone permission"))
        listening.onServiceDestroyed(service)
        runCurrent()
```

In the test `anErrorWhileTheSessionOpensTurnsKestrelOff`, replace

```kotlin
        assertEquals(listOf("start", "open", "stop"), log)
        assertTrue(errors.single() is UnsatisfiedLinkError)
```

with

```kotlin
        assertEquals(listOf("start", "open", "stop"), log)
        assertTrue(errors.single() is UnsatisfiedLinkError)
        // Section 14.6: the native library of the engines did not load.
        assertEquals(ListeningTexts.ENGINES_FAILED, shown.last())
```

In the test `aReplyThatCannotBeSpokenIsShownOnTheBar`, replace

```kotlin
        session.replies[0].complete(Spoke.REFUSED)
        runCurrent()
        listening.send(Event.Heard("Kestrel", named = true))
        runCurrent()
        session.replies[1].completeExceptionally(IllegalStateException("The voice failed"))
        runCurrent()

        assertEquals(listOf(ListeningTexts.NO_TASKS, "Kestrel", ListeningTexts.YES), shown)
        assertEquals("A reply", reports.single().first)
```

with

```kotlin
        session.replies[0].complete(Spoke.REFUSED)
        runCurrent()
        listening.send(Event.Heard("Kestrel, open the settings.", named = true))
        runCurrent()
        session.replies[1].completeExceptionally(IllegalStateException("The voice failed"))
        runCurrent()
        listening.send(Event.Heard("Kestrel", named = true))
        runCurrent()
        session.replies[2].completeExceptionally(IllegalStateException("The voice failed"))
        runCurrent()

        // A refused and a failed reply go to the bar (section 14.6). "Yes?" shows at the start of the wait for the
        // task, and a failed "Yes?" is not shown a second time.
        assertEquals(
            listOf(ListeningTexts.NO_TASKS, "Kestrel, open the settings.", ListeningTexts.NO_TASKS, "Kestrel", ListeningTexts.YES),
            shown,
        )
        assertEquals(listOf("A reply", "A reply"), reports.map { it.first })
```

Since "Yes?" shows at the start of the wait, the "Yes?" that this test and the next one found on the bar no longer comes from `say`; the failed task reply here, and the task reply without a session in the next test, keep both of `say`'s branches covered.

In the test `aReplyWithoutASessionIsEndedByTheOrchestrator`, replace

```kotlin
        // Section 14.6: without a voice, the reply's text goes to the bar.
        assertEquals(ListeningTexts.YES, shown.last())
```

with

```kotlin
        // The bar shows "Yes?" at the start of the wait for the task; a "Yes?" that cannot be spoken is not shown
        // again.
        assertEquals(listOf(ListeningTexts.LISTENING, "Kestrel", ListeningTexts.YES), shown)

        // The answer is a task, and its reply goes to the bar, because there is no voice (section 14.6).
        listening.send(Event.Heard("Open the settings.", named = false))
        runCurrent()
        assertEquals(ListeningTexts.NO_TASKS, shown.last())
```

In the helper `listening`, replace

```kotlin
        openSettings = {
            log += "open settings"
            settingsFailure?.let { throw it }
        },
        memory = memory,
        microphones = { microphone },
    )
```

with

```kotlin
        openSettings = {
            log += "open settings"
            settingsFailure?.let { throw it }
            settingsCanOpen
        },
        memory = memory,
        conditions = conditions,
        microphones = { microphone },
    )
```

Add the fake of the conditions right before the class `FakeMicrophone`, by replacing

```kotlin
    private class FakeMicrophone : Microphone {
```

with

```kotlin
    private class FakeConditions : ListeningConditions {
        var microphone = true

        override fun microphoneAllowed() = microphone
    }

    private class FakeMicrophone : Microphone {
```

In the test `anErrorWhileStoringTheModeIsReportedAndLaterModesAreStillStored`, which builds its own `Listening`, replace

```kotlin
            openSettings = {},
```

with

```kotlin
            openSettings = { true },
            conditions = conditions,
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.ListeningTest --tests dev.kestrel.app.orchestrator.RulesTest`
Expected: `BUILD FAILED` in `:app:compileDebugUnitTestKotlin`, with `Unresolved reference` errors for `ListeningConditions`, `BACKGROUND_START`, `MICROPHONE_PERMISSION`, `ENGINES_FAILED`, `STOPPED_BY_ANDROID`, `SETTINGS_NEED_SERVICE`, `AwaitingTask`, `SettingsUnavailable` and `SettingsNeedService`, and an error that `Listening` has no parameter `conditions`.

- [ ] **Step 3: Show "Yes?", and say why the settings cannot open, in the Orchestrator**

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace

```kotlin
    /** Wait number [wait] for a task after "Yes?" has lasted long enough. */
    data class WaitEnded(val wait: Int) : Event
}
```

with

```kotlin
    /** Wait number [wait] for a task after "Yes?" has lasted long enough. */
    data class WaitEnded(val wait: Int) : Event

    /**
     * Kestrel could not open its settings app, because Android lets it start an activity from the background only while
     * its accessibility service is on. Kestrel says so, because the feedback bar does not exist then (section 14).
     */
    data object SettingsUnavailable : Event
}
```

Replace

```kotlin
    /** "Yes?", after "Kestrel" on its own. */
    data object Yes : Reply
```

with

```kotlin
    /** "Yes?", after "Kestrel" on its own. */
    data object Yes : Reply

    /** Why Kestrel did not open its settings: its accessibility service is off (section 14). */
    data object SettingsNeedService : Reply
```

Replace

```kotlin
    data object MicBusy : Feedback
}
```

with

```kotlin
    data object MicBusy : Feedback

    /**
     * Kestrel waits for the task, after "Yes?" or after an interruption with "Kestrel": the question that Kestrel asks
     * (section 8.9).
     */
    data object AwaitingTask : Feedback
}
```

Replace

```kotlin
        is Event.WaitEnded ->
            if (state.work == Work.AWAITING_TASK && event.wait == state.wait) Step(state.copy(work = Work.IDLE)) else Step(state)
    }
```

with

```kotlin
        is Event.WaitEnded ->
            if (state.work == Work.AWAITING_TASK && event.wait == state.wait) Step(state.copy(work = Work.IDLE)) else Step(state)
        Event.SettingsUnavailable -> if (state.mode == Mode.OFF) Step(state) else say(state, Reply.SettingsNeedService)
    }
```

In `spotted`, replace

```kotlin
                } else {
                    waitForTask(state, Effect.StopSaying)
                }
```

with

```kotlin
                } else {
                    waitForTask(state, Effect.StopSaying, Effect.Show(Feedback.AwaitingTask))
                }
```

Replace

```kotlin
    private fun askForTask(state: State, shown: Effect): Step = waitForTask(state, shown).then { say(it, Reply.Yes) }
```

with

```kotlin
    // The bar shows "Yes?" at the start of the wait, also when "Yes?" cannot be spoken (section 8.9).
    private fun askForTask(state: State, shown: Effect): Step =
        waitForTask(state, shown, Effect.Show(Feedback.AwaitingTask)).then { say(it, Reply.Yes) }
```

- [ ] **Step 4: Turn off with a reason in the controller**

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
    suspend fun write(mode: Mode)
}

/** What Kestrel says and shows while it listens. Plan 2C-4 decides the final wording. */
internal object ListeningTexts {
    const val YES = "Yes?"
    const val NO_TASKS = "I can't run tasks yet."
    const val LISTENING = "Listening"
    const val PAUSED = "Paused"
    const val OFF = "Off"
    const val MIC_BUSY = "Microphone busy"
    const val CANNOT_START = "Kestrel could not start listening. Start it from the notification or the tile."
    const val MODELS_MISSING = "The speech models are missing; see the setup checklist."
    const val MICROPHONE_FAILED = "The microphone failed, so Kestrel stopped listening."
    const val CANNOT_OPEN_SETTINGS = "Kestrel could not open its settings."
}
```

with

```kotlin
    suspend fun write(mode: Mode)
}

/** What Kestrel reads about the phone to say why it stopped (design spec, section 14.6). Any thread may call it. */
internal interface ListeningConditions {
    /** Whether the owner allowed Kestrel to record audio. */
    fun microphoneAllowed(): Boolean
}

/** What Kestrel says and shows while it listens, and why it stopped (design spec, sections 8.9 and 14.6). */
internal object ListeningTexts {
    const val YES = "Yes?"
    const val NO_TASKS = "I can't run tasks yet."
    const val LISTENING = "Listening"
    const val PAUSED = "Paused"
    const val OFF = "Off"

    // Also for the privacy toggle and for Android's own silencing of an app in the background, not only for another
    // app.
    const val MIC_BUSY = "Microphone blocked"
    // Short enough for the feedback bar, which fades after 4 s.
    const val BACKGROUND_START = "Android blocked a start in the background. Start Kestrel with the tile, the shortcut " +
        "or the notification."
    const val MICROPHONE_PERMISSION = "Kestrel needs the microphone permission. Open Kestrel to allow it."
    const val MODELS_MISSING = "The speech models are missing; see the setup checklist."
    const val ENGINES_FAILED = "Kestrel could not load its speech engines."
    const val MICROPHONE_FAILED = "The microphone failed, so Kestrel stopped listening."
    const val STOPPED_BY_ANDROID = "Android stopped Kestrel's listening."
    const val CANNOT_OPEN_SETTINGS = "Kestrel could not open its settings."
    const val SETTINGS_NEED_SERVICE = "I can open my settings only while my accessibility service is on. Tap my " +
        "listening notification instead."
}

/**
 * Why Kestrel turned itself off, or could not turn on (design spec, section 14.6). [text] says it on the feedback bar.
 */
internal enum class StopReason(val text: String) {
    /** Android refused to start ListeningService from the background, or to give it the microphone. */
    BACKGROUND_START(ListeningTexts.BACKGROUND_START),

    /** The owner has not allowed the microphone. */
    MICROPHONE_PERMISSION(ListeningTexts.MICROPHONE_PERMISSION),

    /** A speech model is missing or damaged. */
    MODELS_MISSING(ListeningTexts.MODELS_MISSING),

    /** Another failure while the voice session opened. */
    ENGINES_FAILED(ListeningTexts.ENGINES_FAILED),

    /** The microphone failed, also after its restart. */
    MICROPHONE_FAILED(ListeningTexts.MICROPHONE_FAILED),

    /** Android stopped ListeningService on its own. */
    STOPPED_BY_ANDROID(ListeningTexts.STOPPED_BY_ANDROID),
}
```

In the class's KDoc, replace

```kotlin
 * functions may be called from any thread. [report] gets each failure with a fixed description of what failed, and
 * must log no message.
 * [openSettings] opens Kestrel's settings app; the controller's thread calls it, and it throws when Android refuses.
```

with

```kotlin
 * functions may be called from any thread. [report] gets each failure with a fixed description of what failed, and
 * must log no message. When Kestrel turns itself off, the bar shows a [StopReason] in place of "Off" (section 14.6);
 * [conditions] tells the causes apart.
 * [openSettings] opens Kestrel's settings app and returns true, or returns false while Kestrel's accessibility service
 * is off, because Android then blocks the start without an exception and Kestrel says why instead; the controller's
 * thread calls it, and it throws when Android refuses.
```

Replace

```kotlin
    private val openSettings: () -> Unit,
    private val memory: ModeMemory,
    microphones: () -> Microphone,
) {
```

with

```kotlin
    private val openSettings: () -> Boolean,
    private val memory: ModeMemory,
    private val conditions: ListeningConditions,
    microphones: () -> Microphone,
) {
```

In `onForegroundFailed`, replace

```kotlin
                turnOff("Starting the listening service", error, ListeningTexts.CANNOT_START)
```

with

```kotlin
                turnOff("Starting the listening service", error, startFailure(error))
```

In `follow`, replace

```kotlin
                turnOff("Starting the microphone", e, ListeningTexts.MICROPHONE_FAILED)
```

with

```kotlin
                turnOff("Starting the microphone", e, microphoneFailure())
```

In `start`, replace

```kotlin
            turnOff("Starting the listening service", e, ListeningTexts.CANNOT_START)
```

with

```kotlin
            turnOff("Starting the listening service", e, startFailure(e))
```

In `destroyed`, replace

```kotlin
        // Android ended the service on its own, so Kestrel no longer listens, and the Orchestrator must know it.
        running = null
        send(Event.TurnOff)
    }
```

with

```kotlin
        // Android ended the service on its own, so Kestrel no longer listens, and the Orchestrator must know it.
        // Kestrel says so in place of "Off" (section 14.6).
        running = null
        stopBecause(StopReason.STOPPED_BY_ANDROID)
    }
```

In `open`, replace

```kotlin
                } catch (e: ModelsNotReadyException) {
                    turnOff("Opening the voice session", e, ListeningTexts.MODELS_MISSING)
                    return@withLock
                } catch (e: Exception) {
                    turnOff("Opening the voice session", e, ListeningTexts.CANNOT_START)
                    return@withLock
                } catch (e: Error) {
                    // Kestrel must not look as if it listened. AppGraph's handler still logs the error's classes.
                    send(Event.TurnOff)
                    throw e
                }
```

with

```kotlin
                } catch (e: ModelsNotReadyException) {
                    turnOff("Opening the voice session", e, StopReason.MODELS_MISSING)
                    return@withLock
                } catch (e: Exception) {
                    turnOff("Opening the voice session", e, StopReason.ENGINES_FAILED)
                    return@withLock
                } catch (e: Error) {
                    // Kestrel must not look as if it listened, and says why, as for a native library that does not
                    // load. AppGraph's handler still logs the error's classes.
                    stopBecause(StopReason.ENGINES_FAILED)
                    throw e
                }
```

In `restartMicrophone`, replace

```kotlin
            turnOff("The microphone", error, ListeningTexts.MICROPHONE_FAILED)
```

with

```kotlin
            turnOff("The microphone", error, StopReason.MICROPHONE_FAILED)
```

Replace

```kotlin
    /** Section 14: Kestrel never fails silently. It turns off and shows why, in place of "Off". */
    private fun turnOff(what: String, error: Exception, text: String) {
        report(what, error)
        if (state.value.mode == Mode.OFF) {
            bar.show(text)
        } else {
            offReason = text
            send(Event.TurnOff)
        }
    }
```

with

```kotlin
    /** Section 14: Kestrel never fails silently. It turns off and shows why, in place of "Off". */
    private fun turnOff(what: String, error: Exception, reason: StopReason) {
        report(what, error)
        stopBecause(reason)
    }

    /**
     * Turns Kestrel off for [reason], which the bar shows in place of "Off", or shows [reason] when Kestrel is off
     * already. While a turn-off for an earlier reason is on its way, that reason stays: the earlier failure caused this
     * one, as when Android destroys a service whose newer start failed. [offReason] is set only here, together with the
     * turn-off, and the next Off consumes it, so while Kestrel is on it tells that a turn-off is on its way.
     */
    private fun stopBecause(reason: StopReason) {
        when {
            state.value.mode == Mode.OFF -> bar.show(reason.text)
            offReason != null -> Unit
            else -> {
                offReason = reason.text
                send(Event.TurnOff)
            }
        }
    }

    // Android 14 and later refuse a microphone service without the permission at startForeground(). With the
    // permission, a SecurityException there means that the start came too late after a tap (prototype P2).
    private fun startFailure(error: Exception): StopReason =
        if (error is SecurityException && !conditions.microphoneAllowed()) StopReason.MICROPHONE_PERMISSION else StopReason.BACKGROUND_START

    // Android 13, as on the A71, lets the service start without the permission, and the microphone fails instead.
    private fun microphoneFailure(): StopReason =
        if (conditions.microphoneAllowed()) StopReason.MICROPHONE_FAILED else StopReason.MICROPHONE_PERMISSION
```

Replace

```kotlin
    // Section 14: Kestrel never fails silently. Android may also block an activity start from the background without an
    // exception; Kestrel's accessibility service gives it the right to start one (prototype P1).
    private fun openSettingsOrSayWhy() {
        try {
            openSettings()
        } catch (e: Exception) {
            report("Opening the settings", e)
            bar.show(ListeningTexts.CANNOT_OPEN_SETTINGS)
        }
    }
```

with

```kotlin
    // Section 14: Kestrel never fails silently. Android blocks an activity start from the background without an
    // exception unless Kestrel's accessibility service is on (prototype P1); then the bar does not exist either, so
    // Kestrel says why.
    private fun openSettingsOrSayWhy() {
        val opened = try {
            openSettings()
        } catch (e: Exception) {
            report("Opening the settings", e)
            bar.show(ListeningTexts.CANNOT_OPEN_SETTINGS)
            return
        }
        if (!opened) send(Event.SettingsUnavailable)
    }
```

Replace

```kotlin
        val text = when (reply) {
            Reply.Yes -> ListeningTexts.YES
            is Reply.Text -> reply.text
        }
        val current = session
        if (current == null) {
            // Section 14.6: without a voice the text goes to the bar. Thrown before the reply starts, so that the
            // Orchestrator ends the reply in its place.
            bar.show(text)
            error("No voice session")
        }
        val spoken = current.speak(text)
        // Registered last: nothing after it may throw, or the Orchestrator would end the reply a second time.
        spoken.invokeOnCompletion { error ->
            if (error != null && error !is CancellationException) {
                report("A reply", error)
                // Section 14.6: when the voice fails, Kestrel shows the text instead.
                bar.show(text)
            } else if (error == null && spoken.getCompleted() == Spoke.REFUSED) {
                bar.show(text)
            }
            send(Event.SpeechEnded)
        }
```

with

```kotlin
        val text = when (reply) {
            Reply.Yes -> ListeningTexts.YES
            Reply.SettingsNeedService -> ListeningTexts.SETTINGS_NEED_SERVICE
            is Reply.Text -> reply.text
        }
        // Section 14.6: a reply that Kestrel cannot speak goes to the bar, except "Yes?", which the bar shows already
        // at the start of the wait for the task (Feedback.AwaitingTask).
        val showInstead = { if (reply != Reply.Yes) bar.show(text) }
        val current = session
        if (current == null) {
            // Thrown before the reply starts, so that the Orchestrator ends the reply in its place.
            showInstead()
            error("No voice session")
        }
        val spoken = current.speak(text)
        // Registered last: nothing after it may throw, or the Orchestrator would end the reply a second time.
        spoken.invokeOnCompletion { error ->
            if (error != null && error !is CancellationException) {
                report("A reply", error)
                showInstead()
            } else if (error == null && spoken.getCompleted() == Spoke.REFUSED) {
                showInstead()
            }
            send(Event.SpeechEnded)
        }
```

In `text`, replace

```kotlin
        is Feedback.Heard -> feedback.text
        Feedback.MicBusy -> ListeningTexts.MIC_BUSY
    }
```

with

```kotlin
        is Feedback.Heard -> feedback.text
        Feedback.MicBusy -> ListeningTexts.MIC_BUSY
        Feedback.AwaitingTask -> ListeningTexts.YES
    }
```

- [ ] **Step 5: Wire the conditions, and open the settings only with the accessibility service**

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.kestrel.app.listen.BoostWordSource
import dev.kestrel.app.listen.FeedbackBar
import dev.kestrel.app.listen.LauncherAppLabels
import dev.kestrel.app.listen.Listening
```

with

```kotlin
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import dev.kestrel.app.listen.BoostWordSource
import dev.kestrel.app.listen.FeedbackBar
import dev.kestrel.app.listen.LauncherAppLabels
import dev.kestrel.app.listen.Listening
import dev.kestrel.app.listen.ListeningConditions
```

Replace

```kotlin
            // The launcher's own intent, so that Kestrel's task comes to the front instead of a second MainActivity. The
            // controller's thread calls it; startActivity may be called from any thread.
            openSettings = {
                appContext.startActivity(
                    Intent(appContext, MainActivity::class.java)
                        .setAction(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
```

with

```kotlin
            // The launcher's own intent, so that Kestrel's task comes to the front instead of a second MainActivity.
            // The controller's thread calls it; startActivity may be called from any thread. Without Kestrel's
            // accessibility service, Android blocks the start from the background without an exception (prototype P1),
            // so Kestrel does not try, and says why instead.
            openSettings = {
                if (screen.value == null) {
                    false
                } else {
                    appContext.startActivity(
                        Intent(appContext, MainActivity::class.java)
                            .setAction(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_LAUNCHER)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    true
                }
            },
```

Replace

```kotlin
            microphones = { MicCapture(appContext) },
```

with

```kotlin
            conditions = object : ListeningConditions {
                override fun microphoneAllowed() =
                    appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            },
            microphones = { MicCapture(appContext) },
```

- [ ] **Step 6: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.ListeningTest --tests dev.kestrel.app.orchestrator.RulesTest --tests dev.kestrel.app.orchestrator.OrchestratorTest`
Expected: `BUILD SUCCESSFUL`, with 67 tests in `ListeningTest` (63 and the 4 new ones), 39 in `RulesTest` (38 and `kestrelSaysWhyItCannotOpenItsSettings`) and 9 in `OrchestratorTest`.

- [ ] **Step 7: Update the spec and the README**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 8.1, replace

```markdown
and the feedback bar shows "mic busy" whenever Android silences Kestrel's recording.
```

with

```markdown
and the feedback bar shows "Microphone blocked" whenever Android silences Kestrel's recording (section 14.6).
```

In section 8.9, replace

```markdown
A thin overlay at the top of the screen shows the listening mode, the last thing that Kestrel heard, the current task step and any question that Kestrel is asking. It never receives touches. Its text fades after a few seconds without activity.
```

with

```markdown
A thin overlay at the top of the screen shows the listening mode, the last thing that Kestrel heard, the current task step and any question that Kestrel is asking: when Kestrel starts to wait for a task, after "Kestrel" on its own or after an interruption with "Kestrel", it shows "Yes?", also when it does not say it. When Kestrel turns itself off, it shows why in place of "Off" (section 14.6). It never receives touches. Its text fades after a few seconds without activity.
```

In section 14.6, replace

```markdown
| Another privileged app silences the microphone | The feedback bar shows "mic busy", and listening resumes on its own |
```

with

```markdown
| Another privileged app, the privacy toggle or Android silences the microphone | The feedback bar shows "Microphone blocked", and listening resumes on its own |
```

and replace

```markdown
| The microphone service cannot start | Kestrel asks the owner to start it from the notification or the tile, and says why |
```

with

```markdown
| The microphone service cannot start | Kestrel turns off and says why: Android refused the start in the background (then the owner can start it with the tile, the accessibility shortcut or the notification), the microphone permission is missing, the speech models are missing, or the speech engines did not load |
| Android stops the listening service on its own | Kestrel turns off and says that Android stopped its listening |
| "Kestrel, open settings" while Kestrel's accessibility service is off | Android does not let Kestrel open an activity from the background then, so Kestrel says so and asks the owner to tap its listening notification |
```

In `README.md`, in the paragraph that starts with "Kestrel's Quick Settings tile starts listening", replace

```markdown
While Kestrel's accessibility service is on, "Kestrel, open settings" opens this app; without the service, Android does not let Kestrel open it from the background.
```

with

```markdown
While Kestrel's accessibility service is on, "Kestrel, open settings" opens this app; without the service, Android does not let Kestrel open it from the background, so Kestrel says so, and a tap on its listening notification opens the app. When Kestrel turns itself off, or cannot start, the feedback bar says why in place of "Off".
```

- [ ] **Step 8: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (155 in `:app`, 116 in `:core:voice`), lint in every module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests compile.

- [ ] **Step 9: Run the voice device tests**

`ListeningServiceTest`, `ActivationTest`, `VoiceHookTest` and `ConversationTest` start and stop the real service through the changed controller; no voice device test compares a text of the bar.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 63 run, 0 failed, 0 errors, 0 skipped."

- [ ] **Step 10: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 11: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md README.md
git commit -m "feat(app): say why Kestrel stopped by its cause, show 'Yes?' while it waits for a task, and say why the settings cannot open without the accessibility service" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 2: The problems notification and "Start listening"

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/listen/ProblemNotifications.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt` (`StopReason`, new `StopNotices`, the class's KDoc and constructor, `onForeground`, new `onStartAction`, `onForegroundFailed`, `open`, `turnOff`, `stopBecause`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt` (`onStartCommand`, `notification`, the launcher intent, the companion)
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (imports, `problems`, `notices`)
- Modify: `app/src/main/res/values/strings.xml` (four strings)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 8.6 and 14.6), `README.md` (the paragraph on listening)
- Test: `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/voice/ListeningServiceTest.kt`

**Interfaces:**
- Consumes: `StopReason`, `turnOff(what, error, reason)`, `stopBecause(reason)` and `startFailure(error)` from Task 1; `Listening.foreground(instance)`, `ListeningService.NOTIFICATION_ID`, `Event.Start(paused: Boolean = false)`.
- Produces: `internal enum class StopReason(val text: String, val restart: Boolean, val starting: Boolean)`; `internal interface StopNotices { fun show(reason: StopReason); fun clear() }`; the constructor parameter `notices: StopNotices`; `fun Listening.onStartAction(instance: Any)`; `fun Listening.onForegroundFailed(error: Exception, startAction: Boolean = false)`; `stopBecause(reason: StopReason, notify: Boolean = state.value.mode != Mode.OFF)` and `turnOff(what, error, reason, notify = ...)`; `internal class ProblemNotifications(context: Context) : StopNotices` with `companion object { const val STOPPED_ID = 7_102 }`; `ListeningService.ACTION_START`, `ListeningService.startIntent(context): PendingIntent`, `ListeningService.openKestrel(context): PendingIntent`; `AppGraph.problems: ProblemNotifications` (internal); the strings `problems_channel`, `problem_title_stopped`, `problem_title_not_started` and `problem_start`. Task 4 adds the accessibility service's notification to `ProblemNotifications`; Task 5 calls `stopBecause(reason, notify = true)`.

Final review of plan 2C-1, items 4 and 10, and spike S1's start from a notification. The bar exists only while Kestrel's accessibility service runs, so every reason why Kestrel turned itself off, or could not start, is also posted as a notification on its own channel ("Problems", default importance). Its title says that Kestrel stopped listening or could not start (`StopReason.starting`), its text is the reason, a tap opens Kestrel, and a "Start listening" action comes with the reasons for which a new start can help (`StopReason.restart`: a refused start in the background, a failed microphone, a stop by Android; not missing models, a missing permission or engines that did not load). It goes when a voice session opens again, before the session's microphone starts, so that a microphone that cannot start posts its own reason, which stays. The owner can swipe it away. Kestrel never posts it for an Off that the owner chose: by default only a reason that turns Kestrel off posts it, while a failure that comes after the owner's Off, such as missing models found after the owner turned Kestrel off during the load, shows on the bar only. `stopBecause` posts only after it has sent the turn-off, and a failure to post or to remove the notification is reported by its classes, so that it can never keep Kestrel on.

"Start listening" is `PendingIntent.getForegroundService` with the new action `ACTION_START`, not a broadcast: a broadcast leaves only about 9 s for the microphone (prototype P2), while a service's PendingIntent gets it (spike S1). `ListeningService.onStartCommand` calls `Listening.onStartAction` for it, which on the controller's thread, when the service state is `STOPPED`, sets `STARTING` and `requested` together and clears `offReason`, as `start()` does (final review of plan 2C-3, "For plan 2C-4", item 2), then sends `Event.Start()` and adopts the instance as `foreground()` does, without waiting for the `lifecycle` lock; only `open()` waits for it. So `Listening` never stops this service as one that nobody asked for, whatever reaches the controller's thread first (P2 found that the code before adopted it only by the luck of the order), and a tap while the failed session still closes keeps the service for the new session. `Event.Start` acts only from Off, so a tap after the owner started Kestrel another way changes nothing. A start action whose `startForeground()` fails while Kestrel is off says why on the bar and in the notification, because the owner asked for it. The notification that `startForeground()` posts for "Start listening" shows Listening, not "Kestrel is turning off", although the mode is still Off at that moment. The test `offAndOnWhileASessionOpensKeepTheSessionForTheNewStart` (plan 2C-3, `75b21bc`) pins the adoption of an opening session, which `onStartAction` shares (item 10).

- [ ] **Step 1: Write the failing JVM tests**

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace

```kotlin
    private val conditions = FakeConditions()
```

with

```kotlin
    private val conditions = FakeConditions()
    private val notices = FakeNotices()
```

In the test `theServiceStartsWhenTheModeLeavesOffAndTheSessionOpensOnlyInTheForeground`, replace

```kotlin
        assertSame(microphone, sessions.microphones.single())
        assertTrue(listening.sessionOpen.value)
    }
```

with

```kotlin
        assertSame(microphone, sessions.microphones.single())
        assertTrue(listening.sessionOpen.value)
        // Kestrel listens again, so the notification that said why it stopped goes.
        assertEquals(1, notices.cleared)
    }
```

In the test `theFeedbackBarShowsModesTranscriptsAndMicBusy`, replace

```kotlin
            listOf(ListeningTexts.LISTENING, "Open the settings.", ListeningTexts.MIC_BUSY, ListeningTexts.PAUSED, ListeningTexts.OFF),
            shown,
        )
    }
```

with

```kotlin
            listOf(ListeningTexts.LISTENING, "Open the settings.", ListeningTexts.MIC_BUSY, ListeningTexts.PAUSED, ListeningTexts.OFF),
            shown,
        )
        // Kestrel never posts the problems notification for an Off that the owner chose.
        assertTrue(notices.shown.isEmpty())
    }
```

In the test `aServiceThatCannotStartTurnsKestrelOffAndSaysWhy`, replace

```kotlin
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.BACKGROUND_START), shown)
        assertEquals("Starting the listening service", reports.single().first)
```

with

```kotlin
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.BACKGROUND_START), shown)
        assertEquals("Starting the listening service", reports.single().first)
        // The bar exists only while the accessibility service runs, so the reason is a notification too.
        assertEquals(listOf(StopReason.BACKGROUND_START), notices.shown)
```

In the test `missingModelsTurnKestrelOffAndSaySo`, replace

```kotlin
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.MODELS_MISSING), shown)
        assertFalse(listening.sessionOpen.value)
```

with

```kotlin
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.MODELS_MISSING), shown)
        assertFalse(listening.sessionOpen.value)
        assertEquals(listOf(StopReason.MODELS_MISSING), notices.shown)
```

In the test `aFailureAfterKestrelIsOffShowsItsReasonOnItsOwn`, replace

```kotlin
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.OFF, ListeningTexts.MODELS_MISSING), shown)
```

with

```kotlin
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.OFF, ListeningTexts.MODELS_MISSING), shown)
        // The owner chose Off, so the reason shows on the bar only.
        assertTrue(notices.shown.isEmpty())
```

In the test `aMicrophoneThatDoesNotStartWithoutThePermissionAsksForIt`, replace

```kotlin
        listening.onForeground(service)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.MICROPHONE_PERMISSION), shown)
    }
```

with

```kotlin
        listening.onForeground(service)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.MICROPHONE_PERMISSION), shown)
        // The session opened, but Kestrel never listened, so the notification that says why stays (section 14.6).
        assertEquals(StopReason.MICROPHONE_PERMISSION, notices.visible)
    }
```

Add these five tests right before the test `aServiceThatNobodyAskedForIsStopped`, by replacing

```kotlin
    @Test
    fun aServiceThatNobodyAskedForIsStopped() = runTest {
```

with

```kotlin
    @Test
    fun theStartActionFromOffTurnsKestrelOnInTheServiceThatItStarted() = runTest {
        val listening = listening()

        // The problems notification's "Start listening" started the service, which runs in the foreground.
        listening.onStartAction(service)
        runCurrent()

        assertEquals(Mode.LISTENING, listening.state.value.mode)
        // Kestrel asks Android for no second start, and it does not stop the service as one that nobody asked for.
        assertEquals(listOf("open", "recognition ALL", "microphone on"), log)
        assertTrue(listening.sessionOpen.value)
        assertEquals(1, notices.cleared)
    }

    @Test
    fun theStartActionWhileKestrelIsOnChangesNothing() = runTest {
        val listening = listeningWithSession()
        listening.send(Event.Pause)
        runCurrent()
        log.clear()

        // A tap on a notification that an earlier failure left, after the owner started Kestrel another way.
        listening.onStartAction(service)
        runCurrent()

        assertEquals(Mode.PAUSED, listening.state.value.mode)
        assertTrue(log.isEmpty())
        assertEquals(1, sessions.opened.size)
    }

    @Test
    fun aTurnOffWhileTheStartActionsSessionOpensStopsTheService() = runTest {
        val opening = CompletableDeferred<Unit>()
        sessions.opening = opening
        val listening = listening()
        listening.onStartAction(service)
        runCurrent()

        listening.send(Event.TurnOff)
        runCurrent()
        opening.complete(Unit)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf("open", "close", "closed", "stop"), log)
        assertFalse(listening.sessionOpen.value)
    }

    @Test
    fun theStartActionWhileASessionClosesKeepsTheServiceForTheNewSession() = runTest {
        val listening = listeningWithSession()
        val closing = CompletableDeferred<Unit>()
        sessions.opened.single().closing = closing
        listening.send(Event.TurnOff)
        runCurrent()
        log.clear()

        // The owner taps "Start listening" while the old session still closes, and Android gives the start to the
        // service that runs.
        listening.onStartAction(service)
        runCurrent()
        closing.complete(Unit)
        runCurrent()

        // The close does not stop the service that the start action asked for, and the new session opens after it.
        assertEquals(listOf("closed", "open", "recognition ALL", "microphone on"), log)
        assertEquals(Mode.LISTENING, listening.state.value.mode)
    }

    @Test
    fun aStartActionThatCannotEnterTheForegroundSaysWhyWhileKestrelIsOff() = runTest {
        val listening = listening()

        // The owner tapped "Start listening", and the service stops itself after the failure.
        listening.onForegroundFailed(SecurityException("Foreground service of type microphone not allowed"), startAction = true)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf(ListeningTexts.BACKGROUND_START), shown)
        assertEquals(listOf(StopReason.BACKGROUND_START), notices.shown)
        assertEquals("Starting the listening service", reports.single().first)
    }

    @Test
    fun aServiceThatNobodyAskedForIsStopped() = runTest {
```

In the helper `listening`, replace

```kotlin
        conditions = conditions,
        microphones = { microphone },
    )
```

with

```kotlin
        conditions = conditions,
        notices = notices,
        microphones = { microphone },
    )
```

In the test `anErrorWhileStoringTheModeIsReportedAndLaterModesAreStillStored`, replace

```kotlin
            conditions = conditions,
            // Its first write throws an Error, and the later ones work.
```

with

```kotlin
            conditions = conditions,
            notices = notices,
            // Its first write throws an Error, and the later ones work.
```

Add the fake of the notices right before the class `FakeConditions`, by replacing

```kotlin
    private class FakeConditions : ListeningConditions {
```

with

```kotlin
    private class FakeNotices : StopNotices {
        val shown = mutableListOf<StopReason>()
        var cleared = 0

        // The notification that the owner sees now.
        var visible: StopReason? = null

        override fun show(reason: StopReason) {
            shown += reason
            visible = reason
        }

        override fun clear() {
            cleared++
            visible = null
        }
    }

    private class FakeConditions : ListeningConditions {
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD FAILED` in `:app:compileDebugUnitTestKotlin`, with `Unresolved reference` errors for `StopNotices`, `onStartAction` and the parameter `startAction`, and an error that `Listening` has no parameter `notices`.

- [ ] **Step 3: Post the reason, and adopt the start action, in the controller**

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
/**
 * Why Kestrel turned itself off, or could not turn on (design spec, section 14.6). [text] says it on the feedback bar.
 */
internal enum class StopReason(val text: String) {
    /** Android refused to start ListeningService from the background, or to give it the microphone. */
    BACKGROUND_START(ListeningTexts.BACKGROUND_START),

    /** The owner has not allowed the microphone. */
    MICROPHONE_PERMISSION(ListeningTexts.MICROPHONE_PERMISSION),

    /** A speech model is missing or damaged. */
    MODELS_MISSING(ListeningTexts.MODELS_MISSING),

    /** Another failure while the voice session opened. */
    ENGINES_FAILED(ListeningTexts.ENGINES_FAILED),

    /** The microphone failed, also after its restart. */
    MICROPHONE_FAILED(ListeningTexts.MICROPHONE_FAILED),

    /** Android stopped ListeningService on its own. */
    STOPPED_BY_ANDROID(ListeningTexts.STOPPED_BY_ANDROID),
}
```

with

```kotlin
/**
 * Why Kestrel turned itself off, or could not turn on (design spec, section 14.6). [text] says it on the feedback bar
 * and in the problems notification. [restart]: a new start can help, so the notification offers "Start listening".
 * [starting]: Kestrel failed while it started, so the notification says that it could not start listening.
 */
internal enum class StopReason(val text: String, val restart: Boolean, val starting: Boolean) {
    /** Android refused to start ListeningService from the background, or to give it the microphone. */
    BACKGROUND_START(ListeningTexts.BACKGROUND_START, restart = true, starting = true),

    /** The owner has not allowed the microphone. */
    MICROPHONE_PERMISSION(ListeningTexts.MICROPHONE_PERMISSION, restart = false, starting = true),

    /** A speech model is missing or damaged. */
    MODELS_MISSING(ListeningTexts.MODELS_MISSING, restart = false, starting = true),

    /** Another failure while the voice session opened. */
    ENGINES_FAILED(ListeningTexts.ENGINES_FAILED, restart = false, starting = true),

    /** The microphone failed, also after its restart. */
    MICROPHONE_FAILED(ListeningTexts.MICROPHONE_FAILED, restart = true, starting = false),

    /** Android stopped ListeningService on its own. */
    STOPPED_BY_ANDROID(ListeningTexts.STOPPED_BY_ANDROID, restart = true, starting = false),
}

/**
 * The problems notification, which says why Kestrel stopped also while the feedback bar does not exist (design spec,
 * section 14.6). The controller's thread calls it.
 */
internal interface StopNotices {
    /** Posts [reason], in place of an earlier one. Does nothing without the notification permission. */
    fun show(reason: StopReason)

    /** Removes the notification of [show], if it is there. */
    fun clear()
}
```

In the class's KDoc, replace

```kotlin
 * must log no message. When Kestrel turns itself off, the bar shows a [StopReason] in place of "Off" (section 14.6);
 * [conditions] tells the causes apart.
```

with

```kotlin
 * must log no message. When Kestrel turns itself off, the bar shows a [StopReason] in place of "Off" (section 14.6),
 * and [notices] posts it as a notification, which goes once a voice session opens again; [conditions] tells the causes
 * apart.
```

Replace

```kotlin
    private val conditions: ListeningConditions,
    microphones: () -> Microphone,
) {
```

with

```kotlin
    private val conditions: ListeningConditions,
    private val notices: StopNotices,
    microphones: () -> Microphone,
) {
```

Replace

```kotlin
    /** ListeningService, whose instance is [instance], runs in the foreground. */
    fun onForeground(instance: Any) {
        scope.launch(thread) { foreground(instance) }
    }
```

with

```kotlin
    /** ListeningService, whose instance is [instance], runs in the foreground. */
    fun onForeground(instance: Any) {
        scope.launch(thread) { foreground(instance) }
    }

    /**
     * ListeningService, whose instance is [instance], runs in the foreground after the problems notification's "Start
     * listening" (section 14.6). From Off, Kestrel marks this service as asked for before it sends Event.Start, so it
     * never stops the service as one that nobody asked for. While Kestrel is on, nothing changes.
     */
    fun onStartAction(instance: Any) {
        scope.launch(thread) {
            if (serviceState == ServiceState.STOPPED) {
                serviceState = ServiceState.STARTING
                requested = true
                // A reason from an earlier start must never come back.
                offReason = null
            }
            // Event.Start acts only from Off, so a tap after the owner started Kestrel another way changes nothing.
            send(Event.Start())
            foreground(instance)
        }
    }
```

Replace

```kotlin
    /** ListeningService could not enter the foreground; [error] says why. */
    fun onForegroundFailed(error: Exception) {
        scope.launch(thread) {
            // The service stops itself after a failed startForeground(), which Android allows.
            awaitingForeground = false
            if (serviceState == ServiceState.STOPPED) {
                // A start that nobody asked for, such as a stale notification action.
                report("Starting the listening service", error)
            } else {
                // Also for the running service: a notification action's start failed, and the service stops itself.
                turnOff("Starting the listening service", error, startFailure(error))
            }
        }
    }
```

with

```kotlin
    /**
     * ListeningService could not enter the foreground; [error] says why. [startAction]: the start came from the
     * problems notification's "Start listening", so the owner asked for it, also while Kestrel is off.
     */
    fun onForegroundFailed(error: Exception, startAction: Boolean = false) {
        scope.launch(thread) {
            // The service stops itself after a failed startForeground(), which Android allows.
            awaitingForeground = false
            when {
                // Section 14.6: Kestrel is off, so the bar and the notification say why the owner's start failed.
                startAction && serviceState == ServiceState.STOPPED ->
                    turnOff("Starting the listening service", error, startFailure(error), notify = true)
                // A start that nobody asked for, such as a stale notification action.
                serviceState == ServiceState.STOPPED -> report("Starting the listening service", error)
                // Also for the running service: a notification action's start failed, and the service stops itself.
                else -> turnOff("Starting the listening service", error, startFailure(error))
            }
        }
    }
```

In `open`, replace

```kotlin
                sessionState.value = true
                followSafely(state.value)
```

with

```kotlin
                sessionState.value = true
                // Kestrel listens again, so the notification that said why it stopped goes (section 14.6). Before the
                // microphone starts: a microphone that cannot start posts its own reason, which must stay.
                try {
                    notices.clear()
                } catch (e: Exception) {
                    report("Removing the problems notification", e)
                }
                followSafely(state.value)
```

Replace Task 1's `turnOff` and `stopBecause`

```kotlin
    /** Section 14: Kestrel never fails silently. It turns off and shows why, in place of "Off". */
    private fun turnOff(what: String, error: Exception, reason: StopReason) {
        report(what, error)
        stopBecause(reason)
    }

    /**
     * Turns Kestrel off for [reason], which the bar shows in place of "Off", or shows [reason] when Kestrel is off
     * already. While a turn-off for an earlier reason is on its way, that reason stays: the earlier failure caused this
     * one, as when Android destroys a service whose newer start failed. [offReason] is set only here, together with the
     * turn-off, and the next Off consumes it, so while Kestrel is on it tells that a turn-off is on its way.
     */
    private fun stopBecause(reason: StopReason) {
        when {
            state.value.mode == Mode.OFF -> bar.show(reason.text)
            offReason != null -> Unit
            else -> {
                offReason = reason.text
                send(Event.TurnOff)
            }
        }
    }
```

with

```kotlin
    /** Section 14: Kestrel never fails silently. It turns off and shows why, in place of "Off". */
    private fun turnOff(what: String, error: Exception, reason: StopReason, notify: Boolean = state.value.mode != Mode.OFF) {
        report(what, error)
        stopBecause(reason, notify)
    }

    /**
     * Turns Kestrel off for [reason], which the bar shows in place of "Off", or shows [reason] when Kestrel is off
     * already. While a turn-off for an earlier reason is on its way, that reason stays: the earlier failure caused this
     * one, as when Android destroys a service whose newer start failed. [offReason] is set only here, together with the
     * turn-off, and the next Off consumes it, so while Kestrel is on it tells that a turn-off is on its way. With
     * [notify], the problems notification says it too, because the bar exists only while the accessibility service
     * runs. By default it does so only when Kestrel turns itself off, never after an Off that the owner chose.
     */
    private fun stopBecause(reason: StopReason, notify: Boolean = state.value.mode != Mode.OFF) {
        when {
            state.value.mode == Mode.OFF -> bar.show(reason.text)
            offReason != null -> return
            else -> {
                offReason = reason.text
                send(Event.TurnOff)
            }
        }
        // After the turn-off, so that a notification that cannot be posted never keeps Kestrel on.
        if (notify) {
            try {
                notices.show(reason)
            } catch (e: Exception) {
                report("Posting the problems notification", e)
            }
        }
    }
```

Create `app/src/main/kotlin/dev/kestrel/app/listen/ProblemNotifications.kt`:

```kotlin
package dev.kestrel.app.listen

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import dev.kestrel.app.R

/**
 * Kestrel's notifications about problems (design spec, section 14.6), because the feedback bar exists only while
 * Kestrel's accessibility service runs. The owner can swipe each of them away. Without the notification permission they
 * post nothing. Any thread may call them.
 */
internal class ProblemNotifications(private val context: Context) : StopNotices {
    private val notifications = context.getSystemService(NotificationManager::class.java)

    /** Says why Kestrel stopped listening or could not start, with "Start listening" when a new start can help. */
    override fun show(reason: StopReason) {
        if (!allowed()) return
        notifications.createNotificationChannel(
            NotificationChannel(PROBLEMS, context.getString(R.string.problems_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val title = if (reason.starting) R.string.problem_title_not_started else R.string.problem_title_stopped
        val builder = Notification.Builder(context, PROBLEMS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(title))
            .setContentText(reason.text)
            .setStyle(Notification.BigTextStyle().bigText(reason.text))
            .setContentIntent(ListeningService.openKestrel(context))
            .setAutoCancel(true)
        if (reason.restart) {
            val start = ListeningService.startIntent(context)
            builder.addAction(Notification.Action.Builder(null, context.getString(R.string.problem_start), start).build())
        }
        notifications.notify(STOPPED_ID, builder.build())
    }

    override fun clear() = notifications.cancel(STOPPED_ID)

    private fun allowed() =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** The notification that says why Kestrel stopped listening or could not start. */
        internal const val STOPPED_ID = 7_102
        private const val PROBLEMS = "problems"
    }
}
```

`onStartAction` now changes `requested` and `serviceState` too, so in `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, in the comment on these fields, replace

```kotlin
    // Only [thread] uses these. [requested]: the service was asked to start since the last stop; start() and shutdown()
    // change it together with [serviceState], so it always equals serviceState != STOPPED, and foreground() relies on
    // that. [running]: the service's instance that runs in the foreground and that Kestrel has not asked to stop.
```

with

```kotlin
    // Only [thread] uses these. [requested]: the service was asked to start since the last stop; start(),
    // onStartAction() and shutdown() change it together with [serviceState], so it always equals
    // serviceState != STOPPED, and foreground() relies on that. [running]: the service's instance that runs in the
    // foreground and that Kestrel has not asked to stop.
```

- [ ] **Step 4: Start the service with the start action**

In `app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt`, replace

```kotlin
            startForeground(NOTIFICATION_ID, notification(listening.state.value.mode), FOREGROUND_TYPES)
```

with

```kotlin
            // "Start listening" turns Kestrel on, so the notification that startForeground() posts, possibly after the
            // collector's update, must not say that Kestrel is turning off.
            val mode = listening.state.value.mode
            startForeground(
                NOTIFICATION_ID,
                notification(if (mode == Mode.OFF && intent?.action == ACTION_START) Mode.LISTENING else mode),
                FOREGROUND_TYPES,
            )
```

Replace

```kotlin
            listening.onForegroundFailed(e)
            if (stopSelfResult(startId)) foreground = false
```

with

```kotlin
            listening.onForegroundFailed(e, startAction = intent?.action == ACTION_START)
            if (stopSelfResult(startId)) foreground = false
```

Replace

```kotlin
        listening.onForeground(this)
        when (intent?.action) {
```

with

```kotlin
        // The problems notification's "Start listening" asks for a start that Listening adopts (section 14.6).
        if (intent?.action == ACTION_START) listening.onStartAction(this) else listening.onForeground(this)
        when (intent?.action) {
```

Replace

```kotlin
        val open = PendingIntent.getActivity(this, 0, launcherIntent(), PendingIntent.FLAG_IMMUTABLE)
        val title = when (mode) {
```

with

```kotlin
        val title = when (mode) {
```

Replace

```kotlin
            .setContentIntent(open)
```

with

```kotlin
            .setContentIntent(openKestrel(this))
```

Replace

```kotlin
    // The launcher's own intent, so that a tap brings Kestrel's task to the front instead of adding another MainActivity.
    private fun launcherIntent(): Intent =
        Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    private fun action(title: Int, action: String): Notification.Action =
```

with

```kotlin
    private fun action(title: Int, action: String): Notification.Action =
```

Replace

```kotlin
        internal const val ACTION_STOP = "dev.kestrel.listen.STOP"
```

with

```kotlin
        internal const val ACTION_STOP = "dev.kestrel.listen.STOP"

        /** The problems notification's "Start listening" (design spec, section 14.6). */
        internal const val ACTION_START = "dev.kestrel.listen.START"
```

Replace

```kotlin
        /** The intent of the notification's action [action]: it starts the service with that action. */
```

with

```kotlin
        /**
         * The intent of the problems notification's "Start listening": it starts the service with [ACTION_START]. A
         * service's PendingIntent lets the service record after the tap (spike S1); a broadcast would leave it only
         * about 9 s to call startForeground() with the microphone (prototype P2).
         */
        internal fun startIntent(context: Context): PendingIntent = PendingIntent.getForegroundService(
            context,
            0,
            Intent(context, ListeningService::class.java).setAction(ACTION_START),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /**
         * Opens Kestrel's settings app with the launcher's own intent, so that a tap brings Kestrel's task to the front
         * instead of adding another MainActivity.
         */
        internal fun openKestrel(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PendingIntent.FLAG_IMMUTABLE,
        )

        /** The intent of the notification's action [action]: it starts the service with that action. */
```

- [ ] **Step 5: Wire the notifications, and add the strings**

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import dev.kestrel.app.listen.ModeMemory
```

with

```kotlin
import dev.kestrel.app.listen.ModeMemory
import dev.kestrel.app.listen.ProblemNotifications
```

Replace

```kotlin
    /**
     * Kestrel's listening, one for the whole process (design spec, sections 7.2 and 7.3), so that the accessibility
```

with

```kotlin
    /** Kestrel's notifications about problems (design spec, section 14.6). */
    internal val problems: ProblemNotifications by lazy { ProblemNotifications(appContext) }

    /**
     * Kestrel's listening, one for the whole process (design spec, sections 7.2 and 7.3), so that the accessibility
```

Replace

```kotlin
            report = reportFailure,
```

with

```kotlin
            report = reportFailure,
            notices = problems,
```

In `app/src/main/res/values/strings.xml`, replace

```xml
    <string name="listening_stop">Stop</string>
```

with

```xml
    <string name="listening_stop">Stop</string>

    <!-- The problems notification, which says why Kestrel stopped listening (design spec, section 14.6). -->
    <string name="problems_channel">Problems</string>
    <string name="problem_title_stopped">Kestrel stopped listening</string>
    <string name="problem_title_not_started">Kestrel could not start listening</string>
    <string name="problem_start">Start listening</string>
```

- [ ] **Step 6: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD SUCCESSFUL`, with 72 tests in `ListeningTest` (67 and the 5 new ones).

- [ ] **Step 7: Check the notification and the start action on the device**

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/ListeningServiceTest.kt`, replace

```kotlin
import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.listen.ListeningService
```

with

```kotlin
import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.listen.ListeningService
import dev.kestrel.app.listen.ListeningTexts
import dev.kestrel.app.listen.ProblemNotifications
```

Replace

```kotlin
 * ListeningService (design spec, sections 7.2, 8.5 and 8.6): the foreground, the notification's actions, the screen and
 * the lock, and a stop that Kestrel did not ask for. The lock-screen test runs only on emulators: it skips itself on a
 * phone, and scripts\voice-test.ps1 leaves it out there.
```

with

```kotlin
 * ListeningService (design spec, sections 7.2, 8.5, 8.6 and 14.6): the foreground, the notification's actions, the
 * screen and the lock, a stop that Kestrel did not ask for and the problems notification that it posts, and the start
 * from that notification's "Start listening". The lock-screen test runs only on emulators: it skips itself on a phone,
 * and scripts\voice-test.ps1 leaves it out there.
```

Replace

```kotlin
    @After
    fun tearDown() {
        // A test that failed while the screen was off must not leave it off for the next tests.
```

with

```kotlin
    @After
    fun tearDown() {
        // The problems notification of a stop by Android is not left for the next tests.
        notifications.cancel(ProblemNotifications.STOPPED_ID)
        // A test that failed while the screen was off must not leave it off for the next tests.
```

Replace

```kotlin
        // As when Android ends the service on its own: Kestrel did not ask for it.
        context.stopService(Intent(context, ListeningService::class.java))
        awaitState { it.mode == Mode.OFF }
        assertTrue("The voice session is still open", VoiceTests.waitUntil(30_000) { !listening.sessionOpen.value })
    }
```

with

```kotlin
        // As when Android ends the service on its own: Kestrel did not ask for it.
        context.stopService(Intent(context, ListeningService::class.java))
        awaitState { it.mode == Mode.OFF }
        assertTrue("The voice session is still open", VoiceTests.waitUntil(30_000) { !listening.sessionOpen.value })

        // Section 14.6: the reason is a notification too, with "Start listening", because the bar may not exist.
        val problem = problemNotification()
        assertEquals(ListeningTexts.STOPPED_BY_ANDROID, problem.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(texts(R.string.problem_start), problem.actions.map { it.title.toString() })
    }

    @Test
    fun theProblemNotificationsStartActionTurnsKestrelOn() {
        VoiceTests.useMicrophone(clip)
        // In front, as after a tap on the notification, which lets Kestrel start its microphone service (spike S1).
        ActivityScenario.launch(MainActivity::class.java).use {
            ListeningService.startIntent(context).send()
            assertTrue("The voice session did not open", VoiceTests.waitUntil(60_000) { listening.sessionOpen.value })
        }

        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertTrue("The microphone does not record", VoiceTests.waitUntil(5_000) { clip.recording })
    }
```

Replace

```kotlin
    /** Sends the intent of the notification's action [title], as a tap on it would. */
```

with

```kotlin
    /** The problems notification, once it shows (section 14.6). */
    private fun problemNotification(): Notification {
        var found: Notification? = null
        val shown = VoiceTests.waitUntil(5_000) {
            found = notifications.activeNotifications.firstOrNull { it.id == ProblemNotifications.STOPPED_ID }?.notification
            found != null
        }
        assertTrue("No problems notification", shown)
        return found!!
    }

    /** Sends the intent of the notification's action [title], as a tap on it would. */
```

- [ ] **Step 8: Update the spec and the README**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 8.6, replace

```markdown
| Persistent notification | Pause, resume or stop |
```

with

```markdown
| Persistent notification | Pause, resume or stop |
| "Start listening" in the notification that says why Kestrel stopped (section 14.6) | Starts listening |
```

In section 14.6, replace

```markdown
| The microphone service cannot start | Kestrel turns off and says why: Android refused the start in the background (then the owner can start it with the tile, the accessibility shortcut or the notification), the microphone permission is missing, the speech models are missing, or the speech engines did not load |
| Android stops the listening service on its own | Kestrel turns off and says that Android stopped its listening |
```

with

```markdown
| The microphone service cannot start | Kestrel turns off and says why: Android refused the start in the background (then the owner can start it with the tile, the accessibility shortcut or the notification), the microphone permission is missing, the speech models are missing, or the speech engines did not load |
| Android stops the listening service on its own | Kestrel turns off and says that Android stopped its listening |
| Kestrel turns itself off, or cannot start | Besides the feedback bar, which exists only while the accessibility service runs, a notification on the channel "Problems" says why. A tap opens Kestrel. When a new start can help (a refused start in the background, a failed microphone, a stop by Android), it has a "Start listening" action, which starts the listening service as a service's `PendingIntent`, because a broadcast would leave only about 9 s for the microphone (prototype P2). The notification goes once Kestrel listens again, and the owner can swipe it away. Kestrel posts none for an Off that the owner chose |
```

In `README.md`, replace

```markdown
When Kestrel turns itself off, or cannot start, the feedback bar says why in place of "Off".
```

with

```markdown
When Kestrel turns itself off, or cannot start, the feedback bar says why in place of "Off", and so does a notification, whose "Start listening" starts Kestrel again when a new start can help.
```

- [ ] **Step 9: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (160 in `:app`, 116 in `:core:voice`), lint in every module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests compile.

- [ ] **Step 10: Run the voice device tests**

`ListeningServiceTest` has 4 tests now: `aNewServiceReportsTheScreenAndAStopByAndroidTurnsKestrelOff` also checks the problems notification, and `theProblemNotificationsStartActionTurnsKestrelOn` is new.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 64 run, 0 failed, 0 errors, 0 skipped."

- [ ] **Step 11: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 12: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/listen/ProblemNotifications.kt app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/main/res/values/strings.xml app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/ListeningServiceTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md README.md
git commit -m "feat(app): post why Kestrel stopped as a notification, whose Start listening starts the service with an action that Listening adopts" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: The listening notification's actions as broadcasts

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/listen/NotificationActionReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml` (the receiver)
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt` (the class's KDoc, `onStartCommand`, `actionIntent`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt` (comments in `ServiceControl`, the class's KDoc, `onForegroundFailed`, `start` and `foreground`)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 8.6), `README.md` (the paragraph on listening)
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/voice/ListeningServiceTest.kt`; a comment in `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`

**Interfaces:**
- Consumes: `ListeningService.ACTION_PAUSE`, `ACTION_RESUME`, `ACTION_STOP`, `ListeningService.actionIntent(context, action)`, `Listening.send(event)`, `ListeningService.ACTION_START` and `Listening.onStartAction` from Task 2.
- Produces: `class NotificationActionReceiver : BroadcastReceiver` (non-exported); `ListeningService.actionIntent(context, action)` returns a broadcast `PendingIntent`; `onStartCommand` handles only real starts and `ACTION_START`.

Final review of plan 2C-1, items 9 and 10. Each of the notification's Pause, Resume and Stop started the service again, which called `startForeground()` again: Android may refuse that in the background, and a refusal turned Kestrel off. The service already runs in the foreground, so the actions need no new start: they become broadcasts to a receiver that only Kestrel's own `PendingIntent`s reach (`android:exported="false"`), which sends `Event.Pause`, `Event.Resume` or `Event.TurnOff`. `onStartCommand` then handles only real starts (Listening's own and "Start listening"), so the repost of the mode 1 s after each start stays: it corrects a notification that `startForeground()` posted late, which can happen after every real start. Since Android 14 the owner can swipe the listening notification away while the phone is unlocked; it comes back with the next change of mode, and listening goes on. This plan keeps that behaviour and says so in spec 8.6 and the README.

- [ ] **Step 1: Write the device test of the actions in the background**

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/ListeningServiceTest.kt`, add this test right before the test `theMicrophoneStopsWhileTheScreenIsOffAndStartsAgainAfterTheUnlock`, by replacing

```kotlin
    @Test
    fun theMicrophoneStopsWhileTheScreenIsOffAndStartsAgainAfterTheUnlock() {
```

with

```kotlin
    @Test
    fun theNotificationsActionsWorkWithKestrelInTheBackground() {
        ActivityScenario.launch(MainActivity::class.java).use { VoiceTests.startListening(clip) }
        // As when the owner uses the notification over another app, with the screen on.
        VoiceTests.shell("input keyevent KEYCODE_HOME")
        assertEquals(texts(R.string.listening_pause, R.string.listening_stop), actions(R.string.listening_title_listening))
        // Section 8.6: broadcasts, which need no new start of the service. Before this task they started the service.
        val shown = notifications.activeNotifications.single { it.id == ListeningService.NOTIFICATION_ID }.notification
        assertTrue("An action still starts the service", shown.actions.all { it.actionIntent.isBroadcast })

        press(R.string.listening_pause)
        awaitState { it.mode == Mode.PAUSED }
        assertEquals(texts(R.string.listening_resume, R.string.listening_stop), actions(R.string.listening_title_paused))
        press(R.string.listening_resume)
        awaitState { it.mode == Mode.LISTENING }
        assertEquals(texts(R.string.listening_pause, R.string.listening_stop), actions(R.string.listening_title_listening))
        assertTrue("The microphone does not record", VoiceTests.waitUntil(5_000) { clip.recording })

        press(R.string.listening_stop)
        awaitState { it.mode == Mode.OFF }
        assertTrue("The voice session is still open", VoiceTests.waitUntil(30_000) { !listening.sessionOpen.value })
        assertTrue("ListeningService still runs", VoiceTests.waitUntil(10_000) { !VoiceTests.listeningServiceRuns() })
    }

    @Test
    fun theMicrophoneStopsWhileTheScreenIsOffAndStartsAgainAfterTheUnlock() {
```

- [ ] **Step 2: Run the device test to verify that it fails**

The actions still start the service, so the check of their `PendingIntent`s fails (`PendingIntent.isBroadcast()`, API 31). The presses themselves would pass, because the instrumented process lets Kestrel start its service. The models stay on the device after `voice-test.ps1`, and Gradle reinstalls Kestrel with `install -r`, which keeps them.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.voice.ListeningServiceTest#theNotificationsActionsWorkWithKestrelInTheBackground' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' --no-configuration-cache`
Expected: `BUILD FAILED`; the result file in `app\build\outputs\androidTest-results\connected\debug\` shows 1 test and 1 failure, with the message "An action still starts the service".

- [ ] **Step 3: Add the receiver**

Create `app/src/main/kotlin/dev/kestrel/app/listen/NotificationActionReceiver.kt`:

```kotlin
package dev.kestrel.app.listen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.orchestrator.Event

/**
 * Receives the listening notification's Pause, Resume and Stop (design spec, section 8.6). They are broadcasts, not new
 * starts of ListeningService, which already runs in the foreground: a new start would call startForeground() again,
 * which Android may refuse in the background, and a refusal would turn Kestrel off. It is not exported, so only
 * Kestrel's own PendingIntents reach it. Android calls it on the main thread, and it only sends an event.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = when (intent.action) {
            ListeningService.ACTION_PAUSE -> Event.Pause
            ListeningService.ACTION_RESUME -> Event.Resume
            ListeningService.ACTION_STOP -> Event.TurnOff
            else -> return
        }
        (context.applicationContext as KestrelApplication).graph.listening.send(event)
    }
}
```

In `app/src/main/AndroidManifest.xml`, replace

```xml
        <!-- The Quick Settings tile that toggles listening (design spec, sections 7.2 and 8.6). Only SystemUI, which holds
```

with

```xml
        <!-- The listening notification's Pause, Resume and Stop (design spec, section 8.6). Only Kestrel's own
             PendingIntents reach it. -->
        <receiver
            android:name=".listen.NotificationActionReceiver"
            android:exported="false" />

        <!-- The Quick Settings tile that toggles listening (design spec, sections 7.2 and 8.6). Only SystemUI, which holds
```

- [ ] **Step 4: Send the actions as broadcasts**

In `app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt`, replace

```kotlin
 * (B5). [Listening] starts and stops it, and holds the voice session while it runs. The service shows the notification
 * with Pause or Resume and Stop, or without actions once Kestrel is turning off, and tells [Listening] whether the screen
 * is on and the phone unlocked. It stops itself when [Listening] asks, unless a newer start is still on its way to it.
 * Android calls every callback on the main thread.
```

with

```kotlin
 * (B5). [Listening] starts and stops it, and holds the voice session while it runs. The service shows the notification
 * with Pause or Resume and Stop, or without actions once Kestrel is turning off, and tells [Listening] whether the
 * screen is on and the phone unlocked. The actions are broadcasts to [NotificationActionReceiver], so they need no new
 * start of the service. Since Android 14 the owner can swipe the notification away while the phone is unlocked; it
 * comes back with the next change of mode. The service stops itself when [Listening] asks, unless a newer start is
 * still on its way to it. Android calls every callback on the main thread.
```

Replace

```kotlin
        // The problems notification's "Start listening" asks for a start that Listening adopts (section 14.6).
        if (intent?.action == ACTION_START) listening.onStartAction(this) else listening.onForeground(this)
        when (intent?.action) {
            ACTION_PAUSE -> listening.send(Event.Pause)
            ACTION_RESUME -> listening.send(Event.Resume)
            ACTION_STOP -> listening.send(Event.TurnOff)
        }
        return START_NOT_STICKY
```

with

```kotlin
        // The problems notification's "Start listening" asks for a start that Listening adopts (section 14.6).
        if (intent?.action == ACTION_START) listening.onStartAction(this) else listening.onForeground(this)
        return START_NOT_STICKY
```

Replace

```kotlin
        /** The intent of the notification's action [action]: it starts the service with that action. */
        internal fun actionIntent(context: Context, action: String): PendingIntent = PendingIntent.getForegroundService(
            context,
            0,
            Intent(context, ListeningService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
```

with

```kotlin
        /** The intent of the notification's action [action]: a broadcast to [NotificationActionReceiver]. */
        internal fun actionIntent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, NotificationActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
```

`ListeningService.kt` still uses `Event` in `reportScreen`, so its import stays.

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
     * Asks the service to stop itself. It stops unless a newer start, such as a notification action, is still on its way
     * to it; that start reports to [Listening.onForeground]. Nothing happens when the service does not run.
```

with

```kotlin
     * Asks the service to stop itself, on the main thread a moment later. It stops unless Android has a newer start for
     * it that onStartCommand has not handled yet; that start reports to [Listening.onForeground] or
     * [Listening.onStartAction]. A "Start listening" that onStartCommand handled just before the stop runs, and that
     * Listening has not adopted yet, stops with it; the owner then taps again. Nothing happens when the service does
     * not run.
```

Replace

```kotlin
                // A start that nobody asked for, such as a stale notification action.
                serviceState == ServiceState.STOPPED -> report("Starting the listening service", error)
                // Also for the running service: a notification action's start failed, and the service stops itself.
                else -> turnOff("Starting the listening service", error, startFailure(error))
```

with

```kotlin
                // A start that nobody wants any more: Kestrel turned off before the service reported.
                serviceState == ServiceState.STOPPED -> report("Starting the listening service", error)
                // Also for the running service: a newer start, such as "Start listening", failed, and the service stops
                // itself.
                else -> turnOff("Starting the listening service", error, startFailure(error))
```

Replace

```kotlin
            // A service that nobody asked for, such as one that a stale notification started while Kestrel was off.
            ServiceState.STOPPED -> scope.launch(thread) {
                lifecycle.withLock { if (serviceState == ServiceState.STOPPED) stopService() }
            }
            // The running service handled a notification action.
            ServiceState.FOREGROUND -> Unit
```

with

```kotlin
            // A service that nobody wants any more, such as one whose start came after Kestrel turned off again.
            ServiceState.STOPPED -> scope.launch(thread) {
                lifecycle.withLock { if (serviceState == ServiceState.STOPPED) stopService() }
            }
            // A newer start, such as "Start listening" while Kestrel listens, reached the running service.
            ServiceState.FOREGROUND -> Unit
```

After the broadcasts, only the tile still gives Kestrel a short exemption for a start from the background. In the class's KDoc, replace

```kotlin
 * Android is asked for the service at once, also while a session still closes, because a tap on the tile or a
 * notification action lets Kestrel start a foreground service from the background only for a few seconds. The public
 * functions may be called from any thread. [report] gets each failure with a fixed description of what failed, and
```

with

```kotlin
 * Android is asked for the service at once, also while a session still closes, because a tap on the tile lets Kestrel
 * start a foreground service from the background only for a few seconds. The public functions may be called from any
 * thread. [report] gets each failure with a fixed description of what failed, and
```

In `start`, replace

```kotlin
        // At once, also while a shutdown still closes the session: a tap on the tile or a notification action lets Kestrel
        // start a foreground service from the background only for a few seconds. The shutdown's stop sees [requested]
        // and leaves the service running, and open() waits for the close.
```

with

```kotlin
        // At once, also while a shutdown still closes the session: a tap on the tile lets Kestrel start a foreground
        // service from the background only for a few seconds. The shutdown's stop sees [requested] and leaves the
        // service running, and open() waits for the close.
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, in the test `aReportOfTheClosingServiceBeforeANewStartDoesNotStopTheNewService`, replace

```kotlin
        // A notification action reaches the old service while it closes, and then the owner turns Kestrel on again.
```

with

```kotlin
        // A late report of a start that Kestrel no longer wants reaches the old service while it closes, and then the
        // owner turns Kestrel on again.
```

- [ ] **Step 5: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (160 in `:app`, 116 in `:core:voice`; this task changes no JVM test), lint in every module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests compile.

- [ ] **Step 6: Run the voice device tests**

`ListeningServiceTest` has 5 tests now. `theServiceRunsInTheForegroundAndItsNotificationPausesResumesAndStops` presses the actions with `MainActivity` in front, and `theNotificationsActionsWorkWithKestrelInTheBackground` with Kestrel in the background; both now reach `NotificationActionReceiver`.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 65 run, 0 failed, 0 errors, 0 skipped."

- [ ] **Step 7: Update the spec and the README**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 8.6, replace

```markdown
The side-key and earbud routes depend on Kestrel holding the assistant role (spike S2). If they fail, the shortcut, the tile and the notification still work.
```

with

```markdown
The side-key and earbud routes depend on Kestrel holding the assistant role (spike S2). If they fail, the shortcut, the tile and the notification still work.

The notification's Pause, Resume and Stop reach Kestrel as broadcasts to a receiver that only Kestrel's own notification can reach, so they need no new start of the listening service and work in the background. Since Android 14 the owner can swipe the listening notification away while the phone is unlocked; Kestrel goes on listening, and the notification comes back with the next change of mode.
```

In `README.md`, replace

```markdown
While Kestrel listens, its notification shows whether it listens or is paused, and its Pause or Resume and Stop actions work.
```

with

```markdown
While Kestrel listens, its notification shows whether it listens or is paused, and its Pause or Resume and Stop actions work, also over another app. You can swipe the notification away while the phone is unlocked; Kestrel goes on listening, and the notification comes back when the mode changes.
```

- [ ] **Step 8: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 9: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/listen/NotificationActionReceiver.kt app/src/main/AndroidManifest.xml app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/ListeningServiceTest.kt app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md README.md
git commit -m "feat(app): send the listening notification's Pause, Resume and Stop as broadcasts, which need no new start of the service" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: Battery restrictions and the accessibility service turned off

**Files:**
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt` (`ListeningConditions`, `ListeningTexts`, `StopReason`, `destroyed`, `performUnguarded`, new `demotedByBattery`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (imports, `conditions`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/ProblemNotifications.kt` (imports, `accessibilityServiceOff`, `accessibilityServiceOn`, the companion)
- Modify: `app/src/main/kotlin/dev/kestrel/app/KestrelAccessibilityService.kt` (the class's KDoc, `onServiceConnected`, `onUnbind`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt` (`SetupStatus`, `states`), `SetupStatusReader.kt` (whole file below), `ChecklistPage.kt` (`ChecklistPage`, `StepCard`, `texts`), `SettingsApp.kt` (the checklist's call in `SettingsApp`)
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 14.6 and 15), `README.md` (the setup steps 3 and 5, and the paragraph on the device tests of `:app`)
- Test: `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, `app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/settings/SetupStatusReaderTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/AccessibilityNoticeTest.kt` (new)

**Interfaces:**
- Consumes: `ListeningConditions`, `StopReason` and `stopBecause(reason)` from Task 1; `ProblemNotifications` and `AppGraph.problems` from Task 2; `Listening.running`; `SetupStatus`, `SetupChecklist.states`, `ChecklistPage(states, models, onAction, onBack)` and `SettingsApp`.
- Produces: `ListeningConditions.backgroundRestricted(): Boolean` and `ListeningConditions.demoted(service: Any): Boolean`; `StopReason.BATTERY_RESTRICTED` and `ListeningTexts.BATTERY_RESTRICTED`; `ProblemNotifications.accessibilityServiceOff()`, `accessibilityServiceOn()` and `ProblemNotifications.ACCESSIBILITY_ID = 7_103`; `SetupStatus(..., models, serviceEnabled: Boolean = serviceOn, batteryRestricted: Boolean = false, samsung: Boolean = false)`; `ChecklistPage(states, models, samsung, onAction, onBack)`; the strings `setup_service_warning`, `setup_battery_detail_samsung`, `setup_battery_warning`, `accessibility_channel`, `problem_service_off_title` and `problem_service_off_text`.

Final review of plan 2C-1, items 5 and 8, and prototype P2, Q1 and Q5. With battery use "Restricted" and without Kestrel's accessibility service, Android takes the foreground from `ListeningService` about 30 s after the Quick Settings panel closes (P2 measured only starts from the tile), without an exception or a callback, silences the microphone 5 s later, and stops the service a minute after that. The first sign that Kestrel sees is the silenced microphone (`Feedback.MicBusy`), so at that moment `Listening` asks the running service for its foreground service type: when it is 0 and `isBackgroundRestricted()` is true, Kestrel turns off at once with the battery text instead of showing "Microphone blocked" for a minute. A stop by Android under the restriction gets the same text. The setup checklist shows a warning for the battery step while the battery use is Restricted (not only "To do", which stays for the plain "Optimized" setting), and names Samsung's "Never sleeping apps" only on a Samsung phone (`Build.MANUFACTURER`); plan 2D checks that on the A71.

When the accessibility service disconnects while Kestrel's process runs (`onUnbind`, about 50 ms after the owner turned it off), Kestrel posts a notification: the service is off, so Kestrel cannot use the screen, the accessibility button or the shortcut; a tap opens Android's accessibility settings, where Kestrel's service is listed. Android's page of one service (`ACTION_ACCESSIBILITY_DETAILS_SETTINGS`) is not in the public SDK, and its activity needs a permission that only the system and the installer hold, so Kestrel cannot open it. If Kestrel is listening, it goes on listening (P2, Q5), so the text does not say that Kestrel is off. The notification goes when the service connects again. It has its own channel of low importance, without a sound, because the owner usually turned the service off on purpose, and the device tests turn it off and on many times. `AccessibilityManager.getEnabledAccessibilityServiceList` lists only bound services, so after "Stop app" the checklist said "To do" although Android's settings show the service as on; `SetupStatusReader` now also reads `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`, and when the service is listed there but not bound, the step shows a warning that asks the owner to turn it off and on again. No device test can produce that state, because "Stop app" would also end the test's process; the JVM test covers the step's state, and the device test of the reader checks the setting that it reads.

- [ ] **Step 1: Write the failing JVM tests**

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, add these three tests right before the test `aMicrophoneThatFailsStartsAgainAfterASecond`, by replacing

```kotlin
    @Test
    fun aMicrophoneThatFailsStartsAgainAfterASecond() = runTest {
```

with

```kotlin
    @Test
    fun aSilencedMicrophoneInADemotedServiceUnderTheBatteryRestrictionTurnsKestrelOff() = runTest {
        // Started from the tile without the accessibility service: about 30 s after the panel closed, Android took the
        // foreground from the service without a word, and 5 s later it silenced the microphone (prototype P2).
        conditions.restricted = true
        conditions.demoted = true
        val listening = listeningWithSession()
        log.clear()

        listening.send(Event.MicBusy(true))
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf("close", "closed", "stop"), log)
        // Not "Microphone blocked", which would stay until Android stops the service a minute later.
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.BATTERY_RESTRICTED), shown)
        assertEquals(listOf(StopReason.BATTERY_RESTRICTED), notices.shown)
    }

    @Test
    fun aSilencedMicrophoneIsOnlyShownUnlessTheServiceWasDemotedUnderTheRestriction() = runTest {
        // Demoted without the restriction first, and then restricted while the service keeps the foreground.
        conditions.demoted = true
        val listening = listeningWithSession()
        listening.send(Event.MicBusy(true))
        runCurrent()
        conditions.demoted = false
        conditions.restricted = true
        listening.send(Event.MicBusy(false))
        listening.send(Event.MicBusy(true))
        runCurrent()

        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.MIC_BUSY, ListeningTexts.MIC_BUSY), shown)
        assertTrue(notices.shown.isEmpty())
    }

    @Test
    fun aStopByAndroidUnderTheBatteryRestrictionSaysSo() = runTest {
        conditions.restricted = true
        val listening = listeningWithSession()

        listening.onServiceDestroyed(service)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(ListeningTexts.BATTERY_RESTRICTED, shown.last())
        assertEquals(listOf(StopReason.BATTERY_RESTRICTED), notices.shown)
    }

    @Test
    fun aMicrophoneThatFailsStartsAgainAfterASecond() = runTest {
```

Replace the fake of the conditions

```kotlin
    private class FakeConditions : ListeningConditions {
        var microphone = true

        override fun microphoneAllowed() = microphone
    }
```

with

```kotlin
    private class FakeConditions : ListeningConditions {
        var microphone = true
        var restricted = false
        var demoted = false

        override fun microphoneAllowed() = microphone

        override fun backgroundRestricted() = restricted

        override fun demoted(service: Any) = demoted
    }
```

In `app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt`, add these two tests right after the test `theServiceAndBatteryStepsAreDoneWhenSetUp`, by replacing

```kotlin
        assertEquals(StepState.DONE, states.getValue(SetupStep.ACCESSIBILITY_SERVICE))
        assertEquals(StepState.DONE, states.getValue(SetupStep.BATTERY))
    }
```

with

```kotlin
        assertEquals(StepState.DONE, states.getValue(SetupStep.ACCESSIBILITY_SERVICE))
        assertEquals(StepState.DONE, states.getValue(SetupStep.BATTERY))
    }

    @Test
    fun aServiceThatIsOnButDoesNotRunNeedsAttention() {
        fun state(status: SetupStatus) = SetupChecklist.states(status).getValue(SetupStep.ACCESSIBILITY_SERVICE)

        // As after "Stop app": Android's setting names the service, but Android does not run it (prototype P2).
        assertEquals(StepState.WARNING, state(nothingSetUp.copy(serviceEnabled = true)))
        assertEquals(StepState.DONE, state(nothingSetUp.copy(serviceOn = true, serviceEnabled = true)))
        assertEquals(StepState.TO_DO, state(nothingSetUp))
    }

    @Test
    fun restrictedBatteryUseNeedsAttention() {
        fun state(status: SetupStatus) = SetupChecklist.states(status).getValue(SetupStep.BATTERY)

        // Android then stops listening in the background, also when Kestrel ignores the battery optimisations.
        assertEquals(StepState.WARNING, state(nothingSetUp.copy(batteryRestricted = true)))
        assertEquals(StepState.WARNING, state(nothingSetUp.copy(batteryRestricted = true, batteryUnrestricted = true)))
        assertEquals(StepState.TO_DO, state(nothingSetUp))
    }
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.ListeningTest --tests dev.kestrel.app.settings.SetupChecklistTest`
Expected: `BUILD FAILED` in `:app:compileDebugUnitTestKotlin`, with `Unresolved reference` errors for `BATTERY_RESTRICTED`, `serviceEnabled` and `batteryRestricted`, and errors that `backgroundRestricted` and `demoted` override nothing.

- [ ] **Step 3: Detect the demotion in the controller**

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
    /** Whether the owner allowed Kestrel to record audio. */
    fun microphoneAllowed(): Boolean
}
```

with

```kotlin
    /** Whether the owner allowed Kestrel to record audio. */
    fun microphoneAllowed(): Boolean

    /** Whether the owner set Kestrel's battery use to Restricted (`ActivityManager.isBackgroundRestricted()`). */
    fun backgroundRestricted(): Boolean

    /**
     * Whether Android took the foreground from ListeningService's instance [service]: its foreground service type is 0.
     * Android does this without an exception or a callback (prototype P2).
     */
    fun demoted(service: Any): Boolean
}
```

Replace

```kotlin
    const val STOPPED_BY_ANDROID = "Android stopped Kestrel's listening."
```

with

```kotlin
    const val STOPPED_BY_ANDROID = "Android stopped Kestrel's listening."
    const val BATTERY_RESTRICTED = "Android stops Kestrel's listening in the background because its battery use is " +
        "Restricted. Set it to Unrestricted."
```

Replace

```kotlin
    /** Android stopped ListeningService on its own. */
    STOPPED_BY_ANDROID(ListeningTexts.STOPPED_BY_ANDROID, restart = true, starting = false),
}
```

with

```kotlin
    /** Android stopped ListeningService on its own. */
    STOPPED_BY_ANDROID(ListeningTexts.STOPPED_BY_ANDROID, restart = true, starting = false),

    /**
     * Android took the foreground from ListeningService, or stopped it, because Kestrel's battery use is Restricted.
     */
    BATTERY_RESTRICTED(ListeningTexts.BATTERY_RESTRICTED, restart = false, starting = false),
}
```

In `destroyed`, replace

```kotlin
        running = null
        stopBecause(StopReason.STOPPED_BY_ANDROID)
    }
```

with

```kotlin
        running = null
        // Under the battery restriction, Android stops the service a minute after it took its foreground (prototype
        // P2).
        stopBecause(if (conditions.backgroundRestricted()) StopReason.BATTERY_RESTRICTED else StopReason.STOPPED_BY_ANDROID)
    }
```

In `performUnguarded`, replace

```kotlin
            is Effect.Show -> {
                (effect.feedback as? Feedback.ModeChanged)?.let { noteMode(it.mode) }
                bar.show(text(effect.feedback))
            }
```

with

```kotlin
            is Effect.Show -> {
                (effect.feedback as? Feedback.ModeChanged)?.let { noteMode(it.mode) }
                if (effect.feedback == Feedback.MicBusy && demotedByBattery()) {
                    // Section 14.6: the microphone stays silenced, and Android stops the service a minute later. Only
                    // the first MicBusy asks: when something else already blocked the microphone, the bar says
                    // "Microphone blocked" until Android stops the service, and destroyed() then names the battery.
                    stopBecause(StopReason.BATTERY_RESTRICTED)
                } else {
                    bar.show(text(effect.feedback))
                }
            }
```

Replace

```kotlin
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun say(reply: Reply) {
```

with

```kotlin
    /**
     * Whether Android took the foreground from the running service because Kestrel's battery use is Restricted. Without
     * Kestrel's accessibility service, Android does this about 30 s after the Quick Settings panel closes, and the
     * first sign is the silenced microphone (prototype P2).
     */
    private fun demotedByBattery(): Boolean {
        val service = running ?: return false
        return conditions.demoted(service) && conditions.backgroundRestricted()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun say(reply: Reply) {
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
```

with

```kotlin
import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
```

Replace

```kotlin
            conditions = object : ListeningConditions {
                override fun microphoneAllowed() =
                    appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            },
```

with

```kotlin
            conditions = object : ListeningConditions {
                override fun microphoneAllowed() =
                    appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

                override fun backgroundRestricted() =
                    appContext.getSystemService(ActivityManager::class.java).isBackgroundRestricted

                // A binder call with the service's token, which any thread may make (prototype P2). The type is 0 while
                // the service is not in the foreground.
                override fun demoted(service: Any) = (service as ListeningService).foregroundServiceType == 0
            },
```

- [ ] **Step 4: Tell the new states apart in the checklist**

In `app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt`, replace

```kotlin
/** What Kestrel can find out about its own setup on the phone. [models] is what Kestrel knows about the model files. */
data class SetupStatus(
    val serviceOn: Boolean,
    val microphoneAllowed: Boolean,
    val notificationsAllowed: Boolean,
    val batteryUnrestricted: Boolean,
    val voiceAccessOn: Boolean,
    val models: ModelCheck = ModelCheck.Checking,
)
```

with

```kotlin
/**
 * What Kestrel can find out about its own setup on the phone. [serviceOn]: Kestrel's accessibility service runs.
 * [models] is what Kestrel knows about the model files. [serviceEnabled]: Android's setting names the accessibility
 * service, also when Android does not run it, as after "Stop app" (prototype P2). [batteryRestricted]: the owner set
 * Kestrel's battery use to Restricted. [samsung]: the phone is a Samsung, whose battery settings also have "Never
 * sleeping apps".
 */
data class SetupStatus(
    val serviceOn: Boolean,
    val microphoneAllowed: Boolean,
    val notificationsAllowed: Boolean,
    val batteryUnrestricted: Boolean,
    val voiceAccessOn: Boolean,
    val models: ModelCheck = ModelCheck.Checking,
    val serviceEnabled: Boolean = serviceOn,
    val batteryRestricted: Boolean = false,
    val samsung: Boolean = false,
)
```

Replace

```kotlin
            SetupStep.ACCESSIBILITY_SERVICE -> doneIf(status.serviceOn)
            SetupStep.MICROPHONE_AND_NOTIFICATIONS -> doneIf(status.microphoneAllowed && status.notificationsAllowed)
            SetupStep.BATTERY -> doneIf(status.batteryUnrestricted)
```

with

```kotlin
            SetupStep.ACCESSIBILITY_SERVICE -> when {
                status.serviceOn -> StepState.DONE
                // Android's setting names the service, but Android does not run it, as after "Stop app" (prototype P2).
                status.serviceEnabled -> StepState.WARNING
                else -> StepState.TO_DO
            }
            SetupStep.MICROPHONE_AND_NOTIFICATIONS -> doneIf(status.microphoneAllowed && status.notificationsAllowed)
            SetupStep.BATTERY -> when {
                // Android then stops listening in the background (prototype P2).
                status.batteryRestricted -> StepState.WARNING
                status.batteryUnrestricted -> StepState.DONE
                else -> StepState.TO_DO
            }
```

Replace the whole of `app/src/main/kotlin/dev/kestrel/app/settings/SetupStatusReader.kt` with:

```kotlin
package dev.kestrel.app.settings

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import dev.kestrel.app.KestrelAccessibilityService

/** Reads what Kestrel can find out about its own setup, for the setup checklist (design spec, section 15). */
class SetupStatusReader(private val context: Context) {
    fun read(): SetupStatus {
        val kestrel = ComponentName(context, KestrelAccessibilityService::class.java)
        // Only the services that Android runs.
        val services = context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .map { ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) }
        // Also the services that Android does not run, as after "Stop app" (prototype P2).
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty()
            .split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
        return SetupStatus(
            serviceOn = kestrel in services,
            microphoneAllowed = granted(Manifest.permission.RECORD_AUDIO),
            notificationsAllowed = granted(Manifest.permission.POST_NOTIFICATIONS),
            batteryUnrestricted = context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName),
            voiceAccessOn = services.any { it.packageName == VOICE_ACCESS },
            serviceEnabled = kestrel in enabled,
            batteryRestricted = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
            samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true),
        )
    }

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val VOICE_ACCESS = "com.google.android.apps.accessibility.voiceaccess"
    }
}
```

In `app/src/main/kotlin/dev/kestrel/app/settings/ChecklistPage.kt`, replace

```kotlin
/** The setup checklist (design spec, section 15). [models] is what Kestrel knows about the model files. */
@Composable
internal fun ChecklistPage(
    states: Map<SetupStep, StepState>,
    models: ModelCheck,
    onAction: (SetupAction) -> Unit,
    onBack: () -> Unit,
) {
    PageHeader(stringResource(R.string.settings_checklist), onBack)
    for (step in SetupStep.entries) {
        StepCard(step, states.getValue(step), onAction) {
            if (step == SetupStep.COPY_MODELS) ModelProblems(models)
        }
    }
}

@Composable
private fun StepCard(step: SetupStep, state: StepState, onAction: (SetupAction) -> Unit, details: @Composable () -> Unit) {
    val texts = texts(step)
```

with

```kotlin
/**
 * The setup checklist (design spec, section 15). [models] is what Kestrel knows about the model files. [samsung]: the
 * battery step also names Samsung's "Never sleeping apps".
 */
@Composable
internal fun ChecklistPage(
    states: Map<SetupStep, StepState>,
    models: ModelCheck,
    samsung: Boolean,
    onAction: (SetupAction) -> Unit,
    onBack: () -> Unit,
) {
    PageHeader(stringResource(R.string.settings_checklist), onBack)
    for (step in SetupStep.entries) {
        StepCard(step, states.getValue(step), samsung, onAction) {
            if (step == SetupStep.COPY_MODELS) ModelProblems(models)
        }
    }
}

@Composable
private fun StepCard(
    step: SetupStep,
    state: StepState,
    samsung: Boolean,
    onAction: (SetupAction) -> Unit,
    details: @Composable () -> Unit,
) {
    val texts = texts(step, samsung)
```

Replace

```kotlin
private fun texts(step: SetupStep): StepTexts = when (step) {
```

with

```kotlin
private fun texts(step: SetupStep, samsung: Boolean): StepTexts = when (step) {
```

Replace

```kotlin
    SetupStep.ACCESSIBILITY_SERVICE -> StepTexts(
        R.string.setup_service_title,
        R.string.setup_service_detail,
        SetupAction.ACCESSIBILITY_SETTINGS,
        R.string.setup_open_accessibility,
    )
```

with

```kotlin
    SetupStep.ACCESSIBILITY_SERVICE -> StepTexts(
        R.string.setup_service_title,
        R.string.setup_service_detail,
        SetupAction.ACCESSIBILITY_SETTINGS,
        R.string.setup_open_accessibility,
        R.string.setup_service_warning,
    )
```

Replace

```kotlin
    SetupStep.BATTERY -> StepTexts(
        R.string.setup_battery_title,
        R.string.setup_battery_detail,
        SetupAction.APP_SETTINGS,
        R.string.setup_open_app_settings,
    )
```

with

```kotlin
    SetupStep.BATTERY -> StepTexts(
        R.string.setup_battery_title,
        // Only Samsung's battery settings have "Never sleeping apps" (section 14.6).
        if (samsung) R.string.setup_battery_detail_samsung else R.string.setup_battery_detail,
        SetupAction.APP_SETTINGS,
        R.string.setup_open_app_settings,
        R.string.setup_battery_warning,
    )
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt`, replace

```kotlin
            SettingsPage.CHECKLIST -> ScrollingPage { ChecklistPage(states, status.models, onAction, home) }
```

with

```kotlin
            SettingsPage.CHECKLIST -> ScrollingPage { ChecklistPage(states, status.models, status.samsung, onAction, home) }
```

In `app/src/main/res/values/strings.xml`, replace

```xml
    <string name="setup_service_detail">Kestrel reads the screen and acts on it through this service.</string>
```

with

```xml
    <string name="setup_service_detail">Kestrel reads the screen and acts on it through this service.</string>
    <string name="setup_service_warning">Android lists Kestrel\'s accessibility service as on, but the service does not run, for example after Kestrel was stopped. Turn it off and on again.</string>
```

Replace

```xml
    <string name="setup_battery_detail">So that Android does not stop Kestrel. On a Samsung phone, also add Kestrel to \"Never sleeping apps\" in the battery settings.</string>
```

with

```xml
    <string name="setup_battery_detail">So that Android does not stop Kestrel\'s listening in the background.</string>
    <string name="setup_battery_detail_samsung">So that Android does not stop Kestrel\'s listening in the background. Also add Kestrel to \"Never sleeping apps\" in the battery settings.</string>
    <string name="setup_battery_warning">Kestrel\'s battery use is Restricted, so Android stops its listening in the background. Set it to Unrestricted.</string>
```

Replace

```xml
    <string name="problem_start">Start listening</string>
```

with

```xml
    <string name="problem_start">Start listening</string>
    <string name="accessibility_channel">Accessibility service</string>
    <string name="problem_service_off_title">Kestrel\'s accessibility service is off</string>
    <string name="problem_service_off_text">Kestrel cannot use the screen, the accessibility button or the shortcut until you turn the service on again. If Kestrel is listening, it goes on listening. Tap to open the accessibility settings.</string>
```

- [ ] **Step 5: Post the notification about the accessibility service, and open Android's accessibility settings from it**

In `app/src/main/kotlin/dev/kestrel/app/listen/ProblemNotifications.kt`, replace

```kotlin
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import dev.kestrel.app.R
```

with

```kotlin
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import dev.kestrel.app.R
```

Replace

```kotlin
    override fun clear() = notifications.cancel(STOPPED_ID)
```

with

```kotlin
    override fun clear() = notifications.cancel(STOPPED_ID)

    /**
     * Kestrel's accessibility service disconnected, for example because the owner turned it off. Kestrel cannot use the
     * screen, the accessibility button or the shortcut until the service is on again; if Kestrel is listening, it goes
     * on listening (prototype P2). A tap opens Android's accessibility settings, where the owner turns the service on.
     * Android's page of one service (ACTION_ACCESSIBILITY_DETAILS_SETTINGS) is a system API, and its activity needs a
     * permission that only the system and the installer hold. The channel makes no sound, because the owner usually
     * turned the service off on purpose.
     */
    fun accessibilityServiceOff() {
        if (!allowed()) return
        notifications.createNotificationChannel(
            NotificationChannel(ACCESSIBILITY, context.getString(R.string.accessibility_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val text = context.getString(R.string.problem_service_off_text)
        val settings = PendingIntent.getActivity(
            context,
            0,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, ACCESSIBILITY)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.problem_service_off_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(settings)
            .build()
        notifications.notify(ACCESSIBILITY_ID, notification)
    }

    /** Kestrel's accessibility service connected, so the notification of [accessibilityServiceOff] goes. */
    fun accessibilityServiceOn() = notifications.cancel(ACCESSIBILITY_ID)
```

Replace

```kotlin
        internal const val STOPPED_ID = 7_102
        private const val PROBLEMS = "problems"
```

with

```kotlin
        internal const val STOPPED_ID = 7_102

        /** The notification that says that Kestrel's accessibility service is off. */
        internal const val ACCESSIBILITY_ID = 7_103
        private const val PROBLEMS = "problems"
        private const val ACCESSIBILITY = "accessibility"
```

In `app/src/main/kotlin/dev/kestrel/app/KestrelAccessibilityService.kt`, replace

```kotlin
 * accessibility shortcut toggle listening through it (section 8.6). Android calls every callback on the main thread.
 */
```

with

```kotlin
 * accessibility shortcut toggle listening through it (section 8.6). While it is off in a running process, a
 * notification says so (section 14.6). Android calls every callback on the main thread.
 */
```

Replace

```kotlin
    override fun onServiceConnected() {
        val connected = ScreenSession(this, graph.errors)
```

with

```kotlin
    override fun onServiceConnected() {
        // The notification that said that this service was off goes (section 14.6).
        graph.problems.accessibilityServiceOn()
        val connected = ScreenSession(this, graph.errors)
```

Replace

```kotlin
    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }
```

with

```kotlin
    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        // Section 14.6: the screen, the button and the shortcut need this service; if Kestrel is listening, it goes on
        // listening. Android calls this about 50 ms after the owner turned the service off (prototype P2), and
        // onDestroy right after it. The notice comes after disconnect(), so that the screen session is detached first.
        graph.problems.accessibilityServiceOff()
        return super.onUnbind(intent)
    }
```

- [ ] **Step 6: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.ListeningTest --tests dev.kestrel.app.settings.SetupChecklistTest`
Expected: `BUILD SUCCESSFUL`, with 75 tests in `ListeningTest` (72 and the 3 new ones) and 9 in `SetupChecklistTest` (7 and the 2 new ones).

- [ ] **Step 7: Write the device tests of the checklist, the reader and the notification**

In `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, in the test `stepsToDoOfferTheirActions`, which shows a phone that is not a Samsung, replace

```kotlin
        show(NOTHING_SET_UP)
        openChecklist()

        compose.onNodeWithText(text(R.string.setup_allow)).performScrollTo().performClick()
```

with

```kotlin
        show(NOTHING_SET_UP)
        openChecklist()
        compose.onNodeWithText(text(R.string.setup_battery_detail)).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.setup_battery_detail_samsung)).assertCountEquals(0)

        compose.onNodeWithText(text(R.string.setup_allow)).performScrollTo().performClick()
```

Add these two tests right after the test `voiceAccessBesideKestrelShowsTheWarning`, by replacing

```kotlin
        compose.onNodeWithText(text(R.string.setup_voice_access_warning)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setup_open_accessibility)).performScrollTo().performClick()
        assertEquals(listOf(SetupAction.ACCESSIBILITY_SETTINGS), actions)
    }
```

with

```kotlin
        compose.onNodeWithText(text(R.string.setup_voice_access_warning)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setup_open_accessibility)).performScrollTo().performClick()
        assertEquals(listOf(SetupAction.ACCESSIBILITY_SETTINGS), actions)
    }

    @Test
    fun stepsThatNeedAttentionSayWhy() {
        show(NOTHING_SET_UP.copy(serviceEnabled = true, batteryRestricted = true))
        openChecklist()

        compose.onNodeWithText(text(R.string.setup_service_warning)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setup_battery_warning)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theBatteryStepNamesNeverSleepingAppsOnlyOnASamsung() {
        show(NOTHING_SET_UP.copy(samsung = true))
        openChecklist()

        compose.onNodeWithText(text(R.string.setup_battery_detail_samsung)).performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.setup_battery_detail)).assertCountEquals(0)
    }
```

In `app/src/androidTest/kotlin/dev/kestrel/app/settings/SetupStatusReaderTest.kt`, replace

```kotlin
import dev.kestrel.app.screen.ScreenServiceRule
```

with

```kotlin
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.voice.VoiceTests
```

Replace

```kotlin
    @Test
    fun seesTheServiceTurnOff() = runBlocking {
        assertTrue(reader.read().serviceOn)

        screen.shell("settings delete secure enabled_accessibility_services")
        withTimeout(10_000) { (context.applicationContext as KestrelApplication).graph.screen.first { it == null } }

        assertFalse(reader.read().serviceOn)
    }
```

with

```kotlin
    @Test
    fun seesTheServiceTurnOff() = runBlocking {
        val on = reader.read()
        assertTrue(on.serviceOn)
        assertTrue(on.serviceEnabled)

        screen.shell("settings delete secure enabled_accessibility_services")
        withTimeout(10_000) { (context.applicationContext as KestrelApplication).graph.screen.first { it == null } }

        val off = reader.read()
        assertFalse(off.serviceOn)
        assertFalse(off.serviceEnabled)
    }

    @Test
    fun seesRestrictedBatteryUse() {
        // On a phone, a test process that died between the two commands would leave Kestrel restricted, and no script
        // puts the op back. Before the try block, so that a phone runs no command.
        VoiceTests.assumeEmulator()
        try {
            // What the battery setting "Restricted" sets (prototype P2).
            screen.shell("cmd appops set ${context.packageName} RUN_ANY_IN_BACKGROUND ignore")
            assertTrue(reader.read().batteryRestricted)

            // The op's normal mode; "default" would still count as restricted (prototype P2).
            screen.shell("cmd appops set ${context.packageName} RUN_ANY_IN_BACKGROUND allow")
            assertFalse(reader.read().batteryRestricted)
        } finally {
            screen.shell("cmd appops set ${context.packageName} RUN_ANY_IN_BACKGROUND allow")
        }
    }
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/AccessibilityNoticeTest.kt`:

```kotlin
package dev.kestrel.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.listen.ProblemNotifications
import dev.kestrel.app.screen.ScreenServiceRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

/**
 * The notification that says that Kestrel's accessibility service is off (design spec, section 14.6). The test grants
 * the notification permission, which stays granted, as the voice tests leave it.
 */
class AccessibilityNoticeTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph = (context.applicationContext as KestrelApplication).graph
    private val notifications = context.getSystemService(NotificationManager::class.java)

    @Test
    fun aNotificationSaysThatTheServiceIsOffUntilItConnectsAgain() = runBlocking {
        screen.automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        try {
            screen.shell("settings delete secure enabled_accessibility_services")
            withTimeout(10_000) { graph.screen.first { it == null } }

            val notice = withTimeoutOrNull(5_000) {
                var found: Notification? = null
                while (found == null) {
                    found = notifications.activeNotifications.firstOrNull { it.id == ProblemNotifications.ACCESSIBILITY_ID }
                        ?.notification
                    if (found == null) delay(50)
                }
                found
            }
            assertNotNull("No notification says that the accessibility service is off", notice)
            assertEquals(
                context.getString(R.string.problem_service_off_title),
                notice!!.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            )

            screen.shell("settings put secure enabled_accessibility_services $SERVICE")
            screen.shell("settings put secure accessibility_enabled 1")
            withTimeout(10_000) { graph.screen.filterNotNull().first() }
            val gone = withTimeoutOrNull(5_000) {
                while (notifications.activeNotifications.any { it.id == ProblemNotifications.ACCESSIBILITY_ID }) delay(50)
                true
            }
            assertEquals("The notification is still there after the service connected again", true, gone)
        } finally {
            // Also after a failure, so that the next test class finds the service on.
            screen.shell("settings put secure enabled_accessibility_services $SERVICE")
            screen.shell("settings put secure accessibility_enabled 1")
            notifications.cancel(ProblemNotifications.ACCESSIBILITY_ID)
        }
    }

    private companion object {
        const val SERVICE = "dev.kestrel/dev.kestrel.app.KestrelAccessibilityService"
    }
}
```

- [ ] **Step 8: Update the spec and the README**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 14.6, replace

```markdown
| The accessibility service is turned off or killed | A persistent notification says "Kestrel is off" and opens the settings page with one tap |
| Samsung battery restrictions are detected | The setup checklist asks for unrestricted battery use and "Never sleeping apps" |
```

with

```markdown
| Kestrel's accessibility service is turned off | A notification says that the service is off, so Kestrel cannot use the screen, the accessibility button or the shortcut, and a tap opens Android's accessibility settings. If Kestrel is listening, it goes on listening, because the listening service does not need the accessibility service (prototype P2). The notification makes no sound, and it goes when the service connects again |
| Android lists the accessibility service as on but does not run it, as after "Stop app" | The setup checklist asks the owner to turn the service off and on again |
| Kestrel's battery use is Restricted | The setup checklist shows a warning. Without the accessibility service, Android takes the foreground from the listening service about 30 s after the Quick Settings panel closes and silences the microphone 5 s later (prototype P2); Kestrel then turns off at once and says why, and it says the same when Android stops the service |
| A Samsung phone | The setup checklist also asks for "Never sleeping apps", which Kestrel cannot read |
```

In section 15, replace

```markdown
3. Turn on Kestrel's accessibility service.
```

with

```markdown
3. Turn on Kestrel's accessibility service. When Android lists it as on but does not run it, as after "Stop app", the checklist asks the owner to turn it off and on again.
```

and replace

```markdown
5. Allow unrestricted battery use, and add Kestrel to Samsung's "Never sleeping apps".
```

with

```markdown
5. Allow unrestricted battery use. The checklist shows a warning while the battery use is Restricted, because Android then stops listening in the background (section 14.6). On a Samsung phone, the step also asks for "Never sleeping apps", which Kestrel cannot read.
```

In `README.md`, replace

```markdown
3. Turn on Kestrel's accessibility service. The step's button opens Android's accessibility settings.
```

with

```markdown
3. Turn on Kestrel's accessibility service. The step's button opens Android's accessibility settings. When Android lists the service as on but does not run it, for example after "Stop app", the step asks you to turn it off and on again. When the service goes off while Kestrel runs, for example because you turned it off, a notification says so until the service is on again, and a tap on it opens Android's accessibility settings.
```

Replace

```markdown
5. Allow unrestricted battery use. The step's button opens Kestrel's app settings, where the battery setting is. On a Samsung phone, also add Kestrel to "Never sleeping apps" in the battery settings.
```

with

```markdown
5. Allow unrestricted battery use. The step's button opens Kestrel's app settings, where the battery setting is. While Kestrel's battery use is Restricted, the step shows a warning, because Android then stops listening in the background. On a Samsung phone, also add Kestrel to "Never sleeping apps" in the battery settings; the step names it only there.
```

Replace

```markdown
while a virtual touchscreen behaves like a finger.
```

with

```markdown
while a virtual touchscreen behaves like a finger. On an emulator, `SetupStatusReaderTest` sets Kestrel's app op `RUN_ANY_IN_BACKGROUND` to `ignore`, as the battery setting "Restricted" does, and back to `allow`, the op's normal mode; `default` would leave Kestrel restricted.
```

- [ ] **Step 9: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (165 in `:app`, 116 in `:core:voice`), lint in every module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests compile.

- [ ] **Step 10: Run the voice device tests, which also install the current debug build**

`ActivationTest` turns the accessibility service off after each test, so Kestrel now posts the notification about it; `connected-test.ps1` force-stops Kestrel at the end, which removes Kestrel's notifications.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 65 run, 0 failed, 0 errors, 0 skipped."

- [ ] **Step 11: Run the device tests of the checklist, the reader and the notification**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.settings.SettingsAppTest,dev.kestrel.app.settings.SetupStatusReaderTest,dev.kestrel.app.AccessibilityNoticeTest,dev.kestrel.app.MainActivityTest' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' --no-configuration-cache`
Expected: `BUILD SUCCESSFUL`, and the result files in `app\build\outputs\androidTest-results\connected\debug\` show 26 tests and 0 failures: `SettingsAppTest` 17 (15 and the 2 new ones), `SetupStatusReaderTest` 4 (3 and `seesRestrictedBatteryUse`), `AccessibilityNoticeTest` 1 and `MainActivityTest` 4. Afterwards check that the app op is back: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; & (Get-Adb) -s emulator-5554 shell cmd appops get dev.kestrel RUN_ANY_IN_BACKGROUND` prints `RUN_ANY_IN_BACKGROUND: allow` (possibly with a time after it).

- [ ] **Step 12: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 13: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/main/kotlin/dev/kestrel/app/listen/ProblemNotifications.kt app/src/main/kotlin/dev/kestrel/app/KestrelAccessibilityService.kt app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt app/src/main/kotlin/dev/kestrel/app/settings/SetupStatusReader.kt app/src/main/kotlin/dev/kestrel/app/settings/ChecklistPage.kt app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt app/src/main/res/values/strings.xml app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt app/src/androidTest/kotlin/dev/kestrel/app/settings/SetupStatusReaderTest.kt app/src/androidTest/kotlin/dev/kestrel/app/AccessibilityNoticeTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md README.md
git commit -m "feat(app): turn off with a reason when the battery restriction takes the service's foreground, post a notification while the accessibility service is off, and show both in the checklist" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: The restore and the exit records

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/listen/ExitRecords.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/ListeningSetting.kt` (whole file below)
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt` (`ModeMemory`, new `StoredMode`, `ListeningTexts`, `StopReason`, the class's KDoc and constructor, `Restore`, `restoredAt`, the mode's writer, `onAccessibilityServiceConnected`, new `restoreAfter`, `noteMode`, the companion)
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (imports, `memory`, `exits`, `clock`)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 8.5, 13.5 and 14.6), `README.md` (the paragraph on listening)
- Test: `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`

**Interfaces:**
- Consumes: `ModeMemory`, `onAccessibilityServiceConnected`, `restoreChecked`, `wasOn` and `noteMode`, which exist already; `stopBecause(reason, notify)` and `StopNotices` from Task 2; `StopReason.BATTERY_RESTRICTED` and `ListeningTexts.BATTERY_RESTRICTED` from Task 4, after which the new reason and text go; `SettingKeys.LISTENING`, `SettingsStore.get` and `set`.
- Produces: `data class ListeningSetting(val mode: Mode = Mode.OFF, val writtenAt: Long? = null, val restoredAt: Long? = null)`; `internal data class StoredMode(val mode: Mode, val writtenAt: Long? = null, val restoredAt: Long? = null)`; `ModeMemory.read(): StoredMode` and `ModeMemory.write(mode: Mode, writtenAt: Long, restoredAt: Long?)`; `internal enum class ExitReason { OWNER_STOPPED, CRASH, OTHER }`, `internal data class ExitRecord(val reason: ExitReason, val timestamp: Long)`, `internal fun interface ExitRecords { suspend fun newest(): ExitRecord? }`, `internal class AndroidExitRecords(context: Context) : ExitRecords`; the constructor parameters `exits: ExitRecords` and `clock: () -> Long`; `StopReason.CRASHED_AFTER_RESTORE` and `ListeningTexts.CRASHED_AFTER_RESTORE`.

Plan 2C-2's "Not in this plan", prototype P2, Q4, and the final review of plan 2C-2. The restore brought Kestrel back after every end of its process, also after the owner stopped it (a force stop, "Stop app", or Stop in "Active apps"), and after a crash just after the voice session opened, which could repeat after each crash. Now the stored mode keeps the wall-clock time of its write (`writtenAt`) and the time at which the last restore turned Kestrel on (`restoredAt`), and the restore reads the newest exit record of Kestrel's package before its write of Off:

- A record that is older than the stored mode's write belongs to an older process, because a reboot leaves no record of its own, so it does not count. A value that plan 2C-2 stored has no times (`SettingsStore` reads it with the new fields' defaults), so its newest record decides.
- `REASON_USER_REQUESTED` (`OWNER_STOPPED`): the owner stopped Kestrel, so it stays off and stores Off. `REASON_USER_STOPPED` is not the owner's stop: Android stopped the user profile that the process ran in, so it counts as `OTHER`. On Android 13, an update of Kestrel also ends its process with `REASON_USER_REQUESTED`, so there Kestrel stays off after an update; plan 2D handles that (see "Not in this plan").
- `REASON_CRASH` or `REASON_CRASH_NATIVE` (`CRASH`) less than 2 minutes after the last restore: Kestrel came back and crashed, so it stays off, stores Off, and says why on the bar and in the problems notification, with "Start listening".
- Anything else (a crash 2 minutes or more after the restore, a crash after the owner's own start, a low-memory kill, a package update on Android 14 and later, no record, or records that cannot be read): it restores as before.
- A stored Off with the time of a restore: that restore turned Kestrel on, and its process ended before the voice session opened, for example with a native crash while the models loaded. Kestrel stays off, as "Off first" intends, and reads the record only to say why: after a crash less than 2 minutes after the restore, the bar and the problems notification say so. It then stores Off without the time, so that it says it once. An Off without that time is the owner's, or a restore's decision to stay off, and the restore does nothing more.

`restoredAt` lives in the process from the restore's start until Kestrel next turns off, and goes with every write of the mode, so that a crash in the restored process finds it. An Off clears it: a start after an Off is the owner's, so a crash after it is not one soon after a restore; `listen-check.ps1`'s restart check relies on this, because it turns Kestrel off and on before its `am crash`. The restore still sets `restoreChecked` before its first suspension, and checks `wasOn` and the mode again after each suspension, also after the read of the exit record. `AndroidExitRecords` reads only the record's reason and time, never its description.

- [ ] **Step 1: Write the failing tests**

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace

```kotlin
    private val memory = FakeMemory()
```

with

```kotlin
    private val memory = FakeMemory()
    private val exits = FakeExits()

    // The wall clock, in milliseconds.
    private var now = 1_000_000L
```

Add these twelve tests right after the test `aStoredModeThatCannotBeReadIsReported`, by replacing

```kotlin
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals("Reading the listening mode", reports.single().first)
    }
```

with

```kotlin
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals("Reading the listening mode", reports.single().first)
    }

    @Test
    fun kestrelStaysOffAfterTheOwnerStoppedIt() = runTest {
        // A force stop, "Stop app" or Stop in "Active apps" ended the process that stored Listening (prototype P2).
        memory.stored = Mode.LISTENING
        memory.writtenAt = 500_000
        exits.record = ExitRecord(ExitReason.OWNER_STOPPED, timestamp = 600_000)
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertTrue(log.isEmpty())
        assertEquals(listOf(Mode.OFF), memory.writes)
        assertTrue(notices.shown.isEmpty())
    }

    @Test
    fun aCrashSoonAfterARestoreKeepsKestrelOffAndSaysSo() = runTest {
        memory.stored = Mode.LISTENING
        memory.restoredAt = 500_000
        memory.writtenAt = 510_000
        // 100 s after the restore.
        exits.record = ExitRecord(ExitReason.CRASH, timestamp = 600_000)
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertTrue(log.isEmpty())
        assertEquals(listOf(Mode.OFF), memory.writes)
        assertEquals(listOf(ListeningTexts.CRASHED_AFTER_RESTORE), shown)
        assertEquals(listOf(StopReason.CRASHED_AFTER_RESTORE), notices.shown)
    }

    @Test
    fun aCrashBeforeTheRestoredSessionOpenedKeepsKestrelOffAndSaysSo() = runTest {
        // The last restore stored Off and turned Kestrel on, and a native crash ended the process while the models
        // loaded, so Listening was never stored again.
        memory.stored = Mode.OFF
        memory.writtenAt = 500_000
        memory.restoredAt = 500_000
        exits.record = ExitRecord(ExitReason.CRASH, timestamp = 530_000)
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertTrue(log.isEmpty())
        assertEquals(listOf(StopReason.CRASHED_AFTER_RESTORE), notices.shown)
        // Said once: the next process finds an Off without the time of a restore.
        assertEquals(null, memory.restoredAt)
    }

    @Test
    fun aRestoredProcessThatEndedWithoutACrashBeforeItsSessionOpenedStaysOff() = runTest {
        // The last restore stored Off and turned Kestrel on, and Android ended the process while the models loaded, for
        // example with the low-memory killer. "Off first" keeps Kestrel off, and there is nothing to say.
        memory.stored = Mode.OFF
        memory.writtenAt = 500_000
        memory.restoredAt = 500_000
        exits.record = ExitRecord(ExitReason.OTHER, timestamp = 530_000)
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertTrue(log.isEmpty())
        assertTrue(shown.isEmpty())
        assertTrue(notices.shown.isEmpty())
        assertEquals(null, memory.restoredAt)
    }

    @Test
    fun aCrashTwoMinutesAfterARestoreRestoresListening() = runTest {
        memory.stored = Mode.LISTENING
        memory.restoredAt = 500_000
        memory.writtenAt = 510_000
        exits.record = ExitRecord(ExitReason.CRASH, timestamp = 620_000)
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertEquals(listOf("start"), log)
    }

    @Test
    fun aCrashAfterTheOwnersOwnStartRestoresListening() = runTest {
        // The owner turned Kestrel off and on after the last restore, which cleared its time; listen-check.ps1's
        // restart check does this before its 'am crash'.
        memory.stored = Mode.LISTENING
        memory.writtenAt = 510_000
        exits.record = ExitRecord(ExitReason.CRASH, timestamp = 520_000)
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertEquals(listOf("start"), log)
        assertTrue(notices.shown.isEmpty())
    }

    @Test
    fun otherEndsOfTheProcessRestoreListening() = runTest {
        // Such as a low-memory kill or a package update.
        memory.stored = Mode.LISTENING
        memory.writtenAt = 500_000
        exits.record = ExitRecord(ExitReason.OTHER, timestamp = 600_000)
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertEquals(listOf("start"), log)
    }

    @Test
    fun aRecordFromBeforeTheStoredModeIsIgnoredAsAfterAReboot() = runTest {
        // A reboot leaves no record of its own, so the newest one belongs to an older process.
        memory.stored = Mode.PAUSED
        memory.writtenAt = 500_000
        exits.record = ExitRecord(ExitReason.OWNER_STOPPED, timestamp = 400_000)
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.PAUSED, listening.state.value.mode)
        assertEquals(listOf("start"), log)
    }

    @Test
    fun aModeWithoutATimeLetsTheNewestRecordDecide() = runTest {
        // Stored by plan 2C-2's version, which kept no times.
        memory.stored = Mode.LISTENING
        exits.record = ExitRecord(ExitReason.OWNER_STOPPED, timestamp = 400_000)
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertTrue(log.isEmpty())
    }

    @Test
    fun exitRecordsThatCannotBeReadDoNotStopTheRestore() = runTest {
        memory.stored = Mode.LISTENING
        exits.failure = SecurityException("No access to the records")
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertEquals("Reading the exit records", reports.single().first)
    }

    @Test
    fun theOwnersChoiceWhileTheExitRecordsAreReadIsKept() = runTest {
        memory.stored = Mode.LISTENING
        exits.reading = CompletableDeferred()
        val listening = listening()
        listening.onAccessibilityServiceConnected()
        runCurrent()

        // While the record is read, the owner turns Kestrel on and off again.
        listening.send(Event.Toggle)
        runCurrent()
        listening.send(Event.TurnOff)
        runCurrent()
        exits.reading?.complete(Unit)
        runCurrent()

        // Only the owner's Off was stored; the restore wrote nothing and started nothing more.
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf(Mode.OFF), memory.writes)
        assertEquals(listOf("start"), log)
    }

    @Test
    fun aRestoreKeepsItsTimeUntilKestrelTurnsOff() = runTest {
        memory.stored = Mode.LISTENING
        now = 700_000
        val listening = listening()
        listening.onAccessibilityServiceConnected()
        runCurrent()
        assertEquals(700_000L, memory.restoredAt)

        now = 705_000
        listening.onForeground(service)
        runCurrent()
        // Listening, stored once the session is open, keeps the time of the restore for the rule about crashes.
        assertEquals(listOf(Mode.OFF, Mode.LISTENING), memory.writes)
        assertEquals(705_000L, memory.writtenAt)
        assertEquals(700_000L, memory.restoredAt)

        // A start after an Off is the owner's, so a crash after it is not one soon after a restore.
        listening.send(Event.TurnOff)
        runCurrent()
        assertEquals(Mode.OFF, memory.stored)
        assertEquals(null, memory.restoredAt)
    }
```

In the helper `listening`, replace

```kotlin
        notices = notices,
        microphones = { microphone },
    )
```

with

```kotlin
        notices = notices,
        exits = exits,
        clock = { now },
        microphones = { microphone },
    )
```

In the test `anErrorWhileStoringTheModeIsReportedAndLaterModesAreStillStored`, replace

```kotlin
            notices = notices,
            // Its first write throws an Error, and the later ones work.
            memory = object : ModeMemory {
                override suspend fun read(): Mode = Mode.OFF

                override suspend fun write(mode: Mode) {
```

with

```kotlin
            notices = notices,
            exits = exits,
            clock = { now },
            // Its first write throws an Error, and the later ones work.
            memory = object : ModeMemory {
                override suspend fun read(): StoredMode = StoredMode(Mode.OFF)

                override suspend fun write(mode: Mode, writtenAt: Long, restoredAt: Long?) {
```

Replace the fake of the memory

```kotlin
    private class FakeMemory : ModeMemory {
        var stored = Mode.OFF
        var reads = 0
        var readFailure: Exception? = null
        var reading: CompletableDeferred<Unit>? = null
        var writeFailure: Exception? = null
        var writing: CompletableDeferred<Unit>? = null
        val writes = mutableListOf<Mode>()

        override suspend fun read(): Mode {
            reads++
            reading?.await()
            readFailure?.let { throw it }
            return stored
        }

        override suspend fun write(mode: Mode) {
            writing?.await()
            writeFailure?.let { throw it }
            writes += mode
            stored = mode
        }
    }
```

with

```kotlin
    private class FakeMemory : ModeMemory {
        var stored = Mode.OFF
        var writtenAt: Long? = null
        var restoredAt: Long? = null
        var reads = 0
        var readFailure: Exception? = null
        var reading: CompletableDeferred<Unit>? = null
        var writeFailure: Exception? = null
        var writing: CompletableDeferred<Unit>? = null
        val writes = mutableListOf<Mode>()

        override suspend fun read(): StoredMode {
            reads++
            reading?.await()
            readFailure?.let { throw it }
            return StoredMode(stored, writtenAt, restoredAt)
        }

        override suspend fun write(mode: Mode, writtenAt: Long, restoredAt: Long?) {
            writing?.await()
            writeFailure?.let { throw it }
            writes += mode
            stored = mode
            this.writtenAt = writtenAt
            this.restoredAt = restoredAt
        }
    }

    private class FakeExits : ExitRecords {
        var record: ExitRecord? = null
        var failure: Exception? = null
        var reading: CompletableDeferred<Unit>? = null

        override suspend fun newest(): ExitRecord? {
            reading?.await()
            failure?.let { throw it }
            return record
        }
    }
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD FAILED` in `:app:compileDebugUnitTestKotlin`, with `Unresolved reference` errors for `ExitRecord`, `ExitReason`, `ExitRecords`, `StoredMode` and `CRASHED_AFTER_RESTORE`, and errors that `Listening` has no parameters `exits` and `clock`.

- [ ] **Step 3: Read the exit records**

Create `app/src/main/kotlin/dev/kestrel/app/listen/ExitRecords.kt`:

```kotlin
package dev.kestrel.app.listen

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How a process of Kestrel's ended, as far as the restore needs to know (design spec, section 8.5). */
internal enum class ExitReason {
    /**
     * The owner stopped Kestrel: a force stop, "Stop app", or Stop in "Active apps" (prototype P2). On Android 13, an
     * update of Kestrel gives the same reason.
     */
    OWNER_STOPPED,

    /** Kestrel crashed, in its own code or in a native library. */
    CRASH,

    /** Anything else, such as a low-memory kill or a package update. */
    OTHER,
}

/** The end of a process of Kestrel's: [reason], at [timestamp] in wall-clock milliseconds. */
internal data class ExitRecord(val reason: ExitReason, val timestamp: Long)

/** Reads Android's records of how Kestrel's processes ended. */
internal fun interface ExitRecords {
    /** The newest record, or null when Android has none. A reboot leaves no record of its own. */
    suspend fun newest(): ExitRecord?
}

/**
 * Reads `ActivityManager.getHistoricalProcessExitReasons()` on [Dispatchers.IO]. It reads only the reason and the time,
 * never the record's description, which can hold the text of an exception (section 13.5).
 */
internal class AndroidExitRecords(private val context: Context) : ExitRecords {
    override suspend fun newest(): ExitRecord? = withContext(Dispatchers.IO) {
        context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 1)
            .firstOrNull()
            ?.let { ExitRecord(reasonOf(it.reason), it.timestamp) }
    }

    private fun reasonOf(reason: Int): ExitReason = when (reason) {
        // A force stop, "Stop app" and Stop in "Active apps" (prototype P2). REASON_USER_STOPPED is not the owner's:
        // Android stopped the user profile that the process ran in.
        ApplicationExitInfo.REASON_USER_REQUESTED -> ExitReason.OWNER_STOPPED
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> ExitReason.CRASH
        else -> ExitReason.OTHER
    }
}
```

Replace the whole of `app/src/main/kotlin/dev/kestrel/app/listen/ListeningSetting.kt` with:

```kotlin
package dev.kestrel.app.listen

import dev.kestrel.app.orchestrator.Mode
import kotlinx.serialization.Serializable

/**
 * The listening mode that Kestrel had last, which it turns on in again after its process restarts (design spec,
 * sections 8.5 and 14.4), with the wall-clock times in milliseconds at which it was written ([writtenAt]) and at which
 * the last restore turned Kestrel on ([restoredAt]). A fresh install, and a value that this version cannot read, give
 * Off. A value that an earlier version stored has no times.
 */
@Serializable
data class ListeningSetting(val mode: Mode = Mode.OFF, val writtenAt: Long? = null, val restoredAt: Long? = null)
```

- [ ] **Step 4: Decide the restore by the newest record**

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
/** Keeps Kestrel's listening mode across restarts of its process (design spec, sections 8.5 and 14.4). */
internal interface ModeMemory {
    /** The mode that Kestrel stored last, or [Mode.OFF] when it stored none. */
    suspend fun read(): Mode

    suspend fun write(mode: Mode)
}
```

with

```kotlin
/** A mode that Kestrel stored, with the wall-clock times of [ListeningSetting]; a time is null when it is not known. */
internal data class StoredMode(val mode: Mode, val writtenAt: Long? = null, val restoredAt: Long? = null)

/** Keeps Kestrel's listening mode across restarts of its process (design spec, sections 8.5 and 14.4). */
internal interface ModeMemory {
    /** The mode that Kestrel stored last, or [Mode.OFF] without times when it stored none. */
    suspend fun read(): StoredMode

    /**
     * Stores [mode], written at [writtenAt], with [restoredAt], the time at which the last restore turned Kestrel
     * on.
     */
    suspend fun write(mode: Mode, writtenAt: Long, restoredAt: Long?)
}
```

Replace

```kotlin
    const val CANNOT_OPEN_SETTINGS = "Kestrel could not open its settings."
```

with

```kotlin
    const val CRASHED_AFTER_RESTORE = "Kestrel stopped soon after it came back, so it stays off."
    const val CANNOT_OPEN_SETTINGS = "Kestrel could not open its settings."
```

Replace

```kotlin
    /**
     * Android took the foreground from ListeningService, or stopped it, because Kestrel's battery use is Restricted.
     */
    BATTERY_RESTRICTED(ListeningTexts.BATTERY_RESTRICTED, restart = false, starting = false),
}
```

with

```kotlin
    /**
     * Android took the foreground from ListeningService, or stopped it, because Kestrel's battery use is Restricted.
     */
    BATTERY_RESTRICTED(ListeningTexts.BATTERY_RESTRICTED, restart = false, starting = false),

    /**
     * Kestrel crashed less than 2 minutes after a restore had turned it on, so the restore leaves it off (section 8.5).
     */
    CRASHED_AFTER_RESTORE(ListeningTexts.CRASHED_AFTER_RESTORE, restart = true, starting = false),
}
```

In the class's KDoc, replace

```kotlin
 * first time in a process in which Kestrel has not been on, it stores Off and turns Kestrel on in the stored mode, so
 * that listening comes back after Android ended Kestrel's process or the phone restarted, while a start that ends the
 * process does not come back at every start. A task never comes back (sections 8.5 and 14.4).
```

with

```kotlin
 * first time in a process in which Kestrel has not been on, it stores Off and turns Kestrel on in the stored mode, so
 * that listening comes back after Android ended Kestrel's process or the phone restarted, while a start that ends the
 * process does not come back at every start. It stays off when the newest of [exits] says that the owner stopped
 * Kestrel, or that Kestrel crashed less than 2 minutes after the last restore. A task never comes back (sections 8.5
 * and 14.4). [clock] gives the wall-clock time in milliseconds, which the exit records also use.
```

Replace

```kotlin
    private val notices: StopNotices,
    microphones: () -> Microphone,
) {
    private enum class ServiceState { STOPPED, STARTING, FOREGROUND }
```

with

```kotlin
    private val notices: StopNotices,
    private val exits: ExitRecords,
    private val clock: () -> Long,
    microphones: () -> Microphone,
) {
    private enum class ServiceState { STOPPED, STARTING, FOREGROUND }

    /** What the restore does: start in the stored mode, stay off, or stay off and say that Kestrel crashed. */
    private enum class Restore { START, STAY_OFF, CRASHED }
```

Replace

```kotlin
    private var lastMode: Mode? = null
    private var restoreChecked = false
    private var wasOn = false
```

with

```kotlin
    private var lastMode: Mode? = null
    private var restoreChecked = false
    private var wasOn = false

    // When the restore of this process turned Kestrel on, until Kestrel next turns off; null otherwise. Written on
    // [thread], and stored with every mode by the mode's writer, so that a crash in the restored process finds it.
    @Volatile
    private var restoredAt: Long? = null
```

In the mode's writer in `init`, replace

```kotlin
                    memory.write(mode)
```

with

```kotlin
                    memory.write(mode, clock(), restoredAt)
```

Replace

```kotlin
    /**
     * Kestrel's accessibility service has connected, which also happens after Android ended Kestrel's process or the phone
     * restarted. The first time in a process, if Kestrel has not been on in it, Kestrel turns on in the mode that it
     * stored; Android lets it start its foreground service from the background from now on (prototype P1). Any thread
     * may call it.
     */
    fun onAccessibilityServiceConnected() {
        scope.launch(thread) {
            if (restoreChecked) return@launch
            restoreChecked = true
            // The owner has already chosen a mode in this process, for example with the tile.
            if (wasOn || state.value.mode != Mode.OFF) return@launch
            val stored = try {
                memory.read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Reading the listening mode", e)
                return@launch
            }
            // Also when the owner chose a mode while the stored one was read.
            if (stored == Mode.OFF || wasOn || state.value.mode != Mode.OFF) return@launch
            // Off first: if this start ends Kestrel's process, for example with a native crash while the models load, the
            // next start of the process finds Off and does not try again. The mode is stored again once the voice session
            // is open. Nothing else writes now, because Kestrel has not been on in this process.
            try {
                memory.write(Mode.OFF)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Storing the listening mode", e)
                return@launch
            }
            if (wasOn || state.value.mode != Mode.OFF) return@launch
            send(Event.Start(paused = stored == Mode.PAUSED))
        }
    }
```

with

```kotlin
    /**
     * Kestrel's accessibility service has connected, which also happens after Android ended Kestrel's process or the phone
     * restarted. The first time in a process, if Kestrel has not been on in it, Kestrel turns on in the mode that it
     * stored, unless the newest exit record says that the owner stopped Kestrel, or that Kestrel crashed soon after the
     * last restore (section 8.5); Android lets it start its foreground service from the background from now on
     * (prototype P1). A stored Off that carries the time of a restore means that the restored process ended before its
     * voice session opened: Kestrel stays off, and says why after a crash. Any thread may call it.
     */
    fun onAccessibilityServiceConnected() {
        scope.launch(thread) {
            if (restoreChecked) return@launch
            restoreChecked = true
            // The owner has already chosen a mode in this process, for example with the tile.
            if (wasOn || state.value.mode != Mode.OFF) return@launch
            val stored = try {
                memory.read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Reading the listening mode", e)
                return@launch
            }
            // Also when the owner chose a mode while the stored one was read.
            if (wasOn || state.value.mode != Mode.OFF) return@launch
            // An Off without the time of a restore is the owner's Off, or a restore's decision to stay off.
            if (stored.mode == Mode.OFF && stored.restoredAt == null) return@launch
            val exit = try {
                exits.newest()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Without the record, Kestrel restores as it did before it read records.
                report("Reading the exit records", e)
                null
            }
            // Also when the owner chose a mode while the record was read.
            if (wasOn || state.value.mode != Mode.OFF) return@launch
            // An Off with the time of a restore: that restore turned Kestrel on, and its process ended before the voice
            // session opened, for example with a native crash while the models loaded. Kestrel does not start again,
            // and says why when that end was a crash soon after the restore.
            val decided = restoreAfter(stored, exit)
            val restore = if (stored.mode == Mode.OFF && decided == Restore.START) Restore.STAY_OFF else decided
            val now = clock()
            // Off first: if this start ends Kestrel's process, for example with a native crash while the models load,
            // the next start of the process finds Off and does not try again. The mode is stored again once the voice
            // session is open. Nothing else writes now, because Kestrel has not been on in this process. When Kestrel
            // stays off, Off is all that it stores, without the time of a restore.
            try {
                memory.write(Mode.OFF, now, if (restore == Restore.START) now else null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Storing the listening mode", e)
                return@launch
            }
            if (wasOn || state.value.mode != Mode.OFF) return@launch
            when (restore) {
                Restore.START -> {
                    restoredAt = now
                    send(Event.Start(paused = stored.mode == Mode.PAUSED))
                }
                Restore.STAY_OFF -> Unit
                // Section 14.6: the bar and the notification say why, and the notification offers "Start listening".
                Restore.CRASHED -> stopBecause(StopReason.CRASHED_AFTER_RESTORE, notify = true)
            }
        }
    }

    /** Section 8.5: what the newest exit record [exit] says about the restore of [stored]. */
    private fun restoreAfter(stored: StoredMode, exit: ExitRecord?): Restore {
        // A record from before the stored mode was written belongs to an older process; a reboot leaves no record of
        // its own. A mode that an earlier version stored has no time, so its newest record decides.
        if (exit == null || (stored.writtenAt != null && exit.timestamp < stored.writtenAt)) return Restore.START
        return when (exit.reason) {
            ExitReason.OWNER_STOPPED -> Restore.STAY_OFF
            ExitReason.CRASH -> {
                val restored = stored.restoredAt
                if (restored != null && exit.timestamp - restored < CRASH_AFTER_RESTORE_MILLIS) Restore.CRASHED else Restore.START
            }
            ExitReason.OTHER -> Restore.START
        }
    }
```

Replace

```kotlin
    private fun noteMode(mode: Mode) {
        if (mode != Mode.OFF) wasOn = true
        remember(mode)
    }
```

with

```kotlin
    private fun noteMode(mode: Mode) {
        if (mode != Mode.OFF) wasOn = true
        // A start after an Off is the owner's, so a crash after it is not one soon after a restore (section 8.5).
        if (mode == Mode.OFF) restoredAt = null
        remember(mode)
    }
```

Replace

```kotlin
        const val MICROPHONE_FAILURES_MILLIS = 60_000L
```

with

```kotlin
        const val MICROPHONE_FAILURES_MILLIS = 60_000L

        // Section 8.5: a crash within this time after a restore keeps Kestrel off, so that a start that crashes does
        // not come back after every crash.
        const val CRASH_AFTER_RESTORE_MILLIS = 120_000L
```

- [ ] **Step 5: Wire the exit records and the clock**

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import dev.kestrel.app.listen.BoostWordSource
```

with

```kotlin
import dev.kestrel.app.listen.AndroidExitRecords
import dev.kestrel.app.listen.BoostWordSource
```

Replace

```kotlin
import dev.kestrel.app.listen.SessionListener
```

with

```kotlin
import dev.kestrel.app.listen.SessionListener
import dev.kestrel.app.listen.StoredMode
```

Replace

```kotlin
            // Encrypted like every setting (section 13.4). SettingsStore reads and writes on Dispatchers.IO.
            memory = object : ModeMemory {
                override suspend fun read(): Mode = settings.get(SettingKeys.LISTENING).mode

                override suspend fun write(mode: Mode) = settings.set(SettingKeys.LISTENING, ListeningSetting(mode))
            },
```

with

```kotlin
            // Encrypted like every setting (section 13.4). SettingsStore reads and writes on Dispatchers.IO.
            memory = object : ModeMemory {
                override suspend fun read(): StoredMode =
                    settings.get(SettingKeys.LISTENING).let { StoredMode(it.mode, it.writtenAt, it.restoredAt) }

                override suspend fun write(mode: Mode, writtenAt: Long, restoredAt: Long?) =
                    settings.set(SettingKeys.LISTENING, ListeningSetting(mode, writtenAt, restoredAt))
            },
            exits = AndroidExitRecords(appContext),
            // The wall clock, as in Android's exit records.
            clock = System::currentTimeMillis,
```

- [ ] **Step 6: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD SUCCESSFUL`, with 87 tests in `ListeningTest` (75 and the 12 new ones). The restore's earlier tests pass unchanged, because `FakeExits` has no record by default.

- [ ] **Step 7: Update the spec and the README**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 8.5, replace

```markdown
- When Android ends Kestrel's process or the phone restarts, Kestrel returns to the mode that it had, listening or paused, as soon as its accessibility service connects again. A task never comes back (section 14.4).
```

with

```markdown
- When Android ends Kestrel's process or the phone restarts, Kestrel returns to the mode that it had, listening or paused, as soon as its accessibility service connects again. A task never comes back (section 14.4). Before it returns, it reads Android's newest record of how one of its processes ended (`getHistoricalProcessExitReasons()`). If the owner stopped Kestrel (a force stop, "Stop app" or Stop in "Active apps"), it stays off. If Kestrel crashed less than 2 minutes after a restore had turned it on, also before its voice session opened, it stays off and says so on the feedback bar and in a notification with "Start listening". A record from before the stored mode was written belongs to an older process, because a reboot leaves no record of its own, so it does not count. The stored mode keeps the times at which it was written and at which the last restore turned Kestrel on; an Off clears the latter, because a start after it is the owner's. These rules hold on Android 14 and later: on Android 13, an update of Kestrel ends its process with the same reason as the owner's stop, so Kestrel stays off after an update there. A restart of the phone soon after the owner's stop can lose Android's record of the stop, and Kestrel then comes back.
```

(The rest of that bullet, "Kestrel counts listening or paused as its mode only once its voice session is open, ...", stays.)

In section 14.6, in the row that Task 2 added, replace

```markdown
| Kestrel turns itself off, or cannot start | Besides the feedback bar, which exists only while the accessibility service runs, a notification on the channel "Problems" says why. A tap opens Kestrel. When a new start can help (a refused start in the background, a failed microphone, a stop by Android), it has a "Start listening" action, which starts the listening service as a service's `PendingIntent`, because a broadcast would leave only about 9 s for the microphone (prototype P2). The notification goes once Kestrel listens again, and the owner can swipe it away. Kestrel posts none for an Off that the owner chose |
```

with

```markdown
| Kestrel turns itself off, or cannot start | Besides the feedback bar, which exists only while the accessibility service runs, a notification on the channel "Problems" says why. A tap opens Kestrel. When a new start can help (a refused start in the background, a failed microphone, a stop by Android, a crash soon after a restore), it has a "Start listening" action, which starts the listening service as a service's `PendingIntent`, because a broadcast would leave only about 9 s for the microphone (prototype P2). The notification goes once Kestrel listens again, and the owner can swipe it away. Kestrel posts none for an Off that the owner chose |
```

In section 13.5, replace

```markdown
- Screenshots, audio and credentials are never logged.
```

with

```markdown
- Screenshots, audio and credentials are never logged.
- Kestrel writes no audio, transcript, reply, boost word, app name, screen text, exception message or exit record's description to Android's log. It logs a failure only by its classes and stack frames, and the text form (`toString`) of anything that carries the owner's words gives only lengths or counts.
```

In `README.md`, replace

```markdown
When Android ends Kestrel's process, or the phone restarts, Kestrel comes back in the mode that it had, listening or paused, as soon as its accessibility service connects again.
```

with

```markdown
When Android ends Kestrel's process, or the phone restarts, Kestrel comes back in the mode that it had, listening or paused, as soon as its accessibility service connects again. It stays off when you stopped it yourself (Force stop, "Stop app", or Stop in "Active apps"), unless the phone restarts soon after, because a restart can lose Android's record of the stop. It also stays off when it crashed less than 2 minutes after it came back, and a notification then says so. These rules hold on Android 14 and later; on Android 13, Kestrel also stays off after an update that ended it while it listened.
```

- [ ] **Step 8: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (177 in `:app`, 116 in `:core:voice`), lint in every module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests compile. `VoiceHookTest`, `ScreenServiceRule` and `VoiceHooks` build `ListeningSetting` with its defaults, so they compile unchanged.

- [ ] **Step 9: Run the voice device tests**

`VoiceHookTest.offStoresOffAlsoWhileKestrelIsOff` reads and writes the stored setting through `SettingsStore`, now with the new fields.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 65 run, 0 failed, 0 errors, 0 skipped." Task 7 checks the restore after `am crash` and after a reboot on the device with `listen-check.ps1`, which needs a new process for each, as the final review of plan 2C-2 asks.

- [ ] **Step 10: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 11: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/listen/ExitRecords.kt app/src/main/kotlin/dev/kestrel/app/listen/ListeningSetting.kt app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md README.md
git commit -m "feat(app): keep Kestrel off after the owner stopped it or after a crash soon after a restore, by Android's newest exit record" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: The hint after empty transcripts

**Files:**
- Modify: `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt` (`State`, `Event.Speaking`'s KDoc, new `Event.NothingHeard`, new `Feedback.NoWords`, `Rules.next`, `heard`, new `withoutWords`, `turnOff`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/VoiceSession.kt` (`forward`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt` (`ListeningTexts`, `text`)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 14.6)
- Test: `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceSessionFailuresTest.kt`

**Interfaces:**
- Consumes: `Rules.heard`, `Rules.turnOff`, `waitForTask(state, vararg first, answering)`, `Event.SettingsUnavailable` and `Feedback.AwaitingTask` from Task 1, `ListeningTexts.CRASHED_AFTER_RESTORE` from Task 5, after which the new text goes, `VoiceEvent.Dropped`, `VoiceEvent.Failed`.
- Produces: `State.emptyTranscripts: Int = 0` and `State.noWordsShown: Boolean = false`; `data object Event.NothingHeard`; `data object Feedback.NoWords`; `ListeningTexts.NO_WORDS`.

Spec 14.6 and the final review of plan 2C-1, item 5. `VoiceSession` turned a dropped utterance and a failed transcription into `Event.Heard("", named = false)`, like a real empty transcript, so nothing could count real empty transcripts. Now it sends `Event.NothingHeard` for both, which the Orchestrator handles as before (each restarts the wait for a task, and otherwise changes nothing), but which never counts. The Orchestrator counts real transcripts without words in a row (`State.emptyTranscripts`); a transcript with words starts the count again. After the third one, the bar shows that Kestrel hears sound but no words and that the owner may check the microphone, at most once until Kestrel turns off (`State.noWordsShown`), which is once per voice session. The spec's row said to suggest checking the microphone permission, but without that permission Kestrel cannot start at all (Task 1), so the hint names the microphone.

- [ ] **Step 1: Write the failing tests**

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, in the test `theWaitCoversTheAnswerOnceTheOwnerStartsIt`, replace

```kotlin
        assertEquals(Step(listening), Rules.next(listening, Event.Heard(" ... ", named = false)))
```

with

```kotlin
        // Outside a wait, a transcript without words is only counted (section 14.6).
        assertEquals(Step(listening.copy(emptyTranscripts = 1)), Rules.next(listening, Event.Heard(" ... ", named = false)))
```

Add these three tests right after the test `kestrelSaysWhyItCannotOpenItsSettings`, by replacing

```kotlin
        // A late report changes nothing once Kestrel is off.
        assertEquals(Step(State()), Rules.next(State(), Event.SettingsUnavailable))
    }
```

with

```kotlin
        // A late report changes nothing once Kestrel is off.
        assertEquals(Step(State()), Rules.next(State(), Event.SettingsUnavailable))
    }

    @Test
    fun threeEmptyTranscriptsInARowShowTheHintOncePerSession() {
        var state = listening
        val effects = (1..6).map { Rules.next(state, Event.Heard("", named = false)).also { state = it.state }.effects }

        val hint = listOf(Effect.Show(Feedback.NoWords))
        assertEquals(listOf<List<Effect>>(emptyList(), emptyList(), hint, emptyList(), emptyList(), emptyList()), effects)

        // A new voice session, after Kestrel turned off and on, may show it again.
        state = Rules.next(Rules.next(state, Event.TurnOff).state, Event.Toggle).state
        val again = (1..3).map { Rules.next(state, Event.Heard("", named = false)).also { state = it.state }.effects }
        assertEquals(listOf<List<Effect>>(emptyList(), emptyList(), hint), again)
    }

    @Test
    fun aTranscriptWithWordsStartsTheCountAgain() {
        var state = listening
        for (text in listOf("", "", "Scroll down", "", "")) state = Rules.next(state, Event.Heard(text, named = false)).state

        assertEquals(2, state.emptyTranscripts)
        assertEquals(listOf(Effect.Show(Feedback.NoWords)), Rules.next(state, Event.Heard("", named = false)).effects)
    }

    @Test
    fun anUtteranceWithoutATranscriptRestartsTheWaitButIsNotCounted() {
        // The pipeline dropped the utterance, or transcribing it failed.
        val waiting = listening.copy(work = Work.AWAITING_TASK, wait = 1, emptyTranscripts = 2)

        val step = Rules.next(waiting, Event.NothingHeard)

        assertEquals(listOf(Effect.WaitForTask()), step.effects)
        assertEquals(2, step.state.wait)
        assertEquals(2, step.state.emptyTranscripts)
        assertEquals(Step(listening), Rules.next(listening, Event.NothingHeard))
        assertEquals(Step(State()), Rules.next(State(), Event.NothingHeard))
    }
```

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceSessionFailuresTest.kt`, replace

```kotlin
    fun droppedAndFailedUtterancesReachTheListenerAsEmptyTranscripts() = runBlocking {
```

with

```kotlin
    fun droppedAndFailedUtterancesReachTheListenerAsNothingHeard() = runBlocking {
```

and replace

```kotlin
            microphone.feed(0.02f)
            assertEquals(Event.Heard("", named = false), listener.await { it is Event.Heard })
            assertEquals(emptyList<Exception>(), listener.failures.toList())
```

with

```kotlin
            microphone.feed(0.02f)
            // Not an empty transcript, which would count towards the hint of section 14.6.
            assertEquals(Event.NothingHeard, listener.await { it == Event.NothingHeard })
            assertEquals(emptyList<Exception>(), listener.failures.toList())
```

and replace

```kotlin
            microphone.feed(0.02f)
            assertEquals(Event.Heard("", named = false), listener.await { it is Event.Heard })
            assertEquals(1, listener.failures.size)
```

with

```kotlin
            microphone.feed(0.02f)
            assertEquals(Event.NothingHeard, listener.await { it == Event.NothingHeard })
            assertEquals(1, listener.failures.size)
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.orchestrator.RulesTest`
Expected: `BUILD FAILED` in `:app:compileDebugUnitTestKotlin`, with `Unresolved reference` errors for `emptyTranscripts`, `NoWords` and `NothingHeard`.

- [ ] **Step 3: Count the empty transcripts in the Orchestrator**

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace

```kotlin
 * The Orchestrator's state (design spec, section 7.3). [speeches] counts the replies that Kestrel has started and not
 * finished. [task] is the number of the latest task and [wait] the number of the latest wait for a task, so that the
 * end of an older task or wait changes nothing. [cancelled]: the latest task was cancelled and has not reported yet.
 */
```

with

```kotlin
 * The Orchestrator's state (design spec, section 7.3). [speeches] counts the replies that Kestrel has started and not
 * finished. [task] is the number of the latest task and [wait] the number of the latest wait for a task, so that the
 * end of an older task or wait changes nothing. [cancelled]: the latest task was cancelled and has not reported yet.
 * [emptyTranscripts] counts the transcripts without words in a row, and [noWordsShown] is true once the hint about them
 * has shown since Kestrel turned on (section 14.6).
 */
```

Replace

```kotlin
    val micBusy: Boolean = false,
) {
```

with

```kotlin
    val micBusy: Boolean = false,
    val emptyTranscripts: Int = 0,
    val noWordsShown: Boolean = false,
) {
```

Replace

```kotlin
     * `ListeningPipeline.accept`; the pipeline's `VoiceEvent.SpeechStarted` follows this rule. An utterance that ends
     * without a transcript while recognition is on restarts the 8 s wait for a task: `VoiceSession` sends
     * `Heard("", named = false)` for it.
     */
```

with

```kotlin
     * `ListeningPipeline.accept`; the pipeline's `VoiceEvent.SpeechStarted` follows this rule. An utterance that ends
     * without a transcript while recognition is on restarts the 8 s wait for a task: `VoiceSession` sends
     * [NothingHeard] for it.
     */
```

Replace

```kotlin
    data object SettingsUnavailable : Event
}
```

with

```kotlin
    data object SettingsUnavailable : Event

    /**
     * An utterance ended without a transcript: the pipeline dropped it, or transcribing it failed. Like a transcript
     * without words, it gives the owner the whole wait for a task again, but it does not count towards the hint about
     * empty transcripts (section 14.6).
     */
    data object NothingHeard : Event
}
```

Replace

```kotlin
    data object AwaitingTask : Feedback
}
```

with

```kotlin
    data object AwaitingTask : Feedback

    /** Three transcripts in a row had no words, so the owner may check the microphone (section 14.6). */
    data object NoWords : Feedback
}
```

Replace

```kotlin
        Event.SettingsUnavailable -> if (state.mode == Mode.OFF) Step(state) else say(state, Reply.SettingsNeedService)
    }
```

with

```kotlin
        Event.SettingsUnavailable -> if (state.mode == Mode.OFF) Step(state) else say(state, Reply.SettingsNeedService)
        Event.NothingHeard ->
            if (state.mode != Mode.OFF && state.work == Work.AWAITING_TASK) waitForTask(state) else Step(state)
    }
```

Replace

```kotlin
    private fun heard(state: State, event: Event.Heard): Step {
        if (state.mode == Mode.OFF) return Step(state)
        val awaiting = state.work == Work.AWAITING_TASK
        // An answer to "Yes?" without words: the owner has the full wait again.
        val request = Requests.read(event.text, event.named, afterName = awaiting)
            ?: return if (awaiting) waitForTask(state) else Step(state)
        // A transcription that was queued before the microphone stopped can finish after the screen turned off. Then
        // only the requests that stop something count, because nothing may start (section 14.4).
        if (!state.screenOn && !stopsSomething(request)) return Step(state)
        val shown = Effect.Show(Feedback.Heard(event.text))
        return when (state.work) {
            Work.RUNNING_TASK -> whileTaskRuns(state, request, shown)
            Work.AWAITING_TASK -> whileAwaitingTask(state, request, shown)
            Work.IDLE -> whileIdle(state, request, event.text, shown)
        }
    }
```

with

```kotlin
    private fun heard(state: State, event: Event.Heard): Step {
        if (state.mode == Mode.OFF) return Step(state)
        val awaiting = state.work == Work.AWAITING_TASK
        // An empty transcript that finishes after the screen turned off does not count: nobody could see its hint.
        // Waiting for a task ends when the screen turns off.
        val request = Requests.read(event.text, event.named, afterName = awaiting)
            ?: return if (state.screenOn) withoutWords(state, awaiting) else Step(state)
        // Words start the count of empty transcripts again.
        val counted = state.copy(emptyTranscripts = 0)
        // A transcription that was queued before the microphone stopped can finish after the screen turned off. Then
        // only the requests that stop something count, because nothing may start (section 14.4).
        if (!counted.screenOn && !stopsSomething(request)) return Step(counted)
        val shown = Effect.Show(Feedback.Heard(event.text))
        return when (counted.work) {
            Work.RUNNING_TASK -> whileTaskRuns(counted, request, shown)
            Work.AWAITING_TASK -> whileAwaitingTask(counted, request, shown)
            Work.IDLE -> whileIdle(counted, request, event.text, shown)
        }
    }

    // Section 14.6: a transcript without words. After three in a row, the bar suggests checking the microphone, once
    // until Kestrel turns off. An answer to "Yes?" without words gives the owner the full wait again.
    private fun withoutWords(state: State, awaiting: Boolean): Step {
        val count = state.emptyTranscripts + 1
        val hint = count >= EMPTY_TRANSCRIPTS_FOR_HINT && !state.noWordsShown
        val counted = state.copy(emptyTranscripts = count, noWordsShown = state.noWordsShown || hint)
        val shown = listOfNotNull(Effect.Show(Feedback.NoWords).takeIf { hint })
        return if (awaiting) waitForTask(counted, *shown.toTypedArray()) else Step(counted, shown)
    }
```

Replace

```kotlin
    // Off also forgets the replies in progress and "mic busy", so that a lost SpeechEnded cannot keep recognition off.
    private fun turnOff(state: State, vararg first: Effect): Step = Step(
        state.copy(mode = Mode.OFF, work = Work.IDLE, speeches = 0, cancelled = false, micBusy = false),
```

with

```kotlin
    // Off also forgets the replies in progress and "mic busy", so that a lost SpeechEnded cannot keep recognition off,
    // and the count of empty transcripts, so that the next voice session may show the hint again.
    private fun turnOff(state: State, vararg first: Effect): Step = Step(
        state.copy(
            mode = Mode.OFF,
            work = Work.IDLE,
            speeches = 0,
            cancelled = false,
            micBusy = false,
            emptyTranscripts = 0,
            noWordsShown = false,
        ),
```

Replace

```kotlin
    /** This step, then the step that [next] makes from its state. */
    private fun Step.then(next: (State) -> Step): Step = next(state).let { Step(it.state, effects + it.effects) }
}
```

with

```kotlin
    /** This step, then the step that [next] makes from its state. */
    private fun Step.then(next: (State) -> Step): Step = next(state).let { Step(it.state, effects + it.effects) }

    // Section 14.6: how many transcripts without words in a row bring the hint.
    private const val EMPTY_TRANSCRIPTS_FOR_HINT = 3
}
```

- [ ] **Step 4: Send NothingHeard from the session, and show the hint**

In `app/src/main/kotlin/dev/kestrel/app/listen/VoiceSession.kt`, replace

```kotlin
            // An utterance without a transcript gives the owner the whole wait for a task again (Rules, Event.Speaking).
            VoiceEvent.Dropped -> listener.send(Event.Heard("", named = false))
            is VoiceEvent.Failed -> {
                listener.transcriptionFailed(event.error)
                listener.send(Event.Heard("", named = false))
            }
```

with

```kotlin
            // An utterance without a transcript gives the owner the whole wait for a task again, but it is not an empty
            // transcript, which counts towards the hint of section 14.6 (Rules, Event.NothingHeard).
            VoiceEvent.Dropped -> listener.send(Event.NothingHeard)
            is VoiceEvent.Failed -> {
                listener.transcriptionFailed(event.error)
                listener.send(Event.NothingHeard)
            }
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
    const val CRASHED_AFTER_RESTORE = "Kestrel stopped soon after it came back, so it stays off."
```

with

```kotlin
    const val CRASHED_AFTER_RESTORE = "Kestrel stopped soon after it came back, so it stays off."
    const val NO_WORDS = "Kestrel hears sound but no words. Check the microphone."
```

Replace

```kotlin
        Feedback.AwaitingTask -> ListeningTexts.YES
    }
```

with

```kotlin
        Feedback.AwaitingTask -> ListeningTexts.YES
        Feedback.NoWords -> ListeningTexts.NO_WORDS
    }
```

- [ ] **Step 5: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.orchestrator.RulesTest --tests dev.kestrel.app.orchestrator.OrchestratorTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD SUCCESSFUL`, with 42 tests in `RulesTest` (39 and the 3 new ones; one line of `theWaitCoversTheAnswerOnceTheOwnerStartsIt` changed), 9 in `OrchestratorTest` and 87 in `ListeningTest`.

- [ ] **Step 6: Update the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 14.6, replace

```markdown
| Recognition keeps returning empty text | Kestrel suggests checking the microphone permission |
```

with

```markdown
| Recognition keeps returning empty text | After three transcripts without words in a row, the feedback bar says that Kestrel hears sound but no words and that the owner may check the microphone, at most once per voice session. An utterance that the pipeline dropped, or whose transcription failed, does not count |
```

- [ ] **Step 7: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (180 in `:app`, 116 in `:core:voice`), lint in every module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests compile.

- [ ] **Step 8: Run the voice device tests**

`VoiceSessionFailuresTest.droppedAndFailedUtterancesReachTheListenerAsNothingHeard` (renamed) checks the session's real `forward` with fake engines; `ConversationTest` runs the Orchestrator with the real pipeline.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 65 run, 0 failed, 0 errors, 0 skipped."

- [ ] **Step 9: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 10: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt app/src/main/kotlin/dev/kestrel/app/listen/VoiceSession.kt app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceSessionFailuresTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(app): suggest checking the microphone after three empty transcripts in a row, and stop counting dropped and failed utterances as empty" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 7: Checks on the device

**Files:**
- Create: `app/src/androidTest/kotlin/dev/kestrel/app/voice/RefusedReplyTest.kt`
- Modify: `scripts/voice-test.ps1` (the header, `$emulatorOnly`, the restore at the end)
- Modify: `scripts/lib/Kestrel.ps1` (new `Get-UiNodeCentre`)
- Modify: `scripts/listen-check.ps1` (the header, `Test-KestrelPermission`, new `Get-UiDump` and `Invoke-NotificationCheck`, the notification permission, the order of the checks, the result lines)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 19.1), `README.md` (the paragraphs on the voice tests and on `listen-check.ps1`)
- Test: `scripts/tests/Kestrel.Tests.ps1` (the analyzer's suppressions, new `Describe 'Get-UiNodeCentre'`)

**Interfaces:**
- Consumes: `ListeningTexts.NO_TASKS`, `ScreenSession.feedbackText`, `ScreenServiceRule`, `VoiceTests.startListening`, `VoiceTests.stopListening`, `VoiceTests.assumeEmulator`; the problems notification's "Start listening" from Task 2 and its strings `problem_start` ("Start listening") and `problem_title_not_started` ("Kestrel could not start listening"); `Invoke-SettingsCommand`, `Get-VoiceState`, `Wait-Mode`, `Get-KestrelRecording`, `Get-RecordingProblem`, `Invoke-Once`, `Register-Restore` in `listen-check.ps1`.
- Produces: `function Get-UiNodeCentre([string]$Dump, [string]$Text)`, which returns an object with `X` and `Y`, or `$null`; the result line `notification` of `listen-check.ps1`.

Final review of plan 2C-1, item 14, and the final review of plan 2C-2. A device test refuses Kestrel's audio focus with `cmd appops set dev.kestrel TAKE_AUDIO_FOCUS ignore` and checks that the refused reply's text goes to the feedback bar (`REFUSED`); it puts back the op's normal mode, `foreground`, in a `finally` block of the test (`default` would still refuse, prototype P2). On a phone a rule of order 0 skips the test before `ScreenServiceRule` and `@After` run, so that the class changes nothing there. The normal mode on Android 13 has not been checked, so the test runs only on an emulator, and `voice-test.ps1` leaves it out on a phone and sets the op back to `foreground` at the end of a run on an emulator, as it does for the privacy toggle. `listen-check.ps1` gets a `notification` check: with Kestrel's accessibility service off and Kestrel in the background, a start through the hook is refused, the problems notification appears with "Start listening", the script taps the button in the notification shade, and listening starts and is not silenced (spike S1's start from a notification action, again). It checks the refusal before it opens the shade, so that a "Start listening" that an earlier failure left cannot stand in for this one. It finds the button with `uiautomator dump`, whose file it deletes and never prints, because on a phone the dump holds the text of the owner's notifications. When the shade shows the problems notification collapsed, without its button, for example below other notifications, the script expands it with a swipe down on its title and looks again before it fails. After the tap, the check waits until 30 s have passed since the tap, so that the tile check after it cannot use the exemption that the tap gave Kestrel (prototype P2, Q2). The check runs first: if it fails, the problems notification stays in the shade until Kestrel next listens, which the later checks bring about. The plan ends with the voice device tests, the other device tests of `:app`, and a run of `listen-check.ps1 -Reboot` from a cold boot of the emulator, which also checks the restore of Task 5 after `am crash` and after a reboot, each in a new process.

- [ ] **Step 1: Write the failing Pester tests**

In `scripts/tests/Kestrel.Tests.ps1`, replace

```powershell
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSAvoidUsingInvokeExpression', '',
    Justification = 'A test runs the command that a warning asks the owner to paste.')]
param()
```

with

```powershell
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSAvoidUsingInvokeExpression', '',
    Justification = 'A test runs the command that a warning asks the owner to paste.')]
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()
```

PSScriptAnalyzer checks each script block on its own, so without the second attribute it reports `$shade`, which `BeforeAll` assigns and the `It` blocks use. The tests of the models' script have the same suppression for the same reason.

Then, in `scripts/tests/Kestrel.Tests.ps1`, add at the end of the file:

```powershell

Describe 'Get-UiNodeCentre' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
        # The notification shade as 'uiautomator dump' shows a notification with an action, shortened.
        $shade = @'
<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0"><node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.android.systemui" content-desc="" bounds="[0,0][1080,2400]"><node index="0" text="Kestrel could not start listening" resource-id="android:id/title" class="android.widget.TextView" package="com.android.systemui" content-desc="" bounds="[96,780][900,830]" /><node index="1" text="Start listening now" resource-id="" class="android.widget.TextView" package="com.android.systemui" content-desc="" bounds="[96,840][900,880]" /><node index="2" text="START LISTENING" resource-id="android:id/action0" class="android.widget.Button" package="com.android.systemui" content-desc="" bounds="[264,900][352,948]" /></node></hierarchy>
'@
    }

    It 'returns the centre of the node whose whole text is the text, in any letter case' {
        $centre = Get-UiNodeCentre -Dump $shade -Text 'Start listening'

        $centre.X | Should -Be 308
        $centre.Y | Should -Be 924
    }

    It 'returns $null when no node has exactly that text' {
        Get-UiNodeCentre -Dump $shade -Text 'Start' | Should -BeNullOrEmpty
    }

    It 'returns $null for an answer that is not XML: ''<_>''' -ForEach @('', 'ERROR: null root node returned by UiTestAutomationBridge.') {
        Get-UiNodeCentre -Dump $_ -Text 'Start listening' | Should -BeNullOrEmpty
    }
}
```

- [ ] **Step 2: Run the Pester tests to verify that they fail**

Run: `scripts\test-scripts.ps1`
Expected: in both shells, 4 tests of `Get-UiNodeCentre` fail with "The term 'Get-UiNodeCentre' is not recognized", and the other 91 pass; the script ends with "The script tests or checks failed."

- [ ] **Step 3: Find a node of a uiautomator dump**

In `scripts/lib/Kestrel.ps1`, add at the end of the file:

```powershell

# Returns the centre of the first node of a 'uiautomator dump' whose whole text is the text, in any letter case, because
# some versions of Android show a notification's buttons in capitals: an object with X and Y. Returns $null when no node
# has that text, or when the dump is not XML, as when uiautomator could not read the screen. A dump can hold the text of
# the owner's notifications, so the scripts only search it and never print it.
function Get-UiNodeCentre([string]$Dump, [string]$Text) {
    $xml = New-Object System.Xml.XmlDocument
    try { $xml.LoadXml($Dump) } catch { return $null }
    foreach ($node in @($xml.SelectNodes('//node'))) {
        if ($node.GetAttribute('text') -eq $Text -and $node.GetAttribute('bounds') -match '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
            return [pscustomobject]@{
                X = [int][math]::Floor(([int]$Matches[1] + [int]$Matches[3]) / 2)
                Y = [int][math]::Floor(([int]$Matches[2] + [int]$Matches[4]) / 2)
            }
        }
    }
    $null
}
```

- [ ] **Step 4: Run the Pester tests to verify that they pass**

Run: `scripts\test-scripts.ps1`
Expected: "Tests Passed: 95, Failed: 0" in Windows PowerShell 5.1 and in PowerShell 7, "PSScriptAnalyzer found 0 problems." and "The script tests and checks passed."

- [ ] **Step 5: Add the device test of a refused reply**

Create `app/src/androidTest/kotlin/dev/kestrel/app/voice/RefusedReplyTest.kt`:

```kotlin
package dev.kestrel.app.voice

import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.listen.ListeningTexts
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.voice.models.Engine
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/**
 * A reply that Android refuses to play: Kestrel shows its text on the feedback bar instead (design spec, section 14.6).
 * The test refuses Kestrel's audio focus with the app op TAKE_AUDIO_FOCUS, which Android 17 checks for every focus
 * request, and puts back the op's normal mode, foreground; the mode default would still refuse the focus
 * (prototype P2). It runs only on an emulator, where scripts\voice-test.ps1 also sets the op back to foreground at the
 * end, in case the test's process died before it could. It turns Kestrel's accessibility service on for the feedback
 * bar, and off again afterwards, as the voice tests after it expect.
 */
class RefusedReplyTest {
    // Before ScreenServiceRule: on a phone, the test is skipped before it changes anything there, and its @After does
    // not run either.
    @get:Rule(order = 0)
    val emulatorOnly = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                VoiceTests.assumeEmulator()
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1)
    val screen = ScreenServiceRule()

    private val listening = VoiceTests.graph.listening
    private val clip = ClipMicrophone()

    @Before
    fun setUp() {
        VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.ASR, Engine.TTS)
        VoiceTests.grantListeningPermissions()
    }

    @After
    fun tearDown() {
        try {
            VoiceTests.stopListening()
        } finally {
            VoiceTests.shell("settings delete secure enabled_accessibility_services")
            check(VoiceTests.waitUntil(10_000) { VoiceTests.graph.screen.value == null }) {
                "Kestrel's accessibility service is still connected"
            }
        }
    }

    @Test
    fun aReplyThatAndroidRefusesIsShownOnTheBar() {
        // The accessibility service lets Kestrel start its microphone service from the background.
        VoiceTests.startListening(clip)
        try {
            VoiceTests.shell("cmd appops set dev.kestrel TAKE_AUDIO_FOCUS ignore")

            // A task gets the stub's reply at once, which Android now refuses.
            listening.send(Event.Heard("Kestrel, open the settings.", named = true))

            assertTrue(
                "The feedback bar did not show the refused reply",
                VoiceTests.waitUntil(10_000) { screen.session.feedbackText == ListeningTexts.NO_TASKS },
            )
            assertTrue("The reply did not end", VoiceTests.waitUntil(5_000) { !listening.state.value.speaking })
        } finally {
            // Only here, after assumeEmulator() passed: foreground is the op's normal mode on Android 17, and default
            // would still refuse the focus (prototype P2).
            VoiceTests.shell("cmd appops set dev.kestrel TAKE_AUDIO_FOCUS foreground")
        }
    }
}
```

In `scripts/voice-test.ps1`, replace

```powershell
# password, so on a phone the script leaves that test out too. So does ActivationTest#theTileTogglesListening, which adds
# Kestrel's tile to Quick Settings: no script puts the list of tiles back if the test's process dies. The run fails when
# one of these three tests ran on a phone or did not run on an emulator. On an emulator the script turns the lock screen
# off again at the end, wakes the screen and dismisses the keyguard, also when the run failed or was interrupted.
```

with

```powershell
# password, so on a phone the script leaves that test out too. So does ActivationTest#theTileTogglesListening, which adds
# Kestrel's tile to Quick Settings: no script puts the list of tiles back if the test's process dies. So does
# RefusedReplyTest#aReplyThatAndroidRefusesIsShownOnTheBar, which sets Kestrel's app op TAKE_AUDIO_FOCUS to ignore and
# back to foreground, the op's normal mode on Android 17. The run fails when one of these four tests ran on a phone or
# did not run on an emulator. On an emulator the script turns the lock screen off again at the end, sets
# TAKE_AUDIO_FOCUS back to foreground, wakes the screen and dismisses the keyguard, also when the run failed or was
# interrupted.
```

Replace

```powershell
        # Tests that a phone must not run: one switches the microphone's privacy toggle, one turns on a lock screen that it
        # unlocks with adb, and one changes the list of Quick Settings tiles.
        $emulatorOnly = @(
            'dev.kestrel.app.voice.MicCaptureTest#silencedFollowsTheMicrophonesPrivacyToggle'
            'dev.kestrel.app.voice.ListeningServiceTest#theMicrophoneStopsWhileTheScreenIsOffAndStartsAgainAfterTheUnlock'
            'dev.kestrel.app.voice.ActivationTest#theTileTogglesListening'
        )
```

with

```powershell
        # Tests that a phone must not run: one switches the microphone's privacy toggle, one turns on a lock screen that it
        # unlocks with adb, one changes the list of Quick Settings tiles, and one refuses Kestrel's audio focus.
        $emulatorOnly = @(
            'dev.kestrel.app.voice.MicCaptureTest#silencedFollowsTheMicrophonesPrivacyToggle'
            'dev.kestrel.app.voice.ListeningServiceTest#theMicrophoneStopsWhileTheScreenIsOffAndStartsAgainAfterTheUnlock'
            'dev.kestrel.app.voice.ActivationTest#theTileTogglesListening'
            'dev.kestrel.app.voice.RefusedReplyTest#aReplyThatAndroidRefusesIsShownOnTheBar'
        )
```

Replace

```powershell
            if ($lockScreenOff -and -not (Invoke-DeviceRestore 'turn off the lock screen again' 'cmd lock_settings set-disabled true')) {
                $code = 1
            }
```

with

```powershell
            if ($lockScreenOff -and -not (Invoke-DeviceRestore 'turn off the lock screen again' 'cmd lock_settings set-disabled true')) {
                $code = 1
            }
            # The refused-reply test puts the op back itself, unless its process died in between. The mode default would
            # still refuse Kestrel's audio focus (prototype P2).
            if (-not (Invoke-DeviceRestore 'give Kestrel back its audio focus' 'cmd appops set dev.kestrel TAKE_AUDIO_FOCUS foreground')) {
                $code = 1
            }
```

- [ ] **Step 6: Add the notification check to listen-check.ps1**

In `scripts/listen-check.ps1`, replace

```powershell
# 14.4), on a test device with Kestrel's debug build (scripts\install.ps1) and its speech models (scripts\push-models.ps1):
#   tile      a tap on Kestrel's Quick Settings tile starts listening while Kestrel's accessibility service is off, and a
#             second tap pauses it
```

with

```powershell
# 14.4), on a test device with Kestrel's debug build (scripts\install.ps1) and its speech models (scripts\push-models.ps1):
#   notification  while Kestrel's accessibility service is off, Android refuses a start through the hook with Kestrel
#             in the background; Kestrel's problems notification then offers "Start listening", and a tap on it in the
#             notification shade starts listening (design spec, section 14.6; spike S1). The script finds the button
#             with 'uiautomator dump', whose file it deletes and never prints, because on a phone it holds the text of
#             the owner's notifications. When the shade shows Kestrel's notification collapsed, without its button,
#             the script first expands it with a swipe down on it. If this check fails, the problems notification stays
#             until Kestrel next listens, which the later checks bring about, or until you swipe it away
#   tile      a tap on Kestrel's Quick Settings tile starts listening while Kestrel's accessibility service is off, and a
#             second tap pauses it
```

Replace

```powershell
# While it runs, it grants Kestrel the microphone permission, replaces the list of enabled accessibility services, sets
# the target of the accessibility shortcut, and puts Kestrel's tile first in Quick Settings and opens the panel for the
# tile check. Afterwards, also when it fails or is interrupted, it turns Kestrel off, closes the panel and puts back the
# tiles, the accessibility settings and the permission. If it cannot put something back, also because Ctrl+C interrupted
```

with

```powershell
# While it runs, it grants Kestrel the microphone and notification permissions, replaces the list of enabled
# accessibility services, sets the target of the accessibility shortcut, opens the notification shade for the
# notification check, and puts Kestrel's tile first in Quick Settings and opens the panel for the tile check.
# Afterwards, also when it fails or is interrupted, it turns Kestrel off, closes the panel and puts back the tiles, the
# accessibility settings and the permissions, and it deletes the dump of the screen. When Kestrel's accessibility
# service was off before the run, Kestrel's notification that the service is off stays afterwards; swipe it away. If
# it cannot put something back, also because Ctrl+C interrupted
```

Replace

```powershell
# Kestrel turns itself off when a speech model is missing, so a start that never comes may have this cause.
$modelsHint = '; are the speech models on the device? Run scripts\push-models.ps1'
```

with

```powershell
# Kestrel turns itself off when a speech model is missing, so a start that never comes may have this cause.
$modelsHint = '; are the speech models on the device? Run scripts\push-models.ps1'
$microphonePermission = 'android.permission.RECORD_AUDIO'
$notificationPermission = 'android.permission.POST_NOTIFICATIONS'
# Where 'uiautomator dump' writes the screen's tree on the device; Get-UiDump deletes it after each read.
$uiDumpFile = '/data/local/tmp/kestrel-ui.xml'
```

Replace

```powershell
function Test-MicrophonePermission {
    ((Invoke-Checked 'dumpsys package dev.kestrel') -join "`n") -match 'android\.permission\.RECORD_AUDIO: granted=true'
}
```

with

```powershell
# Whether Kestrel holds a runtime permission, such as android.permission.RECORD_AUDIO.
function Test-KestrelPermission([string]$Permission) {
    ((Invoke-Checked 'dumpsys package dev.kestrel') -join "`n") -match "$([regex]::Escape($Permission)): granted=true"
}
```

Replace

```powershell
function Invoke-TileCheck {
    Stop-Kestrel
```

with

```powershell
# The screen's accessibility tree, as 'uiautomator dump' writes it, or an empty text when uiautomator could not read the
# screen. On a phone it holds the text of the owner's notifications, so the script only searches it, never prints it,
# and deletes the file on the device.
function Get-UiDump {
    $file = $uiDumpFile
    try {
        Invoke-Checked "uiautomator dump $file" | Out-Null
        # The dump ends without a line break, and the device's marker must start a line of its own.
        (Invoke-Checked "cat $file && echo") -join "`n"
    } catch {
        ''
    } finally {
        try { Invoke-Checked "rm -f $file" | Out-Null } catch { Write-Warning "Could not delete $file on $Serial." }
    }
}

function Invoke-NotificationCheck {
    Stop-Kestrel
    # Without the accessibility service, and with Kestrel in the background, Android refuses a start through the hook.
    Set-KestrelService $false
    # The shade and the tap need the screen on, and Kestrel records only while it is on.
    Invoke-Checked 'input keyevent KEYCODE_WAKEUP' | Out-Null
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    # Android lets an app start a foreground service for a few seconds after it was in front; prototype P2 waited 10 s.
    Start-Sleep -Seconds 10
    Invoke-Hook voice -Send toggle | Out-Null
    # Android refuses the start at once, and Kestrel turns off again. This is checked before the shade opens, so that a
    # "Start listening" that an earlier failure left cannot stand in for this one.
    Start-Sleep -Seconds 2
    $state = Get-VoiceState
    if ($state -and $state.mode -ne 'off') {
        return ('failed: Android let the hook start listening in the background without the accessibility service, ' +
            'so the check could not reach the notification')
    }
    $tapped = $null
    try {
        Invoke-Checked 'cmd statusbar expand-notifications' | Out-Null
        # The problems notification's title after a refused start (problem_title_not_started in strings.xml).
        $title = 'Kestrel could not start listening'
        $button = $null
        $notice = $null
        $read = $false
        # A dump takes a second or more, so the wait counts seconds, not dumps.
        $watch = [Diagnostics.Stopwatch]::StartNew()
        while (-not ($button -or $notice) -and $watch.Elapsed.TotalSeconds -lt 15) {
            Start-Sleep -Milliseconds 500
            $dump = Get-UiDump
            $read = $read -or [bool]$dump
            $button = Get-UiNodeCentre -Dump $dump -Text 'Start listening'
            $notice = Get-UiNodeCentre -Dump $dump -Text $title
        }
        if (-not $read) { return 'failed: uiautomator could not read the notification shade within 15 s' }
        if (-not ($button -or $notice)) {
            return 'failed: the notification shade did not show Kestrel''s problems notification within 15 s of a start that Android refused'
        }
        if (-not $button) {
            # The shade shows a notification collapsed, without its buttons, for example below other notifications. A
            # swipe down on a collapsed notification expands it; a second swipe would collapse it again.
            Invoke-Once "input swipe $($notice.X) $($notice.Y) $($notice.X) $($notice.Y + 300) 500"
            $watch.Restart()
            while (-not $button -and $watch.Elapsed.TotalSeconds -lt 5) {
                Start-Sleep -Milliseconds 500
                $button = Get-UiNodeCentre -Dump (Get-UiDump) -Text 'Start listening'
            }
            if (-not $button) {
                return 'failed: Kestrel''s problems notification showed no "Start listening", also after a swipe down on it to expand it'
            }
        }
        $before = Get-KestrelRecording
        Invoke-Once "input tap $($button.X) $($button.Y)"
        $tapped = [Diagnostics.Stopwatch]::StartNew()
        $on = Wait-Mode listening 60
        if ($on -lt 0) { return "failed: no listening within 60 s of a tap on ""Start listening""$modelsHint" }
        $problem = Get-RecordingProblem $before
        if ($problem) { return "failed: listening $on s after a tap on ""Start listening"", but $problem" }
        "ok: Android refused a start in the background, and ""Start listening"" in the notification started listening in $on s"
    } finally {
        # The later checks run with the shade closed.
        Invoke-Checked 'cmd statusbar collapse' | Out-Null
        # The tap lets Kestrel start a foreground service from the background for a while (prototype P2, Q2). The tile
        # check after this one must not use that exemption, so the check waits until 30 s have passed since the tap.
        if ($tapped) {
            $left = 30000 - $tapped.Elapsed.TotalMilliseconds
            if ($left -gt 0) { Start-Sleep -Milliseconds ([int][math]::Ceiling($left)) }
        }
    }
}

function Invoke-TileCheck {
    Stop-Kestrel
```

Replace

```powershell
$microphoneBefore = Test-MicrophonePermission
```

with

```powershell
$microphoneBefore = Test-KestrelPermission $microphonePermission
$notificationsBefore = Test-KestrelPermission $notificationPermission
```

Replace

```powershell
    # Revoking a permission ends Kestrel's process, so this comes last.
    if (-not $microphoneBefore) {
        Register-Restore "Kestrel's microphone permission" (Format-DeviceCommand 'pm revoke dev.kestrel android.permission.RECORD_AUDIO') {
            Assert-TestDevice -Serial $Serial
            if (Test-MicrophonePermission) { Invoke-Checked 'pm revoke dev.kestrel android.permission.RECORD_AUDIO' | Out-Null }
            if (Test-MicrophonePermission) { throw 'it is still granted' }
        }
    }
```

with

```powershell
    # Get-UiDump deletes its file after each read; this also covers a Ctrl+C that came in between. On a phone the file
    # holds the text of the owner's notifications.
    Register-Restore 'the dump of the screen' (Format-DeviceCommand "rm -f $uiDumpFile") {
        Assert-TestDevice -Serial $Serial
        Invoke-Checked "rm -f $uiDumpFile" | Out-Null
    }
    # Revoking a permission ends Kestrel's process, so these come last.
    if (-not $notificationsBefore) {
        Register-Restore "Kestrel's notification permission" (Format-DeviceCommand "pm revoke dev.kestrel $notificationPermission") {
            Assert-TestDevice -Serial $Serial
            if (Test-KestrelPermission $notificationPermission) { Invoke-Checked "pm revoke dev.kestrel $notificationPermission" | Out-Null }
            if (Test-KestrelPermission $notificationPermission) { throw 'it is still granted' }
        }
    }
    if (-not $microphoneBefore) {
        Register-Restore "Kestrel's microphone permission" (Format-DeviceCommand "pm revoke dev.kestrel $microphonePermission") {
            Assert-TestDevice -Serial $Serial
            if (Test-KestrelPermission $microphonePermission) { Invoke-Checked "pm revoke dev.kestrel $microphonePermission" | Out-Null }
            if (Test-KestrelPermission $microphonePermission) { throw 'it is still granted' }
        }
    }
```

Replace

```powershell
    Invoke-Checked 'pm grant dev.kestrel android.permission.RECORD_AUDIO' | Out-Null
```

with

```powershell
    Invoke-Checked "pm grant dev.kestrel $microphonePermission" | Out-Null
    # The notification check taps Kestrel's problems notification.
    Invoke-Checked "pm grant dev.kestrel $notificationPermission" | Out-Null
```

Replace

```powershell
    $results['tile'] = Invoke-TileCheck
```

with

```powershell
    # First: a failed notification check leaves the problems notification, which the later checks' starts remove.
    $results['notification'] = Invoke-NotificationCheck
    $results['tile'] = Invoke-TileCheck
```

Replace

```powershell
        $results.GetEnumerator() | ForEach-Object { Write-Host ('{0,-9} {1}' -f $_.Key, $_.Value) }
```

with

```powershell
        $results.GetEnumerator() | ForEach-Object { Write-Host ('{0,-12} {1}' -f $_.Key, $_.Value) }
```

- [ ] **Step 7: Check the scripts**

Run: `scripts\test-scripts.ps1`
Expected: "Tests Passed: 95, Failed: 0" in both shells, "PSScriptAnalyzer found 0 problems." and "The script tests and checks passed."

- [ ] **Step 8: Update the spec and the README**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 19.1, in the bullet on S1, replace

```markdown
The start from a notification action and the recording while a call rings are no longer scripted; plans 2C-4 and 2D check them again.
```

with

```markdown
Plan 2C-4's notification check in `scripts\listen-check.ps1` repeats the start from a notification action with Kestrel's real problems notification: with Kestrel in the background and its accessibility service off, Android refuses a start through a hook, and the check taps "Start listening" in the notification shade and checks that listening starts and that Android does not silence the microphone. The notification's action is a service's `PendingIntent`, because a broadcast would leave only about 9 s for the microphone (prototype P2). The recording while a call rings is no longer scripted; plan 2D checks it again.
```

In `README.md`, replace

```markdown
so like the privacy-toggle test it skips itself on a phone, and `voice-test.ps1` leaves all three tests out there (the third is `ActivationTest`'s tile test, above). On an emulator the script turns the lock screen off again at the end, in case the test's process died before it could.
```

with

```markdown
so like the privacy-toggle test it skips itself on a phone, and `voice-test.ps1` leaves all four tests out there (the third is `ActivationTest`'s tile test, above). The fourth, `RefusedReplyTest`, sets Kestrel's app op `TAKE_AUDIO_FOCUS` to `ignore`, so that Android refuses Kestrel's replies, and back to `foreground`, the op's normal mode on Android 17; `default` would still refuse them. On an emulator the script turns the lock screen off again at the end and sets the op back to `foreground`, in case the test's process died before it could.
```

Replace

```markdown
`scripts\listen-check.ps1 -Serial $serial` checks the ways in which listening starts without a word, with Kestrel in the background: a tap on the tile while the accessibility service is off,
```

with

```markdown
`scripts\listen-check.ps1 -Serial $serial` checks the ways in which listening starts without a word, with Kestrel in the background: a tap on "Start listening" in Kestrel's problems notification after Android refused a start through the hook while the accessibility service is off, a tap on the tile while the accessibility service is off,
```

Replace

```markdown
While it runs, it grants Kestrel the microphone permission, replaces the list of enabled accessibility services, sets the target of the accessibility shortcut, and puts Kestrel's tile first in Quick Settings and opens the panel for the tile check.
```

with

```markdown
While it runs, it grants Kestrel the microphone and notification permissions, replaces the list of enabled accessibility services, sets the target of the accessibility shortcut, opens the notification shade, and puts Kestrel's tile first in Quick Settings and opens the panel for the tile check. It finds the notification's button with `uiautomator dump`, whose file it deletes and never prints, and it expands Kestrel's notification with a swipe when the shade shows it collapsed. When Kestrel's accessibility service was off before the run, Kestrel's notification that the service is off stays afterwards, because the shell cannot remove another app's notification; swipe it away.
```

- [ ] **Step 9: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (180 in `:app`, 116 in `:core:voice`), lint in every module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests compile.

- [ ] **Step 10: Run the voice device tests**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 66 run, 0 failed, 0 errors, 0 skipped." (65 and `RefusedReplyTest`), and no warning from the restores at the end. Then check the app op: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; & (Get-Adb) -s emulator-5554 shell cmd appops get dev.kestrel TAKE_AUDIO_FOCUS` prints `TAKE_AUDIO_FOCUS: foreground` (possibly with a time after it).

- [ ] **Step 11: Run the other device tests of `:app`**

Task 4 changed what every connection and disconnection of the accessibility service does, which each screen test goes through, and Task 5 reads the exit records at the first connection of each process. This step runs every device test of `:app` outside the voice package; `voice-test.ps1` ran those of the voice package in Step 10.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.notPackage=dev.kestrel.app.voice' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' --no-configuration-cache`
Expected: `BUILD SUCCESSFUL`, and the result files in `app\build\outputs\androidTest-results\connected\debug\` show 103 tests and 0 failures: the 99 of the baseline, and Task 4's two tests in `SettingsAppTest`, `SetupStatusReaderTest.seesRestrictedBatteryUse` and `AccessibilityNoticeTest`.

- [ ] **Step 12: Run listen-check.ps1 from a cold boot**

The run needs Kestrel's current debug build, which Steps 10 and 11 installed, and the speech models, which stay on the device. No `am crash` has run in the 2 minutes before it. The command closes the emulator, waits until it has closed, starts it from a cold boot, checks the new serial, and then runs every check, the reboot too:

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; if ("$(& $adb -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; & $adb -s emulator-5554 emu kill; $deadline = (Get-Date).AddMinutes(2); while ((Get-CimInstance Win32_Process -Filter "Name LIKE 'qemu-system%'" | Where-Object { $_.CommandLine -match '-avd\s+kestrel-api37(\s|$)' }) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 2 }; if (Get-CimInstance Win32_Process -Filter "Name LIKE 'qemu-system%'" | Where-Object { $_.CommandLine -match '-avd\s+kestrel-api37(\s|$)' }) { throw 'kestrel-api37 did not close within 2 minutes' }; $serial = scripts\start-emulator.ps1 -ColdBoot; if ("$(& $adb -s $serial emu avd name)" -notmatch '^kestrel-api37\b') { throw "$serial is not kestrel-api37" }; pwsh -NoProfile -File scripts\listen-check.ps1 -Serial $serial -Reboot; "exit code $LASTEXITCODE"`
Expected: six lines, each of which begins with the check's name and then `ok:`, in this order, with the seconds that the run measured, and then `exit code 0`:

```
notification ok: Android refused a start in the background, and "Start listening" in the notification started listening in <seconds> s
tile         ok: listening <seconds> s after a tap, and a second tap paused it
shortcut     ok: listening <seconds> s after the shortcut, and a second press paused it
settings     ok: Kestrel's settings came to the front <seconds> s after 'Kestrel, open settings'
restart      ok: listening again <seconds> s after Android ended Kestrel's process
reboot       ok: listening again <seconds> s after the reboot
```

No warning follows the lines. The restart and reboot lines also check Task 5's restore in a new process: after `am crash` the newest record is a crash, but Kestrel was turned on by the hook and not by a restore, so it comes back; after the reboot the newest record is older than the stored mode. If the notification line fails with "Android let the hook start listening in the background", Kestrel was still allowed to start from the background; run the script again after 2 minutes. Put the six lines into the task's report, so that the controller can record them in `.superpowers/sdd/progress.md`.

Then check the device: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; $serial = Find-EmulatorSerial 'kestrel-api37'; if (-not $serial -or "$(& $adb -s $serial emu avd name)" -notmatch '^kestrel-api37\b') { throw 'kestrel-api37 is not running' }; pwsh -NoProfile -File scripts\hook.ps1 -Serial $serial voice-state; & $adb -s $serial shell cmd appops get dev.kestrel TAKE_AUDIO_FOCUS; & $adb -s $serial shell cmd appops get dev.kestrel RUN_ANY_IN_BACKGROUND`
Expected: the state starts with `mode=off` and contains `session=closed`; `TAKE_AUDIO_FOCUS: foreground`; `RUN_ANY_IN_BACKGROUND: allow`, or "No operations." when Kestrel has no entry for it.

The restart and reboot checks also pass when Kestrel cannot read its exit records, because the restore then restores as before and reports "Reading the exit records". The reboot check's restore read a record, so check that Kestrel logged no such failure since the boot (Kestrel logs only fixed descriptions and classes): `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; $serial = Find-EmulatorSerial 'kestrel-api37'; if (-not $serial -or "$(& $adb -s $serial emu avd name)" -notmatch '^kestrel-api37\b') { throw 'kestrel-api37 is not running' }; & $adb -s $serial logcat -d -s Kestrel:W | Select-String -SimpleMatch 'Reading the exit records failed'`
Expected: no output.

- [ ] **Step 13: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 14: Commit**

```powershell
git add app/src/androidTest/kotlin/dev/kestrel/app/voice/RefusedReplyTest.kt scripts/voice-test.ps1 scripts/lib/Kestrel.ps1 scripts/tests/Kestrel.Tests.ps1 scripts/listen-check.ps1 docs/superpowers/specs/2026-11-12-kestrel-design.md README.md
git commit -m "test: check a refused reply on the device, and the problems notification's Start listening with listen-check.ps1" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Self-review

**Spec and design coverage.**

| Design decision | Where |
|---|---|
| D1: `CANNOT_START` replaced by texts by cause, decided where the failure is reported (`turnOff(what, error, reason)`): a refused start in the background (any failure of `startForegroundService()`, and a `SecurityException` from `startForeground()` with the permission), a missing permission (Android 14 and later at `startForeground()`, Android 13 at the microphone), missing models, other failures while the session opens, a failed microphone, a stop by Android (`onServiceDestroyed`, with the battery text of D3 when restricted) | Task 1 (`StopReason`, `startFailure`, `microphoneFailure`, `destroyed`), Task 4 (`BATTERY_RESTRICTED` in `destroyed`) |
| D1: the final wording of every text, `MIC_BUSY` as "Microphone blocked", short enough for the bar | Task 1 (`ListeningTexts`) |
| D1: "Yes?" on the bar while Kestrel waits for the task | Task 1 (`Feedback.AwaitingTask` in `askForTask` and in the interruption with "Kestrel"; `say` does not show a refused "Yes?" twice) |
| D1: JVM tests for each cause | Task 1 (four new `ListeningTest` tests and four changed ones), Task 4 (`aStopByAndroidUnderTheBatteryRestrictionSaysSo`) |
| D2: every reason also a notification on its own channel, title by `starting`, text the reason, tap opens Kestrel, "Start listening" when `restart`, gone when a session opens, swipeable, never for the owner's Off, nothing without `POST_NOTIFICATIONS` | Task 2 (`StopNotices`, `ProblemNotifications.show`, `stopBecause(reason, notify)`, `notices.clear()` in `open`) |
| D2: "Start listening" as `PendingIntent.getForegroundService` with `ACTION_START`; `Listening.onStartAction` marks the start as asked for from `STOPPED`, sends `Event.Start()`, adopts the instance; JVM tests from Off, while on, and a TurnOff right after | Task 2 (`startIntent`, `onStartCommand`, `onStartAction`, the five new tests, among them `theStartActionWhileASessionClosesKeepsTheServiceForTheNewSession`) |
| D3: a silenced microphone with type 0 under the restriction turns Kestrel off at once with the battery text; the same text for a stop by Android under the restriction; JVM tests with fakes | Task 4 (`demotedByBattery`, `performUnguarded`, `destroyed`, three tests) |
| D3: the checklist's battery step warns while Restricted, "To do" for "Optimized"; Samsung's "Never sleeping apps" only on a Samsung | Task 4 (`SetupStatus.batteryRestricted` and `samsung`, `SetupStatusReader`, `ChecklistPage`, `SetupChecklistTest`, `SettingsAppTest`, `SetupStatusReaderTest.seesRestrictedBatteryUse`) |
| D4: a notification when the accessibility service disconnects, which says that listening goes on if it is on and goes when the service connects again; the controller's decision: a tap opens Android's accessibility settings (`Settings.ACTION_ACCESSIBILITY_SETTINGS`); spec 14.6's row changed | Task 4 (`accessibilityServiceOff` and `On`, `KestrelAccessibilityService`, `AccessibilityNoticeTest`) |
| D4: the checklist tells "off" from "on but not running" through `ENABLED_ACCESSIBILITY_SERVICES` | Task 4 (`SetupStatus.serviceEnabled`, `states`, `setup_service_warning`, tests) |
| D5: the stored mode with `writtenAt` and `restoredAt`; the newest exit record read before the write of Off; an older record ignored (reboot); `OWNER_STOPPED` stays off; a crash less than 2 minutes after a restore stays off with the notification and "Start listening"; anything else restores; `ExitRecords` with a fake, `AndroidExitRecords` on `Dispatchers.IO`, no description logged; JVM tests for each rule; spec 8.5 | Task 5; also a crash before the restored session opened (`aCrashBeforeTheRestoredSessionOpenedKeepsKestrelOffAndSaysSo`) and a crash after the owner's own start (`aCrashAfterTheOwnersOwnStartRestoresListening`) and an end without a crash before the restored session opened (`aRestoredProcessThatEndedWithoutACrashBeforeItsSessionOpenedStaysOff`); on the device, Task 7, Step 12 checks that Kestrel could read its exit records |
| Final review of plan 2C-2 on D5: the read before the write of Off, `wasOn` and the mode checked after it, `restoreChecked` before the first suspension, a missing time lets any record decide, an age limit considered | Task 5 (`onAccessibilityServiceConnected`, `restoreAfter`, `theOwnersChoiceWhileTheExitRecordsAreReadIsKept`, `aModeWithoutATimeLetsTheNewestRecordDecide`); the age limit is in "Not in this plan" |
| D6: `Dropped` and `Failed` as `Event.NothingHeard`, which keeps the Orchestrator's behaviour; three real empty transcripts in a row show the hint once per session; words start the count again; JVM tests | Task 6 |
| D7: Pause, Resume and Stop as broadcasts to a non-exported receiver; the repost kept for real starts; a press in the background on the device; the swiped notification kept and documented | Task 3 |
| D8: a device test of `REFUSED` with `TAKE_AUDIO_FOCUS ignore`, restored with `foreground`, and the README | Task 7 (`RefusedReplyTest`, `voice-test.ps1`, README) |
| D8: `listen-check.ps1`'s notification check, with the shade, `uiautomator dump`, `input tap` and the recording; Pester tests for the new helper; `test-scripts.ps1` in both shells; spec 19.1 | Task 7 (`Invoke-NotificationCheck`, `Get-UiDump`, `Get-UiNodeCentre`, three Pester tests with four cases) |
| D8: the voice device tests, the other device tests of `:app` and `listen-check.ps1 -Reboot` on emulator-5554 at the end, from a cold boot; the restore in a new process | Task 7, Steps 10, 11 and 12 |
| The controller's decision: `listen-check.ps1` expands a collapsed problems notification before it fails to find "Start listening" | Task 7 (`Invoke-NotificationCheck`, a swipe down on the notification's title) |
| Final review of plan 2C-3, N2: the logging rule in spec 13.5 | Task 5, Step 7 |
| Final review of plan 2C-3, "For plan 2C-4": (1) the session's events go through its `Listener` (`NothingHeard` from `VoiceSession`; the battery check runs in an effect, not as an event); (2) `onStartAction` sets `serviceState` and `requested` together, clears `offReason` and adopts without the lock; (3) every new turn-off goes through `stopBecause` and `Event.TurnOff`; (5) the bar shows "Yes?" at the start of every wait for a task, also after an interruption; (6) only real empty transcripts count; (7) the repost stays, and the comments in `foreground()`, `onForegroundFailed`, `start()`, the class's KDoc and `ServiceControl.stop()` are updated; (10) N4's test exists at `75b21bc` | Tasks 1, 2, 3, 4 and 6; items 4 and 8 are in "Not in this plan", item 9 is plan 2D's |
| Final review of plan 2C-2, M-3: "Kestrel, open settings" without the accessibility service; `openSettings` returns false; a spoken reply; a `ListeningTest` case for each branch; the README's sentence | Task 1 (`Event.SettingsUnavailable`, `Reply.SettingsNeedService`, `kestrelOpenSettingsWithoutTheAccessibilityServiceSaysWhy`; `kestrelOpenSettingsOpensTheSettingsApp` covers the other branch) |
| Final review of plan 2C-2 on D2 and D4: `Event.Start` from the action sets `wasOn`, so a restore in the same process stops, and acts only from Off; no restore after the service reconnects in the same process | Tasks 2 and 5 (unchanged rules, `theStartActionWhileKestrelIsOnChangesNothing`) |
| The expected counts after each task | Steps 6, 8 and 9 of Task 1; Steps 6, 9 and 10 of Task 2; Steps 2, 5 and 6 of Task 3; Steps 6, 9, 10 and 11 of Task 4; Steps 6, 8 and 9 of Task 5; Steps 5, 7 and 8 of Task 6; Steps 2, 4, 7, 9, 10 and 11 of Task 7 |

**Placeholder scan.** No "TBD", "TODO", "similar to Task N" or step without its code or command. The only open values are the seconds that `listen-check.ps1` measures in Task 7, Step 12, which the run prints. Every changed file shows the exact text that it replaces, or the whole new file. A script applied all 202 operations of the plan (replacements, new files, whole files and additions at the end of a file), in order, to copies of the files at `75b21bc`: every replaced text existed, and existed once, when its turn came, and the result has the counts below (`ListeningTest` 87, `RulesTest` 42, `SetupChecklistTest` 9, `ListeningServiceTest` 5, `SettingsAppTest` 17, `SetupStatusReaderTest` 4). The script ends a code block only at a line of three backticks, as Markdown does, and fails when a block never closes, when a fence opens inside a block, when a block holds the plan's own text (a step, a "Replace", a "with", a Run or Expected line), or when a "replace" has no old block, "with" and new block.

**Type consistency.** `StopReason(text, restart, starting)` is used by `Listening`, `ProblemNotifications` and the tests; `StopNotices.show(reason)` and `clear()` by `Listening`, `ProblemNotifications` and `FakeNotices`. `ListeningConditions.microphoneAllowed()`, `backgroundRestricted()` and `demoted(service: Any)` match `AppGraph` and `FakeConditions`. `Listening(scope, thread, sessions, services, bar, report, openSettings: () -> Boolean, memory, conditions, notices, exits, clock, microphones)` matches `AppGraph` (named arguments), the helper `listening` and `anErrorWhileStoringTheModeIsReportedAndLaterModesAreStillStored`. `onStartAction(instance: Any)` and `onForegroundFailed(error, startAction = false)` match `ListeningService.onStartCommand` and the tests. `ModeMemory.read(): StoredMode` and `write(mode, writtenAt: Long, restoredAt: Long?)` match `AppGraph`, `FakeMemory` and the direct construction; `ListeningSetting(mode, writtenAt, restoredAt)` keeps its defaults for `VoiceHooks`, `ScreenServiceRule` and `VoiceHookTest`. `ExitRecord(reason, timestamp)`, `ExitReason.OWNER_STOPPED`, `CRASH`, `OTHER` and `ExitRecords.newest()` match `AndroidExitRecords`, `restoreAfter` and `FakeExits`. `Feedback.AwaitingTask` and `NoWords`, `Event.SettingsUnavailable` and `NothingHeard`, and `Reply.SettingsNeedService` are handled in `Rules.next` and in `Listening.say` and `text`, whose `when`s stay exhaustive. `SetupStatus(..., models, serviceEnabled = serviceOn, batteryRestricted = false, samsung = false)` matches `SetupStatusReader`, `SetupChecklistTest` and `SettingsAppTest`; `ChecklistPage(states, models, samsung, onAction, onBack)` matches `SettingsApp`; `SettingsApp`'s signature does not change. `ProblemNotifications.STOPPED_ID` and `ACCESSIBILITY_ID` match `ListeningServiceTest` and `AccessibilityNoticeTest`. `Get-UiNodeCentre -Dump -Text` returns `X` and `Y`, which `Invoke-NotificationCheck` uses.

**Counts.**

| After | `:app` JVM | `:core:voice` JVM | Voice device tests (`voice-test.ps1`) | Other device tests (`connected-test.ps1`) | Pester (per shell) |
|---|---|---|---|---|---|
| Start (`75b21bc`) | 150 | 116 | 63 | 99, among them `SettingsAppTest` 15, `SetupStatusReaderTest` 3, `MainActivityTest` 4 | 91 |
| Task 1 | 155 (`ListeningTest` 63 → 67, `RulesTest` 38 → 39; changed tests in both) | 116 | 63 | not run | 91 |
| Task 2 | 160 (`ListeningTest` 67 → 72; five tests gain assertions) | 116 | 64 (`ListeningServiceTest` 3 → 4; one test changed) | not run | 91 |
| Task 3 | 160 | 116 | 65 (`ListeningServiceTest` 4 → 5) | not run | 91 |
| Task 4 | 165 (`ListeningTest` 72 → 75, `SetupChecklistTest` 7 → 9) | 116 | 65 | 26 run: `SettingsAppTest` 17, `SetupStatusReaderTest` 4 (one changed), `AccessibilityNoticeTest` 1 (new), `MainActivityTest` 4 | 91 |
| Task 5 | 177 (`ListeningTest` 75 → 87) | 116 | 65 | not run | 91 |
| Task 6 | 180 (`RulesTest` 39 → 42; one line changed) | 116 | 65 (`VoiceSessionFailuresTest` 7, one test renamed and changed) | not run | 91 |
| Task 7 | 180 | 116 | 66 (`RefusedReplyTest` 1, new) | 103 (all outside the voice package) | 95 (`Get-UiNodeCentre` 4) |

**Decisions this plan made beyond the design, and where it departs from it.**
- D1: one `StopReason` per cause carries its text, whether "Start listening" can help (`restart`), and whether the notification's title says "could not start" (`starting`), instead of tracking whether Kestrel had listened. `Listening` cannot name `ForegroundServiceStartNotAllowedException` in JVM tests, so every failure of `startForegroundService()` that is not a `SecurityException` counts as a refused start; on API 33 and later it is the only other documented one.
- D1: "Yes?" shows through a new `Feedback.AwaitingTask` from the Rules, at "Kestrel" on its own and at an interruption with "Kestrel", so it shows also while "Yes?" cannot be spoken; a refused or failed "Yes?" is not shown a second time.
- M-3: the design offered a spoken reply or the problems notification; the plan chose the spoken reply (`Event.SettingsUnavailable` and `Reply.SettingsNeedService`), because the owner asked by voice and the voice works without the service.
- D2: engines that did not load get no "Start listening", because the design does not list them; a "Start listening" whose `startForeground()` fails while Kestrel is off says why on the bar and in the notification.
- D2 on the device: `ListeningServiceTest` sends "Start listening" with `MainActivity` in front; the real tap in the shade, with Kestrel in the background, is `listen-check.ps1`'s check.
- D3: the battery step shows the warning while Restricted also when Kestrel ignores the battery optimisations, because the restriction stops listening either way.
- D4: the notification about the accessibility service has its own channel of low importance, not the problems channel, because the owner usually turns the service off on purpose and the device tests turn it off and on many times; the problems channel would make a sound each time. It is not ongoing, so the owner can swipe it away. By the controller's decision, a tap opens Android's accessibility settings (`Settings.ACTION_ACCESSIBILITY_SETTINGS`), where Kestrel's service is listed: Android's page of one service (`ACTION_ACCESSIBILITY_DETAILS_SETTINGS`) is not in the public SDK at compileSdk 37, and its activity needs a permission that only the system and the installer hold. The text says "If Kestrel is listening, it goes on listening", because the notice also appears while Kestrel is off. The state "on but not running" cannot be produced in a device test, because "Stop app" ends the test's process too.
- D5: no age limit for the restore (see "Not in this plan"). `REASON_USER_STOPPED` counts as `OTHER`, because Android uses it when it stops a user profile, not when the owner stops Kestrel. A stored Off that carries the time of a restore means that the restored process ended before its session opened: Kestrel stays off, as "Off first" intends, and says why after a crash soon after the restore. The rules hold on Android 14 and later; Android 13's update reason and a restart that loses the record are documented in spec 8.5, the README and "Not in this plan". `restoredAt` is cleared at every Off, so that a crash after the owner's own start does not count as one soon after a restore; `listen-check.ps1`'s restart check depends on this. Records that cannot be read do not stop the restore.
- D6: the count lives in the Orchestrator's `State` (`emptyTranscripts`, `noWordsShown`), which Off resets, so "once per voice session" is "once until Kestrel turns off". The hint names the microphone, not its permission, because without the permission Kestrel cannot start at all (spec 14.6's row changes).
- D7: no JVM test, because the receiver is Android's. The presses in the device test would pass before the change, because the instrumented process lets Kestrel start its service, so the test also checks that every action's `PendingIntent` is a broadcast (`isBroadcast()`), which fails before the change.
- D8: `RefusedReplyTest` runs only on an emulator, skipped by a rule of order 0 before `ScreenServiceRule` so that it changes nothing on a phone, and `voice-test.ps1` restores the op there; `SetupStatusReaderTest.seesRestrictedBatteryUse` also runs only on an emulator. The reason is that the normal modes of the two app ops on Android 13 are not known, and a test process that dies between its two commands would leave a phone's op changed. `listen-check.ps1` cannot remove another app's notification from the shell, so its notification check runs first, and the later checks' starts remove a notification that a failed check left. The check reads Kestrel's state before it opens the shade, so that an old "Start listening" cannot stand in for a refusal, and it waits until 30 s after its tap before the tile check, so that the tile check cannot use the exemption that the tap gave.
- The plan has seven tasks instead of the design's six: D5 and D6 are separate tasks, because they change different files and a reviewer can judge each alone.

