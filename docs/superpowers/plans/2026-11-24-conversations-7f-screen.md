# The Conversation Screen (Plan 7F) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the owner Kestrel's conversation screen as the launcher: the transcript, a text field, a microphone, Stop, Continue, Cancel and New conversation, and his past conversations with their list, search, transcript, Continue and Delete; one card per question, whichever channel asks it, with "Reply…" under D21's waits, a pick's choices or a keypad for its number, and the buttons of a confirmation, a hand-over or a pick only on the card over the app; the text channel; the typed-input boundary of design 12.2; the speech rules of design 11 with listening off; the bar's goal progress through one writer; the Conversations settings page; and the debug hook `conversation-type`, with the typed start of a goal checked on kestrel-api37 and, in a last task that waits for the owner, on the A71.

**Architecture:** Every rule of the screen is Android-free and runs in JVM tests: `OwnerCard` gives a question's buttons and `CardDesk` keeps one card per question number for both channels and numbers each tap among the owner's inputs as it arrives, `TypedInputs` reads the owner's typed words with plan 7D's typed reading, `TextChannel` is the conversation's `OwnerChannel` for typed words and cards and turns a button on the screen's copy of a card into an input, `GoalBar` gives the bar's progress, which plan 7D's `OwnedBar`, the bar's one writer, shows as an owner of its own, `Transcript` draws records as lines, `ConversationScreenModel` is the screen's one reducer over `ConversationScreenRules`, and `PastList` and `PastPagesRules` hold the past conversations' pages. The Android parts are thin: `:core:screen`'s `QuestionCard` replaces the confirmation card for every question, `ConversationActivity` hosts stateless Compose views (tested with Compose tests on kestrel-api37) and a `ViewModel` that keeps the model across configuration changes, `ScreenGuard` guards the screen's windows, `AppOffVoice` speaks a typed reply with listening off inside plan 7E's `GoalForeground.whileSpeaking`, and the app graph adds the text channel to plan 7D's engine. Task A1 carries a pick's choices from the agent to the card in `:core:agent` and `:core:conversation`. `:core:conversation` gains only what the list needs, on plan 7B's index of past conversations: deleting one or every closed conversation, and the list's search; a seam that shortens the engine's timers in device tests; and a card's answer that carries the number of the owner's tap (`CardAnswer`), so that the engine numbers no act of his itself.

**Tech Stack:** Kotlin 2.4.21 on Android (minSdk 33, target 37) and on the JVM (JDK 21 from Android Studio, target 17), Jetpack Compose (BOM 2026.09.00, Material 3), kotlinx.coroutines 1.11.0 with `kotlinx-coroutines-test`, kotlinx.serialization 1.13.0, JUnit 4.13.2, AndroidX Test (runner 1.7.0, ext-junit 1.3.0), Compose UI test (`junit4.v2`), Room (the version of plan 7C).

## Global Constraints

- Package and application ID `dev.kestrel`; licence GPL-3.0-or-later. No new library: the views use Material 3 without icons, the screen's view model uses the `ViewModel` that `androidx.activity:activity-compose` brings, and the tests use the libraries that `app/build.gradle.kts` has.
- The owner's hard rule (AGENTS.md): no workarounds and no smallest-change fixes; clean code, good architecture and modularity, even when that means a larger change. The owner's criterion of 2026-11-24: "best = best code quality. Don't worry about time." A step that would patch a symptom at the nearest line is a fault of the plan: stop and ask the controller.
- The approved design is `docs\superpowers\specs\2026-11-24-conversations-design.md`, called "the design" below; its decisions D1 to D24 bind this plan, and so do its sections 10.6, 15.2 and R2. This plan is the design's plan 7F (section 16): `ConversationActivity` as the launcher with the transcript, the field, the microphone, Stop, Continue, Cancel and New conversation; past conversations (list, search, transcript, continue, delete); `TextChannel` and leaving Kestrel's screen; `QuestionCard` and "Reply…" with D21's waits; the typed-input boundary (12.2); the speech rules (11); the Conversations settings page; and the hook `conversation-type`. Plan 7E moved two checks here (its decision 2): the typed start of a goal from `ConversationActivity` in front (10.6), and the A71's typed goal with listening off (15.2). The main spec is `docs\superpowers\specs\2026-11-12-kestrel-design.md` (rewritten by plan 7.0).
- The owner's decisions: the launcher opens the conversation screen (D20); past conversations work like VS Code's: browse them and continue them, and continuing closes the open conversation and takes the current test mode (D23); the settings' "Delete history" lives on the activity-log page and goes through the conversation since plan 7D; while Kestrel is off, questions also show as cards, with D21's waits (plan 7B's decision 10).
- This plan consumes plans 7A to 7E: 7A (`docs/superpowers/plans/2026-11-24-conversations-7a-agent.md`), 7B (`docs/superpowers/plans/2026-11-24-conversations-7b-core.md`, its "Interfaces for 7C and 7D"), 7C (`docs/superpowers/plans/2026-11-24-conversations-7c-archive.md`), 7D (`docs/superpowers/plans/2026-11-24-conversations-7d-voice.md`, its "Interfaces for 7E and 7F") and 7E (`docs/superpowers/plans/2026-11-24-conversations-7e-goal-service.md`, its "Interfaces for 7F and 7G"). It builds no second engine, store, search, card, signal watcher or goal service.
- The safety work of plan 6BF keeps working unchanged: `CheckedLaunch` and `ScreenAction.Launch`, `TaskWitness` and `DebugTaskWitness`, and the touch watcher's `ownerTouches` and signals. This plan changes none of their files.
- Typed input counts as the owner's only when it comes from the field on Kestrel's own screen (D8, design 12.2). Nothing injects it through accessibility, the agent never acts on Kestrel's own screen (`AgentFreeApps.KESTREL`), no exported intent carries words, no notification has a `RemoteInput`, and the only other typing is the debug hook, which exists only in debug builds for callers with `android.permission.DUMP`.
- Never print, log or put into a hook's answer or a toString the owner's words, a reply, a question, a card's text, a record's text, a checklist item's text or a past conversation's request. Every new type's toString prints only kinds, numbers, counts and lengths (design D25 of sub-project 5).
- Run Gradle only through `scripts\gradle.ps1`, one build at a time; when the controller's brief says that agents run in parallel, take `.superpowers\sdd\gradle.lock` in `H:\Code\kestrel` before each Gradle command as the brief says, and do not run `scripts\gradle.ps1 --stop` beside another Gradle user. Otherwise stop Gradle with `scripts\gradle.ps1 --stop` at the end of each task that builds. `scripts\gradle.ps1` refuses every task that installs, uninstalls or runs tests on a device. Install only through `scripts\install.ps1`, run the `:app` and `:core:privacy` device tests only through `scripts\connected-test.ps1`, the voice's device tests only through `scripts\voice-test.ps1`, and the instant commands' device suite only through `scripts\command-test.ps1`.
- Device tests run only on kestrel-api37 through `scripts\connected-test.ps1`. Each device command starts with the emulator's check, written without the word throw: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }`. If the check fails, or no emulator runs, start it with `scripts\start-emulator.ps1`, check the serial that it prints, and use that serial. Never use the AVD `zoran`, the S23+ or the owner's Xiaomi tablet. The A71 (`R58N66RVD7Z`) is used only in Task 15, within AGENTS.md's limits.
- kestrel-api37 holds the owner's own Google account and Kestrel's Copilot profile: no step uninstalls Kestrel there, clears its data, or signs an account in or out. Since plan 7C, its database is at version 3: never install a build without plan 7C there.
- Nothing in this plan calls a provider or runs `--live`. Every device test uses the scripted model.
- The JVM tests run on CI's Ubuntu too: no JVM test needs Windows, a device, a font or a network, and no test depends on the machine's time zone.
- The repository uses LF line endings.
- This plan is `docs/superpowers/plans/2026-11-24-conversations-7f-screen.md`, committed; its changes since are in its amendment sections, and a difference found while it is carried out goes into `.superpowers/sdd/progress.md`.
- Every commit message ends with the line `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.

Code comments name this plan "plan 7F" and the design's sections as "design 10.3 of sub-project 7"; design numbers without a sub-project in existing comments stay those of sub-project 5.

## Before you start

- This plan's Tasks A1 and A2 ran first, right after plan 7B's Tasks 5a to 5d and before its Task 8, and are built: Task A1 in `b4f5fcc3`, Task A2 in `ade80de8`. Its Tasks 1 to 15 start only after plans 7A to 7E, with Tasks A1 and A2, are merged into `sp3`. Since the amendment of 2026-11-26, Tasks 1 to 13a follow plans 7D and 7E as built, on `main` at `284eb0ec`: the plan checker runs clean from Task 1 against that commit alone, with no simulated plan, and so do its runs from Tasks 4, 8 and 11 on the earlier tasks' simulated files (see the amendment). Since the amendment of 2026-11-27, Tasks 11 to 15 follow Tasks A1 to 10 as built on `sp3`, with their review fixes, at `1b1b8f81`: the checker runs clean from Task 11 against that commit alone (see that amendment). Since the amendment of 2026-11-27 for the review fixes, "Interfaces for 7G", Tasks 14 and 15 and the counts follow Tasks 1 to 13a with every review fix, at `f23bd193`. The history below is the plan's before that amendment. It was written against `sp3` at `a141ea4f`, where plan 7A and plan 7B's Tasks 1 to 7 had landed, with plan 7B's fixes (`693f3dde`, `31ef1c54`, `c6bafb8d`, `0aa11403`, `e12844d5`, and Task 7's `a3459e50` and `d6c76d70`), plan 7B's index of past conversations planned in shards after its fix rounds (Tasks 5a to 5d, design 4.3 and 9.x, `90b6c2b7` to `7265779c`), plan 7C's Task 4b built (`1422af92`, `d9382170`) and its Task 4c planned (`a141ea4f`), plan 7E's amendment moved into plan 7B's Tasks 7 and 10 (`ba1cc001`, `82a42656`), and plan 7E rebased onto plan 7B's built rules (`04c540d9`, `b799de95`). It was re-synced to plan 7B's engine after the fixes of its Task 10 (`25cdca0a`, `4804136d`, and the final re-check's `50b99c61` and `edcc4b30`, which moved `RecordRef` to the model package): every store write goes through the engine's writer, and its timers are deadlines on `ConversationClock.sinceBootMillis`; and to `LineKind.OWNER_TOO_LATE`. The plan checker first simulated, in order: plan 7B's Tasks 5a to 5d, this plan's Tasks A1 and A2, plan 7B's Tasks 8 to 10, plan 7C from Task 4c, and plans 7D and 7E; this plan's Tasks 1 to 13a ran clean on top. Its old texts in `core/`, `app/` and their tests are the texts that those plans leave. The files that Tasks 1 to 13a change and that the simulated plans leave as they are at `a141ea4f` are `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`, `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt`, `app/src/main/kotlin/dev/kestrel/app/settings/ListeningPage.kt`, `app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt`, `app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt`, `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt`, `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/CardPresence.kt`, `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/ConfirmationCard.kt`, `core/screen/src/main/res/values/strings.xml`, `app/src/androidTest/kotlin/dev/kestrel/app/screen/ConfirmationCardTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/screen/GridBarAndCardTest.kt` and `testapp/src/main/AndroidManifest.xml`.
- At the starts of Tasks 1, 4, 8 and 11, run the plan checker against the branch's HEAD, in PowerShell 7: `$env:PLANX = 'H:\Code\kestrel-sp3\docs\superpowers\plans\2026-11-24-conversations-7f-screen.md'; $env:BASE_REF = (git -C H:\Code\kestrel-sp3 rev-parse HEAD); $env:S3A = 'C:\Users\AleksaLocal\.copilot\session-state\337a39e5-0c45-4e8a-b4a0-4c1d2eef024e\files\no-such-sim'; $env:SIMDIR = 'C:\Users\AleksaLocal\.copilot\session-state\337a39e5-0c45-4e8a-b4a0-4c1d2eef024e\files\no-such-sim'; $env:OUTDIR = 'plan7f-sim'; $env:END_TASK = ''; python 'C:\Users\AleksaLocal\.copilot\session-state\337a39e5-0c45-4e8a-b4a0-4c1d2eef024e\files\plan3x-check.py'`; for Task 1 also set `$env:START_TASK = '1'`, for Task 4 `$env:START_TASK = '4'`, for Task 8 `$env:START_TASK = '8'`, and for Task 11 `$env:START_TASK = '11'`. Expected: an `operations:` line, a `files:` line, a `simulated files written:` line, and no line that starts with `PROBLEM:`. If a `PROBLEM:` line names a text that plans 7A to 7E moved or changed, or a file that one of them created, stop and ask the controller: the controller rebases the plan (it changes the plan's old texts to the texts that the earlier plans left, keeps each change's meaning, and adjusts a new text that depends on them), runs the checker again until it is clean, and only then gives the task.
- At the start of Task 1, check that plans 7B to 7E and Tasks A1 and A2 as merged have what this plan consumes: `Select-String -Path core\agent\src\main\kotlin\dev\kestrel\agent\port\OwnerPort.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\model\OwnerInput.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\engine\ConversationEngine.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\engine\ConversationView.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\ConversationTexts.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\store\ConversationStore.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\store\HistorySearch.kt, app\src\main\kotlin\dev\kestrel\app\AppGraph.kt, app\src\main\kotlin\dev\kestrel\app\conversation\OwnerSupport.kt, app\src\main\kotlin\dev\kestrel\app\conversation\InputIds.kt, app\src\main\kotlin\dev\kestrel\app\orchestrator\Requests.kt, app\src\main\kotlin\dev\kestrel\app\listen\ScreenTurns.kt, app\src\main\kotlin\dev\kestrel\app\goal\GoalForeground.kt, app\src\main\kotlin\dev\kestrel\app\listen\OwnedBar.kt, app\src\main\kotlin\dev\kestrel\app\goal\GoalTexts.kt -Pattern 'fun replyOpened\(question: Int\)|fun composing\(active: Boolean\)|suspend fun deleteHistory\(\): HistoryDeletion|val hold: GoalHold\? = null|fun held\(hold: GoalHold\)|const val CONTINUE_IN_NOTIFICATION|suspend fun searchable\(\): Searchable|class PastUnavailableException|suspend fun getAll\(|suspend fun conversation\(topic: String\?\): ConversationLookup|val goalForeground: GoalForeground|internal interface OwnerScreen|object InputIds|fun typed\(text: String\): InputIntent\?|class ScreenTurns|suspend fun <T> whileSpeaking|const val MAX_CHOICES = 4|val question: Int\? = null\) : InputIntent|internal class OwnedBar\(private val bar: FeedbackBar\)|val waitsBehindCommand: Boolean = false|override suspend fun take\(after: Long\?, waiting: \(\) -> Unit\): Boolean|private val inputIds: \(\) -> Long|const val BEHIND_COMMAND'`. Expected: 23 matches, as at `284eb0ec`: the last five are plan 7D's `OwnedBar` (Task 5 extends it), `ScreenTurns`' final `take` (Task 2), the engine's `inputIds` (Task 1 removes it) and plan 7E's `waitsBehindCommand` and `BEHIND_COMMAND` (Tasks 6 and 7 show it). Otherwise stop and ask the controller. Then check that only the two files that Task 8 changes name plan 7E's constant, so that its rename reaches every use: `git grep -l CONTINUE_IN_NOTIFICATION -- core app`. Expected: exactly `app/src/test/kotlin/dev/kestrel/app/goal/GoalNoticesTest.kt` and `core/conversation/src/main/kotlin/dev/kestrel/conversation/ConversationTexts.kt` (plan 7E's `GoalRules.backgroundRefused` and its tests take the sentence from `ConversationTexts.held(GoalHold.BACKGROUND_REFUSED)`). Otherwise stop and ask the controller.
- Measure the counts before the first change of Task 1, and note them in the task's report: run `scripts\gradle.ps1 :app:testDebugUnitTest :core:conversation:test :core:screen:testDebugUnitTest :core:privacy:testDebugUnitTest`, then `foreach ($dir in 'app\build\test-results\testDebugUnitTest', 'core\conversation\build\test-results\test', 'core\screen\build\test-results\testDebugUnitTest', 'core\privacy\build\test-results\testDebugUnitTest') { $n = 0; Get-ChildItem $dir -Filter *.xml | ForEach-Object { $n += [int]([xml](Get-Content $_.FullName -Raw)).testsuite.tests }; "$dir $n" }`, then `"app device $(@(Get-ChildItem app\src\androidTest -Recurse -Filter *.kt | Select-String -Pattern '@Test\b').Count)"; "privacy device $(@(Get-ChildItem core\privacy\src\androidTest -Recurse -Filter *.kt | Select-String -Pattern '@Test\b').Count)"`, then `scripts\gradle.ps1 --stop`. These are A₀ (`:app` JVM, with `app/src/testDebug`), C₀ (`:core:conversation`, after Tasks A1 and A2 and plans 7B to 7E), S₀ (`:core:screen` JVM), P₀ (`:core:privacy` JVM), AD₀ (`:app`'s device tests) and PD₀ (`:core:privacy`'s device tests, which Task 14 runs, with Task 3's one more). `:core:agent` (G₀; Task A1 counts its own change), `:tools:eval` (E₀) and the Pester tests do not change in Tasks 1 to 15. Each task gives its counts as "N + k", where N is the count before the task. If a count differs, stop and ask the controller. At `284eb0ec`, measured so for the amendment of 2026-11-26 (with `:core:agent:test` added to the Gradle command and `core\agent\build\test-results\test` to the loop): A₀ = 717, C₀ = 293, G₀ = 315, S₀ = 101, P₀ = 94, AD₀ = 469 and PD₀ = 35.
- Before each task, check that every text that the task replaces exists, and exists once, in its file as the earlier tasks left it; and run `git status --short`. The worktree may hold other agents' uncommitted files. If a text is missing, or a file that the task changes shows changes that are not the task's own, stop and ask the controller.
- Tasks A1, A2 and 1 to 13a use no device: their device tests are compiled with `:app:assembleDebugAndroidTest`, and the controller runs every device test in Task 14 on kestrel-api37. Task 15 waits for the owner and runs on the A71.

## Not in this plan

- `scripts\hook.ps1`'s entries for the hook `conversation-type` and for plan 7D's and plan 7E's hooks, `:tools:eval`'s turns, its typed answers and the tasks of design 15.3 (plan 7G). Until then the controller's steps send the hooks with `am broadcast`, as plans 7D and 7E do.
- The main spec's update (plan 7.0). Task 14 writes the typed start's findings for spike S9 into `.superpowers\sdd\progress.md`.
- Any change to the voice's rules (`Rules`, `Requests`, `VoiceAnswers`), to the goal service or to the agent's loop. The voice channel changes only in its card and in its voice while Kestrel is off.

## Changes to the design that this plan makes

These are this plan's readings of the design where it leaves a choice open. The controller confirms them with the owner, or the plan changes before Task A1. "Decisions for the controller" at the end lists them with a recommendation each.

- **A pick's choices reach its card (Task A1).** The agent's question carries up to 4 choices with design 10.3's labels, from `OwnerPort.ask` through plan 7B's `Dialogue` and `ConversationOwner` to `ChannelQuestion.choices`; a choice answers as the owner's number does. `look_closer` offers its badges' numbers only when there are at most 4 and they run from 1.
- **One card per question number.** `CardDesk` is the overlay card's one owner in `:app`: every channel that asks question N joins one card, drawn with the choices of the caller that has the most (the voice's screenshot card `[Yes, No]` and the text channel's `[Yes, No, Reply…]` become one card with "Reply…", drawn again with a new arming delay), and each caller gets the tapped `CardChoice`; a caller whose choices lack it keeps waiting for its own answer. A different question replaces the card, and the replaced card gives no answer instead of a "no", so that a card that went away never declines a confirmation.
- **A pick's card.** With choices, it has them and Cancel; without choices, as `look_closer`'s grid of many numbers, it has a low number keypad on the card itself (the number, delete, OK and Move; the digits in two rows) and Cancel, so that the owner enters the number where he sees the grid, and no "Reply…", which would cover the grid with Kestrel's screen. The keypad gives only a number from 1 to the pick's highest number, which the agent's question carries (Task A1), and the desk answers the pick with it; Move takes the card to the other end of the screen, so that it hides no number that the owner needs.
- **A screen button's answer names its question** (Task A2), so that a tap on the screen's copy that comes late answers no later question.
- **The screen shows the open card while it is in front**, on the past conversations' pages too. An overlay card at the bottom would cover the field and the keyboard, so the desk hands the card to the screen while it is in front, and the screen shows it above the field. A button on the screen's copy reaches the conversation as an input (`InputIntent.Answer`), so that the gate gives the goal a fresh look at the screen (design 10.3 and D5), and it ignores taps for the card's first 600 ms, as the overlay's do. A confirmation, a hand-over and a pick are answered only on the card over the app (design 12.2 and 12.3): their copy on the screen shows the words and "Show the app", which takes the screen to the back, where the overlay's card answers them.
- **"Reply…"** on the overlay opens the screen with the question in focus, and the overlay's card hides until the screen is in front or 5 s have passed; "Reply…" on the screen focuses the field. Both hold the question for 5 minutes (D21).
- **A held goal's Continue while the phone is locked** waits for the owner's unlock, as the goal notification's Continue does (plan 7E): the card desk keeps the tap with its number, the bar says "Unlock the phone to continue.", and the tap answers once the screen is on and the phone unlocked; the card shows again meanwhile, so that its Stop can answer in its place (the whole-plan review's M2; it was "Unlock the phone, then tap Continue.", and a second tap); the card's buttons are Stop, then Continue (design 10.3), and the held notification's own buttons stay Cancel and Continue (plan 7E).
- **A confirmation is always a button.** Typed words never confirm: while a confirmation waits, the owner's typed words reach it as a command, which counts as "no" (plan 7B: a command at an open question is its answer as text), as the design's table of 10.3 gives a confirmation no "Reply…".
- **The screenshot question's card** has Yes, No and "Reply…": the design's "Cancel" would mean the same as No.
- **The screen leaves the front when the goal that the owner started on it works and its service runs.** After the owner sends a typed command, answers a goal's question, taps Continue or taps the microphone, the screen calls `moveTaskToBack(true)` once the conversation's goal acts and `GoalForeground` says that its service is in the foreground and that Android has answered every start that Kestrel asked for. So the service starts while `ConversationActivity` is in front (design 10.6), a typed request that starts no goal (such as "remember that …") keeps its reply on the screen, and a spoken command after the microphone leaves the screen as a typed one does. One reducer, `ConversationScreenModel`, takes every input in any order, and the screen's `ViewModel` keeps it across configuration changes. The screen never leaves on its own while the owner did not act on it, so a tap on the goal notification's Open interrupts the goal (D13) and shows Continue and Cancel.
- **The microphone** calls `Listening.assist()`, the path of the side key and the assistant gesture (design 10.1): Kestrel turns on if it is off and says "Yes?".
- **Every input of the owner's on the screen, on a card or on the notification is his act of its moment.** Such a number counts where the input lets a goal's round act (a start, a resume, the end of a hand-over or a pause); an answer to a goal's own question lets nothing act, so its number is dropped there, as a spoken or typed answer's is. Plan 7D's `ScreenTurns` decides who acts on the screen by the numbers of the owner's inputs: a goal's round asks an instant command to let go only for an input that came after the command started, and otherwise waits until it has ended (amendment of 2026-11-26; Task 2 is obsolete). So a typed input and a button on the screen's copy of a card take their number as they arrive (Task 4), a button of the goal notification does (plan 7E), and a tap on the card over the app does too: the card desk takes it as the tap reaches it, and the answer carries it into the engine (`CardAnswer`, Task 1), so that a round that the tap resumes never cancels a command that the owner started after his tap; a tap on a hand-over's Continue carries its number to the round in the same way (`HandOverAnswer`).
- **The bar's goal progress is this plan's, and the bar has one writer.** Design 10.5's "Step 12 · 3 of 7 · Replying to Mila" and "On hold: say 'Kestrel, continue' or open Kestrel." are in no earlier plan; `GoalBar` gives them for both channels from the conversation's view, and plan 7D's `OwnedBar`, which the app graph builds and gives to listening, the text channel and the owner's parts, shows them as `BarOwner.Progress` only while no kept text (a question, a hand-over's sentence) holds the bar and no other text showed in the last 4 s.
- **The speech of typed replies with listening off.** With "Speak replies to typed commands" on and Kestrel off, the voice channel speaks a typed conversation's words with `AppOffVoice`, which loads the owner's voice for the reply inside plan 7E's `GoalForeground.whileSpeaking` and closes it 30 s after the last reply.
- **Deleting past conversations.** One past conversation is deleted from the list or its transcript after a question; "Delete all past conversations" deletes every closed conversation, also one in a format that this version cannot read, in one transaction of the archive's own (`RecordArchive.deleteClosed`, which plan 7C's table carries out) with the index without them, keeps the logs and the archive's key, unlike "delete history", and reads no body (`ConversationStore.deleteClosed(keep)` to `ConversationEngine.deleteAllPast`); it raises the store's generation first, as "delete history" does, and while the index cannot be read it deletes nothing and says so. One conversation's deletion is checked by the engine's loop, which refuses to delete the open conversation or the one that a running switch opens, and done by its writer after the writes before it; "Delete all" keeps every conversation that is open at some moment between the owner's ask and its transaction (review M1), waits at the engine's writer's barrier for the writes queued before it, and then runs beside the engine's inbox and writer, never in them, because the store may wait minutes for its index (review N2); the store keeps the open conversation, which its index never holds. So a conversation that the owner continued meanwhile is kept either way, and the page shows "Deleting past conversations…" until the outcome comes back, which it reads out.
- **The list's search** uses the search's own rule over plan 7B's index of past conversations, through `HistorySearch.matching`, which `:core:conversation` gains, once the typing pauses and off the main thread; until the list's first read ends, it says "Loading past conversations…", and a later read (a search, or the list after a deletion) keeps the rows that are shown under a thin progress line until its own come (review B-M3); and while the index cannot be read, "Kestrel can't read past conversations right now. It will try again later." (design 9.5).
- **The round limits on the Conversations page** are choices within the design's ranges (50 to 2,000 replies, 5 to 240 minutes, 10 to 5,000 AI Credits), so that a voice user can set them by name: 50, 100, 200, 500, 1,000 or 2,000 replies; 5, 15, 30, 60, 120 or 240 minutes; 10, 100, 250, 500, 1,000, 2,500 or 5,000 AI Credits.
- **The settings app.** `MainActivity` leaves the launcher but stays exported, because the scripts and the device tests start it with `am start`, and it follows only the extras that it follows today. Its window is not guarded, as today; the Conversations page's dialog is.
- **The card's window title** becomes "Kestrel question" (it was "Kestrel confirmation").
- **The held goal's sentence after Android's refusal** becomes "Open Kestrel and tap Continue." (`ConversationTexts.OPEN_AND_CONTINUE`, design 10.6) in Task 8, once opening Kestrel shows the screen with its Continue.
- **The screen says when the goal waits behind the owner's command.** While the working goal's round waits until the owner's instant command has ended (plan 7E's `ConversationView.waitsBehindCommand`), the screen shows plan 7E's "Waiting for your command to finish" above Stop, where the goal shows, as the goal notification does, because the bar belongs to the command then (Tasks 6 and 7).
- **The device tests shorten the engine's timers** through a seam, `ConversationEngine(timerMillis)` and `AppGraph.conversationTimers`, which only the device tests set (design 15.2: "the waits of D21, shortened by an instrumentation argument"). They type every request with the run's marker and delete only the conversations that they made, and the controller's checks delete only the hook's own (`conversation-control --es do delete-typed`).

## Amendment of 2026-11-26 (after plans 7D and 7E)

Plans 7D and 7E are built and on `main` at `284eb0ec`. This amendment makes Tasks 1 to 15 apply to that code and follow plan 7D's "Changes to the design" and "Interfaces for 7E and 7F" and plan 7E's amendments and "Interfaces for 7F and 7G" as built. Each change:

- **The base.** "Before you start" names `284eb0ec`, its `Select-String` checks five more of plan 7D's and 7E's names (23 matches), and it gives the measured base counts: A₀ = 717, C₀ = 293, G₀ = 315, S₀ = 101, P₀ = 94, AD₀ = 469, PD₀ = 35. The plan checker runs clean from Task 1 against `284eb0ec` alone, and from Tasks 4, 8 and 11 on the earlier tasks' simulated files.
- **Task 1: a tap on the card over the app keeps its own number** (the controller's decision of 2026-11-26). The card desk takes the tap's `InputIds` number as the tap arrives (`CardTap`), the channels return it with the answer (`CardAnswer`, the new return of `OwnerChannel.ask`), and `ConversationOwner.reply`, `ContinueAnswered.tap`, `ResumeGoal.tap` and `OwnerAct.tap` carry it into `RunningGoal.actedFor`; the engine's `inputIds` goes. `ConversationEngineTest`'s case of the card's yes is rewritten, test first, so that a command that the owner started after his tap is not cancelled by the round. A Continue or Cancel tapped on a hand-over's card is an owner input too (the controller's decision on item 38): `OwnerChannel.handOver` returns `HandOverAnswer(end, act)`, and `ConversationOwner` gives the act to the running round (`acted`) before the hand-over returns; `aContinueTappedOnAHandOversCardNamesTheTapsOwnNumber` is new and fails first (C₀ + 1).
- **Task 1: `QuestionCard`'s number field** is `entered`; as `display` it was hidden by `View.display` inside `apply` and did not compile (found by the rebuild check).
- **Task 2 is obsolete.** Plan 7D's `ScreenTurns` already has the final rule (`take(after, waiting)`: cancel only for a newer input of the owner's, otherwise wait), so the task keeps its heading without steps, and its counts, file row and interfaces go.
- **Task 3** meets the engine fixture without `{ ++inputs }`, and the held goal's new sentence moves to Task 8.
- **Task 4: typed words.** `TypedInputs.read(text, view)` reads with `Requests.typed` (the exact name; "never mind", "nothing", "forget it" stay the owner's words) and gives `InputIntent.Answer(answer, question)` with the view's open question; `TypedConversation.view()` replaces `asking()`; `TextChannel` writes through `OwnedBar` and returns `CardAnswer`. `TypedInputsTest` has 4 tests (+ 1).
- **Task 5: one bar writer.** Plan 7D's `OwnedBar` becomes the bar's only writer: the app graph builds it and gives it to `Listening`, `TextChannel` and `AndroidOwnerSupport` (whose `releaseBar` went to the screen session directly), with `BarOwner.Progress` and `progress(text?)`; `SharedFeedbackBar` is not created, and `OwnedBarTest` holds its three tests. Typed speech with listening off already runs inside plan 7E's `GoalForeground.whileSpeaking` (`AppOffVoice`).
- **Tasks 6 and 7: the wait behind the owner's command.** `GoalControls.Working(behindCommand)` follows plan 7E's `ConversationView.waitsBehindCommand`, and the screen shows `GoalTexts.BEHIND_COMMAND` above Stop.
- **Task 8: the refusal's sentence** becomes "Open Kestrel and tap Continue." (`ConversationTexts.OPEN_AND_CONTINUE`) here, once opening Kestrel shows the screen with its Continue; `GoalNoticesTest` changes with it. The banner reads the setup through `SetupStatusReader`, which reads Kestrel's service through `AccessibilitySettings`. No task adds a notification, so no `KestrelNotification` ID is needed.
- **Tasks 10 to 12: faults that the rebuild check found.** `TypedBoundaryTest` imports `assert`; the strings `past_unavailable` and `conversations_delete_unavailable` escape their apostrophe.
- **Task 13a** meets Task 1's desk (`inputIds`, `CardTap`), and the hook's press takes its number at the press, as the owner's tap does.
- **Task 14** is written for a delegated agent, as plan 7E's Task 8: step helpers, `Get-OwnerDataState` and `Compare-OwnerData` before and after every step, the stand-in round set and checked before anything typed reaches a round, a stop and a report at the first failure, the headless emulator and the exact name (`Test-KestrelAvdAnswer`), `Connect-AccessibilityService` to put the accessibility setting back, the typed start's audio focus, and no `--live`.
- **Task 15** waits for the owner with plan 7E's Task 9 safeguards: the A71 helpers, its data checks, the stand-in before typing, listening off throughout, the shade's rule, and a put-back step that runs whatever ended the task.
- **The counts.** `:app` JVM A₀ + 69 (786), device AD₀ + 33 (502), `:core:conversation` C₀ + 12 (305), `:core:agent` G₀ (315), `:core:screen` S₀ (101), `:core:privacy` P₀ + 1 (95) and PD₀ + 1 (36), as the rebuild check measured them.
- **Decisions** 7, 8, 24, 27, 28, 33 and 34 follow these changes; 36 to 49 are new; 38 (a hand-over's Continue on the card), 39 and 41 to 49 are settled by the controller.
- **The plan reviews of 2026-11-26** (`review-plan-7f-a.md` and `review-plan-7f-b.md`), each finding fixed at its root:
  - **One hand-over per question (A-I1, Task 4).** `OwnerChannel.handOver(handOver, part)` with `HandOverPart { OWN, CARD }`: beside the voice channel, which owns a spoken conversation's hand-over, the text channel gets `CARD` and shows only the card, so it never calls `HandOverSignals.opened`, and Kestrel turning off reopens no page.
  - **One source decides how words are played (A-I2, Task 5).** `ChannelOutput.Said(text, ownVoice)`: `ConversationOwner` decides as it routes the words, and `VoiceChannel` follows the output and no longer reads listening's state (its `voiceOn` goes), so a spoken word that waited while the owner turned Kestrel off is not spoken.
  - **A typed answer after its question ended (A-M3, Task 4)** shows `ConversationTexts.ANSWER_ENDED` on the bar and is not read again as a command, which could act where the owner meant only to answer (design 12.2); a spoken one is dropped, as before.
  - **The card comes back with a new screen session (A-M4, Task 1).** `OwnerScreen.connections` counts the sessions (`AndroidOwnerSupport` takes the `StateFlow` of sessions), and `CardDesk` shows its card again on each; the desk's own test. The desk shows "Unlock the phone, then tap Continue." through `notice` (nit N4), and the text channel takes the desk as `SharedCards` (N5).
  - **The bar (A-M5, A-M6, Task 5).** A final reply holds the bar against the progress until its follow-up countdown takes it over; the owner's Stop stops Kestrel's own voice (`OffVoice.stop`, `VoiceChannel.stopSpeech`, called from the engine's steps in the app graph); the debug hook `feedback` writes through `OwnedBar`.
  - **Task 1's checks and Task 7's counts (A-I3, A-M1, A-M7)**: Step 6's searches name `AppGraph.kt`'s path and quote their patterns; Task 7 counts from AD₆.
  - **The hooks' tests on kestrel-api37 and the A71 (B-I1, Tasks 14 and 15)** clear the hooks' earlier marks with one `delete-typed` before they type (after the eval suite in Step 7, and before the stand-in), so the last deletion counts exactly 1.
  - **One rule marks the hooks' conversations (B-I2, Tasks 13 and 13a).** `ConversationHooks.type()` marks nothing; Task 13a's step rule also marks the conversation that an untagged typed input of the hook opened (`CommandTags.untagged`), and its test.
  - **A held goal's card (B-M1)**: Stop, then Continue (design 10.3); the held notification keeps Cancel and Continue (plan 7E).
  - **The call watch (B-M2, Task 8).** `PhoneStateGrant` in the app graph starts it after a grant of READ_PHONE_STATE; both activities call it as they come to the front, so the launcher's path has it too.
  - **The list keeps its rows while it searches (B-M3, Task 11)**, under a thin progress line; "Loading past conversations…" shows only until the first read ends.
  - **"Delete all" in the engine (B-M4, Tasks 3 and 12).** `ConversationEngine.allPastDeletion` says whether one runs; a second call waits for it and deletes nothing again; the page draws the engine's state, also after the owner came back to it (an engine test and a device test).
  - **Task 14 puts back the kept set and the overlay's app-op (B-M5)**: Step 1 checks Kestrel's notification listener, and Step 9 gives it back and sets the overlay's mode of Step 1 when a test left another.
  - **One rule for AI Credits (B-M6, Task 12).** `Profile.countsCredits` in `:core:providers`, read by `AppModelPort` and the Conversations page.
  - **Task 15 checks a clean tree (B-M7)** with `git status --short`; nits: Task 13a's Produces line, the self-review's placeholders, the page's KDoc on values that are no choice, `MainActivity.freshIntent`'s KDoc, and `repeatOnLifecycle` for the screen's leaves.
  - **The re-checks of `297cce7c`** (A: Fix first for R1 and R2; B: Ready, with N1, N2 and two nits): Task 1's and Task 5's per-test counts are current (A-R1). A final reply that gets no follow-up window lets the bar go: the Rules say so (`Effect.NoFollowUp`, one rule over every way that a reply loses its window: unspoken, Kestrel off, a newer reply, no window possible), and listening releases `BarOwner.FinalReply` on it (A-R2, Task 5; `RulesTest` and `OwnedBarTest`). The owner's Stop that does not come by voice is the same stop as a spoken one: `VoiceChannel.stopSpeech` (was `stopOwnVoice`) also stops the voice session's speech through `VoicePath.stopSpeaking` and the Rules' `Event.StopSpeaking` (A's nit). The Conversations page shows a "Delete all" outcome only for a deletion that it started or saw running (B-N1, Task 12; a device test). A row that stays on the list while it is read again has no Delete until the list is current (B-N2, Task 11). `SettingsAppTest`'s line break, and `ProvidersPage.spendingText` reads `Profile.countsCredits` (B's nits).
  - **Commits**: Tasks 4 and 5 commit their `:core:conversation` changes, Task 8 its `PhoneStateGrant`, and Task 12 `Profile.kt` and `AppModelPort.kt`.

## Amendment of 2026-11-27 (Tasks 11 to 15 follow the built tasks' review fixes)

Tasks A1 to 10 are built on `sp3`, with the review fixes that followed them (`a730005c..1b1b8f81`). Task 11's implementer stopped at the plan checker, because two of Step 6's old texts had changed. This amendment makes Tasks 11 to 15 apply to `1b1b8f81` and follow what the fixes changed in meaning. Each change:

- **Task 11, Step 6: the view model's imports.** `ConversationScreenViewModel.kt` no longer imports `receiveAsFlow` (the screen's inputs come through `ScreenFeed`), so the replacement starts from its `InputIntent`, `Flow` and `StateFlow` imports and adds `ConversationId`, `MutableStateFlow`, `asStateFlow`, `update` and `kotlinx.coroutines.launch`, which `confirmDelete` needs and which the view model no longer imports either.
- **Task 11, Step 6: the past pages arm their card on each visit** (the controller's decision). `ConversationApp` wraps the conversation in `CompositionLocalProvider(LocalScreenVisit provides visit)` since `53e0823e`, so that the card's buttons arm again on each of the screen's visits in front. The `if (shown != null) PastPages(…) else ConversationScreen(…)` block goes inside that wrapper, so `CardRow` on the past pages reads the same visit and arms again as the conversation's card does. Decision 31 says so.
- **The meaning of the other fixes, checked against Tasks 11 to 15.** No later task's code relies on an older shape: Tasks 11 to 13a use `TextChannel` (`read`, `input`, `submit`, `choose`), `CardDesk` (`open`, `present`, `deliver`, `publish`, `inputIds`), `CommandTags.onConversationStep`, `TypedTests` (whose `finish` now also deletes every conversation that opened while the tests ran, `1b1b8f81`) and `ConversationEngine.deletePast`, `deleteAllPast` (its keep-set filled before its coroutine starts, `e2c95105`) and `allPastDeletion`, all as built. A hook's typed input reaches the screen's model through `TextChannel.sent` and `ScreenFeed`, so it arms the screen per act (`ScreenState.armed: Arm?`) as the owner's typing does, which Task 14's Step 8 relies on. The voice rules of the stop series (`OwnerChannel.take` at emission, the voice chosen at the output's turn by `Said.typedCopy` and `VoicePath.kestrelOn`, whose one source is `Listening.kestrelOn`, a mode switch that is no stop, and `OffVoice.speak(text, mark)` that may return false), `Event.Assist(taps)` with `CommandTaps`, the goal row without buttons while a continue or limit card is open, and the own voice's playback seam (`AppGraph.ownVoicePlayback`, the debug `OwnVoiceWatch`, `4309a7d3`) touch no code of Tasks 11 to 15; "Interfaces for 7G" (the voice channel, the screen's model, the goal on the screen and the device tests' seams), decision 42 and the self-review's names now describe them as built.
- **Task 12, Step 6** says 5 device tests, the number that its Step 1 adds.
- **Task 14, Step 4**: `ConversationViewsTest` (8, with Task 11's), `QuestionFlowTest` (10) and `TypedBoundaryTest` (3) as built; both of `QuestionFlowTest`'s speech tests skip without the voice's models; the run has 36 tests more than plan 7E's (409 run).
- **The counts.** Measured at `1b1b8f81` with the plan's commands: `:app` JVM 813, `:core:conversation` 308 and `:app` device 496 (the review fixes added 42, 3 and 3 to the tasks' own 771, 305 and 493). Each of Tasks 11 to 13a names its start and end, and the end is `:app` JVM 828, device 505 and `:core:conversation` 308; `:core:agent`, `:core:screen` and `:core:privacy` are as before.
- **The checks.** The plan checker runs clean from Tasks 11, 12 and 13 against `1b1b8f81` alone. From Task 13a it runs clean against the commit of Tasks 11 to 13 applied to `1b1b8f81`, and only then: its old texts in `AgentHookAnswers.kt`, `AgentTaskHooks.kt`, `VoiceHooks.kt` and `ConversationHooks.kt` are Task 13's new texts. Tasks 11 to 13a's code, applied to `1b1b8f81` in order, matches the checker's simulated files, builds, and passes `:app:testDebugUnitTest :core:conversation:test :app:compileDebugAndroidTestKotlin :app:lintDebug` with these counts. Tasks 14 and 15 change no PowerShell command. A run from Task 1 against `284eb0ec` now stops at Task 11's two replacements, because Tasks 1 to 10's texts in this plan are the tasks' and not their review fixes'.

## Amendment of 2026-11-27 (the review fixes of Tasks 1 to 13a and the device checks)

Tasks 11 to 13a are built on `sp3`, with their review fixes: `81d3508e` (Task 11's M1), `1459da03` (Task 12's M1), `c8b23b57` (Task 13's M1 and M2) and `f23bd193` (Task 13a's M1 and M2). This amendment makes "Interfaces for 7G", Tasks 14 and 15 and the counts follow every fix since `a730005c` (`git log a730005c..f23bd193`). Tasks 1 to 13a's steps stay as they were carried out. Each change:

- **Interfaces for 7G: the hooks as built.** `CONVERSATION_TYPE` refuses `--es model`, `--ez live` and `--es marker` without `--es tag` (`add --es tag for <options>: typed words without a tag take no options`, `c8b23b57`). `AGENT_ANSWER --es channel text` and `--es channel card` answer only the card of the tagged task's open question, by its number and kind (`the open card is not the question of the task of tag <tag>; read agent-state again`, which replaces the card channel's `the open card is not the question that agent-state read; read it again`), and the text channel answers `the card no longer takes the answer <words>; read agent-state again` when the card changed before the press. `CONVERSATION_CONTROL --es do delete-typed`: every input that a hook gives is the hooks', tagged or not (`voice heard`, agent-answer, `conversation-type`, `do new`), and a mark goes as soon as the owner's input with his words or his choice, or his switch, reaches the conversation, not only when he continues it (`f23bd193`). Wordless inputs (Stop, Continue, New conversation, and an answer of `Silence` or `Interrupted`) keep the mark, which the controller accepted: the goal notification's buttons give them too, and `goal-control do=tap`'s press of its Stop must leave a check's conversation the hooks' own.
- **Tasks 13 and 13a: a note under their code blocks.** Task 13's Step 5, and Task 13a's Steps 3 and 4, say how `c8b23b57` and `f23bd193` changed the code that they show (`ConversationHooks.type`, `typedAnswer`, `cardAnswer`, `CommandTags.onConversationStep`, `untaggedInputs`, `TextChannel.choose`).
- **Task 14 and Task 15, the build.** Step 1 and Task 15's Step 2 check `contains f23bd193: True` (was `contains 284eb0ec`), and HEAD at `f23bd193` or a later commit, such as this amendment's; the build paragraph says so.
- **Task 14, Step 4: the device tests as built.** `ConversationViewsTest` (8, with `53e0823e`'s re-arming on each visit), `ConversationScreenTest` (4, with the launcher's test), `QuestionFlowTest` (10; its two speech tests play through the device tests' seam `AppGraph.ownVoicePlayback` with the debug `OwnVoiceWatch`, which the test sets and clears itself), `TypedBoundaryTest` (3, by name, as `1b1b8f81` left them), `PastConversationsTest` (2, with `81d3508e`'s Delete that waits), `SettingsAppTest` (54, its 5 Conversations page tests by name, with `1459da03`'s Running), `ConversationHooksTest` (3, its `conversation-type` test tagged); `OpenSettingsTest` runs in Step 6. The run is still 409 (AD₀ + 36 = 505 in the source).
- **Task 14, Step 5** names the stop series of Tasks 5 to 8's review fixes and `e6cd8211`, whose device regression the voice's 96 tests are; no fix changed a voice test.
- **Task 14, Step 6:** `OpenSettingsTest` (1) takes the owner's path since `53e0823e`: the launcher's conversation screen, its Settings button, and the conversation screen at the task's root at the end.
- **Task 14, Step 7:** the suite's inputs, tagged or not, its spoken answers through agent-answer among them, are the hooks', so its `deleted=<n>` counts the conversations that their work opened (n of 0 or more).
- **Task 14, Step 8, and Task 15, Step 4:** the clearing `delete-typed` may count the conversations that earlier hook inputs opened, also untagged ones (n of 0 or more); the notification's Stop, and Task 15's `CONVERSATION_TYPE --es text stop`, are wordless and keep the mark, so the last deletion is still `deleted=1`; the typed command's tag carries no option, and Task 15's untagged stop takes none; the device tests' seams (`conversationTimers`, `ownVoicePlayback`) are only for device tests, and no step sets them.
- **Task 14, Step 10** records the counts with the review fixes, as measured at `f23bd193`.
- **The counts.** Measured at `f23bd193` with `scripts\gradle.ps1 :app:testDebugUnitTest :core:conversation:test`, counted from the XML results as "Before you start" does, and `@Test` in `app\src\androidTest`: `:app` JVM 833 (A₀ + 116: the tasks' 828 and the fixes `81d3508e` + 1, `c8b23b57` + 2 and `f23bd193` + 2), `:core:conversation` 308 and `:app` device 505 (no fix of Tasks 11 to 13a added a device test); `:core:agent` 315, `:core:screen` 101 and `:core:privacy` 95 and 36 are as before.
- **The checks.** Every PowerShell snippet of Tasks 14 and 15, with the step helpers before each step that uses them, parses in PowerShell 7 and in Windows PowerShell 5.1. No step's command changed, except the base check of Task 14's Step 1 and Task 15's Step 2.

## Amendment of 2026-11-27 (Task 14's first run: the screen's intents and the test fixes)

Task 14's first run stopped at Step 4 on `6df4a94e`: 16 tests of the conversation screen's classes and `SettingsAppTest.theLogPagesShowTheirEntries` failed. The fixes are on `sp3`: `012ea255` and `16518f16` (the product), and `7f46179a`, `7e755cc0`, `f6866aa4`, `fdfd1a2d` and `51b5517d` (the tests), with their review (`review-screenfix.md`, M1 and M2 fixed). This amendment makes the plan's text follow them. Tasks 8 to 12's code blocks stay as the tasks were carried out; a note under each says what the fixes changed and why. Each change:
- **The screen keeps the intent that started it (Task 8's note under `ConversationActivity`, `012ea255`).** `onNewIntent` called `setIntent(intent)`, so every later intent became the `singleTask` screen's own: `TypedTests.bringScreen`'s `am start`, "Reply…" and `TypedBoundaryTest`'s SEND and VIEW intents. ActivityScenario follows an activity only while its `getIntent()` matches the intent that started it, so the compose rule lost the screen's lifecycle, and its `close()` failed or waited 45 s; and another app's intent, with its words and data, became the screen's own, which design 12.2 rules out. Now a later intent gives the screen only "Reply…"'s question number. `TypedBoundaryTest.anIntentsWordsAndDataNeverReachTheField` checks that the screen keeps its MAIN intent, with no data and no text (Task 10's note).
- **"Reply…"'s question lives in the view model (Task 8's note, `16518f16`, review M1).** `ConversationScreenViewModel.replyTo` and `reply(question)` keep it across a rotation but not across the process, whose question numbers start again; the activity has no saved number. `onCreate` reads the first intent's number only without saved state and when the screen was not reopened from the recent apps (`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`). `QuestionFlowTest`'s Reply test recreates the screen and checks that the field keeps its focus, a check that failed in a red run without the number (Task 9's note, review M2).
- **The screen's keyboard settles before a test taps (Task 8's notes under `TypedTests` and `ConversationScreenTest`, Tasks 9 to 11's notes; `7f46179a`, `fdfd1a2d`).** The window resizes with the keyboard, whose animations run on Android's clock while the test's clock moves Compose's frames, so a tap's up could land beside Send, Cancel or Continue. The new androidTest helper `ScreenKeyboard` has `rule`, which each screen class applies outside its compose rule (order 1, the compose rule at order 2) and which watches each conversation screen's window from `Stage.CREATED` to `Stage.DESTROYED`, and `settle(compose)`, which waits until the keyboard shows exactly while the field has focus and none of its animations runs. `TypedTests.bringScreen(screen, compose)` and `send(compose, words)` take the compose rule and settle, the new `TypedTests.tapSend(compose)` settles before it taps Send, and `TypedBoundaryTest.sendCentre()` settles in place of its 1.5 s sleep.
- **`awaitRow` waits for the list's read (Task 11's notes under `PastConversationsTest` and `PastViews.kt`; `7e755cc0`, `51b5517d`).** The list's search waits with a `delay` that runs on the test's clock, which only a `waitUntil` moves on, and the old `awaitRow` returned on the earlier read's row, whose Delete was off. It now waits until the row shows and the list's progress line, tagged `PAST_READING_TAG` (`past-reading`), is gone.
- **`SettingsAppTest` opens the log pages from home (Task 12's note, `f6866aa4`).** Task 12's "Conversations" entry pushes "Network log" below the home page's scroll area on kestrel-api37, so the three log tests open the log entries with `openFromHome`, which scrolls first.
- **Interfaces for 7G and the file structure** name the helpers as built (`bringScreen(screen, compose)`, `send(compose, words)`, `tapSend(compose)`, `ScreenKeyboard`, `PAST_READING_TAG`), the screen's intent and `ConversationScreenViewModel.replyTo`.
- **Task 14 and Task 15, the build.** Task 14's Step 1 and Task 15's Step 2 check `contains 51b5517d: True` (was `contains f23bd193`), and HEAD at `51b5517d` or a later commit, such as this amendment's; Task 14's build paragraph says so. Task 14's Step 4 names the fixes in its classes, and the run is still 409; Step 10 records the counts as measured at `f23bd193` and unchanged at `51b5517d`.
- **The counts.** No test class is new and no test is added or removed: `:app` JVM 833 (the fixes' report, `:app:testDebugUnitTest` at `51b5517d`), `:app` device 505 (`@Test` in `app\src\androidTest` at `51b5517d`), and `:core:conversation` 308, `:core:agent` 315, `:core:screen` 101 and `:core:privacy` 95 and 36 as before.
- **The checks.** The two changed commands, Task 14's Step 1 and Task 15's Step 2, parse in PowerShell 7 and in Windows PowerShell 5.1. No other command changed.

## Amendment of 2026-11-27 (Task 14's second run: two stale expected answers)

Task 14's second run (5ac066ec) passed every check; it stopped at Step 8 on two answers that the plan had wrong, not Kestrel. `goal-service-state`'s `starts=` counts every start in Kestrel's process, so after Step 7's eval suite it is far above 1: Tasks 14 and 15 now expect `starts=<n>`, the same n in the step's later lines. `voice-task -Clear` answers `each goal's round runs on the phone again` since plan 7D. The device behaviour is as the plan intends.

## Whole-plan review fixes (2026-11-28)

The whole-plan review of plan 7F (`.superpowers\sdd\review-7f-whole.md`, Fix first: I1, I2, M1 to M4 and seven nits) is fixed at the root, one commit per finding or small group, on `sp3` after `6bfa4df6`. Tasks 1 to 13a keep their code blocks as they were carried out; the code as built, this list, "Interfaces for 7G" and the design are the reference. Each decision:

- **I2: the marks' rule runs from every process's start.** The review's fix, a debug `ContentProvider` or `androidx.startup` initializer, would run before `KestrelApplication.onCreate` builds the graph (Android starts the providers between `attachBaseContext` and `onCreate`), so it could reach the graph only by a post to the main thread, which orders it against main-thread work alone. Instead, debug builds replace the application (`app\src\debug\AndroidManifest.xml`, `tools:replace="android:name"`) with `DebugKestrelApplication`, whose `graphBuilt(graph)`, which `KestrelApplication.onCreate` calls after it builds the graph and before it starts any work, calls `HookConversations.watch(dir, tags, install)`: it reads the marks, sets `CommandTags.ownConversation` and `ownerConversation`, and installs `AppGraph.conversationSteps`. `VoiceHooks.install` keeps only listening's tags and the round's witnesses, and `TestHooks` no longer attaches the marks. A release build has `graphBuilt` as an empty step and neither the class nor the rule. Decision 33 drops its sentence on restarts. Test: `HookConversationsTest.theOwnersCommandBeforeAnyHookCallClearsAMarkThatARestartKept`.
- **M1: one statement of which Stop stops speech.** The code is right and stays: `AppGraph` calls `VoiceChannel.stopSpeech()` for every `InputIntent.Stop` input, spoken ones too, because a spoken stop's second stop, taken in the engine's order, drops the outputs that the engine emitted before it handled the Stop, which the Rules' own stop of the speech cannot know. The KDoc of `Event.StopSpeaking` and "Interfaces for 7G" now say "every Stop input of the owner's, however it came", as `VoiceChannel.stopSpeech` and the app graph's comment did. No behaviour changes.
- **M2: the locked Continue waits for the unlock, as decision 27 says.** Waiting is possible on Android without asking it for the unlock (which an overlay cannot do): the owner unlocks the phone himself, and the desk follows the screen's state through `PhoneSignals.screenStates()` (the screen source's `USER_PRESENT`, the same reading that the conversation's lock signals use). So the code follows the texts, not the other way round: `CardDesk` keeps a Continue tapped while `OwnerScreen.locked()` with the number taken at the tap (`HeldContinue`), shows `UNLOCK_TO_CONTINUE` ("Unlock the phone to continue.", was `UNLOCK_FIRST`, "Unlock the phone, then tap Continue."), shows the card again, and delivers the tap once `OwnerScreen.unlocked()` returns, if a caller of its question still takes Continue; another choice, a newer Continue, or the end of the question drops it. The debug card press keeps refusing a locked Continue (`CardPress.LOCKED`), because the hook's answer must say what the press did at once. Device behaviour changes (the card's locked Continue). Tests: `CardDeskTest.aContinueTappedWhileThePhoneIsLockedAnswersAfterTheUnlockWithItsTapsNumber` (replaces `aContinueTappedWhileThePhoneIsLockedWaitsForTheUnlock`) and `aStopTappedWhileAContinueWaitsForTheUnlockAnswersInItsPlace`.
- **M3: the typed command's arm is anchored where the order is known.** `TextChannel.submit` reads `conversation.view()` before it gives the input to the conversation and emits `SentInput(intent, before)` on `sent` (was `SharedFlow<InputIntent>`); `ConversationScreenRules.sent(state, sent)` reads both `leaves` and the arm (`Arm.Command(before.command)`, `Arm.Goal(before.id, before.goalRecord)`) from that view, so the order in which `ScreenFeed`'s two collectors deliver the view and the input no longer matters, for the hook's typed input on another thread too. The review named only the command's number; the whole view is carried because `leaves` and `Arm.Goal` read the same view and had the same race. Device behaviour changes (a typed request without a goal, such as "remember that …", now always ends its arm). Test: `ConversationScreenModelTest.aTypedCommandsArmEndsWithItsReplyAlsoWhenItsViewCameFirst`, and `TextChannelTest.typedWordsBecomeTextInputsThatTheScreenHearsOf` checks each input's `before`.
- **M4: one count of Kestrel's own voice.** `AppOffVoice.replies` went, with its counter, which counted every reply whose player ended, refused, interrupted and stopped ones too, as "spoken". The debug `OwnVoiceWatch` is the one count: it now counts how each reply ended for Kestrel (a held reply that a stop ended counts as `STOPPED`, not as the player's `FINISHED` of its audio), so `ended(Spoke.FINISHED)` is the replies heard. `QuestionFlowTest`'s two speech tests and `ConversationScreenTest.aTypedCommandLeavesKestrelsScreenAndWorksOnTheAppBelowWithListeningOff` set `AppGraph.ownVoicePlayback` to a watch and read `ended(Spoke.FINISHED)`, `ended(Spoke.STOPPED)` for the copy that the answer ended, and `playing` with no end at all for "nothing was spoken". No product behaviour changes; the device tests change. Test: `OwnVoiceWatchTest.aHeldReplyWaitsAfterItsAudioUntilAStopAndCountsAsStopped` (was `…CountsAsFinishedByThePlayer`) and the release test's count.
- **The nits.** `ConversationOwner.show` went: `say` and the tests use `take(output).show()`, the one path of the engine (the review's premise that only the tests used it missed `say`, which now takes its output the same way). `VoiceHooks.kindOf` names each `InputIntent` explicitly (`command`, `answer`, `stop`, `continue`, `newconversation`, `continueconversation`, `continuepast`, `deletehistory`, `remember`, `forget`, `saveroutine`), so a renamed class changes no wire value. `agent-answer`'s errors name the answer's kind, never its words (`AgentHookAnswers.answerKind`: the button's word, `a number` or `a text answer`; `AgentHookAnswersTest.anAnswersErrorNamesAButtonsWordOrTheAnswersKindAndNeverItsWords`). `PastViews`' `ListRead` prints the search's length, not its text. The engine's and `ConversationOwner`'s `voiceOn` are `kestrelOn` (the machine's `ConversationState.voiceOn` and `ConversationEvent.Voice`, its mirror, keep their names). The KDoc and comments of `OwnedBar`, `QuestionCard`, `CommandTaps`, `ScreenFeed`, `ConversationScreenModel` and the engine are wrapped at 120 columns. `OverlayCards.withShown` stays in release code (decision 20): the hook's check and answer must run under the card desk's own lock, with the card that the desk last gave the overlay (`presented`, review R3 and plan 7G's N2); a debug wrapper of `OwnerScreen.card`, the only seam that would keep it out of release, sees a card only through the screen's calls, which end after the desk has moved on, so it would bring back the race that N2 closed. No behaviour changes but the hook's error texts.

## File structure

| File | Responsibility |
|---|---|
| `core/agent/.../port/OwnerPort.kt`, `loop/OwnerTalk.kt`, `loop/TracingOwner.kt`, `loop/LookCloser.kt`, `testFixtures/.../Fakes.kt`; `core/conversation/.../channel/Dialogue.kt`, `OwnerChannel.kt`, `ConversationOwner.kt`; `app/.../agent/AppOwnerPort.kt`; tests `AgentToolsTest.kt`, `ConversationOwnerTest.kt` | Task A1: the agent's question carries a pick's choices and its highest number (amends plans 7A and 7B; runs before plan 7B's Task 8) |
| `core/conversation/.../model/OwnerInput.kt`, `machine/ConversationMachine.kt`; test `ConversationMachineTest.kt` | Task A2: a button on the screen's copy of a card names its question (amends plan 7B; runs with Task A1) |
| `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/CardQuestion.kt`, `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/QuestionCard.kt` (new); `ScreenSession.kt`, `CardPresence.kt`; `core/conversation/.../channel/OwnerChannel.kt`, `ConversationOwner.kt`, `machine/ConversationEffect.kt`, `ConversationEvent.kt`, `ConversationMachine.kt`, `GoalRules.kt`, `engine/ConversationEngine.kt`, fixtures `FakeChannel.kt`, test `ConversationEngineTest.kt`; `ConfirmationCard.kt` and `core/screen/src/main/res/values/strings.xml` (deleted); `app/src/main/kotlin/dev/kestrel/app/conversation/OwnerCard.kt`, `CardDesk.kt` (new), `OwnerSupport.kt`, `VoiceChannel.kt`, `AppGraph.kt`, `app/src/main/res/values/strings_card.xml` (new); tests `OwnerCardTest.kt`, `CardDeskTest.kt` (new), `VoiceChannelTest.kt`, device `QuestionCardTest.kt` (new, for `ConfirmationCardTest.kt`), `GridBarAndCardTest.kt`, `AgentEndToEndTest.kt` | Task 1: the question card and the card desk |
| none (obsolete since plans 7D and 7E as built) | Task 2: a typed round waits for a running instant command |
| `core/conversation/.../store/RecordArchive.kt`, `ConversationStore.kt`, `ArchivedConversationStore.kt`, `HistorySearch.kt`, `engine/ConversationEngine.kt`, `engine/PastDeletion.kt` (new), fixtures `MemoryArchive.kt`; `core/privacy/.../db/ConversationRow.kt`, `conversations/ConversationArchiveStore.kt`; `app/.../conversation/RoomRecordArchive.kt`, `AppGraph.kt`; tests `ArchivedConversationStoreTest.kt`, `HistorySearchTest.kt`, `ConversationEngineTest.kt`, `ConversationArchiveStoreTest.kt`, `KestrelDatabaseTest.kt`, `RoomRecordArchiveTest.kt` | Task 3: the conversation's parts for the screen, the list and the device tests |
| `app/src/main/kotlin/dev/kestrel/app/conversation/TypedInputs.kt`, `TextChannel.kt`, `KestrelScreen.kt` (new); `VoiceChannel.kt`; tests `TypedInputsTest.kt`, `TextChannelTest.kt` (new) | Task 4: the typed reading and the text channel |
| `app/src/main/kotlin/dev/kestrel/app/conversation/GoalBar.kt`, `OffVoice.kt` (new); `app/.../listen/OwnedBar.kt`, `Listening.kt`, `OwnerSupport.kt`, `VoiceChannel.kt`, `goal/GoalForeground.kt`, `AppGraph.kt`; tests `GoalBarTest.kt`, `OwnedBarTest.kt` (new), `VoiceChannelTest.kt`, `TextChannelTest.kt`, `ListeningTest.kt`, `GoalForegroundTest.kt` | Task 5: the bar's one writer and its progress, typed replies spoken with listening off, and the service's state for the screen |
| `app/src/main/kotlin/dev/kestrel/app/conversation/screen/Transcript.kt`, `ConversationScreenRules.kt`, `ConversationScreenModel.kt` (new); tests `TranscriptTest.kt`, `ConversationScreenRulesTest.kt`, `ConversationScreenModelTest.kt` (new) | Task 6: the screen's models |
| `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationViews.kt`, `TranscriptViews.kt`, `ScreenGuard.kt` (new), `app/src/main/res/values/strings_conversation.xml` (new); device test `ConversationViewsTest.kt` (new) | Task 7: the screen's views |
| `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationActivity.kt`, `ConversationApp.kt`, `ConversationScreenViewModel.kt`, `app/.../settings/SetupState.kt` (new); `core/conversation/.../ConversationTexts.kt`, `AndroidManifest.xml`, `AppGraph.kt`, `ListeningService.kt`, `SetupChecklist.kt`, `SettingsApp.kt`, `MainActivity.kt`; tests `SetupChecklistTest.kt`, `GoalNoticesTest.kt`, device `TypedTests.kt`, `ConversationScreenTest.kt` (new), and `ScreenKeyboard.kt` (new, from Task 14's first run, `7f46179a`) | Task 8: the conversation screen as the launcher |
| device test `app/src/androidTest/kotlin/dev/kestrel/app/conversation/QuestionFlowTest.kt` (new) | Task 9: questions, held goals, cards and typed speech on the device |
| `build-logic/.../MergedManifestArgument.kt` (new), `app/build.gradle.kts`, `testapp/.../ObscuringActivity.kt` (new), `testapp/src/main/AndroidManifest.xml`; tests `ScreenManifestTest.kt`, device `TypedBoundaryTest.kt` (new) | Task 10: the typed-input boundary |
| `app/.../conversation/screen/PastList.kt`, `PastPagesRules.kt`, `PastConversations.kt`, `PastViews.kt` (new); `ConversationApp.kt`, `ConversationViews.kt`, `ConversationScreenViewModel.kt`, `AppGraph.kt`, `app/src/main/res/values/strings_past.xml` (new), `strings_conversation.xml`; tests `PastListTest.kt`, `PastPagesRulesTest.kt`, device `PastConversationsTest.kt` (new), `ConversationViewsTest.kt` | Task 11: past conversations |
| `app/.../settings/ConversationsPage.kt`, `app/src/main/res/values/strings_conversations_page.xml` (new); `SettingsApp.kt`, `SettingsData.kt`, `ListeningPage.kt`; device test `SettingsAppTest.kt` | Task 12: the Conversations page |
| debug `HookConversations.kt` (new), `TestHooks.kt`, `ConversationHooks.kt`, `CommandTags.kt`, `VoiceHooks.kt`, `AgentTaskHooks.kt`, `AgentHookAnswers.kt`; tests `HookConversationsTest.kt` (new), `CommandTagsTest.kt`, `AgentHookAnswersTest.kt`, device `ConversationHooksTest.kt` | Task 13: the hook `conversation-type`, typed answers and the hooks' own conversations |
| `app/.../conversation/CardDesk.kt`; debug `CardPresser.kt` (new), `AgentTaskHooks.kt`, `AgentHookAnswers.kt`, `CommandTags.kt`, `ConversationHooks.kt`, `VoiceHooks.kt`; tests `CardPresserTest.kt` (new), `CardDeskTest.kt`, `AgentHookAnswersTest.kt`, `CommandTagsTest.kt` | Task 13a: the hook presses the card over the app through debug code, under the card desk's lock, and tagged inputs' opened conversations are the hooks' own until the owner's own command continues them (plan 7G's amendments F1 and F2) |
| none in git but `.superpowers\sdd\progress.md` | Task 14: the checks on kestrel-api37 (the controller's steps) |
| none in git but `.superpowers\sdd\progress.md` | Task 15: the checks on the A71 (waits for the owner) |

Every main path of `:app` is below `app/src/main/kotlin/dev/kestrel/app/`, every JVM test below `app/src/test/kotlin/dev/kestrel/app/`, the debug hooks below `app/src/debug/kotlin/dev/kestrel/app/debug/` and their JVM tests below `app/src/testDebug/kotlin/dev/kestrel/app/debug/`; the steps give each path in full.

---

## Plan 7A and 7B amendments

**Status: built.** Task A1 in `b4f5fcc3` and Task A2 in `ade80de8`; their steps are checked. The rest of this section stays as the record of what they did.

Two decisions of the controller change code that plans 7A and 7B built, so they are tasks of their own, which run earlier than the rest of this plan: right after plan 7B's Tasks 5a to 5d and before its Task 8, as the controller routes them.

- **Task A1 (review items G1 and N1): a pick's choices and its numbers reach its card.** The agent's question carries its choices, at most 4, from the agent through the owner port, plan 7B's `Dialogue` and `ConversationOwner`, to `ChannelQuestion.choices`, which design 10.3's card shows as buttons. A tapped choice answers as the owner's number does: `ChannelAnswer.Pick(position)`, which the agent reads as `Answer.Number(position)`. So a caller offers choices only when choice number i is the answer i. With them comes the pick's highest number (`highest`: its numbers run from 1 to it; 0 when the asker knows no range), which the card's keypad keeps to (Task 1). `look_closer`'s "Which number should I use?" offers its badges' numbers as choices when there are at most 4 of them and they run from 1, as the observation numbers its lines; otherwise it offers none, and the owner gives the number on the card's keypad, by voice, or typed. It gives the highest number whenever its numbers run from 1.
- **Task A2 (review low item c): a button on the screen's copy of a card names its question.** `InputIntent.Answer` gains the number of the question that it answers (`question`, null for the voice's and the typed words' answers, which answer the open question), and the conversation's rules ignore an answer that names another question, so that a tap on the screen's copy that comes late never answers the next question.

The port and its implementations must change together to compile, so Task A1 is one commit. Three texts of plans that are not built yet also implement the port or the dialogue; their plans' committed texts already expect Task A1's code (the end of this section names the commits): plan 7B Task 9's `RecordedOwner` and `LocalRequestsTest.FakeDialogue`, and plan 7D Task 3's `GoalOwner`. Plan 7D's Task 3 deletes `AppOwnerPort.kt`, which Task A1 still changes, because it exists then.

#### Before Tasks A1 and A2

- They need only what exists when they start: plan 7A, and plan 7B's Tasks 1 to 7 and 5a to 5d. Check it: `Select-String -Path core\agent\src\main\kotlin\dev\kestrel\agent\port\OwnerPort.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\channel\Dialogue.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\channel\OwnerChannel.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\model\OwnerInput.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\machine\ConversationMachine.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\store\PastIndex.kt -Pattern 'suspend fun ask\(question: String, kind: AnswerKind\): Answer|suspend fun question\(text: String, kind: QuestionKind, spoken: String = text\): ChannelAnswer|data class ChannelQuestion\(|data class Answer\(val answer: ChannelAnswer\) : InputIntent|is InputIntent.Answer -> MachineStep\(answering|class PastIndex'`. Expected: 6 matches. Otherwise stop and ask the controller.
- Run the plan checker over Tasks A1 and A2 alone, against the branch's HEAD, in PowerShell 7: `$env:PLANX = 'H:\Code\kestrel-sp3\docs\superpowers\plans\2026-11-24-conversations-7f-screen.md'; $env:BASE_REF = (git -C H:\Code\kestrel-sp3 rev-parse HEAD); $env:S3A = 'C:\Users\AleksaLocal\.copilot\session-state\337a39e5-0c45-4e8a-b4a0-4c1d2eef024e\files\no-such-sim'; $env:SIMDIR = $env:S3A; $env:OUTDIR = 'plan7f-sim'; $env:START_TASK = 'A1'; $env:END_TASK = 'A2'; python 'C:\Users\AleksaLocal\.copilot\session-state\337a39e5-0c45-4e8a-b4a0-4c1d2eef024e\files\plan3x-check.py'`. Expected: an `operations:` line, a `files:` line, a `simulated files written:` line, and no line that starts with `PROBLEM:`. Otherwise stop and ask the controller.
- Measure the counts before the first change: `scripts\gradle.ps1 :core:agent:test :core:conversation:test`, then `foreach ($dir in 'core\agent\build\test-results\test', 'core\conversation\build\test-results\test') { $n = 0; Get-ChildItem $dir -Filter *.xml | ForEach-Object { $n += [int]([xml](Get-Content $_.FullName -Raw)).testsuite.tests }; "$dir $n" }`, then `scripts\gradle.ps1 --stop`. These are Gₐ (`:core:agent`) and Cₐ (`:core:conversation`).

Counts of Tasks A1 and A2: `:core:agent` Gₐ + 1 (`AgentToolsTest` + 1); `:core:conversation` Cₐ + 2 (`ConversationOwnerTest` + 1, `ConversationMachineTest` + 1). `:app` JVM unchanged.
### Task A1: The agent's question carries a pick's choices

**Files:**
- Modify: `core/agent/src/main/kotlin/dev/kestrel/agent/port/OwnerPort.kt`, `core/agent/src/main/kotlin/dev/kestrel/agent/loop/OwnerTalk.kt`, `core/agent/src/main/kotlin/dev/kestrel/agent/loop/TracingOwner.kt`, `core/agent/src/main/kotlin/dev/kestrel/agent/loop/LookCloser.kt`, `core/agent/src/testFixtures/kotlin/dev/kestrel/agent/testing/Fakes.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/Dialogue.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/OwnerChannel.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/ConversationOwner.kt`, `app/src/main/kotlin/dev/kestrel/app/agent/AppOwnerPort.kt`
- Test: modify `core/agent/src/test/kotlin/dev/kestrel/agent/AgentToolsTest.kt`, `core/conversation/src/test/kotlin/dev/kestrel/conversation/channel/ConversationOwnerTest.kt`

**Interfaces:**
- Produces: `OwnerPort.ask(question: String, kind: AnswerKind, choices: List<String> = emptyList(), highest: Int = 0): Answer` and `OwnerPort.MAX_CHOICES = 4`; `FakeOwner.choices: List<List<String>>` and `FakeOwner.highest: List<Int>`, one entry per question; `Dialogue.question(text, kind, spoken = text, choices = emptyList(), highest = 0)`; `ChannelQuestion.choices` filled from the question's choices, at most `OwnerPort.MAX_CHOICES`, and `ChannelQuestion.highest`.

Counts: `:core:agent` Gₐ + 1 (`AgentToolsTest` + 1); `:core:conversation` Cₐ + 1 (`ConversationOwnerTest` + 1).

- [x] **Step 1: Write the failing tests**

In `core/agent/src/test/kotlin/dev/kestrel/agent/AgentToolsTest.kt`, replace:

```kotlin
        assertTrue(harness.lastRequestText().contains("The owner picked [1]."))
        assertTrue(harness.screen.badges.isNotEmpty())
        assertEquals(2, harness.eyes.closed)
    }
```

with:

```kotlin
        assertTrue(harness.lastRequestText().contains("The owner picked [1]."))
        assertTrue(harness.screen.badges.isNotEmpty())
        assertEquals(2, harness.eyes.closed)
        // Plan 7F: at most 4 numbers are the card's choices, each answering its own number, and the highest is the last.
        val numbers = harness.screen.badges.last().map { it.number.toString() }
        assertTrue(numbers.size <= OwnerPort.MAX_CHOICES)
        assertEquals(listOf(emptyList(), numbers), harness.owner.choices)
        assertEquals(listOf(0, numbers.size), harness.owner.highest)
    }

    // Plan 7F: with more than 4 numbers on the screen, the card offers none; the owner gives the number on its keypad,
    // which keeps to the highest number.
    @Test
    fun aPickOfMoreThanFourNumbersOffersNoChoices() = runTest {
        val screen = ScreenBuilder.screen("com.example.map") {
            for (row in 0 until 5) button(null, 300 + 200 * row, viewId = "pay$row", suggestedName = "pay")
        }
        val harness = Harness(screen, listOf(look, look, look, call("fail", null, null, null, "reason" to "Stop.")), model = withImages)
        harness.eyes.lines = listOf(OcrLine("Pay now", IntRect(40, 300, 1040, 420), 0.95))
        harness.owner.answers += listOf(Answer.Text("Yes."), Answer.Number(1))

        harness.run("Pay the bill")

        assertEquals(AnswerKind.PICK_NUMBER, harness.owner.asked.last().second)
        assertEquals(emptyList<String>(), harness.owner.choices.last())
        val badges = harness.screen.badges.last()
        assertTrue(badges.size > OwnerPort.MAX_CHOICES)
        assertEquals(badges.size, harness.owner.highest.last())
    }
```

In `core/agent/src/test/kotlin/dev/kestrel/agent/AgentToolsTest.kt`, replace:

```kotlin
import dev.kestrel.agent.port.OcrLine
```

with:

```kotlin
import dev.kestrel.agent.port.OcrLine
import dev.kestrel.agent.port.OwnerPort
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/channel/ConversationOwnerTest.kt`, replace:

```kotlin
    @Test
    fun aPauseWithdrawsTheGoalsQuestionAndClosesTheGateUntilTheResume() = runTest {
```

with:

```kotlin
    // Plan 7F: a pick's choices and its highest number reach the channel's question, and a tapped choice answers its number.
    @Test
    fun aPicksChoicesReachTheChannelAndAChoiceAnswersItsNumber() = runTest {
        typed()
        val answer = async { owner.ask("Which number should I use?", AnswerKind.PICK_NUMBER, listOf("1", "2", "3"), highest = 3) }
        runCurrent()
        val asked = text.asked.single()

        assertEquals(listOf("1", "2", "3"), asked.choices)
        assertEquals(3, asked.highest)
        owner.answer(asked.number, ChannelAnswer.Pick(2))
        assertEquals(Answer.Number(2), answer.await())
    }

    @Test
    fun aPauseWithdrawsTheGoalsQuestionAndClosesTheGateUntilTheResume() = runTest {
```

- [x] **Step 2: Run the tests to see them fail**

Run: `scripts\gradle.ps1 :core:agent:test --tests=dev.kestrel.agent.AgentToolsTest`; then `scripts\gradle.ps1 :core:conversation:test --tests=dev.kestrel.conversation.channel.ConversationOwnerTest`
Expected: FAIL: the compiler does not know `OwnerPort.MAX_CHOICES`, `FakeOwner.choices` and `FakeOwner.highest`, `ConversationOwner.ask` takes no choices and `ChannelQuestion` has no `highest`.

- [x] **Step 3: The port, the run, `look_closer`, the dialogue and the owner**

In `core/agent/src/main/kotlin/dev/kestrel/agent/port/OwnerPort.kt`, replace:

```kotlin
    suspend fun ask(question: String, kind: AnswerKind): Answer
```

with:

```kotlin
    /**
     * Asks [question], whose answer is of [kind]. [choices] are a pick's choices, at most [MAX_CHOICES], which the owner's
     * card shows as buttons (design 10.3 of sub-project 7): a tap on choice number i, from 1, answers [Answer.Number] i,
     * as the owner's spoken or typed number i does, so a caller offers choices only when choice i is the answer i. A pick's
     * numbers run from 1 to [highest], which the card's keypad keeps to; 0 when the caller knows no range.
     */
    suspend fun ask(question: String, kind: AnswerKind, choices: List<String> = emptyList(), highest: Int = 0): Answer
```

In `core/agent/src/main/kotlin/dev/kestrel/agent/port/OwnerPort.kt`, replace:

```kotlin
    companion object {
        /** How long [leaveKestrel] waits for Kestrel's own screen to leave (design 10.2 of sub-project 7). */
        const val KESTREL_LEAVE_MILLIS = 3_000L
    }
```

with:

```kotlin
    companion object {
        /** How long [leaveKestrel] waits for Kestrel's own screen to leave (design 10.2 of sub-project 7). */
        const val KESTREL_LEAVE_MILLIS = 3_000L

        /** A pick shows at most this many choices on its card (design 10.3 of sub-project 7). */
        const val MAX_CHOICES = 4
    }
```

In `core/agent/src/main/kotlin/dev/kestrel/agent/loop/OwnerTalk.kt`, replace:

```kotlin
    /** Asks [question] until it gets an answer: never [Answer.Interrupted], whose third time in a row is [Answer.Silence]. */
    suspend fun ask(question: String, kind: AnswerKind): Answer {
        while (true) {
            run.gate()
            val answer = run.limits.waiting { run.ports.owner.ask(question, kind) }
```

with:

```kotlin
    /**
     * Asks [question], with a pick's [choices] and [highest] number, until it gets an answer: never [Answer.Interrupted],
     * whose third time in a row is [Answer.Silence].
     */
    suspend fun ask(question: String, kind: AnswerKind, choices: List<String> = emptyList(), highest: Int = 0): Answer {
        while (true) {
            run.gate()
            val answer = run.limits.waiting { run.ports.owner.ask(question, kind, choices, highest) }
```

In `core/agent/src/main/kotlin/dev/kestrel/agent/loop/TracingOwner.kt`, replace:

```kotlin
    override suspend fun ask(question: String, kind: AnswerKind): Answer {
        trace.asked(question)
        val answer = owner.ask(question, kind)
```

with:

```kotlin
    override suspend fun ask(question: String, kind: AnswerKind, choices: List<String>, highest: Int): Answer {
        trace.asked(question)
        val answer = owner.ask(question, kind, choices, highest)
```

In `core/agent/src/main/kotlin/dev/kestrel/agent/loop/LookCloser.kt`, replace:

```kotlin
    // Step 3 (D20): the owner picks a number on the screen.
    private suspend fun numbers(call: AgentCall.LookCloser): Done {
        run.ports.screen.showBadges(badges())
        try {
            val answer = run.talk.ask(WHICH, AnswerKind.PICK_NUMBER)
```

with:

```kotlin
    // Step 3 (D20): the owner picks a number on the screen; at most four numbers are also the card's buttons (plan 7F).
    private suspend fun numbers(call: AgentCall.LookCloser): Done {
        run.ports.screen.showBadges(badges())
        try {
            val answer = run.talk.ask(WHICH, AnswerKind.PICK_NUMBER, choices(), highest())
```

In `core/agent/src/main/kotlin/dev/kestrel/agent/loop/LookCloser.kt`, replace:

```kotlin
    private fun badges(): List<Badge> = run.observation.table.entries.map { Badge(it.number, it.bounds) }
```

with:

```kotlin
    private fun badges(): List<Badge> = run.observation.table.entries.map { Badge(it.number, it.bounds) }

    // The numbers as the card's choices when there are at most OwnerPort.MAX_CHOICES of them and they run from 1, so that
    // choice number i answers the number i (plan 7F); otherwise none, and the owner gives the number.
    private fun choices(): List<String> {
        val numbers = numbered() ?: return emptyList()
        return if (numbers.size > OwnerPort.MAX_CHOICES) emptyList() else numbers.map(Int::toString)
    }

    // The highest number, which the card's keypad keeps to (plan 7F), when the numbers run from 1; otherwise 0, no range.
    private fun highest(): Int = numbered()?.size ?: 0

    private fun numbered(): List<Int>? = run.observation.table.entries.map { it.number }.takeIf { it == (1..it.size).toList() }
```

In `core/agent/src/main/kotlin/dev/kestrel/agent/loop/LookCloser.kt`, replace:

```kotlin
import dev.kestrel.agent.port.OcrLine
```

with:

```kotlin
import dev.kestrel.agent.port.OcrLine
import dev.kestrel.agent.port.OwnerPort
```

In `core/agent/src/testFixtures/kotlin/dev/kestrel/agent/testing/Fakes.kt`, replace:

```kotlin
    val asked = mutableListOf<Pair<String, AnswerKind>>()
```

with:

```kotlin
    val asked = mutableListOf<Pair<String, AnswerKind>>()

    /** Each question's choices, in the order of [asked]. */
    val choices = mutableListOf<List<String>>()

    /** Each question's highest number, in the order of [asked]. */
    val highest = mutableListOf<Int>()
```

In `core/agent/src/testFixtures/kotlin/dev/kestrel/agent/testing/Fakes.kt`, replace:

```kotlin
    override suspend fun ask(question: String, kind: AnswerKind): Answer {
        asked += question to kind
```

with:

```kotlin
    override suspend fun ask(question: String, kind: AnswerKind, choices: List<String>, highest: Int): Answer {
        asked += question to kind
        this.choices += choices
        this.highest += highest
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AppOwnerPort.kt`, replace:

```kotlin
    override suspend fun ask(question: String, kind: AnswerKind): Answer = questions.withLock {
```

with:

```kotlin
    // Plan 7D replaces this port with the conversation's; until then a pick's choices are spoken as today, not shown.
    override suspend fun ask(question: String, kind: AnswerKind, choices: List<String>, highest: Int): Answer = questions.withLock {
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/Dialogue.kt`, replace:

```kotlin
    suspend fun question(text: String, kind: QuestionKind, spoken: String = text): ChannelAnswer
```

with:

```kotlin
    /**
     * Asks [text] of [kind], spoken as [spoken], with a pick's [choices], which its card shows as buttons, and its [highest]
     * number, which its card's keypad keeps to (design 10.3; 0 for no range).
     */
    suspend fun question(text: String, kind: QuestionKind, spoken: String = text, choices: List<String> = emptyList(), highest: Int = 0): ChannelAnswer
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/OwnerChannel.kt`, replace:

```kotlin
 * form and [spoken] the voice's; [choices] are a pick's at most 4 choices; [confirmation] is a confirmation's, which the
 * card shows as today. toString prints no words.
 */
data class ChannelQuestion(
    val number: Int,
    val text: String,
    val kind: QuestionKind,
    val spoken: String = text,
    val choices: List<String> = emptyList(),
    val confirmation: Confirmation? = null,
) {
    override fun toString(): String =
        "ChannelQuestion(number=$number, kind=$kind, text=${text.length} chars, spoken=${spoken.length} chars, choices=${choices.size}, confirmation=$confirmation)"
```

with:

```kotlin
 * form and [spoken] the voice's; [choices] are a pick's at most 4 choices; [confirmation] is a confirmation's, which the
 * card shows as today; a pick's numbers run from 1 to [highest], which the card's keypad keeps to (0: no range; plan 7F).
 * toString prints no words.
 */
data class ChannelQuestion(
    val number: Int,
    val text: String,
    val kind: QuestionKind,
    val spoken: String = text,
    val choices: List<String> = emptyList(),
    val confirmation: Confirmation? = null,
    val highest: Int = 0,
) {
    override fun toString(): String =
        "ChannelQuestion(number=$number, kind=$kind, text=${text.length} chars, spoken=${spoken.length} chars, choices=${choices.size}, " +
            "confirmation=$confirmation, highest=$highest)"
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/ConversationOwner.kt`, replace:

```kotlin
    private class Draft(
        val text: String,
        val kind: QuestionKind,
        val spoken: String = text,
        val confirmation: Confirmation? = null,
    )
```

with:

```kotlin
    private class Draft(
        val text: String,
        val kind: QuestionKind,
        val spoken: String = text,
        val confirmation: Confirmation? = null,
        val choices: List<String> = emptyList(),
        val highest: Int = 0,
    )
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/ConversationOwner.kt`, replace:

```kotlin
    override suspend fun question(text: String, kind: QuestionKind, spoken: String): ChannelAnswer =
        dialogue(Draft(text, kind, spoken), goal = false)

    override suspend fun ask(question: String, kind: AnswerKind): Answer = answerOf(dialogue(Draft(question, kindOf(kind)), goal = true))
```

with:

```kotlin
    override suspend fun question(text: String, kind: QuestionKind, spoken: String, choices: List<String>, highest: Int): ChannelAnswer =
        dialogue(Draft(text, kind, spoken, choices = choices, highest = highest), goal = false)

    override suspend fun ask(question: String, kind: AnswerKind, choices: List<String>, highest: Int): Answer =
        answerOf(dialogue(Draft(question, kindOf(kind), choices = choices, highest = highest), goal = true))
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/ConversationOwner.kt`, replace:

```kotlin
        val asked = ChannelQuestion(waiting.number, draft.text, draft.kind, draft.spoken, confirmation = draft.confirmation)
```

with:

```kotlin
        val asked = ChannelQuestion(
            waiting.number,
            draft.text,
            draft.kind,
            draft.spoken,
            choices = draft.choices.take(OwnerPort.MAX_CHOICES),
            confirmation = draft.confirmation,
            highest = draft.highest,
        )
```

- [x] **Step 4: Run the tests and the builds**

Run: `scripts\gradle.ps1 :core:agent:test :core:conversation:test :app:testDebugUnitTest :app:assembleDebug; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL; `:core:agent` Gₐ + 1, `:core:conversation` Cₐ + 1, `:app` JVM unchanged.

- [x] **Step 5: Commit**

```bash
git add core/agent core/conversation/src/main/kotlin/dev/kestrel/conversation/channel core/conversation/src/test/kotlin/dev/kestrel/conversation/channel/ConversationOwnerTest.kt app/src/main/kotlin/dev/kestrel/app/agent/AppOwnerPort.kt
git commit -m "feat(agent, conversation): a pick's choices and its highest number reach the channels' question, and look_closer offers up to four numbers as choices" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task A2: A button on the screen's copy of a card names its question

Review low item (c): a button on the conversation screen's copy of a card reaches the conversation as an input (Task 4's `TextChannel.choose`), which the conversation's rules give to whichever question is open when they take it. A tap that comes late could then answer the next question. The answer now names its question, and the rules ignore an answer that names another one. The voice's and the typed words' answers name none and answer the open question, as today.

**Files:**
- Modify: `core/conversation/src/main/kotlin/dev/kestrel/conversation/model/OwnerInput.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/ConversationMachine.kt`
- Test: modify `core/conversation/src/test/kotlin/dev/kestrel/conversation/machine/ConversationMachineTest.kt`

**Interfaces:**
- Produces: `InputIntent.Answer(answer: ChannelAnswer, question: Int? = null)`.

Counts: `:core:conversation` + 1 (`ConversationMachineTest` + 1).

- [x] **Step 1: Write the failing test**

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/machine/ConversationMachineTest.kt`, replace:

```kotlin
        val answered = step(asking, ConversationEvent.Dialogue(DialogueEvent.Answered(4))).state
        assertEquals(GoalPhase.ACTING, answered.goal!!.phase)
        assertNull(answered.question)
    }
```

with:

```kotlin
        val answered = step(asking, ConversationEvent.Dialogue(DialogueEvent.Answered(4))).state
        assertEquals(GoalPhase.ACTING, answered.goal!!.phase)
        assertNull(answered.question)
    }

    // Plan 7F: a button on the screen's copy of a card names its question, so that a tap that came late answers no other.
    @Test
    fun anAnswerThatNamesAnotherQuestionAnswersNothing() {
        val asking = asked(acting(), 4, QuestionKind.YES_NO)

        assertEquals(
            ConversationEffect.AnswerQuestion(4, ChannelAnswer.Yes, leftScreen = true),
            step(asking, typed(InputIntent.Answer(ChannelAnswer.Yes, question = 4))).effect<ConversationEffect.AnswerQuestion>(),
        )
        assertFalse(step(asking, typed(InputIntent.Answer(ChannelAnswer.Yes, question = 3))).has<ConversationEffect.AnswerQuestion>())
    }
```

- [x] **Step 2: Run the test to see it fail**

Run: `scripts\gradle.ps1 :core:conversation:test --tests=dev.kestrel.conversation.machine.ConversationMachineTest`
Expected: FAIL: `InputIntent.Answer` takes no question.

- [x] **Step 3: The answer names its question**

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/model/OwnerInput.kt`, replace:

```kotlin
    /** The answer to the open question as the channel read it; [ChannelAnswer.Silence] when the voice's wait ended. */
    data class Answer(val answer: ChannelAnswer) : InputIntent
```

with:

```kotlin
    /**
     * The answer to the open question as the channel read it; [ChannelAnswer.Silence] when the voice's wait ended. A button
     * on the conversation screen's copy of a card names its [question], and answers no other (plan 7F); the voice's and the
     * typed words' answers name none and answer the open question.
     */
    data class Answer(val answer: ChannelAnswer, val question: Int? = null) : InputIntent
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/ConversationMachine.kt`, replace:

```kotlin
            is InputIntent.Answer -> MachineStep(answering, listOf(ConversationEffect.AnswerQuestion(open.number, intent.answer, leftScreen = typed)))
```

with:

```kotlin
            // A screen button that names another question came late: the question that it answered has gone (plan 7F).
            is InputIntent.Answer ->
                if (intent.question != null && intent.question != open.number) {
                    MachineStep(state)
                } else {
                    MachineStep(answering, listOf(ConversationEffect.AnswerQuestion(open.number, intent.answer, leftScreen = typed)))
                }
```

- [x] **Step 4: Run the tests**

Run: `scripts\gradle.ps1 :core:conversation:test; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL; `:core:conversation` Cₐ + 2 after Tasks A1 and A2.

- [x] **Step 5: Commit**

```bash
git add core/conversation/src/main/kotlin/dev/kestrel/conversation/model/OwnerInput.kt core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/ConversationMachine.kt core/conversation/src/test/kotlin/dev/kestrel/conversation/machine/ConversationMachineTest.kt
git commit -m "feat(conversation): an answer may name its question, and one that names another question answers nothing" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### The owner ports and the dialogue that plans 7B and 7D create

After Task A1, every implementation of the owner port and of the dialogue takes `choices` and `highest`, and passes them on. Three of them are in plans that are not built yet, whose committed texts already expect Task A1's code, because Tasks A1 and A2 run before plan 7B's Task 8: plan 7B's Task 9 since `51e9ef1b`, and plan 7D's Task 3 since `410ae0fd`. For the record, the changed texts:

- Plan 7B, Task 9 ("Kestrel's own requests"), `core/conversation/src/main/kotlin/dev/kestrel/conversation/local/RecordedOwner.kt`, old text:

```kotlin
    override suspend fun ask(question: String, kind: AnswerKind): Answer {
        traced(TraceEntry.Asked(RecordText.of(question)))
        val answer = owner.ask(question, kind)
```

new text:

```kotlin
    override suspend fun ask(question: String, kind: AnswerKind, choices: List<String>, highest: Int): Answer {
        traced(TraceEntry.Asked(RecordText.of(question)))
        val answer = owner.ask(question, kind, choices, highest)
```

- Plan 7B, Task 9, `core/conversation/src/test/kotlin/dev/kestrel/conversation/local/LocalRequestsTest.kt`, `FakeDialogue`, old text:

```kotlin
        override suspend fun question(text: String, kind: QuestionKind, spoken: String): ChannelAnswer {
```

new text:

```kotlin
        override suspend fun question(text: String, kind: QuestionKind, spoken: String, choices: List<String>, highest: Int): ChannelAnswer {
```

- Plan 7D, Task 3 ("The switch-over"), `app/src/main/kotlin/dev/kestrel/app/agent/GoalOwner.kt`, old text:

```kotlin
    override suspend fun ask(question: String, kind: AnswerKind): Answer {
        watcher.forgetOwnActions()
        return owner.ask(question, kind)
```

new text:

```kotlin
    override suspend fun ask(question: String, kind: AnswerKind, choices: List<String>, highest: Int): Answer {
        watcher.forgetOwnActions()
        return owner.ask(question, kind, choices, highest)
```

Plan 7D also deletes `AppOwnerPort.kt` (its Task 3); Task A1's change there keeps the build green until then. Task A2 needs no other plan's change: every other `InputIntent.Answer` names its answer alone, and the new `question` defaults to null.

---
### Task 1: The question card and the card desk

Design 10.3 of sub-project 7: `QuestionCard` in `:core:screen`'s `overlay` package replaces `ConfirmationCard` and keeps its rules: an accessibility overlay that takes touches only on itself, the arming delay (`CARD_ARMING_MILLIS`), and `ScreenActions` acting on nothing while a card shows (D32 item 20 of sub-project 5). Its buttons and root drop a touch that passed through another app's window (design 12.2). A pick whose question carries no choices, such as `look_closer`'s "Which number should I use?" over many numbers, gets a number keypad on the card itself, so that the owner enters the number where he sees the numbered grid, and Kestrel's screen never covers the grid. The keypad gives only a number from 1 to the pick's highest number (Task A1's `highest`), and the desk answers a pick with any such number (review N1). It is low, two rows of five digits under one row with the number, its deletion, OK and Move, and Move takes the card to the other end of the screen, so that the card never hides the number that the owner needs (review low item a).

Plan 7D's interfaces ask for one card per question number, whichever channel asks it. `CardDesk` in `:app` is the card's one owner: every channel that asks question N joins one card, drawn with the choices of the caller that has the most (the voice's screenshot card `[Yes, No]` and the text channel's `[Yes, No, Reply…]` are one card with "Reply…"), and each caller gets the tapped `CardChoice`. A caller whose card lacks the tapped choice (the voice and "Reply…") keeps waiting. The desk shows its card on the overlay while the conversation screen is not in front, and gives it to the screen otherwise (Task 7). A card's Continue that the owner taps while the phone is locked waits for his unlock, as the goal notification's Continue does (plan 7E). The voice channel's confirmation and screenshot cards move onto the desk now; the text channel joins it in Task 4.

A tap on the card over the app is the owner's act of its moment, as a spoken or typed input and a button of the goal notification are (plan 7E's `GoalActionReceiver`): the desk takes its number among his inputs (`InputIds.next`) when the tap reaches it, before any caller's coroutine runs, and every caller that the tap answers gets that one number (`CardTap`). The channels give it to the conversation with the answer (`CardAnswer`, the new return of `OwnerChannel.ask`), and the engine lets the round that a yes on "Continue the goal?" resumes act for that number (plan 7D's `RunningGoal.actedFor`). Before, a tap came without a number, and the engine took the next one when its loop handled the answer (`ConversationEngine`'s `inputIds`): an instant command that the owner started after his tap, before the loop, was then older than the round's act, so the round cancelled it (`ScreenTurns.take`, `after > since`). Now the round waits for it, as plan 7D's rule says for an act that came before the command. A tap on the Continue or Cancel of a hand-over's card (or a permission request's) is an act of the owner's in the same way, as a spoken "Kestrel, continue" is (plan 7D's `EndHandOver(…, act)`): the channel's hand-over returns its end with the tap's number (`HandOverAnswer`, the new return of `OwnerChannel.handOver`), and `ConversationOwner` gives the number to the running round (`acted`, which the engine points at `RunningGoal.actedFor`) before the hand-over returns to it, so that the round's next screen turn names it: it may cancel an instant command that the owner started before his tap, and waits for one that he started after it. Before, such a Continue gave the round no act, and the round waited for every command. Every act of the owner's now comes with its number, so the engine numbers nothing itself, and its `inputIds` goes.

**Files:**
- Create: `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/CardQuestion.kt`, `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/QuestionCard.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/OwnerCard.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/CardDesk.kt`, `app/src/main/res/values/strings_card.xml`
- Modify: `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt`, `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/CardPresence.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/OwnerChannel.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/ConversationOwner.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/ConversationEffect.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/ConversationEvent.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/ConversationMachine.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/GoalRules.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, `core/conversation/src/testFixtures/kotlin/dev/kestrel/conversation/testing/FakeChannel.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/OwnerSupport.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`
- Delete: `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/ConfirmationCard.kt`, `core/screen/src/main/res/values/strings.xml`, `app/src/androidTest/kotlin/dev/kestrel/app/screen/ConfirmationCardTest.kt`
- Test: create `app/src/test/kotlin/dev/kestrel/app/conversation/OwnerCardTest.kt`, `app/src/test/kotlin/dev/kestrel/app/conversation/CardDeskTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/screen/QuestionCardTest.kt`; modify `core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt`, `app/src/test/kotlin/dev/kestrel/app/conversation/VoiceChannelTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/screen/GridBarAndCardTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/agent/AgentEndToEndTest.kt`

**Interfaces:**
- Consumes: plan 7B's `ChannelQuestion(number, text, kind, spoken, choices, confirmation, highest)` (its choices and its highest number filled by Task A1), `QuestionKind`, `ChannelAnswer`, `OwnerChannel`, `ConversationOwner`, `ConversationEvent.ContinueAnswered`, `GoalRules.continueAnswered` and `resume`, `ConversationEffect.ResumeGoal` and `OwnerAct`; Task A1's `OwnerPort.MAX_CHOICES`; plan 7D's `OwnerScreen`, `AndroidOwnerSupport`, `VoiceChannel`, `InputIds.next()`, `RunningGoal.actedFor` and the engine's `inputIds`, which this task removes.
- Produces: `:core:screen`'s `data class CardQuestion(key: Int, text: String, buttons: List<String>, pad: NumberPad? = null)`, `data class NumberPad(delete: String, enter: String, move: String, highest: Int = 0)` with `takes(value)`, `sealed interface CardEnd { Tapped(button: Int); Number(value: Int); Withdrawn }`, `CARD_TITLE = "Kestrel question"` and `CARD_ARMING_MILLIS = 600L` and `suspend fun ScreenSession.ask(question: CardQuestion): CardEnd` (in place of `confirm(question: String): Boolean`); `:app`'s `internal sealed interface CardChoice` (`Yes`, `No`, `Confirm`, `Cancel`, `Continue`, `Stop`, `Reply`, `Pick(number)`, each with `answer: ChannelAnswer?` and `label: Int?`), `internal data class OwnerCard(key, text, choices, picks, question, kind, numberPad, highest)` with `answersOnScreen` and `takes(choice)`, `OwnerCard.of(question: ChannelQuestion, reply: Boolean)` and `OwnerCard.handOver(key: Int, text: String)`; `internal data class CardTap(choice: CardChoice, act: Long)`; `internal interface OwnerCards { suspend fun ask(card: OwnerCard, onReply: () -> Unit = {}): CardTap }` and `internal interface SharedCards : OwnerCards { val open: StateFlow<OwnerCard?> }`; `internal class CardDesk(screen: OwnerScreen, scope: CoroutineScope, notice: (String) -> Unit = {}, replyOpenMillis: Long = REPLY_OPEN_MILLIS, inputIds: () -> Long = InputIds::next, clock: () -> Long) : SharedCards` with `screenInFront(inFront: Boolean)`, `REPLY_OPEN_MILLIS = 5_000L` and `UNLOCK_FIRST = "Unlock the phone, then tap Continue."`, which `notice` shows when a Continue waits for the unlock; `OwnerScreen.card(card: OwnerCard): CardChoice?` (in place of `card(question: String): Boolean?`), `OwnerScreen.locked(): Boolean` and `OwnerScreen.connections: Flow<Int>`, which counts the screen sessions that have connected, so that the desk shows a card that got no answer again on a new session (review A-M4); `AndroidOwnerSupport(context, sessions: StateFlow<ScreenSession?>, bar, open, calls)` (in place of `session: () -> ScreenSession?`); `VoiceChannel(voice, screen, cards: OwnerCards, audio, handOvers, permissions, audioSettleMillis)`; `AppGraph.cardDesk: CardDesk`; `:core:conversation`'s `data class CardAnswer(answer: ChannelAnswer, act: Long)` (package `dev.kestrel.conversation.channel`) and `OwnerChannel.ask(question): CardAnswer` (in place of `ChannelAnswer`), `data class OwnerReply(answer: ChannelAnswer, tap: Long?)` and `ConversationOwner.reply(text, kind): OwnerReply`, `ConversationEvent.ContinueAnswered(answer, tap: Long? = null)`, `ConversationEffect.ResumeGoal(command, cause, steer, input, tap: Long? = null)`, `OwnerAct(input, tap: Long? = null)` with `number: Long?`, `data class HandOverAnswer(end: HandOverEnd, act: Long?)` and `OwnerChannel.handOver(handOver): HandOverAnswer` (in place of `HandOverEnd`), `ConversationOwner(channels, permissions, voiceOn, routing, acted: (Long) -> Unit = {}, events)`, the engine's `running` read by `acted` (`@Volatile`), `GoalRules.continueAnswered(state, answer, tap, now)` and `resume(state, goal, input, steer, now, tap = null)`, `FakeChannel.taps: () -> Long`; the engine's constructor without `inputIds`.

Counts: `:app` JVM A₀ + 11 (`OwnerCardTest` 3, `CardDeskTest` 7, `VoiceChannelTest` + 1). `:core:conversation` C₀ + 1 (`ConversationEngineTest`: its case of the card's yes is rewritten for the tap's own number, and `aContinueTappedOnAHandOversCardNamesTheTapsOwnNumber` is new). `:core:screen` S₀, unchanged. Device tests AD₀ + 2 (`QuestionCardTest` 6 in place of `ConfirmationCardTest`'s 4).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/dev/kestrel/app/conversation/OwnerCardTest.kt`:

```kotlin
package dev.kestrel.app.conversation

import dev.kestrel.conversation.channel.ChannelQuestion
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.ChannelAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The buttons of Kestrel's question card (design 10.3 of sub-project 7). */
class OwnerCardTest {
    private fun card(kind: QuestionKind, reply: Boolean = true, picks: List<String> = emptyList()): OwnerCard =
        OwnerCard.of(ChannelQuestion(7, "Question?", kind, choices = picks), reply)

    @Test
    fun eachKindHasItsButtonsAndOnlyTheKindsThatTakeWordsHaveReply() {
        assertEquals(listOf(CardChoice.Yes, CardChoice.No, CardChoice.Reply), card(QuestionKind.YES_NO).choices)
        assertEquals(listOf(CardChoice.Confirm, CardChoice.Cancel), card(QuestionKind.CONFIRM).choices)
        assertEquals(listOf(CardChoice.Reply, CardChoice.Cancel), card(QuestionKind.FREE).choices)
        assertEquals(listOf(CardChoice.Yes, CardChoice.No, CardChoice.Reply), card(QuestionKind.SCREENSHOT).choices)
        // A resuming button comes last, as on the goal notification (plan 7E).
        assertEquals(listOf(CardChoice.Stop, CardChoice.Continue), card(QuestionKind.CONTINUE_GOAL).choices)
        assertEquals(listOf(CardChoice.Stop, CardChoice.Continue), card(QuestionKind.LIMIT).choices)
        assertEquals(listOf(CardChoice.Yes, CardChoice.No), card(QuestionKind.SWITCH).choices)
        assertEquals(listOf(CardChoice.Yes, CardChoice.No), card(QuestionKind.DELETE_HISTORY).choices)
        // The voice's cards have no Reply…: a confirmation's and the screenshot question's.
        assertEquals(listOf(CardChoice.Yes, CardChoice.No), card(QuestionKind.SCREENSHOT, reply = false).choices)
        // Only the overlay answers a confirmation and a pick, over the app and its numbers (design 12.2 and 10.3).
        assertFalse(card(QuestionKind.CONFIRM).answersOnScreen)
        assertFalse(card(QuestionKind.PICK, picks = listOf("1", "2")).answersOnScreen)
        assertTrue(card(QuestionKind.YES_NO).answersOnScreen)
    }

    @Test
    fun aPickShowsItsChoicesOrAKeypadAndCancelAndEachButtonGivesItsAnswer() {
        val choices = OwnerCard.of(ChannelQuestion(3, "Which number should I use?", QuestionKind.PICK, choices = listOf("1", "2", "3", "4", "5")), reply = true)

        assertEquals(listOf("1", "2", "3", "4"), choices.picks)
        assertEquals(listOf(CardChoice.Pick(1), CardChoice.Pick(2), CardChoice.Pick(3), CardChoice.Pick(4), CardChoice.Cancel), choices.choices)
        assertFalse(choices.numberPad)
        assertEquals(3, choices.key)
        assertEquals(3, choices.question)

        val numbers = OwnerCard.of(ChannelQuestion(8, "Which number should I use?", QuestionKind.PICK, highest = 20), reply = true)
        assertEquals(listOf(CardChoice.Cancel), numbers.choices)
        assertTrue(numbers.numberPad)
        assertEquals(20, numbers.highest)

        assertEquals(ChannelAnswer.Pick(4), CardChoice.Pick(4).answer)
        assertEquals(ChannelAnswer.Yes, CardChoice.Confirm.answer)
        assertEquals(ChannelAnswer.Yes, CardChoice.Continue.answer)
        assertEquals(ChannelAnswer.No, CardChoice.Stop.answer)
        assertEquals(ChannelAnswer.No, CardChoice.Cancel.answer)
        assertNull(CardChoice.Reply.answer)
        assertNull(CardChoice.Pick(1).label)
    }

    @Test
    fun aHandOversCardHasContinueAndCancelNoQuestionAndOnlyTheOverlayAndPrintsNoWords() {
        val card = OwnerCard.handOver(-2, "Sign in to Viber yourself, then tap Continue.")

        assertEquals(listOf(CardChoice.Cancel, CardChoice.Continue), card.choices)
        assertEquals(-2, card.key)
        assertNull(card.question)
        assertFalse(card.answersOnScreen)
        assertFalse(card.toString().contains("Viber"))
    }
}
```

Create `app/src/test/kotlin/dev/kestrel/app/conversation/CardDeskTest.kt`:

```kotlin
package dev.kestrel.app.conversation

import dev.kestrel.conversation.channel.QuestionKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** One card per question number, whichever channel asks it (design 10.3 of sub-project 7; plan 7D's interfaces). */
@OptIn(ExperimentalCoroutinesApi::class)
class CardDeskTest {
    private val overlay = FakeOverlay()
    private var taps = 0L
    private val notices = mutableListOf<String>()

    private fun TestScope.desk() = CardDesk(overlay, backgroundScope, notice = { notices += it }, replyOpenMillis = 5_000, inputIds = { ++taps })

    private val voiceCard = OwnerCard(6, "May I take a screenshot?", listOf(CardChoice.Yes, CardChoice.No), question = 6, kind = QuestionKind.SCREENSHOT)
    private val textCard = voiceCard.copy(choices = listOf(CardChoice.Yes, CardChoice.No, CardChoice.Reply))

    @Test
    fun twoChannelsOfOneQuestionShareOneCardWithTheMostChoicesAndBothGetTheTap() = runTest {
        val desk = desk()
        val voice = async { desk.ask(voiceCard) }
        val text = async { desk.ask(textCard) }
        runCurrent()

        assertEquals(textCard, overlay.shown.last())
        assertEquals(textCard, desk.open.value)
        overlay.tap(CardChoice.Yes)
        runCurrent()

        // One tap is one act of the owner's: both callers get its number, which the desk took as the tap reached it.
        assertEquals(CardTap(CardChoice.Yes, 1), voice.await())
        assertEquals(CardTap(CardChoice.Yes, 1), text.await())
        assertNull(desk.open.value)
    }

    @Test
    fun replyAnswersOnlyTheCallerThatHasItAndHidesTheCardUntilTheScreenCameOrItsWaitEnded() = runTest {
        val desk = desk()
        var replies = 0
        val voice = async { desk.ask(voiceCard) }
        val text = async { desk.ask(textCard) { replies++ } }
        runCurrent()

        overlay.tap(CardChoice.Reply)
        runCurrent()
        assertEquals(1, replies)
        assertFalse(voice.isCompleted || text.isCompleted)
        val shown = overlay.shown.size
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(shown, overlay.shown.size)

        // The screen did not come: the card shows again after its wait.
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(shown + 1, overlay.shown.size)
        voice.cancel()
        text.cancel()
    }

    @Test
    fun theScreenInFrontTakesTheCardFromTheOverlayAndGivesItBack() = runTest {
        val desk = desk()
        val asking = async { desk.ask(textCard) }
        runCurrent()

        desk.screenInFront(true)
        runCurrent()
        assertEquals(1, overlay.withdrawn)
        assertEquals(textCard, desk.open.value)

        desk.screenInFront(false)
        runCurrent()
        assertEquals(2, overlay.shown.size)
        asking.cancel()
        runCurrent()
        assertNull(desk.open.value)
        assertEquals(2, overlay.withdrawn)
    }

    @Test
    fun aNewerQuestionTakesTheCardAndTheOlderComesBackWhenItEnds() = runTest {
        val desk = desk()
        val older = async { desk.ask(voiceCard) }
        runCurrent()
        val handOver = OwnerCard.handOver(-1, "Sign in yourself, then tap Continue.")
        val newer = async { desk.ask(handOver) }
        runCurrent()
        assertEquals(handOver, overlay.shown.last())

        overlay.tap(CardChoice.Cancel)
        runCurrent()
        assertEquals(CardChoice.Cancel, newer.await().choice)
        assertEquals(voiceCard, overlay.shown.last())
        older.cancel()
    }

    // Plan 7E: a button that resumes a goal counts only after the owner's unlock.
    @Test
    fun aContinueTappedWhileThePhoneIsLockedWaitsForTheUnlock() = runTest {
        val desk = desk()
        val held = OwnerCard(9, "Continue the goal?", listOf(CardChoice.Stop, CardChoice.Continue), question = 9, kind = QuestionKind.CONTINUE_GOAL)
        val asking = async { desk.ask(held) }
        runCurrent()

        overlay.locked = true
        overlay.tap(CardChoice.Continue)
        runCurrent()
        assertFalse(asking.isCompleted)
        assertEquals(2, overlay.shown.size)
        assertEquals(listOf(CardDesk.UNLOCK_FIRST), notices)

        overlay.locked = false
        overlay.tap(CardChoice.Continue)
        runCurrent()
        // The tap that waits for the unlock is no act yet; the one after the unlock is, with the first number.
        assertEquals(CardTap(CardChoice.Continue, 1), asking.await())
    }

    // Review N1 of plan 7F: the keypad's number answers its pick within the grid's range, and a number outside it does not.
    @Test
    fun aKeypadsNumberWithinTheRangeAnswersThePickAndOneOutsideItDoesNot() = runTest {
        val desk = desk()
        val pick = OwnerCard(7, "Which number should I use?", listOf(CardChoice.Cancel), question = 7, kind = QuestionKind.PICK, numberPad = true, highest = 20)
        val asking = async { desk.ask(pick) }
        runCurrent()

        overlay.tap(CardChoice.Pick(21))
        runCurrent()
        assertFalse(asking.isCompleted)
        assertEquals(2, overlay.shown.size)
        overlay.tap(CardChoice.Pick(12))
        runCurrent()

        assertEquals(CardChoice.Pick(12), asking.await().choice)
    }

    // Plan 7F: a card that got no answer, because no screen session was connected or its session closed, shows again when a
    // screen session connects, and its callers keep waiting meanwhile.
    @Test
    fun aCardThatGotNoAnswerShowsAgainWhenAScreenSessionConnects() = runTest {
        val desk = desk()
        val asking = async { desk.ask(textCard) }
        runCurrent()
        overlay.noAnswer()
        runCurrent()
        assertFalse(asking.isCompleted)
        assertEquals(1, overlay.shown.size)

        overlay.connections.value = 2
        runCurrent()
        assertEquals(2, overlay.shown.size)
        assertEquals(textCard, overlay.shown.last())
        asking.cancel()
    }
    private class FakeOverlay : OwnerScreen {
        val shown = mutableListOf<OwnerCard>()
        var withdrawn = 0
        var locked = false
        override val connections = MutableStateFlow(1)
        private var tapped = CompletableDeferred<CardChoice?>()

        fun tap(choice: CardChoice) {
            tapped.complete(choice)
        }

        // The card's session went before an answer: the card gives none.
        fun noAnswer() {
            tapped.complete(null)
        }

        override suspend fun card(card: OwnerCard): CardChoice? {
            shown += card
            tapped = CompletableDeferred()
            try {
                return tapped.await()
            } catch (e: CancellationException) {
                withdrawn++
                throw e
            }
        }

        override fun locked(): Boolean = locked

        override suspend fun releaseBar() {}

        override suspend fun kestrelInFront(): Boolean = false
    }
}
```

In `app/src/test/kotlin/dev/kestrel/app/conversation/VoiceChannelTest.kt`, replace:

```kotlin
    private val channel = VoiceChannel(voice, support, support, support, support, audioSettleMillis = 500)
```

with:

```kotlin
    private val channel = VoiceChannel(voice, support, support, support, support, support, audioSettleMillis = 500)
```

In `app/src/test/kotlin/dev/kestrel/app/conversation/VoiceChannelTest.kt`, replace:

```kotlin
        assertEquals(listOf("Send it to Mila?"), support.cards)

        support.card.complete(true)
        runCurrent()
        assertEquals(ChannelAnswer.Yes, asking.await())
```

with:

```kotlin
        assertEquals(listOf(OwnerCard(5, "Send it to Mila?", listOf(CardChoice.Confirm, CardChoice.Cancel), question = 5, kind = QuestionKind.CONFIRM)), support.cards)

        support.card.complete(CardChoice.Confirm)
        runCurrent()
        // The card's answer carries the number of the owner's tap, which the desk took (plan 7F).
        assertEquals(CardAnswer(ChannelAnswer.Yes, act = 1), asking.await())
```

In `app/src/test/kotlin/dev/kestrel/app/conversation/VoiceChannelTest.kt`, replace:

```kotlin
        support.card.complete(false)
        assertEquals(ChannelAnswer.No, cardOnly.await())
```

with:

```kotlin
        support.card.complete(CardChoice.Cancel)
        assertEquals(CardAnswer(ChannelAnswer.No, act = 1), cardOnly.await())
```

In `app/src/test/kotlin/dev/kestrel/app/conversation/VoiceChannelTest.kt`, replace:

```kotlin
    private fun sending() = Confirmation("Send it to Mila?", "Shall I send it to Mila?", Category.SENDING, listOf("Mila"), "Viber", null, null, null)
```

with:

```kotlin
    // Without a card, as without a screen session, the voice still answers (plan 7F).
    @Test
    fun aCardThatGivesNoAnswerLeavesTheQuestionToTheVoice() = runTest {
        val confirmation = sending()
        val asking = async { channel.ask(ChannelQuestion(5, confirmation.question, QuestionKind.CONFIRM, confirmation.spoken, confirmation = confirmation)) }
        runCurrent()
        support.card.complete(null)
        runCurrent()

        assertFalse(asking.isCompleted)
        assertEquals(Asked(5, "Shall I send it to Mila?", QuestionKind.CONFIRM, voice = true), voice.asked.last())
        asking.cancel()
    }

    private fun sending() = Confirmation("Send it to Mila?", "Shall I send it to Mila?", Category.SENDING, listOf("Mila"), "Viber", null, null, null)
```

In `app/src/test/kotlin/dev/kestrel/app/conversation/VoiceChannelTest.kt`, replace:

```kotlin
    private class FakeSupport : OwnerScreen, OtherAudio, HandOverSignals, PermissionDialog {
        var card = CompletableDeferred<Boolean?>()
        val cards = mutableListOf<String>()
```

with:

```kotlin
    private class FakeSupport : OwnerScreen, OwnerCards, OtherAudio, HandOverSignals, PermissionDialog {
        var card = CompletableDeferred<CardChoice?>()
        val cards = mutableListOf<OwnerCard>()
```

In `app/src/test/kotlin/dev/kestrel/app/conversation/VoiceChannelTest.kt`, replace:

```kotlin
        override suspend fun card(question: String): Boolean? {
            cards += question
            return card.await()
        }
```

with:

```kotlin
        // The desk's answer, with the next number of the owner's inputs for each tap; a card that gives none leaves its
        // caller waiting, as the desk does.
        override suspend fun ask(card: OwnerCard, onReply: () -> Unit): CardTap {
            cards += card
            return CardTap(this.card.await() ?: awaitCancellation(), ++taps)
        }

        override suspend fun card(card: OwnerCard): CardChoice? = error("The voice channel asks through the desk")

        override fun locked(): Boolean = false
```

In `app/src/test/kotlin/dev/kestrel/app/conversation/VoiceChannelTest.kt`, replace:

```kotlin
        var card = CompletableDeferred<CardChoice?>()
        val cards = mutableListOf<OwnerCard>()
```

with:

```kotlin
        var card = CompletableDeferred<CardChoice?>()
        val cards = mutableListOf<OwnerCard>()
        private var taps = 0L
        override val connections = MutableStateFlow(1)
```

In `app/src/test/kotlin/dev/kestrel/app/conversation/VoiceChannelTest.kt`, replace:

```kotlin
        assertEquals(HandOverEnd.DONE_BY_ITSELF, handing.await())
```

with:

```kotlin
        assertEquals(HandOverAnswer(HandOverEnd.DONE_BY_ITSELF, act = null), handing.await())
```

In `app/src/test/kotlin/dev/kestrel/app/conversation/VoiceChannelTest.kt`, replace:

```kotlin
import dev.kestrel.conversation.channel.ChannelQuestion
import dev.kestrel.conversation.channel.QuestionKind
```

with:

```kotlin
import dev.kestrel.conversation.channel.ChannelQuestion
import dev.kestrel.conversation.channel.HandOverAnswer
import dev.kestrel.conversation.channel.QuestionKind
```

In `app/src/test/kotlin/dev/kestrel/app/conversation/VoiceChannelTest.kt`, replace:

```kotlin
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.channel.ChannelOutput
```

with:

```kotlin
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.channel.CardAnswer
import dev.kestrel.conversation.channel.ChannelOutput
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/screen/QuestionCardTest.kt`:

```kotlin
package dev.kestrel.app.screen

import android.graphics.Rect
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.ScrollDirection
import dev.kestrel.screen.overlay.CARD_ARMING_MILLIS
import dev.kestrel.screen.overlay.CardEnd
import dev.kestrel.screen.overlay.CardQuestion
import dev.kestrel.screen.overlay.NumberPad
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The question card's safety (design D32 items 20 and 21 of sub-project 5; design 10.3 of sub-project 7): it ignores taps
 * for its first 600 ms, comes no sooner than 600 ms after the previous card closed, and while it shows Kestrel performs no
 * gesture and no node action. Each button gives its own number, and the keypad gives the number that the owner typed.
 */
@RunWith(AndroidJUnit4::class)
class QuestionCardTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val session get() = screen.session

    private fun question(key: Int, text: String, buttons: List<String> = listOf("Confirm", "Cancel")) = CardQuestion(key, text, buttons)

    private suspend fun awaitButton(label: String): Rect = withTimeout(5_000) {
        var found = screen.overlayButton(label)
        while (found == null) {
            delay(20)
            found = screen.overlayButton(label)
        }
        found
    }

    private fun tap(bounds: Rect) {
        screen.shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
    }

    @Test
    fun aTapInTheCardsFirst600MillisecondsDoesNothing() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val asked = SystemClock.uptimeMillis()
        val answer = async { session.ask(question(1, "Delete the test draft?")) }
        val confirm = awaitButton("Confirm")

        tap(confirm)
        // The card was on the screen for less time than since the question; a slow emulator cannot check the delay.
        if (SystemClock.uptimeMillis() - asked >= CARD_ARMING_MILLIS) {
            answer.cancel()
            assumeTrue("The tap came too late to check the delay", false)
        }
        delay(300)
        assertTrue("A tap within the card's first 600 ms answered it", answer.isActive)

        delay(CARD_ARMING_MILLIS)
        tap(confirm)
        assertEquals(CardEnd.Tapped(0), withTimeout(5_000) { answer.await() })
    }

    @Test
    fun aNewCardComesNoSoonerThan600MillisecondsAfterThePreviousOneClosed() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val first = async { session.ask(question(1, "Send a test message?")) }
        awaitButton("Confirm")

        val replaced = SystemClock.uptimeMillis()
        val second = async { session.ask(question(2, "Delete the test draft?")) }
        assertEquals(CardEnd.Withdrawn, withTimeout(5_000) { first.await() })
        withTimeout(5_000) { while (!screen.overlayShows("Delete the test draft?")) delay(20) }
        val after = SystemClock.uptimeMillis() - replaced
        second.cancel()

        assertTrue("The new card came after $after ms", after >= CARD_ARMING_MILLIS)
    }

    @Test
    fun aQuestionAskedDuringAnotherOnesArmingDelayReplacesIt() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val first = async { session.ask(question(1, "Send a test message?")) }
        awaitButton("Confirm")
        // The second question waits out the arming delay after the first card closed; the third comes during that wait.
        val second = async { session.ask(question(2, "Delete the test draft?")) }
        assertEquals(CardEnd.Withdrawn, withTimeout(5_000) { first.await() })
        val third = async { session.ask(question(3, "Call the test number?")) }
        try {
            assertEquals("The waiting question was not replaced", CardEnd.Withdrawn, withTimeout(5_000) { second.await() })
            withTimeout(5_000) { while (!screen.overlayShows("Call the test number?")) delay(20) }
            assertTrue("The replaced question showed its card", !screen.overlayShows("Delete the test draft?"))
        } finally {
            second.cancel()
            third.cancel()
        }
        withTimeout(5_000) { while (session.cardShowing) delay(50) }
    }

    @Test
    fun noGestureAndNoNodeActionRunWhileTheCardShows() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val send = screen.awaitElement { it.viewId == "$FIXTURES:id/send_button" }
        val field = screen.awaitElement { it.viewId == "$FIXTURES:id/message_field" }
        val answer = async { session.ask(question(1, "Delete the test draft?")) }
        try {
            awaitButton("Confirm")
            assertTrue(session.cardShowing)

            assertEquals(ActionResult.CARD_OPEN, session.actions.tap(send.key))
            assertEquals(ActionResult.CARD_OPEN, session.actions.longPress(send.key))
            assertEquals(ActionResult.CARD_OPEN, session.actions.tapAt(send.bounds.centerX, send.bounds.centerY))
            assertEquals(ActionResult.CARD_OPEN, session.actions.setText(field.key, "Hello"))
            assertEquals(ActionResult.CARD_OPEN, session.actions.scroll(ScrollDirection.DOWN))
        } finally {
            answer.cancel()
        }
        withTimeout(5_000) { while (session.cardShowing) delay(50) }
        assertTrue(session.snapshots.current().snapshot.elements.any { it.label == "Ready" })
    }

    // Design 10.3 of sub-project 7: a pick over many numbers is answered on the card's own keypad, so that the owner sees
    // the numbers while he enters one; it keeps to the pick's range, and its Move key takes the card off the numbers.
    @Test
    fun theKeypadGivesTheNumberThatTheOwnerTyped() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val answer = async { session.ask(CardQuestion(4, "Which number should I use?", listOf("Cancel"), NumberPad("Delete digit", "OK", "Move", highest = 20))) }
        val one = awaitButton("1")
        delay(CARD_ARMING_MILLIS)

        tap(one)
        tap(awaitButton("2"))
        tap(awaitButton("9"))
        // 129 is above the pick's highest number, 20: OK gives nothing.
        tap(awaitButton("OK"))
        delay(SETTLE_MILLIS)
        assertFalse(answer.isCompleted)
        tap(awaitButton("Delete digit"))
        // The card moves to the other end of the screen, off the numbers that it covered, and keeps the number.
        val bottom = awaitButton("OK")
        val moved = SystemClock.uptimeMillis()
        tap(awaitButton("Move"))
        val top = withTimeout(5_000) {
            var found = awaitButton("OK")
            while (found.top >= bottom.top) {
                delay(20)
                found = awaitButton("OK")
            }
            found
        }
        // Review L2: the card arms again where it moved to, so that a tap aimed at what was there answers nothing. A slow
        // emulator that finds the moved card too late cannot check it.
        val early = SystemClock.uptimeMillis() - moved < CARD_ARMING_MILLIS - SETTLE_MILLIS / 5
        tap(top)
        delay(SETTLE_MILLIS / 5)
        if (early) assertFalse("A tap in the moved card's first 600 ms answered it", answer.isCompleted)
        delay(CARD_ARMING_MILLIS)
        if (!answer.isCompleted) tap(top)

        assertEquals(CardEnd.Number(12), withTimeout(5_000) { answer.await() })
    }

    @Test
    fun aCardsButtonGivesItsOwnNumber() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val answer = async { session.ask(question(5, "Which chat?", listOf("Ana", "Mila", "Marko", "Jovana", "Cancel"))) }
        val marko = awaitButton("Marko")

        delay(CARD_ARMING_MILLIS)
        tap(marko)

        assertEquals(CardEnd.Tapped(2), withTimeout(5_000) { answer.await() })
    }

    private companion object {
        /** How long the test waits for the card to settle after a tap that changes nothing, or a move. */
        const val SETTLE_MILLIS = 500L
    }
}
```

Replace the whole `app/src/androidTest/kotlin/dev/kestrel/app/screen/GridBarAndCardTest.kt` with:

```kotlin
package dev.kestrel.app.screen

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.WindowSummary
import dev.kestrel.screen.overlay.CardEnd
import dev.kestrel.screen.overlay.CardQuestion
import dev.kestrel.screen.overlay.CARD_ARMING_MILLIS
import dev.kestrel.screen.overlay.CARD_TITLE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
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

    private fun confirmation(key: Int, text: String) = CardQuestion(key, text, listOf("Confirm", "Cancel"))

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
    fun theCardGivesConfirmWhenTheOwnerTapsIt() = runBlocking { answerWith("Confirm", expected = CardEnd.Tapped(CONFIRM)) }

    @Test
    fun theCardGivesCancelWhenTheOwnerTapsIt() = runBlocking { answerWith("Cancel", expected = CardEnd.Tapped(CANCEL)) }

    @Test
    fun cancellingTheQuestionRemovesTheCard() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val answer = async { session.ask(confirmation(1, "Delete the draft?")) }
        eventually { screen.showsWindow(CARD_TITLE) }

        answer.cancel()

        eventually { !screen.showsWindow(CARD_TITLE) }
    }

    @Test
    fun aNewQuestionWithdrawsTheOldOne() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val first = async { session.ask(confirmation(1, "Send a test message?")) }
        eventually { screen.overlayButton("Confirm") != null }

        val second = async { session.ask(confirmation(2, "Delete the draft?")) }

        assertEquals(CardEnd.Withdrawn, withTimeout(5_000) { first.await() })
        // Only the second card is left, so the tap reaches its Confirm button.
        eventually { screen.overlayShows("Delete the draft?") && !screen.overlayShows("Send a test message?") }
        // A card ignores taps for its first 600 ms (design D32 item 21 of sub-project 5).
        delay(ARMED_MILLIS)
        val confirm = screen.overlayButton("Confirm")!!
        screen.shell("input tap ${confirm.centerX()} ${confirm.centerY()}")
        assertEquals(CardEnd.Tapped(CONFIRM), withTimeout(5_000) { second.await() })
        eventually { !screen.showsWindow(CARD_TITLE) }
    }

    @Test
    fun closingTheSessionWithdrawsTheQuestion() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val answer = async { session.ask(confirmation(1, "Send a test message?")) }
        eventually { screen.overlayButton("Confirm") != null }

        // As the service does when it disconnects. The rule's next start closes this session again.
        withContext(Dispatchers.Main) { session.close() }

        assertEquals(CardEnd.Withdrawn, withTimeout(5_000) { answer.await() })
        eventually { !screen.showsWindow(CARD_TITLE) }
    }

    @Test
    fun snapshotsLeaveTheCardOut() = runBlocking {
        screen.open("ViewsActivity", "Views fixture")
        val answer = async { session.ask(confirmation(1, "Send a test message?")) }
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

    private suspend fun CoroutineScope.answerWith(button: String, expected: CardEnd) {
        screen.open("ViewsActivity", "Views fixture")
        // The question contains both buttons' labels, so the tests also check that the rule finds the buttons.
        val answer = async { session.ask(confirmation(1, "Confirm or cancel a test message?")) }
        val bounds = withTimeout(5_000) {
            var found = screen.overlayButton(button)
            while (found == null) {
                delay(100)
                found = screen.overlayButton(button)
            }
            found
        }

        delay(ARMED_MILLIS)
        screen.shell("input tap ${bounds.centerX()} ${bounds.centerY()}")

        assertEquals(expected, withTimeout(5_000) { answer.await() })
        eventually { !screen.showsWindow(CARD_TITLE) }
    }

    private companion object {
        /** A little more than the 600 ms in which a card ignores taps. */
        const val ARMED_MILLIS = CARD_ARMING_MILLIS + 100

        const val CONFIRM = 0
        const val CANCEL = 1
    }
}
```

In `app/src/androidTest/kotlin/dev/kestrel/app/agent/AgentEndToEndTest.kt`, replace:

```kotlin
        check(VoiceTests.waitUntil(5_000) { screen.showsWindow("Kestrel confirmation") }) { "The card did not show" }
```

with:

```kotlin
        check(VoiceTests.waitUntil(5_000) { screen.showsWindow(CARD_TITLE) }) { "The card did not show" }
```

In `app/src/androidTest/kotlin/dev/kestrel/app/agent/AgentEndToEndTest.kt`, replace:

```kotlin
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
```

with:

```kotlin
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.screen.overlay.CARD_TITLE
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt`, replace:

```kotlin
    // A yes on the card of "Continue the goal?" comes without an input; it gets the next input number, as the owner's act.
    @Test
    fun aYesOnTheCardOfContinueTheGoalNamesTheOwnersActAsANewInput() = scope.runTest {
```

with:

```kotlin
    // Plan 7F: a yes on the card of "Continue the goal?" is the owner's act of the moment of his tap, whose number `:app`
    // takes as the tap reaches Kestrel. An input that he gives after the tap, such as the start of an instant command,
    // before the engine's loop takes the answer, is newer than the round's act, so the round never cancels that command.
    @Test
    fun aYesOnTheCardOfContinueTheGoalNamesTheTapsOwnNumber() = scope.runTest {
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt`, replace:

```kotlin
        text.answers += ChannelAnswer.Yes
        val afters = mutableListOf<Long?>()
        val engine = engine(environment(fail), ArchivedConversationStore(archive, backgroundScope) {}, screen = FakeScreenTurn { after -> afters += after; false })

        assertEquals(RestoreResult.GOAL_HELD, engine.restore())
        runCurrent()

        assertEquals(1L, afters.first())
        assertEquals(1L, inputs)
    }
```

with:

```kotlin
        text.answers += ChannelAnswer.Yes
        // The owner's tap takes number 1, and an input that he gives right after it, before the engine's loop takes the
        // answer, such as an instant command's start, takes number 2.
        text.taps = { (++inputs).also { ++inputs } }
        val afters = mutableListOf<Long?>()
        val engine = engine(environment(fail), ArchivedConversationStore(archive, backgroundScope) {}, screen = FakeScreenTurn { after -> afters += after; false })

        assertEquals(RestoreResult.GOAL_HELD, engine.restore())
        runCurrent()

        // The round names the tap's own number, older than the input after it, which it so never lets go; the engine took no
        // number of its own (it would have been 3, newer than that input).
        assertEquals(1L, afters.first())
        assertEquals(2L, inputs)
    }
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt`, replace:

```kotlin
            settings, testMode, voiceOn, clock, report, { ++inputs }, onStep = onStep,
```

with:

```kotlin
            settings, testMode, voiceOn, clock, report, onStep = onStep,
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt`, add before its last closing brace:

```kotlin

    // Plan 7F: a Continue tapped on a hand-over's card is the owner's act of the moment of his tap, as a spoken "Kestrel,
    // continue" is: the round that goes on names the tap's number at its next screen turn. So it may cancel an instant
    // command that the owner started before his tap, and waits for one that he started after it (plan 7D's ScreenTurns:
    // `after > since`).
    @Test
    fun aContinueTappedOnAHandOversCardNamesTheTapsOwnNumber() = scope.runTest {
        val environment = FakeGoalEnvironment(listOf(listSms, listSms, finish(1))).apply {
            data.answer("list_sms", DataResult.NeedsAccess(AccessKind.NOTIFICATIONS), sms)
        }
        text.handOverEnds += HandOverEnd.CONTINUED
        // A command that the owner started before his tap holds the screen since the newest number then.
        var before = 0L
        var tap = 0L
        text.taps = {
            before = inputs
            (++inputs).also { tap = it }
        }
        val afters = mutableListOf<Long?>()
        val engine = engine(environment, screen = FakeScreenTurn { after -> afters += after; false })
        engine.restore()
        val command = typed("Read my latest SMS")
        engine.submit(command)
        runCurrent()

        assertEquals(1, text.handOvers.size)
        assertEquals(listOf(command.id, tap), afters.filterNotNull())
        val after = afters.filterNotNull().last()
        // Newer than the command that started before the tap: the round may cancel it.
        assertTrue(after > before)
        // Not newer than one that starts after the tap, which holds the screen since the tap's number or a later one: the
        // round waits for it.
        assertFalse(after > tap)
        assertEquals(EndReason.FINISHED, engine.view.value.records.single().end)
    }
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt`, replace:

```kotlin
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.port.PermissionEnd
```

with:

```kotlin
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.PermissionEnd
```

Delete `app/src/androidTest/kotlin/dev/kestrel/app/screen/ConfirmationCardTest.kt`: `git rm -q app/src/androidTest/kotlin/dev/kestrel/app/screen/ConfirmationCardTest.kt`. Its four tests are `QuestionCardTest`'s first four.

- [ ] **Step 2: Run the JVM tests to see them fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.conversation.OwnerCardTest --tests=dev.kestrel.app.conversation.CardDeskTest --tests=dev.kestrel.app.conversation.VoiceChannelTest`
Expected: FAIL: the compiler does not know `OwnerCard`, `CardChoice`, `CardDesk`, `CardTap`, `CardAnswer`, `OwnerCards` and `OwnerScreen.locked`.

Run: `scripts\gradle.ps1 :core:conversation:test --tests=dev.kestrel.conversation.engine.ConversationEngineTest`
Expected: FAIL: the compiler does not know `FakeChannel.taps`, and the engine still takes `inputIds`. (Once it compiles, without Step 4's change of the hand-over, `aContinueTappedOnAHandOversCardNamesTheTapsOwnNumber` fails: the round's turn after the hand-over names no act.)

- [ ] **Step 3: The card in `:core:screen`**

Create `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/CardQuestion.kt`:

```kotlin
package dev.kestrel.screen.overlay

/**
 * A question on Kestrel's card (design 10.3 of sub-project 7): [key] names it, [text] is what the card says, [buttons] are
 * its buttons' labels, in order, and [pad], when it is set, puts a number keypad above them. toString prints no words.
 */
data class CardQuestion(val key: Int, val text: String, val buttons: List<String>, val pad: NumberPad? = null) {
    init {
        require(buttons.isNotEmpty()) { "A card has at least one button" }
    }

    override fun toString(): String = "CardQuestion(key=$key, text=${text.length} chars, buttons=${buttons.size}, pad=${pad != null})"
}

/** The question card's window title in Android's window list (plan 7F). */
const val CARD_TITLE = "Kestrel question"

/**
 * How long a new question card ignores taps, and how long after a card closed the next one may appear (design D32 item 21
 * of sub-project 5); the conversation screen's copy of a card waits as long (plan 7F).
 */
const val CARD_ARMING_MILLIS = 600L

/**
 * A card's keypad: the labels of the key that deletes the last digit, of the key that gives the number, and of the key that
 * moves the card to the other end of the screen, off the numbers that it covers; and the [highest] number that it gives,
 * from 1 (0: any number).
 */
data class NumberPad(val delete: String, val enter: String, val move: String, val highest: Int = 0) {
    /** Whether the keypad gives [value]. */
    fun takes(value: Int): Boolean = value >= 1 && (highest == 0 || value <= highest)
}

/** How a card's question ended for its caller. */
sealed interface CardEnd {
    /** The owner tapped the question's button number [button], from 0. */
    data class Tapped(val button: Int) : CardEnd

    /** The owner entered [value] on the card's keypad. */
    data class Number(val value: Int) : CardEnd

    /** The card gave no answer: a different question replaced it, the session closed or dismissed it, or no card could show. */
    data object Withdrawn : CardEnd
}
```

Create `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/QuestionCard.kt`:

```kotlin
package dev.kestrel.screen.overlay

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager.LayoutParams
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.kestrel.screen.model.IntRect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Kestrel's card at the bottom of the screen (design 10.3 of sub-project 7; spec 12.4): a question, its buttons and, for a
 * pick over many numbers, a compact keypad, which gives only a number of the pick's range and whose Move key takes the card
 * to the other end of the screen, so that it never hides a number that the owner needs. Unlike the other overlays it takes touches, but only on itself, and it drops a touch
 * that passed through another app's window above it (design 12.2). A card ignores taps for its first [CARD_ARMING_MILLIS],
 * and again after its Move key took it to its new place,
 * and a new card appears no sooner than [CARD_ARMING_MILLIS] after the previous one closed, so that a tap aimed at an old card's
 * button never answers a new one (design D32 item 21 of sub-project 5); that wait runs in [scope], so that it depends on no
 * single caller. A new question replaces the card, whose caller gets [CardEnd.Withdrawn]: `:app`'s card desk gives every
 * question one card, whichever channel asks it. Use it on the main thread.
 */
internal class QuestionCard(
    private val host: OverlayHost,
    private val scope: CoroutineScope,
    private val clock: () -> Long = SystemClock::uptimeMillis,
) {
    private val presence = CardPresence(clock)

    // The card of the newest question, from its ask until its end, also while it waits out the arming delay.
    private var current: Card? = null

    /**
     * Whether a card is on the screen, or ended less than one settle ago ([CardPresence]); `ScreenActions` acts on nothing
     * while it is (design D32 item 20 of sub-project 5).
     */
    val isShowing: Boolean get() = presence.isShowing

    /** The card's bounds on the screen once Android has laid it out, also while it closes, or null while no card counts as shown. */
    val bounds: IntRect? get() = presence.bounds

    /**
     * Shows [question] and returns the owner's tap or number, or [CardEnd.Withdrawn] when another question replaces the
     * card, [dismiss] ends it, or no card can show because the session has closed. Cancelling the call removes the card.
     */
    suspend fun ask(question: CardQuestion): CardEnd {
        current?.end(CardEnd.Withdrawn)
        val card = Card(question)
        current = card
        appear(card)
        try {
            return card.answer.await()
        } finally {
            card.end(CardEnd.Withdrawn)
        }
    }

    /** Ends the card, also one that still waits out the arming delay, without the owner's answer. */
    fun dismiss() {
        current?.end(CardEnd.Withdrawn)
    }

    // The card shows once the arming delay after the previous card has passed, unless it ended meanwhile. A wait that the
    // session's end cancels gives the card's caller no answer.
    private fun appear(card: Card) {
        scope.launch {
            presence.closedAt?.let { closed ->
                val wait = closed + CARD_ARMING_MILLIS - clock()
                if (wait > 0) delay(wait)
            }
            if (card.open) card.attach()
        }.invokeOnCompletion { if (!card.attached) card.end(CardEnd.Withdrawn) }
    }

    // A key of the card's keypad.
    private sealed interface PadKey {
        data class Digit(val digit: Char) : PadKey

        data object Delete : PadKey

        data object Enter : PadKey

        data object Move : PadKey
    }

    private inner class Card(val question: CardQuestion) {
        val answer = CompletableDeferred<CardEnd>()
        var attached = false
            private set
        private var shownAt = 0L
        private val digits = StringBuilder()
        private var entered: TextView? = null
        private var enter: Button? = null
        private var atTop = false

        val open: Boolean get() = !answer.isCompleted

        private val view: LinearLayout by lazy {
            build().apply {
                addOnLayoutChangeListener { laid, _, _, _, _, _, _, _, _ ->
                    if (current === this@Card && attached) {
                        val at = IntArray(2)
                        laid.getLocationOnScreen(at)
                        presence.laidOut(IntRect(at[0], at[1], at[0] + laid.width, at[1] + laid.height))
                    }
                }
            }
        }

        fun attach() {
            if (!host.show(view, params(atTop))) {
                end(CardEnd.Withdrawn)
                return
            }
            attached = true
            shownAt = clock()
            presence.shown()
        }

        /** Removes the card and gives its caller [end], unless it has an answer already. */
        fun end(end: CardEnd) {
            if (!answer.complete(end)) return
            if (current === this) current = null
            if (attached) {
                host.hide(view)
                presence.closed()
            }
        }

        // A tap in the card's first CARD_ARMING_MILLIS may have been aimed at the card before it.
        private fun armed(): Boolean = attached && clock() - shownAt >= CARD_ARMING_MILLIS

        private fun tapped(button: Int) {
            if (armed()) end(CardEnd.Tapped(button))
        }

        private fun pressed(key: PadKey) {
            if (!armed()) return
            when (key) {
                is PadKey.Digit -> if (digits.length < MAX_DIGITS) digits.append(key.digit)
                PadKey.Delete -> if (digits.isNotEmpty()) digits.deleteCharAt(digits.lastIndex)
                PadKey.Enter -> {
                    entered()?.let { end(CardEnd.Number(it)) }
                    return
                }
                PadKey.Move -> {
                    move()
                    return
                }
            }
            entered?.text = digits.toString()
            enter?.isEnabled = entered() != null
        }

        // The number typed so far, when the keypad gives it: a number outside the pick's range gives nothing.
        private fun entered(): Int? = digits.toString().toIntOrNull()?.takeIf { question.pad?.takes(it) == true }

        // The card goes to the other end of the screen and keeps its number. It arms again there, because a tap may have
        // been aimed at what was under the card's new place (review L2).
        private fun move() {
            host.hide(view)
            atTop = !atTop
            if (!host.show(view, params(atTop))) {
                end(CardEnd.Withdrawn)
                return
            }
            shownAt = clock()
        }

        private fun build(): LinearLayout {
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
                // Design 12.2 of sub-project 7: a touch that passed through another app's window above the card answers nothing.
                filterTouchesWhenObscured = true
                addView(TextView(context).apply {
                    text = question.text
                    setTextColor(Color.BLACK)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                })
                question.pad?.let { pad -> addView(keypad(pad)) }
                addView(LinearLayout(context).apply {
                    // More than three buttons stack, so that a pick's four choices and Cancel keep their whole labels.
                    orientation = if (question.buttons.size > ROW_BUTTONS) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    question.buttons.forEachIndexed { index, label -> addView(button(label) { tapped(index) }) }
                })
            }
        }

        // Low, so that it covers little of the numbers: the number as the owner types it with the deletion, the entry and the
        // move, and then the digits, five to a row.
        private fun keypad(pad: NumberPad): LinearLayout {
            val context = host.context
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(context).apply {
                        setTextColor(Color.BLACK)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                        gravity = Gravity.CENTER
                        entered = this
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    for (key in listOf(PadKey.Delete, PadKey.Enter, PadKey.Move)) addView(padButton(pad, key))
                })
                for (row in listOf("12345", "67890")) {
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        for (digit in row) addView(padButton(pad, PadKey.Digit(digit)), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    })
                }
            }
        }

        private fun padButton(pad: NumberPad, key: PadKey): Button {
            val label = when (key) {
                is PadKey.Digit -> key.digit.toString()
                PadKey.Delete -> pad.delete
                PadKey.Enter -> pad.enter
                PadKey.Move -> pad.move
            }
            return button(label) { pressed(key) }.also { button ->
                if (key == PadKey.Enter) {
                    button.isEnabled = false
                    enter = button
                }
            }
        }

        private fun button(label: String, onTap: () -> Unit): Button = Button(host.context).apply {
            text = label
            filterTouchesWhenObscured = true
            setOnClickListener { onTap() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
    }

    // At the bottom, or at the top once the keypad's Move key took the card there.
    private fun params(top: Boolean) = LayoutParams(
        host.context.resources.displayMetrics.widthPixels - 2 * dp(16f).toInt(),
        LayoutParams.WRAP_CONTENT,
        LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Touches outside the card go to the app below; Kestrel's touch watcher reports them (section 14.1).
        LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = (if (top) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
        fitInsetsTypes = WindowInsets.Type.systemBars()
        y = dp(16f).toInt()
        setTitle(CARD_TITLE)
    }

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, host.context.resources.displayMetrics)

    private companion object {
        /** The keypad's longest number: the numbered grid has at most a few hundred numbers. */
        const val MAX_DIGITS = 3

        const val ROW_BUTTONS = 3
    }
}
```

Delete the old card and its strings, which nothing else in `:core:screen` uses: `git rm -q core/screen/src/main/kotlin/dev/kestrel/screen/overlay/ConfirmationCard.kt core/screen/src/main/res/values/strings.xml`.

In `core/screen/src/main/kotlin/dev/kestrel/screen/overlay/CardPresence.kt`, replace:

```kotlin
 * Whether the confirmation card counts as shown, for any thread (design D8 and D32 item 20 of sub-project 5). After the
```

with:

```kotlin
 * Whether the question card counts as shown, for any thread (design D8 and D32 item 20 of sub-project 5). After the
```

In `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt`, replace:

```kotlin
import dev.kestrel.screen.overlay.ConfirmationCard
```

with:

```kotlin
import dev.kestrel.screen.overlay.CardEnd
import dev.kestrel.screen.overlay.CardQuestion
```

In `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt`, replace:

```kotlin
import dev.kestrel.screen.overlay.OverlayHost
import dev.kestrel.screen.overlay.TouchWatcher
```

with:

```kotlin
import dev.kestrel.screen.overlay.OverlayHost
import dev.kestrel.screen.overlay.QuestionCard
import dev.kestrel.screen.overlay.TouchWatcher
```

In `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt`, replace:

```kotlin
    private val card = ConfirmationCard(overlays)
```

with:

```kotlin
    private val card = QuestionCard(overlays, scope)
```

In `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt`, replace:

```kotlin
    /**
     * Asks [question] on a confirmation card and returns true when the owner taps Confirm. It returns false when the
     * owner taps Cancel, when a newer question replaces this one or when the session closes, and the card then goes at
     * once. A caller that also listens for "confirm" or "cancel", or that gives up after 15 s, cancels this call, which
     * removes the card too (design spec, section 12.4).
     */
    suspend fun confirm(question: String): Boolean = withContext(Dispatchers.Main) {
        // An action that Kestrel made before the question can never hide the owner's touch (design D19 of sub-project 5).
        ownActions.clear()
        card.ask(question)
    }
```

with:

```kotlin
    /**
     * Shows [question] on Kestrel's card and returns the owner's tap or number (design 10.3 of sub-project 7; spec 12.4),
     * or [CardEnd.Withdrawn] when another question replaces the card, the session closes or no card can show; the card
     * then goes at once. A caller that also listens for the owner's voice, or that gives up after its wait, cancels this
     * call, which removes the card.
     */
    suspend fun ask(question: CardQuestion): CardEnd = withContext(Dispatchers.Main) {
        // An action that Kestrel made before the question can never hide the owner's touch (design D19 of sub-project 5).
        ownActions.clear()
        card.ask(question)
    }
```

In `core/screen/src/main/kotlin/dev/kestrel/screen/ScreenSession.kt`, replace:

```kotlin
     * Whether the confirmation card is on the screen (design D8 and D32 item 20 of sub-project 5): `AppScreenPort` waits
```

with:

```kotlin
     * Whether the question card is on the screen (design D8 and D32 item 20 of sub-project 5): `AppScreenPort` waits
```

- [ ] **Step 4: The tap's number reaches the engine, from a question's card and from a hand-over's**

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/OwnerChannel.kt`, replace:

```kotlin
    /**
     * Asks [question] and returns the answer that the channel itself got, as a card's button, which acts over the app and
     * so leaves no screen of Kestrel's; an answer that the owner says or types, also after "Reply…", reaches the
     * conversation as an input instead. Cancelling the call withdraws the question.
     */
    suspend fun ask(question: ChannelQuestion): ChannelAnswer
```

with:

```kotlin
    /**
     * Asks [question] and returns the answer that the channel itself got, as a card's button, which acts over the app and
     * so leaves no screen of Kestrel's, with the number of the owner's tap; an answer that the owner says or types, also
     * after "Reply…", reaches the conversation as an input instead. Cancelling the call withdraws the question.
     */
    suspend fun ask(question: ChannelQuestion): CardAnswer
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/OwnerChannel.kt`, replace:

```kotlin
/** What a channel says or shows (design 11 of sub-project 7). toString prints no words. */
sealed interface ChannelOutput {
```

with:

```kotlin
/**
 * A card's answer as the owner tapped it over the app (design 10.3 of sub-project 7): [answer], and [act], the number among
 * his inputs that `:app` took when his tap came back from the card (its `InputIds`), so that a round that the answer lets
 * act names the moment of his tap, not the moment when the engine handled the answer (design 6.5; plan 7F). Only an answer
 * that lets a round act, as a yes to "Continue the goal?", uses the number; for an answer to a goal's own question it is
 * dropped, as a spoken or typed answer's is (plan 7D's `RunningGoal.actedFor`).
 */
data class CardAnswer(val answer: ChannelAnswer, val act: Long)

/**
 * The owner's answer to a question of the conversation's own, and [tap], the number of his tap on its card when a card gave
 * it; null when the answer came as an input, which has its own number (plan 7F).
 */
data class OwnerReply(val answer: ChannelAnswer, val tap: Long?)

/** What a channel says or shows (design 11 of sub-project 7). toString prints no words. */
sealed interface ChannelOutput {
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/ConversationOwner.kt`, replace:

```kotlin
    /** An answer as it arrived: [leftScreen] when the owner gave it on Kestrel's own screen. */
    private class Given(val answer: ChannelAnswer, val leftScreen: Boolean)
```

with:

```kotlin
    /**
     * An answer as it arrived: [leftScreen] when the owner gave it on Kestrel's own screen, and [tap], the number of his tap
     * when a card gave it (plan 7F).
     */
    private class Given(val answer: ChannelAnswer, val leftScreen: Boolean, val tap: Long? = null)
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/ConversationOwner.kt`, replace:

```kotlin
    override suspend fun question(text: String, kind: QuestionKind, spoken: String, choices: List<String>, highest: Int): ChannelAnswer =
        dialogue(Draft(text, kind, spoken, choices = choices, highest = highest), goal = false)

    override suspend fun ask(question: String, kind: AnswerKind, choices: List<String>, highest: Int): Answer =
        answerOf(dialogue(Draft(question, kindOf(kind), choices = choices, highest = highest), goal = true))

    override suspend fun confirm(confirmation: Confirmation): ConfirmEnd =
        when (dialogue(Draft(confirmation.question, QuestionKind.CONFIRM, confirmation.spoken, confirmation), goal = true)) {
```

with:

```kotlin
    override suspend fun question(text: String, kind: QuestionKind, spoken: String, choices: List<String>, highest: Int): ChannelAnswer =
        dialogue(Draft(text, kind, spoken, choices = choices, highest = highest), goal = false).answer

    /**
     * Asks [text] of [kind] as [question] does, and returns the answer with the number of the owner's tap on its card when
     * a card gave it (plan 7F). "Continue the goal?" asks through it, so that the round that a yes on its card resumes acts
     * for the moment of the tap (design 6.5 of sub-project 7).
     */
    suspend fun reply(text: String, kind: QuestionKind): OwnerReply = dialogue(Draft(text, kind), goal = false).let { OwnerReply(it.answer, it.tap) }

    override suspend fun ask(question: String, kind: AnswerKind, choices: List<String>, highest: Int): Answer =
        answerOf(dialogue(Draft(question, kindOf(kind), choices = choices, highest = highest), goal = true).answer)

    override suspend fun confirm(confirmation: Confirmation): ConfirmEnd =
        when (dialogue(Draft(confirmation.question, QuestionKind.CONFIRM, confirmation.spoken, confirmation), goal = true).answer) {
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/ConversationOwner.kt`, replace:

```kotlin
    private suspend fun dialogue(draft: Draft, goal: Boolean): ChannelAnswer = turns.withLock {
        val waiting = Waiting<Given>(numbers.incrementAndGet(), goal)
        synchronized(lock) {
            if (goal && pause != null) return@withLock ChannelAnswer.Interrupted
```

with:

```kotlin
    private suspend fun dialogue(draft: Draft, goal: Boolean): Given = turns.withLock {
        val waiting = Waiting<Given>(numbers.incrementAndGet(), goal)
        synchronized(lock) {
            if (goal && pause != null) return@withLock Given(ChannelAnswer.Interrupted, leftScreen = false)
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/ConversationOwner.kt`, replace:

```kotlin
            val given = race(asked, waiting.done)
            if (given.leftScreen) synchronized(lock) { fresh = true }
            given.answer
```

with:

```kotlin
            val given = race(asked, waiting.done)
            if (given.leftScreen) synchronized(lock) { fresh = true }
            given
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/ConversationOwner.kt`, replace:

```kotlin
        val askers = mutableListOf<Deferred<ChannelAnswer>>()
```

with:

```kotlin
        val askers = mutableListOf<Deferred<CardAnswer>>()
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/ConversationOwner.kt`, replace:

```kotlin
            for (asker in askers) asker.onAwait { Given(it, leftScreen = false) }
```

with:

```kotlin
            for (asker in askers) asker.onAwait { Given(it.answer, leftScreen = false, tap = it.act) }
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/ConversationEvent.kt`, replace:

```kotlin
    /** The owner's answer to "Continue the goal?". */
    data class ContinueAnswered(val answer: ChannelAnswer) : ConversationEvent
```

with:

```kotlin
    /**
     * The owner's answer to "Continue the goal?"; [tap] is the number of his tap on its card when the card gave it, which
     * `:app` took as the tap reached Kestrel (plan 7F).
     */
    data class ContinueAnswered(val answer: ChannelAnswer, val tap: Long? = null) : ConversationEvent
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/ConversationMachine.kt`, replace:

```kotlin
            is ConversationEvent.ContinueAnswered -> GoalRules.continueAnswered(state, event.answer, now)
```

with:

```kotlin
            is ConversationEvent.ContinueAnswered -> GoalRules.continueAnswered(state, event.answer, event.tap, now)
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/ConversationEffect.kt`, replace:

```kotlin
    /**
     * Runs a held goal's next round as [command], after [cause], with [steer] as its first steering. [input] is the owner's
     * input that resumed it, whose options the round takes; null when he answered "Continue the goal?" on its card.
     */
    data class ResumeGoal(val command: Int, val cause: ResumeCause, val steer: Steering?, val input: OwnerInput?) : ConversationEffect
```

with:

```kotlin
    /**
     * Runs a held goal's next round as [command], after [cause], with [steer] as its first steering. [input] is the owner's
     * input that resumed it, whose options the round takes; null when he answered "Continue the goal?" on its card, and
     * [tap] is then the number of his tap (plan 7F).
     */
    data class ResumeGoal(val command: Int, val cause: ResumeCause, val steer: Steering?, val input: OwnerInput?, val tap: Long? = null) : ConversationEffect
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/ConversationEffect.kt`, replace:

```kotlin
/**
 * The owner's act that lets a goal's round act now (design 6.5 of sub-project 7): his [input], or a tap on a card, which
 * comes without one (null). The round may then have an instant command that he started before it cancelled; a round that
 * goes on without such an act waits for the command instead.
 */
data class OwnerAct(val input: OwnerInput?)
```

with:

```kotlin
/**
 * The owner's act that lets a goal's round act now (design 6.5 of sub-project 7): his [input], or his [tap] on a card, the
 * number among his inputs that `:app` took when the tap reached Kestrel (plan 7F). The round may then have an instant
 * command that he started before it cancelled; a round that goes on without such an act waits for the command instead.
 */
data class OwnerAct(val input: OwnerInput?, val tap: Long? = null) {
    /** The act's number among the owner's inputs; null when no act of his came with it. */
    val number: Long? get() = input?.id ?: tap
}
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/GoalRules.kt`, replace:

```kotlin
    /**
     * A goal held after its round ended resumes in a new round, with [steer] as its first steering (design 7.6), for the
     * owner's [input], or for his yes on the card of "Continue the goal?" (null).
     */
    fun resume(state: ConversationState, goal: GoalState, input: OwnerInput?, steer: String?, now: Long): MachineStep {
```

with:

```kotlin
    /**
     * A goal held after its round ended resumes in a new round, with [steer] as its first steering (design 7.6), for the
     * owner's [input], or for his yes on the card of "Continue the goal?" (null), whose [tap] has its own number (plan 7F).
     */
    fun resume(state: ConversationState, goal: GoalState, input: OwnerInput?, steer: String?, now: Long, tap: Long? = null): MachineStep {
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/GoalRules.kt`, replace:

```kotlin
            ConversationEffect.ResumeGoal(command, cause, steer?.let { ConversationEffect.Steering(it, channel) }, input),
```

with:

```kotlin
            ConversationEffect.ResumeGoal(command, cause, steer?.let { ConversationEffect.Steering(it, channel) }, input, tap),
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/GoalRules.kt`, replace:

```kotlin
    // The yes came from the owner's input (a spoken or typed yes, "Kestrel, continue") or from the card, which gives none.
    // An answer that comes after a lock, a call or "stop" withdrew the question changes nothing: the goal stays as that
    // left it, and the owner's return asks again.
    fun continueAnswered(state: ConversationState, answer: ChannelAnswer, now: Long): MachineStep {
```

with:

```kotlin
    // The yes came from the owner's input (a spoken or typed yes, "Kestrel, continue") or from his tap on the card, whose
    // number [tap] `:app` took as the tap reached Kestrel (plan 7F). An answer that comes after a lock, a call or "stop"
    // withdrew the question changes nothing: the goal stays as that left it, and the owner's return asks again.
    fun continueAnswered(state: ConversationState, answer: ChannelAnswer, tap: Long?, now: Long): MachineStep {
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/GoalRules.kt`, replace:

```kotlin
                    resumePaused(asked, goal, OwnerAct(input), input?.channel ?: state.replyChannel, now)
                } else {
                    resume(asked, goal, input, null, now)
```

with:

```kotlin
                    resumePaused(asked, goal, OwnerAct(input, tap), input?.channel ?: state.replyChannel, now)
                } else {
                    resume(asked, goal, input, null, now, tap)
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
 * the hooks read. [restore] must run once at process start; inputs wait for it. [report] gets a failure by its class;
 * [inputIds] numbers an owner's act that comes without an input, as a tap on a card, among the owner's inputs (`:app`'s
 * `InputIds`), so that the screen's turn can tell whether it came after an instant command started (design 6.5);
 * [onStep] sees every step, for the hooks' tags (plan 7D): every effect that starts work under a new command number
```

with:

```kotlin
 * the hooks read. [restore] must run once at process start; inputs wait for it. [report] gets a failure by its class.
 * Every act of the owner's that lets a goal's round act comes with its number among his inputs, an input with its own and a
 * tap on a card with the one that `:app` took as the tap reached Kestrel (plan 7F), so that the screen's turn can tell
 * whether it came after an instant command started (design 6.5); the engine numbers nothing itself.
 * [onStep] sees every step, for the hooks' tags (plan 7D): every effect that starts work under a new command number
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
    private val report: (String, Throwable) -> Unit,
    private val inputIds: () -> Long,
    private val machine: ConversationMachine = ConversationMachine { ConversationId.random() },
```

with:

```kotlin
    private val report: (String, Throwable) -> Unit,
    private val machine: ConversationMachine = ConversationMachine { ConversationId.random() },
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
    // Design 6.5: the owner's act lets the running round act now, so its next screen turn may have an instant command that
    // he started before it cancelled.
    private fun actedFor(act: OwnerAct) {
        running?.actedFor(inputOf(act))
    }

    // A tap on a card comes without an input: it gets the next input number, as the owner's act at this moment.
    private fun inputOf(act: OwnerAct): Long = act.input?.id ?: inputIds()
```

with:

```kotlin
    // Design 6.5: the owner's act lets the running round act now, so its next screen turn may have an instant command that
    // he started before it cancelled. The act's number is its input's, or his tap's (plan 7F).
    private fun actedFor(act: OwnerAct) {
        act.number?.let { running?.actedFor(it) }
    }
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
        goal.actedFor(inputOf(OwnerAct(effect.input)))
```

with:

```kotlin
        (effect.input?.id ?: effect.tap)?.let(goal::actedFor)
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
            val answer = try {
                owner.question(ConversationTexts.CONTINUE_GOAL, QuestionKind.CONTINUE_GOAL)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Asking to continue the goal", e)
                ChannelAnswer.Silence
            }
            post(ConversationEvent.ContinueAnswered(answer))
```

with:

```kotlin
            val reply = try {
                owner.reply(ConversationTexts.CONTINUE_GOAL, QuestionKind.CONTINUE_GOAL)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Asking to continue the goal", e)
                OwnerReply(ChannelAnswer.Silence, tap = null)
            }
            post(ConversationEvent.ContinueAnswered(reply.answer, reply.tap))
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
import dev.kestrel.conversation.channel.OwnerChannel
```

with:

```kotlin
import dev.kestrel.conversation.channel.OwnerChannel
import dev.kestrel.conversation.channel.OwnerReply
```

In `core/conversation/src/testFixtures/kotlin/dev/kestrel/conversation/testing/FakeChannel.kt`, replace:

```kotlin
    var leaves = true
    var onShow: suspend (ChannelOutput) -> Unit = {}
```

with:

```kotlin
    var leaves = true
    var onShow: suspend (ChannelOutput) -> Unit = {}

    /** The number of each tap on the channel's card among the owner's inputs, which `:app` takes as the tap reaches Kestrel. */
    var taps: () -> Long = { 0L }
```

In `core/conversation/src/testFixtures/kotlin/dev/kestrel/conversation/testing/FakeChannel.kt`, replace:

```kotlin
    override suspend fun ask(question: ChannelQuestion): ChannelAnswer {
        asked += question
        return answers.removeFirstOrNull() ?: awaitCancellation()
    }
```

with:

```kotlin
    override suspend fun ask(question: ChannelQuestion): CardAnswer {
        asked += question
        val answer = answers.removeFirstOrNull() ?: awaitCancellation()
        return CardAnswer(answer, taps())
    }
```

In `core/conversation/src/testFixtures/kotlin/dev/kestrel/conversation/testing/FakeChannel.kt`, replace:

```kotlin
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.conversation.channel.ChannelOutput
```

with:

```kotlin
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.conversation.channel.CardAnswer
import dev.kestrel.conversation.channel.ChannelOutput
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
            report = reportFailure,
            inputIds = InputIds::next,
            onStep = { event, state, step -> conversationSteps?.invoke(event, state, step) },
```

with:

```kotlin
            report = reportFailure,
            onStep = { event, state, step -> conversationSteps?.invoke(event, state, step) },
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/OwnerChannel.kt`, replace:

```kotlin
    /** A hand-over: returns when the owner continued or cancelled on the channel, or when it ended by itself. */
    suspend fun handOver(handOver: HandOver): HandOverEnd
```

with:

```kotlin
    /**
     * A hand-over: returns when the owner continued or cancelled on the channel, with the number of his tap on its card's
     * button, or when it ended by itself.
     */
    suspend fun handOver(handOver: HandOver): HandOverAnswer
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/OwnerChannel.kt`, replace:

```kotlin
/**
 * The owner's answer to a question of the conversation's own, and [tap], the number of his tap on its card when a card gave
 * it; null when the answer came as an input, which has its own number (plan 7F).
 */
data class OwnerReply(val answer: ChannelAnswer, val tap: Long?)
```

with:

```kotlin
/**
 * The owner's answer to a question of the conversation's own, and [tap], the number of his tap on its card when a card gave
 * it; null when the answer came as an input, which has its own number (plan 7F).
 */
data class OwnerReply(val answer: ChannelAnswer, val tap: Long?)

/**
 * How a hand-over ended on a channel (design 10.3 of sub-project 7): [end], and [act], the number among the owner's inputs
 * of his tap on the card's Continue or Cancel, which `:app` took as the tap reached Kestrel; null when the hand-over ended
 * by itself. A Continue on the card is the owner's act of its moment, as a spoken "Kestrel, continue" is (plan 7F).
 */
data class HandOverAnswer(val end: HandOverEnd, val act: Long?)
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/ConversationOwner.kt`, replace:

```kotlin
 * conversation can ask "Continue the goal?" meanwhile; they end by the channel, or by the engine's [endHandOver]; after
 * "Kestrel, continue" a permission request asks [permissions] whether Android granted them.
 */
class ConversationOwner(
    private val channels: Map<InputChannel, OwnerChannel>,
    private val permissions: (List<String>) -> PermissionEnd,
    private val voiceOn: StateFlow<Boolean>,
    private val routing: () -> Routing,
    private val events: (DialogueEvent) -> Unit,
```

with:

```kotlin
 * conversation can ask "Continue the goal?" meanwhile; they end by the channel, or by the engine's [endHandOver]; after
 * "Kestrel, continue" a permission request asks [permissions] whether Android granted them. A tap on the card of a
 * hand-over or a permission request is the owner's act of its moment: [acted] gets its number before the round goes on,
 * so that the round's next screen turn names it, as it names the input of a spoken "Kestrel, continue" (design 6.5; plan
 * 7F).
 */
class ConversationOwner(
    private val channels: Map<InputChannel, OwnerChannel>,
    private val permissions: (List<String>) -> PermissionEnd,
    private val voiceOn: StateFlow<Boolean>,
    private val routing: () -> Routing,
    private val acted: (Long) -> Unit = {},
    private val events: (DialogueEvent) -> Unit,
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/ConversationOwner.kt`, replace:

```kotlin
    override suspend fun handOver(handOver: HandOver): HandOverEnd =
        handedOver(handOver.kind, { it.handOver(handOver) }, { it.handOver(handOver) }) { it }

    // Android's dialog is shown once, by the owner's channel. The card that a spoken conversation gets once Kestrel is off
    // is a hand-over's card, whose Continue checks what Android granted, so the dialog never shows a second time.
    override suspend fun askPermission(permissions: List<String>, why: String): PermissionEnd {
        val ended = { end: HandOverEnd -> if (end == HandOverEnd.CONTINUED) this.permissions(permissions) else PermissionEnd.REFUSED }
        return handedOver(HandOverKind.PERMISSION, { it.askPermission(permissions, why) }, { ended(it.handOver(HandOver.of(HandOverKind.PERMISSION))) }, ended)
    }
```

with:

```kotlin
    override suspend fun handOver(handOver: HandOver): HandOverEnd {
        val answered = { answer: HandOverAnswer -> Ended(answer.end, answer.act) }
        return handedOver(handOver.kind, { answered(it.handOver(handOver)) }, { answered(it.handOver(handOver)) }) { it }
    }

    // Android's dialog is shown once, by the owner's channel. The card that a spoken conversation gets once Kestrel is off
    // is a hand-over's card, whose Continue checks what Android granted, so the dialog never shows a second time.
    override suspend fun askPermission(permissions: List<String>, why: String): PermissionEnd {
        val ended = { end: HandOverEnd -> if (end == HandOverEnd.CONTINUED) this.permissions(permissions) else PermissionEnd.REFUSED }
        return handedOver(
            HandOverKind.PERMISSION,
            { Ended(it.askPermission(permissions, why), act = null) },
            { channel -> channel.handOver(HandOver.of(HandOverKind.PERMISSION)).let { Ended(ended(it.end), it.act) } },
            ended,
        )
    }
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/ConversationOwner.kt`, replace:

```kotlin
    private suspend fun <T> handedOver(
        kind: HandOverKind,
        itself: suspend (OwnerChannel) -> T,
        card: suspend (OwnerChannel) -> T,
        ended: (HandOverEnd) -> T,
    ): T = inTurnAfterPause {
```

with:

```kotlin
    /** How a hand-over or a permission request ended: its [value], and the number of the owner's tap that ended it ([act]). */
    private class Ended<T>(val value: T, val act: Long?)

    // The tap's number reaches the round before the hand-over returns to it, so that the round's next screen turn names it
    // (plan 7F).
    private suspend fun <T> handedOver(
        kind: HandOverKind,
        itself: suspend (OwnerChannel) -> Ended<T>,
        card: suspend (OwnerChannel) -> Ended<T>,
        ended: (HandOverEnd) -> T,
    ): T {
        val result = handedOverInTurn(kind, itself, card, ended)
        result.act?.let(acted)
        return result.value
    }

    private suspend fun <T> handedOverInTurn(
        kind: HandOverKind,
        itself: suspend (OwnerChannel) -> Ended<T>,
        card: suspend (OwnerChannel) -> Ended<T>,
        ended: (HandOverEnd) -> T,
    ): Ended<T> = inTurnAfterPause {
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/ConversationOwner.kt`, replace:

```kotlin
                select<T> {
                    waiting.done.onAwait { ended(it) }
```

with:

```kotlin
                // The engine's end comes for an input, which named the round's act itself (`EndHandOver`), or by itself.
                select<Ended<T>> {
                    waiting.done.onAwait { Ended(ended(it), act = null) }
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
    private var recordsOf: ConversationId? = null
    private var running: RunningGoal? = null
```

with:

```kotlin
    private var recordsOf: ConversationId? = null

    // The owner's tap on a hand-over's card also reaches it, from the round's own coroutine, while the round runs (plan 7F).
    @Volatile
    private var running: RunningGoal? = null
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
    val owner = ConversationOwner(channels, permissions, voiceNow.asStateFlow(), { ConversationOwner.Routing(state.replyChannel, state.settings.speakTypedReplies) }) {
```

with:

```kotlin
    val owner = ConversationOwner(
        channels,
        permissions,
        voiceNow.asStateFlow(),
        { ConversationOwner.Routing(state.replyChannel, state.settings.speakTypedReplies) },
        // Design 6.5: a tap on a hand-over's card lets the running round act now, as "Kestrel, continue" does (plan 7F).
        acted = { act -> running?.actedFor(act) },
    ) {
```

In `core/conversation/src/testFixtures/kotlin/dev/kestrel/conversation/testing/FakeChannel.kt`, replace:

```kotlin
    override suspend fun handOver(handOver: HandOver): HandOverEnd {
        handOvers += handOver
        return handOverEnds.removeFirstOrNull() ?: awaitCancellation()
    }
```

with:

```kotlin
    // A queued end other than DONE_BY_ITSELF is the owner's tap on the card, which takes its number.
    override suspend fun handOver(handOver: HandOver): HandOverAnswer {
        handOvers += handOver
        val end = handOverEnds.removeFirstOrNull() ?: awaitCancellation()
        return HandOverAnswer(end, if (end == HandOverEnd.DONE_BY_ITSELF) null else taps())
    }
```

In `core/conversation/src/testFixtures/kotlin/dev/kestrel/conversation/testing/FakeChannel.kt`, replace:

```kotlin
import dev.kestrel.conversation.channel.ChannelQuestion
import dev.kestrel.conversation.channel.OwnerChannel
```

with:

```kotlin
import dev.kestrel.conversation.channel.ChannelQuestion
import dev.kestrel.conversation.channel.HandOverAnswer
import dev.kestrel.conversation.channel.OwnerChannel
```

Run: `scripts\gradle.ps1 :core:conversation:test`
Expected: BUILD SUCCESSFUL; `ConversationEngineTest.aYesOnTheCardOfContinueTheGoalNamesTheTapsOwnNumber` and `aContinueTappedOnAHandOversCardNamesTheTapsOwnNumber` pass, and `:core:conversation` has C₀ + 1 tests.

- [ ] **Step 5: The card's buttons and the desk in `:app`, and the owner's parts**

Create `app/src/main/res/values/strings_card.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- The buttons of Kestrel's question card (design 10.3 of sub-project 7; plan 7F). -->
<resources>
    <string name="card_yes">Yes</string>
    <string name="card_no">No</string>
    <string name="card_confirm">Confirm</string>
    <string name="card_cancel">Cancel</string>
    <string name="card_continue">Continue</string>
    <string name="card_stop">Stop</string>
    <string name="card_reply">Reply…</string>
    <string name="card_delete_digit">Delete digit</string>
    <string name="card_enter">OK</string>
    <string name="card_move">Move</string>
</resources>
```

Create `app/src/main/kotlin/dev/kestrel/app/conversation/OwnerCard.kt`:

```kotlin
package dev.kestrel.app.conversation

import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.app.R
import dev.kestrel.conversation.channel.ChannelQuestion
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.ChannelAnswer

/**
 * A button of Kestrel's card (design 10.3 of sub-project 7): the answer that a tap on it gives ("Reply…" gives none, because
 * it opens the field for the owner's words), and the string of its word ([label]; null for a pick, whose button says its
 * choice).
 */
internal sealed interface CardChoice {
    val answer: ChannelAnswer?
    val label: Int?

    data object Yes : CardChoice {
        override val answer: ChannelAnswer = ChannelAnswer.Yes
        override val label: Int = R.string.card_yes
    }

    data object No : CardChoice {
        override val answer: ChannelAnswer = ChannelAnswer.No
        override val label: Int = R.string.card_no
    }

    data object Confirm : CardChoice {
        override val answer: ChannelAnswer = ChannelAnswer.Yes
        override val label: Int = R.string.card_confirm
    }

    data object Cancel : CardChoice {
        override val answer: ChannelAnswer = ChannelAnswer.No
        override val label: Int = R.string.card_cancel
    }

    data object Continue : CardChoice {
        override val answer: ChannelAnswer = ChannelAnswer.Yes
        override val label: Int = R.string.card_continue
    }

    data object Stop : CardChoice {
        override val answer: ChannelAnswer = ChannelAnswer.No
        override val label: Int = R.string.card_stop
    }

    data object Reply : CardChoice {
        override val answer: ChannelAnswer? = null
        override val label: Int = R.string.card_reply
    }

    /** A pick's choice number [number], from 1, or the number that the owner entered on the card's keypad. */
    data class Pick(val number: Int) : CardChoice {
        override val answer: ChannelAnswer get() = ChannelAnswer.Pick(number)
        override val label: Int? get() = null
    }
}

/**
 * A card that Kestrel shows the owner (design 10.3 of sub-project 7): [key] names it, the conversation's question number or,
 * for a hand-over, a negative number of the text channel's; [text] says it; [choices] are its buttons, in order; [picks]
 * are a pick's choices, which its Pick buttons say; [question] is the question's number and [kind] its kind, both null for
 * a hand-over; [numberPad] puts the keypad on a pick that carries no choices, which gives a number from 1 to [highest]
 * (0: any number). toString prints no words.
 */
internal data class OwnerCard(
    val key: Int,
    val text: String,
    val choices: List<CardChoice>,
    val picks: List<String> = emptyList(),
    val question: Int? = null,
    val kind: QuestionKind? = null,
    val numberPad: Boolean = false,
    val highest: Int = 0,
) {
    init {
        require(choices.isNotEmpty()) { "A card has at least one button" }
    }

    /** Whether [choice] answers this card: one of its buttons, or a number of its keypad's range (review N1 of plan 7F). */
    fun takes(choice: CardChoice): Boolean =
        choice in choices || (numberPad && choice is CardChoice.Pick && choice.number >= 1 && (highest == 0 || choice.number <= highest))

    /**
     * Whether the conversation screen's copy of the card answers it (plan 7F). A confirmation acts on the app at once, so
     * only the card over that app answers it (design 12.2 and 12.3); a hand-over is the owner's step in the app; and a pick
     * names a number on the app's screen, which Kestrel's screen would cover.
     */
    val answersOnScreen: Boolean get() = kind != null && kind != QuestionKind.CONFIRM && kind != QuestionKind.PICK

    override fun toString(): String =
        "OwnerCard(key=$key, text=${text.length} chars, choices=$choices, picks=${picks.size}, question=$question, kind=$kind, numberPad=$numberPad, highest=$highest)"

    companion object {
        /**
         * [question]'s card with its kind's buttons (design 10.3): "Reply…" when [reply] and the kind takes the owner's
         * words, never for a confirmation, which is always a button. The text channel's cards have "Reply…"; the voice
         * channel's (a confirmation's and the screenshot question's) do not. A pick shows its choices, at most
         * `OwnerPort.MAX_CHOICES`, or a keypad when it has none, and Cancel; its number is entered on the card, never on
         * Kestrel's screen, which would cover the app's numbers. A button that resumes a goal comes last, as on the goal
         * notification (plan 7E).
         */
        fun of(question: ChannelQuestion, reply: Boolean): OwnerCard {
            val words = listOfNotNull(CardChoice.Reply.takeIf { reply })
            val picks = question.choices.take(OwnerPort.MAX_CHOICES).takeIf { question.kind == QuestionKind.PICK }.orEmpty()
            val choices = when (question.kind) {
                QuestionKind.YES_NO, QuestionKind.SCREENSHOT -> listOf(CardChoice.Yes, CardChoice.No) + words
                QuestionKind.CONFIRM -> listOf(CardChoice.Confirm, CardChoice.Cancel)
                QuestionKind.PICK -> picks.indices.map { CardChoice.Pick(it + 1) } + CardChoice.Cancel
                QuestionKind.FREE -> words + CardChoice.Cancel
                QuestionKind.CONTINUE_GOAL, QuestionKind.LIMIT -> listOf(CardChoice.Stop, CardChoice.Continue)
                QuestionKind.SWITCH, QuestionKind.DELETE_HISTORY -> listOf(CardChoice.Yes, CardChoice.No)
            }
            val numberPad = question.kind == QuestionKind.PICK && picks.isEmpty()
            return OwnerCard(question.number, question.text, choices, picks, question.number, question.kind, numberPad, question.highest.takeIf { numberPad } ?: 0)
        }

        /** A hand-over's card (design 10.3): its sentence, Cancel and Continue. */
        fun handOver(key: Int, text: String): OwnerCard = OwnerCard(key, text, listOf(CardChoice.Cancel, CardChoice.Continue))
    }
}
```

Create `app/src/main/kotlin/dev/kestrel/app/conversation/CardDesk.kt`:

```kotlin
package dev.kestrel.app.conversation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The owner's tap on a card's [choice], with [act], its number among his inputs, which the card desk takes when the tap
 * has come back from the card, before any caller runs (`InputIds`; design 6.5 of sub-project 7, plan 7F): one tap is one
 * act, whichever callers it answers.
 */
internal data class CardTap(val choice: CardChoice, val act: Long)

/** The card of each question, whichever channel asks it (design 10.3 of sub-project 7). */
internal interface OwnerCards {
    /**
     * Shows [card] until the owner taps one of its own choices, and returns that choice with his tap's number. "Reply…" is
     * no answer: it calls [onReply], and the question waits for the owner's typed words. Cancelling the call withdraws this
     * caller's card.
     */
    suspend fun ask(card: OwnerCard, onReply: () -> Unit = {}): CardTap
}

/** The cards as the text channel uses them: each question's card, and the open card, which the screen shows (plan 7F). */
internal interface SharedCards : OwnerCards {
    /** The card that waits for the owner, which the conversation screen shows while it is in front. */
    val open: StateFlow<OwnerCard?>
}

/**
 * The one card of each question (design 10.3 of sub-project 7; plan 7D's interfaces: one card per question number,
 * whichever channel asks it). Every caller that asks question [OwnerCard.key] joins one card, drawn with the choices of the
 * caller that has the most, so that the voice's `[Yes, No]` and the text channel's `[Yes, No, Reply…]` for one screenshot
 * question are one card with "Reply…". A tap answers every caller whose card takes that choice, a keypad's number within the
 * pick's range too; a caller whose card lacks it (the voice and "Reply…") keeps waiting, and the card shows again for it,
 * as it does after a number outside the range. The newest question's card shows, and an older
 * one comes back once the newer has ended; a card goes when its last caller leaves.
 *
 * The card shows on the overlay ([OwnerScreen.card]) while the conversation screen is not in front ([screenInFront]); while
 * it is, the screen shows [open] above its field instead, so that the overlay never covers the field and the keyboard,
 * and a confirmation is never answered over Kestrel's own screen (design 12.2). "Reply…" on the overlay hides the card until
 * the screen has come to the front, or for [replyOpenMillis] when it does not come. A Continue that the owner taps while
 * the phone is locked waits for his unlock, as the goal notification's Continue does (plan 7E): the card shows again, and
 * [notice] tells the owner to unlock the phone first ([UNLOCK_FIRST]). A tap that answers takes its number among the
 * owner's inputs ([inputIds]) when it has come back from the card, before any caller runs, and every caller that it
 * answers gets that one number (plan 7F). A card that got no answer, because no screen session was connected or the
 * session closed, shows again when a screen session connects ([OwnerScreen.connections]).
 */
internal class CardDesk(
    private val screen: OwnerScreen,
    private val scope: CoroutineScope,
    private val notice: (String) -> Unit = {},
    private val replyOpenMillis: Long = REPLY_OPEN_MILLIS,
    private val inputIds: () -> Long = InputIds::next,
) : SharedCards {
    private class Caller(val card: OwnerCard, val onReply: () -> Unit) {
        val tapped = CompletableDeferred<CardTap>()
    }

    // The overlay's card, and a count that each tap raises, so that a card whose callers still wait shows again.
    private data class Showing(val card: OwnerCard, val round: Int)

    private val lock = Any()
    private val callers = mutableListOf<Caller>()
    private var round = 0
    private val front = MutableStateFlow(false)
    private val replied = MutableStateFlow<Int?>(null)
    private val openCard = MutableStateFlow<OwnerCard?>(null)
    private val showing = MutableStateFlow<Showing?>(null)
    private var replyWait: Job? = null

    override val open: StateFlow<OwnerCard?> = openCard.asStateFlow()

    // The card on the overlay in its screen session ([connection]): a new session shows it again.
    private data class Presenting(val shown: Showing, val connection: Int)

    init {
        scope.launch {
            combine(showing, front, replied, screen.connections) { shown, inFront, reply, connection ->
                shown?.takeIf { !inFront && reply != it.card.key }?.let { Presenting(it, connection) }
            }
                .distinctUntilChanged()
                .collectLatest { presenting -> if (presenting != null) present(presenting.shown) }
        }
    }

    /** The conversation screen came to the front, or left it. */
    fun screenInFront(inFront: Boolean) {
        if (inFront) replied.value = null
        front.value = inFront
    }

    override suspend fun ask(card: OwnerCard, onReply: () -> Unit): CardTap {
        val caller = Caller(card, onReply)
        synchronized(lock) {
            callers += caller
            publish()
        }
        try {
            return caller.tapped.await()
        } finally {
            synchronized(lock) {
                if (callers.remove(caller)) publish()
            }
        }
    }

    override fun toString(): String = "CardDesk(open=${openCard.value?.key}, callers=${synchronized(lock) { callers.size }}, front=${front.value})"

    // Under the lock: the newest question's card, with the most choices among its callers.
    private fun publish() {
        val key = callers.lastOrNull()?.card?.key
        val card = callers.filter { it.card.key == key }.maxByOrNull { it.card.choices.size }?.card
        openCard.value = card
        showing.value = card?.let { Showing(it, round) }
    }

    // A card without an answer, as when no screen session is connected or its session closed, shows again when the next
    // screen session connects.
    private suspend fun present(shown: Showing) {
        val choice = screen.card(shown.card) ?: return
        when {
            choice == CardChoice.Reply -> replyOpened(shown.card.key)
            choice == CardChoice.Continue && screen.locked() -> {
                notice(UNLOCK_FIRST)
                synchronized(lock) {
                    round++
                    publish()
                }
            }
            // The tap's number among the owner's inputs, taken as the tap reaches the desk (plan 7F).
            else -> deliver(shown.card.key, CardTap(choice, inputIds()))
        }
    }

    private fun deliver(key: Int, tap: CardTap) = synchronized(lock) {
        val answered = callers.filter { it.card.key == key && it.card.takes(tap.choice) }
        callers.removeAll(answered)
        for (caller in answered) caller.tapped.complete(tap)
        round++
        publish()
    }

    private fun replyOpened(key: Int) {
        val asked = synchronized(lock) { callers.filter { it.card.key == key && CardChoice.Reply in it.card.choices } }
        replied.value = key
        replyWait?.cancel()
        replyWait = scope.launch {
            delay(replyOpenMillis)
            replied.compareAndSet(key, null)
        }
        for (caller in asked) caller.onReply()
    }

    companion object {
        /** How long a "Reply…" on the overlay waits for the conversation screen before it shows the card again. */
        const val REPLY_OPEN_MILLIS = 5_000L

        /** A Continue tapped on the card while the phone is locked (plan 7E's rule for Continue). */
        const val UNLOCK_FIRST = "Unlock the phone, then tap Continue."
    }
}
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/OwnerSupport.kt`, replace:

```kotlin
/** Kestrel's own parts of the screen: the confirmation card, the feedback bar, and its own app in front. */
internal interface OwnerScreen {
    /** Shows the confirmation card with [question] until a button answers it; null when no screen session is connected. */
    suspend fun card(question: String): Boolean?
```

with:

```kotlin
/** Kestrel's own parts of the screen: the question card, the feedback bar, the lock, and its own app in front. */
internal interface OwnerScreen {
    /**
     * Shows [card] on Kestrel's question card until a button or the keypad answers it, and returns that choice (design 10.3
     * of sub-project 7); null when no screen session is connected, or when the card gave no answer because a different
     * question replaced it. Only [CardDesk] calls it: every question has one card.
     */
    suspend fun card(card: OwnerCard): CardChoice?

    /** Whether the phone is locked: a card's Continue then waits for the owner's unlock (plan 7E's rule for Continue). */
    fun locked(): Boolean

    /**
     * How many screen sessions have connected so far; it changes when a new one connects, so that the card desk shows the
     * open card again in it after a card got no answer (plan 7F).
     */
    val connections: Flow<Int>
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/OwnerSupport.kt`, replace:

```kotlin
import android.content.Context
import android.content.Intent
```

with:

```kotlin
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/OwnerSupport.kt`, replace:

```kotlin
import dev.kestrel.app.orchestrator.CallState
```

with:

```kotlin
import dev.kestrel.app.R
import dev.kestrel.app.orchestrator.CallState
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/OwnerSupport.kt`, replace:

```kotlin
import dev.kestrel.screen.model.ScreenTree
```

with:

```kotlin
import dev.kestrel.screen.model.ScreenTree
import dev.kestrel.screen.overlay.CardEnd
import dev.kestrel.screen.overlay.CardQuestion
import dev.kestrel.screen.overlay.NumberPad
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/OwnerSupport.kt`, replace:

```kotlin
    override suspend fun card(question: String): Boolean? = session()?.confirm(question)
```

with:

```kotlin
    override suspend fun card(card: OwnerCard): CardChoice? {
        val session = session() ?: return null
        // A pick's button says its choice; every other button says its kind's word (design 10.3 of sub-project 7).
        val labels = card.choices.map { choice ->
            when (choice) {
                is CardChoice.Pick -> card.picks[choice.number - 1]
                else -> context.getString(checkNotNull(choice.label))
            }
        }
        val pad = NumberPad(context.getString(R.string.card_delete_digit), context.getString(R.string.card_enter), context.getString(R.string.card_move), card.highest)
            .takeIf { card.numberPad }
        return when (val end = session.ask(CardQuestion(card.key, card.text, labels, pad))) {
            is CardEnd.Tapped -> card.choices[end.button]
            is CardEnd.Number -> CardChoice.Pick(end.value)
            CardEnd.Withdrawn -> null
        }
    }

    override fun locked(): Boolean = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/OwnerSupport.kt`, replace:

```kotlin
 * sub-project 7): the screen session that is connected when it is asked ([session]), Android's audio, the pages that
 * [open] opens for the owner and the phone's [calls] (Kestrel sees them only with READ_PHONE_STATE).
 */
internal class AndroidOwnerSupport(
    private val context: Context,
    private val session: () -> ScreenSession?,
    private val open: suspend (Intent) -> Boolean,
    private val calls: () -> Flow<CallState>,
) : TouchWatch, OwnerScreen, OtherAudio, HandOverSignals {
    private val audio = context.getSystemService(AudioManager::class.java)
```

with:

```kotlin
 * sub-project 7): the screen session that is connected ([sessions]; the one when it is asked), Android's audio, the pages
 * that [open] opens for the owner and the phone's [calls] (Kestrel sees them only with READ_PHONE_STATE).
 */
internal class AndroidOwnerSupport(
    private val context: Context,
    private val sessions: StateFlow<ScreenSession?>,
    private val open: suspend (Intent) -> Boolean,
    private val calls: () -> Flow<CallState>,
) : TouchWatch, OwnerScreen, OtherAudio, HandOverSignals {
    private val audio = context.getSystemService(AudioManager::class.java)

    override val connections: Flow<Int> = sessions.filterNotNull().withIndex().map { it.index + 1 }.onStart { emit(0) }

    private fun session(): ScreenSession? = sessions.value
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/OwnerSupport.kt`, replace:

```kotlin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
```

with:

```kotlin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.withIndex
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
        AndroidOwnerSupport(
            context = appContext,
            session = { screen.value },
```

with:

```kotlin
        AndroidOwnerSupport(
            context = appContext,
            sessions = screen,
```
In `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, replace:

```kotlin
internal class VoiceChannel(
    private val voice: VoicePath,
    private val screen: OwnerScreen,
    private val audio: OtherAudio,
```

with:

```kotlin
internal class VoiceChannel(
    private val voice: VoicePath,
    private val screen: OwnerScreen,
    private val cards: OwnerCards,
    private val audio: OtherAudio,
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, replace:

```kotlin
    // Without a screen session there is no card, and only the voice answers.
    private suspend fun withCard(question: ChannelQuestion, spoken: String, voiceAnswers: Boolean): ChannelAnswer = coroutineScope {
        val asking = launch { spoken(question, spoken, voiceAnswers) }
        val yes = screen.card(question.text) ?: awaitCancellation()
        asking.cancel()
        if (yes) ChannelAnswer.Yes else ChannelAnswer.No
    }
```

with:

```kotlin
    // The card through the desk, which shares it with the text channel's copy of the question (design 10.3 of sub-project
    // 7); without a screen session there is no card, and only the voice answers. The voice's card has no Reply…, so the
    // desk gives it only answers.
    private suspend fun withCard(question: ChannelQuestion, spoken: String, voiceAnswers: Boolean): CardAnswer = coroutineScope {
        val asking = launch { spoken(question, spoken, voiceAnswers) }
        val tapped = cards.ask(OwnerCard.of(question, reply = false))
        asking.cancel()
        CardAnswer(checkNotNull(tapped.choice.answer) { "The voice's card has no Reply…" }, tapped.act)
    }
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, replace:

```kotlin
    override suspend fun ask(question: ChannelQuestion): ChannelAnswer = try {
```

with:

```kotlin
    override suspend fun ask(question: ChannelQuestion): CardAnswer = try {
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, replace:

```kotlin
    private suspend fun confirm(question: ChannelQuestion): ChannelAnswer {
```

with:

```kotlin
    private suspend fun confirm(question: ChannelQuestion): CardAnswer {
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, replace:

```kotlin
    private suspend fun screenshot(question: ChannelQuestion): ChannelAnswer {
```

with:

```kotlin
    private suspend fun screenshot(question: ChannelQuestion): CardAnswer {
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, replace:

```kotlin
    override suspend fun handOver(handOver: HandOver): HandOverEnd = try {
        voice.handOver(handOver.text)
        handOvers.opened(handOver.kind)
        handOvers.endedByItself(handOver.kind)
        HandOverEnd.DONE_BY_ITSELF
```

with:

```kotlin
    // The voice's hand-over has no button: the owner's "Kestrel, continue" reaches the conversation as an input.
    override suspend fun handOver(handOver: HandOver): HandOverAnswer = try {
        voice.handOver(handOver.text)
        handOvers.opened(handOver.kind)
        handOvers.endedByItself(handOver.kind)
        HandOverAnswer(HandOverEnd.DONE_BY_ITSELF, act = null)
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, replace:

```kotlin
import dev.kestrel.conversation.channel.ChannelQuestion
import dev.kestrel.conversation.channel.OwnerChannel
```

with:

```kotlin
import dev.kestrel.conversation.channel.ChannelQuestion
import dev.kestrel.conversation.channel.HandOverAnswer
import dev.kestrel.conversation.channel.OwnerChannel
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, replace:

```kotlin
import dev.kestrel.app.listen.VoicePath
import dev.kestrel.conversation.channel.ChannelOutput
```

with:

```kotlin
import dev.kestrel.app.listen.VoicePath
import dev.kestrel.conversation.channel.CardAnswer
import dev.kestrel.conversation.channel.ChannelOutput
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, replace:

```kotlin
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.InputChannel
```

with:

```kotlin
import dev.kestrel.conversation.model.InputChannel
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, replace:

```kotlin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
```

with:

```kotlin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
     * What the conversation and a goal's rounds need from Android (design 5.5 of sub-project 7): the confirmation card,
```

with:

```kotlin
     * What the conversation and a goal's rounds need from Android (design 5.5 of sub-project 7): the question card,
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
    /** The conversation's voice (design 11 of sub-project 7), over the Orchestrator. */
    private val voiceChannel: VoiceChannel by lazy {
        VoiceChannel(listening.voicePath, ownerSupport, ownerSupport, ownerSupport, PermissionDialog { permissions -> PermissionActivity.ask(appContext, permissions) })
    }
```

with:

```kotlin
    /**
     * The one card of each question, whichever channel asks it (design 10.3 of sub-project 7; plan 7F): the overlay while
     * the conversation screen is not in front, and the screen's copy while it is.
     */
    internal val cardDesk: CardDesk by lazy { CardDesk(ownerSupport, scope) }

    /** The conversation's voice (design 11 of sub-project 7), over the Orchestrator. */
    private val voiceChannel: VoiceChannel by lazy {
        VoiceChannel(listening.voicePath, ownerSupport, cardDesk, ownerSupport, ownerSupport, PermissionDialog { permissions -> PermissionActivity.ask(appContext, permissions) })
    }
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
import dev.kestrel.app.conversation.AndroidOwnerSupport
```

with:

```kotlin
import dev.kestrel.app.conversation.AndroidOwnerSupport
import dev.kestrel.app.conversation.CardDesk
```

- [ ] **Step 6: Run the tests and the builds**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.conversation.OwnerCardTest --tests=dev.kestrel.app.conversation.CardDeskTest --tests=dev.kestrel.app.conversation.VoiceChannelTest`
Expected: BUILD SUCCESSFUL; `OwnerCardTest` 3, `CardDeskTest` 7 and `VoiceChannelTest` 10 tests pass (plan 7D leaves 9).

Run: `scripts\gradle.ps1 :core:screen:testDebugUnitTest :core:conversation:test :app:testDebugUnitTest :app:assembleDebugAndroidTest; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL. Count the results as "Before you start" says: `:app` JVM A₀ + 11, `:core:conversation` C₀ + 1, `:core:screen` S₀. The device tests compile; they run in Task 14.

Run: `git grep -n "inputIds" -- core/conversation/src/main app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`
Expected: no output: the engine numbers no act of the owner's itself (`CardDesk`'s own `inputIds`, which numbers a tap as it comes back from the card, is in another file).

Run: `git grep -n 'ConfirmationCard\|\.confirm("\|screen_confirm\|Kestrel confirmation' -- core app`
Expected: no output.

- [ ] **Step 7: Commit**

```bash
git add core/screen core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/OwnerChannel.kt core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/ConversationOwner.kt core/conversation/src/main/kotlin/dev/kestrel/conversation/machine core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt core/conversation/src/testFixtures/kotlin/dev/kestrel/conversation/testing/FakeChannel.kt core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt app/src/main/kotlin/dev/kestrel/app/conversation/OwnerCard.kt app/src/main/kotlin/dev/kestrel/app/conversation/CardDesk.kt app/src/main/kotlin/dev/kestrel/app/conversation/OwnerSupport.kt app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/main/res/values/strings_card.xml app/src/test/kotlin/dev/kestrel/app/conversation/OwnerCardTest.kt app/src/test/kotlin/dev/kestrel/app/conversation/CardDeskTest.kt app/src/test/kotlin/dev/kestrel/app/conversation/VoiceChannelTest.kt app/src/androidTest/kotlin/dev/kestrel/app/screen app/src/androidTest/kotlin/dev/kestrel/app/agent/AgentEndToEndTest.kt
git commit -m "feat(screen, app): the question card with its kind's buttons and a keypad for a pick, one card per question number for both channels, and a tap that keeps its own number into the engine" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 2: A typed round waits for a running instant command

Obsolete since plans 7D and 7E as built (amendment of 2026-11-26): this task has no steps. Plan 7D's `ScreenTurns` is the one rule for who acts on the screen, and its final form already does what this task planned, for every channel: it knows the instant command that runs from its start (`startCommand(job, since, letGo)`, with `InputIds.newest()`) until its job has really ended, also after a cancellation (`stopped(job)`), and a goal's round that takes the turn (`take(after, waiting)`) asks the command to let go only for an input of the owner's that came after the command started (`after > since`); otherwise it waits until the command has ended, however long it runs, and says so as it starts to wait (`waiting`, which plan 7E carries into `ConversationView.waitsBehindCommand`). A typed input, a button on the screen's copy of a card, a tap on the card over the app and a button of the goal notification need only their number among the owner's inputs, taken as they arrive: Task 4's `TextChannel.input` takes it for typed words and the screen's buttons, Task 1's `CardDesk` for a tap on the card over the app, and plan 7E's `GoalActionReceiver` for the notification's buttons. No `running(job)`, `afterStopped()` or `RUNNING_MILLIS` comes, and this plan changes neither `ScreenTurns.kt`, `Listening.kt`'s use of it nor `ScreenTurnsTest.kt`. The task keeps its number, so that the later tasks' numbers and the counts' names stay: A₂ = A₁ and AD₂ = AD₁.

---
### Task 3: The conversation's parts for the screen, the list and the device tests

Design 9.5 of sub-project 7: a past conversation can be deleted, or all of them, and the engine never deletes the open one; the list's search field filters by the search's own rule; and the device tests delete the conversations that they made, and only those (Task 8's `TypedTests`). Design 15.2: the device tests shorten D21's waits. This task gives the engine `deletePast(id)`, which the engine's loop checks against the open conversation and the one that a switch is opening, and whose deletion its writer does in the loop's order (plan 7B's rule that every write to the store goes through the writer), and `deleteAllPast()`, which keeps every conversation that is open at some moment between the owner's ask and the deletion's transaction (a keep-set that it starts with the conversation open then, and that the loop adds each newly opened conversation to while the request is pending), waits at the engine's writer's barrier (plan 7B's `stored()`) until every write that the loop queued before it is stored, and then runs beside the engine's inbox, in its scope, over the store's new `deleteClosed(keep)`, because that may wait minutes for the index to be read or rebuilt and must never hold up the conversation or the writer (review N2); a seam for its timers' lengths, which shortens each timer's deadline itself (plan 7B's timers are deadlines on `ConversationClock.sinceBootMillis`, and a `delay` only wakes the loop) and which `AppGraph.conversationTimers` sets for device tests only. Both store parts work on plan 7B's index of past conversations (design 9.2), so that neither reads a body: "Delete all past conversations" needs a path of its own, because the port's `deleteAll` is the whole of "delete history", which also deletes the logs and erases the key (plan 7C's `ae33cfda`). This task adds it to plan 7B's store and plan 7C's archive, which are built: the port's `RecordArchive.deleteClosed(keep, index)` deletes, in one transaction, every closed row whose id is not in `keep` (`open = 0 AND id NOT IN (:keep)`), also one that the index does not name or that this version cannot read, and changes the index's shards as it is told; it keeps the open row, the logs and the archive's key. `ConversationDao.deleteClosed`, `ConversationArchiveStore.deleteClosed` and `RoomRecordArchive.deleteClosed` carry it to the encrypted table, and the fixtures' `MemoryArchive` does it in memory. The store's `deleteClosed(keep)`, under its `withIndex`, reads the engine's keep-set under its lock, so that no write of the writer comes between that read and the transaction, raises the store's `generation` first, as "delete history" does, so that a search that it overtook gives out nothing, and gives the port the index without the deleted conversations and kept rows, which writes only the shards that changed. The loop adds a step's open conversation to every pending keep-set right after the step, before it queues any write of the step, so a conversation whose closed row the transaction can see is in the set: the one that was open when the owner asked, one that he started or continued meanwhile, also when it closed before the deletion ran (review M1). While the index is read or rebuilt it waits, and while the index cannot be read it throws `PastUnavailableException`, which the engine's `deleteAllPast()` gives as `PastDeletion.UNAVAILABLE`. `HistorySearch.matching(text)` ranks the index's shards with `HistoryRanking`, as the agent's search does, and says when the index is being rebuilt or cannot be read.

**Files:**
- Create: `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/PastDeletion.kt`
- Modify: `core/conversation/src/main/kotlin/dev/kestrel/conversation/store/RecordArchive.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/store/ConversationStore.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/store/ArchivedConversationStore.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/store/HistorySearch.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, `core/conversation/src/testFixtures/kotlin/dev/kestrel/conversation/testing/MemoryArchive.kt`, `core/privacy/src/main/kotlin/dev/kestrel/privacy/db/ConversationRow.kt`, `core/privacy/src/main/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStore.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/RoomRecordArchive.kt`, `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`
- Test: modify `core/conversation/src/test/kotlin/dev/kestrel/conversation/store/ArchivedConversationStoreTest.kt`, `core/conversation/src/test/kotlin/dev/kestrel/conversation/store/HistorySearchTest.kt`, `core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt`, `core/privacy/src/test/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStoreTest.kt`, `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KestrelDatabaseTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/conversation/RoomRecordArchiveTest.kt`

**Interfaces:**
- Consumes: plan 7B's index store (Tasks 5a to 5d): `ConversationStore` (`delete(id)`, `generation`, `searchable()`, `list()`), `Searchable` (`Ready(past, open)`, `Rebuilding`, `Failed`), `PastUnavailableException`, `PastIndex` (`conversations`, `kept`, `searches`, `without(ids)`), `ArchivedConversationStore` (its `withIndex` with a suspend block, `change` and `deleted`), `HistorySearch`, `HistoryRanking.rank` and `wanted`, the fixtures' `MemoryArchive` (`calls`, `failures`, `keyErased`, `readShardsGate`, `changing`), `RecordArchive` (`purge`, `deleteAll`); plan 7C's archive as fixed in `ae33cfda`: `ConversationDao` (`purge`, `change`, `deleteClosedOutside`), `ConversationArchiveStore` (`purge`, `key.storing`, `encrypted`) and `RoomRecordArchive` (`purge`, `plain()`); plan 7B's `ConversationEngine` as fixed in `25cdca0a` and `e76ad7a6` (its `Message` inbox, its writer's `Write`s and the barrier `stored()`, `switchTo` and `reopened`, and `startTimer` with its deadline on `clock.sinceBootMillis()`) and `Timer`; plan 7D's `AppGraph.conversations` and `conversationSteps`.
- Produces: `RecordArchive.deleteClosed(keep: Collection<String>, index: IndexWrite): Int`, `ConversationDao.deleteClosed(keep: List<String>, index: IndexRowUpdate): Int`, `ConversationArchiveStore.deleteClosed(keep: Collection<String>, index: IndexUpdate): Int`, `RoomRecordArchive.deleteClosed` and `MemoryArchive.deleteClosed`; `ConversationStore.deleteClosed(keep: () -> Set<ConversationId>): Int`; `sealed interface PastMatches { All; Only(ids: Set<ConversationId>); Rebuilding; Failed }` and `HistorySearch.matching(text: String): PastMatches` (package `dev.kestrel.conversation.store`); `ConversationEngine.deletePast(id: ConversationId): PastDeletion`, `deleteAllPast(): PastDeletion` (a call while one runs waits for it) and `allPastDeletion: StateFlow<AllPastDeletion>`; `enum class PastDeletion { DELETED, OPEN, UNAVAILABLE, FAILED }` and `sealed interface AllPastDeletion { Idle; Running; Ended(outcome) }` (package `dev.kestrel.conversation.engine`); the engine constructor's last parameter `timerMillis: (Timer, Long) -> Long = { _, millis -> millis }`; `AppGraph.conversationTimers: ((Timer, Long) -> Long)?`.

Counts: `:core:conversation` C₁ + 8 (`ArchivedConversationStoreTest` + 1, `HistorySearchTest` + 1, `ConversationEngineTest` + 6), where C₁ = C₀ + 1 is the count after Task 1 (Task 2 adds none). `:core:privacy` JVM P₀ + 1 (`ConversationArchiveStoreTest` + 1) and device tests PD₀ + 1 (`KestrelDatabaseTest` + 1). `:app` JVM unchanged; `:app` device tests AD₂ + 1 (`RoomRecordArchiveTest` + 1), where AD₂ is the count after Task 2.

- [ ] **Step 1: Write the failing tests**

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/store/ArchivedConversationStoreTest.kt`, replace:

```kotlin
    // On the A71, decrypting every row took 5.2 s even in one query; the open row and the index take one decryption each,
    // and one more per shard.
```

with:

```kotlin
    // Design 9.5 of sub-project 7: "Delete all past conversations" keeps the open one, the one that it is told to keep and
    // the key, reads no body, deletes in one call of the archive with the index, and raises the generation; while the index
    // cannot be read, it deletes nothing and says so.
    @Test
    fun deleteClosedKeepsTheOpenConversationAndTheKeptOneReadsNoBodyAndRaisesTheGeneration() = test {
        store.save(conversation(1, now - 3_000))
        store.save(conversation(2, now - 2_000))
        store.save(conversation(3, now - 1_000))
        store.save(conversation(4, now, open = true))
        val fresh = fresh()
        fresh.list()
        val generation = fresh.generation
        archive.calls.clear()

        assertEquals(2, fresh.deleteClosed { setOf(id(2)) })

        assertEquals(listOf("deleteClosed"), archive.calls)
        assertEquals(setOf(id(2).value, id(4).value), archive.rows.keys)
        assertEquals(listOf(id(2)), fresh.list().map { it.id })
        assertEquals(id(4), fresh.open()!!.id)
        assertTrue(fresh.generation > generation)
        assertFalse(archive.keyErased)

        archive.failures["readShards"] = IOException("the disk failed")
        val failing = fresh()
        assertTrue(runCatching { failing.deleteClosed { emptySet() } }.exceptionOrNull() is PastUnavailableException)
    }

    // On the A71, decrypting every row took 5.2 s even in one query; the open row and the index take one decryption each,
    // and one more per shard.
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/store/HistorySearchTest.kt`, replace:

```kotlin
    private suspend fun found(topic: String?): ConversationMatch? = (search.conversation(topic) as? ConversationLookup.Found)?.match
```

with:

```kotlin
    private suspend fun found(topic: String?): ConversationMatch? = (search.conversation(topic) as? ConversationLookup.Found)?.match

    private suspend fun listed(text: String): Set<ConversationId>? = (search.matching(text) as? PastMatches.Only)?.ids

    // Design 9.5 of sub-project 7: the list's search field keeps the past conversations that hold a match, from the index alone,
    // and says when the index cannot be read.
    @Test
    fun theListsSearchNamesThePastConversationsThatHoldAMatchFromTheIndex() = test {
        save(1, record(1, "Plan the trip to Novi Sad", 3 * day), record(2, "Book the hotel", 3 * day - 1))
        save(2, record(1, "Read my latest SMS", day))
        save(3, record(1, "Book a table", 0), open = true)
        store.list()
        archive.calls.clear()

        assertEquals(setOf(id(1)), listed("BOOK the hotel"))
        assertEquals(setOf(id(1), id(2)), listed("trip SMS"))
        assertEquals(emptySet<ConversationId>(), listed("Belgrade"))
        assertEquals(PastMatches.All, search.matching("the about"))
        assertTrue(archive.calls.isEmpty())

        archive.failures["readShards"] = IOException("the disk failed")
        store = ArchivedConversationStore(archive, backgroundScope) {}
        search = HistorySearch(store, { retention }, clock)
        assertEquals(PastMatches.Failed, search.matching("hotel"))
    }
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt`, replace:

```kotlin
        screen: ScreenTurn = FakeScreenTurn { false },
    ): ConversationEngine {
```

with:

```kotlin
        screen: ScreenTurn = FakeScreenTurn { false },
        timerMillis: (Timer, Long) -> Long = { _, millis -> millis },
    ): ConversationEngine {
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt`, replace:

```kotlin
            settings, testMode, voiceOn, clock, report, onStep = onStep,
```

with:

```kotlin
            settings, testMode, voiceOn, clock, report, onStep = onStep, timerMillis = timerMillis,
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt`, replace:

```kotlin
import dev.kestrel.conversation.machine.QuietClock
```

with:

```kotlin
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.machine.Timer
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt`, add before its last closing brace:

```kotlin

    // Design 9.5 of sub-project 7: a past conversation is deleted, or all of them, and never the open one.
    @Test
    fun aPastConversationIsDeletedFromTheListButTheOpenOneIsNot() = scope.runTest {
        val engine = engine(environment(listSms, finish(1), listSms, finish(1), listSms, finish(1)))
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        val first = engine.view.value.id!!
        advanceTimeBy(61_000)
        runCurrent()
        engine.submit(typed("Read my latest SMS again"))
        runCurrent()
        val second = engine.view.value.id!!
        advanceTimeBy(61_000)
        runCurrent()
        engine.submit(typed("And once more"))
        runCurrent()
        val open = engine.view.value.id!!

        assertEquals(PastDeletion.OPEN, engine.deletePast(open))
        assertEquals(PastDeletion.DELETED, engine.deletePast(first))
        assertEquals(listOf(second), store.list().map { it.id })
        assertEquals(PastDeletion.DELETED, engine.deleteAllPast())
        assertTrue(store.list().isEmpty())
        assertEquals(open, store.open()!!.id)
        assertEquals(open, engine.view.value.id)
    }

    // Plan 7F's device tests shorten the engine's timers through its seam, which shortens the deadline itself, not only the
    // wake-up; the machine's lengths stay.
    @Test
    fun theTimersTakeTheirLengthsThroughTheSeam() = scope.runTest {
        val engine = engine(environment(listSms, finish(1)), timerMillis = { _, millis -> millis / 60 })
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        assertNotNull(engine.view.value.id)

        advanceTimeBy(1_001)
        runCurrent()

        assertNull(engine.view.value.id)
    }

    // Review N2 and R4 of plan 7F: "Delete all" waits for the index of past conversations beside the engine's inbox, so the
    // conversation goes on meanwhile, and the open conversation stays.
    @Test
    fun theConversationGoesOnWhileDeleteAllWaitsForTheIndex() = scope.runTest {
        val gate = CompletableDeferred<Unit>()
        archive.readShardsGate = gate
        val engine = engine(environment(listSms, finish(1)))
        engine.restore()
        val deleting = async { engine.deleteAllPast() }
        runCurrent()

        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        assertFalse(deleting.isCompleted)
        assertEquals(EndReason.FINISHED, engine.view.value.records.single().end)
        val open = engine.view.value.id!!

        gate.complete(Unit)
        runCurrent()
        assertEquals(PastDeletion.DELETED, deleting.await())
        assertEquals(open, store.open()!!.id)
        assertEquals(open, engine.view.value.id)
    }

    // Review B-M4 of plan 7F: the engine says that "Delete all" runs, so that the settings page shows it also after the owner
    // left it and came back, and a second ask while it runs waits for it and deletes nothing again.
    @Test
    fun aSecondDeleteAllWhileOneRunsWaitsForItAndTheEngineSaysThatItRuns() = scope.runTest {
        val gate = CompletableDeferred<Unit>()
        archive.readShardsGate = gate
        val engine = engine(environment())
        engine.restore()
        assertEquals(AllPastDeletion.Idle, engine.allPastDeletion.value)
        val first = async { engine.deleteAllPast() }
        runCurrent()
        assertEquals(AllPastDeletion.Running, engine.allPastDeletion.value)
        val second = async { engine.deleteAllPast() }
        runCurrent()

        gate.complete(Unit)
        runCurrent()
        assertEquals(PastDeletion.DELETED, first.await())
        assertEquals(PastDeletion.DELETED, second.await())
        assertEquals(1, archive.calls.count { it == "deleteClosed" })
        assertEquals(AllPastDeletion.Ended(PastDeletion.DELETED), engine.allPastDeletion.value)
    }

    // Design 9.5 of sub-project 7 and plan 7F's review M1: "Delete all" keeps every conversation that is open at some
    // moment between the owner's ask and its transaction: the one that was open when he asked, and one that he started
    // after it, also when both close while the deletion waits for the index; it deletes the others.
    @Test
    fun deleteAllKeepsEveryConversationThatWasOpenBeforeItsTransactionAlsoWhenItClosedMeanwhile() = scope.runTest {
        val gate = CompletableDeferred<Unit>()
        archive.readShardsGate = gate
        val engine = engine(environment(listSms, finish(1), listSms, finish(1), listSms, finish(1)))
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        val past = engine.view.value.id!!
        engine.endQuietTime()
        runCurrent()
        engine.submit(typed("Read my latest SMS again"))
        runCurrent()
        val open = engine.view.value.id!!
        val deleting = async { engine.deleteAllPast() }
        runCurrent()
        engine.endQuietTime()
        runCurrent()
        engine.submit(typed("And once more"))
        runCurrent()
        val started = engine.view.value.id!!
        engine.endQuietTime()
        runCurrent()
        assertNull(engine.view.value.id)

        gate.complete(Unit)
        runCurrent()
        assertEquals(PastDeletion.DELETED, deleting.await())
        assertEquals(setOf(open, started), store.list().map { it.id }.toSet())
        assertNull(store.get(past))
    }

    // Plan 7F's review L1: the deletion of the conversation that a running switch opens finds it open and leaves it; without
    // the check, the writer would open it and then delete its open row.
    @Test
    fun deletingTheConversationThatARunningSwitchOpensLeavesIt() = scope.runTest {
        val reopening = CompletableDeferred<Unit>()
        val gated = object : ConversationStore by store {
            override suspend fun reopen(id: ConversationId, closing: ConversationSnapshot?, nowWall: Long, testMode: Boolean): ConversationSnapshot? {
                reopening.await()
                return store.reopen(id, closing, nowWall, testMode)
            }
        }
        val engine = engine(environment(listSms, finish(1)), gated)
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        val past = engine.view.value.id!!
        engine.endQuietTime()
        runCurrent()

        engine.submit(input(InputChannel.TEXT, InputIntent.ContinueConversation(past)))
        runCurrent()
        val deleting = async { engine.deletePast(past) }
        runCurrent()
        reopening.complete(Unit)
        runCurrent()

        assertEquals(PastDeletion.OPEN, deleting.await())
        assertEquals(past, engine.view.value.id)
        assertEquals(past, store.open()!!.id)
    }
```

In `core/privacy/src/test/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStoreTest.kt`, replace:

```kotlin
    // Design 9.3 of sub-project 7: retention deletes by the table's own times, in one transaction with the index.
```

with:

```kotlin
    // Plan 7F: "Delete all past conversations" deletes every closed row but the one that it keeps, in one transaction with
    // the index, and keeps the open row and the key, which still decrypts what stays.
    @Test
    fun deleteClosedDeletesEveryClosedRowButTheKeptOneWithTheIndexInOneTransactionAndKeepsTheKey() = runTest {
        write(
            PlainConversation(closed("old", last = 5), byteArrayOf(1)),
            PlainConversation(closed("kept", last = 50), byteArrayOf(2)),
            PlainConversation(closed("new", last = 500), byteArrayOf(3)),
            PlainConversation(opened("open", last = 1), byteArrayOf(4)),
            index = IndexUpdate.Update(mapOf("0" to byteArrayOf(1)), emptySet()),
        )
        val before = dao.transactions

        assertEquals(2, store.deleteClosed(keep = listOf("kept"), index = IndexUpdate.Update(mapOf("0" to byteArrayOf(2)), emptySet())))

        assertEquals(setOf("kept", "open"), dao.rows.keys)
        assertArrayEquals(byteArrayOf(2), (store.shards().single() as StoredShard.Readable).payload)
        assertArrayEquals(byteArrayOf(2), (store.read(listOf("kept")).single() as StoredConversation.Readable).payload)
        assertEquals(before + 1, dao.transactions)
    }

    // Design 9.3 of sub-project 7: retention deletes by the table's own times, in one transaction with the index.
```

In `core/privacy/src/test/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStoreTest.kt`, replace:

```kotlin
    override fun purge(oldest: Long, newest: Long, index: IndexRowUpdate): Int {
        transactions++
        return super.purge(oldest, newest, index)
    }
```

with:

```kotlin
    override fun purge(oldest: Long, newest: Long, index: IndexRowUpdate): Int {
        transactions++
        return super.purge(oldest, newest, index)
    }

    override fun deleteClosed(keep: List<String>, index: IndexRowUpdate): Int {
        transactions++
        return super.deleteClosed(keep, index)
    }
```

In `core/privacy/src/test/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStoreTest.kt`, replace:

```kotlin
    override fun deleteClosedOutside(oldest: Long, newest: Long): Int {
```

with:

```kotlin
    override fun deleteClosedExcept(keep: List<String>): Int {
        val gone = rows.values.filter { !it.open && it.id !in keep }.map { it.id }
        gone.forEach { rows.remove(it) }
        return gone.size
    }

    override fun deleteClosedOutside(oldest: Long, newest: Long): Int {
```

In `core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KestrelDatabaseTest.kt`, replace:

```kotlin
    // Design 9.3 of sub-project 7: retention deletes by the table's own times, keeps the rows at both limits and the open
```

with:

```kotlin
    // Plan 7F: "Delete all past conversations" deletes every closed row but the one that it keeps, and changes the shards,
    // in one transaction; the open row and the logs stay. With nothing to keep (`NOT IN ()`), it deletes every closed row.
    @Test
    fun deleteClosedDeletesEveryClosedRowButTheKeptOneAndLeavesTheOpenRowAndTheLogs() = runBlocking {
        db.logDao().insert(LogEntryRow(log = "activity", timestampMillis = 1, payload = byteArrayOf(1)))
        val dao = db.conversationDao()
        dao.write(
            listOf(
                conversation("old", last = 1, open = false),
                conversation("kept", last = 2, open = false),
                conversation("new", last = 3, open = false),
                conversation("open", last = 4, open = true),
            ),
            emptyList(),
            IndexRowUpdate.Update(listOf(shard("0", 1)), emptyList()),
        )

        assertEquals(2, dao.deleteClosed(keep = listOf("kept", "gone"), index = IndexRowUpdate.Update(listOf(shard("0", 2)), emptyList())))
        assertEquals(setOf("kept", "open"), dao.rows(listOf("old", "kept", "new", "open")).map { it.id }.toSet())
        assertArrayEquals(byteArrayOf(2), dao.shards().single().payload!!)

        assertEquals(1, dao.deleteClosed(keep = emptyList(), index = IndexRowUpdate.Keep))
        assertEquals(listOf("open"), dao.rows(listOf("kept", "open")).map { it.id })
        assertEquals(1, db.logDao().newest("activity", 10).size)
    }

    // Design 9.3 of sub-project 7: retention deletes by the table's own times, keeps the rows at both limits and the open
```

In `app/src/androidTest/kotlin/dev/kestrel/app/conversation/RoomRecordArchiveTest.kt`, replace:

```kotlin
    // Design 6.1 of sub-project 7: the table's open column is the one truth about which conversation is open, and the
```

with:

```kotlin
    // Plan 7F: "Delete all past conversations" through the port deletes every closed row but the kept one, with the index,
    // and keeps the open row and the archive's key.
    @Test
    fun deleteClosedKeepsTheOpenRowTheKeptRowAndTheKey() = runBlocking {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val kept = ArchiveHeader("00112233445566778899aabbccddeeff", openedMillis = 11, lastMillis = 21, open = false, commands = 1)
        archive.write(ArchiveChange(rows = listOf(row(past, "{}"), row(kept, "{}"), row(current, "{}")), index = IndexWrite.Replace(mapOf("0" to byteArrayOf(1)))))

        assertEquals(1, archive.deleteClosed(keep = listOf(kept.id), index = IndexWrite.Replace(mapOf("0" to byteArrayOf(2)))))

        assertEquals(setOf(kept.id, current.id), archive.read(listOf(past.id, kept.id, current.id)).map { it.header.id }.toSet())
        assertEquals(listOf(kept.id), archive.closedIds())
        assertTrue(archive.readShards().single() is ShardRead.Readable)
        assertEquals(current, archive.readOpen()!!.header)
        assertTrue(keyStore.containsAlias(alias))
    }

    // Design 6.1 of sub-project 7: the table's open column is the one truth about which conversation is open, and the
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt`, replace:

```kotlin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
```

with:

```kotlin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `scripts\gradle.ps1 :core:conversation:test --tests=dev.kestrel.conversation.engine.ConversationEngineTest --tests=dev.kestrel.conversation.machine.GoalRulesTest --tests=dev.kestrel.conversation.store.*`
Expected: FAIL: the compiler does not know `deleteClosed` (of the store and of the archive's port), `matching`, `PastMatches`, `deletePast`, `deleteAllPast`, `allPastDeletion`, `PastDeletion`, `AllPastDeletion` and `timerMillis`.

- [ ] **Step 3: The store deletes every past conversation, and the list searches the index**

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/store/ConversationStore.kt`, replace:

```kotlin
    /**
     * How many times "delete history" ran in this process: [deleteAll] raises it before it deletes anything, also when the
     * deletion does not complete. A reader that takes it before it reads, and compares it before it gives out what it read
```

with:

```kotlin
    /**
     * How many times "delete history" or "Delete all past conversations" ran in this process: [deleteAll] and
     * [deleteClosed] raise it before they delete anything, also when the deletion does not complete. A reader that takes it
     * before it reads, and compares it before it gives out what it read
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/store/ConversationStore.kt`, replace:

```kotlin
     * complete; the store notes it as its own failure.
     */
    suspend fun deleteAll()
```

with:

```kotlin
     * complete; the store notes it as its own failure.
     */
    suspend fun deleteAll()

    /**
     * "Delete all past conversations" (design 9.5; plan 7F): deletes every closed conversation that [keep] does not name,
     * also one in a format that this version cannot read, and returns how many went. The open conversation, those that
     * [keep] names, the logs and the archive's key stay, and no body is read. [keep] gives the conversations that were open
     * at some moment since the owner asked; the store reads it under its lock, right before the archive's transaction, so
     * that no write comes between. It waits while the index is read or rebuilt; while the index cannot be read, it throws
     * [PastUnavailableException] and deletes nothing.
     */
    suspend fun deleteClosed(keep: () -> Set<ConversationId>): Int
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/store/RecordArchive.kt`, replace:

```kotlin
    suspend fun purge(oldest: Long, newest: Long, index: IndexWrite): Int
```

with:

```kotlin
    suspend fun purge(oldest: Long, newest: Long, index: IndexWrite): Int

    /**
     * "Delete all past conversations" (design 9.5 of sub-project 7; plan 7F), in one transaction: deletes every closed row
     * whose id is not in [keep], whatever the index lists, those that this version cannot read too, and changes the index's
     * shards as [index] says; returns how many rows went. Unlike [deleteAll], it keeps the open row, the logs and the
     * archive's key.
     */
    suspend fun deleteClosed(keep: Collection<String>, index: IndexWrite): Int
```

In `core/conversation/src/testFixtures/kotlin/dev/kestrel/conversation/testing/MemoryArchive.kt`, replace:

```kotlin
    override suspend fun deleteAll(): ArchiveErasure = changing("deleteAll", IndexWrite.Replace(emptyMap())) {
```

with:

```kotlin
    override suspend fun deleteClosed(keep: Collection<String>, index: IndexWrite): Int = changing("deleteClosed", index) {
        val gone = rows.values.filter { !it.header.open && it.header.id !in keep }.map { it.header.id }
        gone.forEach(::remove)
        gone.size
    }

    override suspend fun deleteAll(): ArchiveErasure = changing("deleteAll", IndexWrite.Replace(emptyMap())) {
```

In `core/privacy/src/main/kotlin/dev/kestrel/privacy/db/ConversationRow.kt`, replace:

```kotlin
    @Transaction
    open fun purge(oldest: Long, newest: Long, index: IndexRowUpdate): Int = deleteClosedOutside(oldest, newest).also { change(index) }
```

with:

```kotlin
    @Transaction
    open fun purge(oldest: Long, newest: Long, index: IndexRowUpdate): Int = deleteClosedOutside(oldest, newest).also { change(index) }

    /**
     * "Delete all past conversations" (design 9.5 of sub-project 7; plan 7F), in one transaction: deletes every closed row
     * whose id is not in [keep] and changes the shards' rows as [index] says; returns how many rows went. The open row and
     * the logs stay.
     */
    @Transaction
    open fun deleteClosed(keep: List<String>, index: IndexRowUpdate): Int = deleteClosedExcept(keep).also { change(index) }
```

In `core/privacy/src/main/kotlin/dev/kestrel/privacy/db/ConversationRow.kt`, replace:

```kotlin
    @Query("DELETE FROM conversations WHERE open = 0 AND (last_millis < :oldest OR last_millis > :newest)")
    protected abstract fun deleteClosedOutside(oldest: Long, newest: Long): Int
```

with:

```kotlin
    @Query("DELETE FROM conversations WHERE open = 0 AND (last_millis < :oldest OR last_millis > :newest)")
    protected abstract fun deleteClosedOutside(oldest: Long, newest: Long): Int

    // Room writes one parameter per id of [keep]; SQLite takes an empty list too, which keeps nothing.
    @Query("DELETE FROM conversations WHERE open = 0 AND id NOT IN (:keep)")
    protected abstract fun deleteClosedExcept(keep: List<String>): Int
```

In `core/privacy/src/main/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStore.kt`, replace:

```kotlin
    suspend fun purge(oldest: Long, newest: Long, index: IndexUpdate): Int =
        key.storing(dispatcher, dao::isEmpty) { dao.purge(oldest, newest, encrypted(index)) }
```

with:

```kotlin
    suspend fun purge(oldest: Long, newest: Long, index: IndexUpdate): Int =
        key.storing(dispatcher, dao::isEmpty) { dao.purge(oldest, newest, encrypted(index)) }

    /**
     * "Delete all past conversations" (design 9.5 of sub-project 7; plan 7F), in one transaction: deletes every closed
     * conversation whose id is not in [keep], and changes the index as [index] says; returns how many conversations went.
     * Unlike [deleteAll], it keeps the open conversation, the logs and the key, under which what stays is still read.
     */
    suspend fun deleteClosed(keep: Collection<String>, index: IndexUpdate): Int =
        key.storing(dispatcher, dao::isEmpty) { dao.deleteClosed(keep.toList(), encrypted(index)) }
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/RoomRecordArchive.kt`, replace:

```kotlin
    override suspend fun purge(oldest: Long, newest: Long, index: IndexWrite): Int = table.purge(oldest, newest, index.plain())
```

with:

```kotlin
    override suspend fun purge(oldest: Long, newest: Long, index: IndexWrite): Int = table.purge(oldest, newest, index.plain())

    // "Delete all past conversations" (plan 7F): the conversations alone, not the logs, and the key stays.
    override suspend fun deleteClosed(keep: Collection<String>, index: IndexWrite): Int = table.deleteClosed(keep, index.plain())
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/store/ArchivedConversationStore.kt`, replace:

```kotlin
    override suspend fun purge(retentionDays: Int, nowWall: Long): Int = mutex.withLock {
```

with:

```kotlin
    // Under the lock, so that no write comes between: the archive deletes every closed row that [keep] does not name, those
    // that the index does not name too, in one transaction with the index without the conversations and kept rows that
    // went, which writes only the shards that changed. The generation rises first, as for "delete history", so that a
    // search that this overtook gives out nothing; the conversations that went are marked deleted once the archive's call
    // completed, so that no holder writes one back.
    override suspend fun deleteClosed(keep: () -> Set<ConversationId>): Int = withIndex { index ->
        generation++
        val kept = keep().mapTo(HashSet()) { it.value }
        val gone = index.conversations.map { it.id }.filter { it.value !in kept }
        val goneRows = gone.mapTo(HashSet()) { it.value } + index.kept.map { it.id }.filter { it !in kept }
        changed(update = { it.without(goneRows) }, touched = emptySet(), inMemory = { deleted += gone }) { write ->
            archive.deleteClosed(kept, write)
        }
    }

    override suspend fun purge(retentionDays: Int, nowWall: Long): Int = mutex.withLock {
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/store/HistorySearch.kt`, replace:

```kotlin
/**
 * The on-device search over past conversations (design 9.4 of sub-project 7), behind plan 7A's `search_conversations`, and
```

with:

```kotlin
/**
 * What the list's search field keeps (design 9.5 of sub-project 7; plan 7F): every past conversation, because the text holds
 * no word that counts; those that hold a match; or the mark that the index is being rebuilt, or cannot be read now.
 */
sealed interface PastMatches {
    data object All : PastMatches

    class Only(val ids: Set<ConversationId>) : PastMatches {
        override fun toString(): String = "Only(${ids.size})"
    }

    data object Rebuilding : PastMatches

    data object Failed : PastMatches
}

/**
 * The on-device search over past conversations (design 9.4 of sub-project 7), behind plan 7A's `search_conversations`, and
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/store/HistorySearch.kt`, replace:

```kotlin
    // The closed conversations within retention, the newest change first.
```

with:

```kotlin
    /**
     * The past conversations that hold a match for [text] by the search's own rule (design 9.5 of sub-project 7; plan 7F),
     * from the index's shards alone, so that no body is read. It names every past conversation that matches; the list itself
     * keeps those within retention (plan 7F's `PastList`). Each call ranks the whole index, so the list calls it once the
     * owner's typing pauses, off the main thread.
     */
    suspend fun matching(text: String): PastMatches {
        if (HistoryRanking.wanted(text).isEmpty()) return PastMatches.All
        val generation = store.generation
        val searchable = when (val read = store.searchable()) {
            is Searchable.Ready -> read
            Searchable.Rebuilding -> return PastMatches.Rebuilding
            Searchable.Failed -> return PastMatches.Failed
        }
        val ids = HistoryRanking.rank(searchable.past.searches, HistoryQuery(text), clock.zone()).mapTo(HashSet()) { it.entry.ref.conversation }
        return PastMatches.Only(if (store.generation == generation) ids else emptySet())
    }

    // The closed conversations within retention, the newest change first.
```
- [ ] **Step 4: The engine deletes past conversations and takes its timers' lengths through a seam**

Create `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/PastDeletion.kt`:

```kotlin
package dev.kestrel.conversation.engine

/** How a deletion of a past conversation ended (design 9.5 of sub-project 7; plan 7F). */
enum class PastDeletion {
    DELETED,

    /** The conversation is the open one now, as after the owner continued it, so it stays. */
    OPEN,

    /** The index of past conversations cannot be read now, so nothing was deleted; the store tries again by itself later. */
    UNAVAILABLE,

    /** The archive failed; the engine reported the failure by its class. */
    FAILED,
}

/**
 * "Delete all past conversations" as it stands in this process (design 9.5 of sub-project 7): none has run, one runs, or
 * how the last one ended. The engine holds it, so that the settings page shows a running deletion also after the owner
 * left the page and came back, and offers no second one meanwhile (review B-M4 of plan 7F).
 */
sealed interface AllPastDeletion {
    data object Idle : AllPastDeletion

    data object Running : AllPastDeletion

    data class Ended(val outcome: PastDeletion) : AllPastDeletion
}
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
import kotlinx.coroutines.CoroutineStart
```

with:

```kotlin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
    private val onStep: (ConversationEvent, ConversationState, MachineStep) -> Unit = { _, _, _ -> },
) {
```

with:

```kotlin
    private val onStep: (ConversationEvent, ConversationState, MachineStep) -> Unit = { _, _, _ -> },
    private val timerMillis: (Timer, Long) -> Long = { _, millis -> millis },
) {
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
        class Reopened(val snapshot: ConversationSnapshot?, val chosen: ConversationSnapshot?, val closing: ConversationSnapshot?, val failed: Boolean) : Message
```

with:

```kotlin
        class Reopened(val snapshot: ConversationSnapshot?, val chosen: ConversationSnapshot?, val closing: ConversationSnapshot?, val failed: Boolean) : Message

        /** The deletion of the past conversation [id] (plan 7F); [done] gets how it ended. */
        class DeletePast(val id: ConversationId, val done: CompletableDeferred<PastDeletion>) : Message
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
        class Reopen(val id: ConversationId, val closing: ConversationSnapshot?, val nowWall: Long, val testMode: Boolean) : Write
```

with:

```kotlin
        class Reopen(val id: ConversationId, val closing: ConversationSnapshot?, val nowWall: Long, val testMode: Boolean) : Write

        /** Deletes the past conversation [id] (plan 7F); [done] gets how it ended. */
        class DeletePast(val id: ConversationId, val done: CompletableDeferred<PastDeletion>) : Write
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
    private var switching = false
```

with:

```kotlin
    private var switching = false

    // The past conversation that the running switch opens, which no deletion may take (plan 7F).
    private var switchingTo: ConversationId? = null

    // The keep-sets of the pending "Delete all" requests (plan 7F, review M1): the loop adds each step's open conversation to
    // every one of them, and the request's coroutine and the store read them, under this list's lock.
    private val keeping = mutableListOf<MutableSet<ConversationId>>()

    // "Delete all" as it stands, and the one that runs, under [allPast]'s lock (review B-M4 of plan 7F).
    private val allPast = MutableStateFlow<AllPastDeletion>(AllPastDeletion.Idle)
    private var allPastRun: Deferred<PastDeletion>? = null
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
        onStep(event, before, step)
        state = step.state
```

with:

```kotlin
        onStep(event, before, step)
        state = step.state
        // Before any write of this step is queued: a pending "Delete all" keeps every conversation that is open meanwhile.
        step.state.id?.let { id -> synchronized(keeping) { keeping.forEach { it += id } } }
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
        switching = true
```

with:

```kotlin
        switching = true
        switchingTo = id
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
    private fun reopened(message: Message.Reopened) {
        switching = false
```

with:

```kotlin
    private fun reopened(message: Message.Reopened) {
        switching = false
        switchingTo = null
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
    /** How command [command] ended, among the newest [MAX_OUTCOMES]. */
    fun outcome(command: Int): CommandOutcome? = synchronized(outcomes) { outcomes[command] }
```

with:

```kotlin
    /**
     * Deletes the past conversation [id] (design 9.5 of sub-project 7; plan 7F): the loop checks it between the machine's
     * steps, and the writer deletes it after the writes that the loop gave it before, so that no save or switch of the
     * loop's comes between: [PastDeletion.OPEN] when [id] is the open conversation now, or the one that a running switch
     * opens, which stays. Waits for [restore].
     */
    suspend fun deletePast(id: ConversationId): PastDeletion {
        val done = CompletableDeferred<PastDeletion>()
        inbox.send(Message.DeletePast(id, done))
        return done.await()
    }

    /**
     * Deletes every past conversation (design 9.5; plan 7F), but none that is open at some moment between this call and
     * the deletion's transaction: the one that is open now, and each that the owner starts or continues meanwhile, also
     * when it closes before the deletion runs (review M1). The loop adds each to this call's keep-set ([keeping]) before it
     * queues any write of it, and the store reads the set under its lock. The deletion waits at the writer's barrier until
     * every write that the loop queued before it is stored, so that a conversation that closed before the owner asked is
     * deleted too, and then runs beside the inbox and the writer, never in them: the store may wait minutes for its index
     * to be read or rebuilt, and the conversation goes on meanwhile (review N2). It runs in the engine's scope, so that a
     * caller that goes away stops nothing, and the outcome comes back to the caller once it is done. While one runs, a
     * second call deletes nothing again: it waits for the running one and gets its outcome; [allPastDeletion] says which
     * stands (review B-M4).
     */
    suspend fun deleteAllPast(): PastDeletion = synchronized(allPast) {
        allPastRun?.takeIf { it.isActive } ?: run {
            allPast.value = AllPastDeletion.Running
            scope.async {
                val keep = HashSet<ConversationId>()
                synchronized(keeping) {
                    state.id?.let(keep::add)
                    keeping.add(keep)
                }
                val outcome = try {
                    stored()
                    deleteAllPastNow { synchronized(keeping) { keep.toSet() } }
                } finally {
                    synchronized(keeping) { keeping.removeAll { it === keep } }
                }
                allPast.value = AllPastDeletion.Ended(outcome)
                outcome
            }.also { allPastRun = it }
        }
    }.await()

    /** "Delete all past conversations" as it stands in this process: none yet, running, or how the last one ended. */
    val allPastDeletion: StateFlow<AllPastDeletion> = allPast.asStateFlow()

    /** How command [command] ended, among the newest [MAX_OUTCOMES]. */
    fun outcome(command: Int): CommandOutcome? = synchronized(outcomes) { outcomes[command] }
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
                is Message.Reopened -> reopened(message)
```

with:

```kotlin
                is Message.Reopened -> reopened(message)
                is Message.DeletePast -> deletion(message)
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
                is Message.HistoryDeletionEnded -> deletionEnded(message)
                else -> Unit
```

with:

```kotlin
                is Message.HistoryDeletionEnded -> deletionEnded(message)
                is Message.DeletePast -> message.done.complete(PastDeletion.FAILED)
                else -> Unit
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
    // D23: the writer closes the open conversation into the list and opens the chosen one, in one transaction of the store.
```

with:

```kotlin
    // Design 9.5 of sub-project 7: the open conversation is never a past one, nor is the one that a running switch opens.
    // The writer deletes the others after the writes that the loop gave it before, so that no save or switch comes after;
    // while the settings page's "Delete history" runs, the deletion waits with the other writes ([send]).
    private fun deletion(message: Message.DeletePast) {
        if (message.id == state.id || message.id == switchingTo) {
            message.done.complete(PastDeletion.OPEN)
        } else {
            send(Write.DeletePast(message.id, message.done))
        }
    }

    // While the index of past conversations cannot be read, the store deletes nothing and says so; it is no failure. A failure
    // of the archive is reported by its class.
    private suspend fun deleteAllPastNow(keep: () -> Set<ConversationId>): PastDeletion = try {
        store.deleteClosed(keep)
        PastDeletion.DELETED
    } catch (e: PastUnavailableException) {
        PastDeletion.UNAVAILABLE
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        report("Deleting past conversations", e)
        PastDeletion.FAILED
    }

    // D23: the writer closes the open conversation into the list and opens the chosen one, in one transaction of the store.
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
                inbox.trySend(Message.Reopened(opened, chosen, write.closing, failed = false))
            }
```

with:

```kotlin
                inbox.trySend(Message.Reopened(opened, chosen, write.closing, failed = false))
            }
            is Write.DeletePast -> write.done.complete(
                try {
                    store.delete(write.id)
                    PastDeletion.DELETED
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    report("Deleting a past conversation", e)
                    PastDeletion.FAILED
                },
            )
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
```

with:

```kotlin
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
import dev.kestrel.conversation.store.ConversationStore
import dev.kestrel.safety.Category
```

with:

```kotlin
import dev.kestrel.conversation.store.ConversationStore
import dev.kestrel.conversation.store.PastUnavailableException
import dev.kestrel.safety.Category
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
        val at = clock.sinceBootMillis() + effect.millis
```

with:

```kotlin
        // The device tests' seam shortens the deadline itself: a shorter wake-up alone would end nothing sooner.
        val at = clock.sinceBootMillis() + timerMillis(effect.timer, effect.millis)
```

- [ ] **Step 5: The device tests' seam for the timers**

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
    @Volatile
    internal var conversationSteps: ((ConversationEvent, ConversationState, MachineStep) -> Unit)? = null
```

with:

```kotlin
    @Volatile
    internal var conversationSteps: ((ConversationEvent, ConversationState, MachineStep) -> Unit)? = null

    /**
     * Device tests only: the conversation's timers' lengths, such as D21's waits shortened by an instrumentation argument
     * (design 15.2 of sub-project 7; plan 7F). Null, and so the machine's own lengths, outside those tests.
     */
    @Volatile
    internal var conversationTimers: ((Timer, Long) -> Long)? = null
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
            onStep = { event, state, step -> conversationSteps?.invoke(event, state, step) },
```

with:

```kotlin
            onStep = { event, state, step -> conversationSteps?.invoke(event, state, step) },
            timerMillis = { timer, millis -> conversationTimers?.invoke(timer, millis) ?: millis },
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
import dev.kestrel.conversation.machine.MachineStep
```

with:

```kotlin
import dev.kestrel.conversation.machine.MachineStep
import dev.kestrel.conversation.machine.Timer
```

- [ ] **Step 6: Run the tests and the builds**

Run: `scripts\gradle.ps1 :core:conversation:test --tests=dev.kestrel.conversation.engine.ConversationEngineTest --tests=dev.kestrel.conversation.machine.GoalRulesTest --tests=dev.kestrel.conversation.store.*`
Expected: BUILD SUCCESSFUL; the new tests pass.

Run: `scripts\gradle.ps1 :core:privacy:testDebugUnitTest --tests=dev.kestrel.privacy.conversations.ConversationArchiveStoreTest`
Expected: BUILD SUCCESSFUL; the new test passes.

Run: `scripts\gradle.ps1 :core:conversation:test :core:privacy:testDebugUnitTest :app:testDebugUnitTest :core:privacy:assembleDebugAndroidTest :app:assembleDebugAndroidTest; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL; `:core:conversation` C₁ + 8, `:core:privacy` JVM P₀ + 1, `:app` JVM unchanged. The device tests compile; `KestrelDatabaseTest` and `RoomRecordArchiveTest` run in Task 14.

- [ ] **Step 7: Commit**

```bash
git add core/conversation core/privacy/src/main/kotlin/dev/kestrel/privacy/db/ConversationRow.kt core/privacy/src/main/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStore.kt core/privacy/src/test/kotlin/dev/kestrel/privacy/conversations/ConversationArchiveStoreTest.kt core/privacy/src/androidTest/kotlin/dev/kestrel/privacy/KestrelDatabaseTest.kt app/src/main/kotlin/dev/kestrel/app/conversation/RoomRecordArchive.kt app/src/androidTest/kotlin/dev/kestrel/app/conversation/RoomRecordArchiveTest.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt
git commit -m "feat(conversation, privacy): the engine deletes a past conversation or all of them but never the open one, through the archive's own deletion of the closed rows, which keeps the logs and the key; the list searches the index, and the engine takes its timers' lengths through a seam" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: The typed reading and the text channel

Design 10, 11 and 12.2 of sub-project 7: `TextChannel` is the conversation's `OwnerChannel` for typed words and cards. Kestrel's words and final replies go to the bar (the transcript draws them from the records), and nothing is spoken here. Every question and hand-over asks through Task 1's card desk, which shares the card with the voice's copy of the question, shows it on the overlay while the conversation screen is not in front, and gives it to the screen while it is. "Reply…" on the overlay holds the question for 5 minutes (D21) and opens the conversation screen with the question in focus.

A button on the screen's copy of a card is the owner's input, as his typed words are (design 10.3 and D5): `choose` gives the conversation `InputIntent.Answer` with the card's question number (Task A2) through `submit`, so that a tap that comes late answers no later question, and the machine notes that the owner answered on Kestrel's own screen and the goal looks afresh before it acts. Only the overlay answers a confirmation, a hand-over and a pick: a confirmation acts on the app at once and must never be answered over Kestrel's own screen (design 12.2 and 12.3), a hand-over is the owner's step in the app, and a pick names a number on the app's screen. A tap that belongs to no open card does nothing and is never kept for a later card. The owner's typed words become inputs only through this channel: `TypedInputs` reads them as design 12.2 says, with plan 7D's typed reading (`Requests.typed`: only the exact name counts, and the requests that only the voice reads, such as "never mind", stay the owner's words), and an answer names the number of the question that it answers, as the voice's answers do (plan 7D), so that typed words that arrive late answer no later question; every input that the screen gives the conversation goes through `submit`, which tells the screen of it. Every input of the screen's takes its number among the owner's inputs as it arrives (`InputIds.next` in `input`), so that plan 7D's `ScreenTurns` treats it as the owner's newest act. Where the owner answered belongs to the input's arrival (`AnswerQuestion` with the channel, plan 7B), never to the answer's words. A tap on the card over the app comes back from the desk with its own number (Task 1's `CardTap`), which `ask` gives the conversation with the answer (`CardAnswer`). The channel's words reach the bar through plan 7D's `OwnedBar`, the bar's one writer (Task 5). Both channels wait for Kestrel's own screen to leave through one function, `awaitKestrelLeft`. The app graph wires the channel in Task 8.

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/conversation/TypedInputs.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/TextChannel.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/KestrelScreen.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/OwnerChannel.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/ConversationOwner.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/ConversationTexts.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/ConversationMachine.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/model/OwnerInput.kt`, `core/conversation/src/testFixtures/kotlin/dev/kestrel/conversation/testing/FakeChannel.kt`
- Test: create `app/src/test/kotlin/dev/kestrel/app/conversation/TypedInputsTest.kt`, `app/src/test/kotlin/dev/kestrel/app/conversation/TextChannelTest.kt`; modify `core/conversation/src/test/kotlin/dev/kestrel/conversation/channel/ConversationOwnerTest.kt`, `core/conversation/src/test/kotlin/dev/kestrel/conversation/machine/ConversationMachineTest.kt`

**Interfaces:**
- Consumes: Task 1's `OwnerCard`, `CardChoice`, `CardTap`, `CardAnswer`, `CardDesk` (`ask`, `open`, `screenInFront`) and `OwnerScreen`; plan 7D's `OwnedBar`, `HandOverSignals`, `PermissionDialog`, `InputIds.next()`, `Requests.typed(text)`, `Request.Other`, `VoiceAnswers.read(kind, text, named, request)`; plan 7B's `ConversationView` (`asking`, `question`), `OwnerChannel`, `ChannelOutput`, `ChannelQuestion`, `QuestionKind`, `ChannelAnswer` (`Text(text)` since `693f3dde`), `OwnerInput`, `InputIntent` (`Answer(answer, question)` since Task A2), `InputChannel`; plan 7A's `OwnerPort.KESTREL_LEAVE_MILLIS`, `HandOverEnd`, `PermissionEnd`.
- Produces: `internal object TypedInputs { fun read(text: String, view: ConversationView): InputIntent? }`; `internal interface TypedConversation { fun view(): ConversationView; fun submit(input: OwnerInput); fun replyOpened(question: Int); fun composing(active: Boolean) }`; `internal fun interface ReplyScreen { fun open(question: Int) }`; `TextChannel.handOver` returns `HandOverAnswer` with the tap's number; `enum class HandOverPart { OWN, CARD }` and `OwnerChannel.handOver(handOver, part: HandOverPart = OWN)`, where `CARD` asks a channel for only the hand-over's card beside the channel that owns it (review A-I1), and `FakeChannel.handOverParts`; `ConversationTexts.ANSWER_ENDED`, which the machine shows for a typed answer whose question had ended (review A-M3); `internal class TextChannel(bar: OwnedBar, screen: OwnerScreen, cards: SharedCards, handOvers: HandOverSignals, permissions: PermissionDialog, replies: ReplyScreen, conversation: TypedConversation, clock: () -> Long = System::currentTimeMillis) : OwnerChannel` with `sent: SharedFlow<InputIntent>`, `choose(key: Int, choice: CardChoice)`, `read(text: String): OwnerInput?`, `input(intent: InputIntent): OwnerInput`, `submit(input: OwnerInput)`, `composing(active: Boolean)`; `internal suspend fun OwnerScreen.awaitKestrelLeft(): Boolean` and `KESTREL_POLL_MILLIS` (moved from `VoiceChannel`).

Counts: `:app` JVM A₂ + 14 (`TypedInputsTest` 4, `TextChannelTest` 10), where A₂ is the count after Task 2 (A₂ = A₁: Task 2 has no steps). `:core:conversation` C₃ + 2 (`ConversationOwnerTest` + 1, `ConversationMachineTest` + 1), where C₃ = C₀ + 9 is the count after Task 3.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/dev/kestrel/app/conversation/TypedInputsTest.kt`:

```kotlin
package dev.kestrel.app.conversation

import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.InputIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The owner's typed words on Kestrel's own screen (design 12.2 of sub-project 7). */
class TypedInputsTest {
    private val idle = ConversationView()

    // The open question of the conversation's view, number 3.
    private fun asking(kind: QuestionKind) = ConversationView(asking = kind, question = 3)

    @Test
    fun withoutAQuestionKestrelsOwnRequestsAreReadWithoutTheNameAndTheRestIsACommand() {
        assertEquals(InputIntent.Command("Read my latest SMS"), TypedInputs.read("  Read my latest SMS ", idle))
        assertEquals(InputIntent.NewConversation, TypedInputs.read("new conversation", idle))
        assertEquals(InputIntent.DeleteHistory, TypedInputs.read("delete history", idle))
        assertEquals(InputIntent.Continue, TypedInputs.read("continue", idle))
        assertEquals(InputIntent.Stop, TypedInputs.read("stop", idle))
        assertNull(TypedInputs.read("   ", idle))
    }

    // Plan 7D's typed reading: only the exact name counts, and the voice's own requests stay the owner's words.
    @Test
    fun onlyTheExactNameCountsAndTheVoicesOwnRequestsStayTheOwnersWords() {
        assertEquals(InputIntent.Command("read my latest SMS"), TypedInputs.read("Kestrel, read my latest SMS", idle))
        assertEquals(InputIntent.Command("Kestral, open Viber"), TypedInputs.read("Kestral, open Viber", idle))
        assertEquals(InputIntent.Command("never mind"), TypedInputs.read("never mind", idle))
        assertEquals(InputIntent.Command("forget it"), TypedInputs.read("forget it", idle))
        assertEquals(InputIntent.Command("turn off"), TypedInputs.read("turn off", idle))
        assertEquals(InputIntent.Stop, TypedInputs.read("Stop it, Kestrel", idle))
    }

    // Design D2 of plan 7D: an answer names the question that it answers, so that it never answers a later one.
    @Test
    fun whileAQuestionWaitsTheWordsAnswerItAsTheVoiceReadsThemAndNameIt() {
        assertEquals(InputIntent.Answer(ChannelAnswer.Yes, question = 3), TypedInputs.read("Yes", asking(QuestionKind.YES_NO)))
        assertEquals(InputIntent.Answer(ChannelAnswer.No, question = 3), TypedInputs.read("no", asking(QuestionKind.DELETE_HISTORY)))
        assertEquals(InputIntent.Answer(ChannelAnswer.Text("Mila Petrović"), question = 3), TypedInputs.read("Mila Petrović", asking(QuestionKind.FREE)))
        assertEquals(InputIntent.Answer(ChannelAnswer.Text("nothing"), question = 3), TypedInputs.read("nothing", asking(QuestionKind.FREE)))
        assertEquals(InputIntent.Answer(ChannelAnswer.Pick(2), question = 3), TypedInputs.read("2", asking(QuestionKind.PICK)))
        assertEquals(InputIntent.Answer(ChannelAnswer.Yes, question = 3), TypedInputs.read("continue", asking(QuestionKind.CONTINUE_GOAL)))
        // "Stop" and "cancel" stop the goal also while it asks.
        assertEquals(InputIntent.Stop, TypedInputs.read("cancel", asking(QuestionKind.FREE)))
    }

    @Test
    fun aConfirmationIsNeverAnsweredByTypedWords() {
        // A confirmation is always a button (design 10.3): the words reach it as a command, which counts as no.
        assertEquals(InputIntent.Command("yes"), TypedInputs.read("yes", asking(QuestionKind.CONFIRM)))
        assertEquals(InputIntent.Command("confirm"), TypedInputs.read("confirm", asking(QuestionKind.CONFIRM)))
    }
}
```

Create `app/src/test/kotlin/dev/kestrel/app/conversation/TextChannelTest.kt`:

```kotlin
package dev.kestrel.app.conversation

import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.app.listen.FeedbackBar
import dev.kestrel.app.listen.OwnedBar
import dev.kestrel.conversation.channel.ChannelOutput
import dev.kestrel.conversation.channel.ChannelQuestion
import dev.kestrel.conversation.channel.HandOverAnswer
import dev.kestrel.conversation.channel.HandOverPart
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput
import dev.kestrel.safety.HandOver
import dev.kestrel.safety.HandOverKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The conversation's text channel (design 10, 11 and 12.2 of sub-project 7). */
@OptIn(ExperimentalCoroutinesApi::class)
class TextChannelTest {
    private val support = FakeOwnerParts()
    private val conversation = FakeConversation()
    private val opened = mutableListOf<Int>()
    private lateinit var desk: CardDesk

    private fun TestScope.channel(): TextChannel {
        desk = CardDesk(support, backgroundScope)
        return TextChannel(OwnedBar(support), support, desk, support, support, ReplyScreen { opened += it }, conversation) { 1_000L }
    }

    @Test
    fun kestrelsWordsGoToTheBarAndNothingIsSpoken() = runTest {
        val channel = channel()
        channel.show(ChannelOutput.Said("On it."))
        channel.show(ChannelOutput.Shown("Got it."))
        channel.show(ChannelOutput.Report(2, "Done."))

        assertEquals(listOf("On it.", "Got it.", "Done."), support.bar)
    }

    @Test
    fun aQuestionShowsItsCardOnTheOverlayAndItsButtonAnswersIt() = runTest {
        val channel = channel()
        val asking = async { channel.ask(ChannelQuestion(3, "Which account?", QuestionKind.YES_NO)) }
        runCurrent()
        val card = OwnerCard(3, "Which account?", listOf(CardChoice.Yes, CardChoice.No, CardChoice.Reply), question = 3, kind = QuestionKind.YES_NO)
        assertEquals(listOf(card), support.cards)
        assertEquals(card, desk.open.value)

        support.tap(CardChoice.No)

        assertEquals(ChannelAnswer.No, asking.await().answer)
        runCurrent()
        assertNull(desk.open.value)
    }

    // Design 10.3 and D5: a button on Kestrel's own screen is the owner's input, so the goal looks afresh after it.
    @Test
    fun whileTheScreenIsInFrontTheOverlayGoesAndAScreenButtonIsTheOwnersInput() = runTest {
        val channel = channel()
        val asking = async { channel.ask(ChannelQuestion(4, "Send it?", QuestionKind.YES_NO)) }
        runCurrent()

        desk.screenInFront(true)
        runCurrent()
        assertEquals(1, support.withdrawn)
        assertEquals(4, desk.open.value?.key)
        // A tap on another card's copy, or a choice that the card does not have, changes nothing.
        channel.choose(3, CardChoice.No)
        channel.choose(4, CardChoice.Confirm)
        channel.choose(4, CardChoice.Yes)

        assertEquals(listOf(InputIntent.Answer(ChannelAnswer.Yes, question = 4)), conversation.submitted.map { it.intent })
        assertEquals(InputChannel.TEXT, conversation.submitted.single().channel)
        // The conversation's answer withdraws the question; the screen's tap is no card's answer.
        assertFalse(asking.isCompleted)
        asking.cancel()
    }

    // Design 12.2 and 12.3: only the card over the app answers a confirmation, never Kestrel's own screen.
    @Test
    fun aConfirmationIsAnsweredOnlyOnTheOverlay() = runTest {
        val channel = channel()
        val asking = async { channel.ask(ChannelQuestion(5, "Send it to Mila?", QuestionKind.CONFIRM)) }
        runCurrent()
        desk.screenInFront(true)
        runCurrent()

        channel.choose(5, CardChoice.Confirm)
        assertTrue(conversation.submitted.isEmpty())

        desk.screenInFront(false)
        runCurrent()
        assertEquals(2, support.cards.size)
        support.tap(CardChoice.Confirm)
        assertEquals(ChannelAnswer.Yes, asking.await().answer)
    }

    @Test
    fun replyOnTheOverlayHoldsTheQuestionAndOpensTheScreenForTheOwnersWords() = runTest {
        val channel = channel()
        val asking = async { channel.ask(ChannelQuestion(6, "Which time?", QuestionKind.FREE)) }
        runCurrent()

        support.tap(CardChoice.Reply)
        runCurrent()
        assertEquals(listOf(6), conversation.replies)
        assertEquals(listOf(6), opened)
        desk.screenInFront(true)
        runCurrent()
        assertEquals(1, support.cards.size)
        assertEquals(6, desk.open.value?.question)

        // The typed answer reaches the conversation as an input, which then withdraws the question.
        conversation.view = ConversationView(asking = QuestionKind.FREE, question = 6)
        channel.submit(channel.read("Half past seven")!!)
        assertEquals(InputIntent.Answer(ChannelAnswer.Text("Half past seven"), question = 6), conversation.submitted.single().intent)
        asking.cancel()
        runCurrent()
        assertNull(desk.open.value)
    }

    @Test
    fun aHandOverShowsCancelAndContinueAndEndsByItselfForItsKind() = runTest {
        val channel = channel()
        val handing = async { channel.handOver(HandOver.of(HandOverKind.ACCESS)) }
        runCurrent()
        assertEquals(listOf(HandOverKind.ACCESS), support.opened)
        assertEquals(listOf(CardChoice.Cancel, CardChoice.Continue), support.cards.single().choices)
        assertTrue(support.cards.single().key < 0)
        assertNull(support.cards.single().question)

        support.endedByItself.complete(Unit)
        assertEquals(HandOverAnswer(HandOverEnd.DONE_BY_ITSELF, act = null), handing.await())

        val other = async { channel.handOver(HandOver.of(HandOverKind.PERMISSION)) }
        runCurrent()
        support.tap(CardChoice.Cancel)
        // The tap on Cancel carries its number, which the desk took at the tap.
        val cancelled = other.await()
        assertEquals(HandOverEnd.CANCELLED, cancelled.end)
        assertTrue(cancelled.act != null)
        assertFalse(support.cards[0].key == support.cards[1].key)
    }

    // Plan 7F: as the copy of a spoken conversation's hand-over, the card neither opens the hand-over's page nor waits for
    // its end by itself: the voice owns the hand-over, which so opens its page once, also when Kestrel turns off during it.
    @Test
    fun aHandOversCardBesideTheVoiceOpensNothing() = runTest {
        val channel = channel()
        val handing = async { channel.handOver(HandOver.of(HandOverKind.ACCESS), HandOverPart.CARD) }
        runCurrent()
        assertTrue(support.opened.isEmpty())
        support.endedByItself.complete(Unit)
        runCurrent()
        assertFalse(handing.isCompleted)

        support.tap(CardChoice.Continue)
        assertEquals(HandOverEnd.CONTINUED, handing.await().end)
    }

    @Test
    fun typedWordsBecomeTextInputsThatTheScreenHearsOf() = runTest {
        val channel = channel()
        val sent = mutableListOf<InputIntent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { channel.sent.collect { sent += it } }
        conversation.view = ConversationView(asking = QuestionKind.YES_NO, question = 2)

        channel.submit(channel.read("yes")!!)
        channel.submit(channel.input(InputIntent.Stop))

        assertEquals(listOf(InputChannel.TEXT, InputChannel.TEXT), conversation.submitted.map { it.channel })
        assertEquals(listOf(1_000L, 1_000L), conversation.submitted.map { it.atMillis })
        assertTrue(conversation.submitted[0].id < conversation.submitted[1].id)
        assertEquals(listOf(InputIntent.Answer(ChannelAnswer.Yes, question = 2), InputIntent.Stop), sent)
        assertNull(channel.read("  "))
        channel.composing(true)
        assertEquals(listOf(true), conversation.composings)
    }

    @Test
    fun aPermissionSaysWhyOnTheBarAndShowsAndroidsDialog() = runTest {
        val channel = channel()
        val end = channel.askPermission(listOf("android.permission.READ_CONTACTS"), "I need your contacts to find Mila.")

        assertEquals(PermissionEnd.GRANTED, end)
        assertEquals(listOf("I need your contacts to find Mila."), support.bar)
        assertEquals(listOf(listOf("android.permission.READ_CONTACTS")), support.asked)
    }

    @Test
    fun leavingKestrelsOwnScreenWaitsAtMostThreeSeconds() = runTest {
        val channel = channel()
        support.kestrelPolls = 2
        assertTrue(channel.leaveKestrel())

        support.kestrelPolls = Int.MAX_VALUE
        val start = currentTime
        assertFalse(channel.leaveKestrel())
        assertEquals(OwnerPort.KESTREL_LEAVE_MILLIS, currentTime - start)
    }

    private class FakeConversation : TypedConversation {
        var view = ConversationView()
        val submitted = mutableListOf<OwnerInput>()
        val replies = mutableListOf<Int>()
        val composings = mutableListOf<Boolean>()

        override fun view(): ConversationView = view

        override fun submit(input: OwnerInput) {
            submitted += input
        }

        override fun replyOpened(question: Int) {
            replies += question
        }

        override fun composing(active: Boolean) {
            composings += active
        }
    }

    private class FakeOwnerParts : FeedbackBar, OwnerScreen, HandOverSignals, PermissionDialog {
        override val connections = MutableStateFlow(1)
        val cards = mutableListOf<OwnerCard>()
        val bar = mutableListOf<String>()
        var withdrawn = 0
        var kestrelPolls = 0
        val opened = mutableListOf<HandOverKind>()
        val endedByItself = CompletableDeferred<Unit>()
        val asked = mutableListOf<List<String>>()
        private var answer = CompletableDeferred<CardChoice?>()

        fun tap(choice: CardChoice) {
            answer.complete(choice)
        }

        override fun show(text: String, keep: Boolean) {
            bar += text
        }

        override fun release() {}

        override suspend fun card(card: OwnerCard): CardChoice? {
            cards += card
            answer = CompletableDeferred()
            try {
                return answer.await()
            } catch (e: CancellationException) {
                withdrawn++
                throw e
            }
        }

        override fun locked(): Boolean = false

        override suspend fun releaseBar() {}

        override suspend fun kestrelInFront(): Boolean = (kestrelPolls > 0).also { if (it) kestrelPolls-- }

        override suspend fun opened(kind: HandOverKind) {
            opened += kind
        }

        override suspend fun endedByItself(kind: HandOverKind) {
            if (kind == HandOverKind.ACCESS) endedByItself.await() else awaitCancellation()
        }

        override suspend fun ask(permissions: List<String>): PermissionEnd {
            asked += permissions
            return PermissionEnd.GRANTED
        }
    }
}
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/channel/ConversationOwnerTest.kt`, add before its last closing brace:

```kotlin

    // Plan 7F: one hand-over opens its page once. In a spoken conversation the voice owns the hand-over, and the text
    // channel's card, which comes once Kestrel is off, also when it turns off during the hand-over, is only a copy of its card.
    @Test
    fun aSpokenConversationsHandOverIsTheVoicesAndTheTextChannelShowsOnlyItsCard() = runTest {
        val handing = async { owner.handOver(HandOver.of(HandOverKind.ACCESS)) }
        runCurrent()
        assertEquals(listOf(HandOverPart.OWN), voice.handOverParts)
        assertTrue(text.handOvers.isEmpty())

        voiceOn.value = false
        runCurrent()
        assertEquals(listOf(HandOverPart.CARD), text.handOverParts)
        owner.endHandOver((events.last() as DialogueEvent.HandedOver).number, HandOverEnd.CONTINUED)
        assertEquals(HandOverEnd.CONTINUED, handing.await())
    }
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/machine/ConversationMachineTest.kt`, add before its last closing brace:

```kotlin

    // Plan 7F: a typed answer whose question has ended is not lost without a word: Kestrel says that the question had
    // ended, so that the owner knows that his words did nothing (design 11 and 12.2). A spoken one that came late is dropped,
    // because the voice's wait ended with its question (plan 7D).
    @Test
    fun aTypedAnswerThatCameAfterItsQuestionEndedSaysSo() {
        val asking = asked(acting(), 4, QuestionKind.YES_NO)
        val ended = ConversationEffect.Output(ChannelOutput.Shown(ConversationTexts.ANSWER_ENDED))

        assertEquals(ended, step(asking, typed(InputIntent.Answer(ChannelAnswer.Yes, question = 3))).effect<ConversationEffect.Output>())
        assertEquals(ended, step(acting(), typed(InputIntent.Answer(ChannelAnswer.Text("Half past seven"), question = 4))).effect<ConversationEffect.Output>())
        assertFalse(step(asking, spoken(InputIntent.Answer(ChannelAnswer.Yes, question = 3))).has<ConversationEffect.Output>())
    }
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.conversation.TypedInputsTest --tests=dev.kestrel.app.conversation.TextChannelTest`
Expected: FAIL: the compiler does not know `TypedInputs`, `TextChannel`, `TypedConversation` and `ReplyScreen`.

Run: `scripts\gradle.ps1 :core:conversation:test --tests=dev.kestrel.conversation.channel.ConversationOwnerTest --tests=dev.kestrel.conversation.machine.ConversationMachineTest`
Expected: FAIL: the compiler does not know `HandOverPart`, `FakeChannel.handOverParts` and `ConversationTexts.ANSWER_ENDED`.

- [ ] **Step 3: One wait for Kestrel's own screen**

Create `app/src/main/kotlin/dev/kestrel/app/conversation/KestrelScreen.kt`:

```kotlin
package dev.kestrel.app.conversation

import dev.kestrel.agent.port.OwnerPort
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waits until no window of Kestrel's own app but its overlays is in front, at most `OwnerPort.KESTREL_LEAVE_MILLIS`, and
 * returns whether it left (design 10.2 of sub-project 7): both channels' `leaveKestrel`. The conversation screen moves
 * itself to the back once the goal that the owner started on it works (plan 7F); every other screen of Kestrel's waits for
 * the owner to leave it.
 */
internal suspend fun OwnerScreen.awaitKestrelLeft(): Boolean =
    withTimeoutOrNull(OwnerPort.KESTREL_LEAVE_MILLIS) {
        while (kestrelInFront()) delay(KESTREL_POLL_MILLIS)
        true
    } ?: false

/** How often [awaitKestrelLeft] reads whether Kestrel's own screen is still in front. */
internal const val KESTREL_POLL_MILLIS = 200L
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, replace:

```kotlin
    // Nothing moves Kestrel's own screen away until plan 7F, so the goal waits for the owner to leave it (design 10.2 of
    // sub-project 7).
    override suspend fun leaveKestrel(): Boolean =
        withTimeoutOrNull(OwnerPort.KESTREL_LEAVE_MILLIS) {
            while (screen.kestrelInFront()) delay(KESTREL_POLL_MILLIS)
            true
        } ?: false
```

with:

```kotlin
    override suspend fun leaveKestrel(): Boolean = screen.awaitKestrelLeft()
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, replace:

```kotlin
        /** How long Android takes to report a paused player as stopped. */
        const val AUDIO_SETTLE_MILLIS = 500L

        /** How often [leaveKestrel] reads whether Kestrel's own screen is still in front. */
        const val KESTREL_POLL_MILLIS = 200L
```

with:

```kotlin
        /** How long Android takes to report a paused player as stopped. */
        const val AUDIO_SETTLE_MILLIS = 500L
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, replace:

```kotlin
import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.agent.port.PermissionEnd
```

with:

```kotlin
import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.PermissionEnd
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, replace:

```kotlin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
```

with:

```kotlin
import kotlinx.coroutines.withContext
```

- [ ] **Step 4: The typed reading and the text channel**

Create `app/src/main/kotlin/dev/kestrel/app/conversation/TypedInputs.kt`:

```kotlin
package dev.kestrel.app.conversation

import dev.kestrel.app.orchestrator.Request
import dev.kestrel.app.orchestrator.Requests
import dev.kestrel.app.orchestrator.VoiceAnswers
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.InputIntent

/**
 * The owner's typed words on Kestrel's own screen as an input of the conversation (design 12.2 of sub-project 7), read by
 * plan 7D's typed reading (`Requests.typed`): only the exact name counts, Kestrel's own requests need no name, and the
 * requests that only the voice reads, such as "never mind", "turn off" and "forget it", stay the owner's words. "Stop" and
 * "cancel" always stop. While [view]'s question waits, the words answer it as the voice reads spoken words
 * (`VoiceAnswers`), and the answer names that question's number, so that it never answers a later one (plan 7D's
 * `InputIntent.Answer(answer, question)`); the input's arrival on the text channel tells the conversation that the owner
 * answered on Kestrel's own screen, so the goal looks afresh (design 10.3; plan 7B's `AnswerQuestion`). A confirmation is
 * the exception, because only its card's buttons answer it (design 10.3), so the words reach it as a command, which counts
 * as "no". Null for a text without words.
 */
internal object TypedInputs {
    fun read(text: String, view: ConversationView): InputIntent? {
        val intent = Requests.typed(text) ?: return null
        if (intent == InputIntent.Stop) return intent
        val asking = view.asking
        if (asking != null && asking != QuestionKind.CONFIRM) {
            // The words after the exact name, as the owner typed them.
            val words = (intent as? InputIntent.Command)?.text ?: text.trim()
            VoiceAnswers.read(asking, words, named = false, request = Request.Other(words))?.let { return InputIntent.Answer(it, view.question) }
        }
        return intent
    }
}
```

Create `app/src/main/kotlin/dev/kestrel/app/conversation/TextChannel.kt`:

```kotlin
package dev.kestrel.app.conversation

import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.app.listen.OwnedBar
import dev.kestrel.conversation.channel.CardAnswer
import dev.kestrel.conversation.channel.ChannelOutput
import dev.kestrel.conversation.channel.ChannelQuestion
import dev.kestrel.conversation.channel.HandOverAnswer
import dev.kestrel.conversation.channel.HandOverPart
import dev.kestrel.conversation.channel.OwnerChannel
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput
import dev.kestrel.safety.HandOver
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicInteger

/** What the text channel needs of the conversation (design 10.1 of sub-project 7); the app graph gives the engine's. */
internal interface TypedConversation {
    /** The conversation's view now, whose open question typed words answer. */
    fun view(): ConversationView

    fun submit(input: OwnerInput)

    /** The owner tapped "Reply…" on question [question]'s card (design 10.3). */
    fun replyOpened(question: Int)

    /** The screen's field holds words while the screen is in front (design 6.2). */
    fun composing(active: Boolean)
}

/** Opens the conversation screen with question [question] in focus and the keyboard up ("Reply…", design 10.3). */
internal fun interface ReplyScreen {
    fun open(question: Int)
}

/**
 * The conversation's text channel (design 10, 11 and 12.2 of sub-project 7). Kestrel's words and final replies go to the
 * bar ([bar]), and the transcript draws them from the records; nothing is spoken here. Every question and hand-over asks
 * through the card desk ([cards]), which shares the card with the voice's copy of the question. "Reply…" on the overlay
 * holds the question for 5 minutes and opens the conversation screen with the question in focus; the typed answer then
 * reaches the conversation as an input, which withdraws the question.
 *
 * The owner's typed words and the screen's buttons become inputs only here: [read] reads typed words against the open
 * question, [input] makes the screen's own inputs (Stop, Continue, New conversation, a past conversation's Continue),
 * [choose] makes a button of the screen's copy of a card the owner's answer, and [submit] gives each to the conversation
 * and to [sent], from which the screen learns that the owner acted on it. The debug hook `conversation-type` types through
 * [read] and [submit] too.
 */
internal class TextChannel(
    private val bar: OwnedBar,
    private val screen: OwnerScreen,
    private val cards: SharedCards,
    private val handOvers: HandOverSignals,
    private val permissions: PermissionDialog,
    private val replies: ReplyScreen,
    private val conversation: TypedConversation,
    private val clock: () -> Long = System::currentTimeMillis,
) : OwnerChannel {
    override val kind: InputChannel = InputChannel.TEXT

    private val sentIntents = MutableSharedFlow<InputIntent>(extraBufferCapacity = SENT_BUFFER)
    private val handOverKeys = AtomicInteger()

    /** Every input that the owner's typing or the screen's buttons gave the conversation, in order. */
    val sent: SharedFlow<InputIntent> = sentIntents.asSharedFlow()

    /**
     * The owner tapped [choice] on the screen's copy of the open card [key] (design 10.3): its answer reaches the
     * conversation as his input, so that the goal looks afresh; "Reply…" holds the question while he types. A card that
     * only the overlay answers (a confirmation, a hand-over or a pick), a card that is no longer open, and a choice that the
     * card does not have take nothing, and no tap is kept for a later card (design 12.2).
     */
    fun choose(key: Int, choice: CardChoice) {
        val card = cards.open.value?.takeIf { it.key == key && it.answersOnScreen && choice in it.choices } ?: return
        when (val answer = choice.answer) {
            null -> conversation.replyOpened(checkNotNull(card.question))
            // The answer names its question, so that a tap that comes late answers no later one (Task A2).
            else -> submit(input(InputIntent.Answer(answer, question = card.question)))
        }
    }

    /** The owner's typed [text] as an input, read against the open question (design 12.2); null for a text without words. */
    fun read(text: String): OwnerInput? = TypedInputs.read(text, conversation.view())?.let(::input)

    /** An input of the conversation screen's own, such as its Stop or its Continue. */
    fun input(intent: InputIntent): OwnerInput = OwnerInput(InputIds.next(), InputChannel.TEXT, intent, clock())

    /** Gives [input] to the conversation and tells [sent]. */
    fun submit(input: OwnerInput) {
        conversation.submit(input)
        sentIntents.tryEmit(input.intent)
    }

    fun composing(active: Boolean) = conversation.composing(active)

    override suspend fun show(output: ChannelOutput) {
        val text = when (output) {
            is ChannelOutput.Said -> output.text
            is ChannelOutput.Shown -> output.text
            is ChannelOutput.Report -> output.text
        }
        bar.show(text)
    }

    // The desk returns only a button that answers, with the number of the owner's tap; "Reply…" opens the screen for the
    // owner's words, which come as an input.
    override suspend fun ask(question: ChannelQuestion): CardAnswer {
        val tapped = cards.ask(OwnerCard.of(question, reply = true)) {
            conversation.replyOpened(question.number)
            replies.open(question.number)
        }
        return CardAnswer(checkNotNull(tapped.choice.answer) { "Reply… is no answer" }, tapped.act)
    }

    // A tap on the card's Continue or Cancel carries the number that the desk took at the tap, so that the round that goes on
    // names the owner's act of that moment (plan 7F). As the hand-over's own channel, the text channel opens what the
    // hand-over opens and watches for its end by itself; as the copy of a spoken conversation's hand-over, it shows only the
    // card, because the voice owns that hand-over, so that its page opens once (plan 7F).
    override suspend fun handOver(handOver: HandOver, part: HandOverPart): HandOverAnswer = coroutineScope {
        val card = OwnerCard.handOver(-handOverKeys.incrementAndGet(), handOver.text)
        val tapped = async {
            val tap = cards.ask(card)
            HandOverAnswer(if (tap.choice == CardChoice.Continue) HandOverEnd.CONTINUED else HandOverEnd.CANCELLED, tap.act)
        }
        if (part == HandOverPart.CARD) return@coroutineScope tapped.await()
        handOvers.opened(handOver.kind)
        val byItself = async {
            handOvers.endedByItself(handOver.kind)
            HandOverAnswer(HandOverEnd.DONE_BY_ITSELF, act = null)
        }
        select<HandOverAnswer> {
            byItself.onAwait { it }
            tapped.onAwait { it }
        }.also { currentCoroutineContext().cancelChildren() }
    }

    // Design 10.6: a typed conversation's permission request says why on the bar and shows Android's dialog, once.
    override suspend fun askPermission(permissions: List<String>, why: String): PermissionEnd {
        bar.show(why)
        return this.permissions.ask(permissions)
    }

    override suspend fun leaveKestrel(): Boolean = screen.awaitKestrelLeft()

    override fun toString(): String = "TextChannel($cards)"

    private companion object {
        const val SENT_BUFFER = 16
    }
}
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/OwnerChannel.kt`, replace:

```kotlin
    /**
     * A hand-over: returns when the owner continued or cancelled on the channel, with the number of his tap on its card's
     * button, or when it ended by itself.
     */
    suspend fun handOver(handOver: HandOver): HandOverAnswer
```

with:

```kotlin
    /**
     * A hand-over: returns when the owner continued or cancelled on the channel, with the number of his tap on its card's
     * button, or when it ended by itself. As the hand-over's own channel ([HandOverPart.OWN]) the channel opens what the
     * hand-over opens and watches for its end by itself; as the copy of its card beside the owner's channel
     * ([HandOverPart.CARD]) it shows only the card, so that one hand-over opens its page once (plan 7F).
     */
    suspend fun handOver(handOver: HandOver, part: HandOverPart = HandOverPart.OWN): HandOverAnswer
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/OwnerChannel.kt`, replace:

```kotlin
data class HandOverAnswer(val end: HandOverEnd, val act: Long?)
```

with:

```kotlin
data class HandOverAnswer(val end: HandOverEnd, val act: Long?)

/**
 * How a channel takes part in a hand-over (plan 7F): as its own channel, the owner's latest, or as the copy of its card that
 * a spoken conversation gets on the text channel once Kestrel is off (design 10.3).
 */
enum class HandOverPart { OWN, CARD }
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/ConversationOwner.kt`, replace:

```kotlin
        return handedOver(handOver.kind, { answered(it.handOver(handOver)) }, { answered(it.handOver(handOver)) }) { it }
```

with:

```kotlin
        return handedOver(handOver.kind, { answered(it.handOver(handOver)) }, { answered(it.handOver(handOver, HandOverPart.CARD)) }) { it }
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/ConversationOwner.kt`, replace:

```kotlin
            { channel -> channel.handOver(HandOver.of(HandOverKind.PERMISSION)).let { Ended(ended(it.end), it.act) } },
```

with:

```kotlin
            { channel -> channel.handOver(HandOver.of(HandOverKind.PERMISSION), HandOverPart.CARD).let { Ended(ended(it.end), it.act) } },
```

In `core/conversation/src/testFixtures/kotlin/dev/kestrel/conversation/testing/FakeChannel.kt`, replace:

```kotlin
    // A queued end other than DONE_BY_ITSELF is the owner's tap on the card, which takes its number.
    override suspend fun handOver(handOver: HandOver): HandOverAnswer {
        handOvers += handOver
```

with:

```kotlin
    /** How the channel took part in each hand-over, as its own channel or as the copy of its card (plan 7F). */
    val handOverParts = mutableListOf<HandOverPart>()

    // A queued end other than DONE_BY_ITSELF is the owner's tap on the card, which takes its number.
    override suspend fun handOver(handOver: HandOver, part: HandOverPart): HandOverAnswer {
        handOvers += handOver
        handOverParts += part
```

In `core/conversation/src/testFixtures/kotlin/dev/kestrel/conversation/testing/FakeChannel.kt`, replace:

```kotlin
import dev.kestrel.conversation.channel.HandOverAnswer
import dev.kestrel.conversation.channel.OwnerChannel
```

with:

```kotlin
import dev.kestrel.conversation.channel.HandOverAnswer
import dev.kestrel.conversation.channel.HandOverPart
import dev.kestrel.conversation.channel.OwnerChannel
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, replace:

```kotlin
    // The voice's hand-over has no button: the owner's "Kestrel, continue" reaches the conversation as an input.
    override suspend fun handOver(handOver: HandOver): HandOverAnswer = try {
        voice.handOver(handOver.text)
```

with:

```kotlin
    // The voice's hand-over has no button: the owner's "Kestrel, continue" reaches the conversation as an input. The voice
    // is always the hand-over's own channel; only the text channel shows a copy of its card (plan 7F).
    override suspend fun handOver(handOver: HandOver, part: HandOverPart): HandOverAnswer = try {
        check(part == HandOverPart.OWN) { "The voice shows no hand-over's card" }
        voice.handOver(handOver.text)
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, replace:

```kotlin
import dev.kestrel.conversation.channel.HandOverAnswer
import dev.kestrel.conversation.channel.OwnerChannel
```

with:

```kotlin
import dev.kestrel.conversation.channel.HandOverAnswer
import dev.kestrel.conversation.channel.HandOverPart
import dev.kestrel.conversation.channel.OwnerChannel
```
In `core/conversation/src/main/kotlin/dev/kestrel/conversation/ConversationTexts.kt`, replace:

```kotlin
    const val FINISH_HAND_OVER_SPOKEN = "Say 'Kestrel, continue' or 'cancel' first."
```

with:

```kotlin
    const val FINISH_HAND_OVER_SPOKEN = "Say 'Kestrel, continue' or 'cancel' first."

    /** A typed answer came after its question had ended (plan 7F). */
    const val ANSWER_ENDED = "That question had ended, so I didn't use your answer."
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/model/OwnerInput.kt`, replace:

```kotlin
     * The answer to the open question as the channel read it; [ChannelAnswer.Silence] when the voice's wait ended. The
     * voice's answers and a button on the conversation screen's copy of a card (plan 7F) name their [question], and answer
     * no other, so that an answer that comes after its question ended never answers a newer one; the typed words' answers
     * name none and answer the open question.
```

with:

```kotlin
     * The answer to the open question as the channel read it; [ChannelAnswer.Silence] when the voice's wait ended. The
     * voice's answers, the typed words' answers and a button on the conversation screen's copy of a card (plan 7F) name
     * their [question], and answer no other, so that an answer that comes after its question ended never answers a newer
     * one; only a command's words answer the open question unnamed.
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/ConversationMachine.kt`, replace:

```kotlin
            is InputIntent.Answer -> MachineStep(state)
            InputIntent.Stop -> state.local?.let { stopLocal(state, it) } ?: GoalRules.stop(state)
```

with:

```kotlin
            is InputIntent.Answer -> lateAnswer(state, input)
            InputIntent.Stop -> state.local?.let { stopLocal(state, it) } ?: GoalRules.stop(state)
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/ConversationMachine.kt`, replace:

```kotlin
            // An answer that names another question came late: the question that it answered has gone (the voice's answers;
            // plan 7F's screen buttons).
            is InputIntent.Answer ->
                if (intent.question != null && intent.question != open.number) {
                    MachineStep(state)
                } else {
```

with:

```kotlin
            // An answer that names another question came late: the question that it answered has gone (the voice's answers,
            // the typed words' answers and the screen's buttons all name theirs; plan 7F).
            is InputIntent.Answer ->
                if (intent.question != null && intent.question != open.number) {
                    lateAnswer(state, input)
                } else {
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/ConversationMachine.kt`, replace:

```kotlin
    // "Stop" cancels Kestrel's own request, a switch too, and drops what waits.
```

with:

```kotlin
    // Plan 7F: a typed answer whose question has ended is not lost without a word: Kestrel says that the question had ended,
    // so that the owner knows that his words did nothing (design 11 and 12.2). A spoken one is dropped, because the voice's
    // wait ended with its question (plan 7D), and a button's answer comes only from a card that was open.
    private fun lateAnswer(state: ConversationState, input: OwnerInput): MachineStep =
        if (input.channel == InputChannel.TEXT) {
            MachineStep(state, listOf(ConversationEffect.Output(ChannelOutput.Shown(ConversationTexts.ANSWER_ENDED))))
        } else {
            MachineStep(state)
        }

    // "Stop" cancels Kestrel's own request, a switch too, and drops what waits.
```
- [ ] **Step 5: Run the tests**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.conversation.*`
Expected: BUILD SUCCESSFUL; `TypedInputsTest` 4, `TextChannelTest` 10, `CardDeskTest` 7 and `VoiceChannelTest` 10 tests pass.

Run: `scripts\gradle.ps1 :core:conversation:test :app:testDebugUnitTest; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL; `:app` JVM A₂ + 14, `:core:conversation` C₃ + 2.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/kestrel/app/conversation app/src/test/kotlin/dev/kestrel/app/conversation core/conversation/src
git commit -m "feat(app): the text channel reads the owner's typed words, asks through the card desk, and takes a screen button as the owner's input" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: The bar's one writer and its progress, typed replies spoken with listening off, and the service's start for the screen

Design 10.5 of sub-project 7: during a goal the bar shows its progress, "Step 12 · 3 of 7 · Replying to Mila", from the view's step and checklist, and with a goal held it shows briefly "On hold: say 'Kestrel, continue' or open Kestrel."; no earlier plan shows them, and `GoalBar` does it for both channels. The bar has one writer, plan 7D's `OwnedBar`, which knows whose text the bar shows (`BarOwner`), and which the app graph now builds and gives to listening, the text channel (Task 4) and the owner's parts, whose release of a question's or a hand-over's text went to the screen session directly before and now lets go of only that owner's text. Listening's texts, the text channel's words and the progress all pass through it: a goal's progress is its owner of its own (`BarOwner.Progress`, `progress(text)`); a kept text (a question, a hand-over's sentence, the follow-up's count) stays until it is released, and the newest progress waits while a kept text holds the bar or another text showed in the last 4 s, the bar's own time before it fades, and shows once the bar is free, so that it never hides Kestrel's words, replaces only an earlier progress, and "On hold" shows after the held goal's own sentence (review low item b). Design 10.6 and 11: with "Speak replies to typed commands" on, a typed conversation's words are spoken too, and with listening off Kestrel speaks them with its own voice, loaded for that only, inside the goal service's `mediaPlayback` (plan 7E's `whileSpeaking`). Plan 7B's `ConversationOwner` sends a typed conversation's words to the voice channel as `Said` and, while Kestrel is off, a spoken conversation's as `Shown`. Which voice speaks a `Said` is the engine's decision, made once as it routes the words, and it travels with them (`Said.ownVoice`, set while Kestrel is off): the voice channel follows it and never reads listening's state itself, so a word that waited in the channel while the owner turned Kestrel off is not spoken by the Orchestrator, and one that was routed to Kestrel's own voice is not spoken after the owner's Stop (review A-I2 and A-M6, design 11). A typed Stop stops Kestrel's own voice at once (`VoiceChannel.stopSpeech`, which the app graph calls on the owner's Stop) and drops its waiting words. Design 10.6: the conversation screen leaves the front only once Android has answered the start of the goal service that the owner began there (Task 6), so `GoalForeground` tells it what it knows of the service: the goal's phase as the coordinator has seen it, whether the service is in the foreground, and whether every start that Kestrel asked for has been answered.

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/conversation/GoalBar.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/OffVoice.kt`
- Modify: `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/OwnerChannel.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/ConversationOwner.kt`, `app/src/main/kotlin/dev/kestrel/app/listen/OwnedBar.kt`, `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/OwnerSupport.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, `app/src/main/kotlin/dev/kestrel/app/goal/GoalForeground.kt`, `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, `app/src/main/kotlin/dev/kestrel/app/listen/VoicePath.kt`
- Test: create `app/src/test/kotlin/dev/kestrel/app/conversation/GoalBarTest.kt`, `app/src/test/kotlin/dev/kestrel/app/listen/OwnedBarTest.kt`; modify `core/conversation/src/test/kotlin/dev/kestrel/conversation/channel/ConversationOwnerTest.kt`, `app/src/test/kotlin/dev/kestrel/app/conversation/VoiceChannelTest.kt`, `app/src/test/kotlin/dev/kestrel/app/conversation/TextChannelTest.kt`, `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, `app/src/test/kotlin/dev/kestrel/app/goal/GoalForegroundTest.kt`, `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`

**Interfaces:**
- Consumes: plan 7B's `ConversationView` (`goal`, `step`, `checklist`) and `ChecklistSummary(done, total, current)`; plan 7D's `VoiceChannel`, `AppGraph.voiceChannel`, `ownerSupport` and `followConversation`, `Listening.state`, `Mode.OFF`, `FeedbackBar`, `OwnedBar` (`show`, `takeOver`, `release`) and `BarOwner`, `AndroidOwnerSupport.releaseBar`; Task 4's `TextChannel(bar: OwnedBar, …)`; plan 7E's `GoalForeground` (`whileSpeaking`, `started`, its rules' `starts`) and `GoalServiceRules`; Task 1's `AppGraph.cardDesk`; `VoiceEngines.synthesizer(options)`, `VoicePlayer(context, synthesizer)` with `speak(text): Deferred<Spoke>` and `close()`, `SettingKeys.VOICE` and `VoiceOptionsSetting.options()`.
- Produces: `internal object GoalBar { const val ON_HOLD; fun text(view: ConversationView): String?; suspend fun follow(views: Flow<ConversationView>, show: suspend (String?) -> Unit) }`; `internal class OwnedBar(bar: FeedbackBar, scope: CoroutineScope, clock: () -> Long)` (in place of `OwnedBar(bar)`) with `BarOwner.Progress` and `progress(text: String?)`, where null drops a waiting line, and `OTHERS_MILLIS = 4_000L`; `Listening(…, bar: OwnedBar, …)` and `AndroidOwnerSupport(context, sessions, bar: OwnedBar, open, calls)`; a final reply's kept text holds the bar against the progress for its follow-up countdown (review A-M5) and is let go when none follows it: the Rules' `Effect.NoFollowUp(reply)`, on which listening releases `BarOwner.FinalReply(reply)` (review A-R2); `Event.StopSpeaking` and `VoicePath.stopSpeaking()`, the owner's Stop that did not come by voice, which stops Kestrel's speech as a spoken stop does; `AppGraph.feedbackBar: OwnedBar`, the bar's one writer, which listening, the text channel and the owner's parts write through; `internal data class ForegroundState(goal, running, settled)` and `GoalForeground.foreground: StateFlow<ForegroundState>`; `internal interface OffVoice { suspend fun speak(text: String); fun stop() }`; `ChannelOutput.Said(text, ownVoice: Boolean = false)`, which `ConversationOwner` sets when the voice's copy of a typed conversation's words is to be spoken by Kestrel's own voice because Kestrel is off, so that the engine's decision travels with the output (review A-I2); `internal class AppOffVoice(context, engines: VoiceEngines, options: suspend () -> VoiceOptions, foreground: suspend (suspend () -> Unit) -> Unit, scope: CoroutineScope, report: (String, Throwable) -> Unit, idleMillis: Long = IDLE_MILLIS) : OffVoice` with `replies: Int` and `IDLE_MILLIS = 30_000L`; `VoiceChannel(voice, screen, cards, audio, handOvers, permissions, offVoice: OffVoice, audioSettleMillis)` with `stopSpeech()`, which the app graph calls on the owner's Stop that did not come by voice: the voice session's speech stops (`VoicePath.stopSpeaking`), Kestrel's own voice stops, and the waiting words go (review A-M6 and its re-check); `AppGraph.offVoice: AppOffVoice` and the private `permissionDialog`, which Task 8's text channel shares.

Counts: `:app` JVM A₄ + 13 (`GoalBarTest` 3, `OwnedBarTest` 5, `VoiceChannelTest` + 2, `GoalForegroundTest` + 1, `RulesTest` + 2), where A₄ is the count after Task 4. `:core:conversation` C₄ + 1 (`ConversationOwnerTest` + 1), where C₄ is the count after Task 4.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/dev/kestrel/app/conversation/GoalBarTest.kt`:

```kotlin
package dev.kestrel.app.conversation

import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.engine.ChecklistSummary
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.GoalHold
import dev.kestrel.conversation.model.GoalPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The feedback bar's line for a goal (design 10.5 of sub-project 7). */
@OptIn(ExperimentalCoroutinesApi::class)
class GoalBarTest {
    @Test
    fun aWorkingGoalShowsItsStepItsChecklistAndItsCurrentItem() {
        val view = ConversationView(goal = GoalPhase.ACTING, step = 12, checklist = ChecklistSummary(3, 7, "Reply to Mila"))

        assertEquals("Step 12 · 3 of 7 · Reply to Mila", GoalBar.text(view))
        assertEquals("Step 2", GoalBar.text(ConversationView(goal = GoalPhase.ACTING, step = 2)))
        assertNull(GoalBar.text(ConversationView(goal = GoalPhase.ACTING)))
    }

    @Test
    fun aHeldGoalSaysHowToGoOnAndQuestionsLeaveTheBarToTheirChannel() {
        assertEquals(GoalBar.ON_HOLD, GoalBar.text(ConversationView(goal = GoalPhase.INTERRUPTED, hold = GoalHold.TOUCH)))
        assertEquals(GoalBar.ON_HOLD, GoalBar.text(ConversationView(goal = GoalPhase.PAUSED, hold = GoalHold.LOCK)))
        assertNull(GoalBar.text(ConversationView(goal = GoalPhase.ASKING, step = 3, asking = QuestionKind.FREE)))
        assertNull(GoalBar.text(ConversationView(goal = GoalPhase.HANDED_OVER, step = 3)))
        assertNull(GoalBar.text(ConversationView()))
    }

    @Test
    fun theBarShowsEachNewLineOnce() = runTest {
        val views = MutableStateFlow(ConversationView())
        val shown = mutableListOf<String?>()
        val following = launch(UnconfinedTestDispatcher(testScheduler)) { GoalBar.follow(views) { shown += it } }

        views.value = ConversationView(goal = GoalPhase.ACTING, step = 1)
        views.value = ConversationView(goal = GoalPhase.ACTING, step = 1, testMode = false)
        views.value = ConversationView(goal = GoalPhase.ACTING, step = 2)
        views.value = ConversationView(goal = GoalPhase.INTERRUPTED, hold = GoalHold.TOUCH)
        views.value = ConversationView()

        // The goal's end passes null on, so that a line that still waits for the bar never shows (review L1).
        assertEquals(listOf(null, "Step 1", "Step 2", GoalBar.ON_HOLD, null), shown)
        following.cancel()
    }
}
```

In `app/src/test/kotlin/dev/kestrel/app/conversation/VoiceChannelTest.kt`, replace:

```kotlin
    private val channel = VoiceChannel(voice, support, support, support, support, support, audioSettleMillis = 500)
```

with:

```kotlin
    private val offSpoken = mutableListOf<String>()
    private var offStops = 0
    private var offHeld: CompletableDeferred<Unit>? = null
    private val offVoice = object : OffVoice {
        override suspend fun speak(text: String) {
            offSpoken += text
            offHeld?.await()
        }

        override fun stop() {
            offStops++
        }
    }
    private val channel = VoiceChannel(voice, support, support, support, support, support, offVoice, audioSettleMillis = 500)
```

In `app/src/test/kotlin/dev/kestrel/app/conversation/VoiceChannelTest.kt`, replace:

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
```

with:

```kotlin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
```
In `app/src/test/kotlin/dev/kestrel/app/conversation/VoiceChannelTest.kt`, replace:

```kotlin
    // Without a card, as without a screen session, the voice still answers (plan 7F).
```

with:

```kotlin
    // Design 11 of sub-project 7: how Kestrel speaks is the engine's decision, which travels with the words. Only words that
    // it routed to Kestrel's own voice (a typed conversation's, while Kestrel is off) reach that voice, so a spoken
    // conversation's words that wait for their turn while the owner turns Kestrel off are never spoken by it (plan 7F).
    @Test
    fun onlyWordsRoutedToKestrelsOwnVoiceAreSpokenByIt() = runTest {
        channel.show(ChannelOutput.Said("Done.", ownVoice = true))
        assertEquals(listOf("Done."), offSpoken)
        assertTrue(voice.said.isEmpty())

        channel.show(ChannelOutput.Said("Sent."))
        assertEquals(listOf("Sent."), voice.said)
        assertEquals(1, offSpoken.size)
    }

    // Plan 7F: the owner's Stop on the screen or on the notification is the same stop as a spoken one: the voice session's
    // speech stops, Kestrel's own voice stops, and the words that wait are dropped.
    @Test
    fun aStopStopsKestrelsSpeechAndDropsItsWaitingWords() = runTest {
        val held = CompletableDeferred<Unit>()
        offHeld = held
        launch { channel.show(ChannelOutput.Said("One.", ownVoice = true)) }
        runCurrent()
        launch { channel.show(ChannelOutput.Said("Two.", ownVoice = true)) }
        runCurrent()

        channel.stopSpeech()
        held.complete(Unit)
        runCurrent()

        assertEquals(listOf("One."), offSpoken)
        assertEquals(1, offStops)
        assertEquals(1L, voice.speechStops)
    }

    // Without a card, as without a screen session, the voice still answers (plan 7F).
```

Create `app/src/test/kotlin/dev/kestrel/app/listen/OwnedBarTest.kt`:

```kotlin
package dev.kestrel.app.listen

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The feedback bar's one writer (design 6.6 and 10.5 of sub-project 7): a goal's progress never hides another text, and shows after it. */
class OwnedBarTest {
    private val shown = mutableListOf<Pair<String, Boolean>>()
    private var releases = 0

    private fun TestScope.bar() = OwnedBar(
        object : FeedbackBar {
            override fun show(text: String, keep: Boolean) {
                shown += text to keep
            }

            override fun release() {
                releases++
            }
        },
        backgroundScope,
    ) { testScheduler.currentTime }

    @Test
    fun aKeptTextHoldsTheBarUntilItsOwnerReleasesItAndThenTheNewestProgressShows() = runTest {
        val bar = bar()
        bar.progress("Step 1")
        bar.show("Which account?", keep = true)
        advanceTimeBy(10_000)
        bar.progress("Step 2")
        bar.progress("Step 3")
        runCurrent()
        // Only the kept text's own owner lets it go.
        bar.release(BarOwner.Commands)
        bar.release(BarOwner.Other)

        assertEquals(listOf("Step 1" to false, "Which account?" to true, "Step 3" to false), shown)
        assertEquals(1, releases)
    }

    // Review low item b of plan 7F: "On hold" shows once the held goal's own sentence has had its time on the bar.
    @Test
    fun anotherTextKeepsTheBarForItsTimeAndThenTheNewestProgressShowsByItself() = runTest {
        val bar = bar()
        bar.show("I stopped because you touched the screen.")
        advanceTimeBy(1_000)
        bar.progress("Step 12")
        bar.progress("On hold")
        advanceTimeBy(OwnedBar.OTHERS_MILLIS - 1_001)
        runCurrent()
        assertEquals(listOf("I stopped because you touched the screen." to false), shown)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("I stopped because you touched the screen." to false, "On hold" to false), shown)
    }

    // Review A-M5 of plan 7F: a final reply holds the bar against a goal's progress until its follow-up window's countdown
    // has taken it over and let go, so that the progress never cuts the countdown.
    @Test
    fun aFinalReplyKeepsTheBarForItsFollowUpCountdown() = runTest {
        val bar = bar()
        bar.show("Done.", owner = BarOwner.FinalReply(3))
        bar.progress("On hold")
        advanceTimeBy(OwnedBar.OTHERS_MILLIS * 2)
        runCurrent()
        assertTrue(bar.takeOver("Go on… 5", BarOwner.FollowUp, over = BarOwner.FinalReply(3)))
        bar.release(BarOwner.FollowUp)
        advanceTimeBy(OwnedBar.OTHERS_MILLIS)
        runCurrent()

        assertEquals(listOf("Done." to false, "Go on… 5" to true, "On hold" to false), shown)
    }

    // Review A-R2 of plan 7F: a final reply that gets no follow-up window (unspoken, Kestrel off, or no window could open)
    // is let go by listening, and a later progress then shows.
    @Test
    fun aFinalReplyWithoutACountdownLetsALaterProgressShowOnceItIsLetGo() = runTest {
        val bar = bar()
        bar.show("Done.", owner = BarOwner.FinalReply(3))
        bar.progress("Step 1")
        advanceTimeBy(OwnedBar.OTHERS_MILLIS * 2)
        runCurrent()
        assertEquals(listOf("Done." to false), shown)

        bar.release(BarOwner.FinalReply(3))
        runCurrent()

        assertEquals(listOf("Done." to false, "Step 1" to false), shown)
    }
    // Review L1 of plan 7F: a progress line that waits for the bar never shows once its goal has ended.
    @Test
    fun aWaitingProgressIsDroppedWhenItsGoalEnds() = runTest {
        val bar = bar()
        bar.show("Which account?", keep = true)
        bar.progress("Step 3")
        bar.progress(null)
        bar.release(BarOwner.Other)
        bar.show("Done.")
        bar.progress("Step 1")
        bar.progress(null)
        advanceTimeBy(OwnedBar.OTHERS_MILLIS * 2)
        runCurrent()

        assertEquals(listOf("Which account?" to true, "Done." to false), shown)
    }
}
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
        services = services,
        bar = bar,
        report = { what, error -> reports += what to error },
        openSettings = {
```

with:

```kotlin
        services = services,
        bar = OwnedBar(bar, backgroundScope) { testScheduler.currentTime },
        report = { what, error -> reports += what to error },
        openSettings = {
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
            bar = bar,
            report = { what, error -> reports += what to error },
            openSettings = { SettingsOpening.OPENED },
```

with:

```kotlin
            bar = OwnedBar(bar, backgroundScope) { testScheduler.currentTime },
            report = { what, error -> reports += what to error },
            openSettings = { SettingsOpening.OPENED },
```

In `app/src/test/kotlin/dev/kestrel/app/conversation/TextChannelTest.kt`, replace:

```kotlin
        return TextChannel(OwnedBar(support), support, desk, support, support, ReplyScreen { opened += it }, conversation) { 1_000L }
```

with:

```kotlin
        return TextChannel(OwnedBar(support, backgroundScope) { testScheduler.currentTime }, support, desk, support, support, ReplyScreen { opened += it }, conversation) { 1_000L }
```

In `app/src/test/kotlin/dev/kestrel/app/goal/GoalForegroundTest.kt`, add before its last closing brace:

```kotlin

    // Plan 7F: the conversation screen leaves the front only once Android has answered the start that the owner began there.
    @Test
    fun theScreenSeesTheServiceInTheForegroundOnlyOnceAndroidAnsweredEveryStart() = runTest {
        val foreground = following()
        assertEquals(ForegroundState(), foreground.foreground.value)

        views.value = ConversationView(command = 1, goal = GoalPhase.ACTING)
        assertEquals(ForegroundState(GoalPhase.ACTING, running = false, settled = false), foreground.foreground.value)
        foreground.started(setOf(ForegroundType.SPECIAL_USE))
        assertEquals(ForegroundState(GoalPhase.ACTING, running = true, settled = true), foreground.foreground.value)

        // A resume starts the running service again, and the screen waits for Android's answer once more.
        views.value = ConversationView(command = 1, goal = GoalPhase.INTERRUPTED, hold = GoalHold.TOUCH)
        views.value = ConversationView(command = 1, goal = GoalPhase.ACTING)
        assertEquals(ForegroundState(GoalPhase.ACTING, running = true, settled = false), foreground.foreground.value)
        foreground.started(setOf(ForegroundType.SPECIAL_USE))
        assertEquals(ForegroundState(GoalPhase.ACTING, running = true, settled = true), foreground.foreground.value)
    }
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/channel/ConversationOwnerTest.kt`, replace:

```kotlin
        // A typed conversation's replies are still spoken with the setting on (design 11).
        typed(speak = true)
        owner.show(ChannelOutput.Report(3, "Sent."))
        assertEquals(ChannelOutput.Said("Sent."), voice.shown.last())
```

with:

```kotlin
        // A typed conversation's replies are still spoken with the setting on (design 11), by Kestrel's own voice (plan 7F).
        typed(speak = true)
        owner.show(ChannelOutput.Report(3, "Sent."))
        assertEquals(ChannelOutput.Said("Sent.", ownVoice = true), voice.shown.last())
```
In `core/conversation/src/test/kotlin/dev/kestrel/conversation/channel/ConversationOwnerTest.kt`, add before its last closing brace:

```kotlin

    // Plan 7F: the engine's routing decides how Kestrel speaks, and the decision travels with the words: the voice's copy of
    // a typed conversation's words says that Kestrel's own voice speaks it when Kestrel was off as it was routed, and a
    // spoken conversation's words while Kestrel is off are only shown (design 11).
    @Test
    fun theVoicesCopyOfATypedConversationsWordsSaysWhichVoiceSpeaksThem() = runTest {
        typed(speak = true)
        owner.say("On it.")
        voiceOn.value = false
        owner.say("Done.")
        route = ConversationOwner.Routing(InputChannel.VOICE, speakTyped = false)
        owner.say("Looking.")

        assertEquals(listOf(ChannelOutput.Said("On it."), ChannelOutput.Said("Done.", ownVoice = true), ChannelOutput.Shown("Looking.")), voice.shown)
    }
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
            listOf(Effect.Show(Feedback.ConversationText("Done.", finalReply = 5)), Effect.EndReply(5)),
```

with:

```kotlin
            listOf(Effect.Show(Feedback.ConversationText("Done.", finalReply = 5)), Effect.EndReply(5), Effect.NoFollowUp(5)),
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
    @Test
    fun turnOffAloneInTheFollowUpWindowTurnsKestrelOffAndReachesNoConversation() {
```

with:

```kotlin
    // Review A-R2 of plan 7F: a final reply's text holds the bar for its window's countdown, so the Rules say when no window
    // follows it, and only then: it was not spoken to its end, Kestrel turned off, a newer reply came, or no window could
    // open.
    @Test
    fun aFinalReplyThatGetsNoFollowUpWindowIsLetGo() {
        val said = Rules.next(open, Event.ChannelSays(5, "Done.", final = true)).state
        val noFollowUp = Effect.NoFollowUp(5)

        assertTrue(noFollowUp in Rules.next(said, Event.SpeechEnded(spoken = false)).effects)
        assertTrue(noFollowUp in Rules.next(said, Event.TurnOff).effects)
        assertTrue(noFollowUp in Rules.next(said, Event.ChannelSays(6, "Got it.", final = true)).effects)
        val withoutLength = Rules.next(Rules.next(open, Event.FollowUpLength(0)).state, Event.ChannelSays(5, "Done.", final = true)).state
        assertTrue(noFollowUp in Rules.next(withoutLength, Event.SpeechEnded(spoken = true)).effects)
        // A window that opens takes the reply's text over, and words that are no final reply hold nothing.
        assertTrue(Rules.next(said, Event.SpeechEnded(spoken = true)).effects.none { it is Effect.NoFollowUp })
        val words = Rules.next(open, Event.ChannelSays(4, "Opening Viber.")).state
        assertTrue(Rules.next(words, Event.SpeechEnded(spoken = true)).effects.none { it is Effect.NoFollowUp })
    }

    // Plan 7F: the owner's Stop that does not come by voice is the same stop as a spoken one for Kestrel's speech.
    @Test
    fun theOwnersStopWithoutHisVoiceStopsKestrelsSpeech() {
        val said = Rules.next(open, Event.ChannelSays(5, "Done.", final = true)).state

        assertEquals(listOf(Effect.StopSaying), Rules.next(said, Event.StopSpeaking).effects)
        assertEquals(Step(open), Rules.next(open, Event.StopSpeaking))
    }

    @Test
    fun turnOffAloneInTheFollowUpWindowTurnsKestrelOffAndReachesNoConversation() {
```

In `app/src/test/kotlin/dev/kestrel/app/conversation/VoiceChannelTest.kt`, replace:

```kotlin
        override fun withdraw(question: Int) {
            withdrawn += question
        }
    }
```

with:

```kotlin
        override fun withdraw(question: Int) {
            withdrawn += question
        }

        // As Listening: the Orchestrator's StopSaying for the owner's Stop that did not come by voice.
        override fun stopSpeaking() = stop()
    }
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `scripts\gradle.ps1 :core:conversation:test --tests=dev.kestrel.conversation.channel.ConversationOwnerTest`
Expected: FAIL: the compiler does not know `ChannelOutput.Said.ownVoice`.
Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.conversation.GoalBarTest --tests=dev.kestrel.app.conversation.VoiceChannelTest --tests=dev.kestrel.app.listen.OwnedBarTest --tests=dev.kestrel.app.goal.GoalForegroundTest --tests=dev.kestrel.app.orchestrator.RulesTest`
Expected: FAIL: the compiler does not know `GoalBar`, `OffVoice`, `OwnedBar.progress`, `OwnedBar.OTHERS_MILLIS`, `ForegroundState` and `GoalForeground.foreground`, `OwnedBar` takes no scope and no clock, and `VoiceChannel` takes no voice for while Kestrel is off; nor does it know `Effect.NoFollowUp`, `Event.StopSpeaking` and `VoicePath.stopSpeaking`.

- [ ] **Step 3: The bar's line for a goal**

Create `app/src/main/kotlin/dev/kestrel/app/conversation/GoalBar.kt`:

```kotlin
package dev.kestrel.app.conversation

import dev.kestrel.conversation.engine.ChecklistSummary
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.GoalPhase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The feedback bar's line for a goal (design 10.5 of sub-project 7), for both channels: while the goal acts, its progress,
 * "Step 12 · 3 of 7 · Replying to Mila", from the view's step and checklist; once it is held, [ON_HOLD], which names the
 * words, because the bar takes no touches. A question or a hand-over leaves the bar to the channel that asks it.
 */
internal object GoalBar {
    const val ON_HOLD = "On hold: say 'Kestrel, continue' or open Kestrel."

    /** The bar's line for [view]'s goal, or null when the bar shows nothing of it. */
    fun text(view: ConversationView): String? = when (view.goal) {
        null, GoalPhase.ASKING, GoalPhase.HANDED_OVER -> null
        GoalPhase.ACTING -> progress(view.step, view.checklist)
        GoalPhase.PAUSED, GoalPhase.PAUSED_AT_LIMIT, GoalPhase.INTERRUPTED -> ON_HOLD
    }

    /**
     * Passes each new line of [views] to [show], once; the bar lets a line fade by itself. Null means that the bar has no
     * line for the goal any more (it ended, or it asks), so that a line that still waits for the bar is dropped (review L1).
     */
    suspend fun follow(views: Flow<ConversationView>, show: suspend (String?) -> Unit) {
        views.map(::text).distinctUntilChanged().collect { show(it) }
    }

    private fun progress(step: Int?, checklist: ChecklistSummary?): String? =
        listOfNotNull(step?.let { "Step $it" }, checklist?.let { "${it.done} of ${it.total}" }, checklist?.current)
            .joinToString(" · ")
            .ifEmpty { null }
}
```

- [ ] **Step 4: The bar's one writer, and what the screen reads of the goal service**

Replace the whole `app/src/main/kotlin/dev/kestrel/app/listen/OwnedBar.kt` with:

```kotlin
package dev.kestrel.app.listen

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Who put the text on the feedback bar that it shows last (design 6.6 of sub-project 7). */
internal sealed interface BarOwner {
    /**
     * A text that nobody takes back: a mode, a transcript, "Microphone blocked", a failure, and what the conversation says,
     * asks or hands over. A question or a hand-over's sentence stays until the next text, or until its channel lets it go.
     */
    data object Other : BarOwner

    /** The instant commands' texts: the command that keeps one releases it when it ends (section 7.3). */
    data object Commands : BarOwner

    /** The conversation's final reply number [reply], over which its follow-up window counts down. */
    data class FinalReply(val reply: Int) : BarOwner

    /** The follow-up window's countdown, "Go on…" with the seconds left. */
    data object FollowUp : BarOwner

    /** A goal's progress, design 10.5's "Step 12 · 3 of 7 · …" or "On hold", which only ever replaces progress (plan 7F). */
    data object Progress : BarOwner
}

/**
 * Kestrel's feedback bar with its one writer (design 6.6 and 10.5 of sub-project 7): listening's texts, the text channel's
 * words, the owner's parts' release of a question's text and a goal's progress all reach [bar] through it, in the order of
 * the calls, and it knows whose text the bar shows last, so that a text is taken over only from the owner that it follows
 * and released only by its own owner. The bar is free once its text was released. A goal's [progress] never hides another
 * owner's text: while a kept text or a final reply, which its follow-up countdown takes over, holds the bar, or another
 * text showed less than [OTHERS_MILLIS] ago on [clock], the bar's own time before it fades, the newest progress waits, and it shows once the kept text is released or that time has passed,
 * in [scope]. So "On hold" shows after the held goal's own sentence, and a progress replaces only progress (plan 7F). Any
 * thread may call it.
 */
internal class OwnedBar(
    private val bar: FeedbackBar,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    private val lock = Any()

    // Guarded by lock. The owner is null while the bar is free.
    private var owner: BarOwner? = null
    private var kept = false
    private var shownAt: Long? = null
    private var pending: String? = null
    private var waiting: Job? = null

    /** Shows [text] for [owner], as [FeedbackBar.show] does. */
    fun show(text: String, keep: Boolean = false, owner: BarOwner = BarOwner.Other) {
        synchronized(lock) { put(text, keep, owner) }
    }

    /**
     * Keeps [text] on the bar for [owner], but only while the bar is free, shows [owner]'s own text, or shows the text of
     * [over]; otherwise it shows nothing, so that it never hides a text that someone showed since, and returns false.
     */
    fun takeOver(text: String, owner: BarOwner, over: BarOwner): Boolean {
        synchronized(lock) {
            val current = this.owner
            if (current != null && current != owner && current != over) return false
            put(text, keep = true, owner)
            return true
        }
    }

    /** Lets [owner]'s kept text fade from now on, if the bar still shows it; another owner's text stays. */
    fun release(owner: BarOwner) {
        synchronized(lock) {
            if (this.owner != owner) return
            this.owner = null
            kept = false
            bar.release()
            flush()
        }
    }

    /**
     * Shows a goal's [text] once no other owner's text holds the bar; a newer progress replaces one that still waits, and
     * null drops it, because its goal has ended or asks (review L1 of plan 7F). A line that already shows fades by itself.
     */
    fun progress(text: String?) {
        synchronized(lock) {
            pending = text
            if (text == null) {
                waiting?.cancel()
                waiting = null
            }
            flush()
        }
    }

    override fun toString(): String = synchronized(lock) { "OwnedBar(owner=$owner, kept=$kept, pending=${pending != null})" }

    // Under the lock.
    private fun put(text: String, keep: Boolean, owner: BarOwner) {
        this.owner = owner
        kept = keep
        shownAt = clock()
        bar.show(text, keep)
    }

    // Under the lock: the waiting progress now, when the bar is free or shows progress, or once the other text's time has
    // passed. A final reply holds the bar as a kept text does, until its follow-up window's countdown has taken it over and
    // let go, or another text replaced it, so that a progress never cuts the countdown (review A-M5 of plan 7F); a goal's
    // progress and a follow-up window never meet otherwise, because the window opens only without a goal (plan 7D).
    private fun flush() {
        val text = pending ?: return
        if (kept || owner is BarOwner.FinalReply) return
        val left = if (owner == BarOwner.Progress) 0L else shownAt?.let { OTHERS_MILLIS - (clock() - it) } ?: 0L
        if (left <= 0) {
            pending = null
            put(text, keep = false, BarOwner.Progress)
        } else if (waiting?.isActive != true) {
            waiting = scope.launch {
                delay(left)
                synchronized(lock) {
                    waiting = null
                    flush()
                }
            }
        }
    }

    companion object {
        /** How long the bar shows a text that is not kept before it fades (`FeedbackBar.FADE_AFTER_MILLIS` of `:core:screen`). */
        const val OTHERS_MILLIS = 4_000L
    }
}
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
    private val services: ServiceControl,
    bar: FeedbackBar,
    private val report: (String, Throwable) -> Unit,
```

with:

```kotlin
    private val services: ServiceControl,
    private val bar: OwnedBar,
    private val report: (String, Throwable) -> Unit,
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
    internal var microphones: () -> Microphone = microphones

    // Every text of Listening reaches the bar through it, so that it knows whose text it shows (design 6.6 of
    // sub-project 7).
    private val bar = OwnedBar(bar)
```

with:

```kotlin
    internal var microphones: () -> Microphone = microphones
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/OwnerSupport.kt`, replace:

```kotlin
internal class AndroidOwnerSupport(
    private val context: Context,
    private val sessions: StateFlow<ScreenSession?>,
```

with:

```kotlin
internal class AndroidOwnerSupport(
    private val context: Context,
    private val sessions: StateFlow<ScreenSession?>,
    private val bar: OwnedBar,
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/OwnerSupport.kt`, replace:

```kotlin
    override suspend fun releaseBar() {
        session()?.releaseFeedback()
    }
```

with:

```kotlin
    // Through the bar's one writer, so that it lets go of only the question's or the hand-over's own text (plan 7F).
    override suspend fun releaseBar() {
        bar.release(BarOwner.Other)
    }
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/OwnerSupport.kt`, replace:

```kotlin
import dev.kestrel.app.R
import dev.kestrel.app.orchestrator.CallState
```

with:

```kotlin
import dev.kestrel.app.R
import dev.kestrel.app.listen.BarOwner
import dev.kestrel.app.listen.OwnedBar
import dev.kestrel.app.orchestrator.CallState
```
In `app/src/main/kotlin/dev/kestrel/app/goal/GoalForeground.kt`, replace:

```kotlin
/**
 * The goal service's coordinator (design 10.6 of sub-project 7; plan 7E).
```

with:

```kotlin
/**
 * The goal service as the conversation screen waits for it (plan 7F): the goal's phase as the coordinator has seen it,
 * whether the service is in the foreground ([running]), and whether Android has answered every start that Kestrel asked
 * for, with the service's start or with a refusal ([settled]). The screen leaves the front only once the start of the
 * goal's work that the owner began there has been answered, so that Android sees that start while Kestrel's screen is in
 * front (design 10.6 of sub-project 7).
 */
internal data class ForegroundState(val goal: GoalPhase? = null, val running: Boolean = false, val settled: Boolean = true)

/**
 * The goal service's coordinator (design 10.6 of sub-project 7; plan 7E).
```

In `app/src/main/kotlin/dev/kestrel/app/goal/GoalForeground.kt`, replace:

```kotlin
    private val appliedNow = MutableStateFlow<Set<ForegroundType>?>(null)
```

with:

```kotlin
    private val appliedNow = MutableStateFlow<Set<ForegroundType>?>(null)
    private val foregroundNow = MutableStateFlow(ForegroundState())

    // How many of Kestrel's starts Android has answered, with the service's start or a refusal.
    private var answered = 0

    /** What the conversation screen waits for before it leaves the front (plan 7F). */
    val foreground: StateFlow<ForegroundState> = foregroundNow.asStateFlow()
```

In `app/src/main/kotlin/dev/kestrel/app/goal/GoalForeground.kt`, replace:

```kotlin
            GoalServiceRules.next(state, event).also {
                state = it.state
                shownNow.value = GoalServiceRules.shown(it.state, sdk)
                postedNow.value = GoalServiceRules.posted(it.state)
            }
```

with:

```kotlin
            GoalServiceRules.next(state, event).also {
                state = it.state
                shownNow.value = GoalServiceRules.shown(it.state, sdk)
                postedNow.value = GoalServiceRules.posted(it.state)
                if (event == GoalServiceEvent.Started || event == GoalServiceEvent.Refused) answered++
                foregroundNow.value = ForegroundState(it.state.goal, it.state.service == ServiceState.RUNNING, answered >= it.state.starts)
            }
```

In `app/src/main/kotlin/dev/kestrel/app/goal/GoalForeground.kt`, replace:

```kotlin
import dev.kestrel.conversation.model.GoalEnding
```

with:

```kotlin
import dev.kestrel.conversation.model.GoalEnding
import dev.kestrel.conversation.model.GoalPhase
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt`, replace:

```kotlin
            "feedback" -> session.showFeedback(intent.requiredText())
```

with:

```kotlin
            // Through the bar's one writer, so that it knows whose text the bar shows (plan 7F).
            "feedback" -> graph.feedbackBar.show(intent.requiredText())
```
A final reply's text holds the bar for its follow-up window's countdown (`OwnedBar`), so the Rules, which decide whether a window opens, also say when none follows a final reply (`Effect.NoFollowUp`), and listening lets the reply's text go then (review A-R2). The owner's Stop that does not come by voice reaches the Rules as `Event.StopSpeaking`, which stops Kestrel's speech as a spoken stop does (`Effect.StopSaying`).

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
    data object Assist : Event
```

with:

```kotlin
    data object Assist : Event

    /**
     * The owner's Stop that did not come by voice: typed, on the conversation screen or on the goal notification (plan 7F).
     * Kestrel's speech stops as for a spoken stop.
     */
    data object StopSpeaking : Event
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
    /** Reply number [reply] of the conversation was only shown, because Kestrel is off: its channel goes on at once. */
    data class EndReply(val reply: Int) : Effect
```

with:

```kotlin
    /** Reply number [reply] of the conversation was only shown, because Kestrel is off: its channel goes on at once. */
    data class EndReply(val reply: Int) : Effect

    /**
     * No follow-up window follows final reply number [reply] (design 6.6 of sub-project 7): it was not spoken to its end,
     * Kestrel was or turned off, a newer reply came, or no window could open. Its text no longer waits on the bar for a
     * countdown (review A-R2 of plan 7F).
     */
    data class NoFollowUp(val reply: Int) : Effect
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
    fun next(state: State, event: Event): Step = silenceTimer(state, event, endLateAnswers(endFollowUp(state, react(state, event))))
```

with:

```kotlin
    fun next(state: State, event: Event): Step =
        silenceTimer(state, event, endLateAnswers(endFollowUp(state, withoutFollowUp(state, react(state, event)))))
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
        Event.Assist -> assist(state)
```

with:

```kotlin
        Event.Assist -> assist(state)
        Event.StopSpeaking -> Step(state, listOfNotNull(Effect.StopSaying.takeIf { state.speaking }))
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
        if (state.mode == Mode.OFF) return Step(state, listOf(shown, Effect.EndReply(event.reply)))
```

with:

```kotlin
        if (state.mode == Mode.OFF) return Step(state, listOfNotNull(shown, Effect.EndReply(event.reply), finalReply?.let { Effect.NoFollowUp(it) }))
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
    // Whether a follow-up window may open or stay open:
```

with:

```kotlin
    // Review A-R2 of plan 7F: the final reply that Kestrel was speaking, or had spoken, is no longer one whose end may open
    // a window, and the step opened none for it, so its text no longer waits on the bar for a countdown.
    private fun withoutFollowUp(before: State, step: Step): Step {
        val reply = before.finalReply ?: return step
        if (step.state.finalReply == reply) return step
        if (step.effects.any { it is Effect.Show && (it.feedback as? Feedback.FollowUp)?.reply == reply }) return step
        return step.copy(effects = step.effects + Effect.NoFollowUp(reply))
    }

    // Whether a follow-up window may open or stay open:
```

In `app/src/main/kotlin/dev/kestrel/app/listen/VoicePath.kt`, replace:

```kotlin
    /** The conversation's question number [question] ended: the voice no longer waits for its answer. */
    fun withdraw(question: Int)
```

with:

```kotlin
    /** The conversation's question number [question] ended: the voice no longer waits for its answer. */
    fun withdraw(question: Int)

    /**
     * The owner's Stop that did not come by voice (plan 7F): the Orchestrator stops Kestrel's speech as for a spoken stop,
     * which counts in [speechStops].
     */
    fun stopSpeaking()
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
        override fun withdraw(question: Int) = send(Event.ChannelWithdraws(question))
```

with:

```kotlin
        override fun withdraw(question: Int) = send(Event.ChannelWithdraws(question))

        override fun stopSpeaking() = send(Event.StopSpeaking)
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
            is Effect.EndReply -> replies[effect.reply]?.complete(Unit)
```

with:

```kotlin
            is Effect.EndReply -> replies[effect.reply]?.complete(Unit)
            // The final reply's text no longer waits for a countdown, so a goal's progress may follow it (review A-R2).
            is Effect.NoFollowUp -> bar.release(BarOwner.FinalReply(effect.reply))
```

- [ ] **Step 5: Kestrel's own voice while it is off**

Create `app/src/main/kotlin/dev/kestrel/app/conversation/OffVoice.kt`:

```kotlin
package dev.kestrel.app.conversation

import android.content.Context
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.VoiceEngines
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.speak.VoicePlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** Kestrel's own voice while no voice session is open (design 10.6 and 11 of sub-project 7). */
internal interface OffVoice {
    /** Speaks [text] and returns once it has been spoken, stopped or refused. */
    suspend fun speak(text: String)

    /** Stops the words that it speaks now, as the owner's Stop does (plan 7F). */
    fun stop()
}

/**
 * Kestrel's voice with listening off (design 10.6 and 11 of sub-project 7; plan 7F). With "Speak replies to typed commands"
 * on, a typed conversation's words reach the voice channel while Kestrel is off, and this speaks them with the owner's voice
 * and speed ([options]), on the assistant stream as Kestrel always speaks (spec 8.4), inside [foreground], which adds
 * mediaPlayback to the goal's service as Android 17 asks of audio in the background (plan 7E). The voice loads for the
 * first reply and closes [idleMillis] after the last one, so that a goal's words and its report load it once. A voice
 * that does not load is reported by its class, and the words stay on the bar. One reply at a time, in order.
 */
internal class AppOffVoice(
    private val context: Context,
    private val engines: VoiceEngines,
    private val options: suspend () -> VoiceOptions,
    private val foreground: suspend (suspend () -> Unit) -> Unit,
    private val scope: CoroutineScope,
    private val report: (String, Throwable) -> Unit,
    private val idleMillis: Long = IDLE_MILLIS,
) : OffVoice {
    private class Loaded(val options: VoiceOptions, val synthesizer: Synthesizer, val player: VoicePlayer)

    private val mutex = Mutex()

    // Changed under the mutex; read by stop() without it.
    @Volatile
    private var loaded: Loaded? = null
    private var closing: Job? = null
    private val spoken = AtomicInteger()

    /** How many replies it has spoken in this process, for the device tests. */
    val replies: Int get() = spoken.get()

    override suspend fun speak(text: String) {
        mutex.withLock {
            closing?.cancel()
            val voice = try {
                voiceFor(options())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Loading Kestrel's voice with listening off", e)
                return
            }
            foreground { voice.player.speak(text).await() }
            spoken.incrementAndGet()
            closing = scope.launch {
                delay(idleMillis)
                mutex.withLock { unload() }
            }
        }
    }

    private suspend fun voiceFor(wanted: VoiceOptions): Loaded {
        loaded?.takeIf { it.options == wanted }?.let { return it }
        unload()
        val synthesizer = withContext(Dispatchers.Default) { engines.synthesizer(wanted) }
        return Loaded(wanted, synthesizer, VoicePlayer(context, synthesizer)).also { loaded = it }
    }

    // As VoiceSession.close does: the player stops using the voice before the voice closes.
    private suspend fun unload() {
        val current = loaded ?: return
        loaded = null
        withContext(NonCancellable) {
            current.player.close()
            current.synthesizer.close()
        }
    }

    override fun stop() {
        loaded?.player?.stop()
    }

    override fun toString(): String = "AppOffVoice(loaded=${loaded != null}, replies=$replies)"

    companion object {
        /** How long the voice stays loaded after the last reply. */
        const val IDLE_MILLIS = 30_000L
    }
}
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/OwnerChannel.kt`, replace:

```kotlin
    /** Kestrel's words: spoken by the voice channel, and shown on the bar and in the transcript. */
    data class Said(val text: String) : ChannelOutput {
        override fun toString(): String = "Said(${text.length} chars)"
    }
```

with:

```kotlin
    /**
     * Kestrel's words: spoken by the voice channel, and shown on the bar and in the transcript. [ownVoice]: the engine
     * routed them while Kestrel was off, as a typed conversation's words with "Speak replies to typed commands" on, so that
     * Kestrel's own voice speaks them, never a voice session (design 11 of sub-project 7; plan 7F).
     */
    data class Said(val text: String, val ownVoice: Boolean = false) : ChannelOutput {
        override fun toString(): String = "Said(${text.length} chars, ownVoice=$ownVoice)"
    }
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/ConversationOwner.kt`, replace:

```kotlin
    // The voice's copy of a typed conversation's output: a final reply becomes words, because the owner typed (design 11).
    private fun spokenCopy(output: ChannelOutput): ChannelOutput = if (output is ChannelOutput.Report) ChannelOutput.Said(output.text) else output
```

with:

```kotlin
    // The voice's copy of a typed conversation's output: a final reply becomes words, because the owner typed (design 11),
    // and the words say whether Kestrel's own voice speaks them, because Kestrel is off as they are routed (plan 7F): the
    // voice channel follows this decision, never the listening mode at the moment it plays them.
    private fun spokenCopy(output: ChannelOutput): ChannelOutput = when (output) {
        is ChannelOutput.Shown -> output
        else -> ChannelOutput.Said(textOf(output), ownVoice = !voiceOn.value)
    }
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/channel/ConversationOwner.kt`, replace:

```kotlin
                voice.show(ChannelOutput.Said(asked.spoken))
```

with:

```kotlin
                voice.show(ChannelOutput.Said(asked.spoken, ownVoice = !voiceOn.value))
```
In `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, replace:

```kotlin
    private val permissions: PermissionDialog,
    private val audioSettleMillis: Long = AUDIO_SETTLE_MILLIS,
) : OwnerChannel {
```

with:

```kotlin
    private val permissions: PermissionDialog,
    private val offVoice: OffVoice,
    private val audioSettleMillis: Long = AUDIO_SETTLE_MILLIS,
) : OwnerChannel {
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, replace:

```kotlin
            is ChannelOutput.Said -> inTurn { voice.say(output.text) }
```

with:

```kotlin
            // The engine decided how the words are spoken when it routed them (`ConversationOwner`): Kestrel's own voice
            // speaks only a typed conversation's words that it routed while Kestrel was off (design 10.6 and 11 of
            // sub-project 7), in their turn among the spoken outputs, and a stop drops them as it drops the others.
            is ChannelOutput.Said -> inTurn { if (output.ownVoice) offVoice.speak(output.text) else voice.say(output.text) }
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, replace:

```kotlin
    private suspend fun inTurn(speak: suspend () -> Unit) {
        val came = voice.speechStops
        speaking.withLock { if (voice.speechStops == came) speak() }
    }
```

with:

```kotlin
    private suspend fun inTurn(speak: suspend () -> Unit) {
        val came = voice.speechStops
        val ownCame = ownStops.get()
        speaking.withLock { if (voice.speechStops == came && ownStops.get() == ownCame) speak() }
    }

    /**
     * The owner's Stop that does not come by voice, typed, on the screen or on the notification (plan 7F): it is the same
     * stop as a spoken one. The Orchestrator stops the voice session's speech (`VoicePath.stopSpeaking`, its
     * `Effect.StopSaying`), Kestrel's own voice stops the words that it speaks, and the spoken outputs that wait for their
     * turn are dropped.
     */
    fun stopSpeech() {
        ownStops.incrementAndGet()
        voice.stopSpeaking()
        offVoice.stop()
    }
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, replace:

```kotlin
    private val speaking = Mutex()
```

with:

```kotlin
    private val speaking = Mutex()

    // How many times the owner's Stop that did not come by voice stopped Kestrel's speech (plan 7F).
    private val ownStops = AtomicLong()
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, replace:

```kotlin
import kotlinx.coroutines.withContext
```

with:

```kotlin
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
            onStep = { event, state, step -> conversationSteps?.invoke(event, state, step) },
```

with:

```kotlin
            onStep = { event, state, step ->
                // Plan 7F: the owner's Stop that does not come by voice (typed, on the screen or on the notification) is the
                // same stop as a spoken one: the voice session's speech and Kestrel's own voice stop, and their waiting words go.
                if (event is ConversationEvent.Input && event.input.intent == InputIntent.Stop) voiceChannel.stopSpeech()
                conversationSteps?.invoke(event, state, step)
            },
```
In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
    /** The conversation's voice (design 11 of sub-project 7), over the Orchestrator. */
    private val voiceChannel: VoiceChannel by lazy {
        VoiceChannel(listening.voicePath, ownerSupport, cardDesk, ownerSupport, ownerSupport, PermissionDialog { permissions -> PermissionActivity.ask(appContext, permissions) })
    }
```

with:

```kotlin
    /** Android's permission dialog for Kestrel's own permissions (design D12 of sub-project 5), which both channels show. */
    private val permissionDialog = PermissionDialog { permissions -> PermissionActivity.ask(appContext, permissions) }

    /**
     * Kestrel's own voice while it is off, for a typed conversation's spoken replies (design 10.6 and 11 of sub-project 7),
     * inside the goal service's mediaPlayback; the device tests read how many replies it spoke.
     */
    internal val offVoice: AppOffVoice by lazy {
        AppOffVoice(
            context = appContext,
            engines = voice,
            options = { settings.get(SettingKeys.VOICE).options() },
            foreground = { block -> goalForeground.whileSpeaking(block) },
            scope = scope,
            report = reportFailure,
        )
    }

    /** The conversation's voice (design 11 of sub-project 7), over the Orchestrator, with its own voice while Kestrel is off. */
    private val voiceChannel: VoiceChannel by lazy {
        VoiceChannel(listening.voicePath, ownerSupport, cardDesk, ownerSupport, ownerSupport, permissionDialog, offVoice)
    }
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
        scope.launch { goalForeground.posted.collect { notice -> GoalNotification.post(appContext, notice) } }
    }
```

with:

```kotlin
        scope.launch { goalForeground.posted.collect { notice -> GoalNotification.post(appContext, notice) } }
        // Design 10.5 of sub-project 7: the bar shows a goal's progress, and that it is held, for both channels, through its
        // one writer.
        scope.launch { GoalBar.follow(engine.view) { text -> feedbackBar.progress(text) } }
    }
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
import dev.kestrel.app.conversation.ConversationSignals
```

with:

```kotlin
import dev.kestrel.app.conversation.AppOffVoice
import dev.kestrel.app.conversation.ConversationSignals
import dev.kestrel.app.conversation.GoalBar
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

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

with:

```kotlin
            bar = feedbackBar,
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
    internal val cardDesk: CardDesk by lazy { CardDesk(ownerSupport, scope) }
```

with:

```kotlin
    internal val cardDesk: CardDesk by lazy { CardDesk(ownerSupport, scope, notice = { text -> feedbackBar.show(text) }) }

    /**
     * Kestrel's feedback bar with its one writer (design 6.6 and 10.5 of sub-project 7; plan 7F): listening's texts, the
     * text channel's words, the owner's parts' release of a question's text and a goal's progress. One main-thread task per
     * call, in order, so that an earlier text never replaces a later one.
     */
    internal val feedbackBar: OwnedBar by lazy {
        OwnedBar(
            object : FeedbackBar {
                override fun show(text: String, keep: Boolean) {
                    scope.launch(Dispatchers.Main) { screen.value?.showFeedback(text, keep) }
                }

                override fun release() {
                    scope.launch(Dispatchers.Main) { screen.value?.releaseFeedback() }
                }
            },
            scope,
            SystemClock::uptimeMillis,
        )
    }
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
        AndroidOwnerSupport(
            context = appContext,
            sessions = screen,
```

with:

```kotlin
        AndroidOwnerSupport(
            context = appContext,
            sessions = screen,
            bar = feedbackBar,
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
import dev.kestrel.app.listen.FeedbackBar
```

with:

```kotlin
import dev.kestrel.app.listen.FeedbackBar
import dev.kestrel.app.listen.OwnedBar
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.store.HistorySearch
```

with:

```kotlin
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.store.HistorySearch
```

- [ ] **Step 6: Run the tests**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.conversation.*`
Expected: BUILD SUCCESSFUL; `GoalBarTest` 3, `OwnedBarTest` 5, `VoiceChannelTest` 12, `TextChannelTest` 10 and `CardDeskTest` 7 tests pass.

Run: `scripts\gradle.ps1 :core:conversation:test :app:testDebugUnitTest :app:assembleDebug; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL; `:app` JVM A₄ + 13, `:core:conversation` C₄ + 1.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/kestrel/app/conversation/GoalBar.kt app/src/main/kotlin/dev/kestrel/app/conversation/OffVoice.kt app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt app/src/main/kotlin/dev/kestrel/app/listen/OwnedBar.kt app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt app/src/main/kotlin/dev/kestrel/app/conversation/OwnerSupport.kt app/src/main/kotlin/dev/kestrel/app/goal/GoalForeground.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/test/kotlin/dev/kestrel/app/conversation/GoalBarTest.kt app/src/test/kotlin/dev/kestrel/app/conversation/VoiceChannelTest.kt app/src/test/kotlin/dev/kestrel/app/listen/OwnedBarTest.kt app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt app/src/test/kotlin/dev/kestrel/app/conversation/TextChannelTest.kt app/src/test/kotlin/dev/kestrel/app/goal/GoalForegroundTest.kt app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt app/src/main/kotlin/dev/kestrel/app/listen/VoicePath.kt app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt core/conversation/src/main/kotlin/dev/kestrel/conversation/channel core/conversation/src/test/kotlin/dev/kestrel/conversation/channel/ConversationOwnerTest.kt
git commit -m "feat(app): the bar has one writer, which shows a goal's progress and its hold, Kestrel speaks a typed conversation's replies with its own voice while it is off, and the screen can read when Android answered the goal service's start" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: The screen's models

Design 10.1 of sub-project 7: the conversation screen holds no conversation state of its own; it draws the conversation's view. Its few rules are Android-free here: `Transcript` draws records as lines (the owner's typed and spoken words, Kestrel's words and questions, steering, the goal's checklist as one card, the final answers, the line where the conversation was continued and the line where it ended), and `ConversationScreenRules` decides what the screen shows and what it does: Stop while a goal works, Continue and Cancel with the hold's way to go on while it is held, "New conversation" or "Stop and start new", the transcript of a conversation that just ended, and when the screen leaves the front (see "Changes to the design"): only once the goal that the owner began on it acts and Android has answered the goal service's start (Task 5's `ForegroundState`), so that Android sees the start while the screen is in front (design 10.6). `ConversationScreenModel` is the screen's one reducer: it takes every input (the view, the goal service, the owner's inputs from the screen, the microphone, "New conversation" and whether the screen is in front) and applies the rules to the newest of them after each one, so that no order of their arrival keeps the screen in front of a working goal. A button on the screen's copy of a card reaches the conversation as an input (Task 4), so it arms the screen as typed words do.

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/conversation/screen/Transcript.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationScreenRules.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationScreenModel.kt`
- Test: create `app/src/test/kotlin/dev/kestrel/app/conversation/screen/TranscriptTest.kt`, `app/src/test/kotlin/dev/kestrel/app/conversation/screen/ConversationScreenRulesTest.kt`, `app/src/test/kotlin/dev/kestrel/app/conversation/screen/ConversationScreenModelTest.kt`

**Interfaces:**
- Consumes: plan 7B's `CommandRecord`, `RecordLine`, `LineKind`, `InputChannel`, `InputIntent`, `ChannelAnswer`, `ConversationId`, `ConversationView` (`id`, `command`, `goal`, `asking`, `records`, `continuedAtWall`, `quiet`, `hold`, and plan 7E's `waitsBehindCommand`), `QuietClock`, `GoalPhase`, `QuestionKind`; plan 7A's `Checklist`, `ItemState`; plan 7E's `ConversationTexts.held(hold)` and `GO_ON`; Task 1's `OwnerCard`; Task 5's `ForegroundState`.
- Produces: `internal enum class OwnerLine { COMMAND, ANSWER, STEERING, TOO_LATE, CONTINUE }`; `internal sealed interface TranscriptLine` (`Owner(kind, text, channel, atWall)`, `Kestrel(text, asked)`, `Answer(text)`, `Checklist(items, done)`, `Note(text)`, `LeftOut(lines, data)`, `Continued(atWall)`, `Ended(atWall)`); `internal data class ChecklistRow(text, state)`; `internal object Transcript { fun of(records, continuedAtWall: Long? = null, endedAtWall: Long? = null): List<TranscriptLine> }`; `internal sealed interface GoalControls` (`None`, `Working(behindCommand)`, `Held(how)`); `internal data class ScreenUi(lines, goal, stopAndNew, card)`; `internal data class ScreenState(id, records, continuedAtWall, endedAtWall, armedAt, newAfterStop, clearAtClose)`; `internal sealed interface ScreenEffect` (`Leave`, `Submit(intent)`); `internal data class ScreenStep(state, effects)`; `internal object ConversationScreenRules` with `ui(state, view, card): ScreenUi`, `viewed(state, view, foreground, nowWall): ScreenStep`, `sent(state, intent, view): ScreenState`, `listened(state, view): ScreenState`, `left(state): ScreenState`, `newConversation(state, view): ScreenStep` and `leaves(intent, view): Boolean`; `internal class ConversationScreenModel(nowWall: () -> Long)` with `state: StateFlow<ScreenState>`, `effects: Flow<ScreenEffect>`, `viewed(view)`, `foreground(state)`, `sent(intent)`, `listened()`, `inFront(front)` and `newConversation()`.

Counts: `:app` JVM A₅ + 12 (`TranscriptTest` 3, `ConversationScreenRulesTest` 6, `ConversationScreenModelTest` 3), where A₅ is the count after Task 5.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/dev/kestrel/app/conversation/screen/TranscriptTest.kt`:

```kotlin
package dev.kestrel.app.conversation.screen

import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.ChecklistItem
import dev.kestrel.agent.goal.ItemState
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.LineKind
import dev.kestrel.conversation.model.RecordLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The transcript that the conversation screen draws from records (design 10.1 of sub-project 7). */
class TranscriptTest {
    @Test
    fun aRecordShowsTheOwnersCommandKestrelsLinesItsChecklistAndItsAnswerInOrder() {
        val record = CommandRecord(
            number = 1,
            atWall = 1_000,
            channel = InputChannel.VOICE,
            request = "Plan the trip",
            lines = listOf(
                RecordLine(LineKind.KESTREL_ASKED, "Which day?"),
                RecordLine(LineKind.OWNER_ANSWERED, "Friday", InputChannel.TEXT),
                RecordLine(LineKind.KESTREL_SAID, "On it."),
                RecordLine(LineKind.OWNER_STEERED, "Also book a hotel", InputChannel.VOICE),
                RecordLine(LineKind.OWNER_TOO_LATE, "And a car", InputChannel.VOICE),
                RecordLine(LineKind.OWNER_CONTINUED, "continue", InputChannel.TEXT),
                RecordLine(LineKind.NOTE, "A routine ran"),
            ),
            answer = "Done.",
            checklist = Checklist(listOf(ChecklistItem("c1", "Find a train", ItemState.DONE, step = 3), ChecklistItem("c2", "Book a hotel", ItemState.DOING))),
            droppedLines = 2,
        )

        assertEquals(
            listOf(
                TranscriptLine.Owner(OwnerLine.COMMAND, "Plan the trip", InputChannel.VOICE, 1_000),
                TranscriptLine.LeftOut(lines = 2, data = 0),
                TranscriptLine.Kestrel("Which day?", asked = true),
                TranscriptLine.Owner(OwnerLine.ANSWER, "Friday", InputChannel.TEXT),
                TranscriptLine.Kestrel("On it.", asked = false),
                TranscriptLine.Owner(OwnerLine.STEERING, "Also book a hotel", InputChannel.VOICE),
                TranscriptLine.Owner(OwnerLine.TOO_LATE, "And a car", InputChannel.VOICE),
                TranscriptLine.Owner(OwnerLine.CONTINUE, "continue", InputChannel.TEXT),
                TranscriptLine.Note("A routine ran"),
                TranscriptLine.Checklist(listOf(ChecklistRow("Find a train", ItemState.DONE), ChecklistRow("Book a hotel", ItemState.DOING)), done = 1),
                TranscriptLine.Answer("Done."),
            ),
            Transcript.of(listOf(record)),
        )
    }

    @Test
    fun aContinuedConversationShowsWhereItWasContinuedAndAnEndedOneWhereItEnded() {
        val old = CommandRecord(1, 1_000, InputChannel.TEXT, "Find the restaurant")
        val new = CommandRecord(2, 90_000, InputChannel.VOICE, "What was its address?")

        val lines = Transcript.of(listOf(old, new), continuedAtWall = 80_000, endedAtWall = 95_000)

        assertEquals(TranscriptLine.Continued(80_000), lines[1])
        assertEquals(TranscriptLine.Owner(OwnerLine.COMMAND, "What was its address?", InputChannel.VOICE, 90_000), lines[2])
        assertEquals(TranscriptLine.Ended(95_000), lines.last())
        // Continued with no command since: the line comes after the last command.
        assertEquals(
            listOf(TranscriptLine.Owner(OwnerLine.COMMAND, "Find the restaurant", InputChannel.TEXT, 1_000), TranscriptLine.Continued(80_000)),
            Transcript.of(listOf(old), continuedAtWall = 80_000),
        )
    }

    @Test
    fun linesPrintNoWords() {
        val record = CommandRecord(
            number = 1,
            atWall = 0,
            channel = InputChannel.TEXT,
            request = "Text Mila the secret",
            lines = listOf(RecordLine(LineKind.KESTREL_SAID, "a secret said")),
            answer = "a secret answer",
            checklist = Checklist(listOf(ChecklistItem("c1", "a secret step", ItemState.TODO))),
        )

        assertFalse(Transcript.of(listOf(record)).joinToString().contains("secret"))
    }
}
```

Create `app/src/test/kotlin/dev/kestrel/app/conversation/screen/ConversationScreenRulesTest.kt`:

```kotlin
package dev.kestrel.app.conversation.screen

import dev.kestrel.app.goal.ForegroundState
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.machine.HoldReason
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.GoalHold
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the conversation screen shows and does (design 10.1 and 10.2 of sub-project 7; plan 7F). */
class ConversationScreenRulesTest {
    private val id = ConversationId("0".repeat(32))
    private val other = ConversationId("1".repeat(32))
    private val none = ForegroundState()
    private val inForeground = ForegroundState(GoalPhase.ACTING, running = true, settled = true)

    @Test
    fun aTypedCommandLeavesTheScreenOnlyOnceAndroidAnsweredTheServicesStart() {
        val idle = ConversationView(id = id, command = 1, quiet = QuietClock.Running(1, 0))
        val armed = ConversationScreenRules.sent(ScreenState(), InputIntent.Command("Open Viber"), idle)
        val acting = idle.copy(command = 2, goal = GoalPhase.ACTING, quiet = QuietClock.Held(HoldReason.GOAL))

        // The coordinator has not seen the goal yet, or Android has not answered its start: the screen stays in front.
        assertEquals(emptyList<ScreenEffect>(), ConversationScreenRules.viewed(armed, acting, none, nowWall = 0).effects)
        val starting = ForegroundState(GoalPhase.ACTING, running = false, settled = false)
        assertEquals(emptyList<ScreenEffect>(), ConversationScreenRules.viewed(armed, acting, starting, nowWall = 0).effects)

        val started = ConversationScreenRules.viewed(armed, acting, inForeground, nowWall = 0)
        assertEquals(listOf(ScreenEffect.Leave), started.effects)
        // Once only: the goal's next view moves nothing.
        assertEquals(emptyList<ScreenEffect>(), ConversationScreenRules.viewed(started.state, acting.copy(step = 2), inForeground, nowWall = 0).effects)
    }

    @Test
    fun aRequestWithoutAGoalKeepsTheScreenAndTheOwnersActionEndsWithItsReply() {
        val idle = ConversationView(id = id, command = 1, quiet = QuietClock.Running(1, 0))
        // "Remember that …" is Kestrel's own request: the screen stays and shows its reply.
        assertEquals(ScreenState(), ConversationScreenRules.sent(ScreenState(), InputIntent.Remember("door code", "4521"), idle))

        // A command that starts no goal, as without a provider profile: its reply ends the wait for work.
        val armed = ConversationScreenRules.sent(ScreenState(), InputIntent.Command("Open Viber"), idle)
        val replied = ConversationScreenRules.viewed(armed, idle.copy(command = 2, quiet = QuietClock.Running(2, 0)), none, nowWall = 0).state
        assertNull(replied.armedAt)
        // A later goal that a spoken command started while the screen is in front moves nothing.
        val later = ConversationScreenRules.viewed(replied, idle.copy(command = 3, goal = GoalPhase.ACTING), inForeground, nowWall = 0)
        assertEquals(emptyList<ScreenEffect>(), later.effects)
        // The microphone waits for the spoken command's goal as a typed command does.
        val listening = ConversationScreenRules.listened(replied, idle.copy(command = 3))
        assertEquals(listOf(ScreenEffect.Leave), ConversationScreenRules.viewed(listening, idle.copy(command = 4, goal = GoalPhase.ACTING), inForeground, nowWall = 0).effects)
        assertNull(ConversationScreenRules.left(listening).armedAt)
    }

    @Test
    fun theGoalsControlsFollowItsPhaseAndAHeldGoalSaysHowToGoOn() {
        fun goal(view: ConversationView) = ConversationScreenRules.ui(ScreenState(), view, null).goal

        assertEquals(GoalControls.None, goal(ConversationView(id = id)))
        assertEquals(GoalControls.Working(), goal(ConversationView(id = id, goal = GoalPhase.ASKING)))
        assertEquals(GoalControls.Working(), goal(ConversationView(id = id, goal = GoalPhase.HANDED_OVER)))
        // Plan 7E: while the round waits behind the owner's instant command, the screen says so where the goal shows.
        assertEquals(GoalControls.Working(behindCommand = true), goal(ConversationView(id = id, goal = GoalPhase.ACTING, waitsBehindCommand = true)))
        assertEquals(GoalControls.Held(ConversationTexts.LEAVE_KESTREL), goal(ConversationView(id = id, goal = GoalPhase.INTERRUPTED, hold = GoalHold.KESTREL_SCREEN)))
        assertEquals(GoalControls.Held(ConversationTexts.held(GoalHold.BACKGROUND_REFUSED)), goal(ConversationView(id = id, goal = GoalPhase.INTERRUPTED, hold = GoalHold.BACKGROUND_REFUSED)))
        assertEquals(GoalControls.Held(ConversationTexts.GO_ON), goal(ConversationView(id = id, goal = GoalPhase.PAUSED_AT_LIMIT, hold = GoalHold.LIMIT)))
        assertFalse(ConversationScreenRules.ui(ScreenState(), ConversationView(id = id), null).stopAndNew)
        assertTrue(ConversationScreenRules.ui(ScreenState(), ConversationView(id = id, goal = GoalPhase.ACTING), null).stopAndNew)
    }

    @Test
    fun aClosedConversationKeepsItsTranscriptWithItsEndUntilANewOneOpens() {
        val record = CommandRecord(1, 1_000, InputChannel.TEXT, "Open Viber", answer = "Done.")
        var state = ConversationScreenRules.viewed(ScreenState(), ConversationView(id = id, command = 1, records = listOf(record)), none, nowWall = 1_000).state
        state = ConversationScreenRules.viewed(state, ConversationView(command = 1), none, nowWall = 61_000).state

        val lines = ConversationScreenRules.ui(state, ConversationView(command = 1), null).lines
        assertEquals(TranscriptLine.Owner(OwnerLine.COMMAND, "Open Viber", InputChannel.TEXT, 1_000), lines.first())
        assertEquals(TranscriptLine.Ended(61_000), lines.last())

        val next = CommandRecord(1, 70_000, InputChannel.VOICE, "Call Ana")
        state = ConversationScreenRules.viewed(state, ConversationView(id = other, command = 2, records = listOf(next)), none, nowWall = 70_000).state
        assertEquals(
            listOf<TranscriptLine>(TranscriptLine.Owner(OwnerLine.COMMAND, "Call Ana", InputChannel.VOICE, 70_000)),
            ConversationScreenRules.ui(state, ConversationView(), null).lines,
        )
    }

    @Test
    fun newConversationStopsAGoalFirstAndThenStartsAfreshOnAClearScreen() {
        val record = CommandRecord(1, 1_000, InputChannel.TEXT, "Open Viber")
        val working = ConversationView(id = id, command = 1, goal = GoalPhase.ACTING, records = listOf(record))
        val shown = ConversationScreenRules.viewed(ScreenState(), working, inForeground, nowWall = 0).state

        val stop = ConversationScreenRules.newConversation(shown, working)
        assertEquals(listOf(ScreenEffect.Submit(InputIntent.Stop)), stop.effects)
        val ended = ConversationScreenRules.viewed(stop.state, working.copy(goal = null), none, nowWall = 0)
        assertEquals(listOf(ScreenEffect.Submit(InputIntent.NewConversation)), ended.effects)
        val closed = ConversationScreenRules.viewed(ended.state, ConversationView(command = 1), none, nowWall = 5_000)
        assertTrue(ConversationScreenRules.ui(closed.state, ConversationView(), null).lines.isEmpty())

        // Without a goal it closes the open conversation at once; with none open the screen only starts afresh.
        val asked = ConversationScreenRules.newConversation(shown, working.copy(goal = null))
        assertEquals(listOf(ScreenEffect.Submit(InputIntent.NewConversation)), asked.effects)
        val stillOpen = ConversationScreenRules.viewed(asked.state, working.copy(goal = null), none, nowWall = 0).state
        assertEquals(ScreenState(), ConversationScreenRules.viewed(stillOpen, ConversationView(command = 1), none, nowWall = 1).state)
        assertEquals(ScreenStep(ScreenState()), ConversationScreenRules.newConversation(closed.state, ConversationView()))
    }

    @Test
    fun anAnswerLeavesOnlyWhenTheGoalGoesOn() {
        val asking = ConversationView(id = id, command = 1, goal = GoalPhase.ASKING, asking = QuestionKind.FREE)
        val limit = asking.copy(asking = QuestionKind.LIMIT)

        assertTrue(ConversationScreenRules.leaves(InputIntent.Answer(ChannelAnswer.Text("Mila")), asking))
        assertTrue(ConversationScreenRules.leaves(InputIntent.Answer(ChannelAnswer.Yes), limit))
        assertFalse(ConversationScreenRules.leaves(InputIntent.Answer(ChannelAnswer.No), limit))
        assertFalse(ConversationScreenRules.leaves(InputIntent.Answer(ChannelAnswer.Yes), ConversationView(id = id, asking = QuestionKind.SWITCH)))
        assertTrue(ConversationScreenRules.leaves(InputIntent.Continue, ConversationView(id = id, goal = GoalPhase.INTERRUPTED, hold = GoalHold.TOUCH)))
        assertFalse(ConversationScreenRules.leaves(InputIntent.Continue, ConversationView(id = id)))
        assertFalse(ConversationScreenRules.leaves(InputIntent.Stop, asking))
        assertFalse(ConversationScreenRules.leaves(InputIntent.NewConversation, ConversationView()))
    }
}
```

Create `app/src/test/kotlin/dev/kestrel/app/conversation/screen/ConversationScreenModelTest.kt`:

```kotlin
package dev.kestrel.app.conversation.screen

import dev.kestrel.app.goal.ForegroundState
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.InputIntent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The conversation screen's one reducer (plan 7F): no order of its inputs keeps the screen in front of a working goal. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationScreenModelTest {
    private val id = ConversationId("0".repeat(32))
    private val idle = ConversationView(id = id, command = 1, quiet = QuietClock.Running(1, 0))
    private val acting = idle.copy(command = 2, goal = GoalPhase.ACTING)
    private val inForeground = ForegroundState(GoalPhase.ACTING, running = true, settled = true)
    private val model = ConversationScreenModel { 0L }
    private val effects = mutableListOf<ScreenEffect>()

    private fun TestScope.collecting() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.effects.collect { effects += it } }
    }

    // Review A2: the view and the service changed before the screen heard of the owner's input.
    @Test
    fun theScreenLeavesWhenTheOwnersInputArrivesAfterTheGoalAndItsServiceDid() = runTest {
        collecting()
        model.inFront(true)
        model.viewed(idle)

        model.viewed(acting)
        model.foreground(inForeground)
        assertEquals(emptyList<ScreenEffect>(), effects)
        model.sent(InputIntent.Command("Open Viber"))

        assertEquals(listOf(ScreenEffect.Leave), effects)
        assertNull(model.state.value.armedAt)
    }

    @Test
    fun anInputWhileTheScreenIsNotInFrontArmsNothingAndLeavingTheFrontDisarms() = runTest {
        collecting()
        model.viewed(idle)
        model.sent(InputIntent.Command("Open Viber"))
        assertNull(model.state.value.armedAt)

        model.inFront(true)
        model.sent(InputIntent.Command("Open Viber"))
        assertEquals(1, model.state.value.armedAt)
        model.inFront(false)
        model.viewed(acting)
        model.foreground(inForeground)

        assertEquals(emptyList<ScreenEffect>(), effects)
    }

    @Test
    fun stopAndStartNewStopsTheGoalAndThenStartsANewConversationOnce() = runTest {
        collecting()
        model.inFront(true)
        model.viewed(acting)

        model.newConversation()
        assertEquals(listOf(ScreenEffect.Submit(InputIntent.Stop)), effects)
        model.viewed(acting.copy(goal = null))
        model.viewed(acting.copy(goal = null, step = 1))

        assertEquals(listOf(ScreenEffect.Submit(InputIntent.Stop), ScreenEffect.Submit(InputIntent.NewConversation)), effects)
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.conversation.screen.*`
Expected: FAIL: the compiler does not know `Transcript`, `TranscriptLine`, `ConversationScreenRules`, `ConversationScreenModel` and their types.

- [ ] **Step 3: The transcript**

Create `app/src/main/kotlin/dev/kestrel/app/conversation/screen/Transcript.kt`:

```kotlin
package dev.kestrel.app.conversation.screen

import dev.kestrel.agent.goal.ItemState
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.LineKind

/** The owner's kinds of line in a transcript. */
internal enum class OwnerLine { COMMAND, ANSWER, STEERING, TOO_LATE, CONTINUE }

/** One line of a transcript as the conversation screen draws it (design 10.1 and 9.5 of sub-project 7). toString prints no words. */
internal sealed interface TranscriptLine {
    /** The owner's words: a command with its time, an answer, steering or "continue", typed or spoken ([channel]). */
    data class Owner(val kind: OwnerLine, val text: String, val channel: InputChannel?, val atWall: Long? = null) : TranscriptLine {
        override fun toString(): String = "Owner(kind=$kind, text=${text.length} chars, channel=$channel)"
    }

    /** Kestrel's words, or its question when [asked]. */
    data class Kestrel(val text: String, val asked: Boolean) : TranscriptLine {
        override fun toString(): String = "Kestrel(text=${text.length} chars, asked=$asked)"
    }

    /** A command's final answer. */
    data class Answer(val text: String) : TranscriptLine {
        override fun toString(): String = "Answer(text=${text.length} chars)"
    }

    /** A goal's checklist, as one card that updates, and how many of its items are done. */
    data class Checklist(val items: List<ChecklistRow>, val done: Int) : TranscriptLine {
        override fun toString(): String = "Checklist(items=${items.size}, done=$done)"
    }

    /** A note in the record. */
    data class Note(val text: String) : TranscriptLine {
        override fun toString(): String = "Note(text=${text.length} chars)"
    }

    /** How many lines and data notes the record's bounds left out (design 8.1). */
    data class LeftOut(val lines: Int, val data: Int) : TranscriptLine

    /** The conversation was continued here (design 9.6). */
    data class Continued(val atWall: Long) : TranscriptLine

    /** The conversation ended here (design 6.2). */
    data class Ended(val atWall: Long) : TranscriptLine
}

/** One item of a goal's checklist on the screen. toString prints no words. */
internal data class ChecklistRow(val text: String, val state: ItemState) {
    override fun toString(): String = "ChecklistRow(text=${text.length} chars, state=$state)"
}

/**
 * Draws records as the conversation screen's lines (design 10.1 and 9.5 of sub-project 7): each command with its time and
 * the channel that it came on, what the record's bounds left out, Kestrel's words and questions, the owner's answers,
 * steering and "continue", the record's notes, the goal's checklist and the final answer; the line where the conversation
 * was continued, before the first command after it; and the line where it ended, last.
 */
internal object Transcript {
    fun of(records: List<CommandRecord>, continuedAtWall: Long? = null, endedAtWall: Long? = null): List<TranscriptLine> = buildList {
        var continued = continuedAtWall
        for (record in records) {
            continued?.takeIf { record.atWall >= it }?.let {
                add(TranscriptLine.Continued(it))
                continued = null
            }
            addAll(lines(record))
        }
        continued?.let { add(TranscriptLine.Continued(it)) }
        endedAtWall?.let { add(TranscriptLine.Ended(it)) }
    }

    private fun lines(record: CommandRecord): List<TranscriptLine> = buildList {
        add(TranscriptLine.Owner(OwnerLine.COMMAND, record.request, record.channel, record.atWall))
        if (record.droppedLines > 0 || record.droppedData > 0) add(TranscriptLine.LeftOut(record.droppedLines, record.droppedData))
        for (line in record.lines) {
            add(
                when (line.kind) {
                    LineKind.KESTREL_SAID -> TranscriptLine.Kestrel(line.text, asked = false)
                    LineKind.KESTREL_ASKED -> TranscriptLine.Kestrel(line.text, asked = true)
                    LineKind.OWNER_ANSWERED -> TranscriptLine.Owner(OwnerLine.ANSWER, line.text, line.channel)
                    LineKind.OWNER_STEERED -> TranscriptLine.Owner(OwnerLine.STEERING, line.text, line.channel)
                    // Plan 7B: words that came after the goal's final words, which Kestrel asked the owner to say again.
                    LineKind.OWNER_TOO_LATE -> TranscriptLine.Owner(OwnerLine.TOO_LATE, line.text, line.channel)
                    LineKind.OWNER_CONTINUED -> TranscriptLine.Owner(OwnerLine.CONTINUE, line.text, line.channel)
                    LineKind.NOTE -> TranscriptLine.Note(line.text)
                },
            )
        }
        record.checklist?.let { checklist -> add(TranscriptLine.Checklist(checklist.items.map { ChecklistRow(it.text, it.state) }, checklist.done)) }
        record.answer?.let { add(TranscriptLine.Answer(it)) }
    }
}
```

- [ ] **Step 4: The screen's rules**

Create `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationScreenRules.kt`:

```kotlin
package dev.kestrel.app.conversation.screen

import dev.kestrel.app.conversation.OwnerCard
import dev.kestrel.app.goal.ForegroundState
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.InputIntent

/** What the screen shows for the goal: nothing, Stop while it works, or how to go on with Continue and Cancel while it is held. */
internal sealed interface GoalControls {
    data object None : GoalControls

    /** The goal works; [behindCommand] while its round waits until the owner's instant command has ended (plan 7E). */
    data class Working(val behindCommand: Boolean = false) : GoalControls

    data class Held(val how: String) : GoalControls {
        override fun toString(): String = "Held(how=${how.length} chars)"
    }
}

/**
 * The conversation screen as it is drawn (design 10.1 of sub-project 7): its transcript's [lines], the goal's controls,
 * whether "New conversation" reads "Stop and start new" ([stopAndNew]), and the open question's or hand-over's [card],
 * which the screen shows above the field while it is in front.
 */
internal data class ScreenUi(val lines: List<TranscriptLine>, val goal: GoalControls, val stopAndNew: Boolean, val card: OwnerCard?)

/**
 * The screen's own state: the transcript that it shows ([records] of conversation [id], or of the one that ended at
 * [endedAtWall]), the command number at which the owner's last action on the screen asked for work ([armedAt]: the screen
 * leaves once that work's goal works), and the two steps of "Stop and start new" ([newAfterStop], then [clearAtClose]).
 * toString prints no words.
 */
internal data class ScreenState(
    val id: ConversationId? = null,
    val records: List<CommandRecord> = emptyList(),
    val continuedAtWall: Long? = null,
    val endedAtWall: Long? = null,
    val armedAt: Int? = null,
    val newAfterStop: Boolean = false,
    val clearAtClose: Boolean = false,
) {
    override fun toString(): String =
        "ScreenState(open=${id != null}, records=${records.size}, ended=${endedAtWall != null}, armedAt=$armedAt, newAfterStop=$newAfterStop, clearAtClose=$clearAtClose)"
}

/** What the screen does after a step: leave the front, or give the conversation an input of its own. */
internal sealed interface ScreenEffect {
    data object Leave : ScreenEffect

    data class Submit(val intent: InputIntent) : ScreenEffect
}

internal data class ScreenStep(val state: ScreenState, val effects: List<ScreenEffect> = emptyList())

/**
 * The conversation screen's rules (design 10.1 and 10.2 of sub-project 7; plan 7F). The screen draws the conversation's
 * view; a conversation that closes keeps its transcript with the line where it ended until the next one opens, unless the
 * owner asked for a new one. After the owner's command, Continue, microphone or an answer that lets a goal go on, the
 * screen leaves the front once the goal acts and Android has answered the goal service's start, so that Android sees the
 * start while the screen is in front (design 10.6); a reply without a goal keeps the screen.
 */
internal object ConversationScreenRules {
    fun ui(state: ScreenState, view: ConversationView, card: OwnerCard?): ScreenUi = ScreenUi(
        lines = Transcript.of(state.records, state.continuedAtWall, state.endedAtWall),
        goal = when (view.goal) {
            null -> GoalControls.None
            GoalPhase.ACTING, GoalPhase.ASKING, GoalPhase.HANDED_OVER -> GoalControls.Working(view.waitsBehindCommand)
            GoalPhase.PAUSED, GoalPhase.PAUSED_AT_LIMIT, GoalPhase.INTERRUPTED -> GoalControls.Held(view.hold?.let(ConversationTexts::held) ?: ConversationTexts.GO_ON)
        },
        stopAndNew = view.goal != null,
        card = card,
    )

    /** The newest [view], with what is known of the goal service ([foreground]), at [nowWall]. */
    fun viewed(state: ScreenState, view: ConversationView, foreground: ForegroundState, nowWall: Long): ScreenStep {
        var next = when {
            // A new conversation, or the same one: "New conversation" waits for its close only while the same one is open.
            view.id != null -> state.copy(
                id = view.id,
                records = view.records,
                continuedAtWall = view.continuedAtWall,
                endedAtWall = null,
                clearAtClose = state.clearAtClose && view.id == state.id,
            )
            state.id == null -> state
            state.clearAtClose -> ScreenState()
            else -> state.copy(id = null, endedAtWall = nowWall)
        }
        val effects = mutableListOf<ScreenEffect>()
        val armed = next.armedAt
        if (armed != null) {
            // The coordinator saw the goal act too, the service is in the foreground, and Android answered every start.
            if (view.goal == GoalPhase.ACTING && foreground.goal == GoalPhase.ACTING && foreground.running && foreground.settled) {
                next = next.copy(armedAt = null)
                effects += ScreenEffect.Leave
            } else if (view.goal == null && view.command > armed && view.quiet is QuietClock.Running) {
                // The owner's command ended without a goal that works: its reply stays on the screen.
                next = next.copy(armedAt = null)
            }
        }
        if (next.newAfterStop && view.goal == null) {
            next = next.copy(newAfterStop = false, clearAtClose = true)
            effects += ScreenEffect.Submit(InputIntent.NewConversation)
        }
        return ScreenStep(next, effects)
    }

    /** The owner's [intent] reached the conversation from the screen, while [view] was the conversation's view. */
    fun sent(state: ScreenState, intent: InputIntent, view: ConversationView): ScreenState =
        if (leaves(intent, view)) state.copy(armedAt = view.command) else state

    /** The owner tapped the microphone: the spoken command's goal moves the screen as a typed command's does. */
    fun listened(state: ScreenState, view: ConversationView): ScreenState = state.copy(armedAt = view.command)

    /** The screen left the front: what the owner asked of it there is over. */
    fun left(state: ScreenState): ScreenState = state.copy(armedAt = null)

    /** "New conversation", or "Stop and start new" while a goal is open (design 6.8). */
    fun newConversation(state: ScreenState, view: ConversationView): ScreenStep = when {
        view.goal != null -> ScreenStep(state.copy(newAfterStop = true), listOf(ScreenEffect.Submit(InputIntent.Stop)))
        view.id != null -> ScreenStep(state.copy(clearAtClose = true), listOf(ScreenEffect.Submit(InputIntent.NewConversation)))
        else -> ScreenStep(ScreenState())
    }

    /**
     * Whether the owner's [intent] starts or resumes work in the app that he came from: a command, Continue on a held goal,
     * and an answer that lets the goal go on.
     */
    fun leaves(intent: InputIntent, view: ConversationView): Boolean = when (intent) {
        is InputIntent.Command -> true
        InputIntent.Continue -> view.goal != null
        is InputIntent.Answer -> view.goal != null && when (view.asking) {
            QuestionKind.CONTINUE_GOAL, QuestionKind.LIMIT -> intent.answer == ChannelAnswer.Yes
            else -> true
        }
        else -> false
    }
}
```

Create `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationScreenModel.kt`:

```kotlin
package dev.kestrel.app.conversation.screen

import dev.kestrel.app.goal.ForegroundState
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.InputIntent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The conversation screen's one reducer (plan 7F). It takes every input of the screen: the conversation's view, what the
 * goal service's coordinator knows ([foreground]), each input that the owner gave on the screen ([sent], which counts only
 * while the screen is in front), the microphone, "New conversation" and whether the screen is in front. After each one it
 * applies [ConversationScreenRules.viewed] to the newest of them, so that no order of their arrival keeps the screen in
 * front of a goal that works. [effects] are what the screen does. It is Android-free; the screen's view model keeps it
 * across configuration changes. Any thread may call it.
 */
internal class ConversationScreenModel(private val nowWall: () -> Long) {
    private val lock = Any()
    private var view = ConversationView()
    private var service = ForegroundState()
    private var front = false
    private val stateNow = MutableStateFlow(ScreenState())
    private val pending = Channel<ScreenEffect>(Channel.UNLIMITED)

    val state: StateFlow<ScreenState> = stateNow.asStateFlow()

    /** What the screen does, in order: leave the front, or give the conversation an input of its own. */
    val effects: Flow<ScreenEffect> = pending.receiveAsFlow()

    fun viewed(view: ConversationView) = step {
        this.view = view
        it
    }

    fun foreground(state: ForegroundState) = step {
        service = state
        it
    }

    fun sent(intent: InputIntent) = step { if (front) ConversationScreenRules.sent(it, intent, view) else it }

    fun listened() = step { ConversationScreenRules.listened(it, view) }

    fun inFront(inFront: Boolean) = step {
        front = inFront
        if (inFront) it else ConversationScreenRules.left(it)
    }

    fun newConversation() = synchronized(lock) {
        val asked = ConversationScreenRules.newConversation(stateNow.value, view)
        asked.effects.forEach(pending::trySend)
        settle(asked.state)
    }

    override fun toString(): String = "ConversationScreenModel(${stateNow.value}, front=$front)"

    private fun step(change: (ScreenState) -> ScreenState) = synchronized(lock) { settle(change(stateNow.value)) }

    private fun settle(state: ScreenState) {
        val step = ConversationScreenRules.viewed(state, view, service, nowWall())
        stateNow.value = step.state
        step.effects.forEach(pending::trySend)
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.conversation.screen.*`
Expected: BUILD SUCCESSFUL; `TranscriptTest` 3, `ConversationScreenRulesTest` 6 and `ConversationScreenModelTest` 3 tests pass.

Run: `scripts\gradle.ps1 :app:testDebugUnitTest; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL; `:app` JVM A₅ + 12.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/kestrel/app/conversation/screen app/src/test/kotlin/dev/kestrel/app/conversation/screen
git commit -m "feat(app): the conversation screen's transcript and rules, which leave the front only once the owner's goal works" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 7: The screen's views

Design 10.1 of sub-project 7: the conversation screen shows the transcript (the owner's typed and spoken inputs with a mark that says which, Kestrel's words, questions and answers, steering, the goal's checklist as one card that updates, the final answers, and a line where the conversation ended or was continued); the field ("Tell Kestrel what to do") with Send and the microphone; Stop while a goal works, and Continue and Cancel while it is held; and in the top bar "New conversation" (or "Stop and start new") and the settings. While the setup checklist is not done, a banner "Finish setting up Kestrel" opens it. The views are thin and hold no state but the field's words: they draw Task 6's `ScreenUi` and call `ConversationActions`. They have no icons (no new library), and every button has its own label, so that Voice Access and TalkBack can name it. The menu with "Past conversations" comes with them in Task 11.

The open card shows above the field while the screen is in front (Task 1's card desk). Its buttons ignore taps for the card's first 600 ms, as the overlay's do (design D32 item 21 of sub-project 5), keyed by the card, so that a tap aimed at one question never answers the next one. A card that only the overlay answers (a confirmation, a hand-over or a pick) shows its words with one button, "Show the app", which takes the screen to the back, where the overlay's card answers it (design 12.2 and 12.3). A held goal's buttons are Cancel, then Continue, as on the goal notification (plan 7E). The field holds up to four lines, and Enter sends. Every window of the screen's own, its dialogs and menus too, drops a touch that passed through another app's window (design 12.2): `ScreenGuard` does it for the activity's window (Task 8) and for each dialog and menu (`GuardWindow`).

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationViews.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/screen/TranscriptViews.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ScreenGuard.kt`, `app/src/main/res/values/strings_conversation.xml`
- Test: create `app/src/androidTest/kotlin/dev/kestrel/app/conversation/screen/ConversationViewsTest.kt`

**Interfaces:**
- Consumes: Task 6's `ScreenUi`, `GoalControls`, `TranscriptLine`, `OwnerLine`, `ChecklistRow`; Task 1's `OwnerCard` (`answersOnScreen`), `CardChoice`, `CARD_ARMING_MILLIS` and the strings `card_*`; plan 7E's `ConversationTexts.date(wall, zone)` and `GoalTexts.BEHIND_COMMAND`, the goal notification's words while the round waits behind the owner's instant command.
- Produces: `internal class ConversationActions(send, speak, stop, continueGoal, cancel, newConversation, settings, setup, showApp, choose: (OwnerCard, CardChoice) -> Unit)`; `internal object ScreenGuard { fun guard(root: View) }` and `@Composable internal fun GuardWindow()`; `@Composable internal fun ConversationScreen(ui: ScreenUi, field: String, onField: (String) -> Unit, setupNeeded: Boolean, zone: ZoneId, focus: FocusRequester, actions: ConversationActions)`; `@Composable internal fun TranscriptList(lines: List<TranscriptLine>, zone: ZoneId, modifier: Modifier = Modifier)`; `internal fun timeOf(wall: Long, zone: ZoneId): String` ("18:04"); `internal const val FIELD_TAG = "conversation-field"`; the strings `conversation_*`.

Counts: `:app` device tests AD₆ + 6 (`ConversationViewsTest` 6), where AD₆ = AD₁ + 1 is the count after Task 6 (Task 3 adds `RoomRecordArchiveTest`'s test). `:app` JVM unchanged.

- [ ] **Step 1: Write the failing tests**

Create `app/src/androidTest/kotlin/dev/kestrel/app/conversation/screen/ConversationViewsTest.kt`:

```kotlin
package dev.kestrel.app.conversation.screen

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.kestrel.agent.goal.ItemState
import dev.kestrel.app.R
import dev.kestrel.app.conversation.CardChoice
import dev.kestrel.app.conversation.OwnerCard
import dev.kestrel.app.goal.GoalTexts
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.screen.overlay.CARD_ARMING_MILLIS
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The conversation screen's views with Compose tests on kestrel-api37 (design 10.1 and 15.2 of sub-project 7). */
class ConversationViewsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val zone = ZoneId.of("Europe/Belgrade")
    private val done = mutableListOf<String>()
    private val actions = ConversationActions(
        send = { done += "send" },
        speak = { done += "speak" },
        stop = { done += "stop" },
        continueGoal = { done += "continue" },
        cancel = { done += "cancel" },
        newConversation = { done += "new" },
        settings = { done += "settings" },
        setup = { done += "setup" },
        showApp = { done += "show app" },
        choose = { card, choice -> done += "choose ${card.key} $choice" },
    )

    private fun text(@StringRes id: Int, vararg args: Any): String = compose.activity.getString(id, *args)

    private fun show(ui: MutableState<ScreenUi>, setupNeeded: Boolean = false) {
        compose.setContent {
            var words by remember { mutableStateOf("") }
            val focus = remember { FocusRequester() }
            MaterialTheme { ConversationScreen(ui.value, words, { words = it }, setupNeeded, zone, focus, actions) }
        }
    }

    @Test
    fun theTranscriptShowsEachCommandWithItsChannelAndTimeKestrelsWordsAndTheEnd() {
        val at = ZonedDateTime.of(2026, 11, 20, 18, 4, 0, 0, zone).toInstant().toEpochMilli()
        val lines = listOf(
            TranscriptLine.Owner(OwnerLine.COMMAND, "Read my latest SMS", InputChannel.TEXT, at),
            TranscriptLine.Kestrel("Which one?", asked = true),
            TranscriptLine.Owner(OwnerLine.ANSWER, "The newest", InputChannel.VOICE),
            TranscriptLine.Checklist(listOf(ChecklistRow("Open Messages", ItemState.DONE)), done = 1),
            TranscriptLine.Answer("Mila wrote: Ambar at 8."),
            TranscriptLine.Ended(at + 60_000),
        )
        show(mutableStateOf(ScreenUi(lines, GoalControls.None, stopAndNew = false, card = null)))

        compose.onNodeWithText("Read my latest SMS").assertIsDisplayed()
        compose.onNodeWithText("${text(R.string.conversation_typed)} · 18:04").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.conversation_kestrel_asked)).assertIsDisplayed()
        compose.onNodeWithText("${text(R.string.conversation_you_answered)} · ${text(R.string.conversation_spoken)}").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.conversation_checklist, 1, 1)).assertIsDisplayed()
        compose.onNodeWithText("Mila wrote: Ambar at 8.").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.conversation_ended, "18:05")).assertIsDisplayed()
    }

    @Test
    fun aWorkingGoalHasStopAndAHeldOneSaysHowToGoOnWithContinueAndCancel() {
        val ui = mutableStateOf(ScreenUi(emptyList(), GoalControls.Working(behindCommand = true), stopAndNew = true, card = null))
        show(ui)

        compose.onNodeWithText(GoalTexts.BEHIND_COMMAND).assertIsDisplayed()

        compose.onNodeWithText(text(R.string.conversation_stop_and_new)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.conversation_stop)).performClick()
        ui.value = ScreenUi(emptyList(), GoalControls.Held(ConversationTexts.LEAVE_KESTREL), stopAndNew = true, card = null)
        compose.onNodeWithText(ConversationTexts.LEAVE_KESTREL).assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.conversation_stop)).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.conversation_continue)).performClick()
        compose.onNodeWithText(text(R.string.conversation_cancel)).performClick()
        ui.value = ScreenUi(emptyList(), GoalControls.None, stopAndNew = false, card = null)
        compose.onNodeWithText(text(R.string.conversation_new)).performClick()

        assertEquals(listOf("stop", "continue", "cancel", "new"), done)
    }

    @Test
    fun theOpenQuestionShowsItsButtonsAndReplyPutsTheKeyboardOnTheField() {
        val card = OwnerCard(4, "Send it to Mila?", listOf(CardChoice.Yes, CardChoice.No, CardChoice.Reply), question = 4, kind = QuestionKind.YES_NO)
        // A tap in the card's first 600 ms may have been aimed at the card before it (design D32 item 21 of sub-project 5),
        // so the test's clock stands still until that tap is done.
        compose.mainClock.autoAdvance = false
        show(mutableStateOf(ScreenUi(emptyList(), GoalControls.Working(), stopAndNew = true, card = card)))
        compose.mainClock.advanceTimeByFrame()

        compose.onNodeWithText("Send it to Mila?").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.card_no)).performClick()
        compose.mainClock.advanceTimeBy(CARD_ARMING_MILLIS)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText(text(R.string.card_yes)).performClick()
        compose.onNodeWithText(text(R.string.card_reply)).performClick()

        compose.onNodeWithTag(FIELD_TAG).assertIsFocused()
        assertEquals(listOf("choose 4 Yes", "choose 4 Reply"), done)
    }

    @Test
    fun aHeldGoalsCardHasStopThenContinue() {
        val card = OwnerCard(5, "Continue the goal?", listOf(CardChoice.Stop, CardChoice.Continue), question = 5, kind = QuestionKind.CONTINUE_GOAL)
        show(mutableStateOf(ScreenUi(emptyList(), GoalControls.Working(), stopAndNew = true, card = card)))
        compose.mainClock.advanceTimeBy(CARD_ARMING_MILLIS)

        compose.onNodeWithText(text(R.string.card_stop)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.card_continue)).performClick()

        assertEquals(listOf("choose 5 Continue"), done)
    }

    // Design 12.2 and 12.3: only the card over the app answers a confirmation; the screen offers to show the app.
    @Test
    fun aConfirmationsCopyOnlyShowsTheApp() {
        val card = OwnerCard(6, "Send it to Mila?", listOf(CardChoice.Confirm, CardChoice.Cancel), question = 6, kind = QuestionKind.CONFIRM)
        show(mutableStateOf(ScreenUi(emptyList(), GoalControls.Working(), stopAndNew = true, card = card)))
        compose.mainClock.advanceTimeBy(CARD_ARMING_MILLIS)

        compose.onNodeWithText("Send it to Mila?").assertIsDisplayed()
        compose.onAllNodesWithText(text(R.string.card_confirm)).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.conversation_show_app)).performClick()

        assertEquals(listOf("show app"), done)
    }

    @Test
    fun sendWaitsForWordsAndTheBannerOpensTheChecklist() {
        show(mutableStateOf(ScreenUi(emptyList(), GoalControls.None, stopAndNew = false, card = null)), setupNeeded = true)

        compose.onNodeWithText(text(R.string.conversation_empty)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.conversation_send)).assertIsNotEnabled()
        compose.onNodeWithTag(FIELD_TAG).performTextInput("Open Viber")
        compose.onNodeWithText(text(R.string.conversation_send)).assertIsEnabled().performClick()
        compose.onNodeWithText(text(R.string.conversation_speak)).performClick()
        compose.onNodeWithText(text(R.string.conversation_setup)).performClick()
        compose.onNodeWithText(text(R.string.conversation_settings)).performClick()

        assertEquals(listOf("send", "speak", "setup", "settings"), done)
    }
}
```

- [ ] **Step 2: Compile the tests to see them fail**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin`
Expected: FAIL: the compiler does not know `ConversationScreen`, `ConversationActions`, `FIELD_TAG` and the strings `conversation_*`.

- [ ] **Step 3: The strings**

Create `app/src/main/res/values/strings_conversation.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- The conversation screen (design 10.1 of sub-project 7; plan 7F). -->
<resources xmlns:tools="http://schemas.android.com/tools">
    <string name="conversation_title">Kestrel</string>
    <string name="conversation_hint">Tell Kestrel what to do</string>
    <string name="conversation_send">Send</string>
    <string name="conversation_speak">Speak</string>
    <string name="conversation_stop">Stop</string>
    <string name="conversation_continue">Continue</string>
    <string name="conversation_cancel">Cancel</string>
    <string name="conversation_new">New conversation</string>
    <string name="conversation_stop_and_new">Stop and start new</string>
    <string name="conversation_settings">Settings</string>
    <string name="conversation_show_app">Show the app</string>
    <string name="conversation_setup">Finish setting up Kestrel</string>
    <string name="conversation_empty">Type what Kestrel should do, or tap Speak.</string>
    <string name="conversation_typed">Typed</string>
    <string name="conversation_spoken">Spoken</string>
    <string name="conversation_you_answered">You answered</string>
    <string name="conversation_you_added">You added</string>
    <string name="conversation_you_added_too_late">You added, too late for the goal</string>
    <string name="conversation_you_continued">You continued</string>
    <string name="conversation_kestrel_asked">Kestrel asked</string>
    <string name="conversation_checklist">Checklist: %1$d of %2$d done</string>
    <string name="conversation_left_out" tools:ignore="PluralsCandidate">Earlier lines that are not kept: %1$d; data notes that are not kept: %2$d.</string>
    <string name="conversation_continued">Continued on %1$s</string>
    <string name="conversation_ended">Conversation ended at %1$s</string>
    <string name="conversation_item_todo">To do</string>
    <string name="conversation_item_doing">Doing</string>
    <string name="conversation_item_done">Done</string>
    <string name="conversation_item_skipped">Skipped</string>
    <string name="conversation_item_failed">Failed</string>
</resources>
```

- [ ] **Step 4: The views**

Create `app/src/main/kotlin/dev/kestrel/app/conversation/screen/TranscriptViews.kt`:

```kotlin
package dev.kestrel.app.conversation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kestrel.agent.goal.ItemState
import dev.kestrel.app.R
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.model.InputChannel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A transcript's lines (design 10.1 and 9.5 of sub-project 7), the newest at the bottom, which it scrolls to when a line
 * comes. Without lines it says what the screen is for.
 */
@Composable
internal fun TranscriptList(lines: List<TranscriptLine>, zone: ZoneId, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }
    if (lines.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.conversation_empty), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyColumn(modifier, state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(lines) { line -> Line(line, zone) }
    }
}

/** "18:04": a command's time and a conversation's end. */
internal fun timeOf(wall: Long, zone: ZoneId): String = TIME.format(Instant.ofEpochMilli(wall).atZone(zone))

private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

@Composable
private fun Line(line: TranscriptLine, zone: ZoneId) {
    when (line) {
        is TranscriptLine.Owner -> OwnerWords(line, zone)
        is TranscriptLine.Kestrel -> Column(Modifier.fillMaxWidth()) {
            if (line.asked) Text(stringResource(R.string.conversation_kestrel_asked), style = MaterialTheme.typography.labelMedium)
            Text(line.text, style = MaterialTheme.typography.bodyLarge)
        }
        is TranscriptLine.Answer -> Text(line.text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
        is TranscriptLine.Checklist -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.conversation_checklist, line.done, line.items.size), style = MaterialTheme.typography.titleSmall)
                for (item in line.items) Text("${stringResource(stateLabel(item.state))} · ${item.text}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        is TranscriptLine.Note -> Text(line.text, style = MaterialTheme.typography.bodySmall)
        is TranscriptLine.LeftOut -> Text(stringResource(R.string.conversation_left_out, line.lines, line.data), style = MaterialTheme.typography.bodySmall)
        is TranscriptLine.Continued ->
            Text(stringResource(R.string.conversation_continued, ConversationTexts.date(line.atWall, zone)), style = MaterialTheme.typography.labelLarge)
        is TranscriptLine.Ended -> Text(stringResource(R.string.conversation_ended, timeOf(line.atWall, zone)), style = MaterialTheme.typography.labelLarge)
    }
}

// The owner's words on the right, with what they were, whether he typed or spoke them, and a command's time.
@Composable
private fun OwnerWords(line: TranscriptLine.Owner, zone: ZoneId) {
    val kind = when (line.kind) {
        OwnerLine.COMMAND -> null
        OwnerLine.ANSWER -> R.string.conversation_you_answered
        OwnerLine.STEERING -> R.string.conversation_you_added
        OwnerLine.TOO_LATE -> R.string.conversation_you_added_too_late
        OwnerLine.CONTINUE -> R.string.conversation_you_continued
    }
    val channel = line.channel?.let { if (it == InputChannel.TEXT) R.string.conversation_typed else R.string.conversation_spoken }
    val label = listOfNotNull(kind?.let { stringResource(it) }, channel?.let { stringResource(it) }, line.atWall?.let { timeOf(it, zone) }).joinToString(" · ")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelMedium)
        Card { Text(line.text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge) }
    }
}

private fun stateLabel(state: ItemState): Int = when (state) {
    ItemState.TODO -> R.string.conversation_item_todo
    ItemState.DOING -> R.string.conversation_item_doing
    ItemState.DONE -> R.string.conversation_item_done
    ItemState.SKIPPED -> R.string.conversation_item_skipped
    ItemState.FAILED -> R.string.conversation_item_failed
}
```

Create `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationViews.kt`:

```kotlin
package dev.kestrel.app.conversation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.kestrel.app.R
import dev.kestrel.app.conversation.CardChoice
import dev.kestrel.app.conversation.OwnerCard
import dev.kestrel.app.goal.GoalTexts
import dev.kestrel.screen.overlay.CARD_ARMING_MILLIS
import kotlinx.coroutines.delay
import java.time.ZoneId

/** The conversation screen's field, by which the device tests find it. */
internal const val FIELD_TAG = "conversation-field"

/** What the conversation screen's buttons do; `ConversationApp` gives them. */
internal class ConversationActions(
    val send: () -> Unit,
    val speak: () -> Unit,
    val stop: () -> Unit,
    val continueGoal: () -> Unit,
    val cancel: () -> Unit,
    val newConversation: () -> Unit,
    val settings: () -> Unit,
    val setup: () -> Unit,
    val showApp: () -> Unit,
    val choose: (OwnerCard, CardChoice) -> Unit,
)

/**
 * The conversation screen (design 10.1 of sub-project 7), drawn from [ui]: the top bar with "New conversation" (or "Stop
 * and start new") and Settings, the banner while the setup checklist is not done, the transcript, the goal's Stop or its
 * hold's way to go on with Continue and Cancel, the open question's or hand-over's card, and the field with Send and Speak.
 * It holds no state of its own but the field's words, which [field] and [onField] carry, and [focus], which "Reply…" asks
 * for. Every button is a Material button with its own label, so that Voice Access and TalkBack can name it.
 */
@Composable
internal fun ConversationScreen(
    ui: ScreenUi,
    field: String,
    onField: (String) -> Unit,
    setupNeeded: Boolean,
    zone: ZoneId,
    focus: FocusRequester,
    actions: ConversationActions,
) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.conversation_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = actions.newConversation) {
                Text(stringResource(if (ui.stopAndNew) R.string.conversation_stop_and_new else R.string.conversation_new))
            }
            TextButton(onClick = actions.settings) { Text(stringResource(R.string.conversation_settings)) }
        }
        if (setupNeeded) {
            Card(onClick = actions.setup, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(stringResource(R.string.conversation_setup), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
            }
        }
        TranscriptList(ui.lines, zone, Modifier.weight(1f).fillMaxWidth())
        GoalRow(ui.goal, actions)
        ui.card?.let { card -> CardRow(card, onReply = { focus.requestFocus() }, actions = actions) }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = field,
                onValueChange = onField,
                modifier = Modifier.weight(1f).focusRequester(focus).testTag(FIELD_TAG),
                placeholder = { Text(stringResource(R.string.conversation_hint)) },
                // Up to four lines; Enter sends, as the field's one action.
                maxLines = MAX_FIELD_LINES,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (field.isNotBlank()) actions.send() }),
            )
            Button(onClick = actions.send, enabled = field.isNotBlank()) { Text(stringResource(R.string.conversation_send)) }
            OutlinedButton(onClick = actions.speak) { Text(stringResource(R.string.conversation_speak)) }
        }
    }
}

/**
 * Stop while the goal works, with plan 7E's words while its round waits behind the owner's instant command, whose own text
 * the bar shows then; while it is held, how to go on, with Continue and Cancel (design 6.4, 6.5 and 10.1).
 */
@Composable
private fun GoalRow(goal: GoalControls, actions: ConversationActions) {
    when (goal) {
        GoalControls.None -> Unit
        is GoalControls.Working -> Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            if (goal.behindCommand) Text(GoalTexts.BEHIND_COMMAND, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = actions.stop, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.conversation_stop)) }
        }
        is GoalControls.Held -> Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(goal.how, style = MaterialTheme.typography.bodyMedium)
            // Cancel, then Continue, as on the goal notification (plan 7E).
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.cancel) { Text(stringResource(R.string.conversation_cancel)) }
                Button(onClick = actions.continueGoal) { Text(stringResource(R.string.conversation_continue)) }
            }
        }
    }
}

/**
 * The open question or hand-over (design 10.3), on the conversation and on the past conversations' pages. Its buttons
 * ignore taps for the card's first [CARD_ARMING_MILLIS], as the overlay's do, so that a tap aimed at the card before it
 * never answers it, and each tap names its question (Task A2); "Reply…" calls [onReply], which puts the keyboard on the
 * field. A card that only the overlay answers shows its words and "Show the app", which takes the screen to the back
 * (design 12.2).
 */
@Composable
internal fun CardRow(card: OwnerCard, onReply: () -> Unit, actions: ConversationActions) {
    // Keyed by the card's question number, so that a new question's card starts unarmed.
    var armed by remember(card.key) { mutableStateOf(false) }
    LaunchedEffect(card.key) {
        delay(CARD_ARMING_MILLIS)
        armed = true
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(card.text, style = MaterialTheme.typography.bodyLarge)
            if (!card.answersOnScreen) {
                TextButton(onClick = { if (armed) actions.showApp() }) { Text(stringResource(R.string.conversation_show_app)) }
                return@Column
            }
            for (row in card.choices.chunked(ROW_BUTTONS)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (choice in row) {
                        TextButton(onClick = {
                            if (armed) {
                                if (choice == CardChoice.Reply) onReply()
                                actions.choose(card, choice)
                            }
                        }) { Text(labelOf(card, choice)) }
                    }
                }
            }
        }
    }
}

// A pick's button says its choice; every other button says its kind's word, as on the overlay's card.
@Composable
private fun labelOf(card: OwnerCard, choice: CardChoice): String = when (choice) {
    is CardChoice.Pick -> card.picks[choice.number - 1]
    else -> stringResource(checkNotNull(choice.label))
}

private const val ROW_BUTTONS = 3

private const val MAX_FIELD_LINES = 4
```

Create `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ScreenGuard.kt`:

```kotlin
package dev.kestrel.app.conversation.screen

import android.os.Build
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Design 12.2 of sub-project 7: a window of Kestrel's conversation screen drops a touch that passed through another app's
 * window over it, and from Android 14 only accessibility tools may act on its views, which inherit this from [root].
 */
internal object ScreenGuard {
    fun guard(root: View) {
        root.filterTouchesWhenObscured = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) root.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
    }
}

/** Guards the window that holds this composable: a dialog's or a menu's, which are windows of their own (design 12.2). */
@Composable
internal fun GuardWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        ScreenGuard.guard(view.rootView)
        onDispose {}
    }
}
```

- [ ] **Step 5: Compile the tests and the app**

Run: `scripts\gradle.ps1 :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL; `:app` JVM unchanged. `ConversationViewsTest`'s 6 tests run in Task 14.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/kestrel/app/conversation/screen app/src/main/res/values/strings_conversation.xml app/src/androidTest/kotlin/dev/kestrel/app/conversation/screen/ConversationViewsTest.kt
git commit -m "feat(app): the conversation screen's views: the transcript, the goal's buttons, the open question's card and the field" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 8: The conversation screen as the launcher

Design 10.1, 10.2 and D20 of sub-project 7: `ConversationActivity` (Compose, `singleTask`) becomes the launcher's activity; `MainActivity` stays the settings app, leaves the launcher, and opens from the screen's Settings, from "Kestrel, open settings" and from the intents that name a page, as today. While the setup checklist is not done, the screen's banner opens it; the banner reads the checklist through `SetupState`, the one assembly of its status that the settings app uses too. The screen draws the conversation's view and gives the text channel the owner's typed words and buttons. Its state lives in `ConversationScreenViewModel`, which keeps Task 6's `ConversationScreenModel` across configuration changes and feeds it every input: the conversation's view, the goal service's `foreground` (Task 5), the text channel's `sent`, the microphone, "New conversation" and whether the screen is in front. Once the goal that the owner started on it works and its service runs in the foreground, the model asks the screen to leave, and the activity calls `moveTaskToBack(true)`, so that the app he was in comes back; the text channel waits until it has left (`awaitKestrelLeft`, Task 4). The screen's window drops touches that pass through another app's window, and from Android 14 only accessibility tools may act on it (`ScreenGuard`, design 12.2). The app graph adds the text channel to the engine's channels, and both notifications' taps open the screen (plan 7E's `ListeningService.openKestrel`). Now that opening Kestrel shows the screen with its Continue, a goal that Android's refusal holds says design 10.6's "Open Kestrel and tap Continue." (`ConversationTexts.OPEN_AND_CONTINUE`, plan 7E's interfaces) in place of the notification's Continue.

The device tests type every request with this run's marker, note the conversations that they made, and delete only those (`TypedTests.finish()` through Task 3's `deletePast`), so that the owner's own conversations on kestrel-api37 stay. A conversation that was open before a test, such as the owner's own, closes into his list and stays there. A grant of READ_PHONE_STATE in Android's settings does not restart Kestrel, so the settings app has started the call watch as it comes to the front; now that the launcher opens the conversation screen, that rule moves to one part, `PhoneStateGrant` in the app graph, which both activities call as they come to the front (review B-M2).

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationScreenViewModel.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationApp.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationActivity.kt`, `app/src/main/kotlin/dev/kestrel/app/settings/SetupState.kt`, `app/src/main/kotlin/dev/kestrel/app/listen/PhoneStateGrant.kt`
- Modify: `core/conversation/src/main/kotlin/dev/kestrel/conversation/ConversationTexts.kt`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, `app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt`, `app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt`, `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt`, `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`
- Test: modify `app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt`, `app/src/test/kotlin/dev/kestrel/app/goal/GoalNoticesTest.kt`; create `app/src/test/kotlin/dev/kestrel/app/listen/PhoneStateGrantTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/conversation/TypedTests.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/conversation/ConversationScreenTest.kt`

**Interfaces:**
- Consumes: Task 1's `AppGraph.cardDesk` (`open`, `screenInFront`); Task 3's `ConversationEngine.deletePast(id)` and `AppGraph.conversationTimers`; Task 4's `TextChannel`, `TypedConversation`, `ReplyScreen`; Task 5's `AppGraph.feedbackBar`, `offVoice`, `permissionDialog` and `GoalForeground.foreground`; Task 6's `ConversationScreenModel`, `ConversationScreenRules.ui`, `ScreenEffect`; Task 7's `ConversationScreen`, `ConversationActions`, `ScreenGuard`, `FIELD_TAG`; plan 7D's `AppGraph.conversations`, `conversationStore`, `ownerSupport`, `listening` with `assist()`, `MainActivity` with `EXTRA_PAGE` and its call watch after a grant of READ_PHONE_STATE, which moves to `PhoneStateGrant`; `Listening.watchCalls()`; plan 7C's `AndroidConversationClock`; plan 7E's `ListeningService.openKestrel`, `ConversationTexts.CONTINUE_IN_NOTIFICATION` and `held(hold)`, and `GoalNotices`; the settings' `SetupStatusReader`, `StoredSettingsData` (`providerReady`, `agentFreeReviewed`, `downloadChoice`), `checkModelFiles` and `SettingsPage.CHECKLIST`; the androidTest helpers `AgentTests` (`graph`, `ended`, `texts`), `VoiceTests.waitUntil` and `ScreenServiceRule`.
- Produces: `class ConversationActivity : ComponentActivity` with `EXTRA_QUESTION` and `reply(context, question)`; `internal class ConversationScreenViewModel(graph: AppGraph) : ViewModel` with `model`, `view`, `card`, `leaves`, `zone`, `send(text): Boolean`, `input(intent)`, `choose(card, choice)`, `field(hasWords)`, `inFront(front)`, `listen()` and `newConversation()`; `@Composable internal fun ConversationApp(screen, setupNeeded, replyTo, onSettings, onSetup, onShowApp)`; `internal object SetupState` with `stored(status, providerReady, agentFreeReviewed, downloadChoice)`, `models(graph, report)` and `read(graph, context, report)`; `AppGraph.textChannel: TextChannel`, the engine's channels `VOICE` and `TEXT`; `ConversationTexts.OPEN_AND_CONTINUE = "Open Kestrel and tap Continue."` in place of `CONTINUE_IN_NOTIFICATION`; `SetupChecklist.needsSetup(states): Boolean`; `internal class PhoneStateGrant(granted: () -> Boolean, watchCalls: () -> Unit)` with `check()` and `AppGraph.phoneStateGrant`, the one place that starts the call watch after a grant of READ_PHONE_STATE, which `ConversationActivity` and `MainActivity` call as they come to the front (review B-M2); the androidTest helper `internal object TypedTests` with `graph`, `marker`, `marked(words)`, `own(id)`, `prepare(model)`, `finish()`, `bringScreen(screen)`, `screenInFront(screen)`, `send(nodes, words): Int`, `text(id, args)`.

Counts: `:app` JVM A₆ + 2 (`SetupChecklistTest` + 1, `PhoneStateGrantTest` 1), where A₆ is the count after Task 6. Device tests AD₇ + 4 (`ConversationScreenTest` 4), where AD₇ is the count after Task 7.

Since Task 14's first run (the amendment of 2026-11-27 for it), the androidTest helpers differ from this Produces line, which shows Task 8 as it was carried out: `bringScreen(screen, compose)` and `send(compose, words)` take the compose rule (`AndroidComposeTestRule<*, ConversationActivity>`), and `TypedTests.tapSend(compose)` and the helper `ScreenKeyboard` (`rule`, `settle(compose)`) are new (`7f46179a`, `fdfd1a2d`); `ConversationScreenViewModel` has `replyTo` and `reply(question)` (`16518f16`). Step 1's and Step 4's notes say why.

- [ ] **Step 1: Write the failing tests**

In `app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt`, add before its last closing brace:

```kotlin

    // Design 10.1 of sub-project 7: the conversation screen's banner shows while a step is to do or needs attention.
    @Test
    fun theSetupIsNeededWhileAStepIsToDoOrNeedsAttention() {
        assertTrue(SetupChecklist.needsSetup(SetupChecklist.states(nothingSetUp)))
        val optionalOnly = SetupStep.entries.associateWith { if (it == SetupStep.CALLS) StepState.OPTIONAL else StepState.DONE }
        assertFalse(SetupChecklist.needsSetup(optionalOnly))
        assertTrue(SetupChecklist.needsSetup(optionalOnly + (SetupStep.VOICE_ACCESS to StepState.WARNING)))
    }
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/conversation/TypedTests.kt`:

```kotlin
package dev.kestrel.app.conversation

import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.agent.port.ModelPort
import dev.kestrel.app.AppGraph
import dev.kestrel.app.R
import dev.kestrel.app.agent.AgentTests
import dev.kestrel.app.conversation.screen.FIELD_TAG
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.conversation.engine.PastDeletion
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.InputIntent
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * The conversation screen's device tests (design 15.2 of sub-project 7; plan 7F): the screen in front, the owner's typed
 * words in its field and its buttons through Compose's test rule, and plan 5A's scripted model as each round's model, so
 * that no test calls a provider. Kestrel's listening stays off unless a test turns it on, because the screen works with
 * listening off. Kestrel's data on kestrel-api37 holds the owner's own conversations, so every request that these tests
 * type ends with this run's [marker], and [finish] deletes only the conversations that the tests made ([own]).
 */
internal object TypedTests {
    val graph: AppGraph get() = AgentTests.graph

    /** This run's mark, at the end of every request that the tests type, so that a test finds its own conversations. */
    val marker: String = "7f-" + UUID.randomUUID().toString().take(8)

    private val made = mutableSetOf<ConversationId>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val RESUMED = Regex("""(?:topResumedActivity=|\bResumedActivity: )ActivityRecord\{[^}]* dev\.kestrel/\.app\.conversation\.screen\.ConversationActivity""")

    fun text(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    /** [words] with this run's [marker], as the tests type them. */
    fun marked(words: String): String = "$words $marker"

    /** Notes [id], a conversation that a test made, so that [finish] deletes it. */
    fun own(id: ConversationId) = synchronized(made) { made += id }

    /**
     * No goal and no conversation open, and [model] as the next rounds' model. A conversation that was open before, such as
     * the owner's own, closes into his list and stays there.
     */
    fun prepare(model: ModelPort) {
        endGoal()
        closeConversation()
        graph.roundModels.modelOverride = model
    }

    /** Ends what a test left open, puts back the model and the conversation's timers, and deletes the tests' conversations. */
    fun finish() {
        try {
            endGoal()
            closeConversation()
        } finally {
            graph.roundModels.modelOverride = null
            graph.conversationTimers = null
            deleteOwn()
        }
    }

    /** Whether the conversation screen is Android's resumed activity, in front. */
    fun screenInFront(screen: ScreenServiceRule): Boolean = RESUMED.containsMatchIn(screen.shell("dumpsys activity activities"))

    /** Brings the conversation screen back to the front, as the launcher does, and waits until it is there. */
    fun bringScreen(screen: ScreenServiceRule) {
        screen.shell("am start -W -n dev.kestrel/.app.conversation.screen.ConversationActivity")
        check(VoiceTests.waitUntil(10_000) { screenInFront(screen) }) { "The conversation screen did not come to the front" }
    }

    /**
     * Types [words] with this run's [marker] into the screen's field and taps Send, as the owner does, notes the conversation
     * as the tests' own, and returns the command number that they started.
     */
    fun send(nodes: SemanticsNodeInteractionsProvider, words: String): Int {
        val before = graph.conversations.view.value.command
        nodes.onNodeWithTag(FIELD_TAG).performTextInput(marked(words))
        nodes.onNodeWithText(text(R.string.conversation_send)).performClick()
        check(VoiceTests.waitUntil(5_000) { graph.conversations.view.value.command > before }) { "The typed words started no command" }
        graph.conversations.view.value.id?.let(::own)
        return graph.conversations.view.value.command
    }

    private fun endGoal() {
        val conversations = graph.conversations
        if (conversations.view.value.goal == null) return
        graph.textChannel.submit(graph.textChannel.input(InputIntent.Stop))
        check(VoiceTests.waitUntil(30_000) { conversations.view.value.goal == null }) { "The goal did not end" }
    }

    private fun closeConversation() {
        val conversations = graph.conversations
        if (conversations.view.value.id == null) return
        graph.textChannel.submit(graph.textChannel.input(InputIntent.NewConversation))
        check(VoiceTests.waitUntil(10_000) { conversations.view.value.id == null }) { "The conversation did not close" }
    }

    // A conversation that is still open, because a test failed before it closed, stays noted for the next finish.
    private fun deleteOwn() {
        val ids = synchronized(made) { made.toList().also { made.clear() } }
        val open = runBlocking { ids.filter { graph.conversations.deletePast(it) != PastDeletion.DELETED } }
        synchronized(made) { made += open }
    }
}
```

The code block above shows `TypedTests` as Task 8 carried it out. Task 14's first run found that the screen's buttons moved under the tests' taps, and `7f46179a` and `fdfd1a2d` changed this helper since. The screen's window resizes with the keyboard, whose show animation runs on Android's clock for about 650 ms after typing, and Android shows the keyboard again about 200 ms after `bringScreen` when the field kept its focus. Meanwhile the test's clock moves Compose's frames between a tap's down and up, so the up could land beside Send, Cancel or Continue, and the tap did nothing ("The typed words started no command", "Cancel did not end the goal"). Now the import `SemanticsNodeInteractionsProvider` is `AndroidComposeTestRule` (with `ConversationActivity`); `bringScreen(screen, compose)` ends with `ScreenKeyboard.settle(compose)`; `send(compose, words)` taps Send through the new `tapSend(compose)`, which calls `ScreenKeyboard.settle(compose)` before its click. The new androidTest file `ScreenKeyboard.kt` holds `ScreenKeyboard.rule`, a test rule that each class of the screen's device tests applies outside its compose rule (`ScreenKeyboard.rule` at order 1, the compose rule at order 2), so that each conversation screen gets a `WindowInsetsAnimationCompat` watch on its window's root from `Stage.CREATED` and loses it at `Stage.DESTROYED`. It also holds `settle(compose)`, which waits, for up to 10 s, until the keyboard shows exactly while the field has focus and none of its animations runs, and then lets Compose lay out; it fails clearly for a screen without a watch.

Create `app/src/androidTest/kotlin/dev/kestrel/app/conversation/ConversationScreenTest.kt`:

```kotlin
package dev.kestrel.app.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.TaskRequest
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.app.R
import dev.kestrel.app.agent.AgentTests
import dev.kestrel.app.conversation.screen.ConversationActivity
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.app.screen.ScreenServiceRule.Companion.KESTREL
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.conversation.model.InputChannel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The conversation screen on kestrel-api37 (design 10.1, 10.2 and 15.2 of sub-project 7): the launcher opens it and its
 * Settings open the settings app; a typed command leaves Kestrel's screen and works on the app below with listening off;
 * a second typed command gets the first one's record; and "New conversation" closes the conversation into the list. Each test
 * types its words with `TypedTests`' marker and deletes only its own conversations.
 */
@RunWith(AndroidJUnit4::class)
class ConversationScreenTest {
    @get:Rule(order = 0)
    val screen = ScreenServiceRule()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ConversationActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = TypedTests.graph

    private val onIt = call("say", null, null, null, "text" to "On it.")
    private val fail = call("fail", null, null, null, "reason" to "I stop here.")

    @After
    fun stop() {
        try {
            TypedTests.finish()
        } finally {
            screen.shell("am force-stop $FIXTURES")
        }
    }

    @Test
    fun theLauncherOpensTheConversationScreenAndItsSettingsOpenTheSettingsApp() {
        val launcher = checkNotNull(context.packageManager.getLaunchIntentForPackage(KESTREL)) { "Kestrel has no launcher activity" }
        assertEquals(ConversationActivity::class.java.name, launcher.component?.className)

        compose.onNodeWithText(TypedTests.text(R.string.conversation_settings)).performClick()

        val settings = Regex("""(?:topResumedActivity=|\bResumedActivity: )ActivityRecord\{[^}]* dev\.kestrel/\.app\.MainActivity""")
        check(VoiceTests.waitUntil(10_000) { settings.containsMatchIn(screen.shell("dumpsys activity activities")) }) { "The settings did not open" }
        screen.shell("input keyevent KEYCODE_BACK")
        check(VoiceTests.waitUntil(10_000) { TypedTests.screenInFront(screen) }) { "Back did not return to the conversation screen" }
    }

    @Test
    fun aTypedCommandLeavesKestrelsScreenAndWorksOnTheAppBelowWithListeningOff() {
        screen.open("ViewsActivity", "Views fixture")
        TypedTests.bringScreen(screen)
        val model = ScriptedModel(listOf(onIt, fail))
        TypedTests.prepare(model)
        val spoken = graph.offVoice.replies

        val ended = AgentTests.ended(TypedTests.send(compose, "Look at the fixture"))

        assertEquals(EndReason.FAILED_BY_MODEL, ended.report.end)
        // The round's first observation was the fixture's, so the screen had left before the goal looked (design 10.2).
        assertTrue(AgentTests.texts(model).contains("Views fixture"))
        assertFalse(TypedTests.screenInFront(screen))
        assertEquals(InputChannel.TEXT, graph.conversations.view.value.records.single().channel)
        // A typed command's words are written, not spoken (design 11): with the setting off nothing was spoken.
        assertEquals(spoken, graph.offVoice.replies)
    }

    @Test
    fun aSecondTypedCommandGetsTheFirstOnesRecord() {
        screen.open("ViewsActivity", "Views fixture")
        TypedTests.bringScreen(screen)
        val model = ScriptedModel(listOf(onIt, fail, fail))
        TypedTests.prepare(model)

        AgentTests.ended(TypedTests.send(compose, "Look at the fixture"))
        TypedTests.bringScreen(screen)
        compose.onNodeWithText(TypedTests.marked("Look at the fixture")).assertIsDisplayed()
        AgentTests.ended(TypedTests.send(compose, "And look again"))

        val second = model.requests.last().context.orEmpty()
        assertTrue(second.contains(TaskRequest.CONVERSATION_HEADER))
        assertTrue(second.contains(TypedTests.marked("Look at the fixture")))
        assertEquals(2, graph.conversations.view.value.records.size)
    }

    @Test
    fun newConversationClosesTheConversationIntoTheListAndClearsTheScreen() {
        screen.open("ViewsActivity", "Views fixture")
        TypedTests.bringScreen(screen)
        TypedTests.prepare(ScriptedModel(listOf(fail)))
        AgentTests.ended(TypedTests.send(compose, "Look at the fixture"))
        TypedTests.bringScreen(screen)

        compose.onNodeWithText(TypedTests.text(R.string.conversation_new)).performClick()

        check(VoiceTests.waitUntil(10_000) { graph.conversations.view.value.id == null }) { "The conversation did not close" }
        val words = TypedTests.marked("Look at the fixture")
        compose.waitUntil(5_000) { compose.onAllNodesWithText(words).fetchSemanticsNodes().isEmpty() }
        assertTrue(runBlocking { graph.conversationStore.list() }.any { it.firstRequest == words })
    }
}
```

The code block above shows `ConversationScreenTest` as Task 8 carried it out. Since Task 14's first run, the class applies `ScreenKeyboard.rule` at order 1 and its compose rule at order 2, and its five `TypedTests.bringScreen(screen)` calls are `TypedTests.bringScreen(screen, compose)` (`7f46179a`, `fdfd1a2d`; the note under `TypedTests` says why).

In `app/src/test/kotlin/dev/kestrel/app/goal/GoalNoticesTest.kt`, replace:

```kotlin
        assertEquals(ConversationTexts.CONTINUE_IN_NOTIFICATION, GoalNotices.of(ConversationView(goal = GoalPhase.INTERRUPTED, hold = GoalHold.BACKGROUND_REFUSED))?.text)
```

with:

```kotlin
        assertEquals(ConversationTexts.OPEN_AND_CONTINUE, GoalNotices.of(ConversationView(goal = GoalPhase.INTERRUPTED, hold = GoalHold.BACKGROUND_REFUSED))?.text)
```

Create `app/src/test/kotlin/dev/kestrel/app/listen/PhoneStateGrantTest.kt`:

```kotlin
package dev.kestrel.app.listen

import org.junit.Assert.assertEquals
import org.junit.Test

/** The call watch after a grant of READ_PHONE_STATE outside Kestrel (section 8.7; review B-M2 of plan 7F). */
class PhoneStateGrantTest {
    @Test
    fun aGrantStartsTheCallWatchOnceUntilItIsTakenBackAndGivenAgain() {
        var granted = false
        var watches = 0
        val grant = PhoneStateGrant({ granted }) { watches++ }

        grant.check()
        granted = true
        grant.check()
        grant.check()
        assertEquals(1, watches)

        granted = false
        grant.check()
        granted = true
        grant.check()
        assertEquals(2, watches)
    }
}
```
- [ ] **Step 2: Run the JVM test to see it fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.settings.SetupChecklistTest --tests=dev.kestrel.app.goal.GoalNoticesTest --tests=dev.kestrel.app.listen.PhoneStateGrantTest`
Expected: FAIL: the compiler does not know `needsSetup`, `ConversationTexts.OPEN_AND_CONTINUE` and `PhoneStateGrant`.

- [ ] **Step 3: The banner's rule, and one assembly of the checklist's status**

In `app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt`, replace:

```kotlin
    private fun doneIf(done: Boolean) = if (done) StepState.DONE else StepState.TO_DO
```

with:

```kotlin
    /** Whether a step is still to do or needs attention: the conversation screen's banner (design 10.1 of sub-project 7). */
    fun needsSetup(states: Map<SetupStep, StepState>): Boolean = states.values.any { it == StepState.TO_DO || it == StepState.WARNING }

    private fun doneIf(done: Boolean) = if (done) StepState.DONE else StepState.TO_DO
```

Create `app/src/main/kotlin/dev/kestrel/app/settings/SetupState.kt`:

```kotlin
package dev.kestrel.app.settings

import android.content.Context
import dev.kestrel.app.AppGraph
import dev.kestrel.app.download.DownloadChoice
import dev.kestrel.voice.VoiceChoice
import kotlinx.coroutines.flow.first

/**
 * The setup checklist's status, assembled in one place (plan 7F): what Android says ([SetupStatusReader]), the model files'
 * check, and what the stored settings say. The settings app shows it part by part as each part arrives; the conversation
 * screen's banner reads it whole ([read]).
 */
internal object SetupState {
    /** [status] with the stored settings' part: a ready provider, the safety review, and the voice that the download wants. */
    fun stored(status: SetupStatus, providerReady: Boolean, agentFreeReviewed: Boolean, downloadChoice: DownloadChoice?): SetupStatus =
        status.copy(
            providerReady = providerReady,
            agentFreeReviewed = agentFreeReviewed,
            kokoroWanted = downloadChoice?.voice == VoiceChoice.KOKORO,
        )

    /** The model files' check. A failure goes to [report], which logs it by its classes only (section 13.5). */
    suspend fun models(graph: AppGraph, report: (Exception) -> Unit): ModelCheck = checkModelFiles({ graph.models.check() }, report)

    /** The checklist's states now, with every part read: what the conversation screen's banner shows (design 10.1 of sub-project 7). */
    suspend fun read(graph: AppGraph, context: Context, report: (Exception) -> Unit): Map<SetupStep, StepState> {
        val data = StoredSettingsData(graph, context)
        val status = SetupStatusReader(context).read().copy(models = models(graph, report))
        return SetupChecklist.states(stored(status, data.providerReady.first(), data.agentFreeReviewed.first(), data.downloadChoice.first()))
    }
}
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt`, replace:

```kotlin
    val states = SetupChecklist.states(
        status.copy(
            providerReady = providerReady,
            agentFreeReviewed = agentFreeReviewed,
            kokoroWanted = downloadChoice?.voice == VoiceChoice.KOKORO,
        ),
    )
```

with:

```kotlin
    val states = SetupChecklist.states(SetupState.stored(status, providerReady, agentFreeReviewed, downloadChoice))
```

In `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`, replace:

```kotlin
            val models = checkModelFiles({ graph.models.check() }) { error -> graph.errors.handleException(coroutine, error) }
```

with:

```kotlin
            val models = SetupState.models(graph) { error -> graph.errors.handleException(coroutine, error) }
```

In `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`, replace:

```kotlin
import dev.kestrel.app.settings.SetupStatusReader
import dev.kestrel.app.settings.StoredSettingsData
import dev.kestrel.app.settings.checkModelFiles
```

with:

```kotlin
import dev.kestrel.app.settings.SetupState
import dev.kestrel.app.settings.SetupStatusReader
import dev.kestrel.app.settings.StoredSettingsData
```
- [ ] **Step 4: The screen's view model, its host and its activity**

Create `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationScreenViewModel.kt`:

```kotlin
package dev.kestrel.app.conversation.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kestrel.app.AppGraph
import dev.kestrel.app.conversation.AndroidConversationClock
import dev.kestrel.app.conversation.CardChoice
import dev.kestrel.app.conversation.OwnerCard
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.InputIntent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * The conversation screen's state across configuration changes (design 10.1 and 10.2 of sub-project 7; plan 7F): it keeps
 * [model], Task 6's one reducer, and feeds it every input, the conversation's view, the goal service's foreground state,
 * each input that the text channel gave (also one that the debug hook typed as if on the screen), the microphone, "New
 * conversation" and whether the screen is in front. The owner's typed words and buttons go only through the text channel
 * (design 12.2). [leaves] asks the activity to leave the front; the model's own inputs, such as "Stop and start new"'s,
 * go to the conversation from here, so that a configuration change loses none.
 */
internal class ConversationScreenViewModel(private val graph: AppGraph) : ViewModel() {
    val model = ConversationScreenModel(AndroidConversationClock::wallMillis)

    private val leaving = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private var words = false
    private var front = false

    val view: StateFlow<ConversationView> get() = graph.conversations.view

    val card: StateFlow<OwnerCard?> get() = graph.cardDesk.open

    /** Each time the screen should leave the front, because the owner's goal works (design 10.2). */
    val leaves: Flow<Unit> = leaving.receiveAsFlow()

    val zone: ZoneId get() = AndroidConversationClock.zone()

    init {
        viewModelScope.launch { graph.conversations.view.collect(model::viewed) }
        viewModelScope.launch { graph.goalForeground.foreground.collect(model::foreground) }
        viewModelScope.launch { graph.textChannel.sent.collect(model::sent) }
        viewModelScope.launch {
            model.effects.collect { effect ->
                when (effect) {
                    ScreenEffect.Leave -> leaving.trySend(Unit)
                    is ScreenEffect.Submit -> input(effect.intent)
                }
            }
        }
    }

    /** Gives the conversation the owner's typed [text]; false when it held no words. */
    fun send(text: String): Boolean {
        val input = graph.textChannel.read(text) ?: return false
        graph.textChannel.submit(input)
        return true
    }

    /** One of the screen's own inputs: Stop, Continue, Cancel or New conversation. */
    fun input(intent: InputIntent) = graph.textChannel.submit(graph.textChannel.input(intent))

    /** A button on the screen's copy of the open card, which reaches the conversation as an input (design 10.3). */
    fun choose(card: OwnerCard, choice: CardChoice) = graph.textChannel.choose(card.key, choice)

    /** Whether the field holds words: the quiet time is held while it does and the screen is in front (design 6.2). */
    fun field(hasWords: Boolean) = synchronized(lock) {
        words = hasWords
        graph.textChannel.composing(words && front)
    }

    /** Whether the screen is in front: the model, the card desk (the screen's copy or the overlay) and the quiet time follow it. */
    fun inFront(inFront: Boolean) = synchronized(lock) {
        front = inFront
        model.inFront(inFront)
        graph.cardDesk.screenInFront(inFront)
        graph.textChannel.composing(words && front)
    }

    /** The microphone (design 10.1): as the side key does, Kestrel listens if it is off and waits for one command. */
    fun listen() {
        graph.listening.assist()
        model.listened()
    }

    fun newConversation() = model.newConversation()

    override fun onCleared() {
        inFront(false)
    }

    override fun toString(): String = "ConversationScreenViewModel($model)"
}
```

Create `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationApp.kt`:

```kotlin
package dev.kestrel.app.conversation.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import dev.kestrel.conversation.model.InputIntent

/**
 * The conversation screen's host (design 10.1 of sub-project 7): it draws [ConversationScreen] from [screen]'s model, the
 * conversation's view and the open card, and holds only the field's words. [replyTo] is the question that "Reply…" opened
 * the screen for: the keyboard goes up on the field while that question is open.
 */
@Composable
internal fun ConversationApp(
    screen: ConversationScreenViewModel,
    setupNeeded: Boolean,
    replyTo: Int?,
    onSettings: () -> Unit,
    onSetup: () -> Unit,
    onShowApp: () -> Unit,
) {
    val state by screen.model.state.collectAsState()
    val view by screen.view.collectAsState()
    val card by screen.card.collectAsState()
    var field by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    LaunchedEffect(field.isNotBlank()) { screen.field(field.isNotBlank()) }
    LaunchedEffect(replyTo, card) {
        if (replyTo != null && card?.question == replyTo) focus.requestFocus()
    }

    val actions = ConversationActions(
        send = { if (screen.send(field)) field = "" },
        speak = screen::listen,
        stop = { screen.input(InputIntent.Stop) },
        continueGoal = { screen.input(InputIntent.Continue) },
        cancel = { screen.input(InputIntent.Stop) },
        newConversation = screen::newConversation,
        settings = onSettings,
        setup = onSetup,
        showApp = onShowApp,
        choose = screen::choose,
    )
    ConversationScreen(ConversationScreenRules.ui(state, view, card), field, { field = it }, setupNeeded, screen.zone, focus, actions)
}
```

Create `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationActivity.kt`:

```kotlin
package dev.kestrel.app.conversation.screen

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.MainActivity
import dev.kestrel.app.settings.SettingsPage
import dev.kestrel.app.settings.SetupChecklist
import dev.kestrel.app.settings.SetupState
import kotlinx.coroutines.launch

/**
 * Kestrel's conversation screen (design 10.1 of sub-project 7), the launcher's activity (D20). It holds no conversation
 * state of its own: [ConversationScreenViewModel] keeps the screen's model, and [ConversationApp] draws it and gives the
 * text channel the owner's typed words and buttons. Typed words count as the owner's only from here (design 12.2): the
 * window drops a touch that passed through another app's window, from Android 14 only accessibility tools may act on it,
 * and no intent carries words: "Reply…" names only a question's number ([EXTRA_QUESTION]), which puts the keyboard on the
 * field only while that question is open.
 */
class ConversationActivity : ComponentActivity() {
    private val graph by lazy { (application as KestrelApplication).graph }
    private val screen: ConversationScreenViewModel by viewModels { viewModelFactory { initializer { ConversationScreenViewModel(graph) } } }
    private var setupNeeded by mutableStateOf(false)
    private var replyTo by mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ScreenGuard.guard(window.decorView)
        follow(intent)
        // A leave that the model asked for while no activity was there, as during a rotation, waits for this one.
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { screen.leaves.collect { moveTaskToBack(true) } } }
        setContent {
            MaterialTheme {
                ConversationApp(screen, setupNeeded, replyTo, ::openSettings, ::openChecklist) { moveTaskToBack(true) }
            }
        }
    }

    // singleTask: the launcher, the notifications and "Reply…" bring this screen back with their intent.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        follow(intent)
    }

    override fun onResume() {
        super.onResume()
        screen.inFront(true)
        // The launcher opens this screen, so a grant of READ_PHONE_STATE made outside Kestrel starts the call watch here too.
        graph.phoneStateGrant.check()
        refreshSetup()
    }

    override fun onPause() {
        // A configuration change, such as a rotation, makes the activity again at once: the screen stays in front for its
        // model, which keeps waiting for the goal's service, and the card stays on the screen (review N3).
        if (!isChangingConfigurations) screen.inFront(false)
        super.onPause()
    }

    // Design 12.2: only a question's number, which is no command; any other extra and any data are ignored.
    private fun follow(intent: Intent) {
        replyTo = intent.getIntExtra(EXTRA_QUESTION, 0).takeIf { it > 0 }
    }

    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun openChecklist() {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_PAGE, SettingsPage.CHECKLIST.name))
    }

    // The banner shows while a step of the setup checklist is to do or needs attention, as the settings' checklist reads it.
    private fun refreshSetup() {
        lifecycleScope.launch(graph.errors) {
            val coroutine = coroutineContext
            val states = SetupState.read(graph, applicationContext) { error -> graph.errors.handleException(coroutine, error) }
            setupNeeded = SetupChecklist.needsSetup(states)
        }
    }

    companion object {
        /** The number of the question that "Reply…" opened the screen for (design 10.3); the only extra that the screen reads. */
        const val EXTRA_QUESTION = "dev.kestrel.app.extra.QUESTION"

        /** "Reply…" on the overlay's card: the screen in front, with question [question] in focus and the keyboard up. */
        internal fun reply(context: Context, question: Int) {
            context.startActivity(
                Intent(context, ConversationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_QUESTION, question),
            )
        }
    }
}
```

The code block above shows `ConversationActivity` as Task 8 carried it out. Task 14's first run found that its `onNewIntent` broke 16 of the screen's device tests, and `012ea255` and `16518f16` changed the activity since. `setIntent(intent)` in a `singleTask` activity's `onNewIntent` made every later intent the screen's own: `TypedTests.bringScreen`'s `am start`, "Reply…" and `TypedBoundaryTest`'s SEND and VIEW intents. ActivityScenario follows an activity only while its `getIntent()` matches the intent that started it, so the compose rule lost the screen's lifecycle and its `close()` failed ("Last stage = STARTED") or waited 45 s for a DESTROYED that it never saw. It also made another app's intent, with its words and data, the screen's own, which design 12.2 rules out even though nothing read it. Now:
- The screen keeps the intent that started it: `onNewIntent` calls only `screen.reply(question(intent.extras))`, and the class KDoc says that the screen takes the number from a later intent and keeps the intent that started it, so that no other app's intent becomes the screen's.
- The question number that "Reply…" named lives in `ConversationScreenViewModel` (`replyTo: StateFlow<Int?>`, `reply(question)`), not in the activity's `replyTo` field or its saved state. A view model survives a rotation and `recreate()` but not the process, and question numbers start again in each process (`ConversationOwner.numbers`), so a screen that Android restores after the process died does not put the keyboard on an unrelated question's field (review M1). `setContent` reads `screen.replyTo.collectAsState()`.
- `onCreate` calls `screen.reply(question(intent.extras))` only when `savedInstanceState == null` and the intent lacks `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`, as `MainActivity` does, so that the recent apps do not replay an old "Reply…" intent.
- `follow(intent: Intent)` is `question(extras: Bundle?): Int?`, which reads only `EXTRA_QUESTION` (design 12.2).

The view model comes with `androidx.activity:activity-compose`, which brings `activity-ktx`'s `viewModels` and `lifecycle-viewmodel`'s `viewModelFactory` and `viewModelScope`; no library is added.

- [ ] **Step 5: The launcher, the notifications and the app graph**

Now that opening Kestrel shows the conversation screen with its Continue, a goal that Android's refusal holds names it, as design 10.6 says ("Open Kestrel and tap Continue."), in place of plan 7E's sentence for the notification's Continue (plan 7E's interfaces). Plan 7E's `GoalRules.backgroundRefused` and its tests take the sentence from `ConversationTexts.held(GoalHold.BACKGROUND_REFUSED)`, so the new sentence changes only `ConversationTexts.kt` and the one test that names the constant, plan 7E's `GoalNoticesTest` (see "Before you start").

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/ConversationTexts.kt`, replace:

```kotlin
    /**
     * How the owner goes on after a refusal: the Continue of the held goal's notification, which Kestrel posts without the
     * service (plan 7E). Plan 7F makes it design 10.6's "Open Kestrel and tap Continue." with the conversation screen.
     */
    const val CONTINUE_IN_NOTIFICATION = "Tap Continue in Kestrel's notification."
```

with:

```kotlin
    /**
     * How the owner goes on after a refusal (design 10.6 of sub-project 7): Continue on the conversation screen, which the
     * launcher and every notification of Kestrel's open and whose start of the goal service comes from Kestrel's screen in
     * front (plan 7F); the held goal's notification, which Kestrel posts without the service (plan 7E), has its own
     * Continue too.
     */
    const val OPEN_AND_CONTINUE = "Open Kestrel and tap Continue."
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/ConversationTexts.kt`, replace:

```kotlin
        GoalHold.BACKGROUND_REFUSED -> CONTINUE_IN_NOTIFICATION
```

with:

```kotlin
        GoalHold.BACKGROUND_REFUSED -> OPEN_AND_CONTINUE
```
In `app/src/main/AndroidManifest.xml`, replace:

```xml
        <!-- singleTop: "What can I say?" while the settings app is in front delivers its page to the open app. -->
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
```

with:

```xml
        <!-- Kestrel's conversation screen, the launcher's activity (D20 of sub-project 7). Its only filter is the launcher's,
             so that no intent carries words to it (design 12.2); singleTask, so that the launcher, the notifications and
             "Reply…" bring back the one screen. -->
        <activity
            android:name=".conversation.screen.ConversationActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Kestrel's settings app (design spec, section 15), which the conversation screen's Settings opens. singleTop:
             "What can I say?" while the settings app is in front delivers its page to the open app. Exported without a
             filter, so that the scripts and the device tests start it by name; it follows only a page's name and an
             askable permission. -->
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop" />
```

In `app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt`, replace:

```kotlin
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
```

with:

```kotlin
        /**
         * Opens Kestrel's conversation screen with the launcher's own intent (design 10.1 of sub-project 7), so that a tap
         * brings Kestrel's task to the front instead of adding another screen. The listening notification and the goal
         * notification (plan 7E) share it.
         */
        internal fun openKestrel(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, ConversationActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PendingIntent.FLAG_IMMUTABLE,
        )
```

In `app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt`, replace:

```kotlin
import dev.kestrel.app.MainActivity
```

with:

```kotlin
import dev.kestrel.app.conversation.screen.ConversationActivity
```

In `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`, replace:

```kotlin
         * task gets the intent. The intent gets an identifier of its own, so that it never equals the intent that started
         * Kestrel's task: when something covers the settings app in that task, such as Android's accessibility settings
         * that its checklist opened or a permission dialog that outlived Kestrel's process, Android then starts the
         * settings app on top of it, as it does when the launcher started the task, and closes nothing. With an equal
         * intent, Android only brings the task to the front, and the settings never show.
```

with:

```kotlin
         * task gets the intent. The intent gets an identifier of its own, so that it never equals the intent that started
         * Kestrel's task, which since plan 7F is the launcher's intent for the conversation screen or an earlier intent of
         * this kind: when something covers the settings app in that task, such as Android's accessibility settings that
         * its checklist opened or a permission dialog that outlived Kestrel's process, Android then starts the settings
         * app on top of it and closes nothing. With an equal intent, Android only brings the task to the front, and the
         * settings never show.
```
In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
    /** The conversation's voice (design 11 of sub-project 7), over the Orchestrator, with its own voice while Kestrel is off. */
    private val voiceChannel: VoiceChannel by lazy {
        VoiceChannel(listening.voicePath, ownerSupport, cardDesk, ownerSupport, ownerSupport, permissionDialog, offVoice)
    }
```

with:

```kotlin
    /** The conversation's voice (design 11 of sub-project 7), over the Orchestrator, with its own voice while Kestrel is off. */
    private val voiceChannel: VoiceChannel by lazy {
        VoiceChannel(listening.voicePath, ownerSupport, cardDesk, ownerSupport, ownerSupport, permissionDialog, offVoice)
    }

    /**
     * The conversation's text channel (design 10, 11 and 12.2 of sub-project 7): the conversation screen's typed words and
     * buttons, and, through the card desk, the cards of a typed conversation's questions and hand-overs, and of every
     * question while Kestrel is off. It reaches the engine only through lambdas, because the engine is built with it.
     */
    internal val textChannel: TextChannel by lazy {
        TextChannel(
            bar = feedbackBar,
            screen = ownerSupport,
            cards = cardDesk,
            handOvers = ownerSupport,
            permissions = permissionDialog,
            replies = ReplyScreen { question -> ConversationActivity.reply(appContext, question) },
            conversation = object : TypedConversation {
                override fun view(): ConversationView = conversations.view.value

                override fun submit(input: OwnerInput) = conversations.submit(input)

                override fun replyOpened(question: Int) = conversations.replyOpened(question)

                override fun composing(active: Boolean) = conversations.composing(active)
            },
        )
    }
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
     * Listening is built first. Until plan 7F the voice is its only channel.
```

with:

```kotlin
     * Listening is built first. Its text channel takes the conversation screen's typed words and shows the cards (plan 7F).
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
            channels = mapOf(InputChannel.VOICE to voiceChannel),
```

with:

```kotlin
            channels = mapOf(InputChannel.VOICE to voiceChannel, InputChannel.TEXT to textChannel),
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
import dev.kestrel.app.conversation.VoiceChannel
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.conversation.engine.ConversationEngine
```

with:

```kotlin
import dev.kestrel.app.conversation.ReplyScreen
import dev.kestrel.app.conversation.TextChannel
import dev.kestrel.app.conversation.TypedConversation
import dev.kestrel.app.conversation.VoiceChannel
import dev.kestrel.app.conversation.screen.ConversationActivity
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.conversation.engine.ConversationEngine
import dev.kestrel.conversation.engine.ConversationView
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
import dev.kestrel.conversation.model.InputChannel
```

with:

```kotlin
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.OwnerInput
```

Create `app/src/main/kotlin/dev/kestrel/app/listen/PhoneStateGrant.kt`:

```kotlin
package dev.kestrel.app.listen

/**
 * Starts listening's call watch once Kestrel holds READ_PHONE_STATE (section 8.7). A grant in Android's settings does not
 * restart Kestrel, so each screen of Kestrel's checks it as it comes to the front ([check]): the conversation screen, which
 * the launcher opens, and the settings app, also after its permission dialog (review B-M2 of plan 7F). [granted] reads the
 * permission, and [watchCalls] is `Listening.watchCalls`. Any thread may call it.
 */
internal class PhoneStateGrant(private val granted: () -> Boolean, private val watchCalls: () -> Unit) {
    // Whether the permission was granted at the last check.
    private var seen = false

    @Synchronized
    fun check() {
        val now = granted()
        if (now && !seen) watchCalls()
        seen = now
    }

    override fun toString(): String = "PhoneStateGrant(seen=$seen)"
}
```

In `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`, replace:

```kotlin
    // Whether READ_PHONE_STATE was granted at the last refresh. A grant in Android's settings does not restart Kestrel,
    // so the call monitor starts here too (section 8.7).
    private var phoneStateGranted = false

    private fun refresh() {
```

with:

```kotlin
    private fun refresh() {
```

In `app/src/main/kotlin/dev/kestrel/app/MainActivity.kt`, replace:

```kotlin
        // after the permission dialog, so this covers a grant in the dialog and one in Android's settings.
        val granted = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        if (granted && !phoneStateGranted) graph.listening.watchCalls()
        phoneStateGranted = granted
```

with:

```kotlin
        // after the permission dialog, so this covers a grant in the dialog and one in Android's settings; the conversation
        // screen, which the launcher opens, checks it too (review B-M2 of plan 7F).
        graph.phoneStateGrant.check()
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
    /** The conversation's voice (design 11 of sub-project 7), over the Orchestrator, with its own voice while Kestrel is off. */
```

with:

```kotlin
    /**
     * Starts the call watch once the owner has granted READ_PHONE_STATE, also outside Kestrel (section 8.7): the
     * conversation screen and the settings app check it as they come to the front (review B-M2 of plan 7F).
     */
    internal val phoneStateGrant: PhoneStateGrant by lazy {
        PhoneStateGrant({ appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED }, listening::watchCalls)
    }

    /** The conversation's voice (design 11 of sub-project 7), over the Orchestrator, with its own voice while Kestrel is off. */
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
import android.app.role.RoleManager
```

with:

```kotlin
import android.Manifest
import android.app.role.RoleManager
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
import dev.kestrel.app.listen.OwnedBar
```

with:

```kotlin
import dev.kestrel.app.listen.OwnedBar
import dev.kestrel.app.listen.PhoneStateGrant
```
- [ ] **Step 6: Run the tests and the builds**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.settings.SetupChecklistTest --tests=dev.kestrel.app.goal.GoalNoticesTest --tests=dev.kestrel.app.listen.PhoneStateGrantTest`
Expected: BUILD SUCCESSFUL; the new tests pass, and `GoalNoticesTest` passes with the new sentence.

Run: `scripts\gradle.ps1 :core:conversation:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL; `:core:conversation` unchanged, `:app` JVM A₆ + 2. `ConversationScreenTest`'s 4 tests run in Task 14.

Run: `git grep -n "CONTINUE_IN_NOTIFICATION" -- core app`
Expected: no output.

Run: `git grep -n "checkModelFiles(" -- app/src/main`
Expected: two lines: its definition in `SetupChecklist.kt` and its one call in `SetupState.kt`.

Run: `git grep -n "MainActivity::class.java).setAction(Intent.ACTION_MAIN)" -- app/src/main`
Expected: no output: no notification opens the settings app as the launcher's activity any more.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/kotlin/dev/kestrel/app/conversation/screen app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt app/src/main/kotlin/dev/kestrel/app/settings/SetupChecklist.kt app/src/main/kotlin/dev/kestrel/app/settings/SetupState.kt app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt app/src/main/kotlin/dev/kestrel/app/MainActivity.kt app/src/main/kotlin/dev/kestrel/app/listen/PhoneStateGrant.kt app/src/test/kotlin/dev/kestrel/app/listen/PhoneStateGrantTest.kt app/src/test/kotlin/dev/kestrel/app/settings/SetupChecklistTest.kt app/src/test/kotlin/dev/kestrel/app/goal/GoalNoticesTest.kt app/src/androidTest/kotlin/dev/kestrel/app/conversation/TypedTests.kt app/src/androidTest/kotlin/dev/kestrel/app/conversation/ConversationScreenTest.kt core/conversation/src/main/kotlin/dev/kestrel/conversation/ConversationTexts.kt
git commit -m "feat(app): the conversation screen is the launcher: typed commands reach the conversation through the text channel, the screen leaves once the goal's service runs, a refused goal names the screen's Continue, and the tests delete only their own conversations" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 9: Questions, held goals, cards and typed speech on the device

Design 15.2 of sub-project 7 on kestrel-api37, with the scripted model: the card with "Reply…" and the goal that goes on after the typed answer on the screen; the waits of D21, shortened by an instrumentation argument (`kestrelWaitDivisor`, 60 by default, so that 2 minutes become 2 s and 30 s become 0.5 s); a spoken conversation's question that shows its card once Kestrel is off (the owner's decision); a touch that interrupts a typed goal, and the screen's Continue that resumes it and its Cancel that ends it; after a lock, "Continue the goal?" with its card over the app and its copy on the screen, whose wait ends with the goal still held; a hand-over's card, whose Continue lets the goal go on and whose Cancel ends it, on the overlay; and a typed conversation's words spoken with Kestrel's own voice when "Speak replies to typed commands" is on and Kestrel is off. This task adds tests only: every part that they check comes from Tasks 1 to 8 and from plans 7B to 7E. Each test's conversations are the tests' own (`TypedTests.own`), so that `finish()` deletes them. The hand-over test turns Kestrel's notification access off through the shell, as `NotificationListenerTest` does, and puts back the state that it found.

**Files:**
- Test: create `app/src/androidTest/kotlin/dev/kestrel/app/conversation/QuestionFlowTest.kt`

**Interfaces:**
- Consumes: Task 3's `AppGraph.conversationTimers` and `Timer`; Task 5's `AppGraph.offVoice.replies`; Task 8's `TypedTests` and `ConversationActivity`; the strings `card_reply`, `card_cancel`, `card_continue`, `card_stop`, `card_confirm`, `conversation_continue`, `conversation_cancel` and `conversation_send`; Task 1's `CARD_ARMING_MILLIS`; plan 7B's `ConversationView.asking` and `QuestionKind.CONTINUE_GOAL`; sub-project 5's `KestrelNotificationListener` (`component`, `hasAccess`) and its `list_notifications` hand-over (`HandOverKind.ACCESS`); plan 7D's `AgentTests` (`start`, `stop`, `turnOff`, `run`, `awaitAsking`, `ended`, `texts`, `presses`, `clearPresses`) and `HeldModel`; plan 7C's `SettingKeys.CONVERSATIONS`; `VoiceTests.assumeModels(Engine.TTS)`.
- Produces: the device tests' instrumentation argument `kestrelWaitDivisor` (a whole number above 0; 60 when it is not given).

Counts: `:app` device tests AD₈ + 9 (`QuestionFlowTest` 9), where AD₈ is the count after Task 8. JVM counts unchanged.

- [ ] **Step 1: Write the tests**

Create `app/src/androidTest/kotlin/dev/kestrel/app/conversation/QuestionFlowTest.kt`:

```kotlin
package dev.kestrel.app.conversation

import android.graphics.Rect
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.app.R
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.agent.AgentTests
import dev.kestrel.app.agent.HeldModel
import dev.kestrel.app.conversation.screen.ConversationActivity
import dev.kestrel.app.conversation.screen.FIELD_TAG
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.machine.Timer
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.LineKind
import dev.kestrel.datatools.notifications.KestrelNotificationListener
import dev.kestrel.screen.overlay.CARD_ARMING_MILLIS
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Questions, held goals and typed speech on kestrel-api37 (design 10.3, 10.4, 6.5, 11 and 15.2 of sub-project 7): "Reply…"
 * opens the screen for the typed answer, D21's waits end a question and decline a confirmation (shortened by the
 * instrumentation argument [WAIT_DIVISOR]), a spoken conversation's question shows its card once Kestrel is off, a touch
 * interrupts a typed goal, which the screen's Continue resumes and its Cancel ends, "Continue the goal?" after a lock shows its
 * card and its copy on the screen and its wait leaves the goal held, a hand-over's card continues and cancels the goal on the
 * overlay, and Kestrel's own voice speaks a typed conversation's words when the setting is on and Kestrel is off.
 */
@RunWith(AndroidJUnit4::class)
class QuestionFlowTest {
    @get:Rule(order = 0)
    val screen = ScreenServiceRule()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ConversationActivity>()

    private val graph get() = TypedTests.graph
    private val conversations get() = graph.conversations

    private val onIt = call("say", null, null, null, "text" to "On it.")
    private val fail = call("fail", null, null, null, "reason" to "I stop here.")
    private val ask = call("ask_user", null, null, null, "question" to "Which time?")
    private val delete = call("tap", "Delete chat", null, null, "reason" to "the next step", "category" to "deleting")
    private val notifications = call("list_notifications")

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val listener by lazy { KestrelNotificationListener.component(context).flattenToString() }

    // D21's waits, divided by the instrumentation argument (design 15.2 of sub-project 7).
    private val divisor = InstrumentationRegistry.getArguments().getString(WAIT_DIVISOR)?.toLongOrNull()?.takeIf { it > 0 } ?: DEFAULT_DIVISOR

    @After
    fun stop() {
        try {
            TypedTests.finish()
        } finally {
            AgentTests.clearPresses(screen)
            screen.shell("am force-stop $FIXTURES")
        }
    }

    private fun shortenWaits() {
        graph.conversationTimers = { timer, millis -> if (timer == Timer.QUESTION) millis / divisor else millis }
    }

    private fun awaitButton(label: String): Rect {
        var found: Rect? = null
        check(VoiceTests.waitUntil(15_000) { screen.overlayButton(label)?.also { found = it } != null }) { "The card has no button $label" }
        return checkNotNull(found)
    }

    // A card ignores taps for its first 600 ms (design D32 item 21 of sub-project 5).
    private fun tapCard(label: String) {
        val bounds = awaitButton(label)
        Thread.sleep(ARMED_MILLIS)
        screen.shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
    }

    // The owner types [words] on the screen over the fixture that [open] opens. The model's first reply waits until the
    // screen has left for the goal, so that the question's card shows over the fixture, not inline on the screen.
    private fun typedGoal(model: ScriptedModel, words: String, open: () -> Unit): Int {
        open()
        TypedTests.bringScreen(screen)
        val held = HeldModel(model)
        TypedTests.prepare(held)
        val command = TypedTests.send(compose, words)
        check(VoiceTests.waitUntil(30_000) { held.calls == 1 && !TypedTests.screenInFront(screen) }) { "The screen did not leave for the goal" }
        repeat(REPLIES) { held.release() }
        return command
    }

    @Test
    fun replyOnATypedQuestionsCardOpensTheScreenForTheAnswerAndTheGoalGoesOn() {
        val model = ScriptedModel(listOf(ask, onIt, fail))
        val command = typedGoal(model, "Set a reminder") { screen.open("ViewsActivity", "Views fixture") }
        tapCard(TypedTests.text(R.string.card_reply))
        check(VoiceTests.waitUntil(10_000) { TypedTests.screenInFront(screen) }) { "Reply… did not open the conversation screen" }
        compose.onNodeWithText(TypedTests.text(R.string.card_reply)).assertIsDisplayed()
        compose.onNodeWithTag(FIELD_TAG).assertIsFocused()
        compose.onNodeWithTag(FIELD_TAG).performTextInput("Half past seven")
        compose.onNodeWithText(TypedTests.text(R.string.conversation_send)).performClick()

        val ended = AgentTests.ended(command)
        assertEquals(EndReason.FAILED_BY_MODEL, ended.report.end)
        assertTrue(AgentTests.texts(model).contains("Half past seven"))
        val answered = conversations.view.value.records.single().lines.single { it.kind == LineKind.OWNER_ANSWERED }
        assertEquals(InputChannel.TEXT, answered.channel)
    }

    @Test
    fun aTypedQuestionThatGetsNoAnswerEndsAfterItsWait() {
        shortenWaits()
        val command = typedGoal(ScriptedModel(listOf(ask, fail)), "Set a reminder") { screen.open("ViewsActivity", "Views fixture") }
        awaitButton(TypedTests.text(R.string.card_reply))
        val shown = SystemClock.elapsedRealtime()
        val ended = AgentTests.ended(command, timeoutMillis = QUESTION_MILLIS / divisor + 60_000)
        val waited = SystemClock.elapsedRealtime() - shown

        assertEquals(EndReason.FAILED_BY_MODEL, ended.report.end)
        assertTrue("The question ended after $waited ms", waited >= QUESTION_MILLIS / divisor - SLACK_MILLIS)
        assertNull(screen.overlayButton(TypedTests.text(R.string.card_reply)))
    }

    @Test
    fun aTypedConfirmationThatGetsNoAnswerIsDeclinedAfterItsWait() {
        shortenWaits()
        AgentTests.clearPresses(screen)
        val command = typedGoal(ScriptedModel(listOf(delete, fail)), "Delete my chat with Ana") {
            screen.open("MessengerActivity", "Ana Petrović", "--es chat ana")
        }
        awaitButton(TypedTests.text(R.string.card_confirm))
        val ended = AgentTests.ended(command, timeoutMillis = 60_000)

        assertEquals(EndReason.DECLINED, ended.report.end)
        assertEquals(emptyList<String>(), AgentTests.presses(screen))
    }

    // Plan 7B's decision 10: while Kestrel is off, a spoken conversation's question shows its card too.
    @Test
    fun whileKestrelIsOffASpokenConversationsQuestionShowsItsCard() {
        screen.open("ViewsActivity", "Views fixture")
        AgentTests.start()
        try {
            val command = AgentTests.run("set a reminder", ScriptedModel(listOf(ask, fail)))
            conversations.view.value.id?.let(TypedTests::own)
            AgentTests.awaitAsking(command, QuestionKind.FREE)
            assertNull(screen.overlayButton(TypedTests.text(R.string.card_cancel)))

            AgentTests.turnOff()
            tapCard(TypedTests.text(R.string.card_cancel))

            assertEquals(EndReason.FAILED_BY_MODEL, AgentTests.ended(command).report.end)
        } finally {
            AgentTests.stop()
        }
    }

    @Test
    fun aTouchInterruptsATypedGoalAndTheScreensContinueResumesIt() {
        val model = interruptedGoal()

        TypedTests.bringScreen(screen)
        compose.onNodeWithText(TypedTests.text(R.string.conversation_continue)).performClick()

        // The goal goes on from its next step: only now does the model get its second request, and the screen leaves for it.
        check(VoiceTests.waitUntil(30_000) { model.calls == 2 }) { "The goal did not go on" }
        check(VoiceTests.waitUntil(10_000) { !TypedTests.screenInFront(screen) }) { "The screen did not leave for the goal" }
        model.release()
        check(VoiceTests.waitUntil(30_000) { conversations.view.value.goal == null }) { "The goal did not end" }
        assertEquals(EndReason.FAILED_BY_MODEL, conversations.outcome(conversations.view.value.command)?.report?.end)
        assertTrue(conversations.view.value.records.single().lines.any { it.kind == LineKind.OWNER_CONTINUED && it.channel == InputChannel.TEXT })
    }

    @Test
    fun cancelOnTheScreenEndsAHeldGoal() {
        interruptedGoal()

        TypedTests.bringScreen(screen)
        compose.onNodeWithText(TypedTests.text(R.string.conversation_cancel)).performClick()

        check(VoiceTests.waitUntil(30_000) { conversations.view.value.goal == null }) { "Cancel did not end the goal" }
        assertTrue(TypedTests.screenInFront(screen))
    }

    // Design 6.5, 10.3 and D21: after a lock, a typed goal's "Continue the goal?" shows its card over the app and its copy on
    // the screen; with no answer its wait ends, and the goal stays held at its next step.
    @Test
    fun continueTheGoalShowsItsCardAndItsCopyAndTheGoalStaysHeldAfterItsWait() {
        shortenWaits()
        screen.open("ViewsActivity", "Views fixture")
        TypedTests.bringScreen(screen)
        val model = HeldModel(ScriptedModel(listOf(onIt, fail)))
        TypedTests.prepare(model)
        TypedTests.send(compose, "Look at the fixture")
        check(VoiceTests.waitUntil(30_000) { model.calls == 1 && !TypedTests.screenInFront(screen) }) { "The goal did not start on the fixture" }
        try {
            screen.shell("input keyevent KEYCODE_SLEEP")
            check(VoiceTests.waitUntil(15_000) { conversations.view.value.goal == GoalPhase.PAUSED }) { "The lock did not pause the goal" }
            model.release()
        } finally {
            wake()
        }
        check(VoiceTests.waitUntil(30_000) { conversations.view.value.asking == QuestionKind.CONTINUE_GOAL }) { "No \"Continue the goal?\" after the unlock" }
        awaitButton(TypedTests.text(R.string.card_continue))

        TypedTests.bringScreen(screen)
        // While the goal is held the screen's own row says Cancel and Continue; Stop is the card's.
        compose.waitUntil(5_000) { compose.onAllNodesWithText(TypedTests.text(R.string.card_stop)).fetchSemanticsNodes().isNotEmpty() }
        check(VoiceTests.waitUntil(QUESTION_MILLIS / divisor + 30_000) { conversations.view.value.asking == null }) { "The question did not end after its wait" }

        compose.waitUntil(5_000) { compose.onAllNodesWithText(TypedTests.text(R.string.card_stop)).fetchSemanticsNodes().isEmpty() }
        assertEquals(GoalPhase.PAUSED, conversations.view.value.goal)
        assertEquals(1, model.calls)
    }

    // Design 10.3: a hand-over's card on the overlay; its Continue lets the goal go on, and its Cancel ends the goal.
    @Test
    fun aHandOversCardContinuesAndCancelsTheGoalOnTheOverlay() {
        val hadAccess = KestrelNotificationListener.hasAccess(context)
        screen.shell("cmd notification disallow_listener $listener")
        try {
            check(VoiceTests.waitUntil(10_000) { !KestrelNotificationListener.connected.value }) { "The listener did not disconnect" }
            val model = ScriptedModel(listOf(notifications, notifications, fail))
            val command = typedGoal(model, "Read my notifications") { screen.open("MessengerActivity", "Chats") }

            tapCard(TypedTests.text(R.string.card_continue))
            // The tool ran again without access, and the model's next reply asked for it once more.
            check(VoiceTests.waitUntil(30_000) { model.requests.size == 2 }) { "The goal did not go on after Continue" }
            tapCard(TypedTests.text(R.string.card_cancel))

            assertEquals(EndReason.CANCELLED, AgentTests.ended(command).report.end)
            assertEquals(2, model.requests.size)
        } finally {
            screen.shell("cmd notification ${if (hadAccess) "allow_listener" else "disallow_listener"} $listener")
        }
    }

    @Test
    fun aTypedConversationsWordsAreSpokenWithKestrelsOwnVoiceWhenTheSettingIsOnAndKestrelIsOff() = runBlocking {
        VoiceTests.assumeModels(Engine.TTS)
        val stored = graph.settings.get(SettingKeys.CONVERSATIONS)
        graph.settings.set(SettingKeys.CONVERSATIONS, stored.copy(speakTypedReplies = true))
        try {
            screen.open("ViewsActivity", "Views fixture")
            TypedTests.bringScreen(screen)
            val before = graph.offVoice.replies
            TypedTests.prepare(ScriptedModel(listOf(onIt, fail)))

            AgentTests.ended(TypedTests.send(compose, "Look at the fixture"))

            // "On it." (design 11): spoken by Kestrel's own voice, because Kestrel is off.
            check(VoiceTests.waitUntil(60_000) { graph.offVoice.replies > before }) { "Kestrel's own voice did not speak the goal's words" }
        } finally {
            graph.settings.set(SettingKeys.CONVERSATIONS, stored)
        }
    }

    // A typed goal on the fixture whose first model reply waits, and the owner's touch, which interrupts it (D13; the
    // conversation takes the touch as the touch watcher gives it, as in plan 7E's GoalServiceTest).
    // Once that reply goes, the goal stays held at its next step and asks the model nothing more (plan 7D's GoalPauseTest).
    private fun interruptedGoal(): HeldModel {
        screen.open("ViewsActivity", "Views fixture")
        TypedTests.bringScreen(screen)
        val model = HeldModel(ScriptedModel(listOf(onIt, fail)))
        TypedTests.prepare(model)
        TypedTests.send(compose, "Look at the fixture")
        check(VoiceTests.waitUntil(30_000) { model.calls == 1 && !TypedTests.screenInFront(screen) }) { "The goal did not start on the fixture" }

        conversations.touched()

        check(VoiceTests.waitUntil(15_000) { conversations.view.value.goal == GoalPhase.INTERRUPTED }) { "The touch did not interrupt the goal" }
        assertFalse(TypedTests.screenInFront(screen))
        model.release()
        Thread.sleep(HELD_MILLIS)
        assertEquals(GoalPhase.INTERRUPTED, conversations.view.value.goal)
        assertEquals(1, model.calls)
        return model
    }

    private fun wake() {
        screen.shell("input keyevent KEYCODE_WAKEUP")
        screen.shell("wm dismiss-keyguard")
    }

    private companion object {
        /** The instrumentation argument that divides D21's waits; 60 when it is not given. */
        const val WAIT_DIVISOR = "kestrelWaitDivisor"
        const val DEFAULT_DIVISOR = 60L

        /** D21's wait for a question card. */
        const val QUESTION_MILLIS = 2L * 60 * 1000

        /** How much earlier than its wait a question may seem to end, because the card shows a moment after the question opens. */
        const val SLACK_MILLIS = 1_000L

        /** A little more than the 600 ms in which a card ignores taps. */
        const val ARMED_MILLIS = CARD_ARMING_MILLIS + 100

        /** More releases than any test's script has replies: the held model lets every reply go at once. */
        const val REPLIES = 5

        /** How long the test watches a held goal stay held. */
        const val HELD_MILLIS = 3_000L
    }
}
```

The code block above shows `QuestionFlowTest` as Task 9 carried it out. Since Task 14's first run, the class applies `ScreenKeyboard.rule` at order 1 and its compose rule at order 2, and every `TypedTests.bringScreen(screen)` call is `TypedTests.bringScreen(screen, compose)` (`7f46179a`, `fdfd1a2d`; Task 8's note under `TypedTests` says why). In `replyOnATypedQuestionsCardOpensTheScreenForTheAnswerAndTheGoalGoesOn`, the direct click on Send after "Half past seven" is `TypedTests.tapSend(compose)`, and after the first `assertIsFocused()` the test recreates the screen (`compose.activityRule.scenario.recreate()`, as after a rotation) and checks again that the field has focus: the screen keeps the question that "Reply…" named in its view model (`012ea255`, `16518f16`; Task 8's note under `ConversationActivity`). That check failed in a red run in which `onCreate` dropped the number. The review fix's speech test `aTypedAnswerEndsItsQuestionsSpokenCopyAndTheNextRepliesAreStillSpoken` (`1a4abd50`) taps Send through `TypedTests.tapSend(compose)` too.

- [ ] **Step 2: Compile the tests**

Run: `scripts\gradle.ps1 :app:assembleDebugAndroidTest; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL. The 9 tests run in Task 14.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/kotlin/dev/kestrel/app/conversation/QuestionFlowTest.kt
git commit -m "test(app): typed questions with Reply and D21's shortened waits, cards while Kestrel is off, held goals on the screen, Continue the goal and hand-over cards, and typed speech with listening off" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 10: The typed-input boundary

Design 12.2 of sub-project 7: typed input counts as the owner's only when he typed it in Kestrel's own screen. Task 7's `ScreenGuard`, which Task 8 puts on the screen's window, gives it `filterTouchesWhenObscured` on the root view and `setAccessibilityDataSensitive` from Android 14, and Task 8 gives it its only filter, the launcher's; this task checks both. A unit test reads the build's merged manifest, whose path Gradle gives it through `MergedManifestArgument`, and checks that the screen's only filter is the launcher's, that no activity takes shared or processed text or a link, and that no other activity is the launcher's. On kestrel-api37, a fixture window of another app over the screen, which lets touches pass through it, keeps a tap from reaching Send, and an intent that names the screen with words and data puts nothing in the field.

**Files:**
- Create: `build-logic/convention/src/main/kotlin/dev/kestrel/build/MergedManifestArgument.kt`, `testapp/src/main/kotlin/dev/kestrel/testapp/ObscuringActivity.kt`
- Modify: `app/build.gradle.kts`, `testapp/src/main/AndroidManifest.xml`
- Test: create `app/src/test/kotlin/dev/kestrel/app/conversation/screen/ScreenManifestTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/conversation/TypedBoundaryTest.kt`

**Interfaces:**
- Consumes: AGP's `SingleArtifact.MERGED_MANIFEST`; Task 8's manifest, `ConversationActivity`, `FIELD_TAG` and `TypedTests` (`marked`, `own`); `ScreenServiceRule` (`open`, `shell`, `showsWindow`, `FIXTURES`, `KESTREL`).
- Produces: `abstract class MergedManifestArgument : CommandLineArgumentProvider` (`manifest: RegularFileProperty`, `PROPERTY = "kestrel.mergedManifest"`); every unit-test task's system property `kestrel.mergedManifest`; the fixture `ObscuringActivity` ("Obscuring fixture"), whose window "Obscuring overlay" covers the screen at half opacity while the activity lives, when the app-op `SYSTEM_ALERT_WINDOW` allows it.

Counts: `:app` JVM tests A₉ + 2 (`ScreenManifestTest` 2), where A₉ is the count after Task 9; `:app` device tests AD₉ + 2 (`TypedBoundaryTest` 2).

- [ ] **Step 1: Give the unit tests the merged manifest**

Create `build-logic/convention/src/main/kotlin/dev/kestrel/build/MergedManifestArgument.kt`:

```kotlin
package dev.kestrel.build

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

/**
 * Names an app's merged manifest to a unit-test task in the system property [PROPERTY], and makes the task wait for the
 * manifest and run again when it changes. Add it to the task's `jvmArgumentProviders`, with [manifest] set from
 * `variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)`. Design 12.2 of sub-project 7: a unit test checks the
 * conversation screen's filters in the manifest that Android gets.
 */
abstract class MergedManifestArgument : CommandLineArgumentProvider {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    override fun asArguments(): Iterable<String> = listOf("-D$PROPERTY=${manifest.get().asFile.absolutePath}")

    companion object {
        const val PROPERTY = "kestrel.mergedManifest"
    }
}
```

In `app/build.gradle.kts`, replace:

```kotlin
import dev.kestrel.build.BuildCommitTask
import dev.kestrel.build.CopyAssetsTask
```

with:

```kotlin
import com.android.build.api.artifact.SingleArtifact
import dev.kestrel.build.BuildCommitTask
import dev.kestrel.build.CopyAssetsTask
import dev.kestrel.build.MergedManifestArgument
```

In `app/build.gradle.kts`, replace:

```kotlin
        variant.sources.assets?.addGeneratedSourceDirectory(catalog, CopyAssetsTask::outputDirectory)
    }
```

with:

```kotlin
        variant.sources.assets?.addGeneratedSourceDirectory(catalog, CopyAssetsTask::outputDirectory)
        // ScreenManifestTest reads the variant's merged manifest (design 12.2 of sub-project 7).
        val mergedManifest = objects.newInstance<MergedManifestArgument>()
        mergedManifest.manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        val unitTest = "test${variant.name.replaceFirstChar(Char::titlecase)}UnitTest"
        tasks.withType<Test>().configureEach {
            if (name == unitTest) jvmArgumentProviders.add(mergedManifest)
        }
    }
```

- [ ] **Step 2: Write the manifest's test**

Create `app/src/test/kotlin/dev/kestrel/app/conversation/screen/ScreenManifestTest.kt`:

```kotlin
package dev.kestrel.app.conversation.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Design 12.2 of sub-project 7, over the build's merged manifest, which Gradle names in the system property
 * `kestrel.mergedManifest` (`MergedManifestArgument`): the conversation screen's only filter is the launcher's, so that
 * no intent carries words to it; no activity takes shared or processed text or a link; and no other activity is the
 * launcher's.
 */
class ScreenManifestTest {
    private val activities: List<Element> by lazy {
        val path = checkNotNull(System.getProperty(PROPERTY)) { "Run the test with Gradle, which names the merged manifest in $PROPERTY" }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val manifest = factory.newDocumentBuilder().parse(File(path))
        manifest.getElementsByTagName("activity").elements() + manifest.getElementsByTagName("activity-alias").elements()
    }

    @Test
    fun theConversationScreensOnlyFilterIsTheLaunchers() {
        val screen = activities.single { nameOf(it) == SCREEN }
        val filter = filtersOf(screen).single()

        assertEquals(listOf(MAIN), namesIn(filter, "action"))
        assertEquals(listOf(LAUNCHER), namesIn(filter, "category"))
        assertEquals(0, filter.getElementsByTagName("data").length)
    }

    @Test
    fun noActivityTakesSharedOrProcessedTextOrALinkAndNoOtherActivityIsTheLaunchers() {
        for (activity in activities) {
            val name = nameOf(activity)
            for (filter in filtersOf(activity)) {
                val taken = namesIn(filter, "action").filter { it in REFUSED }
                assertTrue("$name takes $taken", taken.isEmpty())
                if (name != SCREEN) assertTrue("$name is a launcher's activity", LAUNCHER !in namesIn(filter, "category"))
            }
        }
    }

    // The merger writes full class names; a name that starts with a dot is in the app's namespace.
    private fun nameOf(element: Element): String =
        element.getAttributeNS(ANDROID, "name").let { if (it.startsWith(".")) NAMESPACE + it else it }

    private fun filtersOf(activity: Element): List<Element> =
        activity.childNodes.let { nodes -> (0 until nodes.length).map(nodes::item) }.filterIsInstance<Element>().filter { it.tagName == "intent-filter" }

    private fun namesIn(filter: Element, tag: String): List<String> =
        filter.getElementsByTagName(tag).elements().map { it.getAttributeNS(ANDROID, "name") }

    private fun NodeList.elements(): List<Element> = (0 until length).map { item(it) as Element }

    private companion object {
        const val PROPERTY = "kestrel.mergedManifest"
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val NAMESPACE = "dev.kestrel.app"
        const val SCREEN = "dev.kestrel.app.conversation.screen.ConversationActivity"
        const val MAIN = "android.intent.action.MAIN"
        const val LAUNCHER = "android.intent.category.LAUNCHER"

        /** Intents that carry words or a link from another app (design 12.2). */
        val REFUSED = setOf(
            "android.intent.action.SEND",
            "android.intent.action.SEND_MULTIPLE",
            "android.intent.action.PROCESS_TEXT",
            "android.intent.action.VIEW",
        )
    }
}
```

- [ ] **Step 3: Run the manifest's tests**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.conversation.screen.ScreenManifestTest; scripts\gradle.ps1 --stop`
Expected: 2 tests, all PASS; Gradle builds the debug merged manifest first.

- [ ] **Step 4: The fixture window over Kestrel's screen**

Create `testapp/src/main/kotlin/dev/kestrel/testapp/ObscuringActivity.kt`:

```kotlin
package dev.kestrel.testapp

import android.app.Activity
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * A window of this app over every app, through which touches pass (plan 7F): Kestrel's conversation screen drops a touch
 * that passes through another app's window (design 12.2 of sub-project 7). The window, "Obscuring overlay", stays while
 * this activity lives, in the background too, and shows only when the app-op `SYSTEM_ALERT_WINDOW` allows this app to draw
 * over other apps; the device test allows it and puts it back.
 */
class ObscuringActivity : Activity() {
    private var overlay: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = fixturePage("Obscuring fixture")
        if (!Settings.canDrawOverlays(this)) {
            page.addView(TextView(this).apply { text = "This app may not draw over other apps." })
            return
        }
        val cover = View(this).apply { setBackgroundColor(Color.rgb(255, 160, 0)) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            alpha = ALPHA
            title = OVERLAY_TITLE
        }
        getSystemService(WindowManager::class.java).addView(cover, params)
        overlay = cover
    }

    override fun onDestroy() {
        overlay?.let { getSystemService(WindowManager::class.java).removeView(it) }
        overlay = null
        super.onDestroy()
    }

    private companion object {
        /** Android lets touches pass through another app's overlay only up to opacity 0.8 (its rule for untrusted touches). */
        const val ALPHA = 0.5f
        const val OVERLAY_TITLE = "Obscuring overlay"
    }
}
```

In `testapp/src/main/AndroidManifest.xml`, replace:

```xml
    <!-- For the cross-app overlay of injection fixture 6: :tools:eval allows it with appops and puts it back. -->
```

with:

```xml
    <!-- For the cross-app overlays of injection fixture 6 and ObscuringActivity: :tools:eval and the device test
         TypedBoundaryTest allow it with appops and put it back. -->
```

In `testapp/src/main/AndroidManifest.xml`, replace:

```xml
        <!-- sub-project 5C: fixtures -->
        <activity
            android:name=".OcrCanvasActivity"
            android:exported="true" />
```

with:

```xml
        <!-- sub-project 5C: fixtures -->
        <activity
            android:name=".OcrCanvasActivity"
            android:exported="true" />
        <!-- Sub-project 7: a window over Kestrel's conversation screen, through which touches pass (plan 7F). -->
        <activity
            android:name=".ObscuringActivity"
            android:exported="true" />
```

- [ ] **Step 5: Write the device tests**

Create `app/src/androidTest/kotlin/dev/kestrel/app/conversation/TypedBoundaryTest.kt`:

```kotlin
package dev.kestrel.app.conversation

import android.graphics.Point
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.app.R
import dev.kestrel.app.conversation.screen.ConversationActivity
import dev.kestrel.app.conversation.screen.FIELD_TAG
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.app.screen.ScreenServiceRule.Companion.KESTREL
import dev.kestrel.app.voice.VoiceTests
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * The typed-input boundary on kestrel-api37 (design 12.2 of sub-project 7): a tap that passes through another app's
 * window over the conversation screen sends nothing, and an intent that names the screen with words and data puts
 * nothing in its field.
 */
@RunWith(AndroidJUnit4::class)
class TypedBoundaryTest {
    @get:Rule(order = 0)
    val screen = ScreenServiceRule()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ConversationActivity>()

    private val conversations get() = TypedTests.graph.conversations
    private var overlayMode = DEFAULT_MODE

    @Before
    fun allowOverlays() {
        overlayMode = OVERLAY_MODE.find(screen.shell("appops get $FIXTURES SYSTEM_ALERT_WINDOW"))?.groupValues?.get(1) ?: DEFAULT_MODE
        screen.shell("appops set $FIXTURES SYSTEM_ALERT_WINDOW allow")
        TypedTests.prepare(ScriptedModel(listOf(call("fail", null, null, null, "reason" to "I stop here."))))
    }

    @After
    fun putBack() {
        try {
            TypedTests.finish()
        } finally {
            screen.shell("am force-stop $FIXTURES")
            screen.shell("appops set $FIXTURES SYSTEM_ALERT_WINDOW $overlayMode")
        }
    }

    @Test
    fun aTapThatPassesThroughAnotherAppsWindowSendsNothing() {
        screen.open("ObscuringActivity", "Obscuring fixture")
        TypedTests.bringScreen(screen)
        check(VoiceTests.waitUntil(10_000) { screen.showsWindow(OVERLAY) }) { "The fixture's window is not over the screen" }
        val before = conversations.view.value.command
        val words = TypedTests.marked(WORDS)
        compose.onNodeWithTag(FIELD_TAG).performTextInput(words)

        tapSend()
        Thread.sleep(SETTLE_MILLIS)

        assertEquals(before, conversations.view.value.command)
        compose.onNodeWithTag(FIELD_TAG).assertTextContains(words)

        // Without the window, the same tap sends: the tap above was on Send.
        screen.shell("am force-stop $FIXTURES")
        check(VoiceTests.waitUntil(10_000) { !screen.showsWindow(OVERLAY) }) { "The fixture's window stayed" }
        tapSend()
        check(VoiceTests.waitUntil(10_000) { conversations.view.value.command > before }) { "The tap without the window sent nothing" }
        conversations.view.value.id?.let(TypedTests::own)
    }

    @Test
    fun anIntentsWordsAndDataNeverReachTheField() {
        val before = conversations.view.value.command

        screen.shell(
            "am start -W -n $KESTREL/.app.conversation.screen.ConversationActivity -a android.intent.action.SEND " +
                "-t text/plain --es android.intent.extra.TEXT Reminders",
        )
        screen.shell("am start -W -n $KESTREL/.app.conversation.screen.ConversationActivity -a android.intent.action.VIEW -d kestrel:Reminders")
        Thread.sleep(SETTLE_MILLIS)

        compose.onNodeWithTag(FIELD_TAG).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        assertEquals(before, conversations.view.value.command)
    }

    // Send's centre on the screen, read after the keyboard has settled, and tapped with Android's own input command.
    private fun tapSend() {
        compose.waitForIdle()
        Thread.sleep(SETTLE_MILLIS)
        val bounds = compose.onNodeWithText(TypedTests.text(R.string.conversation_send)).fetchSemanticsNode().boundsInWindow
        val origin = IntArray(2)
        compose.runOnUiThread { compose.activity.window.decorView.getLocationOnScreen(origin) }
        val centre = Point(origin[0] + bounds.center.x.roundToInt(), origin[1] + bounds.center.y.roundToInt())
        screen.shell("input tap ${centre.x} ${centre.y}")
    }

    private companion object {
        const val WORDS = "Open my messages"
        const val OVERLAY = "Obscuring overlay"
        const val DEFAULT_MODE = "default"
        const val SETTLE_MILLIS = 1_500L

        /** The app-op's mode in `appops get`, such as "SYSTEM_ALERT_WINDOW: allow; time=+1m ago"; none means default. */
        val OVERLAY_MODE = Regex("""SYSTEM_ALERT_WINDOW: (\w+)""")
    }
}
```

The code block above shows `TypedBoundaryTest` as Task 10 carried it out; its review fix `1b1b8f81` had already changed it (`awaitInputWindow`, `sendCentre` and `tap`, "Send moved between the two taps", and the third test, `theScreensViewsAreForAccessibilityToolsOnly`). Since Task 14's first run (`012ea255`, `7f46179a`, `fdfd1a2d`):
- The class KDoc says that an intent that names the screen with words and data puts nothing in its field "and does not become the screen's intent". After its two intents, `anIntentsWordsAndDataNeverReachTheField` checks that the screen kept the launcher's intent that started it: its action is `Intent.ACTION_MAIN`, and it has no data and no `Intent.EXTRA_TEXT` (`assertEquals`, `assertNull`; the imports `android.content.Intent` and `org.junit.Assert.assertNull`). This check failed in a red run on the old `ConversationActivity`, whose `setIntent` made the VIEW intent the screen's own (Task 8's note under `ConversationActivity`).
- The class applies `ScreenKeyboard.rule` at order 1 and its compose rule at order 2, and `TypedTests.bringScreen(screen)` is `TypedTests.bringScreen(screen, compose)`.
- `sendCentre()` waits with `ScreenKeyboard.settle(compose)` in place of `compose.waitForIdle()` and `Thread.sleep(SETTLE_MILLIS)`: the 1.5 s sleep waited for the same keyboard, and the settle waits for its condition, not for a time. The two other sleeps of `SETTLE_MILLIS` stay, because they wait before a check that nothing happened.

- [ ] **Step 6: Compile the tests and the fixture**

Run: `scripts\gradle.ps1 :app:assembleDebugAndroidTest :testapp:assembleDebug; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL. The 2 device tests run in Task 14.

- [ ] **Step 7: Commit**

```bash
git add build-logic/convention/src/main/kotlin/dev/kestrel/build/MergedManifestArgument.kt app/build.gradle.kts app/src/test/kotlin/dev/kestrel/app/conversation/screen/ScreenManifestTest.kt testapp/src/main/kotlin/dev/kestrel/testapp/ObscuringActivity.kt testapp/src/main/AndroidManifest.xml app/src/androidTest/kotlin/dev/kestrel/app/conversation/TypedBoundaryTest.kt
git commit -m "test(app): the typed-input boundary: the screen's only filter in the merged manifest, touches through another app's window, and intents with words" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 11: Past conversations

Design 9.5 and 9.6 of sub-project 7: the conversation screen's menu opens "Past conversations": a list, newest first, of the closed conversations within retention, each with its date, its first request (cut at 80 characters, as the index keeps it) and its number of commands, and a search field that filters the list with the search's own rule (Task 3's `HistorySearch.matching`). Opening one shows its transcript, read-only, with "Continue this conversation", which sends `ContinueConversation(id)` through the text channel, and Delete. Each conversation can be deleted from the list or from its transcript, after a question; the engine never deletes the open one (Task 3's `deletePast`). A failure of the archive is reported by its class, and the list says that Kestrel could not read its past conversations.

Everything works on plan 7B's index of past conversations (design 9.2): the list's rows come from `ConversationStore.list()`, which reads no body, and the search from the index's shards; only an opened transcript reads its one body (`get(id)`). While the index is read or rebuilt, `list()` waits: the page says "Loading past conversations…" until its first read ends, and a later read keeps the rows that are shown, under a thin progress line, until its own come (review B-M3); a search that finds the index rebuilt again reads the list once more. While the index can be neither read nor rebuilt, `list()` throws `PastUnavailableException` and the search gives `PastMatches.Failed`, and the page says design 9.5's "Kestrel can't read past conversations right now. It will try again later." The store decodes and encodes on its caller's thread, so `PastConversations` calls it on a background dispatcher, and the search runs only once the owner's typing pauses, because each search ranks the whole index. The pages' state (the search's text, the open transcript, the question before a deletion, and the notice after it) lives in the screen's view model, so that a configuration change keeps it, and its rule, `PastPagesRules`, is Android-free and tested on the JVM. While the pages are open, the screen is in front, so the card desk gives the open question's card to the screen: the pages draw it under their title, with its buttons, and its "Reply…" closes the pages and puts the keyboard on the conversation's field (review N6). The deletion's question is a dialog, a window of its own, and the menu is a popup; both carry Task 7's `GuardWindow`, so that a touch that passed through another app's window answers neither (design 12.2).

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/conversation/screen/PastList.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/screen/PastPagesRules.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/screen/PastConversations.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/screen/PastViews.kt`, `app/src/main/res/values/strings_past.xml`
- Modify: `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationViews.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationApp.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationScreenViewModel.kt`, `app/src/main/res/values/strings_conversation.xml`, `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`
- Test: create `app/src/test/kotlin/dev/kestrel/app/conversation/screen/PastListTest.kt`, `app/src/test/kotlin/dev/kestrel/app/conversation/screen/PastPagesRulesTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/conversation/PastConversationsTest.kt`; modify `app/src/androidTest/kotlin/dev/kestrel/app/conversation/screen/ConversationViewsTest.kt`

**Interfaces:**
- Consumes: plan 7B's index store (Tasks 5a to 5d): `ConversationStore.list()` (from the index; it waits while the index is read or rebuilt, and throws `PastUnavailableException` while it cannot be read) and `get(id)` (one body), `ConversationSummary(id, openedAtWall, lastWall, commands, firstRequest)`, `ConversationSnapshot`; plan 7B's `ConversationClock`, `ConversationTexts.date`, `InputIntent.ContinueConversation`; Task 3's `HistorySearch.matching`, `PastMatches` (with `Failed`), `ConversationEngine.deletePast` and `PastDeletion` (with `UNAVAILABLE`); Task 6's `Transcript.of`; Task 7's `TranscriptList`, `ConversationActions`, `ConversationScreen` and `GuardWindow`; Task 8's `ConversationApp`, `ConversationScreenViewModel` and `TypedTests` (`marker`, `marked`, `send`); plan 7C's `AppGraph.conversationStore` and `AndroidConversationClock`.
- Produces: `internal data class PastRow(id, date, request, commands)`; `internal object PastList { fun rows(summaries, sinceWall, matching, zone): List<PastRow> }`; `internal enum class PastNotice { OPEN_NOW, UNAVAILABLE, DELETE_FAILED }`, `internal data class PastPagesState(query, opened, deleting, notice, deletions)` and `internal object PastPagesRules` with `opened(state, id)`, `back(state): PastPagesState?` and `deleted(state, id, outcome)`; `internal data class PastTranscript(id, date, lines)`; `internal sealed interface PastListing` (`Rows(rows)`, `Unavailable`, `Failed`); `internal class PastConversations(store, search, engine, keptDays, clock, report, background = Dispatchers.Default)` with `suspend fun rows(text): PastListing`, `suspend fun transcript(id): PastTranscript?` and `suspend fun delete(id): PastDeletion`; `internal class PastActions(query, open, back, askDelete, dismissDelete, confirmDelete, continueConversation)` and `@Composable internal fun PastPages(state, past, zone, actions)`; `internal const val PAST_SEARCH_TAG = "past-search"` and `internal fun pastRowTag(id): String`; `ConversationActions.past`; `ConversationScreenViewModel` with `pastPages`, `pastConversations`, `openPast()`, `closePast()`, `past(change)`, `confirmDelete()` and `continuePast(id)`; `AppGraph.pastConversations`; the strings `past_*`, the plurals `past_commands` and the string `conversation_more`.

Counts: `:app` JVM tests A₁₀ + 4 (`PastListTest` 2, `PastPagesRulesTest` 2), where A₁₀ is the count after Task 10; `:app` device tests AD₁₀ + 3 (`PastConversationsTest` 2, `ConversationViewsTest` + 1). At `1b1b8f81` (Task 10 with the review fixes of Tasks 1 to 10; the amendment of 2026-11-27), A₁₀ = 813, AD₁₀ = 496 and `:core:conversation` 308, so this task ends at 817 and 499.
- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/dev/kestrel/app/conversation/screen/PastListTest.kt`:

```kotlin
package dev.kestrel.app.conversation.screen

import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.store.ConversationSummary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The list of past conversations (design 9.5 of sub-project 7): which conversations it holds, and what a row says. */
class PastListTest {
    private val zone = ZoneId.of("Europe/Belgrade")
    private val now = ZonedDateTime.of(2026, 11, 20, 18, 4, 0, 0, zone).toInstant().toEpochMilli()

    private fun summary(id: Char, lastWall: Long, request: String?, commands: Int = 1) =
        ConversationSummary(ConversationId(id.toString().repeat(32)), openedAtWall = lastWall - 60_000, lastWall = lastWall, commands = commands, firstRequest = request)

    @Test
    fun theListHoldsTheConversationsWithinRetentionNewestFirstAndWhileTheSearchHasWordsOnlyTheMatchingOnes() {
        val expired = summary('a', now - 31 * DAY, "Open Viber")
        val older = summary('b', now - 2 * DAY, "Read my latest SMS")
        val newer = summary('c', now - DAY, "Find the restaurant Mila sent me")
        val since = now - 30 * DAY

        assertEquals(listOf(newer.id, older.id), PastList.rows(listOf(older, expired, newer), since, null, zone).map { it.id })
        assertEquals(listOf(older.id), PastList.rows(listOf(older, expired, newer), since, setOf(older.id, expired.id), zone).map { it.id })
        assertEquals(emptyList<PastRow>(), PastList.rows(listOf(older, newer), since, emptySet(), zone))
    }

    @Test
    fun aRowHasItsDateItsFirstRequestAsTheIndexCutItAndItsNumberOfCommands() {
        // The index keeps a first request cut at 80 characters (plan 7B's PastIndex.FIRST_REQUEST); the list cuts nothing more.
        val cut = "Find the restaurant that Mila sent me last week, and book a table for four on…"
        val rows = PastList.rows(listOf(summary('a', now, cut, commands = 3), summary('b', now - 1, null)), 0, null, zone)

        assertEquals("Friday, 20 November 2026", rows[0].date)
        assertEquals(cut, rows[0].request)
        assertEquals(3, rows[0].commands)
        assertEquals("", rows[1].request)
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
```

Create `app/src/test/kotlin/dev/kestrel/app/conversation/screen/PastPagesRulesTest.kt`:

```kotlin
package dev.kestrel.app.conversation.screen

import dev.kestrel.conversation.engine.PastDeletion
import dev.kestrel.conversation.model.ConversationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The past conversations' pages (design 9.5 of sub-project 7): where Back goes, and what a deletion's end shows. */
class PastPagesRulesTest {
    private val one = ConversationId("1".repeat(32))
    private val two = ConversationId("2".repeat(32))

    @Test
    fun backGoesFromATranscriptToTheListAndFromTheListOutOfThePages() {
        val list = PastPagesState(query = "trip", notice = PastNotice.DELETE_FAILED)
        val transcript = PastPagesRules.opened(list, one)

        assertEquals(PastPagesState(query = "trip", opened = one), transcript)
        assertEquals(PastPagesState(query = "trip"), PastPagesRules.back(transcript))
        assertNull(PastPagesRules.back(list))
    }

    @Test
    fun aDeletionLeavesItsTranscriptAndCountsAndOtherwiseTheNoticeSaysWhy() {
        val shown = PastPagesState(opened = one, notice = PastNotice.OPEN_NOW)

        assertEquals(PastPagesState(deletions = 1), PastPagesRules.deleted(shown, one, PastDeletion.DELETED))
        assertEquals(PastPagesState(opened = one, deletions = 1), PastPagesRules.deleted(shown, two, PastDeletion.DELETED))
        assertEquals(PastPagesState(opened = one, notice = PastNotice.OPEN_NOW), PastPagesRules.deleted(shown.copy(notice = null), one, PastDeletion.OPEN))
        assertEquals(PastPagesState(opened = one, notice = PastNotice.UNAVAILABLE), PastPagesRules.deleted(shown, one, PastDeletion.UNAVAILABLE))
        assertEquals(PastPagesState(opened = one, notice = PastNotice.DELETE_FAILED), PastPagesRules.deleted(shown, one, PastDeletion.FAILED))
    }
}
```

In `app/src/androidTest/kotlin/dev/kestrel/app/conversation/screen/ConversationViewsTest.kt`, replace:

```kotlin
        settings = { done += "settings" },
        setup = { done += "setup" },
```

with:

```kotlin
        settings = { done += "settings" },
        past = { done += "past" },
        setup = { done += "setup" },
```

In `app/src/androidTest/kotlin/dev/kestrel/app/conversation/screen/ConversationViewsTest.kt`, add before its last closing brace:

```kotlin

    // Design 9.5 of sub-project 7: the top bar's menu opens "Past conversations".
    @Test
    fun theMenuOpensThePastConversations() {
        show(mutableStateOf(ScreenUi(emptyList(), GoalControls.None, stopAndNew = false, card = null)))

        compose.onNodeWithText(text(R.string.conversation_more)).performClick()
        compose.onNodeWithText(text(R.string.past_title)).performClick()

        assertEquals(listOf("past"), done)
    }
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/conversation/PastConversationsTest.kt`:

```kotlin
package dev.kestrel.app.conversation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.app.R
import dev.kestrel.app.agent.AgentTests
import dev.kestrel.app.conversation.screen.ConversationActivity
import dev.kestrel.app.conversation.screen.FIELD_TAG
import dev.kestrel.app.conversation.screen.PAST_SEARCH_TAG
import dev.kestrel.app.conversation.screen.pastRowTag
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.model.ConversationId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/**
 * Past conversations on kestrel-api37 (design 9.5, 9.6 and 15.2 of sub-project 7): the list of closed conversations, its
 * search, a transcript and "Continue this conversation", and the deletion from the list and from a transcript, each after
 * its question. The owner's own past conversations stay in the list: each test reads only the rows of the conversations
 * that it made, which it finds by their tags and through the search field with the run's marker, and deletes only those.
 */
@RunWith(AndroidJUnit4::class)
class PastConversationsTest {
    @get:Rule(order = 0)
    val screen = ScreenServiceRule()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ConversationActivity>()

    private val graph get() = TypedTests.graph
    private val conversations get() = graph.conversations
    private val fail = call("fail", null, null, null, "reason" to "I stop here.")

    @Before
    fun start() {
        TypedTests.prepare(ScriptedModel(listOf(fail, fail)))
    }

    @After
    fun stop() {
        try {
            TypedTests.finish()
        } finally {
            screen.shell("am force-stop $FIXTURES")
        }
    }

    @Test
    fun theListShowsTheClosedConversationsItsSearchFiltersThemAndOneIsContinued() {
        val restaurant = closed(RESTAURANT)
        val fixture = closed(FIXTURE)

        openList(TypedTests.marker)
        awaitRow(restaurant)
        awaitRow(fixture)
        // The newest first: the fixture's row is above the restaurant's.
        assertTrue(top(fixture) < top(restaurant))
        compose.onNodeWithTag(PAST_SEARCH_TAG).performTextClearance()
        compose.onNodeWithTag(PAST_SEARCH_TAG).performTextInput("restaurant")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(pastRowTag(fixture)).fetchSemanticsNodes().isEmpty() }
        awaitRow(restaurant)
        compose.onNodeWithText(TypedTests.marked(RESTAURANT)).performClick()
        compose.onNodeWithText(TypedTests.text(R.string.past_continue)).performClick()

        check(VoiceTests.waitUntil(10_000) { conversations.view.value.id == restaurant }) { "The conversation was not continued" }
        val continued = TypedTests.text(R.string.conversation_continued, ConversationTexts.date(checkNotNull(conversations.view.value.continuedAtWall), ZoneId.systemDefault()))
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(continued)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(FIELD_TAG).assertIsDisplayed()
    }

    @Test
    fun aPastConversationIsDeletedAfterItsQuestionFromTheListAndFromItsTranscript() {
        val restaurant = closed(RESTAURANT)
        val fixture = closed(FIXTURE)

        openList(TypedTests.marker)
        awaitRow(fixture)
        compose.onNode(hasText(TypedTests.text(R.string.past_delete)) and hasAnyAncestor(hasTestTag(pastRowTag(fixture)))).performClick()
        confirmDelete()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(pastRowTag(fixture)).fetchSemanticsNodes().isEmpty() }
        assertTrue(runBlocking { graph.conversationStore.list() }.none { it.id == fixture })

        awaitRow(restaurant)
        compose.onNodeWithText(TypedTests.marked(RESTAURANT)).performClick()
        compose.onNodeWithText(TypedTests.text(R.string.past_continue)).assertIsDisplayed()
        compose.onNodeWithText(TypedTests.text(R.string.past_delete)).performClick()
        confirmDelete()

        compose.waitUntil(5_000) { compose.onAllNodesWithTag(PAST_SEARCH_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(pastRowTag(restaurant)).fetchSemanticsNodes().isEmpty() }
        assertTrue(runBlocking { graph.conversationStore.list() }.none { it.id == restaurant })
    }

    // A closed conversation whose one command was [words] with the run's marker, typed on the screen over the fixture.
    private fun closed(words: String): ConversationId {
        screen.open("ViewsActivity", "Views fixture")
        TypedTests.bringScreen(screen)
        AgentTests.ended(TypedTests.send(compose, words))
        val id = checkNotNull(conversations.view.value.id)
        TypedTests.bringScreen(screen)
        compose.onNodeWithText(TypedTests.text(R.string.conversation_new)).performClick()
        check(VoiceTests.waitUntil(10_000) { conversations.view.value.id == null }) { "The conversation did not close" }
        return id
    }

    // The list with [query] in its search field, which keeps the owner's own conversations out of the way.
    private fun openList(query: String) {
        compose.onNodeWithText(TypedTests.text(R.string.conversation_more)).performClick()
        compose.onNodeWithText(TypedTests.text(R.string.past_title)).performClick()
        compose.onNodeWithTag(PAST_SEARCH_TAG).performTextInput(query)
    }

    private fun awaitRow(id: ConversationId) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(pastRowTag(id)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun top(id: ConversationId): Float = compose.onNodeWithTag(pastRowTag(id)).fetchSemanticsNode().boundsInRoot.top

    private fun confirmDelete() {
        compose.onNodeWithText(TypedTests.text(R.string.past_delete_question)).assertIsDisplayed()
        compose.onNode(hasText(TypedTests.text(R.string.past_delete)) and hasAnyAncestor(isDialog())).performClick()
    }

    private companion object {
        const val RESTAURANT = "Find the restaurant Mila sent me"
        const val FIXTURE = "Look at the fixture"
    }
}
```

The code block above shows `PastConversationsTest` as Task 11 carried it out. Since Task 14's first run (`7e755cc0`, `51b5517d`, and `7f46179a` and `fdfd1a2d` for the keyboard):
- `awaitRow(id)` waits until the row is shown and the list's progress line, tagged `PAST_READING_TAG` (`past-reading`, imported from the screen's package), is gone, under a comment that says why. `aPastConversationIsDeletedAfterItsQuestionFromTheListAndFromItsTranscript` failed on every run: the list's search waits for a pause in the typing with `delay(300)`, which under the `junit4.v2` compose rule runs on the test's clock, and only a `waitUntil` or a gesture moves that clock on. The old `awaitRow` found the row among the earlier read's rows (those of the blank search), whose Delete is off while the new read runs, so it returned at once, the clock never moved, and Delete stayed off. Now its own `waitUntil` moves the clock until the read of the search has ended.
- The class applies `ScreenKeyboard.rule` at order 1 and its compose rule at order 2, and both `TypedTests.bringScreen(screen)` calls in `closed(words)` are `TypedTests.bringScreen(screen, compose)` (Task 8's note under `TypedTests`).

- [ ] **Step 2: Run the JVM tests to see them fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.conversation.screen.PastListTest --tests=dev.kestrel.app.conversation.screen.PastPagesRulesTest`
Expected: FAIL: the compiler does not know `PastList`, `PastRow`, `PastPagesRules`, `PastPagesState` and `PastNotice`.

- [ ] **Step 3: The list's rule and its data**

Create `app/src/main/kotlin/dev/kestrel/app/conversation/screen/PastList.kt`:

```kotlin
package dev.kestrel.app.conversation.screen

import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.store.ConversationSummary
import java.time.ZoneId

/** One past conversation in the list (design 9.5 of sub-project 7): its date, its first request and its number of commands. */
internal data class PastRow(val id: ConversationId, val date: String, val request: String, val commands: Int) {
    override fun toString(): String = "PastRow(commands=$commands, request=${request.length} chars)"
}

/** Which past conversations the list holds, and what each row says (design 9.5 of sub-project 7). */
internal object PastList {
    /**
     * The rows for [summaries], the closed conversations: those whose last change is at or after [sinceWall] (within
     * retention), the newest change first, and, while the search field holds words that count, only those in [matching].
     * A row's date is the day on which its conversation began, in [zone]; its request is the first request as the index
     * keeps it, already cut at 80 characters.
     */
    fun rows(summaries: List<ConversationSummary>, sinceWall: Long, matching: Set<ConversationId>?, zone: ZoneId): List<PastRow> =
        summaries
            .filter { it.lastWall >= sinceWall && (matching == null || it.id in matching) }
            .sortedByDescending { it.lastWall }
            .map { PastRow(it.id, ConversationTexts.date(it.openedAtWall, zone), it.firstRequest.orEmpty(), it.commands) }
}
```
Create `app/src/main/kotlin/dev/kestrel/app/conversation/screen/PastConversations.kt`:

```kotlin
package dev.kestrel.app.conversation.screen

import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.engine.ConversationClock
import dev.kestrel.conversation.engine.ConversationEngine
import dev.kestrel.conversation.engine.PastDeletion
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.store.ArchivedConversationStore
import dev.kestrel.conversation.store.ConversationStore
import dev.kestrel.conversation.store.HistorySearch
import dev.kestrel.conversation.store.PastMatches
import dev.kestrel.conversation.store.PastUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A past conversation's transcript, read-only (design 9.5 of sub-project 7), with the date on which it began. */
internal data class PastTranscript(val id: ConversationId, val date: String, val lines: List<TranscriptLine>) {
    override fun toString(): String = "PastTranscript(lines=${lines.size})"
}

/**
 * What the list shows (design 9.5 of sub-project 7): its rows; or that the index of past conversations cannot be read now,
 * which the store tries again by itself later; or that the archive failed, which was reported.
 */
internal sealed interface PastListing {
    data class Rows(val rows: List<PastRow>) : PastListing

    data object Unavailable : PastListing

    data object Failed : PastListing
}

/**
 * The past conversations as the conversation screen shows them (design 9.5 of sub-project 7): the closed ones within the
 * owner's retention ([keptDays]), filtered by the search's own rule, each one's transcript, and their deletion through the
 * engine, which never deletes the open one. The list and the search read plan 7B's index of past conversations and no
 * body; a transcript reads its one body. The store decodes and encodes on its caller's thread, so every read runs on
 * [background], never on the main thread. A failure of the archive is reported to [report] by its class, as the other
 * parts report theirs.
 */
internal class PastConversations(
    private val store: ConversationStore,
    private val search: HistorySearch,
    private val engine: ConversationEngine,
    private val keptDays: () -> Int,
    private val clock: ConversationClock,
    private val report: (String, Throwable) -> Unit,
    private val background: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * The list while the search field holds [text]. It waits while the index is read or rebuilt (the page says "Loading past
     * conversations…"); a search that finds the index rebuilt again reads the list once more.
     */
    suspend fun rows(text: String): PastListing = withContext(background) {
        try {
            var listing: PastListing? = null
            while (listing == null) {
                val summaries = store.list()
                listing = when (val matching = if (text.isBlank()) PastMatches.All else search.matching(text)) {
                    PastMatches.All -> PastListing.Rows(PastList.rows(summaries, sinceWall(), null, clock.zone()))
                    is PastMatches.Only -> PastListing.Rows(PastList.rows(summaries, sinceWall(), matching.ids, clock.zone()))
                    PastMatches.Rebuilding -> null
                    PastMatches.Failed -> PastListing.Unavailable
                }
            }
            listing
        } catch (e: PastUnavailableException) {
            PastListing.Unavailable
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("Reading past conversations", e)
            PastListing.Failed
        }
    }

    /** Conversation [id]'s transcript, its one body read; null when it is gone, is the open conversation now, or the archive failed. */
    suspend fun transcript(id: ConversationId): PastTranscript? = withContext(background) {
        try {
            store.get(id)?.takeIf { !it.open && it.lastWall >= sinceWall() }?.let { snapshot ->
                PastTranscript(
                    snapshot.id,
                    ConversationTexts.date(snapshot.openedAtWall, clock.zone()),
                    Transcript.of(snapshot.records, snapshot.continuedAtWall, endedAtWall = snapshot.lastWall),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("Reading a past conversation", e)
            null
        }
    }

    suspend fun delete(id: ConversationId): PastDeletion = engine.deletePast(id)

    private fun sinceWall(): Long = clock.wallMillis() - keptDays() * ArchivedConversationStore.DAY_MILLIS

    override fun toString(): String = "PastConversations"
}
```
Create `app/src/main/kotlin/dev/kestrel/app/conversation/screen/PastPagesRules.kt`:

```kotlin
package dev.kestrel.app.conversation.screen

import dev.kestrel.conversation.engine.PastDeletion
import dev.kestrel.conversation.model.ConversationId

/** What a deletion that did not happen says on the past conversations' pages (design 9.5 of sub-project 7). */
internal enum class PastNotice {
    /** The conversation is the open one now, as after the owner continued it, so it stays. */
    OPEN_NOW,

    /** The index of past conversations cannot be read now (design 9.2); the store tries again by itself later. */
    UNAVAILABLE,

    DELETE_FAILED,
}

/**
 * The past conversations' pages (design 9.5 of sub-project 7): the search field's text, the conversation whose transcript
 * is open, the one that the deletion's question asks about, the notice after a deletion, and how many deletions the list
 * has seen, so that it reads itself again after each. toString leaves the owner's words out.
 */
internal data class PastPagesState(
    val query: String = "",
    val opened: ConversationId? = null,
    val deleting: ConversationId? = null,
    val notice: PastNotice? = null,
    val deletions: Int = 0,
) {
    override fun toString(): String =
        "PastPagesState(query=${query.length} chars, opened=${opened != null}, deleting=${deleting != null}, notice=$notice, deletions=$deletions)"
}

/** How the past conversations' pages change (design 9.5 of sub-project 7); the screen's view model keeps their state. */
internal object PastPagesRules {
    fun opened(state: PastPagesState, id: ConversationId): PastPagesState = state.copy(opened = id, notice = null)

    /** Back: from a transcript to the list, and from the list out of the pages (null). */
    fun back(state: PastPagesState): PastPagesState? = if (state.opened == null) null else state.copy(opened = null, notice = null)

    /** How the deletion of [id] ended: a deleted conversation's transcript closes and the list reads itself again. */
    fun deleted(state: PastPagesState, id: ConversationId, outcome: PastDeletion): PastPagesState = when (outcome) {
        PastDeletion.DELETED -> state.copy(opened = state.opened.takeIf { it != id }, notice = null, deletions = state.deletions + 1)
        PastDeletion.OPEN -> state.copy(notice = PastNotice.OPEN_NOW)
        PastDeletion.UNAVAILABLE -> state.copy(notice = PastNotice.UNAVAILABLE)
        PastDeletion.FAILED -> state.copy(notice = PastNotice.DELETE_FAILED)
    }
}
```

- [ ] **Step 4: Run the JVM tests**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.conversation.screen.PastListTest --tests=dev.kestrel.app.conversation.screen.PastPagesRulesTest`
Expected: 4 tests, all PASS.

- [ ] **Step 5: The strings and the views**

Create `app/src/main/res/values/strings_past.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Past conversations on the conversation screen (design 9.5 of sub-project 7; plan 7F). -->
<resources>
    <string name="past_title">Past conversations</string>
    <string name="past_back">Back</string>
    <string name="past_search">Search past conversations</string>
    <string name="past_empty">No past conversations.</string>
    <string name="past_none_found">No past conversation matches.</string>
    <string name="past_loading">Loading past conversations…</string>
    <string name="past_unavailable">Kestrel can\'t read past conversations right now. It will try again later.</string>
    <string name="past_failed">Kestrel could not read its past conversations.</string>
    <string name="past_gone">This conversation is no longer kept.</string>
    <plurals name="past_commands">
        <item quantity="one">%d command</item>
        <item quantity="other">%d commands</item>
    </plurals>
    <string name="past_continue">Continue this conversation</string>
    <string name="past_delete">Delete</string>
    <string name="past_cancel">Cancel</string>
    <string name="past_delete_question">Delete this conversation?</string>
    <string name="past_open_now">This conversation is open now, so it stays.</string>
    <string name="past_delete_failed">Kestrel could not delete the conversation.</string>
</resources>
```

In `app/src/main/res/values/strings_conversation.xml`, replace:

```xml
    <string name="conversation_settings">Settings</string>
```

with:

```xml
    <string name="conversation_settings">Settings</string>
    <string name="conversation_more">More</string>
```

Create `app/src/main/kotlin/dev/kestrel/app/conversation/screen/PastViews.kt`:

```kotlin
package dev.kestrel.app.conversation.screen

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.kestrel.app.R
import dev.kestrel.conversation.model.ConversationId
import kotlinx.coroutines.delay
import java.time.ZoneId

/** The list's search field, by which the device tests find it. */
internal const val PAST_SEARCH_TAG = "past-search"

/** A row of the list, by which the device tests find a conversation's row. */
internal fun pastRowTag(id: ConversationId): String = "past-row-${id.value}"

/** What the owner does on the past conversations' pages; the screen's view model changes their state. */
internal class PastActions(
    val query: (String) -> Unit,
    val open: (ConversationId) -> Unit,
    val back: () -> Unit,
    val askDelete: (ConversationId) -> Unit,
    val dismissDelete: () -> Unit,
    val confirmDelete: () -> Unit,
    val continueConversation: (ConversationId) -> Unit,
)

/**
 * "Past conversations" (design 9.5 of sub-project 7), as [state] says: the list with its search field, or one
 * conversation's transcript, read-only, with "Continue this conversation" and Delete. Each deletion asks first, in a
 * dialog that drops a touch that passed through another app's window (design 12.2). Back goes from the transcript to the
 * list, and from the list back to the conversation. [openCard] draws the open question's card under each page's title, as
 * the conversation does, because the card desk gives the card to the screen while it is in front (review N6).
 */
@Composable
internal fun PastPages(state: PastPagesState, past: PastConversations, zone: ZoneId, actions: PastActions, openCard: @Composable () -> Unit) {
    BackHandler(onBack = actions.back)

    val notice = when (state.notice) {
        PastNotice.OPEN_NOW -> R.string.past_open_now
        PastNotice.UNAVAILABLE -> R.string.past_unavailable
        PastNotice.DELETE_FAILED -> R.string.past_delete_failed
        null -> null
    }
    val shown = state.opened
    if (shown == null) {
        PastListPage(past, state.query, actions.query, state.deletions, notice, openCard, onOpen = actions.open, onDelete = actions.askDelete, onBack = actions.back)
    } else {
        PastTranscriptPage(
            past, shown, zone, notice, openCard,
            onContinue = { actions.continueConversation(shown) },
            onDelete = { actions.askDelete(shown) },
            onBack = actions.back,
        )
    }
    if (state.deleting != null) {
        AlertDialog(
            onDismissRequest = actions.dismissDelete,
            text = {
                GuardWindow()
                Text(stringResource(R.string.past_delete_question))
            },
            confirmButton = { TextButton(onClick = actions.confirmDelete) { Text(stringResource(R.string.past_delete)) } },
            dismissButton = { TextButton(onClick = actions.dismissDelete) { Text(stringResource(R.string.past_cancel)) } },
        )
    }
}
/** What a page has read: nothing yet, or [Done.value]. */
private sealed interface Loaded<out T> {
    data object Waiting : Loaded<Nothing>

    data class Done<T>(val value: T) : Loaded<T>
}

// The list, read again after each deletion ([deletions]) and, while the owner types, once he stops for a moment, because
// each search ranks the whole index. It says "Loading past conversations…" until its first read ends; a later read keeps the
// rows that are shown, under a thin progress line and without their Delete, until its own rows come (review B-M3 and B-N2).
@Composable
private fun PastListPage(
    past: PastConversations,
    query: String,
    onQuery: (String) -> Unit,
    deletions: Int,
    @StringRes notice: Int?,
    openCard: @Composable () -> Unit,
    onOpen: (ConversationId) -> Unit,
    onDelete: (ConversationId) -> Unit,
    onBack: () -> Unit,
) {
    var rows by remember { mutableStateOf<Loaded<PastListing>>(Loaded.Waiting) }
    var reading by remember { mutableStateOf(true) }
    LaunchedEffect(query, deletions) {
        reading = true
        if (query.isNotBlank()) delay(SEARCH_DELAY_MILLIS)
        rows = Loaded.Done(past.rows(query))
        reading = false
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        TopRow(stringResource(R.string.past_title), onBack)
        openCard()
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().testTag(PAST_SEARCH_TAG),
            placeholder = { Text(stringResource(R.string.past_search)) },
            singleLine = true,
        )
        notice?.let { Note(it) }
        val loaded = rows
        if (loaded is Loaded.Waiting) {
            Note(R.string.past_loading)
        } else if (loaded is Loaded.Done) {
            if (reading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            when (val listing = loaded.value) {
                PastListing.Unavailable -> Note(R.string.past_unavailable)
                PastListing.Failed -> Note(R.string.past_failed)
                is PastListing.Rows -> if (listing.rows.isEmpty()) {
                    Note(if (query.isBlank()) R.string.past_empty else R.string.past_none_found)
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listing.rows, key = { it.id.value }) { row -> PastRowCard(row, deletable = !reading, onOpen, onDelete) }
                    }
                }
            }
        }
    }
}

// A row's Delete waits while the list is read again (review B-N2 of plan 7F): a row that stays on the screen during the read
// may be one that the owner has just deleted.
@Composable
private fun PastRowCard(row: PastRow, deletable: Boolean, onOpen: (ConversationId) -> Unit, onDelete: (ConversationId) -> Unit) {
    Card(onClick = { onOpen(row.id) }, modifier = Modifier.fillMaxWidth().testTag(pastRowTag(row.id))) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp)) {
            Text(row.date, style = MaterialTheme.typography.labelMedium)
            Text(row.request, style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.past_commands, row.commands, row.commands), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onDelete(row.id) }, enabled = deletable) { Text(stringResource(R.string.past_delete)) }
            }
        }
    }
}

@Composable
private fun PastTranscriptPage(
    past: PastConversations,
    id: ConversationId,
    zone: ZoneId,
    @StringRes notice: Int?,
    openCard: @Composable () -> Unit,
    onContinue: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    var transcript by remember(id) { mutableStateOf<Loaded<PastTranscript?>>(Loaded.Waiting) }
    LaunchedEffect(id) { transcript = Loaded.Done(past.transcript(id)) }
    val loaded = transcript
    val shown = (loaded as? Loaded.Done)?.value
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        TopRow(shown?.date ?: stringResource(R.string.past_title), onBack)
        openCard()
        notice?.let { Note(it) }
        when {
            loaded is Loaded.Waiting -> Note(R.string.past_loading)
            shown == null -> Note(R.string.past_gone)
            else -> {
                TranscriptList(shown.lines, zone, Modifier.weight(1f).fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onContinue) { Text(stringResource(R.string.past_continue)) }
                    OutlinedButton(onClick = onDelete) { Text(stringResource(R.string.past_delete)) }
                }
            }
        }
    }
}

@Composable
private fun TopRow(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.past_back)) }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun Note(@StringRes text: Int) {
    Text(stringResource(text), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
}

/** How long the owner's typing pauses before the search runs. */
private const val SEARCH_DELAY_MILLIS = 300L
```

The code block above shows `PastViews.kt` as Task 11 carried it out. Since Task 14's first run, `51b5517d` adds `internal const val PAST_READING_TAG = "past-reading"` after `PAST_SEARCH_TAG`, and the list's `LinearProgressIndicator` carries it (`.testTag(PAST_READING_TAG)`), so that `PastConversationsTest`'s `awaitRow` knows by that tag that the list's read has ended (see the note under that test).

In `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationViews.kt`, replace:

```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
```

with:

```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationViews.kt`, replace:

```kotlin
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
```

with:

```kotlin
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationViews.kt`, replace:

```kotlin
    val settings: () -> Unit,
    val setup: () -> Unit,
```

with:

```kotlin
    val settings: () -> Unit,
    val past: () -> Unit,
    val setup: () -> Unit,
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationViews.kt`, replace:

```kotlin
            TextButton(onClick = actions.settings) { Text(stringResource(R.string.conversation_settings)) }
        }
```

with:

```kotlin
            TextButton(onClick = actions.settings) { Text(stringResource(R.string.conversation_settings)) }
            MoreMenu(actions)
        }
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationViews.kt`, replace:

```kotlin
/**
 * Stop while the goal works, with plan 7E's words while its round waits behind the owner's instant command, whose own text
```

with:

```kotlin
/** The top bar's menu (design 10.1 and 9.5 of sub-project 7): "Past conversations". */
@Composable
private fun MoreMenu(actions: ConversationActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(stringResource(R.string.conversation_more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // The menu is a window of its own (design 12.2).
            GuardWindow()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.past_title)) },
                onClick = {
                    open = false
                    actions.past()
                },
            )
        }
    }
}

/**
 * Stop while the goal works, with plan 7E's words while its round waits behind the owner's instant command, whose own text
```

- [ ] **Step 6: The screen opens the pages, and the app graph builds their data**

In `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationScreenViewModel.kt`, replace:

```kotlin
    val zone: ZoneId get() = AndroidConversationClock.zone()
```

with:

```kotlin
    val zone: ZoneId get() = AndroidConversationClock.zone()

    private val pages = MutableStateFlow<PastPagesState?>(null)

    /** The past conversations' pages while they are open (design 9.5), kept across configuration changes. */
    val pastPages: StateFlow<PastPagesState?> = pages.asStateFlow()

    val pastConversations: PastConversations get() = graph.pastConversations
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationScreenViewModel.kt`, replace:

```kotlin
    fun newConversation() = model.newConversation()
```

with:

```kotlin
    fun newConversation() = model.newConversation()

    fun openPast() {
        pages.value = PastPagesState()
    }

    fun closePast() {
        pages.value = null
    }

    /** Changes the open pages; a change to null closes them. */
    fun past(change: (PastPagesState) -> PastPagesState?) = pages.update { it?.let(change) }

    /** The owner said yes to the deletion's question: the engine deletes the conversation, which never is the open one. */
    fun confirmDelete() {
        val id = pages.value?.deleting ?: return
        past { it.copy(deleting = null) }
        viewModelScope.launch {
            val outcome = graph.pastConversations.delete(id)
            past { PastPagesRules.deleted(it, id, outcome) }
        }
    }

    /** "Continue this conversation" goes through the text channel, as the owner's other inputs do (design 9.6). */
    fun continuePast(id: ConversationId) {
        input(InputIntent.ContinueConversation(id))
        closePast()
    }
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationScreenViewModel.kt`, replace:

```kotlin
import dev.kestrel.conversation.model.InputIntent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
```

with:

```kotlin
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.InputIntent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationApp.kt`, replace:

```kotlin
    val card by screen.card.collectAsState()
```

with:

```kotlin
    val card by screen.card.collectAsState()
    val pages by screen.pastPages.collectAsState()
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationApp.kt`, replace:

```kotlin
    LaunchedEffect(replyTo, card) {
        if (replyTo != null && card?.question == replyTo) focus.requestFocus()
    }
```

with:

```kotlin
    // "Reply…" brings the conversation back from the past conversations, and then puts the keyboard on its field.
    LaunchedEffect(replyTo, card) {
        if (replyTo != null && card?.question == replyTo) screen.closePast()
    }
    var replyField by remember { mutableStateOf(false) }
    LaunchedEffect(replyTo, card, pages, replyField) {
        if (pages == null && (replyField || (replyTo != null && card?.question == replyTo))) {
            focus.requestFocus()
            replyField = false
        }
    }
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationApp.kt`, replace:

```kotlin
        settings = onSettings,
        setup = onSetup,
```

with:

```kotlin
        settings = onSettings,
        past = screen::openPast,
        setup = onSetup,
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/screen/ConversationApp.kt`, replace:

```kotlin
    // The card's buttons arm again on each of the screen's visits in front (review of Task 7).
    CompositionLocalProvider(LocalScreenVisit provides visit) {
        ConversationScreen(ConversationScreenRules.ui(state, view, card), field, { field = it }, setupNeeded, screen.zone, focus, actions)
    }
}
```

with:

```kotlin
    // The card's buttons arm again on each of the screen's visits in front (review of Task 7), on the conversation and on
    // the past conversations' pages alike.
    CompositionLocalProvider(LocalScreenVisit provides visit) {
        val shown = pages
        if (shown != null) {
            val past = PastActions(
                query = { text -> screen.past { it.copy(query = text) } },
                open = { id -> screen.past { PastPagesRules.opened(it, id) } },
                back = { screen.past(PastPagesRules::back) },
                askDelete = { id -> screen.past { it.copy(deleting = id) } },
                dismissDelete = { screen.past { it.copy(deleting = null) } },
                confirmDelete = screen::confirmDelete,
                continueConversation = screen::continuePast,
            )
            // "Reply…" on the card closes the pages and then puts the keyboard on the conversation's field.
            PastPages(shown, screen.pastConversations, screen.zone, past) {
                card?.let { open ->
                    CardRow(open, onReply = {
                        screen.closePast()
                        replyField = true
                    }, actions = actions)
                }
            }
        } else {
            ConversationScreen(ConversationScreenRules.ui(state, view, card), field, { field = it }, setupNeeded, screen.zone, focus, actions)
        }
    }
}
```
In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
    private val conversationSearch: HistorySearch by lazy {
        HistorySearch(conversationStore, { conversationSettings.value.keptDays }, AndroidConversationClock)
    }
```

with:

```kotlin
    private val conversationSearch: HistorySearch by lazy {
        HistorySearch(conversationStore, { conversationSettings.value.keptDays }, AndroidConversationClock)
    }

    /** The conversation screen's past conversations (design 9.5 of sub-project 7; plan 7F), within the owner's retention. */
    internal val pastConversations: PastConversations by lazy {
        PastConversations(conversationStore, conversationSearch, conversations, { conversationSettings.value.keptDays }, AndroidConversationClock, reportFailure)
    }
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
import dev.kestrel.app.conversation.screen.ConversationActivity
```

with:

```kotlin
import dev.kestrel.app.conversation.screen.ConversationActivity
import dev.kestrel.app.conversation.screen.PastConversations
```

- [ ] **Step 7: Run the tests and the builds**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL; `:app` JVM A₁₀ + 4 tests, all PASS. The 3 device tests run in Task 14.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/dev/kestrel/app/conversation/screen app/src/main/res/values/strings_past.xml app/src/main/res/values/strings_conversation.xml app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/test/kotlin/dev/kestrel/app/conversation/screen/PastListTest.kt app/src/test/kotlin/dev/kestrel/app/conversation/screen/PastPagesRulesTest.kt app/src/androidTest/kotlin/dev/kestrel/app/conversation/PastConversationsTest.kt app/src/androidTest/kotlin/dev/kestrel/app/conversation/screen/ConversationViewsTest.kt
git commit -m "feat(app): past conversations on the conversation screen: the list with its search, a transcript, Continue this conversation and Delete" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 12: The Conversations page

Design 13 of sub-project 7: a settings page "Conversations", stored encrypted in `SettingKeys.CONVERSATIONS` (plan 7C): the quiet time between commands, the follow-up window, "Speak replies to typed commands", how long past conversations are kept, one round's limits (model replies, working time, and AI Credits only for a default profile that counts them), and "Delete all past conversations" with a question, which Task 3's `deleteAllPast` answers from the index of past conversations without reading a body, beside the conversation's inbox, and whose dialog carries Task 7's `GuardWindow`; while it runs, which may take minutes when the index is rebuilt, the page says "Deleting past conversations…" and offers no second deletion, and it reads out the outcome when it comes; the engine holds that state (`allPastDeletion`), so the page shows it also after the owner left it and came back (review B-M4). Each change counts at once, as on the other pages: the engine follows the stored settings (plan 7D), and plan 7C's retention purges again when the retention changes. The page offers each setting as a list of choices, as the Listening page does; the round's limits take the choices below, within the design's ranges. Every follow-up choice is at most 30 s, the shortest quiet time, so the follow-up window is never longer than the quiet time. The Home page gets a row for the page.

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/settings/ConversationsPage.kt`, `app/src/main/res/values/strings_conversations_page.xml`
- Modify: `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt`, `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt`, `app/src/main/kotlin/dev/kestrel/app/settings/ListeningPage.kt`, `app/src/main/kotlin/dev/kestrel/app/agent/AppModelPort.kt`, `app/src/main/kotlin/dev/kestrel/app/settings/ProvidersPage.kt`, `core/providers/src/main/kotlin/dev/kestrel/providers/profiles/Profile.kt`
- Test: modify `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`

**Interfaces:**
- Consumes: plan 7B's `ConversationSettings` (`QUIET_CHOICES`, `FOLLOW_UP_CHOICES`, `RETENTION_CHOICES`, `REPLIES`, `WORK_MINUTES`, `CREDITS`) and `GoalLimits`; plan 7C's `SettingKeys.CONVERSATIONS`; Task 3's `ConversationEngine.deleteAllPast`, `allPastDeletion`, `PastDeletion` and `AllPastDeletion`; Task 7's `GuardWindow`; `ProvidersSetting.defaultProfile`; the Listening page's `OptionSwitch`.
- Produces: `SettingsPage.CONVERSATIONS`; `SettingsData.conversations: Flow<ConversationSettings>`, `setConversations(settings)`, `countsCredits: Flow<Boolean>`, `deleteAllPast(): PastDeletion` and `allPastDeletion: Flow<AllPastDeletion>`; `@Composable internal fun ConversationsPage(data, onBack)`; `internal object ConversationsTags` (`QUIET`, `FOLLOW_UP`, `RETENTION`, `REPLIES`, `MINUTES`, `CREDITS`); `internal val REPLY_CHOICES`, `MINUTE_CHOICES`, `CREDIT_CHOICES`; the strings `settings_conversations*` and `conversations_*`; `Profile.countsCredits` (`:core:providers`), the one rule that only Copilot counts AI Credits, which `AppModelPort` and the profile page's cost read too; `OptionSwitch` becomes internal.

Counts: `:app` device tests AD₁₁ + 5 (`SettingsAppTest` + 5), where AD₁₁ is the count after Task 11 (499 from `1b1b8f81`, so 504 after this task). JVM counts unchanged (the engine's test of the deletion's state is in Task 3): `:app` 817, `:core:conversation` 308.

- [ ] **Step 1: Write the failing tests**

In `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, replace:

```kotlin
import androidx.compose.ui.test.assertIsDisplayed
```

with:

```kotlin
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
```

In `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, replace:

```kotlin
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
```

with:

```kotlin
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
```

In `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, replace:

```kotlin
import dev.kestrel.conversation.engine.HistoryDeletion
```

with:

```kotlin
import dev.kestrel.agent.goal.GoalLimits
import dev.kestrel.conversation.engine.AllPastDeletion
import dev.kestrel.conversation.engine.HistoryDeletion
import dev.kestrel.conversation.engine.PastDeletion
import dev.kestrel.conversation.model.ConversationSettings
```

In `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, replace:

```kotlin
    @Test
    fun theListeningPageChangesTheOptions() {
```

with:

```kotlin
    // Design 13 of sub-project 7: each choice counts at once.
    @Test
    fun theConversationsPageChangesTheTimesTheSpokenRepliesAndTheRetention() {
        show(NOTHING_SET_UP)
        openConversations()

        choice(ConversationsTags.QUIET, R.plurals.conversations_minutes, 1).assertIsSelected()
        choice(ConversationsTags.QUIET, R.plurals.conversations_minutes, 2).performScrollTo().performClick()
        choice(ConversationsTags.FOLLOW_UP, R.plurals.conversations_seconds, 20).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.conversations_speak_typed)).performScrollTo().assertIsOff().performClick()
        choice(ConversationsTags.RETENTION, R.plurals.conversations_days, 90).performScrollTo().performClick()

        compose.onNodeWithText(text(R.string.conversations_speak_typed)).assertIsOn()
        choice(ConversationsTags.QUIET, R.plurals.conversations_minutes, 2).assertIsSelected()
        assertEquals(
            ConversationSettings(quietSeconds = 120, followUpSeconds = 20, speakTypedReplies = true, retentionDays = 90),
            data.conversations.value,
        )
    }

    @Test
    fun theConversationsPageSetsARoundsLimitsAndItsCreditsOnlyForAProfileThatCountsThem() {
        show(NOTHING_SET_UP)
        openConversations()
        compose.onAllNodesWithText(text(R.string.conversations_credits_title)).assertCountEquals(0)

        compose.onNode(hasText(text(R.string.conversations_replies, "1,000")) and hasAnyAncestor(hasTestTag(ConversationsTags.REPLIES)))
            .performScrollTo().performClick()
        choice(ConversationsTags.MINUTES, R.plurals.conversations_hours, 2).performScrollTo().performClick()
        data.credits.value = true
        compose.onNode(hasText(text(R.string.conversations_credits, "2,500")) and hasAnyAncestor(hasTestTag(ConversationsTags.CREDITS)))
            .performScrollTo().performClick()

        compose.waitForIdle()
        assertEquals(GoalLimits(replies = 1_000, workMinutes = 120, credits = 2_500), data.conversations.value.limits)
    }

    @Test
    fun deleteAllPastConversationsAsksFirst() {
        show(NOTHING_SET_UP)
        openConversations()

        compose.onNodeWithText(text(R.string.conversations_delete_all)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.conversations_delete_all_question)).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.conversations_cancel)) and hasAnyAncestor(isDialog())).performClick()
        assertEquals(0, data.pastDeletions)

        compose.onNodeWithText(text(R.string.conversations_delete_all)).performScrollTo().performClick()
        compose.onNode(hasText(text(R.string.conversations_delete_all_confirm)) and hasAnyAncestor(isDialog())).performClick()

        compose.onNodeWithText(text(R.string.conversations_deleted)).performScrollTo().assertIsDisplayed()
        assertEquals(1, data.pastDeletions)
    }

    // Review B-M4 of plan 7F: a deletion that runs shows when the owner opens the page again, and the page offers no second.
    @Test
    fun aDeletionThatRunsShowsWhenThePageOpensAgainAndOffersNoSecond() {
        data.allPastDeletion.value = AllPastDeletion.Running
        show(NOTHING_SET_UP)
        openConversations()

        compose.onNodeWithText(text(R.string.conversations_deleting)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.conversations_delete_all)).performScrollTo().assertIsNotEnabled()

        data.allPastDeletion.value = AllPastDeletion.Ended(PastDeletion.DELETED)
        compose.onNodeWithText(text(R.string.conversations_deleted)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.conversations_delete_all)).assertIsEnabled()
    }

    // Review B-N1 of plan 7F: the outcome of a deletion that the page did not see running is not shown or read out again.
    @Test
    fun anEarlierDeletionsOutcomeIsNotShownAgain() {
        data.allPastDeletion.value = AllPastDeletion.Ended(PastDeletion.DELETED)
        show(NOTHING_SET_UP)
        openConversations()

        compose.onNodeWithText(text(R.string.conversations_delete_all)).performScrollTo().assertIsEnabled()
        compose.onAllNodesWithText(text(R.string.conversations_deleted)).assertCountEquals(0)
    }

    private fun openConversations() {
        compose.onNodeWithText(text(R.string.settings_conversations)).performScrollTo().performClick()
    }

    // The choice labelled with plural [id] of [count] in the group with [tag]: the groups share labels such as "30 seconds".
    private fun choice(tag: String, id: Int, count: Int) =
        compose.onNode(hasText(compose.activity.resources.getQuantityString(id, count, count)) and hasAnyAncestor(hasTestTag(tag)))

    @Test
    fun theListeningPageChangesTheOptions() {
```

In `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, replace:

```kotlin
        override suspend fun revokeScreenshots(row: dev.kestrel.app.agent.eyes.ScreenshotApprovalRow) {
            approvals.value = approvals.value - row
        }
```

with:

```kotlin
        override suspend fun revokeScreenshots(row: dev.kestrel.app.agent.eyes.ScreenshotApprovalRow) {
            approvals.value = approvals.value - row
        }

        // Sub-project 7: the fake Conversations page
        override val conversations = MutableStateFlow(ConversationSettings())
        val credits = MutableStateFlow(false)
        var pastDeletions = 0

        override val countsCredits: kotlinx.coroutines.flow.Flow<Boolean> get() = credits

        override suspend fun setConversations(settings: ConversationSettings) {
            conversations.value = settings
        }

        override val allPastDeletion = MutableStateFlow<AllPastDeletion>(AllPastDeletion.Idle)

        override suspend fun deleteAllPast(): PastDeletion {
            pastDeletions++
            allPastDeletion.value = AllPastDeletion.Ended(PastDeletion.DELETED)
            return PastDeletion.DELETED
        }
```

This step shows Task 12 as it was carried out. The "Conversations" entry that Task 12 adds to the settings' home page pushes the "Network log" entry below the home page's scroll area on kestrel-api37 (1080 x 2400, 420 dpi), so `theLogPagesShowTheirEntries`, which clicked the log entries with `performClick()` without scrolling, tapped outside the window, and the page stayed Home (found in Task 14's first run). Since `f6866aa4`, `theLogPagesShowTheirEntries`, `deleteHistoryAsksFirst` and `aDeletionThatKestrelLeftAloneSaysWhyAndKeepsTheEntries` open the "Activity log" and "Network log" entries with `openFromHome(…)`, which scrolls to an entry before it clicks it, as the test's other page openings already did; their checks are unchanged.

- [ ] **Step 2: Compile the tests to see them fail**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin`
Expected: FAIL: the compiler does not know `ConversationsTags`, `conversations`, `setConversations`, `countsCredits`, `deleteAllPast`, `allPastDeletion` and the strings `conversations_*`.

- [ ] **Step 3: The page's data**

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt`, replace:

```kotlin
    /** Takes back one approval: the screenshot question comes again for that app and provider. */
    suspend fun revokeScreenshots(row: dev.kestrel.app.agent.eyes.ScreenshotApprovalRow) {}
}
```

with:

```kotlin
    /** Takes back one approval: the screenshot question comes again for that app and provider. */
    suspend fun revokeScreenshots(row: dev.kestrel.app.agent.eyes.ScreenshotApprovalRow) {}

    // Sub-project 7: the Conversations page

    /** The Conversations page's settings (design 13 of sub-project 7). */
    val conversations: Flow<ConversationSettings> get() = flowOf(ConversationSettings())

    suspend fun setConversations(settings: ConversationSettings) {}

    /** Whether the default profile counts AI Credits, so that the page offers a round's credits (design 13). */
    val countsCredits: Flow<Boolean> get() = flowOf(false)

    /**
     * "Delete all past conversations" (design 9.5 of sub-project 7): every closed conversation goes; the open one stays. A
     * call while one runs deletes nothing again and gets the running one's outcome.
     */
    suspend fun deleteAllPast(): PastDeletion = PastDeletion.DELETED

    /** "Delete all past conversations" as it stands, which the page shows also after the owner left it (review B-M4). */
    val allPastDeletion: Flow<AllPastDeletion> get() = flowOf(AllPastDeletion.Idle)
}
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt`, replace:

```kotlin
    override suspend fun deleteHistory(): HistoryDeletion = graph.conversations.deleteHistory()
```

with:

```kotlin
    override suspend fun deleteHistory(): HistoryDeletion = graph.conversations.deleteHistory()

    override val conversations: Flow<ConversationSettings> = graph.settings.observe(SettingKeys.CONVERSATIONS)

    override suspend fun setConversations(settings: ConversationSettings) = write {
        graph.settings.set(SettingKeys.CONVERSATIONS, settings)
    }

    // Design 13 of sub-project 7: the round's credits only for a default profile that counts them, by the agent's own rule.
    override val countsCredits: Flow<Boolean> =
        graph.settings.observe(SettingKeys.PROVIDERS).map { it.defaultProfile?.countsCredits == true }

    override suspend fun deleteAllPast(): PastDeletion = graph.conversations.deleteAllPast()

    override val allPastDeletion: Flow<AllPastDeletion> = graph.conversations.allPastDeletion
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt`, replace:

```kotlin
import dev.kestrel.conversation.engine.HistoryDeletion
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.logs.LogEntry
import dev.kestrel.privacy.logs.NetworkEvent
```

with:

```kotlin
import dev.kestrel.conversation.engine.AllPastDeletion
import dev.kestrel.conversation.engine.HistoryDeletion
import dev.kestrel.conversation.engine.PastDeletion
import dev.kestrel.conversation.model.ConversationSettings
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.logs.LogEntry
import dev.kestrel.privacy.logs.NetworkEvent
```

The rule that only Copilot counts AI Credits gets one home, which the agent's model information and the page both read (review B-M6). In `core/providers/src/main/kotlin/dev/kestrel/providers/profiles/Profile.kt`, replace:

```kotlin
    fun model(role: ModelRole): ProfileModel = if (role == ModelRole.LIGHTWEIGHT) lightweight else versatile
```

with:

```kotlin
    fun model(role: ModelRole): ProfileModel = if (role == ModelRole.LIGHTWEIGHT) lightweight else versatile

    /** Whether the profile's use counts AI Credits, so that a round's credits limit it: only Copilot's does (plan 5A). */
    val countsCredits: Boolean get() = providerId == CatalogLoader.COPILOT
```

In `core/providers/src/main/kotlin/dev/kestrel/providers/profiles/Profile.kt`, replace:

```kotlin
import dev.kestrel.providers.catalog.AuthMethod
```

with:

```kotlin
import dev.kestrel.providers.catalog.AuthMethod
import dev.kestrel.providers.catalog.CatalogLoader
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AppModelPort.kt`, replace:

```kotlin
            countsCredits = copilot,
```

with:

```kotlin
            countsCredits = profile.countsCredits,
```

The profile page shows a month's cost in AI Credits by the same rule. In `app/src/main/kotlin/dev/kestrel/app/settings/ProvidersPage.kt`, replace:

```kotlin
        profile.providerId == CatalogLoader.COPILOT -> stringResource(R.string.providers_cost_credits, decimal(use.credits))
```

with:

```kotlin
        profile.countsCredits -> stringResource(R.string.providers_cost_credits, decimal(use.credits))
```

- [ ] **Step 4: The strings and the page**

Create `app/src/main/res/values/strings_conversations_page.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- The settings page "Conversations" (design 13 of sub-project 7; plan 7F). -->
<resources>
    <string name="settings_conversations">Conversations</string>
    <string name="settings_conversations_summary">How long a conversation lasts, spoken replies, past conversations</string>
    <string name="conversations_quiet">Quiet time between commands</string>
    <string name="conversations_quiet_detail">A conversation ends when this time passes after your command and Kestrel is not busy.</string>
    <string name="conversations_follow_up">Follow-up window</string>
    <string name="conversations_follow_up_detail">After Kestrel\'s spoken reply, you can answer without saying \"Kestrel\" for this long.</string>
    <string name="conversations_off">Off</string>
    <string name="conversations_speak_typed">Speak replies to typed commands</string>
    <string name="conversations_speak_typed_detail">Kestrel also speaks its replies to what you type.</string>
    <string name="conversations_retention">Keep past conversations for</string>
    <string name="conversations_round">Before Kestrel asks \"Continue?\"</string>
    <string name="conversations_replies_title">Model replies per round</string>
    <string name="conversations_minutes_title">Working time per round</string>
    <string name="conversations_credits_title">AI Credits per round</string>
    <string name="conversations_replies">%1$s replies</string>
    <string name="conversations_credits">%1$s AI Credits</string>
    <plurals name="conversations_seconds">
        <item quantity="one">%d second</item>
        <item quantity="other">%d seconds</item>
    </plurals>
    <plurals name="conversations_minutes">
        <item quantity="one">%d minute</item>
        <item quantity="other">%d minutes</item>
    </plurals>
    <plurals name="conversations_hours">
        <item quantity="one">%d hour</item>
        <item quantity="other">%d hours</item>
    </plurals>
    <plurals name="conversations_days">
        <item quantity="one">%d day</item>
        <item quantity="other">%d days</item>
    </plurals>
    <string name="conversations_delete_all">Delete all past conversations</string>
    <string name="conversations_delete_all_question">Delete all past conversations? The open conversation stays.</string>
    <string name="conversations_delete_all_confirm">Delete all</string>
    <string name="conversations_cancel">Cancel</string>
    <string name="conversations_deleted">Past conversations deleted.</string>
    <string name="conversations_deleting">Deleting past conversations…</string>
    <string name="conversations_delete_failed">Kestrel could not delete the past conversations.</string>
    <string name="conversations_delete_unavailable">Kestrel can\'t read past conversations right now, so it deleted none. Try again later.</string>
</resources>
```

In `app/src/main/kotlin/dev/kestrel/app/settings/ListeningPage.kt`, replace:

```kotlin
@Composable
private fun OptionSwitch(@StringRes title: Int, @StringRes detail: Int, on: Boolean?, onChange: (Boolean) -> Unit) {
```

with:

```kotlin
@Composable
internal fun OptionSwitch(@StringRes title: Int, @StringRes detail: Int, on: Boolean?, onChange: (Boolean) -> Unit) {
```

Create `app/src/main/kotlin/dev/kestrel/app/settings/ConversationsPage.kt`:

```kotlin
package dev.kestrel.app.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.kestrel.app.R
import dev.kestrel.app.conversation.screen.GuardWindow
import dev.kestrel.conversation.engine.AllPastDeletion
import dev.kestrel.conversation.engine.PastDeletion
import dev.kestrel.conversation.model.ConversationSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/** The test tags of the page's groups of choices, whose labels repeat across groups, such as "30 seconds". */
internal object ConversationsTags {
    const val QUIET = "conversations-quiet"
    const val FOLLOW_UP = "conversations-follow-up"
    const val RETENTION = "conversations-retention"
    const val REPLIES = "conversations-replies"
    const val MINUTES = "conversations-minutes"
    const val CREDITS = "conversations-credits"
}

/** A round's model replies that the page offers, within `ConversationSettings.REPLIES` (design 13 of sub-project 7). */
internal val REPLY_CHOICES = listOf(50, 100, 200, 500, 1_000, 2_000)

/** A round's working minutes that the page offers, within `ConversationSettings.WORK_MINUTES`. */
internal val MINUTE_CHOICES = listOf(5, 15, 30, 60, 120, 240)

/** A round's AI Credits that the page offers, within `ConversationSettings.CREDITS`. */
internal val CREDIT_CHOICES = listOf(10, 100, 250, 500, 1_000, 2_500, 5_000)

/**
 * The page "Conversations" (design 13 of sub-project 7): the quiet time between commands, the follow-up window, whether
 * typed commands get spoken replies, how long past conversations are kept, one round's limits (its AI Credits only while
 * the default profile counts them), and "Delete all past conversations" with a question. Each change counts at once, also
 * when the owner leaves the page right after it, as on the Listening page; a choice shows as chosen by the value that
 * Kestrel reads: a stored value outside the design's range shows as the nearest bound, which is a choice, and one within
 * the range that is not a choice shows no choice selected.
 */
@Composable
internal fun ConversationsPage(data: SettingsData, onBack: () -> Unit) {
    val stored by data.conversations.collectAsState(initial = null)
    val countsCredits by data.countsCredits.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    // The settings as this page wrote them last, so that a second change builds on the first before the store reports it.
    var written by remember { mutableStateOf<ConversationSettings?>(null) }
    val settings = written ?: stored
    var asking by remember { mutableStateOf(false) }
    // The engine holds the deletion's state, so a deletion that runs shows also after the owner left the page (review B-M4).
    val deletion by data.allPastDeletion.collectAsState(initial = AllPastDeletion.Idle)
    val deleting = deletion == AllPastDeletion.Running
    // An outcome shows only for a deletion that this page started or saw running, so that an earlier one, which the engine
    // keeps for the process's life, is not shown and read out again on a later visit (review B-N1).
    var watched by remember { mutableStateOf(false) }
    LaunchedEffect(deleting) { if (deleting) watched = true }
    val change: ((ConversationSettings) -> ConversationSettings) -> Unit = { edit ->
        (written ?: stored)?.let { current ->
            val next = edit(current)
            written = next
            scope.launch(start = CoroutineStart.UNDISPATCHED) { data.setConversations(next) }
        }
    }
    PageHeader(stringResource(R.string.settings_conversations), onBack)
    Choices(
        R.string.conversations_quiet,
        R.string.conversations_quiet_detail,
        ConversationsTags.QUIET,
        ConversationSettings.QUIET_CHOICES,
        settings?.let { (it.quietMillis / 1_000).toInt() },
        { duration(it) },
    ) { seconds -> change { it.copy(quietSeconds = seconds) } }
    Choices(
        R.string.conversations_follow_up,
        R.string.conversations_follow_up_detail,
        ConversationsTags.FOLLOW_UP,
        ConversationSettings.FOLLOW_UP_CHOICES,
        settings?.let { (it.followUpMillis / 1_000).toInt() },
        { duration(it) },
    ) { seconds -> change { it.copy(followUpSeconds = seconds) } }
    OptionSwitch(R.string.conversations_speak_typed, R.string.conversations_speak_typed_detail, settings?.speakTypedReplies) { on ->
        change { it.copy(speakTypedReplies = on) }
    }
    Choices(
        R.string.conversations_retention,
        null,
        ConversationsTags.RETENTION,
        ConversationSettings.RETENTION_CHOICES,
        settings?.keptDays,
        { pluralStringResource(R.plurals.conversations_days, it, it) },
    ) { days -> change { it.copy(retentionDays = days) } }
    Text(stringResource(R.string.conversations_round), Modifier.padding(top = 24.dp), style = MaterialTheme.typography.titleLarge)
    val limits = settings?.roundLimits
    Choices(
        R.string.conversations_replies_title,
        null,
        ConversationsTags.REPLIES,
        REPLY_CHOICES,
        limits?.replies,
        { stringResource(R.string.conversations_replies, grouped(it)) },
    ) { replies -> change { it.copy(limits = it.limits.copy(replies = replies)) } }
    Choices(
        R.string.conversations_minutes_title,
        null,
        ConversationsTags.MINUTES,
        MINUTE_CHOICES,
        limits?.workMinutes,
        { duration(it * 60) },
    ) { minutes -> change { it.copy(limits = it.limits.copy(workMinutes = minutes)) } }
    if (countsCredits) {
        Choices(
            R.string.conversations_credits_title,
            null,
            ConversationsTags.CREDITS,
            CREDIT_CHOICES,
            limits?.credits,
            { stringResource(R.string.conversations_credits, grouped(it)) },
        ) { credits -> change { it.copy(limits = it.limits.copy(credits = credits)) } }
    }
    Button(onClick = { asking = true }, Modifier.padding(top = 24.dp), enabled = !deleting) { Text(stringResource(R.string.conversations_delete_all)) }
    // The deletion may wait for the index of past conversations (review N2); its outcome is read out when it comes.
    val result = (deletion as? AllPastDeletion.Ended)?.outcome?.takeIf { watched }
    if (deleting) {
        Text(stringResource(R.string.conversations_deleting), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
    } else if (result != null) {
        val said = when (result) {
            PastDeletion.DELETED, PastDeletion.OPEN -> R.string.conversations_deleted
            // Design 9.2: while the index cannot be read, nothing is deleted, and the store tries again by itself later.
            PastDeletion.UNAVAILABLE -> R.string.conversations_delete_unavailable
            PastDeletion.FAILED -> R.string.conversations_delete_failed
        }
        Text(
            stringResource(said),
            Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            // The question is a window of its own, which drops a touch that passed through another app's window (design 12.2).
            text = {
                GuardWindow()
                Text(stringResource(R.string.conversations_delete_all_question))
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    watched = true
                    // The engine runs the deletion in its own scope, so leaving the page stops nothing; the page shows its state.
                    scope.launch { data.deleteAllPast() }
                }) { Text(stringResource(R.string.conversations_delete_all_confirm)) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text(stringResource(R.string.conversations_cancel)) } },
        )
    }
}

/**
 * One setting's choices, with its [title], an optional [detail] and the group's [tag]: each row is one choice, so that its
 * label names it for Voice Access and TalkBack. Nothing can be chosen until the stored settings have been read ([chosen]
 * null).
 */
@Composable
private fun Choices(
    @StringRes title: Int,
    @StringRes detail: Int?,
    tag: String,
    choices: List<Int>,
    chosen: Int?,
    label: @Composable (Int) -> String,
    onChoose: (Int) -> Unit,
) {
    Text(stringResource(title), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
    detail?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
    Column(Modifier.selectableGroup().testTag(tag)) {
        for (choice in choices) {
            val selected = chosen == choice
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected, enabled = chosen != null, role = Role.RadioButton) { onChoose(choice) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null, enabled = chosen != null)
                Text(label(choice), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

// "Off", "30 seconds", "2 minutes" or "4 hours".
@Composable
private fun duration(seconds: Int): String = when {
    seconds == 0 -> stringResource(R.string.conversations_off)
    seconds < 60 -> pluralStringResource(R.plurals.conversations_seconds, seconds, seconds)
    seconds % 3_600 == 0 -> pluralStringResource(R.plurals.conversations_hours, seconds / 3_600, seconds / 3_600)
    else -> pluralStringResource(R.plurals.conversations_minutes, seconds / 60, seconds / 60)
}

// "1,000": Kestrel's texts are English.
private fun grouped(number: Int): String = NumberFormat.getIntegerInstance(Locale.ENGLISH).format(number)
```

- [ ] **Step 5: The page in the settings app**

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt`, replace:

```kotlin
enum class SettingsPage { HOME, CHECKLIST, LISTENING, VOICE, COMMANDS, SAFETY, ACTIVITY_LOG, NETWORK_LOG, ABOUT, PROVIDERS, MEMORY }
```

with:

```kotlin
enum class SettingsPage { HOME, CHECKLIST, LISTENING, VOICE, COMMANDS, SAFETY, ACTIVITY_LOG, NETWORK_LOG, ABOUT, PROVIDERS, MEMORY, CONVERSATIONS }
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt`, replace:

```kotlin
            SettingsPage.MEMORY -> ScrollingPage { MemoryPage(data, home) }
```

with:

```kotlin
            SettingsPage.MEMORY -> ScrollingPage { MemoryPage(data, home) }
            SettingsPage.CONVERSATIONS -> ScrollingPage { ConversationsPage(data, home) }
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt`, replace:

```kotlin
    PageEntry(stringResource(R.string.settings_memory), stringResource(R.string.settings_memory_summary)) { onOpen(SettingsPage.MEMORY) }
```

with:

```kotlin
    PageEntry(stringResource(R.string.settings_conversations), stringResource(R.string.settings_conversations_summary)) {
        onOpen(SettingsPage.CONVERSATIONS)
    }
    PageEntry(stringResource(R.string.settings_memory), stringResource(R.string.settings_memory_summary)) { onOpen(SettingsPage.MEMORY) }
```

- [ ] **Step 6: Compile the tests and the app**

Run: `scripts\gradle.ps1 :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL; `:app` JVM unchanged. The 5 device tests run in Task 14.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/kestrel/app/settings/ConversationsPage.kt app/src/main/res/values/strings_conversations_page.xml app/src/main/kotlin/dev/kestrel/app/settings/SettingsApp.kt app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt app/src/main/kotlin/dev/kestrel/app/settings/ListeningPage.kt app/src/main/kotlin/dev/kestrel/app/agent/AppModelPort.kt app/src/main/kotlin/dev/kestrel/app/settings/ProvidersPage.kt core/providers/src/main/kotlin/dev/kestrel/providers/profiles/Profile.kt app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt
git commit -m "feat(app): the Conversations settings page: quiet time, follow-up window, spoken typed replies, retention, round limits and Delete all past conversations" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 13: The hook `conversation-type` and typed answers

Design 14.4 of sub-project 7: the new hook `conversation-type --es text … --es tag …` types a command or an answer as if on the conversation screen: the words go through the text channel, read as the screen reads them, and a tag names the conversation's command that they start, as `voice heard` does, so that `voice-state -Tag` and `agent-task -Tag` read it. `agent-answer -Tag` with `--es channel text` answers through the text channel, as the owner does on the screen's copy of the card: the open card's button that its words name (yes, no, continue, stop or cancel), which reaches the conversation as an input (Task 4's `choose`), and otherwise typed words. It keeps no tap for later. A confirmation, a hand-over and a pick are answered only on the card over the app (design 12.2 and 12.3), so for them the hook refuses and says to tap the overlay's button with `input tap`, as the device tests do. A conversation that the work of a hook's input opened is the hooks' own; one that it joined, which may be the owner's, is not, so the checks of Tasks 14 and 15 send `conversation-control --es do new` first (review N4). One rule marks them, Task 13a's rule of the conversation's steps (`CommandTags.onConversationStep`), for a typed input with or without a tag and for a tagged transcript; this task adds no rule of its own (review B-I2). `HookConversations` keeps these marks by id in a file of Kestrel's debug-only storage, so that they survive a restart, and `conversation-control --es do delete-typed` deletes the closed ones, clears their marks, and never deletes another conversation, so that the checks leave no test conversation in the owner's list. All of them exist only in debug builds and answer for callers with `android.permission.DUMP` only, as every hook (design 12.2). `scripts\hook.ps1` learns them in plan 7G.

**Files:**
- Create: `app/src/debug/kotlin/dev/kestrel/app/debug/HookConversations.kt`
- Modify: `app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/ConversationHooks.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/CommandTags.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHookAnswers.kt`
- Test: create `app/src/testDebug/kotlin/dev/kestrel/app/debug/HookConversationsTest.kt`; modify `app/src/testDebug/kotlin/dev/kestrel/app/debug/CommandTagsTest.kt`, `app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/debug/ConversationHooksTest.kt`

**Interfaces:**
- Consumes: Task 4's `TextChannel` (`read`, `submit`, `choose`); Task 1's `CardChoice`, `OwnerCard.answersOnScreen` and `AppGraph.cardDesk` (`open`); Task 8's `AppGraph.textChannel` and `TypedTests`; plan 7D's `CommandTags` (`onConversationStep`), `VoiceHooks` (`tags`, `install`, `taskOptions`, `refused`, `YES_CALL_REFUSED`), `ConversationHooks` and `AgentTaskHooks.answer`.
- Produces: the hook command `CONVERSATION_TYPE` (`--es text`, optional `--es tag`, `--es model`, `--ez live`, `--es marker`), whose answer is `typed read=<kind>` with ` tag=<tag>` when tagged, where `<kind>` is the input's kind in lower case (`command`, `answer`, `stop`, `continue`, `newconversation`, …); `conversation-control --es do delete-typed`, whose answer is `deleted=<n> open=<n> failed=<n>`; `internal class HookConversations(file)` with `own`, `all`, `forget`, `FILE` and the process's `attach(dir)`, `own(id)`, `all()` and `forget(done)`; `agent-answer --es channel text`, whose answer ends with ` channel=text`; `CommandTags.typed(tag, input, read, options): OwnerInput?`; `VoiceHooks.typed(graph, tag, input, options): OwnerInput`; `AgentHookAnswers.cardChoice(words, choices): CardChoice?`.

Counts: `:app` JVM tests (debug) A₁₂ + 3 (`CommandTagsTest` + 1, `AgentHookAnswersTest` + 1, `HookConversationsTest` 1), where A₁₂ is the count after Task 12; `:app` device tests AD₁₂ + 1 (`ConversationHooksTest` + 1). From `1b1b8f81`: A₁₂ = 817 and AD₁₂ = 504, so this task ends at 820 and 505.

- [ ] **Step 1: Write the failing tests**

Create `app/src/testDebug/kotlin/dev/kestrel/app/debug/HookConversationsTest.kt`:

```kotlin
package dev.kestrel.app.debug

import dev.kestrel.conversation.model.ConversationId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The hooks' own conversations (plan 7F): their marks survive a restart of Kestrel, and a deletion clears its mark. */
class HookConversationsTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun theMarksSurviveARestartAndADeletionClearsItsMark() {
        val file = folder.root.resolve(HookConversations.FILE)
        val one = ConversationId("1".repeat(32))
        val two = ConversationId("2".repeat(32))
        HookConversations(file).apply {
            own(one)
            own(two)
            own(one)
        }

        val restarted = HookConversations(file)
        assertEquals(listOf(one, two), restarted.all())
        restarted.forget(listOf(one))
        assertEquals(listOf(two), HookConversations(file).all())
    }
}
```
In `app/src/testDebug/kotlin/dev/kestrel/app/debug/CommandTagsTest.kt`, add before its last closing brace:

```kotlin

    // Design 14.4 of sub-project 7: conversation-type tags a typed input, which carries the tag's options.
    @Test
    fun aTypedInputsTagGetsTheCommandThatItStartedAndATagIsUsedOnce() {
        val typed = OwnerInput(11, InputChannel.TEXT, InputIntent.Command("open the maps"), 0)
        val input = checkNotNull(tags.typed("typed", typed, "command", mapOf("model" to "test-model")))
        assertEquals(mapOf("model" to "test-model"), input.options)
        assertEquals(CommandTags.Tagged(CommandTags.Role.CONVERSE, 0, "command"), tags.tagged("typed"))

        val started = ConversationEffect.StartGoal(4, 1, input, "open the maps")
        tags.onConversationStep(ConversationEvent.Input(input), ConversationState(), MachineStep(ConversationState(), listOf(started)))

        assertEquals(CommandTags.Tagged(CommandTags.Role.TASK, 0, "command", task = 4), tags.tagged("typed"))
        assertNull(tags.typed("typed", typed.copy(id = 12), "command"))
    }
```

In `app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt`, replace:

```kotlin
import dev.kestrel.app.agent.TaskEvidence
```

with:

```kotlin
import dev.kestrel.app.agent.TaskEvidence
import dev.kestrel.app.conversation.CardChoice
```

In `app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt`, add before its last closing brace:

```kotlin

    // Design 10.3 of sub-project 7: agent-answer --es channel text presses the card's button that its words name.
    @Test
    fun anAnswerOnTheTextChannelPressesTheButtonThatItsWordsName() {
        val confirmation = listOf(CardChoice.Confirm, CardChoice.Cancel)
        val pick = listOf(CardChoice.Pick(1), CardChoice.Pick(2), CardChoice.Reply)

        assertEquals(CardChoice.Confirm, AgentHookAnswers.cardChoice("confirm", confirmation))
        assertEquals(CardChoice.Cancel, AgentHookAnswers.cardChoice("cancel", confirmation))
        assertNull(AgentHookAnswers.cardChoice("yes", confirmation))
        assertEquals(CardChoice.Pick(2), AgentHookAnswers.cardChoice("2", pick))
        assertNull(AgentHookAnswers.cardChoice("3", pick))
        assertNull(AgentHookAnswers.cardChoice("Half past seven", listOf(CardChoice.Reply, CardChoice.Cancel)))
    }
```

In `app/src/androidTest/kotlin/dev/kestrel/app/debug/ConversationHooksTest.kt`, replace:

```kotlin
import dev.kestrel.agent.goal.GoalLimits
```

with:

```kotlin
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.goal.GoalLimits
```

In `app/src/androidTest/kotlin/dev/kestrel/app/debug/ConversationHooksTest.kt`, replace:

```kotlin
import dev.kestrel.app.agent.AgentTests
```

with:

```kotlin
import dev.kestrel.app.agent.AgentTests
import dev.kestrel.app.conversation.TypedTests
```

In `app/src/androidTest/kotlin/dev/kestrel/app/debug/ConversationHooksTest.kt`, replace:

```kotlin
import org.junit.Assert.assertEquals
```

with:

```kotlin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
```

In `app/src/androidTest/kotlin/dev/kestrel/app/debug/ConversationHooksTest.kt`, replace:

```kotlin
import dev.kestrel.conversation.machine.QuietClock
```

with:

```kotlin
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.model.InputChannel
```

In `app/src/androidTest/kotlin/dev/kestrel/app/debug/ConversationHooksTest.kt`, replace:

```kotlin
    private fun hook(command: String, vararg extras: Pair<String, String>): String {
```

with:

```kotlin
    // Design 14.4 of sub-project 7: the hook types as the owner does on the conversation screen, and its tag names the command.
    @Test
    fun conversationTypeStartsATypedCommandThatItsTagNames() {
        TypedTests.prepare(ScriptedModel(listOf(call("fail", null, null, null, "reason" to "I stop here."))))
        try {
            val tag = "typed-${System.currentTimeMillis()}"
            assertEquals("typed read=command tag=$tag", hook("CONVERSATION_TYPE", "text" to TypedTests.marked("open the settings"), "tag" to tag))

            check(VoiceTests.waitUntil(10_000) { (VoiceHooks.tags.tagged(tag)?.task ?: 0) > 0 }) { "The tag got no command" }
            conversations.view.value.id?.let(TypedTests::own)
            val command = checkNotNull(VoiceHooks.tags.tagged(tag)).task
            assertEquals(EndReason.FAILED_BY_MODEL, AgentTests.ended(command).report.end)
            assertEquals(InputChannel.TEXT, conversations.view.value.records.single().channel)
            assertEquals("error: the tag $tag is in use; give each transcript a new tag", hook("CONVERSATION_TYPE", "text" to "open the settings", "tag" to tag))
            assertEquals("error: add --es text with the words to type", hook("CONVERSATION_TYPE"))

            // The hooks' own conversation, once closed, is deleted; the owner's are not (plan 7F).
            val id = checkNotNull(conversations.view.value.id)
            hook("CONVERSATION_CONTROL", "do" to "new")
            check(VoiceTests.waitUntil(10_000) { conversations.view.value.id == null }) { "The conversation did not close" }
            assertTrue(hook("CONVERSATION_CONTROL", "do" to "delete-typed").startsWith("deleted="))
            assertTrue(runBlocking { TypedTests.graph.conversationStore.list() }.none { it.id == id })
        } finally {
            TypedTests.finish()
        }
    }

    private fun hook(command: String, vararg extras: Pair<String, String>): String {
```

- [ ] **Step 2: Run the JVM tests to see them fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.debug.CommandTagsTest --tests=dev.kestrel.app.debug.AgentHookAnswersTest --tests=dev.kestrel.app.debug.HookConversationsTest`
Expected: FAIL: the compiler does not know `HookConversations`, `CommandTags.typed` and `AgentHookAnswers.cardChoice`.

- [ ] **Step 3: The hooks' own conversations, the tags of typed inputs and the card's buttons**

Create `app/src/debug/kotlin/dev/kestrel/app/debug/HookConversations.kt`:

```kotlin
package dev.kestrel.app.debug

import dev.kestrel.conversation.model.ConversationId
import java.io.File

/**
 * The conversations that the debug hooks opened (plan 7F's Tasks 13 and 13a): those that `conversation-type` or a tagged
 * input's work opened, never one that a switch moved into or that was open before, which may be the owner's (review N4 of
 * plan 7F, N1 of plan 7G). `conversation-control --es do delete-typed` deletes them and clears their marks. The marks are
 * kept by id only, in [file], a file of Kestrel's own storage in debug builds, so that they survive a restart of Kestrel.
 */
internal class HookConversations(private val file: File) {
    private val ids = LinkedHashSet<ConversationId>()

    init {
        // A line that is no conversation's id is no mark.
        if (file.isFile) file.readLines().mapNotNullTo(ids) { line -> runCatching { ConversationId(line.trim()) }.getOrNull() }
    }

    @Synchronized
    fun own(id: ConversationId) {
        if (ids.add(id)) save()
    }

    @Synchronized
    fun all(): List<ConversationId> = ids.toList()

    @Synchronized
    fun forget(done: Collection<ConversationId>) {
        if (ids.removeAll(done.toSet())) save()
    }

    private fun save() = file.writeText(ids.joinToString("\n") { it.value })

    override fun toString(): String = "HookConversations(${ids.size})"

    companion object {
        /** The marks' file in Kestrel's `noBackupFilesDir`. */
        const val FILE = "hook-conversations.txt"

        @Volatile
        private var current: HookConversations? = null

        /** Reads the marks of [dir] once in this process; every hook call attaches them (TestHooks). */
        @Synchronized
        fun attach(dir: File) {
            if (current == null) current = HookConversations(File(dir, FILE))
        }

        fun own(id: ConversationId) {
            current?.own(id)
        }

        fun all(): List<ConversationId> = current?.all().orEmpty()

        fun forget(done: Collection<ConversationId>) {
            current?.forget(done)
        }
    }
}
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt`, replace:

```kotlin
internal class TestHooks(private val graph: AppGraph, private val context: Context) {
    private val cacheDir: File get() = context.cacheDir
```

with:

```kotlin
internal class TestHooks(private val graph: AppGraph, private val context: Context) {
    private val cacheDir: File get() = context.cacheDir

    init {
        // The hooks' own conversations, marked before a restart of Kestrel too (plan 7F).
        HookConversations.attach(context.noBackupFilesDir)
    }
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/CommandTags.kt`, replace:

```kotlin
        while (tags.size > MAX_TAGS) {
            val oldest = tags.keys.first()
            tags.remove(oldest)
            waiting.values.remove(oldest)
            conversing.values.remove(oldest)
            inputs.values.remove(oldest)
            this.options.remove(oldest)
        }
        return true
    }
```

with:

```kotlin
        dropOldest()
        return true
    }

    /**
     * Links [tag] to [input], which the hook `conversation-type` gives the conversation as if the owner had typed it on the
     * conversation screen (design 14.4 of sub-project 7), and returns the input with the hook's [options]; null when the
     * tag is among the newest ones already. [read] is the input's kind. The conversation's step that starts work for the
     * input then gives the tag the command's number, as for a transcript's input ([onConversationStep]).
     */
    @Synchronized
    fun typed(tag: String, input: OwnerInput, read: String, options: Map<String, String> = emptyMap()): OwnerInput? {
        if (tag in tags) return null
        tags[tag] = Tagged(Role.CONVERSE, 0, read)
        inputs[input.id] = tag
        dropOldest()
        while (inputs.size > MAX_TAGS) inputs.remove(inputs.keys.first())
        return if (options.isEmpty()) input else input.copy(options = options)
    }

    // Keeps the newest MAX_TAGS tags, with what each one waits for.
    private fun dropOldest() {
        while (tags.size > MAX_TAGS) {
            val oldest = tags.keys.first()
            tags.remove(oldest)
            waiting.values.remove(oldest)
            conversing.values.remove(oldest)
            inputs.values.remove(oldest)
            options.remove(oldest)
        }
    }
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHookAnswers.kt`, replace:

```kotlin
        answer.startsWith("text:") && answer.length > 5 -> answer.substring(5)
        else -> null
    }
```

with:

```kotlin
        answer.startsWith("text:") && answer.length > 5 -> answer.substring(5)
        else -> null
    }

    /**
     * The button of a card with [choices] that agent-answer's [words] press, as the owner's tap on it (design 10.3 of
     * sub-project 7): yes, no, confirm, cancel, continue, stop or a pick's number; null when the card has no such button.
     */
    fun cardChoice(words: String, choices: List<CardChoice>): CardChoice? {
        val named = when (words) {
            "yes" -> CardChoice.Yes
            "no" -> CardChoice.No
            "confirm" -> CardChoice.Confirm
            "cancel" -> CardChoice.Cancel
            "continue" -> CardChoice.Continue
            "stop" -> CardChoice.Stop
            else -> words.toIntOrNull()?.let(CardChoice::Pick)
        }
        return named?.takeIf { it in choices }
    }
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHookAnswers.kt`, replace:

```kotlin
import dev.kestrel.app.agent.TaskEvidence
```

with:

```kotlin
import dev.kestrel.app.agent.TaskEvidence
import dev.kestrel.app.conversation.CardChoice
```

- [ ] **Step 4: Run the JVM tests**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.debug.CommandTagsTest --tests=dev.kestrel.app.debug.AgentHookAnswersTest --tests=dev.kestrel.app.debug.HookConversationsTest`
Expected: all PASS, the 3 new tests among them.

- [ ] **Step 5: The hooks**

In `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt`, replace:

```kotlin
            if (!tags.register(tag, event, readOf(event.text, event.named), options)) {
                throw HookError("the tag $tag is in use; give each transcript a new tag")
            }
        }
```

with:

```kotlin
            if (!tags.register(tag, event, readOf(event.text, event.named), options)) {
                throw HookError("the tag $tag is in use; give each transcript a new tag")
            }
        }

        /**
         * Links [tag] and [options] to [input], which the hook `conversation-type` gives the conversation as typed words
         * (design 14.4 of sub-project 7), and starts the record of the steps ([install]); returns the input with the options.
         */
        fun typed(graph: AppGraph, tag: String, input: OwnerInput, options: Map<String, String> = emptyMap()): OwnerInput {
            if (!TAG.matches(tag)) throw HookError("add --es tag with 1 to 32 letters, digits, '-' and '_'")
            install(graph)
            return tags.typed(tag, input, kindOf(input), options) ?: throw HookError("the tag $tag is in use; give each transcript a new tag")
        }

        /** A typed input's kind in lower case, such as command, answer or stop: never its words. */
        fun kindOf(input: OwnerInput): String = input.intent.javaClass.simpleName.lowercase()
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt`, replace:

```kotlin
import dev.kestrel.app.settings.speedText
```

with:

```kotlin
import dev.kestrel.app.settings.speedText
import dev.kestrel.conversation.model.OwnerInput
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/ConversationHooks.kt`, replace:

```kotlin
import android.content.Intent
import dev.kestrel.app.AppGraph
```

with:

```kotlin
import android.content.Intent
import android.os.Build
import dev.kestrel.app.AppGraph
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/ConversationHooks.kt`, replace:

```kotlin
/**
 * The conversation's hooks (design 14.4 of sub-project 7): conversation-state reads the open conversation, and
 * conversation-control starts a new one, ends the quiet time, sets a round's limits or says what the restore found. The
 * answers never contain words.
 */
```

with:

```kotlin
/**
 * The conversation's hooks (design 14.4 of sub-project 7): conversation-state reads the open conversation,
 * conversation-control starts a new one, ends the quiet time, sets a round's limits or says what the restore found, and
 * conversation-type types words as the owner does on the conversation screen (plan 7F). The answers never contain words.
 */
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/ConversationHooks.kt`, replace:

```kotlin
        "CONVERSATION_CONTROL" -> control(intent)
        else -> throw HookError("unknown command '$command'")
    }
```

with:

```kotlin
        "CONVERSATION_CONTROL" -> control(intent)
        "CONVERSATION_TYPE" -> type(intent)
        else -> throw HookError("unknown command '$command'")
    }

    // The words go through the text channel, which reads them as the screen does; a tag names the conversation's command
    // that they start, as voice heard's does, with the same options. "yes, call" is refused off the emulator (plan 4D).
    // A conversation that the command's work opens becomes the hooks' own, which delete-typed deletes, by the one rule of
    // the conversation's steps (Task 13a's CommandTags.onConversationStep); one that the command joins, which may be the
    // owner's, does not (review N4, review B-I2). The callers send "do new" first.
    private suspend fun type(intent: Intent): String {
        val text = intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: throw HookError("add --es text with the words to type")
        if (VoiceHooks.refused(text, Build.HARDWARE)) throw HookError(VoiceHooks.YES_CALL_REFUSED)
        val channel = graph.textChannel
        val input = channel.read(text) ?: throw HookError("add --es text with the words to type")
        val tag = intent.getStringExtra(EXTRA_TAG)
        val live = if (intent.hasExtra(EXTRA_LIVE)) intent.getBooleanExtra(EXTRA_LIVE, false) else null
        val options = VoiceHooks.taskOptions(intent.getStringExtra(EXTRA_MODEL), live, intent.getStringExtra(EXTRA_MARKER))
        channel.submit(if (tag != null) VoiceHooks.typed(graph, tag, input, options) else input)
        return "typed read=${VoiceHooks.kindOf(input)}" + (tag?.let { " tag=$it" } ?: "")
    }
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/ConversationHooks.kt`, replace:

```kotlin
        "restore-check" -> "restored=${ConversationHookAnswers.restored(graph.conversations.restored)}"
        else -> throw HookError("add --es do with one of new, expire, limits or restore-check")
    }
```

with:

```kotlin
        "restore-check" -> "restored=${ConversationHookAnswers.restored(graph.conversations.restored)}"
        // The conversations that the hooks opened (HookConversations), never the owner's: each closed one is deleted and its
        // mark cleared, and one that is open or failed stays marked for a later call.
        "delete-typed" -> {
            val ended = HookConversations.all().associateWith { graph.conversations.deletePast(it) }
            HookConversations.forget(ended.filterValues { it == PastDeletion.DELETED }.keys)
            "deleted=${ended.values.count { it == PastDeletion.DELETED }} open=${ended.values.count { it == PastDeletion.OPEN }} " +
                "failed=${ended.values.count { it == PastDeletion.FAILED }}"
        }
        else -> throw HookError("add --es do with one of new, expire, limits, restore-check or delete-typed")
    }
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/ConversationHooks.kt`, replace:

```kotlin
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput
```

with:

```kotlin
import dev.kestrel.conversation.engine.PastDeletion
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/ConversationHooks.kt`, replace:

```kotlin
        val COMMANDS = setOf("CONVERSATION_STATE", "CONVERSATION_CONTROL")
        private const val EXTRA_DO = "do"
        private const val EXTRA_LIMITS = "limits"
```

with:

```kotlin
        val COMMANDS = setOf("CONVERSATION_STATE", "CONVERSATION_CONTROL", "CONVERSATION_TYPE")
        private const val EXTRA_DO = "do"
        private const val EXTRA_LIMITS = "limits"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_TAG = "tag"
        private const val EXTRA_MODEL = "model"
        private const val EXTRA_LIVE = "live"
        private const val EXTRA_MARKER = "marker"
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, replace:

```kotlin
    // agent-answer -Tag k --es answer <yes|no|confirm|cancel|continue|n|text:…> [--es confirming <category>], as the owner's
    // voice would answer; an answer to a confirmation other than no or cancel needs the category that agent-state read.
```

with:

```kotlin
    // agent-answer -Tag k --es answer <yes|no|confirm|cancel|continue|n|text:…> [--es confirming <category>]
    // [--es channel text], as the owner's voice would answer, or with --es channel text as he answers on the card or the
    // conversation screen; an answer to a confirmation other than no or cancel needs the category that agent-state read.
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, replace:

```kotlin
        AgentHookAnswers.answerRefusal(asking, words, confirming, intent.getStringExtra(EXTRA_CONFIRMING))?.let { throw HookError(it) }
        val event = if (asking == null) {
```

with:

```kotlin
        AgentHookAnswers.answerRefusal(asking, words, confirming, intent.getStringExtra(EXTRA_CONFIRMING))?.let { throw HookError(it) }
        when (val channel = intent.getStringExtra(EXTRA_CHANNEL)) {
            null, "voice" -> Unit
            "text" -> return typedAnswer(tag, words, asking)
            else -> throw HookError("add --es channel with voice or text, not $channel")
        }
        val event = if (asking == null) {
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, replace:

```kotlin
    // agent-script --es file <name> | --ez clear true
```

with:

```kotlin
    // Design 10.3, 12.2 and 12.3 of sub-project 7: the open card's button that the words name, as the owner's tap on the
    // screen's copy, which reaches the conversation as an input, or else the words typed on the screen. A confirmation, a
    // hand-over and a pick are answered only on the card over the app, which the caller taps with `input tap`.
    private fun typedAnswer(tag: String, words: String, asking: QuestionKind?): String {
        val card = graph.cardDesk.open.value ?: throw HookError("no card is open: Kestrel listens, and the conversation is spoken")
        if (!card.answersOnScreen) throw HookError("only the card over the app answers this; tap its button with input tap")
        val channel = graph.textChannel
        when (val choice = AgentHookAnswers.cardChoice(words, card.choices)) {
            null -> channel.submit(channel.read(words) ?: throw HookError("the answer holds no words"))
            else -> channel.choose(card.key, choice)
        }
        return "answered tag=$tag asking=${asking?.let(AgentHookAnswers::asking) ?: "hand-over"} channel=text"
    }
    // agent-script --es file <name> | --ez clear true
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, replace:

```kotlin
        private const val EXTRA_CONFIRMING = "confirming"
```

with:

```kotlin
        private const val EXTRA_CONFIRMING = "confirming"
        private const val EXTRA_CHANNEL = "channel"
```

The code blocks of this step show Task 13 as it was carried out. The review fixes `c8b23b57` (Task 13's review M1 and M2) and `f23bd193` (Task 13a's review M2) changed this code since: `ConversationHooks.type` refuses `--es model`, `--ez live` and `--es marker` without `--es tag` before it reads or submits anything (`ConversationHookAnswers.untaggedOptions`), and notes its untagged input as the hooks' (`VoiceHooks.untagged`, Task 13a); `conversation-control do new` notes its input as the hooks'; agent-answer reads the tagged task's open question's number (`view.question`) beside its kind, `typedAnswer(tag, words, question, asking)` refuses another card (`AgentHookAnswers.openCardRefusal`) before it checks `answersOnScreen`, takes a button's input from `TextChannel.answer(key, choice)` and submits every input as the hooks' (`VoiceHooks.untagged`), and the voice channel's answer goes through `VoiceHooks.untagged` too. "Interfaces for 7G" describes the hooks as built.

- [ ] **Step 6: Run the tests and the builds**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL; `:app` JVM A₁₂ + 3 tests, all PASS. The device test runs in Task 14.

- [ ] **Step 7: Commit**

```bash
git add app/src/debug/kotlin/dev/kestrel/app/debug app/src/testDebug/kotlin/dev/kestrel/app/debug/CommandTagsTest.kt app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt app/src/androidTest/kotlin/dev/kestrel/app/debug/ConversationHooksTest.kt
git commit -m "feat(app): the debug hook conversation-type and agent-answer on the text channel" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 13a: The hook presses the card over the app, and every tagged input's opened conversation is the hooks' own

Plan 7G's amendments F1 and F2, with plan 7G's review N1 and N2, as this plan's own task. Plan 7G's runs answer and clean up through these hooks.

- **The card over the app (F1).** Task 13's `agent-answer --es channel text` presses only the conversation screen's copy of a card and refuses a confirmation, a hand-over and a pick, which only the card over the app answers (S2; decisions 3 and 20); `ScreenActions` act on nothing while that card shows, and a hook may not tap by coordinates. `agent-answer --es channel card` presses the card over the app as the owner's tap on it, through debug code: `CardPresser.press(key, kind, choice)` (in `app/src/debug`) reads the card that the overlay shows and answers it in one block under the card desk's lock (`OverlayCards.withShown`, review R3), and only after its guard has checked, in that block, that the overlay shows the card that the hook read, by its question number and its kind (so a card that replaced it is refused, also one with the same buttons), that the card takes the button that the answer names (`AgentHookAnswers.overlayChoice`, built on `cardChoice`), never "Reply…", and that the desk gave the card to the overlay twice `CARD_ARMING_MILLIS` ago, because the overlay may wait one arming delay before it shows a card, and the card then ignores taps for another. The hook first checks that the open card is the question that agent-state read (its kind). A Continue while the phone is locked is refused, as the owner's tap would wait for his unlock. Kestrel's main code never presses a card, so the press is debug code (review L3): `CardDesk` gives only the seam `OverlayCards`, which shows the card on the overlay, under its lock, with a way to answer it. The hook answers `answered tag=<tag> asking=<kind> channel=card`, and otherwise an error that says what the guard found, which `:tools:eval` reads as a refusal and answers again at its next poll.
- **Tagged inputs' conversations (F2).** `CommandTags.onConversationStep` marks, through `HookConversations.own` (Task 13), the conversation that a hook's input's work (a goal, a resumed goal or a local command) opened, the input a tagged transcript of `voice -Send heard`, a tagged typed input, or a typed input of `conversation-type` without a tag (`CommandTags.untagged`); it is the one rule that marks the hooks' conversations (review B-I2): the step's conversation, when it is not the one that was open before the step. It never marks a conversation that a switch moved into, which may be the owner's past conversation, nor one that a tagged follow-up only continues there, nor one that was open before. The marks are kept in Task 13's file, so that they survive a restart of Kestrel (plan 7G's `resume-after-death`), and `delete-typed` clears each deleted conversation's mark. When the owner's own command (typed or spoken, without a tag, and not typed by a hook) opens or continues a marked conversation, its mark goes (`CommandTags.ownerConversation`, `HookConversations.forget`), so `delete-typed` never deletes his words (review low risk). `conversation-type` notes its untagged inputs (`CommandTags.untagged`), so that they are not taken for the owner's. The hooks see the steps from their first call in a process (`VoiceHooks.install`), as they did: after a restart of Kestrel, a command that the owner gives before any hook call leaves the mark (decision 33).

**Files:**
- Create: `app/src/debug/kotlin/dev/kestrel/app/debug/CardPresser.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/conversation/CardDesk.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHookAnswers.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/CommandTags.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/ConversationHooks.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt`
- Test: create `app/src/testDebug/kotlin/dev/kestrel/app/debug/CardPresserTest.kt`; modify `app/src/test/kotlin/dev/kestrel/app/conversation/CardDeskTest.kt`, `app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt`, `app/src/testDebug/kotlin/dev/kestrel/app/debug/CommandTagsTest.kt`

**Interfaces:**
- Consumes: Task 1's `CardDesk` (`present`, `publish`, `deliver`, `open`), `OwnerCard.takes` and `CARD_ARMING_MILLIS`; Task 13's `HookConversations` (`own`, `forget`), `AgentHookAnswers.cardChoice`, `typedAnswer`, `ConversationHooks.type` and `CommandTags.typed`.
- Produces: `CardDesk(screen, scope, notice, replyOpenMillis, inputIds, clock: () -> Long) : SharedCards, OverlayCards`; `internal class ShownCard(card: OwnerCard, shownMillis: Long)`; `internal interface OverlayCards { fun <T> withShown(block: (shown: ShownCard?, answer: (CardChoice) -> Unit) -> T): T; fun locked(): Boolean }`; debug `internal class CardPresser(overlay: OverlayCards)` with `press(key: Int, kind: QuestionKind?, choice: CardChoice): CardPress` and `internal enum class CardPress { PRESSED, NOT_ON_OVERLAY, OTHER_CARD, NO_BUTTON, NOT_ARMED, LOCKED }`; `AgentHookAnswers.overlayChoice(words, choices): CardChoice?`; `agent-answer --es channel card`, whose answer ends with ` channel=card`; `CommandTags.ownConversation` and `ownerConversation: (ConversationId) -> Unit`, `CommandTags.untagged(input)`, `VoiceHooks.untagged(graph, input)`.

Counts: `:app` JVM (debug) A₁₃ + 8 (`CardDeskTest` + 2, `CardPresserTest` 2, `AgentHookAnswersTest` + 1, `CommandTagsTest` + 3), where A₁₃ is the count after Task 13 (820 from `1b1b8f81`, so 828 after this task); device tests unchanged (505).

- [ ] **Step 1: Write the failing tests**

In `app/src/test/kotlin/dev/kestrel/app/conversation/CardDeskTest.kt`, replace:

```kotlin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
```

with:

```kotlin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
```

In `app/src/test/kotlin/dev/kestrel/app/conversation/CardDeskTest.kt`, replace:

```kotlin
    private class FakeOverlay : OwnerScreen {
```

with:

```kotlin
    // Task 13a (plan 7G's F1, review R3 of plan 7F): the debug hook sees the card on the overlay, and for how long the desk
    // has given it to the screen, under the desk's lock, only while it shows, and its answer answers that card's question.
    @Test
    fun theHookSeesTheOverlaysCardWhileItShowsAndItsAnswerAnswersTheQuestion() = runTest {
        val desk = CardDesk(overlay, backgroundScope, replyOpenMillis = 5_000, clock = { testScheduler.currentTime })
        val confirmation = OwnerCard(4, "Send the message?", listOf(CardChoice.Confirm, CardChoice.Cancel), question = 4, kind = QuestionKind.CONFIRM)
        val asking = async { desk.ask(confirmation) }
        runCurrent()
        advanceTimeBy(1_200)

        assertEquals(confirmation to 1_200L, desk.withShown { shown, _ -> shown?.let { it.card to it.shownMillis } })
        // Kestrel's screen in front takes the card from the overlay at once, before the overlay has withdrawn it.
        desk.screenInFront(true)
        assertNull(desk.withShown { shown, _ -> shown })
        desk.screenInFront(false)
        runCurrent()
        desk.withShown { shown, answer -> if (shown != null) answer(CardChoice.Confirm) }
        runCurrent()

        assertEquals(CardChoice.Confirm, asking.await().choice)
        assertNull(desk.withShown { shown, _ -> shown })
        assertNull(desk.open.value)
    }

    // Plan 7G's review N2: once a newer question took the overlay, the hook no longer sees the card that it read, even in
    // the moment before the overlay has withdrawn it.
    @Test
    fun aCardThatANewerQuestionReplacedIsNotTheOneThatTheHookSees() = runTest {
        val desk = CardDesk(overlay, backgroundScope, replyOpenMillis = 5_000, clock = { testScheduler.currentTime })
        val read = OwnerCard(4, "Send the message?", listOf(CardChoice.Confirm, CardChoice.Cancel), question = 4, kind = QuestionKind.CONFIRM)
        val older = async { desk.ask(read) }
        runCurrent()
        assertEquals(4, desk.withShown { shown, _ -> shown?.card?.key })

        val newer = async(start = CoroutineStart.UNDISPATCHED) { desk.ask(read.copy(key = 5, text = "Delete the chat?", question = 5)) }
        assertNull(desk.withShown { shown, _ -> shown })
        runCurrent()
        assertEquals(5, desk.withShown { shown, _ -> shown?.card?.key })
        assertFalse(older.isCompleted || newer.isCompleted)
        older.cancel()
        newer.cancel()
    }

    private class FakeOverlay : OwnerScreen {
```

Create `app/src/testDebug/kotlin/dev/kestrel/app/debug/CardPresserTest.kt`:

```kotlin
package dev.kestrel.app.debug

import dev.kestrel.app.conversation.CardChoice
import dev.kestrel.app.conversation.OverlayCards
import dev.kestrel.app.conversation.OwnerCard
import dev.kestrel.app.conversation.ShownCard
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.screen.overlay.CARD_ARMING_MILLIS
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The debug hook's press of the card over the app (Task 13a; plan 7G's F1 and review N2): only the card whose question
 * number and kind the hook read, only a button that the card takes, and only once the card takes taps.
 */
class CardPresserTest {
    private val overlay = FakeOverlay()
    private val presser = CardPresser(overlay)
    private val confirmation = OwnerCard(4, "Send the message?", listOf(CardChoice.Confirm, CardChoice.Cancel), question = 4, kind = QuestionKind.CONFIRM)

    @Test
    fun aPressAnswersOnlyTheCardThatTheHookReadWithAButtonThatItTakesOnceItTakesTaps() {
        assertEquals(CardPress.NOT_ON_OVERLAY, presser.press(4, QuestionKind.CONFIRM, CardChoice.Confirm))
        overlay.shown = ShownCard(confirmation, 2 * CARD_ARMING_MILLIS - 1)
        assertEquals(CardPress.NOT_ARMED, presser.press(4, QuestionKind.CONFIRM, CardChoice.Confirm))

        overlay.shown = ShownCard(confirmation, 2 * CARD_ARMING_MILLIS)
        // A card that replaced the one that the hook read is refused, also when its buttons are the same.
        assertEquals(CardPress.OTHER_CARD, presser.press(3, QuestionKind.CONFIRM, CardChoice.Confirm))
        assertEquals(CardPress.OTHER_CARD, presser.press(4, QuestionKind.YES_NO, CardChoice.Confirm))
        assertEquals(CardPress.NO_BUTTON, presser.press(4, QuestionKind.CONFIRM, CardChoice.Yes))
        assertEquals(CardPress.NO_BUTTON, presser.press(4, QuestionKind.CONFIRM, CardChoice.Reply))
        assertEquals(emptyList<CardChoice>(), overlay.answers)

        assertEquals(CardPress.PRESSED, presser.press(4, QuestionKind.CONFIRM, CardChoice.Confirm))
        assertEquals(listOf<CardChoice>(CardChoice.Confirm), overlay.answers)
    }

    // Plan 7E: the owner's Continue while the phone is locked waits for his unlock, so the hook's is refused.
    @Test
    fun aContinueWhileThePhoneIsLockedIsRefused() {
        val held = OwnerCard(9, "Continue the goal?", listOf(CardChoice.Stop, CardChoice.Continue), question = 9, kind = QuestionKind.CONTINUE_GOAL)
        overlay.shown = ShownCard(held, 2 * CARD_ARMING_MILLIS)
        overlay.locked = true

        assertEquals(CardPress.LOCKED, presser.press(9, QuestionKind.CONTINUE_GOAL, CardChoice.Continue))
        assertEquals(CardPress.PRESSED, presser.press(9, QuestionKind.CONTINUE_GOAL, CardChoice.Stop))
        assertEquals(listOf<CardChoice>(CardChoice.Stop), overlay.answers)
    }

    private class FakeOverlay : OverlayCards {
        var shown: ShownCard? = null
        var locked = false
        val answers = mutableListOf<CardChoice>()

        override fun <T> withShown(block: (shown: ShownCard?, answer: (CardChoice) -> Unit) -> T): T = block(shown) { answers += it }

        override fun locked(): Boolean = locked
    }
}
```

In `app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt`, add before its last closing brace:

```kotlin

    // Plan 7G's amendment F1: on the card over the app, a confirmation's yes and no are Confirm and Cancel.
    @Test
    fun theOverlaysButtonIsTheOneThatTheWordsNameAndAConfirmationsYesAndNoAreConfirmAndCancel() {
        val confirmation = listOf(CardChoice.Confirm, CardChoice.Cancel)
        assertEquals(CardChoice.Confirm, AgentHookAnswers.overlayChoice("confirm", confirmation))
        assertEquals(CardChoice.Confirm, AgentHookAnswers.overlayChoice("yes", confirmation))
        assertEquals(CardChoice.Cancel, AgentHookAnswers.overlayChoice("no", confirmation))
        assertEquals(CardChoice.Continue, AgentHookAnswers.overlayChoice("continue", listOf(CardChoice.Cancel, CardChoice.Continue)))
        assertEquals(CardChoice.Pick(2), AgentHookAnswers.overlayChoice("2", listOf(CardChoice.Pick(1), CardChoice.Pick(2), CardChoice.Cancel)))
        assertNull(AgentHookAnswers.overlayChoice("5", listOf(CardChoice.Pick(1), CardChoice.Cancel)))
        assertNull(AgentHookAnswers.overlayChoice("yes", listOf(CardChoice.Stop, CardChoice.Continue)))
    }
```

In `app/src/testDebug/kotlin/dev/kestrel/app/debug/CommandTagsTest.kt`, replace:

```kotlin
import dev.kestrel.conversation.machine.MachineStep
import dev.kestrel.conversation.model.InputChannel
```

with:

```kotlin
import dev.kestrel.conversation.machine.MachineStep
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.InputChannel
```

In `app/src/testDebug/kotlin/dev/kestrel/app/debug/CommandTagsTest.kt`, add before its last closing brace:

```kotlin

    // Plan 7G's review N1: only the conversation that a tagged input's work opened is the hooks' own: never one that a switch
    // moved into, which may be the owner's past conversation, nor one that a tagged follow-up continues there, nor one
    // that an input without a tag opened.
    @Test
    fun onlyTheConversationThatATaggedInputsWorkOpenedIsMarked() {
        val own = mutableListOf<ConversationId>()
        tags.ownConversation = { own += it }
        val conversation = ConversationId("0123456789abcdef0123456789abcdef")
        val goal = checkNotNull(tags.typed("goal", OwnerInput(21, InputChannel.TEXT, InputIntent.Command("open the maps"), 0), "command"))
        val opened = ConversationState(id = conversation)
        tags.onConversationStep(ConversationEvent.Input(goal), ConversationState(), MachineStep(opened, listOf(ConversationEffect.StartGoal(5, 1, goal, "open the maps"))))

        val past = ConversationId("fedcba9876543210fedcba9876543210")
        val switch = checkNotNull(tags.typed("switch", OwnerInput(22, InputChannel.TEXT, InputIntent.Command("continue the conversation about the bus"), 0), "command"))
        val moved = ConversationState(id = past)
        tags.onConversationStep(ConversationEvent.Input(switch), opened, MachineStep(moved, listOf(ConversationEffect.SwitchTo(6, past, switch))))
        val followUp = checkNotNull(tags.typed("follow-up", OwnerInput(23, InputChannel.TEXT, InputIntent.Command("and the times"), 0), "command"))
        tags.onConversationStep(ConversationEvent.Input(followUp), moved, MachineStep(moved, listOf(ConversationEffect.StartGoal(7, 2, followUp, "and the times"))))
        val plain = OwnerInput(24, InputChannel.TEXT, InputIntent.Command("open the settings"), 0)
        val other = ConversationState(id = ConversationId("ab".repeat(16)))
        tags.onConversationStep(ConversationEvent.Input(plain), ConversationState(), MachineStep(other, listOf(ConversationEffect.StartGoal(8, 1, plain, "open the settings"))))

        assertEquals(listOf(conversation), own)
    }

    // Review low risk of plan 7F: the owner's own command, without a tag, in a marked conversation makes it his again, so
    // that delete-typed leaves it; a command that conversation-type gave without a tag, or an answer, does not.
    @Test
    fun theOwnersOwnCommandInAMarkedConversationTakesItsMarkAway() {
        val gone = mutableListOf<ConversationId>()
        tags.ownerConversation = { gone += it }
        val marked = ConversationState(id = ConversationId("0123456789abcdef0123456789abcdef"))
        val hooks = OwnerInput(31, InputChannel.TEXT, InputIntent.Command("and the times"), 0)
        tags.untagged(hooks)
        tags.onConversationStep(ConversationEvent.Input(hooks), marked, MachineStep(marked, listOf(ConversationEffect.StartGoal(9, 2, hooks, "and the times"))))
        val answer = OwnerInput(32, InputChannel.TEXT, InputIntent.Answer(ChannelAnswer.Yes), 0)
        tags.onConversationStep(ConversationEvent.Input(answer), marked, MachineStep(marked, emptyList()))
        assertEquals(emptyList<ConversationId>(), gone)

        val owners = OwnerInput(33, InputChannel.VOICE, InputIntent.Command("and the prices"), 0)
        tags.onConversationStep(ConversationEvent.Input(owners), marked, MachineStep(marked, listOf(ConversationEffect.StartGoal(10, 3, owners, "and the prices"))))
        assertEquals(listOf(marked.id), gone)
    }

    // Review B-I2 of plan 7F: one rule marks the hooks' conversations, the conversation's steps. An untagged command of
    // conversation-type whose work opens a conversation marks it; a hook's command that joins a conversation that the
    // owner's command opened marks nothing.
    @Test
    fun anUntaggedHookCommandThatOpensAConversationMarksItAndOneThatJoinsTheOwnersMarksNothing() {
        val own = mutableListOf<ConversationId>()
        tags.ownConversation = { own += it }
        val opened = ConversationId("0123456789abcdef0123456789abcdef")
        val hooks = OwnerInput(41, InputChannel.TEXT, InputIntent.Command("open the maps"), 0)
        tags.untagged(hooks)
        tags.onConversationStep(ConversationEvent.Input(hooks), ConversationState(), MachineStep(ConversationState(id = opened), listOf(ConversationEffect.StartGoal(11, 1, hooks, "open the maps"))))

        val his = ConversationState(id = ConversationId("ab".repeat(16)))
        val owners = OwnerInput(42, InputChannel.VOICE, InputIntent.Command("read my messages"), 0)
        tags.onConversationStep(ConversationEvent.Input(owners), ConversationState(), MachineStep(his, listOf(ConversationEffect.StartGoal(12, 1, owners, "read my messages"))))
        val joins = OwnerInput(43, InputChannel.TEXT, InputIntent.Command("and the times"), 0)
        tags.untagged(joins)
        tags.onConversationStep(ConversationEvent.Input(joins), his, MachineStep(his, listOf(ConversationEffect.StartGoal(13, 2, joins, "and the times"))))

        assertEquals(listOf(opened), own)
    }
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.conversation.CardDeskTest --tests=dev.kestrel.app.debug.CardPresserTest --tests=dev.kestrel.app.debug.AgentHookAnswersTest --tests=dev.kestrel.app.debug.CommandTagsTest`
Expected: FAIL: the compiler does not know `CardDesk.withShown`, `ShownCard`, `OverlayCards`, `CardPresser`, `CardPress`, the desk's `clock`, `AgentHookAnswers.overlayChoice`, `CommandTags.ownConversation`, `ownerConversation` and `untagged`.

- [ ] **Step 3: The card on the overlay for the debug hook, and its press (F1)**

In `app/src/main/kotlin/dev/kestrel/app/conversation/CardDesk.kt`, replace:

```kotlin
    private val inputIds: () -> Long = InputIds::next,
) : SharedCards {
```

with:

```kotlin
    private val inputIds: () -> Long = InputIds::next,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : SharedCards, OverlayCards {
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/CardDesk.kt`, replace:

```kotlin
    private var replyWait: Job? = null
```

with:

```kotlin
    private var replyWait: Job? = null

    // The card on the overlay, from when the desk gave it to the screen, for the debug hook (Task 13a); under the lock.
    private class Presented(val card: OwnerCard, val at: Long)

    private var presented: Presented? = null
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/CardDesk.kt`, replace:

```kotlin
        openCard.value = card
        showing.value = card?.let { Showing(it, round) }
    }
```

with:

```kotlin
        openCard.value = card
        showing.value = card?.let { Showing(it, round) }
        // A card that is no longer the newest question's is no longer the debug hook's to press, even in the moment before
        // the overlay withdraws it (Task 13a).
        if (presented?.card?.key != card?.key) presented = null
    }
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/CardDesk.kt`, replace:

```kotlin
    private suspend fun present(shown: Showing) {
        val choice = screen.card(shown.card) ?: return
```

with:

```kotlin
    private suspend fun present(shown: Showing) {
        val mine = Presented(shown.card, clock())
        // A newer question may have taken the overlay since this card's turn began: it is not the hook's to press.
        synchronized(lock) { if (showing.value?.card?.key == shown.card.key) presented = mine }
        val choice = try {
            screen.card(shown.card)
        } finally {
            synchronized(lock) { if (presented === mine) presented = null }
        } ?: return
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/CardDesk.kt`, replace:

```kotlin
    override suspend fun ask(card: OwnerCard, onReply: () -> Unit): CardTap {
        val caller = Caller(card, onReply)
```

with:

```kotlin
    // The card that the overlay shows and its answer in one block under the lock (review R3), for the debug hook's press
    // (Task 13a): no other card takes the overlay between the hook's check and its answer.
    override fun <T> withShown(block: (shown: ShownCard?, answer: (CardChoice) -> Unit) -> T): T = synchronized(lock) {
        val mine = presented?.takeIf { !front.value }
        block(mine?.let { ShownCard(it.card, clock() - it.at) }) { choice ->
            if (mine != null && presented === mine) {
                presented = null
                // The hook's press is the owner's tap: it takes its number as it reaches the desk (plan 7F).
                deliver(mine.card.key, CardTap(choice, inputIds()))
            }
        }
    }

    override fun locked(): Boolean = screen.locked()

    override suspend fun ask(card: OwnerCard, onReply: () -> Unit): CardTap {
        val caller = Caller(card, onReply)
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/CardDesk.kt`, replace:

```kotlin
    companion object {
        /** How long a "Reply…" on the overlay waits for the conversation screen before it shows the card again. */
        const val REPLY_OPEN_MILLIS = 5_000L

        /** A Continue tapped on the card while the phone is locked (plan 7E's rule for Continue). */
        const val UNLOCK_FIRST = "Unlock the phone, then tap Continue."
    }
}
```

with:

```kotlin
    companion object {
        /** How long a "Reply…" on the overlay waits for the conversation screen before it shows the card again. */
        const val REPLY_OPEN_MILLIS = 5_000L

        /** A Continue tapped on the card while the phone is locked (plan 7E's rule for Continue). */
        const val UNLOCK_FIRST = "Unlock the phone, then tap Continue."
    }
}

/** The card that the overlay shows, and how long ago the card desk gave it to the screen (Task 13a). */
internal class ShownCard(val card: OwnerCard, val shownMillis: Long)

/**
 * The question card over the app, for the debug hook `agent-answer --es channel card` (Task 13a; plan 7G's F1): Kestrel's
 * main code never presses a card, the owner does, so only debug code (`CardPresser`) answers through this (review L3).
 */
internal interface OverlayCards {
    /**
     * Runs [block] under the card desk's lock with the card that the overlay shows, or null while none does (also while
     * Kestrel's screen is in front), and with `answer`, which answers that card's question as the owner's tap on a button
     * does and takes the card from the hook. No other card takes the overlay while [block] runs.
     */
    fun <T> withShown(block: (shown: ShownCard?, answer: (CardChoice) -> Unit) -> T): T

    /** Whether the phone is locked, when a Continue waits for the owner's unlock (plan 7E). */
    fun locked(): Boolean
}
```

Create `app/src/debug/kotlin/dev/kestrel/app/debug/CardPresser.kt`:

```kotlin
package dev.kestrel.app.debug

import dev.kestrel.app.conversation.CardChoice
import dev.kestrel.app.conversation.OverlayCards
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.screen.overlay.CARD_ARMING_MILLIS

/** What the debug hook's press of the overlay's card found (Task 13a); only [PRESSED] answered it. */
internal enum class CardPress { PRESSED, NOT_ON_OVERLAY, OTHER_CARD, NO_BUTTON, NOT_ARMED, LOCKED }

/**
 * Presses a button of the card over the app as the owner's tap, for agent-answer `--es channel card`, which may not tap the
 * screen by coordinates (Task 13a; plan 7G's F1 and review N2). The check and the answer run in one block under the card
 * desk's lock ([OverlayCards.withShown]; review R3 of plan 7F), so no other card takes the overlay between them. The card
 * must be the one that the hook read, question `key` of `kind`, so that a card that replaced it is refused, also one with
 * the same buttons; it must take the choice, never "Reply…", which opens Kestrel's screen; and the desk must have given it
 * to the screen twice the card's arming delay ago, because the overlay may wait one delay before it shows a card, which
 * then ignores taps for another. A Continue while the phone is locked is refused, because the owner's tap would wait for
 * his unlock.
 */
internal class CardPresser(private val overlay: OverlayCards) {
    fun press(key: Int, kind: QuestionKind?, choice: CardChoice): CardPress = overlay.withShown { shown, answer ->
        when {
            shown == null -> CardPress.NOT_ON_OVERLAY
            shown.card.key != key || shown.card.kind != kind -> CardPress.OTHER_CARD
            choice == CardChoice.Reply || !shown.card.takes(choice) -> CardPress.NO_BUTTON
            shown.shownMillis < 2 * CARD_ARMING_MILLIS -> CardPress.NOT_ARMED
            choice == CardChoice.Continue && overlay.locked() -> CardPress.LOCKED
            else -> {
                answer(choice)
                CardPress.PRESSED
            }
        }
    }
}
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHookAnswers.kt`, replace:

```kotlin
        return named?.takeIf { it in choices }
    }
```

with:

```kotlin
        return named?.takeIf { it in choices }
    }

    /**
     * The button of the card over the app that agent-answer's [words] press with `--es channel card` (Task 13a): as
     * [cardChoice], and on a confirmation's card, whose buttons are Confirm and Cancel, "yes" as Confirm and "no" as
     * Cancel, as the voice reads them; null when the card has no such button.
     */
    fun overlayChoice(words: String, choices: List<CardChoice>): CardChoice? = cardChoice(words, choices) ?: when (words) {
        "yes" -> CardChoice.Confirm.takeIf { it in choices }
        "no" -> CardChoice.Cancel.takeIf { it in choices && CardChoice.Confirm in choices }
        else -> null
    }
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, replace:

```kotlin
import dev.kestrel.app.agent.RoundEvidences
```

with:

```kotlin
import dev.kestrel.app.agent.RoundEvidences
import dev.kestrel.app.conversation.CardChoice
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, replace:

```kotlin
    // [--es channel text], as the owner's voice would answer, or with --es channel text as he answers on the card or the
    // conversation screen; an answer to a confirmation other than no or cancel needs the category that agent-state read.
```

with:

```kotlin
    // [--es channel text|card], as the owner's voice would answer, with --es channel text as he answers on the conversation
    // screen's copy of the card, and with --es channel card as he taps the card over the app (Task 13a); an answer to a
    // confirmation other than no or cancel needs the category that agent-state read.
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, replace:

```kotlin
            "text" -> return typedAnswer(tag, words, asking)
            else -> throw HookError("add --es channel with voice or text, not $channel")
```

with:

```kotlin
            "text" -> return typedAnswer(tag, words, asking)
            "card" -> return cardAnswer(tag, words, asking)
            else -> throw HookError("add --es channel with voice, text or card, not $channel")
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, replace:

```kotlin
    // hand-over and a pick are answered only on the card over the app, which the caller taps with `input tap`.
    private fun typedAnswer(tag: String, words: String, asking: QuestionKind?): String {
        val card = graph.cardDesk.open.value ?: throw HookError("no card is open: Kestrel listens, and the conversation is spoken")
        if (!card.answersOnScreen) throw HookError("only the card over the app answers this; tap its button with input tap")
```

with:

```kotlin
    // hand-over and a pick are answered only on the card over the app, which --es channel card presses (cardAnswer).
    private fun typedAnswer(tag: String, words: String, asking: QuestionKind?): String {
        val card = graph.cardDesk.open.value ?: throw HookError("no card is open: Kestrel listens, and the conversation is spoken")
        if (!card.answersOnScreen) throw HookError("only the card over the app answers this; answer with --es channel card")
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, replace:

```kotlin
        return "answered tag=$tag asking=${asking?.let(AgentHookAnswers::asking) ?: "hand-over"} channel=text"
    }
```

with:

```kotlin
        return "answered tag=$tag asking=${asking?.let(AgentHookAnswers::asking) ?: "hand-over"} channel=text"
    }

    // Task 13a (plan 7G's F1): the button of the card over the app that the words name, pressed through the card desk as the
    // owner's tap, never by the screen's coordinates, and only after the guard of CardPresser: the overlay shows
    // the card of the question that agent-state read, by its number and kind, the card takes that button, and it takes
    // taps. A confirmation, a hand-over and a pick are answered here; a refusal leaves the question open for the caller's
    // next try.
    private fun cardAnswer(tag: String, words: String, asking: QuestionKind?): String {
        val desk = graph.cardDesk
        val card = desk.open.value ?: throw HookError("no card is open: Kestrel listens, and the conversation is spoken")
        if (card.kind != asking) throw HookError("the open card is not the question that agent-state read; read it again")
        // A keypad's number within the pick's range is the owner's entry on the card.
        val choice = AgentHookAnswers.overlayChoice(words, card.choices)
            ?: words.toIntOrNull()?.let(CardChoice::Pick)?.takeIf(card::takes)
            ?: throw HookError("the card has no button for the answer $words")
        when (CardPresser(desk).press(card.key, card.kind, choice)) {
            CardPress.PRESSED -> Unit
            CardPress.NOT_ON_OVERLAY -> throw HookError("the card is not on the overlay: Kestrel's screen is in front, or no screen session shows it")
            CardPress.OTHER_CARD -> throw HookError("another card took the overlay; read agent-state again")
            CardPress.NO_BUTTON -> throw HookError("the card has no button for the answer $words")
            CardPress.NOT_ARMED -> throw HookError("the card does not take taps yet; answer again in a second")
            CardPress.LOCKED -> throw HookError("the phone is locked, and Continue waits for the owner's unlock")
        }
        return "answered tag=$tag asking=${asking?.let(AgentHookAnswers::asking) ?: "hand-over"} channel=card"
    }
```

The code blocks of this step show Task 13a as it was carried out. The review fix `c8b23b57` (Task 13's review M2) changed `typedAnswer` and `cardAnswer` since: both take the tagged task's open question's number (`question`, from `view.question`) beside `asking`, and both refuse a card that is not that question's, by its number and kind, with `AgentHookAnswers.openCardRefusal` (`the open card is not the question of the task of tag <tag>; read agent-state again`), which replaced `cardAnswer`'s `card.kind != asking` check and its error; `CardPresser` checks the card again on the overlay as shown. `f23bd193` (Task 13a's review M2) made `typedAnswer` submit its input as the hooks' (`TextChannel.answer` and `VoiceHooks.untagged`).

- [ ] **Step 4: The conversations that tagged inputs opened (F2)**

In `app/src/debug/kotlin/dev/kestrel/app/debug/CommandTags.kt`, replace:

```kotlin
import dev.kestrel.conversation.machine.MachineStep
import dev.kestrel.conversation.model.OwnerInput
```

with:

```kotlin
import dev.kestrel.conversation.machine.MachineStep
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/CommandTags.kt`, replace:

```kotlin
    @Volatile
    var lastGoalWords: Int = 0
        private set
```

with:

```kotlin
    @Volatile
    var lastGoalWords: Int = 0
        private set

    /**
     * Gets the conversation that a tagged input's work opened, which the hooks count as their own (Task 13a);
     * [VoiceHooks.install] sets it to [HookConversations.own]. It runs under this object's lock.
     */
    @Volatile
    var ownConversation: (ConversationId) -> Unit = {}

    /**
     * Gets the conversation that the owner's own command (typed or spoken, without a tag, and not typed by a hook) opened
     * or continued, which is his and never the hooks' (Task 13a, review low risk); [VoiceHooks.install] sets it to
     * [HookConversations.forget]. It runs under this object's lock.
     */
    @Volatile
    var ownerConversation: (ConversationId) -> Unit = {}

    // The inputs that conversation-type gave without a tag: the hooks', not the owner's.
    private val untaggedInputs = LinkedHashSet<Long>()

    /** Notes [input], which `conversation-type` gives the conversation without a tag, as the hooks' and not the owner's. */
    @Synchronized
    fun untagged(input: OwnerInput) {
        untaggedInputs += input.id
        while (untaggedInputs.size > MAX_TAGS) untaggedInputs.remove(untaggedInputs.first())
    }
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/CommandTags.kt`, replace:

```kotlin
    fun onConversationStep(event: ConversationEvent, state: ConversationState, step: MachineStep) {
        val started = mutableSetOf<Long>()
```

with:

```kotlin
    fun onConversationStep(event: ConversationEvent, state: ConversationState, step: MachineStep) {
        // The owner's own command makes its conversation his: a hook's mark on it goes (review low risk). An answer may be
        // a hook's press of the card, so only a command counts.
        val owners = (event as? ConversationEvent.Input)?.input
            ?.takeIf { it.intent is InputIntent.Command && it.id !in inputs && it.id !in untaggedInputs }
        if (owners != null) step.state.id?.let(ownerConversation)
        val started = mutableSetOf<Long>()
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/CommandTags.kt`, replace:

```kotlin
            val tag = input?.let { inputs.remove(it.id) } ?: continue
            tags[tag]?.let { tags[tag] = it.copy(role = Role.TASK, task = command) }
        }
```

with:

```kotlin
            // Only a conversation that a hook's input's work opened is the hooks' own, the input tagged or typed by
            // conversation-type without a tag, a goal's work or a local request's: a switch moves into a past conversation,
            // and a follow-up continues the open one, either of which may be the owner's (plan 7G's review N1). This is the
            // one rule that marks the hooks' conversations (review B-I2 of plan 7F).
            val hooks = input != null && (input.id in inputs || untaggedInputs.remove(input.id))
            if (hooks && effect !is ConversationEffect.SwitchTo && step.state.id != state.id) step.state.id?.let(ownConversation)
            val tag = input?.let { inputs.remove(it.id) } ?: continue
            tags[tag]?.let { tags[tag] = it.copy(role = Role.TASK, task = command) }
        }
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt`, replace:

```kotlin
            graph.conversationSteps = tags::onConversationStep
```

with:

```kotlin
            tags.ownConversation = { HookConversations.own(it) }
            tags.ownerConversation = { HookConversations.forget(listOf(it)) }
            graph.conversationSteps = tags::onConversationStep
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt`, replace:

```kotlin
        /** A typed input's kind in lower case, such as command, answer or stop: never its words. */
```

with:

```kotlin
        /**
         * Notes [input], which the hook `conversation-type` gives the conversation without a tag, as the hooks' own, so that
         * it is not taken for the owner's command, and starts the record of the steps ([install]; Task 13a); returns it.
         */
        fun untagged(graph: AppGraph, input: OwnerInput): OwnerInput {
            install(graph)
            tags.untagged(input)
            return input
        }

        /** A typed input's kind in lower case, such as command, answer or stop: never its words. */
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/ConversationHooks.kt`, replace:

```kotlin
        channel.submit(if (tag != null) VoiceHooks.typed(graph, tag, input, options) else input)
```

with:

```kotlin
        channel.submit(if (tag != null) VoiceHooks.typed(graph, tag, input, options) else VoiceHooks.untagged(graph, input))
```

The code blocks of this step show Task 13a as it was carried out. The review fix `f23bd193` (Task 13a's review M1 and M2) changed `CommandTags` since. `untaggedInputs` became `hookInputs`, which holds every input that a hook gives the conversation, tagged or not, and keeps it after its work starts, because a local request's switch names its input again later. `untagged(event: Event.Heard)` notes a transcript that a hook sends without a tag (`voice heard`, agent-answer's spoken answer; `VoiceHooks.untagged(graph, event)`), and `input()` notes the input that Listening makes of it as the hooks'. `onConversationStep` no longer takes the mark away only for the owner's command: it collects every input that the step brings, its own and the inputs of the work that it starts (`work(effect)`), and for each of the owner's inputs that carries his words or his choice (`owners(input)`: not the hooks', and not a wordless Stop, Continue or New conversation, nor an answer of `Silence` or `Interrupted`) it removes the mark from the conversation that the input reaches (`reached`: the conversation that a `SwitchTo` for the input moves into, or else the one that the step leaves open). Its marking rule reads `input.id in hookInputs`. `TextChannel.choose` takes its input from the new `TextChannel.answer(key, choice)`, with the same behaviour. "Interfaces for 7G" describes `delete-typed` as built.

- [ ] **Step 5: Run the tests and the builds**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL; `:app` JVM A₁₃ + 8 tests, all PASS. Task 14 runs the device tests, `ConversationHooksTest` among them, whose `delete-typed` deletes its own conversation.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/kestrel/app/conversation/CardDesk.kt app/src/debug/kotlin/dev/kestrel/app/debug app/src/test/kotlin/dev/kestrel/app/conversation/CardDeskTest.kt app/src/testDebug/kotlin/dev/kestrel/app/debug
git commit -m "feat(app): agent-answer presses the card over the app that it read, under the card desk's lock, and the conversations that tagged inputs opened are the hooks' own until the owner continues them" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---
### Task 14: The checks on kestrel-api37 (the controller's steps, run by a delegated agent)

Design 15.2 of sub-project 7 on kestrel-api37: this plan's device tests and the regression of `:core:privacy`'s, the agent's, the hooks', the voice's and the instant commands' device tests; the eval dry suite against the newest one in `.superpowers\sdd\progress.md`, because the voice channel's card, the bar's writer and the numbers of the owner's taps changed; and, outside the instrumentation, the launcher's activity and the typed start of a goal from `ConversationActivity` in front with listening off (design 10.6, main spec S9), which plan 7E moved here, with the lines of `dumpsys activity services` that say which exemption Android used and the audio focus with the speech's types for that start. Afterwards the task deletes its test conversation (`conversation-control --es do delete-typed`, Task 13), clears plan 7D's stand-in round, and puts back the exact listening mode and accessibility setting that Step 1 noted. This task changes no file in git.

**Files:**
- None in git; Step 10 writes `.superpowers\sdd\progress.md` (a delegated agent writes its lines into its report instead); Step 1 writes the data check's state file `H:\Code\kestrel\.superpowers\sdd\plan-7f-owner-data.xml` (git-ignored there), and Step 9 deletes it after the last check.

**Interfaces:**
- Consumes: every earlier task, Task 13's `conversation-type` and `conversation-control --es do delete-typed` among them; plan 7D's hooks `conversation-state`, `conversation-control` and `voice-task` (its stand-in round, and `voice-task -Clear`, whose answer is `each goal's round runs on the phone again`); `voice -Send toggle|pause|off|heard`; plan 7E's hooks `goal-service-state` and `goal-control` (`do=tap`, `do=speak`); `scripts\install.ps1`, `scripts\connected-test.ps1`, `scripts\voice-test.ps1`, `scripts\command-test.ps1`, `scripts\eval.ps1`, `scripts\hook.ps1`; `scripts\lib\Kestrel.ps1`'s `Assert-TestDevice`, `Test-KestrelAvdAnswer`, `Get-EmulatorWindowType`, `Connect-AccessibilityService`, `Get-AccessibilityServiceState`, `Format-ServiceState` and `Test-ServiceListed`, and `scripts\lib\DeviceGuard.ps1`'s `Get-OwnerDataState`, `Compare-OwnerData` and `Format-OwnerDataState`.
- Produces: the results in `.superpowers\sdd\progress.md`, under "## Plan 7F", with spike S9's typed start on kestrel-api37.

Counts: none change. The device tests that run are this plan's AD₀ + 36 of `:app` (its tasks' 33 and the review fixes' 3; 505 with AD₀ = 469, counted at `f23bd193`: no review fix of Tasks 11 to 13a added a device test, and at `51b5517d`: the fixes of Task 14's first run added none either) and the PD₀ + 1 of `:core:privacy` (Task 3 adds `KestrelDatabaseTest`'s test of `deleteClosed`; the rest is a regression), across Steps 3 to 6.

**The build and the emulator.** Task 14 installs `sp3` at `51b5517d` (the last fix of Task 14's first run, which stopped at Step 4) or a later commit, such as the commit of this plan's amendment of 2026-11-27 for Task 14's first run, which contains plans 7D and 7E as built (`284eb0ec` or later, so the build keeps kestrel-api37's database at version 3 and has plan 7E's device-found fixes, notification IDs and accessibility helpers), every review fix of Tasks 1 to 13a, the hooks' of `c8b23b57` and `f23bd193` among them, and the fixes of Task 14's first run (`012ea255` to `51b5517d`: the screen keeps the intent that started it and keeps "Reply…"'s question in its view model, and the tests' fixes); Step 1 checks it (`contains 51b5517d: True`). kestrel-api37 runs without a window: `scripts\start-emulator.ps1` starts it with `-no-window`, because the window links Android's clipboard to Windows' clipboard, which makes `ClipboardHookTest` fail now and then (README). No step needs the window, so the task never starts it with `-Window`; Step 1 reads the window type from the emulator's console (`Get-EmulatorWindowType`) and stops unless it says `headless`. Every check of the emulator's name requires its exact name (`Test-KestrelAvdAnswer`, as the scripts do), so an AVD such as `kestrel-api37-old` never passes it.

**How the task runs.** kestrel-api37 holds the owner's own Google account and Kestrel's Copilot profile, which cannot be made again without the owner; losing them is the one failure that matters more than every result of this task. The controller gives the task to an agent with a strict brief, as for plan 7E's Task 8, and the agent follows these rules and the steps exactly:
- Only kestrel-api37: every command names `-s emulator-5554` (or the serial that `scripts\start-emulator.ps1` printed, when the controller's brief gives another) and starts with the emulator's exact check. No command goes to the A71, the S23+, the AVD `zoran` or any other device, and no adb command runs without `-s`.
- Only the commands of the steps: device work goes only through `scripts\install.ps1`, `scripts\connected-test.ps1`, `scripts\voice-test.ps1`, `scripts\command-test.ps1`, `scripts\eval.ps1` (never with `--live`), `scripts\hook.ps1`, the plain `adb -s emulator-5554` commands that the steps contain, `scripts\lib\Kestrel.ps1`'s `Connect-AccessibilityService` in Step 9, which writes the accessibility setting back only when Kestrel's service is not bound under Step 1's setting, and only on the device that `Assert-TestDevice` accepted, and Step 9's two put-backs, `appops set dev.kestrel.testapp SYSTEM_ALERT_WINDOW` to Step 1's mode and `cmd notification allow_listener` for Kestrel's listener, each only when the state differs from Step 1's, with the reads `Get-AccessibilityServiceState`, `Format-ServiceState` and `Test-ServiceListed`, which change nothing. Never `adb install`, `adb uninstall`, `pm clear`, `pm uninstall`, a Gradle task that installs or runs device tests directly, `scripts\hook.ps1 provider-delete`, `--live`, or anything that signs an account in or out or changes a profile.
- **The data check**, before Step 2, after each of Steps 2 to 9, and at the end: `. scripts\lib\Kestrel.ps1; . scripts\lib\Session.ps1; . scripts\lib\DeviceGuard.ps1; Use-KestrelToolchain; Assert-TestDevice -Serial emulator-5554; $adb = Get-Adb; if (-not (Test-KestrelAvdAnswer @(& $adb -s emulator-5554 emu avd name))) { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; $before = Import-Clixml 'H:\Code\kestrel\.superpowers\sdd\plan-7f-owner-data.xml'; $changes = @(Compare-OwnerData -Serial emulator-5554 -Before $before -During 'plan 7F, Task 14'); if ($changes.Count) { $changes; Write-Error "The owner's data changed or could not be read: stop and report" -ErrorAction Stop }; "owner data: as before ($(Format-OwnerDataState $before 'emulator-5554'))"`. Expected: one line that starts with `owner data: as before`. It compares the state that Step 1 recorded with the state now, by machine, through `scripts\lib\DeviceGuard.ps1`'s `Get-OwnerDataState` and `Compare-OwnerData`, as `scripts\connected-test.ps1` does: the number of Google accounts, and Kestrel's default profile, its kind, the number of Copilot profiles, its `id=` and whether its credential is stored. Any other answer, or an error: stop at once, run no further command on the device, and report.
- Stop at the first failure: a failing test, an exit code other than 0, an answer that differs from a step's Expected, a crash that the step does not expect, a frozen emulator, or anything that the steps do not describe. Then run the data check and report. Do not run anything again, cold-boot, retry or fix, except where a step says so (Step 4's known flaky class); the step helpers' `Listen` toggles a second time by itself when the first toggle left Kestrel off, and stops otherwise.
- Every command that runs Gradle (Steps 2 to 7) takes `H:\Code\kestrel\.superpowers\sdd\gradle.lock` as the controller's brief says, and runs `scripts\gradle.ps1 --stop` before it lets the lock go; the agent also holds the brief's emulator lock for the whole task.
- No goal's round may reach a provider: kestrel-api37's default profile is the owner's Copilot profile. Step 8 sets plan 7D's stand-in round (`voice-task`) and checks by its answer that it is set (`StandIn`, one of the step helpers) before anything that it types reaches the conversation, and a stand-in that is not set stops the command. Step 7's eval dry suite is the one step whose rounds run without the stand-in, because the stand-in replaces every round and the suite's tasks need their own scripted rounds: `scripts\eval.ps1 suite` without `--live` gives every input that it sends its scripted model (`:tools:eval`'s dry suite) and checks the accounts and the profile itself before and after (`AccountGuard`), as at plan 7D's gate; listening is on only for that suite, which the step turns on right before it and off right after it.
- The report holds no words that Kestrel heard or said, no screen text, contact, number or e-mail address: only test names, counts, exit codes and the hooks' answers of kinds and counts. The agent writes only its report and the data check's state file.

**The step helpers.** Steps 7 to 9 each run one command that starts with these helpers, pasted exactly as they are, and then the step's own text; they are plan 7E's Task 8 helpers with `Front`: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; $s = 'emulator-5554'; if (-not (Test-KestrelAvdAnswer @(& $adb -s $s emu avd name))) { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; function Hook([string]$command) { "$(& $adb -s $s shell "am broadcast -n dev.kestrel/dev.kestrel.app.debug.TestHookReceiver -a dev.kestrel.hook.$command" | Select-String 'data=')" }; function Voice([string]$send) { pwsh -NoProfile -File scripts\hook.ps1 -Serial $s -Command voice -Send $send }; function Heard([string]$text) { pwsh -NoProfile -File scripts\hook.ps1 -Serial $s -Command voice -Send heard -Text $text }; function Mode { [regex]::Match("$(pwsh -NoProfile -File scripts\hook.ps1 -Serial $s -Command voice-state)", 'mode=(\w+)').Groups[1].Value }; function Listen { foreach ($try in 1, 2) { Voice 'toggle' | Out-Null; Start-Sleep -Seconds 10; $m = Mode; if ($m -eq 'listening') { 'mode: listening'; return }; if ($m -ne 'off') { break } }; Write-Error "Listening did not start (mode=$m): stop and report" -ErrorAction Stop }; function Quiet { Voice 'off' | Out-Null; Start-Sleep -Seconds 3; $m = Mode; if ($m -ne 'off') { Write-Error "Listening did not turn off (mode=$m): stop and report" -ErrorAction Stop }; 'mode: off' }; function StandIn([int]$seconds) { $answer = "$(pwsh -NoProfile -File scripts\hook.ps1 -Serial $s -Command voice-task -Seconds $seconds -Reply 'stand-in')"; if ($answer -notmatch "each goal's round now takes $seconds s") { Write-Error 'The stand-in round is not set: stop and report' -ErrorAction Stop }; "stand-in: $seconds s" }; function Allowed { & $adb -s $s shell dumpsys activity services dev.kestrel/.app.goal.GoalService | Select-String 'allowStartForeground|allowWhileInUse|foregroundServiceType|isForeground' | ForEach-Object { $_.Line.Trim() } }; function Restarted([string]$old) { $deadline = (Get-Date).AddSeconds(90); do { Start-Sleep -Seconds 2; $new = "$(& $adb -s $s shell pidof dev.kestrel)".Trim(); $bound = "$(& $adb -s $s shell dumpsys activity services dev.kestrel/.app.KestrelAccessibilityService)" -match 'app=ProcessRecord' } until (($new -and $new -ne $old -and $bound) -or (Get-Date) -gt $deadline); if (-not ($new -and $new -ne $old -and $bound)) { Write-Error 'No new process of Kestrel with its accessibility service bound within 90 s: stop and report' -ErrorAction Stop }; 'process: new bound: True' }; function Crashes { @(& $adb -s $s logcat -d | Select-String 'FATAL EXCEPTION' -Context 0,1 | Where-Object { "$($_.Context.PostContext)" -match 'Process: dev\.kestrel,' }).Count }; function Focus([string]$kind) { $n = 1 + [int][regex]::Match("$(Hook 'GOAL_SERVICE_STATE')", ' focusRequests=(\d+)').Groups[1].Value; Hook "GOAL_CONTROL --es do $kind"; $deadline = (Get-Date).AddSeconds(15); do { Start-Sleep -Seconds 1; $state = Hook 'GOAL_SERVICE_STATE' } until (($state -match " focusRequests=$n focusAnswered=$n\b") -or (Get-Date) -gt $deadline); if ($state -notmatch " focusRequests=$n focusAnswered=$n\b") { Write-Error "The $kind request did not answer within 15 s: stop and report" -ErrorAction Stop }; $state }; function Front { "front: $("$(& $adb -s $s shell dumpsys activity activities | Select-String 'topResumedActivity=' | Select-Object -First 1)".Trim())" }`. `Hook` sends one of the hooks that `scripts\hook.ps1` learns only in plan 7G (this plan's `conversation-type`, plan 7D's `conversation-state` and `conversation-control`, plan 7E's `goal-service-state` and `goal-control`) to the receiver with `am broadcast` and prints only the answer's `data=` line, which holds no words; `Voice`, `Heard` and `Mode` use `scripts\hook.ps1`; `Listen` toggles listening on, reads the mode, toggles once more only when it is still off, and stops unless it says `listening`; `Quiet` turns listening off and stops unless it says `off`; `StandIn` sets plan 7D's stand-in round and stops unless its answer says so; `Allowed` prints the goal service's lines of `dumpsys activity services` that name the exemption that Android used; `Crashes` counts Kestrel's fatal exceptions in the log; `Focus` sends `goal-control do=speak` or `do=focus` and waits up to 15 s until `goal-service-state` counts that request as answered, and stops otherwise; `Front` prints the activity in front (`topResumedActivity`). `Restarted` is not used here. Every `Write-Error` in them ends the whole command.

- [ ] **Step 1: The base**

Run: `. scripts\lib\Kestrel.ps1; . scripts\lib\Session.ps1; . scripts\lib\DeviceGuard.ps1; Use-KestrelToolchain; Assert-TestDevice -Serial emulator-5554; $adb = Get-Adb; if (-not (Test-KestrelAvdAnswer @(& $adb -s emulator-5554 emu avd name))) { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; $window = Get-EmulatorWindowType emulator-5554; "window: $window"; if ($window -ne 'headless') { Write-Error 'kestrel-api37 runs with its window: stop and report' -ErrorAction Stop }; $state = Get-OwnerDataState -Serial emulator-5554; Format-OwnerDataState $state 'emulator-5554'; if (-not $state.Profiles -or $state.Profiles.Profile -ne 'copilot' -or $state.Profiles.Credential -ne 'present') { Write-Error "kestrel-api37 does not show the owner's Copilot profile with its credential: stop and report" -ErrorAction Stop }; $state | Export-Clixml 'H:\Code\kestrel\.superpowers\sdd\plan-7f-owner-data.xml'; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 -Command voice-state; "accessibility: $(& $adb -s emulator-5554 shell settings get secure enabled_accessibility_services)"; "overlay app-op: $("$(& $adb -s emulator-5554 shell appops get dev.kestrel.testapp SYSTEM_ALERT_WINDOW)".Trim())"; "notification access: $("$(& $adb -s emulator-5554 shell settings get secure enabled_notification_listeners)" -match 'dev\.kestrel/dev\.kestrel\.datatools\.notifications\.KestrelNotificationListener')"; git status --short; git rev-parse HEAD; git merge-base --is-ancestor 51b5517d HEAD; "contains 51b5517d: $($LASTEXITCODE -eq 0)"; "plan 7F built: $((Test-Path app\src\debug\kotlin\dev\kestrel\app\debug\CardPresser.kt) -and (Test-Path app\src\main\kotlin\dev\kestrel\app\conversation\screen\ConversationActivity.kt))"; "accessibility helper: $([bool](Get-Command Connect-AccessibilityService -ErrorAction SilentlyContinue))"`
Expected: `window: headless` (the command stops otherwise: the controller closes kestrel-api37 with `adb -s emulator-5554 emu kill` and starts it with `scripts\start-emulator.ps1`, without `-Window`, checks its serial, the accounts and the profile again, and gives the task again); one line of `Format-OwnerDataState` with the number of Google accounts, the default profile `copilot` with its credential `present`, and the number of Copilot profiles; the `voice-state` line's `mode=` (`off`, `listening` or `paused`), the accessibility setting, which is not `null`, and the fixture app's overlay app-op (note all three: Steps 4 and 9 use them; the overlay's mode is the word after `SYSTEM_ALERT_WINDOW:`, or `default` when the answer is `No operations.`); `notification access: True`, Kestrel's notification listener, which the kept set holds (AGENTS.md; a `False`: stop and report); no changed file in `app/`, `core/`, `build-logic/` or `testapp/`; HEAD at `51b5517d` (the last fix of Task 14's first run) or a later commit of `sp3`, such as the commit of the amendment of 2026-11-27 for Task 14's first run, `contains 51b5517d: True` (the build has every review fix of Tasks 1 to 13a and the fixes of Task 14's first run, and so plans 7C to 7E as built), `plan 7F built: True` and `accessibility helper: True` (a `False`: stop and report). The state is now in `H:\Code\kestrel\.superpowers\sdd\plan-7f-owner-data.xml`, which every data check compares with.

- [ ] **Step 2: Install this build on kestrel-api37, and the launcher's activity**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; if (-not (Test-KestrelAvdAnswer @(& $adb -s emulator-5554 emu avd name))) { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; & $adb -s emulator-5554 logcat -c; scripts\install.ps1 -Serial emulator-5554; scripts\gradle.ps1 --stop; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 -Command state; "launcher: $(@(& $adb -s emulator-5554 shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER dev.kestrel)[-1])"; Start-Sleep -Seconds 5; $crashes = @(& $adb -s emulator-5554 logcat -d | Select-String 'FATAL EXCEPTION' -Context 0,1 | Where-Object { "$($_.Context.PostContext)" -match 'Process: dev\.kestrel,' }); "kestrel crashes: $($crashes.Count)"; $crashes | ForEach-Object { $_.Line; $_.Context.PostContext }`
Expected: a `Success` line of `adb install`, `commit=<the HEAD of Step 1>` and `dirty=0` in the state, `launcher: dev.kestrel/.app.conversation.screen.ConversationActivity`, and `kestrel crashes: 0`; then the data check (an update keeps Kestrel's data). If the emulator froze at its first voice toggle after the install, as on 2026-11-23 and 2026-11-24, stop and report: the controller cold-boots it with `scripts\start-emulator.ps1`, checks its serial, the accounts and the profile again, and gives the rest of the task.

`scripts\connected-test.ps1` installs `:testapp` before `:app`'s device tests, with `ObscuringActivity`.

- [ ] **Step 3: `:core:privacy`'s device tests**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if (-not (Test-KestrelAvdAnswer @(& (Get-Adb) -s emulator-5554 emu avd name))) { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; scripts\connected-test.ps1 -Serial emulator-5554 :core:privacy:connectedDebugAndroidTest; "exit: $LASTEXITCODE"; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL and `exit: 0`; PD₀ + 1 tests (36 with PD₀ = 35), all PASS, among them Task 3's `deleteClosedDeletesEveryClosedRowButTheKeptOneAndLeavesTheOpenRowAndTheLogs`, which checks the deletion of every past conversation on the real table; the script reports no change of Kestrel's install, of the Google accounts or of Kestrel's profiles. Then the data check. Any failure: stop, run the data check, and report.

- [ ] **Step 4: Every device test of `:app` but the voice's**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; if (-not (Test-KestrelAvdAnswer @(& $adb -s emulator-5554 emu avd name))) { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.notPackage=dev.kestrel.app.voice'; "exit: $LASTEXITCODE"; "overlay app-op: $("$(& $adb -s emulator-5554 shell appops get dev.kestrel.testapp SYSTEM_ALERT_WINDOW)".Trim())"; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL and `exit: 0`; no test fails, among them this plan's `QuestionCardTest` (6), `GridBarAndCardTest`, `ConversationViewsTest` (8, among them `theCardsButtonsArmAgainOnEachVisit`, `53e0823e`), `ConversationScreenTest` (4, among them `theLauncherOpensTheConversationScreenAndItsSettingsOpenTheSettingsApp`), `QuestionFlowTest` (10, with D21's waits divided by 60; its Reply test recreates the screen and checks that the field keeps its focus, `012ea255` and `16518f16`), `TypedBoundaryTest` (3: `aTapThatPassesThroughAnotherAppsWindowSendsNothing`, `anIntentsWordsAndDataNeverReachTheField`, which also checks that the screen keeps its own intent, `012ea255`, and `theScreensViewsAreForAccessibilityToolsOnly`), `PastConversationsTest` (2, whose Delete waits while its confirmed deletion runs, `81d3508e`, and whose `awaitRow` waits for the list's read, `7e755cc0` and `51b5517d`), `SettingsAppTest` (54, whose log pages open by scrolling to them first, `f6866aa4`; the 5 new ones the Conversations page's: `theConversationsPageChangesTheTimesTheSpokenRepliesAndTheRetention`, `theConversationsPageSetsARoundsLimitsAndItsCreditsOnlyForAProfileThatCountsThem`, `deleteAllPastConversationsAsksFirst`, `aDeletionThatRunsShowsWhenThePageOpensAgainAndOffersNoSecond` and `anEarlierDeletionsOutcomeIsNotShownAgain`, whose "Delete all" reports Running before the page can show an earlier outcome, `1459da03`), `ConversationHooksTest` (3, among them `conversationTypeStartsATypedCommandThatItsTagNames`, which types with a tag, as `c8b23b57` requires for options, and whose `delete-typed` deletes its own conversation) and `RoomRecordArchiveTest` (1 new), and the earlier plans' tests of `dev.kestrel.app.agent`, `dev.kestrel.app.conversation` (`GoalServiceTest`, `GoalPauseTest`), `AgentEndToEndTest` with the card's new title, `ClipboardHookTest` (reliable only while kestrel-api37 runs without a window, Step 1) and `AgentHooksTest`; the overlay app-op is the one of Step 1. `OpenSettingsTest`, which `53e0823e` changed, is in `dev.kestrel.app.commands` and runs in Step 6. Against plan 7E's Task 8, Step 3 on the same device (373 run, 258 passed and 115 skipped, or 259 passed and 114 skipped when Kestrel holds the phone-state permission), this run has this plan's 36 tests more (the 33 of its tasks and the 3 of the review fixes of Tasks 8 to 10: `ConversationViewsTest`'s re-arming on each visit, `53e0823e`, `QuestionFlowTest`'s stop of a question's spoken copy, `1a4abd50`, and `TypedBoundaryTest`'s check that only accessibility tools may act on the screen's views, `1b1b8f81`), all of which run: 409 run and 0 failed, with the skipped tests of plan 7E's run (`GoalServiceTest.aCallPausesTheGoalWithListeningOffUntilTheNotificationsContinue` while Kestrel lacks the phone-state permission, `ConversationSearchSpeedTest` without its argument, the tests of `dev.kestrel.app.commands`, which skip themselves without `scripts\command-test.ps1`'s argument, and the tests that skipped before plan 7E for a precondition that their assumption names). A skip of `QuestionFlowTest`'s two speech tests (`aTypedConversationsWordsAreSpokenWithKestrelsOwnVoiceWhenTheSettingIsOnAndKestrelIsOff` and `aTypedAnswerEndsItsQuestionsSpokenCopyAndTheNextRepliesAreStillSpoken`, each `VoiceTests.assumeModels(Engine.TTS)`) means that the emulator lacks the voice's models: note it. These two tests play Kestrel's own voice through the device tests' seam `AppGraph.ownVoicePlayback` with the debug `OwnVoiceWatch` (`4309a7d3`), which `QuestionFlowTest` sets and clears itself; the seam is only for device tests, and no step of this task sets it or `AppGraph.conversationTimers`. This step's first run, on `6df4a94e`, stopped with 16 failures of the screen's classes and one of `SettingsAppTest`; the fixes `012ea255` to `51b5517d` (the amendment of 2026-11-27 for Task 14's first run) added no test, so the run is still 409, and the screen's four classes (`ConversationScreenTest`, `QuestionFlowTest`, `TypedBoundaryTest`, `PastConversationsTest`) wait for the screen's keyboard through `ScreenKeyboard.rule` and `settle`; note in the results that the run is on a build with these fixes. The script reports no change of Kestrel's install, of the Google accounts or of Kestrel's profiles. The tests delete only the conversations that they made (`TypedTests`), so the owner's own past conversations on kestrel-api37 stay. A failure of `TypedBoundaryTest.aTapThatPassesThroughAnotherAppsWindowSendsNothing` at its first tap is a finding about design 12.2 (Android passed the touch to the screen): stop, run the data check, and report it as that finding. When the only failures are in `dev.kestrel.app.screen.SystemUiFilterTest`, which is known to be flaky on the emulator (plan 7D's gate), run that class alone once: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if (-not (Test-KestrelAvdAnswer @(& (Get-Adb) -s emulator-5554 emu avd name))) { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.screen.SystemUiFilterTest'; "exit: $LASTEXITCODE"; scripts\gradle.ps1 --stop`; when it passes, note "flaky, passed when run again" and go on. Then the data check. Any other failure, or a second failure of that class: stop, run the data check, and report.

- [ ] **Step 5: The voice's device tests**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if (-not (Test-KestrelAvdAnswer @(& (Get-Adb) -s emulator-5554 emu avd name))) { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; scripts\voice-test.ps1 -Serial emulator-5554; "exit: $LASTEXITCODE"; scripts\gradle.ps1 --stop`
Expected: `exit: 0`; every test of `dev.kestrel.app.voice` passes and none is skipped (96, as at plan 7E's gate; no review fix changed a test there); a question of `ConversationTest` that shows a card shows the question card, and listening's texts reach the bar through the app graph's `OwnedBar` (Task 5). This run is the device regression of the stop series of Tasks 5 to 8's review fixes (`5b0e4366`, `004635b8`, `ef44ec61`, `1afaf809`, `b3195b82`, `0c1d45b5`, `771132a7`: one stop rule for both voices, each output's stop mark taken at its emission, a switch of Kestrel's mode that is no stop) and of `e6cd8211`'s `Event.Assist(taps)`. Then the data check. Any failure: stop, run the data check, and report.

- [ ] **Step 6: The instant commands' device suite**

Task 5 moved listening's bar writer into the app graph, Task 1 changed when a card's tap takes its number, which decides whether a goal's round may cancel an instant command (plan 7D's `ScreenTurns`), and `53e0823e` changed `OpenSettingsTest` (1), which now takes the owner's path: the launcher opens the conversation screen, its Settings button opens the settings app in the same task, and the test ends with the conversation screen at the task's root.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if (-not (Test-KestrelAvdAnswer @(& (Get-Adb) -s emulator-5554 emu avd name))) { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; scripts\command-test.ps1 -Serial emulator-5554; "exit: $LASTEXITCODE"; scripts\gradle.ps1 --stop`
Expected: `exit: 0`; every test of `dev.kestrel.app.commands` passes and none is skipped, `OpenSettingsTest.openSettingsShowsTheSettingsOverAnotherAppsScreenInKestrelsTaskAndClosesNothing` among them; the script puts the device's state back. Then the data check. Any failure: stop, run the data check, and report.

- [ ] **Step 7: The eval dry suite against the newest one**

The suite waits for Kestrel to listen (`:tools:eval`'s preflight, up to 90 s), and the device tests left it off. Kestrel's screen comes to the front first, so that Android lets the hook start the listening service; listening is on only for the suite (see "How the task runs").

Run, after the step helpers: `& $adb -s $s shell am start -W -n dev.kestrel/.app.conversation.screen.ConversationActivity; Start-Sleep -Seconds 3; Listen; & $adb -s $s shell input keyevent KEYCODE_HOME; scripts\eval.ps1 suite --device $s --runs 1; $suite = $LASTEXITCODE; "exit: $suite"; Quiet; Hook 'CONVERSATION_STATE'; Hook 'CONVERSATION_CONTROL --es do new'; Start-Sleep -Seconds 3; Hook 'CONVERSATION_CONTROL --es do delete-typed'`
Expected: `mode: listening`; the suite's own checks of the accounts and the profile pass; `The results are in eval-results\kestrel-api37-<time>.`; `exit: 0`; every task of kestrel-api37's part of `eval\baseline.json` (`Get-Content eval\baseline.json -Raw | ConvertFrom-Json | ForEach-Object { $_.devices.'kestrel-api37'.tasks.PSObject.Properties.Name }`) has a run line, and each run line has the result that the newest dry suite in `.superpowers\sdd\progress.md` (plan 7D's, under "## Plan 7D", unless a later plan noted one) has for the same task; no run has `reason=limit`, `end=STOPPED_AT_LIMIT`, `end=PAUSED_AT_LIMIT` or `end=INTERRUPTED`; `mode: off` (`Quiet`); a `conversation-state` line of counts and kinds only, with `goal=none`; `new=sent`, then `deleted=<n> open=0 failed=0` with n of 0 or more (note n): every input that the suite sends through the hooks, tagged or not, its spoken answers through agent-answer among them, is the hooks' (Task 13a and `f23bd193`), so the conversations that their work opened are the hooks' own, and these and any that earlier runs left are deleted, and no other. Then the data check. A difference: stop, run the data check, and report, with the task's run line and its `agent-task -Result` line, which hold no words of the owner's.

- [ ] **Step 8: The typed start with listening off, and the screen's leaving**

With listening off, the conversation screen comes to the front, and the hook `conversation-type` types a command on it as the owner would, tagged. The goal's round is plan 7D's stand-in, which acts on nothing and, after the given seconds, ends the goal with the given reply, unless it was stopped first; the step sets it and checks its answer before it types. The goal service then starts while `ConversationActivity` is in front (design 10.6), and the screen leaves once the goal acts and the service shows. The step prints the service's lines of `dumpsys activity services` that name the exemption that Android used, the activity in front before and after, and the audio focus with the speech's types while the typed goal works with listening off (`Focus 'speak'`), which tells whether the start from Kestrel's screen in front gave the service while-in-use (S9). It sends `conversation-control --es do new` before it types, so that the typed command opens the hook's own conversation (review N4), and stops by itself before it types when a conversation or a goal is still open. The typed command carries a tag, which `conversation-type` needs for any option (`c8b23b57`); the step gives none. The step runs on the engine's own waits and Kestrel's own player: the device tests' seams (`AppGraph.conversationTimers`, and `AppGraph.ownVoicePlayback` with the debug `OwnVoiceWatch`) are only for device tests, which set and clear them in their own process, and no hook sets them. The notification's Stop that `goal-control do=tap` presses reaches the conversation as an ordinary wordless Stop of the owner's, which keeps the hooks' mark (`f23bd193`, "Interfaces for 7G"), so the step's last `delete-typed` still deletes its conversation.

Run, after the step helpers: `& $adb -s $s logcat -c; Quiet; & $adb -s $s shell am start -W -n dev.kestrel/.app.conversation.screen.ConversationActivity; Start-Sleep -Seconds 3; Front; Hook 'CONVERSATION_CONTROL --es do new'; Start-Sleep -Seconds 3; $state = Hook 'CONVERSATION_STATE'; $state; if ($state -notmatch '\bopen=no\b' -or $state -notmatch '\bgoal=none\b') { Write-Error 'A conversation or a goal is still open: the check stops before it types' -ErrorAction Stop }; Hook 'CONVERSATION_CONTROL --es do delete-typed'; StandIn 120; Hook "CONVERSATION_TYPE --es text 'check the goal service' --es tag typed-start"; Start-Sleep -Seconds 5; Hook 'VOICE_STATE --es tag typed-start'; Hook 'CONVERSATION_STATE'; Hook 'GOAL_SERVICE_STATE'; Allowed; Front; Focus 'speak'; Hook 'GOAL_CONTROL --es do tap --es action stop'; Start-Sleep -Seconds 8; Hook 'CONVERSATION_STATE'; Hook 'GOAL_SERVICE_STATE'; Hook 'CONVERSATION_CONTROL --es do new'; Start-Sleep -Seconds 3; Hook 'CONVERSATION_CONTROL --es do delete-typed'; pwsh -NoProfile -File scripts\hook.ps1 -Serial $s -Command voice-task -Clear; "kestrel crashes: $(Crashes)"`
Expected, in order:
- `mode: off` (`Quiet`);
- `front:` with `dev.kestrel/.app.conversation.screen.ConversationActivity`;
- `new=sent`, then `open=no … goal=none …`: no conversation is open, so the typed command opens the hook's own. If one is still open (a goal holds it), the command stops there by itself, before it types: run the data check, then Step 9 all the same, which puts the device back, and report;
- `deleted=<n> open=0 failed=0` with n of 0 or more (note n): the hooks' earlier conversations that are still marked (Task 13a) go first, among them those that the work of Step 7's suite, of an untagged `voice heard` or of an agent-answer opened, which are the hooks' since `f23bd193`, so that the step's last deletion counts only its own;
- `stand-in: 120 s` (`StandIn`; the command stops when it is not set, before anything is typed);
- `typed read=command tag=typed-start`;
- `tag=typed-start role=task command=0 task=<n> … phase=running …`;
- `… goal=acting … replyChannel=text …`;
- `service=running notice=working posted=none actions=stop,open progress=none types=special-use applied=special-use android=running androidTypes=special-use starts=<n> refusals=0 refuse=none …` (the typed start) and the service's `allowStartForeground` and `allowWhileInUse` lines (note them: S9's exemption for the typed start);
- `front:` with another activity than `ConversationActivity` (the launcher's): the screen left for the goal;
- `speak=sent`, then `… focus=<granted|failed> focusBy=speak focusTypes=special-use+media-playback focusRequests=<n> focusAnswered=<n>` (note it: S9's audio finding for the typed start with listening off);
- `tapped=stop expanded=none shade=closed`, then `goal=none` and `service=stopped notice=none posted=none … android=none androidTypes=none starts=<n> refusals=0 …`;
- `new=sent`, then `deleted=1 open=0 failed=0`: the step's test conversation is gone, and no other (the notification's wordless Stop left its mark);
- `each goal's round runs on the phone again`: the stand-in round is cleared;
- `kestrel crashes: 0`.

Then the data check. A `refusals=1`, a `front:` that still names `ConversationActivity`, or a press whose answer has `expanded=row`, `expanded=group` or `expanded=group,row` is a finding: stop, run the data check, run Step 9, and report the lines. Any other answer: stop, run the data check, run Step 9, and report.

- [ ] **Step 9: Put kestrel-api37 back**

Run, with the mode, the accessibility setting and the overlay's mode of Step 1, after the step helpers: `$mode = '<the mode of Step 1>'; pwsh -NoProfile -File scripts\hook.ps1 -Serial $s -Command voice-task -Clear; if ($mode -ne 'off' -and (Mode) -eq 'off') { & $adb -s $s shell am start -W -n dev.kestrel/.app.conversation.screen.ConversationActivity; Start-Sleep -Seconds 3; Listen }; if ($mode -eq 'paused') { Voice 'pause' | Out-Null; Start-Sleep -Seconds 3 }; if ($mode -eq 'off') { Quiet }; & $adb -s $s shell cmd statusbar collapse; & $adb -s $s shell input keyevent KEYCODE_HOME; $now = Mode; "mode: $now"; if ($now -ne $mode) { Write-Error "Listening is $now, not $($mode): stop and report" -ErrorAction Stop }; Assert-TestDevice -Serial $s; "accessibility bound: $(Connect-AccessibilityService -Serial $s -Services '<the accessibility setting of Step 1>')"; "accessibility: $(& $adb -s $s shell settings get secure enabled_accessibility_services)"; $overlay = '<the overlay mode of Step 1>'; $op = "$(& $adb -s $s shell appops get dev.kestrel.testapp SYSTEM_ALERT_WINDOW)".Trim(); if (-not (($overlay -eq 'default' -and $op -match 'No operations') -or $op -match "SYSTEM_ALERT_WINDOW: $overlay\b")) { & $adb -s $s shell appops set dev.kestrel.testapp SYSTEM_ALERT_WINDOW $overlay }; "overlay app-op: $("$(& $adb -s $s shell appops get dev.kestrel.testapp SYSTEM_ALERT_WINDOW)".Trim())"; $listener = 'dev.kestrel/dev.kestrel.datatools.notifications.KestrelNotificationListener'; if ("$(& $adb -s $s shell settings get secure enabled_notification_listeners)" -notmatch [regex]::Escape($listener)) { & $adb -s $s shell cmd notification allow_listener $listener }; "notification access: $("$(& $adb -s $s shell settings get secure enabled_notification_listeners)" -match [regex]::Escape($listener))"`
Expected: `each goal's round runs on the phone again`; `mode:` and the mode of Step 1 (the command stops when it is another one); `accessibility bound: True` (it writes nothing when Kestrel's service is already bound under Step 1's setting; `False`: run the data check and report, with the warning that names the service's state); the accessibility setting of Step 1; the overlay app-op of Step 1 (the command sets it back to Step 1's mode when a test left another); `notification access: True` (the command gives Kestrel's listener back with `cmd notification allow_listener` when a test left it off, as the kept set requires; `False`: run the data check and report). Then the data check, the last one of the task, and, when it says `owner data: as before`, `Remove-Item 'H:\Code\kestrel\.superpowers\sdd\plan-7f-owner-data.xml'`.

- [ ] **Step 10: Note the results**

Add to `.superpowers\sdd\progress.md`, under "## Plan 7F" (a delegated agent writes these lines into its report under "## Ledger lines", and the controller copies them): the build's commit on kestrel-api37; the data check's answers before and after every step; the counts of Tasks A1, A2 and 1 to 13a with their review fixes (the end's "Counts": `:app` JVM, device, `:core:conversation`, `:core:agent`, `:core:screen` and `:core:privacy`, as measured at `f23bd193` and unchanged at `51b5517d`); the results of Steps 3 to 6 (the numbers of tests that ran, passed and were skipped, and why); Step 7's results folder with its run lines compared with the newest dry suite; Step 8's lines: S9's typed start on Android 17, with the exemption that Android used (`allowStartForeground`, `allowWhileInUse`), the types that it applied, the audio focus with the speech's types, and whether the screen left; that the test conversation was deleted and the stand-in round cleared; and Step 9's mode, accessibility setting and overlay app-op.

---

### Task 15: The checks on the A71 (waits for the owner)

Design 15.2 of sub-project 7 on the A71 (Android 13, One UI 5.1), within AGENTS.md's limits: the launcher's activity, and the typed goal with listening off, which plan 7E moved here (its decision 2): the typed start of the goal service from `ConversationActivity` in front, where Android 13's service has no type (spike S9), the audio focus for that goal with listening off, and the screen's leaving. Every earlier task is complete and green without this one; this task waits until the owner is back and the A71 is on USB.

The checks call no one, send no message, change none of the owner's accounts, put every setting back, and answer only kinds and counts. The hook `goal-control do=tap` reads the shade's notifications on the phone, in memory only, and presses only Kestrel's own button, by its key, checked again at the press. Kestrel speaks its stand-in reply aloud on the A71 only if its typed replies are spoken, which the check does not turn on. Step 4 deletes the check's own conversation, and only it (`conversation-control --es do delete-typed`, Task 13), and clears plan 7D's stand-in round (`voice-task -Clear`), so that the owner's next goal is a real one; Step 5 puts back the exact listening mode that Step 2 noted, a paused one included, the setting "stay awake" and the accessibility setting.

"Reply…" from the overlay's card, which opens the conversation screen from Kestrel's accessibility overlay under Android 13's rules for starting activities from the background, is not checked on the A71: the stand-in round asks no question, and a scripted round on the A71 would need `scripts\connected-test.ps1` there for this plan's device tests, which the controller has not allowed. kestrel-api37's `QuestionFlowTest` checks it on Android 17; the A71 stays unchecked for it, and Step 6 notes so.

**Files:**
- None in git; Step 6 writes `.superpowers\sdd\progress.md`; Step 3 writes the data check's state file `H:\Code\kestrel\.superpowers\sdd\plan-7f-owner-data-a71.xml` (git-ignored there), and Step 5 deletes it after the last check.

**Interfaces:**
- Consumes: every earlier task; the same hooks as Task 14, `conversation-control --es do delete-typed` and `voice-task -Clear` among them; `scripts\install.ps1`, `scripts\hook.ps1`; `scripts\lib\Kestrel.ps1`'s `Assert-TestDevice`, `Connect-AccessibilityService`, `Get-AccessibilityServiceState`, `Format-ServiceState` and `Test-ServiceListed`, and `scripts\lib\DeviceGuard.ps1`'s `Get-OwnerDataState`, `Compare-OwnerData`, `Format-OwnerDataState` and `Get-GoogleAccountCount`.
- Produces: the A71's results in `.superpowers\sdd\progress.md`, under "## Plan 7F": S9's typed start on Android 13.

Counts: none change.

**How the task runs.** The A71 is the owner's phone, used within AGENTS.md's limits: no call, no message, no change to his accounts, every setting put back, and no words that Kestrel heard in any result. The controller runs the steps with these rules, which are plan 7E's Task 9 rules:
- Only the A71 (`R58N66RVD7Z`) and only the steps' commands: every adb command names `-s R58N66RVD7Z`, and the device work goes only through `scripts\install.ps1`, `scripts\hook.ps1`, the plain adb commands of the steps, and `scripts\lib\Kestrel.ps1`'s `Connect-AccessibilityService` in Step 5, which writes the accessibility setting back only when Kestrel's service is not bound under Step 2's setting, and only on the device that `Assert-TestDevice` accepted, with the reads `Get-AccessibilityServiceState`, `Format-ServiceState` and `Test-ServiceListed`, which change nothing. Never `scripts\connected-test.ps1`, `adb uninstall`, `pm clear`, `--live` or `scripts\hook.ps1 provider-delete`.
- **The data check**, after Steps 3 and 4 and at the end of Step 5: `. scripts\lib\Kestrel.ps1; . scripts\lib\Session.ps1; . scripts\lib\DeviceGuard.ps1; Use-KestrelToolchain; Assert-TestDevice -Serial R58N66RVD7Z; $adb = Get-Adb; $before = Import-Clixml 'H:\Code\kestrel\.superpowers\sdd\plan-7f-owner-data-a71.xml'; $changes = @(Compare-OwnerData -Serial R58N66RVD7Z -Before $before -During 'plan 7F, Task 15'); if ($changes.Count) { $changes; Write-Error "The owner's data changed or could not be read: put the A71 back with Step 5 and ask the controller" -ErrorAction Stop }; "owner data: as before ($(Format-OwnerDataState $before 'R58N66RVD7Z'))"`. Expected: one line that starts with `owner data: as before`. Step 3 records the state right after the install; Step 2 has already counted the Google accounts, and Step 3's state must have the same number. Any other answer, or an error: stop at once, put the A71 back with Step 5 and ask the controller.
- No goal's round may reach a provider or act on the owner's phone: listening stays off for the whole task, and Step 4 sets plan 7D's stand-in round and checks its answer (`StandIn`) before it types anything, so no moment exists in which a typed or heard input of the check can start a real round on the A71.
- Stop at the first failure: an exit code other than 0, an answer that differs from a step's Expected, or anything that the steps do not describe. Then run Step 5, which puts the A71 back whatever ended the task, and ask the controller; nothing is run again.
- The shade is put back however a press ends: `goal-control do=tap` closes it again itself and says what the screen shows afterwards (`shade=closed`, also at the end of its error). It says `shade=open` also when it cannot tell (plan 7E's amendment of 2026-11-26): while the phone is locked the A71 always answers `shade=open`, and no step taps while the phone is locked. When an answer of `goal-control do=tap` says `shade=open`, or has no `shade=` (the hook's timeout), run `& $adb -s R58N66RVD7Z shell cmd statusbar collapse` at once, then go on as the step says for that answer.
- On the A71, design 10.6's rules for the goal's row in One UI's shade are not verified yet: `goal-control do=tap` expands a collapsed group or row before its press and says so (`expanded=none|row|group|group,row`, plan 7E's decision 16), so Step 4's press records the A71's value; another value than `none` does not stop the task when the press worked, and the controller takes it to the design's rule for One UI, as plan 7E's Task 9 does.

**The step helpers.** Steps 4 and 5 each run one command that starts with these helpers, pasted exactly as they are, and then the step's own text; they are plan 7E's Task 9 helpers, whose stops end in this task's rule, with `Front`: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; $s = 'R58N66RVD7Z'; if (-not (@(& $adb devices) -match '^R58N66RVD7Z\s+device')) { Write-Error 'The A71 is not on USB' -ErrorAction Stop }; function Hook([string]$command) { "$(& $adb -s $s shell "am broadcast -n dev.kestrel/dev.kestrel.app.debug.TestHookReceiver -a dev.kestrel.hook.$command" | Select-String 'data=')" }; function Voice([string]$send) { pwsh -NoProfile -File scripts\hook.ps1 -Serial $s -Command voice -Send $send }; function Heard([string]$text) { pwsh -NoProfile -File scripts\hook.ps1 -Serial $s -Command voice -Send heard -Text $text }; function Mode { [regex]::Match("$(pwsh -NoProfile -File scripts\hook.ps1 -Serial $s -Command voice-state)", 'mode=(\w+)').Groups[1].Value }; function Listen { foreach ($try in 1, 2) { Voice 'toggle' | Out-Null; Start-Sleep -Seconds 10; $m = Mode; if ($m -eq 'listening') { 'mode: listening'; return }; if ($m -ne 'off') { break } }; Write-Error "Listening did not start (mode=$m): put the A71 back with Step 5 and ask the controller" -ErrorAction Stop }; function Quiet { Voice 'off' | Out-Null; Start-Sleep -Seconds 3; $m = Mode; if ($m -ne 'off') { Write-Error "Listening did not turn off (mode=$m): put the A71 back with Step 5 and ask the controller" -ErrorAction Stop }; 'mode: off' }; function StandIn([int]$seconds) { $answer = "$(pwsh -NoProfile -File scripts\hook.ps1 -Serial $s -Command voice-task -Seconds $seconds -Reply 'stand-in')"; if ($answer -notmatch "each goal's round now takes $seconds s") { Write-Error 'The stand-in round is not set: put the A71 back with Step 5 and ask the controller' -ErrorAction Stop }; "stand-in: $seconds s" }; function Allowed { & $adb -s $s shell dumpsys activity services dev.kestrel/.app.goal.GoalService | Select-String 'allowStartForeground|allowWhileInUse|foregroundServiceType|isForeground' | ForEach-Object { $_.Line.Trim() } }; function Restarted([string]$old) { $deadline = (Get-Date).AddSeconds(90); do { Start-Sleep -Seconds 2; $new = "$(& $adb -s $s shell pidof dev.kestrel)".Trim(); $bound = "$(& $adb -s $s shell dumpsys activity services dev.kestrel/.app.KestrelAccessibilityService)" -match 'app=ProcessRecord' } until (($new -and $new -ne $old -and $bound) -or (Get-Date) -gt $deadline); if (-not ($new -and $new -ne $old -and $bound)) { Write-Error 'No new process of Kestrel with its accessibility service bound within 90 s: put the A71 back with Step 5 and ask the controller' -ErrorAction Stop }; 'process: new bound: True' }; function Crashes { @(& $adb -s $s logcat -d | Select-String 'FATAL EXCEPTION' -Context 0,1 | Where-Object { "$($_.Context.PostContext)" -match 'Process: dev\.kestrel,' }).Count }; function Focus([string]$kind) { $n = 1 + [int][regex]::Match("$(Hook 'GOAL_SERVICE_STATE')", ' focusRequests=(\d+)').Groups[1].Value; Hook "GOAL_CONTROL --es do $kind"; $deadline = (Get-Date).AddSeconds(15); do { Start-Sleep -Seconds 1; $state = Hook 'GOAL_SERVICE_STATE' } until (($state -match " focusRequests=$n focusAnswered=$n\b") -or (Get-Date) -gt $deadline); if ($state -notmatch " focusRequests=$n focusAnswered=$n\b") { Write-Error "The $kind request did not answer within 15 s: put the A71 back with Step 5 and ask the controller" -ErrorAction Stop }; $state }; function Front { "front: $("$(& $adb -s $s shell dumpsys activity activities | Select-String 'topResumedActivity=' | Select-Object -First 1)".Trim())" }`. Their meanings are those of Task 14's helpers.

- [ ] **Step 1: The owner's part (about 2 minutes)**

The owner connects the A71 by USB, unlocks it, leaves it on with its screen on, and checks in Android's accessibility settings that Kestrel's service is on (he turns it on if it is off). Nothing else needs his hands. From this install on, the launcher's Kestrel icon opens the conversation screen; the controller tells him so.

- [ ] **Step 2: The base, and the A71's state before the checks**

Run: `. scripts\lib\Kestrel.ps1; . scripts\lib\Session.ps1; . scripts\lib\DeviceGuard.ps1; Use-KestrelToolchain; Assert-TestDevice -Serial R58N66RVD7Z; $adb = Get-Adb; git status --short; "google accounts: $(Get-GoogleAccountCount @(Invoke-SettingsCommand -Serial R58N66RVD7Z -Command 'dumpsys account'))"; "stay on: $(& $adb -s R58N66RVD7Z shell settings get global stay_on_while_plugged_in)"; "accessibility: $(& $adb -s R58N66RVD7Z shell settings get secure enabled_accessibility_services)"; "notifications: $("$(@(& $adb -s R58N66RVD7Z shell dumpsys package dev.kestrel | Select-String 'android\.permission\.POST_NOTIFICATIONS:') -join ' | ')".Trim())"; pwsh -NoProfile -File scripts\hook.ps1 -Serial R58N66RVD7Z -Command voice-state; pwsh -NoProfile -File scripts\hook.ps1 -Serial R58N66RVD7Z -Command state; git rev-parse HEAD; git merge-base --is-ancestor 51b5517d HEAD; "contains 51b5517d: $($LASTEXITCODE -eq 0)"; "plan 7F built: $((Test-Path app\src\debug\kotlin\dev\kestrel\app\debug\CardPresser.kt) -and (Test-Path app\src\main\kotlin\dev\kestrel\app\conversation\screen\ConversationActivity.kt))"; "accessibility helper: $([bool](Get-Command Connect-AccessibilityService -ErrorAction SilentlyContinue))"`
Expected: the number of Google accounts on the A71 (note it: Step 3's state must have it); the A71's setting "stay awake" (note it, usually `0`); its accessibility setting, which is not empty or `null` and names Kestrel's service (note it); Kestrel's `POST_NOTIFICATIONS` line, read and not changed (note whether it says `granted=true`); a `voice-state` line whose `mode=` is Kestrel's listening mode, `off`, `listening` or `paused` (note it exactly); Kestrel's `commit=`; HEAD at `51b5517d` (the last fix of Task 14's first run) or a later commit of `sp3`, such as the commit of the amendment of 2026-11-27 for Task 14's first run, with no changed file in `app/`, `core/`, `build-logic/` or `testapp/` in `git status --short`, `contains 51b5517d: True` (the build has every review fix of Tasks 1 to 13a and the fixes of Task 14's first run, and so plans 7C to 7E as built), `plan 7F built: True` and `accessibility helper: True` (a `False`: stop, put the A71 back with Step 5 and ask the controller). Without `POST_NOTIFICATIONS`, the goal notification shows only in Android's Task Manager, so Step 4 ends the goal by typing "stop" instead of pressing the notification's Stop (the step says how) and notes it.

- [ ] **Step 3: Install this build on the A71**

Only builds with plan 7C may be installed on the A71 once an earlier plan installed one there (AGENTS.md's rule of plan 7C); this build has it. From this install on, Kestrel's database on the A71 is at version 3: note it in `.superpowers\sdd\progress.md` if it is new.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; & $adb -s R58N66RVD7Z shell settings put global stay_on_while_plugged_in 2; & $adb -s R58N66RVD7Z logcat -c; scripts\install.ps1 -Serial R58N66RVD7Z; scripts\gradle.ps1 --stop; pwsh -NoProfile -File scripts\hook.ps1 -Serial R58N66RVD7Z -Command state; "launcher: $(@(& $adb -s R58N66RVD7Z shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER dev.kestrel)[-1])"; Start-Sleep -Seconds 5; $crashes = @(& $adb -s R58N66RVD7Z logcat -d | Select-String 'FATAL EXCEPTION' -Context 0,1 | Where-Object { "$($_.Context.PostContext)" -match 'Process: dev\.kestrel,' }); "kestrel crashes: $($crashes.Count)"; . scripts\lib\Session.ps1; . scripts\lib\DeviceGuard.ps1; Assert-TestDevice -Serial R58N66RVD7Z; $state = Get-OwnerDataState -Serial R58N66RVD7Z; Format-OwnerDataState $state 'R58N66RVD7Z'; if (-not $state.Profiles) { Write-Error "Kestrel's profiles on the A71 could not be read: put the A71 back with Step 5 and ask the controller" -ErrorAction Stop }; $state | Export-Clixml 'H:\Code\kestrel\.superpowers\sdd\plan-7f-owner-data-a71.xml'`
Expected: a `Success` line of `adb install`; `commit=<HEAD>` and `dirty=0`; `launcher: dev.kestrel/.app.conversation.screen.ConversationActivity`; `kestrel crashes: 0`; one line of `Format-OwnerDataState` whose number of Google accounts is Step 2's (note the line: the data checks compare with it). The A71 stays awake while it is on USB until Step 5 puts the setting back. Then the data check.

- [ ] **Step 4: The typed goal with listening off on Android 13**

As Task 14's Step 8, on the A71: with listening off, the conversation screen comes to the front, the hook types a tagged command on it, the stand-in round acts on nothing, the goal service starts while `ConversationActivity` is in front, and the screen leaves. Without `POST_NOTIFICATIONS` (Step 2), replace `Hook 'GOAL_CONTROL --es do tap --es action stop'` with `Hook "CONVERSATION_TYPE --es text stop"` and note that the notification's Stop was not pressed; that call has no tag and so takes no option (`c8b23b57` refuses `--es model`, `--ez live` and `--es marker` without a tag), and its Stop is the hooks' input. Either Stop is wordless and leaves the hooks' mark on the check's conversation (`f23bd193`, "Interfaces for 7G"), so the last `delete-typed` still answers `deleted=1`. As in Task 14's Step 8, no step sets the device tests' seams (`AppGraph.conversationTimers`, `AppGraph.ownVoicePlayback`), which are only for device tests.

Run, after the step helpers: `& $adb -s $s logcat -c; Quiet; & $adb -s $s shell am start -W -n dev.kestrel/.app.conversation.screen.ConversationActivity; Start-Sleep -Seconds 3; Front; Hook 'CONVERSATION_CONTROL --es do new'; Start-Sleep -Seconds 3; $state = Hook 'CONVERSATION_STATE'; $state; if ($state -notmatch '\bopen=no\b' -or $state -notmatch '\bgoal=none\b') { Write-Error 'A conversation or a goal is still open: the check stops before it types; put the A71 back with Step 5 and ask the controller' -ErrorAction Stop }; Hook 'CONVERSATION_CONTROL --es do delete-typed'; StandIn 120; Hook "CONVERSATION_TYPE --es text 'check the goal service' --es tag typed-a71"; Start-Sleep -Seconds 5; Hook 'VOICE_STATE --es tag typed-a71'; Hook 'CONVERSATION_STATE'; Hook 'GOAL_SERVICE_STATE'; Allowed; Front; Focus 'speak'; Hook 'GOAL_CONTROL --es do tap --es action stop'; Start-Sleep -Seconds 8; Hook 'CONVERSATION_STATE'; Hook 'GOAL_SERVICE_STATE'; Hook 'CONVERSATION_CONTROL --es do new'; Start-Sleep -Seconds 3; Hook 'CONVERSATION_CONTROL --es do delete-typed'; pwsh -NoProfile -File scripts\hook.ps1 -Serial $s -Command voice-task -Clear; "kestrel crashes: $(Crashes)"`
Expected, in order: `mode: off`; `front:` with `ConversationActivity`; `new=sent`, then `open=no … goal=none …`: the owner's conversation, if one was open, closed into his list, where it stays, and the typed command opens the check's own (if a conversation or a goal is still open, the command stops there by itself, before it types or touches that conversation); `deleted=<n> open=0 failed=0` with n of 0 or more (note n: the hooks' earlier conversations that are still marked, as after an earlier sitting or an eval run whose hook inputs, tagged or not, opened them, go first, so that the step's last deletion counts only its own); `stand-in: 120 s` (or the command stops, before anything is typed); `typed read=command tag=typed-a71`; `role=task … phase=running`; `goal=acting … replyChannel=text`; `service=running notice=working posted=none actions=stop,open progress=none types=none applied=none android=running androidTypes=none starts=<n> refusals=0 refuse=none …` (Android 13: no type) and the service's `allowStartForeground` and `allowWhileInUse` lines (note them); `front:` with another activity than `ConversationActivity`; `speak=sent`, then `focus=granted focusBy=speak focusTypes=none focusRequests=<n> focusAnswered=<n>` (Android 13 hardens no background audio, so a `failed` is a finding: note it); `tapped=stop expanded=<value> shade=closed` (One UI's shade; note the value, `none` expected); `goal=none` and `service=stopped … android=none … starts=<n> refusals=0`; `new=sent`; `deleted=1 open=0 failed=0` (the check's conversation, and no other); `each goal's round runs on the phone again`; `kestrel crashes: 0`. Then the data check. A `refusals=1`, or a screen that stayed: note the lines, put the A71 back with Step 5 and ask the controller. When `goal-control` answers that the goal notification's button is not in the shade (`error: the goal notification's stop is not in the shade; shade=closed`), One UI shows the row in another form: note it, end the goal with `Hook "CONVERSATION_TYPE --es text stop"` after the step helpers, delete the check's conversation with `Hook 'CONVERSATION_CONTROL --es do new'; Start-Sleep -Seconds 3; Hook 'CONVERSATION_CONTROL --es do delete-typed'`, then put the A71 back with Step 5 and ask the controller, who decides whether `NoticeButtons` learns One UI's form.

- [ ] **Step 5: Put the A71 back**

Step 5 runs whatever ended the task. Set `$mode` to the mode, and the two settings to the values, that Step 2 noted. Kestrel's screen comes to the front first, so that Android lets the hook start the listening service.

Run, after the step helpers: `$mode = '<the mode of Step 2>'; pwsh -NoProfile -File scripts\hook.ps1 -Serial $s -Command voice-task -Clear; & $adb -s $s shell cmd statusbar collapse; if ($mode -ne 'off' -and (Mode) -eq 'off') { & $adb -s $s shell am start -W -n dev.kestrel/.app.conversation.screen.ConversationActivity; Start-Sleep -Seconds 3; Listen }; if ($mode -eq 'paused') { Voice 'pause' | Out-Null; Start-Sleep -Seconds 3 }; if ($mode -eq 'off' -and (Mode) -ne 'off') { Quiet }; & $adb -s $s shell input keyevent KEYCODE_HOME; & $adb -s $s shell settings put global stay_on_while_plugged_in '<the value of Step 2>'; "stay on: $(& $adb -s $s shell settings get global stay_on_while_plugged_in)"; Assert-TestDevice -Serial $s; "accessibility bound: $(Connect-AccessibilityService -Serial $s -Services '<the accessibility setting of Step 2>')"; "accessibility: $(& $adb -s $s shell settings get secure enabled_accessibility_services)"; "mode: $(Mode)"`
Expected: `each goal's round runs on the phone again` (the stand-in round stays cleared, also when Step 4 stopped early); the value of "stay awake" of Step 2; `accessibility bound: True` (it writes nothing when Kestrel's service is already bound under Step 2's setting; `False`: ask the controller, with the warning that names the service's state); the accessibility setting of Step 2; `mode:` and exactly the mode of Step 2. If the mode differs, run the command again once; if it still differs, note it and ask the controller. Then the data check, the last one of the task, and, when it says `owner data: as before`, `Remove-Item 'H:\Code\kestrel\.superpowers\sdd\plan-7f-owner-data-a71.xml'`.

- [ ] **Step 6: Note the results**

Add to `.superpowers\sdd\progress.md`, under "## Plan 7F": the build's commit on the A71, the launcher's activity, the data check's answers, and Step 4's lines: S9's typed start on Android 13, with the exemption that Android used, the audio focus, the shade's `expanded=` and whether the screen left; that the check's conversation was deleted and the stand-in round cleared; that the settings, the accessibility setting and the listening mode of Step 2 are back; and that "Reply…" stays unchecked on the A71.

---

## Counts at end

| Suite | Before | After | Where the change comes from |
|---|---|---|---|
| `:core:agent`, Tasks A1 and A2 (before plan 7B's Task 8) | Gₐ | Gₐ + 1 | Task A1 (`AgentToolsTest` + 1) |
| `:core:conversation`, Tasks A1 and A2 | Cₐ | Cₐ + 2 | Task A1 (`ConversationOwnerTest` + 1), Task A2 (`ConversationMachineTest` + 1) |
| `:app` JVM (with `app/src/testDebug`) | A₀ | A₀ + 116 | Task 1 + 11, Task 2 + 0 (no steps), Task 4 + 14, Task 5 + 13, Task 6 + 12, Task 8 + 2, Task 10 + 2, Task 11 + 4, Task 13 + 3, Task 13a + 8, the review fixes of Tasks 1 to 10 + 42 (813 at `1b1b8f81`, where the tasks alone give 771), and the review fixes of Tasks 11 to 13a + 5 (`81d3508e` + 1, `PastPagesRulesTest`; `c8b23b57` + 2, `ConversationHookAnswersTest` and `AgentHookAnswersTest`; `f23bd193` + 2, `CommandTagsTest`, whose third changed test replaces one; 833 at `f23bd193`, and 833 at `51b5517d`: the fixes of Task 14's first run add no JVM test) |
| `:app` device tests | AD₀ | AD₀ + 36 | Task 1 + 2 (`QuestionCardTest` 6 for `ConfirmationCardTest`'s 4), Task 3 + 1, Task 7 + 6, Task 8 + 4, Task 9 + 9, Task 10 + 2, Task 11 + 3, Task 12 + 5, Task 13 + 1, and the review fixes + 3 (`ConversationViewsTest`'s re-arming on each visit, `53e0823e`; `QuestionFlowTest`'s stop of a question's spoken copy, `1a4abd50`; `TypedBoundaryTest`'s check of the accessibility tools, `1b1b8f81`; the review fixes of Tasks 11 to 13a add none, and `53e0823e` changed `OpenSettingsTest` without adding one; 505 at `f23bd193`, and 505 at `51b5517d`: the fixes of Task 14's first run change tests and add none) |
| `:core:conversation`, Tasks 1 to 15 | C₀ | C₀ + 15 | Task 1 + 1 (`ConversationEngineTest`: the hand-over card's Continue; its case of the card's yes is rewritten), Task 3 + 8, Task 4 + 2, Task 5 + 1, and the review fixes + 3 (`e2c95105`, `b3195b82` and `0c1d45b5`; 308 at `1b1b8f81`) |
| `:core:agent`, Tasks 1 to 15 | G₀ | G₀ | none |
| `:core:screen` JVM | S₀ | S₀ | none |
| `:core:privacy` JVM and device tests | P₀, PD₀ | P₀ + 1, PD₀ + 1 | Task 3 (`ConversationArchiveStoreTest` + 1, `KestrelDatabaseTest` + 1); Task 14 runs the device tests |
| `:tools:eval`, `build-logic`, Pester | E₀, … | unchanged | none |

At `284eb0ec` (the amendment of 2026-11-26) the base is A₀ = 717, C₀ = 293, G₀ = 315, S₀ = 101, P₀ = 94, AD₀ = 469 and PD₀ = 35, so the end is `:app` JVM 833, device 505, `:core:conversation` 308, `:core:agent` 315, `:core:screen` 101, `:core:privacy` JVM 95 and device 36. The amendment of 2026-11-26's rebuild check measured the tasks' own end (786, 502 and 305); the amendment of 2026-11-27's rebuild check, with Tasks 11 to 13a applied to `1b1b8f81` in order, measured `:app` JVM 828, `:app` device 505 and `:core:conversation` 308; the amendment of 2026-11-27 for the review fixes measured `:app` JVM 833, `:core:conversation` 308 and `:app` device 505 at `f23bd193`, with Tasks 11 to 13a's review fixes; the fixes of Task 14's first run (`012ea255` to `51b5517d`) leave them as they are (`:app` JVM 833 by their report's `:app:testDebugUnitTest`, and 505 `@Test` in `app\src\androidTest`). A review fix committed after `51b5517d` adds its own tests to these, as its report says.

## Interfaces for 7G

Plan 7G (the hooks' script entries, `:tools:eval`'s turns, typed answers and the tasks of design 15.3) can rely on these names and behaviours:

- **The hook `CONVERSATION_TYPE`** (debug builds, callers with `android.permission.DUMP`): `--es text <words>` (required), optional `--es tag <1 to 32 of [A-Za-z0-9_-]>`, `--es model <id>`, `--ez live <bool>`, `--es marker <words>`, as `voice heard` takes them; `--es model`, `--ez live` and `--es marker` need `--es tag`, because only a tag carries them to the typed input, and a call that gives one of them without a tag types nothing (`ConversationHookAnswers.untaggedOptions`, `c8b23b57`). It reads the words as the conversation screen does (`TextChannel.read`) and gives them to the conversation (`TextChannel.submit`), so the screen, if it is in front, leaves for the goal as for the owner's typing. Answer: `typed read=<kind>[ tag=<tag>]`, where `<kind>` is the typed input's kind in lower case (`command`, `answer`, `stop`, `continue`, `newconversation`, `continueconversation`, `deletehistory`, `remember`, `forget`, `saveroutine`, `continuepast`). Errors: `add --es text with the words to type`, `add --es tag with 1 to 32 letters, digits, '-' and '_'`, `add --es tag for <options>: typed words without a tag take no options` (where `<options>` lists the options that the call gave, such as `--es model, --ez live`, never their values), `the tag <tag> is in use; give each transcript a new tag`, and plan 4D's `only the emulator may answer "yes, call"`. A typed input's tag is read as a transcript's: `VOICE_STATE --es tag <tag>` (`role=converse` and `phase=pending` until the conversation takes it, then `role=task task=<n>` and `phase=running|ended`) and `agent-task -Tag`.
- **`CONVERSATION_CONTROL --es do delete-typed`**: deletes the hooks' own conversations, those that `HookConversations` marked, and never another: a conversation that the work of a hook's input opened (Task 13a's one rule of the conversation's steps, `CommandTags.onConversationStep`). Every input that a hook gives the conversation is the hooks', tagged or not (`f23bd193`, Task 13a's review M2): `CONVERSATION_TYPE`'s typed words, `voice heard`'s transcript with or without a tag, agent-answer's spoken and typed answers and its press of a button on the screen's copy of the card, and `CONVERSATION_CONTROL --es do new`. A conversation that was open before, one that a switch moved into and one that a tagged follow-up continues there are never marked, because they may be the owner's. A mark means that only the hooks' inputs are in the conversation (`f23bd193`, Task 13a's review M1): it goes as soon as an input of the owner's that carries his words or his choice reaches the conversation, not only when he continues it: his command, his answer, and his switch into it ("continue the conversation about …", `ContinuePast`, and the list's "Continue this conversation"). The owner's wordless inputs keep the mark (the controller's decision): Stop, Continue and New conversation, and an answer that is `Silence` or `Interrupted`. They put none of his words into the conversation, and the goal notification's buttons give them too, so `goal-control do=tap`'s press of the notification's Stop, which the receiver turns into an ordinary owner input that no hook can name, leaves a check's conversation the hooks' own, and its `delete-typed` still counts it. The callers send `CONVERSATION_CONTROL --es do new` first, and a check that expects exactly its own deletion clears the hooks' earlier marks with one `delete-typed` before it types (review B-I1); that first call's `deleted=<n>` is 0 or more, because it also counts the conversations that earlier steps' hook inputs opened, an untagged `voice heard`'s and an eval suite's among them. The marks are kept by id in `hook-conversations.txt` of Kestrel's `noBackupFilesDir` (debug builds), so they survive a restart of Kestrel, and the rule that clears them watches every step of every process from its start (`DebugKestrelApplication`, `HookConversations.watch`; the whole-plan review's I2), never only from the first hook call; a deletion clears its mark, and a conversation that is still open, or whose deletion failed, stays marked for a later call. Answer: `deleted=<n> open=<n> failed=<n>`.
- **`AGENT_ANSWER --es channel text`** (`voice`, the default, keeps today's behaviour): as the owner answers on the screen's copy of the open card, the card's button that the answer's words name (`yes`, `no`, `continue`, `stop` or `cancel`), which reaches the conversation as an input that names its question (Task A2); otherwise the words typed on the screen (`text:<words>` for a free question). It answers only the card of the tagged task's open question, by that question's number and kind as the conversation's view gives them for the tag's command (both none for a hand-over's card), so it never answers another command's card under the tag's name (`AgentHookAnswers.openCardRefusal`, `c8b23b57`, Task 13's review M2). Its input, the typed words or the button's answer (`TextChannel.answer(key, choice)`, which `choose` uses too), is noted as the hooks' before the conversation gets it (`f23bd193`). It keeps no tap for a later card. A confirmation, a hand-over and a pick are answered only on the card over the app, which `--es channel card` presses. Answer: today's `answered tag=<tag> asking=<kind>` with ` channel=text`. Errors: `no card is open: Kestrel listens, and the conversation is spoken`, `the open card is not the question of the task of tag <tag>; read agent-state again`, `only the card over the app answers this; answer with --es channel card`, `the answer holds no words`, `the card no longer takes the answer <kind>; read agent-state again` (the card changed before the press; `<kind>` is the button's word, `a number` or `a text answer`, never the answer's words: `AgentHookAnswers.answerKind`), `add --es channel with voice, text or card, not <x>`. The text channel shows a card for every question of a typed conversation, and for every question while Kestrel is off.
- **`AGENT_ANSWER --es channel card`** (Task 13a): presses the card over the app as the owner's tap, through the debug `CardPresser.press(key, kind, choice)`, which checks the card that the overlay shows and answers it in one block under the card desk's lock (`OverlayCards.withShown`), never by coordinates: only the card of the tagged task's open question, by that question's number and kind (`AgentHookAnswers.openCardRefusal`, `c8b23b57`, Task 13's review M2), which the presser then checks again on the overlay, so a card that replaced it is refused, a button that the card takes (`AgentHookAnswers.overlayChoice`: the words of `cardChoice`, and a confirmation's `yes` and `no` as Confirm and Cancel; never "Reply…"), and only once the desk gave the card to the overlay twice `CARD_ARMING_MILLIS` ago; a Continue while the phone is locked is refused. Answer: `answered tag=<tag> asking=<kind> channel=card`. Before the press, a card that is not the tagged task's open question gives `the open card is not the question of the task of tag <tag>; read agent-state again`, which replaced `the open card is not the question that agent-state read; read it again`. Errors of the press name what the guard found (`CardPress`: `NOT_ON_OVERLAY`, `OTHER_CARD`, `NO_BUTTON`, `NOT_ARMED`, `LOCKED`), and the question stays open for the next try; `the card has no button for the answer <kind>` names the answer's kind as the text channel's error does.
- **The launcher's activity** is `dev.kestrel/.app.conversation.screen.ConversationActivity` (`singleTask`, exported, its only filter the launcher's). `MainActivity` is the settings app, exported without a filter; `am start -n dev.kestrel/dev.kestrel.app.MainActivity` and its extras work as before. `ConversationActivity.EXTRA_QUESTION` (`dev.kestrel.app.extra.QUESTION`) is the only extra that the screen reads. The screen keeps the intent that started it: a later intent gives it only that number (`012ea255`), which the screen's view model keeps (`ConversationScreenViewModel.replyTo`, `reply(question)`) across a rotation but not across the process (`16518f16`), and the first intent gives it only when the screen has no saved state and was not reopened from the recent apps.
- **The question card** is `:core:screen`'s `QuestionCard`, whose window title is "Kestrel question" (`CARD_TITLE`; it was "Kestrel confirmation"), with the arming delay `CARD_ARMING_MILLIS` (600 ms). Its buttons' labels are `strings_card.xml`'s: Yes, No, Confirm, Cancel, Continue, Stop and "Reply…", a pick's own labels (up to 4, from the agent's question since Task A1), and for a pick without choices a low keypad (a row with the number, "Delete digit", OK and Move; the digits 1 to 5 and 6 to 0) with Cancel, which gives only a number from 1 to the pick's highest number, and whose Move takes the card to the other end of the screen.
- **The card desk** (`AppGraph.cardDesk`, `CardDesk(screen, scope, notice, replyOpenMillis, inputIds, clock) : SharedCards, OverlayCards`): one card per question number for both channels; a card that got no answer shows again when a new screen session connects (`OwnerScreen.connections`, which `AndroidOwnerSupport(context, sessions: StateFlow<ScreenSession?>, bar, open, calls)` counts); a Continue tapped while the phone is locked waits for the unlock with its tap's number and answers once the screen is on and the phone unlocked (`OwnerScreen.unlocked()`, from `PhoneSignals.screenStates()`, which `AndroidOwnerSupport(…, calls, screens)` takes), `notice` shows `CardDesk.UNLOCK_TO_CONTINUE` ("Unlock the phone to continue.") on the bar, and the card shows again meanwhile, so that another choice, such as Stop, answers in its place and drops the waiting Continue (the whole-plan review's M2); each tap that answers takes its number among the owner's inputs as it reaches the desk, and every caller that it answers gets `CardTap(choice, act)` with that one number; `open: StateFlow<OwnerCard?>` (with `answersOnScreen`, false for a confirmation, a hand-over and a pick, and `takes(choice)`, which takes a keypad's number within the pick's range), `screenInFront(front)`; and, for debug code only, `OverlayCards.withShown(block)` and `locked()` (Task 13a).
- **The text channel** (`AppGraph.textChannel`): `sent: SharedFlow<SentInput>` (`SentInput(intent, before)`: each input with the conversation's view from just before `submit` gave it to the conversation, so the screen's arm never takes a view that already counts the input; the whole-plan review's M3), `read(text)` (`TypedInputs.read(text, view)`: plan 7D's typed reading, `Requests.typed`, in which only the exact name counts and the voice's own requests such as "never mind" stay the owner's words, and an answer to the view's open question names its number, `InputIntent.Answer(answer, question)`), `input(intent)` (with the next `InputIds` number, taken as the input arrives), `submit(input)`, `choose(key, choice)` and `composing(active)`; its `ask` returns a card's answer with the number of the owner's tap (`CardAnswer`), and its words reach the bar through `AppGraph.feedbackBar`. Its `handOver(handOver, part)` owns the hand-over (`HandOverSignals.opened`, the page, its end by itself) only for `HandOverPart.OWN`; beside the voice channel, which owns a spoken conversation's hand-over, it gets `HandOverPart.CARD` and shows only the hand-over's card, so a question has one hand-over (review A-I1). A typed answer whose question had ended is not read as a command: the bar shows `ConversationTexts.ANSWER_ENDED` ("That question had ended, so I didn't use your answer.", review A-M3).
- **The voice channel** (`AppGraph.voiceChannel`, `VoiceChannel(voice, screen, cards, audio, handOvers, permissions, offVoice, audioSettleMillis)`): every output takes both voices' marks when the conversation emits it (`OwnerChannel.take`, which returns an `OutputSlot`: the voice session's arrival, `VoicePath.came()`, and Kestrel's own voice's count of stops, `OffVoice.stops`), and which voice speaks it is decided at its turn: a `ChannelOutput.Said` with `typedCopy` (a typed conversation's spoken copy, which `ConversationOwner` emits with "Speak replies to typed commands" on) goes to Kestrel's own voice while Kestrel is off at that moment (`VoicePath.kestrelOn`, whose one source is `Listening.kestrelOn`), and `OffVoice.speak(text, mark)` returns false when a switch of voice came after the mark (`OffVoice.mark(asked)`, `OwnVoiceMark`), so the words then go to the voice session; turning Kestrel on or off is a switch of voice, never a stop: turning off ends the session's speech and drops no waiting output, and turning on ends what Kestrel's own voice speaks (`VoicePath.onKestrelOn`, `OffVoice.endSpeech()`); every stop of the Orchestrator's also stops Kestrel's own voice (`VoicePath.onSpeechStopped`); `stopSpeech()`, which the app graph calls on every Stop input of the owner's, however it came (for a spoken one the Rules stopped the speech already, and this stop also drops the outputs that the engine emitted before it took the Stop), is the same stop as a spoken one: it stops the voice session's speech (`VoicePath.stopSpeaking`, the Rules' `Event.StopSpeaking`), Kestrel's own voice (`OffVoice.stop()`), and drops the waiting words (review A-M6).
- **The owner port** (Task A1): `OwnerPort.ask(question, kind, choices = emptyList(), highest = 0)` with `OwnerPort.MAX_CHOICES = 4`; a choice answers as `Answer.Number(position)`; `ChannelQuestion.highest`. **The answer's question** (Task A2): `InputIntent.Answer(answer, question = null)`; an answer that names another question than the open one answers nothing.
- **The device tests' seams:** `AppGraph.conversationTimers: ((Timer, Long) -> Long)?` and the instrumentation argument `kestrelWaitDivisor` (60 when it is not given); `AppGraph.ownVoicePlayback: ((LoadedVoice) -> LoadedVoice)?`, what Kestrel's own voice plays each reply on (`AppOffVoice`'s `playback`), with the debug `OwnVoiceWatch` (`wrap(voice)`, `ended(spoke)`, `playing`, `holding`, `release()`), the one count of what Kestrel's own voice spoke (`AppOffVoice` has no count of its own since the whole-plan review's M4): it counts how each reply ended for Kestrel (a held reply as its release or a stop ended it, another as its player ended it), so `ended(Spoke.FINISHED)` counts the replies heard, and it holds a chosen reply until it is released or stopped (`4309a7d3`, Task 9's review M1 and M2); the androidTest helper `TypedTests` (`graph`, `marker`, `marked(words)`, `own(id)`, `prepare(model)`, `finish()`, which deletes only the conversations that the tests made, `bringScreen(screen, compose)`, which waits until the screen's keyboard has settled, `screenInFront(screen)`, `send(compose, words)`, `tapSend(compose)`, `text(id, args)`, with `compose` the class's `AndroidComposeTestRule<*, ConversationActivity>`); the androidTest helper `ScreenKeyboard` (`rule`, which each class of the screen's device tests applies outside its compose rule, and `settle(compose)`, which waits until the keyboard shows exactly while the field has focus and none of its animations runs; `7f46179a`, `fdfd1a2d`); the Compose tags `FIELD_TAG` (`conversation-field`), `PAST_SEARCH_TAG` (`past-search`), `PAST_READING_TAG` (`past-reading`, the list's progress line while it reads, `51b5517d`) and `pastRowTag(id)`; the fixture `ObscuringActivity` of `:testapp`, whose window "Obscuring overlay" needs the app-op `SYSTEM_ALERT_WINDOW`.
- **Past conversations:** `ConversationEngine.deletePast(id)` (checked by the engine's loop, done by its writer) and `deleteAllPast()` (after the writer's barrier, beside the engine's inbox, in its scope; it keeps every conversation that is open at some moment before its transaction) with `PastDeletion { DELETED, OPEN, UNAVAILABLE, FAILED }`; a `deleteAllPast()` while one runs waits for it and deletes nothing again, and `allPastDeletion: StateFlow<AllPastDeletion>` (`Idle`, `Running`, `Ended(outcome)`) says which stands (review B-M4); `ConversationStore.deleteClosed(keep)`, which reads the keep-set under its lock, raises the store's generation and throws `PastUnavailableException` while the index cannot be read, over the port's `RecordArchive.deleteClosed(keep, index)`, which keeps the open row, the logs and the key; `HistorySearch.matching(text): PastMatches` (`All`, `Only(ids)`, `Rebuilding`, `Failed`), over plan 7B's index's shards; `AppGraph.pastConversations`.
- **The Conversations page:** `SettingsPage.CONVERSATIONS` (so `MainActivity`'s `EXTRA_PAGE` `CONVERSATIONS` opens it); `SettingsData.conversations`, `setConversations`, `countsCredits` (`Profile.countsCredits` of the default profile, the one rule that only Copilot counts AI Credits, which `AppModelPort` and the profile page's cost read too; review B-M6), `deleteAllPast` and `allPastDeletion`; the page shows a deletion's outcome only for a deletion that it started or saw running (review B-N1).
- **The bar:** `GoalBar.text(view)` gives design 10.5's "Step 12 · 3 of 7 · …" and `GoalBar.ON_HOLD`; `AppGraph.feedbackBar` (plan 7D's `OwnedBar`, now `OwnedBar(bar, scope, clock)`) is the bar's one writer, which listening, the text channel and the owner's parts (`AndroidOwnerSupport.releaseBar`, `BarOwner.Other`) write through, with `BarOwner.Progress` and `progress(text: String?)`, which shows the newest progress once no other owner's text holds the bar, and drops a waiting line when it gets null at the goal's end; a final reply's kept text (`BarOwner.FinalReply`) holds the bar for its follow-up countdown (review A-M5), and listening lets it go when the Rules say that no window follows it (`Effect.NoFollowUp`, review A-R2); `OwnedBar.OTHERS_MILLIS = 4_000L`. The debug hook `feedback` writes the bar through it too.
- **The goal service for the screen:** `GoalForeground.foreground: StateFlow<ForegroundState>` (`goal`, `running`, `settled`).
- **The call watch:** `AppGraph.phoneStateGrant` (`PhoneStateGrant.check()`) starts `Listening.watchCalls` once after a grant of READ_PHONE_STATE; `ConversationActivity` and `MainActivity` call it as they come to the front (review B-M2).
- **The held goal's sentence after Android's refusal:** `ConversationTexts.OPEN_AND_CONTINUE` ("Open Kestrel and tap Continue.") in place of `CONTINUE_IN_NOTIFICATION`, since Task 8.
- **The goal on the screen:** `GoalControls.Working(behindCommand)`: while plan 7E's `ConversationView.waitsBehindCommand` is set, the screen shows `GoalTexts.BEHIND_COMMAND` ("Waiting for your command to finish") above Stop. While an open continue or limit card carries the goal's buttons, the goal row shows none (`GoalControls.None`), so the screen has one Stop and one Continue.
- **The screen's model:** `ConversationScreenViewModel` feeds `ConversationScreenModel` through `ScreenFeed` (the conversation's view, `GoalForeground.foreground`, `TextChannel.sent`, which carries the debug hook's typed inputs too, and `Listening.commandWaitEnds`); an input's act is read against its `SentInput.before` (`ConversationScreenRules.sent(state, sent)`), never against the model's newest view; the screen's arm is per act (`ScreenState.armed: Arm?`: `Arm.Command(after, tap)` for a typed command or a microphone tap, `Arm.Goal(id, record)` for Continue or an answer that lets the goal go on), and it ends with its act: the microphone's tap number (`Listening.assist(): Long`, carried by the Rules' `Event.Assist(taps)`) ends with the `CommandWaitEnd(taps, input)` of the listening that it asked for. `ConversationScreenViewModel.visits` counts the screen's arrivals in front, which `ConversationApp` gives every card it draws (`LocalScreenVisit`), on the conversation and on the past conversations' pages, so that the card's buttons arm again on each visit. `ConversationScreenViewModel.replyTo` holds the question that "Reply…" named, which puts the keyboard on the field while that question is open (`16518f16`).
- **Instant commands and rounds:** plan 7D's `ScreenTurns` as built, unchanged by this plan (Task 2 is obsolete). Every act of the owner's reaches the conversation with its number among his inputs, taken as it arrives: a typed input and a button of the screen's copy of a card (`TextChannel.input`), a tap on the card over the app (`CardTap.act`, which `CardAnswer.act`, `OwnerReply.tap`, `ConversationEvent.ContinueAnswered.tap`, `ConversationEffect.ResumeGoal.tap` and `OwnerAct.tap` carry into `RunningGoal.actedFor`), and a button of the goal notification (plan 7E); the engine has no `inputIds` and numbers nothing itself. A tap on a hand-over's (or a permission request's) card returns its number with the end (`HandOverAnswer.act`), which `ConversationOwner`'s `acted` gives to `RunningGoal.actedFor` before the hand-over returns to the round (item 38).

## Decisions for the controller

Each item is a reading of the design that the design leaves open, or a choice of this plan's. The controller decided items 1, 2, 5, 8, 10, 11, 19, 20 and 22 to 25 on 2026-11-25, with the review of plan 7F, items 29 to 33 with its re-review and plan 7G's, items 36 to 39 with the amendment of 2026-11-26, and items 41 to 49 with the plan reviews of 2026-11-26; the others keep the plan's recommendation, which the controller confirms with the owner before Task A1, or the plan changes.

1. **One card per question number**, whichever channel asks (decided): `CardDesk` joins every channel's ask of question N into one card with the most choices, each caller gets the tapped `CardChoice`, and a caller whose choices lack it keeps waiting; a replaced card gives no answer instead of "no". Two cards for one question would ask twice, and a "no" from a card that went away could decline a confirmation that the owner never saw.
2. **The screen shows the open card while it is in front** (decided), and the overlay's card waits until the screen leaves; a button on the screen's copy reaches the conversation as an input, so that the goal gets a fresh look; the copy ignores taps for 600 ms; and a confirmation, a hand-over and a pick are answered only on the overlay's card: the screen shows their words and "Show the app".
3. **A confirmation is always a button**: typed words at a confirmation are a command, which counts as "no". Recommendation: accept; it follows D8 and the table of design 10.3, which gives a confirmation no "Reply…". The transcript shows the typed words.
4. **The screenshot question's card has Yes, No and "Reply…"**, without the design's "Cancel", which would mean No. Recommendation: accept; the main spec's update (plan 7.0 or 7G) notes it.
5. **The screen leaves when the goal that the owner started on it works and its service runs in the foreground** (decided): `GoalForeground.foreground` says that the service runs and that Android answered every start; one reducer, `ConversationScreenModel`, takes every input, and a `ViewModel` keeps it across configuration changes, during which the screen counts as still in front (review N3: `onPause` skips `inFront(false)` while `isChangingConfigurations`), so a rotation neither disarms the wait nor hands the card back to the overlay. It never leaves on its own, so the notification's Open interrupts the goal (D13).
6. **The microphone is `Listening.assist()`**: Kestrel turns on if it is off and says "Yes?". Recommendation: accept; it is the path of the side key and the assistant gesture.
7. **A typed round waits up to 60 s for a running instant command** instead of cancelling it. Withdrawn with Task 2 (amendment of 2026-11-26): plan 7D's `ScreenTurns` as built decides it for every channel by the input numbers (item 36).
8. **The bar's goal progress of design 10.5 is built here, through the bar's one writer** (decided; since the amendment of 2026-11-26 the writer is plan 7D's `OwnedBar`, built in the app graph, with `BarOwner.Progress`, in place of a `SharedFeedbackBar` beside it): `OwnedBar` passes listening's texts, the text channel's words, the owner's parts' release of a question's text and the progress; a kept text wins, and the newest progress waits until the kept text is released or 4 s after another text, and then shows by itself, so "On hold" shows after the held goal's own sentence (review low item b); it replaces only an earlier progress.
9. **Typed replies with listening off** are spoken by `AppOffVoice`, which loads the owner's voice inside plan 7E's `whileSpeaking` and closes it 30 s after the last reply. Recommendation: accept. If the owner turns Kestrel on within those 30 s, both voices are loaded until `AppOffVoice` closes its own.
10. **Deleting past conversations** (decided): one after a question, from the list or the transcript; all from the Conversations page after a question, through a deletion of its own in the archive's port (`RecordArchive.deleteClosed`, in plan 7B's store and plan 7C's table, which are built), because the port's `deleteAll` is the whole of "delete history" and also deletes the logs and erases the key (plan 7C's `ae33cfda`): one transaction deletes every closed row but those that were open at some moment since the owner asked (the engine's keep-set, which the loop fills and the store reads under its lock; review M1), with the index's entries, and keeps the open row, the logs and the key; the store reads no body, writes only the shards that changed and raises its generation. One deletion is checked by the engine's loop, which never deletes the open conversation or the one that a running switch opens, and done by the engine's writer in the loop's order (plan 7B's rule for writes); "Delete all" waits at the engine's writer's barrier for the writes queued before it and then runs beside the engine's inbox and writer (review N2), and the store keeps the open conversation, which its index never holds; the page shows "Deleting past conversations…" until the outcome comes back, which it reads out. While the index cannot be read, "Delete all" deletes nothing and the page says so (`PastDeletion.UNAVAILABLE`).
11. **The list's search is `HistorySearch.matching`** (decided), the search's own rule (`HistoryRanking`) over the index's shards, run once the typing pauses and off the main thread; until its first read ends, the list says "Loading past conversations…", a later read keeps the rows that are shown under a thin progress line (review B-M3), and while the index cannot be read, design 9.5's "Kestrel can't read past conversations right now. It will try again later." The list shows each first request as the index keeps it, cut once at 80 characters.
12. **The list's date** is the day on which the conversation began, with its year (the list holds up to 365 days), as "Continue the conversation from <day>" names a conversation by its first day. Recommendation: accept.
13. **The round limits are lists of choices**: 50, 100, 200, 500, 1,000 or 2,000 replies; 5, 15, 30, 60, 120 or 240 minutes; 10, 100, 250, 500, 1,000, 2,500 or 5,000 AI Credits, the credits only while the default profile is Copilot's. Recommendation: accept, or the owner names other values within the design's ranges.
14. **`MainActivity` stays exported without a filter**, because the scripts and the device tests start it with `am start`. Recommendation: accept; it reads only a page's name and an askable permission, as today. Its window stays unguarded, so that its device tests and tools act on it as today; the Conversations page's "Delete all" dialog is guarded.
15. **The card's window title becomes "Kestrel question".** Recommendation: accept; the card now asks every kind of question.
16. **The device tests shorten D21's waits through a seam of the engine** (`timerMillis`, set only by `AppGraph.conversationTimers` in device tests). Recommendation: accept; design 15.2 asks for it, and release builds never set it.
17. **The merged manifest reaches the unit test through `build-logic`'s `MergedManifestArgument`**, an input of the test task. Recommendation: accept; the test then checks the manifest that Android gets, as design 12.2 asks.
18. **The obscured-touch test** uses a window of `:testapp` at half opacity, through which Android passes touches (it blocks them above 0.8), and allows the fixture app's `SYSTEM_ALERT_WINDOW` for the test only, then puts the app-op back. Recommendation: accept.
19. **The device tests delete only their own conversations** (decided): every request carries the run's marker, `TypedTests` notes the conversations that the tests made and deletes them with `deletePast`, and `PastConversationsTest` finds its rows by their tags and the marker. A conversation that was open before a test, such as the owner's, closes into his list and stays there.
20. **`agent-answer` answers as the owner does** (decided): with `--es channel text`, a button of the screen's copy becomes an input that names its question, no tap is kept for a later card, and a confirmation, a hand-over and a pick are refused; with `--es channel card` (Task 13a, plan 7G's F1), the card over the app is pressed through the debug `CardPresser`, only the card of the question that the hook read (number and kind), checked and answered in one block under the desk's lock (review R3), never by coordinates. The press is debug code, because Kestrel's main code never presses a card (review L3); the desk gives it only the seam `OverlayCards`.
21. **`scripts\hook.ps1` learns `conversation-type`, `delete-typed` and `--es channel card` in plan 7G**; until then the controller's steps use `am broadcast`. Recommendation: accept, as plans 7D and 7E did.
22. **A pick's choices come from the agent** (decided; Task A1, which amends plans 7A and 7B as one task, because the port and its implementations compile only together): up to 4, and `look_closer` offers its numbers only when there are at most 4 and they run from 1. A pick without choices gets Cancel.
23. **`look_closer`'s grid: the number is entered on the overlay's card** (decided): a low keypad of up to 3 digits, which gives only a number from 1 to the pick's highest number (review N1: the desk answers the pick with any such number, and refuses one outside the range), and whose Move key takes the card to the other end of the screen, off the numbers that it covered (review low item a), where the card arms again for `CARD_ARMING_MILLIS` (review L2); the pick has no "Reply…", so Kestrel's screen never covers the grid.
24. **The checks of Tasks 14 and 15 put the device back** (decided): they clear the stand-in round (`voice-task -Clear`) and put back the exact listening mode, a paused one included, through the hooks. They send `conversation-control --es do new` before they type, so that the typed command opens the hook's own conversation (review N4), and they delete it through `delete-typed`, which deletes only the conversations that the hooks opened. Before they type, they clear the hooks' earlier marks with one `delete-typed` (Task 14's Step 7 after the eval suite, whose tagged inputs mark their conversations, and Steps 8 and Task 15's Step 4 before the stand-in), so that the last deletion counts exactly the check's own (review B-I1).
25. **"Reply…" on the A71 stays unchecked** (G5): the stand-in round asks no question, and a scripted round there needs `connected-test.ps1`, which AGENTS.md does not allow on the A71. kestrel-api37's `QuestionFlowTest` checks it on Android 17. Recommendation: accept, or the controller allows one scripted run on the A71.
26. **"Reply…" on the overlay hides the overlay's card** until the screen is in front or 5 s have passed, so that the card does not cover the screen as it opens and comes back if the screen did not open. Recommendation: accept.
27. **A held goal's Continue tapped on the card while the phone is locked waits for the unlock**, as the goal notification's Continue does (plan 7E): the desk keeps the tap with its number and answers with it once the screen is on and the phone unlocked, the bar says "Unlock the phone to continue." (review A, nit N4; the whole-plan review's M2), and the card shows again meanwhile, so that its Stop can answer in its place; the card's buttons are Stop, then Continue (design 10.3; review B-M1), and the held notification's own buttons stay Cancel and Continue (plan 7E). Recommendation: accept.
28. **The constant `CONTINUE_IN_NOTIFICATION` becomes `OPEN_AND_CONTINUE`**, because plan 7E's sentence "Open Kestrel and tap Continue." names the screen's Continue, not the notification's; in Task 8, once opening Kestrel shows the screen with its Continue (amendment of 2026-11-26; it was Task 3). Recommendation: accept.
29. **Tasks A1 and A2 run before plan 7B's Task 8** (decided, review N5): they change code that plans 7A and 7B built, need only plan 7A and plan 7B's Tasks 1 to 7 and 5a to 5d, and have their own checks, with the plan checker over Tasks A1 and A2 alone (`START_TASK = 'A1'`, `END_TASK = 'A2'`; `END_TASK` is new in the checker and leaves out what follows that task). Plan 7B Task 9's `RecordedOwner` and `LocalRequestsTest.FakeDialogue`, and plan 7D Task 3's `GoalOwner`, change with them (the texts at the end of their section).
30. **"Delete all past conversations" runs beside the engine's inbox** (decided, review N2), in the engine's scope, so that the owner's leaving the page stops nothing; the store keeps the open conversation; the outcome comes back to the page, which shows "Deleting past conversations…" until then and reads the outcome out through a live region.
31. **The past conversations' pages draw the open card** (decided, review N6) under their title; its "Reply…" closes the pages and puts the keyboard on the conversation's field. Its buttons arm again on each of the screen's visits in front, as the conversation's card does (decided with the amendment of 2026-11-27: the pages sit inside `ConversationApp`'s `CompositionLocalProvider(LocalScreenVisit provides visit)`).
32. **A screen button's answer names its question** (decided, review low item c; Task A2), and the screen's copy is keyed by the question's number, so a tap that comes late answers no later question.
33. **The hooks' own conversations** (decided, plan 7G's F2 and its review N1): `HookConversations` marks a conversation only when a hook's command or a tagged input's work opened it, by one rule over the conversation's steps (Task 13a's `CommandTags.onConversationStep`; review B-I2), never when it was open before, a switch moved into it, or a tagged follow-up continues it there; the marks are kept by id in a debug-only file, so they survive a restart, and a deletion clears its mark. The owner's own input with his words or his choice (his command, his answer or his switch into it; never a hook's input, tagged or not) takes the mark away from the conversation that it reaches, so `delete-typed` never deletes his words (review low risk, and Task 13a's review M1 and M2); his wordless inputs (Stop, Continue, New conversation, an answer of `Silence` or `Interrupted`) keep it. The rule watches every step of every process from its start in debug builds (`DebugKestrelApplication` calls `HookConversations.watch` before any of the graph's work; the whole-plan review's I2), so the owner's words clear a mark that a restart kept also before any hook call. The card hook presses only the card whose number and kind match what it read (plan 7G's F1 and its review N2).
34. **A goal's progress line never outlives its goal** (decided, review L1): `GoalBar.follow` passes null when the bar has no line for the goal, and `OwnedBar.progress(null)` drops a line that still waits for the bar. A line that already shows fades by itself.
35. **The keypad card arms again after Move** (decided, review L2), because a tap may have been aimed at what was under the card's new place.
36. **A tap on the card over the app is the owner's act of its moment** (decided by the controller, 2026-11-26): the card desk takes its `InputIds` number as the tap arrives in `:app`, as plan 7E's notification buttons do, and the answer carries it into the engine (`CardAnswer`, `OwnerReply`, `ContinueAnswered.tap`, `ResumeGoal.tap`, `OwnerAct.tap`; Task 1), so that a command that the owner started between his tap and the engine's loop is never cancelled by the round that the tap resumes. The engine's `inputIds` goes, because no act of the owner's comes without its number any more.
37. **Typed words are read by plan 7D's typed reading, and their answers name their question** (decided by the controller, 2026-11-26): `TypedInputs` reads with `Requests.typed` (the exact name only; "never mind", "nothing", "forget it" and "turn off" stay the owner's words) and gives `InputIntent.Answer(answer, question)` with the view's open question (Task 4).
38. **A hand-over's Continue on the card is an owner input** (settled by the controller, 2026-11-26: one rule for every act of the owner's): the card desk takes the tap's `InputIds` number at the tap, the channel's hand-over returns it with its end (`HandOverAnswer`), and `ConversationOwner` gives it to the running round (`acted`) before the hand-over returns, as the spoken `Kestrel, continue` gives its input's number (`EndHandOver(…, act)`). So the round may cancel an instant command that the owner started before his tap, and waits for one that he started after it (Task 1, `aContinueTappedOnAHandOversCardNamesTheTapsOwnNumber`). The same holds for the card of a permission request.
39. **The eval dry suite of Task 14 runs without plan 7D's stand-in round** (accepted by the controller, 2026-11-26), because the stand-in replaces every round and the suite's tasks need their own scripted rounds; `scripts\eval.ps1 suite` without `--live` gives every input its scripted model and checks the accounts and the profile itself, as at plan 7D's gate, and listening is on only for the suite. Recommendation: accept; every other step that can start a round sets the stand-in first.
40. **The screen says when the goal waits behind the owner's command** with plan 7E's notification words, "Waiting for your command to finish", above Stop (Tasks 6 and 7). Recommendation: accept.
41. **One hand-over per question** (decided by the controller with review A-I1): the channel that owns the conversation's hand-over signals it, and the other shows only its card (`HandOverPart.CARD`); Kestrel turning off reopens no page.
42. **How words are played is decided once, where they are routed** (decided, review A-I2): `Said.ownVoice` travels with the output, and the voice channel follows it (design 11). Changed by the stop series of Tasks 5 and 6's review fixes (`b3195b82`, `0c1d45b5`, `771132a7`): an output takes its voices' marks at its emission (`OwnerChannel.take`), and its voice is decided at its turn (`Said.typedCopy` and `VoicePath.kestrelOn`, whose one source is `Listening.kestrelOn`), because a switch of voice is not a stop and drops nothing that waits.
43. **A typed answer that came after its question ended** (decided, review A-M3) is not used: the bar says "That question had ended, so I didn't use your answer." Reading it as a new command could act on words that the owner meant only as an answer (design 12.2); he types it again as a command if he wants it.
44. **A card that got no answer shows again when the screen session comes back** (decided, review A-M4), through the desk's count of sessions.
45. **The bar and Kestrel's own voice** (decided, review A-M5 and A-M6, and their re-check): a final reply holds the bar for its follow-up countdown and lets it go when the Rules say that none follows it; the owner's Stop, typed or spoken, is one stop: Kestrel's speech and its own voice stop at once.
46. **One rule marks the hooks' conversations** (decided, review B-I2): Task 13a's step rule, for tagged inputs and the hook's untagged typed ones; Task 13's view-based marking goes.
47. **The call watch after a grant starts on every path to Kestrel's front** (decided, review B-M2), through `PhoneStateGrant`.
48. **The list keeps its rows while it searches, and "Delete all" lives in the engine** (decided, review B-M3 and B-M4): a second "Delete all" while one runs waits for it and deletes nothing again, which the page cannot offer anyway while the engine says that one runs; the page shows an outcome only for a deletion that it saw (review B-N1), and the rows that a re-read keeps have no Delete until the list is current (review B-N2).
49. **Only Copilot counts AI Credits, by one rule** (decided, review B-M6): `Profile.countsCredits`.

## Self-review

- **Review findings.** C1 (no `leftScreen` on answers; screen buttons as inputs): Tasks 4 and 7. C2 and C3 (the list's search and deleting all on the index): Task 3. S1 and S2: Tasks 1, 4 and 7. S3 (the copy's arming): Task 7. S4 (dialogs and menus guarded): Tasks 7, 11 and 12. S5 (no queued tap; the hook refuses what only the overlay answers): Tasks 4 and 13. A1 to A3 (service in the foreground, one reducer, a `ViewModel`): Tasks 5, 6 and 8. A5 (Enter sends, up to four lines): Task 7. A6 (`SetupState`): Task 8. T1 and T2: Tasks 8 to 11 and 13. E1 to E3 and G5: Tasks 13 to 15. G1: Task A1 and Task 1. G3 (one card per number): Task 1. G4: Task 9. GoalBar's one writer: Task 5. The pages' logic out of the Composables: Tasks 6 and 11. The re-review: N1 (a keypad's number within the pick's range): Tasks A1 and 1; N2 ("Delete all" beside the inbox): Tasks 3 and 12; N3 (no disarming during a configuration change): Task 8; N4 (the hooks mark only the conversations that they opened, and the checks send "do new" first): Tasks 13 to 15; N5 (Tasks A1 and A2 before plan 7B's Task 8, with their own checks): their section; N6 (the pages draw the open card): Task 11; low items a to d: Tasks 1, 5, A2 and 3; plan 7G's amendments with its review N1 and N2: Task 13a. The third review: R1 (the `async` import): Task 3; R2 (the checks stop by themselves when a conversation or goal is open after `do new`): Tasks 14 and 15; R3 (the press checks and answers under one lock): Task 13a; R4 (the engine works while "Delete all" waits for the index): Task 3; L1: Task 5; L2: Task 1; L3 (the press in debug code behind `OverlayCards`): Task 13a; the low risk (the owner's command takes a mark away): Task 13a. The plan reviews of 2026-11-26: A-I1, A-M2 and A-M3: Task 4; A-I2, A-M5 and A-M6: Task 5; A-I3, A-M1, A-M4 and the nits N1 to N6: Task 1 (and Global Constraints); A-M7: Task 7; B-I1, B-M5 and B-M7: Tasks 14 and 15; B-I2: Tasks 13 and 13a; B-M1: the design changes and item 27; B-M2: Task 8; B-M3: Task 11; B-M4: Tasks 3 and 12; B-M6: Task 12; B's nits: Tasks 8, 12, 13a and this section.
- **Design coverage.** D20 and 10.1: Tasks 7 and 8 (the screen, the launcher, the banner, Settings, New conversation, the microphone). 10.2 (leaving Kestrel's screen): Tasks 4, 5, 6 and 8. 10.3 and D21 (cards, "Reply…", waits, picks): Tasks A1, 1, 4 and 9. 10.4 (Stop, Continue, Cancel; a touch that interrupts): Tasks 6, 7 and 9. 10.5 (the bar): Task 5. 10.6 (the typed start of the service): Task 8 and Task 14's Step 8, and Task 15. 11 (speech rules, typed replies with listening off): Tasks 5 and 9. 12.2 and 12.3 (the typed-input boundary; confirmations only over the app): Tasks 1, 4, 7, 8 and 10, and Task 13's hook for DUMP callers only. 9.5 and 9.6 (past conversations on the index, their search, Continue and Delete): Tasks 3 and 11. 13 (the Conversations page): Task 12. 14.4 (`conversation-type`, typed answers): Task 13. 15.2's device tests: Tasks 7 to 13, run in Task 14. Plan 7E's moved checks (its decision 2): Task 14's Step 8 and Task 15.
- **Placeholder scan.** No step says "to be decided", "as needed" or "similar to"; every step that changes code gives the whole code, and every replacement names its old text. Tasks 14 and 15 name the values that the controller fills in from an earlier step: Task 14's listening mode, accessibility setting and overlay's mode of Step 1, and Task 15's listening mode, "stay awake" setting and accessibility setting of Step 2.
- **Names across tasks.** `OwnerPort.MAX_CHOICES`, `ChannelQuestion.highest`, `InputIntent.Answer(answer, question)`, `CardQuestion`, `NumberPad`, `CardEnd`, `CARD_TITLE`, `CARD_ARMING_MILLIS`, `OwnerCard`, `CardChoice`, `CardDesk`, `SharedCards`, `CardTap`, `CardAnswer`, `OwnerReply`, `HandOverAnswer`, `HandOverPart`, `ChannelOutput.Said.typedCopy`, `OffVoice`, `ConversationTexts.ANSWER_ENDED`, `OwnerScreen.connections`, `PhoneStateGrant`, `AllPastDeletion`, `Profile.countsCredits`, `TextChannel`, `TypedInputs`, `GoalBar`, `OwnedBar.progress`, `BarOwner.Progress`, `GoalControls.Working(behindCommand)`, `ForegroundState`, `AppOffVoice`, `Transcript`, `ConversationScreenRules`, `ConversationScreenModel`, `ConversationActions`, `ScreenGuard`, `GuardWindow`, `ConversationScreen`, `ConversationApp`, `ConversationScreenViewModel`, `ConversationActivity`, `SetupState`, `TypedTests`, `PastMatches`, `PastList`, `PastPagesRules`, `PastConversations`, `PastPages`, `ConversationsPage`, `HookConversations`, `CommandTags.typed`, `CommandTags.ownConversation`, `ownerConversation` and `untagged`, `VoiceHooks.untagged`, `OverlayCards`, `ShownCard`, `CardPresser`, `CardPress`, `AgentHookAnswers.cardChoice` and `overlayChoice` keep one signature from the task that makes them to every task that uses them.
- **The plan checker** (with its new `END_TASK`) ran clean at `410ae0fd` over plan 7B's Tasks 8 to 10, plan 7C from Task 5, plans 7D and 7E, and this plan's Tasks A1 to 13a. At `c1ff29ac` (plans 7A and 7B, this plan's Tasks A1 and A2, plan 7C's Tasks 1 to 6, 9 and 9b, and plan 7D's Tasks 1 and 2 built; plan 7B's engine after the fixes of its Task 10, its re-checks (`e76ad7a6`, `50b99c61`), its whole-plan review (`a05ea85a`) and that review's re-check (`4b0cf87f`: `Message.Reopened` carries the chosen and the closed conversation, and the switch's end is `switchEnd`); plan 7C's archive after `ae33cfda` and `6999fa5c`), it runs clean over plan 7C's Task 7, plan 7D from its Task 3 (as committed in `c1ff29ac`, with its writes held during the settings page's "Delete history", which this plan's single deletion joins through `send`, and `inTurn` for spoken outputs), plan 7E (`0b60ddea`) and this plan's Tasks 1 to 13a (241 operations, 97 files for this plan), with no `PROBLEM:` line. Against `sp3`'s HEAD alone, Tasks 1 to 13a meet the texts that plans 7B to 7E create, which is why "Before you start" runs the checker on them only after those plans are merged.
- **The amendment of 2026-11-26.** The plan checker runs clean against `284eb0ec` alone from Task 1 (354 operations, 122 files), and from Tasks 4, 8 and 11 on the simulated files of the tasks before them; every task's code, applied to `284eb0ec` in order, builds, and `:core:conversation:test :core:agent:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :core:privacy:testDebugUnitTest :core:screen:testDebugUnitTest` pass with the end's counts, and `aContinueTappedOnAHandOversCardNamesTheTapsOwnNumber` fails without Task 1's change of the hand-over; every PowerShell command of Tasks 14 and 15 parses in PowerShell 7 and 5.1 with the step helpers before it. Plans 7D's and 7E's findings: `ScreenTurns`' final rule (Task 2), the numbered answers and the inputs' numbers at arrival (Tasks 1 and 4), the one bar writer (Task 5), the one card per question number (Task 1), no new notification (so no new `KestrelNotification` ID), the screen's Continue in the refusal's sentence (Task 8), `waitsBehindCommand` on the screen (Tasks 6 and 7), and the setup state read through `SetupStatusReader` and `AccessibilitySettings` (Task 8's `SetupState`).
