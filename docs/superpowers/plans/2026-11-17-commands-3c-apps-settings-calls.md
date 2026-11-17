# Instant Commands 3C: Global Actions, Apps, Phone Settings, Calls, the Corpus and the A71 Session Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the owner drive the phone itself by voice, as with Voice Access: the global actions, "open X" and "open work X", the previous app, Circle to Search where the phone has it, Wi-Fi and Bluetooth, volumes, mute, silence, media keys, magnification, calls by number and by name with an announcement and a countdown, and "copy this phone number"; measure the command accuracy with the voice corpus; and prepare the A71 session with the owner, so that every "Yes" row of Appendix A works on the emulator.

**Architecture:** Plan 3A left three stubs that this plan replaces whole: `GrammarPhone` (the phone phrases of `CommandParser`, with `HelpPhone` for the command list), `SystemControls` in `:core:screen` (starting intents and launcher activities, system actions, the previous app and panning the magnification), and the phone part `phoneCommands(environment)` of the executor `InstantCommands`. The phone part builds its own Android services from `CommandEnvironment.context` and splits its work into `AppCommands` (the launcher API and the work profile), `DeviceCommands` (radios, audio, media keys, magnification), `CallCommands` (the default phone app, the countdown of `CommandConversation`, contacts through `ContactsReader`) and `NumberCopier`; the pure rules (`PhoneNumbers`, `ContactMatcher`, `Commands.sameAction`) live in `:core:commands`. The voice corpus gets instant-command, conversation and dictation lines and scenes of screen labels, and the evaluation hook answers whether a transcript leads to the same command.

**Tech Stack:** Kotlin 2.4.21 (the Kotlin standard library only in `:core:commands`, JUnit 4 for its tests), kotlinx.coroutines 1.11.0 (kotlinx-coroutines-test for the JVM tests), Android's `AccessibilityService` (global actions, `MagnificationController`, `getSystemActions`), `LauncherApps` (with `getLauncherUserInfo` from API 35), `AudioManager`, `BluetoothManager`, `TelecomManager`, `TelephonyManager`, `ContactsContract`, `ClipboardManager` at compileSdk 37, JUnit 4 and AndroidX Test for the device tests on the `kestrel-api37` emulator through `scripts\command-test.ps1`, Pester 6.2.0 and PSScriptAnalyzer 1.25.0 for the scripts.

## Global Constraints

- Package and application ID `dev.kestrel`; licence GPL-3.0-or-later; no new dependency that ships in the app (`:core:commands` uses only the Kotlin standard library; its tests use JUnit).
- `minSdk 33`, `targetSdk 37`, `compileSdk 37`. Every Android call that the A71 (Android 13) lacks has a check of the API level and a fallback.
- No dependency-injection framework; `AppGraph` wires by hand.
- New permissions: only `READ_CONTACTS` (runtime, asked at first use, plan 3C). Kestrel never requests `CALL_PHONE` or `ACCESS_NOTIFICATION_POLICY`, nor any permission on spec 13.6's "never" list.
- Never log audio, transcripts, replies, questions, boost words, app labels, screen text, contact names, phone numbers, field text, typed text, exception messages or an exit record's description (section 13.5); `toString` of anything that carries the owner's words or the screen's text prints only lengths, counts or kinds.
- Plans 3B and 3C create or change only the files of their own list in "Shared files and anchors" of the design (`.superpowers/sdd/plan-3-design.md`), at the places that it gives; any other file needs the controller. This plan changes only plan 3C's files there, a shared file only at the marker or the exact lines that the design gives, and in addition the three places that the controller allowed: spec 13.6's `<queries>` bullet (Task 1), the README's sentence of the corpus's size (Task 7), and spec 8.7's new bullet on emergency calls after `- After the call, the previous listening mode returns.` (Task 6).
- Test only on `kestrel-api37` (emulator-5554), and check it in the same command as each device command; never on the S23+ or any other AVD, and no command to any connected phone; the A71 only in the session with the owner. Gradle only through `scripts\gradle.ps1` (stopped with `--stop` at the end of each task); device tests only through `scripts\command-test.ps1`, `scripts\voice-test.ps1`, `scripts\connected-test.ps1` (with `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`) or `scripts\listen-check.ps1`, as each task says; installs only through `scripts\install.ps1`. The command device tests grant what they need and never revoke a permission; `command-test.ps1` restores every setting, radio, volume, ringer mode, magnification, tile, lock-screen setting, contact, call-log entry, user profile, file and permission that they change, and the APKs stay installed. A reboot of the emulator is a cold boot.
- Scripts run on Windows PowerShell 5.1 and PowerShell 7; `scripts\test-scripts.ps1` passes after every script change.
- The repository uses LF line endings.
- The plan file is named with the day it is written (`Get-Date -Format yyyy-MM-dd`), and it is frozen once committed; a later difference goes into `.superpowers/sdd/progress.md`.

## Before you start

- Plans 3A and 3B are complete; their last commits are in `.superpowers/sdd/progress.md`. This plan was written against the code as plan 3A leaves it, and against the names that the design fixes for plan 3B. Their "Changes from the plan" entries that touch this plan's files must be kept. This plan never replaces a line that plan 3B owns.
- The counts at the start of this plan are the ones after plan 3B: `:core:commands` 88 tests, `:app` JVM 323 (among them `WordErrorsTest` 5), `:core:screen` 55, `:core:voice` 140, `:core:privacy` 48 and `:core:safety` 2; the command device tests 59 (the package `dev.kestrel.app.commands`, through `command-test.ps1`), the voice device tests 83, `connected-test.ps1` 179 (among them `SettingsAppTest` 31 and `MainActivityTest` 6, with the 59 command tests skipped there), and Pester 144 per shell. Each task gives the totals after it and names the classes whose counts change; this plan changes no count of `:core:screen`, `:core:voice`, `:core:privacy`, `:core:safety` or the voice device tests. Before each task, compare the counts with the ones that the earlier tasks left. If a count differs, stop and ask the controller.
- Before each task, check that every text that the task replaces exists, and exists once, in its file. If a text is missing, stop and ask the controller.
- What this plan builds on (plan 3A): the model in `Command.kt`, `PhoneCommandModel.kt` (the phone variants are nested in `sealed interface PhoneCommand : Command`: `PhoneCommand.Global(action: GlobalCommand)`, `OpenApp(name, work)`, `PreviousApp`, `CircleToSearch`, `Radio(radio: RadioKind, on)`, `Volume(stream: StreamKind?, up)`, `Mute(stream: StreamKind?, on)`, `Silence`, `Media(play)`, `Magnify(action: MagnifyAction)`, `Pan(direction: Direction)`, `Call(who: CallTarget)`, `CopyPhoneNumber`; `CallTarget.Number(digits)` and `CallTarget.Name(name, kind)`) and `TextCommandModel.kt`, all frozen; `CommandParser.parse(text)`, which asks `GrammarScreen.fixed`, `GrammarText.fixed`, `GrammarPhone.fixed`, then `GrammarPhone.targeted`, `GrammarText.targeted`, `GrammarScreen.targeted`, and reads a `Command.Bare` again without polite words; `cutWords(text, from, to)` (internal); `SpokenNumbers.answer(text)`; `CommandHelp.groups` with the stub `HelpPhone`; `CommandHelpTest`, which parses every example of the command list; `Normalizer`, `LabelMatcher.decide`, `MatchCandidate`, `MatchDecision`, `Strictness`, `MatchRules`; `CommandResult(outcome, say, show, repeatable, passOn, acted)` and `Outcome`; `CommandVocabulary.BOOST`. In `:core:screen`: `ScreenSession.actions` (`global(GlobalAction)`, `tap(key)`, `zoom(scale, x, y)`, `resetZoom()`, `zoomScale`, `clickLabelled(label, packages, ifOff)` from plan 2D), `ScreenSession.system` (the stub `SystemControls(service, snapshots, tracker)`), `ScreenSession.snapshots`, `screenBounds`, `hideFeedback()`, `snapshotBuilds`, `snapshotMillis`, `GlobalAction.ALL_APPS` and `DISMISS_SHADE`. In `:app`: `CommandPart`, `CommandConversation` (`say`, `ask`, `keep`, `countdown`), `CommandEnvironment(context, screen, options, targets, scope, openSettings, report)`, `InstantCommands` (text part, phone part, screen part, then the fallback to a bare label; without the accessibility service only `Volume`, `Mute`, `Silence` and `Media` reach the parts; its `TouchCancels` runs the touch watcher during `ask` and `countdown`), `ScreenCommands` with `choose(screen, choices, conversation): Choice`, `ScreenTargets` with `names(snapshot, element)` and `chosen(element)`, `CommandTexts.WHICH_ONE`, `SettingsPage`, `MainActivity.ASKABLE` (with `READ_CONTACTS`), `BoostWordBudget.combine(fixed, screen, vocabulary)` and `screenNames(snapshot)`; the device-test helpers `CommandTests` (`start()`, `stop()`, `send(text)`, `say(text)`, `ask(text)`, `answer(text)`, `next()`, `awaitSaid(text)`, `asked`, `spoken`, `graph`), `TextSessions`, `ScreenServiceRule` and `TestFinger`; `scripts\command-test.ps1 -Serial <serial> [-Class <name>[,<name>...]]`, which already restores everything that this plan's tests change; the markers `    <!-- Plan 3C: permissions -->` (app manifest), `        <!-- Plan 3C: phone fixtures -->` (test app manifest) and `<!-- Plan 3C: apps, phone settings and calls -->` (README). From plan 3B, by the design's names: `dev.kestrel.commands.edit.Dictation.insert(state: TextState, spoken: String, password: Boolean): List<DictationPart>`, `TextState(text, selectionStart, selectionEnd)`, `DictationPart.Text(change: EditStep.Change)` and `DictationPart.NewLine`, `EditStep.Change(start, end, replacement, selectionStart, selectionEnd)`; `GrammarText`, which parses every text phrase of design D8 exactly as D8 lists it, and the text part, which hands a text command back to the bare labels when no field has the focus.
- Run Gradle only through `scripts\gradle.ps1`, and stop it with `scripts\gradle.ps1 --stop` at the end of each task.
- Every device command of this plan starts with a check of the emulator, in the same command: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' };` and then the command itself. If the check throws, or no emulator runs, start the emulator with `scripts\start-emulator.ps1`, check that the serial that it prints belongs to `kestrel-api37`, and use that serial in place of `emulator-5554`. The device scripts also refuse other devices themselves (`Assert-TestDevice`), and `command-test.ps1` refuses every device that is not an emulator. Never send a command to a connected phone. A step that would reboot the emulator closes it and starts it from a cold boot instead.
- Run the command device tests only through `scripts\command-test.ps1 -Serial emulator-5554` (with `-Class` for one class), and the voice device tests only through `scripts\voice-test.ps1 -Serial emulator-5554`.
- Before each task, run `git status --short`. If a file that the task changes (its **Files** list) already shows changes that are not the task's own, stop and ask the controller, because the task's `git add` would commit them too.
- Every commit message ends with the line `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.

## Not in this plan

Plan 3A (done before this plan): the command core, the screen commands, help and the options, the Orchestrator's command work, the countdown and its holds, `command-test.ps1`, and the stubs and markers that this plan fills. This plan changes none of plan 3A's files except its stubs (`GrammarPhone.kt`, `HelpPhone.kt`, `SystemControls.kt`, `PhoneCommands.kt`), the values in `MatchRules.kt` only if the controller decides so after the corpus run, `scripts/hook.ps1` (whose comment plan 3A also changed; the design lists it for this plan), and the shared files at their markers or at the lines that the design and the controller name.

Plan 3B (done before this plan): dictation and text editing, `GrammarText`, `HelpText`, `TextEditor`, the text part, the text fixtures, and spec 9.3's Type row and 9.7. This plan uses only the names that the design fixes for it.

Sub-project 5: "Kestrel, delete history" by voice (this plan moves it there in the spec), tasks, the agent, contact names as boost words, and spec 14.1's fallback for a touch watcher that misses real touches. Later: choosing the SIM by voice, calls in other apps, `CALL_PHONE`, the true silent mode, Circle to Search through a gesture, and apps of profiles other than a known work profile.

## Threads

| Call | Thread | Notes |
|---|---|---|
| `GrammarPhone`, `PhoneNumbers`, `ContactMatcher`, `Commands.sameAction` | The Orchestrator's coroutine (parsing), or the hook's `Dispatchers.Default` | Pure; they never touch Android |
| `PhoneCommands.run` and its parts (`AppCommands`, `DeviceCommands`, `CallCommands`, `NumberCopier`, `EmergencyGuard`) | The command's job (`Dispatchers.IO`) | Binder calls to `LauncherApps`, `AudioManager`, `TelecomManager`, `TelephonyManager`, the contacts provider and the clipboard run here |
| `SystemControls` | The command's job | Like `ScreenActions`: blocking binder calls on the caller's dispatcher; `setMagnificationConfig` switches to the main thread |
| `CommandConversation.say`, `ask`, `countdown` | Called from the command's job | `countdown` returns false after a touch or 30 s of holds; a voice cancel cancels the job, so it never returns then |
| `ContactsReader.contacts()` | The command's job; `@Synchronized` | The `ContentObserver` runs on the main looper and only sets a `@Volatile` flag |
| `EvalHooks` (`VOICE_EVAL` with `group`) | The hook's coroutine; the comparison on `Dispatchers.Default` | `screenLabels` reads one snapshot of the screen in front |

## File structure

| File | Responsibility |
|---|---|
| `core/screen/src/main/kotlin/dev/kestrel/screen/act/SystemControls.kt` | Task 1 (replaces 3A's stub whole): starting intents and launcher activities, the previous app, system actions, panning the magnification, the magnified centre |
| `core/commands/src/main/kotlin/dev/kestrel/commands/GrammarPhone.kt`, `HelpPhone.kt` | Task 1 (replaces 3A's stubs whole); Tasks 2, 3, 4 and 6 add their phrases and examples |
| `core/commands/src/test/kotlin/dev/kestrel/commands/GrammarPhoneTest.kt` | Tasks 1 to 4 and 6: the phone phrases in several phrasings, and the phrases that stay with other groups or bare |
| `app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommands.kt`, `PhoneCommandTexts.kt` | Task 1 (replaces 3A's stub whole); Tasks 2 to 4 and 6: the phone part and its texts |
| `app/src/main/kotlin/dev/kestrel/app/commands/AppCommands.kt` | Task 2: "open X", the work profile, and the spoken "Which one?" among names, which `CallCommands` also uses |
| `app/src/main/kotlin/dev/kestrel/app/commands/DeviceCommands.kt` | Task 3: Wi-Fi and Bluetooth; Task 4: volumes, mute, silence, media keys and magnification |
| `core/commands/src/main/kotlin/dev/kestrel/commands/phone/PhoneNumbers.kt`, `Contacts.kt`, `ContactMatcher.kt` | Task 5: spoken and shown phone numbers, the contact model, the contact and number matcher |
| `app/src/main/kotlin/dev/kestrel/app/commands/ContactsAccess.kt`, `ContactsReader.kt` | Task 5: `READ_CONTACTS`, and the contacts kept in memory, watched after the first read |
| `app/src/main/kotlin/dev/kestrel/app/commands/CallCommands.kt` | Task 6: calls, the phone app's call button, "copy this phone number" |
| `app/src/main/AndroidManifest.xml` | Task 1: the home app's visibility; Task 5: `READ_CONTACTS` (both at the 3C marker) |
| `app/src/test/kotlin/dev/kestrel/app/commands/PhoneAppsTest.kt`, `PhoneDevicesTest.kt`, `ContactsReaderTest.kt`, `PhoneCallsTest.kt` | Tasks 2 to 6: the JVM tests of the phone part's decisions |
| `app/src/androidTest/kotlin/dev/kestrel/app/commands/PhoneTests.kt`, `SystemCommandsTest.kt`, `AppCommandsTest.kt`, `DeviceCommandsTest.kt`, `CallCommandsTest.kt` | Tasks 1 to 4 and 6: the command device tests and their helper |
| `testapp/src/main/kotlin/dev/kestrel/testapp/MediaActivity.kt`, `PhoneNumberActivity.kt`, `testapp/src/main/res/values/ids_phone.xml`, `testapp/src/main/AndroidManifest.xml` | Task 4: the media player; Task 6: the phone numbers and their IDs (both activities at the 3C marker) |
| `core/commands/src/main/kotlin/dev/kestrel/commands/SameAction.kt`, `text/WordErrors.kt` (moved from `:app`), their tests and `CorpusTest.kt` | Task 7: whether a transcript leads to the same action, the word errors, and the corpus's command lines |
| `corpus/commands.txt`, `corpus/scenes.txt`, `scripts/lib/Corpus.ps1`, `scripts/tests/Corpus.Tests.ps1`, `scripts/voice-eval.ps1`, `scripts/hook.ps1`, `app/src/debug/kotlin/dev/kestrel/app/debug/EvalHooks.kt` | Task 7: the corpus's instant commands and scenes, the command accuracy and its measurement |
| `scripts/a71-commands.ps1`, `scripts/tests/A71Commands.Tests.ps1`, `app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt`, `scripts/hook.ps1` | Task 8: the A71 session and its two hooks |
| `docs/superpowers/specs/2026-11-12-kestrel-design.md`, `README.md` | The spec lines of design D18's 3C part and spec 13.6's `<queries>` bullet, each in the task that changes the behaviour; the README at the 3C marker and the coverage table in Task 8, and the README's sentence of the corpus's size in Task 7 |

---

### Task 1: System controls, the global actions, "open apps", the previous app and Circle to Search

**Files:**
- Modify (replace 3A's stub whole): `core/screen/src/main/kotlin/dev/kestrel/screen/act/SystemControls.kt`
- Modify (replace 3A's stubs whole): `core/commands/src/main/kotlin/dev/kestrel/commands/GrammarPhone.kt`, `core/commands/src/main/kotlin/dev/kestrel/commands/HelpPhone.kt`, `app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommands.kt`
- Create: `app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommandTexts.kt`
- Modify: `app/src/main/AndroidManifest.xml` (at the marker `    <!-- Plan 3C: permissions -->`)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (13.6's bullet `- App visibility uses`, Appendix A's row `| General | Circle to Search`)
- Test: `core/commands/src/test/kotlin/dev/kestrel/commands/GrammarPhoneTest.kt` (new), `app/src/androidTest/kotlin/dev/kestrel/app/commands/PhoneTests.kt` (new helper), `app/src/androidTest/kotlin/dev/kestrel/app/commands/SystemCommandsTest.kt` (new)

**Interfaces:**
- Consumes: plan 3A's `PhoneCommand.Global(action: GlobalCommand)`, `PhoneCommand.PreviousApp`, `PhoneCommand.CircleToSearch`, `GlobalCommand`, `Command`, `CommandResult`, `Outcome`, `HelpGroup`, `CommandHelpTest`; `ScreenSession.actions.global(GlobalAction): ActionResult`, `GlobalAction.ALL_APPS`, `GlobalAction.DISMISS_SHADE`, `ScreenSession.hideFeedback()`, `ScreenSession.system`, `ActionResult`, `ActionMethod.INTENT`, `GLOBAL_ACTION`, `MAGNIFICATION`, `ScreenChangeTracker.awaitQuiet`, `ACTION_QUIET_MILLIS`, `onScreenChanged()`, `SnapshotCache.invalidate()`; `CommandPart`, `CommandEnvironment`, `CommandConversation`; the device-test helpers `CommandTests`, `ScreenServiceRule` (`open`, `shell`, `awaitElement`, `session`), `VoiceTests.shell` (which splits a command at spaces) and `VoiceTests.waitUntil`, and the test app's `NotificationActivity` (title "Fixture reminder", the extra `cancel`) and `CommandsActivity` (its button "Open dialog", whose dialog shows "Draft").
- Produces (`:core:screen`, `dev.kestrel.screen.act`): `class SystemControls(service, snapshots, tracker)` with the suspending `start(intent: Intent): ActionResult`, `openActivity(component: ComponentName, user: UserHandle): ActionResult`, `previousApp(): ActionResult`, `systemAction(id: Int): ActionResult`, `pan(dx: Int, dy: Int): ActionResult`, and `fun systemActions(): Set<Int>`, `fun magnifiedCentre(): Pair<Float, Float>?`. (`:core:commands`) `GrammarPhone` with its fixed phrases of the global actions, the previous app and Circle to Search, and the anchors `// Tasks 2 to 6 of plan 3C add their phrases above this line.`, `// Tasks 2 to 6 of plan 3C add their rules above this line.` and `// Tasks 2 to 6 of plan 3C add their functions above this line.`; its helper `MutableMap<String, Command>.phrases(command, vararg phrases)`; `HelpPhone` with the group "Apps and the system" and the anchor `// Tasks 2 to 6 of plan 3C add their groups above this line.`. (`:app`, `dev.kestrel.app.commands`) `internal fun phoneCommands(environment): CommandPart` and `internal class PhoneCommands(environment)`, with the anchors `// Tasks 2 to 6 of plan 3C add their parts above this line.` and `// Tasks 2 to 6 of plan 3C add their commands above this line.`; `internal object PhoneCommandTexts` with `NO_APP_DRAWER`, `NOT_SWITCHED`, `NO_CIRCLE_TO_SEARCH`, `CIRCLE_TO_SEARCH_ACTION` and the anchor `// Tasks 2 to 6 of plan 3C add their texts above this line.`. androidTest: `internal object PhoneTests` (`SYSTEM_UI`, `CLOCK`, `SETTINGS`, `CALL_LOG`, `IMAGES`, `TEST_ACCOUNT`, `MOBILE`, `WORK`, `homePackage()`, `activeApp(session)`, `awaitActiveApp(session, packageName, timeoutMillis)`, `largestRowId(uri)`, `deleteRowsAfter(uri, id)`, `addContact(given, family, vararg numbers)`, `removeTestContacts()`), and `SystemCommandsTest` (8 tests) with the GrammarPhoneTest anchor `// Tasks 2 to 6 of plan 3C add their tests above this line.`.

Design D9, D5 and D18. The global actions go through `ScreenActions.global`: "back" and "go back", "home" and "go home" (decision 11; "tap home" still taps a button named Home), "show notifications", "show Quick Settings", "show recent apps", "lock screen", "turn off device" (the power menu), "take a screenshot" (Kestrel hides its feedback bar first; the numbers, the labels and the grid stay) and "open apps" (`GlobalAction.ALL_APPS`; where Android refuses it, Kestrel says "I can't open the app drawer on this phone."). "Show previous app" sends two recent-apps actions 300 ms apart and waits up to 3 s for an app window in front whose app is neither the one before nor the home app, whose overview is active in between (prototype P4); the app before is the active app window, or the top-most one while the shade or a system dialog is active. Kestrel keeps no history of the apps. The home app is found with `resolveActivity` for `CATEGORY_HOME`, which an app since Android 11 sees only when its manifest declares that intent in `<queries>`, so this task adds that declaration at the manifest's 3C marker. Circle to Search runs only through the system action 500 when `getSystemActions()` lists it, which the emulator does not, and Kestrel says "Circle to Search isn't available on this phone." otherwise; it makes no gesture on the navigation handle. A global action is repeatable, except the lock screen. `SystemControls` also gets the functions that later tasks use: starting an intent and a launcher activity of a profile from the accessibility service, which Android lets start activities from the background, and moving the magnified area (`pan`, and `magnifiedCentre()`, which the tests and "zoom in" read). The phone part asks for the screen session only when a command needs it; Task 4 adds the commands that work without it. Every command test class of this plan has `@get:Rule(order = 0) val only = CommandTests.only` before `@get:Rule(order = 1) val screen = ScreenServiceRule()`, as plan 3A's classes have, so that a run without `kestrelCommands=true` skips each test before anything changes on the device.

The first step checks, before anything else on the device, that the shell may add and delete contacts of the test account on kestrel-api37, which Task 6's tests need (design D15).

- [ ] **Step 1: Check that the emulator's shell may add and delete the test account's contacts**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; $adb = Get-Adb; & $adb -s emulator-5554 shell content insert --uri content://com.android.contacts/raw_contacts --bind account_type:s:dev.kestrel.test --bind account_name:s:kestrel-test; & $adb -s emulator-5554 shell "content query --uri content://com.android.contacts/raw_contacts --projection _id --where account_name=\'kestrel-test\'"; & $adb -s emulator-5554 shell "content delete --uri 'content://com.android.contacts/raw_contacts?caller_is_syncadapter=true' --where account_name=\'kestrel-test\'"; & $adb -s emulator-5554 shell "content query --uri content://com.android.contacts/raw_contacts --projection _id --where account_name=\'kestrel-test\'"`
Expected: the first query prints one line `Row: 0 _id=` and a number, and the second prints `No result found.`. If the insert or the delete prints an error such as `Permission Denial`, stop and report it to the controller, because Task 6's tests add their contacts this way.

- [ ] **Step 2: Write the failing JVM test of the phrases**

Create `core/commands/src/test/kotlin/dev/kestrel/commands/GrammarPhoneTest.kt`:

```kotlin
package dev.kestrel.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarPhoneTest {
    private fun assertParses(expected: Command, vararg phrases: String) {
        for (phrase in phrases) assertEquals(phrase, expected, CommandParser.parse(phrase))
    }

    private fun assertBare(vararg phrases: String) {
        for (phrase in phrases) assertTrue(phrase, CommandParser.parse(phrase) is Command.Bare)
    }

    @Test
    fun theGlobalActionsHaveSeveralPhrasingsAndBackAndHomeAloneAreGlobalActions() {
        assertParses(PhoneCommand.Global(GlobalCommand.BACK), "Back.", "Go back.", "go back please", "Navigate back")
        assertParses(PhoneCommand.Global(GlobalCommand.HOME), "Home", "Go home.", "Go to the home screen.")
        assertParses(PhoneCommand.Global(GlobalCommand.NOTIFICATIONS), "Show notifications.", "Open the notifications.")
        assertParses(PhoneCommand.Global(GlobalCommand.QUICK_SETTINGS), "Show Quick Settings.", "Open quick settings")
        assertParses(PhoneCommand.Global(GlobalCommand.RECENT_APPS), "Show recent apps.", "Recent apps.", "Show recents")
        assertParses(PhoneCommand.Global(GlobalCommand.LOCK_SCREEN), "Lock screen.", "Lock the phone.")
        assertParses(PhoneCommand.Global(GlobalCommand.POWER_MENU), "Turn off device.", "Show the power menu.")
        assertParses(PhoneCommand.Global(GlobalCommand.SCREENSHOT), "Take a screenshot.", "Screenshot", "Take screenshot")
        assertParses(PhoneCommand.Global(GlobalCommand.ALL_APPS), "Open apps.", "Show all apps.", "Open the app drawer.")
        assertParses(PhoneCommand.PreviousApp, "Show previous app.", "Previous app.", "Go to the previous app.", "Switch apps")
        assertParses(PhoneCommand.CircleToSearch, "Circle to search.", "Start Circle to Search.")
    }

    @Test
    fun tapHomeTapsALabelAndLongerSentencesStayBare() {
        assertEquals(Command.Tap(Target.Label("home")), CommandParser.parse("tap home"))
        assertBare("I think we should go back later.", "Back to the drawing board.", "Home sweet home.")
    }

    // Tasks 2 to 6 of plan 3C add their tests above this line.
}
```

- [ ] **Step 3: Write the failing device tests and their helper**

Create `app/src/androidTest/kotlin/dev/kestrel/app/commands/PhoneTests.kt`:

```kotlin
package dev.kestrel.app.commands

import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.model.WindowKind
import kotlinx.coroutines.runBlocking

/**
 * Helpers of plan 3C's command device tests: Android's own apps, and the rows that the tests add to the contacts, the
 * call log and the media store, through the shell, which may change them. command-test.ps1 deletes what a test leaves.
 * Nothing here reads a row's content other than its ID.
 */
internal object PhoneTests {
    const val SYSTEM_UI = "com.android.systemui"
    const val CLOCK = "com.google.android.deskclock"
    const val SETTINGS = "com.android.settings"
    const val CALL_LOG = "content://call_log/calls"
    const val IMAGES = "content://media/external/images/media"

    /** The account of the contacts that the tests add; command-test.ps1 deletes its contacts at the end. */
    const val TEST_ACCOUNT = "kestrel-test"

    // Phone.TYPE_MOBILE and Phone.TYPE_WORK of Android's contacts.
    const val MOBILE = 2
    const val WORK = 3

    private val ID = Regex("_id=(\\d+)")

    /** The package of the home app, which also shows the recent apps. */
    fun homePackage(): String =
        VoiceTests.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
            .trim().lines().last().substringBefore('/')

    /** The package of the app window that is in front. */
    fun activeApp(session: ScreenSession): String? = runBlocking {
        session.snapshots.current().snapshot.windows.firstOrNull { it.kind == WindowKind.APPLICATION && it.active }?.packageName
    }

    /** Waits up to [timeoutMillis] until the app window of [packageName] is in front. */
    fun awaitActiveApp(session: ScreenSession, packageName: String, timeoutMillis: Long = 10_000): Boolean =
        VoiceTests.waitUntil(timeoutMillis) { activeApp(session) == packageName }

    /** The largest row ID of the content [uri], or 0. */
    fun largestRowId(uri: String): Long =
        ID.findAll(VoiceTests.shell("content query --uri $uri --projection _id")).maxOfOrNull { it.groupValues[1].toLong() } ?: 0

    /** Deletes the rows of [uri] whose ID is larger than [id]. */
    fun deleteRowsAfter(uri: String, id: Long) {
        VoiceTests.shell("content delete --uri $uri --where _id>$id")
    }

    /**
     * Adds a contact of [TEST_ACCOUNT] with a [given] and a [family] name, which Android joins into its display name, and
     * [numbers] with their types. The shell splits a command at spaces, so no value may have one.
     */
    fun addContact(given: String, family: String, vararg numbers: Pair<String, Int>) {
        VoiceTests.shell(
            "content insert --uri content://com.android.contacts/raw_contacts --bind account_type:s:dev.kestrel.test " +
                "--bind account_name:s:$TEST_ACCOUNT",
        )
        val rows = VoiceTests.shell(
            "content query --uri content://com.android.contacts/raw_contacts --projection _id --where account_name='$TEST_ACCOUNT'",
        )
        val id = ID.findAll(rows).maxOf { it.groupValues[1].toLong() }
        VoiceTests.shell(
            "content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$id " +
                "--bind mimetype:s:vnd.android.cursor.item/name --bind data2:s:$given --bind data3:s:$family",
        )
        for ((number, type) in numbers) {
            VoiceTests.shell(
                "content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$id " +
                    "--bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:$number --bind data2:i:$type",
            )
        }
    }

    /** Deletes every contact of [TEST_ACCOUNT], also those of an interrupted earlier run. */
    fun removeTestContacts() {
        VoiceTests.shell(
            "content delete --uri content://com.android.contacts/raw_contacts?caller_is_syncadapter=true --where account_name='$TEST_ACCOUNT'",
        )
    }
}
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/commands/SystemCommandsTest.kt`:

```kotlin
package dev.kestrel.app.commands

import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.commands.Outcome
import dev.kestrel.screen.act.GlobalAction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The global actions, "open apps", "show previous app" and Circle to Search (design D9), on the emulator's own apps.
 * Like every command test, they run only through scripts\command-test.ps1, which puts back the lock-screen setting and
 * deletes the screenshots and the test app's notification.
 */
@RunWith(AndroidJUnit4::class)
class SystemCommandsTest {
    // Before ScreenServiceRule, as in plan 3A's classes: a run without kestrelCommands=true skips each test before the rule
    // switches the accessibility services, and before @Before and @After run.
    @get:Rule(order = 0)
    val only = CommandTests.only

    @get:Rule(order = 1)
    val screen = ScreenServiceRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun start() = CommandTests.start()

    @After
    fun stop() {
        CommandTests.stop()
        screen.shell("input keyevent KEYCODE_HOME")
    }

    @Test
    fun goBackClosesTheDialogAndHomeShowsTheHomeScreen() = runBlocking<Unit> {
        screen.open("CommandsActivity", "Commands fixture")
        assertEquals(Outcome.DONE, CommandTests.say("tap Open dialog").outcome)
        screen.awaitElement { it.label == "Draft" }

        assertEquals(Outcome.DONE, CommandTests.say("go back").outcome)
        assertTrue(VoiceTests.waitUntil(5_000) { runBlocking { screen.session.snapshots.current().snapshot.elements.none { it.label == "Draft" } } })
        assertEquals(Outcome.DONE, CommandTests.say("home").outcome)

        assertTrue("The home screen is not in front", PhoneTests.awaitActiveApp(screen.session, PhoneTests.homePackage()))
    }

    @Test
    fun theShadeQuickSettingsAndTheRecentAppsOpen() = runBlocking<Unit> {
        screen.shell("pm grant $FIXTURES android.permission.POST_NOTIFICATIONS")
        screen.shell("am start -W -n $FIXTURES/.NotificationActivity")
        try {
            assertEquals(Outcome.DONE, CommandTests.say("show notifications").outcome)
            screen.awaitElement { it.packageName == PhoneTests.SYSTEM_UI && it.label == "Fixture reminder" }
            screen.session.actions.global(GlobalAction.DISMISS_SHADE)

            assertEquals(Outcome.DONE, CommandTests.say("show quick settings").outcome)
            screen.awaitElement { it.packageName == PhoneTests.SYSTEM_UI && it.viewId?.contains(":id/qs_tile") == true }
            screen.session.actions.global(GlobalAction.DISMISS_SHADE)

            screen.open("CommandsActivity", "Commands fixture")
            assertEquals(Outcome.DONE, CommandTests.say("show recent apps").outcome)
            // The home app shows the recent apps.
            assertTrue("The recent apps did not show", PhoneTests.awaitActiveApp(screen.session, PhoneTests.homePackage()))
        } finally {
            screen.shell("am start -W -n $FIXTURES/.NotificationActivity --ez cancel true")
            screen.session.actions.global(GlobalAction.DISMISS_SHADE)
        }
    }

    @Test
    fun lockScreenTurnsTheScreenOffAndTheCommandStillEnds() {
        // kestrel-api37 has no lock screen, so the test turns on the swipe lock screen, as ListeningServiceTest does.
        val noLockScreen = screen.shell("cmd lock_settings get-disabled").trim() == "true"
        if (noLockScreen) screen.shell("cmd lock_settings set-disabled false")
        try {
            assertEquals(Outcome.DONE, CommandTests.say("lock screen").outcome)

            val power = context.getSystemService(PowerManager::class.java)
            assertTrue("The screen is still on", VoiceTests.waitUntil(5_000) { !power.isInteractive })
        } finally {
            screen.shell("input keyevent KEYCODE_WAKEUP")
            screen.shell("wm dismiss-keyguard")
            if (noLockScreen) screen.shell("cmd lock_settings set-disabled true")
        }
    }

    @Test
    fun turnOffDeviceShowsThePowerMenu() = runBlocking<Unit> {
        try {
            assertEquals(Outcome.DONE, CommandTests.say("turn off device").outcome)

            screen.awaitElement { it.label.equals("Restart", ignoreCase = true) || it.label.equals("Power off", ignoreCase = true) }
        } finally {
            screen.session.actions.global(GlobalAction.BACK)
        }
    }

    @Test
    fun aScreenshotIsTakenWithoutTheFeedbackBar() {
        screen.open("CommandsActivity", "Commands fixture")
        val before = PhoneTests.largestRowId(PhoneTests.IMAGES)
        try {
            assertEquals(Outcome.DONE, CommandTests.say("take a screenshot").outcome)

            assertNull(screen.session.feedbackText)
            assertTrue("No screenshot was saved", VoiceTests.waitUntil(10_000) { PhoneTests.largestRowId(PhoneTests.IMAGES) > before })
        } finally {
            PhoneTests.deleteRowsAfter(PhoneTests.IMAGES, before)
        }
    }

    @Test
    fun openAppsShowsTheListOfAllApps() = runBlocking<Unit> {
        screen.shell("input keyevent KEYCODE_HOME")

        assertEquals(Outcome.DONE, CommandTests.say("open apps").outcome)

        // The home screen shows neither app; the list of all apps shows both.
        val home = PhoneTests.homePackage()
        screen.awaitElement { it.packageName == home && it.label == "Clock" }
        screen.awaitElement { it.packageName == home && it.label == "Contacts" }
    }

    @Test
    fun showPreviousAppBringsBackTheAppBeforeTheCurrentOne() {
        screen.open("CommandsActivity", "Commands fixture")
        screen.shell("am start -W -a android.settings.SETTINGS")
        assertTrue(PhoneTests.awaitActiveApp(screen.session, PhoneTests.SETTINGS))

        assertEquals(Outcome.DONE, CommandTests.say("show previous app").outcome)

        assertTrue("The fixture app did not come back", PhoneTests.awaitActiveApp(screen.session, FIXTURES))
    }

    @Test
    fun circleToSearchIsNotAvailableOnTheEmulator() {
        assertEquals(Outcome.FAILED, CommandTests.say("circle to search").outcome)

        CommandTests.awaitSaid(PhoneCommandTexts.NO_CIRCLE_TO_SEARCH)
    }
}
```

- [ ] **Step 4: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 --continue :core:commands:test --tests dev.kestrel.commands.GrammarPhoneTest :app:compileDebugAndroidTestKotlin`
Expected: `BUILD FAILED`: `GrammarPhoneTest` runs 2 tests, and `theGlobalActionsHaveSeveralPhrasingsAndBackAndHomeAloneAreGlobalActions` fails with `Back. expected:<Global(action=BACK)> but was:<Bare(5 characters, number=false)>` (the other one, which checks that "tap home" stays plan 3A's tap, passes already), and the device tests do not compile: "Unresolved reference 'PhoneCommandTexts'".

- [ ] **Step 5: Write the phone phrases and the command list's group**

Replace the whole contents of `core/commands/src/main/kotlin/dev/kestrel/commands/GrammarPhone.kt` with:

```kotlin
package dev.kestrel.commands

/**
 * The phone phrases of plan 3C (design spec, sections 9.3 and 13.6, and Appendix A): the global actions, apps, the phone's
 * settings, media, magnification and calls. Its targeted rules return null for a target that is not theirs: "turn on" and
 * "switch on" only for Wi-Fi and Bluetooth, "increase" and "turn up" only with "volume", "open X" never for the spoken
 * punctuation of dictation ("open quote", "open parenthesis", "open bracket"), "call X" and "dial X".
 */
internal object GrammarPhone {
    private val FIXED: Map<String, Command> = buildMap {
        phrases(PhoneCommand.Global(GlobalCommand.BACK), "back", "go back", "navigate back")
        phrases(PhoneCommand.Global(GlobalCommand.HOME), "home", "go home", "go to home", "home screen", "go to the home screen")
        phrases(
            PhoneCommand.Global(GlobalCommand.NOTIFICATIONS),
            "show notifications", "show the notifications", "show my notifications", "open notifications",
            "open the notifications", "open the notification shade",
        )
        phrases(
            PhoneCommand.Global(GlobalCommand.QUICK_SETTINGS),
            "show quick settings", "show the quick settings", "open quick settings", "open the quick settings", "quick settings",
        )
        phrases(
            PhoneCommand.Global(GlobalCommand.RECENT_APPS),
            "show recent apps", "show the recent apps", "open recent apps", "recent apps", "show recents", "recents",
        )
        phrases(PhoneCommand.Global(GlobalCommand.LOCK_SCREEN), "lock screen", "lock the screen", "lock the phone", "lock phone", "lock my phone")
        phrases(
            PhoneCommand.Global(GlobalCommand.POWER_MENU),
            "turn off device", "turn off the device", "power menu", "show power menu", "show the power menu", "open the power menu",
        )
        phrases(
            PhoneCommand.Global(GlobalCommand.SCREENSHOT),
            "take screenshot", "take a screenshot", "screenshot", "take a screen shot", "capture the screen",
        )
        phrases(
            PhoneCommand.Global(GlobalCommand.ALL_APPS),
            "open apps", "open all apps", "show apps", "show all apps", "all apps", "open the app drawer", "show the app drawer",
        )
        phrases(
            PhoneCommand.PreviousApp,
            "show previous app", "show the previous app", "previous app", "go to previous app", "go to the previous app",
            "switch to the previous app", "switch apps",
        )
        phrases(PhoneCommand.CircleToSearch, "circle to search", "start circle to search", "open circle to search")
        // Tasks 2 to 6 of plan 3C add their phrases above this line.
    }

    fun fixed(words: List<String>, text: String): Command? = FIXED[words.joinToString(" ")]

    fun targeted(words: List<String>, text: String): Command? {
        // Tasks 2 to 6 of plan 3C add their rules above this line.
        return null
    }

    private fun MutableMap<String, Command>.phrases(command: Command, vararg phrases: String) {
        for (phrase in phrases) put(phrase, command)
    }

    // Tasks 2 to 6 of plan 3C add their functions above this line.
}
```

Replace the whole contents of `core/commands/src/main/kotlin/dev/kestrel/commands/HelpPhone.kt` with:

```kotlin
package dev.kestrel.commands

/** The phone commands of the command list. Every example must parse to a command (`CommandHelpTest`). */
internal object HelpPhone {
    val groups: List<HelpGroup> = listOf(
        HelpGroup(
            "Apps and the system",
            listOf(
                "go back", "go home", "show notifications", "show quick settings", "show recent apps", "show previous app",
                "lock screen", "turn off device", "take a screenshot", "open apps", "circle to search",
            ),
        ),
        // Tasks 2 to 6 of plan 3C add their groups above this line.
    )
}
```

- [ ] **Step 6: Run the tests of `:core:commands` to verify that they pass**

Run: `scripts\gradle.ps1 :core:commands:test`
Expected: `BUILD SUCCESSFUL`, with 90 tests (88 + `GrammarPhoneTest` 2); `CommandHelpTest` passes with the new group, whose every example parses to a command.

- [ ] **Step 7: Write the system controls**

Replace the whole contents of `core/screen/src/main/kotlin/dev/kestrel/screen/act/SystemControls.kt` with:

```kotlin
package dev.kestrel.screen.act

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.MagnificationConfig
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.UserHandle
import android.view.accessibility.AccessibilityWindowInfo
import dev.kestrel.screen.read.LiveSnapshot
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.SnapshotCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Starts activities and system actions, switches to the previous app and pans the magnification (design spec, section
 * 9.3). Like [ScreenActions], it makes blocking binder calls on the caller's dispatcher, so callers run it on
 * `Dispatchers.IO`, and each action waits until the screen has been quiet afterwards, but never longer than 3 s.
 */
class SystemControls(
    private val service: AccessibilityService,
    private val snapshots: SnapshotCache<LiveSnapshot>,
    private val tracker: ScreenChangeTracker,
) {
    /**
     * Starts [intent] from the accessibility service, which Android lets start activities from the background (prototype
     * P4), in a new task.
     */
    suspend fun start(intent: Intent): ActionResult {
        return try {
            service.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            settled(ActionMethod.INTENT)
        } catch (e: ActivityNotFoundException) {
            NO_ACTIVITY
        } catch (e: SecurityException) {
            REFUSED
        }
    }

    /** Starts the launcher activity [component] of the profile [user], as the launcher would (research r21). */
    suspend fun openActivity(component: ComponentName, user: UserHandle): ActionResult {
        val launcher = service.getSystemService(LauncherApps::class.java) ?: return NO_ACTIVITY
        return try {
            launcher.startMainActivity(component, user, null, null)
            settled(ActionMethod.INTENT)
        } catch (e: ActivityNotFoundException) {
            NO_ACTIVITY
        } catch (e: SecurityException) {
            REFUSED
        }
    }

    /**
     * Shows the app that was in front before the current one (research r24, prototype P4): two recent-apps actions 300 ms
     * apart, and then a wait of up to 3 s for an app window in front whose app is neither the one before nor the home
     * screen, whose overview shows in between. Kestrel keeps no history of the apps.
     */
    suspend fun previousApp(): ActionResult {
        val before = frontApp()
        val home = homeApp()
        if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)) return RECENTS_REFUSED
        delay(RECENTS_GAP_MILLIS)
        if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)) return RECENTS_REFUSED
        val switched = withTimeoutOrNull(SWITCH_MILLIS) {
            while (true) {
                val now = activeApp()
                if (now != null && now != before && now != home) break
                delay(POLL_MILLIS)
            }
        } != null
        tracker.onScreenChanged()
        return if (switched) settled(ActionMethod.GLOBAL_ACTION) else NOT_SWITCHED
    }

    /** Performs the system action [id], such as Circle to Search (500) where the launcher offers it (research r25). */
    suspend fun systemAction(id: Int): ActionResult =
        if (service.performGlobalAction(id)) settled(ActionMethod.GLOBAL_ACTION) else ActionResult.Failed("Android refused the system action")

    /**
     * Moves the centre of the magnified area by [dx] and [dy] pixels of the screen; Android keeps the magnified area on the
     * screen. Fails while the screen is not magnified.
     */
    suspend fun pan(dx: Int, dy: Int): ActionResult {
        val controller = service.magnificationController
        val config = controller.magnificationConfig
        if (config == null || config.scale <= 1f) return NOT_MAGNIFIED
        val moved = MagnificationConfig.Builder()
            .setMode(MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN)
            .setScale(config.scale)
            .setCenterX(config.centerX + dx)
            .setCenterY(config.centerY + dy)
            .build()
        val done = withContext(Dispatchers.Main) { controller.setMagnificationConfig(moved, false) }
        if (!done) return ActionResult.Failed("Android refused to move the magnification")
        // As in ScreenActions.zoom: Android sends no event for a move of the magnification, and its node cache keeps the
        // old positions, so Kestrel reports the change itself, before and after the wait.
        tracker.onScreenChanged()
        val result = settled(ActionMethod.MAGNIFICATION)
        service.clearCache()
        tracker.onScreenChanged()
        snapshots.invalidate()
        return result
    }

    /** The centre of the magnified area in screen pixels, or null while the screen is not magnified. */
    fun magnifiedCentre(): Pair<Float, Float>? =
        service.magnificationController.magnificationConfig?.takeIf { it.scale > 1f }?.let { it.centerX to it.centerY }

    /** The ids of the system actions that Android offers now, such as 14 for all apps. */
    fun systemActions(): Set<Int> = service.systemActions.mapTo(HashSet()) { it.id }

    /** The package of the app window that is in front, or null. Kestrel's own overlays are no app windows. */
    private fun activeApp(): String? =
        service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }?.root?.packageName?.toString()

    // The app in front: the active app window, or the top-most one while the shade or a system dialog is active, so that the
    // app under the shade does not count as the previous app.
    private fun frontApp(): String? {
        val apps = service.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        return (apps.firstOrNull { it.isActive } ?: apps.firstOrNull())?.root?.packageName?.toString()
    }

    // The manifest's <queries> lets Kestrel see the home app.
    private fun homeApp(): String? = service.packageManager
        .resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName

    private suspend fun settled(method: ActionMethod): ActionResult {
        tracker.awaitQuiet(ScreenChangeTracker.ACTION_QUIET_MILLIS, since = tracker.now())
        return ActionResult.Done(method)
    }

    private companion object {
        const val RECENTS_GAP_MILLIS = 300L
        const val SWITCH_MILLIS = 3_000L
        const val POLL_MILLIS = 100L
        val NO_ACTIVITY = ActionResult.Failed("No app can open it")
        val REFUSED = ActionResult.Failed("Android does not let Kestrel open it")
        val RECENTS_REFUSED = ActionResult.Failed("Android refused the recent apps action")
        val NOT_SWITCHED = ActionResult.Failed("No other app came to the front")
        val NOT_MAGNIFIED = ActionResult.Failed("The screen is not magnified")
    }
}
```

- [ ] **Step 8: Write the phone part and its texts**

Create `app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommandTexts.kt`:

```kotlin
package dev.kestrel.app.commands

/**
 * What the phone commands say and show (design spec, sections 9.3 and 13.6, and Appendix A). Kestrel speaks only what the
 * owner must act on; the other texts go to the feedback bar. A text with a name or a number in it is never logged.
 */
internal object PhoneCommandTexts {
    const val NO_APP_DRAWER = "I can't open the app drawer on this phone."
    const val NOT_SWITCHED = "I couldn't switch apps."
    const val NO_CIRCLE_TO_SEARCH = "Circle to Search isn't available on this phone."

    /** The system action of Circle to Search, where the launcher offers it (research r25). */
    const val CIRCLE_TO_SEARCH_ACTION = 500

    // Tasks 2 to 6 of plan 3C add their texts above this line.
}
```

Replace the whole contents of `app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommands.kt` with:

```kotlin
package dev.kestrel.app.commands

import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.GlobalCommand
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.PhoneCommand
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.GlobalAction

/**
 * The phone commands of plan 3C (design spec, sections 9.3 and 13.6, and Appendix A): global actions, apps, the phone's
 * settings, media, magnification and calls. The part builds its own Android services from the environment's context,
 * and touches none of them before a command needs it.
 */
internal fun phoneCommands(environment: CommandEnvironment): CommandPart = PhoneCommands(environment)

internal class PhoneCommands(private val environment: CommandEnvironment) : CommandPart {
    // Tasks 2 to 6 of plan 3C add their parts above this line.

    override suspend fun run(command: Command, heard: String, conversation: CommandConversation): CommandResult? {
        val screen = environment.screen() ?: return null
        return when (command) {
            is PhoneCommand.Global -> global(screen, command.action)
            PhoneCommand.PreviousApp -> when (screen.system.previousApp()) {
                is ActionResult.Done -> CommandResult(Outcome.DONE, repeatable = true)
                is ActionResult.Failed -> CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NOT_SWITCHED)
            }
            PhoneCommand.CircleToSearch -> circleToSearch(screen)
            // Tasks 2 to 6 of plan 3C add their commands above this line.
            else -> null
        }
    }

    // A screenshot leaves out Kestrel's feedback bar; the numbers, the labels and the grid stay (design D9).
    private suspend fun global(screen: ScreenSession, action: GlobalCommand): CommandResult {
        if (action == GlobalCommand.SCREENSHOT) screen.hideFeedback()
        return when (val result = screen.actions.global(globalAction(action))) {
            is ActionResult.Done -> CommandResult(Outcome.DONE, repeatable = action != GlobalCommand.LOCK_SCREEN)
            is ActionResult.Failed -> if (action == GlobalCommand.ALL_APPS) {
                CommandResult(Outcome.FAILED, say = PhoneCommandTexts.NO_APP_DRAWER)
            } else {
                // The reasons of ScreenActions are fixed texts without the screen's words.
                CommandResult(Outcome.FAILED, show = result.reason)
            }
        }
    }

    // Only through Android's system action, where the launcher offers it (research r25); never a gesture.
    private suspend fun circleToSearch(screen: ScreenSession): CommandResult {
        if (PhoneCommandTexts.CIRCLE_TO_SEARCH_ACTION !in screen.system.systemActions()) {
            return CommandResult(Outcome.FAILED, say = PhoneCommandTexts.NO_CIRCLE_TO_SEARCH)
        }
        return when (val result = screen.system.systemAction(PhoneCommandTexts.CIRCLE_TO_SEARCH_ACTION)) {
            is ActionResult.Done -> CommandResult(Outcome.DONE)
            is ActionResult.Failed -> CommandResult(Outcome.FAILED, show = result.reason)
        }
    }

    private fun globalAction(action: GlobalCommand): GlobalAction = when (action) {
        GlobalCommand.BACK -> GlobalAction.BACK
        GlobalCommand.HOME -> GlobalAction.HOME
        GlobalCommand.NOTIFICATIONS -> GlobalAction.NOTIFICATIONS
        GlobalCommand.QUICK_SETTINGS -> GlobalAction.QUICK_SETTINGS
        GlobalCommand.RECENT_APPS -> GlobalAction.RECENT_APPS
        GlobalCommand.LOCK_SCREEN -> GlobalAction.LOCK_SCREEN
        GlobalCommand.POWER_MENU -> GlobalAction.POWER_MENU
        GlobalCommand.SCREENSHOT -> GlobalAction.SCREENSHOT
        GlobalCommand.ALL_APPS -> GlobalAction.ALL_APPS
    }
}
```

In `app/src/main/AndroidManifest.xml`, replace

```xml
    <!-- Plan 3C: permissions -->
```

with

```xml
    <!-- "Show previous app" tells the home app, whose overview shows between the two apps, from the app that comes back
         (design D9 of sub-project 3). Since Android 11 an app sees the home app only when it declares its intent here. -->
    <queries>
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.HOME" />
        </intent>
    </queries>
    <!-- Plan 3C: permissions -->
```

- [ ] **Step 9: Change the spec's lines of the home app and Circle to Search**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace

```markdown
- App visibility uses `<queries>` for launcher apps and for the deep-link schemes.
```

with

```markdown
- App visibility uses `<queries>` for launcher apps, for the home app (so that "show previous app" can tell the home app's overview from the app that comes back) and for the deep-link schemes.
```

Then replace

```markdown
| General | Circle to Search | Best effort: a long press on the navigation handle, where the phone supports it |
```

with

```markdown
| General | Circle to Search | Yes, through Android's system action where the launcher offers it; otherwise Kestrel says that it isn't available on the phone. Kestrel makes no gesture on the navigation handle. |
```

- [ ] **Step 10: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin :testapp:assembleDebug`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:commands` 90, `:app` 323, the others as before), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests and the test app compile.

- [ ] **Step 11: Run the new command device tests**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\command-test.ps1 -Serial emulator-5554 -Class SystemCommandsTest`
Expected: `BUILD SUCCESSFUL` and "Command tests: 8 run, 0 failed, 0 errors, 0 skipped.", with no warning from the restores at the end. If `openAppsShowsTheListOfAllApps` fails because the list of all apps shows no element named "Clock" or "Contacts", or `theShadeQuickSettingsAndTheRecentAppsOpen` because no element's view ID contains `:id/qs_tile`, report it to the controller with the failure message, and do not change the tests.

- [ ] **Step 12: Run all command device tests**

The phone phrases now parse "back", "home" and the other global phrases, which no test of plans 3A and 3B uses (design D5); this run checks that.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\command-test.ps1 -Serial emulator-5554`
Expected: `BUILD SUCCESSFUL` and "Command tests: 67 run, 0 failed, 0 errors, 0 skipped." (59 + `SystemCommandsTest` 8), with no warning from the restores.

- [ ] **Step 13: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 14: Commit**

```powershell
git add core/screen/src/main/kotlin/dev/kestrel/screen/act/SystemControls.kt core/commands/src/main/kotlin/dev/kestrel/commands/GrammarPhone.kt core/commands/src/main/kotlin/dev/kestrel/commands/HelpPhone.kt core/commands/src/test/kotlin/dev/kestrel/commands/GrammarPhoneTest.kt app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommands.kt app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommandTexts.kt app/src/main/AndroidManifest.xml app/src/androidTest/kotlin/dev/kestrel/app/commands/PhoneTests.kt app/src/androidTest/kotlin/dev/kestrel/app/commands/SystemCommandsTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(commands): the global actions, open apps, show previous app and Circle to Search by voice" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 2: "Open X" and "open work X"

**Files:**
- Modify: `core/commands/src/main/kotlin/dev/kestrel/commands/GrammarPhone.kt`, `core/commands/src/main/kotlin/dev/kestrel/commands/HelpPhone.kt`, `app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommands.kt`, `app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommandTexts.kt` (at Task 1's anchors)
- Create: `app/src/main/kotlin/dev/kestrel/app/commands/AppCommands.kt`
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (Appendix A's row `| General | Open [app]`)
- Test: `core/commands/src/test/kotlin/dev/kestrel/commands/GrammarPhoneTest.kt`, `app/src/test/kotlin/dev/kestrel/app/commands/PhoneAppsTest.kt` (new), `app/src/androidTest/kotlin/dev/kestrel/app/commands/AppCommandsTest.kt` (new)

**Interfaces:**
- Consumes: Task 1's anchors, `phrases`, `PhoneCommands`, `PhoneCommandTexts`, `SystemControls.openActivity`, `PhoneTests.CLOCK` and `PhoneTests.awaitActiveApp`; plan 3A's `PhoneCommand.OpenApp(name, work)`, `cutWords(text, from, to)`, `LabelMatcher.decide`, `MatchCandidate`, `MatchDecision`, `Strictness.NORMAL`, `Normalizer.normalize`, `SpokenNumbers.answer(text)`, `CommandConversation.ask`, `CommandEnvironment.context` and `report`, `CommandResult.passOn`, and the help groups of plan 3A, among them "open tutorial", which `GrammarScreen` parses before `GrammarPhone.targeted`.
- Produces: the rule `openApp` of `GrammarPhone.targeted` with `withoutPlease(words)` (only "please" goes, because an app's name may end with "now", as "Google Now" does); (`:app`) `internal data class LaunchableApp(label, component, user, work)`, `internal class AppCommands(environment)` with `suspend fun open(screen, command, conversation): CommandResult`, and these top-level helpers, which Task 6's calls use too: `internal sealed interface Pick<out T>` (`One(item)`, `Several(items)`, `None`), `internal const val MAX_SPOKEN_CHOICES = 4`, `internal fun <T> pick(spoken, items, name, preferred): Pick<T>`, `internal sealed interface Answer` (`Chosen(index)`, `Ends(result)`) and `internal suspend fun askAmong(conversation, question, names, fold = { it }): Answer`; the texts `cantFindApp(name)`, `WORK_APPS_TOO_OLD`, `NO_WORK_PROFILE`, `WORK_APPS_PAUSED`, `whichOne(names)`, `noChoice(number)` and `SAY_THE_NUMBER`; the help group "Open apps".

Design D9 and D5. "Open X" and "launch X" take the rest of the utterance as the app's name, in the recogniser's case and without a final "please"; "open work X", "open X in the work profile" and "open X from the work profile" look only in the work profile. "Open" never takes plan 3B's spoken punctuation ("open quote", "open parenthesis", "open bracket" and their variants), so that those stay dictation, and the fixed phrases ("open apps", "open the app drawer", "open tutorial") come first. The name is matched with the matcher's normal limits against the launcher's apps (`LauncherApps.getActivityList`); an app in both profiles counts once, as the owner's, but two apps of the owner with the same name both stay, so that "Which one?" asks. On Android 15 and later, a work profile is a profile whose launcher user type is `android.os.usertype.profile.MANAGED`, so Secure Folder, a clone profile and the private space never count, and a profile that Android refuses to describe counts as no work profile; on Android 13 and 14 Kestrel cannot tell them apart, so it opens only the owner's apps there and says "I can't open work apps on this version of Android." for "open work X". While the work profile is paused, Kestrel opens the app all the same, which makes Android show its own dialog to turn work apps on, and says "Work apps are paused." Close names ask "Which one?" by voice, with up to 4 names said and kept on the bar; the answer is a number or one of the names, an answer that is neither ends the command and counts as a new utterance, an answer that names two of the offered items (two apps called "Phone") ends it with "Say the number." on the bar, and no answer cancels it. The device tests make a work profile with `pm create-user --profileOf 0 --managed KestrelTest` and remove it; one of them pauses it with `UserManager.requestQuietModeEnabled`, under the shell's permissions, adopted through the rule's `screen.automation`, which keeps Kestrel's accessibility service on (`Instrumentation.getUiAutomation()` would connect the test's UiAutomation again without that flag and turn the service off). `command-test.ps1` removes a profile that an interrupted run left. As every command test class of this plan, `AppCommandsTest` has `CommandTests.only` as its first rule, so that a run without `kestrelCommands=true` skips each test before anything changes on the device.

- [ ] **Step 1: Write the failing JVM tests**

In `core/commands/src/test/kotlin/dev/kestrel/commands/GrammarPhoneTest.kt`, replace

```kotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
```

with

```kotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
```

Then replace

```kotlin
    // Tasks 2 to 6 of plan 3C add their tests above this line.
```

with

```kotlin
    @Test
    fun openTakesTheAppsNameWithItsCaseAndTheWorkProfile() {
        assertParses(PhoneCommand.OpenApp("Chrome", work = false), "Open Chrome.", "Launch Chrome", "open Chrome please")
        assertParses(PhoneCommand.OpenApp("the Play Store", work = false), "Open the Play Store.")
        assertParses(PhoneCommand.OpenApp("Gmail", work = true), "Open work Gmail.", "Open Gmail in the work profile.")
        assertParses(PhoneCommand.Global(GlobalCommand.ALL_APPS), "Open apps.")
        assertParses(Command.Help(HelpKind.TUTORIAL), "Open tutorial.")
    }

    @Test
    fun openNeverOpensTheSpokenPunctuationOfDictation() {
        for (phrase in listOf("open quote", "Open quote hello close quote", "open parenthesis", "open bracket one", "open double quote")) {
            assertFalse(phrase, CommandParser.parse(phrase) is PhoneCommand.OpenApp)
        }
    }

    // Tasks 2 to 6 of plan 3C add their tests above this line.
```

Create `app/src/test/kotlin/dev/kestrel/app/commands/PhoneAppsTest.kt`:

```kotlin
package dev.kestrel.app.commands

import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneAppsTest {
    private class App(val label: String, val work: Boolean)

    private val chrome = App("Chrome", work = false)
    private val workChrome = App("Chrome", work = true)
    private val gmail = App("Gmail", work = false)
    private val clock = App("Clock", work = false)
    private val photos = App("Photos", work = false)
    private val editor = App("Photo Editor", work = false)
    private val apps = listOf(workChrome, chrome, gmail, clock, photos, editor, App("Phone", work = false))

    private fun pick(spoken: String, items: List<App> = apps) = pick(spoken, items, { it.label }, { !it.work })

    @Test
    fun theOwnersAppStandsForTheSameAppInTheWorkProfile() {
        assertEquals(Pick.One(gmail), pick("Gmail"))
        assertEquals(Pick.One(chrome), pick("chrome"))
        assertEquals(Pick.One(workChrome), pick("Chrome", listOf(workChrome, gmail)))
        assertEquals(Pick.None, pick("Quorbex Zyntal"))
    }

    @Test
    fun twoAppsOfTheOwnerWithTheSameNameAreBothOffered() {
        val otherPhone = App("Phone", work = false)
        val phone = App("Phone", work = false)

        assertEquals(Pick.Several(listOf(phone, otherPhone)), pick("Phone", listOf(phone, otherPhone, gmail)))
    }

    @Test
    fun closeNamesAreOfferedAndAnAnswerIsANumberOrOneOfTheNames() = runTest {
        assertEquals(Pick.Several(listOf(photos, editor)), pick("Photo"))
        val names = listOf("Photos", "Photo Editor")

        assertEquals(Answer.Chosen(1), askAmong(Answers("2"), PhoneCommandTexts.whichOne(names), names))
        assertEquals(Answer.Chosen(1), askAmong(Answers("photo editor"), "Which one?", names))
        assertEquals(Answer.Chosen(1), askAmong(Answers("to"), "Which one?", names))
    }

    @Test
    fun anAnswerThatIsNoChoiceEndsTheCommand() = runTest {
        val names = listOf("Photos", "Photo Editor")

        assertEquals(Answer.Ends(CommandResult(Outcome.CANCELLED, passOn = "scroll down")), askAmong(Answers("scroll down"), "Which one?", names))
        assertEquals(Answer.Ends(CommandResult(Outcome.FAILED, show = PhoneCommandTexts.noChoice(3))), askAmong(Answers("3"), "Which one?", names))
        assertEquals(Answer.Ends(CommandResult(Outcome.CANCELLED)), askAmong(Answers(null), "Which one?", names))
        assertEquals("Which one? 1, Photos. 2, Photo Editor.", PhoneCommandTexts.whichOne(names))
        // Two offered items with the same name: the name chooses neither, and it is no new utterance.
        assertEquals(
            Answer.Ends(CommandResult(Outcome.FAILED, show = PhoneCommandTexts.SAY_THE_NUMBER)),
            askAmong(Answers("phone"), "Which one?", listOf("Phone", "Phone")),
        )
    }

    /** Answers each question with the next of [answers]; null is a question that nobody answered. */
    private class Answers(private vararg val answers: String?) : CommandConversation {
        private var next = 0

        override suspend fun say(text: String) = Unit

        override suspend fun ask(question: String): String? = answers[next++]

        override fun keep(text: String) = Unit

        override suspend fun countdown(millis: Long, text: (secondsLeft: Int) -> String): Boolean = true
    }
}
```

- [ ] **Step 2: Write the failing device tests**

Create `app/src/androidTest/kotlin/dev/kestrel/app/commands/AppCommandsTest.kt`:

```kotlin
package dev.kestrel.app.commands

import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.commands.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Open X" and "open work X" (design D9; research r21), with the emulator's Clock app in the owner's profile and in a work
 * profile that the tests make with `pm create-user --profileOf 0 --managed` and remove; one test pauses it.
 * command-test.ps1 also removes a profile that an interrupted run left.
 */
@RunWith(AndroidJUnit4::class)
class AppCommandsTest {
    // Before ScreenServiceRule, as in plan 3A's classes: a run without kestrelCommands=true skips each test before the rule
    // switches the accessibility services, and before @Before and @After run.
    @get:Rule(order = 0)
    val only = CommandTests.only

    @get:Rule(order = 1)
    val screen = ScreenServiceRule()

    private val launcher = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(LauncherApps::class.java)

    @Before
    fun start() {
        removeWorkProfiles()
        CommandTests.start()
    }

    @After
    fun stop() {
        CommandTests.stop()
        removeWorkProfiles()
        screen.shell("input keyevent KEYCODE_HOME")
    }

    @Test
    fun openStartsTheAppThatTheOwnerNames() {
        assertEquals(Outcome.DONE, CommandTests.say("open Clock").outcome)

        assertTrue("Clock is not in front", PhoneTests.awaitActiveApp(screen.session, PhoneTests.CLOCK))
    }

    @Test
    fun anAppThatIsNotThereIsSaid() {
        assertEquals(Outcome.FAILED, CommandTests.say("open Quorbex Zyntal").outcome)

        CommandTests.awaitSaid("I can't find an app called Quorbex Zyntal.")
    }

    @Test
    fun openWorkWithoutAWorkProfileSaysSo() {
        assertEquals(Outcome.FAILED, CommandTests.say("open work Clock").outcome)

        CommandTests.awaitSaid(PhoneCommandTexts.NO_WORK_PROFILE)
    }

    @Test
    fun openWorkStartsTheWorkProfilesAppAndOpenTheOwnersOne() {
        val user = createWorkProfile()

        assertEquals(Outcome.DONE, CommandTests.say("open work Clock").outcome)
        assertTrue("The work profile's Clock is not in front", VoiceTests.waitUntil(10_000) { resumed().contains("u$user ${PhoneTests.CLOCK}/") })

        assertEquals(Outcome.DONE, CommandTests.say("open Clock").outcome)
        assertTrue("The owner's Clock is not in front", VoiceTests.waitUntil(10_000) { resumed().contains("u0 ${PhoneTests.CLOCK}/") })
    }

    @Test
    fun aPausedWorkProfilesAppOpensAndKestrelSaysThatWorkAppsArePaused() {
        createWorkProfile()
        val work = launcher.profiles.first { it != Process.myUserHandle() }
        val users = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(UserManager::class.java)
        // Pausing a profile needs MANAGE_USERS or MODIFY_QUIET_MODE, which only the shell holds here. The rule's UiAutomation
        // keeps the accessibility services on; Instrumentation.getUiAutomation() would connect it again without that flag
        // and turn Kestrel's service off.
        screen.automation.adoptShellPermissionIdentity()
        try {
            users.requestQuietModeEnabled(true, work)
        } finally {
            screen.automation.dropShellPermissionIdentity()
        }
        assertTrue("Android did not pause the work profile", VoiceTests.waitUntil(10_000) { users.isQuietModeEnabled(work) })

        // Android shows its own dialog to turn work apps on.
        assertEquals(Outcome.DONE, CommandTests.say("open work Clock").outcome)
        CommandTests.awaitSaid(PhoneCommandTexts.WORK_APPS_PAUSED)
    }

    // Makes a work profile, starts it, waits until the launcher API lists it, and returns its user ID.
    private fun createWorkProfile(): String {
        val created = screen.shell("pm create-user --profileOf 0 --managed $PROFILE")
        val user = Regex("created user id (\\d+)").find(created)?.groupValues?.get(1) ?: throw AssertionError("No profile: $created")
        screen.shell("am start-user -w $user")
        assertTrue("Android does not list the profile", VoiceTests.waitUntil(15_000) { launcher.profiles.size == 2 })
        return user
    }

    // The resumed activity, with its user, as "u10 com.google.android.deskclock/...". It names no window content.
    private fun resumed(): String = screen.shell("dumpsys activity activities").lineSequence().firstOrNull { "topResumedActivity" in it }.orEmpty()

    private fun removeWorkProfiles() {
        for (match in Regex("UserInfo\\{(\\d+):$PROFILE:").findAll(screen.shell("pm list users"))) {
            screen.shell("pm remove-user ${match.groupValues[1]}")
        }
    }

    private companion object {
        const val PROFILE = "KestrelTest"
    }
}
```

- [ ] **Step 3: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 --continue :core:commands:test --tests dev.kestrel.commands.GrammarPhoneTest :app:testDebugUnitTest --tests dev.kestrel.app.commands.PhoneAppsTest`
Expected: `BUILD FAILED`: `GrammarPhoneTest` runs 4 tests and `openTakesTheAppsNameWithItsCaseAndTheWorkProfile` fails at "Open Chrome.", because the phrase is still a bare text (`openNeverOpensTheSpokenPunctuationOfDictation` passes already; it guards the rule that this task adds); `:app`'s tests do not compile: "Unresolved reference 'pick'".

- [ ] **Step 4: Write the phrases and the command list's group**

In `core/commands/src/main/kotlin/dev/kestrel/commands/GrammarPhone.kt`, replace

```kotlin
        // Tasks 2 to 6 of plan 3C add their rules above this line.
```

with

```kotlin
        openApp(words, text)?.let { return it }
        // Tasks 2 to 6 of plan 3C add their rules above this line.
```

Then replace

```kotlin
    // Tasks 2 to 6 of plan 3C add their functions above this line.
```

with

```kotlin
    private val OPEN_VERBS = setOf("open", "launch")

    // A name never ends with "please", which the parser removes only from a bare text.
    private fun withoutPlease(words: List<String>): Int = if (words.size > 2 && words.last() == "please") words.size - 1 else words.size

    // Plan 3B's spoken punctuation, which "open" never opens as an app: "open quote", "open parenthesis" and the others.
    private val PUNCTUATION = setOf("quote", "quotes", "double", "single", "parenthesis", "parentheses", "paren", "bracket", "brackets")
    private val IN_WORK_PROFILE = listOf(
        listOf("in", "the", "work", "profile"),
        listOf("in", "work", "profile"),
        listOf("from", "the", "work", "profile"),
    )

    // "Open Chrome", "open work Gmail", "open Gmail in the work profile". The name keeps the recogniser's case.
    private fun openApp(words: List<String>, text: String): Command? {
        val end = withoutPlease(words)
        if (end < 2 || words[0] !in OPEN_VERBS) return null
        if (words[0] == "open" && words[1] in PUNCTUATION) return null
        if (words[1] == "work" && end > 2) return PhoneCommand.OpenApp(cutWords(text, 2, end), work = true)
        val ending = IN_WORK_PROFILE.firstOrNull { end - 1 > it.size && words.subList(end - it.size, end) == it }
        if (ending != null) return PhoneCommand.OpenApp(cutWords(text, 1, end - ending.size), work = true)
        return PhoneCommand.OpenApp(cutWords(text, 1, end), work = false)
    }

    // Tasks 2 to 6 of plan 3C add their functions above this line.
```

In `core/commands/src/main/kotlin/dev/kestrel/commands/HelpPhone.kt`, replace

```kotlin
        // Tasks 2 to 6 of plan 3C add their groups above this line.
```

with

```kotlin
        HelpGroup("Open apps", listOf("open Chrome", "open work Gmail", "open Gmail in the work profile")),
        // Tasks 2 to 6 of plan 3C add their groups above this line.
```

- [ ] **Step 5: Run the tests of `:core:commands` to verify that they pass**

Run: `scripts\gradle.ps1 :core:commands:test`
Expected: `BUILD SUCCESSFUL`, with 92 tests (`GrammarPhoneTest` 4).

- [ ] **Step 6: Write the apps**

Create `app/src/main/kotlin/dev/kestrel/app/commands/AppCommands.kt`:

```kotlin
package dev.kestrel.app.commands

import android.content.ComponentName
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.annotation.RequiresApi
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.PhoneCommand
import dev.kestrel.commands.SpokenNumbers
import dev.kestrel.commands.text.LabelMatcher
import dev.kestrel.commands.text.MatchCandidate
import dev.kestrel.commands.text.MatchDecision
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.commands.text.Strictness
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionResult

/** An app that the launcher shows, in the owner's profile or the work profile. Its text form never shows its name. */
internal data class LaunchableApp(val label: String, val component: ComponentName, val user: UserHandle, val work: Boolean) {
    override fun toString() = "LaunchableApp(label of ${label.length} characters, work=$work)"
}

/**
 * "Open X" and "open work X" (design spec, Appendix A; research r21). Kestrel matches X against the launcher's apps of
 * the owner's profile and, on Android 15 and later, of a work profile, and starts the match through the launcher API. On
 * Android 13 and 14 it cannot tell a work profile from Secure Folder or a clone profile, so it uses only the owner's
 * profile there.
 */
internal class AppCommands(environment: CommandEnvironment) {
    private val launcher: LauncherApps? = environment.context.getSystemService(LauncherApps::class.java)
    private val users: UserManager? = environment.context.getSystemService(UserManager::class.java)
    private val report = environment.report

    suspend fun open(screen: ScreenSession, command: PhoneCommand.OpenApp, conversation: CommandConversation): CommandResult {
        val launcher = launcher ?: return CommandResult(Outcome.FAILED, say = PhoneCommandTexts.cantFindApp(command.name))
        val owner = Process.myUserHandle()
        val work = if (Build.VERSION.SDK_INT >= WORK_PROFILE_API) workProfile(launcher, owner) else null
        if (command.work && Build.VERSION.SDK_INT < WORK_PROFILE_API) {
            return CommandResult(Outcome.FAILED, say = PhoneCommandTexts.WORK_APPS_TOO_OLD)
        }
        if (command.work && work == null) return CommandResult(Outcome.FAILED, say = PhoneCommandTexts.NO_WORK_PROFILE)
        val apps = when {
            command.work -> apps(launcher, checkNotNull(work), isWork = true)
            work == null -> apps(launcher, owner, isWork = false)
            else -> apps(launcher, owner, isWork = false) + apps(launcher, work, isWork = true)
        }
        val app = when (val picked = pick(command.name, apps, { it.label }, { !it.work })) {
            is Pick.One -> picked.item
            is Pick.Several -> {
                val names = picked.items.map { it.label }
                when (val answer = askAmong(conversation, PhoneCommandTexts.whichOne(names), names)) {
                    is Answer.Chosen -> picked.items[answer.index]
                    is Answer.Ends -> return answer.result
                }
            }
            Pick.None -> return CommandResult(Outcome.FAILED, say = PhoneCommandTexts.cantFindApp(command.name))
        }
        // Android shows its own dialog to turn paused work apps on.
        val paused = app.work && users?.isQuietModeEnabled(app.user) == true
        return when (val result = screen.system.openActivity(app.component, app.user)) {
            is ActionResult.Done -> CommandResult(Outcome.DONE, say = PhoneCommandTexts.WORK_APPS_PAUSED.takeIf { paused }, repeatable = true)
            is ActionResult.Failed -> CommandResult(Outcome.FAILED, show = result.reason)
        }
    }

    private fun apps(launcher: LauncherApps, user: UserHandle, isWork: Boolean): List<LaunchableApp> = try {
        launcher.getActivityList(null, user).map { LaunchableApp(it.label.toString(), it.componentName, user, isWork) }
    } catch (e: SecurityException) {
        // Only the class, never a message (section 13.5).
        report("Listing the apps of a profile", e)
        emptyList()
    }

    // A profile whose type is the managed profile; never Secure Folder, a clone profile or the private space. A profile
    // that Android refuses to describe counts as no work profile.
    @RequiresApi(WORK_PROFILE_API)
    private fun workProfile(launcher: LauncherApps, owner: UserHandle): UserHandle? = launcher.profiles.firstOrNull { user ->
        user != owner && try {
            launcher.getLauncherUserInfo(user)?.userType == MANAGED_PROFILE
        } catch (e: SecurityException) {
            report("Reading a profile's type", e)
            false
        }
    }

    private companion object {
        // LauncherApps.getLauncherUserInfo and its user types arrived with Android 15.
        const val WORK_PROFILE_API = 35
        const val MANAGED_PROFILE = "android.os.usertype.profile.MANAGED"
    }
}

/** What the owner named: one item, several close ones for "Which one?", or none. */
internal sealed interface Pick<out T> {
    data class One<T>(val item: T) : Pick<T>

    data class Several<T>(val items: List<T>) : Pick<T>

    data object None : Pick<Nothing>
}

/** How many names "Which one?" says aloud at most. */
internal const val MAX_SPOKEN_CHOICES = 4

/**
 * The item whose [name] the owner said in [spoken], with the matcher's normal limits (design spec, section 9.6). Among
 * items with the same name, the [preferred] ones stand for the others, as an app of the owner's profile does for the same
 * app in the work profile; two preferred items with the same name both stay, for "Which one?". A single near miss is
 * offered as a confirmation.
 */
internal fun <T> pick(spoken: String, items: List<T>, name: (T) -> String, preferred: (T) -> Boolean): Pick<T> {
    val distinct = items.filter { Normalizer.normalize(name(it)).isNotEmpty() }
        .groupBy { Normalizer.normalize(name(it)) }
        .values.flatMap { same -> same.filter(preferred).ifEmpty { same } }
    val candidates = distinct.mapIndexed { index, item -> MatchCandidate(index.toString(), listOf(name(item))) }
    return when (val decision = LabelMatcher.decide(spoken, candidates, Strictness.NORMAL)) {
        is MatchDecision.Found -> Pick.One(distinct[decision.id.toInt()])
        is MatchDecision.Ambiguous -> Pick.Several(decision.ids.take(MAX_SPOKEN_CHOICES).map { distinct[it.toInt()] })
        MatchDecision.NotFound -> Pick.None
    }
}

/** How the answer to a spoken "Which one?" ended: with the index of the chosen name, or with the command's result. */
internal sealed interface Answer {
    data class Chosen(val index: Int) : Answer

    data class Ends(val result: CommandResult) : Answer
}

/**
 * Asks [question], whose [names] are numbered from 1, and reads the answer: a number or one of the names, after [fold],
 * such as "cell" for "mobile". An answer that is neither ends the command and is read as a new utterance; an answer that
 * names two of them ends it with "Say the number."; no answer cancels it.
 */
internal suspend fun askAmong(
    conversation: CommandConversation,
    question: String,
    names: List<String>,
    fold: (String) -> String = { it },
): Answer {
    val answer = conversation.ask(question) ?: return Answer.Ends(CommandResult(Outcome.CANCELLED))
    SpokenNumbers.answer(answer)?.let { number ->
        return if (number in 1..names.size) {
            Answer.Chosen(number - 1)
        } else {
            Answer.Ends(CommandResult(Outcome.FAILED, show = PhoneCommandTexts.noChoice(number)))
        }
    }
    val candidates = names.mapIndexed { index, name -> MatchCandidate(index.toString(), listOf(name)) }
    return when (val found = LabelMatcher.decide(fold(answer), candidates, Strictness.NORMAL)) {
        is MatchDecision.Found -> Answer.Chosen(found.id.toInt())
        // Two offered items with the same name, such as two mobile numbers: only their numbers tell them apart.
        is MatchDecision.Ambiguous -> Answer.Ends(CommandResult(Outcome.FAILED, show = PhoneCommandTexts.SAY_THE_NUMBER))
        MatchDecision.NotFound -> Answer.Ends(CommandResult(Outcome.CANCELLED, passOn = answer))
    }
}
```

In `app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommandTexts.kt`, replace

```kotlin
    // Tasks 2 to 6 of plan 3C add their texts above this line.
```

with

```kotlin
    /** [name] is the owner's words, which Kestrel says and never logs. */
    fun cantFindApp(name: String) = "I can't find an app called $name."

    const val WORK_APPS_TOO_OLD = "I can't open work apps on this version of Android."
    const val NO_WORK_PROFILE = "There's no work profile on this phone."
    const val WORK_APPS_PAUSED = "Work apps are paused."

    /** "Which one? 1, Chrome. 2, Chrome Beta." for apps and contacts; the names are said and kept on the bar. */
    fun whichOne(names: List<String>) = "Which one? " + names.withIndex().joinToString(" ") { "${it.index + 1}, ${it.value}." }

    fun noChoice(number: Int) = "There's no number $number in the list."

    const val SAY_THE_NUMBER = "Say the number."

    // Tasks 2 to 6 of plan 3C add their texts above this line.
```

In `app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommands.kt`, replace

```kotlin
    // Tasks 2 to 6 of plan 3C add their parts above this line.
```

with

```kotlin
    private val apps = AppCommands(environment)
    // Tasks 2 to 6 of plan 3C add their parts above this line.
```

Then replace

```kotlin
            // Tasks 2 to 6 of plan 3C add their commands above this line.
```

with

```kotlin
            is PhoneCommand.OpenApp -> apps.open(screen, command, conversation)
            // Tasks 2 to 6 of plan 3C add their commands above this line.
```

- [ ] **Step 7: Run the JVM tests of the apps to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.commands.PhoneAppsTest`
Expected: `BUILD SUCCESSFUL`, with 4 tests run.

- [ ] **Step 8: Change the spec's row of "Open [app]"**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace

```markdown
| General | Open [app]; Open apps; Open work [app] | Yes. Work apps open through Android's launcher API when a work profile exists. |
```

with

```markdown
| General | Open [app]; Open apps; Open work [app] | Yes. Apps open through Android's launcher API. Work apps open only from a profile that Android names a work profile, which Kestrel can tell from Android 15 on; on Android 13 and 14 it cannot tell a work profile from Secure Folder or a clone profile, so it opens only the owner's apps there. |
```

- [ ] **Step 9: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin :testapp:assembleDebug`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:commands` as in Step 5, `:app` 327 with `PhoneAppsTest` 4, the others as before), lint without issues in every Android module, the audit, and the device tests and the test app compile.

- [ ] **Step 10: Run the new command device tests**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\command-test.ps1 -Serial emulator-5554 -Class AppCommandsTest`
Expected: `BUILD SUCCESSFUL` and "Command tests: 5 run, 0 failed, 0 errors, 0 skipped.", with no warning from the restores at the end. If `openWorkStartsTheWorkProfilesAppAndOpenTheOwnersOne` fails because `pm create-user` makes no profile or Android lists none, or `aPausedWorkProfilesAppOpensAndKestrelSaysThatWorkAppsArePaused` because Android refuses to pause the profile, report it to the controller with the failure message, and do not change the tests.

- [ ] **Step 11: Run all command device tests**

"Open X" now takes phrases that were bare texts before ("open the settings" now names an app); this run checks that no test of plans 3A and 3B used one of them as a bare label.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\command-test.ps1 -Serial emulator-5554`
Expected: `BUILD SUCCESSFUL` and "Command tests: 72 run, 0 failed, 0 errors, 0 skipped." (`AppCommandsTest` 5), with no warning from the restores.

- [ ] **Step 12: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 13: Commit**

```powershell
git add core/commands/src/main/kotlin/dev/kestrel/commands/GrammarPhone.kt core/commands/src/main/kotlin/dev/kestrel/commands/HelpPhone.kt core/commands/src/test/kotlin/dev/kestrel/commands/GrammarPhoneTest.kt app/src/main/kotlin/dev/kestrel/app/commands/AppCommands.kt app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommands.kt app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommandTexts.kt app/src/test/kotlin/dev/kestrel/app/commands/PhoneAppsTest.kt app/src/androidTest/kotlin/dev/kestrel/app/commands/AppCommandsTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(commands): open apps and work apps by voice" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: Wi-Fi and Bluetooth

**Files:**
- Modify: `core/commands/src/main/kotlin/dev/kestrel/commands/GrammarPhone.kt`, `core/commands/src/main/kotlin/dev/kestrel/commands/HelpPhone.kt`, `app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommands.kt`, `app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommandTexts.kt` (at Task 1's anchors)
- Create: `app/src/main/kotlin/dev/kestrel/app/commands/DeviceCommands.kt`
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (Appendix A's row `| Settings | Turn Bluetooth`)
- Test: `core/commands/src/test/kotlin/dev/kestrel/commands/GrammarPhoneTest.kt`, `app/src/test/kotlin/dev/kestrel/app/commands/PhoneDevicesTest.kt` (new), `app/src/androidTest/kotlin/dev/kestrel/app/commands/DeviceCommandsTest.kt` (new)

**Interfaces:**
- Consumes: Task 1's anchors, `SystemControls.start(intent)` and `PhoneTests.SYSTEM_UI`; plan 3A's `PhoneCommand.Radio(radio, on)`, `RadioKind`, `Command.Switch` and `Command.Check` (which stay screen commands for every other name), `ScreenSession.snapshots`, `actions.tap(key)`, `actions.global(GlobalAction.QUICK_SETTINGS | DISMISS_SHADE | BACK)`, `ScreenTargets.names(snapshot, element)`, `chosen(element)` and `still(chosen)` through `CommandEnvironment.targets`, `CommandParser.POLITE_WORDS`, `Normalizer.words` and `normalize`, Android's `Settings.Global.WIFI_ON` and `BluetoothAdapter.isEnabled`, `ScreenSnapshot.element(key)`, `ScreenElement` (`checkable`, `checked`, `enabled`, `clickable`, `packageName`, `windowId`, `viewId`, `parent`, `label`, `text`, `contentDescription`, `editable`), `WindowSummary` and `WindowKind`; the test helpers `CommandTests` (with its rule `only`), `ScreenServiceRule` (`shell`, `awaitElement`, `session.feedbackText`) and `VoiceTests.waitUntil`.
- Produces: the rule `radio` of `GrammarPhone.targeted`; (`:app`) `internal class DeviceCommands(environment)` with `suspend fun radio(screen, radio, on): CommandResult` and, in its companion, `fun namesRadio(snapshot, element, radio): Boolean`; the texts `WIFI_ALREADY_ON`, `WIFI_ALREADY_OFF`, `NO_WIFI_SWITCH`, `WIFI_NOT_CHANGED`, `BLUETOOTH_ALREADY_ON`, `BLUETOOTH_ALREADY_OFF`, `NO_BLUETOOTH_SWITCH`, `BLUETOOTH_NOT_CHANGED`; the help group "Wi-Fi and Bluetooth"; the JVM test class `PhoneDevicesTest` and the device test class `DeviceCommandsTest`, each with the anchor `// Task 4 of plan 3C adds its tests above this line.`.

Design D10 and prototype P4. "Turn on Wi-Fi", "switch Bluetooth off", "enable Bluetooth", "Wi-Fi on" and their variants name a radio, also with "please" or "now" after them and with "blue tooth" as the recogniser may write it; "turn on" with any other name stays plan 3A's switch command. Kestrel reads each radio's state from Android (Wi-Fi's global setting `wifi_on`, Bluetooth's adapter), says on the bar when it is already as asked, and reports success only when Android's state changed within 3 s. Wi-Fi goes through the switch of Android's Internet panel (`Settings.Panel.ACTION_INTERNET_CONNECTIVITY`, started from the accessibility service), because the Quick Settings tile only disconnects. Kestrel acts only in the panel's own window: a window that was not on the screen before the command, of SystemUI (the emulator's dialog) or Settings, with a checkable element whose view ID ends in `wifi_toggle` or that is named after Wi-Fi; so never on Quick Settings' tile, also while the shade is open and no app window is active, and never on a switch of the app in front. It clicks the switch, after checking it again, and closes the panel with its "Done" button, or with Back only while the panel still shows. Bluetooth goes through its tile in SystemUI's Quick Settings, and through the main switch of the Bluetooth settings page that Kestrel opens, in that page's new window of Settings, when Quick Settings shows no tile. The panel, the shade and the page close also when the command is cancelled, during its waits or while the panel, the shade or the page opens; after a cancel in the first moments of a start, Kestrel waits up to 1 s for the new window, so that it can close it. A switch counts as the radio's when one of its names, its row's or those of the elements inside it is the radio's name, alone or after "use", before a comma that adds a detail ("Wi-Fi,AndroidWifi", "Bluetooth, Pixel Buds"), so that "Wi-Fi calling" is not Wi-Fi's switch. This is narrower than D10's "starts with the radio's name", which would take "Wi-Fi calling" too. Neither radio needs a permission. The device tests turn both radios off and on through the emulator's own panel and tiles; the tile test first checks that Quick Settings shows the Bluetooth tile, and the other removes the tile and waits until Quick Settings shows none before it speaks, so that each test shows which path ran. `command-test.ps1` puts back the radios and the tiles. As every command test class of this plan, `DeviceCommandsTest` has `CommandTests.only` as its first rule.

- [ ] **Step 1: Write the failing JVM tests**

In `core/commands/src/test/kotlin/dev/kestrel/commands/GrammarPhoneTest.kt`, replace

```kotlin
    // Tasks 2 to 6 of plan 3C add their tests above this line.
```

with

```kotlin
    @Test
    fun wifiAndBluetoothTurnOnAndOffAndOtherSwitchesStayScreenCommands() {
        assertParses(PhoneCommand.Radio(RadioKind.WIFI, on = true), "Turn on Wi-Fi.", "Turn the WiFi on", "Enable wifi", "Wi-Fi on.")
        assertParses(PhoneCommand.Radio(RadioKind.WIFI, on = false), "Turn off Wi‑Fi.", "Switch off the Wi-Fi", "Disable Wi Fi")
        assertParses(PhoneCommand.Radio(RadioKind.BLUETOOTH, on = true), "Turn on Bluetooth.", "Switch Bluetooth on.", "Bluetooth on")
        assertParses(PhoneCommand.Radio(RadioKind.BLUETOOTH, on = false), "Turn off Bluetooth.", "Turn Bluetooth off", "disable bluetooth")
        assertParses(PhoneCommand.Radio(RadioKind.WIFI, on = true), "Turn on Wi-Fi please.", "turn on wifi now")
        assertParses(PhoneCommand.Radio(RadioKind.BLUETOOTH, on = false), "Turn off Bluetooth now.", "Turn off blue tooth.")
        assertParses(Command.Switch(Target.Label("the flashlight"), on = true), "Turn on the flashlight.")
        assertParses(Command.Switch(null, on = false), "Switch off.")
        // "Turn off" alone is Kestrel's own verb, whose name the recogniser may lose; it is no command.
        assertBare("Turn off.")
        assertParses(Command.Check(Target.Label("Wi-Fi"), on = true), "check Wi-Fi")
    }

    // Tasks 2 to 6 of plan 3C add their tests above this line.
```

Create `app/src/test/kotlin/dev/kestrel/app/commands/PhoneDevicesTest.kt`:

```kotlin
package dev.kestrel.app.commands

import dev.kestrel.commands.RadioKind
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneDevicesTest {
    private val panel = WindowSummary(1, WindowKind.SYSTEM, packageName = "com.android.systemui", bounds = IntRect(0, 0, 1080, 2400))
    private val bounds = IntRect(0, 0, 100, 100)

    private fun element(key: String, parent: Int? = null, label: String? = null, description: String? = null, checkable: Boolean = false) =
        ScreenElement(
            key = key, windowId = 1, parent = parent, label = label, contentDescription = description, bounds = bounds,
            checkable = checkable, clickable = checkable,
        )

    @Test
    fun aRadioSwitchIsNamedByItselfItsRowOrWhatIsInsideIt() {
        val elements = listOf(
            element("tile", checkable = true),
            element("tile-name", parent = 0, description = "Wi-Fi,AndroidWifi"),
            element("bt-row", label = "Use Bluetooth"),
            element("bt-switch", parent = 2, checkable = true),
            element("panel", description = "Wi\u2011Fi", checkable = true),
            element("flashlight", checkable = true, label = "Flashlight"),
            element("wifi-calling", checkable = true, label = "Wi-Fi calling preferences"),
            element("bt-tile", checkable = true),
            element("bt-tile-name", parent = 7, description = "Bluetooth, Pixel Buds"),
        )
        val snapshot = ScreenSnapshot(0, listOf(panel), elements)
        fun names(key: String, radio: RadioKind) = DeviceCommands.namesRadio(snapshot, snapshot.element(key)!!, radio)

        assertTrue(names("tile", RadioKind.WIFI))
        assertTrue(names("bt-switch", RadioKind.BLUETOOTH))
        assertTrue(names("panel", RadioKind.WIFI))
        assertFalse(names("tile", RadioKind.BLUETOOTH))
        assertFalse(names("flashlight", RadioKind.WIFI))
        assertTrue(names("bt-tile", RadioKind.BLUETOOTH))
        assertFalse(names("wifi-calling", RadioKind.WIFI))
    }

    // Task 4 of plan 3C adds its tests above this line.
}
```

- [ ] **Step 2: Write the failing device tests**

Create `app/src/androidTest/kotlin/dev/kestrel/app/commands/DeviceCommandsTest.kt`:

```kotlin
package dev.kestrel.app.commands

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.RadioKind
import dev.kestrel.screen.act.GlobalAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Wi-Fi and Bluetooth (design D10, prototype P4), on the emulator's own Internet panel, Quick Settings and Bluetooth
 * settings. command-test.ps1 puts back the radios, the volumes, the ringer mode and the Quick Settings tiles.
 */
@RunWith(AndroidJUnit4::class)
class DeviceCommandsTest {
    // Before ScreenServiceRule, as in plan 3A's classes: a run without kestrelCommands=true skips each test before the rule
    // switches the accessibility services, and before @Before and @After run.
    @get:Rule(order = 0)
    val only = CommandTests.only

    @get:Rule(order = 1)
    val screen = ScreenServiceRule()

    @Before
    fun start() {
        CommandTests.start()
        screen.shell("input keyevent KEYCODE_HOME")
    }

    @After
    fun stop() {
        CommandTests.stop()
        screen.shell("input keyevent KEYCODE_HOME")
    }

    @Test
    fun wifiTurnsOffAndOnThroughTheInternetPanel() = runBlocking<Unit> {
        try {
            if (!radio("wifi_on")) assertEquals(Outcome.DONE, CommandTests.say("turn on Wi-Fi").outcome)

            assertEquals(Outcome.DONE, CommandTests.say("turn off Wi-Fi").outcome)
            assertTrue("Wi-Fi is still on", VoiceTests.waitUntil(5_000) { !radio("wifi_on") })
            awaitGone { it.viewId?.endsWith(":id/wifi_toggle") == true }
            assertEquals(Outcome.DONE, CommandTests.say("turn Wi-Fi off").outcome)
            awaitBar(PhoneCommandTexts.WIFI_ALREADY_OFF)
            assertEquals(Outcome.DONE, CommandTests.say("turn on Wi-Fi").outcome)

            assertTrue("Wi-Fi is still off", VoiceTests.waitUntil(5_000) { radio("wifi_on") })
        } finally {
            screen.shell("cmd wifi set-wifi-enabled enabled")
        }
    }

    @Test
    fun bluetoothTurnsOffAndOnThroughItsQuickSettingsTile() = runBlocking<Unit> {
        try {
            if (!radio("bluetooth_on")) screen.shell("cmd bluetooth_manager enable")
            assertTrue(VoiceTests.waitUntil(5_000) { radio("bluetooth_on") })
            // So that the test fails, instead of passing through the settings page, when Quick Settings has no tile.
            awaitBluetoothTile(shown = true)

            assertEquals(Outcome.DONE, CommandTests.say("turn off Bluetooth").outcome)
            assertTrue("Bluetooth is still on", VoiceTests.waitUntil(5_000) { !radio("bluetooth_on") })
            awaitGone { it.packageName == PhoneTests.SYSTEM_UI && it.viewId?.contains(":id/qs_tile") == true }
            assertEquals(Outcome.DONE, CommandTests.say("switch Bluetooth on").outcome)

            assertTrue("Bluetooth is still off", VoiceTests.waitUntil(5_000) { radio("bluetooth_on") })
        } finally {
            screen.shell("cmd bluetooth_manager enable")
        }
    }

    @Test
    fun bluetoothUsesItsSettingsPageWhenQuickSettingsHasNoTile() = runBlocking<Unit> {
        val tiles = screen.shell("settings get secure sysui_qs_tiles").trim()
        assertTrue("Quick Settings has no Bluetooth tile to remove", "bt" in tiles.split(','))
        try {
            if (!radio("bluetooth_on")) screen.shell("cmd bluetooth_manager enable")
            assertTrue(VoiceTests.waitUntil(5_000) { radio("bluetooth_on") })
            screen.shell("settings put secure sysui_qs_tiles ${tiles.split(',').filter { it != "bt" }.joinToString(",")}")
            // SystemUI applies the new list a moment later; until then the command would still find the tile.
            awaitBluetoothTile(shown = false)

            assertEquals(Outcome.DONE, CommandTests.say("turn off Bluetooth").outcome)

            assertTrue("Bluetooth is still on", VoiceTests.waitUntil(5_000) { !radio("bluetooth_on") })
        } finally {
            screen.shell("settings put secure sysui_qs_tiles $tiles")
            screen.shell("cmd bluetooth_manager enable")
        }
    }

    // Task 4 of plan 3C adds its tests above this line.

    // Opens Quick Settings, waits until it shows Bluetooth's tile or until it shows none, and closes it again.
    private suspend fun awaitBluetoothTile(shown: Boolean) {
        screen.session.actions.global(GlobalAction.QUICK_SETTINGS)
        try {
            screen.awaitElement { it.packageName == PhoneTests.SYSTEM_UI && it.viewId?.contains(":id/qs_tile") == true }
            withTimeout(5_000) {
                while (true) {
                    val snapshot = screen.session.snapshots.current().snapshot
                    val tile = snapshot.elements.any {
                        it.packageName == PhoneTests.SYSTEM_UI && it.checkable && DeviceCommands.namesRadio(snapshot, it, RadioKind.BLUETOOTH)
                    }
                    if (tile == shown) break
                    delay(100)
                }
            }
        } finally {
            screen.session.actions.global(GlobalAction.DISMISS_SHADE)
        }
    }

    // Whether the radio is on, from Android's global setting wifi_on or bluetooth_on, which is 0 when it is off.
    private fun radio(setting: String): Boolean = screen.shell("settings get global $setting").trim() != "0"

    private suspend fun awaitBar(text: String) = withTimeout(5_000) { while (screen.session.feedbackText != text) delay(50) }

    // Waits until no element on the screen matches [predicate], such as the closed panel's switch.
    private suspend fun awaitGone(predicate: (dev.kestrel.screen.model.ScreenElement) -> Boolean) = withTimeout(5_000) {
        while (screen.session.snapshots.current().snapshot.elements.any(predicate)) delay(100)
    }
}
```

- [ ] **Step 3: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 --continue :core:commands:test --tests dev.kestrel.commands.GrammarPhoneTest :app:testDebugUnitTest --tests dev.kestrel.app.commands.PhoneDevicesTest`
Expected: `BUILD FAILED`: `GrammarPhoneTest` runs 5 tests and `wifiAndBluetoothTurnOnAndOffAndOtherSwitchesStayScreenCommands` fails at "Turn on Wi-Fi.", which is still plan 3A's switch command; `:app`'s tests do not compile: "Unresolved reference 'DeviceCommands'".

- [ ] **Step 4: Write the phrases and the command list's group**

In `core/commands/src/main/kotlin/dev/kestrel/commands/GrammarPhone.kt`, replace

```kotlin
        // Tasks 2 to 6 of plan 3C add their rules above this line.
```

with

```kotlin
        radio(words)?.let { return it }
        // Tasks 2 to 6 of plan 3C add their rules above this line.
```

Then replace

```kotlin
    // Tasks 2 to 6 of plan 3C add their functions above this line.
```

with

```kotlin
    private val SWITCH_VERBS = setOf("turn", "switch")
    private val ON_OFF = mapOf("on" to true, "off" to false)
    private val ENABLE = mapOf("enable" to true, "disable" to false)

    // "Turn on Wi-Fi", "turn Bluetooth off", "enable Bluetooth", "Wi-Fi off", also with "please" or "now" after them.
    // Normalizer writes "Wi-Fi" as "wi fi", and the recogniser may write "Bluetooth" as "blue tooth".
    private fun radio(said: List<String>): Command? {
        val words = said.dropLastWhile { it in CommandParser.POLITE_WORDS }
        val (on, rest) = when {
            words.size >= 3 && words[0] in SWITCH_VERBS && words[1] in ON_OFF -> ON_OFF.getValue(words[1]) to words.drop(2)
            words.size >= 3 && words[0] in SWITCH_VERBS && words.last() in ON_OFF ->
                ON_OFF.getValue(words.last()) to words.subList(1, words.size - 1)
            words.size >= 2 && words[0] in ENABLE -> ENABLE.getValue(words[0]) to words.drop(1)
            words.size >= 2 && words.last() in ON_OFF -> ON_OFF.getValue(words.last()) to words.dropLast(1)
            else -> return null
        }
        val radio = when (rest.dropWhile { it == "the" || it == "my" }) {
            listOf("wi", "fi"), listOf("wifi") -> RadioKind.WIFI
            listOf("bluetooth"), listOf("blue", "tooth") -> RadioKind.BLUETOOTH
            else -> return null
        }
        return PhoneCommand.Radio(radio, on)
    }

    // Tasks 2 to 6 of plan 3C add their functions above this line.
```

In `core/commands/src/main/kotlin/dev/kestrel/commands/HelpPhone.kt`, replace

```kotlin
        // Tasks 2 to 6 of plan 3C add their groups above this line.
```

with

```kotlin
        HelpGroup("Wi-Fi and Bluetooth", listOf("turn on Wi-Fi", "turn off Bluetooth")),
        // Tasks 2 to 6 of plan 3C add their groups above this line.
```

- [ ] **Step 5: Run the tests of `:core:commands` to verify that they pass**

Run: `scripts\gradle.ps1 :core:commands:test`
Expected: `BUILD SUCCESSFUL`, with 93 tests (`GrammarPhoneTest` 5); `GrammarScreenTest` still passes, so plan 3A's switch phrases keep their meaning.

- [ ] **Step 6: Write the radios**

Create `app/src/main/kotlin/dev/kestrel/app/commands/DeviceCommands.kt`:

```kotlin
package dev.kestrel.app.commands

import android.bluetooth.BluetoothManager
import android.content.Intent
import android.provider.Settings
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.RadioKind
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.GlobalAction
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone's settings (design spec, Appendix A; prototype P4): Wi-Fi through the Internet panel's switch, and Bluetooth
 * through its Quick Settings tile or its settings page.
 */
internal class DeviceCommands(environment: CommandEnvironment) {
    private val bluetooth: BluetoothManager? = environment.context.getSystemService(BluetoothManager::class.java)
    private val resolver = environment.context.contentResolver
    private val targets = environment.targets

    suspend fun radio(screen: ScreenSession, radio: RadioKind, on: Boolean): CommandResult =
        if (radio == RadioKind.WIFI) wifi(screen, on) else bluetooth(screen, on)

    /**
     * Wi-Fi through the switch of Android's Internet panel, which turns the radio off and on; the Quick Settings tile only
     * disconnects (prototype P4). Kestrel reads Wi-Fi's state from Android and opens the panel only when it differs. It
     * acts only in the panel's own window: a window that was not on the screen before, of SystemUI or Settings, that shows
     * the switch; so never on Quick Settings' tile or on a switch of the app in front. It clicks the switch, waits up to
     * 3 s for Android's state to change, and closes the panel with its "Done" button, or Back while the panel still shows,
     * also when the command is cancelled.
     */
    private suspend fun wifi(screen: ScreenSession, on: Boolean): CommandResult {
        if (wifiOn() == on) {
            return CommandResult(Outcome.DONE, show = if (on) PhoneCommandTexts.WIFI_ALREADY_ON else PhoneCommandTexts.WIFI_ALREADY_OFF)
        }
        val before = screen.snapshots.current().snapshot.windows.map { it.id }.toSet()
        // The start is inside the try, so that a cancel during its wait closes the panel too; until the start fails, the
        // panel may still be on its way.
        var opening = true
        try {
            if (screen.system.start(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) is ActionResult.Failed) {
                opening = false
                return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NO_WIFI_SWITCH)
            }
            val toggle = await(screen) { snapshot, element ->
                element.windowId !in before && element.packageName in PANEL_PACKAGES && element.checkable && element.enabled &&
                    element.viewId?.contains(":id/qs_tile") != true &&
                    (element.viewId?.endsWith(":id/wifi_toggle") == true || namesRadio(snapshot, element, RadioKind.WIFI))
            } ?: return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NO_WIFI_SWITCH)
            // Checked again right before the click: the panel may have changed since the snapshot.
            val element = targets.still(ScreenTargets.chosen(toggle))
            if (element != null && element.checked != on) screen.actions.tap(element.key)
            val changed = awaitState(on) { wifiOn() }
            return if (changed) {
                CommandResult(Outcome.DONE, repeatable = true)
            } else {
                CommandResult(Outcome.FAILED, show = PhoneCommandTexts.WIFI_NOT_CHANGED)
            }
        } finally {
            withContext(NonCancellable) { closePanel(screen, before, opening) }
        }
    }

    // Closes the panel that the command opened, with its "Done" button, or with Back while its window still shows, so that
    // Back never acts on the owner's app.
    private suspend fun closePanel(screen: ScreenSession, before: Set<Int>, opening: Boolean) {
        val panel = newWindow(screen, before, PANEL_PACKAGES, opening) ?: return
        val snapshot = screen.snapshots.current().snapshot
        val done = snapshot.elements.firstOrNull { element ->
            element.windowId == panel.id && element.enabled && element.clickable &&
                (element.viewId?.endsWith(":id/done_button") == true ||
                    ScreenTargets.names(snapshot, element).any { Normalizer.normalize(it) == "done" })
        }
        if (done == null || screen.actions.tap(done.key) is ActionResult.Failed) screen.actions.global(GlobalAction.BACK)
    }

    /**
     * Bluetooth through its Quick Settings tile, whose switch turns the radio off and on (prototype P4), or, when Quick
     * Settings shows no tile, through the main switch of the Bluetooth settings page that Kestrel opens. Kestrel acts only
     * on SystemUI's tile or on that page's window, never on a switch of the app in front, and the state is Android's, read
     * for up to 3 s. The shade and the page close also when the command is cancelled.
     */
    private suspend fun bluetooth(screen: ScreenSession, on: Boolean): CommandResult {
        if (bluetoothOn() == on) {
            return CommandResult(Outcome.DONE, show = if (on) PhoneCommandTexts.BLUETOOTH_ALREADY_ON else PhoneCommandTexts.BLUETOOTH_ALREADY_OFF)
        }
        try {
            screen.actions.global(GlobalAction.QUICK_SETTINGS)
            val tile = await(screen) { snapshot, element ->
                element.packageName == SYSTEM_UI && element.checkable && element.enabled && namesRadio(snapshot, element, RadioKind.BLUETOOTH)
            }
            if (tile != null) {
                val element = targets.still(ScreenTargets.chosen(tile))
                if (element != null && element.checked != on) screen.actions.tap(element.key)
                return bluetoothResult(awaitState(on) { bluetoothOn() })
            }
        } finally {
            withContext(NonCancellable) { screen.actions.global(GlobalAction.DISMISS_SHADE) }
        }
        return bluetoothPage(screen, on)
    }

    // The main switch of the Bluetooth settings page, in the window of Settings that the command opened.
    private suspend fun bluetoothPage(screen: ScreenSession, on: Boolean): CommandResult {
        val before = screen.snapshots.current().snapshot.windows.map { it.id }.toSet()
        var opening = true
        try {
            if (screen.system.start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) is ActionResult.Failed) {
                opening = false
                return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NO_BLUETOOTH_SWITCH)
            }
            val main = await(screen) { snapshot, element ->
                element.windowId !in before && element.packageName == SETTINGS && element.checkable && element.enabled &&
                    snapshot.windows.any { it.id == element.windowId && it.kind == WindowKind.APPLICATION } &&
                    namesRadio(snapshot, element, RadioKind.BLUETOOTH)
            } ?: return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NO_BLUETOOTH_SWITCH)
            val element = targets.still(ScreenTargets.chosen(main))
            if (element != null && element.checked != on) screen.actions.tap(element.key)
            return bluetoothResult(awaitState(on) { bluetoothOn() })
        } finally {
            withContext(NonCancellable) {
                // Back only while the page that the command opened shows.
                if (newWindow(screen, before, setOf(SETTINGS), opening) != null) screen.actions.global(GlobalAction.BACK)
            }
        }
    }

    private fun bluetoothResult(changed: Boolean) = if (changed) {
        CommandResult(Outcome.DONE, repeatable = true)
    } else {
        CommandResult(Outcome.FAILED, show = PhoneCommandTexts.BLUETOOTH_NOT_CHANGED)
    }

    // Android's own states; no permission is needed to read them at Kestrel's target SDK. Wi-Fi's global setting is 1 when
    // Wi-Fi is on, and 2 when it is on while airplane mode is on.
    private fun wifiOn(): Boolean? = try {
        Settings.Global.getInt(resolver, Settings.Global.WIFI_ON).let { it == 1 || it == 2 }
    } catch (e: Settings.SettingNotFoundException) {
        null
    }

    private fun bluetoothOn(): Boolean? = bluetooth?.adapter?.isEnabled

    // Waits up to 3 s for Android's [state] of a radio to become [on].
    private suspend fun awaitState(on: Boolean, state: () -> Boolean?): Boolean =
        withTimeoutOrNull(RADIO_MILLIS) {
            while (state() != on) delay(POLL_MILLIS)
            true
        } ?: false

    // A window of [packages] that was not on the screen [before]; while it may still be [opening], after a cancel in the
    // first moments of its start, Kestrel waits up to 1 s for it.
    private suspend fun newWindow(screen: ScreenSession, before: Set<Int>, packages: Set<String>, opening: Boolean): WindowSummary? {
        suspend fun find() = screen.snapshots.current().snapshot.windows.firstOrNull { it.id !in before && it.packageName in packages }
        if (!opening) return find()
        return withTimeoutOrNull(APPEAR_MILLIS) {
            var window = find()
            while (window == null) {
                delay(POLL_MILLIS)
                window = find()
            }
            window
        }
    }

    // Waits up to 3 s for an element of the current snapshot that [accept] takes.
    private suspend fun await(screen: ScreenSession, accept: (ScreenSnapshot, ScreenElement) -> Boolean): ScreenElement? =
        withTimeoutOrNull(PANEL_MILLIS) {
            var found: ScreenElement? = null
            while (found == null) {
                val snapshot = screen.snapshots.current().snapshot
                found = snapshot.elements.firstOrNull { accept(snapshot, it) }
                if (found == null) delay(POLL_MILLIS)
            }
            found
        }

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        private const val SETTINGS = "com.android.settings"
        private const val PANEL_MILLIS = 3_000L
        private const val RADIO_MILLIS = 3_000L
        private const val POLL_MILLIS = 100L
        private const val APPEAR_MILLIS = 1_000L

        // The Internet panel: SystemUI's dialog on the emulator's Android 17 (prototype P4), a panel of Settings elsewhere.
        private val PANEL_PACKAGES = setOf(SYSTEM_UI, SETTINGS)

        /**
         * Whether [element] is named after [radio]: one of its names, those of its nearest labelled ancestor or those of
         * the elements inside it, is the radio's name, alone or after "use", in folded words, before a comma that may add
         * a detail, as the "Wi-Fi,AndroidWifi" inside a Quick Settings tile and "Bluetooth, Pixel Buds" do. So "Wi-Fi
         * calling" is no Wi-Fi switch.
         */
        fun namesRadio(snapshot: ScreenSnapshot, element: ScreenElement, radio: RadioKind): Boolean {
            val index = snapshot.elements.indexOf(element)
            val inside = snapshot.elements.filter { candidate -> index >= 0 && within(snapshot, candidate, index) }
            val names = ScreenTargets.names(snapshot, element) +
                inside.flatMap { listOfNotNull(it.label, it.text.takeUnless { _ -> it.editable }, it.contentDescription) }
            val radioNames = if (radio == RadioKind.WIFI) setOf(listOf("wi", "fi"), listOf("wifi")) else setOf(listOf("bluetooth"), listOf("blue", "tooth"))
            return names.any { name ->
                Normalizer.words(name.substringBefore(',')).let { if (it.firstOrNull() == "use") it.drop(1) else it } in radioNames
            }
        }

        // Whether [candidate] lies inside the element at [index], at most 20 levels down.
        private fun within(snapshot: ScreenSnapshot, candidate: ScreenElement, index: Int): Boolean {
            var parent = candidate.parent
            var depth = 0
            while (parent != null && depth < 20) {
                if (parent == index) return true
                parent = snapshot.elements.getOrNull(parent)?.parent
                depth++
            }
            return false
        }
    }
}
```

In `app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommandTexts.kt`, replace

```kotlin
    // Tasks 2 to 6 of plan 3C add their texts above this line.
```

with

```kotlin
    const val WIFI_ALREADY_ON = "Wi-Fi is already on."
    const val WIFI_ALREADY_OFF = "Wi-Fi is already off."
    const val NO_WIFI_SWITCH = "I can't find the Wi-Fi switch."
    const val WIFI_NOT_CHANGED = "Wi-Fi didn't change."
    const val BLUETOOTH_ALREADY_ON = "Bluetooth is already on."
    const val BLUETOOTH_ALREADY_OFF = "Bluetooth is already off."
    const val NO_BLUETOOTH_SWITCH = "I can't find the Bluetooth switch."
    const val BLUETOOTH_NOT_CHANGED = "Bluetooth didn't change."

    // Tasks 2 to 6 of plan 3C add their texts above this line.
```

In `app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommands.kt`, replace

```kotlin
    // Tasks 2 to 6 of plan 3C add their parts above this line.
```

with

```kotlin
    private val device = DeviceCommands(environment)
    // Tasks 2 to 6 of plan 3C add their parts above this line.
```

Then replace

```kotlin
            // Tasks 2 to 6 of plan 3C add their commands above this line.
```

with

```kotlin
            is PhoneCommand.Radio -> device.radio(screen, command.radio, command.on)
            // Tasks 2 to 6 of plan 3C add their commands above this line.
```

- [ ] **Step 7: Run the JVM tests of the radios to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.commands.PhoneDevicesTest`
Expected: `BUILD SUCCESSFUL`, with 1 test run.

- [ ] **Step 8: Change the spec's row of Wi-Fi and Bluetooth**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace

```markdown
| Settings | Turn Bluetooth on or off; Turn Wi-Fi on or off | Yes, through the Quick Settings tiles |
```

with

```markdown
| Settings | Turn Bluetooth on or off; Turn Wi-Fi on or off | Yes. Wi-Fi through the switch of Android's Internet panel, because its Quick Settings tile only disconnects; Bluetooth through its Quick Settings tile, or through the switch of the Bluetooth settings when Quick Settings has no tile. |
```

- [ ] **Step 9: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin :testapp:assembleDebug`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:commands` as in Step 5, `:app` 328 with `PhoneDevicesTest` 1, the others as before), lint without issues in every Android module, the audit, and the device tests and the test app compile.

- [ ] **Step 10: Run the new command device tests**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\command-test.ps1 -Serial emulator-5554 -Class DeviceCommandsTest`
Expected: `BUILD SUCCESSFUL` and "Command tests: 3 run, 0 failed, 0 errors, 0 skipped.", with no warning from the restores at the end. If a test fails because the Internet panel shows no Wi-Fi switch or Quick Settings no Bluetooth tile, report it to the controller with the failure message, and do not change the tests.

- [ ] **Step 11: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 12: Commit**

```powershell
git add core/commands/src/main/kotlin/dev/kestrel/commands/GrammarPhone.kt core/commands/src/main/kotlin/dev/kestrel/commands/HelpPhone.kt core/commands/src/test/kotlin/dev/kestrel/commands/GrammarPhoneTest.kt app/src/main/kotlin/dev/kestrel/app/commands/DeviceCommands.kt app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommands.kt app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommandTexts.kt app/src/test/kotlin/dev/kestrel/app/commands/PhoneDevicesTest.kt app/src/androidTest/kotlin/dev/kestrel/app/commands/DeviceCommandsTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(commands): Wi-Fi and Bluetooth by voice" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: Volume, mute, silence, the media keys and magnification

**Files:**
- Modify: `core/commands/src/main/kotlin/dev/kestrel/commands/GrammarPhone.kt`, `core/commands/src/main/kotlin/dev/kestrel/commands/HelpPhone.kt`, `app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommands.kt`, `app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommandTexts.kt` (at Task 1's anchors, and `PhoneCommands.run`'s first line)
- Modify (replace Task 3's file whole): `app/src/main/kotlin/dev/kestrel/app/commands/DeviceCommands.kt`
- Create: `testapp/src/main/kotlin/dev/kestrel/testapp/MediaActivity.kt`
- Modify: `testapp/src/main/AndroidManifest.xml` (at the marker `        <!-- Plan 3C: phone fixtures -->`)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (9.3's row `| Back, home, recent apps`, Appendix A's row `| Settings | Turn volume`)
- Test: `core/commands/src/test/kotlin/dev/kestrel/commands/GrammarPhoneTest.kt`, `app/src/test/kotlin/dev/kestrel/app/commands/PhoneDevicesTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/commands/DeviceCommandsTest.kt`

**Interfaces:**
- Consumes: Task 1's anchors and `SystemControls.pan(dx, dy)` and `magnifiedCentre()`, Task 3's `DeviceCommands` and test classes; plan 3A's `PhoneCommand.Volume(stream, up)`, `Mute(stream, on)`, `Silence`, `Media(play)`, `Magnify(action)`, `Pan(direction)`, `StreamKind`, `MagnifyAction`, `Direction`, `Command.Scroll(direction, target)` and `Command.Slider` (which keeps "increase" without "volume"), `ScreenSession.actions.zoom(scale, x, y)`, `resetZoom()`, `zoomScale`, `ScreenSession.screenBounds`, `InstantCommands`, which passes only `Volume`, `Mute`, `Silence` and `Media` to the parts while the accessibility service is off, and the test app's `fixturePage(title)` and `addStatus()`.
- Produces: the fixed phrases of silence, the media keys, magnification and panning, and the rules `volume` and `mute` of `GrammarPhone.targeted`; `DeviceCommands.withoutScreen(command): CommandResult?`, `magnify(screen, action)` and `pan(screen, direction)`, and in its companion `streamOf(kind, mode)`, `volumeResult(before, after, min, max, up)`, `muteRefusal(kind)` and `panBy(direction, width, height, scale)`; the texts `HIGHEST`, `LOWEST`, `VOLUME_NOT_CHANGED`, `RINGER_WHILE_SILENT`, `MUTE_ALARM`, `MUTE_CALL`, `ALREADY_MUTED`, `NOT_MUTED`, `ALREADY_VIBRATE`, `ALREADY_SILENT`, `NO_MEDIA`, `NOT_MAGNIFIED`, `MOST_MAGNIFIED`; the help groups "Volume and media" and "Magnification"; (`:testapp`) `MediaActivity` (title "Media fixture", status "Playing" or "Paused").

Design D10, prototype P4 and decision 5. A volume phrase needs the word "volume", one direction and at most one stream ("volume up", "turn the alarm volume down", "increase the ringer volume"); any other word leaves it to the other grammars, so "increase" alone stays plan 3A's slider. Kestrel changes the named stream by one step with `adjustStreamVolume` and `FLAG_SHOW_UI`, so that Android's volume panel shows it, and reads the index back after 300 ms; without a stream it changes the call stream during another app's call and the music stream otherwise. "Mute" and "unmute" act on the music stream, or the ring stream when the owner names it ("the ringer", and "the phone", which means its ringer here); Android ignores a mute of the alarm and the call volume, so Kestrel says that it can't and changes nothing. "Silence", "vibrate" and "silent mode" set the ringer to vibrate, because the true silent mode needs notification-policy access, which Kestrel never asks for, and "unmute the ringer", "turn off vibrate" and "vibrate off" go back to normal. "Media play" and "media pause" send the media key itself (play or pause, not the toggle) through `AudioManager.dispatchMediaKeyEvent`, which reaches the media session that played last; after 1 s Kestrel checks whether music plays. These four commands work without the accessibility service too, so the phone part takes them before it asks for the screen. "Zoom in" magnifies 2 times at the screen's centre and one step more, up to 8 times, around the magnified centre; "zoom out" goes one step back; "stop magnification" ends it. "Pan left" and the other directions, and "scroll" in a direction without a target while the screen is magnified, move the magnified area by a third of what it shows. This task adds the test app's `MediaActivity`, a quiet tone with an active media session; its view IDs are not needed, so `ids_phone.xml` comes with Task 6's fixture.

- [ ] **Step 1: Write the failing JVM tests**

In `core/commands/src/test/kotlin/dev/kestrel/commands/GrammarPhoneTest.kt`, replace

```kotlin
    // Tasks 2 to 6 of plan 3C add their tests above this line.
```

with

```kotlin
    @Test
    fun theVolumeNeedsTheWordVolumeAndAtMostOneStream() {
        assertParses(PhoneCommand.Volume(null, up = true), "Volume up.", "Turn the volume up.", "Turn up the volume", "Increase the volume.")
        assertParses(PhoneCommand.Volume(null, up = false), "Volume down", "Lower the volume.", "turn the volume down a bit")
        assertParses(PhoneCommand.Volume(StreamKind.MEDIA, up = true), "Turn up the media volume.", "Music volume up.")
        assertParses(PhoneCommand.Volume(StreamKind.ALARM, up = false), "Turn the alarm volume down.", "Decrease the alarm volume")
        assertParses(PhoneCommand.Volume(StreamKind.RING, up = true), "Ring volume up.", "Increase the ringer volume.")
        assertParses(PhoneCommand.Volume(StreamKind.CALL, up = false), "Call volume down.", "Turn down the phone volume.")
        assertParses(PhoneCommand.Volume(StreamKind.NOTIFICATION, up = true), "Notification volume up.")
        assertBare("Volume.", "Turn up the volume and the lights.", "The volume is too high.", "volume up down", "media alarm volume up")
        assertParses(Command.Slider(increase = true, target = null), "Increase.")
    }

    @Test
    fun muteSilenceAndMediaKeys() {
        assertParses(PhoneCommand.Mute(null, on = true), "Mute.", "Mute the sound.")
        assertParses(PhoneCommand.Mute(null, on = false), "Unmute.", "Un-mute.")
        assertParses(PhoneCommand.Mute(StreamKind.RING, on = true), "Mute the ringer.", "Mute the ringtone")
        assertParses(PhoneCommand.Mute(StreamKind.RING, on = false), "Unmute the ringer.", "Turn off vibrate.", "Vibrate off.")
        assertParses(PhoneCommand.Mute(StreamKind.RING, on = true), "Mute the phone.")
        assertParses(PhoneCommand.Mute(StreamKind.ALARM, on = true), "Mute the alarm.", "Mute the alarm volume.")
        assertParses(PhoneCommand.Mute(StreamKind.MEDIA, on = true), "Mute the music.")
        assertParses(PhoneCommand.Silence, "Silence.", "Vibrate.", "Silent mode.", "Silence the phone.", "Turn on vibrate.")
        assertParses(PhoneCommand.Media(play = true), "Media play.", "Play media.", "Play the music.")
        assertParses(PhoneCommand.Media(play = false), "Media pause.", "Pause the music.", "Pause media.")
        assertBare("Play.", "Pause.", "Mute the TV.", "Play the next song for me.")
    }

    @Test
    fun magnificationZoomsPansAndStops() {
        assertParses(PhoneCommand.Magnify(MagnifyAction.IN), "Start magnification.", "Start zooming.", "Magnify.", "Zoom in.", "Enhance.")
        assertParses(PhoneCommand.Magnify(MagnifyAction.OUT), "Zoom out.")
        assertParses(PhoneCommand.Magnify(MagnifyAction.STOP), "Stop magnification.", "Stop zooming.", "Cancel zooming.")
        assertParses(PhoneCommand.Pan(Direction.LEFT), "Pan left.", "Move left.", "Go left.")
        assertParses(PhoneCommand.Pan(Direction.UP), "Pan up.", "Move up")
        assertParses(Command.Scroll(Direction.DOWN, null), "Scroll down.")
    }

    // Tasks 2 to 6 of plan 3C add their tests above this line.
```

In `app/src/test/kotlin/dev/kestrel/app/commands/PhoneDevicesTest.kt`, replace

```kotlin
import dev.kestrel.commands.RadioKind
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
```

with

```kotlin
import android.media.AudioManager
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Direction
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.RadioKind
import dev.kestrel.commands.StreamKind
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
```

Then replace

```kotlin
    // Task 4 of plan 3C adds its tests above this line.
```

with

```kotlin
    @Test
    fun aVolumeWithoutAStreamIsTheCallsDuringACallAndTheMusicsOtherwise() {
        assertEquals(AudioManager.STREAM_MUSIC, DeviceCommands.streamOf(null, AudioManager.MODE_NORMAL))
        assertEquals(AudioManager.STREAM_VOICE_CALL, DeviceCommands.streamOf(null, AudioManager.MODE_IN_CALL))
        assertEquals(AudioManager.STREAM_VOICE_CALL, DeviceCommands.streamOf(null, AudioManager.MODE_IN_COMMUNICATION))
        assertEquals(AudioManager.STREAM_RING, DeviceCommands.streamOf(StreamKind.RING, AudioManager.MODE_IN_CALL))
        assertEquals(AudioManager.STREAM_ALARM, DeviceCommands.streamOf(StreamKind.ALARM, AudioManager.MODE_NORMAL))
    }

    @Test
    fun aVolumeStepIsReadBackAndAnUnchangedOneSaysWhy() {
        assertEquals(CommandResult(Outcome.DONE, repeatable = true), DeviceCommands.volumeResult(3, 7, 1, 7, up = true))
        assertEquals(CommandResult(Outcome.DONE, show = PhoneCommandTexts.HIGHEST), DeviceCommands.volumeResult(15, 15, 0, 15, up = true))
        assertEquals(CommandResult(Outcome.DONE, show = PhoneCommandTexts.LOWEST), DeviceCommands.volumeResult(1, 1, 1, 7, up = false))
        assertEquals(CommandResult(Outcome.FAILED, show = PhoneCommandTexts.VOLUME_NOT_CHANGED), DeviceCommands.volumeResult(5, 5, 0, 15, up = true))
    }

    @Test
    fun theAlarmAndTheCallVolumeAreNeverMuted() {
        assertEquals(PhoneCommandTexts.MUTE_ALARM, DeviceCommands.muteRefusal(StreamKind.ALARM))
        assertEquals(PhoneCommandTexts.MUTE_CALL, DeviceCommands.muteRefusal(StreamKind.CALL))
        assertNull(DeviceCommands.muteRefusal(StreamKind.RING))
        assertNull(DeviceCommands.muteRefusal(null))
    }

    @Test
    fun panMovesAThirdOfTheVisibleArea() {
        assertEquals(-180 to 0, DeviceCommands.panBy(Direction.LEFT, 1080, 2400, 2f))
        assertEquals(0 to 400, DeviceCommands.panBy(Direction.DOWN, 1080, 2400, 2f))
        assertEquals(0 to -267, DeviceCommands.panBy(Direction.UP, 1080, 2400, 3f))
    }
```

- [ ] **Step 2: Write the failing device tests and the media fixture**

In `app/src/androidTest/kotlin/dev/kestrel/app/commands/DeviceCommandsTest.kt`, replace

```kotlin
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.RadioKind
import dev.kestrel.screen.act.GlobalAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Wi-Fi and Bluetooth (design D10, prototype P4), on the emulator's own Internet panel, Quick Settings and Bluetooth
 * settings. command-test.ps1 puts back the radios, the volumes, the ringer mode and the Quick Settings tiles.
 */
```

with

```kotlin
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.RadioKind
import dev.kestrel.screen.act.GlobalAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phone's settings (design D10, prototype P4): Wi-Fi and Bluetooth on the emulator's own Internet panel, Quick
 * Settings and Bluetooth settings, the volumes, mute, silence, the media keys with the test app's player, and
 * magnification. command-test.ps1 puts back the radios, the volumes, the ringer mode, the magnification and the Quick
 * Settings tiles.
 */
```

Then replace

```kotlin
    // Task 4 of plan 3C adds its tests above this line.
```

with

```kotlin
    private val audio = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(AudioManager::class.java)

    @Test
    fun volumeStepsTheMusicStreamOrTheStreamThatTheOwnerNames() {
        // In the middle of each stream, so that a step can go both ways.
        for (stream in listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_ALARM)) {
            screen.shell("cmd media_session volume --stream $stream --set ${audio.getStreamMaxVolume(stream) / 2}")
        }
        val music = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val alarm = audio.getStreamVolume(AudioManager.STREAM_ALARM)

        assertEquals(Outcome.DONE, CommandTests.say("volume up").outcome)
        assertTrue(audio.getStreamVolume(AudioManager.STREAM_MUSIC) > music)
        assertEquals(Outcome.DONE, CommandTests.say("turn the volume down").outcome)
        assertEquals(music, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals(Outcome.DONE, CommandTests.say("turn the alarm volume down").outcome)
        assertTrue(audio.getStreamVolume(AudioManager.STREAM_ALARM) < alarm)
        assertEquals(music, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun muteAndUnmuteTheMusicButNeverTheAlarm() {
        screen.shell("cmd media_session volume --stream 3 --set ${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2}")

        assertEquals(Outcome.DONE, CommandTests.say("mute").outcome)
        assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC))
        assertEquals(Outcome.DONE, CommandTests.say("unmute").outcome)
        assertTrue(!audio.isStreamMute(AudioManager.STREAM_MUSIC))
        assertEquals(Outcome.FAILED, CommandTests.say("mute the alarm").outcome)
        CommandTests.awaitSaid(PhoneCommandTexts.MUTE_ALARM)
        assertTrue(!audio.isStreamMute(AudioManager.STREAM_ALARM))
    }

    @Test
    fun silencePutsThePhoneOnVibrateAndUnmuteTheRingerEndsIt() {
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)

        assertEquals(Outcome.DONE, CommandTests.say("silence").outcome)
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audio.ringerMode)
        assertEquals(Outcome.DONE, CommandTests.say("unmute the ringer").outcome)

        assertTrue(VoiceTests.waitUntil(2_000) { audio.ringerMode == AudioManager.RINGER_MODE_NORMAL })
    }

    @Test
    fun mediaPauseAndPlayReachAPlayerInTheBackground() = runBlocking<Unit> {
        try {
            screen.open("MediaActivity", "Media fixture")
            screen.awaitElement { it.label == "Playing" }
            screen.shell("input keyevent KEYCODE_HOME")
            assertTrue(VoiceTests.waitUntil(3_000) { audio.isMusicActive })

            assertEquals(Outcome.DONE, CommandTests.say("media pause").outcome)
            assertTrue(!audio.isMusicActive)
            assertEquals(Outcome.DONE, CommandTests.say("play media").outcome)
            assertTrue(audio.isMusicActive)
            assertEquals(Outcome.DONE, CommandTests.say("pause the music").outcome)
            // Nothing plays now, so a pause reaches no player.
            assertEquals(Outcome.FAILED, CommandTests.say("media pause").outcome)
            awaitBar(PhoneCommandTexts.NO_MEDIA)
        } finally {
            screen.shell("am force-stop $FIXTURES")
        }
    }

    @Test
    fun magnificationZoomsPansWithScrollAndStops() = runBlocking<Unit> {
        screen.open("CommandsActivity", "Commands fixture")

        assertEquals(Outcome.DONE, CommandTests.say("zoom in").outcome)
        assertEquals(2f, screen.session.actions.zoomScale)
        val (x, _) = checkNotNull(screen.session.system.magnifiedCentre())
        assertEquals(Outcome.DONE, CommandTests.say("pan left").outcome)
        val left = checkNotNull(screen.session.system.magnifiedCentre()).first
        assertTrue("The magnified area did not move left", left < x - 100)
        // While the screen is magnified, "scroll" without a target pans.
        assertEquals(Outcome.DONE, CommandTests.say("scroll right").outcome)
        assertTrue("The magnified area did not move right", checkNotNull(screen.session.system.magnifiedCentre()).first > left + 100)
        assertEquals(Outcome.DONE, CommandTests.say("magnify").outcome)
        assertEquals(3f, screen.session.actions.zoomScale)
        assertEquals(Outcome.DONE, CommandTests.say("zoom out").outcome)
        assertEquals(2f, screen.session.actions.zoomScale)
        assertEquals(Outcome.DONE, CommandTests.say("stop magnification").outcome)

        assertEquals(1f, screen.session.actions.zoomScale)
    }
```

Create `testapp/src/main/kotlin/dev/kestrel/testapp/MediaActivity.kt`:

```kotlin
package dev.kestrel.testapp

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.sin

/**
 * A player for "media play" and "media pause" (design spec, Appendix A; prototype P4): a quiet 440 Hz tone with an active
 * media session, which Android's media keys reach also while the fixture is in the background. It starts playing, and the
 * status line says "Playing" or "Paused".
 */
class MediaActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var tone: AudioTrack
    private lateinit var session: MediaSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = fixturePage("Media fixture")
        status = page.addStatus()
        tone = toneTrack()
        session = MediaSession(this, "KestrelFixture").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()

                override fun onPause() = pause()
            })
            isActive = true
        }
        play()
    }

    override fun onDestroy() {
        session.release()
        tone.release()
        super.onDestroy()
    }

    private fun play() {
        tone.play()
        session.setPlaybackState(state(PlaybackState.STATE_PLAYING))
        status.text = "Playing"
    }

    private fun pause() {
        tone.pause()
        session.setPlaybackState(state(PlaybackState.STATE_PAUSED))
        status.text = "Paused"
    }

    private fun state(state: Int): PlaybackState = PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
        .setState(state, 0, 1f)
        .build()

    // One second of the tone, 440 whole waves, which the track plays in a loop.
    private fun toneTrack(): AudioTrack {
        val samples = ShortArray(RATE) { (sin(2 * PI * 440 * it / RATE) * 0.05 * Short.MAX_VALUE).toInt().toShort() }
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
            .apply {
                write(samples, 0, samples.size)
                setLoopPoints(0, samples.size, -1)
            }
    }

    private companion object {
        const val RATE = 44_100
    }
}
```

In `testapp/src/main/AndroidManifest.xml`, replace

```xml
        <!-- Plan 3C: phone fixtures -->
```

with

```xml
        <activity
            android:name=".MediaActivity"
            android:exported="true" />
        <!-- Plan 3C: phone fixtures -->
```

- [ ] **Step 3: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 --continue :core:commands:test --tests dev.kestrel.commands.GrammarPhoneTest :app:testDebugUnitTest --tests dev.kestrel.app.commands.PhoneDevicesTest`
Expected: `BUILD FAILED`: `GrammarPhoneTest` runs 8 tests, of which the 3 new ones fail, at "Volume up.", "Mute." and "Start magnification.", which are still bare texts; `:app`'s tests do not compile: "Unresolved reference 'streamOf'".

- [ ] **Step 4: Write the phrases and the command list's groups**

In `core/commands/src/main/kotlin/dev/kestrel/commands/GrammarPhone.kt`, replace

```kotlin
        // Tasks 2 to 6 of plan 3C add their phrases above this line.
```

with

```kotlin
        phrases(
            PhoneCommand.Silence,
            "silence", "silence the phone", "silence my phone", "silent mode", "vibrate", "vibrate mode", "vibrate only",
            "set to vibrate", "turn on vibrate",
        )
        // Back from vibrate to sound, as "unmute the ringer" does.
        phrases(PhoneCommand.Mute(StreamKind.RING, on = false), "turn off vibrate", "turn vibrate off", "vibrate off")
        phrases(PhoneCommand.Media(play = true), "media play", "play media", "play the music", "play music", "resume media", "resume the music")
        phrases(PhoneCommand.Media(play = false), "media pause", "pause media", "pause the music", "pause music")
        phrases(
            PhoneCommand.Magnify(MagnifyAction.IN),
            "start magnification", "start zooming", "magnify", "magnify the screen", "zoom in", "enhance", "magnification on",
        )
        phrases(PhoneCommand.Magnify(MagnifyAction.OUT), "zoom out")
        phrases(
            PhoneCommand.Magnify(MagnifyAction.STOP),
            "stop magnification", "stop zooming", "cancel zooming", "stop magnifying", "end magnification", "magnification off",
        )
        phrases(PhoneCommand.Pan(Direction.UP), "pan up", "move up", "go up")
        phrases(PhoneCommand.Pan(Direction.DOWN), "pan down", "move down", "go down")
        phrases(PhoneCommand.Pan(Direction.LEFT), "pan left", "move left", "go left")
        phrases(PhoneCommand.Pan(Direction.RIGHT), "pan right", "move right", "go right")
        // Tasks 2 to 6 of plan 3C add their phrases above this line.
```

Then replace

```kotlin
        // Tasks 2 to 6 of plan 3C add their rules above this line.
```

with

```kotlin
        volume(words)?.let { return it }
        mute(words)?.let { return it }
        // Tasks 2 to 6 of plan 3C add their rules above this line.
```

Then replace

```kotlin
    // Tasks 2 to 6 of plan 3C add their functions above this line.
```

with

```kotlin
    private val STREAMS = mapOf(
        "media" to StreamKind.MEDIA, "music" to StreamKind.MEDIA, "alarm" to StreamKind.ALARM, "alarms" to StreamKind.ALARM,
        "phone" to StreamKind.CALL, "call" to StreamKind.CALL, "ring" to StreamKind.RING, "ringer" to StreamKind.RING,
        "ringtone" to StreamKind.RING, "notification" to StreamKind.NOTIFICATION, "notifications" to StreamKind.NOTIFICATION,
    )
    private val UP = setOf("up", "increase", "raise", "louder")
    private val DOWN = setOf("down", "decrease", "lower", "reduce", "quieter")
    private val VOLUME_WORDS = setOf("turn", "the", "my", "volume", "of", "a", "bit", "little")

    // "Volume up", "turn the alarm volume down", "increase the ringer volume": "volume", one direction, at most one stream
    // and no other words.
    private fun volume(words: List<String>): Command? {
        if ("volume" !in words) return null
        var up: Boolean? = null
        var stream: StreamKind? = null
        for (word in words) {
            when (word) {
                in UP -> if (up == false) return null else up = true
                in DOWN -> if (up == true) return null else up = false
                in STREAMS -> stream = oneStream(stream, STREAMS.getValue(word)) ?: return null
                in VOLUME_WORDS -> Unit
                else -> return null
            }
        }
        return up?.let { PhoneCommand.Volume(stream, it) }
    }

    private val MUTE_WORDS = setOf("the", "my", "volume", "sound", "audio")

    // "Mute the phone" means its ringer, not the call volume.
    private val MUTE_STREAMS = STREAMS + ("phone" to StreamKind.RING)

    // "Mute", "unmute the ringer", "mute the alarm volume".
    private fun mute(words: List<String>): Command? {
        val (on, from) = when {
            words.firstOrNull() == "mute" -> true to 1
            words.firstOrNull() == "unmute" -> false to 1
            words.size >= 2 && words[0] == "un" && words[1] == "mute" -> false to 2
            else -> return null
        }
        var stream: StreamKind? = null
        for (word in words.drop(from)) {
            when (word) {
                in MUTE_STREAMS -> stream = oneStream(stream, MUTE_STREAMS.getValue(word)) ?: return null
                in MUTE_WORDS -> Unit
                else -> return null
            }
        }
        return PhoneCommand.Mute(stream, on)
    }

    // Two different streams in one phrase name none.
    private fun oneStream(current: StreamKind?, named: StreamKind): StreamKind? = if (current == null || current == named) named else null

    // Tasks 2 to 6 of plan 3C add their functions above this line.
```

In `core/commands/src/main/kotlin/dev/kestrel/commands/HelpPhone.kt`, replace

```kotlin
        // Tasks 2 to 6 of plan 3C add their groups above this line.
```

with

```kotlin
        HelpGroup(
            "Volume and media",
            listOf("volume up", "turn down the alarm volume", "mute", "unmute the ringer", "silence", "media play", "media pause"),
        ),
        HelpGroup("Magnification", listOf("zoom in", "pan left", "scroll down", "zoom out", "stop magnification")),
        // Tasks 2 to 6 of plan 3C add their groups above this line.
```

- [ ] **Step 5: Run the tests of `:core:commands` to verify that they pass**

Run: `scripts\gradle.ps1 :core:commands:test`
Expected: `BUILD SUCCESSFUL`, with 96 tests (`GrammarPhoneTest` 8); `GrammarScreenTest` still passes, so "scroll down", "increase" and plan 3A's other phrases keep their meaning.

- [ ] **Step 6: Write the volumes, the media keys and magnification**

Replace the whole contents of `app/src/main/kotlin/dev/kestrel/app/commands/DeviceCommands.kt` with:

```kotlin
package dev.kestrel.app.commands

import android.bluetooth.BluetoothManager
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.view.KeyEvent
import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Direction
import dev.kestrel.commands.MagnifyAction
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.PhoneCommand
import dev.kestrel.commands.RadioKind
import dev.kestrel.commands.StreamKind
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.GlobalAction
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * The phone's settings, media and magnification (design spec, Appendix A; prototype P4): Wi-Fi through the Internet
 * panel's switch, Bluetooth through its Quick Settings tile or its settings page, volumes through Android's audio API, the
 * media keys, and Android's magnification.
 */
internal class DeviceCommands(environment: CommandEnvironment) {
    private val audio: AudioManager? = environment.context.getSystemService(AudioManager::class.java)
    private val bluetooth: BluetoothManager? = environment.context.getSystemService(BluetoothManager::class.java)
    private val resolver = environment.context.contentResolver
    private val targets = environment.targets

    /** The commands that work without Kestrel's accessibility service: volume, mute, silence and the media keys. */
    suspend fun withoutScreen(command: Command): CommandResult? = when (command) {
        is PhoneCommand.Volume -> volume(command.stream, command.up)
        is PhoneCommand.Mute -> mute(command.stream, command.on)
        PhoneCommand.Silence -> silence()
        is PhoneCommand.Media -> media(command.play)
        else -> null
    }

    suspend fun radio(screen: ScreenSession, radio: RadioKind, on: Boolean): CommandResult =
        if (radio == RadioKind.WIFI) wifi(screen, on) else bluetooth(screen, on)

    /**
     * Wi-Fi through the switch of Android's Internet panel, which turns the radio off and on; the Quick Settings tile only
     * disconnects (prototype P4). Kestrel reads Wi-Fi's state from Android and opens the panel only when it differs. It
     * acts only in the panel's own window: a window that was not on the screen before, of SystemUI or Settings, that shows
     * the switch; so never on Quick Settings' tile or on a switch of the app in front. It clicks the switch, waits up to
     * 3 s for Android's state to change, and closes the panel with its "Done" button, or Back while the panel still shows,
     * also when the command is cancelled.
     */
    private suspend fun wifi(screen: ScreenSession, on: Boolean): CommandResult {
        if (wifiOn() == on) {
            return CommandResult(Outcome.DONE, show = if (on) PhoneCommandTexts.WIFI_ALREADY_ON else PhoneCommandTexts.WIFI_ALREADY_OFF)
        }
        val before = screen.snapshots.current().snapshot.windows.map { it.id }.toSet()
        // The start is inside the try, so that a cancel during its wait closes the panel too; until the start fails, the
        // panel may still be on its way.
        var opening = true
        try {
            if (screen.system.start(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)) is ActionResult.Failed) {
                opening = false
                return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NO_WIFI_SWITCH)
            }
            val toggle = await(screen) { snapshot, element ->
                element.windowId !in before && element.packageName in PANEL_PACKAGES && element.checkable && element.enabled &&
                    element.viewId?.contains(":id/qs_tile") != true &&
                    (element.viewId?.endsWith(":id/wifi_toggle") == true || namesRadio(snapshot, element, RadioKind.WIFI))
            } ?: return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NO_WIFI_SWITCH)
            // Checked again right before the click: the panel may have changed since the snapshot.
            val element = targets.still(ScreenTargets.chosen(toggle))
            if (element != null && element.checked != on) screen.actions.tap(element.key)
            val changed = awaitState(on) { wifiOn() }
            return if (changed) {
                CommandResult(Outcome.DONE, repeatable = true)
            } else {
                CommandResult(Outcome.FAILED, show = PhoneCommandTexts.WIFI_NOT_CHANGED)
            }
        } finally {
            withContext(NonCancellable) { closePanel(screen, before, opening) }
        }
    }

    // Closes the panel that the command opened, with its "Done" button, or with Back while its window still shows, so that
    // Back never acts on the owner's app.
    private suspend fun closePanel(screen: ScreenSession, before: Set<Int>, opening: Boolean) {
        val panel = newWindow(screen, before, PANEL_PACKAGES, opening) ?: return
        val snapshot = screen.snapshots.current().snapshot
        val done = snapshot.elements.firstOrNull { element ->
            element.windowId == panel.id && element.enabled && element.clickable &&
                (element.viewId?.endsWith(":id/done_button") == true ||
                    ScreenTargets.names(snapshot, element).any { Normalizer.normalize(it) == "done" })
        }
        if (done == null || screen.actions.tap(done.key) is ActionResult.Failed) screen.actions.global(GlobalAction.BACK)
    }

    /**
     * Bluetooth through its Quick Settings tile, whose switch turns the radio off and on (prototype P4), or, when Quick
     * Settings shows no tile, through the main switch of the Bluetooth settings page that Kestrel opens. Kestrel acts only
     * on SystemUI's tile or on that page's window, never on a switch of the app in front, and the state is Android's, read
     * for up to 3 s. The shade and the page close also when the command is cancelled.
     */
    private suspend fun bluetooth(screen: ScreenSession, on: Boolean): CommandResult {
        if (bluetoothOn() == on) {
            return CommandResult(Outcome.DONE, show = if (on) PhoneCommandTexts.BLUETOOTH_ALREADY_ON else PhoneCommandTexts.BLUETOOTH_ALREADY_OFF)
        }
        try {
            screen.actions.global(GlobalAction.QUICK_SETTINGS)
            val tile = await(screen) { snapshot, element ->
                element.packageName == SYSTEM_UI && element.checkable && element.enabled && namesRadio(snapshot, element, RadioKind.BLUETOOTH)
            }
            if (tile != null) {
                val element = targets.still(ScreenTargets.chosen(tile))
                if (element != null && element.checked != on) screen.actions.tap(element.key)
                return bluetoothResult(awaitState(on) { bluetoothOn() })
            }
        } finally {
            withContext(NonCancellable) { screen.actions.global(GlobalAction.DISMISS_SHADE) }
        }
        return bluetoothPage(screen, on)
    }

    // The main switch of the Bluetooth settings page, in the window of Settings that the command opened.
    private suspend fun bluetoothPage(screen: ScreenSession, on: Boolean): CommandResult {
        val before = screen.snapshots.current().snapshot.windows.map { it.id }.toSet()
        var opening = true
        try {
            if (screen.system.start(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) is ActionResult.Failed) {
                opening = false
                return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NO_BLUETOOTH_SWITCH)
            }
            val main = await(screen) { snapshot, element ->
                element.windowId !in before && element.packageName == SETTINGS && element.checkable && element.enabled &&
                    snapshot.windows.any { it.id == element.windowId && it.kind == WindowKind.APPLICATION } &&
                    namesRadio(snapshot, element, RadioKind.BLUETOOTH)
            } ?: return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NO_BLUETOOTH_SWITCH)
            val element = targets.still(ScreenTargets.chosen(main))
            if (element != null && element.checked != on) screen.actions.tap(element.key)
            return bluetoothResult(awaitState(on) { bluetoothOn() })
        } finally {
            withContext(NonCancellable) {
                // Back only while the page that the command opened shows.
                if (newWindow(screen, before, setOf(SETTINGS), opening) != null) screen.actions.global(GlobalAction.BACK)
            }
        }
    }

    private fun bluetoothResult(changed: Boolean) = if (changed) {
        CommandResult(Outcome.DONE, repeatable = true)
    } else {
        CommandResult(Outcome.FAILED, show = PhoneCommandTexts.BLUETOOTH_NOT_CHANGED)
    }

    // Android's own states; no permission is needed to read them at Kestrel's target SDK. Wi-Fi's global setting is 1 when
    // Wi-Fi is on, and 2 when it is on while airplane mode is on.
    private fun wifiOn(): Boolean? = try {
        Settings.Global.getInt(resolver, Settings.Global.WIFI_ON).let { it == 1 || it == 2 }
    } catch (e: Settings.SettingNotFoundException) {
        null
    }

    private fun bluetoothOn(): Boolean? = bluetooth?.adapter?.isEnabled

    // Waits up to 3 s for Android's [state] of a radio to become [on].
    private suspend fun awaitState(on: Boolean, state: () -> Boolean?): Boolean =
        withTimeoutOrNull(RADIO_MILLIS) {
            while (state() != on) delay(POLL_MILLIS)
            true
        } ?: false

    // A window of [packages] that was not on the screen [before]; while it may still be [opening], after a cancel in the
    // first moments of its start, Kestrel waits up to 1 s for it.
    private suspend fun newWindow(screen: ScreenSession, before: Set<Int>, packages: Set<String>, opening: Boolean): WindowSummary? {
        suspend fun find() = screen.snapshots.current().snapshot.windows.firstOrNull { it.id !in before && it.packageName in packages }
        if (!opening) return find()
        return withTimeoutOrNull(APPEAR_MILLIS) {
            var window = find()
            while (window == null) {
                delay(POLL_MILLIS)
                window = find()
            }
            window
        }
    }

    // Waits up to 3 s for an element of the current snapshot that [accept] takes.
    private suspend fun await(screen: ScreenSession, accept: (ScreenSnapshot, ScreenElement) -> Boolean): ScreenElement? =
        withTimeoutOrNull(PANEL_MILLIS) {
            var found: ScreenElement? = null
            while (found == null) {
                val snapshot = screen.snapshots.current().snapshot
                found = snapshot.elements.firstOrNull { accept(snapshot, it) }
                if (found == null) delay(POLL_MILLIS)
            }
            found
        }

    /**
     * Volume up or down by one step of the stream (design D10): the stream that the owner names, else the call stream
     * during another app's call and the media stream otherwise. Android's own volume panel shows the change.
     */
    private suspend fun volume(kind: StreamKind?, up: Boolean): CommandResult {
        val audio = audio ?: return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.VOLUME_NOT_CHANGED)
        val stream = streamOf(kind, audio.mode)
        val before = audio.getStreamVolume(stream)
        try {
            audio.adjustStreamVolume(stream, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        } catch (e: SecurityException) {
            return CommandResult(Outcome.FAILED, say = PhoneCommandTexts.RINGER_WHILE_SILENT)
        }
        delay(READ_BACK_MILLIS)
        val after = audio.getStreamVolume(stream)
        return volumeResult(before, after, audio.getStreamMinVolume(stream), audio.getStreamMaxVolume(stream), up)
    }

    /**
     * "Mute" and "unmute": the media stream, or the ring stream when the owner names it. Android silently ignores a mute of
     * the alarm and the call volume (prototype P4), so Kestrel says so and changes nothing (decision 5).
     */
    private suspend fun mute(kind: StreamKind?, on: Boolean): CommandResult {
        if (on) muteRefusal(kind)?.let { return CommandResult(Outcome.FAILED, say = it) }
        val audio = audio ?: return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.VOLUME_NOT_CHANGED)
        val stream = streamOf(kind ?: StreamKind.MEDIA, AudioManager.MODE_NORMAL)
        if (audio.isStreamMute(stream) == on) {
            return CommandResult(Outcome.DONE, show = if (on) PhoneCommandTexts.ALREADY_MUTED else PhoneCommandTexts.NOT_MUTED)
        }
        try {
            audio.adjustStreamVolume(stream, if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
        } catch (e: SecurityException) {
            return CommandResult(Outcome.FAILED, say = PhoneCommandTexts.RINGER_WHILE_SILENT)
        }
        delay(READ_BACK_MILLIS)
        return if (audio.isStreamMute(stream) == on) {
            CommandResult(Outcome.DONE)
        } else {
            CommandResult(Outcome.FAILED, show = PhoneCommandTexts.VOLUME_NOT_CHANGED)
        }
    }

    /**
     * "Silence" puts the phone on vibrate. The true silent mode needs notification-policy access, which Kestrel never asks
     * for, and Android also refuses to leave it without that access.
     */
    private suspend fun silence(): CommandResult {
        val audio = audio ?: return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.VOLUME_NOT_CHANGED)
        when (audio.ringerMode) {
            AudioManager.RINGER_MODE_VIBRATE -> return CommandResult(Outcome.DONE, show = PhoneCommandTexts.ALREADY_VIBRATE)
            AudioManager.RINGER_MODE_SILENT -> return CommandResult(Outcome.DONE, show = PhoneCommandTexts.ALREADY_SILENT)
        }
        try {
            audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        } catch (e: SecurityException) {
            return CommandResult(Outcome.FAILED, say = PhoneCommandTexts.RINGER_WHILE_SILENT)
        }
        delay(READ_BACK_MILLIS)
        return if (audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            CommandResult(Outcome.DONE)
        } else {
            CommandResult(Outcome.FAILED, show = PhoneCommandTexts.VOLUME_NOT_CHANGED)
        }
    }

    /**
     * "Media play" and "media pause": the media key itself, not a toggle, through Android's audio API, which reaches the
     * player of the media session that played last, also while Kestrel speaks (prototype P4). After 1 s Kestrel checks
     * whether music plays; Kestrel's own replies do not count as music.
     */
    private suspend fun media(play: Boolean): CommandResult {
        val audio = audio ?: return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NO_MEDIA)
        val playing = audio.isMusicActive
        val key = if (play) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PAUSE
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        delay(MEDIA_MILLIS)
        val reacted = if (play) audio.isMusicActive else playing && !audio.isMusicActive
        return if (reacted) CommandResult(Outcome.DONE, repeatable = true) else CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NO_MEDIA)
    }

    /**
     * Magnification (design D10): "zoom in" magnifies 2 times at the screen's centre, and one step more, up to 8 times,
     * while the screen is magnified; "zoom out" goes one step back and ends at 1; "stop magnification" ends it.
     */
    suspend fun magnify(screen: ScreenSession, action: MagnifyAction): CommandResult {
        val scale = screen.actions.zoomScale
        val result = when (action) {
            MagnifyAction.IN -> when {
                scale <= 1f -> screen.screenBounds.let { screen.actions.zoom(FIRST_SCALE, it.centerX, it.centerY) }
                scale >= MAX_SCALE -> return CommandResult(Outcome.DONE, show = PhoneCommandTexts.MOST_MAGNIFIED)
                else -> zoomAround(screen, minOf(MAX_SCALE, scale.toInt() + 1f))
            }
            MagnifyAction.OUT -> when {
                scale <= 1f -> return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NOT_MAGNIFIED)
                scale - 1f <= 1f -> screen.actions.resetZoom()
                else -> zoomAround(screen, scale - 1f)
            }
            MagnifyAction.STOP -> if (scale <= 1f) return CommandResult(Outcome.DONE) else screen.actions.resetZoom()
        }
        return done(result)
    }

    // A new scale around the centre of the magnified area, so that the place that the owner looks at stays.
    private suspend fun zoomAround(screen: ScreenSession, scale: Float): ActionResult {
        val (x, y) = screen.system.magnifiedCentre() ?: screen.screenBounds.let { it.centerX.toFloat() to it.centerY.toFloat() }
        return screen.actions.zoom(scale, x.roundToInt(), y.roundToInt())
    }

    /** "Pan left", and "scroll" without a target while magnified: a third of the visible width or height (design D10). */
    suspend fun pan(screen: ScreenSession, direction: Direction): CommandResult {
        val scale = screen.actions.zoomScale
        if (scale <= 1f) return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.NOT_MAGNIFIED)
        val (dx, dy) = panBy(direction, screen.screenBounds.width, screen.screenBounds.height, scale)
        return done(screen.system.pan(dx, dy))
    }

    private fun done(result: ActionResult): CommandResult = when (result) {
        is ActionResult.Done -> CommandResult(Outcome.DONE, repeatable = true)
        // The reasons of the screen layer are fixed texts without the screen's words.
        is ActionResult.Failed -> CommandResult(Outcome.FAILED, show = result.reason)
    }

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        private const val SETTINGS = "com.android.settings"
        private const val PANEL_MILLIS = 3_000L
        private const val RADIO_MILLIS = 3_000L
        private const val POLL_MILLIS = 100L
        private const val APPEAR_MILLIS = 1_000L
        private const val READ_BACK_MILLIS = 300L
        private const val MEDIA_MILLIS = 1_000L
        private const val FIRST_SCALE = 2f
        private const val MAX_SCALE = 8f

        // The Internet panel: SystemUI's dialog on the emulator's Android 17 (prototype P4), a panel of Settings elsewhere.
        private val PANEL_PACKAGES = setOf(SYSTEM_UI, SETTINGS)

        /**
         * Whether [element] is named after [radio]: one of its names, those of its nearest labelled ancestor or those of
         * the elements inside it, is the radio's name, alone or after "use", in folded words, before a comma that may add
         * a detail, as the "Wi-Fi,AndroidWifi" inside a Quick Settings tile and "Bluetooth, Pixel Buds" do. So "Wi-Fi
         * calling" is no Wi-Fi switch.
         */
        fun namesRadio(snapshot: ScreenSnapshot, element: ScreenElement, radio: RadioKind): Boolean {
            val index = snapshot.elements.indexOf(element)
            val inside = snapshot.elements.filter { candidate -> index >= 0 && within(snapshot, candidate, index) }
            val names = ScreenTargets.names(snapshot, element) +
                inside.flatMap { listOfNotNull(it.label, it.text.takeUnless { _ -> it.editable }, it.contentDescription) }
            val radioNames = if (radio == RadioKind.WIFI) setOf(listOf("wi", "fi"), listOf("wifi")) else setOf(listOf("bluetooth"), listOf("blue", "tooth"))
            return names.any { name ->
                Normalizer.words(name.substringBefore(',')).let { if (it.firstOrNull() == "use") it.drop(1) else it } in radioNames
            }
        }

        // Whether [candidate] lies inside the element at [index], at most 20 levels down.
        private fun within(snapshot: ScreenSnapshot, candidate: ScreenElement, index: Int): Boolean {
            var parent = candidate.parent
            var depth = 0
            while (parent != null && depth < 20) {
                if (parent == index) return true
                parent = snapshot.elements.getOrNull(parent)?.parent
                depth++
            }
            return false
        }

        /** The stream of [kind], or without one the call stream during another app's call and the media stream otherwise. */
        fun streamOf(kind: StreamKind?, mode: Int): Int = when (kind) {
            null -> if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
                AudioManager.STREAM_VOICE_CALL
            } else {
                AudioManager.STREAM_MUSIC
            }
            StreamKind.MEDIA -> AudioManager.STREAM_MUSIC
            StreamKind.RING -> AudioManager.STREAM_RING
            StreamKind.ALARM -> AudioManager.STREAM_ALARM
            StreamKind.CALL -> AudioManager.STREAM_VOICE_CALL
            StreamKind.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
        }

        /** What a volume step ended with, read back from Android: one stream's step can jump several indices. */
        fun volumeResult(before: Int, after: Int, min: Int, max: Int, up: Boolean): CommandResult = when {
            after != before -> CommandResult(Outcome.DONE, repeatable = true)
            up && before >= max -> CommandResult(Outcome.DONE, show = PhoneCommandTexts.HIGHEST)
            !up && before <= min -> CommandResult(Outcome.DONE, show = PhoneCommandTexts.LOWEST)
            // Do Not Disturb, or Android 17's limits on volume changes from the background (research r16).
            else -> CommandResult(Outcome.FAILED, show = PhoneCommandTexts.VOLUME_NOT_CHANGED)
        }

        /** Why Kestrel does not mute the stream of [kind], or null when it may. */
        fun muteRefusal(kind: StreamKind?): String? = when (kind) {
            StreamKind.ALARM -> PhoneCommandTexts.MUTE_ALARM
            StreamKind.CALL -> PhoneCommandTexts.MUTE_CALL
            else -> null
        }

        /** How far "pan" moves the magnified area: a third of the visible width or height, in screen pixels. */
        fun panBy(direction: Direction, width: Int, height: Int, scale: Float): Pair<Int, Int> {
            val dx = (width / scale / 3).roundToInt()
            val dy = (height / scale / 3).roundToInt()
            return when (direction) {
                Direction.UP -> 0 to -dy
                Direction.DOWN -> 0 to dy
                Direction.LEFT -> -dx to 0
                Direction.RIGHT -> dx to 0
            }
        }
    }
}
```

In `app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommandTexts.kt`, replace

```kotlin
    // Tasks 2 to 6 of plan 3C add their texts above this line.
```

with

```kotlin
    const val HIGHEST = "The volume is already at its highest."
    const val LOWEST = "The volume is already at its lowest."
    const val VOLUME_NOT_CHANGED = "Android didn't change the volume."
    const val RINGER_WHILE_SILENT = "Android doesn't let me change the ringer while the phone is silent."
    const val MUTE_ALARM = "Android doesn't let me mute the alarm volume."
    const val MUTE_CALL = "Android doesn't let me mute the call volume."
    const val ALREADY_MUTED = "It's already muted."
    const val NOT_MUTED = "It isn't muted."
    const val ALREADY_VIBRATE = "The phone is already on vibrate."
    const val ALREADY_SILENT = "The phone is already silent."
    const val NO_MEDIA = "No media app reacted."
    const val NOT_MAGNIFIED = "The screen is not magnified."
    const val MOST_MAGNIFIED = "The screen is magnified as much as it can be."

    // Tasks 2 to 6 of plan 3C add their texts above this line.
```

In `app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommands.kt`, replace

```kotlin
    override suspend fun run(command: Command, heard: String, conversation: CommandConversation): CommandResult? {
        val screen = environment.screen() ?: return null
```

with

```kotlin
    override suspend fun run(command: Command, heard: String, conversation: CommandConversation): CommandResult? {
        // Without Kestrel's accessibility service, InstantCommands passes only these, which need no screen.
        device.withoutScreen(command)?.let { return it }
        val screen = environment.screen() ?: return null
```

Then replace

```kotlin
            // Tasks 2 to 6 of plan 3C add their commands above this line.
```

with

```kotlin
            is PhoneCommand.Magnify -> device.magnify(screen, command.action)
            is PhoneCommand.Pan -> device.pan(screen, command.direction)
            // While the screen is magnified, "scroll" without a target moves the magnified area (design D10).
            is Command.Scroll -> if (command.target == null && screen.actions.zoomScale > 1f) device.pan(screen, command.direction) else null
            // Tasks 2 to 6 of plan 3C add their commands above this line.
```

- [ ] **Step 7: Run the JVM tests of the device commands to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.commands.PhoneDevicesTest`
Expected: `BUILD SUCCESSFUL`, with 5 tests run.

- [ ] **Step 8: Change the spec's rows of the global actions and the volumes**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace

```markdown
| Back, home, recent apps, notifications, Quick Settings, lock, screenshot, power menu, split screen, play and pause | Android's global actions | – |
```

with

```markdown
| Back, home, recent apps, notifications, Quick Settings, lock, screenshot, power menu, split screen and all apps | Android's global actions | – |
| Play and pause | The media keys through Android's audio API, which reach the player that played last; Android 13 has no global action for them | – |
```

Then replace

```markdown
| Settings | Turn volume up or down; Turn [media, alarm, phone or ring] volume up or down; Mute; Unmute; Silence | Yes, through Android's audio API |
```

with

```markdown
| Settings | Turn volume up or down; Turn [media, alarm, phone or ring] volume up or down; Mute; Unmute; Silence | Yes, through Android's audio API. "Silence" puts the phone on vibrate, because the true silent mode needs access to Do Not Disturb, which Kestrel never asks for. Android does not let an app mute the alarm or the call volume, so Kestrel says so and changes nothing. |
```

- [ ] **Step 9: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin :testapp:assembleDebug`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:commands` as in Step 5, `:app` 332 with `PhoneDevicesTest` 5, the others as before), lint without issues in every Android module, the audit, and the device tests and the test app compile.

- [ ] **Step 10: Run the device commands' device tests**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\command-test.ps1 -Serial emulator-5554 -Class DeviceCommandsTest`
Expected: `BUILD SUCCESSFUL` and "Command tests: 8 run, 0 failed, 0 errors, 0 skipped." (3 from Task 3 and 5 new), with no warning from the restores at the end, which put back the volumes, the ringer mode and the magnification. If `mediaPauseAndPlayReachAPlayerInTheBackground` fails because music never plays (`isMusicActive` stays false with the fixture's tone), report it to the controller with the failure message, and do not change the test.

- [ ] **Step 11: Run all command device tests**

"Mute", "silence", "move up" and the other new fixed phrases were bare texts before; this run checks that no test of plans 3A and 3B used one of them.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\command-test.ps1 -Serial emulator-5554`
Expected: `BUILD SUCCESSFUL` and "Command tests: 80 run, 0 failed, 0 errors, 0 skipped." (`DeviceCommandsTest` 8 in all, 3 of them from Task 3), with no warning from the restores.

- [ ] **Step 12: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 13: Commit**

```powershell
git add core/commands/src/main/kotlin/dev/kestrel/commands/GrammarPhone.kt core/commands/src/main/kotlin/dev/kestrel/commands/HelpPhone.kt core/commands/src/test/kotlin/dev/kestrel/commands/GrammarPhoneTest.kt app/src/main/kotlin/dev/kestrel/app/commands/DeviceCommands.kt app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommands.kt app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommandTexts.kt app/src/test/kotlin/dev/kestrel/app/commands/PhoneDevicesTest.kt app/src/androidTest/kotlin/dev/kestrel/app/commands/DeviceCommandsTest.kt testapp/src/main/kotlin/dev/kestrel/testapp/MediaActivity.kt testapp/src/main/AndroidManifest.xml docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(commands): volumes, mute, silence, media keys and magnification by voice" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: Phone numbers and contacts

**Files:**
- Create: `core/commands/src/main/kotlin/dev/kestrel/commands/phone/PhoneNumbers.kt`, `core/commands/src/main/kotlin/dev/kestrel/commands/phone/Contacts.kt`, `core/commands/src/main/kotlin/dev/kestrel/commands/phone/ContactMatcher.kt`
- Create: `app/src/main/kotlin/dev/kestrel/app/commands/ContactsAccess.kt`, `app/src/main/kotlin/dev/kestrel/app/commands/ContactsReader.kt`
- Modify: `app/src/main/AndroidManifest.xml` (at the marker `    <!-- Plan 3C: permissions -->`)
- Test: `core/commands/src/test/kotlin/dev/kestrel/commands/phone/PhoneNumbersTest.kt`, `core/commands/src/test/kotlin/dev/kestrel/commands/phone/ContactMatcherTest.kt`, `app/src/test/kotlin/dev/kestrel/app/commands/ContactsReaderTest.kt` (all new)

**Interfaces:**
- Consumes: plan 3A's `Normalizer` (`normalize`, `words`), `LabelMatcher.score` and `decide`, `MatchCandidate`, `MatchDecision`, `Strictness.STRICT`, `MatchRules`; `CommandEnvironment.report(what, error)`; Android's `ContactsContract.Data`, `CommonDataKinds.StructuredName`, `Nickname` and `Phone`, `Contacts.CONTENT_URI` and `ContentObserver`.
- Produces (`:core:commands`, `dev.kestrel.commands.phone`): `object PhoneNumbers` with `digits(text): String?`, `find(text): List<IntRange>`, `dialable(stored): String?`, `mainNumber(digits): String` and `mayAutoDial(digits): Boolean`; `data class PhoneEntry(number, kind, superPrimary)`, `data class Contact(id, names, numbers, starred)`, `sealed interface ContactMatch` (`One`, `Several`, `TooMany(count)`, `None`), `sealed interface NumberChoice` (`One`, `Several`, `None`); `object ContactMatcher` with `find(spoken, contacts): ContactMatch`, `number(contact, kind): NumberChoice`, `kindOf(kind): String` and `MAX_CHOICES = 4`. (`:app`) `internal fun interface ContactsAccess { fun allowed(): Boolean }` and `internal fun contactsAccess(context)`; `internal interface ContactsSource { fun read(): List<Contact>; fun watch(changed: () -> Unit): AutoCloseable }`, `internal class ContactsReader(access, source, report)` with `fun contacts(): List<Contact>?` (null without the permission), and `internal class AndroidContacts(resolver) : ContactsSource`.

Design D11 and research r19 and r20. `PhoneNumbers.digits` reads a spoken or written number: digits and digit words ("zero" and "oh" to "nine"), "plus" for "+", "star" for `*`, "hash" and "pound" for `#`, and spaces, dashes, dots and brackets between them; anything else is no number, so "call Ana" stays a name. `find` finds the phone numbers in a screen's text: an optional "+", then 6 to 15 digits with spaces, dashes, dots, slashes or brackets between them, so that the Serbian "064/123-45-67" counts, but never a date such as "17.11.2026", "17. 11. 2026." or "17/11/26", also with a time after it. `dialable` turns a contact's stored number into what the phone app takes in a `tel:` link: the digits, a leading "+", `*` and `#`, and a pause (",", "p", "ext." or "x") or a wait (";", "w") before the digits that the phone app sends after the call has started; it gives null for anything with other letters, which Kestrel never turns into digits. `mayAutoDial` is false for a number with fewer than 5 digits before its pause, with `*` or `#`, or with anything but digits, "+", a pause and a wait; Task 6 also asks Android whether the main number is an emergency number. `ContactMatcher` matches in Kestrel, not through the provider's filter, which matches neither loosely nor across alphabets: a name that the owner says exactly comes first, then a single word as the first word of a contact's name, then the matcher with its strict limits over each contact's display name and nicknames; among equal matches starred contacts come first; 2 to 4 close contacts are `Several`, more are `TooMany`, and a single near miss is `Several` of one, which Task 6 offers as a confirmation. A number is the spoken kind (with "cell" as "mobile", and the owner's own labels by their normalised text), else the default number, else the only one; numbers that differ only in their formatting count once. `ContactsReader` touches the provider only at the first read that finds `READ_CONTACTS` granted; it keeps the contacts in memory, registers its observer on the contacts only after that read, because Android refuses the registration without the permission, and reads again after a change. When the permission is missing at a read, it drops the contacts and the observer, so that a later grant starts afresh. A refused registration is reported by its class, and the contacts still come; without a watch, the reader reads again at each call, so a later change is never missed. It reads the rows in the order of their IDs, so that a contact's numbers keep the order in which they were stored. `READ_CONTACTS` joins the manifest here; Task 6's calls ask for it at the first "call [name]" through `MainActivity`'s `EXTRA_ASK_PERMISSION`, and the setup checklist does not list it.

- [ ] **Step 1: Write the failing JVM tests**

Create `core/commands/src/test/kotlin/dev/kestrel/commands/phone/PhoneNumbersTest.kt`:

```kotlin
package dev.kestrel.commands.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumbersTest {
    @Test
    fun spokenNumbersBecomeDigitsWithPlusStarAndHash() {
        assertEquals("0641234567", PhoneNumbers.digits("064 123 4567"))
        assertEquals("0641234567", PhoneNumbers.digits("064-123-4567."))
        assertEquals("0641234567", PhoneNumbers.digits("zero six four one two three four five six seven"))
        assertEquals("+381641234567", PhoneNumbers.digits("plus 381 64 123 45 67"))
        assertEquals("+381641234567", PhoneNumbers.digits("+381 (64) 123-4567"))
        assertEquals("*123#", PhoneNumbers.digits("star one two three hash"))
        assertEquals("#31#", PhoneNumbers.digits("pound 3 1 pound"))
        assertEquals("112", PhoneNumbers.digits("One one two."))
    }

    @Test
    fun anythingElseIsNoNumber() {
        assertNull(PhoneNumbers.digits("Ana"))
        assertNull(PhoneNumbers.digits("064 Ana"))
        assertNull(PhoneNumbers.digits("plus"))
        assertNull(PhoneNumbers.digits("064 plus 1"))
        assertNull(PhoneNumbers.digits(""))
    }

    @Test
    fun theScreensNumbersAreFoundAndDatesAreNot() {
        val text = "Office: +381 11 234 5678, mobile 064/123-45-67? No: 064 123 4567. Meeting on 17.11.2026 or 2026-11-17, room 12."
        val found = PhoneNumbers.find(text).map { text.substring(it) }

        assertEquals(listOf("+381 11 234 5678", "064/123-45-67", "064 123 4567"), found)
        assertEquals(listOf("(011) 234-5678"), PhoneNumbers.find("Call (011) 234-5678 today").map { "Call (011) 234-5678 today".substring(it) })
        for (date in listOf("17/11/26 at 10.30, code 12345", "Sent 17.11.2026 10:30", "17. 11. 2026.", "2026-11-17 10:30")) {
            assertTrue(date, PhoneNumbers.find(date).isEmpty())
        }
    }

    @Test
    fun kestrelPressesCallOnlyForLongNumbersWithoutStarOrHash() {
        assertTrue(PhoneNumbers.mayAutoDial("0641234567"))
        assertTrue(PhoneNumbers.mayAutoDial("+381641234567"))
        assertFalse(PhoneNumbers.mayAutoDial("112"))
        assertFalse(PhoneNumbers.mayAutoDial("1234"))
        assertFalse(PhoneNumbers.mayAutoDial("*#06#"))
        assertFalse(PhoneNumbers.mayAutoDial("*1230641234567"))
    }

    @Test
    fun storedNumbersKeepTheirPausesWaitsAndExtensionsForThePhoneApp() {
        assertEquals("0641234567,123", PhoneNumbers.dialable("064 123 4567,123"))
        assertEquals("+381641234567;99", PhoneNumbers.dialable("+381 (64) 123-4567;99"))
        assertEquals("0641234567,12", PhoneNumbers.dialable("064 123 4567 ext. 12"))
        assertEquals("0641234567,12", PhoneNumbers.dialable("064 123 4567 x12"))
        assertEquals("0641234567;12", PhoneNumbers.dialable("0641234567w12"))
        assertEquals("0112345678", PhoneNumbers.dialable("011/234-5678"))
        assertNull(PhoneNumbers.dialable("1-800-FLOWERS"))
        assertTrue(PhoneNumbers.mayAutoDial("0641234567,123"))
        assertFalse(PhoneNumbers.mayAutoDial("112,5"))
        assertFalse(PhoneNumbers.mayAutoDial("1-800-FLOWERS"))
    }
}
```

Create `core/commands/src/test/kotlin/dev/kestrel/commands/phone/ContactMatcherTest.kt`:

```kotlin
package dev.kestrel.commands.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactMatcherTest {
    private fun contact(id: Long, name: String, vararg numbers: PhoneEntry, starred: Boolean = false, nickname: String? = null) =
        Contact(id, listOfNotNull(name, nickname), numbers.toList(), starred)

    private val mobile = PhoneEntry("064 123 4567", "mobile", superPrimary = false)
    private val work = PhoneEntry("011 234 5678", "work", superPrimary = false)
    private val ana = contact(1, "Ana Petrović", mobile, work)
    private val anaJ = contact(2, "Ana Jovanović", mobile)
    private val anja = contact(3, "Anja Marković", mobile)
    private val djordje = contact(4, "Đorđe Ilić", mobile, nickname = "Đole")
    private val marko = contact(5, "Marko", mobile)
    private val contacts = listOf(ana, anaJ, anja, djordje, marko)

    @Test
    fun aFullNameAFirstNameAndANicknameFindTheirContact() {
        assertEquals(ContactMatch.One(ana), ContactMatcher.find("Ana Petrovic", contacts))
        assertEquals(ContactMatch.One(anja), ContactMatcher.find("Anja", contacts))
        assertEquals(ContactMatch.One(djordje), ContactMatcher.find("Djordje Ilic", contacts))
        assertEquals(ContactMatch.One(djordje), ContactMatcher.find("Đole", contacts))
        assertEquals(ContactMatch.One(marko), ContactMatcher.find("Marko", contacts))
    }

    @Test
    fun aSharedFirstNameAsksAndTheStarredContactComesFirst() {
        assertEquals(ContactMatch.Several(listOf(anaJ, ana)), ContactMatcher.find("Ana", contacts))
        val starred = ana.copy(starred = true)
        assertEquals(ContactMatch.Several(listOf(starred, anaJ)), ContactMatcher.find("Ana", listOf(starred, anaJ, anja)))
    }

    @Test
    fun moreThanFourContactsAreTooManyAndAStrangerIsNone() {
        val many = (1L..6L).map { contact(it, "Ana Number$it", mobile) }

        assertEquals(ContactMatch.TooMany(6), ContactMatcher.find("Ana", many))
        assertEquals(ContactMatch.None, ContactMatcher.find("Nikola", contacts))

    }

    @Test
    fun theNumberIsTheKindThatWasSaidElseTheDefaultElseTheOnlyOne() {
        assertEquals(NumberChoice.One(work), ContactMatcher.number(ana, "work"))
        assertEquals(NumberChoice.One(mobile), ContactMatcher.number(ana, "cell"))
        assertEquals(NumberChoice.None, ContactMatcher.number(ana, "home"))
        assertEquals(NumberChoice.Several(listOf(mobile, work)), ContactMatcher.number(ana, null))
        val defaultWork = work.copy(superPrimary = true)
        assertEquals(NumberChoice.One(defaultWork), ContactMatcher.number(ana.copy(numbers = listOf(mobile, defaultWork)), null))
        assertEquals(NumberChoice.One(mobile), ContactMatcher.number(anaJ, null))
        val gym = PhoneEntry("011 555 0101", "Gym", superPrimary = false)
        assertEquals(NumberChoice.One(gym), ContactMatcher.number(ana.copy(numbers = listOf(mobile, gym)), "gym"))
    }

    @Test
    fun aSingleNearMissIsOfferedAsAConfirmation() {
        val maria = contact(7, "Maria Ilić", mobile)

        assertEquals(ContactMatch.Several(listOf(maria)), ContactMatcher.find("Marija", listOf(maria, marko)))
    }

    @Test
    fun numbersThatDifferOnlyInFormattingCountOnce() {
        val same = contact(6, "Dan", mobile, PhoneEntry("064-123-4567", "home", superPrimary = false))

        assertEquals(NumberChoice.One(mobile), ContactMatcher.number(same, null))
    }
}
```

Create `app/src/test/kotlin/dev/kestrel/app/commands/ContactsReaderTest.kt`:

```kotlin
package dev.kestrel.app.commands

import dev.kestrel.commands.phone.Contact
import dev.kestrel.commands.phone.PhoneEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactsReaderTest {
    private val ana = Contact(1, listOf("Ana Petrović"), listOf(PhoneEntry("064 123 4567", "mobile", superPrimary = false)), starred = false)

    /** The provider as the tests see it: what it was asked, in order. */
    private class Provider(var contacts: List<Contact>) : ContactsSource {
        val calls = mutableListOf<String>()
        var refuseWatch = false
        var changed: (() -> Unit)? = null

        override fun read(): List<Contact> {
            calls += "read"
            return contacts
        }

        override fun watch(changed: () -> Unit): AutoCloseable {
            calls += "watch"
            if (refuseWatch) throw SecurityException("Permission Denial: Ana Petrović")
            this.changed = changed
            return AutoCloseable { calls += "unwatch" }
        }
    }

    private var allowed = true
    private val reports = mutableListOf<String>()
    private val provider = Provider(listOf(ana))
    private val reader = ContactsReader({ allowed }, provider) { what, error -> reports += "$what: ${error.javaClass.simpleName}" }

    @Test
    fun nothingIsReadOrWatchedBeforeTheFirstReadWithThePermission() {
        allowed = false

        assertNull(reader.contacts())
        assertEquals(emptyList<String>(), provider.calls)

        allowed = true
        assertEquals(listOf(ana), reader.contacts())
        // The watch comes after the read, because Android refuses it without the permission.
        assertEquals(listOf("read", "watch"), provider.calls)
    }

    @Test
    fun theContactsAreKeptUntilTheyChange() {
        reader.contacts()
        reader.contacts()
        assertEquals(listOf("read", "watch"), provider.calls)

        val marko = ana.copy(id = 2, names = listOf("Marko"))
        provider.contacts = listOf(ana, marko)
        checkNotNull(provider.changed).invoke()

        assertEquals(listOf(ana, marko), reader.contacts())
        assertEquals(listOf("read", "watch", "read"), provider.calls)
    }

    @Test
    fun aLostPermissionDropsTheContactsAndTheWatchAndAGrantStartsAfresh() {
        reader.contacts()
        allowed = false

        assertNull(reader.contacts())
        assertEquals(listOf("read", "watch", "unwatch"), provider.calls)

        allowed = true
        reader.contacts()
        assertEquals(listOf("read", "watch", "unwatch", "read", "watch"), provider.calls)
    }

    @Test
    fun aRefusedWatchIsReportedByItsClassAndTheContactsStillCome() {
        provider.refuseWatch = true

        assertEquals(listOf(ana), reader.contacts())
        assertEquals(listOf("Watching the contacts: SecurityException"), reports)
        // Without a watch, nothing tells the reader of a change, so it reads again and tries the watch again.
        assertEquals(listOf(ana), reader.contacts())
        assertEquals(listOf("read", "watch", "read", "watch"), provider.calls)
    }
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 --continue :core:commands:test --tests "dev.kestrel.commands.phone.*" :app:testDebugUnitTest --tests dev.kestrel.app.commands.ContactsReaderTest`
Expected: `BUILD FAILED`: neither test source set compiles: "Unresolved reference 'PhoneNumbers'" and "Unresolved reference 'ContactsReader'".

- [ ] **Step 3: Write the phone numbers and the contact matcher**

Create `core/commands/src/main/kotlin/dev/kestrel/commands/phone/PhoneNumbers.kt`:

```kotlin
package dev.kestrel.commands.phone

/**
 * Phone numbers in speech and on the screen (design spec, section 13.6). Nothing here keeps or prints a number.
 */
object PhoneNumbers {
    private val TOKEN = Regex("""[+*#]|\p{L}+|\d+""")
    private val DIGIT_WORDS = mapOf(
        "zero" to '0', "oh" to '0', "one" to '1', "two" to '2', "three" to '3', "four" to '4', "five" to '5', "six" to '6',
        "seven" to '7', "eight" to '8', "nine" to '9',
    )

    // An optional "+", then digits with spaces, dashes, dots, slashes or brackets between them, not inside a word or a
    // number. Serbian numbers are often written as 064/123-45-67.
    private val CANDIDATE = Regex("""(?<![\p{L}\p{N}+])\+?[\d(][\d \u00A0\-./()]*\d(?![\p{L}\p{N}])""")

    private const val DAY = """(?:0?[1-9]|[12]\d|3[01])"""
    private const val MONTH = """(?:0?[1-9]|1[0-2])"""
    private const val YEAR = """(?:\d{2}|\d{4})"""

    // A date such as 17.11.2026, 17. 11. 2026., 2026-11-17, 17/11/26 or 11/17/26, also with a time or words after it.
    private val DATE =
        Regex("""(?:$DAY[./-] ?$MONTH[./-] ?$YEAR|$MONTH[./-]$DAY[./-]$YEAR|\d{4}[./-]$MONTH[./-]$DAY)\.?(?:[ \u00A0].*)?""")

    // The parts of a stored number: an extension's word, digits, words, or one other character.
    private val STORED = Regex("""extension|ext\.?|\d+|\p{L}+|[^\p{L}\d]""")
    private val EXTENSION = setOf("extension", "ext", "ext.", "x", "p")
    private val WAIT = setOf("w")
    private const val SEPARATORS = " \u00A0-./()"

    /** Numbers shorter than this are emergency and short codes, which Kestrel never calls itself. */
    const val SHORTEST_AUTO_DIAL = 5

    /**
     * The number that [text] says, as "+", digits, "*" and "#", or null when [text] says anything else. Digits and digit
     * words count, "plus" is "+" at the start, "star" is "*", "hash" and "pound" are "#"; spaces, dashes and other
     * separators are ignored.
     */
    fun digits(text: String): String? {
        val number = StringBuilder()
        for (token in TOKEN.findAll(text.lowercase()).map { it.value }) {
            when {
                token[0].isDigit() -> token.forEach { number.append(Character.forDigit(Character.digit(it, 10), 10)) }
                token == "+" || token == "plus" -> if (number.isEmpty()) number.append('+') else return null
                token == "*" || token == "star" -> number.append('*')
                token == "#" || token == "hash" || token == "pound" -> number.append('#')
                token in DIGIT_WORDS -> number.append(DIGIT_WORDS.getValue(token))
                else -> return null
            }
        }
        return number.toString().takeIf { it.any(Char::isDigit) }
    }

    /**
     * Where [text] shows phone numbers: an optional "+", then 6 to 15 digits with spaces, dashes, dots, slashes or brackets
     * between them. Dates such as "17.11.2026", "17. 11. 2026." and "17/11/26", also with a time after them, are skipped.
     */
    fun find(text: String): List<IntRange> = CANDIDATE.findAll(text).filter { match ->
        match.value.count(Char::isDigit) in 6..15 && !DATE.matches(match.value)
    }.map { it.range }.toList()

    /**
     * A contact's stored number as the phone app takes it in a `tel:` link: digits, a leading "+", "*" and "#", and a pause
     * (",") or a wait (";") before the digits that the phone app sends after the call has started. An extension ("ext.",
     * "x") and the letter "p" become a pause, and "w" a wait, so that the phone app dials the main number and sends the
     * rest itself; spaces, dashes, dots, slashes and brackets go. Null for anything else, such as letters of a word, which
     * Kestrel never turns into another number.
     */
    fun dialable(stored: String): String? {
        val number = StringBuilder()
        for (token in STORED.findAll(stored.lowercase()).map { it.value }) {
            when {
                token[0].isDigit() -> number.append(token)
                token == "+" -> if (number.isEmpty()) number.append('+') else return null
                token == "*" || token == "#" -> number.append(token)
                token == "," || token in EXTENSION -> if (number.isNotEmpty()) number.append(',') else return null
                token == ";" || token in WAIT -> if (number.isNotEmpty()) number.append(';') else return null
                token in SEPARATORS -> Unit
                else -> return null
            }
        }
        return number.toString().takeIf { it.any(Char::isDigit) }
    }

    /** The number that the phone app dials first: [digits] before a pause or a wait. */
    fun mainNumber(digits: String): String = digits.substringBefore(',').substringBefore(';')

    /**
     * Whether Kestrel may press Call for [digits] itself: digits with at most a leading "+", a pause or a wait, and at least
     * [SHORTEST_AUTO_DIAL] digits before the pause, so that no emergency or short code qualifies, and no "*" or "#", which
     * codes that change the phone's settings use. The caller also asks Android whether the main number is an emergency
     * number.
     */
    fun mayAutoDial(digits: String): Boolean =
        digits.all { it.isDigit() || it == '+' || it == ',' || it == ';' } && mainNumber(digits).count(Char::isDigit) >= SHORTEST_AUTO_DIAL
}
```

Create `core/commands/src/main/kotlin/dev/kestrel/commands/phone/Contacts.kt`:

```kotlin
package dev.kestrel.commands.phone

/**
 * One phone number of a contact: the [number] as the contacts store it, its [kind] ("mobile", "work", "home", "main",
 * "other" or the owner's own label) and whether it is the contact's default number. Its text form never shows the number.
 */
data class PhoneEntry(val number: String, val kind: String, val superPrimary: Boolean) {
    override fun toString() = "PhoneEntry(${number.length} characters, kind of ${kind.length} characters, superPrimary=$superPrimary)"
}

/**
 * A contact as "call X" sees it: its [names] (the display name first, then nicknames), its [numbers] and whether the owner
 * starred it. Its text form shows only counts.
 */
data class Contact(val id: Long, val names: List<String>, val numbers: List<PhoneEntry>, val starred: Boolean) {
    override fun toString() = "Contact(${names.size} names, ${numbers.size} numbers, starred=$starred)"
}

/** Whom the owner's words name among the contacts. */
sealed interface ContactMatch {
    data class One(val contact: Contact) : ContactMatch

    /** Up to [ContactMatcher.MAX_CHOICES] contacts for "Which one?"; a single near miss is offered as a confirmation. */
    data class Several(val contacts: List<Contact>) : ContactMatch

    /** More contacts than "Which one?" can offer. */
    data class TooMany(val count: Int) : ContactMatch

    data object None : ContactMatch
}

/** Which of a contact's numbers to call. */
sealed interface NumberChoice {
    data class One(val entry: PhoneEntry) : NumberChoice

    /** Several numbers, for "Which number?". */
    data class Several(val entries: List<PhoneEntry>) : NumberChoice

    data object None : NumberChoice
}
```

Create `core/commands/src/main/kotlin/dev/kestrel/commands/phone/ContactMatcher.kt`:

```kotlin
package dev.kestrel.commands.phone

import dev.kestrel.commands.text.LabelMatcher
import dev.kestrel.commands.text.MatchCandidate
import dev.kestrel.commands.text.MatchDecision
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.commands.text.Strictness

/**
 * Finds the contact and the number that "call X" names (design spec, section 13.6; research r20). It matches in Kestrel,
 * with the strict limits of section 9.6, so that Cyrillic and Latin spellings and names without diacritics find each
 * other: a name that the owner says exactly, then a first name alone as the first word of a contact's name, then the
 * matcher, whose single near miss is offered in "Which one?" as a confirmation. Equal matches are ranked with the starred
 * contacts first.
 */
object ContactMatcher {
    /** "Which one?" offers at most this many contacts; with more, Kestrel asks for the full name. */
    const val MAX_CHOICES = 4

    private val SAME_KINDS = mapOf("cell" to "mobile", "cellphone" to "mobile", "cell phone" to "mobile", "mobile phone" to "mobile")

    fun find(spoken: String, contacts: List<Contact>): ContactMatch {
        val said = Normalizer.normalize(spoken)
        if (said.isEmpty()) return ContactMatch.None
        val exact = contacts.filter { contact -> contact.names.any { Normalizer.normalize(it) == said } }
        if (exact.isNotEmpty()) return decide(ranked(exact))
        if (' ' !in said) {
            val first = contacts.filter { contact -> contact.names.any { Normalizer.words(it).firstOrNull() == said } }
            if (first.isNotEmpty()) return decide(ranked(first))
        }
        val candidates = contacts.mapIndexed { index, contact -> MatchCandidate(index.toString(), contact.names) }
        return when (val decision = LabelMatcher.decide(spoken, candidates, Strictness.STRICT)) {
            is MatchDecision.Found -> ContactMatch.One(contacts[decision.id.toInt()])
            // A single near miss is offered too, as a confirmation.
            is MatchDecision.Ambiguous -> offer(decision.ids.map { contacts[it.toInt()] }.sortedByDescending { it.starred })
            MatchDecision.NotFound -> ContactMatch.None
        }
    }

    /**
     * The number of [contact] to call: the one of the [kind] that the owner said ("mobile", "work", a custom label), else
     * the default number, else the only number. Numbers that differ only in their formatting count once.
     */
    fun number(contact: Contact, kind: String?): NumberChoice {
        val entries = contact.numbers.distinctBy { entry -> entry.number.filter { it.isDigit() || it == '+' } }
        if (kind != null) {
            val wanted = kindOf(kind)
            return choice(entries.filter { kindOf(it.kind) == wanted })
        }
        entries.singleOrNull()?.let { return NumberChoice.One(it) }
        entries.firstOrNull { it.superPrimary }?.let { return NumberChoice.One(it) }
        return choice(entries)
    }

    /** The kind of a number as the matcher compares it: folded, and "cell" as "mobile". */
    fun kindOf(kind: String): String = Normalizer.normalize(kind).let { SAME_KINDS[it] ?: it }

    private fun choice(entries: List<PhoneEntry>): NumberChoice = when (entries.size) {
        0 -> NumberChoice.None
        1 -> NumberChoice.One(entries[0])
        else -> NumberChoice.Several(entries)
    }

    private fun ranked(contacts: List<Contact>): List<Contact> =
        contacts.sortedWith(compareByDescending<Contact> { it.starred }.thenBy { Normalizer.normalize(it.names.firstOrNull().orEmpty()) })

    private fun decide(contacts: List<Contact>): ContactMatch = if (contacts.size == 1) ContactMatch.One(contacts[0]) else offer(contacts)

    private fun offer(contacts: List<Contact>): ContactMatch =
        if (contacts.size <= MAX_CHOICES) ContactMatch.Several(contacts) else ContactMatch.TooMany(contacts.size)
}
```

- [ ] **Step 4: Run the tests of `:core:commands` to verify that they pass**

Run: `scripts\gradle.ps1 :core:commands:test`
Expected: `BUILD SUCCESSFUL`, with 107 tests (`PhoneNumbersTest` 5, `ContactMatcherTest` 6).

- [ ] **Step 5: Write the contacts reader and the permission**

Create `app/src/main/kotlin/dev/kestrel/app/commands/ContactsAccess.kt`:

```kotlin
package dev.kestrel.app.commands

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/**
 * Whether Kestrel may read the contacts (`READ_CONTACTS`, design spec, section 13.6). An interface, so that the JVM tests
 * can check the first use without the permission.
 */
internal fun interface ContactsAccess {
    fun allowed(): Boolean
}

/** Kestrel's own permission to read the contacts, which the owner gives at the first "call [name]". */
internal fun contactsAccess(context: Context) = ContactsAccess {
    context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
}
```

Create `app/src/main/kotlin/dev/kestrel/app/commands/ContactsReader.kt`:

```kotlin
package dev.kestrel.app.commands

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import dev.kestrel.commands.phone.Contact
import dev.kestrel.commands.phone.PhoneEntry

/** Where the contacts come from: Android's contacts provider, or a fake in the JVM tests. */
internal interface ContactsSource {
    /** Every contact with its names and numbers. Throws [SecurityException] without `READ_CONTACTS`. */
    fun read(): List<Contact>

    /** Calls [changed] after each change of the contacts until the returned handle closes. Throws without the permission. */
    fun watch(changed: () -> Unit): AutoCloseable
}

/**
 * The owner's contacts for "call [name]" (design spec, section 13.6; research r20), kept in memory. Nothing touches the
 * provider until the first read that finds `READ_CONTACTS` granted; that read registers the watch for changes only after
 * it has read, because Android refuses the registration without the permission. After a change, the next read reads
 * again. When the permission is missing at a read, the reader drops what it kept and its watch, so that a later grant
 * starts afresh. It never logs a name or a number; a failure is reported by its classes only.
 */
internal class ContactsReader(
    private val access: ContactsAccess,
    private val source: ContactsSource,
    private val report: (String, Throwable) -> Unit,
) {
    private var kept: List<Contact>? = null
    private var watch: AutoCloseable? = null

    @Volatile
    private var changed = false

    /** The contacts, or null without the permission. */
    @Synchronized
    fun contacts(): List<Contact>? {
        if (!access.allowed()) {
            drop()
            return null
        }
        // Kept only while a watch tells of changes; after a refused watch, every read reads again.
        kept?.let { if (!changed && watch != null) return it }
        // Cleared before the read, so that a change during the read leads to another read next time.
        changed = false
        val contacts = try {
            source.read()
        } catch (e: SecurityException) {
            drop()
            return null
        }
        kept = contacts
        if (watch == null) {
            watch = try {
                source.watch { changed = true }
            } catch (e: SecurityException) {
                report("Watching the contacts", e)
                null
            }
        }
        return contacts
    }

    private fun drop() {
        kept = null
        changed = false
        watch?.close()
        watch = null
    }
}

/** The contacts of Android's provider: names, nicknames and phone numbers, never other data. */
internal class AndroidContacts(private val resolver: ContentResolver) : ContactsSource {
    override fun read(): List<Contact> {
        val names = LinkedHashMap<Long, MutableList<String>>()
        val numbers = HashMap<Long, MutableList<PhoneEntry>>()
        val starred = HashMap<Long, Boolean>()
        val kinds = arrayOf(StructuredName.CONTENT_ITEM_TYPE, Nickname.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE)
        resolver.query(Data.CONTENT_URI, COLUMNS, "${Data.MIMETYPE} IN (?, ?, ?)", kinds, Data._ID)?.use { rows ->
            while (rows.moveToNext()) {
                val id = rows.getLong(0)
                val contactNames = names.getOrPut(id) { mutableListOf() }
                rows.getString(1)?.let { if (it !in contactNames) contactNames.add(0, it) }
                starred[id] = rows.getInt(6) != 0
                val value = rows.getString(3) ?: continue
                when (rows.getString(2)) {
                    Nickname.CONTENT_ITEM_TYPE -> if (value !in contactNames) contactNames += value
                    Phone.CONTENT_ITEM_TYPE -> numbers.getOrPut(id) { mutableListOf() } +=
                        PhoneEntry(value, kind(rows.getInt(4), rows.getString(5)), rows.getInt(7) != 0)
                }
            }
        }
        return names.filterValues { it.isNotEmpty() }.map { (id, contactNames) ->
            Contact(id, contactNames, numbers[id].orEmpty(), starred[id] == true)
        }
    }

    override fun watch(changed: () -> Unit): AutoCloseable {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = changed()
        }
        resolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
        return AutoCloseable { resolver.unregisterContentObserver(observer) }
    }

    private fun kind(type: Int, label: String?): String = when (type) {
        Phone.TYPE_MOBILE -> "mobile"
        Phone.TYPE_WORK -> "work"
        Phone.TYPE_HOME -> "home"
        Phone.TYPE_MAIN -> "main"
        Phone.TYPE_WORK_MOBILE -> "work mobile"
        Phone.TYPE_CUSTOM -> label?.takeIf { it.isNotBlank() } ?: "other"
        else -> "other"
    }

    private companion object {
        // The display name of the contact, the row's kind and value, a phone number's type and label, the star and
        // whether the number is the contact's default.
        val COLUMNS = arrayOf(
            Data.CONTACT_ID, Data.DISPLAY_NAME, Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3, Data.STARRED, Data.IS_SUPER_PRIMARY,
        )
    }
}
```

In `app/src/main/AndroidManifest.xml`, replace

```xml
    <!-- Plan 3C: permissions -->
```

with

```xml
    <!-- Optional, asked for at the first "call [name]": the contacts, which Kestrel reads and matches only on the phone
         (design spec, section 13.6). -->
    <uses-permission android:name="android.permission.READ_CONTACTS" />
    <!-- Plan 3C: permissions -->
```

- [ ] **Step 6: Run the JVM tests of the reader to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.commands.ContactsReaderTest`
Expected: `BUILD SUCCESSFUL`, with 4 tests run.

- [ ] **Step 7: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin :testapp:assembleDebug`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:commands` as in Step 4, `:app` 336 with `ContactsReaderTest` 4, the others as before), lint without issues in every Android module, the audit, and the device tests and the test app compile.

- [ ] **Step 8: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 9: Commit**

```powershell
git add core/commands/src/main/kotlin/dev/kestrel/commands/phone core/commands/src/test/kotlin/dev/kestrel/commands/phone app/src/main/kotlin/dev/kestrel/app/commands/ContactsAccess.kt app/src/main/kotlin/dev/kestrel/app/commands/ContactsReader.kt app/src/test/kotlin/dev/kestrel/app/commands/ContactsReaderTest.kt app/src/main/AndroidManifest.xml
git commit -m "feat(commands): phone numbers, the contact matcher and the contacts reader" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: Calls by number and by name, and "copy this phone number"

**Files:**
- Modify: `core/commands/src/main/kotlin/dev/kestrel/commands/GrammarPhone.kt`, `core/commands/src/main/kotlin/dev/kestrel/commands/HelpPhone.kt`, `app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommands.kt`, `app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommandTexts.kt` (at Task 1's anchors, which this task removes, `PhoneCommands.run`'s screen line and the texts' package line)
- Create: `app/src/main/kotlin/dev/kestrel/app/commands/CallCommands.kt`
- Create: `testapp/src/main/kotlin/dev/kestrel/testapp/PhoneNumberActivity.kt`, `testapp/src/main/res/values/ids_phone.xml`
- Modify: `testapp/src/main/AndroidManifest.xml` (at the marker `        <!-- Plan 3C: phone fixtures -->`)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (8.7's sentence `The labels are English; the phone app's other languages come with sub-project 3's matching.` and its bullet `- After the call, the previous listening mode returns.`, 13.6's bullet `- Kestrel never requests`, Appendix A's rows `| Calls | Call [number]` and `| Calls | Copy this phone number`)
- Test: `core/commands/src/test/kotlin/dev/kestrel/commands/GrammarPhoneTest.kt`, `app/src/test/kotlin/dev/kestrel/app/commands/PhoneCallsTest.kt` (new), `app/src/androidTest/kotlin/dev/kestrel/app/commands/CallCommandsTest.kt` (new)

**Interfaces:**
- Consumes: Task 1's anchors, `SystemControls.start(intent)` and `PhoneTests` (`addContact`, `removeTestContacts`, `largestRowId`, `deleteRowsAfter`, `CALL_LOG`, `MOBILE`, `WORK`); Task 2's `askAmong` (with its `fold`), `Answer`, `PhoneCommandTexts.whichOne` and `noChoice`; Task 5's `PhoneNumbers` (`digits`, `dialable`, `mainNumber`, `mayAutoDial`, `find`), `ContactMatcher` (with `kindOf`), `ContactMatch`, `NumberChoice`, `Contact`, `PhoneEntry`, `ContactsReader`, `ContactsSource`, `AndroidContacts` and `contactsAccess`; plan 3A's `PhoneCommand.Call(who)`, `CallTarget`, `PhoneCommand.CopyPhoneNumber`, `Normalizer.withOffsets(text)` with `Folded.span(from, to)`, `cutWords`, `CommandConversation.say`, `ask(question)` and `countdown(millis, text)`, `CommandEnvironment.openSettings(page, permission)` and `targets`, `ScreenCommands(environment).choose(screen, choices, conversation)` with `Choice.Chosen` and `Choice.Ends`, `ScreenElement`'s `parent`, `isActionable` and `clickable`, `WindowSummary.title`, `CommandTexts.SCREEN_CHANGED`, `ScreenActions.clickLabelled(label, packages)` (plan 2D), `ScreenTargets.chosen(element)`, `names(snapshot, element)`, `view()` and `decide(view, target, kind, strictness, cells)` with `ScreenView`, `TargetKind` and `Resolution`, `GesturePaths.fromEdge` and `limit`, `Badge`, every variant of the three frozen model files, `CommandParser.POLITE_WORDS`, `CommandTexts.WHICH_ONE`, `MainActivity.ASKABLE` (with `READ_CONTACTS`), `ScreenSession.numberBadges`, the orchestrator's `Mode.IN_CALL`, `State.counting`, and the test helpers `CommandTests` (`send`, `ask`, `answer`, `next`, `awaitSaid`, `spoken`, `graph`), `ScreenServiceRule` (`automation`, `open(activity, title, extras)`, `awaitElement`, `awaitInputWindow`) and `TestFinger`.
- Produces: the rule `call` of `GrammarPhone.targeted` (with `numberText`) and the fixed phrases of "copy this phone number"; (`:app`) `internal enum class Pressed { CALLING, CHOOSE_SIM, REFUSED, OTHER_NUMBER }`, `internal enum class DialRule { CALL, EMERGENCY, TAP_YOURSELF }`, `internal interface Dialer` (`rule(digits)`, `open`, `awaitCallButton`, `press(digits)`), `internal class CallCommands(contacts, dialer, openSettings, policy)` with `suspend fun call(who, conversation): CommandResult`, `COUNTDOWN_MILLIS = 4_000L` and `create(environment)`, `internal class AndroidDialer(context, screen) : Dialer` with, in its companion, `callButton(snapshot, phoneApp)`, `fieldText(snapshot, phoneApp)`, `shownNumber(snapshot, phoneApp)`, `emergency(telephony, digits): Boolean?`, `mayCall(digits, emergency)`, `ruleFor(digits, emergency): DialRule`, `isNumberField(element)` and `fieldTexts(snapshot, phoneApp)`; `internal enum class EmergencyPolicy { CONFIRM, TOUCH_ONLY }` with `CURRENT = CONFIRM` (the owner's decision); `internal class EmergencyGuard(context, targets, screen, policy)` with `suspend fun check(command, heard, conversation): CommandResult?`, its `sealed interface Reach` (`Element(target, kind, strictness, cells, gesture)`, `Point(x, y)`, `Anywhere`) and, in its companion, `PHONE_PACKAGES`, `suspend fun confirm(policy, conversation): CommandResult?`, `confirmed(answer)`, `needsYes(reaches, view, phoneApps, emergency)`, `emergencyNamed(snapshot, index)`, `guardedWindows(snapshot, phoneApps, emergency)`, `resolved(reaches, view)` and `reaches(command, screen): List<Reach>`; `internal class NumberCopier(environment)` with `suspend fun copy(screen, conversation)` and `numbersOn(snapshot)`; the call texts of `PhoneCommandTexts` (`NEED_CONTACTS`, `cantFindContact`, `tooManyContacts`, `noNumber`, `noNumberOfKind`, `whichNumber`, `spoken`, `contactAndKind`, `calling`, `callingIn`, `tapCallYourself`, `OPENED_TAP_CALL`, `NO_PHONE_APP`, `COULD_NOT_PRESS`, `OTHER_NUMBER`, `EMERGENCY_QUESTION`, `EMERGENCY_TOUCH_ONLY`, `CHOOSE_SIM`, `NO_PHONE_NUMBER`, `COULD_NOT_COPY`); the help group "Calls"; (`:testapp`) `PhoneNumberActivity` (title "Phone numbers fixture", "Office: +381 11 234 5678", "Mobile: 064 123 4567" unless the extra `single` is true, "Meeting on 17.11.2026", a "Paste" button, the status "Pasted" with the clipboard's text, an "Emergency call" button that only shows "Emergency call pressed", and "Rearrange", which puts a second such button with another view ID in its place) and the IDs `office_number`, `mobile_number`, `meeting_date`, `paste_button`, `emergency_button`, `emergency_button_moved` and `rearrange_button`. All of plan 3C's anchors in its own files are gone after this task.

Design D11, spec 13.6, research r19 and r20, prototype P4 and decision 1. "Call X" and "dial X" take a number when all the words after the verb are one (with "+", "*" and "#" kept, which the normaliser drops), and a name otherwise: "call Ana", "call Ana mobile", "call Ana on mobile", "call Ana's work number", and "call Ana's gym number" for the owner's own label; a polite word at the end ("call Mom now") is no part of the name or the number. Kestrel never requests `CALL_PHONE`: it opens the default phone app with `ACTION_DIAL` and a `tel:` link built with `Uri.fromParts`, so that `*`, `#`, a pause (",") and a wait (";") stay, waits up to 3 s for the phone app's call button (Google's by its view ID `dialpad_voice_call_button`, and only when no element has that ID, another phone app's by the name "Call", "Dial" or "Voice call"), says whom it calls ("Calling Ana Petrović, mobile.", or the number digit by digit), counts down 4 s from the end of that announcement on the bar, and presses the button only when the phone app's number field shows the number that it announced: for another number it says "The phone app shows another number, so I didn't press Call.", and without a number field that it can read it does not press ("I couldn't press Call. Tap Call yourself."). "Cancel", "stop", "no" or a touch stops the countdown, and the phone app stays open with the number. The command ends `DONE` once the press went through, or when the phone app already shows the call; a SIM chooser after the press ends it `DONE` with "Choose the SIM." on the bar, and Kestrel does not choose. For a number with fewer than 5 digits or one with `*` or `#`, Kestrel only opens the phone app and says "I opened the phone app with 1 9 8 5. Tap Call yourself." Anything that may call an emergency number needs the owner's "yes, call" (the controller's decisions on the reviews; spec 8.7), under a policy that one constant switches, `EmergencyPolicy.CURRENT`, which is `CONFIRM` by the owner's decision (decision 13): `CONFIRM` asks "That may call an emergency number. Say 'yes, call' to do it." through `CommandConversation.ask`, and goes on only when the answer's normalised words are exactly "yes call", with or without "Kestrel" after them (an answer that starts with the name is a request of Kestrel's own, which plan 3A's rules turn into a cancel); any other answer, the question's timer, a touch or a cancel ends the command `CANCELLED`. `TOUCH_ONLY` refuses instead with "Tap it yourself for an emergency call." For an emergency number (Android's `isEmergencyNumber`, and also when that check throws or no `TelephonyManager` exists), "call X" says whom it calls and then, under `CONFIRM`, asks the question instead of counting down, so that the countdown alone never presses Call; under `TOUCH_ONLY` it only opens the phone app with the number and says to tap Call. `EmergencyGuard` runs before the screen part sees a command and needs no list of buttons. R1: an element whose name (its text, content description or view ID, split at its words, and the label of an element that takes a touch) holds the word "emergency" or "sos", or whose nearest clickable parent's does, such as the emergency buttons of the power menu and the lock screen, and a tap whose element's centre lies inside such a button (a caption drawn over it). R2: every window of a phone package (the default phone app, `com.android.phone`, `com.android.dialer`, `com.google.android.dialer`, `com.samsung.android.dialer`, `com.samsung.android.incallui`, `com.android.server.telecom`, and One UI's emergency dialer `com.samsung.android.app.telephonyui`) while one of the package's windows shows an element or a title that R1 names (an emergency dialer, also one whose number field Kestrel cannot read), or a number field that shows an emergency number, a number that Android cannot check, a number with fewer than 5 digits or with `*` or `#`, or a text that reads as no number; a number field is the field with the view ID `digits` (which Android's emergency dialer does not make editable), with any text, or another text field of the package, but only while its text holds a digit, `*` or `#`, so that a name in the phone app's search box asks nothing. Every window of the package counts, so that its menus and dialogs ("Call with a note") count too: every action on their elements needs the yes, except the digit keys (by their view IDs `zero` to `nine`, `star` and `pound`, or a whole name that is one digit, `*`, `#` or `+`, as the dial pad's keys have, never "1 Telenor"), the number field and its delete key (`deleteButton`, or the name "backspace" or "delete"), so that the owner can type and correct the number by voice; and so does every gesture that starts or ends inside them. After a "yes, call", the guard finds the command's targets again and lets the command go on only when it still finds the same elements (by key) in the same windows, the same grid square or the same point; otherwise it ends with plan 3A's "The screen changed. Say it again.", so that a yes counts only for what the question was about. The guard reads the targets of every command with an exhaustive `when` over the sealed command model, without an `else`, so that the compiler names a variant that is missing: a tap, a long press, a bare text, check and switch, expand and collapse, dismiss, the sliders, a scroll on a target, a swipe, a pinch and a drag on a target (as gestures), a drag whose end the owner gives later (which can end anywhere), and "start editing" with a target, each found with the screen part's own search (a number names any element, whatever the command); and the points of the gestures without a target (the screen's centre, the edge swipes' lines). "What is N" and "show commands for N" only say something, and the other variants name nothing; each returns no target explicitly. A text command that the text part declined is also checked as the bare text that the executor then runs. Kestrel's own feedback bar, which shows the question, never counts for R1. The phone app gives its number field the focus; plan 3B's dictation types into a field whose input type is a phone number's only speech that reads as a phone number, and plan 3B's "start editing" acts only on a text field, so a bare "call" there goes on to the phone part and its guard. The device test answers only "no" in the phone app, never "yes, call" with an emergency number: it says "call 112" and then "call", "tap dial", "expand", "collapse" and "drag" with the call button's number and "scroll down", types and deletes a digit by the keys' numbers without a question, and puts "abc" into the field, which a guard that took only the digits, or let an unreadable field through, would not ask about; a second test clicks the fixture's "Emergency call" button, which calls no one, only after "yes, call"; a third one changes the screen under the question with an accessibility click on the fixture's "Rearrange" (no touch, so the question stays) and expects "The screen changed. Say it again." after "yes, call", and no click. `aNumberIsAnnouncedCountedDownAndCalled` ends the call with plan 2D's `clickLabelled("End call", packages)`, with `EmergencyGuard.PHONE_PACKAGES` as the packages. What stays uncovered goes into spec 8.7 and the README: the phone app's controls that call a stored number while the number field is empty (the call icon of a call-history row, a favourite, a contact's page, a speed-dial key), the names of R1 in other languages than English, letters alone in a phone app's text field other than the one with the view ID `digits`, and an app's own emergency feature outside the phone packages whose names do not say "emergency" or "SOS". AOSP's carrier shortcuts in the emergency dialer are covered, because their view IDs (`emergency_call_number_info_view`, `emergency_call_confirm_view`) say "emergency". A contact's stored number keeps its pause, wait or extension (`PhoneNumbers.dialable`: "064 123 4567 ext. 12" becomes "0641234567,12"), so that the phone app dials the main number and sends the rest itself; a stored number with letters goes to the phone app as it is, and Kestrel never presses Call for it, so it never turns a stored number into another one. A name goes through Task 5's matcher among the contacts with a phone number, and among all contacts only when none of those matches (so that "Petra has no phone number." still comes); 2 to 4 close contacts ask "Which one?", more say "There are 6 contacts like Ana. Say the full name.", and a contact with several numbers asks "Which number? Mobile or work?" (with numbers, "Which number? 1, mobile. 2, mobile.", when two numbers have the same kind); the answer is a number or one of the names or kinds, where "cell" counts as "mobile". Without `READ_CONTACTS`, Kestrel says why and opens its settings app with `EXTRA_ASK_PERMISSION`, which asks for it. A call is never repeated by "again". "Copy this phone number" copies the only phone number in the texts of the app windows (not the status bar, notifications, text fields or password fields); with several, it shows "Which one?" numbers on the elements that hold them, through plan 3A's `ScreenCommands.choose`. The device tests call a made-up number on the emulator's phone app, whose call becomes active (plan 2D), end it with the in-call screen's "End call", and add their contacts through the shell (Task 1's check), which `command-test.ps1` deletes with the new call-log rows; a test that fails during a call ends it in `@After`. As every command test class, `CallCommandsTest` has `CommandTests.only` as its first rule, so that a run without `kestrelCommands=true` grants nothing and deletes nothing.

- [ ] **Step 1: Write the failing JVM tests**

In `core/commands/src/test/kotlin/dev/kestrel/commands/GrammarPhoneTest.kt`, replace

```kotlin
    // Tasks 2 to 6 of plan 3C add their tests above this line.
```

with

```kotlin
    @Test
    fun callTakesANumberOrANameWithAKind() {
        assertParses(
            PhoneCommand.Call(CallTarget.Number("0641234567")),
            "Call 064 123 4567.", "Dial 064-123-4567", "call zero six four one two three four five six seven",
        )
        assertParses(
            PhoneCommand.Call(CallTarget.Number("+381641234567")),
            "Call +381 64 123 4567.", "call plus three eight one six four one two three four five six seven",
        )
        assertParses(PhoneCommand.Call(CallTarget.Number("*123#")), "Dial star one two three hash.")
        assertParses(PhoneCommand.Call(CallTarget.Number("*#06#")), "Dial *#06#.")
        assertParses(PhoneCommand.Call(CallTarget.Number("112")), "Call 112.")
        assertParses(PhoneCommand.Call(CallTarget.Name("Ana Petrović", null)), "Call Ana Petrović.")
        assertParses(PhoneCommand.Call(CallTarget.Name("Mom", null)), "Call Mom.", "Call Mom now.", "Call Mom please")
        assertParses(PhoneCommand.Call(CallTarget.Number("0641234567")), "Call 064 123 4567 now.")
        assertParses(PhoneCommand.Call(CallTarget.Name("Ana", "mobile")), "Call Ana mobile.", "Call Ana on mobile.", "Call Ana's mobile number.")
        assertParses(PhoneCommand.Call(CallTarget.Name("Ana", "work")), "call Ana's work number", "Dial Ana at work.")
        assertParses(PhoneCommand.Call(CallTarget.Name("Ana", "gym")), "Call Ana's gym number.")
        assertParses(PhoneCommand.Call(CallTarget.Name("Ana", null)), "Call Ana's number.")
        assertParses(PhoneCommand.Call(CallTarget.Name("home", null)), "Call home.")
        assertParses(PhoneCommand.Volume(StreamKind.CALL, up = true), "Call volume up.")
        assertBare("Call.", "I'll call you later, okay?", "Call please.", "Dial now.")
    }

    @Test
    fun copyThisPhoneNumber() {
        assertParses(PhoneCommand.CopyPhoneNumber, "Copy this phone number.", "Copy the phone number.", "Copy the number.")
    }
```

Create `app/src/test/kotlin/dev/kestrel/app/commands/PhoneCallsTest.kt`:

```kotlin
package dev.kestrel.app.commands

import android.Manifest
import dev.kestrel.app.settings.SettingsPage
import dev.kestrel.commands.CallTarget
import dev.kestrel.commands.Command
import dev.kestrel.commands.Direction
import dev.kestrel.commands.Target
import dev.kestrel.commands.TextCommand
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.phone.Contact
import dev.kestrel.commands.phone.PhoneEntry
import dev.kestrel.commands.phone.PhoneNumbers
import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import dev.kestrel.screen.overlay.Badge
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCallsTest {
    private companion object {
        const val DIALER = "com.google.android.dialer"
    }

    private val mobile = PhoneEntry("064 123 4567", "mobile", superPrimary = false)
    private val work = PhoneEntry("011 234 5678", "work", superPrimary = false)
    private val ana = Contact(1, listOf("Ana Petrović"), listOf(mobile, work), starred = false)
    private val anaJ = Contact(2, listOf("Ana Jovanović"), listOf(mobile.copy(number = "065 765 4321")), starred = false)

    /** The phone app: what Kestrel did with it. */
    private class Phone(var button: Boolean = true, var pressed: Pressed = Pressed.CALLING) : Dialer {
        val did = mutableListOf<String>()

        // 0641111111 stands for an emergency number, which Android would report.
        override fun rule(digits: String) = AndroidDialer.ruleFor(digits) { it == "0641111111" }

        override suspend fun open(digits: String): Boolean {
            did += "open $digits"
            return true
        }

        override suspend fun awaitCallButton() = button

        override suspend fun press(digits: String): Pressed {
            did += "press $digits"
            return pressed
        }
    }

    /** The owner: answers the questions in order, and lets the countdown run out unless [stops]. */
    private class Owner(private vararg val answers: String?, val stops: Boolean = false) : CommandConversation {
        val said = mutableListOf<String>()
        val asked = mutableListOf<String>()
        val counted = mutableListOf<String>()
        private var next = 0

        override suspend fun say(text: String) {
            said += text
        }

        override suspend fun ask(question: String): String? {
            asked += question
            return answers[next++]
        }

        override fun keep(text: String) = Unit

        override suspend fun countdown(millis: Long, text: (secondsLeft: Int) -> String): Boolean {
            counted += text((millis / 1_000).toInt())
            return !stops
        }
    }

    private var allowed = true
    private val opened = mutableListOf<Pair<SettingsPage?, String?>>()
    private val phone = Phone()

    private fun calls(vararg contacts: Contact, policy: EmergencyPolicy = EmergencyPolicy.CONFIRM): CallCommands {
        val source = object : ContactsSource {
            override fun read() = contacts.toList()

            override fun watch(changed: () -> Unit) = AutoCloseable {}
        }
        return CallCommands(ContactsReader({ allowed }, source) { _, _ -> }, phone, { page, permission -> opened += page to permission }, policy)
    }

    @Test
    fun aNumberIsAnnouncedDigitByDigitCountedDownAndCalled() = runTest {
        val owner = Owner()

        val result = calls().call(CallTarget.Number("0641234567"), owner)

        assertEquals(CommandResult(Outcome.DONE), result)
        assertEquals(listOf("Calling 0 6 4 1 2 3 4 5 6 7."), owner.said)
        assertEquals(listOf("Calling 0 6 4 1 2 3 4 5 6 7, in 4"), owner.counted)
        assertEquals(listOf("open 0641234567", "press 0641234567"), phone.did)
    }

    @Test
    fun aStoppedCountdownLeavesTheNumberInThePhoneApp() = runTest {
        val result = calls().call(CallTarget.Number("0641234567"), Owner(stops = true))

        assertEquals(CommandResult(Outcome.CANCELLED), result)
        assertEquals(listOf("open 0641234567"), phone.did)
    }

    @Test
    fun aShortOrCodeNumberOnlyOpensThePhoneApp() = runTest {
        val owner = Owner()

        val result = calls().call(CallTarget.Number("*#06#"), owner)

        assertEquals(CommandResult(Outcome.DONE, say = "I opened the phone app with star hash 0 6 hash. Tap Call yourself."), result)
        assertEquals(listOf("open *#06#"), phone.did)
        assertEquals(emptyList<String>(), owner.counted)
        assertEquals(DialRule.TAP_YOURSELF, AndroidDialer.ruleFor("1985") { false })
        assertEquals(DialRule.CALL, AndroidDialer.ruleFor("0641234567,12") { false })
    }

    @Test
    fun anEmergencyNumberIsPressedOnlyAfterYesCallAndNeverThroughTheCountdown() = runTest {
        val yes = Owner("Yes, call.")
        assertEquals(CommandResult(Outcome.DONE), calls().call(CallTarget.Number("0641111111"), yes))
        assertEquals(listOf("Calling 0 6 4 1 1 1 1 1 1 1."), yes.said)
        assertEquals(listOf(PhoneCommandTexts.EMERGENCY_QUESTION), yes.asked)
        assertEquals(emptyList<String>(), yes.counted)
        assertEquals(listOf("open 0641111111", "press 0641111111"), phone.did)

        // Any other answer, and no answer (the question's timer or a touch), cancel; Android's check failing counts as an
        // emergency number.
        for (answer in listOf("no", "yes", null)) {
            assertEquals(CommandResult(Outcome.CANCELLED), calls().call(CallTarget.Number("0641111111"), Owner(answer)))
        }
        assertEquals(DialRule.EMERGENCY, AndroidDialer.ruleFor("0641234567") { null })
        assertEquals(DialRule.EMERGENCY, AndroidDialer.ruleFor("112") { it == "112" })

        // Under TOUCH_ONLY, Kestrel only opens the phone app and asks nothing.
        val touch = Owner()
        assertEquals(
            CommandResult(Outcome.DONE, say = "I opened the phone app with 0 6 4 1 1 1 1 1 1 1. Tap Call yourself."),
            calls(policy = EmergencyPolicy.TOUCH_ONLY).call(CallTarget.Number("0641111111"), touch),
        )
        assertEquals(emptyList<String>(), touch.asked)
        assertEquals("press 0641111111", phone.did.single { it.startsWith("press") })
    }

    @Test
    fun onlyYesCallGoesOnAndTouchOnlyRefusesWithoutAsking() = runTest {
        for (answer in listOf("yes, call", "Yes call.", "yes call Kestrel")) {
            assertEquals(answer, null, EmergencyGuard.confirm(EmergencyPolicy.CONFIRM, Owner(answer)))
        }
        // An answer that starts with the name is a request of Kestrel's own, which plan 3A's rules never pass as an answer.
        for (answer in listOf("yes", "call", "yes please call", "no", "yes, call 112", "Kestrel, yes, call", "", null)) {
            assertEquals(answer, CommandResult(Outcome.CANCELLED), EmergencyGuard.confirm(EmergencyPolicy.CONFIRM, Owner(answer)))
        }
        val owner = Owner()
        assertEquals(
            CommandResult(Outcome.FAILED, say = "Tap it yourself for an emergency call."),
            EmergencyGuard.confirm(EmergencyPolicy.TOUCH_ONLY, owner),
        )
        assertEquals(emptyList<String>(), owner.asked)
    }

    @Test
    fun withoutACallButtonOrWithARefusedPressTheOwnerTapsCall() = runTest {
        phone.button = false
        assertEquals(
            CommandResult(Outcome.FAILED, say = PhoneCommandTexts.OPENED_TAP_CALL),
            calls().call(CallTarget.Number("0641234567"), Owner()),
        )
        phone.button = true
        phone.pressed = Pressed.REFUSED
        assertEquals(
            CommandResult(Outcome.FAILED, say = PhoneCommandTexts.COULD_NOT_PRESS),
            calls().call(CallTarget.Number("0641234567"), Owner()),
        )
        phone.pressed = Pressed.CHOOSE_SIM
        assertEquals(CommandResult(Outcome.DONE, show = PhoneCommandTexts.CHOOSE_SIM), calls().call(CallTarget.Number("0641234567"), Owner()))
    }

    @Test
    fun withoutTheContactsPermissionKestrelSaysWhyAndOpensItsSettingsToAskForIt() = runTest {
        allowed = false

        val result = calls(ana).call(CallTarget.Name("Ana", null), Owner())

        assertEquals(CommandResult(Outcome.FAILED, say = PhoneCommandTexts.NEED_CONTACTS), result)
        assertEquals(listOf<Pair<SettingsPage?, String?>>(null to Manifest.permission.READ_CONTACTS), opened)
        assertEquals(emptyList<String>(), phone.did)
    }

    @Test
    fun aNameWithTheKindIsCalledWithItsKind() = runTest {
        val owner = Owner()

        calls(ana, anaJ).call(CallTarget.Name("Ana Petrovic", "mobile"), owner)

        assertEquals(listOf("Calling Ana Petrović, mobile."), owner.said)
        assertEquals(listOf("open 0641234567", "press 0641234567"), phone.did)
    }

    @Test
    fun twoContactsAskWhichOneAndSeveralNumbersAskWhichNumber() = runTest {
        val owner = Owner("2", "work")

        calls(ana, anaJ, Contact(3, listOf("Marko"), listOf(mobile), starred = false)).call(CallTarget.Name("Ana", null), owner)

        assertEquals(listOf("Which one? 1, Ana Jovanović. 2, Ana Petrović.", "Which number? Mobile or work?"), owner.asked)
        assertEquals(listOf("Calling Ana Petrović, work."), owner.said)
        assertEquals(listOf("open 0112345678", "press 0112345678"), phone.did)

        // "Cell" is the mobile number.
        calls(ana).call(CallTarget.Name("Ana Petrović", null), Owner("cell"))
        assertEquals("open 0641234567", phone.did[2])
    }

    @Test
    fun aStoredPauseOrExtensionStaysForThePhoneAppAndAWordIsNeverTurnedIntoDigits() = runTest {
        val office = Contact(4, listOf("Office"), listOf(PhoneEntry("011 234 5678 ext. 12", "work", superPrimary = false)), starred = false)
        val shop = Contact(5, listOf("Flower Shop"), listOf(PhoneEntry("1-800-FLOWERS", "work", superPrimary = false)), starred = false)

        calls(office).call(CallTarget.Name("Office", null), Owner())
        val result = calls(shop).call(CallTarget.Name("Flower Shop", null), Owner())

        assertEquals(listOf("open 0112345678,12", "press 0112345678,12", "open 1-800-FLOWERS"), phone.did)
        assertEquals(CommandResult(Outcome.DONE, say = "I opened the phone app with Flower Shop, work. Tap Call yourself."), result)
    }

    @Test
    fun aContactWithoutANumberIsFoundOnlyWhenNoOtherContactMatches() = runTest {
        val petra = Contact(6, listOf("Petra Ilić"), emptyList(), starred = false)
        val anaP = Contact(7, listOf("Ana P."), emptyList(), starred = false)
        val owner = Owner()

        calls(anaJ, anaP).call(CallTarget.Name("Ana", null), owner)

        assertEquals(emptyList<String>(), owner.asked)
        assertEquals(listOf("Calling Ana Jovanović, mobile."), owner.said)
        assertEquals(
            CommandResult(Outcome.FAILED, say = "Petra Ilić has no phone number."),
            calls(anaJ, petra).call(CallTarget.Name("Petra", null), Owner()),
        )
    }

    @Test
    fun aStrangerTooManyNamesAndAMissingKindAreSaid() = runTest {
        val many = (1L..5L).map { Contact(it, listOf("Ana Number$it"), listOf(mobile), starred = false) }

        assertEquals(
            CommandResult(Outcome.FAILED, say = "I can't find Nikola in your contacts."),
            calls(ana).call(CallTarget.Name("Nikola", null), Owner()),
        )
        assertEquals(
            CommandResult(Outcome.FAILED, say = "There are 5 contacts like Ana. Say the full name."),
            calls(*many.toTypedArray()).call(CallTarget.Name("Ana", null), Owner()),
        )
        assertEquals(
            CommandResult(Outcome.FAILED, say = "Ana Petrović has no home number."),
            calls(ana).call(CallTarget.Name("Ana Petrović", "home"), Owner()),
        )
    }

    private val screen = IntRect(0, 0, 1080, 2400)
    private val noEmergency = { _: String -> false }

    private fun element(
        key: String,
        window: Int,
        bounds: IntRect,
        packageName: String? = DIALER,
        text: String? = null,
        description: String? = null,
        viewId: String? = null,
        clickable: Boolean = true,
        parent: Int? = null,
    ) = ScreenElement(
        key = key, windowId = window, parent = parent, packageName = packageName, text = text, contentDescription = description,
        viewId = viewId?.let { "$packageName:id/$it" }, label = text ?: description, clickable = clickable, bounds = bounds,
    )

    // The phone app: its number field, which shows [field], a digit key, the delete key, the call button, a caption drawn
    // over the button outside its view tree, and a row that expands; [phoneApp] is its package, and [editable] whether its
    // field is a text field (Android's emergency dialer's is not).
    private fun dialer(field: String?, phoneApp: String = DIALER, editable: Boolean = true): ScreenSnapshot {
        val app = WindowSummary(1, WindowKind.APPLICATION, packageName = phoneApp, bounds = screen)
        return ScreenSnapshot(
            0,
            listOf(app),
            listOf(
                ScreenElement(
                    key = "digits", windowId = 1, packageName = phoneApp, viewId = "$phoneApp:id/digits", text = field,
                    editable = editable, bounds = IntRect(0, 0, 1080, 200),
                ),
                element("five", 1, IntRect(400, 1000, 680, 1200), phoneApp, text = "5", description = "5,JKL", viewId = "five"),
                element("delete", 1, IntRect(800, 2000, 1000, 2200), phoneApp, description = "backspace", viewId = "deleteButton"),
                element("call", 1, IntRect(400, 2000, 680, 2200), phoneApp, description = "dial", viewId = "dialpad_voice_call_button"),
                element("caption", 1, IntRect(450, 2050, 630, 2150), phoneApp, text = "Call", clickable = false),
                element("more", 1, IntRect(0, 300, 1080, 400), phoneApp, text = "More").copy(actions = setOf(ElementAction.EXPAND)),
            ),
        )
    }

    // Numbers on the screen: 1 on the row, 2 on the call button, 3 on the digit key, 4 on the delete key and 5 on the field.
    private fun view(snapshot: ScreenSnapshot) = ScreenView(
        snapshot,
        listOf("more", "call", "five", "delete", "digits").mapIndexed { index, key -> Badge(index + 1, key, snapshot.element(key)!!.bounds) },
        grid = null,
    )

    private fun needsYes(snapshot: ScreenSnapshot, command: Command, emergency: (String) -> Boolean? = noEmergency) =
        EmergencyGuard.needsYes(EmergencyGuard.reaches(command, screen), view(snapshot), EmergencyGuard.PHONE_PACKAGES, emergency)

    @Test
    fun aPhoneWindowWhoseNumberKestrelWouldNotCallNeedsTheYesForAllButItsKeysFieldAndDelete() {
        val tapCall = Command.Tap(Target.Number(2))
        // A number that Kestrel would call, and an empty field, need nothing.
        assertFalse(needsYes(dialer("064 123 4567"), tapCall))
        assertFalse(needsYes(dialer(null), tapCall))
        // An emergency number, a number that Android cannot check, a short number, a code, and a field that reads as no number.
        assertTrue(needsYes(dialer("112"), tapCall) { it == "112" })
        assertTrue(needsYes(dialer("064 123 4567"), tapCall) { null })
        for (field in listOf("1985", "*#06#", "abc", "112 Call")) {
            assertTrue(field, needsYes(dialer(field), tapCall))
        }
        // The whole window, not one button: the row, the caption over the button, a bare "call" or "dial".
        for (command in listOf(Command.Tap(Target.Number(1)), Command.Tap(Target.Label("call")), Command.Bare.of("dial"))) {
            assertTrue(command.toString(), needsYes(dialer("112"), command))
        }
        // The exceptions, so that the owner can type and correct the number by voice.
        val keys = listOf(Command.Tap(Target.Number(3)), Command.Tap(Target.Number(4)), Command.LongPress(Target.Number(4)), Command.Tap(Target.Number(5)))
        for (command in keys) {
            assertFalse(command.toString(), needsYes(dialer("112"), command))
        }
        // The same window in an app that is no phone app needs nothing.
        assertFalse(needsYes(dialer("112", phoneApp = "com.example.chat"), tapCall))
        // A button whose name is one digit and a word is no key of the dial pad.
        val sim = element("sim", 1, IntRect(0, 1800, 300, 1900), text = "1 Telenor")
        val withSim = dialer("112").let { it.copy(elements = it.elements + sim) }
        assertTrue(needsYes(withSim, Command.Tap(Target.Label("1 Telenor"))) { it == "112" })
        // A dialog of the phone app, such as "Call with a note", is a window of its own, which counts too.
        val note = WindowSummary(2, WindowKind.APPLICATION, packageName = DIALER, bounds = IntRect(100, 800, 980, 1400))
        val send = element("send", 2, IntRect(600, 1300, 900, 1380), text = "Send and call")
        val withDialog = dialer("112").let { it.copy(windows = listOf(note) + it.windows, elements = it.elements + send) }
        assertTrue(needsYes(withDialog, Command.Tap(Target.Label("send and call"))) { it == "112" })
        // A name in the phone app's search box asks nothing; letters in the field with the view ID digits do.
        val search = dialer(null).let { snapshot ->
            snapshot.copy(elements = snapshot.elements + ScreenElement(
                key = "search", windowId = 1, packageName = DIALER, viewId = "$DIALER:id/search_box", text = "Ana", editable = true,
                bounds = IntRect(0, 200, 1080, 300),
            ))
        }
        assertFalse(needsYes(search, tapCall))
    }

    @Test
    fun everyCommandThatActsInSuchAWindowNeedsTheYesAndOnlyThose() {
        val snapshot = dialer("1985")
        for (command in listOf(
            Command.Expand(Target.Number(1)), Command.Collapse(Target.Number(2)), Command.Check(Target.Number(2), on = true),
            Command.Scroll(Direction.DOWN, Target.Number(2)), Command.Scroll(Direction.DOWN, null), Command.LongPress(Target.Number(2)),
            Command.Swipe(Direction.UP, Target.Number(3)), Command.Drag(Target.Number(3), Target.Number(4)),
            Command.Drag(Target.Number(3), null), Command.Dismiss(Target.Number(1)), Command.Bare.of("2"),
        )) {
            assertTrue(command.toString(), needsYes(snapshot, command))
        }
        for (command in listOf(Command.WhatIs(2), Command.ShowNumbers, TextCommand.NewLine, Command.Bare.of("3"))) {
            assertFalse(command.toString(), needsYes(snapshot, command))
        }
    }

    @Test
    fun androidsEmergencyDialerCountsAsAPhoneWindowAlthoughItsFieldIsNoTextField() {
        val emergencyDialer = dialer("112", phoneApp = "com.android.phone", editable = false)

        assertTrue(needsYes(emergencyDialer, Command.Tap(Target.Label("dial"))) { it == "112" })
        assertFalse(needsYes(emergencyDialer, Command.Tap(Target.Number(3))) { it == "112" })
        assertEquals(listOf("112"), AndroidDialer.fieldTexts(emergencyDialer, "com.android.phone"))

        // One UI's emergency dialer, whose number field Kestrel cannot read, counts by its title.
        val samsung = "com.samsung.android.app.telephonyui"
        val oneUi = ScreenSnapshot(
            0,
            listOf(WindowSummary(1, WindowKind.APPLICATION, packageName = samsung, title = "Emergency call", bounds = screen)),
            listOf(
                element("number", 1, IntRect(0, 0, 1080, 200), samsung, text = "112", viewId = "dialer_number", clickable = false),
                element("one", 1, IntRect(0, 1000, 360, 1200), samsung, text = "1", viewId = "one"),
                element("call", 1, IntRect(400, 2000, 680, 2200), samsung, text = "Call"),
            ),
        )
        val view = ScreenView(oneUi, emptyList(), grid = null)
        fun yes(command: Command) =
            EmergencyGuard.needsYes(EmergencyGuard.reaches(command, screen), view, EmergencyGuard.PHONE_PACKAGES, noEmergency)
        assertTrue(yes(Command.Tap(Target.Label("call"))))
        assertFalse(yes(Command.Tap(Target.Label("1"))))

        // Without a title, a text that R1 names guards the package's other elements, and its windows without elements too.
        val popup = WindowSummary(2, WindowKind.APPLICATION, packageName = samsung, bounds = IntRect(0, 1500, 1080, 1900))
        val untitled = ScreenSnapshot(
            0,
            listOf(popup, WindowSummary(1, WindowKind.APPLICATION, packageName = samsung, bounds = screen)),
            oneUi.elements + element("head", 1, IntRect(0, 300, 1080, 400), samsung, text = "Emergency call", clickable = false),
        )
        val untitledView = ScreenView(untitled, emptyList(), grid = null)
        val tapCall = EmergencyGuard.reaches(Command.Tap(Target.Label("call")), screen)
        assertTrue(EmergencyGuard.needsYes(tapCall, untitledView, EmergencyGuard.PHONE_PACKAGES, noEmergency))
        assertEquals(setOf(1, 2), EmergencyGuard.guardedWindows(untitled, EmergencyGuard.PHONE_PACKAGES, noEmergency))
    }

    @Test
    fun aLateYesCountsOnlyWhileTheCommandFindsTheSameElementsInTheSameWindows() {
        val snapshot = dialer("112")
        val reaches = EmergencyGuard.reaches(Command.Tap(Target.Number(2)), screen)
        val asked = EmergencyGuard.resolved(reaches, view(snapshot))

        assertEquals(asked, EmergencyGuard.resolved(reaches, view(snapshot)))
        // The numbers changed under the question: 2 now names the row.
        val moved = ScreenView(snapshot, listOf(Badge(2, "more", snapshot.element("more")!!.bounds)), grid = null)
        assertFalse(asked == EmergencyGuard.resolved(reaches, moved))
        // The same key in another window.
        val other = snapshot.copy(elements = snapshot.elements.map { if (it.key == "call") it.copy(windowId = 2) else it })
        assertFalse(asked == EmergencyGuard.resolved(reaches, view(other)))
    }

    @Test
    fun anElementNamedEmergencyOrSosNeedsTheYesAlsoThroughItsParentAndACaptionOverIt() {
        val ui = "com.android.systemui"
        val menu = WindowSummary(1, WindowKind.SYSTEM, packageName = ui, bounds = screen)
        val snapshot = ScreenSnapshot(
            0,
            listOf(menu),
            listOf(
                element("power", 1, IntRect(0, 0, 360, 300), ui, text = "Power off"),
                element("sos", 1, IntRect(360, 0, 720, 300), ui, viewId = "emergencyButton"),
                // A button inside the emergency button, which takes a touch of its own.
                element("icon", 1, IntRect(400, 50, 680, 250), ui, description = "Phone icon", parent = 1),
                element("help", 1, IntRect(720, 0, 1080, 300), ui, description = "SOS messages"),
                element("caption", 1, IntRect(450, 100, 630, 200), ui, text = "Call", clickable = false),
            ),
        )
        val view = ScreenView(snapshot, emptyList(), grid = null)
        fun yes(command: Command) =
            EmergencyGuard.needsYes(EmergencyGuard.reaches(command, screen), view, EmergencyGuard.PHONE_PACKAGES, noEmergency)

        assertTrue(EmergencyGuard.emergencyNamed(snapshot, 1))
        for (label in listOf("phone icon", "SOS messages", "call")) {
            assertTrue(label, yes(Command.Tap(Target.Label(label))))
        }
        assertFalse(yes(Command.Tap(Target.Label("power off"))))
        // Kestrel's own bar, which shows the question, is no emergency button.
        val bar = snapshot.copy(elements = listOf(element("bar", 1, screen, "dev.kestrel", text = PhoneCommandTexts.EMERGENCY_QUESTION)))
        assertFalse(EmergencyGuard.emergencyNamed(bar, 0))
    }

    @Test
    fun thePhoneNumbersOnTheScreenComeFromTheAppWindowsOnly() {
        val app = WindowSummary(1, WindowKind.APPLICATION, bounds = IntRect(0, 0, 1080, 2400))
        val bar = WindowSummary(2, WindowKind.SYSTEM, bounds = IntRect(0, 0, 1080, 100))
        fun text(key: String, text: String, window: Int = 1, editable: Boolean = false) =
            ScreenElement(key = key, windowId = window, text = text, bounds = IntRect(0, 0, 10, 10), editable = editable)
        val snapshot = ScreenSnapshot(
            0,
            listOf(bar, app),
            listOf(
                text("office", "Office: +381 11 234 5678"),
                text("again", "Office +381-11-234-5678"),
                text("date", "Meeting on 17.11.2026"),
                text("status", "Missed call from 064 999 8888", window = 2),
                text("field", "060 111 2222", editable = true),
                text("mobile", "Mobile 064 123 4567"),
            ),
        )

        val found = NumberCopier.numbersOn(snapshot).map { it.first.key to it.second }

        assertEquals(listOf("office" to "+381 11 234 5678", "mobile" to "064 123 4567"), found)
    }
}
```

- [ ] **Step 2: Write the failing device tests and the phone-number fixture**

Create `app/src/androidTest/kotlin/dev/kestrel/app/commands/CallCommandsTest.kt`:

```kotlin
package dev.kestrel.app.commands

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.app.screen.ScreenServiceRule.Companion.KESTREL
import dev.kestrel.app.screen.TestFinger
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.commands.Outcome
import dev.kestrel.screen.model.ScreenElement
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Calls by number and by name, with the announcement and the countdown, and "copy this phone number" (design D11), on
 * the emulator's phone app, whose calls to a made-up number become active, and the owner's "yes, call" before anything that
 * may call an emergency number (design spec, section 8.7), which these tests never answer with yes in the phone app. A test
 * that fails during a call ends it. The tests grant the phone-state and contacts permissions; command-test.ps1 revokes what was not granted before, and deletes the test contacts and the new call-log
 * rows.
 */
@RunWith(AndroidJUnit4::class)
class CallCommandsTest {
    // Before ScreenServiceRule, as in plan 3A's classes: a run without kestrelCommands=true skips each test before the rule
    // switches the accessibility services, and before @Before and @After run.
    @get:Rule(order = 0)
    val only = CommandTests.only

    @get:Rule(order = 1)
    val screen = ScreenServiceRule()

    private val listening get() = CommandTests.graph.listening
    private var callLog = 0L

    @Before
    fun start() {
        // Before the start: the call state reaches Kestrel only with this permission (design spec, section 8.7).
        screen.automation.grantRuntimePermission(KESTREL, Manifest.permission.READ_PHONE_STATE)
        screen.automation.grantRuntimePermission(KESTREL, Manifest.permission.READ_CONTACTS)
        PhoneTests.removeTestContacts()
        callLog = PhoneTests.largestRowId(PhoneTests.CALL_LOG)
        CommandTests.start()
    }

    @After
    fun stop() {
        try {
            // A test that failed during a call leaves it active; without a call, the key would turn the screen off.
            if (listening.state.value.mode == Mode.IN_CALL) screen.shell("input keyevent KEYCODE_ENDCALL")
            CommandTests.stop()
        } finally {
            PhoneTests.deleteRowsAfter(PhoneTests.CALL_LOG, callLog)
            PhoneTests.removeTestContacts()
            screen.shell("input keyevent KEYCODE_HOME")
        }
    }

    @Test
    fun aNumberIsAnnouncedCountedDownAndCalled() {
        assertEquals(Outcome.DONE, CommandTests.say("call 064 123 4567").outcome)

        CommandTests.awaitSaid("Calling 0 6 4 1 2 3 4 5 6 7.")
        assertTrue("No call is active", VoiceTests.waitUntil(15_000) { listening.state.value.mode == Mode.IN_CALL })
        // The emulator's in-call screen names its end control "End call" (plan 2D).
        assertTrue("No end-call control", VoiceTests.waitUntil(5_000) { screen.session.actions.clickLabelled("End call", EmergencyGuard.PHONE_PACKAGES) })
        assertTrue("Listening did not come back", VoiceTests.waitUntil(15_000) { listening.state.value.mode == Mode.LISTENING })
    }

    @Test
    fun cancelDuringTheCountdownLeavesTheNumberInThePhoneApp() = runBlocking<Unit> {
        CommandTests.send("call 064 123 4567")
        assertTrue("No countdown", VoiceTests.waitUntil(10_000) { listening.state.value.counting })

        assertEquals(Outcome.CANCELLED, CommandTests.answer("cancel").outcome)

        screen.awaitElement { it.viewId?.endsWith(":id/digits") == true }
        Thread.sleep(COUNTDOWN_AND_MORE)
        assertTrue(listening.state.value.mode == Mode.LISTENING)
    }

    @Test
    fun aTouchDuringTheCountdownCancelsIt() = runBlocking<Unit> {
        TestFinger(screen.automation).use { finger ->
            CommandTests.send("call 064 123 4567")
            assertTrue("No countdown", VoiceTests.waitUntil(10_000) { listening.state.value.counting })
            screen.awaitInputWindow("Kestrel touch watcher")
            val digits = screen.awaitElement { it.viewId?.endsWith(":id/digits") == true }.bounds

            finger.tap(digits.centerX, digits.centerY)

            assertEquals(Outcome.CANCELLED, CommandTests.next().outcome)
        }
        Thread.sleep(COUNTDOWN_AND_MORE)
        assertTrue(listening.state.value.mode == Mode.LISTENING)
    }

    @Test
    fun anEmergencyNumberOrAnUnreadableOneInThePhoneAppNeedsTheOwnersYesForAllButItsKeys() = runBlocking<Unit> {
        // Only "no" answers the question here: never "yes, call" with an emergency number, on any device.
        assertEquals(PhoneCommandTexts.EMERGENCY_QUESTION, CommandTests.ask("call 112"))
        assertTrue(CommandTests.spoken.contains("Calling 1 1 2."))
        assertEquals(Outcome.CANCELLED, CommandTests.answer("no").outcome)
        val digits = screen.awaitElement { it.viewId?.endsWith(":id/digits") == true && it.text?.filter(Char::isDigit) == "112" }
        // The number field has the focus, but its input type is a phone number's, so plan 3B's dictation leaves a bare
        // "call" to the phone part. A number names any element, so "expand", "collapse" and "drag" with the call button's
        // number need the yes too, and so does a scroll, whose gesture starts in the phone app's window.
        assertEquals(Outcome.DONE, CommandTests.say("show numbers").outcome)
        val button = screen.awaitElement { it.viewId?.endsWith(":id/dialpad_voice_call_button") == true }
        val number = screen.session.numberBadges.first { it.key == button.key }.number
        for (said in listOf("call", "tap dial", "expand $number", "collapse $number", "drag $number to $number", "scroll down")) {
            assertEquals(said, PhoneCommandTexts.EMERGENCY_QUESTION, CommandTests.ask(said))
            assertEquals(said, Outcome.CANCELLED, CommandTests.answer("no").outcome)
        }
        // The digit keys and the delete key need no yes, so that the owner can correct the number by voice.
        assertEquals(Outcome.DONE, CommandTests.say("tap ${badge { it.viewId?.endsWith(":id/five") == true }}").outcome)
        screen.awaitElement { it.key == digits.key && it.text?.filter(Char::isDigit) == "1125" }
        assertEquals(Outcome.DONE, CommandTests.say("tap ${badge { it.viewId?.endsWith(":id/deleteButton") == true }}").outcome)
        screen.awaitElement { it.key == digits.key && it.text?.filter(Char::isDigit) == "112" }
        assertEquals(Outcome.DONE, CommandTests.say("hide numbers").outcome)
        assertTrue("A call started", listening.state.value.mode == Mode.LISTENING)

        // A field that reads as no number hides what the phone app would dial, so the window needs the yes too; a guard
        // that took only the digits, or let an unreadable field through, would press Call here.
        screen.session.actions.setText(digits.key, "abc")
        screen.awaitElement { it.key == digits.key && it.text == "abc" }
        assertEquals(PhoneCommandTexts.EMERGENCY_QUESTION, CommandTests.ask("tap dial"))
        assertEquals(Outcome.CANCELLED, CommandTests.answer("no").outcome)
        Thread.sleep(COUNTDOWN_AND_MORE)
        assertTrue(listening.state.value.mode == Mode.LISTENING)
    }

    @Test
    fun anElementNamedEmergencyIsClickedOnlyAfterYesCall() = runBlocking<Unit> {
        screen.open("PhoneNumberActivity", "Phone numbers fixture")

        // The fixture's "Emergency call" button calls no one; it only shows that it was clicked.
        assertEquals(PhoneCommandTexts.EMERGENCY_QUESTION, CommandTests.ask("tap emergency call"))
        assertEquals(Outcome.CANCELLED, CommandTests.answer("no").outcome)
        Thread.sleep(1_000)
        assertTrue(screen.session.snapshots.current().snapshot.elements.none { it.label == "Emergency call pressed" })

        assertEquals(PhoneCommandTexts.EMERGENCY_QUESTION, CommandTests.ask("tap emergency call"))
        assertEquals(Outcome.DONE, CommandTests.answer("yes, call").outcome)
        screen.awaitElement { it.packageName == FIXTURES && it.label == "Emergency call pressed" }
    }

    @Test
    fun aYesAfterTheScreenChangedUnderTheQuestionEndsTheCommand() = runBlocking<Unit> {
        screen.open("PhoneNumberActivity", "Phone numbers fixture")
        assertEquals(PhoneCommandTexts.EMERGENCY_QUESTION, CommandTests.ask("tap emergency call"))

        // An accessibility click, which is no touch, so the question stays: another "Emergency call" button takes the
        // place of the one that the question was about.
        screen.session.actions.tap(screen.awaitElement { it.viewId?.endsWith(":id/rearrange_button") == true }.key)
        screen.awaitElement { it.viewId?.endsWith(":id/emergency_button_moved") == true }

        assertEquals(Outcome.FAILED, CommandTests.answer("yes, call").outcome)
        CommandTests.awaitSaid(CommandTexts.SCREEN_CHANGED)
        Thread.sleep(1_000)
        assertTrue(screen.session.snapshots.current().snapshot.elements.none { it.label == "Emergency call pressed" })
    }

    // The number that the numbers overlay shows on the element that [which] picks.
    private suspend fun badge(which: (ScreenElement) -> Boolean): Int {
        val element = screen.awaitElement(predicate = which)
        return screen.session.numberBadges.first { it.key == element.key }.number
    }

    @Test
    fun aNameAsksWhichOneAndWhichNumberAndIsAnnounced() {
        PhoneTests.addContact("Ana", "Petrović", "0641234567" to PhoneTests.MOBILE, "0112345678" to PhoneTests.WORK)
        PhoneTests.addContact("Ana", "Jovanović", "0657654321" to PhoneTests.MOBILE)

        assertEquals("Which one? 1, Ana Jovanović. 2, Ana Petrović.", CommandTests.ask("call Ana"))
        CommandTests.send("2")
        assertTrue("No second question", VoiceTests.waitUntil(10_000) { CommandTests.spoken.lastOrNull() == "Which number? Mobile or work?" })
        CommandTests.send("work")
        assertTrue("No countdown", VoiceTests.waitUntil(10_000) { listening.state.value.counting })

        assertEquals("Calling Ana Petrović, work.", CommandTests.spoken.last())
        assertEquals(Outcome.CANCELLED, CommandTests.answer("stop").outcome)
    }

    @Test
    fun copyThisPhoneNumberCopiesTheOnlyNumberOrTheOneThatTheOwnerChooses() = runBlocking<Unit> {
        screen.open("PhoneNumberActivity", "Phone numbers fixture", "--ez single true")
        assertEquals(Outcome.DONE, CommandTests.say("copy this phone number").outcome)
        screen.session.actions.tap(screen.awaitElement { it.packageName == FIXTURES && it.label == "Paste" }.key)
        screen.awaitElement { it.label == "Pasted +381 11 234 5678" }

        screen.open("PhoneNumberActivity", "Phone numbers fixture")
        assertEquals(CommandTexts.WHICH_ONE, CommandTests.ask("copy the phone number"))
        // The numbers go from the top: the office's is 1 and the mobile's is 2. The date is no phone number.
        assertEquals(2, screen.session.numberBadges.size)
        assertEquals(Outcome.DONE, CommandTests.answer("2").outcome)
        screen.session.actions.tap(screen.awaitElement { it.packageName == FIXTURES && it.label == "Paste" }.key)
        screen.awaitElement { it.label == "Pasted 064 123 4567" }
    }

    private companion object {
        // The 4 s countdown and a little more, after which a call that was not cancelled would have started.
        const val COUNTDOWN_AND_MORE = 6_000L
    }
}
```

Create `testapp/src/main/kotlin/dev/kestrel/testapp/PhoneNumberActivity.kt`:

```kotlin
package dev.kestrel.testapp

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView

/**
 * Phone numbers for "copy this phone number" (design spec, Appendix A): an office number, a mobile number (left out with
 * the extra `single`) and a date that is no phone number. Kestrel cannot read the clipboard, so the "Paste" button reads
 * it here, while the fixture is in front, and the status line shows "Pasted" and the number. The "Emergency call" button
 * calls no one: it only shows "Emergency call pressed", for the owner's "yes, call" (design spec, section 8.7). "Rearrange"
 * puts another "Emergency call" button, with another view ID, in its place, so that the screen changes under the question.
 */
class PhoneNumberActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = fixturePage("Phone numbers fixture")
        val status = page.addStatus()
        page.addView(TextView(this).apply {
            id = R.id.office_number
            text = "Office: +381 11 234 5678"
        })
        if (!intent.getBooleanExtra(EXTRA_SINGLE, false)) {
            page.addView(TextView(this).apply {
                id = R.id.mobile_number
                text = "Mobile: 064 123 4567"
            })
        }
        page.addView(TextView(this).apply {
            id = R.id.meeting_date
            text = "Meeting on 17.11.2026"
        })
        page.addView(Button(this).apply {
            id = R.id.paste_button
            text = "Paste"
            isAllCaps = false
            setOnClickListener {
                val clip = getSystemService(ClipboardManager::class.java).primaryClip
                status.text = "Pasted ${clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text ?: ""}".trim()
            }
        })
        val emergency = Button(this).apply {
            id = R.id.emergency_button
            text = "Emergency call"
            isAllCaps = false
            setOnClickListener { status.text = "Emergency call pressed" }
        }
        val moved = Button(this).apply {
            id = R.id.emergency_button_moved
            text = "Emergency call"
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener { status.text = "Emergency call pressed" }
        }
        page.addView(emergency)
        page.addView(moved)
        page.addView(Button(this).apply {
            id = R.id.rearrange_button
            text = "Rearrange"
            isAllCaps = false
            setOnClickListener {
                emergency.visibility = View.GONE
                moved.visibility = View.VISIBLE
            }
        })
    }

    private companion object {
        const val EXTRA_SINGLE = "single"
    }
}
```

Create `testapp/src/main/res/values/ids_phone.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- The view IDs of plan 3C's fixtures, which Android merges with ids.xml. MediaActivity needs none. -->
<resources>
    <item name="office_number" type="id" />
    <item name="mobile_number" type="id" />
    <item name="meeting_date" type="id" />
    <item name="paste_button" type="id" />
    <item name="emergency_button" type="id" />
    <item name="emergency_button_moved" type="id" />
    <item name="rearrange_button" type="id" />
</resources>
```

In `testapp/src/main/AndroidManifest.xml`, replace

```xml
        <!-- Plan 3C: phone fixtures -->
```

with

```xml
        <activity
            android:name=".PhoneNumberActivity"
            android:exported="true" />
        <!-- Plan 3C: phone fixtures -->
```

- [ ] **Step 3: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 --continue :core:commands:test --tests dev.kestrel.commands.GrammarPhoneTest :app:testDebugUnitTest --tests dev.kestrel.app.commands.PhoneCallsTest`
Expected: `BUILD FAILED`: `GrammarPhoneTest` runs 10 tests and the 2 new ones fail, at "Call 064 123 4567." and "Copy this phone number.", which are still bare texts; `:app`'s tests do not compile: "Unresolved reference 'CallCommands'".

- [ ] **Step 4: Write the phrases and the command list's group**

In `core/commands/src/main/kotlin/dev/kestrel/commands/GrammarPhone.kt`, replace

```kotlin
package dev.kestrel.commands

/**
```

with

```kotlin
package dev.kestrel.commands

import dev.kestrel.commands.phone.PhoneNumbers
import dev.kestrel.commands.text.Normalizer

/**
```

Then replace

```kotlin
        // Tasks 2 to 6 of plan 3C add their phrases above this line.
```

with

```kotlin
        phrases(
            PhoneCommand.CopyPhoneNumber,
            "copy this phone number", "copy the phone number", "copy phone number", "copy this number", "copy the number",
        )
```

Then replace

```kotlin
        // Tasks 2 to 6 of plan 3C add their rules above this line.
```

with

```kotlin
        call(words, text)?.let { return it }
```

Then replace

```kotlin
    // Tasks 2 to 6 of plan 3C add their functions above this line.
```

with

```kotlin
    private val CALL_VERBS = setOf("call", "dial")
    private val KINDS = setOf("mobile", "cell", "work", "home", "main")
    private val NUMBER_WORDS = setOf("number", "phone")
    private val PLACE_WORDS = setOf("on", "at")
    private val POSSESSIVE = Regex("['\u2019]s$", RegexOption.IGNORE_CASE)

    // "Call 064 123 4567", "call Ana", "call Ana mobile", "call Ana on work", "call Ana's work number", "call Ana's gym
    // number". The kind is a known kind after the name, or the words after a name with "'s". A name or a number never
    // ends with a polite word, which the parser removes only from a bare text: "call Ana now".
    private fun call(words: List<String>, text: String): Command? {
        var said = words.size
        while (said > 1 && words[said - 1] in CommandParser.POLITE_WORDS) said--
        if (said < 2 || words[0] !in CALL_VERBS) return null
        PhoneNumbers.digits(numberText(text, said))?.let { return PhoneCommand.Call(CallTarget.Number(it)) }
        val end = if (said > 2 && words[said - 1] in NUMBER_WORDS) said - 1 else said
        val owner = (1 until end).firstOrNull { POSSESSIVE.containsMatchIn(cutWords(text, it, it + 1)) }
        if (owner != null) {
            val name = cutWords(text, 1, owner + 1).replace(POSSESSIVE, "")
            val kind = words.subList(owner + 1, end).joinToString(" ").ifEmpty { null }
            return PhoneCommand.Call(CallTarget.Name(name, kind))
        }
        if (end > 2 && words[end - 1] in KINDS) {
            var nameEnd = end - 1
            if (nameEnd > 2 && words[nameEnd - 1] in PLACE_WORDS) nameEnd--
            return PhoneCommand.Call(CallTarget.Name(cutWords(text, 1, nameEnd), words[end - 1]))
        }
        return PhoneCommand.Call(CallTarget.Name(cutWords(text, 1, said), null))
    }

    private const val NUMBER_SIGNS = "+*# "

    // The words after the verb with the "+", "*" and "#" around them, which Normalizer drops as punctuation.
    private fun numberText(text: String, end: Int): String {
        val folded = Normalizer.withOffsets(text)
        val span = folded.span(1, end)
        val verbEnd = folded.span(0, 1).last + 1
        var start = span.first
        while (start > verbEnd && text[start - 1] in NUMBER_SIGNS) start--
        var stop = span.last + 1
        while (stop < text.length && text[stop] in NUMBER_SIGNS) stop++
        return text.substring(start, stop)
    }
```

In `core/commands/src/main/kotlin/dev/kestrel/commands/HelpPhone.kt`, replace

```kotlin
        // Tasks 2 to 6 of plan 3C add their groups above this line.
```

with

```kotlin
        HelpGroup("Calls", listOf("call Ana", "call Ana mobile", "call 064 123 4567", "copy this phone number")),
```

- [ ] **Step 5: Run the tests of `:core:commands` to verify that they pass**

Run: `scripts\gradle.ps1 :core:commands:test`
Expected: `BUILD SUCCESSFUL`, with 109 tests (`GrammarPhoneTest` 10); `GrammarScreenTest` and `CommandHelpTest` still pass.

- [ ] **Step 6: Write the calls and "copy this phone number"**

Create `app/src/main/kotlin/dev/kestrel/app/commands/CallCommands.kt`:

```kotlin
package dev.kestrel.app.commands

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import dev.kestrel.app.settings.SettingsPage
import dev.kestrel.commands.CallTarget
import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Direction
import dev.kestrel.commands.Edge
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.PhoneCommand
import dev.kestrel.commands.Target
import dev.kestrel.commands.TextCommand
import dev.kestrel.commands.phone.ContactMatch
import dev.kestrel.commands.phone.ContactMatcher
import dev.kestrel.commands.phone.NumberChoice
import dev.kestrel.commands.phone.PhoneEntry
import dev.kestrel.commands.phone.PhoneNumbers
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.commands.text.Strictness
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.GesturePaths
import dev.kestrel.screen.act.ScreenEdge
import dev.kestrel.screen.act.ScrollDirection
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** How a press of the phone app's call button ended. */
internal enum class Pressed { CALLING, CHOOSE_SIM, REFUSED, OTHER_NUMBER }

/**
 * What "call X" does with a number (design D11 and the controller's decisions): [CALL] after the countdown, [EMERGENCY]
 * after the owner's "yes, call" (or not at all under [EmergencyPolicy.TOUCH_ONLY]), and [TAP_YOURSELF] never.
 */
internal enum class DialRule { CALL, EMERGENCY, TAP_YOURSELF }

/** The default phone app as "call X" uses it; a fake in the JVM tests. */
internal interface Dialer {
    /** What Kestrel does with [digits]; see [DialRule]. */
    fun rule(digits: String): DialRule

    /** Opens the default phone app with [digits]; false when it could not. */
    suspend fun open(digits: String): Boolean

    /** Waits up to 3 s for the phone app's call button; false when none shows. */
    suspend fun awaitCallButton(): Boolean

    /**
     * Presses the call button, unless the phone app already shows the call, or its number field shows another number than
     * [digits], which Kestrel announced.
     */
    suspend fun press(digits: String): Pressed
}

/**
 * "Call [number]" and "call [name]" (design spec, section 13.6; decision 1). Kestrel never requests `CALL_PHONE`: it opens
 * the default phone app with the number, says whom it calls, counts down 4 s from the end of that announcement on the bar
 * and presses the phone app's call button. "Cancel", "stop" or "no", or a touch, stops the countdown, and the phone app
 * stays open with the number. For an emergency number Kestrel follows [policy]: it asks for "yes, call" after the
 * announcement instead of the countdown, or only opens the phone app. For a number with fewer than 5 digits or one with
 * `*` or `#`, Kestrel only opens the phone app. A contact's number keeps its pause, wait or extension, which the phone app sends after the
 * call has started. Contacts come from [contacts], which needs `READ_CONTACTS`; without it Kestrel opens its settings app,
 * which asks for it. A call is never repeated by "again".
 */
internal class CallCommands(
    private val contacts: ContactsReader,
    private val dialer: Dialer,
    private val openSettings: (SettingsPage?, String?) -> Unit,
    private val policy: EmergencyPolicy = EmergencyPolicy.CURRENT,
) {
    suspend fun call(who: CallTarget, conversation: CommandConversation): CommandResult {
        val (digits, whom) = when (who) {
            is CallTarget.Number -> who.digits to PhoneCommandTexts.spoken(who.digits)
            is CallTarget.Name -> when (val found = contact(who, conversation)) {
                is Found.Number -> found.digits to found.whom
                is Found.Ends -> return found.result
            }
        }
        val rule = dialer.rule(digits)
        if (rule == DialRule.TAP_YOURSELF || rule == DialRule.EMERGENCY && policy == EmergencyPolicy.TOUCH_ONLY) {
            if (!dialer.open(digits)) return CommandResult(Outcome.FAILED, say = PhoneCommandTexts.NO_PHONE_APP)
            return CommandResult(Outcome.DONE, say = PhoneCommandTexts.tapCallYourself(whom))
        }
        if (!dialer.open(digits)) return CommandResult(Outcome.FAILED, say = PhoneCommandTexts.NO_PHONE_APP)
        if (!dialer.awaitCallButton()) return CommandResult(Outcome.FAILED, say = PhoneCommandTexts.OPENED_TAP_CALL)
        conversation.say(PhoneCommandTexts.calling(whom))
        if (rule == DialRule.EMERGENCY) {
            // Never through the countdown alone: an emergency number needs the owner's "yes, call".
            EmergencyGuard.confirm(policy, conversation)?.let { return it }
        } else if (!conversation.countdown(COUNTDOWN_MILLIS) { PhoneCommandTexts.callingIn(whom, it) }) {
            // The owner's speech holds the countdown until its transcript decides; a touch or 30 s of holds give up.
            return CommandResult(Outcome.CANCELLED)
        }
        return when (dialer.press(digits)) {
            Pressed.CALLING -> CommandResult(Outcome.DONE)
            Pressed.CHOOSE_SIM -> CommandResult(Outcome.DONE, show = PhoneCommandTexts.CHOOSE_SIM)
            Pressed.REFUSED -> CommandResult(Outcome.FAILED, say = PhoneCommandTexts.COULD_NOT_PRESS)
            Pressed.OTHER_NUMBER -> CommandResult(Outcome.FAILED, say = PhoneCommandTexts.OTHER_NUMBER)
        }
    }

    private sealed interface Found {
        /** The number to call, and whom Kestrel names: "Ana Petrović, mobile". */
        class Number(val digits: String, val whom: String) : Found

        class Ends(val result: CommandResult) : Found
    }

    private suspend fun contact(who: CallTarget.Name, conversation: CommandConversation): Found {
        val all = contacts.contacts() ?: run {
            openSettings(null, Manifest.permission.READ_CONTACTS)
            return Found.Ends(CommandResult(Outcome.FAILED, say = PhoneCommandTexts.NEED_CONTACTS))
        }
        // Only contacts that Kestrel can call are offered; a contact without a number is found only when no other matches.
        val callable = all.filter { it.numbers.isNotEmpty() }
        val match = ContactMatcher.find(who.name, callable).takeUnless { it == ContactMatch.None } ?: ContactMatcher.find(who.name, all)
        val contact = when (match) {
            is ContactMatch.One -> match.contact
            is ContactMatch.Several -> {
                val names = match.contacts.map { it.names.first() }
                when (val answer = askAmong(conversation, PhoneCommandTexts.whichOne(names), names)) {
                    is Answer.Chosen -> match.contacts[answer.index]
                    is Answer.Ends -> return Found.Ends(answer.result)
                }
            }
            is ContactMatch.TooMany ->
                return Found.Ends(CommandResult(Outcome.FAILED, say = PhoneCommandTexts.tooManyContacts(match.count, who.name)))
            ContactMatch.None -> return Found.Ends(CommandResult(Outcome.FAILED, say = PhoneCommandTexts.cantFindContact(who.name)))
        }
        val name = contact.names.first()
        val entry = when (val choice = ContactMatcher.number(contact, who.kind)) {
            is NumberChoice.One -> choice.entry
            is NumberChoice.Several -> {
                val kinds = choice.entries.map { it.kind }
                // The answer is a number or a kind, where "cell" counts as "mobile".
                val question = PhoneCommandTexts.whichNumber(kinds)
                when (val answer = askAmong(conversation, question, kinds.map(ContactMatcher::kindOf), ContactMatcher::kindOf)) {
                    is Answer.Chosen -> choice.entries[answer.index]
                    is Answer.Ends -> return Found.Ends(answer.result)
                }
            }
            NumberChoice.None -> {
                val missing = who.kind?.let { PhoneCommandTexts.noNumberOfKind(name, it) } ?: PhoneCommandTexts.noNumber(name)
                return Found.Ends(CommandResult(Outcome.FAILED, say = missing))
            }
        }
        return Found.Number(digitsOf(entry), PhoneCommandTexts.contactAndKind(name, entry.kind))
    }

    // The stored number as the phone app takes it, with its pause, wait or extension; a number that is no plain number,
    // such as one with letters, goes to the phone app as it is stored, and Kestrel never presses Call for it.
    private fun digitsOf(entry: PhoneEntry): String = PhoneNumbers.dialable(entry.number) ?: entry.number

    companion object {
        /** The countdown after the announcement (decision 1). */
        const val COUNTDOWN_MILLIS = 4_000L

        fun create(environment: CommandEnvironment) = CallCommands(
            ContactsReader(contactsAccess(environment.context), AndroidContacts(environment.context.contentResolver), environment.report),
            AndroidDialer(environment.context, environment.screen),
            environment.openSettings,
        )
    }
}

/**
 * The default phone app through Kestrel's accessibility service: `ACTION_DIAL` with a `tel:` link, which shows the number
 * (prototype P4), and its call button: on Google's phone app the view ID `dialpad_voice_call_button`, otherwise a button
 * named "Call", "Dial" or "Voice call" (Samsung's, not checked yet).
 */
internal class AndroidDialer(context: Context, private val screen: () -> ScreenSession?) : Dialer {
    private val telecom: TelecomManager? = context.getSystemService(TelecomManager::class.java)
    private val telephony: TelephonyManager? = context.getSystemService(TelephonyManager::class.java)

    override fun rule(digits: String): DialRule = ruleFor(digits) { emergency(telephony, it) }

    override suspend fun open(digits: String): Boolean {
        val session = screen() ?: return false
        // Uri.fromParts encodes "#", "*", "," and ";", which a parsed tel: link would cut off or change.
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", digits, null))
        telecom?.defaultDialerPackage?.let { intent.setPackage(it) }
        return session.system.start(intent) is ActionResult.Done
    }

    override suspend fun awaitCallButton(): Boolean = awaitButton(BUTTON_MILLIS) != null

    override suspend fun press(digits: String): Pressed {
        val session = screen() ?: return Pressed.REFUSED
        val button = awaitButton(AGAIN_MILLIS)
        val phoneApp = telecom?.defaultDialerPackage
        // The phone app may keep digits that were typed before; Kestrel presses Call only for the number that it announced.
        val shown = phoneApp?.let { shownNumber(session.snapshots.current().snapshot, it) }
        if (button != null) {
            // Without a number field that Kestrel can read, it cannot check the number, so it does not press.
            if (shown == null) return Pressed.REFUSED
            if (shown.filter(Char::isDigit) != digits.filter(Char::isDigit)) return Pressed.OTHER_NUMBER
        }
        if (button == null || session.actions.tap(button.key) is ActionResult.Failed) {
            return if (inCall(session.snapshots.current().snapshot)) Pressed.CALLING else Pressed.REFUSED
        }
        val chooser = withTimeoutOrNull(SIM_MILLIS) {
            while (!simChooser(session.snapshots.current().snapshot)) delay(POLL_MILLIS)
            true
        }
        return if (chooser == true) Pressed.CHOOSE_SIM else Pressed.CALLING
    }

    private suspend fun awaitButton(millis: Long): ScreenElement? {
        val session = screen() ?: return null
        val phoneApp = telecom?.defaultDialerPackage ?: return null
        return withTimeoutOrNull(millis) {
            var found: ScreenElement? = null
            while (found == null) {
                found = callButton(session.snapshots.current().snapshot, phoneApp)
                if (found == null) delay(POLL_MILLIS)
            }
            found
        }
    }

    // The phone app's in-call screen shows an "End call" control.
    private fun inCall(snapshot: ScreenSnapshot): Boolean {
        val phoneApp = telecom?.defaultDialerPackage
        return snapshot.elements.any { it.packageName == phoneApp && namesOf(snapshot, it).any { name -> name == "end call" } }
    }

    // A window of the phone app or of Android's call service that offers the SIM cards.
    private fun simChooser(snapshot: ScreenSnapshot): Boolean {
        val phoneApp = telecom?.defaultDialerPackage
        return snapshot.elements.any { element ->
            (element.packageName == phoneApp || element.packageName == TELECOM) &&
                namesOf(snapshot, element).any { "sim" in it.split(' ') }
        }
    }

    companion object {
        private const val BUTTON_MILLIS = 3_000L
        private const val AGAIN_MILLIS = 1_000L
        private const val SIM_MILLIS = 1_500L
        private const val POLL_MILLIS = 100L
        private const val GOOGLE_CALL_BUTTON = ":id/dialpad_voice_call_button"
        private const val TELECOM = "com.android.server.telecom"
        private val CALL_NAMES = setOf("call", "dial", "voice call")

        /**
         * The call button of [phoneApp] in [snapshot]: Google's by its view ID, and only when no element has that ID, a
         * clickable element named "Call", "Dial" or "Voice call" (design D11).
         */
        fun callButton(snapshot: ScreenSnapshot, phoneApp: String): ScreenElement? {
            val own = snapshot.elements.filter { it.enabled && it.packageName == phoneApp }
            return own.firstOrNull { it.viewId?.endsWith(GOOGLE_CALL_BUTTON) == true }
                ?: own.firstOrNull { it.clickable && namesOf(snapshot, it).any { name -> name in CALL_NAMES } }
        }

        /**
         * Whether [element] is a phone app's number field: a text field, or a field with the view ID `digits`, which
         * Android's emergency dialer does not make editable.
         */
        fun isNumberField(element: ScreenElement): Boolean =
            element.editable && !element.password || element.viewId?.endsWith(":id/digits") == true

        /** The texts of [phoneApp]'s number fields that are not empty; the number field is the first. */
        fun fieldTexts(snapshot: ScreenSnapshot, phoneApp: String): List<String> = snapshot.elements
            .filter { it.packageName == phoneApp && isNumberField(it) && !it.text.isNullOrBlank() }
            .mapNotNull { it.text }

        /** The text of [phoneApp]'s number field, or null when the field is empty or absent. */
        fun fieldText(snapshot: ScreenSnapshot, phoneApp: String): String? = fieldTexts(snapshot, phoneApp).firstOrNull()

        /** The number in [phoneApp]'s number field, as the phone app dials it, or null when the field is empty or no number. */
        fun shownNumber(snapshot: ScreenSnapshot, phoneApp: String): String? = fieldText(snapshot, phoneApp)?.let(PhoneNumbers::dialable)

        /**
         * Whether Android reports [digits] as an emergency number, or null when Kestrel cannot tell: without a
         * TelephonyManager, or when the check throws, as it can on a phone without telephony.
         */
        fun emergency(telephony: TelephonyManager?, digits: String): Boolean? = try {
            telephony?.isEmergencyNumber(digits)
        } catch (e: RuntimeException) {
            null
        }

        /**
         * Whether Kestrel may press Call for [digits] itself (design D11): a long number without "*" or "#" that Android
         * reports as no emergency number; when Kestrel cannot tell ([emergency] null), it does not.
         */
        fun mayCall(digits: String, emergency: Boolean?): Boolean = PhoneNumbers.mayAutoDial(digits) && emergency == false

        /**
         * What "call X" does with [digits]; [emergency] is Android's check of the main number, null when it cannot tell.
         * A code with `*` or `#`, or a stored number with letters, never; an emergency number, also when Android cannot
         * tell, after "yes, call"; a number with fewer than 5 digits never; any other after the countdown.
         */
        fun ruleFor(digits: String, emergency: (String) -> Boolean?): DialRule = when {
            !digits.all { it.isDigit() || it == '+' || it == ',' || it == ';' } -> DialRule.TAP_YOURSELF
            emergency(PhoneNumbers.mainNumber(digits)) != false -> DialRule.EMERGENCY
            !PhoneNumbers.mayAutoDial(digits) -> DialRule.TAP_YOURSELF
            else -> DialRule.CALL
        }

        private fun namesOf(snapshot: ScreenSnapshot, element: ScreenElement): List<String> =
            (ScreenTargets.names(snapshot, element) + listOfNotNull(element.contentDescription)).map { Normalizer.normalize(it) }
    }
}

/**
 * What Kestrel does with a command by voice that may call an emergency number (the owner's decision, decision 13 of
 * sub-project 3): [CONFIRM] asks for "yes, call" first, and [TOUCH_ONLY] refuses, so that the owner taps.
 */
internal enum class EmergencyPolicy {
    CONFIRM,
    TOUCH_ONLY,
    ;

    companion object {
        /** The one constant that switches the policy; the owner chose [CONFIRM]. */
        val CURRENT: EmergencyPolicy = CONFIRM
    }
}

/**
 * Keeps commands by voice from calling an emergency number without the owner's "yes, call" (design spec, section 8.7),
 * with two rules that need no list of buttons. R1: an element whose name (text, content description or view ID, and the
 * label of an element that takes a touch) holds the word "emergency" or "sos", or whose nearest clickable parent's does,
 * such as the emergency buttons of the power menu and the lock screen. R2: every window of a phone package while one of
 * its windows shows an element that R1 names (an emergency dialer), or a number field with a number that Kestrel would not
 * call itself (an emergency number, also when Android cannot tell, a number with fewer than 5 digits or with `*` or `#`,
 * or a text that reads as no number): every action on their elements, except the digit keys, the number field and its
 * delete key, and every gesture that starts or ends inside them. After a "yes, call", the command must still find the
 * same elements in the same windows, or it ends. The phone
 * part asks it before the screen part sees a command. Every variant of the sealed command model is listed, without an
 * `else`, so that the compiler names a new one; each target is found with the screen part's own search, so that a number
 * names an element whatever the command.
 */
internal class EmergencyGuard(
    context: Context,
    private val targets: ScreenTargets,
    private val screen: () -> ScreenSession?,
    private val policy: EmergencyPolicy = EmergencyPolicy.CURRENT,
) {
    private val telecom: TelecomManager? = context.getSystemService(TelecomManager::class.java)
    private val telephony: TelephonyManager? = context.getSystemService(TelephonyManager::class.java)

    /**
     * Null when [command] may run; otherwise how it ends: cancelled without the owner's "yes, call", or refused under
     * [EmergencyPolicy.TOUCH_ONLY]. A text command reaches the phone part only when the text part declined it, and the
     * executor then runs [heard] as a bare label through the screen part, so that bare text counts too.
     */
    suspend fun check(command: Command, heard: String, conversation: CommandConversation): CommandResult? {
        val session = screen() ?: return null
        val bare = if (command is TextCommand) reaches(Command.Bare.of(heard), session.screenBounds) else emptyList()
        val reaches = reaches(command, session.screenBounds) + bare
        if (reaches.isEmpty()) return null
        val view = targets.view() ?: return null
        val phoneApps = PHONE_PACKAGES + listOfNotNull(telecom?.defaultDialerPackage)
        if (!needsYes(reaches, view, phoneApps) { AndroidDialer.emergency(telephony, it) }) return null
        val asked = resolved(reaches, view)
        confirm(policy, conversation)?.let { return it }
        // The yes counts only for what the question was about: a number that names another element by now, or a screen
        // that changed under the question, ends the command.
        val now = targets.view() ?: return CommandResult(Outcome.FAILED, say = CommandTexts.SCREEN_CHANGED)
        return if (resolved(reaches, now) == asked) null else CommandResult(Outcome.FAILED, say = CommandTexts.SCREEN_CHANGED)
    }

    /** Where a command acts: an element that it names, found as the screen part finds it, or a point of a gesture. */
    sealed interface Reach {
        /** [gesture]: a swipe, a pinch or a drag on the element, which R2's exceptions do not cover. */
        data class Element(
            val target: Target?,
            val kind: TargetKind,
            val strictness: Strictness,
            val cells: Boolean,
            val gesture: Boolean = false,
        ) : Reach

        data class Point(val x: Int, val y: Int) : Reach

        /** The end of a drag that the owner gives after "Drag it where?", which can lie anywhere. */
        data object Anywhere : Reach
    }

    companion object {
        /** The phone apps and Android's call services; the default phone app counts too. */
        val PHONE_PACKAGES = setOf(
            "com.android.phone", "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer",
            "com.samsung.android.incallui", "com.android.server.telecom",
            // One UI's emergency dialer.
            "com.samsung.android.app.telephonyui",
        )
        private val EMERGENCY_WORDS = setOf("emergency", "sos")
        private val KEY_IDS = setOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "star", "pound")
        private val DELETE_IDS = setOf("deletebutton", "delete_button", "backspace")
        private val DELETE_NAMES = setOf("delete", "backspace", "erase")
        private val KEY_NAMES = setOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "#", "+")
        private const val KESTREL = "dev.kestrel"
        private const val NAME = "kestrel"

        /** Under [EmergencyPolicy.CONFIRM], asks for "yes, call": null when the owner said it; otherwise how the command ends. */
        suspend fun confirm(policy: EmergencyPolicy, conversation: CommandConversation): CommandResult? = when (policy) {
            EmergencyPolicy.TOUCH_ONLY -> CommandResult(Outcome.FAILED, say = PhoneCommandTexts.EMERGENCY_TOUCH_ONLY)
            // Another answer, the question's timer (null), a touch (null) or a cancel ends the command as cancelled.
            EmergencyPolicy.CONFIRM ->
                if (confirmed(conversation.ask(PhoneCommandTexts.EMERGENCY_QUESTION))) null else CommandResult(Outcome.CANCELLED)
        }

        /**
         * Whether [answer] is exactly "yes, call", with or without "Kestrel" after it; an answer that starts with the name
         * is a request of Kestrel's own, which ends the command before it gets here (plan 3A's rules).
         */
        fun confirmed(answer: String?): Boolean {
            val words = Normalizer.words(answer ?: return false).toMutableList()
            if (words.lastOrNull() == NAME) words.removeAt(words.lastIndex)
            return words == listOf("yes", "call")
        }

        /**
         * Whether one of [reaches] needs the owner's "yes, call" in [view] under R1 or R2; [phoneApps] are R2's packages and
         * [emergency] is Android's check, null when it cannot tell.
         */
        fun needsYes(reaches: List<Reach>, view: ScreenView, phoneApps: Set<String>, emergency: (String) -> Boolean?): Boolean {
            val snapshot = view.snapshot
            val named = snapshot.elements.indices.filterTo(HashSet()) { emergencyNamed(snapshot, it) }
            val windows = guardedWindows(snapshot, phoneApps, emergency)
            if (named.isEmpty() && windows.isEmpty()) return false
            val windowBounds = snapshot.windows.filter { it.id in windows }.map { it.bounds }
            // A tap reaches what lies under the point, also a button that holds a caption outside its view tree.
            val namedBounds = named.map { snapshot.elements[it] }.filter { it.isActionable }.map { it.bounds }
            fun point(x: Int, y: Int) = (windowBounds + namedBounds).any { it.holds(x, y) }
            fun element(key: String, gesture: Boolean): Boolean {
                val index = snapshot.elements.indexOfFirst { it.key == key }
                if (index < 0) return false
                val found = snapshot.elements[index]
                val centreX = found.bounds.centerX
                val centreY = found.bounds.centerY
                if (index in named || touchParent(snapshot, index) in named || namedBounds.any { it.holds(centreX, centreY) }) return true
                if (gesture) return found.windowId in windows || windowBounds.any { it.holds(centreX, centreY) }
                return found.windowId in windows && !typesTheNumber(snapshot, actor(snapshot, index))
            }
            return reaches.any { reach ->
                when (reach) {
                    is Reach.Point -> point(reach.x, reach.y)
                    Reach.Anywhere -> true
                    is Reach.Element -> when (val found = ScreenTargets.decide(view, reach.target, reach.kind, reach.strictness, reach.cells)) {
                        is Resolution.One -> element(found.element.key, reach.gesture)
                        is Resolution.Several -> found.elements.any { element(it.key, reach.gesture) }
                        is Resolution.Cell -> point(found.bounds.centerX, found.bounds.centerY)
                        Resolution.NotFound, Resolution.NeedNumbers, is Resolution.NoSuchNumber, Resolution.Changed,
                        Resolution.NoScreen -> false
                    }
                }
            }
        }

        /** R1: whether the name of element [index] holds "emergency" or "sos"; never Kestrel's own windows, such as its bar. */
        fun emergencyNamed(snapshot: ScreenSnapshot, index: Int): Boolean {
            val element = snapshot.elements[index]
            if (element.packageName == KESTREL) return false
            // A view ID such as "emergencyButton" or "sos_call" splits into words.
            val id = element.viewId?.substringAfter(":id/")?.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            val names = listOfNotNull(element.text, element.contentDescription, id, element.label.takeIf { element.isActionable })
            return names.any { name -> Normalizer.words(name).any { it in EMERGENCY_WORDS } }
        }

        /**
         * R2: the IDs of the windows of the phone packages, among [phoneApps], that show an element or a title that R1
         * names (an emergency dialer, also one whose number field Kestrel cannot read) or a number field with a number that Kestrel
         * would not call itself; every window of such a package counts, so that its menus and dialogs count too.
         */
        fun guardedWindows(snapshot: ScreenSnapshot, phoneApps: Set<String>, emergency: (String) -> Boolean?): Set<Int> {
            val fields = snapshot.elements.filter { it.packageName in phoneApps && showsNumber(it) }.filter { field ->
                val shown = PhoneNumbers.dialable(field.text!!) ?: return@filter true
                !AndroidDialer.mayCall(shown, emergency(PhoneNumbers.mainNumber(shown)))
            }
            val named = snapshot.elements.indices.filter { snapshot.elements[it].packageName in phoneApps && emergencyNamed(snapshot, it) }
                .map { snapshot.elements[it] }
            val packages = (fields + named).mapNotNullTo(HashSet()) { it.packageName }
            // A window's title, such as an emergency dialer's "Emergency call", names it too.
            snapshot.windows
                .filter { window -> window.packageName in phoneApps && Normalizer.words(window.title.orEmpty()).any { it in EMERGENCY_WORDS } }
                .mapNotNullTo(packages) { it.packageName }
            return snapshot.elements.filter { it.packageName in packages }.mapTo(HashSet()) { it.windowId } +
                snapshot.windows.filter { it.packageName in packages }.map { it.id }
        }

        // A number field that shows something: the field with the view ID `digits` with any text, and another text field
        // only with a digit, `*` or `#`, so that a name in the phone app's search box asks nothing.
        private fun showsNumber(element: ScreenElement): Boolean {
            val text = element.text
            if (text.isNullOrBlank() || !AndroidDialer.isNumberField(element)) return false
            return element.viewId?.endsWith(":id/digits") == true || text.any { it.isDigit() || it == '*' || it == '#' }
        }

        /**
         * What [reaches] find in [view]: the keys and windows of the elements, a grid square, or the point itself, so that
         * the yes counts only while the command still finds the same.
         */
        fun resolved(reaches: List<Reach>, view: ScreenView): List<Any> = reaches.map { reach ->
            when (reach) {
                is Reach.Element -> when (val found = ScreenTargets.decide(view, reach.target, reach.kind, reach.strictness, reach.cells)) {
                    is Resolution.One -> place(view, found.element.key)
                    is Resolution.Several -> found.elements.map { place(view, it.key) }
                    is Resolution.Cell -> found.bounds
                    Resolution.NotFound, Resolution.NeedNumbers, is Resolution.NoSuchNumber, Resolution.Changed,
                    Resolution.NoScreen -> found
                }
                is Reach.Point, Reach.Anywhere -> reach
            }
        }

        private fun place(view: ScreenView, key: String) = key to view.snapshot.element(key)?.windowId

        // What a tap on element [index] clicks: the element when it takes a touch, or else its nearest clickable parent.
        private fun actor(snapshot: ScreenSnapshot, index: Int): Int =
            if (snapshot.elements[index].isActionable) index else touchParent(snapshot, index) ?: index

        // The nearest parent of element [index] that takes a touch.
        private fun touchParent(snapshot: ScreenSnapshot, index: Int): Int? {
            var parent = snapshot.elements[index].parent
            while (parent != null) {
                val ancestor = snapshot.elements.getOrNull(parent) ?: return null
                if (ancestor.clickable || ancestor.longClickable) return parent
                parent = ancestor.parent
            }
            return null
        }

        // R2's exceptions, so that the owner can type and correct the number by voice: the number field, the digit keys
        // (by their view IDs, or a whole name that is one digit, "*", "#" or "+", as a dial pad's keys have, never
        // "1 Telenor") and the delete key.
        private fun typesTheNumber(snapshot: ScreenSnapshot, index: Int): Boolean {
            val element = snapshot.elements[index]
            if (AndroidDialer.isNumberField(element)) return true
            val id = element.viewId?.substringAfter(":id/")?.lowercase()
            if (id in KEY_IDS || id in DELETE_IDS) return true
            if (!element.clickable && !element.longClickable) return false
            val names = listOfNotNull(element.text, element.contentDescription)
            return names.any { it.trim() in KEY_NAMES || Normalizer.normalize(it) in DELETE_NAMES }
        }

        private fun IntRect.holds(x: Int, y: Int) = x in left until right && y in top until bottom

        /** Where [command] acts on a screen of [screen] bounds; empty for a command that acts on no element or point. */
        fun reaches(command: Command, screen: IntRect): List<Reach> {
            val centre = listOf(Reach.Point(screen.centerX, screen.centerY))
            fun tap(target: Target) = listOf(Reach.Element(target, TargetKind.TAP, Strictness.NORMAL, cells = true))
            fun gesture(target: Target) = listOf(Reach.Element(target, TargetKind.TAP, Strictness.NORMAL, cells = true, gesture = true))
            fun element(target: Target?, kind: TargetKind) =
                target?.let { listOf(Reach.Element(it, kind, Strictness.NORMAL, cells = false)) }.orEmpty()
            return when (command) {
                is Command.Tap -> tap(command.target)
                is Command.LongPress -> tap(command.target)
                is Command.Bare -> listOf(
                    command.number?.let { Reach.Element(Target.Number(it), TargetKind.BARE, Strictness.NORMAL, cells = true) }
                        ?: Reach.Element(Target.Label(command.text), TargetKind.BARE, Strictness.STRICT, cells = false),
                )
                // Without a target, these act on what the screen part's search finds without one, which this search finds too.
                is Command.Check -> listOf(Reach.Element(command.target, TargetKind.CHECK, Strictness.NORMAL, cells = false))
                is Command.Switch -> listOf(Reach.Element(command.target, TargetKind.CHECK, Strictness.NORMAL, cells = false))
                is Command.Expand -> listOf(Reach.Element(command.target, TargetKind.EXPAND, Strictness.NORMAL, cells = false))
                is Command.Collapse -> listOf(Reach.Element(command.target, TargetKind.COLLAPSE, Strictness.NORMAL, cells = false))
                is Command.Slider -> listOf(Reach.Element(command.target, TargetKind.SLIDER, Strictness.NORMAL, cells = false))
                is Command.Dismiss -> element(command.target, TargetKind.DISMISS)
                is Command.Scroll -> command.target?.let { element(it, TargetKind.SCROLL) } ?: centre
                is Command.ScrollToEnd -> command.target?.let { element(it, TargetKind.SCROLL) } ?: centre
                is Command.SwipePage -> centre
                is Command.SwipeFromEdge -> GesturePaths.fromEdge(screen, screenEdge(command.edge)).let {
                    listOf(Reach.Point(it.fromX, it.fromY), Reach.Point(it.toX, it.toY))
                }
                is Command.Swipe -> command.target?.let { gesture(it) }
                    ?: GesturePaths.limit(screen, screen.centerX, screen.centerY, scrollDirection(command.direction))
                        ?.let { listOf(Reach.Point(it.fromX, it.fromY), Reach.Point(it.toX, it.toY)) }.orEmpty()
                is Command.Pinch -> command.target?.let { gesture(it) } ?: centre
                // Both ends; an end that the owner gives later can lie anywhere.
                is Command.Drag -> gesture(command.from) + (command.to?.let { gesture(it) } ?: listOf(Reach.Anywhere))
                // They only say what an element is or allows.
                is Command.WhatIs, is Command.CommandsFor -> emptyList()
                Command.ShowNumbers, Command.HideNumbers, Command.ShowLabels, Command.HideLabels, Command.ShowGrid,
                Command.HideGrid, Command.MoreSquares, Command.FewerSquares, Command.Again, is Command.Help -> emptyList()
                is TextCommand -> textReaches(command)
                is PhoneCommand -> phoneReaches(command)
            }
        }

        // The text part runs first and takes these in a focused field (plan 3B); only "start editing" names an element.
        private fun textReaches(command: TextCommand): List<Reach> = when (command) {
            is TextCommand.StartEditing ->
                command.target?.let { listOf(Reach.Element(it, TargetKind.EDIT, Strictness.NORMAL, cells = false)) }.orEmpty()
            TextCommand.StopEditing, is TextCommand.Type, TextCommand.Undo, TextCommand.Redo, is TextCommand.Insert,
            is TextCommand.Replace, is TextCommand.ReplaceBetween, is TextCommand.ChangeCase, TextCommand.FormatEmail,
            is TextCommand.Delete, is TextCommand.MoveCursor, is TextCommand.Select, TextCommand.Unselect,
            is TextCommand.Clipboard, is TextCommand.Keyboard, TextCommand.NewLine, TextCommand.ShowPassword,
            is TextCommand.Search -> emptyList()
        }

        // The phone commands act through Android's services; a call goes through Kestrel's own rule (CallCommands).
        private fun phoneReaches(command: PhoneCommand): List<Reach> = when (command) {
            is PhoneCommand.Global, is PhoneCommand.OpenApp, PhoneCommand.PreviousApp, PhoneCommand.CircleToSearch,
            is PhoneCommand.Radio, is PhoneCommand.Volume, is PhoneCommand.Mute, PhoneCommand.Silence, is PhoneCommand.Media,
            is PhoneCommand.Magnify, is PhoneCommand.Pan, is PhoneCommand.Call, PhoneCommand.CopyPhoneNumber -> emptyList()
        }

        private fun screenEdge(edge: Edge): ScreenEdge = when (edge) {
            Edge.TOP -> ScreenEdge.TOP
            Edge.BOTTOM -> ScreenEdge.BOTTOM
            Edge.LEFT -> ScreenEdge.LEFT
            Edge.RIGHT -> ScreenEdge.RIGHT
        }

        private fun scrollDirection(direction: Direction): ScrollDirection = when (direction) {
            Direction.UP -> ScrollDirection.UP
            Direction.DOWN -> ScrollDirection.DOWN
            Direction.LEFT -> ScrollDirection.LEFT
            Direction.RIGHT -> ScrollDirection.RIGHT
        }
    }
}

/**
 * "Copy this phone number" (design spec, Appendix A): the phone numbers in the texts of the app windows, never in the
 * status bar or the notifications. One number is copied at once; for several, Kestrel shows "Which one?" numbers on the
 * elements that hold them and asks. A background app cannot read the clipboard, so Kestrel only writes it.
 */
internal class NumberCopier(private val environment: CommandEnvironment) {
    private val screenCommands = ScreenCommands(environment)

    suspend fun copy(screen: ScreenSession, conversation: CommandConversation): CommandResult {
        val found = numbersOn(screen.snapshots.current().snapshot)
        val number = when (found.size) {
            0 -> return CommandResult(Outcome.FAILED, say = PhoneCommandTexts.NO_PHONE_NUMBER)
            1 -> found[0].second
            else -> when (val choice = screenCommands.choose(screen, found.map { ScreenTargets.chosen(it.first) }, conversation)) {
                is Choice.Chosen -> found.first { it.first.key == choice.element.key }.second
                is Choice.Ends -> return choice.result
            }
        }
        val clipboard = environment.context.getSystemService(ClipboardManager::class.java)
            ?: return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.COULD_NOT_COPY)
        clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, number))
        return CommandResult(Outcome.DONE)
    }

    companion object {
        private const val CLIP_LABEL = "Phone number"

        /** Each element of an app window whose text or description shows a phone number, with its first one; each number once. */
        fun numbersOn(snapshot: ScreenSnapshot): List<Pair<ScreenElement, String>> {
            val apps = snapshot.windows.filter { it.kind == WindowKind.APPLICATION }.map { it.id }.toSet()
            return snapshot.elements.filter { it.windowId in apps && !it.password && !it.editable }.mapNotNull { element ->
                listOfNotNull(element.text, element.contentDescription).firstNotNullOfOrNull { text ->
                    PhoneNumbers.find(text).firstOrNull()?.let { text.substring(it) }
                }?.let { element to it }
            }.distinctBy { (_, number) -> number.filter(Char::isDigit) }
        }
    }
}
```

In `app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommandTexts.kt`, replace

```kotlin
package dev.kestrel.app.commands

/**
```

with

```kotlin
package dev.kestrel.app.commands

import dev.kestrel.commands.phone.ContactMatcher

/**
```

Then replace

```kotlin
    // Tasks 2 to 6 of plan 3C add their texts above this line.
```

with

```kotlin
    const val NEED_CONTACTS = "I need access to your contacts to call by name. " +
        "Allow it on the screen that I opened, then say it again."

    /** [name] is the owner's words or a contact's name, which Kestrel says and never logs. */
    fun cantFindContact(name: String) = "I can't find $name in your contacts."

    fun tooManyContacts(count: Int, name: String) = "There are $count contacts like $name. Say the full name."

    fun noNumber(name: String) = "$name has no phone number."

    fun noNumberOfKind(name: String, kind: String) = "$name has no $kind number."

    /** "Which number? Mobile or work?", or with numbers when two numbers have the same kind: "Which number? 1, mobile. 2, mobile." */
    fun whichNumber(kinds: List<String>): String {
        if (kinds.map(ContactMatcher::kindOf).distinct().size < kinds.size) {
            return "Which number? " + kinds.withIndex().joinToString(" ") { "${it.index + 1}, ${it.value}." }
        }
        val listed = if (kinds.size == 1) kinds[0] else kinds.dropLast(1).joinToString(", ") + " or " + kinds.last()
        return "Which number? ${listed.replaceFirstChar { it.uppercase() }}?"
    }

    /** A number as Kestrel says it: digit by digit, with "plus", "star" and "hash". */
    fun spoken(digits: String) = digits.map {
        when (it) {
            '+' -> "plus"
            '*' -> "star"
            '#' -> "hash"
            else -> it.toString()
        }
    }.joinToString(" ")

    /** Whom Kestrel calls: "Ana Petrović, mobile", or a spoken number. */
    fun contactAndKind(name: String, kind: String) = "$name, $kind"

    /** The announcement: "Calling Ana Petrović, mobile." */
    fun calling(whom: String) = "Calling $whom."

    /** The countdown on the bar: "Calling Ana Petrović, mobile, in 4". */
    fun callingIn(whom: String, seconds: Int) = "Calling $whom, in $seconds"

    /**
     * A short or code number, one that is no plain number, or an emergency number under [EmergencyPolicy.TOUCH_ONLY]:
     * Kestrel never presses Call for it (design D11).
     */
    fun tapCallYourself(whom: String) = "I opened the phone app with $whom. Tap Call yourself."

    const val OPENED_TAP_CALL = "I opened the phone app. Tap Call yourself."
    const val NO_PHONE_APP = "I couldn't open the phone app."
    const val COULD_NOT_PRESS = "I couldn't press Call. Tap Call yourself."
    const val OTHER_NUMBER = "The phone app shows another number, so I didn't press Call."
    const val EMERGENCY_QUESTION = "That may call an emergency number. Say 'yes, call' to do it."
    const val EMERGENCY_TOUCH_ONLY = "Tap it yourself for an emergency call."
    const val CHOOSE_SIM = "Choose the SIM."
    const val NO_PHONE_NUMBER = "I can't find a phone number on the screen."
    const val COULD_NOT_COPY = "I couldn't copy the number."
```

In `app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommands.kt`, replace

```kotlin
    // Tasks 2 to 6 of plan 3C add their parts above this line.
```

with

```kotlin
    private val calls = CallCommands.create(environment)
    private val numbers = NumberCopier(environment)
    private val emergency = EmergencyGuard(environment.context, environment.targets, environment.screen)
```

Then replace

```kotlin
        val screen = environment.screen() ?: return null
        return when (command) {
```

with

```kotlin
        val screen = environment.screen() ?: return null
        // Whatever may call an emergency number needs the owner's "yes, call" first (design spec, section 8.7).
        emergency.check(command, heard, conversation)?.let { return it }
        return when (command) {
```

Then replace

```kotlin
            // Tasks 2 to 6 of plan 3C add their commands above this line.
```

with

```kotlin
            is PhoneCommand.Call -> calls.call(command.who, conversation)
            PhoneCommand.CopyPhoneNumber -> numbers.copy(screen, conversation)
```

- [ ] **Step 7: Run the JVM tests of the calls to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.commands.PhoneCallsTest`
Expected: `BUILD SUCCESSFUL`, with 18 tests run.

- [ ] **Step 8: Change the spec's lines of calls**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace

```markdown
The labels are English; the phone app's other languages come with sub-project 3's matching.
```

with

```markdown
The labels are English; the phone app's other languages come with Serbian support (section 21).
```

Then replace

```markdown
- Kestrel never requests `SEND_SMS`, `CALL_PHONE`, `CAMERA`, `ACCESS_BACKGROUND_LOCATION`, `SYSTEM_ALERT_WINDOW`, `REQUEST_INSTALL_PACKAGES`, `QUERY_ALL_PACKAGES`, `MANAGE_EXTERNAL_STORAGE`, `WRITE_SETTINGS` or `WRITE_SECURE_SETTINGS`.
```

with

```markdown
- Kestrel never requests `SEND_SMS`, `CALL_PHONE`, `CAMERA`, `ACCESS_BACKGROUND_LOCATION`, `SYSTEM_ALERT_WINDOW`, `REQUEST_INSTALL_PACKAGES`, `QUERY_ALL_PACKAGES`, `MANAGE_EXTERNAL_STORAGE`, `WRITE_SETTINGS` or `WRITE_SECURE_SETTINGS`. So "call [number]" and "call [name]" open the default phone app with the number: Kestrel says whom it calls, counts down 4 s from the end of that announcement on the feedback bar, and then presses the phone app's call button through its accessibility service. "Cancel", "stop", "no" or a touch stops the countdown and leaves the number in the phone app. For a number with fewer than 5 digits or one with `*` or `#`, Kestrel never presses Call: it opens the phone app with the number and asks the owner to tap Call. For an emergency number it asks for "yes, call" after the announcement instead of counting down, or, under the other policy, only opens the phone app (section 8.7). A contact's number keeps its pause, wait or extension for the phone app. Kestrel matches contacts on the phone, itself; it asks for `READ_CONTACTS` at the first "call [name]", through its settings app.
```

Then replace

```markdown
| Calls | Call [number]; Call [name] | Yes. When several contacts match, Kestrel asks "Which one?". |
| Calls | Copy this phone number | Yes |
```

with

```markdown
| Calls | Call [number]; Call [name] | Yes, through the default phone app's call button, after an announcement and a 4 s countdown that "cancel" or a touch stops (section 13.6). When several contacts match, Kestrel asks "Which one?", and for a contact with several numbers "Which number?". A short or `*` and `#` number only opens the phone app, and an emergency number needs "yes, call" (section 8.7). |
| Calls | Copy this phone number | Yes, from the texts of the app windows. When the screen shows several numbers, Kestrel asks "Which one?". |
```

Then replace

```markdown
- After the call, the previous listening mode returns. If the call interrupted a task, the task is cancelled; sub-project 5 asks "Continue the task?" (section 14.4).
```

with

```markdown
- After the call, the previous listening mode returns. If the call interrupted a task, the task is cancelled; sub-project 5 asks "Continue the task?" (section 14.4).
- Emergency calls (plan 3C). Kestrel never acts by voice on what may call an emergency number without the owner's "yes, call". R1: an element whose name (text, content description or view ID, and the label of an element that takes a touch) holds the word "emergency" or "SOS", or whose nearest clickable parent's does, such as the emergency buttons of the power menu and the lock screen. R2: every window of a phone package (the default phone app, `com.android.phone`, `com.android.dialer`, `com.google.android.dialer`, `com.samsung.android.dialer`, `com.samsung.android.incallui`, `com.android.server.telecom` or One UI's emergency dialer `com.samsung.android.app.telephonyui`) while one of its windows shows an element or a title that R1 names, or a number field that shows an emergency number (also when Android cannot check it), a number with fewer than 5 digits or with `*` or `#`, or a text that reads as no number: every action on their elements except the digit keys, the number field and its delete key, so that the owner can still type and correct the number by voice, and every gesture that starts or ends inside them. Kestrel asks "That may call an emergency number. Say 'yes, call' to do it." and acts only on "yes, call", with or without its name after it, and only while the command still finds the same elements; any other answer, the question's timer, a touch or a cancel ends the command. "Call [number]" with an emergency number asks the same question after its announcement and never presses Call through the countdown alone (section 13.6). This policy is the owner's decision; one constant switches it to touch only, where Kestrel refuses with "Tap it yourself for an emergency call." and "call [number]" only opens the phone app. Not covered: the phone app's controls that call a stored number while the number field is empty (the call icon of a call-history row, a favourite, a contact's page, a speed-dial key), phones in other languages than English for R1, whose words are English, letters alone in a phone app's text field other than the one with the view ID `digits`, and an app's own emergency feature outside the phone packages whose names do not say "emergency" or "SOS".
```

- [ ] **Step 9: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin :testapp:assembleDebug`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:commands` as in Step 5, `:app` 354 with `PhoneCallsTest` 18, the others as before), lint without issues in every Android module, the audit, and the device tests and the test app compile.

- [ ] **Step 10: Run the call device tests**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\command-test.ps1 -Serial emulator-5554 -Class CallCommandsTest`
Expected: `BUILD SUCCESSFUL` and "Command tests: 8 run, 0 failed, 0 errors, 0 skipped.", with no warning from the restores at the end, which delete the test contacts and the new call-log rows and revoke `READ_CONTACTS` and `READ_PHONE_STATE` if they were not granted before. If `aNumberIsAnnouncedCountedDownAndCalled` fails because Kestrel finds no call button in the emulator's phone app, or `copyThisPhoneNumberCopiesTheOnlyNumberOrTheOneThatTheOwnerChooses` because the status line never shows "Pasted" and the number, or `anEmergencyNumberOrAnUnreadableOneInThePhoneAppNeedsTheOwnersYesForAllButItsKeys` because a bare "call" is typed into the phone app's number field (plan 3B's phone-field rule did not see it as a phone field), the field does not keep "abc" after the set-text, or the phone app has no keys with the view IDs `five` and `deleteButton`, report it to the controller with the failure message, and do not change the tests.

- [ ] **Step 11: Run all command device tests**

"Call X", "dial X" and "copy the number" were bare texts before; this run checks that no test of plans 3A and 3B used one of them.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\command-test.ps1 -Serial emulator-5554`
Expected: `BUILD SUCCESSFUL` and "Command tests: 88 run, 0 failed, 0 errors, 0 skipped." (`CallCommandsTest` 8), with no warning from the restores.

- [ ] **Step 12: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 13: Commit**

```powershell
git add core/commands/src/main/kotlin/dev/kestrel/commands/GrammarPhone.kt core/commands/src/main/kotlin/dev/kestrel/commands/HelpPhone.kt core/commands/src/test/kotlin/dev/kestrel/commands/GrammarPhoneTest.kt app/src/main/kotlin/dev/kestrel/app/commands/CallCommands.kt app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommands.kt app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommandTexts.kt app/src/test/kotlin/dev/kestrel/app/commands/PhoneCallsTest.kt app/src/androidTest/kotlin/dev/kestrel/app/commands/CallCommandsTest.kt testapp/src/main/kotlin/dev/kestrel/testapp/PhoneNumberActivity.kt testapp/src/main/res/values/ids_phone.xml testapp/src/main/AndroidManifest.xml docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(commands): calls by number and name with a countdown, and copy this phone number" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 7: The corpus's instant commands and the command accuracy

**Files:**
- Modify: `corpus/commands.txt` (after plan 2D: the comment at its top, and new lines at its end)
- Create: `corpus/scenes.txt`
- Move: `app/src/main/kotlin/dev/kestrel/app/listen/WordErrors.kt` to `core/commands/src/main/kotlin/dev/kestrel/commands/text/WordErrors.kt`, and `app/src/test/kotlin/dev/kestrel/app/listen/WordErrorsTest.kt` to `core/commands/src/test/kotlin/dev/kestrel/commands/text/WordErrorsTest.kt`
- Create: `core/commands/src/main/kotlin/dev/kestrel/commands/SameAction.kt`
- Modify: `app/src/debug/kotlin/dev/kestrel/app/debug/EvalHooks.kt` (after plan 2D), `scripts/hook.ps1` (after plan 3A), `scripts/lib/Corpus.ps1`, `scripts/tests/Corpus.Tests.ps1` and `scripts/voice-eval.ps1` (after plan 2D)
- Modify: `README.md` (the sentence of the corpus's size, `about 110 English utterances`)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (8.3's bullet line that plan 3A added, and 16.4 from `### 16.4` to the line before `### 16.5`)
- Test: `core/commands/src/test/kotlin/dev/kestrel/commands/SameActionTest.kt`, `core/commands/src/test/kotlin/dev/kestrel/commands/CorpusTest.kt` (both new)

**Interfaces:**
- Consumes: Tasks 1 to 6's phrases, Task 5's `ContactMatcher`, `Contact` and `ContactMatch`; plan 3B's `GrammarText`, `Dictation.insert(state, spoken, password)`, `TextState`, `DictationPart.Text(change)` and `DictationPart.NewLine`, `EditStep.Change(start, end, replacement, selectionStart, selectionEnd)`; plan 3A's `CommandParser`, `LabelMatcher.decide`, `MatchCandidate`, `MatchDecision`, `Strictness`, `Normalizer`, `CommandVocabulary.BOOST`, `BoostWordBudget.combine(fixed, screen, vocabulary)` and `screenNames(snapshot)`, `AppGraph.screen`; plan 2D's `WordErrors.count(expected, heard)`, the hook `VOICE_EVAL` (`EvalHooks.compare`), `Read-Corpus`, `New-EvalTotals`, `Add-EvalResult`, `Format-EvalSummary`, `Format-Percent`, `ConvertFrom-HookAnswer`, `scripts\make-corpus.ps1`, `scripts\voice-eval.ps1` and `scripts\voice-test.ps1`.
- Produces: `data class WordErrors` in `dev.kestrel.commands.text`, public; `object Commands` (`dev.kestrel.commands`) with `fun sameAction(expected: Command, heard: Command, labels: List<String>): Boolean` and `fun sameDictation(expected: String, heard: String): Boolean`; the corpus groups `conversation` and `dictation`; `corpus/scenes.txt`; `Read-CorpusScenes -Path` (a table from a line's id to its scene's labels), `Format-GroupErrors -Name -Totals`, and the totals' `Commands` and `RightCommands`; the hook's extras `group`, `labels`, `noLabels` and `screenLabels` (`hook.ps1 voice-eval -Group -Labels -NoLabels -ScreenLabels`) and its answer's ending ` command=1`, ` command=0` or ` command=-`; `voice-eval.ps1 -NoLabels`.

Design D13 and D15, and decision 10. The corpus gets 204 lines: 179 instant commands in the group `appendix` (every "Yes" row of Appendix A in at least two phrasings, D8's text phrases as plan 3B pins them, and bare labels and numbers), 20 lines of `conversation` that must stay bare speech and act on nothing, and D8's five lines of `dictation`; 60 of them are marked for the owner, so that the A71 session asks for 160 recordings in all. `corpus/scenes.txt` gives 21 lines the labels that the screen would show, among them labels near the right one; the call lines have no scene, because Kestrel's voice session never has contact names as boost words. The evaluation hook gives every line of the corpus the command vocabulary, and a line with a scene its labels, as the screen's boost words, as Kestrel's voice session does, and for a line of the three groups it answers whether the transcript leads to the same action as the line's text: `Commands.sameAction` takes the same variant with the same fields, where a label or an app's name counts as the same when the matcher finds the same label of the scene for both, a contact's name when Task 5's contact matcher finds the same name of the scene for both, or else when its folded words are the same (so that "Petrovic" is "Petrović", as Kestrel would take it), a typed text or another payload when its folded words have no word errors, and a bare label when both find the same label with the strict limits of bare labels. A `conversation` line counts as right when the transcript stays bare speech, and a `dictation` line when dictating the transcript into an empty field types the same words and the same punctuation marks as dictating the text. `voice-eval.ps1` prints the share of these lines that lead to the correct action, the ids of the lines that did not (also for the owner's recordings, whose transcripts never leave the phone), and the word error rates of the dictation and the task lines, with the scenes' labels or, with `-NoLabels`, without them and the vocabulary; spec 8.3 records the difference. `WordErrors` moves to `:core:commands` so that `Commands` can use it; the hook imports it from there. `CorpusTest` checks the corpus against the grammars: each `appendix` line is a command, except four bare labels and numbers ("Cancel.", "Seven.", "Save." and "Send later."), and no `conversation` or `dictation` line is a command; it reads `corpus/commands.txt` from the repository (`../../corpus` from the module), which Gradle does not track as an input, so after a change of only the corpus, `scripts\gradle.ps1 :core:commands:test --rerun` runs it again. Spec 5.3's target is at least 95% of the command lines for the generated corpus in both voices; the owner's recordings are measured after the A71 session (Task 8).

- [ ] **Step 1: Write the corpus's new lines and its scenes**

In `corpus/commands.txt`, replace

```text
#   id        a plain name, which is also the name of the utterance's clips
#   group     appendix (Appendix A's commands, which later sub-projects handle), own (Kestrel's own phrases), answer
#             (answers to "Yes?"), task (tasks with Kestrel's name), confusable (phrases near Kestrel's words) or call
#             (the call commands)
#   context   idle, or answer for an answer to "Yes?"
```

with

```text
#   id        a plain name, which is also the name of the utterance's clips
#   group     appendix (Appendix A's instant commands, and bare labels and numbers), own (Kestrel's own phrases),
#             answer (answers to "Yes?"), task (tasks with Kestrel's name), confusable (phrases near Kestrel's words),
#             call (the call commands), conversation (speech that is no command, which Kestrel must ignore) or
#             dictation (dictated text with spoken punctuation, typed into a field)
#   context   idle, or answer for an answer to "Yes?"
```

Then replace

```text
# transcript always reads as expected. For example, "Forget about it." in an answer is a task, because only "never
# mind", "forget it" and "nothing" end the wait.
c001 | appendix | idle | 0 | Other | 1 | Open Chrome.
```

with

```text
# transcript always reads as expected. For example, "Forget about it." in an answer is a task, because only "never
# mind", "forget it" and "nothing" end the wait. For the groups appendix, conversation and dictation, the evaluation also
# checks the instant command (design spec, section 16.4): the transcript must lead to the action that the text leads to,
# with the labels that corpus\scenes.txt gives the line; a conversation line must stay bare speech, and a dictation line
# must type the same words and punctuation as its text.
c001 | appendix | idle | 0 | Other | 1 | Open Chrome.
```

Then replace

```text
c111 | call | idle | 0 | - | 1 | Speaker.
```

with

```text
c111 | call | idle | 0 | - | 1 | Speaker.
c112 | appendix | idle | 0 | Other | 1 | Open Gmail.
c113 | appendix | idle | 0 | Other | 0 | Launch Chrome.
c114 | appendix | idle | 0 | Other | 1 | Open work Gmail.
c115 | appendix | idle | 0 | Other | 0 | Open Chrome in the work profile.
c116 | appendix | idle | 0 | Other | 0 | Show all apps.
c117 | appendix | idle | 0 | Other | 0 | Open the app drawer.
c118 | appendix | idle | 0 | Other | 1 | Back.
c119 | appendix | idle | 0 | Other | 1 | Home.
c120 | appendix | idle | 0 | Other | 0 | Open notifications.
c121 | appendix | idle | 0 | Other | 0 | Open Quick Settings.
c122 | appendix | idle | 0 | Other | 0 | Recent apps.
c123 | appendix | idle | 0 | Other | 1 | Previous app.
c124 | appendix | idle | 0 | Other | 0 | Lock the phone.
c125 | appendix | idle | 0 | Other | 1 | Turn off device.
c126 | appendix | idle | 0 | Other | 0 | Show the power menu.
c127 | appendix | idle | 0 | Other | 0 | Screenshot.
c128 | appendix | idle | 0 | Other | 0 | Repeat that.
c129 | appendix | idle | 0 | Stop | 0 | Cancel.
c130 | appendix | idle | 0 | Other | 0 | Circle to search.
c131 | appendix | idle | 0 | Other | 0 | Start Circle to Search.
c132 | appendix | idle | 0 | Other | 1 | Media play.
c133 | appendix | idle | 0 | Other | 0 | Play media.
c134 | appendix | idle | 0 | Other | 0 | Pause the music.
c135 | appendix | idle | 0 | Other | 0 | Show all commands.
c136 | appendix | idle | 0 | Other | 0 | Open tutorial.
c137 | appendix | idle | 0 | Other | 0 | What is 5?
c138 | appendix | idle | 0 | Other | 0 | What's number 3?
c139 | appendix | idle | 0 | Other | 0 | Show commands for Send.
c140 | appendix | idle | 0 | Other | 0 | Show commands for 4.
c141 | appendix | idle | 0 | Other | 0 | Hide labels.
c142 | appendix | idle | 0 | Other | 1 | Turn on Wi-Fi.
c143 | appendix | idle | 0 | Other | 1 | Turn Wi-Fi off.
c144 | appendix | idle | 0 | Other | 0 | Turn on Bluetooth.
c145 | appendix | idle | 0 | Other | 0 | Switch Bluetooth off.
c146 | appendix | idle | 0 | Other | 0 | Volume down.
c147 | appendix | idle | 0 | Other | 1 | Turn up the media volume.
c148 | appendix | idle | 0 | Other | 1 | Turn the alarm volume down.
c149 | appendix | idle | 0 | Other | 0 | Increase the ringer volume.
c150 | appendix | idle | 0 | Other | 0 | Phone volume up.
c151 | appendix | idle | 0 | Other | 1 | Mute.
c152 | appendix | idle | 0 | Other | 0 | Unmute.
c153 | appendix | idle | 0 | Other | 0 | Mute the ringer.
c154 | appendix | idle | 0 | Other | 1 | Silence.
c155 | appendix | idle | 0 | Other | 0 | Vibrate.
c156 | appendix | idle | 0 | Other | 0 | Seven.
c157 | appendix | idle | 0 | Other | 1 | Save.
c158 | appendix | idle | 0 | Other | 0 | Tap Save.
c159 | appendix | idle | 0 | Other | 0 | Send later.
c160 | appendix | idle | 0 | Other | 1 | Show password.
c161 | appendix | idle | 0 | Other | 0 | Show the password.
c162 | appendix | idle | 0 | Other | 0 | Search for weather tomorrow.
c163 | appendix | idle | 0 | Other | 1 | Call 064 123 4567.
c164 | appendix | idle | 0 | Other | 0 | Dial zero six four one two three four five six seven.
c165 | appendix | idle | 0 | Other | 1 | Call Ana Petrović.
c166 | appendix | idle | 0 | Other | 1 | Call Ana mobile.
c167 | appendix | idle | 0 | Other | 0 | Call Ana's work number.
c168 | appendix | idle | 0 | Other | 1 | Copy this phone number.
c169 | appendix | idle | 0 | Other | 0 | Copy the phone number.
c170 | appendix | idle | 0 | Other | 0 | Increment 4.
c171 | appendix | idle | 0 | Other | 0 | Decrease 6.
c172 | appendix | idle | 0 | Other | 0 | Long press Send.
c173 | appendix | idle | 0 | Other | 0 | Drag 3 to 7.
c174 | appendix | idle | 0 | Other | 0 | Drag 5.
c175 | appendix | idle | 0 | Other | 0 | Uncheck.
c176 | appendix | idle | 0 | Other | 1 | Check Subscribe.
c177 | appendix | idle | 0 | Other | 0 | Switch on.
c178 | appendix | idle | 0 | Other | 0 | Switch off Dark theme.
c179 | appendix | idle | 0 | Other | 0 | Expand 2.
c180 | appendix | idle | 0 | Other | 0 | Expand Details.
c181 | appendix | idle | 0 | Other | 0 | Collapse 2.
c182 | appendix | idle | 0 | Other | 0 | Collapse Details.
c183 | appendix | idle | 0 | Other | 0 | Dismiss 3.
c184 | appendix | idle | 0 | Other | 0 | Dismiss the reminder.
c185 | appendix | idle | 0 | Other | 0 | Scroll up.
c186 | appendix | idle | 0 | Other | 0 | Scroll right.
c187 | appendix | idle | 0 | Other | 1 | Scroll down on Messages.
c188 | appendix | idle | 0 | Other | 0 | Scroll to the bottom.
c189 | appendix | idle | 0 | Other | 0 | Swipe backwards.
c190 | appendix | idle | 0 | Other | 1 | Swipe from the left edge.
c191 | appendix | idle | 0 | Other | 0 | Swipe from the bottom.
c192 | appendix | idle | 0 | Other | 0 | More squares.
c193 | appendix | idle | 0 | Other | 0 | Fewer squares.
c194 | appendix | idle | 0 | Other | 0 | Hide grid.
c195 | appendix | idle | 0 | Other | 0 | Swipe 5 up.
c196 | appendix | idle | 0 | Other | 0 | Swipe up on 5.
c197 | appendix | idle | 0 | Other | 1 | Pinch out.
c198 | appendix | idle | 0 | Other | 0 | Pinch in 4.
c199 | appendix | idle | 0 | Other | 0 | Long press 7.
c200 | appendix | idle | 0 | Other | 1 | Start editing the message.
c201 | appendix | idle | 0 | Other | 1 | Stop editing.
c202 | appendix | idle | 0 | Other | 0 | Stop typing.
c203 | appendix | idle | 0 | Other | 1 | Type see you tomorrow.
c204 | appendix | idle | 0 | Other | 0 | Undo that.
c205 | appendix | idle | 0 | Other | 1 | Redo.
c206 | appendix | idle | 0 | Other | 0 | Redo that.
c207 | appendix | idle | 0 | Other | 1 | Insert really before nice.
c208 | appendix | idle | 0 | Other | 0 | Insert very after really.
c209 | appendix | idle | 0 | Other | 0 | Insert and between salt and pepper.
c210 | appendix | idle | 0 | Other | 0 | Insert too before late.
c211 | appendix | idle | 0 | Other | 1 | Format email.
c212 | appendix | idle | 0 | Other | 0 | Format as email address.
c213 | appendix | idle | 0 | Other | 1 | Replace Monday with Tuesday.
c214 | appendix | idle | 0 | Other | 0 | Replace cat with dog.
c215 | appendix | idle | 0 | Other | 0 | Replace everything between hello and world with hi.
c216 | appendix | idle | 0 | Other | 0 | Replace everything between first and last with middle.
c217 | appendix | idle | 0 | Other | 1 | Capitalize paris.
c218 | appendix | idle | 0 | Other | 0 | Capitalise london.
c219 | appendix | idle | 0 | Other | 0 | Uppercase nasa.
c220 | appendix | idle | 0 | Other | 1 | Uppercase hello.
c221 | appendix | idle | 0 | Other | 0 | Lowercase hello.
c222 | appendix | idle | 0 | Other | 1 | Lowercase world.
c223 | appendix | idle | 0 | Other | 1 | Delete.
c224 | appendix | idle | 0 | Other | 0 | Delete that.
c225 | appendix | idle | 0 | Other | 1 | Delete all.
c226 | appendix | idle | 0 | Other | 0 | Delete everything.
c227 | appendix | idle | 0 | Other | 0 | Delete tomorrow.
c228 | appendix | idle | 0 | Other | 1 | Delete the meeting.
c229 | appendix | idle | 0 | Other | 0 | Delete to the beginning.
c230 | appendix | idle | 0 | Other | 0 | Delete to the start.
c231 | appendix | idle | 0 | Other | 1 | Delete to the end.
c232 | appendix | idle | 0 | Other | 0 | Delete to the end of the text.
c233 | appendix | idle | 0 | Other | 1 | Delete selected text.
c234 | appendix | idle | 0 | Other | 0 | Delete the selection.
c235 | appendix | idle | 0 | Other | 0 | Delete from hello to world.
c236 | appendix | idle | 0 | Other | 0 | Delete from first to last.
c237 | appendix | idle | 0 | Other | 1 | Delete three words.
c238 | appendix | idle | 0 | Other | 0 | Delete the next two sentences.
c239 | appendix | idle | 0 | Other | 0 | Delete the last line.
c240 | appendix | idle | 0 | Other | 0 | Delete two paragraphs.
c241 | appendix | idle | 0 | Other | 0 | Delete five characters.
c242 | appendix | idle | 0 | Other | 1 | Go to the beginning.
c243 | appendix | idle | 0 | Other | 0 | Move to the start.
c244 | appendix | idle | 0 | Other | 1 | Go to the end.
c245 | appendix | idle | 0 | Other | 0 | Move to the end.
c246 | appendix | idle | 0 | Other | 0 | Move after tomorrow.
c247 | appendix | idle | 0 | Other | 1 | Move after the meeting.
c248 | appendix | idle | 0 | Other | 0 | Move before tomorrow.
c249 | appendix | idle | 0 | Other | 0 | Move before lunch.
c250 | appendix | idle | 0 | Other | 0 | Move between salt and pepper.
c251 | appendix | idle | 0 | Other | 0 | Move between hello and world.
c252 | appendix | idle | 0 | Other | 1 | Right two words.
c253 | appendix | idle | 0 | Other | 0 | Left three characters.
c254 | appendix | idle | 0 | Other | 0 | Right one line.
c255 | appendix | idle | 0 | Other | 0 | Left one sentence.
c256 | appendix | idle | 0 | Other | 0 | Right two paragraphs.
c257 | appendix | idle | 0 | Other | 1 | Select all.
c258 | appendix | idle | 0 | Other | 1 | Unselect all text.
c259 | appendix | idle | 0 | Other | 0 | Unselect.
c260 | appendix | idle | 0 | Other | 0 | Select to the beginning.
c261 | appendix | idle | 0 | Other | 0 | Select to the start.
c262 | appendix | idle | 0 | Other | 0 | Select to the end.
c263 | appendix | idle | 0 | Other | 0 | Select to the end of the text.
c264 | appendix | idle | 0 | Other | 0 | Select tomorrow.
c265 | appendix | idle | 0 | Other | 1 | Select the meeting.
c266 | appendix | idle | 0 | Other | 0 | Highlight meeting.
c267 | appendix | idle | 0 | Other | 0 | Highlight tomorrow.
c268 | appendix | idle | 0 | Other | 0 | Select from hello to world.
c269 | appendix | idle | 0 | Other | 0 | Select from first to last.
c270 | appendix | idle | 0 | Other | 1 | Select three words.
c271 | appendix | idle | 0 | Other | 0 | Select the next two sentences.
c272 | appendix | idle | 0 | Other | 0 | Copy that.
c273 | appendix | idle | 0 | Other | 1 | Cut.
c274 | appendix | idle | 0 | Other | 0 | Cut that.
c275 | appendix | idle | 0 | Other | 0 | Paste that.
c276 | appendix | idle | 0 | Other | 1 | Show keyboard.
c277 | appendix | idle | 0 | Other | 0 | Show the keyboard.
c278 | appendix | idle | 0 | Other | 0 | Hide the keyboard.
c279 | appendix | idle | 0 | Other | 1 | New line.
c280 | appendix | idle | 0 | Other | 0 | New line please.
c281 | appendix | idle | 0 | Other | 1 | Start magnification.
c282 | appendix | idle | 0 | Other | 0 | Magnify.
c283 | appendix | idle | 0 | Other | 0 | Enhance.
c284 | appendix | idle | 0 | Other | 1 | Zoom out.
c285 | appendix | idle | 0 | Other | 1 | Pan left.
c286 | appendix | idle | 0 | Other | 0 | Move up.
c287 | appendix | idle | 0 | Other | 1 | Stop magnification.
c288 | appendix | idle | 0 | Other | 0 | Cancel zooming.
c289 | appendix | idle | 0 | Other | 0 | Start zooming.
c290 | appendix | idle | 0 | Other | 0 | Stop zooming.
c291 | conversation | idle | 0 | Other | 1 | I'll be there in ten minutes.
c292 | conversation | idle | 0 | Other | 0 | That sounds like a great idea.
c293 | conversation | idle | 0 | Other | 0 | Can you believe it rained all day?
c294 | conversation | idle | 0 | Other | 1 | We should go back to that restaurant sometime.
c295 | conversation | idle | 0 | Other | 0 | My phone was on silent all morning.
c296 | conversation | idle | 0 | Other | 0 | The music in this place is too loud.
c297 | conversation | idle | 0 | Other | 0 | Tell her I said hello.
c298 | conversation | idle | 0 | Other | 0 | Let's meet at the station at six.
c299 | conversation | idle | 0 | Other | 1 | I can't find my keys anywhere.
c300 | conversation | idle | 0 | Other | 0 | Did you see the game last night?
c301 | conversation | idle | 0 | Other | 0 | He always forgets to call his mother.
c302 | conversation | idle | 0 | Other | 0 | The battery is almost empty.
c303 | conversation | idle | 0 | Other | 0 | Turn left at the next corner.
c304 | conversation | idle | 0 | Other | 0 | Please don't forget the milk.
c305 | conversation | idle | 0 | Other | 0 | Thanks, that was really helpful.
c306 | conversation | idle | 0 | Other | 0 | How was your trip to Belgrade?
c307 | conversation | idle | 0 | Other | 1 | The volume of work this week is crazy.
c308 | conversation | idle | 0 | Other | 0 | She sent me the photos yesterday.
c309 | conversation | idle | 0 | Other | 0 | Maybe we can watch a movie tonight.
c310 | conversation | idle | 0 | Other | 0 | Okay, see you tomorrow then.
c311 | dictation | idle | 0 | Other | 1 | hello comma how are you question mark
c312 | dictation | idle | 0 | Other | 0 | see you at five period
c313 | dictation | idle | 0 | Other | 0 | the meeting is on Monday exclamation mark
c314 | dictation | idle | 0 | Other | 1 | capital p one two three
c315 | dictation | idle | 0 | Other | 0 | open quote hello close quote she said
```

Create `corpus/scenes.txt`:

```text
# The screens of corpus\commands.txt's instant commands (design spec, section 16.4): the labels that the screen would show
# while the owner says a line. scripts\voice-eval.ps1 gives the evaluation hook a line's labels, which it uses as the
# screen's boost words, as Kestrel's voice session does, and as the screen for the matcher, which must find the same
# label for what Kestrel heard as for the line's text. A scene lists its labels, the ones that the line names and others
# near them; a line without a scene has no labels. Two kinds of lines:
#   scene <name> | <label>; <label>; ...
#   line <id> | <scene>
# Every label is English or a name, as an app or a phone shows it, and none is private.
scene commands | Save; Safe; Send; Send later; Message from Ana; Delete; Message from Dan; Open dialog; Counter; Ђорђе; Subscribe; Wi‑Fi; Speed slider; Details; Drag me; Drop here
scene settings | Network and internet; Connected devices; Apps; Notifications; Battery; Storage; Sound and vibration; Display; Dark theme; Accessibility; Security and privacy; Location; System; About phone; Search settings
scene chats | Messages; Ana Petrović: See you at 5; Marko; Vesna; Đorđe Jovanović; New chat; Search; Camera; Settings
scene dialog | Cancel; OK; Delete; Don't ask again
scene apps | Chrome; Gmail; Clock; Settings; Camera; Photos; Maps; YouTube; Play Store; Contacts; Phone; Messages; Calendar; Files; Viber; WhatsApp
scene gallery | First photo; Second photo; Third photo; Albums; Search
scene quick | Flashlight; Wi-Fi; Bluetooth; Do not disturb; Airplane mode; Auto-rotate
scene editor | Message; Subject; To; Send; Attach
scene shade | Reminder: call the bank; Mark done; Clear all
line c001 | apps
line c017 | quick
line c020 | commands
line c037 | gallery
line c112 | apps
line c113 | apps
line c114 | apps
line c115 | apps
line c129 | dialog
line c139 | commands
line c157 | commands
line c158 | commands
line c159 | commands
line c172 | commands
line c176 | commands
line c178 | settings
line c180 | commands
line c182 | commands
line c184 | shade
line c187 | chats
line c200 | editor
```

- [ ] **Step 2: Write the failing JVM tests**

Create `core/commands/src/test/kotlin/dev/kestrel/commands/SameActionTest.kt`:

```kotlin
package dev.kestrel.commands

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SameActionTest {
    private val scene = listOf("Save", "Safe", "Send", "Send later", "Message from Ana", "Delete", "Settings")

    private fun same(expected: String, heard: String, labels: List<String> = scene): Boolean =
        Commands.sameAction(checkNotNull(CommandParser.parse(expected)), checkNotNull(CommandParser.parse(heard)), labels)

    @Test
    fun theSameCommandWithItsCaseAndPunctuationIsTheSame() {
        assertTrue(same("Scroll down.", "scroll down"))
        assertTrue(same("Tap number 12.", "Tap number twelve."))
        assertTrue(same("Turn the volume up.", "Turn the volume up"))
        assertFalse(same("Scroll down.", "Scroll up."))
        assertFalse(same("Turn off Bluetooth.", "Turn on Bluetooth."))
        assertFalse(same("Show numbers.", "Show labels."))
    }

    @Test
    fun aLabelIsTheSameWhenTheMatcherFindsTheSameLabel() {
        assertTrue(same("Tap Message from Ana.", "Tap message from Anna."))
        assertTrue(same("Tap Send later.", "tap send later"))
        assertFalse(same("Tap Save.", "Tap Safe."))
        assertFalse(same("Tap Send.", "Tap Send later."))
        // Without a label for the expected words, the words themselves must match.
        assertTrue(same("Long-press the second photo.", "Long press the second photo", emptyList()))
        assertFalse(same("Long-press the second photo.", "Long press the second phone", emptyList()))
    }

    @Test
    fun aBareLabelNeedsTheSameStrictMatchAndANumberTheSameNumber() {
        assertTrue(same("Send later.", "Send later"))
        assertFalse(same("Send later.", "Sent letter"))
        assertTrue(same("Seven.", "7"))
        assertFalse(same("Seven.", "11"))
    }

    @Test
    fun payloadsAndNamesNeedNoWordErrors() {
        assertTrue(Commands.sameAction(TextCommand.Type("hello there"), TextCommand.Type("Hello there."), scene))
        assertFalse(Commands.sameAction(TextCommand.Type("hello there"), TextCommand.Type("hello their"), scene))
        assertTrue(same("Call Mom.", "call mom"))
        assertFalse(same("Call Mom.", "Call Tom."))
        // The recogniser cannot write "ć"; the contact matcher still finds the same contact, as Kestrel would.
        assertTrue(same("Call Ana Petrović.", "Call Anna Petrovich.", listOf("Ana Petrović", "Mom")))
        assertTrue(same("Call Ana Petrović.", "Call Ana Petrovic."))
        assertFalse(same("Call Ana mobile.", "Call Anna.", listOf("Ana Petrović", "Mom")))
        assertTrue(same("Call 064 123 4567.", "call zero six four one two three four five six seven"))
        assertTrue(same("Open settings.", "Open setting.", listOf("Settings", "Clock")))
        assertFalse(same("Open work Gmail.", "Open Gmail."))
    }

    @Test
    fun dictationIsTheSameWhenItTypesTheSameWordsAndMarks() {
        assertTrue(Commands.sameDictation("hello comma how are you question mark", "Hello, comma, how are you? Question mark."))
        assertFalse(Commands.sameDictation("hello comma how are you question mark", "Hello, how are you? Question mark."))
        assertFalse(Commands.sameDictation("see you at five period", "see you at nine period"))
    }
}
```

Create `core/commands/src/test/kotlin/dev/kestrel/commands/CorpusTest.kt`:

```kotlin
package dev.kestrel.commands

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The instant commands of the voice corpus (design spec, section 16.4) parse as the evaluation expects: every line of the
 * group appendix to a command, except the bare labels and numbers, and every conversation and dictation line to bare
 * speech. The tests run in the module's folder.
 */
class CorpusTest {
    private val lines = File("../../corpus/commands.txt").readLines(Charsets.UTF_8)
        .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        .map { line -> line.split('|').map { it.trim() } }

    private fun parsed(group: String) = lines.filter { it[1] == group }.associate { it[0] to CommandParser.parse(it[6]) }

    @Test
    fun everyInstantCommandLineIsACommandAndTheBareLabelsAndNumbersStayBare() {
        val commands = parsed("appendix")
        val bare = commands.filterValues { it == null || it is Command.Bare }.keys - UNCHECKED

        assertEquals(BARE, bare)
        assertEquals(219, commands.size)
    }

    @Test
    fun conversationAndDictationStayBare() {
        val speech = parsed("conversation") + parsed("dictation")

        assertEquals(emptyMap<String, Command?>(), speech.filterValues { it !is Command.Bare })
        assertEquals(25, speech.size)
    }

    private companion object {
        // "Cancel.", "Seven.", "Save." and "Send later." name buttons, or a number, on their own.
        val BARE = setOf("c129", "c156", "c157", "c159")

        // "Delete the last word." is no phrasing that the design fixes for plan 3B, so it is not checked here.
        val UNCHECKED = setOf("c029")
    }
}
```

- [ ] **Step 3: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:commands:test`
Expected: `BUILD FAILED`: the tests do not compile: "Unresolved reference 'Commands'".

- [ ] **Step 4: Move `WordErrors` to `:core:commands`**

Run: `git mv app/src/main/kotlin/dev/kestrel/app/listen/WordErrors.kt core/commands/src/main/kotlin/dev/kestrel/commands/text/WordErrors.kt`
Expected: no output.

Run: `git mv app/src/test/kotlin/dev/kestrel/app/listen/WordErrorsTest.kt core/commands/src/test/kotlin/dev/kestrel/commands/text/WordErrorsTest.kt`
Expected: no output.

In `core/commands/src/main/kotlin/dev/kestrel/commands/text/WordErrors.kt`, replace

```kotlin
package dev.kestrel.app.listen

/**
```

with

```kotlin
package dev.kestrel.commands.text

/**
```

Then replace

```kotlin
 * 99, because the recogniser may write either: "six forty-five" is "6:45", and "twenty five" is "25". The word error
 * rate is [errors] divided by [words]. The evaluation hook of debug builds uses it. It holds only counts, so it may be
 * printed.
 */
internal data class WordErrors(val words: Int, val substitutions: Int, val deletions: Int, val insertions: Int) {
    val errors: Int get() = substitutions + deletions + insertions
```

with

```kotlin
 * 99, because the recogniser may write either: "six forty-five" is "6:45", and "twenty five" is "25". The word error
 * rate is [errors] divided by [words]. The evaluation hook of debug builds and Commands.sameAction use it. It holds
 * only counts, so it may be printed.
 */
data class WordErrors(val words: Int, val substitutions: Int, val deletions: Int, val insertions: Int) {
    val errors: Int get() = substitutions + deletions + insertions
```

In `core/commands/src/test/kotlin/dev/kestrel/commands/text/WordErrorsTest.kt`, replace

```kotlin
package dev.kestrel.app.listen

import org.junit.Assert.assertEquals
```

with

```kotlin
package dev.kestrel.commands.text

import org.junit.Assert.assertEquals
```

- [ ] **Step 5: Write `Commands.sameAction`**

Create `core/commands/src/main/kotlin/dev/kestrel/commands/SameAction.kt`:

```kotlin
package dev.kestrel.commands

import dev.kestrel.commands.edit.Dictation
import dev.kestrel.commands.edit.DictationPart
import dev.kestrel.commands.edit.TextState
import dev.kestrel.commands.phone.Contact
import dev.kestrel.commands.phone.ContactMatch
import dev.kestrel.commands.phone.ContactMatcher
import dev.kestrel.commands.text.LabelMatcher
import dev.kestrel.commands.text.MatchCandidate
import dev.kestrel.commands.text.MatchDecision
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.commands.text.Strictness
import dev.kestrel.commands.text.WordErrors
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Compares what a corpus line asks for with what Kestrel heard (design spec, section 16.4): whether the heard command
 * leads to the same action as the expected one.
 */
object Commands {
    /**
     * Whether [heard] is the same command as [expected]: the same variant with the same fields. A label or an app's name
     * counts as the same when the matcher finds the same one of the scene's [labels] for both, and a contact's name when
     * the contact matcher finds the same one of them; when it finds none for the expected words, the folded words must
     * have no word errors. Any other text, such as a typed text or a
     * search, counts as the same when it has no word errors. A bare text counts as the same when both find the same of
     * the [labels] with the strict limits of bare labels, or else when its words have no errors.
     */
    fun sameAction(expected: Command, heard: Command, labels: List<String>): Boolean = same(expected, heard, labels)

    /**
     * Whether dictating [heard] into an empty field types what dictating [expected] types: no word errors, and the same
     * punctuation marks in the same order.
     */
    fun sameDictation(expected: String, heard: String): Boolean {
        val want = dictated(expected)
        val got = dictated(heard)
        return WordErrors.count(want, got).errors == 0 && marks(want) == marks(got)
    }

    private fun same(expected: Any?, heard: Any?, labels: List<String>): Boolean = when {
        expected == null || heard == null -> expected == heard
        expected.javaClass != heard.javaClass -> false
        expected is Target.Label -> sameName(expected.spoken, (heard as Target.Label).spoken, labels)
        expected is Command.Bare -> sameBare(expected, heard as Command.Bare, labels)
        expected is PhoneCommand.OpenApp ->
            expected.work == (heard as PhoneCommand.OpenApp).work && sameName(expected.name, heard.name, labels)
        expected is CallTarget.Name -> sameContact(expected, heard as CallTarget.Name, labels)
        expected is String -> sameWords(expected, heard as String)
        expected is Number || expected is Boolean || expected is Char || expected is Enum<*> -> expected == heard
        else -> fields(expected.javaClass).all { same(it.get(expected), it.get(heard), labels) }
    }

    private fun sameName(expected: String, heard: String, labels: List<String>): Boolean {
        val found = found(expected, labels, Strictness.NORMAL) ?: return sameWords(expected, heard)
        return found == found(heard, labels, Strictness.NORMAL)
    }

    private fun sameBare(expected: Command.Bare, heard: Command.Bare, labels: List<String>): Boolean {
        if (expected.number != heard.number) return false
        val found = found(expected.text, labels, Strictness.STRICT) ?: return sameWords(expected.text, heard.text)
        return found == found(heard.text, labels, Strictness.STRICT)
    }

    // The label that the matcher finds for [spoken] among [labels], or null.
    private fun found(spoken: String, labels: List<String>, strictness: Strictness): String? {
        val candidates = labels.map { MatchCandidate(it, listOf(it)) }
        return (LabelMatcher.decide(spoken, candidates, strictness) as? MatchDecision.Found)?.id
    }

    // The words after folding, so that "Petrović" and "Petrovic" are the same word, as the matcher takes them.
    private fun sameWords(expected: String, heard: String): Boolean =
        WordErrors.count(Normalizer.normalize(expected), Normalizer.normalize(heard)).errors == 0

    // The contact that Kestrel would call, with the scene's labels as the contacts, or else the folded words.
    private fun sameContact(expected: CallTarget.Name, heard: CallTarget.Name, labels: List<String>): Boolean {
        if (expected.kind?.let(Normalizer::normalize) != heard.kind?.let(Normalizer::normalize)) return false
        val contacts = labels.mapIndexed { index, label -> Contact(index.toLong(), listOf(label), emptyList(), starred = false) }
        val wanted = (ContactMatcher.find(expected.name, contacts) as? ContactMatch.One)?.contact?.id
            ?: return sameWords(expected.name, heard.name)
        return wanted == (ContactMatcher.find(heard.name, contacts) as? ContactMatch.One)?.contact?.id
    }

    // The text that dictating [spoken] types into an empty field; a new line is a line break.
    private fun dictated(spoken: String): String {
        var state = TextState("", 0, 0)
        for (part in Dictation.insert(state, spoken, password = false)) {
            state = when (part) {
                is DictationPart.Text -> part.change.let { change ->
                    TextState(state.text.replaceRange(change.start, change.end, change.replacement), change.selectionStart, change.selectionEnd)
                }
                DictationPart.NewLine -> {
                    val text = state.text.substring(0, state.selectionEnd) + "\n" + state.text.substring(state.selectionEnd)
                    TextState(text, state.selectionEnd + 1, state.selectionEnd + 1)
                }
            }
        }
        return state.text
    }

    private fun marks(text: String): String = text.filter { !it.isLetterOrDigit() && !it.isWhitespace() && it != '\'' }

    // The fields of a command's class, which are its properties; the classes come from this module's model.
    private fun fields(type: Class<*>): List<Field> =
        type.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.onEach { it.isAccessible = true }
}
```

- [ ] **Step 6: Run the tests of `:core:commands` to verify that they pass**

Run: `scripts\gradle.ps1 :core:commands:test`
Expected: `BUILD SUCCESSFUL`, with 121 tests (`SameActionTest` 5, `CorpusTest` 2, and `WordErrorsTest` 5, which moved here). If `CorpusTest` fails, its message names the corpus line by its id: an `appendix` line that is no command, or a `conversation` or `dictation` line that plan 3B's or 3C's grammar takes as a command. Report the line to the controller, and change neither the line nor a grammar.

- [ ] **Step 7: Let the evaluation hook check the instant commands**

In `app/src/debug/kotlin/dev/kestrel/app/debug/EvalHooks.kt`, replace

```kotlin
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.app.listen.BoostWordSource
import dev.kestrel.app.listen.LauncherAppLabels
import dev.kestrel.app.listen.WordErrors
import dev.kestrel.app.orchestrator.Request
import dev.kestrel.app.orchestrator.Requests
import dev.kestrel.voice.ModelsNotReadyException
```

with

```kotlin
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.app.listen.BoostWordBudget
import dev.kestrel.app.listen.BoostWordSource
import dev.kestrel.app.listen.LauncherAppLabels
import dev.kestrel.app.orchestrator.Request
import dev.kestrel.app.orchestrator.Requests
import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandParser
import dev.kestrel.commands.CommandVocabulary
import dev.kestrel.commands.Commands
import dev.kestrel.commands.text.WordErrors
import dev.kestrel.voice.ModelsNotReadyException
```

Then replace

```kotlin
 * does, transcribes it with the boost words of Kestrel's voice session, and compares the transcript with the text that
 * was to be said. Their answers hold only counts and times, except that VOICE_EVAL with `reveal` adds the transcript,
 * which it does only for a generated clip, whose name starts with [GENERATED]. They log nothing.
 */
```

with

```kotlin
 * does, transcribes it with the boost words of Kestrel's voice session, and compares the transcript with the text that
 * was to be said; for a line of the corpus with its group (`group`), it also uses the command vocabulary and the labels
 * of the line's scene as the screen's boost words, as each utterance of Kestrel's voice session does, and for a line of
 * the instant commands it answers whether the transcript leads to the same action (sub-project 3). Their answers hold only counts and times, except that
 * VOICE_EVAL with `reveal` adds the transcript, which it does only for a generated clip, whose name starts with
 * [GENERATED]. They log nothing.
 */
```

Then replace

```kotlin
        }
        return evaluationLock.withLock {
```

with

```kotlin
        }
        // The labels of the screen in front, for the A71 session's transcription time on a busy screen; they never leave
        // the device. Otherwise the labels of the line's scene, which scripts\voice-eval.ps1 passes.
        val labels = if (intent.getBooleanExtra(EXTRA_SCREEN_LABELS, false)) {
            graph.screen.value?.let { BoostWordBudget.screenNames(it.snapshots.current().snapshot) }.orEmpty()
        } else {
            intent.getStringExtra(EXTRA_LABELS)?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        }
        return evaluationLock.withLock {
```

Then replace

```kotlin
                ?: throw HookError("load the recogniser first with --ez load true, until it answers ready")
            withContext(Dispatchers.Default) { compare(ready, samples, expected, kind, intent, reveal) }
        }
```

with

```kotlin
                ?: throw HookError("load the recogniser first with --ez load true, until it answers ready")
            withContext(Dispatchers.Default) { compare(ready, samples, expected, kind, intent, reveal, labels) }
        }
```

Then replace

```kotlin
        reveal: Boolean,
    ): String {
```

with

```kotlin
        reveal: Boolean,
        labels: List<String>,
    ): String {
```

Then replace

```kotlin
        val started = SystemClock.elapsedRealtime()
        // Without the boost words on request, to show their effect (plan 2C-3's final review, item 3).
        val words = if (intent.getBooleanExtra(EXTRA_NO_BOOST, false)) emptyList() else ready.words
        val heard = if (speech.isEmpty()) "" else recognizer.transcribe(speech, words).text
```

with

```kotlin
        val started = SystemClock.elapsedRealtime()
        // A line of the corpus also gets the command vocabulary and its scene's labels as the screen's, as each
        // utterance of Kestrel's voice session does (design spec, section 8.3), unless noLabels leaves them out to show
        // their effect. Without any boost words on request, to show theirs (plan 2C-3's final review, item 3).
        val group = intent.getStringExtra(EXTRA_GROUP)
        val words = when {
            intent.getBooleanExtra(EXTRA_NO_BOOST, false) -> emptyList()
            group == null || intent.getBooleanExtra(EXTRA_NO_LABELS, false) -> ready.words
            else -> BoostWordBudget.combine(ready.words, labels, CommandVocabulary.BOOST)
        }
        val heard = if (speech.isEmpty()) "" else recognizer.transcribe(speech, words).text
```

Then replace

```kotlin
            "insertions=${errors.insertions} request=$request read=$read millis=$millis seconds=${seconds(speech.size)} " +
            "pss=${Debug.getPss() / 1024} parts=${parts.size} twice=$twice"
        return if (reveal) "$answer heard=$heard" else answer
    }
```

with

```kotlin
            "insertions=${errors.insertions} request=$request read=$read millis=$millis seconds=${seconds(speech.size)} " +
            "pss=${Debug.getPss() / 1024} parts=${parts.size} twice=$twice" +
            group?.let { " command=${command(it, expected, heard, labels)}" }.orEmpty()
        return if (reveal) "$answer heard=$heard" else answer
    }

    /**
     * Whether what Kestrel heard leads to the action that the line's text leads to (design spec, section 16.4): 1 or 0,
     * and - for a group without instant commands. A conversation line must stay bare speech, which acts on nothing; a
     * dictation line must stay bare and type the same words and punctuation as the text; any other line must be the
     * same command, with the same labels of its scene.
     */
    private fun command(group: String, expected: String, heard: String, labels: List<String>): String {
        val said = CommandParser.parse(heard)
        val right = when (group) {
            CONVERSATION -> said == null || said is Command.Bare
            DICTATION -> said is Command.Bare && Commands.sameDictation(expected, heard)
            APPENDIX -> {
                val wanted = CommandParser.parse(expected)
                wanted != null && said != null && Commands.sameAction(wanted, said, labels)
            }
            else -> return "-"
        }
        return if (right) "1" else "0"
    }
```

Then replace

```kotlin
        private const val EXTRA_NO_BOOST = "noBoost"
    }
```

with

```kotlin
        private const val EXTRA_NO_BOOST = "noBoost"
        private const val EXTRA_GROUP = "group"
        private const val EXTRA_LABELS = "labels"
        private const val EXTRA_NO_LABELS = "noLabels"
        private const val EXTRA_SCREEN_LABELS = "screenLabels"

        // The groups of corpus\commands.txt whose lines are instant commands, speech that must stay bare, and dictation.
        private const val APPENDIX = "appendix"
        private const val CONVERSATION = "conversation"
        private const val DICTATION = "dictation"
    }
```

In `scripts/hook.ps1`, replace

```powershell
#   voice-eval -File name -Expected 'text' -Kind kind [-Spotted] [-AfterName] [-Reveal] [-NoBoost]
#                                           transcribes the clip and compares it with the text: the numbers of words and
```

with

```powershell
#   voice-eval -File name -Expected 'text' -Kind kind [-Spotted] [-AfterName] [-Reveal] [-NoBoost]
#              [-Group group [-Labels 'label;label'] [-NoLabels] [-ScreenLabels]]
#                                           transcribes the clip and compares it with the text: the numbers of words and
```

Then replace

```powershell
#                                           memory; -Reveal adds what Kestrel heard, only for a generated clip, whose
#                                           name starts with gen-; -NoBoost transcribes without the boost words
#   voice-eval -Close                       closes that recogniser
```

with

```powershell
#                                           memory; -Reveal adds what Kestrel heard, only for a generated clip, whose
#                                           name starts with gen-; -NoBoost transcribes without the boost words. With
#                                           the corpus's group of the line, the boost words also have the command
#                                           vocabulary and the labels as the screen's, as in Kestrel's voice session,
#                                           unless -NoLabels leaves both out, and the answer ends with command=1 when the
#                                           transcript leads to the same instant command as the text, 0 when not, and -
#                                           for a group without instant commands; -ScreenLabels takes the labels from
#                                           the screen in front, which never leave the device
#   voice-eval -Close                       closes that recogniser
```

Then replace

```powershell
    [switch]$NoBoost,
    [string]$Create,
```

with

```powershell
    [switch]$NoBoost,
    [string]$Group,
    [string]$Labels,
    [switch]$NoLabels,
    [switch]$ScreenLabels,
    [string]$Create,
```

Then replace

```powershell
if ($Reply) { $extras += "--es reply $(ConvertTo-ShellText $Reply)" }
if ($NoBoost) { $extras += '--ez noBoost true' }

$hookAction = 'dev.kestrel.hook.' + $Command.ToUpperInvariant().Replace('-', '_')
```

with

```powershell
if ($Reply) { $extras += "--es reply $(ConvertTo-ShellText $Reply)" }
if ($NoBoost) { $extras += '--ez noBoost true' }
if ($Group) { $extras += "--es group $(ConvertTo-ShellText $Group)" }
if ($PSBoundParameters.ContainsKey('Labels')) { $extras += "--es labels $(ConvertTo-ShellText $Labels)" }
if ($NoLabels) { $extras += '--ez noLabels true' }
if ($ScreenLabels) { $extras += '--ez screenLabels true' }

$hookAction = 'dev.kestrel.hook.' + $Command.ToUpperInvariant().Replace('-', '_')
```

- [ ] **Step 8: Write the failing Pester tests of the scenes and the command accuracy**

In `scripts/tests/Corpus.Tests.ps1`, replace

```powershell
    It 'reads the repository''s corpus' {
        $rows = @(Read-Corpus -Path "$PSScriptRoot\..\..\corpus\commands.txt")

        $rows.Count | Should -Be 111
        @($rows | Where-Object { $_.Owner }).Count | Should -Be 100
        @($rows | Select-Object -ExpandProperty Id -Unique).Count | Should -Be 111
        $rows[0].Text | Should -BeExactly 'Open Chrome.'
        @($rows | Where-Object { $_.Context -eq 'answer' }).Count | Should -Be 12
    }

    It 'reads the columns, and skips comments and empty lines' {
```

with

```powershell
    It 'reads the repository''s corpus' {
        $rows = @(Read-Corpus -Path "$PSScriptRoot\..\..\corpus\commands.txt")

        $rows.Count | Should -Be 315
        @($rows | Where-Object { $_.Owner }).Count | Should -Be 160
        @($rows | Select-Object -ExpandProperty Id -Unique).Count | Should -Be 315
        $rows[0].Text | Should -BeExactly 'Open Chrome.'
        @($rows | Where-Object { $_.Context -eq 'answer' }).Count | Should -Be 12
        @($rows | Where-Object { $_.Group -eq 'conversation' }).Count | Should -Be 20
        @($rows | Where-Object { $_.Group -eq 'dictation' }).Count | Should -Be 5
    }

    It 'reads the columns, and skips comments and empty lines' {
```

Then replace

```powershell
        { Read-Corpus -Path (New-CorpusFile @($line, $line)) } | Should -Throw '*listed twice*'
    }
```

with

```powershell
        { Read-Corpus -Path (New-CorpusFile @($line, $line)) } | Should -Throw '*listed twice*'
    }
}

Describe 'Read-CorpusScenes' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Corpus.ps1"

        # Writes a scenes file with these lines, and returns its path.
        function New-ScenesFile([string[]]$Lines) {
            $path = Join-Path $TestDrive 'scenes.txt'
            [IO.File]::WriteAllText($path, ($Lines -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
            $path
        }
    }

    It 'gives each line the labels of its scene, and skips comments and empty lines' {
        $lines = Read-CorpusScenes -Path (New-ScenesFile @('# a comment', '', 'scene dialog | Cancel; OK ;; Delete', 'line c129 | dialog', 'line x1 | dialog'))

        $lines.Count | Should -Be 2
        $lines['c129'] | Should -Be @('Cancel', 'OK', 'Delete')
        $lines['x1'] | Should -Be @('Cancel', 'OK', 'Delete')
    }

    It 'refuses a line that it cannot read: <_>' -ForEach @(
        'scene | Cancel',
        'scene dialog |  ; ',
        'line c129 | nowhere',
        'screen dialog | Cancel'
    ) {
        { Read-CorpusScenes -Path (New-ScenesFile @($_)) } | Should -Throw '*scenes.txt:1:*'
    }

    It 'refuses a scene or a line that is listed twice' {
        { Read-CorpusScenes -Path (New-ScenesFile @('scene s | a', 'scene s | b')) } | Should -Throw '*listed twice*'
        { Read-CorpusScenes -Path (New-ScenesFile @('scene s | a', 'line x1 | s', 'line x1 | s')) } | Should -Throw '*listed twice*'
    }

    It 'reads the repository''s scenes, whose lines are lines of the corpus' {
        $ids = @(Read-Corpus -Path "$PSScriptRoot\..\..\corpus\commands.txt" | Select-Object -ExpandProperty Id)
        $lines = Read-CorpusScenes -Path "$PSScriptRoot\..\..\corpus\scenes.txt"

        $lines.Count | Should -Be 21
        @($lines.Keys | Where-Object { $_ -notin $ids }) | Should -BeNullOrEmpty
        $lines['c020'] | Should -Contain 'Send'
    }
```

Then replace

```powershell
        )
    }
}
```

with

```powershell
        )
    }

    It 'counts the commands that lead to the correct action, and leaves out the other lines' {
        $totals = New-EvalTotals
        foreach ($command in '1', '0', '1', '-') {
            Add-EvalResult -Totals $totals -Answer (ConvertFrom-HookAnswer "words=2 substitutions=0 deletions=0 insertions=0 request=ok read=Other millis=200 seconds=1.0 pss=900 parts=1 twice=0 command=$command")
        }

        (Format-EvalSummary $totals)[2] | Should -BeExactly 'Commands that lead to the correct action: 2 of 3 (66.7 %)'
    }

    It 'prints the word error rate of a group of lines' {
        $totals = New-EvalTotals
        Add-EvalResult -Totals $totals -Answer (ConvertFrom-HookAnswer 'words=8 substitutions=1 deletions=0 insertions=0 request=ok read=Other millis=200 seconds=2.0 pss=900 twice=0')

        Format-GroupErrors -Name 'Dictation lines' -Totals $totals | Should -BeExactly 'Dictation lines: 1 clips, 8 words, word error rate 12.5 %'
    }
}
```

Run: `scripts\test-scripts.ps1`
Expected: exit code 1 and "The script tests or checks failed.": in each shell the new tests fail with "The term 'Read-CorpusScenes' is not recognized" or "The term 'Format-GroupErrors' is not recognized", or because the totals have no `Commands`.

- [ ] **Step 9: Read the scenes and print the command accuracy**

In `scripts/lib/Corpus.ps1`, replace

```powershell
            Text = $text
        }
    }
}

# How long the A71 session records an utterance of the owner, in whole seconds: half a second for each word and 2 s more,
```

with

```powershell
            Text = $text
        }
    }
}

# Reads corpus\scenes.txt: the labels that the screen would show while the owner says some lines of corpus\commands.txt,
# which the evaluation hook gives the recogniser as boost words and the matcher as the screen (design spec, section
# 16.4). It has two kinds of lines, 'scene <name> | <label>; <label>; ...' and 'line <id> | <scene>'; lines that start
# with '#' and empty lines are skipped. Returns a table from each line's id to the labels of its scene. Throws, with the
# file and the line's number, on a line of another kind, a scene without labels or listed twice, a line that names a
# scene that no earlier line lists, or an id listed twice.
function Read-CorpusScenes([string]$Path) {
    $scenes = @{}
    $lines = @{}
    $number = 0
    foreach ($line in [IO.File]::ReadAllLines($Path, [Text.UTF8Encoding]::new($false))) {
        $number++
        if (-not $line.Trim() -or $line.TrimStart().StartsWith('#')) { continue }
        $where = "${Path}:${number}:"
        $head, $rest = $line -split '\|', 2
        $match = [regex]::Match($head.Trim(), '^(scene|line) ([a-z0-9-]+)$')
        if ($null -eq $rest -or -not $match.Success) { throw "$where a line must be 'scene <name> | <labels>' or 'line <id> | <scene>'." }
        $name = $match.Groups[2].Value
        $value = $rest.Trim()
        if ($match.Groups[1].Value -eq 'scene') {
            if ($scenes.ContainsKey($name)) { throw "$where the scene $name is listed twice." }
            $labels = @($value -split ';' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
            if ($labels.Count -eq 0) { throw "$where the scene $name needs labels." }
            $scenes[$name] = $labels
        } else {
            if (-not $scenes.ContainsKey($value)) { throw "$where unknown scene '$value'." }
            if ($lines.ContainsKey($name)) { throw "$where the line $name is listed twice." }
            $lines[$name] = $scenes[$value]
        }
    }
    $lines
}

# How long the A71 session records an utterance of the owner, in whole seconds: half a second for each word and 2 s more,
```

Then replace

```powershell
        Pss = 0
        Millis = [System.Collections.Generic.List[double]]::new()
```

with

```powershell
        Pss = 0
        Commands = 0
        RightCommands = 0
        Millis = [System.Collections.Generic.List[double]]::new()
```

Then replace

```powershell
        if ($Answer.request -eq 'ok') { $Totals.Intended++ }
    }
```

with

```powershell
        if ($Answer.request -eq 'ok') { $Totals.Intended++ }
    }
    # The answer of an instant command's line says whether it leads to the correct action; '-' is any other line.
    if ($Answer.Contains('command') -and $Answer.command -ne '-') {
        $Totals.Commands++
        if ($Answer.command -eq '1') { $Totals.RightCommands++ }
    }
```

Then replace

```powershell
    "Requests read as intended: $($Totals.Intended) of $($Totals.Checked) ($(Format-Percent $Totals.Intended $Totals.Checked) %)"
    "Transcription: median $median ms, 90th percentile $ninetieth ms, longest $longest ms; at most $perSecond ms per second of audio"
```

with

```powershell
    "Requests read as intended: $($Totals.Intended) of $($Totals.Checked) ($(Format-Percent $Totals.Intended $Totals.Checked) %)"
    if ($Totals.Commands -gt 0) {
        "Commands that lead to the correct action: $($Totals.RightCommands) of $($Totals.Commands) ($(Format-Percent $Totals.RightCommands $Totals.Commands) %)"
    }
    "Transcription: median $median ms, 90th percentile $ninetieth ms, longest $longest ms; at most $perSecond ms per second of audio"
```

Then replace

```powershell
    "Kestrel's memory (PSS): at most $($Totals.Pss) MB"
}
```

with

```powershell
    "Kestrel's memory (PSS): at most $($Totals.Pss) MB"
}

# The word error rate of a group of lines, such as the dictation or the task lines, as one line of the summary.
function Format-GroupErrors([string]$Name, $Totals) {
    $errors = $Totals.Substitutions + $Totals.Deletions + $Totals.Insertions
    "${Name}: $($Totals.Clips) clips, $($Totals.Words) words, word error rate $(Format-Percent $errors $Totals.Words) %"
}
```

In `scripts/voice-eval.ps1`, replace

```powershell
# Measures Kestrel's recognition on a voice corpus on a test device (design spec, sections 5.3 and 16.4): the word error
# rate, the share of utterances that Kestrel's Requests reads as intended, how long each transcription takes, how often
# the recogniser decodes a clip a second time without boost words, and the memory that Kestrel uses (PSS). -Corpus
# generated uses the clips that scripts\make-corpus.ps1 made in corpus\generated\<voice>; -Corpus owner uses the owner's
```

with

```powershell
# Measures Kestrel's recognition on a voice corpus on a test device (design spec, sections 5.3 and 16.4): the word error
# rate, the share of utterances that Kestrel's Requests reads as intended, the share of instant-command lines that lead
# to the correct action, the word error rates of the dictation and the task lines, how long each transcription takes, how
# often the recogniser decodes a clip a second time without boost words, and the memory that Kestrel uses (PSS). -Corpus
# generated uses the clips that scripts\make-corpus.ps1 made in corpus\generated\<voice>; -Corpus owner uses the owner's
```

Then replace

```powershell
# into Kestrel's folder Android/data/dev.kestrel/files/eval, and the hook voice-eval transcribes it with the boost words
# of Kestrel's voice session and compares it there with the text that was to be said. For the owner's recordings the
# hook answers only counts and times, and the script prints only totals; for the generated clips, -Show also prints each
# clip that Kestrel misheard or read otherwise, with what it heard. Kestrel must be off, because its voice session would
# hold a second recogniser of about 700 MB. At the end, also when it fails or is interrupted, the script deletes the
# folder's clips on the device and closes the hook's recogniser. -NoBoost transcribes without the boost words, so that
# a second run shows what they change. It needs Kestrel's debug build, its speech models and PowerShell 7.3 or newer, as
# scripts\hook.ps1 does.
# Example: scripts\voice-eval.ps1 -Serial emulator-5554 -Corpus generated -Voice piper -Show
```

with

```powershell
# into Kestrel's folder Android/data/dev.kestrel/files/eval, and the hook voice-eval transcribes it with the boost words
# of Kestrel's voice session and the command vocabulary, and for a line with a scene in corpus\scenes.txt also with the
# scene's labels as the screen's, and compares it there with the text that was to be said. For the owner's recordings
# the hook answers only counts and times, and the script prints only totals and the ids of the lines whose command was
# wrong; for the generated clips, -Show also prints each clip that Kestrel misheard or read otherwise, with what it
# heard. Kestrel must be off, because its voice session would hold a second recogniser of about 700 MB. At the end,
# also when it fails or is interrupted, the script deletes the folder's clips on the device and closes the hook's
# recogniser. -NoBoost transcribes without any boost words, and -NoLabels without the screen's labels and the command
# vocabulary, so that a second run shows what they change (design spec, section 8.3). It needs Kestrel's debug build,
# its speech models and PowerShell 7.3 or newer, as scripts\hook.ps1 does.
# Example: scripts\voice-eval.ps1 -Serial emulator-5554 -Corpus generated -Voice piper -Show
```

Then replace

```powershell
    [switch]$Show,
    [switch]$NoBoost
)
```

with

```powershell
    [switch]$Show,
    [switch]$NoBoost,
    [switch]$NoLabels
)
```

Then replace

```powershell
$rows = @(Read-Corpus -Path (Join-Path $root 'commands.txt') | Where-Object { Test-Path -LiteralPath (Join-Path $folder "$($_.Id).wav") })
if ($rows.Count -eq 0) {
```

with

```powershell
$rows = @(Read-Corpus -Path (Join-Path $root 'commands.txt') | Where-Object { Test-Path -LiteralPath (Join-Path $folder "$($_.Id).wav") })
$scenes = Read-CorpusScenes -Path (Join-Path $root 'scenes.txt')
if ($rows.Count -eq 0) {
```

Then replace

```powershell
$totals = New-EvalTotals
$misheard = [System.Collections.Generic.List[string]]::new()
try {
```

with

```powershell
$totals = New-EvalTotals
# The dictation and the task lines' word error rates, which more boost words could raise (design spec, section 8.3).
$groups = [ordered]@{ dictation = New-EvalTotals; task = New-EvalTotals }
$misheard = [System.Collections.Generic.List[string]]::new()
# A line's id and text are public; what Kestrel heard stays on the device.
$wrongCommands = [System.Collections.Generic.List[string]]::new()
try {
```

Then replace

```powershell
            NoBoost = [bool]$NoBoost
        }
        $answer = ConvertFrom-HookAnswer (Invoke-Hook voice-eval $options)
        Add-EvalResult -Totals $totals -Answer $answer
        $errors = [int]$answer.substitutions + [int]$answer.deletions + [int]$answer.insertions
        if ($Show -and ($errors -gt 0 -or $answer.request -eq 'wrong')) {
            $misheard.Add("$($row.Id): '$($row.Text)' was heard as '$($answer.heard)' and read as $($answer.read)")
        }
```

with

```powershell
            NoBoost = [bool]$NoBoost
            Group = $row.Group
            NoLabels = [bool]$NoLabels
        }
        if ($scenes.ContainsKey($row.Id)) { $options.Labels = $scenes[$row.Id] -join ';' }
        $answer = ConvertFrom-HookAnswer (Invoke-Hook voice-eval $options)
        Add-EvalResult -Totals $totals -Answer $answer
        if ($groups.Contains($row.Group)) { Add-EvalResult -Totals $groups[$row.Group] -Answer $answer }
        if ($answer.command -eq '0') { $wrongCommands.Add($row.Id) }
        $errors = [int]$answer.substitutions + [int]$answer.deletions + [int]$answer.insertions
        if ($Show -and ($errors -gt 0 -or $answer.request -eq 'wrong' -or $answer.command -eq '0')) {
            $misheard.Add("$($row.Id): '$($row.Text)' was heard as '$($answer.heard)', read as $($answer.read), command=$($answer.command)")
        }
```

Then replace

```powershell
Format-EvalSummary $totals | ForEach-Object { Write-Host $_ }
$misheard | ForEach-Object { Write-Host $_ }
```

with

```powershell
Format-EvalSummary $totals | ForEach-Object { Write-Host $_ }
Write-Host (Format-GroupErrors -Name 'Dictation lines' -Totals $groups.dictation)
Write-Host (Format-GroupErrors -Name 'Task lines' -Totals $groups.task)
if ($wrongCommands.Count -gt 0) { Write-Host "Lines that did not lead to the correct action: $($wrongCommands -join ', ')" }
$misheard | ForEach-Object { Write-Host $_ }
```

- [ ] **Step 10: Run the script tests**

Run: `scripts\test-scripts.ps1`
Expected: "The script tests and checks passed.", with 153 Pester tests in each shell (`Corpus.Tests.ps1` 9 more) and "PSScriptAnalyzer found 0 problems."

- [ ] **Step 11: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin :testapp:assembleDebug`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:commands` as in Step 6, `:app` 349, because `WordErrorsTest` moved, the others as before), lint without issues in every Android module, the audit, and the device tests and the test app compile, among them the debug build's `EvalHooks`.

- [ ] **Step 12: Run the voice device tests**

The voice device tests include plan 2D's `EvalHookTest`, which checks the hook's answer without a group; `voice-test.ps1` also copies the speech models with Kokoro, which Step 13 needs.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: `BUILD SUCCESSFUL` and "Voice tests: 83 run, 0 failed, 0 errors, 0 skipped.", with no warning at the end.

- [ ] **Step 13: Make the generated corpus in both voices**

Kestrel must be off, because its voice session would hold a second recogniser.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 voice -Send off; pwsh -NoProfile -File scripts\make-corpus.ps1 -Serial emulator-5554 -Voice piper; pwsh -NoProfile -File scripts\make-corpus.ps1 -Serial emulator-5554 -Voice kokoro`
Expected: both runs end without an error, and `corpus\generated\piper` and `corpus\generated\kokoro` each hold 315 clips (`(Get-ChildItem corpus\generated\piper -Filter *.wav).Count`).

- [ ] **Step 14: Measure the command accuracy with and without the labels**

Run each of these four commands and keep each one's summary (the lines from "Clips:" to "Task lines:"):

`. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; pwsh -NoProfile -File scripts\voice-eval.ps1 -Serial emulator-5554 -Corpus generated -Voice piper -Show`

`. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; pwsh -NoProfile -File scripts\voice-eval.ps1 -Serial emulator-5554 -Corpus generated -Voice kokoro -Show`

`. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; pwsh -NoProfile -File scripts\voice-eval.ps1 -Serial emulator-5554 -Corpus generated -Voice piper -NoLabels`

`. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; pwsh -NoProfile -File scripts\voice-eval.ps1 -Serial emulator-5554 -Corpus generated -Voice kokoro -NoLabels`

Expected: each run prints "Clips: 315", "Commands that lead to the correct action: X of 244 (Y %)", "Dictation lines: 5 clips, …" and "Task lines: 19 clips, …"; in the first two runs (with the labels) Y is at least 95.0. If Y is below 95.0 in either of the first two runs, stop and report to the controller with both summaries and the lines that `-Show` printed with `command=0`; the controller decides whether the grammar, the matcher's limits in `MatchRules.kt` (with spec 9.6's `- **Limits:**` line) or the vocabulary changes, and the runs repeat after that change. Do not change a corpus line to reach the target. The voices read "Call 064 123 4567." (c163) as whole numbers ("zero sixty-four, one hundred twenty-three, …"); if c163 is among the lines with `command=0` because the recogniser wrote those words, say so in the report, because the owner's recording would not have that cause.

- [ ] **Step 15: Write the measurements and the corpus into the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace

```markdown
- The effect of the screen's labels and the command vocabulary on the command accuracy is measured in sub-project 3.
```

with the line below, with the numbers of Step 14 in place of the letters: A, E, I and M from the first run (Piper with the labels), B, F, J and N from the second (Kokoro with the labels), C, G, K and O from the third (Piper without them), and D, H, L and P from the fourth (Kokoro without them); A to D are the percentages of "Commands that lead to the correct action", E to H the word error rates of "Dictation lines", I to L those of "Task lines", and M to P the first numbers of "Decoded a second time without boost words".

```markdown
- The effect of the screen's labels and the command vocabulary, measured on kestrel-api37 with the generated corpus (plan 3C): with them, A % of the instant-command lines of the Piper clips and B % of the Kokoro clips led to the correct action, and without them (`scripts\voice-eval.ps1 -NoLabels`) C % and D %. The word error rate of the dictation lines was E % and F % with them and G % and H % without them, that of the task lines I % and J % with them and K % and L % without them, and the recogniser decoded M and N clips a second time without boost words with them, and O and P without them.
```

Then replace

```markdown
### 16.4 Voice tests

- The corpus has generated English commands and about 100 recordings of the owner, made once on the A71 or on the PC. **The owner's recordings stay out of the public repository,** in a folder that git ignores.
- `corpus/commands.txt` lists the corpus's utterances, none of them private: Appendix A's commands for the later sub-projects, Kestrel's own phrases, answers to "Yes?", tasks with the name, phrases near Kestrel's words and the call commands. Each line says what `Requests` should read, by the rules of section 8.3 as they are, and whether the A71 session asks the owner to record it (100 lines). `scripts/make-corpus.ps1` says each utterance in one of Kestrel's voices on a test device into a 16 kHz WAV clip in `corpus/generated/<voice>`, and the A71 session records the owner's into `corpus/owner/clips`; git ignores both folders.
- `scripts/voice-eval.ps1` copies each clip into Kestrel's files folder and sends its text with the hook's call, and a hook of the debug build cuts the clip's speech out with Kestrel's speech detector, as the pipeline does, transcribes it with the boost words of Kestrel's voice session and compares it on the device. A number counts the same in words and in digits. The hook answers only counts and times: words, substitutions, deletions and insertions, whether `Requests` read the utterance as intended and the kind of request that it read, the transcription's time, the length of the speech, Kestrel's memory (PSS), the number of utterances that the detector found and whether the recogniser decoded the clip a second time without boost words. Only for a generated clip, whose name on the device starts with `gen-`, may it also return what it heard. `-NoBoost` transcribes without the boost words, which shows their effect. The script prints the word error rate, the share of requests read as intended, the median, the 90th percentile and the longest transcription time, and the memory.
- Command accuracy and word error rate are measured on the emulator for every release candidate, and whenever the voice code or a model changes.
```

with

```markdown
### 16.4 Voice tests

- The corpus has generated English commands and about 160 recordings of the owner, made in the A71 sessions of sub-projects 2 and 3. **The owner's recordings stay out of the public repository,** in a folder that git ignores.
- `corpus/commands.txt` lists the corpus's utterances, none of them private: Appendix A's instant commands, with every "Yes" row in at least two phrasings, and bare labels and numbers; speech that is no command, which must stay bare speech; dictated text with spoken punctuation; Kestrel's own phrases, answers to "Yes?", tasks with the name, phrases near Kestrel's words and the call commands. Each line says what `Requests` should read, by the rules of section 8.3 as they are, and whether the A71 session asks the owner to record it (160 lines). `corpus/scenes.txt` gives some instant-command lines the labels that the screen would show, with others near them. `scripts/make-corpus.ps1` says each utterance in one of Kestrel's voices on a test device into a 16 kHz WAV clip in `corpus/generated/<voice>`, and the A71 sessions record the owner's into `corpus/owner/clips`; git ignores both folders.
- `scripts/voice-eval.ps1` copies each clip into Kestrel's files folder and sends its text with the hook's call, and a hook of the debug build cuts the clip's speech out with Kestrel's speech detector, as the pipeline does, transcribes it with the boost words of Kestrel's voice session and compares it on the device. Every line's boost words also have the command vocabulary, and a line with a scene the scene's labels as the screen's, as each utterance of the voice session does (section 8.3). For an instant-command, conversation or dictation line, the hook also says whether the transcript leads to the same action as the text: the same command, where a label counts as the same when the matcher finds the same label of the scene for both, a contact's name when the contact matcher does, and a typed text when it has no word errors; speech that is no command must stay bare speech, and dictation must type the same words and punctuation marks. A number counts the same in words and in digits. The hook answers only counts and times: words, substitutions, deletions and insertions, whether `Requests` read the utterance as intended and the kind of request that it read, whether the instant command is right, the transcription's time, the length of the speech, Kestrel's memory (PSS), the number of utterances that the detector found and whether the recogniser decoded the clip a second time without boost words. Only for a generated clip, whose name on the device starts with `gen-`, may it also return what it heard; for the owner's recordings the script prints only totals and the ids of the lines whose command was wrong. `-NoBoost` transcribes without the boost words, and `-NoLabels` without the labels and the command vocabulary, which shows their effect. The script prints the word error rate, the share of requests read as intended, the share of instant commands that lead to the correct action, the word error rates of the dictation and the task lines, the median, the 90th percentile and the longest transcription time, and the memory.
- Command accuracy and word error rate are measured on the emulator for every release candidate, and whenever the voice code, a model, the grammar or the matcher changes. At least 95% of the instant-command lines must lead to the correct action (section 5.3), in both voices and with the owner's recordings.
```

- [ ] **Step 16: Write the corpus's new size into the README**

In `README.md`, replace

```markdown
The voice corpus is in `corpus\commands.txt`: about 110 English utterances, none of them private,
```

with

```markdown
The voice corpus is in `corpus\commands.txt`: about 315 English utterances, none of them private, among them the instant commands, speech that must stay bare and dictation,
```

- [ ] **Step 17: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 18: Commit**

Git ignores the generated clips, so they stay out of the commit; Step 4's `git mv` already staged the removal of the old `WordErrors` files.

```powershell
git add corpus/commands.txt corpus/scenes.txt core/commands/src/main/kotlin/dev/kestrel/commands/SameAction.kt core/commands/src/main/kotlin/dev/kestrel/commands/text/WordErrors.kt core/commands/src/test/kotlin/dev/kestrel/commands/SameActionTest.kt core/commands/src/test/kotlin/dev/kestrel/commands/CorpusTest.kt core/commands/src/test/kotlin/dev/kestrel/commands/text/WordErrorsTest.kt app/src/debug/kotlin/dev/kestrel/app/debug/EvalHooks.kt scripts/hook.ps1 scripts/lib/Corpus.ps1 scripts/tests/Corpus.Tests.ps1 scripts/voice-eval.ps1 docs/superpowers/specs/2026-11-12-kestrel-design.md README.md
git commit -m "feat(corpus): instant commands, conversation and dictation lines, scenes and the command accuracy" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 8: The coverage of Appendix A, the A71 session and the documents

**Files:**
- Create: `scripts/a71-commands.ps1`, `scripts/tests/A71Commands.Tests.ps1`
- Modify: `app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt` (after plan 2D: the hooks `PROFILES` and `SNAPSHOTS`), `scripts/hook.ps1` (after Task 7: the commands `profiles` and `snapshots`)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (13.5's bullet with `"Kestrel, delete history"`, 18.3's two bullet lines, 18.5's `- **Scope:**` line, a paragraph after 19.1's last line, Appendix A's row `| Kestrel, delete history |`, and the coverage table before `## Appendix B. Key facts and sources`)
- Modify: `README.md` (at the marker `<!-- Plan 3C: apps, phone settings and calls -->`)

**Interfaces:**
- Consumes: every command of plans 3A to 3C and their device tests; plan 2D's `scripts\lib\Session.ps1` (`Add-SessionResult`, the A71 checks) and `scripts\lib\Kestrel.ps1` (`Get-LargestRowId`, `Get-StreamVolume`, `Get-GrantedPermissions`, `Invoke-SettingsCommand`), `scripts\lib\Corpus.ps1` (`Read-Corpus`, `ConvertFrom-HookAnswer`), `scripts\install.ps1`, `scripts\push-models.ps1`, the hooks `voice`, `voice-state`, `voice-record` and `voice-eval` (with Task 7's `-Group` and `-ScreenLabels`); plan 3A's `ScreenSession.snapshotBuilds` and `snapshotMillis`; `LauncherApps.getProfiles`, `getActivityList` and, from Android 15, `getLauncherUserInfo`, and `UserManager.getSerialNumberForUser`.
- Produces: `scripts\a71-commands.ps1 -Serial <serial> [-DryRun] [-Step <name>[,<name>...]]` with the steps `install`, `screen`, `swipes`, `notifications`, `global`, `profiles`, `radios`, `sound`, `text`, `calls`, `timing` and `recordings`, its helpers `Get-SessionSteps`, `Test-SimAbsent`, `Get-UserSummary`, `Get-NewScreenshotIds`, `Format-CheckResult` and `Get-Median`, and the results file `corpus\owner\commands-session-<time>.txt` (`commands-dry-run-<time>.txt` for a dry run); the hooks `hook.ps1 profiles` (`user=<serial number> apps=<count> type=<user type or unknown>` per profile, separated by "; ") and `hook.ps1 snapshots` (`builds=<count> millis=<total>`); the spec's coverage table.

Design D15, D16 and D18. The A71 session runs once with the owner at the end of sub-project 3; this task writes its script and tries it on the emulator with `-DryRun`, and the controller then asks the owner for it, with an estimate of 80 to 105 minutes, of which about 70 to 95 need the owner (review C's count of its checks). The script follows D16's checklist in its order and reuses plan 2D's session helpers: it asks before it reads the device's model and before each step, records only pass, fail, counts, kinds and outcomes in a git-ignored results file, and puts back what it changes on the phone (Kestrel's mode, the accessibility settings and with them a magnification, "Stay awake", the radios, the volumes, the sound mode, the keyboard, the contacts permission and its screenshots); the clipboard stays as the owner leaves it. Android 13 has no shell command for the sound mode, so its restore, which comes after the volumes' (setting the ring volume also sets the sound mode), checks the result and warns when the owner must set it back. The results name the call by number "call the confirmed number", never the number. The step `calls` checks for a SIM card itself, right before its first call and every time it runs, also with `-Step calls`; with a SIM card, or when the phone does not say that none is there (only an empty state and `ABSENT` count), it skips the calls and says why, and otherwise the owner confirms that no SIM card is in. Kestrel presses Call only for a number that the owner gives and confirms, and for a contact of the owner's choice, never for a made-up number that could be a stranger's. With the owner's consent, the step then checks that Kestrel asks for "yes, call" in One UI's phone app with the short number 1234 and with letters in the number field ("abc", through the hook `type`), and the owner answers only "no"; never with an emergency number, because emergency calls go out without a SIM card. A failed set-text records its reason and lets the step go on, and the numbers are hidden afterwards in any case. The step `radios` refuses a phone that is connected by wireless debugging, which turning Wi-Fi off would cut. It posts the notification of the notification step with `cmd notification post`, because the test app is not installed on the A71, and it never tries an emergency number there. Two new hooks give the session what Kestrel sees without words: the profiles of the launcher API as serial numbers, numbers of apps and, from Android 15, user types, and the count and the total time of the snapshots that the screen layer built. The step `timing` compares the transcription time of the generated command clips with the labels of the busy screen in front (`voice-eval -ScreenLabels`, whose labels never leave the phone) and without them. The step `recordings` records the owner's command, conversation and dictation lines that are marked for the owner and that no earlier session recorded; `scripts\voice-eval.ps1 -Corpus owner` measures them later on the emulator, and if they miss 95%, the controller has the cause fixed and the measurement repeats on the kept recordings, without new recordings. The spec gets its coverage table, the A71 results' place in 19.1, sub-project 3's "done when", and "Kestrel, delete history" by voice moves to sub-project 5 (decision 6).

- [ ] **Step 1: Write the failing Pester tests of the session's helpers**

Create `scripts/tests/A71Commands.Tests.ps1`:

```powershell
# Tests of the helpers in scripts\a71-commands.ps1, the A71 session of the instant commands. The script needs a device,
# so the tests load only its helper functions from the script's syntax tree. scripts\test-scripts.ps1 runs these tests in
# Windows PowerShell 5.1 and in PowerShell 7.
[Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseDeclaredVarsMoreThanAssignments', '',
    Justification = 'Pester shares the variables of BeforeAll with the It blocks.')]
param()

BeforeAll {
    $tokens = $null
    $errors = $null
    $tree = [System.Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot\..\a71-commands.ps1", [ref]$tokens, [ref]$errors)
    $helpers = @('Get-SessionSteps', 'Test-SimAbsent', 'Get-UserSummary', 'Get-NewScreenshotIds', 'Format-CheckResult', 'Get-Median')
    $functions = $tree.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -in $helpers
        }, $true)
    foreach ($function in $functions) { . ([scriptblock]::Create($function.Extent.Text)) }
}

Describe 'The helpers of the A71 session of the instant commands' {
    It 'parses the script, and finds every helper' {
        $errors | Should -BeNullOrEmpty
        @($functions).Count | Should -Be $helpers.Count
    }

    It 'takes the steps also as one text separated by commas' {
        $names = @('install', 'screen', 'calls')

        Get-SessionSteps -Step @('screen,calls') -Names $names | Should -Be @('screen', 'calls')
        Get-SessionSteps -Step @('install', ' calls ') -Names $names | Should -Be @('install', 'calls')
        @(Get-SessionSteps -Step @() -Names $names).Count | Should -Be 0
    }

    It 'refuses a step that it does not know' {
        { Get-SessionSteps -Step @('screen,radio') -Names @('screen', 'radios') } | Should -Throw 'Unknown steps: radio.*'
    }

    It 'tells from the SIM state whether a SIM card is in the phone' {
        Test-SimAbsent -State 'ABSENT' | Should -BeTrue
        Test-SimAbsent -State 'ABSENT,ABSENT' | Should -BeTrue
        Test-SimAbsent -State '' | Should -BeTrue
        Test-SimAbsent -State 'LOADED,ABSENT' | Should -BeFalse
        Test-SimAbsent -State 'PIN_REQUIRED' | Should -BeFalse
        # A phone with a SIM card can say NOT_READY while its radio starts.
        Test-SimAbsent -State 'NOT_READY' | Should -BeFalse
    }

    It 'keeps the users'' IDs, types and flags, and never their names' {
        $users = @('Users:', '	UserInfo{0:Owner:c13} running', '	UserInfo{150:Secure Folder:10001030} running')
        $dump = @(
            'Users:'
            '  UserInfo{0:Owner:c13} serialNo=0 isPrimary=true'
            '    Type: android.os.usertype.full.SYSTEM'
            '  UserInfo{150:Secure Folder:10001030} serialNo=10'
            '    Type: com.samsung.android.knox.securefolder'
        )

        $summary = @(Get-UserSummary -Users $users -Dump $dump)

        $summary | Should -Be @('id=0 type=android.os.usertype.full.SYSTEM flags=c13', 'id=150 type=com.samsung.android.knox.securefolder flags=10001030')
        ($summary -join ' ') | Should -Not -Match 'Owner'
    }

    It 'finds the new screenshots and leaves the owner''s other images' {
        $rows = @(
            'Row: 0 _id=120, _display_name=Screenshot_20261120_101500_One UI Home.png'
            'Row: 1 _id=121, _display_name=IMG_20261120_101501.jpg'
        )

        @(Get-NewScreenshotIds -Rows $rows) | Should -Be @(120)
        @(Get-NewScreenshotIds -Rows @('No result found.')).Count | Should -Be 0
    }

    It 'writes a check''s result with the kind and the outcome of the command, and the median of some times' {
        $state = [ordered]@{ mode = 'listening'; command = '7'; kind = 'Radio'; outcome = 'done'; session = 'open' }

        Format-CheckResult -Name 'turn off Wi-Fi' -Owner 'y' -State $state | Should -BeExactly 'turn off Wi-Fi: owner=y kind=Radio outcome=done'
        Format-CheckResult -Name 'mute' -Owner 'n' -State $null | Should -BeExactly 'mute: owner=n kind=- outcome=-'
        Get-Median @(300, 100, 200) | Should -Be 200
        Get-Median @(400, 100, 300, 200) | Should -Be 200
        Get-Median @() | Should -Be 0
    }
}
```

Run: `scripts\test-scripts.ps1`
Expected: exit code 1 and "The script tests or checks failed.": in each shell the 7 new tests fail, because `scripts\a71-commands.ps1` does not exist.

- [ ] **Step 2: Add the hooks of the profiles and the snapshots**

In `app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt`, replace

```kotlin
import android.content.Intent
import android.os.Handler
import android.os.Looper
import dev.kestrel.app.AppGraph
```

with

```kotlin
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import dev.kestrel.app.AppGraph
```

Then replace

```kotlin
        if (command in SessionHooks.COMMANDS) return SessionHooks(graph, context).run(command, intent)
        val session = graph.screen.value ?: throw HookError("Kestrel's accessibility service is off")
```

with

```kotlin
        if (command in SessionHooks.COMMANDS) return SessionHooks(graph, context).run(command, intent)
        if (command == "PROFILES") return profiles()
        val session = graph.screen.value ?: throw HookError("Kestrel's accessibility service is off")
```

Then replace

```kotlin
            }
            else -> throw HookError("unknown command '$command'")
        }
```

with

```kotlin
            }
            // How many snapshots the screen layer built and how long they took, for the A71 session (design spec, 8.3).
            "SNAPSHOTS" -> "builds=${session.snapshotBuilds} millis=${session.snapshotMillis}"
            else -> throw HookError("unknown command '$command'")
        }
    }

    /**
     * The profiles that the launcher API lists, for the A71 session of sub-project 3 (research r21): each one's serial
     * number, its number of launcher apps and, from Android 15, its user type. It never names a user or an app.
     */
    private fun profiles(): String {
        val launcher = context.getSystemService(LauncherApps::class.java) ?: throw HookError("the launcher API is missing")
        val users = context.getSystemService(UserManager::class.java)
        return launcher.profiles.joinToString("; ") { user ->
            val apps = try {
                launcher.getActivityList(null, user).size.toString()
            } catch (e: SecurityException) {
                "refused"
            }
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                launcher.getLauncherUserInfo(user)?.userType
            } else {
                null
            }
            "user=${users?.getSerialNumberForUser(user)} apps=$apps type=${type ?: "unknown"}"
        }
```

In `scripts/hook.ps1`, replace

```powershell
#                                           failure of the microphone fails the check
# Spike S1's location check (scripts\spike-s1.ps1 runs these):
```

with

```powershell
#                                           failure of the microphone fails the check
# The A71 session of the instant commands (scripts\a71-commands.ps1 runs these):
#   profiles                                the profiles that the launcher API lists: each one's serial number, number
#                                           of apps and, from Android 15, type; never a name
#   snapshots                               how many snapshots the screen layer has built and how long they took in all
# Spike S1's location check (scripts\spike-s1.ps1 runs these):
```

Then replace

```powershell
        'voice-state', 'voice-clip', 'voice-call-commands', 'voice-choice', 'voice-synth', 'voice-record', 'voice-eval',
        'voice-counts', 'voice-task', 'voice-echo', 'location', 'spike-results', 'files', 'watch')][string]$Command,
    [string]$Key,
```

with

```powershell
        'voice-state', 'voice-clip', 'voice-call-commands', 'voice-choice', 'voice-synth', 'voice-record', 'voice-eval',
        'voice-counts', 'voice-task', 'voice-echo', 'location', 'spike-results', 'files', 'watch', 'profiles',
        'snapshots')][string]$Command,
    [string]$Key,
```

- [ ] **Step 3: Write the session's script**

Create `scripts/a71-commands.ps1`:

```powershell
# The session on the Galaxy A71 with the owner for the instant commands (plan 3C, design D16 of sub-project 3; design spec,
# sections 18.3 and 19.1). It takes the owner through every check of sub-project 3 that needs the A71 or the owner, in
# order. It asks before it reads the device's model and before each step. In each check the owner says a command to
# Kestrel, or does what the script asks, and then answers whether Kestrel did what the script describes; the script also
# records the kind and the outcome of Kestrel's latest instant command, never what Kestrel heard. The steps' estimates
# add up to 95 minutes; the session takes about 80 to 105 minutes, of which about 70 to 95 need the owner, because
# almost every step needs the owner's voice or hands, and only about 8 to 13 minutes (the install at the start, the
# transcriptions of the step timing and the restores at the end) run without the owner. The steps:
#   install        installs Kestrel's debug build and copies the speech models, turns Kestrel's accessibility service
#                  on, and records whether a SIM card is in the phone
#   screen         numbers, labels, the grid, tap, long press, scroll, "Which one?", bare labels and "require verbs"
#   swipes         the edge swipes, swipe forwards and backwards, and a slider
#   notifications  "show notifications", "expand", "collapse" and "dismiss" on a notification that the script posts
#   global         back, home, Quick Settings, recent apps, the previous app, the lock screen, the power menu, a
#                  screenshot (deleted afterwards), "open apps", and "open" with three of the owner's apps
#   profiles       the users and the profiles that the launcher API lists, as IDs, types and counts (research r21)
#   radios         Wi-Fi and Bluetooth off and on
#   sound          volumes, mute, silence, media play and pause, magnification and Circle to Search
#   text           dictation and editing with Samsung Keyboard, "show password" and "search for"
#   calls          a call to a number that the owner gives and confirms, the question "yes, call" in the phone app with
#                  1234 and with letters in the number field (answered "no"), the contacts permission at first use, calls
#                  to a contact of the owner's choice cancelled by voice and by a touch, "Which one?", and "copy this phone
#                  number"; it checks first, every time it runs, that no SIM card is in the phone (without one, Android
#                  refuses a call, research r19), and the owner confirms it; with a SIM card, or when the phone does not
#                  say that none is there, it skips the calls
#   timing         the snapshots that Kestrel builds while it listens on a busy screen, and the transcription time with the
#                  screen's labels as boost words (design spec, section 8.3)
#   recordings     the owner's recordings of the lines of corpus\commands.txt that are marked for the owner and that no
#                  earlier session recorded
# The results go into corpus\owner\commands-session-<time>.txt and the recordings into corpus\owner\clips; git ignores
# both. scripts\voice-eval.ps1 measures the recordings later on the emulator. The script puts back what it changes on the
# phone, also when a step fails or Ctrl+C stops it: Kestrel's mode Off, the accessibility settings (which also ends a
# magnification), the setting "Stay awake", Wi-Fi and Bluetooth, the volumes of the call, ring, music, alarm and
# notification streams, the sound mode, the keyboard, Kestrel's contacts permission and the screenshots that it took;
# the clipboard stays as the owner leaves it. It refuses every device but the A71 and kestrel-api37. -DryRun runs the
# session on kestrel-api37 without the owner: it answers every question itself, sends what the owner would say through
# the voice hook, skips what needs the owner's hands otherwise, and records two silent clips into a folder of its own
# that it deletes. -Step runs only the named steps, also as one text separated by commas. It needs PowerShell 7.3 or
# newer, as scripts\hook.ps1 does. After Ctrl+C, it ends with exit code 1.
# Example: scripts\a71-commands.ps1 -Serial R58N12345AB
#Requires -Version 7.3
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [switch]$DryRun,
    [string[]]$Step
)
$ErrorActionPreference = 'Stop'

# The steps that -Step names, split at commas, as pwsh -File passes '-Step text,calls' as one text. Throws for a name that
# is no step.
function Get-SessionSteps([string[]]$Step, [string[]]$Names) {
    $chosen = @($Step | ForEach-Object { "$_" -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    $unknown = @($chosen | Where-Object { $_ -notin $Names })
    if ($unknown) { throw "Unknown steps: $($unknown -join ', '). The steps are: $($Names -join ', ')." }
    $chosen
}

# Whether the answer of 'getprop gsm.sim.state' says that no SIM card is in the phone, in each of its slots. A phone
# with a SIM card can say NOT_READY or UNKNOWN while its radio starts or is off, so only ABSENT and an empty slot count.
function Test-SimAbsent([string]$State) {
    $slots = @("$State".Trim() -split ',' | ForEach-Object { $_.Trim() })
    @($slots | Where-Object { $_ -notin @('', 'ABSENT') }).Count -eq 0
}

# The users of 'pm list users', with their types from 'dumpsys user', as 'id=10 type=android.os.usertype.profile.MANAGED
# flags=1030'. A user's name, which can be the owner's, is left out.
function Get-UserSummary([string[]]$Users, [string[]]$Dump) {
    $types = @{}
    $current = $null
    foreach ($line in $Dump) {
        if ($line -match 'UserInfo\{(\d+):') { $current = $Matches[1] }
        if ($null -ne $current -and $line -match '^\s*Type:\s*(\S+)') {
            $types[$current] = $Matches[1]
            $current = $null
        }
    }
    foreach ($line in $Users) {
        if ($line -match 'UserInfo\{(\d+):.*:([0-9a-fA-F]+)\}') {
            $type = if ($types.ContainsKey($Matches[1])) { $types[$Matches[1]] } else { 'unknown' }
            "id=$($Matches[1]) type=$type flags=$($Matches[2])"
        }
    }
}

# The IDs of the screenshots in the answer of 'content query ... --projection _id:_display_name', whose lines read
# 'Row: 0 _id=123, _display_name=Screenshot_20261120_101500.png'. Other images are the owner's, and stay.
function Get-NewScreenshotIds([string[]]$Rows) {
    foreach ($line in $Rows) {
        if ($line -match '\b_id=(\d+), _display_name=Screenshot') { [long]$Matches[1] }
    }
}

# One check's line of the results: what the owner said or did, the owner's answer, and the kind and the outcome of
# Kestrel's latest instant command from the voice-state hook. Never what Kestrel heard.
function Format-CheckResult([string]$Name, [string]$Owner, $State) {
    $command = if ($State) { "kind=$($State['kind']) outcome=$($State['outcome'])" } else { 'kind=- outcome=-' }
    "${Name}: owner=$Owner $command"
}

# The median of some numbers, or 0 without any.
function Get-Median([double[]]$Values) {
    if (-not $Values -or $Values.Count -eq 0) { return 0 }
    $sorted = @($Values | Sort-Object)
    $sorted[[int][math]::Floor(($sorted.Count - 1) / 2)]
}

$stepNames = @('install', 'screen', 'swipes', 'notifications', 'global', 'profiles', 'radios', 'sound', 'text', 'calls', 'timing', 'recordings')
$Step = Get-SessionSteps -Step $Step -Names $stepNames
# Before any command reaches a device: a dry run answers the owner's questions itself, so it must never reach a phone.
if ($DryRun -and $Serial -notlike 'emulator-*') { throw 'A dry run runs only on the emulator kestrel-api37.' }

. "$PSScriptRoot\lib\Kestrel.ps1"
. "$PSScriptRoot\lib\Corpus.ps1"
. "$PSScriptRoot\lib\Session.ps1"
Use-KestrelToolchain

$owner = Join-Path $PSScriptRoot '..\corpus\owner'
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'
$resultsFile = if ($DryRun) { Join-Path $owner "commands-dry-run-$stamp.txt" } else { Join-Path $owner "commands-session-$stamp.txt" }
$clipsFolder = if ($DryRun) { Join-Path $owner "commands-dry-run-clips-$stamp" } else { Join-Path $owner 'clips' }
$corpusFile = Join-Path $PSScriptRoot '..\corpus\commands.txt'
$remote = Get-EvalFolder
$service = 'dev.kestrel/dev.kestrel.app.KestrelAccessibilityService'
$streams = @(0, 2, 3, 4, 5)
$simAbsent = $false
$gradleUsed = $false
$prepared = $false

# Asks the owner to do something, and returns 'go' after Enter or 'skip' after s. q stops the session. In a dry run it
# answers $DryRunAnswer itself.
function Request-Owner([string]$Prompt, [string]$DryRunAnswer = 'skip') {
    if ($DryRun) {
        Write-Host "[dry run] $Prompt"
        return $DryRunAnswer
    }
    $answer = (Read-Host $Prompt).Trim().ToLowerInvariant()
    if ($answer -eq 'q') { throw 'The owner stopped the session.' }
    if ($answer -in 's', 'skip') { 'skip' } else { 'go' }
}

# Asks the owner a question, and returns the answer as typed. In a dry run it answers $DryRunAnswer itself.
function Read-OwnerAnswer([string]$Prompt, [string]$DryRunAnswer) {
    if ($DryRun) {
        Write-Host "[dry run] $Prompt"
        return $DryRunAnswer
    }
    (Read-Host $Prompt).Trim()
}

if ((Request-Owner "The session first reads the model of the device $Serial, to check that it is the Galaxy A71. Press Enter to go ahead, or q to stop." 'go') -ne 'go') {
    exit 1
}
# Refuses every device but the A71 and kestrel-api37; the daily phone would need -AllowDailyPhone, which this script
# never passes.
Assert-TestDevice -Serial $Serial
$isEmulator = $KestrelTestDevices[$Serial].Pattern -eq 'kestrel-api37'
if ($DryRun -and -not $isEmulator) { throw 'A dry run runs only on the emulator kestrel-api37.' }
$adb = Get-Adb

function Add-Result([string]$StepName, [string]$Text) {
    Add-SessionResult -Path $resultsFile -Step $StepName -Text $Text
    Write-Host "${StepName}: $Text"
}

# Runs a command on the device and fails when it fails; it runs only on the device that Assert-TestDevice accepted.
function Invoke-Checked([string]$Command) { Invoke-SettingsCommand -Serial $Serial -Command $Command }

# Reads one line of the device's answer, without spaces around it.
function Read-DeviceValue([string]$Command) { ((Invoke-Checked $Command) -join "`n").Trim() }

# Runs scripts\hook.ps1 with a command and its parameters, which a hashtable names, and returns the answer as one text.
function Invoke-Hook([string]$Command, [hashtable]$With = @{}) {
    (& "$PSScriptRoot\hook.ps1" -Serial $Serial -Command $Command @With) -join "`n"
}

# Kestrel's state from the voice-state hook, or $null while Kestrel does not answer.
function Get-VoiceState {
    try { ConvertFrom-VoiceState (Invoke-Hook voice-state) } catch { $null }
}

# Waits until $Condition is true for Kestrel's state, which it gets as its argument. Returns the seconds that it waited,
# or -1 after the timeout.
function Wait-State([scriptblock]$Condition, [double]$TimeoutSeconds) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $state = Get-VoiceState
        if ($state -and (& $Condition $state)) { return [math]::Round($watch.Elapsed.TotalSeconds, 1) }
        Start-Sleep -Milliseconds 250
    } while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    -1
}

# Waits until Kestrel is in the mode, with its voice session open, or closed for 'off'.
function Wait-Mode([string]$Mode, [double]$TimeoutSeconds) {
    $session = if ($Mode -eq 'off') { 'closed' } else { 'open' }
    Wait-State ({ param($State) $State.mode -eq $Mode -and $State.session -eq $session }.GetNewClosure()) $TimeoutSeconds
}

# Turns Kestrel off, which it also stores, and waits until its voice session has closed.
function Stop-Kestrel {
    Invoke-Hook voice @{ Send = 'off' } | Out-Null
    if ((Wait-Mode off 30) -lt 0) { throw 'Kestrel did not turn off within 30 s.' }
}

# Turns Kestrel on with its own microphone, from the front, where Android lets it start its microphone service, and goes
# back to the home screen. Loading the models takes up to a minute on the A71.
function Start-Kestrel {
    Invoke-Hook voice-clip @{ Clear = $true } | Out-Null
    $state = Get-VoiceState
    if ($state -and $state.mode -eq 'paused') { Invoke-Hook voice @{ Send = 'resume' } | Out-Null }
    if (-not $state -or $state.mode -eq 'off') {
        Invoke-Checked 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
        Invoke-Hook voice @{ Send = 'toggle' } | Out-Null
        # Kestrel's own restore after its process ended may have started listening just before the toggle, which then
        # paused it.
        Start-Sleep -Seconds 1
        $after = Get-VoiceState
        if ($after -and $after.mode -eq 'paused') { Invoke-Hook voice @{ Send = 'resume' } | Out-Null }
    }
    if ((Wait-Mode listening 120) -lt 0) { throw 'Kestrel did not start listening within 120 s.' }
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
}

# What the script has still to put back. Each restore enters the list before its change, and leaves it when it worked.
# $ByHand says how to put it back by hand, for the warning at the end.
$pending = [System.Collections.Generic.List[object]]::new()

function Register-Restore([string]$What, [string]$ByHand, $Data, [scriptblock]$Action) {
    $restore = [pscustomobject]@{ What = $What; ByHand = $ByHand; Data = $Data; Action = $Action; Reason = 'the session ended before it' }
    $pending.Add($restore)
    $restore
}

# Runs a restore that is still pending, and keeps it pending, with the reason, when it fails.
function Complete-Restore($Restore) {
    if (-not $pending.Contains($Restore)) { return }
    try {
        & $Restore.Action $Restore.Data | Out-Null
        $pending.Remove($Restore) | Out-Null
    } catch {
        $Restore.Reason = $_.Exception.Message
    }
}

# Runs the restores in order, each in the finally block of the one before, so that a stop such as Ctrl+C during one of
# them still runs the others.
function Invoke-Restore([object[]]$Restores, [int]$Index = 0) {
    if ($Index -ge $Restores.Count) { return }
    try {
        Complete-Restore $Restores[$Index]
    } finally {
        Invoke-Restore $Restores ($Index + 1)
    }
}

# Before the first step that runs: what every step may change is saved, and the screen stays on while the phone is
# plugged in. Registered first, so that it comes back last; Kestrel's mode Off comes back after the accessibility
# settings, so that Kestrel is still on to turn off.
function Initialize-Session {
    if ($script:prepared) { return }
    $script:prepared = $true
    # Last of all, because revoking a permission ends Kestrel's process.
    Register-Restore "Kestrel's contacts permission" 'Settings > Apps > Kestrel > Permissions > Contacts' (Test-ContactsGranted) {
        param($Data)
        Assert-TestDevice -Serial $Serial
        $now = Test-ContactsGranted
        if ($now -and -not $Data) { Invoke-Checked 'pm revoke dev.kestrel android.permission.READ_CONTACTS' | Out-Null }
        if ($Data -and -not $now) { Invoke-Checked 'pm grant dev.kestrel android.permission.READ_CONTACTS' | Out-Null }
    } | Out-Null
    Register-Restore "Kestrel's mode Off" "Run: scripts\hook.ps1 -Serial $Serial voice -Send off" $null { Stop-Kestrel } | Out-Null
    $accessibility = Get-AccessibilitySettings -Serial $Serial
    Register-Restore 'the accessibility settings' 'Put back the accessibility services, the shortcut and the button in Settings > Accessibility.' $accessibility {
        param($Data)
        Assert-TestDevice -Serial $Serial
        if (-not (Restore-AccessibilitySettings -Serial $Serial -Saved $Data)) { throw 'see the warning above' }
    } | Out-Null
    $keyboard = Read-DeviceValue 'settings get secure default_input_method'
    Register-Restore 'the keyboard' 'Settings > General management > Keyboard list and default' $keyboard {
        param($Data)
        Assert-TestDevice -Serial $Serial
        if ($Data -notin 'null', '' -and (Read-DeviceValue 'settings get secure default_input_method') -ne $Data) {
            Invoke-Checked "ime set $Data" | Out-Null
        }
    } | Out-Null
    $radios = [ordered]@{ Wifi = Read-DeviceValue 'settings get global wifi_on'; Bluetooth = Read-DeviceValue 'settings get global bluetooth_on' }
    Register-Restore 'Wi-Fi and Bluetooth' 'Turn Wi-Fi and Bluetooth back as they were in Quick Settings.' $radios {
        param($Data)
        Assert-TestDevice -Serial $Serial
        $wifi = if ($Data.Wifi -eq '0') { 'disabled' } else { 'enabled' }
        Invoke-Checked "cmd wifi set-wifi-enabled $wifi" | Out-Null
        $bluetooth = if ($Data.Bluetooth -eq '0') { 'disable' } else { 'enable' }
        Invoke-Checked "cmd bluetooth_manager $bluetooth" | Out-Null
    } | Out-Null
    $sound = [ordered]@{ Ringer = Read-DeviceValue 'settings get global mode_ringer'; Volumes = [ordered]@{} }
    foreach ($stream in $streams) {
        $sound.Volumes[$stream] = Get-StreamVolume -Lines (Invoke-Checked "cmd media_session volume --stream $stream --get")
    }
    # The sound mode first and the volumes second, so that the volumes come back first: on a phone, setting the ring
    # volume also sets the sound mode, so the sound mode's check must come last. The two restores are separate, so that
    # a failure of one does not stop the other.
    Register-Restore 'the sound mode' 'Set the sound mode back in Quick Settings: sound, vibrate or mute.' $sound.Ringer {
        param($Data)
        Assert-TestDevice -Serial $Serial
        $modes = @{ '0' = 'SILENT'; '1' = 'VIBRATE'; '2' = 'NORMAL' }
        if ((Read-DeviceValue 'settings get global mode_ringer') -eq $Data -or -not $modes.Contains($Data)) { return }
        # Android 13 has no shell command for the sound mode; there the command does nothing, and the check below warns.
        Invoke-Checked "cmd audio set-ringer-mode $($modes[$Data])" | Out-Null
        if ((Read-DeviceValue 'settings get global mode_ringer') -ne $Data) { throw 'Android did not take the sound mode from the shell' }
    } | Out-Null
    Register-Restore 'the volumes' 'Set the volumes back in Settings > Sounds and vibration > Volume.' $sound.Volumes {
        param($Data)
        Assert-TestDevice -Serial $Serial
        foreach ($stream in $Data.Keys) { Invoke-Checked "cmd media_session volume --stream $stream --set $($Data[$stream])" | Out-Null }
    } | Out-Null
    Register-Restore "the clips in Kestrel's folder files/eval" "Run: & '$adb' -s $Serial shell 'rm -f $remote/*.wav'" $null {
        Assert-TestDevice -Serial $Serial
        Invoke-Checked "rm -f $remote/*.wav" | Out-Null
    } | Out-Null
    $stayOn = Read-DeviceValue 'settings get global stay_on_while_plugged_in'
    Register-Restore 'the setting "Stay awake"' "Run: & '$adb' -s $Serial shell settings put global stay_on_while_plugged_in $stayOn" $stayOn {
        param($Data)
        Assert-TestDevice -Serial $Serial
        $command = if ($Data -in 'null', '') { 'settings delete global stay_on_while_plugged_in' } else { "settings put global stay_on_while_plugged_in $Data" }
        Invoke-Checked $command | Out-Null
    } | Out-Null
    Invoke-Checked 'svc power stayon true' | Out-Null
    # Kestrel's accessibility service, next to the owner's other services.
    $enabled = if ($accessibility.Contains('enabled_accessibility_services')) { "$($accessibility['enabled_accessibility_services'])" } else { 'null' }
    $services = @(@($enabled -split ':') | Where-Object { $_ -and $_ -ne 'null' -and $_ -ne $service }) + $service
    if (-not (Set-SecureSetting -Serial $Serial -Name 'enabled_accessibility_services' -Value ($services -join ':'))) { throw 'Could not turn on Kestrel''s accessibility service.' }
    if (-not (Set-SecureSetting -Serial $Serial -Name 'accessibility_enabled' -Value '1')) { throw 'Could not turn on accessibility.' }
    Request-Owner 'The phone''s screen now stays on while the phone is plugged in, and Kestrel''s accessibility service is on. Keep the phone unlocked during the session, except when a step asks you to lock it. Press Enter to go on.' 'go' | Out-Null
}

# Sends what the owner would say, as if Kestrel had heard it, with a pause after it.
function Send-Heard([string]$Text) {
    Invoke-Hook voice @{ Send = 'heard'; Text = $Text } | Out-Null
    Start-Sleep -Seconds 2
}

# One check: the owner prepares what $Prepare says, if anything, says $Say to Kestrel, and answers whether $Look
# happened. The result holds $Name (or else $Say), the owner's answer and the kind and the outcome of Kestrel's latest
# instant command. A dry run sends $Heard (by default $Say) through the voice hook, then $Answer, if any, as the answer to
# Kestrel's question, and answers itself.
function Invoke-Check([string]$StepName, [string]$Say, [string]$Look, [string]$Prepare = '', [string]$Heard = '', [string]$Name = '', [string]$Answer = '') {
    # What the results call the check: $Name when what the owner says is private, such as a phone number.
    $label = if ($Name) { $Name } else { $Say }
    if ($Prepare -and (Request-Owner "$Prepare Press Enter when it is ready, or s to skip this check." 'go') -eq 'skip') {
        Add-Result $StepName "${label}: skipped"
        return
    }
    $before = Get-VoiceState
    $number = if ($before) { [int]$before['command'] } else { 0 }
    if ($DryRun) {
        Send-Heard $(if ($Heard) { $Heard } else { $Say })
        if ($Answer) {
            Wait-State ({ param($State) [int]$State['command'] -gt $number }.GetNewClosure()) 15 | Out-Null
            # Kestrel says its question before it listens for the answer.
            Start-Sleep -Seconds 3
            Send-Heard $Answer
        }
    } elseif ((Request-Owner "Say: $Say    (Enter once Kestrel has reacted, s to skip)") -eq 'skip') {
        Add-Result $StepName "${label}: skipped"
        return
    }
    Wait-State ({ param($State) [int]$State['command'] -gt $number -and $State['outcome'] -ne 'running' }.GetNewClosure()) 15 | Out-Null
    $answer = Read-OwnerAnswer "$Look Type y or n." 'dry'
    Add-Result $StepName (Format-CheckResult -Name $label -Owner $answer -State (Get-VoiceState))
}

# Whether Kestrel may read the contacts.
function Test-ContactsGranted {
    @(Get-GrantedPermissions -Dump (Invoke-Checked 'dumpsys package dev.kestrel') -Permissions @('android.permission.READ_CONTACTS')).Count -gt 0
}

# In a dry run, wakes the emulator's screen and dismisses its keyguard, as the owner would unlock the phone.
function Wait-Unlock {
    Request-Owner 'Unlock the phone as you always do, then press Enter.' 'go' | Out-Null
    if ($DryRun) {
        Invoke-Checked 'input keyevent KEYCODE_WAKEUP' | Out-Null
        Invoke-Checked 'wm dismiss-keyguard' | Out-Null
    }
}

# The largest row ID of the phone's images, so that the screenshot check deletes only newer screenshots.
function Get-LargestImageId { Get-LargestRowId -Rows (Invoke-Checked 'content query --uri content://media/external/images/media --projection _id') }

function Invoke-InstallStep {
    # On Android 13 an update ends Kestrel's process with the reason of the owner's stop, so Kestrel would stay off after it.
    try { Stop-Kestrel } catch { Write-Host 'Kestrel did not answer, so it is probably not installed yet.' }
    $script:gradleUsed = $true
    & "$PSScriptRoot\install.ps1" -Serial $Serial
    if ($LASTEXITCODE -ne 0) { throw 'scripts\install.ps1 failed.' }
    & "$PSScriptRoot\push-models.ps1" -Serial $Serial
    Add-Result install 'Kestrel installed, and the speech models copied'
    # Only recorded here; the step calls checks again right before its first call.
    $absent = Test-SimAbsent -State (Read-DeviceValue 'getprop gsm.sim.state')
    Add-Result install "SIM card: $(if ($absent) { 'none' } else { 'present, or the phone does not say that none is there' })"
    Start-Kestrel
    Add-Result install 'Kestrel listens'
}

function Invoke-ScreenStep {
    Start-Kestrel
    Invoke-Checked 'am start -a android.settings.SETTINGS' | Out-Null
    Invoke-Check screen 'show numbers' 'Did a number appear on each button and row of Settings?'
    Invoke-Check screen 'tap 3' 'Did Kestrel tap the element with the number 3?' -Prepare 'Go back to the first page of Settings, where the numbers show.'
    Invoke-Check screen 'go back' 'Did Settings go back a page?'
    Invoke-Check screen 'hide numbers' 'Did the numbers go?'
    Invoke-Check screen 'show labels' 'Did a name appear next to each button?'
    Invoke-Check screen 'hide labels' 'Did the names go?'
    Invoke-Check screen 'show grid' 'Did numbered squares cover the screen?'
    Invoke-Check screen 'more squares' 'Did the squares get smaller?'
    Invoke-Check screen 'fewer squares' 'Did the squares get bigger?'
    Invoke-Check screen 'hide grid' 'Did the squares go?'
    Invoke-Check screen 'scroll down' 'Did the list of Settings move down?'
    Invoke-Check screen 'scroll to top' 'Is the list back at its top?'
    Invoke-Check screen 'tap Display' 'Did the page Display open?'
    Invoke-Check screen 'go back' 'Is the first page of Settings back?'
    Invoke-Check screen 'Display' 'Did the page Display open, from the name alone?'
    Invoke-Check screen 'go home' 'Is the home screen in front?'
    Invoke-Check screen "long press and the name of an app under its icon on the home screen" 'Did the app''s menu open?' -Heard 'long press Phone'
    Invoke-Check screen 'go back' 'Did the menu close?'
    Invoke-Check screen "tap and a name that two buttons on the screen share" 'Did Kestrel ask "Which one?" and show numbers on just those buttons?' -Prepare 'Open a screen where two buttons have the same name, such as two "Install" or two "Delete" buttons.' -Heard 'tap Delete'
    Invoke-Check screen 'the number of the button that you want' 'Did Kestrel tap that one, and did the numbers go?' -Heard '1'
    Invoke-Checked "am start -n dev.kestrel/.app.MainActivity --es dev.kestrel.app.extra.PAGE COMMANDS" | Out-Null
    if ((Request-Owner 'Kestrel''s page "Instant commands" is open. Switch "Require verbs" on, then press Enter.' 'skip') -eq 'go') {
        Invoke-Checked 'am start -a android.settings.SETTINGS' | Out-Null
        Invoke-Check screen 'Display' 'Did nothing happen, because a name alone no longer taps?'
        Invoke-Checked "am start -n dev.kestrel/.app.MainActivity --es dev.kestrel.app.extra.PAGE COMMANDS" | Out-Null
        Request-Owner 'Switch "Require verbs" off again, then press Enter.' 'go' | Out-Null
    }
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
}

function Invoke-SwipeStep {
    Start-Kestrel
    Invoke-Checked 'am start -a android.settings.SETTINGS' | Out-Null
    Invoke-Check swipes 'tap Display' 'Did the page Display open?'
    Invoke-Check swipes 'swipe from the left edge' 'Did Settings go back, as with the back gesture?'
    Invoke-Check swipes 'swipe from the bottom edge' 'Is the home screen in front, as with the home gesture?'
    Invoke-Check swipes 'swipe forwards' 'Did the home screen show its next page?'
    Invoke-Check swipes 'swipe backwards' 'Did the home screen show its first page again?'
    Invoke-Checked 'am start -a android.settings.DISPLAY_SETTINGS' | Out-Null
    Invoke-Check swipes 'show numbers' 'Did a number appear on the brightness slider?'
    Invoke-Check swipes 'increment and the brightness slider''s number' 'Did the brightness go up one step?' -Heard 'increment 1'
    Invoke-Check swipes 'decrement and the same number' 'Did the brightness go back down?' -Heard 'decrement 1'
    Invoke-Check swipes 'hide numbers' 'Did the numbers go?'
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
}

function Invoke-NotificationStep {
    Start-Kestrel
    # A notification of the shell, with a long text that the shade can expand; it holds no private text.
    Invoke-Checked "cmd notification post -S bigtext -t 'Kestrel check' kestrel-check 'The first line of a long text. A second line that the expanded notification shows. A third line.'" | Out-Null
    Invoke-Check notifications 'show notifications' 'Did the notification shade open with the notification "Kestrel check"?'
    Invoke-Check notifications 'expand Kestrel check' 'Did the notification show its whole text?'
    Invoke-Check notifications 'collapse Kestrel check' 'Did it show one line again?'
    Invoke-Check notifications 'dismiss Kestrel check' 'Did the notification go?'
    Invoke-Check notifications 'go home' 'Did the shade close?'
    Request-Owner 'If the notification "Kestrel check" is still there, swipe it away, then press Enter.' 'go' | Out-Null
}

function Invoke-GlobalStep {
    Start-Kestrel
    Invoke-Checked 'am start -a android.settings.SETTINGS' | Out-Null
    Invoke-Check global 'tap Display' 'Did the page Display open?'
    Invoke-Check global 'go back' 'Did Settings go back a page?'
    Invoke-Check global 'go home' 'Is the home screen in front?'
    Invoke-Check global 'show quick settings' 'Did Quick Settings open?'
    Invoke-Check global 'home' 'Is the home screen in front again?'
    Invoke-Check global 'show recent apps' 'Did the recent apps show?'
    Invoke-Check global 'go home' 'Is the home screen in front?'
    Invoke-Check global 'show previous app' 'Did the app that you used before the current one come back?' -Prepare 'Open two of your apps, one after the other, and stay in the second.'
    Invoke-Check global 'lock screen' 'Did the screen turn off and lock?'
    Wait-Unlock
    Add-Result global "after the unlock, Kestrel's mode is $((Get-VoiceState).mode)"
    Invoke-Check global 'turn off device' 'Did the power menu open?'
    Invoke-Check global 'go back' 'Did the power menu close?'
    $images = Get-LargestImageId
    Invoke-Check global 'take a screenshot' 'Did the phone take a screenshot, without Kestrel''s bar on it?'
    Start-Sleep -Seconds 3
    $new = @(Get-NewScreenshotIds -Rows (Invoke-Checked "content query --uri content://media/external/images/media --projection _id:_display_name --where _id\>$images"))
    foreach ($id in $new) { Invoke-Checked "content delete --uri content://media/external/images/media --where _id=$id" | Out-Null }
    Add-Result global "deleted $($new.Count) new screenshots"
    Invoke-Check global 'open apps' 'Did the list of all apps open?'
    Invoke-Check global 'go home' 'Is the home screen in front?'
    foreach ($try in 1..3) {
        Invoke-Check global "open and the name of one of your apps (app $try of 3)" 'Did that app open?' -Heard 'open Settings'
        Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    }
}

function Invoke-ProfileStep {
    $answer = Read-OwnerAnswer 'Is Secure Folder on, on this phone? Type y or n (it stays as it is).' 'n'
    Add-Result profiles "Secure Folder on: $answer"
    foreach ($user in Get-UserSummary -Users (Invoke-Checked 'pm list users') -Dump (Invoke-Checked 'dumpsys user')) { Add-Result profiles "user $user" }
    # The launcher API's profiles, as Kestrel sees them: IDs, numbers of apps and, from Android 15, types.
    Add-Result profiles "launcher API: $(Invoke-Hook profiles)"
}

function Invoke-RadioStep {
    # Turning Wi-Fi off would cut a connection by wireless debugging, and the session with it.
    if ($Serial -match ':|\._adb-tls-') {
        Add-Result radios 'skipped: the phone is connected by wireless debugging; connect it by USB for this step'
        return
    }
    Start-Kestrel
    foreach ($check in @(
            @{ Say = 'turn off Wi-Fi'; Setting = 'wifi_on'; On = $false }
            @{ Say = 'turn on Wi-Fi'; Setting = 'wifi_on'; On = $true }
            @{ Say = 'turn off Bluetooth'; Setting = 'bluetooth_on'; On = $false }
            @{ Say = 'turn on Bluetooth'; Setting = 'bluetooth_on'; On = $true })) {
        Invoke-Check radios $check.Say 'Did Kestrel open the panel, switch the radio and close the panel again?'
        Start-Sleep -Seconds 2
        $now = (Read-DeviceValue "settings get global $($check.Setting)") -ne '0'
        Add-Result radios "$($check.Say): Android says $(if ($now -eq $check.On) { 'done' } else { 'not done' })"
    }
}

function Invoke-SoundStep {
    Start-Kestrel
    foreach ($check in @(
            @{ Say = 'volume up'; Stream = 3 }
            @{ Say = 'volume down'; Stream = 3 }
            @{ Say = 'turn the ring volume up'; Stream = 2 }
            @{ Say = 'turn the ring volume down'; Stream = 2 }
            @{ Say = 'turn the alarm volume up'; Stream = 4 }
            @{ Say = 'turn the alarm volume down'; Stream = 4 }
            @{ Say = 'turn the call volume up'; Stream = 0 }
            @{ Say = 'turn the call volume down'; Stream = 0 })) {
        $before = Get-StreamVolume -Lines (Invoke-Checked "cmd media_session volume --stream $($check.Stream) --get")
        Invoke-Check sound $check.Say 'Did the volume panel show the change?'
        $after = Get-StreamVolume -Lines (Invoke-Checked "cmd media_session volume --stream $($check.Stream) --get")
        Add-Result sound "$($check.Say): stream $($check.Stream) went from $before to $after"
    }
    Invoke-Check sound 'mute' 'Is the media volume muted?'
    Invoke-Check sound 'unmute' 'Is the media volume back?'
    Invoke-Check sound 'mute the alarm' 'Did Kestrel say that Android doesn''t let it mute the alarm volume?'
    Invoke-Check sound 'silence' 'Is the phone on vibrate?'
    Invoke-Check sound 'unmute the ringer' 'Is the sound back on?'
    Add-Result sound "the ringer mode is $(Read-DeviceValue 'settings get global mode_ringer') (2 is sound)"
    Invoke-Check sound 'media pause' 'Did the music stop?' -Prepare 'Start music in one of your music apps, then go to the home screen.'
    Invoke-Check sound 'media play' 'Did the music play again?'
    Invoke-Check sound 'media pause' 'Did the music stop?'
    Invoke-Check sound 'zoom in' 'Is the screen magnified?'
    Invoke-Check sound 'pan left' 'Did the magnified area move to the left?'
    Invoke-Check sound 'scroll right' 'Did it move to the right?'
    Invoke-Check sound 'zoom out' 'Is the screen back to normal?'
    Invoke-Check sound 'magnify' 'Is the screen magnified again?'
    Invoke-Check sound 'stop magnification' 'Is the screen back to normal?'
    Invoke-Check sound 'circle to search' 'Did Kestrel say that Circle to Search isn''t available on this phone?'
}

function Invoke-TextStep {
    Start-Kestrel
    $prepare = 'Open Samsung Notes, start a new note, and leave the cursor in it, with Samsung Keyboard showing.'
    Invoke-Check text 'hello comma how are you question mark' 'Does the note read "Hello, how are you?"?' -Prepare $prepare
    Invoke-Check text 'new line' 'Is the cursor on a new line?'
    Invoke-Check text 'capital p one two three' 'Did "P123" follow?'
    Invoke-Check text 'select all' 'Is all the text selected?'
    Invoke-Check text 'copy' 'Did the phone copy it?'
    Invoke-Check text 'go to the end' 'Is the cursor at the end?'
    Invoke-Check text 'paste' 'Is the text there twice now?'
    Invoke-Check text 'delete three words' 'Did the last three words go?'
    Invoke-Check text 'undo' 'Are they back?'
    Invoke-Check text 'redo' 'Did they go again?'
    Invoke-Check text 'select hello' 'Is "Hello" selected?'
    Invoke-Check text 'uppercase hello' 'Does it read "HELLO"?'
    Invoke-Check text 'hide keyboard' 'Did the keyboard go?'
    Invoke-Check text 'show keyboard' 'Is the keyboard back?'
    Invoke-Check text 'show password' 'Can you read the test characters in the password field now?' -Prepare 'Open the sign-in screen of one of your apps, and type only a few test characters into its password field, never your password.'
    Invoke-Check text 'search for weather tomorrow' 'Did the browser search for "weather tomorrow"?' -Prepare 'Open your browser on its start page.'
    Request-Owner 'Delete the test note and the test characters if you want, then press Enter.' 'go' | Out-Null
}

function Invoke-CallStep {
    # The SIM check runs here, right before the first call, every time the step runs: also when -Step leaves out the step
    # install, when the owner skipped it, or when it failed before its check (research r19; design D16, step 1).
    $script:simAbsent = $DryRun -or (Test-SimAbsent -State (Read-DeviceValue 'getprop gsm.sim.state'))
    if (-not $script:simAbsent) {
        Add-Result calls 'skipped: a SIM card is in the phone, or the phone does not say that none is there'
        return
    }
    if ((Read-OwnerAnswer 'Kestrel will press Call in this step. Check that no SIM card is in the phone, then type y to go on, or anything else to skip the calls.' 'y') -ne 'y') {
        Add-Result calls 'skipped by the owner'
        return
    }
    # Kestrel presses Call only for a number that the owner gives and confirms, never for a made-up one that could be a
    # stranger's; the dry run calls the emulator's made-up number.
    $number = (Read-OwnerAnswer 'Type a phone number that is fine to call, such as another phone of yours, with digits only.' '0641234567') -replace '[\s-]', ''
    if ($number -notmatch '^\+?\d{5,15}$' -or (Read-OwnerAnswer "You will say ""call $number"", and Kestrel will press Call for it. Type y if that is fine." 'y') -ne 'y') {
        Add-Result calls 'call by number: skipped, no number confirmed'
        $number = $null
    }
    Start-Kestrel
    $callLog = Get-LargestRowId -Rows (Invoke-Checked 'content query --uri content://call_log/calls --projection _id')
    if ($number) {
        Invoke-Check calls "call $number" 'Did the phone app open with the number, did Kestrel say it, count down 4 s on its bar and press Call (which Android refuses without a SIM card)?' -Name 'call the confirmed number'
    }
    if ($DryRun) {
        # The emulator places the call; its console ends it, and the call's row goes.
        Start-Sleep -Seconds 3
        & $adb -s $Serial emu gsm cancel $number | Out-Null
        Start-Sleep -Seconds 2
        Invoke-Checked "content delete --uri content://call_log/calls --where _id\>$callLog" | Out-Null
    }
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    # The question "yes, call" in One UI's phone app (design spec, section 8.7): with a short number, which Kestrel never
    # calls itself, and then with letters in the number field, "tap call" must ask, and the owner answers "no". Never an
    # emergency number on a phone, because emergency calls go out without a SIM card, and never "yes, call"; this runs
    # only after the SIM check above found none.
    if ((Read-OwnerAnswer 'Next comes the check that Kestrel asks before it presses the phone app''s Call button for a number that it would not call: the phone app opens with 1234, you say "tap call", and you answer "no". Type y to go on, or anything else to skip it.' 'y') -eq 'y') {
        Invoke-Check calls 'call 1234' 'Did the phone app open with 1234, and did Kestrel say that you must tap Call yourself?'
        Invoke-Check calls 'tap call, and "no" when Kestrel asks' 'Did Kestrel ask you to say "yes, call", and after your "no", does the phone app still show 1234, without a call?' -Heard 'tap call' -Answer 'no'
        $field = Read-OwnerAnswer 'Say "show numbers", then type the number that Kestrel shows on the phone app''s number field (the one with 1234), or s to skip this.' 'skip'
        try {
            if ($field -match '^\d+$') {
                # Letters only, never the digits of an emergency number.
                Invoke-Hook type @{ Number = [int]$field; Text = 'abc' } | Out-Null
                Invoke-Check calls 'tap call, and "no" when Kestrel asks' 'Does the number field show "abc", did Kestrel ask you to say "yes, call", and after your "no", no call?' -Heard 'tap call' -Answer 'no'
            }
        } catch {
            if ($_.Exception.Message -eq 'The owner stopped the session.') { throw }
            # The hooks' errors name no text of the screen.
            Add-Result calls "letters in the number field: $($_.Exception.Message)"
        } finally {
            # The owner may have said "show numbers" and then skipped.
            try { Invoke-Hook overlay @{ Show = 'none' } | Out-Null } catch { Write-Host 'Kestrel did not hide its numbers.' }
        }
        Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    }
    # Revoking the permission ends Kestrel's process, so the session starts Kestrel again, after Android has brought it
    # back for its accessibility service and Kestrel has restored its mode.
    Invoke-Checked 'pm revoke dev.kestrel android.permission.READ_CONTACTS' | Out-Null
    Start-Sleep -Seconds 3
    Wait-State ({ param($State) $State['mode'] -ne 'off' }) 10 | Out-Null
    Start-Kestrel
    Invoke-Check calls 'call and the name of one of your contacts' 'Did Kestrel say that it needs your contacts and open its settings, which ask for them? Allow it.' -Heard 'call Ana'
    Add-Result calls "contacts permission granted afterwards: $(Test-ContactsGranted)"
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    Invoke-Check calls 'call and the same name, and about 3 s into the countdown "cancel"' 'Did the countdown stop, with "Cancelled" on the bar and the phone app showing the number?' -Heard 'call Ana'
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    Invoke-Check calls 'call and the same name, and "stop" as soon as the countdown starts' 'Did the countdown stop?' -Heard 'call Ana'
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    Invoke-Check calls 'call and the same name, and touch the screen during the countdown' 'Did your touch stop the countdown? (This shows whether Kestrel sees a real finger.)' -Heard 'call Ana'
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    Invoke-Check calls 'call and a first name that two of your contacts share' 'Did Kestrel ask "Which one?" with their names? Then touch the screen: did the question end?' -Prepare 'Skip this check (s) if no two of your contacts share a first name.' -Heard 'call Ana'
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    Invoke-Check calls 'copy this phone number' 'Paste it into a note or a message field: is it the contact''s number?' -Prepare 'Open a contact in Contacts that shows one phone number.'
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
}

function Invoke-TimingStep {
    Start-Kestrel
    $before = Invoke-Hook snapshots
    if ((Request-Owner 'Open Settings, and for about 2 minutes scroll and open pages with Kestrel, saying a command now and then ("scroll down", "tap Display", "go back"). Press Enter when you are done.' 'go') -eq 'skip') {
        Add-Result timing 'skipped by the owner'
        return
    }
    $after = Invoke-Hook snapshots
    if ($before -match 'builds=(\d+) millis=(\d+)') {
        $builds0 = [int]$Matches[1]
        $millis0 = [long]$Matches[2]
        if ($after -match 'builds=(\d+) millis=(\d+)') {
            $builds = [int]$Matches[1] - $builds0
            $millis = [long]$Matches[2] - $millis0
            $average = if ($builds -gt 0) { [math]::Round($millis / $builds) } else { 0 }
            Add-Result timing "Kestrel built $builds snapshots in $millis ms while it listened, $average ms each"
        }
    }
    # The transcription time with the busy screen's labels as boost words, against the session's words alone, on the
    # generated clips of the instant commands; the labels never leave the phone.
    $folder = Join-Path $PSScriptRoot '..\corpus\generated\piper'
    $rows = @(Read-Corpus -Path $corpusFile | Where-Object { $_.Group -eq 'appendix' -and (Test-Path -LiteralPath (Join-Path $folder "$($_.Id).wav")) } | Select-Object -First 10)
    if ($rows.Count -eq 0) {
        Add-Result timing 'transcription time not measured: no generated clips in corpus\generated\piper; make them with scripts\make-corpus.ps1 on the emulator'
        return
    }
    Stop-Kestrel
    Invoke-Checked 'am start -a android.settings.SETTINGS' | Out-Null
    Request-Owner 'Settings is open. Leave it in front, with many entries on the screen, while the script measures for a few minutes.' 'go' | Out-Null
    try {
        $state = Invoke-Hook voice-eval @{ Load = $true }
        $watch = [Diagnostics.Stopwatch]::StartNew()
        while ($state -eq 'loading' -and $watch.Elapsed.TotalSeconds -lt 300) {
            Start-Sleep -Seconds 2
            $state = Invoke-Hook voice-eval @{ Load = $true }
        }
        if ($state -notmatch '^ready') { throw "the recogniser did not load: $state" }
        $withLabels = [System.Collections.Generic.List[double]]::new()
        $without = [System.Collections.Generic.List[double]]::new()
        foreach ($row in $rows) {
            Invoke-AdbCopy -Serial $Serial -Direction push -From (Join-Path $folder "$($row.Id).wav") -To "$remote/gen-timing.wav"
            $options = @{ File = 'gen-timing'; Expected = $row.Text; Kind = 'skip'; Group = $row.Group; ScreenLabels = $true }
            $withLabels.Add([double](ConvertFrom-HookAnswer (Invoke-Hook voice-eval $options)).millis)
            $without.Add([double](ConvertFrom-HookAnswer (Invoke-Hook voice-eval @{ File = 'gen-timing'; Expected = $row.Text; Kind = 'skip' })).millis)
        }
        Add-Result timing "transcription of $($rows.Count) command clips: median $(Get-Median $withLabels) ms with the screen's labels and the vocabulary, $(Get-Median $without) ms without them"
    } finally {
        Invoke-Hook voice-eval @{ Close = $true } | Out-Null
        Invoke-Checked "rm -f $remote/gen-timing.wav" | Out-Null
        Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    }
}

# Records the owner into a clip of up to $Seconds seconds and copies it to $File. The recording starts once the
# microphone records, the screen is woken and Kestrel's settings brought to the front first, because Android silences a
# recording otherwise. Returns the hook's answer.
function Invoke-OwnerRecording([string]$File, [int]$Seconds) {
    Invoke-Checked 'input keyevent KEYCODE_WAKEUP' | Out-Null
    Invoke-Checked 'am start -n dev.kestrel/.app.MainActivity' | Out-Null
    $started = Invoke-Hook voice-record @{ File = 'owner'; Seconds = $Seconds }
    if ($started -ne 'recording') { throw "voice-record did not start: $started" }
    Write-Host "Speak now ($Seconds s)"
    Start-Sleep -Seconds $Seconds
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $reply = Invoke-Hook voice-record @{ Result = $true }
        if ($reply -ne 'recording') { break }
        Start-Sleep -Milliseconds 250
    } while ($watch.Elapsed.TotalSeconds -lt 10)
    Invoke-AdbCopy -Serial $Serial -Direction pull -From "$remote/owner.wav" -To $File
    Invoke-Checked "rm -f $remote/owner.wav" | Out-Null
    $reply
}

function Invoke-RecordingStep {
    New-Item -ItemType Directory -Force -Path $clipsFolder | Out-Null
    # Only the lines that no earlier session recorded: plan 2D's session recorded the first 100.
    $rows = @(Read-Corpus -Path $corpusFile | Where-Object { $_.Owner -and -not (Test-Path -LiteralPath (Join-Path $clipsFolder "$($_.Id).wav")) })
    if ($DryRun) { $rows = @($rows | Select-Object -First 2) }
    Stop-Kestrel
    $minutes = [math]::Max(1, [math]::Ceiling($rows.Count * 12 / 60))
    if ((Request-Owner "Next come $($rows.Count) lines to say, about $minutes minutes. Sit in a quiet room and hold the phone as you would to talk to it; the script keeps Kestrel's settings in front. For each line, press Enter, wait for ""Speak now"", and say the line once, as you would say it to Kestrel. Press Enter to begin, or s to skip the recordings." 'go') -eq 'skip') {
        Add-Result recordings 'skipped'
        return
    }
    $recorded = 0
    for ($i = 0; $i -lt $rows.Count; $i++) {
        $row = $rows[$i]
        $answer = Read-OwnerAnswer "[$($i + 1)/$($rows.Count)] Say: $($row.Text)    (Enter to record, s to skip the line, q to stop)" ''
        if ($answer -eq 'q') { break }
        if ($answer -eq 's') { continue }
        $seconds = if ($DryRun) { 1 } else { Get-ClipSeconds -Text $row.Text }
        $reply = Invoke-OwnerRecording -File (Join-Path $clipsFolder "$($row.Id).wav") -Seconds $seconds
        $quiet = $reply -match 'silenced=true' -or ($reply -match 'loudest (\d+) %' -and [int]$Matches[1] -lt 3)
        if ($quiet -and -not $DryRun) {
            Write-Host 'That recording was silenced or too quiet. Check that the screen is on, the phone unlocked and Kestrel''s settings in front, and say the line again.'
            $i--
            continue
        }
        if ((Read-OwnerAnswer 'Enter for the next line, or r to record this line again.' '') -eq 'r') {
            $i--
            continue
        }
        $recorded++
    }
    Invoke-Checked 'input keyevent KEYCODE_HOME' | Out-Null
    Add-Result recordings "$recorded of $($rows.Count) lines recorded into $((Resolve-Path -LiteralPath $clipsFolder).Path)"
}

$plan = @(
    @{ Name = 'install'; Minutes = 7; Title = 'installing Kestrel, its accessibility service and the SIM check'; Run = 'Invoke-InstallStep' }
    @{ Name = 'screen'; Minutes = 12; Title = 'numbers, labels, the grid, taps, scrolling, "Which one?" and require verbs'; Run = 'Invoke-ScreenStep' }
    @{ Name = 'swipes'; Minutes = 5; Title = 'the edge swipes, swiping pages and a slider'; Run = 'Invoke-SwipeStep' }
    @{ Name = 'notifications'; Minutes = 3; Title = 'a notification in the shade'; Run = 'Invoke-NotificationStep' }
    @{ Name = 'global'; Minutes = 9; Title = 'back, home, Quick Settings, recent apps, lock, power menu, screenshot and apps'; Run = 'Invoke-GlobalStep' }
    @{ Name = 'profiles'; Minutes = 1; Title = 'the phone''s users and profiles, as IDs and counts'; Run = 'Invoke-ProfileStep' }
    @{ Name = 'radios'; Minutes = 4; Title = 'Wi-Fi and Bluetooth'; Run = 'Invoke-RadioStep' }
    @{ Name = 'sound'; Minutes = 11; Title = 'volumes, mute, silence, media, magnification and Circle to Search'; Run = 'Invoke-SoundStep' }
    @{ Name = 'text'; Minutes = 11; Title = 'dictation and editing with Samsung Keyboard, show password and search'; Run = 'Invoke-TextStep' }
    @{ Name = 'calls'; Minutes = 12; Title = 'calls by number and name, the guard of the Call button, the contacts permission and copying a number'; Run = 'Invoke-CallStep' }
    @{ Name = 'timing'; Minutes = 6; Title = 'the snapshots while Kestrel listens, and the transcription time with labels'; Run = 'Invoke-TimingStep' }
    @{ Name = 'recordings'; Minutes = 14; Title = 'your recordings of the new command lines'; Run = 'Invoke-RecordingStep' }
)

$completed = $false
$stopped = $false
Add-Result session "started on $(if ($isEmulator) { 'kestrel-api37' } else { 'the Galaxy A71' })$(if ($DryRun) { ', as a dry run' })"
try {
    foreach ($entry in $plan) {
        if ($Step -and $entry.Name -notin $Step) { continue }
        if ((Request-Owner "Step $($entry.Name), about $($entry.Minutes) minutes: $($entry.Title). Press Enter to run it, s to skip it, or q to stop the session." 'go') -eq 'skip') {
            Add-Result $entry.Name 'skipped by the owner'
            continue
        }
        Initialize-Session
        try {
            & $entry.Run
        } catch {
            if ($_.Exception.Message -eq 'The owner stopped the session.') { throw }
            # The scripts' and hooks' errors name only steps, files and classes, never what Kestrel heard.
            Add-Result $entry.Name "failed: $($_.Exception.Message)"
        }
    }
    $completed = $true
} catch {
    $stopped = $true
    Add-Result session "stopped: $($_.Exception.Message)"
} finally {
    try {
        # The restores that are left, the newest first, so that the screen, the clips, the sound, the radios, the
        # keyboard, the accessibility settings, Kestrel's mode and its permission come last.
        $left = @($pending.ToArray())
        [array]::Reverse($left)
        Invoke-Restore $left
    } finally {
        if (-not ($completed -or $stopped)) { Add-SessionResult -Path $resultsFile -Step session -Text 'stopped, as by Ctrl+C' }
        if ($DryRun -and (Test-Path -LiteralPath $clipsFolder)) { Remove-Item -LiteralPath $clipsFolder -Recurse -Force }
        foreach ($restore in $pending) { Write-Warning "Could not put back $($restore.What) on $Serial ($($restore.Reason)). $($restore.ByHand)" }
        # install.ps1 builds through Gradle, which keeps a daemon running.
        if ($gradleUsed) { & "$PSScriptRoot\gradle.ps1" --stop | Out-Null }
        Write-Host "The results are in $((Resolve-Path -LiteralPath $resultsFile).Path)."
        # After a stop, such as Ctrl+C, PowerShell would end the script with exit code 0. pwsh -File ends a stopped script
        # that declares parameters with exit code 0, even after 'exit 1', unless the script gives the host its exit code;
        # run from a prompt, that would close the window, so the script does it only when nothing called it.
        if (-not ($completed -or $stopped)) {
            if (@(Get-PSCallStack).Count -eq 1) { $Host.SetShouldExit(1) }
            exit 1
        }
    }
}
if ($stopped -or $pending.Count -gt 0) { exit 1 }
```

- [ ] **Step 4: Run the script tests**

Run: `scripts\test-scripts.ps1`
Expected: "The script tests and checks passed.", with 160 Pester tests in each shell (`A71Commands.Tests.ps1` 7) and "PSScriptAnalyzer found 0 problems."

- [ ] **Step 5: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin :testapp:assembleDebug`
Expected: `BUILD SUCCESSFUL`: all JVM tests as after Task 7, lint without issues in every Android module, the audit, and the device tests and the test app compile, among them the debug build's `TestHooks`.

- [ ] **Step 6: Run every device test of Appendix A's rows**

This run is the emulator part of section 18.3's "done when". Kestrel must be installed from the current commit; `command-test.ps1` and `voice-test.ps1` install it.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\command-test.ps1 -Serial emulator-5554`
Expected: `BUILD SUCCESSFUL` and "Command tests: 88 run, 0 failed, 0 errors, 0 skipped.", 59 + 29 (plan 3C's `SystemCommandsTest` 8, `AppCommandsTest` 5, `DeviceCommandsTest` 8 and `CallCommandsTest` 8), with no warning from the restores.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: `BUILD SUCCESSFUL` and "Voice tests: 83 run, 0 failed, 0 errors, 0 skipped.", with no warning at the end.

The other device tests of `:app`, among them `SettingsAppTest`, which the coverage table names too; the command tests in this run skip themselves, as plan 3A designed:

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.notPackage=dev.kestrel.app.voice' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' --no-configuration-cache`
Expected: `BUILD SUCCESSFUL`, and the result files in `app\build\outputs\androidTest-results\connected\debug\` show 208 tests (179 + plan 3C's 29) and 0 failures; the 88 command tests among them are skipped.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\listen-check.ps1 -Serial emulator-5554`
Expected: exit code 0, and no check in the table that it prints at the end failed.

If a test or a check fails, stop and report it to the controller with its message.

- [ ] **Step 7: Try the A71 session on the emulator**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; pwsh -NoProfile -File scripts\a71-commands.ps1 -Serial emulator-5554 -DryRun`
Expected: exit code 0, no warning, and "The results are in" with the path of `corpus\owner\commands-dry-run-<time>.txt`. In that file the step `install` records the emulator's SIM state, the step `calls` calls 0641234567 (a dry run answers the owner's questions itself), the step `profiles` reports one profile of the launcher API (`user=0 apps=… type=android.os.usertype.full.SYSTEM`), the step `timing` reports the snapshots that Kestrel built and the two median times, and no step reports "failed:". Afterwards Kestrel's mode is Off and the emulator's radios, volumes and sound mode are as before the run. If a step failed, report the file's lines to the controller.

- [ ] **Step 8: Add the coverage table, the "done when" and the move of "Kestrel, delete history" to the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace

```markdown
- Both logs are encrypted and can be viewed in settings. "Kestrel, delete history" (after a spoken confirmation) or a button wipes both.
```

with

```markdown
- Both logs are encrypted and can be viewed in settings. A button in settings wipes both after a confirmation; "Kestrel, delete history" by voice, after a spoken confirmation, comes with the agent (sub-project 5).
```

Then replace

```markdown
- **Scope:** `:core:commands` with every command in Appendix A that is marked "Yes"; matching; "Which one?"; dictation and text editing; the settings, media, call and magnification commands; help; the Voice Access options in section 9.8.
- **Done when:** every "Yes" row in Appendix A passes on the emulator, and a manual checklist passes on the A71. Kestrel can now replace Voice Access.
```

with

```markdown
- **Scope:** `:core:commands` with every command in Appendix A that is marked "Yes"; matching; "Which one?"; dictation and text editing; the settings, media, call and magnification commands; help; the Voice Access options in section 9.8; the instant commands of the voice corpus and their accuracy (section 16.4).
- **Done when:** every "Yes" row in Appendix A passes its device tests on the emulator in one run (the coverage table after Appendix A); at least 95% of the corpus's instant-command lines lead to the correct action on the emulator, in both generated voices and with the owner's recordings (section 16.4); and the A71 session of `scripts/a71-commands.ps1` has run with the owner, and its results are in section 19.1, among them whether the touch watcher saw the owner's real finger (spike S3). Kestrel can now replace Voice Access.
```

Then replace

```markdown
- **Scope:** the agent loop, the observation format, the tools, the data tools and their permissions; the safety check (action categories, modes, security floor, confirmations, hand-overs, agent-free apps, redaction and test-mode enforcement); escalation and limits; `look_closer` with PP-OCRv5 and the screenshot question; the completion check; memory and routines; partial results.
```

with

```markdown
- **Scope:** the agent loop, the observation format, the tools, the data tools and their permissions; the safety check (action categories, modes, security floor, confirmations, hand-overs, agent-free apps, redaction and test-mode enforcement); escalation and limits; `look_closer` with PP-OCRv5 and the screenshot question; the completion check; memory and routines; partial results; "Kestrel, delete history" by voice, with its spoken confirmation.
```

Then replace

```markdown
| Kestrel, delete history | Wipes the activity log and the network log, after confirmation |
```

with

```markdown
| Kestrel, delete history | Wipes the activity log and the network log, after a spoken confirmation. It comes with the agent (sub-project 5); until then, the button on the settings pages of the logs does it. |
```

Then replace

```markdown
## Appendix B. Key facts and sources
```

with

```markdown
### Coverage of the "Yes" rows on the emulator

Each "Yes" row of the first table and the device tests that check it on kestrel-api37 (sub-project 3, plan 3C). `scripts\command-test.ps1 -Serial` runs the tests of the instant commands (the package `dev.kestrel.app.commands`), `scripts\voice-test.ps1 -Serial` the voice tests of sub-project 2, `scripts\connected-test.ps1` the other device tests of `:app`, among them `SettingsAppTest`, and `scripts\listen-check.ps1 -Serial` sub-project 2's checks of listening and calls. One run of all four is the emulator part of section 18.3's "done when".

| Group | Commands | Device tests |
|---|---|---|
| General | Open [app]; Open apps; Open work [app] | `AppCommandsTest`; `SystemCommandsTest#openAppsShowsTheListOfAllApps` |
| General | Go back; Go home; Show notifications; Show Quick Settings; Show recent apps; Show previous app | `SystemCommandsTest` |
| General | Lock screen; Turn off device; Take screenshot | `SystemCommandsTest` |
| General | Again; Cancel | `TouchCommandsTest#deleteInADialogAndAgainTapItsOnlyDeleteButtonTwice` and `#stopEndsWhichOneAndRemovesItsNumbers`; `CallCommandsTest#cancelDuringTheCountdownLeavesTheNumberInThePhoneApp` |
| General | Circle to Search | `SystemCommandsTest#circleToSearchIsNotAvailableOnTheEmulator` (the emulator's launcher offers no Circle to Search) |
| General | Media play; Media pause | `DeviceCommandsTest#mediaPauseAndPlayReachAPlayerInTheBackground` |
| Help | What can I say?; Show all commands; Open tutorial | `HelpCommandsTest` |
| Help | Show numbers; Hide numbers; Show labels; Hide labels; What is [number]?; Show commands for [label or number] | `OverlayCommandsTest` |
| Help | Stop Voice Access; Voice Access settings | `ConversationTest` ("Stop Kestrel"); `scripts\listen-check.ps1` ("Kestrel, open settings") |
| Settings | Turn Bluetooth on or off; Turn Wi-Fi on or off | `DeviceCommandsTest` (Wi-Fi, Bluetooth by its tile and by its settings page) |
| Settings | Turn volume up or down; …; Mute; Unmute; Silence | `DeviceCommandsTest` (volumes, mute and silence) |
| Numbers and labels | "7"; "Save"; "Tap 7"; "Tap Save" | `TouchCommandsTest`; `OverlayCommandsTest#numbersAndLabelsShowAndHide` |
| Passwords | Show password; spelling a password character by character | `TextCommandsTest` |
| Search in apps | Search for [query] | `TextCommandsTest` |
| Calls | Answer call | `scripts\listen-check.ps1` (a call from the emulator's console, answered and answered on speaker) |
| Calls | Keep active during phone calls; turn listening on for incoming calls | `SettingsAppTest#theListeningPageChangesTheOptions`; `scripts\listen-check.ps1` |
| Calls | Call [number]; Call [name] | `CallCommandsTest` |
| Calls | Copy this phone number | `CallCommandsTest#copyThisPhoneNumberCopiesTheOnlyNumberOrTheOneThatTheOwnerChooses` |
| Sliders | Increment [number]; Decrement [number] | `TouchCommandsTest#incrementAndDecrementMoveASeekBarAndAComposeSliderOneStep` |
| Touch gestures | Tap; [item]; Long-press; Drag; Check; Uncheck; Switch on; Switch off; Expand; Collapse; Dismiss | `TouchCommandsTest`; `GestureCommandsTest#dragMovesAnElementToTheTargetThatTheOwnerNamesOrGivesWhenAsked` |
| Swipe gestures | Scroll; Scroll to top or bottom; Swipe forwards or backwards; Swipe from an edge | `GestureCommandsTest` |
| Grid | Show grid; More squares; Fewer squares; the grid's taps, swipes, pinches and drags; Hide grid | `OverlayCommandsTest#theGridTapsAndMakesGesturesOnItsSquares` |
| Typing, Punctuation, Replacing, Deleting, Cursor, Selecting, Clipboard, Keyboard | Every row | `TextCommandsTest`; `TextEditorTest` |
| Magnification | Start magnification; …; Pan, move, go or scroll; Stop magnification | `DeviceCommandsTest#magnificationZoomsPansWithScrollAndStops` |
| Stopping | Stop listening; Touch to pause in the notification; screen off; time-out after no speech | `ListeningServiceTest#theServiceRunsInTheForegroundAndItsNotificationPausesResumesAndStops` and `#theMicrophoneStopsWhileTheScreenIsOffAndStartsAgainAfterTheUnlock`; `ConversationTest`; the time-out only in the JVM test `RulesTest#thirtySecondsWithoutSpeechPauseKestrelWhenTheSettingIsOn` |
| Options | Cancel on touch | `TouchCommandsTest#theOwnersTouchCancelsWhichOne`; `CallCommandsTest#aTouchDuringTheCountdownCancelsIt` |
| Options | Require verbs; numbers for all items or only unlabeled ones; behaviour on screen wake-up; time-out after no speech | `TouchCommandsTest#requireVerbsKeepsANameAloneFromTapping`; `OverlayCommandsTest#theNumbersSettingPutsNumbersOnlyOnElementsWithoutAName`; `SettingsAppTest#theListeningPageChangesTheOptions`; the time-out only in the JVM test `RulesTest#thirtySecondsWithoutSpeechPauseKestrelWhenTheSettingIsOn` |

## Appendix B. Key facts and sources
```

- [ ] **Step 9: Add the emulator's results of sub-project 3 to 19.1**

Find the commit after Task 7 with `git rev-parse --short HEAD` (Step 6 ran with this task's changes on top of it, which touch only the debug hooks and the scripts), and the command accuracy of Task 7 in the spec's 8.3 (its numbers A and B).

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace

```markdown
The results of the session go into this section and into the README's table of measurements.

## 20. Risks and open questions
```

with the text below, with the commit in place of COMMIT, the count of Step 6's command tests in place of N, the voice tests' count in place of V, and Task 7's numbers in place of A and B:

```markdown
The results of the session go into this section and into the README's table of measurements.
- **The instant commands on the emulator (plan 3C).** On kestrel-api37 (commit COMMIT), one run of `scripts\command-test.ps1` passed all N device tests of the instant commands, `scripts\voice-test.ps1` passed all V voice tests, `scripts\connected-test.ps1` passed the other device tests of `:app` and `scripts\listen-check.ps1` passed every check, so every "Yes" row of Appendix A passed on the emulator (the coverage table after Appendix A). With the generated corpus, A % of the instant-command lines of the Piper clips and B % of the Kokoro clips led to the correct action (section 8.3). The emulator's launcher offers no Circle to Search, so Kestrel says that it is not available there. `scripts\a71-commands.ps1 -DryRun` passed on kestrel-api37. The A71 session with the owner is still open: it checks every group of commands on One UI, the profiles that decide which work apps Kestrel may open on Android 13, the call button of Samsung's phone app, the snapshots and the transcription time on a busy screen, and the touch watcher with the owner's real finger (spike S3), and it records the owner's command lines, which `scripts\voice-eval.ps1 -Corpus owner` measures on the emulator. Its results go into this section and the README.

## 20. Risks and open questions
```

- [ ] **Step 10: Describe the phone's commands and the session in the README**

In `README.md`, replace

```markdown
<!-- Plan 3C: apps, phone settings and calls -->
```

with

```markdown
You can also drive the phone itself: "go back", "go home", "show notifications", "show Quick Settings", "show recent apps", "show previous app", "lock screen", "turn off device" (the power menu), "take a screenshot", "open apps", and "circle to search" where the phone offers it. "Open Chrome" opens an app by its name; "open work Gmail" opens the app of the work profile, from Android 15 on. "Turn on Wi-Fi" and "turn off Bluetooth" switch the radios. "Volume up", "turn the alarm volume down", "mute", "unmute the ringer" and "silence", which puts the phone on vibrate, change the sound, and "media play" and "media pause" reach the player that played last. "Zoom in" magnifies the screen, "pan left" or "scroll left" moves the magnified area, and "zoom out" and "stop magnification" end it. "Call 064 123 4567" or "call Ana" (also "call Ana mobile") opens your phone app with the number, says whom it calls and counts down 4 seconds on the feedback bar before it presses Call; say "cancel" or touch the screen to stop it, and the number stays in the phone app. For a short number or a code with `*` or `#`, Kestrel only opens the phone app, and you tap Call yourself. Before anything that may call an emergency number, such as "call 112", an "Emergency" or "SOS" button, or the phone app's Call button while it shows such a number, a short number or one that Kestrel can't read, Kestrel asks you to say "yes, call"; anything else stops it. It does not see the phone app's controls that call a stored number while the number field is empty (a call-history row's call icon, a favourite, a contact's page, a speed-dial key), "emergency" and "SOS" in other languages than English, or an app's own emergency feature outside the phone app. The first "call [name]" asks for access to your contacts, which Kestrel reads only on the phone. "Copy this phone number" copies a phone number from the screen.

The voice corpus also has the instant commands, with the labels that the screen would show for some of them in `corpus\scenes.txt`; `scripts\voice-eval.ps1` prints the share of them that lead to the correct action, and `-NoLabels` measures it without the screen's labels and the command words. `scripts\a71-commands.ps1 -Serial $serial` takes you through the checks of the instant commands on the Galaxy A71, in about 80 to 105 minutes, of which about 70 to 95 need you; `-DryRun` tries it on the emulator without you.

<!-- Plan 3C: apps, phone settings and calls -->
```

- [ ] **Step 11: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 12: Commit**

```powershell
git add scripts/a71-commands.ps1 scripts/tests/A71Commands.Tests.ps1 scripts/hook.ps1 app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt docs/superpowers/specs/2026-11-12-kestrel-design.md README.md
git commit -m "feat(commands): the A71 session of the instant commands, Appendix A's coverage and the results on the emulator" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Self-review

**Design coverage.** D9: the global actions, "open apps" with its spoken refusal, the screenshot without the feedback bar, "show previous app" through two recent-apps actions and the home app, Circle to Search through system action 500 only (Task 1); "open X", "open work X", the work profile from Android 15 by its user type, Android 13 and 14, the paused profile, "Which one?" among names, the punctuation words that "open" never takes (Task 2). D10: the radios through the Internet panel's switch, the Bluetooth tile and the Bluetooth settings (Task 3); volumes with the stream rule and the read-back, mute and its refusals, silence as vibrate, the media keys and their check, magnification, panning and the scroll that pans while magnified (Task 4). D11: `PhoneNumbers`, `ContactMatcher`, `ContactsReader` with its observer after the grant, `ContactsAccess`, `READ_CONTACTS` (Task 5); the dial with `Uri.fromParts`, the call button, the announcement and the 4 s countdown, the press and the end of the command, the SIM chooser, the short and code numbers, the owner's "yes, call" for emergency numbers with `EmergencyPolicy` and `EmergencyGuard`'s two rules, "Which one?" and "Which number?", the first-use request through `EXTRA_ASK_PERMISSION`, and "copy this phone number" (Task 6). D13 and D15: the corpus's command, `conversation` and `dictation` lines, `corpus/scenes.txt`, `WordErrors` moved, `Commands.sameAction`, the hook's labels and `command` answer, `-NoLabels`, the runs in both voices, the 95% target, and the tuning of `MatchRules.kt` through the controller (Task 7); the coverage table and one run of every device test (Task 8). D16: `scripts\a71-commands.ps1` with D16's twelve steps in order, its Pester tests and its dry run (Task 8). D17: no new code logs or prints a name, a number, a label, a transcript or a typed text; `LaunchableApp`, `Contact` and `PhoneEntry` print only lengths and counts; the hooks answer only counts, kinds and outcomes. D18's 3C part: 9.3's row (Task 4), Appendix A's Open (Task 2), Circle to Search (Task 1), settings (Tasks 3 and 4) and calls rows, 13.6 and 8.7 with its new bullet on emergency calls (Task 6), 8.3 and 16.4 (Task 7), 13.5, 18.3, 18.5, 19.1, the delete-history row and the coverage table (Task 8); 9.6's `- **Limits:**` line changes only if the controller tunes the limits after Task 7's runs. The README at the 3C marker (Task 8) and its sentence of the corpus's size (Task 7), and spec 13.6's `<queries>` bullet (Task 1), which the controller added to this plan's list.

**Placeholder scan.** Every step has its complete code or its exact command and expected output. The only values that the plan cannot know in advance are measurements and counts, and each step says where they come from: the letters A to P of spec 8.3 (Task 7, Step 15, from Step 14's four runs), and COMMIT, N, V, A and B of 19.1 (Task 8, Step 9). The counts of the tests are absolute, from plan 3B's totals in "Before you start".

**Type consistency.** `GrammarPhone.fixed` and `targeted` keep 3A's signatures, and every rule returns `Command?`; `radio` (Task 3) and `call` (Task 6) drop every polite word of plan 3A's `CommandParser.POLITE_WORDS` at the end, and `openApp` (Task 2) only "please". `Pick`, `pick`, `Answer`, `askAmong` (with its `fold`, which Task 6 uses for "cell") and `MAX_SPOKEN_CHOICES` (Task 2, `AppCommands.kt`) are used by `CallCommands` (Task 6). `PhoneNumbers.dialable` and `mainNumber` (Task 5) are used by `CallCommands`, `AndroidDialer` and `EmergencyGuard` (Task 6). `PhoneCommandTexts` imports `ContactMatcher` only in Task 6, when `whichNumber` needs it. `DeviceCommands` keeps `radio` and `namesRadio` from Task 3 in Task 4's whole file. `PhoneCommandTexts` grows by anchors in Tasks 2, 3, 4 and 6, and every text that the tests use (`NO_WORK_PROFILE`, `WIFI_ALREADY_OFF`, `MUTE_ALARM`, `NO_MEDIA`, `HIGHEST`, `LOWEST`, `VOLUME_NOT_CHANGED`, `whichOne`, `noChoice`) exists in the task that tests it. `ContactsReader(access, source, report)` (Task 5) is built by `CallCommands.create` (Task 6) with `environment.report`. `SystemControls` keeps 3A's constructor and signatures and adds `magnifiedCentre()`, which Task 4 and its device test use. `Commands.sameAction(expected, heard, labels)` matches the design's name; `Commands.sameDictation` is the hook's check of a dictation line. The hook's extras (`group`, `labels`, `noLabels`, `screenLabels`) are the names that `hook.ps1` sends.

**Counts.**

| Task | `:core:commands` | `:app` JVM | Command device tests | Pester (each shell) |
|---|---|---|---|---|
| 1 | + 2 (`GrammarPhoneTest` 2) | – | + 8 (`SystemCommandsTest` 8) | – |
| 2 | + 2 (`GrammarPhoneTest` 4) | + 4 (`PhoneAppsTest` 4) | + 5 (`AppCommandsTest` 5) | – |
| 3 | + 1 (`GrammarPhoneTest` 5) | + 1 (`PhoneDevicesTest` 1) | + 3 (`DeviceCommandsTest` 3) | – |
| 4 | + 3 (`GrammarPhoneTest` 8) | + 4 (`PhoneDevicesTest` 5) | + 5 (`DeviceCommandsTest` 8) | – |
| 5 | + 11 (`PhoneNumbersTest` 5, `ContactMatcherTest` 6) | + 4 (`ContactsReaderTest` 4) | – | – |
| 6 | + 2 (`GrammarPhoneTest` 10) | + 18 (`PhoneCallsTest` 18) | + 8 (`CallCommandsTest` 8) | – |
| 7 | + 12 (`SameActionTest` 5, `CorpusTest` 2, `WordErrorsTest` 5, moved) | − 5 (`WordErrorsTest`, moved) | – | + 9 (`Corpus.Tests.ps1`) |
| 8 | – | – | – | + 7 (`A71Commands.Tests.ps1`) |
| All | + 33 | + 26 | + 29 | + 16 |
| Totals | 88 → 121 | 323 → 349 | 59 → 88 | 144 → 160 |

The voice device tests stay at 83; Task 7 and Task 8 run them. `connected-test.ps1` goes from 179 to 208, with the 88 command tests skipped there.
