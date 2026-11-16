# Voice 2C-2: Activation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the owner start, pause and stop Kestrel's listening without the debug hooks: Kestrel's own phrases "Kestrel, turn off" and "Kestrel, open settings", the accessibility button and shortcut, a real Quick Settings tile, and the return of the last mode after Android ended Kestrel's process or the phone restarted; then replace the spike's stand-ins and check the real start paths with a script.

**Architecture:** The Orchestrator gains `Event.Start(paused)`, `Request.OpenSettings` and `Effect.OpenSettings`, and `Requests` reads "Kestrel, turn off", "Kestrel, open settings" and, after "Yes?", "never mind". `KestrelAccessibilityService` registers an accessibility-button callback that sends `Event.Toggle`. `KestrelTileService` (package `dev.kestrel.app.listen`) shows the mode and sends `Event.Toggle`. `Listening` stores the mode in a new setting through a `ModeMemory` seam (Listening or Paused once a voice session is open, Off at once), and restores it once per process when the accessibility service connects, if Kestrel has not been on in that process; the restore stores Off before it starts Kestrel. The debug stand-ins of spike S1 go, and `scripts\listen-check.ps1` checks the start paths of the real service on a device.

**Tech Stack:** Kotlin 2.4.21, kotlinx.coroutines 1.11.0, kotlinx.serialization 1.13.0 (its compiler plugin, already used by the `core` modules, now also in `:app`), Android's `AccessibilityButtonController`, `TileService` and `Tile` at compileSdk 37, JUnit 4 with kotlinx-coroutines-test, device tests on the `kestrel-api37` emulator through `scripts\voice-test.ps1`, and Pester 6.2.0 with PSScriptAnalyzer 1.25.0 through `scripts\test-scripts.ps1`.

## Global Constraints

- Package and application ID `dev.kestrel`; licence GPL-3.0-or-later; no new dependency that ships in the app. (The serialization compiler plugin is a build plugin; `kotlinx-serialization-json` already reaches `:app` through `:core:privacy`.)
- `minSdk 33`, `targetSdk 37`, `compileSdk 37`.
- No dependency-injection framework; `AppGraph` wires by hand.
- Never log audio, transcripts, replies, boost words, screen text or exception messages (section 13.5); `toString` of anything that carries the owner's words prints only lengths.
- Test only on `kestrel-api37` (emulator-5554); never on the S23+ or the AVD `zoran`; never send commands to the A71. Gradle only through `scripts\gradle.ps1` (stopped with `--stop`), device tests only through `scripts\voice-test.ps1` or `scripts\connected-test.ps1`, installs only through `scripts\install.ps1`. Device tests restore every setting they change and keep the APKs installed.
- Scripts run on Windows PowerShell 5.1 and PowerShell 7; `scripts\test-scripts.ps1` passes after every script change. (A script that calls `scripts\hook.ps1` declares `#Requires -Version 7.3`, as `hook.ps1` and `spike-s1.ps1` do; the helpers that it adds to `scripts\lib\Kestrel.ps1` run in both shells, and Pester tests them in both.)
- The repository uses LF line endings.

## Before you start

- Plan 2C-1 is complete (HEAD `648f4f7`). The current totals: `:app` JVM 99 tests, `:core:voice` JVM 99, voice device tests 57, Pester 70 per shell. This plan changes no code in `:core:voice`, so its 99 stay.
- What the listening service really does now (`.superpowers/sdd/progress.md`, plan 2C-1): `Listening` (in `AppGraph.listening`) owns the Orchestrator on one single-threaded dispatcher, starts `ListeningService` when the mode leaves Off, opens a `VoiceSession` once the service reports the foreground, and closes the session and stops the service on `Effect.StopService`. A start that fails turns Kestrel off with a reason that replaces "Off" on the feedback bar. `follow()` runs again after every shutdown. `VoiceTests.startListening(clip)`, `useMicrophone(microphone)`, `stopListening()`, `waitUntil(...)`, `shell(...)` and `grantListeningPermissions()` are the device tests' helpers; `ScreenServiceRule` (package `dev.kestrel.app.screen`) turns Kestrel's accessibility service off and on before each test and offers a `UiAutomation` that does not suppress accessibility services.
- Prototype P1 (`.superpowers/sdd/research/p1-prototype-2c.md`, Q2) on kestrel-api37: `cmd accessibility call-system-action 13` and `UiAutomation.performGlobalAction(13)` reach `onClicked` when `accessibility_shortcut_target_service` names Kestrel and its service is bound; `performGlobalAction(11)` reaches it on this image's gestural navigation only with `accessibility_gesture_targets` set, and returns false when Android does not offer the action. Enabling Kestrel's service adds it to `accessibility_button_targets` by itself; disabling it empties the targets. Research R14: the shell command always exits with 0, so a script can see a click only in Kestrel's state.
- Run Gradle only through `scripts\gradle.ps1`, and stop it with `scripts\gradle.ps1 --stop` when you are done.
- Run the device tests only through `scripts\voice-test.ps1 -Serial emulator-5554`, or, for one test class, through `scripts\connected-test.ps1` with the arguments that the steps give: they keep Kestrel installed, so the speech models stay on the device. All new device tests of this plan live in the package `dev.kestrel.app.voice`, because `voice-test.ps1` copies the models for that package. Before each device step, check the emulator: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; & (Get-Adb) -s emulator-5554 emu avd name` must print `kestrel-api37` (then `OK`). If it prints anything else, or no emulator runs, start it with `scripts\start-emulator.ps1` and use the serial that it prints instead of `emulator-5554`.
- After you change a script, run `scripts\test-scripts.ps1` (AGENTS.md).
- From Task 4 on, Kestrel remembers its mode across restarts of its process, and brings it back when its accessibility service connects. The device tests do not depend on it: `ScreenServiceRule` stores Off before it turns the service on. To keep Kestrel from coming back by hand, for example after `listen-check.ps1` was stopped twice with Ctrl+C, run `scripts\hook.ps1 -Serial emulator-5554 voice -Send off`, which stops a restore that runs at the same time and stores Off, also while Kestrel is off.
- Every commit message ends with the line `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.

## Not in this plan

Plan 2C-3: boost words beyond "Kestrel"; the final texts of the feedback bar (including "Waiting for your task" and mic-busy wording that also fits the privacy toggle); failure reasons by cause and as a notification with a "Start listening" action (`Event.Start`, which this plan adds); the "Kestrel is off" notification when the accessibility service disconnects; the empty-transcript hint of section 14.6; detecting a `startForeground()` that Android ignored; restarting the microphone once after an error such as `ERROR_DEAD_OBJECT`; plan 2A's deferred rows 9, 10, 11 and 13.

From the final review of plan 2C-1, "For plan 2C-2", the items that are not activation:

| Item | What | Plan |
|---|---|---|
| 9 | The owner can swipe the service's notification away since Android 14; does section 8.6's "persistent notification" need more? | 2C-3 |
| 10 | The notification's actions with Kestrel in the background, and broadcast `PendingIntent`s to a receiver for Pause, Resume and Stop | 2C-3 |
| 11 | A start that waits behind a closing session asks Android for the service only after the close, which can outlast the short exemption of a tap on the tile or a notification; `listen-check.ps1` starts the tile from a closed session, so it cannot see this | 2C-3 |
| 14 (the rest) | A device test of a refused reply (`REFUSED`) | 2C-3 |
| 14 (the rest) | The player's focus requests without a listener, and focus given up between queued replies | 2D (with calls and audio focus) |
| 14 (the rest) | Appendix A's other commands with the name ("continue", "save that as", "remember", "forget", "delete history"), which still read as tasks | Sub-projects 3 and 5, with the features that they control |
| 15 | A session tag that the Orchestrator checks, so that an event of a closing session cannot act on the next state | 2C-3 |
| 16 | A test that calls `VOICE_CLIP` must put the microphone factory back itself | 2C-3 |

Also for plan 2C-3, with item 10: spike S1's start from a notification action, which no script checks any more since this plan removes the stand-in's notification hook.

Also for plan 2C-3: the restore after a crash that came after the voice session opened, and after the owner stopped Kestrel from Android (Force stop, or Stop in the list of foreground services), both of which leave the mode stored: read `ActivityManager.getHistoricalProcessExitReasons()` at the restore, and do not restore after `REASON_USER_REQUESTED` or `REASON_USER_STOPPED`, or after a crash soon after the last restore.

Plan 2D: the assistant role, earbuds, calls (with spike S1's recording while a call rings on the lock screen, which no script checks any more), the voice settings, the corpus and the A71 session.

## Threads

| Call | Thread | Notes |
|---|---|---|
| `KestrelAccessibilityService.onServiceConnected`, the button callback's `onClicked`, `disconnect()` | The main thread | `onClicked` only sends `Event.Toggle`; `onServiceConnected` calls `Listening.onAccessibilityServiceConnected()`, which returns at once |
| `KestrelTileService.onStartListening`, `onStopListening`, `onClick`, `onDestroy`, and the tile's updates | The main thread; the mode is collected on `Dispatchers.Main.immediate` in the tile's own scope | `onClick` only sends `Event.Toggle` |
| `Listening.send` | Any thread | Unchanged |
| `Effect.OpenSettings` | The controller's thread (`perform`), which calls `startActivity` on the application context | `Context.startActivity` may be called from any thread |
| The restore's check, its write of Off and its `Event.Start`; `Listening.skipRestore()`, which the debug hook's `off` calls | A coroutine on the controller's thread (`skipRestore` switches to it with `withContext`) | `ModeMemory.read()` and `write()` suspend; `SettingsStore` decrypts and encrypts on `Dispatchers.IO`. Nothing else writes then, because Kestrel has not been on in the process |
| Storing the mode | `perform()` (each `Feedback.ModeChanged`) and `open()` (controller's thread) note each new mode in a `StateFlow`, Listening or Paused only while a voice session is open; one coroutine in `AppGraph.scope` (`Dispatchers.IO`) writes it | One writer, so the stored mode is always the newest; it may skip a mode that a newer one replaced at once |

A tap on the tile and the accessibility binding both let Kestrel start a foreground service of the type microphone from the background (prototype P1, Q1 scenarios 1, 4 and 5). The tile's exemption lasts only a short while; the start follows within milliseconds, on the controller's thread.

## File structure

| File | Responsibility |
|---|---|
| `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt` | `Event.Start(paused)`, `Effect.OpenSettings`, and their rules |
| `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt` | `Request.OpenSettings`; "Kestrel, turn off" and "switch off"; "open settings"; "never mind", "forget it" and "nothing" after "Yes?" |
| `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt` | Performs `Effect.OpenSettings`; `ModeMemory`; stores the mode once a voice session is open, and restores it once per process |
| `app/src/main/kotlin/dev/kestrel/app/listen/ListeningSetting.kt` | The stored mode, a `@Serializable` value (new) |
| `app/src/main/kotlin/dev/kestrel/app/listen/KestrelTileService.kt` | The Quick Settings tile (new) |
| `app/src/main/kotlin/dev/kestrel/app/KestrelAccessibilityService.kt` | The accessibility button's callback; the restore when the service connects |
| `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` | `SettingKeys.LISTENING`; wires `openSettings` and `memory` into `Listening` |
| `app/build.gradle.kts` | Applies the kotlinx.serialization compiler plugin |
| `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`, `app/src/main/res/drawable/ic_tile.xml` | The tile's declaration, texts and icon |
| `app/src/debug/AndroidManifest.xml` | Loses the spike's stand-in service, tile, start-up initializer and the permissions that only they needed |
| `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt` | The hook's `off` also stores Off, so that Kestrel does not come back at its next start |
| `app/src/androidTest/kotlin/dev/kestrel/app/screen/ScreenServiceRule.kt` | Stores Off before it turns the accessibility service on, so that no stored mode starts Kestrel in a test |
| `scripts/voice-test.ps1` | Leaves the tile's device test out on phones, as it does for the other tests that change a device setting |
| `app/src/debug/kotlin/dev/kestrel/app/debug/spike/` | `SpikeListeningService.kt`, `SpikeStartup.kt` and `SpikeTileService.kt` deleted; `SpikeListening.kt` and `SpikeHooks.kt` keep only the location check and the other spikes' hooks |
| `scripts/listen-check.ps1` | Checks the start paths of the real service on a device (new) |
| `scripts/lib/Kestrel.ps1`, `scripts/tests/Kestrel.Tests.ps1` | `ConvertFrom-VoiceState`, `Test-ShortcutTarget` and `Test-RecordingSilenced`, with their Pester tests |
| `scripts/spike-s4.ps1` | Stores Off before it turns Kestrel's accessibility service on, as `spike-s1.ps1` does |
| `scripts/spike-s1.ps1`, `scripts/hook.ps1` | Only the location check; the `listen` commands removed; the help of `voice -Send off` |
| `README.md`, `docs/superpowers/specs/2026-11-12-kestrel-design.md` | How listening starts; `listen-check.ps1`; sections 7.2, 8.3, 8.5, 19.1 and Appendix A |

Tests: `app/src/test/kotlin/dev/kestrel/app/orchestrator/RequestsTest.kt`, `RulesTest.kt` and `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt` (JVM, changed); `app/src/androidTest/kotlin/dev/kestrel/app/voice/ActivationTest.kt` (device, new) and `VoiceHookTest.kt` (device, one more test).

---

### Task 1: Kestrel's own phrases, `Event.Start` and opening the settings

**Files:**
- Modify: `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt` (`Event`, `Effect`, `Rules.next`, `whileAwaitingTask`, `whileIdle`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt` (`Request`, the KDoc of `Requests`, two new sets, `read`, `named`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt` (`ListeningTexts`, the constructor, `performUnguarded`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (`listening`)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 8.3 and 8.5, Appendix A)
- Test: `app/src/test/kotlin/dev/kestrel/app/orchestrator/RequestsTest.kt`, `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`

**Interfaces:**
- Consumes: `Rules`, `State`, `Event`, `Effect`, `Feedback`, `Mode`, `Work`, `Step` (package `dev.kestrel.app.orchestrator`); `Requests.read(transcript: String, spotted: Boolean, afterName: Boolean = false): Request?`; `Listening`'s internal constructor and `ListeningTexts` (package `dev.kestrel.app.listen`).
- Produces: `data class Event.Start(val paused: Boolean = false) : Event` (Task 4's restore sends it; plan 2C-3's notification will too); `data object Effect.OpenSettings : Effect`; `data object Request.OpenSettings : Request`; `ListeningTexts.CANNOT_OPEN_SETTINGS`; `Listening`'s constructor gains `openSettings: () -> Unit` (after `report`, before `microphones`), which Task 4's test helper and `AppGraph` keep passing by name.

Section 8.3 and Appendix A list Kestrel's own phrases. "Kestrel, turn off" (also "switch off") becomes the same `TurnOff` as "Stop Kestrel", while "Kestrel, turn off the wifi" stays a task; as with "Wake up, Kestrel", the name may also come last: "Turn off, Kestrel". "Kestrel, open settings", "Kestrel, open your settings" and "Kestrel settings" open Kestrel's settings app; "Kestrel, open the settings" stays a task, because the owner may mean Android's settings. After "Yes?", "never mind", "forget it" and "nothing" end the wait as a named stop; "Forget the milk" stays a task, because "Kestrel, forget [fact]" is one of Appendix A's commands. `Event.Start` turns Kestrel on only while it is off, so a start that arrives late, such as the restore of Task 4 after the owner already tapped the tile, toggles nothing.

- [ ] **Step 1: Write the failing tests**

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RequestsTest.kt`, add these four tests right after the test `anAnswerToYesIsReadAsTheWordsAfterTheName`:

```kotlin
    @Test
    fun turnOffAndSwitchOffAfterTheNameTurnKestrelOff() {
        assertEquals(Request.TurnOff, Requests.read("Kestrel, turn off.", spotted = false))
        assertEquals(Request.TurnOff, Requests.read("Kestrel, switch off, please.", spotted = true))
        assertEquals(Request.TurnOff, Requests.read("Turn off.", spotted = false, afterName = true))
        assertEquals(Request.Task("turn off the wifi."), Requests.read("Kestrel, turn off the wifi.", spotted = false))
        assertEquals(Request.Other("Turn off."), Requests.read("Turn off.", spotted = false))
    }

    @Test
    fun turnOffMayEndWithTheName() {
        assertEquals(Request.TurnOff, Requests.read("Turn off, Kestrel.", spotted = false))
        assertEquals(Request.TurnOff, Requests.read("Switch off, please, Kestrel.", spotted = false))
        assertEquals(Request.Other("Turn off the wifi, Kestrel."), Requests.read("Turn off the wifi, Kestrel.", spotted = false))
    }

    @Test
    fun kestrelsSettingsOpenWithTheirOwnPhrases() {
        assertEquals(Request.OpenSettings, Requests.read("Kestrel, open settings.", spotted = false))
        assertEquals(Request.OpenSettings, Requests.read("Kestrel, open your settings, please.", spotted = true))
        assertEquals(Request.OpenSettings, Requests.read("Kestrel settings", spotted = false))
        assertEquals(Request.OpenSettings, Requests.read("Open settings.", spotted = false, afterName = true))
        // The owner may mean Android's settings, so these stay a task and an instant command.
        assertEquals(Request.Task("open the settings."), Requests.read("Kestrel, open the settings.", spotted = false))
        assertEquals(Request.Other("Open settings."), Requests.read("Open settings.", spotted = false))
    }

    @Test
    fun anAnswerToYesMayCallItOff() {
        assertEquals(Request.Stop(named = true), Requests.read("Never mind.", spotted = false, afterName = true))
        assertEquals(Request.Stop(named = true), Requests.read("Forget it, please.", spotted = false, afterName = true))
        assertEquals(Request.Stop(named = true), Requests.read("Nothing.", spotted = false, afterName = true))
        assertEquals(Request.Stop(named = true), Requests.read("Kestrel, never mind.", spotted = true, afterName = true))
        assertEquals(Request.Stop(named = true), Requests.read("Never mind, Kestrel.", spotted = false, afterName = true))
        // "Kestrel, forget [fact]" is a command of its own (Appendix A).
        assertEquals(Request.Task("Forget the milk."), Requests.read("Forget the milk.", spotted = false, afterName = true))
        // Only an answer to "Yes?" is called off this way.
        assertEquals(Request.Task("never mind."), Requests.read("Kestrel, never mind.", spotted = false))
        assertEquals(Request.Other("Never mind."), Requests.read("Never mind.", spotted = false))
    }
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, add these five tests at the end of the class, after the test `theNotificationAndOtherEventsThatChangeNothing` and before the class's closing brace:

```kotlin
    @Test
    fun startTurnsKestrelOnOnlyWhileItIsOff() {
        val started = Rules.next(State(), Event.Start())

        assertEquals(Mode.LISTENING, started.state.mode)
        assertEquals(listOf(Effect.Show(Feedback.ModeChanged(Mode.LISTENING))), started.effects)
        assertEquals(Mode.PAUSED, Rules.next(State(), Event.Start(paused = true)).state.mode)
        // A start that comes late must not toggle anything.
        assertEquals(Step(listening), Rules.next(listening, Event.Start(paused = true)))
        assertEquals(Step(paused), Rules.next(paused, Event.Start()))
    }

    @Test
    fun kestrelTurnOffTurnsKestrelOff() {
        val off = Rules.next(paused, Event.Heard("Kestrel, turn off.", named = true))

        assertEquals(Mode.OFF, off.state.mode)
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Kestrel, turn off.")), Effect.Show(Feedback.ModeChanged(Mode.OFF)), Effect.StopService),
            off.effects,
        )
    }

    @Test
    fun kestrelOpenSettingsOpensThemWhileListeningOrPaused() {
        assertEquals(
            Step(listening, listOf(Effect.Show(Feedback.Heard("Kestrel, open settings.")), Effect.OpenSettings)),
            Rules.next(listening, Event.Heard("Kestrel, open settings.", named = true)),
        )
        assertEquals(
            Step(paused, listOf(Effect.Show(Feedback.Heard("Kestrel settings")), Effect.OpenSettings)),
            Rules.next(paused, Event.Heard("Kestrel settings", named = true)),
        )
        // Nothing may start while the screen is off (section 14.4).
        val dark = listening.copy(screenOn = false)
        assertEquals(Step(dark), Rules.next(dark, Event.Heard("Kestrel, open settings.", named = true)))
    }

    @Test
    fun openSettingsEndsTheWaitForATaskAndIsIgnoredWhileATaskRuns() {
        val waiting = listening.copy(work = Work.AWAITING_TASK, wait = 1)
        val running = listening.copy(work = Work.RUNNING_TASK, task = 1)

        assertEquals(
            Step(listening.copy(wait = 1), listOf(Effect.Show(Feedback.Heard("Open settings.")), Effect.OpenSettings)),
            Rules.next(waiting, Event.Heard("Open settings.", named = false)),
        )
        assertEquals(Step(running), Rules.next(running, Event.Heard("Kestrel, open settings.", named = true)))
    }

    @Test
    fun neverMindEndsTheWaitForATask() {
        val waiting = listening.copy(work = Work.AWAITING_TASK, wait = 1)

        assertEquals(
            Step(listening.copy(wait = 1), listOf(Effect.Show(Feedback.Heard("Never mind.")))),
            Rules.next(waiting, Event.Heard("Never mind.", named = false)),
        )
    }
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, add a field after `private val service = Any()`:

```kotlin
    private var settingsFailure: Exception? = null
```

Add these two tests right after the test `aServiceThatCannotStartTurnsKestrelOffAndSaysWhy`:

```kotlin
    @Test
    fun kestrelOpenSettingsOpensTheSettingsApp() = runTest {
        val listening = listeningWithSession()
        log.clear()

        listening.send(Event.Heard("Kestrel, open settings.", named = true))
        runCurrent()

        assertEquals(listOf("open settings"), log)
        assertEquals(Mode.LISTENING, listening.state.value.mode)
    }

    @Test
    fun settingsThatCannotOpenAreReportedAndShown() = runTest {
        settingsFailure = IllegalStateException("No activity")
        val listening = listeningWithSession()

        listening.send(Event.Heard("Kestrel settings", named = true))
        runCurrent()

        assertEquals("Opening the settings", reports.single().first)
        assertEquals(ListeningTexts.CANNOT_OPEN_SETTINGS, shown.last())
        assertEquals(Mode.LISTENING, listening.state.value.mode)
    }
```

In the same file, replace the helper

```kotlin
    private fun TestScope.listening(scope: CoroutineScope = backgroundScope): Listening = Listening(
        scope = scope,
        thread = StandardTestDispatcher(testScheduler),
        sessions = sessions,
        services = services,
        bar = { shown += it },
        report = { what, error -> reports += what to error },
        microphones = { microphone },
    )
```

with

```kotlin
    private fun TestScope.listening(scope: CoroutineScope = backgroundScope): Listening = Listening(
        scope = scope,
        thread = StandardTestDispatcher(testScheduler),
        sessions = sessions,
        services = services,
        bar = { shown += it },
        report = { what, error -> reports += what to error },
        openSettings = {
            log += "open settings"
            settingsFailure?.let { throw it }
        },
        microphones = { microphone },
    )
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.orchestrator.RequestsTest --tests dev.kestrel.app.orchestrator.RulesTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD FAILED`, with "Unresolved reference 'OpenSettings'", "Unresolved reference 'Start'", "Unresolved reference 'CANNOT_OPEN_SETTINGS'" and "No parameter with name 'openSettings' found".

- [ ] **Step 3: Add `Event.Start`, `Effect.OpenSettings` and their rules**

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace

```kotlin
    /** The accessibility button or shortcut, or the Quick Settings tile (section 8.6). */
    data object Toggle : Event
```

with

```kotlin
    /** The accessibility button or shortcut, or the Quick Settings tile (section 8.6). */
    data object Toggle : Event

    /**
     * Turns Kestrel on while it is off: listening, or paused when [paused]. While Kestrel listens or is paused it changes
     * nothing, so that a start that comes late cannot toggle anything. The restore after a restart of Kestrel's process
     * sends it (section 8.5).
     */
    data class Start(val paused: Boolean = false) : Event
```

Replace

```kotlin
    /** Stops the listening service. */
    data object StopService : Effect
}
```

with

```kotlin
    /** Stops the listening service. */
    data object StopService : Effect

    /** Opens Kestrel's settings app, for "Kestrel, open settings" (section 15). */
    data object OpenSettings : Effect
}
```

Replace

```kotlin
        Event.Toggle -> switch(state, if (state.mode == Mode.LISTENING) Mode.PAUSED else Mode.LISTENING)
```

with

```kotlin
        Event.Toggle -> switch(state, if (state.mode == Mode.LISTENING) Mode.PAUSED else Mode.LISTENING)
        is Event.Start ->
            if (state.mode == Mode.OFF) switch(state, if (event.paused) Mode.PAUSED else Mode.LISTENING) else Step(state)
```

Replace

```kotlin
        Request.PauseListening -> changeMode(state.copy(work = Work.IDLE), Mode.PAUSED, shown)
        Request.TurnOff -> turnOff(state, shown)
    }
```

with

```kotlin
        Request.PauseListening -> changeMode(state.copy(work = Work.IDLE), Mode.PAUSED, shown)
        Request.TurnOff -> turnOff(state, shown)
        Request.OpenSettings -> Step(state.copy(work = Work.IDLE), listOf(shown, Effect.OpenSettings))
    }
```

Replace

```kotlin
        Request.PauseListening -> changeMode(state, Mode.PAUSED, shown)
        Request.TurnOff -> turnOff(state, shown)
        is Request.Stop -> if (request.named) {
```

with

```kotlin
        Request.PauseListening -> changeMode(state, Mode.PAUSED, shown)
        Request.TurnOff -> turnOff(state, shown)
        Request.OpenSettings -> Step(state, listOf(shown, Effect.OpenSettings))
        is Request.Stop -> if (request.named) {
```

`whileTaskRuns` needs no change: its `else -> Step(state)` ignores `OpenSettings` as it ignores the other requests (section 11.10), and "Kestrel, turn off" reaches its existing `Request.TurnOff` branch.

- [ ] **Step 4: Read the new phrases**

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt`, replace

```kotlin
    /** "Stop Kestrel": off. */
    data object TurnOff : Request
```

with

```kotlin
    /** "Stop Kestrel", or "Kestrel, turn off": off. */
    data object TurnOff : Request

    /** "Kestrel, open settings" or "Kestrel settings": Kestrel's settings app (section 15). */
    data object OpenSettings : Request
```

Replace

```kotlin
 *    "stop". Further names right after it are dropped. Then the rest is nothing (the name on its own), a stop,
 *    "wake up", "stop listening", or a task. A task after "stop" or "cancel" asks to cancel a running task first.
```

with

```kotlin
 *    "stop". Further names right after it are dropped. Then the rest is nothing (the name on its own), a stop,
 *    "wake up", "stop listening", "turn off" or "switch off", "open settings", "open your settings" or "settings", or a
 *    task. In an answer to "Yes?", "never mind", "forget it" and "nothing" are a named stop, which ends the wait. A task
 *    after "stop" or "cancel" asks to cancel a running task first.
```

Replace

```kotlin
 * 4. Otherwise "stop listening" pauses, and "wake up" or "stop listening" followed by the name count too.
```

with

```kotlin
 * 4. Otherwise "stop listening" pauses, and "wake up", "stop listening", "turn off" or "switch off" followed by the
 *    name count too.
```

Replace

```kotlin
            beforeName == "wake up" -> Request.WakeUp
```

with

```kotlin
            beforeName == "wake up" -> Request.WakeUp
            beforeName != null && beforeName in TURN_OFF -> Request.TurnOff
            afterName && beforeName != null && beforeName in NEVER_MIND -> Request.Stop(named = true)
```

Replace

```kotlin
    // Words that Kestrel's own phrases may come with, as in "Kestrel, wake up, please".
    private val POLITE = setOf("please", "now", "ok", "okay")
```

with

```kotlin
    // Words that Kestrel's own phrases may come with, as in "Kestrel, wake up, please".
    private val POLITE = setOf("please", "now", "ok", "okay")

    // Kestrel's own phrases after the name, as phrase() gives them (Appendix A).
    private val TURN_OFF = setOf("turn off", "switch off")
    private val OPEN_SETTINGS = setOf("open settings", "open your settings", "settings")

    // Answers to "Yes?" that end the wait (section 8.3).
    private val NEVER_MIND = setOf("never mind", "nevermind", "forget it", "nothing")
```

Replace

```kotlin
            return named(transcript, rest, limit, stopFirst = start == 1 && words[0].text in STOP_WORDS)
```

with

```kotlin
            return named(transcript, rest, limit, stopFirst = start == 1 && words[0].text in STOP_WORDS, afterName = afterName)
```

Replace

```kotlin
            afterName -> named(transcript, words, limit, stopFirst = false)
```

with

```kotlin
            afterName -> named(transcript, words, limit, stopFirst = false, afterName = true)
```

Replace

```kotlin
    /** What [rest], the words after the name, ask for. */
    private fun named(transcript: String, rest: List<Word>, limit: Int, stopFirst: Boolean): Request {
        if (rest.isEmpty()) return Request.Name
        if (readStop(rest, limit) != null) return Request.Stop(named = true)
        return when (phrase(rest)) {
            "wake up" -> Request.WakeUp
            "stop listening" -> Request.PauseListening
            else -> Request.Task(transcript.substring(rest.first().range.first).trim(), stopFirst)
        }
    }
```

with

```kotlin
    /** What [rest], the words after the name, ask for. [afterName]: they answer "Yes?". */
    private fun named(transcript: String, rest: List<Word>, limit: Int, stopFirst: Boolean, afterName: Boolean): Request {
        if (rest.isEmpty()) return Request.Name
        if (readStop(rest, limit) != null) return Request.Stop(named = true)
        val said = phrase(rest)
        return when {
            said == "wake up" -> Request.WakeUp
            said == "stop listening" -> Request.PauseListening
            said in TURN_OFF -> Request.TurnOff
            said in OPEN_SETTINGS -> Request.OpenSettings
            // "Forget [something]" stays a task, because "Kestrel, forget [fact]" is a command (Appendix A).
            afterName && said in NEVER_MIND -> Request.Stop(named = true)
            else -> Request.Task(transcript.substring(rest.first().range.first).trim(), stopFirst)
        }
    }
```

- [ ] **Step 5: Open the settings from the controller**

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
/** What Kestrel says and shows while it listens. Plan 2C-2 decides the final wording. */
```

with

```kotlin
/** What Kestrel says and shows while it listens. Plan 2C-3 decides the final wording. */
```

Replace

```kotlin
    const val MICROPHONE_FAILED = "The microphone failed, so Kestrel stopped listening."
}
```

with

```kotlin
    const val MICROPHONE_FAILED = "The microphone failed, so Kestrel stopped listening."
    const val CANNOT_OPEN_SETTINGS = "Kestrel could not open its settings."
}
```

Replace

```kotlin
 * any thread. [report] gets each failure with a fixed description of what failed, and must log no message.
 */
```

with

```kotlin
 * any thread. [report] gets each failure with a fixed description of what failed, and must log no message.
 * [openSettings] opens Kestrel's settings app; the controller's thread calls it, and it throws when Android refuses.
 */
```

Replace

```kotlin
    private val report: (String, Throwable) -> Unit,
    microphones: () -> Microphone,
) {
```

with

```kotlin
    private val report: (String, Throwable) -> Unit,
    private val openSettings: () -> Unit,
    microphones: () -> Microphone,
) {
```

Replace

```kotlin
            Effect.StopService -> shutdown()
        }
    }
```

with

```kotlin
            Effect.StopService -> shutdown()
            Effect.OpenSettings -> openSettingsOrSayWhy()
        }
    }

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

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, add the import `import android.content.Intent` after `import android.content.Context`, and replace

```kotlin
            report = { what, error ->
                for ((index, part) in error.describeWithoutMessagesByCause().withIndex()) {
                    Log.w(TAG, if (index == 0) "$what failed: $part" else part)
                }
            },
            microphones = { MicCapture(appContext) },
```

with

```kotlin
            report = { what, error ->
                for ((index, part) in error.describeWithoutMessagesByCause().withIndex()) {
                    Log.w(TAG, if (index == 0) "$what failed: $part" else part)
                }
            },
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
            microphones = { MicCapture(appContext) },
```

- [ ] **Step 6: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.orchestrator.RequestsTest --tests dev.kestrel.app.orchestrator.RulesTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD SUCCESSFUL`, with 22 tests in `RequestsTest` (18 and the 4 new ones), 36 in `RulesTest` (31 and 5) and 36 in `ListeningTest` (34 and 2).

- [ ] **Step 7: Update the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 8.3, replace

```markdown
"Kestrel" on its own makes Kestrel answer "Yes?" and treat the next utterance within 8 s as the task.
```

with

```markdown
"Kestrel" on its own makes Kestrel answer "Yes?" and treat the next utterance within 8 s as the task. "Never mind", "forget it" or "nothing" in answer to "Yes?" ends the wait.
```

In section 8.5, replace

```markdown
| Off | Nothing; the microphone is released | "Stop Kestrel", the notification's Stop action, or a fresh install |
```

with

```markdown
| Off | Nothing; the microphone is released | "Stop Kestrel", "Kestrel, turn off", the notification's Stop action, or a fresh install |
```

In Appendix A, in the table of the commands that use Kestrel's name, replace

```markdown
| Kestrel, wake up | Resumes listening from the paused mode |
```

with

```markdown
| Kestrel, wake up | Resumes listening from the paused mode |
| Kestrel, turn off | Turns Kestrel off, as "Stop Kestrel" does |
```

and replace

```markdown
| Kestrel, open settings | Opens Kestrel's settings |
```

with

```markdown
| Kestrel, open settings; Kestrel settings | Opens Kestrel's settings |
```

- [ ] **Step 8: Run all JVM tests of `:app` and lint**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest :app:lintDebug`
Expected: `BUILD SUCCESSFUL`: 110 JVM tests in `:app` (99 and the 11 new ones), and lint finds no issue (`app\build\reports\lint-results-debug.txt` says "No issues found.")

- [ ] **Step 9: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/test/kotlin/dev/kestrel/app/orchestrator/RequestsTest.kt app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(app): read 'Kestrel, turn off', 'Kestrel, open settings' and 'never mind' after 'Yes?', and add Event.Start" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 2: The accessibility button and shortcut

**Files:**
- Modify: `app/src/main/kotlin/dev/kestrel/app/KestrelAccessibilityService.kt` (whole file below)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 7.2)
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/voice/ActivationTest.kt` (new)

**Interfaces:**
- Consumes: `AppGraph.listening`, `Listening.send(event: Event)`, `Event.Toggle`; in the device test `VoiceTests.graph`, `assumeModels(vararg engines: Engine)`, `grantListeningPermissions()`, `useMicrophone(microphone: Microphone)`, `stopListening()`, `waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean`, `shell(command: String): String`, `Listening.sessionOpen`, `ClipMicrophone.recording`, and `ScreenServiceRule` with its `automation: UiAutomation`.
- Produces: `class ActivationTest` in `dev.kestrel.app.voice` with the private helpers `press(action: Int): Boolean`, `awaitListening()`, `awaitState(condition: (State) -> Boolean)`, `secure(name: String): String` and `putSecure(name: String, value: String)`, which Task 3 uses for the tile's test. `KestrelAccessibilityService` keeps `onServiceConnected()` and `disconnect()`, to which Task 4 adds one line.

Section 8.6: the accessibility shortcut or button "toggles between listening and paused, or starts Kestrel when it is off", which is exactly `Event.Toggle`. Both reach the service's `AccessibilityButtonController.AccessibilityButtonCallback.onClicked` (prototype P1, Q2); `res/xml/accessibility_service_config.xml` already asks for the button with `flagRequestAccessibilityButton`. The accessibility binding lets Kestrel start its foreground service from the background (P1, Q1 scenario 1), so no visible activity is needed.

The device test runs in Kestrel's process under instrumentation, and Android may count an instrumented process as a reason to let it start a foreground service from the background. So `ActivationTest` checks the wiring from the system's action to listening; Task 5's `listen-check.ps1` checks the start paths without instrumentation. The test writes the targets through the device's shell with `executeShellCommandRw("sh")`, because `UiAutomation.executeShellCommand` splits a command at spaces and so cannot write an empty value back, and an empty `accessibility_button_targets` is what Android leaves after it removed a service (P1).

- [ ] **Step 1: Write the failing test**

Create `app/src/androidTest/kotlin/dev/kestrel/app/voice/ActivationTest.kt`:

```kotlin
package dev.kestrel.app.voice

import android.accessibilityservice.AccessibilityService
import android.os.ParcelFileDescriptor
import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.State
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.voice.models.Engine
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The ways into listening without a word (design spec, section 8.6): the accessibility shortcut and the accessibility
 * button, which both reach KestrelAccessibilityService's button callback. Kestrel is in the background, as when the owner
 * uses them. The test process is instrumented, which Android may count as a reason to let Kestrel start a foreground
 * service from the background, so these tests check the wiring; scripts\listen-check.ps1 checks the start paths
 * themselves. Each test puts back the settings that it changes, and leaves Kestrel off.
 */
class ActivationTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val listening = VoiceTests.graph.listening
    private val clip = ClipMicrophone()

    @Before
    fun setUp() {
        VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.ASR, Engine.TTS)
        VoiceTests.grantListeningPermissions()
        VoiceTests.useMicrophone(clip)
        VoiceTests.shell("input keyevent KEYCODE_HOME")
    }

    @After
    fun tearDown() {
        VoiceTests.stopListening()
    }

    @Test
    fun theShortcutAndTheButtonToggleListening() {
        val saved = TARGETS.associateWith { secure(it) }
        try {
            putSecure(SHORTCUT, SERVICE)
            // Android reads the setting a moment after it changes.
            assertTrue(
                "Android does not name Kestrel as the shortcut's target",
                VoiceTests.waitUntil(5_000) { SHORTCUT_KEY.containsMatchIn(VoiceTests.shell("dumpsys accessibility")) },
            )

            assertTrue("The shortcut's action failed", press(AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_SHORTCUT))
            awaitListening()
            assertTrue("The shortcut's action failed", press(AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_SHORTCUT))
            awaitState { it.mode == Mode.PAUSED }

            // With gestural navigation, as on kestrel-api37, Android offers the button's action only for a gesture target;
            // with the navigation bar's buttons, for a button target in the navigation bar (prototype P1, research R14).
            putSecure(BUTTON, SERVICE)
            putSecure(GESTURE, SERVICE)
            putSecure(BUTTON_MODE, "0")
            // SystemUI offers the action a moment after it has seen the targets. Until then the call returns false and does
            // nothing, so only the call that returns true presses the button.
            assertTrue(
                "Android did not offer the accessibility button's action",
                VoiceTests.waitUntil(5_000) { press(AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_BUTTON) },
            )
            awaitState { it.mode == Mode.LISTENING }
        } finally {
            for ((name, value) in saved) putSecure(name, value)
        }
    }

    /** Asks Android for a system action, as the accessibility shortcut or button does. Returns whether Android ran it. */
    private fun press(action: Int): Boolean = screen.automation.performGlobalAction(action)

    /** Waits until Kestrel listens, with its voice session open and the test's microphone recording. */
    private fun awaitListening() {
        // Loading the models takes several seconds.
        assertTrue(
            "Kestrel does not listen; the state is ${listening.state.value}",
            VoiceTests.waitUntil(60_000) { listening.state.value.mode == Mode.LISTENING && listening.sessionOpen.value && clip.recording },
        )
    }

    private fun awaitState(condition: (State) -> Boolean) {
        assertTrue("The state is ${listening.state.value}", VoiceTests.waitUntil(10_000) { condition(listening.state.value) })
    }

    /** A secure setting as the shell prints it: "null" when it is not set. */
    private fun secure(name: String): String = VoiceTests.shell("settings get secure $name").trimEnd('\r', '\n')

    /** Writes a secure setting through the device's shell, which reads quotes, so an empty value too; "null" deletes it. */
    private fun putSecure(name: String, value: String) {
        val command = if (value == "null") "settings delete secure $name" else "settings put secure $name '${value.replace("'", "'\\''")}'"
        val (output, input) = screen.automation.executeShellCommandRw("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(input).use { it.write("$command\n".toByteArray()) }
        // The shell ends when its input ends, and only then its output, so the setting is written when this returns.
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }

    private companion object {
        const val SERVICE = "dev.kestrel/dev.kestrel.app.KestrelAccessibilityService"
        const val SHORTCUT = "accessibility_shortcut_target_service"
        const val BUTTON = "accessibility_button_targets"
        const val GESTURE = "accessibility_gesture_targets"
        const val BUTTON_MODE = "accessibility_button_mode"
        val TARGETS = listOf(SHORTCUT, BUTTON, GESTURE, BUTTON_MODE)

        // How `dumpsys accessibility` names the shortcut's targets, as in "shortcut key:{dev.kestrel/...}".
        val SHORTCUT_KEY = Regex("""shortcut key:\{[^}]*dev\.kestrel/""")
    }
}
```

- [ ] **Step 2: Check the emulator**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; & (Get-Adb) -s emulator-5554 emu avd name`
Expected: `kestrel-api37`, then `OK`. Anything else: stop, and start the emulator as "Before you start" says.

- [ ] **Step 3: Run the test to verify that it fails**

The models are on the emulator since plan 2C-1's runs of `voice-test.ps1`, and `leaveApksInstalledAfterRun` keeps Kestrel, and so the models, installed.

Run: `scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.voice.ActivationTest' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' --no-configuration-cache`
Expected: `BUILD FAILED`, with 1 failed test, `ActivationTest > theShortcutAndTheButtonToggleListening`, and the message "Kestrel does not listen; the state is State(mode=OFF, ...)" after about 60 s: Android delivers the shortcut, but nothing in Kestrel receives it. If the test is skipped instead, the models are missing: run `scripts\voice-test.ps1 -Serial emulator-5554` once, which copies them, and repeat this step.

- [ ] **Step 4: Receive the button and the shortcut**

Replace the whole of `app/src/main/kotlin/dev/kestrel/app/KestrelAccessibilityService.kt` with:

```kotlin
package dev.kestrel.app

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.screen.ScreenSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Reads the screen and acts on it for Kestrel (design spec, sections 7.2 and 9). The accessibility button and the
 * accessibility shortcut toggle listening through it (section 8.6). Android calls every callback on the main thread.
 */
class KestrelAccessibilityService : AccessibilityService() {
    private val graph: AppGraph get() = (application as KestrelApplication).graph
    private var session: ScreenSession? = null
    private var purging: Job? = null
    private var button: AccessibilityButtonController.AccessibilityButtonCallback? = null

    override fun onServiceConnected() {
        val connected = ScreenSession(this, graph.errors)
        session = connected
        graph.attachScreen(connected)
        // The service runs for days at a time, so it also keeps the logs within their 7 days (design spec, section 13.5).
        purging = graph.scope.launch {
            val context = coroutineContext
            graph.logs.keepPurging { error -> graph.errors.handleException(context, error) }
        }
        // A press of the accessibility button and the accessibility shortcut both arrive here, on the main thread. Toggle
        // switches between listening and paused, or starts Kestrel when it is off (section 8.6).
        val clicks = object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController) {
                graph.listening.send(Event.Toggle)
            }
        }
        accessibilityButtonController.registerAccessibilityButtonCallback(clicks)
        button = clicks
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
        button?.let { accessibilityButtonController.unregisterAccessibilityButtonCallback(it) }
        button = null
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

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 7.2, replace

```markdown
| `KestrelAccessibilityService` | `AccessibilityService` | Reads the screen, performs actions, draws the overlays and watches for touches. It declares `isAccessibilityTool="true"` (D19). |
```

with

```markdown
| `KestrelAccessibilityService` | `AccessibilityService` | Reads the screen, performs actions, draws the overlays and watches for touches. It declares `isAccessibilityTool="true"` (D19). The accessibility button and the accessibility shortcut reach Kestrel through it and toggle listening (section 8.6). |
```

- [ ] **Step 5: Build and lint**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin :app:lintDebug`
Expected: `BUILD SUCCESSFUL`, and lint finds no issue (`app\build\reports\lint-results-debug.txt` says "No issues found.")

- [ ] **Step 6: Check the emulator**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; & (Get-Adb) -s emulator-5554 emu avd name`
Expected: `kestrel-api37`, then `OK`. Anything else: stop, and start the emulator as "Before you start" says.

- [ ] **Step 7: Run the voice device tests**

Run: `scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 58 run, 0 failed, 0 errors, 0 skipped.", including `ActivationTest`, and no "should run only on an emulator" line. If the test stops at "Android does not name Kestrel as the shortcut's target", print the lines of `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; & (Get-Adb) -s emulator-5554 shell dumpsys accessibility` that contain `shortcut` and fix `SHORTCUT_KEY`. If it stops at "Android did not offer the accessibility button's action", print `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; & (Get-Adb) -s emulator-5554 shell settings get secure navigation_mode`: with 0 (three buttons) Android reads `accessibility_button_targets` with the mode 0, which the test sets too. Afterwards `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; & (Get-Adb) -s emulator-5554 shell settings get secure accessibility_shortcut_target_service` prints what it printed before the run (`null` on a fresh kestrel-api37), because `connected-test.ps1` puts the accessibility settings back.

- [ ] **Step 8: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/KestrelAccessibilityService.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/ActivationTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(app): the accessibility button and shortcut toggle listening" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: The Quick Settings tile

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/listen/KestrelTileService.kt`
- Create: `app/src/main/res/drawable/ic_tile.xml`
- Modify: `app/src/main/AndroidManifest.xml` (the tile's declaration)
- Modify: `app/src/main/res/values/strings.xml` (the tile's subtitles)
- Modify: `app/src/debug/AndroidManifest.xml` (the spike's tile goes)
- Delete: `app/src/debug/kotlin/dev/kestrel/app/debug/spike/SpikeTileService.kt`
- Modify: `scripts/voice-test.ps1` (the emulator-only tests)
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/voice/ActivationTest.kt` (one more test)

**Interfaces:**
- Consumes: `AppGraph.listening`, `Listening.state: StateFlow<State>`, `Listening.send(event: Event)`, `Event.Toggle`, `Mode`; `ActivationTest`'s helpers from Task 2.
- Produces: `class KestrelTileService : TileService()` in `dev.kestrel.app.listen`, whose component `dev.kestrel/dev.kestrel.app.listen.KestrelTileService` Task 5's `listen-check.ps1` adds, taps and removes; the strings `tile_listening`, `tile_paused` and `tile_off`; the drawable `ic_tile`.

Sections 7.2, 8.5 and 8.6: the tile toggles listening. It shows the mode while the owner sees it: active while Kestrel listens, inactive while it is paused or off, with "Listening", "Paused" or "Off" as its subtitle. Android draws a tile's icon in one colour, so the icon is a monochrome vector: the letter K of the launcher icon. The meta-data `android.service.quicksettings.TOGGLEABLE_TILE` tells Android that the tile is a switch, which TalkBack and other accessibility services announce. A tap gives Kestrel a short exemption for starting its foreground service from the background, also without the accessibility service (prototype P1, Q1 scenario 4).

The device test checks the toggle and the tile's look. SystemUI asks a tile for its look only while the owner could see it, so the test puts Kestrel's tile first in the list with `cmd statusbar set-tiles`, opens Quick Settings with `cmd statusbar expand-settings`, and reads the tile's line in SystemUI's dump, which on kestrel-api37 reads `CustomTile: QSTile$BooleanState[spec=custom(...),...,secondaryLabel=...,...,state=N,...]`. The test changes the list of tiles, a setting of the whole device that no script puts back if the test's process dies, so it runs only on an emulator, and `voice-test.ps1` leaves it out on a phone, as it does for the privacy-toggle and lock-screen tests.

- [ ] **Step 1: Write the failing test**

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/ActivationTest.kt`, add this test right after the test `theShortcutAndTheButtonToggleListening`:

```kotlin
    @Test
    fun theTileTogglesListening() {
        // It changes the list of tiles, which no script puts back if the test's process dies.
        VoiceTests.assumeEmulator()
        // Without Kestrel's tile, also if an earlier run's test process died before it removed the tile.
        val tiles = secure(TILES).let { list ->
            if (list == "null") list else list.split(',').filterNot { TILE_NAME in it }.joinToString(",")
        }
        try {
            // First in the list, so that the tile is on the first page of Quick Settings, whose tiles SystemUI follows while
            // the panel is open. A device without a list of its own gets the tile at the end.
            if (tiles == "null" || tiles.isEmpty()) {
                VoiceTests.shell("cmd statusbar add-tile $TILE")
            } else {
                VoiceTests.shell("cmd statusbar set-tiles $TILE_SPEC,$tiles")
                // A SystemUI without set-tiles follows the setting itself.
                if (!VoiceTests.waitUntil(5_000) { secure(TILES).contains(TILE_NAME) }) putSecure(TILES, "$TILE_SPEC,$tiles")
            }
            assertTrue("Quick Settings does not list Kestrel's tile", VoiceTests.waitUntil(5_000) { secure(TILES).contains(TILE_NAME) })
            VoiceTests.shell("cmd statusbar expand-settings")
            // SystemUI makes and binds the tile a moment after it lists it, and a click before that is lost.
            awaitTile("Off", Tile.STATE_INACTIVE, timeoutMillis = 15_000)

            VoiceTests.shell("cmd statusbar click-tile $TILE")
            awaitListening()
            awaitTile("Listening", Tile.STATE_ACTIVE)
            VoiceTests.shell("cmd statusbar click-tile $TILE")
            awaitState { it.mode == Mode.PAUSED }
            awaitTile("Paused", Tile.STATE_INACTIVE)
        } finally {
            VoiceTests.shell("cmd statusbar collapse")
            VoiceTests.shell("cmd statusbar remove-tile $TILE")
            // SystemUI writes the list a moment later. A list that does not come back as it was is written back.
            if (!VoiceTests.waitUntil(5_000) { secure(TILES) == tiles }) putSecure(TILES, tiles)
        }
    }

    /** Waits until SystemUI's dump shows Kestrel's tile with [subtitle] and in [state], as the owner sees it. */
    private fun awaitTile(subtitle: String, state: Int, timeoutMillis: Long = 5_000) {
        val look = Regex("""spec=custom\([^)]*KestrelTileService\).*,secondaryLabel=$subtitle,.*,state=$state,""")
        assertTrue(
            "SystemUI does not show Kestrel's tile as $subtitle",
            VoiceTests.waitUntil(timeoutMillis) { look.containsMatchIn(VoiceTests.shell("dumpsys activity service com.android.systemui/.SystemUIService")) },
        )
    }
```

Add the import `import android.service.quicksettings.Tile` after `import android.os.ParcelFileDescriptor`.

In the same file's companion object, replace

```kotlin
        // How `dumpsys accessibility` names the shortcut's targets, as in "shortcut key:{dev.kestrel/...}".
        val SHORTCUT_KEY = Regex("""shortcut key:\{[^}]*dev\.kestrel/""")
```

with

```kotlin
        // How `dumpsys accessibility` names the shortcut's targets, as in "shortcut key:{dev.kestrel/...}".
        val SHORTCUT_KEY = Regex("""shortcut key:\{[^}]*dev\.kestrel/""")

        const val TILES = "sysui_qs_tiles"
        const val TILE = "dev.kestrel/dev.kestrel.app.listen.KestrelTileService"

        // SystemUI lists the tile as custom(dev.kestrel/.app.listen.KestrelTileService), as add-tile writes it.
        const val TILE_SPEC = "custom(dev.kestrel/.app.listen.KestrelTileService)"
        const val TILE_NAME = "KestrelTileService"
```

and replace the class's KDoc text

```kotlin
 * The ways into listening without a word (design spec, section 8.6): the accessibility shortcut and the accessibility
 * button, which both reach KestrelAccessibilityService's button callback. Kestrel is in the background, as when the owner
 * uses them. The test process is instrumented, which Android may count as a reason to let Kestrel start a foreground
 * service from the background, so these tests check the wiring; scripts\listen-check.ps1 checks the start paths
 * themselves. Each test puts back the settings that it changes, and leaves Kestrel off.
```

with

```kotlin
 * The ways into listening without a word (design spec, section 8.6): the accessibility shortcut and the accessibility
 * button, which both reach KestrelAccessibilityService's button callback, and the Quick Settings tile. Kestrel is in the
 * background, as when the owner uses them. The test process is instrumented, which Android may count as a reason to let
 * Kestrel start a foreground service from the background, so these tests check the wiring; scripts\listen-check.ps1
 * checks the start paths themselves. Each test puts back the settings that it changes, and leaves Kestrel off. The tile's
 * test runs only on an emulator, because no script puts the list of tiles back if the test's process dies.
```

In `scripts/voice-test.ps1`, replace

```powershell
# password, so on a phone the script leaves that test out too. The run fails when one of the two tests ran on a phone or
# did not run on an emulator. On an emulator the script turns the lock screen off again at the end, wakes the screen and
# dismisses the keyguard, also when the run failed or was interrupted.
```

with

```powershell
# password, so on a phone the script leaves that test out too. So does ActivationTest#theTileTogglesListening, which adds
# Kestrel's tile to Quick Settings: no script puts the list of tiles back if the test's process dies. The run fails when
# one of these three tests ran on a phone or did not run on an emulator. On an emulator the script turns the lock screen
# off again at the end, wakes the screen and dismisses the keyguard, also when the run failed or was interrupted.
```

and replace

```powershell
        # Tests that a phone must not run: one switches the microphone's privacy toggle, the other turns on a lock screen
        # that it unlocks with adb.
        $emulatorOnly = @(
            'dev.kestrel.app.voice.MicCaptureTest#silencedFollowsTheMicrophonesPrivacyToggle'
            'dev.kestrel.app.voice.ListeningServiceTest#theMicrophoneStopsWhileTheScreenIsOffAndStartsAgainAfterTheUnlock'
        )
```

with

```powershell
        # Tests that a phone must not run: one switches the microphone's privacy toggle, one turns on a lock screen that it
        # unlocks with adb, and one changes the list of Quick Settings tiles.
        $emulatorOnly = @(
            'dev.kestrel.app.voice.MicCaptureTest#silencedFollowsTheMicrophonesPrivacyToggle'
            'dev.kestrel.app.voice.ListeningServiceTest#theMicrophoneStopsWhileTheScreenIsOffAndStartsAgainAfterTheUnlock'
            'dev.kestrel.app.voice.ActivationTest#theTileTogglesListening'
        )
```

The script's check after the run reads each of these tests from the JUnit reports by its method name, so it also fails a run in which the tile's test ran on a phone or did not run on an emulator.

- [ ] **Step 2: Check the emulator**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; & (Get-Adb) -s emulator-5554 emu avd name`
Expected: `kestrel-api37`, then `OK`. Anything else: stop, and start the emulator as "Before you start" says.

- [ ] **Step 3: Run the test to verify that it fails**

Run: `scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.voice.ActivationTest#theTileTogglesListening' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' --no-configuration-cache`
Expected: `BUILD FAILED`, with 1 failed test, `ActivationTest > theTileTogglesListening`, and either "SystemUI does not show Kestrel's tile as Off" (after 15 s) or "Quick Settings does not list Kestrel's tile": Kestrel has no such tile yet. Afterwards `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; & (Get-Adb) -s emulator-5554 shell settings get secure sysui_qs_tiles` prints no `KestrelTileService`.

- [ ] **Step 4: Write the tile**

Create `app/src/main/kotlin/dev/kestrel/app/listen/KestrelTileService.kt`:

```kotlin
package dev.kestrel.app.listen

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.R
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Kestrel's Quick Settings tile (design spec, sections 7.2, 8.5 and 8.6). A tap toggles between listening and paused, or
 * starts Kestrel when it is off. While the owner sees the tile, it shows the mode: active while Kestrel listens, inactive
 * while it is paused or off. Android calls every callback on the main thread.
 */
class KestrelTileService : TileService() {
    private val listening: Listening get() = (application as KestrelApplication).graph.listening
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var following: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        following?.cancel()
        following = scope.launch {
            listening.state.map { it.mode }.distinctUntilChanged().collect { show(it) }
        }
    }

    override fun onStopListening() {
        following?.cancel()
        following = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        listening.send(Event.Toggle)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun show(mode: Mode) {
        val tile = qsTile ?: return
        tile.state = if (mode == Mode.LISTENING) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = getString(
            when (mode) {
                Mode.LISTENING -> R.string.tile_listening
                Mode.PAUSED -> R.string.tile_paused
                Mode.OFF -> R.string.tile_off
            },
        )
        tile.updateTile()
    }
}
```

Create `app/src/main/res/drawable/ic_tile.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <!-- The letter K of the launcher icon, in one colour, because Android tints a tile's icon itself. -->
    <path
        android:pathData="M8,5 V19 M16.5,5 L9,12.5 M11.5,10.5 L16.5,19"
        android:strokeColor="#FFFFFFFF"
        android:strokeLineCap="square"
        android:strokeWidth="2.5" />
</vector>
```

In `app/src/main/res/values/strings.xml`, replace

```xml
    <string name="listening_stop">Stop</string>
</resources>
```

with

```xml
    <string name="listening_stop">Stop</string>

    <!-- The subtitle of the Quick Settings tile (design spec, sections 8.5 and 8.6). -->
    <string name="tile_listening">Listening</string>
    <string name="tile_paused">Paused</string>
    <string name="tile_off">Off</string>
</resources>
```

In `app/src/main/AndroidManifest.xml`, replace

```xml
        <!-- Keeps Kestrel listening, with the microphone and voice output (design spec, sections 7.2 and 8.5). -->
        <service
            android:name=".listen.ListeningService"
            android:exported="false"
            android:foregroundServiceType="microphone|mediaPlayback" />
```

with

```xml
        <!-- Keeps Kestrel listening, with the microphone and voice output (design spec, sections 7.2 and 8.5). -->
        <service
            android:name=".listen.ListeningService"
            android:exported="false"
            android:foregroundServiceType="microphone|mediaPlayback" />

        <!-- The Quick Settings tile that toggles listening (design spec, sections 7.2 and 8.6). Only SystemUI, which holds
             BIND_QUICK_SETTINGS_TILE, can bind it. -->
        <service
            android:name=".listen.KestrelTileService"
            android:exported="true"
            android:icon="@drawable/ic_tile"
            android:label="Kestrel"
            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
            <intent-filter>
                <action android:name="android.service.quicksettings.action.QS_TILE" />
            </intent-filter>
            <meta-data
                android:name="android.service.quicksettings.TOGGLEABLE_TILE"
                android:value="true" />
        </service>
```

- [ ] **Step 5: Remove the spike's tile**

Run: `git rm app/src/debug/kotlin/dev/kestrel/app/debug/spike/SpikeTileService.kt`
Expected: `rm 'app/src/debug/kotlin/dev/kestrel/app/debug/spike/SpikeTileService.kt'`.

In `app/src/debug/AndroidManifest.xml`, replace

```xml
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
```

with

```xml
        <!-- Spike S1. Plan 2C-2 replaces it with the real ListeningService. -->
        <service
            android:name=".debug.spike.SpikeListeningService"
            android:exported="false"
            android:foregroundServiceType="microphone|mediaPlayback" />
```

`scripts\spike-s1.ps1` still names the spike's tile in its `tile` scenario until Task 5 removes that scenario; do not run that scenario in between.

- [ ] **Step 6: Build and lint**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin :app:lintDebug kestrelAudit`
Expected: `BUILD SUCCESSFUL`; lint finds no issue (`app\build\reports\lint-results-debug.txt` says "No issues found.") (the tile is exported with `BIND_QUICK_SETTINGS_TILE`, and its icon is a vector), and the audit finds no test hook in the release build.

- [ ] **Step 7: Check the scripts**

Run: `scripts\test-scripts.ps1`
Expected: "The script tests and checks passed.": `Tests Passed: 70, Failed: 0` in Windows PowerShell 5.1 and in PowerShell 7, and "PSScriptAnalyzer found 0 problems." (in `voice-test.ps1` too).

- [ ] **Step 8: Check the emulator**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; & (Get-Adb) -s emulator-5554 emu avd name`
Expected: `kestrel-api37`, then `OK`. Anything else: stop, and start the emulator as "Before you start" says.

- [ ] **Step 9: Run the voice device tests**

Run: `scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 59 run, 0 failed, 0 errors, 0 skipped.", including the 2 tests of `ActivationTest`, and no "should run only on an emulator" line. If `theTileTogglesListening` stops at "SystemUI does not show Kestrel's tile as Off", "as Listening" or "as Paused", print the lines of `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; & (Get-Adb) -s emulator-5554 shell dumpsys activity service com.android.systemui/.SystemUIService` that contain `KestrelTileService`: `secondaryLabel=null` means that SystemUI did not ask the tile for its look (check that the panel was open and the tile first), and another subtitle or state means that `show()` is wrong. Afterwards `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; & (Get-Adb) -s emulator-5554 shell settings get secure sysui_qs_tiles` prints the same list as before the run, without `KestrelTileService`.

- [ ] **Step 10: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/listen/KestrelTileService.kt app/src/main/res/drawable/ic_tile.xml app/src/main/res/values/strings.xml app/src/main/AndroidManifest.xml app/src/debug/AndroidManifest.xml app/src/androidTest/kotlin/dev/kestrel/app/voice/ActivationTest.kt scripts/voice-test.ps1
git commit -m "feat(app): the Quick Settings tile toggles listening and shows the mode" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

(`git rm` in Step 5 has already staged the deleted `SpikeTileService.kt`.)

---

### Task 4: Bring the mode back after a restart

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/listen/ListeningSetting.kt`
- Modify: `app/build.gradle.kts` (the serialization plugin)
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt` (`ModeMemory`, the KDoc, the constructor, the fields, `init`, `onAccessibilityServiceConnected`, `remember`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (`SettingKeys.LISTENING`, `listening`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/KestrelAccessibilityService.kt` (`onServiceConnected`)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 8.5)
- Modify: `app/src/androidTest/kotlin/dev/kestrel/app/screen/ScreenServiceRule.kt` (`before()` stores Off)
- Modify: `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt` (`event` stores Off for `off`)
- Modify: `scripts/hook.ps1` (the help of `voice -Send off`)
- Test: `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceHookTest.kt` (one more test)

**Interfaces:**
- Consumes: `Event.Start(paused: Boolean = false)` from Task 1; `Listening`'s constructor with `openSettings` from Task 1; `KestrelAccessibilityService.onServiceConnected()` from Task 2; `SettingsStore.get(key: SettingKey<T>): T` and `set(key: SettingKey<T>, value: T)` (`dev.kestrel.privacy.settings`), `SettingKey(name: String, serializer: KSerializer<T>, default: T)`.
- Produces: `internal interface ModeMemory { suspend fun read(): Mode; suspend fun write(mode: Mode) }` in `dev.kestrel.app.listen`; `Listening`'s constructor gains `memory: ModeMemory` (after `openSettings`); `fun Listening.onAccessibilityServiceConnected()`; `internal suspend fun Listening.skipRestore()`; `@Serializable data class ListeningSetting(val mode: Mode = Mode.OFF)`; `SettingKeys.LISTENING`; `ScreenServiceRule.before()` stores Off before it turns the accessibility service on; the hook `VOICE` with the event `off` skips the restore and stores Off, also while Kestrel is off. Task 5's `listen-check.ps1` relies on the behaviour: after `am crash dev.kestrel` or a reboot, Kestrel listens again once its accessibility service has connected, and `hook.ps1 voice -Send off` stores Off.

Sections 8.5 and 14.4: after Android ended Kestrel's process, or the phone restarted, listening comes back in the mode that it had, but a task never does; the Orchestrator starts with a fresh `State()`. `Listening` notes each new mode on its own thread, where it performs the Orchestrator's `Effect.Show(Feedback.ModeChanged)`: every change of mode shows the new mode, also the changes that the Orchestrator makes in one run and that the state's collector skips. It writes the mode through `ModeMemory` from one coroutine of `AppGraph.scope`, so the newest mode is always written last. Off counts at once; Listening and Paused count only once a voice session is open. So a start that fails, or that ends Kestrel's process (a native crash while the models load, the low-memory killer), never leaves Listening stored, and Kestrel does not crash again at every start; Android would treat a second crash within 2 minutes as crashing too often and keep the accessibility service away for 30 minutes. Nothing is written until the mode first changes in a process, so the Off that every process starts with never replaces the stored mode before the restore has read it; the first Off after a change is always written, also when it replaces a Listening that an earlier process stored. The restore runs once per process, at the first connection of the accessibility service, and only if Kestrel has not been on in the process, also not while the stored mode was read, because the owner has then chosen a mode. From that moment Android lets Kestrel start its foreground service from the background (prototype P1, Q1 scenarios 1 and 5). The restore stores Off before it sends `Event.Start`, so that a start that ends the process is not tried again; once the session is open, the mode is stored again.

The device tests must not depend on a mode that an earlier, interrupted run stored: `ScreenServiceRule.before()` stores Off before it turns the accessibility service on. The hook's `off` first stops any restore in the process with `Listening.skipRestore()`, which runs on the controller's thread, and then stores Off and sends `Event.TurnOff`: a restore that runs at the same time either stops at its next check or has sent its `Event.Start` before, which the `TurnOff` then follows. The hook stores Off also while Kestrel is off, which it is in a new process, where `Event.TurnOff` changes nothing and so stores nothing; this is the command that "Before you start" and `listen-check.ps1` give for turning Kestrel off by hand.

`:app` did not use the kotlinx.serialization compiler plugin so far; the `core` modules apply it the same way, and `kotlinx-serialization-json` already reaches `:app` through `:core:privacy`'s `api` dependency. An enum such as `Mode` is serializable without an annotation. A stored value that this version cannot read (for example a mode that a later version adds) gives `SettingsStore`'s default, Off.

- [ ] **Step 1: Write the failing tests**

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, add the import `import java.io.IOException` after `import org.junit.Test`, and add a field after `private var settingsFailure: Exception? = null`:

```kotlin
    private val memory = FakeMemory()
```

Add these thirteen tests right after the test `settingsThatCannotOpenAreReportedAndShown`:

```kotlin
    @Test
    fun theModeIsStoredWhenItChanges() = runTest {
        val listening = listeningWithSession()
        listening.send(Event.Toggle)
        runCurrent()
        listening.send(Event.TurnOff)
        runCurrent()

        // Every process starts off, and that Off is not stored. Listening counts once the session is open.
        assertEquals(listOf(Mode.LISTENING, Mode.PAUSED, Mode.OFF), memory.writes)
    }

    @Test
    fun aStartThatFailsStoresOff() = runTest {
        services.failure = IllegalStateException("Not allowed in the background")
        val listening = listening()

        listening.send(Event.Toggle)
        runCurrent()

        // Listening was never stored, because no voice session opened; the Off is a change and is stored.
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf(Mode.OFF), memory.writes)
    }

    @Test
    fun anOffReplacesAModeThatAnEarlierProcessStored() = runTest {
        // An earlier process ended while Kestrel listened, and no restore has reset the stored mode.
        memory.stored = Mode.LISTENING
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()

        // Off before the voice session opened.
        listening.send(Event.TurnOff)
        runCurrent()

        assertEquals(Mode.OFF, memory.stored)
    }

    @Test
    fun theAccessibilityServiceBringsListeningBack() = runTest {
        memory.stored = Mode.LISTENING
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.LISTENING, listening.state.value.mode)
        assertEquals(listOf("start"), log)
    }

    @Test
    fun theAccessibilityServiceBringsThePauseBack() = runTest {
        memory.stored = Mode.PAUSED
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.PAUSED, listening.state.value.mode)
        assertEquals(listOf("start"), log)
    }

    @Test
    fun theStoredModeIsReadOncePerProcess() = runTest {
        memory.stored = Mode.LISTENING
        val listening = listening()
        listening.onAccessibilityServiceConnected()
        runCurrent()
        listening.send(Event.TurnOff)
        runCurrent()

        // The service connects again, for example after the owner turned it off and on in Android's settings.
        memory.stored = Mode.LISTENING
        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(1, memory.reads)
    }

    @Test
    fun aStoredOffStartsNothing() = runTest {
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(1, memory.reads)
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertTrue(log.isEmpty())
    }

    @Test
    fun aRestoreStoresOffBeforeItStarts() = runTest {
        memory.stored = Mode.LISTENING
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        // If this start ended Kestrel's process, the next start of the process would find Off.
        assertEquals(listOf(Mode.OFF), memory.writes)
        assertEquals(listOf("start"), log)

        listening.onForeground(service)
        runCurrent()

        assertEquals(listOf(Mode.OFF, Mode.LISTENING), memory.writes)
    }

    @Test
    fun nothingIsRestoredOnceKestrelHasBeenOn() = runTest {
        memory.stored = Mode.PAUSED
        val listening = listening()
        listening.send(Event.Toggle)
        runCurrent()
        listening.send(Event.TurnOff)
        runCurrent()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(0, memory.reads)
    }

    @Test
    fun theOwnersChoiceDuringTheReadIsKept() = runTest {
        memory.stored = Mode.LISTENING
        memory.reading = CompletableDeferred()
        val listening = listening()
        listening.onAccessibilityServiceConnected()
        runCurrent()

        // While the stored mode is read, the owner turns Kestrel on and off again.
        listening.send(Event.Toggle)
        runCurrent()
        listening.send(Event.TurnOff)
        runCurrent()
        memory.reading?.complete(Unit)
        runCurrent()

        // Only the owner's Off was stored; the restore wrote nothing and started nothing.
        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(listOf(Mode.OFF), memory.writes)
    }

    @Test
    fun anOnAndOffInOneRunDuringTheReadIsKept() = runTest {
        memory.stored = Mode.LISTENING
        memory.reading = CompletableDeferred()
        val listening = listening()
        listening.onAccessibilityServiceConnected()
        runCurrent()

        // Both wait in the channel, so the Orchestrator handles them in one run, and the state's collector sees neither.
        listening.send(Event.Toggle)
        listening.send(Event.TurnOff)
        runCurrent()
        memory.reading?.complete(Unit)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals(Mode.OFF, memory.stored)
    }

    @Test
    fun aRestoreThatTheOffHookSkippedStartsNothing() = runTest {
        memory.stored = Mode.LISTENING
        memory.reading = CompletableDeferred()
        val listening = listening()
        listening.onAccessibilityServiceConnected()
        runCurrent()

        listening.skipRestore()
        memory.reading?.complete(Unit)
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertTrue(log.isEmpty())
    }

    @Test
    fun aStoredModeThatCannotBeReadIsReported() = runTest {
        memory.readFailure = IOException("The disk failed")
        val listening = listening()

        listening.onAccessibilityServiceConnected()
        runCurrent()

        assertEquals(Mode.OFF, listening.state.value.mode)
        assertEquals("Reading the listening mode", reports.single().first)
    }
```

In the helper `listening(...)`, replace

```kotlin
        openSettings = {
            log += "open settings"
            settingsFailure?.let { throw it }
        },
        microphones = { microphone },
    )
```

with

```kotlin
        openSettings = {
            log += "open settings"
            settingsFailure?.let { throw it }
        },
        memory = memory,
        microphones = { microphone },
    )
```

Add this class right before the class `FakeMicrophone`:

```kotlin
    private class FakeMemory : ModeMemory {
        var stored = Mode.OFF
        var reads = 0
        var readFailure: Exception? = null
        var reading: CompletableDeferred<Unit>? = null
        val writes = mutableListOf<Mode>()

        override suspend fun read(): Mode {
            reads++
            reading?.await()
            readFailure?.let { throw it }
            return stored
        }

        override suspend fun write(mode: Mode) {
            writes += mode
            stored = mode
        }
    }

```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD FAILED`, with "Unresolved reference 'ModeMemory'", "Unresolved reference 'onAccessibilityServiceConnected'", "Unresolved reference 'skipRestore'" and "No parameter with name 'memory' found".

- [ ] **Step 3: Store the mode and restore it in `Listening`**

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, add the imports `import kotlinx.coroutines.flow.filterNotNull` after `import kotlinx.coroutines.flow.asStateFlow` and `import kotlinx.coroutines.withContext` after `import kotlinx.coroutines.sync.withLock`, and replace

```kotlin
/** Shows a text on the feedback bar (design spec, section 8.9), or nothing while the accessibility service is off. */
internal fun interface FeedbackBar {
    fun show(text: String)
}
```

with

```kotlin
/** Shows a text on the feedback bar (design spec, section 8.9), or nothing while the accessibility service is off. */
internal fun interface FeedbackBar {
    fun show(text: String)
}

/** Keeps Kestrel's listening mode across restarts of its process (design spec, sections 8.5 and 14.4). */
internal interface ModeMemory {
    /** The mode that Kestrel stored last, or [Mode.OFF] when it stored none. */
    suspend fun read(): Mode

    suspend fun write(mode: Mode)
}
```

Replace

```kotlin
 * the accessibility service, the notification and later the tile can send it events whether or not ListeningService
 * runs. It owns the [Orchestrator] and follows its state: when the mode leaves Off, it starts ListeningService; once the
 * service runs in the foreground, it opens a voice session; while the session is open, the microphone runs exactly while
 * [State.microphone] is true, and the pipeline's recognition is [State.recognition] before the first frame and after
 * every change. [Effect.StopService] closes the session and then stops the service.
```

with

```kotlin
 * the accessibility service, the notification and the tile can send it events whether or not ListeningService runs. It
 * owns the [Orchestrator] and follows its state: when the mode leaves Off, it starts ListeningService; once the service
 * runs in the foreground, it opens a voice session; while the session is open, the microphone runs exactly while
 * [State.microphone] is true, and the pipeline's recognition is [State.recognition] before the first frame and after
 * every change. [Effect.StopService] closes the session and then stops the service. It stores the mode in [memory]:
 * listening or paused once a voice session is open, off at once. When Kestrel's accessibility service connects for the
 * first time in a process in which Kestrel has not been on, it stores Off and turns Kestrel on in the stored mode, so
 * that listening comes back after Android ended Kestrel's process or the phone restarted, while a start that ends the
 * process does not come back at every start. A task never comes back (sections 8.5 and 14.4).
```

Replace

```kotlin
 * [openSettings] opens Kestrel's settings app; the controller's thread calls it, and it throws when Android refuses.
 */
```

with

```kotlin
 * [openSettings] opens Kestrel's settings app; the controller's thread calls it, and it throws when Android refuses.
 * [memory] is read in a coroutine on [thread] and written by one coroutine of [scope], so the newest mode is written last,
 * and by the restore, which writes Off before anything else can write.
 */
```

Replace

```kotlin
    private val openSettings: () -> Unit,
    microphones: () -> Microphone,
) {
```

with

```kotlin
    private val openSettings: () -> Unit,
    private val memory: ModeMemory,
    microphones: () -> Microphone,
) {
```

Replace

```kotlin
    private val lifecycle = Mutex()
    private val sessionState = MutableStateFlow(false)
```

with

```kotlin
    private val lifecycle = Mutex()
    private val sessionState = MutableStateFlow(false)

    // Only [thread] uses these. [lastMode]: the mode that was last noted for storing, null before the first change of
    // mode in this process. [restoreChecked]: the accessibility service has connected in this process, so the stored mode
    // was considered. [wasOn]: Kestrel has been on in this process, so the owner has already chosen a mode.
    private var lastMode: Mode? = null
    private var restoreChecked = false
    private var wasOn = false

    // The newest mode to store. It stays null until the mode first changes in this process, so that the Off that every
    // process starts with never replaces the stored mode before the restore has read it.
    private val modeToStore = MutableStateFlow<Mode?>(null)
```

Replace

```kotlin
    init {
        scope.launch(thread) { orchestrator.state.collect { followSafely(it) } }
    }

    /** Sends [event] to the Orchestrator. Any thread may call it. */
    fun send(event: Event) = orchestrator.send(event)
```

with

```kotlin
    init {
        scope.launch(thread) { orchestrator.state.collect { followSafely(it) } }
        // One writer, so that the newest mode is written last. It may skip a mode that a newer one replaced at once.
        scope.launch {
            modeToStore.filterNotNull().collect { mode ->
                try {
                    memory.write(mode)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    report("Storing the listening mode", e)
                }
            }
        }
    }

    /** Sends [event] to the Orchestrator. Any thread may call it. */
    fun send(event: Event) = orchestrator.send(event)

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

    /**
     * For the debug hook's off: no restore turns Kestrel on in this process from now on. A restore either stops at its
     * next check, or it sent its Event.Start before this returned, so an Event.TurnOff sent afterwards comes after it.
     */
    internal suspend fun skipRestore() {
        withContext(thread) {
            restoreChecked = true
            // As if the owner had chosen a mode.
            wasOn = true
        }
    }
```

Replace

```kotlin
    private fun followSafely(state: State) {
```

with

```kotlin
    // Every change of mode shows the new mode, so the controller sees each change here, in order. This includes the
    // changes that the Orchestrator makes in one run, which the state's collector skips. The Off that every process
    // starts with is not a change, so it never replaces the stored mode before the restore has read it.
    private fun noteMode(mode: Mode) {
        if (mode != Mode.OFF) wasOn = true
        remember(mode)
    }

    // Listening and paused count once a voice session is open, so that a start that ends Kestrel's process, such as a
    // native crash while the models load, does not come back at every start of the process. Off counts at once.
    private fun remember(mode: Mode) {
        if (mode != Mode.OFF && session == null) return
        if (mode == lastMode) return
        lastMode = mode
        modeToStore.value = mode
    }

    private fun followSafely(state: State) {
```

Replace

```kotlin
            is Effect.Show -> bar.show(text(effect.feedback))
```

with

```kotlin
            is Effect.Show -> {
                (effect.feedback as? Feedback.ModeChanged)?.let { noteMode(it.mode) }
                bar.show(text(effect.feedback))
            }
```

Replace

```kotlin
                session = opened
                listener = events
                sessionState.value = true
                followSafely(state.value)
```

with

```kotlin
                session = opened
                listener = events
                sessionState.value = true
                followSafely(state.value)
                // After followSafely, which starts the microphone, so that a native crash there comes before Listening is stored.
                remember(state.value.mode)
```

- [ ] **Step 4: The setting and the wiring**

`AppGraph` must pass `memory` before `:app` compiles again, so this step comes before the test run.

In `app/build.gradle.kts`, replace

```kotlin
plugins {
    id("kestrel.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
```

with

```kotlin
plugins {
    id("kestrel.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
```

Create `app/src/main/kotlin/dev/kestrel/app/listen/ListeningSetting.kt`:

```kotlin
package dev.kestrel.app.listen

import dev.kestrel.app.orchestrator.Mode
import kotlinx.serialization.Serializable

/**
 * The listening mode that Kestrel had last, which it turns on in again after its process restarts (design spec, sections
 * 8.5 and 14.4). A fresh install, and a value that this version cannot read, give Off.
 */
@Serializable
data class ListeningSetting(val mode: Mode = Mode.OFF)
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, add these imports in their alphabetical places: `import dev.kestrel.app.listen.ListeningSetting` after `import dev.kestrel.app.listen.ListeningService`, `import dev.kestrel.app.listen.ModeMemory` after it, and `import dev.kestrel.app.orchestrator.Mode` after `import dev.kestrel.app.listen.VoiceSessions`. Then replace

```kotlin
object SettingKeys {
    val SAFETY = SettingKey("safety", SafetySettings.serializer(), SafetySettings())
}
```

with

```kotlin
object SettingKeys {
    val SAFETY = SettingKey("safety", SafetySettings.serializer(), SafetySettings())

    /** The listening mode that Kestrel turns on in again after its process restarts (design spec, sections 8.5 and 14.4). */
    val LISTENING = SettingKey("listening", ListeningSetting.serializer(), ListeningSetting())
}
```

and replace

```kotlin
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            microphones = { MicCapture(appContext) },
```

with

```kotlin
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            // Encrypted like every setting (section 13.4). SettingsStore reads and writes on Dispatchers.IO.
            memory = object : ModeMemory {
                override suspend fun read(): Mode = settings.get(SettingKeys.LISTENING).mode

                override suspend fun write(mode: Mode) = settings.set(SettingKeys.LISTENING, ListeningSetting(mode))
            },
            microphones = { MicCapture(appContext) },
```

In `app/src/main/kotlin/dev/kestrel/app/KestrelAccessibilityService.kt`, replace

```kotlin
        accessibilityButtonController.registerAccessibilityButtonCallback(clicks)
        button = clicks
    }
```

with

```kotlin
        accessibilityButtonController.registerAccessibilityButtonCallback(clicks)
        button = clicks
        // After a restart of Kestrel's process or of the phone, listening comes back from here (sections 8.5 and 14.4).
        graph.listening.onAccessibilityServiceConnected()
    }
```

- [ ] **Step 5: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD SUCCESSFUL`, with 49 tests in `ListeningTest` (36 and the 13 new ones).

- [ ] **Step 6: Update the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 8.5, replace

```markdown
- An optional setting, off by default, pauses listening after 30 s without speech.
```

with

```markdown
- When Android ends Kestrel's process or the phone restarts, Kestrel returns to the mode that it had, listening or paused, as soon as its accessibility service connects again. A task never comes back (section 14.4). Kestrel counts listening or paused as its mode only once its voice session is open, so when Kestrel turned itself off after a failure, or it ended before its voice session was open, it stays off.
- An optional setting, off by default, pauses listening after 30 s without speech.
```

- [ ] **Step 7: Run all JVM tests, lint and the audit**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug kestrelAudit`
Expected: `BUILD SUCCESSFUL`: 123 JVM tests in `:app` (110 and the 13 new ones), the device tests compile, lint finds no issue (`app\build\reports\lint-results-debug.txt` says "No issues found."), and the audit passes: the serialization plugin adds no dependency.

- [ ] **Step 8: Write the failing device test of the hook's `off`**

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceHookTest.kt`, add the imports `import dev.kestrel.app.SettingKeys` after `import dev.kestrel.app.MainActivity` and `import dev.kestrel.app.listen.ListeningSetting` after `import dev.kestrel.app.debug.VoiceHooks`, and add this test right after the test `anUnknownEventSaysWhichEventsExist`:

```kotlin
    @Test
    fun offStoresOffAlsoWhileKestrelIsOff() {
        val settings = VoiceTests.graph.settings
        try {
            // As an interrupted run leaves it: Kestrel is off in this process, and Listening is stored.
            runBlocking { settings.set(SettingKeys.LISTENING, ListeningSetting(Mode.LISTENING)) }
            assertEquals(Mode.OFF, listening.state.value.mode)

            assertEquals("sent off", hook("VOICE", "event" to "off").text)

            assertEquals(Mode.OFF, runBlocking { settings.get(SettingKeys.LISTENING) }.mode)
        } finally {
            runBlocking { settings.set(SettingKeys.LISTENING, ListeningSetting()) }
        }
    }
```

`VoiceHookTest` already imports `runBlocking`, `Mode` and `assertEquals`.

- [ ] **Step 9: Check the emulator, and run the test to verify that it fails**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; & (Get-Adb) -s emulator-5554 emu avd name`
Expected: `kestrel-api37`, then `OK`. Anything else: stop, and start the emulator as "Before you start" says.

Run: `scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.voice.VoiceHookTest#offStoresOffAlsoWhileKestrelIsOff' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' --no-configuration-cache`
Expected: `BUILD FAILED`, with 1 failed test, `VoiceHookTest > offStoresOffAlsoWhileKestrelIsOff`, and "expected:<OFF> but was:<LISTENING>": while Kestrel is off, `Event.TurnOff` changes nothing, so nothing stores Off. The test's `finally` stores Off again.

- [ ] **Step 10: Store Off in the tests' rule and in the hook's `off`**

In `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt`, add the imports `import dev.kestrel.app.SettingKeys` after `import dev.kestrel.app.AppGraph` and `import dev.kestrel.app.listen.ListeningSetting` after `import dev.kestrel.app.debug.TestHooks.HookError`. Replace

```kotlin
    private fun event(intent: Intent): String {
```

with

```kotlin
    private suspend fun event(intent: Intent): String {
```

and replace

```kotlin
            "off" -> Event.TurnOff
```

with

```kotlin
            "off" -> {
                // No restore may turn Kestrel on in this process from now on. Then Off is stored at once, also while Kestrel
                // is off, as it is in a new process, where TurnOff changes nothing and so stores nothing: Kestrel must not
                // come back at the next start of its process.
                graph.listening.skipRestore()
                graph.settings.set(SettingKeys.LISTENING, ListeningSetting())
                Event.TurnOff
            }
```

This is safe next to `Listening`'s single writer: after a `TurnOff`, Off is the newest mode, so the writer's last write is Off too. `run` is a `suspend` function, which `TestHooks.answer` calls inside the receiver's coroutine, so `event` may suspend.

In `app/src/androidTest/kotlin/dev/kestrel/app/screen/ScreenServiceRule.kt`, add the imports `import dev.kestrel.app.SettingKeys` and `import dev.kestrel.app.listen.ListeningSetting` after `import dev.kestrel.app.KestrelApplication`, and replace

```kotlin
    override fun before() {
        runBlocking {
            shell("settings delete secure enabled_accessibility_services")
```

with

```kotlin
    override fun before() {
        runBlocking {
            // A mode that an interrupted run stored would turn Kestrel on when its service connects (section 8.5).
            graph.settings.set(SettingKeys.LISTENING, ListeningSetting())
            shell("settings delete secure enabled_accessibility_services")
```

In `scripts/hook.ps1`, replace

```powershell
#                                           notification's Pause or Resume and Stop actions work while Kestrel is on
```

with

```powershell
#                                           notification's Pause or Resume and Stop actions work while Kestrel is on;
#                                           off also stops a restore of the stored mode and stores Off, also while
#                                           Kestrel is off, so that Kestrel does not come back at the next start of
#                                           its process
```

- [ ] **Step 11: Build the device tests and check the scripts**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin :app:lintDebug`
Expected: `BUILD SUCCESSFUL`, and lint finds no issue.

Run: `scripts\test-scripts.ps1`
Expected: "The script tests and checks passed.": `Tests Passed: 70, Failed: 0` in each shell, and "PSScriptAnalyzer found 0 problems."

- [ ] **Step 12: Check the emulator**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; & (Get-Adb) -s emulator-5554 emu avd name`
Expected: `kestrel-api37`, then `OK`. Anything else: stop, and start the emulator as "Before you start" says.

- [ ] **Step 13: Run the voice device tests**

`ActivationTest` connects Kestrel's accessibility service, which reads the stored mode once in the test process; `ScreenServiceRule` has stored Off just before, so nothing starts, whatever an earlier run left.

Run: `scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 60 run, 0 failed, 0 errors, 0 skipped.", including the 7 tests of `VoiceHookTest`. `offStoresOffAlsoWhileKestrelIsOff`, which failed in Step 9, passes now.

- [ ] **Step 14: Commit**

```powershell
git add app/build.gradle.kts app/src/main/kotlin/dev/kestrel/app/listen/ListeningSetting.kt app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/main/kotlin/dev/kestrel/app/KestrelAccessibilityService.kt app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md app/src/androidTest/kotlin/dev/kestrel/app/screen/ScreenServiceRule.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceHookTest.kt app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt scripts/hook.ps1
git commit -m "feat(app): store the listening mode and bring it back when the accessibility service connects after a restart" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: Replace the spike's stand-ins, and check the start paths with a script

**Files:**
- Delete: `app/src/debug/kotlin/dev/kestrel/app/debug/spike/SpikeListeningService.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/spike/SpikeStartup.kt`
- Modify: `app/src/debug/kotlin/dev/kestrel/app/debug/spike/SpikeListening.kt` (whole file below)
- Modify: `app/src/debug/kotlin/dev/kestrel/app/debug/spike/SpikeHooks.kt` (whole file below)
- Modify: `app/src/debug/AndroidManifest.xml` (whole file below)
- Modify: `scripts/lib/Kestrel.ps1` (three functions at the end)
- Modify: `scripts/hook.ps1` (help and `ValidateSet`)
- Modify: `scripts/spike-s1.ps1` (whole file below)
- Modify: `scripts/spike-s4.ps1` (the hook's `off` before the accessibility service is turned on)
- Create: `scripts/listen-check.ps1`
- Modify: `README.md`, `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 19.1)
- Test: `scripts/tests/Kestrel.Tests.ps1`

**Interfaces:**
- Consumes: `KestrelTileService` (component `dev.kestrel/dev.kestrel.app.listen.KestrelTileService`) from Task 3; the restore of Task 4; the button callback of Task 2; `Effect.OpenSettings` of Task 1; `scripts\hook.ps1`'s `state`, `voice -Send off|toggle`, `voice -Send heard -Text ... -Named` and `voice-state`, whose `off` stops a restore and stores Off since Task 4; from `scripts\lib\Kestrel.ps1`: `Use-KestrelToolchain`, `Assert-TestDevice`, `Get-Adb`, `Invoke-SettingsCommand`, `Get-SecureSetting`, `Set-SecureSetting`, `Get-AccessibilitySettings`, `Restore-AccessibilitySettings`.
- Produces: in `scripts\lib\Kestrel.ps1`, `ConvertFrom-VoiceState([string]$Text)` (an ordered table of the voice-state hook's names and values; throws for any other text) and `Test-ShortcutTarget([string[]]$Dump, [string]$Package)` (`$true` when the `shortcut key:{...}` line of `dumpsys accessibility` names a service of the package) and `Test-RecordingSilenced([string[]]$Dump, [string]$Package)` (`$true` or `$false` from the newest `rec start` or `rec update` line of `dumpsys audio` for the package, `$null` when there is none); `scripts\listen-check.ps1 -Serial <serial> [-Reboot]`.

Plan 2C-1 left spike S1's stand-ins in the debug build: `SpikeListeningService`, `SpikeTileService` (gone with Task 3), `SpikeStartup`, and the hooks `LISTEN`, `LISTEN_NOTIFICATION` and `LISTEN_AFTER_BOOT`. The real service, tile, shortcut and restore replace them, so they go; the location check (`LOCATION`, `SPIKE_RESULTS`, `SpikeListening`'s results) and the hooks of spikes S3 and S7 (`WATCH`, `FILES`) stay. The debug manifest keeps only the location permissions: `RECORD_AUDIO` and the `FOREGROUND_SERVICE*` permissions are in the main manifest since plan 2C-1, and the debug manifest needed them only for the stand-in service.

`scripts\listen-check.ps1` repeats S1's checks of the tile, the accessibility service and the reboot with the real service, with Kestrel in the background and without instrumentation, which `ActivationTest` cannot: the tile with the accessibility service off (only the tap's exemption allows the start), the accessibility shortcut, "Kestrel, open settings" while the service is on (Android lets Kestrel start an activity from the background only while the service is bound, and blocks it silently otherwise), the return of listening after `am crash dev.kestrel`, and with `-Reboot` after a reboot. Android counts two crashes within 2 minutes as crashing too often (`min_crash_interval=120000` on kestrel-api37) and then keeps the accessibility service away for 30 minutes, so the script and the README ask for 2 minutes between two runs. Each check starts with the hook's `off`, which stops a restore in Kestrel's process and stores Off, so a mode that an earlier run stored cannot turn Kestrel on in the middle of a check, also when the accessibility service is already on when the script starts. Kestrel stores Listening only once its voice session is open, from a coroutine a moment later, so the restart and reboot checks wait 2 s after the session opened before they end the process. As spike S1 did, the tile, shortcut and restart checks also check that Android lets Kestrel record: the newest `rec start` or `rec update` line of `dumpsys audio` for Kestrel, which on kestrel-api37 reads `rec start riid:1071 uid:10331 session:1193 src:VOICE_RECOGNITION not silenced pack:dev.kestrel`, must belong to a recording that started after the check's action and say `not silenced`. The tile check waits until `sysui_qs_tiles` names Kestrel's tile and then 2 s before it taps, because a tap before SystemUI has made the tile is lost; SystemUI's dump lists only the tiles of the first page, so the device test's wait for the tile's look does not carry over to a tile that `add-tile` puts at the end. S1's start from a notification action and its recording while a call rings are no longer scripted; "Not in this plan" names the plans that check them again. `spike-s1.ps1` and `spike-s4.ps1` turn Kestrel's accessibility service on, so they first send the hook's `off`, which stores Off, or Kestrel would bring back a listening mode that it stored and stay on after the spike. It reads Kestrel's state through `hook.ps1 voice-state`, so it declares `#Requires -Version 7.3` like `hook.ps1` and `spike-s1.ps1`; the three helpers that it needs go into `scripts\lib\Kestrel.ps1`, which runs in both shells, and get Pester tests there. It calls `Assert-TestDevice` before any command reaches the device, and `hook.ps1` checks the device again on every call. Commands that toggle something (a tap on the tile, the shortcut, `am crash`) run once through adb, because `Invoke-SettingsCommand` repeats a command that failed, and a repeated toggle would undo the first.

- [ ] **Step 1: Write the failing Pester tests**

At the end of `scripts/tests/Kestrel.Tests.ps1`, after the `Describe 'Get-TestResultCounts'` block, add:

```powershell

Describe 'ConvertFrom-VoiceState' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
    }

    It 'reads every name and value of the hook''s answer' {
        $state = ConvertFrom-VoiceState 'mode=listening work=idle speaking=false microphone=true recognition=all screenOn=true micBusy=false session=open clips=off'

        $state.mode | Should -BeExactly 'listening'
        $state.session | Should -BeExactly 'open'
        $state.clips | Should -BeExactly 'off'
        @($state.Keys) | Should -HaveCount 9
    }

    It 'throws for a text that is not the hook''s answer: ''<_>''' -ForEach @('', 'error: the command failed', 'mode=off work=idle') {
        $text = $_
        { ConvertFrom-VoiceState $text } | Should -Throw -ExpectedMessage "Not an answer of the voice-state hook: '$text'"
    }
}

Describe 'Test-ShortcutTarget' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
    }

    It 'returns $true when the shortcut''s line names a service of the package' {
        $dump = @(
            '     button mode: 1'
            '     shortcut key:{com.example.reader/com.example.reader.ReaderService, dev.kestrel/dev.kestrel.app.KestrelAccessibilityService}'
            '     button:{}'
        )

        Test-ShortcutTarget -Dump $dump -Package dev.kestrel | Should -BeExactly $true
    }

    It 'returns $false when <Name>' -ForEach @(
        @{ Name = 'the line names another package'; Dump = @('     shortcut key:{com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService}') }
        @{ Name = 'the line names a package that only starts like it'; Dump = @('     shortcut key:{dev.kestrel.testapp/dev.kestrel.testapp.Service}') }
        @{ Name = 'the line names no service'; Dump = @('     shortcut key:{}') }
        @{ Name = 'there is no such line'; Dump = @('     button:{}') }
    ) {
        Test-ShortcutTarget -Dump $Dump -Package dev.kestrel | Should -BeExactly $false
    }
}

Describe 'Test-RecordingSilenced' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
    }

    It 'returns $false when the newest recording of the package is not silenced' {
        $dump = @(
            '11-16 10:21:56:811 rec start riid:1831 uid:10331 session:2001 src:VOICE_RECOGNITION silenced pack:dev.kestrel'
            '11-16 10:21:57:022 rec start riid:1839 uid:10331 session:2009 src:VOICE_RECOGNITION not silenced pack:dev.kestrel'
            '11-16 10:21:57:123 rec stop riid:1839 uid:10331 session:2009 src:VOICE_RECOGNITION silenced pack:dev.kestrel'
            '11-16 10:21:57:200 rec start riid:1840 uid:10200 session:2010 src:MIC silenced pack:dev.kestrel.testapp'
        )

        Test-RecordingSilenced -Dump $dump -Package dev.kestrel | Should -BeExactly $false
    }

    It 'returns $true when Android silenced the newest recording of the package' {
        $dump = @(
            '11-16 10:21:57:022 rec start riid:1839 uid:10331 session:2009 src:VOICE_RECOGNITION not silenced pack:dev.kestrel'
            '11-16 10:21:58:410 rec update riid:1839 uid:10331 session:2009 src:VOICE_RECOGNITION silenced pack:dev.kestrel'
        )

        Test-RecordingSilenced -Dump $dump -Package dev.kestrel | Should -BeExactly $true
    }

    It 'returns $null when no recording names the package' {
        $dump = @(
            '11-16 10:21:57:200 rec start riid:1840 uid:10200 session:2010 src:MIC not silenced pack:dev.kestrel.testapp'
            'some other line'
        )

        Test-RecordingSilenced -Dump $dump -Package dev.kestrel | Should -BeNullOrEmpty
    }
}
```

- [ ] **Step 2: Run the script tests to verify that they fail**

Run: `scripts\test-scripts.ps1`
Expected: "The script tests or checks failed." and exit code 1. In both shells Pester prints `Tests Passed: 70, Failed: 12`: the 12 new tests fail with "The term 'ConvertFrom-VoiceState' is not recognized", "The term 'Test-ShortcutTarget' is not recognized" or "The term 'Test-RecordingSilenced' is not recognized". PSScriptAnalyzer finds 0 problems.

- [ ] **Step 3: Write the helpers**

At the end of `scripts/lib/Kestrel.ps1`, after the function `Get-TestResultCounts`, add:

```powershell

# Reads the answer of the voice-state hook, such as 'mode=listening work=idle ... session=open clips=off', into an ordered
# table of names and values. The answer never contains what Kestrel heard. Throws when the text is not such an answer.
function ConvertFrom-VoiceState([string]$Text) {
    $state = [ordered]@{}
    foreach ($pair in @("$Text".Trim() -split '\s+' | Where-Object { $_ })) {
        $name, $value = $pair -split '=', 2
        if (-not $name -or $null -eq $value) { throw "Not an answer of the voice-state hook: '$Text'" }
        $state[$name] = $value
    }
    if (-not ($state.Contains('mode') -and $state.Contains('session'))) { throw "Not an answer of the voice-state hook: '$Text'" }
    $state
}

# Returns whether the output of 'dumpsys accessibility' names a service of the package as a target of the accessibility
# shortcut, in its line 'shortcut key:{...}'. Android reads the setting a moment after it changes, and this line shows
# what it has read.
function Test-ShortcutTarget([string[]]$Dump, [string]$Package) {
    foreach ($line in $Dump) {
        if ($line -match 'shortcut key:\{([^}]*)\}') {
            return [bool](@($Matches[1] -split ',\s*') | Where-Object { $_ -like "$Package/*" })
        }
    }
    $false
}

# Returns whether Android silences the package's recording, from the output of 'dumpsys audio': the newest line
# 'rec start ...' or 'rec update ...' of the package ends with 'silenced pack:<package>' or 'not silenced pack:<package>'.
# Returns $null when no such line names the package.
function Test-RecordingSilenced([string[]]$Dump, [string]$Package) {
    $silenced = $null
    $pattern = '\brec (?:start|update) .*? (?<state>not silenced|silenced) pack:' + [regex]::Escape($Package) + '(?:\s|$)'
    foreach ($line in $Dump) {
        if ($line -match $pattern) { $silenced = $Matches['state'] -eq 'silenced' }
    }
    $silenced
}
```

- [ ] **Step 4: Run the script tests to verify that they pass**

Run: `scripts\test-scripts.ps1`
Expected: "The script tests and checks passed.": Pester prints `Tests Passed: 82, Failed: 0` in Windows PowerShell 5.1 and in PowerShell 7 (70 and the 12 new ones), and "PSScriptAnalyzer found 0 problems."

- [ ] **Step 5: Remove the stand-ins from the debug build**

Run: `git rm app/src/debug/kotlin/dev/kestrel/app/debug/spike/SpikeListeningService.kt app/src/debug/kotlin/dev/kestrel/app/debug/spike/SpikeStartup.kt`
Expected: two `rm '...'` lines.

Replace the whole of `app/src/debug/kotlin/dev/kestrel/app/debug/spike/SpikeListening.kt` with:

```kotlin
package dev.kestrel.app.debug.spike

import android.content.Context
import java.io.File
import java.time.Instant

/**
 * Spike S1's location check (design spec, section 19), in debug builds only: can Kestrel read the location while another
 * app is in front? `scripts/spike-s1.ps1` runs it. The results go to a file that `scripts/hook.ps1 spike-results` reads,
 * and they say only whether a location came, never where the device is. `scripts/listen-check.ps1` checks how the real
 * ListeningService starts.
 */
internal object SpikeListening {
    fun record(context: Context, line: String) = synchronized(this) {
        resultsFile(context).appendText("${Instant.now()} $line\n")
    }

    fun results(context: Context): String = synchronized(this) {
        resultsFile(context).takeIf(File::exists)?.readText().orEmpty()
    }

    fun clearResults(context: Context) = synchronized(this) { resultsFile(context).delete() }

    private fun resultsFile(context: Context) = File(context.noBackupFilesDir, "spike-results.txt")
}
```

Replace the whole of `app/src/debug/kotlin/dev/kestrel/app/debug/spike/SpikeHooks.kt` with:

```kotlin
package dev.kestrel.app.debug.spike

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

/** The test-hook commands of spike S1's location check and of spikes S3 and S7. `scripts/hook.ps1` lists them. */
internal class SpikeHooks(private val context: Context, private val graph: AppGraph) {
    suspend fun run(command: String, intent: Intent): String = when (command) {
        "FILES" -> files(intent)
        "LOCATION" -> location(intent)
        "WATCH" -> watch(intent)
        else -> results(intent)
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
        val COMMANDS = setOf("FILES", "LOCATION", "SPIKE_RESULTS", "WATCH")
        private const val BUFFER_BYTES = 1 shl 16
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

Replace the whole of `app/src/debug/AndroidManifest.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Spike S1's location check (design spec, section 19). -->
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
    </application>
</manifest>
```

- [ ] **Step 6: Build, lint and audit the debug build without the stand-ins**

Run: `scripts\gradle.ps1 :app:assembleDebug :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug kestrelAudit`
Expected: `BUILD SUCCESSFUL`: 123 JVM tests in `:app`, lint finds no issue (`app\build\reports\lint-results-debug.txt` says "No issues found."), and the audit passes. A compile error that names `SpikeListening.start`, `SpikeListeningService` or `requestListenAtNextStart` means a caller was missed: `git grep -n "SpikeListening\.start\|SpikeListeningService\|ListenAtNextStart" app` must print nothing.

- [ ] **Step 7: Remove the `listen` commands from `hook.ps1`**

In `scripts/hook.ps1`, replace

```powershell
# Spike S1 (scripts\spike-s1.ps1 runs these):
#   listen [-Source name] [-DelaySeconds n] [-Seconds n]
#                                           starts the stand-in listening service after n seconds (15 by default)
#   listen-notification                     posts a notification whose action starts it
#   listen-after-boot                       starts it 15 s after the accessibility service connects at the next
#                                           start of Kestrel's process, which a reboot causes
#   location [-Source name] [-DelaySeconds n]
```

with

```powershell
# Spike S1's location check (scripts\spike-s1.ps1 runs these):
#   location [-Source name] [-DelaySeconds n]
```

and replace

```powershell
        'voice-state', 'voice-clip', 'listen', 'listen-notification', 'listen-after-boot', 'location', 'spike-results',
        'files', 'watch')][string]$Command,
```

with

```powershell
        'voice-state', 'voice-clip', 'location', 'spike-results', 'files', 'watch')][string]$Command,
```

and replace

```powershell
#   spike-results [-Clear]                  what the spike found: whether each step worked, never the audio or the place
```

with

```powershell
#   spike-results [-Clear]                  what the location check found: whether a location came, never the place
```

The parameters `-Source`, `-DelaySeconds` and `-Seconds` stay: `location` and `watch` use them.

- [ ] **Step 8: Keep only the location check in `spike-s1.ps1`**

Replace the whole of `scripts/spike-s1.ps1` with:

```powershell
# Spike S1 (design spec, section 19), its location check: can Kestrel read the location while another app is in front?
# scripts\listen-check.ps1 checks how Kestrel's real listening service starts. This script needs Kestrel's debug build
# (scripts\install.ps1), and it prints what Kestrel recorded in its results: whether a
# location came, never the place. The location comes from a test provider with a made-up place, which replaces the fused
# provider while the script runs.
# Afterwards, also when it fails or is interrupted, it puts back what it changed on the device. If it cannot put something
# back, also because Ctrl+C interrupted it while it put things back, it says at the end what is left and how to put it
# back by hand. Then, and after Ctrl+C, it ends with exit code 1. With -Verbose, it names each thing that it puts back.
# Example: scripts\spike-s1.ps1 -Serial emulator-5554
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
Assert-TestDevice -Serial $Serial
$adb = Get-Adb
$service = 'dev.kestrel/dev.kestrel.app.KestrelAccessibilityService'

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

# Asks Kestrel for the location, and lets the test provider report its place a few times meanwhile.
function Get-SpikeLocation([string]$Source) {
    Invoke-Hook location -Source $Source | Out-Null
    for ($i = 0; $i -lt 4; $i++) {
        Start-Sleep -Seconds 3
        Invoke-Shell 'cmd location providers set-test-provider-location fused --location 44.8125,20.4612 --accuracy 10' | Out-Null
    }
    Wait-Result "source=$Source " 20
}

# Runs a command on the device and fails when it fails.
function Invoke-Checked([string]$Command) { Invoke-SettingsCommand -Serial $Serial -Command $Command }

# Returns the permissions, of those given, that Kestrel has now.
function Get-GrantedPermissions([string[]]$Permissions) {
    $package = (Invoke-Checked 'dumpsys package dev.kestrel') -join "`n"
    @($Permissions | Where-Object { $package -match "android\.permission\.$_`: granted=true" })
}

# Returns the shell's mock-location mode: allow, ignore, deny, foreground or default.
function Get-MockLocationMode {
    $answer = (Invoke-Checked 'appops get com.android.shell android:mock_location') -join "`n"
    if ($answer -match '(?m)^\s*MOCK_LOCATION: (\w+)') { return $Matches[1] }
    # For an app-op that the shell has never used, Android names the mode that it then has.
    if ($answer -match '(?m)^Default mode: (\w+)') { return $Matches[1] }
    throw "Could not read the shell's mock-location mode from: $answer"
}

# What the script has still to put back. Each restore enters this list before the change that it undoes is made, and it
# leaves the list when it has worked. At the end, the script names the restores that are left and how to do them by hand.
$pending = [System.Collections.Generic.List[object]]::new()
$stopReason = 'the script was stopped'

function Register-Restore([string]$What, [string]$ByHand, [scriptblock]$Action) {
    $restore = [pscustomobject]@{ What = $What; ByHand = $ByHand; Action = $Action; Reason = $stopReason }
    $pending.Add($restore)
    $restore
}

# Runs the restores in order. A stop, such as Ctrl+C, that comes while a finally block runs ends that block at once, and
# PowerShell then runs the finally blocks around it in full and ignores further stops. So each restore runs in the
# finally block of the one before it, and a stop ends only the restore that is running.
function Invoke-Restore([object[]]$Steps, [int]$Index = 0) {
    if ($Index -ge $Steps.Count) { return }
    $restore = $Steps[$Index]
    try {
        Write-Verbose "Putting back $($restore.What)"
        & $restore.Action | Out-Null
        $pending.Remove($restore) | Out-Null
    } catch {
        $restore.Reason = $_.Exception.Message
    } finally {
        Invoke-Restore $Steps ($Index + 1)
    }
}

# Returns a PowerShell command that runs the given command on the device, for the warnings.
function Format-DeviceCommand([string]$Command) {
    "Run: & '$($adb -replace "'", "''")' -s $Serial shell '$($Command -replace "'", "''")'"
}

$results = [ordered]@{}
$permissions = 'ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION'
$savedAccessibility = Get-AccessibilitySettings -Serial $Serial
$grantedBefore = Get-GrantedPermissions $permissions
$notGrantedBefore = @($permissions | Where-Object { $_ -notin $grantedBefore })
$revokeByHand = @($notGrantedBefore | ForEach-Object { "pm revoke dev.kestrel android.permission.$_" }) -join '; '
# The commands that put back the accessibility settings by hand. As Restore-AccessibilitySettings does, they put back the
# list of services first, and they delete the list and the switch if these were not there before.
$accessibilityByHand = @(foreach ($setting in @('enabled_accessibility_services', 'accessibility_enabled') + @($savedAccessibility.Keys) | Select-Object -Unique) {
        $value = if ($savedAccessibility.Contains($setting)) { $savedAccessibility[$setting] } else { 'null' }
        if ($value -ne 'null') {
            "settings put secure $setting '$($value -replace "'", "'\''")'"
        } elseif ($setting -in 'enabled_accessibility_services', 'accessibility_enabled') {
            "settings delete secure $setting"
        }
    }) -join '; '
$completed = $false
$failed = $false
$restoresAtEnd = @(
    Register-Restore 'the accessibility settings' (Format-DeviceCommand $accessibilityByHand) {
        if (-not (Restore-AccessibilitySettings -Serial $Serial -Saved $savedAccessibility)) { throw 'see the warning above' }
    }
    # Revoking a permission stops Kestrel's process, so this comes last.
    if ($notGrantedBefore) {
        Register-Restore "Kestrel's permissions" (Format-DeviceCommand $revokeByHand) {
            $granted = @(Get-GrantedPermissions $notGrantedBefore)
            foreach ($permission in $granted) { Invoke-Checked "pm revoke dev.kestrel android.permission.$permission" | Out-Null }
            $still = if ($granted) { @(Get-GrantedPermissions $granted) } else { @() }
            if ($still) { throw "$($still -join ', ') still granted" }
        }
    }
)
try {
    foreach ($permission in $permissions) { Invoke-Checked "pm grant dev.kestrel android.permission.$permission" | Out-Null }
    Invoke-Hook spike-results -Clear | Out-Null

    # The shell may add a test provider only with the mock-location permission.
    $mockMode = Get-MockLocationMode
    $locationOn = (Invoke-Checked 'cmd location is-location-enabled') -join ''
    # Android records in this setting who switched location last.
    $locationChanger = Get-SecureSetting -Serial $Serial -Name location_changer
    $changerCommand = if ($locationChanger -eq 'null') { 'settings delete secure location_changer' } else { "settings put secure location_changer '$locationChanger'" }
    # Android removes the test provider only while the shell has the mock-location permission.
    $removeProvider = 'appops set com.android.shell android:mock_location allow; cmd location providers remove-test-provider fused; ' +
    "appops set com.android.shell android:mock_location $mockMode"
    $providerAdded = $false
    $restores = @(
        Register-Restore 'the home screen' (Format-DeviceCommand 'input keyevent KEYCODE_HOME') {
            Invoke-Shell 'input keyevent KEYCODE_HOME'
        }
        Register-Restore 'the fused location provider' (Format-DeviceCommand $removeProvider) {
            if ($providerAdded) { Invoke-Checked 'cmd location providers remove-test-provider fused' }
        }
        Register-Restore 'the mock-location permission of the shell' (Format-DeviceCommand "appops set com.android.shell android:mock_location $mockMode") {
            Invoke-Checked "appops set com.android.shell android:mock_location $mockMode"
            if ((Get-MockLocationMode) -ne $mockMode) { throw "it is not $mockMode" }
        }
        Register-Restore 'the location switch' (Format-DeviceCommand "cmd location set-location-enabled $locationOn; $changerCommand") {
            Invoke-Checked "cmd location set-location-enabled $locationOn"
            if (((Invoke-Checked 'cmd location is-location-enabled') -join '') -ne $locationOn) { throw "it is not $locationOn" }
            if ((Get-SecureSetting -Serial $Serial -Name location_changer) -cne $locationChanger -and
                -not (Set-SecureSetting -Serial $Serial -Name location_changer -Value $locationChanger)) {
                throw 'the setting location_changer differs from before'
            }
        }
    )
    try {
        Invoke-Checked 'appops set com.android.shell android:mock_location allow' | Out-Null
        Invoke-Checked 'cmd location set-location-enabled true' | Out-Null
        $providerAdded = $true
        Invoke-Checked 'cmd location providers add-test-provider fused' | Out-Null
        Invoke-Checked 'cmd location providers set-test-provider-enabled fused true' | Out-Null
        # When its accessibility service connects, Kestrel would bring back a listening mode that it stored, and this
        # script never turns it off. The hook's off stops that and stores Off.
        Invoke-Hook voice -Send off | Out-Null
        Set-KestrelService $true
        Invoke-Shell 'am start -W -n dev.kestrel/dev.kestrel.app.MainActivity' | Out-Null
        $results['location, Kestrel in front'] = Get-SpikeLocation 'kestrel-in-front'
        Invoke-Shell 'am start -W -a android.settings.SETTINGS' | Out-Null
        $results['location, Settings in front'] = Get-SpikeLocation 'settings-in-front'
        Set-KestrelService $false
        $results['location, service off'] = Get-SpikeLocation 'service-off'
    } finally {
        Invoke-Restore $restores
    }
    Write-Host "Android may keep the made-up place as the device's last known location until the device gets a real one or restarts."
    $completed = $true
} catch {
    $failed = $true
    throw
} finally {
    try {
        Invoke-Restore $restoresAtEnd
    } finally {
        foreach ($restore in $pending) {
            Write-Warning "Could not put back $($restore.What) on $Serial ($($restore.Reason)). $($restore.ByHand)"
        }
        # After a stop, such as Ctrl+C, PowerShell would end the script with exit code 0. A failed run ends with its error.
        if (-not ($completed -or $failed) -or $pending.Reason -contains $stopReason) {
            # pwsh -File ends a stopped script that declares parameters with exit code 0, even after 'exit 1', unless the
            # script gives the host its exit code. Run from a prompt, that would close the window, so the script does it
            # only when nothing called it.
            if (@(Get-PSCallStack).Count -eq 1) { $Host.SetShouldExit(1) }
            exit 1
        }
    }
}
$results.GetEnumerator() | ForEach-Object { '{0,-30} {1}' -f $_.Key, $_.Value }
if ($pending.Count -gt 0) {
    Write-Warning 'The device is not as it was before. The warnings above say how to put it back.'
    exit 1
}
```

In `scripts/spike-s4.ps1`, replace

```powershell
    if ($LASTEXITCODE -ne 0) { throw "adb reverse tcp:$port failed." }
    Invoke-Checked "settings put secure enabled_accessibility_services $service" | Out-Null
```

with

```powershell
    if ($LASTEXITCODE -ne 0) { throw "adb reverse tcp:$port failed." }
    # When its accessibility service connects, Kestrel would bring back a listening mode that it stored, and this script
    # never turns it off. The hook's off stops that and stores Off.
    Invoke-Hook voice -Send off | Out-Null
    Invoke-Checked "settings put secure enabled_accessibility_services $service" | Out-Null
```

- [ ] **Step 9: Write `listen-check.ps1`**

Create `scripts/listen-check.ps1`:

```powershell
# Checks the ways in which Kestrel's listening starts without a word from the owner (design spec, sections 8.5, 8.6 and
# 14.4), on a test device with Kestrel's debug build (scripts\install.ps1) and its speech models (scripts\push-models.ps1):
#   tile      a tap on Kestrel's Quick Settings tile starts listening while Kestrel's accessibility service is off, and a
#             second tap pauses it
#   shortcut  the accessibility shortcut ('cmd accessibility call-system-action 13') starts listening, and a second press
#             pauses it
#   settings  with the accessibility service on, 'Kestrel, open settings' (a transcript that the hook sends) brings
#             Kestrel's settings app to the front
#   restart   after Android ended Kestrel's process ('am crash'), listening comes back once the accessibility service has
#             connected again. Android does not restart an app that crashed twice within 2 minutes, so wait 2 minutes
#             before you run the script again.
#   reboot    with -Reboot: listening comes back after a reboot
# Kestrel is in the background for each check, and it listens with its own microphone. The tile, shortcut and restart
# checks also check in 'dumpsys audio' that Android did not silence Kestrel's recording. The script reads Kestrel's state
# with 'scripts\hook.ps1 voice-state', which never contains what Kestrel heard, and prints one line per check, also when
# a check fails with an error.
# While it runs, it grants Kestrel the microphone permission, replaces the list of enabled accessibility services, sets
# the target of the accessibility shortcut and adds Kestrel's tile to Quick Settings. Afterwards, also when it fails or is
# interrupted, it turns Kestrel off and puts back the tiles, the accessibility settings and the permission. If it cannot
# put something back, also because Ctrl+C interrupted it while it put things back, it says at the end what is left and
# how to put it back by hand. Then, after Ctrl+C, and when a check failed, it ends with exit code 1. With -Verbose, it
# names each thing that it puts back. With -Reboot it reboots the device; a phone with a PIN, pattern or password asks
# you to unlock it after the reboot. It needs PowerShell 7.3 or newer, as scripts\hook.ps1 does.
# Example: scripts\listen-check.ps1 -Serial emulator-5554 -Reboot
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [switch]$Reboot
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"
Use-KestrelToolchain
# No command reaches the device before this check.
Assert-TestDevice -Serial $Serial
$adb = Get-Adb
$service = 'dev.kestrel/dev.kestrel.app.KestrelAccessibilityService'
$tile = 'dev.kestrel/dev.kestrel.app.listen.KestrelTileService'
$isEmulator = $Serial -like 'emulator-*'
# Kestrel turns itself off when a speech model is missing, so a start that never comes may have this cause.
$modelsHint = '; are the speech models on the device? Run scripts\push-models.ps1'

# Runs a command on the device and fails when it fails. A busy device sometimes fails a command, so it is repeated.
function Invoke-Checked([string]$Command) { Invoke-SettingsCommand -Serial $Serial -Command $Command }

# Runs a command on the device once, for the commands that toggle something, which a repeat would toggle back. Fails when
# adb or the command fails.
function Invoke-Once([string]$Command) {
    # adb's error lines become part of the error message. 'Continue' keeps them from stopping the script also where
    # PowerShell applies the preference to a native command's error output (PSNativeCommandUseErrorActionPreference).
    $ErrorActionPreference = 'Continue'
    $output = @(& $adb -s $Serial shell $Command 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Could not run '$Command' on ${Serial}: $($output -join ' ')" }
}

function Invoke-Hook { (& "$PSScriptRoot\hook.ps1" -Serial $Serial @args) -join "`n" }

# Kestrel's state from the voice-state hook, or $null while Kestrel does not answer, such as while its process starts.
function Get-VoiceState {
    try { ConvertFrom-VoiceState (Invoke-Hook voice-state) } catch { $null }
}

# Waits until Kestrel is in the mode, with its voice session open, or closed for 'off'. Returns the seconds that it
# waited, or -1 after the timeout.
function Wait-Mode([string]$Mode, [int]$TimeoutSeconds) {
    $session = if ($Mode -eq 'off') { 'closed' } else { 'open' }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $state = Get-VoiceState
        if ($state -and $state.mode -eq $Mode -and $state.session -eq $session) { return [math]::Round($watch.Elapsed.TotalSeconds, 1) }
        Start-Sleep -Seconds 1
    } while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    -1
}

# Turns Kestrel off, and waits until its voice session has closed. A change to Off is also what Kestrel stores.
function Stop-Kestrel {
    Invoke-Hook voice -Send off | Out-Null
    if ((Wait-Mode off 30) -lt 0) { throw 'Kestrel did not turn off within 30 s.' }
}

# Turns Kestrel's accessibility service on, as the only enabled service, or off, and waits until Kestrel sees it.
function Set-KestrelService([bool]$On) {
    if ($On) {
        Invoke-Checked "settings put secure enabled_accessibility_services $service" | Out-Null
        Invoke-Checked 'settings put secure accessibility_enabled 1' | Out-Null
    } else {
        Invoke-Checked 'settings delete secure enabled_accessibility_services' | Out-Null
    }
    $expected = if ($On) { 'screen=on*' } else { 'screen=off*' }
    for ($i = 0; $i -lt 30; $i++) {
        if ((Invoke-Hook state) -like $expected) { return }
        Start-Sleep -Seconds 1
    }
    throw "Kestrel's accessibility service did not turn $(if ($On) { 'on' } else { 'off' }) within 30 s."
}

# The ID of Kestrel's process, or an empty string when it does not run.
function Get-KestrelProcess { ((Invoke-Checked 'pidof dev.kestrel || true') -join '').Trim() }

function Test-MicrophonePermission {
    ((Invoke-Checked 'dumpsys package dev.kestrel') -join "`n") -match 'android\.permission\.RECORD_AUDIO: granted=true'
}

# Waits until the device has started again after a reboot, which a new boot ID shows, and until Android has unlocked the
# user's storage, without which Kestrel cannot run. On a phone with a PIN, the owner has to unlock it first. It waits for
# at most 5 minutes.
function Wait-Reboot([string]$BootId) {
    # adb complains while the device is offline, and that must not stop the wait.
    $ErrorActionPreference = 'Continue'
    $deadline = (Get-Date).AddMinutes(5)
    $lockedChecks = 0
    do {
        Start-Sleep -Seconds 3
        $id = "$(& $adb -s $Serial shell cat /proc/sys/kernel/random/boot_id 2>$null)".Trim()
        $booted = $id -and $id -ne $BootId -and "$(& $adb -s $Serial shell getprop sys.boot_completed 2>$null)".Trim() -eq '1'
        $unlocked = $booted -and "$(& $adb -s $Serial shell am get-started-user-state 0 2>$null)" -match 'RUNNING_UNLOCKED'
        # Without a PIN, Android unlocks the storage a few seconds after the start.
        if ($booted -and -not $unlocked -and ++$lockedChecks -eq 2 -and -not $isEmulator) {
            Read-Host 'Unlock the phone, then press Enter' | Out-Null
        }
    } until ($unlocked -or (Get-Date) -gt $deadline)
    if (-not $unlocked) { throw "$Serial did not start again within 5 minutes of the reboot." }
}

# The line of 'dumpsys audio' that starts Kestrel's newest recording, or $null. Each recording has its own riid, so a
# newer recording has another line.
$recordingStart = '\brec start .* pack:dev\.kestrel(?:\s|$)'
function Get-RecordingStart { (@(Invoke-Checked 'dumpsys audio') -match $recordingStart) | Select-Object -Last 1 }

# Waits up to 5 s for a recording of Kestrel's that started after the line $Before, and returns whether Android let it
# record: its newest line says 'not silenced' (design spec, B5; spike S1).
function Test-Recording([string]$Before) {
    for ($i = 0; $i -lt 10; $i++) {
        if ($i -gt 0) { Start-Sleep -Milliseconds 500 }
        $dump = @(Invoke-Checked 'dumpsys audio')
        $start = ($dump -match $recordingStart) | Select-Object -Last 1
        if (-not $start -or $start -eq $Before) { continue }
        if ((Test-RecordingSilenced -Dump $dump -Package dev.kestrel) -eq $false) { return $true }
    }
    $false
}

function Invoke-TileCheck {
    Stop-Kestrel
    # Without the accessibility service, only the tap's own exemption lets Kestrel start its service from the background.
    Set-KestrelService $false
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    Invoke-Checked "cmd statusbar add-tile $tile" | Out-Null
    $listed = $false
    for ($i = 0; $i -lt 10 -and -not $listed; $i++) {
        if ($i -gt 0) { Start-Sleep -Milliseconds 500 }
        $listed = (Get-SecureSetting -Serial $Serial -Name sysui_qs_tiles) -match 'KestrelTileService'
    }
    if (-not $listed) { return 'failed: Quick Settings did not list Kestrel''s tile within 5 s' }
    # SystemUI makes the tile a moment after it lists it, and a tap before that is lost.
    Start-Sleep -Seconds 2
    $before = Get-RecordingStart
    Invoke-Once "cmd statusbar click-tile $tile"
    $on = Wait-Mode listening 60
    if ($on -lt 0) {
        return ('failed: no listening within 60 s of a tap; a tap before SystemUI made the tile is lost, and Kestrel turns ' +
            'off when a speech model is missing (scripts\push-models.ps1)')
    }
    if (-not (Test-Recording $before)) { return "failed: listening $on s after a tap, but Android silenced the microphone" }
    Invoke-Once "cmd statusbar click-tile $tile"
    if ((Wait-Mode paused 10) -lt 0) { return "failed: listening $on s after a tap, but a second tap did not pause it" }
    "ok: listening $on s after a tap, and a second tap paused it"
}

function Invoke-ShortcutCheck {
    Stop-Kestrel
    Set-KestrelService $true
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    Invoke-Checked "settings put secure accessibility_shortcut_target_service $service" | Out-Null
    # Android reads the setting a moment after it changes.
    $named = $false
    for ($i = 0; $i -lt 10 -and -not $named; $i++) {
        if ($i -gt 0) { Start-Sleep -Milliseconds 500 }
        $named = Test-ShortcutTarget -Dump (Invoke-Checked 'dumpsys accessibility') -Package dev.kestrel
    }
    if (-not $named) { return 'failed: Android did not take Kestrel as the target of the accessibility shortcut' }
    # The command's exit code is 0 even when the action did nothing, so only Kestrel's state tells.
    $before = Get-RecordingStart
    Invoke-Once 'cmd accessibility call-system-action 13'
    $on = Wait-Mode listening 60
    if ($on -lt 0) { return "failed: no listening within 60 s of the shortcut$modelsHint" }
    if (-not (Test-Recording $before)) {
        return "failed: listening $on s after the shortcut, but Android silenced the microphone"
    }
    Invoke-Once 'cmd accessibility call-system-action 13'
    if ((Wait-Mode paused 10) -lt 0) { return "failed: listening $on s after the shortcut, but a second press did not pause it" }
    "ok: listening $on s after the shortcut, and a second press paused it"
}

function Invoke-SettingsCheck {
    Stop-Kestrel
    # The accessibility service is on since the shortcut check. Without it, Android does not let Kestrel open an activity
    # from the background, and nothing tells Kestrel so.
    Invoke-Hook voice -Send toggle | Out-Null
    if ((Wait-Mode listening 60) -lt 0) { return "failed: Kestrel did not start listening before the request$modelsHint" }
    # Kestrel acts on a request only while the screen is on.
    Invoke-Checked 'input keyevent KEYCODE_WAKEUP' | Out-Null
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    Invoke-Hook voice -Send heard -Text 'Kestrel, open settings.' -Named | Out-Null
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt 10) {
        $activities = (Invoke-Checked 'dumpsys activity activities') -join "`n"
        if ($activities -match 'topResumedActivity=ActivityRecord\{[^}]* dev\.kestrel/\.app\.MainActivity ') {
            $opened = [math]::Round($watch.Elapsed.TotalSeconds, 1)
            Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
            return "ok: Kestrel's settings came to the front $opened s after 'Kestrel, open settings'"
        }
        Start-Sleep -Milliseconds 500
    }
    "failed: Kestrel's settings did not come to the front within 10 s of 'Kestrel, open settings'"
}

function Invoke-RestartCheck {
    Stop-Kestrel
    # The accessibility service is on since the shortcut check, so Android lets the hook's start run in the background.
    Invoke-Hook voice -Send toggle | Out-Null
    if ((Wait-Mode listening 60) -lt 0) { return "failed: Kestrel did not start listening before its process ended$modelsHint" }
    # Kestrel stores Listening once its voice session is open, from a coroutine a moment later; a crash before that write
    # would leave Off stored.
    Start-Sleep -Seconds 2
    $recording = Get-RecordingStart
    $before = Get-KestrelProcess
    $watch = [Diagnostics.Stopwatch]::StartNew()
    Invoke-Once 'am crash dev.kestrel'
    $after = ''
    while ($watch.Elapsed.TotalSeconds -lt 60) {
        Start-Sleep -Seconds 1
        $after = Get-KestrelProcess
        if ($after -and $after -ne $before) { break }
    }
    if (-not $after -or $after -eq $before) {
        return ("failed: Kestrel's process did not start again within 60 s of its end; Android does not restart an app that " +
            'crashed twice within 2 minutes, so wait 2 minutes before you run the script again')
    }
    if ((Wait-Mode listening 120) -lt 0) { return 'failed: Kestrel did not listen again within 120 s of its new start' }
    $again = [math]::Round($watch.Elapsed.TotalSeconds, 1)
    if (-not (Test-Recording $recording)) {
        return "failed: listening again $again s after Android ended Kestrel's process, but Android silenced the microphone"
    }
    "ok: listening again $again s after Android ended Kestrel's process"
}

function Invoke-RebootCheck {
    Stop-Kestrel
    Invoke-Hook voice -Send toggle | Out-Null
    if ((Wait-Mode listening 60) -lt 0) { return "failed: Kestrel did not start listening before the reboot$modelsHint" }
    # As in the restart check: Kestrel stores Listening a moment after its voice session opened.
    Start-Sleep -Seconds 2
    $bootId = ((Invoke-Checked 'cat /proc/sys/kernel/random/boot_id') -join '').Trim()
    $watch = [Diagnostics.Stopwatch]::StartNew()
    & $adb -s $Serial reboot | Out-Null
    Wait-Reboot $bootId
    if ((Wait-Mode listening 240) -lt 0) { return 'failed: Kestrel did not listen again within 240 s of the device''s start' }
    "ok: listening again $([math]::Round($watch.Elapsed.TotalSeconds, 1)) s after the reboot"
}

# What the script has still to put back. Each restore enters this list before the change that it undoes is made, and it
# leaves the list when it has worked. At the end, the script names the restores that are left and how to do them by hand.
$pending = [System.Collections.Generic.List[object]]::new()
$stopReason = 'the script was stopped'

function Register-Restore([string]$What, [string]$ByHand, [scriptblock]$Action) {
    $restore = [pscustomobject]@{ What = $What; ByHand = $ByHand; Action = $Action; Reason = $stopReason }
    $pending.Add($restore)
    $restore
}

# Runs the restores in order. A stop, such as Ctrl+C, that comes while a finally block runs ends that block at once, and
# PowerShell then runs the finally blocks around it in full and ignores further stops. So each restore runs in the
# finally block of the one before it, and a stop ends only the restore that is running.
function Invoke-Restore([object[]]$Steps, [int]$Index = 0) {
    if ($Index -ge $Steps.Count) { return }
    $restore = $Steps[$Index]
    try {
        Write-Verbose "Putting back $($restore.What)"
        & $restore.Action | Out-Null
        $pending.Remove($restore) | Out-Null
    } catch {
        $restore.Reason = $_.Exception.Message
    } finally {
        Invoke-Restore $Steps ($Index + 1)
    }
}

# Returns a PowerShell command that runs the given command on the device, for the warnings.
function Format-DeviceCommand([string]$Command) {
    "Run: & '$($adb -replace "'", "''")' -s $Serial shell '$($Command -replace "'", "''")'"
}

# Fails before anything changes when Kestrel's debug build is not installed.
Invoke-Hook voice-state | Out-Null

$results = [ordered]@{}
$savedAccessibility = Get-AccessibilitySettings -Serial $Serial
$tilesBefore = Get-SecureSetting -Serial $Serial -Name sysui_qs_tiles
$microphoneBefore = Test-MicrophonePermission
# The commands that put back the accessibility settings by hand. As Restore-AccessibilitySettings does, they put back the
# list of services first, and they delete the list and the switch if these were not there before.
$accessibilityByHand = @(foreach ($setting in @('enabled_accessibility_services', 'accessibility_enabled') + @($savedAccessibility.Keys) | Select-Object -Unique) {
        $value = if ($savedAccessibility.Contains($setting)) { $savedAccessibility[$setting] } else { 'null' }
        if ($value -ne 'null') {
            "settings put secure $setting '$($value -replace "'", "'\''")'"
        } elseif ($setting -in 'enabled_accessibility_services', 'accessibility_enabled') {
            "settings delete secure $setting"
        }
    }) -join '; '
$completed = $false
$failed = $false
$restoresAtEnd = @(
    # First, so that Kestrel stores Off and does not come back at the next start of its process.
    Register-Restore "Kestrel's mode Off" "Run: scripts\hook.ps1 -Serial $Serial voice -Send off" {
        Stop-Kestrel
    }
    Register-Restore 'the Quick Settings tiles' (Format-DeviceCommand "cmd statusbar remove-tile $tile") {
        Invoke-Checked "cmd statusbar remove-tile $tile" | Out-Null
        Start-Sleep -Seconds 2
        if ((Get-SecureSetting -Serial $Serial -Name sysui_qs_tiles) -cne $tilesBefore -and
            -not (Set-SecureSetting -Serial $Serial -Name sysui_qs_tiles -Value $tilesBefore)) {
            throw 'the list of tiles differs from before'
        }
    }
    Register-Restore 'the accessibility settings' (Format-DeviceCommand $accessibilityByHand) {
        if (-not (Restore-AccessibilitySettings -Serial $Serial -Saved $savedAccessibility)) { throw 'see the warning above' }
    }
    # Revoking a permission ends Kestrel's process, so this comes last.
    if (-not $microphoneBefore) {
        Register-Restore "Kestrel's microphone permission" (Format-DeviceCommand 'pm revoke dev.kestrel android.permission.RECORD_AUDIO') {
            if (Test-MicrophonePermission) { Invoke-Checked 'pm revoke dev.kestrel android.permission.RECORD_AUDIO' | Out-Null }
            if (Test-MicrophonePermission) { throw 'it is still granted' }
        }
    }
)
try {
    Invoke-Checked 'pm grant dev.kestrel android.permission.RECORD_AUDIO' | Out-Null
    # The hook's off first stops any restore in Kestrel's process and then stores Off, also while Kestrel is off, so a
    # mode that an earlier run stored cannot turn Kestrel on during a check, even if the accessibility service is on.
    Stop-Kestrel
    # Kestrel records with its own microphone, also if a hook gave it the clip microphone earlier in its process.
    Invoke-Hook voice-clip -Clear | Out-Null
    $results['tile'] = Invoke-TileCheck
    $results['shortcut'] = Invoke-ShortcutCheck
    $results['settings'] = Invoke-SettingsCheck
    $results['restart'] = Invoke-RestartCheck
    if ($Reboot) { $results['reboot'] = Invoke-RebootCheck }
    $completed = $true
} catch {
    $failed = $true
    throw
} finally {
    try {
        Invoke-Restore $restoresAtEnd
    } finally {
        # Also when a check failed with an error, so that the lines of the checks that ran are not lost.
        $results.GetEnumerator() | ForEach-Object { Write-Host ('{0,-9} {1}' -f $_.Key, $_.Value) }
        foreach ($restore in $pending) {
            Write-Warning "Could not put back $($restore.What) on $Serial ($($restore.Reason)). $($restore.ByHand)"
        }
        # After a stop, such as Ctrl+C, PowerShell would end the script with exit code 0. A failed run ends with its error.
        if (-not ($completed -or $failed) -or $pending.Reason -contains $stopReason) {
            # pwsh -File ends a stopped script that declares parameters with exit code 0, even after 'exit 1', unless the
            # script gives the host its exit code. Run from a prompt, that would close the window, so the script does it
            # only when nothing called it.
            if (@(Get-PSCallStack).Count -eq 1) { $Host.SetShouldExit(1) }
            exit 1
        }
    }
}
if ($pending.Count -gt 0) {
    Write-Warning 'The device is not as it was before. The warnings above say how to put it back.'
    exit 1
}
if (@($results.Values | Where-Object { $_ -like 'failed*' }).Count -gt 0) { exit 1 }
```

- [ ] **Step 10: Run the script tests and the analyzer**

Run: `scripts\test-scripts.ps1`
Expected: "The script tests and checks passed.": `Tests Passed: 82, Failed: 0` in each shell, and "PSScriptAnalyzer found 0 problems." (`listen-check.ps1` and `spike-s1.ps1` declare `#Requires -Version 7.3`, so the 5.1 compatibility rules skip them; the new helpers in `Kestrel.ps1` pass those rules.)

- [ ] **Step 11: Update the README**

In `README.md`, replace

```markdown
> **Status:** early development. Sub-project 1 (Foundation) is in progress. Kestrel cannot listen or run tasks yet.
```

with

```markdown
> **Status:** early development. Kestrel listens for its name and its own commands, such as "Kestrel, turn off", but it cannot run tasks yet.
```

Replace

```markdown
and `voice-test.ps1` leaves both tests out there.
```

with

```markdown
and `voice-test.ps1` leaves all three tests out there (the third is `ActivationTest`'s tile test, below).
```

Replace

```markdown
and grant Kestrel the microphone and notification permissions, which stay granted.
```

with

```markdown
and grant Kestrel the microphone and notification permissions, which stay granted. `ActivationTest` sets the targets of the accessibility shortcut and button and puts them back. Its tile test puts Kestrel's tile first in Quick Settings and opens the panel, and then removes the tile and closes the panel again. It changes the list of tiles, which no script puts back if the test's process dies, so it skips itself on a phone, and `voice-test.ps1` leaves it out there too.
```

Replace the paragraph

```markdown
Until the accessibility shortcut and the Quick Settings tile come with plan 2C-2, listening starts in a debug build through the hooks. It needs the speech models (`push-models.ps1`), the microphone permission and, for the notification, the notification permission (the setup checklist asks for both), and Kestrel must be in front or its accessibility service on, because Android lets an app in the background start a microphone service only then. While Kestrel listens, its notification shows whether it listens or is paused, and its Pause or Resume and Stop actions work. Kestrel stops recording while the screen is off or the phone locked, and goes on after the unlock.
```

with

```markdown
Kestrel's Quick Settings tile starts listening; add it when you edit the Quick Settings panel. Once Kestrel's accessibility service is on, the accessibility button and the accessibility shortcut start it too, when Android's accessibility settings name Kestrel as their target. Each of them toggles between listening and paused, or starts Kestrel when it is off. "Kestrel, turn off" or "Stop Kestrel" turns it off. While Kestrel's accessibility service is on, "Kestrel, open settings" opens this app; without the service, Android does not let Kestrel open it from the background. Listening needs the speech models (`push-models.ps1`), the microphone permission and, for the notification, the notification permission (the setup checklist asks for both). While Kestrel listens, its notification shows whether it listens or is paused, and its Pause or Resume and Stop actions work. Kestrel stops recording while the screen is off or the phone locked, and goes on after the unlock. When Android ends Kestrel's process, or the phone restarts, Kestrel comes back in the mode that it had, listening or paused, as soon as its accessibility service connects again. In a debug build the hooks start and drive listening too. A start from a hook needs Kestrel in front or its accessibility service on, because Android lets an app in the background start a microphone service only then.
```

Replace

```markdown
`voice-clip` makes Kestrel hear only such clips from its next start until `voice-clip -Clear` or the end of its process. After `voice-clip -Clear`, Kestrel uses its own microphone again from its next start.
```

with

```markdown
`voice-clip` makes Kestrel hear only such clips from its next start until `voice-clip -Clear` or the end of its process. After `voice-clip -Clear`, Kestrel uses its own microphone again from its next start.

`scripts\listen-check.ps1 -Serial $serial` checks the ways in which listening starts without a word, with Kestrel in the background: a tap on the tile while the accessibility service is off, the accessibility shortcut, "Kestrel, open settings" (a transcript that the hook sends) while the service is on, and the return of listening after Android ended Kestrel's process (`am crash`) and, with `-Reboot`, after a reboot. In the tile, shortcut and restart checks it also checks in `dumpsys audio` that Android did not silence Kestrel's recording. It needs Kestrel's debug build and the speech models, prints one line per check and never what Kestrel heard, and needs PowerShell 7.3 or newer, as `hook.ps1` does. While it runs, it grants Kestrel the microphone permission, replaces the list of enabled accessibility services, sets the target of the accessibility shortcut and adds Kestrel's tile. Afterwards, also when it fails or is interrupted, it turns Kestrel off and puts these back. It ends with exit code 1 when a check failed, when it could not put something back (it then prints the command that does it by hand), or after Ctrl+C. Android does not restart an app that crashed twice within 2 minutes, so wait 2 minutes before you run the script again.
```

Replace the bullet

```markdown
- `scripts\spike-s1.ps1 -Serial $serial` checks whether Kestrel's listening service can start and record when the accessibility service, a notification, the Quick Settings tile or a reboot starts it, also while a call rings on the lock screen, and whether Kestrel can read the location while another app is in front. It uses a stand-in listening service and a tile that only debug builds contain, and it prints whether each step worked, never the audio or the place. While it runs, it grants Kestrel the microphone, notification and location permissions, changes the accessibility services, adds a Quick Settings tile, turns the lock screen on if there is none, turns location on, lets the shell set mock locations and replaces the fused location provider with a test provider. The `boot` scenario asks Kestrel to record at its next start. On the emulator, the `ringing` scenario places calls, which end up in the call log. Afterwards, also when the script fails or is interrupted, it puts all of this back as it was before: it revokes only the permissions that Kestrel did not have, ends the calls and deletes them from the call log. Android may still keep the made-up place as the last known location, until the device gets a real location or restarts. If it cannot put something back, also because Ctrl+C interrupted it while it put things back, it says at the end what is left and the command that puts it back, and it ends with exit code 1. After Ctrl+C, it also ends with exit code 1. The `boot` scenario reboots the device. On a phone, the `ringing` scenario asks you to call the phone and to end the call. If the phone has a PIN, pattern or password, the `ringing` scenario asks you to unlock it at the end, and the `boot` scenario asks you to unlock it after the reboot.
```

with

```markdown
- `scripts\spike-s1.ps1 -Serial $serial` checks whether Kestrel can read the location while another app is in front, the one part of spike S1 that still uses the debug hooks. It prints whether a location came, never the place. While it runs, it grants Kestrel the location permissions, changes the accessibility services, turns location on, lets the shell set mock locations and replaces the fused location provider with a test provider. Afterwards, also when the script fails or is interrupted, it puts all of this back as it was before, and it revokes only the permissions that Kestrel did not have. Android may still keep the made-up place as the last known location, until the device gets a real location or restarts. If it cannot put something back, also because Ctrl+C interrupted it while it put things back, it says at the end what is left and the command that puts it back, and it ends with exit code 1. After Ctrl+C, it also ends with exit code 1.
```

- [ ] **Step 12: Update spec section 19.1**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 19.1, in the paragraph "**S1 on the emulator and the A71 (plan 1C).**", replace

```markdown
`scripts\spike-s1.ps1` repeats these checks. On the A71 (Android 13, plan 1C), every scenario
```

with

```markdown
On the A71 (Android 13, plan 1C), every scenario
```

and, at the end of the same paragraph, replace

```markdown
because the A71 has no SIM card, so it is still open on a phone.
```

with

```markdown
because the A71 has no SIM card, so it is still open on a phone. Plan 2C-2 replaced the stand-in with the real `ListeningService` and tile: `scripts\listen-check.ps1` repeats the checks of the tile, the accessibility service and the reboot with them, and `scripts\spike-s1.ps1` repeats the location check. The start from a notification action and the recording while a call rings are no longer scripted; plans 2C-3 and 2D check them again.
```

- [ ] **Step 13: Run every check of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: all JVM tests (123 in `:app`, 99 in `:core:voice`), lint in every module (no issue in any `lint-results-debug.txt`) and the audit pass.

- [ ] **Step 14: Check the emulator**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; & (Get-Adb) -s emulator-5554 emu avd name`
Expected: `kestrel-api37`, then `OK`. Anything else: stop, and start the emulator as "Before you start" says.

- [ ] **Step 15: Run the voice device tests, which also install the current debug build**

Run: `scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 60 run, 0 failed, 0 errors, 0 skipped." Kestrel's current debug build and the speech models stay on the emulator for the next step.

- [ ] **Step 16: Run `listen-check.ps1` on the emulator**

Read first what the script must put back: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; foreach ($name in 'enabled_accessibility_services', 'accessibility_shortcut_target_service', 'sysui_qs_tiles') { "$name=$(& $adb -s emulator-5554 shell settings get secure $name)" }`, and note the three lines.

Run: `pwsh -NoProfile -File scripts\listen-check.ps1 -Serial emulator-5554 -Reboot`
Expected, after about 5 minutes (the reboot takes most of it), five lines and exit code 0 (`$LASTEXITCODE` is 0):

```
tile      ok: listening <s> s after a tap, and a second tap paused it
shortcut  ok: listening <s> s after the shortcut, and a second press paused it
settings  ok: Kestrel's settings came to the front <s> s after 'Kestrel, open settings'
restart   ok: listening again <s> s after Android ended Kestrel's process
reboot    ok: listening again <s> s after the reboot
```

where each `<s>` is a number of seconds (the model loads take about 5-10 s of each). If `tile` fails with "no listening", look in `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; & (Get-Adb) -s emulator-5554 logcat -d` for a "Kestrel" warning line "Starting the listening service failed: ForegroundServiceStartNotAllowedException ...": then the tap's exemption did not let Kestrel start in the background. If logcat has no such line, the tap was probably lost: wait 2 minutes and run the script again. If a check fails with "Android silenced the microphone", print the lines of `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; & (Get-Adb) -s emulator-5554 shell dumpsys audio` that contain `pack:dev.kestrel`. If `settings` fails, print the lines of `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; & (Get-Adb) -s emulator-5554 logcat -d` that contain `dev.kestrel/.app.MainActivity`: they say whether Android blocked the start from the background. If `restart` fails with "did not start again", Android did not bring the accessibility service back after the crash: print `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; & (Get-Adb) -s emulator-5554 shell dumpsys accessibility` and look for Kestrel under "Bound services". Before you run the script again, wait 2 minutes: Android does not restart an app that crashed twice within 2 minutes, and then keeps Kestrel's accessibility service away for 30 minutes (a reinstall with `scripts\install.ps1` ends that earlier). Never print a transcript to find out.

- [ ] **Step 17: Check that the emulator is as it was**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; foreach ($name in 'enabled_accessibility_services', 'accessibility_shortcut_target_service', 'sysui_qs_tiles') { "$name=$(& $adb -s emulator-5554 shell settings get secure $name)" }; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 voice-state`
Expected: the same three lines as in Step 16 (on a fresh kestrel-api37 `enabled_accessibility_services=null` and `accessibility_shortcut_target_service=null`, and `sysui_qs_tiles` without `KestrelTileService`), and a state that starts with `mode=off` and contains `session=closed`.

- [ ] **Step 18: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 19: Commit**

```powershell
git add app/src/debug/kotlin/dev/kestrel/app/debug/spike/SpikeListening.kt app/src/debug/kotlin/dev/kestrel/app/debug/spike/SpikeHooks.kt app/src/debug/AndroidManifest.xml scripts/lib/Kestrel.ps1 scripts/tests/Kestrel.Tests.ps1 scripts/hook.ps1 scripts/spike-s1.ps1 scripts/spike-s4.ps1 scripts/listen-check.ps1 README.md docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat: replace spike S1's stand-ins with the real start paths, and check them with listen-check.ps1" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

(`git rm` in Step 5 has already staged the two deleted files.)

---

## Self-review

**Spec and design coverage.**

| Design decision | Where |
|---|---|
| D1 `Event.Start(paused)` that changes nothing while Kestrel is on; "Kestrel, turn off" and "switch off" as `TurnOff` (also with the name at the end, as "Wake up, Kestrel"), while "turn off the wifi" stays a task; `Request.OpenSettings` for "open settings", "open your settings" and "Kestrel settings"; "never mind", "forget it" and "nothing" after "Yes?" as `Stop(named = true)` (also "Never mind, Kestrel."), while "forget [something else]" stays a task; `Effect.OpenSettings` while listening, paused or waiting for a task (ending the wait), ignored while a task runs; the controller starts `MainActivity` with `FLAG_ACTIVITY_NEW_TASK` from the application context, and reports a failure by class and shows it on the bar; JVM tests; spec 8.3, 8.5 and Appendix A | Task 1; the device check of "open settings" in Task 5's `listen-check.ps1` |
| D2 `AccessibilityButtonCallback` registered in `onServiceConnected`, `onClicked` sends `Event.Toggle`, unregistered in `disconnect()`; `ActivationTest` in `dev.kestrel.app.voice` with `ScreenServiceRule`'s non-suppressing `UiAutomation`, Kestrel in the background, the four secure settings saved and restored in a `finally`, the shortcut twice (listening, paused), then the button with the button and gesture targets and mode 0, `performGlobalAction` asserted true, listening again; the clip microphone; Kestrel off at the end; spec 7.2's row names the button and the shortcut | Task 2 |
| D3 `KestrelTileService` in `dev.kestrel.app.listen`: exported with `BIND_QUICK_SETTINGS_TILE`, label "Kestrel", a monochrome vector icon, `TOGGLEABLE_TILE`; follows the mode on the main thread from `onStartListening` to `onStopListening` (active only while listening; subtitle "Listening", "Paused" or "Off"); `onClick` sends `Event.Toggle`; `SpikeTileService` and its manifest entry deleted; device test with the tile added first, a wait until SystemUI shows it as "Off", two clicks with the tile's state and subtitle read from SystemUI's dump after each, and the tile removed in a `finally`, on emulators only; Kestrel off at the end | Task 3 |
| D4 `SettingKeys.LISTENING` with a `@Serializable` value (default Off), written in `graph.scope` when the mode changes (Listening or Paused once a voice session is open, Off at once), noted where the controller performs each `Feedback.ModeChanged`; read once per process when the accessibility service connects, only if Kestrel has not been on in the process, also not during the read; the restore stores Off and then sends `Event.Start()` or `Event.Start(paused = true)`; tasks never resume; a failure never repeats at every start, also a start that ends the process before the session is open; JVM tests with a fake source for each stored mode (Listening, Paused, Off), once per process, not after Kestrel was on, not when the owner chose during the read (also in one run), not after the hook's `off`, the Off before the start, an Off that replaces an earlier process's mode, and the writes; the tests' rule and the hook's `off` store Off | Task 4 |
| D5 `SpikeListeningService`, `SpikeStartup` (and its start-up meta-data), the `LISTEN`, `LISTEN_NOTIFICATION` and `LISTEN_AFTER_BOOT` hooks and `SpikeListening.start` deleted; `LOCATION`, `SPIKE_RESULTS`, `FILES`, `WATCH` kept; the debug manifest's stand-in entries and permissions removed; `spike-s1.ps1` keeps only the location check and names `listen-check.ps1`; `listen-check.ps1 -Serial [-Reboot]` with one line per check (tile through `add-tile`/`click-tile`, shortcut through action 13, "open settings", the process's end through `am crash`, the reboot), the state through `hook.ps1 voice-state`, everything put back and Kestrel off, refusing a device that `Assert-TestDevice` refuses; Pester tests for its helpers in both shells; README; spec 19.1; the run on emulator-5554 | Task 5 (and Task 3 for the spike's tile) |
| Final review of 2C-1, "For plan 2C-2": item 1 (button, shortcut, tile, the stand-ins, `spike-s1.ps1`), item 6 (resuming after a restart, tasks never), item 14 in part ("never mind" and similar answers, "Kestrel, turn off" and Appendix A's "Kestrel, open settings" no longer read as tasks). Items 2-5, 8 and 12 go to plan 2C-3 as the design says; items 9, 10, 11, 15, 16 and the rest of 14 are listed with their plans in "Not in this plan"; item 13 was done in `648f4f7` | Tasks 1-5; "Not in this plan" |
| Review 1 of this plan: I-1, I-2, M-1 to M-7, N-1 to N-4 | Tasks 1-5, "Not in this plan" |
| Review 3 of this plan: M-1 (the texts on what `listen-check.ps1` covers, spec 19.1 at the end of S1's paragraph, the two "Not in this plan" entries, `Test-RecordingSilenced` with 3 Pester tests, used by the tile, shortcut and restart checks), M-2 (the hook's `off` before the spikes turn the accessibility service on), M-3 (the tile check waits for `sysui_qs_tiles`), M-4 (every device command dot-sources `Kestrel.ps1`), M-5 (the README's status), N-1 to N-6 (N-4: the hook's device test runs and fails before the change) | Tasks 3, 4 and 5; "Not in this plan" |
| Review 2 of this plan: M-A (the mode noted in `perform`, `lastMode` nullable, two new tests), M-B (`skipRestore()` before the hook's Off, one new test), M-C (the tile test waits until SystemUI shows the tile as "Off"), M-D (`remember` after `followSafely`; the 2C-3 entry on crashes after the session opened and stops from Android; the spec bullet), N-A to N-D, and C-1 to C-6 (see below) | Tasks 1, 3, 4 and 5; "Not in this plan"; "Threads" |

**The points that review 2 could not read (C-1 to C-6).**
- C-1: `Invoke-RestartCheck` and `Invoke-RebootCheck` wait until `voice-state` says `mode=listening` and `session=open`, and then 2 s more, before `am crash` or the reboot, because Kestrel stores Listening from a coroutine a moment after the session opened.
- C-2: `listen-check.ps1`'s first command, and the first command of every check, is `Stop-Kestrel`, which sends the hook's `off`; since M-B the hook calls `Listening.skipRestore()` on the controller's thread before it stores Off and sends `TurnOff`, so a restore that the accessibility service starts at the same time cannot turn Kestrel on afterwards.
- C-3: `VoiceHookTest` already imports `runBlocking`, `Mode` and `assertEquals`; the plan adds `SettingKeys` and `ListeningSetting`. `VoiceHooks.event` is called only from `VoiceHooks.run`, a `suspend` function that `TestHooks.answer` calls inside the receiver's coroutine.
- C-4: Task 4 Step 11 runs `scripts\test-scripts.ps1` after the change to `hook.ps1` (Pester 70 per shell then).
- C-5: in `voice-test.ps1`, the run's check loops over `$emulatorOnly` and finds each test by its method name, `notClass` joins the list with commas, and the failure message names the test; the header now says "these three tests". Both replaced texts exist once in the script at `648f4f7`. The README's sentence on the two older tests ("leaves both tests out there") is followed by the plan's new sentence on the tile's test.
- C-6: `cmd statusbar help` on kestrel-api37 lists `set-tiles LIST-OF-TILES`, and `sysui_qs_tiles` holds entries such as `custom(com.google.android.gms/.nearby.sharing.SharingTileService)`, the form of `TILE_SPEC`. If the tile still does not appear, the test writes the list itself (N-B).

**Placeholder scan.** No "TBD", "TODO", "similar to Task N" or step without its code or command. Every modified file shows the exact text that it replaces, or the whole new file. A script applied all 63 replacements of the plan in order to copies of the files at `648f4f7` (the later tasks' replacements to the earlier tasks' new text); each old text was found exactly once. The PowerShell of Tasks 3-5 was parsed, and on copies of the changed scripts (`voice-test.ps1`, `hook.ps1` and `spike-s4.ps1` after Tasks 3-5, `listen-check.ps1`, the new `spike-s1.ps1`, `Kestrel.ps1` with its helpers) PSScriptAnalyzer with `scripts\PSScriptAnalyzerSettings.psd1` found no problem, and the tests of `Kestrel.Tests.ps1` with the 12 new ones (44) passed in Windows PowerShell 5.1 and in PowerShell 7. The scripts are ASCII only.

**Type consistency.** `Event.Start(paused: Boolean = false)` is used by `Rules.next`, `RulesTest` and `Listening.onAccessibilityServiceConnected`. `Effect.OpenSettings` and `Request.OpenSettings` are `data object`s, handled in `Rules.whileIdle`, `Rules.whileAwaitingTask` and `Listening.performUnguarded`. `Listening(scope, thread, sessions, services, bar, report, openSettings: () -> Unit, memory: ModeMemory, microphones)` matches `AppGraph` (named arguments) and `ListeningTest.listening(...)`. `ModeMemory.read(): Mode` and `write(mode: Mode)` (both `suspend`) match `AppGraph`'s object and `FakeMemory` (with `reading: CompletableDeferred<Unit>?`). `Listening.noteMode(mode: Mode)` is called from `performUnguarded` for each `Feedback.ModeChanged`, and `remember(mode: Mode)` from `noteMode` and from `open()`. `internal suspend fun Listening.skipRestore()` is called by `VoiceHooks.event` and `ListeningTest`. `ListeningSetting(mode: Mode = Mode.OFF)` matches `SettingKeys.LISTENING`, the memory's `read`/`write`, `ScreenServiceRule.before()`, `VoiceHooks.event` and `VoiceHookTest`. `Listening.onAccessibilityServiceConnected()` is called by `KestrelAccessibilityService.onServiceConnected()`. `ListeningTexts.CANNOT_OPEN_SETTINGS` is used by `Listening` and `ListeningTest`. `ActivationTest`'s `press(action)`, `awaitListening()`, `awaitState(...)`, `secure(name)` and `putSecure(name, value)` from Task 2 are used by Task 3's test, which adds `awaitTile(subtitle: String, state: Int, timeoutMillis: Long = 5_000)`. The tile's component `dev.kestrel/dev.kestrel.app.listen.KestrelTileService`, and its spec `custom(dev.kestrel/.app.listen.KestrelTileService)`, are the same in the manifest, `ActivationTest`, `voice-test.ps1`'s list (by the method `theTileTogglesListening`) and `listen-check.ps1`. `ConvertFrom-VoiceState`, `Test-ShortcutTarget` and `Test-RecordingSilenced` are defined in Task 5 Step 3 and used in Steps 1 and 9; on the emulator's real `dumpsys audio`, `Test-RecordingSilenced` returned `$false` for `dev.kestrel`.

**Counts.** `RequestsTest` 18 → 22, `RulesTest` 31 → 36, `ListeningTest` 34 → 36 (Task 1) → 49 (Task 4); `:app` JVM 99 → 110 (Task 1) → 123 (Task 4); `:core:voice` JVM stays 99; voice device tests 57 → 58 (Task 2) → 59 (Task 3) → 60 (Task 4, `VoiceHookTest` 6 → 7; also in Task 5); Pester 70 per shell until Task 5, then 82.

**Decisions this plan made beyond the design.**
- `:app` applies the kotlinx.serialization compiler plugin, which `@Serializable` needs; the `core` modules apply it the same way, and it adds no dependency. `ListeningSetting` stores `Mode` itself; a value that this version cannot read, such as a mode that plan 2D adds, reads as Off through `SettingsStore`'s default.
- The mode is stored through a `ModeMemory` seam, so the JVM tests use a fake. The controller notes the mode where it performs `Effect.Show(Feedback.ModeChanged)`, which every change of mode gives, in order, also the changes that the Orchestrator makes in one run (review 2, M-A). Listening and Paused are stored only once a voice session is open and its microphone has started, Off at once (review 1, I-2; review 2, M-D), and the restore stores Off before it starts Kestrel: a start that fails or ends Kestrel's process before then never leaves Listening stored. Nothing is written before the first change of mode in a process, so the Off that every process starts with never replaces the stored mode before the restore has read it; the first Off after a change is always written, also over a Listening that an earlier process stored. "Once per process" means that the first connection of the accessibility service decides; the restore does nothing once Kestrel has been on in the process, also when the owner turned it on and off while the stored mode was read (review 1, M-3).
- Spike S1's start from a notification action and its recording while a call rings are no longer scripted once the stand-ins go; "Not in this plan" gives them to plans 2C-3 and 2D (review 3, M-1). `spike-s1.ps1` and `spike-s4.ps1` send the hook's `off` before they turn the accessibility service on (review 3, M-2).
- `ScreenServiceRule.before()` and the hook's `off` store Off directly (review 1, I-1), and the hook first stops any restore with `Listening.skipRestore()` (review 2, M-B), so no device test and no check of `listen-check.ps1` depends on how an earlier run ended, and `voice -Send off` turns Kestrel off for good also in a new process.
- After "Yes?", the answer is read as the words after the name (plan 2B's rule), so "Turn off." and "Open settings." as an answer turn Kestrel off and open the settings. "nevermind" as one word counts as "never mind", and "Never mind, Kestrel." as an answer counts too (review 2, N-C). "Turn off, Kestrel" counts as "Kestrel, turn off" (review 1, N-3), but "Open settings, Kestrel" does not: without the name first it stays an instant command. "Kestrel, open the settings" stays a task, because the owner may mean Android's settings.
- `Effect.OpenSettings` uses the launcher's intent with `FLAG_ACTIVITY_NEW_TASK`, as `ListeningService`'s notification does, so that a second request brings Kestrel's task to the front instead of stacking `MainActivity`. Android allows it from the background only while the accessibility service is bound; `listen-check.ps1` checks it, and the README says so.
- Spec section 8.5 gains a bullet for the restore, because no section described it; Appendix A's "Kestrel, open settings" row also names "Kestrel settings"; spec 7.2's row for the accessibility service names the button and the shortcut.
- `ActivationTest` writes the secure settings through `executeShellCommandRw("sh")`, so that it can write an empty value back. Its shortcut-and-button test is not limited to emulators: it puts back everything that it changes, and `connected-test.ps1` puts the accessibility settings back again after any run. On a phone with three-button navigation, the button part relies on `accessibility_button_targets` with the mode 0 (research R14), which plan 2D's run on the A71 checks. The tile's test is limited to emulators, because no script puts the list of tiles back (review 1, M-2).
- The tile's test removes a Kestrel tile that an earlier run left from the list that it saves (review 2, N-D), puts Kestrel's tile first with `cmd statusbar set-tiles` (or writes the list itself if SystemUI does not take it, N-B), opens Quick Settings so that SystemUI asks the tile for its look, waits up to 15 s until SystemUI's dump shows the tile as "Off" before the first click (review 2, M-C), and reads the look after each click (review 1, M-4). The shortcut's test and script wait until `dumpsys accessibility` names Kestrel, and the button's test until `performGlobalAction` returns true, so only one call presses the button.
- `listen-check.ps1` declares `#Requires -Version 7.3` because it calls `hook.ps1`, which needs it; its three device-free helpers are in `scripts\lib\Kestrel.ps1` and run in both shells. It runs the tile check with the accessibility service off, so that only the tap's exemption allows the start, as spike S1's tile scenario did. Every check starts with the hook's `off`. It runs the commands that toggle something once, through adb, instead of through `Invoke-SettingsCommand`, which repeats a failed command. Its tile check taps 2 s after `sysui_qs_tiles` names the tile (review 3, M-3), and its tile, shortcut and restart checks fail when `dumpsys audio` shows Kestrel's new recording as silenced (review 3, M-1), as spike S1 checked. Its results print also when a check fails with an error, and it gives Kestrel back its own microphone first. It ends Kestrel's process with `am crash`, which needs no debug hook, 2 s after the voice session opened, and asks for 2 minutes between two runs, because Android counts a second crash within 2 minutes as crashing too often (review 1, M-1).

**Not verified while writing (no Gradle run, no device change).** That the Kotlin compiles and lint stays clean (every API name was checked in `android.jar` of `platforms\android-37.0` with `javap`: `AccessibilityButtonController.registerAccessibilityButtonCallback`/`unregisterAccessibilityButtonCallback`, `AccessibilityButtonCallback.onClicked`, `AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_BUTTON = 11` and `GLOBAL_ACTION_ACCESSIBILITY_SHORTCUT = 13`, `TileService.getQsTile`/`onStartListening`/`onStopListening`/`onClick`, `Tile.setState`/`setSubtitle`/`updateTile`, `Tile.STATE_ACTIVE`/`STATE_INACTIVE`, `META_DATA_TOGGLEABLE_TILE = "android.service.quicksettings.TOGGLEABLE_TILE"`, `UiAutomation.executeShellCommandRw(String): ParcelFileDescriptor[]` and `performGlobalAction(int): boolean`). With read-only commands on emulator-5554 (after `emu avd name` printed `kestrel-api37`): `dumpsys accessibility` prints `shortcut key:{...}`; SystemUI's dump prints `CustomTile: QSTile$BooleanState[spec=custom(...),...,secondaryLabel=...,...,state=N,...]`; `dumpsys activity activities` prints `topResumedActivity=ActivityRecord{... u0 <package>/<activity> t<n>}`; `cmd statusbar` offers `set-tiles`, `expand-settings` and `collapse`. Not verified: that SystemUI asks Kestrel's tile for its look while Quick Settings is open and the tile is first; that Android binds Kestrel's accessibility service again within 60 s of a first `am crash`; that Kestrel's `MainActivity` shows as `dev.kestrel/.app.MainActivity` in the dump.
