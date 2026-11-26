# The Goal Service and Resilience (Plan 7E) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every open goal a foreground service, `GoalService`, with its notification and its buttons (Stop and Open while the goal works, Cancel and Continue while it is held), so that a goal keeps working while Kestrel's screen is not in front, with listening on or off; hold a working goal as interrupted, with a sentence that names a way on that exists, when Android refuses the service, and post the held goal's notification without the service then; add `mediaPlayback` to the service while Kestrel speaks; end a goal as "stop" does when the owner stopped Kestrel in Android's "Active apps"; and check on kestrel-api37 the goal's starts, the audio with listening off, the lock and call pauses with listening off, "Continue the goal?" after a restart and the owner's stop, with the A71's checks in a last task that waits for the owner.

**Architecture:** The logic is Android-free and runs in JVM tests: `GoalNotices` turns the conversation's view into the notification's words and buttons, `GoalServiceRules` decides from the view's goal and the service's reports when the service starts, what it shows, with which foreground-service types, what Kestrel posts without the service, and when a refused start must hold the goal, `GoalForeground` applies those rules, starts the service through a port and tells the conversation of a refusal (`ConversationEngine.backgroundRefused(command)`, which the conversation's machine turns into an interruption with a hold of its own), and `processEndOf` reads Android's exit record of Kestrel's last process, through the one reader that listening's restore uses too, into plan 7B's `ProcessEnd`, which the conversation's restore takes. The Android parts are thin: `GoalService` follows `GoalForeground.shown` with `startForeground`, `GoalNotification` builds and posts the notification, `GoalActionReceiver` gives its buttons to the conversation, and the app graph wires them beside plan 7D's `ConversationSignals`. Two changes complete plan 7D's screen turn (`ScreenTurns`, the one rule for who acts on the screen): a pause lets go of the round's turn as soon as no step that a gate let through can act any more, at once during a model call, so that the owner's instant commands work during a pause and never beside a step, and the round's turn says when it waits behind the owner's instant command, which the conversation's view carries (`waitsBehindCommand`) and the notification says, because the bar belongs to the command then. Each notification button is an owner's input with the next number of his inputs (`GoalButtons`), as plan 7D's `ScreenTurns` needs. Debug hooks read the service, tap its buttons in SystemUI's shade by their keys, and ask for the audio focus, so that the controller checks Android's own rules for the starts and for background audio outside the instrumentation, which Android exempts from them.

**Tech Stack:** Kotlin 2.4.21 on Android (minSdk 33, target 37) and on the JVM (JDK 21 from Android Studio, target 17), kotlinx.coroutines 1.11.0 with `kotlinx-coroutines-test`, kotlinx.serialization 1.13.0, JUnit 4.13.2, AndroidX Test (runner 1.7.0, ext-junit 1.3.0), Room (the version of plan 7C).

## Global Constraints

- Package and application ID `dev.kestrel`; licence GPL-3.0-or-later. No new library.
- The owner's hard rule (AGENTS.md): no workarounds and no smallest-change fixes; clean code, good architecture and modularity, even when that means a larger change. The owner's criterion of 2026-11-24: "best = best code quality. Don't worry about time." A step that would patch a symptom at the nearest line is a fault of the plan: stop and ask the controller.
- The approved design is `docs\superpowers\specs\2026-11-24-conversations-design.md`, called "the design" below; its decisions D1 to D24 bind this plan, and so do its sections 10.6, 15.2 and R2. This plan is the design's plan 7E (section 16): `GoalService` with `specialUse`, the Android 13 path and `mediaPlayback` while speaking; its notification and buttons; "Continue the goal?" after a restart; the lock and call pauses with listening off; and the start checks of 10.6. The main spec is `docs\superpowers\specs\2026-11-12-kestrel-design.md` (rewritten by plan 7.0: sections 7.2, 13.6, 14.4, 14.6 and 15's goal notification, and spike S9).
- The owner's decisions: `GoalService` runs for every open goal, in every phase, with listening on or off (D19). A stop in Android's "Active apps" (the Task Manager of foreground services) is the owner's stop: the goal ends as "stop" ends it, is not held, its service does not start again, and listening stays off (2026-11-25). Only a button that resumes or widens what Kestrel does asks Android for the owner's unlock (Continue); the held goal's buttons read Cancel first, then Continue, so that a tap meant as Stop lands on Cancel (2026-11-25).
- This plan consumes plans 7A (`docs/superpowers/plans/2026-11-24-conversations-7a-agent.md`), 7B (`docs/superpowers/plans/2026-11-24-conversations-7b-core.md`, with plan 7D's section "What plan 7B changes for this plan" and its restore's `ProcessEnd`, see "The restore's `ProcessEnd` in plan 7B"), 7C (`docs/superpowers/plans/2026-11-24-conversations-7c-archive.md`) and 7D (`docs/superpowers/plans/2026-11-24-conversations-7d-voice.md`, its section "Interfaces for 7E and 7F"). It builds no second engine, store, signal watcher or call backstop: the pauses with listening off come from plan 7D's `ConversationSignals`.
- The safety work of plan 6BF keeps working unchanged: `CheckedLaunch` and `ScreenAction.Launch`, `TaskWitness` and `DebugTaskWitness`, and the touch watcher's `ownerTouches` and signals. This plan changes none of their files.
- Typed input counts as the owner's only from Kestrel's own screen (D8): the notification has no `RemoteInput`, its buttons carry only an action's name and the goal that their notice shows (by the conversation's id and the goal's record, as the broadcast's data), its receiver is not exported and acts only for that goal while the goal's notice offers what the button does (Continue only while it offers Continue, a stopping button while it offers any way to stop), and Continue needs the owner's unlock.
- Never print, log or put into a hook's answer the owner's words, a reply, a question, a record's text, a checklist item's text or the notification's text. Every new type's toString prints only kinds, numbers, counts and lengths (design D25 of sub-project 5).
- Run Gradle only through `scripts\gradle.ps1`, one build at a time; when the controller's brief says that agents run in parallel, take `.superpowers\sdd\gradle.lock` in `H:\Code\kestrel` before each Gradle command as the brief says, and do not run `scripts\gradle.ps1 --stop` beside another Gradle user. Otherwise stop Gradle with `scripts\gradle.ps1 --stop` at the end of each task that builds. `scripts\gradle.ps1` refuses every task that installs, uninstalls or runs tests on a device. Install only through `scripts\install.ps1`, and run the `:app` device tests only through `scripts\connected-test.ps1` (the voice's device tests through `scripts\voice-test.ps1`).
- Device steps run only in Tasks 8 and 9, which are the controller's; the controller gives Task 8 to an agent with a strict brief, whose steps the agent follows exactly (Task 8). On kestrel-api37 each device command starts with the emulator's check, written without the word throw: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if (-not (Test-KestrelAvdAnswer @(& (Get-Adb) -s emulator-5554 emu avd name))) { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }`. If the check fails, or no emulator runs, start it with `scripts\start-emulator.ps1`, check the serial that it prints, and use that serial. Never use the AVD `zoran`, the S23+ or the owner's Xiaomi tablet. The A71 (`R58N66RVD7Z`) is used only in Task 9, within AGENTS.md's limits.
- kestrel-api37 holds the owner's own Google account and Kestrel's Copilot profile: no step uninstalls Kestrel there, clears its data, or signs an account in or out; Task 8 checks before and after that `dumpsys account` counts the same Google accounts and that `scripts\hook.ps1 provider-standin` answers `profile=copilot` with the same `id=` and `credential=present`. Since plan 7C, kestrel-api37's database is at version 3: never install a build without plan 7C there.
- Nothing in this plan calls a provider or runs `--live`. Every device test uses the scripted model or the stand-in round of the hook `voice-task`.
- The JVM tests run on CI's Ubuntu too: no JVM test needs Windows, a device, a font or a network, and no test depends on the machine's time zone.
- The repository uses LF line endings.
- This plan is `.superpowers/sdd/plan-7e.md`; the controller commits it as `docs/superpowers/plans/2026-11-24-conversations-7e-goal-service.md`. A plan is frozen once committed; a later difference goes into `.superpowers/sdd/progress.md`.
- Every commit message ends with the line `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.

Code comments name this plan "plan 7E" and the design's sections as "design 10.6 of sub-project 7"; design numbers without a sub-project in existing comments stay those of sub-project 5.

## Before you start

- This plan starts only after plans 7A, 7B, 7C and 7D are merged into `sp3`. It was written against `sp3` while plan 7B was being carried out, and amended on 2026-11-25 against `sp3` at `3d0a1921`, where plans 7A to 7D are done (see "Amendment of 2026-11-25 (after plan 7D)"). Every old text that a task replaces exists once at `3d0a1921`, or is the text that an earlier task of this plan leaves.
- At the start of Task 1, and again at the starts of Tasks 4, 5 and 6, run the plan checker against the branch's HEAD, in PowerShell 7: `$env:PLANX = 'H:\Code\kestrel-sp3\docs\superpowers\plans\2026-11-24-conversations-7e-goal-service.md'; $env:BASE_REF = (git -C H:\Code\kestrel-sp3 rev-parse HEAD); $env:S3A = 'C:\Users\AleksaLocal\.copilot\session-state\337a39e5-0c45-4e8a-b4a0-4c1d2eef024e\files\no-such-sim'; $env:SIMDIR = 'C:\Users\AleksaLocal\.copilot\session-state\337a39e5-0c45-4e8a-b4a0-4c1d2eef024e\files\no-such-sim'; $env:OUTDIR = 'plan7e-sim'; python 'C:\Users\AleksaLocal\.copilot\session-state\337a39e5-0c45-4e8a-b4a0-4c1d2eef024e\files\plan3x-check.py'`; for Task 4 also set `$env:START_TASK = '4'`, for Task 5 `$env:START_TASK = '5'`, and for Task 6 `$env:START_TASK = '6'`. Expected: an `operations:` line, a `files:` line, a `simulated files written:` line, and no line that starts with `PROBLEM:`. If a `PROBLEM:` line names a text that plans 7A to 7D moved or changed, or a file that one of them created, stop and ask the controller: the controller rebases the plan (it changes the plan's old texts to the texts that the earlier plans left, keeps each change's meaning, and adjusts a new text that depends on them), runs the checker again until it is clean, and only then gives the task.
- At the start of Task 1, check that plans 7B and 7D as merged have what this plan consumes: `Select-String -Path core\conversation\src\main\kotlin\dev\kestrel\conversation\ConversationTexts.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\model\GoalState.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\model\ProcessEnd.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\machine\GoalRules.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\machine\ConversationEvent.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\machine\ConversationMachine.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\engine\ConversationEngine.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\engine\ConversationView.kt, app\src\main\kotlin\dev\kestrel\app\AppGraph.kt, app\src\main\kotlin\dev\kestrel\app\KestrelApplication.kt, app\src\main\kotlin\dev\kestrel\app\conversation\InputIds.kt, app\src\androidTest\kotlin\dev\kestrel\app\agent\HeldModel.kt, app\src\androidTest\kotlin\dev\kestrel\app\agent\AgentTests.kt, app\src\debug\kotlin\dev\kestrel\app\debug\ConversationHooks.kt -Pattern 'fun held\(hold: GoalHold\)|RESTART\(GoalPhase\.INTERRUPTED|fun of\(report: TaskReport\): GoalHold\?|data object Touched : ConversationEvent|ConversationEvent\.Touched -> GoalRules\.touched|fun touched\(\) = post|val quiet: QuietClock = QuietClock\.Off,|private fun followConversation|graph\.conversations\.restore\(\)|object InputIds|class HeldModel|fun turnOff|fun run\(request: String, model: ModelPort\)|"CONVERSATION_CONTROL"|data class OwnerStopped|suspend fun restore\(previous: ProcessEnd'`. Expected: 17 matches, as at `3d0a1921`. Then check that plan 7D as merged has the seams that Tasks 1b, 1c, 5 and 7 change or use: `Select-String -Path core\conversation\src\main\kotlin\dev\kestrel\conversation\goal\GoalEnvironment.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\goal\ScreenTakingGoal.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\goal\GoalRunner.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\goal\RunningGoal.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\engine\ConversationEngine.kt, core\conversation\src\testFixtures\kotlin\dev\kestrel\conversation\testing\FakeScreenTurn.kt, core\agent\src\main\kotlin\dev\kestrel\agent\loop\FrontCheck.kt, app\src\main\kotlin\dev\kestrel\app\listen\ScreenTurns.kt, app\src\main\kotlin\dev\kestrel\app\conversation\RoomRecordArchive.kt, app\src\androidTest\kotlin\dev\kestrel\app\conversation\ConversationTests.kt, app\src\androidTest\kotlin\dev\kestrel\app\agent\AgentTests.kt -Pattern '^    suspend fun take\(after: Long\?\): Boolean$|override suspend fun take\(after: Long\?\): Boolean|override suspend fun takeScreen\(\): Boolean = screen\.take\(goal\.takeAct\(\)\)|^            screen\.take\(goal\.takeAct\(\)\)$|fun takeAct\(\): Long\?|ConversationEffect\.PauseGoal -> owner\.pause\(\)|run\.ports\.screen\.act\(ScreenAction\.Global\(GlobalKind\.BACK\)\)|fun startCommand\(job: Job, since: Long, letGo: \(\) -> Unit\): Boolean|private val deleteHistory: suspend \(\) -> KeyErasure|fun endWork\(\)|ConversationTests\.endWork\(\)'`. Expected: exactly 12 matches, one in each file and two in `ScreenTurns.kt`. Otherwise stop and ask the controller.
- Measure the counts before the first change of Task 1, and note them in the task's report: run `scripts\gradle.ps1 :app:testDebugUnitTest :core:conversation:test :core:agent:test`, then `foreach ($dir in 'app\build\test-results\testDebugUnitTest', 'core\conversation\build\test-results\test', 'core\agent\build\test-results\test') { $n = 0; Get-ChildItem $dir -Filter *.xml | ForEach-Object { $n += [int]([xml](Get-Content $_.FullName -Raw)).testsuite.tests }; "$dir $n" }`, then `"device $(@(Get-ChildItem app\src\androidTest -Recurse -Filter *.kt | Select-String -Pattern '@Test\b').Count)"`, then `scripts\gradle.ps1 --stop`. These are A₀ (`:app` JVM, with `app/src/testDebug`), C₀ (`:core:conversation`), G₀ (`:core:agent`) and AD₀ (`:app`'s device tests). At `3d0a1921` they are A₀ = 659, C₀ = 281, G₀ = 313 and AD₀ = 455 (measured for the amendment of 2026-11-25; plan 7D's counts at its end). Each task gives its counts as "N + k", where N is the count before the task. If a count differs, stop and ask the controller.
- Before each task, check that every text that the task replaces exists, and exists once, in its file as the earlier tasks left it; and run `git status --short`. The worktree may hold other agents' uncommitted files. If a text is missing, or a file that the task changes shows changes that are not the task's own, stop and ask the controller.
- Tasks 1 to 7 (with 1b and 1c) use no device. Task 8 is the controller's steps on kestrel-api37, which the controller gives to an agent with a strict brief. Task 9 waits for the owner and runs on the A71.

## Not in this plan

- The conversation screen, `TextChannel`, the question cards with "Reply…", the typed path and its speech with listening off (plan 7F). This plan gives plan 7F `GoalForeground.whileSpeaking`, which adds `mediaPlayback` around a speech, and the check of the typed start moves to plan 7F, which creates `ConversationActivity` (see "Decisions for the controller", item 2).
- The way on after a refused start that Kestrel's own screen gives. In this plan, Kestrel says "Tap Continue in Kestrel's notification." after a refusal, because that notification exists then (Kestrel posts the held goal's notification without the service; see "Changes to the design"), and "Kestrel, continue" works by voice as for any held goal. Plan 7F changes `ConversationTexts.CONTINUE_IN_NOTIFICATION` once the conversation screen's Continue exists, to design 10.6's "Open Kestrel and tap Continue.", and its test with it.
- `scripts\hook.ps1`'s entries for this plan's hooks `goal-service-state` and `goal-control`, `:tools:eval`'s turns and its tasks `interrupt-continue` and `resume-after-death` (plan 7G). Until then the controller's steps send the hooks with `am broadcast`, as plan 7D's Task 8 does. Design 15.2's check that a yes after a restart "continues from the checklist with a fresh observation" is plan 7G's `resume-after-death`, whose scripted model keeps a checklist: Task 8 here uses the stand-in round, which has none, and checks the restart's start, "Continue the goal?" and its Continue.
- The main spec's update (plan 7.0). Tasks 8 and 9 write their findings for spike S9 into `.superpowers\sdd\progress.md`, and decision 11 routes them into the main spec.

## Changes to the design that this plan makes

These are this plan's readings of the design where it leaves a choice open. The controller confirms them with the owner, or the plan changes before Task 1.

- **A refusal is an interruption of its own.** When Android refuses the goal service, a goal whose round works (it acts, asks or has handed a step over) cannot go on in the background, so the conversation interrupts the round as a touch does, with a cause of its own: `Interruption.BACKGROUND_REFUSED` in `:core:agent` and `GoalHold.BACKGROUND_REFUSED` in `:core:conversation`, which holds the goal as `INTERRUPTED` for 30 minutes (D17) and resumes it in a new round (design 7.6). The refusal names the command whose start Android refused, so that a late refusal never touches a newer goal. Kestrel then says the round's report with the reason and how to go on around what it did: "Android didn't let me start working in the background. I opened Viber. Tap Continue in Kestrel's notification." A goal that is held already keeps its hold, and Kestrel says only "Android didn't let me start working in the background. Tap Continue in Kestrel's notification." Design 10.6's last words ("Open Kestrel and tap Continue.") name the conversation screen's Continue, which plan 7F brings; until then the sentence names the notification's Continue, which exists (see "Not in this plan").
- **After a refusal, Kestrel posts the held goal's notification without the service.** A refused service shows nothing, so Kestrel posts the held goal's notification itself, as an ordinary notification with Cancel and Continue. Its Continue is the owner's action on a notification, from which Android lets the service start (design 10.6's "the notification's Continue starts it from the notification's broadcast"). The posted notification goes when the service starts again or the goal ends.
- **Every start or resume of the goal's work starts the service.** The service starts when a goal opens, also when a restart restores a held goal, and again whenever the goal's work begins again after a hold, also while the service still runs. So the typed start comes from `ConversationActivity` in front, the spoken start from the voice path while `ListeningService` runs, and the resume from the notification's Continue from its broadcast, as design 10.6 names them. A new start from the owner's own action is how a running service may gain Android's while-in-use standing, which Android 17 asks of an app that plays audio in the background (main spec B5). A start that Android refuses for a service that still runs counts, and changes nothing else. A goal that is ending (a stop, or the owner's stop at a restart) starts nothing.
- **Background audio on Android 17 depends on while-in-use, not on the type.** Android 17 lets an app play audio in the background only from a foreground service that is not a short service and, for an app that targets API 37, has while-in-use (main spec B5). Design 10.6 chose `mediaPlayback` as the type that the service has while it speaks, so the service adds it (`GoalForeground.whileSpeaking`); spike S9 records, outside the instrumentation, whether Android gives the audio focus with that type, with `specialUse` alone, and without a service (Task 8, Step 7).
- **The service stays for the final reply.** The service stops when the goal has ended and its final reply has been spoken or shown (the view's quiet clock is no longer held for `REPLYING`), so that a typed goal's spoken report keeps its service. Meanwhile it shows "Kestrel finished the goal" without buttons. When the goal ends while the phone is locked or a call lasts, the report waits for the owner's return (design 6.4), and the notification keeps saying "Kestrel finished the goal" until then.
- **The notification's buttons keep the conversation's channel, and count only for the goal that their notice showed.** A tap on Kestrel's own notification is neither speech nor typing and carries no words, so its input takes the channel of the owner's latest input (`ConversationView.replyChannel`), and the replies stay as they were (design 11). Each button's broadcast names the goal that its notice shows, by the conversation and the goal's record, and a tap for another goal does nothing. A Continue counts only while the goal's notice offers Continue, so a stale Continue does nothing; a button that stops counts while the notice offers any way to stop, because the owner's tap on Stop can arrive after his pull of the shade held the goal. A tap that starts Kestrel's process waits for the conversation's restore and is judged against the restored goal.
- **What the notification says, and the order of its buttons.** While the goal works: "Kestrel is working", the current checklist item and the checklist's progress bar, Stop and Open. While it is held: "Continue the goal?", how to go on for its hold (`ConversationTexts.held`, the sentence that Kestrel says, so "Leave Kestrel's screen, then tap Continue or say 'Kestrel, continue'." for a goal held at Kestrel's own screen), Cancel and then Continue: the first button always ends the goal, also when a touch held it while the owner reached for Stop. Continue asks Android for the owner's unlock (`setAuthenticationRequired`), so that nobody resumes a goal from the lock screen; Stop and Cancel need none. The conversation's view gains `hold` and `goalEnding`, so that the notification can name the hold and the service does not start for a goal that is ending. The lock screen shows only "Kestrel is working on a task". When the owner swipes the notification away, it comes back with the next change of the goal, as the listening notification comes back with the next change of mode (spec 8.6).
- **Open.** The notification's tap and its Open open Kestrel's launcher activity through `ListeningService.openKestrel`, which plan 7F points at `ConversationActivity`. While the goal acts, the owner's pull of the shade is a touch for the touch watcher (a device test checks it), and Kestrel's own screen in front holds the goal at the round's gate (design 10.2), so the tap interrupts the goal without code of its own.
- **The owner's stop in "Active apps".** Android's Task Manager stops the whole app without a callback, and its documentation names `ApplicationExitInfo.REASON_USER_REQUESTED` as the record to look for at the next start; Android gives the same reason for a force stop in Settings and for removing the app from the recent apps, which are the owner's stops too, and no public sub-reason tells them apart. Before Android 14, an update of the app gave that reason as well; from Android 14 an update gives `REASON_PACKAGE_UPDATED`. Kestrel reads the record once, with the reader that listening's restore already uses, and gives it one reading for both restores (the controller's decision of 2026-11-25): a `REASON_USER_REQUESTED` is the owner's stop, so listening stays off and a restored goal ends, except on Android 13 within a minute of Kestrel's last install or update, before or after it, where it cannot be told from the update: listening stays off as before, and the goal is held and asks "Continue the goal?", as for any other end. The restore (plan 7B) ends the goal as "stop" does only when that stop came after the conversation's snapshot was saved.
- **Android 13.** The service starts with no type there (`startForeground(id, notification, 0)`), also while Kestrel speaks, because Android 13 neither checks types nor hardens background audio. The platform's `startForeground(int, Notification, int)` exists from API 29, so the plan calls it directly instead of `ServiceCompat`.
- **The whole first search's speed.** Plan 7C measures the archive's share of the first search; the whole search is measured here (design 4.3 and R3). `ConversationSearchSpeedTest` (Task 7) writes 30 heavy days with R3's fixture in one instrumentation run and times their last close, times the first search once the index has been read in a second run, and a search right after a start in a third, each a new process; Task 9 runs it on the A71, where a search over 1 s at 30 days goes to the owner.
- **The checks of Android's rules run outside the instrumentation.** Android lets an instrumentation started from the shell start foreground services from the background, and the instrumented process has a standing that Android's audio hardening may not apply to, so the device tests check the plumbing, and the controller's steps check the starts and the audio themselves with the hooks: the spoken start, the notification's Continue tapped in SystemUI's shade, the start after a restart, the audio focus with and without the service, and the owner's stop.
- **The notification says when the goal waits behind the owner's command.** Plan 7D's round waits until the owner's instant command has ended when it goes on by itself or for an older input, and the bar shows the command's own text meanwhile (`OwnedBar`), so nothing told the owner why the goal did not move. The round's screen turn now says when it starts to wait behind such a command, the conversation's view carries it (`ConversationView.waitsBehindCommand`), and the working goal's notification says "Waiting for your command to finish" in place of the current checklist item, with its progress and its buttons (Tasks 1c and 2). Plan 7F's conversation screen can show it from the same field.
- **A pause lets go of the screen's turn once no step can act.** Design 6.5 has the round let go of the turn as each wait for the owner starts; a lock or a call starts its wait when the conversation pauses the goal. The round lets go then, unless a step that a gate let through may still act on the screen: from the run's take of the turn until its next model call or its next wait for the owner, a pause's release waits for that span to end, so no instant command ever starts beside a step; during a model call it lets go at once (`ScreenTakingGoal`, the one rule; the controller's decision of 2026-11-25). A resume before the span ends keeps the turn. The observation's Back on an app that crashed passes the pause gate, as every action does (Task 1b; plan 7D's re-review, R5-1).
- **Each button of the notification is the owner's input of its moment.** A tap on Stop, Continue or Cancel takes the next number of the owner's inputs (`InputIds.next`) when it arrives, as a spoken or typed input does, so that a Continue that resumes the goal has an instant command that he started before the tap let go of the screen, and waits for one that he started after it (plan 7D's `ScreenTurns`; Tasks 2 and 5).


## Amendment of 2026-11-25 (after plan 7D)

Plans 7A to 7D are done and on `main` and `sp3` at `3d0a1921`; plan 7D changed much during its reviews (its "Changes to the design that this plan makes" and "Interfaces for 7E and 7F" as built). This amendment makes the plan apply to that code and follow plan 7D as built, and takes the two items that plan 7D's last re-review left to this plan. Each change:

- **The base.** "Before you start" names `3d0a1921` as the base, checks plan 7D's seams with a second `Select-String` (exactly 12 matches) besides the first (17 matches), and gives the measured base counts: A₀ = 659, C₀ = 281, G₀ = 313, AD₀ = 455.
- **Task 1, rebased.** Plan 7D added `ConversationView.ownRequest` after `keptForOwner` (the field, the KDoc, `toString` and `ConversationEngine.publish`), so Task 1's four old texts there end with `ownRequest`, and `hold` and `goalEnding` follow it. Its meaning is unchanged. Task 1 is no longer the plan's one change to the core modules: Tasks 1b and 1c change them too.
- **Task 1b, new: a pause lets go of the screen's turn once no step can act (plan 7D's re-review, round 5, R5-1, with the controller's decision of 2026-11-25).** `ScreenTakingGoal`, through which every turn of a round now goes (the round's first and last too), is the one rule: from the run's take of the turn (`takeScreen`) until its next model call (`stepStarted`, which the run calls right before every model call) or its next wait for the owner (`releaseScreen`, as `TaskRun.waitForOwner` starts), a step that a gate let through may act, and a pause (`paused()`) that comes then lets go of the turn only when that span ends; during a model call it lets go at once, so that a lock or a call that meets the round inside a model call no longer leaves the owner's instant commands refused with the busy text. A resume (`resumed()`) before the span ends keeps the turn. The engine calls `GoalRunner.paused()` with every `PauseGoal` and `GoalRunner.resumed()` with every `ResumePausedGoal`. `:core:agent`'s ports do not change: the span's ends are the goal port's existing `stepStarted` and `releaseScreen`. `FrontCheck`'s Back on an app that crashed passes `TaskRun.gate()` first, as every action passes the pause gate (D2), and a gate that held the run uses up none of its checks. Tests: `ScreenTakingGoalTest` (new, 2), `ScreenTurnTest` + 2 (`aCrashedAppInTheFirstObservationIsClosedOnlyAfterTheGate`, `pausesAtACrashedAppsBackUseUpNoneOfTheObservationsChecks`), and `ConversationEngineTest` + 2 (`aPauseDuringAStepLetsGoOfTheTurnOnceTheStepHasEndedAndTheRoundTakesItAgainBeforeItActs`, and `aPauseDuringAModelCallLetsGoOfTheTurnAtOnce`, with the fixture's `FakeGoalEnvironment.beforeCall`); on the device, `GoalServiceTest`'s lock test checks `screenTurns.roundHolds()` after a pause during a model call (Task 5).
- **Task 1c, new: the view says when the round waits behind the owner's command (plan 7D's re-review, round 4's nit).** `ScreenTurn.take` gets `waiting: () -> Unit`, which `ScreenTurns` calls as a round starts to wait for an instant command that goes on (not for one that it has cancelled); `ScreenTakingGoal` (Task 1b) tells `GoalListener.waitsBehindCommand`, and the engine carries it into `ConversationView.waitsBehindCommand`, as it carries the round's step. The name says "behind", because `HoldReason.WAITING_FOR_COMMAND` and Listening's `awaitingCommand` already name the wait for a command after "Yes?". Tests: `ScreenTurnsTest` + 1 (the file is replaced whole, because every `take` call gains its lambda), `GoalRunnerTest` + 1, `ConversationEngineTest` + 1; `ScreenTakingGoalTest`'s listener, `ListeningTest`'s three `take` calls and `FakeScreenTurn` follow the new signature, and `FakeScreenTurn.command` lets a JVM test make a take wait behind a command.
- **Task 2: the wait behind the command, and each button as the owner's input.** The working goal's notice says `GoalTexts.BEHIND_COMMAND` ("Waiting for your command to finish") in place of the current item while `waitsBehindCommand` is set, because the bar belongs to the command then. `GoalButtons.input` turns a tap into the owner's input, with the next `InputIds` number taken at the tap, so that plan 7D's `ScreenTurns` treats the notification's Continue as the owner's newest act (a command that he started before the tap lets go of the screen; one that he started after it is waited for); it counts only for the goal that the notice showed (`NoticeGoal`), Continue only while the goal's notice offers Continue, and a stopping button while it offers any way to stop. `GoalButtons.press` decides it after `ConversationEngine.awaitRestored()`, at most `RESTORE_WAIT_MILLIS` (5 s). `GoalNoticesTest` has 10 tests (+ 5 over the plan before the amendment).
- **Task 5: the receiver and the device tests.** `GoalActionReceiver` runs `GoalButtons.press` under `goAsync()` on the app graph's scope, with the goal that the broadcast's data names; `GoalNotification.intentOf` puts each goal's key into its buttons' data, so each goal has PendingIntents of its own. `AppGraph.goalStartRefusals` is a debug seam for Task 8's check of a tap that starts Kestrel's process. `GoalServiceTest` and `GoalServiceHooksTest` end their goals through `AgentTests.stop()`, which stops whatever "stop" stops through plan 7D's `ConversationTests.endWork()`, instead of an input of their own; their starts close the conversation through `AgentTests.run` (`ConversationTests.close()`). The lock test checks that the paused round let go of the screen's turn (Task 1b), and `anOlderGoalsContinueNeverResumesTheNextGoal` that an older goal's Continue never resumes a newer one.
- **Task 7: plan 7D's `RoomRecordArchive`.** Plan 7D's Task 7 gave `RoomRecordArchive` a second parameter, the archive's "delete history"; the measurement passes the test table's own `deleteAll()`, as `RoomRecordArchiveTest` does.
- **Task 8: a delegated agent with a strict brief, and plan 7D's gate's lessons.** Task 8 names its rules: only kestrel-api37 and only the steps' commands; a data check before Step 2, after each of Steps 2 to 10 (8a too) and at the end, by machine (`Get-OwnerDataState` in Step 1, `Compare-OwnerData` after); `StandIn` before every `heard` and Continue; stop and report at the first failure, with no retry or cold boot but the allowances that a step names (plan 7D's known flaky `SystemUiFilterTest`, run alone once; `Listen`'s second toggle); the Gradle and emulator locks; a report without words; the ledger lines in the report. The step helpers (`Hook`, `Voice`, `Heard`, `Mode`, `Listen`, `Quiet`, `StandIn`, `Allowed`, `Restarted`, `Crashes`) are written once and pasted before each step's text. Step 3 gives the numbers against plan 7D's gate run 2, Steps 7 and 8 describe plan 7D's stand-in round as built (it ends the goal with its reply), and Step 8a checks a tap on the posted notification that starts Kestrel's process. Every PowerShell command of Tasks 8 and 9 parses without an error in PowerShell 7 and in Windows PowerShell 5.1 (`Parser.ParseInput`, with the step helpers before each step's text).
- **The counts.** `:app` JVM A₀ + 27 (686), device AD₀ + 12 (467), `:core:conversation` C₀ + 9 (290), `:core:agent` G₀ + 2 (315); every task's own counts follow. As built, the review fixes add four tests to `:app` (690, Task 6's) and two to `:core:conversation` (292, Tasks 1b's and 3's); "Counts at the end of plan 7E" names them.
- **The plan review of 2026-11-25 (Fix first), fixed at the root.**
  - I1: `ConversationEngineTest.aPauseDuringAModelCallLetsGoOfTheTurnAtOnce` (Task 1b) holds the round inside its first model call (the fixture's new `FakeGoalEnvironment.beforeCall`), pauses it, and checks at once that the turn is let go; it fails without the engine's call of `GoalRunner.paused()`. The earlier engine test, a pause during Kestrel's speech, now checks the other half: the turn stays until the step has ended.
  - I2: a button that stops (Stop or Cancel, `GoalAction.stops`) counts while the goal's notice offers any way to stop, so the owner's tap on Stop that arrives after his pull of the shade held the goal still stops it; Continue counts only while the notice offers Continue (`GoalNoticesTest.aStoppingButtonCountsWhileTheGoalsNoticeOffersAnyWayToStop`).
  - I3: every step of Task 8 that sends `heard` or a Continue first sets plan 7D's stand-in round through `StandIn`, which checks the hook's answer and stops the command when it is not set, so no goal's round can reach the owner's Copilot profile; Step 7 sets it again before its Continue. Task 9 does the same on the A71.
  - M1: Task 8's data check is `Get-OwnerDataState` (Step 1, saved with `Export-Clixml` in the controller's git-ignored `.superpowers\sdd\`) and `Compare-OwnerData` after every step, which compare by machine and read Kestrel after `stop-app` too; any difference stops the task.
  - M2: each button's broadcast names the goal that its notice shows, by the conversation's id and the goal's record (`NoticeGoal`, from the new `ConversationView.goalRecord`), and the receiver ignores a tap for another goal (`GoalNoticesTest.aButtonCountsOnlyForTheGoalThatItsNoticeShowed`). Not the command number, which the controller named as an example: a goal's command number belongs to one process (it is not stored) and changes at every resume, so it would drop a tap after a restart and a Stop after a resume; the record names the goal across both.
  - M3: the receiver keeps the broadcast alive (`goAsync`) and decides after `ConversationEngine.awaitRestored()` (new, Task 1) against the restored goal's notice (`GoalButtons.press`), so a tap on the held goal's notification that Kestrel posted without the service, which starts Kestrel's process, counts for the restored goal (same conversation and record) instead of being lost. Tests: `GoalNoticesTest.aTapThatStartsKestrelsProcessWaitsForTheRestoreAndCountsForTheRestoredGoal` and `ConversationEngineTest.awaitRestoredReturnsOnceTheRestoreHasRunWithTheRestoredGoalInTheView`. The receiver now judges every tap against the conversation's view, the one source of the notice, instead of the coordinator's copy.
  - M4: "Interfaces for 7F and 7G" names only public API of `:core:conversation` and `:core:agent` (`GoalRunner.paused` and `resumed`, `ScreenTurn.take`, `GoalListener.waitsBehindCommand`, `ConversationEngine.awaitRestored`, the view's fields) and `:app`'s internal API, which plans 7F and 7G, in `:app`, may use; it says that `ScreenTakingGoal`, `FrontCheck` and `GoalRules` are internal and not for them.
  - M5: a gate that holds the run at the crashed app's Back is no check of `FrontCheck`'s own: the screen is read again and every check is kept (`ScreenTurnTest.pausesAtACrashedAppsBackUseUpNoneOfTheObservationsChecks`, which fails with the gated Back of the earlier amendment).
  - Nits: Task 8, Step 9 and Task 9, Step 7 stop before the stop when the accessibility setting reads `null` or nothing, so they never write the text `null` back; Task 1b's count line says that its first 3 come from Task 1.
- **The second plan review of 2026-11-25 (Fix first), fixed at the root.**
  - R2-I1: each goal's buttons have PendingIntents of their own: `GoalNotification.intentOf` puts the goal's key (`NoticeGoal.key`, `<conversation>/<record>`) into the broadcast's data, which Android compares when it matches PendingIntents (extras it ignores), and drops `FLAG_UPDATE_CURRENT`; `goalOf` reads the data. Tests: `GoalNoticesTest.eachGoalHasAKeyOfItsOwnThatNamesItAgain` and the device test `GoalServiceTest.anOlderGoalsContinueNeverResumesTheNextGoal`, which keeps goal A's Continue, ends A, holds a goal B and sends A's Continue; with the shared PendingIntent it resumes B and fails. Task 8, Step 3 runs it.
  - R2-I2: Task 8's data check and Step 1 had `\"` around the AVD check, which PowerShell runs as a command; every PowerShell command of Tasks 8 and 9 is now written to parse cleanly in PowerShell 7 and Windows PowerShell 5.1, checked with `Parser.ParseInput` (the step helpers before each step's text), which also found and fixed two expandable strings that the parser rejects (a regex with `)` inside `$(...)`, now computed before the string, and `$mode:` read as a drive-qualified variable).
  - R2-I3: Task 8, Step 8 and Task 9, Step 6 turn listening off (`Quiet`, checked) before the crash, read the mode first after the restart and stop unless it is `off`, and set the stand-in again at once (`StandIn 120`), before anything else; the press after it still comes after `StandIn 5`.
  - R2-M1: `GoalButtons.press` waits at most `RESTORE_WAIT_MILLIS` (5 s) and drops a tap after a slower restore, with a report of its kind (`aTapWhoseRestoreDoesNotEndInTimeIsDroppedAndReportedByItsKind`); Task 8's new Step 8a checks on the device that a tap on the posted notification that reaches or starts a new process counts for the restored goal: a start refused through the new debug hook `goal-control do=refuse` (`AppGraph.goalStartRefusals`) posts the held goal's notification, the hook `goal-control do=send-later` hands its own Cancel to `AlarmManager` (round 3 of this review replaced the screen taps of rounds 1 and 2, see below), and `run-as dev.kestrel kill -9` ends the process before the alarm sends it.
  - R2-M2: Task 1, Step 7 expects C₀ + 3.
  - R2-M3: Task 9 has Task 8's safeguards for the A71: a rules block, the owner's data recorded right after the install (`Get-OwnerDataState`, its own state file) and compared after every step (`Compare-OwnerData`), the stand-in and listening rules, stop at the first failure, and Step 8 (put the A71 back) run whatever ended the task.
  - R2-M4: the step helpers' `Listen` reads the mode after its toggle, toggles a second time only while it is still off, and stops unless it says `listening`; `Quiet` checks `off`; Task 8, Step 10 and Task 9, Step 8 check the mode that they put back. The prose "run the command again once" is gone.
  - R2-M5: the Global Constraints, this section's entries, decision 15 and the self-review say what the code does.
  - Nits: Task 9, Step 7 writes back the accessibility setting as it found it, which it prints and which must be Step 2's; the self-review names its reviews apart; Task 8, Step 6 and Task 9, Step 4 stop the measurement's loop at the first exit code other than 0.
- **Round 2 of the second plan review (Fix first), fixed at the root.**
  - R3-I1 and R3-M1: Task 8, Step 8a no longer reads the shade with `uiautomator dump`, whose query matched nothing and whose obvious repair could have chosen another app's button, and which unbinds Kestrel's accessibility service while it runs. Round 2 had Kestrel's own accessibility service find the button's bounds (`goal-control do=locate`) for an `input tap`; round 3 replaced that (R4-M1, below).
  - R3-M2: `StandIn` comes right after Kestrel comes to the front and before `Listen`, in Task 8, Steps 7 to 9 (8a too) and Task 9, Steps 5 to 7, so no moment exists in which Kestrel listens without the stand-in; the rules of both tasks say so.
  - Nit: Task 9, Step 8 stops when the listening mode that it put back differs from Step 2's, as Task 8, Step 10 does.
- **Round 3 of the second plan review (R4-M1), fixed at the root.** Step 8a taps nothing on the screen any more: the bounds of round 2 were read up to a second before the tap, and a row that another app's notification moved meanwhile could have turned the tap into one on that app's notification, on the phone that holds the owner's account. The debug hook `goal-control do=locate` (and `NoticeButtons.bounds` with its test) gives way to `goal-control do=send-later --es action cancel` (Task 6): it takes the PendingIntent of the button on Kestrel's own posted notification from `NotificationManager.getActiveNotifications()`, the very intent that the owner's tap sends (never built again), and hands it to `AlarmManager.setExact`, which sends it 5 s later (Android sends no app's alarm sooner); it answers `send=scheduled`, or `send=none`, which stops the step. The step reads the process's ID before the hook and ends the process right after its answer with `run-as dev.kestrel kill -9`, which keeps the alarm; the restored goal must then end with `dropped taps: 0`. The exact alarm needs `USE_EXACT_ALARM`, which the debug manifest declares: Android grants it at the install from API 33 (Kestrel's minimum), so no setting of the phone changes, and release builds do not have it. The choice of intent is pure (`NoticeIntents.idOf` and `intentOf`) and tested on the JVM (`NoticeIntentsTest`: the button on the notification that shows it now, never another of Kestrel's notifications with the same label). Counts stay at A₀ + 27 (`NoticeButtonsTest` 2, `NoticeIntentsTest` 1).
- **Round 4 of the second plan review (R5-M1), fixed at the root.** After `kill -9` Android binds Kestrel's accessibility service (and its notification listener) again within a second or two, long before the alarm's 5 s, so the Cancel reached a process that had already restored, and Step 8a did not check M3's case. Step 8a now reads the accessibility setting first (it stops when the setting is empty, `null` or lacks Kestrel), writes it without Kestrel's service and takes Kestrel's notification access away when Kestrel had it, checks that both took and that the service is unbound, and only then schedules the Cancel and kills the process. It waits for the new process by `pidof` alone (no broadcast that could start it) and checks in the log that Android started it for the button's broadcast (`started by the button: True`). A `try`/`finally` writes the setting back exactly as read and gives the notification access back on every path, including every stop, checks that both took and that the accessibility service is bound again, and prints only whether each matches. The decision 12 and the ledger line say what the step checks.
- **The rest.** "Changes to the design" gains the wait behind the owner's command, the pause's release and the buttons as the owner's inputs; "Interfaces for 7F and 7G" gains `waitsBehindCommand`, `goalRecord`, `awaitRestored`, `ScreenTurn.take`'s `waiting`, `GoalListener.waitsBehindCommand`, `GoalRunner.paused` and `resumed`, `NoticeGoal`, `GoalButtons`, `GoalTexts.BEHIND_COMMAND` and the hook's `do=refuse` and `do=send-later` (and names `ScreenTakingGoal`, `FrontCheck` and `GoalRules` only as internal); "Decisions for the controller" gains items 13 to 15. The checker is clean from Task 1 and from Tasks 4, 5 and 6.
- **Task 6's review fix (616f5af3), in Tasks 8 and 9.** `goal-control do=tap` closes the shade however the press ends and says so (`tapped=<action> shade=closed`; an error ends with `; shade=closed|open`), so every press's Expected says `shade=closed`, and Task 9 collapses the shade itself only after an answer of `goal-control do=tap` that says `shade=open` or has no `shade=` (the hook's timeout). The new `goal-control do=refuse-off` takes back an armed refusal (`AppGraph.goalStartRefusal`), and `goal-service-state` shows `refuse=armed|none` after `refusals=`: Step 8a's `finally` sends `refuse-off` and checks `refuse=none`, so it prints five lines. `goal-service-state` ends with `focusRequests=<n> focusAnswered=<n>`: the new step helper `Focus` (Tasks 8 and 9) sends each `speak` and `focus` and waits up to 15 s for its own request's counts, so an older result is never read as this one's. "Interfaces for 7F and 7G" and the counts follow (`:app` JVM A₀ + 31, 690).
- **Task 7's review fix (6cf11754), in Tasks 8 and 9.** `ConversationSearchSpeedTest` deletes its test database and key after every run that fails, and its new mode `kestrelSearchSpeed=clean` only deletes them, for a run that was killed. Task 8, Step 6 and Task 9, Step 4 run `clean` in a `finally` after the loop, however the loop ends, and expect `exit clean: 0`. The device count does not change.

## Amendment of 2026-11-26 (the device-found fixes of Task 8, `dab1e6c2..ecf2a999`)

Task 8's Step 3 failed on kestrel-api37 at Task 7's commit `9cae294b`, and the investigations that followed fixed the causes at their roots in 14 commits, from `dab1e6c2` to `ecf2a999`, now on `sp3`. This amendment makes Tasks 8 and 9, the counts and the interfaces follow that code. It changes only the plan's text; nothing was measured on a device for it. Each fix, with its commits:

- **The goal's buttons in the shade without any expansion (design 10.6).** On Android 17, Android put Kestrel's listening and goal notifications in one collapsed group, where the goal's row showed one line and no buttons, so `goal-control do=tap` failed in `GoalServiceHooksTest`. `dab1e6c2`: the hook expands the collapsed group first and then the goal's own row (`NoticeButtons.collapsedRow`, outermost row first). `e6a2dac6`: the goal service's notification is colorized in Kestrel's colour (`R.color.kestrel`, `#FF9C4A1A`, now a resource that the launcher icon's background uses too), so SystemUI shows it in its own section, first and expanded, and never groups it; new device test `GoalServiceTest.withListeningOnTheShadeShowsTheHeldGoalsCancelAndContinueWithoutExpandingAnything`. `7a8d41d7`: the goal service's notification, the held goal's posted one and listening's notification are each an app group of their own without a summary (`NotificationGroups.alone`), so Android does not group them automatically on API 33 to 37; design 10.6 of sub-project 7 says what holds on which API version, that the held goal's posted notification is not colorized (Android colorizes only a foreground service's), and that the A71's run checks Android 13 (decision 16).
- **The hook's shade rules.** `7f57aa3f`: `goal-control do=tap` decides from the screen whether the shade closed (it is open while one of SystemUI's windows is the active one), not from Android's answer to the dismiss, which Android refuses once SystemUI has closed the shade itself. `fffd1c8a`: `NoticeButtons` expands only SystemUI's notification rows (never another container), and tells the shade from the lock screen and the bouncer (while the phone is locked, the active SystemUI window is the shade only if it shows Quick Settings' tiles). `87212a97`: the answer is fail-safe: on a locked phone whose open shade showed no known tile, as on the A71 until its tile IDs are in `systemui-parts.json`, the hook answers `shade=open`, and the caller collapses a shade that may be closed already. The answer's form (`tapped=<action> shade=closed|open`) is unchanged, so no Expected of Task 8 changes; Task 9's rule for `shade=open` says that it also means "could not tell".
- **`ScreenServiceRule`'s wait (device tests).** `c9c6b621`: after it turns Kestrel's accessibility service off, the rule waits until Android's accessibility manager lists the service neither as enabled nor as binding, and only then turns it on, because a service that the manager still bound never connected again and the rule timed out after 10 s. `a551a49e`: the off and the on are steps of their own (`ManagedService` in `app\src\debug` reads the manager's state), and a service that does not connect while the manager still binds it is turned off and on once more, with the reason in the log; any other stall fails with the manager's state.
- **kestrel-api37 without a window.** `ClipboardHookTest` failed now and then because the emulator's window links Android's clipboard to Windows' clipboard and sent older clips back after Kestrel's restore. `d98dc55f`: `scripts\start-emulator.ps1` starts kestrel-api37 with `-no-window`, and with `-Window` for a step in which the owner works in its window, and warns when a running emulator runs otherwise than asked. `c4127df2`: `scripts\a71-agent.ps1` gives kestrel-api37 its window only for the owner's items there (`Restart-KestrelEmulator` in `scripts\lib\Kestrel.ps1`, with the owner's data compared around each restart), the window type comes from the console (`Get-EmulatorWindowType`), and the dry runs put the host microphone back as it started. `c114bbb9`: a failed restart stops the sitting with both errors, the speech step puts the host microphone back, and every check of the emulator's name requires the exact name (`Test-KestrelAvdAnswer`). `560a88ca`: the texts say what a stop after changed owner data does, and an unreadable state during a restart stops the sitting. README and AGENTS.md say how kestrel-api37 starts. Task 8 needs no window: Step 1 now checks `window: headless` and that HEAD contains `ecf2a999`, and every name check of Task 8 and of the Global Constraints uses `Test-KestrelAvdAnswer`. Task 9's A71 part is unaffected.
- **The microphone's stop and the refusal's retry.** In an emulated call, `AudioRecord.stop()` did not wake the microphone thread's blocking read, and the stop waited for that thread for ever, which hung listening (an ANR of the debug hook's broadcast in `scripts\listen-check.ps1`). `9fb66b8b`: `MicrophoneThread` (`:core:voice`) is the one rule for every microphone's thread (`MicCapture` and the debug `ClipMicrophone`); it reads without blocking (`RecordFrames`, `READ_NON_BLOCKING`, which keeps an incomplete frame's samples), and a stop waits at most 2 s and always releases the recording. `77f901a6`: never two microphone threads: a thread that a stop did not see end lingers until its callback returns, a start meanwhile is refused, and the pipeline's pause after the stop runs as that thread's last act; every step of the release runs also when an earlier one fails. `ecf2a999`: a refused start throws `MicrophoneBusyException`, which `Listening.follow` hands to its existing restart rule (one more try after 1 s, then off with `MICROPHONE_FAILED`), and a second stop or a second failure says what happened.
- **Spec 8.1 of the main spec.** `9fb66b8b` and `77f901a6` add to section 8.1 that the microphone's thread reads without blocking, that a stop waits at most 2 s and releases every part of the recording, and that a lingering thread refuses a start until it has ended.
- **Task 8's expected results.** Step 3: 372 run, 257 passed and 115 skipped (258 and 114 when the call test runs), 13 more than plan 7D's gate, with `GoalServiceTest`'s 9 that need no permission and `ClipboardHookTest`; Step 5: all 10 tests of `GoalServiceTest`; Task 8 runs AD₀ + 13 device tests. Tasks 8 and 9 install `ecf2a999` or a later commit.
- **The counts.** `:app` JVM 698 (A₀ + 39: the fixes add `NoticeButtonsTest` + 4, `ManagedServiceTest` 2 and `ListeningTest` + 2); `:app` device tests 468 (AD₀ + 13), counted in the source; `:core:conversation` 292 and `:core:agent` 315, measured again at `ecf2a999` with Gradle and unchanged; `:core:voice` 179 (171 at `9cae294b`; `MicrophoneThreadTest` 6, `RecordFramesTest` 2), which the plan names now as a note, because its own tasks do not change that module.
- **The rest.** "Interfaces for 7F and 7G" gains the hook's shade rules and a section for the fixes; "Decisions for the controller" gains item 16 (design 10.6's row on the A71); the self-review's spec coverage names the new device test and spec 8.1. Every PowerShell command of Tasks 8 and 9 still parses without an error in PowerShell 7 and in Windows PowerShell 5.1 (`Parser.ParseInput`, with the step helpers before each step's text).

## Amendment of 2026-11-26 (decision 16: the hook says what it had to expand, `d3f679e6`)

Decision 16 is settled: the controller chose a hook answer that says whether the press needed an expansion. `d3f679e6` makes `goal-control do=tap` answer `tapped=<action> expanded=none|row|group|group,row shade=closed|open`: the collapsed rows that the hook asked to expand before its press, in their order, each once (`group` for the group that Android put the notification in, `row` for the notification's own row). The field sits between the action and the shade, so the answer still starts with `tapped=` and ends with `shade=`; the hook's errors keep their form (`…; shade=closed|open`). The search of the shade moved from `GoalServiceHooks` into `NoticeButtons.find`, and `NoticeButtons.collapsedRow` now names which expansion a row is, so that JVM tests cover every case. This amendment changes Tasks 8 and 9 and the plan's other texts to that answer; nothing was measured on a device for it.

- **Task 8.** Tasks 8 and 9 install `d3f679e6` or a later commit, and Task 8's Step 1 and Task 9's Step 2 check `contains d3f679e6`. Every press of Steps 7 and 8 (the refusal paths too) expects `expanded=none`: on kestrel-api37 the colorized goal row is first and expanded, as Step 3's `GoalServiceTest.withListeningOnTheShadeShowsTheHeldGoalsCancelAndContinueWithoutExpandingAnything` shows even with listening on. Any other value stops the task and is reported. Step 3 says that `GoalServiceHooksTest`'s press expects `expanded=none`, and Step 11 notes the values.
- **Task 9.** Every press of Steps 5 to 7 expects `tapped=<action> expanded=<value> shade=closed`, and the A71's value is recorded as design 10.6's finding on Android 13 (Step 9). `expanded=none` is the expected result after the explicit-group fix (`NotificationGroups.alone`); another value does not stop the task when the press worked, and the controller takes it to the design's rule for One UI.
- **The rest.** Decision 16 says it is settled; "Interfaces for 7F and 7G" gives the answer's new form; the counts: `:app` JVM 702 (A₀ + 43: `NoticeButtonsTest` + 4), and the device tests stay 468 (`GoalServiceHooksTest`'s expected answer changed, no test was added). Every PowerShell command of Tasks 8 and 9 still parses without an error in PowerShell 7 and in Windows PowerShell 5.1 (`Parser.ParseInput`, with the step helpers before each step's text).

## Amendment of 2026-11-26 (Task 8's second run: the press's outcome, `96861f22`)

Task 8's second run on `006de2d9` failed at Step 3 in the same test as the first run, with "continue is not in the shade". The cause was `d3f679e6`: the hook read the press helper's null, which meant "pressed", as "not in the shade". `96861f22` replaces `NoticeButtons.find` with `findAndPress`, which says pressed, refused (with its reason) or not shown, each with the expansions that it made, and the hook answers each outcome explicitly; its answers keep their form. Tasks 8 and 9 install `96861f22` or a later commit, and Task 8's Step 1 and Task 9's Step 2 check `contains 96861f22`. The counts: `:app` JVM 703 (`NoticeButtonsTest` + 1); the device tests stay 468. Headless kestrel-api37 shows the goal row first and expanded, with its buttons (`expanded=none`).

## Amendment of 2026-11-26 (Task 8's third run: notification IDs, 415ed6bd, 7aa15be0)

Task 8's third run failed at Step 8a with `send=none`: the held goal's notification, with its Cancel and Continue, was gone from the shade, while Kestrel's state still said `posted=held`. The cause was a collision of notification IDs (`.superpowers\sdd\debug-goalhooks-report.md`, round 5). `GoalNotification.SERVICE_ID` (7102) and `POSTED_ID` (7103) were also `ProblemNotifications.STOPPED_ID` ("Kestrel stopped") and `ACCESSIBILITY_ID` ("accessibility service off"), and Android keeps one notification per ID and app. When Step 8a turned Kestrel's accessibility service off, the accessibility notice replaced the held goal's notification as 7103 and was then cancelled, so Android showed nothing of the goal. The goal service's notification had the same problem with "Kestrel stopped". This was a product bug, and the A71 would have had it too. This amendment changes only the plan's text; the runs below were made for the fix.

- **The fix.** `415ed6bd` adds the enum `KestrelNotification` (package `dev.kestrel.app`), which names every kind of Kestrel's notifications once, with the ID 7101 plus its position, so the IDs are unique by construction; a new kind goes at the end. Listening (7101), the problem notices (7102 to 7104) and sign-in (7105) keep their IDs, so `scripts\a71-session.ps1`, which reads 7104, needs no change; the goal service's notification moves to 7106 and the held goal's posted one to 7107. `ListeningService`, `ProblemNotifications`, `SignInService` and `GoalNotification` take their IDs from the enum. New tests: the JVM test `KestrelNotificationTest.everyKindOfKestrelsNotificationsHasAnIdOfItsOwn`, and the device test `GoalServiceTest.aRefusedStartHoldsTheGoalAndAndroidKeepsShowingItsNoticeWithCancelAndContinue`, which holds a goal by a refused start, turns the accessibility service off, and checks that Android keeps showing the notice on the channel goals with Cancel and Continue. `7aa15be0`: that test raises the instrumented process's count of refused starts, so `GoalServiceTest.aHeldGoalOffersCancelBeforeContinue…` and `GoalServiceHooksTest`'s state test compare the count with its value before their own steps, not with 0.
- **Task 5.** The text and code as built name `SERVICE_ID = 7_102` and `POSTED_ID = 7_103`; a note after the Interfaces' "Produces" and one after `GoalNotification.kt` say that `415ed6bd` moved them to the enum, with 7106 and 7107, and why. The built code is not rewritten here. "Interfaces for 7F and 7G" gives the new IDs and a bullet for `KestrelNotification`.
- **Tasks 8 and 9.** Both install `7aa15be0` or a later commit, and Task 8's Step 1 and Task 9's Step 2 check `contains 7aa15be0`. Task 8, Step 3: 373 run, 258 passed and 115 skipped (259 and 114 when the call test runs), with `GoalServiceTest`'s 10 that need no permission; Step 5: all 11 tests of `GoalServiceTest`; Task 8 runs AD₀ + 14 device tests.
- **Runs on `7aa15be0` (headless kestrel-api37, for the fix).** `:app:testDebugUnitTest :app:lintDebug`: BUILD SUCCESSFUL, 704 JVM tests, 0 failures. `GoalServiceTest` and `GoalServiceHooksTest` together: 13 run, 0 failed, 1 skipped (the call test). Step 8a's own command passed, with `restored=goal-held`, the Cancel counted, `dropped taps: 0`, everything put back, and distinct IDs in Android's event log (7107 on goals, 7103 on accessibility, 7106 on goals in the new process). The owner's data was as before around every device run.
- **The counts.** `:app` JVM 704 (A₀ + 45: `KestrelNotificationTest` 1); `:app` device tests 469 (AD₀ + 14), counted in the source (`@Test` in `app\src\androidTest`); the other modules are unchanged. Task 8's Step 1 and Task 9's Step 2 parse without an error in PowerShell 7 and in Windows PowerShell 5.1 (`Parser.ParseInput`).

## Amendment of 2026-11-26 (Task 8's fourth run: Step 9's rebind and the unbound-service notice)

Task 8's fourth run passed Steps 1 to 8a and failed at Step 9 with `bound: False`: after the owner's stop (`cmd activity stop-app`), writing the same accessibility setting back did not bind Kestrel's accessibility service. The cause (`.superpowers\sdd\debug-step9-report.md`): a stop of the app kills the process and brings down its services without the package broadcast of a force stop, so Android's accessibility manager holds the service as crashed while the setting still names it (`AccessibilityUserState.serviceDisconnectedLocked`); `AccessibilityManagerService.updateServicesLocked` skips a crashed service, and only a change of the enabled list clears the crashed set (`removeDisabledServicesFromTemporaryStatesLocked`). A write of the same value is no change, so nothing binds the service. Android 13 has the same crashed set and the same skip, so Task 9's Step 7 had the same bug; there an off does not end a binding either (AOSP 13 drops a disabled service only from the crashed set). The run also found a product gap: after the owner's Stop in "Active apps", nothing told him that the screen features were off, because notice 7103 was posted only from `onUnbind`, which a killed process never runs. kestrel-api37 was restored through Android's own recovery, with the owner's data as before. This amendment changes only the plan's text.

- **`b907e8ed` (scripts).** `scripts\lib\Kestrel.ps1` gets `$KestrelAccessibilityService`, `ConvertFrom-AccessibilityDump` and `Test-ServiceRecordBound` (the current user's Enabled, Binding and Crashed sets of `dumpsys accessibility`, and whether the service record is bound), `Get-AccessibilityServiceState`, `Disconnect-AccessibilityService` (the list without the service, and a wait by condition until Android lists it in no set and it is unbound) and `Connect-AccessibilityService` (the list back, after an off that Android applied, and a wait by condition, up to 30 s, until the service is bound). `Restore-AccessibilitySettings` puts a list that names Kestrel's service back through `Connect-AccessibilityService`, so the restores of `scripts\connected-test.ps1`, the A71 scripts and `scripts\listen-check.ps1` bind Kestrel again after a stop or a crash; the A71 scripts' enables and the magnification restore use the helpers instead of a write and a sleep. Pester's `fake-adb.ps1` models the accessibility manager's bound, binding and crashed sets.
- **`a941c67d` (scripts, the review of `b907e8ed`).** `Connect-AccessibilityService` turns the service off only when Android holds it as crashed or binding (binding only from API 34 on, `$OffEndsBindingSdk`), or when the list already names the same services and Android does not run it, so a bound service keeps its shortcut and button targets; below API 34 it waits for a binding, and its warning explains Android 13's rule. Neither helper throws when the device does not answer: each warns (Connect with a paste-ready command) and returns `$false`. `Restore-AccessibilitySettings` reads the other settings again after the list, so the targets that Android cleared go back. The by-hand restores come from one helper, `Get-AccessibilityRestoreCommands`, which takes Kestrel's service out of the list and waits before it puts it back.
- **`55de7dfd` (scripts, the re-check of `a941c67d`).** `Restore-AccessibilitySettings` waits until Android has turned a running Kestrel off before it reads the settings again, and the pasted wait of `Get-AccessibilityRestoreCommands` reads only the service lists (`Format-ServiceListedFilter`), not the shortcut targets. Pester at `55de7dfd`: 543 passed and 1 skipped (Windows PowerShell 5.1), 544 passed (PowerShell 7), 0 failed; PSScriptAnalyzer 0 problems.
- **`4cdc50ca` (app).** `UnboundServiceCheck` (`dev.kestrel.app.listen`): at the start of Kestrel's process, when the previous process ended with the owner's stop, Kestrel waits 10 s (`GRACE_MILLIS`) and, when the setting names its accessibility service and Android does not run it, posts the existing notice 7103 (`ProblemNotifications.accessibilityServiceOff`: the channel `accessibility`, quiet; a tap opens Android's accessibility settings); `onServiceConnected` cancels it as before. Only after the owner's stop, because after a crash, a low-memory kill or a reboot Android binds the service again by itself. The notice's text says to turn the service on, "or off and on again if it shows as on". The main spec's section 14.6 and design 10.6 of sub-project 7 say so (below).
- **`7a491675` (app, the review of `4cdc50ca`).** The check reads the exit reason itself and runs after `OWNER_STOPPED` and, on Android 13, `UNCLEAR_STOP`; it ignores a record from before the current boot; its read and its post run on the main thread, where `onServiceConnected` cancels the notice. `KestrelApplication` reads the exit record once (`AppGraph.previousExit()`, through `readNewestExit`, which replaces Task 4's `readProcessEnd` and `previousProcessEnd()`) and gives `processEndOf(exit)` to the conversation's restore. `AccessibilitySettings` (`dev.kestrel.app`) holds the one parser of a setting's component list and the one query of the running services, which `SetupStatusReader` uses too. The notice's title is now "Kestrel's accessibility service stopped".
- **`66253fc3` (app, the re-check of `7a491675`).** The setup checklist reads the shortcut and button targets through `AccessibilitySettings.names`, and the check's KDoc names its limit when the wall clock jumps; no behaviour changes.
- **The specs.** Main spec, section 14.6: the row "Kestrel's accessibility service is turned off" names the notification's title, "Kestrel's accessibility service stopped"; the row "Android lists the accessibility service as on but does not run it, as after "Stop app"" adds: "The kill of "Stop app" or of Stop in "Active apps" leaves no `onUnbind`, and Android binds the service again only after the owner turns it off and on, so at the next start of Kestrel's process, when the newest exit record since the boot is such a stop (on Android 13 also a stop that cannot be told from an update) and the service still does not run 10 s later while the setting names it, Kestrel posts its notification "Kestrel's accessibility service stopped" of the row above, whose text says to turn the service off and on." Design 10.6 of sub-project 7 gains the bullet: "**Stop in "Active apps".** The owner's Stop there kills Kestrel's process, and the next start ends the goal (`ProcessEnd.OwnerStopped`), except on Android 13 within a minute of an update, where it holds the goal and asks (6.7). In both cases Android leaves Kestrel's accessibility service unbound until the owner turns it off and on, so at that start Kestrel posts its notice "Kestrel's accessibility service stopped" (spec 14.6), which says so and opens Android's accessibility settings."
- **Task 8.** The build note and Step 1: `sp3` at `55de7dfd` or a later commit, `contains 55de7dfd` and `accessibility helper: True` (the install must contain the notice, so the installed build moves too). The rule on the allowed commands names `Connect-AccessibilityService` and `Disconnect-AccessibilityService` in Steps 8a, 9 and 10, with their reads. Step 8a turns the service off with `Disconnect-AccessibilityService` and binds it again in its `finally` with `Connect-AccessibilityService` (its guard reads the setting with `Test-ServiceListed`, as Step 9's does). Step 9 explains the crashed set, prints the service's state after the stop, binds the service with `Connect-AccessibilityService`, waits 12 s after the restart, and expects `notices=1 kinds=accessibility` before the bind and `notices=0 kinds=none` after `bound: True` (otherwise it stops after the command, which has bound the service, and reports). Step 10 makes sure that the service is bound under Step 1's setting (`accessibility bound: True`). Step 11 records the state after the stop, `bound:` and both `NOTICES` answers.
- **Task 9.** The build note and Step 2: `55de7dfd`, as in Task 8. The rule names `Connect-AccessibilityService` in Steps 7 and 8. Step 7 binds the service as Task 8's Step 9 does, with the same waits and `NOTICES` answers (also after a stop that cannot be told from an update), and its Expected names Android 13's stuck binding; Step 8 makes sure that the service is bound under Step 2's setting; Step 9 records the same lines as Task 8's Step 11.
- **The counts.** `:app` JVM 711 (A₀ + 52: `UnboundServiceCheckTest` 6, of which `4cdc50ca` adds 4 and `7a491675` 2, and `ExitRecordsTest` + 1, moved out of `ProcessEndsTest`'s one test); `:app` device tests 469 (AD₀ + 14), unchanged, counted in the source; the other modules are unchanged. Every PowerShell command of Tasks 8 and 9 parses without an error in PowerShell 7 and in Windows PowerShell 5.1 (`Parser.ParseInput`, with the step helpers before each step's own text): 100 snippets (the 18 commands of the steps' "Run", the two data checks, and the commands inside the rules and the Expected texts), each after its task's step helpers unless it starts with its own `. scripts\lib\Kestrel.ps1`, 0 errors in both.
- **The rest.** "Interfaces for 7F and 7G" names `readNewestExit` and `previousExit()` for the old functions, and gains a section for `AccessibilitySettings` and the script helpers for plan 7G's scripts. Task 4's text and code name `readProcessEnd` and `previousProcessEnd()` as they were built; the built code is not rewritten here.

## Amendment of 2026-11-26 (the whole-plan review's fixes, branch `fix-7e-whole`)

The whole-plan review of plan 7E (`.superpowers\sdd\review-7e-whole.md`, Fix first: I1, I2, M1 to M9 and nits) is fixed at the root, one commit per concern. This amendment records what changed for the plan's texts; Tasks 1 to 7 keep the code as they were written, and the code as built is the reference.

- **I1 and M4: one reading of the exit record per process, and its uses run apart.** `PreviousExit` (`dev.kestrel.app.listen`, in `ExitRecords.kt`) reads `ExitRecords` once per process and gives every reader that reading (`get()`), with the one error rule ("Reading how Kestrel's last process ended", then as without a record); it replaces `readNewestExit` and `AppGraph.previousExit()`, and `AppGraph.previousExit` is the process's one `PreviousExit`. `Listening` takes `previousExit: suspend () -> ExitRecord?` in place of `exits: ExitRecords` and has no reading or error rule of its own. `KestrelApplication` starts the accessibility check and the conversation's restore with `PreviousExit.launchEach`, each in a coroutine of its own in the graph's supervisor scope, so a failed restore no longer cancels the notice of design 10.6 and spec 14.6. Tests: `ExitRecordsTest.everyReaderInAProcessGetsTheOneReadingOfTheRecord`, `aRecordThatAndroidCannotGiveIsReportedOnceAndCountsAsNoneForEveryReader` (in place of `aRecordThatAndroidCannotGiveIsReportedAndCountsAsNone`) and `aFailureOfOneUseOfTheReadingNeverCancelsAnother`; `ListeningTest`'s two tests of an unreadable record now feed no record (`aRestoredProcessThatEndedBeforeItsSessionOpenedSaysSoAlsoWithoutARecord`, `withoutARecordTheRestoreStartsInTheStoredMode`).
- **I2: the refusal of a start is a debug seam that a release build does not have.** `GoalStartRefusal` moved to `app\src\debug` (package `dev.kestrel.app.debug`), and `AppGraph` has only `goalStartGate: (() -> Unit)?`, null in a release build, as `conversationSteps`, `witnesses` and `modelOverride` are; the goal service's start calls the gate when it is set. `GoalStartRefusal.of(graph)` installs the process's one refusal as the gate (its `check` throws `ForegroundServiceStartNotAllowedException` for the start that takes an armed refusal), and the hooks and the device tests (`GoalServiceTest`, `GoalServiceHooksTest`) reach it there. Test: `GoalStartRefusalTest.theStartGateRefusesExactlyTheOneStartAfterAnArm` (the test file moved to `app\src\testDebug`).
- **M2, M3 and the nits of the goal notification: one rule for the notice whose buttons count, and the posted notice is plain.** `GoalServiceStatus.counting` (a `PlacedNotice`: the notice and its `NoticePlace`) is the one rule, the service's notice, else the one posted without it; `GoalForeground.notice()`, `GoalServiceAnswers.state` and `NoticeIntents.idOf` all read it (the rules never show both, so the order only names the rule once). The receiver keeps judging a tap against the conversation's view (`GoalButtons.press` over `GoalNotices.of`), from which the coordinator's notices come too, as the plan review's M3 decided. `NoticePlace` (`SERVICE`, `POSTED`) carries each place's notification ID and whether it is the foreground service's: `GoalNotification.build(context, notice, place)` colorizes the notice, gives it Kestrel's colour and `FOREGROUND_SERVICE_IMMEDIATE` only for `SERVICE`, so the posted notice is plain, as its comment and design 10.6 say (Android colorizes only a foreground service's notification and shows only such a notification at once, so the colour and the behaviour were never applied to it; the accent colour was, and now goes). `GoalNotices.of` gives a goal without its name (`NoticeGoal`) no buttons, and `GoalNotification.intentOf` takes a `NoticeGoal`, so no button without a goal's key is ever built; `GoalNotification.CHANNEL` is internal, and `GoalServiceTest` reads it instead of its own copy. Tests: `GoalForegroundTest.theNoticeWhoseButtonsCountIsTheServicesElseTheOnePostedWithoutIt`, `GoalNoticesTest.aGoalWithoutItsNameShowsNoButtons` (its other tests now name their goal), `NoticeIntentsTest` over `NoticePlace`, and on the device `GoalServiceTest.aRefusedStartHoldsTheGoalAndAndroidKeepsShowingItsNoticeWithCancelAndContinue`, which checks that the posted notice is neither colorized nor coloured.
- **M5: a round's end clears only its own view of the screen's turn.** `GoalRunner.turn` is an `AtomicReference`: `run` sets it, `paused` and `resumed` read it, and a round's end clears it with `compareAndSet(taking, null)`, so a round that started between the check and the write keeps its view. Test: `GoalRunnerTest.anOlderRoundsLateEndLeavesTheNewerRoundsTurnToThePause` (an older round that ends after a newer one started leaves the newer round's turn to the pause); it fails when the end clears the view without the check. The window between the old check and its write cannot be forced in a JVM test; the compare-and-set closes it by construction.
- **M6: the interfaces name the code's parameters.** "Interfaces for 7F and 7G" names `GoalButtons.input(broadcast, goal, shown, channel, number, atMillis)` and `GoalButtons.press(broadcast, goal, restored, number, atMillis, report)`, as the code has them (it named `next` and `now`), so that plan 7F's screen buttons can call them with named arguments; the input's number is `number` everywhere.
- **M7: the reading of the accessibility service lives in one place.** `AccessibilityServiceState` moved from `listen/UnboundServiceCheck.kt` into `AccessibilitySettings.kt` (package `dev.kestrel.app`), so the one reading of the service no longer depends on one of its callers, and `AccessibilitySettings.kestrelComponent(context)` is the one construction of Kestrel's service's component, which `kestrel()` and `SetupStatusReader`'s shortcut and button targets use. No behaviour changes.
- **M8: one rule for reading `dumpsys accessibility`, in Kotlin and in PowerShell.** `ManagedService.of` (the device tests' reader) now reads as `ConvertFrom-AccessibilityDump` does: only the block of the user that `currentUserId=` names (the first block without that line), the `Crashed services` set too, and the component only by its exact name in braces; a dump without the current user's block gives null, which never counts as off. `ManagedService.off` is "neither enabled, nor binding, nor crashed", as `Test-ServiceOff` reads the manager (the scripts add the service record's binding, which `ScreenServiceRule` reads as the end of Kestrel's session). `ServiceStall` retries a crashed service once too, through an off, which clears the crashed set, and fails on an unreadable state. Both readers are tested with the same sample dumps and their states (`app\src\testDebug\resources\accessibility-dumps\cases.tsv`): `ManagedServiceTest.everySampleDumpGivesTheStateThatTheScriptsReaderGivesIt` (it failed with the old reader on the current user, the exact name and the unreadable dumps) and `theServiceIsOffOnlyWhenTheManagerNeitherHasItEnabledNorBindsNorHoldsItAsCrashed`, and Pester's `ConvertFrom-AccessibilityDump` cases, one for each sample (7, in place of 4 of their own).
- **M9: `Restart-KestrelEmulator` returns why it failed and never throws.** A window type that the restarted emulator's console does not give (`Get-EmulatorWindowType` throws) is returned as "kestrel-api37's window type could not be read: …", as every other failure of the function is, so `scripts\listen-check.ps1` and `scripts\a71-agent.ps1` report it as the reason. The start through `scripts\start-emulator.ps1` is the function `Start-KestrelEmulator`, so that Pester can stand in for it. Test: Pester's `returns why, and throws nothing, when the restarted emulator's window type cannot be read`. `scripts\lib\Kestrel.ps1` also gets the two missing blank lines between functions (a nit).
- **M1: no script turns Kestrel's accessibility service off by hand.** `scripts\listen-check.ps1` and `scripts\spike-s1.ps1` turn it off with `Disconnect-AccessibilityService` (which takes only Kestrel's service out of the list, keeps every other service, and waits until Android's accessibility manager has applied the off), and stop when it did not; listen-check then still waits for Kestrel's own `screen=off`, and spike-s1 has no sleep any more. Test: the new `scripts\tests\ServiceOff.Tests.ps1`, which loads each script's `Set-KestrelService` and checks that its off goes through `Disconnect-AccessibilityService`, with no write and no sleep of its own, and stops when Android did not apply it (6 cases).
- **The other nits.** `GoalServiceHooks.tap` and `NoticeButtons.Tapped` are formatted, `ClipMicrophone.stop` lost its stray blank line, `KestrelNotification.kt`, `KestrelNotificationTest.kt` and `Microphone.kt` end with a newline, and the import of `KestrelNotification` sits in order in `ListeningService`, `ProblemNotifications`, `SignInService` (and, with M2 and M3, `GoalNotification`; `AppGraph`'s import of `ForegroundServiceStartNotAllowedException` went with I2). No behaviour changes.
- **The counts after these fixes.** `:app` JVM 717 (A₀ + 58), `:core:conversation` 293, `:core:agent` 315, `:core:voice` 179 (unchanged), `:app` device tests 469 (unchanged: the M3 check is in an existing test), Pester + 9 (`ConvertFrom-AccessibilityDump` 4 → 7, `Restart-KestrelEmulator` + 1, `ServiceOff.Tests.ps1` 6, less the 4 that the samples replaced).

## File structure
| File | Responsibility |
|---|---|
| `core/agent/src/main/kotlin/dev/kestrel/agent/TaskReport.kt`; `core/conversation/` `model/GoalState.kt`, `ConversationTexts.kt`, `machine/ConversationEvent.kt`, `machine/GoalRules.kt`, `machine/ConversationMachine.kt`, `engine/ConversationEngine.kt`, `engine/ConversationView.kt`; `app/src/main/kotlin/dev/kestrel/app/agent/AgentTexts.kt`; tests `GoalRulesTest.kt`, `ConversationEngineTest.kt`, `AgentTextsTest.kt` | Task 1: a refused start holds the goal, the view names the hold, a goal's ending and its record, and the engine says when its restore has run |
| `core/agent/src/main/kotlin/dev/kestrel/agent/loop/FrontCheck.kt`; `core/conversation/` `goal/GoalEnvironment.kt`, `goal/ScreenTakingGoal.kt`, `goal/GoalRunner.kt`, `engine/ConversationEngine.kt`, fixture `testing/FakeGoalEnvironment.kt`; tests `core/agent/src/test/kotlin/dev/kestrel/agent/ScreenTurnTest.kt`, `core/conversation/src/test/kotlin/dev/kestrel/conversation/goal/ScreenTakingGoalTest.kt` (new), `ConversationEngineTest.kt` | Task 1b: a pause lets go of the screen's turn once no step can act, and the observation's Back passes the gate |
| `core/conversation/` `goal/GoalEnvironment.kt`, `goal/RunningGoal.kt`, `goal/ScreenTakingGoal.kt`, `engine/ConversationEngine.kt`, `engine/ConversationView.kt`, fixture `testing/FakeScreenTurn.kt`; `app/src/main/kotlin/dev/kestrel/app/listen/ScreenTurns.kt`; tests `GoalRunnerTest.kt`, `ScreenTakingGoalTest.kt`, `ConversationEngineTest.kt`, `app/src/test/kotlin/dev/kestrel/app/listen/ScreenTurnsTest.kt`, `ListeningTest.kt` | Task 1c: the view says when the round waits behind the owner's command |
| `app/src/main/kotlin/dev/kestrel/app/goal/GoalTexts.kt`, `app/src/main/kotlin/dev/kestrel/app/goal/GoalNotice.kt` (new); test `app/src/test/kotlin/dev/kestrel/app/goal/GoalNoticesTest.kt` (new) | Task 2: the notification's words and buttons, with the wait behind the owner's command and each button as the owner's input |
| `app/src/main/kotlin/dev/kestrel/app/goal/GoalServiceRules.kt`, `app/src/main/kotlin/dev/kestrel/app/goal/GoalForeground.kt` (new); tests `app/src/test/kotlin/dev/kestrel/app/goal/GoalServiceRulesTest.kt`, `app/src/test/kotlin/dev/kestrel/app/goal/GoalForegroundTest.kt` (new) | Task 3: when the service runs, what Kestrel posts without it, with which types, and a refusal |
| `app/src/main/kotlin/dev/kestrel/app/listen/ExitRecords.kt`, `listen/Listening.kt`; `app/src/main/kotlin/dev/kestrel/app/conversation/ProcessEnds.kt` (new); `AppGraph.kt`, `KestrelApplication.kt`; tests `app/src/test/kotlin/dev/kestrel/app/listen/ExitRecordsTest.kt`, `listen/ListeningTest.kt`, `app/src/test/kotlin/dev/kestrel/app/conversation/ProcessEndsTest.kt` (new) | Task 4: one reader and one reading of how Kestrel's last process ended, for both restores |
| `app/src/main/kotlin/dev/kestrel/app/goal/GoalNotification.kt`, `app/src/main/kotlin/dev/kestrel/app/goal/GoalService.kt`, `app/src/main/kotlin/dev/kestrel/app/goal/GoalActionReceiver.kt` (new); `AndroidManifest.xml`, `AppGraph.kt`; device test `app/src/androidTest/kotlin/dev/kestrel/app/conversation/GoalServiceTest.kt` (new) | Task 5: the service, its notification, its buttons and the wiring |
| debug `FocusProbe.kt`, `GoalServiceAnswers.kt`, `NoticeButtons.kt`, `GoalServiceHooks.kt` (new), `TestHooks.kt`; JVM tests `app/src/testDebug/kotlin/dev/kestrel/app/debug/GoalServiceAnswersTest.kt`, `app/src/testDebug/kotlin/dev/kestrel/app/debug/NoticeButtonsTest.kt` (new); device test `app/src/androidTest/kotlin/dev/kestrel/app/debug/GoalServiceHooksTest.kt` (new) | Task 6: the hooks `goal-service-state` and `goal-control` |
| device test `app/src/androidTest/kotlin/dev/kestrel/app/conversation/ConversationSearchSpeedTest.kt` (new) | Task 7: the whole first search's speed, a close and a search right after a start |
| none in git but `.superpowers\sdd\progress.md` (device steps) | Task 8: the checks on kestrel-api37 (the controller's steps) |
| none in git but `.superpowers\sdd\progress.md` (device steps) | Task 9: the checks on the A71 (waits for the owner) |

Every main path of `:app` is below `app/src/main/kotlin/dev/kestrel/app/`, every JVM test below `app/src/test/kotlin/dev/kestrel/app/`, the debug hooks below `app/src/debug/kotlin/dev/kestrel/app/debug/` and their JVM tests below `app/src/testDebug/kotlin/dev/kestrel/app/debug/`; the steps give each path in full.

## The restore's `ProcessEnd` in plan 7B

The owner's stop in Android's "Active apps" ends a restored goal as "stop" ends it (the controller's decision of 2026-11-25, review item I4). What the restore needs for it was written here first and is now in plan 7B itself: its Task 7 creates `model/ProcessEnd.kt` (`OwnerStopped(atWall)`, `Other`), gives `ConversationEvent.Restored` its `previous: ProcessEnd = ProcessEnd.Other`, and makes the machine end a restored goal through `GoalRules.restored(state, stored, heldSince, now, end = EndReason.CANCELLED)`, which gives `EndHeldGoal(EndReason.CANCELLED)`, after `OwnerStopped` at or after the snapshot's `lastWall`, with `ConversationMachineTest.theOwnersStopOfKestrelEndsTheRestoredGoalAsStopDoesAndAsksNothing`; its Task 10 gives `ConversationEngine.restore(previous: ProcessEnd = ProcessEnd.Other)`, with `ConversationEngineTest.aRestoreAfterTheOwnersStopEndsTheGoalWithItsReportAndAsksNothing`. This plan's Task 4 gives `restore()` its argument, and "Before you start" checks for both.

---

### Task 1: A refused start holds the goal

Design 10.6 of sub-project 7: "When Android refuses a start, Kestrel says 'Android didn't let me start working in the background. Open Kestrel and tap Continue.' and the goal is held as interrupted." A working goal cannot go on without its service, so the conversation interrupts its round, as a touch does (D13), with a cause of its own, and holds the goal for 30 minutes (D17); a goal that is held already keeps its hold and only hears why. The refusal names the command whose start Android refused, so that a late refusal never touches a newer goal. Until plan 7F brings the conversation screen's Continue, the sentence names the way on that exists after a refusal, the notification's Continue (Tasks 3 and 5 post the held goal's notification without the service). The conversation's view gains the hold and the goal's ending, so that the goal notification of Task 2 can name how to go on and the service of Task 3 starts for no goal that is ending. Tasks 1, 1b and 1c are the plan's changes to `:core:agent` and `:core:conversation`.

**Files:**
- Modify: `core/agent/src/main/kotlin/dev/kestrel/agent/TaskReport.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/model/GoalState.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/ConversationTexts.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/ConversationEvent.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/GoalRules.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/ConversationMachine.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationView.kt`, `app/src/main/kotlin/dev/kestrel/app/agent/AgentTexts.kt`
- Test: `core/conversation/src/test/kotlin/dev/kestrel/conversation/machine/GoalRulesTest.kt`, `core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt`, `app/src/test/kotlin/dev/kestrel/app/agent/AgentTextsTest.kt`

**Interfaces:**
- Consumes: plan 7A's `Interruption` and `TaskReport.interruption`; plan 7B's `GoalHold`, `GoalRules.touched`, `GoalHold.of`, `GoalRules.held`, `ConversationTexts.held`, `ConversationEvent.Touched`, `ConversationEffect.InterruptGoal`, `ConversationEngine.touched()`, `ConversationView`; plan 7D's `AgentTexts.sentence`.
- Produces: `Interruption.BACKGROUND_REFUSED` (package `dev.kestrel.agent`); `GoalHold.BACKGROUND_REFUSED` (`GoalPhase.INTERRUPTED`, `ResumeCause.INTERRUPTION`); `ConversationTexts.BACKGROUND_REFUSED = "Android didn't let me start working in the background."` and `ConversationTexts.CONTINUE_IN_NOTIFICATION = "Tap Continue in Kestrel's notification."` (plan 7F changes it), with `ConversationTexts.held(GoalHold.BACKGROUND_REFUSED) == CONTINUE_IN_NOTIFICATION` (plan 7B's `held(hold)`, which this task makes one `when` over every `GoalHold`; `GoalRules.backgroundRefused` and the tests of `:core:conversation` take the sentence from it, so plan 7F changes it in `ConversationTexts` alone); `ConversationEvent.BackgroundRefused(command: Int)`; `GoalRules.backgroundRefused(state: ConversationState, command: Int): MachineStep`; `fun ConversationEngine.backgroundRefused(command: Int)` (any thread; never blocks), where [command] is the view's `command` when the refused start was asked; `ConversationView.hold: GoalHold?`, `ConversationView.goalEnding: GoalEnding?` and `ConversationView.goalRecord: Int?` (the open goal's record number in its conversation, which with the view's `id` names the goal across Kestrel's processes, for the notification's buttons; the last three fields, default null); `suspend fun ConversationEngine.awaitRestored()`, which returns once `restore()` has run, so that an input that started Kestrel's process can be judged against the restored view; `AgentTexts.sentence` gives `ConversationTexts.BACKGROUND_REFUSED` as the reason of an interruption with that cause.

Counts: `:core:conversation` C₀ + 3 (`GoalRulesTest` + 1, `ConversationEngineTest` + 2). `:app` JVM A₀, unchanged (`AgentTextsTest` gains a check in an existing test). `:core:agent` G₀, unchanged.

- [ ] **Step 1: Write the failing tests**

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/machine/GoalRulesTest.kt`, replace:

```kotlin
    @Test
    fun touchesDuringAHandOverAReplyOrAHeldGoalInterruptNothing() {
```

with:

```kotlin
    @Test
    fun androidsRefusalHoldsAWorkingGoalAsInterruptedAndLeavesAHeldGoalsHold() {
        // Plan 7E; design 10.6 of sub-project 7: a goal that acts, asks or has handed a step over cannot go on in the
        // background, so its round is interrupted with Android's refusal as its cause.
        val refusing = step(acting(), ConversationEvent.BackgroundRefused(1))
        assertEquals(ConversationEffect.InterruptGoal(Interruption.BACKGROUND_REFUSED), refusing.effect<ConversationEffect.InterruptGoal>())
        assertTrue(step(refusing.state, ConversationEvent.BackgroundRefused(1)).effects.isEmpty())
        val handedOver = step(acting(), ConversationEvent.Dialogue(DialogueEvent.HandedOver(2))).state
        assertTrue(step(handedOver, ConversationEvent.BackgroundRefused(1)).has<ConversationEffect.InterruptGoal>())
        // A late refusal of an older command's start never touches this goal.
        assertTrue(step(acting(), ConversationEvent.BackgroundRefused(2)).effects.isEmpty())

        val sentence = "Android didn't let me start working in the background. I opened Viber."
        val held = ended(refusing.state, TaskReport(EndReason.INTERRUPTED, interruption = Interruption.BACKGROUND_REFUSED), sentence)
        assertEquals(GoalHold.BACKGROUND_REFUSED, held.state.goal!!.hold)
        assertEquals(GoalPhase.INTERRUPTED, held.state.goal!!.phase)
        assertEquals(listOf(ChannelOutput.Said("$sentence ${ConversationTexts.held(GoalHold.BACKGROUND_REFUSED)}")), held.outputs())

        // A held goal keeps its hold and its 30 minutes; Kestrel says why its service is missing and how to go on, at once
        // while the owner is there.
        val said = ChannelOutput.Said("${ConversationTexts.BACKGROUND_REFUSED} ${ConversationTexts.held(GoalHold.BACKGROUND_REFUSED)}")
        val touchedHeld = ended(step(acting(), ConversationEvent.Touched).state, TaskReport(EndReason.INTERRUPTED, interruption = Interruption.TOUCH)).state
        val toldNow = step(touchedHeld, ConversationEvent.BackgroundRefused(1))
        assertEquals(touchedHeld.goal, toldNow.state.goal)
        assertEquals(listOf(said), toldNow.outputs())
        // A lock paused the goal, so the words wait until the owner is back; the record has them at once.
        val paused = signal(acting(), PhoneSignal.LOCKED).state
        val told = step(paused, ConversationEvent.BackgroundRefused(1))
        assertEquals(paused.goal, told.state.goal)
        assertTrue(told.outputs().isEmpty())
        assertEquals(ConversationEffect.AddLine(id, 1, LineKind.KESTREL_SAID, said.text, null), told.effect<ConversationEffect.AddLine>())
        assertEquals(said, signal(told.state, PhoneSignal.UNLOCKED).outputs().first())
        assertTrue(step(idle, ConversationEvent.BackgroundRefused(0)).effects.isEmpty())
    }

    @Test
    fun touchesDuringAHandOverAReplyOrAHeldGoalInterruptNothing() {
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt`, replace:

```kotlin
import dev.kestrel.agent.EndReason
```

with:

```kotlin
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.Interruption
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt`, replace:

```kotlin
import dev.kestrel.conversation.model.GoalPhase
```

with:

```kotlin
import dev.kestrel.conversation.model.GoalHold
import dev.kestrel.conversation.model.GoalPhase
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt`, replace:

```kotlin
    @Test
    fun aTouchInterruptsTheGoalAndContinueResumesItInANewRoundThatCountsOnItsSteps() = scope.runTest {
```

with:

```kotlin
    @Test
    fun androidsRefusalOfTheGoalServiceHoldsTheGoalAndContinueResumesIt() = scope.runTest {
        val speaking = CompletableDeferred<Unit>()
        text.onShow = { if (it == ChannelOutput.Said("On it.")) speaking.await() }
        val environment = environment(say("On it."), listSms, finish(1))
        val engine = engine(environment)
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        assertEquals(GoalPhase.ACTING, engine.view.value.goal)

        engine.backgroundRefused(engine.view.value.command)
        runCurrent()

        // Plan 7E; design 10.6 of sub-project 7: the goal is held as interrupted, and the view names why.
        assertEquals(GoalPhase.INTERRUPTED, engine.view.value.goal)
        assertEquals(GoalHold.BACKGROUND_REFUSED, engine.view.value.hold)
        assertEquals(1, engine.view.value.goalRecord)
        assertEquals(Interruption.BACKGROUND_REFUSED, engine.outcome(1)!!.report.interruption)
        assertTrue(text.texts().last().endsWith(ConversationTexts.held(GoalHold.BACKGROUND_REFUSED)))

        engine.submit(input(InputChannel.TEXT, InputIntent.Continue))
        runCurrent()
        assertNull(engine.view.value.goal)
        assertNull(engine.view.value.hold)
        assertEquals(EndReason.FINISHED, engine.view.value.records.single().end)
    }

    // Plan 7E: a tap on the goal notification can start Kestrel's process; the receiver waits until the conversation is
    // restored, and then reads the restored goal, which the view names by its conversation and its record.
    @Test
    fun awaitRestoredReturnsOnceTheRestoreHasRunWithTheRestoredGoalInTheView() = scope.runTest {
        val now = ManualConversationClock.BASE
        val id = ConversationId("0123456789abcdef0123456789abcdef")
        val record = CommandRecord(1, now - 60_000, InputChannel.TEXT, "Archive my newsletters")
        store.save(ConversationSnapshot(id, now - 60_000, now - 30_000, open = true, testMode = false, records = listOf(record), goal = GoalState(1, GoalPhase.ACTING, now - 30_000)))
        val engine = engine(environment(), ArchivedConversationStore(archive, backgroundScope) {})
        var seen: ConversationView? = null
        launch {
            engine.awaitRestored()
            seen = engine.view.value
        }
        runCurrent()
        assertNull(seen)

        assertEquals(RestoreResult.GOAL_HELD, engine.restore())
        runCurrent()

        assertEquals(id, seen?.id)
        assertEquals(GoalPhase.INTERRUPTED, seen?.goal)
        assertEquals(1, seen?.goalRecord)
    }

    @Test
    fun aTouchInterruptsTheGoalAndContinueResumesItInANewRoundThatCountsOnItsSteps() = scope.runTest {
```

In `app/src/test/kotlin/dev/kestrel/app/agent/AgentTextsTest.kt`, replace:

```kotlin
    // Plan 7A: an interruption says that Kestrel stopped, and why when Kestrel's own screen stayed in front.
```

with:

```kotlin
    // Plan 7A: an interruption says that Kestrel stopped, and why when Kestrel's own screen stayed in front; plan 7E: an
    // interruption by Android's refusal of the goal service says that, and the conversation adds how to go on.
```

In `app/src/test/kotlin/dev/kestrel/app/agent/AgentTextsTest.kt`, replace:

```kotlin
            "I stopped. I didn't change anything.",
            AgentTexts.sentence(TaskReport(EndReason.INTERRUPTED, interruption = Interruption.TOUCH), "Copilot"),
        )
    }
```

with:

```kotlin
            "I stopped. I didn't change anything.",
            AgentTexts.sentence(TaskReport(EndReason.INTERRUPTED, interruption = Interruption.TOUCH), "Copilot"),
        )
        assertEquals(
            "Android didn't let me start working in the background. I opened Viber.",
            AgentTexts.sentence(TaskReport(EndReason.INTERRUPTED, interruption = Interruption.BACKGROUND_REFUSED, ledger = listOf("opened Viber")), "Copilot"),
        )
    }
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `scripts\gradle.ps1 :core:conversation:test --tests=dev.kestrel.conversation.machine.GoalRulesTest --tests=dev.kestrel.conversation.engine.ConversationEngineTest`
Expected: FAIL, the compiler reports `Unresolved reference 'BackgroundRefused'`, `Unresolved reference 'BACKGROUND_REFUSED'`, `Unresolved reference 'goalRecord'` and `Unresolved reference 'awaitRestored'`.

- [ ] **Step 3: The cause, the hold and the sentences**

In `core/agent/src/main/kotlin/dev/kestrel/agent/TaskReport.kt`, replace:

```kotlin
    /** Kestrel's own screen stayed in front of the app that the run would observe, longer than the owner port waits. */
    KESTREL_SCREEN,
}
```

with:

```kotlin
    /** Kestrel's own screen stayed in front of the app that the run would observe, longer than the owner port waits. */
    KESTREL_SCREEN,

    /**
     * Android refused the goal service, so the goal could not go on working in the background (plan 7E; design 10.6 of
     * sub-project 7).
     */
    BACKGROUND_REFUSED,
}
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/model/GoalState.kt`, replace:

```kotlin
 * pauses the round, which waits at its gate and goes on with a fresh look ([resume] is null); a limit, a touch, Kestrel's
 * own screen or a restart ended the round, and the goal resumes in a new run with [resume] (design 7.6).
```

with:

```kotlin
 * pauses the round, which waits at its gate and goes on with a fresh look ([resume] is null); a limit, a touch, Kestrel's
 * own screen, a restart or Android's refusal of the goal service (plan 7E) ended the round, and the goal resumes in a new
 * run with [resume] (design 7.6).
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/model/GoalState.kt`, replace:

```kotlin
    RESTART(GoalPhase.INTERRUPTED, ResumeCause.RESTART),
    ;
```

with:

```kotlin
    RESTART(GoalPhase.INTERRUPTED, ResumeCause.RESTART),
    BACKGROUND_REFUSED(GoalPhase.INTERRUPTED, ResumeCause.INTERRUPTION),
    ;
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/ConversationTexts.kt`, replace:

```kotlin
    const val LEAVE_KESTREL = "Leave Kestrel's screen, then tap Continue or say 'Kestrel, continue'."
```

with:

```kotlin
    const val LEAVE_KESTREL = "Leave Kestrel's screen, then tap Continue or say 'Kestrel, continue'."

    /** Android refused the goal service (plan 7E; design 10.6): why the goal stopped. */
    const val BACKGROUND_REFUSED = "Android didn't let me start working in the background."

    /**
     * How the owner goes on after a refusal: the Continue of the held goal's notification, which Kestrel posts without the
     * service (plan 7E). Plan 7F makes it design 10.6's "Open Kestrel and tap Continue." with the conversation screen.
     */
    const val CONTINUE_IN_NOTIFICATION = "Tap Continue in Kestrel's notification."
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/ConversationTexts.kt`, replace:

```kotlin
    /** How the owner goes on with a goal that [hold] holds after its round ended (design 6.5 and 10.2). */
    fun held(hold: GoalHold): String = if (hold == GoalHold.KESTREL_SCREEN) LEAVE_KESTREL else GO_ON
```

with:

```kotlin
    /** How the owner goes on with a goal that [hold] holds (design 6.5, 10.2 and 10.6); the goal notification shows it too. */
    fun held(hold: GoalHold): String = when (hold) {
        GoalHold.KESTREL_SCREEN -> LEAVE_KESTREL
        GoalHold.BACKGROUND_REFUSED -> CONTINUE_IN_NOTIFICATION
        GoalHold.LOCK, GoalHold.INCOMING_CALL, GoalHold.OUTGOING_CALL, GoalHold.LIMIT, GoalHold.TOUCH, GoalHold.RESTART -> GO_ON
    }
```

- [ ] **Step 4: The machine's rule and the engine's way in**

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/ConversationEvent.kt`, replace:

```kotlin
    /** The touch watcher saw the owner's touch while a goal ran (D13). */
    data object Touched : ConversationEvent
```

with:

```kotlin
    /** The touch watcher saw the owner's touch while a goal ran (D13). */
    data object Touched : ConversationEvent

    /**
     * Android refused to start the goal service for the goal of command [command] (plan 7E; design 10.6): that goal is held
     * as interrupted while its round works, and keeps its hold while it is held; any other goal is left alone.
     */
    data class BackgroundRefused(val command: Int) : ConversationEvent
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/ConversationMachine.kt`, replace:

```kotlin
            ConversationEvent.Touched -> GoalRules.touched(state)
```

with:

```kotlin
            ConversationEvent.Touched -> GoalRules.touched(state)
            is ConversationEvent.BackgroundRefused -> GoalRules.backgroundRefused(state, event.command)
```

`GoalHold.of(report)`, plan 7B's one rule for which round's end holds a goal (the goal runner reads it for the round's final words and the machine for the goal's phase), gives Android's refusal its own hold. In `core/conversation/src/main/kotlin/dev/kestrel/conversation/model/GoalState.kt`, replace:

```kotlin
            EndReason.INTERRUPTED -> if (report.interruption == Interruption.KESTREL_SCREEN) KESTREL_SCREEN else TOUCH
```

with:

```kotlin
            EndReason.INTERRUPTED -> when (report.interruption) {
                Interruption.KESTREL_SCREEN -> KESTREL_SCREEN
                Interruption.BACKGROUND_REFUSED -> BACKGROUND_REFUSED
                Interruption.TOUCH, null -> TOUCH
            }
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/GoalRules.kt`, replace:

```kotlin
        if (!interrupts) return MachineStep(state)
        return MachineStep(ending(state, goal, GoalEnding.INTERRUPTING), listOf(ConversationEffect.InterruptGoal(Interruption.TOUCH)))
    }
```

with:

```kotlin
        if (!interrupts) return MachineStep(state)
        return MachineStep(ending(state, goal, GoalEnding.INTERRUPTING), listOf(ConversationEffect.InterruptGoal(Interruption.TOUCH)))
    }

    /**
     * Android refused the goal service (plan 7E; design 10.6 of sub-project 7). A goal whose round works (it acts, asks or
     * has handed a step over) cannot go on in the background, so its round is interrupted and the goal held, and Kestrel
     * says why and how to go on after the round's report. A held goal, also one that a lock or a call paused or a restart
     * left, keeps its hold and its 30 minutes, and Kestrel says only why and how to go on: at once when the owner is there,
     * else after what it keeps for him already, when he is back (as [held] does); the record has the words at once. A
     * refusal of another command's start, which came late, changes nothing.
     */
    fun backgroundRefused(state: ConversationState, command: Int): MachineStep {
        val goal = state.goal ?: return MachineStep(state)
        if (goal.ending != null || goal.command != command) return MachineStep(state)
        if (goal.hold == null) {
            return MachineStep(ending(state, goal, GoalEnding.INTERRUPTING), listOf(ConversationEffect.InterruptGoal(Interruption.BACKGROUND_REFUSED)))
        }
        // How to go on is the hold's own sentence, which plan 7F changes in ConversationTexts alone.
        val words = ChannelOutput.Said("${ConversationTexts.BACKGROUND_REFUSED} ${ConversationTexts.held(GoalHold.BACKGROUND_REFUSED)}")
        val line = ConversationEffect.AddLine(state.openId, goal.record, LineKind.KESTREL_SAID, words.text, null)
        if (state.phone.present) return MachineStep(state, listOf(ConversationEffect.Output(words), line))
        val kept = ChannelOutput.Said(listOfNotNull(state.heldWords?.text, words.text).joinToString(" "))
        return MachineStep(state.copy(heldWords = kept), listOf(line))
    }
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
    fun touched() = post(ConversationEvent.Touched)
```

with:

```kotlin
    fun touched() = post(ConversationEvent.Touched)

    /**
     * Android refused to start the goal service for the goal of command [command], the view's `command` when the start was
     * asked (plan 7E; design 10.6 of sub-project 7). Any thread; never blocks.
     */
    fun backgroundRefused(command: Int) = post(ConversationEvent.BackgroundRefused(command))

    /**
     * Returns once [restore] has run, with the restored conversation in [view] (plan 7E): a tap on the goal notification
     * that started Kestrel's process is judged against the restored goal, not against the empty view before it.
     */
    suspend fun awaitRestored() = started.await()
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
            keptForOwner = current.heldReports.isNotEmpty() || current.heldWords != null,
            ownRequest = current.local?.cancelled == false,
        )
```

with:

```kotlin
            keptForOwner = current.heldReports.isNotEmpty() || current.heldWords != null,
            ownRequest = current.local?.cancelled == false,
            hold = current.goal?.hold,
            goalEnding = current.goal?.ending,
            goalRecord = current.goal?.record,
        )
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationView.kt`, replace:

```kotlin
import dev.kestrel.conversation.model.GoalPhase
```

with:

```kotlin
import dev.kestrel.conversation.model.GoalEnding
import dev.kestrel.conversation.model.GoalHold
import dev.kestrel.conversation.model.GoalPhase
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationView.kt`, replace:

```kotlin
 * that the voice stops it before the command in "Stop. Kestrel, …". toString prints no words.
```

with:

```kotlin
 * that the voice stops it before the command in "Stop. Kestrel, …", why a held goal is held ([hold], which the goal
 * notification names), whether the goal is ending ([goalEnding], for which the goal service does not start), and the open
 * goal's record number ([goalRecord], which with [id] names the goal across Kestrel's processes, for the notification's
 * buttons; plan 7E). toString prints no words.
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationView.kt`, replace:

```kotlin
    val ownRequest: Boolean = false,
) {
```

with:

```kotlin
    val ownRequest: Boolean = false,
    val hold: GoalHold? = null,
    val goalEnding: GoalEnding? = null,
    val goalRecord: Int? = null,
) {
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationView.kt`, replace:

```kotlin
            "kept=$keptForOwner, own=$ownRequest)"
```

with:

```kotlin
            "kept=$keptForOwner, own=$ownRequest, hold=$hold, ending=$goalEnding, goalRecord=$goalRecord)"
```

- [ ] **Step 5: The round's sentence**

In `app/src/main/kotlin/dev/kestrel/app/agent/AgentTexts.kt`, replace:

```kotlin
import dev.kestrel.app.providers.ProviderTexts
```

with:

```kotlin
import dev.kestrel.app.providers.ProviderTexts
import dev.kestrel.conversation.ConversationTexts
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AgentTexts.kt`, replace:

```kotlin
        EndReason.INTERRUPTED -> when (report.interruption) {
            Interruption.KESTREL_SCREEN -> "I stopped because Kestrel's own screen stayed in front."
            Interruption.TOUCH, null -> "I stopped."
        }
```

with:

```kotlin
        EndReason.INTERRUPTED -> when (report.interruption) {
            Interruption.KESTREL_SCREEN -> "I stopped because Kestrel's own screen stayed in front."
            // Plan 7E: the conversation adds how to go on (design 10.6 of sub-project 7).
            Interruption.BACKGROUND_REFUSED -> ConversationTexts.BACKGROUND_REFUSED
            Interruption.TOUCH, null -> "I stopped."
        }
```

- [ ] **Step 6: Run the tests to see them pass**

Run: `scripts\gradle.ps1 :core:conversation:test --tests=dev.kestrel.conversation.machine.GoalRulesTest --tests=dev.kestrel.conversation.engine.ConversationEngineTest`
Expected: PASS.

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.agent.AgentTextsTest`
Expected: PASS.

- [ ] **Step 7: Run the three modules' tests, count them and stop Gradle**

Run: `scripts\gradle.ps1 :core:agent:test :core:conversation:test :app:testDebugUnitTest :core:conversation:check`, then `foreach ($dir in 'app\build\test-results\testDebugUnitTest', 'core\conversation\build\test-results\test', 'core\agent\build\test-results\test') { $n = 0; Get-ChildItem $dir -Filter *.xml | ForEach-Object { $n += [int]([xml](Get-Content $_.FullName -Raw)).testsuite.tests }; "$dir $n" }`, then `scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL; `app\build\test-results\testDebugUnitTest` A₀, `core\conversation\build\test-results\test` C₀ + 3, `core\agent\build\test-results\test` G₀; `:core:conversation:check` runs the privacy audit (`kestrelAudit`) with no finding.

- [ ] **Step 8: Commit**

```bash
git add core/agent/src/main core/conversation/src app/src/main/kotlin/dev/kestrel/app/agent/AgentTexts.kt app/src/test/kotlin/dev/kestrel/app/agent/AgentTextsTest.kt
git commit -m "feat(conversation): Android's refusal of the goal service holds its working goal as interrupted, with a cause, a hold and a sentence of its own, and the view names the hold and a goal's ending (plan 7E)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 1b: A pause lets go of the screen's turn once no step can act

Design 6.5 of sub-project 7, as plan 7D built it: a goal's round holds the screen's turn while it acts, and lets go of it as every wait for the owner starts, so that his instant commands work during the wait; while a round holds the turn, Listening starts no instant command and the bar says "Busy with the goal. Say "stop" to end it." (`ScreenTurns`, plan 7D). A lock or a call is such a wait, and it starts when the conversation pauses the goal, not when the round next reaches its gate (`TaskRun.waitForOwner`). Plan 7D's round let go only at that gate, so a pause that met the round inside a model call kept the turn held until the call ended: the owner, back on the phone, had his instant commands refused with the busy text, although the goal showed as paused and "stop" would end it (plan 7D's whole-plan re-review, round 5, R5-1).

Letting go at once at every pause would open a gap of its own: between a gate that the run has passed and the end of the step that the gate let through (its fresh read of the screen, its safety check, its action, the observation after it, Kestrel's speech), the step may act on the screen, and an instant command must not start beside it (the controller's decision of 2026-11-25). So the round's own view of the turn, `ScreenTakingGoal`, through which every turn of the round goes, the round's first one too, is the one rule for a pause: from the moment the run asks for the turn (`takeScreen`) until its next model call starts (`stepStarted`, which the run calls right before every model call, after the turn's gate, the observation and the steering) or its next wait for the owner starts (`releaseScreen`), a step may act, and a pause that comes then lets go of the turn only when that span ends; a pause that comes while the round waits for its model lets go at once. A resume before the span ends keeps the turn, because the goal acts again. The engine reaches the running round's view through `GoalRunner.paused()` and `GoalRunner.resumed()`. `:core:agent`'s ports do not change: the span's ends are the goal port's existing `stepStarted` and `releaseScreen`.

The Back with which an observation closes an app that crashed acts on the screen while the goal may be paused, because the observation passed no pause gate. It now passes the gate first, as every other action does (D2), and a gate that held the run is no check of its own: the screen is read again, and the observation keeps all of its checks for the screen after the pause.

**Files:**
- Modify: `core/agent/src/main/kotlin/dev/kestrel/agent/loop/FrontCheck.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/goal/GoalEnvironment.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/goal/ScreenTakingGoal.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/goal/GoalRunner.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, fixture `core/conversation/src/testFixtures/kotlin/dev/kestrel/conversation/testing/FakeGoalEnvironment.kt`
- Create (test): `core/conversation/src/test/kotlin/dev/kestrel/conversation/goal/ScreenTakingGoalTest.kt`
- Test: `core/agent/src/test/kotlin/dev/kestrel/agent/ScreenTurnTest.kt`, `core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt`

**Interfaces:**
- Consumes: plan 7D's `ScreenTurn` (`take`, `release`), `ScreenTakingGoal`, `GoalRunner`'s screen turn, `GoalPort.stepStarted`, `takeScreen` and `releaseScreen`, `TaskRun.gate()` and `waitForOwner`, `FrontCheck.checked`, `ConversationEffect.PauseGoal` and `ResumePausedGoal`, `ConversationOwner.pause()` and `resume()`; the fixtures' `FakeGoal.screenTurn` and `screenTurns` (`:core:agent`) and `FakeScreenTurn` (`takes`, `releases`, `holds`).
- Produces: `ScreenTakingGoal.paused()` and `resumed()` (any thread), with the span from `takeScreen` to the next `stepStarted` or `releaseScreen` in which a pause's release waits; `GoalRunner.paused()` and `GoalRunner.resumed()`, which reach the running round's `ScreenTakingGoal`; `GoalRunner.round` takes its first turn and lets go of its last through its `ScreenTakingGoal` too; the engine calls `paused()` with `ConversationOwner.pause()` for every `ConversationEffect.PauseGoal`, and `resumed()` before `ConversationOwner.resume()` for every `ResumePausedGoal`. `FrontCheck.checked` closes an app that crashed with Back only when `TaskRun.gate()` returns false; a gate that held the run reads the screen again first and uses up none of the observation's checks. `FakeGoalEnvironment.beforeCall: suspend (Int) -> Unit`, which runs before each model call with the number of that call (1, 2, …), so that a test can hold a round inside its model call.

Counts: `:core:agent` G₀ + 2 (`ScreenTurnTest` + 2). `:core:conversation` C₀ + 3 (Task 1) + 4 = C₀ + 7 (`ScreenTakingGoalTest` 2, `ConversationEngineTest` + 2). `:app` JVM A₀, unchanged.

- [ ] **Step 1: Write the failing tests**

Create `core/conversation/src/test/kotlin/dev/kestrel/conversation/goal/ScreenTakingGoalTest.kt`:

```kotlin
package dev.kestrel.conversation.goal

import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.TraceEntry
import dev.kestrel.conversation.model.RoundEnd
import dev.kestrel.conversation.testing.FakeScreenTurn
import dev.kestrel.safety.Category
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a pause lets go of a goal's round's screen turn (design 6.5 of sub-project 7; plan 7E): once no step that a gate let
 * through can act on the screen any more, and at once while the round waits for its model (plan 7D's re-review, R5-1).
 */
class ScreenTakingGoalTest {
    private val screen = FakeScreenTurn()
    private val steps = mutableListOf<Int>()
    private val listener = object : GoalListener {
        override fun stepStarted(step: Int) {
            steps += step
        }

        override suspend fun traced(entry: TraceEntry) = Unit

        override suspend fun checklist(checklist: Checklist) = Unit

        override fun allowed(categories: Set<Category>) = Unit

        override fun steeringChanged() = Unit

        override fun touched() = Unit

        override fun ended(end: RoundEnd) = Unit
    }
    private val taking = ScreenTakingGoal(RunningGoal(1, 1, emptyMap(), emptyList(), listener), screen)

    // The controller's decision of 2026-11-25: a pause while a step that its gate let through may still act keeps the turn,
    // so that no instant command starts beside the step; the turn goes when the step has ended, which is the run's next model
    // call or its next wait for the owner.
    @Test
    fun aPauseWhileAStepMayActLetsGoOfTheTurnOnlyWhenTheStepHasEnded() = runTest {
        taking.takeScreen()
        taking.paused()
        assertTrue(screen.holds)
        taking.stepStarted(2)
        assertFalse(screen.holds)
        assertEquals(listOf(2), steps)

        taking.takeScreen()
        taking.paused()
        assertTrue(screen.holds)
        taking.releaseScreen()
        assertFalse(screen.holds)
        assertEquals(2, screen.releases)
    }

    // Plan 7D's re-review, R5-1: a pause while the round waits for its model lets go at once, so that the owner's instant
    // commands work during it. A resume before the step's end keeps the turn, because the goal acts again.
    @Test
    fun aPauseDuringAModelCallLetsGoAtOnceAndAResumeBeforeTheStepsEndKeepsTheTurn() = runTest {
        taking.takeScreen()
        taking.stepStarted(1)
        assertTrue(screen.holds)
        taking.paused()
        assertFalse(screen.holds)

        taking.takeScreen()
        taking.paused()
        taking.resumed()
        taking.stepStarted(2)
        assertTrue(screen.holds)
    }
}
```

In `core/agent/src/test/kotlin/dev/kestrel/agent/ScreenTurnTest.kt`, replace:

```kotlin
    // The observation after a pause closes an app that crashed meanwhile with Back, which is an action on the screen; it
    // waits for the turn, because the owner's command may still act there.
```

with:

```kotlin
    // Plan 7E: the Back that closes an app that crashed is an action on the screen, so it passes the pause gate first, as
    // every action does (D2), and with it the turn. Here the first observation finds the crashed app, before any gate.
    @Test
    fun aCrashedAppInTheFirstObservationIsClosedOnlyAfterTheGate() = runTest {
        val crashed = ScreenBuilder.screen("android", "Viber keeps stopping") { button("Close app", 300) }
        val harness = Harness(crashed, listOf(call("fail", null, null, null, "reason" to "Stop.")))
        var snapshots = 0
        harness.screen.onSnapshot = { if (++snapshots == 2) harness.screen.current = Screens.chats() }
        val given = CompletableDeferred<Unit>()
        harness.goal.screenTurn = {
            given.await()
            false
        }

        launch { harness.run("Open Ana's chat") }
        advanceUntilIdle()
        assertTrue(harness.screen.actions.none { it is ScreenAction.Global })
        assertEquals(1, harness.goal.screenTurns)
        given.complete(Unit)
        advanceUntilIdle()

        assertTrue(harness.screen.actions.any { it is ScreenAction.Global })
    }

    // Plan 7E: a gate that holds the run at the crashed app's Back is no check of the observation's own: however many
    // pauses come there, the observation still closes the app once the run may act, and never ends the task as stuck.
    @Test
    fun pausesAtACrashedAppsBackUseUpNoneOfTheObservationsChecks() = runTest {
        val crashed = ScreenBuilder.screen("android", "Viber keeps stopping") { button("Close app", 300) }
        val harness = Harness(crashed, listOf(call("fail", null, null, null, "reason" to "Stop.")))
        repeat(3) { harness.owner.gates += Gate.RESUMED }
        harness.screen.onSnapshot = { if (harness.screen.actions.any { it is ScreenAction.Global }) harness.screen.current = Screens.chats() }

        val report = harness.run("Open Ana's chat")

        assertEquals(1, harness.screen.actions.count { it is ScreenAction.Global })
        assertEquals(EndReason.FAILED_BY_MODEL, report.end)
    }

    // The observation after a pause closes an app that crashed meanwhile with Back, which is an action on the screen; it
    // waits for the turn, because the owner's command may still act there.
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt`, replace:

```kotlin
    // A save that fails is reported; the view still shows the step, and the next step saves the conversation again.
```

with:

```kotlin
    // Plan 7E: a lock pauses the goal while the step that its gate let through still runs, here while Kestrel speaks. The
    // step may still act, so the round keeps the screen's turn and no instant command starts beside it; once the step has
    // ended, the round's next gate is a wait for the owner, and the turn goes. The round takes it again before it acts.
    @Test
    fun aPauseDuringAStepLetsGoOfTheTurnOnceTheStepHasEndedAndTheRoundTakesItAgainBeforeItActs() = scope.runTest {
        val speaking = CompletableDeferred<Unit>()
        text.onShow = { if (it == ChannelOutput.Said("On it.")) speaking.await() }
        val screen = FakeScreenTurn()
        val environment = environment(say("On it."), listSms, listSms, finish(3))
        val engine = engine(environment, screen = screen)
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        assertTrue(screen.holds)

        engine.signal(PhoneSignal.LOCKED)
        runCurrent()
        assertEquals(GoalPhase.PAUSED, engine.view.value.goal)
        assertTrue(screen.holds)
        val takes = screen.takes
        speaking.complete(Unit)
        runCurrent()
        assertFalse(screen.holds)
        assertEquals(takes, screen.takes)
        assertTrue(engine.view.value.records.single().data.isEmpty())

        engine.signal(PhoneSignal.UNLOCKED)
        runCurrent()
        engine.submit(input(InputChannel.TEXT, InputIntent.Answer(ChannelAnswer.Yes)))
        runCurrent()
        assertTrue(screen.takes > takes)
        assertEquals(EndReason.FINISHED, engine.view.value.records.single().end)
        assertFalse(screen.holds)
    }

    // Plan 7D's re-review, R5-1: a lock pauses the goal while its round waits inside a model call, with the screen's turn
    // held. No step can act then, so the engine has the round let go of the turn at once, and the owner's instant commands
    // work during the pause, before the model answers.
    @Test
    fun aPauseDuringAModelCallLetsGoOfTheTurnAtOnce() = scope.runTest {
        val answering = CompletableDeferred<Unit>()
        val screen = FakeScreenTurn()
        val environment = environment(listSms, listSms, finish(2)).apply { beforeCall = { if (it == 1) answering.await() } }
        val engine = engine(environment, screen = screen)
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        assertTrue(screen.holds)

        engine.signal(PhoneSignal.LOCKED)
        runCurrent()

        assertEquals(GoalPhase.PAUSED, engine.view.value.goal)
        assertFalse(screen.holds)
        answering.complete(Unit)
        runCurrent()
        assertFalse(screen.holds)
        assertTrue(engine.view.value.records.single().data.isEmpty())
    }

    // A save that fails is reported; the view still shows the step, and the next step saves the conversation again.
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `scripts\gradle.ps1 --continue :core:agent:test --tests=dev.kestrel.agent.ScreenTurnTest :core:conversation:test --tests=dev.kestrel.conversation.goal.ScreenTakingGoalTest --tests=dev.kestrel.conversation.engine.ConversationEngineTest`
Expected: FAIL: `ScreenTurnTest > aCrashedAppInTheFirstObservationIsClosedOnlyAfterTheGate` (the Back came before any turn, so the first `assertTrue` fails) and `ScreenTurnTest > pausesAtACrashedAppsBackUseUpNoneOfTheObservationsChecks` (the Back came before the pauses, at the first check); the compiler reports `Unresolved reference 'paused'` and `Unresolved reference 'resumed'` in `ScreenTakingGoalTest.kt` and `Unresolved reference 'beforeCall'` in `ConversationEngineTest.kt`.

- [ ] **Step 3: The observation's Back passes the gate**

In `core/agent/src/main/kotlin/dev/kestrel/agent/loop/FrontCheck.kt`, replace:

```kotlin
     * [pastKestrel]; another agent-free app ends the task; a Play services screen is handed over; a crashed app is closed
     * with back. Returns the snapshot to observe.
```

with:

```kotlin
     * [pastKestrel]; another agent-free app ends the task; a Play services screen is handed over; a crashed app is closed
     * with back once the pause gate lets the run act, as every action does (plan 7E). Returns the snapshot to observe.
```

In `core/agent/src/main/kotlin/dev/kestrel/agent/loop/FrontCheck.kt`, replace:

```kotlin
        var current = snapshot
        repeat(CHECKS) {
            current = pastKestrel(current) { run.fresh() }
            val front = ScreenTree.frontWindow(current)?.packageName
            when {
                run.settings.agentFree.isAgentFree(front) -> throw Ended(EndReason.AGENT_FREE, detail = run.label(front) ?: front)
                PlayServices.handsOver(current) -> run.handOverOnly(HandOver.of(HandOverKind.PLAY_SERVICES))
                crashed(current) -> {
                    run.ports.screen.act(ScreenAction.Global(GlobalKind.BACK))
                    appStopped = true
                }
                else -> return current
            }
            current = run.fresh()
        }
```

with:

```kotlin
        var current = snapshot
        var checks = 0
        while (checks < CHECKS) {
            current = pastKestrel(current) { run.fresh() }
            val front = ScreenTree.frontWindow(current)?.packageName
            when {
                run.settings.agentFree.isAgentFree(front) -> throw Ended(EndReason.AGENT_FREE, detail = run.label(front) ?: front)
                PlayServices.handsOver(current) -> run.handOverOnly(HandOver.of(HandOverKind.PLAY_SERVICES))
                crashed(current) -> {
                    // Back acts on the screen, so it passes the pause gate first (D2; plan 7E). A gate that held the run
                    // is no check: the screen, which may have changed meanwhile, is read again, and every check is kept.
                    if (run.gate()) {
                        current = run.fresh()
                        continue
                    }
                    run.ports.screen.act(ScreenAction.Global(GlobalKind.BACK))
                    appStopped = true
                }
                else -> return current
            }
            checks++
            current = run.fresh()
        }
```

- [ ] **Step 4: The round's one rule for a pause**

The tests hold a round inside its model call through the fixture. In `core/conversation/src/testFixtures/kotlin/dev/kestrel/conversation/testing/FakeGoalEnvironment.kt`, replace:

```kotlin
 * and one scripted model whose replies serve the conversation's rounds in order. [afterCall] runs after each model reply
 * with the number of requests so far: a test may steer or touch there. [sentenceOf] is the sentence of every report.
```

with:

```kotlin
 * and one scripted model whose replies serve the conversation's rounds in order. [beforeCall] runs before each model call
 * with that call's number (1, 2, …), and may hold the round there; [afterCall] runs after each model reply with the number
 * of requests so far: a test may steer or touch there. [sentenceOf] is the sentence of every report.
```

In `core/conversation/src/testFixtures/kotlin/dev/kestrel/conversation/testing/FakeGoalEnvironment.kt`, replace:

```kotlin
    var afterCall: (Int) -> Unit = {}

    private val watched: ModelPort = Watched(model) { afterCall(it) }
```

with:

```kotlin
    var beforeCall: suspend (Int) -> Unit = {}
    var afterCall: (Int) -> Unit = {}

    private val watched: ModelPort = Watched(model, { beforeCall(it) }) { afterCall(it) }
```

In `core/conversation/src/testFixtures/kotlin/dev/kestrel/conversation/testing/FakeGoalEnvironment.kt`, replace:

```kotlin
    private class Watched(private val scripted: ScriptedModel, private val after: (Int) -> Unit) : ModelPort {
        override val model: ModelInfo get() = scripted.model

        override suspend fun call(role: ModelRole, request: ChatRequest, step: Int): PricedResponse =
            scripted.call(role, request, step).also { after(scripted.requests.size) }
    }
```

with:

```kotlin
    private class Watched(
        private val scripted: ScriptedModel,
        private val before: suspend (Int) -> Unit,
        private val after: (Int) -> Unit,
    ) : ModelPort {
        override val model: ModelInfo get() = scripted.model

        override suspend fun call(role: ModelRole, request: ChatRequest, step: Int): PricedResponse {
            before(scripted.requests.size + 1)
            return scripted.call(role, request, step).also { after(scripted.requests.size) }
        }
    }
```

Then the rule itself.

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/goal/GoalEnvironment.kt`, replace:

```kotlin
 * hand-overs and pauses, and when it ends, on every path (`GoalRunner`, `TaskRun.waitForOwner`). `:app`'s `ScreenTurns`
 * (plan 7D) implements it.
 */
```

with:

```kotlin
 * hand-overs and pauses, and when it ends, on every path (`GoalRunner`, `TaskRun.waitForOwner`); a pause that the
 * conversation starts lets go of it as soon as no step that a gate let through can act any more, at once while the round
 * waits for its model (`ScreenTakingGoal`; plan 7E).
 * `:app`'s `ScreenTurns` (plan 7D) implements it.
 */
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/goal/ScreenTakingGoal.kt`, replace the whole file with:

```kotlin
package dev.kestrel.conversation.goal

import dev.kestrel.agent.goal.GoalPort

/**
 * A goal's round as its run sees it (design 6.5 and 7.1 of sub-project 7): the goal, whose screen turn goes through
 * [screen], the one rule for who acts on the screen, with the number of the owner's input that lets the round act now
 * (`RunningGoal.takeAct`) at the first turn after that input, and none at the turns after it.
 *
 * It is also the one rule for when a pause lets go of the round's turn (plan 7E). From the moment the run asks for the
 * turn ([takeScreen], at the round's start and as every wait for the owner ends) until its next model call starts
 * ([stepStarted]) or its next wait for the owner starts ([releaseScreen]), a step that a gate let through may act on the
 * screen: its action, the observation after it, and Kestrel's speech. A pause ([paused]) that comes then lets go of the
 * turn only when that span ends, so that no instant command starts beside the step; a pause that comes while the round
 * waits for its model lets go at once, so that the owner's instant commands work during it (plan 7D's re-review, R5-1). A
 * resume ([resumed]) before the span ends keeps the turn, because the goal acts again. Any thread may call [paused] and
 * [resumed].
 */
internal class ScreenTakingGoal(private val goal: RunningGoal, private val screen: ScreenTurn) : GoalPort by goal {
    private val lock = Any()

    // Guarded by lock: whether a step of the run may act on the screen now, and whether a pause waits for it to end.
    private var acting = false
    private var pauseWaits = false

    override suspend fun takeScreen(): Boolean {
        // A pause that comes while the turn is being taken waits too: the run acts right after the take.
        synchronized(lock) { acting = true }
        return screen.take(goal.takeAct())
    }

    // The run's model call comes next, and no step acts on the screen before the next gate takes the turn again.
    override fun stepStarted(step: Int) {
        goal.stepStarted(step)
        settle(release = false)
    }

    override fun releaseScreen() = settle(release = true)

    /** A lock or a call paused the goal (design 6.5 of sub-project 7). */
    fun paused() {
        synchronized(lock) {
            if (acting) pauseWaits = true else screen.release()
        }
    }

    /** The goal goes on after its pause: a release that waited for the step's end is no longer wanted. */
    fun resumed() {
        synchronized(lock) { pauseWaits = false }
    }

    // The span in which a step may act ends: the turn goes when the run lets go of it, or when a pause waited for the end.
    private fun settle(release: Boolean) {
        synchronized(lock) {
            if (release || pauseWaits) screen.release()
            acting = false
            pauseWaits = false
        }
    }

    override fun toString(): String = "ScreenTakingGoal($goal)"
}
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/goal/GoalRunner.kt`, replace:

```kotlin
    // The newest finished goal's steps and when it finished, for "save that as" (design D23 of sub-project 5).
    @Volatile
    private var kept: Pair<Recording, Long>? = null
```

with:

```kotlin
    // The newest finished goal's steps and when it finished, for "save that as" (design D23 of sub-project 5).
    @Volatile
    private var kept: Pair<Recording, Long>? = null

    // The running round's view of the screen's turn, which a pause and its resume reach (plan 7E).
    @Volatile
    private var turn: ScreenTakingGoal? = null
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/goal/GoalRunner.kt`, replace:

```kotlin
    /** "Save that as [name]": the newest finished goal's steps as a routine, asked through [owner]; null once saved. */
```

with:

```kotlin
    /**
     * A lock or a call paused the goal, a wait for the owner (design 6.5 of sub-project 7; plan 7E): the running round lets
     * go of the screen's turn once no step that a gate let through can act any more, at once while it waits for its model,
     * so that the owner's instant commands work during the pause (`ScreenTakingGoal`).
     */
    fun paused() {
        turn?.paused()
    }

    /** The paused goal goes on: a release that waited for the round's step is no longer wanted. */
    fun resumed() {
        turn?.resumed()
    }

    /** "Save that as [name]": the newest finished goal's steps as a routine, asked through [owner]; null once saved. */
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/goal/GoalRunner.kt`, replace:

```kotlin
        // The round runs until its agent reports, it is refused, it fails or it is cancelled; then finish ends it, once.
        val cancellation = try {
            // A round that a touch or a stop cancelled before it first ran prepares nothing.
            currentCoroutineContext().ensureActive()
            screen.take(goal.takeAct())
            when (val setup = environment.prepare(RoundStart(goal.command, plan.options, owner, ScreenTakingGoal(goal, screen), searched(plan), RoundPurpose.GOAL))) {
```

with:

```kotlin
        // Every turn of the round goes through its goal, the first and the last too, so that a pause has one rule.
        val taking = ScreenTakingGoal(goal, screen)
        turn = taking
        // The round runs until its agent reports, it is refused, it fails or it is cancelled; then finish ends it, once.
        val cancellation = try {
            // A round that a touch or a stop cancelled before it first ran prepares nothing.
            currentCoroutineContext().ensureActive()
            taking.takeScreen()
            when (val setup = environment.prepare(RoundStart(goal.command, plan.options, owner, taking, searched(plan), RoundPurpose.GOAL))) {
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/goal/GoalRunner.kt`, replace:

```kotlin
        } finally {
            // The round lets go of the screen's turn on every path, before its end reaches the listener (design 6.5).
            screen.release()
        }
```

with:

```kotlin
        } finally {
            // The round lets go of the screen's turn on every path, before its end reaches the listener (design 6.5).
            taking.releaseScreen()
            if (turn === taking) turn = null
        }
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
            ConversationEffect.PauseGoal -> owner.pause()
            is ConversationEffect.ResumePausedGoal -> {
                effect.act?.let(::actedFor)
                owner.resume()
            }
```

with:

```kotlin
            // The pause is a wait for the owner, which starts now; the round's turn goes once its step can act no more
            // (design 6.5 of sub-project 7; plan 7E).
            ConversationEffect.PauseGoal -> {
                owner.pause()
                goals.paused()
            }
            is ConversationEffect.ResumePausedGoal -> {
                effect.act?.let(::actedFor)
                goals.resumed()
                owner.resume()
            }
```

- [ ] **Step 5: Run the tests to see them pass**

Run: `scripts\gradle.ps1 :core:agent:test --tests=dev.kestrel.agent.ScreenTurnTest :core:conversation:test --tests=dev.kestrel.conversation.goal.ScreenTakingGoalTest --tests=dev.kestrel.conversation.engine.ConversationEngineTest --tests=dev.kestrel.conversation.goal.GoalRunnerTest`
Expected: PASS, with `ScreenTurnTest`'s 4 (plan 7D's `theObservationAfterAPauseClosesACrashedAppOnlyAfterTheTurn` among them), `ScreenTakingGoalTest`'s 2 and plan 7D's `GoalRunnerTest.theRoundLetsGoOfTheTurnOnEveryPath`.

- [ ] **Step 6: Run the three modules' tests, count them and stop Gradle**

Run: `scripts\gradle.ps1 :core:agent:test :core:conversation:test :app:testDebugUnitTest :core:conversation:check`, then `foreach ($dir in 'app\build\test-results\testDebugUnitTest', 'core\conversation\build\test-results\test', 'core\agent\build\test-results\test') { $n = 0; Get-ChildItem $dir -Filter *.xml | ForEach-Object { $n += [int]([xml](Get-Content $_.FullName -Raw)).testsuite.tests }; "$dir $n" }`, then `scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL; `app\build\test-results\testDebugUnitTest` A₀, `core\conversation\build\test-results\test` C₀ + 7, `core\agent\build\test-results\test` G₀ + 2.

- [ ] **Step 7: Commit**

```bash
git add core/agent/src/main/kotlin/dev/kestrel/agent/loop/FrontCheck.kt core/agent/src/test/kotlin/dev/kestrel/agent/ScreenTurnTest.kt core/conversation/src/testFixtures/kotlin/dev/kestrel/conversation/testing/FakeGoalEnvironment.kt core/conversation/src/main/kotlin/dev/kestrel/conversation/goal/GoalEnvironment.kt core/conversation/src/main/kotlin/dev/kestrel/conversation/goal/ScreenTakingGoal.kt core/conversation/src/main/kotlin/dev/kestrel/conversation/goal/GoalRunner.kt core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt core/conversation/src/test/kotlin/dev/kestrel/conversation/goal/ScreenTakingGoalTest.kt core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt
git commit -m "fix(agent, conversation): a pause lets go of the round's screen turn once no step that a gate let through can act, at once during a model call, and the observation's Back on a crashed app passes the gate (plan 7E; plan 7D's re-review, R5-1)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 1c: The view says when the round waits behind the owner's command

Design 6.5 of sub-project 7, as plan 7D built it: a round that takes the screen's turn while the owner's instant command runs, and does not act for an input of his that came after the command started, waits until the command has ended, because the command is his newest act (`ScreenTurns.take`). Meanwhile the bar belongs to the command (`OwnedBar` with `BarOwner.Commands`), so it cannot say that the goal waits, and a long command, such as a dictation, makes the goal look stuck (plan 7D's whole-plan re-review, round 4's nit, which plan 7D left to this plan). The round's turn tells its caller when it starts to wait behind such a command (`ScreenTurn.take`'s `waiting`), the round's goal tells its listener (`GoalListener.waitsBehindCommand`), and the conversation's view carries it (`ConversationView.waitsBehindCommand`), as it carries the round's step: Task 2's notification says it, and plan 7F's conversation screen can. A command that the round has asked to let go, for the owner's newer input, is no such wait: it ends within 3 s.

The name says "behind": the conversation already has `HoldReason.WAITING_FOR_COMMAND` and Listening `awaitingCommand`, which name the wait for the owner's command after "Yes?".

**Files:**
- Modify: `core/conversation/src/main/kotlin/dev/kestrel/conversation/goal/GoalEnvironment.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/goal/RunningGoal.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/goal/ScreenTakingGoal.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationView.kt`, `core/conversation/src/testFixtures/kotlin/dev/kestrel/conversation/testing/FakeScreenTurn.kt`, `app/src/main/kotlin/dev/kestrel/app/listen/ScreenTurns.kt`
- Test: `core/conversation/src/test/kotlin/dev/kestrel/conversation/goal/GoalRunnerTest.kt`, `core/conversation/src/test/kotlin/dev/kestrel/conversation/goal/ScreenTakingGoalTest.kt`, `core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt`, `app/src/test/kotlin/dev/kestrel/app/listen/ScreenTurnsTest.kt`, `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`

**Interfaces:**
- Consumes: plan 7D's `ScreenTurn`, `ScreenTurns` (`startCommand`, `stopped`, `take`, `release`, `commandEnded`), Task 1b's `ScreenTakingGoal` (through which every turn of a round goes), `RunningGoal.takeAct`, `GoalListener`, the engine's `Message.Step` and `roundStep`; Task 1's `ConversationView.hold` and `goalEnding`.
- Produces: `ScreenTurn.take(after: Long?, waiting: () -> Unit): Boolean`, whose [waiting] hears each start of a wait behind the owner's command that goes on; `GoalListener.waitsBehindCommand(waits: Boolean)` (must not block); `ConversationView.waitsBehindCommand: Boolean` (the last field, default false), true only while the open goal works (`ACTING`, `ASKING`, `HANDED_OVER`) and its round waits behind the owner's command; `FakeScreenTurn.command: CompletableDeferred<Unit>?`, the owner's command that the next take waits behind.

Counts: `:app` JVM A₀ + 1 (`ScreenTurnsTest` + 1). `:core:conversation` C₀ + 7 + 2 = C₀ + 9 (`GoalRunnerTest` + 1, `ConversationEngineTest` + 1). `:core:agent` G₀ + 2, unchanged.

- [ ] **Step 1: Write the failing tests**

In `app/src/test/kotlin/dev/kestrel/app/listen/ScreenTurnsTest.kt`, replace the whole file with:

```kotlin
package dev.kestrel.app.listen

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screen's turn, the one rule for who acts on the screen (spec 7.3; design 6.5 and 7.1 of sub-project 7; plan 7D). */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenTurnsTest {
    private val reports = mutableListOf<String>()
    private val turns = ScreenTurns({ what, _ -> reports += what })

    @Test
    fun withoutACommandTheScreenIsFreeAtOnce() = runTest {
        assertFalse(turns.held())

        assertFalse(turns.take(after = null) {})

        assertEquals(0L, currentTime)
    }

    // Re-review round 2, R2-1: a round that goes on by itself, or for an input of the owner's that came before the command,
    // never has the command cancelled; it waits as long as the command runs, because the command is his newest act.
    @Test
    fun aRoundWithoutANewerInputWaitsForTheRunningCommandWithoutCancellingIt() = runTest {
        for (after in listOf(null, 4L, 5L)) {
            val job = Job()
            var asked = 0
            turns.startCommand(job, since = 5) { asked++ }

            val taken = async { turns.take(after) {} }
            advanceTimeBy(60_000)
            runCurrent()
            assertFalse("$after", taken.isCompleted)
            assertEquals("$after", 0, asked)
            job.complete()
            runCurrent()

            assertTrue("$after", taken.await())
            assertFalse(turns.held())
            turns.release()
        }
        assertEquals(emptyList<String>(), reports)
    }

    // Plan 7E: a round that waits behind the owner's command, which goes on, says so as it starts to wait, so that the goal
    // notification can tell why the goal does not move while the bar belongs to the command. A round that has the command
    // cancelled, for the owner's newer input, waits only for the cancellation's end and says nothing.
    @Test
    fun aRoundSaysWhenItWaitsBehindTheOwnersCommandButNotWhenItHasItCancelled() = runTest {
        var waits = 0
        val job = Job()
        turns.startCommand(job, since = 5) {}

        val taken = async { turns.take(after = null) { waits++ } }
        runCurrent()
        assertEquals(1, waits)
        job.complete()
        assertTrue(taken.await())
        assertEquals(1, waits)
        turns.release()

        val cancelled = Job()
        turns.startCommand(cancelled, since = 5) { cancelled.complete() }
        assertTrue(turns.take(after = 6) { waits++ })
        assertEquals(1, waits)
    }

    // Re-review round 4, R4-1: while a round holds the turn, no instant command starts, whatever the Orchestrator reads of the
    // goal's phase; once the round lets go, as a wait for the owner starts or the round ends, commands start again.
    @Test
    fun aCommandCannotStartWhileARoundHoldsTheTurnAndCanAfterItLetsGo() = runTest {
        assertFalse(turns.take(after = null) {})
        assertTrue(turns.roundHolds())

        assertFalse(turns.startCommand(Job(), since = 5) {})
        assertFalse(turns.held())
        turns.release()

        assertFalse(turns.roundHolds())
        assertTrue(turns.startCommand(Job(), since = 5) {})
        assertTrue(turns.held())
    }

    // An input of the owner's that came after the command started chose the goal: the command is asked to let go, and the
    // round gets the screen once its job has really ended.
    @Test
    fun anOwnersInputAfterTheCommandStartedHasItCancelled() = runTest {
        val job = Job()
        var asked = 0
        turns.startCommand(job, since = 5) { asked++ }
        assertTrue(turns.held())

        val taken = async { turns.take(after = 6) {} }
        runCurrent()
        assertEquals(1, asked)
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(taken.isCompleted)
        job.complete()
        runCurrent()

        assertTrue(taken.await())
        assertFalse(turns.held())
        assertEquals(emptyList<String>(), reports)
    }

    @Test
    fun aCancelledCommandHoldsTheScreenUntilItsJobHasCompleted() = runTest {
        val finished = CompletableDeferred<Unit>()
        // Like a gesture or a binder call that Android finishes, the job ignores its cancellation for a while.
        val job = backgroundScope.launch { withContext(NonCancellable) { finished.await() } }
        runCurrent()
        turns.startCommand(job, since = 5) {}
        job.cancel()
        turns.stopped(job)

        val taken = async { turns.take(after = null) {} }
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(taken.isCompleted)
        assertTrue(turns.held())
        finished.complete(Unit)
        runCurrent()

        assertTrue(taken.await())
        assertFalse(turns.held())
        assertEquals(emptyList<String>(), reports)
    }

    // A round that waits for the owner's command gives it at most 3 s from the moment that it is cancelled, as "stop" does.
    @Test
    fun aWaitingRoundGivesACommandThatIsCancelledMeanwhileAtMostThreeSeconds() = runTest {
        val job = Job()
        turns.startCommand(job, since = 5) {}
        val taken = async { turns.take(after = null) {} }
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(taken.isCompleted)

        turns.stopped(job)
        advanceTimeBy(2_999)
        runCurrent()
        assertFalse(taken.isCompleted)
        advanceTimeBy(2)
        runCurrent()

        assertTrue(taken.await())
        assertEquals(listOf("A cancelled instant command"), reports)
    }

    @Test
    fun aStuckCommandHoldsTheScreenForAtMostThreeSecondsAndIsReportedOnce() = runTest {
        turns.startCommand(Job(), since = 5) {}

        val taken = async { turns.take(after = 6) {} }
        advanceTimeBy(2_999)
        runCurrent()
        assertFalse(taken.isCompleted)
        advanceTimeBy(2)
        runCurrent()

        assertTrue(taken.isCompleted)
        assertEquals(listOf("A cancelled instant command"), reports)
        // Kestrel goes on without it: nothing waits for it again.
        assertFalse(turns.held())
        assertFalse(turns.take(after = 7) {})
        assertEquals(1, reports.size)
    }
}
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
        val taken = async { turns.take(after = InputIds.next()) }
```

with:

```kotlin
        val taken = async { turns.take(after = InputIds.next()) {} }
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
        assertFalse(turns.take(after = null))
```

with:

```kotlin
        assertFalse(turns.take(after = null) {})
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
        val taken = async { turns.take(after = before) }
```

with:

```kotlin
        val taken = async { turns.take(after = before) {} }
```

In `core/conversation/src/testFixtures/kotlin/dev/kestrel/conversation/testing/FakeScreenTurn.kt`, replace the whole file with:

```kotlin
package dev.kestrel.conversation.testing

import dev.kestrel.conversation.goal.ScreenTurn
import kotlinx.coroutines.CompletableDeferred

/**
 * The screen's turn as the tests script it (design 6.5 of sub-project 7): [turn] answers each `take` with whether it
 * waited; the fake counts the takes and the releases, and whether the round holds the turn now. A test that sets
 * [command], the owner's instant command that goes on, has the next `take` wait behind it as `ScreenTurns` does: the take
 * says that it waits, waits until the command completes, and counts as a take that waited (plan 7E).
 */
class FakeScreenTurn(private val turn: suspend (after: Long?) -> Boolean = { false }) : ScreenTurn {
    var takes = 0
        private set
    var releases = 0
        private set
    var holds = false
        private set
    var command: CompletableDeferred<Unit>? = null

    override suspend fun take(after: Long?, waiting: () -> Unit): Boolean {
        takes++
        val behind = command?.let { running ->
            command = null
            waiting()
            running.await()
            true
        } ?: false
        val waited = turn(after)
        holds = true
        return waited || behind
    }

    override fun release() {
        releases++
        holds = false
    }

    override fun toString(): String = "FakeScreenTurn(takes=$takes, releases=$releases, holds=$holds, command=${command != null})"
}
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/goal/GoalRunnerTest.kt`, replace:

```kotlin
        var touches = 0

        override fun stepStarted(step: Int) {
```

with:

```kotlin
        var touches = 0
        val waits = mutableListOf<Boolean>()

        override fun stepStarted(step: Int) {
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/goal/GoalRunnerTest.kt`, replace:

```kotlin
        override fun touched() {
            touches++
        }
```

with:

```kotlin
        override fun touched() {
            touches++
        }

        override fun waitsBehindCommand(waits: Boolean) {
            this.waits += waits
        }
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/goal/ScreenTakingGoalTest.kt`, replace:

```kotlin
        override fun touched() = Unit
```

with:

```kotlin
        override fun touched() = Unit

        override fun waitsBehindCommand(waits: Boolean) = Unit
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/goal/GoalRunnerTest.kt`, replace:

```kotlin
    // Re-review round 4, R4-1: the round holds the screen's turn while it acts, and lets go of it when it ends, on every
```

with:

```kotlin
    // Plan 7E: a round whose turn waits behind the owner's instant command, at its start or as a wait for the owner ends,
    // tells its listener as the wait starts and once it has the turn, so that the goal notification can say why the goal
    // does not move while the bar belongs to the command.
    @Test
    fun aRoundThatWaitsBehindTheOwnersCommandTellsItsListenerUntilItHasTheTurn() = runTest {
        val screen = FakeScreenTurn()
        val command = CompletableDeferred<Unit>()
        screen.command = command
        val goal = RunningGoal(1, 1, emptyMap(), emptyList(), listener)

        runner(environment(listSms, finish(1)), screen).run(this, goal, plan(record()), FakeOwner())
        advanceUntilIdle()
        assertEquals(listOf(true), listener.waits)
        assertTrue(listener.ends.isEmpty())
        command.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(true, false), listener.waits)
        assertEquals(EndReason.FINISHED, listener.ends.single().report.end)
    }

    // Re-review round 4, R4-1: the round holds the screen's turn while it acts, and lets go of it when it ends, on every
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt`, replace:

```kotlin
    // A save that fails is reported; the view still shows the step, and the next step saves the conversation again.
```

with:

```kotlin
    // Plan 7E: while the goal's round waits behind the owner's instant command, the view says so, for the goal
    // notification, because the bar belongs to the command then; once the round has the turn, it no longer does.
    @Test
    fun theViewSaysWhileTheRoundWaitsBehindTheOwnersCommand() = scope.runTest {
        val screen = FakeScreenTurn()
        val command = CompletableDeferred<Unit>()
        screen.command = command
        val engine = engine(environment(listSms, finish(1)), screen = screen)
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        assertEquals(GoalPhase.ACTING, engine.view.value.goal)
        assertTrue(engine.view.value.waitsBehindCommand)

        command.complete(Unit)
        runCurrent()
        assertFalse(engine.view.value.waitsBehindCommand)
        assertEquals(EndReason.FINISHED, engine.view.value.records.single().end)
    }

    // A save that fails is reported; the view still shows the step, and the next step saves the conversation again.
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `scripts\gradle.ps1 --continue :core:conversation:test --tests=dev.kestrel.conversation.goal.GoalRunnerTest --tests=dev.kestrel.conversation.engine.ConversationEngineTest :app:testDebugUnitTest --tests=dev.kestrel.app.listen.ScreenTurnsTest --tests=dev.kestrel.app.listen.ListeningTest`
Expected: FAIL; among its errors, the compiler reports `'take' overrides nothing` in `FakeScreenTurn.kt`, `'waitsBehindCommand' overrides nothing` in `GoalRunnerTest.kt` and `ScreenTakingGoalTest.kt` and `Unresolved reference 'waitsBehindCommand'` in `ConversationEngineTest.kt`, and `Too many arguments` (the trailing lambda of `take`) in `ScreenTurnsTest.kt` and `ListeningTest.kt`.

- [ ] **Step 3: The turn says when it waits behind the owner's command**

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/goal/GoalEnvironment.kt`, replace:

```kotlin
 * `:app`'s `ScreenTurns` (plan 7D) implements it.
 */
interface ScreenTurn {
    suspend fun take(after: Long?): Boolean
```

with:

```kotlin
 * [waiting] hears it each time a [take] starts to wait for an instant command that goes on, the owner's newest act, so
 * that the conversation's view can say why the goal does not move (plan 7E); a command that the round has asked to let go
 * is no such wait. `:app`'s `ScreenTurns` (plan 7D) implements it.
 */
interface ScreenTurn {
    suspend fun take(after: Long?, waiting: () -> Unit): Boolean
```

In `app/src/main/kotlin/dev/kestrel/app/listen/ScreenTurns.kt`, replace:

```kotlin
 *   newest act, as during a hand-over (D15 of sub-project 5), so the round waits until it has ended. A cancelled job that
```

with:

```kotlin
 *   newest act, as during a hand-over (D15 of sub-project 5), so the round waits until it has ended, and says so as it
 *   starts to wait, for the goal notification (plan 7E). A cancelled job that
```

In `app/src/main/kotlin/dev/kestrel/app/listen/ScreenTurns.kt`, replace:

```kotlin
    suspend fun commandEnded(): Boolean = waitFor(null)
```

with:

```kotlin
    suspend fun commandEnded(): Boolean = waitFor(null) {}
```

In `app/src/main/kotlin/dev/kestrel/app/listen/ScreenTurns.kt`, replace:

```kotlin
     * act now, or null when it goes on by itself.
     */
    override suspend fun take(after: Long?): Boolean {
```

with:

```kotlin
     * act now, or null when it goes on by itself. [waiting] hears it each time the round starts to wait for a command that
     * goes on (plan 7E).
     */
    override suspend fun take(after: Long?, waiting: () -> Unit): Boolean {
```

In `app/src/main/kotlin/dev/kestrel/app/listen/ScreenTurns.kt`, replace:

```kotlin
            waited = waitFor(after) || waited
```

with:

```kotlin
            waited = waitFor(after, waiting) || waited
```

In `app/src/main/kotlin/dev/kestrel/app/listen/ScreenTurns.kt`, replace:

```kotlin
    private suspend fun waitFor(after: Long?): Boolean {
```

with:

```kotlin
    private suspend fun waitFor(after: Long?, waiting: () -> Unit): Boolean {
```

In `app/src/main/kotlin/dev/kestrel/app/listen/ScreenTurns.kt`, replace:

```kotlin
                // The owner's command goes on: the caller waits until it ends, or until something cancels it.
                select {
```

with:

```kotlin
                // The owner's command goes on: the caller waits until it ends, or until something cancels it.
                waiting()
                select {
```

- [ ] **Step 4: The round's goal tells its listener, and the view carries it**

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/goal/RunningGoal.kt`, replace:

```kotlin
    /** The touch watcher saw the owner's touch. */
    fun touched()
```

with:

```kotlin
    /** The touch watcher saw the owner's touch. */
    fun touched()

    /**
     * The round's screen turn waits behind the owner's instant command, which goes on ([waits] true), or no longer does
     * (plan 7E): the view says so, for the goal notification, because the bar belongs to the command then. Must not block.
     */
    fun waitsBehindCommand(waits: Boolean)
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/goal/ScreenTakingGoal.kt`, replace:

```kotlin
 * (`RunningGoal.takeAct`) at the first turn after that input, and none at the turns after it.
 *
```

with:

```kotlin
 * (`RunningGoal.takeAct`) at the first turn after that input, and none at the turns after it. While a turn waits behind
 * the owner's instant command, the goal's listener knows it (plan 7E).
 *
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/goal/ScreenTakingGoal.kt`, replace:

```kotlin
        synchronized(lock) { acting = true }
        return screen.take(goal.takeAct())
    }
```

with:

```kotlin
        synchronized(lock) { acting = true }
        var behind = false
        try {
            return screen.take(goal.takeAct()) {
                if (!behind) {
                    behind = true
                    goal.listener.waitsBehindCommand(true)
                }
            }
        } finally {
            if (behind) goal.listener.waitsBehindCommand(false)
        }
    }
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
        /** Step [step] of the running round of record [record] of [conversation] started; it only changes the view. */
        class Step(val conversation: ConversationId, val record: Int, val step: Int) : Message
```

with:

```kotlin
        /** Step [step] of the running round of record [record] of [conversation] started; it only changes the view. */
        class Step(val conversation: ConversationId, val record: Int, val step: Int) : Message

        /**
         * The running round of record [record] of [conversation] waits behind the owner's instant command, or no longer
         * ([waits]); it only changes the view (plan 7E).
         */
        class BehindCommand(val conversation: ConversationId, val record: Int, val waits: Boolean) : Message
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
    // The running round's newest step, which the view and the snapshot add to the goal's earlier steps (design 10.5).
    private var roundStep: Int? = null
```

with:

```kotlin
    // The running round's newest step, which the view and the snapshot add to the goal's earlier steps (design 10.5).
    private var roundStep: Int? = null

    // Whether the running round waits behind the owner's instant command, for the view (plan 7E).
    private var roundBehindCommand = false
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
                is Message.Step -> if (message.conversation == recordsOf && running?.record == message.record) {
                    roundStep = message.step
                    publish()
                }
```

with:

```kotlin
                is Message.Step -> if (message.conversation == recordsOf && running?.record == message.record) {
                    roundStep = message.step
                    publish()
                }
                is Message.BehindCommand -> if (message.conversation == recordsOf && running?.record == message.record) {
                    roundBehindCommand = message.waits
                    publish()
                }
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
        running = goal
        roundStep = null
        roundLedgerFrom = 0
```

with:

```kotlin
        running = goal
        roundStep = null
        roundBehindCommand = false
        roundLedgerFrom = 0
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
        roundStep = null
        roundLedgerFrom = record.ledger.size
```

with:

```kotlin
        roundStep = null
        roundBehindCommand = false
        roundLedgerFrom = record.ledger.size
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
        override fun touched() = this@ConversationEngine.touched()
```

with:

```kotlin
        override fun touched() = this@ConversationEngine.touched()

        override fun waitsBehindCommand(waits: Boolean) {
            inbox.trySend(Message.BehindCommand(conversation, record, waits))
        }
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
            hold = current.goal?.hold,
            goalEnding = current.goal?.ending,
            goalRecord = current.goal?.record,
        )
```

with:

```kotlin
            hold = current.goal?.hold,
            goalEnding = current.goal?.ending,
            goalRecord = current.goal?.record,
            waitsBehindCommand = own && roundBehindCommand && current.goal?.phase in WORKING,
        )
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationView.kt`, replace:

```kotlin
 * goal's record number ([goalRecord], which with [id] names the goal across Kestrel's processes, for the notification's
 * buttons; plan 7E). toString prints no words.
```

with:

```kotlin
 * goal's record number ([goalRecord], which with [id] names the goal across Kestrel's processes, for the notification's
 * buttons), and whether the working goal's round waits behind the owner's instant command ([waitsBehindCommand], which the
 * goal notification says, because the bar belongs to the command then; plan 7E). toString prints no words.
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationView.kt`, replace:

```kotlin
    val goalRecord: Int? = null,
) {
```

with:

```kotlin
    val goalRecord: Int? = null,
    val waitsBehindCommand: Boolean = false,
) {
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationView.kt`, replace:

```kotlin
            "kept=$keptForOwner, own=$ownRequest, hold=$hold, ending=$goalEnding, goalRecord=$goalRecord)"
```

with:

```kotlin
            "kept=$keptForOwner, own=$ownRequest, hold=$hold, ending=$goalEnding, goalRecord=$goalRecord, behind=$waitsBehindCommand)"
```

- [ ] **Step 5: Run the tests to see them pass**

Run: `scripts\gradle.ps1 :core:conversation:test --tests=dev.kestrel.conversation.goal.GoalRunnerTest --tests=dev.kestrel.conversation.engine.ConversationEngineTest :app:testDebugUnitTest --tests=dev.kestrel.app.listen.ScreenTurnsTest --tests=dev.kestrel.app.listen.ListeningTest`
Expected: PASS, with `ScreenTurnsTest`'s 8.

- [ ] **Step 6: Run the three modules' tests, compile the device tests, count them and stop Gradle**

Run: `scripts\gradle.ps1 :core:agent:test :core:conversation:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :core:conversation:check`, then `foreach ($dir in 'app\build\test-results\testDebugUnitTest', 'core\conversation\build\test-results\test', 'core\agent\build\test-results\test') { $n = 0; Get-ChildItem $dir -Filter *.xml | ForEach-Object { $n += [int]([xml](Get-Content $_.FullName -Raw)).testsuite.tests }; "$dir $n" }`, then `scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL; `app\build\test-results\testDebugUnitTest` A₀ + 1, `core\conversation\build\test-results\test` C₀ + 9, `core\agent\build\test-results\test` G₀ + 2.

- [ ] **Step 7: Commit**

```bash
git add core/conversation/src app/src/main/kotlin/dev/kestrel/app/listen/ScreenTurns.kt app/src/test/kotlin/dev/kestrel/app/listen/ScreenTurnsTest.kt app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt
git commit -m "feat(conversation, app): the screen's turn says when a goal's round waits behind the owner's instant command, and the conversation's view carries it for the goal notification (plan 7E; plan 7D's re-review, round 4's nit)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 2: The notification's words and buttons

Design 10.6 of sub-project 7 and the main spec's section 15: the goal notification says "Kestrel is working" with the current checklist item and a progress bar, and has Stop and Open; with the goal held, it asks "Continue the goal?" with Cancel and Continue, in that order, so that a tap meant as Stop lands on Cancel when a touch holds the goal while the owner reaches for Stop (the owner's decision of 2026-11-25). `GoalNotices` builds that from the conversation's view, apart from Android, so that a JVM test checks it; `GoalAction` names each button with the owner's input that it gives, and whether it resumes what Kestrel does, which Task 5 makes ask for the owner's unlock. After the goal's end, while its final reply is said or waits for the owner's return, the notification says "Kestrel finished the goal". While the working goal's round waits behind the owner's instant command (Task 1c), the notification says "Waiting for your command to finish" in place of the current item, because the bar shows the command's own text then. A tap on a button is an input of the owner's like a spoken or typed one (plan 7D): `GoalButtons.input` gives it the next number of the owner's inputs (`InputIds.next`) at the moment of the tap, so that a Continue that resumes the goal is his newest act, and an instant command that he started before the tap lets the round have the screen (plan 7D's `ScreenTurns`). It counts only for the goal that the notice shows, which each notice names by its conversation and its record (`NoticeGoal`, from the view's `id` and `goalRecord`), because a command number belongs to one process and changes at every resume: a tap for another goal, also one that comes late after a goal ended and a new one opened, is nothing. A button that stops counts while the goal's notice offers any way to stop, because the owner's tap on Stop can arrive after a touch (his pull of the shade) held the goal and the notice offers Cancel instead; Continue counts only while the notice offers Continue. `GoalButtons.press` decides a tap after the conversation's restore, so that a tap that started Kestrel's process (on the held goal's notification that Kestrel posted without the service, which outlives the process) is judged against the restored goal and not lost; it waits at most `RESTORE_WAIT_MILLIS` (5 s, well inside the 10 s that Android gives a broadcast), and drops a tap whose restore took longer, with a report of its kind only. Each goal's buttons get PendingIntents of their own: the goal's `key` goes into the broadcast's data (Task 5), which Android compares when it matches PendingIntents, so a newer goal's notice never rewrites an older notice's button.

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/goal/GoalTexts.kt`, `app/src/main/kotlin/dev/kestrel/app/goal/GoalNotice.kt`
- Test: `app/src/test/kotlin/dev/kestrel/app/goal/GoalNoticesTest.kt`

**Interfaces:**
- Consumes: Task 1's `ConversationView.hold` and `goalRecord`, `ConversationTexts.held`, `ConversationTexts.CONTINUE_GOAL`; Task 1c's `ConversationView.waitsBehindCommand`; plan 7B's `ConversationView` (`id`, `goal`, `checklist`, `replyChannel`), `ConversationId`, `ChecklistSummary(done, total, current)`, `GoalState.HELD`, `OwnerInput(id, channel, intent, atMillis)`, `InputChannel`, `InputIntent.Stop` and `InputIntent.Continue`.
- Produces (package `dev.kestrel.app.goal`, all `internal`): `object GoalTexts { CHANNEL = "Goals"; WORKING = "Kestrel is working"; BEHIND_COMMAND = "Waiting for your command to finish"; FINISHED = "Kestrel finished the goal"; PUBLIC = "Kestrel is working on a task"; STOP = "Stop"; OPEN = "Open"; CONTINUE = "Continue"; CANCEL = "Cancel" }`; `enum class GoalAction(val label: String, val intent: InputIntent?, val resumes: Boolean) { STOP, OPEN, CONTINUE, CANCEL }` (only `CONTINUE` resumes) with `val stops: Boolean` (`STOP` and `CANCEL`), `val broadcast: String` (`"dev.kestrel.goal.<NAME>"`) and `GoalAction.ofBroadcast(action: String?): GoalAction?`; `enum class NoticeKind { WORKING, HELD, CLOSING }`; `data class NoticeProgress(val done: Int, val total: Int)`; `data class NoticeGoal(val conversation: ConversationId, val record: Int)` with `val key: String` (`"<conversation>/<record>"`) and `NoticeGoal.ofKey(key: String?): NoticeGoal?`; `data class GoalNotice(val kind: NoticeKind, val title: String, val text: String?, val progress: NoticeProgress?, val actions: List<GoalAction>, val goal: NoticeGoal? = null)`; `object GoalNotices { val CLOSING: GoalNotice; fun of(view: ConversationView): GoalNotice? }`; a held notice's actions are `[CANCEL, CONTINUE]`; `object GoalButtons { fun input(broadcast: String?, goal: NoticeGoal?, shown: GoalNotice?, channel: InputChannel, next: () -> Long, atMillis: Long): OwnerInput?; suspend fun press(broadcast: String?, goal: NoticeGoal?, restored: suspend () -> ConversationView, next: () -> Long, now: () -> Long, report: (String, Throwable) -> Unit): OwnerInput?; const val RESTORE_WAIT_MILLIS = 5_000L }`.

Counts: `:app` JVM A₀ + 1 + 10 = A₀ + 11 (`GoalNoticesTest` 10).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/dev/kestrel/app/goal/GoalNoticesTest.kt`:

```kotlin
package dev.kestrel.app.goal

import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.engine.ChecklistSummary
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.GoalHold
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The goal notification's words and buttons (design 10.6 of sub-project 7; plan 7E). */
class GoalNoticesTest {
    private val working = listOf(GoalAction.STOP, GoalAction.OPEN)
    private val id = ConversationId("0123456789abcdef0123456789abcdef")
    private val goal = NoticeGoal(id, 2)

    // The open goal of record 2 in the conversation [id], in [phase].
    private fun view(phase: GoalPhase, hold: GoalHold? = null) = ConversationView(id = id, goal = phase, hold = hold, goalRecord = 2)

    @Test
    fun aWorkingGoalShowsItsCurrentItemAndProgressWithStopAndOpen() {
        val notice = GoalNotices.of(ConversationView(goal = GoalPhase.ACTING, checklist = ChecklistSummary(3, 7, "Reply to Mila")))
        assertEquals(GoalNotice(NoticeKind.WORKING, "Kestrel is working", "Reply to Mila", NoticeProgress(3, 7), working), notice)
        // The item may hold a name, so the notice prints none.
        assertFalse(notice.toString().contains("Mila"))

        // Without a checklist there is neither an item nor a bar; a goal that asks or has handed a step over still works.
        val bare = GoalNotice(NoticeKind.WORKING, "Kestrel is working", null, null, working)
        assertEquals(bare, GoalNotices.of(ConversationView(goal = GoalPhase.ASKING)))
        assertEquals(bare, GoalNotices.of(ConversationView(goal = GoalPhase.HANDED_OVER, checklist = ChecklistSummary(0, 0, null))))
        assertNull(GoalNotices.of(ConversationView()))
    }

    // Plan 7E: the bar belongs to the owner's instant command while the round waits behind it, so the notification says
    // why the goal does not move, in place of the current item; the progress and the buttons stay.
    @Test
    fun aRoundThatWaitsBehindTheOwnersCommandSaysSoInPlaceOfItsItem() {
        val view = ConversationView(goal = GoalPhase.ACTING, checklist = ChecklistSummary(3, 7, "Reply to Mila"), waitsBehindCommand = true)
        assertEquals(
            GoalNotice(NoticeKind.WORKING, "Kestrel is working", "Waiting for your command to finish", NoticeProgress(3, 7), working),
            GoalNotices.of(view),
        )
    }

    @Test
    fun aHeldGoalAsksToContinueAndSaysHowForItsHoldWithCancelFirst() {
        // Cancel first: a tap meant as Stop, while a touch holds the goal, lands on Cancel (the owner's decision).
        val held = listOf(GoalAction.CANCEL, GoalAction.CONTINUE)
        assertEquals(
            GoalNotice(NoticeKind.HELD, "Continue the goal?", ConversationTexts.GO_ON, null, held),
            GoalNotices.of(ConversationView(goal = GoalPhase.PAUSED, hold = GoalHold.LOCK, checklist = ChecklistSummary(3, 7, "Reply to Mila"))),
        )
        assertEquals(ConversationTexts.GO_ON, GoalNotices.of(ConversationView(goal = GoalPhase.PAUSED_AT_LIMIT, hold = GoalHold.LIMIT))?.text)
        assertEquals(ConversationTexts.LEAVE_KESTREL, GoalNotices.of(ConversationView(goal = GoalPhase.INTERRUPTED, hold = GoalHold.KESTREL_SCREEN))?.text)
        assertEquals(ConversationTexts.CONTINUE_IN_NOTIFICATION, GoalNotices.of(ConversationView(goal = GoalPhase.INTERRUPTED, hold = GoalHold.BACKGROUND_REFUSED))?.text)
    }

    @Test
    fun eachButtonGivesItsOwnInputAndOpenSendsNoBroadcast() {
        assertEquals(GoalAction.CONTINUE, GoalAction.ofBroadcast(GoalAction.CONTINUE.broadcast))
        // Design 6.4: the notification's Stop and the held goal's Cancel both stop the goal.
        assertEquals(InputIntent.Stop, GoalAction.ofBroadcast(GoalAction.CANCEL.broadcast)?.intent)
        assertEquals(InputIntent.Stop, GoalAction.ofBroadcast(GoalAction.STOP.broadcast)?.intent)
        assertNull(GoalAction.ofBroadcast(GoalAction.OPEN.broadcast))
        assertNull(GoalAction.ofBroadcast("dev.kestrel.listen.STOP"))
        assertNull(GoalAction.ofBroadcast(null))
    }

    // Plan 7E with plan 7D's ScreenTurns: a tap on a button that the notification shows is the owner's input of that
    // moment, with the next number of his inputs, so that a Continue that resumes the goal has an instant command that he
    // started before the tap let go of the screen; a button that counts for nothing takes no number.
    @Test
    fun aShownButtonIsTheOwnersNewestInputAndAStaleContinueIsNothing() {
        var newest = 41L
        val held = GoalNotices.of(view(GoalPhase.INTERRUPTED, GoalHold.TOUCH))

        assertEquals(
            OwnerInput(42, InputChannel.TEXT, InputIntent.Continue, 7),
            GoalButtons.input(GoalAction.CONTINUE.broadcast, goal, held, InputChannel.TEXT, { ++newest }, 7),
        )
        assertEquals(
            OwnerInput(43, InputChannel.VOICE, InputIntent.Stop, 8),
            GoalButtons.input(GoalAction.CANCEL.broadcast, goal, held, InputChannel.VOICE, { ++newest }, 8),
        )
        // A Continue from a notification that the working goal replaced, and Open, which sends no broadcast.
        val working = GoalNotices.of(view(GoalPhase.ACTING))
        assertNull(GoalButtons.input(GoalAction.CONTINUE.broadcast, goal, working, InputChannel.TEXT, { ++newest }, 9))
        assertNull(GoalButtons.input(GoalAction.OPEN.broadcast, goal, working, InputChannel.TEXT, { ++newest }, 9))
        assertNull(GoalButtons.input(GoalAction.STOP.broadcast, goal, null, InputChannel.TEXT, { ++newest }, 9))
        assertEquals(43L, newest)
    }

    // The review of plan 7E (I2): the owner's pull of the shade is a touch, which holds the goal, so his tap on Stop can
    // arrive after the held notice, which offers Cancel, replaced the working one. Any button that stops counts while the
    // goal's notice offers a way to stop; Continue needs its own button.
    @Test
    fun aStoppingButtonCountsWhileTheGoalsNoticeOffersAnyWayToStop() {
        val held = GoalNotices.of(view(GoalPhase.INTERRUPTED, GoalHold.TOUCH))
        val working = GoalNotices.of(view(GoalPhase.ACTING))

        assertEquals(InputIntent.Stop, GoalButtons.input(GoalAction.STOP.broadcast, goal, held, InputChannel.TEXT, { 1 }, 0)?.intent)
        assertEquals(InputIntent.Stop, GoalButtons.input(GoalAction.CANCEL.broadcast, goal, working, InputChannel.TEXT, { 1 }, 0)?.intent)
        // The notice after the goal's end offers nothing, so nothing stops.
        assertNull(GoalButtons.input(GoalAction.STOP.broadcast, null, GoalNotices.CLOSING, InputChannel.TEXT, { 1 }, 0))
    }

    // The review of plan 7E (M2): each notice names its goal by its conversation and its record, which outlive a resume and
    // Kestrel's process, and a button counts only for that goal: a late tap meant for another goal is nothing.
    @Test
    fun aButtonCountsOnlyForTheGoalThatItsNoticeShowed() {
        val held = GoalNotices.of(view(GoalPhase.INTERRUPTED, GoalHold.TOUCH))
        assertEquals(goal, held?.goal)
        assertNull(GoalNotices.CLOSING.goal)

        assertNull(GoalButtons.input(GoalAction.CONTINUE.broadcast, NoticeGoal(id, 1), held, InputChannel.TEXT, { 1 }, 0))
        assertNull(GoalButtons.input(GoalAction.CANCEL.broadcast, NoticeGoal(ConversationId("fedcba9876543210fedcba9876543210"), 2), held, InputChannel.TEXT, { 1 }, 0))
        assertNull(GoalButtons.input(GoalAction.CANCEL.broadcast, null, held, InputChannel.TEXT, { 1 }, 0))
    }

    // The review of plan 7E (M3): a tap on the held goal's notification that Kestrel posted without the service can start
    // Kestrel's process. The press waits until the conversation is restored, and decides against the restored goal's
    // notice, so the tap is neither lost nor judged against the empty view before the restore.
    @Test
    fun aTapThatStartsKestrelsProcessWaitsForTheRestoreAndCountsForTheRestoredGoal() = runTest {
        val restored = CompletableDeferred<ConversationView>()
        val pressed = async { GoalButtons.press(GoalAction.CONTINUE.broadcast, goal, { restored.await() }, { 42 }, { 7 }) { _, error -> throw error } }
        runCurrent()
        assertFalse(pressed.isCompleted)

        restored.complete(view(GoalPhase.INTERRUPTED, GoalHold.RESTART).copy(replyChannel = InputChannel.TEXT))

        assertEquals(OwnerInput(42, InputChannel.TEXT, InputIntent.Continue, 7), pressed.await())
    }

    // The second review of plan 7E (R2-M1): a restore that does not end in time would hold the broadcast past Android's
    // limit. The press gives up after RESTORE_WAIT_MILLIS, drops the tap, takes no number and reports only its kind.
    @Test
    fun aTapWhoseRestoreDoesNotEndInTimeIsDroppedAndReportedByItsKind() = runTest {
        val reports = mutableListOf<String>()
        var numbers = 0L
        val pressed = async {
            GoalButtons.press(GoalAction.CANCEL.broadcast, goal, { awaitCancellation() }, { ++numbers }, { 7 }) { what, _ -> reports += what }
        }
        advanceTimeBy(GoalButtons.RESTORE_WAIT_MILLIS - 1)
        runCurrent()
        assertFalse(pressed.isCompleted)
        advanceTimeBy(2)

        assertNull(pressed.await())
        assertEquals(0L, numbers)
        assertEquals(listOf("A goal notification's button"), reports)
    }

    // The second review of plan 7E (R2-I1): each goal has a key of its own, which its buttons' broadcasts carry as their
    // data, so that Android gives every goal's buttons PendingIntents of their own; the key names the goal again.
    @Test
    fun eachGoalHasAKeyOfItsOwnThatNamesItAgain() {
        assertEquals(goal, NoticeGoal.ofKey(goal.key))
        assertNotEquals(goal.key, NoticeGoal(id, 3).key)
        assertNotEquals(goal.key, NoticeGoal(ConversationId("fedcba9876543210fedcba9876543210"), 2).key)
        assertNull(NoticeGoal.ofKey("0123456789abcdef0123456789abcdef/0"))
        assertNull(NoticeGoal.ofKey("not a goal/2"))
        assertNull(NoticeGoal.ofKey(null))
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.goal.GoalNoticesTest`
Expected: FAIL, the compiler reports `Unresolved reference 'GoalNotices'`.

- [ ] **Step 3: The words and the notice**

Create `app/src/main/kotlin/dev/kestrel/app/goal/GoalTexts.kt`:

```kotlin
package dev.kestrel.app.goal

/**
 * The goal notification's own words (design 10.6 of sub-project 7; main spec, section 15), in English and built by code.
 * A held goal's question and how to go on are the conversation's (`ConversationTexts`).
 */
internal object GoalTexts {
    /** The notification channel's name in Android's settings. */
    const val CHANNEL = "Goals"
    const val WORKING = "Kestrel is working"

    /**
     * In place of the current item while the goal's round waits behind the owner's instant command, whose own text the bar
     * shows then (plan 7E).
     */
    const val BEHIND_COMMAND = "Waiting for your command to finish"

    /** After the goal's end, while its final reply is spoken or shown, or waits for the owner's return (design 6.4). */
    const val FINISHED = "Kestrel finished the goal"

    /** The lock screen's version: the current checklist item may hold names. */
    const val PUBLIC = "Kestrel is working on a task"
    const val STOP = "Stop"
    const val OPEN = "Open"
    const val CONTINUE = "Continue"
    const val CANCEL = "Cancel"
}
```

Create `app/src/main/kotlin/dev/kestrel/app/goal/GoalNotice.kt`:

```kotlin
package dev.kestrel.app.goal

import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.GoalState
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A button of the goal notification (design 10.6 of sub-project 7): [STOP] and [CANCEL] stop the goal, as "stop" does,
 * and [CONTINUE] goes on with a held goal, as "Kestrel, continue" does (design 6.4); each reaches Kestrel as a broadcast
 * with the action [broadcast], which only Kestrel's own notification can send. [OPEN] opens Kestrel's screen and gives no
 * input. A button that [resumes] what Kestrel does asks Android for the owner's unlock first, so that nobody resumes a goal
 * from the lock screen (the owner's decision of 2026-11-25); a button that stops Kestrel needs none.
 */
internal enum class GoalAction(val label: String, val intent: InputIntent?, val resumes: Boolean) {
    STOP(GoalTexts.STOP, InputIntent.Stop, resumes = false),
    OPEN(GoalTexts.OPEN, null, resumes = false),
    CONTINUE(GoalTexts.CONTINUE, InputIntent.Continue, resumes = true),
    CANCEL(GoalTexts.CANCEL, InputIntent.Stop, resumes = false),
    ;

    /** Whether the button stops the goal: Stop while it works, Cancel while it is held (design 6.4). */
    val stops: Boolean get() = intent == InputIntent.Stop

    /** The action of the broadcast that the button sends. */
    val broadcast: String get() = "dev.kestrel.goal.$name"

    companion object {
        /** The button whose broadcast has the action [action]; null for any other action, and for [OPEN]. */
        fun ofBroadcast(action: String?): GoalAction? = entries.firstOrNull { it.intent != null && it.broadcast == action }
    }
}

/** What the notification shows: a goal that works, a held goal, or the end of a goal whose final reply is said or waits. */
internal enum class NoticeKind { WORKING, HELD, CLOSING }

/** The progress bar: [done] of [total] checklist items. */
internal data class NoticeProgress(val done: Int, val total: Int)

/**
 * The goal that a notice shows, by its [conversation] and its [record] number there: one name for the goal across its
 * resumes and Kestrel's processes, which a command number is not (it belongs to one process and changes at every resume).
 * The buttons' broadcasts carry it, so that a tap counts only for the goal that its notice showed (plan 7E).
 */
internal data class NoticeGoal(val conversation: ConversationId, val record: Int) {
    /** The goal as one text, `<conversation>/<record>`, which the buttons' broadcasts carry as their data. */
    val key: String get() = "${conversation.value}/$record"

    override fun toString(): String = "NoticeGoal(record=$record)"

    companion object {
        /** The goal that [key] names; null for any other text. */
        fun ofKey(key: String?): NoticeGoal? {
            val (conversation, record) = key?.split('/')?.takeIf { it.size == 2 } ?: return null
            val number = record.toIntOrNull()?.takeIf { it > 0 } ?: return null
            return runCatching { NoticeGoal(ConversationId(conversation), number) }.getOrNull()
        }
    }
}

/**
 * The goal notification's words and buttons, apart from Android, for the [goal] that it shows (none after the goal's end).
 * toString prints no words: [text] may hold names.
 */
internal data class GoalNotice(
    val kind: NoticeKind,
    val title: String,
    val text: String?,
    val progress: NoticeProgress?,
    val actions: List<GoalAction>,
    val goal: NoticeGoal? = null,
) {
    override fun toString(): String = "GoalNotice(kind=$kind, text=${text?.length} chars, progress=$progress, actions=$actions, goal=$goal)"
}

/**
 * The goal notification for the conversation's view (design 10.6 of sub-project 7; plan 7E). While the goal works (it
 * acts, asks or has handed a step over): "Kestrel is working", the current checklist item, or "Waiting for your command
 * to finish" while its round waits behind the owner's instant command, the checklist's progress, Stop and Open. While it
 * is held: "Continue the goal?", how to go on for its hold (the sentence that Kestrel says), Cancel and then Continue, so
 * that the first button always ends the goal. Without a goal: none.
 */
internal object GoalNotices {
    /** The notice after the goal's end while its final reply is said or waits, and of a start that came after the end. */
    val CLOSING = GoalNotice(NoticeKind.CLOSING, GoalTexts.FINISHED, null, null, emptyList())

    fun of(view: ConversationView): GoalNotice? {
        val phase = view.goal ?: return null
        val goal = view.id?.let { id -> view.goalRecord?.let { NoticeGoal(id, it) } }
        if (phase in GoalState.HELD) {
            val how = view.hold?.let(ConversationTexts::held)
            return GoalNotice(NoticeKind.HELD, ConversationTexts.CONTINUE_GOAL, how, null, listOf(GoalAction.CANCEL, GoalAction.CONTINUE), goal)
        }
        val checklist = view.checklist
        val progress = checklist?.takeIf { it.total > 0 }?.let { NoticeProgress(it.done, it.total) }
        val text = if (view.waitsBehindCommand) GoalTexts.BEHIND_COMMAND else checklist?.current
        return GoalNotice(NoticeKind.WORKING, GoalTexts.WORKING, text, progress, listOf(GoalAction.STOP, GoalAction.OPEN), goal)
    }
}

/**
 * A tap on a button of the goal notification as an input of the owner's (design 6.4 and 10.6 of sub-project 7; plan 7E).
 * The tap counts only for the [goal] that its notice showed, and only while the goal's notice [shown] offers what the
 * button does: a button that stops while the notice offers any way to stop (his tap on Stop can arrive after a touch, his
 * pull of the shade, held the goal and the notice offers Cancel instead), and Continue only while it offers Continue. A tap
 * that counts is the owner's act of its moment, as a spoken or typed input is, so it takes the next number of his inputs
 * from [next] (`InputIds.next`), and only then: a Continue that resumes the goal lets the round have an instant command
 * that he started before the tap let go of the screen, and waits for one that he started after it (plan 7D's
 * `ScreenTurns`). A tap carries no words (D8), so the input keeps [channel], the channel of the owner's latest input, and
 * Kestrel's replies stay as they were (design 11).
 */
internal object GoalButtons {
    fun input(broadcast: String?, goal: NoticeGoal?, shown: GoalNotice?, channel: InputChannel, next: () -> Long, atMillis: Long): OwnerInput? {
        val action = GoalAction.ofBroadcast(broadcast) ?: return null
        val intent = action.intent ?: return null
        if (shown == null || goal == null || shown.goal != goal) return null
        val offered = if (action.stops) shown.actions.any { it.stops } else action in shown.actions
        if (!offered) return null
        return OwnerInput(next(), channel, intent, atMillis)
    }

    /** How long a tap waits for the conversation's restore: well inside the 10 s that Android gives a broadcast. */
    const val RESTORE_WAIT_MILLIS = 5_000L

    /**
     * Decides a tap once the conversation is [restored] (`ConversationEngine.awaitRestored`, then its view), against the
     * notice of the goal in that view: a tap on the held goal's notification that Kestrel posted without the service can
     * start Kestrel's process, and is then judged against the restored goal, never lost against the empty view before it.
     * A restore that does not end within [RESTORE_WAIT_MILLIS] drops the tap, and [report] gets only its kind.
     */
    suspend fun press(
        broadcast: String?,
        goal: NoticeGoal?,
        restored: suspend () -> ConversationView,
        next: () -> Long,
        now: () -> Long,
        report: (String, Throwable) -> Unit,
    ): OwnerInput? {
        val view = withTimeoutOrNull(RESTORE_WAIT_MILLIS) { restored() }
        if (view == null) {
            report("A goal notification's button", RestoreTooSlowException())
            return null
        }
        return input(broadcast, goal, GoalNotices.of(view), view.replyChannel, next, now())
    }
}

/** A tap on the goal notification waited longer than `GoalButtons.RESTORE_WAIT_MILLIS` for the restore, and was dropped. */
private class RestoreTooSlowException : Exception()
```

- [ ] **Step 4: Run the test to see it pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.goal.GoalNoticesTest`
Expected: PASS, 10 tests.

- [ ] **Step 5: Run `:app`'s JVM tests, count them and stop Gradle**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest`, then `$n = 0; Get-ChildItem app\build\test-results\testDebugUnitTest -Filter *.xml | ForEach-Object { $n += [int]([xml](Get-Content $_.FullName -Raw)).testsuite.tests }; "app $n"`, then `scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL; `app` A₀ + 11.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/kestrel/app/goal app/src/test/kotlin/dev/kestrel/app/goal
git commit -m "feat(app): the goal notification's words and buttons, built from the conversation's view (plan 7E)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: When the service runs, what Kestrel posts without it, with which types, and a refusal

The goal service's logic, apart from Android (design 10.6 and D19 of sub-project 7): `GoalServiceRules` is one function from a state and an event to the new state and its commands, and `GoalForeground` applies it one event at a time, starts the service through a port, and tells the conversation when a refused start leaves a goal without its service. The rules: the service starts when a goal opens, also a held goal that a restart restored, and again each time the goal's work begins after a hold, but never for a goal that is ending; it runs until the goal has ended and its final reply has been said; Android's end of a service that a goal still needs starts it again; a stopped service shows nothing, and after a refusal Kestrel posts the held goal's notice itself, without the service, until the next start or the goal's end; the service is of the type specialUse from Android 14 on, with mediaPlayback while Kestrel speaks, and of no type on Android 13.

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/goal/GoalServiceRules.kt`, `app/src/main/kotlin/dev/kestrel/app/goal/GoalForeground.kt`
- Test: `app/src/test/kotlin/dev/kestrel/app/goal/GoalServiceRulesTest.kt`, `app/src/test/kotlin/dev/kestrel/app/goal/GoalForegroundTest.kt`

**Interfaces:**
- Consumes: Task 1's `ConversationView.goalEnding` and `ConversationView.hold`; Task 2's `GoalNotice`, `GoalNotices`, `NoticeKind`, `GoalAction`, `GoalTexts`; plan 7B's `ConversationView` (`goal`, `command`, `quiet`), `GoalPhase`, `GoalState.HELD`, `GoalEnding.STOPPING`, `QuietClock.Held`, `HoldReason.REPLYING`.
- Produces (package `dev.kestrel.app.goal`, all `internal`): `enum class ForegroundType(val wire: String) { SPECIAL_USE("special-use"), MEDIA_PLAYBACK("media-playback") }`; `enum class ServiceState { STOPPED, STARTING, RUNNING }`; `data class Shown(val notice: GoalNotice, val types: Set<ForegroundType>)`; `data class GoalServiceState(goal: GoalPhase? = null, command: Int = 0, replying: Boolean = false, notice: GoalNotice? = null, speakers: Int = 0, service: ServiceState = ServiceState.STOPPED, startedFor: Int = 0, lost: Boolean = false, starts: Int = 0, refusals: Int = 0)` with `val wanted: Boolean`; `sealed interface GoalServiceEvent` (`Viewed(goal: GoalPhase?, stopping: Boolean, command: Int, replying: Boolean, notice: GoalNotice?)`, `Started`, `Refused`, `Destroyed`, `Speaking(active: Boolean)`); `sealed interface GoalServiceCommand` (`Start`, `HoldGoal(command: Int)`); `data class GoalServiceStep(state: GoalServiceState, commands: List<GoalServiceCommand> = emptyList())`; `object GoalServiceRules { const val TYPED_SDK = 34; fun next(state: GoalServiceState, event: GoalServiceEvent): GoalServiceStep; fun shown(state: GoalServiceState, sdk: Int): Shown?; fun posted(state: GoalServiceState): GoalNotice?; fun types(sdk: Int, speaking: Boolean): Set<ForegroundType> }`; `data class GoalServiceStatus(service: ServiceState, notice: GoalNotice?, posted: GoalNotice?, types: Set<ForegroundType>?, applied: Set<ForegroundType>?, starts: Int, refusals: Int)`; `class GoalForeground(start: () -> Unit, holdGoal: (command: Int) -> Unit, sdk: Int, report: (String, Throwable) -> Unit)` with `val shown: StateFlow<Shown?>`, `val posted: StateFlow<GoalNotice?>`, `fun notice(): GoalNotice?` (the service's notice, or else the posted one), `suspend fun follow(views: Flow<ConversationView>)`, `fun started(types: Set<ForegroundType>)`, `fun applied(types: Set<ForegroundType>)`, `fun failed(error: Exception)`, `fun destroyed()`, `suspend fun <T> whileSpeaking(block: suspend () -> T): T`, `fun status(): GoalServiceStatus` and `GoalForeground.APPLY_MILLIS = 1_000L`.

Counts: `:app` JVM A₀ + 11 + 9 = A₀ + 20 (`GoalServiceRulesTest` 6, `GoalForegroundTest` 3).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/dev/kestrel/app/goal/GoalServiceRulesTest.kt`:

```kotlin
package dev.kestrel.app.goal

import dev.kestrel.conversation.model.GoalPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the goal service runs, what it shows and with which types (design 10.6 and D19 of sub-project 7; plan 7E). */
class GoalServiceRulesTest {
    private val working = GoalNotice(NoticeKind.WORKING, GoalTexts.WORKING, null, null, listOf(GoalAction.STOP, GoalAction.OPEN))
    private val held = GoalNotice(NoticeKind.HELD, "Continue the goal?", null, null, listOf(GoalAction.CANCEL, GoalAction.CONTINUE))

    private fun viewed(state: GoalServiceState, goal: GoalPhase?, command: Int = 1, replying: Boolean = false, stopping: Boolean = false): GoalServiceStep {
        val notice = when (goal) {
            null -> null
            GoalPhase.ACTING, GoalPhase.ASKING, GoalPhase.HANDED_OVER -> working
            GoalPhase.PAUSED, GoalPhase.PAUSED_AT_LIMIT, GoalPhase.INTERRUPTED -> held
        }
        return GoalServiceRules.next(state, GoalServiceEvent.Viewed(goal, stopping, command, replying, notice))
    }

    private fun running(): GoalServiceState = GoalServiceRules.next(viewed(GoalServiceState(), GoalPhase.ACTING).state, GoalServiceEvent.Started).state

    @Test
    fun aGoalThatOpensOrResumesStartsTheServiceAndAHoldOrAnEndingStartsNothing() {
        val opened = viewed(GoalServiceState(), GoalPhase.ACTING)
        assertEquals(listOf(GoalServiceCommand.Start), opened.commands)
        assertEquals(ServiceState.STARTING, opened.state.service)
        val running = running()
        assertTrue(viewed(running, GoalPhase.ASKING).commands.isEmpty())
        assertTrue(viewed(running, GoalPhase.HANDED_OVER).commands.isEmpty())

        val interrupted = viewed(running, GoalPhase.INTERRUPTED)
        assertTrue(interrupted.commands.isEmpty())
        assertEquals(held, GoalServiceRules.shown(interrupted.state, 37)?.notice)

        // Design 10.6: the resume starts the running service again, from the context of the owner's input.
        val resumed = viewed(interrupted.state, GoalPhase.ACTING)
        assertEquals(listOf(GoalServiceCommand.Start), resumed.commands)
        assertEquals(ServiceState.RUNNING, resumed.state.service)
        assertEquals(2, resumed.state.starts)

        // Design 6.7: a goal that a restart restored is held, and its notification asks "Continue the goal?"; a goal that
        // the restore ends after the owner's stop in "Active apps" starts nothing (plan 7B).
        assertEquals(listOf(GoalServiceCommand.Start), viewed(GoalServiceState(), GoalPhase.INTERRUPTED, command = 0).commands)
        assertTrue(viewed(GoalServiceState(), GoalPhase.INTERRUPTED, command = 0, stopping = true).commands.isEmpty())
    }

    @Test
    fun theServiceRunsUntilTheGoalsFinalReplyHasBeenSaid() {
        val replying = viewed(running(), null, replying = true).state
        assertEquals(GoalNotices.CLOSING, GoalServiceRules.shown(replying, 37)?.notice)

        val ended = viewed(replying, null).state
        assertNull(GoalServiceRules.shown(ended, 37))
        assertEquals(ServiceState.STOPPED, ended.service)
        assertTrue(GoalServiceRules.next(ended, GoalServiceEvent.Destroyed).commands.isEmpty())
        // A final reply without a goal's service starts none.
        assertNull(GoalServiceRules.shown(viewed(GoalServiceState(), null, replying = true).state, 37))
    }

    @Test
    fun aRefusedStartHoldsItsGoalWithoutTheServiceAndNothingElse() {
        val refused = GoalServiceRules.next(viewed(GoalServiceState(), GoalPhase.ACTING, command = 4).state, GoalServiceEvent.Refused)
        assertEquals(listOf(GoalServiceCommand.HoldGoal(4)), refused.commands)
        assertEquals(ServiceState.STOPPED, refused.state.service)
        assertEquals(1, refused.state.refusals)

        // A refused new start of a service that runs leaves it running, and the goal working.
        val resumed = viewed(viewed(running(), GoalPhase.PAUSED).state, GoalPhase.ACTING).state
        val again = GoalServiceRules.next(resumed, GoalServiceEvent.Refused)
        assertTrue(again.commands.isEmpty())
        assertEquals(ServiceState.RUNNING, again.state.service)
        assertEquals(1, again.state.refusals)

        assertTrue(GoalServiceRules.next(GoalServiceState(service = ServiceState.STARTING), GoalServiceEvent.Refused).commands.isEmpty())
    }

    @Test
    fun aRefusedGoalHeldAsInterruptedShowsNothingAndIsPostedWithoutTheServiceUntilItsNextStart() {
        val refused = GoalServiceRules.next(viewed(GoalServiceState(), GoalPhase.ACTING, command = 4).state, GoalServiceEvent.Refused).state
        // A stopped service shows nothing, and the goal is posted only once the conversation holds it.
        assertNull(GoalServiceRules.shown(refused, 37))
        assertNull(GoalServiceRules.posted(refused))

        val heldNow = viewed(refused, GoalPhase.INTERRUPTED, command = 4).state
        assertNull(GoalServiceRules.shown(heldNow, 37))
        assertEquals(held, GoalServiceRules.posted(heldNow))

        // Design 10.6: the posted notice's Continue resumes the goal, which starts the service and takes the posted notice away.
        val resumed = viewed(heldNow, GoalPhase.ACTING, command = 5)
        assertEquals(listOf(GoalServiceCommand.Start), resumed.commands)
        assertNull(GoalServiceRules.posted(resumed.state))
        assertEquals(working, GoalServiceRules.shown(resumed.state, 37)?.notice)
        // The goal's end takes it away too.
        assertNull(GoalServiceRules.posted(viewed(heldNow, null).state))
    }

    @Test
    fun androidsEndOfTheServiceOfAnOpenGoalStartsItAgain() {
        val restarted = GoalServiceRules.next(running(), GoalServiceEvent.Destroyed)
        assertEquals(listOf(GoalServiceCommand.Start), restarted.commands)
        assertEquals(ServiceState.STARTING, restarted.state.service)
        // The end of an older instance while a start is on its way changes nothing.
        assertEquals(GoalServiceStep(restarted.state), GoalServiceRules.next(restarted.state, GoalServiceEvent.Destroyed))
    }

    @Test
    fun theTypesFollowAndroidsVersionAndKestrelsSpeech() {
        assertEquals(emptySet<ForegroundType>(), GoalServiceRules.types(33, speaking = true))
        assertEquals(setOf(ForegroundType.SPECIAL_USE), GoalServiceRules.types(34, speaking = false))
        assertEquals(setOf(ForegroundType.SPECIAL_USE, ForegroundType.MEDIA_PLAYBACK), GoalServiceRules.types(37, speaking = true))

        val two = listOf(true, true).fold(running()) { state, active -> GoalServiceRules.next(state, GoalServiceEvent.Speaking(active)).state }
        val one = GoalServiceRules.next(two, GoalServiceEvent.Speaking(false)).state
        assertEquals(setOf(ForegroundType.SPECIAL_USE, ForegroundType.MEDIA_PLAYBACK), GoalServiceRules.shown(one, 37)?.types)
        val none = GoalServiceRules.next(one, GoalServiceEvent.Speaking(false)).state
        assertEquals(setOf(ForegroundType.SPECIAL_USE), GoalServiceRules.shown(none, 37)?.types)
    }
}
```

Create `app/src/test/kotlin/dev/kestrel/app/goal/GoalForegroundTest.kt`:

```kotlin
package dev.kestrel.app.goal

import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.machine.HoldReason
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.model.GoalHold
import dev.kestrel.conversation.model.GoalPhase
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The goal service's coordinator over the conversation's view (design 10.6 of sub-project 7; plan 7E). */
class GoalForegroundTest {
    private val views = MutableStateFlow(ConversationView())
    private var starts = 0
    private val holds = mutableListOf<Int>()
    private var refuse = false
    private val reports = mutableListOf<String>()

    private fun TestScope.following(sdk: Int = 37): GoalForeground {
        val foreground = GoalForeground(
            start = {
                starts++
                if (refuse) throw IllegalStateException("startForegroundService() not allowed")
            },
            holdGoal = { command -> holds += command },
            sdk = sdk,
        ) { what, _ -> reports += what }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { foreground.follow(views) }
        return foreground
    }

    @Test
    fun aGoalStartsTheServiceWhichStopsOnceTheGoalsFinalReplyHasBeenSaid() = runTest {
        val foreground = following()

        views.value = ConversationView(command = 1, goal = GoalPhase.ACTING)
        assertEquals(1, starts)
        foreground.started(setOf(ForegroundType.SPECIAL_USE))
        assertEquals(Shown(GoalNotices.of(views.value)!!, setOf(ForegroundType.SPECIAL_USE)), foreground.shown.value)

        views.value = ConversationView(command = 1, quiet = QuietClock.Held(HoldReason.REPLYING))
        assertEquals(GoalNotices.CLOSING, foreground.shown.value?.notice)
        // A final reply kept until the owner is back keeps the service, also when its conversation closed meanwhile.
        views.value = ConversationView(command = 1, keptForOwner = true)
        assertEquals(GoalNotices.CLOSING, foreground.shown.value?.notice)
        views.value = ConversationView(command = 1, quiet = QuietClock.Running(1, 0))
        assertNull(foreground.shown.value)
        foreground.destroyed()

        assertEquals(1, starts)
        assertEquals(ServiceState.STOPPED, foreground.status().service)
    }

    @Test
    fun aRefusedStartHoldsItsGoalOncePostsTheHeldGoalAndTheNextResumeStartsAgain() = runTest {
        val foreground = following()
        refuse = true

        views.value = ConversationView(command = 1, goal = GoalPhase.ACTING)
        assertEquals(listOf(1), holds)
        assertEquals(listOf("Starting the goal service"), reports)
        assertNull(foreground.shown.value)
        // The conversation holds the goal: a held goal starts nothing, so the refusal does not repeat, and Kestrel posts it.
        views.value = ConversationView(command = 1, goal = GoalPhase.INTERRUPTED, hold = GoalHold.BACKGROUND_REFUSED)
        assertEquals(1, starts)
        assertEquals(GoalNotices.of(views.value), foreground.posted.value)
        assertEquals(foreground.posted.value, foreground.notice())

        refuse = false
        views.value = ConversationView(command = 2, goal = GoalPhase.ACTING)
        assertEquals(2, starts)
        assertEquals(listOf(1), holds)
        assertEquals(
            GoalServiceStatus(ServiceState.STARTING, GoalNotices.of(views.value), null, setOf(ForegroundType.SPECIAL_USE), null, 2, 1),
            foreground.status(),
        )
    }

    @Test
    fun aSpeechWaitsUntilTheServiceAddedMediaPlaybackAndAndroid13NeedsNoType() = runTest {
        val foreground = following()
        views.value = ConversationView(command = 1, goal = GoalPhase.ACTING)
        foreground.started(setOf(ForegroundType.SPECIAL_USE))

        val speech = async { foreground.whileSpeaking { "spoken" } }
        runCurrent()
        assertFalse(speech.isCompleted)
        assertEquals(setOf(ForegroundType.SPECIAL_USE, ForegroundType.MEDIA_PLAYBACK), foreground.shown.value?.types)
        foreground.applied(setOf(ForegroundType.SPECIAL_USE, ForegroundType.MEDIA_PLAYBACK))
        assertEquals("spoken", speech.await())
        assertEquals(setOf(ForegroundType.SPECIAL_USE), foreground.shown.value?.types)

        val android13 = following(sdk = 33)
        android13.started(emptySet())
        assertEquals(emptySet<ForegroundType>(), android13.shown.value?.types)
        assertEquals("spoken", android13.whileSpeaking { "spoken" })
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.goal.GoalServiceRulesTest --tests=dev.kestrel.app.goal.GoalForegroundTest`
Expected: FAIL, the compiler reports `Unresolved reference 'GoalServiceRules'` and `Unresolved reference 'GoalForeground'`.

- [ ] **Step 3: The rules**

Create `app/src/main/kotlin/dev/kestrel/app/goal/GoalServiceRules.kt`:

```kotlin
package dev.kestrel.app.goal

import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.GoalState

/** A type of foreground service that the goal service uses (design 10.6 of sub-project 7), with the hooks' name. */
internal enum class ForegroundType(val wire: String) {
    SPECIAL_USE("special-use"),
    MEDIA_PLAYBACK("media-playback"),
}

/** The goal service as its coordinator knows it: not running, asked to start, or in the foreground. */
internal enum class ServiceState { STOPPED, STARTING, RUNNING }

/** What the running goal service shows, and with which foreground-service types. */
internal data class Shown(val notice: GoalNotice, val types: Set<ForegroundType>)

/**
 * The goal service as its rules see it: the conversation's [goal] and the view's [command], whether its final reply is
 * being said ([replying]), the goal's [notice], how many of Kestrel's speeches run ([speakers]), the [service], the command
 * whose start Kestrel asked for last ([startedFor]), whether a refusal left the open goal without its service ([lost]),
 * and, for the hooks, how many starts Kestrel asked for and how many Android refused in this process.
 */
internal data class GoalServiceState(
    val goal: GoalPhase? = null,
    val command: Int = 0,
    val replying: Boolean = false,
    val notice: GoalNotice? = null,
    val speakers: Int = 0,
    val service: ServiceState = ServiceState.STOPPED,
    val startedFor: Int = 0,
    val lost: Boolean = false,
    val starts: Int = 0,
    val refusals: Int = 0,
) {
    /** The service runs for every open goal (D19), and after its end while a running service says its final reply. */
    val wanted: Boolean get() = goal != null || (replying && service != ServiceState.STOPPED)
}

/** What happens to the goal service. */
internal sealed interface GoalServiceEvent {
    /**
     * The conversation's view: the goal's phase, whether the goal is being stopped ([stopping], as after the owner's stop
     * at a restart), the view's command, whether a final reply is being said, and the goal's notice.
     */
    data class Viewed(val goal: GoalPhase?, val stopping: Boolean, val command: Int, val replying: Boolean, val notice: GoalNotice?) : GoalServiceEvent

    /** The service is in the foreground. */
    data object Started : GoalServiceEvent

    /** Android refused a start: `startForegroundService()` or the service's `startForeground()` threw. */
    data object Refused : GoalServiceEvent

    /** The service ended. */
    data object Destroyed : GoalServiceEvent

    /** A speech of Kestrel's begins or ends. */
    data class Speaking(val active: Boolean) : GoalServiceEvent
}

/** What the coordinator does for the rules: ask Android to start the service, or hold the goal whose start was refused. */
internal sealed interface GoalServiceCommand {
    data object Start : GoalServiceCommand

    /** Holds the goal of command [command], whose start Android refused (`ConversationEngine.backgroundRefused`). */
    data class HoldGoal(val command: Int) : GoalServiceCommand
}

internal data class GoalServiceStep(val state: GoalServiceState, val commands: List<GoalServiceCommand> = emptyList())

/**
 * When the goal service runs and what it shows (design 10.6 and D19 of sub-project 7; plan 7E), as one function from a
 * state and an event to the new state and its commands. The service starts when a goal opens, also a held goal that a
 * restart restored, and again each time the goal's work begins after a hold, also while it runs, so that every owner's
 * input that starts or resumes a goal starts it from that input's context: the conversation screen in front, the voice
 * path while the listening service runs, or the notification's Continue. A goal that is being stopped starts nothing. The
 * service runs until the goal has ended and its final reply has been said. A start that Android refuses holds the goal of
 * the command that asked for it, when that goal has no running service; a stopped service shows nothing, and while the
 * goal that lost its service is held, Kestrel posts its notice without the service, so that its Continue can start the
 * service from the notification. Android's end of a service that a goal still needs starts it again. From Android 14 the
 * service is of the type specialUse, with mediaPlayback while Kestrel speaks; Android 13 checks no type, and hardens no
 * background audio, so the service has none there.
 */
internal object GoalServiceRules {
    /** The first Android version that checks a foreground service's type (Android 14). */
    const val TYPED_SDK = 34

    private val WORKING = setOf(GoalPhase.ACTING, GoalPhase.ASKING, GoalPhase.HANDED_OVER)

    fun next(state: GoalServiceState, event: GoalServiceEvent): GoalServiceStep = when (event) {
        is GoalServiceEvent.Viewed -> viewed(state, event)
        GoalServiceEvent.Started -> GoalServiceStep(state.copy(service = ServiceState.RUNNING))
        GoalServiceEvent.Refused -> refused(state)
        GoalServiceEvent.Destroyed -> destroyed(state)
        is GoalServiceEvent.Speaking ->
            GoalServiceStep(state.copy(speakers = (state.speakers + if (event.active) 1 else -1).coerceAtLeast(0)))
    }

    /** What the service shows in [state] on Android [sdk]; null when it should not run, and while it is stopped. */
    fun shown(state: GoalServiceState, sdk: Int): Shown? =
        if (state.wanted && state.service != ServiceState.STOPPED) Shown(state.notice ?: GoalNotices.CLOSING, types(sdk, state.speakers > 0)) else null

    /** The held goal's notice that Kestrel posts without the service after a refusal; null otherwise. */
    fun posted(state: GoalServiceState): GoalNotice? =
        state.notice?.takeIf { state.lost && state.service == ServiceState.STOPPED && state.goal in GoalState.HELD }

    /** The service's types on Android [sdk]: none on Android 13, specialUse from Android 14, with mediaPlayback while [speaking]. */
    fun types(sdk: Int, speaking: Boolean): Set<ForegroundType> = when {
        sdk < TYPED_SDK -> emptySet()
        speaking -> setOf(ForegroundType.SPECIAL_USE, ForegroundType.MEDIA_PLAYBACK)
        else -> setOf(ForegroundType.SPECIAL_USE)
    }

    private fun viewed(state: GoalServiceState, event: GoalServiceEvent.Viewed): GoalServiceStep {
        val begins = event.goal != null && !event.stopping && (state.goal == null || (event.goal in WORKING && state.goal !in WORKING))
        val seen = state.copy(
            goal = event.goal,
            command = event.command,
            replying = event.replying,
            notice = event.notice ?: GoalNotices.CLOSING.takeIf { event.replying },
            lost = state.lost && event.goal != null,
        )
        return when {
            begins -> start(seen)
            seen.wanted -> GoalServiceStep(seen)
            else -> GoalServiceStep(seen.copy(service = ServiceState.STOPPED, notice = null))
        }
    }

    // A refused new start of a service that runs leaves it running; a goal without its service is held (design 10.6).
    private fun refused(state: GoalServiceState): GoalServiceStep {
        val counted = state.copy(refusals = state.refusals + 1)
        if (state.service == ServiceState.RUNNING) return GoalServiceStep(counted)
        val stopped = counted.copy(service = ServiceState.STOPPED, lost = state.goal != null)
        return GoalServiceStep(stopped, listOfNotNull(GoalServiceCommand.HoldGoal(state.startedFor).takeIf { state.goal != null }))
    }

    // Only the end of a running service is news: a stop that the rules asked for, or a refusal, made it stopped already, and
    // a start that is on its way outlives the end of an older instance.
    private fun destroyed(state: GoalServiceState): GoalServiceStep = when {
        state.service != ServiceState.RUNNING -> GoalServiceStep(state)
        state.goal != null -> start(state.copy(service = ServiceState.STOPPED))
        else -> GoalServiceStep(state.copy(service = ServiceState.STOPPED))
    }

    private fun start(state: GoalServiceState): GoalServiceStep = GoalServiceStep(
        state.copy(
            service = if (state.service == ServiceState.STOPPED) ServiceState.STARTING else state.service,
            startedFor = state.command,
            lost = false,
            starts = state.starts + 1,
        ),
        listOf(GoalServiceCommand.Start),
    )
}
```

- [ ] **Step 4: The coordinator**

Create `app/src/main/kotlin/dev/kestrel/app/goal/GoalForeground.kt`:

```kotlin
package dev.kestrel.app.goal

import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.machine.HoldReason
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.model.GoalEnding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The goal service as the hooks read it: the coordinator's [service], the [notice] that the service shows (null while it
 * shows none), the held goal's notice that Kestrel [posted] without the service, the [types] that the service should have
 * (null while it should show nothing), the types that it [applied] in the foreground (null while it is not there), and
 * the starts and refusals in this process.
 */
internal data class GoalServiceStatus(
    val service: ServiceState,
    val notice: GoalNotice?,
    val posted: GoalNotice?,
    val types: Set<ForegroundType>?,
    val applied: Set<ForegroundType>?,
    val starts: Int,
    val refusals: Int,
)

/**
 * The goal service's coordinator (design 10.6 of sub-project 7; plan 7E). It follows the conversation's view, applies
 * [GoalServiceRules] one event at a time, asks Android to start the service through [start], which throws what
 * `startForegroundService()` throws, and calls [holdGoal] with the command whose start Android refused when that leaves a
 * goal without its service. `GoalService` follows [shown] and reports to [started], [applied], [failed] and [destroyed];
 * the app graph posts [posted]. [sdk] is Android's version. Any thread may call it; [report] gets each refusal by its
 * class.
 */
internal class GoalForeground(
    private val start: () -> Unit,
    private val holdGoal: (command: Int) -> Unit,
    private val sdk: Int,
    private val report: (String, Throwable) -> Unit,
) {
    private val lock = Any()
    private var state = GoalServiceState()
    private val shownNow = MutableStateFlow<Shown?>(null)
    private val postedNow = MutableStateFlow<GoalNotice?>(null)
    private val appliedNow = MutableStateFlow<Set<ForegroundType>?>(null)

    /** What the goal service shows now, and with which types; null while it should show nothing. */
    val shown: StateFlow<Shown?> = shownNow.asStateFlow()

    /** The held goal's notice that Kestrel posts without the service after a refusal; null otherwise. */
    val posted: StateFlow<GoalNotice?> = postedNow.asStateFlow()

    /** The notice whose buttons count now: the service's, or else the one posted without it. */
    fun notice(): GoalNotice? = shownNow.value?.notice ?: postedNow.value

    /** Follows the conversation's [views] for as long as it runs. */
    suspend fun follow(views: Flow<ConversationView>) {
        views.map { view ->
            GoalServiceEvent.Viewed(
                goal = view.goal,
                stopping = view.goalEnding == GoalEnding.STOPPING,
                command = view.command,
                // The final reply is said, or kept until the owner is there (plan 7B), also after the conversation closed.
                replying = view.goal == null && (view.quiet == REPLYING || view.keptForOwner),
                notice = GoalNotices.of(view),
            )
        }
            .distinctUntilChanged()
            .collect { on(it) }
    }

    /** The service is in the foreground with [types]. */
    fun started(types: Set<ForegroundType>) {
        appliedNow.value = types
        on(GoalServiceEvent.Started)
    }

    /** The running service changed its types to [types]. */
    fun applied(types: Set<ForegroundType>) {
        appliedNow.value = types
    }

    /** The service's `startForeground()` threw [error]: Android refused the start. */
    fun failed(error: Exception) {
        report("Starting the goal service", error)
        on(GoalServiceEvent.Refused)
    }

    /** The service ended. */
    fun destroyed() {
        appliedNow.value = null
        on(GoalServiceEvent.Destroyed)
    }

    /**
     * Runs [block], a speech of Kestrel's with listening off (plan 7F's typed replies), while the service has the type
     * mediaPlayback, which design 10.6 chose for Kestrel's audio in the background. Android 17 lets an app play audio in
     * the background only from a foreground service with while-in-use (main spec B5); whether it also asks for this type
     * is spike S9's finding (Task 8). It waits up to [APPLY_MILLIS] for the service to apply the type; on Android 13, or
     * without a goal's running service, [block] runs at once.
     */
    suspend fun <T> whileSpeaking(block: suspend () -> T): T {
        on(GoalServiceEvent.Speaking(true))
        try {
            if (ForegroundType.MEDIA_PLAYBACK in shownNow.value?.types.orEmpty()) {
                withTimeoutOrNull(APPLY_MILLIS) { appliedNow.first { ForegroundType.MEDIA_PLAYBACK in it.orEmpty() } }
            }
            return block()
        } finally {
            on(GoalServiceEvent.Speaking(false))
        }
    }

    fun status(): GoalServiceStatus = synchronized(lock) {
        val shown = shownNow.value
        GoalServiceStatus(state.service, shown?.notice, postedNow.value, shown?.types, appliedNow.value, state.starts, state.refusals)
    }

    private fun on(event: GoalServiceEvent) {
        val step = synchronized(lock) {
            GoalServiceRules.next(state, event).also {
                state = it.state
                shownNow.value = GoalServiceRules.shown(it.state, sdk)
                postedNow.value = GoalServiceRules.posted(it.state)
            }
        }
        for (command in step.commands) perform(command)
    }

    private fun perform(command: GoalServiceCommand) {
        when (command) {
            GoalServiceCommand.Start -> try {
                start()
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException and the other refusals of a start in the background.
                report("Starting the goal service", e)
                on(GoalServiceEvent.Refused)
            }
            is GoalServiceCommand.HoldGoal -> holdGoal(command.command)
        }
    }

    override fun toString(): String = "GoalForeground(${status()})"

    companion object {
        /** How long a speech waits for the service to add mediaPlayback. */
        const val APPLY_MILLIS = 1_000L

        private val REPLYING = QuietClock.Held(HoldReason.REPLYING)
    }
}
```

- [ ] **Step 5: Run the tests to see them pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.goal.*`
Expected: PASS, 19 tests.

- [ ] **Step 6: Run `:app`'s JVM tests, count them and stop Gradle**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest`, then `$n = 0; Get-ChildItem app\build\test-results\testDebugUnitTest -Filter *.xml | ForEach-Object { $n += [int]([xml](Get-Content $_.FullName -Raw)).testsuite.tests }; "app $n"`, then `scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL; `app` A₀ + 20.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/kestrel/app/goal app/src/test/kotlin/dev/kestrel/app/goal
git commit -m "feat(app): the goal service's rules and coordinator: a start for every goal and resume, the types, the final reply, a refusal of its own command and the held goal posted without the service (plan 7E)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: How Kestrel's last process ended, for both restores

The owner's decision of 2026-11-25: a stop in Android's "Active apps" is the owner's stop, so a goal that the restore finds after it ends as "stop" ends it (plan 7B). Kestrel already reads Android's newest exit record for listening's restore (`AndroidExitRecords` and `exitReasonOf` in `listen/ExitRecords.kt`, design spec 8.5). This task keeps that reader as the only one, built once in the app graph, and gives its record to the conversation's restore too, so that one record has one reading. What Android's documentation says, and so what the reading is: Android's Task Manager stops the whole app without a callback and names `ApplicationExitInfo.REASON_USER_REQUESTED` as the record to check at the next start; the same reason marks a force stop in Settings and removing the app from the recent apps, which are the owner's stops too, and no public sub-reason tells them apart (`getSubReason` is not public). Before Android 14 an update of the app gave `REASON_USER_REQUESTED` as well; from Android 14 it gives `REASON_PACKAGE_UPDATED`. The controller's reading of 2026-11-25: on Android 13, a `REASON_USER_REQUESTED` within a minute of Kestrel's last install or update, before or after it, cannot be told from the update (`ExitReason.UNCLEAR_STOP`): listening stays off, as it does today after that reason, and a restored goal is held and asks "Continue the goal?" (the safe fallback). Outside that minute, and on Android 14 and later, it is the owner's stop for both: listening stays off and the goal ends. So the reader also reads the time of Kestrel's last install or update, which only that reading needs; `ExitRecord` keeps its reason and time.

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/conversation/ProcessEnds.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/ExitRecords.kt`, `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, `app/src/main/kotlin/dev/kestrel/app/KestrelApplication.kt`
- Test: `app/src/test/kotlin/dev/kestrel/app/listen/ExitRecordsTest.kt`, `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, `app/src/test/kotlin/dev/kestrel/app/conversation/ProcessEndsTest.kt` (new)

**Interfaces:**
- Consumes: plan 7B's `ProcessEnd` and `ConversationEngine.restore(previous: ProcessEnd)`; plan 7D's `AppGraph.conversations` and the restore in `KestrelApplication.onCreate`; sub-project 2's `ExitRecords`, `ExitRecord(reason, timestamp)`, `AndroidExitRecords(context)` and `Listening`'s `restoreAfter`.
- Produces: `ExitReason.UNCLEAR_STOP`; `exitReasonOf(reason: Int, timestamp: Long, sdk: Int, updatedAt: Long): ExitReason` and `UPDATE_MARGIN_MILLIS = 60_000L` (package `dev.kestrel.app.listen`); `processEndOf(record: ExitRecord?): ProcessEnd` and `suspend fun readProcessEnd(exits: ExitRecords, report: (String, Throwable) -> Unit): ProcessEnd` (package `dev.kestrel.app.conversation`); `AppGraph.previousProcessEnd(): ProcessEnd` (`internal suspend`), over the graph's one `AndroidExitRecords`, which listening reads too; `KestrelApplication.onCreate` restores the conversation with it. All `internal`.

Counts: `:app` JVM A₀ + 20 + 3 = A₀ + 23 (`ExitRecordsTest` + 1, `ListeningTest` + 1, `ProcessEndsTest` 1).

- [ ] **Step 1: Write the failing tests**

In `app/src/test/kotlin/dev/kestrel/app/listen/ExitRecordsTest.kt`, replace:

```kotlin
        assertEquals(reasons, reasons.mapValues { (reason, _) -> exitReasonOf(reason) })
    }
}
```

with:

```kotlin
        assertEquals(reasons, reasons.mapValues { (reason, _) -> exitReasonOf(reason, AT, 37, updatedAt = AT) })
    }

    @Test
    fun onAndroid13AStopWithinAMinuteOfAnUpdateCannotBeToldFromItAndAnyOtherStopIsTheOwners() {
        // Android 13 gives an update REASON_USER_REQUESTED too, and takes the update's time a few seconds before or after
        // its kill, so the minute counts on both sides (plan 7E).
        assertEquals(ExitReason.UNCLEAR_STOP, exitReasonOf(10, AT, 33, updatedAt = AT - 30_000))
        assertEquals(ExitReason.UNCLEAR_STOP, exitReasonOf(10, AT, 33, updatedAt = AT + 2_000))
        assertEquals(ExitReason.UNCLEAR_STOP, exitReasonOf(10, AT, 33, updatedAt = AT + 60_000))
        // A stop an hour after the last update, or an hour before an update that came while no process of Kestrel's ran.
        assertEquals(ExitReason.OWNER_STOPPED, exitReasonOf(10, AT, 33, updatedAt = AT - 3_600_000))
        assertEquals(ExitReason.OWNER_STOPPED, exitReasonOf(10, AT, 33, updatedAt = AT + 3_600_000))
        // From Android 14 an update has a reason of its own.
        assertEquals(ExitReason.OWNER_STOPPED, exitReasonOf(10, AT, 34, updatedAt = AT))
        assertEquals(ExitReason.OTHER, exitReasonOf(16, AT, 34, updatedAt = AT))
    }

    private companion object {
        const val AT = 5_000_000_000L
    }
}
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
    @Test
    fun aCrashSoonAfterARestoreKeepsKestrelOffAndSaysSo() = runTest {
```

with:

```kotlin
    @Test
    fun kestrelStaysOffAfterAStopThatCannotBeToldFromAnUpdate() = runTest {
        // On Android 13, a stop within a minute of Kestrel's update may be the owner's, so listening stays off after it, as
        // after the owner's stop (plan 7E).
        memory.stored = Mode.LISTENING
        memory.writtenAt = 500_000
        exits.record = ExitRecord(ExitReason.UNCLEAR_STOP, timestamp = 600_000)
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
```

Create `app/src/test/kotlin/dev/kestrel/app/conversation/ProcessEndsTest.kt`:

```kotlin
package dev.kestrel.app.conversation

import dev.kestrel.app.listen.ExitReason
import dev.kestrel.app.listen.ExitRecord
import dev.kestrel.app.listen.ExitRecords
import dev.kestrel.conversation.model.ProcessEnd
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** How Kestrel's last process ended, for the conversation's restore (plan 7E; plan 7B's `ProcessEnd`). */
class ProcessEndsTest {
    @Test
    fun onlyTheOwnersSureStopEndsTheRestoredGoal() = runTest {
        val at = 5_000_000_000L
        assertEquals(ProcessEnd.OwnerStopped(at), processEndOf(ExitRecord(ExitReason.OWNER_STOPPED, at)))
        // A stop that cannot be told from an update on Android 13, any other end, and no record hold the goal and ask.
        for (reason in listOf(ExitReason.UNCLEAR_STOP, ExitReason.CRASH, ExitReason.OTHER)) {
            assertEquals(ProcessEnd.Other, processEndOf(ExitRecord(reason, at)))
        }
        assertEquals(ProcessEnd.Other, processEndOf(null))

        // When Android cannot give its record, the failure is reported and the goal is held.
        val failures = mutableListOf<String>()
        val failing = object : ExitRecords {
            override suspend fun newest(): ExitRecord? = throw IllegalStateException("no records")
        }
        assertEquals(ProcessEnd.Other, readProcessEnd(failing) { what, _ -> failures += what })
        assertEquals(listOf("Reading how Kestrel's last process ended"), failures)
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.listen.ExitRecordsTest --tests=dev.kestrel.app.listen.ListeningTest --tests=dev.kestrel.app.conversation.ProcessEndsTest`
Expected: FAIL, the compiler reports `Unresolved reference 'UNCLEAR_STOP'` and `Unresolved reference 'processEndOf'`.

- [ ] **Step 3: One reading of the exit record**

In `app/src/main/kotlin/dev/kestrel/app/listen/ExitRecords.kt`, replace:

```kotlin
import android.content.Context
```

with:

```kotlin
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlin.math.abs
```

In `app/src/main/kotlin/dev/kestrel/app/listen/ExitRecords.kt`, replace:

```kotlin
    /**
     * The owner stopped Kestrel: a force stop, "Stop app", or Stop in "Active apps" (prototype P2). On Android 13, an
     * update of Kestrel gives the same reason.
     */
    OWNER_STOPPED,
```

with:

```kotlin
    /**
     * The owner stopped Kestrel: a force stop, "Stop app", Stop in "Active apps" or removing it from Recents (prototype P2,
     * and the documentation of Android's Task Manager). Listening stays off, and a restored goal ends as "stop" ends it
     * (plan 7E).
     */
    OWNER_STOPPED,

    /**
     * On Android 13, a stop within [UPDATE_MARGIN_MILLIS] of Kestrel's last install or update, before or after it, which
     * Android 13 records with the same reason as an update (plan 7E). Listening stays off, as after the owner's stop, and a
     * restored goal is held and asks "Continue the goal?".
     */
    UNCLEAR_STOP,
```

In `app/src/main/kotlin/dev/kestrel/app/listen/ExitRecords.kt`, replace:

```kotlin
/**
 * Reads `ActivityManager.getHistoricalProcessExitReasons()` on [Dispatchers.IO]. It reads only the reason and the time,
 * never the record's description, which can hold the text of an exception (section 13.5).
 */
internal class AndroidExitRecords(private val context: Context) : ExitRecords {
    override suspend fun newest(): ExitRecord? = withContext(Dispatchers.IO) {
        context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 1)
            .firstOrNull()
            ?.let { ExitRecord(exitReasonOf(it.reason), it.timestamp) }
    }
}

/** What an [ApplicationExitInfo]'s reason [reason], one of its `REASON_*` values, means for the restore (section 8.5). */
internal fun exitReasonOf(reason: Int): ExitReason = when (reason) {
    // A force stop, "Stop app" and Stop in "Active apps" (prototype P2). REASON_USER_STOPPED is not the owner's:
    // Android stopped the user profile that the process ran in.
    ApplicationExitInfo.REASON_USER_REQUESTED -> ExitReason.OWNER_STOPPED
```

with:

```kotlin
/**
 * Reads `ActivityManager.getHistoricalProcessExitReasons()` on [Dispatchers.IO], with the time of Kestrel's last install or
 * update, which tells the owner's stop from an update on Android 13. It reads only the reason and the times, never the
 * record's description, which can hold the text of an exception (section 13.5). The app graph builds one, which listening's
 * restore and the conversation's restore both read (plan 7E).
 */
internal class AndroidExitRecords(private val context: Context) : ExitRecords {
    override suspend fun newest(): ExitRecord? = withContext(Dispatchers.IO) {
        val record = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 1)
            .firstOrNull()
            ?: return@withContext null
        val updated = context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).lastUpdateTime
        ExitRecord(exitReasonOf(record.reason, record.timestamp, Build.VERSION.SDK_INT, updated), record.timestamp)
    }
}

/** How near Kestrel's last install or update, before or after it, a stop on Android 13 cannot be told from the update. */
internal const val UPDATE_MARGIN_MILLIS = 60_000L

/**
 * What an [ApplicationExitInfo]'s reason [reason], one of its `REASON_*` values, at [timestamp] means for the restores
 * (section 8.5; plan 7E), on Android [sdk], with the time of Kestrel's last install or update [updatedAt]. From Android 14
 * an update has a reason of its own. Android 13 gives an update REASON_USER_REQUESTED, and takes the update's time a few
 * seconds before or after its kill, so there a stop within [UPDATE_MARGIN_MILLIS] of the update, on either side, is
 * [ExitReason.UNCLEAR_STOP].
 */
internal fun exitReasonOf(reason: Int, timestamp: Long, sdk: Int, updatedAt: Long): ExitReason = when (reason) {
    // A force stop, "Stop app", Stop in "Active apps" and removing Kestrel from Recents (prototype P2). REASON_USER_STOPPED
    // is not the owner's: Android stopped the user profile that the process ran in.
    ApplicationExitInfo.REASON_USER_REQUESTED ->
        if (sdk < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && abs(updatedAt - timestamp) <= UPDATE_MARGIN_MILLIS) {
            ExitReason.UNCLEAR_STOP
        } else {
            ExitReason.OWNER_STOPPED
        }
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
            ExitReason.OWNER_STOPPED -> Restore.STAY_OFF
```

with:

```kotlin
            // On Android 13 a stop next to an update may be the owner's, so listening stays off after it too (plan 7E).
            ExitReason.OWNER_STOPPED, ExitReason.UNCLEAR_STOP -> Restore.STAY_OFF
```

Listening's restore keeps its behaviour: it stayed off after every `REASON_USER_REQUESTED` before, and it still does.

- [ ] **Step 4: The conversation's reading of the record**

Create `app/src/main/kotlin/dev/kestrel/app/conversation/ProcessEnds.kt`:

```kotlin
package dev.kestrel.app.conversation

import dev.kestrel.app.listen.ExitReason
import dev.kestrel.app.listen.ExitRecord
import dev.kestrel.app.listen.ExitRecords
import dev.kestrel.conversation.model.ProcessEnd
import kotlinx.coroutines.CancellationException

/**
 * How Kestrel's previous process ended, for the conversation's restore (plan 7E; plan 7B's `ProcessEnd`), from the newest exit
 * record, as `exitReasonOf` reads it for listening's restore too. Only the owner's sure stop ends a restored goal; a stop
 * that cannot be told from an update on Android 13, any other end, and no record hold it and ask "Continue the goal?".
 */
internal fun processEndOf(record: ExitRecord?): ProcessEnd =
    record?.takeIf { it.reason == ExitReason.OWNER_STOPPED }?.let { ProcessEnd.OwnerStopped(it.timestamp) } ?: ProcessEnd.Other

/** Reads [exits] for the restore. When Android cannot give its record, [report] gets the failure and a goal is held. */
internal suspend fun readProcessEnd(exits: ExitRecords, report: (String, Throwable) -> Unit): ProcessEnd = try {
    processEndOf(exits.newest())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    report("Reading how Kestrel's last process ended", e)
    ProcessEnd.Other
}
```

- [ ] **Step 5: One reader in the app graph, and the restore takes its record**

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
import dev.kestrel.app.conversation.AndroidOwnerSupport
```

with:

```kotlin
import dev.kestrel.app.conversation.AndroidOwnerSupport
import dev.kestrel.app.conversation.readProcessEnd
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
import dev.kestrel.conversation.model.InputChannel
```

with:

```kotlin
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.ProcessEnd
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
    // Listening gives the conversation the owner's spoken inputs and its wait for a command after "Yes?", and gets the
```

with:

```kotlin
    // Android's records of how Kestrel's processes ended: one reader, which listening's restore and the conversation's
    // restore both read, so that one record has one reading (plan 7E).
    private val exitRecords: AndroidExitRecords by lazy { AndroidExitRecords(appContext) }

    /** How Kestrel's previous process ended, which the conversation's restore takes at the start (plan 7E). */
    internal suspend fun previousProcessEnd(): ProcessEnd = readProcessEnd(exitRecords, reportFailure)

    // Listening gives the conversation the owner's spoken inputs and its wait for a command after "Yes?", and gets the
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
            exits = AndroidExitRecords(appContext),
```

with:

```kotlin
            exits = exitRecords,
```

The conversation is restored once at the process's start, with how the previous process ended: a goal that the owner's stop in "Active apps" cut ends as "stop" ends it (the owner's decision of 2026-11-25).

In `app/src/main/kotlin/dev/kestrel/app/KestrelApplication.kt`, replace:

```kotlin
        graph.scope.launch { graph.conversations.restore() }
```

with:

```kotlin
        graph.scope.launch { graph.conversations.restore(graph.previousProcessEnd()) }
```

- [ ] **Step 6: Run the tests to see them pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.listen.ExitRecordsTest --tests=dev.kestrel.app.conversation.ProcessEndsTest --tests=dev.kestrel.app.listen.ListeningTest`
Expected: PASS: `ExitRecordsTest` 2, `ProcessEndsTest` 1, and every test of `ListeningTest`, whose restores after the owner's stop stay off as before, and `kestrelStaysOffAfterAStopThatCannotBeToldFromAnUpdate` with them.

- [ ] **Step 7: Run `:app`'s JVM tests, compile the device tests, count them and stop Gradle**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`, then `$n = 0; Get-ChildItem app\build\test-results\testDebugUnitTest -Filter *.xml | ForEach-Object { $n += [int]([xml](Get-Content $_.FullName -Raw)).testsuite.tests }; "app $n"`, then `scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL; `app` A₀ + 23. Task 8 checks the owner's stop on kestrel-api37 with `adb shell cmd activity stop-app dev.kestrel`, which Android's documentation names for this test, and Task 9 on the A71.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/dev/kestrel/app/listen/ExitRecords.kt app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt app/src/test/kotlin/dev/kestrel/app/listen/ExitRecordsTest.kt app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt app/src/main/kotlin/dev/kestrel/app/conversation/ProcessEnds.kt app/src/test/kotlin/dev/kestrel/app/conversation/ProcessEndsTest.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/main/kotlin/dev/kestrel/app/KestrelApplication.kt
git commit -m "feat(app): one reader of Android's exit record for both restores, so the owner's stop in Active apps ends a restored goal and a stop next to an update on Android 13 holds it (plan 7E)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: The service, its notification, its buttons and the wiring

The Android parts, kept thin (design 10.6 of sub-project 7): `GoalNotification` builds the goal notification (Cancel before Continue for a held goal, and Continue asking for the owner's unlock) and posts the held goal's notification without the service after a refusal; `GoalService` follows `GoalForeground.shown` with `startForeground()`, calls `startForeground()` at every start as Android requires after `startForegroundService()`, and reports to the coordinator; `GoalActionReceiver` gives the notification's Stop, Continue and Cancel to the conversation, only while the notification shows that button, each as the owner's input of that moment with the next number of his inputs, only for the goal that its notice showed, and decided after the conversation's restore when the tap started Kestrel's process (Task 2's `GoalButtons`); the manifest declares both and the permission `FOREGROUND_SERVICE_SPECIAL_USE`; and the app graph builds the coordinator and lets it follow the conversation beside plan 7D's `ConversationSignals`, so that the lock and call pauses with listening off now also show in the notification. The device tests run on kestrel-api37 in Task 8. They run in Kestrel's own instrumented process, where Android's rules for background starts and background audio do not apply as they do to Kestrel alone, so they check the wiring, and Task 8 checks Android's rules.

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/goal/GoalNotification.kt`, `app/src/main/kotlin/dev/kestrel/app/goal/GoalService.kt`, `app/src/main/kotlin/dev/kestrel/app/goal/GoalActionReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/conversation/GoalServiceTest.kt` (device)

**Interfaces:**
- Consumes: Tasks 1 to 4 (Task 1's `ConversationEngine.awaitRestored`, Task 2's `GoalButtons` and `NoticeGoal` among them); plan 7D's `AppGraph.conversations`, `followConversation`, `InputIds.next()`, `AppGraph.screenTurns` (`roundHolds()`), `AgentTests` (`start()`, `stop()`, which ends the conversation's work through `ConversationTests.endWork()`, `turnOff()`, `run(request, model: ModelPort): Int`, which closes the open conversation first through `ConversationTests.close()`, `ended(command): CommandOutcome`), `HeldModel` (`calls`, `release()`), `ScreenServiceRule`; `ListeningService.openKestrel(context)`.
- Produces: `internal object GoalNotification` with `SERVICE_ID = 7_102`, `POSTED_ID = 7_103`, `fun createChannel(context: Context)`, `fun build(context: Context, notice: GoalNotice): Notification`, `fun post(context: Context, notice: GoalNotice?)`, `fun intentOf(context: Context, action: GoalAction, goal: NoticeGoal?): PendingIntent` (the goal's key in the broadcast's data, `kestrel-goal:<conversation>/<record>`, so each goal has PendingIntents of its own; no `FLAG_UPDATE_CURRENT`) and `fun goalOf(intent: Intent): NoticeGoal?`; `AppGraph.goalStartRefusals: AtomicBoolean` (`internal`; only the debug hook `goal-control do=refuse` sets it, and the next start of the goal service throws `ForegroundServiceStartNotAllowedException` as Android's refusal does, for Task 8's check of a tap that starts Kestrel's process); `class GoalService : Service()` with `internal fun GoalService.Companion.start(context: Context)` (throws what `startForegroundService()` throws) and `internal fun GoalService.Companion.runningTypes(): Set<ForegroundType>?` (null while no instance is in the foreground); `class GoalActionReceiver : BroadcastReceiver()`; `AppGraph.goalForeground: GoalForeground` (`internal`, built on first use); the notification channel `goals`, named "Goals"; the manifest's `GoalService` (`exported="false"`, `foregroundServiceType="specialUse|mediaPlayback"`, with the property `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`) and `GoalActionReceiver` (`exported="false"`), and the permission `android.permission.FOREGROUND_SERVICE_SPECIAL_USE`.
- Note (amendment of 2026-11-26, `415ed6bd`): the IDs 7102 and 7103 collided with the problem notices "Kestrel stopped" and "accessibility service off", so the accessibility notice replaced the held goal's notification. They are now `KestrelNotification.GOAL_SERVICE.id` (7106) and `KestrelNotification.GOAL_POSTED.id` (7107); see the note after `GoalNotification.kt` below.

Counts: `:app` JVM A₀ + 23, unchanged. Device tests: AD₀ + 9 (`GoalServiceTest` 9, which Task 8 runs).

- [ ] **Step 1: Write the device test**

Create `app/src/androidTest/kotlin/dev/kestrel/app/conversation/GoalServiceTest.kt`:

```kotlin
package dev.kestrel.app.conversation

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.app.agent.AgentTests
import dev.kestrel.app.agent.HeldModel
import dev.kestrel.app.goal.ForegroundType
import dev.kestrel.app.goal.GoalActionReceiver
import dev.kestrel.app.goal.GoalNotification
import dev.kestrel.app.goal.GoalService
import dev.kestrel.app.goal.GoalTexts
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.safety.EmergencyGuard
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The goal service's wiring on kestrel-api37 (design 10.6 and 15.2 of sub-project 7; plan 7E): it runs for every open goal
 * with listening on or off, its notification follows the goal, its buttons reach the conversation, a held goal offers
 * Cancel before Continue and Continue asks for the owner's unlock, a held goal's Continue starts it again, the lock and call
 * pauses with listening off ask "Continue the goal?" in it and let go of the screen's turn at once, a pull of the shade
 * while the goal acts holds the goal, and it adds mediaPlayback while Kestrel speaks. The model's replies wait until the
 * test lets each one go (HeldModel), so that no step depends on the emulator's speed. The tests run in Kestrel's own
 * process, where Android lets the instrumentation start foreground services from the background, so they check the wiring,
 * and the controller checks Android's own rules for the starts and for background audio outside the tests (plan 7E, Task
 * 8). kestrel-api37 has no lock screen, so waking the screen unlocks it. Each test starts without a conversation
 * (`AgentTests.run` closes it through `ConversationTests`), and `AgentTests.stop` ends whatever "stop" stops.
 */
@RunWith(AndroidJUnit4::class)
class GoalServiceTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = AgentTests.graph
    private val conversations get() = graph.conversations

    // A say passes the agent's gate at the next turn, where a paused goal waits; fail ends the goal.
    private val onIt = call("say", null, null, null, "text" to "On it.")
    private val fail = call("fail", null, null, null, "reason" to "I stop here.")

    @Before
    fun start() {
        VoiceTests.assumeEmulator()
        AgentTests.start()
    }

    @After
    fun stop() {
        wake()
        screen.shell("cmd statusbar collapse")
        AgentTests.stop()
        check(VoiceTests.waitUntil(15_000) { GoalService.runningTypes() == null }) { "The goal service still runs" }
    }

    @Test
    fun aGoalRunsTheServiceWithListeningOnAndOffUntilItsFinalReply() {
        val model = HeldModel(ScriptedModel(listOf(onIt, fail)))
        val command = AgentTests.run("read my newest notification", model)
        awaitCalls(model, 1)

        val working = awaitShown("the working goal") { it.title == GoalTexts.WORKING }
        assertEquals(listOf(GoalTexts.STOP, GoalTexts.OPEN), working.actions)
        awaitTypes(setOf(ForegroundType.SPECIAL_USE))

        // Decision 10 of plan 7B: turning Kestrel off leaves the goal working, and the service with it (D19).
        AgentTests.turnOff()
        assertEquals(GoalPhase.ACTING, conversations.view.value.goal)
        assertEquals(setOf(ForegroundType.SPECIAL_USE), GoalService.runningTypes())

        model.release()
        awaitCalls(model, 2)
        model.release()
        assertEquals(EndReason.FAILED_BY_MODEL, AgentTests.ended(command).report.end)
        check(VoiceTests.waitUntil(15_000) { GoalService.runningTypes() == null && shown() == null }) { "The service outlived the goal" }
    }

    @Test
    fun theNotificationsStopEndsTheWorkingGoal() {
        val model = HeldModel(ScriptedModel(listOf(onIt, fail)))
        val command = AgentTests.run("read my newest notification", model)
        awaitCalls(model, 1)
        awaitShown("Stop") { GoalTexts.STOP in it.actions }

        press(GoalTexts.STOP)

        assertEquals(EndReason.CANCELLED, AgentTests.ended(command).report.end)
        assertEquals(1, model.calls)
    }

    @Test
    fun aHeldGoalOffersCancelBeforeContinueWhichAsksForTheUnlockAndResumesTheGoalWithANewStart() {
        val model = HeldModel(ScriptedModel(listOf(onIt, fail)))
        AgentTests.run("read my newest notification", model)
        awaitCalls(model, 1)
        awaitTypes(setOf(ForegroundType.SPECIAL_USE))
        val starts = graph.goalForeground.status().starts

        // D13: a touch holds the goal; the conversation takes it as the touch watcher gives it.
        conversations.touched()
        val held = awaitShown("Continue the goal?") { it.title == ConversationTexts.CONTINUE_GOAL }
        assertEquals(ConversationTexts.GO_ON, held.text)
        // The owner's decisions of 2026-11-25: Cancel first, and only Continue, which resumes the goal, asks for the unlock.
        assertEquals(listOf(GoalTexts.CANCEL, GoalTexts.CONTINUE), held.actions)
        val buttons = checkNotNull(notification()).actions.associateBy { it.title.toString() }
        assertTrue(checkNotNull(buttons[GoalTexts.CONTINUE]).isAuthenticationRequired)
        assertFalse(checkNotNull(buttons[GoalTexts.CANCEL]).isAuthenticationRequired)
        assertEquals(GoalPhase.INTERRUPTED, conversations.view.value.goal)
        assertEquals(starts, graph.goalForeground.status().starts)

        press(GoalTexts.CONTINUE)

        // Design 10.6: the resume starts the service again, from the notification's broadcast.
        awaitCalls(model, 2)
        val resumed = conversations.view.value.command
        awaitStarts(starts + 1)
        assertEquals(0, graph.goalForeground.status().refusals)
        model.release()
        awaitCalls(model, 3)
        model.release()
        assertEquals(EndReason.FAILED_BY_MODEL, AgentTests.ended(resumed).report.end)
    }

    @Test
    fun aPullOfTheShadeWhileTheGoalActsIsATouchThatHoldsTheGoal() {
        val model = HeldModel(ScriptedModel(listOf(onIt, fail)))
        AgentTests.run("read my newest notification", model)
        awaitCalls(model, 1)
        val width = context.resources.displayMetrics.widthPixels
        val height = context.resources.displayMetrics.heightPixels

        // Design 10.6: the owner's way to the notification's buttons is a pull of the shade, which is a touch (D13), so
        // the goal is held before he can tap a button, and the first button then is Cancel.
        screen.shell("input swipe ${width / 2} 5 ${width / 2} ${height / 2}")

        check(VoiceTests.waitUntil(15_000) { conversations.view.value.goal == GoalPhase.INTERRUPTED }) { "The pull of the shade did not hold the goal" }
        val held = awaitShown("Continue the goal?") { it.title == ConversationTexts.CONTINUE_GOAL }
        assertEquals(listOf(GoalTexts.CANCEL, GoalTexts.CONTINUE), held.actions)
    }

    @Test
    fun withListeningOffTheNotificationsContinueAnswersContinueTheGoalAfterALock() {
        val model = HeldModel(ScriptedModel(listOf(onIt, fail)))
        AgentTests.run("read my newest notification", model)
        awaitCalls(model, 1)
        AgentTests.turnOff()
        val starts = graph.goalForeground.status().starts

        screen.shell("input keyevent KEYCODE_SLEEP")
        check(VoiceTests.waitUntil(15_000) { conversations.view.value.goal == GoalPhase.PAUSED }) { "The lock did not pause the goal" }
        // The round is inside its first model call, which held the screen's turn; the pause is a wait for the owner, so the
        // round has let go of it at once (plan 7E; plan 7D's re-review, R5-1).
        assertFalse(graph.screenTurns.roundHolds())
        awaitShown("Continue the goal?") { it.title == ConversationTexts.CONTINUE_GOAL }
        // The model's first reply goes; the paused goal waits at its next step and asks the model nothing more.
        model.release()
        wake()
        check(VoiceTests.waitUntil(30_000) { conversations.view.value.asking == QuestionKind.CONTINUE_GOAL }) { "No \"Continue the goal?\" after the unlock" }
        assertEquals(1, model.calls)

        // With listening off, the notification answers it (and, from plan 7F, the conversation screen).
        press(GoalTexts.CONTINUE)

        awaitCalls(model, 2)
        awaitStarts(starts + 1)
        model.release()
        check(VoiceTests.waitUntil(30_000) { conversations.view.value.goal == null }) { "The goal did not end" }
        assertEquals(EndReason.FAILED_BY_MODEL, conversations.outcome(conversations.view.value.command)?.report?.end)
    }

    @Test
    fun aCallPausesTheGoalWithListeningOffUntilTheNotificationsContinue() {
        assumeTrue(
            "Needs Kestrel's phone-state permission, which plan 7E's Task 8 gives for this run and takes back",
            context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED,
        )
        val model = HeldModel(ScriptedModel(listOf(onIt, fail)))
        AgentTests.run("read my newest notification", model)
        awaitCalls(model, 1)
        AgentTests.turnOff()

        // No action of the agent's came before the call, so the call backstop leaves it alone: the owner placed it.
        screen.shell("am start -W -a android.intent.action.CALL -d tel:0640000000")
        check(VoiceTests.waitUntil(15_000) { conversations.view.value.goal == GoalPhase.PAUSED }) { "The call did not pause the goal" }
        awaitShown("Continue the goal?") { it.title == ConversationTexts.CONTINUE_GOAL }
        model.release()
        endCall()
        check(VoiceTests.waitUntil(30_000) { conversations.view.value.asking == QuestionKind.CONTINUE_GOAL }) { "No \"Continue the goal?\" after the call" }

        press(GoalTexts.CONTINUE)

        awaitCalls(model, 2)
        model.release()
        check(VoiceTests.waitUntil(30_000) { conversations.view.value.goal == null }) { "The goal did not end" }
        screen.shell("input keyevent KEYCODE_HOME")
    }

    /**
     * What this checks: while Kestrel speaks, the running service gets the type mediaPlayback in Android itself (its
     * `foregroundServiceType`), which means that Android accepted the second `startForeground()` with the added type and
     * that the speech waited for it, and the service drops the type after the speech. It does not check that Android plays
     * the audio: in the instrumented process the audio focus is given with or without the service, so Task 8 checks that
     * outside it, with the hook `goal-control do=speak|focus`.
     */
    @Test
    fun whileKestrelSpeaksTheRunningServiceHasMediaPlaybackAndDropsItAfter() = runBlocking<Unit> {
        assumeTrue("Android checks the types from Android 14 on", Build.VERSION.SDK_INT >= 34)
        val model = HeldModel(ScriptedModel(listOf(fail)))
        AgentTests.run("read my newest notification", model)
        awaitCalls(model, 1)
        AgentTests.turnOff()
        awaitTypes(setOf(ForegroundType.SPECIAL_USE))

        val during = graph.goalForeground.whileSpeaking { GoalService.runningTypes() }

        assertEquals(setOf(ForegroundType.SPECIAL_USE, ForegroundType.MEDIA_PLAYBACK), during)
        awaitTypes(setOf(ForegroundType.SPECIAL_USE))
        model.release()
    }

    // The second review of plan 7E (R2-I1): SystemUI may hold an older goal's button, as a Continue that waits for the
    // owner's unlock, while a newer goal is held. Each goal's buttons have PendingIntents of their own, so the older
    // Continue names the older goal, and the newer goal stays held.
    @Test
    fun anOlderGoalsContinueNeverResumesTheNextGoal() {
        val first = HeldModel(ScriptedModel(listOf(onIt, fail)))
        AgentTests.run("read my newest notification", first)
        awaitCalls(first, 1)
        conversations.touched()
        awaitShown("the first goal held") { it.title == ConversationTexts.CONTINUE_GOAL }
        val olderContinue = checkNotNull(notification()).actions.single { it.title.toString() == GoalTexts.CONTINUE }.actionIntent
        ConversationTests.endWork()

        val second = HeldModel(ScriptedModel(listOf(onIt, fail)))
        AgentTests.run("read my newest notification", second)
        awaitCalls(second, 1)
        conversations.touched()
        check(VoiceTests.waitUntil(15_000) { conversations.view.value.goal == GoalPhase.INTERRUPTED }) { "The touch did not hold the second goal" }
        awaitShown("the second goal held") { it.title == ConversationTexts.CONTINUE_GOAL }

        olderContinue.send()
        Thread.sleep(SETTLE_MILLIS)

        assertEquals(GoalPhase.INTERRUPTED, conversations.view.value.goal)
        assertEquals(1, second.calls)
    }

    @Test
    fun theManifestDeclaresTheServiceAndItsReceiverForKestrelAlone() {
        val packages = context.packageManager
        val service = ComponentName(context, GoalService::class.java)
        val info = packages.getServiceInfo(service, PackageManager.ComponentInfoFlags.of(0))
        assertFalse(info.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, info.foregroundServiceType)
        assertTrue(packages.getProperty("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE", service).string.orEmpty().startsWith("An on-device agent"))
        val receiver = packages.getReceiverInfo(ComponentName(context, GoalActionReceiver::class.java), PackageManager.ComponentInfoFlags.of(0))
        assertFalse(receiver.exported)
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
    }

    // The goal notification as the owner sees it: its title, its text and its buttons' labels.
    private class Shown(val title: String?, val text: String?, val actions: List<String>)

    private fun notification(): Notification? =
        context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == GoalNotification.SERVICE_ID }?.notification

    private fun shown(): Shown? = notification()?.let { notification ->
        Shown(
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            notification.actions.orEmpty().map { it.title.toString() },
        )
    }

    private fun awaitShown(what: String, condition: (Shown) -> Boolean): Shown {
        var last: Shown? = null
        check(VoiceTests.waitUntil(15_000) { shown()?.also { last = it }?.let(condition) == true }) { "The goal notification did not show $what" }
        return checkNotNull(last)
    }

    // Sends the button's PendingIntent, as SystemUI does when the owner taps it on an unlocked phone.
    private fun press(label: String) {
        checkNotNull(notification()).actions.single { it.title.toString() == label }.actionIntent.send()
    }

    private fun awaitTypes(types: Set<ForegroundType>) {
        check(VoiceTests.waitUntil(15_000) { GoalService.runningTypes() == types }) { "The goal service does not run with $types" }
    }

    // The start runs on the coordinator's collector, apart from the round that asks the model, so the test waits for it.
    private fun awaitStarts(starts: Int) {
        check(VoiceTests.waitUntil(15_000) { graph.goalForeground.status().starts == starts }) { "The goal service was not started again" }
    }

    private fun awaitCalls(model: HeldModel, calls: Int) {
        check(VoiceTests.waitUntil(30_000) { model.calls == calls }) { "The model did not get request $calls" }
    }

    private fun wake() {
        screen.shell("input keyevent KEYCODE_WAKEUP")
        screen.shell("wm dismiss-keyguard")
    }

    // The emulator's in-call screen names its end control "End call" (plan 2D); the key ends the call when it does not show.
    private fun endCall() {
        if (!VoiceTests.waitUntil(5_000) { screen.session.actions.clickLabelled("End call", EmergencyGuard.PHONE_PACKAGES) }) {
            screen.shell("input keyevent KEYCODE_ENDCALL")
        }
        check(VoiceTests.waitUntil(10_000) { !callActive() }) { "A call is still active" }
    }

    // Android's call state for each SIM: a line mCallState=0 is CALL_STATE_IDLE.
    private fun callActive() = screen.shell("dumpsys telephony.registry").lineSequence().map { it.trim() }
        .any { it.startsWith("mCallState=") && it != "mCallState=0" }

    private companion object {
        // How long a test watches a held goal stay held after a button that must not count.
        const val SETTLE_MILLIS = 3_000L
    }
}
```

- [ ] **Step 2: Compile the device test to see it fail**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin`
Expected: FAIL, the compiler reports `Unresolved reference 'GoalService'`, `Unresolved reference 'GoalNotification'` and `Unresolved reference 'goalForeground'`.

- [ ] **Step 3: The notification, the service and its receiver**

Create `app/src/main/kotlin/dev/kestrel/app/goal/GoalNotification.kt`:

```kotlin
package dev.kestrel.app.goal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.kestrel.app.R
import dev.kestrel.app.listen.ListeningService

/**
 * The goal notification on Android (design 10.6 of sub-project 7; plan 7E): its channel "Goals", the notification that
 * [GoalService] shows in the foreground ([SERVICE_ID]), and the held goal's notification that Kestrel posts without the
 * service after a refusal ([POSTED_ID], an ID of its own, so that taking it away never touches the service's). A tap opens
 * Kestrel's screen; the lock screen shows only "Kestrel is working on a task", because the current item may hold names.
 * The buttons are broadcasts to [GoalActionReceiver] whose data names the goal that the notice shows, each goal with
 * PendingIntents of its own, and with no inline reply (D8);
 * a button that resumes what Kestrel does asks Android for the owner's unlock first (the owner's decision of 2026-11-25).
 */
internal object GoalNotification {
    const val SERVICE_ID = 7_102
    const val POSTED_ID = 7_103
    private const val CHANNEL = "goals"

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, GoalTexts.CHANNEL, NotificationManager.IMPORTANCE_LOW))
    }

    fun build(context: Context, notice: GoalNotice): Notification {
        val public = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(GoalTexts.PUBLIC)
            .build()
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(notice.title)
            .setContentIntent(ListeningService.openKestrel(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        notice.text?.let { builder.setContentText(it) }
        notice.progress?.let { builder.setProgress(it.total, it.done, false) }
        for (action in notice.actions) {
            builder.addAction(
                Notification.Action.Builder(null, action.label, intentOf(context, action, notice.goal))
                    .setAuthenticationRequired(action.resumes)
                    .build(),
            )
        }
        return builder.build()
    }

    /** Posts [notice] as the held goal's notification without the service, or takes it away when [notice] is null. */
    fun post(context: Context, notice: GoalNotice?) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (notice == null) {
            notifications.cancel(POSTED_ID)
            return
        }
        createChannel(context)
        notifications.notify(POSTED_ID, build(context, notice))
    }

    /**
     * The PendingIntent of [action]'s button for [goal], the goal that the notice shows: Open opens Kestrel's screen, the
     * others are broadcasts to [GoalActionReceiver] whose data names the goal (plan 7E). Android matches PendingIntents by
     * their intent's action and data, not by extras, so each goal's buttons get PendingIntents of their own, and a newer
     * goal's notice never rewrites the button of an older notice that SystemUI still holds, as a Continue that waits for the
     * owner's unlock (the second review of plan 7E, R2-I1). Nothing of a PendingIntent is ever updated.
     */
    fun intentOf(context: Context, action: GoalAction, goal: NoticeGoal?): PendingIntent =
        if (action.intent == null) {
            ListeningService.openKestrel(context)
        } else {
            val intent = Intent(context, GoalActionReceiver::class.java).setAction(action.broadcast)
            goal?.let { intent.setData(Uri.fromParts(SCHEME, it.key, null)) }
            PendingIntent.getBroadcast(context, action.ordinal, intent, PendingIntent.FLAG_IMMUTABLE)
        }

    /** The goal that a button's broadcast names in its data; null when it names none. */
    fun goalOf(intent: Intent): NoticeGoal? = intent.data?.takeIf { it.scheme == SCHEME }?.schemeSpecificPart.let(NoticeGoal::ofKey)

    private const val SCHEME = "kestrel-goal"
}
```

**Note (amendment of 2026-11-26, Task 8's third run, `415ed6bd`).** The code above, as built at Task 5, gave the goal's notifications `SERVICE_ID = 7_102` and `POSTED_ID = 7_103`. Those were also `ProblemNotifications.STOPPED_ID` ("Kestrel stopped") and `ACCESSIBILITY_ID` ("accessibility service off"), and Android keeps one notification per ID and app: when Kestrel's accessibility service went off, its notice replaced the held goal's notification and then took it away, so the owner lost Cancel and Continue while Kestrel's state still said `posted=held`. `415ed6bd` adds the enum `KestrelNotification` (package `dev.kestrel.app`), which names every kind of Kestrel's notifications once, with the ID 7101 plus its position; a new kind goes at the end. The older kinds keep their IDs (listening 7101, the problem notices 7102 to 7104, sign-in 7105), and the goal's two move to the end: `GoalNotification.SERVICE_ID = KestrelNotification.GOAL_SERVICE.id` (7106) and `POSTED_ID = KestrelNotification.GOAL_POSTED.id` (7107), now `val`s. `ListeningService`, `ProblemNotifications` and `SignInService` take their IDs from the enum too.

Create `app/src/main/kotlin/dev/kestrel/app/goal/GoalService.kt`:

```kotlin
package dev.kestrel.app.goal

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.kestrel.app.KestrelApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps a goal working while Kestrel's screen is not in front (design 10.6 of sub-project 7; plan 7E): a foreground service
 * of the type specialUse from Android 14 on, with mediaPlayback added while Kestrel speaks a typed command's reply with
 * listening off, and of no type on Android 13, which checks none. [GoalForeground] decides when it runs, what it shows and
 * with which types; the service follows [GoalForeground.shown] and tells it when it is in the foreground, refused or gone.
 * Every start calls startForeground(), as Android requires after startForegroundService(), and a start that comes after
 * the goal's end stops the service again; a refused new start of a service that runs leaves it running. Android calls
 * every callback on the main thread.
 */
class GoalService : Service() {
    private val graph get() = (application as KestrelApplication).graph
    private val foreground: GoalForeground get() = graph.goalForeground
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notifications: NotificationManager
    private var latestStart = 0
    private var types: Set<ForegroundType> = emptySet()

    @Volatile
    private var inForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        current = this
        notifications = getSystemService(NotificationManager::class.java)
        GoalNotification.createChannel(this)
        scope.launch { foreground.shown.collect { shown -> if (inForeground) follow(shown) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStart = startId
        val shown = foreground.shown.value
        val wanted = shown?.types ?: GoalServiceRules.types(Build.VERSION.SDK_INT, speaking = false)
        try {
            startForeground(GoalNotification.SERVICE_ID, GoalNotification.build(this, shown?.notice ?: GoalNotices.CLOSING), mask(wanted))
        } catch (e: Exception) {
            // For example ForegroundServiceStartNotAllowedException: Android refused the start (design 10.6 of sub-project 7).
            // A service that is in the foreground already stays, as the rules say.
            foreground.failed(e)
            if (!inForeground) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        inForeground = true
        types = wanted
        foreground.started(wanted)
        // A start that came after the goal's end has shown the end, as Android requires, and stops.
        if (foreground.shown.value == null) stopSelfResult(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (current === this) current = null
        scope.cancel()
        inForeground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        foreground.destroyed()
        super.onDestroy()
    }

    // A new notice updates the notification; new types need startForeground() again, which Android may refuse, and then
    // the service keeps its types and shows the notice.
    private fun follow(shown: Shown?) {
        if (shown == null) {
            stopSelfResult(latestStart)
            return
        }
        if (shown.types != types) {
            try {
                startForeground(GoalNotification.SERVICE_ID, GoalNotification.build(this, shown.notice), mask(shown.types))
                types = shown.types
                foreground.applied(shown.types)
                return
            } catch (e: Exception) {
                graph.report("Changing the goal service's types", e)
            }
        }
        notifications.notify(GoalNotification.SERVICE_ID, GoalNotification.build(this, shown.notice))
    }

    companion object {
        // The service while it runs, for runningTypes(); onDestroy clears it, so it cannot keep a destroyed service alive.
        @Volatile
        private var current: GoalService? = null

        /** Asks Android to start the service in the foreground; throws what startForegroundService() throws. */
        internal fun start(context: Context) {
            context.startForegroundService(Intent(context, GoalService::class.java))
        }

        /** The types with which the service is in the foreground, or null while none is: for the hooks and the device tests. */
        internal fun runningTypes(): Set<ForegroundType>? = current?.takeIf { it.inForeground }?.let { typesOf(it.foregroundServiceType) }

        private fun mask(types: Set<ForegroundType>): Int = types.fold(0) { bits, type -> bits or bitOf(type) }

        private fun typesOf(mask: Int): Set<ForegroundType> = ForegroundType.entries.filterTo(mutableSetOf()) { mask and bitOf(it) != 0 }

        private fun bitOf(type: ForegroundType): Int = when (type) {
            ForegroundType.SPECIAL_USE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            ForegroundType.MEDIA_PLAYBACK -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
    }
}
```

Create `app/src/main/kotlin/dev/kestrel/app/goal/GoalActionReceiver.kt`:

```kotlin
package dev.kestrel.app.goal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.conversation.InputIds
import kotlinx.coroutines.launch

/**
 * Receives the goal notification's Stop, Continue and Cancel (design 10.6 of sub-project 7): each becomes an owner's input
 * of the conversation (design 6.4), with the next number of his inputs, only for the goal that its notice showed and only
 * while that goal's notice offers what the button does (`GoalButtons`). A tap on the held goal's notification that Kestrel
 * posted without the service can start Kestrel's process; the receiver keeps the broadcast alive (`goAsync`) until the
 * conversation is restored, at most `GoalButtons.RESTORE_WAIT_MILLIS`, and decides against the restored goal
 * (`GoalButtons.press`), so the tap is not lost and the broadcast never outlives Android's limit. It is not
 * exported, so only Kestrel's own PendingIntents reach it, as the listening notification's receiver (spec 8.6).
 */
class GoalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as KestrelApplication).graph
        val conversations = graph.conversations
        val action = intent.action
        val goal = GoalNotification.goalOf(intent)
        val pending = goAsync()
        graph.scope.launch {
            try {
                val restored = suspend {
                    conversations.awaitRestored()
                    conversations.view.value
                }
                GoalButtons.press(action, goal, restored, InputIds::next, System::currentTimeMillis, graph::report)?.let(conversations::submit)
            } finally {
                pending.finish()
            }
        }
    }
}
```

- [ ] **Step 4: The manifest**

In `app/src/main/AndroidManifest.xml`, replace:

```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
```

with:

```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <!-- Granted at install, for GoalService from Android 14 on (design 10.6 of sub-project 7; spec 13.6). -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
```

In `app/src/main/AndroidManifest.xml`, replace:

```xml
        <receiver
            android:name=".listen.NotificationActionReceiver"
            android:exported="false" />
```

with:

```xml
        <receiver
            android:name=".listen.NotificationActionReceiver"
            android:exported="false" />

        <!-- Keeps a goal working while Kestrel's screen is not in front, and shows its notification (design 10.6 of
             sub-project 7; plan 7E): specialUse from Android 14 on, with mediaPlayback added while Kestrel speaks a typed
             command's reply with listening off. Android 13 checks no type. -->
        <service
            android:name=".goal.GoalService"
            android:exported="false"
            android:foregroundServiceType="specialUse|mediaPlayback">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="An on-device agent that carries out the owner's spoken or typed request on the phone through its accessibility service while the owner watches; its notification shows the progress and stops it." />
        </service>

        <!-- The goal notification's Stop, Continue and Cancel (design 10.6 of sub-project 7). Only Kestrel's own
             PendingIntents reach it. -->
        <receiver
            android:name=".goal.GoalActionReceiver"
            android:exported="false" />
```

- [ ] **Step 5: The app graph**

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
import dev.kestrel.app.download.networkKind
```

with:

```kotlin
import android.app.ForegroundServiceStartNotAllowedException
import dev.kestrel.app.download.networkKind
import dev.kestrel.app.goal.GoalForeground
import dev.kestrel.app.goal.GoalNotification
import dev.kestrel.app.goal.GoalService
import java.util.concurrent.atomic.AtomicBoolean
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
    // The voice reads the conversation's view, and the phone's lock and calls reach the conversation while it is open
    // (design 5.5 and 6.5 of sub-project 7).
    private fun followConversation(engine: ConversationEngine) {
        scope.launch { engine.view.forVoice().collect { view -> listening.send(Event.ConversationChanged(view)) } }
        scope.launch { ConversationSignals(phoneSignals, callBackstop, scope, engine::signal, reportFailure).follow(engine.view) }
    }
```

with:

```kotlin
    // The voice reads the conversation's view, the phone's lock and calls reach the conversation while it is open (design
    // 5.5 and 6.5 of sub-project 7), and the goal service follows its goal, with the held goal's notification that Kestrel
    // posts without the service after a refusal (design 10.6; plan 7E).
    private fun followConversation(engine: ConversationEngine) {
        scope.launch { engine.view.forVoice().collect { view -> listening.send(Event.ConversationChanged(view)) } }
        scope.launch { ConversationSignals(phoneSignals, callBackstop, scope, engine::signal, reportFailure).follow(engine.view) }
        scope.launch { goalForeground.follow(engine.view) }
        scope.launch { goalForeground.posted.collect { notice -> GoalNotification.post(appContext, notice) } }
    }

    /**
     * The goal service's coordinator (design 10.6 of sub-project 7; plan 7E): it starts GoalService for every open goal,
     * with listening on or off, and again at each start or resume of the goal's work, and a start that Android refuses
     * holds the goal of the command that asked for it. GoalService, the notification's receiver and the debug hooks read it.
     */
    internal val goalForeground: GoalForeground by lazy {
        GoalForeground(
            start = {
                if (goalStartRefusals.getAndSet(false)) throw ForegroundServiceStartNotAllowedException("goal-control do=refuse")
                GoalService.start(appContext)
            },
            holdGoal = { command -> conversations.backgroundRefused(command) },
            sdk = Build.VERSION.SDK_INT,
            report = reportFailure,
        )
    }

    /**
     * Debug builds only: the hook `goal-control do=refuse` sets it, and the goal service's next start then throws as
     * Android's refusal does, so that Task 8 of plan 7E checks the held goal's notification that Kestrel posts without the
     * service, and a tap on it that starts Kestrel's process. A release build never sets it.
     */
    internal val goalStartRefusals = AtomicBoolean(false)
```

- [ ] **Step 6: Build, run the JVM tests and lint, compile the device tests, count them and stop Gradle**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug`, then `$n = 0; Get-ChildItem app\build\test-results\testDebugUnitTest -Filter *.xml | ForEach-Object { $n += [int]([xml](Get-Content $_.FullName -Raw)).testsuite.tests }; "app $n"`, then `"device $(@(Get-ChildItem app\src\androidTest -Recurse -Filter *.kt | Select-String -Pattern '@Test\b').Count)"`, then `scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL; lint reports no error (a warning `InlinedApi` for `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`, which only Android 14 and later see, is expected); `app` A₀ + 23; `device` AD₀ + 9. Task 8 runs the device tests.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/main/kotlin/dev/kestrel/app/goal app/src/androidTest/kotlin/dev/kestrel/app/conversation/GoalServiceTest.kt
git commit -m "feat(app): GoalService runs for every open goal with its notification, Stop and Open, Cancel and an unlocked Continue, the held goal posted after a refusal, and mediaPlayback while Kestrel speaks (plan 7E)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: The hooks `goal-service-state` and `goal-control`

The controller checks Android's own rules for the goal's starts and for background audio outside the instrumentation (Task 8 on kestrel-api37 and Task 9 on the A71), so the debug build gets two hooks. `goal-service-state` reads the service in kinds and counts, with the newest audio-focus result. `goal-control` does six things. `tap` presses a button of the goal notification in SystemUI's shade by the button's key, which the press checks again in every snapshot that it acts on, so that it never presses a row that moved or another app's button; SystemUI then sends the button's broadcast as it does for the owner's finger. `touch` gives the conversation a touch as the touch watcher gives it, where a real touch on the screen is not safe, as on the A71. `speak` asks Android for the audio focus as Kestrel's voice does, inside `GoalForeground.whileSpeaking`. `focus` asks for it without that, as the control (spike S9; main spec B5). `refuse` has the goal service's next start refused as Android refuses it, so that Task 8 can check the held goal's notification that Kestrel posts without the service, and a tap on it that starts Kestrel's process. `send-later` hands the PendingIntent of a button of Kestrel's own posted goal notification, the very one that Android holds on the notification, to `AlarmManager`, which sends it about 5 s later, so that Task 8 can end Kestrel's process meanwhile and the system delivers the button's broadcast to a new process as it delivers the owner's tap; nothing is tapped on the screen. The speak and focus requests run on the app graph's scope a moment after the hook's broadcast has returned, so that the broadcast's own standing does not count.

**Files:**
- Create: `app/src/debug/kotlin/dev/kestrel/app/debug/FocusProbe.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/GoalServiceAnswers.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/NoticeButtons.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/NoticeIntents.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/GoalServiceHooks.kt`
- Modify: `app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt`, `app/src/debug/AndroidManifest.xml`
- Test: `app/src/testDebug/kotlin/dev/kestrel/app/debug/GoalServiceAnswersTest.kt`, `app/src/testDebug/kotlin/dev/kestrel/app/debug/NoticeButtonsTest.kt`, `app/src/testDebug/kotlin/dev/kestrel/app/debug/NoticeIntentsTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/debug/GoalServiceHooksTest.kt` (device)

**Interfaces:**
- Consumes: Tasks 2 to 5 (`AppGraph.goalStartRefusals`, `GoalForeground.status()`, `GoalForeground.notice()`, `GoalForeground.whileSpeaking`, `GoalService.runningTypes()`, `GoalAction`, `GoalNotice`, `NoticeKind`, `ForegroundType`, `ServiceState`); plan 7B's `ConversationEngine.touched()`; `AppGraph.scope`; `ScreenSession.actions` (`global`, `expand`, `tap(key, expected, policy, guard)`) and `ScreenSession.snapshots`; `PressPolicy.ONCE`; `ScreenSnapshot`, `ScreenElement` (`key`, `parent`, `packageName`, `text`, `contentDescription`, `clickable`, `actions`), `ElementAction`, `GlobalAction.NOTIFICATIONS` and `GlobalAction.DISMISS_SHADE`; `GoalNotification.SERVICE_ID` and `POSTED_ID`; Android's `NotificationManager.getActiveNotifications()` and `AlarmManager.setExact` with `USE_EXACT_ALARM`; `TestHooks`, `TestHooks.HookError`.
- Produces (package `dev.kestrel.app.debug`, debug builds only): `data class FocusResult(val granted: Boolean, val speaking: Boolean, val types: Set<ForegroundType>?)` and `object FocusProbe { @Volatile var last: FocusResult?; suspend fun request(context: Context, foreground: GoalForeground, speaking: Boolean): FocusResult; DELAY_MILLIS = 1_000L }`; `object GoalServiceAnswers { fun state(status: GoalServiceStatus, android: Set<ForegroundType>?, focus: FocusResult?): String }`; `object NoticeButtons { fun button(snapshot: ScreenSnapshot, title: String, label: String): String?; fun isButton(element: ScreenElement, label: String): Boolean; fun collapsedRow(snapshot: ScreenSnapshot, title: String): String? }`; `object NoticeIntents { data class Posted<T>(val id: Int, val buttons: List<Pair<String, T>>); fun idOf(status: GoalServiceStatus, action: GoalAction): Int?; fun <T> intentOf(posted: List<Posted<T>>, id: Int, label: String): T? }`; `class GoalServiceHooks(graph: AppGraph, context: Context)` with `COMMANDS = setOf("GOAL_SERVICE_STATE", "GOAL_CONTROL")`. The hooks: `dev.kestrel.hook.GOAL_SERVICE_STATE` answers `service=stopped|starting|running notice=working|held|closing|none posted=held|none actions=<a,b>|none progress=<done>/<total>|none types=<t+t>|none applied=<t+t>|none android=running|none androidTypes=<t+t>|none starts=<n> refusals=<n> focus=granted|failed|none focusBy=speak|focus|none focusTypes=<t+t>|none`, with the types `special-use` and `media-playback`; `dev.kestrel.hook.GOAL_CONTROL --es do tap --es action stop|open|continue|cancel` answers `tapped=<action>`, `--es do touch` answers `touch=sent`, `--es do speak|focus` answers `speak=sent` or `focus=sent`, whose result `goal-service-state` shows about a second later, `--es do refuse` answers `refuse=armed`: the goal service's next start is refused as Android refuses it (`AppGraph.goalStartRefusals`), once; and `--es do send-later --es action stop|continue|cancel` answers `send=scheduled`: `AlarmManager` sends the button's PendingIntent, as Android holds it on Kestrel's goal notification, `SEND_LATER_MILLIS` (5 s) later; or `send=none` when Kestrel's goal notification shows no such button.

Counts: `:app` JVM A₀ + 23 + 4 = A₀ + 27 (`GoalServiceAnswersTest` 1, `NoticeButtonsTest` 2, `NoticeIntentsTest` 1). Device tests: AD₀ + 9 + 2 = AD₀ + 11 (`GoalServiceHooksTest` 2).

- [ ] **Step 1: Write the failing tests**

Create `app/src/testDebug/kotlin/dev/kestrel/app/debug/GoalServiceAnswersTest.kt`:

```kotlin
package dev.kestrel.app.debug

import dev.kestrel.app.goal.ForegroundType
import dev.kestrel.app.goal.GoalAction
import dev.kestrel.app.goal.GoalNotice
import dev.kestrel.app.goal.GoalServiceStatus
import dev.kestrel.app.goal.NoticeKind
import dev.kestrel.app.goal.NoticeProgress
import dev.kestrel.app.goal.ServiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The answer of the hook goal-service-state (plan 7E): kinds and counts, never the notification's words. */
class GoalServiceAnswersTest {
    @Test
    fun theAnswerHoldsKindsAndCountsOnly() {
        val notice = GoalNotice(NoticeKind.WORKING, "Kestrel is working", "Reply to Mila", NoticeProgress(3, 7), listOf(GoalAction.STOP, GoalAction.OPEN))
        val status = GoalServiceStatus(
            ServiceState.RUNNING,
            notice,
            null,
            setOf(ForegroundType.SPECIAL_USE, ForegroundType.MEDIA_PLAYBACK),
            setOf(ForegroundType.SPECIAL_USE),
            2,
            0,
        )
        val focus = FocusResult(granted = true, speaking = true, types = setOf(ForegroundType.SPECIAL_USE, ForegroundType.MEDIA_PLAYBACK))

        val answer = GoalServiceAnswers.state(status, setOf(ForegroundType.SPECIAL_USE), focus)

        assertEquals(
            "service=running notice=working posted=none actions=stop,open progress=3/7 types=special-use+media-playback " +
                "applied=special-use android=running androidTypes=special-use starts=2 refusals=0 focus=granted focusBy=speak " +
                "focusTypes=special-use+media-playback",
            answer,
        )
        assertFalse(answer.contains("Mila"))
        // After a refusal: no service, the held goal posted without it, and its buttons; a control request without a service.
        val held = GoalNotice(NoticeKind.HELD, "Continue the goal?", "Tap Continue in Kestrel's notification.", null, listOf(GoalAction.CANCEL, GoalAction.CONTINUE))
        assertEquals(
            "service=stopped notice=none posted=held actions=cancel,continue progress=none types=none applied=none android=none " +
                "androidTypes=none starts=1 refusals=1 focus=failed focusBy=focus focusTypes=none",
            GoalServiceAnswers.state(GoalServiceStatus(ServiceState.STOPPED, null, held, null, null, 1, 1), null, FocusResult(false, false, null)),
        )
    }
}
```

Create `app/src/testDebug/kotlin/dev/kestrel/app/debug/NoticeButtonsTest.kt`:

```kotlin
package dev.kestrel.app.debug

import dev.kestrel.screen.model.ElementAction
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

/** The goal notification's buttons in SystemUI's shade, for the hook goal-control (plan 7E). */
class NoticeButtonsTest {
    private val shade = WindowSummary(id = 1, kind = WindowKind.SYSTEM, packageName = SYSTEM_UI, bounds = IntRect(0, 0, 1080, 2400))

    private fun element(
        key: String,
        parent: Int?,
        text: String? = null,
        clickable: Boolean = false,
        actions: Set<ElementAction> = emptySet(),
        packageName: String = SYSTEM_UI,
    ) = ScreenElement(key = key, windowId = 1, parent = parent, packageName = packageName, text = text, bounds = IntRect(0, 0, 10, 10), actions = actions, clickable = clickable)

    @Test
    fun theButtonIsTheOneInKestrelsRowNotAnotherAppsWithTheSameLabel() {
        val snapshot = ScreenSnapshot(
            takenAtMillis = 0,
            windows = listOf(shade),
            elements = listOf(
                element("shade", null),
                element("row-other", 0, actions = setOf(ElementAction.COLLAPSE)),
                element("other-title", 1, text = "Download paused"),
                element("other-continue", 1, text = "Continue", clickable = true),
                element("row-kestrel", 0, actions = setOf(ElementAction.COLLAPSE)),
                element("kestrel-title", 4, text = "Continue the goal?"),
                // Some phones write a notification's buttons in capitals.
                element("kestrel-continue", 4, text = "CONTINUE", clickable = true),
            ),
        )

        assertEquals("kestrel-continue", NoticeButtons.button(snapshot, "Continue the goal?", "Continue"))
        assertNull(NoticeButtons.collapsedRow(snapshot, "Continue the goal?"))
        assertNull(NoticeButtons.button(snapshot, "Kestrel is working", "Stop"))
        // The press checks the element again: SystemUI's clickable element with the label, nothing else.
        assertTrue(NoticeButtons.isButton(snapshot.elements[6], "Continue"))
        assertFalse(NoticeButtons.isButton(snapshot.elements[5], "Continue"))
        assertFalse(NoticeButtons.isButton(element("app-continue", null, text = "Continue", clickable = true, packageName = "com.example"), "Continue"))
    }

    @Test
    fun aCollapsedRowIsNamedForExpandingAndShowsNoButtonYet() {
        val snapshot = ScreenSnapshot(
            takenAtMillis = 0,
            windows = listOf(shade),
            elements = listOf(
                element("shade", null),
                element("row-kestrel", 0, actions = setOf(ElementAction.EXPAND)),
                element("kestrel-title", 1, text = "Kestrel is working"),
            ),
        )

        assertNull(NoticeButtons.button(snapshot, "Kestrel is working", "Stop"))
        assertEquals("row-kestrel", NoticeButtons.collapsedRow(snapshot, "Kestrel is working"))
    }

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
    }
}
```

Create `app/src/testDebug/kotlin/dev/kestrel/app/debug/NoticeIntentsTest.kt`:

```kotlin
package dev.kestrel.app.debug

import dev.kestrel.app.goal.ForegroundType
import dev.kestrel.app.goal.GoalAction
import dev.kestrel.app.goal.GoalNotice
import dev.kestrel.app.goal.GoalNotification
import dev.kestrel.app.goal.GoalServiceStatus
import dev.kestrel.app.goal.NoticeKind
import dev.kestrel.app.goal.ServiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The button's PendingIntent that the hook goal-control do=send-later hands to AlarmManager (plan 7E; the second review's
 * R4-M1): the one on the notification that shows the button now, as Android holds it posted, never one of another of
 * Kestrel's notifications with the same label.
 */
class NoticeIntentsTest {
    @Test
    fun theIntentIsTheButtonsOnTheNotificationThatShowsItNow() {
        val held = GoalNotice(NoticeKind.HELD, "Continue the goal?", "Tap Continue in Kestrel's notification.", null, listOf(GoalAction.CANCEL, GoalAction.CONTINUE))
        val working = GoalNotice(NoticeKind.WORKING, "Kestrel is working", "Reply to Mila", null, listOf(GoalAction.STOP, GoalAction.OPEN))
        val posted = GoalServiceStatus(ServiceState.STOPPED, null, held, null, null, 1, 1)
        val running = GoalServiceStatus(ServiceState.RUNNING, working, null, setOf(ForegroundType.SPECIAL_USE), setOf(ForegroundType.SPECIAL_USE), 1, 0)
        val shown = listOf(
            NoticeIntents.Posted(OTHER_ID, listOf(GoalAction.CANCEL.label to "other-cancel")),
            NoticeIntents.Posted(GoalNotification.POSTED_ID, listOf(GoalAction.CANCEL.label to "held-cancel", GoalAction.CONTINUE.label to "held-continue")),
            NoticeIntents.Posted(GoalNotification.SERVICE_ID, listOf(GoalAction.STOP.label to "working-stop")),
        )

        // The held goal posted without the service: its Cancel, not the Cancel of another of Kestrel's notifications.
        val heldId = NoticeIntents.idOf(posted, GoalAction.CANCEL)
        assertEquals(GoalNotification.POSTED_ID, heldId)
        assertEquals("held-cancel", NoticeIntents.intentOf(shown, checkNotNull(heldId), GoalAction.CANCEL.label))
        // The working goal in the service's notification: its Stop; it offers no Cancel.
        assertEquals("working-stop", NoticeIntents.intentOf(shown, checkNotNull(NoticeIntents.idOf(running, GoalAction.STOP)), GoalAction.STOP.label))
        assertNull(NoticeIntents.idOf(running, GoalAction.CANCEL))
        // A notification that Android no longer shows, or a button that it does not carry, has no intent.
        assertNull(NoticeIntents.intentOf(shown.take(1), GoalNotification.POSTED_ID, GoalAction.CANCEL.label))
        assertNull(NoticeIntents.intentOf(shown, GoalNotification.SERVICE_ID, GoalAction.OPEN.label))
    }

    private companion object {
        const val OTHER_ID = 7_001
    }
}
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/debug/GoalServiceHooksTest.kt`:

```kotlin
package dev.kestrel.app.debug

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.app.agent.AgentTests
import dev.kestrel.app.agent.HeldModel
import dev.kestrel.app.goal.NoticeKind
import dev.kestrel.app.goal.ServiceState
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.conversation.model.GoalPhase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The goal service's hooks on kestrel-api37 (plan 7E): goal-service-state in kinds and counts, the audio-focus request's
 * answer, and goal-control's press of the held goal's Continue in SystemUI's shade, which resumes the goal as the owner's
 * tap would. The focus that the instrumented process gets proves nothing about Android's hardening; this checks only that
 * the hook answers, and Task 8 runs it outside the instrumentation. `AgentTests.stop` ends whatever "stop" stops.
 */
@RunWith(AndroidJUnit4::class)
class GoalServiceHooksTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = AgentTests.graph
    private val conversations get() = graph.conversations
    private val onIt = call("say", null, null, null, "text" to "On it.")
    private val fail = call("fail", null, null, null, "reason" to "I stop here.")

    @Before
    fun start() {
        VoiceTests.assumeEmulator()
        AgentTests.start()
    }

    @After
    fun stop() {
        screen.shell("cmd statusbar collapse")
        AgentTests.stop()
    }

    @Test
    fun goalServiceStateAnswersInKindsAndCountsWhileAGoalWorks() {
        val model = HeldModel(ScriptedModel(listOf(fail)))
        AgentTests.run("read my newest notification", model)
        check(VoiceTests.waitUntil(30_000) { model.calls == 1 && graph.goalForeground.status().service == ServiceState.RUNNING }) { "No goal service" }

        val answer = hook("GOAL_SERVICE_STATE")

        assertTrue(
            answer,
            Regex(
                "service=running notice=working posted=none actions=stop,open progress=none types=special-use applied=special-use " +
                    "android=running androidTypes=special-use starts=\\d+ refusals=0 focus=(none|granted|failed) focusBy=(none|speak|focus) " +
                    "focusTypes=\\S+",
            ).matches(answer),
        )
        assertEquals("speak=sent", hook("GOAL_CONTROL", "do" to "speak"))
        check(VoiceTests.waitUntil(10_000) { hook("GOAL_SERVICE_STATE").contains("focusBy=speak focusTypes=special-use+media-playback") }) {
            "The speak request did not answer with the speech's types"
        }
        assertEquals("error: add --es do with one of tap, touch, speak, focus, refuse or send-later", hook("GOAL_CONTROL", "do" to "other"))
        assertEquals("error: add --es action with one of stop, open, continue or cancel", hook("GOAL_CONTROL", "do" to "tap"))
        model.release()
    }

    @Test
    fun goalControlPressesTheHeldGoalsContinueInTheShadeAndTheGoalGoesOn() {
        val model = HeldModel(ScriptedModel(listOf(onIt, fail)))
        AgentTests.run("read my newest notification", model)
        check(VoiceTests.waitUntil(30_000) { model.calls == 1 && graph.goalForeground.status().service == ServiceState.RUNNING }) {
            "The model got no request, or the goal service does not run"
        }

        assertEquals("touch=sent", hook("GOAL_CONTROL", "do" to "touch"))
        check(VoiceTests.waitUntil(15_000) {
            conversations.view.value.goal == GoalPhase.INTERRUPTED && graph.goalForeground.notice()?.kind == NoticeKind.HELD
        }) { "The touch did not hold the goal" }
        val starts = graph.goalForeground.status().starts

        assertEquals("tapped=continue", hook("GOAL_CONTROL", "do" to "tap", "action" to "continue"))

        check(VoiceTests.waitUntil(30_000) { model.calls == 2 }) { "The goal did not go on" }
        check(VoiceTests.waitUntil(15_000) { graph.goalForeground.status().starts == starts + 1 }) { "The goal service was not started again" }
        model.release()
        check(VoiceTests.waitUntil(30_000) { model.calls == 3 }) { "The goal took no next step" }
        model.release()
        check(VoiceTests.waitUntil(30_000) { conversations.view.value.goal == null }) { "The goal did not end" }
    }

    private fun hook(command: String, vararg extras: Pair<String, String>): String {
        val intent = Intent(TestHooks.ACTION_PREFIX + command)
        for ((key, value) in extras) intent.putExtra(key, value)
        return runBlocking { TestHooks(graph, context).run(intent) }.text
    }
}
```

- [ ] **Step 2: Run the JVM tests to see them fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.debug.GoalServiceAnswersTest --tests=dev.kestrel.app.debug.NoticeButtonsTest --tests=dev.kestrel.app.debug.NoticeIntentsTest`
Expected: FAIL, the compiler reports `Unresolved reference 'GoalServiceAnswers'`, `Unresolved reference 'FocusResult'`, `Unresolved reference 'NoticeButtons'` and `Unresolved reference 'NoticeIntents'`.

- [ ] **Step 3: The focus probe, the answers, the buttons, their intents and the hooks**

Create `app/src/debug/kotlin/dev/kestrel/app/debug/FocusProbe.kt`:

```kotlin
package dev.kestrel.app.debug

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import dev.kestrel.app.goal.ForegroundType
import dev.kestrel.app.goal.GoalForeground
import dev.kestrel.app.goal.GoalService

/** What a request for the audio focus gave: [granted] or not, whether it ran [speaking], and the service's types then. */
internal data class FocusResult(val granted: Boolean, val speaking: Boolean, val types: Set<ForegroundType>?)

/**
 * Spike S9's check of audio in the background (main spec B5; design 10.6 of sub-project 7; plan 7E): asks Android for the
 * audio focus as Kestrel's voice does before it speaks (usage assistant, speech), either inside
 * `GoalForeground.whileSpeaking` (speaking) or alone, the control. Android 17 refuses the focus to an app that has neither
 * a visible activity nor a foreground service with while-in-use, so the two requests and a request after the goal's end
 * tell which of those Android asks of Kestrel. It gives the focus back at once and keeps the newest result for
 * goal-service-state.
 */
internal object FocusProbe {
    /** How long after the hook's broadcast the request waits, so that the broadcast's own standing has ended. */
    const val DELAY_MILLIS = 1_000L

    @Volatile
    var last: FocusResult? = null

    private val SPEECH: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    suspend fun request(context: Context, foreground: GoalForeground, speaking: Boolean): FocusResult {
        val audio = context.getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(SPEECH).build()
        val ask = {
            val granted = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            audio.abandonAudioFocusRequest(focus)
            FocusResult(granted, speaking, GoalService.runningTypes())
        }
        return if (speaking) foreground.whileSpeaking { ask() } else ask()
    }
}
```

Create `app/src/debug/kotlin/dev/kestrel/app/debug/GoalServiceAnswers.kt`:

```kotlin
package dev.kestrel.app.debug

import dev.kestrel.app.goal.ForegroundType
import dev.kestrel.app.goal.GoalServiceStatus

/**
 * The answer of the hook goal-service-state (plan 7E; design 10.6 of sub-project 7), apart from Android so that a JVM test
 * checks it: the coordinator's view of the service, the notice that the service shows and the held goal's notice that
 * Kestrel posted without it, the buttons and progress of the notice that counts, the types that the service should have
 * and those that it applied, whether Android runs it in the foreground and with which types ([android], null when it does
 * not), the starts and refusals in this process, and the newest audio-focus request ([focus]). Kinds and counts only,
 * never the notification's words.
 */
internal object GoalServiceAnswers {
    fun state(status: GoalServiceStatus, android: Set<ForegroundType>?, focus: FocusResult?): String {
        val counting = status.notice ?: status.posted
        val focusBy = when {
            focus == null -> "none"
            focus.speaking -> "speak"
            else -> "focus"
        }
        return "service=${status.service.name.lowercase()} notice=${status.notice?.kind?.name?.lowercase() ?: "none"} " +
            "posted=${status.posted?.kind?.name?.lowercase() ?: "none"} " +
            "actions=${counting?.actions?.takeIf { it.isNotEmpty() }?.joinToString(",") { it.name.lowercase() } ?: "none"} " +
            "progress=${counting?.progress?.let { "${it.done}/${it.total}" } ?: "none"} " +
            "types=${types(status.types)} applied=${types(status.applied)} android=${if (android == null) "none" else "running"} " +
            "androidTypes=${types(android)} starts=${status.starts} refusals=${status.refusals} " +
            "focus=${focus?.let { if (it.granted) "granted" else "failed" } ?: "none"} focusBy=$focusBy focusTypes=${types(focus?.types)}"
    }

    // "special-use+media-playback"; none without a type, as on Android 13, and without a service.
    private fun types(types: Set<ForegroundType>?): String =
        types?.takeIf { it.isNotEmpty() }?.sortedBy { it.ordinal }?.joinToString("+") { it.wire } ?: "none"
}
```

Create `app/src/debug/kotlin/dev/kestrel/app/debug/NoticeButtons.kt`:

```kotlin
package dev.kestrel.app.debug

import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot

/**
 * A button of Kestrel's own goal notification in SystemUI's shade (plan 7E), for the hook goal-control. The notification's
 * row is the SystemUI element above the notification's title that offers expand or collapse, as `ScreenActions` tells a
 * row; the button is a clickable SystemUI element in that row with the button's label, so that another app's button with
 * the same label never counts. Labels are compared without letter case, because some phones write a notification's
 * buttons in capitals.
 */
internal object NoticeButtons {
    /** The key of the button [label] in the row whose title is [title]; null when the shade does not show it. */
    fun button(snapshot: ScreenSnapshot, title: String, label: String): String? {
        val row = row(snapshot, title) ?: return null
        return snapshot.elements.indices.firstOrNull { index ->
            isButton(snapshot.elements[index], label) && row in ancestors(snapshot, index)
        }?.let { snapshot.elements[it].key }
    }

    /** Whether [element] is SystemUI's clickable element with the label [label]: the check that a press repeats. */
    fun isButton(element: ScreenElement, label: String): Boolean =
        element.packageName == SYSTEM_UI && element.clickable && named(element, label)

    /** The key of the row whose title is [title] while it is collapsed, so that it can be expanded to show its buttons. */
    fun collapsedRow(snapshot: ScreenSnapshot, title: String): String? =
        row(snapshot, title)?.let { snapshot.elements[it] }?.takeIf { ElementAction.EXPAND in it.actions }?.key

    private fun row(snapshot: ScreenSnapshot, title: String): Int? {
        val titled = snapshot.elements.indices.firstOrNull { snapshot.elements[it].packageName == SYSTEM_UI && named(snapshot.elements[it], title) }
            ?: return null
        return ancestors(snapshot, titled).firstOrNull { index ->
            val actions = snapshot.elements[index].actions
            ElementAction.EXPAND in actions || ElementAction.COLLAPSE in actions
        }
    }

    private fun ancestors(snapshot: ScreenSnapshot, index: Int): List<Int> =
        generateSequence(snapshot.elements[index].parent) { snapshot.elements[it].parent }.toList()

    private fun named(element: ScreenElement, words: String): Boolean =
        element.text.equals(words, ignoreCase = true) || element.contentDescription.equals(words, ignoreCase = true)

    private const val SYSTEM_UI = "com.android.systemui"
}
```

Create `app/src/debug/kotlin/dev/kestrel/app/debug/NoticeIntents.kt`:

```kotlin
package dev.kestrel.app.debug

import dev.kestrel.app.goal.GoalAction
import dev.kestrel.app.goal.GoalNotification
import dev.kestrel.app.goal.GoalServiceStatus

/**
 * A button's PendingIntent as Android holds it on Kestrel's own posted goal notification (plan 7E, Task 8, Step 8a), for
 * the hook goal-control do=send-later: the very intent that the owner's tap on the button sends, taken from the
 * notification that Android shows, never built again. Generic in the intent, so that the choice is tested without Android.
 */
internal object NoticeIntents {
    /** One of Kestrel's posted notifications: its ID and its buttons, each as its label and its intent. */
    data class Posted<T>(val id: Int, val buttons: List<Pair<String, T>>)

    /**
     * The ID of the notification that shows [action] now: the held goal's notification that Kestrel posted without the
     * service, or else the service's own; null when neither offers [action].
     */
    fun idOf(status: GoalServiceStatus, action: GoalAction): Int? = when {
        status.posted?.actions?.contains(action) == true -> GoalNotification.POSTED_ID
        status.notice?.actions?.contains(action) == true -> GoalNotification.SERVICE_ID
        else -> null
    }

    /** The intent of the button [label] on Kestrel's posted notification [id]; null when Android shows no such button. */
    fun <T> intentOf(posted: List<Posted<T>>, id: Int, label: String): T? =
        posted.firstOrNull { it.id == id }?.buttons?.firstOrNull { it.first == label }?.second
}
```

Create `app/src/debug/kotlin/dev/kestrel/app/debug/GoalServiceHooks.kt`:

```kotlin
package dev.kestrel.app.debug

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import dev.kestrel.app.AppGraph
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.app.goal.GoalAction
import dev.kestrel.app.goal.GoalNotice
import dev.kestrel.app.goal.GoalService
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.GlobalAction
import dev.kestrel.screen.act.PressPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The goal service's hooks (plan 7E; design 10.6 of sub-project 7). goal-service-state reads the service and the newest
 * audio-focus request. goal-control presses a button of the goal notification in SystemUI's shade by its key, so that
 * SystemUI sends the button's broadcast as it does for the owner's finger and Android's rules for a start from a
 * notification apply; gives the conversation a touch, as the touch watcher gives it (D13), where a real touch on the screen
 * is not safe, as on the A71; asks Android for the audio focus with and without the speech's types (spike S9); or has a
 * button's own PendingIntent sent a few seconds later, to a process that has ended meanwhile. The answers never contain
 * words.
 */
internal class GoalServiceHooks(private val graph: AppGraph, private val context: Context) {
    suspend fun run(command: String, intent: Intent): String = when (command) {
        "GOAL_SERVICE_STATE" -> GoalServiceAnswers.state(graph.goalForeground.status(), GoalService.runningTypes(), FocusProbe.last)
        "GOAL_CONTROL" -> control(intent)
        else -> throw HookError("unknown command '$command'")
    }

    private suspend fun control(intent: Intent): String = when (val wanted = intent.getStringExtra(EXTRA_DO)) {
        "tap" -> tap(intent.getStringExtra(EXTRA_ACTION))
        "touch" -> {
            graph.conversations.touched()
            "touch=sent"
        }
        "speak", "focus" -> {
            // After the hook's broadcast has returned, so that its own standing does not count.
            graph.scope.launch {
                delay(FocusProbe.DELAY_MILLIS)
                FocusProbe.last = FocusProbe.request(context, graph.goalForeground, speaking = wanted == "speak")
            }
            "$wanted=sent"
        }
        // The next start of the goal service is refused as Android refuses it, once (plan 7E, Task 8, Step 8a).
        "refuse" -> {
            graph.goalStartRefusals.set(true)
            "refuse=armed"
        }
        "send-later" -> sendLater(intent.getStringExtra(EXTRA_ACTION))
        else -> throw HookError("add --es do with one of tap, touch, speak, focus, refuse or send-later")
    }

    private suspend fun tap(name: String?): String {
        val action = actionOf(name)
        val notice = graph.goalForeground.notice() ?: throw HookError("the goal service shows no notification")
        if (action !in notice.actions) throw HookError("the goal notification has no ${action.name.lowercase()} now")
        val session = graph.screen.value ?: throw HookError("Kestrel's accessibility service is off")
        session.actions.global(GlobalAction.NOTIFICATIONS)
        val key = buttonInShade(session, notice, action)
        if (key == null) {
            session.actions.global(GlobalAction.DISMISS_SHADE)
            throw HookError("the goal notification's ${action.name.lowercase()} is not in the shade")
        }
        // By the button's key, which the press checks again in every snapshot that it acts on: a row that a new notification
        // moved, or another app's button, is never pressed in its place (AGENTS.md's limits on the A71). Once only, so that a
        // Continue is never sent twice.
        val pressed = session.actions.tap(
            key,
            expected = { element -> NoticeButtons.isButton(element, action.label) },
            policy = PressPolicy.ONCE,
            guard = { snapshot -> NoticeButtons.button(snapshot, notice.title, action.label) == key },
        )
        if (pressed is ActionResult.Failed) throw HookError(pressed.reason)
        // A broadcast's button leaves the shade open; Open's activity closes it.
        if (action.intent != null) session.actions.global(GlobalAction.DISMISS_SHADE)
        return "tapped=${action.name.lowercase()}"
    }

    /**
     * Hands the PendingIntent of the goal notification's button, as Android holds it on Kestrel's posted notification, to
     * AlarmManager, which sends it [SEND_LATER_MILLIS] later (Task 8, Step 8a of plan 7E; the second review's R4-M1). The
     * step ends Kestrel's process meanwhile, and the system delivers the button's broadcast to a new process as it
     * delivers the owner's tap. Nothing is tapped on the screen, so a row that moves in the shade can never turn it into a
     * tap on another app's notification. `send=scheduled`, or `send=none` when Kestrel's goal notification shows no such
     * button. USE_EXACT_ALARM in the debug manifest allows the exact alarm without a setting of the phone.
     */
    private fun sendLater(name: String?): String {
        val action = actionOf(name)
        val id = NoticeIntents.idOf(graph.goalForeground.status(), action) ?: return "send=none"
        val posted = context.getSystemService(NotificationManager::class.java).activeNotifications.map { shown ->
            NoticeIntents.Posted(shown.id, (shown.notification.actions ?: emptyArray()).map { it.title.toString() to it.actionIntent })
        }
        val button = NoticeIntents.intentOf(posted, id, action.label) ?: return "send=none"
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (!alarms.canScheduleExactAlarms()) throw HookError("Kestrel may not set an exact alarm")
        alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + SEND_LATER_MILLIS, button)
        return "send=scheduled"
    }

    private fun actionOf(name: String?): GoalAction = GoalAction.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        ?: throw HookError("add --es action with one of stop, open, continue or cancel")

    // The shade opens with an animation, and a collapsed row shows its buttons only once it is expanded: the key of the
    // button in Kestrel's own row, or null, after it asked to expand that row.
    private suspend fun buttonInShade(session: ScreenSession, notice: GoalNotice, action: GoalAction): String? {
        repeat(SHADE_TRIES) {
            delay(SHADE_MILLIS)
            val snapshot = session.snapshots.current().snapshot
            NoticeButtons.button(snapshot, notice.title, action.label)?.let { return it }
            NoticeButtons.collapsedRow(snapshot, notice.title)?.let { session.actions.expand(it) }
        }
        return null
    }

    companion object {
        val COMMANDS = setOf("GOAL_SERVICE_STATE", "GOAL_CONTROL")
        private const val EXTRA_DO = "do"
        private const val EXTRA_ACTION = "action"
        private const val SHADE_TRIES = 5
        private const val SHADE_MILLIS = 600L

        // Android sends no app's alarm sooner than 5 s after it was set (AlarmManager's minimum futurity).
        private const val SEND_LATER_MILLIS = 5_000L
    }
}
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt`, replace:

```kotlin
        if (command in AgentHooks.COMMANDS) return AgentHooks(graph, context).run(command, intent)
```

with:

```kotlin
        if (command in AgentHooks.COMMANDS) return AgentHooks(graph, context).run(command, intent)
        if (command in GoalServiceHooks.COMMANDS) return GoalServiceHooks(graph, context).run(command, intent)
```

In `app/src/debug/AndroidManifest.xml`, replace:

```xml
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

with:

```xml
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

    <!-- The hook goal-control do=send-later has a goal notification's button sent a few seconds later by an exact alarm
         (plan 7E, Task 8, Step 8a). Android grants USE_EXACT_ALARM at the install (API 33 and later, Kestrel's minimum),
         so the hook changes no setting of the phone; release builds do not have it. -->
    <uses-permission android:name="android.permission.USE_EXACT_ALARM" />
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.debug.GoalServiceAnswersTest --tests=dev.kestrel.app.debug.NoticeButtonsTest --tests=dev.kestrel.app.debug.NoticeIntentsTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Run `:app`'s JVM tests and lint, compile the device tests, count them and stop Gradle**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug`, then `$n = 0; Get-ChildItem app\build\test-results\testDebugUnitTest -Filter *.xml | ForEach-Object { $n += [int]([xml](Get-Content $_.FullName -Raw)).testsuite.tests }; "app $n"`, then `"device $(@(Get-ChildItem app\src\androidTest -Recurse -Filter *.kt | Select-String -Pattern '@Test\b').Count)"`, then `scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL; `app` A₀ + 27; `device` AD₀ + 11. Task 8 runs the device tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/debug app/src/testDebug app/src/androidTest/kotlin/dev/kestrel/app/debug/GoalServiceHooksTest.kt
git commit -m "feat(app): the hooks goal-service-state and goal-control, which read the goal service, press its notification's buttons by key and ask for the audio focus with and without the speech's types (plan 7E)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 7: The whole first search's speed, a close and a search right after a start

Design 4.3 of sub-project 7 asks that the first search over 30 days of conversations takes at most 1 s on the A71, and that the archive's share of it takes at most 500 ms; design R3 estimates it for 30 days of heavy use (300 conversations, 600 commands of about 300 different words each, about 25,000 different words in all), with an index of about 450 KB in two shards. Plan 7C measures the archive's share; this plan measures the whole search, a close and a search right after a start, with R3's fixture. `ConversationSearchSpeedTest` runs three times, each time in a new process, as `scripts\connected-test.ps1` starts the instrumentation anew:
- With `kestrelSearchSpeed=write` it saves the 300 conversations through plan 7B's store into plan 7C's archive, in a test database with a test key, oldest first, as closes add them: each close encodes and encrypts one shard of the index, the newest, again (design 9.2), so the run takes about a minute and a half on the A71. It times the last close, of a conversation that was open, with the index at its full size, and leaves an open conversation of today.
- With `kestrelSearchSpeed=search` a new store reads the index in the background (its shards and the table's stamp, decoded), and `list()` waits for it; then the first search is timed: the ranking over the shards and the read of the best matches' bodies in one call (design 9.4).
- With `kestrelSearchSpeed=start` a new store and a new `HistorySearch` time a search right after a start, R3's worst case: the restore reads only the open conversation, and the search waits for the index, which the store reads in the background meanwhile. Then the run deletes the database and erases the key.

In each run the JSON, Deflate and AES code, Room and the key's handle are loaded for the first time. It is a measurement: without its argument it skips itself, and Tasks 8 and 9 run it.

**Files:**
- Test: `app/src/androidTest/kotlin/dev/kestrel/app/conversation/ConversationSearchSpeedTest.kt` (device)

**Interfaces:**
- Consumes: plan 7B's `ArchivedConversationStore(archive, scope, noted)`, `ConversationStore.save`, `open()` and `list()`, `HistorySearch(store, retentionDays, clock)`, `HistorySearch.search(query, excluding)` with `HistoryResult.unavailable`, `ConversationSnapshot`, `CommandRecord`, `RecordLine`, `LineKind`, `DataNote(tool, step, text, items)`, `AppUse`, `ConversationId.random()`; plan 7A's `HistoryQuery` and `HistoryPort.MAX_MATCHES`; plan 7C's `RoomRecordArchive(table, deleteHistory)` (plan 7D's Task 7 added `deleteHistory`), `ConversationArchiveStore(dao, key)` with `deleteAll()`, `KeystoreErasableKey.of(alias)` with `erase()`, `KestrelDatabase.conversationDao()`, `AndroidConversationClock`.
- Produces: the instrumentation argument `kestrelSearchSpeed=write|search|start`, the test database `test-search-speed.db` and the test key `kestrel.test.search-speed`, which the `write` run leaves for the two others and the `start` run deletes (and `clean`, which deletes them; a run that fails deletes them itself, Task 7's review fix 6cf11754), the log tag `KestrelSearch` and its lines `wrote 300 conversations in <s> s`, `closed a conversation in <ms> ms`, `read the index in <ms> ms`, `searched 300 conversations in <ms> ms`, `restored the open conversation in <ms> ms` and `searched right after a start in <ms> ms`, which Tasks 8 and 9 read.

Counts: `:app` JVM A₀ + 27, unchanged. Device tests: AD₀ + 11 + 1 = AD₀ + 12 (`ConversationSearchSpeedTest` 1, skipped without its argument).

- [ ] **Step 1: Write the measurement**

Create `app/src/androidTest/kotlin/dev/kestrel/app/conversation/ConversationSearchSpeedTest.kt`:

```kotlin
package dev.kestrel.app.conversation

import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.goal.HistoryPort
import dev.kestrel.agent.goal.HistoryQuery
import dev.kestrel.agent.goal.HistoryResult
import dev.kestrel.conversation.model.AppUse
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSnapshot
import dev.kestrel.conversation.model.DataNote
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.LineKind
import dev.kestrel.conversation.model.RecordLine
import dev.kestrel.conversation.store.ArchivedConversationStore
import dev.kestrel.conversation.store.HistorySearch
import dev.kestrel.privacy.conversations.ConversationArchiveStore
import dev.kestrel.privacy.crypto.KeystoreErasableKey
import dev.kestrel.privacy.db.KestrelDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * How long the first search over 30 days of past conversations takes, a close, and a search right after a start (design
 * 4.3 and R3 of sub-project 7; plan 7E), in three instrumentation runs, each a new process, with design R3's fixture:
 * 300 closed conversations of two commands, each command with about 300 words drawn evenly from 25,000 made-up words with
 * a fixed seed, so that the index of past conversations has the size of 30 heavy days (about 450 KB in two shards).
 * - kestrelSearchSpeed=write saves them through plan 7B's store into plan 7C's archive in a test database with a test key,
 *   oldest first, so that each close encodes and encrypts the index's newest shard again, times the last close, and leaves
 *   an open conversation of today.
 * - kestrelSearchSpeed=search lets a new store read the index in the background and times the first search after it.
 * - kestrelSearchSpeed=start times a search right after a new store's start, which waits for the index while the store
 *   reads it; the restore reads only the open conversation. Afterwards the run deletes the database and erases the key.
 * It is a measurement: it fails only when the store could not use the index or the search does not find the
 * conversations that hold the words, and it logs under the tag KestrelSearch. It never uses Kestrel's own database or key.
 */
@RunWith(AndroidJUnit4::class)
class ConversationSearchSpeedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val random = Random(SEED)
    private val vocabulary: List<String> by lazy { generateSequence { word() }.distinct().take(VOCABULARY).toList() }

    @Test
    fun measuresTheFirstSearchOver30DaysOfConversations() = runBlocking<Unit> {
        val mode = InstrumentationRegistry.getArguments().getString("kestrelSearchSpeed")
        assumeTrue("Runs with -Pandroid.testInstrumentationRunnerArguments.kestrelSearchSpeed=write, then =search, then =start", mode != null && mode in MODES)
        when (mode) {
            "write" -> write()
            "search" -> search()
            else -> start()
        }
    }

    private suspend fun CoroutineScope.write() {
        context.deleteDatabase(FILE)
        KeystoreErasableKey.of(ALIAS).erase()
        val now = System.currentTimeMillis()
        val written = database()
        try {
            val store = store(written, this)
            // The store has read the empty archive, so that each close writes the index's newest shard at once.
            assertTrue(store.list().isEmpty())
            val started = SystemClock.elapsedRealtime()
            for (n in CONVERSATIONS - 1 downTo 1) store.save(conversation(n, now - DAYS * DAY_MILLIS * n / CONVERSATIONS))
            val newest = conversation(0, now - 60_000L)
            store.save(newest.copy(open = true))
            val closing = SystemClock.elapsedRealtime()
            store.save(newest)
            val closed = SystemClock.elapsedRealtime()
            val today = CommandRecord(1, now - 30_000L, InputChannel.VOICE, "Turn on dark mode", answer = "Dark mode is on.", end = EndReason.FINISHED)
            store.save(ConversationSnapshot(ConversationId.random(), now - 30_000L, now, open = true, testMode = true, records = listOf(today)))
            assertEquals(CONVERSATIONS, store.list().size)

            Log.i(TAG, "wrote $CONVERSATIONS conversations in ${(closed - started) / 1_000} s")
            Log.i(TAG, "closed a conversation in ${closed - closing} ms")
        } finally {
            written.close()
        }
    }

    private suspend fun CoroutineScope.search() {
        val read = database()
        try {
            val opened = SystemClock.elapsedRealtime()
            val store = store(read, this)
            // list() waits while the store reads the index in the background.
            assertEquals(CONVERSATIONS, store.list().size)
            val loaded = SystemClock.elapsedRealtime()
            val found = HistorySearch(store, { DAYS.toInt() }, AndroidConversationClock).search(HistoryQuery(QUERY))
            val searched = SystemClock.elapsedRealtime()

            Log.i(TAG, "read the index in ${loaded - opened} ms")
            Log.i(TAG, "searched $CONVERSATIONS conversations in ${searched - loaded} ms")
            assertFound(found)
        } finally {
            read.close()
        }
    }

    private suspend fun CoroutineScope.start() {
        val read = database()
        try {
            val started = SystemClock.elapsedRealtime()
            val store = store(read, this)
            val open = store.open()
            val restored = SystemClock.elapsedRealtime()
            // The search waits while the store reads the index in the background.
            val found = HistorySearch(store, { DAYS.toInt() }, AndroidConversationClock).search(HistoryQuery(QUERY))
            val searched = SystemClock.elapsedRealtime()

            Log.i(TAG, "restored the open conversation in ${restored - started} ms")
            Log.i(TAG, "searched right after a start in ${searched - started} ms")
            assertEquals("Turn on dark mode", open?.records?.single()?.request)
            assertFound(found)
        } finally {
            read.close()
            context.deleteDatabase(FILE)
            KeystoreErasableKey.of(ALIAS).erase()
        }
    }

    private fun assertFound(found: HistoryResult) {
        assertNull("The store could not use the index, so the search did not run", found.unavailable)
        assertEquals(HistoryPort.MAX_MATCHES, found.matches.size)
        assertTrue(found.more)
    }

    private fun database() = Room.databaseBuilder(context, KestrelDatabase::class.java, FILE).build()

    // The store's scope is the test's own, so that its reading of the index in the background ends before the run does. The
    // measurement deletes no history; the archive's "delete history" is the test table's own, as in RoomRecordArchiveTest.
    private fun store(database: KestrelDatabase, scope: CoroutineScope): ArchivedConversationStore {
        val table = ConversationArchiveStore(database.conversationDao(), KeystoreErasableKey.of(ALIAS))
        return ArchivedConversationStore(RoomRecordArchive(table) { table.deleteAll() }, scope) {}
    }

    // Two commands, each with about 300 words in its request, its lines, its answer and its data notes. The first command
    // of every tenth conversation names the restaurant that the search looks for.
    private fun conversation(n: Int, last: Long): ConversationSnapshot {
        val records = List(COMMANDS) { index ->
            val request = if (n % 10 == 0 && index == 0) "Find the restaurant Mila sent me last week" else "Check ${words(6)}"
            CommandRecord(
                number = index + 1,
                atWall = last - (COMMANDS - index) * 60_000L,
                channel = InputChannel.VOICE,
                request = request,
                lines = List(2) { RecordLine(LineKind.KESTREL_SAID, words(20)) },
                answer = words(30),
                end = EndReason.FINISHED,
                data = List(2) { DataNote("list_sms", it + 1, words(120), items = 10) },
                apps = listOf(AppUse("Viber", "com.viber.voip")),
                ledger = listOf("opened Viber"),
            )
        }
        return ConversationSnapshot(ConversationId.random(), last - 300_000L, last, open = false, testMode = true, records = records)
    }

    private fun words(count: Int): String = List(count) { vocabulary[random.nextInt(VOCABULARY)] }.joinToString(" ")

    // A made-up word of 4 to 10 letters that starts as words often do and then alternates vowels and consonants.
    private fun word(): String = buildString {
        append(FIRST[random.nextInt(FIRST.length)])
        repeat(3 + random.nextInt(7)) { append(if (it % 2 == 0) VOWELS[random.nextInt(VOWELS.length)] else CONSONANTS[random.nextInt(CONSONANTS.length)]) }
    }

    private companion object {
        const val TAG = "KestrelSearch"
        const val FILE = "test-search-speed.db"
        const val ALIAS = "kestrel.test.search-speed"
        const val QUERY = "restaurant Mila"
        const val CONVERSATIONS = 300
        const val COMMANDS = 2
        const val VOCABULARY = 25_000
        const val SEED = 25_112_026L
        const val DAYS = 30L
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
        const val FIRST = "ssscccppbbtttmmaddrrffhhgglwweiouvnkjy"
        const val VOWELS = "aeiouaeioy"
        const val CONSONANTS = "rstlnmdkpbgvzcfhj"
        val MODES = setOf("write", "search", "start")
    }
}
```

- [ ] **Step 2: Compile the device tests, count them and stop Gradle**

Run: `scripts\gradle.ps1 :app:compileDebugAndroidTestKotlin`, then `"device $(@(Get-ChildItem app\src\androidTest -Recurse -Filter *.kt | Select-String -Pattern '@Test\b').Count)"`, then `scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL; `device` AD₀ + 12. Tasks 8 and 9 run it.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/kotlin/dev/kestrel/app/conversation/ConversationSearchSpeedTest.kt
git commit -m "test(app): the first search over 30 heavy days of past conversations, a close and a search right after a start, each timed in a new process (plan 7E)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 8: The checks on kestrel-api37 (the controller's steps, run by a delegated agent)

Design 15.2 of sub-project 7 on kestrel-api37: the device tests of this plan and the regression of the agent's, the hooks' and the voice's device tests; the call pause with listening off, which needs Kestrel's phone-state permission for one run; the whole first search's speed, a close and a search right after a start; and, outside the instrumentation, Android's own rules (main spec S9 and B5): the spoken start while `ListeningService` runs, the audio focus with listening off with and without the speech's types and after the goal's end, the held goal's Continue pressed in SystemUI's shade, the start after a restart with "Continue the goal?" and its Continue, a tap on the posted notification that starts Kestrel's process, and the owner's stop in "Active apps". For every start, the step keeps the lines of `dumpsys activity services` that say which exemption Android used. The typed start from `ConversationActivity` is plan 7F's (see "Decisions for the controller", item 2). This task changes no file in git.

**Files:**
- None in git; Step 11 writes `.superpowers\sdd\progress.md` (a delegated agent writes its lines into its report instead).

**Interfaces:**
- Consumes: every earlier task; plan 7D's hooks `conversation-state` and `conversation-control` and its stand-in round (`voice-task`); the hook `crash` (spec 16.1); `adb shell cmd activity stop-app`, which Android's documentation names to test the Task Manager's Stop; `scripts\install.ps1`, `scripts\connected-test.ps1`, `scripts\voice-test.ps1`, `scripts\hook.ps1`; `scripts\lib\Kestrel.ps1`'s `Connect-AccessibilityService` and `Disconnect-AccessibilityService` (Task 8's fourth run); the hook `NOTICES` of the A71 sessions, which counts Kestrel's problem notices by kind.
- Produces: the results in `.superpowers\sdd\progress.md`, under "## Plan 7E", with spike S9's findings on kestrel-api37.

Counts: none change. The device tests that run are AD₀ + 14 in all, across Steps 3 to 6 (this plan's 12, the test of the goal's buttons in the shade that the device-found fixes add, and the test of the held notice while the accessibility service goes off that Task 8's third run adds; see the amendments of 2026-11-26).

**The build and the emulator (amendment of 2026-11-26).** Task 8 installs `sp3` at `ecf2a999` or a later commit: the device-found fixes from `dab1e6c2` to `ecf2a999` (the goal notification's groups and colour, the hook's shade rules, `ScreenServiceRule`'s wait, kestrel-api37 without a window, and the microphone's stop) are what Steps 3 to 9 expect, and an older build fails Step 3. Since decision 16 was settled, Task 8 installs `d3f679e6` or a later commit, whose `goal-control do=tap` says which collapsed rows it had to expand before its press (`expanded=`), and Steps 7 and 8 expect it. Since Task 8's third run, Task 8 installs `7aa15be0` or a later commit: `96861f22` makes the hook's press report its outcome (Task 8's second run), and `415ed6bd` with `7aa15be0` gives every kind of Kestrel's notifications its own ID (`KestrelNotification`; the goal's are 7106 and 7107), without which Step 8a's accessibility notice replaces the held goal's notification and its Cancel is lost. Since Task 8's fourth run, Task 8 installs and runs `sp3` at `55de7dfd` or a later commit (amendment of 2026-11-26, Task 8's fourth run): Steps 8a, 9 and 10 call the accessibility helpers of `scripts\lib\Kestrel.ps1` (`Connect-AccessibilityService` and `Disconnect-AccessibilityService`, from `b907e8ed`, `a941c67d` and `55de7dfd`), and Step 9 expects the notice that Kestrel posts when the owner's stop left its accessibility service unbound (`4cdc50ca`, `7a491675` and `66253fc3`); Step 1 checks that HEAD contains `55de7dfd` and that the helper exists. kestrel-api37 runs without a window: `scripts\start-emulator.ps1` starts it with `-no-window`, because the window links Android's clipboard to Windows' clipboard, which makes `ClipboardHookTest` fail now and then (README). No step of Task 8 needs the window, so the task never starts it with `-Window`; Step 1 reads the window type from the emulator's console (`Get-EmulatorWindowType`) and stops unless it says `headless`. Every check of the emulator's name requires its exact name (`Test-KestrelAvdAnswer`, as the scripts do), so an AVD such as `kestrel-api37-old` never passes it.

Each device command below starts with the emulator's check, and the steps that send this plan's hooks, or plan 7D's `conversation-state` and `conversation-control`, define `Hook`, which sends one to the receiver with `am broadcast` (`scripts\hook.ps1` learns them in plan 7G) and prints only the answer's `data=` line, which holds no words.

**How the task runs.** kestrel-api37 holds the owner's own Google account and Kestrel's Copilot profile, which cannot be made again without the owner; losing them is the one failure that matters more than every result of this task. The controller gives the task to an agent with a strict brief, as for plan 7D's gate, and the agent follows these rules and the steps exactly:
- Only kestrel-api37: every command names `-s emulator-5554` (or the serial that `scripts\start-emulator.ps1` printed, when the controller's brief gives another) and starts with the emulator's check. No command goes to the A71, the S23+, the AVD `zoran` or any other device, and no adb command runs without `-s`.
- Only the commands of the steps: device work goes only through `scripts\install.ps1`, `scripts\connected-test.ps1`, `scripts\voice-test.ps1`, `scripts\hook.ps1`, the plain `adb -s emulator-5554` commands that the steps contain, and `scripts\lib\Kestrel.ps1`'s `Connect-AccessibilityService` and `Disconnect-AccessibilityService` in Steps 8a, 9 and 10, which change only the list of enabled accessibility services and do so only on the device that `Assert-TestDevice` accepted, with the reads `Get-AccessibilityServiceState`, `Format-ServiceState` and `Test-ServiceListed`, which change nothing. Never `adb install`, `adb uninstall`, `pm clear`, `pm uninstall`, a Gradle task that installs or runs device tests directly, `scripts\hook.ps1 provider-delete`, `--live`, or anything that signs an account in or out or changes a profile.
- **The data check**, before Step 2, after each of Steps 2 to 10 (8a too), and at the end: `. scripts\lib\Kestrel.ps1; . scripts\lib\Session.ps1; . scripts\lib\DeviceGuard.ps1; Use-KestrelToolchain; Assert-TestDevice -Serial emulator-5554; $adb = Get-Adb; if (-not (Test-KestrelAvdAnswer @(& $adb -s emulator-5554 emu avd name))) { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; $before = Import-Clixml 'H:\Code\kestrel\.superpowers\sdd\plan-7e-owner-data.xml'; $changes = @(Compare-OwnerData -Serial emulator-5554 -Before $before -During 'plan 7E, Task 8'); if ($changes.Count) { $changes; Write-Error "The owner's data changed or could not be read: stop and report" -ErrorAction Stop }; "owner data: as before ($(Format-OwnerDataState $before 'emulator-5554'))"`. Expected: one line that starts with `owner data: as before`. It compares the state that Step 1 recorded with the state now, by machine, through `scripts\lib\DeviceGuard.ps1`'s `Get-OwnerDataState` and `Compare-OwnerData`, as `scripts\connected-test.ps1` does: the number of Google accounts (`Get-GoogleAccountCount`, which counts `dumpsys account`'s account lines; a raw `Select-String` on `type=com.google` counts one account more than once), and Kestrel's default profile, its kind, the number of Copilot profiles, its `id=` and whether its credential is stored. It unstops Kestrel's package first (`pm unstop`, which starts nothing), so it reads Kestrel also after Step 9's `stop-app`. Any other answer, or an error: stop at once, run no further command on the device, and report.
- Stop at the first failure: a failing test, an exit code other than 0, an answer that differs from a step's Expected, a crash that the step does not expect, a frozen emulator, or anything that the steps do not describe. Then run the data check and report. Do not run anything again, cold-boot, retry or fix, except where a step says so (Step 3's known flaky class); the step helpers' `Listen` toggles a second time by itself when the first toggle left Kestrel off, and stops otherwise.
- Every command that runs Gradle (Steps 2 to 6) takes `H:\Code\kestrel\.superpowers\sdd\gradle.lock` as the controller's brief says, and runs `scripts\gradle.ps1 --stop` before it lets the lock go; the agent also holds the brief's emulator lock for the whole task.
- No goal's round may reach a provider: kestrel-api37's default profile is the owner's Copilot profile, so every step sets plan 7D's stand-in round (`voice-task`) and checks by its answer that it is set, before it turns listening on (right after Kestrel comes to the front), and again before each Continue that resumes a goal (`StandIn`, one of the step helpers); a stand-in that is not set stops the command. So no moment exists in which Kestrel listens without the stand-in.
- The report holds no words that Kestrel heard or said, no screen text, contact, number or e-mail address: only test names, counts, exit codes and the hooks' answers of kinds and counts. The agent writes only its report and the data check's state file `H:\Code\kestrel\.superpowers\sdd\plan-7e-owner-data.xml` (git-ignored there), which Step 1 writes and Step 10 deletes.

**The step helpers.** Steps 7 to 10 (8a too) each run one command that starts with these helpers, pasted exactly as they are, and then the step's own text: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; $s = 'emulator-5554'; if (-not (Test-KestrelAvdAnswer @(& $adb -s $s emu avd name))) { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; function Hook([string]$command) { "$(& $adb -s $s shell "am broadcast -n dev.kestrel/dev.kestrel.app.debug.TestHookReceiver -a dev.kestrel.hook.$command" | Select-String 'data=')" }; function Voice([string]$send) { pwsh -NoProfile -File scripts\hook.ps1 -Serial $s -Command voice -Send $send }; function Heard([string]$text) { pwsh -NoProfile -File scripts\hook.ps1 -Serial $s -Command voice -Send heard -Text $text }; function Mode { [regex]::Match("$(pwsh -NoProfile -File scripts\hook.ps1 -Serial $s -Command voice-state)", 'mode=(\w+)').Groups[1].Value }; function Listen { foreach ($try in 1, 2) { Voice 'toggle' | Out-Null; Start-Sleep -Seconds 10; $m = Mode; if ($m -eq 'listening') { 'mode: listening'; return }; if ($m -ne 'off') { break } }; Write-Error "Listening did not start (mode=$m): stop and report" -ErrorAction Stop }; function Quiet { Voice 'off' | Out-Null; Start-Sleep -Seconds 3; $m = Mode; if ($m -ne 'off') { Write-Error "Listening did not turn off (mode=$m): stop and report" -ErrorAction Stop }; 'mode: off' }; function StandIn([int]$seconds) { $answer = "$(pwsh -NoProfile -File scripts\hook.ps1 -Serial $s -Command voice-task -Seconds $seconds -Reply 'stand-in')"; if ($answer -notmatch "each goal's round now takes $seconds s") { Write-Error 'The stand-in round is not set: stop and report' -ErrorAction Stop }; "stand-in: $seconds s" }; function Allowed { & $adb -s $s shell dumpsys activity services dev.kestrel/.app.goal.GoalService | Select-String 'allowStartForeground|allowWhileInUse|foregroundServiceType|isForeground' | ForEach-Object { $_.Line.Trim() } }; function Restarted([string]$old) { $deadline = (Get-Date).AddSeconds(90); do { Start-Sleep -Seconds 2; $new = "$(& $adb -s $s shell pidof dev.kestrel)".Trim(); $bound = "$(& $adb -s $s shell dumpsys activity services dev.kestrel/.app.KestrelAccessibilityService)" -match 'app=ProcessRecord' } until (($new -and $new -ne $old -and $bound) -or (Get-Date) -gt $deadline); if (-not ($new -and $new -ne $old -and $bound)) { Write-Error 'No new process of Kestrel with its accessibility service bound within 90 s: stop and report' -ErrorAction Stop }; 'process: new bound: True' }; function Crashes { @(& $adb -s $s logcat -d | Select-String 'FATAL EXCEPTION' -Context 0,1 | Where-Object { "$($_.Context.PostContext)" -match 'Process: dev\.kestrel,' }).Count }; function Focus([string]$kind) { $n = 1 + [int][regex]::Match("$(Hook 'GOAL_SERVICE_STATE')", ' focusRequests=(\d+)').Groups[1].Value; Hook "GOAL_CONTROL --es do $kind"; $deadline = (Get-Date).AddSeconds(15); do { Start-Sleep -Seconds 1; $state = Hook 'GOAL_SERVICE_STATE' } until (($state -match " focusRequests=$n focusAnswered=$n\b") -or (Get-Date) -gt $deadline); if ($state -notmatch " focusRequests=$n focusAnswered=$n\b") { Write-Error "The $kind request did not answer within 15 s: stop and report" -ErrorAction Stop }; $state }`. `Hook` sends one of this plan's hooks, or plan 7D's `conversation-state` and `conversation-control`, to the receiver and prints only the answer's `data=` line; `Voice`, `Heard` and `Mode` use `scripts\hook.ps1`; `Listen` toggles listening on, reads the mode, toggles once more only when it is still off, and stops unless it says `listening`; `Quiet` turns listening off and stops unless it says `off`; `StandIn` sets plan 7D's stand-in round and stops unless its answer says so; `Restarted` waits up to 90 s for a new process of Kestrel with its accessibility service bound, and stops otherwise; `Crashes` counts Kestrel's fatal exceptions in the log; `Focus` sends `goal-control do=speak` or `do=focus`, waits up to 15 s until `goal-service-state` counts that request as sent and answered (`focusRequests=<n> focusAnswered=<n>`, one more than before it), prints its `send` answer and that state line, and stops otherwise, so that an older request's result is never read as this one's. Every `Write-Error` in them ends the whole command.

- [ ] **Step 1: The base**

Run: `. scripts\lib\Kestrel.ps1; . scripts\lib\Session.ps1; . scripts\lib\DeviceGuard.ps1; Use-KestrelToolchain; Assert-TestDevice -Serial emulator-5554; $adb = Get-Adb; if (-not (Test-KestrelAvdAnswer @(& $adb -s emulator-5554 emu avd name))) { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; $window = Get-EmulatorWindowType emulator-5554; "window: $window"; if ($window -ne 'headless') { Write-Error 'kestrel-api37 runs with its window: stop and report' -ErrorAction Stop }; $state = Get-OwnerDataState -Serial emulator-5554; Format-OwnerDataState $state 'emulator-5554'; if (-not $state.Profiles -or $state.Profiles.Profile -ne 'copilot' -or $state.Profiles.Credential -ne 'present') { Write-Error "kestrel-api37 does not show the owner's Copilot profile with its credential: stop and report" -ErrorAction Stop }; $state | Export-Clixml 'H:\Code\kestrel\.superpowers\sdd\plan-7e-owner-data.xml'; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 -Command voice-state; "accessibility: $(& $adb -s emulator-5554 shell settings get secure enabled_accessibility_services)"; git status --short; git rev-parse HEAD; git merge-base --is-ancestor 55de7dfd HEAD; "contains 55de7dfd: $($LASTEXITCODE -eq 0)"; "accessibility helper: $([bool](Get-Command Connect-AccessibilityService -ErrorAction SilentlyContinue))"`
Expected: `window: headless` (the command stops otherwise: the controller closes kestrel-api37 with `adb -s emulator-5554 emu kill` and starts it with `scripts\start-emulator.ps1`, without `-Window`, checks its serial, the accounts and the profile again, and gives the task again); one line of `Format-OwnerDataState` with the number of Google accounts (1 at plan 7D's gate), the default profile `copilot` with its credential `present`, and the number of Copilot profiles; the `voice-state` line's `mode=` and the accessibility setting, which is not `null` (note both: Steps 9 and 10 use them); no changed file in `app/` or `core/`; HEAD at `55de7dfd` or a later commit of `sp3` (Task 7's commit, the device-found fixes, the hook's `expanded=` of decision 16, the press's outcome, the notification IDs of Task 8's third run, and the accessibility helpers and the unbound-service notice of its fourth run), and `contains 55de7dfd: True` (`False`: stop and report); `accessibility helper: True` (`False`: stop and report). The state is now in `H:\Code\kestrel\.superpowers\sdd\plan-7e-owner-data.xml`, which every data check compares with.

- [ ] **Step 2: Install this build on kestrel-api37**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; if (-not (Test-KestrelAvdAnswer @(& $adb -s emulator-5554 emu avd name))) { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; & $adb -s emulator-5554 logcat -c; scripts\install.ps1 -Serial emulator-5554; scripts\gradle.ps1 --stop; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 -Command state; Start-Sleep -Seconds 5; $crashes = @(& $adb -s emulator-5554 logcat -d | Select-String 'FATAL EXCEPTION' -Context 0,1 | Where-Object { "$($_.Context.PostContext)" -match 'Process: dev\.kestrel,' }); "kestrel crashes: $($crashes.Count)"; $crashes | ForEach-Object { $_.Line; $_.Context.PostContext }`
Expected: a `Success` line of `adb install`, `commit=<the HEAD of Step 1>` and `dirty=0` in the state, and `kestrel crashes: 0`; then the data check (an update keeps Kestrel's data). If the emulator froze at its first voice toggle after the install, as on 2026-11-23 and 2026-11-24, stop and report: the controller cold-boots it with `scripts\start-emulator.ps1`, checks its serial, the accounts and the profile again, and gives the rest of the task.

- [ ] **Step 3: Every device test of `:app` but the voice's**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if (-not (Test-KestrelAvdAnswer @(& (Get-Adb) -s emulator-5554 emu avd name))) { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.notPackage=dev.kestrel.app.voice'; "exit: $LASTEXITCODE"; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL and `exit: 0`; no test fails, among them `GoalServiceTest`'s 10 that need no permission (`anOlderGoalsContinueNeverResumesTheNextGoal`, the device check of each goal's own PendingIntents, `withListeningOnTheShadeShowsTheHeldGoalsCancelAndContinueWithoutExpandingAnything`, the check of design 10.6 that the shade shows the held goal's Cancel and Continue without any expansion, and `aRefusedStartHoldsTheGoalAndAndroidKeepsShowingItsNoticeWithCancelAndContinue`, the check that the held notice stays with Cancel and Continue while Kestrel's accessibility service goes off, among them) (the pull of the shade among them), `GoalServiceHooksTest`'s 2 (its press of Continue expects `tapped=continue expanded=none shade=closed`), `ClipboardHookTest` (reliable only while kestrel-api37 runs without a window, Step 1), every test of `dev.kestrel.app.agent` (plan 7D's `GoalPauseTest` too), `ConversationHooksTest` and `AgentHooksTest`. Skipped: `GoalServiceTest.aCallPausesTheGoalWithListeningOffUntilTheNotificationsContinue` while Kestrel lacks the phone-state permission (Step 5 runs it), `ConversationSearchSpeedTest` (Step 6 runs it), the tests of `dev.kestrel.app.commands`, which skip themselves without `scripts\command-test.ps1`'s argument, and the tests that skipped before this plan for a precondition that their assumption names (at plan 7D's gate: `GpsLocationTest`, `PermissionFlowTest`, `OwnerHooksTest.everyCopilotProfileIsDeletedAndTheOthersStay`, `ModelDownloadDeviceTest` and `CustomTabSignInTest`). Against plan 7D's gate on the same device (run 2: 359 run, 246 passed, 0 failed, 113 skipped), this run has 14 tests more (this plan's 12, the device-found fixes' 1 and the notification IDs' 1): 373 run, 258 passed and 115 skipped, or 259 passed and 114 skipped when Kestrel holds the phone-state permission already and the call test runs (measured on `7f57aa3f` and on `d98dc55f`, before the notification IDs' test: 372 run, 0 failed, 115 skipped). The script reports no change of Kestrel's install, of the Google accounts or of Kestrel's profiles. A failure of `aPullOfTheShadeWhileTheGoalActsIsATouchThatHoldsTheGoal` is a finding about design 10.6's premise that a tap on the notification is a touch: stop, run the data check, and report it as that finding. When the only failures are in `dev.kestrel.app.screen.SystemUiFilterTest`, which is known to be flaky on the emulator (a slow notification shade; plan 7D's gate), run that class alone once: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if (-not (Test-KestrelAvdAnswer @(& (Get-Adb) -s emulator-5554 emu avd name))) { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.screen.SystemUiFilterTest'; "exit: $LASTEXITCODE"; scripts\gradle.ps1 --stop`; when it passes, note "flaky, passed when run again" and go on. Any other failure, or a second failure of that class: stop, run the data check, and report.

- [ ] **Step 4: The voice's device tests**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if (-not (Test-KestrelAvdAnswer @(& (Get-Adb) -s emulator-5554 emu avd name))) { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; scripts\voice-test.ps1 -Serial emulator-5554; "exit: $LASTEXITCODE"; scripts\gradle.ps1 --stop`
Expected: `exit: 0`; every test of `dev.kestrel.app.voice` passes and none is skipped; `ConversationTest`'s task now runs with the goal service beside it (96 tests at plan 7D's gate). Any failure: stop, run the data check, and report.

- [ ] **Step 5: The call pause with listening off, with the phone-state permission for one run**

The test needs Kestrel's `READ_PHONE_STATE`, which is not in the kept set and stays as the owner left it (AGENTS.md): the step gives it when it is missing, runs `GoalServiceTest`, takes it back, and compares the permission's whole line, its flags too. Taking it back ends Kestrel's process, which `scripts\connected-test.ps1` has stopped already.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; if (-not (Test-KestrelAvdAnswer @(& $adb -s emulator-5554 emu avd name))) { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; $line = { "$(@(& $adb -s emulator-5554 shell dumpsys package dev.kestrel | Select-String 'android\.permission\.READ_PHONE_STATE:') -join ' | ')".Trim() }; $before = & $line; "phone state before: $before"; $had = $before -match 'granted=true'; if (-not $had) { & $adb -s emulator-5554 shell pm grant dev.kestrel android.permission.READ_PHONE_STATE }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.conversation.GoalServiceTest'; "exit: $LASTEXITCODE"; if (-not $had) { & $adb -s emulator-5554 shell pm revoke dev.kestrel android.permission.READ_PHONE_STATE }; $after = & $line; "phone state after: $after"; "same: $($after -eq $before)"; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL and `exit: 0`; all 11 tests of `GoalServiceTest` pass, none skipped (the call test placed a call to the emulator's fake network and ended it); `same: True`. When the lines differ only by the flag `USER_SET`, which `pm revoke` sets, clear it with `& $adb -s emulator-5554 shell pm clear-permission-flags dev.kestrel android.permission.READ_PHONE_STATE user-set` and compare again. Any other difference, or a failure: stop, run the data check, and report.

- [ ] **Step 6: The whole first search's speed, a close and a search right after a start**

The measurement runs three times, each in a new process: the first run writes the test archive of 30 heavy days and times its last close, the second times the first search once the index has been read, and the third times a search right after a start and then deletes the archive. A fourth run, `clean`, in a `finally` after the loop however the loop ends, deletes the test archive and key in any case (a run that fails deletes them itself, but a killed run leaves them, and a loop that stops early never reaches `start`).

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; if (-not (Test-KestrelAvdAnswer @(& $adb -s emulator-5554 emu avd name))) { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; & $adb -s emulator-5554 logcat -c; try { foreach ($mode in 'write', 'search', 'start') { scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.conversation.ConversationSearchSpeedTest' "-Pandroid.testInstrumentationRunnerArguments.kestrelSearchSpeed=$mode"; "exit ${mode}: $LASTEXITCODE"; if ($LASTEXITCODE -ne 0) { 'the measurement stopped here'; break } } } finally { scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.conversation.ConversationSearchSpeedTest' '-Pandroid.testInstrumentationRunnerArguments.kestrelSearchSpeed=clean'; "exit clean: $LASTEXITCODE" }; & $adb -s emulator-5554 logcat -d -s KestrelSearch:I; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL four times, `exit write: 0`, `exit search: 0`, `exit start: 0` and `exit clean: 0`; the lines `wrote 300 conversations in <s> s`, `closed a conversation in <ms> ms`, `read the index in <ms> ms`, `searched 300 conversations in <ms> ms`, `restored the open conversation in <ms> ms` and `searched right after a start in <ms> ms`. The emulator's Keystore runs on the PC, so these times are only noted; the A71's decide (Task 9). A run that ends with another exit code stops the loop, so that no time is read from a half-written archive (a run that fails deletes the test archive itself, and the `clean` run after the loop, which prints `exit clean: 0` also then, deletes what a killed run left): stop, run the data check, and report.

- [ ] **Step 7: The spoken start, background audio, a held goal's Continue from the notification, and Stop**

The connected tests stopped Kestrel, so it comes to the front first, which lets the hook start listening, and then goes to the back: the goal's start then comes from the voice path while `ListeningService` runs in the foreground. Each goal's round is plan 7D's stand-in, which acts on nothing and, after the given seconds, ends the goal with the given reply as its final words, unless the goal was stopped first. With the goal working, the step turns listening off (the goal goes on, decision 10 of plan 7B) and asks for the audio focus with the speech's types (`speak`) and without them (`focus`), and again after the goal's end with Kestrel in the background, which tells whether Android 17's hardening applies to Kestrel at all. For each start, the step prints the service's lines of `dumpsys activity services` that name the exemption that Android used.

Run, after the step helpers: `& $adb -s $s logcat -c; "assistant: $("$(& $adb -s $s shell cmd role get-role-holders android.app.role.ASSISTANT)".Trim())"; & $adb -s $s shell am start -W -n dev.kestrel/dev.kestrel.app.MainActivity; Start-Sleep -Seconds 3; StandIn 120; Listen; & $adb -s $s shell input keyevent KEYCODE_HOME; Start-Sleep -Seconds 2; Heard 'Kestrel, check the goal service'; Start-Sleep -Seconds 3; Hook 'CONVERSATION_STATE'; Hook 'GOAL_SERVICE_STATE'; Allowed; Quiet; Hook 'CONVERSATION_STATE'; Focus 'speak'; Focus 'focus'; & $adb -s $s shell dumpsys audio | Select-String 'AudioHardening|dev\.kestrel' | ForEach-Object { $_.Line.Trim() }; Hook 'GOAL_CONTROL --es do touch'; Start-Sleep -Seconds 3; Hook 'CONVERSATION_STATE'; Hook 'GOAL_SERVICE_STATE'; StandIn 120; Hook 'GOAL_CONTROL --es do tap --es action continue'; Start-Sleep -Seconds 3; Hook 'CONVERSATION_STATE'; Hook 'GOAL_SERVICE_STATE'; Allowed; Hook 'GOAL_CONTROL --es do tap --es action stop'; Start-Sleep -Seconds 8; Hook 'CONVERSATION_STATE'; Hook 'GOAL_SERVICE_STATE'; Focus 'focus'; & $adb -s $s shell dumpsys audio | Select-String 'AudioHardening|dev\.kestrel' | ForEach-Object { $_.Line.Trim() }`
Expected, in order (each `StandIn` prints `stand-in: <n> s`, or stops the command, so that no goal's round reaches the owner's Copilot profile):
- `assistant:` and the holders of the assistant role, package names only (note them: Android's rules for background audio give an app that serves the assistant role through `VoiceInteractionService` a standing of its own, which would explain a `granted` control below);
- `stand-in: 120 s`, set before listening is on, so that nothing heard can reach a provider (`StandIn`);
- `mode: listening` (`Listen`; it stops the command when Kestrel does not listen after a second toggle);
- `goal=acting`; `service=running notice=working posted=none actions=stop,open progress=none types=special-use applied=special-use android=running androidTypes=special-use starts=1 refusals=0 refuse=none …` (the spoken start), and the service's `allowStartForeground` and `allowWhileInUse` lines (note them: S9's exemption for the spoken start);
- `mode: off` (`Quiet`), then `goal=acting` (the goal works on with listening off);
- `speak=sent`, then `… focus=<granted|failed> focusBy=speak focusTypes=special-use+media-playback focusRequests=<n> focusAnswered=<n>` (`Focus`);
- `focus=sent`, then `… focus=<granted|failed> focusBy=focus focusTypes=special-use focusRequests=<n> focusAnswered=<n>`, one more than the `speak`'s;
- the audio service's lines that name Kestrel or `AudioHardening` (note them);
- `touch=sent`; `goal=interrupted`; `service=running notice=held posted=none actions=cancel,continue … starts=1 refusals=0`;
- `stand-in: 120 s`, `tapped=continue expanded=none shade=closed`; `goal=acting`; `notice=working … starts=2 refusals=0` (the start from the notification's broadcast), and the service's `allowStartForeground` and `allowWhileInUse` lines (note them);
- `tapped=stop expanded=none shade=closed`; `goal=none`; `service=stopped notice=none posted=none … android=none androidTypes=none starts=2 refusals=0 …`;
- `focus=sent`, then `… focus=failed focusBy=focus focusTypes=none focusRequests=<n> focusAnswered=<n>`: without a service and with Kestrel in the background, Android 17's hardening refuses the focus, so the two requests above tell its rules apart. If it says `focus=granted`, the hardening did not apply to Kestrel here (note it: S9 then cannot tell the types apart on kestrel-api37), and the audio service's lines and the assistant role's holders show why.

Every press of `goal-control do=tap` in Task 8 expects `expanded=none`: on Android 17 the goal service's colorized notification is first in the shade and expanded, with its buttons, and the held goal's posted one is a group of its own (design 10.6; the device-found fixes), so the hook needs no expansion; Step 3's `GoalServiceTest.withListeningOnTheShadeShowsTheHeldGoalsCancelAndContinueWithoutExpandingAnything` showed it even with listening's notification beside the goal's, and Steps 7 and 8 press with listening off. An answer with `expanded=row`, `expanded=group` or `expanded=group,row` is a finding against design 10.6 on kestrel-api37, also when the press worked: stop, run the data check, and report the answer.

S9's audio finding is the pair of `speak` and `focus` while the goal worked: `granted` for both means that a foreground service with while-in-use is enough and the type does not matter; `granted` for `speak` only means that the type matters; `failed` for `speak` means that the service lacked while-in-use. A `refusals=1` is S9's finding that Android refused a start: then the goal is held as interrupted, the answer shows `service=stopped notice=none posted=held actions=cancel,continue`, Kestrel said "Android didn't let me start working in the background. … Tap Continue in Kestrel's notification.", and the command's later answers differ; note the start that was refused, then run, after the step helpers, `StandIn 120; Hook 'GOAL_CONTROL --es do tap --es action continue'` (its start comes from a notification) and expect `tapped=continue expanded=none shade=closed` and `starts=` one higher, then end the goal with `Hook 'GOAL_CONTROL --es do tap --es action stop'` (after the step helpers; expect `tapped=stop expanded=none shade=closed`), and go on with Step 8. Any other answer: stop, run the data check, and report.

- [ ] **Step 8: The start after a restart, "Continue the goal?" and its Continue**

The hook `crash` ends Kestrel's process during a goal (spec 16.1). Android restarts the process when it binds Kestrel's accessibility service again; the conversation's restore holds the goal as interrupted and asks "Continue the goal?" (design 6.7), and the goal service starts for the held goal at the process's start. The step waits for the new process and the accessibility service's binding before it sends any broadcast, so that no broadcast of the check starts the process itself. The stand-in of `voice-task` lives only in the process, and after the restart Kestrel asks "Continue the goal?": a heard "yes" would resume the goal on the owner's Copilot profile (the second review of plan 7E, R2-I3). So the step turns listening off before the crash, which the restart keeps (listening restores the stored mode), reads the mode first after the restart and stops unless it is `off`, and sets the stand-in again at once, before anything else, and once more before the Continue. With listening off, it asks for the audio focus with the speech's types, which tells whether a service started at the process's start has while-in-use.

Run, after the step helpers: `& $adb -s $s logcat -c; & $adb -s $s shell am start -W -n dev.kestrel/dev.kestrel.app.MainActivity; Start-Sleep -Seconds 3; StandIn 120; Listen; & $adb -s $s shell input keyevent KEYCODE_HOME; Start-Sleep -Seconds 2; Heard 'Kestrel, check the restart'; Start-Sleep -Seconds 3; Hook 'GOAL_SERVICE_STATE'; Quiet; $old = "$(& $adb -s $s shell pidof dev.kestrel)".Trim(); pwsh -NoProfile -File scripts\hook.ps1 -Serial $s -Command crash -Text 'goal service restart check'; Restarted $old; $m = Mode; if ($m -ne 'off') { Write-Error "Listening is $m after the restart: stop and report" -ErrorAction Stop }; 'mode after the restart: off'; StandIn 120; Start-Sleep -Seconds 5; Hook 'CONVERSATION_CONTROL --es do restore-check'; Hook 'CONVERSATION_STATE'; Hook 'GOAL_SERVICE_STATE'; Allowed; Focus 'speak'; StandIn 5; Hook 'GOAL_CONTROL --es do tap --es action continue'; Start-Sleep -Seconds 20; Hook 'CONVERSATION_STATE'; Hook 'GOAL_SERVICE_STATE'; "kestrel crashes: $(Crashes)"`
Expected, in order: `stand-in: 120 s` (each `StandIn` prints its line, or stops the command); `mode: listening`; `service=running notice=working … starts=3 refusals=0`; `mode: off`; `crash` answers; `process: new bound: True`; `mode after the restart: off`; `stand-in: 120 s`; `restored=goal-held`; `open=yes … goal=interrupted`; `service=running notice=held posted=none actions=cancel,continue … starts=1 refusals=0` (the start at the process's start, in a new process) and the service's `allowStartForeground` and `allowWhileInUse` lines (note them); `speak=sent`, then `… focus=<granted|failed> focusBy=speak focusTypes=special-use+media-playback focusRequests=1 focusAnswered=1` (the new process's first request; note it: S9's audio finding for a service started at the process's start); `stand-in: 5 s`; `tapped=continue expanded=none shade=closed` (with listening off; anything else stops, as Step 7 says); `goal=none` (the resumed round's stand-in answered after 5 s, and the goal ended with its reply); `service=stopped … starts=2 refusals=0`; `kestrel crashes: 1` (the hook's own). When Android did not restart Kestrel by itself within 90 s, `Restarted` stops the command: run the data check and report (the controller decides how to start Kestrel then, after which nothing may be heard before the stand-in is set). If the restored goal's service shows `refusals=1` and `service=stopped`, that is S9's finding for the start at a restart: note it, then run, after the step helpers, `StandIn 5; Hook 'GOAL_CONTROL --es do tap --es action continue'`, and expect `tapped=continue expanded=none shade=closed`, and `goal=none` and `starts=2 refusals=1` at the end. Any other answer: stop, run the data check, and report.

- [ ] **Step 8a: A tap on the posted notification that starts Kestrel's process**

The held goal's notification that Kestrel posts without the service outlives Kestrel's process, and a tap on it can start a new process of Kestrel, whose receiver must wait for the conversation's restore before it counts the tap (the first review's M3 and the second review's R2-M1). The step checks exactly that case, so it makes the button's broadcast the only thing that can start the new process (the second review's R5-M1). First it reads the accessibility setting (as Step 9 does) and Kestrel's notification access; it stops when the setting is empty or `null`, or does not hold Kestrel's service. It sets the stand-in before listening is on; has the goal service's next start refused (`goal-control do=refuse`), so that a goal is held and its notification posted without the service; and turns listening off, so that nothing can be heard after the restart. Then it turns Kestrel's accessibility service off with `Disconnect-AccessibilityService` (`scripts\lib\Kestrel.ps1`), which writes the setting without Kestrel's service and waits until Android lists the service neither as enabled, binding nor crashed and the service is unbound, and it takes Kestrel's notification access away (`cmd notification disallow_listener`) when Kestrel had it, because Android binds both again after a process ends and either binding would start the new process; it checks that both took, and stops otherwise. It reads the process's ID. The hook `goal-control do=send-later --es action cancel` takes the posted notification's own Cancel, the PendingIntent that Android holds on Kestrel's notification and that the owner's tap would send, and hands it to `AlarmManager`, which sends it 5 s later; the hook answers `send=scheduled`, or `send=none` when Kestrel's notification shows no Cancel, which stops the step. Right after the answer the step ends Kestrel's process (`run-as dev.kestrel kill -9`, which, unlike a stop of the app, keeps its alarms). Nothing is tapped on the screen, so no other app's notification can be touched however the shade's rows move (the second review's R4-M1), and no word of the shade leaves the phone. The step waits for a new process by its ID alone, sending nothing to Kestrel, and reads in the log whether Android started it for the button's broadcast (`Start proc <pid>:dev.kestrel/… for broadcast {…GoalActionReceiver}`); only then do its hooks ask whether the Cancel counted for the restored goal. In a `finally`, which runs however the step ends, it gives Kestrel's notification access back when Kestrel had it (AGENTS.md's kept set) and writes the accessibility setting back exactly as it read it with `Connect-AccessibilityService`, which waits up to 30 s until Android has bound the service, and binds it through an off that Android applied when Android holds it as crashed; it checks that both took and that the service is bound, prints only whether each matches, and stops otherwise. Then it takes back a refusal that is still armed (`goal-control do=refuse-off`), which is left only when the step stopped between the arm and the refused start, and checks that `goal-service-state` shows `refuse=none`, so that no later start in that process is refused by the seam (Task 6's review M2). Cancel runs no round, so no stand-in is needed after the restart; the stand-in covers the goal before the kill.

Run, after the step helpers: `Assert-TestDevice -Serial $s; $services = "$(& $adb -s $s shell settings get secure enabled_accessibility_services)".Trim(); if (($services -in '', 'null') -or -not (Test-ServiceListed -Services $services -Component $KestrelAccessibilityService)) { Write-Error "The accessibility setting is empty or lacks Kestrel's service, so the step could not write it back: stop and report" -ErrorAction Stop }; $listener = "$(& $adb -s $s shell settings get secure enabled_notification_listeners)" -match 'dev\.kestrel/\S*KestrelNotificationListener'; "notification access before: $listener"; $old = ''; try { & $adb -s $s logcat -c; & $adb -s $s shell am start -W -n dev.kestrel/dev.kestrel.app.MainActivity; Start-Sleep -Seconds 3; StandIn 120; Listen; & $adb -s $s shell input keyevent KEYCODE_HOME; Start-Sleep -Seconds 2; Hook 'CONVERSATION_CONTROL --es do expire'; Start-Sleep -Seconds 2; Hook 'GOAL_CONTROL --es do refuse'; Heard 'Kestrel, check a tap that starts Kestrel'; Start-Sleep -Seconds 5; Quiet; $off = Disconnect-AccessibilityService -Serial $s; if ($listener) { & $adb -s $s shell cmd notification disallow_listener dev.kestrel/dev.kestrel.datatools.notifications.KestrelNotificationListener }; Start-Sleep -Seconds 3; $still = (-not $off) -or ("$(& $adb -s $s shell settings get secure enabled_notification_listeners)" -match 'dev\.kestrel/'); if ($still) { Write-Error 'Kestrel is still an accessibility service or a notification listener: stop and report' -ErrorAction Stop }; 'kestrel unbound: True'; Hook 'CONVERSATION_STATE'; Hook 'GOAL_SERVICE_STATE'; $old = "$(& $adb -s $s shell pidof dev.kestrel)".Trim(); $sent = Hook 'GOAL_CONTROL --es do send-later --es action cancel'; if ("$sent" -notmatch 'send=scheduled') { Write-Error "Kestrel's goal notification shows no Cancel ($sent): stop and report" -ErrorAction Stop }; & $adb -s $s shell "run-as dev.kestrel kill -9 $old"; $deadline = (Get-Date).AddSeconds(30); do { Start-Sleep -Milliseconds 500; $new = "$(& $adb -s $s shell pidof dev.kestrel)".Trim() } until (($new -and $new -ne $old) -or (Get-Date) -gt $deadline); if (-not ($new -and $new -ne $old)) { Write-Error 'No new process of Kestrel within 30 s of the kill: stop and report' -ErrorAction Stop }; 'process: new'; Start-Sleep -Seconds 8; $byButton = @(& $adb -s $s logcat -d | Select-String "Start proc ${new}:dev\.kestrel/\S+ for broadcast \{dev\.kestrel/[\w.]*GoalActionReceiver\}").Count -eq 1; "started by the button: $byButton"; if (-not $byButton) { Write-Error 'Something other than the button started the new process: stop and report' -ErrorAction Stop }; Hook 'CONVERSATION_CONTROL --es do restore-check'; Hook 'CONVERSATION_STATE'; Hook 'GOAL_SERVICE_STATE'; "mode: $(Mode)"; "dropped taps: $(@(& $adb -s $s logcat -d | Select-String "A goal notification's button failed").Count)" } finally { if ($listener) { & $adb -s $s shell cmd notification allow_listener dev.kestrel/dev.kestrel.datatools.notifications.KestrelNotificationListener }; $bound = Connect-AccessibilityService -Serial $s -Services $services; $same = "$(& $adb -s $s shell settings get secure enabled_accessibility_services)".Trim() -ceq $services; $access = (-not $listener) -or ("$(& $adb -s $s shell settings get secure enabled_notification_listeners)" -match 'dev\.kestrel/\S*KestrelNotificationListener'); "accessibility as before: $same"; "notification access as before: $access"; "bound again: $bound"; $refusal = "$(Hook 'GOAL_CONTROL --es do refuse-off')"; $none = ($refusal -match 'refuse=(none|disarmed)') -and ("$(Hook 'GOAL_SERVICE_STATE')" -match ' refuse=none '); "refusal: $([regex]::Match($refusal, 'refuse=\w+').Value)"; "no refusal left: $none"; if (-not ($same -and $access -and $bound -and $none)) { Write-Error 'Kestrel is not back as it was: stop and report' -ErrorAction Stop } }`
Expected, in order: `notification access before: True` (or `False` when Kestrel's notification access was off; then the step neither takes it away nor gives it back); `stand-in: 120 s`; `mode: listening`; `expire=sent`; `refuse=armed`; `mode: off`; `kestrel unbound: True`; `goal=interrupted`; `service=stopped notice=none posted=held actions=cancel,continue … refusals=1 refuse=none …` (the refused start held the goal, Kestrel posted its notification, and the start took the armed refusal); `send=scheduled`; `process: new`; `started by the button: True` (Android started the new process for the button's broadcast, so the receiver ran in a process that had not restored yet); `restored=goal-held` (the kill is no owner's stop, and the Cancel had not come before it); `goal=none` (the Cancel counted for the restored goal, after the restore); a `goal-service-state` line with `posted=none` (note its `starts=` and `service=`: the restore may have started the service for the restored goal before the Cancel); `mode: off`; `dropped taps: 0`; then, from the `finally`, `accessibility as before: True`, `notification access as before: True`, `bound again: True`, `refusal: refuse=none` (`refuse=disarmed` when the step stopped between `refuse=armed` and the refused start: note it) and `no refusal left: True`. A `goal=interrupted` at the end means that the Cancel was lost: stop, run the data check, and report, with `dropped taps:`, which counts the receiver's drops after a slow restore. A `restored=goal-ended` or `goal=none` before the kill means that the Cancel reached the old process: stop, run the data check, and report. Every other stop of the command (`send=none`, no new process, `started by the button: False`, Kestrel still bound before the kill) also ends in the `finally`, which puts the two settings back: run the data check and report, with the `finally`'s five lines. When one of those lines says `False`, the command stops after printing it: report it at once, because kestrel-api37 is then not as Step 1 left it. When `Disconnect-AccessibilityService` or `Connect-AccessibilityService` warns, the warning names the service's state; report it. Neither throws when the device does not answer: each warns and returns `False`, so the `finally` still prints its lines and takes back the refusal.

- [ ] **Step 9: The owner's stop in "Active apps"**

`adb shell cmd activity stop-app dev.kestrel` stops Kestrel as the Task Manager's Stop does (Android's documentation names it for this test). At the next start the restore must end the goal as "stop" does: no "Continue the goal?", no goal service, and listening stays off. Before its command, the step ends the quiet time of Step 8a's conversation (`conversation-control do=expire`), so that the command opens a conversation of its own and the restored conversation's `commands=1` shows that it is this one. The step prints only the reason and the sub-reason that Android recorded for the stop, which S9 and the owner's decision of 2026-11-25 need. A stop of the app leaves Kestrel's accessibility service in Android's crashed set while the setting still names it, and Android binds it again neither by itself nor when the same setting is written again: AOSP's `AccessibilityManagerService.updateServicesLocked` skips a crashed service, and only a change of the enabled list clears the set, through `AccessibilityUserState.removeDisabledServicesFromTemporaryStatesLocked` (Task 8's fourth run). So the step prints the service's state after the stop, and binds it again with `Connect-AccessibilityService` (`scripts\lib\Kestrel.ps1`), which turns the service off, waits until Android has applied the off, writes back the setting as the step found it at its start (which must be Step 1's), and waits up to 30 s until the service is bound. At the restart Kestrel posts its notice that the accessibility service is off, because Android holds the service as crashed after the stop (spec 14.6), and the notice goes when the step binds the service again. Kestrel posts it 10 s after the start of its process (`UnboundServiceCheck.GRACE_MILLIS`), so the step waits 12 s after the restart before it reads the notices (`Hook 'NOTICES'`, which answers with counts and kinds only); after `bound: True` it reads them again for up to 10 s, because the service's `onServiceConnected`, which cancels the notice, runs in Kestrel's process a moment after Android lists the service as bound.

Run, after the step helpers: `Assert-TestDevice -Serial $s; $services = "$(& $adb -s $s shell settings get secure enabled_accessibility_services)".Trim(); if (($services -in '', 'null') -or -not (Test-ServiceListed -Services $services -Component $KestrelAccessibilityService)) { Write-Error "The accessibility setting is empty or lacks Kestrel's service, so the step could not write it back: stop and report" -ErrorAction Stop }; "accessibility before: $services"; & $adb -s $s shell am start -W -n dev.kestrel/dev.kestrel.app.MainActivity; Start-Sleep -Seconds 3; StandIn 120; Listen; & $adb -s $s shell input keyevent KEYCODE_HOME; Start-Sleep -Seconds 2; Hook 'CONVERSATION_CONTROL --es do expire'; Start-Sleep -Seconds 2; Heard 'Kestrel, check the owner''s stop'; Start-Sleep -Seconds 3; Hook 'CONVERSATION_STATE'; & $adb -s $s shell cmd activity stop-app dev.kestrel; Start-Sleep -Seconds 3; $exit = "$(& $adb -s $s shell dumpsys activity exit-info dev.kestrel)"; $reason = [regex]::Match($exit, 'reason=\d+ \([^)]*\)').Value; $subreason = [regex]::Match($exit, 'subreason=\d+ \([^)]*\)').Value; "exit: $reason $subreason"; & $adb -s $s shell am start -W -n dev.kestrel/dev.kestrel.app.MainActivity; Start-Sleep -Seconds 12; & $adb -s $s shell input keyevent KEYCODE_HOME; Hook 'CONVERSATION_CONTROL --es do restore-check'; Hook 'CONVERSATION_STATE'; Hook 'GOAL_SERVICE_STATE'; "mode: $(Mode)"; Hook 'NOTICES'; "after the stop: $(Format-ServiceState (Get-AccessibilityServiceState -Serial $s))"; "bound: $(Connect-AccessibilityService -Serial $s -Services $services)"; foreach ($try in 1..10) { $notices = Hook 'NOTICES'; if ($notices -match 'notices=0 kinds=none') { break }; Start-Sleep -Seconds 1 }; $notices`
Expected, in order: `accessibility before:` and the setting of Step 1; `stand-in: 120 s` (or the command stops); `mode: listening`; `expire=sent`; `goal=acting` and `commands=1`; `exit: reason=10 (USER REQUESTED)` and the sub-reason that Android 17 gives (note both: the documentation names only the reason); `restored=goal-ended`; `goal=none` and `commands=1`; `service=stopped notice=none posted=none … starts=0 refusals=0` (the restore started no service); `mode: off` (listening stays off); `notices=1 kinds=accessibility` (the notice after the owner's stop, before the step binds the service again); `after the stop: enabled=True binding=False crashed=True bound=False` (Android holds the stopped app's service as crashed; the line comes after MainActivity started Kestrel's process again, and a new process does not clear the crashed state, so `bound=False` with a running process is expected; note another state); `bound: True` (`False` comes with `Connect-AccessibilityService`'s warning, which names the state: stop, run the data check, and report); `notices=0 kinds=none` (the service's `onServiceConnected` cancelled the notice). If the reason is another one, or `restored=goal-held`, the owner's stop was not read as his: stop, run the data check, and report the two lines. The command binds the service before it reads the notices again, so it does not stop at a notice's answer: when the first `NOTICES` answer is not `notices=1 kinds=accessibility` (Kestrel did not tell the owner), or the last one is not `notices=0 kinds=none` (a `kinds=accessibility` after `bound: True` means that the cancel did not run), stop after the command, run the data check, and report both answers.

- [ ] **Step 10: Put kestrel-api37 back**

Run, with the mode of Step 1, after the step helpers: `$mode = '<the mode of Step 1>'; pwsh -NoProfile -File scripts\hook.ps1 -Serial $s -Command voice-task -Clear; if ($mode -ne 'off' -and (Mode) -eq 'off') { & $adb -s $s shell am start -W -n dev.kestrel/dev.kestrel.app.MainActivity; Start-Sleep -Seconds 3; Listen }; if ($mode -eq 'paused') { Voice 'pause' | Out-Null; Start-Sleep -Seconds 3 }; if ($mode -eq 'off') { Quiet }; & $adb -s $s shell cmd statusbar collapse; & $adb -s $s shell input keyevent KEYCODE_HOME; $now = Mode; "mode: $now"; if ($now -ne $mode) { Write-Error "Listening is $now, not $($mode): stop and report" -ErrorAction Stop }; Assert-TestDevice -Serial $s; "accessibility bound: $(Connect-AccessibilityService -Serial $s -Services '<the accessibility setting of Step 1>')"; "accessibility: $(& $adb -s $s shell settings get secure enabled_accessibility_services)"`
Expected: `mode:` and the mode of Step 1 (the command stops when it is another one); `accessibility bound: True` (it writes nothing when Kestrel's service is already bound under Step 1's setting; `False`: run the data check and report); the accessibility setting of Step 1. Then the data check, the last one of the task, and, when it says `owner data: as before`, `Remove-Item 'H:\Code\kestrel\.superpowers\sdd\plan-7e-owner-data.xml'`.

- [ ] **Step 11: Note the results**

Add to `.superpowers\sdd\progress.md`, under "## Plan 7E" (a delegated agent writes these lines into its report under "## Ledger lines", and the controller copies them): the build's commit on kestrel-api37; the data check's answers before and after every step; the counts of Tasks 1 to 7; the numbers of tests that passed and were skipped in Steps 3 to 6; the times of Step 6 (the write run, the close, the index's read, the first search, the restore and the search right after a start); and S9's findings on kestrel-api37: for each of the spoken start (Step 7), the start from the notification's Continue (Step 7) and the start at a restart (Step 8), whether Android allowed it (`refusals=0`) or refused it, with the types that Android applied (`androidTypes=`) and the `allowStartForeground` and `allowWhileInUse` lines; the audio focus for `speak` and `focus` while the goal worked, after the restart, and after the goal's end, with the audio service's lines and the assistant role's holders; whether the pull of the shade held the goal (Step 3); the `expanded=` of every press of Steps 7 and 8 (`none` each, or the step stopped); whether the posted notification's Cancel, whose broadcast started a new process of Kestrel (`started by the button:`), counted after the restore (Step 8a, with `dropped taps:` and the `finally`'s five lines); and the reason and sub-reason of the owner's stop with the restore's answer, the service's state after the stop (`after the stop:`), `bound:`, and the two `NOTICES` answers, before the service was bound again and after it (Step 9).

---

### Task 9: The checks on the A71 (waits for the owner)

Design 15.2 of sub-project 7 on the A71 (Android 13, One UI 5.1), within AGENTS.md's limits: the goal service's starts on Android 13, where the service has no type (spike S9: the spoken start, the start from the notification's Continue, and the start after a restart), the audio focus with listening off (Android 13 has no background-audio hardening, so the record only shows that nothing refuses it), the owner's stop in "Active apps" with the reason that Android 13 records for it, and the whole first search over an archive of 30 heavy days against design 4.3's 1 s, with a close and a search right after a start. The typed goal with listening off and the typed start need plan 7F's conversation screen, so plan 7F's A71 checks carry them (see "Decisions for the controller", item 2). Every earlier task is complete and green without this one; this task waits until the owner is back and the A71 is on USB.

The checks call no one, send no message, change none of the owner's accounts, put every setting back, and answer only kinds and counts. The hook `goal-control do=tap` reads the shade's notifications on the phone, in memory only, and presses only Kestrel's own button, by its key, checked again at the press. Kestrel speaks its stand-in replies aloud on the A71. The conversations of the checks stay in Kestrel's past conversations on the A71, under their test requests, until their retention ends.

**Files:**
- None in git; Step 9 writes `.superpowers\sdd\progress.md`; Step 3 writes the data check's state file `H:\Code\kestrel\.superpowers\sdd\plan-7e-owner-data-a71.xml` (git-ignored there), and Step 8 deletes it after the last check.

**Interfaces:**
- Consumes: every earlier task; the same hooks as Task 8; plan 7C's `ConversationArchiveSpeedTest`; `scripts\install.ps1`, `scripts\connected-test.ps1`, `scripts\hook.ps1`; `scripts\lib\Kestrel.ps1`'s `Connect-AccessibilityService`.
- Produces: the A71's results in `.superpowers\sdd\progress.md`, under "## Plan 7E": spike S9's findings on Android 13, the reason of the owner's stop on Android 13, and the first search's time against design 4.3.

Counts: none change.

**The build (amendment of 2026-11-26).** Task 9 installs `sp3` at `55de7dfd` or a later commit, as Task 8 does, so that the A71 gets the device-found fixes, the hook's `expanded=`, the notification IDs of Task 8's third run, and the accessibility helpers and the unbound-service notice of its fourth run too (Step 2 checks it). Without `415ed6bd`, the A71 would lose the held goal's notification in the same way whenever Kestrel's accessibility service went off, because the accessibility notice had the same ID. Without `b907e8ed`, Step 7 could not bind Kestrel's accessibility service again after the owner's stop: Android 13 holds the stopped app's service as crashed in the same way (AOSP 13 has the same crashed set and the same skip), and Step 7 expects the notice of `4cdc50ca` and `7a491675`. The A71's part is otherwise unaffected by them: kestrel-api37's start without a window and its window rules concern only the emulator, and Task 9 sends nothing to kestrel-api37. On the A71 (Android 13 with One UI), design 10.6's rules for the goal's row are not verified yet: the row should be first and expanded (the notification's own app group and its colour), but One UI may order or expand rows in its own way. `goal-control do=tap` expands a collapsed group or row before its press and says so in its answer (`expanded=none|row|group|group,row`, decision 16), so each press of Steps 5 to 7 records the A71's value: the finding for design 10.6 on Android 13. With the notification's own app group (`NotificationGroups.alone`, the explicit-group fix), the expected result is `expanded=none`; another value does not stop the task when the press worked, and the controller takes it, with the steps' answers, to the design's rule for One UI.

**How the task runs.** The A71 is the owner's phone, used within AGENTS.md's limits: no call, no message, no change to his accounts, every setting put back, and no words that Kestrel heard in any result. The controller runs the steps with these rules:
- Only the A71 (`R58N66RVD7Z`) and only the steps' commands: every adb command names `-s R58N66RVD7Z`, and the device work goes only through `scripts\install.ps1`, `scripts\connected-test.ps1`, `scripts\hook.ps1`, the plain adb commands of the steps, and `scripts\lib\Kestrel.ps1`'s `Connect-AccessibilityService` in Steps 7 and 8, which changes only the list of enabled accessibility services and does so only on the device that `Assert-TestDevice` accepted, with the reads `Get-AccessibilityServiceState`, `Format-ServiceState` and `Test-ServiceListed`, which change nothing.
- **The data check**, after each of Steps 3 to 7 and at the end of Step 8: `. scripts\lib\Kestrel.ps1; . scripts\lib\Session.ps1; . scripts\lib\DeviceGuard.ps1; Use-KestrelToolchain; Assert-TestDevice -Serial R58N66RVD7Z; $adb = Get-Adb; $before = Import-Clixml 'H:\Code\kestrel\.superpowers\sdd\plan-7e-owner-data-a71.xml'; $changes = @(Compare-OwnerData -Serial R58N66RVD7Z -Before $before -During 'plan 7E, Task 9'); if ($changes.Count) { $changes; Write-Error "The owner's data changed or could not be read: put the A71 back with Step 8 and ask the controller" -ErrorAction Stop }; "owner data: as before ($(Format-OwnerDataState $before 'R58N66RVD7Z'))"`. Expected: one line that starts with `owner data: as before`. Step 3 records the state right after the install, because Kestrel answers `provider-standin` in the form that the check reads only from this build on; Step 2 has already counted the Google accounts, and Step 3's state must have the same number. Any other answer, or an error: stop at once, put the A71 back with Step 8 and ask the controller.
- No goal's round may reach a provider or act on the owner's phone: `StandIn` comes before listening is turned on (right after Kestrel comes to the front) and again before every Continue, and after a restart listening stays off until the stand-in is set again (Step 6). So no moment exists in which Kestrel listens on the A71 without the stand-in.
- Stop at the first failure: an exit code other than 0, an answer that differs from a step's Expected, or anything that the steps do not describe. Then run Step 8, which puts the A71 back whatever ended the task, and ask the controller; nothing is run again.
- The shade is put back however a press ends: `goal-control do=tap` closes it again itself and says what the screen shows afterwards (`shade=closed`, also at the end of its error). It says `shade=open` also when it cannot tell (amendment of 2026-11-26): while the phone is locked, the shade counts as closed only when the shade that the hook opened showed a Quick Settings tile that `systemui-parts.json` knows, and the A71's entry has no tile IDs yet, so a locked A71 always answers `shade=open`; no step taps while the phone is locked. When an answer of `goal-control do=tap` says `shade=open`, or has no `shade=` (the hook's timeout), run `& $adb -s R58N66RVD7Z shell cmd statusbar collapse` at once (a collapse of a shade that is already closed does no harm), then go on as the step says for that answer.

**The step helpers.** Steps 5 to 8 each run one command that starts with these helpers, pasted exactly as they are, and then the step's own text; they are Task 8's helpers for the A71, whose stops end in this task's rule: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; $s = 'R58N66RVD7Z'; if (-not (@(& $adb devices) -match '^R58N66RVD7Z\s+device')) { Write-Error 'The A71 is not on USB' -ErrorAction Stop }; function Hook([string]$command) { "$(& $adb -s $s shell "am broadcast -n dev.kestrel/dev.kestrel.app.debug.TestHookReceiver -a dev.kestrel.hook.$command" | Select-String 'data=')" }; function Voice([string]$send) { pwsh -NoProfile -File scripts\hook.ps1 -Serial $s -Command voice -Send $send }; function Heard([string]$text) { pwsh -NoProfile -File scripts\hook.ps1 -Serial $s -Command voice -Send heard -Text $text }; function Mode { [regex]::Match("$(pwsh -NoProfile -File scripts\hook.ps1 -Serial $s -Command voice-state)", 'mode=(\w+)').Groups[1].Value }; function Listen { foreach ($try in 1, 2) { Voice 'toggle' | Out-Null; Start-Sleep -Seconds 10; $m = Mode; if ($m -eq 'listening') { 'mode: listening'; return }; if ($m -ne 'off') { break } }; Write-Error "Listening did not start (mode=$m): put the A71 back with Step 8 and ask the controller" -ErrorAction Stop }; function Quiet { Voice 'off' | Out-Null; Start-Sleep -Seconds 3; $m = Mode; if ($m -ne 'off') { Write-Error "Listening did not turn off (mode=$m): put the A71 back with Step 8 and ask the controller" -ErrorAction Stop }; 'mode: off' }; function StandIn([int]$seconds) { $answer = "$(pwsh -NoProfile -File scripts\hook.ps1 -Serial $s -Command voice-task -Seconds $seconds -Reply 'stand-in')"; if ($answer -notmatch "each goal's round now takes $seconds s") { Write-Error 'The stand-in round is not set: put the A71 back with Step 8 and ask the controller' -ErrorAction Stop }; "stand-in: $seconds s" }; function Allowed { & $adb -s $s shell dumpsys activity services dev.kestrel/.app.goal.GoalService | Select-String 'allowStartForeground|allowWhileInUse|foregroundServiceType|isForeground' | ForEach-Object { $_.Line.Trim() } }; function Restarted([string]$old) { $deadline = (Get-Date).AddSeconds(90); do { Start-Sleep -Seconds 2; $new = "$(& $adb -s $s shell pidof dev.kestrel)".Trim(); $bound = "$(& $adb -s $s shell dumpsys activity services dev.kestrel/.app.KestrelAccessibilityService)" -match 'app=ProcessRecord' } until (($new -and $new -ne $old -and $bound) -or (Get-Date) -gt $deadline); if (-not ($new -and $new -ne $old -and $bound)) { Write-Error 'No new process of Kestrel with its accessibility service bound within 90 s: put the A71 back with Step 8 and ask the controller' -ErrorAction Stop }; 'process: new bound: True' }; function Crashes { @(& $adb -s $s logcat -d | Select-String 'FATAL EXCEPTION' -Context 0,1 | Where-Object { "$($_.Context.PostContext)" -match 'Process: dev\.kestrel,' }).Count }; function Focus([string]$kind) { $n = 1 + [int][regex]::Match("$(Hook 'GOAL_SERVICE_STATE')", ' focusRequests=(\d+)').Groups[1].Value; Hook "GOAL_CONTROL --es do $kind"; $deadline = (Get-Date).AddSeconds(15); do { Start-Sleep -Seconds 1; $state = Hook 'GOAL_SERVICE_STATE' } until (($state -match " focusRequests=$n focusAnswered=$n\b") -or (Get-Date) -gt $deadline); if ($state -notmatch " focusRequests=$n focusAnswered=$n\b") { Write-Error "The $kind request did not answer within 15 s: put the A71 back with Step 8 and ask the controller" -ErrorAction Stop }; $state }`.

- [ ] **Step 1: The owner's part (about 2 minutes)**

The owner connects the A71 by USB, unlocks it, leaves it on with its screen on, and checks in Android's accessibility settings that Kestrel's service is on (he turns it on if it is off). Nothing else needs his hands.

- [ ] **Step 2: The base, and the A71's state before the checks**

Run: `. scripts\lib\Kestrel.ps1; . scripts\lib\Session.ps1; . scripts\lib\DeviceGuard.ps1; Use-KestrelToolchain; Assert-TestDevice -Serial R58N66RVD7Z; $adb = Get-Adb; "google accounts: $(Get-GoogleAccountCount @(Invoke-SettingsCommand -Serial R58N66RVD7Z -Command 'dumpsys account'))"; "stay on: $(& $adb -s R58N66RVD7Z shell settings get global stay_on_while_plugged_in)"; "accessibility: $(& $adb -s R58N66RVD7Z shell settings get secure enabled_accessibility_services)"; "notifications: $("$(@(& $adb -s R58N66RVD7Z shell dumpsys package dev.kestrel | Select-String 'android\.permission\.POST_NOTIFICATIONS:') -join ' | ')".Trim())"; pwsh -NoProfile -File scripts\hook.ps1 -Serial R58N66RVD7Z -Command voice-state; pwsh -NoProfile -File scripts\hook.ps1 -Serial R58N66RVD7Z -Command state; git rev-parse HEAD; git merge-base --is-ancestor 55de7dfd HEAD; "contains 55de7dfd: $($LASTEXITCODE -eq 0)"; "accessibility helper: $([bool](Get-Command Connect-AccessibilityService -ErrorAction SilentlyContinue))"`
Expected: the number of Google accounts on the A71 (note it: Step 3's state must have it); the A71's setting "stay awake" (note it, usually `0`); its accessibility setting (note it); Kestrel's `POST_NOTIFICATIONS` line, read and not changed (note whether it says `granted=true`); a `voice-state` line whose `mode=` is Kestrel's listening mode (note it); Kestrel's `commit=`; and HEAD at `55de7dfd` or a later commit of `sp3`, with no change to `app/` or `core/`, and `contains 55de7dfd: True` (`False`: stop, put the A71 back with Step 8 and ask the controller); `accessibility helper: True` (`False`: the same). Without `POST_NOTIFICATIONS`, the goal notification shows only in Android's Task Manager, so Steps 5 and 6 skip their presses of its buttons (each step says how) and note it.

- [ ] **Step 3: Install this build on the A71**

From this install on, Kestrel's database on the A71 is at version 3 (plan 7C), unless an earlier plan installed a build with plan 7C there: from then on, AGENTS.md's rule of plan 7C holds for the A71, and only builds with plan 7C may be installed there. Note it in `.superpowers\sdd\progress.md` if it is new.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; & $adb -s R58N66RVD7Z shell settings put global stay_on_while_plugged_in 2; & $adb -s R58N66RVD7Z logcat -c; scripts\install.ps1 -Serial R58N66RVD7Z; scripts\gradle.ps1 --stop; pwsh -NoProfile -File scripts\hook.ps1 -Serial R58N66RVD7Z -Command state; Start-Sleep -Seconds 5; $crashes = @(& $adb -s R58N66RVD7Z logcat -d | Select-String 'FATAL EXCEPTION' -Context 0,1 | Where-Object { "$($_.Context.PostContext)" -match 'Process: dev\.kestrel,' }); "kestrel crashes: $($crashes.Count)"; . scripts\lib\Session.ps1; . scripts\lib\DeviceGuard.ps1; Assert-TestDevice -Serial R58N66RVD7Z; $state = Get-OwnerDataState -Serial R58N66RVD7Z; Format-OwnerDataState $state 'R58N66RVD7Z'; if (-not $state.Profiles) { Write-Error "Kestrel's profiles on the A71 could not be read: put the A71 back with Step 8 and ask the controller" -ErrorAction Stop }; $state | Export-Clixml 'H:\Code\kestrel\.superpowers\sdd\plan-7e-owner-data-a71.xml'`
Expected: a `Success` line of `adb install`; `commit=<HEAD>` and `dirty=0`; `kestrel crashes: 0`; one line of `Format-OwnerDataState` whose number of Google accounts is Step 2's (note the line: the data checks compare with it). The A71 stays awake while it is on USB until Step 8 puts the setting back.

- [ ] **Step 4: The whole first search's speed on the A71**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; & $adb -s R58N66RVD7Z logcat -c; try { foreach ($mode in 'write', 'search', 'start') { scripts\connected-test.ps1 -Serial R58N66RVD7Z :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.conversation.ConversationSearchSpeedTest' "-Pandroid.testInstrumentationRunnerArguments.kestrelSearchSpeed=$mode"; "exit ${mode}: $LASTEXITCODE"; if ($LASTEXITCODE -ne 0) { 'the measurement stopped here'; break } } } finally { scripts\connected-test.ps1 -Serial R58N66RVD7Z :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.kestrel.app.conversation.ConversationSearchSpeedTest' '-Pandroid.testInstrumentationRunnerArguments.kestrelSearchSpeed=clean'; "exit clean: $LASTEXITCODE" }; & $adb -s R58N66RVD7Z logcat -d -s KestrelSearch:I; scripts\gradle.ps1 --stop`
Expected: `scripts\connected-test.ps1` accepts the A71 as a test device; `exit write: 0`, `exit search: 0`, `exit start: 0` and `exit clean: 0` (a run with another exit code stops the loop, and the task, after the `clean` run, which runs in a `finally` however the loop ends and deletes the test archive and key); the six lines of Task 8, Step 6. The write run takes about a minute and a half on the A71 (design R3: each close encodes and encrypts one shard). When `.superpowers\sdd\progress.md` has no A71 time of plan 7C's `ConversationArchiveSpeedTest` yet, run that measurement too, as plan 7C's Task 8, Step 5 says. Design 4.3 and R3 at 30 days: the first search (`searched 300 conversations`, R3's estimate about 0.32 s) and the search right after a start (`searched right after a start`, about 0.64 s) take at most 1,000 ms each, and a close (`closed a conversation`) about 0.4 s at most. When either search takes more than 1,000 ms, the controller takes the times, with plan 7C's time of the archive's share, to the owner.

- [ ] **Step 5: The spoken start, the audio focus, a held goal's Continue from the notification, and Stop, on Android 13**

The connected test stopped Kestrel, so it comes to the front first and then goes to the back, as on kestrel-api37. The touch comes from the hook, because a real touch on the A71's screen could act in the owner's apps. Without `POST_NOTIFICATIONS` (Step 2), replace `StandIn 120; Hook 'GOAL_CONTROL --es do tap --es action continue'` with `StandIn 120; & $adb -s $s shell am start -W -n dev.kestrel/dev.kestrel.app.MainActivity; Start-Sleep -Seconds 3; Listen; & $adb -s $s shell input keyevent KEYCODE_HOME; Heard 'Kestrel, continue'` and the press of Stop with `Heard 'cancel'`, and note that the notification's starts were not checked.

Run, after the step helpers: `& $adb -s $s shell am start -W -n dev.kestrel/dev.kestrel.app.MainActivity; Start-Sleep -Seconds 3; StandIn 120; Listen; & $adb -s $s shell input keyevent KEYCODE_HOME; Start-Sleep -Seconds 2; Heard 'Kestrel, check the goal service'; Start-Sleep -Seconds 3; Hook 'CONVERSATION_STATE'; Hook 'GOAL_SERVICE_STATE'; Allowed; Quiet; Focus 'speak'; Focus 'focus'; Hook 'GOAL_CONTROL --es do touch'; Start-Sleep -Seconds 3; Hook 'CONVERSATION_STATE'; Hook 'GOAL_SERVICE_STATE'; StandIn 120; Hook 'GOAL_CONTROL --es do tap --es action continue'; Start-Sleep -Seconds 3; Hook 'CONVERSATION_STATE'; Hook 'GOAL_SERVICE_STATE'; Allowed; Hook 'GOAL_CONTROL --es do tap --es action stop'; Start-Sleep -Seconds 8; Hook 'CONVERSATION_STATE'; Hook 'GOAL_SERVICE_STATE'`
Expected, in order (each `StandIn` prints `stand-in: <n> s`, or stops the command, so that no goal's round reaches a provider or acts on the owner's phone): `stand-in: 120 s`; `mode: listening`; `goal=acting`; `service=running notice=working posted=none actions=stop,open progress=none types=none applied=none android=running androidTypes=none starts=1 refusals=0 refuse=none …` (Android 13: no type) and the service's `allowStartForeground` and `allowWhileInUse` lines (note them); `mode: off`; `speak=sent`, then `focus=granted focusBy=speak focusTypes=none focusRequests=<n> focusAnswered=<n>`; `focus=sent`, then `focus=granted focusBy=focus focusTypes=none focusRequests=<n> focusAnswered=<n>`, one more than the `speak`'s (Android 13's two requests answer `focusTypes=none` both, so only the counts tell the second from the first; Android 13 hardens no background audio, and a `failed` is a finding); `touch=sent`; `goal=interrupted`; `notice=held posted=none actions=cancel,continue … starts=1 refusals=0`; `stand-in: 120 s`; `tapped=continue expanded=<value> shade=closed` (One UI's shade; note the value); `goal=acting`; `starts=2 refusals=0` and the service's lines (note them); `tapped=stop expanded=<value> shade=closed` (note the value); `goal=none`; `service=stopped … android=none … starts=2 refusals=0`. The expected `expanded=` is `none` each time, after the explicit-group fix; `row`, `group` or `group,row` is design 10.6's finding on Android 13 (Step 9 records it), and the step goes on. A `refusals=1` is S9's finding, handled as in Task 8, Step 7, with this task's helpers. When `goal-control` answers that the goal notification's button is not in the shade (`error: the goal notification's <action> is not in the shade; shade=closed`), One UI shows the row in another form: note it, end the goal with `& $adb -s $s shell am start -W -n dev.kestrel/dev.kestrel.app.MainActivity; Start-Sleep -Seconds 3; Listen; & $adb -s $s shell input keyevent KEYCODE_HOME; Heard 'cancel'` after the step helpers, then put the A71 back with Step 8 and ask the controller, who decides whether `NoticeButtons` learns One UI's form.

- [ ] **Step 6: The start after a restart on Android 13**

Run, after the step helpers: `& $adb -s $s logcat -c; & $adb -s $s shell am start -W -n dev.kestrel/dev.kestrel.app.MainActivity; Start-Sleep -Seconds 3; StandIn 120; Listen; & $adb -s $s shell input keyevent KEYCODE_HOME; Start-Sleep -Seconds 2; Heard 'Kestrel, check the restart'; Start-Sleep -Seconds 3; Hook 'GOAL_SERVICE_STATE'; Quiet; $old = "$(& $adb -s $s shell pidof dev.kestrel)".Trim(); pwsh -NoProfile -File scripts\hook.ps1 -Serial $s -Command crash -Text 'goal service restart check'; Restarted $old; $m = Mode; if ($m -ne 'off') { Write-Error "Listening is $m after the restart: put the A71 back with Step 8 and ask the controller" -ErrorAction Stop }; 'mode after the restart: off'; StandIn 120; Start-Sleep -Seconds 5; Hook 'CONVERSATION_CONTROL --es do restore-check'; Hook 'CONVERSATION_STATE'; Hook 'GOAL_SERVICE_STATE'; Allowed; StandIn 5; Hook 'GOAL_CONTROL --es do tap --es action continue'; Start-Sleep -Seconds 20; Hook 'CONVERSATION_STATE'; Hook 'GOAL_SERVICE_STATE'; "kestrel crashes: $(Crashes)"`
Listening goes off before the crash and stays off after the restart, and the stand-in is set again at once, before anything else, because after the restart Kestrel asks "Continue the goal?" aloud, and a heard "yes" without the stand-in would resume the goal on the owner's profile and act on his phone (the second review of plan 7E, R2-I3).

Expected, in order: `stand-in: 120 s`; `mode: listening`; `service=running notice=working … starts=3 refusals=0`; `mode: off`; `crash` answers; `process: new bound: True`; `mode after the restart: off`; `stand-in: 120 s`; `restored=goal-held`; `open=yes … goal=interrupted`; `service=running notice=held posted=none actions=cancel,continue … types=none … starts=1 refusals=0` and the service's lines (note them); `stand-in: 5 s`; `tapped=continue expanded=<value> shade=closed` (note the value: `none` is expected, as in Step 5, and another one is the finding of design 10.6 and does not stop the step); `goal=none`; `service=stopped … starts=2 refusals=0`; `kestrel crashes: 1` (the hook's own). If Android shows its dialog for the crash, note it and close it with `& $adb -s R58N66RVD7Z shell input keyevent KEYCODE_BACK` only when the dialog's window is Android's (`& $adb -s R58N66RVD7Z shell dumpsys window | Select-String 'Application Error'`). A restart that `Restarted` does not see, or a refusal at the restart, are handled as in Task 8, Step 8, with this task's helpers and rule; without `POST_NOTIFICATIONS`, replace the press with `& $adb -s $s shell am start -W -n dev.kestrel/dev.kestrel.app.MainActivity; Start-Sleep -Seconds 3; Listen; & $adb -s $s shell input keyevent KEYCODE_HOME; Heard 'Kestrel, continue'`, after `StandIn 5`.

- [ ] **Step 7: The owner's stop in "Active apps" on Android 13**

As Task 8, Step 9: Kestrel is stopped as the Task Manager's Stop does, and the restore must end the goal. Kestrel was last installed by Step 4's connected tests (Gradle installs the app again), many minutes before the stop, so the stop is outside Android 13's minute around an update; the step prints Kestrel's `lastUpdateTime` beside the exit record's time, so that the note shows it. The step ends Step 6's conversation first, as Task 8, Step 9 does, and binds Kestrel's accessibility service again with `Connect-AccessibilityService`, as Task 8, Step 9 does, with the accessibility setting as it found it at its start, which must be Step 2's. As there, Kestrel posts its notice that the accessibility service is off at the restart (spec 14.6; on Android 13 also after a stop that cannot be told from an update), the step reads the notices 12 s after the restart, and again for up to 10 s after `bound: True`.

Run, after the step helpers: `Assert-TestDevice -Serial $s; $services = "$(& $adb -s $s shell settings get secure enabled_accessibility_services)".Trim(); if (($services -in '', 'null') -or -not (Test-ServiceListed -Services $services -Component $KestrelAccessibilityService)) { Write-Error "The accessibility setting is empty or lacks Kestrel's service, so the step could not write it back: put the A71 back with Step 8 and ask the controller" -ErrorAction Stop }; "accessibility before: $services"; & $adb -s $s shell am start -W -n dev.kestrel/dev.kestrel.app.MainActivity; Start-Sleep -Seconds 3; StandIn 120; Listen; & $adb -s $s shell input keyevent KEYCODE_HOME; Start-Sleep -Seconds 2; Hook 'CONVERSATION_CONTROL --es do expire'; Start-Sleep -Seconds 2; Heard 'Kestrel, check the owner''s stop'; Start-Sleep -Seconds 3; Hook 'CONVERSATION_STATE'; & $adb -s $s shell cmd activity stop-app dev.kestrel; Start-Sleep -Seconds 3; $exit = "$(& $adb -s $s shell dumpsys activity exit-info dev.kestrel)"; $reason = [regex]::Match($exit, 'reason=\d+ \([^)]*\)').Value; $subreason = [regex]::Match($exit, 'subreason=\d+ \([^)]*\)').Value; "exit: $reason $subreason"; "exit time: $([regex]::Match($exit, 'timestamp=\S+ \S+').Value)"; "updated: $("$(@(& $adb -s $s shell dumpsys package dev.kestrel | Select-String 'lastUpdateTime=') | Select-Object -First 1)".Trim())"; & $adb -s $s shell am start -W -n dev.kestrel/dev.kestrel.app.MainActivity; Start-Sleep -Seconds 12; & $adb -s $s shell input keyevent KEYCODE_HOME; Hook 'CONVERSATION_CONTROL --es do restore-check'; Hook 'CONVERSATION_STATE'; Hook 'GOAL_SERVICE_STATE'; "mode: $(Mode)"; Hook 'NOTICES'; "after the stop: $(Format-ServiceState (Get-AccessibilityServiceState -Serial $s))"; "bound: $(Connect-AccessibilityService -Serial $s -Services $services)"; foreach ($try in 1..10) { $notices = Hook 'NOTICES'; if ($notices -match 'notices=0 kinds=none') { break }; Start-Sleep -Seconds 1 }; $notices`
Expected, in order: `accessibility before:` and the setting of Step 2; `stand-in: 120 s`; `mode: listening`; `expire=sent`; `goal=acting` and `commands=1`; `exit: reason=10 (USER REQUESTED)` and the sub-reason that Android 13 gives (note both); the exit record's time and `lastUpdateTime=`, more than a minute apart (note both); `restored=goal-ended`; `goal=none` and `commands=1`; `service=stopped notice=none posted=none … starts=0 refusals=0`; `mode: off`; `notices=1 kinds=accessibility`; `after the stop:` and the state (note it; `crashed=True` is expected); `bound: True` (`False` comes with `Connect-AccessibilityService`'s warning: put the A71 back with Step 8 and ask the controller, with the lines); `notices=0 kinds=none`. When `bound: False` comes with the sentence "Android 33 ends a binding only when the bind finishes, or when the app is updated or force stopped; turning the service off does not end it.", Kestrel's bind is stuck: put the A71 back with Step 8 and ask the controller; only the bind's end or a force stop of Kestrel (which the steps do not do) ends it. The crashed case of the owner's stop is recovered on Android 13 as on 17. When the first `NOTICES` answer is not `notices=1 kinds=accessibility`, or the last one is not `notices=0 kinds=none`, put the A71 back with Step 8 and ask the controller, with both answers. When `cmd activity stop-app` is unknown on One UI 5.1, note it, end the goal with `Hook 'GOAL_CONTROL --es do tap --es action cancel'` (expect `tapped=cancel expanded=<value> shade=closed`, and note the value as in Step 5) after the step helpers, and go on with Step 8 (Kestrel was not stopped, so its notices then answer `notices=0 kinds=none` both times, and `bound: True` writes nothing). If the two times are within a minute of each other, the stop cannot be told from the update on Android 13: expect `restored=goal-held` and `mode=off` instead, and still `notices=1 kinds=accessibility` before the bind and `notices=0 kinds=none` after it (the check also runs after such a stop, `ExitReason.UNCLEAR_STOP`), note it, and end the goal with `Hook 'GOAL_CONTROL --es do tap --es action cancel'` (expect `tapped=cancel expanded=<value> shade=closed`, and note the value as in Step 5). If the reason is another one, or `restored=goal-held` with the times more than a minute apart, put the A71 back with Step 8 and ask the controller, with the lines.

- [ ] **Step 8: Put the A71 back**

Run, with the stay-awake setting and the listening mode of Step 2, after the step helpers: `$stayOn = '<the value of Step 2>'; $mode = '<the mode of Step 2>'; pwsh -NoProfile -File scripts\hook.ps1 -Serial $s -Command voice-task -Clear; if ($mode -ne 'off' -and (Mode) -eq 'off') { & $adb -s $s shell am start -W -n dev.kestrel/dev.kestrel.app.MainActivity; Start-Sleep -Seconds 3; Listen }; if ($mode -eq 'paused') { Voice 'pause' | Out-Null; Start-Sleep -Seconds 3 }; if ($mode -eq 'off') { Quiet }; & $adb -s $s shell settings put global stay_on_while_plugged_in $stayOn; & $adb -s $s shell cmd statusbar collapse; & $adb -s $s shell input keyevent KEYCODE_HOME; "stay on: $(& $adb -s $s shell settings get global stay_on_while_plugged_in)"; Assert-TestDevice -Serial $s; "accessibility bound: $(Connect-AccessibilityService -Serial $s -Services '<the accessibility setting of Step 2>')"; "accessibility: $(& $adb -s $s shell settings get secure enabled_accessibility_services)"; $now = Mode; "mode: $now"; if ($now -ne $mode) { Write-Error "The A71's listening mode is $now, not $($mode): ask the controller" -ErrorAction Stop }`
Expected: `stay on:` the value of Step 2; `accessibility bound: True` (it writes nothing when Kestrel's service is already bound under Step 2's setting; `False` comes with `Connect-AccessibilityService`'s warning: ask the controller, with the lines); the accessibility setting of Step 2; `mode:` and the mode of Step 2 (the command stops when it is another one). Step 8 runs whatever ended the task, also after a stop in Steps 3 to 7. Then the data check, the last one of the task, and, when it says `owner data: as before`, `Remove-Item 'H:\Code\kestrel\.superpowers\sdd\plan-7e-owner-data-a71.xml'`.

- [ ] **Step 9: Note the results**

Add to `.superpowers\sdd\progress.md`, under "## Plan 7E": the build's commit on the A71; whether this install moved the A71's database to version 3; whether Kestrel had `POST_NOTIFICATIONS`; the times of Step 4 on the A71 (the write run, the close, the index's read, the first search, the restore and the search right after a start) against design 4.3's 1,000 ms and R3's estimates (and plan 7C's read time, if Step 4 measured it); S9's findings on Android 13 for the spoken start, the start from the notification's Continue and the start at a restart, with the service's `allowStartForeground` and `allowWhileInUse` lines, and the audio focus; design 10.6's finding on Android 13: the `expanded=` of every press of Steps 5 to 7, with its action and step (`none` each time confirms the goal's row on One UI 5.1; another value goes to the controller); and the reason and sub-reason of the owner's stop with the restore's answer, the service's state after the stop (`after the stop:`), `bound:`, and the two `NOTICES` answers of Step 7, before the service was bound again and after it. When every start was allowed on both devices, S9 holds as the design expects; otherwise the controller takes the findings to the owner, with the refused starts and their contexts.

---

## Counts at the end of plan 7E

- `:app` JVM (with `app/src/testDebug`): A₀ + 31 (Task 1c + 1, Task 2 + 10, Task 3 + 9, Task 4 + 3, Task 6 + 4, Task 6's review fix 616f5af3 + 4: `GoalStartRefusalTest` 1, `FocusRequestsTest` 1, `GoalServiceAnswersTest` + 2); 690 with A₀ = 659 at Task 7's end (`9cae294b`). The device-found fixes of Task 8 (amendment of 2026-11-26) add 8: `NoticeButtonsTest` + 4 (2 to 6), `ManagedServiceTest` 2 and `ListeningTest` + 2; so A₀ + 39, 698 at `ecf2a999`. The hook's `expanded=` (decision 16, `d3f679e6`) adds 4: `NoticeButtonsTest` + 4 (6 to 10; `GoalServiceAnswersTest`'s test of the press's answer checks the new form); so A₀ + 43, 702 at `d3f679e6`. The press's outcome fix (`96861f22`, Task 8's second run) adds 1: `NoticeButtonsTest` + 1 (10 to 11, `findAndPress`); so A₀ + 44, 703 at `96861f22`. The notification IDs (`415ed6bd`, Task 8's third run) add 1: `KestrelNotificationTest.everyKindOfKestrelsNotificationsHasAnIdOfItsOwn`; so A₀ + 45, 704 at `7aa15be0` (`:app:testDebugUnitTest`, 0 failures). The unbound-service notice (Task 8's fourth run) adds 7: `4cdc50ca` adds `UnboundServiceCheckTest` 4 (708), and `7a491675` adds `UnboundServiceCheckTest` + 2 (6 in all) and `ExitRecordsTest.aRecordThatAndroidCannotGiveIsReportedAndCountsAsNone` (moved from `ProcessEndsTest`'s one test, which keeps its other checks); `66253fc3` and `55de7dfd` add none; so A₀ + 52, 711 at `7a491675` and at `55de7dfd` (`:app:testDebugUnitTest`, 0 failures, 0 skipped). The whole-plan review's fixes (amendment of 2026-11-26, branch `fix-7e-whole`): I1 and M4 add `ExitRecordsTest` + 2 (713); I2 adds `GoalStartRefusalTest` + 1, which moved to `app\src\testDebug` (714); M2, M3 and a nit add `GoalForegroundTest` + 1 and `GoalNoticesTest` + 1 (716); M8 adds `ManagedServiceTest` + 1 (3 in all; 717).
- `:app` device tests: AD₀ + 13 (`GoalServiceTest` 10, `GoalServiceHooksTest` 2, `ConversationSearchSpeedTest` 1, which skips itself without its argument; the review fixes of Tasks 6 and 7 add none: `kestrelSearchSpeed=clean` is an argument's value, not a test; the device-found fixes add `GoalServiceTest.withListeningOnTheShadeShowsTheHeldGoalsCancelAndContinueWithoutExpandingAnything`); 468 with AD₀ = 455, counted in the source at `ecf2a999` (`@Test` in `app\src\androidTest`; 467 at `9cae294b`). The notification IDs (`415ed6bd`) add `GoalServiceTest.aRefusedStartHoldsTheGoalAndAndroidKeepsShowingItsNoticeWithCancelAndContinue`, so `GoalServiceTest` has 11: AD₀ + 14, 469 at `7aa15be0`, counted in the source. Task 8's fourth run adds no device test (a stop of the app would end the instrumentation's process too, so Task 8's Step 9 checks the notice with the hook `NOTICES`): 469 at `55de7dfd`, counted in the source.
- `:core:conversation`: C₀ + 9 (Task 1: `GoalRulesTest` + 1, `ConversationEngineTest` + 2; Task 1b: `ScreenTakingGoalTest` 2, `ConversationEngineTest` + 2; Task 1c: `GoalRunnerTest` + 1, `ConversationEngineTest` + 1) as the tasks are written, and C₀ + 11 as built: Task 1b's review fix 745b7f96 adds `GoalRunnerTest.aPauseJustAfterAGateLetTheRunThroughLetsGoOfTheTurnByTheNextModelCall`, and Task 3's review fix cbb90859 adds `ConversationEngineTest.androidsRefusalDuringAHandOverClosesItAndHoldsTheGoalUntilContinue`; 292 with C₀ = 281, plan 7D's count at its end, which includes plan 7B's restore cases for the owner's stop; measured again at `ecf2a999`: 292 (the device-found fixes change no file of `:core:conversation`). The whole-plan review's M5 adds `GoalRunnerTest.anOlderRoundsLateEndLeavesTheNewerRoundsTurnToThePause`: 293. `:core:agent`: G₀ + 2 (Task 1b: `ScreenTurnTest` + 2; Task 1b's review fix changed `ScreenTurnTest` but added no test); 315 with G₀ = 313, as built, and measured again at `ecf2a999`: 315. `:core:voice`, which this plan's tasks do not change and so did not count: the device-found fixes add 8 (`MicrophoneThreadTest` 6, `RecordFramesTest` 2), 171 at `9cae294b` and 179 at `ecf2a999`. `:tools:eval`: unchanged. The Pester tests: this plan's tasks change no script; the device-found fixes change `scripts\start-emulator.ps1`, `scripts\lib\Kestrel.ps1`, `scripts\lib\Session.ps1`, `scripts\listen-check.ps1`, `scripts\a71-agent.ps1`, `scripts\a71-commands.ps1` and `scripts\a71-session.ps1`, with their Pester cases (`scripts\test-scripts.ps1` at `560a88ca`: Windows PowerShell 521 passed and 1 skipped, PowerShell 7 522 passed, PSScriptAnalyzer 0 problems).

## Interfaces for 7F and 7G

What plans 7F and 7G consume, as this plan leaves Kestrel. Every type of `:app` is `internal` unless it says otherwise.

**The goal service (plans 7F and 7G)**
- `AppGraph.goalForeground: GoalForeground` (package `dev.kestrel.app.goal`): `suspend fun <T> whileSpeaking(block: suspend () -> T): T` adds `mediaPlayback` to the goal's running service from Android 14 on and waits up to `GoalForeground.APPLY_MILLIS` (1 s) until Android applied it. Plan 7F's speech of a typed command's reply with listening off ("Speak replies to typed commands", design 11) runs inside it, and loads the voice engine for that only (design 10.6). The service runs for an open goal and until its final reply has been said, so a goal's spoken report keeps it; a local request's typed reply with listening off is spoken while `ConversationActivity` is in front, where Android allows audio without a service. Whether Android 17 asks for the type or only for a service with while-in-use is S9's finding of Task 8, which plan 7F reads before it relies on either.
- `GoalForeground.status(): GoalServiceStatus` (`service`, `notice`, `posted`, `types`, `applied`, `starts`, `refusals`, and `counting: PlacedNotice?`, the one rule for the notice whose buttons count: the service's, else the one posted without it, with its `NoticePlace`), `GoalForeground.shown: StateFlow<Shown?>`, `GoalForeground.posted: StateFlow<GoalNotice?>` and `GoalForeground.notice()` (`counting`'s notice); `GoalService.runningTypes(): Set<ForegroundType>?`; `NoticePlace.SERVICE` and `NoticePlace.POSTED`, with their notification IDs (`id`: 7106 and 7107, taken from `KestrelNotification` since `415ed6bd`; 7102 and 7103 as Task 5 was written, which collided with the problem notices; the whole-plan review's M2 and M3 replaced `GoalNotification.SERVICE_ID` and `POSTED_ID` with them) and `foreground` (only the service's notification is colorized and shown at once; the posted one is plain); `GoalNotification.CHANNEL`, `GoalNotification.build(context, notice, place)`, `post`, `intentOf(context, action, goal: NoticeGoal)` (the goal's key in the broadcast's data, so each goal's buttons have PendingIntents of their own, never updated) and `goalOf(intent)`; `AppGraph.goalStartGate: (() -> Unit)?`, which every start of the goal service passes first and which is null in a release build, and the debug seam `GoalStartRefusal.of(graph)` (package `dev.kestrel.app.debug`, in `app\src\debug`; `arm()`, `take(): Boolean`, `armed`; in memory only, so a new process has none; Task 6's review fix), which installs itself as that gate, which only `goal-control do=refuse` arms and `do=refuse-off` takes back; the goal service's start takes it, so one arm refuses exactly one start. Device tests reach it as `GoalStartRefusal.of(graph)` (the whole-plan review's I2 moved it out of `app\src\main`).
- The goal notification's tap and its Open use `ListeningService.openKestrel(context)`: plan 7F points it at `ConversationActivity` when that becomes the launcher, for both notifications.
- `GoalTexts` (`WORKING`, `BEHIND_COMMAND`, `FINISHED`, `PUBLIC`, `STOP`, `OPEN`, `CONTINUE`, `CANCEL`), `GoalAction` (`label`, `intent`, `resumes`, `stops`, `broadcast`, `ofBroadcast`), `GoalNotice` (with `goal: NoticeGoal?`; a notice has buttons only when it names its goal, which an open goal always does), `NoticeGoal(conversation, record)` with `key` and `ofKey`, `GoalNotices.of(view)`, `NoticeKind`, `NoticeProgress`. A held goal's buttons are Cancel, then Continue, and Continue asks for the owner's unlock: plan 7F's question card and conversation screen follow the same order and the same rule for a button that resumes a goal. While the working goal's round waits behind the owner's instant command (`ConversationView.waitsBehindCommand`), the notice's text is `GoalTexts.BEHIND_COMMAND` in place of the current item.
- `GoalButtons.input(broadcast, goal, shown, channel, number, atMillis): OwnerInput?` and `suspend fun GoalButtons.press(broadcast, goal, restored, number, atMillis, report): OwnerInput?` (`number` and `atMillis` are the tap's own, taken as its broadcast arrives) (it waits at most `GoalButtons.RESTORE_WAIT_MILLIS`, 5 s, and drops a tap after a slower restore with a report of its kind): a tap counts only for the goal that its notice showed (`NoticeGoal`, the conversation's id and the goal's record, which outlive a resume and Kestrel's process), a button that stops while the goal's notice offers any way to stop, and Continue only while it offers Continue; a tap that counts is the owner's input of its moment, with the next number of his inputs (`InputIds::next`). `press` decides after `ConversationEngine.awaitRestored()`, against the restored view's notice, so a tap that started Kestrel's process is not lost; `GoalActionReceiver` runs it under `goAsync`. Plan 7F's screen buttons that resume or stop a goal give their inputs the same way (the next number, for the goal that the screen shows), so that plan 7D's `ScreenTurns` treats each tap as the owner's newest act.

**The conversation (plan 7F)**
- `ConversationEngine.backgroundRefused(command)` and `ConversationEvent.BackgroundRefused(command)` (the machine's rule is `:core:conversation`'s internal `GoalRules.backgroundRefused`, which no other module calls); `Interruption.BACKGROUND_REFUSED` (`:core:agent`) and `GoalHold.BACKGROUND_REFUSED` (`GoalPhase.INTERRUPTED`, resumed as `ResumeCause.INTERRUPTION`); `ConversationTexts.BACKGROUND_REFUSED` and `ConversationTexts.CONTINUE_IN_NOTIFICATION` ("Tap Continue in Kestrel's notification."), which plan 7F changes to design 10.6's "Open Kestrel and tap Continue." once its conversation screen has Continue. `GoalRules.backgroundRefused`, `GoalNotices` and the tests of `:core:conversation` read it through `ConversationTexts.held(GoalHold.BACKGROUND_REFUSED)`, so plan 7F's change needs no edit elsewhere in `:core:conversation`; only `GoalNoticesTest` (Task 2) names the constant.
- `ConversationView.hold: GoalHold?` and `ConversationView.goalEnding: GoalEnding?`: plan 7F's conversation screen shows a held goal's way to go on from the hold, as the notification does, beside its Cancel and Continue. `ConversationView.goalRecord: Int?`: with `id`, the goal's name across its resumes and Kestrel's processes, which plan 7F's screen buttons carry as the notification's do. `suspend fun ConversationEngine.awaitRestored()`: returns once `restore()` has run, for an input that may have started Kestrel's process.
- `ConversationView.waitsBehindCommand: Boolean` (the view's last field): true while the open goal works and its round waits behind the owner's instant command, which goes on, the owner's newest act; plan 7F's conversation screen may show it, as the notification does, because the bar belongs to the command then. It comes from plan 7D's screen turn, through public API of `:core:conversation`: `ScreenTurn.take(after: Long?, waiting: () -> Unit): Boolean` (`waiting` hears each start of such a wait; a command that the round asked to let go is none), which `:app`'s `ScreenTurns` implements, and `GoalListener.waitsBehindCommand(waits: Boolean)`; plan 7F reads only the view's field. `FakeScreenTurn.command` (`:core:conversation`'s fixtures) makes the next take wait behind a command in a JVM test.
- A pause lets go of the round's screen turn once no step that a gate let through can act (from the run's take of the turn until its next model call or its next wait for the owner), and at once during a model call; a resume before then keeps the turn. The engine does it through the public `GoalRunner.paused()` (with every `ConversationEffect.PauseGoal`) and `GoalRunner.resumed()` (with every `ResumePausedGoal`). The rule's class (`ScreenTakingGoal`) and the crashed app's gated Back (`FrontCheck`) are internal to `:core:conversation` and `:core:agent`, and plans 7F and 7G use neither: a typed or card resume goes through the machine's `ResumePausedGoal`, so it needs nothing of its own. The conversation-state hook's line is unchanged.
- Plan 7B's `ProcessEnd` (`OwnerStopped(atWall)`, `Other`), `ConversationEngine.restore(previous: ProcessEnd)`; this plan's `ExitReason.UNCLEAR_STOP`, `exitReasonOf(reason, timestamp, sdk, updatedAt)`, `processEndOf(record)` and `AppGraph.previousExit: PreviousExit` (`suspend fun get(): ExitRecord?`, the process's one reading of the newest exit record, which listening's restore, the conversation's restore through `processEndOf` and the notice after the owner's stop all take; and `launchEach(scope, uses)`, which starts each use in a coroutine of its own). `7a491675` replaced Task 4's `readProcessEnd` and `AppGraph.previousProcessEnd()` with `readNewestExit` and `previousExit()`, and the whole-plan review's fix of I1 replaced those with `PreviousExit`.

**The checks (plans 7F and 7G)**
- The hooks `dev.kestrel.hook.GOAL_SERVICE_STATE` (answer: `service=… notice=… posted=… actions=… progress=… types=… applied=… android=… androidTypes=… starts=<n> refusals=<n> refuse=armed|none focus=… focusBy=… focusTypes=… focusRequests=<n> focusAnswered=<n>`) and `dev.kestrel.hook.GOAL_CONTROL --es do tap --es action stop|open|continue|cancel` (`tapped=<action> expanded=none|row|group|group,row shade=closed|open`; `expanded=` names the collapsed rows that the hook asked to expand before its press, in their order, each once, `group` for the group that Android put the notification in and `row` for the notification's own row, and `none` when the row showed its buttons without any, as design 10.6 asks (decision 16); its errors end with `; shade=closed|open`; the hook asks to close the shade however the press ends, and says `shade=closed` only when the screen shows it closed, and `shade=open` also when it cannot tell, such as on a locked phone whose open shade showed no Quick Settings tile that `systemui-parts.json` knows, after which the caller collapses it; before its press it expands a collapsed group of Kestrel's notifications and then the goal's own row, and only SystemUI's notification rows, never another of SystemUI's containers), `--es do touch` (`touch=sent`) `--es do speak|focus` (`speak=sent`, `focus=sent`), `--es do refuse` (`refuse=armed`: the next start is refused, once) and `--es do refuse-off` (`refuse=disarmed` when it took back an armed refusal, else `refuse=none`) and `--es do send-later --es action stop|continue|cancel` (`send=scheduled`: the button's own PendingIntent on Kestrel's goal notification, sent by `AlarmManager` 5 s later; or `send=none`). Plan 7G adds them to `scripts\hook.ps1` as `goal-service-state` and `goal-control -Do tap|touch|speak|focus|refuse|refuse-off|send-later [-Action …]`, with Pester cases in `scripts\test-scripts.ps1`.
- Plan 7F's typed start check (design 10.6 and 15.2): with `ConversationActivity` in front and listening off, a typed command (plan 7F's `conversation-type`, or the screen itself), then `GOAL_SERVICE_STATE` expects `service=running … starts=1 refusals=0`, with the service's `allowStartForeground` and `allowWhileInUse` lines and a `do=speak` after the screen went to the back; on kestrel-api37 as Task 8, Step 7 does, and on the A71 as Task 9, Step 5 does, together with design 15.2's typed goal with listening off.
- Plan 7G's tasks `interrupt-continue` and `resume-after-death` may use `GOAL_CONTROL --es do touch` or a real tap, `crash`, `CONVERSATION_CONTROL --es do restore-check` and `GOAL_CONTROL --es do tap --es action continue`, as Task 8, Steps 7 and 8, do; `resume-after-death` carries design 15.2's check that a yes after a restart continues from the checklist with a fresh observation. An interruption by Android's refusal ends a round as `end=INTERRUPTED`, as a touch does.
- `ConversationSearchSpeedTest` with `-Pandroid.testInstrumentationRunnerArguments.kestrelSearchSpeed=write`, then `=search`, then `=start`, and `=clean` after them however they ended (it only deletes the test database and key), logging `wrote 300 conversations in <s> s`, `closed a conversation in <ms> ms`, `read the index in <ms> ms`, `searched 300 conversations in <ms> ms`, `restored the open conversation in <ms> ms` and `searched right after a start in <ms> ms` under `KestrelSearch`.
- `GoalServiceTest`'s helpers (`press(label)` sends a button's PendingIntent; `awaitShown` reads the notification's title, text and buttons) are the pattern for plan 7F's device tests of the notification beside the screen.

**The device-found fixes of Task 8 (plans 7F and 7G; amendment of 2026-11-26)**
- `NotificationGroups.alone(id)` (package `dev.kestrel.app`): the goal service's notification, the held goal's posted one and listening's notification are each an app group of their own, without a summary, so that Android does not put them in one collapsed group; a new ongoing notification of Kestrel's (plan 7F's, if any) takes a group of its own the same way. The goal service's notification is colorized in `R.color.kestrel` (`#FF9C4A1A`, which the launcher icon's background uses too), so that SystemUI shows it first and expanded (design 10.6).
- `ScreenServiceRule` (`app\src\androidTest`) turns Kestrel's accessibility service off and waits until Android's accessibility manager lists it neither as enabled, nor as binding, nor as crashed, for the current user (`ManagedService`, in `app\src\debug`, which reads `dumpsys accessibility` by the rule of the scripts' `ConvertFrom-AccessibilityDump`, tested with the same sample dumps in `app\src\testDebug\resources\accessibility-dumps`), and until its session has ended, then on; when the service does not connect while the manager still binds it or holds it as crashed, the rule turns it off and on once more and logs why. Plans 7F's and 7G's device tests that need a fresh session use the rule as it is.
- `Microphone.stop()` (`:core:voice`) waits at most 2 s for the microphone's thread (`MicrophoneThread`) and then releases the recording; while a thread that did not end in time still runs, a start throws `MicrophoneBusyException`, which `Listening` hands to its restart rule (it tries once more after 1 s). Spec 8.1 of the main spec says so.
- kestrel-api37 runs without a window (`scripts\start-emulator.ps1`; `-Window` for a step in which the owner works in its window, and `Restart-KestrelEmulator` in `scripts\lib\Kestrel.ps1` for a script that switches); a check of the emulator's name uses `Test-KestrelAvdAnswer`, which requires the exact name.
- `KestrelNotification` (package `dev.kestrel.app`, `415ed6bd`): every kind of Kestrel's notifications, each with its own `id`, 7101 plus its position (listening 7101, the problem notices 7102 to 7104, sign-in 7105, the goal service's 7106, the held goal's posted one 7107). A new notification of plans 7F and 7G is a new kind at the end of the enum and takes its ID from there, never a number of its own; `KestrelNotificationTest` checks that no two kinds share an ID. `scripts\a71-session.ps1` reads 7104.

**Task 8's fourth run (plans 7F and 7G; amendment of 2026-11-26)**
- `AccessibilitySettings` (package `dev.kestrel.app`, `7a491675`): the one reading of Kestrel's accessibility service: `running(context)` (the services that Android runs, not those that it holds as crashed or still binds), `names(context, name, component)` (whether a secure setting's list of components names one), `kestrelComponent(context)` (Kestrel's service's component, which the targets' reads use too) and `kestrel(context, running)`, an `AccessibilityServiceState(enabled, running)`, which `AccessibilitySettings.kt` declares beside them (package `dev.kestrel.app`; the whole-plan review's M7 moved it out of `listen`). `SetupStatusReader` reads `serviceOn`, `serviceEnabled` and the shortcut and button targets through it, so plan 7F's settings pages, which read `SetupStatusReader`, and any screen of plan 7F that shows whether the service runs read the same state as the notice, never a reading of their own. The notice after the owner's stop (`UnboundServiceCheck`, started by `KestrelApplication`) is internal to the start of the process and needs nothing from plans 7F and 7G; its notice is `KestrelNotification.PROBLEM_ACCESSIBILITY` (7103), titled "Kestrel's accessibility service stopped", and `onServiceConnected` cancels it as before.
- For plan 7G's scripts: `scripts\lib\Kestrel.ps1` has `$KestrelAccessibilityService` (Kestrel's component), `Connect-AccessibilityService -Serial -Services` (puts the list back and waits up to 30 s until Android has bound Kestrel's service; it turns the service off first, through an off that Android applied, only when Android holds it as crashed or binding, which a write of the same list never ends, or when the list already names it and Android does not run it; below API 34 it waits for a binding instead), `Disconnect-AccessibilityService -Serial` (takes the service out of the list and waits until Android lists it neither as enabled, binding nor crashed, and it is unbound), the reads `Get-AccessibilityServiceState`, `Format-ServiceState` and `Test-ServiceListed`, `Restore-AccessibilitySettings` (which puts a list that names Kestrel's service back through `Connect-AccessibilityService`, and the targets that Android cleared after it) and `Get-AccessibilityRestoreCommands` (the paste-ready commands, which take Kestrel's service out of the list and wait before they put it back). Each needs `Assert-TestDevice` for the serial first, and none throws when the device does not answer: each warns and returns `$false`. A script of plan 7G that turns Kestrel's accessibility service on or off, or puts the list back after a stop or a crash of Kestrel, uses them, never a plain `settings put` and a sleep.

## Decisions for the controller

1. **Task 1 changes `:core:agent` and `:core:conversation`, and plan 7B carries the restore's `ProcessEnd`.** Design 10.6's refused start needs a cause of its own (`Interruption.BACKGROUND_REFUSED`, `GoalHold.BACKGROUND_REFUSED`), the machine's rule and the engine's way in with the refused command, and the view's `hold` and `goalEnding`; the owner's stop needs the restore's `ProcessEnd`. Recommendation: accept; the alternatives (reusing the touch's interruption, or a hold kept only in `:app`) would give a wrong sentence or a second owner of the goal's state.
2. **The typed start and the A71's typed goal with listening off move to plan 7F.** `ConversationActivity` and `TextChannel` are plan 7F's, so plan 7E cannot start a goal from them. Recommendation: plan 7F's last tasks carry design 10.6's typed start on kestrel-api37 and the A71, with this plan's hooks, and design 15.2's typed goal with listening off on the A71.
3. **Plan 7E measures the whole first search, a close and a search right after a start** (plan 7C's "Left open" and design R3), with R3's fixture of 30 heavy days, in three runs so that each timed part runs in a new process. Recommendation: yes, so that design 4.3's 1 s is judged on the whole search after a start, not only the archive's read; when either search takes more than 1 s on the A71 at 30 days, the controller takes it to the owner.
4. **Every start or resume of a goal's work starts the service again, also while it runs.** This is how design 10.6's three starts come from the owner's own contexts, and how a running service may gain while-in-use from the owner's action, which Android 17 asks of background audio (main spec B5); Task 8 records the standing that Android gave each start. Recommendation: accept.
5. **The service stays until the goal's final reply has been said**, showing "Kestrel finished the goal" without buttons, also for hours when the report waits for the owner's return while the phone is locked. Recommendation: accept; otherwise a typed goal's spoken report with listening off would lose its service on Android 17.
6. **The notification's buttons keep the conversation's reply channel, and count only for the goal that their notice showed**: each goal's buttons have PendingIntents of their own (the goal's key in the broadcast's data), so a newer goal's notice never rewrites an older button; Continue counts only while the goal's notice offers it, a stopping button while it offers any way to stop (both plan reviews: I2, M2 and R2-I1). Recommendation: accept.
7. **The call pause test needs `READ_PHONE_STATE` for one run on kestrel-api37** (Task 8, Step 5 gives it when it is missing, takes it back and compares the permission's line with its flags). Recommendation: accept; without it the call pause with listening off would have only JVM tests.
8. **Task 9 installs this build on the A71**, which moves the A71's database to version 3 when no earlier plan did, so that from then on only builds with plan 7C may be installed there. Recommendation: accept, and tell the owner at the sitting.
9. **The debug hooks `goal-control do=touch|speak|focus`** give the conversation a touch without touching the A71's screen, and ask for the audio focus outside the instrumentation. Recommendation: accept; the device tests check the real touch watcher (the pull of the shade, and sub-project 5's tests).
10. **`scripts\hook.ps1` learns this plan's hooks in plan 7G**, as plan 7D's. Recommendation: accept; until then Tasks 8 and 9 send them with `am broadcast`.
11. **Spike S9's outcome in the main spec.** Recommendation: after Task 9, the controller writes S9's findings on both devices into the main spec's section 19 and B5, and into design 10.6 where they change its sentence on `mediaPlayback`, in a documentation commit; when a start was refused, the owner first decides what follows.
12. **The goal notification that the owner swipes away comes back with the next change of the goal**, as the listening notification does, not at once. Recommendation: accept; posting it again at once would undo the owner's own swipe, and a held goal keeps "Kestrel, continue" and, from plan 7F, the conversation screen. The held goal's notification that Kestrel posts without the service also outlives Kestrel's process when Android ends it: the next start removes it (the posted notice is empty at every start), and a tap on it before then starts Kestrel's process, whose receiver waits for the conversation's restore, at most 5 s, and counts the tap for the restored goal, which has the same conversation and record (the reviews' M3 and R2-M1); Task 8, Step 8a checks it on kestrel-api37, with a start refused through the debug hook `goal-control do=refuse`, the posted notification's own Cancel handed to `AlarmManager` by `goal-control do=send-later`, the process ended before the alarm sends it, and Kestrel's accessibility service and notification access off meanwhile, so that only the button's broadcast starts the new process.

13. **The wait behind the owner's command is a field of the conversation's view (amendment of 2026-11-25).** The round's screen turn reports it through `ScreenTurn.take`'s `waiting`, the goal's listener, and the engine, which carries it as it carries the round's step, so that the notification, plan 7F's screen and any later reader take it from the one view. The other form, a flow of `:app`'s `ScreenTurns` that the notification combines with the view, would put a goal's state beside the view and need each reader to combine it again. The notification says "Waiting for your command to finish" in place of the current item; `conversation-state` and `goal-service-state` keep their lines. Recommendation: accept.
14. **A pause lets go of the screen's turn once no step can act (amendment of 2026-11-25; plan 7D's re-review, R5-1; settled by the controller).** The controller ruled out a release while an action that a gate guarded may still run. `ScreenTakingGoal` keeps the span from the run's take of the turn to its next model call or its next wait for the owner; a pause in that span lets go only at its end, and a pause during a model call lets go at once. It needs no change of `:core:agent`'s ports: the goal port's `stepStarted` already comes right before every model call, after the turn's gate, the observation and the steering, and `releaseScreen` as every wait for the owner starts. The span is wider than the action alone (it also covers the observation after it and the next turn's steering), which keeps the turn a little longer than needed, never shorter.
15. **Each notification button is the owner's input of its moment, for its own goal (amendment of 2026-11-25, with both plan reviews).** `GoalButtons.input` takes the next `InputIds` number at the tap, as plan 7D's interfaces ask of an owner's act that resumes a goal, only for the goal that the notice showed (each goal's buttons have PendingIntents of their own), Continue only while the goal's notice offers Continue, a stopping button while it offers any way to stop; a tap that starts Kestrel's process waits for the restore, at most 5 s. Recommendation: accept; plan 7F's screen buttons follow the same rule.
16. **Design 10.6's goal row on the A71 (amendment of 2026-11-26; settled: the hook reports its expansion).** Design 10.6 says that the goal service's notification is first in the shade and expanded, with its buttons, and names the A71's run as the check on Android 13. Task 9 presses the buttons through `goal-control do=tap`, which expands a collapsed group or row first; as first written, its answer did not say whether it did, so a working press could not show design 10.6's row. The controller decided that the hook reports what it had to expand: since `d3f679e6`, the answer is `tapped=<action> expanded=none|row|group|group,row shade=closed|open` (`NoticeButtons.find` and `NoticeButtons.collapsedRow`, with JVM tests for each case). Task 8 expects `expanded=none` at every press on kestrel-api37 and stops at any other value; Task 9 records the A71's value at every press as design 10.6's finding on Android 13, where `expanded=none` is the expected result after the explicit-group fix (`NotificationGroups.alone`), and another value goes to the controller without stopping the task. `GoalServiceHooksTest` expects `expanded=none` too.

## Self-review

- **Spec coverage.** Design 10.6: `GoalService` for every open goal in every phase with listening on or off (D19; Tasks 3 and 5, `GoalServiceTest.aGoalRunsTheServiceWithListeningOnAndOffUntilItsFinalReply`); the notification on the channel "Goals" with "Kestrel is working", the current item, the progress bar, Stop and Open, and with a held goal "Continue the goal?" with Cancel and Continue, the lock screen's "Kestrel is working on a task" (Tasks 2 and 5); the goal's buttons in the shade without any expansion, through the notification's own app group and its colour (the device-found fixes of Task 8, `GoalServiceTest.withListeningOnTheShadeShowsTheHeldGoalsCancelAndContinueWithoutExpandingAnything`; on Android 13, decision 16); the held goal's notice, which stays with Cancel and Continue while Kestrel's accessibility service goes off, because every kind of Kestrel's notifications has its own ID (Task 8's third run, `KestrelNotificationTest` and `GoalServiceTest.aRefusedStartHoldsTheGoalAndAndroidKeepsShowingItsNoticeWithCancelAndContinue`); its buttons through a receiver that only Kestrel's notification reaches, each goal's buttons with PendingIntents of their own and counted only for that goal (Continue while its notice offers Continue, a stopping button while it offers any way to stop), after the restore when a tap starts Kestrel's process, Continue only after the owner's unlock, no `RemoteInput` (Tasks 2 and 5; Task 8, Step 8a); a tap opens Kestrel's screen, and while the goal acts the pull of the shade interrupts it (`aPullOfTheShadeWhileTheGoalActsIsATouchThatHoldsTheGoal`); `specialUse` with its property and permission, the Android 13 path, `mediaPlayback` while speaking (Tasks 3 and 5), and Android 17's background audio checked outside the instrumentation (Tasks 6 and 8); the three starts and the refusal's sentence and hold, with the held goal posted without the service (Tasks 1, 3, 5, 8 and 9; the typed start in plan 7F, decision 2). Design 6.5 and 14.4: the lock and call pauses with listening off ask "Continue the goal?", answered from the notification (Task 5's lock and call tests; plan 7D's `ConversationSignals`). Design 6.7 and 15.2: "Continue the goal?" after a restart and its Continue (Task 8, Step 8; Task 9, Step 6), the checklist's continuation in plan 7G. The owner's decision on "Active apps": plan 7B's `ProcessEnd`, Task 4, Task 8, Step 9 and Task 9, Step 7. Design 15.2's A71 list: the three starts (Task 9; typed in 7F) and the first search (Tasks 7 and 9). R2: Tasks 8 and 9 note S9's findings, with the exemption that Android used. Main spec 8.1 (the device-found fixes of Task 8): a stop of the microphone waits at most 2 s and always releases the recording, and a start that a lingering thread refuses goes through listening's restart rule (`MicrophoneThreadTest`, `RecordFramesTest`, `ListeningTest.aStartThatAnEarlierMicrophoneThreadRefusesIsTriedAgainAfterASecondAndListeningComesBack` and `aStartThatAnEarlierMicrophoneThreadRefusesAgainAfterTheRetryTurnsKestrelOff`).
- **The first review of the plan as written (2026-11-25, before the amendment).** M1: the hook's `speak` and `focus` with the control after the goal's end and the audio service's lines (Tasks 6 and 8), the device test that says what it checks, and the comments on while-in-use. I1: Cancel before Continue. I2: Continue asks for the unlock. I3: the press by key with its guard. I4: plan 7B's `ProcessEnd` and Task 4. I5: a stopped service shows nothing, and the posted notice. I6: separate runs, each a new process (now three: write, search, start). Minor 1: a refused new start leaves a running service. Minor 2: the refusal names its command, and the receiver checks the button. Minor 3: `id=`, `credential=present`, the mode and the permission's line. Minor 4: the wait for the new process and the binding, and the exemption's lines. Minor 5: `POST_NOTIFICATIONS` read on the A71, and the swiped notification (decision 12). Minor 6: the refusal's sentence names the notification's Continue, which exists. Minor 7: the checklist's continuation in plan 7G, and the pull of the shade. Minor 8: "Kestrel finished the goal". Minor 9: the waits for the starts and the service, and the mode after the crash.
- **The rebase of 2026-11-25 (plan 7B's Task 7 as built, and the archive's index in shards).** Task 1 follows plan 7B's `GoalRules` (`ending(...)`, `PhoneState.present`, `heldWords`) and `ConversationView.keptForOwner`: a refusal's words for a held goal wait until the owner is there, and the goal service stays while a final reply is kept for him. Task 7 reads `HistoryResult.unavailable`, uses R3's fixture of 30 heavy days, and times a close and a search right after a start besides the first search. After plan 7B's fixes of Task 10 (`25cdca0a`), a refusal's `ConversationEffect.AddLine` names its conversation (`state.openId`), as every record effect does. After plan 7B's whole-plan fix (`a05ea85a`), Android's refusal gets its hold in `GoalHold.of(report)`, plan 7B's one rule for the holding ends, and the view reads `heldReports`.
- **The re-review of 2026-11-25.** N1: one reader of the exit record (`AndroidExitRecords`, built once in the app graph) and one reading of reason 10 for both restores, with `ExitReason.UNCLEAR_STOP` on Android 13 near an update, after which listening stays off (`ListeningTest.kestrelStaysOffAfterAStopThatCannotBeToldFromAnUpdate`) and the goal is held (Task 4). N2: Task 8, Step 9 and Task 9, Step 7 end the earlier conversation first and expect `commands=1`. N3: Task 9, Step 7 prints `lastUpdateTime` beside the exit record's time and names Step 4's install. N4: the minute around an update counts on both sides, with its cases in `ExitRecordsTest`. N5: Task 8, Step 7 records the assistant role's holders, and decision 12 says that the posted notification outlives the process. N6: the amendments are now in plan 7B's Tasks 7 and 10.
- **Placeholders.** Each step has its code or its command; Task 8, Step 10 and Task 9, Step 8 take values that their Steps 1 and 2 printed, which the steps name.
- **Type consistency.** `Interruption.BACKGROUND_REFUSED`, `GoalHold.BACKGROUND_REFUSED`, `ConversationEvent.BackgroundRefused(command)`, `GoalRules.backgroundRefused(state, command)`, `ConversationEngine.backgroundRefused(command)`, `ConversationView.hold` and `goalEnding`, `ConversationTexts.BACKGROUND_REFUSED` and `CONTINUE_IN_NOTIFICATION` (Task 1); `ProcessEnd` and `restore(previous)` (plan 7B) and `ExitReason.UNCLEAR_STOP`, `exitReasonOf`, `processEndOf`, `readProcessEnd` and `AppGraph.previousProcessEnd` (Task 4); `ConversationView.goalRecord`, `waitsBehindCommand` and `ConversationEngine.awaitRestored()` (Tasks 1 and 1c); `GoalNotice(kind, title, text, progress, actions, goal)`, `NoticeGoal(conversation, record)` with `key` and `ofKey`, `NoticeKind`, `NoticeProgress(done, total)`, `GoalAction(label, intent, resumes)` with `stops`, `broadcast` and `ofBroadcast`, `GoalNotices.CLOSING` and `of`, `GoalButtons.input`, `press` and `RESTORE_WAIT_MILLIS` (Task 2); `ForegroundType`, `ServiceState`, `Shown(notice, types)`, `GoalServiceEvent.Viewed(goal, stopping, command, replying, notice)`, `GoalServiceCommand.Start` and `HoldGoal(command)`, `GoalServiceRules.next/shown/posted/types`, `GoalServiceStatus(service, notice, posted, types, applied, starts, refusals)` and `GoalForeground(start, holdGoal, sdk, report)` with `follow`, `started`, `applied`, `failed`, `destroyed`, `whileSpeaking`, `status`, `notice`, `shown` and `posted` (Task 3) are the names of Tasks 5 and 6; `GoalNotification` (`intentOf(context, action, goal)`, `goalOf`), `GoalService.start` and `runningTypes`, and `AppGraph.goalStartRefusals` (Task 5) are the names of Task 6 and the tests; `FocusResult(granted, speaking, types)` and `FocusProbe` (Task 6).
- **Safety.** No file of plan 6BF's safety work changes; the notification carries no words of the owner's off the phone, its lock-screen version hides the item, Continue needs the owner's unlock, its receiver is not exported and acts only for the goal that a button's notice showed and only while that goal's notice offers what the button does, and its buttons give only `Stop` and `Continue`, which carry no words (D8). Task 8 and Task 9 set the stand-in round before every `heard` and Continue, keep listening off across a restart until the stand-in is set again, and compare the owner's data by machine after every step. The hooks answer kinds and counts only, press only Kestrel's own button, by its key, checked again at the press, and send only the PendingIntent that Android holds on Kestrel's own notification; Step 8a taps nothing on the screen.
- **The amendment of 2026-11-25 (plans 7A to 7D as built, at `3d0a1921`).** Task 1's old texts follow plan 7D's `ownRequest`; Tasks 1b and 1c carry plan 7D's two items left to this plan (R5-1, with the controller's rule that a pause never lets go while a step may act, and the wait behind the owner's command), each test-first with its failing run; Tasks 2 and 5 make every notification button an owner's input with its own number; the device tests end their work through plan 7D's `ConversationTests`; Task 7 follows plan 7D's `RoomRecordArchive`; Task 8 follows plan 7D's gate (the data check with `Get-GoogleAccountCount`, a delegated agent's rules, the flaky class); the counts are measured at `3d0a1921`. The first plan review of the amendment (I1 to I3, M1 to M5, nits) and its second review (R2-I1 to R2-I3, R2-M1 to R2-M5, nits; its round 2, R3-I1, R3-M1, R3-M2 and a nit; its round 3, R4-M1) are fixed at their roots (see the amendment's entries). The checker is clean from Tasks 1, 4, 5 and 6, and the two `Select-String` checks of "Before you start" give 17 and 12 matches there.
- **The amendment of 2026-11-26 (the device-found fixes, `dab1e6c2..ecf2a999`).** Task 8's counts and expected results follow the fixes as built (372 run in Step 3, `GoalServiceTest` 9 without the permission and 10 with it), and no expected hook answer changes form; Step 1 checks the headless emulator and the build; the end counts are 698, 468, 292 and 315, with `:core:voice`'s 179 as a note; Task 9's install needs `ecf2a999` and its rule for `shade=open` names the fail-safe; decision 16 leaves design 10.6's row on the A71 to the controller. Every PowerShell command of Tasks 8 and 9 parses in PowerShell 7 and Windows PowerShell 5.1.
- **The amendment for decision 16 (`d3f679e6`).** `goal-control do=tap` says `expanded=none|row|group|group,row`; Task 8 expects `expanded=none` at every press and stops at any other value, Task 9 records the A71's value at every press as design 10.6's finding on Android 13 (`none` expected after the explicit-group fix), decision 16 is settled, and the interfaces and counts follow (`:app` JVM 702).
- **The amendment for Task 8's third run (`415ed6bd`, `7aa15be0`).** Every kind of Kestrel's notifications has its own ID (`KestrelNotification`; the goal's are 7106 and 7107); Task 5's text and the interfaces say so beside the IDs as first written, Tasks 8 and 9 install `7aa15be0`, Task 8's counts follow (373 run in Step 3, `GoalServiceTest` 10 without the permission and 11 with it), and the end counts are 704 and 469. Every PowerShell command of Task 8's Step 1 and Task 9's Step 2 parses in PowerShell 7 and Windows PowerShell 5.1.
- **The amendment for Task 8's fourth run (`b907e8ed..55de7dfd`).** Steps 8a, 9 and 10 of Task 8 and Steps 7 and 8 of Task 9 turn Kestrel's accessibility service off and on only through `Disconnect-AccessibilityService` and `Connect-AccessibilityService`, which wait by condition and bind a service that Android holds as crashed; Step 9 and Task 9's Step 7 expect the unbound-service notice before the bind and none after it, and the notes record both answers; Tasks 8 and 9 install `55de7dfd`; the end counts are 711 and 469; the interfaces follow the renamed reading of the exit record and name `AccessibilitySettings` and the script helpers. Every PowerShell command of Tasks 8 and 9 parses in PowerShell 7 and Windows PowerShell 5.1.
