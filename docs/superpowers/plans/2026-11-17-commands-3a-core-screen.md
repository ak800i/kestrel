# Instant Commands 3A: the Command Core, the Screen Commands, Help and the Options Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the owner drive the screen by voice without Kestrel's name, as with Voice Access: taps by label and by number, "Which one?", bare labels, scrolling, swipes, gestures, sliders, notifications, the grid, numbers and labels, help, "again", and the options "require verbs" and the numbers mode; and leave plans 3B and 3C their stubs and anchors.

**Architecture:** A new plain Kotlin module `:core:commands` normalises and matches spoken words (`Normalizer`, `PhoneticKey`, `LabelMatcher`) and parses a transcript into one `Command` of a frozen sealed model (`CommandParser` with the per-plan grammar objects `GrammarScreen`, `GrammarText` and `GrammarPhone`). The Orchestrator's Rules read every transcript that is not Kestrel's own request as a command while Kestrel listens; the new work `RUNNING_COMMAND` numbers each command, and `Listening` runs it as one job through a `CommandRunner`, with a `CommandConversation` for "Which one?", spoken replies and the call countdown that plan 3C uses, and cancels it on "stop", "cancel", a mode change or a touch. The runner, `InstantCommands` in `:app`, asks three parts in turn (text, phone, screen); plan 3A writes the screen part with `ScreenTargets`, which finds candidates in the current snapshot, asks "Which one?" and checks the chosen element again right before it acts, and creates the text and phone parts as stubs that plans 3B and 3C replace whole.

**Tech Stack:** Kotlin 2.4.21 (the Kotlin standard library only in `:core:commands`, JUnit 4 for its tests), kotlinx.coroutines 1.11.0 (kotlinx-coroutines-test for the JVM tests), kotlinx.serialization for the stored options, Android's `AccessibilityService`, `AccessibilityNodeInfo` (`RangeInfo`, `ACTION_SET_PROGRESS`, `ACTION_EXPAND`, `ACTION_COLLAPSE`, `ACTION_DISMISS`) and accessibility overlays at compileSdk 37, Jetpack Compose for the settings page and one fixture, JUnit 4 and AndroidX Test for the device tests on the `kestrel-api37` emulator through the new `scripts\command-test.ps1`, Pester 6.2.0 for the scripts.

## Global Constraints

- Package and application ID `dev.kestrel`; licence GPL-3.0-or-later; no new dependency that ships in the app (`:core:commands` uses only the Kotlin standard library; its tests use JUnit).
- `minSdk 33`, `targetSdk 37`, `compileSdk 37`. Every Android call that the A71 (Android 13) lacks has a check of the API level and a fallback.
- No dependency-injection framework; `AppGraph` wires by hand.
- New permissions: only `READ_CONTACTS` (runtime, asked at first use, plan 3C). Kestrel never requests `CALL_PHONE` or `ACCESS_NOTIFICATION_POLICY`, nor any permission on spec 13.6's "never" list.
- Never log audio, transcripts, replies, questions, boost words, app labels, screen text, contact names, phone numbers, field text, typed text, exception messages or an exit record's description (section 13.5); `toString` of anything that carries the owner's words or the screen's text prints only lengths, counts or kinds.
- Plans 3B and 3C create or change only the files of their own list in "Shared files and anchors" of the design (`.superpowers/sdd/plan-3-design.md`), at the places that it gives; any other file needs the controller. This plan creates every stub, file and marker of plan 3A's list there, exactly as named.
- Test only on `kestrel-api37` (emulator-5554), and check it in the same command as each device command; never on the S23+ or the AVD `zoran`, and no command to any connected phone; the A71 only in the session with the owner. Gradle only through `scripts\gradle.ps1` (stopped with `--stop` at the end of each task); device tests only through `scripts\command-test.ps1`, `scripts\voice-test.ps1`, `scripts\connected-test.ps1` (with `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`) or `scripts\listen-check.ps1`, as each task says; installs only through `scripts\install.ps1`. The command device tests grant what they need and never revoke a permission; `command-test.ps1` restores every setting, radio, volume, ringer mode, magnification, tile, lock-screen setting, contact, call-log entry, user profile, file and permission that they change, except Kestrel's microphone and notification permissions, which stay granted as after `voice-test.ps1` (revoking a permission ends Kestrel's process), and the APKs stay installed. A reboot of the emulator is a cold boot.
- Scripts run on Windows PowerShell 5.1 and PowerShell 7; `scripts\test-scripts.ps1` passes after every script change.
- The repository uses LF line endings.
- The plan file is named with the day it is written (`Get-Date -Format yyyy-MM-dd`), and it is frozen once committed; a later difference goes into `.superpowers/sdd/progress.md`.

## Before you start

- Plan 2D is complete; its last commit is in `.superpowers/sdd/progress.md`, section "Plan 2D". This plan was written against the code as plan 2D leaves it. Its "Changes from the plan" entries that touch this plan's files must be kept; in particular `Rules.hears()` may carry plan 2D's `!state.micBusy` (Task 7's run of plan 2D), and plan 2D's comment in `State.recognition` about the call commands may read differently: this plan never replaces those lines.
- The totals at the start of this plan are the ones after plan 2D (59b86ae): `:app` JVM 264 tests (among them `RulesTest` 61, `OrchestratorTest` 10, `ListeningTest` 117, `RequestsTest` 28 and `SetupChecklistTest` 12), `:core:voice` JVM 139 (among them `ListeningPipelineTest` 28), `:core:screen` JVM 47 (among them `SnapshotBuilderTest` 9 and `SnapshotCacheTest` 5), `:core:privacy` 48, `:core:safety` 2, `:core:commands` 0 (this plan creates it), voice device tests 81 (among them `VoiceSessionFailuresTest` 7), the other device tests of `:app` 116 (among them `SettingsAppTest` 29 and `MainActivityTest` 4), Pester 132 per shell. Each task gives its totals; before each task, compare the counts with the ones that the earlier tasks left. If a count differs, stop and ask the controller.
- Before each task, check that every text that the task replaces exists, and exists once, in its file. If a text is missing, stop and ask the controller.
- What this plan builds on (the code after plan 2D): `Rules.whileIdle` sends `Effect.RunCommand(text)` for `Request.Other` and for a stop without the name, only while the mode is Listening and the screen is on; `Listening` ignores it. `Work` has `IDLE`, `AWAITING_TASK` and `RUNNING_TASK`. The Orchestrator runs the wait for a task (8 s to start an answer, 35 s once the owner speaks), restarted when "Yes?" has been spoken; `Effect.WaitForTask` and `Effect.TimeSilence` are timers that the Orchestrator runs itself. `ScreenSession` has numbers (`NumbersMode.ALL` and `UNLABELED`), labels, the grid, "Which one?" numbers (`showWhichOne`), the feedback bar (which fades 4 s after a text), the confirmation card and the touch watcher (`startTouchWatcher`, `ownerTouches`); `hideNumbers()` also ends "show numbers". `ScreenActions` has `tap`, `longPress`, `tapAt`, `longPressAt`, `setText`, `scroll`, `swipe`, `drag`, `pinch`, `zoom`, `resetZoom`, `global`, `openApp`, `openUri` and `clickLabelled`, and it reads `snapshots.current()` again for each action; `tap` on an element that offers no click taps its centre with a gesture. `ScreenElement.isActionable` is `enabled && (clickable || longClickable || editable || checkable)`. The debug hook `voice -Send heard -Text '...'` sends `Event.Heard`, the same event that a transcript sends. The voice pipeline sends the keyword spotter's hits (`VoiceEvent.Spotted`) whether or not Kestrel speaks and whatever the recognition; only the Rules ignore them while Kestrel is silent.
- Run Gradle only through `scripts\gradle.ps1`, and stop it with `scripts\gradle.ps1 --stop` at the end of each task.
- From Task 4 on, run the command device tests (the package `dev.kestrel.app.commands`) only through `scripts\command-test.ps1 -Serial emulator-5554`, which Task 4 writes. They skip themselves unless the instrumentation argument `kestrelCommands=true` is present, which only that script passes. Run the voice device tests only through `scripts\voice-test.ps1 -Serial emulator-5554`, and the other device test classes through `scripts\connected-test.ps1`, always with `'-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'`, as each task says.
- Every device command of this plan starts with a check of the emulator, in the same command: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' };` and then the command itself. If the check throws, or no emulator runs, start the emulator with `scripts\start-emulator.ps1`, check that the serial that it prints belongs to `kestrel-api37`, and use that serial in place of `emulator-5554`. The device scripts also refuse other devices themselves (`Assert-TestDevice`), and `command-test.ps1` refuses every device that is not an emulator. Never send a command to a connected phone. A step that would reboot the emulator closes it and starts it from a cold boot instead.
- Before each task, run `git status --short`. If a file that the task changes (its **Files** list) already shows changes that are not the task's own, stop and ask the controller, because the task's `git add` would commit them too.
- Every commit message ends with the line `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.

## Not in this plan

Plan 3B (dictation and text editing): the editing model (`TextState`, `EditStep`, `TextUnits`, `TextSearch`, `EditModel`, `Dictation`, `EditHistory`), `GrammarText` and `HelpText` (this plan creates both as stubs), the real `TextEditor` (this plan creates a stub with the final constructor and signatures), the text part `TextCommands.kt` (a stub here), the text fixtures `EditActivity`, `ComposeEditActivity` and `WebEditActivity`, and spec 9.3's Type row and 9.7. Until plan 3B, "delete", "copy", "undo" and the other text phrases reach the executor as bare labels, and "type X" is a bare label too.

Plan 3C (global actions, apps, phone settings, calls, the corpus and the A71 session): `GrammarPhone` and `HelpPhone` (stubs here), the real `SystemControls` (a stub here), the phone part `PhoneCommands.kt` (a stub here), "back", "home" and the other global actions, "open X", "open apps", "show previous app", Circle to Search, Wi-Fi and Bluetooth, volume, mute, silence, media keys, magnification, calls and the call countdown's use, contacts and `READ_CONTACTS`, "copy this phone number", the corpus with its scenes and `Commands.sameAction`, the tuning of `MatchRules`, Appendix A's coverage table, and the A71 session (`a71-commands.ps1`). This plan builds the countdown, its holds and the cancel rules around it, and the `READ_CONTACTS` entry of `MainActivity.ASKABLE`; plan 3C uses them.

Sub-project 5: tasks, the agent, routines, memory and hand-overs, and with them instant commands during a hand-over (spec 11.10), "Kestrel, continue", "save that as", "remember", "forget" and "delete history" by voice, and spec 14.1's fallback for a touch watcher that misses real touches. Boosting contact names and aliases. Later milestones: entering the PIN by voice, wake-up with the screen off, "Recognize common icons", switch access. Languages other than English for commands; the matcher handles Serbian names on the screen, not Serbian commands.

## Threads

| Call | Thread | Notes |
|---|---|---|
| `Rules.next` with the new events, and `CommandParser.parse` inside it | The Orchestrator's coroutine on the controller's thread (`Listening.thread`) | The parser is pure and fast; it never touches Android |
| `Listening`'s handling of `RunCommand`, `CancelCommand`, `AnswerCommand`, `HoldCommand`, `Reply.Command` and `Feedback.CommandText` | The controller's thread | `RunCommand` launches the command's job in `Listening.scope` (`Dispatchers.IO` in `AppGraph`); `CancelCommand` cancels it and keeps it as the stopping job; a later `RunCommand`, `RunTask` or `OpenSettings` waits on the controller's thread (in a coroutine, at most 3 s) until the stopping job has completed |
| `CommandRunner.run` and every `CommandPart.run` | The command's job (`Dispatchers.IO`) | Binder calls to the accessibility service and `ScreenActions` run here; the `finally` blocks that remove "Which one?" numbers and stop the touch watcher run under `NonCancellable` |
| `CommandConversation.say`, `ask`, `countdown` | Called from the command's job | They send `Event.CommandSays`, `CommandAsks`, `CommandCounts` to the Orchestrator and wait for a `CompletableDeferred` that the controller's thread completes; `countdown` reads the hold flag that `Effect.HoldCommand` sets on the controller's thread (`@Volatile`) |
| `CommandConversation.keep`, `FeedbackBar.show(text, keep)`, `release()` | Any thread | `AppGraph`'s bar posts each call to the main thread in order, as today |
| `CommandRunner.privateInput()` | The controller's thread, before each `Feedback.Heard` | Makes no binder call: it reads only state that the parts keep (plan 3B reads `SnapshotCache.latest()`, which Task 8 adds) |
| `ScreenTargets.resolve`, `still` | The command's job | They read `snapshots.current()`, which may wait for a quiet screen |
| `ScreenSession.endWhichOne()`, `whichOneShown`, `showFeedback`, `releaseFeedback()`, `hideFeedback()` (Task 9) | Any thread; they switch to the main thread | `whichOneShown` is a `StateFlow` that the main thread writes |
| `ScreenSession.screenBounds` (Task 6), `snapshotBuilds` and `snapshotMillis` (Task 8) | Any thread | `screenBounds` reads the overlay host's bounds of the screen; the two counters are atomic |
| `ScreenSession.keepFresh` (Task 8) and the snapshot that it builds | `AppGraph`'s collector `keepScreenFresh` sets it on `Dispatchers.IO` (`AppGraph.scope`); the session's screen watcher builds the snapshot in the session's scope, and the reader runs on `Dispatchers.IO` | `SnapshotCache.latest()` never waits and never builds |

## File structure

| File | Responsibility |
|---|---|
| `settings.gradle.kts`, `core/commands/build.gradle.kts`, `app/build.gradle.kts` | Task 1: the module `:core:commands` and `:app`'s dependency on it |
| `core/commands/src/main/kotlin/dev/kestrel/commands/text/Normalizer.kt`, `JaroWinkler.kt`, `PhoneticKey.kt`, `LabelMatcher.kt`, `MatchRules.kt` | Task 1: folding spoken words and labels with offsets, the similarity, the phonetic key, the decision and its limits (plan 3C may change only the values in `MatchRules.kt`) |
| `core/commands/src/main/kotlin/dev/kestrel/commands/Command.kt`, `TextCommandModel.kt`, `PhoneCommandModel.kt` | Task 2: every command variant of plans 3A, 3B and 3C, frozen after Task 2 |
| `core/commands/src/main/kotlin/dev/kestrel/commands/CommandParser.kt`, `SpokenNumbers.kt`, `GrammarScreen.kt`, `GrammarText.kt` (stub), `GrammarPhone.kt` (stub) | Task 2: the parser with its two phases and the polite-word rule, spoken numbers, the screen phrases |
| `core/commands/src/main/kotlin/dev/kestrel/commands/CommandResult.kt`, `CommandHelp.kt`, `HelpScreen.kt`, `HelpText.kt` (stub), `HelpPhone.kt` (stub) | Task 2: what a command ends with; the help list, one file per plan |
| `core/commands/src/main/kotlin/dev/kestrel/commands/CommandVocabulary.kt` | Task 8: the boost phrases of the command vocabulary |
| `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, `Orchestrator.kt` | Task 3: `Work.RUNNING_COMMAND`, the command's state, events, effects, reply and feedback, the answer's timer, the countdown's holds, the cancel rules, "Again" and `passOn` |
| `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt` | Task 3: the command's job, its conversation, the stopping job, the reports, `sessions` as a variable, `FeedbackBar` as a plain interface, the neutral bar text for password fields |
| `app/src/main/kotlin/dev/kestrel/app/commands/CommandRunner.kt` | Task 3: `CommandRunner`, `CommandConversation`, `CommandReport`; Task 4: `CommandPart`, `CommandEnvironment` |
| `app/src/main/kotlin/dev/kestrel/app/commands/InstantCommands.kt`, `ScreenCommands.kt`, `ScreenTargets.kt`, `CommandTexts.kt`, `CommandOptions.kt`, `TextCommands.kt` (stub), `PhoneCommands.kt` (stub) | Task 4: the executor and its parts, the targets and "Which one?", the texts, the options; Tasks 5 to 7 add the screen commands to `ScreenCommands.kt` and `ScreenTargets.kt`, and Tasks 5, 6, 7 and 9 add their texts to `CommandTexts.kt`; Task 9 adds require verbs, the numbers mode and help |
| `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt` | Task 3: `showFeedback(text, keep)`, `releaseFeedback()`; Task 4: `text`, `system`, `endWhichOne()`, `whichOneShown`; Task 6: `screenBounds`; Task 8: `snapshotBuilds`, `snapshotMillis`, `keepFresh`; Task 9: `hideFeedback()` |
| `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/FeedbackBar.kt` | Task 3: a text that stays until it is released |
| `core/screen/src/main/kotlin/dev/kestrel/screen/act/TextEditor.kt`, `SystemControls.kt` | Task 4: stubs with the final constructors and signatures, which plans 3B and 3C replace whole |
| `core/screen/src/main/kotlin/dev/kestrel/screen/model/ScreenSnapshot.kt`, `read/NodeData.kt`, `read/AccessibilityNodeData.kt`, `read/SnapshotBuilder.kt`, `act/ScreenActions.kt` | Task 5: sliders (`slider`, `isActionable`), `adjust`, `expand`, `collapse`, `dismiss`, `swipeAway` |
| `core/screen/src/main/kotlin/dev/kestrel/screen/act/GesturePaths.kt` | Task 6: gesture lengths that stay on the screen |
| `core/screen/src/main/kotlin/dev/kestrel/screen/act/ActionResult.kt`, `act/ScreenActions.kt` | Task 6: `NOTHING_SCROLLS`, which `scroll` gives when nothing scrolls (`ScreenActions.kt` also changes in Task 5, above) |
| `core/screen/src/main/kotlin/dev/kestrel/screen/read/SnapshotCache.kt` | Task 8: `latest()` |
| `core/screen/src/main/kotlin/dev/kestrel/screen/act/GlobalAction.kt` | Task 5: `ALL_APPS`, `DISMISS_SHADE` |
| `core/voice/src/test/kotlin/dev/kestrel/voice/listen/ListeningPipelineTest.kt` | Task 3: the check that the spotter's hits reach the session while Kestrel is silent (the pipeline does not change) |
| `app/src/main/kotlin/dev/kestrel/app/listen/VoiceSession.kt`, `ScreenBoostWords.kt`, `BoostWordBudget.kt` | Task 8: each utterance's boost words with the screen's labels and the command vocabulary |
| `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` | Task 3: the bar as an object and a runner that ignores commands; Task 4: `InstantCommands` and its environment; Task 8: `keepFresh` and the screen's boost words; Task 9: `SettingKeys.COMMANDS` and the stored options |
| `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`, `settings/SettingsApp.kt`, `settings/SettingsData.kt`, `settings/CommandsPage.kt`, `AndroidManifest.xml`, `res/values/strings.xml` | Task 9: `singleTop`, `EXTRA_PAGE`, `EXTRA_ASK_PERMISSION`, `ASKABLE`, the page "Instant commands", and the markers for plans 3B and 3C |
| `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt`, `scripts/hook.ps1` | Task 3: `voice-state` with the command's number, kind and outcome (neither file is in the design's list for 3A; plan 3C's writer takes `hook.ps1` as Task 3 leaves it) |
| `testapp/src/main/kotlin/dev/kestrel/testapp/CommandsActivity.kt`, `NotificationActivity.kt`, `testapp/src/main/res/values/ids.xml`, `testapp/src/main/AndroidManifest.xml` | Task 4: the commands fixture and the markers for plans 3B and 3C; Task 5: the test app's notification (`NotificationActivity`, `POST_NOTIFICATIONS`) |
| `app/src/androidTest/kotlin/dev/kestrel/app/commands/CommandTests.kt`, `TextSessions.kt` | Task 4: the helper that drives commands through the same path as speech, with a voice session that needs no models; Task 9: `CommandTests.start()` resets the options |
| `scripts/command-test.ps1`, `scripts/lib/Kestrel.ps1`, `scripts/tests/CommandTest.Tests.ps1`, `AGENTS.md` | Task 4: the only way to run the command device tests, with every restore that plans 3A to 3C need |
| `docs/superpowers/specs/2026-11-12-kestrel-design.md`, `README.md` | The spec's sections of design D18's 3A part, each in the task that changes the behaviour (7.4 and 9.6's limits in Task 1; 7.3, 8.9 and 9.5 in Task 3; 9.4, 9.6, 14.1, 9.8, Appendix A's "Cancel on touch" and 16.3 in Task 4; 9.3's sideways swipes in Task 6; 8.3 in Task 8; 15 and Appendix A's help rows in Task 9); the README's section `## Instant commands` and its test example in Task 9 |

Tests: `core/commands/src/test/kotlin/dev/kestrel/commands/text/NormalizerTest.kt`, `JaroWinklerTest.kt`, `PhoneticKeyTest.kt`, `LabelMatcherTest.kt` (JVM, new, Task 1); `core/commands/src/test/kotlin/dev/kestrel/commands/CommandModelTest.kt`, `SpokenNumbersTest.kt`, `CommandParserTest.kt`, `GrammarScreenTest.kt`, `CommandHelpTest.kt` (JVM, new, Task 2); `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, `OrchestratorTest.kt`, `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, `core/voice/src/test/kotlin/dev/kestrel/voice/listen/ListeningPipelineTest.kt` (JVM, changed, Task 3); `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceHookTest.kt` (device, changed, Task 3: the pattern of the state while Kestrel is off); `app/src/test/kotlin/dev/kestrel/app/commands/ScreenTargetsTest.kt` (JVM, new, Task 4; changed in Tasks 5 and 7); `app/src/androidTest/kotlin/dev/kestrel/app/commands/TouchCommandsTest.kt` (device, new, Task 4; changed in Tasks 5 and 9); `scripts/tests/CommandTest.Tests.ps1` (Pester, new, Task 4); and the tests of Tasks 5 to 9 (`core/screen/src/test/kotlin/dev/kestrel/screen/read/FakeNodes.kt`, `SnapshotBuilderTest.kt`, `SnapshotCacheTest.kt`, `act/GesturePathsTest.kt`, `app/src/test/kotlin/dev/kestrel/app/listen/BoostWordBudgetTest.kt`, device `GestureCommandsTest`, `OverlayCommandsTest` (changed in Task 9 too), `HelpCommandsTest`, `voice/ScreenBoostWordsTest`, `settings/SettingsAppTest`, `MainActivityTest`), and `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceSessionFailuresTest.kt` (device, changed, Task 8: one expectation).

---
### Task 1: `:core:commands`, the normaliser and the matcher

**Files:**
- Modify: `settings.gradle.kts` (`include(":core:commands")`), `app/build.gradle.kts` (the dependency)
- Create: `core/commands/build.gradle.kts`
- Create: `core/commands/src/main/kotlin/dev/kestrel/commands/text/Normalizer.kt`, `JaroWinkler.kt`, `PhoneticKey.kt`, `LabelMatcher.kt`, `MatchRules.kt`
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 7.4 and 9.6)
- Test: `core/commands/src/test/kotlin/dev/kestrel/commands/text/NormalizerTest.kt`, `JaroWinklerTest.kt`, `PhoneticKeyTest.kt`, `LabelMatcherTest.kt`

**Interfaces:**
- Consumes: the convention plugin `kestrel.jvm.library` (as `:core:safety` uses it) and `libs.junit`.
- Produces (module `:core:commands`, package `dev.kestrel.commands.text`): `object Normalizer { fun normalize(text: String): String; fun words(text: String): List<String>; fun withOffsets(text: String): Folded }` and the internal `Normalizer.transliterate(text)` and `Normalizer.fold(text)`; `class Folded(val text: String, val starts: IntArray, val ends: IntArray)` with `val words: List<IntRange>`, `fun source(from: Int, to: Int): IntRange`, `fun span(fromWord: Int, toWord: Int): IntRange`; `object JaroWinkler { fun similarity(a: String, b: String): Double }`; `object PhoneticKey { fun of(text: String): String }` and the internal `PhoneticKey.spelled(text)`; `data class MatchCandidate(val id: String, val names: List<String>)`; `enum class Strictness { NORMAL, STRICT }`; `sealed interface MatchDecision { data class Found(val id: String); data class Ambiguous(val ids: List<String>); data object NotFound }`; `object LabelMatcher { fun score(spoken: String, name: String, strictness: Strictness = Strictness.NORMAL): Double; fun decide(spoken: String, candidates: List<MatchCandidate>, strictness: Strictness): MatchDecision }` and the internal `LabelMatcher.accepts(best: Double, second: Double?, strict: Boolean): Boolean`; `object MatchRules` with the constants `ACCEPT`, `MARGIN`, `STRICT_ACCEPT`, `STRICT_MARGIN`, `CLOSE`, `NEAR`, `MAX_AMBIGUOUS`, `SHORT_LETTERS`, `PREFIX_SCORE`, `WORD_SIMILARITY`, `PHONETIC_WORD_LETTERS`, `PHONETIC_WORD_SIMILARITY`, `JOINED_WEIGHT`, `WORDS_WEIGHT`, `PHONETIC_WEIGHT`, `TOLERANCE`. Task 2 builds the parser on `Normalizer` and `Folded`; Task 4 decides targets with `LabelMatcher`; plan 3B's `TextSearch` maps phrases back to a field's offsets with `Folded`; plan 3C tunes only the values in `MatchRules.kt`.

Design D1, D3 and D4. `:core:commands` is a plain Kotlin module like `:core:safety`: its tests run on the JVM. The normaliser folds every text that Kestrel compares (spoken words, screen labels, app names, contact names) in the same way: lower case, Cyrillic in Latin letters (the Serbian table, with the Russian and Ukrainian extras), no diacritics (by Unicode decomposition, with the explicit rules đ→dj, ł→l, ø→o, æ→ae, œ→oe and ß→ss), no apostrophes ("don't" becomes "dont", as in `Requests`), "okay", "OK" and "O.K." as the same word "ok" (the recogniser writes "okay" where a screen says "OK"; the controller's change to design D4, whose pair "ok"/"Okay" no longer must stay apart), and every other character that is not a letter or a digit, every Unicode dash and the non-breaking hyphen U+2011 included, as a space. `withOffsets` keeps, for each folded character, the range of the original text that made it, so that Task 2's parser cuts payloads from the original transcript with the recogniser's case and punctuation, and plan 3B maps a phrase found in a field back to the field's offsets. The matcher scores a spoken phrase against a name with `0.40 · JaroWinkler(joined) + 0.35 · words + 0.25 · phonetic` (research r23); an exact match after folding, also with the words joined ("wi fi" and "Wi‑Fi"), scores 1.0, and a phrase whose words are the first words of a name scores at least 0.85. The spoken phrase's leading articles ("the", "a", "an") are dropped, except that a name which starts with an article itself ("The Guardian") is still an exact match. Two words count as the same when they are equal, equal without a final "s" (on words longer than 3 letters), have a Jaro–Winkler similarity of at least 0.92, or, with `Strictness.NORMAL` only, when both have at least 5 letters, have the same phonetic key, and are at least 0.80 similar once the key has respelled them. The last rule is this plan's addition to design D4, as the controller decided it: without it, "tap veiber" scores 0.59 against "Viber" and "Giorgie" 0.50 against "Đorđe", and neither reaches the limits that D4's tests require. It never applies to bare labels, contacts or other strict matches, because one word said to someone in the room ("black", "dealt") must not tap a label that only sounds like it ("Block", "Delete"), and the check of the respelled forms keeps the key from accepting a word on its own ("later" is not "Letter"). `decide` acts at once on the only candidate whose name the owner said exactly (after folding, also with the words joined), whatever near misses are on the screen ("Ana" taps "Ana" next to "Anja"), and asks "Which one?" with the exact matches only when there are two or more; this is the controller's rule for plan 3A. Without an exact match, it acts only on a clear winner (`NORMAL`: at least 0.80 and 0.10 ahead; `STRICT`, and every phrase or best name of 4 letters or fewer: at least 0.90 and 0.15 ahead), treats two equal best scores as ambiguous, offers in "Which one?" every candidate from 0.60 up within 0.10 of the best (so a single near miss, such as "Anja" for "Ana", asks with its one number, which works as a confirmation), and compares every score and margin with a tolerance of 1e-9, because in doubles 0.95 − 0.80 is 0.1499999999999999.

- [ ] **Step 1: Add the module**

In `settings.gradle.kts`, replace

```kotlin
include(":core:privacy")
```

with

```kotlin
include(":core:commands")
include(":core:privacy")
```

Create `core/commands/build.gradle.kts`:

```kotlin
plugins {
    id("kestrel.jvm.library")
}

dependencies {
    testImplementation(libs.junit)
}
```

In `app/build.gradle.kts`, replace

```kotlin
    implementation(project(":core:privacy"))
```

with

```kotlin
    implementation(project(":core:commands"))
    implementation(project(":core:privacy"))
```

- [ ] **Step 2: Write the failing tests**

Create `core/commands/src/test/kotlin/dev/kestrel/commands/text/NormalizerTest.kt`:

```kotlin
package dev.kestrel.commands.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NormalizerTest {
    @Test
    fun lowercasesAndTurnsPunctuationIntoSingleSpaces() {
        assertEquals("tap send now", Normalizer.normalize("  Tap SEND, now!  "))
        assertEquals(listOf("scroll", "down"), Normalizer.words("Scroll\tdown."))
        assertEquals(emptyList<String>(), Normalizer.words(" ?! "))
    }

    @Test
    fun deletesApostrophesAsRequestsDoes() {
        assertEquals("dont stop", Normalizer.normalize("Don't stop"))
        assertEquals("dont stop", Normalizer.normalize("Don\u2019t stop"))
    }

    @Test
    fun everyDashAndTheNonBreakingHyphenSeparateWords() {
        for (dash in listOf("-", "\u2010", "\u2011", "\u2012", "\u2013", "\u2014")) {
            assertEquals("wi fi", Normalizer.normalize("Wi${dash}Fi"))
        }
    }

    @Test
    fun foldsDiacriticsWithTheExplicitRules() {
        assertEquals("cevapcici sargarepa zaba", Normalizer.normalize("Ćevapčići šargarepa žaba"))
        assertEquals("djordje", Normalizer.normalize("Đorđe"))
        assertEquals("strasse lodz aero oeuvre", Normalizer.normalize("Straße Łódź Ærø Œuvre"))
        assertEquals("cafe", Normalizer.normalize("Cafe\u0301"))
    }

    @Test
    fun transliteratesSerbianRussianAndUkrainianCyrillic() {
        assertEquals("djordje", Normalizer.normalize("Ђорђе"))
        assertEquals("ana petrovic", Normalizer.normalize("Ана Петровић"))
        assertEquals("ljubav njiva dzep", Normalizer.normalize("Љубав њива џеп"))
        assertEquals("scuka julja jasa", Normalizer.normalize("щука, Юля, Яша"))
        assertEquals("obekt", Normalizer.normalize("объект"))
        assertEquals("jevropa jizak", Normalizer.normalize("Європа їжак"))
    }

    @Test
    fun withOffsetsMapsEachFoldedCharacterToTheOriginalText() {
        val original = "Tap «Ђорђе», please"
        val folded = Normalizer.withOffsets(original)

        assertEquals("tap djordje please", folded.text)
        assertEquals(listOf(0..2, 4..10, 12..17), folded.words)
        // The two folded letters "dj" both come from "Ђ".
        assertEquals(5..5, folded.source(4, 6))
        assertEquals("Ђорђе", original.substring(folded.span(1, 2)))
        assertEquals("Ђорђе», please", original.substring(folded.span(1, 3)))
        assertFalse(folded.toString().contains("djordje"))
    }

    @Test
    fun okayOkAndOKWithDotsAreTheSameWord() {
        assertEquals("ok tap ok", Normalizer.normalize("Okay, tap OK"))
        assertEquals("ok", Normalizer.normalize("O.K."))
        val original = "Okay, tap O.K."
        val folded = Normalizer.withOffsets(original)
        assertEquals("ok tap ok", folded.text)
        assertEquals("Okay", original.substring(folded.span(0, 1)))
        assertEquals("O.K", original.substring(folded.span(2, 3)))
        // Two letters with a space between them stay two words.
        assertEquals(listOf("o", "k"), Normalizer.words("O K"))
    }

    @Test
    fun withOffsetsKeepsTheRecognisersCaseAndInnerPunctuation() {
        val original = "type Hello, World!"
        val folded = Normalizer.withOffsets(original)

        assertEquals("Hello, World", original.substring(folded.span(1, 3)))
    }
}
```

Create `core/commands/src/test/kotlin/dev/kestrel/commands/text/JaroWinklerTest.kt`:

```kotlin
package dev.kestrel.commands.text

import org.junit.Assert.assertEquals
import org.junit.Test

class JaroWinklerTest {
    @Test
    fun givesTheKnownValues() {
        assertEquals(0.9611, JaroWinkler.similarity("martha", "marhta"), 0.0001)
        assertEquals(0.8133, JaroWinkler.similarity("dixon", "dicksonx"), 0.0001)
        assertEquals(0.8667, JaroWinkler.similarity("save", "safe"), 0.0001)
    }

    @Test
    fun equalAndEmptyStrings() {
        assertEquals(1.0, JaroWinkler.similarity("send", "send"), 0.0)
        assertEquals(1.0, JaroWinkler.similarity("", ""), 0.0)
        assertEquals(0.0, JaroWinkler.similarity("send", ""), 0.0)
        assertEquals(0.0, JaroWinkler.similarity("abc", "xyz"), 0.0)
    }
}
```

Create `core/commands/src/test/kotlin/dev/kestrel/commands/text/PhoneticKeyTest.kt`:

```kotlin
package dev.kestrel.commands.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PhoneticKeyTest {
    @Test
    fun serbianNamesAndTheirEnglishSpellingsShareAKey() {
        assertEquals("jrj", PhoneticKey.of("Đorđe"))
        assertEquals("jrj", PhoneticKey.of("Ђорђе"))
        assertEquals("jrj", PhoneticKey.of("Georgie"))
        assertEquals("jrj", PhoneticKey.of("Giorgie"))
        assertEquals("tnch", PhoneticKey.of("Tonči"))
        assertEquals("tnch", PhoneticKey.of("Tonchi"))
    }

    @Test
    fun keepsALeadingVowelDropsTheOthersAndCollapsesRepeats() {
        assertEquals("vbr", PhoneticKey.of("Viber"))
        assertEquals("vbr", PhoneticKey.of("veiber"))
        assertEquals("an", PhoneticKey.of("Anna"))
        assertEquals("wf", PhoneticKey.of("Wi-Fi"))
    }

    @Test
    fun voicedAndUnvoicedPairsStayApart() {
        assertNotEquals(PhoneticKey.of("Save"), PhoneticKey.of("Safe"))
        assertNotEquals(PhoneticKey.of("Back"), PhoneticKey.of("Pack"))
    }
}
```

Create `core/commands/src/test/kotlin/dev/kestrel/commands/text/LabelMatcherTest.kt`:

```kotlin
package dev.kestrel.commands.text

import dev.kestrel.commands.text.MatchDecision.Ambiguous
import dev.kestrel.commands.text.MatchDecision.Found
import dev.kestrel.commands.text.MatchDecision.NotFound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelMatcherTest {
    private fun alone(spoken: String, name: String, strictness: Strictness = Strictness.NORMAL) =
        LabelMatcher.decide(spoken, listOf(MatchCandidate("x", listOf(name))), strictness)

    private fun among(spoken: String, vararg names: String, strictness: Strictness = Strictness.NORMAL) =
        LabelMatcher.decide(spoken, names.map { MatchCandidate(it, listOf(it)) }, strictness)

    @Test
    fun thePairsOfResearchR23Match() {
        for ((spoken, name) in listOf(
            "wi fi" to "Wi-Fi",
            "wifi" to "Wi-Fi",
            "wi fi" to "Wi\u2011Fi",
            "setting" to "Settings",
            "color" to "Colour",
            "Giorgie" to "Đorđe",
            "Georgie" to "Đorđe",
            "Tonchi" to "Tonči",
            "viber" to "Viber",
            "veiber" to "Viber",
            "Delet" to "Delete",
            "djordje" to "Ђорђе",
        )) {
            assertEquals("$spoken to $name", Found("x"), alone(spoken, name))
        }
        assertEquals(Found("Delete"), among("delet", "Delete", "Send"))
    }

    @Test
    fun thePairsThatMustNotMatchDoNot() {
        for ((spoken, name) in listOf(
            "save" to "Safe",
            "back" to "Pack",
            "sound" to "Sand",
            "mail" to "Male",
            "on" to "Off",
            "call" to "Cell",
            "add" to "Odd",
            "dan" to "Dina",
            "ana" to "Anja",
            "ok" to "Oka",
            "no" to "Now",
            "no" to "Nor",
        )) {
            assertNotEquals("$spoken to $name", Found("x"), alone(spoken, name))
        }
    }

    @Test
    fun theExactLabelWinsOverASimilarOne() {
        assertEquals(Found("Save"), among("save", "Save", "Safe"))
        assertEquals(Found("Send"), among("send", "Send", "Send later"))
        assertEquals(Found("Send later"), among("send later", "Send", "Send later"))
    }

    @Test
    fun theOnlyExactNameActsWhateverIsNearIt() {
        assertEquals(Found("Ana"), among("ana", "Ana", "Anja"))
        assertEquals(Found("Mark"), among("mark", "Mark", "Marko", strictness = Strictness.STRICT))
        assertEquals(Found("Marija"), among("marija", "Maria", "Marija", strictness = Strictness.STRICT))
        assertEquals(Found("Đorđe"), among("djordje", "Đorđe", "Đorđa", strictness = Strictness.STRICT))
    }

    @Test
    fun twoExactNamesAskWithOnlyThem() {
        val candidates = listOf(
            MatchCandidate("first", listOf("Settings")),
            MatchCandidate("near", listOf("Setting")),
            MatchCandidate("second", listOf("Settings")),
        )

        assertEquals(Ambiguous(listOf("first", "second")), LabelMatcher.decide("settings", candidates, Strictness.NORMAL))
        assertEquals(Ambiguous(listOf("first", "second")), LabelMatcher.decide("the settings", candidates, Strictness.STRICT))
    }

    @Test
    fun withoutAnExactNameTheNearMissesAsk() {
        assertEquals(Ambiguous(listOf("x")), alone("ana", "Anja"))
        assertEquals(Ambiguous(listOf("Maria", "Marija")), among("mariya", "Maria", "Marija", "Send"))
    }

    @Test
    fun nothingCloseIsNotFound() {
        assertEquals(NotFound, among("weather", "Send", "Save"))
        assertEquals(NotFound, LabelMatcher.decide("send", emptyList(), Strictness.NORMAL))
    }

    @Test
    fun aPhraseThatStartsANameMatchesIt() {
        assertTrue(LabelMatcher.score("ana petrović", "Ana Petrović: See you at 5") >= MatchRules.PREFIX_SCORE)
        assertEquals(Found("x"), alone("Ana Petrović", "Ana Petrović: See you at 5"))
    }

    @Test
    fun leadingArticlesOfTheSpokenPhraseAreDropped() {
        assertEquals(1.0, LabelMatcher.score("the settings", "Settings"), 0.0)
        assertEquals(1.0, LabelMatcher.score("the", "The"), 0.0)
    }

    @Test
    fun aCandidateScoresItsBestName() {
        val candidates = listOf(MatchCandidate("row", listOf("Message from Ana", "Delete")), MatchCandidate("send", listOf("Send")))

        assertEquals(Found("row"), LabelMatcher.decide("delete", candidates, Strictness.NORMAL))
    }

    @Test
    fun aPhoneticKeyNeverAcceptsAWordOnItsOwn() {
        // Bare labels and contacts are strict: a word said to someone in the room taps nothing that only sounds like it.
        for ((spoken, name) in listOf(
            "black" to "Block",
            "dealt" to "Delete",
            "stare" to "Store",
            "shore" to "Share",
            "later" to "Letter",
            "class" to "Close",
            "council" to "Cancel",
            "roast" to "Reset",
        )) {
            assertNotEquals("$spoken to $name", Found("x"), alone(spoken, name, Strictness.STRICT))
        }
        // With a verb, the words must also be similar once respelled, as "veiber" and "Viber" are.
        for ((spoken, name) in listOf("later" to "Letter", "class" to "Close", "council" to "Cancel", "roast" to "Reset")) {
            assertNotEquals("$spoken to $name", Found("x"), alone(spoken, name))
        }
        assertEquals(Found("x"), alone("veiber", "Viber"))
        assertNotEquals(Found("x"), alone("veiber", "Viber", Strictness.STRICT))
    }

    @Test
    fun okayIsAnExactMatchForOk() {
        assertEquals(1.0, LabelMatcher.score("okay", "OK"), 0.0)
        assertEquals(Found("x"), alone("Okay.", "OK", Strictness.STRICT))
        assertEquals(Found("OK"), among("okay", "OK", "Okapi", strictness = Strictness.STRICT))
        assertEquals(Found("x"), alone("ok", "O.K."))
    }

    @Test
    fun aNameThatStartsWithAnArticleIsSaidExactly() {
        assertEquals(1.0, LabelMatcher.score("The Guardian", "The Guardian"), 0.0)
        assertEquals(Found("x"), alone("The Guardian", "The Guardian", Strictness.STRICT))
        assertTrue(LabelMatcher.score("the weather", "The Weather Channel") >= MatchRules.PREFIX_SCORE)
    }

    @Test
    fun aMarginWithinTheToleranceOfItsLimitPasses() {
        for (strict in listOf(true, false)) {
            val accept = if (strict) MatchRules.STRICT_ACCEPT else MatchRules.ACCEPT
            val margin = if (strict) MatchRules.STRICT_MARGIN else MatchRules.MARGIN
            val best = accept + 0.05
            // Doubles are not exact: 0.95 - 0.80 is 0.1499999999999999.
            assertTrue(LabelMatcher.accepts(best, best - margin + MatchRules.TOLERANCE / 2, strict))
            assertFalse(LabelMatcher.accepts(best, best - margin + MatchRules.TOLERANCE * 10, strict))
            assertTrue(LabelMatcher.accepts(accept - MatchRules.TOLERANCE / 2, null, strict))
            assertFalse(LabelMatcher.accepts(accept - MatchRules.TOLERANCE * 10, null, strict))
            assertFalse(LabelMatcher.accepts(best, best, strict))
        }
    }

    @Test
    fun printsNoNames() {
        assertFalse(MatchCandidate("key:Send", listOf("Send")).toString().contains("Send"))
        assertFalse(Found("key:Send").toString().contains("Send"))
        assertFalse(Ambiguous(listOf("key:Send")).toString().contains("Send"))
    }
}
```

- [ ] **Step 3: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:commands:test`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'Normalizer'", "Unresolved reference 'JaroWinkler'", "Unresolved reference 'PhoneticKey'" and "Unresolved reference 'LabelMatcher'".

- [ ] **Step 4: Write the normaliser**

Create `core/commands/src/main/kotlin/dev/kestrel/commands/text/Normalizer.kt`:

```kotlin
package dev.kestrel.commands.text

import java.text.Normalizer as Unicode
import java.util.Locale

/**
 * Folds every text that Kestrel compares into one form (design spec, section 9.6): spoken words, screen labels, app
 * names and contact names. It lowercases, writes Cyrillic in Latin letters, removes diacritics, deletes apostrophes, and
 * turns every other character that is not a letter or a digit into a space, with single spaces between words. "Okay" and
 * "O.K." become the word "ok".
 */
object Normalizer {
    fun normalize(text: String): String = withOffsets(text).text

    fun words(text: String): List<String> = normalize(text).split(' ').filter { it.isNotEmpty() }

    /** The folded text, with the range of [text] that each folded character came from. */
    fun withOffsets(text: String): Folded {
        val folded = StringBuilder()
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        var space = false
        var lastEnd = 0
        var index = 0
        while (index < text.length) {
            val next = text.offsetByCodePoints(index, 1)
            for (char in fold(transliterate(text.substring(index, next)))) {
                if (char in APOSTROPHES) continue
                if (!char.isLetterOrDigit()) {
                    space = true
                    continue
                }
                if (space && folded.isNotEmpty()) {
                    folded.append(' ')
                    starts += lastEnd
                    ends += index
                }
                space = false
                folded.append(char)
                starts += index
                ends += next
                lastEnd = next
            }
            index = next
        }
        return okay(text, Folded(folded.toString(), starts.toIntArray(), ends.toIntArray()))
    }

    // The recogniser writes "okay" where a screen says "OK" or "O.K.": all three fold to the word "ok", which keeps the
    // offsets of the whole original word.
    private fun okay(original: String, folded: Folded): Folded {
        val text = StringBuilder()
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        val words = folded.words
        var index = 0
        while (index < words.size) {
            val range = words[index]
            val word = folded.text.substring(range)
            val next = words.getOrNull(index + 1)
            val dotted = word == "o" && next != null && folded.text.substring(next) == "k" &&
                original.substring(folded.ends[range.first], folded.starts[next.first]) == "."
            if (text.isNotEmpty()) {
                // The space keeps the offsets of the separators before the word.
                text.append(' ')
                starts += folded.starts[range.first - 1]
                ends += folded.ends[range.first - 1]
            }
            when {
                dotted || word == "okay" -> {
                    val last = if (dotted) next.last else range.last
                    text.append("ok")
                    starts += folded.starts[range.first]
                    ends += folded.ends[range.first]
                    starts += folded.starts[if (dotted) last else range.first + 1]
                    ends += folded.ends[last]
                }
                else -> for (position in range) {
                    text.append(folded.text[position])
                    starts += folded.starts[position]
                    ends += folded.ends[position]
                }
            }
            index += if (dotted) 2 else 1
        }
        return Folded(text.toString(), starts.toIntArray(), ends.toIntArray())
    }

    /** [text] in lower case, with Cyrillic letters written in Latin ones that may still carry diacritics. */
    internal fun transliterate(text: String): String = buildString {
        for (char in text.lowercase(Locale.ROOT)) append(CYRILLIC[char] ?: char)
    }

    /** [text] without diacritics, with the explicit rules first. Apostrophes and separators stay. */
    internal fun fold(text: String): String = buildString {
        for (char in text) {
            val explicit = EXPLICIT[char]
            if (explicit != null) {
                append(explicit)
                continue
            }
            for (part in Unicode.normalize(char.toString(), Unicode.Form.NFD)) {
                val type = Character.getType(part)
                if (type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() ||
                    type == Character.COMBINING_SPACING_MARK.toInt()
                ) {
                    continue
                }
                append(part)
            }
        }
    }

    private val APOSTROPHES = setOf('\'', '\u2019', '\u2018', '\u02BC')

    private val EXPLICIT = mapOf('đ' to "dj", 'ł' to "l", 'ø' to "o", 'æ' to "ae", 'œ' to "oe", 'ß' to "ss")

    // The Serbian table, then the Russian and Ukrainian letters that it lacks. The hard and soft signs disappear.
    private val CYRILLIC = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'ђ' to "đ", 'е' to "e", 'ж' to "ž", 'з' to "z",
        'и' to "i", 'ј' to "j", 'к' to "k", 'л' to "l", 'љ' to "lj", 'м' to "m", 'н' to "n", 'њ' to "nj", 'о' to "o",
        'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'ћ' to "ć", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c",
        'ч' to "č", 'џ' to "dž", 'ш' to "š",
        'й' to "j", 'ё' to "e", 'э' to "e", 'ю' to "ju", 'я' to "ja", 'ы' to "y", 'щ' to "šč", 'ъ' to "", 'ь' to "",
        'є' to "je", 'ї' to "ji", 'і' to "i", 'ґ' to "g",
    )
}

/**
 * A folded text. The folded character `i` came from the original characters `starts[i] until ends[i]`; a space came
 * from the separators between two words.
 */
class Folded(val text: String, val starts: IntArray, val ends: IntArray) {
    /** Each word's range in [text]. */
    val words: List<IntRange> = buildList {
        var start = -1
        for (index in 0..text.length) {
            val letter = index < text.length && text[index] != ' '
            if (letter && start < 0) start = index
            if (!letter && start >= 0) {
                add(start until index)
                start = -1
            }
        }
    }

    /** The range of the original text that made the folded characters `from until to`. */
    fun source(from: Int, to: Int): IntRange = starts[from] until ends[to - 1]

    /** The range of the original text that made the words `fromWord until toWord`. */
    fun span(fromWord: Int, toWord: Int): IntRange = source(words[fromWord].first, words[toWord - 1].last + 1)

    override fun toString() = "Folded(${text.length} characters, ${words.size} words)"
}
```

- [ ] **Step 5: Write the similarity, the phonetic key and the matcher**

Create `core/commands/src/main/kotlin/dev/kestrel/commands/text/JaroWinkler.kt`:

```kotlin
package dev.kestrel.commands.text

/** The Jaro–Winkler similarity, from 0.0 to 1.0, with a prefix scale of 0.1 for up to 4 letters (research r23). */
object JaroWinkler {
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val window = maxOf(0, maxOf(a.length, b.length) / 2 - 1)
        val aMatched = BooleanArray(a.length)
        val bMatched = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            for (j in maxOf(0, i - window)..minOf(b.length - 1, i + window)) {
                if (!bMatched[j] && a[i] == b[j]) {
                    aMatched[i] = true
                    bMatched[j] = true
                    matches++
                    break
                }
            }
        }
        if (matches == 0) return 0.0
        var transpositions = 0
        var k = 0
        for (i in a.indices) {
            if (!aMatched[i]) continue
            while (!bMatched[k]) k++
            if (a[i] != b[k]) transpositions++
            k++
        }
        val m = matches.toDouble()
        val jaro = (m / a.length + m / b.length + (m - transpositions / 2.0) / m) / 3
        var prefix = 0
        while (prefix < minOf(4, a.length, b.length) && a[prefix] == b[prefix]) prefix++
        return jaro + prefix * 0.1 * (1 - jaro)
    }
}
```

Create `core/commands/src/main/kotlin/dev/kestrel/commands/text/PhoneticKey.kt`:

```kotlin
package dev.kestrel.commands.text

/**
 * A phonetic key for names and labels (research r23). It respells Serbian letters before it folds them, so that "Đorđe"
 * and "Georgie" share the key "jrj", joins the words, keeps a leading vowel, drops the other vowels, collapses repeated
 * letters and never truncates. Voiced and unvoiced pairs stay apart, so that "Save" and "Safe" differ.
 */
object PhoneticKey {
    fun of(text: String): String {
        val letters = spelled(text).filter { it.isLetterOrDigit() }
        val key = StringBuilder()
        for ((index, char) in letters.withIndex()) {
            if (index > 0 && char in VOWELS) continue
            if (key.isNotEmpty() && key.last() == char) continue
            key.append(char)
        }
        return key.toString()
    }

    /** [text] respelled as the key reads it, before the vowels go: "Giorgie" and "Đorđe" become "jiorjie" and "jorje". */
    internal fun spelled(text: String): String = Normalizer.fold(respell(Normalizer.transliterate(text)))

    private fun respell(text: String): String {
        val spelled = text.replace("č", "ch").replace("ć", "ch").replace("š", "sh").replace("ž", "zh").replace("đ", "dj")
            .replace("dj", "j")
        return SOFT_G.replace(spelled, "j")
    }

    private val SOFT_G = Regex("g(?=[ei])")
    private const val VOWELS = "aeiouy"
}
```

Create `core/commands/src/main/kotlin/dev/kestrel/commands/text/MatchRules.kt`:

```kotlin
package dev.kestrel.commands.text

/**
 * The matcher's limits and weights (design spec, section 9.6): research r23's starting points. Plan 3C's corpus run may
 * tune the values; nothing else changes them.
 */
object MatchRules {
    /** The best score that acts with `Strictness.NORMAL`, and how far it must beat the second best. */
    const val ACCEPT = 0.80
    const val MARGIN = 0.10

    /** The same for `Strictness.STRICT`, bare labels, contacts and every phrase or best name of 4 letters or fewer. */
    const val STRICT_ACCEPT = 0.90
    const val STRICT_MARGIN = 0.15

    /** A candidate at least this good can be offered in "Which one?"; below it for every candidate, nothing was found. */
    const val CLOSE = 0.60

    /** "Which one?" offers the candidates within this distance of the best, at most [MAX_AMBIGUOUS] of them. */
    const val NEAR = 0.10
    const val MAX_AMBIGUOUS = 20

    /** A spoken phrase or best name with at most this many letters and digits takes the strict limits. */
    const val SHORT_LETTERS = 4

    /** The least score of a phrase whose words are the first words of a name. */
    const val PREFIX_SCORE = 0.85

    /** Two words count as the same with at least this Jaro–Winkler similarity. */
    const val WORD_SIMILARITY = 0.92

    /**
     * With `Strictness.NORMAL` only, two words of at least this many letters count as the same when their phonetic keys
     * are equal and their respelled forms are at least [PHONETIC_WORD_SIMILARITY] similar, so that the key never accepts
     * a word on its own.
     */
    const val PHONETIC_WORD_LETTERS = 5
    const val PHONETIC_WORD_SIMILARITY = 0.80

    const val JOINED_WEIGHT = 0.40
    const val WORDS_WEIGHT = 0.35
    const val PHONETIC_WEIGHT = 0.25

    /** Doubles are not exact: 0.95 - 0.80 is 0.1499999999999999. */
    const val TOLERANCE = 1e-9
}
```

Create `core/commands/src/main/kotlin/dev/kestrel/commands/text/LabelMatcher.kt`:

```kotlin
package dev.kestrel.commands.text

import kotlin.math.abs

/** Something that the owner may name: an element on the screen, an app or a contact, with all its names. */
data class MatchCandidate(val id: String, val names: List<String>) {
    override fun toString() = "MatchCandidate(id of ${id.length} characters, ${names.size} names)"
}

enum class Strictness { NORMAL, STRICT }

sealed interface MatchDecision {
    data class Found(val id: String) : MatchDecision {
        override fun toString() = "Found(id of ${id.length} characters)"
    }

    data class Ambiguous(val ids: List<String>) : MatchDecision {
        override fun toString() = "Ambiguous(${ids.size} candidates)"
    }

    data object NotFound : MatchDecision
}

/**
 * Matches spoken words to names (design spec, section 9.6; research r23). It folds both sides with [Normalizer] and
 * scores them with the joined Jaro–Winkler similarity, the word overlap and the phonetic key.
 */
object LabelMatcher {
    private val ARTICLES = setOf("the", "a", "an")

    // The score of an exact match after folding, also with the words joined; no other match reaches it.
    private const val EXACT = 1.0

    /**
     * How well [spoken] names [name], from 0.0 to 1.0. With [Strictness.STRICT], two words never count as the same for
     * their phonetic key.
     */
    fun score(spoken: String, name: String, strictness: Strictness = Strictness.NORMAL): Double {
        val folded = Normalizer.withOffsets(spoken)
        val all = folded.words.map { folded.text.substring(it) }
        val articles = all.takeWhile { it in ARTICLES }.size.takeIf { it < all.size } ?: 0
        val said = all.drop(articles)
        val named = Normalizer.words(name)
        if (said.isEmpty() || named.isEmpty()) return 0.0
        val joined = said.joinToString("")
        val namedJoined = named.joinToString("")
        // A name may start with an article itself, as "The Guardian" does.
        if (joined == namedJoined || all.joinToString("") == namedJoined) return EXACT
        val saidText = spoken.substring(folded.span(articles, all.size))
        val total = MatchRules.JOINED_WEIGHT * JaroWinkler.similarity(joined, namedJoined) +
            MatchRules.WORDS_WEIGHT * overlap(said, named, phoneticWords = strictness == Strictness.NORMAL) +
            MatchRules.PHONETIC_WEIGHT * phonetic(saidText, name)
        val prefix = listOf(said, all).any { it.size < named.size && named.subList(0, it.size) == it }
        return if (prefix) maxOf(total, MatchRules.PREFIX_SCORE) else total
    }

    /**
     * Decides which of [candidates] [spoken] names: [MatchDecision.Found] for the only exact match or else a clear winner,
     * [MatchDecision.Ambiguous] for two or more exact matches or else the close ones, and [MatchDecision.NotFound] when no
     * candidate comes close.
     */
    fun decide(spoken: String, candidates: List<MatchCandidate>, strictness: Strictness): MatchDecision {
        val ranked = candidates.mapNotNull { candidate ->
            candidate.names.map { Scored(candidate.id, score(spoken, it, strictness), it) }.maxByOrNull { it.score }
        }.sortedByDescending { it.score }
        val best = ranked.firstOrNull() ?: return MatchDecision.NotFound
        // The only name that the owner said exactly acts, whatever near misses the screen also shows.
        val exact = ranked.filter { it.score == EXACT }
        if (exact.size == 1) return MatchDecision.Found(exact[0].id)
        if (exact.size > 1) return MatchDecision.Ambiguous(exact.take(MatchRules.MAX_AMBIGUOUS).map { it.id })
        val second = ranked.getOrNull(1)?.score
        val strict = strictness == Strictness.STRICT || letters(spoken) <= MatchRules.SHORT_LETTERS ||
            letters(best.name) <= MatchRules.SHORT_LETTERS
        if (accepts(best.score, second, strict)) return MatchDecision.Found(best.id)
        if (best.score + MatchRules.TOLERANCE < MatchRules.CLOSE) return MatchDecision.NotFound
        val close = ranked.filter {
            it.score + MatchRules.TOLERANCE >= MatchRules.CLOSE && best.score - it.score <= MatchRules.NEAR + MatchRules.TOLERANCE
        }
        return MatchDecision.Ambiguous(close.take(MatchRules.MAX_AMBIGUOUS).map { it.id })
    }

    /** Whether a best score of [best], with [second] behind it, acts. Two equal best scores never act. */
    internal fun accepts(best: Double, second: Double?, strict: Boolean): Boolean {
        val accept = if (strict) MatchRules.STRICT_ACCEPT else MatchRules.ACCEPT
        val margin = if (strict) MatchRules.STRICT_MARGIN else MatchRules.MARGIN
        if (second != null && abs(best - second) <= MatchRules.TOLERANCE) return false
        return best + MatchRules.TOLERANCE >= accept && (second == null || best - second + MatchRules.TOLERANCE >= margin)
    }

    private class Scored(val id: String, val score: Double, val name: String)

    // The Dice coefficient over words; each word of the name matches at most one spoken word.
    private fun overlap(said: List<String>, named: List<String>, phoneticWords: Boolean): Double {
        val used = BooleanArray(named.size)
        var pairs = 0
        for (word in said) {
            val index = named.indices.firstOrNull { !used[it] && sameWord(word, named[it], phoneticWords) } ?: continue
            used[index] = true
            pairs++
        }
        return 2.0 * pairs / (said.size + named.size)
    }

    private fun sameWord(a: String, b: String, phoneticWords: Boolean): Boolean =
        a == b || stem(a) == stem(b) || JaroWinkler.similarity(a, b) >= MatchRules.WORD_SIMILARITY ||
            (phoneticWords && soundTheSame(a, b))

    // This plan's addition to design D4: "veiber" is "Viber". The respelled forms must be similar too, so that the key
    // never accepts a word on its own ("later" is not "Letter").
    private fun soundTheSame(a: String, b: String): Boolean =
        minOf(a.length, b.length) >= MatchRules.PHONETIC_WORD_LETTERS && PhoneticKey.of(a) == PhoneticKey.of(b) &&
            JaroWinkler.similarity(PhoneticKey.spelled(a), PhoneticKey.spelled(b)) + MatchRules.TOLERANCE >=
            MatchRules.PHONETIC_WORD_SIMILARITY

    private fun stem(word: String): String = if (word.length > 3 && word.endsWith('s')) word.dropLast(1) else word

    private fun phonetic(a: String, b: String): Double {
        val first = PhoneticKey.of(a)
        val second = PhoneticKey.of(b)
        if (first.isEmpty() && second.isEmpty()) return 1.0
        if (first.isEmpty() || second.isEmpty()) return 0.0
        return 1.0 - editDistance(first, second).toDouble() / maxOf(first.length, second.length)
    }

    private fun letters(text: String): Int {
        val words = Normalizer.words(text)
        return words.dropWhile { it in ARTICLES }.ifEmpty { words }.sumOf { it.length }
    }

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                current[j + 1] = minOf(previous[j + 1] + 1, current[j] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
            }
            previous = current
        }
        return previous[b.length]
    }
}
```

- [ ] **Step 6: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:commands:test`
Expected: `BUILD SUCCESSFUL`, with 28 tests in `:core:commands`: `NormalizerTest` 8, `JaroWinklerTest` 2, `PhoneticKeyTest` 3 and `LabelMatcherTest` 15.

- [ ] **Step 7: Update the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace

```markdown
| `:core:commands` | The instant-command grammar, the parser and the matching of spoken words to screen elements | Yes |
```

with

```markdown
| `:core:commands` | The instant-command grammar, the parser, the matching of spoken words to screen elements, apps and contacts, the editing model and dictation, and the phone-number rules. The contacts themselves are read in `:app` until `:core:datatools` exists (sub-project 5) | Yes |
```

and replace

```markdown
- **Scoring:** each candidate gets a score from word overlap, Jaro–Winkler similarity and a phonetic key.
```

with

```markdown
- **Scoring:** each candidate gets 0.40 × the Jaro–Winkler similarity of the words joined without spaces, 0.35 × the word overlap (the Dice coefficient; two words match when they are equal, equal without a final "s", or at least 0.92 similar, and, for a command with a verb but never for bare labels or contacts, when both have 5 letters or more, the same phonetic key and spellings at least 0.80 similar once respelled) and 0.25 × the similarity of an in-house phonetic key that respells Serbian letters first ("Đorđe" and "Georgie" share it). The leading "the", "a" or "an" of the spoken phrase is dropped, and "okay", "OK" and "O.K." are the same word, so "okay" taps an "OK" button. An exact match after normalising, with or without those articles, scores 1.0, and a phrase whose words start a name, such as "Ana Petrović" for a chat row "Ana Petrović: See you at 5", scores at least 0.85.
- **Limits:** a name that the owner says exactly (after normalising) acts at once when no other candidate has it, whatever near misses the screen also shows, so "Ana" taps "Ana" next to "Anja"; two or more exact matches ask "Which one?" with those only. Without an exact match, Kestrel acts when the best score is at least 0.80 and beats the second best by at least 0.10. For bare labels, contacts, and every phrase or name of 4 letters or fewer, it needs 0.90 and 0.15. Two equal best scores never act. Candidates from 0.60 up that are within 0.10 of the best are offered in "Which one?"; below 0.60 for all, nothing was found; so a single near miss, such as "Anja" for "Ana", asks "Which one?" with its one number, which works as a confirmation. These are research r23's starting values, which the corpus run of sub-project 3 checks.
```

- [ ] **Step 8: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:commands` 28; `:app` 264, `:core:voice` 139, `:core:screen` 47, `:core:privacy` 48 and `:core:safety` 2, as before this task), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), and the audit, which now also covers `:core:commands`.

- [ ] **Step 9: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 10: Commit**

```powershell
git add settings.gradle.kts app/build.gradle.kts core/commands/build.gradle.kts core/commands/src/main/kotlin/dev/kestrel/commands/text/Normalizer.kt core/commands/src/main/kotlin/dev/kestrel/commands/text/JaroWinkler.kt core/commands/src/main/kotlin/dev/kestrel/commands/text/PhoneticKey.kt core/commands/src/main/kotlin/dev/kestrel/commands/text/LabelMatcher.kt core/commands/src/main/kotlin/dev/kestrel/commands/text/MatchRules.kt core/commands/src/test/kotlin/dev/kestrel/commands/text/NormalizerTest.kt core/commands/src/test/kotlin/dev/kestrel/commands/text/JaroWinklerTest.kt core/commands/src/test/kotlin/dev/kestrel/commands/text/PhoneticKeyTest.kt core/commands/src/test/kotlin/dev/kestrel/commands/text/LabelMatcherTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(commands): add the module :core:commands with the normaliser, the phonetic key and the label matcher" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---
### Task 2: The command model, the parser, the screen grammar and help

**Files:**
- Create: `core/commands/src/main/kotlin/dev/kestrel/commands/Command.kt`, `TextCommandModel.kt`, `PhoneCommandModel.kt`
- Create: `core/commands/src/main/kotlin/dev/kestrel/commands/CommandParser.kt`, `SpokenNumbers.kt`, `GrammarScreen.kt`, `GrammarText.kt`, `GrammarPhone.kt`
- Create: `core/commands/src/main/kotlin/dev/kestrel/commands/CommandResult.kt`, `CommandHelp.kt`, `HelpScreen.kt`, `HelpText.kt`, `HelpPhone.kt`
- Test: `core/commands/src/test/kotlin/dev/kestrel/commands/CommandModelTest.kt`, `SpokenNumbersTest.kt`, `CommandParserTest.kt`, `GrammarScreenTest.kt`, `CommandHelpTest.kt`

**Interfaces:**
- Consumes: Task 1's `Normalizer.words(text)`, `Normalizer.withOffsets(text)` and `Folded.span(fromWord, toWord)`.
- Produces (package `dev.kestrel.commands`): `sealed interface Target { data class Label(val spoken: String); data class Number(val number: Int) }`, `enum class Direction { UP, DOWN, LEFT, RIGHT }`, `enum class Edge { TOP, BOTTOM, LEFT, RIGHT }`, `enum class HelpKind { SUMMARY, ALL, TUTORIAL }`, and `sealed interface Command` with the nested variants `Tap(target)`, `LongPress(target)`, `Bare(text, number)` (with `Bare.of(text)`), `Check(target?, on)`, `Switch(target?, on)`, `Expand(target?)`, `Collapse(target?)`, `Dismiss(target)`, `Drag(from, to?)`, `Scroll(direction, target?)`, `ScrollToEnd(top, target?)`, `SwipePage(forward)`, `SwipeFromEdge(edge)`, `Swipe(direction, target?)`, `Pinch(out, target?)`, `Slider(increase, target?)`, `ShowNumbers`, `HideNumbers`, `ShowLabels`, `HideLabels`, `ShowGrid`, `HideGrid`, `MoreSquares`, `FewerSquares`, `WhatIs(number)`, `CommandsFor(target)`, `Help(kind)`, `Again`; in `TextCommandModel.kt` `TextUnit`, `TextScope`, `Place`, `TextCase`, `ClipboardAction` and `sealed interface TextCommand : Command` with `StartEditing(target?)`, `StopEditing`, `Type(text)`, `Undo`, `Redo`, `Insert(text, place)`, `Replace(old, new)`, `ReplaceBetween(from, to, new)`, `ChangeCase(case, phrase)`, `FormatEmail`, `Delete(scope?)`, `MoveCursor(place)`, `Select(scope)`, `Unselect`, `Clipboard(action)`, `Keyboard(show)`, `NewLine`, `ShowPassword`, `Search(query)`; in `PhoneCommandModel.kt` `GlobalCommand`, `RadioKind`, `StreamKind`, `MagnifyAction`, `CallTarget { Number(digits); Name(name, kind?) }` and `sealed interface PhoneCommand : Command` with `Global(action)`, `OpenApp(name, work)`, `PreviousApp`, `CircleToSearch`, `Radio(radio, on)`, `Volume(stream?, up)`, `Mute(stream?, on)`, `Silence`, `Media(play)`, `Magnify(action)`, `Pan(direction)`, `Call(who)`, `CopyPhoneNumber`. `object CommandParser { fun parse(text: String): Command? }`; `internal object GrammarScreen`, `GrammarText`, `GrammarPhone`, each with `fun fixed(words: List<String>, text: String): Command?` and `fun targeted(words: List<String>, text: String): Command?`; `internal object TargetWords { fun read(words: List<String>, text: String, from: Int, to: Int = words.size): Target? }`; `internal fun cutWords(text: String, from: Int, to: Int): String`; `object SpokenNumbers { fun number(words: List<String>, lone: Boolean = false): Int?; fun answer(text: String): Int? }`; `data class CommandResult(val outcome: Outcome, val say: String? = null, val show: String? = null, val repeatable: Boolean = false, val passOn: String? = null, val acted: Command? = null)`; `enum class Outcome { DONE, FAILED, IGNORED, CANCELLED }`; `data class HelpGroup(val title: String, val examples: List<String>)`; `object CommandHelp { val groups: List<HelpGroup> }`; `internal object HelpScreen`, `HelpText`, `HelpPhone`, each with `val groups: List<HelpGroup>`. After this task the three model files are frozen: plans 3B and 3C change none of them. Task 3's Rules parse with `CommandParser.parse`; Task 4's executor runs the variants of `Command.kt`; Task 9's settings page lists `CommandHelp.groups`; plan 3B replaces `GrammarText.kt` and `HelpText.kt` whole, and plan 3C `GrammarPhone.kt` and `HelpPhone.kt`.

Design D5, D7 and "Shared names". The model holds every variant of plans 3A, 3B and 3C, in three files, so that plans 3B and 3C can be written at the same time against it and never change it. The variants of `Command.kt` are nested in `Command`, the text variants in `TextCommand` and the phone variants in `PhoneCommand`, a sealed sub-interface that design D5 does not name: it keeps the phone variants nested, so that `PhoneCommand.Call` and `PhoneCommand.Mute` never clash with plan 2D's `Request.Call` and `Request.Mute` in the Rules. Every variant that carries the owner's words or digits prints only lengths, counts and kinds (design D17). The parser matches only whole utterances, in two phases, so that no group takes another group's phrase: first the fixed phrases of every group (screen, text, phone), then the rules with a target or a payload (phone, text, screen). Whatever no rule takes becomes `Command.Bare` with the whole original transcript: a bare label, a bare number or dictation, which the executor decides. Only when the whole utterance is `Bare` does the parser try again without the polite words "please", "now", "ok", "okay", "could you" and "can you" at its start and end, and it keeps that result only when it is not `Bare`; so polite words never leave a payload or a `Bare`, with two exceptions: a polite word after a spoken number is no part of it ("tap 5 please" taps number 5), and the answer to a question may have polite words around its number ("Two, please"). A label keeps its polite words, as design D5 says ("tap Send now"). Payloads and labels are cut from the original transcript (`cutWords`), so that they keep the recogniser's case and punctuation. Spoken numbers are digits, "number 21" and words up to 999; right after a verb or "number" (also in a bare "Number to"), and as the whole answer to a question, a lone "to" or "too" counts as 2, "for" as 4, "won" as 1 and "ate" as 8. `GrammarText` and `GrammarPhone` are stubs that take nothing, so until plans 3B and 3C "type hello", "delete" or "open Viber" are bare labels. None of this task's tests uses a phrase that plan 3C's `GrammarPhone` will take (design D5's list), so they keep passing after plan 3C. A drag whose last word is "to" ("drag 3 to") has no place, so Kestrel asks "Drag it where?" instead of looking for a label "3 to". The fixed phrases are those of Appendix A and design D7; "what can I do", "turn on" and "turn off" alone are not among them, because conversation or a recogniser that loses "Kestrel" in "Kestrel, turn off" could trigger them. The help list joins one file per plan; `CommandHelpTest` parses every example of all three and fails on a `Bare`, so that the help cannot drift from the grammar.

- [ ] **Step 1: Write the failing tests**

Create `core/commands/src/test/kotlin/dev/kestrel/commands/CommandModelTest.kt`:

```kotlin
package dev.kestrel.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CommandModelTest {
    @Test
    fun theOwnersWordsNeverReachToString() {
        val secret = "Ana Petrović 0641234567"
        val printed = listOf(
            Target.Label(secret),
            Command.Tap(Target.Label(secret)),
            Command.Bare(secret, null),
            Command.CommandsFor(Target.Label(secret)),
            TextScope.Phrase(secret),
            TextScope.FromTo(secret, secret),
            TextScope.Between(secret, secret),
            Place.Before(secret),
            Place.After(secret),
            Place.Between(secret, secret),
            TextCommand.Type(secret),
            TextCommand.Insert(secret, Place.Before(secret)),
            TextCommand.Replace(secret, secret),
            TextCommand.ReplaceBetween(secret, secret, secret),
            TextCommand.ChangeCase(TextCase.UPPER, secret),
            TextCommand.Delete(TextScope.Phrase(secret)),
            TextCommand.Search(secret),
            PhoneCommand.OpenApp(secret, work = false),
            CallTarget.Number(secret),
            CallTarget.Name(secret, secret),
            PhoneCommand.Call(CallTarget.Name(secret, "mobile")),
            CommandResult(Outcome.DONE, say = secret, show = secret, passOn = secret, acted = Command.Bare(secret, null)),
        ).map { it.toString() }

        for (text in printed) {
            assertFalse(text, text.contains("Ana") || text.contains("064") || text.contains("mobile"))
        }
    }

    @Test
    fun bareOfReadsAWholeSpokenNumber() {
        assertEquals(Command.Bare("21", 21), Command.Bare.of("21"))
        assertEquals(Command.Bare("Number twenty one.", 21), Command.Bare.of("Number twenty one."))
        assertEquals(Command.Bare("Send", null), Command.Bare.of("Send"))
        // A lone "to" is a number only right after a verb or "number", or as an answer.
        assertEquals(Command.Bare("to", null), Command.Bare.of("to"))
        assertEquals(Command.Bare("Number to", 2), Command.Bare.of("Number to"))
    }
}
```

Create `core/commands/src/test/kotlin/dev/kestrel/commands/SpokenNumbersTest.kt`:

```kotlin
package dev.kestrel.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpokenNumbersTest {
    private fun number(text: String, lone: Boolean = false) = SpokenNumbers.number(text.split(" "), lone)

    @Test
    fun readsDigitsAndNumber() {
        assertEquals(21, number("21"))
        assertEquals(7, number("number 7"))
        assertEquals(0, number("0"))
    }

    @Test
    fun readsWordsUpTo999() {
        assertEquals(5, number("five"))
        assertEquals(13, number("thirteen"))
        assertEquals(40, number("forty"))
        assertEquals(21, number("twenty one"))
        assertEquals(100, number("one hundred"))
        assertEquals(105, number("one hundred and five"))
        assertEquals(999, number("nine hundred ninety nine"))
        assertEquals(12, number("number twelve"))
    }

    @Test
    fun anythingElseIsNoNumber() {
        assertNull(number("send"))
        assertNull(number("twenty send"))
        assertNull(number("one hundred and"))
        assertNull(number("five five"))
        assertNull(number("number"))
        assertNull(SpokenNumbers.number(emptyList()))
    }

    @Test
    fun loneHomophonesCountOnlyWhenAllowed() {
        assertEquals(2, number("to", lone = true))
        assertEquals(2, number("too", lone = true))
        assertEquals(4, number("for", lone = true))
        assertEquals(1, number("won", lone = true))
        assertEquals(8, number("ate", lone = true))
        assertEquals(2, number("number to", lone = true))
        assertEquals(2, number("number to"))
        assertNull(number("to"))
        assertNull(number("to for", lone = true))
    }

    @Test
    fun readsTheAnswerToWhichOne() {
        assertEquals(2, SpokenNumbers.answer("2"))
        assertEquals(2, SpokenNumbers.answer("Number 2."))
        assertEquals(2, SpokenNumbers.answer("Tap two"))
        assertEquals(3, SpokenNumbers.answer("click three"))
        assertEquals(2, SpokenNumbers.answer("to"))
        assertEquals(4, SpokenNumbers.answer("For."))
        assertEquals(2, SpokenNumbers.answer("Two, please."))
        assertEquals(3, SpokenNumbers.answer("Okay, number three now"))
        assertNull(SpokenNumbers.answer("scroll down"))
        assertNull(SpokenNumbers.answer("tap send"))
    }
}
```

Create `core/commands/src/test/kotlin/dev/kestrel/commands/CommandParserTest.kt`:

```kotlin
package dev.kestrel.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandParserTest {
    @Test
    fun textWithoutWordsIsNoCommand() {
        assertNull(CommandParser.parse(""))
        assertNull(CommandParser.parse(" ?! "))
    }

    @Test
    fun aCommandMatchesOnlyTheWholeUtterance() {
        val said = "I think we should scroll down later"

        assertEquals(Command.Bare(said, null), CommandParser.parse(said))
        assertEquals(Command.Bare("Tap", null), CommandParser.parse("Tap"))
    }

    @Test
    fun bareKeepsTheWholeOriginalTranscriptAndItsNumber() {
        assertEquals(Command.Bare("Save.", null), CommandParser.parse("Save."))
        assertEquals(Command.Bare("21", 21), CommandParser.parse("21"))
        assertEquals(Command.Bare("Number five", 5), CommandParser.parse("Number five"))
    }

    @Test
    fun politeWordsAroundACommandAreDropped() {
        assertEquals(Command.Scroll(Direction.DOWN, null), CommandParser.parse("Please scroll down."))
        assertEquals(Command.Scroll(Direction.DOWN, null), CommandParser.parse("Scroll down, please"))
        assertEquals(Command.ShowNumbers, CommandParser.parse("Could you show numbers please"))
        assertEquals(Command.ShowGrid, CommandParser.parse("ok can you show grid now"))
        assertEquals(Command.Scroll(Direction.DOWN, null), CommandParser.parse("Scroll down, can you?"))
    }

    @Test
    fun politeWordsStayInPayloadsAndInBare() {
        assertEquals(Command.Tap(Target.Label("Send now")), CommandParser.parse("tap Send now"))
        assertEquals(Command.Bare("OK.", null), CommandParser.parse("OK."))
        assertEquals(Command.Bare("Please send it now", null), CommandParser.parse("Please send it now"))
    }

    @Test
    fun cutWordsKeepsTheRecognisersCaseAndInnerPunctuation() {
        assertEquals("Wi\u2011Fi", cutWords("Tap Wi\u2011Fi.", 1, 3))
        assertEquals("Hello, World", cutWords("type Hello, World!", 1, 3))
    }

    @Test
    fun targetWordsReadNumbersAndLabels() {
        val words = listOf("tap", "number", "five")
        assertEquals(Target.Number(5), TargetWords.read(words, "tap number five", 1))
        assertEquals(Target.Number(2), TargetWords.read(listOf("tap", "to"), "tap to", 1))
        assertEquals(Target.Number(5), TargetWords.read(listOf("tap", "5", "please"), "tap 5 please", 1))
        assertEquals(Target.Label("Send later"), TargetWords.read(listOf("tap", "send", "later"), "tap Send later", 1))
        assertNull(TargetWords.read(listOf("tap"), "tap", 1))
    }
}
```

Create `core/commands/src/test/kotlin/dev/kestrel/commands/GrammarScreenTest.kt`:

```kotlin
package dev.kestrel.commands

import dev.kestrel.commands.Command.Bare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen phrases of Appendix A (design spec, section 16.2): every row in two or more phrasings. None of them is a
 * phrase that plan 3C's phone grammar takes.
 */
class GrammarScreenTest {
    private fun label(text: String) = Target.Label(text)

    private fun number(value: Int) = Target.Number(value)

    private val phrases: List<Pair<String, Command>> = listOf(
        // Tap and long press, by label and by number.
        "tap Send" to Command.Tap(label("Send")),
        "Click Send later." to Command.Tap(label("Send later")),
        "press save" to Command.Tap(label("save")),
        "touch the send button" to Command.Tap(label("the send button")),
        "hit Delete" to Command.Tap(label("Delete")),
        "click on Subscribe" to Command.Tap(label("Subscribe")),
        "tap 7" to Command.Tap(number(7)),
        "tap number twelve" to Command.Tap(number(12)),
        "tap to" to Command.Tap(number(2)),
        "Tap #21" to Command.Tap(number(21)),
        "Tap number 12." to Command.Tap(number(12)),
        "tap 5 please" to Command.Tap(number(5)),
        "tap now" to Command.Tap(label("now")),
        "tap home" to Command.Tap(label("home")),
        "tap Wi\u2011Fi" to Command.Tap(label("Wi\u2011Fi")),
        "long press Send" to Command.LongPress(label("Send")),
        "long-press Counter" to Command.LongPress(label("Counter")),
        "long tap 4" to Command.LongPress(number(4)),
        "press and hold Counter" to Command.LongPress(label("Counter")),
        "Long-press the second photo." to Command.LongPress(label("the second photo")),
        "touch and hold 3" to Command.LongPress(number(3)),
        // Check, uncheck and switches.
        "check Subscribe" to Command.Check(label("Subscribe"), on = true),
        "check" to Command.Check(null, on = true),
        "uncheck Subscribe" to Command.Check(label("Subscribe"), on = false),
        "uncheck 5" to Command.Check(number(5), on = false),
        "switch on Dark theme" to Command.Switch(label("Dark theme"), on = true),
        "turn on Airplane mode" to Command.Switch(label("Airplane mode"), on = true),
        "Turn on the flashlight." to Command.Switch(label("the flashlight"), on = true),
        "switch off 4" to Command.Switch(number(4), on = false),
        "turn Dark theme off" to Command.Switch(label("Dark theme"), on = false),
        "switch on" to Command.Switch(null, on = true),
        "switch off" to Command.Switch(null, on = false),
        // Expand, collapse and dismiss.
        "expand Details" to Command.Expand(label("Details")),
        "expand" to Command.Expand(null),
        "collapse 3" to Command.Collapse(number(3)),
        "collapse" to Command.Collapse(null),
        "dismiss the notification" to Command.Dismiss(label("the notification")),
        "dismiss 2" to Command.Dismiss(number(2)),
        // Drag.
        "drag 3 to 7" to Command.Drag(number(3), number(7)),
        "drag Drag me to Drop here" to Command.Drag(label("Drag me"), label("Drop here")),
        "drag 5" to Command.Drag(number(5), null),
        "drag the photo" to Command.Drag(label("the photo"), null),
        // A last "to" without a place: Kestrel asks "Drag it where?".
        "drag 3 to" to Command.Drag(number(3), null),
        "drag Send to" to Command.Drag(label("Send"), null),
        // Scrolling.
        "scroll down" to Command.Scroll(Direction.DOWN, null),
        "scroll up" to Command.Scroll(Direction.UP, null),
        "scroll left" to Command.Scroll(Direction.LEFT, null),
        "Scroll right." to Command.Scroll(Direction.RIGHT, null),
        "scroll down on 3" to Command.Scroll(Direction.DOWN, number(3)),
        "Scroll down on the list." to Command.Scroll(Direction.DOWN, label("the list")),
        "scroll up in Messages" to Command.Scroll(Direction.UP, label("Messages")),
        "scroll to top" to Command.ScrollToEnd(top = true, target = null),
        "scroll to the top" to Command.ScrollToEnd(top = true, target = null),
        "scroll to bottom" to Command.ScrollToEnd(top = false, target = null),
        "scroll to the bottom of 2" to Command.ScrollToEnd(top = false, target = number(2)),
        "scroll to the top on Messages" to Command.ScrollToEnd(top = true, target = label("Messages")),
        // Swipes.
        "swipe forwards" to Command.SwipePage(forward = true),
        "swipe forward" to Command.SwipePage(forward = true),
        "swipe backwards" to Command.SwipePage(forward = false),
        "swipe backward" to Command.SwipePage(forward = false),
        "swipe from the bottom edge" to Command.SwipeFromEdge(Edge.BOTTOM),
        "swipe from bottom edge" to Command.SwipeFromEdge(Edge.BOTTOM),
        "swipe from the top" to Command.SwipeFromEdge(Edge.TOP),
        "swipe from the left edge" to Command.SwipeFromEdge(Edge.LEFT),
        "swipe from the right edge" to Command.SwipeFromEdge(Edge.RIGHT),
        "swipe from left" to Command.SwipeFromEdge(Edge.LEFT),
        "swipe up" to Command.Swipe(Direction.UP, null),
        "swipe left" to Command.Swipe(Direction.LEFT, null),
        "swipe up on 5" to Command.Swipe(Direction.UP, number(5)),
        "swipe 5 right" to Command.Swipe(Direction.RIGHT, number(5)),
        "swipe left on the pager" to Command.Swipe(Direction.LEFT, label("the pager")),
        // Pinch.
        "pinch in" to Command.Pinch(out = false, target = null),
        "pinch out" to Command.Pinch(out = true, target = null),
        "pinch out 6" to Command.Pinch(out = true, target = number(6)),
        "pinch in on the map" to Command.Pinch(out = false, target = label("the map")),
        // Polite words around a command go; without "on", a word after the direction is no target.
        "swipe up please" to Command.Swipe(Direction.UP, null),
        "pinch out please" to Command.Pinch(out = true, target = null),
        // Sliders.
        "increment 4" to Command.Slider(increase = true, target = number(4)),
        "increase Brightness" to Command.Slider(increase = true, target = label("Brightness")),
        "decrement 4" to Command.Slider(increase = false, target = number(4)),
        "decrease the slider" to Command.Slider(increase = false, target = label("the slider")),
        "increment" to Command.Slider(increase = true, target = null),
        "decrease" to Command.Slider(increase = false, target = null),
        // Numbers and labels.
        "show numbers" to Command.ShowNumbers,
        "show the numbers" to Command.ShowNumbers,
        "hide numbers" to Command.HideNumbers,
        "hide the numbers" to Command.HideNumbers,
        "show labels" to Command.ShowLabels,
        "show the labels" to Command.ShowLabels,
        "hide labels" to Command.HideLabels,
        "hide the labels" to Command.HideLabels,
        "what is 5" to Command.WhatIs(5),
        "What is 5?" to Command.WhatIs(5),
        "what's number twelve" to Command.WhatIs(12),
        "show commands for 5" to Command.CommandsFor(number(5)),
        "show commands for Send" to Command.CommandsFor(label("Send")),
        "commands for Subscribe" to Command.CommandsFor(label("Subscribe")),
        // The grid.
        "show grid" to Command.ShowGrid,
        "show the grid" to Command.ShowGrid,
        "hide grid" to Command.HideGrid,
        "hide the grid" to Command.HideGrid,
        "more squares" to Command.MoreSquares,
        "more grid squares" to Command.MoreSquares,
        "fewer squares" to Command.FewerSquares,
        "less squares" to Command.FewerSquares,
        // Help and again.
        "what can I say" to Command.Help(HelpKind.SUMMARY),
        "What can I say?" to Command.Help(HelpKind.SUMMARY),
        "show all commands" to Command.Help(HelpKind.ALL),
        "show commands" to Command.Help(HelpKind.ALL),
        "open tutorial" to Command.Help(HelpKind.TUTORIAL),
        "again" to Command.Again,
        "Again." to Command.Again,
        "repeat that" to Command.Again,
        "do it again" to Command.Again,
    )

    @Test
    fun everyScreenPhraseParsesToItsCommand() {
        for ((said, expected) in phrases) {
            assertEquals(said, expected, CommandParser.parse(said))
        }
    }

    @Test
    fun phrasesThatAreNoScreenCommandStayBare() {
        for (said in listOf(
            "Save",
            "Send later",
            "play",
            "pause",
            "help",
            "stop",
            "cancel",
            "tap",
            "scroll",
            "swipe",
            "drag",
            "I think we should go back later",
            "Can you tell me what time it is",
            "what is this",
            "let me check",
            // Not in Appendix A, and common in conversation.
            "what can I do",
            "turn on",
            "Turn off.",
        )) {
            val parsed = CommandParser.parse(said)
            assertTrue("$said gave $parsed", parsed is Bare)
            assertEquals(said, (parsed as Bare).text)
        }
    }
}
```

Create `core/commands/src/test/kotlin/dev/kestrel/commands/CommandHelpTest.kt`:

```kotlin
package dev.kestrel.commands

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandHelpTest {
    @Test
    fun everyExampleParsesToACommand() {
        for (group in CommandHelp.groups) {
            for (example in group.examples) {
                val parsed = CommandParser.parse(example)
                assertNotNull(example, parsed)
                assertFalse("$example parses to $parsed", parsed is Command.Bare)
            }
        }
    }

    @Test
    fun theListJoinsTheGroupsOfEveryPlan() {
        assertTrue(CommandHelp.groups.containsAll(HelpScreen.groups))
        assertTrue(CommandHelp.groups.containsAll(HelpText.groups))
        assertTrue(CommandHelp.groups.containsAll(HelpPhone.groups))
        assertTrue(HelpScreen.groups.all { it.title.isNotBlank() && it.examples.isNotEmpty() })
    }
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :core:commands:test`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'Command'", "Unresolved reference 'CommandParser'", "Unresolved reference 'SpokenNumbers'" and "Unresolved reference 'CommandHelp'".

- [ ] **Step 3: Write the command model**

Create `core/commands/src/main/kotlin/dev/kestrel/commands/Command.kt`:

```kotlin
package dev.kestrel.commands

import dev.kestrel.commands.text.Normalizer

/** What a command acts on: the owner's words as heard, or a number of a badge or a grid cell (design spec, section 9.4). */
sealed interface Target {
    /** [spoken] keeps the recogniser's case, for "I can't find X". */
    data class Label(val spoken: String) : Target {
        override fun toString() = "Label(${spoken.length} characters)"
    }

    data class Number(val number: Int) : Target
}

enum class Direction { UP, DOWN, LEFT, RIGHT }

enum class Edge { TOP, BOTTOM, LEFT, RIGHT }

enum class HelpKind { SUMMARY, ALL, TUTORIAL }

/**
 * An instant command (design spec, section 9.5, and Appendix A). The variants of the screen commands are nested here;
 * the text commands are [TextCommand]s and the phone commands [PhoneCommand]s. The model is frozen: plans 3B and 3C
 * change none of its three files.
 */
sealed interface Command {
    data class Tap(val target: Target) : Command

    data class LongPress(val target: Target) : Command

    /**
     * Speech that no rule takes, as the owner said it: a bare label, a bare number ([number] when the whole text is a
     * spoken number) or dictation. The executor decides which.
     */
    data class Bare(val text: String, val number: Int?) : Command {
        override fun toString() = "Bare(${text.length} characters, number=${number != null})"

        companion object {
            fun of(text: String): Bare = Bare(text, SpokenNumbers.number(Normalizer.words(text)))
        }
    }

    /** "Check" or "uncheck" ([on] false); without a target, the only checkable element or the focused one. */
    data class Check(val target: Target?, val on: Boolean) : Command

    data class Switch(val target: Target?, val on: Boolean) : Command

    data class Expand(val target: Target?) : Command

    data class Collapse(val target: Target?) : Command

    data class Dismiss(val target: Target) : Command

    /** "Drag X to Y"; without [to], Kestrel asks "Drag it where?". */
    data class Drag(val from: Target, val to: Target?) : Command

    /** [direction] is the content's direction, as in "scroll down". */
    data class Scroll(val direction: Direction, val target: Target?) : Command

    data class ScrollToEnd(val top: Boolean, val target: Target?) : Command

    /** "Swipe forwards" shows the next page of a horizontal list or pager. */
    data class SwipePage(val forward: Boolean) : Command

    data class SwipeFromEdge(val edge: Edge) : Command

    /** [direction] is the finger's direction. */
    data class Swipe(val direction: Direction, val target: Target?) : Command

    data class Pinch(val out: Boolean, val target: Target?) : Command

    data class Slider(val increase: Boolean, val target: Target?) : Command

    data object ShowNumbers : Command

    data object HideNumbers : Command

    data object ShowLabels : Command

    data object HideLabels : Command

    data object ShowGrid : Command

    data object HideGrid : Command

    data object MoreSquares : Command

    data object FewerSquares : Command

    data class WhatIs(val number: Int) : Command

    data class CommandsFor(val target: Target) : Command

    data class Help(val kind: HelpKind) : Command

    /** "Again": the Rules replace it with the last repeatable command; the executor never sees it. */
    data object Again : Command
}
```

Create `core/commands/src/main/kotlin/dev/kestrel/commands/TextCommandModel.kt`:

```kotlin
package dev.kestrel.commands

/** The units of the text commands (design spec, section 9.7, and Appendix A). */
enum class TextUnit { CHARACTER, WORD, SENTENCE, LINE, PARAGRAPH }

/** What "delete X" and "select X" act on. */
sealed interface TextScope {
    data object Selection : TextScope

    data object All : TextScope

    data object ToStart : TextScope

    data object ToEnd : TextScope

    data class Phrase(val text: String) : TextScope {
        override fun toString() = "Phrase(${text.length} characters)"
    }

    /** From [from] to [to], both included. */
    data class FromTo(val from: String, val to: String) : TextScope {
        override fun toString() = "FromTo(${from.length} and ${to.length} characters)"
    }

    /** Between [from] and [to], both left out. */
    data class Between(val from: String, val to: String) : TextScope {
        override fun toString() = "Between(${from.length} and ${to.length} characters)"
    }

    /** [count] units, backwards from the cursor unless [forward]. */
    data class Units(val count: Int, val unit: TextUnit, val forward: Boolean) : TextScope
}

/** Where "insert" and "move" put the cursor or the text. */
sealed interface Place {
    data object Start : Place

    data object End : Place

    data class Before(val phrase: String) : Place {
        override fun toString() = "Before(${phrase.length} characters)"
    }

    data class After(val phrase: String) : Place {
        override fun toString() = "After(${phrase.length} characters)"
    }

    data class Between(val first: String, val second: String) : Place {
        override fun toString() = "Between(${first.length} and ${second.length} characters)"
    }

    data class Units(val count: Int, val unit: TextUnit, val forward: Boolean) : Place
}

enum class TextCase { CAPITALIZE, UPPER, LOWER }

enum class ClipboardAction { CUT, COPY, PASTE }

/** The text commands of plan 3B (design spec, section 9.7, and Appendix A). */
sealed interface TextCommand : Command {
    data class StartEditing(val target: Target?) : TextCommand

    data object StopEditing : TextCommand

    data class Type(val text: String) : TextCommand {
        override fun toString() = "Type(${text.length} characters)"
    }

    data object Undo : TextCommand

    data object Redo : TextCommand

    data class Insert(val text: String, val place: Place) : TextCommand {
        override fun toString() = "Insert(${text.length} characters, $place)"
    }

    data class Replace(val old: String, val new: String) : TextCommand {
        override fun toString() = "Replace(${old.length} with ${new.length} characters)"
    }

    data class ReplaceBetween(val from: String, val to: String, val new: String) : TextCommand {
        override fun toString() = "ReplaceBetween(${from.length} and ${to.length} with ${new.length} characters)"
    }

    data class ChangeCase(val case: TextCase, val phrase: String) : TextCommand {
        override fun toString() = "ChangeCase($case, ${phrase.length} characters)"
    }

    data object FormatEmail : TextCommand

    /** A bare "delete" has no scope: it deletes the selection, or the word before the cursor. */
    data class Delete(val scope: TextScope?) : TextCommand

    data class MoveCursor(val place: Place) : TextCommand

    data class Select(val scope: TextScope) : TextCommand

    data object Unselect : TextCommand

    data class Clipboard(val action: ClipboardAction) : TextCommand

    data class Keyboard(val show: Boolean) : TextCommand

    data object NewLine : TextCommand

    data object ShowPassword : TextCommand

    data class Search(val query: String) : TextCommand {
        override fun toString() = "Search(${query.length} characters)"
    }
}
```

Create `core/commands/src/main/kotlin/dev/kestrel/commands/PhoneCommandModel.kt`:

```kotlin
package dev.kestrel.commands

/** The global actions of plan 3C (design spec, section 9.3, and Appendix A). */
enum class GlobalCommand { BACK, HOME, NOTIFICATIONS, QUICK_SETTINGS, RECENT_APPS, LOCK_SCREEN, POWER_MENU, SCREENSHOT, ALL_APPS }

enum class RadioKind { WIFI, BLUETOOTH }

enum class StreamKind { MEDIA, RING, ALARM, CALL, NOTIFICATION }

enum class MagnifyAction { IN, OUT, STOP }

/** Whom "call X" calls: a spoken number, or a contact by name with an optional kind of number such as "mobile". */
sealed interface CallTarget {
    data class Number(val digits: String) : CallTarget {
        override fun toString() = "Number(${digits.length} digits)"
    }

    data class Name(val name: String, val kind: String?) : CallTarget {
        override fun toString() = "Name(${name.length} characters, kind=${kind != null})"
    }
}

/**
 * The phone commands of plan 3C (design spec, sections 9.3 and 13.6, and Appendix A). They are nested here, so that
 * [Call] and [Mute] never clash with the call commands of the Orchestrator's requests.
 */
sealed interface PhoneCommand : Command {
    data class Global(val action: GlobalCommand) : PhoneCommand

    data class OpenApp(val name: String, val work: Boolean) : PhoneCommand {
        override fun toString() = "OpenApp(${name.length} characters, work=$work)"
    }

    data object PreviousApp : PhoneCommand

    data object CircleToSearch : PhoneCommand

    data class Radio(val radio: RadioKind, val on: Boolean) : PhoneCommand

    /** Without [stream], the call volume during a call and the media volume otherwise. */
    data class Volume(val stream: StreamKind?, val up: Boolean) : PhoneCommand

    data class Mute(val stream: StreamKind?, val on: Boolean) : PhoneCommand

    data object Silence : PhoneCommand

    data class Media(val play: Boolean) : PhoneCommand

    data class Magnify(val action: MagnifyAction) : PhoneCommand

    data class Pan(val direction: Direction) : PhoneCommand

    data class Call(val who: CallTarget) : PhoneCommand

    data object CopyPhoneNumber : PhoneCommand
}
```

Create `core/commands/src/main/kotlin/dev/kestrel/commands/CommandResult.kt`:

```kotlin
package dev.kestrel.commands

/** How an instant command ended. */
enum class Outcome { DONE, FAILED, IGNORED, CANCELLED }

/**
 * What an instant command ends with (design spec, section 9.5). [say] is spoken, [show] goes to the feedback bar.
 * [repeatable]: "again" may repeat it. [passOn]: an answer that did not answer the command's question, which the
 * Orchestrator reads as a new utterance. [acted]: the command that acted, when it differs from the one that ran, as
 * after the fallback to a bare label.
 */
data class CommandResult(
    val outcome: Outcome,
    val say: String? = null,
    val show: String? = null,
    val repeatable: Boolean = false,
    val passOn: String? = null,
    val acted: Command? = null,
) {
    override fun toString() =
        "CommandResult($outcome, say=${say?.length}, show=${show?.length}, repeatable=$repeatable, " +
            "passOn=${passOn?.length}, acted=${acted?.javaClass?.simpleName})"
}
```

- [ ] **Step 4: Write the parser, the spoken numbers and the screen grammar**

Create `core/commands/src/main/kotlin/dev/kestrel/commands/SpokenNumbers.kt`:

```kotlin
package dev.kestrel.commands

import dev.kestrel.commands.text.Normalizer

/** Spoken numbers (design spec, section 9.5): digits, "number 21", and words up to 999. */
object SpokenNumbers {
    private val ONES = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    ).withIndex().associate { it.value to it.index }
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80,
        "ninety" to 90,
    )

    // What the recogniser writes for a lone number that the owner said.
    private val HOMOPHONES = mapOf("to" to 2, "too" to 2, "for" to 4, "won" to 1, "ate" to 8)
    private val TAP_VERBS = setOf("tap", "click", "press", "touch", "hit")

    /**
     * The number that [words] say, or null. [words] are folded words; a leading "number" is allowed. With [lone], a single
     * "to" or "too" counts as 2, "for" as 4, "won" as 1 and "ate" as 8: right after a verb or "number" (which allows them
     * without [lone]), and as the whole answer to a question.
     */
    fun number(words: List<String>, lone: Boolean = false): Int? {
        val numbered = words.size > 1 && words[0] == "number"
        val rest = if (numbered) words.drop(1) else words
        if (rest.isEmpty()) return null
        if (rest.size == 1) {
            val word = rest[0]
            if (word.length <= 6 && word.all { it in '0'..'9' }) return word.toInt()
            // "Number to" is number two, also as a bare number.
            if (lone || numbered) HOMOPHONES[word]?.let { return it }
        }
        return words(rest)
    }

    /** The answer to "Which one?": "2", "number 2", "tap 2", or a lone "to", also with polite words around it. */
    fun answer(text: String): Int? {
        var words = Normalizer.words(text)
        while (words.size > 1 && words.first() in CommandParser.POLITE_WORDS) words = words.drop(1)
        while (words.size > 1 && words.last() in CommandParser.POLITE_WORDS) words = words.dropLast(1)
        val rest = if (words.size > 1 && words[0] in TAP_VERBS) words.drop(1) else words
        return number(rest, lone = true)
    }

    // [ones "hundred" ["and"]] [tens [ones]] or [ones], with every word used.
    private fun words(words: List<String>): Int? {
        var index = 0
        var value = 0
        val hundreds = ONES[words[0]]
        if (words.size >= 2 && hundreds != null && hundreds in 1..9 && words[1] == "hundred") {
            value = hundreds * 100
            index = 2
            if (index < words.size && words[index] == "and") {
                index++
                if (index == words.size) return null
            }
        }
        if (index < words.size) {
            val tens = TENS[words[index]]
            val ones = ONES[words[index]]
            when {
                tens != null -> {
                    value += tens
                    index++
                    val unit = words.getOrNull(index)?.let { ONES[it] }
                    if (unit != null && unit in 1..9) {
                        value += unit
                        index++
                    }
                }
                ones != null -> {
                    value += ones
                    index++
                }
                else -> return null
            }
        }
        return if (index == words.size) value else null
    }
}
```

Create `core/commands/src/main/kotlin/dev/kestrel/commands/CommandParser.kt`:

```kotlin
package dev.kestrel.commands

import dev.kestrel.commands.text.Normalizer

/**
 * Reads an instant command from a transcript (design spec, section 9.5). Kestrel's own phrases, which start with its
 * name, never reach it. A command matches only the whole utterance. The fixed phrases of every group come first
 * ([GrammarScreen], [GrammarText], [GrammarPhone]), then the rules with a target or a payload ([GrammarPhone],
 * [GrammarText], [GrammarScreen]); each group's targeted rules return null for a target that is not theirs. Anything else
 * is [Command.Bare]. Only a [Command.Bare] is read again without the polite words around it.
 */
object CommandParser {
    internal val POLITE_WORDS = setOf("please", "now", "ok", "okay")
    private val POLITE_PAIRS = listOf(listOf("could", "you"), listOf("can", "you"))

    /** The command in [text], or null when [text] has no words. */
    fun parse(text: String): Command? {
        val words = Normalizer.words(text)
        if (words.isEmpty()) return null
        val whole = read(words, text)
        if (whole !is Command.Bare) return whole
        var from = 0
        var to = words.size
        while (true) {
            val pair = POLITE_PAIRS.firstOrNull { to - from > it.size && words.subList(from, from + it.size) == it }
            from += when {
                pair != null -> pair.size
                to - from > 1 && words[from] in POLITE_WORDS -> 1
                else -> break
            }
        }
        while (true) {
            val pair = POLITE_PAIRS.firstOrNull { to - from > it.size && words.subList(to - it.size, to) == it }
            to -= when {
                pair != null -> pair.size
                to - from > 1 && words[to - 1] in POLITE_WORDS -> 1
                else -> break
            }
        }
        if (from == 0 && to == words.size) return whole
        val inner = cutWords(text, from, to)
        val again = read(Normalizer.words(inner), inner)
        return if (again is Command.Bare) whole else again
    }

    private fun read(words: List<String>, text: String): Command =
        GrammarScreen.fixed(words, text) ?: GrammarText.fixed(words, text) ?: GrammarPhone.fixed(words, text)
            ?: GrammarPhone.targeted(words, text) ?: GrammarText.targeted(words, text)
            ?: GrammarScreen.targeted(words, text) ?: Command.Bare.of(text.trim())
}

/**
 * The original text of the words `from until to` of `Normalizer.words(text)`, with the recogniser's case and the
 * punctuation between those words.
 */
internal fun cutWords(text: String, from: Int, to: Int): String = text.substring(Normalizer.withOffsets(text).span(from, to))

/** Reads the words of a command that name its target. */
internal object TargetWords {
    /**
     * The words `from until to` as a target: a spoken number (a lone "to" counts, because a verb comes before it) as
     * [Target.Number], otherwise their original text as [Target.Label]. Null when there are no words.
     */
    fun read(words: List<String>, text: String, from: Int, to: Int = words.size): Target? {
        if (from >= to) return null
        // "Tap 5 please": a polite word after a number is no part of it. A label keeps its words (design D5).
        var end = to
        while (end - from > 1 && words[end - 1] in CommandParser.POLITE_WORDS) end--
        SpokenNumbers.number(words.subList(from, end), lone = true)?.let { return Target.Number(it) }
        return Target.Label(cutWords(text, from, to))
    }
}
```

Create `core/commands/src/main/kotlin/dev/kestrel/commands/GrammarScreen.kt`:

```kotlin
package dev.kestrel.commands

/**
 * The screen phrases of plan 3A (design spec, Appendix A: help, numbers and labels, touch and swipe gestures, the grid,
 * sliders). "Back" and "home" alone, "zoom in" and "zoom out" and every phrase with "open" except "open tutorial" belong
 * to [GrammarPhone].
 */
internal object GrammarScreen {
    private val DIRECTIONS = mapOf("up" to Direction.UP, "down" to Direction.DOWN, "left" to Direction.LEFT, "right" to Direction.RIGHT)
    private val EDGES = mapOf("top" to Edge.TOP, "bottom" to Edge.BOTTOM, "left" to Edge.LEFT, "right" to Edge.RIGHT)
    private val TAP_VERBS = listOf("tap", "click", "press", "touch", "hit")
    private val LONG_PRESS_VERBS = listOf("long press", "long tap", "long click", "press and hold", "touch and hold")
    private val PLACES = setOf("on", "in", "of")

    private val FIXED: Map<String, Command> = buildMap {
        for (phrase in listOf("show numbers", "show the numbers", "show all numbers", "numbers on")) put(phrase, Command.ShowNumbers)
        for (phrase in listOf("hide numbers", "hide the numbers", "remove numbers", "numbers off")) put(phrase, Command.HideNumbers)
        for (phrase in listOf("show labels", "show the labels", "show names")) put(phrase, Command.ShowLabels)
        for (phrase in listOf("hide labels", "hide the labels", "hide names")) put(phrase, Command.HideLabels)
        for (phrase in listOf("show grid", "show the grid", "grid on")) put(phrase, Command.ShowGrid)
        for (phrase in listOf("hide grid", "hide the grid", "close the grid", "grid off")) put(phrase, Command.HideGrid)
        for (phrase in listOf("more squares", "more grid squares")) put(phrase, Command.MoreSquares)
        for (phrase in listOf("fewer squares", "less squares", "fewer grid squares")) put(phrase, Command.FewerSquares)
        put("what can i say", Command.Help(HelpKind.SUMMARY))
        for (phrase in listOf("show all commands", "show commands", "show me the commands")) put(phrase, Command.Help(HelpKind.ALL))
        put("open tutorial", Command.Help(HelpKind.TUTORIAL))
        for (phrase in listOf("again", "repeat that", "do it again", "do that again")) put(phrase, Command.Again)
        for ((word, direction) in DIRECTIONS) {
            put("scroll $word", Command.Scroll(direction, null))
            put("swipe $word", Command.Swipe(direction, null))
        }
        for (phrase in listOf("scroll to top", "scroll to the top")) put(phrase, Command.ScrollToEnd(top = true, target = null))
        for (phrase in listOf("scroll to bottom", "scroll to the bottom")) put(phrase, Command.ScrollToEnd(top = false, target = null))
        for (phrase in listOf("swipe forwards", "swipe forward")) put(phrase, Command.SwipePage(forward = true))
        for (phrase in listOf("swipe backwards", "swipe backward")) put(phrase, Command.SwipePage(forward = false))
        for ((word, edge) in EDGES) {
            for (phrase in listOf("swipe from the $word edge", "swipe from $word edge", "swipe from the $word", "swipe from $word")) {
                put(phrase, Command.SwipeFromEdge(edge))
            }
        }
        put("pinch in", Command.Pinch(out = false, target = null))
        put("pinch out", Command.Pinch(out = true, target = null))
        for (phrase in listOf("increment", "increase")) put(phrase, Command.Slider(increase = true, target = null))
        for (phrase in listOf("decrement", "decrease")) put(phrase, Command.Slider(increase = false, target = null))
        put("check", Command.Check(null, on = true))
        put("uncheck", Command.Check(null, on = false))
        // Not "turn on" or "turn off" alone: "turn off" is also Kestrel's own verb, whose name the recogniser may lose.
        // "Switch on" and "switch off" alone stay, because Appendix A lists them, although "Kestrel, switch off" that
        // loses the name switches the only switch on the screen too.
        put("switch on", Command.Switch(null, on = true))
        put("switch off", Command.Switch(null, on = false))
        put("expand", Command.Expand(null))
        put("collapse", Command.Collapse(null))
    }

    fun fixed(words: List<String>, text: String): Command? = FIXED[words.joinToString(" ")]

    fun targeted(words: List<String>, text: String): Command? {
        fun target(from: Int, to: Int = words.size) = TargetWords.read(words, text, from, to)

        for (verb in LONG_PRESS_VERBS) {
            val from = after(words, verb)
            if (from > 0) return target(from)?.let { Command.LongPress(it) }
        }
        for (verb in TAP_VERBS) {
            var from = after(words, verb)
            if (from < 0) continue
            if (from < words.size - 1 && words[from] == "on") from++
            return target(from)?.let { Command.Tap(it) }
        }
        after(words, "check").takeIf { it > 0 }?.let { from -> return target(from)?.let { Command.Check(it, on = true) } }
        after(words, "uncheck").takeIf { it > 0 }?.let { from -> return target(from)?.let { Command.Check(it, on = false) } }
        switch(words, text)?.let { return it }
        after(words, "expand").takeIf { it > 0 }?.let { from -> return target(from)?.let { Command.Expand(it) } }
        after(words, "collapse").takeIf { it > 0 }?.let { from -> return target(from)?.let { Command.Collapse(it) } }
        after(words, "dismiss").takeIf { it > 0 }?.let { from -> return target(from)?.let { Command.Dismiss(it) } }
        if (words.size >= 2 && words[0] == "drag") {
            val to = words.lastIndexOf("to")
            if (to in 2 until words.size - 1) {
                val from = target(1, to) ?: return null
                return Command.Drag(from, target(to + 1))
            }
            // "Drag 3 to" stops before its place, so Kestrel asks "Drag it where?".
            val end = if (to >= 2 && to == words.size - 1) to else words.size
            return target(1, end)?.let { Command.Drag(it, null) }
        }
        scroll(words, text)?.let { return it }
        swipe(words, text)?.let { return it }
        pinch(words, text)?.let { return it }
        for ((verb, increase) in listOf("increment" to true, "increase" to true, "decrement" to false, "decrease" to false)) {
            val from = after(words, verb)
            if (from > 0) return target(from)?.let { Command.Slider(increase, it) }
        }
        for (prefix in listOf("what is", "whats")) {
            val from = after(words, prefix)
            if (from > 0) return SpokenNumbers.number(words.subList(from, words.size), lone = true)?.let { Command.WhatIs(it) }
        }
        for (prefix in listOf("show commands for", "commands for", "what can i say for")) {
            val from = after(words, prefix)
            if (from > 0) return target(from)?.let { Command.CommandsFor(it) }
        }
        return null
    }

    // "switch on X", "turn off X", "switch X on", "turn X off".
    private fun switch(words: List<String>, text: String): Command? {
        if (words.size < 3 || words[0] !in setOf("switch", "turn")) return null
        val state = mapOf("on" to true, "off" to false)
        state[words[1]]?.let { on -> return TargetWords.read(words, text, 2)?.let { Command.Switch(it, on) } }
        state[words.last()]?.let { on -> return TargetWords.read(words, text, 1, words.size - 1)?.let { Command.Switch(it, on) } }
        return null
    }

    // "scroll down on X", "scroll up in X", "scroll to the top of X".
    private fun scroll(words: List<String>, text: String): Command? {
        if (words.size < 3 || words[0] != "scroll") return null
        DIRECTIONS[words[1]]?.let { direction ->
            if (words[2] !in PLACES) return null
            return TargetWords.read(words, text, 3)?.let { Command.Scroll(direction, it) }
        }
        for (start in listOf(listOf("to", "the"), listOf("to"))) {
            val end = 1 + start.size
            if (words.size <= end + 1 || words.subList(1, end) != start) continue
            val top = when (words[end]) {
                "top" -> true
                "bottom" -> false
                else -> return null
            }
            if (words[end + 1] !in PLACES) return null
            return TargetWords.read(words, text, end + 2)?.let { Command.ScrollToEnd(top, it) }
        }
        return null
    }

    // "swipe up on X", "swipe X up".
    private fun swipe(words: List<String>, text: String): Command? {
        if (words.size < 3 || words[0] != "swipe") return null
        DIRECTIONS[words[1]]?.let { direction ->
            if (words[2] !in PLACES) return null
            return TargetWords.read(words, text, 3)?.let { Command.Swipe(direction, it) }
        }
        DIRECTIONS[words.last()]?.let { direction ->
            return TargetWords.read(words, text, 1, words.size - 1)?.let { Command.Swipe(direction, it) }
        }
        return null
    }

    // "pinch out 5", "pinch in on X". Without "on", only a number follows, so that "pinch out please" stays bare.
    private fun pinch(words: List<String>, text: String): Command? {
        if (words.size < 3 || words[0] != "pinch" || words[1] !in setOf("in", "out")) return null
        val out = words[1] == "out"
        if (words[2] in PLACES) return TargetWords.read(words, text, 3)?.let { Command.Pinch(out, it) }
        return SpokenNumbers.number(words.subList(2, words.size), lone = true)?.let { Command.Pinch(out, Target.Number(it)) }
    }

    /** When [words] start with the words of [phrase], the index after them; otherwise -1. */
    private fun after(words: List<String>, phrase: String): Int {
        val parts = phrase.split(" ")
        return if (words.size >= parts.size && words.subList(0, parts.size) == parts) parts.size else -1
    }
}
```

Create `core/commands/src/main/kotlin/dev/kestrel/commands/GrammarText.kt`:

```kotlin
package dev.kestrel.commands

/** The text phrases of plan 3B (design spec, section 9.7). Plan 3B replaces this stub, which takes nothing. */
internal object GrammarText {
    fun fixed(words: List<String>, text: String): Command? = null

    fun targeted(words: List<String>, text: String): Command? = null
}
```

Create `core/commands/src/main/kotlin/dev/kestrel/commands/GrammarPhone.kt`:

```kotlin
package dev.kestrel.commands

/** The phone phrases of plan 3C (design spec, sections 9.3 and 13.6). Plan 3C replaces this stub, which takes nothing. */
internal object GrammarPhone {
    fun fixed(words: List<String>, text: String): Command? = null

    fun targeted(words: List<String>, text: String): Command? = null
}
```

- [ ] **Step 5: Write the help list**

Create `core/commands/src/main/kotlin/dev/kestrel/commands/CommandHelp.kt`:

```kotlin
package dev.kestrel.commands

/** A group of the command list that Kestrel's settings app shows (design spec, Appendix A, help). */
data class HelpGroup(val title: String, val examples: List<String>)

/** Every group of the command list: plan 3A's screen commands, then plan 3B's text and plan 3C's phone commands. */
object CommandHelp {
    val groups: List<HelpGroup> get() = HelpScreen.groups + HelpText.groups + HelpPhone.groups
}
```

Create `core/commands/src/main/kotlin/dev/kestrel/commands/HelpScreen.kt`:

```kotlin
package dev.kestrel.commands

/** The screen commands of the command list. Every example must parse to a command (`CommandHelpTest`). */
internal object HelpScreen {
    val groups: List<HelpGroup> = listOf(
        HelpGroup("Tap and press", listOf("tap Send", "tap 5", "long press Send", "press and hold 3")),
        HelpGroup("Switches and sliders", listOf("check Remember me", "uncheck 4", "turn on Dark theme", "increase 6", "decrease 6")),
        HelpGroup("Notifications and lists", listOf("expand 2", "collapse 2", "dismiss 3")),
        HelpGroup(
            "Scroll and swipe",
            listOf("scroll down", "scroll up on 2", "scroll to top", "swipe forwards", "swipe from the left edge", "swipe up"),
        ),
        HelpGroup("Drag and pinch", listOf("drag 3 to 7", "drag 3", "pinch out", "pinch in 4")),
        HelpGroup(
            "Numbers and labels",
            listOf("show numbers", "hide numbers", "show labels", "hide labels", "what is 5", "show commands for 5"),
        ),
        HelpGroup("The grid", listOf("show grid", "more squares", "fewer squares", "tap 12", "hide grid")),
        HelpGroup("Help and repeating", listOf("what can I say", "show all commands", "again")),
    )
}
```

Create `core/commands/src/main/kotlin/dev/kestrel/commands/HelpText.kt`:

```kotlin
package dev.kestrel.commands

/** The text commands of the command list. Plan 3B replaces this stub. */
internal object HelpText {
    val groups: List<HelpGroup> = emptyList()
}
```

Create `core/commands/src/main/kotlin/dev/kestrel/commands/HelpPhone.kt`:

```kotlin
package dev.kestrel.commands

/** The phone commands of the command list. Plan 3C replaces this stub. */
internal object HelpPhone {
    val groups: List<HelpGroup> = emptyList()
}
```

- [ ] **Step 6: Run the tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:commands:test`
Expected: `BUILD SUCCESSFUL`, with 28 + 18 = 46 tests in `:core:commands`: the 28 of Task 1, `CommandModelTest` 2, `SpokenNumbersTest` 5, `CommandParserTest` 7, `GrammarScreenTest` 2 and `CommandHelpTest` 2.

- [ ] **Step 7: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:commands` 46; `:app` 264, `:core:voice` 139, `:core:screen` 47, `:core:privacy` 48 and `:core:safety` 2, as before this task), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), and the audit.

- [ ] **Step 8: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 9: Commit**

```powershell
git add core/commands/src/main/kotlin/dev/kestrel/commands/Command.kt core/commands/src/main/kotlin/dev/kestrel/commands/TextCommandModel.kt core/commands/src/main/kotlin/dev/kestrel/commands/PhoneCommandModel.kt core/commands/src/main/kotlin/dev/kestrel/commands/CommandParser.kt core/commands/src/main/kotlin/dev/kestrel/commands/SpokenNumbers.kt core/commands/src/main/kotlin/dev/kestrel/commands/GrammarScreen.kt core/commands/src/main/kotlin/dev/kestrel/commands/GrammarText.kt core/commands/src/main/kotlin/dev/kestrel/commands/GrammarPhone.kt core/commands/src/main/kotlin/dev/kestrel/commands/CommandResult.kt core/commands/src/main/kotlin/dev/kestrel/commands/CommandHelp.kt core/commands/src/main/kotlin/dev/kestrel/commands/HelpScreen.kt core/commands/src/main/kotlin/dev/kestrel/commands/HelpText.kt core/commands/src/main/kotlin/dev/kestrel/commands/HelpPhone.kt core/commands/src/test/kotlin/dev/kestrel/commands/CommandModelTest.kt core/commands/src/test/kotlin/dev/kestrel/commands/SpokenNumbersTest.kt core/commands/src/test/kotlin/dev/kestrel/commands/CommandParserTest.kt core/commands/src/test/kotlin/dev/kestrel/commands/GrammarScreenTest.kt core/commands/src/test/kotlin/dev/kestrel/commands/CommandHelpTest.kt
git commit -m "feat(commands): add the frozen command model, the parser with the screen grammar, spoken numbers and the help list" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: The Orchestrator and Listening run instant commands

**Files:**
- Modify: `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt` (imports, `Work`, `State`, `Event`, `Reply`, `Feedback`, `Effect`, `Rules.react`, the end of `whenNoCommand`, `heard`, `command`, and the new functions for a running command)
- Modify: `app/src/main/kotlin/dev/kestrel/app/orchestrator/Orchestrator.kt` (imports, the KDoc, the constructor, `handle`, the companion)
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt` (imports, `FeedbackBar`, `ListeningTexts`, the class's KDoc and constructor, `sessions`, the command's job and conversation, `performUnguarded`, `say`, `text`, the companion)
- Create: `app/src/main/kotlin/dev/kestrel/app/commands/CommandRunner.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (imports, `bar`, `commands`)
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/FeedbackBar.kt`, `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt` (`showFeedback`, `releaseFeedback`)
- Modify: `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt` (`state()`), `scripts/hook.ps1` (the comment of `voice-state`)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 7.3, 8.9 and 9.5)
- Test: `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, `OrchestratorTest.kt`, `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, `core/voice/src/test/kotlin/dev/kestrel/voice/listen/ListeningPipelineTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceHookTest.kt` (the pattern of the state while Kestrel is off)

**Interfaces:**
- Consumes: Task 2's `Command`, `CommandParser.parse(text)`, `CommandResult`, `Outcome`, `TextCommand`, `PhoneCommand.Global`, `PhoneCommand.Call`, `GlobalCommand.LOCK_SCREEN`; Task 1's `Normalizer.words(text)`; plan 2D's `Requests.read`, `Event.Assist`, `Event.CallState`, `Mode.IN_CALL`.
- Produces (package `dev.kestrel.app.orchestrator`): `Work.RUNNING_COMMAND`; `State.command: Int = 0`, `running: Command? = null`, `heard: String? = null` (the running command's transcript), `asking: Boolean = false`, `counting: Boolean = false`, `lastCommand: Command? = null`, `lastHeard: String? = null`, `spottedStop: Boolean = false`; `Event.CommandEnded(command: Int, result: CommandResult)`, `Event.CommandAsks(command: Int, question: String)`, `Event.CommandSays(command: Int, text: String)`, `Event.CommandCounts(command: Int, counting: Boolean)`, `Event.AnswerWaitEnded(wait: Int)`; `Effect.RunCommand(command: Int, parsed: Command, heard: String)` (in place of `RunCommand(text)`), `Effect.CancelCommand`, `Effect.AnswerCommand(command: Int, text: String?)`, `Effect.HoldCommand(command: Int, hold: Boolean)`, `Effect.WaitForAnswer(answering: Boolean = false)`; `Reply.Command(command: Int, text: String)`; `Feedback.CommandText(text: String, keep: Boolean = false)`, `Feedback.CommandCancelled`, `Feedback.CommandBusy`, `Feedback.NothingToRepeat`; the `Orchestrator` parameter `questionMillis: Long = 15_000`. Package `dev.kestrel.app.commands` (`CommandRunner.kt`): `internal interface CommandRunner { suspend fun run(command: Command, heard: String, conversation: CommandConversation): CommandResult; fun privateInput(): Boolean }`, `internal interface CommandConversation { suspend fun say(text: String); suspend fun ask(question: String): String?; fun keep(text: String); suspend fun countdown(millis: Long, text: (secondsLeft: Int) -> String): Boolean }`, `internal data class CommandReport(val command: Int, val kind: String, val outcome: Outcome)`. Package `dev.kestrel.app.listen`: `internal interface FeedbackBar { fun show(text: String, keep: Boolean = false); fun release() }`; `ListeningTexts.CANCELLED`, `BUSY`, `NOTHING_TO_REPEAT`, `PRIVATE_INPUT`, `COMMAND_FAILED`; the `Listening` constructor parameter `commands: CommandRunner` (after `callActions`); `@Volatile internal var Listening.sessions: VoiceSessions`; `internal val Listening.commandReports: SharedFlow<CommandReport>`; `internal val Listening.lastReport: CommandReport?`. `:core:screen`: `ScreenSession.showFeedback(text: String, keep: Boolean = false)` and `suspend fun releaseFeedback()`. Task 4 replaces `AppGraph`'s runner that ignores every command with `InstantCommands`, and its device tests replace `Listening.sessions`; plan 3C's calls use `CommandConversation.say` and `countdown`.

Design D2. The Rules now read every transcript that is not Kestrel's own request, and a stop without the name, with `CommandParser.parse` while Kestrel listens with the screen on and the phone unlocked and nothing else runs; the new work `RUNNING_COMMAND` numbers each command (`State.command`), so that the end of an older one changes nothing, and keeps the command and its transcript (`running`, `heard`) for "again". `Listening` runs the command as one job in its `scope` through the `CommandRunner` that `AppGraph` passes in, sends `Event.CommandEnded` with its result, and publishes one `CommandReport` for every command that started (its kind and outcome, never words). A step that leaves `RUNNING_COMMAND` other than through the command's own end always includes `Effect.CancelCommand` (`leaveCommand` in the Rules; `RulesTest.everyEventKeepsARunningCommandOrCancelsIt` checks it for every event): "stop" or "cancel" (named or not, and a transcript that starts with either, also after a word such as "okay", as in "Okay, stop scrolling"), a change of mode, the screen turning off, and Kestrel's own requests, which then run as they would when idle; the two exceptions are a running "lock screen" when the screen turns off and a running call when the call becomes active, because the command caused them. `Listening` cancels the job and keeps it as the stopping job: a later `RunCommand`, `RunTask` or `OpenSettings` starts only once it has completed, or after at most 3 s, which Kestrel reports by the classes of a `StuckCommandException`. This keeps spec 7.3's rule that two parts of Kestrel never drive the screen at once, also when Android finishes a gesture or a binder call of the cancelled command; a command that was cancelled before its deferred start never runs. A command that arrives while another runs is dropped (decision 3): a recognised one, and any speech while dictation may be running (a `TextCommand` or a `Bare` runs), make the bar say "Still busy with the last command."; other bare speech may be conversation and is only shown. "Again" runs the last repeatable command again under a new number with its transcript (`acted` names the command that acted, such as the bare label after the executor's fallback); without one, the bar says "There's no command to repeat.". A result's `passOn`, an answer that did not answer the question, is read at once as a new utterance.

The command talks to the owner through its `CommandConversation`: `say` sends `Event.CommandSays`, which the Rules speak as `Reply.Command` and show on the bar, and returns when that reply has ended in any way (or at once without a voice session); `ask` sends `Event.CommandAsks`, which the Rules speak and keep on the bar, and the next transcript that is not Kestrel's own request nor a stop comes back as the answer; the Orchestrator ends the wait after `questionMillis` (15 s) from the end of the question's reply, or `answerMillis` (35 s) once the owner speaks (`Event.Speaking`), and `ask` then returns null. `keep` shows a text that stays until the command ends, when `Listening` releases it (`FeedbackBar.release()`). `countdown` keeps "…in 4", "…in 3" on the bar and returns true when the time has run out (the call's countdown of plan 3C, decision 1): while `State.counting`, the spotter's "stop" or "cancel" cancels the command at once, whether or not Kestrel speaks; `Event.Speaking` holds the countdown (`Effect.HoldCommand`), and the utterance's transcript then cancels it ("cancel", "stop", or an utterance whose first word, after fillers such as "oh" or "wait" and the name, is "no", whatever follows, as in "No, not her"; a word counts as the name when `Requests` reads it alone as the name, so a misheard "Castrel" counts too), changes the mode, or lets it go on from where it stopped; so does an utterance without a transcript (`Event.NothingHeard`). When the spotter's word has cancelled the command, the transcript of that same word, which comes once Kestrel is idle, is only shown (`State.spottedStop`), so that it never taps a button named "Cancel" or "Stop". Off forgets the last command, so that "again" never repeats a command of an earlier run. After 30 s of holds in total, `countdown` gives up and returns false. The voice pipeline already sends the spotter's hits while Kestrel is silent and recognition is on; the new `ListeningPipelineTest` case pins that, and the pipeline does not change. While a password field has the focus, the bar shows "Typing in a password field." instead of every transcript: `Listening` asks `CommandRunner.privateInput()` before it shows a `Feedback.Heard`. Until Task 4, `AppGraph` passes a runner that ignores every command, so nothing changes for the owner yet. The hook `voice-state` adds the command's number, kind and outcome before `session=`, so that the scripts that read `session=` and `clips=` keep working; `VoiceHookTest`'s pattern of the state while Kestrel is off gets the new fields.

- [ ] **Step 1: Write the failing tests**

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace

```kotlin
package dev.kestrel.app.orchestrator

import dev.kestrel.voice.Keyword
```

with

```kotlin
package dev.kestrel.app.orchestrator

import dev.kestrel.commands.CallTarget
import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Direction
import dev.kestrel.commands.GlobalCommand
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.PhoneCommand
import dev.kestrel.commands.Target
import dev.kestrel.voice.Keyword
```

then replace

```kotlin
    private val listening = State(mode = Mode.LISTENING)
    private val paused = State(mode = Mode.PAUSED)
```

with

```kotlin
    private val listening = State(mode = Mode.LISTENING)
    private val paused = State(mode = Mode.PAUSED)
    private val runningCommand =
        listening.copy(work = Work.RUNNING_COMMAND, command = 1, running = Command.Tap(Target.Label("Send")), heard = "tap Send")
```

and replace

```kotlin
    @Test
    fun otherSpeechGoesToTheCommandsOnlyWhileListening() {
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Scroll down")), Effect.RunCommand("Scroll down")),
            Rules.next(listening, Event.Heard("Scroll down", named = false)).effects,
        )
        assertEquals(Step(paused), Rules.next(paused, Event.Heard("Scroll down", named = true)))
        assertEquals(Effect.RunCommand("Cancel!"), Rules.next(listening, Event.Heard("Cancel!", named = false)).effects.last())
    }
```

with

```kotlin
    @Test
    fun otherSpeechRunsAnInstantCommandOnlyWhileListening() {
        val step = Rules.next(listening, Event.Heard("Scroll down", named = false))

        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Scroll down")), Effect.RunCommand(1, Command.Scroll(Direction.DOWN, null), "Scroll down")),
            step.effects,
        )
        assertEquals(Work.RUNNING_COMMAND, step.state.work)
        assertEquals(1, step.state.command)
        assertEquals(Recognition.ALL, step.state.recognition)
        assertEquals(Step(paused), Rules.next(paused, Event.Heard("Scroll down", named = true)))
        // Plain "cancel" while nothing runs may name a button (section 9.5).
        assertEquals(
            Effect.RunCommand(1, Command.Bare("Cancel!", null), "Cancel!"),
            Rules.next(listening, Event.Heard("Cancel!", named = false)).effects.last(),
        )
        val dark = listening.copy(screenOn = false)
        assertEquals(Step(dark), Rules.next(dark, Event.Heard("Scroll down", named = false)))
    }

    @Test
    fun aCommandEndsWithItsResultAndARepeatableOneBecomesTheLastCommand() {
        val started = Rules.next(listening, Event.Heard("tap Send", named = false)).state

        val ended = Rules.next(started, Event.CommandEnded(1, CommandResult(Outcome.DONE, show = "It's already on", repeatable = true)))

        assertEquals(Work.IDLE, ended.state.work)
        assertEquals(null, ended.state.running)
        assertEquals(Command.Tap(Target.Label("Send")), ended.state.lastCommand)
        assertEquals("tap Send", ended.state.lastHeard)
        assertEquals(listOf(Effect.Show(Feedback.CommandText("It's already on"))), ended.effects)
        // An older command's end, and an end after the work left the command, change nothing.
        assertEquals(Step(started), Rules.next(started, Event.CommandEnded(0, CommandResult(Outcome.DONE))))
        assertEquals(Step(ended.state), Rules.next(ended.state, Event.CommandEnded(1, CommandResult(Outcome.DONE))))
        // A spoken result of a command that is not repeatable, and a cancel, which the bar shows.
        val said = Rules.next(started, Event.CommandEnded(1, CommandResult(Outcome.FAILED, say = "I can't find Send.")))
        assertEquals(listOf(Effect.Say(Reply.Text("I can't find Send."))), said.effects)
        assertEquals(1, said.state.speeches)
        assertEquals(null, said.state.lastCommand)
        assertEquals(
            listOf(Effect.Show(Feedback.CommandCancelled)),
            Rules.next(started, Event.CommandEnded(1, CommandResult(Outcome.CANCELLED))).effects,
        )
        // A touch that cancels a question while Kestrel still says it stops the question too.
        assertEquals(
            listOf(Effect.Show(Feedback.CommandCancelled), Effect.StopSaying),
            Rules.next(started.copy(speeches = 1), Event.CommandEnded(1, CommandResult(Outcome.CANCELLED))).effects,
        )
    }

    @Test
    fun againRepeatsTheCommandThatActedWithItsTranscript() {
        val started = Rules.next(listening, Event.Heard("tap Delete", named = false)).state
        // The executor fell back to the bare label, and that command acted.
        val acted = CommandResult(Outcome.DONE, repeatable = true, acted = Command.Bare("Delete", null))
        val ended = Rules.next(started, Event.CommandEnded(1, acted)).state

        val again = Rules.next(ended, Event.Heard("Again.", named = false))

        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Again.")), Effect.RunCommand(2, Command.Bare("Delete", null), "tap Delete")),
            again.effects,
        )
        assertEquals(Command.Bare("Delete", null), again.state.running)
        // Off forgets it, so that "again" never repeats a command of an earlier run.
        assertEquals(null, Rules.next(ended, Event.TurnOff).state.lastCommand)
        // Without a repeatable command, the bar says so.
        assertEquals(
            Step(listening, listOf(Effect.Show(Feedback.Heard("again")), Effect.Show(Feedback.NothingToRepeat))),
            Rules.next(listening, Event.Heard("again", named = false)),
        )
    }

    @Test
    fun stopCancelAndSpeechThatStartsWithThemCancelACommand() {
        for (said in listOf("Stop.", "cancel", "Kestrel, stop.", "Cancel it, Kestrel.", "Stop scrolling", "Okay, stop scrolling")) {
            val step = Rules.next(runningCommand, Event.Heard(said, named = false))
            assertEquals(said, Work.IDLE, step.state.work)
            assertEquals(said, listOf(Effect.Show(Feedback.Heard(said)), Effect.CancelCommand, Effect.Show(Feedback.CommandCancelled)), step.effects)
        }
        // A question or an announcement in progress stops too.
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop.")), Effect.CancelCommand, Effect.StopSaying, Effect.Show(Feedback.CommandCancelled)),
            Rules.next(runningCommand.copy(speeches = 1), Event.Heard("Stop.", named = false)).effects,
        )
    }

    @Test
    fun kestrelsOwnRequestsCancelTheCommandFirstAndThenAct() {
        val task = Rules.next(runningCommand, Event.Heard("Kestrel, open Viber.", named = true))
        assertEquals(
            listOf(Effect.CancelCommand, Effect.Show(Feedback.Heard("Kestrel, open Viber.")), Effect.RunTask(1, "open Viber.")),
            task.effects,
        )
        assertEquals(Work.RUNNING_TASK, task.state.work)

        val settings = Rules.next(runningCommand, Event.Heard("Kestrel, open settings.", named = true))
        assertEquals(
            listOf(Effect.CancelCommand, Effect.Show(Feedback.Heard("Kestrel, open settings.")), Effect.OpenSettings),
            settings.effects,
        )

        val pausing = Rules.next(runningCommand, Event.Heard("Stop listening.", named = false))
        assertEquals(Mode.PAUSED, pausing.state.mode)
        assertEquals(Effect.CancelCommand, pausing.effects.first())

        val assisted = Rules.next(runningCommand, Event.Assist)
        assertEquals(Effect.CancelCommand, assisted.effects.first())
        assertEquals(Work.AWAITING_TASK, assisted.state.work)
    }

    @Test
    fun aCommandThatArrivesWhileAnotherRunsIsDropped() {
        assertEquals(
            Step(runningCommand, listOf(Effect.Show(Feedback.Heard("Scroll down")), Effect.Show(Feedback.CommandBusy))),
            Rules.next(runningCommand, Event.Heard("Scroll down", named = false)),
        )
        assertEquals(Effect.Show(Feedback.CommandBusy), Rules.next(runningCommand, Event.Heard("Again", named = false)).effects.last())
        // Bare speech may be conversation, so the bar does not say so, except while dictation may run.
        assertEquals(
            Step(runningCommand, listOf(Effect.Show(Feedback.Heard("Hello there")))),
            Rules.next(runningCommand, Event.Heard("Hello there", named = false)),
        )
        val dictating = runningCommand.copy(running = Command.Bare("Hello", null))
        assertEquals(Effect.Show(Feedback.CommandBusy), Rules.next(dictating, Event.Heard("Hello there", named = false)).effects.last())
    }

    @Test
    fun aQuestionIsSpokenKeptOnTheBarAndTheNextTranscriptAnswersIt() {
        val asked = Rules.next(runningCommand, Event.CommandAsks(1, "Which one?"))

        assertTrue(asked.state.asking)
        assertEquals(
            listOf(Effect.Show(Feedback.CommandText("Which one?", keep = true)), Effect.Say(Reply.Command(1, "Which one?")), Effect.WaitForAnswer()),
            asked.effects,
        )
        val answered = Rules.next(asked.state, Event.Heard("To.", named = false))
        assertFalse(answered.state.asking)
        assertEquals(Work.RUNNING_COMMAND, answered.state.work)
        assertEquals(listOf(Effect.Show(Feedback.Heard("To.")), Effect.AnswerCommand(1, "To.")), answered.effects)
        // A question of an older command changes nothing.
        assertEquals(Step(runningCommand), Rules.next(runningCommand, Event.CommandAsks(0, "Which one?")))
    }

    @Test
    fun theWaitForAnAnswerStartsAgainWhenTheQuestionWasSpokenAndGrowsWhenTheOwnerAnswers() {
        val asked = Rules.next(runningCommand, Event.CommandAsks(1, "Which one?")).state

        val spoken = Rules.next(asked, Event.SpeechEnded)
        assertEquals(listOf(Effect.WaitForAnswer()), spoken.effects)
        assertEquals(2, spoken.state.wait)
        val answering = Rules.next(spoken.state, Event.Speaking)
        assertEquals(listOf(Effect.WaitForAnswer(answering = true)), answering.effects)
        // The end of an older wait changes nothing; the end of the latest one ends the question without an answer.
        assertEquals(Step(answering.state), Rules.next(answering.state, Event.AnswerWaitEnded(2)))
        val ended = Rules.next(answering.state, Event.AnswerWaitEnded(3))
        assertFalse(ended.state.asking)
        assertEquals(listOf(Effect.AnswerCommand(1, null)), ended.effects)
        // An utterance without a transcript gives the owner the whole wait again.
        assertEquals(listOf(Effect.WaitForAnswer()), Rules.next(answering.state, Event.NothingHeard).effects)
    }

    @Test
    fun aCountdownHoldsWhileTheOwnerSpeaksAndOnlyCancelStopNoOrAModeChangeEndIt() {
        val counting = Rules.next(runningCommand, Event.CommandCounts(1, counting = true)).state
        assertTrue(counting.counting)
        assertEquals(listOf(Effect.HoldCommand(1, hold = true)), Rules.next(counting, Event.Speaking).effects)
        // Anything else lets the countdown go on: other speech, Kestrel's other requests, and an utterance without words.
        for (said in listOf("What time is it?", "Kestrel, open settings.", "Kestrel", "tap Save", "I have no idea.")) {
            val step = Rules.next(counting, Event.Heard(said, named = false))
            assertEquals(said, listOf(Effect.Show(Feedback.Heard(said)), Effect.HoldCommand(1, hold = false)), step.effects)
            assertEquals(said, counting, step.state)
        }
        assertEquals(listOf(Effect.HoldCommand(1, hold = false)), Rules.next(counting, Event.NothingHeard).effects)
        assertEquals(Step(counting), Rules.next(counting, Event.Assist))
        // "Cancel", "stop" and "no", with or without the name, cancel it.
        for (said in listOf(
            "Cancel.", "Stop", "No.", "No, no.", "Kestrel, no.", "Stop that.", "Cancel it, Kestrel.", "Hey Kestrel, no.",
            "No, Castrel.", "Oh no!", "No, no, wait.", "No, not her.", "No, I said Anja.", "Thank you, no.",
        )) {
            val step = Rules.next(counting, Event.Heard(said, named = false))
            assertEquals(said, Effect.CancelCommand, step.effects[1])
            assertFalse(said, step.state.counting)
        }
        // A change of mode cancels it and then acts.
        val pausing = Rules.next(counting, Event.Heard("Stop listening.", named = false))
        assertEquals(Mode.PAUSED, pausing.state.mode)
        assertEquals(Effect.CancelCommand, pausing.effects.first())
        assertEquals(Mode.OFF, Rules.next(counting, Event.Heard("Kestrel, turn off.", named = false)).state.mode)
        assertFalse(Rules.next(counting, Event.CommandCounts(1, counting = false)).state.counting)
    }

    @Test
    fun theSpottersStopOrCancelEndsACountdownAtOnceAndACommandWhileKestrelSpeaks() {
        val counting = runningCommand.copy(counting = true)
        assertEquals(
            Step(listening.copy(command = 1, spottedStop = true), listOf(Effect.CancelCommand, Effect.Show(Feedback.CommandCancelled))),
            Rules.next(counting, Event.Spotted(Keyword.CANCEL)),
        )
        // While Kestrel is silent and no countdown runs, the transcript decides.
        assertEquals(Step(runningCommand), Rules.next(runningCommand, Event.Spotted(Keyword.STOP)))
        val speaking = runningCommand.copy(speeches = 1)
        assertEquals(
            listOf(Effect.CancelCommand, Effect.StopSaying, Effect.Show(Feedback.CommandCancelled)),
            Rules.next(speaking, Event.Spotted(Keyword.STOP)).effects,
        )
        // "Kestrel" over Kestrel's voice cancels the command and waits for a task, but not during a countdown.
        val named = Rules.next(speaking, Event.Spotted(Keyword.KESTREL))
        assertEquals(Effect.CancelCommand, named.effects.first())
        assertEquals(Work.AWAITING_TASK, named.state.work)
        assertEquals(Step(counting.copy(speeches = 1)), Rules.next(counting.copy(speeches = 1), Event.Spotted(Keyword.KESTREL)))
    }

    @Test
    fun theTranscriptOfTheSpottersCancelNamesNoButton() {
        val cancelled = Rules.next(runningCommand.copy(counting = true), Event.Spotted(Keyword.CANCEL)).state

        // The same word's transcript comes once the command has ended: it is only shown.
        val heard = Rules.next(cancelled, Event.Heard("Cancel.", named = false))
        assertEquals(Step(cancelled.copy(spottedStop = false), listOf(Effect.Show(Feedback.Heard("Cancel.")))), heard)
        // A later "cancel" may name a button again.
        assertEquals(
            Effect.RunCommand(2, Command.Bare("Cancel.", null), "Cancel."),
            Rules.next(heard.state, Event.Heard("Cancel.", named = false)).effects.last(),
        )
        // Another transcript runs as usual.
        assertEquals(
            Effect.RunCommand(2, Command.Scroll(Direction.DOWN, null), "Scroll down"),
            Rules.next(cancelled, Event.Heard("Scroll down", named = false)).effects.last(),
        )
    }

    @Test
    fun aChangeOfModeOrTheScreenTurningOffCancelsTheCommand() {
        for (event in listOf(Event.Toggle, Event.Pause, Event.TurnOff, Event.Screen(on = false), Event.CallState(CallState.RINGING))) {
            val step = Rules.next(runningCommand, event)
            assertEquals("$event", Effect.CancelCommand, step.effects.first())
            assertEquals("$event", Work.IDLE, step.state.work)
            assertEquals("$event", null, step.state.running)
        }
        assertEquals(Mode.PAUSED, Rules.next(runningCommand, Event.Toggle).state.mode)
    }

    @Test
    fun lockingTheScreenAndTheCallThatACommandPlacedLeaveItRunning() {
        val locking = runningCommand.copy(running = PhoneCommand.Global(GlobalCommand.LOCK_SCREEN))
        val dark = Rules.next(locking, Event.Screen(on = false))
        assertEquals(Step(locking.copy(screenOn = false)), dark)
        assertEquals(Work.IDLE, Rules.next(dark.state, Event.CommandEnded(1, CommandResult(Outcome.DONE))).state.work)

        val calling = runningCommand.copy(running = PhoneCommand.Call(CallTarget.Number("0641234567")))
        val active = Rules.next(calling, Event.CallState(CallState.ACTIVE))
        assertEquals(Mode.IN_CALL, active.state.mode)
        assertEquals(Work.RUNNING_COMMAND, active.state.work)
        assertFalse(Effect.CancelCommand in active.effects)
        assertEquals(Work.IDLE, Rules.next(active.state, Event.CommandEnded(1, CommandResult(Outcome.DONE))).state.work)
    }

    @Test
    fun everyEventKeepsARunningCommandOrCancelsIt() {
        val events = listOf(
            Event.Toggle, Event.Start(), Event.Pause, Event.Resume, Event.TurnOff, Event.Heard("Scroll down", named = false),
            Event.Heard("Stop.", named = false), Event.Heard("", named = false), Event.Heard("Kestrel", named = true),
            Event.Speaking, Event.Spotted(Keyword.STOP), Event.Spotted(Keyword.CANCEL), Event.Spotted(Keyword.KESTREL),
            Event.SpeechEnded, Event.TaskEnded(1, "Done."), Event.MicBusy(true), Event.MicBusy(false), Event.Screen(on = true),
            Event.Screen(on = false), Event.WaitEnded(1), Event.SettingsUnavailable, Event.NothingHeard,
            Event.CallState(CallState.IDLE), Event.CallState(CallState.RINGING), Event.CallState(CallState.ACTIVE), Event.Assist,
            Event.Options(WakeUpMode.STAY_OFF, pauseAfterSilence = true, callCommands = true), Event.Silent(1),
            Event.CommandAsks(1, "Which one?"), Event.CommandSays(1, "Calling."), Event.CommandCounts(1, counting = true),
            Event.AnswerWaitEnded(1), Event.CommandEnded(2, CommandResult(Outcome.DONE)),
        )
        val starts = listOf(
            runningCommand,
            runningCommand.copy(speeches = 1),
            runningCommand.copy(asking = true, wait = 1),
            runningCommand.copy(counting = true),
        )
        for (from in starts) {
            for (event in events) {
                val step = Rules.next(from, event)
                val goesOn = step.state.work == Work.RUNNING_COMMAND && step.state.command == 1 && step.state.running == from.running
                assertTrue("$event from $from", goesOn || Effect.CancelCommand in step.effects)
            }
        }
    }

    @Test
    fun anAnswerThatWasNoAnswerIsReadAsANewUtterance() {
        val ended = Rules.next(runningCommand, Event.CommandEnded(1, CommandResult(Outcome.CANCELLED, passOn = "Scroll down")))

        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Scroll down")), Effect.RunCommand(2, Command.Scroll(Direction.DOWN, null), "Scroll down")),
            ended.effects,
        )
        assertEquals(2, ended.state.command)
    }

    @Test
    fun theStatePrintsNoWords() {
        val printed = runningCommand.copy(lastCommand = Command.Bare("secret words", null), lastHeard = "secret words").toString()

        assertFalse(printed, printed.contains("secret") || printed.contains("Send"))
    }
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/OrchestratorTest.kt`, replace

```kotlin
import org.junit.Assert.assertFalse
import org.junit.Test
```

with

```kotlin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
```

and replace

```kotlin
    @Test
    fun theWaitAfterYesEndsAfterEightSeconds() = runTest {
```

with

```kotlin
    @Test
    fun anUnansweredQuestionEndsFifteenSecondsAfterItWasSpoken() = runTest {
        val orchestrator = Orchestrator(backgroundScope, { performed += it }, { errors += it })
        orchestrator.send(Event.Toggle)
        orchestrator.send(Event.Heard("tap Send", named = false))
        orchestrator.send(Event.CommandAsks(1, "Which one?"))
        runCurrent()
        // The question took 5 s to say; the 15 s count from its end.
        advanceTimeBy(5_000)
        orchestrator.send(Event.SpeechEnded)
        runCurrent()

        advanceTimeBy(14_999)
        runCurrent()
        assertTrue(orchestrator.state.value.asking)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(orchestrator.state.value.asking)
        assertEquals(Effect.AnswerCommand(1, null), performed.last())
    }

    @Test
    fun aCommandThatCannotStartEndsAsFailed() = runTest {
        val orchestrator = Orchestrator(backgroundScope, { if (it is Effect.RunCommand) error("No runner") }, { errors += it })

        orchestrator.send(Event.Toggle)
        orchestrator.send(Event.Heard("tap Send", named = false))
        runCurrent()

        assertEquals(Work.IDLE, orchestrator.state.value.work)
        assertEquals(1, errors.size)
    }

    @Test
    fun theWaitAfterYesEndsAfterEightSeconds() = runTest {
```

In `core/voice/src/test/kotlin/dev/kestrel/voice/listen/ListeningPipelineTest.kt`, replace

```kotlin
    @Test
    fun kestrelsOwnKeywordsAreIgnored() = runTest {
```

with

```kotlin
    @Test
    fun theSpottersHitsComeWhileKestrelIsSilentAndRecognitionIsOn() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.ALL
        detector.hearsSpeech = true

        spotter.next.addLast(listOf(KeywordHit(Keyword.CANCEL, 0.02f)))
        pipeline.feed()
        spotter.next.addLast(listOf(KeywordHit(Keyword.STOP, 0.04f)))
        pipeline.feed()

        // Design D2: a call's countdown stops at the spotter's "cancel" or "stop" also while Kestrel is silent.
        assertEquals(
            listOf(VoiceEvent.Spotted(Keyword.CANCEL), VoiceEvent.Spotted(Keyword.STOP)),
            events.filterIsInstance<VoiceEvent.Spotted>(),
        )
    }

    @Test
    fun kestrelsOwnKeywordsAreIgnored() = runTest {
```

This test passes already: it pins what the countdown relies on, so that a later change of the pipeline cannot hold the spotter's hits back.

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace

```kotlin
package dev.kestrel.app.listen

import dev.kestrel.app.orchestrator.CallState
```

with

```kotlin
package dev.kestrel.app.listen

import dev.kestrel.app.commands.CommandConversation
import dev.kestrel.app.commands.CommandReport
import dev.kestrel.app.commands.CommandRunner
import dev.kestrel.app.orchestrator.CallState
```

then replace

```kotlin
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.voice.Keyword
```

with

```kotlin
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Direction
import dev.kestrel.commands.Outcome
import dev.kestrel.voice.Keyword
```

then replace

```kotlin
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
```

with

```kotlin
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
```

then replace

```kotlin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
```

with

```kotlin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
```

then replace

```kotlin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
```

with

```kotlin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
```

then replace

```kotlin
    private val storedOptions = MutableStateFlow(ListeningOptions())
```

with

```kotlin
    private val storedOptions = MutableStateFlow(ListeningOptions())

    // The texts that the bar keeps until they are released, and how often a kept text was released.
    private val kept = mutableListOf<String>()
    private var releases = 0
    private val bar = object : FeedbackBar {
        override fun show(text: String, keep: Boolean) {
            shown += text
            if (keep) kept += text
        }

        override fun release() {
            releases++
        }
    }
    private val runner = FakeRunner()
    private val commandReports = mutableListOf<CommandReport>()

    // How often a countdown of countDown() ran out, as a call would be placed then.
    private var placedCalls = 0
```

then replace

```kotlin
    @Test
    fun theFeedbackBarShowsModesTranscriptsAndMicBusy() = runTest {
```

with

```kotlin
    @Test
    fun aCommandRunsAsOneJobAndReportsItsEnd() = runTest {
        val listening = listeningWithSession()
        collectReports(listening)

        listening.send(Event.Heard("Scroll down", named = false))
        runCurrent()

        assertEquals(listOf<Command>(Command.Scroll(Direction.DOWN, null)), runner.started)
        assertEquals(listOf("Scroll down"), runner.heard)
        assertEquals(Work.IDLE, listening.state.value.work)
        assertEquals(Command.Scroll(Direction.DOWN, null), listening.state.value.lastCommand)
        assertEquals(listOf(CommandReport(1, "Scroll", Outcome.DONE)), commandReports)
        assertEquals(CommandReport(1, "Scroll", Outcome.DONE), listening.lastReport)
    }

    @Test
    fun aNewCommandWaitsUntilTheCancelledOneHasCompleted() = runTest {
        val listening = listeningWithSession()
        collectReports(listening)
        val gate = CompletableDeferred<Unit>()
        // Like a gesture or a binder call that Android finishes, the first command ignores its cancellation for a while.
        runner.action = {
            withContext(NonCancellable) { gate.await() }
            CommandResult(Outcome.DONE)
        }
        listening.send(Event.Heard("Scroll down", named = false))
        runCurrent()

        runner.action = { CommandResult(Outcome.DONE) }
        listening.send(Event.Heard("Stop.", named = false))
        listening.send(Event.Heard("Scroll up", named = false))
        runCurrent()
        assertEquals(1, runner.started.size)

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf<Command>(Command.Scroll(Direction.DOWN, null), Command.Scroll(Direction.UP, null)), runner.started)
        assertEquals(listOf(Outcome.CANCELLED, Outcome.DONE), commandReports.map { it.outcome })
    }

    @Test
    fun aStuckCancelledCommandHoldsTheNextOneForAtMostThreeSeconds() = runTest {
        val listening = listeningWithSession()
        val gate = CompletableDeferred<Unit>()
        runner.action = {
            withContext(NonCancellable) { gate.await() }
            CommandResult(Outcome.DONE)
        }
        listening.send(Event.Heard("Scroll down", named = false))
        runCurrent()
        runner.action = { CommandResult(Outcome.DONE) }
        listening.send(Event.Heard("Stop.", named = false))
        listening.send(Event.Heard("Scroll up", named = false))
        runCurrent()

        advanceTimeBy(2_999)
        runCurrent()
        assertEquals(1, runner.started.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(Command.Scroll(Direction.UP, null), runner.started.last())
        assertEquals("A cancelled instant command", reports.last().first)
        // Kestrel goes on without the stuck job: the next command starts at once.
        listening.send(Event.Heard("Scroll down", named = false))
        runCurrent()
        assertEquals(3, runner.started.size)
        gate.complete(Unit)
        runCurrent()
    }

    @Test
    fun theSettingsWaitUntilTheCancelledCommandHasCompleted() = runTest {
        val listening = listeningWithSession()
        val gate = CompletableDeferred<Unit>()
        runner.action = {
            withContext(NonCancellable) { gate.await() }
            CommandResult(Outcome.DONE)
        }
        listening.send(Event.Heard("Scroll down", named = false))
        runCurrent()
        log.clear()

        // The request cancels the command first; the settings open only once its job has completed.
        listening.send(Event.Heard("Kestrel, open settings.", named = true))
        runCurrent()
        assertFalse("open settings" in log)

        gate.complete(Unit)
        runCurrent()
        assertTrue("open settings" in log)
    }

    @Test
    fun sayReturnsAtOnceWhenTheReplyCannotStart() = runTest {
        val listening = listeningWithSession()
        sessions.opened.single().speakFailure = IllegalStateException("No voice")
        var said = false
        runner.action = { conversation ->
            conversation.say("Calling 112.")
            said = true
            CommandResult(Outcome.DONE)
        }

        listening.send(Event.Heard("tap Send", named = false))
        runCurrent()

        assertTrue(said)
        assertEquals(Work.IDLE, listening.state.value.work)
    }

    @Test
    fun aRefusedQuestionStaysOnTheBarAsTheKeptText() = runTest {
        val listening = listeningWithSession()
        runner.action = { conversation ->
            conversation.ask("Which one?")
            CommandResult(Outcome.CANCELLED)
        }
        listening.send(Event.Heard("tap Delete", named = false))
        runCurrent()
        val before = shown.size

        // Android refuses the question's reply: the bar keeps the question, and shows no copy of it that would fade.
        sessions.opened.single().replies.last().complete(Spoke.REFUSED)
        runCurrent()

        assertEquals(before, shown.size)
        assertEquals(listOf("Which one?"), kept)
    }

    @Test
    fun aCommandCancelledBeforeItStartedNeverRuns() = runTest {
        val listening = listeningWithSession()
        collectReports(listening)
        val gate = CompletableDeferred<Unit>()
        runner.action = {
            withContext(NonCancellable) { gate.await() }
            CommandResult(Outcome.DONE)
        }
        listening.send(Event.Heard("Scroll down", named = false))
        runCurrent()
        listening.send(Event.Heard("Stop.", named = false))
        listening.send(Event.Heard("Scroll up", named = false))
        listening.send(Event.Heard("Stop.", named = false))
        runCurrent()

        gate.complete(Unit)
        runCurrent()

        assertEquals(1, runner.started.size)
        assertEquals(listOf(CommandReport(1, "Scroll", Outcome.CANCELLED), CommandReport(2, "Scroll", Outcome.CANCELLED)), commandReports)
        assertEquals(Work.IDLE, listening.state.value.work)
    }

    @Test
    fun aQuestionIsSpokenAndTheNextTranscriptIsItsAnswer() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        var answer: String? = "none"
        runner.action = { conversation ->
            answer = conversation.ask("Which one?")
            CommandResult(Outcome.DONE)
        }
        listening.send(Event.Heard("tap Send", named = false))
        runCurrent()
        assertEquals(listOf("Which one?"), session.said)
        assertEquals(listOf("Which one?"), kept)
        assertTrue(listening.state.value.asking)

        session.replies.single().complete(Spoke.FINISHED)
        listening.send(Event.Heard("Number two.", named = false))
        runCurrent()

        assertEquals("Number two.", answer)
        assertEquals(Work.IDLE, listening.state.value.work)
        // The kept question fades once the command has ended.
        assertEquals(1, releases)
    }

    @Test
    fun aQuestionWithoutAnAnswerEndsFifteenSecondsAfterItWasSpoken() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        var answer: String? = "none"
        runner.action = { conversation ->
            answer = conversation.ask("Which one?")
            CommandResult(if (answer == null) Outcome.CANCELLED else Outcome.DONE)
        }
        listening.send(Event.Heard("tap Send", named = false))
        runCurrent()
        advanceTimeBy(3_000)
        session.replies.single().complete(Spoke.FINISHED)
        runCurrent()

        advanceTimeBy(14_999)
        runCurrent()
        assertEquals("none", answer)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(null, answer)
        assertEquals(ListeningTexts.CANCELLED, shown.last())
    }

    @Test
    fun sayReturnsWhenTheReplyHasEnded() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        var said = false
        runner.action = { conversation ->
            conversation.say("Calling 112.")
            said = true
            CommandResult(Outcome.DONE)
        }
        listening.send(Event.Heard("tap Send", named = false))
        runCurrent()
        assertEquals(listOf("Calling 112."), session.said)
        assertEquals("Calling 112.", shown.last())
        assertFalse(said)

        // A reply that Android interrupted has ended too.
        session.replies.single().complete(Spoke.INTERRUPTED)
        runCurrent()
        assertTrue(said)
    }

    @Test
    fun theOwnersSpeechHoldsTheCountdownAndALateCancelStillStopsIt() = runTest {
        val listening = listeningWithSession()
        collectReports(listening)
        countDown()
        listening.send(Event.Heard("tap Send", named = false))
        runCurrent()
        assertEquals("Calling in 4", kept.last())

        advanceTimeBy(1_000)
        listening.send(Event.Speaking)
        runCurrent()
        // The transcript of "cancel" comes 5 s after the word, later than the countdown would have ended.
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, placedCalls)
        listening.send(Event.Heard("Cancel.", named = false))
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(0, placedCalls)
        assertEquals(Outcome.CANCELLED, commandReports.single().outcome)
        assertEquals(ListeningTexts.CANCELLED, shown.last())
    }

    @Test
    fun otherSpeechLetsTheCountdownGoOnFromWhereItStopped() = runTest {
        val listening = listeningWithSession()
        countDown()
        listening.send(Event.Heard("tap Send", named = false))
        runCurrent()
        advanceTimeBy(1_000)
        listening.send(Event.Speaking)
        runCurrent()
        advanceTimeBy(5_000)
        listening.send(Event.Heard("What time is it?", named = false))
        runCurrent()

        // 3 s of the countdown were left when the owner started to speak.
        advanceTimeBy(2_900)
        runCurrent()
        assertEquals(0, placedCalls)
        advanceTimeBy(400)
        runCurrent()
        assertEquals(1, placedCalls)
    }

    @Test
    fun theSpottersCancelStopsTheCountdownAtOnceWhileKestrelIsSilent() = runTest {
        val listening = listeningWithSession()
        collectReports(listening)
        countDown()
        listening.send(Event.Heard("tap Send", named = false))
        runCurrent()
        advanceTimeBy(1_000)

        listening.send(Event.Spotted(Keyword.CANCEL))
        runCurrent()

        assertEquals(Outcome.CANCELLED, commandReports.single().outcome)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, placedCalls)
    }

    @Test
    fun anUtteranceWithoutATranscriptLetsTheCountdownGoOn() = runTest {
        val listening = listeningWithSession()
        countDown()
        listening.send(Event.Heard("tap Send", named = false))
        runCurrent()
        advanceTimeBy(1_000)
        listening.send(Event.Speaking)
        runCurrent()
        advanceTimeBy(1_000)
        listening.send(Event.NothingHeard)
        runCurrent()

        advanceTimeBy(2_900)
        runCurrent()
        assertEquals(0, placedCalls)
        advanceTimeBy(400)
        runCurrent()
        assertEquals(1, placedCalls)
    }

    @Test
    fun thirtySecondsOfHoldsGiveTheCountdownUp() = runTest {
        val listening = listeningWithSession()
        collectReports(listening)
        countDown()
        listening.send(Event.Heard("tap Send", named = false))
        runCurrent()
        advanceTimeBy(1_000)
        // A television that keeps talking: the owner never seems to finish.
        listening.send(Event.Speaking)
        runCurrent()

        advanceTimeBy(29_800)
        runCurrent()
        assertTrue(commandReports.isEmpty())
        advanceTimeBy(400)
        runCurrent()

        assertEquals(0, placedCalls)
        assertEquals(Outcome.CANCELLED, commandReports.single().outcome)
        assertEquals(ListeningTexts.CANCELLED, shown.last())
    }

    @Test
    fun whileAPasswordFieldHasTheFocusTheBarHidesWhatKestrelHeard() = runTest {
        val listening = listeningWithSession()
        runner.password = true

        listening.send(Event.Heard("Hunter two", named = false))
        runCurrent()

        assertEquals(listOf(ListeningTexts.LISTENING, ListeningTexts.PRIVATE_INPUT), shown)
    }

    @Test
    fun aRunnerThatThrowsEndsTheCommandAsFailed() = runTest {
        val listening = listeningWithSession()
        collectReports(listening)
        runner.action = { throw IllegalStateException("The screen went away") }

        listening.send(Event.Heard("tap Send", named = false))
        runCurrent()

        assertEquals(Work.IDLE, listening.state.value.work)
        assertEquals(ListeningTexts.COMMAND_FAILED, shown.last())
        assertEquals("An instant command", reports.single().first)
        assertEquals(Outcome.FAILED, commandReports.single().outcome)
    }

    @Test
    fun aTextThatTheCommandKeepsIsReleasedWhenItEnds() = runTest {
        val listening = listeningWithSession()
        val gate = CompletableDeferred<Unit>()
        runner.action = { conversation ->
            conversation.keep("1, Ana. 2, Anja.")
            gate.await()
            CommandResult(Outcome.DONE)
        }
        listening.send(Event.Heard("tap Send", named = false))
        runCurrent()
        assertEquals(listOf("1, Ana. 2, Anja."), kept)
        assertEquals(0, releases)

        gate.complete(Unit)
        runCurrent()
        assertEquals(1, releases)
    }

    @Test
    fun theFeedbackBarShowsModesTranscriptsAndMicBusy() = runTest {
```

then replace

```kotlin
        sessions = sessions,
        services = services,
        bar = { shown += it },
        report = { what, error -> reports += what to error },
        openSettings = {
```

with

```kotlin
        sessions = sessions,
        services = services,
        bar = bar,
        report = { what, error -> reports += what to error },
        openSettings = {
```

then replace

```kotlin
        callActions = callActions,
        options = options,
```

with

```kotlin
        callActions = callActions,
        commands = runner,
        options = options,
```

then replace

```kotlin
    private inner class FakeSessions : VoiceSessions {
```

with

```kotlin
    /** Collects the command reports of [listening] from now on. */
    private fun TestScope.collectReports(listening: Listening) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { listening.commandReports.collect { commandReports += it } }
    }

    /** The next command counts down 4 s, as a call does, and counts a call when the countdown runs out. */
    private fun countDown() {
        runner.action = { conversation ->
            if (conversation.countdown(4_000) { "Calling in $it" }) {
                placedCalls++
                CommandResult(Outcome.DONE)
            } else {
                CommandResult(Outcome.CANCELLED)
            }
        }
    }

    private class FakeRunner : CommandRunner {
        val started = mutableListOf<Command>()
        val heard = mutableListOf<String>()
        var password = false

        // What the next command does; by default it ends at once, done and repeatable.
        var action: suspend (CommandConversation) -> CommandResult = { CommandResult(Outcome.DONE, repeatable = true) }

        override suspend fun run(command: Command, heard: String, conversation: CommandConversation): CommandResult {
            started += command
            this.heard += heard
            return action(conversation)
        }

        override fun privateInput() = password
    }

    private inner class FakeSessions : VoiceSessions {
```

then replace

```kotlin
            sessions = sessions,
            services = services,
            bar = { shown += it },
            report = { what, error -> reports += what to error },
            openSettings = { true },
```

with

```kotlin
            sessions = sessions,
            services = services,
            bar = bar,
            report = { what, error -> reports += what to error },
            openSettings = { true },
```

and replace

```kotlin
            callActions = callActions,
            options = storedOptions,
```

with

```kotlin
            callActions = callActions,
            commands = runner,
            options = storedOptions,
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.orchestrator.RulesTest --tests dev.kestrel.app.orchestrator.OrchestratorTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'CommandRunner'", "Unresolved reference 'CommandEnded'", "Unresolved reference 'RUNNING_COMMAND'", "Unresolved reference 'CommandCancelled'" and "No parameter with name 'commands' found".

Run: `scripts\gradle.ps1 :core:voice:testDebugUnitTest --tests dev.kestrel.voice.listen.ListeningPipelineTest`
Expected: `BUILD SUCCESSFUL`, with 29 tests in `ListeningPipelineTest` (the 28 of plan 2D and `theSpottersHitsComeWhileKestrelIsSilentAndRecognitionIsOn`): the pipeline already sends the spotter's hits, so this test passes before and after the task.

- [ ] **Step 3: Run instant commands in the Rules**

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace

```kotlin
package dev.kestrel.app.orchestrator

import dev.kestrel.voice.Keyword
import dev.kestrel.voice.listen.Recognition
```

with

```kotlin
package dev.kestrel.app.orchestrator

import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandParser
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.GlobalCommand
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.PhoneCommand
import dev.kestrel.commands.TextCommand
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.voice.Keyword
import dev.kestrel.voice.listen.Recognition
```

then replace

```kotlin
    /** A task runs, and only "stop" and "cancel" count (section 11.10). */
    RUNNING_TASK,
}
```

with

```kotlin
    /** A task runs, and only "stop" and "cancel" count (section 11.10). */
    RUNNING_TASK,

    /** An instant command runs (section 7.3): it may ask a question or count down, and "stop" or "cancel" cancel it. */
    RUNNING_COMMAND,
}
```

then replace

```kotlin
 * the running ListeningService has reported the screen on and the phone unlocked, so that the next unlock is a wake-up
 * (section 8.5).
 */
data class State(
```

with

```kotlin
 * the running ListeningService has reported the screen on and the phone unlocked, so that the next unlock is a wake-up
 * (section 8.5). [command] is the number of the latest instant command, so that the end of an older one changes nothing;
 * [running] is the command that runs and [heard] its transcript; [asking]: it waits for the answer to its question;
 * [counting]: it counts down, as before a call. [lastCommand] and [lastHeard] are the last command that ended as
 * repeatable and its transcript, for "again" (section 9.5); off forgets them. [spottedStop]: the spotter's "stop" or
 * "cancel" cancelled the command, so the transcript of the same word names no button. It lasts until the next transcript
 * that reaches the commands, so when the pipeline dropped that word, one later "stop…" or "cancel…" is only shown.
 */
data class State(
```

then replace

```kotlin
    val silence: Int = 0,
    val screenSeen: Boolean = false,
) {
    val speaking: Boolean get() = speeches > 0
```

with

```kotlin
    val silence: Int = 0,
    val screenSeen: Boolean = false,
    val command: Int = 0,
    val running: Command? = null,
    val heard: String? = null,
    val asking: Boolean = false,
    val counting: Boolean = false,
    val lastCommand: Command? = null,
    val lastHeard: String? = null,
    val spottedStop: Boolean = false,
) {
    val speaking: Boolean get() = speeches > 0

    // The owner's words never reach a log (section 13.5): only the commands' kinds and the transcripts' lengths.
    override fun toString() =
        "State(mode=$mode, work=$work, speeches=$speeches, task=$task, wait=$wait, cancelled=$cancelled, screenOn=$screenOn, " +
            "micBusy=$micBusy, emptyTranscripts=$emptyTranscripts, noWordsShown=$noWordsShown, beforeCall=$beforeCall, " +
            "callCommands=$callCommands, onWakeUp=$onWakeUp, pauseAfterSilence=$pauseAfterSilence, silence=$silence, " +
            "screenSeen=$screenSeen, command=$command, running=${running?.javaClass?.simpleName}, heard=${heard?.length}, " +
            "asking=$asking, counting=$counting, lastCommand=${lastCommand?.javaClass?.simpleName}, " +
            "lastHeard=${lastHeard?.length}, spottedStop=$spottedStop)"
```

then replace

```kotlin
    /** Timer number [silence] of the pause after 30 s without speech has run out (section 8.5). */
    data class Silent(val silence: Int) : Event
}
```

with

```kotlin
    /** Timer number [silence] of the pause after 30 s without speech has run out (section 8.5). */
    data class Silent(val silence: Int) : Event

    /** Instant command number [command] ended with [result] (section 7.3). Listening sends it for every command that ran. */
    data class CommandEnded(val command: Int, val result: CommandResult) : Event

    /** Command number [command] asks [question] and waits for the answer. */
    data class CommandAsks(val command: Int, val question: String) : Event {
        override fun toString() = "CommandAsks($command, ${question.length} characters)"
    }

    /** Command number [command] says [text], such as a call's announcement. */
    data class CommandSays(val command: Int, val text: String) : Event {
        override fun toString() = "CommandSays($command, ${text.length} characters)"
    }

    /** Command number [command] starts ([counting]) or ends a countdown, as before a call (section 13.6). */
    data class CommandCounts(val command: Int, val counting: Boolean) : Event

    /** Wait number [wait] for the answer to a command's question has lasted long enough. */
    data class AnswerWaitEnded(val wait: Int) : Event
}
```

then replace

```kotlin
    data class Text(val text: String) : Reply {
        override fun toString() = "Text(${text.length} characters)"
    }
}
```

with

```kotlin
    data class Text(val text: String) : Reply {
        override fun toString() = "Text(${text.length} characters)"
    }

    /** What instant command number [command] says or asks. */
    data class Command(val command: Int, val text: String) : Reply {
        override fun toString() = "Command($command, ${text.length} characters)"
    }
}
```

then replace

```kotlin
    /** Three transcripts in a row had no words, so the owner may check the microphone (section 14.6). */
    data object NoWords : Feedback
}
```

with

```kotlin
    /** Three transcripts in a row had no words, so the owner may check the microphone (section 14.6). */
    data object NoWords : Feedback

    /** What an instant command shows. With [keep], the text stays until the command ends or the next text. */
    data class CommandText(val text: String, val keep: Boolean = false) : Feedback {
        override fun toString() = "CommandText(${text.length} characters, keep=$keep)"
    }

    /** "Cancelled": a command was cancelled, or its question was not answered. */
    data object CommandCancelled : Feedback

    /** A command came while another ran, and was dropped (decision 3 of sub-project 3). */
    data object CommandBusy : Feedback

    /** "Again" without a command to repeat. */
    data object NothingToRepeat : Feedback
}
```

then replace

```kotlin
    /** Speech for the instant commands of sub-project 3. */
    data class RunCommand(val text: String) : Effect {
        override fun toString() = "RunCommand(${text.length} characters)"
    }
```

with

```kotlin
    /**
     * Runs instant command number [command], [parsed] from the transcript [heard], which the executor needs for its
     * fallback to a bare label and for dictation. It must send [Event.CommandEnded] once it ends (section 7.3).
     */
    data class RunCommand(val command: Int, val parsed: Command, val heard: String) : Effect {
        override fun toString() = "RunCommand($command, ${parsed.javaClass.simpleName}, ${heard.length} characters)"
    }

    /** Cancels the running instant command. Nothing new may act on the screen until its job has completed. */
    data object CancelCommand : Effect

    /** The answer to the question of command number [command], or null when the wait for it ended. */
    data class AnswerCommand(val command: Int, val text: String?) : Effect {
        override fun toString() = "AnswerCommand($command, ${text?.length} characters)"
    }

    /** Holds the countdown of command number [command] while the owner speaks ([hold]), or lets it go on. */
    data class HoldCommand(val command: Int, val hold: Boolean) : Effect

    /**
     * Starts the timer that ends the latest wait for the answer to a command's question, in place of the one that runs.
     * [answering]: the owner has started to answer. The Orchestrator runs it itself.
     */
    data class WaitForAnswer(val answering: Boolean = false) : Effect
```

then replace

```kotlin
object Rules {
    fun next(state: State, event: Event): Step = silenceTimer(state, event, react(state, event))

    private fun react(state: State, event: Event): Step = when (event) {
```

with

```kotlin
object Rules {
    fun next(state: State, event: Event): Step = silenceTimer(state, event, react(state, event))

    private val STOP_WORDS = setOf("stop", "cancel")

    // Words that may come before "stop" or "cancel", as in "Okay, stop scrolling" or "No, cancel the drag".
    private val BEFORE_STOP = setOf("ok", "okay", "please", "now", "no", "hey", "oh", "just", "wait", "so")

    // Words that may come before or with "no" during a countdown, as in "Oh no", "Wait, no" or "Thank you, no".
    private val NO_FILLERS =
        setOf("oh", "wait", "please", "now", "ok", "okay", "hey", "just", "not", "thanks", "thank", "you", "dont")

    private fun react(state: State, event: Event): Step =
        if (state.work == Work.RUNNING_COMMAND) duringCommand(state, event) else withoutCommand(state, event)

    private fun withoutCommand(state: State, event: Event): Step = when (event) {
```

then replace

```kotlin
        is Event.Silent -> silent(state, event.silence)
    }
```

with

```kotlin
        is Event.Silent -> silent(state, event.silence)
        // A command that has ended or was cancelled may still report; nothing changes then.
        is Event.CommandEnded, is Event.CommandAsks, is Event.CommandSays, is Event.CommandCounts, is Event.AnswerWaitEnded ->
            Step(state)
    }
```

then replace

```kotlin
            Work.IDLE -> whileIdle(counted, request, event.text, shown)
        }
    }
```

with

```kotlin
            Work.IDLE -> whileIdle(counted, request, event.text, shown)
            // duringCommand() reads the transcripts of a running command.
            Work.RUNNING_COMMAND -> Step(counted)
        }
    }
```

then replace

```kotlin
    // Instant commands work only while Kestrel listens (sections 8.5 and 9.5), and not after the screen turned off.
    private fun command(state: State, text: String, shown: Effect): Step =
        if (state.mode == Mode.LISTENING && state.screenOn) Step(state, listOf(shown, Effect.RunCommand(text))) else Step(state)
```

with

```kotlin
    // Instant commands work only while Kestrel listens (sections 8.5 and 9.5), and only with the screen on and the phone
    // unlocked. "Again" runs the last repeatable command again, with its transcript.
    private fun command(state: State, text: String, shown: Effect): Step {
        val spotted = state.spottedStop
        val fresh = if (spotted) state.copy(spottedStop = false) else state
        if (fresh.mode != Mode.LISTENING || !fresh.screenOn) return Step(fresh)
        // Design D2: the transcript of the "stop" or "cancel" whose keyword already cancelled the command names no button.
        if (spotted && startsWithStop(text)) return Step(fresh, listOf(shown))
        return startCommand(fresh, text, shown)
    }

    private fun startCommand(state: State, text: String, shown: Effect): Step {
        val parsed = CommandParser.parse(text) ?: return Step(state, listOf(shown))
        if (parsed != Command.Again) return runCommand(state, parsed, text, shown)
        val last = state.lastCommand
        val lastHeard = state.lastHeard
        if (last == null || lastHeard == null) return Step(state, listOf(shown, Effect.Show(Feedback.NothingToRepeat)))
        return runCommand(state, last, lastHeard, shown)
    }

    // Every command gets the next number, so that the end of an older one changes nothing.
    private fun runCommand(state: State, command: Command, heard: String, vararg first: Effect): Step {
        val number = state.command + 1
        return Step(
            state.copy(work = Work.RUNNING_COMMAND, command = number, running = command, heard = heard),
            first.toList() + Effect.RunCommand(number, command, heard),
        )
    }

    // Section 7.3: while an instant command runs. A step that leaves RUNNING_COMMAND other than through the command's own
    // end goes through leaveCommand(), which cancels the command's job first.
    private fun duringCommand(state: State, event: Event): Step = when (event) {
        is Event.CommandEnded -> if (event.command == state.command) commandEnded(state, event.result) else Step(state)
        is Event.CommandAsks -> if (event.command == state.command) ask(state, event.question) else Step(state)
        is Event.CommandSays -> if (event.command == state.command) {
            say(state, Reply.Command(event.command, event.text), Effect.Show(Feedback.CommandText(event.text)))
        } else {
            Step(state)
        }
        is Event.CommandCounts -> if (event.command == state.command) Step(state.copy(counting = event.counting)) else Step(state)
        is Event.AnswerWaitEnded -> if (state.asking && event.wait == state.wait) {
            Step(state.copy(asking = false), listOf(Effect.AnswerCommand(state.command, null)))
        } else {
            Step(state)
        }
        is Event.Heard -> heardDuringCommand(state, event)
        // The owner starts to speak: a countdown holds until the transcript decides, and an answer gets the longer wait.
        Event.Speaking -> when {
            state.counting -> Step(state, listOf(Effect.HoldCommand(state.command, hold = true)))
            state.asking && !state.speaking -> waitForAnswer(state, answering = true)
            else -> Step(state)
        }
        // An utterance without a transcript lets a held countdown go on, and gives an answer the whole wait again.
        Event.NothingHeard -> when {
            state.counting -> Step(state, listOf(Effect.HoldCommand(state.command, hold = false)))
            state.asking -> waitForAnswer(state)
            else -> Step(state)
        }
        is Event.Spotted -> spottedDuringCommand(state, event.keyword)
        // The wait for the answer starts again when the question has been spoken.
        Event.SpeechEnded ->
            speechEnded(state).let { ended -> if (state.speeches == 1 && state.asking) ended.then { waitForAnswer(it) } else ended }
        is Event.TaskEnded -> lateTaskEnded(state, event)
        // A countdown ignores the assistant gesture, as it ignores Kestrel's other requests.
        Event.Assist -> if (state.counting) Step(state) else keepOrLeave(state, event)
        // The screen that "lock screen" turned off is what the command did; its end still comes.
        is Event.Screen -> if (!event.on && !locksScreen(state.running)) leaveFor(state, event) else keepOrLeave(state, event)
        // The call that "call X" placed becomes active: the command goes on in the mode In call, and its end still counts.
        is Event.CallState -> if (event.state == CallState.ACTIVE && state.running is PhoneCommand.Call) {
            withoutCommand(state, event).let { Step(it.state.copy(work = Work.RUNNING_COMMAND), it.effects) }
        } else {
            keepOrLeave(state, event)
        }
        else -> keepOrLeave(state, event)
    }

    // An event that leaves the work and the mode alone keeps the command; any other cancels it first and then acts as it
    // would without a command.
    private fun keepOrLeave(state: State, event: Event): Step {
        val step = withoutCommand(state, event)
        return if (step.state.work == Work.RUNNING_COMMAND && step.state.mode == state.mode) step else leaveFor(state, event)
    }

    private fun leaveFor(state: State, event: Event): Step = leaveCommand(state).then { withoutCommand(it, event) }

    // Cancels the running command's job, and stops a question or an announcement in progress.
    private fun leaveCommand(state: State, vararg first: Effect): Step = Step(
        state.copy(work = Work.IDLE, running = null, heard = null, asking = false, counting = false),
        first.toList() + Effect.CancelCommand + listOfNotNull(Effect.StopSaying.takeIf { state.speaking }),
    )

    // "Stop" or "cancel": the command is cancelled, and the bar says so.
    private fun cancelCommand(state: State, vararg first: Effect): Step =
        leaveCommand(state, *first).let { Step(it.state, it.effects + Effect.Show(Feedback.CommandCancelled)) }

    private fun locksScreen(command: Command?): Boolean = (command as? PhoneCommand.Global)?.action == GlobalCommand.LOCK_SCREEN

    // The command's result: the bar shows it or "Cancelled", Kestrel says what it must say, a repeatable command becomes
    // the one that "again" repeats, and an answer that did not answer the question is read as a new utterance.
    private fun commandEnded(state: State, result: CommandResult): Step {
        val cancelled = result.outcome == Outcome.CANCELLED
        val repeat = result.repeatable && result.outcome == Outcome.DONE
        val ended = state.copy(
            work = Work.IDLE,
            running = null,
            heard = null,
            asking = false,
            counting = false,
            lastCommand = if (repeat) result.acted ?: state.running else state.lastCommand,
            lastHeard = if (repeat) state.heard else state.lastHeard,
        )
        // Copied, because a property of another module allows no smart cast.
        val show = result.show
        val said = result.say
        val passOn = result.passOn
        val shown = when {
            show != null -> Effect.Show(Feedback.CommandText(show))
            cancelled && passOn == null -> Effect.Show(Feedback.CommandCancelled)
            else -> null
        }
        // A touch that cancelled a question also stops it while Kestrel still says it (section 14.1).
        var step = Step(ended, listOfNotNull(shown, Effect.StopSaying.takeIf { cancelled && state.speaking }))
        if (said != null) step = step.then { say(it, Reply.Text(said)) }
        if (passOn == null) return step
        return step.then { heard(it, Event.Heard(passOn, named = false)) }
    }

    // The question stays on the bar until the command ends. The wait for the answer starts now and again when the
    // question has been spoken.
    private fun ask(state: State, question: String): Step = say(
        state.copy(asking = true),
        Reply.Command(state.command, question),
        Effect.Show(Feedback.CommandText(question, keep = true)),
    ).then { waitForAnswer(it) }

    // Every wait gets the next number, as the waits for a task do, so that the end of an older wait changes nothing.
    private fun waitForAnswer(state: State, answering: Boolean = false): Step =
        Step(state.copy(wait = state.wait + 1), listOf(Effect.WaitForAnswer(answering)))

    // Section 7.3 and design D2 of sub-project 3: a transcript while a command runs. "Stop", "cancel", and a transcript
    // that starts with either, cancel it; so does "no" during a countdown. During a countdown, only those and the changes
    // of mode count, and anything else lets it go on. Kestrel's own requests cancel the command and then run. While the
    // command asks, the transcript is the answer; otherwise the new command is dropped.
    private fun heardDuringCommand(state: State, event: Event.Heard): Step {
        if (state.inCall) return callCommand(state, event)
        val request = Requests.read(event.text, event.named)
            ?: return if (state.screenOn) withoutWordsDuringCommand(state) else Step(state)
        val counted = state.copy(emptyTranscripts = 0)
        if (!counted.screenOn && !stopsSomething(request)) return Step(counted)
        val shown = Effect.Show(Feedback.Heard(event.text))
        val stops = request is Request.Stop || (request is Request.Other && startsWithStop(event.text)) ||
            (state.counting && saysNo(event.text, event.named))
        if (stops) return cancelCommand(counted, shown)
        if (state.counting && request != Request.PauseListening && request != Request.TurnOff) {
            return Step(counted, listOf(shown, Effect.HoldCommand(state.command, hold = false)))
        }
        if (request !is Request.Other) return leaveCommand(counted).then { whileIdle(it, request, event.text, shown) }
        if (state.asking) {
            return Step(counted.copy(asking = false), listOf(shown, Effect.AnswerCommand(state.command, request.text)))
        }
        return busy(counted, request.text, shown)
    }

    private fun withoutWordsDuringCommand(state: State): Step {
        val counted = withoutWords(state, awaiting = false)
        return when {
            state.counting -> Step(counted.state, counted.effects + Effect.HoldCommand(state.command, hold = false))
            state.asking -> counted.then { waitForAnswer(it) }
            else -> counted
        }
    }

    private fun startsWithStop(text: String): Boolean = Normalizer.words(text).firstOrNull { it !in BEFORE_STOP } in STOP_WORDS

    // "No", "Kestrel, no", "oh no, wait" or "no, not her": the first word that is neither a filler nor the name is "no",
    // whatever follows it. The name may come anywhere, misspelt too: a word counts as the name when Requests reads it
    // alone as the name.
    private fun saysNo(text: String, named: Boolean): Boolean =
        Normalizer.words(text).firstOrNull { it !in NO_FILLERS && Requests.read(it, named) != Request.Name } == "no"

    // Decision 3 of sub-project 3: a command that arrives while another runs is dropped. A recognised one says so on the
    // bar; bare speech may be conversation, so the bar says so only while dictation may run (a text or a bare command).
    private fun busy(state: State, text: String, shown: Effect): Step {
        val parsed = CommandParser.parse(text)
        val dictating = state.running is TextCommand || state.running is Command.Bare
        return if (parsed != null && (parsed !is Command.Bare || dictating)) {
            Step(state, listOf(shown, Effect.Show(Feedback.CommandBusy)))
        } else {
            Step(state, listOf(shown))
        }
    }

    // The spotter's "stop" or "cancel" cancels the command while Kestrel speaks or a countdown runs, at once. Its "Kestrel"
    // over Kestrel's voice cancels the command and waits for a task, as when idle, except during a countdown.
    private fun spottedDuringCommand(state: State, keyword: Keyword): Step = when {
        state.inCall -> Step(state)
        keyword != Keyword.KESTREL ->
            if (state.counting || state.speaking) cancelCommand(state.copy(spottedStop = true)) else Step(state)
        state.speaking && !state.counting -> leaveCommand(state).then { spotted(it, keyword) }
        else -> Step(state)
    }

    // A task that was cancelled before the command started may still report what it did.
    private fun lateTaskEnded(state: State, event: Event.TaskEnded): Step {
        if (event.task != state.task || !state.cancelled) return Step(state)
        val ended = state.copy(cancelled = false)
        return if (event.reply == null) Step(ended) else say(ended, Reply.Text(event.reply))
    }
```

then replace

```kotlin
            // The next run's reports start the count of wake-ups again (section 8.5).
            screenSeen = false,
        ),
```

with

```kotlin
            // The next run's reports start the count of wake-ups again (section 8.5).
            screenSeen = false,
            // "Again" never repeats a command of an earlier run, and the owner's last words go.
            lastCommand = null,
            lastHeard = null,
            spottedStop = false,
        ),
```
- [ ] **Step 4: Run the timer of the answer in the Orchestrator**

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Orchestrator.kt`, replace

```kotlin
package dev.kestrel.app.orchestrator

import kotlinx.coroutines.CoroutineScope
```

with

```kotlin
package dev.kestrel.app.orchestrator

import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import kotlinx.coroutines.CoroutineScope
```

then replace

```kotlin
 * "Yes?" runs here: [waitMillis] for the owner to start the answer, and [answerMillis] once the owner has started it. So
 * does the timer of the pause after [silenceMillis] without speech (section 8.5).
 */
class Orchestrator(
```

with

```kotlin
 * "Yes?" runs here: [waitMillis] for the owner to start the answer, and [answerMillis] once the owner has started it. So
 * does the timer of the pause after [silenceMillis] without speech (section 8.5), and the timer of the answer to an
 * instant command's question: [questionMillis] for the owner to start the answer, and [answerMillis] once the owner has
 * started it. A [Effect.RunCommand] that throws counts as a command that ended as failed.
 */
class Orchestrator(
```

then replace

```kotlin
    private val silenceMillis: Long = SILENCE_MILLIS,
) {
```

with

```kotlin
    private val silenceMillis: Long = SILENCE_MILLIS,
    private val questionMillis: Long = QUESTION_MILLIS,
) {
```

then replace

```kotlin
    private var silence: Job? = null
```

with

```kotlin
    private var silence: Job? = null
    private var answer: Job? = null
```

then replace

```kotlin
        if (step.state.mode != Mode.LISTENING) {
            silence?.cancel()
            silence = null
        }
```

with

```kotlin
        if (step.state.mode != Mode.LISTENING) {
            silence?.cancel()
            silence = null
        }
        if (!step.state.asking) {
            answer?.cancel()
            answer = null
        }
```

then replace

```kotlin
            } else if (effect == Effect.TimeSilence) {
```

with

```kotlin
            } else if (effect is Effect.WaitForAnswer) {
                val number = step.state.wait
                val millis = if (effect.answering) answerMillis else questionMillis
                answer?.cancel()
                answer = scope.launch {
                    delay(millis)
                    send(Event.AnswerWaitEnded(number))
                }
            } else if (effect == Effect.TimeSilence) {
```

then replace

```kotlin
                        is Effect.RunTask -> notStarted += Event.TaskEnded(effect.task, reply = null)
```

with

```kotlin
                        is Effect.RunTask -> notStarted += Event.TaskEnded(effect.task, reply = null)
                        is Effect.RunCommand -> notStarted += Event.CommandEnded(effect.command, CommandResult(Outcome.FAILED))
```

and replace

```kotlin
        // Section 8.5: the optional pause after 30 s without speech.
        const val SILENCE_MILLIS = 30_000L
```

with

```kotlin
        // Section 8.5: the optional pause after 30 s without speech.
        const val SILENCE_MILLIS = 30_000L

        // Design D2 of sub-project 3: the owner has 15 s to start answering an instant command's question, counted from
        // the end of the question.
        const val QUESTION_MILLIS = 15_000L
```

- [ ] **Step 5: Add the runner's interfaces**

Create `app/src/main/kotlin/dev/kestrel/app/commands/CommandRunner.kt`:

```kotlin
package dev.kestrel.app.commands

import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome

/**
 * Runs the instant commands (design spec, sections 7.3 and 9.5). `Listening` calls [run] in a job of its own, one
 * command at a time, and cancels the job when the owner says "stop" or "cancel" or the mode changes.
 */
internal interface CommandRunner {
    /** Runs [command], which was parsed from the transcript [heard], and returns how it ended. */
    suspend fun run(command: Command, heard: String, conversation: CommandConversation): CommandResult

    /**
     * Whether a password field has the focus, so that the feedback bar must not show what Kestrel heard (section 13.5).
     * The controller's thread calls it before each transcript is shown, so it makes no binder call.
     */
    fun privateInput(): Boolean
}

/** How a running command talks to the owner (design spec, section 7.3). */
internal interface CommandConversation {
    /** Says [text] and shows it on the bar; returns when the reply has ended in any way, or at once without a voice session. */
    suspend fun say(text: String)

    /**
     * Asks [question], keeps it on the bar, and returns the next transcript that is not Kestrel's own request nor a stop,
     * or null when the owner did not answer in time.
     */
    suspend fun ask(question: String): String?

    /** Shows [text] on the bar until the command ends or the next text, as a numbered list. */
    fun keep(text: String)

    /**
     * Counts down [millis], keeping `text(secondsLeft)` on the bar, and returns true when the time has run out. The
     * owner's speech holds the countdown until its transcript decides; after 30 s of holds it returns false. A cancel
     * ends the command's job, so the call then never returns.
     */
    suspend fun countdown(millis: Long, text: (secondsLeft: Int) -> String): Boolean
}

/** What happened to instant command number [command] of the kind [kind] (its class's simple name), never its words. */
internal data class CommandReport(val command: Int, val kind: String, val outcome: Outcome)
```

- [ ] **Step 6: Run the commands in Listening**

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace

```kotlin
import dev.kestrel.app.orchestrator.Effect
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Feedback
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.Orchestrator
import dev.kestrel.app.orchestrator.Reply
import dev.kestrel.app.orchestrator.Request
import dev.kestrel.app.orchestrator.State
import dev.kestrel.voice.ModelsNotReadyException
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.listen.Microphone
import dev.kestrel.voice.speak.Spoke
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
```

with

```kotlin
import dev.kestrel.app.commands.CommandConversation
import dev.kestrel.app.commands.CommandReport
import dev.kestrel.app.commands.CommandRunner
import dev.kestrel.app.orchestrator.Effect
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Feedback
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.Orchestrator
import dev.kestrel.app.orchestrator.Reply
import dev.kestrel.app.orchestrator.Request
import dev.kestrel.app.orchestrator.State
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import dev.kestrel.voice.ModelsNotReadyException
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.listen.Microphone
import dev.kestrel.voice.speak.Spoke
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
```

then replace

```kotlin
/** Shows a text on the feedback bar (design spec, section 8.9), or nothing while the accessibility service is off. */
internal fun interface FeedbackBar {
    fun show(text: String)
}
```

with

```kotlin
/**
 * Shows a text on the feedback bar (design spec, section 8.9), or nothing while the accessibility service is off. A text
 * that an instant command keeps stays until [release] or the next text; any other fades after a few seconds.
 */
internal interface FeedbackBar {
    fun show(text: String, keep: Boolean = false)

    /** A kept text fades from now on. */
    fun release()
}
```

then replace

```kotlin
    const val CANNOT_OPEN_SETTINGS = "Kestrel could not open its settings."
```

with

```kotlin
    const val CANNOT_OPEN_SETTINGS = "Kestrel could not open its settings."

    // The instant commands (sections 7.3 and 9.5).
    const val CANCELLED = "Cancelled"
    const val BUSY = "Still busy with the last command."
    const val NOTHING_TO_REPEAT = "There's no command to repeat."
    const val PRIVATE_INPUT = "Typing in a password field."
    const val COMMAND_FAILED = "The command failed."
```

then replace

```kotlin
 * them on [thread], passes them to the Orchestrator, and keeps the newest in [currentOptions].
 */
class Listening internal constructor(
    private val scope: CoroutineScope,
    private val thread: CoroutineDispatcher,
    private val sessions: VoiceSessions,
```

with

```kotlin
 * them on [thread], passes them to the Orchestrator, and keeps the newest in [currentOptions].
 * [commands] runs the instant commands (sections 7.3 and 9.5): one job at a time in [scope]. A cancelled job stays the
 * stopping job, and a new command, a task or the settings wait until it has completed, for at most 3 s, so that two parts
 * of Kestrel never drive the screen at once. Every command that started gets one report in [commandReports].
 */
class Listening internal constructor(
    private val scope: CoroutineScope,
    private val thread: CoroutineDispatcher,
    sessions: VoiceSessions,
```

then replace

```kotlin
    private val callActions: CallControl,
    options: Flow<ListeningOptions>,
```

with

```kotlin
    private val callActions: CallControl,
    private val commands: CommandRunner,
    options: Flow<ListeningOptions>,
```

then replace

```kotlin
    @Volatile
    internal var microphones: () -> Microphone = microphones
```

with

```kotlin
    @Volatile
    internal var microphones: () -> Microphone = microphones

    /** Opens the voice sessions. The command device tests replace it with sessions that need no speech models. */
    @Volatile
    internal var sessions: VoiceSessions = sessions
```

then replace

```kotlin
    private var assistWhenOpen = false
```

with

```kotlin
    private var assistWhenOpen = false

    // Only [thread] uses these (section 7.3): the running command's job and conversation, the cancelled job that may
    // still act, the number of the newest command to run, and the newest number that was cancelled, also before its job
    // started.
    private var commandJob: Job? = null
    private var conversation: Conversation? = null
    private var stopping: Job? = null
    private var newestCommand = 0
    private var cancelledCommand = 0

    private val reports = MutableSharedFlow<CommandReport>(extraBufferCapacity = 64)

    /** One report for every instant command that started: its number, its kind and its outcome, never words. */
    internal val commandReports: SharedFlow<CommandReport> = reports.asSharedFlow()

    /** The newest of [commandReports], for the hook voice-state. */
    @Volatile
    internal var lastReport: CommandReport? = null
        private set
```

then replace

```kotlin
            is Effect.RunTask -> runTask(effect.task, effect.text)
            // Nothing runs that could be cancelled yet, and the instant commands come with sub-project 3.
            Effect.CancelTask, is Effect.RunCommand -> Unit
```

with

```kotlin
            is Effect.RunTask ->
                afterStopping({ send(Event.TaskEnded(effect.task, reply = null)) }) { runTask(effect.task, effect.text) }
            // Nothing runs that could be cancelled yet; the tasks come with sub-project 5.
            Effect.CancelTask -> Unit
            is Effect.RunCommand -> {
                newestCommand = effect.command
                afterStopping({ send(Event.CommandEnded(effect.command, CommandResult(Outcome.FAILED))) }) { startCommand(effect) }
            }
            Effect.CancelCommand -> cancelCommand()
            is Effect.AnswerCommand -> conversation?.answered(effect.command, effect.text)
            is Effect.HoldCommand -> conversation?.held(effect.command, effect.hold)
```

then replace

```kotlin
                } else {
                    bar.show(text(effect.feedback))
                }
            }
            // The Orchestrator runs the timers itself.
            is Effect.WaitForTask, Effect.TimeSilence -> Unit
            Effect.StopService -> shutdown()
            Effect.OpenSettings -> openSettingsOrSayWhy()
```

with

```kotlin
                } else {
                    bar.show(text(effect.feedback), keep = (effect.feedback as? Feedback.CommandText)?.keep == true)
                }
            }
            // The Orchestrator runs the timers itself.
            is Effect.WaitForTask, Effect.TimeSilence, is Effect.WaitForAnswer -> Unit
            Effect.StopService -> shutdown()
            Effect.OpenSettings -> afterStopping { openSettingsOrSayWhy() }
            // Plan 2D's call commands (Effect.CallCommand) still run at once: a ringing call must not wait 3 s.
```

then replace

```kotlin
            is Reply.Text -> reply.text
        }
        // Section 14.6: a reply that Kestrel cannot speak, or that Android interrupted, goes to the bar, except "Yes?",
        // which the bar shows already at the start of the wait for the task (Feedback.AwaitingTask).
        val showInstead = { if (reply != Reply.Yes) bar.show(text) }
        val current = session
        if (current == null) {
            // Thrown before the reply starts, so that the Orchestrator ends the reply in its place.
            showInstead()
            error("No voice session")
        }
```

with

```kotlin
            is Reply.Text -> reply.text
            is Reply.Command -> reply.text
        }
        // A command's say() waits for this reply, and returns once it has ended in any way.
        val ended = (reply as? Reply.Command)?.let { conversation?.takeSpoken(it.command) }
        // Section 14.6: a reply that Kestrel cannot speak, or that Android interrupted, goes to the bar, except "Yes?",
        // which the bar shows already at the start of the wait for the task (Feedback.AwaitingTask), and a command's
        // text, which the Rules showed already, a question kept until the command ends.
        val showInstead = { if (reply != Reply.Yes && reply !is Reply.Command) bar.show(text) }
        val current = session
        if (current == null) {
            // Thrown before the reply starts, so that the Orchestrator ends the reply in its place.
            showInstead()
            ended?.complete(Unit)
            error("No voice session")
        }
```

then replace

```kotlin
        val spoken = current.speak(text)
        // Registered last: nothing after it may throw, or the Orchestrator would end the reply a second time.
        spoken.invokeOnCompletion { error ->
            if (error != null && error !is CancellationException) {
                report("A reply", error)
                showInstead()
            } else if (error == null && unspoken(spoken.getCompleted())) {
                showInstead()
            }
            send(Event.SpeechEnded)
        }
    }
```

with

```kotlin
        val spoken = try {
            current.speak(text)
        } catch (e: Exception) {
            ended?.complete(Unit)
            throw e
        }
        // Registered last: nothing after it may throw, or the Orchestrator would end the reply a second time.
        spoken.invokeOnCompletion { error ->
            if (error != null && error !is CancellationException) {
                report("A reply", error)
                showInstead()
            } else if (error == null && unspoken(spoken.getCompleted())) {
                showInstead()
            }
            ended?.complete(Unit)
            send(Event.SpeechEnded)
        }
    }
```

then replace

```kotlin
        is Feedback.Heard -> feedback.text
        Feedback.MicBusy -> ListeningTexts.MIC_BUSY
        Feedback.AwaitingTask -> ListeningTexts.YES
        Feedback.NoWords -> ListeningTexts.NO_WORDS
    }
```

with

```kotlin
        // Section 13.5: while a password field has the focus, the bar never shows what Kestrel heard.
        is Feedback.Heard -> if (privateInput()) ListeningTexts.PRIVATE_INPUT else feedback.text
        Feedback.MicBusy -> ListeningTexts.MIC_BUSY
        Feedback.AwaitingTask -> ListeningTexts.YES
        Feedback.NoWords -> ListeningTexts.NO_WORDS
        is Feedback.CommandText -> feedback.text
        Feedback.CommandCancelled -> ListeningTexts.CANCELLED
        Feedback.CommandBusy -> ListeningTexts.BUSY
        Feedback.NothingToRepeat -> ListeningTexts.NOTHING_TO_REPEAT
    }

    private fun privateInput(): Boolean = try {
        commands.privateInput()
    } catch (e: Exception) {
        report("Checking for a password field", e)
        false
    }

    /**
     * Runs [action] at once, or, while a cancelled command's job has not completed, on [thread] once it has, but after
     * at most [STOPPING_MILLIS] (section 7.3). A deferred action that throws is reported, and then [failed] runs.
     */
    private fun afterStopping(failed: () -> Unit = {}, action: () -> Unit) {
        // A cancelled job is no longer active, but it may still run until it completes.
        val job = stopping?.takeIf { !it.isCompleted }
        stopping = job
        if (job == null) {
            action()
            return
        }
        scope.launch(thread) {
            if (withTimeoutOrNull(STOPPING_MILLIS) { job.join() } == null) {
                report("A cancelled instant command", StuckCommandException())
                // Kestrel goes on without it: nothing later waits for it again.
                if (stopping === job) stopping = null
            }
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("An effect", e)
                failed()
            } catch (e: Error) {
                // As in perform(): such as an OutOfMemoryError. Off also ends a command that never started.
                report("An effect", e)
                send(Event.TurnOff)
            }
        }
    }

    // Section 7.3: the command's job in [scope]. Its end goes to the Orchestrator, and its report to [commandReports].
    private fun startCommand(effect: Effect.RunCommand) {
        val kind = effect.parsed.javaClass.simpleName
        // A command that was cancelled while it waited for the stopping job never runs.
        if (effect.command <= cancelledCommand) {
            publish(CommandReport(effect.command, kind, Outcome.CANCELLED))
            return
        }
        val talk = Conversation(effect.command)
        conversation = talk
        // One report per command: a cancel that comes after the body's last check changes nothing. The body writes it
        // before the job completes, and the handler runs only after that.
        var reported = false
        val job = scope.launch {
            val result = try {
                commands.run(effect.parsed, effect.heard, talk)
            } catch (e: Exception) {
                // A cancellation that is not the job's own, such as a timeout inside the command, is a failure.
                ensureActive()
                report("An instant command", e)
                CommandResult(Outcome.FAILED, show = ListeningTexts.COMMAND_FAILED)
            } finally {
                // A text that the command kept on the bar fades from now on.
                bar.release()
            }
            // A command that was cancelled while Android finished its last call ends here, without a result.
            ensureActive()
            send(Event.CommandEnded(effect.command, result))
            reported = true
            publish(CommandReport(effect.command, kind, result.outcome))
        }
        job.invokeOnCompletion { error ->
            if (reported) return@invokeOnCompletion
            when (error) {
                null -> Unit
                is CancellationException -> publish(CommandReport(effect.command, kind, Outcome.CANCELLED))
                else -> {
                    // Such as an OutOfMemoryError: the command still ends, or Kestrel would wait for it for ever.
                    send(Event.CommandEnded(effect.command, CommandResult(Outcome.FAILED, show = ListeningTexts.COMMAND_FAILED)))
                    publish(CommandReport(effect.command, kind, Outcome.FAILED))
                }
            }
        }
        commandJob = job
    }

    // Section 7.3: the cancelled job is kept as the stopping job, so that nothing new acts on the screen before it has
    // completed, also when Android finishes a gesture or a binder call of it.
    private fun cancelCommand() {
        cancelledCommand = newestCommand
        conversation = null
        val job = commandJob ?: return
        commandJob = null
        job.cancel()
        stopping = job
    }

    private fun publish(done: CommandReport) {
        lastReport = done
        reports.tryEmit(done)
    }

    /**
     * How command number [command] talks to the owner (section 7.3). The Orchestrator speaks and shows what it says and
     * asks; [answered] and [held] come from its effects on [thread].
     */
    private inner class Conversation(private val command: Int) : CommandConversation {
        @Volatile
        private var spoken: CompletableDeferred<Unit>? = null

        @Volatile
        private var answer: CompletableDeferred<String?>? = null

        @Volatile
        private var held = false

        override suspend fun say(text: String) {
            val ended = CompletableDeferred<Unit>()
            spoken = ended
            send(Event.CommandSays(command, text))
            ended.await()
        }

        override suspend fun ask(question: String): String? {
            val reply = CompletableDeferred<String?>()
            answer = reply
            send(Event.CommandAsks(command, question))
            return reply.await()
        }

        override fun keep(text: String) = bar.show(text, keep = true)

        override suspend fun countdown(millis: Long, text: (secondsLeft: Int) -> String): Boolean {
            held = false
            send(Event.CommandCounts(command, counting = true))
            try {
                var left = millis
                var heldFor = 0L
                var shown = -1
                while (left > 0) {
                    val seconds = ((left + 999) / 1_000).toInt()
                    if (!held && seconds != shown) {
                        keep(text(seconds))
                        shown = seconds
                    }
                    delay(TICK_MILLIS)
                    if (!held) {
                        left -= TICK_MILLIS
                    } else {
                        heldFor += TICK_MILLIS
                        if (heldFor >= HOLD_LIMIT_MILLIS) return false
                    }
                }
                return true
            } finally {
                send(Event.CommandCounts(command, counting = false))
            }
        }

        /** The waiting say() of this command, which the reply that starts now ends; null when none waits. */
        fun takeSpoken(number: Int): CompletableDeferred<Unit>? = if (number == command) spoken.also { spoken = null } else null

        fun answered(number: Int, text: String?) {
            if (number == command) answer?.complete(text)
        }

        fun held(number: Int, hold: Boolean) {
            if (number == command) held = hold
        }
    }
```

and replace

```kotlin
        // Section 8.5: a crash within this time after a restore keeps Kestrel off, so that a start that crashes does
        // not come back after every crash.
        const val CRASH_AFTER_RESTORE_MILLIS = 120_000L
    }
}
```

with

```kotlin
        // Section 8.5: a crash within this time after a restore keeps Kestrel off, so that a start that crashes does
        // not come back after every crash.
        const val CRASH_AFTER_RESTORE_MILLIS = 120_000L

        // Section 7.3: how long a new command, a task or the settings wait for a cancelled command's job.
        const val STOPPING_MILLIS = 3_000L

        // How often a countdown looks at its hold, and how long the holds may last in all before it gives up, so that
        // speech in the background cannot hold a call for ever (design D2 of sub-project 3).
        const val TICK_MILLIS = 100L
        const val HOLD_LIMIT_MILLIS = 30_000L
    }
}

/** A cancelled instant command's job had not completed after 3 s; Kestrel went on without it. */
private class StuckCommandException : Exception()
```

- [ ] **Step 7: Keep a text on the bar, and wire the bar and a runner in AppGraph**

In `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/FeedbackBar.kt`, replace

```kotlin
 * A thin bar at the top of the screen with what Kestrel heard and does. It never takes touches, and its text
 * fades after a few seconds without a new one (design spec, section 8.9). Use it on the main thread.
```

with

```kotlin
 * A thin bar at the top of the screen with what Kestrel heard and does. It never takes touches, and its text
 * fades after a few seconds without a new one (design spec, section 8.9), except a kept text, such as an instant
 * command's question, which stays until it is released or the next text replaces it. Use it on the main thread.
```

then replace

```kotlin
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
```

with

```kotlin
    // Whether the text on the bar stays until release().
    private var kept = false

    /** Shows [text]. With [keep], it stays until [release] or the next text; otherwise it fades after a few seconds. */
    fun show(text: String, keep: Boolean = false) {
        handler.removeCallbacks(fade)
        view.animate().cancel()
        view.alpha = 1f
        view.text = text
        if (host.show(view, params())) {
            this.text = text
            kept = keep
            if (!keep) handler.postDelayed(fade, FADE_AFTER_MILLIS)
        } else {
            this.text = null
            kept = false
        }
    }

    /** A kept text fades from now on, as any other text does. */
    fun release() {
        if (!kept || text == null) return
        kept = false
        handler.postDelayed(fade, FADE_AFTER_MILLIS)
    }

    fun hide() {
        handler.removeCallbacks(fade)
        text = null
        kept = false
        host.hide(view)
    }
```

In `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt`, replace

```kotlin
    /** Shows [text] on the feedback bar, which fades after a few seconds (design spec, section 8.9). */
    suspend fun showFeedback(text: String) = withContext(Dispatchers.Main) { feedback.show(text) }
```

with

```kotlin
    /**
     * Shows [text] on the feedback bar, which fades after a few seconds (design spec, section 8.9). With [keep], the text
     * stays until [releaseFeedback] or the next text, as an instant command's question does.
     */
    suspend fun showFeedback(text: String, keep: Boolean = false) = withContext(Dispatchers.Main) { feedback.show(text, keep) }

    /** A kept text on the feedback bar fades from now on. */
    suspend fun releaseFeedback() = withContext(Dispatchers.Main) { feedback.release() }
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import android.util.Log
import dev.kestrel.app.listen.AndroidCallApi
```

with

```kotlin
import android.util.Log
import dev.kestrel.app.commands.CommandConversation
import dev.kestrel.app.commands.CommandRunner
import dev.kestrel.app.listen.AndroidCallApi
```

then replace

```kotlin
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.privacy.PrivacyStore
```

with

```kotlin
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import dev.kestrel.privacy.PrivacyStore
```

then replace

```kotlin
            // One main-thread task per text, in order, so that an earlier text never replaces a later one.
            bar = FeedbackBar { text -> scope.launch(Dispatchers.Main) { screen.value?.showFeedback(text) } },
```

with

```kotlin
            // One main-thread task per call, in order, so that an earlier text never replaces a later one.
            bar = object : FeedbackBar {
                override fun show(text: String, keep: Boolean) {
                    scope.launch(Dispatchers.Main) { screen.value?.showFeedback(text, keep) }
                }

                override fun release() {
                    scope.launch(Dispatchers.Main) { screen.value?.releaseFeedback() }
                }
            },
```

and replace

```kotlin
            callActions = CallActions(AndroidCallApi(appContext)) {
                screen.value?.let { session -> AndroidCallScreen(appContext, session.actions) }
            },
```

with

```kotlin
            callActions = CallActions(AndroidCallApi(appContext)) {
                screen.value?.let { session -> AndroidCallScreen(appContext, session.actions) }
            },
            // The executor of the instant commands comes with the next task; until then every command is ignored.
            commands = object : CommandRunner {
                override suspend fun run(command: Command, heard: String, conversation: CommandConversation) =
                    CommandResult(Outcome.IGNORED)

                override fun privateInput() = false
            },
```

- [ ] **Step 8: Run the JVM tests to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.orchestrator.RulesTest --tests dev.kestrel.app.orchestrator.OrchestratorTest --tests dev.kestrel.app.listen.ListeningTest`
Expected: `BUILD SUCCESSFUL`, with 76 tests in `RulesTest` (61 + 15: the replaced `otherSpeechRunsAnInstantCommandOnlyWhileListening` and 15 new ones), 12 in `OrchestratorTest` (10 + 2) and 135 in `ListeningTest` (117 + 18).

- [ ] **Step 9: Report the command in voice-state**

In `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt`, replace

```kotlin
import dev.kestrel.app.orchestrator.Event
```

with

```kotlin
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Work
```

then replace

```kotlin
        val clips = if (graph.listening.microphones === clipFactory) "on" else "off"
        return "mode=${state.mode.name.lowercase()} work=${state.work.name.lowercase()} speaking=${state.speaking} " +
            "microphone=${state.microphone} recognition=${state.recognition.name.lowercase()} " +
            "screenOn=${state.screenOn} micBusy=${state.micBusy} session=$session clips=$clips"
```

with

```kotlin
        val clips = if (graph.listening.microphones === clipFactory) "on" else "off"
        // The latest instant command: its number, its kind and how it ended, never its words.
        val report = graph.listening.lastReport?.takeIf { it.command == state.command }
        val kind = state.running?.javaClass?.simpleName ?: report?.kind ?: "none"
        val outcome = when {
            state.work == Work.RUNNING_COMMAND -> "running"
            report != null -> report.outcome.name.lowercase()
            else -> "none"
        }
        return "mode=${state.mode.name.lowercase()} work=${state.work.name.lowercase()} speaking=${state.speaking} " +
            "microphone=${state.microphone} recognition=${state.recognition.name.lowercase()} " +
            "screenOn=${state.screenOn} micBusy=${state.micBusy} command=${state.command} kind=$kind outcome=$outcome " +
            "session=$session clips=$clips"
```

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceHookTest.kt`, replace

```kotlin
            "mode=off work=idle speaking=false microphone=false recognition=named screenOn=(true|false) micBusy=false session=closed clips=off",
```

with

```kotlin
            "mode=off work=idle speaking=false microphone=false recognition=named screenOn=(true|false) micBusy=false " +
                "command=\\d+ kind=\\w+ outcome=\\w+ session=closed clips=off",
```

In `scripts/hook.ps1`, replace

```powershell
#   voice-state                             the Orchestrator's mode, work, speaking, microphone, recognition, screen and
#                                           "mic busy", whether the voice session is open, and whether Kestrel's next
#                                           start uses the clip microphone (clips=on); never text
```

with

```powershell
#   voice-state                             the Orchestrator's mode, work, speaking, microphone, recognition, screen and
#                                           "mic busy", the latest instant command's number, kind and outcome, whether
#                                           the voice session is open, and whether Kestrel's next start uses the clip
#                                           microphone (clips=on); never text
```

- [ ] **Step 10: Update the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace

```markdown
Each transition is defined in one place and covered by unit tests: the Orchestrator lives in `:app`, and its rules are one pure function from the state and an event to the new state and a list of effects, such as speaking or running a task. The microphone and recognition are not effects; they follow from the state, so they cannot drift from it. Section 11.10 describes which speech Kestrel accepts while a task runs.
```

with

```markdown
Each transition is defined in one place and covered by unit tests: the Orchestrator lives in `:app`, and its rules are one pure function from the state and an event to the new state and a list of effects, such as speaking or running a task. The microphone and recognition are not effects; they follow from the state, so they cannot drift from it. Section 11.10 describes which speech Kestrel accepts while a task runs.

An instant command runs as one job, which gets the next number, so that the end of an older command changes nothing. Kestrel cancels it on "stop" or "cancel" (also a transcript that starts with either, after a word such as "okay"), on a change of mode, when the screen turns off, and before its own requests, which then run; two exceptions are a "lock screen" command that turns the screen off and the call that a command placed, because the command caused them. A cancelled job must complete, for at most 3 s, before a new command, a task or the settings may act; after that, Kestrel goes on without it. A command may ask a question, such as "Which one?": the next transcript that is not Kestrel's own request is the answer, and the owner has 15 s from the end of the question to start answering, and 35 s once speaking. A call's countdown (section 13.6) holds while the owner speaks, so that a "cancel" whose transcript comes late still counts, and the keyword spotter's "stop" or "cancel" ends it at once; the transcript of that same word then names no button. During a countdown, "no" also cancels it, with or without the name and also when other words follow it, as in "No, not her"; Kestrel's other requests and the assistant gesture are ignored, and the holds give up after 30 s in total. A "no" whose transcript comes only after the countdown has run out is too late, because the spotter does not listen for "no".
```

and replace

```markdown
It never receives touches. Its text fades after a few seconds without activity.
```

with

```markdown
It never receives touches. Its text fades after a few seconds without activity, except an instant command's question or countdown, which stays until the command ends or the next text replaces it.
```

and replace

```markdown
- "Again" repeats the last instant command. "Cancel" cancels the current multi-step command, such as a drag or a grid action, or a running task.
```

with

```markdown
- "Again" (also "repeat that" and "do it again") repeats the last instant command that acted, on the current screen. A call, help, an edit of a password field, and a command that failed or was cancelled are not repeated.
- "Stop" or "cancel", with or without Kestrel's name, cancels the running instant command, such as a "Which one?" question, a drag or a call's countdown, or a running task. While nothing runs, "stop" and "cancel" alone are bare labels, which may tap a button with that name.
- A command that comes while another runs is dropped, and the bar says "Still busy with the last command."; bare speech is dropped without a word, because it may be conversation, except during dictation.
```

- [ ] **Step 11: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:app` 299 = 264 + 35, `:core:voice` 140 = 139 + 1, `:core:commands` 46, `:core:screen` 47, `:core:privacy` 48, `:core:safety` 2), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests compile.

- [ ] **Step 12: Run the script tests**

Run: `scripts\test-scripts.ps1`
Expected: "The script tests and checks passed.", with 132 Pester tests in each shell (unchanged) and "PSScriptAnalyzer found 0 problems."

- [ ] **Step 13: Run the voice device tests**

Every transcript that is not Kestrel's own request now starts a command, which `AppGraph`'s runner ignores, and the hook `voice-state` answers more fields.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 81 run, 0 failed, 0 errors, 0 skipped." (unchanged; `VoiceHookTest` changed one pattern), and no warning from the restores at the end.

- [ ] **Step 14: Run the other device tests of `:app`**

The feedback bar's overlay changed; `GridBarAndCardTest` and `OverlayTest` show texts on it.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.notPackage=dev.kestrel.app.voice' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' --no-configuration-cache`
Expected: `BUILD SUCCESSFUL`, and the result files in `app\build\outputs\androidTest-results\connected\debug\` show 116 tests (unchanged) and 0 failures.

- [ ] **Step 15: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 16: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt app/src/main/kotlin/dev/kestrel/app/orchestrator/Orchestrator.kt app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt app/src/main/kotlin/dev/kestrel/app/commands/CommandRunner.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt core/screen/src/main/kotlin/dev/kestrel/screen/overlay/FeedbackBar.kt core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt scripts/hook.ps1 docs/superpowers/specs/2026-11-12-kestrel-design.md app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt app/src/test/kotlin/dev/kestrel/app/orchestrator/OrchestratorTest.kt app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt core/voice/src/test/kotlin/dev/kestrel/voice/listen/ListeningPipelineTest.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceHookTest.kt
git commit -m "feat(app): run instant commands as numbered jobs with questions, a countdown that the owner's speech holds, again and cancel" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: The executor's frame, targets and "Which one?", the commands fixture, the test helpers and command-test.ps1

**Files:**
- Create: `core/screen/src/main/kotlin/dev/kestrel/screen/act/TextEditor.kt`, `core/screen/src/main/kotlin/dev/kestrel/screen/act/SystemControls.kt` (stubs)
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt` (imports, `text`, `system`, `whichOneShown`, `endWhichOne`, and the places that show or hide the "Which one?" numbers)
- Modify: `app/src/main/kotlin/dev/kestrel/app/commands/CommandRunner.kt` (`CommandPart`, `CommandEnvironment`)
- Create: `app/src/main/kotlin/dev/kestrel/app/commands/InstantCommands.kt`, `ScreenCommands.kt`, `ScreenTargets.kt`, `CommandTexts.kt`, `CommandOptions.kt`, `TextCommands.kt` (stub), `PhoneCommands.kt` (stub)
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (imports, `commandOptions`, `commands`, the `commands` of `Listening`)
- Create: `testapp/src/main/kotlin/dev/kestrel/testapp/CommandsActivity.kt`; Modify: `testapp/src/main/res/values/ids.xml`, `testapp/src/main/AndroidManifest.xml`
- Create: `app/src/androidTest/kotlin/dev/kestrel/app/commands/CommandTests.kt`, `TextSessions.kt`, `TouchCommandsTest.kt`
- Create: `scripts/command-test.ps1`, `scripts/tests/CommandTest.Tests.ps1`; Modify: `scripts/lib/Kestrel.ps1` (new helpers), `AGENTS.md`
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (sections 9.4, 9.6, 9.8, 14.1 and 16.3, and Appendix A's "Cancel on touch" row)
- Test: `app/src/test/kotlin/dev/kestrel/app/commands/ScreenTargetsTest.kt`

**Interfaces:**
- Consumes: Task 3's `CommandRunner`, `CommandConversation`, `CommandReport`, `Listening.sessions`, `Listening.commandReports`, `State.asking`; Task 2's `Command`, `Target`, `CommandResult`, `Outcome`, `SpokenNumbers.answer(text)`, `PhoneCommand.Volume`, `Mute`, `Silence`, `Media`; Task 1's `LabelMatcher.decide`, `MatchCandidate`, `MatchDecision`, `Strictness`, `MatchRules.MAX_AMBIGUOUS`, `Normalizer.normalize`; `ScreenSession.snapshots`, `numberBadges`, `grid`, `showWhichOne(keys)`, `showNumbers(mode)`, `startTouchWatcher()`, `stopTouchWatcher()`, `ownerTouches`, `actions.tap(key)`, `longPress(key)`, `tapAt(x, y)`, `longPressAt(x, y)`; `Numbering.forScreen`, `Badge`, `Grid`; the test helpers `ScreenServiceRule`, `TestFinger` and `VoiceTests.grantListeningPermissions()`, `VoiceTests.waitUntil`, `VoiceTests.listeningServiceRuns()`; the script helpers `Assert-TestDevice`, `Invoke-SettingsCommand`, `Get-SecureSetting`, `Set-SecureSetting`, `Get-TestResultCounts`, `Get-LargestRowId`.
- Produces (`:core:screen`, package `dev.kestrel.screen.act`): `enum class ClipboardKind { CUT, COPY, PASTE }`; `data class FieldState(val text: String, val selectionStart: Int, val selectionEnd: Int, val password: Boolean, val multiLine: Boolean, val focused: Boolean, val packageName: String?)`; `class TextEditor(service: AccessibilityService, snapshots: SnapshotCache<LiveSnapshot>, tracker: ScreenChangeTracker)` with the suspending `read(key): FieldState?`, `replace(key, start, end, replacement, selectionStart, selectionEnd): ActionResult`, `newLine(key)`, `select(key, start, end)`, `submit(key)`, `clipboard(key, kind: ClipboardKind)`, `typeAtCursor(key, text)`, `deleteBeforeCursor(key, count: Int)`, each returning `ActionResult`; `class SystemControls(service, snapshots, tracker)` with the suspending `start(intent: Intent)`, `openActivity(component: ComponentName, user: UserHandle)`, `previousApp()`, `systemAction(id: Int)`, `pan(dx: Int, dy: Int)`, each returning `ActionResult`, and `fun systemActions(): Set<Int>` (stubs; plans 3B and 3C replace both files whole). `ScreenSession.text: TextEditor`, `ScreenSession.system: SystemControls`, `suspend fun ScreenSession.endWhichOne()`, `val ScreenSession.whichOneShown: StateFlow<Boolean>`. Package `dev.kestrel.app.commands`: `internal interface CommandPart { suspend fun run(command: Command, heard: String, conversation: CommandConversation): CommandResult?; fun privateInput(): Boolean = false }`; `internal class CommandEnvironment(val context: Context, val screen: () -> ScreenSession?, val options: StateFlow<CommandOptions>, val targets: ScreenTargets, val scope: CoroutineScope, val openSettings: (page: SettingsPage?, permission: String?) -> Unit, val report: (String, Throwable) -> Unit)`; `@Serializable data class CommandOptions(val requireVerbs: Boolean = false, val numbers: NumbersMode = NumbersMode.ALL)`; `internal class InstantCommands(environment: CommandEnvironment) : CommandRunner`; `internal fun screenCommands(environment): CommandPart` and `internal class ScreenCommands` with `act(screen, target, kind, conversation, onCell, onElement)` and `choose(screen, choices, conversation): Choice`; `internal sealed interface Choice { Chosen(element: ChosenElement); Ends(result: CommandResult) }`; `internal fun textCommands(environment): CommandPart` and `internal fun phoneCommands(environment): CommandPart` (stubs); `internal enum class TargetKind { TAP, BARE, CHECK, EXPAND, COLLAPSE, DISMISS, SCROLL, EDIT }`; `internal data class ChosenElement(val key: String, val className: String?, val label: String?, val bounds: IntRect)`; `internal data class ScreenView(val snapshot: ScreenSnapshot, val badges: List<Badge>, val grid: Grid?)`; `internal sealed interface Resolution { One(element); Cell(number, bounds); Several(elements); NotFound; NeedNumbers; NoSuchNumber(number); Changed; NoScreen }`; `internal class ScreenTargets(screen: () -> ScreenSession?)` with `view()`, `resolve(target, kind, strictness, cells)`, `still(chosen)` and, in its companion, `candidates(snapshot, kind)`, `names(snapshot, element)`, `decide(view, target, kind, strictness, cells)`, `chosen(element)`, `same(chosen, element)`; `internal object CommandTexts`; `AppGraph.commands: CommandRunner`. `:testapp`: `CommandsActivity` and the IDs `commands_dialog`, `seek_bar`, `progress_bar`, `pager`, `details_row`, `drag_source`, `drop_target`, `dialog_status`. androidTest, package `dev.kestrel.app.commands`: `internal object CommandTests` (`ARGUMENT`, `only` (every command test class's rule of order 0, before `ScreenServiceRule` of order 1), `graph`, `assumeCommands()`, `start()`, `stop()`, `send(text)`, `say(text, timeoutMillis)`, `ask(text, timeoutMillis)`, `answer(text, timeoutMillis)`, `next(timeoutMillis)`, `awaitSaid(text, timeoutMillis)`, `asked`, `spoken`), `internal class TextSessions : VoiceSessions`. Scripts: `scripts\command-test.ps1 -Serial <serial> [-Class <name>[,<name>...]]`. Tasks 5 to 9 add their commands to `ScreenCommands.run` above the line `// Tasks 5 to 9 add their commands above this line.`, use `act` and `choose`, and run their device tests with `command-test.ps1`; plans 3B and 3C replace `TextCommands.kt`, `PhoneCommands.kt`, `TextEditor.kt` and `SystemControls.kt` whole.

Design D6, D14 (the options' model) and D15. `InstantCommands` asks its three parts in turn (the text part, the phone part, the screen part); a part returns a result for a command that it takes, or null. When none takes a command, the executor runs the transcript once more as a bare label through the screen part, and a result that acted then names `Command.Bare` as the command that acted, so that "delete" and then "again" taps the same button twice. Until plans 3B and 3C, every 3A command has a part and "delete" is already `Command.Bare`, so no 3A test reaches this fallback; plan 3B's first text device test is the first that does. Plan 3A writes the screen part and creates the text and phone parts as stubs that take nothing; plans 3B and 3C replace their stub file whole and build their own Android services inside their factory, so that neither changes `AppGraph` or `InstantCommands`. Without Kestrel's accessibility service, only the volume, mute, silence and media commands reach the parts (plan 3C); any other command makes Kestrel say "My accessibility service is off, so I can't use the screen.", and bare speech is ignored. While a part's question or countdown waits, the touch watcher runs, and the owner's touch ends the question without an answer or the countdown without a call, so the command ends as cancelled (spec 14.1 now covers these two cases too).

`ScreenTargets` turns the current snapshot into the candidates for each kind of command: the elements that the owner can act on, and, for tap and long press, also named elements that have no actionable ancestor and no actionable element inside them (they get a tap at their centre); never the keyboard's window, and never an element that a window above covers whole, such as the screen behind a full-screen dialog, because a finger could not reach it. An element without a name takes the names of its nearest labelled ancestor; a text field's typed text is never a name. A label is decided by `LabelMatcher`; a number names a badge of "show numbers" or of "Which one?", or a grid cell while the grid shows (the grid wins), or, when neither shows, an actionable element whose whole name is that number, such as a key of the dialer; otherwise Kestrel says "Say 'show numbers' first.". `ScreenCommands.act` keeps the chosen element's key, class, label and bounds (`ChosenElement`) and checks them in the current snapshot right before the action, because a key names a position (spec 9.2): a label is decided once more on the new screen, while a number or an answer ends with "The screen changed. Say it again.". Several close candidates show "Which one?" numbers on just those elements and ask "Which one?"; the answer is a number ("2", "number 2", "tap 2", "to"), an answer that is no number ends the command and is read as a new utterance, and the question ends at once with "The screen changed. Say it again." when the numbers go because the screen changed. The command removes its "Which one?" numbers when it ends (`ScreenSession.endWhichOne()`, which shows the numbers of "show numbers" again when that is on). A label that nothing matches makes Kestrel say "I can't find X." and shows "Which one?" numbers on all actionable elements, which stay until the screen changes, so that "tap 5" works next. A bare label taps only a clear `STRICT` match among the actionable elements, and a bare number taps like "tap N" while numbers or the grid show, and otherwise only an actionable element whose whole name is that number; everything else is ignored without a word, because bare speech may be conversation.

The command device tests drive each command through the same path as speech: `CommandTests` sends `Event.Heard(text, named = false)` to `graph.listening`, as a transcript does, and waits for the command's report. So that they need no speech models, `CommandTests` replaces `Listening.sessions` with `TextSessions`, whose session never records, finishes each reply at once and remembers what Kestrel said. Every command device test skips itself unless the instrumentation argument `kestrelCommands=true` is present, which only `scripts\command-test.ps1` passes: `CommandTests.only` is the first rule of every command test class (`@get:Rule(order = 0)`), before `ScreenServiceRule`, so a skipped test changes nothing on the device; so `voice-test.ps1`, the other runs of `connected-test.ps1` and the README's run of all of `:app:connectedDebugAndroidTest` skip them and change nothing, and `command-test.ps1` fails a run in which a command test was skipped. `command-test.ps1` refuses every device that is not an emulator, runs the package `dev.kestrel.app.commands` through `connected-test.ps1`, and saves before and puts back afterwards, also after a failure or Ctrl+C, everything that the command tests of plans 3A, 3B and 3C change: Wi-Fi and Bluetooth, the volume of every stream and the ringer mode, the magnification, Quick Settings' tiles, the lock-screen setting, users that the tests made (a managed profile named "KestrelTest"), contacts that the tests made (account name "kestrel-test"), call-log rows newer than the largest ID that it saw first, screenshots newer than its start, the test app's notifications, and the runtime permissions that the tests grant (`READ_CONTACTS` and `READ_PHONE_STATE` for Kestrel, `POST_NOTIFICATIONS` for the test app), which it revokes when they were not granted before; Kestrel's microphone and notification permissions, which the tests also grant, stay granted, as after `voice-test.ps1`, because revoking a permission ends Kestrel's process. It reads all of that state before the run, and puts back nothing when a read failed, so that a failed read can never revoke a permission that was granted before. Its pure helpers live in `scripts/lib/Kestrel.ps1` with Pester tests.

- [ ] **Step 1: Write the failing JVM tests**

Create `app/src/test/kotlin/dev/kestrel/app/commands/ScreenTargetsTest.kt`:

```kotlin
package dev.kestrel.app.commands

import dev.kestrel.commands.Target
import dev.kestrel.commands.text.Strictness
import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import dev.kestrel.screen.overlay.Badge
import dev.kestrel.screen.overlay.Grid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTargetsTest {
    private val app = WindowSummary(1, WindowKind.APPLICATION, bounds = IntRect(0, 0, 1080, 2400), layer = 0)
    private val keyboard = WindowSummary(2, WindowKind.INPUT_METHOD, bounds = IntRect(0, 1600, 1080, 2400), layer = 2)

    private fun element(
        key: String,
        label: String?,
        bounds: IntRect,
        clickable: Boolean = true,
        parent: Int? = null,
        window: Int = 1,
        className: String = "android.widget.Button",
    ) = ScreenElement(key = key, windowId = window, parent = parent, className = className, label = label, bounds = bounds, clickable = clickable)

    private val elements = listOf(
        element("send", "Send", IntRect(100, 100, 300, 200)),
        element("later", "Send later", IntRect(400, 100, 700, 200)),
        element("row", null, IntRect(0, 300, 1080, 400), clickable = false, className = "android.widget.LinearLayout"),
        element("ana", "Message from Ana", IntRect(0, 300, 600, 400), clickable = false, parent = 2, className = "android.widget.TextView"),
        element("delete1", "Delete", IntRect(800, 300, 1000, 400), parent = 2),
        element("delete2", "Delete", IntRect(800, 500, 1000, 600)),
        element("compose", "Compose", IntRect(0, 700, 1080, 800), clickable = false, className = "android.widget.FrameLayout"),
        element("icon", null, IntRect(100, 700, 200, 800), parent = 6, className = "android.widget.ImageButton"),
        element("q", "q", IntRect(0, 1700, 100, 1800), window = 2),
        element("subscribe", "Subscribe", IntRect(100, 900, 500, 1000)).copy(checkable = true),
        element("message", "Message", IntRect(100, 1100, 900, 1200), className = "android.widget.EditText")
            .copy(editable = true, hint = "Message", text = "secret typed", focused = true),
        element("hidden", "Hidden", IntRect(0, 1700, 200, 1800)),
        element("seven", "7", IntRect(100, 1300, 200, 1400)),
        element("details", "Details", IntRect(100, 1450, 500, 1550), clickable = false).copy(actions = setOf(ElementAction.EXPAND)),
    )
    private val snapshot = ScreenSnapshot(0, listOf(keyboard, app), elements)
    private val plain = ScreenView(snapshot, badges = emptyList(), grid = null)

    private fun keys(kind: TargetKind) = ScreenTargets.candidates(snapshot, kind).map { it.key }

    private fun decide(view: ScreenView, target: Target?, kind: TargetKind = TargetKind.TAP, cells: Boolean = false) =
        ScreenTargets.decide(view, target, kind, Strictness.NORMAL, cells)

    private fun one(key: String) = Resolution.One(ScreenTargets.chosen(snapshot.element(key)!!))

    @Test
    fun candidatesLeaveOutTheKeyboardAndWhatAWindowAboveCoversWhole() {
        val tap = keys(TargetKind.TAP)

        assertFalse(tap.contains("q"))
        assertFalse(tap.contains("hidden"))
        // A named element without anything actionable around or inside it gets a tap at its centre.
        assertTrue(tap.containsAll(listOf("send", "ana", "delete1", "icon", "message", "details")))
        // An element with an actionable element inside it is no candidate, and neither is one without a name.
        assertFalse(tap.contains("compose"))
        assertFalse(tap.contains("row"))
        assertEquals(listOf("send", "later", "delete1", "delete2", "icon", "subscribe", "message", "seven"), keys(TargetKind.BARE))
        assertEquals(listOf("subscribe"), keys(TargetKind.CHECK))
        assertEquals(listOf("message"), keys(TargetKind.EDIT))
        assertEquals(listOf("details"), keys(TargetKind.EXPAND))
    }

    @Test
    fun anElementWithoutANameTakesItsNearestLabelledAncestorsAndTypedTextIsNoName() {
        assertEquals(listOf("Compose"), ScreenTargets.names(snapshot, snapshot.element("icon")!!))
        assertEquals(listOf("Message"), ScreenTargets.names(snapshot, snapshot.element("message")!!))
    }

    @Test
    fun aLabelIsFoundAndTwoEqualOnesAsk() {
        assertEquals(one("send"), decide(plain, Target.Label("send")))
        assertEquals(one("later"), decide(plain, Target.Label("Send later")))
        assertEquals(one("icon"), decide(plain, Target.Label("compose")))
        assertEquals(
            Resolution.Several(listOf(ScreenTargets.chosen(elements[4]), ScreenTargets.chosen(elements[5]))),
            decide(plain, Target.Label("Delete")),
        )
        assertEquals(Resolution.NotFound, decide(plain, Target.Label("weather")))
    }

    @Test
    fun bareLabelsLookOnlyAtActionableElements() {
        assertEquals(one("details"), decide(plain, Target.Label("details")))
        assertEquals(Resolution.NotFound, decide(plain, Target.Label("details"), TargetKind.BARE))
    }

    @Test
    fun aNumberNamesABadgeAGridCellOrAnElementNamedWithIt() {
        val badges = listOf(Badge(1, "send", IntRect(100, 100, 300, 200)), Badge(2, "delete1", IntRect(800, 300, 1000, 400)))
        val numbered = plain.copy(badges = badges)
        assertEquals(one("delete1"), decide(numbered, Target.Number(2)))
        assertEquals(Resolution.NoSuchNumber(5), decide(numbered, Target.Number(5)))
        // A badge that a new snapshot has not caught up with.
        assertEquals(Resolution.Changed, decide(plain.copy(badges = listOf(Badge(1, "gone", IntRect(0, 0, 10, 10)))), Target.Number(1)))

        val grid = Grid(IntRect(0, 0, 1080, 2400), columns = 4)
        val gridded = numbered.copy(grid = grid)
        assertEquals(Resolution.Cell(3, grid.cell(3)), decide(gridded, Target.Number(3), cells = true))
        assertEquals(one("send"), decide(gridded, Target.Number(1), cells = false))

        // Without numbers or the grid, only an actionable element whose whole name is the number.
        assertEquals(one("seven"), decide(plain, Target.Number(7)))
        assertEquals(Resolution.NeedNumbers, decide(plain, Target.Number(8)))
        val seven = element("seven2", "7", IntRect(300, 1300, 400, 1400))
        val eight = element("eight", "8", IntRect(500, 1300, 600, 1400), clickable = false, className = "android.widget.TextView")
        val keys = plain.copy(snapshot = ScreenSnapshot(0, listOf(keyboard, app), elements + seven + eight))
        assertEquals(Resolution.Several(listOf(ScreenTargets.chosen(elements[12]), ScreenTargets.chosen(seven))), decide(keys, Target.Number(7)))
        assertEquals(Resolution.NeedNumbers, decide(keys, Target.Number(8)))
    }

    @Test
    fun withoutATargetTheOnlyCandidateOrTheFocusedOne() {
        assertEquals(one("subscribe"), decide(plain, null, TargetKind.CHECK))
        assertEquals(one("message"), decide(plain, null, TargetKind.EDIT))
        assertEquals(Resolution.NotFound, decide(plain, null, TargetKind.DISMISS))
        // Several candidates: the focused one, and without one, all of them for "Which one?".
        val news = element("news", "News", IntRect(600, 900, 1000, 1000)).copy(checkable = true)
        val two = plain.copy(snapshot = ScreenSnapshot(0, listOf(keyboard, app), elements + news))
        assertEquals(Resolution.Several(listOf(ScreenTargets.chosen(elements[9]), ScreenTargets.chosen(news))), decide(two, null, TargetKind.CHECK))
        val focusedNews = news.copy(focused = true)
        val focused = plain.copy(snapshot = ScreenSnapshot(0, listOf(keyboard, app), elements + focusedNews))
        assertEquals(Resolution.One(ScreenTargets.chosen(focusedNews)), decide(focused, null, TargetKind.CHECK))
    }

    @Test
    fun theChosenElementIsStillTheSameOnlyWithItsClassLabelAndMostOfItsBounds() {
        val send = snapshot.element("send")!!
        val chosen = ScreenTargets.chosen(send)

        assertTrue(ScreenTargets.same(chosen, send))
        assertTrue(ScreenTargets.same(chosen, send.copy(bounds = IntRect(120, 110, 320, 210))))
        assertFalse(ScreenTargets.same(chosen, send.copy(bounds = IntRect(100, 160, 300, 260))))
        assertFalse(ScreenTargets.same(chosen, send.copy(label = "Sent")))
        assertFalse(ScreenTargets.same(chosen, send.copy(className = "android.widget.TextView")))
        assertFalse(ScreenTargets.same(chosen, null))
    }

    @Test
    fun aChosenElementPrintsNoLabel() {
        assertFalse(ScreenTargets.chosen(elements[0]).toString().contains("Send"))
    }
}
```

- [ ] **Step 2: Run the test to verify that it fails**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.commands.ScreenTargetsTest`
Expected: `BUILD FAILED`, because the test source does not compile: "Unresolved reference 'ScreenTargets'", "Unresolved reference 'TargetKind'", "Unresolved reference 'ScreenView'" and "Unresolved reference 'Resolution'".

- [ ] **Step 3: Add the stubs of the text editor and the system controls, and the "Which one?" state of the screen session**

Create `core/screen/src/main/kotlin/dev/kestrel/screen/act/TextEditor.kt`:

```kotlin
package dev.kestrel.screen.act

import android.accessibilityservice.AccessibilityService
import dev.kestrel.screen.read.LiveSnapshot
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.SnapshotCache

/** Which clipboard action [TextEditor.clipboard] performs. */
enum class ClipboardKind { CUT, COPY, PASTE }

/**
 * A text field as the editing commands see it (design spec, section 9.7): its [text] (empty while it shows its hint), the
 * selection, whether it is a password field, has more than one line and has the focus, and its app.
 */
data class FieldState(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val password: Boolean,
    val multiLine: Boolean,
    val focused: Boolean,
    val packageName: String?,
) {
    override fun toString() =
        "FieldState(${text.length} characters, selection $selectionStart to $selectionEnd, password=$password, " +
            "multiLine=$multiLine, focused=$focused)"
}

/**
 * Edits text fields through Kestrel's accessibility input method, with set-text as the fallback (design spec, section
 * 9.7). Plan 3B replaces this stub, which edits nothing, and keeps its constructor and signatures.
 */
@Suppress("unused")
class TextEditor(
    private val service: AccessibilityService,
    private val snapshots: SnapshotCache<LiveSnapshot>,
    private val tracker: ScreenChangeTracker,
) {
    suspend fun read(key: String): FieldState? = null

    suspend fun replace(key: String, start: Int, end: Int, replacement: String, selectionStart: Int, selectionEnd: Int): ActionResult =
        NOT_AVAILABLE

    suspend fun newLine(key: String): ActionResult = NOT_AVAILABLE

    suspend fun select(key: String, start: Int, end: Int): ActionResult = NOT_AVAILABLE

    suspend fun submit(key: String): ActionResult = NOT_AVAILABLE

    suspend fun clipboard(key: String, kind: ClipboardKind): ActionResult = NOT_AVAILABLE

    suspend fun typeAtCursor(key: String, text: String): ActionResult = NOT_AVAILABLE

    suspend fun deleteBeforeCursor(key: String, count: Int): ActionResult = NOT_AVAILABLE

    private companion object {
        val NOT_AVAILABLE = ActionResult.Failed("Not available yet")
    }
}
```

Create `core/screen/src/main/kotlin/dev/kestrel/screen/act/SystemControls.kt`:

```kotlin
package dev.kestrel.screen.act

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.os.UserHandle
import dev.kestrel.screen.read.LiveSnapshot
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.SnapshotCache

/**
 * Starts activities and system actions, switches to the previous app and pans the magnification (design spec, section
 * 9.3). Plan 3C replaces this stub, which does nothing, and keeps its constructor and signatures.
 */
@Suppress("unused")
class SystemControls(
    private val service: AccessibilityService,
    private val snapshots: SnapshotCache<LiveSnapshot>,
    private val tracker: ScreenChangeTracker,
) {
    suspend fun start(intent: Intent): ActionResult = NOT_AVAILABLE

    suspend fun openActivity(component: ComponentName, user: UserHandle): ActionResult = NOT_AVAILABLE

    suspend fun previousApp(): ActionResult = NOT_AVAILABLE

    suspend fun systemAction(id: Int): ActionResult = NOT_AVAILABLE

    suspend fun pan(dx: Int, dy: Int): ActionResult = NOT_AVAILABLE

    fun systemActions(): Set<Int> = emptySet()

    private companion object {
        val NOT_AVAILABLE = ActionResult.Failed("Not available yet")
    }
}
```

In `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt`, replace

```kotlin
import dev.kestrel.screen.act.GestureRunner
import dev.kestrel.screen.act.ScreenActions
```

with

```kotlin
import dev.kestrel.screen.act.GestureRunner
import dev.kestrel.screen.act.ScreenActions
import dev.kestrel.screen.act.SystemControls
import dev.kestrel.screen.act.TextEditor
```

then replace

```kotlin
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
```

with

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
```

then replace

```kotlin
    val actions = ScreenActions(service, snapshots, gestures, tracker)
```

with

```kotlin
    val actions = ScreenActions(service, snapshots, gestures, tracker)

    /** Edits text fields through Kestrel's input method (design spec, section 9.7). */
    val text = TextEditor(service, snapshots, tracker)

    /** Starts activities and system actions, switches apps and pans the magnification (design spec, section 9.3). */
    val system = SystemControls(service, snapshots, tracker)

    private val whichOne = MutableStateFlow(false)

    /** Whether "Which one?" numbers show. They go when the screen content changes. */
    val whichOneShown: StateFlow<Boolean> = whichOne.asStateFlow()
```

then replace

```kotlin
    suspend fun showNumbers(mode: NumbersMode = NumbersMode.ALL) = withContext(Dispatchers.Main) {
        whichOneShownOn = null
        numbersMode = mode
```

with

```kotlin
    suspend fun showNumbers(mode: NumbersMode = NumbersMode.ALL) = withContext(Dispatchers.Main) {
        whichOneShownOn = null
        whichOne.value = false
        numbersMode = mode
```

then replace

```kotlin
        whichOneShownOn = contentOf(snapshot)
        numbers.show(Numbering.forCandidates(snapshot.elements.filter { it.key in keys }))
    }

    suspend fun hideNumbers() = withContext(Dispatchers.Main) {
        numbersMode = null
        whichOneShownOn = null
        numbers.hide()
    }
```

with

```kotlin
        whichOneShownOn = contentOf(snapshot)
        numbers.show(Numbering.forCandidates(snapshot.elements.filter { it.key in keys }))
        whichOne.value = true
    }

    /**
     * Removes the "Which one?" numbers of a command that has ended: the numbers of "show numbers" come back when it is
     * on, and otherwise the numbers go. Nothing changes when no "Which one?" numbers show.
     */
    suspend fun endWhichOne() = withContext(Dispatchers.Main) {
        if (whichOneShownOn == null) return@withContext
        whichOneShownOn = null
        whichOne.value = false
        val mode = numbersMode
        if (mode == null) numbers.hide() else numbers.show(Numbering.forScreen(snapshots.current().snapshot, mode))
    }

    suspend fun hideNumbers() = withContext(Dispatchers.Main) {
        numbersMode = null
        whichOneShownOn = null
        whichOne.value = false
        numbers.hide()
    }
```

and replace

```kotlin
            // The candidates belong to the screen on which "Which one?" was asked.
            whichOneShownOn = null
            if (numbersMode == null) numbers.hide()
```

with

```kotlin
            // The candidates belong to the screen on which "Which one?" was asked.
            whichOneShownOn = null
            whichOne.value = false
            if (numbersMode == null) numbers.hide()
```

- [ ] **Step 4: Add the executor's frame, the targets and the screen part**

In `app/src/main/kotlin/dev/kestrel/app/commands/CommandRunner.kt`, replace

```kotlin
package dev.kestrel.app.commands

import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
```

with

```kotlin
package dev.kestrel.app.commands

import android.content.Context
import dev.kestrel.app.settings.SettingsPage
import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import dev.kestrel.screen.ScreenSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
```

and replace

```kotlin
/** What happened to instant command number [command] of the kind [kind] (its class's simple name), never its words. */
internal data class CommandReport(val command: Int, val kind: String, val outcome: Outcome)
```

with

```kotlin
/** What happened to instant command number [command] of the kind [kind] (its class's simple name), never its words. */
internal data class CommandReport(val command: Int, val kind: String, val outcome: Outcome)

/**
 * One part of the executor: the text commands (plan 3B), the phone commands (plan 3C) or the screen commands. [run]
 * returns null for a command that the part does not take.
 */
internal interface CommandPart {
    suspend fun run(command: Command, heard: String, conversation: CommandConversation): CommandResult?

    /** Whether a password field has the focus, as [CommandRunner.privateInput] asks; it makes no binder call. */
    fun privateInput(): Boolean = false
}

/**
 * What every part of the executor needs (design spec, section 9.5). [screen] is null while Kestrel's accessibility
 * service is off. [options] are the owner's options for the instant commands, read at each command. [openSettings] opens
 * Kestrel's settings app, on [SettingsPage] when one is given, and asks for the permission when one is given. [report]
 * logs a failure by its classes only.
 */
internal class CommandEnvironment(
    val context: Context,
    val screen: () -> ScreenSession?,
    val options: StateFlow<CommandOptions>,
    val targets: ScreenTargets,
    val scope: CoroutineScope,
    val openSettings: (page: SettingsPage?, permission: String?) -> Unit,
    val report: (String, Throwable) -> Unit,
)
```

Create `app/src/main/kotlin/dev/kestrel/app/commands/CommandOptions.kt`:

```kotlin
package dev.kestrel.app.commands

import dev.kestrel.screen.overlay.NumbersMode
import kotlinx.serialization.Serializable

/**
 * The owner's options for the instant commands (design spec, sections 9.5 and 9.8): [requireVerbs] stops bare labels
 * from tapping, and [numbers] chooses between numbers on all elements and on the unlabeled ones only.
 */
@Serializable
data class CommandOptions(val requireVerbs: Boolean = false, val numbers: NumbersMode = NumbersMode.ALL)
```

Create `app/src/main/kotlin/dev/kestrel/app/commands/CommandTexts.kt`:

```kotlin
package dev.kestrel.app.commands

/** What the screen commands say and show (design spec, sections 9.4 to 9.6). Kestrel speaks only what the owner must act on. */
internal object CommandTexts {
    const val SERVICE_OFF = "My accessibility service is off, so I can't use the screen."
    const val WHICH_ONE = "Which one?"
    const val SCREEN_CHANGED = "The screen changed. Say it again."
    const val SHOW_NUMBERS_FIRST = "Say 'show numbers' first."
    const val NOTHING_HERE = "I can't find anything for that here."

    /** [spoken] is the owner's words as the recogniser wrote them. Kestrel says this text and never logs it. */
    fun cantFind(spoken: String) = "I can't find $spoken."

    fun noNumber(number: Int) = "There's no number $number on the screen."
}
```

Create `app/src/main/kotlin/dev/kestrel/app/commands/ScreenTargets.kt`:

```kotlin
package dev.kestrel.app.commands

import dev.kestrel.commands.Target
import dev.kestrel.commands.text.LabelMatcher
import dev.kestrel.commands.text.MatchCandidate
import dev.kestrel.commands.text.MatchDecision
import dev.kestrel.commands.text.MatchRules
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.commands.text.Strictness
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.overlay.Badge
import dev.kestrel.screen.overlay.Grid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a command acts on, which decides its candidates (design spec, sections 9.5 and 9.6). */
internal enum class TargetKind {
    /** Tap and long press: the actionable elements, and named elements with nothing actionable around or inside them. */
    TAP,

    /** A bare label or number: the actionable elements only. */
    BARE,
    CHECK,
    EXPAND,
    COLLAPSE,
    DISMISS,
    SCROLL,
    EDIT,
}

/** The element that Kestrel chose, as it was then. [ScreenTargets.still] checks it again right before the action. */
internal data class ChosenElement(val key: String, val className: String?, val label: String?, val bounds: IntRect) {
    override fun toString() = "ChosenElement(key of ${key.length} characters, label of ${label?.length} characters)"
}

/** What a command sees: the snapshot, the numbers on the screen and the grid. */
internal data class ScreenView(val snapshot: ScreenSnapshot, val badges: List<Badge>, val grid: Grid?) {
    override fun toString() = "ScreenView(${snapshot.elements.size} elements, ${badges.size} numbers, grid=${grid != null})"
}

/** How a target was found. */
internal sealed interface Resolution {
    data class One(val element: ChosenElement) : Resolution

    /** A cell of the grid. */
    data class Cell(val number: Int, val bounds: IntRect) : Resolution

    /** Several close candidates, for "Which one?". */
    data class Several(val elements: List<ChosenElement>) : Resolution

    data object NotFound : Resolution

    /** A number while neither numbers nor the grid show, and no element is named with it. */
    data object NeedNumbers : Resolution

    data class NoSuchNumber(val number: Int) : Resolution

    /** A badge names an element that is no longer on the screen. */
    data object Changed : Resolution

    /** Kestrel's accessibility service is off. */
    data object NoScreen : Resolution
}

/**
 * Finds the elements that commands act on (design spec, sections 9.2, 9.4 and 9.6). The functions of the companion are
 * pure, so that JVM tests check the decisions; the others read the current snapshot.
 */
internal class ScreenTargets(private val screen: () -> ScreenSession?) {
    /** The current snapshot with the numbers and the grid on the screen, or null without the accessibility service. */
    suspend fun view(): ScreenView? {
        val session = screen() ?: return null
        val snapshot = session.snapshots.current().snapshot
        // The overlays change only on the main thread.
        return withContext(Dispatchers.Main) { ScreenView(snapshot, session.numberBadges, session.grid) }
    }

    /** Finds [target] among the candidates of [kind]. With [cells], a number names a grid cell while the grid shows. */
    suspend fun resolve(target: Target?, kind: TargetKind, strictness: Strictness = Strictness.NORMAL, cells: Boolean = false): Resolution {
        val view = view() ?: return Resolution.NoScreen
        return decide(view, target, kind, strictness, cells)
    }

    /** The element that [chosen] names in the current snapshot, when it is still the element that Kestrel chose. */
    suspend fun still(chosen: ChosenElement): ScreenElement? {
        val session = screen() ?: return null
        return session.snapshots.current().snapshot.element(chosen.key).takeIf { same(chosen, it) }
    }

    companion object {
        private const val MAX_DEPTH = 20

        /**
         * The candidates of [kind] in [snapshot], in the snapshot's order. The keyboard's window never gives candidates,
         * and an element that a window above covers whole is no candidate, because a finger could not reach it.
         */
        fun candidates(snapshot: ScreenSnapshot, kind: TargetKind): List<ScreenElement> {
            val keyboard = snapshot.windows.filter { it.kind == WindowKind.INPUT_METHOD }.map { it.id }.toSet()
            val shown = snapshot.elements.filter { it.windowId !in keyboard && !covered(snapshot, it) }
            return when (kind) {
                TargetKind.TAP -> {
                    val near = nearActionable(snapshot)
                    shown.filter { it.isActionable || (it.enabled && ownNames(it).isNotEmpty() && it.key !in near) }
                }
                TargetKind.BARE -> shown.filter { it.isActionable }
                TargetKind.CHECK -> shown.filter { it.enabled && it.checkable }
                TargetKind.EXPAND -> shown.filter { it.enabled && ElementAction.EXPAND in it.actions }
                TargetKind.COLLAPSE -> shown.filter { it.enabled && ElementAction.COLLAPSE in it.actions }
                TargetKind.DISMISS -> shown.filter { it.enabled && ElementAction.DISMISS in it.actions }
                TargetKind.SCROLL -> shown.filter { it.enabled && it.scrollable }
                TargetKind.EDIT -> shown.filter { it.enabled && it.editable }
            }
        }

        /** The names of [element], or of its nearest ancestor that has names. A field's typed text is never a name. */
        fun names(snapshot: ScreenSnapshot, element: ScreenElement): List<String> {
            var current: ScreenElement? = element
            var depth = 0
            while (current != null && depth <= MAX_DEPTH) {
                val names = ownNames(current)
                if (names.isNotEmpty()) return names
                current = current.parent?.let { snapshot.elements.getOrNull(it) }
                depth++
            }
            return emptyList()
        }

        fun decide(view: ScreenView, target: Target?, kind: TargetKind, strictness: Strictness, cells: Boolean): Resolution {
            val candidates = candidates(view.snapshot, kind)
            return when (target) {
                null -> only(candidates)
                is Target.Number -> number(view, target.number, candidates, cells)
                is Target.Label -> label(view.snapshot, target.spoken, candidates, strictness)
            }
        }

        fun chosen(element: ScreenElement) = ChosenElement(element.key, element.className, element.label, element.bounds)

        /** Whether [element] is still [chosen]: the same class and label, and bounds that cover at least half the chosen ones. */
        fun same(chosen: ChosenElement, element: ScreenElement?): Boolean {
            if (element == null || element.className != chosen.className || element.label != chosen.label) return false
            val overlap = chosen.bounds.intersect(element.bounds) ?: return chosen.bounds.isEmpty && chosen.bounds == element.bounds
            return 2L * overlap.width * overlap.height >= chosen.bounds.width.toLong() * chosen.bounds.height
        }

        // Without a target: the only candidate, else the focused one.
        private fun only(candidates: List<ScreenElement>): Resolution {
            candidates.singleOrNull()?.let { return Resolution.One(chosen(it)) }
            candidates.singleOrNull { it.focused }?.let { return Resolution.One(chosen(it)) }
            if (candidates.isEmpty()) return Resolution.NotFound
            return Resolution.Several(candidates.take(MatchRules.MAX_AMBIGUOUS).map(::chosen))
        }

        private fun number(view: ScreenView, number: Int, candidates: List<ScreenElement>, cells: Boolean): Resolution {
            val grid = view.grid
            if (cells && grid != null) {
                return if (number in 1..grid.cellCount) Resolution.Cell(number, grid.cell(number)) else Resolution.NoSuchNumber(number)
            }
            if (view.badges.isNotEmpty()) {
                val badge = view.badges.firstOrNull { it.number == number } ?: return Resolution.NoSuchNumber(number)
                val element = view.snapshot.element(badge.key) ?: return Resolution.Changed
                return Resolution.One(ChosenElement(badge.key, element.className, element.label, badge.bounds))
            }
            // An actionable element named with its number, such as a key of the dialer or of a calculator (design D6).
            val named = candidates.filter { candidate ->
                candidate.isActionable && names(view.snapshot, candidate).any { Normalizer.normalize(it) == number.toString() }
            }
            return when (named.size) {
                0 -> Resolution.NeedNumbers
                1 -> Resolution.One(chosen(named[0]))
                else -> Resolution.Several(named.map(::chosen))
            }
        }

        private fun label(snapshot: ScreenSnapshot, spoken: String, candidates: List<ScreenElement>, strictness: Strictness): Resolution {
            val named = candidates.map { MatchCandidate(it.key, names(snapshot, it)) }
            return when (val decision = LabelMatcher.decide(spoken, named, strictness)) {
                is MatchDecision.Found -> snapshot.element(decision.id)?.let { Resolution.One(chosen(it)) } ?: Resolution.NotFound
                is MatchDecision.Ambiguous -> Resolution.Several(decision.ids.mapNotNull { snapshot.element(it) }.map(::chosen))
                MatchDecision.NotFound -> Resolution.NotFound
            }
        }

        private fun ownNames(element: ScreenElement): List<String> = listOfNotNull(
            element.label,
            element.text.takeUnless { element.editable },
            element.contentDescription,
            element.hint,
            element.supplementalDescription,
            element.suggestedName,
        ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        // The keys of the elements that have an actionable element around them or inside them.
        private fun nearActionable(snapshot: ScreenSnapshot): Set<String> {
            val near = HashSet<String>()
            for (element in snapshot.elements) {
                var ancestor = element.parent?.let { snapshot.elements.getOrNull(it) }
                var depth = 0
                while (ancestor != null && depth < MAX_DEPTH) {
                    if (element.isActionable) near += ancestor.key
                    if (ancestor.isActionable) near += element.key
                    ancestor = ancestor.parent?.let { snapshot.elements.getOrNull(it) }
                    depth++
                }
            }
            return near
        }

        private fun covered(snapshot: ScreenSnapshot, element: ScreenElement): Boolean {
            val window = snapshot.windows.firstOrNull { it.id == element.windowId } ?: return false
            return snapshot.windows.any { it.layer > window.layer && it.bounds.contains(element.bounds) }
        }

        private fun IntRect.contains(other: IntRect): Boolean =
            left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
    }
}
```

Create `app/src/main/kotlin/dev/kestrel/app/commands/ScreenCommands.kt`:

```kotlin
package dev.kestrel.app.commands

import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.SpokenNumbers
import dev.kestrel.commands.Target
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.commands.text.Strictness
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

internal fun screenCommands(environment: CommandEnvironment): CommandPart = ScreenCommands(environment)

/** How "Which one?" ended: with the element that the owner chose, or with the command's result. */
internal sealed interface Choice {
    data class Chosen(val element: ChosenElement) : Choice

    data class Ends(val result: CommandResult) : Choice
}

/**
 * The screen commands (design spec, sections 9.2 to 9.6, and Appendix A): tap and long press by label, by number and on
 * the grid, bare labels and bare numbers.
 */
internal class ScreenCommands(private val environment: CommandEnvironment) : CommandPart {
    private val targets: ScreenTargets get() = environment.targets

    override suspend fun run(command: Command, heard: String, conversation: CommandConversation): CommandResult? {
        val screen = environment.screen() ?: return null
        return when (command) {
            is Command.Tap -> act(screen, command.target, TargetKind.TAP, conversation, onCell = { tapAt(screen, it) }) {
                screen.actions.tap(it.key)
            }
            is Command.LongPress -> act(
                screen,
                command.target,
                TargetKind.TAP,
                conversation,
                onCell = { screen.actions.longPressAt(it.centerX, it.centerY) },
            ) { screen.actions.longPress(it.key) }
            is Command.Bare -> bare(screen, command)
            // Tasks 5 to 9 add their commands above this line.
            else -> null
        }
    }

    /**
     * Finds [target] among the candidates of [kind], asks "Which one?" when several fit, checks right before the action
     * that the chosen element is still the one that Kestrel chose, and runs [onElement] on it, or [onCell] on a grid cell
     * while the grid shows (without [onCell], a number names a badge). A label that nothing matches makes Kestrel say
     * "I can't find X" and show "Which one?" numbers on all actionable elements.
     */
    suspend fun act(
        screen: ScreenSession,
        target: Target?,
        kind: TargetKind,
        conversation: CommandConversation,
        onCell: (suspend (IntRect) -> ActionResult)? = null,
        onElement: suspend (ScreenElement) -> ActionResult,
    ): CommandResult {
        val found = targets.resolve(target, kind, cells = onCell != null)
        val chosen = when (found) {
            is Resolution.One -> found.element
            is Resolution.Cell -> return done(checkNotNull(onCell)(found.bounds))
            is Resolution.Several -> when (val choice = choose(screen, found.elements, conversation)) {
                is Choice.Chosen -> choice.element
                is Choice.Ends -> return choice.result
            }
            Resolution.NotFound -> return notFound(screen, target)
            Resolution.NeedNumbers -> return CommandResult(Outcome.FAILED, say = CommandTexts.SHOW_NUMBERS_FIRST)
            is Resolution.NoSuchNumber -> return CommandResult(Outcome.FAILED, show = CommandTexts.noNumber(found.number))
            Resolution.Changed -> return changed()
            Resolution.NoScreen -> return CommandResult(Outcome.FAILED, say = CommandTexts.SERVICE_OFF)
        }
        // A key names a position (section 9.2). A label is decided once more on the new screen; a number or an answer may
        // name another element by now.
        val element = targets.still(chosen)
            ?: (if (target is Target.Label && found is Resolution.One) targets.resolve(target, kind) else null)
                ?.let { (it as? Resolution.One)?.element }?.let { targets.still(it) }
            ?: return changed()
        return done(onElement(element))
    }

    /**
     * Shows "Which one?" numbers on [choices] and asks "Which one?" (section 9.4). The answer is a number; an answer that
     * is none ends the command and is read as a new utterance, and the question ends when its numbers go because the
     * screen changed. The numbers go when the question ends.
     */
    suspend fun choose(screen: ScreenSession, choices: List<ChosenElement>, conversation: CommandConversation): Choice {
        try {
            screen.showWhichOne(choices.map { it.key })
            val badges = withContext(Dispatchers.Main) { screen.numberBadges }
            // None of the chosen elements is in the newest snapshot, so there is nothing to choose from.
            if (badges.isEmpty()) return Choice.Ends(changed())
            val answer = when (val asked = askWhileShown(screen, conversation)) {
                Asked.ScreenChanged -> return Choice.Ends(changed())
                is Asked.Answer -> asked.text ?: return Choice.Ends(CommandResult(Outcome.CANCELLED))
            }
            val number = SpokenNumbers.answer(answer) ?: return Choice.Ends(CommandResult(Outcome.CANCELLED, passOn = answer))
            val badge = badges.firstOrNull { it.number == number }
                ?: return Choice.Ends(CommandResult(Outcome.FAILED, show = CommandTexts.noNumber(number)))
            val element = choices.firstOrNull { it.key == badge.key } ?: return Choice.Ends(changed())
            return Choice.Chosen(element)
        } finally {
            withContext(NonCancellable) { screen.endWhichOne() }
        }
    }

    private sealed interface Asked {
        data class Answer(val text: String?) : Asked {
            override fun toString() = "Answer(${text?.length} characters)"
        }

        data object ScreenChanged : Asked
    }

    private suspend fun askWhileShown(screen: ScreenSession, conversation: CommandConversation): Asked = coroutineScope {
        val answer = async { Asked.Answer(conversation.ask(CommandTexts.WHICH_ONE)) }
        val gone = async {
            screen.whichOneShown.first { !it }
            Asked.ScreenChanged
        }
        select<Asked> {
            answer.onAwait { it }
            gone.onAwait { it }
        }.also {
            answer.cancel()
            gone.cancel()
        }
    }

    // "I can't find X", with "Which one?" numbers on all actionable elements, which stay until the screen changes, so that
    // "tap 5" works next.
    private suspend fun notFound(screen: ScreenSession, target: Target?): CommandResult {
        val view = targets.view()
        if (view != null) {
            val keys = ScreenTargets.candidates(view.snapshot, TargetKind.BARE).map { it.key }
            if (keys.isNotEmpty()) screen.showWhichOne(keys)
        }
        val spoken = (target as? Target.Label)?.spoken
        return CommandResult(Outcome.FAILED, say = if (spoken != null) CommandTexts.cantFind(spoken) else CommandTexts.NOTHING_HERE)
    }

    // Design D6: a bare number taps like "tap N" while numbers or the grid show, and otherwise only an element whose whole
    // name is that number; a bare label taps only a clear, strict match. Anything else is ignored without a word, because
    // bare speech may be conversation.
    private suspend fun bare(screen: ScreenSession, command: Command.Bare): CommandResult {
        val number = command.number
        // A bare "okay" is conversation far more often than an answer to a dialog; "tap OK" still taps an "OK" button.
        if (number == null && Normalizer.words(command.text).all { it == "ok" }) return CommandResult(Outcome.IGNORED)
        val found = if (number != null) {
            targets.resolve(Target.Number(number), TargetKind.BARE, cells = true)
        } else {
            targets.resolve(Target.Label(command.text), TargetKind.BARE, Strictness.STRICT)
        }
        return when (found) {
            is Resolution.Cell -> done(tapAt(screen, found.bounds))
            is Resolution.One -> {
                val element = targets.still(found.element) ?: return changed()
                done(screen.actions.tap(element.key))
            }
            else -> CommandResult(Outcome.IGNORED)
        }
    }

    private suspend fun tapAt(screen: ScreenSession, bounds: IntRect): ActionResult = screen.actions.tapAt(bounds.centerX, bounds.centerY)

    // The reasons of ScreenActions are fixed texts without the screen's words, which the bar may show.
    private fun done(result: ActionResult): CommandResult = when (result) {
        is ActionResult.Done -> CommandResult(Outcome.DONE, repeatable = true)
        is ActionResult.Failed -> CommandResult(Outcome.FAILED, show = result.reason)
    }

    private fun changed() = CommandResult(Outcome.FAILED, say = CommandTexts.SCREEN_CHANGED)
}
```

Create `app/src/main/kotlin/dev/kestrel/app/commands/TextCommands.kt`:

```kotlin
package dev.kestrel.app.commands

import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult

/** The text commands of plan 3B (design spec, section 9.7). Plan 3B replaces this stub, which takes no command. */
@Suppress("UNUSED_PARAMETER")
internal fun textCommands(environment: CommandEnvironment): CommandPart = object : CommandPart {
    override suspend fun run(command: Command, heard: String, conversation: CommandConversation): CommandResult? = null
}
```

Create `app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommands.kt`:

```kotlin
package dev.kestrel.app.commands

import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult

/**
 * The phone commands of plan 3C (design spec, sections 9.3 and 13.6): global actions, apps, phone settings, media,
 * magnification and calls. Plan 3C replaces this stub, which takes no command.
 */
@Suppress("UNUSED_PARAMETER")
internal fun phoneCommands(environment: CommandEnvironment): CommandPart = object : CommandPart {
    override suspend fun run(command: Command, heard: String, conversation: CommandConversation): CommandResult? = null
}
```

Create `app/src/main/kotlin/dev/kestrel/app/commands/InstantCommands.kt`:

```kotlin
package dev.kestrel.app.commands

import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.PhoneCommand
import dev.kestrel.screen.ScreenSession
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/**
 * The executor of the instant commands (design spec, section 9.5). It asks the text part, then the phone part, then the
 * screen part; when none takes a command, it runs the transcript once more as a bare label through the screen part, and
 * a result that acted then names that bare label as the command that acted, for "again". Without Kestrel's
 * accessibility service, only the volume, mute, silence and media commands run.
 */
internal class InstantCommands(private val environment: CommandEnvironment) : CommandRunner {
    private val text = textCommands(environment)
    private val phone = phoneCommands(environment)
    private val screen = screenCommands(environment)

    override suspend fun run(command: Command, heard: String, conversation: CommandConversation): CommandResult {
        val session = environment.screen()
        if (session == null && !withoutScreen(command)) {
            return if (command is Command.Bare) CommandResult(Outcome.IGNORED) else CommandResult(Outcome.FAILED, say = CommandTexts.SERVICE_OFF)
        }
        val talk = if (session == null) conversation else TouchCancels(conversation, session)
        for (part in listOf(text, phone, screen)) {
            part.run(command, heard, talk)?.let { return it }
        }
        val bare = Command.Bare.of(heard)
        val result = screen.run(bare, heard, talk) ?: CommandResult(Outcome.IGNORED)
        return if (result.outcome == Outcome.DONE) result.copy(acted = bare) else result
    }

    override fun privateInput(): Boolean = text.privateInput() || phone.privateInput() || screen.privateInput()

    private fun withoutScreen(command: Command): Boolean =
        command is PhoneCommand.Volume || command is PhoneCommand.Mute || command is PhoneCommand.Silence ||
            command is PhoneCommand.Media
}

/**
 * Cancels a command's question or countdown when the owner touches the screen (design spec, section 14.1): while [ask] or
 * [countdown] waits, the touch watcher runs, and a touch makes [ask] return null and [countdown] return false, so the
 * command ends as cancelled.
 */
private class TouchCancels(private val inner: CommandConversation, private val screen: ScreenSession) : CommandConversation {
    override suspend fun say(text: String) = inner.say(text)

    override fun keep(text: String) = inner.keep(text)

    override suspend fun ask(question: String): String? = untilTouched(null) { inner.ask(question) }

    override suspend fun countdown(millis: Long, text: (secondsLeft: Int) -> String): Boolean =
        untilTouched(false) { inner.countdown(millis, text) }

    private suspend fun <T> untilTouched(touched: T, block: suspend () -> T): T = coroutineScope {
        // Subscribed before the watcher starts, so that no touch is lost in between.
        val touch = async(start = CoroutineStart.UNDISPATCHED) { screen.ownerTouches.first() }
        try {
            screen.startTouchWatcher()
            val work = async { block() }
            select<T> {
                work.onAwait { it }
                touch.onAwait { touched }
            }.also { work.cancel() }
        } finally {
            touch.cancel()
            withContext(NonCancellable) { screen.stopTouchWatcher() }
        }
    }
}
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import dev.kestrel.app.commands.CommandConversation
import dev.kestrel.app.commands.CommandRunner
```

with

```kotlin
import dev.kestrel.app.commands.CommandEnvironment
import dev.kestrel.app.commands.CommandOptions
import dev.kestrel.app.commands.CommandRunner
import dev.kestrel.app.commands.InstantCommands
import dev.kestrel.app.commands.ScreenTargets
```

then replace

```kotlin
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import dev.kestrel.privacy.PrivacyStore
```

with

```kotlin
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.privacy.PrivacyStore
```

then replace

```kotlin
    /**
     * Kestrel's listening, one for the whole process (design spec, sections 7.2 and 7.3), so that the accessibility
```

with

```kotlin
    // The owner's options for the instant commands, at their defaults until the settings app stores them.
    private val commandOptions = MutableStateFlow(CommandOptions())

    /**
     * The executor of the instant commands (design spec, section 9.5), which Listening runs on Dispatchers.IO. It works on
     * the screen through Kestrel's accessibility service while that is connected.
     */
    internal val commands: CommandRunner by lazy {
        InstantCommands(
            CommandEnvironment(
                context = appContext,
                screen = { screen.value },
                options = commandOptions.asStateFlow(),
                targets = ScreenTargets { screen.value },
                scope = scope,
                // From the accessibility service, which Android lets start activities from the background (prototype P1).
                // Showing a page and asking for a permission come with the settings page of the instant commands.
                openSettings = { _, _ ->
                    appContext.startActivity(
                        Intent(appContext, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                },
                report = reportFailure,
            ),
        )
    }

    /**
     * Kestrel's listening, one for the whole process (design spec, sections 7.2 and 7.3), so that the accessibility
```

and replace

```kotlin
            // The executor of the instant commands comes with the next task; until then every command is ignored.
            commands = object : CommandRunner {
                override suspend fun run(command: Command, heard: String, conversation: CommandConversation) =
                    CommandResult(Outcome.IGNORED)

                override fun privateInput() = false
            },
```

with

```kotlin
            commands = commands,
```

- [ ] **Step 5: Run the JVM tests to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.commands.ScreenTargetsTest`
Expected: `BUILD SUCCESSFUL`, with 8 tests in `ScreenTargetsTest`.

- [ ] **Step 6: Add the commands fixture to the test app**

Create `testapp/src/main/kotlin/dev/kestrel/testapp/CommandsActivity.kt`:

```kotlin
package dev.kestrel.testapp

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.util.Locale

/**
 * The fixture of the instant commands (design spec, section 16.3): similar labels, two "Delete" buttons in two rows, a
 * full-screen dialog with one "Delete" button, a check box, a switch named "Wi‑Fi" with a non-breaking hyphen, a View
 * slider without a name, a Compose slider, a determinate progress bar, an expandable row, a drag source and target, a
 * horizontal pager, a long-press counter and a Cyrillic label. The status line reports each action. The buttons show
 * their texts as they are, so that the labels in Kestrel's snapshots equal the texts.
 */
class CommandsActivity : ComponentActivity() {
    private lateinit var status: TextView
    private var longPresses = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = fixturePage("Commands fixture")
        status = page.addStatus()
        page.addView(
            row(
                button("Save") { report("Clicked Save") },
                button("Safe") { report("Clicked Safe") },
                button("Send") { report("Clicked Send") },
                button("Send later") { report("Clicked Send later") },
            ),
        )
        page.addView(row(text("Message from Ana"), button("Delete") { report("Deleted Ana") }))
        page.addView(row(text("Message from Dan"), button("Delete") { report("Deleted Dan") }))
        page.addView(row(button("Open dialog") { openDialog() }, counter(), button("Ђорђе") { report("Clicked Ђорђе") }))
        page.addView(
            row(
                CheckBox(this).apply {
                    text = "Subscribe"
                    setOnCheckedChangeListener { _, checked -> report(if (checked) "Checked Subscribe" else "Unchecked Subscribe") }
                },
                // On purpose a non-breaking hyphen (U+2011), as Android's own Wi-Fi labels have.
                Switch(this).apply {
                    text = "Wi\u2011Fi"
                    setOnCheckedChangeListener { _, on -> report(if (on) "Wi\u2011Fi on" else "Wi\u2011Fi off") }
                },
            ),
        )
        page.addView(
            SeekBar(this).apply {
                // No name on purpose: Kestrel numbers it, and "increment N" moves it.
                id = R.id.seek_bar
                max = 100
                progress = 50
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) = report("Seek bar $value")

                    override fun onStartTrackingTouch(bar: SeekBar) = Unit

                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                })
            },
        )
        page.addView(
            ComposeView(this).apply {
                setContent {
                    MaterialTheme {
                        var value by remember { mutableFloatStateOf(0.5f) }
                        Slider(
                            value = value,
                            onValueChange = {
                                value = it
                                report("Slider ${String.format(Locale.ROOT, "%.2f", it)}")
                            },
                            steps = 19,
                            modifier = Modifier.semantics { contentDescription = "Speed slider" },
                        )
                    }
                }
            },
        )
        page.addView(
            // Determinate, so it has a range but offers no action: Kestrel must not number it.
            ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                id = R.id.progress_bar
                isIndeterminate = false
                max = 100
                progress = 40
            },
        )
        page.addView(detailsRow())
        val target = text("Drop here").apply {
            id = R.id.drop_target
            setBackgroundColor(0xFFC8E6C9.toInt())
        }
        val source = DragSourceView(this).apply {
            id = R.id.drag_source
            text = "Drag me"
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xFFFFE0B2.toInt())
            this.target = target
            onDropped = { report("Dropped on Drop here") }
        }
        page.addView(row(source, target))
        page.addView(pager(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))
    }

    private fun report(text: String) {
        status.text = text
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (view in views) addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun text(label: String) = TextView(this).apply {
        text = label
        setPadding(0, dp(12), 0, dp(12))
    }

    private fun counter() = button("Counter") { report("Clicked Counter") }.apply {
        setOnLongClickListener {
            longPresses++
            report(if (longPresses == 1) "Long-pressed 1 time" else "Long-pressed $longPresses times")
            true
        }
    }

    // A full-screen dialog covers the page, so its "Delete" is the only one that a finger, and Kestrel, can reach.
    private fun openDialog() {
        val dialog = Dialog(this, android.R.style.Theme_Material_Light_NoActionBar)
        var deletes = 0
        val dialogStatus = text("Draft").apply { id = R.id.dialog_status }
        dialog.setContentView(
            LinearLayout(this).apply {
                id = R.id.commands_dialog
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(48), dp(16), dp(16))
                addView(dialogStatus)
                addView(
                    button("Delete") {
                        deletes++
                        dialogStatus.text = if (deletes == 1) "Deleted 1 time" else "Deleted $deletes times"
                    },
                )
                addView(button("Close") { dialog.dismiss() })
            },
        )
        dialog.show()
    }

    // A row that offers expand or collapse to accessibility services, as a notification does. Only the row is visible
    // to them, named "Details".
    private fun detailsRow(): View {
        val more = text("Saved at noon").apply {
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return LinearLayout(this).apply {
            id = R.id.details_row
            orientation = LinearLayout.VERTICAL
            contentDescription = "Details"
            addView(text("Details").apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            addView(more)
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    val expanded = more.visibility == View.VISIBLE
                    info.addAction(
                        if (expanded) AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE else AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND,
                    )
                }

                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    val expand = when (action) {
                        AccessibilityNodeInfo.ACTION_EXPAND -> true
                        AccessibilityNodeInfo.ACTION_COLLAPSE -> false
                        else -> return super.performAccessibilityAction(host, action, args)
                    }
                    more.visibility = if (expand) View.VISIBLE else View.GONE
                    report(if (expand) "Expanded Details" else "Collapsed Details")
                    return true
                }
            }
        }
    }

    // Three pages side by side; the status names the page that is in view once a scroll has moved.
    private fun pager() = HorizontalScrollView(this).apply {
        id = R.id.pager
        val width = resources.displayMetrics.widthPixels - dp(32)
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                for (number in 1..3) {
                    addView(text("Page $number"), LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.MATCH_PARENT))
                }
            },
        )
        setOnScrollChangeListener { _, x, _, _, _ -> report("Showing page ${(x + width / 2) / width + 1}") }
    }
}

/**
 * Reports a long press that moved onto [target] and was lifted there, as a launcher's icon is dragged onto a folder.
 * It reads raw touches, so Kestrel must drag it with a gesture.
 */
class DragSourceView(context: Context) : TextView(context) {
    var target: View? = null
    var onDropped: () -> Unit = {}
    private var downAt = 0L

    // On purpose: the source reads raw touches and offers no click action.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> downAt = event.eventTime
            MotionEvent.ACTION_UP -> {
                val drop = target ?: return true
                val origin = IntArray(2).also(drop::getLocationOnScreen)
                val inside = event.rawX.toInt() in origin[0] until origin[0] + drop.width &&
                    event.rawY.toInt() in origin[1] until origin[1] + drop.height
                if (inside && event.eventTime - downAt >= LONG_PRESS_MILLIS) onDropped()
            }
        }
        return true
    }

    private companion object {
        const val LONG_PRESS_MILLIS = 500L
    }
}
```

In `testapp/src/main/res/values/ids.xml`, replace

```xml
    <item name="gesture_pad" type="id" />
</resources>
```

with

```xml
    <item name="gesture_pad" type="id" />
    <item name="commands_dialog" type="id" />
    <item name="dialog_status" type="id" />
    <item name="seek_bar" type="id" />
    <item name="progress_bar" type="id" />
    <item name="details_row" type="id" />
    <item name="drag_source" type="id" />
    <item name="drop_target" type="id" />
    <item name="pager" type="id" />
</resources>
```

In `testapp/src/main/AndroidManifest.xml`, replace

```xml
        <activity
            android:name=".HookProbeActivity"
            android:exported="true" />
    </application>
```

with

```xml
        <activity
            android:name=".HookProbeActivity"
            android:exported="true" />
        <activity
            android:name=".CommandsActivity"
            android:exported="true" />
        <!-- Plan 3B: text fixtures -->
        <!-- Plan 3C: phone fixtures -->
    </application>
```

- [ ] **Step 7: Add the command test helpers and the first command device tests**

Create `app/src/androidTest/kotlin/dev/kestrel/app/commands/TextSessions.kt`:

```kotlin
package dev.kestrel.app.commands

import dev.kestrel.app.listen.Session
import dev.kestrel.app.listen.SessionListener
import dev.kestrel.app.listen.VoiceSessions
import dev.kestrel.voice.listen.Microphone
import dev.kestrel.voice.listen.Recognition
import dev.kestrel.voice.speak.Spoke
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * Voice sessions for the command device tests (design D15 of sub-project 3): a session needs no speech models, never
 * records, finishes each reply at once and remembers what Kestrel said. The tests send their transcripts as events.
 */
internal class TextSessions : VoiceSessions {
    private val said = mutableListOf<String>()

    /** What Kestrel said, in order. */
    val spoken: List<String> get() = synchronized(said) { said.toList() }

    override suspend fun open(microphone: Microphone, listener: SessionListener): Session = object : Session {
        override var recognition: Recognition = Recognition.ALL

        override fun startMicrophone() = Unit

        override fun stopMicrophone() = Unit

        override fun speak(text: String): Deferred<Spoke> {
            synchronized(said) { said += text }
            return CompletableDeferred(Spoke.FINISHED)
        }

        override fun stopSpeaking() = Unit

        override suspend fun close() = Unit
    }
}
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/commands/CommandTests.kt`:

```kotlin
package dev.kestrel.app.commands

import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.AppGraph
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.listen.VoiceSessions
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.voice.VoiceTests
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Drives instant commands on the device through the same path as speech (design D15 of sub-project 3): a text goes to
 * Kestrel's Orchestrator as a transcript without the name, and the test waits for the command's report. The voice session
 * is [TextSessions], so the tests need no speech models. Every command test skips itself unless the instrumentation
 * argument [ARGUMENT] is true, which only scripts\command-test.ps1 passes, because only that script puts back what the
 * tests change on the device.
 */
internal object CommandTests {
    const val ARGUMENT = "kestrelCommands"
    private const val REPORT_MILLIS = 15_000L

    val graph: AppGraph
        get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KestrelApplication).graph

    /**
     * Skips each test unless [ARGUMENT] is true, before any other rule runs: every command test class applies it first,
     * as its rule of order 0, so that a skipped test never reaches ScreenServiceRule, which changes the device.
     */
    val only: TestRule
        get() = TestRule { base: Statement, _: Description ->
            object : Statement() {
                override fun evaluate() {
                    assumeCommands()
                    base.evaluate()
                }
            }
        }

    private var sessions: TextSessions? = null
    private var previousSessions: VoiceSessions? = null
    private var collector: Job? = null
    private val reports = Channel<CommandReport>(Channel.UNLIMITED)

    /** What Kestrel said since [start]. */
    val spoken: List<String> get() = sessions?.spoken.orEmpty()

    /** Whether the running command waits for the answer to its question. */
    val asked: Boolean get() = graph.listening.state.value.asking

    fun assumeCommands() =
        assumeTrue("Runs only through scripts\\command-test.ps1", InstrumentationRegistry.getArguments().getString(ARGUMENT) == "true")

    /**
     * Turns listening on with [TextSessions], and waits until the session is open and Kestrel listens. Kestrel's
     * accessibility service must be on (ScreenServiceRule), so that Android lets it start its listening service.
     */
    fun start() {
        assumeCommands()
        VoiceTests.grantListeningPermissions()
        val listening = graph.listening
        // An earlier test that failed may have left Kestrel on.
        if (listening.state.value.mode != Mode.OFF) turnOff()
        val text = TextSessions()
        if (previousSessions == null) previousSessions = listening.sessions
        listening.sessions = text
        sessions = text
        while (reports.tryReceive().isSuccess) Unit
        collector = graph.scope.launch(start = CoroutineStart.UNDISPATCHED) { listening.commandReports.collect { reports.send(it) } }
        listening.send(Event.Start())
        check(VoiceTests.waitUntil(15_000) { listening.sessionOpen.value && listening.state.value.mode == Mode.LISTENING }) {
            "Listening did not start; the mode is ${listening.state.value.mode}"
        }
    }

    /** Turns Kestrel off, waits until the session has closed and the service has stopped, and puts the sessions back. */
    fun stop() {
        if (sessions == null) return
        try {
            turnOff()
        } finally {
            collector?.cancel()
            collector = null
            previousSessions?.let { graph.listening.sessions = it }
            previousSessions = null
            sessions = null
        }
    }

    private fun turnOff() {
        val listening = graph.listening
        listening.send(Event.TurnOff)
        check(VoiceTests.waitUntil(30_000) { listening.state.value.mode == Mode.OFF && !listening.sessionOpen.value }) {
            "Listening did not stop"
        }
        check(VoiceTests.waitUntil(10_000) { !VoiceTests.listeningServiceRuns() }) { "ListeningService still runs" }
    }

    /** Sends [text] as a transcript without the name, as speech does. */
    fun send(text: String) = graph.listening.send(Event.Heard(text, named = false))

    /** Sends [text] and waits for the report of the command that it started. */
    fun say(text: String, timeoutMillis: Long = REPORT_MILLIS): CommandReport {
        send(text)
        return next(timeoutMillis)
    }

    /** Sends [text], waits until its command asks, and returns what Kestrel asked. */
    fun ask(text: String, timeoutMillis: Long = REPORT_MILLIS): String {
        val before = spoken.size
        send(text)
        check(VoiceTests.waitUntil(timeoutMillis) { asked && spoken.size > before }) { "The command asked nothing" }
        return spoken.last()
    }

    /** Answers the running command's question with [text], and waits for the command's report. */
    fun answer(text: String, timeoutMillis: Long = REPORT_MILLIS): CommandReport = say(text, timeoutMillis)

    /** Waits for the report of the next command that ends. */
    fun next(timeoutMillis: Long = REPORT_MILLIS): CommandReport =
        runBlocking { withTimeoutOrNull(timeoutMillis) { reports.receive() } }
            ?: throw AssertionError("No command ended within $timeoutMillis ms")

    /** Waits until Kestrel has said [text]; the spoken result of a command comes a moment after its report. */
    fun awaitSaid(text: String, timeoutMillis: Long = 5_000) {
        check(VoiceTests.waitUntil(timeoutMillis) { spoken.lastOrNull() == text }) { "Kestrel did not say the expected text" }
    }
}
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/commands/TouchCommandsTest.kt`:

```kotlin
package dev.kestrel.app.commands

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.TestFinger
import dev.kestrel.commands.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tap and long press by label and by number, "Which one?", "I can't find X", bare labels and "again" (design D6). */
@RunWith(AndroidJUnit4::class)
class TouchCommandsTest {
    // Before ScreenServiceRule, as in RefusedReplyTest: a run without kestrelCommands=true skips each test before the rule
    // switches the accessibility services, and before @Before and @After run.
    @get:Rule(order = 0)
    val only = CommandTests.only

    @get:Rule(order = 1)
    val screen = ScreenServiceRule()

    @Before
    fun listenOnTheFixture() {
        CommandTests.start()
        screen.open("CommandsActivity", "Commands fixture")
    }

    @After
    fun stopListening() = CommandTests.stop()

    private fun awaitStatus(text: String) = runBlocking { screen.awaitElement { it.label == text } }

    @Test
    fun tapByLabelTapsTheButton() {
        assertEquals(Outcome.DONE, CommandTests.say("tap Send").outcome)
        awaitStatus("Clicked Send")
    }

    @Test
    fun theExactLabelWinsOverASimilarOne() {
        assertEquals(Outcome.DONE, CommandTests.say("tap Safe").outcome)
        awaitStatus("Clicked Safe")
        assertEquals(Outcome.DONE, CommandTests.say("tap Save").outcome)
        awaitStatus("Clicked Save")
    }

    @Test
    fun whichOneAsksAndTheAnsweredNumberActs() {
        assertEquals(CommandTexts.WHICH_ONE, CommandTests.ask("tap Delete"))
        assertEquals(2, screen.session.numberBadges.size)

        // The rows are numbered from the top: Ana's row is 1 and Dan's row is 2.
        assertEquals(Outcome.DONE, CommandTests.answer("number two").outcome)
        awaitStatus("Deleted Dan")
        assertTrue(screen.session.numberBadges.isEmpty())
    }

    @Test
    fun anAnswerThatIsNoNumberRunsAsANewCommand() {
        CommandTests.ask("tap Delete")

        assertEquals(Outcome.CANCELLED, CommandTests.answer("tap Send").outcome)
        assertEquals(Outcome.DONE, CommandTests.next().outcome)
        awaitStatus("Clicked Send")
    }

    @Test
    fun aLabelThatNothingMatchesSaysSoAndLeavesNumbersForTheNextTap() {
        assertEquals(Outcome.FAILED, CommandTests.say("tap Weather").outcome)
        CommandTests.awaitSaid("I can't find Weather.")
        val send = runBlocking { screen.awaitElement { it.label == "Send" } }
        val number = screen.session.numberBadges.first { it.key == send.key }.number

        assertEquals(Outcome.DONE, CommandTests.say("tap $number").outcome)
        awaitStatus("Clicked Send")
    }

    @Test
    fun aBareLabelTapsOnlyAClearMatchAndTwoEqualLabelsAreIgnored() {
        assertEquals(Outcome.DONE, CommandTests.say("Send later").outcome)
        awaitStatus("Clicked Send later")

        assertEquals(Outcome.IGNORED, CommandTests.say("Delete").outcome)
        assertFalse(CommandTests.asked)
        // Nothing was tapped, and Kestrel said nothing.
        awaitStatus("Clicked Send later")
        assertTrue(CommandTests.spoken.isEmpty())
    }

    @Test
    fun deleteInADialogAndAgainTapItsOnlyDeleteButtonTwice() {
        assertEquals(Outcome.DONE, CommandTests.say("tap Open dialog").outcome)
        awaitStatus("Draft")

        assertEquals(Outcome.DONE, CommandTests.say("Delete").outcome)
        awaitStatus("Deleted 1 time")
        assertEquals(Outcome.DONE, CommandTests.say("again").outcome)
        awaitStatus("Deleted 2 times")
    }

    @Test
    fun aNumberWithoutNumbersOnTheScreenAsksForThem() {
        assertEquals(Outcome.FAILED, CommandTests.say("tap 7").outcome)
        CommandTests.awaitSaid(CommandTexts.SHOW_NUMBERS_FIRST)
    }

    @Test
    fun longPressByLabel() {
        assertEquals(Outcome.DONE, CommandTests.say("long press Counter").outcome)
        awaitStatus("Long-pressed 1 time")
    }

    @Test
    fun aCyrillicLabelAnswersToItsLatinSpelling() {
        assertEquals(Outcome.DONE, CommandTests.say("tap Djordje").outcome)
        awaitStatus("Clicked Ђорђе")
    }

    @Test
    fun whichOneEndsWhenTheScreenChanges() {
        CommandTests.ask("tap Delete")

        // Another activity changes the screen without a touch, so only the change of the screen can end the question.
        screen.shell("am start -W -n ${ScreenServiceRule.FIXTURES}/.ViewsActivity")

        // Well before the question's own 15 s timeout.
        assertEquals(Outcome.FAILED, CommandTests.next(5_000).outcome)
        CommandTests.awaitSaid(CommandTexts.SCREEN_CHANGED)
    }

    @Test
    fun stopEndsWhichOneAndRemovesItsNumbers() {
        CommandTests.ask("tap Delete")

        assertEquals(Outcome.CANCELLED, CommandTests.say("stop", 5_000).outcome)
        // The job removes its numbers before it completes, and the report comes after that.
        assertTrue(screen.session.numberBadges.isEmpty())
    }

    @Test
    fun theOwnersTouchCancelsWhichOne() = runBlocking {
        TestFinger(screen.automation).use { finger ->
            val title = screen.awaitElement { it.label == "Commands fixture" }.bounds
            CommandTests.ask("tap Delete")
            screen.awaitInputWindow("Kestrel touch watcher")

            finger.tap(title.centerX, title.centerY)

            // Well before the question's own 15 s timeout, which would also end it as cancelled.
            assertEquals(Outcome.CANCELLED, CommandTests.next(5_000).outcome)
        }
    }
}
```

- [ ] **Step 8: Run the JVM tests of `:app`**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, with 307 tests in `:app` (299 + `ScreenTargetsTest` 8).

- [ ] **Step 9: Write the failing Pester tests of the script helpers**

Create `scripts/tests/CommandTest.Tests.ps1`:

```powershell
# Tests of the helpers in scripts\lib\Kestrel.ps1 that scripts\command-test.ps1 uses to read the device's state and to
# choose the tests. scripts\test-scripts.ps1 runs these tests in Windows PowerShell 5.1 and in PowerShell 7.

Describe 'Get-StreamVolume' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
    }

    It 'reads the index from the answer of cmd media_session volume --get' {
        Get-StreamVolume -Lines @('[V] will get volume', '[V] stream=3', '[V] volume is 7 in range [0..25]') | Should -Be 7
    }

    It 'throws when the answer has no volume' {
        { Get-StreamVolume -Lines @('Unknown command') } | Should -Throw -ExpectedMessage 'Not an answer of cmd media_session volume: *'
    }
}

Describe 'Get-UserIdsNamed' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
    }

    It 'returns the IDs of the users with the name' {
        $lines = @('Users:', '	UserInfo{0:Owner:c13} running', '	UserInfo{10:KestrelTest:1030}', '	UserInfo{11:KestrelTest:1030}')

        Get-UserIdsNamed -Lines $lines -Name 'KestrelTest' | Should -Be @(10, 11)
    }

    It 'returns nothing when no user has the name' {
        @(Get-UserIdsNamed -Lines @('Users:', '	UserInfo{0:Owner:c13} running') -Name 'KestrelTest').Count | Should -Be 0
    }
}

Describe 'Get-GrantedPermissions' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
        $script:dump = @(
            '    install permissions:',
            '      android.permission.INTERNET: granted=true',
            '    User 0: ceDataInode=1 installed=true',
            '      runtime permissions:',
            '        android.permission.RECORD_AUDIO: granted=true, flags=[ USER_SET ]',
            '        android.permission.READ_CONTACTS: granted=false, flags=[ ]',
            '        android.permission.POST_NOTIFICATIONS: granted=true, flags=[ USER_SET ]'
        )
    }

    It 'returns the permissions of the list that are granted' {
        $asked = @('android.permission.RECORD_AUDIO', 'android.permission.READ_CONTACTS', 'android.permission.POST_NOTIFICATIONS', 'android.permission.READ_PHONE_STATE')

        Get-GrantedPermissions -Dump $script:dump -Permissions $asked | Should -Be @('android.permission.RECORD_AUDIO', 'android.permission.POST_NOTIFICATIONS')
    }

    It 'returns nothing for a package that is not installed' {
        @(Get-GrantedPermissions -Dump @('Unable to find package: dev.kestrel') -Permissions @('android.permission.RECORD_AUDIO')).Count | Should -Be 0
    }
}

Describe 'Get-CommandTestSelection' {
    BeforeAll {
        . "$PSScriptRoot\..\lib\Kestrel.ps1"
    }

    It 'selects the whole package without classes' {
        Get-CommandTestSelection -Class @() | Should -BeExactly 'package=dev.kestrel.app.commands'
    }

    It 'selects classes and methods of the package by their simple names' {
        Get-CommandTestSelection -Class @('TouchCommandsTest', 'GestureCommandsTest#swipeUpOnANumber') |
            Should -BeExactly 'class=dev.kestrel.app.commands.TouchCommandsTest,dev.kestrel.app.commands.GestureCommandsTest#swipeUpOnANumber'
    }

    It 'refuses a name that is no class name: <_>' -ForEach @('dev.kestrel.app.voice.VoiceHookTest', 'Touch;rm', '', '#method') {
        { Get-CommandTestSelection -Class @($_) } | Should -Throw -ExpectedMessage 'Not a test class of dev.kestrel.app.commands: *'
    }
}
```

Run: `scripts\test-scripts.ps1`
Expected: exit code 1 and "The script tests or checks failed.": in each shell the new tests fail with "The term 'Get-StreamVolume' is not recognized", "The term 'Get-UserIdsNamed' is not recognized", "The term 'Get-GrantedPermissions' is not recognized" and "The term 'Get-CommandTestSelection' is not recognized".

- [ ] **Step 10: Add the script helpers and command-test.ps1**

In `scripts/lib/Kestrel.ps1`, replace

```powershell
# Returns the largest _id in the output of 'content query --uri <uri> --projection _id', whose lines read
# 'Row: 0 _id=12', or 0 when it has no row, as when the device answers 'No result found.'.
```

with

```powershell
# Reads the index of a stream's volume from the answer of 'cmd media_session volume --stream <stream> --get', whose line
# reads '[V] volume is 7 in range [0..25]'. Throws when the answer has no such line.
function Get-StreamVolume([string[]]$Lines) {
    foreach ($line in $Lines) {
        if ($line -match 'volume is (\d+) in range') { return [int]$Matches[1] }
    }
    throw "Not an answer of cmd media_session volume: '$($Lines -join ' ')'"
}

# Returns the IDs of the users called [Name] in the output of 'pm list users', whose lines read
# 'UserInfo{10:KestrelTest:1030}', such as the work profile that the command tests make.
function Get-UserIdsNamed([string[]]$Lines, [string]$Name) {
    foreach ($line in $Lines) {
        if ($line -match 'UserInfo\{(\d+):([^:}]*):' -and $Matches[2] -ceq $Name) { [int]$Matches[1] }
    }
}

# Returns the permissions of the list that the output of 'dumpsys package <package>' shows as granted, each once, in the
# order of the list. A package that is not installed has none.
function Get-GrantedPermissions([string[]]$Dump, [string[]]$Permissions) {
    $text = $Dump -join "`n"
    foreach ($permission in $Permissions) {
        if ($text -match "$([regex]::Escape($permission)): granted=true") { $permission }
    }
}

# The instrumentation argument that selects command device tests: the whole package dev.kestrel.app.commands, or the
# classes of [Class], each a simple class name with an optional '#method'. Throws for any other name, so that no name can
# reach other packages or the shell.
function Get-CommandTestSelection([string[]]$Class) {
    if ($Class.Count -eq 0) { return 'package=dev.kestrel.app.commands' }
    $names = foreach ($name in $Class) {
        if ($name -cnotmatch '^[A-Z][A-Za-z0-9]*(#[a-z][A-Za-z0-9]*)?$') { throw "Not a test class of dev.kestrel.app.commands: '$name'" }
        "dev.kestrel.app.commands.$name"
    }
    "class=$($names -join ',')"
}

# Returns the largest _id in the output of 'content query --uri <uri> --projection _id', whose lines read
# 'Row: 0 _id=12', or 0 when it has no row, as when the device answers 'No result found.'.
```

Create `scripts/command-test.ps1`:

```powershell
# Runs the device tests of the instant commands (the package dev.kestrel.app.commands) on the kestrel-api37 emulator
# (design spec, section 16.3), and refuses every other device, because the tests change the device's state. It reads that
# state first and puts it back at the end, also when the run failed or Ctrl+C interrupted it: Wi-Fi and Bluetooth, the
# ringer mode and the volumes of the call, ring, music, alarm and notification streams, the list of Quick Settings tiles,
# the lock-screen setting (it also wakes the screen and dismisses the keyguard), users called KestrelTest, raw contacts of
# the account kestrel-test, the call-log rows and images newer than the largest IDs that it saw first, the test app's
# notifications (it stops the test app), and the runtime permissions that the tests grant, which it revokes when they
# were not granted before. Kestrel's microphone and notification permissions, which the tests grant, stay granted, as
# after voice-test.ps1, because revoking a permission ends Kestrel's process. When a read of that state fails, the script
# puts back nothing. connected-test.ps1, which runs the tests, puts back the accessibility settings, the magnification's
# among them, and turning Kestrel's accessibility service off ends a magnification that it started.
# It passes the instrumentation argument kestrelCommands=true, without which every command test skips itself, so a run
# with a skipped test, or without tests, fails. Kestrel and its test APK stay installed. After Ctrl+C, it ends with exit
# code 1. Each command at the end runs only if the serial still names the emulator that the script accepted at the start;
# if one cannot run, the script warns with the command that does it by hand and ends with exit code 1.
# Example: scripts\command-test.ps1 -Serial emulator-5554
#          scripts\command-test.ps1 -Serial emulator-5554 -Class TouchCommandsTest,GestureCommandsTest#swipeUpOnANumber
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    # Test classes of dev.kestrel.app.commands by their simple names, each with an optional '#method'.
    [string[]]$Class = @()
)
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\lib\Kestrel.ps1"

# Runs a command that puts the device back, and returns whether it worked. When it did not, it warns with the command
# that does it by hand.
function Invoke-DeviceRestore([string]$What, [string]$Command) {
    try {
        Invoke-SettingsCommand -Serial $Serial -Command $Command | Out-Null
        $true
    } catch {
        # The message is Invoke-SettingsCommand's, which holds only the command, the serial and adb's own error text.
        Write-Warning ("Could not $What on $Serial ($($_.Exception.Message)). If the emulator has another serial now, use " +
            "that one. Run: & '$((Get-Adb) -replace "'", "''")' -s $Serial shell $Command")
        $false
    }
}

# 'content' writes a refusal to stderr and still ends with exit code 0, and Invoke-SettingsCommand keeps only stdout, so
# a delete runs with its stderr joined to stdout. A delete that works prints nothing. The device's answer names only the
# provider and Java classes.
function Invoke-ContentDelete([string]$What, [string]$Arguments) {
    $command = "content delete $Arguments 2>&1"
    $answer = @(try { Invoke-SettingsCommand -Serial $Serial -Command $command } catch { $_.Exception.Message })
    if ($answer.Count -eq 0) { return $true }
    Write-Warning ("Could not $What on $Serial (the device answered '$($answer[0])'). If the emulator has another serial now, " +
        "use that one. Run: & '$((Get-Adb) -replace "'", "''")' -s $Serial shell $command")
    $false
}

# Reads one line of the device's answer, without spaces around it.
function Read-DeviceValue([string]$Command) {
    ((Invoke-SettingsCommand -Serial $Serial -Command $Command) -join "`n").Trim()
}

$code = 1
$ended = $false
$failed = $false
$saved = $null
# The streams whose volume the tests change: the call, ring, music, alarm and notification streams.
$streams = @(0, 2, 3, 4, 5)
$ringerModes = @{ '0' = 'SILENT'; '1' = 'VIBRATE'; '2' = 'NORMAL' }
# The runtime permissions that the tests may grant and the script revokes afterwards. Kestrel's microphone and
# notification permissions are not among them: they stay granted, as after voice-test.ps1.
$permissions = [ordered]@{
    'dev.kestrel' = @('android.permission.READ_CONTACTS', 'android.permission.READ_PHONE_STATE')
    'dev.kestrel.testapp' = @('android.permission.POST_NOTIFICATIONS')
}
try {
    Use-KestrelToolchain
    if ($Serial -notlike 'emulator-*') { throw "Refusing ${Serial}: the command tests run only on the kestrel-api37 emulator." }
    Assert-TestDevice -Serial $Serial
    $selection = Get-CommandTestSelection -Class $Class
    # Read whole before it counts, so that a read that fails leaves the restore nothing to put back.
    $before = [ordered]@{
        Wifi = Read-DeviceValue 'settings get global wifi_on'
        Bluetooth = Read-DeviceValue 'settings get global bluetooth_on'
        Ringer = Read-DeviceValue 'settings get global mode_ringer'
        # A Hashtable, because an [ordered] dictionary reads an integer key as a position.
        Volumes = @{}
        Tiles = Get-SecureSetting -Serial $Serial -Name 'sysui_qs_tiles'
        LockScreenOff = (Read-DeviceValue 'cmd lock_settings get-disabled') -eq 'true'
        CallLog = Get-LargestRowId -Rows (Invoke-SettingsCommand -Serial $Serial -Command 'content query --uri content://call_log/calls --projection _id')
        Images = Get-LargestRowId -Rows (Invoke-SettingsCommand -Serial $Serial -Command 'content query --uri content://media/external/images/media --projection _id')
        Granted = @{}
    }
    foreach ($stream in $streams) {
        $before.Volumes[$stream] = Get-StreamVolume -Lines (Invoke-SettingsCommand -Serial $Serial -Command "cmd media_session volume --stream $stream --get")
    }
    foreach ($package in $permissions.Keys) {
        $dump = Invoke-SettingsCommand -Serial $Serial -Command "dumpsys package $package"
        $before.Granted[$package] = @(Get-GrantedPermissions -Dump $dump -Permissions $permissions[$package])
    }
    $saved = $before
    $results = Join-Path $PSScriptRoot '..\app\build\outputs\androidTest-results\connected'
    if (Test-Path -LiteralPath $results) { Remove-Item -LiteralPath $results -Recurse -Force }
    $tasks = @(
        ':app:connectedDebugAndroidTest'
        "-Pandroid.testInstrumentationRunnerArguments.$selection"
        '-Pandroid.testInstrumentationRunnerArguments.kestrelCommands=true'
        '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true'
        # As in voice-test.ps1: no instrumentation argument of an earlier run may be reused by mistake.
        '--no-configuration-cache'
    )
    & "$PSScriptRoot\connected-test.ps1" -Serial $Serial @tasks
    $code = $LASTEXITCODE
    $counts = Get-TestResultCounts -Folder $results
    Write-Host "Command tests: $($counts.Tests) run, $($counts.Failures) failed, $($counts.Errors) errors, $($counts.Skipped) skipped."
    if ($code -eq 0 -and ($counts.Tests -eq 0 -or $counts.Skipped -gt 0)) {
        Write-Host 'The run has no tests or has skipped tests, so it fails.'
        $code = 1
    }
    $ended = $true
} catch {
    $failed = $true
    throw
} finally {
    # A stop, such as Ctrl+C, that comes while a finally block runs ends that block at once, so the restore runs in a try
    # block of its own, and the exit code is set in its finally block.
    try {
        if ($saved) {
            # The ringer mode first, because it changes the ring volume.
            $ringer = try { Read-DeviceValue 'settings get global mode_ringer' } catch { $null }
            if ($ringer -ne $saved.Ringer -and $ringerModes.Contains($saved.Ringer) -and
                -not (Invoke-DeviceRestore 'put the ringer mode back' "cmd audio set-ringer-mode $($ringerModes[$saved.Ringer])")) {
                $code = 1
            }
            foreach ($stream in $saved.Volumes.Keys) {
                $now = try { Get-StreamVolume -Lines (Invoke-SettingsCommand -Serial $Serial -Command "cmd media_session volume --stream $stream --get") } catch { -1 }
                if ($now -ne $saved.Volumes[$stream] -and
                    -not (Invoke-DeviceRestore "put the volume of stream $stream back" "cmd audio set-volume $stream $($saved.Volumes[$stream])")) {
                    $code = 1
                }
            }
            $wifi = try { Read-DeviceValue 'settings get global wifi_on' } catch { $null }
            if (($wifi -eq '0') -ne ($saved.Wifi -eq '0')) {
                $state = if ($saved.Wifi -eq '0') { 'disabled' } else { 'enabled' }
                if (-not (Invoke-DeviceRestore 'put Wi-Fi back' "cmd wifi set-wifi-enabled $state")) { $code = 1 }
            }
            $bluetooth = try { Read-DeviceValue 'settings get global bluetooth_on' } catch { $null }
            if (($bluetooth -eq '0') -ne ($saved.Bluetooth -eq '0')) {
                $state = if ($saved.Bluetooth -eq '0') { 'disable' } else { 'enable' }
                if (-not (Invoke-DeviceRestore 'put Bluetooth back' "cmd bluetooth_manager $state")) { $code = 1 }
            }
            $tiles = try { Get-SecureSetting -Serial $Serial -Name 'sysui_qs_tiles' } catch { $null }
            if ($tiles -cne $saved.Tiles -and -not (Set-SecureSetting -Serial $Serial -Name 'sysui_qs_tiles' -Value $saved.Tiles)) { $code = 1 }
            if ($saved.LockScreenOff -and -not (Invoke-DeviceRestore 'turn off the lock screen again' 'cmd lock_settings set-disabled true')) {
                $code = 1
            }
            $users = try { @(Get-UserIdsNamed -Lines (Invoke-SettingsCommand -Serial $Serial -Command 'pm list users') -Name 'KestrelTest') } catch { @() }
            foreach ($user in $users) {
                if (-not (Invoke-DeviceRestore "remove the test user $user" "pm remove-user $user")) { $code = 1 }
            }
            $contacts = "--uri 'content://com.android.contacts/raw_contacts?caller_is_syncadapter=true' --where account_name=\'kestrel-test\'"
            if (-not (Invoke-ContentDelete 'delete the test contacts' $contacts)) { $code = 1 }
            # Only when something is new, so that a run that made nothing needs no write.
            $callLog = try { Get-LargestRowId -Rows (Invoke-SettingsCommand -Serial $Serial -Command 'content query --uri content://call_log/calls --projection _id') } catch { $null }
            if ($callLog -ne $saved.CallLog -and
                -not (Invoke-ContentDelete 'delete the new call-log rows' "--uri content://call_log/calls --where '_id>$($saved.CallLog)'")) {
                $code = 1
            }
            $images = try { Get-LargestRowId -Rows (Invoke-SettingsCommand -Serial $Serial -Command 'content query --uri content://media/external/images/media --projection _id') } catch { $null }
            if ($images -ne $saved.Images -and
                -not (Invoke-ContentDelete 'delete the new screenshots' "--uri content://media/external/images/media --where '_id>$($saved.Images)'")) {
                $code = 1
            }
            # Stopping the test app also removes its notifications.
            if (-not (Invoke-DeviceRestore "stop the test app" 'am force-stop dev.kestrel.testapp')) { $code = 1 }
            # Last, because revoking a permission ends the app's process.
            foreach ($package in $permissions.Keys) {
                $dump = try { Invoke-SettingsCommand -Serial $Serial -Command "dumpsys package $package" } catch { @() }
                foreach ($permission in @(Get-GrantedPermissions -Dump $dump -Permissions $permissions[$package])) {
                    if ($permission -in $saved.Granted[$package]) { continue }
                    if (-not (Invoke-DeviceRestore "revoke $permission of $package" "pm revoke $package $permission")) { $code = 1 }
                }
            }
            if (-not (Invoke-DeviceRestore 'wake the screen' 'input keyevent KEYCODE_WAKEUP')) { $code = 1 }
            if (-not (Invoke-DeviceRestore 'dismiss the keyguard' 'wm dismiss-keyguard')) { $code = 1 }
        }
    } finally {
        # After a stop, such as Ctrl+C, PowerShell would end the script with exit code 0. A failed run ends with its error.
        if (-not ($ended -or $failed)) {
            # As in connected-test.ps1: pwsh -File ends a stopped script that declares parameters with exit code 0, even
            # after 'exit 1', unless the script gives the host its exit code, which it does only when nothing called it.
            if (@(Get-PSCallStack).Count -eq 1) { $Host.SetShouldExit(1) }
            exit 1
        }
    }
}
exit $code
```

Run: `scripts\test-scripts.ps1`
Expected: "The script tests and checks passed.", with 144 Pester tests in each shell (132 + `CommandTest.Tests.ps1` 12: 2 + 2 + 2 + 6) and "PSScriptAnalyzer found 0 problems."

- [ ] **Step 11: Name the script in AGENTS.md and update the spec**

In `AGENTS.md`, replace

```markdown
- Run device tests only through `scripts\connected-test.ps1`, `scripts\voice-test.ps1` or `scripts\listen-check.ps1`, as each plan says, and install only through `scripts\install.ps1`.
```

with

```markdown
- Run device tests only through `scripts\connected-test.ps1`, `scripts\voice-test.ps1`, `scripts\command-test.ps1` (the instant commands, on the emulator only) or `scripts\listen-check.ps1`, as each plan says, and install only through `scripts\install.ps1`.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace

```markdown
| Which one? | When "tap X" matches several elements, numbers appear on those elements only. They go when the screen changes. If "show numbers" is on, its numbers then come back. |
```

with

```markdown
| Which one? | When a command's words fit several elements, numbers appear on those elements only, and Kestrel asks "Which one?"; the answer is a number, such as "2", "number 2" or "tap 2". The numbers go when the command ends or the screen changes, and the question then ends; if "show numbers" is on, its numbers come back. When nothing fits, Kestrel says "I can't find X" and shows numbers on all actionable elements until the screen changes. |
```

then replace

```markdown
- **Deciding:** Kestrel acts when the best score passes a threshold and clearly beats the second best. Otherwise it shows numbers on the candidates and asks "Which one?". When nothing comes close, it says "I can't find X" and shows numbers.
```

with

```markdown
- **Deciding:** Kestrel acts when the best score passes a threshold and clearly beats the second best. Otherwise it shows numbers on the candidates and asks "Which one?". When nothing comes close, it says "I can't find X" and shows numbers. A number names a badge, or a cell while the grid shows; without numbers or the grid, it names only an actionable element whose whole name is that number, such as a key of the dialer, and otherwise Kestrel says "Say 'show numbers' first."
- **Checking:** right before it acts, Kestrel checks that the element still has the class, the label and most of the bounds that it had when Kestrel chose it, because a key names a position (section 9.2). A label is then decided once more; a number or an answer to "Which one?" ends with "The screen changed. Say it again."
- **Bare labels and numbers:** a bare label acts only on its one exact match or a clear match under the strict limits, and never asks "Which one?" or says "I can't find X", because the owner may be talking to someone. A bare "okay" or "OK" is ignored, because it is conversation far more often than an answer to a dialog; "tap OK" taps an "OK" button. A bare number acts like "tap N" while numbers or the grid show. Anything else that is not a command is ignored without a word.
```

then replace

```markdown
Kestrel offers these Voice Access settings: numbers for all elements or only unlabeled ones, require verbs, behaviour on screen wake-up, time-out after no speech, listening for incoming calls, in-call commands, and the earbud microphone. Voice Access's "Cancel on touch" option is always on for tasks in Kestrel (section 14.1).
```

with

```markdown
Kestrel offers these Voice Access settings: numbers for all elements or only unlabeled ones, require verbs, behaviour on screen wake-up, time-out after no speech, listening for incoming calls, in-call commands, and the earbud microphone. Voice Access's "Cancel on touch" option is always on in Kestrel, for tasks and for an instant command's question or a call's countdown (section 14.1).
```

then replace

```markdown
- Any touch on the screen cancels a running task, a routine replay, a pending confirmation or an open question from the agent. Kestrel stops at once, removes its task overlays, says "Cancelled", and reports what it had already done (section 14.5). This way, Kestrel never races with the owner, and changes that it did not make never confuse it.
```

with

```markdown
- Any touch on the screen cancels a running task, a routine replay, a pending confirmation or an open question from the agent. Kestrel stops at once, removes its task overlays, says "Cancelled", and reports what it had already done (section 14.5). This way, Kestrel never races with the owner, and changes that it did not make never confuse it.
- A touch also cancels an instant command that waits for the owner: its "Which one?" question, or the countdown before a call (section 13.6). The bar then shows "Cancelled", and the owner's touch reaches the app as usual.
```

then replace

```markdown
| Options | Cancel on touch | Always on for tasks (section 14.1) |
```

with

```markdown
| Options | Cancel on touch | Always on, for tasks and for an instant command's question or a call's countdown (section 14.1) |
```

and replace

```markdown
These run on `kestrel-api37`, and on the A71 before each release. They use the fixture screens in `:testapp`: classic Views, Compose, a WebView page, unlabeled icon buttons, long lists, dialogs, text fields, a secure window and an injection screen. They cover snapshots, every action and its fallback, the overlays, text editing, the touch watcher, and OCR on fixture screenshots.
```

with

```markdown
These run on `kestrel-api37`, and on the A71 before each release. They use the fixture screens in `:testapp`: classic Views, Compose, a WebView page, unlabeled icon buttons, long lists, dialogs, text fields, a secure window and an injection screen. They cover snapshots, every action and its fallback, the overlays, text editing, the touch watcher, and OCR on fixture screenshots.

The device tests of the instant commands (the package `dev.kestrel.app.commands`) drive each command through the same path as speech: the test sends the text to the Orchestrator as a transcript, with a voice session that needs no speech models, and waits for the command's report. They use the fixture `CommandsActivity` and Android's own apps. They skip themselves unless the instrumentation argument `kestrelCommands=true` is present, which only `scripts\command-test.ps1` passes. That script runs only on the emulator, and puts back what the tests change: the radios, volumes and ringer mode, Quick Settings' tiles, the lock-screen setting, users, contacts, call-log rows, screenshots, and the permissions that the tests grant, except Kestrel's microphone and notification permissions, which stay granted as after `scripts\voice-test.ps1`.
```

- [ ] **Step 12: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin :testapp:assembleDebug`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:app` 307, `:core:commands` 46, `:core:voice` 140, `:core:screen` 47, `:core:privacy` 48, `:core:safety` 2), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests and the test app compile.

- [ ] **Step 13: Check the script's device commands on the emulator**

`command-test.ps1` reads and puts back the device's state with shell commands that no earlier script used. This step runs each reading command once and changes nothing.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; Assert-TestDevice -Serial emulator-5554; foreach ($command in 'settings get global wifi_on', 'settings get global bluetooth_on', 'settings get global mode_ringer', 'cmd lock_settings get-disabled', 'cmd media_session volume --stream 3 --get', 'pm list users', 'content query --uri content://media/external/images/media --projection _id', "content delete --uri content://call_log/calls --where '_id<0' 2>&1", "content delete --uri content://media/external/images/media --where '_id<0' 2>&1", "content delete --uri 'content://com.android.contacts/raw_contacts?caller_is_syncadapter=true' --where account_name=\'kestrel-test\' 2>&1", 'cmd audio || true') { "== $command"; Invoke-SettingsCommand -Serial emulator-5554 -Command $command | Select-Object -First 4 }`
Expected: each command prints its answer without an error: `wifi_on` and `bluetooth_on` print `0`, `1` or `2`; `mode_ringer` prints `2`; `get-disabled` prints `true` or `false`; the volume line reads `[V] volume is N in range [0..M]` (`VolumeCtrl`'s lines start with `[V]`; the helper accepts any mark or none); `pm list users` lists `UserInfo{0:...}`; the image query prints rows or "No result found."; the three deletes, which match nothing, print nothing (`content` writes a refusal, such as "Error while accessing provider", to stderr and still ends with exit code 0, so each delete joins its stderr to its output, as `command-test.ps1` does; any output is a refusal; the emulator has no contacts of the account `kestrel-test`); and `cmd audio || true` (without a subcommand, every version prints its help) lists `set-ringer-mode NORMAL|SILENT|VIBRATE` and `set-volume STREAM_TYPE VOLUME_INDEX` (if they are not among the first 4 lines, run `Invoke-SettingsCommand -Serial emulator-5554 -Command 'cmd audio || true' | Select-String 'set-ringer-mode|set-volume'`). If a command is unknown or answers differently, stop and report it to the controller: the restores of `command-test.ps1` depend on these answers.

- [ ] **Step 14: Run the command device tests**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\command-test.ps1 -Serial emulator-5554`
Expected: `BUILD SUCCESSFUL` and "Command tests: 13 run, 0 failed, 0 errors, 0 skipped." (`TouchCommandsTest` 13), with no warning from the restores at the end. If `theOwnersTouchCancelsWhichOne` fails because no touch reached the watcher, report it to the controller with the failure message; do not change the touch watcher.

- [ ] **Step 15: Run the other device tests of `:app`**

The command tests skip themselves in this run, because it does not pass `kestrelCommands=true`; `ScreenSession` changed for "Which one?", which `OverlayTest` covers.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.notPackage=dev.kestrel.app.voice' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' --no-configuration-cache`
Expected: `BUILD SUCCESSFUL`, and the result files in `app\build\outputs\androidTest-results\connected\debug\` show 129 tests (116 + the 13 of `TouchCommandsTest`), of which the 13 of `TouchCommandsTest` are skipped, and 0 failures. Because `CommandTests.only` is their first rule, the skipped tests do not switch the accessibility services.

- [ ] **Step 16: Run the voice device tests**

`AppGraph` now runs every transcript that is not Kestrel's own request through `InstantCommands`.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 81 run, 0 failed, 0 errors, 0 skipped." (unchanged), and no warning from the restores at the end.

- [ ] **Step 17: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 18: Commit**

```powershell
git add core/screen/src/main/kotlin/dev/kestrel/screen/act/TextEditor.kt core/screen/src/main/kotlin/dev/kestrel/screen/act/SystemControls.kt core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt app/src/main/kotlin/dev/kestrel/app/commands/CommandRunner.kt app/src/main/kotlin/dev/kestrel/app/commands/InstantCommands.kt app/src/main/kotlin/dev/kestrel/app/commands/ScreenCommands.kt app/src/main/kotlin/dev/kestrel/app/commands/ScreenTargets.kt app/src/main/kotlin/dev/kestrel/app/commands/CommandTexts.kt app/src/main/kotlin/dev/kestrel/app/commands/CommandOptions.kt app/src/main/kotlin/dev/kestrel/app/commands/TextCommands.kt app/src/main/kotlin/dev/kestrel/app/commands/PhoneCommands.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/test/kotlin/dev/kestrel/app/commands/ScreenTargetsTest.kt testapp/src/main/kotlin/dev/kestrel/testapp/CommandsActivity.kt testapp/src/main/res/values/ids.xml testapp/src/main/AndroidManifest.xml app/src/androidTest/kotlin/dev/kestrel/app/commands/CommandTests.kt app/src/androidTest/kotlin/dev/kestrel/app/commands/TextSessions.kt app/src/androidTest/kotlin/dev/kestrel/app/commands/TouchCommandsTest.kt scripts/command-test.ps1 scripts/lib/Kestrel.ps1 scripts/tests/CommandTest.Tests.ps1 AGENTS.md docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(app): run tap, long press and bare labels through the executor of the instant commands, with Which one?, its checks and command-test.ps1" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---
### Task 5: Check and switch, expand, collapse and dismiss, sliders, and the test app's notification

**Files:**
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/read/NodeData.kt` (`slider`), `core/screen/src/main/kotlin/dev/kestrel/screen/read/AccessibilityNodeData.kt` (`slider`, `SLIDER_ACTIONS`), `core/screen/src/main/kotlin/dev/kestrel/screen/model/ScreenSnapshot.kt` (`ScreenElement.slider`, `isActionable`), `core/screen/src/main/kotlin/dev/kestrel/screen/read/SnapshotBuilder.kt` (the actionable test, `toElement`)
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/act/ScreenActions.kt` (`adjust`, `expand`, `collapse`, `dismiss`, `swipeAway`, the companion object), `core/screen/src/main/kotlin/dev/kestrel/screen/act/GlobalAction.kt` (`ALL_APPS`, `DISMISS_SHADE`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/commands/ScreenTargets.kt` (`TargetKind.SLIDER`, `candidates`, `decide`, `preferred`), `app/src/main/kotlin/dev/kestrel/app/commands/ScreenCommands.kt` (imports, `run`, `check`, `expandOrCollapse`), `app/src/main/kotlin/dev/kestrel/app/commands/CommandTexts.kt` (`ALREADY_ON`, `ALREADY_OFF`, `ALREADY_EXPANDED`, `ALREADY_COLLAPSED`, `NOT_CHECKABLE`)
- Create: `testapp/src/main/kotlin/dev/kestrel/testapp/NotificationActivity.kt`; Modify: `testapp/src/main/AndroidManifest.xml` (`POST_NOTIFICATIONS`, `NotificationActivity`)
- Test: `core/screen/src/test/kotlin/dev/kestrel/screen/read/FakeNodes.kt`, `core/screen/src/test/kotlin/dev/kestrel/screen/read/SnapshotBuilderTest.kt`, `app/src/test/kotlin/dev/kestrel/app/commands/ScreenTargetsTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/commands/TouchCommandsTest.kt`

**Interfaces:**
- Consumes: Task 2's `Command.Check(target, on)`, `Command.Switch(target, on)`, `Command.Expand(target)`, `Command.Collapse(target)`, `Command.Dismiss(target)`, `Command.Slider(increase, target)`, `Target`, `Outcome`; Task 4's `ScreenCommands.run` (its `screen` and the line `// Tasks 5 to 9 add their commands above this line.`), `ScreenCommands.act(screen, target, kind, conversation, onCell, onElement): CommandResult`, `TargetKind`, `ScreenTargets.candidates`, `ScreenTargets.decide`, `ScreenTargets.chosen`, `Resolution.One`, `CommandTexts.WHICH_ONE`, the test helpers `CommandTests` (`say`, `ask`, `answer`) and `TouchCommandsTest` (its `@Before`, which opens `CommandsActivity`, and `awaitStatus`), and `CommandsActivity` ("Subscribe", the switch "Wi‑Fi", `seek_bar`, the Compose slider, `progress_bar`, the row "Details"); `ScreenActions.tap`, `gesture`, `settled`, `offers`; `Gestures.swipe`; `ScreenServiceRule` (`session`, `shell`, `awaitElement`).
- Produces: `val NodeData.slider: Boolean`; `ScreenElement.slider: Boolean = false`, and `isActionable` with sliders; in `ScreenActions`, `suspend fun adjust(key: String, increase: Boolean): ActionResult`, `suspend fun expand(key: String): ActionResult`, `suspend fun collapse(key: String): ActionResult`, `suspend fun dismiss(key: String): ActionResult`, `suspend fun swipeAway(key: String): ActionResult`; `GlobalAction.ALL_APPS` (plan 3C's "open apps") and `GlobalAction.DISMISS_SHADE`; `TargetKind.SLIDER`; `CommandTexts.ALREADY_ON`, `ALREADY_OFF`, `ALREADY_EXPANDED`, `ALREADY_COLLAPSED` and `NOT_CHECKABLE`; the test app's `NotificationActivity` (title "Fixture reminder", the extra `cancel`).

Design D7 and D6. "Check", "uncheck", "switch on" and "switch off" click a checkable element only when its checked state differs from the one asked for; otherwise the bar says "It's already on." or "It's already off.". Without a name, the only checkable element, or the focused one, is the target; with several, Kestrel asks "Which one?". A label and "Which one?" choose only among the elements that can do the command, but a number names any element with that number: so a number that names an element that does not check makes the bar say "It isn't a check box or a switch." (a settings row with its switch inside it would otherwise be tapped, which toggles the switch whatever its state). "Expand" and "collapse" take the rows that offer either action, so that a row that already is as asked, named by its name, by its number or as the only row, gets nothing done, and the bar says "It's already expanded." or "It's already collapsed." instead of "I can't find it"; two rows with the same name, one open and one closed, then get "Which one?". Without a name, "expand" prefers the rows that can expand and "collapse" the ones that can collapse, so that "collapse" in the shade collapses the one expanded notification; the only row that already is as asked is still the target, so that the bar says so. "Expand" and "collapse" use the element's own action and tap it when it has none or refuses, except in SystemUI, where a tap would open a notification's app; "dismiss" uses the dismiss action, and only in SystemUI's notification shade does a notification without one get a sideways swipe (`swipeAway`), because spec 9.3 forbids Kestrel's own sideways swipes elsewhere. Kestrel takes a SystemUI element for a notification row when it offers expand or collapse: a View reports a class that Android knows, such as `FrameLayout`, so the class cannot tell a row, and a Quick Settings tile or a button of the power menu offers neither. SystemUI's rows offer the dismiss action, so a device test calls `swipeAway` directly to check the fallback. A slider is an enabled node with a range that offers the set-progress, scroll-forward or scroll-backward action; a determinate progress bar has a range but none of these, so it is no slider. A slider counts as actionable, so that it gets a number and a place in "Which one?", and the snapshot keeps it also without a name. "Increment" and "decrement" move it one step: with its scroll action, which moves it by the app's own step (a twentieth of a View `SeekBar`'s range), or else with the set-progress action by a twentieth of its range, and by at least one unit when the range holds whole numbers; an element without a range, which a number can name, is refused. The test app posts a notification with a long text and an action for the shade's commands; `command-test.ps1` (Task 4) already takes back the test app's notification permission and stops the test app, which removes its notification. `GlobalAction.ALL_APPS` and `DISMISS_SHADE` come here rather than in Task 9, because this task's tests close the shade with `DISMISS_SHADE`.

- [ ] **Step 1: Write the failing tests**

In `core/screen/src/test/kotlin/dev/kestrel/screen/read/FakeNodes.kt`, replace

```kotlin
    override val dataSensitive: Boolean = false,
    override val actions: Set<ElementAction> = emptySet(),
```

with

```kotlin
    override val dataSensitive: Boolean = false,
    override val slider: Boolean = false,
    override val actions: Set<ElementAction> = emptySet(),
```

In `core/screen/src/test/kotlin/dev/kestrel/screen/read/SnapshotBuilderTest.kt`, replace

```kotlin
    @Test
    fun passwordTextNeverAppearsAndTheHintNamesTheField() {
```

with

```kotlin
    @Test
    fun aSliderIsActionableWithoutANameAndAProgressBarIsNot() {
        val slider = FakeNode(className = "android.widget.SeekBar", slider = true)
        // A determinate progress bar has a range too, but no action that moves it.
        val progress = FakeNode(className = "android.widget.ProgressBar", stateDescription = "40%")
        val root = FakeNode(className = "android.widget.LinearLayout", children = listOf(slider, progress))

        val elements = build(FakeWindow(root)).snapshot.elements

        assertEquals(listOf("android.widget.SeekBar", "android.widget.ProgressBar"), elements.map { it.className })
        assertEquals(listOf(true, false), elements.map { it.slider })
        assertEquals(listOf(true, false), elements.map { it.isActionable })
        assertFalse(elements.first().copy(enabled = false).isActionable)
    }

    @Test
    fun passwordTextNeverAppearsAndTheHintNamesTheField() {
```

In `app/src/test/kotlin/dev/kestrel/app/commands/ScreenTargetsTest.kt`, replace

```kotlin
    @Test
    fun aChosenElementPrintsNoLabel() {
```

with

```kotlin
    @Test
    fun theSlidersAreTheCandidatesOfIncrementAndDecrementAndTheFocusedOneNeedsNoName() {
        val bar = "android.widget.SeekBar"
        val seekBar = element("seek", null, IntRect(0, 100, 1080, 200), clickable = false, className = bar).copy(slider = true)
        val progress = element("progress", null, IntRect(0, 300, 1080, 400), clickable = false, className = "android.widget.ProgressBar")
        val speed = element("speed", "Speed slider", IntRect(0, 500, 1080, 600), clickable = false, className = bar)
            .copy(slider = true, focused = true)
        val view = ScreenView(ScreenSnapshot(0, listOf(app), listOf(seekBar, progress, speed)), badges = emptyList(), grid = null)

        assertEquals(listOf("seek", "speed"), ScreenTargets.candidates(view.snapshot, TargetKind.SLIDER).map { it.key })
        assertEquals(Resolution.One(ScreenTargets.chosen(speed)), decide(view, null, TargetKind.SLIDER))
    }

    @Test
    fun aChosenElementPrintsNoLabel() {
```

Replace

```kotlin
        assertEquals(Resolution.One(ScreenTargets.chosen(focusedNews)), decide(focused, null, TargetKind.CHECK))
    }
```

with

```kotlin
        assertEquals(Resolution.One(ScreenTargets.chosen(focusedNews)), decide(focused, null, TargetKind.CHECK))
        // Without a name, "collapse" prefers the rows that can collapse, and "expand" the ones that can expand. The new row
        // lies above the fake keyboard, which covers the screen from y = 1600 down.
        val more = element("more", "More", IntRect(600, 1450, 1000, 1550), clickable = false).copy(actions = setOf(ElementAction.COLLAPSE))
        val rows = plain.copy(snapshot = ScreenSnapshot(0, listOf(keyboard, app), elements + more))
        assertEquals(Resolution.One(ScreenTargets.chosen(more)), decide(rows, null, TargetKind.COLLAPSE))
        assertEquals(one("details"), decide(rows, null, TargetKind.EXPAND))
        // The only row already is as asked: it is still the target, so that the bar can say so.
        assertEquals(one("details"), decide(plain, null, TargetKind.COLLAPSE))
    }
```

In `app/src/androidTest/kotlin/dev/kestrel/app/commands/TouchCommandsTest.kt`, replace

```kotlin
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.TestFinger
import dev.kestrel.commands.Outcome
import kotlinx.coroutines.runBlocking
```

with

```kotlin
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.app.screen.TestFinger
import dev.kestrel.commands.Outcome
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.GlobalAction
import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.ScreenElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
```

Replace

```kotlin
            assertEquals(Outcome.CANCELLED, CommandTests.next(5_000).outcome)
        }
    }
}
```

with

```kotlin
            assertEquals(Outcome.CANCELLED, CommandTests.next(5_000).outcome)
        }
    }

    @Test
    fun checkAndSwitchClickOnlyWhenTheStateDiffers() = runBlocking<Unit> {
        assertEquals(Outcome.DONE, CommandTests.say("check Subscribe").outcome)
        awaitStatus("Checked Subscribe")
        assertEquals(Outcome.DONE, CommandTests.say("switch on Subscribe").outcome)
        // Already on: Kestrel clicks nothing and says so on the bar.
        awaitBar(CommandTexts.ALREADY_ON)
        awaitStatus("Checked Subscribe")
        assertEquals(Outcome.DONE, CommandTests.say("uncheck Subscribe").outcome)
        awaitStatus("Unchecked Subscribe")
        assertEquals(Outcome.DONE, CommandTests.say("check Wi-Fi").outcome)
        awaitStatus(WIFI_ON)

        // Without a name, with two checkable elements on the screen, Kestrel asks which one.
        assertEquals(CommandTexts.WHICH_ONE, CommandTests.ask("switch off"))
        val wifi = screen.awaitElement { it.label == WIFI && it.checkable }
        assertEquals(Outcome.DONE, CommandTests.answer("${badge(wifi.key)}").outcome)
        awaitStatus(WIFI_OFF)

        // A number can name an element that does not check: Kestrel refuses it and clicks nothing.
        screen.session.showNumbers()
        val save = screen.awaitElement { it.label == "Save" }
        assertEquals(Outcome.FAILED, CommandTests.say("check ${badge(save.key)}").outcome)
        awaitBar(CommandTexts.NOT_CHECKABLE)
        awaitStatus(WIFI_OFF)
    }

    @Test
    fun incrementAndDecrementMoveASeekBarAndAComposeSliderOneStep() = runBlocking<Unit> {
        val seekBar = screen.awaitElement { it.viewId == "$FIXTURES:id/seek_bar" }
        val speed = screen.awaitElement { it.slider && it.packageName == FIXTURES && it.key != seekBar.key }
        screen.session.showNumbers()

        assertEquals(Outcome.DONE, CommandTests.say("increment ${badge(seekBar.key)}").outcome)
        awaitStatus("Seek bar 55")
        assertEquals(Outcome.DONE, CommandTests.say("decrement ${badge(seekBar.key)}").outcome)
        awaitStatus("Seek bar 50")
        assertEquals(Outcome.DONE, CommandTests.say("increment ${badge(speed.key)}").outcome)
        awaitStatus("Slider 0.55")
    }

    @Test
    fun aSliderGetsANumberAndAProgressBarDoesNot() = runBlocking<Unit> {
        val seekBar = screen.awaitElement { it.viewId == "$FIXTURES:id/seek_bar" }

        screen.session.showNumbers()

        val numbered = screen.session.numberBadges.map { it.key }
        assertTrue("The seek bar has no number", seekBar.key in numbered)
        val progress = screen.session.snapshots.current().snapshot.elements.filter { it.viewId == "$FIXTURES:id/progress_bar" }
        assertTrue("The progress bar has a number", progress.none { it.key in numbered })
    }

    @Test
    fun expandAndCollapseUseTheRowsOwnActions() = runBlocking<Unit> {
        assertEquals(Outcome.DONE, CommandTests.say("expand Details").outcome)
        awaitStatus("Expanded Details")
        // Expanded, the row offers only collapse: Kestrel clicks nothing and says so on the bar.
        assertEquals(Outcome.DONE, CommandTests.say("expand Details").outcome)
        awaitBar(CommandTexts.ALREADY_EXPANDED)
        awaitStatus("Expanded Details")
        assertEquals(Outcome.DONE, CommandTests.say("collapse Details").outcome)
        awaitStatus("Collapsed Details")
    }

    @Test
    fun aNotificationInTheShadeExpandsCollapsesAndGoesByItsNameAndByItsNumber() = runBlocking<Unit> {
        postNotification()
        try {
            screen.session.actions.global(GlobalAction.NOTIFICATIONS)
            // The shade may show the newest notification expanded already.
            val wasExpanded = ElementAction.COLLAPSE in notificationRow().actions
            val first = if (wasExpanded) "collapse" else "expand"
            val second = if (wasExpanded) "expand" else "collapse"

            // By the name that the labels overlay would show for it.
            assertEquals(Outcome.DONE, CommandTests.say("$first ${checkNotNull(notificationRow().label)}").outcome)
            notificationRow { (ElementAction.COLLAPSE in it.actions) != wasExpanded }
            // By its number.
            screen.session.showNumbers()
            assertEquals(Outcome.DONE, CommandTests.say("$second ${badge(notificationRow().key)}").outcome)
            notificationRow { (ElementAction.COLLAPSE in it.actions) == wasExpanded }
            // Said again, it changes nothing: the row already is as the owner asks.
            assertEquals(Outcome.DONE, CommandTests.say("$second ${badge(notificationRow().key)}").outcome)
            awaitBar(if (wasExpanded) CommandTexts.ALREADY_EXPANDED else CommandTexts.ALREADY_COLLAPSED)
            // By its title, which is what the owner reads in the shade.
            assertEquals(Outcome.DONE, CommandTests.say("$first $NOTIFICATION_TITLE").outcome)
            notificationRow { (ElementAction.COLLAPSE in it.actions) != wasExpanded }

            // Dismiss by its name,
            assertEquals(Outcome.DONE, CommandTests.say("dismiss ${checkNotNull(notificationRow().label)}").outcome)
            withTimeout(5_000) { while (notificationPosted()) delay(100) }
            // and, posted again, by its number.
            postNotification()
            assertEquals(Outcome.DONE, CommandTests.say("dismiss ${badge(notificationRow().key)}").outcome)
            withTimeout(5_000) { while (notificationPosted()) delay(100) }
        } finally {
            removeNotification()
        }
    }

    @Test
    fun swipeAwayDismissesANotificationInTheShade() = runBlocking<Unit> {
        postNotification()
        try {
            screen.session.actions.global(GlobalAction.NOTIFICATIONS)

            // The fallback of "dismiss" for a notification without a dismiss action; the fixture's offers one, so the test
            // calls the fallback itself.
            assertTrue(screen.session.actions.swipeAway(notificationRow().key) is ActionResult.Done)

            withTimeout(5_000) { while (notificationPosted()) delay(100) }
        } finally {
            removeNotification()
        }
    }

    private suspend fun awaitBar(text: String) = withTimeout(5_000) { while (screen.session.feedbackText != text) delay(50) }

    /** The number on the element with [key], once the numbers have followed the screen. */
    private suspend fun badge(key: String): Int = withTimeout(5_000) {
        var badge = screen.session.numberBadges.firstOrNull { it.key == key }
        while (badge == null) {
            delay(50)
            badge = screen.session.numberBadges.firstOrNull { it.key == key }
        }
        badge.number
    }

    /** Posts the test app's notification and waits until Android lists it. The tests grant, command-test.ps1 revokes. */
    private suspend fun postNotification() {
        screen.shell("pm grant $FIXTURES android.permission.POST_NOTIFICATIONS")
        screen.shell("am start -W -n $FIXTURES/.NotificationActivity")
        withTimeout(5_000) { while (!notificationPosted()) delay(100) }
    }

    /** Removes the test app's notification, if it is still there, and closes the shade. */
    private suspend fun removeNotification() {
        screen.shell("am start -W -n $FIXTURES/.NotificationActivity --ez cancel true")
        screen.session.actions.global(GlobalAction.DISMISS_SHADE)
    }

    /** Whether Android lists a notification of the test app. The answer holds only keys, never a notification's text. */
    private fun notificationPosted(): Boolean = screen.shell("cmd notification list").contains("|$FIXTURES|")

    /**
     * The test app's notification in the shade: the nearest element above its title that offers to be dismissed, once it
     * matches [accept].
     */
    private suspend fun notificationRow(accept: (ScreenElement) -> Boolean = { true }): ScreenElement = withTimeout(5_000) {
        var row: ScreenElement? = null
        while (row == null) {
            val elements = screen.session.snapshots.current().snapshot.elements
            val title = elements.firstOrNull { it.packageName == SYSTEM_UI && it.label == NOTIFICATION_TITLE }
            row = generateSequence(title?.parent) { elements[it].parent }
                .map { elements[it] }
                .firstOrNull { ElementAction.DISMISS in it.actions }
                ?.takeIf(accept)
            if (row == null) delay(100)
        }
        row
    }

    private companion object {
        const val WIFI = "Wi\u2011Fi"
        const val WIFI_ON = "Wi\u2011Fi on"
        const val WIFI_OFF = "Wi\u2011Fi off"
        const val SYSTEM_UI = "com.android.systemui"

        /** NotificationActivity.TITLE in the test app. */
        const val NOTIFICATION_TITLE = "Fixture reminder"
    }
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 --continue :core:screen:testDebugUnitTest --tests dev.kestrel.screen.read.SnapshotBuilderTest :app:testDebugUnitTest --tests dev.kestrel.app.commands.ScreenTargetsTest :app:compileDebugAndroidTestKotlin`
Expected: `BUILD FAILED`, because the test sources do not compile: "'slider' overrides nothing" in `:core:screen`, "No parameter with name 'slider' found" and "Unresolved reference 'SLIDER'" in the JVM tests of `:app`, and "Unresolved reference 'ALREADY_ON'", "Unresolved reference 'NOT_CHECKABLE'", "Unresolved reference 'ALREADY_EXPANDED'", "Unresolved reference 'swipeAway'" and "Unresolved reference 'DISMISS_SHADE'" in the device tests of `:app`.

- [ ] **Step 3: Read sliders into the snapshot**

In `core/screen/src/main/kotlin/dev/kestrel/screen/read/NodeData.kt`, replace

```kotlin
    val dataSensitive: Boolean
    val actions: Set<ElementAction>
```

with

```kotlin
    val dataSensitive: Boolean

    /**
     * Whether the node is a slider: an enabled node with a range that offers the set-progress, scroll-forward or
     * scroll-backward action (design spec, Appendix A, "Increment" and "Decrement"). A determinate progress bar has a
     * range but none of these actions, so it is no slider.
     */
    val slider: Boolean
    val actions: Set<ElementAction>
```

In `core/screen/src/main/kotlin/dev/kestrel/screen/read/AccessibilityNodeData.kt`, replace

```kotlin
    override val dataSensitive: Boolean get() = Build.VERSION.SDK_INT >= 34 && info.isAccessibilityDataSensitive
```

with

```kotlin
    override val dataSensitive: Boolean get() = Build.VERSION.SDK_INT >= 34 && info.isAccessibilityDataSensitive
    override val slider: Boolean
        get() = info.isEnabled && info.rangeInfo != null && info.actionList.any { it.id in SLIDER_ACTIONS }
```

Replace

```kotlin
            AccessibilityAction.ACTION_DISMISS.id to ElementAction.DISMISS,
        )
    }
```

with

```kotlin
            AccessibilityAction.ACTION_DISMISS.id to ElementAction.DISMISS,
        )

        val SLIDER_ACTIONS = setOf(
            AccessibilityAction.ACTION_SET_PROGRESS.id,
            AccessibilityAction.ACTION_SCROLL_FORWARD.id,
            AccessibilityAction.ACTION_SCROLL_BACKWARD.id,
        )
    }
```

In `core/screen/src/main/kotlin/dev/kestrel/screen/model/ScreenSnapshot.kt`, replace

```kotlin
    val dataSensitive: Boolean = false,
) {
    /** Whether the owner can act on the element directly. Numbers go on these elements (design spec, section 9.4). */
    val isActionable: Boolean get() = enabled && (clickable || longClickable || editable || checkable)
}
```

with

```kotlin
    val dataSensitive: Boolean = false,
    /** A slider, which "increment" and "decrement" move (see `NodeData.slider`). */
    val slider: Boolean = false,
) {
    /** Whether the owner can act on the element directly. Numbers go on these elements (design spec, section 9.4). */
    val isActionable: Boolean get() = enabled && (clickable || longClickable || editable || checkable || slider)
}
```

In `core/screen/src/main/kotlin/dev/kestrel/screen/read/SnapshotBuilder.kt`, replace

```kotlin
        val actionable = node.clickable || node.longClickable || node.editable || node.checkable
```

with

```kotlin
        // A slider counts as actionable, so that it gets a number, also without a name of its own.
        val actionable = node.clickable || node.longClickable || node.editable || node.checkable || node.slider
```

Replace

```kotlin
            dataSensitive = node.dataSensitive,
        )
    }
```

with

```kotlin
            dataSensitive = node.dataSensitive,
            slider = node.slider,
        )
    }
```

- [ ] **Step 4: Add the slider, expand, collapse and dismiss actions, and the two global actions**

In `core/screen/src/main/kotlin/dev/kestrel/screen/act/ScreenActions.kt`, replace

```kotlin
import dev.kestrel.screen.model.IntRect
```

with

```kotlin
import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.IntRect
```

Replace

```kotlin
    /** How many times Android's magnification enlarges the screen; 1 when it does not. */
```

with

```kotlin
    /**
     * Moves the slider with [key] one step up ([increase]) or down (design spec, Appendix A): with its scroll-forward or
     * scroll-backward action, which moves it by the app's own step, or else with the set-progress action by a twentieth
     * of its range.
     */
    suspend fun adjust(key: String, increase: Boolean): ActionResult {
        val node = snapshots.current().node(key) ?: return NOT_ON_SCREEN
        if (!node.isEnabled) return DISABLED
        // A number can name an element that is no slider, such as a list, whose scroll action would move it instead.
        val range = node.rangeInfo ?: return ActionResult.Failed("The element is not a slider")
        val step = if (increase) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (node.offers(step) && node.performAction(step)) return settled(ActionMethod.ACTION)
        if (node.offers(SET_PROGRESS)) {
            // A range of whole numbers that has fewer than 20 steps would round a twentieth back to the same value.
            val change = ((range.max - range.min) / SLIDER_STEPS).let {
                if (range.type == AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT) maxOf(1f, it) else it
            }
            val value = (range.current + if (increase) change else -change).coerceIn(range.min, range.max)
            if (value != range.current) {
                val arguments = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) }
                return if (node.performAction(SET_PROGRESS, arguments)) {
                    settled(ActionMethod.ACTION)
                } else {
                    ActionResult.Failed("The slider refused the change")
                }
            }
        }
        // Apps offer a step's action only while the slider can move that way.
        return ActionResult.Failed(if (increase) "The slider is at its highest" else "The slider is at its lowest")
    }

    /**
     * Expands the element with [key] with its expand action, or else taps it, as most rows that open and close allow. In
     * SystemUI it never taps, because a tap on a notification opens its app.
     */
    suspend fun expand(key: String): ActionResult = expandOrCollapse(key, AccessibilityNodeInfo.ACTION_EXPAND)

    /** Collapses the element with [key], in the same way as [expand]. */
    suspend fun collapse(key: String): ActionResult = expandOrCollapse(key, AccessibilityNodeInfo.ACTION_COLLAPSE)

    private suspend fun expandOrCollapse(key: String, action: Int): ActionResult {
        val live = snapshots.current()
        val element = live.snapshot.element(key) ?: return NOT_ON_SCREEN
        val node = live.node(key) ?: return NOT_ON_SCREEN
        if (!node.isEnabled) return DISABLED
        if (node.offers(action) && node.performAction(action)) return settled(ActionMethod.ACTION)
        val expanding = action == AccessibilityNodeInfo.ACTION_EXPAND
        // An element that offers only the other action is already as the owner wants it: a tap would toggle it back.
        val other = if (expanding) AccessibilityNodeInfo.ACTION_COLLAPSE else AccessibilityNodeInfo.ACTION_EXPAND
        if (node.offers(other)) return ActionResult.Failed(if (expanding) "It's already expanded" else "It's already collapsed")
        if (element.packageName == SYSTEM_UI) return ActionResult.Failed(if (expanding) "It can't expand" else "It can't collapse")
        return tap(key)
    }

    /**
     * Dismisses the element with [key] with its dismiss action (design spec, Appendix A). In SystemUI's notification shade
     * a notification that offers none is swiped away ([swipeAway]). Anywhere else Kestrel never swipes sideways on its
     * own, because a sideways swipe across a row can archive a message or start a call (section 9.3).
     */
    suspend fun dismiss(key: String): ActionResult {
        val live = snapshots.current()
        val element = live.snapshot.element(key) ?: return NOT_ON_SCREEN
        val node = live.node(key) ?: return NOT_ON_SCREEN
        if (!node.isEnabled) return DISABLED
        if (node.offers(AccessibilityNodeInfo.ACTION_DISMISS) && node.performAction(AccessibilityNodeInfo.ACTION_DISMISS)) {
            return settled(ActionMethod.ACTION)
        }
        return if (element.notificationRow) swipeAway(key) else NOT_DISMISSIBLE
    }

    /**
     * Swipes the notification with [key] in SystemUI's notification shade sideways across 80% of its width, as a finger
     * dismisses it. It swipes nothing else (design spec, section 9.3).
     */
    suspend fun swipeAway(key: String): ActionResult {
        val element = snapshots.current().snapshot.element(key) ?: return NOT_ON_SCREEN
        if (!element.notificationRow) return NOT_DISMISSIBLE
        val bounds = element.bounds
        val from = ScreenPoint(bounds.left + bounds.width / 10, bounds.centerY)
        val to = ScreenPoint(bounds.right - 1 - bounds.width / 10, bounds.centerY)
        return gesture(listOf(from, to)) { arrayOf(Gestures.swipe(from, to)) }
    }

    /**
     * Whether the element is a notification row in SystemUI's shade: one that offers expand or collapse. A View reports
     * a class that Android knows, such as `FrameLayout`, so the class cannot tell a row, and a Quick Settings tile, the
     * media player or a button of the power menu offers neither action.
     */
    private val ScreenElement.notificationRow: Boolean
        get() = packageName == SYSTEM_UI && (ElementAction.EXPAND in actions || ElementAction.COLLAPSE in actions)

    /** How many times Android's magnification enlarges the screen; 1 when it does not. */
```

Replace

```kotlin
        val COVERED = ActionResult.Failed("Other windows cover the element")
```

with

```kotlin
        val COVERED = ActionResult.Failed("Other windows cover the element")
        val NOT_DISMISSIBLE = ActionResult.Failed("I can't dismiss it.")
        val SET_PROGRESS = AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id
        const val SLIDER_STEPS = 20f
        const val SYSTEM_UI = "com.android.systemui"
```

In `core/screen/src/main/kotlin/dev/kestrel/screen/act/GlobalAction.kt`, replace

```kotlin
    /** Plays or pauses media, like the button on a headset. During a call, the same key answers or ends the call. */
    PLAY_PAUSE(AccessibilityService.GLOBAL_ACTION_KEYCODE_HEADSETHOOK),
}
```

with

```kotlin
    /** Plays or pauses media, like the button on a headset. During a call, the same key answers or ends the call. */
    PLAY_PAUSE(AccessibilityService.GLOBAL_ACTION_KEYCODE_HEADSETHOOK),

    /** The launcher's list of all apps, where the launcher offers it (design spec, Appendix A, "Open apps"). */
    ALL_APPS(AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS),

    /** Closes the notification shade and Quick Settings. */
    DISMISS_SHADE(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE),
}
```

- [ ] **Step 5: Run the tests of `:core:screen` to verify that they pass**

Run: `scripts\gradle.ps1 :core:screen:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, with the count of `:core:screen` before this task + 1 (`SnapshotBuilderTest` 10).

- [ ] **Step 6: Run the new commands in the screen part**

In `app/src/main/kotlin/dev/kestrel/app/commands/ScreenTargets.kt`, replace

```kotlin
    SCROLL,
    EDIT,
}
```

with

```kotlin
    SCROLL,
    EDIT,

    /** "Increment" and "decrement": the sliders. */
    SLIDER,
}
```

Replace

```kotlin
                TargetKind.EDIT -> shown.filter { it.enabled && it.editable }
```

with

```kotlin
                TargetKind.EDIT -> shown.filter { it.enabled && it.editable }
                TargetKind.SLIDER -> shown.filter { it.enabled && it.slider }
```

Replace

```kotlin
                TargetKind.EXPAND -> shown.filter { it.enabled && ElementAction.EXPAND in it.actions }
                TargetKind.COLLAPSE -> shown.filter { it.enabled && ElementAction.COLLAPSE in it.actions }
```

with

```kotlin
                // Both commands take a row in either state, so that one that is already open or closed gets "It's already
                // expanded." or "It's already collapsed." rather than "I can't find it".
                TargetKind.EXPAND, TargetKind.COLLAPSE ->
                    shown.filter { it.enabled && (ElementAction.EXPAND in it.actions || ElementAction.COLLAPSE in it.actions) }
```

Replace

```kotlin
                null -> only(candidates)
```

with

```kotlin
                null -> only(preferred(candidates, kind))
```

Replace

```kotlin
        // Without a target: the only candidate, else the focused one.
```

with

```kotlin
        // Without a name, "expand" takes the rows that can expand and "collapse" the ones that can collapse; a row that
        // already is as asked is the target only when no row can, so that the bar can say so.
        private fun preferred(candidates: List<ScreenElement>, kind: TargetKind): List<ScreenElement> {
            val action = when (kind) {
                TargetKind.EXPAND -> ElementAction.EXPAND
                TargetKind.COLLAPSE -> ElementAction.COLLAPSE
                else -> return candidates
            }
            return candidates.filter { action in it.actions }.ifEmpty { candidates }
        }

        // Without a target: the only candidate, else the focused one.
```

In `app/src/main/kotlin/dev/kestrel/app/commands/CommandTexts.kt`, replace

```kotlin
    fun noNumber(number: Int) = "There's no number $number on the screen."
```

with

```kotlin
    fun noNumber(number: Int) = "There's no number $number on the screen."

    const val ALREADY_ON = "It's already on."
    const val ALREADY_OFF = "It's already off."
    const val ALREADY_EXPANDED = "It's already expanded."
    const val ALREADY_COLLAPSED = "It's already collapsed."
    const val NOT_CHECKABLE = "It isn't a check box or a switch."
```

In `app/src/main/kotlin/dev/kestrel/app/commands/ScreenCommands.kt`, replace

```kotlin
import dev.kestrel.screen.act.ActionResult
```

with

```kotlin
import dev.kestrel.screen.act.ActionMethod
import dev.kestrel.screen.act.ActionResult
```

Replace

```kotlin
import dev.kestrel.screen.model.IntRect
```

with

```kotlin
import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.IntRect
```

Replace

```kotlin
            // Tasks 5 to 9 add their commands above this line.
```

with

```kotlin
            is Command.Check -> check(screen, command.target, command.on, conversation)
            is Command.Switch -> check(screen, command.target, command.on, conversation)
            is Command.Expand -> expandOrCollapse(screen, command.target, expand = true, conversation)
            is Command.Collapse -> expandOrCollapse(screen, command.target, expand = false, conversation)
            is Command.Dismiss -> act(screen, command.target, TargetKind.DISMISS, conversation) {
                screen.actions.dismiss(it.key)
            }
            is Command.Slider -> act(screen, command.target, TargetKind.SLIDER, conversation) {
                screen.actions.adjust(it.key, command.increase)
            }
            // Tasks 5 to 9 add their commands above this line.
```

Replace

```kotlin
    private suspend fun tapAt(screen: ScreenSession, bounds: IntRect): ActionResult = screen.actions.tapAt(bounds.centerX, bounds.centerY)
```

with

```kotlin
    /**
     * "Check", "uncheck", "switch on" and "switch off" (design spec, Appendix A): a click only when the element's checked
     * state differs from the one asked for; otherwise nothing is clicked, and the bar says that it is already on or off.
     */
    private suspend fun check(screen: ScreenSession, target: Target?, on: Boolean, conversation: CommandConversation): CommandResult {
        var already = false
        val result = act(screen, target, TargetKind.CHECK, conversation) { element ->
            when {
                // A number can name an element that does not check, such as a settings row with its switch inside it. A
                // tap would toggle that switch whatever its state.
                !element.checkable -> ActionResult.Failed(CommandTexts.NOT_CHECKABLE)
                element.checked == on -> {
                    already = true
                    // Nothing to click: the element is as the owner wants it.
                    ActionResult.Done(ActionMethod.ACTION)
                }
                else -> screen.actions.tap(element.key)
            }
        }
        if (!already || result.outcome != Outcome.DONE) return result
        return result.copy(show = if (on) CommandTexts.ALREADY_ON else CommandTexts.ALREADY_OFF)
    }

    /**
     * "Expand" and "collapse". The target may offer only the other action, whether the owner names it, gives its number or
     * names none: it already is as the owner asks, so nothing is done, and the bar says so; a tap would toggle it back.
     */
    private suspend fun expandOrCollapse(
        screen: ScreenSession,
        target: Target?,
        expand: Boolean,
        conversation: CommandConversation,
    ): CommandResult {
        var already = false
        val (wanted, other) = if (expand) ElementAction.EXPAND to ElementAction.COLLAPSE else ElementAction.COLLAPSE to ElementAction.EXPAND
        val result = act(screen, target, if (expand) TargetKind.EXPAND else TargetKind.COLLAPSE, conversation) { element ->
            when {
                wanted !in element.actions && other in element.actions -> {
                    already = true
                    ActionResult.Done(ActionMethod.ACTION)
                }
                expand -> screen.actions.expand(element.key)
                else -> screen.actions.collapse(element.key)
            }
        }
        if (!already || result.outcome != Outcome.DONE) return result
        return result.copy(show = if (expand) CommandTexts.ALREADY_EXPANDED else CommandTexts.ALREADY_COLLAPSED)
    }

    private suspend fun tapAt(screen: ScreenSession, bounds: IntRect): ActionResult = screen.actions.tapAt(bounds.centerX, bounds.centerY)
```

- [ ] **Step 7: Add the test app's notification**

Create `testapp/src/main/kotlin/dev/kestrel/testapp/NotificationActivity.kt`:

```kotlin
package dev.kestrel.testapp

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle

/**
 * Posts the fixture notification and closes at once, for "expand", "collapse" and "dismiss" in the notification shade
 * (design spec, Appendix A): a notification with a long text, which the shade can expand, and an action. With the extra
 * `cancel` it removes the notification instead. The tests grant the test app the notification permission.
 */
class NotificationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val notifications = getSystemService(NotificationManager::class.java)
        if (intent.getBooleanExtra(EXTRA_CANCEL, false)) {
            notifications.cancel(ID)
        } else {
            // The default importance puts the notification into the shade without a heads-up.
            val channel = NotificationChannel(CHANNEL, "Fixtures", NotificationManager.IMPORTANCE_DEFAULT)
            notifications.createNotificationChannel(channel)
            val open = PendingIntent.getActivity(this, 0, Intent(this, CommandsActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val icon = Icon.createWithResource(this, android.R.drawable.ic_menu_send)
            notifications.notify(
                ID,
                Notification.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(TITLE)
                    .setContentText(TEXT)
                    .setStyle(Notification.BigTextStyle().bigText(LONG_TEXT))
                    .setContentIntent(open)
                    .addAction(Notification.Action.Builder(icon, "Mark done", open).build())
                    .build(),
            )
        }
        finish()
    }

    companion object {
        const val EXTRA_CANCEL = "cancel"
        const val TITLE = "Fixture reminder"
        private const val ID = 1
        private const val CHANNEL = "fixtures"
        private const val TEXT = "The first line of a long text."
        private val LONG_TEXT = (1..6).joinToString("\n") { "Line $it of a long text, which the expanded notification shows." }
    }
}
```

In `testapp/src/main/AndroidManifest.xml`, replace

```xml
    <!-- Lets the hook probe see Kestrel, so that only Kestrel's permission check can refuse it. -->
```

with

```xml
    <!-- For NotificationActivity's notification: the command tests grant it, and command-test.ps1 takes it back. -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <!-- Lets the hook probe see Kestrel, so that only Kestrel's permission check can refuse it. -->
```

Replace

```xml
        <activity
            android:name=".CommandsActivity"
            android:exported="true" />
```

with

```xml
        <activity
            android:name=".CommandsActivity"
            android:exported="true" />
        <!-- Shows nothing: it posts or removes the notification and closes. -->
        <activity
            android:name=".NotificationActivity"
            android:exported="true"
            android:theme="@android:style/Theme.NoDisplay" />
```

- [ ] **Step 8: Run the JVM tests of `:app` to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.commands.ScreenTargetsTest`
Expected: `BUILD SUCCESSFUL`, with 9 tests in `ScreenTargetsTest` (8 and `theSlidersAreTheCandidatesOfIncrementAndDecrementAndTheFocusedOneNeedsNoName`).

- [ ] **Step 9: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin :testapp:assembleDebug`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:screen` 48, `:app` 308, `:core:commands` 46, `:core:voice` 140), lint in every Android module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests and the test app compile.

- [ ] **Step 10: Run the command device tests**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\command-test.ps1 -Serial emulator-5554`
Expected: `BUILD SUCCESSFUL` and "Command tests: 19 run, 0 failed, 0 errors, 0 skipped." (`TouchCommandsTest` 13 + 6), with no warning from the restores at the end. If `aNotificationInTheShadeExpandsCollapsesAndGoesByItsNameAndByItsNumber` or `swipeAwayDismissesANotificationInTheShade` fails in one of these ways, Android 17's shade differs from what this plan expects: `notificationRow` times out (the shade names its rows differently), `checkNotNull` fails with "Required value was null." (the row has no label of its own), `postNotification` times out (`cmd notification list` prints another format), the step "by its title" ends `FAILED` (the row's joined name does not start with the title, so the title alone does not reach the matcher), or the row offers neither expand nor collapse (so `swipeAway` refuses it). Report it to the controller with the failure message and the failing line, and do not change the tests or the code.

- [ ] **Step 11: Run the screen device tests**

The snapshot now reads sliders, and a slider is actionable, which the numbers, the labels and the snapshots of the screen tests use.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.package=dev.kestrel.app.screen' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' --no-configuration-cache`
Expected: `BUILD SUCCESSFUL`, and the result files in `app\build\outputs\androidTest-results\connected\debug\` show the 63 tests of the package `dev.kestrel.app.screen`, as before this task, and 0 failures.

- [ ] **Step 12: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 13: Commit**

```powershell
git add core/screen/src/main/kotlin/dev/kestrel/screen/read/NodeData.kt core/screen/src/main/kotlin/dev/kestrel/screen/read/AccessibilityNodeData.kt core/screen/src/main/kotlin/dev/kestrel/screen/model/ScreenSnapshot.kt core/screen/src/main/kotlin/dev/kestrel/screen/read/SnapshotBuilder.kt core/screen/src/main/kotlin/dev/kestrel/screen/act/ScreenActions.kt core/screen/src/main/kotlin/dev/kestrel/screen/act/GlobalAction.kt core/screen/src/test/kotlin/dev/kestrel/screen/read/FakeNodes.kt core/screen/src/test/kotlin/dev/kestrel/screen/read/SnapshotBuilderTest.kt app/src/main/kotlin/dev/kestrel/app/commands/ScreenTargets.kt app/src/main/kotlin/dev/kestrel/app/commands/ScreenCommands.kt app/src/main/kotlin/dev/kestrel/app/commands/CommandTexts.kt app/src/test/kotlin/dev/kestrel/app/commands/ScreenTargetsTest.kt app/src/androidTest/kotlin/dev/kestrel/app/commands/TouchCommandsTest.kt testapp/src/main/kotlin/dev/kestrel/testapp/NotificationActivity.kt testapp/src/main/AndroidManifest.xml
git commit -m "feat(commands): check, uncheck and switch, expand, collapse and dismiss, also in the notification shade, and move sliders one step" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: Scrolling, swipes, pinches and drags

**Files:**
- Create: `core/screen/src/main/kotlin/dev/kestrel/screen/act/GesturePaths.kt`
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt` (`screenBounds`), `core/screen/src/main/kotlin/dev/kestrel/screen/act/ActionResult.kt` (`NOTHING_SCROLLS`), `core/screen/src/main/kotlin/dev/kestrel/screen/act/ScreenActions.kt` (`scroll` gives `NOTHING_SCROLLS`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/commands/ScreenCommands.kt` (imports, `run`, `scroll`, `scrollToEnd`, `scrollAllTheWay`, `content`, `swipePage`, `swipeFromEdge`, `swipe`, `pinch`, `drag`, `dragTarget`, `scrollDirection`, `screenEdge`, the companion object), `app/src/main/kotlin/dev/kestrel/app/commands/CommandTexts.kt` (`NOTHING_SWIPES_SIDEWAYS`, `DRAG_WHERE`)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 9.3, the sentence on sideways swipes)
- Test: `core/screen/src/test/kotlin/dev/kestrel/screen/act/GesturePathsTest.kt` (new), `app/src/androidTest/kotlin/dev/kestrel/app/commands/GestureCommandsTest.kt` (new)

**Interfaces:**
- Consumes: Task 2's `Command.Scroll(direction, target)`, `ScrollToEnd(top, target)`, `SwipePage(forward)`, `SwipeFromEdge(edge)`, `Swipe(direction, target)`, `Pinch(out, target)`, `Drag(from, to)`, `Direction`, `Edge`, `Target`, `SpokenNumbers.answer(text)`; Task 4's `ScreenCommands.act` (with `onCell` for a grid cell), its private `done(ActionResult)` and `changed()`, `targets.still(chosen)`, `ScreenTargets.chosen(element)`, `ChosenElement`, `TargetKind.TAP` and `SCROLL`, `CommandConversation.ask`, `CommandTests` (`only` and the rest) and `CommandsActivity` ("Drag me", "Drop here", the pager `pager`); Task 5's `GlobalAction.DISMISS_SHADE` and `ActionMethod`'s import in `ScreenCommands.kt`; `ScreenActions.scroll(direction, key)`, `swipe`, `pinch`, `drag`; `ScrollDirection`; `IntRect`; `OverlayHost.screenBounds()`; the fixtures `ScrollActivity` (with `pages`) and `GesturesActivity`.
- Produces: in package `dev.kestrel.screen.act`, `enum class ScreenEdge { TOP, BOTTOM, LEFT, RIGHT }`, `data class SwipeLine(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int)`, `data class PinchLine(val centerX: Int, val centerY: Int, val startSpan: Int, val endSpan: Int)` and `object GesturePaths { fun limit(screen: IntRect, centerX: Int, centerY: Int, out: Boolean): PinchLine?; fun limit(screen: IntRect, fromX: Int, fromY: Int, fingerDirection: ScrollDirection): SwipeLine?; fun fromEdge(screen: IntRect, edge: ScreenEdge): SwipeLine }`; `val ScreenSession.screenBounds: IntRect`; `ActionResult.NOTHING_SCROLLS` (what `ScreenActions.scroll` gives when nothing scrolls that way); `CommandTexts.NOTHING_SWIPES_SIDEWAYS` and `DRAG_WHERE`; in `ScreenCommands`, the private `companion object` with `FOUND`, which Task 7 uses.

Design D7. "Scroll up/down/left/right" scrolls through `ScreenActions.scroll`, with a list that the owner names ("scroll down on Messages") or the one that `scroll` picks; "scroll to top/bottom" repeats it until the action fails or the screen's content stays the same, at most 30 times or 15 s. "Swipe forwards" and "swipe backwards" scroll a list or pager that moves sideways right or left, and say "Nothing on the screen swipes sideways." when there is none; any other failure shows its own reason. "Swipe up/down/left/right" swipes from the screen's centre, or from the centre of a grid's square or an element ("swipe up on 5", "swipe 5 up"), in the finger's direction, which the owner names; spec 9.3's rule against sideways swipes binds Kestrel's own choices and fallbacks, and this task says so in the spec. "Swipe from the bottom/top/left/right edge" swipes from one pixel inside that edge a third of the screen inwards. "Pinch in/out" pinches around the screen's centre, a square's or an element's. `GesturePaths` keeps these gestures on the screen: a swipe covers a third of the screen and ends at least one pixel inside it, and a pinch's larger span is at most 80% of the screen's width and at most twice the distance from its centre to the nearer side edge (the fingers move side by side, so only the side edges limit them), with the smaller span a quarter of the larger. "Drag X to Y" presses and holds X's centre and moves to Y's; "drag X" asks "Drag it where?" and takes the answer as Y: a number, a name, or either after "to", where a lone "to" is the number 2, as in every answer. The question, or "Which one?" for Y, can take many seconds, so X is checked again right before the finger moves, as Y is, and the drag ends with "The screen changed. Say it again." when X has gone (a square of the grid is a fixed point and needs no check).

- [ ] **Step 1: Write the failing tests**

Create `core/screen/src/test/kotlin/dev/kestrel/screen/act/GesturePathsTest.kt`:

```kotlin
package dev.kestrel.screen.act

import dev.kestrel.screen.model.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GesturePathsTest {
    /** kestrel-api37's screen: a Pixel 8, 1080 by 2400 pixels. */
    private val screen = IntRect(0, 0, 1080, 2400)

    @Test
    fun aPinchAtTheCentreCoversAtMostEightyPercentOfTheWidth() {
        assertEquals(PinchLine(540, 1200, 216, 864), GesturePaths.limit(screen, 540, 1200, out = true))
        assertEquals(PinchLine(540, 1200, 864, 216), GesturePaths.limit(screen, 540, 1200, out = false))
    }

    @Test
    fun aPinchNearASideEdgeKeepsBothFingersOnTheScreen() {
        // ScreenActions.pinch puts the fingers half a span to each side of the centre: here at last from 0 to 200, and from
        // 921 to 1079, the screen's last column.
        assertEquals(PinchLine(100, 1200, 50, 200), GesturePaths.limit(screen, 100, 1200, out = true))
        assertEquals(PinchLine(1000, 1200, 39, 158), GesturePaths.limit(screen, 1000, 1200, out = true))
    }

    @Test
    fun aPinchOnASideEdgeOrOutsideTheScreenHasNoPath() {
        assertNull(GesturePaths.limit(screen, 0, 1200, out = true))
        assertNull(GesturePaths.limit(screen, 1080, 1200, out = true))
        assertNull(GesturePaths.limit(screen, 540, -1, out = false))
    }

    @Test
    fun aSwipeCoversAThirdOfTheScreenInTheFingersDirection() {
        assertEquals(SwipeLine(540, 1200, 540, 400), GesturePaths.limit(screen, 540, 1200, ScrollDirection.UP))
        assertEquals(SwipeLine(540, 1200, 540, 2000), GesturePaths.limit(screen, 540, 1200, ScrollDirection.DOWN))
        assertEquals(SwipeLine(540, 1200, 180, 1200), GesturePaths.limit(screen, 540, 1200, ScrollDirection.LEFT))
        assertEquals(SwipeLine(540, 1200, 900, 1200), GesturePaths.limit(screen, 540, 1200, ScrollDirection.RIGHT))
    }

    @Test
    fun aSwipeNearAnEdgeEndsOnePixelInsideTheScreen() {
        assertEquals(SwipeLine(540, 2300, 540, 2398), GesturePaths.limit(screen, 540, 2300, ScrollDirection.DOWN))
        assertEquals(SwipeLine(100, 1200, 1, 1200), GesturePaths.limit(screen, 100, 1200, ScrollDirection.LEFT))
        // A swipe that cannot move, or that starts outside the screen, has no path.
        assertNull(GesturePaths.limit(screen, 540, 1, ScrollDirection.UP))
        assertNull(GesturePaths.limit(screen, 540, 2400, ScrollDirection.UP))
    }

    @Test
    fun anEdgeSwipeStartsOnePixelInsideItsEdgeAndGoesAThirdInwards() {
        assertEquals(SwipeLine(540, 1, 540, 801), GesturePaths.fromEdge(screen, ScreenEdge.TOP))
        assertEquals(SwipeLine(540, 2398, 540, 1598), GesturePaths.fromEdge(screen, ScreenEdge.BOTTOM))
        assertEquals(SwipeLine(1, 1200, 361, 1200), GesturePaths.fromEdge(screen, ScreenEdge.LEFT))
        assertEquals(SwipeLine(1078, 1200, 718, 1200), GesturePaths.fromEdge(screen, ScreenEdge.RIGHT))
    }
}
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/commands/GestureCommandsTest.kt`:

```kotlin
package dev.kestrel.app.commands

import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.commands.Outcome
import dev.kestrel.screen.act.GlobalAction
import dev.kestrel.screen.model.WindowKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Scrolling, swipes, pinches and drags by voice (design spec, Appendix A, "Touch gestures" and "Swipe gestures"),
 * through the same path as speech. Like every command test, they run only through scripts\command-test.ps1.
 */
class GestureCommandsTest {
    // Before ScreenServiceRule, as in RefusedReplyTest: a run without kestrelCommands=true skips each test before the rule
    // switches the accessibility services, and before @Before and @After run.
    @get:Rule(order = 0)
    val only = CommandTests.only

    @get:Rule(order = 1)
    val screen = ScreenServiceRule()

    @Before
    fun start() = CommandTests.start()

    @After
    fun stop() = CommandTests.stop()

    @Test
    fun scrollMovesTheListAndScrollToTheEndsGoesAllTheWay() = runBlocking<Unit> {
        screen.open("ScrollActivity", "Scroll fixture")

        assertEquals(Outcome.DONE, CommandTests.say("scroll down").outcome)
        screen.awaitElement { it.label == "Row 25" }
        assertFalse(shows("Row 1"))
        assertEquals(Outcome.DONE, CommandTests.say("scroll to bottom").outcome)
        screen.awaitElement { it.label == "Row 60" }
        assertEquals(Outcome.DONE, CommandTests.say("scroll to top").outcome)
        screen.awaitElement { it.label == "Row 1" }
    }

    @Test
    fun scrollOnANamedListScrollsThatList() = runBlocking<Unit> {
        screen.open("CommandsActivity", "Commands fixture")

        // The pager has no name of its own; Kestrel names it after its view ID, `pager`.
        assertEquals(Outcome.DONE, CommandTests.say("scroll right on pager").outcome)
        awaitStatus("Showing page 2")
    }

    @Test
    fun swipeForwardsAndBackwardsTurnThePagesOfAPager() = runBlocking<Unit> {
        screen.open("ScrollActivity", "Pager scroll fixture", extras = "--ez pages true")

        assertEquals(Outcome.DONE, CommandTests.say("swipe forwards").outcome)
        screen.awaitElement { it.label == "Second page row 1" }
        assertEquals(Outcome.DONE, CommandTests.say("swipe backwards").outcome)
        // Part of the first page stays in sight beside the second, so the test waits until the second page has gone.
        withTimeout(5_000) { while (shows("Second page row 1")) delay(100) }
    }

    @Test
    fun swipeForwardsSaysSoWhenNothingMovesSideways() = runBlocking<Unit> {
        screen.open("ScrollActivity", "Scroll fixture")

        assertEquals(Outcome.FAILED, CommandTests.say("swipe forwards").outcome)
        withTimeout(5_000) { while (screen.session.feedbackText != CommandTexts.NOTHING_SWIPES_SIDEWAYS) delay(50) }
    }

    @Test
    fun aSwipeGoesInTheDirectionThatTheOwnerNames() = runBlocking<Unit> {
        screen.open("GesturesActivity", "Gestures fixture")

        assertEquals(Outcome.DONE, CommandTests.say("swipe up").outcome)
        awaitStatus("Swipe up")
        assertEquals(Outcome.DONE, CommandTests.say("swipe left on gesture pad").outcome)
        awaitStatus("Swipe left")
    }

    @Test
    fun pinchesOpenAndCloseAroundTheCentre() = runBlocking<Unit> {
        screen.open("GesturesActivity", "Gestures fixture")

        assertEquals(Outcome.DONE, CommandTests.say("pinch out").outcome)
        awaitStatus("Pinch out")
        assertEquals(Outcome.DONE, CommandTests.say("pinch in on gesture pad").outcome)
        awaitStatus("Pinch in")
    }

    @Test
    fun dragMovesAnElementToTheTargetThatTheOwnerNamesOrGivesWhenAsked() = runBlocking<Unit> {
        screen.open("CommandsActivity", "Commands fixture")

        assertEquals(Outcome.DONE, CommandTests.say("drag Drag me to Drop here").outcome)
        awaitStatus("Dropped on Drop here")

        screen.open("CommandsActivity", "Commands fixture")
        assertEquals(CommandTexts.DRAG_WHERE, CommandTests.ask("drag Drag me"))
        assertEquals(Outcome.DONE, CommandTests.answer("to Drop here").outcome)
        awaitStatus("Dropped on Drop here")
    }

    @Test
    fun edgeSwipesReachTheSystemsGestures() = runBlocking<Unit> {
        screen.open("GesturesActivity", "Gestures fixture")

        // With gesture navigation, as on kestrel-api37, a swipe from the top edge opens the notification shade,
        assertEquals(Outcome.DONE, CommandTests.say("swipe from the top edge").outcome)
        try {
            withTimeout(5_000) { while (!shadeOpen()) delay(100) }
        } finally {
            screen.session.actions.global(GlobalAction.DISMISS_SHADE)
        }
        withTimeout(5_000) { while (shadeOpen()) delay(100) }
        // and one from the left edge goes back, which closes the fixture.
        assertEquals(Outcome.DONE, CommandTests.say("swipe from the left edge").outcome)
        withTimeout(5_000) {
            while (screen.session.snapshots.current().snapshot.windows.any { it.kind == WindowKind.APPLICATION && it.packageName == FIXTURES }) {
                delay(100)
            }
        }
    }

    private suspend fun shows(label: String): Boolean = screen.session.snapshots.current().snapshot.elements.any { it.label == label }

    private suspend fun awaitStatus(text: String) = screen.awaitElement { it.viewId == "$FIXTURES:id/status" && it.label == text }

    /** Whether the notification shade has the input focus. `dumpsys window` names the window, never its content. */
    private fun shadeOpen(): Boolean =
        screen.shell("dumpsys window").lineSequence().any { "mCurrentFocus" in it && "NotificationShade" in it }
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 --continue :core:screen:testDebugUnitTest --tests dev.kestrel.screen.act.GesturePathsTest :app:compileDebugAndroidTestKotlin`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'PinchLine'", "Unresolved reference 'GesturePaths'", "Unresolved reference 'SwipeLine'" and "Unresolved reference 'ScreenEdge'" in `:core:screen`, and "Unresolved reference 'NOTHING_SWIPES_SIDEWAYS'" and "Unresolved reference 'DRAG_WHERE'" in the device tests of `:app`.

- [ ] **Step 3: Keep the gestures on the screen**

Create `core/screen/src/main/kotlin/dev/kestrel/screen/act/GesturePaths.kt`:

```kotlin
package dev.kestrel.screen.act

import dev.kestrel.screen.model.IntRect

/** One of the screen's four edges. */
enum class ScreenEdge { TOP, BOTTOM, LEFT, RIGHT }

/** A swipe from ([fromX], [fromY]) to ([toX], [toY]), in screen pixels. */
data class SwipeLine(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int)

/** A pinch around ([centerX], [centerY]) whose two fingers, side by side, move from [startSpan] to [endSpan] pixels apart. */
data class PinchLine(val centerX: Int, val centerY: Int, val startSpan: Int, val endSpan: Int)

/**
 * The gestures that the owner names by voice, kept on the screen (design spec, section 9.3): Android refuses a gesture
 * with a point outside the screen, and a finger that would leave the screen ends the gesture early. "One pixel inside"
 * leaves the outermost row or column of pixels free.
 */
object GesturePaths {
    /** How much of the screen's width a pinch's larger span may cover. */
    private const val PINCH_WIDTH_SHARE = 0.8

    /** The smaller span of a pinch is this part of the larger one. */
    private const val SMALL_SPAN_DIVISOR = 4

    /** A swipe moves a third of the screen's height (up and down) or width (left and right). */
    private const val SWIPE_DIVISOR = 3

    /**
     * A pinch out ([out]) or in around ([centerX], [centerY]). The larger span is at most 80% of the screen's width and
     * at most twice the distance from the centre to the nearer side edge, so that both fingers stay on the screen; the
     * smaller span is a quarter of the larger. Null when the centre lies outside the screen, or so close to a side edge
     * that the fingers could not move.
     */
    fun limit(screen: IntRect, centerX: Int, centerY: Int, out: Boolean): PinchLine? {
        if (!screen.holds(centerX, centerY)) return null
        val nearer = minOf(centerX - screen.left, screen.right - 1 - centerX)
        val larger = minOf((screen.width * PINCH_WIDTH_SHARE).toInt(), 2 * nearer)
        val smaller = larger / SMALL_SPAN_DIVISOR
        if (smaller < 1) return null
        return if (out) PinchLine(centerX, centerY, smaller, larger) else PinchLine(centerX, centerY, larger, smaller)
    }

    /**
     * A swipe from ([fromX], [fromY]) in [fingerDirection], the direction in which the finger moves (the opposite of
     * `ScreenActions.scroll`'s direction, which is the content's), over a third of the screen's height or width, cut so
     * that it ends at least one pixel inside the screen. Null when the start lies outside the screen or the cut swipe
     * would not move.
     */
    fun limit(screen: IntRect, fromX: Int, fromY: Int, fingerDirection: ScrollDirection): SwipeLine? {
        if (!screen.holds(fromX, fromY)) return null
        val tall = screen.height / SWIPE_DIVISOR
        val wide = screen.width / SWIPE_DIVISOR
        val line = when (fingerDirection) {
            ScrollDirection.UP -> SwipeLine(fromX, fromY, fromX, maxOf(fromY - tall, screen.top + 1))
            ScrollDirection.DOWN -> SwipeLine(fromX, fromY, fromX, minOf(fromY + tall, screen.bottom - 2))
            ScrollDirection.LEFT -> SwipeLine(fromX, fromY, maxOf(fromX - wide, screen.left + 1), fromY)
            ScrollDirection.RIGHT -> SwipeLine(fromX, fromY, minOf(fromX + wide, screen.right - 2), fromY)
        }
        val moves = when (fingerDirection) {
            ScrollDirection.UP -> line.toY < fromY
            ScrollDirection.DOWN -> line.toY > fromY
            ScrollDirection.LEFT -> line.toX < fromX
            ScrollDirection.RIGHT -> line.toX > fromX
        }
        return line.takeIf { moves }
    }

    /** A swipe from one pixel inside [edge], at the middle of that edge, a third of the screen inwards. */
    fun fromEdge(screen: IntRect, edge: ScreenEdge): SwipeLine {
        val tall = screen.height / SWIPE_DIVISOR
        val wide = screen.width / SWIPE_DIVISOR
        return when (edge) {
            ScreenEdge.TOP -> SwipeLine(screen.centerX, screen.top + 1, screen.centerX, screen.top + 1 + tall)
            ScreenEdge.BOTTOM -> SwipeLine(screen.centerX, screen.bottom - 2, screen.centerX, screen.bottom - 2 - tall)
            ScreenEdge.LEFT -> SwipeLine(screen.left + 1, screen.centerY, screen.left + 1 + wide, screen.centerY)
            ScreenEdge.RIGHT -> SwipeLine(screen.right - 2, screen.centerY, screen.right - 2 - wide, screen.centerY)
        }
    }

    private fun IntRect.holds(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
}
```

In `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt`, replace

```kotlin
    /** The grid on the screen now, or null. "Tap 5" taps the centre of the grid's cell 5. */
    val grid: Grid? get() = gridOverlay.grid
```

with

```kotlin
    /** The grid on the screen now, or null. "Tap 5" taps the centre of the grid's cell 5. */
    val grid: Grid? get() = gridOverlay.grid

    /** The whole screen in pixels, for the swipes and pinches that the owner names (design spec, section 9.3). */
    val screenBounds: IntRect get() = overlays.screenBounds()
```

In `core/screen/src/main/kotlin/dev/kestrel/screen/act/ActionResult.kt`, replace

```kotlin
    data class Failed(val reason: String) : ActionResult
}
```

with

```kotlin
    data class Failed(val reason: String) : ActionResult

    companion object {
        /** What `ScreenActions.scroll` gives when nothing on the screen scrolls in the asked direction. */
        val NOTHING_SCROLLS = Failed("Nothing on the screen scrolls that way")
    }
}
```

In `core/screen/src/main/kotlin/dev/kestrel/screen/act/ScreenActions.kt`, replace

```kotlin
            ?: return ActionResult.Failed("Nothing on the screen scrolls that way")
```

with

```kotlin
            ?: return ActionResult.NOTHING_SCROLLS
```

- [ ] **Step 4: Run the tests of `:core:screen` to verify that they pass**

Run: `scripts\gradle.ps1 :core:screen:testDebugUnitTest --tests dev.kestrel.screen.act.GesturePathsTest`
Expected: `BUILD SUCCESSFUL`, with 6 tests in `GesturePathsTest`.

- [ ] **Step 5: Run the scrolls and gestures in the screen part**

In `app/src/main/kotlin/dev/kestrel/app/commands/CommandTexts.kt`, replace

```kotlin
    fun noNumber(number: Int) = "There's no number $number on the screen."
```

with

```kotlin
    fun noNumber(number: Int) = "There's no number $number on the screen."

    const val NOTHING_SWIPES_SIDEWAYS = "Nothing on the screen swipes sideways."
    const val DRAG_WHERE = "Drag it where?"
```

In `app/src/main/kotlin/dev/kestrel/app/commands/ScreenCommands.kt`, replace

```kotlin
import dev.kestrel.commands.CommandResult
```

with

```kotlin
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Direction
import dev.kestrel.commands.Edge
```

Replace

```kotlin
import dev.kestrel.screen.act.ActionResult
```

with

```kotlin
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.GesturePaths
import dev.kestrel.screen.act.ScreenEdge
import dev.kestrel.screen.act.ScrollDirection
```

Replace

```kotlin
import kotlinx.coroutines.withContext
```

with

```kotlin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
```

Replace

```kotlin
            // Tasks 5 to 9 add their commands above this line.
```

with

```kotlin
            is Command.Scroll -> scroll(screen, command, conversation)
            is Command.ScrollToEnd -> scrollToEnd(screen, command, conversation)
            is Command.SwipePage -> swipePage(screen, command.forward)
            is Command.SwipeFromEdge -> swipeFromEdge(screen, command.edge)
            is Command.Swipe -> swipe(screen, command, conversation)
            is Command.Pinch -> pinch(screen, command, conversation)
            is Command.Drag -> drag(screen, command, conversation)
            // Tasks 5 to 9 add their commands above this line.
```

Replace

```kotlin
    private suspend fun tapAt(screen: ScreenSession, bounds: IntRect): ActionResult = screen.actions.tapAt(bounds.centerX, bounds.centerY)
```

with

```kotlin
    /** "Scroll down", and "scroll down on Messages" with a list that the owner names (design spec, Appendix A). */
    private suspend fun scroll(screen: ScreenSession, command: Command.Scroll, conversation: CommandConversation): CommandResult {
        val direction = scrollDirection(command.direction)
        val target = command.target ?: return done(screen.actions.scroll(direction))
        return act(screen, target, TargetKind.SCROLL, conversation) { screen.actions.scroll(direction, it.key) }
    }

    /**
     * "Scroll to top" and "scroll to bottom": scrolls until the list stops moving, that is until the action fails or the
     * screen's content stays the same, at most [MAX_SCROLLS] times and for [SCROLL_TO_END_MILLIS].
     */
    private suspend fun scrollToEnd(
        screen: ScreenSession,
        command: Command.ScrollToEnd,
        conversation: CommandConversation,
    ): CommandResult {
        val direction = if (command.top) ScrollDirection.UP else ScrollDirection.DOWN
        val target = command.target ?: return done(scrollAllTheWay(screen, direction, null))
        return act(screen, target, TargetKind.SCROLL, conversation) { scrollAllTheWay(screen, direction, it.key) }
    }

    private suspend fun scrollAllTheWay(screen: ScreenSession, direction: ScrollDirection, key: String?): ActionResult {
        var before = content(screen)
        var last = screen.actions.scroll(direction, key)
        if (last is ActionResult.Failed) return last
        withTimeoutOrNull(SCROLL_TO_END_MILLIS) {
            repeat(MAX_SCROLLS - 1) {
                val now = content(screen)
                if (now == before) return@withTimeoutOrNull
                before = now
                val next = screen.actions.scroll(direction, key)
                if (next is ActionResult.Failed) return@withTimeoutOrNull
                last = next
            }
        }
        return last
    }

    /** What moves when a list scrolls: the names on the screen and where they are. */
    private suspend fun content(screen: ScreenSession) = screen.snapshots.current().snapshot.elements.map { it.label to it.bounds }

    /**
     * "Swipe forwards" shows the next page of a list or pager that moves sideways, and "swipe backwards" the one before.
     * When nothing moves sideways the bar says so; any other failure, such as a touch that cancelled the swipe, shows its
     * own reason.
     */
    private suspend fun swipePage(screen: ScreenSession, forward: Boolean): CommandResult =
        when (val result = screen.actions.scroll(if (forward) ScrollDirection.RIGHT else ScrollDirection.LEFT)) {
            ActionResult.NOTHING_SCROLLS -> CommandResult(Outcome.FAILED, show = CommandTexts.NOTHING_SWIPES_SIDEWAYS)
            else -> done(result)
        }

    /** "Swipe from the bottom edge" and the other edges: from one pixel inside the edge, a third of the screen inwards. */
    private suspend fun swipeFromEdge(screen: ScreenSession, edge: Edge): CommandResult {
        val line = GesturePaths.fromEdge(screen.screenBounds, screenEdge(edge))
        return done(screen.actions.swipe(line.fromX, line.fromY, line.toX, line.toY))
    }

    /**
     * "Swipe up", "swipe up on 5" and "swipe 5 up": from the screen's centre, or from the centre of a grid's square or of
     * an element, a third of the screen in the direction that the owner names, which is the finger's direction. The owner
     * may name a sideways swipe across a list; spec 9.3's rule against them binds Kestrel's own choices.
     */
    private suspend fun swipe(screen: ScreenSession, command: Command.Swipe, conversation: CommandConversation): CommandResult {
        val direction = scrollDirection(command.direction)
        val swipeFrom: suspend (Int, Int) -> ActionResult = { x, y ->
            val line = GesturePaths.limit(screen.screenBounds, x, y, direction)
            if (line == null) OFF_SCREEN else screen.actions.swipe(line.fromX, line.fromY, line.toX, line.toY)
        }
        val target = command.target ?: return screen.screenBounds.let { done(swipeFrom(it.centerX, it.centerY)) }
        return act(screen, target, TargetKind.TAP, conversation, onCell = { swipeFrom(it.centerX, it.centerY) }) {
            swipeFrom(it.bounds.centerX, it.bounds.centerY)
        }
    }

    /** "Pinch out" and "pinch in", around the screen's centre or the centre of a grid's square or of an element. */
    private suspend fun pinch(screen: ScreenSession, command: Command.Pinch, conversation: CommandConversation): CommandResult {
        val pinchAround: suspend (Int, Int) -> ActionResult = { x, y ->
            val line = GesturePaths.limit(screen.screenBounds, x, y, command.out)
            if (line == null) OFF_SCREEN else screen.actions.pinch(line.centerX, line.centerY, line.startSpan, line.endSpan)
        }
        val target = command.target ?: return screen.screenBounds.let { done(pinchAround(it.centerX, it.centerY)) }
        return act(screen, target, TargetKind.TAP, conversation, onCell = { pinchAround(it.centerX, it.centerY) }) {
            pinchAround(it.bounds.centerX, it.bounds.centerY)
        }
    }

    /**
     * "Drag Drag me to Drop here" or "drag 5 to 9": presses and holds the centre of the first element or square and moves
     * to the second's. "Drag 5" asks "Drag it where?" and takes the answer as the second.
     */
    private suspend fun drag(screen: ScreenSession, command: Command.Drag, conversation: CommandConversation): CommandResult {
        var source: ChosenElement? = null
        var square: IntRect? = null
        // This act only finds where the drag starts; the finger moves once the target is known.
        val found = act(
            screen,
            command.from,
            TargetKind.TAP,
            conversation,
            onCell = { cell ->
                square = cell
                FOUND
            },
        ) { element ->
            source = ScreenTargets.chosen(element)
            FOUND
        }
        if (source == null && square == null) return found
        val to = command.to ?: dragTarget(conversation.ask(CommandTexts.DRAG_WHERE) ?: return CommandResult(Outcome.CANCELLED))
        var moved = false
        // The question, or "Which one?" for the target, can take many seconds, so the start is checked again right before
        // the finger moves, as the target is (section 9.2). A square of the grid is a fixed point of the screen.
        val dragTo: suspend (Int, Int) -> ActionResult = { toX, toY ->
            val from = square ?: source?.let { targets.still(it) }?.bounds
            if (from == null) {
                moved = true
                FOUND
            } else {
                screen.actions.drag(from.centerX, from.centerY, toX, toY)
            }
        }
        val result = act(screen, to, TargetKind.TAP, conversation, onCell = { dragTo(it.centerX, it.centerY) }) {
            dragTo(it.bounds.centerX, it.bounds.centerY)
        }
        return if (moved) changed() else result
    }

    /** The answer to "Drag it where?": a number, a name, or either after "to". A lone "to" is the number 2. */
    private fun dragTarget(answer: String): Target {
        val rest = answer.replace(LEADING_TO, "").trim().ifEmpty { answer }
        return SpokenNumbers.answer(rest)?.let { Target.Number(it) } ?: Target.Label(rest)
    }

    private fun scrollDirection(direction: Direction): ScrollDirection = when (direction) {
        Direction.UP -> ScrollDirection.UP
        Direction.DOWN -> ScrollDirection.DOWN
        Direction.LEFT -> ScrollDirection.LEFT
        Direction.RIGHT -> ScrollDirection.RIGHT
    }

    private fun screenEdge(edge: Edge): ScreenEdge = when (edge) {
        Edge.TOP -> ScreenEdge.TOP
        Edge.BOTTOM -> ScreenEdge.BOTTOM
        Edge.LEFT -> ScreenEdge.LEFT
        Edge.RIGHT -> ScreenEdge.RIGHT
    }

    private companion object {
        const val MAX_SCROLLS = 30
        const val SCROLL_TO_END_MILLIS = 15_000L

        /** "to" at the start of an answer, as in "to Drop here". */
        val LEADING_TO = Regex("^\\s*to\\b[\\s,]*", RegexOption.IGNORE_CASE)

        /** A gesture whose point lies outside the screen, or too close to its edge to move. */
        val OFF_SCREEN = ActionResult.Failed("The gesture would leave the screen")

        /** What a step that only finds its element gives `act`: nothing has moved yet. */
        val FOUND = ActionResult.Done(ActionMethod.ACTION)
    }

    private suspend fun tapAt(screen: ScreenSession, bounds: IntRect): ActionResult = screen.actions.tapAt(bounds.centerX, bounds.centerY)
```

- [ ] **Step 6: Update the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 9.3, replace

```markdown
Kestrel never swipes sideways across a list that moves up and down, because a sideways swipe across a row can archive a message or start a call.
```

with

```markdown
Kestrel never swipes sideways on its own across a list that moves up and down, because a sideways swipe across a row can archive a message or start a call. The one exception is the fallback of "dismiss" for a notification in the notification shade that offers no dismiss action. The rule binds Kestrel's own choices and fallbacks: a swipe whose direction the owner names, such as "swipe left on 5", goes where the owner says. The swipes and pinches that the owner names stay on the screen: a swipe covers a third of the screen and ends at least one pixel inside it, and a pinch's larger span is at most 80% of the screen's width and at most twice the distance from its centre to the nearer side edge.
```

- [ ] **Step 7: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:screen` 54, `:app` 308, `:core:commands` 46, `:core:voice` 140), lint in every module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests compile.

- [ ] **Step 8: Run the command device tests**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\command-test.ps1 -Serial emulator-5554`
Expected: `BUILD SUCCESSFUL` and "Command tests: 27 run, 0 failed, 0 errors, 0 skipped." (`TouchCommandsTest` 19, `GestureCommandsTest` 8), with no warning from the restores at the end. If `edgeSwipesReachTheSystemsGestures` fails, run `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; & (Get-Adb) -s emulator-5554 shell settings get secure navigation_mode`: the test needs gesture navigation (2), which prototype P4 found on kestrel-api37, and a swipe that Kestrel injects at the edge must reach the system's gesture handlers. Report the failure message and that value to the controller, and do not change the test or the code.

- [ ] **Step 9: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 10: Commit**

```powershell
git add core/screen/src/main/kotlin/dev/kestrel/screen/act/GesturePaths.kt core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt core/screen/src/main/kotlin/dev/kestrel/screen/act/ActionResult.kt core/screen/src/main/kotlin/dev/kestrel/screen/act/ScreenActions.kt core/screen/src/test/kotlin/dev/kestrel/screen/act/GesturePathsTest.kt app/src/main/kotlin/dev/kestrel/app/commands/ScreenCommands.kt app/src/main/kotlin/dev/kestrel/app/commands/CommandTexts.kt app/src/androidTest/kotlin/dev/kestrel/app/commands/GestureCommandsTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(commands): scroll, swipe, pinch and drag by voice, with gestures that stay on the screen" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 7: Numbers, labels, the grid, "What is N?" and "Show commands for X"

**Files:**
- Modify: `app/src/main/kotlin/dev/kestrel/app/commands/ScreenCommands.kt` (imports, `run`, `shown`, `squares`, `whatIs`, `commandsFor`), `app/src/main/kotlin/dev/kestrel/app/commands/ScreenTargets.kt` (`role`, `sayable`, `SWITCHES`), `app/src/main/kotlin/dev/kestrel/app/commands/CommandTexts.kt` (`SHOW_GRID_FIRST`, `whatIs`, `square`, `youCanSay`)
- Test: `app/src/test/kotlin/dev/kestrel/app/commands/ScreenTargetsTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/commands/OverlayCommandsTest.kt` (new)

**Interfaces:**
- Consumes: Task 2's `Command.ShowNumbers`, `HideNumbers`, `ShowLabels`, `HideLabels`, `ShowGrid`, `HideGrid`, `MoreSquares`, `FewerSquares`, `WhatIs(number)`, `CommandsFor(target)`; Task 4's `ScreenCommands.act`, its private `changed()`, `targets` and `environment`, `ScreenTargets.resolve(target, kind, strictness, cells)`, `ScreenTargets.same`, `ScreenTargets.names`, `Resolution`, `CommandEnvironment.options` (`CommandOptions.numbers`), `CommandTexts.SHOW_NUMBERS_FIRST` and `noNumber`, `CommandTests` (`say`, `ask`, `answer`, `awaitSaid`) and `CommandsActivity` ("Save", "Subscribe", "Counter"); Task 6's `FOUND` in `ScreenCommands`' companion object and its drag; `ScreenSession.showNumbers(mode)`, `hideNumbers()`, `showLabels()`, `hideLabels()`, `showGrid(grid)`, `hideGrid()`, `grid`, `numberBadges`, `labelsShowing`; `Grid.more()`, `Grid.fewer()`, `Grid.columns`; `ScreenElement`'s `className`, `checkable`, `checked`, `editable`, `slider`, `scrollable`, `clickable`, `longClickable`, `actions`.
- Produces: in `ScreenTargets`' companion, `fun role(element: ScreenElement): String` and `fun sayable(element: ScreenElement): List<String>`; `CommandTexts.SHOW_GRID_FIRST`, `fun whatIs(number: Int, name: String?, role: String): String`, `fun square(number: Int): String`, `fun youCanSay(phrases: List<String>): String`.

Design D7 and D6. "Show numbers" puts numbers on the actionable elements, in the mode of the option (Task 9 stores it; until then the default, all elements), and "hide numbers" removes them; "show labels" and "hide labels" do the same for the names; "show grid", "hide grid", "more squares" and "fewer squares" show and change the grid, which stays until "hide grid" ("more squares" and "fewer squares" without a grid say "Say 'show grid' first."). While the grid shows, a number names its square: Task 4's tap, long press and bare numbers and Task 6's swipe, pinch and drag already act on squares. "What is 5?" says the name and the kind of the element with the number 5 ("Number 5 is Send, a button."; "Number 5 has no name. It's a switch, on."), or, while the grid shows, "That's square 5 of the grid."; the name is the element's or its nearest labelled ancestor's, as `ScreenTargets.names` gives it. "Show commands for X" says what the element allows, from its actions and its kind ("You can say tap, long press, check or uncheck."). Both answers are spoken, as design D2 lists them, and they are the screen's names, which Kestrel speaks and never logs.

- [ ] **Step 1: Write the failing tests**

In `app/src/test/kotlin/dev/kestrel/app/commands/ScreenTargetsTest.kt`, replace

```kotlin
    @Test
    fun aChosenElementPrintsNoLabel() {
```

with

```kotlin
    @Test
    fun whatIsNamesTheKindOfAnElementAndTheStateOfOneThatChecks() {
        val wifi = element("wifi", "Wi-Fi", IntRect(0, 0, 100, 100), className = "android.widget.Switch").copy(checkable = true, checked = true)
        val slider = element("seek", null, IntRect(0, 0, 100, 100), clickable = false, className = "android.widget.SeekBar").copy(slider = true)

        assertEquals("a button", ScreenTargets.role(snapshot.element("send")!!))
        assertEquals("a check box, off", ScreenTargets.role(snapshot.element("subscribe")!!))
        assertEquals("a switch, on", ScreenTargets.role(wifi))
        assertEquals("a text field", ScreenTargets.role(snapshot.element("message")!!))
        assertEquals("a text", ScreenTargets.role(snapshot.element("ana")!!))
        assertEquals("Number 5 is Send, a button.", CommandTexts.whatIs(5, "Send", ScreenTargets.role(snapshot.element("send")!!)))
        assertEquals("Number 5 has no name. It's a slider.", CommandTexts.whatIs(5, null, ScreenTargets.role(slider)))
    }

    @Test
    fun showCommandsForSaysWhatAnElementAllows() {
        assertEquals("You can say tap, check or uncheck.", CommandTexts.youCanSay(ScreenTargets.sayable(snapshot.element("subscribe")!!)))
        assertEquals("You can say tap or long press.", CommandTexts.youCanSay(ScreenTargets.sayable(elements[0].copy(longClickable = true))))
        assertEquals("You can say tap or expand.", CommandTexts.youCanSay(ScreenTargets.sayable(snapshot.element("details")!!)))
        assertEquals("You can say tap or start editing.", CommandTexts.youCanSay(ScreenTargets.sayable(snapshot.element("message")!!)))
    }

    @Test
    fun aChosenElementPrintsNoLabel() {
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/commands/OverlayCommandsTest.kt`:

```kotlin
package dev.kestrel.app.commands

import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.commands.Outcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Numbers, labels, the grid, "What is N?" and "Show commands for X" by voice (design spec, Appendix A, "Help" and
 * "Grid"), through the same path as speech. Like every command test, they run only through scripts\command-test.ps1.
 */
class OverlayCommandsTest {
    // Before ScreenServiceRule, as in RefusedReplyTest: a run without kestrelCommands=true skips each test before the rule
    // switches the accessibility services, and before @Before and @After run.
    @get:Rule(order = 0)
    val only = CommandTests.only

    @get:Rule(order = 1)
    val screen = ScreenServiceRule()

    @Before
    fun start() = CommandTests.start()

    @After
    fun stop() = CommandTests.stop()

    @Test
    fun numbersAndLabelsShowAndHide() = runBlocking<Unit> {
        screen.open("CommandsActivity", "Commands fixture")

        assertEquals(Outcome.DONE, CommandTests.say("show numbers").outcome)
        assertTrue(screen.session.numberBadges.isNotEmpty())
        assertEquals(Outcome.DONE, CommandTests.say("hide numbers").outcome)
        assertTrue(screen.session.numberBadges.isEmpty())
        assertEquals(Outcome.DONE, CommandTests.say("show labels").outcome)
        assertTrue(screen.session.labelsShowing)
        assertEquals(Outcome.DONE, CommandTests.say("hide labels").outcome)
        assertFalse(screen.session.labelsShowing)
    }

    @Test
    fun whatIsANumberSaysTheNameAndTheKindOfItsElement() = runBlocking<Unit> {
        screen.open("CommandsActivity", "Commands fixture")
        assertEquals(Outcome.DONE, CommandTests.say("show numbers").outcome)
        val save = badge(screen.awaitElement { it.label == "Save" }.key)
        val subscribe = badge(screen.awaitElement { it.label == "Subscribe" && it.checkable }.key)

        assertEquals(Outcome.DONE, CommandTests.say("what is $save").outcome)
        CommandTests.awaitSaid("Number $save is Save, a button.")
        assertEquals(Outcome.DONE, CommandTests.say("what is $subscribe").outcome)
        CommandTests.awaitSaid("Number $subscribe is Subscribe, a check box, off.")
    }

    @Test
    fun showCommandsForSaysWhatAnElementAllows() = runBlocking<Unit> {
        screen.open("CommandsActivity", "Commands fixture")

        assertEquals(Outcome.DONE, CommandTests.say("show commands for Subscribe").outcome)
        CommandTests.awaitSaid("You can say tap, check or uncheck.")
        assertEquals(Outcome.DONE, CommandTests.say("show commands for Counter").outcome)
        CommandTests.awaitSaid("You can say tap or long press.")
    }

    @Test
    fun theGridTapsAndMakesGesturesOnItsSquares() = runBlocking<Unit> {
        screen.open("GesturesActivity", "Gestures fixture")

        assertEquals(Outcome.DONE, CommandTests.say("show grid").outcome)
        assertEquals(4, screen.session.grid?.columns)
        assertEquals(Outcome.DONE, CommandTests.say("more squares").outcome)
        assertEquals(6, screen.session.grid?.columns)
        assertEquals(Outcome.DONE, CommandTests.say("fewer squares").outcome)
        assertEquals(4, screen.session.grid?.columns)
        // On kestrel-api37's screen of 1080 by 2400 pixels, the grid has 4 columns and 9 rows: square 18 lies on the pad,
        // and square 26 two rows below it.
        assertEquals(Outcome.DONE, CommandTests.say("tap 18").outcome)
        awaitStatus("Tap")
        assertEquals(Outcome.DONE, CommandTests.say("long press 18").outcome)
        awaitStatus("Long press")
        assertEquals(Outcome.DONE, CommandTests.say("18").outcome)
        awaitStatus("Tap")
        assertEquals(Outcome.DONE, CommandTests.say("swipe 18 up").outcome)
        awaitStatus("Swipe up")
        assertEquals(Outcome.DONE, CommandTests.say("pinch out 18").outcome)
        awaitStatus("Pinch out")
        assertEquals(CommandTexts.DRAG_WHERE, CommandTests.ask("drag 18"))
        assertEquals(Outcome.DONE, CommandTests.answer("26").outcome)
        awaitStatus("Drag down")
        assertEquals(Outcome.DONE, CommandTests.say("what is 18").outcome)
        CommandTests.awaitSaid("That's square 18 of the grid.")
        assertEquals(Outcome.DONE, CommandTests.say("hide grid").outcome)
        assertNull(screen.session.grid)
    }

    private suspend fun awaitStatus(text: String) = screen.awaitElement { it.viewId == "$FIXTURES:id/status" && it.label == text }

    /** The number on the element with [key], once the numbers have followed the screen. */
    private suspend fun badge(key: String): Int = withTimeout(5_000) {
        var badge = screen.session.numberBadges.firstOrNull { it.key == key }
        while (badge == null) {
            delay(50)
            badge = screen.session.numberBadges.firstOrNull { it.key == key }
        }
        badge.number
    }
}
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 --continue :app:testDebugUnitTest --tests dev.kestrel.app.commands.ScreenTargetsTest :app:compileDebugAndroidTestKotlin`
Expected: `BUILD FAILED`, because the JVM test source does not compile: "Unresolved reference 'role'", "Unresolved reference 'whatIs'", "Unresolved reference 'youCanSay'" and "Unresolved reference 'sayable'". (The new device test compiles already: it uses only earlier names.)

- [ ] **Step 3: Describe an element and what it allows**

In `app/src/main/kotlin/dev/kestrel/app/commands/ScreenTargets.kt`, replace

```kotlin
        private fun ownNames(element: ScreenElement): List<String> = listOfNotNull(
```

with

```kotlin
        /** The class names of switches, which "What is N?" calls "a switch" rather than "a check box". */
        private val SWITCHES = setOf("Switch", "SwitchCompat", "SwitchMaterial", "MaterialSwitch", "ToggleButton")

        /** What [element] is, for "What is N?": "a button", or "a check box, on" with the state of an element that checks. */
        fun role(element: ScreenElement): String {
            val kind = element.className?.substringAfterLast('.').orEmpty()
            val noun = when {
                element.slider -> "a slider"
                element.editable -> "a text field"
                kind in SWITCHES -> "a switch"
                kind == "RadioButton" -> "a radio button"
                element.checkable -> "a check box"
                element.scrollable -> "a list"
                element.clickable || element.longClickable -> "a button"
                else -> "a text"
            }
            return if (element.checkable) "$noun, ${if (element.checked) "on" else "off"}" else noun
        }

        /** What the owner can say for [element], for "Show commands for X", from its actions and its kind. */
        fun sayable(element: ScreenElement): List<String> = buildList {
            if (element.clickable || !element.scrollable) add("tap")
            if (element.longClickable || ElementAction.LONG_CLICK in element.actions) add("long press")
            if (element.checkable) addAll(listOf("check", "uncheck"))
            if (element.slider) addAll(listOf("increment", "decrement"))
            if (ElementAction.EXPAND in element.actions) add("expand")
            if (ElementAction.COLLAPSE in element.actions) add("collapse")
            if (ElementAction.DISMISS in element.actions) add("dismiss")
            if (element.scrollable) add("scroll")
            if (element.editable) add("start editing")
        }

        private fun ownNames(element: ScreenElement): List<String> = listOfNotNull(
```

In `app/src/main/kotlin/dev/kestrel/app/commands/CommandTexts.kt`, replace

```kotlin
    fun noNumber(number: Int) = "There's no number $number on the screen."
```

with

```kotlin
    fun noNumber(number: Int) = "There's no number $number on the screen."

    const val SHOW_GRID_FIRST = "Say 'show grid' first."

    /** The answer to "What is 5?". [name] is a name on the screen, which Kestrel says and never logs. */
    fun whatIs(number: Int, name: String?, role: String) =
        if (name != null) "Number $number is $name, $role." else "Number $number has no name. It's $role."

    /** The answer to "What is 5?" while the grid shows. */
    fun square(number: Int) = "That's square $number of the grid."

    /** The answer to "Show commands for X", such as "You can say tap, check or uncheck." */
    fun youCanSay(phrases: List<String>) = when (phrases.size) {
        0 -> "There's nothing to say for it."
        1 -> "You can say ${phrases.single()}."
        else -> "You can say ${phrases.dropLast(1).joinToString(", ")} or ${phrases.last()}."
    }
```

- [ ] **Step 4: Run the JVM tests to verify that they pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.commands.ScreenTargetsTest`
Expected: `BUILD SUCCESSFUL`, with 11 tests in `ScreenTargetsTest` (9 and `whatIsNamesTheKindOfAnElementAndTheStateOfOneThatChecks`, `showCommandsForSaysWhatAnElementAllows`).

- [ ] **Step 5: Run the overlay commands in the screen part**

In `app/src/main/kotlin/dev/kestrel/app/commands/ScreenCommands.kt`, replace

```kotlin
import dev.kestrel.screen.model.ScreenElement
```

with

```kotlin
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.overlay.Grid
```

Replace

```kotlin
            // Tasks 5 to 9 add their commands above this line.
```

with

```kotlin
            Command.ShowNumbers -> shown { screen.showNumbers(environment.options.value.numbers) }
            Command.HideNumbers -> shown { screen.hideNumbers() }
            Command.ShowLabels -> shown { screen.showLabels() }
            Command.HideLabels -> shown { screen.hideLabels() }
            Command.ShowGrid -> shown { screen.showGrid() }
            Command.HideGrid -> shown { screen.hideGrid() }
            Command.MoreSquares -> squares(screen) { it.more() }
            Command.FewerSquares -> squares(screen) { it.fewer() }
            is Command.WhatIs -> whatIs(screen, command.number)
            is Command.CommandsFor -> commandsFor(screen, command.target, conversation)
            // Tasks 5 to 9 add their commands above this line.
```

Replace

```kotlin
    private suspend fun tapAt(screen: ScreenSession, bounds: IntRect): ActionResult = screen.actions.tapAt(bounds.centerX, bounds.centerY)
```

with

```kotlin
    /** Shows or hides numbers, labels or the grid (design spec, section 9.4). */
    private suspend fun shown(change: suspend () -> Unit): CommandResult {
        change()
        return CommandResult(Outcome.DONE, repeatable = true)
    }

    /** "More squares" and "fewer squares" change the grid that shows. */
    private suspend fun squares(screen: ScreenSession, change: (Grid) -> Grid): CommandResult {
        // The overlays change only on the main thread.
        val grid = withContext(Dispatchers.Main) { screen.grid }
            ?: return CommandResult(Outcome.FAILED, say = CommandTexts.SHOW_GRID_FIRST)
        screen.showGrid(change(grid))
        return CommandResult(Outcome.DONE, repeatable = true)
    }

    /**
     * "What is 5?" says the name and the kind of the element with that number, or, while the grid shows, which square it
     * is (design spec, Appendix A).
     */
    private suspend fun whatIs(screen: ScreenSession, number: Int): CommandResult {
        val answer = when (val found = targets.resolve(Target.Number(number), TargetKind.TAP, cells = true)) {
            is Resolution.Cell -> CommandTexts.square(found.number)
            is Resolution.One -> {
                // One snapshot for the check and the name: an element's parent is an index into its own snapshot.
                val snapshot = screen.snapshots.current().snapshot
                val element = snapshot.element(found.element.key)?.takeIf { ScreenTargets.same(found.element, it) }
                    ?: return changed()
                CommandTexts.whatIs(number, ScreenTargets.names(snapshot, element).firstOrNull(), ScreenTargets.role(element))
            }
            is Resolution.NoSuchNumber -> return CommandResult(Outcome.FAILED, say = CommandTexts.noNumber(found.number))
            Resolution.Changed -> return changed()
            else -> return CommandResult(Outcome.FAILED, say = CommandTexts.SHOW_NUMBERS_FIRST)
        }
        return CommandResult(Outcome.DONE, say = answer, repeatable = true)
    }

    /** "Show commands for Send" says what the owner can say for that element, from its actions and its kind. */
    private suspend fun commandsFor(screen: ScreenSession, target: Target, conversation: CommandConversation): CommandResult {
        var phrases: List<String>? = null
        val found = act(screen, target, TargetKind.TAP, conversation) { element ->
            phrases = ScreenTargets.sayable(element)
            FOUND
        }
        val sayable = phrases ?: return found
        return found.copy(say = CommandTexts.youCanSay(sayable))
    }

    private suspend fun tapAt(screen: ScreenSession, bounds: IntRect): ActionResult = screen.actions.tapAt(bounds.centerX, bounds.centerY)
```

- [ ] **Step 6: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:app` 310, `:core:screen` 54, `:core:commands` 46, `:core:voice` 140), lint in every module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests compile.

- [ ] **Step 7: Run the command device tests**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\command-test.ps1 -Serial emulator-5554`
Expected: `BUILD SUCCESSFUL` and "Command tests: 31 run, 0 failed, 0 errors, 0 skipped." (`TouchCommandsTest` 19, `GestureCommandsTest` 8, `OverlayCommandsTest` 4), with no warning from the restores at the end.

- [ ] **Step 8: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 9: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/commands/ScreenCommands.kt app/src/main/kotlin/dev/kestrel/app/commands/ScreenTargets.kt app/src/main/kotlin/dev/kestrel/app/commands/CommandTexts.kt app/src/test/kotlin/dev/kestrel/app/commands/ScreenTargetsTest.kt app/src/androidTest/kotlin/dev/kestrel/app/commands/OverlayCommandsTest.kt
git commit -m "feat(commands): show and hide numbers, labels and the grid by voice, and say what an element is and what it allows" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 8: The screen's labels and the command vocabulary as boost words

**Files:**
- Create: `core/commands/src/main/kotlin/dev/kestrel/commands/CommandVocabulary.kt`, `app/src/main/kotlin/dev/kestrel/app/listen/BoostWordBudget.kt`, `app/src/main/kotlin/dev/kestrel/app/listen/ScreenBoostWords.kt`
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/read/SnapshotCache.kt` (`latest`), `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt` (imports, `snapshots`, `snapshotBuilds`, `snapshotMillis`, `keepFresh`, the screen watcher in `init`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/VoiceSession.kt` (imports, the class's KDoc and constructor, the pipeline's boost words, `open`), `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (imports, the voice session's `screenWords`, `screenLabels`, `keepScreenFresh`)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 8.3)
- Test: `core/screen/src/test/kotlin/dev/kestrel/screen/read/SnapshotCacheTest.kt`, `app/src/test/kotlin/dev/kestrel/app/listen/BoostWordBudgetTest.kt` (new), `app/src/androidTest/kotlin/dev/kestrel/app/voice/ScreenBoostWordsTest.kt` (new), `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceSessionFailuresTest.kt`

**Interfaces:**
- Consumes: `ScreenSession.snapshots: SnapshotCache<LiveSnapshot>`, `LiveSnapshot.snapshot`; `ScreenElement`'s `isActionable`, `editable`, `password`, `dataSensitive`, `label`, `contentDescription`, `hint`, `suggestedName`, `windowId` and `bounds`; `WindowKind.INPUT_METHOD`; `BoostWordSource.NAME` and `BoostWordSource.SHORTEST`; `VoiceSession.open(context, engines, microphone, listener, boostWords, loadSpeechDetector, loadKeywordSpotter, loadVoice, loadRecognizer)` and `VoiceSession`'s constructor, whose last parameter is `voice`; `Recognizer.keptBoostWords(boostWords: List<String>): Int`; `Listening.state`, `Listening.sessionOpen`, `Mode.LISTENING`; `AppGraph.screen`; `ScreenServiceRule` (`open`, `session`, `shell`, `FIXTURES`); `VoiceTests.graph`, `VoiceTests.assumeModels`, `VoiceTests.turnOffAccessibilityService`, `VoiceTests.grantListeningPermissions`, `VoiceTests.startListening(ClipMicrophone)`, `VoiceTests.stopListening`, `VoiceTests.waitUntil`, the debug `ClipMicrophone`; Task 4's `CommandsActivity` and its button "Send later".
- Produces: `object CommandVocabulary { val BOOST: List<String> }` (package `dev.kestrel.commands`); `fun SnapshotCache.latest(): T?`; the `ScreenSession` members `@Volatile var keepFresh: Boolean`, `val snapshotBuilds: Int` and `val snapshotMillis: Long`; in package `dev.kestrel.app.listen`, `internal object BoostWordBudget { const val MAX_SCREEN = 40; const val MAX_VOCABULARY = 8; fun combine(fixed: List<String>, screen: List<String>, vocabulary: List<String>): List<String>; fun screenNames(snapshot: ScreenSnapshot): List<String> }` and `internal class ScreenBoostWords(screen: () -> ScreenSession?) { fun words(): List<String> }`; the parameter `screenWords: suspend () -> List<String> = { emptyList() }` of `VoiceSession.open` and of `VoiceSession`'s constructor. Plan 3C's corpus calls `BoostWordBudget.combine` with a scene's labels, and its A71 session reads `snapshotBuilds` and `snapshotMillis` through a hook of its own.

Design D13. Each utterance's boost words are "Kestrel", up to 40 labels from the screen, up to 8 phrases of the command vocabulary and then the app names, in this order; `BoostWords.format` keeps the first 100 usable phrases, so the cap drops the system apps first, as before. The labels never cost the recogniser a snapshot: `ScreenBoostWords` reads only `SnapshotCache.latest()`, the newest snapshot that was built, and while a voice session is open and Kestrel listens, `ScreenSession.keepFresh` makes the screen session build a snapshot after each settled change of the screen. `AppGraph` sets it from `Listening.state` and `Listening.sessionOpen`, also on a screen session that connects while Kestrel listens. The names come from the actionable elements outside the keyboard, from the top of the screen down: an element's label, description and suggested name, and for a text field only its hint, never its text. Password fields and data-sensitive elements give none, and names with fewer than 3 letters or digits, more than 4 words or more than 40 characters, or no letter, are left out. `ScreenSession.snapshotBuilds` and `snapshotMillis` count every snapshot that the session built and the time that the builds took, so that plan 3C's A71 session can measure what `keepFresh` costs (design D16, step 11); `ScreenSession.kt` is plan 3A's file alone. A device test starts listening and checks that `AppGraph` turns `keepFresh` on and off with listening, that a new screen's labels reach the boost words, and that a later change, which only the screen watcher sees, reaches them too; another checks with the real recogniser that sherpa-onnx keeps all eight phrases of the vocabulary; if it drops "Wi-Fi", Step 11 replaces it with "WiFi". No boost word is ever logged.

- [ ] **Step 1: Write the failing tests**

In `core/screen/src/test/kotlin/dev/kestrel/screen/read/SnapshotCacheTest.kt`, replace

```kotlin
import org.junit.Assert.assertEquals
```

with

```kotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
```

Replace

```kotlin
        cache.invalidate()

        assertEquals(2, cache.current())
    }
}
```

with

```kotlin
        cache.invalidate()

        assertEquals(2, cache.current())
    }

    @Test
    fun latestGivesTheNewestSnapshotWithoutWaitingOrBuilding() = runTest {
        val tracker = ScreenChangeTracker { testScheduler.currentTime }
        var builds = 0
        val cache = SnapshotCache(tracker) { ++builds }
        assertNull(cache.latest())
        cache.current()
        val changedAt = testScheduler.currentTime
        tracker.onScreenChanged()

        // The screen has changed, but latest() gives the snapshot that was built last, at once.
        assertEquals(1, cache.latest())
        assertEquals(1, builds)
        assertEquals(changedAt, testScheduler.currentTime)
    }
}
```

Create `app/src/test/kotlin/dev/kestrel/app/listen/BoostWordBudgetTest.kt`:

```kotlin
package dev.kestrel.app.listen

import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class BoostWordBudgetTest {
    @Test
    fun kestrelComesFirstThenTheScreenThenTheVocabularyThenTheApps() {
        val labels = (1..45).map { "Label $it" }
        val vocabulary = (1..10).map { "Phrase $it" }

        val words = BoostWordBudget.combine(listOf("Kestrel", "Revolut", "Phone"), labels, vocabulary)

        assertEquals(listOf("Kestrel") + labels.take(40) + vocabulary.take(8) + listOf("Revolut", "Phone"), words)
    }

    @Test
    fun aPhraseCountsOnceAtItsFirstPlaceIgnoringCase() {
        val words = BoostWordBudget.combine(listOf("Kestrel", "viber"), listOf("Viber", "kestrel", "Send"), listOf("send"))

        assertEquals(listOf("Kestrel", "Viber", "Send"), words)
    }

    @Test
    fun theScreenGivesTheNamesOfItsActionableElementsFromTheTopDown() {
        val snapshot = snapshot(
            element("b", top = 300, label = "Send later", clickable = true),
            element("a", top = 100, label = "Send", contentDescription = "Send message", clickable = true),
            element("c", top = 500, suggestedName = "compose", clickable = true),
            // A text field gives its hint, never what was typed into it.
            element("d", top = 700, label = "Message", text = "Dear Ana", hint = "Message", editable = true),
        )

        assertEquals(listOf("Send", "Send message", "Send later", "compose", "Message"), BoostWordBudget.screenNames(snapshot))
    }

    @Test
    fun theScreenGivesNoNameThatIsPrivateOutOfReachOrUnfitForABoost() {
        val snapshot = snapshot(
            element("a", top = 100, label = "Password", hint = "Password", editable = true, password = true),
            element("b", top = 200, label = "Card number", clickable = true, dataSensitive = true),
            element("c", top = 300, label = "Not a button"),
            element("d", top = 400, label = "Delete", clickable = true, enabled = false),
            element("e", top = 500, label = "Keyboard key", clickable = true, windowId = KEYBOARD),
            element("f", top = 600, label = "OK", clickable = true),
            element("g", top = 700, label = "Ana Petrović See you at five", clickable = true),
            element("h", top = 800, label = "x".repeat(41), clickable = true),
            element("i", top = 900, label = "123 456", clickable = true),
        )

        assertEquals(emptyList<String>(), BoostWordBudget.screenNames(snapshot))
    }

    private fun snapshot(vararg elements: ScreenElement) = ScreenSnapshot(
        takenAtMillis = 0,
        windows = listOf(
            WindowSummary(id = KEYBOARD, kind = WindowKind.INPUT_METHOD, bounds = IntRect(0, 1600, 1080, 2400), layer = 2),
            WindowSummary(id = APP, kind = WindowKind.APPLICATION, bounds = IntRect(0, 0, 1080, 2400), layer = 1),
        ),
        elements = elements.toList(),
    )

    private fun element(
        key: String,
        top: Int,
        label: String? = null,
        text: String? = null,
        contentDescription: String? = null,
        hint: String? = null,
        suggestedName: String? = null,
        clickable: Boolean = false,
        editable: Boolean = false,
        enabled: Boolean = true,
        password: Boolean = false,
        dataSensitive: Boolean = false,
        windowId: Int = APP,
    ) = ScreenElement(
        key = key,
        windowId = windowId,
        text = text,
        contentDescription = contentDescription,
        hint = hint,
        label = label,
        suggestedName = suggestedName,
        bounds = IntRect(0, top, 1080, top + 100),
        enabled = enabled,
        clickable = clickable,
        editable = editable,
        password = password,
        dataSensitive = dataSensitive,
    )

    private companion object {
        const val APP = 1
        const val KEYBOARD = 2
    }
}
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/voice/ScreenBoostWordsTest.kt`:

```kotlin
package dev.kestrel.app.voice

import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.listen.BoostWordBudget
import dev.kestrel.app.listen.BoostWordSource
import dev.kestrel.app.listen.ScreenBoostWords
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.commands.CommandVocabulary
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/**
 * The labels on the screen as boost words (design spec, section 8.3): the screen gives them from its newest snapshot,
 * AppGraph keeps that snapshot current while Kestrel listens, and the recogniser keeps them and every phrase of the
 * command vocabulary. The failure messages name only the vocabulary's phrases and the fixture app's labels.
 */
class ScreenBoostWordsTest {
    // Before ScreenServiceRule, so that a run without the models changes nothing on the device.
    @get:Rule(order = 0)
    val models = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.ASR, Engine.TTS)
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1)
    val screen = ScreenServiceRule()

    // The voice tests after this one run without Kestrel's accessibility service, as ActivationTest explains.
    @After
    fun turnOffTheService() = VoiceTests.turnOffAccessibilityService()

    @Test
    fun theLabelsOnTheScreenBecomeBoostWordsAndStayCurrentWhileKestrelListens() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val session = screen.session
        val words = ScreenBoostWords { session }

        // ScreenServiceRule.open built a snapshot of this screen.
        val views = words.words()
        // The messages never print the list: on a phone it would hold the labels of the status bar too.
        assertTrue("A name of the Views fixture is missing", views.containsAll(listOf("Send", "compose", "Remember me", "Message", "Show dialog")))
        // A password field, an element that takes no action, a disabled field and the title give no words.
        assertFalse("A name that is no boost word is there", views.any { it in listOf("Password", "Touch target", "Disabled field", "Views fixture") })

        // Nothing builds a snapshot of a new screen while Kestrel is off,
        screen.shell("am start -W -n ${ScreenServiceRule.FIXTURES}/.CommandsActivity")
        delay(1_000)
        assertFalse("Send later" in words.words())
        // but while Kestrel listens, AppGraph keeps the session's snapshot fresh: at once, and after each later change.
        VoiceTests.grantListeningPermissions()
        // Inside the try: a start that fails must not leave Kestrel on, with the clip microphone, for the later voice tests.
        try {
            // The accessibility service lets Kestrel start its microphone service from the background, as in RefusedReplyTest.
            VoiceTests.startListening(ClipMicrophone())
            assertTrue("keepFresh is off while Kestrel listens", VoiceTests.waitUntil(5_000) { session.keepFresh })
            withTimeout(5_000) { while ("Send later" !in words.words()) delay(100) }
            // Back to the Views fixture: only the screen watcher builds a snapshot of it now.
            screen.shell("input keyevent KEYCODE_BACK")
            withTimeout(5_000) { while ("Send later" in words.words()) delay(100) }
        } finally {
            VoiceTests.stopListening()
        }
        assertTrue("keepFresh stays on after Kestrel turned off", VoiceTests.waitUntil(5_000) { !session.keepFresh })
    }

    @Test
    fun theRecognizerKeepsTheVocabularyAndTheLabels() {
        runBlocking { VoiceTests.graph.voice.recognizer() }.use { recognizer ->
            val dropped = CommandVocabulary.BOOST.filter { recognizer.keptBoostWords(listOf(it)) == 0 }
            assertEquals("sherpa-onnx drops these phrases of the command vocabulary", emptyList<String>(), dropped)

            val words = BoostWordBudget.combine(listOf(BoostWordSource.NAME), listOf("Send", "Remember me", "Show dialog"), CommandVocabulary.BOOST)
            assertEquals(words.size, recognizer.keptBoostWords(words))
        }
    }
}
```

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceSessionFailuresTest.kt`, replace

```kotlin
import dev.kestrel.app.orchestrator.Event
```

with

```kotlin
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.commands.CommandVocabulary
```

Replace

```kotlin
            boostWords = { listOf("Kestrel", "Viber") },
```

with

```kotlin
            boostWords = { listOf("Kestrel", "Viber") },
            screenWords = { listOf("Send later") },
```

Replace

```kotlin
            assertEquals(listOf(listOf("Kestrel", "Viber")), recognizer.boostWords.toList())
```

with

```kotlin
            // Each utterance: the name, the screen's labels, the command vocabulary, then the session's app names.
            assertEquals(listOf(listOf("Kestrel", "Send later") + CommandVocabulary.BOOST + "Viber"), recognizer.boostWords.toList())
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 --continue :core:screen:testDebugUnitTest --tests dev.kestrel.screen.read.SnapshotCacheTest :app:testDebugUnitTest --tests dev.kestrel.app.listen.BoostWordBudgetTest :app:compileDebugAndroidTestKotlin`
Expected: `BUILD FAILED`, because the test sources do not compile: "Unresolved reference 'latest'" in `:core:screen`, "Unresolved reference 'BoostWordBudget'" in the JVM tests of `:app`, and "Unresolved reference 'ScreenBoostWords'", "Unresolved reference 'CommandVocabulary'" and "No parameter with name 'screenWords' found" in the device tests of `:app`.

- [ ] **Step 3: Give the snapshot cache its newest snapshot**

In `core/screen/src/main/kotlin/dev/kestrel/screen/read/SnapshotCache.kt`, replace

```kotlin
    /**
     * Makes the next [current] call build a new snapshot, for example before an action is retried. This also holds
```

with

```kotlin
    /**
     * The newest snapshot that was built, or null before the first build. It never waits and never builds, so it may be
     * older than the screen: the recogniser's boost words read it before each utterance (design spec, section 8.3), and
     * `ScreenSession.keepFresh` keeps it current while Kestrel listens.
     */
    fun latest(): T? = entry?.value

    /**
     * Makes the next [current] call build a new snapshot, for example before an action is retried. This also holds
```

- [ ] **Step 4: Keep the screen session's snapshot fresh while Kestrel listens**

In `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt`, replace

```kotlin
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
```

with

```kotlin
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
```

Replace

```kotlin
    /** The current snapshot. A new one is built only after the screen has changed. */
    val snapshots = SnapshotCache(tracker) { withContext(Dispatchers.IO) { reader.read() } }
```

with

```kotlin
    private val builds = AtomicInteger()
    private val buildMillis = AtomicLong()

    /** The current snapshot. A new one is built only after the screen has changed. */
    val snapshots = SnapshotCache(tracker) {
        val started = SystemClock.elapsedRealtime()
        withContext(Dispatchers.IO) { reader.read() }.also {
            builds.incrementAndGet()
            buildMillis.addAndGet(SystemClock.elapsedRealtime() - started)
        }
    }

    /**
     * How many snapshots this session has built, and how long the builds took in all, so that the A71 session can
     * measure what [keepFresh] costs (design spec, section 8.3).
     */
    val snapshotBuilds: Int get() = builds.get()
    val snapshotMillis: Long get() = buildMillis.get()
```

Replace

```kotlin
    /** Emits when the owner touches the screen while the touch watcher runs (design spec, section 14.1). */
    val ownerTouches: SharedFlow<Unit> get() = watcher.ownerTouches
```

with

```kotlin
    /** Emits when the owner touches the screen while the touch watcher runs (design spec, section 14.1). */
    val ownerTouches: SharedFlow<Unit> get() = watcher.ownerTouches

    /**
     * While true, the session builds a snapshot after each settled change of the screen, so that `snapshots.latest()`
     * stays current for the boost words of each utterance (design spec, section 8.3). AppGraph sets it while a voice
     * session is open and Kestrel listens. Any thread may set it.
     */
    @Volatile
    var keepFresh: Boolean = false
        set(value) {
            val starts = value && !field
            field = value
            // The newest snapshot may be old by now, so the first one is built at once.
            if (starts) scope.launch { snapshots.current() }
        }
```

Replace

```kotlin
                if (numbersMode == null && labelsShownOn == null && whichOneShownOn == null) return@collect
```

with

```kotlin
                if (!keepFresh && numbersMode == null && labelsShownOn == null && whichOneShownOn == null) return@collect
```

- [ ] **Step 5: Add the command vocabulary, the budget and the screen's words**

Create `core/commands/src/main/kotlin/dev/kestrel/commands/CommandVocabulary.kt`:

```kotlin
package dev.kestrel.commands

/**
 * Words of the instant commands that the recogniser may miss without help, boosted with every utterance (design spec,
 * section 8.3). The common command words, such as "tap", "scroll" or "back", need no boost. A device test,
 * `ScreenBoostWordsTest`, checks that sherpa-onnx keeps every one of them.
 */
object CommandVocabulary {
    val BOOST: List<String> = listOf(
        "Bluetooth",
        "Wi-Fi",
        "Quick Settings",
        "unselect",
        "uppercase",
        "lowercase",
        "semicolon",
        "magnification",
    )
}
```

Create `app/src/main/kotlin/dev/kestrel/app/listen/BoostWordBudget.kt`:

```kotlin
package dev.kestrel.app.listen

import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind

/**
 * The boost words of one utterance (design spec, section 8.3): "Kestrel", then at most [MAX_SCREEN] labels from the
 * screen, then at most [MAX_VOCABULARY] phrases of the command vocabulary, then the session's app names. sherpa-onnx
 * gets at most 100 usable phrases, so the cap drops the system apps first, as before. Never log a boost word.
 */
internal object BoostWordBudget {
    const val MAX_SCREEN = 40
    const val MAX_VOCABULARY = 8

    /** At most this many words and characters in a label; a longer one is the screen's text, not a name. */
    private const val MAX_WORDS = 4
    private const val MAX_LENGTH = 40

    /**
     * Puts the lists together. [fixed] is the session's list as [BoostWordSource] reads it, "Kestrel" first and the app
     * names after it. A phrase that came earlier, ignoring case, counts once.
     */
    fun combine(fixed: List<String>, screen: List<String>, vocabulary: List<String>): List<String> =
        (listOf(BoostWordSource.NAME) + screen.take(MAX_SCREEN) + vocabulary.take(MAX_VOCABULARY) + fixed)
            .distinctBy { it.lowercase() }

    /**
     * The names that [snapshot] offers as boost words, from the top of the screen down: those of the actionable
     * elements outside the keyboard, that is an element's label, description and suggested name, and for a text field
     * only its hint, never what was typed into it. Password fields and data-sensitive elements give none. A name with
     * fewer than [BoostWordSource.SHORTEST] letters or digits, more than [MAX_WORDS] words or [MAX_LENGTH] characters, or
     * no letter, such as a time or a phone number, is left out, and a repeated name counts once, ignoring case.
     */
    fun screenNames(snapshot: ScreenSnapshot): List<String> {
        val keyboard = snapshot.windows.filter { it.kind == WindowKind.INPUT_METHOD }.map { it.id }.toSet()
        return snapshot.elements
            .filter { it.isActionable && it.windowId !in keyboard && !it.password && !it.dataSensitive }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .flatMap(::namesOf)
            .map { it.trim() }
            .filter(::fits)
            .distinctBy { it.lowercase() }
    }

    private fun namesOf(element: ScreenElement): List<String> =
        if (element.editable) listOfNotNull(element.hint) else listOfNotNull(element.label, element.contentDescription, element.suggestedName)

    private fun fits(name: String): Boolean =
        name.count { it.isLetterOrDigit() } >= BoostWordSource.SHORTEST &&
            name.length <= MAX_LENGTH &&
            name.split(' ').count { it.isNotEmpty() } <= MAX_WORDS &&
            name.any { it.isLetter() }
}
```

Create `app/src/main/kotlin/dev/kestrel/app/listen/ScreenBoostWords.kt`:

```kotlin
package dev.kestrel.app.listen

import dev.kestrel.screen.ScreenSession

/**
 * The labels on the screen as boost words of an utterance (design spec, section 8.3), from the newest snapshot that
 * [screen]'s session has built. It never builds a snapshot, so the recogniser never waits for the screen;
 * `ScreenSession.keepFresh` keeps that snapshot current while Kestrel listens. Without Kestrel's accessibility service
 * there are no labels. Never log a boost word.
 */
internal class ScreenBoostWords(private val screen: () -> ScreenSession?) {
    fun words(): List<String> = screen()?.snapshots?.latest()?.snapshot?.let(BoostWordBudget::screenNames).orEmpty()
}
```

- [ ] **Step 6: Pass the screen's words and the vocabulary to the recogniser with each utterance**

In `app/src/main/kotlin/dev/kestrel/app/listen/VoiceSession.kt`, replace

```kotlin
import dev.kestrel.app.orchestrator.Event
```

with

```kotlin
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.commands.CommandVocabulary
```

Replace

```kotlin
 * session. [words] are the boost words of every utterance of the session (section 8.3).
 */
```

with

```kotlin
 * session. [words] are the session's own boost words, "Kestrel" and the app names, and [screenWords] gives the labels on
 * the screen before each utterance; [BoostWordBudget] puts them together with the command vocabulary (section 8.3).
 */
```

Replace

```kotlin
    override val voice: VoiceOptions? = null,
) : Session {
```

with

```kotlin
    override val voice: VoiceOptions? = null,
    private val screenWords: suspend () -> List<String> = { emptyList() },
) : Session {
```

Replace

```kotlin
        boostWords = { words },
```

with

```kotlin
        boostWords = { BoostWordBudget.combine(words, screenWords(), CommandVocabulary.BOOST) },
```

Replace

```kotlin
         * change of the installed apps counts from the next time that Kestrel turns on. The other loaders are
         * parameters only for the tests.
         */
```

with

```kotlin
         * change of the installed apps counts from the next time that Kestrel turns on. [screenWords] gives the labels on
         * the screen before each utterance. The other loaders are parameters only for the tests.
         */
```

Replace

```kotlin
            boostWords: suspend () -> List<String> = { listOf(BoostWordSource.NAME) },
            loadSpeechDetector: suspend () -> SpeechDetector = { engines.speechDetector() },
```

with

```kotlin
            boostWords: suspend () -> List<String> = { listOf(BoostWordSource.NAME) },
            screenWords: suspend () -> List<String> = { emptyList() },
            loadSpeechDetector: suspend () -> SpeechDetector = { engines.speechDetector() },
```

Replace

```kotlin
                    context, detector, spotter, recognizer, chosen.synthesizer, microphone, listener, words, chosen.options,
                )
```

with

```kotlin
                    context, detector, spotter, recognizer, chosen.synthesizer, microphone, listener, words, chosen.options,
                    screenWords,
                )
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import dev.kestrel.app.listen.ProblemNotifications
```

with

```kotlin
import dev.kestrel.app.listen.ProblemNotifications
import dev.kestrel.app.listen.ScreenBoostWords
```

Replace

```kotlin
import kotlinx.coroutines.flow.asStateFlow
```

with

```kotlin
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
```

Replace

```kotlin
                            boostWords,
```

with

```kotlin
                            boostWords,
                            // The labels on the screen before each utterance, from the newest snapshot (section 8.3).
                            screenWords = { screenLabels.words() },
```

Replace

```kotlin
            microphones = { MicCapture(appContext, earbud = { listening.currentOptions.value.earbudMicrophone }) },
        )
    }
```

with

```kotlin
            microphones = { MicCapture(appContext, earbud = { listening.currentOptions.value.earbudMicrophone }) },
        ).also(::keepScreenFresh)
    }
```

Replace

```kotlin
    /** Kestrel's screen layer while its accessibility service is connected, otherwise null. */
    val screen: StateFlow<ScreenSession?> = screens.asStateFlow()
```

with

```kotlin
    /** Kestrel's screen layer while its accessibility service is connected, otherwise null. */
    val screen: StateFlow<ScreenSession?> = screens.asStateFlow()

    /** The labels on the screen as boost words (section 8.3). */
    private val screenLabels = ScreenBoostWords { screen.value }

    /**
     * While a voice session is open and Kestrel listens, the screen session builds a snapshot after each settled change
     * of the screen, so that each utterance's boost words read a current one without waiting (section 8.3). A screen
     * session that connects while Kestrel listens is kept fresh too.
     */
    private fun keepScreenFresh(listening: Listening) {
        scope.launch {
            combine(screens, listening.state, listening.sessionOpen) { session, state, open ->
                session to (open && state.mode == Mode.LISTENING)
            }.collect { (session, fresh) -> session?.keepFresh = fresh }
        }
    }
```

- [ ] **Step 7: Run the JVM tests to verify that they pass**

Run: `scripts\gradle.ps1 :core:screen:testDebugUnitTest --tests dev.kestrel.screen.read.SnapshotCacheTest :app:testDebugUnitTest --tests dev.kestrel.app.listen.BoostWordBudgetTest`
Expected: `BUILD SUCCESSFUL`, with 6 tests in `SnapshotCacheTest` (5 and `latestGivesTheNewestSnapshotWithoutWaitingOrBuilding`) and 4 in `BoostWordBudgetTest`.

- [ ] **Step 8: Update the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 8.3, replace

```markdown
The labels on screen and the command vocabulary come with sub-project 3, and the aliases and the contact names with sub-project 5.
```

with

```markdown
Sub-project 3 adds, before each utterance and after "Kestrel", up to 40 labels from the screen and up to 8 phrases of the command vocabulary, so the app names come after them. The labels are the names of the actionable elements outside the keyboard, from the top of the screen down: an element's label, description and suggested name, and for a text field only its hint, never what was typed into it; password fields and data-sensitive elements give none, and a label with fewer than three letters or digits, more than four words or more than 40 characters, or no letter, such as a time or a phone number, is left out. Kestrel reads them from its newest snapshot of the screen without waiting for a new one; while a voice session is open and Kestrel listens, the screen layer builds a snapshot after each settled change of the screen, so that this snapshot is current. The aliases and the contact names come with sub-project 5.
```

Replace

```markdown
- A transcript is a task when its first word matches "Kestrel", allowing near misses
```

with

```markdown
- The effect of the screen's labels and the command vocabulary on the command accuracy is measured in sub-project 3.
- A transcript is a task when its first word matches "Kestrel", allowing near misses
```

- [ ] **Step 9: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (`:core:screen` 55, `:app` 314, `:core:commands` 46, `:core:voice` 140), lint in every module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests compile.

- [ ] **Step 10: Run the voice device tests**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\voice-test.ps1 -Serial emulator-5554`
Expected: "Voice tests: 83 run, 0 failed, 0 errors, 0 skipped." (the 81 after plan 2D, which no earlier task of this plan changes, and the two of `ScreenBoostWordsTest`; and `VoiceSessionFailuresTest` keeps its 7), and no warning from the restores at the end. If `theLabelsOnTheScreenBecomeBoostWordsAndStayCurrentWhileKestrelListens` fails at "keepFresh is off while Kestrel listens", the wiring of Step 6 in `AppGraph` is wrong; if it times out after the Back key, the screen watcher of Step 4 is.

- [ ] **Step 11: Only if `theRecognizerKeepsTheVocabularyAndTheLabels` failed in Step 10**

Its message names the phrases that sherpa-onnx drops. If it names only "Wi-Fi", in `core/commands/src/main/kotlin/dev/kestrel/commands/CommandVocabulary.kt` replace

```kotlin
        "Wi-Fi",
```

with

```kotlin
        // sherpa-onnx drops "Wi-Fi" (ScreenBoostWordsTest).
        "WiFi",
```

and run Step 10's command again; expected as in Step 10. If it names another phrase, stop and ask the controller, because design D13 lists the vocabulary.

- [ ] **Step 12: Run the command device tests**

`keepFresh` now builds a snapshot after each change of the screen while the command tests listen.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\command-test.ps1 -Serial emulator-5554`
Expected: `BUILD SUCCESSFUL` and "Command tests: 31 run, 0 failed, 0 errors, 0 skipped." (unchanged since Task 7), with no warning from the restores at the end.

- [ ] **Step 13: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 14: Commit**

```powershell
git add core/commands/src/main/kotlin/dev/kestrel/commands/CommandVocabulary.kt core/screen/src/main/kotlin/dev/kestrel/screen/read/SnapshotCache.kt core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt core/screen/src/test/kotlin/dev/kestrel/screen/read/SnapshotCacheTest.kt app/src/main/kotlin/dev/kestrel/app/listen/BoostWordBudget.kt app/src/main/kotlin/dev/kestrel/app/listen/ScreenBoostWords.kt app/src/main/kotlin/dev/kestrel/app/listen/VoiceSession.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/test/kotlin/dev/kestrel/app/listen/BoostWordBudgetTest.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/ScreenBoostWordsTest.kt app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceSessionFailuresTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(voice): boost the labels on the screen and the command vocabulary with each utterance, from a snapshot that the screen session keeps fresh while Kestrel listens" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 9: The options, help and the page "Instant commands"

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/settings/CommandsPage.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (imports, `SettingKeys.COMMANDS`, `commandOptions`, the environment's `options` and `openSettings`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt` (imports, `pageRequest`, `onCreate`, `onNewIntent`, `follow`, the companion object), `app/src/main/AndroidManifest.xml` (`singleTop`, the marker `    <!-- Plan 3C: permissions -->`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt` (`SettingsPage.COMMANDS`, `PageRequest`, `SettingsApp`'s `request`, the page, the home page's entry), `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt` (`commandOptions`, `setCommandOptions`), `app/src/main/res/values/strings.xml` (the page's strings and the markers `    <!-- Plan 3B strings -->` and `    <!-- Plan 3C strings -->`)
- Modify: `app/src/main/kotlin/dev/kestrel/app/commands/ScreenCommands.kt` (imports, `run`, `bare`, `help`), `app/src/main/kotlin/dev/kestrel/app/commands/CommandTexts.kt` (`HELP`), `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt` (`hideFeedback`)
- Modify: `docs/superpowers/specs/2026-11-12-kestrel-design.md` (section 15 and Appendix A's help row), `README.md` (the status, the test example, the page "Instant commands", the section `## Instant commands` with the markers of plans 3B and 3C)
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/MainActivityTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/commands/HelpCommandsTest.kt` (new), `app/src/androidTest/kotlin/dev/kestrel/app/commands/TouchCommandsTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/commands/OverlayCommandsTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/commands/CommandTests.kt` (`start` resets the options)

**Interfaces:**
- Consumes: Task 2's `Command.Help(kind)`, `CommandHelp.groups` (`HelpGroup.title`, `examples`); Task 4's `CommandOptions(requireVerbs, numbers)`, `CommandEnvironment.options` and `openSettings(page, permission)`, `AppGraph`'s `commandOptions` and its `CommandEnvironment`, `ScreenCommands.bare`, `CommandTests` (`only`, `start`, `graph`, `say`, `awaitSaid`) and `TouchCommandsTest` (its `@Before` and `awaitStatus`); Task 7's `OverlayCommandsTest` and its `badge`; Task 3's `FeedbackBar.hide()` (the overlay) and `ScreenSession.releaseFeedback()`; `SettingKey(name, serializer, default)`, `SettingsStore.observe(key)`, `get`, `set`; `PageHeader`, `PageEntry`, `ScrollingPage`; `SettingsPage`; `MainActivity.askForPermissions(vararg)`; `ScreenServiceRule.KESTREL`.
- Produces: `SettingKeys.COMMANDS`; `internal val AppGraph.commandOptions: StateFlow<CommandOptions>` (in place of Task 4's private `MutableStateFlow`); `SettingsPage.COMMANDS`; `class PageRequest(val page: SettingsPage)`; `SettingsApp(status, data, onAction, onCheckModels, request: PageRequest? = null, onExportLogs)`; `val SettingsData.commandOptions: Flow<CommandOptions>` and `suspend fun SettingsData.setCommandOptions(options: CommandOptions)`; `internal fun CommandsPage(data: SettingsData, onBack: () -> Unit)`; `MainActivity.EXTRA_PAGE`, `EXTRA_ASK_PERMISSION` and `ASKABLE` (`setOf(Manifest.permission.READ_CONTACTS)`), which plan 3C's first use of `READ_CONTACTS` passes through `CommandEnvironment.openSettings(null, Manifest.permission.READ_CONTACTS)`; `CommandTexts.HELP`; `suspend fun ScreenSession.hideFeedback()` (plan 3C's screenshot); the markers `    <!-- Plan 3C: permissions -->` in the app's manifest, `    <!-- Plan 3B strings -->` and `    <!-- Plan 3C strings -->` in `strings.xml`, and `<!-- Plan 3B: dictation and editing -->` and `<!-- Plan 3C: apps, phone settings and calls -->` at the end of the README's section `## Instant commands`.

Design D14, D7 (help) and D18. The options of the instant commands, "Require verbs" (off by default) and the numbers on all elements or only on those without a name (all by default), are one `CommandOptions` setting, stored encrypted under `SettingKeys.COMMANDS`; `AppGraph` keeps them as a `StateFlow` that the executor reads at each command, so a change counts from the next command. With "Require verbs" on, a bare label or number never taps, also not as the fallback of an unrecognised command. The new page "Instant commands" holds both options and the list of `CommandHelp.groups`, which plans 3B and 3C fill with their own examples. "What can I say?", "show all commands", "show commands" and "open tutorial" open Kestrel's settings app on that page and say a short summary; there is no separate tutorial, and help is never repeated by "again". So that the page also opens while the settings app is in front, `MainActivity` becomes `singleTop` and reads two extras in `onCreate` (only at its first start, so that a rotation keeps the owner's page, and not when the owner reopens Kestrel from the recent apps, which repeats the task's first intent) and in `onNewIntent`: `EXTRA_PAGE`, the name of a `SettingsPage`, and `EXTRA_ASK_PERMISSION`, a permission of the fixed set `ASKABLE`, which plan 3C uses for `READ_CONTACTS` once it declares the permission in the manifest; any app may start `MainActivity`, so an unknown page or another permission is ignored. `SettingsApp` takes the page of a `PageRequest` at its start and switches to it whenever a new request arrives; `PageRequest` is not a data class, so that the same page asked for twice still switches. `ScreenSession.hideFeedback()` hides the bar at once, also a kept text, for plan 3C's screenshot. The markers that plans 3B and 3C replace go into the app's manifest, `strings.xml` and the README here, and the spec's section 15 and Appendix A's help row describe the page and help.

- [ ] **Step 1: Write the failing tests**

In `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, replace

```kotlin
import androidx.compose.material3.MaterialTheme
```

with

```kotlin
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
```

Replace

```kotlin
import androidx.compose.ui.test.onAllNodesWithText
```

with

```kotlin
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
```

Replace

```kotlin
import dev.kestrel.app.R
import dev.kestrel.app.listen.ListeningOptions
```

with

```kotlin
import dev.kestrel.app.R
import dev.kestrel.app.commands.CommandOptions
import dev.kestrel.app.listen.ListeningOptions
import dev.kestrel.commands.CommandHelp
import dev.kestrel.screen.overlay.NumbersMode
```

Replace

```kotlin
    private fun openVoicePage() {
```

with

```kotlin
    @Test
    fun theCommandsPageChangesTheOptionsAndListsTheCommands() {
        show(NOTHING_SET_UP)
        compose.onNodeWithText(text(R.string.settings_commands)).performScrollTo().performClick()

        compose.onNodeWithText(text(R.string.commands_numbers_all)).assertIsSelected()
        compose.onNodeWithText(text(R.string.commands_require_verbs)).assertIsOff().performClick()
        compose.onNodeWithText(text(R.string.commands_numbers_unlabeled)).performScrollTo().performClick()

        compose.onNodeWithText(text(R.string.commands_require_verbs)).assertIsOn()
        compose.onNodeWithText(text(R.string.commands_numbers_unlabeled)).assertIsSelected()
        assertEquals(CommandOptions(requireVerbs = true, numbers = NumbersMode.UNLABELED), data.commandOptions.value)
        // The list that "What can I say?" opens: each group of commands with its examples.
        val group = CommandHelp.groups.first()
        compose.onAllNodesWithText(group.title).onFirst().performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(group.examples.first()).onFirst().performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aRequestedPageOpensAndTheSameRequestAgainSwitchesBackToIt() {
        var request by mutableStateOf<PageRequest?>(PageRequest(SettingsPage.COMMANDS))
        compose.setContent { MaterialTheme { SettingsApp(NOTHING_SET_UP, data, { actions += it }, { checks++ }, request) { exports++ } } }
        compose.onNodeWithText(text(R.string.commands_list)).assertExists()

        compose.onNodeWithText(text(R.string.settings_back)).performClick()
        compose.onNodeWithText(text(R.string.settings_checklist)).assertIsDisplayed()
        // A second "What can I say?" while the app shows another page.
        request = PageRequest(SettingsPage.COMMANDS)

        compose.onNodeWithText(text(R.string.commands_list)).assertExists()
    }

    private fun openVoicePage() {
```

Replace

```kotlin
        override val listening = MutableStateFlow(ListeningOptions())
        override val version = "9.9.9 (99)"
```

with

```kotlin
        override val listening = MutableStateFlow(ListeningOptions())
        override val commandOptions = MutableStateFlow(CommandOptions())
        override val version = "9.9.9 (99)"
```

Replace

```kotlin
        override suspend fun setListening(options: ListeningOptions) {
            listening.value = options
        }
```

with

```kotlin
        override suspend fun setListening(options: ListeningOptions) {
            listening.value = options
        }

        override suspend fun setCommandOptions(options: CommandOptions) {
            commandOptions.value = options
        }
```

In `app/src/androidTest/kotlin/dev/kestrel/app/MainActivityTest.kt`, replace

```kotlin
import androidx.annotation.StringRes
```

with

```kotlin
import android.content.Intent
import androidx.annotation.StringRes
```

Replace

```kotlin
import androidx.compose.ui.test.SemanticsMatcher
import dev.kestrel.privacy.logs.ActivityEvent
```

with

```kotlin
import androidx.compose.ui.test.SemanticsMatcher
import dev.kestrel.app.commands.CommandOptions
import dev.kestrel.app.settings.SettingsPage
import dev.kestrel.privacy.logs.ActivityEvent
```

Replace

```kotlin
import androidx.compose.ui.test.isOn
```

with

```kotlin
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isOn
```

Replace

```kotlin
import dev.kestrel.safety.SafetySettings
```

with

```kotlin
import dev.kestrel.safety.SafetySettings
import dev.kestrel.screen.overlay.NumbersMode
```

Replace

```kotlin
    @After
    fun turnTestModeBackOn() = runBlocking {
        graph.settings.set(SettingKeys.SAFETY, SafetySettings(testMode = true))
    }
```

with

```kotlin
    @After
    fun turnTestModeBackOn() = runBlocking {
        graph.settings.set(SettingKeys.SAFETY, SafetySettings(testMode = true))
    }

    @After
    fun putTheCommandOptionsBack() = runBlocking {
        graph.settings.set(SettingKeys.COMMANDS, CommandOptions())
    }

    @Test
    fun theCommandsPageStoresTheOptions() {
        runBlocking { graph.settings.set(SettingKeys.COMMANDS, CommandOptions()) }
        compose.onNodeWithText(text(R.string.settings_commands)).performScrollTo().performClick()
        // The rows are disabled until the stored options have arrived from the settings store; a click before is lost.
        await(hasText(text(R.string.commands_require_verbs)) and isEnabled())

        compose.onNodeWithText(text(R.string.commands_require_verbs)).performClick()
        compose.onNodeWithText(text(R.string.commands_numbers_unlabeled)).performScrollTo().performClick()

        val expected = CommandOptions(requireVerbs = true, numbers = NumbersMode.UNLABELED)
        compose.waitUntil(5_000) { runBlocking { graph.settings.get(SettingKeys.COMMANDS) } == expected }
    }

    @Test
    fun anIntentWithAPageOpensThatPageInTheRunningApp() {
        await(hasText(text(R.string.settings_checklist)))

        // As "What can I say?" does while the settings app is in front: MainActivity is singleTop.
        compose.runOnUiThread {
            compose.activity.startActivity(
                Intent(compose.activity, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_PAGE, SettingsPage.COMMANDS.name)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }

        await(hasText(text(R.string.commands_list)))
    }
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/commands/HelpCommandsTest.kt`:

```kotlin
package dev.kestrel.app.commands

import androidx.annotation.StringRes
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.R
import dev.kestrel.app.listen.ListeningTexts
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.KESTREL
import dev.kestrel.commands.Outcome
import dev.kestrel.screen.model.ScreenElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * "What can I say?" and its kin open Kestrel's settings app on the page "Instant commands" and say a short summary
 * (design spec, Appendix A, "Help"). Like every command test, they run only through scripts\command-test.ps1.
 */
class HelpCommandsTest {
    // Before ScreenServiceRule: without kestrelCommands=true the test skips before it changes anything, and its @After
    // does not run either, so a skipped test never presses Home.
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
    fun whatCanISayOpensTheListOfCommandsAndSaysASummary() = runBlocking<Unit> {
        screen.open("CommandsActivity", "Commands fixture")

        assertEquals(Outcome.DONE, CommandTests.say("what can I say").outcome)

        awaitKestrel(R.string.commands_list)
        CommandTests.awaitSaid(CommandTexts.HELP)
        // Help is not repeatable, and turning Kestrel off before the test forgot any earlier command. "Again" with nothing to
        // repeat ends no command, so the test sends it and waits for the bar.
        CommandTests.send("again")
        withTimeout(5_000) { while (screen.session.feedbackText != ListeningTexts.NOTHING_TO_REPEAT) delay(50) }
    }

    @Test
    fun theOtherHelpPhrasesOpenTheListAgainWhileTheSettingsAppShowsAnotherPage() = runBlocking<Unit> {
        screen.open("CommandsActivity", "Commands fixture")
        assertEquals(Outcome.DONE, CommandTests.say("show all commands").outcome)
        awaitKestrel(R.string.commands_list)

        // Back to the settings app's home page, which stays in front; MainActivity is singleTop.
        screen.session.actions.tap(awaitKestrel(R.string.settings_back).key)
        awaitKestrel(R.string.settings_checklist)
        assertEquals(Outcome.DONE, CommandTests.say("open tutorial").outcome)
        awaitKestrel(R.string.commands_list)

        screen.session.actions.tap(awaitKestrel(R.string.settings_back).key)
        awaitKestrel(R.string.settings_checklist)
        assertEquals(Outcome.DONE, CommandTests.say("show commands").outcome)
        awaitKestrel(R.string.commands_list)
    }

    /**
     * Waits for an element of Kestrel's settings app whose text starts with the text [id]. A page entry's card joins its
     * title and its summary into one text.
     */
    private suspend fun awaitKestrel(@StringRes id: Int): ScreenElement =
        screen.awaitElement { it.packageName == KESTREL && it.label?.startsWith(context.getString(id)) == true }
}
```

In `app/src/androidTest/kotlin/dev/kestrel/app/commands/TouchCommandsTest.kt`, replace

```kotlin
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
```

with

```kotlin
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
```

Replace

```kotlin
    private suspend fun awaitBar(text: String) = withTimeout(5_000) { while (screen.session.feedbackText != text) delay(50) }
```

with

```kotlin
    @Test
    fun requireVerbsKeepsANameAloneFromTapping() = runBlocking<Unit> {
        val settings = CommandTests.graph.settings
        settings.set(SettingKeys.COMMANDS, CommandOptions(requireVerbs = true))
        try {
            withTimeout(5_000) { while (!CommandTests.graph.commandOptions.value.requireVerbs) delay(50) }

            assertEquals(Outcome.IGNORED, CommandTests.say("Send later").outcome)
            assertEquals(Outcome.DONE, CommandTests.say("tap Send later").outcome)
            awaitStatus("Clicked Send later")
        } finally {
            settings.set(SettingKeys.COMMANDS, CommandOptions())
            withTimeout(5_000) { while (CommandTests.graph.commandOptions.value != CommandOptions()) delay(50) }
        }
    }

    private suspend fun awaitBar(text: String) = withTimeout(5_000) { while (screen.session.feedbackText != text) delay(50) }
```

In `app/src/androidTest/kotlin/dev/kestrel/app/commands/CommandTests.kt`, replace

```kotlin
import dev.kestrel.app.KestrelApplication
```

with

```kotlin
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.SettingKeys
```

Replace

```kotlin
        VoiceTests.grantListeningPermissions()
```

with

```kotlin
        VoiceTests.grantListeningPermissions()
        // A test process that died may have left other options stored, and command-test.ps1 does not reset Kestrel's
        // settings.
        runBlocking { graph.settings.set(SettingKeys.COMMANDS, CommandOptions()) }
        check(VoiceTests.waitUntil(5_000) { graph.commandOptions.value == CommandOptions() }) { "The options did not reset" }
```

In `app/src/androidTest/kotlin/dev/kestrel/app/commands/OverlayCommandsTest.kt`, replace

```kotlin
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.commands.Outcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
```

with

```kotlin
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.commands.Outcome
import dev.kestrel.screen.overlay.NumbersMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
```

Replace

```kotlin
    private suspend fun awaitStatus(text: String) = screen.awaitElement { it.viewId == "$FIXTURES:id/status" && it.label == text }

    /** The number on the element with [key], once the numbers have followed the screen. */
```

with

```kotlin
    @Test
    fun theNumbersSettingPutsNumbersOnlyOnElementsWithoutAName() = runBlocking<Unit> {
        val settings = CommandTests.graph.settings
        settings.set(SettingKeys.COMMANDS, CommandOptions(numbers = NumbersMode.UNLABELED))
        try {
            withTimeout(5_000) { while (CommandTests.graph.commandOptions.value.numbers != NumbersMode.UNLABELED) delay(50) }
            screen.open("CommandsActivity", "Commands fixture")
            val seekBar = screen.awaitElement { it.viewId == "$FIXTURES:id/seek_bar" }
            val save = screen.awaitElement { it.label == "Save" }

            assertEquals(Outcome.DONE, CommandTests.say("show numbers").outcome)

            val numbered = screen.session.numberBadges.map { it.key }
            assertTrue("The seek bar, which has no name, has no number", seekBar.key in numbered)
            assertFalse("Save has a number", save.key in numbered)
        } finally {
            settings.set(SettingKeys.COMMANDS, CommandOptions())
            withTimeout(5_000) { while (CommandTests.graph.commandOptions.value != CommandOptions()) delay(50) }
        }
    }

    @Test
    fun hideFeedbackHidesAKeptTextAndLeavesTheNumbers() = runBlocking<Unit> {
        screen.open("CommandsActivity", "Commands fixture")
        val session = screen.session
        session.showNumbers()

        // In one go on the main thread, so that no text of Kestrel's own comes in between.
        val shown = withContext(Dispatchers.Main) {
            session.showFeedback("Calling", keep = true)
            session.hideFeedback()
            session.feedbackText
        }

        assertNull(shown)
        assertTrue(session.numberBadges.isNotEmpty())
    }

    private suspend fun awaitStatus(text: String) = screen.awaitElement { it.viewId == "$FIXTURES:id/status" && it.label == text }

    /** The number on the element with [key], once the numbers have followed the screen. */
```

- [ ] **Step 2: Run the tests to verify that they fail**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin`
Expected: `BUILD FAILED`, because the device tests do not compile: "'commandOptions' overrides nothing", "Unresolved reference 'PageRequest'", "Unresolved reference 'COMMANDS'", "Unresolved reference 'EXTRA_PAGE'", "Unresolved reference 'commands_list'", "Unresolved reference 'HELP'", "Unresolved reference 'hideFeedback'" and "Cannot access 'commandOptions'".

- [ ] **Step 3: Store the options and add the page "Instant commands"**

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
    /** The owner's choices for listening (design spec, sections 8.5, 8.7, 8.8 and 15). */
    val LISTENING_OPTIONS = SettingKey("listening-options", ListeningOptions.serializer(), ListeningOptions())
}
```

with

```kotlin
    /** The owner's choices for listening (design spec, sections 8.5, 8.7, 8.8 and 15). */
    val LISTENING_OPTIONS = SettingKey("listening-options", ListeningOptions.serializer(), ListeningOptions())

    /** The options of the instant commands: "Require verbs" and where the numbers go (design spec, sections 9.8 and 15). */
    val COMMANDS = SettingKey("commands", CommandOptions.serializer(), CommandOptions())
}
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt`, replace

```kotlin
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.listen.ListeningOptions
```

with

```kotlin
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.commands.CommandOptions
import dev.kestrel.app.listen.ListeningOptions
```

Replace

```kotlin
    suspend fun setListening(options: ListeningOptions)

    suspend fun activityLog(): List<LogEntry<ActivityEvent>>
```

with

```kotlin
    suspend fun setListening(options: ListeningOptions)

    /** The options of the instant commands (design spec, sections 9.5, 9.8 and 15). */
    val commandOptions: Flow<CommandOptions>

    suspend fun setCommandOptions(options: CommandOptions)

    suspend fun activityLog(): List<LogEntry<ActivityEvent>>
```

Replace

```kotlin
    override suspend fun setListening(options: ListeningOptions) = write {
        graph.settings.set(SettingKeys.LISTENING_OPTIONS, options)
    }
```

with

```kotlin
    override suspend fun setListening(options: ListeningOptions) = write {
        graph.settings.set(SettingKeys.LISTENING_OPTIONS, options)
    }

    override val commandOptions: Flow<CommandOptions> = graph.settings.observe(SettingKeys.COMMANDS)

    override suspend fun setCommandOptions(options: CommandOptions) = write {
        graph.settings.set(SettingKeys.COMMANDS, options)
    }
```

Create `app/src/main/kotlin/dev/kestrel/app/settings/CommandsPage.kt`:

```kotlin
package dev.kestrel.app.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.kestrel.app.R
import dev.kestrel.app.commands.CommandOptions
import dev.kestrel.commands.CommandHelp
import dev.kestrel.screen.overlay.NumbersMode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * The page "Instant commands" (design spec, sections 9.4, 9.5, 9.8 and 15): "Require verbs", where the numbers go, and
 * the list of commands that "What can I say?" opens. Each choice counts from the next command.
 */
@Composable
internal fun CommandsPage(data: SettingsData, onBack: () -> Unit) {
    val stored by data.commandOptions.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    // The options as this page wrote them last, so that a second change builds on the first before the store reports it.
    var written by remember { mutableStateOf<CommandOptions?>(null) }
    val options = written ?: stored
    // Stores the current options with one change, as the page "Listening and activation" does: each write is queued at
    // once, so the store keeps the order of the changes, and a write is made also when the owner leaves the page at once.
    val change: ((CommandOptions) -> CommandOptions) -> Unit = { edit ->
        (written ?: stored)?.let { current ->
            val next = edit(current)
            written = next
            scope.launch(start = CoroutineStart.UNDISPATCHED) { data.setCommandOptions(next) }
        }
    }
    PageHeader(stringResource(R.string.settings_commands), onBack)
    // The whole row toggles the switch, so that its title names it for Voice Access and TalkBack.
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = options?.requireVerbs == true, enabled = options != null, role = Role.Switch) { on ->
                change { it.copy(requireVerbs = on) }
            }
            .padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.commands_require_verbs), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Switch(checked = options?.requireVerbs == true, onCheckedChange = null, enabled = options != null)
    }
    Text(stringResource(R.string.commands_require_verbs_detail), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.commands_numbers), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
    for ((mode, label) in NUMBER_CHOICES) {
        val selected = options?.numbers == mode
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, enabled = options != null, role = Role.RadioButton) {
                    change { it.copy(numbers = mode) }
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, enabled = options != null)
            Text(stringResource(label), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
    Text(stringResource(R.string.commands_list), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.commands_list_detail), style = MaterialTheme.typography.bodyMedium)
    for (group in CommandHelp.groups) {
        Text(group.title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
        for (example in group.examples) Text(example, style = MaterialTheme.typography.bodyMedium)
    }
}

private val NUMBER_CHOICES = listOf(
    NumbersMode.ALL to R.string.commands_numbers_all,
    NumbersMode.UNLABELED to R.string.commands_numbers_unlabeled,
)
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt`, replace

```kotlin
enum class SettingsPage { HOME, CHECKLIST, LISTENING, VOICE, SAFETY, ACTIVITY_LOG, NETWORK_LOG, ABOUT }
```

with

```kotlin
enum class SettingsPage { HOME, CHECKLIST, LISTENING, VOICE, COMMANDS, SAFETY, ACTIVITY_LOG, NETWORK_LOG, ABOUT }

/**
 * A request to show [page], from an intent that opened the settings app, such as "What can I say?" (design spec, section
 * 15). Not a data class: two requests for the same page are different requests, so the second one still switches to the
 * page after the owner has left it.
 */
class PageRequest(val page: SettingsPage)
```

Replace

```kotlin
 * opens, and when the About page shows again after a licence text (design spec, 8.10). [onExportLogs] asks the owner
 * for a file to export both logs to.
 */
@Composable
fun SettingsApp(
    status: SetupStatus,
    data: SettingsData,
    onAction: (SetupAction) -> Unit,
    onCheckModels: () -> Unit,
    onExportLogs: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    // A licence text opens on top of the About page, and Back returns there.
    var licence by rememberSaveable { mutableStateOf<Licence?>(null) }
```

with

```kotlin
 * opens, and when the About page shows again after a licence text (design spec, 8.10). [request] names a page to show,
 * at the start and whenever a new request arrives. [onExportLogs] asks the owner for a file to export both logs to.
 */
@Composable
fun SettingsApp(
    status: SetupStatus,
    data: SettingsData,
    onAction: (SetupAction) -> Unit,
    onCheckModels: () -> Unit,
    request: PageRequest? = null,
    onExportLogs: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(request?.page ?: SettingsPage.HOME) }
    // A licence text opens on top of the About page, and Back returns there.
    var licence by rememberSaveable { mutableStateOf<Licence?>(null) }
    // A new request, such as a second "What can I say?" while the app shows another page, switches to its page.
    LaunchedEffect(request) {
        request?.let {
            page = it.page
            licence = null
        }
    }
```

Replace

```kotlin
            SettingsPage.VOICE -> ScrollingPage { VoicePage(data, status.models, home) }
```

with

```kotlin
            SettingsPage.VOICE -> ScrollingPage { VoicePage(data, status.models, home) }
            SettingsPage.COMMANDS -> ScrollingPage { CommandsPage(data, home) }
```

Replace

```kotlin
    PageEntry(stringResource(R.string.settings_voice), voiceSummary(voice)) { onOpen(SettingsPage.VOICE) }
```

with

```kotlin
    PageEntry(stringResource(R.string.settings_voice), voiceSummary(voice)) { onOpen(SettingsPage.VOICE) }
    PageEntry(stringResource(R.string.settings_commands), stringResource(R.string.settings_commands_summary)) {
        onOpen(SettingsPage.COMMANDS)
    }
```

In `app/src/main/res/values/strings.xml`, replace

```xml
    <string name="about_model_absent">%1$s: optional, not on the phone</string>
</resources>
```

with

```xml
    <string name="about_model_absent">%1$s: optional, not on the phone</string>
    <string name="settings_commands">Instant commands</string>
    <string name="settings_commands_summary">Require verbs, numbers, and what you can say</string>
    <string name="commands_require_verbs">Require verbs</string>
    <string name="commands_require_verbs_detail">With this on, a name alone, such as \"Save\", does nothing, and you say \"tap Save\". With it off, a name alone taps an element when you say its name exactly and no other element has that name, or when the name clearly fits that one element.</string>
    <string name="commands_numbers">Numbers</string>
    <string name="commands_numbers_all">On all elements</string>
    <string name="commands_numbers_unlabeled">Only on elements without a name</string>
    <string name="commands_list">What you can say</string>
    <string name="commands_list_detail">Say \"What can I say?\" to come back to this list.</string>
    <!-- Plan 3B strings -->
    <!-- Plan 3C strings -->
</resources>
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
```

with

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
```

Replace

```kotlin
import kotlinx.coroutines.flow.asStateFlow
```

with

```kotlin
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
```

Replace

```kotlin
import kotlinx.coroutines.flow.combine
```

with

```kotlin
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
```

Replace

```kotlin
    // The owner's options for the instant commands, at their defaults until the settings app stores them.
    private val commandOptions = MutableStateFlow(CommandOptions())
```

with

```kotlin
    /**
     * The owner's options for the instant commands (design spec, sections 9.5 and 9.8), stored encrypted, which the
     * executor reads at each command: the defaults until the store has answered, and after a read that failed, which is
     * logged by its classes only.
     */
    internal val commandOptions: StateFlow<CommandOptions> by lazy {
        settings.observe(SettingKeys.COMMANDS)
            .catch { reportFailure("Reading the command options", it) }
            .stateIn(scope, SharingStarted.Eagerly, CommandOptions())
    }
```

Replace

```kotlin
                options = commandOptions.asStateFlow(),
```

with

```kotlin
                options = commandOptions,
```

- [ ] **Step 4: Open the settings app on a page, and ask for a permission, from an intent**

In `app/src/main/AndroidManifest.xml`, replace

```xml
    <uses-permission android:name="android.permission.ANSWER_PHONE_CALLS" />
```

with

```xml
    <uses-permission android:name="android.permission.ANSWER_PHONE_CALLS" />
    <!-- Plan 3C: permissions -->
```

Replace

```xml
        <activity
            android:name=".MainActivity"
            android:exported="true">
```

with

```xml
        <!-- singleTop: "What can I say?" while the settings app is in front delivers its page to the open app. -->
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop">
```

In `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`, replace

```kotlin
import dev.kestrel.app.settings.ModelCheck
import dev.kestrel.app.settings.SettingsApp
```

with

```kotlin
import dev.kestrel.app.settings.ModelCheck
import dev.kestrel.app.settings.PageRequest
import dev.kestrel.app.settings.SettingsApp
import dev.kestrel.app.settings.SettingsPage
```

Replace

```kotlin
    private var status by mutableStateOf<SetupStatus?>(null)
```

with

```kotlin
    private var status by mutableStateOf<SetupStatus?>(null)

    /** The page that the latest intent asked for, such as the list of commands after "What can I say?". */
    private var pageRequest by mutableStateOf<PageRequest?>(null)
```

Replace

```kotlin
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                status?.let {
                    SettingsApp(it, data, ::perform, ::checkModels) {
                        exportLogs.launch("kestrel-logs-${LocalDate.now()}.json")
                    }
                }
            }
        }
    }
```

with

```kotlin
        super.onCreate(savedInstanceState)
        // After the activity is made again, as after a rotation, the page that the owner has open stays. Reopened from the
        // recent apps, Android gives it the task's first intent again, which may be an old "What can I say?".
        if (savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) follow(intent)
        setContent {
            MaterialTheme {
                status?.let {
                    SettingsApp(it, data, ::perform, ::checkModels, pageRequest) {
                        exportLogs.launch("kestrel-logs-${LocalDate.now()}.json")
                    }
                }
            }
        }
    }

    // MainActivity is singleTop, so an intent for it while it is in front comes here, such as a second "What can I say?".
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        follow(intent)
    }

    /**
     * Shows the page that [EXTRA_PAGE] names, and asks for the permission of [EXTRA_ASK_PERMISSION] when [ASKABLE] has it.
     * Any app may start MainActivity, so an unknown page or any other permission is ignored.
     */
    private fun follow(intent: Intent) {
        intent.getStringExtra(EXTRA_PAGE)
            ?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }
            ?.let { pageRequest = PageRequest(it) }
        intent.getStringExtra(EXTRA_ASK_PERMISSION)?.takeIf { it in ASKABLE }?.let { askForPermissions(it) }
    }
```

Replace

```kotlin
    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }
}
```

with

```kotlin
    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    companion object {
        /** The name of a [SettingsPage] to show, such as the list of commands that "What can I say?" opens. */
        const val EXTRA_PAGE = "dev.kestrel.app.extra.PAGE"

        /** A permission of [ASKABLE] to ask for, as when Kestrel needs the contacts to call by name (design spec, 13.6). */
        const val EXTRA_ASK_PERMISSION = "dev.kestrel.app.extra.ASK_PERMISSION"

        /** The permissions that an intent may ask for. Plan 3C declares READ_CONTACTS in the manifest. */
        val ASKABLE: Set<String> = setOf(Manifest.permission.READ_CONTACTS)
    }
}
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace

```kotlin
                // From the accessibility service, which Android lets start activities from the background (prototype P1).
                // Showing a page and asking for a permission come with the settings page of the instant commands.
                openSettings = { _, _ ->
                    appContext.startActivity(
                        Intent(appContext, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                },
```

with

```kotlin
                // From the accessibility service, which Android lets start activities from the background (prototype P1).
                // MainActivity is singleTop, so an open settings app switches to the page.
                openSettings = { page, permission ->
                    val intent = Intent(appContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    page?.let { intent.putExtra(MainActivity.EXTRA_PAGE, it.name) }
                    permission?.let { intent.putExtra(MainActivity.EXTRA_ASK_PERMISSION, it) }
                    appContext.startActivity(intent)
                },
```

- [ ] **Step 5: Add help, require verbs and hiding the bar**

In `app/src/main/kotlin/dev/kestrel/app/commands/CommandTexts.kt`, replace

```kotlin
    fun noNumber(number: Int) = "There's no number $number on the screen."
```

with

```kotlin
    fun noNumber(number: Int) = "There's no number $number on the screen."

    /** What "What can I say?" says while the settings app opens on the list of commands (design spec, Appendix A). */
    const val HELP = "You can say things like tap Send, scroll down, go back, show numbers or type hello. " +
        "The list is on the screen."
```

In `app/src/main/kotlin/dev/kestrel/app/commands/ScreenCommands.kt`, replace

```kotlin
import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
```

with

```kotlin
import dev.kestrel.app.settings.SettingsPage
import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
```

Replace

```kotlin
            // Tasks 5 to 9 add their commands above this line.
```

with

```kotlin
            is Command.Help -> help()
            // Tasks 5 to 9 add their commands above this line.
```

Replace

```kotlin
    private suspend fun bare(screen: ScreenSession, command: Command.Bare): CommandResult {
        val number = command.number
```

with

```kotlin
    private suspend fun bare(screen: ScreenSession, command: Command.Bare): CommandResult {
        // With "require verbs" on, a bare label or number never taps (design spec, section 9.5).
        if (environment.options.value.requireVerbs) return CommandResult(Outcome.IGNORED)
        val number = command.number
```

Replace

```kotlin
    private suspend fun tapAt(screen: ScreenSession, bounds: IntRect): ActionResult = screen.actions.tapAt(bounds.centerX, bounds.centerY)
```

with

```kotlin
    /**
     * "What can I say?", "show all commands", "show commands" and "open tutorial" open the list of commands on the settings
     * page "Instant commands" and say a short summary (design spec, Appendix A). "Again" never repeats it.
     */
    private fun help(): CommandResult {
        environment.openSettings(SettingsPage.COMMANDS, null)
        return CommandResult(Outcome.DONE, say = CommandTexts.HELP)
    }

    private suspend fun tapAt(screen: ScreenSession, bounds: IntRect): ActionResult = screen.actions.tapAt(bounds.centerX, bounds.centerY)
```

In `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt`, replace

```kotlin
    suspend fun releaseFeedback() = withContext(Dispatchers.Main) { feedback.release() }
```

with

```kotlin
    suspend fun releaseFeedback() = withContext(Dispatchers.Main) { feedback.release() }

    /**
     * Hides the feedback bar at once, also a kept text, as before a screenshot (design spec, Appendix A); the numbers, the
     * labels and the grid stay.
     */
    suspend fun hideFeedback() = withContext(Dispatchers.Main) { feedback.hide() }
```

- [ ] **Step 6: Update the spec**

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, section 15, replace

```markdown
voice and speech; instant-command options (section 9.8);
```

with

```markdown
voice and speech; instant commands (section 9.8);
```

Replace

```markdown
The setup checklist stays visible until everything is done:
```

with

```markdown
The page "Instant commands" holds "Require verbs" (section 9.5), off by default, and whether numbers go on all elements, the default, or only on those without a name (section 9.4). Below them it lists what the owner can say: this is the list that "What can I say?", "Show all commands", "Show commands" and "Open tutorial" open. The choices are stored encrypted, like every setting, and count from the next command. Kestrel opens its settings app on a page with an intent that names the page; the settings app takes such an intent also while it is in front.

The setup checklist stays visible until everything is done:
```

In Appendix A, replace

```markdown
| Help | What can I say?; Show all commands; Open tutorial | Yes. Kestrel shows a list of commands and speaks a short summary. |
```

with

```markdown
| Help | What can I say?; Show all commands; Open tutorial | Yes. Kestrel opens the list of commands on its settings page "Instant commands" and speaks a short summary; "Show commands" does the same. There is no separate tutorial, and "help" alone is a bare label. |
```

- [ ] **Step 7: Update the README**

In `README.md`, replace

```markdown
> **Status:** early development. Kestrel listens for its name and its own commands, such as "Kestrel, turn off", but it cannot run tasks yet.
```

with

```markdown
> **Status:** early development. Kestrel listens for its name, its own commands, such as "Kestrel, turn off", and commands for the screen, such as "tap Send" or "scroll down", but it cannot run tasks yet.
```

Replace

```markdown
scripts\connected-test.ps1 -Serial $serial :core:privacy:connectedDebugAndroidTest :core:voice:connectedDebugAndroidTest :app:connectedDebugAndroidTest
```

with

```markdown
scripts\connected-test.ps1 -Serial $serial :core:privacy:connectedDebugAndroidTest :core:voice:connectedDebugAndroidTest :app:connectedDebugAndroidTest
scripts\command-test.ps1 -Serial $serial  # the instant commands' device tests, on the emulator only, which the line above skips
```

Replace

```markdown
- **About:** Kestrel's version, the Android version
```

with

```markdown
- **Instant commands:** "Require verbs", which makes a name alone, such as "Save", do nothing, so that only "tap Save" taps it, and whether numbers go on all elements or only on those without a name; below them, the list of what you can say, which "What can I say?" opens. Each choice counts from the next command.
- **About:** Kestrel's version, the Android version
```

Replace

```markdown
## Licence
```

with

```markdown
## Instant commands

While Kestrel listens, you drive the screen by voice without Kestrel's name, as with Voice Access (design spec, section 9 and Appendix A). Say the name of a button to tap it, such as "Send" or "tap Send"; "long press Send" holds it. "Show numbers" puts a number on every element, and then "tap 5", or just "5", taps that element; "hide numbers" removes them. "Show labels" shows the name that Kestrel uses for each element, "What is 5?" says the name and the kind of element 5, and "show commands for Send" says what you can do with it. "Show grid" puts numbered squares over the screen for places without an element, "more squares" and "fewer squares" change their size, and "hide grid" removes them. You can also scroll ("scroll down", "scroll to bottom", "scroll down on Messages"), swipe ("swipe up", "swipe left on 5", "swipe forwards", "swipe from the left edge"), pinch ("pinch out"), drag ("drag 5 to 9", or "drag 5" and then the place when Kestrel asks "Drag it where?"), check and uncheck ("check Remember me", "switch off"), expand, collapse and dismiss, also notifications in the shade, and move sliders one step ("increment 4", "decrement 4"). A name that you say exactly acts at once when no other element has it, whatever near misses the screen also shows; when two or more elements have that name, Kestrel shows numbers on them only and asks "Which one?", and when only a near miss fits, it asks "Which one?" with its one number, as a confirmation; say the number. When nothing matches, it says that it can't find it and shows numbers on every element, so that you can say the number next. A name alone, without a verb, taps only when it clearly names one element: it never asks "Which one?", and it never takes a name that only sounds like what you said, as a command with a verb such as "tap" may. Kestrel ignores other speech without a word, because it may be meant for someone else. "Again" repeats the last command, and "stop" or "cancel" cancels a command while it runs or asks, as a touch does while it asks; while nothing runs, "stop" and "cancel" tap a button with that name. A command that you say while another one runs is dropped, and the feedback bar says "Still busy with the last command." "What can I say?" opens the list of commands on the settings page "Instant commands" and says a short summary.

Before each utterance, Kestrel gives the speech recogniser the names on the screen and a few rare words of the commands, so that it writes them as the screen does. It reads the names from its newest picture of the screen, which it keeps current while it listens; it never uses a password field or what you typed into a field.

The device tests of the instant commands run only through `scripts\command-test.ps1 -Serial $serial`, on the emulator; every other run, such as `connected-test.ps1` above, skips them. The script refuses phones, because the tests change the device's state: Wi-Fi and Bluetooth, the volumes and the ringer mode, Quick Settings' tiles, the lock screen, users, contacts, the call log, screenshots and permissions. It puts all of these back at the end, also after a failure or Ctrl+C, except Kestrel's microphone and notification permissions, which stay granted, as after `voice-test.ps1`, because revoking a permission ends Kestrel's process. It ends with exit code 1 when a test was skipped or something could not be put back. `-Class TouchCommandsTest` runs one class, and `-Class TouchCommandsTest#longPressByLabel` one test.

<!-- Plan 3B: dictation and editing -->
<!-- Plan 3C: apps, phone settings and calls -->

## Licence
```

- [ ] **Step 8: Run all checks of the build**

Run: `scripts\gradle.ps1 check kestrelAudit :app:compileDebugAndroidTestKotlin`
Expected: `BUILD SUCCESSFUL`: all JVM tests (unchanged: `:app` 314, `:core:screen` 55, `:core:commands` 46, `:core:voice` 140), lint in every module (every `lint-results-debug.txt` says "No issues found."), the audit, and the device tests compile.

- [ ] **Step 9: Run the other device tests of `:app`**

Tasks 5 to 9 changed the snapshot, `ScreenSession`'s screen watcher and snapshot builder, and `MainActivity` (now `singleTop`), which the screen, overlay and settings tests use; the command tests skip themselves in this run, because it does not pass `kestrelCommands=true`.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.notPackage=dev.kestrel.app.voice' '-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true' --no-configuration-cache`
Expected: `BUILD SUCCESSFUL`, and the result files in `app\build\outputs\androidTest-results\connected\debug\` show 156 tests, of which the 36 command tests are skipped, and 0 failures: `SettingsAppTest` 31 (29 and `theCommandsPageChangesTheOptionsAndListsTheCommands`, `aRequestedPageOpensAndTheSameRequestAgainSwitchesBackToIt`) and `MainActivityTest` 6 (4 and `theCommandsPageStoresTheOptions`, `anIntentWithAPageOpensThatPageInTheRunningApp`).

- [ ] **Step 10: Run the command device tests**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { throw 'emulator-5554 is not kestrel-api37' }; scripts\command-test.ps1 -Serial emulator-5554`
Expected: `BUILD SUCCESSFUL` and "Command tests: 36 run, 0 failed, 0 errors, 0 skipped." (`TouchCommandsTest` 20, `GestureCommandsTest` 8, `OverlayCommandsTest` 6, `HelpCommandsTest` 2), with no warning from the restores at the end.

- [ ] **Step 11: Stop Gradle**

Run: `scripts\gradle.ps1 --stop`
Expected: "Stopping Daemon(s)" and "1 Daemon stopped" (or "No Gradle daemons are running.").

- [ ] **Step 12: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/settings/CommandsPage.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/main/kotlin/dev/kestrel/app/MainActivity.kt app/src/main/AndroidManifest.xml app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt app/src/main/res/values/strings.xml app/src/main/kotlin/dev/kestrel/app/commands/ScreenCommands.kt app/src/main/kotlin/dev/kestrel/app/commands/CommandTexts.kt core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt app/src/androidTest/kotlin/dev/kestrel/app/MainActivityTest.kt app/src/androidTest/kotlin/dev/kestrel/app/commands/HelpCommandsTest.kt app/src/androidTest/kotlin/dev/kestrel/app/commands/TouchCommandsTest.kt app/src/androidTest/kotlin/dev/kestrel/app/commands/OverlayCommandsTest.kt app/src/androidTest/kotlin/dev/kestrel/app/commands/CommandTests.kt docs/superpowers/specs/2026-11-12-kestrel-design.md README.md
git commit -m "feat(app): add the page Instant commands with require verbs and the numbers option, and open it on What can I say?" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

## Self-review (Tasks 5 to 9)

**1. Design and spec coverage.** Each item of design D7, D13, D14 and D18 that "The plans" gives to Tasks 5 to 9, and where it is:

| Design item | Task |
|---|---|
| D7: check, uncheck, switch on and off, a click only when the state differs, "It's already on/off" on the bar; the only or focused element without a name, "Which one?" with several; a number that names an element that does not check is refused | 5 (`check`, `TouchCommandsTest.checkAndSwitchClickOnlyWhenTheStateDiffers`) |
| D7: expand and collapse with the element's action, a tap as the fallback (never in SystemUI), nothing and "It's already expanded/collapsed" for an element that offers only the other action, by name, by number or without a name; dismiss with the dismiss action, the sideways swipe only on a notification row in SystemUI's shade (`swipeAway`, called directly by a test), "I can't dismiss it" elsewhere | 5 (`ScreenActions.expand`, `collapse`, `dismiss`, `swipeAway`, `ScreenCommands.expandOrCollapse`; the notification tests) |
| D7: sliders (`NodeData.slider`, `ScreenElement.slider`, `isActionable`, kept without a name), one step by the scroll action or a twentieth by set-progress, a View `SeekBar` and a Compose `Slider`, no number on a determinate progress bar | 5 (`adjust`, `TargetKind.SLIDER`, two device tests, `SnapshotBuilderTest`, `ScreenTargetsTest`) |
| D7: the test app's notification with a long text and an action; expand, collapse and dismiss by label and by number, and expand or collapse by the title | 5 (`NotificationActivity`, `aNotificationInTheShade…`) |
| D7: scroll with and without a target, scroll to top and bottom (30 times or 15 s), swipe forwards and backwards with "Nothing on the screen swipes sideways", edge swipes, swipes on a number or element, pinch, drag with "Drag it where?" and its start checked again right before the finger moves | 6 (`ScreenCommands`, `GestureCommandsTest`) |
| D7: `GesturePaths.limit` with JVM tests in `:core:screen` | 6 (`GesturePathsTest`) |
| D7: numbers (in the option's mode) and labels, the grid with more and fewer squares and its gestures, "What is N?", "Show commands for X" | 7 (`OverlayCommandsTest`, `ScreenTargetsTest`); the numbers mode from the stored option in 9 |
| D2/D6: "again" after the fallback to a bare label | Task 4's `TouchCommandsTest.deleteInADialogAndAgainTapItsOnlyDeleteButtonTwice`; Tasks 5 to 9 add nothing for it |
| D13: `SnapshotCache.latest()`, `ScreenSession.keepFresh` set by `AppGraph`, `ScreenBoostWords`, `CommandVocabulary` checked with `keptBoostWords` (and the "WiFi" fallback), `BoostWordBudget`, `VoiceSession`'s second source, the voice device test (it starts listening, so it fails without `AppGraph`'s wiring or the screen watcher's change), no boost word logged; the counts of snapshot builds for D16 | 8 |
| D14: `CommandOptions` stored under `SettingKeys.COMMANDS`, the page "Instant commands" with both options and the list, `SettingsData.commandOptions` and `setCommandOptions`, `MainActivity` `singleTop` with `EXTRA_PAGE`, `EXTRA_ASK_PERMISSION` and `ASKABLE`, `SettingsApp`'s starting page, `openSettings` with the extras; require verbs and the numbers mode in the executor | 9 (and the numbers mode's read in 7) |
| D7 help: the four help phrases open the page and speak the summary; not repeatable | 9 (`help`, `HelpCommandsTest`) |
| `GlobalAction.ALL_APPS` and `DISMISS_SHADE`, `ScreenSession.hideFeedback()` | 5 (the global actions, moved from 9) and 9 |
| Markers: the app's manifest (`    <!-- Plan 3C: permissions -->`), `strings.xml` (`    <!-- Plan 3B strings -->`, `    <!-- Plan 3C strings -->`), the README's section `## Instant commands` (`<!-- Plan 3B: dictation and editing -->`, `<!-- Plan 3C: apps, phone settings and calls -->`) | 9 (the test app's markers are Task 4's) |
| D18's 3A part: 8.3 with the bullet line that plan 3C replaces | 8 |
| D18: 9.3's sentence on sideways swipes | 6 |
| D18: 15 (the page) and Appendix A's help row | 9 |
| D18: 7.3, 7.4, 9.4, 9.5, 9.6 (with the `- **Limits:**` line), 9.8, 14.1, Appendix A's "Cancel on touch", 16.3 | Tasks 1, 3 and 4 |
| README: the section `## Instant commands`, the corrected test example with `command-test.ps1`, the page in the list of pages, the status line | 9 |

No item of these lists is left without a task. The controller's two later decisions are in Task 9's README text: a name said exactly acts at once unless another element has it, and a single near miss asks "Which one?" as a confirmation (`LabelMatcher.decide`, Task 1); `command-test.ps1` leaves Kestrel's microphone and notification permissions granted. Task 9 does not replace spec 9.6's `- **Limits:**` line, which Task 1 writes. The README, the page's "Require verbs" text and the help describe the matching of bare labels as review A's decision has it: a name alone taps only a clear match, never asks "Which one?" and never takes a name that only sounds like what was said; the rule for names that sound alike (at least 0.80 similar once respelled) holds only for commands with a verb. Every command test class has `CommandTests.only` as its outer rule, before `ScreenServiceRule`, and `ScreenBoostWordsTest` its assumption of the models, so that a skipped test changes nothing on the device.

**2. Placeholder scan.** No "TBD", "TODO", "similar to Task N" or step without its code. The test counts are absolute: they start from the totals at 59b86ae, the end of plan 2D (`:app` JVM 264, `:core:voice` 139, `:core:screen` 47, `:core:commands` 0, voice device tests 81, the other device tests 116, Pester 132 per shell), with Tasks 1 to 4 (`:app` + 43, `:core:voice` + 1, `:core:commands` 46, the other device tests + 13, Pester + 12); the list below gives each task's additions and totals. Step 11 of Task 8 is conditional on a test result and gives both the exact replacement and when to stop instead. Every "replace" text was checked mechanically: applied in order after Tasks 1 to 4 to the files at 59b86ae, the end of plan 2D, each of the 268 operations of Tasks 1 to 9 (the replacements and the new files, without the conditional Step 11 of Task 8, whose text `        "Wi-Fi",` the file `CommandVocabulary.kt` holds once) finds its text exactly once.

**3. Type consistency.** Names that one task produces and a later task uses:
- Task 5 → Task 6: `GlobalAction.DISMISS_SHADE` (`GestureCommandsTest`), the import of `ActionMethod` in `ScreenCommands.kt`.
- Task 6 → Task 7: `FOUND` in `ScreenCommands`' companion object (`commandsFor`), `CommandTexts.DRAG_WHERE` (`OverlayCommandsTest`'s grid drag), `ScreenSession.screenBounds`.
- Task 6: `ActionResult.NOTHING_SCROLLS`, which `ScreenActions.scroll` returns and `swipePage` compares with.
- Task 5 → Tasks 7 and 9: `ScreenElement.slider` (`ScreenTargets.role`, `sayable`; the numbers-mode test), `TouchCommandsTest.awaitBar` (the anchor of Task 9's test).
- Task 7 → Task 9: `OverlayCommandsTest` and its `awaitStatus` line (the anchor of Task 9's tests).
- Task 8 → Task 9: the imports `kotlinx.coroutines.flow.combine` in `AppGraph.kt` (Task 9's anchor for `stateIn`).
- The names from Tasks 1 to 4, as Tasks 5 to 9 use them: `ScreenCommands.act(screen, target, kind, conversation, onCell, onElement)`, `done`, `changed`, `targets`, `environment`, `bare`, `TargetKind` (`TAP`, `BARE`, `CHECK`, `EXPAND`, `COLLAPSE`, `DISMISS`, `SCROLL`, `EDIT`), `ScreenTargets.candidates`, `names`, `decide`, `chosen`, `same`, `resolve`, `still`, `ChosenElement`, `Resolution` (with `Changed`), `CommandTexts.WHICH_ONE`, `SHOW_NUMBERS_FIRST`, `noNumber`, `CommandOptions(requireVerbs, numbers)`, `CommandEnvironment.options` and `openSettings(page, permission)`, `CommandTests` (`only`, `start`, `stop`, `say`, `ask`, `answer`, `awaitSaid`, `graph`; Task 9 adds the reset of the options to `start`), `ScreenSession.releaseFeedback()`, `FeedbackBar.hide()`, `Command.*` nested variants, `Direction`, `Edge`, `SpokenNumbers.answer`, `CommandHelp.groups`.
- Every new test function returns `Unit` (`runBlocking<Unit>`), because JUnit 4 runs only methods without a result.

**4. Expected counts (additions, and the totals after each task).** After Task 4: `:core:screen` 47, `:app` 307, `:core:commands` 46, `:core:voice` 140, voice device tests 81, command device tests 13, the other device tests 129 (13 skipped), Pester 144 per shell.

| Task | `:core:screen` JVM | `:app` JVM | `:core:commands` JVM | voice device tests | command device tests (`command-test.ps1`) | other device tests (`connected-test.ps1`, not the voice package) |
|---|---|---|---|---|---|---|
| 5 | + 1 (`SnapshotBuilderTest` 9 → 10), 48 | + 1 (`ScreenTargetsTest` 8 → 9), 308 | 0, 46 | 0, 81 | + 6 (`TouchCommandsTest` 13 → 19), 19 in all | + 6, all skipped (the command tests): 135, 19 skipped |
| 6 | + 6 (`GesturePathsTest` 6, new), 54 | 0, 308 | 0, 46 | 0, 81 | + 8 (`GestureCommandsTest` 8, new), 27 in all | + 8 skipped: 143, 27 skipped |
| 7 | 0, 54 | + 2 (`ScreenTargetsTest` 11), 310 | 0, 46 | 0, 81 | + 4 (`OverlayCommandsTest` 4, new), 31 in all | + 4 skipped: 147, 31 skipped |
| 8 | + 1 (`SnapshotCacheTest` 5 → 6), 55 | + 4 (`BoostWordBudgetTest` 4, new), 314 | 0, 46 | + 2 (`ScreenBoostWordsTest` 2, new; `VoiceSessionFailuresTest` keeps 7), 83 | 0, 31 in all | 0: 147, 31 skipped |
| 9 | 0, 55 | 0, 314 | 0, 46 | 0, 83 | + 5 (`HelpCommandsTest` 2, new; `OverlayCommandsTest` 6; `TouchCommandsTest` 20), 36 in all | + 4 run (`SettingsAppTest` 29 → 31, `MainActivityTest` 4 → 6) and + 5 skipped: 156, 36 skipped, which Step 9 runs |

`:core:voice` stays 140 and Pester 144 per shell in Tasks 5 to 9 (no script changes). The package `dev.kestrel.app.screen` keeps its 63 device tests.
