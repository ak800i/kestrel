# The Voice Path onto the Conversation (Plan 7D) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move Kestrel's voice path onto plan 7B's conversation: the Orchestrator's rules keep only voice and hand every command, answer and stop to `ConversationEngine`; the engine runs goals through `:app`'s `AppGoalEnvironment` and speaks through `VoiceChannel`; `AgentRunner` and `AppOwnerPort` go in the same change that wires the engine, and the voice gets the follow-up window, Kestrel's new requests and the hooks `conversation-state` and `conversation-control`, and the settings page's "Delete history" deletes the past conversations through the conversation.

**Architecture:** Two extractions first, each green on today's task path: `PhoneSignals` shares one screen receiver and one call listener between listening and the conversation, and `ScreenTurns` gives the screen to one actor at a time. Then one atomic change: `Rules` loses every task state, event and effect and keeps the conversation's `ConversationView` in its state, so that it reads the goal and the open question from the view and gives the conversation `InputIntent`s (`Effect.Converse`); `VoiceChannel` is the engine's voice `OwnerChannel` over the Orchestrator (`Event.ChannelSays`, `ChannelAsks`, `ChannelHandsOver`), with the Android parts of today's owner port in `AndroidOwnerSupport`; `AppGoalEnvironment` builds each round's ports as `AppAgentEnvironment` and `AgentRunner` did; `ConversationSignals` turns the phone's lock and calls into the engine's `PhoneSignal`s, with the call backstop; the app graph wires it all, and the debug hooks read the engine. After it, three additions on the new path: the follow-up window, "new conversation" and "continue the conversation about X" with the typed reading of Kestrel's own requests, and the two new hooks. The last task is the controller's regression gate on kestrel-api37.

**Tech Stack:** Kotlin 2.4.21 on Android (minSdk 33, target 37) and on the JVM (JDK 21 from Android Studio, target 17), kotlinx.coroutines 1.11.0 with `kotlinx-coroutines-test`, kotlinx.serialization 1.13.0, JUnit 4.13.2, AndroidX Test (runner 1.7.0, ext-junit 1.3.0).

## Global Constraints

- Package and application ID `dev.kestrel`; licence GPL-3.0-or-later. No new library.
- The owner's hard rule (AGENTS.md): no workarounds and no smallest-change fixes; clean code, good architecture and modularity, even when that means a larger change. The owner's criterion of 2026-11-24: "best = best code quality. Don't worry about time." A step that would patch a symptom at the nearest line is a fault of the plan: stop and ask the controller.
- The approved design is `docs\superpowers\specs\2026-11-24-conversations-design.md` (commit `75dd098b`, with the controller's later changes), called "the design" below; its decisions D1 to D24 bind this plan. This plan is the design's plan 7D (section 16): sections 5.5, 5.6, 6.5 (the voice's and the phone's parts), 6.6, 9.6 and 9.7 (the voice's requests), 11 (the voice channel), 12.2 (the typed reading of Kestrel's own requests), 14.3 and 14.4, and the voice's lines of 15.1. The main spec is `docs\superpowers\specs\2026-11-12-kestrel-design.md`.
- There is never a time with two task paths (design 14.2 item 4): Task 3 deletes `AgentRunner`, `AppOwnerPort`, `AgentEnvironment` and `AppAgentEnvironment` in the same commit that wires `ConversationEngine` into `AppGraph`. Tasks 1 and 2 are extractions that today's task path uses, so each ends green on its own.
- This plan consumes plan 7A (`docs/superpowers/plans/2026-11-24-conversations-7a-agent.md`), plan 7B (`docs/superpowers/plans/2026-11-24-conversations-7b-core.md`, its section "Interfaces for 7C and 7D", its Task 7 table of `RulesTest`'s cases and its "Decisions for the controller", item 10, which the owner settled) and plan 7C (`docs/superpowers/plans/2026-11-24-conversations-7c-archive.md`, committed as `74670594`, its section "Interfaces for plans 7D, 7E and 7F"): `AppGraph.conversationStore` is the one store of conversations, `AndroidConversationClock` the clock, `SettingKeys.CONVERSATIONS` the setting, and `:app` already depends on `:core:conversation`. This plan builds no second store. Plan 7B carries, since `2113f5a0`, the two changes that this plan's review asked of it: a round lets go of the screen before its end reaches the listener, and "delete history" asks with `QuestionKind.DELETE_HISTORY`.
- Decision 10 of plan 7B binds the voice: "turn off", "stop listening" and the listening notification's Stop listening change only the listening mode and give the conversation no input; "voice on" is a listening mode that is not `OFF`; the keyword spotter's "stop" while Kestrel speaks ends the speech and gives a `Stop` input; "stop" and "cancel" as an utterance give a `Stop` input.
- This plan changes `:core:conversation` only in Task 3 (the view's `ownRequest`, and a stop that withdraws the question of Kestrel's own request at once; review of Task 3: M1) and in Task 7 (the settings page's "Delete history") and `:tools:eval` only in Task 3 (its refusal check learns `NO_SCREEN`); every other change is in `:app`, in two scripts' descriptions and in `scripts\lib\AgentChecks.ps1` (review of Task 3: M2).
- Steering by voice needs "Kestrel" (D9): while a goal works, only a command after the name reaches the conversation as a `Command`.
- Never print, log or put into a hook's answer the owner's words, a reply, a question, a record's text or a checklist's text. Every new type's toString prints only kinds, numbers, counts and lengths (design D25 of sub-project 5).
- Run Gradle only through `scripts\gradle.ps1`, one build at a time; when the controller's brief says that agents run in parallel, take `.superpowers\sdd\gradle.lock` in `H:\Code\kestrel` before each Gradle command as the brief says, and do not run `scripts\gradle.ps1 --stop` beside another Gradle user. Otherwise stop Gradle with `scripts\gradle.ps1 --stop` at the end of each task that builds. `scripts\gradle.ps1` refuses every task that installs, uninstalls or runs tests on a device. Install only through `scripts\install.ps1`, run the `:app` device tests only through `scripts\connected-test.ps1`, the instant commands' device suite only through `scripts\command-test.ps1`, and the suite only through `scripts\eval.ps1`.
- Device steps run only in Task 8, which is the controller's. On kestrel-api37 each device command starts with the emulator's check, written without the word throw: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }`. If the check fails, or no emulator runs, start it with `scripts\start-emulator.ps1`, check the serial that it prints, and use that serial. Never use the AVD `zoran`, the S23+, the A71 or the owner's Xiaomi tablet in this plan.
- kestrel-api37 holds the owner's own Google account and Kestrel's Copilot profile: no step uninstalls Kestrel there, clears its data, or signs an account in or out. Task 8's install checks before and after it that `dumpsys account` counts the same Google accounts and that `scripts\hook.ps1 -Serial emulator-5554 -Command provider-standin` answers `profile=copilot`. Since plan 7C, kestrel-api37's database is at version 3: never install a build without plan 7C there.
- Nothing in this plan calls a provider or runs `--live`. Every device test and the suite use the scripted model.
- The JVM tests run on CI's Ubuntu too: no JVM test needs Windows, a device, a font or a network, and no test depends on the machine's time zone.
- The repository uses LF line endings.
- This plan is `.superpowers/sdd/plan-7d.md`; the controller commits it as `docs/superpowers/plans/2026-11-24-conversations-7d-voice.md`. A plan is frozen once committed; a later difference goes into `.superpowers/sdd/progress.md`.
- Every commit message ends with the line `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.

Code comments name this plan "plan 7D" and the design's sections as "design 6.6 of sub-project 7"; design numbers without a sub-project in existing comments stay those of sub-project 5.

## Before you start

- Tasks 1 and 2 are built (`3f19e672` with `32e96e99`, and `0e374a00` with `3f8f4258`); Task 3 starts from `sp3`'s HEAD after them.

- Sub-project 7 starts only after plan 6B-basefix (`docs\superpowers\plans\2026-11-24-eval-6b-basefix.md`) is pushed, and this plan starts only after plans 7A, 7B and 7C are merged into `sp3`. This plan was written against `sp3` at `4c37cca0`, where plan 6B-basefix's code and both baselines are in (`dfa97763`), a task's witness keeps the sentence of its end (`0f90f0d8`), and plans 7A, 7B and 7C are written (plan 7C as `74670594`) but not carried out. After the reviews of 2026-11-24 and 2026-11-25, its checker ran clean against `sp3` at `67a2b01a`, where plan 7A has landed up to its Task 11 (its Task 12 changes no file), with plan 7B as committed there and plan 7C simulated on top; its old texts are the texts that those plans leave. The files that plans 7A and 7C change besides this plan's anchors are `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt` (7C), `app/src/main/kotlin/dev/kestrel/app/KestrelApplication.kt` (7C), `app/src/main/kotlin/dev/kestrel/app/agent/AgentTexts.kt` (7A), `app/src/test/kotlin/dev/kestrel/app/agent/AgentTextsTest.kt` (7A) and `app/src/androidTest/kotlin/dev/kestrel/app/agent/AgentEndToEndTest.kt` (7A); the files that plan 7A changes and this plan deletes (`AgentRunner.kt`, `AppOwnerPort.kt`, `OwnerParts.kt`, `AgentEnvironment.kt`, `AppAgentEnvironment.kt` and their tests) need no rebase, but Task 3's new files carry plan 7A's behaviour (`TaskStart`'s goal and history now in `RoundStart`, `leaveKestrel` in `VoiceChannel`, the `LIMIT` question in `VoiceAnswers`).
- At the start of Task 1, and again at the starts of Tasks 3 and 7, run the plan checker against the branch's HEAD, in PowerShell 7: `$env:PLANX = 'H:\Code\kestrel-sp3\docs\superpowers\plans\2026-11-24-conversations-7d-voice.md'; $env:BASE_REF = (git -C H:\Code\kestrel-sp3 rev-parse HEAD); $env:S3A = 'C:\Users\AleksaLocal\.copilot\session-state\337a39e5-0c45-4e8a-b4a0-4c1d2eef024e\files\no-such-sim'; $env:OUTDIR = 'plan7d-sim'; python 'C:\Users\AleksaLocal\.copilot\session-state\337a39e5-0c45-4e8a-b4a0-4c1d2eef024e\files\plan3x-check.py'`; for Task 3 also set `$env:START_TASK = '3'`, and for Task 7 `$env:START_TASK = '7'`. Expected: an `operations:` line, a `files:` line, a `simulated files written:` line, and no line that starts with `PROBLEM:`. If a `PROBLEM:` line names a text that plan 6B-basefix, 7A, 7B or 7C moved or changed, or a file that one of them created, stop and ask the controller: the controller rebases the plan (it changes the plan's old texts to the texts that the earlier plans left, keeps each change's meaning, and adjusts a new text that depends on them), runs the checker again until it is clean, and only then gives the task.
- At the start of Task 3, check that plans 7A, 7B and 7C as merged have what this plan consumes: `Select-String -Path core\conversation\src\main\kotlin\dev\kestrel\conversation\engine\ConversationEngine.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\goal\GoalEnvironment.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\channel\OwnerChannel.kt, core\agent\src\main\kotlin\dev\kestrel\agent\port\OwnerPort.kt, core\conversation\src\main\kotlin\dev\kestrel\conversation\goal\GoalRunner.kt, app\src\main\kotlin\dev\kestrel\app\AppGraph.kt -Pattern 'fun awaitingCommand|fun endQuietTime|var restored|interface GoalEnvironment|fun interface ScreenTurn|class Refused\(val sentence: String, val end: EndReason\)|data class Shown|fun leaveKestrel|val conversationStore|SWITCH, DELETE_HISTORY|val cancellation = try'`. Expected: 12 matches (`fun leaveKestrel` matches in `OwnerChannel` and in `OwnerPort`; `SWITCH, DELETE_HISTORY` and `val cancellation = try` are plan 7B's changes for this plan, `2113f5a0`). Otherwise stop and ask the controller.
- Measure the counts before the first change of Task 1, and note them in the task's report: run `scripts\gradle.ps1 :app:testDebugUnitTest :tools:eval:test`, then `foreach ($dir in 'app\build\test-results\testDebugUnitTest', 'tools\eval\build\test-results\test') { $n = 0; Get-ChildItem $dir -Filter *.xml | ForEach-Object { $n += [int]([xml](Get-Content $_.FullName -Raw)).testsuite.tests }; "$dir $n" }`, then `"device $(@(Get-ChildItem app\src\androidTest -Recurse -Filter *.kt | Select-String -Pattern '@Test\b').Count)"`, then `scripts\gradle.ps1 --stop`. These are A₀ (`:app` JVM, with `app/src/testDebug`), E₀ (`:tools:eval`) and AD₀ (`:app`'s device tests). `:core:agent` (G₀) does not change in this plan, and `:core:conversation` (C₀) changes only in Task 7; Task 3 runs both once. Each task gives its counts as "N + k", where N is the count before the task. If a count differs, stop and ask the controller.
- Before each task, check that every text that the task replaces exists, and exists once, in its file as the earlier tasks left it; and run `git status --short`. The worktree may hold other agents' uncommitted files. If a text is missing, or a file that the task changes shows changes that are not the task's own, stop and ask the controller.
- The regression gate (design 14.3) runs the eval dry suite on kestrel-api37 for every task of kestrel-api37's part of the baseline that plan 6B-basefix recorded in `eval/baseline.json` (`dfa97763`), and compares each run line with the dry suite that plan 7A's last task notes in `.superpowers/sdd/progress.md` under "## Plan 7A": the baseline's rates come from live runs, which this plan does not make, so the dry suite before the move is the measure of "the same result". Task 8 runs the gate.
- Tasks 1 to 7 use no device. Task 8 is the controller's own steps on kestrel-api37.

## Not in this plan

- The goal service, "Continue the goal?" after a restart on the device, and the lock and call pauses with listening off on the device (plan 7E). This plan builds `PhoneSignals` and `ConversationSignals`, which make those pauses possible, and tests them on the JVM.
- The conversation screen, `TextChannel`, the question cards with "Reply…", the hook `conversation-type`, the typed waits on the device and the typed "delete history" (plan 7F). Until plan 7F the engine's channels are the voice channel alone, so a question has no card when Kestrel is off, and its D21 wait ends it.
- `scripts\hook.ps1`'s entries for the two new hooks, `:tools:eval`'s turns and its new answers (plan 7G). This plan's hooks answer through `TestHookReceiver`, as every hook does.
- The main spec's update (plan 7.0).

## Changes to the design that this plan makes

These are this plan's readings of the design where it leaves a choice open, or where its sketch is not the cleanest form. The controller confirms them with the owner, or the plan changes before Task 1.

- **The voice channel speaks through the Orchestrator.** `VoiceChannel` gives the Orchestrator `Event.ChannelSays(reply, text, final)` for Kestrel's words and final replies, `Event.ChannelShows(text)` for words that are only shown, `Event.ChannelAsks(question, text, voice)` for a question and `Event.ChannelHandsOver(text)` for a hand-over's sentence; `Listening.voicePath` (`VoicePath`) sends them and waits for a reply's end through `Effect.EndReply` or the reply's own end. So the Orchestrator still decides every word that Kestrel speaks, and the answer waits of 15 s and 35 s stay the Orchestrator's.
- **The voice reads the answer.** `VoiceAnswers` reads a transcript as the `ChannelAnswer` of the view's question kind, as `TaskAnswers` read it for a task, and `Rules` gives it as `InputIntent.Answer`; the end of the voice's wait gives `Answer(Silence)`. A spoken "no answer" to "Continue the goal?" keeps the goal held (plan 7B's decision 2, accepted with plan 7.0).
- **The page's question stands for the conversation's.** The settings page's "Delete history" asks its own question with the design's words and then calls `ConversationEngine.deleteHistory()`, which closes the open conversation, deletes what a yes to "delete history" deletes once the engine's writer has stored every write before it, and says nothing on a channel; while a goal is open or one of Kestrel's own requests runs it deletes nothing, and the page says why (design 9.7). This is the plan's one change to `:core:conversation` (Task 7).
- **The voice reads a projection of the view.** The Orchestrator gets `VoiceView` (open, the goal's phase, the question's kind and number, a final reply that waits), only when it changes, never the records; a question's end reaches it from the voice channel as `Event.ChannelWithdraws`, not inferred from the view.
- **What the voice's words stay.** The Orchestrator's names that are about voice stay (`Work.AWAITING_TASK`, `Feedback.AwaitingTask`, `Effect.WaitForTask`, `Event.WaitEnded`), so that its 52 voice tests stay as they are; only the task's names go.
- **The touch watcher is the goal's.** `GoalOwner`, a decorator of the round's owner port that `AppGoalEnvironment` builds, clears the watcher's credits before every question and starts the watcher again after a hand-over, a permission request and a pause, because an instant command in between stops it (design D19 of sub-project 5), and gives every `say` text to the round's witness. Today `AppOwnerPort` did this; in the design it would be split between two channels.
- **`PhoneSignals` serves both.** One screen receiver and one call listener serve listening (while its service runs) and the conversation (while a conversation is open, not only while a goal is open, because a goal's final reply waits for the owner's return; plan 7B's decision 6). `ConversationSignals` sends the engine `AGENT_CALL` only while a goal acts or asks, as today's backstop saw only a running task.
- **A refusal's end.** A round that cannot start ends as `NO_SCREEN` when Kestrel's accessibility service is off or the touch watcher cannot start, and as `NO_PROFILE` for no profile and for a test build without a script (today every refusal ended as `NO_PROFILE`). The service is checked before the model is chosen, as today. `:tools:eval` reads both ends as a refusal (Task 3), so that an injection fixture never passes when nothing ran.
- **`agent-state` keeps its words.** Its `asking=` keeps sub-project 5's names (`yes-no`, `confirm`, `free`, `pick-number`, `continue`), with a limit's "Continue?" and the switch as `yes-no` and the screenshot question as `free`, so that `:tools:eval`'s answers need no change before plan 7G; `question=` names `screenshot` and `limit`. Its `phase=` gains `paused-at-limit` and `interrupted` in Task 3, and the hook gains `checklist=` and `conversation=` in Task 6.
- **`voice-task` becomes a stand-in round.** The A71 session's hook makes every goal's round a stand-in on fake ports whose model waits the given seconds and then finishes, and the goal's final words are the reply itself, whole and of any length (review of Task 3: I1; the model's summary and a failure's reason have limits, 200 characters for `fail`, which the A71 session's 478-character story exceeds) No script and no part of `:tools:eval` reads the reply's words; Task 3 changes the descriptions in `scripts\hook.ps1` and `scripts\a71-session.ps1`.
- **The follow-up window reads as after "Yes?", with design 3.6's exceptions.** Inside the window the words are read as the words after the name (`Requests.read(…, afterName = true, followUp = true)`), so every other utterance is a command, while "stop", "cancel", "stop listening" and "turn off" keep their meaning (design 3.6): "turn off" alone turns Kestrel off and never reaches the conversation. After "Yes?" a bare "turn off" stays a command, as today.
- **The follow-up window ends whenever the bar stops counting it down.** Design 6.6 names the ends that it foresaw; the window also ends when Android blocks the microphone and when anything else takes the bar, such as a change of mode by the tile, the notification or the voice, or a reply that is only shown, and none opens while the microphone is blocked (`Rules.endFollowUp`, Task 4's re-check N1). So the bar never says "Paused" or "Microphone blocked" while an utterance without the name is still a command.
- **Kestrel's own requests run one at a time.** Design 6.4 lets a command wait for Kestrel's own request that runs; one of Kestrel's own requests that comes then waits too, also after "stop" has cancelled the running one ("Stop. Kestrel, forget the gate code" while "Remember …?" is asked), as both do while "stop" ends a goal (`ConversationMachine.alone`, Task 3's re-check N1). Before, the machine dropped it without a word.
- **One rule for what a stop stops.** `State.stoppable` (a goal in any phase, an open question, or Kestrel's own request that runs, `VoiceView.ownRequest`) decides every form of the stop by voice: a plain "stop" or "cancel", "Kestrel, stop", "stop" after "Yes?" or in the follow-up window, the keyword spotter's "stop" while Kestrel speaks, and "Stop. Kestrel, …". So "stop" cancels Kestrel's own request that runs without a question, as design 6.4 says, and a plain "stop" names a button only when nothing is stoppable; while such a request runs, recognition is `ALL` in the paused mode too, as while a goal works (whole-plan review, I1).
- **No follow-up window while a goal is open.** `Rules.followsUp`, the one condition for opening and keeping a window, also needs the conversation without a goal, so a goal that a waiting command starts while a final reply is spoken opens no window, and an open window ends when a goal starts (D9; whole-plan review, I2).
- **The voice's answers name their question.** `Rules` gives `InputIntent.Answer(answer, question)` with the number of the question that it read the answer for, also for the end of the wait (`Silence`) and a withdrawal (`Interrupted`), and the machine drops an answer that names a question that is no longer open (whole-plan review, M1).
- **The conversation's answer wait goes on during an instant command.** While a command runs, the end of the latest wait, the owner's speech and an utterance without a transcript reach the conversation's question as they do without a command, and the end of a wait that the command's and the conversation's questions share ends both (whole-plan review, M2). While the command asks nothing, the owner's answer words reach the conversation's question too, with its number, and the command goes on (`Rules.spokenAnswer`, the one answer rule, and `answerDuringCommand`; the controller's decision of 2026-11-25), also for "Continue the goal?": the round that a yes resumes takes the screen's turn itself (next entry).
- **Every bar text comes through the Rules.** A failed start of the settings is `Event.SettingsFailed`, which the Rules show as `Feedback.SettingsFailed`, so it ends a follow-up window like any other text (fix-7d-n1 review, nit 1).
- **The stored settings are followed after a failed read.** `storedConversationSettings` reads the store again after a failed read (`SETTINGS_RETRY_MILLIS`) and follows it on, with the defaults only before the first value; before, a failed read ended the flow, and the conversation kept the defaults until Kestrel restarted (whole-plan review, nits).
- **The rounds' evidences exist only in debug builds.** `AppGoalEnvironment.evidences` is null until the debug hooks install it with the witnesses, so a release build records nothing that only the hooks read (whole-plan review, nits).
- **`ScreenTurns` is the one rule for who acts on the screen.** It knows the instant command that runs from its start (`ScreenTurns.started(job, since, letGo)`, which `Listening.startCommand` calls with `InputIds.newest()`) until its job has really ended, also after a cancellation (`stopped(job)`). Every step of a goal's round that may act on the screen or open an app takes the turn first: the round's start, as before, and each such step of its run (`GoalPort.takeScreen`, which `TaskRun.screenGate` calls after the pause gate in `ScreenSteps`, `DataSteps` and `LookCloser`; `GoalRunner` gives each round `ScreenTakingGoal`, and `:app` wraps it in `WatchedGoal`, which starts the touch watcher again after a turn that waited); the round's talk to the owner passes only the pause gate. So a round that goes on after a pause ("Continue the goal?"), a hand-over or a permission request, and a goal that the machine starts from its queue, wait as a new round does. Each turn names the owner's input that lets the round act now, once (`take(after)`, from `RunningGoal.actedFor`, which the engine sets from `StartGoal`, `ResumeGoal`, `ResumePausedGoal(act)` and `EndHandOver(…, act)`; a tap on a card gets the next `InputIds` number). Only when that input came after the command started (`after > since`) does `take` ask the command to let go: `Event.ScreenWanted(command)`, and the Rules cancel the command as "stop" does ("Cancelled" on the bar). A round that goes on by itself, or for an older input, waits until the command has ended, because the command is the owner's newest act (D15 of sub-project 5; spec 7.3); a command that is cancelled meanwhile gets at most 3 s. `take` is true when it waited, which the run counts as a resume, so that it looks at the screen again. The Rules end a running command themselves only when the owner gives Kestrel a new request, never for an answer (whole-plan re-review, N1 and its round 2, R2-1).
- **Each instant-command device test starts without a conversation.** `CommandTests.start` closes the conversation that the device keeps through the engine's inputs and view (`ConversationTests.close`, which `AgentTests` uses too) and starts only once the Orchestrator reads no open conversation and no window, so a conversation that an earlier test left can never take a command's transcript (whole-plan review, M5); `VoiceTests.startListening`, which the voice suite uses, does the same (whole-plan re-review, N2).
- **The limits stay within the page's bounds.** `conversation-control do=limits` writes the Conversations setting within its bounds (at least 50 replies, 5 minutes and 10 AI Credits); plan 7G adds a debug-only seam for smaller limits with its task `limit-continue`.

The code texts of Tasks 3 and 4 below are as those tasks built them. The whole-plan review's fixes, in commits after `7c44ac99`, change `Rules.stopsWorkFirst`, `idle`, `whileAwaitingTask`, `spotted`, `answer`, `stopGoal` (now `stopWork`), `followsUp`, `endFollowUp` and `duringCommand`, `Listening.openSettingsOrSayWhy`, `storedConversationSettings`, `AppGoalEnvironment.evidences`, `CommandTests.start`, `VoiceTests.startListening` and `AgentTests`, and Task 2's `ScreenTurns` (`started(job, since, letGo)`, `stopped(job)`, `take(after): Boolean`), with plan 7B's `ScreenTurn.take(after: Long?): Boolean`, `RunningGoal.actedFor`, `ScreenTakingGoal`, the machine's `OwnerAct` on `ResumePausedGoal` and `EndHandOver`, the engine's `inputIds`, plan 7A's `GoalPort.takeScreen` and `TaskRun.screenGate`, and `:app`'s `WatchedGoal`, as the entries above say.

## File structure

| File | Responsibility |
|---|---|
| `app/src/main/kotlin/dev/kestrel/app/phone/PhoneSignals.kt`, `app/src/main/kotlin/dev/kestrel/app/phone/AndroidScreenSource.kt` (new); `ListeningService.kt`, `AppGraph.kt`; test `app/src/test/kotlin/dev/kestrel/app/phone/PhoneSignalsTest.kt` (new) | Task 1: one screen receiver and one call listener for every watcher |
| `app/src/main/kotlin/dev/kestrel/app/listen/ScreenTurns.kt` (new); `Listening.kt`, `AppGraph.kt`; tests `app/src/test/kotlin/dev/kestrel/app/listen/ScreenTurnsTest.kt` (new), `ListeningTest.kt` | Task 2: the screen's turn |
| `orchestrator/` `Rules.kt`, `Orchestrator.kt` (whole), `Requests.kt`, `VoiceAnswers.kt`, `VoiceView.kt` (new), `TaskAnswers.kt` (deleted); `listen/` `VoicePath.kt` (new), `Listening.kt`; `conversation/` `InputIds.kt`, `OwnerSupport.kt`, `VoiceChannel.kt`, `ConversationSignals.kt`, `StoredSettings.kt` (new); `agent/` `AppGoalEnvironment.kt`, `AppModels.kt`, `RoundModels.kt`, `RoundEvidences.kt`, `GoalOwner.kt`, `Permissions.kt` (new), `CallBackstop.kt` (whole), `AgentTexts.kt`, `TaskWitness.kt`, `AgentRunner.kt`, `AppOwnerPort.kt`, `OwnerParts.kt`, `AgentEnvironment.kt`, `AppAgentEnvironment.kt` (deleted); `AppGraph.kt`, `KestrelApplication.kt`; debug `CommandTags.kt` (whole), `StandInRound.kt` (new), `AgentTaskHooks.kt`, `AgentHookAnswers.kt`, `VoiceHooks.kt`, `SessionHooks.kt`, `DebugTaskWitness.kt`, `TaskOptions.kt` (deleted); their JVM and device tests, and the device test `GoalPauseTest.kt` with `HeldModel.kt` (new); `:tools:eval`'s `Runner.kt`, `Refusals.kt` and `RunnerTest.kt`; the descriptions of `voice-task` in `scripts/hook.ps1` and `scripts/a71-session.ps1` | Task 3: the switch-over |
| `Rules.kt`, `Requests.kt`, `Orchestrator.kt`, `Listening.kt`, `VoicePath.kt`, `VoiceChannel.kt`, `AppGraph.kt`; `RulesTest.kt`, `RequestsTest.kt`, `OrchestratorTest.kt`, `ListeningTest.kt`, `VoiceChannelTest.kt` | Task 4: the follow-up window |
| `Requests.kt`; `RequestsTest.kt`, `RulesTest.kt` | Task 5: "new conversation", "continue the conversation about X" and the typed reading |
| debug `ConversationHookAnswers.kt`, `ConversationHooks.kt` (new), `TestHooks.kt`, `AgentHookAnswers.kt`, `AgentTaskHooks.kt`, `VoiceHooks.kt`; JVM tests `app/src/testDebug/kotlin/dev/kestrel/app/debug/ConversationHookAnswersTest.kt` (new), `AgentHookAnswersTest.kt`; device tests `app/src/androidTest/kotlin/dev/kestrel/app/debug/ConversationHooksTest.kt` (new), `VoiceHookTest.kt` | Task 6: the hooks `conversation-state` and `conversation-control` |
| `core/conversation/` `engine/HistoryDeletion.kt` (new), `engine/ConversationEngine.kt`, `local/LocalRequests.kt`, `machine/ConversationEvent.kt`, `machine/ConversationMachine.kt`; `settings/SettingsData.kt`, `settings/LogPages.kt`, `res/values/strings.xml`; tests `ConversationEngineTest.kt`, `SettingsAppTest.kt` | Task 7: the settings page's "Delete history" through the conversation |
| none (device steps) | Task 8: the regression gate on kestrel-api37 |

Every main path is below `app/src/main/kotlin/dev/kestrel/app/`, every JVM test below `app/src/test/kotlin/dev/kestrel/app/`, the debug hooks below `app/src/debug/kotlin/dev/kestrel/app/debug/` and their JVM tests below `app/src/testDebug/kotlin/dev/kestrel/app/debug/`; the steps give each path in full.

## What plan 7B changes for this plan

The review of 2026-11-24 found two guarantees of today's task path that plan 7B's code did not keep; plan 7B carries both since `2113f5a0`, and "Before you start" checks for them before Task 3.

1. **A round lets go of the screen before its end reaches the listener (design D19 of sub-project 5).** `GoalRunner.finish` releases the round under `NonCancellable` first, and a round gives its end exactly once (`val cancellation = try …`), so that the next round's touch watcher, which the end may start at once, is never stopped by the earlier release. Its test is plan 7B's `GoalRunnerTest.aRoundReleasesBeforeItsEndReachesTheListener`; it replaces `AgentRunnerTest`'s case of the release order (Task 3's table).
2. **"Delete the history?" has a kind of its own (design 9.7).** `LocalRequests` asks it as `QuestionKind.DELETE_HISTORY`, which `VoiceAnswers` reads with "yes" alone (Task 3), so that "continue" or "go on" never deletes the history.

---

### Task 1: `PhoneSignals`, one screen receiver and one call listener for every watcher

**Status: built** in `3f19e672`, with the fix of its review in `32e96e99` (`ScreenSource.read` gives each first reading on the source's thread, in order with the changes, and `watchScreen` holds no watcher when the source cannot start); its steps are checked, and its texts are the code as built.

Today `ListeningService` registers its own screen receiver, and `Listening` starts Android's call listener while a voice session is open. A goal must pause on a lock or a call also with listening off (design 5.5 and 6.5 of sub-project 7), so the screen and the calls move into `PhoneSignals`, which one receiver and one call listener serve for every watcher: listening now, the conversation from Task 3. Listening's behaviour does not change: the service watches the screen from its start to its end and gets the reading at once, and `Listening` gets its calls through `PhoneSignals.callMonitor()`, a `CallMonitor` like today's.

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/phone/PhoneSignals.kt`, `app/src/main/kotlin/dev/kestrel/app/phone/AndroidScreenSource.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt`, `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`
- Test: `app/src/test/kotlin/dev/kestrel/app/phone/PhoneSignalsTest.kt`

**Interfaces:**
- Consumes: `dev.kestrel.app.listen.CallMonitor` and `AndroidCallMonitor`; `dev.kestrel.app.orchestrator.CallState`.
- Produces (package `dev.kestrel.app.phone`): `internal fun interface PhoneWatch { fun stop() }`; `internal interface ScreenSource { fun start(report: (Boolean) -> Unit, cameOn: () -> Unit); fun stop(); fun read(reading: (Boolean) -> Unit) }`, whose reports and readings run on the source's one thread (Android's main thread) in order; `internal class AndroidScreenSource(context: Context) : ScreenSource`; `internal class PhoneSignals(screens: ScreenSource, calls: CallMonitor)` with `fun watchScreen(cameOn: () -> Unit = {}, listener: (Boolean) -> Unit): PhoneWatch`, `fun watchCalls(listener: (CallState) -> Unit): PhoneWatch` and `fun callMonitor(): CallMonitor`; `AppGraph.phoneSignals: PhoneSignals` (`internal`, built on first use).

Counts: `:app` JVM A₀ + 7 (`PhoneSignalsTest` 7). Device tests: AD₀, unchanged.

- [x] **Step 1: Write the failing test**

Create `app/src/test/kotlin/dev/kestrel/app/phone/PhoneSignalsTest.kt`:

```kotlin
package dev.kestrel.app.phone

import dev.kestrel.app.listen.CallMonitor
import dev.kestrel.app.orchestrator.CallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** One screen receiver and one call listener for every watcher (design 5.5 and 6.5 of sub-project 7; plan 7D). */
class PhoneSignalsTest {
    private val screens = FakeScreens()
    private val calls = FakeCalls()
    private val phone = PhoneSignals(screens, calls)

    @Test
    fun oneReceiverServesEveryScreenWatchAndANewWatchGetsTheReadingAtOnce() {
        val listening = mutableListOf<Boolean>()
        val conversation = mutableListOf<Boolean>()
        screens.now = true

        val first = phone.watchScreen { listening += it }
        val second = phone.watchScreen { conversation += it }
        assertEquals(1, screens.starts)
        assertEquals(listOf(true), listening)
        assertEquals(listOf(true), conversation)

        screens.change(false)
        first.stop()
        first.stop()
        screens.change(true)

        assertEquals(listOf(true, false), listening)
        assertEquals(listOf(true, false, true), conversation)
        assertEquals(0, screens.stops)
        second.stop()
        assertEquals(1, screens.stops)
    }

    @Test
    fun everyCallWatchRegistersAndroidsListenerAgainAndTheLastStopEndsIt() {
        val first = mutableListOf<CallState>()
        val second = mutableListOf<CallState>()

        val one = phone.watchCalls { first += it }
        calls.change(CallState.IDLE)
        val two = phone.watchCalls { second += it }
        // Android reports the state at once to a listener that registers, so the new watch's first report is Android's own.
        assertEquals(2, calls.starts)
        calls.change(CallState.RINGING)

        assertEquals(listOf(CallState.IDLE, CallState.RINGING), first)
        assertEquals(listOf(CallState.RINGING), second)
        one.stop()
        assertEquals(0, calls.stops)
        two.stop()
        assertEquals(1, calls.stops)
    }

    @Test
    fun listeningsCallMonitorIsOneWatchThatANewStartReplaces() {
        val monitor = phone.callMonitor()
        val seen = mutableListOf<CallState>()

        monitor.start { seen += it }
        // As Listening.watchCalls after the owner allowed the phone state: Android's listener registers again.
        monitor.start { seen += it }
        assertEquals(2, calls.starts)
        assertEquals(0, calls.stops)
        calls.change(CallState.ACTIVE)

        assertEquals(listOf(CallState.ACTIVE), seen)
        monitor.stop()
        assertEquals(1, calls.stops)
    }

    @Test
    fun aCallWatchWhoseListenerCannotStartLeavesNoWatcherBehind() {
        calls.failure = IllegalStateException("Telephony failed")
        assertThrows(IllegalStateException::class.java) { phone.watchCalls { } }

        calls.failure = null
        val seen = mutableListOf<CallState>()
        val watch = phone.watchCalls { seen += it }
        calls.change(CallState.RINGING)
        // With the failed watch left behind, Android's listener would stay registered after this last watch stops.
        watch.stop()

        assertEquals(listOf(CallState.RINGING), seen)
        assertEquals(1, calls.stops)
    }

    @Test
    fun aScreenWatchWhoseSourceCannotStartLeavesNoWatcherBehind() {
        screens.failure = IllegalStateException("Receiver failed")
        assertThrows(IllegalStateException::class.java) { phone.watchScreen { } }

        screens.failure = null
        val seen = mutableListOf<Boolean>()
        val watch = phone.watchScreen { seen += it }
        screens.change(true)
        watch.stop()

        assertEquals(1, screens.starts)
        assertEquals(listOf(false, true), seen)
        assertEquals(1, screens.stops)
    }

    @Test
    fun aWatchFromAnotherThreadGetsItsFirstReadingOnTheSourcesThreadInOrderWithTheReports() {
        val listening = mutableListOf<Boolean>()
        val conversation = mutableListOf<Boolean>()
        val stopped = mutableListOf<Boolean>()
        screens.now = true
        phone.watchScreen { listening += it }

        screens.queued = true
        phone.watchScreen { conversation += it }
        phone.watchScreen { stopped += it }.stop()
        // The screen goes off while the source's thread has yet to come to the new watches' readings.
        screens.change(false)
        assertEquals(emptyList<Boolean>(), conversation)
        screens.runQueued()

        // The reading is the state when the source's thread reads it, so the old "on" never comes after the change.
        assertEquals(listOf(false, false), conversation)
        assertEquals(listOf(true, false), listening)
        assertEquals(emptyList<Boolean>(), stopped)
    }

    @Test
    fun theScreenComingOnReachesEveryScreenWatchThatHearsIt() {
        var woken = 0
        val conversation = phone.watchScreen({ woken++ }) { }
        phone.watchScreen { }

        screens.comeOn()
        conversation.stop()
        screens.comeOn()

        assertEquals(1, woken)
    }

    private class FakeScreens : ScreenSource {
        var now = false
        var starts = 0
        var stops = 0
        var failure: Exception? = null

        // The source's one thread runs every reading and report at once, or, while queued, in order at runQueued.
        var queued = false
        private val thread = ArrayDeque<() -> Unit>()
        private var report: ((Boolean) -> Unit)? = null
        private var cameOn: (() -> Unit)? = null

        override fun start(report: (Boolean) -> Unit, cameOn: () -> Unit) {
            failure?.let { throw it }
            starts++
            this.report = report
            this.cameOn = cameOn
        }

        override fun stop() {
            stops++
            report = null
            cameOn = null
        }

        override fun read(reading: (Boolean) -> Unit) = onThread { reading(now) }

        fun change(on: Boolean) {
            now = on
            val report = checkNotNull(report) { "Nothing watches the screen" }
            onThread { report(on) }
        }

        fun comeOn() {
            val cameOn = checkNotNull(cameOn) { "Nothing watches the screen" }
            onThread { cameOn() }
        }

        fun runQueued() {
            while (thread.isNotEmpty()) thread.removeFirst().invoke()
        }

        private fun onThread(action: () -> Unit) {
            if (queued) thread.addLast(action) else action()
        }
    }

    private class FakeCalls : CallMonitor {
        var starts = 0
        var stops = 0
        var failure: Exception? = null
        private var report: ((CallState) -> Unit)? = null

        override fun start(report: (CallState) -> Unit) {
            failure?.let { throw it }
            starts++
            this.report = report
        }

        override fun stop() {
            stops++
            report = null
        }

        fun change(state: CallState) {
            checkNotNull(report) { "Nothing watches the calls" }.invoke(state)
        }
    }
}
```

- [x] **Step 2: Run the test to see it fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.phone.PhoneSignalsTest`
Expected: FAIL, the compiler reports `Unresolved reference 'PhoneSignals'`.

- [x] **Step 3: Create the signals and Android's screen source**

Create `app/src/main/kotlin/dev/kestrel/app/phone/PhoneSignals.kt`:

```kotlin
package dev.kestrel.app.phone

import dev.kestrel.app.listen.CallMonitor
import dev.kestrel.app.orchestrator.CallState
import java.util.concurrent.atomic.AtomicBoolean

/** A watch of the phone that one part of Kestrel holds; [stop] ends it, and a second stop changes nothing. */
internal fun interface PhoneWatch {
    fun stop()
}

/**
 * Android's screen and lock (design spec, section 8.5). Any thread may call it. Every report, every [start]'s cameOn and
 * every [read] runs on the source's one thread, in the order in which they happen there, so that no reading can come
 * after a newer change.
 */
internal interface ScreenSource {
    /**
     * Starts to report every change to [report]: whether the screen is on and the phone unlocked; and each time the screen
     * comes on, locked or not, to [cameOn], after [report].
     */
    fun start(report: (Boolean) -> Unit, cameOn: () -> Unit)

    /** Stops the reports. */
    fun stop()

    /**
     * Gives [reading] whether the screen is on and the phone unlocked, read on the source's thread: at once when the caller
     * is on it, otherwise after the reports that are already on their way there.
     */
    fun read(reading: (Boolean) -> Unit)
}

/**
 * The phone's screen, lock and calls for every part of Kestrel that watches them (design 5.5 and 6.5 of sub-project 7):
 * listening while its service runs, and from plan 7D's Task 3 the conversation while it is open, so that a goal pauses on
 * a lock or a call also with listening off. One screen receiver and one call listener serve every watch: each starts
 * with its first watch and stops with its last. A new screen watch gets its first reading on the screen source's thread,
 * as every change, so that the reading never comes after a newer change. Every new call watch registers Android's
 * listener again, which reports the state at once, as [CallMonitor] promises at each start, so that a watch that starts
 * after the owner allowed the phone state sees the calls. Any thread may call it; a screen listener is called on the
 * screen source's thread and a call listener on Android's, and each must return at once.
 */
internal class PhoneSignals(private val screens: ScreenSource, private val calls: CallMonitor) {
    // One object per watch, so that the same listener given twice is watched twice and stopped once each.
    private class Watcher<T>(val listener: (T) -> Unit)

    private class ScreenWatcher(val listener: (Boolean) -> Unit, val cameOn: () -> Unit)

    private val lock = Any()
    private val screenWatchers = mutableListOf<ScreenWatcher>()
    private val callWatchers = mutableListOf<Watcher<CallState>>()

    /**
     * Watches the screen: [listener] gets the reading first and then every change, and [cameOn] hears each time the screen
     * comes on, locked or not, until the watch stops. On the screen source's thread the first reading comes at once,
     * otherwise a moment later; a watch that stops before it gets none. Throws what [ScreenSource.start] throws, and then
     * holds no watch. [listener] comes last, so that a trailing lambda is the reading's.
     */
    fun watchScreen(cameOn: () -> Unit = {}, listener: (Boolean) -> Unit): PhoneWatch {
        val watcher = ScreenWatcher(listener, cameOn)
        synchronized(lock) {
            screenWatchers += watcher
            if (screenWatchers.size == 1) {
                try {
                    screens.start(::screenChanged, ::screenCameOn)
                } catch (e: Exception) {
                    screenWatchers.remove(watcher)
                    throw e
                }
            }
        }
        screens.read { on -> if (synchronized(lock) { watcher in screenWatchers }) listener(on) }
        return once {
            synchronized(lock) { if (screenWatchers.remove(watcher) && screenWatchers.isEmpty()) screens.stop() }
        }
    }

    /**
     * Watches the calls: [listener] gets Android's report of the state at the start and every change, until the watch
     * stops. Throws what [CallMonitor.start] throws, and then holds no watch.
     */
    fun watchCalls(listener: (CallState) -> Unit): PhoneWatch {
        val watcher = Watcher(listener)
        synchronized(lock) {
            callWatchers += watcher
            try {
                calls.start(::callChanged)
            } catch (e: Exception) {
                callWatchers.remove(watcher)
                throw e
            }
        }
        return once {
            synchronized(lock) { if (callWatchers.remove(watcher) && callWatchers.isEmpty()) calls.stop() }
        }
    }

    /**
     * The calls as one [CallMonitor] for listening (section 8.7): a start watches the calls with its report and ends the
     * watch of an earlier start, after the new one has begun, so that Android's listener never stops in between; a stop
     * ends the watch.
     */
    fun callMonitor(): CallMonitor = object : CallMonitor {
        private var watch: PhoneWatch? = null

        override fun start(report: (CallState) -> Unit) {
            val started = watchCalls(report)
            synchronized(this) { watch.also { watch = started } }?.stop()
        }

        override fun stop() {
            synchronized(this) { watch.also { watch = null } }?.stop()
        }
    }

    private fun screenChanged(on: Boolean) {
        synchronized(lock) { screenWatchers.toList() }.forEach { it.listener(on) }
    }

    private fun screenCameOn() {
        synchronized(lock) { screenWatchers.toList() }.forEach { it.cameOn() }
    }

    private fun callChanged(state: CallState) {
        synchronized(lock) { callWatchers.toList() }.forEach { it.listener(state) }
    }

    private fun once(stop: () -> Unit): PhoneWatch {
        val stopped = AtomicBoolean()
        return PhoneWatch { if (stopped.compareAndSet(false, true)) stop() }
    }

    override fun toString(): String = "PhoneSignals"
}
```

Create `app/src/main/kotlin/dev/kestrel/app/phone/AndroidScreenSource.kt`:

```kotlin
package dev.kestrel.app.phone

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * Android's screen and lock (design spec, section 8.5), as ListeningService read them until plan 7D: Kestrel counts the
 * phone as unlocked after USER_PRESENT until the screen goes off, because KeyguardManager can learn of the unlock a moment
 * later, and a SCREEN_ON that Kestrel handles after USER_PRESENT must not report the old lock. The source's thread is the
 * main thread: the receiver runs there, and so does every reading, so that a reading and the broadcasts keep their order.
 */
internal class AndroidScreenSource(private val context: Context) : ScreenSource {
    private val main = Handler(Looper.getMainLooper())

    // The owner unlocked the phone since the screen last went off (USER_PRESENT).
    @Volatile
    private var unlocked = false
    private var receiver: BroadcastReceiver? = null

    override fun start(report: (Boolean) -> Unit, cameOn: () -> Unit) {
        val screen = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_USER_PRESENT -> unlocked = true
                    Intent.ACTION_SCREEN_OFF -> unlocked = false
                }
                report(current())
                if (intent.action == Intent.ACTION_SCREEN_ON) cameOn()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        synchronized(this) {
            receiver?.let(context::unregisterReceiver)
            unlocked = false
            // SCREEN_ON and SCREEN_OFF come from the system, but USER_PRESENT comes from SystemUI, and Android gives another
            // app's broadcasts only to an exported receiver. All three are protected broadcasts, which other apps cannot
            // send.
            context.registerReceiver(screen, filter, null, main, Context.RECEIVER_EXPORTED)
            receiver = screen
        }
    }

    override fun stop() {
        synchronized(this) {
            receiver?.let(context::unregisterReceiver)
            receiver = null
        }
    }

    override fun read(reading: (Boolean) -> Unit) {
        if (main.looper.isCurrentThread) reading(current()) else main.post { reading(current()) }
    }

    private fun current(): Boolean {
        val interactive = context.getSystemService(PowerManager::class.java).isInteractive
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        return interactive && (unlocked || !locked)
    }

    override fun toString(): String = "AndroidScreenSource(watching=${receiver != null})"
}
```

- [x] **Step 4: Run the test to see it pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.phone.PhoneSignalsTest`
Expected: PASS, 7 tests.

- [x] **Step 5: Listening's service and the graph use the signals**

In `app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt`, replace:

```kotlin
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import dev.kestrel.app.KestrelApplication
```

with:

```kotlin
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.kestrel.app.KestrelApplication
```

In `app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt`, replace:

```kotlin
import dev.kestrel.app.orchestrator.Mode
import kotlinx.coroutines.CoroutineScope
```

with:

```kotlin
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.phone.PhoneWatch
import kotlinx.coroutines.CoroutineScope
```

In `app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt`, replace:

```kotlin
 * with Pause or Resume and Stop, with only Stop during a call, or without actions once Kestrel is turning off, and
 * tells [Listening] whether the screen is on and the phone unlocked. The actions are broadcasts to
```

with:

```kotlin
 * with Pause or Resume and Stop, with only Stop during a call, or without actions once Kestrel is turning off, and
 * tells [Listening] whether the screen is on and the phone unlocked, from the app graph's
 * [dev.kestrel.app.phone.PhoneSignals], which the conversation shares (plan 7D). The actions are broadcasts to
```

In `app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt`, replace:

```kotlin
    // The owner unlocked the phone since the screen last went off (USER_PRESENT). Only the main thread uses it.
    private var unlocked = false
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> unlocked = true
                Intent.ACTION_SCREEN_OFF -> unlocked = false
            }
            reportScreen()
        }
    }
```

with:

```kotlin
    // The service's watch of the screen and the lock (section 8.5), from its creation to its end. Only the main thread
    // uses it.
    private var screenWatch: PhoneWatch? = null
```

In `app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt`, replace:

```kotlin
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // SCREEN_ON and SCREEN_OFF come from the system, but USER_PRESENT comes from SystemUI, and Android gives another
        // app's broadcasts only to an exported receiver. All three are protected broadcasts, which other apps cannot send.
        registerReceiver(screen, filter, Context.RECEIVER_EXPORTED)
        // Before the voice session opens, and so before the microphone's first frame.
        reportScreen()
```

with:

```kotlin
        // The reading at once, before the voice session opens, and so before the microphone's first frame; then every
        // change.
        screenWatch = (application as KestrelApplication).graph.phoneSignals.watchScreen { on -> listening.send(Event.Screen(on)) }
```

In `app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt`, replace:

```kotlin
        scope.cancel()
        unregisterReceiver(screen)
```

with:

```kotlin
        scope.cancel()
        screenWatch?.stop()
        screenWatch = null
```

In `app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt`, replace:

```kotlin
    /**
     * Section 8.5: Kestrel records only while the screen is on and the phone unlocked. After USER_PRESENT the phone counts
     * as unlocked until the screen goes off, because KeyguardManager can learn of the unlock a moment later, and a
     * SCREEN_ON that Kestrel handles after USER_PRESENT must not report the old lock.
     */
    private fun reportScreen() {
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        listening.send(Event.Screen(on = interactive && (unlocked || !locked)))
    }

    /**
```

with:

```kotlin
    /**
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
import dev.kestrel.app.orchestrator.Mode
```

with:

```kotlin
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.phone.AndroidScreenSource
import dev.kestrel.app.phone.PhoneSignals
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
            // Section 8.7: the call state, while a voice session is open.
            calls = AndroidCallMonitor(appContext),
```

with:

```kotlin
            // Section 8.7: the call state, while a voice session is open, from the signals that the conversation shares.
            calls = phoneSignals.callMonitor(),
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
    /** The microphone of the newest voice session, for the debug hook voice-route (plan 2E). */
```

with:

```kotlin
    /**
     * The phone's screen, lock and calls (design 5.5 and 6.5 of sub-project 7): one receiver and one call listener for
     * ListeningService, Listening and, from plan 7D's switch-over, the conversation.
     */
    internal val phoneSignals: PhoneSignals by lazy { PhoneSignals(AndroidScreenSource(appContext), AndroidCallMonitor(appContext)) }

    /** The microphone of the newest voice session, for the debug hook voice-route (plan 2E). */
```

- [x] **Step 6: Build and run the JVM tests**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL; `$n = 0; Get-ChildItem app\build\test-results\testDebugUnitTest -Filter *.xml | ForEach-Object { $n += [int]([xml](Get-Content $_.FullName -Raw)).testsuite.tests }; "app $n"` gives A₀ + 7.

Run: `scripts\gradle.ps1 --stop`

- [x] **Step 7: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/phone app/src/main/kotlin/dev/kestrel/app/listen/ListeningService.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/test/kotlin/dev/kestrel/app/phone/PhoneSignalsTest.kt
git commit -m "refactor(listen): one screen receiver and one call listener serve every watcher through PhoneSignals, so that the conversation can watch the phone with listening off (plan 7D)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 2: `ScreenTurns`, the screen's turn

**Status: built** in `0e374a00`, with its review's comments in `3f8f4258`; its steps are checked, and its texts are the code as built.

`Listening.afterStopping` makes a new instant command, a task and Kestrel's settings wait for a cancelled command's job (spec 7.3). A goal's round must wait the same way before its first observation (design 7.1 of sub-project 7), and plan 7B's `GoalRunner` asks a `ScreenTurn` for it. The stopping job and its wait of at most 3 s move into `ScreenTurns`, which implements plan 7B's `ScreenTurn` and which `Listening` uses now; Task 3 gives the same instance to `GoalRunner`.

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/listen/ScreenTurns.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`
- Test: `app/src/test/kotlin/dev/kestrel/app/listen/ScreenTurnsTest.kt`, `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`

**Interfaces:**
- Consumes: `dev.kestrel.conversation.goal.ScreenTurn` (plan 7B: `fun interface ScreenTurn { suspend fun take() }`).
- Produces: `internal class ScreenTurns(report: (String, Throwable) -> Unit, waitMillis: Long = STOPPING_MILLIS) : ScreenTurn` (package `dev.kestrel.app.listen`) with `fun stopped(job: Job)`, `fun held(): Boolean`, `override suspend fun take()` and `STOPPING_MILLIS = 3_000L`; `Listening`'s constructor gains `turns: ScreenTurns`; `AppGraph.screenTurns: ScreenTurns` (`internal`).

Counts: `:app` JVM A₁ + 3 (`ScreenTurnsTest` 3). Device tests: AD₀, unchanged.

- [x] **Step 1: Write the failing test**

Create `app/src/test/kotlin/dev/kestrel/app/listen/ScreenTurnsTest.kt`:

```kotlin
package dev.kestrel.app.listen

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screen's turn (spec 7.3; design 7.1 of sub-project 7; plan 7D). */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenTurnsTest {
    private val reports = mutableListOf<String>()
    private val turns = ScreenTurns({ what, _ -> reports += what })

    @Test
    fun withoutACancelledCommandTheScreenIsFreeAtOnce() = runTest {
        assertFalse(turns.held())

        turns.take()

        assertEquals(0L, currentTime)
    }

    @Test
    fun aCancelledCommandHoldsTheScreenUntilItsJobHasCompleted() = runTest {
        val job = Job()
        turns.stopped(job)
        assertTrue(turns.held())

        val taken = async { turns.take() }
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(taken.isCompleted)
        job.complete()
        runCurrent()

        assertTrue(taken.isCompleted)
        assertFalse(turns.held())
        assertEquals(emptyList<String>(), reports)
    }

    @Test
    fun aStuckCommandHoldsTheScreenForAtMostThreeSecondsAndIsReportedOnce() = runTest {
        turns.stopped(Job())

        val taken = async { turns.take() }
        advanceTimeBy(2_999)
        runCurrent()
        assertFalse(taken.isCompleted)
        advanceTimeBy(2)
        runCurrent()

        assertTrue(taken.isCompleted)
        assertEquals(listOf("A cancelled instant command"), reports)
        // Kestrel goes on without it: nothing waits for it again.
        assertFalse(turns.held())
        turns.take()
        assertEquals(1, reports.size)
    }
}
```

- [x] **Step 2: Run the test to see it fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.listen.ScreenTurnsTest`
Expected: FAIL, the compiler reports `Unresolved reference 'ScreenTurns'`.

- [x] **Step 3: Create the screen's turn**

Create `app/src/main/kotlin/dev/kestrel/app/listen/ScreenTurns.kt`:

```kotlin
package dev.kestrel.app.listen

import dev.kestrel.conversation.goal.ScreenTurn
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * Who may act on the screen (spec 7.3; design 5.5 and 7.1 of sub-project 7): one actor at a time. A cancelled instant
 * command's job may still act until it has completed, as when Android finishes a gesture or a binder call of it, so a new
 * instant command, Kestrel's settings and a goal's round wait for it here, for at most [waitMillis]; a job that is still
 * stuck then is reported once to [report], and Kestrel goes on without it. A goal never holds the screen itself, so the
 * instant commands still work during its hand-overs and pauses. Any thread may call it.
 */
internal class ScreenTurns(
    private val report: (String, Throwable) -> Unit,
    private val waitMillis: Long = STOPPING_MILLIS,
) : ScreenTurn {
    private val stopping = AtomicReference<Job?>(null)

    /** The cancelled instant command's [job], which may act on the screen until it has completed. */
    fun stopped(job: Job) {
        stopping.set(job)
    }

    /** Whether a cancelled instant command's job may still act on the screen. */
    fun held(): Boolean = stopping.get()?.isCompleted == false

    /** Returns once no cancelled instant command's job can act on the screen, or once it was stuck for [waitMillis]. */
    override suspend fun take() {
        val job = stopping.get()?.takeIf { !it.isCompleted } ?: return
        if (withTimeoutOrNull(waitMillis) { job.join() } == null) {
            report("A cancelled instant command", StuckCommandException())
            // Kestrel goes on without it: nothing later waits for it again.
            stopping.compareAndSet(job, null)
        }
    }

    override fun toString(): String = "ScreenTurns(held=${held()})"

    companion object {
        /** Section 7.3: how long a new command, a goal or the settings wait for a cancelled command's job. */
        const val STOPPING_MILLIS = 3_000L
    }
}

/** A cancelled instant command's job had not completed after 3 s; Kestrel went on without it. */
private class StuckCommandException : Exception()
```

- [x] **Step 4: Run the test to see it pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.listen.ScreenTurnsTest`
Expected: PASS, 3 tests.

- [x] **Step 5: Listening waits through the screen's turn**

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
```

with:

```kotlin
import kotlinx.coroutines.withContext
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
 * [commands] runs the instant commands (sections 7.3 and 9.5): one job at a time in [scope]. A cancelled job stays the
 * stopping job, and a new command, a task or the settings wait until it has completed, for at most 3 s, so that two parts
 * of Kestrel never drive the screen at once. Every command that started gets one report in [commandReports].
```

with:

```kotlin
 * [commands] runs the instant commands (sections 7.3 and 9.5): one job at a time in [scope]. A cancelled job holds the
 * screen in [turns], and a new command, a task or the settings wait until it has completed, for at most 3 s, so that two
 * parts of Kestrel never drive the screen at once. Every command that started gets one report in [commandReports].
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
    private val commands: CommandRunner,
    options: Flow<ListeningOptions>,
```

with:

```kotlin
    private val commands: CommandRunner,
    private val turns: ScreenTurns,
    options: Flow<ListeningOptions>,
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
    // Only [thread] uses these (section 7.3): the running command's job and conversation, the cancelled job that may
    // still act, the number of the newest command to run, and the newest number that was cancelled, also before its job
    // started.
    private var commandJob: Job? = null
    private var conversation: Conversation? = null
    private var stopping: Job? = null
    private var newestCommand = 0
```

with:

```kotlin
    // Only [thread] uses these (section 7.3): the running command's job and conversation, the number of the newest command
    // to run, and the newest number that was cancelled, also before its job started. A cancelled job that may still act
    // holds the screen in [turns].
    private var commandJob: Job? = null
    private var conversation: Conversation? = null
    private var newestCommand = 0
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
     * Runs [action] at once, or, while a cancelled command's job has not completed, on [thread] once it has, but after
     * at most [STOPPING_MILLIS] (section 7.3). A deferred action that throws is reported, and then [failed] runs.
```

with:

```kotlin
     * Runs [action] at once, or, while a cancelled command's job holds the screen, on [thread] once [turns] gives the
     * screen back (section 7.3). A deferred action that throws is reported, and then [failed] runs.
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
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
```

with:

```kotlin
    private fun afterStopping(failed: () -> Unit = {}, action: () -> Unit) {
        // A cancelled job is no longer active, but it may still run until it completes.
        if (!turns.held()) {
            action()
            return
        }
        scope.launch(thread) {
            turns.take()
            try {
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
        val job = commandJob ?: return
        commandJob = null
        job.cancel()
        stopping = job
    }
```

with:

```kotlin
        val job = commandJob ?: return
        commandJob = null
        job.cancel()
        turns.stopped(job)
    }
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
        const val CRASH_AFTER_RESTORE_MILLIS = 120_000L

        // Section 7.3: how long a new command, a task or the settings wait for a cancelled command's job.
        const val STOPPING_MILLIS = 3_000L
```

with:

```kotlin
        const val CRASH_AFTER_RESTORE_MILLIS = 120_000L
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
        const val HOLD_LIMIT_MILLIS = 30_000L
    }
}

/** A cancelled instant command's job had not completed after 3 s; Kestrel went on without it. */
private class StuckCommandException : Exception()
```

with:

```kotlin
        const val HOLD_LIMIT_MILLIS = 30_000L
    }
}
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
            commands = commands,
            // Section 15: encrypted like every setting. Listening passes a change on at once.
```

with:

```kotlin
            commands = commands,
            turns = screenTurns,
            // Section 15: encrypted like every setting. Listening passes a change on at once.
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
    internal val phoneSignals: PhoneSignals by lazy { PhoneSignals(AndroidScreenSource(appContext), AndroidCallMonitor(appContext)) }
```

with:

```kotlin
    internal val phoneSignals: PhoneSignals by lazy { PhoneSignals(AndroidScreenSource(appContext), AndroidCallMonitor(appContext)) }

    /** The screen's turn (spec 7.3; design 7.1 of sub-project 7): the instant commands, the settings and the goals wait in it. */
    internal val screenTurns: ScreenTurns by lazy { ScreenTurns(reportFailure) }
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
import dev.kestrel.app.listen.ScreenBoostWords
```

with:

```kotlin
import dev.kestrel.app.listen.ScreenBoostWords
import dev.kestrel.app.listen.ScreenTurns
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
        callActions = callActions,
        commands = runner,
        options = options,
```

with:

```kotlin
        callActions = callActions,
        commands = runner,
        turns = ScreenTurns({ what, error -> reports += what to error }),
        options = options,
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
            commands = runner,
            options = storedOptions,
```

with:

```kotlin
            commands = runner,
            turns = ScreenTurns({ what, error -> reports += what to error }),
            options = storedOptions,
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
        // A command that was cancelled while it waited for the stopping job never runs.
```

with:

```kotlin
        // A command that was cancelled while it waited for a cancelled job that held the screen in [turns] never runs.
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
    // Section 7.3: the cancelled job is kept as the stopping job, so that nothing new acts on the screen before it has
```

with:

```kotlin
    // Section 7.3: the cancelled job holds the screen in [turns], so that nothing new acts on the screen before it has
```

- [x] **Step 6: Build and run the JVM tests**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL; the count gives A₁ + 3, and `ListeningTest`'s `aStuckCancelledCommandHoldsTheNextOneForAtMostThreeSeconds` and `aNewCommandWaitsUntilTheCancelledOneHasCompleted` pass unchanged.

Run: `scripts\gradle.ps1 --stop`

- [x] **Step 7: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/listen/ScreenTurns.kt app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt app/src/main/kotlin/dev/kestrel/app/AppGraph.kt app/src/test/kotlin/dev/kestrel/app/listen/ScreenTurnsTest.kt app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt
git commit -m "refactor(listen): the screen's turn moves into ScreenTurns, which the goals of plan 7B's GoalRunner will wait in too (plan 7D)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: The switch-over: the voice path gives the conversation its inputs, and the task path goes

The one change of design 14.2 item 4: after it, every command, answer, stop and Kestrel's own request that the voice hears reaches `ConversationEngine` as an `OwnerInput`, and nothing of today's task path is left. `Rules` loses every task state, event and effect: it keeps what the voice reads of the conversation in its state (`VoiceView`, which the app graph sends only when it changes, never with the records; `Event.ConversationChanged`), reads the goal's phase and the open question from it, and gives the conversation `InputIntent`s (`Effect.Converse`), with D9 and plan 7B's decision 10 as its rules. `VoiceChannel` is the engine's voice channel: it speaks through the Orchestrator (`Listening.voicePath`), asks the conversation's questions with the Orchestrator's answer waits (`Event.ChannelAsks`, whose wait ends as `ChannelAnswer.Silence`, and `Event.ChannelWithdraws`, which ends the wait when the question ends otherwise), and keeps the card, the other app's audio and the hand-overs that end by themselves of today's `AppOwnerPort`, through small ports (`OwnerScreen`, `OtherAudio`, `HandOverSignals`, `PermissionDialog`, and `TouchWatch` for the goal's watcher), which `AndroidOwnerSupport` implements for both channels. `VoiceAnswers` reads a spoken answer as the `ChannelAnswer` of the question's kind; "Delete the history?" (plan 7B's `QuestionKind.DELETE_HISTORY`, "What plan 7B changes for this plan") takes only "yes". `AppGoalEnvironment` builds each round's ports as `AppAgentEnvironment` and `AgentRunner` did, with `RoundModels` (the model choice of design D28 of sub-project 5), `GoalOwner` (the touch watcher around the conversation's owner port) and `RoundEvidences` (what the debug hooks read of each round). `ConversationSignals` gives the conversation the lock and the calls through Task 1's `PhoneSignals`, with the call backstop. The app graph wires them as design 5.6 of sub-project 7 says, gives the engine the stored settings as a cold flow, so that its restore never decides with the defaults, and the debug hooks read the engine instead of `AgentRunner`. `:tools:eval` counts a round that could not start as a refusal also when it ends as `NO_SCREEN`.

The deleted JVM tests and their successors (design 14.3 of sub-project 7: every rule keeps a test):

| Deleted | Its rule's test now |
|---|---|
| `AgentRunnerTest`: a task runs, a task that cannot start, cancel, a replaced task's report, the effects that steer the owner port, a pause before the owner port, a failed close, remember, forget, "save that as", delete history | plan 7B's `GoalRunnerTest`, `ConversationEngineTest`, `LocalRequestsTest` and `ConversationMachineTest` |
| `AgentRunnerTest`: the release order (an earlier task's release never stops the next task's touch watcher) | plan 7B's `GoalRunnerTest.aRoundReleasesBeforeItsEndReachesTheListener` (`2113f5a0`) |
| `RulesTest.deleteHistoryAsksAndOnlyYesDeletes`: only "yes" deletes the history | `VoiceAnswersTest.continueOrGoOnNeverDeletesTheHistory`, and plan 7B's `LocalRequestsTest` with `QuestionKind.DELETE_HISTORY` (item 2) |
| `AgentRunnerTest`: a debug build runs only with a script or in a live sitting | `RoundModelsTest.aDebugBuildRunsOnlyWithAScriptOrInALiveSitting` |
| `AgentRunnerTest`: an outgoing call right after an action, a call that cannot be ended | `ConversationSignalsTest.aCallThatBecomesActiveRightAfterTheAgentsActionEndsTheGoalAndKestrelEndsIt`, `CallBackstopTest.kestrelEndsTheAgentsCallOnlyWithThePermission`, plan 7B's `GoalRulesTest.aCallThatTheAgentStartedEndsTheGoal` |
| `AgentRunnerTest`: the owner's touch and a late echo, the touches and signals of a task's end, the witness | plan 7B's `GoalRulesTest` (a touch interrupts), `RoundEvidencesTest`, `GoalOwnerTest.everyQuestionClearsTheWatchersCreditsFirstAndKestrelsWordsGoToTheWitness` |
| `AppOwnerPortTest`: a question and its answer, a pause that withdraws it, the gate, a hand-over or a permission request that a pause met | plan 7B's `ConversationOwnerTest` |
| `AppOwnerPortTest`: the card and the voice, the other app's audio, the screenshot question, the hand-over's end by itself, Kestrel's own permission, a cancelled question lets the bar go, Kestrel's own screen | `VoiceChannelTest` |
| `AppOwnerPortTest`: the access hand-over's end by itself | `OwnerSupportTest.theAccessHandOverEndsByItselfOnlyOnceAndroidsPageHasLeftTheFront` |
| `AppOwnerPortTest`: an open confirmation's category and an open question's kind | plan 7B's `ConversationOwnerTest`, and `AgentHookAnswersTest.theQuestionAndTheAskingKeepSubProjectFivesWords` |
| `AppAgentEnvironmentTest` (2) | `AppGoalEnvironmentTest` (the same 2) |
| `CallBackstopTest`: the pauses and the hand-overs (5) | `ConversationSignalsTest`, `OwnerSupportTest` (the two call hand-overs) |
| `RulesTest`'s 25 deleted cases | as plan 7B's Task 7 table says, with `VoiceAnswersTest` for the spoken answers and `VoiceChannelTest` for what the voice says |
| `OrchestratorTest.aTaskThatCannotStartSaysSo`, `aPauseEndsAfterThirtyMinutesAndTouchesAnswerOneSecondAfterTheQuestion` | plan 7B's `GoalRunnerTest` (a round that cannot start) and `GoalRulesTest` (the holds of 30 minutes; a touch never answers "Continue the goal?") |
| `ListeningTest`: a task stopped or paused while it waits for a cancelled command, a deferred task that throws | `ScreenTurnsTest` (Task 2) and plan 7B's `GoalRunnerTest` (the screen's turn before a round) |
| The lock's pause and "Continue the task?" on the device, which today's agent tests do not cover | the new device test `GoalPauseTest`, with listening on and off |

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/orchestrator/VoiceAnswers.kt`, `app/src/main/kotlin/dev/kestrel/app/orchestrator/VoiceView.kt`, `app/src/main/kotlin/dev/kestrel/app/listen/VoicePath.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/StoredSettings.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/InputIds.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/OwnerSupport.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/ConversationSignals.kt`, `app/src/main/kotlin/dev/kestrel/app/agent/GoalOwner.kt`, `app/src/main/kotlin/dev/kestrel/app/agent/RoundModels.kt`, `app/src/main/kotlin/dev/kestrel/app/agent/RoundEvidences.kt`, `app/src/main/kotlin/dev/kestrel/app/agent/AppGoalEnvironment.kt`, `app/src/main/kotlin/dev/kestrel/app/agent/AppModels.kt`, `app/src/main/kotlin/dev/kestrel/app/agent/Permissions.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/StandInRound.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt` (whole), `app/src/main/kotlin/dev/kestrel/app/orchestrator/Orchestrator.kt` (whole), `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt`, `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, `app/src/main/kotlin/dev/kestrel/app/phone/PhoneSignals.kt`, `app/src/main/kotlin/dev/kestrel/app/agent/CallBackstop.kt` (whole), `app/src/main/kotlin/dev/kestrel/app/agent/AgentTexts.kt`, `app/src/main/kotlin/dev/kestrel/app/agent/TaskWitness.kt`, `app/src/main/kotlin/dev/kestrel/app/agent/PermissionActivity.kt`, `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, `app/src/main/kotlin/dev/kestrel/app/KestrelApplication.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/CommandTags.kt` (whole), `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHookAnswers.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/DebugTaskWitness.kt`; `tools/eval/src/main/kotlin/dev/kestrel/eval/run/Runner.kt`, `tools/eval/src/main/kotlin/dev/kestrel/eval/run/Refusals.kt`; the descriptions of `voice-task` in `scripts/hook.ps1` and `scripts/a71-session.ps1`; `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationView.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/ConversationMachine.kt` (review of Task 3: M1); `scripts/lib/AgentChecks.ps1` (review of Task 3: M2)
- Delete: `app/src/main/kotlin/dev/kestrel/app/orchestrator/TaskAnswers.kt`, `app/src/main/kotlin/dev/kestrel/app/agent/AgentRunner.kt`, `app/src/main/kotlin/dev/kestrel/app/agent/AppOwnerPort.kt`, `app/src/main/kotlin/dev/kestrel/app/agent/OwnerParts.kt`, `app/src/main/kotlin/dev/kestrel/app/agent/AgentEnvironment.kt`, `app/src/main/kotlin/dev/kestrel/app/agent/AppAgentEnvironment.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/TaskOptions.kt`, `app/src/test/kotlin/dev/kestrel/app/agent/AgentRunnerTest.kt`, `app/src/test/kotlin/dev/kestrel/app/agent/AppOwnerPortTest.kt`, `app/src/test/kotlin/dev/kestrel/app/agent/AppAgentEnvironmentTest.kt`
- Test: create `app/src/test/kotlin/dev/kestrel/app/orchestrator/VoiceAnswersTest.kt`, `app/src/test/kotlin/dev/kestrel/app/conversation/VoiceChannelTest.kt`, `app/src/test/kotlin/dev/kestrel/app/conversation/OwnerSupportTest.kt`, `app/src/test/kotlin/dev/kestrel/app/conversation/ConversationSignalsTest.kt`, `app/src/test/kotlin/dev/kestrel/app/agent/GoalOwnerTest.kt`, `app/src/test/kotlin/dev/kestrel/app/agent/RoundModelsTest.kt`, `app/src/test/kotlin/dev/kestrel/app/agent/RoundEvidencesTest.kt`, `app/src/test/kotlin/dev/kestrel/app/agent/AppGoalEnvironmentTest.kt`, `app/src/test/kotlin/dev/kestrel/app/orchestrator/VoiceViewTest.kt`, `app/src/test/kotlin/dev/kestrel/app/conversation/StoredSettingsTest.kt`, `app/src/testDebug/kotlin/dev/kestrel/app/debug/StandInRoundTest.kt`, the device test `app/src/androidTest/kotlin/dev/kestrel/app/agent/GoalPauseTest.kt` with its model `app/src/androidTest/kotlin/dev/kestrel/app/agent/HeldModel.kt`; modify `RulesTest.kt`, `OrchestratorTest.kt`, `ListeningTest.kt`, `PhoneSignalsTest.kt`, `CallBackstopTest.kt` (whole), `AgentTextsTest.kt`, and in `app/src/testDebug/kotlin/dev/kestrel/app/debug/` `CommandTagsTest.kt`, `AgentHookAnswersTest.kt`, `HookRulesTest.kt`, `DebugTaskWitnessTest.kt`; `tools/eval/src/test/kotlin/dev/kestrel/eval/run/RunnerTest.kt`; `core/conversation/src/test/kotlin/dev/kestrel/conversation/machine/ConversationMachineTest.kt`, `core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt`; `scripts/tests/AgentChecks.Tests.ps1`; the device tests `app/src/androidTest/kotlin/dev/kestrel/app/agent/AgentTests.kt`, `AgentEndToEndTest.kt`, `NotificationListenerTest.kt`, `PermissionFlowTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/debug/AgentHooksTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/voice/SessionHookTest.kt`, `VoiceHookTest.kt`, `ConversationTest.kt`

**Interfaces:**
- Consumes: plan 7B with its changes of `2113f5a0` (`QuestionKind.DELETE_HISTORY`, and a round's release before its end reaches the listener), and its "Interfaces for 7C and 7D" (`ConversationEngine` and its `view`, `owner`, `outcome`, `submit`, `signal`, `awaitingCommand`, `endQuietTime`, `restore`; `ConversationView`; `OwnerChannel`, `ChannelOutput`, `ChannelQuestion`, `QuestionKind`; `OwnerInput`, `InputIntent`, `ChannelAnswer`, `InputChannel`, `GoalPhase`, `PhoneSignal`; `GoalEnvironment`, `RoundStart`, `RoundSetup`, `RoundPurpose`; `GoalRunner`; `LocalRequests`; `HistorySearch`; `ConversationEvent`, `ConversationState`, `ConversationEffect`, `MachineStep`, `QuietClock`, `HoldReason`; `ConversationSettings`); plan 7C's `AppGraph.conversationStore`, `AndroidConversationClock`, `SettingKeys.CONVERSATIONS`; plan 7A's `OwnerPort.leaveKestrel()`, `OwnerPort.KESTREL_LEAVE_MILLIS`, `AgentPorts(…, goal, history)`; Tasks 1 and 2.
- Produces (package `dev.kestrel.app.orchestrator`): `data class VoiceQuestion(number: Int, kind: QuestionKind)`; `State` without `task`, `cancelled`, `taskPhase`, `taskAsking`, `handOver`, `pause`, `continueAsked`, `touchesArmed`, `pauseExpired`, `heldReport`, `offAfterTask`, `deleteAsking`, and with `conversation: VoiceView`, `voiceQuestion: VoiceQuestion?`, `voiceAnswers`, `answered: Int?`, `offAfterGoal`, `goal: GoalPhase?`, `goalWorks`, `openQuestion: VoiceQuestion?`; `Work` without `RUNNING_TASK`; no `TaskPhase`, `TaskAsk` or `PauseCause`; `data class VoiceView(open, goal: GoalPhase?, asking: QuestionKind?, question: Int?, replying)` with `VoiceView.of(view: ConversationView)` and `fun Flow<ConversationView>.forVoice(): Flow<VoiceView>`; `Event.ConversationChanged(view: VoiceView)`, `ChannelSays(reply: Int, text)`, `ChannelShows(text)`, `ChannelAsks(question: Int, text, kind: QuestionKind, voice = true)`, `ChannelHandsOver(text)`, `ChannelWithdraws(question: Int)` in place of the task's events; `Reply.Channel(reply: Int, text)`; `Feedback.ConversationText(text, keep = false)`; `Effect.Converse(intent: InputIntent, heard: Event.Heard? = null)` and `Effect.EndReply(reply: Int)` in place of the task's effects; `Requests.intent(request: Request): InputIntent?`; `internal object VoiceAnswers { fun read(kind: QuestionKind, text: String, named: Boolean, request: Request): ChannelAnswer? }`; `Orchestrator` without `handOverMillis`, `pauseMillis`, `armMillis` and `taskFailed`.
- Produces (package `dev.kestrel.app.listen`): `internal interface VoicePath { val ready: StateFlow<Boolean>; val speechStops: Long; suspend fun say(text: String); fun show(text: String); fun ask(question: Int, text: String, kind: QuestionKind, voice: Boolean); fun handOver(text: String); fun withdraw(question: Int) }`; `Listening.voicePath: VoicePath`, `Listening.converse: (OwnerInput) -> Unit`, `Listening.awaitingCommand: (Boolean) -> Unit`, the debug seam `Listening.inputs: ((Event.Heard?, OwnerInput) -> OwnerInput)?`; `Listening` without `runTask`, `steerTask`, `runMemory` and `taskOptions`; `ListeningTexts` without the task's and the history's sentences, which plan 7B's `ConversationTexts` holds.
- Produces (package `dev.kestrel.app.conversation`): `internal object InputIds { fun next(): Long }`; the ports `internal interface TouchWatch { fun forgetOwnActions(); suspend fun watch() }`, `OwnerScreen { suspend fun card(question): Boolean?; suspend fun releaseBar(); suspend fun kestrelInFront(): Boolean }`, `OtherAudio { fun playing(): Boolean; suspend fun pause() }`, `HandOverSignals { suspend fun opened(kind); suspend fun endedByItself(kind) }` and `fun interface PermissionDialog { suspend fun ask(permissions): PermissionEnd }`, and `AndroidOwnerSupport(context, session, open, calls: () -> Flow<CallState>) : TouchWatch, OwnerScreen, OtherAudio, HandOverSignals`; `storedConversationSettings(stored: Flow<ConversationSettings>, report): Flow<ConversationSettings>`; `awaitAccessGiven(connected, page, front)`, `awaitOutgoingCall(calls: Flow<CallState>)`; `internal class VoiceChannel(voice: VoicePath, screen: OwnerScreen, audio: OtherAudio, handOvers: HandOverSignals, permissions: PermissionDialog, audioSettleMillis = AUDIO_SETTLE_MILLIS) : OwnerChannel` with `AUDIO_PAUSED`, `AUDIO_SETTLE_MILLIS`, `KESTREL_POLL_MILLIS`; `internal class ConversationSignals(phone: PhoneSignals, backstop: CallBackstop, scope: CoroutineScope, signal: (PhoneSignal) -> Unit, report)` with `suspend fun follow(views: Flow<ConversationView>)`.
- Produces (package `dev.kestrel.app.agent`): `internal class GoalOwner(owner: OwnerPort, watcher: TouchWatch, witness: TaskWitness) : OwnerPort`; `internal class RoundModels(debuggable, liveSession, profile)` with `Choice.Model(port)`, `Choice.Refused(sentence, end)`, `modelOverride`, `suspend fun choose(options): Choice`, `MODEL`, `LIVE`; `internal class RoundEvidences` with `Evidence(touches, signals, witness: TaskEvidence)`, `Round.touched(signal)`, `start(command, witness)`, `of(command)`, `MAX = 16`; `internal class AppGoalEnvironment(…) : GoalEnvironment` with `evidences`, `selfChatAliases`, `witnesses`, `standIn: (suspend (RoundStart) -> RoundSetup)?` and `taskId(command)`; `startWatcherOrStop` (moved); `AppModels`, `AndroidApps`, `AndroidAgentClock` (moved unchanged); `Permissions` (moved unchanged); `CallBackstop(calls, clock)` with `acted()`, `forget()`, `stops(): Boolean`, `endCall()` and `WINDOW_MILLIS`; `PhoneSignals.callStates(): Flow<CallState>`.
- Produces (`AppGraph`): `roundModels: RoundModels`, `goalEnvironment: AppGoalEnvironment`, `conversations: ConversationEngine`, the debug seam `conversationSteps: ((ConversationEvent, ConversationState, MachineStep) -> Unit)?`; no `agent`. `KestrelApplication` restores the conversation at the process start.
- Produces (`TaskWitness`): `said`, `watch` and `evidence` without `ended`; `TaskEvidence(said, markerInData)` without `spoken`.
- Produces (debug): `CommandTags.Role.CONVERSE`, `CommandTags.input(heard, input)`, `CommandTags.onConversationStep(event, state, step)`, `CommandTags.lastGoalWords`; `VoiceHooks.install(graph)`; `AgentHookAnswers.Ended(report, spoken, touches, signals, evidence)`, `AgentHookAnswers.asking(kind)`, `AgentHookAnswers.question(kind: QuestionKind)`, `answerRefusal(asking: QuestionKind?, …)`; `StandInRound(seconds, reply) : GoalEnvironment`, whose finished round's final words are the reply. `:tools:eval`: `Refusals.ENDS = setOf("NO_PROFILE", "NO_SCREEN")`, and `Refusals.KINDS` without `cancelled` and `could-not-start`. Device tests: `AgentTests.run(request, model: ModelPort)`, `AgentTests.turnOff()`, `AgentTests.spoken()`; `HeldModel(model: ModelPort) : ModelPort` with `calls` and `release()`.

Counts: `:app` JVM A₂ + 4 − D, where D is the number of tests in `AgentRunnerTest`, `AppOwnerPortTest` and `AppAgentEnvironmentTest` as plans 7A to 7C left them (Step 1 counts it; 39 once plan 7A has run): `RulesTest` 124 − 25 + 1 = 100, `OrchestratorTest` 17 − 2 = 15, `ListeningTest` 147 − 3 + 2 = 146, `CallBackstopTest` 7 − 4 = 3, `CommandTagsTest` 4 + 1 = 5, `PhoneSignalsTest` 7 + 1 = 8, `DebugTaskWitnessTest` 2, and new `VoiceAnswersTest` 3, `VoiceChannelTest` 9, `OwnerSupportTest` 3, `ConversationSignalsTest` 7, `GoalOwnerTest` 2, `RoundModelsTest` 2, `RoundEvidencesTest` 1, `AppGoalEnvironmentTest` 2, `VoiceViewTest` 1, `StoredSettingsTest` 1, and in `app/src/testDebug` `StandInRoundTest` 1; `VoiceAnswersTest` has 4 of them. `:tools:eval` E₀ + 1 (`RunnerTest`). `:core:conversation` C₀ + 4 (`ConversationMachineTest` + 1, `ConversationEngineTest` + 1; review of Task 3: M1; `ConversationMachineTest` + 2, its re-check's N1), and `:core:agent` G₀, unchanged. Pester + 1 (`AgentChecks.Tests.ps1`; review of Task 3: M2). Device tests: AD₀ + 2 (`GoalPauseTest` 2; `AgentTests` is a helper, and the changed device tests keep their number).

- [ ] **Step 1: Check the plans that this task consumes, and count the tests of the files that it deletes**

Run the checker with `$env:START_TASK = '3'` and the `Select-String` check of "Before you start". Then run: `@(Select-String -Path app\src\test\kotlin\dev\kestrel\app\agent\AgentRunnerTest.kt, app\src\test\kotlin\dev\kestrel\app\agent\AppOwnerPortTest.kt, app\src\test\kotlin\dev\kestrel\app\agent\AppAgentEnvironmentTest.kt -Pattern '@Test\b').Count`
Expected: the checker clean, the matches that "Before you start" names, and a number D (39 once plan 7A has run). Note D in the task's report.

- [ ] **Step 2: Delete the task path's files**

Run: `git rm -q app/src/main/kotlin/dev/kestrel/app/orchestrator/TaskAnswers.kt app/src/main/kotlin/dev/kestrel/app/agent/AgentRunner.kt app/src/main/kotlin/dev/kestrel/app/agent/AppOwnerPort.kt app/src/main/kotlin/dev/kestrel/app/agent/OwnerParts.kt app/src/main/kotlin/dev/kestrel/app/agent/AgentEnvironment.kt app/src/main/kotlin/dev/kestrel/app/agent/AppAgentEnvironment.kt app/src/debug/kotlin/dev/kestrel/app/debug/TaskOptions.kt app/src/test/kotlin/dev/kestrel/app/agent/AgentRunnerTest.kt app/src/test/kotlin/dev/kestrel/app/agent/AppOwnerPortTest.kt app/src/test/kotlin/dev/kestrel/app/agent/AppAgentEnvironmentTest.kt`
Expected: no output; `git status --short` shows the ten files as `D`.

- [ ] **Step 3: Write the new JVM tests**

Create `app/src/test/kotlin/dev/kestrel/app/orchestrator/VoiceAnswersTest.kt`:

```kotlin
package dev.kestrel.app.orchestrator

import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.ChannelAnswer
import org.junit.Assert.assertEquals
import org.junit.Test

/** The spoken answers that the conversation's questions take (design D2 of sub-project 5; design 11 of sub-project 7). */
class VoiceAnswersTest {
    private fun read(kind: QuestionKind, text: String, named: Boolean = false): ChannelAnswer? =
        VoiceAnswers.read(kind, text, named, Requests.read(text, named) ?: Request.Other(text))

    @Test
    fun aConfirmationGoesAheadOnConfirmYesOrSendIt() {
        for (yes in listOf("Confirm.", "Yes", "Send it!")) assertEquals(yes, ChannelAnswer.Yes, read(QuestionKind.CONFIRM, yes))
        assertEquals(ChannelAnswer.Yes, read(QuestionKind.CONFIRM, "Kestrel, confirm", named = true))
        assertEquals(ChannelAnswer.No, read(QuestionKind.CONFIRM, "No"))
        assertEquals(null, read(QuestionKind.CONFIRM, "Go on."))
    }

    @Test
    fun theOtherYesOrNoQuestionsTakeYesContinueOrGoOn() {
        for (kind in listOf(QuestionKind.YES_NO, QuestionKind.CONTINUE_GOAL, QuestionKind.LIMIT, QuestionKind.SWITCH)) {
            for (yes in listOf("Yes.", "Yes, please.", "Continue", "Go on.")) assertEquals("$kind $yes", ChannelAnswer.Yes, read(kind, yes))
            assertEquals("$kind", ChannelAnswer.No, read(kind, "No."))
            assertEquals("$kind", null, read(kind, "Open Viber"))
        }
    }

    @Test
    fun continueOrGoOnNeverDeletesTheHistory() {
        assertEquals(ChannelAnswer.Yes, read(QuestionKind.DELETE_HISTORY, "Yes, please."))
        assertEquals(ChannelAnswer.No, read(QuestionKind.DELETE_HISTORY, "No."))
        for (other in listOf("Continue", "Go on.", "Confirm.", "Send it")) assertEquals(other, null, read(QuestionKind.DELETE_HISTORY, other))
        assertEquals(null, read(QuestionKind.DELETE_HISTORY, "Kestrel, continue", named = true))
    }

    @Test
    fun aFreeQuestionTakesTheWordsAndAPickANumber() {
        assertEquals(ChannelAnswer.Text("The blue one."), read(QuestionKind.FREE, "The blue one."))
        assertEquals(ChannelAnswer.Text("Yes."), read(QuestionKind.SCREENSHOT, "Yes."))
        assertEquals(ChannelAnswer.Pick(12), read(QuestionKind.PICK, "12"))
        assertEquals(null, read(QuestionKind.PICK, "the red one"))
    }
}
```

Create `app/src/test/kotlin/dev/kestrel/app/orchestrator/VoiceViewTest.kt`:

```kotlin
package dev.kestrel.app.orchestrator

import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.machine.HoldReason
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.InputChannel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the voice reads of the conversation (design 5.5 of sub-project 7). */
class VoiceViewTest {
    @Test
    fun theVoiceHearsOfAViewOnlyWhenWhatItReadsChanged() = runTest {
        val acting = ConversationView(id = ConversationId.random(), command = 1, goal = GoalPhase.ACTING)
        val views = flowOf(
            acting,
            // A goal's step and its records are not the voice's.
            acting.copy(step = 3, records = listOf(CommandRecord(1, 0, InputChannel.VOICE, "Read my latest SMS"))),
            acting.copy(goal = GoalPhase.ASKING, asking = QuestionKind.YES_NO, question = 4),
            acting.copy(goal = null, quiet = QuietClock.Held(HoldReason.REPLYING)),
            // One of Kestrel's own requests runs, which "Stop. Kestrel, …" stops first.
            ConversationView(id = acting.id, command = 2, ownRequest = true),
            ConversationView(),
        )

        assertEquals(
            listOf(
                VoiceView(open = true, goal = GoalPhase.ACTING),
                VoiceView(open = true, goal = GoalPhase.ASKING, asking = QuestionKind.YES_NO, question = 4),
                VoiceView(open = true, replying = true),
                VoiceView(open = true, ownRequest = true),
                VoiceView(),
            ),
            views.forVoice().toList(),
        )
    }
}
```

Create `app/src/test/kotlin/dev/kestrel/app/conversation/StoredSettingsTest.kt`:

```kotlin
package dev.kestrel.app.conversation

import dev.kestrel.conversation.model.ConversationSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** The settings that the conversation's restore decides with (design 13 of sub-project 7). */
class StoredSettingsTest {
    @Test
    fun theFirstSettingsAreTheStoredOnesAndAFailedReadGivesTheDefaults() = runTest {
        val failures = mutableListOf<String>()
        val report: (String, Throwable) -> Unit = { what, _ -> failures += what }
        val stored = flow {
            delay(100)
            emit(ConversationSettings(quietSeconds = 300))
        }

        assertEquals(ConversationSettings(quietSeconds = 300), storedConversationSettings(stored, report).first())
        assertEquals(ConversationSettings(), storedConversationSettings(flow { throw IOException("The disk failed") }, report).first())
        assertEquals(listOf("Reading the conversation settings"), failures)
    }
}
```

Create `app/src/test/kotlin/dev/kestrel/app/conversation/VoiceChannelTest.kt`:

```kotlin
package dev.kestrel.app.conversation

import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.app.listen.VoicePath
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.channel.ChannelOutput
import dev.kestrel.conversation.channel.ChannelQuestion
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.context.SteeringMessage
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.safety.Category
import dev.kestrel.safety.Confirmation
import dev.kestrel.safety.HandOver
import dev.kestrel.safety.HandOverKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The conversation's voice (design 11 of sub-project 7; design D2, D14, D15 and D32 items 17 and 18 of sub-project 5). */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceChannelTest {
    private val voice = FakeVoice()
    private val support = FakeSupport()
    private val channel = VoiceChannel(voice, support, support, support, support, audioSettleMillis = 500)

    @Test
    fun kestrelsWordsAndItsFinalReplyAreSpokenAndShownWordsOnlyShown() = runTest {
        channel.show(ChannelOutput.Said("Android gave Kestrel only the photos that you selected."))
        channel.show(ChannelOutput.Report(3, "Done."))
        channel.show(ChannelOutput.Shown("Got it."))

        assertEquals(listOf("Android gave Kestrel only the photos that you selected.", "Done."), voice.said)
        assertEquals(listOf("Got it."), voice.shown)
    }

    // The engine says "I got 'X' too late" at once, also while a goal's final words still play (plan 7B): each spoken
    // output waits until the one before it has ended, in the order in which they came.
    @Test
    fun spokenOutputsPlayOneAfterAnotherInTheOrderInWhichTheyCame() = runTest {
        val tooLate = ConversationTexts.cameTooLate(SteeringMessage.of("and to Ana", InputChannel.VOICE))
        voice.holds = true
        val report = async { channel.show(ChannelOutput.Report(3, "Done.")) }
        runCurrent()
        val late = async { channel.show(ChannelOutput.Said(tooLate)) }
        runCurrent()
        assertEquals(listOf("Done."), voice.said)

        voice.ends.removeFirst().complete(Unit)
        runCurrent()
        assertTrue(report.isCompleted)
        assertEquals(listOf("Done.", tooLate), voice.said)
        assertFalse(late.isCompleted)

        voice.ends.removeFirst().complete(Unit)
        runCurrent()
        assertTrue(late.isCompleted)
    }

    // Decision 10: a stop ends Kestrel's speech at once, also the spoken outputs that wait for their turn, such as the "too
    // late" sentence behind a goal's final words; only the outputs that come after the stop play.
    @Test
    fun aStopDropsTheSpokenOutputsThatWaitAndOnlyLaterOnesPlay() = runTest {
        val tooLate = ConversationTexts.cameTooLate(SteeringMessage.of("and to Ana", InputChannel.VOICE))
        voice.holds = true
        val report = async { channel.show(ChannelOutput.Report(3, "Done.")) }
        runCurrent()
        val late = async { channel.show(ChannelOutput.Said(tooLate)) }
        runCurrent()

        voice.stop()
        runCurrent()
        assertTrue(report.isCompleted)
        assertTrue(late.isCompleted)
        assertEquals(listOf("Done."), voice.said)

        val after = async { channel.show(ChannelOutput.Said("Stopped.")) }
        runCurrent()
        assertEquals(listOf("Done.", "Stopped."), voice.said)
        voice.ends.removeFirst().complete(Unit)
        runCurrent()
        assertTrue(after.isCompleted)
    }

    @Test
    fun aQuestionIsAskedWheneverAVoiceSessionOpensAndLetsTheBarGoOnceWithdrawn() = runTest {
        val asking = async { channel.ask(ChannelQuestion(4, "Which account?", QuestionKind.FREE)) }
        runCurrent()
        assertEquals(listOf(Asked(4, "Which account?", QuestionKind.FREE, voice = true)), voice.asked)

        // Kestrel turned off and on again: the question is spoken again once a session is open.
        voice.ready.value = false
        runCurrent()
        voice.ready.value = true
        runCurrent()
        assertEquals(2, voice.asked.size)
        assertFalse(asking.isCompleted)
        assertEquals(0, support.releases)

        // The spoken answer reaches the conversation as an input, which then withdraws the question: the voice's answer
        // wait ends.
        asking.cancel()
        runCurrent()
        assertEquals(1, support.releases)
        assertEquals(listOf(4), voice.withdrawn)
    }

    @Test
    fun aConfirmationShowsItsCardWhileKestrelSpeaksAndTheCardAnswersIt() = runTest {
        val confirmation = sending()
        val asking = async { channel.ask(ChannelQuestion(5, confirmation.question, QuestionKind.CONFIRM, confirmation.spoken, confirmation = confirmation)) }
        runCurrent()
        assertEquals(listOf(Asked(5, "Shall I send it to Mila?", QuestionKind.CONFIRM, voice = true)), voice.asked)
        assertEquals(listOf("Send it to Mila?"), support.cards)

        support.card.complete(true)
        runCurrent()
        assertEquals(ChannelAnswer.Yes, asking.await())
        assertEquals(1, support.releases)
    }

    @Test
    fun anotherAppsAudioLeavesOnlyTheCardUnlessKestrelPausedIt() = runTest {
        val confirmation = sending()
        val question = ChannelQuestion(5, confirmation.question, QuestionKind.CONFIRM, confirmation.spoken, confirmation = confirmation)

        // The audio goes on although Kestrel pressed pause: only the card's button counts.
        support.playing = true
        support.pauseStops = false
        val cardOnly = async { channel.ask(question) }
        advanceUntilIdle()
        assertEquals(Asked(5, "Shall I send it to Mila?", QuestionKind.CONFIRM, voice = false), voice.asked.last())
        support.card.complete(false)
        assertEquals(ChannelAnswer.No, cardOnly.await())

        // Kestrel paused it: the voice counts again, and Kestrel says so first.
        support.card = CompletableDeferred()
        support.pauseStops = true
        val voiced = async { channel.ask(question) }
        advanceUntilIdle()
        assertEquals(Asked(5, "${VoiceChannel.AUDIO_PAUSED} Shall I send it to Mila?", QuestionKind.CONFIRM, voice = true), voice.asked.last())
        voiced.cancel()

        // The screenshot question does the same, and has no card while the voice may answer.
        support.playing = true
        val screenshot = async { channel.ask(ChannelQuestion(6, "May I take a screenshot?", QuestionKind.SCREENSHOT)) }
        advanceUntilIdle()
        assertEquals(Asked(6, "${VoiceChannel.AUDIO_PAUSED} May I take a screenshot?", QuestionKind.SCREENSHOT, voice = true), voice.asked.last())
        assertEquals(2, support.cards.size)
        screenshot.cancel()
    }

    @Test
    fun aHandOverSaysItsSentenceOpensItsPageAndEndsByItselfOnlyForItsKind() = runTest {
        val handing = async { channel.handOver(HandOver.of(HandOverKind.ACCESS)) }
        runCurrent()
        assertEquals(listOf(HandOver.of(HandOverKind.ACCESS).text), voice.handOvers)
        assertEquals(listOf(HandOverKind.ACCESS), support.opened)
        assertFalse(handing.isCompleted)

        support.endedByItself.complete(Unit)
        assertEquals(HandOverEnd.DONE_BY_ITSELF, handing.await())
        assertEquals(1, support.releases)
    }

    @Test
    fun kestrelsOwnPermissionSaysWhyAndAndroidsDialogAnswersIt() = runTest {
        support.android = PermissionEnd.PARTLY

        val end = channel.askPermission(listOf("android.permission.READ_CONTACTS"), "I need your contacts to find Mila.")

        assertEquals(PermissionEnd.PARTLY, end)
        assertEquals(listOf("I need your contacts to find Mila."), voice.handOvers)
        assertEquals(listOf(listOf("android.permission.READ_CONTACTS")), support.asked)
        assertEquals(1, support.releases)
    }

    @Test
    fun leavingKestrelsOwnScreenWaitsAtMostThreeSeconds() = runTest {
        support.kestrelPolls = 2
        assertTrue(channel.leaveKestrel())

        support.kestrelPolls = Int.MAX_VALUE
        val start = currentTime
        assertFalse(channel.leaveKestrel())
        assertEquals(OwnerPort.KESTREL_LEAVE_MILLIS, currentTime - start)
    }

    private fun sending() = Confirmation("Send it to Mila?", "Shall I send it to Mila?", Category.SENDING, listOf("Mila"), "Viber", null, null, null)

    private data class Asked(val number: Int, val text: String, val kind: QuestionKind, val voice: Boolean)

    private class FakeVoice : VoicePath {
        override val ready = MutableStateFlow(true)
        val said = mutableListOf<String>()
        val shown = mutableListOf<String>()
        val asked = mutableListOf<Asked>()
        val handOvers = mutableListOf<String>()
        val withdrawn = mutableListOf<Int>()

        // While [holds], each speech lasts until the test ends it, through [ends] in order.
        var holds = false
        val ends = ArrayDeque<CompletableDeferred<Unit>>()
        override var speechStops = 0L

        // As Listening on the Orchestrator's StopSaying: the speech that plays ends, and the count goes up.
        fun stop() {
            speechStops++
            ends.removeFirstOrNull()?.complete(Unit)
        }

        override suspend fun say(text: String) {
            said += text
            if (holds) CompletableDeferred<Unit>().also(ends::addLast).await()
        }

        override fun show(text: String) {
            shown += text
        }

        override fun ask(question: Int, text: String, kind: QuestionKind, voice: Boolean) {
            asked += Asked(question, text, kind, voice)
        }

        override fun handOver(text: String) {
            handOvers += text
        }

        override fun withdraw(question: Int) {
            withdrawn += question
        }
    }

    private class FakeSupport : OwnerScreen, OtherAudio, HandOverSignals, PermissionDialog {
        var card = CompletableDeferred<Boolean?>()
        val cards = mutableListOf<String>()
        var releases = 0
        var playing = false
        var pauseStops = true
        var kestrelPolls = 0
        val opened = mutableListOf<HandOverKind>()
        val endedByItself = CompletableDeferred<Unit>()
        var android = PermissionEnd.GRANTED
        val asked = mutableListOf<List<String>>()

        override suspend fun card(question: String): Boolean? {
            cards += question
            return card.await()
        }

        override suspend fun releaseBar() {
            releases++
        }

        override suspend fun kestrelInFront(): Boolean = (kestrelPolls > 0).also { if (it) kestrelPolls-- }

        override fun playing(): Boolean = playing

        override suspend fun pause() {
            if (pauseStops) playing = false
        }

        override suspend fun opened(kind: HandOverKind) {
            opened += kind
        }

        override suspend fun endedByItself(kind: HandOverKind) {
            if (kind == HandOverKind.ACCESS) endedByItself.await() else awaitCancellation()
        }

        override suspend fun ask(permissions: List<String>): PermissionEnd {
            asked += permissions
            return android
        }
    }
}
```

Create `app/src/test/kotlin/dev/kestrel/app/conversation/OwnerSupportTest.kt`:

```kotlin
package dev.kestrel.app.conversation

import dev.kestrel.app.orchestrator.CallState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hand-overs that end by themselves (design D9 and D15 of sub-project 5), with listening on or off. */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnerSupportTest {
    @Test
    fun theAccessHandOverEndsByItselfOnlyOnceAndroidsPageHasLeftTheFront() = runTest {
        val connected = MutableStateFlow(false)
        var front: String? = SETTINGS
        val given = async { awaitAccessGiven(connected, SETTINGS) { front } }
        runCurrent()

        connected.value = true
        advanceTimeBy(1_000)
        assertFalse(given.isCompleted)

        front = "com.android.launcher3"
        advanceTimeBy(1_000)
        assertTrue(given.isCompleted)
    }

    @Test
    fun aCallHandOverEndsByItselfOnlyWhenTheOwnerPlacesTheCall() = runTest {
        val calls = MutableStateFlow(CallState.IDLE)
        val incoming = async { awaitOutgoingCall(calls) }
        runCurrent()
        calls.value = CallState.RINGING
        runCurrent()
        calls.value = CallState.ACTIVE
        runCurrent()
        assertFalse(incoming.isCompleted)
        incoming.cancel()

        calls.value = CallState.IDLE
        val placed = async { awaitOutgoingCall(calls) }
        runCurrent()
        calls.value = CallState.ACTIVE
        runCurrent()
        assertTrue(placed.isCompleted)
    }

    @Test
    fun aCallThatTheOwnerPlacesAfterAnIncomingCallEndedStillEndsTheHandOver() = runTest {
        val calls = MutableStateFlow(CallState.IDLE)
        val placed = async { awaitOutgoingCall(calls) }
        runCurrent()
        calls.value = CallState.RINGING
        runCurrent()
        calls.value = CallState.ACTIVE
        runCurrent()
        calls.value = CallState.IDLE
        runCurrent()
        assertFalse(placed.isCompleted)

        calls.value = CallState.ACTIVE
        runCurrent()
        assertTrue(placed.isCompleted)
    }

    private companion object {
        const val SETTINGS = "com.android.settings"
    }
}
```

Create `app/src/test/kotlin/dev/kestrel/app/conversation/ConversationSignalsTest.kt`:

```kotlin
package dev.kestrel.app.conversation

import dev.kestrel.app.agent.CallBackstop
import dev.kestrel.app.listen.CallApi
import dev.kestrel.app.listen.CallMonitor
import dev.kestrel.app.orchestrator.CallState
import dev.kestrel.app.phone.PhoneSignals
import dev.kestrel.app.phone.ScreenSource
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.PhoneSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The phone's lock and calls as the conversation's signals, with the call backstop (design 6.5 of sub-project 7). */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationSignalsTest {
    private val screens = FakeScreens()
    private val calls = FakeCalls()
    private val callApi = FakeCallApi()
    private var now = 0L
    private val backstop = CallBackstop(callApi) { now }
    private val signals = mutableListOf<PhoneSignal>()
    private val reports = mutableListOf<String>()
    private val views = MutableStateFlow(ConversationView())
    private val open = ConversationView(id = ConversationId.random(), command = 1)

    @Test
    fun theLockAndTheUnlockReachTheConversationOnlyWhileItIsOpen() = runTest {
        follow()
        screens.now = true
        screens.change(false)
        assertEquals(0, screens.starts)

        views.value = open
        runCurrent()
        assertEquals(1, screens.starts)
        screens.change(false)
        screens.change(false)
        screens.change(true)
        assertEquals(listOf(PhoneSignal.LOCKED, PhoneSignal.UNLOCKED), signals)

        // A closed conversation watches nothing; a lock meanwhile counts at the next watch.
        views.value = ConversationView()
        runCurrent()
        assertEquals(1, screens.stops)
        screens.now = false
        views.value = open
        runCurrent()
        assertEquals(listOf(PhoneSignal.LOCKED, PhoneSignal.UNLOCKED, PhoneSignal.LOCKED), signals)
    }

    @Test
    fun theFirstWatchSendsTheLockStateAlsoOnAPhoneWithoutALockScreen() = runTest {
        // A phone without a lock screen, as kestrel-api37, reads as unlocked from the start; the conversation still learns it
        // (plan 7B: until a lock signal comes, the owner is not known to be there).
        follow()
        views.value = open
        runCurrent()
        assertEquals(listOf(PhoneSignal.UNLOCKED), signals)

        // A later watch sends only a change since the conversation's last reading.
        views.value = ConversationView()
        runCurrent()
        views.value = open
        runCurrent()
        assertEquals(listOf(PhoneSignal.UNLOCKED), signals)
    }

    @Test
    fun wordsKeptForTheOwnerKeepTheWatchAfterTheConversationClosed() = runTest {
        follow()
        screens.now = false
        views.value = open
        runCurrent()

        // A change of test mode closed the conversation while the phone was locked; its goal's final reply waits.
        views.value = ConversationView(keptForOwner = true)
        runCurrent()
        assertEquals(0, screens.stops)
        screens.change(true)
        assertEquals(listOf(PhoneSignal.LOCKED, PhoneSignal.UNLOCKED), signals)

        views.value = ConversationView()
        runCurrent()
        assertEquals(1, screens.stops)
    }

    @Test
    fun theScreenComingOnReachesTheConversationWhileItWatches() = runTest {
        follow()
        screens.now = false
        screens.comeOn()
        views.value = open
        runCurrent()

        // Locked or not, the engine then ends the timers whose deadlines passed while the phone slept (plan 7B).
        screens.comeOn()
        assertEquals(listOf(PhoneSignal.LOCKED, PhoneSignal.SCREEN_ON), signals)
    }

    @Test
    fun callsReachTheConversationOnceEachAndAGoalKeepsTheWatch() = runTest {
        follow()
        views.value = ConversationView(goal = GoalPhase.HANDED_OVER)
        runCurrent()

        calls.change(CallState.IDLE)
        calls.change(CallState.RINGING)
        calls.change(CallState.ACTIVE)
        calls.change(CallState.ACTIVE)
        calls.change(CallState.IDLE)

        assertEquals(listOf(PhoneSignal.UNLOCKED, PhoneSignal.RINGING, PhoneSignal.CALL_ACTIVE, PhoneSignal.CALL_IDLE), signals)
    }

    @Test
    fun aCallThatBecomesActiveRightAfterTheAgentsActionEndsTheGoalAndKestrelEndsIt() = runTest {
        follow()
        views.value = open.copy(goal = GoalPhase.ACTING)
        runCurrent()
        backstop.acted()
        now = 1_000

        calls.change(CallState.ACTIVE)
        runCurrent()

        assertEquals(listOf(PhoneSignal.UNLOCKED, PhoneSignal.AGENT_CALL), signals)
        assertEquals(1, callApi.ended)
    }

    @Test
    fun aCallThatRangALateOneOrOneDuringAHandOverIsTheOwners() = runTest {
        follow()
        views.value = open.copy(goal = GoalPhase.ACTING)
        runCurrent()
        backstop.acted()
        now = 1_000
        calls.change(CallState.RINGING)
        calls.change(CallState.ACTIVE)
        calls.change(CallState.IDLE)

        now = 7_000
        calls.change(CallState.ACTIVE)
        calls.change(CallState.IDLE)

        views.value = open.copy(goal = GoalPhase.HANDED_OVER)
        runCurrent()
        backstop.acted()
        calls.change(CallState.ACTIVE)
        runCurrent()

        assertEquals(
            listOf(
                PhoneSignal.UNLOCKED, PhoneSignal.RINGING, PhoneSignal.CALL_ACTIVE, PhoneSignal.CALL_IDLE,
                PhoneSignal.CALL_ACTIVE, PhoneSignal.CALL_IDLE, PhoneSignal.CALL_ACTIVE,
            ),
            signals,
        )
        assertEquals(0, callApi.ended)
    }

    private fun kotlinx.coroutines.test.TestScope.follow() {
        val conversationSignals = ConversationSignals(PhoneSignals(screens, calls), backstop, backgroundScope, { signals += it }) { what, _ -> reports += what }
        backgroundScope.launch { conversationSignals.follow(views) }
        runCurrent()
    }

    private class FakeScreens : ScreenSource {
        var now = true
        var starts = 0
        var stops = 0
        private var report: ((Boolean) -> Unit)? = null
        private var cameOn: (() -> Unit)? = null

        override fun start(report: (Boolean) -> Unit, cameOn: () -> Unit) {
            starts++
            this.report = report
            this.cameOn = cameOn
        }

        override fun stop() {
            stops++
            report = null
            cameOn = null
        }

        override fun read(reading: (Boolean) -> Unit) = reading(now)

        fun change(on: Boolean) {
            now = on
            report?.invoke(on)
        }

        fun comeOn() {
            cameOn?.invoke()
        }
    }

    private class FakeCalls : CallMonitor {
        private var report: ((CallState) -> Unit)? = null

        override fun start(report: (CallState) -> Unit) {
            this.report = report
        }

        override fun stop() {
            report = null
        }

        fun change(state: CallState) {
            report?.invoke(state)
        }
    }

    private class FakeCallApi : CallApi {
        var ended = 0

        override fun allowed(): Boolean = true

        override fun answer() = Unit

        override fun end(): Boolean {
            ended++
            return true
        }

        override fun active(): Boolean = false

        override fun showInCallScreen() = Unit
    }
}
```

Create `app/src/test/kotlin/dev/kestrel/app/agent/GoalOwnerTest.kt`:

```kotlin
package dev.kestrel.app.agent

import dev.kestrel.agent.port.AnswerKind
import dev.kestrel.agent.port.DataPort
import dev.kestrel.agent.port.Gate
import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.testing.FakeOwner
import dev.kestrel.app.conversation.TouchWatch
import dev.kestrel.safety.HandOver
import dev.kestrel.safety.HandOverKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The owner as a goal's round talks to him, with the goal's touch watcher around it (design D19 of sub-project 5). */
class GoalOwnerTest {
    private val owner = FakeOwner()
    private val watcher = CountingWatch()
    private val said = mutableListOf<String>()
    private val witness = object : TaskWitness {
        override fun said(text: String) {
            said += text
        }

        override fun watch(data: DataPort): DataPort = data

        override val evidence: TaskEvidence get() = TaskEvidence(said.toList())
    }
    private val goalOwner = GoalOwner(owner, watcher, witness)

    @Test
    fun everyQuestionClearsTheWatchersCreditsFirstAndKestrelsWordsGoToTheWitness() = runTest {
        goalOwner.say("Android gave Kestrel only the photos that you selected.")
        goalOwner.ask("Which one?", AnswerKind.FREE)

        assertEquals(listOf("Android gave Kestrel only the photos that you selected."), said)
        assertEquals(1, watcher.forgets)
        assertEquals(0, watcher.watches)
    }

    @Test
    fun aHandOverAPermissionRequestAndAResumedGateStartTheWatcherAgain() = runTest {
        owner.handOverEnds.addLast(HandOverEnd.CONTINUED)
        assertEquals(HandOverEnd.CONTINUED, goalOwner.handOver(HandOver.of(HandOverKind.ACCESS)))
        goalOwner.askPermission(listOf("android.permission.READ_CONTACTS"), "I need your contacts.")
        assertEquals(2, watcher.watches)

        owner.gates.addLast(Gate.GO)
        owner.gates.addLast(Gate.RESUMED)
        goalOwner.gate()
        assertEquals(2, watcher.watches)
        goalOwner.gate()
        assertEquals(3, watcher.watches)
    }

    private class CountingWatch : TouchWatch {
        var forgets = 0
        var watches = 0

        override fun forgetOwnActions() {
            forgets++
        }

        override suspend fun watch() {
            watches++
        }
    }
}
```

Create `app/src/test/kotlin/dev/kestrel/app/agent/RoundModelsTest.kt`:

```kotlin
package dev.kestrel.app.agent

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.port.ModelPort
import dev.kestrel.agent.testing.ScriptedModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** How a round's model is chosen (design D28 of sub-project 5; plan 7D). */
class RoundModelsTest {
    private val profileModel = ScriptedModel(emptyList())
    private val asked = mutableListOf<String?>()
    private var live = false
    private var profile: ModelPort? = profileModel

    private fun models(debuggable: Boolean) = RoundModels(debuggable, { live }) { lightweight ->
        asked += lightweight
        profile
    }

    @Test
    fun aReleaseBuildUsesTheDefaultProfileOrSaysThatNoneIsSetUp() = runTest {
        val release = models(debuggable = false)

        assertSame(profileModel, (release.choose(mapOf(RoundModels.MODEL to "gpt-5-mini")) as RoundModels.Choice.Model).port)
        assertEquals(listOf<String?>(null), asked)

        profile = null
        assertEquals(RoundModels.Choice.Refused(AgentTexts.NO_PROFILE, EndReason.NO_PROFILE), release.choose(emptyMap()))
    }

    @Test
    fun aDebugBuildRunsOnlyWithAScriptOrInALiveSitting() = runTest {
        val debug = models(debuggable = true)
        val refused = RoundModels.Choice.Refused(AgentTexts.couldNotStart(AgentTexts.TEST_BUILD), EndReason.NO_PROFILE)

        assertEquals(refused, debug.choose(emptyMap()))
        // The hook's extra live counts only while a live sitting is open.
        assertEquals(refused, debug.choose(mapOf(RoundModels.LIVE to "true")))

        live = true
        val chosen = debug.choose(mapOf(RoundModels.LIVE to "true", RoundModels.MODEL to "gpt-5-mini")) as RoundModels.Choice.Model
        assertSame(profileModel, chosen.port)
        assertEquals(listOf<String?>("gpt-5-mini"), asked)

        val script = ScriptedModel(emptyList())
        debug.modelOverride = script
        assertSame(script, (debug.choose(emptyMap()) as RoundModels.Choice.Model).port)
    }
}
```

Create `app/src/test/kotlin/dev/kestrel/app/agent/RoundEvidencesTest.kt`:

```kotlin
package dev.kestrel.app.agent

import dev.kestrel.agent.port.DataPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the debug hooks read of the newest rounds (plan 6B-basefix; design 14.4 of sub-project 7). */
class RoundEvidencesTest {
    @Test
    fun eachRoundCountsItsTouchesAndItsSignalsAndTheNewestSixteenStay() {
        val evidences = RoundEvidences()
        val witness = object : TaskWitness {
            override fun said(text: String) = Unit

            override fun watch(data: DataPort): DataPort = data

            override val evidence: TaskEvidence get() = TaskEvidence(listOf("Done."), markerInData = true)
        }

        val round = evidences.start(1, witness)
        round.touched(signal = false)
        round.touched(signal = true)

        assertEquals(RoundEvidences.Evidence(2, 1, TaskEvidence(listOf("Done."), markerInData = true)), evidences.of(1))
        for (command in 2..RoundEvidences.MAX + 1) evidences.start(command, TaskWitness.NONE)
        assertNull(evidences.of(1))
        assertEquals(RoundEvidences.Evidence(), evidences.of(RoundEvidences.MAX + 1))
    }
}
```

Create `app/src/test/kotlin/dev/kestrel/app/agent/AppGoalEnvironmentTest.kt`:

```kotlin
package dev.kestrel.app.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The touch watcher of a goal's round (design D19 of sub-project 5), which AppGoalEnvironment starts (plan 7D). */
@OptIn(ExperimentalCoroutinesApi::class)
class AppGoalEnvironmentTest {
    @Test
    fun aRoundThatIsCancelledWhileTheWatcherStartsStopsTheWatcherAgain() = runTest {
        var stopped = 0
        val adding = CompletableDeferred<Boolean>()
        val start = launch {
            startWatcherOrStop(start = { adding.await() }, stop = { delay(10); stopped++ })
        }
        runCurrent()

        start.cancel()
        runCurrent()
        testScheduler.advanceUntilIdle()

        assertTrue(start.isCancelled)
        assertEquals(1, stopped)
    }

    @Test
    fun aStartThatThrowsStopsTheWatcherAndRethrows() = runTest {
        var stopped = 0

        val thrown = runCatching {
            startWatcherOrStop(start = { throw IllegalStateException() }, stop = { stopped++ })
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertEquals(1, stopped)
    }
}
```

Replace the whole `app/src/test/kotlin/dev/kestrel/app/agent/CallBackstopTest.kt` with:

```kotlin
package dev.kestrel.app.agent

import dev.kestrel.app.listen.CallApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The backstop against a call that the agent placed (design D13 of sub-project 5; plan 7D). */
class CallBackstopTest {
    private var now = 0L
    private val calls = FakeCalls()
    private val backstop = CallBackstop(calls) { now }

    @Test
    fun aCallWithinFiveSecondsOfTheAgentsLastActionIsTheAgents() {
        assertFalse(backstop.stops())

        backstop.acted()
        now = 5_000
        assertTrue(backstop.stops())
        now = 5_001
        assertFalse(backstop.stops())
    }

    @Test
    fun anActionOfAnEarlierRoundNeverCounts() {
        backstop.acted()
        backstop.forget()
        now = 1_000

        assertFalse(backstop.stops())
    }

    @Test
    fun kestrelEndsTheAgentsCallOnlyWithThePermission() {
        backstop.endCall()
        assertEquals(1, calls.ended)

        calls.allowed = false
        backstop.endCall()
        assertEquals(1, calls.ended)

        // The owner took the permission back meanwhile: the goal stops all the same.
        calls.allowed = true
        calls.refused = true
        backstop.endCall()
        assertEquals(1, calls.ended)
    }

    private class FakeCalls : CallApi {
        var allowed = true
        var refused = false
        var ended = 0

        override fun allowed(): Boolean = allowed

        override fun answer() = Unit

        override fun end(): Boolean {
            if (refused) throw SecurityException("No permission for calls")
            ended++
            return true
        }

        override fun active(): Boolean = false

        override fun showInCallScreen() = Unit
    }
}
```

In `app/src/test/kotlin/dev/kestrel/app/phone/PhoneSignalsTest.kt`, replace:

```kotlin
import dev.kestrel.app.orchestrator.CallState
```

with:

```kotlin
import dev.kestrel.app.orchestrator.CallState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
```

In `app/src/test/kotlin/dev/kestrel/app/phone/PhoneSignalsTest.kt`, replace:

```kotlin
/** One screen receiver and one call listener for every watcher (design 5.5 and 6.5 of sub-project 7; plan 7D). */
```

with:

```kotlin
/** One screen receiver and one call listener for every watcher (design 5.5 and 6.5 of sub-project 7; plan 7D). */
@OptIn(ExperimentalCoroutinesApi::class)
```

In `app/src/test/kotlin/dev/kestrel/app/phone/PhoneSignalsTest.kt`, replace:

```kotlin
    }

    private class FakeScreens : ScreenSource {
```

with:

```kotlin
    }

    @Test
    fun theCallStatesFlowWatchesTheCallsWhileItIsCollected() = runTest {
        val seen = mutableListOf<CallState>()
        val collecting = backgroundScope.launch { phone.callStates().collect { seen += it } }
        runCurrent()
        assertEquals(1, calls.starts)

        calls.change(CallState.RINGING)
        runCurrent()
        assertEquals(listOf(CallState.RINGING), seen)

        collecting.cancel()
        runCurrent()
        assertEquals(1, calls.stops)
    }

    private class FakeScreens : ScreenSource {
```

`:tools:eval` counts a round that could not start as a refusal by its end, `NO_PROFILE` or `NO_SCREEN` (decision 7), so that an injection fixture never passes when nothing ran.

In `tools/eval/src/test/kotlin/dev/kestrel/eval/run/RunnerTest.kt`, replace:

```kotlin
    @Test
    fun aLostTranscriptIsReadBackAndNeverSentTwice() {
```

with:

```kotlin
    @Test
    fun aRoundWithoutKestrelsServiceIsARefusalToo() {
        run.adb.hook("AGENT_TASK") { extras ->
            when (extras["marker"]) {
                null -> "state=done end=NO_SCREEN confirmed=0 unconfirmed=0 beforeTestMode=0"
                "accessibility service is off" -> "state=done marker=1"
                else -> "state=done marker=0"
            }
        }

        val line = runner().run(task(rule = VerdictRule.Injection, kind = TaskKind.INJECTION), "[]", 1, "maple heron")

        assertEquals(Reason.END, line.reason)
        assertEquals(RunResult.FAIL, line.result)
        assertTrue(run.console.warnings.contains("Task stand-in-hotspot, run 1: Kestrel refused the task before it ran (no-service)."))
    }

    @Test
    fun aLostTranscriptIsReadBackAndNeverSentTwice() {
```

The review of Task 3 asked for three fixes, each with its tests (I1, M1 and M2): `voice-task`'s stand-in round answers with its whole reply as the goal's final words; "Stop. Kestrel, …" stops one of Kestrel's own requests while it asks or runs, and the command after it runs; and `Send-OwnTaskAnswer` waits for the question of Kestrel's own request through `agent-state -Tag`.

Create `app/src/testDebug/kotlin/dev/kestrel/app/debug/StandInRoundTest.kt`:

```kotlin
package dev.kestrel.app.debug

import dev.kestrel.agent.Agent
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.TaskRequest
import dev.kestrel.agent.goal.NoGoal
import dev.kestrel.agent.goal.NoHistory
import dev.kestrel.agent.testing.FakeOwner
import dev.kestrel.conversation.goal.RoundPurpose
import dev.kestrel.conversation.goal.RoundSetup
import dev.kestrel.conversation.goal.RoundStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The A71 session's stand-in round (`voice-task`; design 14.4 of sub-project 7). */
@OptIn(ExperimentalCoroutinesApi::class)
class StandInRoundTest {
    @Test
    fun aStandInRoundTakesItsSecondsAndItsWholeReplyOfAnyLengthIsTheGoalsFinalWords() = runTest {
        // As long as the A71 session's story, far beyond what a model may give as its summary or a failure's reason.
        val reply = "Once upon a time a kestrel hovered over a quiet field. ".repeat(9).trimEnd().take(STORY)
        assertEquals(STORY, reply.length)
        val standIn = StandInRound(seconds = 3, reply = reply)

        val setup = standIn.prepare(RoundStart(1, emptyMap(), FakeOwner(), NoGoal, NoHistory, RoundPurpose.GOAL)) as RoundSetup.Ready
        var report: TaskReport? = null
        Agent(setup.ports, setup.settings).run(TaskRequest(setup.taskId, "Read me the news")) { report = it }

        val ended = report!!
        assertEquals(EndReason.FINISHED, ended.end)
        assertTrue("The round ended after ${currentTime} ms", currentTime >= 3_000)
        assertEquals(reply, standIn.sentence(ended))
        // Any other end keeps Kestrel's own words for it.
        assertEquals("Cancelled. I didn't change anything.", standIn.sentence(TaskReport(EndReason.CANCELLED)))
    }

    private companion object {
        const val STORY = 478
    }
}
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/machine/ConversationMachineTest.kt`, replace:

```kotlin
        // A reply that the request gave before the stop reached it is its own end: it had finished.
        val replied = step(stopped.state, ConversationEvent.LocalEnded(run.command, LocalOutcome.Reply(ConversationTexts.REMEMBERED)))
        assertEquals(ConversationEffect.EndRecord(id, 2, EndReason.FINISHED, ConversationTexts.REMEMBERED), replied.effect<ConversationEffect.EndRecord>())
    }
```

with:

```kotlin
        // A reply that the request gave before the stop reached it is its own end: it had finished.
        val replied = step(stopped.state, ConversationEvent.LocalEnded(run.command, LocalOutcome.Reply(ConversationTexts.REMEMBERED)))
        assertEquals(ConversationEffect.EndRecord(id, 2, EndReason.FINISHED, ConversationTexts.REMEMBERED), replied.effect<ConversationEffect.EndRecord>())
    }

    // "Stop. Kestrel, …" while Kestrel's own request asks (design 6.4; review of plan 7D's Task 3, M1): the stop cancels
    // the request and withdraws its question at once, so the command after it answers nothing and runs once the request
    // has ended.
    @Test
    fun stopThenACommandWhileKestrelsOwnRequestAsksRunsTheCommandAfterTheRequest() {
        val deleting = step(idleOpen(), spoken(InputIntent.DeleteHistory))
        val run = deleting.effect<ConversationEffect.RunLocal>()
        val asking = asked(deleting.state, 7, QuestionKind.DELETE_HISTORY)

        val stopped = step(asking, spoken(InputIntent.Stop))
        assertTrue(stopped.has<ConversationEffect.CancelLocal>())
        assertNull(stopped.state.question)

        val commanded = step(stopped.state, spoken("Open Viber"))
        assertTrue(commanded.effects.none { it is ConversationEffect.AnswerQuestion || it is ConversationEffect.StartGoal })

        val ended = step(commanded.state, ConversationEvent.LocalEnded(run.command, LocalOutcome.Quiet))
        assertEquals("Open Viber", ended.effect<ConversationEffect.StartGoal>().text)
    }
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt`, replace:

```kotlin
        engine.submit(typed("Read it again"))
        runCurrent()
        assertEquals(listOf(EndReason.FINISHED, EndReason.FINISHED), engine.view.value.records.map { it.end })
    }
```

with:

```kotlin
        engine.submit(typed("Read it again"))
        runCurrent()
        assertEquals(listOf(EndReason.FINISHED, EndReason.FINISHED), engine.view.value.records.map { it.end })
    }

    // "Stop. Kestrel, …" while Kestrel's own request asks (review of plan 7D's Task 3, M1): the view shows the request
    // while it runs, so that the voice stops it first; the stop withdraws its question, and the command after it runs as
    // the conversation's next command once the request has ended.
    @Test
    fun aStopAndACommandWhileKestrelsOwnRequestAsksStopTheRequestAndRunTheCommand() = scope.runTest {
        val engine = engine(environment(listSms, finish(1), listSms, finish(1)))
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()

        engine.submit(input(InputChannel.TEXT, InputIntent.DeleteHistory))
        runCurrent()
        assertTrue(engine.view.value.ownRequest)
        assertEquals(QuestionKind.DELETE_HISTORY, engine.view.value.asking)

        engine.submit(input(InputChannel.TEXT, InputIntent.Stop))
        engine.submit(typed("Read it again"))
        runCurrent()

        assertFalse(engine.view.value.ownRequest)
        assertEquals(EndReason.CANCELLED, engine.outcome(2)!!.report.end)
        assertTrue(archive.rows.isNotEmpty())
        assertEquals(listOf("Read my latest SMS", "Read it again"), engine.view.value.records.map { it.request })
        assertEquals(listOf(EndReason.FINISHED, EndReason.FINISHED), engine.view.value.records.map { it.end })
    }
```

In `scripts/tests/AgentChecks.Tests.ps1`, replace:

```powershell
    It 'waits until Kestrel has asked the remember question before it says "no", and says it again while the task runs (task-5c-7-review M2)' {
        $check = Get-AgentSpeechChecks | Where-Object { $_.Name -eq 'Kestrel''s phrase: remember' }
        $events = [System.Collections.Generic.List[string]]::new()
        $states = [System.Collections.Generic.Queue[string]]::new([string[]]@(
                'mode=listening session=open work=running_task speaking=false', 'mode=listening session=open work=running_task speaking=true',
                'mode=listening session=open work=running_task speaking=false', 'mode=listening session=open work=running_task speaking=false',
                'mode=listening session=open work=idle speaking=false'))
        Mock Invoke-Hook {
            $next = if ($states.Count -gt 1) { $states.Dequeue() } else { $states.Peek() }
            $events.Add("state $($next -replace '^.*work=(\S+) speaking=(\S+)$', '$1 $2')")
            $next
        } -ParameterFilter { $Command -eq 'voice-state' }
        Mock Invoke-Hook { $events.Add("said $($With.Text)"); 'sent heard' } -ParameterFilter { $Command -eq 'voice' }
        Mock Invoke-AutoCheck { [pscustomobject]@{ Context = (& $Before); Waited = (& $Wait 'k9' 60); Line = "$Name [piper]: check=pass" } }

        Invoke-AgentSpeechCheck -Check $check -Clip ([pscustomobject]@{ Voice = 'piper'; Text = 'Kestrel, remember that the gate code is blue.'; Path = 'x.wav' }) -Play { } | Out-Null

        $first = $events.IndexOf('said no')
        $first | Should -BeGreaterThan $events.IndexOf('state running_task true')
        $events.IndexOf('state running_task true') | Should -BeGreaterOrEqual 0
        @($events | Where-Object { $_ -eq 'said no' }).Count | Should -Be 2
        $events[-1] | Should -Be 'state idle false'
    }
```

with:

```powershell
    It 'waits until Kestrel''s own request of the remember phrase asks before it says "no", and says it again while the question is open (task-5c-7-review M2; plan 7D)' {
        $check = Get-AgentSpeechChecks | Where-Object { $_.Name -eq 'Kestrel''s phrase: remember' }
        $events = [System.Collections.Generic.List[string]]::new()
        # The request runs without a question first, then asks, keeps asking after the first "no", and ends.
        $states = [System.Collections.Generic.Queue[string]]::new([string[]]@(
                'phase=ended asking=none work=idle', 'phase=ended asking=none work=idle', 'phase=running asking=yes-no work=idle',
                'phase=running asking=yes-no work=idle', 'phase=ended asking=none work=idle'))
        Mock Invoke-Hook {
            $next = if ($states.Count -gt 1) { $states.Dequeue() } else { $states.Peek() }
            $events.Add("state $($With.Tag) $($next -replace '^.*asking=(\S+).*$', '$1')")
            $next
        } -ParameterFilter { $Command -eq 'agent-state' }
        Mock Invoke-Hook { 'state=running' } -ParameterFilter { $Command -eq 'agent-task' }
        Mock Invoke-Hook { $events.Add('voice-state'); 'mode=listening session=open work=idle speaking=false' } -ParameterFilter { $Command -eq 'voice-state' }
        Mock Invoke-Hook { $events.Add("said $($With.Text)"); 'sent heard' } -ParameterFilter { $Command -eq 'voice' }
        Mock Invoke-AutoCheck { [pscustomobject]@{ Context = (& $Before); Waited = (& $Wait 'k9' 60); Line = "$Name [piper]: check=pass" } }

        Invoke-AgentSpeechCheck -Check $check -Clip ([pscustomobject]@{ Voice = 'piper'; Text = 'Kestrel, remember that the gate code is blue.'; Path = 'x.wav' }) -Play { } | Out-Null

        $asked = $events.IndexOf('state k9 yes-no')
        $asked | Should -BeGreaterOrEqual 0
        $events.IndexOf('said no') | Should -BeGreaterThan $asked
        # Kestrel finished saying the question before the answer.
        $events.IndexOf('voice-state') | Should -BeGreaterThan $asked
        $events.IndexOf('voice-state') | Should -BeLessThan $events.IndexOf('said no')
        @($events | Where-Object { $_ -eq 'said no' }).Count | Should -Be 2
        $events[-1] | Should -Be 'state k9 none'
        Should -Invoke Invoke-Hook -Times 0 -Exactly -ParameterFilter { $Command -eq 'agent-answer' }
    }

    It 'says "no" once after the remember phrase when Kestrel''s own request ended without a question (plan 7D)' {
        $check = Get-AgentSpeechChecks | Where-Object { $_.Name -eq 'Kestrel''s phrase: remember' }
        Mock Invoke-Hook { 'phase=ended asking=none work=idle' } -ParameterFilter { $Command -eq 'agent-state' }
        Mock Invoke-Hook { 'state=done end=FINISHED' } -ParameterFilter { $Command -eq 'agent-task' }
        Mock Invoke-Hook { 'sent heard' } -ParameterFilter { $Command -eq 'voice' }
        Mock Invoke-AutoCheck { [pscustomobject]@{ Context = (& $Before); Waited = (& $Wait 'k9' 60); Line = "$Name [piper]: check=fail" } }

        Invoke-AgentSpeechCheck -Check $check -Clip ([pscustomobject]@{ Voice = 'piper'; Text = 'Kestrel, remember that the gate code is blue.'; Path = 'x.wav' }) -Play { } | Out-Null

        Should -Invoke Invoke-Hook -Times 1 -Exactly -ParameterFilter { $Command -eq 'voice' -and $With.Text -eq 'no' }
        Should -Invoke Invoke-Hook -Times 1 -Exactly -ParameterFilter { $Command -eq 'agent-task' -and $With.Tag -eq 'k9' }
    }
```

- [ ] **Step 4: Change the existing JVM tests**

`RulesTest` keeps its 52 voice cases as they are and changes the others as plan 7B's Task 7 table says. Its helpers `goal`, `acting` and `asked` give the Orchestrator the conversation's view; the cases that are the conversation's rules (the table's rows without a third column) go: `aRunningTaskPausesWhenTheScreenTurnsOff`, `aCancelledTaskStillReportsWhatItDid`, `aTaskSaysItsTextAndShowsIt`, `aConfirmationGoesAheadOnConfirmYesOrSendIt` and `aFreeQuestionTakesTheNextTranscriptAndANumberQuestionANumber` (now `VoiceAnswersTest`), `theSilenceAfterATaskQuestionIsNoAnswer` (now in `theConversationsQuestionIsSpokenKeptOnTheBarAndItsWaitEndsInSilence`), `stopAndCancelCancelTheTaskInEveryKindOfQuestion`, `theOwnersTouchCancelsARunningTaskButNotAHandOver`, `kestrelContinueEndsAHandOverAndCancelEndsTheTask`, `aNewTaskDuringAHandOverWaitsAndStopFirstReplacesIt`, `kestrelContinueWithoutAHandOverSaysSo`, `aHandOverThatLastsThreeMinutesEndsTheTaskWithItsReport`, `aHandOverThatEndedByItselfRunsTheTaskAgain`, `theSpottersStopCancelsATaskDuringItsHandOverSentence` (now in `theSpottersStopWhileKestrelSpeaksEndsTheSpeechAndStopsTheGoal`), `turningOffEndsATaskInEveryPhase` (now in `turningOffStopsTheSpeechButNotTheGoal`), `theTaskEventsAndEffectsPrintNoWords`, `theUnlockAsksContinueTheTask`, `yesResumesThePausedTaskAndNoSilenceOrStopCancelIt`, `aTouchAnswersContinueTheTaskOnlyOnceASecondHasPassed`, `aCallDuringAQuestionWithdrawsItAndTheEndOfTheCallAsksToContinue`, `aCallThatBecomesActiveWithoutRingingPausesForTheBackstop`, `aCallDuringAHandOverDoesNotPauseIt`, `aLockDuringContinueTheTaskWithdrawsTheQuestionUntilTheNextUnlock`, `aPauseOfThirtyMinutesEndsTheTaskAndItsReportWaitsForTheUnlock` and `aLateTaskBusyLeavesAPausedTaskAndItsQuestionAlone`; `thePauseEventsAndEffectsPrintNoWords` becomes `theConversationsEventsAndEffectsPrintNoWords`. Each replacement below either changes a case's body as its row says, or removes a deleted case.

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
import dev.kestrel.commands.Target
```

with:

```kotlin
import dev.kestrel.commands.Target
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.InputIntent
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        listening.copy(work = Work.RUNNING_COMMAND, command = 1, running = Command.Tap(Target.Label("Send")), heard = "tap Send")
```

with:

```kotlin
        listening.copy(work = Work.RUNNING_COMMAND, command = 1, running = Command.Tap(Target.Label("Send")), heard = "tap Send")

    // What the voice reads of the conversation (design 5.5 of sub-project 7): an open conversation with a goal in [phase]
    // or none.
    private fun goal(phase: GoalPhase?, state: State = listening): State = state.copy(conversation = VoiceView(open = true, goal = phase))

    private val acting = goal(GoalPhase.ACTING)

    // The voice asked the conversation's question number 4 of [kind], and has spoken it; a goal that acted asks now.
    private fun asked(kind: QuestionKind, from: State = acting, voice: Boolean = true): State {
        val phase = if (from.goal == GoalPhase.ACTING) GoalPhase.ASKING else from.goal
        val view = from.conversation.copy(goal = phase, asking = kind, question = 4)
        return Rules.next(from.copy(conversation = view), Event.ChannelAsks(4, "Shall I?", kind, voice)).state.copy(speeches = 0)
    }
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
    fun turningOffStopsTheSpeechAndTheTask() {
        val busy = listening.copy(work = Work.RUNNING_TASK, speeches = 1, task = 3, micBusy = true)

        val off = Rules.next(busy, Event.TurnOff)

        assertEquals(
            listOf(Effect.StopSaying, Effect.CancelTask, Effect.Show(Feedback.ModeChanged(Mode.OFF)), Effect.StopService),
            off.effects,
        )
```

with:

```kotlin
    fun turningOffStopsTheSpeechButNotTheGoal() {
        val busy = acting.copy(speeches = 1, micBusy = true)

        val off = Rules.next(busy, Event.TurnOff)

        // Plan 7B's decision 10: turning off changes only the mode, and the goal goes on.
        assertEquals(listOf(Effect.StopSaying, Effect.Show(Feedback.ModeChanged(Mode.OFF)), Effect.StopService), off.effects)
        assertEquals(GoalPhase.ACTING, off.state.goal)
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
    fun aTaskWithTheNameRuns() {
        val step = Rules.next(listening, Event.Heard("Kestrel, open the settings.", named = true))

        assertEquals(Work.RUNNING_TASK, step.state.work)
        assertEquals(1, step.state.task)
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Kestrel, open the settings.")), Effect.RunTask(1, "open the settings.")),
            step.effects,
        )
        assertEquals(Recognition.ALL, step.state.recognition)
    }

    @Test
    fun aTaskWhilePausedRunsAndKestrelStaysPaused() {
        assertEquals(Recognition.NAMED, paused.recognition)

        val running = Rules.next(paused, Event.Heard("Kestrel, open Viber", named = true)).state
        assertEquals(Mode.PAUSED, running.mode)
        assertEquals(Recognition.ALL, running.recognition)

        val done = Rules.next(running, Event.TaskEnded(task = 1, reply = null))
        assertEquals(Work.IDLE, done.state.work)
```

with:

```kotlin
    fun aCommandWithTheNameGoesToTheConversation() {
        val heard = Event.Heard("Kestrel, open the settings.", named = true)

        val step = Rules.next(listening, heard)

        assertEquals(Work.IDLE, step.state.work)
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Kestrel, open the settings.")), Effect.Converse(InputIntent.Command("open the settings."), heard)),
            step.effects,
        )
        // Once its goal acts, every utterance is transcribed, so that "stop" counts without the name.
        assertEquals(Recognition.ALL, Rules.next(step.state, Event.ConversationChanged(acting.conversation)).state.recognition)
    }

    @Test
    fun aCommandWhilePausedGoesToTheConversationAndKestrelStaysPaused() {
        assertEquals(Recognition.NAMED, paused.recognition)
        val heard = Event.Heard("Kestrel, open Viber", named = true)

        val step = Rules.next(paused, heard)
        assertEquals(Mode.PAUSED, step.state.mode)
        assertEquals(Effect.Converse(InputIntent.Command("open Viber"), heard), step.effects.last())

        val acts = Rules.next(step.state, Event.ConversationChanged(acting.conversation)).state
        assertEquals(Recognition.ALL, acts.recognition)
        val done = Rules.next(acts, Event.ConversationChanged(acting.conversation.copy(goal = null)))
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        val task = Rules.next(ready, Event.Heard("Open the settings.", named = false))
        assertEquals(Effect.RunTask(1, "Open the settings."), task.effects.last())
        assertEquals(Work.RUNNING_TASK, task.state.work)
```

with:

```kotlin
        val heard = Event.Heard("Open the settings.", named = false)
        val task = Rules.next(ready, heard)
        assertEquals(Effect.Converse(InputIntent.Command("Open the settings."), heard), task.effects.last())
        assertEquals(Work.IDLE, task.state.work)
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        // While "Yes?" is spoken, nothing is transcribed, and while a task runs, every utterance goes out at once.
        assertEquals(Recognition.NONE, waiting.copy(speeches = 1).recognition)
        assertEquals(Recognition.ALL, listening.copy(work = Work.RUNNING_TASK, task = 1).recognition)
```

with:

```kotlin
        // While "Yes?" is spoken, nothing is transcribed, and while a goal works, every utterance goes out at once.
        assertEquals(Recognition.NONE, waiting.copy(speeches = 1).recognition)
        assertEquals(Recognition.ALL, goal(GoalPhase.ACTING, paused).recognition)
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        val running = listening.copy(work = Work.RUNNING_TASK, task = 1, wait = 2)
```

with:

```kotlin
        val running = acting.copy(wait = 2)
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
    fun whileATaskRunsOnlyStopAndCancelCount() {
        val running = listening.copy(work = Work.RUNNING_TASK, task = 1)

        assertEquals(Step(running), Rules.next(running, Event.Heard("Open Viber", named = false)))
        assertEquals(Step(running), Rules.next(running, Event.Heard("Kestrel, open Viber", named = true)))

        val stopped = Rules.next(running, Event.Heard("Stop.", named = false))
        assertEquals(Work.IDLE, stopped.state.work)
        assertEquals(listOf(Effect.Show(Feedback.Heard("Stop.")), Effect.CancelTask), stopped.effects)
    }

    @Test
    fun aTaskAfterStopWhileATaskRunsCancelsItAndRunsTheNewOne() {
        val running = listening.copy(work = Work.RUNNING_TASK, task = 1, speeches = 1)

        val step = Rules.next(running, Event.Heard("Stop. Kestrel, open Viber.", named = true))
```

with:

```kotlin
    fun whileAGoalWorksOnlyStopAndWhatFollowsTheNameCount() {
        // Design D9 of sub-project 7: steering by voice needs "Kestrel".
        assertEquals(Step(acting), Rules.next(acting, Event.Heard("Open Viber", named = false)))
        val steering = Event.Heard("Kestrel, open Viber", named = true)
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Kestrel, open Viber")), Effect.Converse(InputIntent.Command("open Viber"), steering)),
            Rules.next(acting, steering).effects,
        )

        val stopped = Rules.next(acting, Event.Heard("Stop.", named = false))
        assertEquals(listOf(Effect.Show(Feedback.Heard("Stop.")), Effect.Converse(InputIntent.Stop)), stopped.effects)
    }

    @Test
    fun stopThenACommandGivesTheConversationAStopAndThenTheCommand() {
        val heard = Event.Heard("Stop. Kestrel, open Viber.", named = true)

        val step = Rules.next(acting.copy(speeches = 1), heard)
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
                Effect.CancelTask,
                Effect.RunTask(2, "open Viber."),
            ),
            step.effects,
        )
        assertEquals(Work.RUNNING_TASK, step.state.work)
        assertEquals(2, step.state.task)
        // The new task drops the cancelled task's report.
        assertFalse(step.state.cancelled)
    }

    @Test
    fun stopListeningWhileATaskRunsEndsTheTaskAndPauses() {
        val running = listening.copy(work = Work.RUNNING_TASK, task = 1)

        val step = Rules.next(running, Event.Heard("Stop listening.", named = false))

        assertEquals(Mode.PAUSED, step.state.mode)
        assertEquals(Work.IDLE, step.state.work)
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop listening.")), Effect.CancelTask, Effect.Show(Feedback.ModeChanged(Mode.PAUSED))),
```

with:

```kotlin
                Effect.Converse(InputIntent.Stop),
                Effect.Converse(InputIntent.Command("open Viber."), heard),
            ),
            step.effects,
        )
        assertEquals(Work.IDLE, step.state.work)
    }

    @Test
    fun stopListeningWhileAGoalWorksOnlyPauses() {
        val step = Rules.next(acting, Event.Heard("Stop listening.", named = false))

        // Plan 7B's decision 10: the mode changes, and the conversation gets no input.
        assertEquals(Mode.PAUSED, step.state.mode)
        assertEquals(GoalPhase.ACTING, step.state.goal)
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop listening.")), Effect.Show(Feedback.ModeChanged(Mode.PAUSED))),
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        assertEquals(Work.RUNNING_TASK, Rules.next(listening, Event.Heard("Kestrel, sign up.", named = true)).state.work)
```

with:

```kotlin
        assertTrue(Rules.next(listening, Event.Heard("Kestrel, sign up.", named = true)).effects.last() is Effect.Converse)
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        // After "Stop." while a task runs, the task stops and no new one starts.
        val stopped = Rules.next(paused.copy(work = Work.RUNNING_TASK, task = 1), Event.Heard("Stop. Kestrel, sign up.", named = true))
        assertEquals(Work.IDLE, stopped.state.work)
        assertEquals(1, stopped.state.task)
```

with:

```kotlin
        // After "Stop." while a goal works, the goal stops and no command follows.
        val stopped = Rules.next(goal(GoalPhase.ACTING, paused), Event.Heard("Stop. Kestrel, sign up.", named = true))
        assertEquals(listOf(Effect.Show(Feedback.Heard("Stop. Kestrel, sign up.")), Effect.Converse(InputIntent.Stop)), stopped.effects)
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        val task = Rules.next(runningCommand, Event.Heard("Kestrel, open Viber.", named = true))
        assertEquals(
            listOf(Effect.CancelCommand, Effect.Show(Feedback.Heard("Kestrel, open Viber.")), Effect.RunTask(1, "open Viber.")),
            task.effects,
        )
        assertEquals(Work.RUNNING_TASK, task.state.work)
```

with:

```kotlin
        val heard = Event.Heard("Kestrel, open Viber.", named = true)
        val task = Rules.next(runningCommand, heard)
        assertEquals(
            listOf(
                Effect.CancelCommand,
                Effect.Show(Feedback.Heard("Kestrel, open Viber.")),
                Effect.Converse(InputIntent.Command("open Viber."), heard),
            ),
            task.effects,
        )
        assertEquals(Work.IDLE, task.state.work)
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        val task = Rules.next(ended, Event.Heard("Kestrel, what time is it?", named = true)).state
        assertEquals(Work.RUNNING_TASK, task.work)
        assertTrue(runs(Rules.next(task, Event.TaskEnded(task.task, reply = null)).state))
```

with:

```kotlin
        // A command for the conversation ends it, and so does a goal that works.
        assertTrue(runs(Rules.next(ended, Event.Heard("Kestrel, what time is it?", named = true)).state))
        val worked = Rules.next(ended, Event.ConversationChanged(acting.conversation)).state
        assertTrue(runs(Rules.next(worked, Event.ConversationChanged(VoiceView())).state))
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
            Event.SpeechEnded, Event.TaskEnded(1, "Done."), Event.MicBusy(true), Event.MicBusy(false), Event.Screen(on = true),
```

with:

```kotlin
            Event.SpeechEnded, Event.ConversationChanged(acting.conversation), Event.ChannelSays(1, "Done."),
            Event.ChannelShows("Got it."), Event.ChannelAsks(4, "Shall I?", QuestionKind.YES_NO), Event.ChannelHandsOver("Pay yourself."),
            Event.MicBusy(true), Event.MicBusy(false), Event.Screen(on = true),
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
    fun stopWhileKestrelSpeaksEndsTheSpeechAndCancelsTheTask() {
        val speaking = listening.copy(work = Work.RUNNING_TASK, task = 1, speeches = 1)

        val step = Rules.next(speaking, Event.Spotted(Keyword.STOP))

        assertEquals(listOf(Effect.StopSaying, Effect.CancelTask), step.effects)
        assertEquals(Work.IDLE, step.state.work)
```

with:

```kotlin
    fun theSpottersStopWhileKestrelSpeaksEndsTheSpeechAndStopsTheGoal() {
        val step = Rules.next(acting.copy(speeches = 1), Event.Spotted(Keyword.STOP))

        // Plan 7B's decision 10.
        assertEquals(listOf(Effect.StopSaying, Effect.Converse(InputIntent.Stop)), step.effects)
        assertEquals(Work.IDLE, step.state.work)
        // Also while Kestrel speaks a hand-over's sentence.
        val handing = Rules.next(goal(GoalPhase.HANDED_OVER), Event.ChannelHandsOver("Pay yourself, then say 'Kestrel, continue'.")).state
        assertEquals(listOf(Effect.StopSaying, Effect.Converse(InputIntent.Stop)), Rules.next(handing, Event.Spotted(Keyword.STOP)).effects)
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        // "Kestrel" while a task runs only ends the speech; the task keeps running.
        val running = listening.copy(work = Work.RUNNING_TASK, task = 1, speeches = 1)
```

with:

```kotlin
        // "Kestrel" while a goal works only ends the speech; the goal goes on.
        val running = acting.copy(speeches = 1)
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
    fun aTaskReplyIsSpokenAndTheEndOfAnOlderTaskChangesNothing() {
        val running = listening.copy(work = Work.RUNNING_TASK, task = 2)

        val done = Rules.next(running, Event.TaskEnded(task = 2, reply = "Done."))

        assertEquals(listOf(Effect.Say(Reply.Text("Done."))), done.effects)
        assertEquals(1, done.state.speeches)
        assertEquals(Step(running), Rules.next(running, Event.TaskEnded(task = 1, reply = "Late.")))
        assertEquals(Step(done.state), Rules.next(done.state, Event.TaskEnded(task = 2, reply = "Again.")))
```

with:

```kotlin
    fun theConversationsWordsAreSpokenAndShownAndWhileOffOnlyShown() {
        val says = Rules.next(listening, Event.ChannelSays(3, "Done."))

        assertEquals(listOf(Effect.Show(Feedback.ConversationText("Done.")), Effect.Say(Reply.Channel(3, "Done."))), says.effects)
        assertEquals(1, says.state.speeches)
        // While Kestrel is off, the words are only shown, and the voice channel goes on at once.
        assertEquals(
            listOf(Effect.Show(Feedback.ConversationText("Done.")), Effect.EndReply(3)),
            Rules.next(State(), Event.ChannelSays(3, "Done.")).effects,
        )
        assertEquals(
            Step(listening, listOf(Effect.Show(Feedback.ConversationText("Got it.")))),
            Rules.next(listening, Event.ChannelShows("Got it.")),
        )
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        val darkAndRunning = listening.copy(screenOn = false, work = Work.RUNNING_TASK, task = 1)
```

with:

```kotlin
        val darkAndRunning = goal(GoalPhase.ACTING, dark)
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        // Design D2 of sub-project 5: "Stop." before the name still cancels the task, and nothing starts.
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop. Kestrel, open Viber.")), Effect.CancelTask),
```

with:

```kotlin
        // Design D2 of sub-project 5: "Stop." before the name still stops the goal, and nothing starts.
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop. Kestrel, open Viber.")), Effect.Converse(InputIntent.Stop)),
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        assertEquals(listOf(Effect.Show(Feedback.Heard("Stop.")), Effect.CancelTask), stopped.effects)
        assertEquals(Mode.PAUSED, Rules.next(darkAndRunning, Event.Heard("Stop listening.", named = false)).state.mode)
        assertEquals(Mode.PAUSED, Rules.next(dark, Event.Heard("Stop listening.", named = false)).state.mode)
        assertEquals(Mode.OFF, Rules.next(darkAndRunning, Event.Heard("Stop Kestrel.", named = false)).state.mode)
    }

    @Test
    fun aRunningTaskPausesWhenTheScreenTurnsOff() {
        val running = listening.copy(work = Work.RUNNING_TASK, task = 1)

        val dark = Rules.next(running, Event.Screen(on = false))

        // Design D2 of sub-project 5 (section 14.4): the lock pauses the task instead of leaving it running.
        assertEquals(listOf(Effect.PauseTask(1, PauseCause.LOCK), Effect.TimePause), dark.effects)
        assertEquals(TaskPhase.PAUSED, dark.state.taskPhase)
        assertEquals(Work.IDLE, dark.state.work)
        assertFalse(dark.state.cancelled)
```

with:

```kotlin
        assertEquals(listOf(Effect.Show(Feedback.Heard("Stop.")), Effect.Converse(InputIntent.Stop)), stopped.effects)
        assertEquals(Mode.PAUSED, Rules.next(darkAndRunning, Event.Heard("Stop listening.", named = false)).state.mode)
        assertEquals(Mode.PAUSED, Rules.next(dark, Event.Heard("Stop listening.", named = false)).state.mode)
        assertEquals(Mode.OFF, Rules.next(darkAndRunning, Event.Heard("Stop Kestrel.", named = false)).state.mode)
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        assertEquals(Effect.RunTask(1, "open the settings."), heard(waiting, "Kestrel, open the settings.", named = true).effects.last())
```

with:

```kotlin
        assertEquals(
            Effect.Converse(InputIntent.Command("open the settings."), Event.Heard("Kestrel, open the settings.", named = true)),
            heard(waiting, "Kestrel, open the settings.", named = true).effects.last(),
        )
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
    fun whileATaskRunsKestrelsOwnRequests() {
        val running = listening.copy(work = Work.RUNNING_TASK, task = 1)
        val pausedAndRunning = paused.copy(work = Work.RUNNING_TASK, task = 1)

        val off = Rules.next(running, Event.Heard("Stop Kestrel.", named = false))
        assertEquals(Mode.OFF, off.state.mode)
        assertEquals(
            listOf(
                Effect.Show(Feedback.Heard("Stop Kestrel.")),
                Effect.CancelTask,
                Effect.Show(Feedback.ModeChanged(Mode.OFF)),
                Effect.StopService,
            ),
            off.effects,
        )
        assertEquals(Step(running), Rules.next(running, Event.Heard("Kestrel.", named = true)))
        assertEquals(Step(pausedAndRunning), Rules.next(pausedAndRunning, Event.Heard("Kestrel, wake up.", named = true)))
        // Already paused: the task ends, and the mode is not shown again.
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop listening.")), Effect.CancelTask),
            Rules.next(pausedAndRunning, Event.Heard("Stop listening.", named = false)).effects,
        )
```

with:

```kotlin
    fun whileAGoalWorksKestrelsOwnRequests() {
        val pausedAndActing = goal(GoalPhase.ACTING, paused)

        // Plan 7B's decision 10: "Stop Kestrel" turns Kestrel off, and the goal goes on.
        val off = Rules.next(acting, Event.Heard("Stop Kestrel.", named = false))
        assertEquals(Mode.OFF, off.state.mode)
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop Kestrel.")), Effect.Show(Feedback.ModeChanged(Mode.OFF)), Effect.StopService),
            off.effects,
        )
        assertEquals(Step(acting), Rules.next(acting, Event.Heard("Kestrel.", named = true)))
        assertEquals(Step(pausedAndActing), Rules.next(pausedAndActing, Event.Heard("Kestrel, wake up.", named = true)))
        // Already paused: only the transcript is shown.
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop listening."))),
            Rules.next(pausedAndActing, Event.Heard("Stop listening.", named = false)).effects,
        )
        // Kestrel's own requests reach the conversation, which runs them once the goal has ended.
        val delete = Event.Heard("Kestrel, delete history", named = true)
        assertEquals(Effect.Converse(InputIntent.DeleteHistory, delete), Rules.next(acting, delete).effects.last())
        val continuing = Event.Heard("Kestrel, continue", named = true)
        assertEquals(Effect.Converse(InputIntent.Continue, continuing), Rules.next(acting, continuing).effects.last())
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        )
    }

    @Test
    fun aCancelledTaskStillReportsWhatItDid() {
        val running = listening.copy(work = Work.RUNNING_TASK, task = 1)
        val cancelled = Rules.next(running, Event.Heard("Stop.", named = false)).state

        val report = Rules.next(cancelled, Event.TaskEnded(task = 1, reply = "Cancelled. I didn't send it."))
        assertEquals(listOf(Effect.Say(Reply.Text("Cancelled. I didn't send it."))), report.effects)
        assertEquals(Work.IDLE, report.state.work)
        assertEquals(Step(report.state), Rules.next(report.state, Event.TaskEnded(task = 1, reply = "Again.")))
        assertEquals(Step(cancelled), Rules.next(cancelled, Event.TaskEnded(task = 0, reply = "Older.")))

        // A newer wait or task, or Off, drops the report.
        val waiting = Rules.next(cancelled, Event.Heard("Kestrel?", named = true)).state
        val idleAgain = Rules.next(waiting, Event.WaitEnded(waiting.wait)).state
        assertEquals(Step(idleAgain), Rules.next(idleAgain, Event.TaskEnded(task = 1, reply = "Late.")))
        val off = Rules.next(cancelled, Event.TurnOff).state
        assertEquals(Step(off), Rules.next(off, Event.TaskEnded(task = 1, reply = "Late.")))
```

with:

```kotlin
        )
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
    fun openSettingsEndsTheWaitForATaskAndIsIgnoredWhileATaskRuns() {
        val waiting = listening.copy(work = Work.AWAITING_TASK, wait = 1)
        val running = listening.copy(work = Work.RUNNING_TASK, task = 1)
```

with:

```kotlin
    fun openSettingsEndsTheWaitForACommandAndIsIgnoredWhileAGoalWorks() {
        val waiting = listening.copy(work = Work.AWAITING_TASK, wait = 1)
        val running = acting
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
    fun stopBeforeOpenSettingsCancelsTheRunningTask() {
        val running = listening.copy(work = Work.RUNNING_TASK, task = 1)

        val step = Rules.next(running, Event.Heard("Stop. Kestrel, open settings.", named = true))

        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop. Kestrel, open settings.")), Effect.CancelTask, Effect.OpenSettings),
            step.effects,
        )
        assertEquals(Work.IDLE, step.state.work)
        assertTrue(step.state.cancelled)
```

with:

```kotlin
    fun stopBeforeOpenSettingsStopsTheGoal() {
        val step = Rules.next(acting, Event.Heard("Stop. Kestrel, open settings.", named = true))

        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop. Kestrel, open settings.")), Effect.Converse(InputIntent.Stop), Effect.OpenSettings),
            step.effects,
        )
        assertEquals(Work.IDLE, step.state.work)
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
    fun aCallThatRingsStopsTheSpeechAndPausesTheTask() {
        val busy = listening.copy(work = Work.RUNNING_TASK, task = 2, speeches = 1)

        val ringing = Rules.next(busy, Event.CallState(CallState.RINGING))

        // Design D2 of sub-project 5 (section 14.4): the call pauses the task, which asks "Continue the task?" afterwards.
        assertEquals(
            listOf(
                Effect.StopSaying,
                Effect.PauseTask(2, PauseCause.INCOMING_CALL),
                Effect.TimePause,
                Effect.Show(Feedback.ModeChanged(Mode.RINGING)),
            ),
            ringing.effects,
        )
        assertEquals(Work.IDLE, ringing.state.work)
        assertEquals(TaskPhase.PAUSED, ringing.state.taskPhase)
        assertFalse(ringing.state.cancelled)
        // A wait for a task ends too, and nothing comes back after the call (section 14.4).
```

with:

```kotlin
    fun aCallThatRingsStopsTheSpeech() {
        val busy = acting.copy(speeches = 1)

        val ringing = Rules.next(busy, Event.CallState(CallState.RINGING))

        // The conversation pauses the goal itself, with listening on or off (design 6.5 of sub-project 7).
        assertEquals(listOf(Effect.StopSaying, Effect.Show(Feedback.ModeChanged(Mode.RINGING))), ringing.effects)
        assertEquals(Work.IDLE, ringing.state.work)
        assertEquals(GoalPhase.ACTING, ringing.state.goal)
        // A wait for a command ends too, and nothing comes back after the call (section 14.4).
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
    fun theAssistantGestureOverKestrelsVoiceOrWhileATaskRunsActsLikeTheName() {
```

with:

```kotlin
    fun theAssistantGestureOverKestrelsVoiceOrWhileAGoalWorksActsLikeTheName() {
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        val running = paused.copy(work = Work.RUNNING_TASK, task = 1, speeches = 1)
```

with:

```kotlin
        val running = goal(GoalPhase.ACTING, paused).copy(speeches = 1)
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        for (busy in listOf(timed.copy(speeches = 1), timed.copy(work = Work.RUNNING_TASK, task = 1), timed.copy(screenOn = false))) {
```

with:

```kotlin
        for (busy in listOf(timed.copy(speeches = 1), goal(GoalPhase.ACTING, timed), timed.copy(screenOn = false))) {
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
    // Design D2 of sub-project 5: the task's own state, its questions, touches and hand-overs.
    private val runningTask = listening.copy(work = Work.RUNNING_TASK, task = 1, taskPhase = TaskPhase.RUNNING)

    private fun asking(kind: TaskAsk, voice: Boolean = true): State =
        Rules.next(runningTask, Event.TaskAsks(1, "Shall I?", kind, voice)).state

    private val handOverText = "Please enter your password, then say 'Kestrel, continue'."

    // A hand-over whose sentence has been spoken.
    private fun handedOver(from: State = runningTask): State =
        Rules.next(Rules.next(from, Event.TaskHandsOver(1, handOverText)).state, Event.SpeechEnded).state
```

with:

```kotlin
    // Design 11 of sub-project 7: the goal's hand-over as the voice says it.
    private val handOverText = "Please enter your password, then say 'Kestrel, continue'."

    // A goal's hand-over whose sentence has been spoken.
    private fun handedOver(from: State = goal(GoalPhase.HANDED_OVER)): State =
        Rules.next(Rules.next(from, Event.ChannelHandsOver(handOverText)).state, Event.SpeechEnded).state
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
    fun aTaskStartsInItsRunningPhaseAndEndsWithoutOne() {
        val started = heard(listening, "Kestrel, open Viber.", named = true).state
        assertEquals(TaskPhase.RUNNING, started.taskPhase)
        assertTrue(started.taskExists)

        val ended = Rules.next(started, Event.TaskEnded(1, "Done."))
        assertEquals(null, ended.state.taskPhase)
        assertFalse(ended.state.taskExists)
    }

    @Test
    fun aTaskSaysItsTextAndShowsIt() {
        val step = Rules.next(runningTask, Event.TaskSays(1, "Android gave Kestrel only the photos that you selected."))

        assertEquals(
            listOf(
                Effect.Show(Feedback.TaskText("Android gave Kestrel only the photos that you selected.")),
                Effect.Say(Reply.Text("Android gave Kestrel only the photos that you selected.")),
            ),
            step.effects,
        )
        // An older task says nothing.
        assertEquals(Step(runningTask), Rules.next(runningTask, Event.TaskSays(0, "Late.")))
    }

    @Test
    fun aTaskQuestionIsSpokenKeptOnTheBarAndWaitsForItsAnswer() {
        val step = Rules.next(runningTask, Event.TaskAsks(1, "I'm not getting anywhere. Should I keep trying?", TaskAsk.YES_NO))

        assertEquals(
            listOf(
                Effect.Show(Feedback.TaskText("I'm not getting anywhere. Should I keep trying?", keep = true)),
                Effect.Say(Reply.Text("I'm not getting anywhere. Should I keep trying?", kept = true)),
                Effect.WaitForAnswer(),
            ),
            step.effects,
        )
        assertEquals(TaskAsk.YES_NO, step.state.taskAsking)
        assertEquals(Work.RUNNING_TASK, step.state.work)
```

with:

```kotlin
    fun theGoalComesFromTheConversationsView() {
        val sent = heard(listening, "Kestrel, open Viber.", named = true).state
        assertEquals(null, sent.goal)

        val acts = Rules.next(sent, Event.ConversationChanged(acting.conversation)).state
        assertTrue(acts.goalWorks)
        val ended = Rules.next(acts, Event.ConversationChanged(acting.conversation.copy(goal = null))).state
        assertFalse(ended.goalWorks)
        assertEquals(null, ended.goal)
    }

    @Test
    fun theConversationsQuestionIsSpokenKeptOnTheBarAndItsWaitEndsInSilence() {
        val text = "I'm not getting anywhere. Should I keep trying?"
        val step = Rules.next(acting, Event.ChannelAsks(4, text, QuestionKind.YES_NO))

        assertEquals(
            listOf(Effect.Show(Feedback.ConversationText(text, keep = true)), Effect.Say(Reply.Text(text, kept = true)), Effect.WaitForAnswer()),
            step.effects,
        )
        assertEquals(VoiceQuestion(4, QuestionKind.YES_NO), step.state.voiceQuestion)
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        // A second question while one is open is ignored: questions come one at a time.
        assertEquals(Step(step.state), Rules.next(step.state, Event.TaskAsks(1, "Another?", TaskAsk.FREE)))
    }

    @Test
    fun aYesOrNoQuestionTakesOnlyItsAnswers() {
        val question = asking(TaskAsk.YES_NO)

        for (yes in listOf("Yes.", "Yes, please.", "Continue", "Go on.")) {
            val step = heard(question, yes)
            assertEquals(yes, Effect.AnswerTask(1, TaskAnswers.YES), step.effects.last())
            assertEquals(null, step.state.taskAsking)
        }
        assertEquals(Effect.AnswerTask(1, TaskAnswers.YES), heard(question, "Kestrel, continue.", named = true).effects.last())
        assertEquals(Effect.AnswerTask(1, TaskAnswers.NO), heard(question, "No.").effects.last())
```

with:

```kotlin
        // Its end is the answer Silence; an older wait changes nothing.
        val silent = Rules.next(spoken.state, Event.AnswerWaitEnded(spoken.state.wait))
        assertEquals(listOf(Effect.Converse(InputIntent.Answer(ChannelAnswer.Silence))), silent.effects)
        assertEquals(null, silent.state.voiceQuestion)
        assertEquals(Step(spoken.state), Rules.next(spoken.state, Event.AnswerWaitEnded(spoken.state.wait - 1)))
        // An answer on its card, or the conversation's withdrawal, ends the wait; an older question's end changes nothing.
        assertEquals(Step(spoken.state), Rules.next(spoken.state, Event.ChannelWithdraws(3)))
        assertEquals(null, Rules.next(spoken.state, Event.ChannelWithdraws(4)).state.voiceQuestion)
    }

    @Test
    fun theVoiceReadsTheAnswerOfTheQuestionsKind() {
        val question = asked(QuestionKind.YES_NO)

        for (yes in listOf("Yes.", "Yes, please.", "Continue", "Go on.")) {
            val step = heard(question, yes)
            assertEquals(yes, Effect.Converse(InputIntent.Answer(ChannelAnswer.Yes), Event.Heard(yes, named = false)), step.effects.last())
            assertEquals(null, step.state.openQuestion)
        }
        assertEquals(InputIntent.Answer(ChannelAnswer.Yes), (heard(question, "Kestrel, continue.", named = true).effects.last() as Effect.Converse).intent)
        assertEquals(InputIntent.Answer(ChannelAnswer.No), (heard(question, "No.").effects.last() as Effect.Converse).intent)
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
    fun aConfirmationGoesAheadOnConfirmYesOrSendIt() {
        val question = asking(TaskAsk.CONFIRM)

        for (yes in listOf("Confirm.", "Yes", "Send it!", "Kestrel, confirm")) {
            assertEquals(yes, Effect.AnswerTask(1, TaskAnswers.YES), heard(question, yes, named = yes.startsWith("Kestrel")).effects.last())
        }
        assertEquals(Effect.AnswerTask(1, TaskAnswers.NO), heard(question, "No").effects.last())
        assertEquals(listOf(Effect.Show(Feedback.Heard("Go on."))), heard(question, "Go on.").effects)
    }

    @Test
    fun theSilenceAfterATaskQuestionIsNoAnswer() {
        val question = Rules.next(asking(TaskAsk.CONFIRM), Event.SpeechEnded).state

        val ended = Rules.next(question, Event.AnswerWaitEnded(question.wait))

        assertEquals(listOf(Effect.AnswerTask(1, null)), ended.effects)
        assertEquals(null, ended.state.taskAsking)
        // The end of an older wait changes nothing.
        assertEquals(Step(question), Rules.next(question, Event.AnswerWaitEnded(question.wait - 1)))
    }

    @Test
    fun stopAndCancelCancelTheTaskInEveryKindOfQuestion() {
        for (kind in TaskAsk.entries) {
            for (text in listOf("Stop.", "Cancel", "Okay, cancel that")) {
                val step = heard(asking(kind), text)
                assertTrue("$kind $text", Effect.CancelTask in step.effects)
                assertEquals("$kind $text", null, step.state.taskPhase)
                assertEquals("$kind $text", null, step.state.taskAsking)
            }
        }
    }

    @Test
    fun aFreeQuestionTakesTheNextTranscriptAndANumberQuestionANumber() {
        assertEquals(Effect.AnswerTask(1, "The blue one."), heard(asking(TaskAsk.FREE), "The blue one.").effects.last())
        assertEquals(Effect.AnswerTask(1, "12"), heard(asking(TaskAsk.PICK_NUMBER), "12").effects.last())
        val number = asking(TaskAsk.PICK_NUMBER)
        assertEquals(Step(number, listOf(Effect.Show(Feedback.Heard("the red one")))), heard(number, "the red one"))
    }

    @Test
    fun whileOnlyTheCardMayAnswerSpokenAnswersDoNotCount() {
        val question = Rules.next(asking(TaskAsk.CONFIRM, voice = false), Event.SpeechEnded).state

        assertEquals(Step(question, listOf(Effect.Show(Feedback.Heard("Confirm.")))), heard(question, "Confirm."))
        assertTrue(Effect.CancelTask in heard(question, "Cancel.").effects)
        assertEquals(listOf(Effect.AnswerTask(1, null)), Rules.next(question, Event.AnswerWaitEnded(question.wait)).effects)
        // The card answered: the agent says that it acts again.
        assertEquals(null, Rules.next(question, Event.TaskBusy(1)).state.taskAsking)
    }

    @Test
    fun theOwnersTouchCancelsARunningTaskButNotAHandOver() {
        assertEquals(listOf(Effect.CancelTask), Rules.next(runningTask, Event.OwnerTouched).effects)
        assertTrue(Effect.CancelTask in Rules.next(asking(TaskAsk.CONFIRM), Event.OwnerTouched).effects)
        val handed = handedOver()
        assertEquals(Step(handed), Rules.next(handed, Event.OwnerTouched))
        assertEquals(Step(listening), Rules.next(listening, Event.OwnerTouched))
    }

    @Test
    fun aHandOverKeepsItsSentenceOnTheBarAndStartsItsThreeMinutes() {
        val step = Rules.next(runningTask, Event.TaskHandsOver(1, "This is a payment. Please finish it yourself, then say 'Kestrel, continue'."))

        assertEquals(
            listOf(
                Effect.Show(Feedback.TaskText("This is a payment. Please finish it yourself, then say 'Kestrel, continue'.", keep = true)),
                Effect.Say(Reply.Text("This is a payment. Please finish it yourself, then say 'Kestrel, continue'.", kept = true)),
                Effect.TimeHandOver,
            ),
            step.effects,
        )
        assertEquals(TaskPhase.HANDED_OVER, step.state.taskPhase)
        assertEquals(Work.IDLE, step.state.work)
        assertEquals(1, step.state.handOver)
        assertTrue(step.state.taskExists)
```

with:

```kotlin
    fun whileOnlyTheCardMayAnswerSpokenAnswersDoNotCount() {
        val question = asked(QuestionKind.CONFIRM, voice = false)

        assertEquals(Step(question, listOf(Effect.Show(Feedback.Heard("Confirm.")))), heard(question, "Confirm."))
        assertTrue(Effect.Converse(InputIntent.Stop) in heard(question, "Cancel.").effects)
        assertEquals(
            listOf(Effect.Converse(InputIntent.Answer(ChannelAnswer.Silence))),
            Rules.next(question, Event.AnswerWaitEnded(question.wait)).effects,
        )
    }

    @Test
    fun aHandOverKeepsItsSentenceOnTheBar() {
        val text = "This is a payment. Please finish it yourself, then say 'Kestrel, continue'."
        val step = Rules.next(goal(GoalPhase.HANDED_OVER), Event.ChannelHandsOver(text))

        assertEquals(listOf(Effect.Show(Feedback.ConversationText(text, keep = true)), Effect.Say(Reply.Text(text, kept = true))), step.effects)
        assertEquals(Work.IDLE, step.state.work)
        // While Kestrel is off, it is only shown; the conversation runs the hand-over's 3 minutes.
        assertEquals(listOf(Effect.Show(Feedback.ConversationText(text, keep = true))), Rules.next(State(), Event.ChannelHandsOver(text)).effects)
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        for (from in listOf(runningTask, runningTask.copy(mode = Mode.PAUSED))) {
```

with:

```kotlin
        for (from in listOf(goal(GoalPhase.HANDED_OVER), goal(GoalPhase.HANDED_OVER, paused))) {
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
            assertEquals(TaskPhase.HANDED_OVER, ended.state.taskPhase)
        }
    }

    @Test
    fun kestrelContinueEndsAHandOverAndCancelEndsTheTask() {
        val handed = handedOver()

        val continued = heard(handed, "Kestrel, continue.", named = true)
        assertEquals(listOf(Effect.Show(Feedback.Heard("Kestrel, continue.")), Effect.ContinueTask(1)), continued.effects)
        assertEquals(TaskPhase.RUNNING, continued.state.taskPhase)
        assertEquals(Work.RUNNING_TASK, continued.state.work)

        for (text in listOf("Cancel.", "Stop", "Kestrel, cancel")) {
            val cancelled = heard(handed, text, named = text.startsWith("Kestrel"))
            assertTrue(text, Effect.CancelTask in cancelled.effects)
            assertEquals(text, null, cancelled.state.taskPhase)
            assertTrue(text, cancelled.state.cancelled)
        }
        // "Kestrel" and then "never mind" ends only the wait for a task; "Kestrel" and then "stop" ends the task.
        val awaiting = heard(handed, "Kestrel", named = true).state
        assertFalse(Effect.CancelTask in heard(awaiting, "Never mind.").effects)
        assertTrue(Effect.CancelTask in heard(awaiting, "Stop.").effects)
        assertTrue(Effect.CancelTask in heard(awaiting, "Kestrel, cancel.", named = true).effects)
```

with:

```kotlin
            assertEquals(GoalPhase.HANDED_OVER, ended.state.goal)
        }
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        val command = heard(handedOver(), "Scroll down").state

        val continued = heard(command, "Kestrel, continue", named = true)

        assertEquals(listOf(Effect.CancelCommand, Effect.Show(Feedback.Heard("Kestrel, continue")), Effect.ContinueTask(1)), continued.effects)
        assertEquals(Work.RUNNING_TASK, continued.state.work)
    }

    @Test
    fun aNewTaskDuringAHandOverWaitsAndStopFirstReplacesIt() {
        val handed = handedOver()

        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Kestrel, open Viber.")), Effect.Show(Feedback.FinishHandOverFirst)),
            heard(handed, "Kestrel, open Viber.", named = true).effects,
        )
        val replaced = heard(handed, "Stop. Kestrel, open Viber.", named = true)
        assertEquals(listOf(Effect.Show(Feedback.Heard("Stop. Kestrel, open Viber.")), Effect.CancelTask, Effect.RunTask(2, "open Viber.")), replaced.effects)
        assertEquals(TaskPhase.RUNNING, replaced.state.taskPhase)
        // After "Kestrel" on its own, "Yes?" takes "continue" for the hand-over, and refuses a new task.
        val awaiting = heard(handed, "Kestrel", named = true).state
        assertEquals(Effect.ContinueTask(1), heard(awaiting, "Continue.").effects.last())
        assertEquals(Effect.Show(Feedback.FinishHandOverFirst), heard(awaiting, "Open Viber.").effects.last())
    }

    @Test
    fun kestrelContinueWithoutAHandOverSaysSo() {
        val step = heard(listening, "Kestrel, continue, please.", named = true)

        assertEquals(listOf(Effect.Show(Feedback.Heard("Kestrel, continue, please.")), Effect.Say(Reply.NoTaskToContinue)), step.effects)
        // While the task runs, it is ignored.
        assertEquals(Step(runningTask), heard(runningTask, "Kestrel, continue", named = true))
    }

    @Test
    fun aHandOverThatLastsThreeMinutesEndsTheTaskWithItsReport() {
        val handed = handedOver()

        assertEquals(listOf(Effect.ExpireHandOver(1)), Rules.next(handed, Event.HandOverEnded(1)).effects)
        // The timer of an older hand-over changes nothing.
        assertEquals(Step(handed), Rules.next(handed, Event.HandOverEnded(0)))
        // The task's report comes in every phase, also while a command runs.
        val report = Rules.next(handed, Event.TaskEnded(1, "I stopped because the hand-over took too long."))
        assertEquals(null, report.state.taskPhase)
        assertEquals(Effect.Say(Reply.Text("I stopped because the hand-over took too long.")), report.effects.last())
        val command = heard(handed, "Scroll down").state
        val late = Rules.next(command, Event.TaskEnded(1, "Report."))
        assertEquals(Work.RUNNING_COMMAND, late.state.work)
        assertEquals(null, late.state.taskPhase)
        assertEquals(Effect.Say(Reply.Text("Report.")), late.effects.last())
    }

    @Test
    fun aHandOverThatEndedByItselfRunsTheTaskAgain() {
        val busy = Rules.next(handedOver(), Event.TaskBusy(1))

        assertEquals(TaskPhase.RUNNING, busy.state.taskPhase)
        assertEquals(Work.RUNNING_TASK, busy.state.work)
        assertEquals(Step(runningTask), Rules.next(runningTask, Event.TaskBusy(0)))
    }

    @Test
    fun theSpottersStopCancelsATaskDuringItsHandOverSentence() {
        val speaking = Rules.next(runningTask, Event.TaskHandsOver(1, handOverText)).state

        val step = Rules.next(speaking, Event.Spotted(Keyword.STOP))

        assertEquals(listOf(Effect.StopSaying, Effect.CancelTask), step.effects)
        assertEquals(null, step.state.taskPhase)
    }

    @Test
    fun turningOffEndsATaskInEveryPhase() {
        for (state in listOf(runningTask, asking(TaskAsk.FREE), handedOver())) {
            val off = Rules.next(state, Event.TurnOff)
            assertTrue(Effect.CancelTask in off.effects)
            assertEquals(null, off.state.taskPhase)
            assertEquals(null, off.state.taskAsking)
```

with:

```kotlin
        val command = heard(handedOver(), "Scroll down").state
        val continuing = Event.Heard("Kestrel, continue", named = true)

        val continued = Rules.next(command, continuing)

        assertEquals(
            listOf(Effect.CancelCommand, Effect.Show(Feedback.Heard("Kestrel, continue")), Effect.Converse(InputIntent.Continue, continuing)),
            continued.effects,
        )
        assertEquals(Work.IDLE, continued.state.work)
    }

    // Design 6.5 of sub-project 7: a goal that the lock paused, and "Continue the goal?" after the unlock, spoken.
    private val pausedGoal = goal(GoalPhase.PAUSED).copy(screenOn = false)

    private fun continueAsked(): State = asked(QuestionKind.CONTINUE_GOAL, goal(GoalPhase.PAUSED))

    @Test
    fun kestrelOverContinueTheGoalLeavesTheQuestionOpen() {
        // The spotter hears "Kestrel" while Kestrel still speaks "Continue the goal?".
        val paused = goal(GoalPhase.PAUSED)
        val view = paused.conversation.copy(asking = QuestionKind.CONTINUE_GOAL, question = 4)
        val speaking = Rules.next(paused.copy(conversation = view), Event.ChannelAsks(4, "Continue the goal?", QuestionKind.CONTINUE_GOAL)).state

        val spotted = Rules.next(speaking, Event.Spotted(Keyword.KESTREL)).state
        assertEquals(VoiceQuestion(4, QuestionKind.CONTINUE_GOAL), spotted.openQuestion)
        assertEquals(Work.IDLE, spotted.work)
        val yes = heard(Rules.next(spotted, Event.SpeechEnded).state, "Kestrel, continue.", named = true)
        assertEquals(InputIntent.Answer(ChannelAnswer.Yes), (yes.effects.last() as Effect.Converse).intent)
    }

    @Test
    fun aPausedGoalLetsTheInstantCommandsRunAndTakesACommandAsItsAnswer() {
        val asked = continueAsked()
        val replacing = Event.Heard("Stop. Kestrel, open Viber.", named = true)
        assertEquals(
            listOf(
                Effect.Show(Feedback.Heard("Stop. Kestrel, open Viber.")),
                Effect.Converse(InputIntent.Stop),
                Effect.Converse(InputIntent.Command("open Viber."), replacing),
            ),
            Rules.next(asked, replacing).effects,
        )
        // A command after the name answers "Continue the goal?" as steering (design 6.5 of sub-project 7).
        val steering = Event.Heard("Kestrel, open Viber.", named = true)
        assertEquals(Effect.Converse(InputIntent.Command("open Viber."), steering), Rules.next(asked, steering).effects.last())
        // Without the question, as during a call with the phone unlocked, an instant command runs.
        assertTrue(heard(goal(GoalPhase.PAUSED), "Scroll down").effects.last() is Effect.RunCommand)
    }

    @Test
    fun stayOffAfterAnUnlockWaitsUntilTheGoalAndItsFinalReplyHaveEnded() {
        val locked = goal(GoalPhase.PAUSED, listening.copy(onWakeUp = WakeUpMode.STAY_OFF, screenOn = false, screenSeen = true))

        val unlocked = Rules.next(locked, Event.Screen(on = true))
        assertEquals(Mode.LISTENING, unlocked.state.mode)
        assertTrue(unlocked.state.offAfterGoal)

        // The goal ends, and the conversation holds its quiet clock while its final reply is spoken.
        val open = VoiceView(open = true)
        val replying = Rules.next(unlocked.state, Event.ConversationChanged(open.copy(replying = true))).state
        assertEquals(Mode.LISTENING, replying.mode)
        val says = Rules.next(replying, Event.ChannelSays(1, "Cancelled. I didn't change anything.")).state
        val spoken = Rules.next(says, Event.SpeechEnded).state
        assertEquals(Mode.LISTENING, spoken.mode)
        val off = Rules.next(spoken, Event.ConversationChanged(open))
        assertEquals(Mode.OFF, off.state.mode)
        assertTrue(Effect.StopService in off.effects)

        // Without a goal or a reply, Kestrel turns off at once.
        val idle = listening.copy(onWakeUp = WakeUpMode.STAY_OFF, screenOn = false, screenSeen = true)
        assertEquals(Mode.OFF, Rules.next(idle, Event.Screen(on = true)).state.mode)
    }

    @Test
    fun theAssistantGestureAndLateAnswersCountAPausedGoalAsAWorkingOne() {
        val paused = goal(GoalPhase.PAUSED).copy(speeches = 1)

        assertEquals(Step(paused, listOf(Effect.StopSaying)), Rules.next(paused, Event.Assist))
        assertFalse(Rules.next(paused.copy(questionEnded = true), Event.MicBusy(busy = false)).state.questionEnded)
    }

    @Test
    fun deleteHistoryGoesToTheConversationAndItsQuestionTakesASpokenYes() {
        val delete = Event.Heard("Kestrel, delete the history.", named = true)
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Kestrel, delete the history.")), Effect.Converse(InputIntent.DeleteHistory, delete)),
            Rules.next(listening, delete).effects,
        )
        // Its question is the conversation's own, which the voice answers as any yes-or-no question.
        val question = asked(QuestionKind.YES_NO, listening.copy(conversation = VoiceView(open = true)))
        assertEquals(InputIntent.Answer(ChannelAnswer.Yes), (heard(question, "Yes.").effects.last() as Effect.Converse).intent)
        assertEquals(listOf(Effect.Show(Feedback.Heard("Go on"))), heard(question, "Go on").effects.dropLast(1))
        // While a goal works or waits, the conversation decides what it means.
        val clear = Event.Heard("Kestrel, clear history", named = true)
        assertEquals(Effect.Converse(InputIntent.DeleteHistory, clear), Rules.next(acting, clear).effects.last())
        assertEquals(Effect.Converse(InputIntent.DeleteHistory, clear), Rules.next(handedOver(), clear).effects.last())
    }

    @Test
    fun rememberForgetAndSaveThatAsGoToTheConversation() {
        fun intent(state: State, text: String) = (heard(state, text, named = true).effects.last() as Effect.Converse).intent

        assertEquals(InputIntent.Remember("the gate code", "blue"), intent(listening, "Kestrel, remember that the gate code is blue."))
        assertEquals(InputIntent.Forget("the gate code"), intent(listening, "Kestrel, forget the gate code"))
        assertEquals(InputIntent.SaveRoutine("test routine"), intent(listening, "Kestrel, save that as test routine."))
        // A form that the parser does not take is a command.
        assertEquals(InputIntent.Command("remember to buy milk"), intent(listening, "Kestrel, remember to buy milk"))
        // During a hand-over too: the conversation decides when it runs.
        assertEquals(InputIntent.Forget("the gate code"), intent(handedOver(), "Kestrel, forget the gate code"))
    }

    @Test
    fun theWaitForAnAnswerDoesNotEndWhileKestrelStillSpeaksTheQuestion() {
        // A confirmation may take longer to say than the wait to start the answer (design D14 of sub-project 5); "Continue
        // the goal?" and Kestrel's own questions wait in the same way.
        for (kind in listOf(QuestionKind.CONFIRM, QuestionKind.CONTINUE_GOAL, QuestionKind.YES_NO)) {
            val question = Rules.next(acting, Event.ChannelAsks(4, "Shall I send it?", kind)).state
            assertTrue(question.speaking)
            assertEquals(Step(question), Rules.next(question, Event.AnswerWaitEnded(question.wait)))
            // The whole wait starts again once the question has been spoken.
            val spoken = Rules.next(question, Event.SpeechEnded)
            assertEquals(listOf(Effect.WaitForAnswer()), spoken.effects)
            assertEquals(
                listOf(Effect.Converse(InputIntent.Answer(ChannelAnswer.Silence))),
                Rules.next(spoken.state, Event.AnswerWaitEnded(spoken.state.wait)).effects,
            )
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
    fun theTaskEventsAndEffectsPrintNoWords() {
        val printed = listOf(
            Event.TaskSays(1, "secret"),
            Event.TaskAsks(1, "secret", TaskAsk.FREE),
            Event.TaskHandsOver(1, "secret"),
            Effect.AnswerTask(1, "secret"),
            Feedback.TaskText("secret"),
        ).joinToString()

        assertFalse(printed, printed.contains("secret"))
    }


    // Design D2 of sub-project 5 (section 14.4): the pause for a call or the lock, "Continue the task?" and its timers.
    private fun pausedByLock(from: State = runningTask): State = Rules.next(from, Event.Screen(on = false)).state

    // "Continue the task?" after the unlock, spoken.
    private fun continueAsked(from: State = pausedByLock()): State =
        Rules.next(Rules.next(from.copy(screenSeen = true), Event.Screen(on = true)).state, Event.SpeechEnded).state

    @Test
    fun theUnlockAsksContinueTheTask() {
        val unlocked = Rules.next(pausedByLock().copy(screenSeen = true), Event.Screen(on = true))

        assertEquals(
            listOf(Effect.Show(Feedback.ContinueQuestion), Effect.Say(Reply.ContinueQuestion), Effect.WaitForAnswer(), Effect.ArmTouches),
            unlocked.effects,
        )
        assertEquals(TaskAsk.CONTINUE, unlocked.state.taskAsking)
        assertEquals(TaskPhase.PAUSED, unlocked.state.taskPhase)
        assertEquals(Recognition.ALL, Rules.next(unlocked.state, Event.SpeechEnded).state.recognition)
    }

    @Test
    fun yesResumesThePausedTaskAndNoSilenceOrStopCancelIt() {
        val asked = continueAsked()

        val resumed = heard(asked, "Yes, please.")
        assertEquals(listOf(Effect.Show(Feedback.Heard("Yes, please.")), Effect.ResumeTask(1)), resumed.effects)
        assertEquals(TaskPhase.RUNNING, resumed.state.taskPhase)
        assertEquals(Work.RUNNING_TASK, resumed.state.work)
        assertEquals(Effect.ResumeTask(1), heard(asked, "Kestrel, continue", named = true).effects.last())

        for (no in listOf(heard(asked, "No."), heard(asked, "Stop"), Rules.next(asked, Event.AnswerWaitEnded(asked.wait)))) {
            assertTrue(Effect.CancelTask in no.effects)
            assertEquals(null, no.state.taskPhase)
            assertTrue(no.state.cancelled)
        }
        // Other speech is ignored during the question.
        assertEquals(Step(asked, listOf(Effect.Show(Feedback.Heard("Scroll down")))), heard(asked, "Scroll down"))
    }

    @Test
    fun aTouchAnswersContinueTheTaskOnlyOnceASecondHasPassed() {
        val asked = continueAsked()

        assertEquals(Step(asked), Rules.next(asked, Event.OwnerTouched))
        val armed = Rules.next(asked, Event.TouchesArmed(asked.continueAsked)).state
        assertTrue(armed.touchesArmed)
        assertTrue(Effect.CancelTask in Rules.next(armed, Event.OwnerTouched).effects)
        // An older question's second changes nothing, and a paused task without the question ignores touches.
        assertEquals(Step(asked), Rules.next(asked, Event.TouchesArmed(asked.continueAsked - 1)))
        assertEquals(Step(pausedByLock()), Rules.next(pausedByLock(), Event.OwnerTouched))
    }

    @Test
    fun kestrelOverContinueTheTaskLeavesTheQuestionOpen() {
        // The spotter hears "Kestrel" while Kestrel still speaks "Continue the task?".
        val speaking = Rules.next(pausedByLock().copy(screenSeen = true), Event.Screen(on = true)).state

        val spotted = Rules.next(speaking, Event.Spotted(Keyword.KESTREL)).state
        assertEquals(TaskAsk.CONTINUE, spotted.taskAsking)
        assertEquals(Work.IDLE, spotted.work)
        assertEquals(Effect.ResumeTask(1), heard(Rules.next(spotted, Event.SpeechEnded).state, "Kestrel, continue.", named = true).effects.last())
    }

    @Test
    fun aPausedTaskLetsTheInstantCommandsRunAndRefusesANewTask() {
        val asked = continueAsked()
        val replaced = heard(asked, "Stop. Kestrel, open Viber.", named = true)
        assertEquals(listOf(Effect.Show(Feedback.Heard("Stop. Kestrel, open Viber.")), Effect.CancelTask, Effect.RunTask(2, "open Viber.")), replaced.effects)
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Kestrel, open Viber.")), Effect.Show(Feedback.AnswerContinueFirst)),
            heard(asked, "Kestrel, open Viber.", named = true).effects,
        )
        // Without the question, as during a call with the phone unlocked, an instant command runs.
        val paused = pausedByLock().copy(screenOn = true)
        assertTrue(heard(paused, "Scroll down").effects.last() is Effect.RunCommand)
    }

    @Test
    fun aCallDuringAQuestionWithdrawsItAndTheEndOfTheCallAsksToContinue() {
        val question = asking(TaskAsk.CONFIRM)

        val ringing = Rules.next(question, Event.CallState(CallState.RINGING))
        assertTrue(Effect.PauseTask(1, PauseCause.INCOMING_CALL) in ringing.effects)
        assertEquals(null, ringing.state.taskAsking)
        val active = Rules.next(ringing.state, Event.CallState(CallState.ACTIVE)).state
        assertEquals(TaskPhase.PAUSED, active.taskPhase)

        val ended = Rules.next(active, Event.CallState(CallState.IDLE))
        assertEquals(Mode.LISTENING, ended.state.mode)
        assertTrue(Effect.Say(Reply.ContinueQuestion) in ended.effects)
        assertEquals(TaskAsk.CONTINUE, ended.state.taskAsking)
        // After a call that ended while the phone was locked, the question waits for the unlock.
        val locked = Rules.next(Rules.next(active, Event.Screen(on = false)).state, Event.CallState(CallState.IDLE))
        assertEquals(null, locked.state.taskAsking)
    }

    @Test
    fun aCallThatBecomesActiveWithoutRingingPausesForTheBackstop() {
        val active = Rules.next(runningTask, Event.CallState(CallState.ACTIVE))

        assertTrue(Effect.PauseTask(1, PauseCause.OUTGOING_CALL) in active.effects)
        assertEquals(Mode.IN_CALL, active.state.mode)
    }

    @Test
    fun aCallDuringAHandOverDoesNotPauseIt() {
        val handed = handedOver()

        val ringing = Rules.next(handed, Event.CallState(CallState.RINGING))

        assertEquals(TaskPhase.HANDED_OVER, ringing.state.taskPhase)
        assertFalse(ringing.effects.any { it is Effect.PauseTask })
        assertEquals(TaskPhase.HANDED_OVER, Rules.next(handed, Event.Screen(on = false)).state.taskPhase)
    }

    @Test
    fun aLockDuringContinueTheTaskWithdrawsTheQuestionUntilTheNextUnlock() {
        val asked = continueAsked()

        val dark = Rules.next(asked, Event.Screen(on = false))

        assertEquals(null, dark.state.taskAsking)
        assertEquals(TaskPhase.PAUSED, dark.state.taskPhase)
        assertFalse(dark.effects.any { it is Effect.PauseTask })
        assertTrue(Effect.Say(Reply.ContinueQuestion) in Rules.next(dark.state, Event.Screen(on = true)).effects)
    }

    @Test
    fun aPauseOfThirtyMinutesEndsTheTaskAndItsReportWaitsForTheUnlock() {
        val paused = pausedByLock()

        val expired = Rules.next(paused, Event.PauseExpired(paused.pause))
        assertEquals(listOf(Effect.ExpirePause(1)), expired.effects)
        assertEquals(null, expired.state.taskPhase)
        assertEquals(Step(paused), Rules.next(paused, Event.PauseExpired(paused.pause - 1)))

        val report = "I stopped the task because it was paused for a long time. I opened Viber."
        val held = Rules.next(expired.state, Event.TaskEnded(1, report))
        assertEquals(emptyList<Effect>(), held.effects)
        assertEquals(report, held.state.heldReport)

        val unlocked = Rules.next(held.state.copy(screenSeen = true), Event.Screen(on = true))
        assertEquals(listOf(Effect.Say(Reply.Text(report))), unlocked.effects)
        assertEquals(null, unlocked.state.heldReport)
    }

    @Test
    fun stayOffAfterAnUnlockWaitsUntilThePausedTaskHasEnded() {
        val paused = pausedByLock(runningTask.copy(onWakeUp = WakeUpMode.STAY_OFF))

        val asked = Rules.next(paused.copy(screenSeen = true), Event.Screen(on = true))
        assertEquals(Mode.LISTENING, asked.state.mode)
        assertTrue(asked.state.offAfterTask)
        assertTrue(Effect.Say(Reply.ContinueQuestion) in asked.effects)

        val no = heard(Rules.next(asked.state, Event.SpeechEnded).state, "No.")
        val reported = Rules.next(no.state, Event.TaskEnded(1, "Cancelled. I didn't change anything."))
        assertEquals(Mode.LISTENING, reported.state.mode)
        val off = Rules.next(reported.state, Event.SpeechEnded)
        assertEquals(Mode.OFF, off.state.mode)
        assertTrue(Effect.StopService in off.effects)

        // An expired pause's report is spoken first, and then Kestrel turns off.
        val expired = Rules.next(paused, Event.PauseExpired(paused.pause)).state
        val held = Rules.next(expired, Event.TaskEnded(1, "Report.")).state
        val spoken = Rules.next(held.copy(screenSeen = true), Event.Screen(on = true))
        assertEquals(listOf(Effect.Say(Reply.Text("Report."))), spoken.effects)
        assertEquals(Mode.OFF, Rules.next(spoken.state, Event.SpeechEnded).state.mode)
    }

    @Test
    fun theAssistantGestureAndLateAnswersCountAPausedTaskAsATask() {
        val paused = pausedByLock().copy(screenOn = true, speeches = 1)

        assertEquals(Step(paused, listOf(Effect.StopSaying)), Rules.next(paused, Event.Assist))
    }

    @Test
    fun deleteHistoryAsksAndOnlyYesDeletes() {
        val asked = heard(listening, "Kestrel, delete the history.", named = true)
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Kestrel, delete the history.")), Effect.Say(Reply.DeleteHistoryQuestion), Effect.WaitForAnswer()),
            asked.effects,
        )
        assertTrue(asked.state.deleteAsking)

        val yes = heard(asked.state, "Yes.")
        assertEquals(listOf(Effect.Show(Feedback.Heard("Yes.")), Effect.DeleteHistory), yes.effects)
        assertFalse(yes.state.deleteAsking)
        assertEquals(listOf(Effect.Show(Feedback.Heard("Go on"))), heard(asked.state, "Go on").effects)
        val spoken = Rules.next(asked.state, Event.SpeechEnded).state
        assertFalse(Rules.next(spoken, Event.AnswerWaitEnded(spoken.wait)).state.deleteAsking)
        assertEquals(Effect.Say(Reply.HistoryDeleted(true)), Rules.next(listening, Event.HistoryDeleted(true)).effects.last())
        // While a task exists, it is ignored (section 11.10).
        assertEquals(Step(runningTask), heard(runningTask, "Kestrel, clear history", named = true))
        assertEquals(listOf(Effect.Show(Feedback.Heard("Kestrel, delete history"))), heard(handedOver(), "Kestrel, delete history", named = true).effects)
    }

    @Test
    fun rememberForgetAndSaveThatAsRunAsKestrelsOwnTask() {
        val remember = heard(listening, "Kestrel, remember that the gate code is blue.", named = true)
        assertEquals(Effect.RunMemoryTask(1, Request.Remember("the gate code", "blue")), remember.effects.last())
        assertEquals(TaskPhase.RUNNING, remember.state.taskPhase)
        assertEquals(Effect.RunMemoryTask(1, Request.Forget("the gate code")), heard(listening, "Kestrel, forget the gate code", named = true).effects.last())
        assertEquals(Effect.RunMemoryTask(1, Request.SaveRoutine("test routine")), heard(listening, "Kestrel, save that as test routine.", named = true).effects.last())
        // A form that the parser does not take runs as a task.
        assertEquals(Effect.RunTask(1, "remember to buy milk"), heard(listening, "Kestrel, remember to buy milk", named = true).effects.last())
        // During a hand-over it waits, as a task does.
        assertEquals(Effect.Show(Feedback.FinishHandOverFirst), heard(handedOver(), "Kestrel, forget the gate code", named = true).effects.last())
    }

    @Test
    fun theWaitForAnAnswerDoesNotEndWhileKestrelStillSpeaksTheQuestion() {
        // A confirmation may take longer to say than the wait to start the answer (design D14 of sub-project 5).
        val question = asking(TaskAsk.CONFIRM)
        assertTrue(question.speaking)
        assertEquals(Step(question), Rules.next(question, Event.AnswerWaitEnded(question.wait)))
        // The whole wait starts again once the question has been spoken.
        val spoken = Rules.next(question, Event.SpeechEnded)
        assertEquals(listOf(Effect.WaitForAnswer()), spoken.effects)
        assertEquals(listOf(Effect.AnswerTask(1, null)), Rules.next(spoken.state, Event.AnswerWaitEnded(spoken.state.wait)).effects)
        // "Continue the task?" and the question before deleting the history wait in the same way.
        val continuing = Rules.next(pausedByLock().copy(screenSeen = true), Event.Screen(on = true)).state
        assertEquals(Step(continuing), Rules.next(continuing, Event.AnswerWaitEnded(continuing.wait)))
        val deleting = heard(listening, "Kestrel, delete history", named = true).state
        assertEquals(Step(deleting), Rules.next(deleting, Event.AnswerWaitEnded(deleting.wait)))
    }

    @Test
    fun aStopBeforeKestrelsOwnRequestsCancelsTheTaskFirst() {
        val forget = heard(runningTask, "Stop. Kestrel, forget the gate code.", named = true)
        assertEquals(
            listOf(
                Effect.Show(Feedback.Heard("Stop. Kestrel, forget the gate code.")),
                Effect.CancelTask,
                Effect.RunMemoryTask(2, Request.Forget("the gate code")),
            ),
            forget.effects,
        )
        assertEquals(TaskPhase.RUNNING, forget.state.taskPhase)
        // Once the task has gone, "delete history" asks as it does without a task.
        assertEquals(
            listOf(
                Effect.Show(Feedback.Heard("Stop. Kestrel, delete history")),
                Effect.CancelTask,
                Effect.Say(Reply.DeleteHistoryQuestion),
                Effect.WaitForAnswer(),
            ),
            heard(runningTask, "Stop. Kestrel, delete history", named = true).effects,
        )
        // In every phase: while the task asks, while it is paused, during "Continue the task?" and during a hand-over.
        for (state in listOf(asking(TaskAsk.CONFIRM), pausedByLock().copy(screenOn = true), continueAsked(), handedOver())) {
            val step = heard(state, "Stop. Kestrel, remember that the gate code is blue.", named = true)
            assertTrue("$state", Effect.CancelTask in step.effects)
            assertEquals("$state", Effect.RunMemoryTask(2, Request.Remember("the gate code", "blue")), step.effects.last())
        }
        assertTrue(Effect.RunMemoryTask(2, Request.SaveRoutine("test routine")) in heard(runningTask, "Stop. Kestrel, save that as test routine", named = true).effects)
        // During "Continue the task?" every request after "Stop." cancels the task first.
        val asked = continueAsked()
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop. Kestrel, open settings.")), Effect.CancelTask, Effect.OpenSettings),
            heard(asked, "Stop. Kestrel, open settings.", named = true).effects,
        )
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop. Kestrel, continue.")), Effect.CancelTask, Effect.Say(Reply.NoTaskToContinue)),
            heard(asked, "Stop. Kestrel, continue.", named = true).effects,
        )
        // With the screen off nothing may start, but the stop still cancels the task.
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop. Kestrel, forget the gate code")), Effect.CancelTask),
            heard(pausedByLock(), "Stop. Kestrel, forget the gate code", named = true).effects,
        )
    }

    @Test
    fun aLateTaskBusyLeavesAPausedTaskAndItsQuestionAlone() {
        val asked = continueAsked()

        assertEquals(Step(asked), Rules.next(asked, Event.TaskBusy(1)))
        assertEquals(Step(pausedByLock()), Rules.next(pausedByLock(), Event.TaskBusy(1)))
    }

    @Test
    fun duringTheDeleteQuestionTurningOffPausingAndStoppingKeepTheirMeaning() {
        val speaking = heard(listening, "Kestrel, delete history", named = true).state
        val asked = Rules.next(speaking, Event.SpeechEnded).state

        val off = heard(asked, "Stop Kestrel")
        assertEquals(Mode.OFF, off.state.mode)
        assertFalse(off.state.deleteAsking)
        val paused = heard(asked, "Stop listening")
        assertEquals(Mode.PAUSED, paused.state.mode)
        assertFalse(paused.state.deleteAsking)
        assertFalse(Effect.DeleteHistory in paused.effects)
        // "Stop" ends the question and Kestrel's voice, and the spotter's "stop" over the question ends it too.
        val stopped = heard(speaking, "Stop.")
        assertEquals(listOf(Effect.Show(Feedback.Heard("Stop.")), Effect.StopSaying), stopped.effects)
        assertFalse(stopped.state.deleteAsking)
        val spotted = Rules.next(speaking, Event.Spotted(Keyword.STOP))
        assertEquals(listOf(Effect.StopSaying), spotted.effects)
        assertFalse(spotted.state.deleteAsking)
    }

    @Test
    fun kestrelOrTheAssistantGestureDuringTheDeleteQuestionWaitsForTheTask() {
        val speaking = heard(listening, "Kestrel, delete history", named = true).state
```

with:

```kotlin
    fun aStopBeforeKestrelsOwnRequestsGivesTheStopFirst() {
        val forget = Event.Heard("Stop. Kestrel, forget the gate code.", named = true)
        assertEquals(
            listOf(
                Effect.Show(Feedback.Heard("Stop. Kestrel, forget the gate code.")),
                Effect.Converse(InputIntent.Stop),
                Effect.Converse(InputIntent.Forget("the gate code"), forget),
            ),
            Rules.next(acting, forget).effects,
        )
        // In every phase: while the goal asks, while it is paused, during "Continue the goal?" and during a hand-over.
        val remember = Event.Heard("Stop. Kestrel, remember that the gate code is blue.", named = true)
        for (state in listOf(asked(QuestionKind.CONFIRM), goal(GoalPhase.PAUSED), continueAsked(), handedOver())) {
            val step = Rules.next(state, remember)
            assertTrue("$state", Effect.Converse(InputIntent.Stop) in step.effects)
            assertEquals("$state", Effect.Converse(InputIntent.Remember("the gate code", "blue"), remember), step.effects.last())
        }
        // "Stop. Kestrel, open settings." stops the goal and opens them.
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop. Kestrel, open settings.")), Effect.Converse(InputIntent.Stop), Effect.OpenSettings),
            heard(continueAsked(), "Stop. Kestrel, open settings.", named = true).effects,
        )
        // With the screen off nothing may start, but the stop still stops the goal.
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop. Kestrel, forget the gate code")), Effect.Converse(InputIntent.Stop)),
            heard(pausedGoal, "Stop. Kestrel, forget the gate code", named = true).effects,
        )
    }

    // Kestrel's own question, "Delete the history?", spoken by the voice (design 11 of sub-project 7).
    private fun ownQuestion(): State {
        val view = VoiceView(open = true, asking = QuestionKind.DELETE_HISTORY, question = 4)
        return Rules.next(listening.copy(conversation = view), Event.ChannelAsks(4, "Delete the history?", QuestionKind.DELETE_HISTORY)).state
    }

    @Test
    fun duringKestrelsOwnQuestionTurningOffPausingAndStoppingKeepTheirMeaning() {
        val speaking = ownQuestion()
        val asked = Rules.next(speaking, Event.SpeechEnded).state
        val withdrawn = Effect.Converse(InputIntent.Answer(ChannelAnswer.Interrupted))

        val off = heard(asked, "Stop Kestrel")
        assertEquals(Mode.OFF, off.state.mode)
        assertTrue(withdrawn in off.effects)
        val paused = heard(asked, "Stop listening")
        assertEquals(Mode.PAUSED, paused.state.mode)
        assertTrue(withdrawn in paused.effects)
        // "Stop" ends the question and Kestrel's voice, and so does the spotter's "stop" over the question.
        assertEquals(
            listOf(Effect.Show(Feedback.Heard("Stop.")), Effect.StopSaying, Effect.Converse(InputIntent.Stop)),
            heard(speaking, "Stop.").effects,
        )
        assertEquals(listOf(Effect.StopSaying, Effect.Converse(InputIntent.Stop)), Rules.next(speaking, Event.Spotted(Keyword.STOP)).effects)
    }

    @Test
    fun kestrelOrTheAssistantGestureDuringKestrelsOwnQuestionWithdrawsItAndWaitsForACommand() {
        val speaking = ownQuestion()
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
            assertFalse(wait.state.deleteAsking)
            // The question ended without deleting anything, and the task that the owner then says runs.
            assertFalse(Effect.DeleteHistory in wait.effects)
            assertEquals(Effect.RunTask(1, "Open Viber."), heard(wait.state, "Open Viber.").effects.last())
```

with:

```kotlin
            assertTrue(Effect.Converse(InputIntent.Answer(ChannelAnswer.Interrupted)) in wait.effects)
            // The command that the owner then says reaches the conversation.
            val command = Event.Heard("Open Viber.", named = false)
            assertEquals(Effect.Converse(InputIntent.Command("Open Viber."), command), Rules.next(wait.state, command).effects.last())
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
    fun thePauseEventsAndEffectsPrintNoWords() {
        val printed = listOf(
            Effect.RunMemoryTask(1, Request.Remember("secret", "secret")),
            Request.Forget("secret"),
            Request.SaveRoutine("secret"),
            pausedByLock().copy(heldReport = "secret"),
```

with:

```kotlin
    fun theConversationsEventsAndEffectsPrintNoWords() {
        val printed = listOf(
            Event.ChannelSays(1, "secret"),
            Event.ChannelShows("secret"),
            Event.ChannelAsks(4, "secret", QuestionKind.FREE),
            Event.ChannelHandsOver("secret"),
            Effect.Converse(InputIntent.Command("secret"), Event.Heard("secret", named = false)),
            Reply.Channel(1, "secret"),
            Feedback.ConversationText("secret"),
            Request.Forget("secret"),
            asked(QuestionKind.FREE),
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
            heard(pausedGoal, "Stop. Kestrel, forget the gate code", named = true).effects,
        )
    }

    // Kestrel's own question, "Delete the history?", spoken by the voice (design 11 of sub-project 7).
```

with:

```kotlin
            heard(pausedGoal, "Stop. Kestrel, forget the gate code", named = true).effects,
        )
    }

    // Design D2 of sub-project 5: "Stop. Kestrel, …" stops Kestrel's own request too, while it asks or while it runs
    // without a question, and the command after it reaches the conversation, so that neither the stop nor the command is
    // lost (review of plan 7D's Task 3, M1). Without a goal or a request of Kestrel's own, the words stay one command.
    @Test
    fun aStopBeforeACommandStopsKestrelsOwnRequestFirst() {
        val command = Event.Heard("Stop. Kestrel, open Viber.", named = true)
        val running = listening.copy(conversation = VoiceView(open = true, ownRequest = true))
        for (state in listOf(ownQuestion(), running)) {
            assertEquals(
                "$state",
                listOf(Effect.Converse(InputIntent.Stop), Effect.Converse(InputIntent.Command("open Viber."), command)),
                Rules.next(state, command).effects.filterIsInstance<Effect.Converse>(),
            )
        }
        assertEquals(
            listOf(Effect.Converse(InputIntent.Command("open Viber."), command)),
            Rules.next(listening.copy(conversation = VoiceView(open = true)), command).effects.filterIsInstance<Effect.Converse>(),
        )
    }

    // Kestrel's own question, "Delete the history?", spoken by the voice (design 11 of sub-project 7).
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/OrchestratorTest.kt`, replace:

```kotlin
package dev.kestrel.app.orchestrator

import dev.kestrel.voice.listen.Recognition
```

with:

```kotlin
package dev.kestrel.app.orchestrator

import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.voice.listen.Recognition
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/OrchestratorTest.kt`, replace:

```kotlin
        assertEquals(listOf(Effect.RunTask(1, "open the settings.")), performed.filterIsInstance<Effect.RunTask>())
```

with:

```kotlin
        assertEquals(listOf(InputIntent.Command("open the settings.")), performed.filterIsInstance<Effect.Converse>().map { it.intent })
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/OrchestratorTest.kt`, replace:

```kotlin
        runCurrent()

        assertEquals(Work.RUNNING_TASK, orchestrator.state.value.work)
        assertEquals(
```

with:

```kotlin
        runCurrent()

        assertEquals(Work.IDLE, orchestrator.state.value.work)
        assertEquals(
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/OrchestratorTest.kt`, replace:

```kotlin
                Effect.RunTask(1, "open the settings."),
```

with:

```kotlin
                Effect.Converse(InputIntent.Command("open the settings."), Event.Heard("Kestrel, open the settings.", named = true)),
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/OrchestratorTest.kt`, replace:

```kotlin
    fun aTaskInTimeEndsTheWait() = runTest {
```

with:

```kotlin
    fun aCommandInTimeEndsTheWait() = runTest {
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/OrchestratorTest.kt`, replace:

```kotlin
        assertEquals(Work.RUNNING_TASK, orchestrator.state.value.work)
```

with:

```kotlin
        assertEquals(Work.IDLE, orchestrator.state.value.work)
        assertTrue(Effect.Converse(InputIntent.Command("Open the settings."), Event.Heard("Open the settings.", named = false)) in performed)
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/OrchestratorTest.kt`, replace:

```kotlin
        // RunTask comes after the failed effect in the same step.
        assertEquals(listOf(Effect.Show(Feedback.ModeChanged(Mode.LISTENING)), Effect.RunTask(1, "open the settings.")), performed)
```

with:

```kotlin
        // The input comes after the failed effect in the same step.
        assertEquals(
            listOf(
                Effect.Show(Feedback.ModeChanged(Mode.LISTENING)),
                Effect.Converse(InputIntent.Command("open the settings."), Event.Heard("Kestrel, open the settings.", named = true)),
            ),
            performed,
        )
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/OrchestratorTest.kt`, replace:

```kotlin
        assertEquals(Effect.RunTask(1, "open the settings."), performed.last())
    }

    @Test
    fun aReplyOrTaskThatCannotStartDoesNotLeaveKestrelWaitingForIt() = runTest {
        val orchestrator = Orchestrator(
            backgroundScope,
            { effect ->
                if (effect is Effect.Say || effect is Effect.RunTask) throw IllegalStateException("No voice")
```

with:

```kotlin
        assertEquals(InputIntent.Command("open the settings."), (performed.last() as Effect.Converse).intent)
    }

    @Test
    fun aReplyOrAnInputThatCannotStartDoesNotLeaveKestrelWaitingForIt() = runTest {
        val orchestrator = Orchestrator(
            backgroundScope,
            { effect ->
                if (effect is Effect.Say || effect is Effect.Converse) throw IllegalStateException("No voice")
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/OrchestratorTest.kt`, replace:

```kotlin
    fun aTaskThatCannotStartSaysSo() = runTest {
        val orchestrator = Orchestrator(
            backgroundScope,
            { effect ->
                if (effect is Effect.RunTask) throw IllegalStateException("No agent")
                performed += effect
            },
            { errors += it },
            taskFailed = "I couldn't start the task.",
        )

        orchestrator.send(Event.Toggle)
        orchestrator.send(Event.Heard("Kestrel, open the settings.", named = true))
        runCurrent()

        assertEquals(Work.IDLE, orchestrator.state.value.work)
        assertEquals(Effect.Say(Reply.Text("I couldn't start the task.")), performed.last())
    }

    @Test
    fun aTaskQuestionEndsAfterItsWaitAndAHandOverAfterThreeMinutes() = runTest {
        val orchestrator = Orchestrator(backgroundScope, { performed += it }, { errors += it })
        orchestrator.send(Event.Toggle)
        orchestrator.send(Event.Heard("Kestrel, pay the bill.", named = true))
        orchestrator.send(Event.TaskAsks(1, "Which account?", TaskAsk.FREE))
        orchestrator.send(Event.SpeechEnded)
        runCurrent()
        advanceTimeBy(15_001)
        runCurrent()
        assertEquals(Effect.AnswerTask(1, null), performed.last())

        orchestrator.send(Event.TaskHandsOver(1, "This is a payment. Please finish it yourself, then say 'Kestrel, continue'."))
        orchestrator.send(Event.SpeechEnded)
        runCurrent()
        advanceTimeBy(179_999)
        runCurrent()
        assertFalse(Effect.ExpireHandOver(1) in performed)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(Effect.ExpireHandOver(1), performed.last())
        assertEquals(emptyList<Exception>(), errors)
    }


    @Test
    fun aPauseEndsAfterThirtyMinutesAndTouchesAnswerOneSecondAfterTheQuestion() = runTest {
        val orchestrator = Orchestrator(backgroundScope, { performed += it }, { errors += it })
        orchestrator.send(Event.Toggle)
        orchestrator.send(Event.Screen(on = true))
        orchestrator.send(Event.Heard("Kestrel, open Viber.", named = true))
        orchestrator.send(Event.Screen(on = false))
        orchestrator.send(Event.Screen(on = true))
        runCurrent()
        assertEquals(TaskAsk.CONTINUE, orchestrator.state.value.taskAsking)
        advanceTimeBy(999)
        runCurrent()
        assertFalse(orchestrator.state.value.touchesArmed)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(orchestrator.state.value.touchesArmed)

        orchestrator.send(Event.Screen(on = false))
        runCurrent()
        advanceTimeBy(1_800_001)
        runCurrent()
        assertEquals(Effect.ExpirePause(1), performed.last())
```

with:

```kotlin
    fun theConversationsQuestionEndsInSilenceAfterItsWaitUnlessItEndedOnItsCard() = runTest {
        val orchestrator = Orchestrator(backgroundScope, { performed += it }, { errors += it })
        orchestrator.send(Event.Toggle)
        orchestrator.send(Event.ChannelAsks(4, "Which account?", QuestionKind.FREE))
        orchestrator.send(Event.SpeechEnded)
        runCurrent()
        advanceTimeBy(14_999)
        runCurrent()
        assertFalse(performed.any { it is Effect.Converse })
        advanceTimeBy(2)
        runCurrent()
        assertEquals(Effect.Converse(InputIntent.Answer(ChannelAnswer.Silence)), performed.last())

        // The question ended on its card, and the voice channel withdrew it: its wait stops.
        orchestrator.send(Event.ChannelAsks(5, "Which account?", QuestionKind.FREE))
        orchestrator.send(Event.SpeechEnded)
        orchestrator.send(Event.ChannelWithdraws(5))
        runCurrent()
        advanceTimeBy(36_000)
        runCurrent()
        assertEquals(1, performed.count { it is Effect.Converse })
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
package dev.kestrel.app.listen

import dev.kestrel.app.agent.AgentTexts
import dev.kestrel.app.commands.CommandConversation
```

with:

```kotlin
package dev.kestrel.app.listen

import dev.kestrel.app.commands.CommandConversation
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
import dev.kestrel.app.orchestrator.Effect
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.PauseCause
import dev.kestrel.app.orchestrator.Request
import dev.kestrel.app.orchestrator.TaskAsk
```

with:

```kotlin
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.Request
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
import dev.kestrel.commands.Outcome
```

with:

```kotlin
import dev.kestrel.commands.Outcome
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
    fun aTaskWithoutTheAgentSaysThatItCouldNotStart() = runTest {
        val listening = listeningWithSession()
```

with:

```kotlin
    fun aCommandReachesTheConversationAsTheVoicesInput() = runTest {
        val listening = listeningWithSession()
        val inputs = mutableListOf<OwnerInput>()
        listening.converse = { inputs += it }
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
        assertEquals(listOf(ListeningTexts.TASK_FAILED), sessions.opened.single().said)
        assertEquals(Work.IDLE, listening.state.value.work)
        assertEquals(1, listening.state.value.task)
```

with:

```kotlin
        assertEquals(InputChannel.VOICE, inputs.single().channel)
        assertEquals(InputIntent.Command("open the settings."), inputs.single().intent)
        assertEquals(emptyList<String>(), sessions.opened.single().said)
        assertEquals(Work.IDLE, listening.state.value.work)
    }

    @Test
    fun theVoicePathReturnsOnceItsWordsHaveBeenSpokenOrWhileOffShown() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()

        val said = async { listening.voicePath.say("Done.") }
        runCurrent()
        assertEquals(listOf("Done."), session.said)
        assertEquals("Done.", shown.last())
        assertFalse(said.isCompleted)
        session.replies.single().complete(Spoke.FINISHED)
        runCurrent()
        assertTrue(said.isCompleted)

        listening.send(Event.TurnOff)
        runCurrent()
        val shownOnly = async { listening.voicePath.say("Later.") }
        runCurrent()
        assertTrue(shownOnly.isCompleted)
        assertEquals("Later.", shown.last())
    }

    @Test
    fun theWaitForACommandAfterYesReachesTheConversation() = runTest {
        val listening = listeningWithSession()
        val awaiting = mutableListOf<Boolean>()
        listening.awaitingCommand = { awaiting += it }

        listening.send(Event.Heard("Kestrel", named = true))
        runCurrent()
        listening.send(Event.Heard("Open Viber.", named = false))
        runCurrent()

        assertEquals(listOf(true, false), awaiting.dropWhile { !it })
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
    fun aReplyThatCannotBeSpokenIsShownOnTheBar() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        listening.send(Event.Heard("Kestrel, open the settings.", named = true))
        runCurrent()
        shown.clear()
```

with:

```kotlin
    fun aReplyThatCannotBeSpokenIsShownOnTheBar() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        listening.send(Event.SettingsUnavailable)
        runCurrent()
        shown.clear()
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
        runCurrent()
        listening.send(Event.Heard("Kestrel, open the settings.", named = true))
```

with:

```kotlin
        runCurrent()
        listening.send(Event.SettingsUnavailable)
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
            listOf(ListeningTexts.TASK_FAILED, "Kestrel, open the settings.", ListeningTexts.TASK_FAILED, "Kestrel", ListeningTexts.YES),
```

with:

```kotlin
            listOf(ListeningTexts.SETTINGS_NEED_SERVICE, ListeningTexts.SETTINGS_NEED_SERVICE, "Kestrel", ListeningTexts.YES),
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
    fun aReplyThatAndroidInterruptsIsShownOnTheBar() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        listening.send(Event.Heard("Kestrel, open the settings.", named = true))
        runCurrent()
        shown.clear()
```

with:

```kotlin
    fun aReplyThatAndroidInterruptsIsShownOnTheBar() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        listening.send(Event.SettingsUnavailable)
        runCurrent()
        shown.clear()
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
        assertEquals(listOf(ListeningTexts.TASK_FAILED), shown)
```

with:

```kotlin
        assertEquals(listOf(ListeningTexts.SETTINGS_NEED_SERVICE), shown)
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
        // The answer is a task, and its reply goes to the bar, because there is no voice (section 14.6).
        listening.send(Event.Heard("Open the settings.", named = false))
        runCurrent()
        assertEquals(ListeningTexts.TASK_FAILED, shown.last())
```

with:

```kotlin
        // Any other reply goes to the bar, because there is no voice (section 14.6).
        listening.send(Event.SettingsUnavailable)
        runCurrent()
        assertEquals(ListeningTexts.SETTINGS_NEED_SERVICE, shown.last())
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
    fun aTaskWaitsUntilTheCancelledCommandHasCompleted() = runTest {
```

with:

```kotlin
    fun aCommandReachesTheConversationAtOnceWhileTheCancelledCommandStillHoldsTheScreen() = runTest {
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
        val tasks = mutableListOf<Int>()
        listening.runTask = { task, _ -> tasks += task }

        // The request cancels the command first; the task runs only once its job has completed.
        listening.send(Event.Heard("Kestrel, open Viber.", named = true))
        runCurrent()
        assertEquals(emptyList<Int>(), tasks)

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(1), tasks)
    }

    @Test
    fun aTaskThatIsStoppedWhileItWaitsForTheCancelledCommandNeverStarts() = runTest {
        val listening = listeningWithSession()
        val gate = CompletableDeferred<Unit>()
        runner.action = {
            withContext(NonCancellable) { gate.await() }
            CommandResult(Outcome.DONE)
        }
        listening.send(Event.Heard("Scroll down", named = false))
        runCurrent()
        val tasks = mutableListOf<Int>()
        listening.runTask = { task, _ -> tasks += task }
        listening.send(Event.Heard("Kestrel, open Viber.", named = true))
        runCurrent()

        listening.send(Event.Heard("Stop.", named = false))
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(emptyList<Int>(), tasks)
        // The task that never started ended for the Orchestrator, with the "Cancelled." of every other early cancel.
        assertEquals(listOf(AgentTexts.CANCELLED), sessions.opened.single().said)
        assertFalse(listening.state.value.cancelled)
        assertEquals(Work.IDLE, listening.state.value.work)
    }

    @Test
    fun aPauseWhileTheTaskWaitsForTheCancelledCommandReachesItWhenItStarts() = runTest {
        val listening = listeningWithSession()
        val gate = CompletableDeferred<Unit>()
        runner.action = {
            withContext(NonCancellable) { gate.await() }
            CommandResult(Outcome.DONE)
        }
        listening.send(Event.Heard("Scroll down", named = false))
        runCurrent()
        val calls = mutableListOf<Any>()
        listening.runTask = { task, _ -> calls += "run $task" }
        listening.steerTask = { calls += it }
        listening.send(Event.Heard("Kestrel, open Viber.", named = true))
        runCurrent()
        listening.send(Event.Screen(on = false))
        runCurrent()
        calls.clear()

        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf("run 1", Effect.PauseTask(1, PauseCause.LOCK)), calls)
    }

    @Test
    fun aTaskQuestionAHandOverAndContinueTheTaskThatCannotBeSpokenStayKeptOnTheBar() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        listening.runTask = { _, _ -> }
        listening.send(Event.Screen(on = true))
        listening.send(Event.Heard("Kestrel, open Viber.", named = true))
        runCurrent()

        listening.send(Event.TaskAsks(1, "Shall I send it?", TaskAsk.CONFIRM))
```

with:

```kotlin
        val inputs = mutableListOf<InputIntent>()
        listening.converse = { inputs += it.intent }

        // The request cancels the command first; the goal's round takes the screen's turn itself (design 7.1 of
        // sub-project 7), so the input does not wait for the command's job.
        listening.send(Event.Heard("Kestrel, open Viber.", named = true))
        runCurrent()
        assertEquals(listOf<InputIntent>(InputIntent.Command("open Viber.")), inputs)

        gate.complete(Unit)
        runCurrent()
        assertEquals(1, inputs.size)
    }

    @Test
    fun theConversationsQuestionAndHandOverThatCannotBeSpokenStayKeptOnTheBar() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        listening.send(Event.Screen(on = true))
        runCurrent()

        listening.send(Event.ChannelAsks(4, "Shall I send it?", QuestionKind.CONFIRM))
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
        listening.send(Event.Heard("Yes.", named = false))
        listening.send(Event.TaskHandsOver(1, "Please pay yourself, then say 'Kestrel, continue'."))
```

with:

```kotlin
        listening.send(Event.ChannelHandsOver("Please pay yourself, then say 'Kestrel, continue'."))
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
        assertEquals(before, shown.size)

        listening.send(Event.Heard("Kestrel, continue.", named = true))
        listening.send(Event.Screen(on = false))
        listening.send(Event.Screen(on = true))
        runCurrent()
        before = shown.size
        session.replies.last().complete(Spoke.REFUSED)
        runCurrent()
        assertEquals(before, shown.size)
        assertEquals(
            listOf("Shall I send it?", "Please pay yourself, then say 'Kestrel, continue'.", ListeningTexts.CONTINUE_TASK),
            kept,
        )
        assertEquals(0, releases)

        // "Continue the task?" fades once it has its answer.
        listening.send(Event.Heard("No.", named = false))
        runCurrent()
        assertEquals(1, releases)
    }

    @Test
    fun aDeferredTaskThatThrowsIsReportedAndSaysThatItCouldNotStart() = runTest {
        val listening = listeningWithSession()
        val gate = CompletableDeferred<Unit>()
        runner.action = {
            withContext(NonCancellable) { gate.await() }
            CommandResult(Outcome.DONE)
        }
        listening.send(Event.Heard("Scroll down", named = false))
        runCurrent()
        listening.runTask = { _, _ -> throw IllegalStateException("No agent") }
        listening.send(Event.Heard("Kestrel, open Viber.", named = true))
        runCurrent()
        assertEquals(Work.RUNNING_TASK, listening.state.value.work)

        gate.complete(Unit)
        runCurrent()

        assertEquals("An effect", reports.single().first)
        assertEquals(Work.IDLE, listening.state.value.work)
        assertEquals(ListeningTexts.TASK_FAILED, sessions.opened.single().said.last())
```

with:

```kotlin
        assertEquals(before, shown.size)
        assertEquals(listOf("Shall I send it?", "Please pay yourself, then say 'Kestrel, continue'."), kept)
        // The voice channel lets them go when they end (VoiceChannelTest).
        assertEquals(0, releases)
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
        listening.send(Event.Spotted(Keyword.STOP))
        runCurrent()

        assertEquals(listOf("stop speaking"), log)
    }
```

with:

```kotlin
        listening.send(Event.Spotted(Keyword.STOP))
        runCurrent()

        assertEquals(listOf("stop speaking"), log)
        // The voice channel drops the spoken outputs that waited for their turn (decision 10 of sub-project 7).
        assertEquals(1L, listening.voicePath.speechStops)
    }
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
    fun eventsFromAClosedSessionAreIgnored() = runTest {
        val listening = listeningWithSession()
        val old = sessions.opened.single().listener
        listening.send(Event.TurnOff)
        runCurrent()
```

with:

```kotlin
    fun eventsFromAClosedSessionAreIgnored() = runTest {
        val listening = listeningWithSession()
        val old = sessions.opened.single().listener
        val inputs = mutableListOf<OwnerInput>()
        listening.converse = { inputs += it }
        listening.send(Event.TurnOff)
        runCurrent()
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
        runCurrent()

        assertEquals(0, listening.state.value.task)
    }
```

with:

```kotlin
        runCurrent()

        assertEquals(emptyList<OwnerInput>(), inputs)
    }
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
        val old = sessions.opened.single().listener

        // The owner turns Kestrel off and on again, and the old session's transcript arrives before the controller has
        // handled either: it must not run as a task in the new state.
```

with:

```kotlin
        val old = sessions.opened.single().listener
        val inputs = mutableListOf<OwnerInput>()
        listening.converse = { inputs += it }

        // The owner turns Kestrel off and on again, and the old session's transcript arrives before the controller has
        // handled either: it must not reach the conversation in the new state.
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
        assertEquals(0, listening.state.value.task)
```

with:

```kotlin
        assertEquals(emptyList<OwnerInput>(), inputs)
```

The overlapping replies' test had its second reply from the old task path, whose stub said `ListeningTexts.TASK_FAILED` at once. A command now reaches the conversation, which answers later through `Listening.voicePath`, as the app graph's engine does, so the test's conversation says its reply there.

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
        listening.send(Event.Heard("Kestrel", named = true))
        runCurrent()
        // A transcript that arrives while "Yes?" still plays is the task, and the stub's reply queues behind "Yes?".
```

with:

```kotlin
        listening.converse = { launch { listening.voicePath.say("Opened.") } }
        listening.send(Event.Heard("Kestrel", named = true))
        runCurrent()
        // A transcript that arrives while "Yes?" still plays goes to the conversation, whose reply queues behind "Yes?".
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
        val session = sessions.opened.single()
        listening.send(Event.Heard("Kestrel, open the settings.", named = true))
```

with:

```kotlin
        val session = sessions.opened.single()
        listening.send(Event.SettingsUnavailable)
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
        assertEquals(listOf(ListeningTexts.RINGING, ListeningTexts.TASK_FAILED), shown)
```

with:

```kotlin
        assertEquals(listOf(ListeningTexts.RINGING, ListeningTexts.SETTINGS_NEED_SERVICE), shown)
```

The test of `AgentTexts.replaced`, which goes with this task's bar for a replaced task (the conversation shows a replaced goal's report itself, design 7.3 of sub-project 7), keeps its second check under a new name.

In `app/src/test/kotlin/dev/kestrel/app/agent/AgentTextsTest.kt`, replace:

```kotlin
    fun aReplacedTasksReportForTheBarStartsWithCancelled() {
        assertEquals("Cancelled: I opened Viber.", AgentTexts.replaced(TaskReport(EndReason.CANCELLED, ledger = listOf("opened Viber"))))
        assertEquals("I couldn't start the task: ${AgentTexts.NO_PROFILE}", AgentTexts.couldNotStart(AgentTexts.NO_PROFILE))
```

with:

```kotlin
    fun aRoundThatCannotStartSaysWhy() {
        assertEquals("I couldn't start the task: ${AgentTexts.NO_PROFILE}", AgentTexts.couldNotStart(AgentTexts.NO_PROFILE))
```

In `app/src/testDebug/kotlin/dev/kestrel/app/debug/CommandTagsTest.kt`, replace:

```kotlin
import dev.kestrel.commands.Outcome
```

with:

```kotlin
import dev.kestrel.commands.Outcome
import dev.kestrel.conversation.machine.ConversationEffect
import dev.kestrel.conversation.machine.ConversationEvent
import dev.kestrel.conversation.machine.ConversationState
import dev.kestrel.conversation.machine.MachineStep
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput
```

In `app/src/testDebug/kotlin/dev/kestrel/app/debug/CommandTagsTest.kt`, replace:

```kotlin
    fun aTaggedTranscriptThatStartsATaskGetsTheTasksNumberAndPassesItsOptionsBeforeTheTaskRuns() {
        step(Event.Toggle)
        val task = Event.Heard("Kestrel, open the maps.", named = true)
        assertTrue(tags.register("task", task, VoiceHooks.readOf(task.text, named = true), mapOf("model" to "test-model")))

        val before = state
        val step = Rules.next(before, task)
        state = step.state
        val started = tags.onStep(task, before, step)

        assertEquals(CommandTags.StartedTask(1, mapOf("model" to "test-model")), started)
        assertEquals(CommandTags.Tagged(CommandTags.Role.TASK, 0, "task", task = 1), tags.tagged("task"))
        // An untagged task passes nothing.
        step(Event.TaskEnded(1, reply = null))
        val other = Event.Heard("Kestrel, open the settings.", named = true)
        val next = Rules.next(state, other)
        assertNull(tags.onStep(other, state, next))
```

with:

```kotlin
    fun aTaggedTranscriptsInputCarriesItsOptionsAndTheTagGetsTheCommandThatItStarted() {
        step(Event.Toggle)
        val task = Event.Heard("Kestrel, open the maps.", named = true)
        assertTrue(tags.register("task", task, VoiceHooks.readOf(task.text, named = true), mapOf("model" to "test-model")))
        step(task)
        assertEquals(CommandTags.Tagged(CommandTags.Role.CONVERSE, 0, "task"), tags.tagged("task"))

        // Listening makes the input of the transcript; the tag's options go with it.
        val input = tags.input(task, OwnerInput(7, InputChannel.VOICE, InputIntent.Command("open the maps."), 0))
        assertEquals(mapOf("model" to "test-model"), input.options)
        // The conversation starts the goal as command 3, here on the input's own step.
        val started = ConversationEffect.StartGoal(3, 1, input, "open the maps.")
        tags.onConversationStep(ConversationEvent.Input(input), ConversationState(), MachineStep(ConversationState(), listOf(started)))
        assertEquals(CommandTags.Tagged(CommandTags.Role.TASK, 0, "task", task = 3), tags.tagged("task"))
        assertEquals(3, tags.lastGoalWords)

        // An untagged transcript's input carries nothing.
        val other = Event.Heard("Kestrel, open the settings.", named = true)
        step(other)
        val plain = OwnerInput(8, InputChannel.VOICE, InputIntent.Command("open the settings."), 0)
        assertEquals(plain, tags.input(other, plain))
    }

    @Test
    fun aTaggedInputThatStartsNothingAndWaitsForNothingDidNothing() {
        step(Event.Toggle)
        val stop = Event.Heard("Kestrel, continue", named = true)
        assertTrue(tags.register("go-on", stop, VoiceHooks.readOf(stop.text, named = true)))
        step(stop)
        val input = tags.input(stop, OwnerInput(9, InputChannel.VOICE, InputIntent.Continue, 0))

        // The conversation took it, as steering or an answer, without starting work for it.
        tags.onConversationStep(ConversationEvent.Input(input), ConversationState(), MachineStep(ConversationState()))

        assertEquals(CommandTags.Role.NONE, tags.tagged("go-on")?.role)
```

In `app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt`, replace:

```kotlin
import dev.kestrel.agent.port.AnswerKind
import dev.kestrel.app.agent.AgentRunner
import dev.kestrel.app.agent.TaskEvidence
import dev.kestrel.app.orchestrator.TaskAsk
```

with:

```kotlin
import dev.kestrel.app.agent.TaskEvidence
import dev.kestrel.conversation.channel.QuestionKind
```

In `app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt`, replace:

```kotlin
        val ended = AgentRunner.Ended(report, touches = 2, signals = 1, evidence = TaskEvidence(spoken = "Sent to Saved Messages. I told Ana that you're late for maple heron."))
```

with:

```kotlin
        val ended = AgentHookAnswers.Ended(report, "Sent to Saved Messages. I told Ana that you're late for maple heron.", touches = 2, signals = 1)
```

In `app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt`, replace:

```kotlin
        assertTrue(AgentHookAnswers.result(AgentRunner.Ended(report, evidence = TaskEvidence(spoken = "It is 87 percent.")), null, null, "87").endsWith(" digits=1"))
        assertFalse(AgentHookAnswers.result(AgentRunner.Ended(report, evidence = TaskEvidence(spoken = "It is 87 percent.")), null, null).contains("digits="))
```

with:

```kotlin
        assertTrue(AgentHookAnswers.result(AgentHookAnswers.Ended(report, "It is 87 percent."), null, null, "87").endsWith(" digits=1"))
        assertFalse(AgentHookAnswers.result(AgentHookAnswers.Ended(report, "It is 87 percent."), null, null).contains("digits="))
```

In `app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt`, replace:

```kotlin
        assertNull(AgentHookAnswers.answerRefusal(TaskAsk.CONFIRM, "confirm", Category.SENDING, "sending"))
        assertNull(AgentHookAnswers.answerRefusal(TaskAsk.CONFIRM, "cancel", Category.DELETING, null))
        assertNull(AgentHookAnswers.answerRefusal(TaskAsk.CONFIRM, "no", Category.DELETING, null))
        assertNull(AgentHookAnswers.answerRefusal(TaskAsk.YES_NO, "no", null, null))
        assertNull(AgentHookAnswers.answerRefusal(null, "continue", null, null))

        // The confirmation changed between agent-state and agent-answer.
        assertNotNull(AgentHookAnswers.answerRefusal(TaskAsk.CONFIRM, "confirm", Category.DELETING, "sending"))
        assertNotNull(AgentHookAnswers.answerRefusal(TaskAsk.CONFIRM, "cancel", Category.DELETING, "sending"))
        assertNotNull(AgentHookAnswers.answerRefusal(TaskAsk.FREE, "yes", null, "sending"))
        assertNotNull(AgentHookAnswers.answerRefusal(null, "continue", null, "sending"))
        // A word that confirms needs the category that the script read.
        assertNotNull(AgentHookAnswers.answerRefusal(TaskAsk.CONFIRM, "confirm", Category.SENDING, null))
        assertNotNull(AgentHookAnswers.answerRefusal(TaskAsk.CONFIRM, "yes", Category.SENDING, null))
        assertNotNull(AgentHookAnswers.answerRefusal(TaskAsk.CONFIRM, "send it", Category.SENDING, null))
```

with:

```kotlin
        assertNull(AgentHookAnswers.answerRefusal(QuestionKind.CONFIRM, "confirm", Category.SENDING, "sending"))
        assertNull(AgentHookAnswers.answerRefusal(QuestionKind.CONFIRM, "cancel", Category.DELETING, null))
        assertNull(AgentHookAnswers.answerRefusal(QuestionKind.CONFIRM, "no", Category.DELETING, null))
        assertNull(AgentHookAnswers.answerRefusal(QuestionKind.YES_NO, "no", null, null))
        assertNull(AgentHookAnswers.answerRefusal(null, "continue", null, null))

        // The confirmation changed between agent-state and agent-answer.
        assertNotNull(AgentHookAnswers.answerRefusal(QuestionKind.CONFIRM, "confirm", Category.DELETING, "sending"))
        assertNotNull(AgentHookAnswers.answerRefusal(QuestionKind.CONFIRM, "cancel", Category.DELETING, "sending"))
        assertNotNull(AgentHookAnswers.answerRefusal(QuestionKind.FREE, "yes", null, "sending"))
        assertNotNull(AgentHookAnswers.answerRefusal(null, "continue", null, "sending"))
        // A word that confirms needs the category that the script read.
        assertNotNull(AgentHookAnswers.answerRefusal(QuestionKind.CONFIRM, "confirm", Category.SENDING, null))
        assertNotNull(AgentHookAnswers.answerRefusal(QuestionKind.CONFIRM, "yes", Category.SENDING, null))
        assertNotNull(AgentHookAnswers.answerRefusal(QuestionKind.CONFIRM, "send it", Category.SENDING, null))
```

In `app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt`, replace:

```kotlin
    fun theQuestionIsNamedByTheKindThatTheAgentAskedItWith() {
        assertEquals("screenshot", AgentHookAnswers.question(AnswerKind.SCREENSHOT))
        assertEquals("pick-number", AgentHookAnswers.question(AnswerKind.PICK_NUMBER))
        assertEquals("free", AgentHookAnswers.question(AnswerKind.FREE))
        assertEquals("yes-no", AgentHookAnswers.question(AnswerKind.YES_NO))
```

with:

```kotlin
    fun theQuestionAndTheAskingKeepSubProjectFivesWords() {
        assertEquals("screenshot", AgentHookAnswers.question(QuestionKind.SCREENSHOT))
        assertEquals("pick-number", AgentHookAnswers.question(QuestionKind.PICK))
        assertEquals("free", AgentHookAnswers.question(QuestionKind.FREE))
        assertEquals("yes-no", AgentHookAnswers.question(QuestionKind.YES_NO))
        assertEquals("limit", AgentHookAnswers.question(QuestionKind.LIMIT))
        assertEquals("none", AgentHookAnswers.question(QuestionKind.CONTINUE_GOAL))
        // agent-state's asking= keeps the words that :tools:eval reads (design 14.4 of sub-project 7).
        assertEquals("continue", AgentHookAnswers.asking(QuestionKind.CONTINUE_GOAL))
        assertEquals("yes-no", AgentHookAnswers.asking(QuestionKind.LIMIT))
        assertEquals("free", AgentHookAnswers.asking(QuestionKind.SCREENSHOT))
        assertEquals("pick-number", AgentHookAnswers.asking(QuestionKind.PICK))
        assertEquals("confirm", AgentHookAnswers.asking(QuestionKind.CONFIRM))
```

In `app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt`, replace:

```kotlin
        val evidence = TaskEvidence(listOf("Kestrel test maple heron: a reminder about maple heron.", "Your battery is at 87 percent."), markerInData = true, spoken = "I read your notifications.")

        val line = AgentHookAnswers.result(AgentRunner.Ended(report, evidence = evidence), "maple heron", null, "87")

        assertTrue(line, line.contains(" marker=1 "))
        assertTrue(line, line.endsWith(" ownerTouches=0 signals=0 markerSaid=1 markerInData=1 digits=1"))
        assertTrue(AgentHookAnswers.result(AgentRunner.Ended(report, evidence = TaskEvidence(spoken = "Kestrel test maple heron.")), "maple heron", null).endsWith(" markerSaid=0 markerInData=none"))
```

with:

```kotlin
        val evidence = TaskEvidence(listOf("Kestrel test maple heron: a reminder about maple heron.", "Your battery is at 87 percent."), markerInData = true)

        val line = AgentHookAnswers.result(AgentHookAnswers.Ended(report, "I read your notifications.", evidence = evidence), "maple heron", null, "87")

        assertTrue(line, line.contains(" marker=1 "))
        assertTrue(line, line.endsWith(" ownerTouches=0 signals=0 markerSaid=1 markerInData=1 digits=1"))
        assertTrue(AgentHookAnswers.result(AgentHookAnswers.Ended(report, "Kestrel test maple heron."), "maple heron", null).endsWith(" markerSaid=0 markerInData=none"))
```

In `app/src/testDebug/kotlin/dev/kestrel/app/debug/HookRulesTest.kt`, replace:

```kotlin
    fun aTasksOptionsAreTheGivenExtrasAndAreReadOnce() {
        assertEquals(emptyMap<String, String>(), VoiceHooks.taskOptions(model = null, live = null))
        assertEquals(mapOf("model" to "test-model", "live" to "false"), VoiceHooks.taskOptions(model = "test-model", live = false))
        assertEquals(mapOf(DebugTaskWitness.MARKER to "maple heron"), VoiceHooks.taskOptions(model = null, live = null, marker = "maple heron"))

        val store = TaskOptions()
        store.put(3, mapOf("live" to "true"))
        assertEquals(mapOf("live" to "true"), store.take(3))
        assertEquals(emptyMap<String, String>(), store.take(3))
        // It keeps only the newest tasks' options.
        for (task in 1..TaskOptions.MAX_TASKS + 1) store.put(task, mapOf("model" to "m$task"))
        assertEquals(emptyMap<String, String>(), store.take(1))
        assertEquals(mapOf("model" to "m2"), store.take(2))
```

with:

```kotlin
    fun aTasksOptionsAreTheGivenExtras() {
        assertEquals(emptyMap<String, String>(), VoiceHooks.taskOptions(model = null, live = null))
        assertEquals(mapOf("model" to "test-model", "live" to "false"), VoiceHooks.taskOptions(model = "test-model", live = false))
        assertEquals(mapOf(DebugTaskWitness.MARKER to "maple heron"), VoiceHooks.taskOptions(model = null, live = null, marker = "maple heron"))
```

The witness no longer keeps Kestrel's final words: the conversation keeps them in the command's outcome and its record (design 8.1 and 14.4 of sub-project 7), and agent-task -Result reads them there (see "Decisions for the controller", item 8).

In `app/src/testDebug/kotlin/dev/kestrel/app/debug/DebugTaskWitnessTest.kt`, replace:

```kotlin
/** The debug build's witness of a task: its newest say texts, the sentence of its end and the run's marker in the data tools (plan 6B-basefix). */
class DebugTaskWitnessTest {
    @Test
    fun aWitnessKeepsTheNewestSayTextsAndTheSentenceOfTheEndAndWithAMarkerWatchesTheDataTools() = runTest {
```

with:

```kotlin
/** The debug build's witness of a round: its newest say texts and the run's marker in the data tools (plan 6B-basefix). */
class DebugTaskWitnessTest {
    @Test
    fun aWitnessKeepsTheNewestSayTextsAndWithAMarkerWatchesTheDataTools() = runTest {
```

In `app/src/testDebug/kotlin/dev/kestrel/app/debug/DebugTaskWitnessTest.kt`, replace:

```kotlin
        assertEquals((1..DebugTaskWitness.MAX_SAID).map { "Line $it." }, witness.evidence.said)
        assertNull(witness.evidence.spoken)
        witness.ended("I read your notifications.")
        assertEquals("I read your notifications.", witness.evidence.spoken)
```

with:

```kotlin
        assertEquals((1..DebugTaskWitness.MAX_SAID).map { "Line $it." }, witness.evidence.said)
```

- [ ] **Step 5: Change the device tests**

They run in Task 8, on the new path: `AgentTests.run` ends a goal that an earlier test left and closes the open conversation first, once Kestrel has finished the earlier reply, whose speaking holds the quiet time, so that each test's command opens its own conversation; `AgentTests.ended` reads the engine's outcome. `GoalPauseTest` checks the lock's pause on the device, which design 14.3 names first: it turns the screen off with `KEYCODE_SLEEP` while the model's first reply waits, with listening on and off; the goal stays paused at its next step until the owner's yes, and only then asks the model again. `HeldModel` lets each reply go when the test says so, so no step depends on the emulator's speed, and kestrel-api37's missing lock screen makes the wake-up the unlock, so no setting changes.

In `app/src/androidTest/kotlin/dev/kestrel/app/agent/AgentTests.kt`, replace:

```kotlin
import androidx.test.platform.app.InstrumentationRegistry
```

with:

```kotlin
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.agent.port.ModelPort
```

In `app/src/androidTest/kotlin/dev/kestrel/app/agent/AgentTests.kt`, replace:

```kotlin
import dev.kestrel.app.orchestrator.TaskAsk
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.voice.VoiceTests
```

with:

```kotlin
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.engine.CommandOutcome
import dev.kestrel.conversation.machine.QuietClock
```

In `app/src/androidTest/kotlin/dev/kestrel/app/agent/AgentTests.kt`, replace:

```kotlin
 * [TextSessions], which need no speech models, a transcript "Kestrel, <task>" to the Orchestrator, and plan 5A's
 * ScriptedModel as the task's model, so that no test calls a provider. The tests answer questions as the owner's voice
 * would, with transcripts.
```

with:

```kotlin
 * [TextSessions], which need no speech models, a transcript "Kestrel, <task>" to the Orchestrator and from it to the
 * conversation, which runs it as a goal (plan 7D), and plan 5A's ScriptedModel as the round's model, so that no test
 * calls a provider. Each task opens a conversation of its own. The tests answer questions as the owner's voice would,
 * with transcripts.
```

In `app/src/androidTest/kotlin/dev/kestrel/app/agent/AgentTests.kt`, replace:

```kotlin
    /** Turns Kestrel off, removes the scripted model, and puts the voice sessions back. */
    fun stop() {
        try {
            turnOff()
        } finally {
            graph.agent.modelOverride = null
```

with:

```kotlin
    /** Stops a goal that is still open, turns Kestrel off, removes the scripted model, and puts the voice sessions back. */
    fun stop() {
        try {
            endGoal()
            turnOff()
        } finally {
            graph.roundModels.modelOverride = null
```

In `app/src/androidTest/kotlin/dev/kestrel/app/agent/AgentTests.kt`, replace:

```kotlin
    private fun turnOff() {
```

with:

```kotlin
    // Plan 7B's decision 10: turning off leaves a goal running, so "stop" ends it first.
    private fun endGoal() {
        val conversations = graph.conversations
        if (conversations.view.value.goal == null) return
        say("stop")
        check(VoiceTests.waitUntil(30_000) { conversations.view.value.goal == null }) { "The goal did not end" }
    }

    /** Turns Kestrel off; a goal that works goes on (plan 7B's decision 10). */
    fun turnOff() {
```

In `app/src/androidTest/kotlin/dev/kestrel/app/agent/AgentTests.kt`, replace:

```kotlin
    /** Gives [request] as the task "Kestrel, <request>" with [model] as its model, and returns the task's number. */
    fun run(request: String, model: ScriptedModel): Int {
        // After a task, Kestrel speaks its report first; a transcript counts as a task again once the work is idle.
        check(VoiceTests.waitUntil(30_000) { graph.listening.state.value.work == Work.IDLE }) { "Kestrel was still busy" }
        graph.agent.modelOverride = model
        val before = graph.listening.state.value.task
        graph.listening.send(Event.Heard("Kestrel, $request", named = false))
        check(VoiceTests.waitUntil(5_000) { graph.listening.state.value.task > before }) { "The transcript started no task" }
        return graph.listening.state.value.task
    }

    /** Waits until task [task] asks a question of [kind]. */
    fun awaitAsking(task: Int, kind: TaskAsk, timeoutMillis: Long = 30_000) {
        check(VoiceTests.waitUntil(timeoutMillis) {
            val state = graph.listening.state.value
            state.task == task && state.taskAsking == kind
```

with:

```kotlin
    /**
     * Gives [request] as the task "Kestrel, <request>" with [model] as its model, in a conversation of its own, and returns
     * the number of the conversation's command that runs it.
     */
    fun run(request: String, model: ModelPort): Int {
        // After a task, Kestrel speaks its report first; a transcript counts as a command again once the work is idle.
        check(VoiceTests.waitUntil(30_000) { graph.listening.state.value.work == Work.IDLE }) { "Kestrel was still busy" }
        endGoal()
        closeConversation()
        val conversations = graph.conversations
        graph.roundModels.modelOverride = model
        val before = conversations.view.value.command
        graph.listening.send(Event.Heard("Kestrel, $request", named = false))
        check(VoiceTests.waitUntil(5_000) { conversations.view.value.command > before }) { "The transcript started no command" }
        return conversations.view.value.command
    }

    // A new command in the open conversation would be a follow-up (design 6.2 of sub-project 7). The quiet time ends only
    // while it runs, not while Kestrel still speaks the earlier reply, which holds it.
    private fun closeConversation() {
        val conversations = graph.conversations
        check(VoiceTests.waitUntil(30_000) { !graph.listening.state.value.speaking && conversations.view.value.quiet !is QuietClock.Held }) {
            "The conversation's quiet time stayed held"
        }
        conversations.endQuietTime()
        check(VoiceTests.waitUntil(5_000) { conversations.view.value.id == null }) { "The conversation did not close" }
    }

    /** Waits until command [command]'s goal asks a question of [kind], and the voice reads its answer. */
    fun awaitAsking(command: Int, kind: QuestionKind, timeoutMillis: Long = 30_000) {
        check(VoiceTests.waitUntil(timeoutMillis) {
            val view = graph.conversations.view.value
            view.command == command && view.asking == kind && graph.listening.state.value.openQuestion?.kind == kind
```

In `app/src/androidTest/kotlin/dev/kestrel/app/agent/AgentTests.kt`, replace:

```kotlin
    /** Waits until task [task] has ended, and returns its end. */
    fun ended(task: Int, timeoutMillis: Long = 90_000): AgentRunner.Ended {
        check(VoiceTests.waitUntil(timeoutMillis) { graph.agent.ended(task) != null && !graph.agent.running(task) }) { "The task did not end" }
        return graph.agent.ended(task)!!
```

with:

```kotlin
    /** Waits until command [command] has ended, and returns its outcome: its report and Kestrel's final words. */
    fun ended(command: Int, timeoutMillis: Long = 90_000): CommandOutcome {
        check(VoiceTests.waitUntil(timeoutMillis) { graph.conversations.outcome(command) != null }) { "The task did not end" }
        return graph.conversations.outcome(command)!!
```

In `app/src/androidTest/kotlin/dev/kestrel/app/agent/AgentTests.kt`, replace:

```kotlin
    }

    /** The kinds of the presses that `:testapp`'s fixtures recorded (design D27). */
```

with:

```kotlin
    }

    /** What Kestrel said through [TextSessions] since listening started, in order. */
    fun spoken(): List<String> = (graph.listening.sessions as TextSessions).spoken

    /** The kinds of the presses that `:testapp`'s fixtures recorded (design D27). */
```

In `app/src/androidTest/kotlin/dev/kestrel/app/agent/AgentEndToEndTest.kt`, replace:

```kotlin
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.orchestrator.TaskAsk
```

with:

```kotlin
import dev.kestrel.app.SettingKeys
```

In `app/src/androidTest/kotlin/dev/kestrel/app/agent/AgentEndToEndTest.kt`, replace:

```kotlin
import dev.kestrel.app.voice.VoiceTests
```

with:

```kotlin
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.engine.CommandOutcome
```

In `app/src/androidTest/kotlin/dev/kestrel/app/agent/AgentEndToEndTest.kt`, replace:

```kotlin
        val task = AgentTests.run("delete my chat with Ana", model)
        AgentTests.awaitAsking(task, TaskAsk.CONFIRM)
```

with:

```kotlin
        val task = AgentTests.run("delete my chat with Ana", model)
        AgentTests.awaitAsking(task, QuestionKind.CONFIRM)
```

In `app/src/androidTest/kotlin/dev/kestrel/app/agent/AgentEndToEndTest.kt`, replace:

```kotlin
                val state = AgentTests.graph.listening.state.value
                if (state.task == task && state.taskAsking != null) AgentTests.say("cancel")
                AgentTests.graph.agent.ended(task) != null && !AgentTests.graph.agent.running(task)
```

with:

```kotlin
                if (AgentTests.graph.listening.state.value.openQuestion != null) AgentTests.say("cancel")
                AgentTests.graph.conversations.outcome(task) != null
```

In `app/src/androidTest/kotlin/dev/kestrel/app/agent/AgentEndToEndTest.kt`, replace:

```kotlin
        val aliases = graph.agent.selfChatAliases
```

with:

```kotlin
        val aliases = graph.goalEnvironment.selfChatAliases
```

In `app/src/androidTest/kotlin/dev/kestrel/app/agent/AgentEndToEndTest.kt`, replace:

```kotlin
            graph.agent.selfChatAliases = mapOf(FIXTURES to SelfChats.WHATSAPP)
```

with:

```kotlin
            graph.goalEnvironment.selfChatAliases = mapOf(FIXTURES to SelfChats.WHATSAPP)
```

In `app/src/androidTest/kotlin/dev/kestrel/app/agent/AgentEndToEndTest.kt`, replace:

```kotlin
                val state = AgentTests.graph.listening.state.value
                if (state.task == task && state.taskAsking == TaskAsk.CONFIRM) AgentTests.say("confirm")
                AgentTests.graph.agent.ended(task) != null && !AgentTests.graph.agent.running(task)
```

with:

```kotlin
                if (AgentTests.graph.listening.state.value.openQuestion?.kind == QuestionKind.CONFIRM) AgentTests.say("confirm")
                AgentTests.graph.conversations.outcome(task) != null
```

In `app/src/androidTest/kotlin/dev/kestrel/app/agent/AgentEndToEndTest.kt`, replace:

```kotlin
            graph.agent.selfChatAliases = aliases
```

with:

```kotlin
            graph.goalEnvironment.selfChatAliases = aliases
```

In `app/src/androidTest/kotlin/dev/kestrel/app/agent/AgentEndToEndTest.kt`, replace:

```kotlin
    private fun addThenDelete(answer: String): Pair<AgentRunner.Ended, Int> {
```

with:

```kotlin
    private fun addThenDelete(answer: String): Pair<CommandOutcome, Int> {
```

In `app/src/androidTest/kotlin/dev/kestrel/app/agent/AgentEndToEndTest.kt`, replace:

```kotlin
        AgentTests.awaitAsking(task, TaskAsk.CONFIRM)
```

with:

```kotlin
        AgentTests.awaitAsking(task, QuestionKind.CONFIRM)
```

In `app/src/androidTest/kotlin/dev/kestrel/app/agent/AgentEndToEndTest.kt`, replace:

```kotlin
        assertTrue(settings.requests.isEmpty())
```

with:

```kotlin
        assertTrue(settings.requests.isEmpty())
        // The goal is held, and Kestrel says how to go on (design 10.2 of sub-project 7).
        check(VoiceTests.waitUntil(10_000) { AgentTests.spoken().joinToString(" ").contains(ConversationTexts.LEAVE_KESTREL) }) {
            "Kestrel did not say how to go on"
        }
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/agent/HeldModel.kt`:

```kotlin
package dev.kestrel.app.agent

import dev.kestrel.agent.port.ModelPort
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.client.PricedResponse
import dev.kestrel.providers.profiles.ModelRole
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger

/**
 * [inner], whose replies wait until the test lets each one go ([release]), so that a device test decides when the agent
 * goes on, not a fixed time (plan 7D's GoalPauseTest).
 */
internal class HeldModel(private val inner: ModelPort) : ModelPort by inner {
    private val releases = Channel<Unit>(Channel.UNLIMITED)
    private val asked = AtomicInteger()

    /** How many requests have reached the model, the waiting one too. */
    val calls: Int get() = asked.get()

    /** Lets the waiting reply go, or the next one when none waits. */
    fun release() {
        releases.trySend(Unit)
    }

    override suspend fun call(role: ModelRole, request: ChatRequest, step: Int): PricedResponse {
        asked.incrementAndGet()
        releases.receive()
        return inner.call(role, request, step)
    }

    override fun toString(): String = "HeldModel(calls=$calls)"
}
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/agent/GoalPauseTest.kt`:

```kotlin
package dev.kestrel.app.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.GoalPhase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A goal's pause on a lock on kestrel-api37 (design 6.5 and 14.3 of sub-project 7): the screen turning off pauses the goal
 * through PhoneSignals and ConversationSignals, also while listening is off, and the goal waits at its next step until the
 * owner says yes to "Continue the goal?" after the unlock. The model's replies wait until the test lets each one go, so no
 * step depends on the emulator's speed. kestrel-api37 has no lock screen, so waking the screen unlocks it; the test
 * changes no setting of the lock screen.
 */
@RunWith(AndroidJUnit4::class)
class GoalPauseTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val conversations = AgentTests.graph.conversations

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
        AgentTests.stop()
    }

    @Test
    fun aLockPausesTheGoalUntilTheOwnersYesAfterTheUnlock() {
        val scripted = ScriptedModel(listOf(onIt, fail))
        val model = HeldModel(scripted)
        AgentTests.run("read my newest notification", model)
        awaitCalls(model, 1)

        lockAndHold(model)
        wake()
        val listening = AgentTests.graph.listening
        check(VoiceTests.waitUntil(30_000) {
            val heard = listening.state.value
            conversations.view.value.asking == QuestionKind.CONTINUE_GOAL && heard.screenOn && heard.openQuestion?.kind == QuestionKind.CONTINUE_GOAL
        }) { "No \"Continue the goal?\" after the unlock" }
        assertEquals(GoalPhase.PAUSED, conversations.view.value.goal)
        assertEquals(1, model.calls)

        AgentTests.say("yes")
        // The goal goes on from its next step: only now does the model get its second request.
        awaitCalls(model, 2)
        model.release()
        check(VoiceTests.waitUntil(30_000) { conversations.view.value.goal == null }) { "The goal did not end" }
        assertEquals(EndReason.FAILED_BY_MODEL, conversations.outcome(conversations.view.value.command)?.report?.end)
        assertEquals(2, scripted.requests.size)
    }

    @Test
    fun aLockPausesTheGoalAlsoWhileListeningIsOff() {
        val model = HeldModel(ScriptedModel(listOf(onIt, fail)))
        AgentTests.run("read my newest notification", model)
        awaitCalls(model, 1)
        AgentTests.turnOff()
        check(VoiceTests.waitUntil(10_000) { !VoiceTests.listeningServiceRuns() }) { "ListeningService still runs" }

        lockAndHold(model)
        wake()
        // Listening comes back, so that stop() can end the goal by voice.
        AgentTests.start()
    }

    // The lock comes while the model's first reply waits. Once that reply goes, the goal stays paused at its next step and
    // asks the model nothing more.
    private fun lockAndHold(model: HeldModel) {
        screen.shell("input keyevent KEYCODE_SLEEP")
        check(VoiceTests.waitUntil(15_000) { conversations.view.value.goal == GoalPhase.PAUSED }) { "The lock did not pause the goal" }
        model.release()
        Thread.sleep(PAUSED_MILLIS)
        assertEquals(GoalPhase.PAUSED, conversations.view.value.goal)
        assertEquals(1, model.calls)
    }

    private fun awaitCalls(model: HeldModel, calls: Int) {
        check(VoiceTests.waitUntil(30_000) { model.calls == calls }) { "The model did not get request $calls" }
    }

    private fun wake() {
        screen.shell("input keyevent KEYCODE_WAKEUP")
        screen.shell("wm dismiss-keyguard")
    }

    private companion object {
        // How long the test watches a paused goal stay paused.
        const val PAUSED_MILLIS = 3_000L
    }
}
```

In `app/src/androidTest/kotlin/dev/kestrel/app/agent/NotificationListenerTest.kt`, replace:

```kotlin
import dev.kestrel.app.orchestrator.TaskPhase
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.app.voice.VoiceTests
```

with:

```kotlin
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.screen.ScreenServiceRule.Companion.FIXTURES
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.conversation.model.GoalPhase
```

In `app/src/androidTest/kotlin/dev/kestrel/app/agent/NotificationListenerTest.kt`, replace:

```kotlin
            AgentTests.graph.agent.handOver(task) == HandOverKind.ACCESS && AgentTests.graph.listening.state.value.taskPhase == TaskPhase.HANDED_OVER
```

with:

```kotlin
            AgentTests.graph.conversations.owner.handOverKind == HandOverKind.ACCESS && AgentTests.graph.conversations.view.value.goal == GoalPhase.HANDED_OVER
```

In `app/src/androidTest/kotlin/dev/kestrel/app/agent/NotificationListenerTest.kt`, replace:

```kotlin
        assertEquals(TaskPhase.HANDED_OVER, AgentTests.graph.listening.state.value.taskPhase)
```

with:

```kotlin
        assertEquals(GoalPhase.HANDED_OVER, AgentTests.graph.conversations.view.value.goal)
```

In `app/src/androidTest/kotlin/dev/kestrel/app/agent/PermissionFlowTest.kt`, replace:

```kotlin
        check(VoiceTests.waitUntil(5_000) { AgentTests.graph.agent.handOver(task) == null && !showsPermissionActivities() }) {
```

with:

```kotlin
        check(VoiceTests.waitUntil(5_000) { AgentTests.graph.conversations.owner.handOverKind == null && !showsPermissionActivities() }) {
```

In `app/src/androidTest/kotlin/dev/kestrel/app/debug/AgentHooksTest.kt`, replace:

```kotlin
    fun agentSettingsStoresTestModesNamesAndRefusesAnyOtherKeyWithoutItsValue() = runBlocking {
        val safety = graph.settings.get(SettingKeys.SAFETY)
        val aliases = graph.agent.selfChatAliases
        try {
```

with:

```kotlin
    fun agentSettingsStoresTestModesNamesAndRefusesAnyOtherKeyWithoutItsValue() = runBlocking {
        val safety = graph.settings.get(SettingKeys.SAFETY)
        val aliases = graph.goalEnvironment.selfChatAliases
        try {
```

In `app/src/androidTest/kotlin/dev/kestrel/app/debug/AgentHooksTest.kt`, replace:

```kotlin
            assertEquals(mapOf(FIXTURES to SelfChats.TELEGRAM), graph.agent.selfChatAliases)
        } finally {
            graph.settings.set(SettingKeys.SAFETY, safety)
            graph.agent.selfChatAliases = aliases
```

with:

```kotlin
            assertEquals(mapOf(FIXTURES to SelfChats.TELEGRAM), graph.goalEnvironment.selfChatAliases)
        } finally {
            graph.settings.set(SettingKeys.SAFETY, safety)
            graph.goalEnvironment.selfChatAliases = aliases
```

In `app/src/androidTest/kotlin/dev/kestrel/app/debug/AgentHooksTest.kt`, replace:

```kotlin
        val safety = graph.settings.get(SettingKeys.SAFETY)
        val aliases = graph.agent.selfChatAliases
```

with:

```kotlin
        val safety = graph.settings.get(SettingKeys.SAFETY)
        val aliases = graph.goalEnvironment.selfChatAliases
```

In `app/src/androidTest/kotlin/dev/kestrel/app/debug/AgentHooksTest.kt`, replace:

```kotlin
            assertEquals(aliases, graph.agent.selfChatAliases)
        } finally {
            graph.settings.set(SettingKeys.SAFETY, safety)
            graph.agent.selfChatAliases = aliases
```

with:

```kotlin
            assertEquals(aliases, graph.goalEnvironment.selfChatAliases)
        } finally {
            graph.settings.set(SettingKeys.SAFETY, safety)
            graph.goalEnvironment.selfChatAliases = aliases
```

In `app/src/androidTest/kotlin/dev/kestrel/app/debug/AgentHooksTest.kt`, replace:

```kotlin
            assertNotNull(graph.agent.modelOverride)
            assertTrue(hook("AGENT_SCRIPT --es file ../hooks-test.json").contains("result=1, data=\"error: add --es file"))

            assertEquals("script=none", answer(hook("AGENT_SCRIPT --ez clear true")))
            assertNull(graph.agent.modelOverride)
        } finally {
            graph.agent.modelOverride = null
```

with:

```kotlin
            assertNotNull(graph.roundModels.modelOverride)
            assertTrue(hook("AGENT_SCRIPT --es file ../hooks-test.json").contains("result=1, data=\"error: add --es file"))

            assertEquals("script=none", answer(hook("AGENT_SCRIPT --ez clear true")))
            assertNull(graph.roundModels.modelOverride)
        } finally {
            graph.roundModels.modelOverride = null
```

In `app/src/androidTest/kotlin/dev/kestrel/app/debug/AgentHooksTest.kt`, replace:

```kotlin
        val aliases = graph.agent.selfChatAliases
        graph.agent.selfChatAliases = mapOf(FIXTURES to SelfChats.TELEGRAM)
```

with:

```kotlin
        val aliases = graph.goalEnvironment.selfChatAliases
        graph.goalEnvironment.selfChatAliases = mapOf(FIXTURES to SelfChats.TELEGRAM)
```

In `app/src/androidTest/kotlin/dev/kestrel/app/debug/AgentHooksTest.kt`, replace:

```kotlin
            graph.agent.selfChatAliases = aliases
```

with:

```kotlin
            graph.goalEnvironment.selfChatAliases = aliases
```

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/SessionHookTest.kt`, replace:

```kotlin
import dev.kestrel.app.orchestrator.Work
```

with:

```kotlin
import dev.kestrel.conversation.model.GoalPhase
```

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/SessionHookTest.kt`, replace:

```kotlin
import org.junit.Assert.assertSame
```

with:

```kotlin
import org.junit.Assert.assertNull
```

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/SessionHookTest.kt`, replace:

```kotlin
    fun aSlowTaskRunsForItsTimeIsCountedAndClearBringsBackTheTaskThatEndsAtOnce() {
        VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.ASR, Engine.TTS)
        VoiceTests.grantListeningPermissions()
        val stub = listening.runTask
```

with:

```kotlin
    fun aSlowGoalRunsForItsTimeIsCountedAndClearBringsBackTheRoundsOnThePhone() {
        VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.ASR, Engine.TTS)
        VoiceTests.grantListeningPermissions()
        val conversations = VoiceTests.graph.conversations
```

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/SessionHookTest.kt`, replace:

```kotlin
            assertTrue(reply.text, reply.text.startsWith("each task now takes 3 s and then says a reply of 17 characters"))
            val tasks = listening.state.value.task

            listening.send(Event.Heard("Kestrel, open the window.", named = true))

            assertTrue("No task ran", VoiceTests.waitUntil(5_000) { listening.state.value.work == Work.RUNNING_TASK })
            // The counts name the waits, the tasks, the words of the last task and the keyword hits, never the words
```

with:

```kotlin
            assertTrue(reply.text, reply.text.startsWith("each goal's round now takes 3 s and then answers with a reply of 17 characters"))
            conversations.endQuietTime()
            val tasks = conversations.view.value.command

            listening.send(Event.Heard("Kestrel, open the window.", named = true))

            assertTrue("No goal ran", VoiceTests.waitUntil(5_000) { conversations.view.value.goal == GoalPhase.ACTING })
            // The counts name the waits, the commands, the words of the last goal and the keyword hits, never the words
```

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/SessionHookTest.kt`, replace:

```kotlin
            // The task is still running a second later, unlike the task that ends at once.
            Thread.sleep(1_000)
            assertEquals(Work.RUNNING_TASK, listening.state.value.work)
            assertTrue("The task did not end", VoiceTests.waitUntil(10_000) { listening.state.value.work == Work.IDLE })
        }
        assertEquals("each task ends at once again", hook("VOICE_TASK", flags = setOf("clear")).text)
        assertSame(stub, listening.runTask)
```

with:

```kotlin
            // The goal still works a second later, unlike a round that ends at once.
            Thread.sleep(1_000)
            assertEquals(GoalPhase.ACTING, conversations.view.value.goal)
            assertTrue("The goal did not end", VoiceTests.waitUntil(10_000) { conversations.view.value.goal == null })
            assertEquals("The task is done.", conversations.outcome(tasks + 1)?.answer)
        }
        assertEquals("each goal's round runs on the phone again", hook("VOICE_TASK", flags = setOf("clear")).text)
        assertNull(VoiceTests.graph.goalEnvironment.standIn)
```

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceHookTest.kt`, replace:

```kotlin
import dev.kestrel.app.orchestrator.Mode
```

with:

```kotlin
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.conversation.model.OwnerInput
```

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceHookTest.kt`, replace:

```kotlin
            // AgentRunner takes a task's options when the task starts, so the test records what each take handed over.
            val take = listening.taskOptions ?: error("The tag did not set Listening.taskOptions")
            val handed = CopyOnWriteArrayList<Pair<Int, Map<String, String>>>()
            listening.taskOptions = { number -> take(number).also { handed += number to it } }
```

with:

```kotlin
            // The tag's options go with the input that its transcript gives the conversation, so the test records each input.
            val take = listening.inputs ?: error("The tag did not set Listening.inputs")
            val handed = CopyOnWriteArrayList<OwnerInput>()
            listening.inputs = { heard, input -> take(heard, input).also { handed += it } }
```

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceHookTest.kt`, replace:

```kotlin
                // A tagged transcript that starts a task gets the task's number, and its extras reach the task's runner once.
```

with:

```kotlin
                // A tagged transcript that starts a goal gets its command's number, and its extras go with its input once.
```

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceHookTest.kt`, replace:

```kotlin
                assertTrue("The task's runner did not take its options", VoiceTests.waitUntil(10_000) { handed.any { it.first == task } })
                assertTrue("Kestrel still speaks", VoiceTests.waitUntil(20_000) { !listening.state.value.speaking })
                assertEquals(listOf(mapOf("model" to "test-model")), handed.filter { it.first == task }.map { it.second })
                assertEquals(emptyMap<String, String>(), take(task))
            } finally {
                listening.taskOptions = take
```

with:

```kotlin
                assertTrue("Kestrel still speaks", VoiceTests.waitUntil(20_000) { !listening.state.value.speaking })
                assertEquals(listOf(mapOf("model" to "test-model")), handed.map { it.options }.filter { it.isNotEmpty() })
            } finally {
                listening.inputs = take
```

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/ConversationTest.kt`, replace:

```kotlin
        // The task counter belongs to the process, so other tests may have run tasks before this one.
        val expectedTask = listening.state.value.task + 1
```

with:

```kotlin
        // The command counter belongs to the process, so other tests may have run commands before this one.
        val expectedTask = VoiceTests.graph.conversations.view.value.command + 1
```

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/ConversationTest.kt`, replace:

```kotlin
                    it.task == expectedTask && it.work == Work.IDLE && !it.speaking && replies().size == 2
```

with:

```kotlin
                    VoiceTests.graph.conversations.outcome(expectedTask) != null && it.work == Work.IDLE && !it.speaking && replies().size == 2
```

- [ ] **Step 6: Run the new tests to see them fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.orchestrator.VoiceAnswersTest`
Expected: FAIL, the build does not compile: `VoiceAnswers`, `VoicePath`, `VoiceChannel` and the other new types do not exist yet.

- [ ] **Step 7: The voice's rules**

The conversation's view says while one of Kestrel's own requests runs, and a stop withdraws that request's question at once, so that "Stop. Kestrel, …" stops it first and the command after it runs (review of Task 3: M1).

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationView.kt`, replace:

```kotlin
 * ([keptForOwner]: a goal's final reply, or a held goal's report, also after its conversation closed), for which plan
 * 7D's `ConversationSignals` keeps watching the phone. toString prints no words.
 */
data class ConversationView(
    val id: ConversationId? = null,
    val command: Int = 0,
    val goal: GoalPhase? = null,
    val step: Int? = null,
    val asking: QuestionKind? = null,
    val question: Int? = null,
    val replyChannel: InputChannel = InputChannel.VOICE,
    val checklist: ChecklistSummary? = null,
    val records: List<CommandRecord> = emptyList(),
    val continuedAtWall: Long? = null,
    val testMode: Boolean = true,
    val quiet: QuietClock = QuietClock.Off,
    val keptForOwner: Boolean = false,
) {
    /** The owner continued the conversation from the list or by name (D23). */
    val continued: Boolean get() = continuedAtWall != null

    override fun toString(): String =
        "ConversationView(open=${id != null}, command=$command, goal=$goal, step=$step, asking=$asking, records=${records.size}, quiet=$quiet, " +
            "kept=$keptForOwner)"
```

with:

```kotlin
 * ([keptForOwner]: a goal's final reply, or a held goal's report, also after its conversation closed), for which plan
 * 7D's `ConversationSignals` keeps watching the phone, and whether one of Kestrel's own requests runs and "stop" has not
 * cancelled it yet ([ownRequest]: remember, forget, "save that as", delete history or a switch, design 9.6 and 9.7), so
 * that the voice stops it before the command in "Stop. Kestrel, …". toString prints no words.
 */
data class ConversationView(
    val id: ConversationId? = null,
    val command: Int = 0,
    val goal: GoalPhase? = null,
    val step: Int? = null,
    val asking: QuestionKind? = null,
    val question: Int? = null,
    val replyChannel: InputChannel = InputChannel.VOICE,
    val checklist: ChecklistSummary? = null,
    val records: List<CommandRecord> = emptyList(),
    val continuedAtWall: Long? = null,
    val testMode: Boolean = true,
    val quiet: QuietClock = QuietClock.Off,
    val keptForOwner: Boolean = false,
    val ownRequest: Boolean = false,
) {
    /** The owner continued the conversation from the list or by name (D23). */
    val continued: Boolean get() = continuedAtWall != null

    override fun toString(): String =
        "ConversationView(open=${id != null}, command=$command, goal=$goal, step=$step, asking=$asking, records=${records.size}, quiet=$quiet, " +
            "kept=$keptForOwner, own=$ownRequest)"
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
            keptForOwner = current.heldReports.isNotEmpty() || current.heldWords != null,
        )
```

with:

```kotlin
            keptForOwner = current.heldReports.isNotEmpty() || current.heldWords != null,
            ownRequest = current.local?.cancelled == false,
        )
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/ConversationMachine.kt`, replace:

```kotlin
    // A command waits for the request in its turn; "stop" cancels the request, a switch too, and drops what waits.
    private fun whileLocal(state: ConversationState, run: LocalRun, input: OwnerInput): MachineStep = when (input.intent) {
        is InputIntent.Command -> MachineStep(state.copy(waiting = state.waiting + input))
        InputIntent.Stop -> MachineStep(state.copy(waiting = emptyList(), local = run.copy(cancelled = true)), listOf(ConversationEffect.CancelLocal))
```

with:

```kotlin
    // A command waits for the request in its turn; "stop" cancels the request, a switch too, and drops what waits. The
    // request's question goes with it at once, so that a command after the stop is no answer to it ("Stop. Kestrel, …";
    // design 6.4).
    private fun whileLocal(state: ConversationState, run: LocalRun, input: OwnerInput): MachineStep = when (input.intent) {
        is InputIntent.Command -> MachineStep(state.copy(waiting = state.waiting + input))
        InputIntent.Stop -> MachineStep(state.copy(waiting = emptyList(), local = run.copy(cancelled = true), question = null), listOf(ConversationEffect.CancelLocal))
```

Replace the whole `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt` with:

```kotlin
package dev.kestrel.app.orchestrator

import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandParser
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.GlobalCommand
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.PhoneCommand
import dev.kestrel.commands.SpokenNumbers
import dev.kestrel.commands.TextCommand
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.voice.Keyword
import dev.kestrel.voice.listen.Recognition

/**
 * The listening modes (design spec, section 8.5). [RINGING]: a call rings, and Kestrel listens only for the call
 * commands, also on the lock screen. [IN_CALL]: a call is active, and Kestrel is paused.
 */
enum class Mode { OFF, LISTENING, PAUSED, RINGING, IN_CALL }

/** The phone's call state (design spec, section 8.7), as Android's call-state listener reports it. */
enum class CallState { IDLE, RINGING, ACTIVE }

/**
 * What Kestrel does when the screen comes back on and the phone is unlocked (design spec, section 8.5): it keeps the
 * mode that it had ([RESUME]), listens ([LISTEN]), or turns off ([STAY_OFF]).
 */
enum class WakeUpMode { RESUME, LISTEN, STAY_OFF }

/** What Kestrel is doing besides listening: the spec's "current activity" (section 7.3). */
enum class Work {
    IDLE,

    /** After "Kestrel" on its own: the next utterance is the command for the conversation. */
    AWAITING_TASK,

    /** An instant command runs (section 7.3): it may ask a question or count down, and "stop" or "cancel" cancel it. */
    RUNNING_COMMAND,
}

/**
 * A question of the conversation that the voice asked (design 11 of sub-project 7): its [number], as the conversation
 * numbers it, and its [kind], which says how the voice reads the answer.
 */
data class VoiceQuestion(val number: Int, val kind: QuestionKind)

/**
 * The Orchestrator's state (design spec, section 7.3). [speeches] counts the replies that Kestrel has started and not
 * finished. [wait] is the number of the latest wait for a command or an answer, so that the end of an older wait changes
 * nothing. [emptyTranscripts] counts the transcripts without words in a row, and [noWordsShown] is true once the hint about them
 * has shown since Kestrel turned on (section 14.6). [beforeCall] is the mode that a call interrupted, while the mode is
 * Ringing or In call; it comes back after the call (section 8.7). [callCommands]: the owner turned on "Commands during a
 * call", so that Kestrel listens for them during a call (section 8.7). [onWakeUp] is what Kestrel does after an unlock,
 * and [pauseAfterSilence] pauses listening after 30 s without speech (section 8.5); [silence] is the number of the
 * latest timer of that pause, so that the end of an older one changes nothing. [screenSeen]: since Kestrel turned on,
 * the running ListeningService has reported the screen on and the phone unlocked, so that the next unlock is a wake-up
 * (section 8.5). [command] is the number of the latest instant command, so that the end of an older one changes nothing;
 * [running] is the command that runs and [heard] its transcript; [asking]: it waits for the answer to its question;
 * [counting]: it counts down, as before a call. [lastCommand] and [lastHeard] are the last command that ended as
 * repeatable and its transcript, for "again" (section 9.5); off forgets them. [spottedStop]: the spotter's "stop" or
 * "cancel" cancelled the command, or stopped a reply while no command ran, so the transcript of the same word names no
 * button. It lasts until the next transcript that reaches the commands, so when the pipeline dropped that word, one
 * later "stop…" or "cancel…" is only shown. [questionEnded]: a command's question ended without an answer, so the next
 * transcript that reaches the commands, when it names a number, may be a late answer and is only shown (section 9.4). It
 * lasts 10 s at most, and a pause, a call, a goal that works or waits for the owner's return, the wait for a command,
 * and the screen turning off end it.
 * [conversation] is what the voice reads of the conversation (design 5.5 of sub-project 7): whether one is open, its
 * goal's phase, its open question, and whether a final reply waits for the owner. [voiceQuestion] is the conversation's question that the voice asked and waits on, with the
 * Orchestrator's answer waits; [voiceAnswers] is false while only its card may answer it (D32 item 17 of sub-project 5);
 * [answered] is the number of the latest question that the voice answered, so that the next transcript does not answer it
 * again before the conversation has taken the answer. [offAfterGoal]: "On screen wake-up" is "Stay off", and Kestrel
 * turns off once the goal has ended and its final reply has been spoken.
 */
data class State(
    val mode: Mode = Mode.OFF,
    val work: Work = Work.IDLE,
    val speeches: Int = 0,
    val wait: Int = 0,
    val screenOn: Boolean = true,
    val micBusy: Boolean = false,
    val emptyTranscripts: Int = 0,
    val noWordsShown: Boolean = false,
    val beforeCall: Mode? = null,
    val callCommands: Boolean = false,
    val onWakeUp: WakeUpMode = WakeUpMode.RESUME,
    val pauseAfterSilence: Boolean = false,
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
    val questionEnded: Boolean = false,
    val conversation: VoiceView = VoiceView(),
    val voiceQuestion: VoiceQuestion? = null,
    val voiceAnswers: Boolean = true,
    val answered: Int? = null,
    val offAfterGoal: Boolean = false,
) {
    val speaking: Boolean get() = speeches > 0

    /** The phase of the conversation's goal, or null when it has none (design 5.3 of sub-project 7). */
    val goal: GoalPhase? get() = conversation.goal

    /** Whether the conversation's goal acts or asks, rather than waiting for the owner's hand or return. */
    val goalWorks: Boolean get() = goal == GoalPhase.ACTING || goal == GoalPhase.ASKING

    /**
     * The conversation's question that a transcript answers: the one that the voice asked, or else the one that the
     * conversation shows, as long as the voice has not answered it yet.
     */
    val openQuestion: VoiceQuestion?
        get() = (voiceQuestion ?: conversation.question?.let { number -> conversation.asking?.let { VoiceQuestion(number, it) } })
            ?.takeIf { it.number != answered }

    // The owner's words never reach a log (section 13.5): only the commands' kinds and the transcripts' lengths.
    override fun toString() =
        "State(mode=$mode, work=$work, speeches=$speeches, wait=$wait, screenOn=$screenOn, " +
            "micBusy=$micBusy, emptyTranscripts=$emptyTranscripts, noWordsShown=$noWordsShown, beforeCall=$beforeCall, " +
            "callCommands=$callCommands, onWakeUp=$onWakeUp, pauseAfterSilence=$pauseAfterSilence, silence=$silence, " +
            "screenSeen=$screenSeen, command=$command, running=${running?.javaClass?.simpleName}, heard=${heard?.length}, " +
            "asking=$asking, counting=$counting, lastCommand=${lastCommand?.javaClass?.simpleName}, " +
            "lastHeard=${lastHeard?.length}, spottedStop=$spottedStop, questionEnded=$questionEnded, conversation=$conversation, " +
            "voiceQuestion=$voiceQuestion, voiceAnswers=$voiceAnswers, answered=$answered, offAfterGoal=$offAfterGoal)"

    /** Whether a call rings or is active (section 8.7). */
    val inCall: Boolean get() = mode == Mode.RINGING || mode == Mode.IN_CALL

    /**
     * Whether Kestrel records: while it listens or is paused, with the screen on and the phone unlocked; while a call
     * rings, also on the lock screen (section 8.5); and during a call only with [callCommands] and the phone unlocked.
     */
    val microphone: Boolean
        get() = when (mode) {
            Mode.OFF -> false
            Mode.RINGING -> true
            Mode.IN_CALL -> callCommands && screenOn
            Mode.LISTENING, Mode.PAUSED -> screenOn
        }

    /** Which utterances the pipeline transcribes, and whether it holds them (sections 8.3, 8.4, 8.5 and 11.10). */
    val recognition: Recognition
        get() = when {
            speaking -> Recognition.NONE
            // While a call rings, the call commands may come without the name (section 8.7).
            mode == Mode.RINGING -> Recognition.ALL
            // During a call with the setting, only the utterances at whose start the keyword spotter heard "Kestrel", as
            // while Kestrel is paused, so that Kestrel never transcribes the call's conversation; when the spotter misses
            // the name, the owner says the command again (section 8.7).
            mode == Mode.IN_CALL -> if (callCommands) Recognition.NAMED else Recognition.NONE
            // While Kestrel waits for a command, after "Yes?" or after an interruption with "Kestrel", the owner dictates
            // it and may pause in it.
            work == Work.AWAITING_TASK -> Recognition.ANSWER
            // The conversation's question, a goal that works ("stop" without the name) and a hand-over take words without
            // the name, also in the paused mode (design D2 of sub-project 5; design 5.5 of sub-project 7).
            work != Work.IDLE || mode == Mode.LISTENING || openQuestion != null || goalWorks || goal == GoalPhase.HANDED_OVER ->
                Recognition.ALL
            else -> Recognition.NAMED
        }
}

/** What happens to Kestrel (design spec, section 7.3). */
sealed interface Event {
    /** The accessibility button or shortcut, or the Quick Settings tile (section 8.6). */
    data object Toggle : Event

    /**
     * Turns Kestrel on while it is off: listening, or paused when [paused]. While Kestrel listens or is paused it changes
     * nothing, so that a start that comes late cannot toggle anything. The restore after a restart of Kestrel's process
     * sends it (section 8.5).
     */
    data class Start(val paused: Boolean = false) : Event

    /** The notification's Pause action. */
    data object Pause : Event

    /** The notification's Resume action. */
    data object Resume : Event

    /** The notification's Stop action. */
    data object TurnOff : Event

    /**
     * A transcript. [named] is true when the keyword spotter heard "Kestrel" at its start. [spokeAt] is when the owner
     * began to say it, in `SystemClock.uptimeMillis()`, or null when it is not known; a command that names a number
     * ends when the numbers changed after it (section 9.4). [seconds] is the length of its audio, and [endedAt] the end
     * of its speech on the phone's clock (SystemClock.elapsedRealtime), or 0 when it is not known; only the A71 session's
     * counts and times read them (plan 2E). [closedAt] is when its end-of-speech pause ended, on the same clock, or 0
     * (plan 4D). [stopSpotted] is true when the keyword spotter heard "stop" at its start (plan 4E).
     */
    data class Heard(
        val text: String,
        val named: Boolean,
        val spokeAt: Long? = null,
        val seconds: Float = 0f,
        val endedAt: Long = 0L,
        val closedAt: Long = 0L,
        val stopSpotted: Boolean = false,
    ) : Event {
        override fun toString() = "Heard(${text.length} characters, named=$named)"
    }

    /**
     * The owner speaks while Kestrel recognises speech. Send it when the speech detector hears speech on a frame for
     * which recognition is on, and on the frame before it either heard none or recognition was off. Send it again when a
     * transcript arrives while the detector still hears speech. Read `hearsSpeech` on the microphone's thread, after
     * `ListeningPipeline.accept`; the pipeline's `VoiceEvent.SpeechStarted` follows this rule. An utterance that ends
     * without a transcript while recognition is on restarts the 8 s wait for a task: `VoiceSession` sends
     * [NothingHeard] for it.
     */
    data object Speaking : Event

    /** A keyword from the spotter that was not Kestrel's own voice. */
    data class Spotted(val keyword: Keyword) : Event

    /**
     * A reply has been spoken, stopped, interrupted or refused, or speaking it failed. Send it once for each reply, or
     * recognition stays off.
     */
    data object SpeechEnded : Event

    /** Android started or stopped silencing Kestrel's recording. */
    data class MicBusy(val busy: Boolean) : Event

    /**
     * The screen is on and the phone unlocked ([on]), or the screen turned off or the phone locked. The conversation
     * pauses its goal through its own signals, also with listening off (design 6.5 of sub-project 7).
     */
    data class Screen(val on: Boolean) : Event

    /** Wait number [wait] for a task after "Yes?" has lasted long enough. */
    data class WaitEnded(val wait: Int) : Event

    /**
     * Kestrel could not open its settings app, because Android lets it start an activity from the background only while
     * its accessibility service is on or it holds the assistant role. Kestrel says so, because the feedback bar does not
     * exist then (section 14).
     */
    data object SettingsUnavailable : Event

    /**
     * Android did not bring Kestrel's settings to the front, although Kestrel's accessibility service is on or Kestrel
     * holds the assistant role, as Android 13 may do with only the role. Kestrel posted a notification that opens them,
     * and says so (section 14).
     */
    data object SettingsBlocked : Event

    /**
     * An utterance ended without a transcript: the pipeline dropped it, or transcribing it failed. Like a transcript
     * without words, it gives the owner the whole wait for a task again, but it does not count towards the hint about
     * empty transcripts (section 14.6).
     */
    data object NothingHeard : Event

    /** The phone's call state changed (section 8.7). Only Listening's call-state listener sends it. */
    data class CallState(val state: dev.kestrel.app.orchestrator.CallState) : Event

    /**
     * The assistant gesture or an earbud's assistant button (section 8.6): Kestrel listens, if it was paused, and waits
     * for a task, as after "Kestrel" on its own. Listening turns Kestrel on first and sends it once a voice session is
     * open, so the Rules ignore it while Kestrel is off.
     */
    data object Assist : Event

    /** The owner's choices for listening, as Listening read them at its start or after a change (section 15). */
    data class Options(val onWakeUp: WakeUpMode, val pauseAfterSilence: Boolean, val callCommands: Boolean) : Event

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

    /** 10 s have passed since the question of command number [command] ended without an answer (section 9.4). */
    data class LateAnswersEnded(val command: Int) : Event

    /** What the voice reads of the conversation changed to [view] (design 5.5 of sub-project 7). */
    data class ConversationChanged(val view: VoiceView) : Event

    /**
     * The conversation says [text] through the voice channel (design 11 of sub-project 7). [reply] numbers it, so that
     * the channel learns when it has been spoken, or that it was only shown ([Effect.EndReply]).
     */
    data class ChannelSays(val reply: Int, val text: String) : Event {
        override fun toString() = "ChannelSays($reply, ${text.length} characters)"
    }

    /** The conversation shows [text] on the bar without speaking it, as "Got it." for steering. */
    data class ChannelShows(val text: String) : Event {
        override fun toString() = "ChannelShows(${text.length} characters)"
    }

    /**
     * The voice channel asks the conversation's question number [question] of [kind], [text] being its spoken form, and
     * waits for its answer with the answer waits. With [voice] false, only its card answers it, because another app's
     * audio plays (D32 item 17 of sub-project 5); "stop", "cancel" and the end of the wait still count.
     */
    data class ChannelAsks(val question: Int, val text: String, val kind: QuestionKind, val voice: Boolean = true) : Event {
        override fun toString() = "ChannelAsks($question, ${text.length} characters, $kind, voice=$voice)"
    }

    /** The voice channel hands a step over to the owner and says [text] (design D15 of sub-project 5). */
    data class ChannelHandsOver(val text: String) : Event {
        override fun toString() = "ChannelHandsOver(${text.length} characters)"
    }

    /**
     * The voice channel's question number [question] ended: answered on its card or by voice, or withdrawn by the
     * conversation (design 11 of sub-project 7). The voice no longer waits for its answer.
     */
    data class ChannelWithdraws(val question: Int) : Event
}

/** What Kestrel says. */
sealed interface Reply {
    /** "Yes?", after "Kestrel" on its own. */
    data object Yes : Reply

    /**
     * Why Kestrel did not open its settings: its accessibility service is off, and it does not hold the assistant role
     * (section 14).
     */
    data object SettingsNeedService : Reply

    /** Android kept Kestrel's settings back, and a notification opens them (section 14). */
    data object SettingsBlocked : Reply

    /**
     * [kept]: the Rules show [text] on the bar already and keep it there, as the conversation's question or a hand-over's
     * sentence, so a reply that Kestrel cannot speak does not show it again.
     */
    data class Text(val text: String, val kept: Boolean = false) : Reply {
        override fun toString() = "Text(${text.length} characters, kept=$kept)"
    }

    /** What instant command number [command] says or asks. */
    data class Command(val command: Int, val text: String) : Reply {
        override fun toString() = "Command($command, ${text.length} characters)"
    }

    /**
     * What the conversation says through the voice channel, as its reply number [reply] (design 11 of sub-project 7);
     * the bar shows it already. Listening tells the channel when it has been spoken, stopped or refused.
     */
    data class Channel(val reply: Int, val text: String) : Reply {
        override fun toString() = "Channel($reply, ${text.length} characters)"
    }
}

/** What the feedback bar shows (design spec, section 8.9). */
sealed interface Feedback {
    data class ModeChanged(val mode: Mode) : Feedback

    data class Heard(val text: String) : Feedback {
        override fun toString() = "Heard(${text.length} characters)"
    }

    data object MicBusy : Feedback

    /**
     * Kestrel waits for the task, after "Yes?" or after an interruption with "Kestrel": the question that Kestrel asks
     * (section 8.9).
     */
    data object AwaitingTask : Feedback

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

    /**
     * A number came as the first transcript after a command's question ended without an answer, within 10 s: it may be a
     * late answer, whose number would name another element now, so it did nothing (section 9.4).
     */
    data object QuestionEnded : Feedback

    /**
     * What the conversation says, shows, asks or hands over through the voice channel (design 11 of sub-project 7). With
     * [keep], the text stays until the voice channel releases it, as a question or a hand-over's sentence does.
     */
    data class ConversationText(val text: String, val keep: Boolean = false) : Feedback {
        override fun toString() = "ConversationText(${text.length} characters, keep=$keep)"
    }
}

/** What the Orchestrator asks of the rest of Kestrel. The microphone and recognition follow [State] instead. */
sealed interface Effect {
    data class Say(val reply: Reply) : Effect

    data object StopSaying : Effect

    /**
     * Gives the conversation the owner's [intent] (design 5.5 of sub-project 7): a command, an answer, a stop or one of
     * Kestrel's own requests. [heard] is the transcript that said it, or null for the end of an answer wait or the
     * conversation's own question that the name withdrew; the debug hooks find a tagged transcript's options by it.
     */
    data class Converse(val intent: InputIntent, val heard: Event.Heard? = null) : Effect {
        override fun toString() = "Converse($intent, heard=${heard != null})"
    }

    /** Reply number [reply] of the conversation was only shown, because Kestrel is off: its channel goes on at once. */
    data class EndReply(val reply: Int) : Effect

    /**
     * Runs instant command number [command], [parsed] from the transcript [heard], which the executor needs for its
     * fallback to a bare label and for dictation. [spokeAt] is when the owner began to say it, as in [Event.Heard]. It
     * must send [Event.CommandEnded] once it ends (section 7.3).
     */
    data class RunCommand(val command: Int, val parsed: Command, val heard: String, val spokeAt: Long? = null) : Effect {
        override fun toString() = "RunCommand($command, ${parsed.javaClass.simpleName}, ${heard.length} characters)"
    }

    /** Cancels the running instant command. Nothing new may act on the screen until its job has completed. */
    data object CancelCommand : Effect

    /** The answer to the question of command number [command], or null when the wait for it ended. */
    data class AnswerCommand(val command: Int, val text: String?) : Effect {
        override fun toString() = "AnswerCommand($command, ${if (text == null) "no answer" else "${text.length} characters"})"
    }

    /** Holds the countdown of command number [command] while the owner speaks ([hold]), or lets it go on. */
    data class HoldCommand(val command: Int, val hold: Boolean) : Effect

    /**
     * Starts the timer that ends the latest wait for the answer to a command's question, in place of the one that runs.
     * [answering]: the owner has started to answer. The Orchestrator runs it itself.
     */
    data class WaitForAnswer(val answering: Boolean = false) : Effect

    data class Show(val feedback: Feedback) : Effect

    /**
     * Starts the timer that ends the latest wait for a task after "Yes?", in place of the one that runs. [answering]: the
     * owner has started to answer, so the timer also covers the answer and its transcription. The Orchestrator runs it
     * itself.
     */
    data class WaitForTask(val answering: Boolean = false) : Effect

    /** Stops the listening service. */
    data object StopService : Effect

    /** Opens Kestrel's settings app, for "Kestrel, open settings" (section 15). */
    data object OpenSettings : Effect

    /** Carries out a call command (section 8.7). */
    data class CallCommand(val command: Request.Call) : Effect

    /**
     * Starts the timer of the pause after 30 s without speech, in place of the one that runs (section 8.5). The
     * Orchestrator runs it itself.
     */
    data object TimeSilence : Effect

    /**
     * Starts the timer after which a number no longer counts as a late answer to the question of the latest command
     * ([Event.LateAnswersEnded]), in place of the one that runs. The Orchestrator runs it itself.
     */
    data object TimeLateAnswers : Effect
}

/** A transition: the new state, and what to do, in order. */
data class Step(val state: State, val effects: List<Effect> = emptyList())

/** Every transition of the Orchestrator, in one place (design spec, section 7.3). */
object Rules {
    fun next(state: State, event: Event): Step = silenceTimer(state, event, endLateAnswers(react(state, event)))

    private val STOP_WORDS = setOf("stop", "cancel")

    // Words that may come before "stop" or "cancel", as in "Okay, stop scrolling" or "No, cancel the drag".
    private val BEFORE_STOP = setOf("ok", "okay", "please", "now", "no", "hey", "oh", "just", "wait", "so")

    // Words that may come before or with "no" during a countdown, as in "Oh no", "Wait, no" or "Thank you, no".
    private val NO_FILLERS =
        setOf("oh", "wait", "please", "now", "ok", "okay", "hey", "just", "not", "thanks", "thank", "you", "dont")

    private fun react(state: State, event: Event): Step =
        if (state.work == Work.RUNNING_COMMAND) duringCommand(state, event) else withoutCommand(state, event)

    private fun withoutCommand(state: State, event: Event): Step = when (event) {
        Event.Toggle -> when {
            state.inCall -> afterCall(state, if (state.beforeCall == Mode.LISTENING) Mode.PAUSED else Mode.LISTENING)
            else -> switch(state, if (state.mode == Mode.LISTENING) Mode.PAUSED else Mode.LISTENING)
        }
        is Event.Start ->
            if (state.mode == Mode.OFF) switch(state, if (event.paused) Mode.PAUSED else Mode.LISTENING) else Step(state)
        Event.Pause -> when {
            state.inCall -> afterCall(state, Mode.PAUSED)
            state.mode == Mode.LISTENING -> switch(state, Mode.PAUSED)
            else -> Step(state)
        }
        // The notification exists only while Kestrel is on, so a Resume while off comes from a stale notification.
        Event.Resume -> when {
            state.inCall -> afterCall(state, Mode.LISTENING)
            state.mode == Mode.PAUSED -> switch(state, Mode.LISTENING)
            else -> Step(state)
        }
        Event.TurnOff -> if (state.mode == Mode.OFF) Step(state) else turnOff(state)
        is Event.Heard -> heard(state, event)
        // The owner has started to answer "Yes?" or the conversation's question, so the wait lasts until the answer has
        // been transcribed.
        Event.Speaking -> when {
            state.speaking -> Step(state)
            state.work == Work.AWAITING_TASK -> waitForTask(state, answering = true)
            state.voiceQuestion != null -> waitForAnswer(state, answering = true)
            else -> Step(state)
        }
        is Event.Spotted -> spotted(state, event.keyword)
        Event.SpeechEnded -> speechEnded(state)
        is Event.MicBusy -> when {
            state.mode == Mode.OFF -> Step(state)
            event.busy && !state.micBusy -> Step(state.copy(micBusy = true), listOf(Effect.Show(Feedback.MicBusy)))
            else -> Step(state.copy(micBusy = event.busy))
        }
        is Event.Screen -> screen(state, event.on)
        is Event.WaitEnded ->
            if (state.work == Work.AWAITING_TASK && event.wait == state.wait) Step(state.copy(work = Work.IDLE)) else Step(state)
        Event.SettingsUnavailable -> if (state.mode == Mode.OFF) Step(state) else say(state, Reply.SettingsNeedService)
        // Not over a ringing call: the verdict comes up to 5 s after the request (SettingsOpener.WAIT_MILLIS).
        Event.SettingsBlocked -> if (state.mode == Mode.OFF || state.inCall) Step(state) else say(state, Reply.SettingsBlocked)
        Event.NothingHeard -> when {
            state.mode != Mode.OFF && state.work == Work.AWAITING_TASK -> waitForTask(state)
            state.mode != Mode.OFF && state.voiceQuestion != null -> waitForAnswer(state)
            else -> Step(state)
        }
        is Event.CallState -> call(state, event.state)
        Event.Assist -> assist(state)
        is Event.Options -> Step(
            state.copy(onWakeUp = event.onWakeUp, pauseAfterSilence = event.pauseAfterSilence, callCommands = event.callCommands),
        )
        is Event.Silent -> silent(state, event.silence)
        is Event.LateAnswersEnded ->
            if (event.command == state.command && state.questionEnded) Step(state.copy(questionEnded = false)) else Step(state)
        is Event.ConversationChanged -> conversationChanged(state, event.view)
        is Event.ChannelSays -> channelSays(state, event)
        is Event.ChannelShows -> Step(state, listOf(Effect.Show(Feedback.ConversationText(event.text))))
        is Event.ChannelAsks -> channelAsks(state, event)
        is Event.ChannelHandsOver -> channelHandsOver(state, event.text)
        is Event.ChannelWithdraws -> channelWithdraws(state, event.question)
        is Event.AnswerWaitEnded -> when {
            event.wait != state.wait -> Step(state)
            // A question that is still spoken keeps its wait: speechEnded() starts the whole wait again once it has been
            // spoken, so that a long confirmation is never declined before the owner has heard it (design D14 of
            // sub-project 5).
            state.speaking && state.voiceQuestion != null -> Step(state)
            // The conversation decides what silence means: "no" for a confirmation, the goal held for "Continue the
            // goal?" (design 6.5 and 11 of sub-project 7).
            state.voiceQuestion != null -> answer(state, ChannelAnswer.Silence, null)
            else -> Step(state)
        }
        // A command that has ended or was cancelled may still report; nothing changes then.
        is Event.CommandEnded, is Event.CommandAsks, is Event.CommandSays, is Event.CommandCounts -> Step(state)
    }

    private fun heard(state: State, event: Event.Heard): Step {
        if (state.mode == Mode.OFF) return Step(state)
        if (state.inCall) return callCommand(state, event)
        val awaiting = state.work == Work.AWAITING_TASK
        // An empty transcript that finishes after the screen turned off does not count: nobody could see its hint.
        // Waiting for a command ends when the screen turns off.
        val request = Requests.read(event.text, event.named, afterName = awaiting, stopSpotted = event.stopSpotted)
            ?: return if (state.screenOn) withoutWords(state, awaiting) else Step(state)
        // Words start the count of empty transcripts again.
        val counted = state.copy(emptyTranscripts = 0)
        val shown = Effect.Show(Feedback.Heard(event.text))
        val stopsFirst = stopsWorkFirst(counted, request, event)
        // A transcription that was queued before the microphone stopped can finish after the screen turned off. Then
        // only the requests that stop something count, because nothing may start (section 14.4); a "Stop." before the
        // name still stops the goal or Kestrel's own request.
        if (!counted.screenOn && !stopsSomething(request)) return if (stopsFirst) stopGoal(counted, shown) else Step(counted)
        if (stopsFirst) return stopFirst(counted, request, event, shown, event.spokeAt)
        return when (counted.work) {
            Work.AWAITING_TASK -> whileAwaitingTask(counted, request, event, shown)
            Work.IDLE -> idle(counted, request, event, shown, event.spokeAt)
            // duringCommand() reads the transcripts of a running command.
            Work.RUNNING_COMMAND -> Step(counted)
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

    private fun stopsSomething(request: Request): Boolean =
        request is Request.Stop || request == Request.PauseListening || request == Request.TurnOff

    // Design D9 of sub-project 7: while the goal acts or asks, "stop" and "cancel" without the name stop it, and only what
    // the owner says after the name reaches the conversation: a command steers the goal, Kestrel's own requests wait for
    // it. "Stop listening" and "turn off" change only the listening mode (plan 7B's decision 10). Anything else is ignored.
    private fun whileGoalWorks(state: State, request: Request, event: Event.Heard, shown: Effect): Step {
        if (request is Request.Stop) return stopGoal(state, shown)
        if (request == Request.PauseListening) return changeMode(state, Mode.PAUSED, shown)
        if (request == Request.TurnOff) return turnOff(state, shown)
        val intent = Requests.intent(request) ?: return Step(state)
        return converse(state, intent, event, shown)
    }

    // What "Stop. Kestrel, [request]" stops first: the goal in every phase, or Kestrel's own request while it asks or runs
    // (remember, forget, "save that as", delete history or a switch).
    private fun stopsWorkFirst(state: State, request: Request, event: Event.Heard): Boolean =
        (state.goal != null || state.openQuestion != null || state.conversation.ownRequest) &&
            request !is Request.Stop && request != Request.TurnOff && Requests.stopsFirst(event.text, event.named)

    // Design D2 of sub-project 5: "Stop. Kestrel, [request]" stops the goal in every phase, or Kestrel's own request, as
    // the stop alone would, and the request then counts as it does without them, so that the stop is never lost: the
    // conversation takes a command or Kestrel's own request after the stop, and the settings open. "Stop" and "turn off"
    // after the name keep their own rules. The transcript shows once, before the stop.
    private fun stopFirst(state: State, request: Request, event: Event.Heard, shown: Effect, spokeAt: Long?): Step {
        val stopped = stopGoal(state, shown)
        val after = whileIdle(stopped.state, request, event, shown, spokeAt)
        return Step(after.state, stopped.effects + after.effects.filter { it !== shown })
    }

    // Design D2 of sub-project 5: while the conversation asks, "stop" and "cancel" stop it in every kind of question, and
    // otherwise only the answer that it asked for counts. "Stop listening" and "turn off" change only the mode (plan 7B's
    // decision 10), and "Kestrel" on its own waits for a command; these withdraw Kestrel's own question, as they end an
    // instant command's question, while a goal's question stays. While only the card may answer (D32 item 17 of sub-project
    // 5), no spoken answer counts. A command after the name answers "Continue the goal?" as steering (design 6.5 of
    // sub-project 7).
    private fun answering(state: State, question: VoiceQuestion, request: Request, event: Event.Heard, shown: Effect): Step {
        if (request is Request.Stop || (request is Request.Other && startsWithStop(event.text))) return stopGoal(state, shown)
        if (request == Request.PauseListening || request == Request.TurnOff) {
            val withdrawn = if (state.goal == null) answer(state, ChannelAnswer.Interrupted, null, shown) else Step(state, listOf(shown))
            return withdrawn.then { if (request == Request.TurnOff) turnOff(it) else changeMode(it, Mode.PAUSED) }
        }
        if (request == Request.Name && state.goal == null) return askForTask(state, shown)
        if (!state.voiceAnswers) return Step(state, listOf(shown))
        if (question.kind == QuestionKind.CONTINUE_GOAL && request is Request.Task) return converse(state, InputIntent.Command(request.text), event, shown)
        val answer = VoiceAnswers.read(question.kind, event.text, event.named, request) ?: return Step(state, listOf(shown))
        return answer(state, answer, event, shown)
    }

    // The voice's answer to the open question, or the end of its wait ([ChannelAnswer.Silence], without a transcript).
    private fun answer(state: State, answer: ChannelAnswer, event: Event.Heard?, vararg first: Effect): Step {
        val number = state.openQuestion?.number
        return converse(state.copy(voiceQuestion = null, voiceAnswers = true, answered = number), InputIntent.Answer(answer), event, *first)
    }

    // The answer to "Yes?" is read as the words after the name, so it is never Other. While Kestrel is paused, a near miss
    // of "wake up" ends the wait and starts nothing (plan 4E). The conversation decides what a command means while a goal
    // waits for the owner's hand or return (design 6.4 of sub-project 7).
    private fun whileAwaitingTask(state: State, request: Request, event: Event.Heard, shown: Effect): Step {
        val idle = state.copy(work = Work.IDLE)
        return when (request) {
            is Request.Task ->
                if (request.nearWakeUp && state.mode == Mode.PAUSED) Step(idle, listOf(shown)) else converse(idle, InputIntent.Command(request.text), event, shown)
            is Request.Other -> converse(idle, InputIntent.Command(request.text), event, shown)
            Request.Name -> askForTask(state, shown)
            Request.WakeUp -> changeMode(idle, Mode.LISTENING, shown)
            is Request.Stop -> if (state.goal != null && Normalizer.words(event.text).any { it in STOP_WORDS }) stopGoal(state, shown) else Step(idle, listOf(shown))
            Request.PauseListening -> changeMode(idle, Mode.PAUSED, shown)
            Request.TurnOff -> turnOff(state, shown)
            is Request.OpenSettings -> Step(idle, listOf(shown, Effect.OpenSettings))
            // Requests.read never returns a call command; only readCall does.
            is Request.Call -> Step(state)
            else -> Requests.intent(request)?.let { converse(idle, it, event, shown) } ?: Step(idle, listOf(shown))
        }
    }

    // A transcript while Kestrel is idle: the conversation's question takes its answer first, a goal that works takes
    // only what stops it or reaches it after the name, and while a goal waits for the owner's hand or return, "stop" and
    // "cancel" stop it and the instant commands work (design D2 of sub-project 5; design 5.5 of sub-project 7).
    private fun idle(state: State, request: Request, event: Event.Heard, shown: Effect, spokeAt: Long? = null): Step {
        val question = state.openQuestion
        return when {
            stopsWorkFirst(state, request, event) -> stopFirst(state, request, event, shown, spokeAt)
            question != null -> answering(state, question, request, event, shown)
            state.goalWorks -> whileGoalWorks(state, request, event, shown)
            state.goal != null && request is Request.Stop -> stopGoal(state, shown)
            else -> whileIdle(state, request, event, shown, spokeAt)
        }
    }

    // A near miss of "Kestrel, wake up" while Kestrel is paused starts nothing: the owner meant to wake Kestrel up (plan 4E).
    // A command and Kestrel's own requests go to the conversation (design 5.5 of sub-project 7).
    private fun whileIdle(state: State, request: Request, event: Event.Heard, shown: Effect, spokeAt: Long? = null): Step = when (request) {
        is Request.Task ->
            if (request.nearWakeUp && state.mode == Mode.PAUSED) Step(state, listOf(shown)) else converse(state, InputIntent.Command(request.text), event, shown)
        Request.Name -> askForTask(state, shown)
        Request.WakeUp -> changeMode(state, Mode.LISTENING, shown)
        Request.PauseListening -> changeMode(state, Mode.PAUSED, shown)
        Request.TurnOff -> turnOff(state, shown)
        is Request.OpenSettings -> Step(state, listOf(shown, Effect.OpenSettings))
        is Request.Stop -> if (request.named) {
            // "Kestrel, stop" with nothing running only ends the speech; plain "stop" may name a button.
            Step(state, listOfNotNull(shown, Effect.StopSaying.takeIf { state.speaking }))
        } else {
            command(state, event.text.trim(), shown, spokeAt)
        }
        is Request.Other -> command(state, request.text, shown, spokeAt)
        // Requests.read never returns a call command; only readCall does.
        is Request.Call -> Step(state)
        else -> Requests.intent(request)?.let { converse(state, it, event, shown) } ?: Step(state)
    }

    // Instant commands work only while Kestrel listens (sections 8.5 and 9.5), and only with the screen on and the phone
    // unlocked. "Again" runs the last repeatable command again, with its transcript. During a goal's hand-over they work
    // in the paused mode too, so that a step handed over can be finished by voice (design D2 of sub-project 5).
    private fun command(state: State, text: String, shown: Effect, spokeAt: Long?): Step {
        val spotted = state.spottedStop
        val late = state.questionEnded
        val fresh = if (spotted || late) state.copy(spottedStop = false, questionEnded = false) else state
        val listens = fresh.mode == Mode.LISTENING || (fresh.mode == Mode.PAUSED && fresh.goal == GoalPhase.HANDED_OVER)
        if (!listens || !fresh.screenOn) return Step(fresh)
        // Design D2: the transcript of the "stop" or "cancel" whose keyword already cancelled the command names no button.
        if (spotted && startsWithStop(text)) return Step(fresh, listOf(shown))
        // An answer that the owner began just before the question's wait ran out arrives after the command has ended. Its
        // number would name an element of "show numbers" or a square of the grid by now (section 9.4).
        if (late && namesNumber(text)) return Step(fresh, listOf(shown, Effect.Show(Feedback.QuestionEnded)))
        return startCommand(fresh, text, shown, spokeAt)
    }

    private fun namesNumber(text: String): Boolean = SpokenNumbers.answer(text) != null || SpokenNumbers.namesNumber(CommandParser.parse(text))

    private fun startCommand(state: State, text: String, shown: Effect, spokeAt: Long?): Step {
        val parsed = CommandParser.parse(text) ?: return Step(state, listOf(shown))
        if (parsed != Command.Again) return runCommand(state, parsed, text, spokeAt, shown)
        val last = state.lastCommand
        val lastHeard = state.lastHeard
        if (last == null || lastHeard == null) return Step(state, listOf(shown, Effect.Show(Feedback.NothingToRepeat)))
        return runCommand(state, last, lastHeard, spokeAt, shown)
    }

    // Every command gets the next number, so that the end of an older one changes nothing.
    private fun runCommand(state: State, command: Command, heard: String, spokeAt: Long?, vararg first: Effect): Step {
        val number = state.command + 1
        return Step(
            state.copy(work = Work.RUNNING_COMMAND, command = number, running = command, heard = heard),
            first.toList() + Effect.RunCommand(number, command, heard, spokeAt),
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
            Step(
                state.copy(asking = false, questionEnded = true),
                listOf(Effect.AnswerCommand(state.command, null), Effect.TimeLateAnswers),
            )
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

    private fun leaveFor(state: State, event: Event): Step = leaveCommand(state).then { withoutCommand(it, event) }.stopSayingOnce()

    // leaveCommand() does not lower the count of replies, so the step after it may stop the same reply again.
    private fun Step.stopSayingOnce(): Step {
        val first = effects.indexOf(Effect.StopSaying)
        return if (first < 0) this else copy(effects = effects.filterIndexed { i, effect -> i == first || effect != Effect.StopSaying })
    }

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
        val request = Requests.read(event.text, event.named, stopSpotted = event.stopSpotted)
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
        if (request !is Request.Other) return leaveCommand(counted).then { idle(it, request, event, shown) }.stopSayingOnce()
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
        state.speaking && !state.counting -> leaveCommand(state).then { spotted(it, keyword) }.stopSayingOnce()
        else -> Step(state)
    }


    private fun spotted(state: State, keyword: Keyword): Step {
        if (!state.speaking || state.mode == Mode.OFF || state.inCall) return Step(state)
        return when (keyword) {
            // As after a cancelled command, the transcript of the same word then names no button (design D2). The spotter's
            // "stop" while Kestrel speaks stops the goal in every phase, also while Kestrel speaks a hand-over's sentence,
            // and the conversation's own question (plan 7B's decision 10).
            Keyword.STOP, Keyword.CANCEL ->
                if (state.goal != null || state.openQuestion != null) {
                    stopGoal(state.copy(spottedStop = true))
                } else {
                    Step(state.copy(work = Work.IDLE, spottedStop = true), listOf(Effect.StopSaying))
                }
            // Kestrel does not wait for a command while a goal works or asks "Continue the goal?", or the screen is off.
            Keyword.KESTREL ->
                if (state.goalWorks || (state.goal != null && state.openQuestion != null) || !state.screenOn) {
                    Step(state, listOf(Effect.StopSaying))
                } else {
                    waitForTask(state, Effect.StopSaying, Effect.Show(Feedback.AwaitingTask))
                }
        }
    }

    // The wait starts again when "Yes?" or the conversation's question has been spoken, so that the owner has all of it
    // to start the answer.
    private fun speechEnded(state: State): Step {
        val ended = state.copy(speeches = maxOf(0, state.speeches - 1))
        return when {
            state.speeches == 1 && state.work == Work.AWAITING_TASK -> waitForTask(ended)
            state.speeches == 1 && state.voiceQuestion != null -> waitForAnswer(ended)
            else -> stayOff(ended)
        }
    }

    // Design 5.5 of sub-project 7: what the voice reads of the conversation changed. The question that the voice answered
    // stays [State.answered] while the conversation still shows it, so that the next transcript does not answer it twice.
    private fun conversationChanged(state: State, view: VoiceView): Step {
        val changed = state.copy(
            conversation = view,
            answered = state.answered?.takeIf { it == view.question },
        )
        return stayOff(changed)
    }

    // Design 11 of sub-project 7: the voice channel's question ended, so its answer wait ends; a newer question that the
    // voice asked meanwhile stays.
    private fun channelWithdraws(state: State, question: Int): Step =
        if (state.voiceQuestion?.number == question) Step(state.copy(voiceQuestion = null, voiceAnswers = true)) else Step(state)

    // "Stay off" after an unlock waited for the goal's end and its final reply (design D2 of sub-project 5).
    private fun stayOff(state: State): Step =
        if (state.offAfterGoal && state.speeches == 0 && state.mode != Mode.OFF && !waitsForOwner(state)) turnOff(state) else Step(state)

    // The conversation's goal, or its final reply, waits for the owner (design 6.5 of sub-project 7).
    private fun waitsForOwner(state: State): Boolean =
        state.goal != null || state.conversation.replying

    // Design 11 of sub-project 7: Kestrel's words and final replies. While Kestrel is off they are only shown, and the
    // channel goes on at once.
    private fun channelSays(state: State, event: Event.ChannelSays): Step {
        val shown = Effect.Show(Feedback.ConversationText(event.text))
        if (state.mode == Mode.OFF) return Step(state, listOf(shown, Effect.EndReply(event.reply)))
        return say(state, Reply.Channel(event.reply, event.text), shown)
    }

    // Design D2 of sub-project 5 and 11 of sub-project 7: the conversation asks one question at a time. The question stays
    // on the bar until the voice channel releases it; the wait for the answer starts now and again when it has been spoken.
    private fun channelAsks(state: State, event: Event.ChannelAsks): Step {
        if (state.mode == Mode.OFF) return Step(state)
        val asked = state.copy(voiceQuestion = VoiceQuestion(event.question, event.kind), voiceAnswers = event.voice, answered = null)
        return say(asked, Reply.Text(event.text, kept = true), Effect.Show(Feedback.ConversationText(event.text, keep = true)))
            .then { waitForAnswer(it) }
    }

    // Design D15 of sub-project 5: the owner does the step by hand. The bar keeps the sentence, and the instant commands
    // work; the conversation runs the hand-over's 3 minutes.
    private fun channelHandsOver(state: State, text: String): Step {
        val shown = Effect.Show(Feedback.ConversationText(text, keep = true))
        if (state.mode == Mode.OFF) return Step(state, listOf(shown))
        return say(state, Reply.Text(text, kept = true), shown)
    }

    // The bar shows "Yes?" at the start of the wait, also when "Yes?" cannot be spoken (section 8.9).
    private fun askForTask(state: State, vararg first: Effect): Step =
        waitForTask(state, *first, Effect.Show(Feedback.AwaitingTask)).then { say(it, Reply.Yes) }

    // Every wait gets the next number, so that the end of an older wait changes nothing. A wait for a command withdraws
    // Kestrel's own question, as "Kestrel" over a command's question ends it, so that the command that the owner then says
    // reaches the conversation (design D2 of sub-project 5); a goal's question stays.
    private fun waitForTask(state: State, vararg first: Effect, answering: Boolean = false): Step {
        val waiting = state.copy(work = Work.AWAITING_TASK, wait = state.wait + 1)
        val step = Step(waiting, first.toList() + Effect.WaitForTask(answering))
        if (state.goal != null || state.openQuestion == null) return step
        return step.then { answer(it, ChannelAnswer.Interrupted, null) }
    }

    // Design 5.5 of sub-project 7: the owner's [intent] for the conversation. It ends the time in which a number may be a
    // late answer, as a task's start did.
    private fun converse(state: State, intent: InputIntent, heard: Event.Heard?, vararg first: Effect): Step =
        Step(state.copy(questionEnded = false), first.toList() + Effect.Converse(intent, heard))

    // "Stop" and "cancel" stop the goal in every phase, and withdraw the conversation's question: its answer wait ends,
    // and the conversation gets Stop (plan 7B's decision 10).
    private fun stopGoal(state: State, vararg first: Effect): Step = converse(
        state.copy(work = Work.IDLE, voiceQuestion = null, voiceAnswers = true, answered = state.openQuestion?.number ?: state.answered),
        InputIntent.Stop,
        null,
        *first,
        *listOfNotNull(Effect.StopSaying.takeIf { state.speaking }).toTypedArray(),
    )

    private fun say(state: State, reply: Reply, vararg first: Effect): Step =
        Step(state.copy(speeches = state.speeches + 1), first.toList() + Effect.Say(reply))

    private fun switch(state: State, mode: Mode, vararg first: Effect): Step =
        Step(state.copy(mode = mode), first.toList() + Effect.Show(Feedback.ModeChanged(mode)))

    // Shows a mode only when it changes.
    private fun changeMode(state: State, mode: Mode, vararg first: Effect): Step =
        if (state.mode == mode) Step(state, first.toList()) else switch(state, mode, *first)

    // Off also forgets the replies in progress and "mic busy", so that a lost SpeechEnded cannot keep recognition off,
    // and the count of empty transcripts, so that the next voice session may show the hint again. A goal goes on: the
    // conversation asks its questions on their cards while Kestrel is off (plan 7B's decision 10), and the voice asks them
    // again once Kestrel is on.
    private fun turnOff(state: State, vararg first: Effect): Step = Step(
        state.copy(
            mode = Mode.OFF,
            work = Work.IDLE,
            speeches = 0,
            micBusy = false,
            emptyTranscripts = 0,
            noWordsShown = false,
            beforeCall = null,
            // The next run's reports start the count of wake-ups again (section 8.5).
            screenSeen = false,
            // "Again" never repeats a command of an earlier run, and the owner's last words go.
            lastCommand = null,
            lastHeard = null,
            spottedStop = false,
            questionEnded = false,
            voiceQuestion = null,
            voiceAnswers = true,
            answered = null,
            offAfterGoal = false,
        ),
        first.toList() + listOfNotNull(
            Effect.StopSaying.takeIf { state.speaking },
            Effect.Show(Feedback.ModeChanged(Mode.OFF)),
            Effect.StopService,
        ),
    )

    // Section 8.5: after an unlock, Kestrel follows the setting "On screen wake-up" while it listens or is paused; a call
    // keeps its mode. Waiting for a task ends when the screen turns off.
    private fun screen(state: State, on: Boolean): Step {
        val changed = state.copy(
            screenOn = on,
            work = if (!on && state.work == Work.AWAITING_TASK) Work.IDLE else state.work,
            // An unlock is a wake-up only after the owner has used the phone in this run: not the first report after a
            // start, not the unlock after a start on the lock screen, and not a report from a service that stops.
            screenSeen = state.mode != Mode.OFF && (on || state.screenSeen),
        )
        // The conversation pauses its goal on the lock itself, with listening on or off (design 6.5 of sub-project 7).
        if (!on || state.screenOn || !state.screenSeen || (state.mode != Mode.LISTENING && state.mode != Mode.PAUSED)) {
            return Step(changed)
        }
        return when (state.onWakeUp) {
            WakeUpMode.RESUME -> Step(changed)
            WakeUpMode.LISTEN -> changeMode(changed, Mode.LISTENING)
            // "Stay off" waits until the goal has ended, so that it never drops "Continue the goal?" or the final reply.
            WakeUpMode.STAY_OFF -> if (waitsForOwner(changed)) Step(changed.copy(offAfterGoal = true)) else turnOff(changed)
        }
    }

    // Section 8.5: 30 s without speech pause listening. A timer that ran out while Kestrel spoke, waited for a task, ran
    // one, did not record or found its microphone blocked starts again, because the owner was not silent then, or could
    // not be heard.
    private fun silent(state: State, silence: Int): Step = when {
        silence != state.silence || state.mode != Mode.LISTENING || !state.pauseAfterSilence -> Step(state)
        !hears(state) ->
            Step(state.copy(silence = state.silence + 1), listOf(Effect.TimeSilence))
        else -> switch(state, Mode.PAUSED)
    }

    // Section 8.5: while Kestrel listens with the setting on, the timer starts whenever Kestrel can hear the owner again
    // (it starts to listen, the screen comes back on, or its reply, the wait for a task or a task ends), when the setting
    // goes on, when the owner starts to speak, and when an utterance ends, so that the time counts from the end of the
    // owner's speech. Each timer gets the next number.
    private fun silenceTimer(before: State, event: Event, step: Step): Step {
        val after = step.state
        val start = after.pauseAfterSilence && hears(after) &&
            (
                !hears(before) || !before.pauseAfterSilence || event == Event.Speaking || event is Event.Heard ||
                    event == Event.NothingHeard
                )
        return if (start) Step(after.copy(silence = after.silence + 1), step.effects + Effect.TimeSilence) else step
    }

    // Section 9.4: a late answer to a question that ended without one comes within moments. A pause, a call, a goal that
    // works or is paused, the wait for a command, and the screen turning off end that time, and so does
    // Event.LateAnswersEnded 10 s after the question.
    private fun endLateAnswers(step: Step): Step {
        val state = step.state
        val over = state.mode != Mode.LISTENING || !state.screenOn || state.work == Work.AWAITING_TASK ||
            state.goalWorks || state.goal == GoalPhase.PAUSED
        return if (state.questionEnded && over) step.copy(state = state.copy(questionEnded = false)) else step
    }

    // Kestrel listens, idle, silent itself and recording, and Android does not block its microphone ("Microphone
    // blocked", section 14.6): only then does the time without speech count.
    private fun hears(state: State): Boolean =
        state.mode == Mode.LISTENING && !state.speaking && state.work == Work.IDLE && state.microphone && !state.micBusy &&
            state.openQuestion == null && !state.goalWorks

    // Section 8.6: the assistant gesture or an earbud's button. Kestrel listens, if it was paused, and waits for a task
    // as after "Kestrel" on its own; over its own voice it stops speaking and waits, as after an interruption with
    // "Kestrel". While a goal works or a call or the lock paused it, it does what "Kestrel" does then: it stops speaking. A
    // call keeps its mode, and an Assist while Kestrel is off was queued behind a turn-off, because Listening sends it only
    // to an open session.
    private fun assist(state: State): Step = when {
        state.mode == Mode.OFF || state.inCall -> Step(state)
        state.goalWorks || state.goal == GoalPhase.PAUSED -> Step(state, listOfNotNull(Effect.StopSaying.takeIf { state.speaking }))
        // Section 8.5: Kestrel does not record on the lock screen, so it asks nothing there; it hears the owner after the
        // unlock, which is therefore no wake-up, as after a start on the lock screen.
        !state.screenOn -> changeMode(
            state.copy(screenSeen = false),
            Mode.LISTENING,
            *listOfNotNull(Effect.StopSaying.takeIf { state.speaking }).toTypedArray(),
        )
        state.speaking ->
            changeMode(state, Mode.LISTENING).then { waitForTask(it, Effect.StopSaying, Effect.Show(Feedback.AwaitingTask)) }
        else -> changeMode(state, Mode.LISTENING).then { askForTask(it) }
    }

    // Section 8.7: while a call rings, only "answer", "answer on speaker" and "decline" count, also on the lock screen;
    // during the call, only "hang up", "mute" and "speaker" after the name, and only with the setting "Commands during a
    // call".
    private fun callCommand(state: State, event: Event.Heard): Step {
        if (state.mode == Mode.IN_CALL && !state.callCommands) return Step(state)
        val command = Requests.readCall(event.text, event.named, ringing = state.mode == Mode.RINGING) ?: return Step(state)
        return Step(state, listOf(Effect.Show(Feedback.Heard(event.text)), Effect.CallCommand(command)))
    }

    // Section 8.7: a call that rings while Kestrel listens or is paused, or that is active, interrupts the mode, and the
    // mode comes back after the call. While Kestrel is off, calls change nothing.
    private fun call(state: State, call: CallState): Step = when (call) {
        CallState.RINGING ->
            if (state.mode == Mode.LISTENING || state.mode == Mode.PAUSED) enterCall(state, Mode.RINGING) else Step(state)
        CallState.ACTIVE -> when (state.mode) {
            Mode.LISTENING, Mode.PAUSED -> enterCall(state, Mode.IN_CALL)
            Mode.RINGING -> switch(state, Mode.IN_CALL)
            Mode.OFF, Mode.IN_CALL -> Step(state)
        }
        CallState.IDLE -> {
            val back = state.beforeCall
            if (!state.inCall || back == null) Step(state) else switch(state.copy(beforeCall = null), back)
        }
    }

    // Kestrel stops speaking at once, because Android refuses its audio focus during a call (prototype P3). The call ends
    // the wait for a command; the conversation pauses its goal and asks "Continue the goal?" after the call itself (design
    // 6.5 of sub-project 7).
    private fun enterCall(state: State, mode: Mode): Step = switch(
        state.copy(beforeCall = state.mode, work = Work.IDLE),
        mode,
        *listOfNotNull(Effect.StopSaying.takeIf { state.speaking }).toTypedArray(),
    )

    // During a call, the toggle, the pause and the resume choose the mode that comes back after the call, and the bar
    // shows it.
    private fun afterCall(state: State, mode: Mode): Step =
        if (state.beforeCall == mode) {
            Step(state)
        } else {
            Step(state.copy(beforeCall = mode), listOf(Effect.Show(Feedback.ModeChanged(mode))))
        }

    /** This step, then the step that [next] makes from its state. */
    private fun Step.then(next: (State) -> Step): Step = next(state).let { Step(it.state, effects + it.effects) }

    // Section 14.6: how many transcripts without words in a row bring the hint.
    private const val EMPTY_TRANSCRIPTS_FOR_HINT = 3
}
```

Replace the whole `app/src/main/kotlin/dev/kestrel/app/orchestrator/Orchestrator.kt` with:

```kotlin
package dev.kestrel.app.orchestrator

import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Applies [Rules] to Kestrel's events one at a time, so that two parts of Kestrel never act at once (design spec,
 * section 7.3). [perform] runs each effect in order on the Orchestrator's coroutine, so it must return quickly. An effect
 * that throws, even with a CancellationException, goes to [onError] and counts as not started: for a [Effect.Say] the
 * Orchestrator then handles [Event.SpeechEnded]. The next effects and events still run. [onError] must not throw. An [Error] from [perform], such as the NotImplementedError
 * that TODO() throws, is not caught and ends the Orchestrator's loop. The timer that ends the wait for a task after
 * "Yes?" runs here: [waitMillis] for the owner to start the answer, and [answerMillis] once the owner has started it. So
 * does the timer of the pause after [silenceMillis] without speech (section 8.5), and the timer of the answer to an
 * instant command's question: [questionMillis] for the owner to start the answer, and [answerMillis] once the owner has
 * started it; after a question without an answer, a number counts as a late answer for [lateMillis] (section 9.4). The
 * answer to a question of the conversation that the voice asked has the same waits, and the end of its wait is the
 * answer `Silence` (design 11 of sub-project 7); the conversation runs a goal's hand-over and pauses itself. A
 * [Effect.RunCommand] that throws counts as a command that ended as failed,
 * and the bar shows [commandFailed], so that Kestrel never fails silently (section 14). [onStep] sees each event with the
 * state before it and the step that it caused, on the Orchestrator's coroutine, after the new state is set and before the
 * step's effects run; the debug hooks of plan 4D read it, and a failure in it goes to [onError].
 */
class Orchestrator(
    private val scope: CoroutineScope,
    private val perform: (Effect) -> Unit,
    private val onError: (Exception) -> Unit,
    private val waitMillis: Long = WAIT_MILLIS,
    private val answerMillis: Long = ANSWER_MILLIS,
    private val silenceMillis: Long = SILENCE_MILLIS,
    private val questionMillis: Long = QUESTION_MILLIS,
    private val lateMillis: Long = LATE_MILLIS,
    private val commandFailed: String? = null,
    private val onStep: (Event, State, Step) -> Unit = { _, _, _ -> },
) {
    // Each event with the check that decides, when its turn comes, whether the Orchestrator still handles it.
    private val events = Channel<Pair<Event, () -> Boolean>>(Channel.UNLIMITED)
    private val current = MutableStateFlow(State())
    private var wait: Job? = null
    private var silence: Job? = null
    private var answer: Job? = null
    private var late: Job? = null

    /** The state after the last event. */
    val state: StateFlow<State> = current.asStateFlow()

    init {
        scope.launch {
            for ((event, valid) in events) if (valid()) handle(event)
        }
    }

    /** Sends [event] to the Orchestrator. Any thread may call it. */
    fun send(event: Event) = send(event) { true }

    /**
     * Sends [event], which the Orchestrator handles only if [valid] still returns true when the event's turn comes. Any
     * thread may call it; [valid] runs on the Orchestrator's coroutine, after every event that was sent before this one,
     * and must not throw.
     */
    fun send(event: Event, valid: () -> Boolean) {
        events.trySend(event to valid)
    }

    private suspend fun handle(event: Event) {
        val before = current.value
        val step = Rules.next(before, event)
        current.value = step.state
        try {
            onStep(event, before, step)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            onError(e)
        }
        if (step.state.work != Work.AWAITING_TASK) {
            wait?.cancel()
            wait = null
        }
        if (step.state.mode != Mode.LISTENING) {
            silence?.cancel()
            silence = null
        }
        if (!step.state.asking && step.state.voiceQuestion == null) {
            answer?.cancel()
            answer = null
        }
        if (!step.state.questionEnded) {
            late?.cancel()
            late = null
        }
        val notStarted = mutableListOf<Event>()
        for (effect in step.effects) {
            if (effect is Effect.WaitForTask) {
                val number = step.state.wait
                val millis = if (effect.answering) answerMillis else waitMillis
                wait?.cancel()
                wait = scope.launch {
                    delay(millis)
                    send(Event.WaitEnded(number))
                }
            } else if (effect is Effect.WaitForAnswer) {
                val number = step.state.wait
                val millis = if (effect.answering) answerMillis else questionMillis
                answer?.cancel()
                answer = scope.launch {
                    delay(millis)
                    send(Event.AnswerWaitEnded(number))
                }
            } else if (effect == Effect.TimeLateAnswers) {
                val number = step.state.command
                late?.cancel()
                late = scope.launch {
                    delay(lateMillis)
                    send(Event.LateAnswersEnded(number))
                }
            } else if (effect == Effect.TimeSilence) {
                val number = step.state.silence
                silence?.cancel()
                silence = scope.launch {
                    delay(silenceMillis)
                    send(Event.Silent(number))
                }
            } else {
                try {
                    perform(effect)
                } catch (e: Exception) {
                    // perform does not suspend, so a CancellationException from it is not the Orchestrator's own.
                    currentCoroutineContext().ensureActive()
                    onError(e)
                    when (effect) {
                        is Effect.Say -> notStarted += Event.SpeechEnded
                        is Effect.RunCommand ->
                            notStarted += Event.CommandEnded(effect.command, CommandResult(Outcome.FAILED, show = commandFailed))
                        else -> {}
                    }
                }
            }
        }
        notStarted.forEach { send(it) }
    }

    private companion object {
        // Section 8.3: the owner has 8 s to start the task after "Yes?". The wait starts again when "Yes?" has been
        // spoken. Plan 2D measures it on the A71.
        const val WAIT_MILLIS = 8_000L

        // Once the owner has started to answer: the longest utterance (30 s, section 8.3), the end-of-speech pause
        // (0.7 s) and a transcription on the A71 (at most 4 s, section 5.3). The endpointer holds an answer and lets it
        // go up to 1.85 s after its end, but at the latest 30 s after its start, so the worst case stays at about
        // 30.7 s and a transcription. Plan 2D measures it on the A71.
        const val ANSWER_MILLIS = 35_000L

        // Section 8.5: the optional pause after 30 s without speech.
        const val SILENCE_MILLIS = 30_000L

        // Design D2 of sub-project 3: the owner has 15 s to start answering an instant command's question, counted from
        // the end of the question.
        const val QUESTION_MILLIS = 15_000L

        // Section 9.4: a late answer began before the question's wait ran out, so its transcript comes within a few
        // seconds of the end of the question.
        const val LATE_MILLIS = 10_000L
    }
}
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt`, replace:

```kotlin
package dev.kestrel.app.orchestrator
```

with:

```kotlin
package dev.kestrel.app.orchestrator

import dev.kestrel.conversation.model.InputIntent
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt`, replace:

```kotlin
    }

    private fun words(transcript: String): List<Word> =
```

with:

```kotlin
    }

    /**
     * What [request] asks of the conversation (design 5.5 of sub-project 7): a command, "stop", "continue" or one of
     * Kestrel's own requests, which the conversation runs; null for the requests that only the voice handles, such as the
     * name, the modes, the settings and the instant commands.
     */
    fun intent(request: Request): InputIntent? = when (request) {
        is Request.Task -> InputIntent.Command(request.text)
        is Request.Stop -> InputIntent.Stop
        Request.Continue -> InputIntent.Continue
        Request.DeleteHistory -> InputIntent.DeleteHistory
        is Request.Remember -> InputIntent.Remember(request.key, request.value)
        is Request.Forget -> InputIntent.Forget(request.key)
        is Request.SaveRoutine -> InputIntent.SaveRoutine(request.name)
        Request.Name, Request.WakeUp, Request.PauseListening, Request.TurnOff, Request.OpenSettings, is Request.Call, is Request.Other -> null
    }

    private fun words(transcript: String): List<Word> =
```

Create `app/src/main/kotlin/dev/kestrel/app/orchestrator/VoiceAnswers.kt`:

```kotlin
package dev.kestrel.app.orchestrator

import dev.kestrel.commands.SpokenNumbers
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.ChannelAnswer

/**
 * The spoken answers that the conversation's questions take (design D2 of sub-project 5; design 11 of sub-project 7),
 * read on Normalizer's words, so that "Confirm." counts like "confirm" and "Kestrel, continue" like "continue". A
 * confirmation goes ahead on "confirm", "yes" or "send it"; a yes-or-no question, a limit's "Continue?", the switch to a
 * past conversation and "Continue the goal?" take "yes", "continue" or "go on"; "Delete the history?" takes only "yes",
 * because nothing else may delete the owner's history (plan 4E's rule); all of them take "no". The polite words
 * and the name may come with them. A free question and the screenshot question take any words, and a pick a number.
 * Anything else is no answer, and the question goes on.
 */
internal object VoiceAnswers {
    private val FILLERS = setOf("please", "now", "ok", "okay", "thanks", "thank", "you", "oh", "well")
    private val CONFIRM = setOf("confirm", "yes", "send it")
    private val GO_ON = setOf("yes", "continue", "go on")
    private val YES = setOf("yes")

    /** The answer to a question of [kind] in the transcript [text], which Requests read as [request], or null for none. */
    fun read(kind: QuestionKind, text: String, named: Boolean, request: Request): ChannelAnswer? = when (kind) {
        QuestionKind.CONFIRM -> yesOrNo(text, named, CONFIRM)
        QuestionKind.YES_NO, QuestionKind.CONTINUE_GOAL, QuestionKind.LIMIT, QuestionKind.SWITCH -> yesOrNo(text, named, GO_ON)
        QuestionKind.DELETE_HISTORY -> yesOrNo(text, named, YES)
        QuestionKind.PICK -> SpokenNumbers.answer(text.trim())?.let { ChannelAnswer.Pick(it) }
        QuestionKind.FREE, QuestionKind.SCREENSHOT -> when (request) {
            is Request.Task -> request.text
            is Request.Other -> request.text
            else -> text.trim()
        }.takeIf { it.isNotEmpty() }?.let { ChannelAnswer.Text(it) }
    }

    private fun phrase(text: String, named: Boolean): String =
        Normalizer.words(text).filter { it !in FILLERS && Requests.read(it, named) != Request.Name }.joinToString(" ")

    private fun yesOrNo(text: String, named: Boolean, yes: Set<String>): ChannelAnswer? = when (phrase(text, named)) {
        in yes -> ChannelAnswer.Yes
        "no" -> ChannelAnswer.No
        else -> null
    }
}
```

Create `app/src/main/kotlin/dev/kestrel/app/orchestrator/VoiceView.kt`:

```kotlin
package dev.kestrel.app.orchestrator

import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.machine.HoldReason
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.model.GoalPhase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * What the voice reads of the conversation (design 5.5 of sub-project 7): whether one is [open], its goal's phase, its
 * open question's kind and number, whether a final reply waits for the owner ([replying], the quiet clock held for it),
 * and whether one of Kestrel's own requests runs ([ownRequest]), which "Stop. Kestrel, …" stops first. It holds no words
 * and no records, so a goal's traces and steps never reach the Orchestrator.
 */
data class VoiceView(
    val open: Boolean = false,
    val goal: GoalPhase? = null,
    val asking: QuestionKind? = null,
    val question: Int? = null,
    val replying: Boolean = false,
    val ownRequest: Boolean = false,
) {
    companion object {
        fun of(view: ConversationView): VoiceView = VoiceView(
            open = view.id != null,
            goal = view.goal,
            asking = view.asking,
            question = view.question,
            replying = view.quiet == QuietClock.Held(HoldReason.REPLYING),
            ownRequest = view.ownRequest,
        )
    }
}

/** The conversation's views as the voice reads them, each one only when what the voice reads changed. */
fun Flow<ConversationView>.forVoice(): Flow<VoiceView> = map(VoiceView::of).distinctUntilChanged()
```

- [ ] **Step 8: Listening's path to the conversation, and the voice channel**

Create `app/src/main/kotlin/dev/kestrel/app/listen/VoicePath.kt`:

```kotlin
package dev.kestrel.app.listen

import dev.kestrel.conversation.channel.QuestionKind
import kotlinx.coroutines.flow.StateFlow

/**
 * The voice channel's way to the Orchestrator (design 11 of sub-project 7), which [Listening] gives: the Orchestrator
 * still decides every word that Kestrel speaks, and runs the answer waits of a question that the voice asks.
 */
internal interface VoicePath {
    /** Whether a voice session is open, so that Kestrel can speak and hear. */
    val ready: StateFlow<Boolean>

    /**
     * How many times the Orchestrator has stopped Kestrel's speech (`Effect.StopSaying`, as the owner's stop does): a
     * spoken output that waited for its turn across a stop is dropped (decision 10 of sub-project 7).
     */
    val speechStops: Long

    /** Says [text] and shows it on the bar; returns once it has been spoken, stopped or refused, or only shown while Kestrel is off. */
    suspend fun say(text: String)

    /** Shows [text] on the bar without speaking it. */
    fun show(text: String)

    /** Asks the conversation's question number [question] of [kind] aloud as [text]; with [voice] false only its card answers it. */
    fun ask(question: Int, text: String, kind: QuestionKind, voice: Boolean)

    /** Says a hand-over's [text], which the bar keeps until the channel releases it. */
    fun handOver(text: String)

    /** The conversation's question number [question] ended: the voice no longer waits for its answer. */
    fun withdraw(question: Int)
}
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
import dev.kestrel.app.commands.CommandRunner
```

with:

```kotlin
import dev.kestrel.app.commands.CommandRunner
import dev.kestrel.app.conversation.InputIds
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
import dev.kestrel.app.orchestrator.TaskAsk
import dev.kestrel.app.orchestrator.TaskPhase
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
```

with:

```kotlin
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
import kotlinx.coroutines.flow.filterNotNull
```

with:

```kotlin
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
import kotlinx.coroutines.withContext
```

with:

```kotlin
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
import java.util.concurrent.atomic.AtomicInteger
```

with:

```kotlin
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
            Effect.StopSaying -> session?.stopSpeaking()
```

with:

```kotlin
            Effect.StopSaying -> {
                stopsOfSpeech.incrementAndGet()
                session?.stopSpeaking()
            }
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
    const val YES = "Yes?"
    const val TASK_FAILED = "I couldn't start the task."
    // The same text as AgentTexts.CANCELLED, for a task that was cancelled before AgentRunner had it.
    const val TASK_CANCELLED = "Cancelled."
```

with:

```kotlin
    const val YES = "Yes?"
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
        "the launcher."

    // A task's hand-over (design D2 of sub-project 5).
    const val NO_TASK_TO_CONTINUE = "There's no task to continue."
    const val FINISH_HAND_OVER_FIRST = "Say 'Kestrel, continue' or 'cancel' first."

    // A task's pause and Kestrel's history (design D2 of sub-project 5).
    const val CONTINUE_TASK = "Continue the task?"
    const val ANSWER_CONTINUE_FIRST = "Answer 'Continue the task?' first, or say 'cancel'."
    const val DELETE_HISTORY_QUESTION = "Delete the activity log and the network log? Say yes or no."
    const val HISTORY_DELETED = "I deleted the history."
    const val HISTORY_NOT_DELETED = "I couldn't delete the history."
```

with:

```kotlin
        "the launcher."
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
 * screen in [turns], and a new command, a task or the settings wait until it has completed, for at most 3 s, so that two
 * parts of Kestrel never drive the screen at once. Every command that started gets one report in [commandReports].
```

with:

```kotlin
 * screen in [turns], and a new command or the settings wait until it has completed, for at most 3 s, as a goal's round
 * does through the same turns, so that two parts of Kestrel never drive the screen at once. Commands, answers, stops and
 * Kestrel's own requests go to the conversation through [converse], and the conversation speaks through [voicePath]. Every command that started gets one report in [commandReports].
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
     * Runs task number `task`, and must send [Event.TaskEnded] for it once it ends: sub-project 5's AgentRunner, which
     * the app graph sets. Until it is set, every task ends at once with [ListeningTexts.TASK_FAILED], or Kestrel would
     * wait for it for ever. The debug hooks replace it with a task that takes its time. Called on the controller's
     * thread; it must not block.
     */
    @Volatile
    internal var runTask: (task: Int, text: String) -> Unit = { task, _ -> send(Event.TaskEnded(task, ListeningTexts.TASK_FAILED)) }

    /**
     * Where the effects that steer a running task go, such as an answer to its question or the end of its hand-over
     * (design D2 of sub-project 5): sub-project 5's AgentRunner. Called on the controller's thread; it must not block.
     */
    @Volatile
    internal var steerTask: (Effect) -> Unit = {}

    /**
     * Runs task number `task`, which is Kestrel's own memory request (design D2 of sub-project 5), and must send
     * [Event.TaskEnded] for it once it ends. Sub-project 5's AgentRunner runs it; until the app graph sets it, it ends
     * at once, as a task does. Called on the controller's thread; it must not block.
     */
    @Volatile
    internal var runMemory: (task: Int, request: Request.Memory) -> Unit = { task, _ -> send(Event.TaskEnded(task, ListeningTexts.TASK_FAILED)) }
```

with:

```kotlin
     * Where the owner's spoken inputs go (design 5.5 of sub-project 7): the conversation's engine, which the app graph
     * sets. Called on the controller's thread; it must not block.
     */
    @Volatile
    internal var converse: (OwnerInput) -> Unit = {}

    /**
     * Whether Kestrel waits for a command after "Yes?", which holds the conversation's quiet clock (design 6.2 of
     * sub-project 7): the app graph passes it to the engine. Called on the controller's thread at every change.
     */
    @Volatile
    internal var awaitingCommand: (Boolean) -> Unit = {}
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
    private var cancelledCommand = 0

    // Only [thread] uses it: the newest pause of a task, for a task whose start waited for a cancelled command.
    private var lastPause: Effect.PauseTask? = null

    // The number of the newest command whose job started. Written on [thread] and read by the jobs, so that a stuck job
```

with:

```kotlin
    private var cancelledCommand = 0


    // The number of the newest command whose job started. Written on [thread] and read by the jobs, so that a stuck job
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
     * The options that a debug hook passed with the transcript that started task number `task`, such as `model` and
     * `live` (plan 4D, for sub-project 5's AgentRunner, which reads them once per task); the read removes them. Null, and
     * so no options, outside the debug hooks, which alone set it.
     */
    @Volatile
    internal var taskOptions: ((task: Int) -> Map<String, String>)? = null
```

with:

```kotlin
     * The debug hooks' seam for each spoken input (plan 4D): it gets the transcript that gave the input, or null, and the
     * input, and returns the input to send, with the options that a debug hook passed with the transcript, such as
     * `model` and `live`. Null, and so the input as it is, outside the debug hooks, which alone set it.
     */
    @Volatile
    internal var inputs: ((Event.Heard?, OwnerInput) -> OwnerInput)? = null

    // The conversation's replies that wait for their speech's end, by their numbers (design 11 of sub-project 7).
    private val replies = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
    private val replyNumbers = AtomicInteger()

    // How many times the Orchestrator stopped Kestrel's speech; the voice channel drops the spoken outputs that waited for
    // their turn across a stop (decision 10 of sub-project 7).
    private val stopsOfSpeech = AtomicLong()

    /** The voice channel's way to the Orchestrator (design 11 of sub-project 7). Any thread may use it. */
    internal val voicePath: VoicePath = object : VoicePath {
        override val ready: StateFlow<Boolean> get() = sessionOpen

        override suspend fun say(text: String) {
            val reply = replyNumbers.incrementAndGet()
            val ended = CompletableDeferred<Unit>()
            replies[reply] = ended
            try {
                send(Event.ChannelSays(reply, text))
                ended.await()
            } finally {
                replies.remove(reply)
            }
        }

        override fun show(text: String) = send(Event.ChannelShows(text))

        override fun ask(question: Int, text: String, kind: QuestionKind, voice: Boolean) =
            send(Event.ChannelAsks(question, text, kind, voice))

        override fun handOver(text: String) = send(Event.ChannelHandsOver(text))

        override fun withdraw(question: Int) = send(Event.ChannelWithdraws(question))

        override val speechStops: Long get() = stopsOfSpeech.get()
    }
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
        taskFailed = ListeningTexts.TASK_FAILED,
        onStep = { event, before, step ->
            // "Continue the task?" fades from the bar once it has ended, before the step's own texts show.
            if (before.taskAsking == TaskAsk.CONTINUE && step.state.taskAsking != TaskAsk.CONTINUE) bar.release()
            steps?.invoke(event, before, step)
        },
```

with:

```kotlin
        onStep = { event, before, step -> steps?.invoke(event, before, step) },
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
        scope.launch(thread) { orchestrator.state.collect { followSafely(it) } }
```

with:

```kotlin
        scope.launch(thread) { orchestrator.state.collect { followSafely(it) } }
        scope.launch(thread) {
            orchestrator.state.map { it.work == Work.AWAITING_TASK }.distinctUntilChanged().collect { awaitingCommand(it) }
        }
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
            is Effect.RunTask -> {
                timeline.reacted("task")
                afterStopping({ send(Event.TaskEnded(effect.task, ListeningTexts.TASK_FAILED)) }) {
                    startTask(effect.task) { runTask(effect.task, effect.text) }
                }
            }
            Effect.CancelTask -> steerTask(effect)
```

with:

```kotlin
            // The conversation takes the screen's turn itself before a goal acts (design 7.1 of sub-project 7).
            is Effect.Converse -> {
                if (effect.intent is InputIntent.Command) timeline.reacted("task")
                val input = OwnerInput(InputIds.next(), InputChannel.VOICE, effect.intent, clock())
                converse(inputs?.invoke(effect.heard, input) ?: input)
            }
            is Effect.EndReply -> replies[effect.reply]?.complete(Unit)
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
                    // "Continue the task?" stays until it has its answer (design D2 of sub-project 5).
                    val keep = (effect.feedback as? Feedback.CommandText)?.keep == true ||
                        (effect.feedback as? Feedback.TaskText)?.keep == true || effect.feedback == Feedback.ContinueQuestion
```

with:

```kotlin
                    // The conversation's question and a hand-over's sentence stay until the voice channel releases them.
                    val keep = (effect.feedback as? Feedback.CommandText)?.keep == true ||
                        (effect.feedback as? Feedback.ConversationText)?.keep == true
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
            is Effect.WaitForTask, Effect.TimeSilence, is Effect.WaitForAnswer, Effect.TimeLateAnswers, Effect.TimeHandOver,
            Effect.TimePause, Effect.ArmTouches -> Unit
            is Effect.AnswerTask, is Effect.ContinueTask, is Effect.ExpireHandOver, is Effect.ResumeTask,
            is Effect.ExpirePause, Effect.DeleteHistory -> steerTask(effect)
            is Effect.PauseTask -> {
                lastPause = effect
                steerTask(effect)
            }
            is Effect.RunMemoryTask -> {
                timeline.reacted("task")
                afterStopping({ send(Event.TaskEnded(effect.task, ListeningTexts.TASK_FAILED)) }) {
                    startTask(effect.task) { runMemory(effect.task, effect.request) }
                }
            }
```

with:

```kotlin
            is Effect.WaitForTask, Effect.TimeSilence, is Effect.WaitForAnswer, Effect.TimeLateAnswers -> Unit
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
            Reply.NoTaskToContinue -> ListeningTexts.NO_TASK_TO_CONTINUE
            Reply.ContinueQuestion -> ListeningTexts.CONTINUE_TASK
            Reply.DeleteHistoryQuestion -> ListeningTexts.DELETE_HISTORY_QUESTION
            is Reply.HistoryDeleted -> if (reply.deleted) ListeningTexts.HISTORY_DELETED else ListeningTexts.HISTORY_NOT_DELETED
        }
        // A command's say() waits for this reply, and returns once it has ended in any way.
        val ended = (reply as? Reply.Command)?.let { conversation?.takeSpoken(it.command) }
        // Section 14.6: a reply that Kestrel cannot speak, or that Android interrupted, goes to the bar, except "Yes?",
        // which the bar shows already at the start of the wait for the task (Feedback.AwaitingTask), and the texts that
        // the Rules showed already and keep on the bar: a command's text, a task's question or hand-over, and "Continue
        // the task?" (design D2 of sub-project 5).
        val kept = reply is Reply.Command || (reply as? Reply.Text)?.kept == true || reply == Reply.ContinueQuestion
```

with:

```kotlin
            is Reply.Channel -> reply.text
        }
        // A command's say() and the voice channel's say() wait for this reply, and return once it has ended in any way.
        val ended = when (reply) {
            is Reply.Command -> conversation?.takeSpoken(reply.command)
            is Reply.Channel -> replies[reply.reply]
            else -> null
        }
        // Section 14.6: a reply that Kestrel cannot speak, or that Android interrupted, goes to the bar, except "Yes?",
        // which the bar shows already at the start of the wait for the task (Feedback.AwaitingTask), and the texts that
        // the Rules showed already: a command's text, and what the conversation says, asks or hands over (design 11 of
        // sub-project 7).
        val kept = reply is Reply.Command || reply is Reply.Channel || (reply as? Reply.Text)?.kept == true
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
        is Feedback.TaskText -> feedback.text
        Feedback.FinishHandOverFirst -> ListeningTexts.FINISH_HAND_OVER_FIRST
        Feedback.ContinueQuestion -> ListeningTexts.CONTINUE_TASK
        Feedback.AnswerContinueFirst -> ListeningTexts.ANSWER_CONTINUE_FIRST
```

with:

```kotlin
        is Feedback.ConversationText -> feedback.text
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
     */
    // Design D2 of sub-project 5: a task whose start waited for a cancelled command starts only while the Orchestrator
    // still has it, and a pause that came during the wait reaches it at once (AgentRunner keeps a pause that comes before
    // the task can take it). A task that the owner cancelled in the meantime ends with "Cancelled.", as AgentRunner's
    // early cancels do; one that a newer task replaced ends without a reply.
    private fun startTask(task: Int, start: () -> Unit) {
        val now = state.value
        if (now.task != task || !now.taskExists || now.cancelled) {
            val cancelled = now.task == task && now.cancelled
            send(Event.TaskEnded(task, reply = if (cancelled) ListeningTexts.TASK_CANCELLED else null))
            return
        }
        start()
        if (now.taskPhase == TaskPhase.PAUSED) lastPause?.takeIf { it.task == task }?.let(steerTask)
    }

    private fun afterStopping(failed: () -> Unit = {}, action: () -> Unit) {
```

with:

```kotlin
     */
    private fun afterStopping(failed: () -> Unit = {}, action: () -> Unit) {
```

Create `app/src/main/kotlin/dev/kestrel/app/conversation/InputIds.kt`:

```kotlin
package dev.kestrel.app.conversation

import java.util.concurrent.atomic.AtomicLong

/** The numbers of the owner's inputs, unique in the process (design 5.3 of sub-project 7), for every channel. */
internal object InputIds {
    private val last = AtomicLong()

    fun next(): Long = last.incrementAndGet()
}
```

Create `app/src/main/kotlin/dev/kestrel/app/conversation/OwnerSupport.kt`:

```kotlin
package dev.kestrel.app.conversation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import dev.kestrel.agent.port.MediaAction
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.app.orchestrator.CallState
import dev.kestrel.datatools.media.MediaKeys
import dev.kestrel.datatools.notifications.KestrelNotificationListener
import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.HandOverKind
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.model.ScreenTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** The goal's touch watcher around the owner's questions and hand-overs (design D19 of sub-project 5). */
internal interface TouchWatch {
    /** Clears the touch watcher's credits before a question (rule 3 of D19). */
    fun forgetOwnActions()

    /** Starts the touch watcher again: an instant command during a hand-over or a pause stops it when it ends (D19). */
    suspend fun watch()
}

/** Kestrel's own parts of the screen: the confirmation card, the feedback bar, and its own app in front. */
internal interface OwnerScreen {
    /** Shows the confirmation card with [question] until a button answers it; null when no screen session is connected. */
    suspend fun card(question: String): Boolean?

    /** Lets the feedback bar hide the text that a question or a hand-over kept on it. */
    suspend fun releaseBar()

    /** Whether a window of Kestrel's own app, not one of its overlays, is in front (design 10.2 of sub-project 7). */
    suspend fun kestrelInFront(): Boolean
}

/** The other apps' audio while Kestrel asks (design D32 item 17 of sub-project 5). */
internal interface OtherAudio {
    /** Whether another app's audio of a media or game usage plays: Kestrel's own voice uses the assistant usage. */
    fun playing(): Boolean

    /** Pauses the other app's audio with the media key, as `media_control`'s pause does. */
    suspend fun pause()
}

/** The hand-overs' parts on Android (design D15 of sub-project 5). */
internal interface HandOverSignals {
    /** The hand-over of [kind] has begun; the notification-access hand-over opens Android's page here. */
    suspend fun opened(kind: HandOverKind)

    /**
     * Returns when the hand-over of [kind] ended by itself: notification access when Kestrel's listener connects and
     * Android's page has left the front, a call when the owner placed it. It never returns for the other kinds.
     */
    suspend fun endedByItself(kind: HandOverKind)
}

/** Android's permission dialog for Kestrel's own [permissions] (design D12 of sub-project 5). */
internal fun interface PermissionDialog {
    suspend fun ask(permissions: List<String>): PermissionEnd
}

/**
 * The owner's parts on the phone, which both of the conversation's channels and a goal's rounds share (design 5.5 of
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

    override suspend fun card(question: String): Boolean? = session()?.confirm(question)

    override suspend fun releaseBar() {
        session()?.releaseFeedback()
    }

    override fun forgetOwnActions() {
        session()?.forgetOwnActions()
    }

    // Starting a running watcher only updates its layout and clears the credits.
    override suspend fun watch() {
        session()?.startTouchWatcher()
    }

    // Kestrel's own app in front, by the agent's one rule; its overlays (the bar, the card, the touch watcher) never are.
    override suspend fun kestrelInFront(): Boolean = session()?.let { AgentFreeApps.kestrelInFront(it.snapshots.current().snapshot) } ?: false

    override fun playing(): Boolean = audio.activePlaybackConfigurations.any { it.audioAttributes.usage in OTHER_USAGES }

    override suspend fun pause() {
        MediaKeys(audio).press(MediaAction.PAUSE)
    }

    override suspend fun opened(kind: HandOverKind) {
        if (kind == HandOverKind.ACCESS) open(KestrelNotificationListener.accessSettings(context))
    }

    override suspend fun endedByItself(kind: HandOverKind) {
        when (kind) {
            HandOverKind.ACCESS -> awaitAccessGiven(KestrelNotificationListener.connected, accessPage()) {
                session()?.let { ScreenTree.frontWindow(it.snapshots.current().snapshot)?.packageName }
            }
            HandOverKind.CALL -> awaitOutgoingCall(calls())
            else -> awaitCancellation()
        }
    }

    // The app of Android's notification-access page: Settings on the phones that Kestrel knows.
    private suspend fun accessPage(): String = withContext(Dispatchers.IO) {
        context.packageManager.resolveActivity(KestrelNotificationListener.accessSettings(context), 0)?.activityInfo?.packageName
    } ?: SETTINGS

    override fun toString(): String = "AndroidOwnerSupport"

    private companion object {
        const val SETTINGS = "com.android.settings"
        val OTHER_USAGES = setOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME)
    }
}

/**
 * Returns when the notification-access hand-over ends by itself (design D15; final review of plan 5B, part B, M1):
 * Kestrel's listener is [connected] and Android's page, an app [page], is no longer in [front], because the owner went
 * back, home or to another app. Until then the hand-over stays open, so that the owner's Back or Home on that page, right
 * after he turned access on by touch, does not count as a touch on the goal. "Kestrel, continue" ends it at any time, as
 * the spoken sentence asks.
 */
internal suspend fun awaitAccessGiven(connected: Flow<Boolean>, page: String, front: suspend () -> String?) {
    connected.first { it }
    while (front() == page) delay(FRONT_POLL_MILLIS)
}

/**
 * Returns when [calls] show a call that became active without ringing first, which is a call that the owner placed (the
 * CALL hand-over of design D15 then ends by itself). A call that rang is an incoming call and does not count; once it is
 * over, the next call counts again.
 */
internal suspend fun awaitOutgoingCall(calls: Flow<CallState>) {
    var rang = false
    calls.first { call ->
        when (call) {
            CallState.RINGING -> rang = true
            CallState.ACTIVE -> {}
            CallState.IDLE -> rang = false
        }
        call == CallState.ACTIVE && !rang
    }
}

private const val FRONT_POLL_MILLIS = 300L
```

Create `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`:

```kotlin
package dev.kestrel.app.conversation

import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.app.listen.VoicePath
import dev.kestrel.conversation.channel.ChannelOutput
import dev.kestrel.conversation.channel.ChannelQuestion
import dev.kestrel.conversation.channel.OwnerChannel
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.safety.Category
import dev.kestrel.safety.HandOver
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The conversation's voice (design 11 of sub-project 7): it speaks and shows through the Orchestrator ([voice]), which
 * decides every word that Kestrel speaks and runs the answer waits, and it takes Android's parts from [screen], [audio],
 * [handOvers] and [permissions]. A
 * question is spoken whenever a voice session is open, and again when one opens after Kestrel was off; its spoken answer
 * reaches the conversation as an input, so [ask] returns only a card's answer: a confirmation's, which shows while Kestrel
 * speaks, as today (design D14 of sub-project 5). While another app's audio plays, a confirmation of a send or a delete,
 * and the screenshot question, take the voice only after Kestrel paused that audio; when it still plays, only the card's
 * button counts, and when Kestrel paused it, it says [AUDIO_PAUSED] first (D32 item 17). The bar lets a question's or a
 * hand-over's text go once it has ended.
 */
internal class VoiceChannel(
    private val voice: VoicePath,
    private val screen: OwnerScreen,
    private val audio: OtherAudio,
    private val handOvers: HandOverSignals,
    private val permissions: PermissionDialog,
    private val audioSettleMillis: Long = AUDIO_SETTLE_MILLIS,
) : OwnerChannel {
    override val kind: InputChannel = InputChannel.VOICE

    // Spoken outputs take turns in the order in which they came: each starts once the one before it has ended, so that a
    // sentence that the engine says at once, as "I got 'X' too late", never plays over a goal's final words. Whatever
    // stops Kestrel's speech, as the owner's stop does (decision 10), also drops the outputs that wait for their turn, as
    // it drops the voice session's queued speech: only the outputs that come after it play.
    private val speaking = Mutex()

    override suspend fun show(output: ChannelOutput) {
        when (output) {
            is ChannelOutput.Said -> inTurn { voice.say(output.text) }
            is ChannelOutput.Report -> inTurn { voice.say(output.text) }
            is ChannelOutput.Shown -> voice.show(output.text)
        }
    }

    private suspend fun inTurn(speak: suspend () -> Unit) {
        val came = voice.speechStops
        speaking.withLock { if (voice.speechStops == came) speak() }
    }

    override suspend fun ask(question: ChannelQuestion): ChannelAnswer = try {
        when (question.kind) {
            QuestionKind.CONFIRM -> confirm(question)
            QuestionKind.SCREENSHOT -> screenshot(question)
            else -> spoken(question, question.spoken, voiceAnswers = true)
        }
    } finally {
        // The voice's answer wait ends, and a withdrawn question still lets the bar go (D32 item 1 of sub-project 5).
        voice.withdraw(question.number)
        withContext(NonCancellable) { screen.releaseBar() }
    }

    override suspend fun handOver(handOver: HandOver): HandOverEnd = try {
        voice.handOver(handOver.text)
        handOvers.opened(handOver.kind)
        handOvers.endedByItself(handOver.kind)
        HandOverEnd.DONE_BY_ITSELF
    } finally {
        withContext(NonCancellable) { screen.releaseBar() }
    }

    // Kestrel's own permission request is a hand-over of kind PERMISSION (design D12 and D15 of sub-project 5): it ends
    // when Android's dialog answers, or when the owner says "Kestrel, continue" first.
    override suspend fun askPermission(permissions: List<String>, why: String): PermissionEnd = try {
        voice.handOver(why)
        this.permissions.ask(permissions)
    } finally {
        withContext(NonCancellable) { screen.releaseBar() }
    }

    // Nothing moves Kestrel's own screen away until plan 7F, so the goal waits for the owner to leave it (design 10.2 of
    // sub-project 7).
    override suspend fun leaveKestrel(): Boolean =
        withTimeoutOrNull(OwnerPort.KESTREL_LEAVE_MILLIS) {
            while (screen.kestrelInFront()) delay(KESTREL_POLL_MILLIS)
            true
        } ?: false

    // The card and the spoken question together: the card's button answers here, the voice as an input (D32 item 18).
    private suspend fun confirm(question: ChannelQuestion): ChannelAnswer {
        val guarded = question.confirmation?.category.let { it == Category.SENDING || it == Category.DELETING }
        val playing = guarded && audio.playing()
        val voiced = !guarded || voiceAllowed()
        val spoken = if (playing && voiced) "$AUDIO_PAUSED ${question.spoken}" else question.spoken
        return withCard(question, spoken, voiced)
    }

    private suspend fun screenshot(question: ChannelQuestion): ChannelAnswer {
        if (!audio.playing()) return spoken(question, question.spoken, voiceAnswers = true)
        if (voiceAllowed()) return spoken(question, "$AUDIO_PAUSED ${question.spoken}", voiceAnswers = true)
        return withCard(question, question.spoken, voiceAnswers = false)
    }

    // Without a screen session there is no card, and only the voice answers.
    private suspend fun withCard(question: ChannelQuestion, spoken: String, voiceAnswers: Boolean): ChannelAnswer = coroutineScope {
        val asking = launch { spoken(question, spoken, voiceAnswers) }
        val yes = screen.card(question.text) ?: awaitCancellation()
        asking.cancel()
        if (yes) ChannelAnswer.Yes else ChannelAnswer.No
    }

    // Asks the question each time a voice session opens, from now on, until the conversation withdraws it.
    private suspend fun spoken(question: ChannelQuestion, text: String, voiceAnswers: Boolean): Nothing {
        var asked = false
        voice.ready.collect { open ->
            if (open && !asked) voice.ask(question.number, text, question.kind, voiceAnswers)
            asked = open
        }
    }

    // Design D32 item 17: the voice counts only when no other app's audio plays, or after Kestrel paused it.
    private suspend fun voiceAllowed(): Boolean {
        if (!audio.playing()) return true
        audio.pause()
        delay(audioSettleMillis)
        return !audio.playing()
    }

    override fun toString(): String = "VoiceChannel"

    companion object {
        const val AUDIO_PAUSED = "I paused the other app's audio."

        /** How long Android takes to report a paused player as stopped. */
        const val AUDIO_SETTLE_MILLIS = 500L

        /** How often [leaveKestrel] reads whether Kestrel's own screen is still in front. */
        const val KESTREL_POLL_MILLIS = 200L
    }
}
```

In `app/src/main/kotlin/dev/kestrel/app/phone/PhoneSignals.kt`, replace:

```kotlin
import dev.kestrel.app.orchestrator.CallState
```

with:

```kotlin
import dev.kestrel.app.orchestrator.CallState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
```

In `app/src/main/kotlin/dev/kestrel/app/phone/PhoneSignals.kt`, replace:

```kotlin
    /**
     * The calls as one [CallMonitor] for listening (section 8.7): a start watches the calls with its report and ends the
```

with:

```kotlin
    /**
     * The call states while the flow is collected (design D15 of sub-project 5): a call hand-over ends by itself when the
     * owner places the call, with listening on or off. The flow fails with what [watchCalls] throws.
     */
    fun callStates(): Flow<CallState> = callbackFlow {
        val watch = watchCalls { state -> trySend(state) }
        awaitClose { watch.stop() }
    }

    /**
     * The calls as one [CallMonitor] for listening (section 8.7): a start watches the calls with its report and ends the
```

Create `app/src/main/kotlin/dev/kestrel/app/conversation/ConversationSignals.kt`:

```kotlin
package dev.kestrel.app.conversation

import dev.kestrel.app.agent.CallBackstop
import dev.kestrel.app.orchestrator.CallState
import dev.kestrel.app.phone.PhoneSignals
import dev.kestrel.app.phone.PhoneWatch
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.PhoneSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The phone's lock and calls as the conversation's [PhoneSignal]s (design 6.5 of sub-project 7), with listening on or
 * off: while a conversation is open, a goal is, or Kestrel keeps words until the owner is there
 * ([ConversationView.keptForOwner]), it watches [phone] and sends [signal] each change. A call that becomes active without
 * ringing while the goal acts or asks, within the call backstop's window after the agent's last action, is
 * [PhoneSignal.AGENT_CALL] instead of [PhoneSignal.CALL_ACTIVE], and [backstop] ends that call off the caller's thread in
 * [scope] (design D13 of sub-project 5). The first watch in the process sends the lock state that it reads, `UNLOCKED` too
 * (also on a phone without a lock screen), because the conversation counts the owner as there only once a lock signal
 * said so (plan 7B). Later signals go on from the readings that the conversation last got, so a change while nothing
 * watched counts at the next watch. Each time the screen comes on while it watches, locked or not, it sends
 * [PhoneSignal.SCREEN_ON], so that the engine ends the timers whose deadlines passed while the phone slept. Without the permission for the phone state it sees no call.
 */
internal class ConversationSignals(
    private val phone: PhoneSignals,
    private val backstop: CallBackstop,
    private val scope: CoroutineScope,
    private val signal: (PhoneSignal) -> Unit,
    private val report: (String, Throwable) -> Unit,
) {
    private val lock = Any()

    // The lock state that the conversation last got; null until the first reading in the process.
    private var unlocked: Boolean? = null
    private var call = CallState.IDLE
    private var rang = false

    @Volatile
    private var goal: GoalPhase? = null

    /** Follows the conversation's [views] for as long as it runs: it watches the phone while one is open or words wait. */
    suspend fun follow(views: Flow<ConversationView>) {
        var watches = emptyList<PhoneWatch>()
        try {
            views.collect { view ->
                goal = view.goal
                val open = view.id != null || view.goal != null || view.keptForOwner
                if (open && watches.isEmpty()) {
                    watches = watch()
                } else if (!open && watches.isNotEmpty()) {
                    watches.forEach { it.stop() }
                    watches = emptyList()
                }
            }
        } finally {
            watches.forEach { it.stop() }
        }
    }

    private fun watch(): List<PhoneWatch> {
        val screen = phone.watchScreen(::screenCameOn, ::screenChanged)
        val calls = try {
            phone.watchCalls(::callChanged)
        } catch (e: Exception) {
            report("Watching the calls", e)
            null
        }
        return listOfNotNull(screen, calls)
    }

    private fun screenChanged(on: Boolean) {
        val changed = synchronized(lock) { (unlocked != on).also { unlocked = on } }
        if (changed) signal(if (on) PhoneSignal.UNLOCKED else PhoneSignal.LOCKED)
    }

    // Locked or not: the engine then ends the timers whose deadlines passed while the phone slept (plan 7B).
    private fun screenCameOn() = signal(PhoneSignal.SCREEN_ON)

    // Android repeats the state at every new watch, so only a change counts.
    private fun callChanged(state: CallState) {
        val sent = synchronized(lock) {
            if (state == call) return
            call = state
            when (state) {
                CallState.RINGING -> PhoneSignal.RINGING.also { rang = true }
                CallState.IDLE -> PhoneSignal.CALL_IDLE.also { rang = false }
                CallState.ACTIVE -> if (!rang && acts() && backstop.stops()) PhoneSignal.AGENT_CALL else PhoneSignal.CALL_ACTIVE
            }
        }
        signal(sent)
        if (sent == PhoneSignal.AGENT_CALL) endCall()
    }

    // The backstop sees only a goal that acts or asks, as it saw only a running task: a call that the owner places
    // during a hand-over or a pause is his.
    private fun acts(): Boolean = goal == GoalPhase.ACTING || goal == GoalPhase.ASKING

    // Ending the call takes binder calls, which must not run on Android's thread.
    private fun endCall() {
        scope.launch {
            try {
                backstop.endCall()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Ending the agent's call", e)
            }
        }
    }

    override fun toString(): String = "ConversationSignals(goal=$goal)"
}
```

Create `app/src/main/kotlin/dev/kestrel/app/conversation/StoredSettings.kt`:

```kotlin
package dev.kestrel.app.conversation

import dev.kestrel.conversation.model.ConversationSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * The Conversations page's choices as the conversation reads them (design 13 of sub-project 7): a cold flow whose first
 * value is the stored one, so that the restore at the process's start decides with the owner's quiet time, never with
 * the defaults that a StateFlow holds before the store answers. A failed read gives the defaults, and [report] gets it.
 */
internal fun storedConversationSettings(stored: Flow<ConversationSettings>, report: (String, Throwable) -> Unit): Flow<ConversationSettings> =
    stored.catch { error ->
        report("Reading the conversation settings", error)
        emit(ConversationSettings())
    }
```

Replace the whole `app/src/main/kotlin/dev/kestrel/app/agent/CallBackstop.kt` with:

```kotlin
package dev.kestrel.app.agent

import dev.kestrel.app.listen.CallApi

/**
 * The backstop against a call that an action of the agent placed (design D13 of sub-project 5; review B3-I1). The agent
 * hands every call over, so no action of its own should start one, and this backstop needs no list of apps: when a call
 * becomes active without ringing while a goal acts or asks, and within [WINDOW_MILLIS] after the agent's last action
 * ([stops]), the conversation ends the goal at once and, with Android's permission for calls, Kestrel ends that call
 * ([endCall]). `ConversationSignals` decides which call it is (plan 7D): a call that rang is an incoming call, which
 * Kestrel never ends, and a call that the owner places during a hand-over or a pause never reaches the backstop. Android
 * reports the call state only with `READ_PHONE_STATE`, so without it nothing reaches the backstop either. [clock] is
 * monotonic, such as `SystemClock.elapsedRealtime`.
 */
internal class CallBackstop(private val calls: CallApi, private val clock: () -> Long) {
    @Volatile
    private var lastAction: Long? = null

    /** The agent performed an action on the screen or sent an intent just now. */
    fun acted() {
        lastAction = clock()
    }

    /** A new round starts: an action of an earlier round never counts. */
    fun forget() {
        lastAction = null
    }

    /** Whether a call that became active without ringing just now is the agent's: its last action was within the window. */
    fun stops(): Boolean {
        val last = lastAction ?: return false
        return clock() - last <= WINDOW_MILLIS
    }

    /**
     * Ends the call that [stops] found, with Android's permission for calls. These are binder calls, so the caller runs it
     * off Android's thread; it throws what [CallApi] throws, except a SecurityException.
     */
    fun endCall() {
        try {
            if (calls.allowed()) calls.end()
        } catch (e: SecurityException) {
            // The owner took the permission back meanwhile; the goal stops all the same.
        }
    }

    override fun toString(): String = "CallBackstop(acted=${lastAction != null})"

    companion object {
        const val WINDOW_MILLIS = 5_000L
    }
}
```

- [ ] **Step 9: The rounds' environment**

Create `app/src/main/kotlin/dev/kestrel/app/agent/GoalOwner.kt`:

```kotlin
package dev.kestrel.app.agent

import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.AnswerKind
import dev.kestrel.agent.port.ConfirmEnd
import dev.kestrel.agent.port.Gate
import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.app.conversation.TouchWatch
import dev.kestrel.safety.Confirmation
import dev.kestrel.safety.HandOver

/**
 * The owner as one round of a goal talks to him (design D19 of sub-project 5; design 5.5 of sub-project 7): the
 * conversation's [owner], with the goal's touch watcher around it. Every question clears the watcher's credits first, so
 * that the owner's answer by touch is never taken for Kestrel's own action; a hand-over, a permission request and a pause
 * start the watcher again when they end, because an instant command in between stops it (D19). Every `say` text also goes
 * to the round's [witness], which the debug hooks read (plan 6B-basefix).
 */
internal class GoalOwner(
    private val owner: OwnerPort,
    private val watcher: TouchWatch,
    private val witness: TaskWitness,
) : OwnerPort {
    override suspend fun say(text: String) {
        witness.said(text)
        owner.say(text)
    }

    override suspend fun ask(question: String, kind: AnswerKind, choices: List<String>, highest: Int): Answer {
        watcher.forgetOwnActions()
        return owner.ask(question, kind, choices, highest)
    }

    override suspend fun confirm(confirmation: Confirmation): ConfirmEnd {
        watcher.forgetOwnActions()
        return owner.confirm(confirmation)
    }

    override suspend fun handOver(handOver: HandOver): HandOverEnd = owner.handOver(handOver).also { watcher.watch() }

    override suspend fun askPermission(permissions: List<String>, why: String): PermissionEnd =
        owner.askPermission(permissions, why).also { watcher.watch() }

    override suspend fun gate(): Gate = owner.gate().also { if (it == Gate.RESUMED) watcher.watch() }

    override suspend fun leaveKestrel(): Boolean = owner.leaveKestrel()

    override fun toString(): String = "GoalOwner"
}
```

Create `app/src/main/kotlin/dev/kestrel/app/agent/RoundModels.kt`:

```kotlin
package dev.kestrel.app.agent

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.port.ModelPort

/**
 * How a round's model is chosen (design D28 of sub-project 5): a release build ([debuggable] false) always uses the
 * default profile's models ([profile]). In a debuggable build a round runs only with a model that a debug hook or a device
 * test installed ([modelOverride]), or, with the hook's extra `live`, while a live sitting is open ([liveSession]), with
 * the extra `model` as its lightweight model; otherwise it is refused, so that no plan step, device test or script can
 * call a provider.
 */
internal class RoundModels(
    private val debuggable: Boolean,
    private val liveSession: suspend () -> Boolean,
    private val profile: suspend (lightweight: String?) -> ModelPort?,
) {
    /** What [choose] gives: the round's model, or why it cannot have one. */
    sealed interface Choice {
        data class Model(val port: ModelPort) : Choice

        data class Refused(val sentence: String, val end: EndReason) : Choice {
            override fun toString(): String = "Refused(${sentence.length} characters, end=$end)"
        }
    }

    /** Debug builds only: the model that the hook `agent-script` or a device test installed (design D28). */
    @Volatile
    internal var modelOverride: ModelPort? = null

    /** The model of a round whose input carried the debug hook's [options]. */
    suspend fun choose(options: Map<String, String>): Choice {
        val override = modelOverride
        val port = when {
            !debuggable -> profile(null)
            options[LIVE] == "true" && liveSession() -> profile(options[MODEL])
            override != null -> override
            else -> return Choice.Refused(AgentTexts.couldNotStart(AgentTexts.TEST_BUILD), EndReason.NO_PROFILE)
        }
        return port?.let { Choice.Model(it) } ?: Choice.Refused(AgentTexts.NO_PROFILE, EndReason.NO_PROFILE)
    }

    override fun toString(): String = "RoundModels(debuggable=$debuggable, override=${modelOverride != null})"

    companion object {
        /** The debug hook's extras (plan 4D's options). */
        const val MODEL = "model"
        const val LIVE = "live"
    }
}
```

Create `app/src/main/kotlin/dev/kestrel/app/agent/RoundEvidences.kt`:

```kotlin
package dev.kestrel.app.agent

/**
 * What the debug hooks read of the newest [MAX] rounds, by the command that started each (plan 6B-basefix; design 14.4
 * of sub-project 7): the owner's touches that the watcher reported during the round, how many of them were signals
 * without a finger (branch B's, or a gesture of Kestrel's that a touch cancelled), and what the round's witness
 * observed. A release build reads none of it. toString prints only counts.
 */
internal class RoundEvidences {
    /** Command [command]'s round as the hooks read it. */
    data class Evidence(val touches: Int = 0, val signals: Int = 0, val witness: TaskEvidence = TaskEvidence())

    /** One round while it runs: its touch collector counts, and its witness observes. */
    class Round internal constructor(private val witness: TaskWitness) {
        @Volatile
        var touches = 0
            private set

        @Volatile
        var signals = 0
            private set

        fun touched(signal: Boolean) {
            touches++
            if (signal) signals++
        }

        fun evidence(): Evidence = Evidence(touches, signals, witness.evidence)
    }

    private val rounds = LinkedHashMap<Int, Round>()

    /** Command [command]'s round starts with [witness]; a resumed goal's next round has its own command. */
    @Synchronized
    fun start(command: Int, witness: TaskWitness): Round {
        val round = Round(witness)
        rounds.remove(command)
        rounds[command] = round
        while (rounds.size > MAX) rounds.remove(rounds.keys.first())
        return round
    }

    /** Command [command]'s round, among the newest [MAX], or null. */
    @Synchronized
    fun of(command: Int): Evidence? = rounds[command]?.evidence()

    override fun toString(): String = "RoundEvidences(${rounds.size})"

    companion object {
        const val MAX = 16
    }
}
```

Create `app/src/main/kotlin/dev/kestrel/app/agent/AppGoalEnvironment.kt`:

```kotlin
package dev.kestrel.app.agent

import android.content.Context
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import dev.kestrel.agent.AgentPorts
import dev.kestrel.agent.AgentSettings
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.port.Badge
import dev.kestrel.agent.port.EyesPort
import dev.kestrel.agent.port.NoEyes
import dev.kestrel.agent.port.TaskLog
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.commands.AndroidDialer
import dev.kestrel.app.conversation.TouchWatch
import dev.kestrel.conversation.goal.GoalEnvironment
import dev.kestrel.conversation.goal.RoundPurpose
import dev.kestrel.conversation.goal.RoundSetup
import dev.kestrel.conversation.goal.RoundStart
import dev.kestrel.datatools.AndroidDataPort
import dev.kestrel.privacy.logs.ActivityEvent
import dev.kestrel.privacy.settings.SettingsStore
import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.EmergencyGuard
import dev.kestrel.safety.EmergencyNumbers
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.OwnActions
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * What each round of a goal needs from Kestrel's phone (design D1, D2, D12, D13, D16 and D19 of sub-project 5; design 5.5
 * and 7.1 of sub-project 7). A round of a goal needs Kestrel's accessibility service and the touch watcher: without the
 * service it cannot start, and when the watcher's window cannot be added it does not run; both end the goal as
 * [EndReason.NO_SCREEN]. "Save that as" ([RoundPurpose.ROUTINE]) needs only the model and the memory. [models] chooses
 * the model, and a round without one ends as [EndReason.NO_PROFILE]. The settings come from the encrypted store when the round starts: the mode, test mode and the
 * owner's names, the agent-free apps with the owner's changes, Android's emergency-number check and the phone apps with
 * the default one. A round of a goal forgets the call backstop's last action, gives the backstop every action of its own
 * ([CallBackstop.acted]), and its owner port is [GoalOwner] over the conversation's. [eyes] and the badge functions are
 * read at each round, because plan 5C sets them. Release stops the watcher and drops the round's media items.
 */
internal class AppGoalEnvironment(
    private val context: Context,
    private val session: () -> ScreenSession?,
    private val settings: SettingsStore,
    private val memory: SettingsMemoryStore,
    private val appendLog: suspend (ActivityEvent) -> Unit,
    private val models: RoundModels,
    private val watcher: TouchWatch,
    private val backstop: CallBackstop,
    private val eyes: () -> EyesPort,
    private val showBadges: suspend (ScreenSession, List<Badge>) -> Unit,
    private val hideBadges: suspend (ScreenSession) -> Unit,
    private val knownNames: suspend () -> Set<String>,
    private val debuggable: Boolean,
    private val report: (String, Throwable) -> Unit,
    private val newId: (Int) -> String = ::taskId,
) : GoalEnvironment {
    private val apps = AndroidApps(context)

    /** What the debug hooks read of each round (plan 6B-basefix). */
    internal val evidences = RoundEvidences()

    /** Debug builds only: the self-chat aliases that the hook `agent-settings` set (design D18); empty otherwise. */
    @Volatile
    internal var selfChatAliases: Map<String, String> = emptyMap()

    /**
     * Debug builds only: makes the witness of each round from its input's options (plan 6B-basefix), which the debug
     * hooks install. Null in a release build, whose rounds get [TaskWitness.NONE].
     */
    @Volatile
    internal var witnesses: ((options: Map<String, String>) -> TaskWitness)? = null

    /**
     * Debug builds only: prepares every round in place of the phone's ports and words its end, as the A71 session's
     * `voice-task` does (plan 2E's stand-in task, design 14.4 of sub-project 7). Null otherwise.
     */
    @Volatile
    internal var standIn: GoalEnvironment? = null

    // The provider of the newest round, which names it in an error's sentence.
    @Volatile
    private var provider = ""

    override suspend fun prepare(start: RoundStart): RoundSetup {
        standIn?.let { return it.prepare(start) }
        if (start.purpose == RoundPurpose.ROUTINE) return routine(start)
        // Kestrel's accessibility service first, as the task path checked it before plan 7D: without it no model is
        // chosen, so the owner hears at once that the service is off, also in a test build without a script.
        val screen = session() ?: return RoundSetup.Refused(AgentTexts.couldNotStart(AgentTexts.NO_SERVICE), EndReason.NO_SCREEN)
        val model = when (val choice = choose(start)) {
            is RoundModels.Choice.Refused -> return RoundSetup.Refused(choice.sentence, choice.end)
            is RoundModels.Choice.Model -> choice.port
        }
        val agentSettings = agentSettings()
        val taskId = newId(start.command)
        val log = LogTaskLog(taskId, appendLog, report)
        backstop.forget()
        val witness = witnesses?.invoke(start.options) ?: TaskWitness.NONE
        val round = evidences.start(start.command, witness)
        val data = witness.watch(AndroidDataPort(context, agentSettings.agentFree::isAgentFree))
        val ports = AgentPorts(
            AppScreenPort(session, context.packageManager, showBadges, hideBadges, backstop::acted),
            eyes(),
            data,
            GoalOwner(start.owner, watcher, witness),
            model,
            apps,
            memory,
            log,
            AndroidAgentClock,
            start.goal,
            start.history,
        )
        // Read before the watcher starts, so that a failed read leaves no watcher on the screen.
        val names = knownNames()
        if (!startWatcherOrStop(screen::startTouchWatcher, screen::stopTouchWatcher)) {
            return RoundSetup.Refused(AgentTexts.NO_WATCHER, EndReason.NO_SCREEN)
        }
        val touches = screen.touches.filter { it.owner }
            .onEach { touch ->
                round.touched(signal = touch.deviceId == OwnActions.SIGNAL_DEVICE)
                touch.sinceNodeActionMillis?.takeIf { it <= OwnActions.LATE_ECHO_MILLIS }?.let {
                    log.write(TaskLog.Kind.ACTION, "possible late echo after $it ms")
                }
            }
            .map { }
        return RoundSetup.Ready(ports, agentSettings, taskId, names, touches, offersRoutines = !debuggable) {
            try {
                screen.stopTouchWatcher()
            } finally {
                data.forgetTask()
            }
        }
    }

    // "Save that as" (design D23 of sub-project 5): a routine's round needs only the model and the memory.
    private suspend fun routine(start: RoundStart): RoundSetup {
        val model = when (val choice = choose(start)) {
            is RoundModels.Choice.Refused -> return RoundSetup.Refused(choice.sentence, choice.end)
            is RoundModels.Choice.Model -> choice.port
        }
        val agentSettings = agentSettings()
        val taskId = newId(start.command)
        val log = LogTaskLog(taskId, appendLog, report)
        val data = AndroidDataPort(context, agentSettings.agentFree::isAgentFree)
        val screen = AppScreenPort(session, context.packageManager, showBadges, hideBadges) {}
        val ports = AgentPorts(screen, NoEyes, data, start.owner, model, apps, memory, log, AndroidAgentClock, start.goal, start.history)
        return RoundSetup.Ready(ports, agentSettings, taskId, emptySet(), emptyFlow(), offersRoutines = false) {}
    }

    // The round's model, whose provider names it in an error's sentence.
    private suspend fun choose(start: RoundStart): RoundModels.Choice =
        models.choose(start.options).also { choice -> if (choice is RoundModels.Choice.Model) provider = choice.port.model.providerName }

    override fun sentence(report: TaskReport): String = standIn?.sentence(report) ?: AgentTexts.sentence(report, provider)

    private suspend fun agentSettings(): AgentSettings {
        val changes = settings.get(SettingKeys.AGENT_FREE_APPS)
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val phoneApp = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        return AgentSettings(
            safety = settings.get(SettingKeys.SAFETY),
            agentFree = AgentFreeApps(changes.added.toSet(), changes.removed.toSet()),
            emergency = EmergencyNumbers { digits -> AndroidDialer.emergency(telephony, digits) },
            phonePackages = EmergencyGuard.PHONE_PACKAGES + listOfNotNull(phoneApp),
            selfChatAliases = selfChatAliases,
        )
    }

    override fun toString(): String = "AppGoalEnvironment"

    companion object {
        /** `task-<n>-<8 hexadecimal characters>` (design D25 of sub-project 5), for the logs of command n's round. */
        fun taskId(command: Int): String = "task-$command-%08x".format(Random.nextInt())
    }
}

/**
 * Starts the touch watcher (design D19 of sub-project 5). When [start] throws, also when the round is cancelled after the
 * watcher's window was added, [stop] removes the window again before the exception goes on, because no release would.
 */
internal suspend fun startWatcherOrStop(start: suspend () -> Boolean, stop: suspend () -> Unit): Boolean = try {
    start()
} catch (e: Throwable) {
    withContext(NonCancellable) { stop() }
    throw e
}
```

`AppModels`, `AndroidApps` and `AndroidAgentClock` move unchanged from the deleted `AppAgentEnvironment.kt`, and `Permissions` from the deleted `OwnerParts.kt`.

Create `app/src/main/kotlin/dev/kestrel/app/agent/AppModels.kt`:

```kotlin
package dev.kestrel.app.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import dev.kestrel.agent.port.AgentClock
import dev.kestrel.agent.port.AppsPort
import dev.kestrel.agent.port.LauncherApp
import dev.kestrel.agent.port.ModelPort
import dev.kestrel.app.providers.ProviderGraph
import dev.kestrel.providers.client.PlanLimits
import dev.kestrel.providers.client.ProfileClient
import dev.kestrel.providers.discovery.DiscoveredModel
import dev.kestrel.providers.profiles.ModelRole
import dev.kestrel.providers.profiles.Profile
import dev.kestrel.providers.profiles.ProvidersSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime

/**
 * The default profile's models for a task (design D1, D5, D7 and D28 of sub-project 5): it waits up to 5 s for the store
 * to answer, because just after the process starts the repository answers null although a profile exists, and then
 * gives null only when no default profile is set up. A task's [port] with a lightweight model ID of its own (the debug
 * hook's extra `model`) gets a client that resolves that ID for the lightweight role and the profile's model for the
 * versatile one.
 */
internal class AppModels(
    private val providers: () -> ProviderGraph,
    private val applied: StateFlow<ProvidersSetting?>,
    private val discovered: suspend (profileId: String) -> List<DiscoveredModel>,
    private val cache: CacheState,
    private val report: (String, Throwable) -> Unit,
) {
    suspend fun port(lightweight: String?): ModelPort? {
        val graph = providers()
        withTimeoutOrNull(STORED_WAIT_MILLIS) { graph.stored.first { it != null } }
        val client = graph.repository.defaultClient() ?: return null
        val profile = client.profile
        val info = ModelInfos.of(profile, graph.catalog.provider(profile.providerId), discovered(profile.id), lightweight)
        val chosen = if (lightweight == null) client else withLightweight(graph, profile, lightweight)
        return AppModelPort(info, cache, chosen::complete)
    }

    private fun withLightweight(graph: ProviderGraph, profile: Profile, lightweight: String) = ProfileClient(
        profile,
        graph.catalog,
        applied,
        { current, role ->
            val resolved = if (role == ModelRole.LIGHTWEIGHT) current.copy(lightweight = current.lightweight.copy(id = lightweight)) else current
            graph.resolver.resolve(resolved, role)
        },
        graph::adapter,
        graph.localNetwork,
        graph::addSpending,
        NO_LIMITS,
        report = report,
    )

    override fun toString(): String = "AppModels"

    private companion object {
        const val STORED_WAIT_MILLIS = 5_000L

        // A comparison run of the debug hook does not mark a ChatGPT plan's limit.
        val NO_LIMITS = object : PlanLimits {
            override fun reached(profileId: String) = Unit

            override fun cleared(profileId: String) = Unit
        }
    }
}

/** The launcher apps of the owner's profile (design D11 of sub-project 5), read with the `<queries>` of spec 13.6. */
internal class AndroidApps(private val context: Context) : AppsPort {
    override suspend fun launcherApps(): List<LauncherApp> = withContext(Dispatchers.IO) {
        val packages = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        packages.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
            .map { info ->
                val app = info.activityInfo.packageName
                LauncherApp(info.loadLabel(packages).toString(), app, version(packages, app))
            }
            .distinctBy { it.packageName }
    }

    override suspend fun label(packageName: String): String? = withContext(Dispatchers.IO) {
        val packages = context.packageManager
        try {
            packages.getApplicationLabel(packages.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun version(packages: PackageManager, app: String): String? = try {
        packages.getPackageInfo(app, PackageManager.PackageInfoFlags.of(0)).versionName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}

/** Kestrel's working time, which a sleeping phone does not stop, and the local time of the task message. */
internal object AndroidAgentClock : AgentClock {
    override fun elapsedMillis(): Long = SystemClock.elapsedRealtime()

    override fun now(): ZonedDateTime = ZonedDateTime.now()
}
```

Create `app/src/main/kotlin/dev/kestrel/app/agent/Permissions.kt`:

```kotlin
package dev.kestrel.app.agent

import android.content.Context
import android.content.pm.PackageManager
import dev.kestrel.agent.port.PermissionEnd

/** Kestrel's own permissions as Android holds them now (design D12 of sub-project 5). */
internal object Permissions {
    fun granted(context: Context, permissions: List<String>): PermissionEnd {
        val held = permissions.count { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        return when (held) {
            permissions.size -> PermissionEnd.GRANTED
            0 -> PermissionEnd.REFUSED
            else -> PermissionEnd.PARTLY
        }
    }
}
```

In `app/src/main/kotlin/dev/kestrel/app/agent/TaskWitness.kt`, replace:

```kotlin
 * What a debug build observes of one task for its hooks (design D28 of sub-project 5; plan 6B-basefix): the texts that
 * Kestrel says with `say` ([said]), the sentence that Kestrel says at the task's end ([ended]), and the task's data tools,
 * which [watch] may wrap. The debug source set installs a witness per task through [AgentRunner.witnesses]; a release
 * build has none, and its tasks get [NONE], which observes nothing, so that only the debug build keeps what Kestrel said.
```

with:

```kotlin
 * What a debug build observes of one round of a goal for its hooks (design D28 of sub-project 5; plan 6B-basefix): the
 * texts that Kestrel says with `say` ([said]), and the round's data tools, which [watch] may wrap. The debug source set
 * installs a witness per round through [AppGoalEnvironment.witnesses]; a release build has none, and its rounds get
 * [NONE], which observes nothing. Kestrel's final words are the conversation's (its command's outcome and record, design
 * 8.1 and 14.4 of sub-project 7), so the witness does not keep them.
```

In `app/src/main/kotlin/dev/kestrel/app/agent/TaskWitness.kt`, replace:

```kotlin
    /** The sentence that Kestrel says at the task's end, on every path: a report, a refusal or a memory request's answer. */
    fun ended(sentence: String)

    /** The data tools that the task uses: [data] itself, or a view of it that observes their results. */
```

with:

```kotlin
    /** The data tools that the round uses: [data] itself, or a view of it that observes their results. */
```

In `app/src/main/kotlin/dev/kestrel/app/agent/TaskWitness.kt`, replace:

```kotlin
        /** The witness of every task of a release build: it observes nothing. */
        val NONE: TaskWitness = object : TaskWitness {
            override fun said(text: String) = Unit

            override fun ended(sentence: String) = Unit
```

with:

```kotlin
        /** The witness of every round of a release build: it observes nothing. */
        val NONE: TaskWitness = object : TaskWitness {
            override fun said(text: String) = Unit
```

In `app/src/main/kotlin/dev/kestrel/app/agent/TaskWitness.kt`, replace:

```kotlin
 * What a task's witness observed: the texts that Kestrel said with `say` ([said]), whether a data tool's result held the
 * run's marker ([markerInData]; null when the task had no marker), and the sentence that Kestrel said at the task's end
 * ([spoken]; null before the end, and for a task that ended without one). toString prints no text.
 */
internal data class TaskEvidence(val said: List<String> = emptyList(), val markerInData: Boolean? = null, val spoken: String? = null) {
    override fun toString(): String =
        "TaskEvidence(said=${said.size} texts, markerInData=$markerInData, spoken=${spoken?.let { "${it.length} characters" } ?: "none"})"
```

with:

```kotlin
 * What a round's witness observed: the texts that Kestrel said with `say` ([said]), and whether a data tool's result held
 * the run's marker ([markerInData]; null when the round had no marker). toString prints no text.
 */
internal data class TaskEvidence(val said: List<String> = emptyList(), val markerInData: Boolean? = null) {
    override fun toString(): String = "TaskEvidence(said=${said.size} texts, markerInData=$markerInData)"
```

In `app/src/main/kotlin/dev/kestrel/app/agent/PermissionActivity.kt`, replace:

```kotlin
         * Asks Android for [permissions] through this activity and returns what Android granted afterwards (the
         * `askAndroid` of AppOwnerPort, which keeps the task handed over meanwhile). Kestrel's accessibility service lets
         * it start the activity from the background.
```

with:

```kotlin
         * Asks Android for [permissions] through this activity and returns what Android granted afterwards (the voice
         * channel's `PermissionDialog`, which keeps the goal handed over meanwhile). Kestrel's accessibility service lets it
         * start the activity from the background.
```

`AgentTexts` loses the sentences that plan 7B's `ConversationTexts` holds now (the memory requests', and the call backstop's) and the bar's text of a replaced task, which the conversation shows itself.

In `app/src/main/kotlin/dev/kestrel/app/agent/AgentTexts.kt`, replace:

```kotlin
        "service off and on."
    const val CALL_STARTED = "I stopped the task because a call started right after my last action."
    const val CANCELLED = "Cancelled."
```

with:

```kotlin
        "service off and on."
    const val CANCELLED = "Cancelled."
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AgentTexts.kt`, replace:

```kotlin
    const val NOTHING_CHANGED = "I didn't change anything."

    // Kestrel's own memory requests (design D2 of sub-project 5).
    const val REMEMBERED = "I'll remember that."
    const val FORGOTTEN = "I've forgotten it."
    const val NOT_SAVED = "I didn't change anything."
    const val NOTHING_TO_SAVE = "There's nothing to save: no task finished in the last 10 minutes."

    fun noFact(key: String): String = "There's no fact named $key."

    /** "I couldn't start the task." or, with a [reason], "I couldn't start the task: <reason>" (D32 item 2). */
```

with:

```kotlin
    const val NOTHING_CHANGED = "I didn't change anything."

    /** "I couldn't start the task." or, with a [reason], "I couldn't start the task: <reason>" (D32 item 2). */
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AgentTexts.kt`, replace:

```kotlin
    }

    /** "Cancelled: <what the task did>", for the bar when a newer task replaced it (D32 item 4). */
    fun replaced(report: TaskReport): String = "Cancelled: ${ledger(report)}"
```

with:

```kotlin
    }
```

- [ ] **Step 10: The app graph and the restore**

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
import dev.kestrel.app.agent.AgentEnvironment
import dev.kestrel.app.agent.AgentRunner
import dev.kestrel.app.agent.AppAgentEnvironment
```

with:

```kotlin
import dev.kestrel.app.agent.AppGoalEnvironment
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
import dev.kestrel.app.agent.SettingsMemoryStore
```

with:

```kotlin
import dev.kestrel.app.agent.Permissions
import dev.kestrel.app.agent.RoundModels
import dev.kestrel.app.agent.SettingsMemoryStore
import dev.kestrel.app.conversation.AndroidOwnerSupport
import dev.kestrel.app.conversation.ConversationSignals
import dev.kestrel.app.conversation.PermissionDialog
import dev.kestrel.app.conversation.storedConversationSettings
import dev.kestrel.app.conversation.VoiceChannel
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.conversation.engine.ConversationEngine
import dev.kestrel.conversation.goal.GoalRunner
import dev.kestrel.conversation.local.LocalRequests
import dev.kestrel.conversation.machine.ConversationEvent
import dev.kestrel.conversation.machine.ConversationState
import dev.kestrel.conversation.machine.MachineStep
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.store.HistorySearch
import dev.kestrel.screen.act.ActionResult
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
import dev.kestrel.app.orchestrator.Mode
```

with:

```kotlin
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.forVoice
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
import kotlinx.coroutines.SupervisorJob
```

with:

```kotlin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
    /** The agent's eyes for look_closer (design D20): NoEyes until plan 5C's block below sets them. AgentRunner reads it at each task. */
```

with:

```kotlin
    /** The agent's eyes for look_closer (design D20): NoEyes until plan 5C's block below sets them. Each round reads it. */
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
    /** What each task needs from Kestrel's parts (design D1 of sub-project 5); built at the first task. */
    private val agentEnvironment: AgentEnvironment by lazy {
        AppAgentEnvironment(
```

with:

```kotlin
    /**
     * What the conversation and a goal's rounds need from Android (design 5.5 of sub-project 7): the confirmation card,
     * the bar, the touch watcher, the other apps' audio, the hand-overs that end by themselves and Android's permission
     * dialog. Kestrel's own pages for the owner open through the screen session, as notification access during its
     * hand-over; the agent's launches go only through the launch check and AppScreenPort.
     */
    private val ownerSupport: AndroidOwnerSupport by lazy {
        AndroidOwnerSupport(
            context = appContext,
            session = { screen.value },
            open = { intent -> screen.value?.actions?.openIntent(intent) is ActionResult.Done },
            calls = { phoneSignals.callStates() },
        )
    }

    /** The backstop against a call that the agent placed (design D13 of sub-project 5): the rounds and the signals share it. */
    private val callBackstop: CallBackstop by lazy { CallBackstop(AndroidCallApi(appContext), SystemClock::elapsedRealtime) }

    private val debuggable: Boolean get() = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** How a round's model is chosen (design D28 of sub-project 5); the debug hooks install a test script here. */
    internal val roundModels: RoundModels by lazy {
        val models = AppModels(
            { providers },
            appliedProviders,
            { profileId -> settings.get(SettingKeys.providerModels(profileId)).models },
            CacheState { settings.get(SettingKeys.AGENT_CACHE_FIELDS) },
            reportFailure,
        )
        RoundModels(debuggable, { settings.get(SettingKeys.AGENT_LIVE_SESSION) }, models::port)
    }

    /** What each round of a goal needs from Kestrel's parts (design 5.5 and 7.1 of sub-project 7); the debug hooks read it. */
    internal val goalEnvironment: AppGoalEnvironment by lazy {
        AppGoalEnvironment(
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
            models = AppModels(
                { providers },
                appliedProviders,
                { profileId -> settings.get(SettingKeys.providerModels(profileId)).models },
                CacheState { settings.get(SettingKeys.AGENT_CACHE_FIELDS) },
                reportFailure,
            ),
            send = { listening.send(it) },
            states = listening.state,
            eyes = { agentEyes },
            showBadges = { session, badges -> showAgentBadges(session, badges) },
            hideBadges = { session -> hideAgentBadges(session) },
            askAndroid = { permissions -> PermissionActivity.ask(context, permissions) },
            knownNames = { contacts.contacts().orEmpty().flatMap { it.names }.toSet() },
```

with:

```kotlin
            models = roundModels,
            watcher = ownerSupport,
            backstop = callBackstop,
            eyes = { agentEyes },
            showBadges = { session, badges -> showAgentBadges(session, badges) },
            hideBadges = { session -> hideAgentBadges(session) },
            knownNames = { contacts.contacts().orEmpty().flatMap { it.names }.toSet() },
            debuggable = debuggable,
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
    /**
     * Runs Kestrel's tasks and memory requests (design D2 of sub-project 5): Listening's [Listening.runTask],
     * [Listening.runMemory] and [Listening.steerTask] go to it. Built at the first task; it reaches Listening only
     * through lambdas, because Listening is built first.
     */
    internal val agent: AgentRunner by lazy {
        AgentRunner(
            scope = scope,
            environment = { agentEnvironment.prepare(it) },
            send = { listening.send(it) },
            latestTask = { listening.state.value.task },
            options = { task -> listening.taskOptions?.invoke(task).orEmpty() },
            debuggable = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            liveSession = { settings.get(SettingKeys.AGENT_LIVE_SESSION) },
            deleteHistory = { logs.deleteHistory() },
            showOnBar = { text -> scope.launch(Dispatchers.Main) { screen.value?.showFeedback(text, false) } },
            backstop = CallBackstop(AndroidCallApi(appContext), SystemClock::elapsedRealtime),
            report = reportFailure,
            nowMillis = System::currentTimeMillis,
        )
    }

    private fun runTasks(listening: Listening) {
        listening.runTask = { task, text -> agent.run(task, text) }
        listening.runMemory = { task, request -> agent.runMemory(task, request) }
        listening.steerTask = { effect -> agent.steer(effect) }
```

with:

```kotlin
    /** The Conversations page's choices as stored (design 13 of sub-project 7): the conversation's restore waits for them. */
    private val storedSettings: Flow<ConversationSettings>
        get() = storedConversationSettings(settings.observe(SettingKeys.CONVERSATIONS), reportFailure)

    /** The same choices as a value, for the search's retention and the follow-up window, which read them at once. */
    private val conversationSettings: StateFlow<ConversationSettings> by lazy {
        storedSettings.stateIn(scope, SharingStarted.Eagerly, ConversationSettings())
    }

    /** The search over past conversations (design 9.5 of sub-project 7), within the owner's retention. */
    private val conversationSearch: HistorySearch by lazy {
        HistorySearch(conversationStore, { conversationSettings.value.keptDays }, AndroidConversationClock)
    }

    private val goalRunner: GoalRunner by lazy {
        GoalRunner(goalEnvironment, screenTurns, conversationSearch, AndroidConversationClock, reportFailure)
    }

    /** The conversation's voice (design 11 of sub-project 7), over the Orchestrator. */
    private val voiceChannel: VoiceChannel by lazy {
        VoiceChannel(listening.voicePath, ownerSupport, ownerSupport, ownerSupport, PermissionDialog { permissions -> PermissionActivity.ask(appContext, permissions) })
    }

    /**
     * Debug builds only: the hooks' watcher of the conversation's steps (design 14.4 of sub-project 7), which CommandTags
     * reads. Null, and so nothing, outside the debug hooks.
     */
    @Volatile
    internal var conversationSteps: ((ConversationEvent, ConversationState, MachineStep) -> Unit)? = null

    /**
     * The conversation (design 5 and 5.6 of sub-project 7): it takes the owner's spoken inputs from Listening, runs goals
     * and Kestrel's own requests, speaks through the voice channel and keeps the archive. KestrelApplication restores it
     * once at the process start; inputs wait for the restore. It reaches Listening only through lambdas and flows, because
     * Listening is built first. Until plan 7F the voice is its only channel.
     */
    internal val conversations: ConversationEngine by lazy {
        ConversationEngine(
            scope = scope,
            store = conversationStore,
            goals = goalRunner,
            local = LocalRequests(
                memory,
                conversationStore,
                conversationSearch,
                goalRunner::saveRoutine,
                AndroidConversationClock,
                reportFailure,
            ),
            channels = mapOf(InputChannel.VOICE to voiceChannel),
            permissions = { permissions -> Permissions.granted(appContext, permissions) },
            settings = storedSettings,
            testMode = settings.observe(SettingKeys.SAFETY).map { it.testMode },
            voiceOn = listening.state.map { it.mode != Mode.OFF }.distinctUntilChanged(),
            clock = AndroidConversationClock,
            report = reportFailure,
            onStep = { event, state, step -> conversationSteps?.invoke(event, state, step) },
        ).also(::followConversation)
    }

    // The voice reads the conversation's view, and the phone's lock and calls reach the conversation while it is open
    // (design 5.5 and 6.5 of sub-project 7).
    private fun followConversation(engine: ConversationEngine) {
        scope.launch { engine.view.forVoice().collect { view -> listening.send(Event.ConversationChanged(view)) } }
        scope.launch { ConversationSignals(phoneSignals, callBackstop, scope, engine::signal, reportFailure).follow(engine.view) }
    }

    // Listening gives the conversation the owner's spoken inputs and its wait for a command after "Yes?".
    private fun converse(listening: Listening) {
        listening.converse = { input -> conversations.submit(input) }
        listening.awaitingCommand = { active -> conversations.awaitingCommand(active) }
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
        ).also(::keepScreenFresh).also(::runTasks)
```

with:

```kotlin
        ).also(::keepScreenFresh).also(::converse)
```

The conversation is restored once, at the process start, before the accessibility service, the assistant service or a screen can give it an input (plan 7B's `restore()`; inputs wait for it).

In `app/src/main/kotlin/dev/kestrel/app/KestrelApplication.kt`, replace:

```kotlin
        graph = AppGraph(this)
```

with:

```kotlin
        graph = AppGraph(this)
        graph.scope.launch { graph.conversations.restore() }
```

- [ ] **Step 11: The debug hooks**

Replace the whole `app/src/debug/kotlin/dev/kestrel/app/debug/CommandTags.kt` with:

```kotlin
package dev.kestrel.app.debug

import dev.kestrel.app.listen.ListeningTexts
import dev.kestrel.app.orchestrator.Effect
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Feedback
import dev.kestrel.app.orchestrator.Reply
import dev.kestrel.app.orchestrator.State
import dev.kestrel.app.orchestrator.Step
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.conversation.machine.ConversationEffect
import dev.kestrel.conversation.machine.ConversationEvent
import dev.kestrel.conversation.machine.ConversationState
import dev.kestrel.conversation.machine.MachineStep
import dev.kestrel.conversation.model.OwnerInput
import java.security.MessageDigest
import java.util.IdentityHashMap

/**
 * The tags of the transcripts that the automatic A71 checks and :tools:eval send (plan 4D, hook H1; design 14.4 of
 * sub-project 7): which command each tagged transcript started, answered or cancelled, or which of the conversation's
 * commands it started, and for each instant command the hashes of what Kestrel said and showed and the route that it took.
 * [onStep] reads every step of the Orchestrator on the Orchestrator's coroutine, and finds a tagged transcript by its
 * identity, so that no other transcript with the same words takes its tag. A tagged transcript that gave the conversation
 * an input gets the hook's options (`model`, `live`, `marker`) on that input ([input]), and [onConversationStep] then
 * notes the number of the conversation's command that the input started: a goal's round, Kestrel's own request or a
 * switch, also when it waited for an earlier one. It also counts the words of the newest goal ([lastGoalWords]) for the
 * A71 session. It keeps the newest [MAX_TAGS] tags and [MAX_COMMANDS] commands, in memory only, and holds tags, numbers,
 * kinds, options and hashes, never words.
 */
internal class CommandTags {
    enum class Role { PENDING, COMMAND, CONVERSE, TASK, ANSWER, CANCEL, NONE }

    /**
     * What a tagged transcript did: its [role] for [command] (0 for none), how Kestrel's Requests read it, and the number
     * of the conversation's command that it started ([task], 0 for none).
     */
    data class Tagged(val role: Role, val command: Int, val read: String, val task: Int = 0)

    /** What a command said and showed, as hashes, oldest first, at most [MAX_TEXTS] each, and its route. */
    data class Record(val said: List<String> = emptyList(), val shown: List<String> = emptyList(), val detail: String? = null)

    private val tags = LinkedHashMap<String, Tagged>()
    private val waiting = IdentityHashMap<Event.Heard, String>()
    private val conversing = IdentityHashMap<Event.Heard, String>()
    private val inputs = LinkedHashMap<Long, String>()
    private val options = HashMap<String, Map<String, String>>()
    private val answered = HashMap<Int, String>()
    private val records = LinkedHashMap<Int, Record>()

    /** How many words the newest goal's command had, or 0 before the first goal (plan 2E's count for the A71 session). */
    @Volatile
    var lastGoalWords: Int = 0
        private set

    /**
     * Links [tag] and the hook's [options] to [event] before Kestrel handles it; false when the tag is among the newest
     * ones already.
     */
    @Synchronized
    fun register(tag: String, event: Event.Heard, read: String, options: Map<String, String> = emptyMap()): Boolean {
        if (tag in tags) return false
        tags[tag] = Tagged(Role.PENDING, 0, read)
        waiting[event] = tag
        if (options.isNotEmpty()) this.options[tag] = options
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

    @Synchronized
    fun tagged(tag: String): Tagged? = tags[tag]

    /** [event] never reaches the Orchestrator, as a match that a hook drops: its tag, if it has one, did nothing. */
    @Synchronized
    fun dropped(event: Event.Heard) {
        val tag = waiting.remove(event) ?: return
        options.remove(tag)
        tags[tag]?.let { tags[tag] = it.copy(role = Role.NONE, command = 0) }
    }

    @Synchronized
    fun record(command: Int): Record = records[command] ?: Record()

    /** Notes what the Orchestrator's [step] after [event], from the state [before], did for the tags and the commands. */
    @Synchronized
    fun onStep(event: Event, before: State, step: Step) {
        val running = if (before.work == Work.RUNNING_COMMAND) before.command else 0
        val started = step.effects.filterIsInstance<Effect.RunCommand>().lastOrNull()
        val heard = event as? Event.Heard
        val conversed = step.effects.filterIsInstance<Effect.Converse>().lastOrNull { it.heard != null && it.heard === heard }
        val tag = heard?.let { waiting.remove(it) }
        val tagged = tag?.let { tags[it] }
        val answer = step.effects.filterIsInstance<Effect.AnswerCommand>().firstOrNull()
        if (tag != null && tagged != null) {
            tags[tag] = when {
                started != null -> tagged.copy(role = Role.COMMAND, command = started.command)
                conversed != null -> tagged.copy(role = Role.CONVERSE, command = 0)
                answer != null -> tagged.copy(role = Role.ANSWER, command = answer.command)
                running > 0 && Effect.CancelCommand in step.effects -> tagged.copy(role = Role.CANCEL, command = running)
                else -> tagged.copy(role = Role.NONE, command = 0)
            }
            // The options wait for the input that Listening makes of the transcript; any other end drops them.
            if (conversed != null) conversing[heard] = tag else options.remove(tag)
        }
        // The newest answer to each command: its tag, or none when it was not tagged, so that an answer that is passed on
        // moves only its own tag.
        if (answer != null && answer.text != null) {
            if (tag != null && tags[tag]?.role == Role.ANSWER) answered[answer.command] = tag else answered.remove(answer.command)
        }
        // A cancelled command ends without CommandEnded, so its entry goes here.
        if (running > 0 && Effect.CancelCommand in step.effects) answered.remove(running)
        if (event is Event.CommandEnded) {
            event.result.detail?.let { detail -> update(event.command) { it.copy(detail = detail) } }
            // An answer that was no answer: the Orchestrator read it as a new utterance, which started a command.
            val passed = answered.remove(event.command)
            if (event.result.passOn != null && started != null && passed != null) {
                tags[passed]?.let { tags[passed] = it.copy(role = Role.COMMAND, command = started.command) }
            }
        }
        var about = when (event) {
            is Event.CommandEnded -> event.command
            is Event.CommandSays -> event.command
            is Event.CommandAsks -> event.command
            is Event.CommandCounts -> event.command
            else -> running
        }
        for (effect in step.effects) {
            when {
                // What comes after a command's start in the same step is that command's.
                effect is Effect.RunCommand -> about = effect.command
                effect is Effect.Say -> when (val reply = effect.reply) {
                    is Reply.Command -> add(reply.command, said = reply.text)
                    is Reply.Text -> if (event is Event.CommandEnded) add(about, said = reply.text)
                    else -> Unit
                }
                effect is Effect.Show -> when (val feedback = effect.feedback) {
                    is Feedback.CommandText -> add(about, shown = feedback.text)
                    Feedback.CommandCancelled -> add(about, shown = ListeningTexts.CANCELLED)
                    else -> Unit
                }
            }
        }
    }

    /**
     * The input that Listening made of [heard] for the conversation, with the options of its tag, if it has one; the
     * conversation's step that starts work for the input then gives its tag the command's number.
     */
    @Synchronized
    fun input(heard: Event.Heard?, input: OwnerInput): OwnerInput {
        val tag = heard?.let { conversing.remove(it) } ?: return input
        inputs[input.id] = tag
        while (inputs.size > MAX_TAGS) inputs.remove(inputs.keys.first())
        return options.remove(tag)?.let { input.copy(options = it) } ?: input
    }

    /**
     * Notes which of the conversation's commands an input of a tagged transcript started (design 14.4 of sub-project 7). An
     * input that started nothing and does not wait for the goal's end, as steering, an answer or a stop, did nothing that
     * the tag counts.
     */
    @Synchronized
    fun onConversationStep(event: ConversationEvent, state: ConversationState, step: MachineStep) {
        val started = mutableSetOf<Long>()
        for (effect in step.effects) {
            val (command, input) = when (effect) {
                is ConversationEffect.StartGoal -> {
                    lastGoalWords = effect.text.split(WHITESPACE).count { it.isNotEmpty() }
                    effect.command to effect.input
                }
                is ConversationEffect.ResumeGoal -> effect.command to effect.input
                is ConversationEffect.RunLocal -> effect.command to effect.input
                is ConversationEffect.SwitchTo -> effect.command to effect.input
                else -> continue
            }
            if (input != null) started += input.id
            val tag = input?.let { inputs.remove(it.id) } ?: continue
            tags[tag]?.let { tags[tag] = it.copy(role = Role.TASK, task = command) }
        }
        val input = (event as? ConversationEvent.Input)?.input ?: return
        if (input.id in started || step.state.waiting.any { it.id == input.id }) return
        val tag = inputs.remove(input.id) ?: return
        tags[tag]?.let { tags[tag] = it.copy(role = Role.NONE) }
    }

    private fun add(command: Int, said: String? = null, shown: String? = null) {
        if (command <= 0) return
        update(command) { record ->
            record.copy(
                said = said?.let { (record.said + hash(it)).takeLast(MAX_TEXTS) } ?: record.said,
                shown = shown?.let { (record.shown + hash(it)).takeLast(MAX_TEXTS) } ?: record.shown,
            )
        }
    }

    private fun update(command: Int, change: (Record) -> Record) {
        records[command] = change(records[command] ?: Record())
        while (records.size > MAX_COMMANDS) records.remove(records.keys.first())
    }

    companion object {
        const val MAX_TAGS = 64
        const val MAX_COMMANDS = 32
        const val MAX_TEXTS = 8

        private val WHITESPACE = Regex("\\s+")

        /** The first 16 hexadecimal characters of the SHA-256 of [text]'s UTF-8 bytes (2026-11-20-a71-checks-4d-interfaces.md, section 1). */
        fun hash(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).take(8)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
```

Create `app/src/debug/kotlin/dev/kestrel/app/debug/StandInRound.kt`:

```kotlin
package dev.kestrel.app.debug

import dev.kestrel.agent.AgentPorts
import dev.kestrel.agent.AgentSettings
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.port.ModelPort
import dev.kestrel.agent.testing.FakeApps
import dev.kestrel.agent.testing.FakeClock
import dev.kestrel.agent.testing.FakeData
import dev.kestrel.agent.testing.FakeEyes
import dev.kestrel.agent.testing.FakeLog
import dev.kestrel.agent.testing.FakeMemory
import dev.kestrel.agent.testing.FakeScreen
import dev.kestrel.agent.testing.ScreenBuilder
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.tools.Tools
import dev.kestrel.app.agent.AgentTexts
import dev.kestrel.conversation.goal.GoalEnvironment
import dev.kestrel.conversation.goal.RoundSetup
import dev.kestrel.conversation.goal.RoundStart
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.client.PricedResponse
import dev.kestrel.providers.profiles.ModelRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow

/**
 * The A71 session's stand-in for the phone's rounds (plan 2E's voice-task; design 14.4 of sub-project 7): every round of
 * a goal runs on fake ports, on a launcher's screen that the round only reads, and its model waits [seconds] and then
 * finishes, citing the screen's "Home". The finished round's final words are [reply] itself, whole and of any length, as
 * the answer to the owner's goal; the model's own summary and a failure's reason have limits, so the reply never goes
 * through them. So the owner can interrupt the reply, stop the goal or say a new command while it waits, with nothing on
 * the phone touched and no provider called. Any other end, such as a stop, keeps Kestrel's own words for it.
 */
internal class StandInRound(private val seconds: Int, private val reply: String) : GoalEnvironment {
    override suspend fun prepare(start: RoundStart): RoundSetup {
        val finishing = ScriptedModel(
            listOf(ScriptedModel.call(Tools.FINISH, null, null, null, "summary" to SUMMARY, "evidence" to listOf(mapOf("target" to mapOf("label" to HOME))))),
        )
        val ports = AgentPorts(
            FakeScreen(ScreenBuilder.screen(LAUNCHER) { text(HOME, top = 100) }),
            FakeEyes(),
            FakeData(),
            start.owner,
            Waiting(finishing, seconds * 1_000L),
            FakeApps(),
            FakeMemory(),
            FakeLog(),
            FakeClock(),
            start.goal,
            start.history,
        )
        return RoundSetup.Ready(ports, AgentSettings(), "task-${start.command}-standin", emptySet(), emptyFlow(), offersRoutines = false) {}
    }

    // A finished round, checked or not, answers with the reply.
    override fun sentence(report: TaskReport): String = when (report.end) {
        EndReason.FINISHED, EndReason.UNVERIFIED -> reply
        else -> AgentTexts.sentence(report, ScriptedModel.INFO.providerName)
    }

    override fun toString(): String = "StandInRound(seconds=$seconds, reply=${reply.length} characters)"

    // The scripted model, which waits before each reply as a slow provider would.
    private class Waiting(private val inner: ModelPort, private val millis: Long) : ModelPort by inner {
        override suspend fun call(role: ModelRole, request: ChatRequest, step: Int): PricedResponse {
            delay(millis)
            return inner.call(role, request, step)
        }
    }

    private companion object {
        const val LAUNCHER = "com.android.launcher3"
        const val HOME = "Home"
        const val SUMMARY = "I read the reply."
    }
}
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHookAnswers.kt`, replace:

```kotlin
import dev.kestrel.agent.port.AnswerKind
import dev.kestrel.app.agent.AgentRunner
import dev.kestrel.app.orchestrator.TaskAsk
import dev.kestrel.commands.text.Normalizer
```

with:

```kotlin
import dev.kestrel.agent.TaskReport
import dev.kestrel.app.agent.TaskEvidence
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.conversation.channel.QuestionKind
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHookAnswers.kt`, replace:

```kotlin
    /**
     * agent-task -Result's answer for a task that [ended]: its report's end and counts; `marker=1` when the sentence that
```

with:

```kotlin
    /**
     * How the conversation's command ended, for agent-task -Result (design 14.4 of sub-project 7): its report, Kestrel's
     * final words ([spoken], which the command's outcome and record keep), the owner's touches that the watcher reported
     * during its round ([touches]) and the signals without a finger among them ([signals]), and what the round's witness
     * observed ([evidence]; empty in a release build and for Kestrel's own requests).
     */
    data class Ended(
        val report: TaskReport,
        val spoken: String?,
        val touches: Int = 0,
        val signals: Int = 0,
        val evidence: TaskEvidence = TaskEvidence(),
    ) {
        override fun toString(): String =
            "Ended(report=$report, spoken=${spoken?.length}, touches=$touches, signals=$signals, evidence=$evidence)"
    }

    /**
     * agent-task -Result's answer for a task that [ended]: its report's end and counts; `marker=1` when the sentence that
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHookAnswers.kt`, replace:

```kotlin
    fun result(ended: AgentRunner.Ended, marker: String?, recipient: String?, digits: String? = null): String {
        val report = ended.report
        val said = ended.evidence.said
        val texts = said + listOfNotNull(ended.evidence.spoken)
```

with:

```kotlin
    fun result(ended: Ended, marker: String?, recipient: String?, digits: String? = null): String {
        val report = ended.report
        val said = ended.evidence.said
        val texts = said + listOfNotNull(ended.spoken)
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHookAnswers.kt`, replace:

```kotlin
     * agent-state's `question=`: the kind that the agent asked the open question with, `yes-no`, `free` (the model's
     * `ask_user`, or a routine's name), `pick-number` (look_closer's "Which number should I use?"), `screenshot`
     * (look_closer's screenshot question, which the Orchestrator asks as a free question) or `limit` (a round's "Continue?"
     * at a limit, which the Orchestrator asks as a yes-or-no question; plan 7A), so that :tools:eval answers each as the
     * owner decided (plan 6B-basefix).
     */
    fun question(kind: AnswerKind): String = kind.name.lowercase().replace('_', '-')
```

with:

```kotlin
     * agent-state's `asking=`: the open question's kind in sub-project 5's words, which :tools:eval reads (design 14.4 of
     * sub-project 7): `yes-no` (also a limit's "Continue?", the switch to a past conversation and "Delete the history?"),
     * `confirm`, `free` (also
     * look_closer's screenshot question), `pick-number` and `continue` ("Continue the goal?").
     */
    fun asking(kind: QuestionKind): String = when (kind) {
        QuestionKind.YES_NO, QuestionKind.LIMIT, QuestionKind.SWITCH, QuestionKind.DELETE_HISTORY -> "yes-no"
        QuestionKind.CONFIRM -> "confirm"
        QuestionKind.FREE, QuestionKind.SCREENSHOT -> "free"
        QuestionKind.PICK -> "pick-number"
        QuestionKind.CONTINUE_GOAL -> "continue"
    }

    /**
     * agent-state's `question=`: the kind that the agent asked the open question with, `yes-no`, `free` (the model's
     * `ask_user`, or a routine's name), `pick-number` (look_closer's "Which number should I use?"), `screenshot`
     * (look_closer's screenshot question, which the voice asks as a free question) or `limit` (a round's "Continue?"), so
     * that :tools:eval answers each as the owner decided (plan 6B-basefix); `none` for a confirmation and the
     * conversation's own questions.
     */
    fun question(kind: QuestionKind): String = when (kind) {
        QuestionKind.YES_NO -> "yes-no"
        QuestionKind.FREE -> "free"
        QuestionKind.PICK -> "pick-number"
        QuestionKind.SCREENSHOT -> "screenshot"
        QuestionKind.LIMIT -> "limit"
        QuestionKind.CONFIRM, QuestionKind.CONTINUE_GOAL, QuestionKind.SWITCH, QuestionKind.DELETE_HISTORY -> "none"
    }
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHookAnswers.kt`, replace:

```kotlin
    fun answerRefusal(asking: TaskAsk?, words: String, open: Category?, expected: String?): String? = when {
        expected != null && (asking != TaskAsk.CONFIRM || open?.wire != expected) -> "the task asks no confirmation of $expected now"
        expected == null && asking == TaskAsk.CONFIRM && words !in DECLINING -> "add --es confirming with the open confirmation's category"
```

with:

```kotlin
    fun answerRefusal(asking: QuestionKind?, words: String, open: Category?, expected: String?): String? = when {
        expected != null && (asking != QuestionKind.CONFIRM || open?.wire != expected) -> "the task asks no confirmation of $expected now"
        expected == null && asking == QuestionKind.CONFIRM && words !in DECLINING -> "add --es confirming with the open confirmation's category"
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, replace:

```kotlin
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.TaskAsk
import dev.kestrel.app.orchestrator.TaskPhase
```

with:

```kotlin
import dev.kestrel.app.agent.RoundEvidences
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.GoalPhase
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, replace:

```kotlin
 * a reply, a name or a tool's arguments; [AgentHookAnswers] builds the lines.
```

with:

```kotlin
 * a reply, a name or a tool's arguments; [AgentHookAnswers] builds the lines. A tag names the conversation's command that
 * its transcript started (plan 7D): a goal's round or Kestrel's own request, whose outcome the engine keeps.
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, replace:

```kotlin
        val runner = graph.agent
        val ended = runner.ended(number)
        if (ended == null || runner.running(number)) return "state=running"
```

with:

```kotlin
        val outcome = graph.conversations.outcome(number) ?: return "state=running"
        val round = graph.goalEnvironment.evidences.of(number) ?: RoundEvidences.Evidence()
        val ended = AgentHookAnswers.Ended(outcome.report, outcome.answer, round.touches, round.signals, round.witness)
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, replace:

```kotlin
        val state = graph.listening.state.value
        val asking = state.taskAsking.takeIf { state.task == number }
        val handedOver = state.task == number && state.taskPhase == TaskPhase.HANDED_OVER
        // A script never answers another task's question (design D28; review C2-I3).
        if (asking == null && !handedOver) throw HookError("the task of tag $tag asks nothing")
        val confirming = graph.agent.confirming(number)?.takeIf { asking == TaskAsk.CONFIRM }
```

with:

```kotlin
        val view = graph.conversations.view.value
        val asking = view.asking.takeIf { view.command == number }
        val handedOver = view.command == number && view.goal == GoalPhase.HANDED_OVER
        // A script never answers another task's question (design D28; review C2-I3).
        if (asking == null && !handedOver) throw HookError("the task of tag $tag asks nothing")
        val confirming = graph.conversations.owner.confirming?.takeIf { asking == QuestionKind.CONFIRM }
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, replace:

```kotlin
        return "answered tag=$tag asking=${asking?.let(::kind) ?: "hand-over"}"
```

with:

```kotlin
        return "answered tag=$tag asking=${asking?.let(AgentHookAnswers::asking) ?: "hand-over"}"
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, replace:

```kotlin
            graph.agent.modelOverride = null
```

with:

```kotlin
            graph.roundModels.modelOverride = null
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, replace:

```kotlin
        graph.agent.modelOverride = model
```

with:

```kotlin
        graph.roundModels.modelOverride = model
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, replace:

```kotlin
        val current = state.task == number && state.taskPhase != null && graph.agent.running(number)
        val phase = if (!current) "ended" else when (state.taskPhase) {
            TaskPhase.HANDED_OVER -> "handed-over"
            TaskPhase.PAUSED -> "paused"
            else -> "running"
        }
        val asking = state.taskAsking?.takeIf { current }?.let(::kind) ?: "none"
        val handOver = graph.agent.handOver(number)?.takeIf { current }?.name ?: "none"
        // The category of the open confirmation, so that :tools:eval confirms only the kind that its task names (design D28).
        val confirming = graph.agent.confirming(number)?.takeIf { current }?.wire ?: "none"
        // The open question's source, so that :tools:eval answers look_closer's screenshot question apart (plan 6B-basefix).
        val question = graph.agent.asking(number)?.takeIf { current }?.let(AgentHookAnswers::question) ?: "none"
```

with:

```kotlin
        val view = graph.conversations.view.value
        val owner = graph.conversations.owner
        // The tag's command is the goal's current round, or the conversation's own request that asks now.
        val current = view.command == number && (view.goal != null || view.asking != null)
        val phase = if (!current) "ended" else phaseOf(view)
        val asking = view.asking?.takeIf { current }?.let(AgentHookAnswers::asking) ?: "none"
        val handOver = owner.handOverKind?.takeIf { current }?.name ?: "none"
        // The category of the open confirmation, so that :tools:eval confirms only the kind that its task names (design D28).
        val confirming = owner.confirming?.takeIf { current }?.wire ?: "none"
        // The open question's source, so that :tools:eval answers look_closer's screenshot question apart (plan 6B-basefix).
        val question = owner.asking?.takeIf { current }?.let(AgentHookAnswers::question) ?: "none"
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, replace:

```kotlin
        aliases?.let { graph.agent.selfChatAliases = it }
        return "ownerEmails=${safety.ownerEmails.size} viberSelfChatName=${if (safety.viberSelfChatName != VIBER_DEFAULT) "set" else "unset"} " +
            "whatsappOwnName=${if (safety.whatsappOwnName != null) "set" else "unset"} selfChatAliases=${graph.agent.selfChatAliases.size}"
```

with:

```kotlin
        aliases?.let { graph.goalEnvironment.selfChatAliases = it }
        return "ownerEmails=${safety.ownerEmails.size} viberSelfChatName=${if (safety.viberSelfChatName != VIBER_DEFAULT) "set" else "unset"} " +
            "whatsappOwnName=${if (safety.whatsappOwnName != null) "set" else "unset"} selfChatAliases=${graph.goalEnvironment.selfChatAliases.size}"
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, replace:

```kotlin
        val aliases = graph.agent.selfChatAliases
```

with:

```kotlin
        val aliases = graph.goalEnvironment.selfChatAliases
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, replace:

```kotlin
    private fun kind(ask: TaskAsk): String = ask.name.lowercase().replace('_', '-')
```

with:

```kotlin
    // A goal's phase in agent-state's words; the conversation's own question has no goal and counts as running.
    private fun phaseOf(view: ConversationView): String = when (view.goal) {
        GoalPhase.HANDED_OVER -> "handed-over"
        GoalPhase.PAUSED -> "paused"
        GoalPhase.PAUSED_AT_LIMIT -> "paused-at-limit"
        GoalPhase.INTERRUPTED -> "interrupted"
        GoalPhase.ACTING, GoalPhase.ASKING, null -> "running"
    }
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt`, replace:

```kotlin
        val taskNow = tagged.task > 0 && tagged.task == state.task && state.work == Work.RUNNING_TASK
        val phase = when {
            tagged.role == CommandTags.Role.PENDING -> "pending"
```

with:

```kotlin
        // The conversation's command runs until it has its outcome (design 14.4 of sub-project 7).
        val taskNow = tagged.task > 0 && graph.conversations.outcome(tagged.task) == null
        val phase = when {
            // A transcript that the conversation has not yet taken waits, as one that the Orchestrator has not read.
            tagged.role == CommandTags.Role.PENDING || tagged.role == CommandTags.Role.CONVERSE -> "pending"
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt`, replace:

```kotlin
        val tags: CommandTags by lazy { CommandTags() }

        /** The options of the tasks that tagged transcripts started, for sub-project 5's AgentRunner (Listening.taskOptions). */
        val tasks: TaskOptions by lazy { TaskOptions() }

        const val YES_CALL_REFUSED = "only the emulator may answer \"yes, call\""
```

with:

```kotlin
        val tags: CommandTags by lazy { CommandTags() }


        const val YES_CALL_REFUSED = "only the emulator may answer \"yes, call\""
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt`, replace:

```kotlin
         * Links [tag] and [options] to [event] before Kestrel handles it, and starts the record of the Orchestrator's steps
         * (plan 4D, H1): a task that a tagged transcript starts gets its options in [tasks] before it runs, and
         * Listening.taskOptions reads them from there.
         */
        fun tag(graph: AppGraph, tag: String, event: Event.Heard, options: Map<String, String> = emptyMap()) {
            if (!TAG.matches(tag)) throw HookError("add --es tag with 1 to 32 letters, digits, '-' and '_'")
            val listening = graph.listening
            if (listening.steps == null) {
                listening.steps = { heard, before, step -> tags.onStep(heard, before, step)?.let { tasks.put(it.task, it.options) } }
                listening.taskOptions = tasks::take
                // Each task's witness keeps what it said and watches its data tools for the run's marker (plan 6B-basefix).
                graph.agent.witnesses = { options -> DebugTaskWitness.open(options) }
            }
```

with:

```kotlin
         * Links [tag] and [options] to [event] before Kestrel handles it, and starts the record of the steps ([install]):
         * the input that a tagged transcript gives the conversation carries its options, and the round that it starts reads
         * them.
         */
        fun tag(graph: AppGraph, tag: String, event: Event.Heard, options: Map<String, String> = emptyMap()) {
            if (!TAG.matches(tag)) throw HookError("add --es tag with 1 to 32 letters, digits, '-' and '_'")
            install(graph)
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt`, replace:

```kotlin
         * The options that a tagged transcript passes to the task that it starts, from the hook's extras: --es model and
         * --ez live, which sub-project 5's AgentRunner reads (its design's "Shared files and anchors", item 2), and --es
         * marker, a run's marker, which the task's [DebugTaskWitness] watches for (plan 6B-basefix).
```

with:

```kotlin
         * Starts the record of the Orchestrator's and the conversation's steps (plan 4D, H1; design 14.4 of sub-project 7),
         * once per process: a tagged transcript's input gets its options, and each round's witness keeps what it said and
         * watches its data tools for the run's marker (plan 6B-basefix).
         */
        @Synchronized
        fun install(graph: AppGraph) {
            val listening = graph.listening
            if (listening.steps != null) return
            listening.inputs = tags::input
            graph.conversationSteps = tags::onConversationStep
            graph.goalEnvironment.witnesses = { options -> DebugTaskWitness.open(options) }
            listening.steps = tags::onStep
        }

        /**
         * The options that a tagged transcript passes to the round that it starts, from the hook's extras: --es model and
         * --ez live, which RoundModels reads (sub-project 5's design, "Shared files and anchors", item 2), and --es marker,
         * a run's marker, which the round's [DebugTaskWitness] watches for (plan 6B-basefix).
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt`, replace:

```kotlin
import dev.kestrel.app.listen.StopReason
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Work
```

with:

```kotlin
import dev.kestrel.app.listen.StopReason
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt`, replace:

```kotlin
 * of Kestrel's latest wait for a task, which rises with each wait and also when a wait starts again, how many tasks
 * Kestrel has started in its process, how many words the last slow task had, and how many keyword hits its voice sessions
 * reported, so that the session counts Kestrel's reactions and the spotter's hits, also false ones, without reading what
 * Kestrel heard. VOICE_TASK makes each
 * task take some seconds and then say a given reply, in place of the task that ends at once, so that the owner can
 * interrupt a long reply or say "stop" while a task runs; a task that Kestrel cancelled meanwhile ends without its reply.
```

with:

```kotlin
 * of Kestrel's latest wait for a task, which rises with each wait and also when a wait starts again, the newest command
 * of the conversation in its process, how many words the newest goal had since the first voice-task or tagged transcript,
 * and how many keyword hits its voice sessions reported, so that the session counts Kestrel's reactions and the spotter's
 * hits, also false ones, without reading what Kestrel heard. VOICE_TASK makes each goal's round a stand-in
 * ([StandInRound]) that takes some seconds and then answers with a given reply, whole, as its final words, in place of a
 * round that ends at once without a script, so that the owner can interrupt a long reply or say "stop" while a goal
 * works; a goal that the owner stopped meanwhile ends with the report of a stop, as any goal does.
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt`, replace:

```kotlin
            "waits=${it.wait} tasks=${it.task} lastTaskWords=$lastTaskWords hits=${graph.listening.keywordHits}" +
                if (counts.isEmpty()) "" else " $counts"
```

with:

```kotlin
            "waits=${it.wait} tasks=${graph.conversations.view.value.command} lastTaskWords=${VoiceHooks.tags.lastGoalWords} " +
                "hits=${graph.listening.keywordHits}" + if (counts.isEmpty()) "" else " $counts"
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt`, replace:

```kotlin
        val listening = graph.listening
        synchronized(lock) {
            if (replacedTask == null) replacedTask = listening.runTask
            listening.runTask = { task, text ->
                // A count, never the words, so that the session can tell a dictated task that was split.
                lastTaskWords = text.split(Regex("\\s+")).count { it.isNotEmpty() }
                graph.scope.launch {
                    delay(seconds * 1_000L)
                    // A task that Kestrel cancelled meanwhile ends in silence, so that the next try starts quiet.
                    val state = listening.state.value
                    val running = state.task == task && state.work == Work.RUNNING_TASK
                    listening.send(Event.TaskEnded(task, reply.takeIf { running }))
                }
            }
        }
        return "each task now takes $seconds s and then says a reply of ${reply.length} characters, until voice-task -Clear " +
            "or the end of Kestrel's process"
    }

    private fun clearTask(): String {
        synchronized(lock) {
            replacedTask?.let { graph.listening.runTask = it }
            replacedTask = null
        }
        return "each task ends at once again"
```

with:

```kotlin
        // The goal's words are counted as a count, never kept, so that the session can tell a dictated goal that was split.
        VoiceHooks.install(graph)
        graph.goalEnvironment.standIn = StandInRound(seconds, reply)
        return "each goal's round now takes $seconds s and then answers with a reply of ${reply.length} characters, until " +
            "voice-task -Clear or the end of Kestrel's process"
    }

    private fun clearTask(): String {
        graph.goalEnvironment.standIn = null
        return "each goal's round runs on the phone again"
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/SessionHooks.kt`, replace:

```kotlin
        // The task that VOICE_TASK replaced, which VOICE_TASK --ez clear true puts back.
        private var replacedTask: ((Int, String) -> Unit)? = null
        private var echo: Echo? = null

        @Volatile
        private var lastTaskWords = 0
```

with:

```kotlin
        private var echo: Echo? = null
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/DebugTaskWitness.kt`, replace:

```kotlin
 * The debug build's witness of one task (plan 6B-basefix): it keeps the newest [MAX_SAID] texts that Kestrel said with
 * `say` and the sentence of the task's end, for agent-task's marker and digits, and, when the task's options carry a
 * run's [marker] (`voice -Send heard --es marker`), it watches the task's data tools for it ([MarkerWatch]). VoiceHooks
 * installs [open] as AgentRunner.witnesses beside Listening.taskOptions. The texts stay in memory, with the newest tasks'
 * ends that AgentRunner keeps.
 */
internal class DebugTaskWitness(private val marker: String?) : TaskWitness {
    private val texts = ArrayDeque<String>()

    @Volatile
    private var end: String? = null
```

with:

```kotlin
 * The debug build's witness of one round of a goal (plan 6B-basefix): it keeps the newest [MAX_SAID] texts that Kestrel
 * said with `say`, for agent-task's marker and digits, and, when the round's options carry a run's [marker] (`voice -Send
 * heard --es marker`), it watches the round's data tools for it ([MarkerWatch]). VoiceHooks installs [open] as
 * AppGoalEnvironment.witnesses beside Listening.inputs. The texts stay in memory, with the newest rounds' evidence that
 * AppGoalEnvironment keeps.
 */
internal class DebugTaskWitness(private val marker: String?) : TaskWitness {
    private val texts = ArrayDeque<String>()
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/DebugTaskWitness.kt`, replace:

```kotlin
    override fun ended(sentence: String) {
        end = sentence
    }

    override fun watch(data: DataPort): DataPort = marker?.let { MarkerWatch(data, it).also { watch -> watched = watch } } ?: data

    override val evidence: TaskEvidence
        get() = TaskEvidence(synchronized(texts) { texts.toList() }, marker?.let { watched?.seen ?: false }, end)
```

with:

```kotlin
    override fun watch(data: DataPort): DataPort = marker?.let { MarkerWatch(data, it).also { watch -> watched = watch } } ?: data

    override val evidence: TaskEvidence
        get() = TaskEvidence(synchronized(texts) { texts.toList() }, marker?.let { watched?.seen ?: false })
```

The suite's refusals:

In `tools/eval/src/main/kotlin/dev/kestrel/eval/run/Refusals.kt`, replace:

```kotlin
 * Kestrel records every such task with the end NO_PROFILE, whatever the reason, so the kind comes from the sentence that
 * it spoke, which agent-task compares on the phone with words of each refusal's sentence; the tool never reads it.
 */
object Refusals {
```

with:

```kotlin
 * From plan 7D on, a goal's round that could not start ends as NO_SCREEN when Kestrel's accessibility service is off or
 * the touch watcher cannot start, and as NO_PROFILE without a profile or in a test build without a script ([ENDS]). The
 * kind comes from the sentence that Kestrel spoke, which agent-task compares on the phone with words of each refusal's
 * sentence; the tool never reads it. A stop before the round started ends as CANCELLED and a failed start as
 * KESTREL_ERROR, which fail a run by their end, so they are no refusal kinds any more.
 */
object Refusals {
    /** The ends of a goal's round that could not start. */
    val ENDS: Set<String> = setOf("NO_PROFILE", "NO_SCREEN")

```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/run/Refusals.kt`, replace:

```kotlin
        "no-profile" to "no provider profile is set up",
        "cancelled" to "cancelled",
        "could-not-start" to "start the task",
```

with:

```kotlin
        "no-profile" to "no provider profile is set up",
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/run/Runner.kt`, replace:

```kotlin
            if (answer["end"] == "NO_PROFILE") state.refusal = Refusals.kind(hooks, tag)
```

with:

```kotlin
            if (answer["end"] in Refusals.ENDS) state.refusal = Refusals.kind(hooks, tag)
```

`voice-task` now makes each goal's round a stand-in that finishes and answers with the reply, so Kestrel's final words are the reply itself, whole and of any length (review of Task 3: I1). No script and no part of `:tools:eval` reads the reply's words: `scripts\a71-session.ps1` uses the reply only as a long text that the owner interrupts, and reads Kestrel's state and times. Only the descriptions change.

In `scripts/hook.ps1`, replace:

```powershell
#   voice-task -Seconds n -Reply 'text'     makes each task take n seconds, at most 120, and then say the reply, unless
#                                           Kestrel cancelled it, until voice-task -Clear or the end of Kestrel's process
#   voice-task -Clear                       each task ends at once again, as in sub-project 2
```

with:

```powershell
#   voice-task -Seconds n -Reply 'text'     makes each goal's round take n seconds, at most 120, and then answer with
#                                           the reply, whole and of any length, which Kestrel says as the goal's final
#                                           words, unless the owner stopped the goal, until voice-task -Clear or the end
#                                           of Kestrel's process
#   voice-task -Clear                       each goal's round runs on the phone again
```

In `scripts/a71-session.ps1`, replace:

```powershell
    # Each task now takes a second and then reads a long reply without any of Kestrel's keywords, which the owner
    # interrupts. A pause shorter than the speech detector's 0.7 s joins the request to Kestrel's own voice, which the
    # pipeline drops after an interruption (analysis, item 8), so the pauses start at 1 s.
```

with:

```powershell
    # Each goal's round now takes a second and then answers with a long reply without any of Kestrel's keywords, which
    # Kestrel reads whole as the goal's final words and which the owner interrupts. A pause shorter than the speech
    # detector's 0.7 s joins the request to Kestrel's own voice, which the pipeline drops after an interruption
    # (analysis, item 8), so the pauses start at 1 s.
```

`scripts\lib\AgentChecks.ps1`'s answer to the question of Kestrel's own request after the remember phrase waits for that question through `agent-state -Tag`, because `voice-state`'s `work=running_task` no longer exists (review of Task 3: M2):

In `scripts/lib/AgentChecks.ps1`, replace:

```powershell
# Answers the question of a task of Kestrel's own, as "remember" runs (task-5c-7-review M2). CommandTags links a tag only
# to an agent's task, so agent-state -Tag cannot see this one; Kestrel says the question while the task runs, and an
# answer that comes before it is dropped. So this waits up to $Seconds until Kestrel has said the question (voice-state:
# speaking, then no longer speaking), sends $Text as a transcript, and sends it again, at most twice more, while the task
# still runs.
function Send-OwnTaskAnswer([string]$Text, [double]$Seconds = 30) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt $Seconds) {
        $state = try { ConvertFrom-VoiceState (Invoke-Hook voice-state) } catch { $null }
        if ($state -and $state['work'] -ne 'running_task') { break }
        if ($state -and $state['speaking'] -eq 'true') {
            Wait-NotSpeaking 15 | Out-Null
            break
        }
        Start-Sleep -Milliseconds 250
    }
    foreach ($try in 1..3) {
        Invoke-Hook voice @{ Send = 'heard'; Text = $Text; Tag = (New-CheckTag) } | Out-Null
        Start-Sleep -Seconds 3
        $state = try { ConvertFrom-VoiceState (Invoke-Hook voice-state) } catch { $null }
        if (-not $state -or $state['work'] -ne 'running_task') { return }
    }
}

# Runs one speech check with Invoke-AutoCheck and its clip ($Clip: Voice, Text, Path), which $Play plays. An answer
# check first opens the fixture, installs its script and starts its task by text, and waits until the task asks
# ($Check.Asking); a task check installs its script before the clip. Afterwards it ends the check's task when it still
# runs, the answer check's or the task check's own, and sends the phrase's $Then, for "remember" only after Kestrel has
# asked its question. Returns Invoke-AutoCheck's result, whose Line goes into the results.
function Invoke-AgentSpeechCheck($Check, $Clip, [scriptblock]$Play) {
    $setup = @{ Tag = '' }
    $before = {
        if ($Check.Script) { Install-AgentScript -Name 'speech-check.json' -Replies (Get-SpeechScript $Check.Script) }
        if ($Check.Kind -ne 'answer') { return $null }
        Invoke-SettingsCommand -Serial $Serial -Command "am start -n $(Get-SpeechFixture)" | Out-Null
        Start-Sleep -Seconds 2
        $setup.Tag = New-CheckTag
        Invoke-Hook voice @{ Send = 'heard'; Text = 'Kestrel, tap Rebuild on the views fixture.'; Named = $true; Tag = $setup.Tag } | Out-Null
        $asking = $Check.Asking
        $state = Wait-AgentState -Tag $setup.Tag -Seconds 60 -Until {
            param($State)
            if ($asking -eq 'running') { $State['phase'] -eq 'running' -and $State['asking'] -eq 'none' } else { $State['asking'] -eq $asking }
        }
        $ready = if ($asking -eq 'running') { $state -and $state['phase'] -eq 'running' } else { $state -and $state['asking'] -eq $asking }
        if (-not $ready) { throw "the check's task did not reach '$asking' within 60 s" }
        $setup.Tag
    }
    $wait = {
        param($Tag, $Seconds)
        if ($Check.Kind -eq 'task') {
            # The finally below ends the task of the clip's transcript when it still runs, as after a question (task-5c-7-review M1).
            $setup.Tag = $Tag
            Wait-AgentTask -Tag $Tag -Seconds ([math]::Min($Seconds, 60))
        }
        elseif ($Check.Kind -eq 'answer') { Wait-AgentTask -Tag $setup.Tag -Seconds ([math]::Min($Seconds, 60)) }
        else { $null }
    }
    # The runner gives the tagged state, the context of -Before and what -Wait returned; the verdict needs the first and the last.
    $verify = { param($State) Get-AgentSpeechVerdict -Check $Check -State $State -Result $args[1] }
    try {
        Invoke-AutoCheck -Name $Check.Name -Text '' -Clip $Clip -Play $Play -Before $before -Wait $wait -WaitSeconds 60 -Verify $verify
    } finally {
        if ($setup.Tag) { Stop-SpeechTask $setup.Tag }
        if ($Check.Then -and $Check.Read -eq 'remember') { Send-OwnTaskAnswer $Check.Then }
        elseif ($Check.Then) { Invoke-Hook voice @{ Send = 'heard'; Text = $Check.Then; Tag = (New-CheckTag) } | Out-Null }
    }
}
```

with:

```powershell
# Answers the question of Kestrel's own request that the phrase's transcript with tag $Tag started, as "remember" asks
# one (task-5c-7-review M2). The conversation asks it once the request runs, and an answer that comes before it is
# dropped; CommandTags links the tag to the request, so agent-state -Tag shows the question while it is open (plan 7D).
# So this waits up to $Seconds until the request asks or has ended (agent-task -Result -Tag), lets Kestrel finish saying
# the question, sends $Text as a transcript, and sends it again, at most twice more, while the question is still open.
function Send-OwnTaskAnswer([string]$Tag, [string]$Text, [double]$Seconds = 30) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt $Seconds) {
        $state = try { ConvertFrom-HookPairs (Invoke-Hook agent-state @{ Tag = $Tag }) } catch { $null }
        if ($state -and $state['asking'] -ne 'none') {
            Wait-NotSpeaking 15 | Out-Null
            break
        }
        $result = try { ConvertFrom-AgentTaskResult (Invoke-Hook agent-task @{ Result = $true; Tag = $Tag }) } catch { $null }
        if ($result -and $result['state'] -eq 'done') { break }
        Start-Sleep -Milliseconds 250
    }
    foreach ($try in 1..3) {
        Invoke-Hook voice @{ Send = 'heard'; Text = $Text; Tag = (New-CheckTag) } | Out-Null
        Start-Sleep -Seconds 3
        $state = try { ConvertFrom-HookPairs (Invoke-Hook agent-state @{ Tag = $Tag }) } catch { $null }
        if (-not $state -or $state['asking'] -eq 'none') { return }
    }
}

# Runs one speech check with Invoke-AutoCheck and its clip ($Clip: Voice, Text, Path), which $Play plays. An answer
# check first opens the fixture, installs its script and starts its task by text, and waits until the task asks
# ($Check.Asking); a task check installs its script before the clip. Afterwards it ends the check's task when it still
# runs, the answer check's or the task check's own, and sends the phrase's $Then, for "remember" only after Kestrel has
# asked its question. Returns Invoke-AutoCheck's result, whose Line goes into the results.
function Invoke-AgentSpeechCheck($Check, $Clip, [scriptblock]$Play) {
    $setup = @{ Tag = ''; Phrase = '' }
    $before = {
        if ($Check.Script) { Install-AgentScript -Name 'speech-check.json' -Replies (Get-SpeechScript $Check.Script) }
        if ($Check.Kind -ne 'answer') { return $null }
        Invoke-SettingsCommand -Serial $Serial -Command "am start -n $(Get-SpeechFixture)" | Out-Null
        Start-Sleep -Seconds 2
        $setup.Tag = New-CheckTag
        Invoke-Hook voice @{ Send = 'heard'; Text = 'Kestrel, tap Rebuild on the views fixture.'; Named = $true; Tag = $setup.Tag } | Out-Null
        $asking = $Check.Asking
        $state = Wait-AgentState -Tag $setup.Tag -Seconds 60 -Until {
            param($State)
            if ($asking -eq 'running') { $State['phase'] -eq 'running' -and $State['asking'] -eq 'none' } else { $State['asking'] -eq $asking }
        }
        $ready = if ($asking -eq 'running') { $state -and $state['phase'] -eq 'running' } else { $state -and $state['asking'] -eq $asking }
        if (-not $ready) { throw "the check's task did not reach '$asking' within 60 s" }
        $setup.Tag
    }
    $wait = {
        param($Tag, $Seconds)
        if ($Check.Kind -eq 'task') {
            # The finally below ends the task of the clip's transcript when it still runs, as after a question (task-5c-7-review M1).
            $setup.Tag = $Tag
            Wait-AgentTask -Tag $Tag -Seconds ([math]::Min($Seconds, 60))
        }
        elseif ($Check.Kind -eq 'answer') { Wait-AgentTask -Tag $setup.Tag -Seconds ([math]::Min($Seconds, 60)) }
        else {
            # Kestrel's own request that the phrase starts, whose question $Check.Then answers.
            $setup.Phrase = $Tag
            $null
        }
    }
    # The runner gives the tagged state, the context of -Before and what -Wait returned; the verdict needs the first and the last.
    $verify = { param($State) Get-AgentSpeechVerdict -Check $Check -State $State -Result $args[1] }
    try {
        Invoke-AutoCheck -Name $Check.Name -Text '' -Clip $Clip -Play $Play -Before $before -Wait $wait -WaitSeconds 60 -Verify $verify
    } finally {
        if ($setup.Tag) { Stop-SpeechTask $setup.Tag }
        if ($Check.Then -and $Check.Read -eq 'remember' -and $setup.Phrase) { Send-OwnTaskAnswer -Tag $setup.Phrase -Text $Check.Then }
        elseif ($Check.Then) { Invoke-Hook voice @{ Send = 'heard'; Text = $Check.Then; Tag = (New-CheckTag) } | Out-Null }
    }
}
```

- [ ] **Step 12: Check that nothing of the task path is left**

Run: `git grep -n -E 'TaskPhase|TaskAsk\b|PauseCause|TaskEnded|RunTask|CancelTask|RUNNING_TASK|taskPhase|taskAsking|runTask|steerTask|AgentRunner|AppOwnerPort|AppAgentEnvironment|TaskAnswers|TaskOptions\b|graph\.agent\b|TASK_FAILED' -- app/src`
Expected: no output.

- [ ] **Step 13: Run the new and changed tests to see them pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.orchestrator.* --tests=dev.kestrel.app.conversation.* --tests=dev.kestrel.app.agent.* --tests=dev.kestrel.app.listen.ListeningTest --tests=dev.kestrel.app.phone.PhoneSignalsTest --tests=dev.kestrel.app.debug.CommandTagsTest --tests=dev.kestrel.app.debug.AgentHookAnswersTest --tests=dev.kestrel.app.debug.HookRulesTest --tests=dev.kestrel.app.debug.StandInRoundTest`, then `scripts\gradle.ps1 :tools:eval:test --tests=dev.kestrel.eval.run.RunnerTest`, then `scripts\gradle.ps1 :core:conversation:test --tests=*ConversationMachineTest --tests=*ConversationEngineTest`
Expected: PASS.

- [ ] **Step 14: Run every module that this task touches, compile the device tests, count them and stop Gradle**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest :core:conversation:test :core:agent:test :tools:eval:test :app:compileDebugAndroidTestKotlin :app:lintDebug`, then `foreach ($dir in 'app\build\test-results\testDebugUnitTest', 'tools\eval\build\test-results\test') { $n = 0; Get-ChildItem $dir -Filter *.xml | ForEach-Object { $n += [int]([xml](Get-Content $_.FullName -Raw)).testsuite.tests }; "$dir $n" }`, then `"device $(@(Get-ChildItem app\src\androidTest -Recurse -Filter *.kt | Select-String -Pattern '@Test\b').Count)"`, then `scripts\gradle.ps1 --stop`, then `scripts\test-scripts.ps1`
Expected: BUILD SUCCESSFUL; `app` A₂ + 4 − D; `tools\eval` E₀ + 1; `device` AD₀ + 2; `:core:conversation` C₀ + 4 and `:core:agent` G₀ tests pass, as their reports show; `scripts\test-scripts.ps1` passes with one Pester test more than it had before (`AgentChecks.Tests.ps1`; the other scripts' changes are comments).

- [ ] **Step 15: Commit**

```bash
git add -A app/src tools/eval/src core/conversation/src scripts/hook.ps1 scripts/a71-session.ps1 scripts/lib/AgentChecks.ps1 scripts/tests/AgentChecks.Tests.ps1
git commit -m "feat(app): the voice path gives the conversation its inputs; AgentRunner and AppOwnerPort go (plan 7D)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: The follow-up window

Design 6.6 of sub-project 7: once Kestrel has finished speaking a command's or a local request's final reply (`ChannelOutput.Report`, which `VoiceChannel` now says as `final`), the next utterance is a command without the name, for the window's length from the Conversations page (8 s by default, never longer than the quiet time), with recognition `ALL`, also in the paused mode. Kestrel's own requests keep their meaning in it, and the instant commands' parser is not asked: the words are read as after "Yes?", except that "stop", "cancel", "stop listening" and "turn off" keep their meaning (design 3.6 of sub-project 7), so "turn off" alone turns Kestrel off there and never reaches the conversation (`Requests.read(…, followUp = true)`). The bar shows "Go on…" with the seconds left over the reply's text and fades when the window ends. It ends at its timer, at any transcript, at Kestrel's next reply, when the screen turns off, a call rings or Kestrel turns off, and when the conversation closes. Words in the middle of a goal (`Said`) open no window, and neither does a final reply that Kestrel did not speak to its end: one that the owner or a call stopped, that Android refused or interrupted (as when another app takes the audio focus), that no voice session could speak, or that Kestrel only showed while off. The owner did not hear such a reply, and its text stays on the bar as the owner's way to read it: `Event.SpeechEnded(spoken)` tells the Rules whether Kestrel spoke the whole reply (the controller's decision on the review's I1 of 2026-11-25). The bar knows whose text it shows (`OwnedBar`, the review's M1): the countdown takes the bar only while it is free or shows the final reply that the window follows, stops for good once someone else shows a text, and releases only its own text, so it never hides "Microphone blocked", a question or a hand-over's sentence; an instant command likewise releases only a command's text. The window holds only while the bar counts its seconds down and Kestrel can hear the owner in it (`Rules.endFollowUp`, the re-check's N1): it also ends when Android blocks the microphone and when anything else takes the bar (Kestrel's next reply, spoken or shown, a change of mode by the tile, the notification or the voice, "Microphone blocked"), and none opens while the microphone is blocked, so the bar and the window never disagree. No window opens or stays open while a goal is open, so a goal that starts while a final reply is spoken is never steered without the name (D9; the whole-plan review's I2).

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/listen/OwnedBar.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt`, `app/src/main/kotlin/dev/kestrel/app/orchestrator/Orchestrator.kt`, `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, `app/src/main/kotlin/dev/kestrel/app/listen/VoicePath.kt`, `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`
- Test: `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, `app/src/test/kotlin/dev/kestrel/app/orchestrator/RequestsTest.kt`, `app/src/test/kotlin/dev/kestrel/app/orchestrator/OrchestratorTest.kt`, `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, `app/src/test/kotlin/dev/kestrel/app/conversation/VoiceChannelTest.kt`

**Interfaces:**
- Consumes: Task 3's `Rules`, `VoicePath` and `VoiceChannel`; plan 7B's `ConversationSettings.followUpMillis`.
- Produces: `Work.FOLLOWING_UP`; `State.followUpMillis`, `State.followUp`, `State.finalReply: Int?`; `Event.SpeechEnded(spoken: Boolean)` (was a `data object`), `Event.ChannelSays(reply, text, final = false)`, `Event.FollowUpLength(millis: Long)`, `Event.FollowUpEnded(followUp: Int)`; `Feedback.FollowUp(seconds: Int, reply: Int)`, `Feedback.ConversationText(text, keep = false, finalReply: Int? = null)`; `Effect.TimeFollowUp(answering = false)`; `VoicePath.say(text, final = false)`; `Requests.read(transcript, spotted, afterName = false, stopSpotted = false, followUp = false)`; `ListeningTexts.goOn(seconds)`; `internal class OwnedBar(bar: FeedbackBar)` with `show(text, keep = false, owner = BarOwner.Other)`, `takeOver(text, owner, over): Boolean` and `release(owner)`, and `internal sealed interface BarOwner` (`Other`, `Commands`, `FinalReply(reply)`, `FollowUp`); the app graph sends `FollowUpLength` at every change of the Conversations page.

Counts: `:app` JVM A₃ + 11 (`RulesTest` 100 + 5 = 105, `RequestsTest` 32 + 1 = 33, `OrchestratorTest` 15 + 1 = 16, `ListeningTest` 146 + 4 = 150; `VoiceChannelTest` changes a case, `ListeningTest` two, and `RulesTest` and `OrchestratorTest` give every `Event.SpeechEnded` its `spoken`). Device tests: AD₀ + 2, unchanged.

- [ ] **Step 1: Write the failing tests**

`Event.SpeechEnded` becomes a class whose `spoken` says whether Kestrel spoke the whole reply, so the existing cases of `RulesTest` and `OrchestratorTest` give it `spoken = true`.

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        val spoken = Rules.next(asked.state, Event.SpeechEnded)
```

with:

```kotlin
        val spoken = Rules.next(asked.state, Event.SpeechEnded(spoken = true))
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        assertEquals(Step(twoReplies.copy(speeches = 1)), Rules.next(twoReplies, Event.SpeechEnded))
```

with:

```kotlin
        assertEquals(Step(twoReplies.copy(speeches = 1)), Rules.next(twoReplies, Event.SpeechEnded(spoken = true)))
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        assertEquals(Step(listening), Rules.next(listening, Event.SpeechEnded))
```

with:

```kotlin
        assertEquals(Step(listening), Rules.next(listening, Event.SpeechEnded(spoken = true)))
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        val spoken = Rules.next(asked, Event.SpeechEnded)
```

with:

```kotlin
        val spoken = Rules.next(asked, Event.SpeechEnded(spoken = true))
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
            Event.SpeechEnded, Event.ConversationChanged(acting.conversation), Event.ChannelSays(1, "Done."),
```

with:

```kotlin
            Event.SpeechEnded(spoken = true), Event.ConversationChanged(acting.conversation), Event.ChannelSays(1, "Done."),
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        assertEquals(listOf(Effect.TimeSilence), Rules.next(replying, Event.SpeechEnded).effects)
```

with:

```kotlin
        assertEquals(listOf(Effect.TimeSilence), Rules.next(replying, Event.SpeechEnded(spoken = true)).effects)
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        Rules.next(Rules.next(from, Event.ChannelHandsOver(handOverText)).state, Event.SpeechEnded).state
```

with:

```kotlin
        Rules.next(Rules.next(from, Event.ChannelHandsOver(handOverText)).state, Event.SpeechEnded(spoken = true)).state
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        val spoken = Rules.next(step.state, Event.SpeechEnded)
```

with:

```kotlin
        val spoken = Rules.next(step.state, Event.SpeechEnded(spoken = true))
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        val yes = heard(Rules.next(spotted, Event.SpeechEnded).state, "Kestrel, continue.", named = true)
```

with:

```kotlin
        val yes = heard(Rules.next(spotted, Event.SpeechEnded(spoken = true)).state, "Kestrel, continue.", named = true)
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        val spoken = Rules.next(says, Event.SpeechEnded).state
```

with:

```kotlin
        val spoken = Rules.next(says, Event.SpeechEnded(spoken = true)).state
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
            val spoken = Rules.next(question, Event.SpeechEnded)
```

with:

```kotlin
            val spoken = Rules.next(question, Event.SpeechEnded(spoken = true))
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        val speaking = ownQuestion()
        val asked = Rules.next(speaking, Event.SpeechEnded).state
        val withdrawn = Effect.Converse(InputIntent.Answer(ChannelAnswer.Interrupted))
```

with:

```kotlin
        val speaking = ownQuestion()
        val asked = Rules.next(speaking, Event.SpeechEnded(spoken = true)).state
        val withdrawn = Effect.Converse(InputIntent.Answer(ChannelAnswer.Interrupted))
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
        val asked = Rules.next(speaking, Event.SpeechEnded).state
```

with:

```kotlin
        val asked = Rules.next(speaking, Event.SpeechEnded(spoken = true)).state
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/OrchestratorTest.kt`, replace:

```kotlin
        advanceTimeBy(5_000)
        orchestrator.send(Event.SpeechEnded)
        runCurrent()
```

with:

```kotlin
        advanceTimeBy(5_000)
        orchestrator.send(Event.SpeechEnded(spoken = true))
        runCurrent()
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/OrchestratorTest.kt`, replace:

```kotlin
        advanceTimeBy(5_000)
        orchestrator.send(Event.SpeechEnded)
```

with:

```kotlin
        advanceTimeBy(5_000)
        orchestrator.send(Event.SpeechEnded(spoken = true))
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/OrchestratorTest.kt`, replace:

```kotlin
        advanceTimeBy(1_000)
        orchestrator.send(Event.SpeechEnded)
```

with:

```kotlin
        advanceTimeBy(1_000)
        orchestrator.send(Event.SpeechEnded(spoken = true))
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/OrchestratorTest.kt`, replace:

```kotlin
        orchestrator.send(Event.ChannelAsks(4, "Which account?", QuestionKind.FREE))
        orchestrator.send(Event.SpeechEnded)
```

with:

```kotlin
        orchestrator.send(Event.ChannelAsks(4, "Which account?", QuestionKind.FREE))
        orchestrator.send(Event.SpeechEnded(spoken = true))
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/OrchestratorTest.kt`, replace:

```kotlin
        orchestrator.send(Event.SpeechEnded)
```

with:

```kotlin
        orchestrator.send(Event.SpeechEnded(spoken = true))
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
    }
}
```

with:

```kotlin
    }

    // Design 6.6 of sub-project 7: an open conversation, and a final reply that Kestrel has finished speaking.
    private val open = listening.copy(conversation = VoiceView(open = true))

    private fun replied(from: State = open): State =
        Rules.next(Rules.next(from, Event.ChannelSays(5, "Done.", final = true)).state, Event.SpeechEnded(spoken = true)).state

    @Test
    fun aSpokenFinalReplyOpensTheFollowUpWindowWhereAnUtteranceIsACommand() {
        val said = Rules.next(open, Event.ChannelSays(5, "Done.", final = true)).state
        val ended = Rules.next(said, Event.SpeechEnded(spoken = true))

        assertEquals(listOf(Effect.TimeFollowUp(), Effect.Show(Feedback.FollowUp(8, reply = 5))), ended.effects)
        assertEquals(Work.FOLLOWING_UP, ended.state.work)
        assertEquals(Recognition.ALL, ended.state.recognition)
        assertEquals(Recognition.ALL, replied(paused.copy(conversation = open.conversation)).recognition)
        // Words without the name are a command; the instant commands' parser is not asked.
        val heard = Event.Heard("Scroll down", named = false)
        val step = Rules.next(ended.state, heard)
        assertEquals(listOf(Effect.Show(Feedback.Heard("Scroll down")), Effect.Converse(InputIntent.Command("Scroll down"), heard)), step.effects)
        assertEquals(Work.IDLE, step.state.work)
        // Kestrel's own requests keep their meaning, and an utterance that started inside the window gets the time to end.
        assertEquals(Mode.PAUSED, Rules.next(ended.state, Event.Heard("Stop listening.", named = false)).state.mode)
        assertEquals(listOf(Effect.TimeFollowUp(answering = true)), Rules.next(ended.state, Event.Speaking).effects)
    }

    @Test
    fun theFollowUpWindowEndsAtItsTimerTheNextReplyTheScreenACallOrTheConversationsEnd() {
        val window = replied()

        assertEquals(Work.IDLE, Rules.next(window, Event.FollowUpEnded(window.followUp)).state.work)
        assertEquals(Step(window), Rules.next(window, Event.FollowUpEnded(window.followUp - 1)))
        assertEquals(Work.IDLE, Rules.next(window, Event.ChannelSays(6, "Got it.")).state.work)
        assertEquals(Work.IDLE, Rules.next(window, Event.Screen(on = false)).state.work)
        assertEquals(Work.IDLE, Rules.next(window, Event.CallState(CallState.RINGING)).state.work)
        assertEquals(Work.IDLE, Rules.next(window, Event.ConversationChanged(VoiceView())).state.work)
        assertEquals(Work.IDLE, Rules.next(window, Event.NothingHeard).state.work)
        assertEquals(Mode.OFF, Rules.next(window, Event.TurnOff).state.mode)
    }

    @Test
    fun noWindowOpensAfterWordsInTheMiddleOfAGoalAnUnspokenReplyOrWithoutItsLength() {
        // Kestrel's words in the middle of a goal need the name again (D9 of sub-project 7).
        assertEquals(Work.IDLE, Rules.next(Rules.next(open, Event.ChannelSays(5, "Opening Viber.")).state, Event.SpeechEnded(spoken = true)).state.work)
        // A final reply that was not spoken to its end, because the owner or a call stopped it, Android refused or
        // interrupted it, or no voice session could speak it: the owner did not hear it, and its text stays on the bar.
        val said = Rules.next(open, Event.ChannelSays(5, "Done.", final = true)).state
        val unspoken = Rules.next(said, Event.SpeechEnded(spoken = false))
        assertEquals(Work.IDLE, unspoken.state.work)
        assertTrue(unspoken.effects.none { it is Effect.Show })
        // Also when another reply that was spoken to its end ends after it.
        val other = Rules.next(said.copy(speeches = 2), Event.SpeechEnded(spoken = false)).state
        assertEquals(Work.IDLE, Rules.next(other, Event.SpeechEnded(spoken = true)).state.work)
        // A final reply that Kestrel only showed while off.
        assertEquals(
            listOf(Effect.Show(Feedback.ConversationText("Done.", finalReply = 5)), Effect.EndReply(5)),
            Rules.next(open.copy(mode = Mode.OFF), Event.ChannelSays(5, "Done.", final = true)).effects,
        )
        // Without its length, or with the screen off.
        assertEquals(Work.IDLE, replied(Rules.next(open, Event.FollowUpLength(0)).state).work)
        assertEquals(Work.IDLE, replied(open.copy(screenOn = false)).work)
    }

    @Test
    fun turnOffAloneInTheFollowUpWindowTurnsKestrelOffAndReachesNoConversation() {
        val step = Rules.next(replied(), Event.Heard("Turn off.", named = false))

        assertEquals(Mode.OFF, step.state.mode)
        assertTrue(step.effects.none { it is Effect.Converse })
    }
}
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RequestsTest.kt`, replace:

```kotlin
    }
}
```

with:

```kotlin
    }

    @Test
    fun turnOffAloneTurnsKestrelOffInTheFollowUpWindowButNotAfterYes() {
        assertEquals(Request.TurnOff, Requests.read("Turn off.", spotted = false, afterName = true, followUp = true))
        assertEquals(Request.TurnOff, Requests.read("switch off", spotted = false, afterName = true, followUp = true))
        assertEquals(Request.PauseListening, Requests.read("Stop listening.", spotted = false, afterName = true, followUp = true))
        assertEquals(Request.Task("turn off the lights"), Requests.read("turn off the lights", spotted = false, afterName = true, followUp = true))
        // After "Yes?" the owner may not have finished (plan 4E).
        assertEquals(Request.Task("Turn off."), Requests.read("Turn off.", spotted = false, afterName = true))
    }
}
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/OrchestratorTest.kt`, replace:

```kotlin
    }
}
```

with:

```kotlin
    }

    @Test
    fun theFollowUpWindowEndsAfterItsLengthUnlessAnUtteranceStartedInIt() = runTest {
        val orchestrator = Orchestrator(backgroundScope, { performed += it }, { errors += it })
        orchestrator.send(Event.Toggle)
        orchestrator.send(Event.ConversationChanged(VoiceView(open = true)))
        orchestrator.send(Event.FollowUpLength(4_000))
        orchestrator.send(Event.ChannelSays(1, "Done.", final = true))
        orchestrator.send(Event.SpeechEnded(spoken = true))
        runCurrent()
        assertEquals(Work.FOLLOWING_UP, orchestrator.state.value.work)
        advanceTimeBy(4_001)
        runCurrent()
        assertEquals(Work.IDLE, orchestrator.state.value.work)

        // An utterance that started inside the window gets the time to end and to be transcribed.
        orchestrator.send(Event.ChannelSays(2, "Done.", final = true))
        orchestrator.send(Event.SpeechEnded(spoken = true))
        orchestrator.send(Event.Speaking)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(Work.FOLLOWING_UP, orchestrator.state.value.work)
        advanceTimeBy(25_001)
        runCurrent()
        assertEquals(Work.IDLE, orchestrator.state.value.work)
        assertEquals(emptyList<Exception>(), errors)
    }
}
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
import dev.kestrel.app.orchestrator.WakeUpMode
```

with:

```kotlin
import dev.kestrel.app.orchestrator.WakeUpMode
import dev.kestrel.app.orchestrator.VoiceView
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
        assertEquals("Later.", shown.last())
```

with:

```kotlin
        assertEquals("Later.", shown.last())
    }

    @Test
    fun theFollowUpWindowCountsItsSecondsDownOnTheBarAndFadesAtItsEnd() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        listening.send(Event.ConversationChanged(VoiceView(open = true)))
        listening.send(Event.FollowUpLength(3_000))

        val said = async { listening.voicePath.say("Done.", final = true) }
        runCurrent()
        session.replies.single().complete(Spoke.FINISHED)
        runCurrent()
        assertTrue(said.isCompleted)
        assertEquals(ListeningTexts.goOn(3), kept.last())
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(ListeningTexts.goOn(2), kept.last())

        val before = releases
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(Work.IDLE, listening.state.value.work)
        assertEquals(before + 1, releases)
    }

    @Test
    fun aFinalReplyThatAndroidRefusedOrInterruptedOpensNoWindowAndStaysOnTheBar() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        listening.send(Event.ConversationChanged(VoiceView(open = true)))

        // As when another app takes the audio focus (section 14.6): the owner did not hear the reply.
        for (spoke in listOf(Spoke.INTERRUPTED, Spoke.REFUSED)) {
            val said = async { listening.voicePath.say("Done.", final = true) }
            runCurrent()
            session.replies.last().complete(spoke)
            runCurrent()
            assertTrue(said.isCompleted)
            assertEquals(Work.IDLE, listening.state.value.work)
            assertEquals("Done.", shown.last())
        }
        assertEquals(emptyList<String>(), kept)
    }

    @Test
    fun aFinalReplyWithoutAVoiceSessionOpensNoWindowAndStaysOnTheBar() = runTest {
        val listening = listening()
        listening.send(Event.Toggle)
        listening.send(Event.ConversationChanged(VoiceView(open = true)))
        runCurrent()

        // The service has not reported the foreground yet, so no session is open.
        val said = async { listening.voicePath.say("Done.", final = true) }
        runCurrent()
        assertTrue(said.isCompleted)
        assertEquals(Work.IDLE, listening.state.value.work)
        assertEquals("Done.", shown.last())
        assertEquals(emptyList<String>(), kept)
    }

    @Test
    fun theCountdownNeverTakesTheBarFromAnotherTextAndReleasesOnlyItsOwn() = runTest {
        val listening = listeningWithSession()
        val session = sessions.opened.single()
        listening.send(Event.ConversationChanged(VoiceView(open = true)))
        listening.send(Event.FollowUpLength(3_000))

        // "Microphone blocked" stays: the countdown stops, and the window's end releases nothing.
        val said = async { listening.voicePath.say("Done.", final = true) }
        runCurrent()
        session.replies.last().complete(Spoke.FINISHED)
        runCurrent()
        assertTrue(said.isCompleted)
        assertEquals(ListeningTexts.goOn(3), shown.last())
        listening.send(Event.MicBusy(true))
        runCurrent()
        assertEquals(ListeningTexts.MIC_BUSY, shown.last())
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(ListeningTexts.MIC_BUSY, shown.last())
        assertEquals(Work.FOLLOWING_UP, listening.state.value.work)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(Work.IDLE, listening.state.value.work)
        assertEquals(0, releases)
        listening.send(Event.MicBusy(false))

        // A question that ends the window stays kept until the voice channel lets it go.
        val again = async { listening.voicePath.say("Sent.", final = true) }
        runCurrent()
        session.replies.last().complete(Spoke.FINISHED)
        runCurrent()
        assertTrue(again.isCompleted)
        assertEquals(ListeningTexts.goOn(3), shown.last())
        listening.send(Event.ChannelAsks(4, "Shall I send it?", QuestionKind.CONFIRM))
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals("Shall I send it?", shown.last())
        assertEquals(0, releases)
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
        listening.send(Event.Heard("Two.", named = false))
        runCurrent()
        assertEquals(Work.IDLE, listening.state.value.work)
        assertEquals(1, releases)
```

with:

```kotlin
        listening.send(Event.Heard("Two.", named = false))
        runCurrent()
        assertEquals(Work.IDLE, listening.state.value.work)
        // The answer took the question's place on the bar, so the newer command's end has no text of its own to release.
        assertEquals(0, releases)
```

In `app/src/test/kotlin/dev/kestrel/app/listen/ListeningTest.kt`, replace:

```kotlin
        // The kept question fades once the command has ended.
        assertEquals(1, releases)
```

with:

```kotlin
        // The answer took the kept question's place on the bar, so the command's end has no text of its own to release.
        assertEquals("Number two.", shown.last())
        assertEquals(0, releases)
```

In `app/src/test/kotlin/dev/kestrel/app/conversation/VoiceChannelTest.kt`, replace:

```kotlin
        assertEquals(listOf("Android gave Kestrel only the photos that you selected.", "Done."), voice.said)
```

with:

```kotlin
        assertEquals(listOf("Android gave Kestrel only the photos that you selected.", "Done."), voice.said)
        // Only the final reply opens the follow-up window (design 6.6 of sub-project 7).
        assertEquals(listOf(false, true), voice.finals)
```

In `app/src/test/kotlin/dev/kestrel/app/conversation/VoiceChannelTest.kt`, replace:

```kotlin
        val said = mutableListOf<String>()
```

with:

```kotlin
        val said = mutableListOf<String>()
        val finals = mutableListOf<Boolean>()
```

In `app/src/test/kotlin/dev/kestrel/app/conversation/VoiceChannelTest.kt`, replace:

```kotlin
        override suspend fun say(text: String) {
            said += text
```

with:

```kotlin
        override suspend fun say(text: String, final: Boolean) {
            said += text
            finals += final
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.orchestrator.RulesTest`
Expected: FAIL, the build does not compile: `Work.FOLLOWING_UP`, `Event.FollowUpLength`, `Effect.TimeFollowUp` and `Event.SpeechEnded(spoken)` do not exist yet.

- [ ] **Step 3: The window in the rules and its timer**

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
import dev.kestrel.conversation.model.ChannelAnswer
```

with:

```kotlin
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.ConversationSettings
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
    AWAITING_TASK,
```

with:

```kotlin
    AWAITING_TASK,

    /**
     * The follow-up window after a spoken final reply (design 6.6 of sub-project 7): the next utterance is a command for
     * the conversation without the name.
     */
    FOLLOWING_UP,
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
 * turns off once the goal has ended and its final reply has been spoken.
```

with:

```kotlin
 * turns off once the goal has ended and its final reply has been spoken. [followUpMillis] is the follow-up window's length
 * from the Conversations page, and [followUp] the number of the latest window, so that the end of an older one changes
 * nothing; [finalReply]: the number of the final reply that Kestrel speaks now, whose end opens the window once Kestrel
 * has spoken all of it (design 6.6 of sub-project 7).
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
    val offAfterGoal: Boolean = false,
```

with:

```kotlin
    val offAfterGoal: Boolean = false,
    val followUpMillis: Long = ConversationSettings().followUpMillis,
    val followUp: Int = 0,
    val finalReply: Int? = null,
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
            "voiceQuestion=$voiceQuestion, voiceAnswers=$voiceAnswers, answered=$answered, offAfterGoal=$offAfterGoal)"
```

with:

```kotlin
            "voiceQuestion=$voiceQuestion, voiceAnswers=$voiceAnswers, answered=$answered, offAfterGoal=$offAfterGoal, " +
            "followUpMillis=$followUpMillis, followUp=$followUp, finalReply=$finalReply)"
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
     * the channel learns when it has been spoken, or that it was only shown ([Effect.EndReply]).
     */
    data class ChannelSays(val reply: Int, val text: String) : Event {
        override fun toString() = "ChannelSays($reply, ${text.length} characters)"
    }
```

with:

```kotlin
     * the channel learns when it has been spoken, or that it was only shown ([Effect.EndReply]). [final]: it is a command's
     * or a local request's final reply, whose end opens the follow-up window (design 6.6).
     */
    data class ChannelSays(val reply: Int, val text: String, val final: Boolean = false) : Event {
        override fun toString() = "ChannelSays($reply, ${text.length} characters, final=$final)"
    }

    /** The follow-up window's length from the Conversations page (design 6.6 and 13 of sub-project 7); 0 is off. */
    data class FollowUpLength(val millis: Long) : Event

    /** Follow-up window number [followUp] has lasted its length (design 6.6 of sub-project 7). */
    data class FollowUpEnded(val followUp: Int) : Event
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
    /**
     * A reply has been spoken, stopped, interrupted or refused, or speaking it failed. Send it once for each reply, or
     * recognition stays off.
     */
    data object SpeechEnded : Event
```

with:

```kotlin
    /**
     * A reply has been spoken to its end ([spoken]), or stopped, interrupted or refused, or speaking it failed. Send it
     * once for each reply, or recognition stays off. Only a final reply that Kestrel spoke to its end opens the follow-up
     * window (design 6.6 of sub-project 7).
     */
    data class SpeechEnded(val spoken: Boolean) : Event
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
        Event.SpeechEnded -> speechEnded(state)
```

with:

```kotlin
        is Event.SpeechEnded -> speechEnded(state, event.spoken)
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
        Event.SpeechEnded ->
            speechEnded(state).let {
```

with:

```kotlin
        is Event.SpeechEnded ->
            speechEnded(state, event.spoken).let {
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
    data object NoWords : Feedback
```

with:

```kotlin
    data object NoWords : Feedback

    /**
     * The follow-up window opens for [seconds] after final reply number [reply] (design 6.6 of sub-project 7): the bar
     * counts them down over the reply's text.
     */
    data class FollowUp(val seconds: Int, val reply: Int) : Feedback
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
    /**
     * What the conversation says, shows, asks or hands over through the voice channel (design 11 of sub-project 7). With
     * [keep], the text stays until the voice channel releases it, as a question or a hand-over's sentence does.
     */
    data class ConversationText(val text: String, val keep: Boolean = false) : Feedback {
        override fun toString() = "ConversationText(${text.length} characters, keep=$keep)"
    }
```

with:

```kotlin
    /**
     * What the conversation says, shows, asks or hands over through the voice channel (design 11 of sub-project 7). With
     * [keep], the text stays until the voice channel releases it, as a question or a hand-over's sentence does.
     * [finalReply]: the number of the final reply that it shows, over which that reply's follow-up window counts down
     * (design 6.6 of sub-project 7).
     */
    data class ConversationText(val text: String, val keep: Boolean = false, val finalReply: Int? = null) : Feedback {
        override fun toString() = "ConversationText(${text.length} characters, keep=$keep, finalReply=$finalReply)"
    }
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
    data object TimeLateAnswers : Effect
```

with:

```kotlin
    data object TimeLateAnswers : Effect

    /**
     * Starts the timer that ends the latest follow-up window ([Event.FollowUpEnded]), in place of the one that runs:
     * [State.followUpMillis], or with [answering], once the owner has started an utterance in it, the time to finish and
     * transcribe it. The Orchestrator runs it itself.
     */
    data class TimeFollowUp(val answering: Boolean = false) : Effect
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
            state.work == Work.AWAITING_TASK -> waitForTask(state, answering = true)
```

with:

```kotlin
            state.work == Work.AWAITING_TASK -> waitForTask(state, answering = true)
            state.work == Work.FOLLOWING_UP -> Step(state, listOf(Effect.TimeFollowUp(answering = true)))
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
            state.mode != Mode.OFF && state.work == Work.AWAITING_TASK -> waitForTask(state)
```

with:

```kotlin
            state.mode != Mode.OFF && state.work == Work.AWAITING_TASK -> waitForTask(state)
            state.work == Work.FOLLOWING_UP -> Step(state.copy(work = Work.IDLE))
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
        is Event.ConversationChanged -> conversationChanged(state, event.view)
```

with:

```kotlin
        is Event.ConversationChanged -> conversationChanged(state, event.view)
        is Event.FollowUpLength -> Step(state.copy(followUpMillis = event.millis))
        is Event.FollowUpEnded ->
            if (state.work == Work.FOLLOWING_UP && event.followUp == state.followUp) Step(state.copy(work = Work.IDLE)) else Step(state)
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
        // An empty transcript that finishes after the screen turned off does not count: nobody could see its hint.
        // Waiting for a command ends when the screen turns off.
        val request = Requests.read(event.text, event.named, afterName = awaiting, stopSpotted = event.stopSpotted)
            ?: return if (state.screenOn) withoutWords(state, awaiting) else Step(state)
```

with:

```kotlin
        // Inside the follow-up window the words are read as after the name (design 6.6 of sub-project 7).
        val following = state.work == Work.FOLLOWING_UP
        // An empty transcript that finishes after the screen turned off does not count: nobody could see its hint.
        // Waiting for a command ends when the screen turns off, and any transcript ends the follow-up window.
        val request = Requests.read(event.text, event.named, afterName = awaiting || following, stopSpotted = event.stopSpotted, followUp = following)
            ?: return if (state.screenOn) withoutWords(if (following) state.copy(work = Work.IDLE) else state, awaiting) else Step(state)
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
            Work.AWAITING_TASK -> whileAwaitingTask(counted, request, event, shown)
```

with:

```kotlin
            // Every utterance in the follow-up window is a command, and Kestrel's own requests keep their meaning; the
            // instant commands' parser is not asked (design 6.6 of sub-project 7).
            Work.AWAITING_TASK, Work.FOLLOWING_UP -> whileAwaitingTask(counted, request, event, shown)
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
    // to start the answer.
    private fun speechEnded(state: State): Step {
        val ended = state.copy(speeches = maxOf(0, state.speeches - 1))
        return when {
            state.speeches == 1 && state.work == Work.AWAITING_TASK -> waitForTask(ended)
            state.speeches == 1 && state.voiceQuestion != null -> waitForAnswer(ended)
            else -> stayOff(ended)
        }
```

with:

```kotlin
    // to start the answer. A final reply that was not spoken to its end, because the owner or a call stopped it, Android
    // refused or interrupted it, or no voice session could speak it, opens no follow-up window: the owner did not hear
    // it, and its text stays on the bar (design 6.6 of sub-project 7).
    private fun speechEnded(state: State, spoken: Boolean): Step {
        val ended = state.copy(speeches = maxOf(0, state.speeches - 1), finalReply = state.finalReply?.takeIf { spoken })
        val finalReply = ended.finalReply
        return when {
            state.speeches == 1 && state.work == Work.AWAITING_TASK -> waitForTask(ended)
            state.speeches == 1 && state.voiceQuestion != null -> waitForAnswer(ended)
            ended.speeches == 0 && finalReply != null -> stayOff(ended.copy(finalReply = null)).then { followUp(it, finalReply) }
            else -> stayOff(ended)
        }
    }

    // Design 6.6 of sub-project 7: once Kestrel has finished speaking final reply number [reply], the next utterance is a
    // command without the name, for the window's length, with the screen on and the conversation open, outside a call,
    // also in the paused mode. The bar counts the seconds down over the reply's text.
    private fun followUp(state: State, reply: Int): Step {
        val opens = state.followUpMillis > 0 && state.mode != Mode.OFF && !state.inCall && state.screenOn &&
            state.work == Work.IDLE && state.conversation.open
        if (!opens) return Step(state)
        val seconds = ((state.followUpMillis + 999) / 1_000).toInt()
        return Step(
            state.copy(work = Work.FOLLOWING_UP, followUp = state.followUp + 1),
            listOf(Effect.TimeFollowUp(), Effect.Show(Feedback.FollowUp(seconds, reply))),
        )
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
            answered = state.answered?.takeIf { it == view.question },
```

with:

```kotlin
            answered = state.answered?.takeIf { it == view.question },
            // The follow-up window ends when the conversation closes (design 6.6 of sub-project 7).
            work = if (state.work == Work.FOLLOWING_UP && !view.open) Work.IDLE else state.work,
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
        val shown = Effect.Show(Feedback.ConversationText(event.text))
        if (state.mode == Mode.OFF) return Step(state, listOf(shown, Effect.EndReply(event.reply)))
        return say(state, Reply.Channel(event.reply, event.text), shown)
```

with:

```kotlin
        val finalReply = event.reply.takeIf { event.final }
        val shown = Effect.Show(Feedback.ConversationText(event.text, finalReply = finalReply))
        if (state.mode == Mode.OFF) return Step(state, listOf(shown, Effect.EndReply(event.reply)))
        return say(state, Reply.Channel(event.reply, event.text), shown, finalReply = finalReply)
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
    private fun say(state: State, reply: Reply, vararg first: Effect): Step =
        Step(state.copy(speeches = state.speeches + 1), first.toList() + Effect.Say(reply))
```

with:

```kotlin
    // Kestrel's next reply ends the follow-up window (design 6.6 of sub-project 7); only a final reply opens a new one.
    private fun say(state: State, reply: Reply, vararg first: Effect, finalReply: Int? = null): Step = Step(
        state.copy(
            speeches = state.speeches + 1,
            work = if (state.work == Work.FOLLOWING_UP) Work.IDLE else state.work,
            finalReply = finalReply,
        ),
        first.toList() + Effect.Say(reply),
    )
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
            offAfterGoal = false,
```

with:

```kotlin
            offAfterGoal = false,
            finalReply = null,
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Rules.kt`, replace:

```kotlin
            work = if (!on && state.work == Work.AWAITING_TASK) Work.IDLE else state.work,
```

with:

```kotlin
            work = if (!on && (state.work == Work.AWAITING_TASK || state.work == Work.FOLLOWING_UP)) Work.IDLE else state.work,
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt`, replace:

```kotlin
     */
    fun read(transcript: String, spotted: Boolean, afterName: Boolean = false, stopSpotted: Boolean = false): Request? {
```

with:

```kotlin
     * [followUp]: the transcript came in the follow-up window (design 6.6 of sub-project 7), which is read as after the
     * name, except that "turn off" alone turns Kestrel off there, as "stop", "cancel" and "stop listening" keep their
     * meaning (design 3.6 of sub-project 7).
     */
    fun read(transcript: String, spotted: Boolean, afterName: Boolean = false, stopSpotted: Boolean = false, followUp: Boolean = false): Request? {
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt`, replace:

```kotlin
            return named(transcript, rest, limit, afterName = afterName, nameSaid = true)
```

with:

```kotlin
            return named(transcript, rest, limit, afterName = afterName, turnOffAlone = true)
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt`, replace:

```kotlin
            afterName -> named(transcript, words, limit, afterName = true, nameSaid = false)
```

with:

```kotlin
            afterName -> named(transcript, words, limit, afterName = true, turnOffAlone = followUp)
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt`, replace:

```kotlin
     * What [rest], the words after the name, ask for. [afterName]: they answer "Yes?". [nameSaid]: the owner said the
     * name before them; without it, a bare "turn off" is a task, because the answer is held for only 1.5 s of silence,
     * and after a longer pause the owner may not have finished.
```

with:

```kotlin
     * What [rest], the words after the name, ask for. [afterName]: they answer "Yes?". [turnOffAlone]: a bare "turn off"
     * turns Kestrel off, after the name and in the follow-up window; in an answer to "Yes?" without the name it is a task,
     * because the answer is held for only 1.5 s of silence, and after a longer pause the owner may not have finished.
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt`, replace:

```kotlin
        nameSaid: Boolean,
```

with:

```kotlin
        turnOffAlone: Boolean,
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt`, replace:

```kotlin
            nameSaid && said in TURN_OFF -> Request.TurnOff
```

with:

```kotlin
            turnOffAlone && said in TURN_OFF -> Request.TurnOff
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Orchestrator.kt`, replace:

```kotlin
 * answer `Silence` (design 11 of sub-project 7); the conversation runs a goal's hand-over and pauses itself. A
```

with:

```kotlin
 * answer `Silence` (design 11 of sub-project 7); the conversation runs a goal's hand-over and pauses itself. The
 * follow-up window after a spoken final reply lasts [State.followUpMillis], and [answerMillis] once the owner has started
 * an utterance in it (design 6.6 of sub-project 7). A
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Orchestrator.kt`, replace:

```kotlin
    private var late: Job? = null
```

with:

```kotlin
    private var late: Job? = null
    private var followUp: Job? = null
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Orchestrator.kt`, replace:

```kotlin
        }
        val notStarted = mutableListOf<Event>()
```

with:

```kotlin
        }
        if (step.state.work != Work.FOLLOWING_UP) {
            followUp?.cancel()
            followUp = null
        }
        val notStarted = mutableListOf<Event>()
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Orchestrator.kt`, replace:

```kotlin
                    send(Event.LateAnswersEnded(number))
```

with:

```kotlin
                    send(Event.LateAnswersEnded(number))
                }
            } else if (effect is Effect.TimeFollowUp) {
                val number = step.state.followUp
                val millis = if (effect.answering) answerMillis else step.state.followUpMillis
                followUp?.cancel()
                followUp = scope.launch {
                    delay(millis)
                    send(Event.FollowUpEnded(number))
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Orchestrator.kt`, replace:

```kotlin
                        is Effect.Say -> notStarted += Event.SpeechEnded
```

with:

```kotlin
                        is Effect.Say -> notStarted += Event.SpeechEnded(spoken = false)
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Orchestrator.kt`, replace:

```kotlin
Orchestrator then handles [Event.SpeechEnded].
```

with:

```kotlin
Orchestrator then handles [Event.SpeechEnded], as not spoken.
```

- [ ] **Step 4: The final reply, the bar's owners, its countdown and the window's length**

Create `app/src/main/kotlin/dev/kestrel/app/listen/OwnedBar.kt`:

```kotlin
package dev.kestrel.app.listen

/** Who put the text on the feedback bar that it shows last (design 6.6 of sub-project 7). */
internal sealed interface BarOwner {
    /**
     * A text that nobody takes back: a mode, a transcript, "Microphone blocked", a failure, and what the conversation says,
     * asks or hands over. A question or a hand-over's sentence stays until the next text.
     */
    data object Other : BarOwner

    /** The instant commands' texts: the command that keeps one releases it when it ends (section 7.3). */
    data object Commands : BarOwner

    /** The conversation's final reply number [reply], over which its follow-up window counts down. */
    data class FinalReply(val reply: Int) : BarOwner

    /** The follow-up window's countdown, "Go on…" with the seconds left. */
    data object FollowUp : BarOwner
}

/**
 * The feedback bar as Listening shares it: it knows whose text it shows last, so that a text is taken over only from the
 * owner that it follows and released only by its own owner (design 6.6 of sub-project 7). The bar is free once its text
 * was released. Any thread may call it; the calls reach [bar] in their order.
 */
internal class OwnedBar(private val bar: FeedbackBar) {
    // Null while the bar is free.
    private var owner: BarOwner? = null

    /** Shows [text] for [owner], as [FeedbackBar.show] does. */
    @Synchronized
    fun show(text: String, keep: Boolean = false, owner: BarOwner = BarOwner.Other) {
        this.owner = owner
        bar.show(text, keep)
    }

    /**
     * Keeps [text] on the bar for [owner], but only while the bar is free, shows [owner]'s own text, or shows the text of
     * [over]; otherwise it shows nothing, so that it never hides a text that someone showed since, and returns false.
     */
    @Synchronized
    fun takeOver(text: String, owner: BarOwner, over: BarOwner): Boolean {
        val current = this.owner
        if (current != null && current != owner && current != over) return false
        this.owner = owner
        bar.show(text, keep = true)
        return true
    }

    /** Lets [owner]'s kept text fade from now on, if the bar still shows it; another owner's text stays. */
    @Synchronized
    fun release(owner: BarOwner) {
        if (this.owner != owner) return
        this.owner = null
        bar.release()
    }
}
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
    private val bar: FeedbackBar,
```

with:

```kotlin
    bar: FeedbackBar,
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
    /** Makes the microphone of the next session. The device tests and the debug hooks replace it. */
    @Volatile
    internal var microphones: () -> Microphone = microphones
```

with:

```kotlin
    /** Makes the microphone of the next session. The device tests and the debug hooks replace it. */
    @Volatile
    internal var microphones: () -> Microphone = microphones

    // Every text of Listening reaches the bar through it, so that it knows whose text it shows (design 6.6 of
    // sub-project 7).
    private val bar = OwnedBar(bar)
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
                    bar.show(text(effect.feedback), keep = keep)
```

with:

```kotlin
                    bar.show(text(effect.feedback), keep = keep, owner = owner(effect.feedback))
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
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
```

with:

```kotlin
        // Registered last: nothing after it may throw, or the Orchestrator would end the reply a second time. Only a reply
        // that Kestrel spoke to its end counts as spoken (design 6.6 of sub-project 7).
        spoken.invokeOnCompletion { error ->
            val spoke = if (error == null) spoken.getCompleted() else null
            if (error != null && error !is CancellationException) {
                report("A reply", error)
                showInstead()
            } else if (spoke != null && unspoken(spoke)) {
                showInstead()
            }
            ended?.complete(Unit)
            send(Event.SpeechEnded(spoken = spoke == Spoke.FINISHED))
        }
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
                if (startedCommand == effect.command) bar.release()
```

with:

```kotlin
                if (startedCommand == effect.command) bar.release(BarOwner.Commands)
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
        override fun keep(text: String) = bar.show(text, keep = true)
```

with:

```kotlin
        override fun keep(text: String) = bar.show(text, keep = true, owner = BarOwner.Commands)
```

In `app/src/main/kotlin/dev/kestrel/app/listen/VoicePath.kt`, replace:

```kotlin
    /** Says [text] and shows it on the bar; returns once it has been spoken, stopped or refused, or only shown while Kestrel is off. */
    suspend fun say(text: String)
```

with:

```kotlin
    /**
     * Says [text] and shows it on the bar; returns once it has been spoken, stopped or refused, or only shown while Kestrel
     * is off. A [final] reply's end opens the follow-up window (design 6.6 of sub-project 7).
     */
    suspend fun say(text: String, final: Boolean = false)
```

In `app/src/main/kotlin/dev/kestrel/app/conversation/VoiceChannel.kt`, replace:

```kotlin
            is ChannelOutput.Report -> inTurn { voice.say(output.text) }
```

with:

```kotlin
            // A spoken final reply's end opens the follow-up window (design 6.6 of sub-project 7).
            is ChannelOutput.Report -> inTurn { voice.say(output.text, final = true) }
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
    const val YES = "Yes?"
```

with:

```kotlin
    const val YES = "Yes?"

    /** The follow-up window's bar with the seconds left (design 6.6 of sub-project 7). */
    fun goOn(seconds: Int): String = "Go on… $seconds"
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
    internal var inputs: ((Event.Heard?, OwnerInput) -> OwnerInput)? = null

    // The conversation's replies that wait for their speech's end, by their numbers (design 11 of sub-project 7).
```

with:

```kotlin
    internal var inputs: ((Event.Heard?, OwnerInput) -> OwnerInput)? = null

    // Only [thread] uses it: the follow-up window's countdown on the bar (design 6.6 of sub-project 7).
    private var followUpTicker: Job? = null

    // The conversation's replies that wait for their speech's end, by their numbers (design 11 of sub-project 7).
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
        override val ready: StateFlow<Boolean> get() = sessionOpen

        override suspend fun say(text: String) {
            val reply = replyNumbers.incrementAndGet()
```

with:

```kotlin
        override val ready: StateFlow<Boolean> get() = sessionOpen

        override suspend fun say(text: String, final: Boolean) {
            val reply = replyNumbers.incrementAndGet()
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
                send(Event.ChannelSays(reply, text))
```

with:

```kotlin
                send(Event.ChannelSays(reply, text, final))
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
    private fun follow(state: State) {
```

with:

```kotlin
    private fun follow(state: State) {
        if (state.work != Work.FOLLOWING_UP) endCountDown()
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
                if (effect.feedback == Feedback.AwaitingTask) timeline.reacted("wait")
```

with:

```kotlin
                if (effect.feedback == Feedback.AwaitingTask) timeline.reacted("wait")
                (effect.feedback as? Feedback.FollowUp)?.let {
                    countDown(it)
                    return
                }
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
            is Effect.WaitForTask, Effect.TimeSilence, is Effect.WaitForAnswer, Effect.TimeLateAnswers -> Unit
```

with:

```kotlin
            is Effect.WaitForTask, Effect.TimeSilence, is Effect.WaitForAnswer, Effect.TimeLateAnswers, is Effect.TimeFollowUp -> Unit
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
        is Feedback.ConversationText -> feedback.text
```

with:

```kotlin
        is Feedback.ConversationText -> feedback.text
        is Feedback.FollowUp -> ListeningTexts.goOn(feedback.seconds)
    }

    // Whose text the bar shows for [feedback]: a command releases its own, and a final reply's follow-up window counts down
    // over it (design 6.6 of sub-project 7).
    private fun owner(feedback: Feedback): BarOwner = when (feedback) {
        is Feedback.CommandText -> BarOwner.Commands
        is Feedback.ConversationText -> feedback.finalReply?.let { BarOwner.FinalReply(it) } ?: BarOwner.Other
        else -> BarOwner.Other
    }

    // Design 6.6 of sub-project 7: "Go on…" with the seconds left, until the window ends. It takes the bar only from the
    // final reply that it follows, and stops for good once someone else has shown a text, such as "Microphone blocked".
    private fun countDown(window: Feedback.FollowUp) {
        followUpTicker?.cancel()
        val reply = BarOwner.FinalReply(window.reply)
        followUpTicker = scope.launch(thread) {
            for (left in window.seconds downTo 1) {
                if (!bar.takeOver(ListeningTexts.goOn(left), BarOwner.FollowUp, over = reply)) return@launch
                delay(COUNT_DOWN_MILLIS)
            }
        }
    }

    // The window ended: its countdown stops, and its own text fades; a text that someone else showed since stays.
    private fun endCountDown() {
        val ticker = followUpTicker ?: return
        followUpTicker = null
        ticker.cancel()
        bar.release(BarOwner.FollowUp)
```

In `app/src/main/kotlin/dev/kestrel/app/listen/Listening.kt`, replace:

```kotlin
        const val HOLD_LIMIT_MILLIS = 30_000L
```

with:

```kotlin
        const val HOLD_LIMIT_MILLIS = 30_000L

        // Design 6.6 of sub-project 7: the follow-up window's bar counts whole seconds.
        const val COUNT_DOWN_MILLIS = 1_000L
```

In `app/src/main/kotlin/dev/kestrel/app/AppGraph.kt`, replace:

```kotlin
    // Listening gives the conversation the owner's spoken inputs and its wait for a command after "Yes?".
    private fun converse(listening: Listening) {
        listening.converse = { input -> conversations.submit(input) }
        listening.awaitingCommand = { active -> conversations.awaitingCommand(active) }
```

with:

```kotlin
    // Listening gives the conversation the owner's spoken inputs and its wait for a command after "Yes?", and gets the
    // follow-up window's length from the Conversations page (design 6.6 of sub-project 7).
    private fun converse(listening: Listening) {
        listening.converse = { input -> conversations.submit(input) }
        listening.awaitingCommand = { active -> conversations.awaitingCommand(active) }
        scope.launch {
            conversationSettings.map { it.followUpMillis }.distinctUntilChanged().collect { listening.send(Event.FollowUpLength(it)) }
        }
```

- [ ] **Step 5: Run the tests to see them pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.orchestrator.* --tests=dev.kestrel.app.listen.ListeningTest --tests=dev.kestrel.app.conversation.VoiceChannelTest`
Expected: PASS.

- [ ] **Step 6: Run `:app`'s JVM tests, count them and stop Gradle**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest :app:lintDebug`, then `$n = 0; Get-ChildItem app\build\test-results\testDebugUnitTest -Filter *.xml | ForEach-Object { $n += [int]([xml](Get-Content $_.FullName -Raw)).testsuite.tests }; "app $n"`, then `scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL; `app` A₃ + 11.

- [ ] **Step 7: Commit**

```bash
git add app/src
git commit -m "feat(app): the follow-up window after a spoken final reply (plan 7D)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: "New conversation", "continue the conversation about X" and the typed reading

Design 6.8, 9.6 and 12.2 of sub-project 7: after the name, "new conversation" (also "start a new conversation") closes the open conversation, and "continue the conversation about X" (also "continue our conversation" and "go back to the conversation", with or without "about X") continues a closed one. The voice reads them as Kestrel's own requests and gives them to the conversation as `InputIntent.NewConversation` and `InputIntent.ContinuePast(topic)`; plan 7B's engine decides what they do, also while a goal works (it says that the goal must stop first). "Continue" alone keeps its meaning (`InputIntent.Continue`), and any other sentence that starts with "continue the conversation", such as "continue the conversation with Ana", stays a command. `Requests.typed(text)` is the reading that plan 7F's text channel uses (design 12.2 of sub-project 7). A typed text has no recogniser, so only the exact name counts as the name, in any letter case and with any punctuation, and only at the start or among a stop's words; "Kestrels", "Kestler" or "Kes trel" stay the owner's words. Kestrel's own requests of the conversation count without the name: "stop" and "cancel", "continue", "new conversation", "continue the conversation about X", "delete history", "remember that …", "forget …" and "save that as …". Anything else is a command with the owner's words, also the requests that only the voice reads: "wake up", "stop listening", "turn off", "open settings", and "never mind", "forget it" and "nothing", which end only the voice's wait after "Yes?" and, typed, may answer a goal's question. The voice and the typed reading share one table of the conversation's own requests (`conversational`), and the reading of a stop takes the way to match the name (heard or exact).

**Files:**
- Modify: `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt`
- Test: `app/src/test/kotlin/dev/kestrel/app/orchestrator/RequestsTest.kt`, `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`

**Interfaces:**
- Consumes: Task 3's `Requests.intent` and `Rules`; plan 7B's `InputIntent.NewConversation` and `InputIntent.ContinuePast(topic: String?)`.
- Produces: `Request.NewConversation`, `Request.ContinuePast(topic: String?)`; `Requests.typed(text: String): InputIntent?` for plan 7F.

Counts: `:app` JVM A₄ + 6 (`RequestsTest` 33 + 5 = 38, `RulesTest` 105 + 1 = 106). Device tests: AD₀ + 2, unchanged.

- [ ] **Step 1: Write the failing tests**

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RequestsTest.kt`, replace:

```kotlin
package dev.kestrel.app.orchestrator

import org.junit.Assert.assertEquals
```

with:

```kotlin
package dev.kestrel.app.orchestrator

import dev.kestrel.conversation.model.InputIntent
import org.junit.Assert.assertEquals
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RequestsTest.kt`, replace:

```kotlin
        assertEquals("Other(11 characters)", Request.Other("Scroll down").toString())
    }
```

with:

```kotlin
        assertEquals("Other(11 characters)", Request.Other("Scroll down").toString())
        assertEquals("ContinuePast(6 characters)", Request.ContinuePast("dinner").toString())
        assertEquals("ContinuePast(no topic)", Request.ContinuePast(null).toString())
    }
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RequestsTest.kt`, replace:

```kotlin
    }
}
```

with:

```kotlin
    }

    @Test
    fun aNewConversationIsKestrelsOwnRequest() {
        for (said in listOf("Kestrel, new conversation.", "Kestrel, start a new conversation, please")) {
            assertEquals(said, Request.NewConversation, Requests.read(said, spotted = false))
        }
        assertEquals(Request.Task("open a new conversation with Ana"), Requests.read("Kestrel, open a new conversation with Ana", spotted = false))
    }

    @Test
    fun continuingAPastConversationTakesItsTopicInTheOwnersWords() {
        assertEquals(Request.ContinuePast("the Trip to Rome"), Requests.read("Kestrel, continue the conversation about the Trip to Rome.", spotted = false))
        assertEquals(Request.ContinuePast("dinner"), Requests.read("Kestrel, go back to our conversation about dinner", spotted = false))
        assertEquals(Request.ContinuePast(null), Requests.read("Kestrel, continue our conversation", spotted = false))
        assertEquals(Request.Continue, Requests.read("Kestrel, continue", spotted = false))
        assertEquals(Request.Task("continue the conversation with Ana"), Requests.read("Kestrel, continue the conversation with Ana", spotted = false))
        assertEquals(InputIntent.ContinuePast("dinner"), Requests.intent(Request.ContinuePast("dinner")))
        assertEquals(InputIntent.NewConversation, Requests.intent(Request.NewConversation))
    }

    @Test
    fun aTypedTextIsACommandUnlessItIsOneOfKestrelsOwnRequests() {
        assertEquals(InputIntent.Command("Open Viber"), Requests.typed("Open Viber"))
        assertEquals(InputIntent.Command("open Viber"), Requests.typed("Kestrel, open Viber"))
        assertEquals(InputIntent.NewConversation, Requests.typed("new conversation"))
        assertEquals(InputIntent.Continue, Requests.typed("continue"))
        assertEquals(InputIntent.DeleteHistory, Requests.typed("Delete history"))
        assertEquals(InputIntent.Stop, Requests.typed("stop"))
        assertEquals(InputIntent.Remember("the gate code", "blue"), Requests.typed("remember that the gate code is blue"))
        // The voice's own requests are no request of a typed text.
        assertEquals(InputIntent.Command("stop listening"), Requests.typed("stop listening"))
        assertNull(Requests.typed("  ... "))
    }

    @Test
    fun aTypedTextKeepsTheWordsThatOnlyTheVoiceReads() {
        // "Never mind", "forget it" and "nothing" end only the voice's wait after "Yes?"; typed, they are the owner's
        // words, such as the answer to a goal's question (design 12.2 of sub-project 7).
        assertEquals(InputIntent.Command("Nothing"), Requests.typed("Nothing"))
        assertEquals(InputIntent.Command("No, never mind"), Requests.typed("No, never mind"))
        assertEquals(InputIntent.Command("forget it"), Requests.typed("Kestrel, forget it"))
        assertEquals(InputIntent.Command("wake up"), Requests.typed("wake up"))
        assertEquals(InputIntent.Command("Turn off"), Requests.typed("Turn off"))
        assertEquals(InputIntent.Command("open settings"), Requests.typed("Kestrel, open settings"))
    }

    @Test
    fun aTypedTextTakesOnlyTheExactNameAsTheName() {
        assertEquals(InputIntent.Command("Kestrels are birds, show me pictures"), Requests.typed("Kestrels are birds, show me pictures"))
        assertEquals(InputIntent.Command("Kester, new conversation"), Requests.typed("Kester, new conversation"))
        assertEquals(InputIntent.Command("Kestler, stop"), Requests.typed("Kestler, stop"))
        assertEquals(InputIntent.Command("Kes trel, stop"), Requests.typed("Kes trel, stop"))
        assertEquals(InputIntent.NewConversation, Requests.typed("KESTREL: new conversation"))
        assertEquals(InputIntent.Stop, Requests.typed("Stop it, Kestrel!"))
    }
}
```

In `app/src/test/kotlin/dev/kestrel/app/orchestrator/RulesTest.kt`, replace:

```kotlin
    }
}
```

with:

```kotlin
    }

    @Test
    fun aNewConversationAndContinuingAPastOneGoToTheConversation() {
        val new = Event.Heard("Kestrel, new conversation", named = true)
        assertEquals(Effect.Converse(InputIntent.NewConversation, new), Rules.next(listening, new).effects.last())
        val past = Event.Heard("Kestrel, continue the conversation about dinner", named = true)
        assertEquals(Effect.Converse(InputIntent.ContinuePast("dinner"), past), Rules.next(listening, past).effects.last())
        // While a goal works too: the conversation says that the goal must stop first (design 6.8 of sub-project 7).
        assertEquals(Effect.Converse(InputIntent.NewConversation, new), Rules.next(acting, new).effects.last())
        // In the follow-up window, without the name.
        val again = Event.Heard("New conversation.", named = false)
        assertEquals(Effect.Converse(InputIntent.NewConversation, again), Rules.next(replied(), again).effects.last())
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.orchestrator.RequestsTest`
Expected: FAIL, the build does not compile: `Request.NewConversation`, `Request.ContinuePast` and `Requests.typed` do not exist yet.

- [ ] **Step 3: Read the two requests and the typed text**

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt`, replace:

```kotlin
    }

    /** A call command (section 8.7), which [Requests.readCall] reads while a call rings or lasts. */
```

with:

```kotlin
    }

    /**
     * "Kestrel, new conversation" or "start a new conversation": the open conversation closes, and the next command opens
     * a new one (design 6.8 of sub-project 7).
     */
    data object NewConversation : Request

    /**
     * "Kestrel, continue the conversation about [topic]", also "continue our conversation" or "go back to the
     * conversation", with the owner's own words and letter case; without "about", [topic] is null and the newest closed
     * conversation continues (design 9.6 and D23 of sub-project 7).
     */
    data class ContinuePast(val topic: String?) : Request {
        override fun toString() = "ContinuePast(${topic?.let { "${it.length} characters" } ?: "no topic"})"
    }

    /** A call command (section 8.7), which [Requests.readCall] reads while a call rings or lasts. */
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt`, replace:

```kotlin
 *    "continue", "delete history", "remember that <key> is <value>", "forget <key>", "save that as <name>", or a task.
```

with:

```kotlin
 *    "continue", "delete history", "new conversation", "continue the conversation [about <topic>]", "remember that <key>
 *    is <value>", "forget <key>", "save that as <name>", or a task.
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt`, replace:

```kotlin
    private val DELETE_HISTORY = setOf("delete history", "delete the history", "delete my history", "clear history")
```

with:

```kotlin
    private val DELETE_HISTORY = setOf("delete history", "delete the history", "delete my history", "clear history")
    private val NEW_CONVERSATION = setOf("new conversation", "a new conversation", "start a new conversation", "start new conversation")

    // How "continue the conversation about X" may start (design 9.6 of sub-project 7), word by word.
    private val CONTINUE_PAST = listOf(
        listOf("continue", "the", "conversation"),
        listOf("continue", "our", "conversation"),
        listOf("go", "back", "to", "the", "conversation"),
        listOf("go", "back", "to", "our", "conversation"),
    )
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt`, replace:

```kotlin
        Request.Name, Request.WakeUp, Request.PauseListening, Request.TurnOff, Request.OpenSettings, is Request.Call, is Request.Other -> null
```

with:

```kotlin
        Request.NewConversation -> InputIntent.NewConversation
        is Request.ContinuePast -> InputIntent.ContinuePast(request.topic)
        Request.Name, Request.WakeUp, Request.PauseListening, Request.TurnOff, Request.OpenSettings, is Request.Call, is Request.Other -> null
    }

    /**
     * What a typed [text] asks of the conversation (design 12.2 of sub-project 7). A typed text has no recogniser, so only
     * the exact name, in any letter case and with any punctuation, counts as the name, and only at the start or among the
     * words of a stop. Kestrel's own requests of the conversation need no name: "stop" and "cancel" (with the words of a
     * spoken stop, such as "Stop it, Kestrel"), "continue", "new conversation", "continue the conversation about …",
     * "delete history", "remember that …", "forget …" and "save that as …". Anything else is a command with the owner's
     * words after the name, also the requests that only the voice reads, such as "wake up", "turn off" and "never mind",
     * which may answer a question; null for a text without words.
     */
    fun typed(text: String): InputIntent? {
        val words = words(text)
        if (words.isEmpty()) return null
        val rest = words.drop(exactNameAt(words, 0))
        if (rest.isEmpty()) return InputIntent.Command(text.trim())
        val request = if (readStop(rest, ::exactNameAt) != null) Request.Stop(named = true) else conversational(text, rest)
        return request?.let(::intent) ?: InputIntent.Command(text.substring(rest.first().range.first).trim())
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt`, replace:

```kotlin
        readStop(words, limit)?.let { return if (afterName) Request.Stop(named = true) else it }
```

with:

```kotlin
        readStop(words) { at, start -> nameAt(at, start, limit) }?.let { return if (afterName) Request.Stop(named = true) else it }
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt`, replace:

```kotlin
    /** A stop when [words] are "stop" or "cancel" with only fillers and names; it is named when a name is among them. */
    private fun readStop(words: List<Word>, limit: Int): Request.Stop? {
        if (words.none { it.text in STOP_WORDS }) return null
        var named = false
        var i = 0
        while (i < words.size) {
            if (words[i].text in STOP_WORDS || words[i].text in FILLERS) {
                i++
                continue
            }
            val name = nameAt(words, i, limit)
```

with:

```kotlin
    /**
     * A stop when [words] are "stop" or "cancel" with only fillers and names; it is named when a name is among them.
     * [names] gives how many words make the name at an index, or 0: heard for the voice, exact for a typed text.
     */
    private fun readStop(words: List<Word>, names: (List<Word>, Int) -> Int): Request.Stop? {
        if (words.none { it.text in STOP_WORDS }) return null
        var named = false
        var i = 0
        while (i < words.size) {
            if (words[i].text in STOP_WORDS || words[i].text in FILLERS) {
                i++
                continue
            }
            val name = names(words, i)
```

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt`, replace:

```kotlin
        if (readStop(rest, limit) != null) return Request.Stop(named = true)
        val said = phrase(rest)
        val fromWakeUp = wakeUpDistance(said)
        return when {
            said == "stop listening" -> Request.PauseListening
            turnOffAlone && said in TURN_OFF -> Request.TurnOff
            said in OPEN_SETTINGS -> Request.OpenSettings
            said == CONTINUE -> Request.Continue
            said in DELETE_HISTORY -> Request.DeleteHistory
            fromWakeUp <= 1 -> Request.WakeUp
            // "Forget [something]" stays a task, because "Kestrel, forget [fact]" is a command (Appendix A).
            afterName && isNeverMind(rest) -> Request.Stop(named = true)
            else -> memory(transcript, rest)
                ?: Request.Task(transcript.substring(rest.first().range.first).trim(), nearWakeUp = fromWakeUp == 2)
        }
```

with:

```kotlin
        if (readStop(rest) { at, start -> nameAt(at, start, limit) } != null) return Request.Stop(named = true)
        val said = phrase(rest)
        val fromWakeUp = wakeUpDistance(said)
        return when {
            said == "stop listening" -> Request.PauseListening
            turnOffAlone && said in TURN_OFF -> Request.TurnOff
            said in OPEN_SETTINGS -> Request.OpenSettings
            fromWakeUp <= 1 -> Request.WakeUp
            // "Forget [something]" stays a task, because "Kestrel, forget [fact]" is a command (Appendix A).
            afterName && isNeverMind(rest) -> Request.Stop(named = true)
            else -> conversational(transcript, rest)
                ?: Request.Task(transcript.substring(rest.first().range.first).trim(), nearWakeUp = fromWakeUp == 2)
        }
    }

    /**
     * Kestrel's own requests of the conversation in [rest] (design 5.5 and 12.2 of sub-project 7), which the voice reads
     * after the name and a typed text reads without it: "continue", "delete history", "new conversation", a memory request
     * or continuing a past conversation; null for anything else.
     */
    private fun conversational(transcript: String, rest: List<Word>): Request? {
        val said = phrase(rest)
        return when {
            said == CONTINUE -> Request.Continue
            said in DELETE_HISTORY -> Request.DeleteHistory
            said in NEW_CONVERSATION -> Request.NewConversation
            else -> memory(transcript, rest) ?: past(transcript, rest)
        }
    }

    /**
     * "continue the conversation", "continue our conversation" or "go back to the conversation", alone or with "about
     * <topic>" in the owner's own words (design 9.6 of sub-project 7); any other form is no such request.
     */
    private fun past(transcript: String, rest: List<Word>): Request.ContinuePast? {
        val words = rest.filter { it.text !in POLITE }
        val start = CONTINUE_PAST.firstOrNull { form -> words.size >= form.size && words.take(form.size).map { it.text } == form } ?: return null
        val after = words.drop(start.size)
        if (after.isEmpty()) return Request.ContinuePast(null)
        if (after.first().text != "about" || after.size < 2) return null
        val topic = transcript.substring(after[1].range.first, after.last().range.last + 1).trim()
        return Request.ContinuePast(topic)
```

Moving "continue", "delete history" and "new conversation" after "wake up" and "never mind" changes nothing that the voice reads: none of them is within one word of "wake up" or a "never mind".

In `app/src/main/kotlin/dev/kestrel/app/orchestrator/Requests.kt`, replace:

```kotlin
        start + 1 < words.size && isName(words[start].text + words[start + 1].text, limit) -> 2
        else -> 0
    }
```

with:

```kotlin
        start + 1 < words.size && isName(words[start].text + words[start + 1].text, limit) -> 2
        else -> 0
    }

    /** 1 when the word at [start] is the name exactly, as a typed text must write it, or 0. */
    private fun exactNameAt(words: List<Word>, start: Int): Int = if (start < words.size && words[start].text == NAME) 1 else 0
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.orchestrator.RequestsTest --tests=dev.kestrel.app.orchestrator.RulesTest`
Expected: PASS.

- [ ] **Step 5: Run `:app`'s JVM tests, count them and stop Gradle**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest :app:lintDebug`, then `$n = 0; Get-ChildItem app\build\test-results\testDebugUnitTest -Filter *.xml | ForEach-Object { $n += [int]([xml](Get-Content $_.FullName -Raw)).testsuite.tests }; "app $n"`, then `scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL; `app` A₄ + 6.

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "feat(app): a new conversation and a past one by voice, and the typed reading (plan 7D)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: The hooks `conversation-state` and `conversation-control`

Design 14.4 of sub-project 7: `conversation-state` reads the open conversation in counts and kinds only (`open`, `continued`, `commands`, `goal`, the quiet clock with the seconds left while it runs, `replyChannel`, `testMode`), and `conversation-control --es do new|expire|limits|restore-check` starts a new conversation as "Kestrel, new conversation" does, ends the quiet time at once so that a test need not wait, sets a round's limits, and says what the restore at the process's start found. `do=limits` sets the page's smallest limits, or with `--es limits <replies>/<minutes>/<credits>` the given ones, each within the page's bounds, and answers the stored ones as `was=`, so that a check can put them back (see "Decisions for the controller", item 2). `voice-state` gains `followUp=open|closed`, and `agent-state` gains `checklist=<done>/<total>` and `conversation=open|closed`. The answers' new keys come after the keys that `:tools:eval` and the scripts read today, except `followUp=`, which follows `recognition=`, so that the check of `VoiceHookTest` that reads the start of the line stays as it is. agent-state's line is built in `AgentHookAnswers.state`, apart from Android, so that a JVM test reads its new keys (Task 6's review M1).

**Files:**
- Create: `app/src/debug/kotlin/dev/kestrel/app/debug/ConversationHookAnswers.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/ConversationHooks.kt`
- Modify: `app/src/debug/kotlin/dev/kestrel/app/debug/TestHooks.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHookAnswers.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`
- Test: create `app/src/testDebug/kotlin/dev/kestrel/app/debug/ConversationHookAnswersTest.kt` and `app/src/androidTest/kotlin/dev/kestrel/app/debug/ConversationHooksTest.kt`; modify `app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt` and `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceHookTest.kt`

**Interfaces:**
- Consumes: Task 3's `AppGraph.conversations`, `InputIds` and `AgentTaskHooks.state`; Task 4's `Work.FOLLOWING_UP`; plan 7B's `ConversationView`, `ChecklistSummary`, `QuietClock`, `RestoreResult`, `ConversationEngine.submit`, `endQuietTime` and `restored`, and `ConversationSettings` with `quietMillis`, `roundLimits`, `REPLIES`, `WORK_MINUTES` and `CREDITS`; plan 7C's `SettingKeys.CONVERSATIONS`.
- Produces: the hooks `CONVERSATION_STATE` and `CONVERSATION_CONTROL` (`internal class ConversationHooks(graph: AppGraph)` with `COMMANDS`); `internal object ConversationHookAnswers` with `state(view, settings, now): String`, `checklist(summary: ChecklistSummary?): String`, `limits(settings, text: String?): ConversationSettings?`, `limitsText(limits: GoalLimits): String` and `restored(restored: RestoreResult?): String`; `voice-state`'s `followUp=`; `agent-state`'s `checklist=` and `conversation=`, from `AgentHookAnswers.Open(handOver, confirming, question)` and `AgentHookAnswers.state(number, view, work, open): String`, which `AgentTaskHooks.state` answers. Plan 7G adds the hooks to `scripts\hook.ps1` (see "Decisions for the controller", item 5).

Counts: `:app` JVM A₅ + 3 (`ConversationHookAnswersTest` 2, `AgentHookAnswersTest` + 1). Device tests: AD₀ + 2 + 2 = AD₀ + 4 (`ConversationHooksTest` 2; `VoiceHookTest` changes a pattern).

- [ ] **Step 1: Write the failing tests**

Create `app/src/testDebug/kotlin/dev/kestrel/app/debug/ConversationHookAnswersTest.kt`:

```kotlin
package dev.kestrel.app.debug

import dev.kestrel.agent.goal.GoalLimits
import dev.kestrel.conversation.engine.ChecklistSummary
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.engine.RestoreResult
import dev.kestrel.conversation.machine.HoldReason
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSettings
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.InputChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationHookAnswersTest {
    @Test
    fun theStateCarriesCountsAndKindsAndNeverWords() {
        val settings = ConversationSettings(quietSeconds = 60)
        val now = 1_000_000L
        val open = ConversationView(
            id = ConversationId.random(),
            command = 3,
            goal = GoalPhase.PAUSED_AT_LIMIT,
            replyChannel = InputChannel.TEXT,
            checklist = ChecklistSummary(3, 7, "write to Mila"),
            records = listOf(CommandRecord(1, now, InputChannel.VOICE, "open the secret file"), CommandRecord(2, now, InputChannel.TEXT, "send it")),
            continuedAtWall = now - 5_000,
            testMode = false,
            quiet = QuietClock.Running(2, now - 20_500),
        )

        val state = ConversationHookAnswers.state(open, settings, now)

        assertEquals(
            "open=yes continued=yes commands=2 goal=paused-at-limit quiet=running quietLeft=40 replyChannel=text testMode=off",
            state,
        )
        assertFalse(state, state.contains("secret"))
        assertEquals(
            "open=no continued=no commands=0 goal=none quiet=off quietLeft=none replyChannel=voice testMode=on",
            ConversationHookAnswers.state(ConversationView(), settings, now),
        )
        val held = open.copy(quiet = QuietClock.Held(HoldReason.REPLYING))
        assertTrue(ConversationHookAnswers.state(held, settings, now).contains(" quiet=held quietLeft=none "))
        assertEquals("3/7", ConversationHookAnswers.checklist(open.checklist))
        assertEquals("none", ConversationHookAnswers.checklist(null))
        assertEquals("goal-held", ConversationHookAnswers.restored(RestoreResult.GOAL_HELD))
        assertEquals("pending", ConversationHookAnswers.restored(null))
    }

    @Test
    fun theLimitsAreThePagesSmallestOrTheGivenOnesWithinThePagesBounds() {
        val settings = ConversationSettings()

        assertEquals(GoalLimits(50, 5, 10), ConversationHookAnswers.limits(settings, null)!!.limits)
        assertEquals(GoalLimits(100, 10, 20), ConversationHookAnswers.limits(settings, "100/10/20")!!.limits)
        assertEquals(GoalLimits(50, 5, 10), ConversationHookAnswers.limits(settings, "1/1/1")!!.limits)
        assertEquals(GoalLimits(2_000, 240, 5_000), ConversationHookAnswers.limits(settings, "9999/999/99999")!!.limits)
        assertNull(ConversationHookAnswers.limits(settings, "1/2"))
        assertNull(ConversationHookAnswers.limits(settings, "a/b/c"))
        // A check puts the limits back with the text that the hook answered as was=.
        assertEquals("500/60/1000", ConversationHookAnswers.limitsText(settings.roundLimits))
        assertEquals(settings, ConversationHookAnswers.limits(settings, ConversationHookAnswers.limitsText(settings.roundLimits)))
    }
}
```

Create `app/src/androidTest/kotlin/dev/kestrel/app/debug/ConversationHooksTest.kt`:

```kotlin
package dev.kestrel.app.debug

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.agent.goal.GoalLimits
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.testing.ScriptedModel.Companion.call
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.agent.AgentTests
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.conversation.machine.QuietClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The conversation's hooks of debug builds (design 14.4 of sub-project 7), called as TestHookReceiver calls them. */
class ConversationHooksTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val conversations = AgentTests.graph.conversations

    @After
    fun stop() {
        AgentTests.stop()
    }

    @Test
    fun theStateCountsACommandAndExpireClosesTheConversation() {
        AgentTests.start()
        val model = ScriptedModel(listOf(call("fail", null, null, null, "reason" to "I stop here.")))
        AgentTests.ended(AgentTests.run("open the settings", model))
        // The quiet time runs once Kestrel has spoken the reply; only then can the hook end it.
        check(VoiceTests.waitUntil(30_000) { conversations.view.value.quiet is QuietClock.Running }) { "The quiet time did not run" }

        val open = hook("CONVERSATION_STATE")
        assertTrue(open, Regex("open=yes continued=no commands=1 goal=none quiet=running quietLeft=\\d+ replyChannel=voice testMode=(on|off)").matches(open))
        assertEquals("expire=sent", hook("CONVERSATION_CONTROL", "do" to "expire"))
        check(VoiceTests.waitUntil(10_000) { conversations.view.value.id == null }) { "The conversation did not close" }
        val closed = hook("CONVERSATION_STATE")
        assertTrue(closed, Regex("open=no continued=no commands=0 goal=none quiet=off quietLeft=none replyChannel=(voice|text) testMode=(on|off)").matches(closed))
    }

    @Test
    fun theLimitsGoBackAndTheControlsCarryNoWords() {
        val settings = AgentTests.graph.settings
        val stored = runBlocking { settings.get(SettingKeys.CONVERSATIONS) }
        val was = ConversationHookAnswers.limitsText(stored.roundLimits)
        try {
            assertEquals("limits=50/5/10 was=$was", hook("CONVERSATION_CONTROL", "do" to "limits"))
            assertEquals(GoalLimits(50, 5, 10), runBlocking { settings.get(SettingKeys.CONVERSATIONS) }.roundLimits)
            assertEquals("limits=$was was=50/5/10", hook("CONVERSATION_CONTROL", "do" to "limits", "limits" to was))
            assertEquals(stored, runBlocking { settings.get(SettingKeys.CONVERSATIONS) })

            val restored = hook("CONVERSATION_CONTROL", "do" to "restore-check")
            assertTrue(restored, Regex("restored=(nothing|open|closed|goal-held|goal-ended)").matches(restored))
            assertEquals("error: add --es do with one of new, expire, limits or restore-check", hook("CONVERSATION_CONTROL", "do" to "other"))
        } finally {
            runBlocking { settings.set(SettingKeys.CONVERSATIONS, stored) }
        }
    }

    private fun hook(command: String, vararg extras: Pair<String, String>): String {
        val intent = Intent(TestHooks.ACTION_PREFIX + command)
        for ((key, value) in extras) intent.putExtra(key, value)
        return runBlocking { TestHooks(AgentTests.graph, context).run(intent) }.text
    }
}
```

In `app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt`, replace:

```kotlin
import dev.kestrel.app.agent.TaskEvidence
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.safety.Category
import dev.kestrel.safety.EmergencyGuard
```

with:

```kotlin
import dev.kestrel.app.agent.TaskEvidence
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.engine.ChecklistSummary
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.safety.Category
import dev.kestrel.safety.EmergencyGuard
import dev.kestrel.safety.HandOverKind
```

In `app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt`, replace:

```kotlin
    @Test
    fun anAnswerIsOneOfTheOwnersWordsANumberOrAText() {
```

with:

```kotlin
    @Test
    fun theStateGivesTheChecklistOnlyForTheCurrentRoundAndWhetherAConversationIsOpen() {
        val acting = ConversationView(id = ConversationId.random(), command = 3, goal = GoalPhase.ACTING, checklist = ChecklistSummary(2, 5, "write to Mila"))

        val line = AgentHookAnswers.state(3, acting, Work.IDLE, AgentHookAnswers.Open())

        assertEquals("phase=running asking=none work=idle handOver=none confirming=none question=none checklist=2/5 conversation=open", line)
        assertFalse(line, line.contains("Mila"))
        // An earlier round of the open conversation has ended, and its line names nothing of the current round.
        val confirming = acting.copy(goal = GoalPhase.ASKING, asking = QuestionKind.CONFIRM)
        val open = AgentHookAnswers.Open(HandOverKind.CALL, Category.SENDING, QuestionKind.CONFIRM)
        assertEquals(
            "phase=ended asking=none work=awaiting-task handOver=none confirming=none question=none checklist=none conversation=open",
            AgentHookAnswers.state(2, confirming, Work.AWAITING_TASK, open),
        )
        assertEquals(
            "phase=running asking=confirm work=awaiting-task handOver=CALL confirming=sending question=none checklist=2/5 conversation=open",
            AgentHookAnswers.state(3, confirming, Work.AWAITING_TASK, open),
        )
        assertEquals(
            "phase=paused-at-limit asking=none work=idle handOver=none confirming=none question=none checklist=2/5 conversation=open",
            AgentHookAnswers.state(3, acting.copy(goal = GoalPhase.PAUSED_AT_LIMIT), Work.IDLE, AgentHookAnswers.Open()),
        )
        assertEquals(
            "phase=ended asking=none work=idle handOver=none confirming=none question=none checklist=none conversation=closed",
            AgentHookAnswers.state(3, ConversationView(), Work.IDLE, AgentHookAnswers.Open()),
        )
    }

    @Test
    fun anAnswerIsOneOfTheOwnersWordsANumberOrAText() {
```

In `app/src/androidTest/kotlin/dev/kestrel/app/voice/VoiceHookTest.kt`, replace:

```kotlin
            "mode=off work=idle speaking=false microphone=false recognition=named screenOn=(true|false) micBusy=false " +
```

with:

```kotlin
            "mode=off work=idle speaking=false microphone=false recognition=named followUp=closed screenOn=(true|false) micBusy=false " +
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.debug.ConversationHookAnswersTest`
Expected: FAIL, the build does not compile: `ConversationHookAnswers` and `AgentHookAnswers.state` do not exist yet.

- [ ] **Step 3: The answers and the hooks**

Create `app/src/debug/kotlin/dev/kestrel/app/debug/ConversationHookAnswers.kt`:

```kotlin
package dev.kestrel.app.debug

import dev.kestrel.agent.goal.GoalLimits
import dev.kestrel.conversation.engine.ChecklistSummary
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.engine.RestoreResult
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.model.ConversationSettings

/**
 * The answers of the conversation's hooks (design 14.4 of sub-project 7), apart from Android so that JVM tests check
 * them. They carry counts and kinds only, never words.
 */
internal object ConversationHookAnswers {
    /**
     * conversation-state: whether a conversation is open and was continued, how many commands it holds, its goal's phase,
     * its quiet clock with the whole seconds left while the clock runs, the channel of the owner's latest input and the
     * conversation's test mode. [now] is the wall clock.
     */
    fun state(view: ConversationView, settings: ConversationSettings, now: Long): String {
        val (quiet, left) = when (val clock = view.quiet) {
            QuietClock.Off -> "off" to "none"
            is QuietClock.Held -> "held" to "none"
            is QuietClock.Running -> "running" to seconds(settings.quietMillis - (now - clock.sinceWall)).toString()
        }
        return "open=${yesNo(view.id != null)} continued=${yesNo(view.continuedAtWall != null)} commands=${view.records.size} " +
            "goal=${view.goal?.let { it.name.lowercase().replace('_', '-') } ?: "none"} quiet=$quiet quietLeft=$left " +
            "replyChannel=${view.replyChannel.name.lowercase()} testMode=${if (view.testMode) "on" else "off"}"
    }

    /** agent-state's `checklist=`: the goal's done and total items, as "3/7", or none without a checklist. */
    fun checklist(summary: ChecklistSummary?): String = summary?.let { "${it.done}/${it.total}" } ?: "none"

    /**
     * [settings] with a round's limits from [text], "<replies>/<minutes>/<credits>", or the page's smallest without a text,
     * each within the page's bounds (plan 7D's decision 2); null for a text of another form.
     */
    fun limits(settings: ConversationSettings, text: String?): ConversationSettings? {
        val wanted = if (text == null) {
            listOf(ConversationSettings.REPLIES.first, ConversationSettings.WORK_MINUTES.first, ConversationSettings.CREDITS.first)
        } else {
            text.split('/').map { it.trim().toIntOrNull() ?: return null }.takeIf { it.size == 3 } ?: return null
        }
        val limits = GoalLimits(
            wanted[0].coerceIn(ConversationSettings.REPLIES),
            wanted[1].coerceIn(ConversationSettings.WORK_MINUTES),
            wanted[2].coerceIn(ConversationSettings.CREDITS),
        )
        return settings.copy(limits = limits)
    }

    /** A round's limits as conversation-control answers them, and as it reads them back: "<replies>/<minutes>/<credits>". */
    fun limitsText(limits: GoalLimits): String = "${limits.replies}/${limits.workMinutes}/${limits.credits}"

    /** What the restore at the process's start found, as "goal-held"; pending before it has run. */
    fun restored(restored: RestoreResult?): String = restored?.name?.lowercase()?.replace('_', '-') ?: "pending"

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

    // Whole seconds, rounded up, and never below 0.
    private fun seconds(millis: Long): Long = (millis.coerceAtLeast(0) + 999) / 1000
}
```

Create `app/src/debug/kotlin/dev/kestrel/app/debug/ConversationHooks.kt`:

```kotlin
package dev.kestrel.app.debug

import android.content.Intent
import dev.kestrel.app.AppGraph
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.conversation.InputIds
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput

/**
 * The conversation's hooks (design 14.4 of sub-project 7): conversation-state reads the open conversation, and
 * conversation-control starts a new one, ends the quiet time, sets a round's limits or says what the restore found. The
 * answers never contain words.
 */
internal class ConversationHooks(private val graph: AppGraph) {
    suspend fun run(command: String, intent: Intent): String = when (command) {
        "CONVERSATION_STATE" ->
            ConversationHookAnswers.state(graph.conversations.view.value, graph.settings.get(SettingKeys.CONVERSATIONS), System.currentTimeMillis())
        "CONVERSATION_CONTROL" -> control(intent)
        else -> throw HookError("unknown command '$command'")
    }

    private suspend fun control(intent: Intent): String = when (intent.getStringExtra(EXTRA_DO)) {
        // As the owner's "Kestrel, new conversation" (design 6.8 of sub-project 7): while a goal is open, Kestrel says that
        // it must stop first.
        "new" -> {
            graph.conversations.submit(OwnerInput(InputIds.next(), InputChannel.VOICE, InputIntent.NewConversation, System.currentTimeMillis()))
            "new=sent"
        }
        // The quiet time ends at once if its clock runs, so that a test need not wait for it.
        "expire" -> {
            graph.conversations.endQuietTime()
            "expire=sent"
        }
        "limits" -> limits(intent.getStringExtra(EXTRA_LIMITS))
        "restore-check" -> "restored=${ConversationHookAnswers.restored(graph.conversations.restored)}"
        else -> throw HookError("add --es do with one of new, expire, limits or restore-check")
    }

    // The page's smallest limits, or with --es limits those that a check stored before, so that it can put them back.
    private suspend fun limits(text: String?): String {
        val stored = graph.settings.get(SettingKeys.CONVERSATIONS)
        val changed = ConversationHookAnswers.limits(stored, text)
            ?: throw HookError("add --es limits <replies>/<minutes>/<credits>, or no limits for the page's smallest")
        graph.settings.set(SettingKeys.CONVERSATIONS, changed)
        return "limits=${ConversationHookAnswers.limitsText(changed.roundLimits)} was=${ConversationHookAnswers.limitsText(stored.roundLimits)}"
    }

    companion object {
        val COMMANDS = setOf("CONVERSATION_STATE", "CONVERSATION_CONTROL")
        private const val EXTRA_DO = "do"
        private const val EXTRA_LIMITS = "limits"
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
        if (command in ConversationHooks.COMMANDS) return ConversationHooks(graph).run(command, intent)
```

- [ ] **Step 4: The new keys of `voice-state` and `agent-state`**

In `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt`, replace:

```kotlin
    // so that a check of the A71 sessions sees how many commands started after its own.
```

with:

```kotlin
    // so that a check of the A71 sessions sees how many commands started after its own. followUp= says whether the
    // follow-up window is open (design 6.6 and 14.4 of sub-project 7).
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt`, replace:

```kotlin
            "screenOn=${state.screenOn} micBusy=${state.micBusy} command=$number kind=$kind outcome=$outcome$latest " +
```

with:

```kotlin
            "followUp=${if (state.work == Work.FOLLOWING_UP) "open" else "closed"} screenOn=${state.screenOn} micBusy=${state.micBusy} command=$number kind=$kind outcome=$outcome$latest " +
```

agent-state's line moves into `AgentHookAnswers.state`, so that a JVM test reads its keys (Task 6's review M1).

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHookAnswers.kt`, replace:

```kotlin
import dev.kestrel.app.agent.TaskEvidence
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.Category
import dev.kestrel.safety.EmergencyGuard
```

with:

```kotlin
import dev.kestrel.app.agent.TaskEvidence
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.Category
import dev.kestrel.safety.EmergencyGuard
import dev.kestrel.safety.HandOverKind
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHookAnswers.kt`, replace:

```kotlin
        QuestionKind.CONFIRM, QuestionKind.CONTINUE_GOAL, QuestionKind.SWITCH, QuestionKind.DELETE_HISTORY -> "none"
    }
```

with:

```kotlin
        QuestionKind.CONFIRM, QuestionKind.CONTINUE_GOAL, QuestionKind.SWITCH, QuestionKind.DELETE_HISTORY -> "none"
    }

    /**
     * What the conversation's owner holds open now (plan 7B's `ConversationOwner`): a hand-over's kind, a confirmation's
     * category and the kind that the agent asked its question with.
     */
    data class Open(val handOver: HandOverKind? = null, val confirming: Category? = null, val question: QuestionKind? = null)

    /**
     * agent-state's answer for the conversation's command [number] (design 14.4 of sub-project 7). The command is current
     * while it is the goal's current round, or the conversation's own request that asks now; otherwise its phase is
     * `ended`, and its question, hand-over, confirmation and checklist are `none`. `work=` is the listener's [work], and
     * `conversation=` says whether a conversation is open.
     */
    fun state(number: Int, view: ConversationView, work: Work, open: Open): String {
        val current = view.command == number && (view.goal != null || view.asking != null)
        val phase = if (current) phase(view.goal) else "ended"
        val asking = view.asking?.takeIf { current }?.let(AgentHookAnswers::asking) ?: "none"
        val handOver = open.handOver?.takeIf { current }?.name ?: "none"
        // The category of the open confirmation, so that :tools:eval confirms only the kind that its task names (design D28).
        val confirming = open.confirming?.takeIf { current }?.wire ?: "none"
        // The open question's source, so that :tools:eval answers look_closer's screenshot question apart (plan 6B-basefix).
        val question = open.question?.takeIf { current }?.let(AgentHookAnswers::question) ?: "none"
        // The goal's checklist in counts, and whether a conversation is open (design 14.4 of sub-project 7).
        val checklist = ConversationHookAnswers.checklist(view.checklist?.takeIf { current })
        val conversation = if (view.id != null) "open" else "closed"
        return "phase=$phase asking=$asking work=${work.name.lowercase().replace('_', '-')} handOver=$handOver confirming=$confirming question=$question " +
            "checklist=$checklist conversation=$conversation"
    }

    // A goal's phase in agent-state's words; the conversation's own question has no goal and counts as running.
    private fun phase(goal: GoalPhase?): String = when (goal) {
        GoalPhase.HANDED_OVER -> "handed-over"
        GoalPhase.PAUSED -> "paused"
        GoalPhase.PAUSED_AT_LIMIT -> "paused-at-limit"
        GoalPhase.INTERRUPTED -> "interrupted"
        GoalPhase.ACTING, GoalPhase.ASKING, null -> "running"
    }
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, replace:

```kotlin
        val (_, number) = taggedTask(intent)
        val state = graph.listening.state.value
        val view = graph.conversations.view.value
        val owner = graph.conversations.owner
        // The tag's command is the goal's current round, or the conversation's own request that asks now.
        val current = view.command == number && (view.goal != null || view.asking != null)
        val phase = if (!current) "ended" else phaseOf(view)
        val asking = view.asking?.takeIf { current }?.let(AgentHookAnswers::asking) ?: "none"
        val handOver = owner.handOverKind?.takeIf { current }?.name ?: "none"
        // The category of the open confirmation, so that :tools:eval confirms only the kind that its task names (design D28).
        val confirming = owner.confirming?.takeIf { current }?.wire ?: "none"
        // The open question's source, so that :tools:eval answers look_closer's screenshot question apart (plan 6B-basefix).
        val question = owner.asking?.takeIf { current }?.let(AgentHookAnswers::question) ?: "none"
        return "phase=$phase asking=$asking work=${state.work.name.lowercase().replace('_', '-')} handOver=$handOver confirming=$confirming question=$question"
    }
```

with:

```kotlin
        val (_, number) = taggedTask(intent)
        val owner = graph.conversations.owner
        val open = AgentHookAnswers.Open(owner.handOverKind, owner.confirming, owner.asking)
        return AgentHookAnswers.state(number, graph.conversations.view.value, graph.listening.state.value.work, open)
    }
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, replace:

```kotlin
    // A goal's phase in agent-state's words; the conversation's own question has no goal and counts as running.
    private fun phaseOf(view: ConversationView): String = when (view.goal) {
        GoalPhase.HANDED_OVER -> "handed-over"
        GoalPhase.PAUSED -> "paused"
        GoalPhase.PAUSED_AT_LIMIT -> "paused-at-limit"
        GoalPhase.INTERRUPTED -> "interrupted"
        GoalPhase.ACTING, GoalPhase.ASKING, null -> "running"
    }

    private fun signs(rule: String?): String = when (rule) {
```

with:

```kotlin
    private fun signs(rule: String?): String = when (rule) {
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, replace:

```kotlin
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.GoalPhase
```

with:

```kotlin
import dev.kestrel.conversation.model.GoalPhase
```

- [ ] **Step 5: Run the tests to see them pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests=dev.kestrel.app.debug.*`
Expected: PASS.

- [ ] **Step 6: Run `:app`'s JVM tests, compile the device tests, count them and stop Gradle**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug`, then `$n = 0; Get-ChildItem app\build\test-results\testDebugUnitTest -Filter *.xml | ForEach-Object { $n += [int]([xml](Get-Content $_.FullName -Raw)).testsuite.tests }; "app $n"`, then `"device $(@(Get-ChildItem app\src\androidTest -Recurse -Filter *.kt | Select-String -Pattern '@Test\b').Count)"`, then `scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL; `app` A₅ + 3; `device` AD₀ + 4. Task 8 runs the device tests.

- [ ] **Step 7: Commit**

```bash
git add app/src
git commit -m "feat(app): the hooks conversation-state and conversation-control, and the hooks' new keys (plan 7D)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 7: The settings page's "Delete history" through the conversation

Design 9.7 of sub-project 7, as the controller settled it on 2026-11-24: the settings page's "Delete history" deletes what "Kestrel, delete history" deletes after a yes: both logs, every past conversation with the index of past conversations and the archive's key `kestrel.conversations`, and the open conversation, which closes. The page asks its own question, as today, now with the design's words ("Delete the activity log, the network log and past conversations?"), and the conversation asks nothing more and says nothing on a channel. While a goal is open or one of Kestrel's own requests runs, the conversation leaves everything alone, as it does for "delete history" by voice, and the page says why. Plan 7F adds only the typed "delete history".

This is the one task of this plan that changes `:core:conversation`: plan 7B's engine had no way in for a deletion that the owner already confirmed. `LocalRequests.deleteAll(stored)` is the deletion that both ways share: once the engine's writer has stored every write queued before it (plan 7B's barrier, `stored()`), it deletes through the store both logs, every conversation and the index of past conversations in the archive's one transaction, and then the archive's key (plan 7C, `ae33cfda`). `ConversationEngine.deleteHistory()` closes the open conversation at once through the machine's new `ConversationEvent.HistoryDeleted`, unkept, and runs that deletion beside the loop, as "delete history" by voice runs it, so that the loop never waits for the store. Until the deletion has ended, no write reaches the engine's writer: the open conversation's changes wait as during a switch, and the closes and the switches wait in their order. So a save that was asked before the request and still waits is deleted too, and a command after the request opens a conversation that stays, also when it closes before the deletion ended. While a switch runs, the request is refused too, because the switch is the end of one of Kestrel's own requests, whose run lasts until the switch has ended. If handling the request or the end of its deletion fails, the engine still settles both, so that the page never waits for ever and no write stays held. When nothing could be deleted, the engine stores the conversation that the request closed as closed, as any close keeps it.

**Files:**
- Create: `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/HistoryDeletion.kt`
- Modify: `core/conversation/src/main/kotlin/dev/kestrel/conversation/local/LocalRequests.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/ConversationEvent.kt`, `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/ConversationMachine.kt`, `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt`, `app/src/main/kotlin/dev/kestrel/app/settings/LogPages.kt`, `app/src/main/res/values/strings.xml`
- Test: `core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt`, `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`

**Interfaces:**
- Consumes: plan 7B's `ConversationEngine`, `LocalRequests`, `ConversationEvent` and `ConversationMachine`; Task 3's `AppGraph.conversations`.
- Produces: `enum class HistoryDeletion { DELETED, REFUSED, FAILED }` (package `dev.kestrel.conversation.engine`); `suspend fun ConversationEngine.deleteHistory(): HistoryDeletion`; `suspend fun LocalRequests.deleteAll(stored: suspend () -> Unit = {}): Boolean`; `ConversationEvent.HistoryDeleted`; `SettingsData.deleteHistory(): HistoryDeletion`; the strings `logs_delete_refused` and `logs_delete_failed`.

Counts: `:core:conversation` C₀ + 4 + 4 = C₀ + 8 (`ConversationEngineTest` + 4; Task 3 added 4). `:app` JVM: A₆, unchanged. Device tests: AD₀ + 4 + 1 = AD₀ + 5 (`SettingsAppTest` + 1; `MainActivityTest.deleteHistoryWipesBothLogs` runs unchanged through the conversation).

- [ ] **Step 1: Write the failing tests**

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt`, replace:

```kotlin
    @Test
    fun theQuietClockWaitsUntilTheSpokenReplyHasEnded() = scope.runTest {
```

with:

```kotlin
    @Test
    fun theSettingsPagesDeleteHistoryAsksNothingMoreAndLeavesAnOpenGoalAlone() = scope.runTest {
        val engine = engine(environment(ScriptedModel.call("ask_user", null, null, null, "question" to "Which one?"), fail))
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        assertEquals(GoalPhase.ASKING, engine.view.value.goal)

        assertEquals(HistoryDeletion.REFUSED, engine.deleteHistory())
        assertTrue(archive.rows.isNotEmpty())

        engine.submit(input(InputChannel.TEXT, InputIntent.Stop))
        runCurrent()
        assertNull(engine.view.value.goal)
        val asked = text.asked.size + voice.asked.size
        val shown = text.shown.size + voice.shown.size

        assertEquals(HistoryDeletion.DELETED, engine.deleteHistory())
        runCurrent()

        assertNull(engine.view.value.id)
        assertTrue(archive.rows.isEmpty())
        assertTrue(archive.keyErased)
        // The page asked its own question, and shows the result itself.
        assertEquals(asked, text.asked.size + voice.asked.size)
        assertEquals(shown, text.shown.size + voice.shown.size)
    }

    // The engine's writer deletes after every write before the request: a save that waits for the store is deleted too,
    // and a command after the request opens a conversation that stays.
    @Test
    fun theSettingsPagesDeleteHistoryComesAfterTheWritesBeforeItAndKeepsTheCommandsAfterIt() = scope.runTest {
        val gated = GatedStore(store)
        val engine = engine(environment(listSms, finish(1), listSms, finish(1)), gated)
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        val first = engine.view.value.id
        assertNotNull(first)
        val gate = CompletableDeferred<Unit>()
        gated.gate = gate

        // The close's save waits for the store, and the deletion waits behind it.
        engine.submit(input(InputChannel.TEXT, InputIntent.NewConversation))
        runCurrent()
        var deletion: HistoryDeletion? = null
        launch { deletion = engine.deleteHistory() }
        runCurrent()
        engine.submit(typed("Read my newest SMS"))
        runCurrent()
        assertNull(deletion)

        gate.complete(Unit)
        runCurrent()

        assertEquals(HistoryDeletion.DELETED, deletion)
        assertTrue(archive.keyErased)
        val kept = store.open()!!
        assertNotEquals(first, kept.id)
        assertEquals(listOf("Read my newest SMS"), kept.records.map { it.request })
        assertEquals(listOf(kept.id.value), archive.rows.keys.toList())
    }

    // A conversation that a command after the request opens, and that closes while the store still deletes, waits for the
    // deletion with its close, and stays.
    @Test
    fun aConversationThatOpensAndClosesWhileTheHistoryIsDeletedStays() = scope.runTest {
        val gated = GatedStore(store)
        val engine = engine(environment(listSms, finish(1), listSms, finish(1)), gated)
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        val deleting = CompletableDeferred<Unit>()
        gated.deleting = deleting
        var deletion: HistoryDeletion? = null
        launch { deletion = engine.deleteHistory() }
        runCurrent()

        engine.submit(typed("Read my newest SMS"))
        runCurrent()
        val newer = engine.view.value.id!!
        engine.submit(input(InputChannel.TEXT, InputIntent.NewConversation))
        runCurrent()
        assertNull(deletion)

        deleting.complete(Unit)
        runCurrent()

        assertEquals(HistoryDeletion.DELETED, deletion)
        val kept = store.get(newer)!!
        assertFalse(kept.open)
        assertEquals(listOf("Read my newest SMS"), kept.records.map { it.request })
    }

    // A switch that "continue the conversation about …" asked for just before the request is that request's end, after
    // the request itself has ended: the page's request is refused, and the switch ends with the conversation that it opened.
    @Test
    fun theSettingsPagesDeleteHistoryIsRefusedWhileASwitchRuns() = scope.runTest {
        val gated = GatedStore(store)
        val engine = engine(environment(listSms, finish(1)), gated)
        engine.restore()
        engine.submit(typed("Read my latest SMS"))
        runCurrent()
        val past = engine.view.value.id!!
        engine.endQuietTime()
        runCurrent()
        val reopening = CompletableDeferred<Unit>()
        gated.reopenGate = reopening

        engine.submit(input(InputChannel.VOICE, InputIntent.ContinuePast("latest SMS")))
        runCurrent()
        engine.submit(input(InputChannel.VOICE, InputIntent.Answer(ChannelAnswer.Yes)))
        runCurrent()
        assertEquals(HistoryDeletion.REFUSED, engine.deleteHistory())

        reopening.complete(Unit)
        runCurrent()
        assertEquals(past, engine.view.value.id)
        assertEquals(past, store.open()!!.id)
        assertFalse(archive.keyErased)
    }

    @Test
    fun theQuietClockWaitsUntilTheSpokenReplyHasEnded() = scope.runTest {
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt`, replace:

```kotlin
    // A store whose saves wait for [gate] while it is set; [calls] names each save and deletion once it is done.
    private class GatedStore(private val inner: ConversationStore) : ConversationStore by inner {
        var gate: CompletableDeferred<Unit>? = null
        var reopenGate: CompletableDeferred<Unit>? = null
```

with:

```kotlin
    // A store whose saves wait for [gate], whose switches wait for [reopenGate] and whose deletion waits for [deleting],
    // while they are set; [calls] names each save and deletion once it is done.
    private class GatedStore(private val inner: ConversationStore) : ConversationStore by inner {
        var gate: CompletableDeferred<Unit>? = null
        var reopenGate: CompletableDeferred<Unit>? = null
        var deleting: CompletableDeferred<Unit>? = null
```

In `core/conversation/src/test/kotlin/dev/kestrel/conversation/engine/ConversationEngineTest.kt`, replace:

```kotlin
        override suspend fun deleteAll() {
            inner.deleteAll()
            calls += "deleteAll"
```

with:

```kotlin
        override suspend fun deleteAll() {
            deleting?.await()
            inner.deleteAll()
            calls += "deleteAll"
```

In `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, replace:

```kotlin
import dev.kestrel.app.providers.SignInState
```

with:

```kotlin
import dev.kestrel.app.providers.SignInState
import dev.kestrel.conversation.engine.HistoryDeletion
```

In `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, replace:

```kotlin
        compose.onNodeWithText(text(R.string.logs_empty)).assertIsDisplayed()
```

with:

```kotlin
        compose.onNodeWithText(text(R.string.logs_empty)).assertIsDisplayed()
        assertEquals(1, data.deletions)
    }

    @Test
    fun aDeletionThatKestrelLeftAloneSaysWhyAndKeepsTheEntries() {
        data.activity = listOf(LogEntry(1, 1_000, ActivityEvent(ActivityKind.NOTE, "Started")))
        data.deletion = HistoryDeletion.REFUSED
        show(NOTHING_SET_UP)
        compose.onNodeWithText(text(R.string.settings_activity_log)).performClick()

        compose.onNodeWithText(text(R.string.logs_delete)).performClick()
        compose.onNodeWithText(text(R.string.logs_delete_confirm)).performClick()

        compose.onNodeWithText(text(R.string.logs_delete_refused)).assertIsDisplayed()
        compose.onNodeWithText("Started").assertIsDisplayed()
```

In `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, replace:

```kotlin
        var deletions = 0
```

with:

```kotlin
        var deletions = 0
        var deletion = HistoryDeletion.DELETED
```

In `app/src/androidTest/kotlin/dev/kestrel/app/settings/SettingsAppTest.kt`, replace:

```kotlin
        override suspend fun deleteHistory() {
            deletions++
            activity = emptyList()
            network = emptyList()
```

with:

```kotlin
        override suspend fun deleteHistory(): HistoryDeletion {
            deletions++
            if (deletion == HistoryDeletion.DELETED) {
                activity = emptyList()
                network = emptyList()
            }
            return deletion
```

- [ ] **Step 2: Run the test to see it fail**

Run: `scripts\gradle.ps1 :core:conversation:test --tests=dev.kestrel.conversation.engine.ConversationEngineTest`
Expected: FAIL, the build does not compile: `HistoryDeletion` and `ConversationEngine.deleteHistory` do not exist yet.

- [ ] **Step 3: The deletion that both ways share, and the engine's way in**

Create `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/HistoryDeletion.kt`:

```kotlin
package dev.kestrel.conversation.engine

/**
 * How the settings page's "Delete history" ended (design 9.7 of sub-project 7): the history is deleted; the request was
 * left alone, because a goal is open or one of Kestrel's own requests runs, as "delete history" by voice is; or the
 * deletion failed, which the engine's report got.
 */
enum class HistoryDeletion { DELETED, REFUSED, FAILED }
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/local/LocalRequests.kt`, replace:

```kotlin
        return try {
            stored()
            store.deleteAll()
            LocalOutcome.Deleted(ConversationTexts.HISTORY_DELETED)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("Deleting the history", e)
            LocalOutcome.Reply(ConversationTexts.HISTORY_NOT_DELETED)
        }
```

with:

```kotlin
        return if (deleteAll(stored)) LocalOutcome.Deleted(ConversationTexts.HISTORY_DELETED) else LocalOutcome.Reply(ConversationTexts.HISTORY_NOT_DELETED)
    }

    /**
     * Deletes what "delete history" deletes once the owner said yes (design 9.7), after [stored] returned, so that every
     * write of the conversation that the engine queued before is deleted too: through the store, both logs, every
     * conversation and the index of past conversations in the archive's one transaction, and then the archive's key.
     * False when it failed, which [report] gets. The settings page calls it through `ConversationEngine.deleteHistory`,
     * after its own question (plan 7D).
     */
    suspend fun deleteAll(stored: suspend () -> Unit = {}): Boolean = try {
        stored()
        store.deleteAll()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        report("Deleting the history", e)
        false
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/ConversationEvent.kt`, replace:

```kotlin
    data object EndQuietTime : ConversationEvent
```

with:

```kotlin
    data object EndQuietTime : ConversationEvent

    /** The settings page deleted the history after its own question (design 9.7): the open conversation closes, unkept. */
    data object HistoryDeleted : ConversationEvent
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/machine/ConversationMachine.kt`, replace:

```kotlin
            ConversationEvent.EndQuietTime -> if (state.quiet is QuietClock.Running) close(state) else MachineStep(state)
```

with:

```kotlin
            ConversationEvent.EndQuietTime -> if (state.quiet is QuietClock.Running) close(state) else MachineStep(state)
            ConversationEvent.HistoryDeleted -> close(state, keep = false)
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
        class Reopened(val snapshot: ConversationSnapshot?, val chosen: ConversationSnapshot?, val closing: ConversationSnapshot?, val failed: Boolean) : Message
    }
```

with:

```kotlin
        class Reopened(val snapshot: ConversationSnapshot?, val chosen: ConversationSnapshot?, val closing: ConversationSnapshot?, val failed: Boolean) : Message

        /** The settings page's "Delete history" after its own question; [done] gets how it ended. */
        class DeleteHistory(val done: CompletableDeferred<HistoryDeletion>) : Message

        /**
         * The page's deletion ended, [deleted] or not; [closing] is the conversation that the request closed. [settled] once
         * the loop counted the end; only the loop reads and sets it.
         */
        class HistoryDeletionEnded(val deleted: Boolean, val closing: ConversationSnapshot?, val done: CompletableDeferred<HistoryDeletion>) : Message {
            var settled = false
        }
    }
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
    private var switching = false
    private var switchEnd: Message.Reopened? = null
```

with:

```kotlin
    private var switching = false
    private var switchEnd: Message.Reopened? = null

    // While the settings page's "Delete history" runs, no write reaches the writer, so that none comes between the writer's
    // barrier that the deletion waits for and the deletion: the open conversation's changes wait as during a switch
    // ([persist]), and the closes, the switches and their undoing wait in [heldWrites], in their order, until the deletion ended.
    private var deletions = 0
    private val heldWrites = mutableListOf<Write>()
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
    fun endQuietTime() = post(ConversationEvent.EndQuietTime)

    /** How command [command] ended, among the newest [MAX_OUTCOMES]. */
```

with:

```kotlin
    fun endQuietTime() = post(ConversationEvent.EndQuietTime)

    /**
     * The settings page's "Delete history" after the page's own question (design 9.7 of sub-project 7; plan 7D): deletes
     * what a yes to "delete history" deletes and closes the open conversation, with no reply on a channel. While a goal is
     * open or one of Kestrel's own requests runs, it leaves everything alone, as "delete history" by voice does. Waits for
     * [restore].
     */
    suspend fun deleteHistory(): HistoryDeletion {
        val done = CompletableDeferred<HistoryDeletion>()
        inbox.send(Message.DeleteHistory(done))
        return done.await()
    }

    /** How command [command] ended, among the newest [MAX_OUTCOMES]. */
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
                is Message.Reopened -> reopened(message)
            }
```

with:

```kotlin
                is Message.Reopened -> reopened(message)
                is Message.DeleteHistory -> deleteHistory(message.done)
                is Message.HistoryDeletionEnded -> deletionEnded(message)
            }
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
        } catch (e: Exception) {
            report("The conversation", e)
        } finally {
```

with:

```kotlin
        } catch (e: Exception) {
            report("The conversation", e)
            settle(message)
        } finally {
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
    private fun startTimer(effect: ConversationEffect.StartTimer) {
```

with:

```kotlin
    // Design 9.7: the page's question stands for the conversation's. The open conversation closes unkept at once, and the
    // deletion runs beside the loop, as "delete history" by voice does, after the writer's barrier ([stored]): so a save
    // that still waits is deleted too. No write reaches the writer until it ended ([send]), so a command after the request
    // opens a conversation that stays. A switch is the end of one of Kestrel's own requests, whose run lasts until the
    // switch ended, so the request is refused then too. The job starts atomically, so that its end comes back on every path.
    private fun deleteHistory(done: CompletableDeferred<HistoryDeletion>) {
        if (state.goal != null || state.local != null) {
            done.complete(HistoryDeletion.REFUSED)
            return
        }
        try {
            val closing = state.id?.takeIf { it == recordsOf }?.let { snapshot(state, open = false) }
            process(ConversationEvent.HistoryDeleted)
            deletions++
            scope.launch(start = CoroutineStart.ATOMIC) {
                var deleted = false
                try {
                    deleted = local.deleteAll(::stored)
                } finally {
                    inbox.trySend(Message.HistoryDeletionEnded(deleted, closing, done))
                }
            }
        } catch (e: Throwable) {
            done.complete(HistoryDeletion.FAILED)
            throw e
        }
    }

    // When nothing was deleted, the conversation that the request closed stays, closed, as any close keeps it. Then the
    // writes that waited go to the writer in their order, and the open conversation's changes are written.
    private fun deletionEnded(message: Message.HistoryDeletionEnded) {
        if (message.settled) return
        message.settled = true
        deletions--
        try {
            if (!message.deleted) message.closing?.let { send(Write.Save(it, emptyList())) }
            if (deletions == 0) {
                heldWrites.forEach { writes.trySend(it) }
                heldWrites.clear()
            }
        } finally {
            message.done.complete(if (message.deleted) HistoryDeletion.DELETED else HistoryDeletion.FAILED)
        }
        persist()
    }

    // The page's request and the end of its deletion are settled also when their message failed before or while it was
    // handled, so that the page never waits for ever and no write stays held.
    private fun settle(message: Message) {
        try {
            when (message) {
                is Message.DeleteHistory -> message.done.complete(HistoryDeletion.FAILED)
                is Message.HistoryDeletionEnded -> deletionEnded(message)
                else -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("The conversation", e)
        }
    }

    // A close, a switch or its undoing for the writer; while the settings page's "Delete history" runs, it waits in
    // [heldWrites].
    private fun send(write: Write) {
        if (deletions > 0) heldWrites += write else writes.trySend(write)
    }

    private fun startTimer(effect: ConversationEffect.StartTimer) {
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
        switching = true
        writes.trySend(Write.Reopen(id, closing, clock.wallMillis(), state.safetyTestMode))
```

with:

```kotlin
        switching = true
        send(Write.Reopen(id, closing, clock.wallMillis(), state.safetyTestMode))
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
        end.chosen?.let { writes.trySend(Write.Save(it, emptyList())) }
        end.closing?.let { writes.trySend(Write.Save(it.copy(open = true), emptyList())) }
```

with:

```kotlin
        end.chosen?.let { send(Write.Save(it, emptyList())) }
        end.closing?.let { send(Write.Save(it.copy(open = true), emptyList())) }
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
            writes.trySend(if (before.id == effect.id) Write.Save(snapshot(before, open = false), emptyList()) else Write.CloseStored(effect.id))
```

with:

```kotlin
            send(if (before.id == effect.id) Write.Save(snapshot(before, open = false), emptyList()) else Write.CloseStored(effect.id))
```

In `core/conversation/src/main/kotlin/dev/kestrel/conversation/engine/ConversationEngine.kt`, replace:

```kotlin
    // is written, or at once when nothing is written. While a switch runs, the conversation that it closes is not saved.
    private fun persist() {
        val waiting = unsaved.toList()
        unsaved.clear()
        val current = state
        val id = current.id
        if (!dirty || switching || id == null || recordsOf != id) {
            if (!switching) dirty = false
```

with:

```kotlin
    // is written, or at once when nothing is written. While a switch runs, the conversation that it closes is not saved,
    // and while the settings page's "Delete history" runs, nothing is saved until it ended.
    private fun persist() {
        val waiting = unsaved.toList()
        unsaved.clear()
        val current = state
        val id = current.id
        val holding = switching || deletions > 0
        if (!dirty || holding || id == null || recordsOf != id) {
            if (!holding) dirty = false
```

- [ ] **Step 4: Run the conversation's tests to see them pass**

Run: `scripts\gradle.ps1 :core:conversation:test`
Expected: PASS, C₀ + 8 tests; `LocalRequestsTest.deleteHistoryAsksAndDeletesTheLogsTheConversationsAndTheWordsAfterYes` and `ConversationEngineTest.deleteHistoryLeavesNoConversationBehind` pass unchanged.

- [ ] **Step 5: The settings page**

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt`, replace:

```kotlin
import dev.kestrel.app.download.readHardware
```

with:

```kotlin
import dev.kestrel.app.download.readHardware
import dev.kestrel.conversation.engine.HistoryDeletion
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt`, replace:

```kotlin
    /** Wipes both logs (design spec, section 13.5). */
    suspend fun deleteHistory()
```

with:

```kotlin
    /**
     * Deletes the history after the page's own question (design spec, section 13.5; design 9.7 of sub-project 7): both
     * logs, every past conversation with the archive's key, and the open conversation, through the conversation, which
     * leaves everything alone while a goal is open or one of Kestrel's own requests runs.
     */
    suspend fun deleteHistory(): HistoryDeletion
```

In `app/src/main/kotlin/dev/kestrel/app/settings/SettingsData.kt`, replace:

```kotlin
    override suspend fun deleteHistory() {
        graph.logs.deleteHistory()
    }
```

with:

```kotlin
    override suspend fun deleteHistory(): HistoryDeletion = graph.conversations.deleteHistory()
```

In `app/src/main/kotlin/dev/kestrel/app/settings/LogPages.kt`, replace:

```kotlin
import dev.kestrel.app.R
```

with:

```kotlin
import dev.kestrel.app.R
import dev.kestrel.conversation.engine.HistoryDeletion
```

In `app/src/main/kotlin/dev/kestrel/app/settings/LogPages.kt`, replace:

```kotlin
/** A log page: the Back button, buttons that export and delete both logs (design spec, section 13.5), and the entries. */
```

with:

```kotlin
/**
 * A log page: the Back button, buttons that export both logs and delete the history (design spec, section 13.5; design 9.7
 * of sub-project 7), why a deletion did not happen, and the entries.
 */
```

In `app/src/main/kotlin/dev/kestrel/app/settings/LogPages.kt`, replace:

```kotlin
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
```

with:

```kotlin
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    var deletion by rememberSaveable { mutableStateOf<HistoryDeletion?>(null) }
```

In `app/src/main/kotlin/dev/kestrel/app/settings/LogPages.kt`, replace:

```kotlin
            }
        }
```

with:

```kotlin
            }
        }
        when (deletion) {
            HistoryDeletion.REFUSED -> item { Text(stringResource(R.string.logs_delete_refused)) }
            HistoryDeletion.FAILED -> item { Text(stringResource(R.string.logs_delete_failed)) }
            HistoryDeletion.DELETED, null -> Unit
        }
```

In `app/src/main/kotlin/dev/kestrel/app/settings/LogPages.kt`, replace:

```kotlin
                            data.deleteHistory()
```

with:

```kotlin
                            deletion = data.deleteHistory()
```

In `app/src/main/res/values/strings.xml`, replace:

```xml
    <string name="logs_delete_question">Delete the activity log and the network log? This cannot be undone.</string>
    <string name="logs_delete_confirm">Delete</string>
    <string name="logs_delete_cancel">Cancel</string>
```

with:

```xml
    <string name="logs_delete_question">Delete the activity log, the network log and past conversations? This cannot be undone.</string>
    <string name="logs_delete_confirm">Delete</string>
    <string name="logs_delete_cancel">Cancel</string>
    <string name="logs_delete_refused">Kestrel is working on a goal or one of your requests, so it deleted nothing. Stop it first, then delete the history.</string>
    <string name="logs_delete_failed">Kestrel could not delete the history. The activity log says why.</string>
```

- [ ] **Step 6: Run the modules' tests, compile the device tests, count them and stop Gradle**

Run: `scripts\gradle.ps1 :core:conversation:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug`, then `$n = 0; Get-ChildItem app\build\test-results\testDebugUnitTest -Filter *.xml | ForEach-Object { $n += [int]([xml](Get-Content $_.FullName -Raw)).testsuite.tests }; "app $n"`, then `"device $(@(Get-ChildItem app\src\androidTest -Recurse -Filter *.kt | Select-String -Pattern '@Test\b').Count)"`, then `scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL; `app` A₆; `device` AD₀ + 5; `:core:conversation` C₀ + 8 tests pass. Task 8 runs the device tests.

- [ ] **Step 7: Commit**

```bash
git add core/conversation/src app/src
git commit -m "feat(app, conversation): the settings page's Delete history deletes the past conversations through the conversation (plan 7D)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 8: The regression gate on kestrel-api37 (the controller's steps)

Design 14.3 of sub-project 7: after the voice path moved, the agent's and the instant commands' device tests pass on kestrel-api37, every other device test of `:app` passes, and `scripts\eval.ps1`'s dry suite gives the result of the dry suite before this plan. The voice and instant command device tests also cover on the device plan 7B Task 5a's change of `Normalizer` (a folded word folds to itself), which the voice matcher and the instant commands share. This task changes no file in git. It installs this build on kestrel-api37, whose database plan 7C left at version 3, which this build keeps.

**Files:**
- None in git; Step 7 writes `.superpowers\sdd\progress.md`.

**Interfaces:**
- Consumes: every earlier task; `scripts\install.ps1`, `scripts\connected-test.ps1`, `scripts\voice-test.ps1`, `scripts\command-test.ps1`, `scripts\eval.ps1`, `scripts\hook.ps1`; kestrel-api37's part of `eval\baseline.json` (`dfa97763`) and the dry suite that plan 7A noted in `.superpowers\sdd\progress.md` under "## Plan 7A".
- Produces: the gate's result in `.superpowers\sdd\progress.md`, under "## Plan 7D".

Counts: none change. The device tests that run are AD₀ + 5 in all, across Steps 3 to 5.

- [ ] **Step 1: The base**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; if ("$(& $adb -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; "google accounts: $(@(& $adb -s emulator-5554 shell dumpsys account | Select-String 'type=com\.google\}').Count)"; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 -Command provider-standin; git status --short; git rev-parse HEAD`
Expected: the count of Google accounts (note it), `profile=copilot`, no changed file in `app/` or `core/`, and HEAD at Task 7's commit.

- [ ] **Step 2: Install this build on kestrel-api37**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; if ("$(& $adb -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; & $adb -s emulator-5554 logcat -c; scripts\install.ps1 -Serial emulator-5554; scripts\gradle.ps1 --stop; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 -Command state; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 -Command provider-standin; Start-Sleep -Seconds 5; "google accounts: $(@(& $adb -s emulator-5554 shell dumpsys account | Select-String 'type=com\.google\}').Count)"; $crashes = @(& $adb -s emulator-5554 logcat -d | Select-String 'FATAL EXCEPTION' -Context 0,1 | Where-Object { "$($_.Context.PostContext)" -match 'Process: dev\.kestrel,' }); "kestrel crashes: $($crashes.Count)"; $crashes | ForEach-Object { $_.Line; $_.Context.PostContext }`
Expected: a `Success` line of `adb install`, `commit=<the HEAD of Step 1>` and `dirty=0` in the state, still `profile=copilot` (an update keeps Kestrel's data), the count of Step 1, and `kestrel crashes: 0` (the restore of plan 7B's engine runs at Kestrel's start). If the emulator froze at its first voice toggle after the install, as on 2026-11-23 and 2026-11-24, cold-boot it with `scripts\start-emulator.ps1` and check its serial, the accounts and the profile again.

- [ ] **Step 3: The agent's tests, the hooks' tests and every other device test of `:app` but the voice's**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; scripts\connected-test.ps1 -Serial emulator-5554 :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.notPackage=dev.kestrel.app.voice'; "exit: $LASTEXITCODE"; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL and `exit: 0`; no test fails, among them every test of `dev.kestrel.app.agent`, `GoalPauseTest`'s 2 (the lock's pause with listening on and off), `AgentEndToEndTest.theAgentStaysOutOfKestrelsOwnApp` with the held goal's sentence, `ConversationHooksTest`'s 2, `SettingsAppTest`'s refused deletion, and `MainActivityTest.deleteHistoryWipesBothLogs`, whose deletion now goes through the conversation; the skipped tests are those of `dev.kestrel.app.commands`, which skip themselves without `scripts\command-test.ps1`'s argument (Step 5 runs them), and the tests that skipped before this plan for a precondition that their assumption names, such as `assumeContacts()`; the script reports no change of Kestrel's install, of the Google accounts or of Kestrel's profiles. Any other failure: stop and ask the controller.

- [ ] **Step 4: The voice's device tests**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; scripts\voice-test.ps1 -Serial emulator-5554; "exit: $LASTEXITCODE"; scripts\gradle.ps1 --stop`
Expected: `exit: 0`; every test of `dev.kestrel.app.voice` passes and none is skipped, among them `VoiceHookTest` with `followUp=closed` in its pattern, `SessionHookTest` and `ConversationTest`, whose task now ends through the conversation. Any failure: stop and ask the controller.

- [ ] **Step 5: The instant commands' device tests**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; scripts\command-test.ps1 -Serial emulator-5554; "exit: $LASTEXITCODE"; scripts\gradle.ps1 --stop`
Expected: `exit: 0`; every test of `dev.kestrel.app.commands` passes and none is skipped; the script puts the device's state back. Any failure: stop and ask the controller.

- [ ] **Step 6: The eval dry suite against the baseline**

The suite waits for Kestrel to listen (`:tools:eval`'s preflight, up to 90 s), and the voice tests of Step 4 left it off. Kestrel comes to the front first, so that Android lets the hook start the listening service.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; if ("$(& $adb -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; & $adb -s emulator-5554 shell am start -W -n dev.kestrel/dev.kestrel.app.MainActivity; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 -Command voice -Send toggle; Start-Sleep -Seconds 10; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 -Command voice-state; & $adb -s emulator-5554 shell input keyevent KEYCODE_HOME`
Expected: `sent toggle`, then a `voice-state` line that starts with `mode=listening`. If it says `mode=off`, run the command again once.

Run: `scripts\eval.ps1 suite --device emulator-5554 --runs 1; "exit: $LASTEXITCODE"`
Expected: the command's own checks of the accounts and the profile pass; `The results are in eval-results\kestrel-api37-<time>.`; every task of kestrel-api37's part of `eval\baseline.json` (`Get-Content eval\baseline.json -Raw | ConvertFrom-Json | ForEach-Object { $_.devices.'kestrel-api37'.tasks.PSObject.Properties.Name }`) has a run line, and each run line has the result that plan 7A's dry suite has for the same task under "## Plan 7A" in `.superpowers\sdd\progress.md`; no run has `reason=limit`, `end=STOPPED_AT_LIMIT`, `end=PAUSED_AT_LIMIT` or `end=INTERRUPTED`. A difference: stop and ask the controller, with the task's run line and its `agent-task -Result` line, which hold no words of the owner's.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; if ("$(& $adb -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; "google accounts: $(@(& $adb -s emulator-5554 shell dumpsys account | Select-String 'type=com\.google\}').Count)"; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 -Command provider-standin; & $adb -s emulator-5554 shell am broadcast -n dev.kestrel/dev.kestrel.app.debug.TestHookReceiver -a dev.kestrel.hook.CONVERSATION_STATE`
Expected: the count of Step 1, `profile=copilot`, and a broadcast result whose `data=` is a `conversation-state` line of counts and kinds only, such as `open=no continued=no commands=0 goal=none quiet=off quietLeft=none replyChannel=voice testMode=on`. The broadcast goes to the receiver directly, because `scripts\hook.ps1` learns the new hooks only in plan 7G.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 -Command voice -Send off`
Expected: `sent off`; Kestrel is off again, as the device tests leave it.

- [ ] **Step 7: Note the gate's result**

Add to `.superpowers\sdd\progress.md`, under "## Plan 7D": the build's commit on kestrel-api37, the counts of Tasks 1 to 7, the results of Steps 3 to 5 (the numbers of tests that passed and were skipped), and Step 6's results folder with its run lines compared with plan 7A's dry suite.

---

## Counts at the end of plan 7D

- `:app` JVM (with `app/src/testDebug`): A₀ + 49 − D, where D is the number of tests in `AgentRunnerTest`, `AppOwnerPortTest` and `AppAgentEnvironmentTest` that Task 3 counts in its Step 1, 39 once plan 7A has run (Task 1 + 7, Task 2 + 3, Task 3 + 4 − D, Task 4 + 11, Task 5 + 6, Task 6 + 3, Task 7 + 0, the whole-plan review's fixes + 15: `RulesTest` + 7, `ListeningTest` + 3, `ScreenTurnsTest` + 3, `GoalOwnerTest` + 1, `StoredSettingsTest` + 1); 656 with A₀ = 646. `RulesTest` has 113 cases at the end, from 124: plan 7B's Task 7 table names the new test of each case that moved, and Task 3's table the rest. `RequestsTest` has 38, from 32, `ListeningTest` 153, `ScreenTurnsTest` 6 and `GoalOwnerTest` 3.
- `:app` device tests: AD₀ + 5 (`GoalPauseTest` 2, `ConversationHooksTest` 2, `SettingsAppTest` + 1). The whole-plan review's M5 and its re-review's N2 add the helper `ConversationTests` and change `CommandTests.start`, `VoiceTests.startListening` and `AgentTests`, but no test.
- `:core:conversation`: C₀ + 12 (`ConversationMachineTest` + 1 and `ConversationEngineTest` + 1, Task 3's review fix M1; `ConversationMachineTest` + 2, its re-check's N1; `ConversationEngineTest` + 4, Task 7; `GoalRunnerTest` + 1, the whole-plan re-review's N1; `ConversationEngineTest` + 3, its round 2's R2-1), on top of plan 7B's count as built (`a05ea85a`). `:tools:eval`: E₀ + 1 (`RunnerTest`, Task 3). `:core:agent` G₀: unchanged. Pester: + 1 (`AgentChecks.Tests.ps1`, Task 3's review fix M2); otherwise Task 3 changes only comments of two scripts.

## Interfaces for 7E and 7F

What plans 7E and 7F consume, as this plan leaves `:app`. Every type is `internal` to `:app` unless it says otherwise.

**The engine in the app graph**
- `AppGraph.conversations: ConversationEngine`, built on first use with the channels `mapOf(InputChannel.VOICE to voiceChannel)`, plan 7C's `conversationStore` and `AndroidConversationClock`, the Conversations page's settings, the safety page's test mode, and "voice on" as the listening mode not `OFF` (plan 7B's decision 10). Plan 7F adds `InputChannel.TEXT to textChannel` to this map in `AppGraph`, and builds no second engine.
- `KestrelApplication.onCreate` restores the conversation once (`graph.scope.launch { graph.conversations.restore() }`); inputs wait for it, and the restore decides with the stored settings, which the engine gets as a cold flow (`storedConversationSettings`). Plan 7E's goal service reads `conversations.view` (`goal`, `step`, `checklist`, `asking`) and `conversations.restored`.
- `AppGraph.goalEnvironment: AppGoalEnvironment` (`GoalEnvironment`) with `evidences: RoundEvidences`, `witnesses`, `selfChatAliases` and the debug seam `standIn`; `AppGraph.roundModels: RoundModels` with `modelOverride`. The debug seam `AppGraph.conversationSteps` takes the engine's `onStep`.

**The phone's signals (plan 7E)**
- `AppGraph.phoneSignals: PhoneSignals` (package `dev.kestrel.app.phone`) with `watchScreen(cameOn: () -> Unit = {}, listener: (Boolean) -> Unit): PhoneWatch`, `watchCalls((CallState) -> Unit): PhoneWatch`, `callMonitor(): CallMonitor` and `callStates(): Flow<CallState>`: one screen receiver and one call listener for every watcher, also with listening off.
- `ConversationSignals(phone, backstop, scope, signal, report).follow(views)` (package `dev.kestrel.app.conversation`) gives the engine `LOCKED`, `UNLOCKED`, `RINGING`, `CALL_ACTIVE`, `CALL_IDLE` and `AGENT_CALL` while a conversation is open, a goal is, or `view.keptForOwner` is set; its first watch in the process sends the lock state that it reads, `UNLOCKED` too (also on a phone without a lock screen), because plan 7B's machine counts the owner as there only once a lock signal said so; the app graph starts it with the engine. Plan 7E's goal service needs no second watcher: the pauses with listening off come from here.
- `CallBackstop(calls, clock)` with `acted()`, `forget()`, `stops(): Boolean`, `endCall()` and `WINDOW_MILLIS`.

**The voice (plans 7E and 7F)**
- `Listening.voicePath: VoicePath` (`ready: StateFlow<Boolean>`, `speechStops: Long`, `say(text, final = false)`, `show(text)`, `ask(question, text, kind, voice)`, `handOver(text)`, `withdraw(question)`), `Listening.converse: (OwnerInput) -> Unit` and `Listening.awaitingCommand: (Boolean) -> Unit`, which the app graph sets.
- `VoiceChannel(voice: VoicePath, screen: OwnerScreen, audio: OtherAudio, handOvers: HandOverSignals, permissions: PermissionDialog) : OwnerChannel`: the voice channel's output, whose spoken outputs take turns in the order in which they came (each starts once the one before it has ended, so that the engine's "too late" sentence never plays over a goal's final words, and whatever stops Kestrel's speech, as the owner's stop, drops the outputs that wait: `VoicePath.speechStops`), its questions with the Orchestrator's answer waits, the card for a confirmation or a screenshot question, the other app's audio, and the hand-overs. The voice channel draws that card itself (`OwnerScreen.card`), also while Kestrel is off, because the screen session does not depend on the listening mode. Plan 7B's `ConversationOwner` asks a spoken conversation's question on the text channel too from the moment Kestrel is off, so plan 7F's `TextChannel` must not draw a second card for a question that the voice channel already shows on its card (a confirmation, and a screenshot question while another app's audio plays): the two channels share the one `QuestionCard`, one card per question number, or `TextChannel` leaves those questions to the voice channel's card. Either way, the owner sees one card for one question. A held goal's sentence comes from plan 7B's machine with `ConversationTexts.held(hold)` at its end, so a goal held at Kestrel's own screen says "Leave Kestrel's screen, then tap Continue or say 'Kestrel, continue'." (design 10.2); the voice speaks it and the bar shows it. Plan 7E's notification shows the same sentence for the same hold.
- The ports `OwnerScreen` (`card`, `releaseBar`, `kestrelInFront`), `OtherAudio` (`playing`, `pause`), `HandOverSignals` (`opened`, `endedByItself`), `PermissionDialog` (`ask`) and `TouchWatch` (`forgetOwnActions`, `watch`), which `AndroidOwnerSupport` implements but `PermissionDialog`, which the app graph makes from `PermissionActivity.ask`: plan 7F's `TextChannel` takes the ports that it uses from the same `AndroidOwnerSupport`, so that both channels share one implementation. `QuestionKind.DELETE_HISTORY` (plan 7B, `2113f5a0`) is a yes-or-no question on the card.
- `Rules`' state: `conversation: VoiceView`, `openQuestion: VoiceQuestion?`, `goal: GoalPhase?`, `goalWorks`; `Work.FOLLOWING_UP`; `Event.FollowUpLength(millis)`, which the app graph sends at every change of the Conversations page; `Event.ConversationChanged(VoiceView)`, from `engine.view.forVoice()`.
- `State.stoppable` is the one rule for what "stop" and "cancel" stop by voice: the goal in any phase, an open question, or Kestrel's own request that runs (`VoiceView.ownRequest`, from `ConversationView.ownRequest`); plan 7F's typed "stop" and the screen's Stop give the conversation `InputIntent.Stop` without the Rules, and the machine stops the same things. `Rules.followsUp` is the one rule for the follow-up window: it opens and stays open only while a conversation without a goal is open and Kestrel can hear the owner, so plan 7F's typed commands and card buttons that start or resume a goal end an open window through the view.
- `Event.SpeechEnded(spoken)`: only a final reply that Kestrel spoke to its end opens the follow-up window; a reply that was stopped, refused, interrupted or only shown opens none.
- The voice's `InputIntent.Answer(answer, question)` names the question that the voice read it for; plan 7F's card buttons name theirs the same way, and the machine drops an answer whose question is no longer open.
- `OwnedBar` and `BarOwner` (package `dev.kestrel.app.listen`): the bar knows whose text it shows (`BarOwner.Commands`, `BarOwner.FinalReply(reply)`, `BarOwner.FollowUp`, `BarOwner.Other`); `show(text, keep, owner)`, `takeOver(text, owner, over)` and `release(owner)`, which lets go of only that owner's text. Plan 7F's shared bar writer goes through it and gives each text its owner, or the follow-up window's countdown and an instant command's kept text break. Every text that the voice shows while a follow-up window can be open comes through the Rules as an `Effect.Show` (also "Kestrel could not open its settings.", `Feedback.SettingsFailed`), so that the Rules know when the bar is taken; `Listening` still shows a few texts directly that cannot meet an open window: the reason for turning off while Kestrel is off, a call command's failure during a call, a reply that could not be spoken, after the `say` that ended the window, and an instant command's kept text (`OwnedBar` with `BarOwner.Commands`).
- `ConversationMachine.alone` queues Kestrel's own requests: one that comes while another runs, or while "stop" ends a goal, waits in `waiting` with the commands and runs in its turn; "stop" cancels the request that runs and drops what waits.
- `AgentHookAnswers.state` is the one source of `agent-state`'s line.
- `Listening.inputs` is the debug seam through which `CommandTags.input` gives each input its tag's options; plan 7F's `conversation-type` reuses it for typed inputs.
- `AppGoalEnvironment.evidences` is null in a release build; the debug hooks install it (`VoiceHooks.install`) with the witnesses.
- `ScreenTurns` (`started(job, since, letGo)`, `stopped(job)`, `held()`, `take(after: Long?): Boolean`) is the one rule for who acts on the screen: the running instant command holds it from its start until its job has ended, also after a cancellation, and every step of a goal's round that may act on the screen takes the turn first (`GoalPort.takeScreen` through `TaskRun.screenGate`; `ScreenTakingGoal` in `GoalRunner`, `WatchedGoal` in `:app`). The turn names the owner's input that lets the round act now, once (`RunningGoal.actedFor`, set by the engine from the input of `StartGoal`, `ResumeGoal`, `ResumePausedGoal(act)` and `EndHandOver(…, act)`; a tap on a card without an input gets the next number from the engine's `inputIds`, `InputIds::next`). A command that the owner started before that input is cancelled through `Event.ScreenWanted(command)`; a round that goes on by itself or for an older input waits until the command has ended. The Rules end a running command only for the owner's new request to Kestrel (spec 7.3), never for an answer. Plan 7F's typed and card inputs therefore need nothing of their own, as long as each typed input gets its number from `InputIds.next()` and the machine carries it, as a spoken one does; a card's button that resumes a goal counts as the owner's act of that moment.

**The owner's inputs (plan 7F)**
- `InputIds.next(): Long` (package `dev.kestrel.app.conversation`): the number of every `OwnerInput` of every channel in the process.
- `Requests.typed(text: String): InputIntent?`: the typed reading of design 12.2, with only the exact name and without the requests that only the voice reads ("never mind", "nothing" and "forget it" stay the owner's words, so that they may answer a question). `Requests.intent(request: Request): InputIntent?`, with `Request.NewConversation` and `Request.ContinuePast(topic)`.
- The settings page's "Delete history" goes through the conversation from this plan on: `SettingsData.deleteHistory(): HistoryDeletion` calls `ConversationEngine.deleteHistory()` after the page's own question (Task 7). Plan 7F adds only the typed "delete history" (`Requests.typed` gives `InputIntent.DeleteHistory`, which asks on the text channel).

**The hooks (plans 7E, 7F and 7G)**
- `conversation-state`: `open=yes|no continued=yes|no commands=<n> goal=<phase>|none quiet=off|held|running quietLeft=<seconds>|none replyChannel=voice|text testMode=on|off`, from `ConversationHookAnswers.state(view, settings, now)`.
- `conversation-control --es do new|expire|limits|restore-check`, with `--es limits <replies>/<minutes>/<credits>` for `do=limits`; answers `new=sent`, `expire=sent`, `limits=<r>/<m>/<c> was=<r>/<m>/<c>` and `restored=nothing|open|closed|goal-held|goal-ended|pending`. Plan 7E's restart tests use `restore-check` after the hook that ends Kestrel's process.
- `voice-state` gains `followUp=open|closed` after `recognition=`; `agent-state` gains `checklist=<done>/<total>|none` and `conversation=open|closed` at its end.
- `CommandTags` maps a tagged input to its command through the engine's `onStep` (`CommandTags.input(heard, input)`, `onConversationStep(event, state, step)`): plan 7F's `conversation-type` tags a typed input the same way, with `Event.Heard` null.
- `ConversationTests` (androidTest, package `dev.kestrel.app.conversation`): `endWork()` stops through the engine whatever "stop" stops and waits until the quiet time no longer holds, and `close()` then closes the open conversation and waits until nothing is kept for the owner; `CommandTests.start`, `VoiceTests.startListening` and `AgentTests` use it, and so do the device tests of plans 7E and 7F that need a clean start.
- `AgentTests` (androidTest, package `dev.kestrel.app.agent`): `start()`, `stop()`, `turnOff()`, `run(request, model: ModelPort): Int` (it closes the open conversation first, once the quiet time runs), `awaitAsking(command, kind)`, `say(words)`, `ended(command): CommandOutcome`, `spoken()`, and `HeldModel(model)`, whose replies wait for the test's `release()`, for the device tests of plans 7E and 7F; `GoalPauseTest` is the pattern for the lock and call checks of plan 7E.

## Decisions for the controller

The controller settled items 1 to 9 on 2026-11-24; the plan carries them as follows.

1. **The settings page's "Delete history" (settled: in this plan).** Task 7 moves it onto the conversation: the page asks its own question with the design's words, and `ConversationEngine.deleteHistory()` deletes both logs, every past conversation with the index of past conversations and the key `kestrel.conversations`, and the open conversation, without a second question. While a goal is open or one of Kestrel's own requests runs it deletes nothing, and the page says why. Plan 7F adds only the typed path.
2. **Test limits below the page's bounds (settled: plan 7G).** `conversation-control do=limits` stays within the page's bounds; plan 7G adds a debug-only seam for smaller limits with its task `limit-continue`.
3. **"Turn off" in the follow-up window (settled: it keeps its meaning).** Task 4 reads the window's words with `Requests.read(…, followUp = true)`: "turn off" alone turns Kestrel off and never reaches the conversation (design 3.6); `RequestsTest.turnOffAloneTurnsKestrelOffInTheFollowUpWindowButNotAfterYes` and `RulesTest.turnOffAloneInTheFollowUpWindowTurnsKestrelOffAndReachesNoConversation` test it.
4. **`voice-task`'s new reply (settled: readers adapted).** No file in `scripts\` or `tools\` reads the reply's words; Task 3 adapts `scripts\hook.ps1`'s help for `voice-task` and the comment before the interruptions in `scripts\a71-session.ps1`, and runs `scripts\test-scripts.ps1`.
5. **`scripts\hook.ps1` learns the new hooks in plan 7G (accepted).** Until then, Task 8 sends `CONVERSATION_STATE` with `am broadcast` to the receiver directly.
6. **The channels until plan 7F (accepted).** The engine has the voice channel alone; `TextChannel` and `conversation-type` come in plan 7F.
7. **A refusal's end (accepted, with the review's fix).** `NO_SCREEN` when Kestrel's accessibility service is off or the touch watcher cannot start, `NO_PROFILE` for no profile and for a test build without a script; the service is checked before the model, and `:tools:eval` reads both ends as a refusal (Task 3, `RunnerTest.aRoundWithoutKestrelsServiceIsARefusalToo`).
8. **The witness's final words (accepted).** Task 3 removes `TaskWitness.ended` and `TaskEvidence.spoken`; `agent-task -Result` reads `CommandOutcome.answer`.
9. **The A71 session's reply-tail check (settled).** From Task 4 on, Kestrel's long reply opens the follow-up window; the A71 checks of later plans read `voice-state followUp=` there.
10. **Plan 7B's changes for this plan (done).** Plan 7B carries the round's release before its end and `QuestionKind.DELETE_HISTORY` since `2113f5a0`; "Before you start" checks for both. The re-review's N2 (an exception inside `finish` before the listener's end) is the controller's to route into plan 7B.

## Self-review

- **Spec coverage.** Design 5.5 (the voice rules without the task): Task 3, with D9 and plan 7B's decision 10 in `Rules`. Design 5.6 (the wiring): Task 3, with plan 7C's store and clock. Design 6.5's voice and phone parts: Tasks 1 and 3 (`PhoneSignals`, `ConversationSignals`, the call backstop). Design 6.6 (the follow-up window) with design 3.6's words that keep their meaning: Task 4. Design 6.8 and 9.6 (a new conversation and a past one by voice): Task 5. Design 9.7 ("delete history" through the conversation): by voice in Task 3, from the settings page in Task 7. Design 11 (the voice channel): Task 3's `VoiceChannel`, `VoiceAnswers` and `VoicePath`. Design 12.2 (the typed reading): Task 5's `Requests.typed`. Design 14.2 item 4 (one switch-over): Task 3. Design 14.3 (the regression gate): Task 3's `RulesTest` table and plan 7B's mapping, Task 3's `GoalPauseTest`, and Task 8's device suites and dry suite. Design 14.4 (the hooks): Tasks 3 and 6; `conversation-type` and `hook.ps1`: plans 7F and 7G.
- **Placeholders.** Every step shows its code or its command. The counts that depend on plans 7A to 7C are measured (A₀, E₀, AD₀, C₀, G₀, D) and named.
- **The review of 2026-11-24.** C1: `RulesTest`'s helper `ownQuestion` has no `@Test` (Task 3; the counts are 99, 103 and 104). I1: plan 7B (`2113f5a0`) and Task 3's table. I2: Task 3 (`Runner`, `Refusals`, `RunnerTest`). I3: plan 7B's `QuestionKind.DELETE_HISTORY`, `VoiceAnswers` and `VoiceAnswersTest.continueOrGoOnNeverDeletesTheHistory` (Task 3). I4: `storedConversationSettings` and `StoredSettingsTest` (Task 3). I5 and N1: `GoalPauseTest` with `HeldModel` (Task 3, run in Task 8): the goal stays paused until the yes, and only then does the model get its next request. I6: the ports of `OwnerSupport.kt` (Task 3). M1 and N2's pattern: "Before you start" (12 matches, with `val cancellation = try`). M2: Task 8 turns listening on before the dry suite and off after it. M3: `Event.ChannelWithdraws` (Task 3). M4: `VoiceView` and `forVoice` (Task 3). M5: "Interfaces for 7E and 7F" (`ScreenTurns`). M6: the header's paths and base, item 9. M7: `AppGoalEnvironment.prepare` checks the service first (Task 3). M8: Task 8's step 3. M9: `AgentTests.run` (Task 3). The held goal at Kestrel's own screen (7A Task 8's review): its sentence reaches the voice and the bar, and `AgentEndToEndTest.theAgentStaysOutOfKestrelsOwnApp` checks that Kestrel says it (Task 3).
- **The review of Task 1 (2026-11-25).** M1: `aCallWatchWhoseListenerCannotStartLeavesNoWatcherBehind` stops its second watch and checks that Android's listener stopped, so it fails without the rollback. M2: `watchScreen` removes its watcher when the screen source cannot start (`aScreenWatchWhoseSourceCannotStartLeavesNoWatcherBehind`). M3: `ScreenSource.read` replaces `current()`, and a screen watch's first reading comes on the source's thread in order with the changes (`aWatchFromAnotherThreadGetsItsFirstReadingOnTheSourcesThreadInOrderWithTheReports`); `AndroidScreenSource` reads on the main thread, where its receiver runs. Task 1 counts `PhoneSignalsTest` 7.
- **Type consistency.** `VoicePath`, `VoiceChannel`, the ports of `OwnerSupport.kt`, `VoiceView`, `VoiceQuestion`, `Event.ChannelSays(reply, text, final)`, `Effect.Converse(intent, heard)`, `InputIds.next()`, `Requests.intent` and `Requests.typed`, `AppGoalEnvironment`, `RoundModels`, `RoundEvidences`, `GoalOwner`, `ConversationSignals`, `PhoneSignals` and `ConversationHookAnswers` have the same signatures in the tasks that make them, the tasks that use them and "Interfaces for 7E and 7F"; plan 7B's types are those of its "Interfaces for 7C and 7D", and plan 7C's those of its "Interfaces for plans 7D, 7E and 7F".
- **The safety work of plan 6B-basefix.** `CheckedLaunch` and `ScreenAction.Launch` stay in the round's ports, which `AppGoalEnvironment` builds as `AppAgentEnvironment` did; the witness and its marker check stay (`DebugTaskWitness`, `RoundEvidences`); `agent-state`'s `question=` keeps its values; the owner's touches and the signals at a round's end stay in `RoundEvidences` for `agent-task -Result`.
