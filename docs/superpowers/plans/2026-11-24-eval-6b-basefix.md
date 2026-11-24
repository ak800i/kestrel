# The Baselines' Fixes (Plan 6B-basefix) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every failed or CANCELLED run of the task suite explain itself, apply the owner's decisions on how `:tools:eval` answers Kestrel's questions, fix the faults of the test that the analysis of the two v1.0 baselines found, let `open_deep_link`'s `app` find Google Maps by the name that Kestrel's own prompt gives, and record both baselines again.

**Architecture:** Kestrel's debug hooks answer more, still without text: `agent-state` names the open question's source (`question=`, from the `AnswerKind` that `AppOwnerPort` now keeps), and `agent-task` counts the owner's touches and the signals among them, compares the marker and the digits also with what Kestrel said with `say`, and says whether a data tool's result held the run's marker. `ScreenSteps.linkApp` matches `open_deep_link`'s `app` only against the launcher apps that open the link, by their labels and by their package names' words. `:tools:eval` answers look_closer's screenshot question "no", leaves its "Which number should I use?" open, records each answer, marks a run that its own "cancel" ended `reason=asked`, writes a detail line per run into the suite's `details.txt`, keeps the display page's other size settings around `font-size`, and asks `maps-place` for a place that the system prompt does not name. The controller then checks both devices and records both baselines again.

**Tech Stack:** Kotlin 2.4.21 on Android (minSdk 33, target 37) and on the JVM (JDK 21 from Android Studio, target 17), kotlinx.coroutines with `kotlinx-coroutines-test`, kotlinx.serialization 1.13.0, JUnit 4.13.2, PowerShell 7.3 and Windows PowerShell 5.1, Pester 6.2.0.

## Global Constraints

- Package and application ID `dev.kestrel`; licence GPL-3.0-or-later. Nothing new ships in the app; the new hook answers exist only in the debug build (`app/src/debug`). What a debug build observes of a task (the `say` texts, the run's marker in the data) is a collaborator that only the debug source set installs (`DebugTaskWitness`); the main code defines only its seam, `TaskWitness`, whose release implementation observes and keeps nothing, and checks no debug flag for it. `:tools:eval` uses only `kotlinx-serialization-json`, JUnit and the JDK, and depends on no other Kestrel module (design D2 of sub-project 6).
- The owner's hard rule (AGENTS.md): no workarounds and no smallest-change fixes; clean code, good architecture and modularity, even when that means a larger change. A step that would patch a symptom at the nearest line is a fault of the plan: stop and ask the controller.
- The owner's decisions bind this plan: `:tools:eval` answers look_closer's screenshot question "no", leaves look_closer's "Which number should I use?" unanswered so that Kestrel's own wait ends and the model gets `NoAnswer`, keeps "cancel" for the model's `ask_user` and for a hand-over, and keeps its other answers. `OwnerSignal` does not change in this plan (B1 waits for the evidence of T1 and T2), and the system prompt does not change (T7 changes the task instead).
- Live runs call the owner's default Copilot profile, in test mode, on kestrel-api37 and the Galaxy A71 only, never on the S23+, with no ceiling on AI Credits (the owner's decision of 2026-11-23; AGENTS.md). Only the controller's steps of Task 11 run `--live`; every other step uses the scripted model or no model.
- Never print, log or write into a result the owner's words, screen text, a notification, an SMS, an e-mail, a contact, a prompt, a reply, a `say` text, a tool's arguments, a value of `corpus\owner\a71-config.json` or a word that Kestrel heard. A hook answers counts, flags, kinds and matches; the marker and the digits are compared on the phone (design D2, D5, D6 of sub-project 6; D28 of sub-project 5).
- The run line stays task-check.ps1's: `task=<name> run=<i> result=<pass|fail|skipped> end=<EndReason|none> check=<pass|fail|skipped> steps= workMs= credits=<n|unknown> model= in= out= cacheRead= cacheWrite= [asked=<kinds>] reason=<ok|timeout|check|end|untestable|limit|confirm|call|stopped|setup|stalled|asked>`; this plan adds only the reason `asked` and, with it alone, the pair `asked=` before `reason=`, which names the cancelled questions' kinds (readers that take the pairs by name, as `ConvertFrom-TaskLine`, read it unchanged). A suite's detail lines go into its own `details.txt`, never into `runs.txt`, which `scripts\a71-agent.ps1` and `--resume` read. A results file's line is `<yyyy-MM-dd HH:mm:ss>\t<step>\t<text>` in UTF-8 without a BOM (design D6).
- Every change on a device pushes its restore before the change, with the value read before it; a restore that fails names what is left and how to undo it by hand (design D3, D4 and D9; fix-6a-5's contract).
- Device steps run only in Tasks 10 and 11, which are the controller's. On kestrel-api37 they run with the emulator's check in the same command, written without the word throw: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }`. If the check fails, or no emulator runs, start it with `scripts\start-emulator.ps1`, check the serial that it prints, and use that serial. Never use the AVD `zoran`, the S23+ or the owner's Xiaomi tablet (`8a49a19a`).
- kestrel-api37 holds the owner's own Google account and Kestrel's Copilot profile: no step uninstalls Kestrel there, clears its data, or signs an account in or out. Each device step checks before and after it that `dumpsys account` counts the same Google accounts and that `scripts\hook.ps1 -Serial emulator-5554 -Command provider-standin` answers `profile=copilot`.
- A step on the A71 (`R58N66RVD7Z`) follows AGENTS.md: no call; messages only to the owner's Telegram Saved Messages in test mode, e-mails only to himself, calendar events and contacts only in his secondary account, each removed after its run; every setting put back; nothing that Kestrel heard in any result; `Assert-TestDevice` and `. scripts\lib\Oracle.ps1` before `Unlock-TestPhone`; at the end the A71 locked and "Stay awake" at 0. The controller's launcher `files\a71-eval-copy.ps1` does all of this around each A71 command of Tasks 10 and 11. Only Task 10, Step 1 (Kestrel's Copilot sign-in on kestrel-api37, about 3 minutes) and Step 2's fallback for the accessibility service need the owner's hands.
- Never build or install `:tools:eval` (`installDist`, or `scripts\eval.ps1`, which rebuilds it) while a suite runs from its copy: the running JVM loads classes from the installed jars (the lesson of 2026-11-24). While a suite of Task 11 runs, other agents may run only `:tools:eval:test`, `:tools:eval:check` or `:tools:eval:jar`.
- Run Gradle only through `scripts\gradle.ps1`, one build at a time; when the controller's brief says that agents run in parallel, take `.superpowers\sdd\gradle.lock` in `H:\Code\kestrel` before each Gradle command as the brief says, and do not run `scripts\gradle.ps1 --stop` beside another Gradle user. Otherwise stop Gradle with `scripts\gradle.ps1 --stop` at the end of each task that builds. Install only through `scripts\install.ps1`. Every `:app` device test goes through `scripts\connected-test.ps1` only, which since 25bafae2 keeps Kestrel and its data, refuses arguments that would set otherwise, and fails when Kestrel was uninstalled or installed again (the incident of 2026-11-24); this plan runs no `:app` device test. Run the suite only through `scripts\eval.ps1` or the controller's copy launchers.
- The owner's permission set for the evaluation (2026-11-24) stays granted on both test devices: READ_CALENDAR, READ_CONTACTS, WRITE_CONTACTS, READ_SMS, READ_CALL_LOG, READ_MEDIA_IMAGES and READ_MEDIA_VIDEO, which a live series grants (Task 9) and no restore takes back; this is the one exception to "every setting put back". Location, phone state and call answering are not in the set and stay as they are.
- No step ever places a call or answers "yes, call" (process rule 10).
- Scripts run in Windows PowerShell 5.1 and PowerShell 7; `scripts\test-scripts.ps1` passes after every script change, comments included.
- The JVM tests run on CI's Ubuntu too: no test needs Windows, a device, a font or a network.
- The repository uses LF line endings.
- This plan is `.superpowers/sdd/plan-6b-basefix.md`; the controller commits it as `docs/superpowers/plans/2026-11-24-eval-6b-basefix.md`. A plan is frozen once committed; a later difference goes into `.superpowers/sdd/progress.md`.
- Every commit message ends with the line `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.

D-numbers without a sub-project are those of sub-project 6's design (`.superpowers/sdd/plan-6-design.md`); T1 to T7, B1, B1', B2 and P1 are the sections of the analysis `files\inv-baseline-fails.md` that the controller gives with each brief. Code comments name this plan "plan 6B-basefix".

## Before you start

- The code is the branch `sp3` at `f7bbc0bd` or a later commit of the controller's.
- Measure the counts before the first change of Task 1, and note them in the task's report: run `scripts\gradle.ps1 :app:testDebugUnitTest :core:agent:test :tools:eval:test`, then `foreach ($dir in 'app\build\test-results\testDebugUnitTest', 'core\agent\build\test-results\test', 'tools\eval\build\test-results\test') { $n = 0; Get-ChildItem $dir -Filter *.xml | ForEach-Object { $n += [int]([xml](Get-Content $_.FullName -Raw)).testsuite.tests }; "$dir $n" }`, then `scripts\test-scripts.ps1` and `scripts\gradle.ps1 --stop`. These are A₀ (`:app` JVM, with `app/src/testDebug`), G₀ (`:core:agent`), E₀ (`:tools:eval`) and P₀ (Pester, in each shell). Each task gives its counts as "N + k", where N is the count before the task. If a count differs, stop and ask the controller.
- Before each task, check that every text that the task replaces exists, and exists once, in its file as the earlier tasks left it; and run `git status --short`. If a text is missing, or a file that the task changes shows changes that are not the task's own, stop and ask the controller.
- Tasks 1 to 9 use no device. Tasks 10 and 11 are the controller's own steps; Task 10 begins with the owner's Copilot sign-in on kestrel-api37, which the incident of 2026-11-24 wiped.
- In PowerShell, quote a list of tasks for `scripts\eval.ps1`, as in `--tasks 'maps-place,font-size'`.

## Not in this plan

- **B1**, branch B's remaining signals (`OwnerSignal`): the A71's logcat from 13:59 on holds no `KestrelTouch` line, so no CANCELLED run after 13:59 came from branch B, and nothing proves the earlier ones. The new baselines' detail lines (`ownerTouches=`, `signals=`, `asked=`) decide it; see "Left open".
- **B2**, the chooser of a link that Kestrel opened itself, which test mode treats as a share sheet, and Google Maps' `api=1`: B1' removes the chooser from maps-place; see "Left open".
- The v1.1 items of the ledger (instant commands from noise, the ANR dialog) and plans 6C, 6D and v11-listen.

## Changes to the design that this plan makes

- **The tool's answers (P1).** Design D4 gave every free and pick-number question "cancel". Now `Answers.reply` answers look_closer's screenshot question "no", which LookCloser reads as the owner's no and goes on to "Which number should I use?" in the same call; it leaves that question open, so that the Orchestrator's own wait (15 s after the question) ends with no answer and the model reads `NoAnswer`; and it keeps "cancel" for the model's `ask_user` and for a hand-over, and "no" for a yes-or-no question and "Continue the task?". Spec 16.5's sentence on the answers changes with it.
- **The question's source by its `AnswerKind`.** The analysis proposed `question=ask_user|screenshot|pick-number|stuck|cost|share`. `AppOwnerPort` keeps the `AnswerKind` of the open question, so `agent-state` answers `question=yes-no|free|pick-number|screenshot|none`. That is enough for P1: the screenshot question is the only free question that the tool answers apart, and "Which number?" is the only pick-number question. The stuck question, the cost question and the routine's questions are all yes-no, which the tool answers "no" alike; naming them apart would need `:core:agent` to give a reason with each question, which no decision needs now (see "Left open").
- **`reason=asked`.** A run whose task ended CANCELLED after the run's own answer ended it, an answer that reached Kestrel ("cancel" to one of Kestrel's questions or hand-overs, or "no" to "Continue the task?"), is `reason=asked` instead of `reason=end`, and its run line names the cancelled kinds in `asked=<kinds>` right before `reason=` (such as `asked=hand-over/PERMISSION`, Kestrel's own permission request), with a warning that names them too. A refused confirmation stays `reason=confirm`.
- **A declined task's reason (T5).** A `Declined` task whose run never answered its decline, and whose task did not end DECLINED, is `reason=end`, no longer `reason=check`.
- **A detail line per run.** After each run line, the runner writes `task=<name> run=<i> asked=<kind>:<answer>,… ownerTouches=<n|none> signals=<n|none> markerSaid=<0|1|none> markerInData=<0|1|none> putBack=<name,…|none> byHand=<name,…|none>` (a `RunDetail`) on the console and, in a suite, into `details.txt` of the results folder, with the step `detail`. `runs.txt` keeps only the run lines. A resume appends to `details.txt`, so a task that ran again has two lines for a run; the later one counts.
- **A task's witness.** The debug build's observations of a task are a collaborator of their own: `:app`'s main code has the seam `TaskWitness` (what Kestrel said, a view of the data tools, the evidence), every task gets one through `TaskStart.witness`, and a release build's is `TaskWitness.NONE`. The debug source set installs `DebugTaskWitness` through `AgentRunner.witnesses`, beside the task options' seam that it installs already; it keeps the newest `say` texts and, when `:tools:eval` gave the transcript the run's marker (`voice -Send heard --es marker`), wraps the data tools in `MarkerWatch`, which keeps only whether a result's field held the marker's words.
- **What Kestrel said counts for the reply checks (T3).** `agent-task`'s `marker=` and `digits=` compare the end's spoken sentence and every `say` text of the task, because the owner hears both (design D29's "Kestrel's spoken reply"). `markerSaid=` tells whether a `say` text held the marker. Spec 16.5's sentence changes with it.
- **font-size puts back the display page's other size controls as Android applies them (T6).** The font scale keeps its `SettingsValue`. `KeepDisplayDensity` reads the display's override with `wm density` and applies it again with `wm density <n>` or `wm density reset`, because Android shows a written `secure display_density_forced` only after a reboot, and names it in `putBack=`. `KeepStoredSettings` writes back every other setting of `system`, `secure` and `global` whose key holds `font`, `density` or `zoom` (One UI's font keys), deletes one that the run added, and names it in `byHand=` with a warning, because only its own page applies it. The One UI keys are found at each run by that pattern, so no key is written into the code; Task 10 confirms on the A71, read-only, that the pattern covers them and that the A71 has no density override left.
- **maps-place asks for Ada Ciganlija (T7).** "show Ada Ciganlija on Google Maps" replaces "show Kalemegdan on Google Maps", which is word for word the system prompt's example. Ada Ciganlija is written the same in English and Serbian, so Maps shows the same words in either language. The dry-run script names the app "Google Maps", as the prompt tells the model, so that the dry runs of Task 10 test B1' on both devices.
- **One permission set for the evaluation (the owner's decision of 2026-11-24).** Before a live series, `:tools:eval` grants Kestrel READ_CALENDAR, READ_CONTACTS, WRITE_CONTACTS, READ_SMS, READ_CALL_LOG, READ_MEDIA_IMAGES and READ_MEDIA_VIDEO, reads them back, stops when one is missing, and records the states in `suite.json`; they stay granted, with no restore. Today the A71 granted only READ_CONTACTS, and calendar-add's task ended CANCELLED at Kestrel's request for READ_CALENDAR, which the run cancelled. AGENTS.md and spec 16.5 say so.
- **`open_deep_link`'s `app` (B1').** Spec 11.4's sentence on `app` changes: a name finds exactly one of the installed apps that open the link, by its launcher name as the launcher's matcher reads it, or by exactly its package name's words without the generic parts; a vendor's word alone names no app.

## File structure

| File | Responsibility |
|---|---|
| `app/src/main/kotlin/dev/kestrel/app/agent/` `AgentEnvironment.kt`, `AppOwnerPort.kt`, `AgentRunner.kt`; `app/src/debug/kotlin/dev/kestrel/app/debug/` `AgentHookAnswers.kt`, `AgentTaskHooks.kt`; `scripts/hook.ps1` (comments) | Task 1: `agent-state`'s `question=` (P1's Kestrel side) |
| `AgentEnvironment.kt`, `AppAgentEnvironment.kt`, `AgentRunner.kt`, `AgentHookAnswers.kt`, `AgentTaskHooks.kt`, `scripts/hook.ps1` (comments) | Task 2: the touches and the signals in `agent-task`'s answer (T2) |
| `app/src/main/kotlin/dev/kestrel/app/agent/TaskWitness.kt` (new), `AgentEnvironment.kt`, `AppOwnerPort.kt`, `AppAgentEnvironment.kt`, `AgentRunner.kt`; `app/src/debug/kotlin/dev/kestrel/app/debug/` `DebugTaskWitness.kt` and `MarkerWatch.kt` (new), `VoiceHooks.kt`, `AgentHookAnswers.kt`; `testapp/.../NotificationActivity.kt`; `scripts/hook.ps1` (comments); spec 16.5 | Task 3: the task's witness, `markerSaid=` and `markerInData=` (T3), and the notification's marker in its body (T4) |
| `core/agent/src/main/kotlin/dev/kestrel/agent/loop/ScreenSteps.kt`, `core/agent/src/test/kotlin/dev/kestrel/agent/AgentToolsTest.kt`, spec 11.4 | Task 4: `open_deep_link`'s `app` among the apps that open the link (B1') |
| `tools/eval/src/main/kotlin/dev/kestrel/eval/` `run/Asked.kt` (new), `task/Task.kt`, `run/Runner.kt`, `run/Verdict.kt`, `run/RunLine.kt`, `hook/Hooks.kt`, spec 16.5 | Task 5: the tool's answers (P1), their record and `reason=asked` (T1), and a declined task's reason (T5) |
| `run/RunDetail.kt` and `task/PutBack.kt` (new), `run/Runner.kt`, `run/LineWriter.kt`, `hook/Hooks.kt`, `task/Steps.kt`, `command/SeriesCommand.kt`, `report/SuiteFolder.kt`, spec 16.5 | Task 6: the run's detail line and `details.txt` |
| `task/SettingsSteps.kt`, `task/SettingsTasks.kt`, `tools/eval/src/test/kotlin/dev/kestrel/eval/device/FakeAdb.kt` | Task 7: font-size's `KeepDisplayDensity` and `KeepStoredSettings` (T6) |
| `task/AppTasks.kt`, `tools/eval/src/main/resources/dev/kestrel/eval/scripts/maps-place.json` | Task 8: maps-place's place (T7) |
| `tools/eval/src/main/kotlin/dev/kestrel/eval/run/EvalPermissions.kt` (new), `command/SeriesCommand.kt`, `report/SuiteFolder.kt`, `AGENTS.md`, spec 16.5 | Task 9: the evaluation's permission set before a live series |
| none (device steps) | Task 10: the checks on both devices before the baselines |
| `eval/baseline.json` | Task 11: both baselines again |

Every Kotlin path of `:tools:eval` is below `tools/eval/src/main/kotlin/dev/kestrel/eval/`, and its tests below `tools/eval/src/test/kotlin/dev/kestrel/eval/`.

---

### Task 1: `agent-state` names the open question's source

The Kestrel side of P1: `AppOwnerPort` keeps the `AnswerKind` of its open question, `AgentRunner` hands it to the debug hook, and `agent-state` answers it as `question=`, so that `:tools:eval` (Task 5) can tell look_closer's screenshot question from the model's own free question, which both reach the Orchestrator as `TaskAsk.FREE`.

**Files:**
- Modify: `app/src/main/kotlin/dev/kestrel/app/agent/AgentEnvironment.kt`, `app/src/main/kotlin/dev/kestrel/app/agent/AppOwnerPort.kt`, `app/src/main/kotlin/dev/kestrel/app/agent/AgentRunner.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHookAnswers.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, `scripts/hook.ps1`
- Test: `app/src/test/kotlin/dev/kestrel/app/agent/AppOwnerPortTest.kt`, `app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt`

**Interfaces:**
- Consumes: `dev.kestrel.agent.port.AnswerKind` (`YES_NO`, `FREE`, `PICK_NUMBER`, `SCREENSHOT`); `AgentRunner.confirming(task: Int): Category?` as the pattern.
- Produces: `TaskOwner.asking: AnswerKind?` (default `null`); `AppOwnerPort.asking`, set while `ask` waits; `AgentRunner.asking(task: Int): AnswerKind?`; `AgentHookAnswers.question(kind: AnswerKind): String` (`yes-no`, `free`, `pick-number`, `screenshot`); `agent-state`'s answer ends with ` question=<yes-no|free|pick-number|screenshot|none>`, which Task 5 reads.

Counts: `:app` JVM N + 2 (A₀ + 2). Pester unchanged.

- [ ] **Step 1: Write the failing tests**

Add this test to `app/src/test/kotlin/dev/kestrel/app/agent/AppOwnerPortTest.kt`, before its last closing brace:

```kotlin
    @Test
    fun anOpenQuestionTellsItsKindUntilItEnds() = runTest {
        val asked = started { owner.ask("May I send a screenshot of Settings to Copilot?", AnswerKind.SCREENSHOT) }
        assertEquals(AnswerKind.SCREENSHOT, owner.asking)

        owner.answered("no")
        assertEquals(Answer.Text("no"), asked.await())
        assertNull(owner.asking)

        val number = started { owner.ask("Which number should I use?", AnswerKind.PICK_NUMBER) }
        assertEquals(AnswerKind.PICK_NUMBER, owner.asking)
        owner.answered(null)
        assertEquals(Answer.Silence, number.await())
        assertNull(owner.asking)
    }
```

Add this test to `app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt`, before its last closing brace:

```kotlin
    // The wire names that :tools:eval's answers read (plan 6B-basefix).
    @Test
    fun theQuestionIsNamedByTheKindThatTheAgentAskedItWith() {
        assertEquals("screenshot", AgentHookAnswers.question(AnswerKind.SCREENSHOT))
        assertEquals("pick-number", AgentHookAnswers.question(AnswerKind.PICK_NUMBER))
        assertEquals("free", AgentHookAnswers.question(AnswerKind.FREE))
        assertEquals("yes-no", AgentHookAnswers.question(AnswerKind.YES_NO))
    }
```

In `app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt`, replace:

```kotlin
import dev.kestrel.agent.TaskReport
```

with:

```kotlin
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.port.AnswerKind
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.agent.AppOwnerPortTest --tests dev.kestrel.app.debug.AgentHookAnswersTest`
Expected: FAIL, the compiler reports `Unresolved reference 'asking'` and `Unresolved reference 'question'`.

- [ ] **Step 3: Keep the open question's kind**

In `app/src/main/kotlin/dev/kestrel/app/agent/AgentEnvironment.kt`, replace:

```kotlin
import dev.kestrel.agent.AgentSettings
import dev.kestrel.agent.port.ModelPort
```

with:

```kotlin
import dev.kestrel.agent.AgentSettings
import dev.kestrel.agent.port.AnswerKind
import dev.kestrel.agent.port.ModelPort
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AgentEnvironment.kt`, replace:

```kotlin
    /** The category of the confirmation that is open now, or null; the debug hook `agent-state` reads it (design D28). */
    val confirming: Category? get() = null
```

with:

```kotlin
    /** The category of the confirmation that is open now, or null; the debug hook `agent-state` reads it (design D28). */
    val confirming: Category? get() = null

    /**
     * The kind of the question that is open now, or null; the debug hook `agent-state` reads it, so that :tools:eval
     * answers look_closer's screenshot question apart from the model's own question (plan 6B-basefix).
     */
    val asking: AnswerKind? get() = null
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AppOwnerPort.kt`, replace:

```kotlin
    @Volatile
    override var confirming: Category? = null
        private set
```

with:

```kotlin
    @Volatile
    override var confirming: Category? = null
        private set

    @Volatile
    override var asking: AnswerKind? = null
        private set
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AppOwnerPort.kt`, replace:

```kotlin
    override suspend fun ask(question: String, kind: AnswerKind): Answer = questions.withLock {
        screen.forgetOwnActions()
        try {
```

with:

```kotlin
    override suspend fun ask(question: String, kind: AnswerKind): Answer = questions.withLock {
        screen.forgetOwnActions()
        asking = kind
        try {
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AppOwnerPort.kt`, replace:

```kotlin
        } finally {
            // A cancelled task's coroutine still lets the bar go before the report (D32 item 1).
            withContext(NonCancellable) { screen.releaseBar() }
        }
```

with:

```kotlin
        } finally {
            asking = null
            // A cancelled task's coroutine still lets the bar go before the report (D32 item 1).
            withContext(NonCancellable) { screen.releaseBar() }
        }
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AgentRunner.kt`, replace:

```kotlin
    internal fun confirming(task: Int): Category? = synchronized(lock) { current?.takeIf { it.task == task } }?.owner?.confirming
```

with:

```kotlin
    internal fun confirming(task: Int): Category? = synchronized(lock) { current?.takeIf { it.task == task } }?.owner?.confirming

    /** The kind of the question that task number [task] has open, or null (the debug hook `agent-state`). */
    internal fun asking(task: Int): AnswerKind? = synchronized(lock) { current?.takeIf { it.task == task } }?.owner?.asking
```

- [ ] **Step 4: Answer it in `agent-state`**

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHookAnswers.kt`, replace:

```kotlin
import dev.kestrel.agent.TaskReport
```

with:

```kotlin
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.port.AnswerKind
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHookAnswers.kt`, replace:

```kotlin
    /** Whether one of the task's confirmed [recipients] is [wanted], compared folded: match, other, or none without either. */
```

with:

```kotlin
    /**
     * agent-state's `question=`: the kind that the agent asked the open question with, `yes-no`, `free` (the model's
     * `ask_user`, or a routine's name), `pick-number` (look_closer's "Which number should I use?") or `screenshot`
     * (look_closer's screenshot question, which the Orchestrator asks as a free question), so that :tools:eval answers each
     * as the owner decided (plan 6B-basefix).
     */
    fun question(kind: AnswerKind): String = kind.name.lowercase().replace('_', '-')

    /** Whether one of the task's confirmed [recipients] is [wanted], compared folded: match, other, or none without either. */
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, replace:

```kotlin
        val confirming = graph.agent.confirming(number)?.takeIf { current }?.wire ?: "none"
        return "phase=$phase asking=$asking work=${state.work.name.lowercase().replace('_', '-')} handOver=$handOver confirming=$confirming"
```

with:

```kotlin
        val confirming = graph.agent.confirming(number)?.takeIf { current }?.wire ?: "none"
        // The open question's source, so that :tools:eval answers look_closer's screenshot question apart (plan 6B-basefix).
        val question = graph.agent.asking(number)?.takeIf { current }?.let(AgentHookAnswers::question) ?: "none"
        return "phase=$phase asking=$asking work=${state.work.name.lowercase().replace('_', '-')} handOver=$handOver confirming=$confirming question=$question"
```

In `scripts/hook.ps1`, replace:

```powershell
#   agent-state -Tag k                      the task's phase, its open question's kind, Kestrel's work, the hand-over and
#                                           the category of the open confirmation
```

with:

```powershell
#   agent-state -Tag k                      the task's phase, its open question's kind, Kestrel's work, the hand-over, the
#                                           category of the open confirmation, and the question's source (question=yes-no,
#                                           free, pick-number, screenshot or none), which tells look_closer's screenshot
#                                           question from the model's own free question
```

- [ ] **Step 5: Run the tests to see them pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.agent.AppOwnerPortTest --tests dev.kestrel.app.debug.AgentHookAnswersTest`
Expected: PASS.

Run: `scripts\gradle.ps1 :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL, A₀ + 2 tests.

Run: `scripts\test-scripts.ps1`
Expected: every test passes in both shells, P₀ in each.

Run: `scripts\gradle.ps1 --stop`

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/agent app/src/debug/kotlin/dev/kestrel/app/debug app/src/test/kotlin/dev/kestrel/app/agent/AppOwnerPortTest.kt app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt scripts/hook.ps1
git commit -m "feat(debug): agent-state names the open question's source, so that the suite tells look_closer's screenshot question from ask_user" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 2: `agent-task` counts the owner's touches and the signals among them

T2: Kestrel's end of a task keeps how many of the owner's touches the watcher reported and how many of them were signals without a finger (branch B's, or a cancelled gesture of Kestrel's). These are counts that the runner keeps of its own task, as it keeps the report; `agent-task -Result` answers them as `ownerTouches=` and `signals=`, so that a CANCELLED run says whether a touch or a signal ended it.

**Files:**
- Modify: `app/src/main/kotlin/dev/kestrel/app/agent/AgentEnvironment.kt`, `app/src/main/kotlin/dev/kestrel/app/agent/AppAgentEnvironment.kt`, `app/src/main/kotlin/dev/kestrel/app/agent/AgentRunner.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHookAnswers.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, `scripts/hook.ps1`
- Test: `app/src/test/kotlin/dev/kestrel/app/agent/AgentRunnerTest.kt`, `app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt`

**Interfaces:**
- Consumes: `OwnActions.SIGNAL_DEVICE` (the device of a touch that no input event carried); Task 1's `AgentHookAnswers` import of `AnswerKind`.
- Produces: `OwnerTouch(lateEchoMillis: Long? = null, signal: Boolean = false)`; `AgentRunner.Ended(report: TaskReport, spoken: String, touches: Int = 0, signals: Int = 0)`; `AgentHookAnswers.result(ended: AgentRunner.Ended, marker: String?, recipient: String?, digits: String? = null): String`, whose answer adds ` ownerTouches=<n> signals=<n>` after `cacheWrite=`, before ` digits=`. Task 3 adds `Ended.evidence` and the marker's places.

Counts: `:app` JVM N + 1 (A₀ + 3). Pester unchanged.

- [ ] **Step 1: Write the failing tests**

Add this test to `app/src/test/kotlin/dev/kestrel/app/agent/AgentRunnerTest.kt`, before its last closing brace:

```kotlin
    @Test
    fun aTasksEndCountsTheOwnersTouchesAndTheSignalsAmongThem() = runTest {
        owner.gateWait = CompletableDeferred()
        val runner = runner()
        runner.run(1, "open Viber")
        runCurrent()

        touches.emit(OwnerTouch())
        touches.emit(OwnerTouch(signal = true))
        runCurrent()
        runner.steer(Effect.CancelTask)
        advanceUntilIdle()

        val ended = runner.ended(1)!!
        assertEquals(listOf(2, 1), listOf(ended.touches, ended.signals))
    }
```

In `app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt`, replace:

```kotlin
import dev.kestrel.agent.port.AnswerKind
```

with:

```kotlin
import dev.kestrel.agent.port.AnswerKind
import dev.kestrel.app.agent.AgentRunner
```

In `app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt`, replace:

```kotlin
        val line = AgentHookAnswers.result(report, "Sent to Saved Messages. I told Ana that you're late for maple heron.", "Maple heron", "saved messages")

        assertEquals(
            "state=done end=FINISHED steps=5 workMs=41000 credits=0.50 confirmed=1 unconfirmed=0 shareItems=0 recipient=match marker=1 " +
                "beforeTestMode=0 model=scripted-light in=5000 out=250 cacheRead=2500 cacheWrite=0",
            line,
        )
```

with:

```kotlin
        val ended = AgentRunner.Ended(report, "Sent to Saved Messages. I told Ana that you're late for maple heron.", touches = 2, signals = 1)
        val line = AgentHookAnswers.result(ended, "Maple heron", "saved messages")

        assertEquals(
            "state=done end=FINISHED steps=5 workMs=41000 credits=0.50 confirmed=1 unconfirmed=0 shareItems=0 recipient=match marker=1 " +
                "beforeTestMode=0 model=scripted-light in=5000 out=250 cacheRead=2500 cacheWrite=0 ownerTouches=2 signals=1",
            line,
        )
```

In `app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt`, replace:

```kotlin
        assertTrue(AgentHookAnswers.result(report, "It is 87 percent.", null, null, "87").endsWith(" digits=1"))
        assertFalse(AgentHookAnswers.result(report, "It is 87 percent.", null, null).contains("digits="))
```

with:

```kotlin
        assertTrue(AgentHookAnswers.result(AgentRunner.Ended(report, "It is 87 percent."), null, null, "87").endsWith(" digits=1"))
        assertFalse(AgentHookAnswers.result(AgentRunner.Ended(report, "It is 87 percent."), null, null).contains("digits="))
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.agent.AgentRunnerTest --tests dev.kestrel.app.debug.AgentHookAnswersTest`
Expected: FAIL, the compiler reports `No parameter with name 'signal'`, `Unresolved reference 'touches'` and `'signals'`, and a type mismatch for `result`'s first argument.

- [ ] **Step 3: Count the touches**

In `app/src/main/kotlin/dev/kestrel/app/agent/AgentEnvironment.kt`, replace:

```kotlin
/** A touch of the owner's that the watcher reported; [lateEchoMillis] is set when it came soon after an action of Kestrel's (D19). */
internal data class OwnerTouch(val lateEchoMillis: Long? = null)
```

with:

```kotlin
/**
 * A touch of the owner's that the watcher reported; [lateEchoMillis] is set when it came soon after an action of Kestrel's
 * (D19), and [signal] when no input event carried it: branch B's signal of the screen's events, or a gesture of Kestrel's
 * that a touch cancelled.
 */
internal data class OwnerTouch(val lateEchoMillis: Long? = null, val signal: Boolean = false)
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AppAgentEnvironment.kt`, replace:

```kotlin
            OwnerTouch(touch.sinceNodeActionMillis?.takeIf { it <= OwnActions.LATE_ECHO_MILLIS })
```

with:

```kotlin
            OwnerTouch(touch.sinceNodeActionMillis?.takeIf { it <= OwnActions.LATE_ECHO_MILLIS }, signal = touch.deviceId == OwnActions.SIGNAL_DEVICE)
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AgentRunner.kt`, replace:

```kotlin
    /** How task number n ended, for the debug hook `agent-task -Result`: its report and the sentence that Kestrel said. */
    internal data class Ended(val report: TaskReport, val spoken: String) {
        override fun toString(): String = "Ended(report=$report, spoken=${spoken.length} characters)"
    }
```

with:

```kotlin
    /**
     * How task number n ended, for the debug hook `agent-task -Result`: its report, the sentence that Kestrel said at its
     * end, the owner's touches that the watcher reported during the task ([touches]) and how many of them were signals
     * without a finger ([signals]: branch B's, or a cancelled gesture of Kestrel's).
     */
    internal data class Ended(val report: TaskReport, val spoken: String, val touches: Int = 0, val signals: Int = 0) {
        override fun toString(): String = "Ended(report=$report, spoken=${spoken.length} characters, touches=$touches, signals=$signals)"
    }
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AgentRunner.kt`, replace:

```kotlin
        // A pause that came before the owner port existed; the owner port gets it as soon as it exists (design D2).
        var pausedEarly = false
    }
```

with:

```kotlin
        // A pause that came before the owner port existed; the owner port gets it as soon as it exists (design D2).
        var pausedEarly = false

        // The owner's touches that the watcher reported during the task, and the signals among them; only the task's touch
        // collector writes them.
        @Volatile
        var touches = 0

        @Volatile
        var signals = 0
    }
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AgentRunner.kt`, replace:

```kotlin
                val touches = launch { ports.touches.collect { touched(ports, it) } }
```

with:

```kotlin
                val touches = launch { ports.touches.collect { touched(running, ports, it) } }
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AgentRunner.kt`, replace:

```kotlin
                        result.recording?.let { kept = it to nowMillis() }
                        record(task, result, sentence)
```

with:

```kotlin
                        result.recording?.let { kept = it to nowMillis() }
                        record(task, Ended(result, sentence, running.touches, running.signals))
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AgentRunner.kt`, replace:

```kotlin
            record(task, result, sentence.orEmpty())
```

with:

```kotlin
            record(task, Ended(result, sentence.orEmpty()))
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AgentRunner.kt`, replace:

```kotlin
    private suspend fun touched(ready: Prepared.Ready, touch: OwnerTouch) {
        send(Event.OwnerTouched)
```

with:

```kotlin
    private suspend fun touched(running: Running, ready: Prepared.Ready, touch: OwnerTouch) {
        running.touches++
        if (touch.signal) running.signals++
        send(Event.OwnerTouched)
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AgentRunner.kt`, replace:

```kotlin
    private fun refuse(task: Int, sentence: String) {
        record(task, TaskReport(EndReason.NO_PROFILE), sentence)
        send(Event.TaskEnded(task, sentence))
    }

    private fun record(task: Int, result: TaskReport, sentence: String) = synchronized(lock) {
        ends[task] = Ended(result, sentence)
        while (ends.size > MAX_ENDS) ends.remove(ends.keys.first())
    }
```

with:

```kotlin
    private fun refuse(task: Int, sentence: String) {
        record(task, Ended(TaskReport(EndReason.NO_PROFILE), sentence))
        send(Event.TaskEnded(task, sentence))
    }

    private fun record(task: Int, ended: Ended) = synchronized(lock) {
        ends[task] = ended
        while (ends.size > MAX_ENDS) ends.remove(ends.keys.first())
    }
```

- [ ] **Step 4: Answer them in `agent-task`**

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHookAnswers.kt`, replace:

```kotlin
import dev.kestrel.agent.port.AnswerKind
```

with:

```kotlin
import dev.kestrel.agent.port.AnswerKind
import dev.kestrel.app.agent.AgentRunner
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHookAnswers.kt`, replace:

```kotlin
    /** agent-task -Result's answer for a task that ended; with [digits] (agent-task -Digits), `digits=1|0` at its end. */
    fun result(report: TaskReport, spoken: String, marker: String?, recipient: String?, digits: String? = null): String =
        "state=done end=${report.end} steps=${report.steps} workMs=${report.workMillis} " +
            "credits=${String.format(Locale.ROOT, "%.2f", report.credits)} confirmed=${report.confirmed} " +
            "unconfirmed=${report.unconfirmed} shareItems=${report.shareItems} recipient=${recipient(report.recipients, recipient)} " +
            "marker=${marker(spoken, marker)} beforeTestMode=${report.beforeTestMode} model=${word(report.modelId)} " +
            "in=${report.inputTokens} out=${report.outputTokens} cacheRead=${report.cacheRead} cacheWrite=${report.cacheWrite}" +
            (digits?.let { " digits=${digits(spoken, it)}" } ?: "")
```

with:

```kotlin
    /**
     * agent-task -Result's answer for a task that [ended]: its report's end and counts, whether its spoken sentence holds
     * [marker], the owner's touches that the watcher reported (`ownerTouches=`) and the signals among them (`signals=`); with
     * [digits] (agent-task -Digits), `digits=1|0` at its end.
     */
    fun result(ended: AgentRunner.Ended, marker: String?, recipient: String?, digits: String? = null): String {
        val report = ended.report
        return "state=done end=${report.end} steps=${report.steps} workMs=${report.workMillis} " +
            "credits=${String.format(Locale.ROOT, "%.2f", report.credits)} confirmed=${report.confirmed} " +
            "unconfirmed=${report.unconfirmed} shareItems=${report.shareItems} recipient=${recipient(report.recipients, recipient)} " +
            "marker=${marker(ended.spoken, marker)} beforeTestMode=${report.beforeTestMode} model=${word(report.modelId)} " +
            "in=${report.inputTokens} out=${report.outputTokens} cacheRead=${report.cacheRead} cacheWrite=${report.cacheWrite} " +
            "ownerTouches=${ended.touches} signals=${ended.signals}" +
            (digits?.let { " digits=${digits(ended.spoken, it)}" } ?: "")
    }
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentTaskHooks.kt`, replace:

```kotlin
        return AgentHookAnswers.result(
            ended.report, ended.spoken, intent.getStringExtra(EXTRA_MARKER), intent.getStringExtra(EXTRA_RECIPIENT), intent.getStringExtra(EXTRA_DIGITS),
        )
```

with:

```kotlin
        return AgentHookAnswers.result(ended, intent.getStringExtra(EXTRA_MARKER), intent.getStringExtra(EXTRA_RECIPIENT), intent.getStringExtra(EXTRA_DIGITS))
```

In `scripts/hook.ps1`, replace:

```powershell
#                                           holds 2025550143, but "58 percent, about 7 hours" does not hold 587
```

with:

```powershell
#                                           holds 2025550143, but "58 percent, about 7 hours" does not hold 587;
#                                           ownerTouches= and signals= count the owner's touches that the touch watcher
#                                           reported during the task and the signals among them without a finger (branch
#                                           B's, a cancelled gesture)
```

- [ ] **Step 5: Run the tests to see them pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.agent.AgentRunnerTest --tests dev.kestrel.app.debug.AgentHookAnswersTest`
Expected: PASS.

Run: `scripts\gradle.ps1 :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`
Expected: BUILD SUCCESSFUL, A₀ + 3 tests.

Run: `scripts\test-scripts.ps1`
Expected: every test passes in both shells, P₀ in each.

Run: `scripts\gradle.ps1 --stop`

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/agent app/src/debug/kotlin/dev/kestrel/app/debug app/src/test/kotlin/dev/kestrel/app/agent/AgentRunnerTest.kt app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt scripts/hook.ps1
git commit -m "feat(debug): agent-task counts the owner's touches and the signals among them" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 3: The task's witness: the `say` texts and the run's marker in the data, in debug builds only

T3 and T4. The debug hooks need two things that only a running task sees: what Kestrel said with `say`, which the owner hears as he hears the end's sentence (design D29's "Kestrel's spoken reply"), and whether a data tool's result held the run's marker. Both are observations of the debug build, so they get their own collaborator: `:app`'s main code defines the seam, `TaskWitness`, which every task has and which in a release build observes nothing (`TaskWitness.NONE`); the debug source set installs `DebugTaskWitness` per task through `AgentRunner.witnesses`, beside the task options' seam that it installs already, and `DebugTaskWitness` alone keeps the texts and wraps the data tools in `MarkerWatch` when the run gave a marker. No release code checks a debug flag, and no release build keeps a text. `agent-task -Result` then compares the marker and the digits with the end's sentence and every `say` text, and answers `markerSaid=` and `markerInData=`, which separate "the data lacked the marker" (the environment), "it was said with `say`" and "the model left it out" (Kestrel's result). The test app's marker notification holds the marker in its body too (T4).

**Files:**
- Create: `app/src/main/kotlin/dev/kestrel/app/agent/TaskWitness.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/DebugTaskWitness.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/MarkerWatch.kt`, `app/src/testDebug/kotlin/dev/kestrel/app/debug/DebugTaskWitnessTest.kt`
- Modify: `app/src/main/kotlin/dev/kestrel/app/agent/AgentEnvironment.kt`, `app/src/main/kotlin/dev/kestrel/app/agent/AppOwnerPort.kt`, `app/src/main/kotlin/dev/kestrel/app/agent/AppAgentEnvironment.kt`, `app/src/main/kotlin/dev/kestrel/app/agent/AgentRunner.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt`, `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHookAnswers.kt`, `testapp/src/main/kotlin/dev/kestrel/testapp/NotificationActivity.kt`, `scripts/hook.ps1`, `docs/superpowers/specs/2026-11-12-kestrel-design.md`
- Test: `app/src/test/kotlin/dev/kestrel/app/agent/AppOwnerPortTest.kt`, `app/src/test/kotlin/dev/kestrel/app/agent/AgentRunnerTest.kt`, `app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt`, `app/src/testDebug/kotlin/dev/kestrel/app/debug/HookRulesTest.kt`

**Interfaces:**
- Consumes: Task 2's `AgentRunner.Ended` and `AgentHookAnswers.result`; `DataPort`, `DataResult.Ok`, `DataLine.fields`; `VoiceHooks.taskOptions` and `VoiceHooks.tag`; `FakeData` of `:core:agent`'s test fixtures.
- Produces: `TaskWitness` (`said(text: String)`, `watch(data: DataPort): DataPort`, `evidence: TaskEvidence`, `TaskWitness.NONE`); `TaskEvidence(said: List<String> = emptyList(), markerInData: Boolean? = null)`; `AgentRunner.witnesses: ((options: Map<String, String>) -> TaskWitness)?`; `TaskStart.witness: TaskWitness` (default `NONE`); `AppOwnerPort(…, witness: TaskWitness = TaskWitness.NONE, …)`; `AgentRunner.Ended.evidence: TaskEvidence`; `DebugTaskWitness.open(options: Map<String, String>): TaskWitness`, `DebugTaskWitness.MARKER = "marker"`, `DebugTaskWitness.MAX_SAID = 32`; `MarkerWatch(data: DataPort, marker: String)` with `seen` and `MarkerWatch.holds(text: String, marker: String): Boolean`; `VoiceHooks.taskOptions(model: String?, live: Boolean?, marker: String? = null)`; `agent-task -Result -Marker`'s answer adds ` markerSaid=<0|1> markerInData=<0|1|none>` after `signals=`. Task 6's runner sends the marker with `voice -Send heard --es marker` and reads both.

Counts: `:app` JVM N + 5 (A₀ + 8). Pester unchanged.

- [ ] **Step 1: Write the failing tests**

Create `app/src/testDebug/kotlin/dev/kestrel/app/debug/DebugTaskWitnessTest.kt`:

```kotlin
package dev.kestrel.app.debug

import dev.kestrel.agent.port.DataField
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.testing.FakeData
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The debug build's witness of a task: its newest say texts and the run's marker in the data tools (plan 6B-basefix). */
class DebugTaskWitnessTest {
    @Test
    fun aWitnessKeepsTheNewestSayTextsAndWithAMarkerWatchesTheDataTools() = runTest {
        val data = FakeData()
        val notification = DataResult.Ok(listOf(DataLine("Notification 1.", listOf(DataField("title", "Kestrel test Maple heron")))))
        data.answer("list_notifications", notification)
        data.answer("list_events", DataResult.Ok(listOf(DataLine("Event 1.", listOf(DataField("title", "heron maple"))))))
        val witness = DebugTaskWitness.open(mapOf(DebugTaskWitness.MARKER to "maple heron"))
        val watched = witness.watch(data)

        watched.listEvents(LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 25))
        assertEquals(false, witness.evidence.markerInData)
        assertEquals(notification, watched.listNotifications())
        assertEquals(true, witness.evidence.markerInData)
        repeat(DebugTaskWitness.MAX_SAID + 1) { witness.said("Line $it.") }
        assertEquals((1..DebugTaskWitness.MAX_SAID).map { "Line $it." }, witness.evidence.said)

        // Without a marker the data tools stay as they are, and the evidence says that the task had none.
        val plain = DebugTaskWitness.open(emptyMap())
        assertSame(data, plain.watch(data))
        assertNull(plain.evidence.markerInData)
    }

    @Test
    fun aMarkerIsHeldOnlyWhenItsWordsComeInOrder() {
        assertTrue(MarkerWatch.holds("Kestrel test: Maple heron.", "maple heron"))
        assertFalse(MarkerWatch.holds("The e-mail about heron maple.", "maple heron"))
        assertFalse(MarkerWatch.holds("maple", "maple heron"))
    }
}
```

Add this test to `app/src/test/kotlin/dev/kestrel/app/agent/AppOwnerPortTest.kt`, before its last closing brace:

```kotlin
    @Test
    fun whatKestrelSaysGoesToTheTasksWitnessToo() = runTest {
        val heard = mutableListOf<String>()
        val witness = object : TaskWitness {
            override fun said(text: String) {
                heard += text
            }

            override fun watch(data: DataPort): DataPort = data

            override val evidence: TaskEvidence get() = TaskEvidence(heard.toList())
        }
        val witnessed = AppOwnerPort(1, { sent += it }, screen, audio, signals, { android.await() }, { PermissionEnd.PARTLY }, witness = witness)

        witnessed.say("Your battery is at 87 percent.")

        assertEquals(listOf<Event>(Event.TaskSays(1, "Your battery is at 87 percent.")), sent)
        assertEquals(listOf("Your battery is at 87 percent."), heard)
    }
```

In `app/src/test/kotlin/dev/kestrel/app/agent/AppOwnerPortTest.kt`, replace:

```kotlin
import dev.kestrel.agent.port.ConfirmEnd
```

with:

```kotlin
import dev.kestrel.agent.port.ConfirmEnd
import dev.kestrel.agent.port.DataPort
```

Add this test to `app/src/test/kotlin/dev/kestrel/app/agent/AgentRunnerTest.kt`, before its last closing brace:

```kotlin
    @Test
    fun anInstalledWitnessGetsTheTasksOptionsAndItsEvidenceIsTheTasksEnd() = runTest {
        val given = mutableListOf<Map<String, String>>()
        val runner = runner(options = mapOf("marker" to "maple heron"))
        runner.witnesses = { options ->
            given += options
            object : TaskWitness {
                override fun said(text: String) = Unit

                override fun watch(data: DataPort): DataPort = data

                override val evidence: TaskEvidence get() = TaskEvidence(listOf("Opening Viber."), markerInData = true)
            }
        }

        runner.run(1, "open Viber")
        advanceUntilIdle()

        assertEquals(listOf(mapOf("marker" to "maple heron")), given)
        assertEquals(TaskEvidence(listOf("Opening Viber."), markerInData = true), runner.ended(1)!!.evidence)
        // Without an installed witness, as in a release build, a task observes nothing.
        val plain = runner()
        plain.run(2, "open Viber")
        advanceUntilIdle()
        assertSame(TaskWitness.NONE, starts.last().witness)
        assertEquals(TaskEvidence(), plain.ended(2)!!.evidence)
    }
```

In `app/src/test/kotlin/dev/kestrel/app/agent/AgentRunnerTest.kt`, replace:

```kotlin
import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.Gate
```

with:

```kotlin
import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.DataPort
import dev.kestrel.agent.port.Gate
```

In `app/src/test/kotlin/dev/kestrel/app/agent/AgentRunnerTest.kt`, replace:

```kotlin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentRunnerTest {
```

with:

```kotlin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentRunnerTest {
```

Add this test to `app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt`, before its last closing brace:

```kotlin
    @Test
    fun theMarkerAndTheDigitsCountAlsoInWhatKestrelSaidDuringTheTask() {
        val report = TaskReport(EndReason.FINISHED, summary = "s", steps = 2)
        val evidence = TaskEvidence(listOf("Kestrel test maple heron: a reminder about maple heron.", "Your battery is at 87 percent."), markerInData = true)

        val line = AgentHookAnswers.result(AgentRunner.Ended(report, "I read your notifications.", evidence = evidence), "maple heron", null, "87")

        assertTrue(line, line.contains(" marker=1 "))
        assertTrue(line, line.endsWith(" ownerTouches=0 signals=0 markerSaid=1 markerInData=1 digits=1"))
        assertTrue(AgentHookAnswers.result(AgentRunner.Ended(report, "Kestrel test maple heron."), "maple heron", null).endsWith(" markerSaid=0 markerInData=none"))
        assertFalse(line.contains("reminder"))
    }
```

In `app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt`, replace:

```kotlin
import dev.kestrel.app.agent.AgentRunner
```

with:

```kotlin
import dev.kestrel.app.agent.AgentRunner
import dev.kestrel.app.agent.TaskEvidence
```

In `app/src/testDebug/kotlin/dev/kestrel/app/debug/AgentHookAnswersTest.kt`, replace:

```kotlin
                "beforeTestMode=0 model=scripted-light in=5000 out=250 cacheRead=2500 cacheWrite=0 ownerTouches=2 signals=1",
```

with:

```kotlin
                "beforeTestMode=0 model=scripted-light in=5000 out=250 cacheRead=2500 cacheWrite=0 ownerTouches=2 signals=1 markerSaid=0 markerInData=none",
```

In `app/src/testDebug/kotlin/dev/kestrel/app/debug/HookRulesTest.kt`, replace:

```kotlin
        assertEquals(mapOf("model" to "test-model", "live" to "false"), VoiceHooks.taskOptions(model = "test-model", live = false))
```

with:

```kotlin
        assertEquals(mapOf("model" to "test-model", "live" to "false"), VoiceHooks.taskOptions(model = "test-model", live = false))
        assertEquals(mapOf(DebugTaskWitness.MARKER to "maple heron"), VoiceHooks.taskOptions(model = null, live = null, marker = "maple heron"))
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.debug.DebugTaskWitnessTest --tests dev.kestrel.app.agent.AppOwnerPortTest --tests dev.kestrel.app.agent.AgentRunnerTest --tests dev.kestrel.app.debug.AgentHookAnswersTest --tests dev.kestrel.app.debug.HookRulesTest`
Expected: FAIL, the compiler reports `Unresolved reference 'TaskWitness'`, `'TaskEvidence'`, `'DebugTaskWitness'`, `'MarkerWatch'`, `'witnesses'` and `'evidence'`, and `No parameter with name 'witness'` and `'marker'`.

- [ ] **Step 3: The seam in the main code**

Create `app/src/main/kotlin/dev/kestrel/app/agent/TaskWitness.kt`:

```kotlin
package dev.kestrel.app.agent

import dev.kestrel.agent.port.DataPort

/**
 * What a debug build observes of one task for its hooks (design D28 of sub-project 5; plan 6B-basefix): the texts that
 * Kestrel says with `say` ([said]), and the task's data tools, which [watch] may wrap. The debug source set installs a
 * witness per task through [AgentRunner.witnesses]; a release build has none, and its tasks get [NONE], which observes
 * nothing, so that only the debug build keeps what Kestrel said.
 */
internal interface TaskWitness {
    fun said(text: String)

    /** The data tools that the task uses: [data] itself, or a view of it that observes their results. */
    fun watch(data: DataPort): DataPort

    /** What the witness observed so far. */
    val evidence: TaskEvidence

    companion object {
        /** The witness of every task of a release build: it observes nothing. */
        val NONE: TaskWitness = object : TaskWitness {
            override fun said(text: String) = Unit

            override fun watch(data: DataPort): DataPort = data

            override val evidence: TaskEvidence get() = TaskEvidence()

            override fun toString(): String = "TaskWitness.NONE"
        }
    }
}

/**
 * What a task's witness observed: the texts that Kestrel said with `say` ([said]), and whether a data tool's result held
 * the run's marker ([markerInData]; null when the task had no marker). toString prints no text.
 */
internal data class TaskEvidence(val said: List<String> = emptyList(), val markerInData: Boolean? = null) {
    override fun toString(): String = "TaskEvidence(said=${said.size} texts, markerInData=$markerInData)"
}
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AgentEnvironment.kt`, replace:

```kotlin
    val acted: () -> Unit = {},
    val local: Boolean = false,
) {
```

with:

```kotlin
    val acted: () -> Unit = {},
    val local: Boolean = false,
    /** What the debug build observes of the task; [TaskWitness.NONE] in a release build (plan 6B-basefix). */
    val witness: TaskWitness = TaskWitness.NONE,
) {
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AppOwnerPort.kt`, replace:

```kotlin
    private val granted: (List<String>) -> PermissionEnd,
    private val audioSettleMillis: Long = AUDIO_SETTLE_MILLIS,
```

with:

```kotlin
    private val granted: (List<String>) -> PermissionEnd,
    // What the debug build observes of the task, which hears every say text too (plan 6B-basefix).
    private val witness: TaskWitness = TaskWitness.NONE,
    private val audioSettleMillis: Long = AUDIO_SETTLE_MILLIS,
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AppOwnerPort.kt`, replace:

```kotlin
    override suspend fun say(text: String) = send(Event.TaskSays(task, text))
```

with:

```kotlin
    override suspend fun say(text: String) {
        witness.said(text)
        send(Event.TaskSays(task, text))
    }
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AppAgentEnvironment.kt`, replace:

```kotlin
        val data = AndroidDataPort(context, open, agentSettings.agentFree::isAgentFree)
```

with:

```kotlin
        val data = start.witness.watch(AndroidDataPort(context, open, agentSettings.agentFree::isAgentFree))
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AppAgentEnvironment.kt`, replace:

```kotlin
            askAndroid,
            { Permissions.granted(context, it) },
        )
```

with:

```kotlin
            askAndroid,
            { Permissions.granted(context, it) },
            witness = start.witness,
        )
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AgentRunner.kt`, replace:

```kotlin
    /** Debug builds only: the self-chat aliases that the hook `agent-settings` set (design D18); empty otherwise. */
    @Volatile
    internal var selfChatAliases: Map<String, String> = emptyMap()
```

with:

```kotlin
    /** Debug builds only: the self-chat aliases that the hook `agent-settings` set (design D18); empty otherwise. */
    @Volatile
    internal var selfChatAliases: Map<String, String> = emptyMap()

    /**
     * Debug builds only: makes the witness of each task from the task's options (plan 6B-basefix), which the debug hooks
     * install with the task options' seam. Null in a release build, whose tasks get [TaskWitness.NONE].
     */
    @Volatile
    internal var witnesses: ((options: Map<String, String>) -> TaskWitness)? = null
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AgentRunner.kt`, replace:

```kotlin
            val start = TaskStart(task, newId(task), text, choice, selfChatAliases, backstop::acted)
```

with:

```kotlin
            val witness = witnesses?.invoke(options) ?: TaskWitness.NONE
            val start = TaskStart(task, newId(task), text, choice, selfChatAliases, backstop::acted, witness = witness)
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AgentRunner.kt`, replace:

```kotlin
                        record(task, Ended(result, sentence, running.touches, running.signals))
```

with:

```kotlin
                        record(task, Ended(result, sentence, running.touches, running.signals, witness.evidence))
```

In `app/src/main/kotlin/dev/kestrel/app/agent/AgentRunner.kt`, replace:

```kotlin
     * end, the owner's touches that the watcher reported during the task ([touches]) and how many of them were signals
     * without a finger ([signals]: branch B's, or a cancelled gesture of Kestrel's).
     */
    internal data class Ended(val report: TaskReport, val spoken: String, val touches: Int = 0, val signals: Int = 0) {
        override fun toString(): String = "Ended(report=$report, spoken=${spoken.length} characters, touches=$touches, signals=$signals)"
    }
```

with:

```kotlin
     * end, the owner's touches that the watcher reported during the task ([touches]) and how many of them were signals
     * without a finger ([signals]: branch B's, or a cancelled gesture of Kestrel's), and what the task's witness observed
     * ([evidence]; empty in a release build).
     */
    internal data class Ended(
        val report: TaskReport,
        val spoken: String,
        val touches: Int = 0,
        val signals: Int = 0,
        val evidence: TaskEvidence = TaskEvidence(),
    ) {
        override fun toString(): String =
            "Ended(report=$report, spoken=${spoken.length} characters, touches=$touches, signals=$signals, evidence=$evidence)"
    }
```

- [ ] **Step 4: The debug build's witness**

Create `app/src/debug/kotlin/dev/kestrel/app/debug/MarkerWatch.kt`:

```kotlin
package dev.kestrel.app.debug

import dev.kestrel.agent.port.DataPort
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.port.MediaKinds
import dev.kestrel.commands.text.Normalizer
import java.time.LocalDate

/**
 * The data tools of a task whose run gave a [marker], for agent-task's `markerInData=` (plan 6B-basefix): [seen] is
 * whether a result of a data tool that reads held the marker's words in order in one of its fields. It keeps only that
 * flag, never a value, and passes every result on unchanged.
 */
internal class MarkerWatch(private val data: DataPort, private val marker: String) : DataPort by data {
    @Volatile
    var seen = false
        private set

    override suspend fun findContact(name: String): DataResult = look(data.findContact(name))

    override suspend fun findMedia(from: LocalDate, until: LocalDate, kinds: MediaKinds): DataResult = look(data.findMedia(from, until, kinds))

    override suspend fun listEvents(from: LocalDate, to: LocalDate): DataResult = look(data.listEvents(from, to))

    override suspend fun listNotifications(): DataResult = look(data.listNotifications())

    override suspend fun listSms(from: String?, limit: Int): DataResult = look(data.listSms(from, limit))

    override suspend fun listCalls(limit: Int): DataResult = look(data.listCalls(limit))

    private fun look(result: DataResult): DataResult {
        if (result is DataResult.Ok && result.lines.any { line -> line.fields.any { holds(it.value, marker) } }) seen = true
        return result
    }

    override fun toString(): String = "MarkerWatch(seen=$seen)"

    companion object {
        /** Whether [text] holds [marker]'s words in order, compared as the matcher folds them (design D29 of sub-project 5). */
        fun holds(text: String, marker: String): Boolean {
            val wanted = Normalizer.words(marker)
            if (wanted.isEmpty()) return false
            val words = Normalizer.words(text)
            return (0..words.size - wanted.size).any { start -> wanted.indices.all { words[start + it] == wanted[it] } }
        }
    }
}
```

Create `app/src/debug/kotlin/dev/kestrel/app/debug/DebugTaskWitness.kt`:

```kotlin
package dev.kestrel.app.debug

import dev.kestrel.agent.port.DataPort
import dev.kestrel.app.agent.TaskEvidence
import dev.kestrel.app.agent.TaskWitness

/**
 * The debug build's witness of one task (plan 6B-basefix): it keeps the newest [MAX_SAID] texts that Kestrel said with
 * `say`, for agent-task's marker and digits, and, when the task's options carry a run's [marker] (`voice -Send heard
 * --es marker`), it watches the task's data tools for it ([MarkerWatch]). VoiceHooks installs [open] as
 * AgentRunner.witnesses beside Listening.taskOptions. The texts stay in memory, with the newest tasks' ends that
 * AgentRunner keeps.
 */
internal class DebugTaskWitness(private val marker: String?) : TaskWitness {
    private val texts = ArrayDeque<String>()

    @Volatile
    private var watched: MarkerWatch? = null

    override fun said(text: String) {
        synchronized(texts) {
            texts.addLast(text)
            if (texts.size > MAX_SAID) texts.removeFirst()
        }
    }

    override fun watch(data: DataPort): DataPort = marker?.let { MarkerWatch(data, it).also { watch -> watched = watch } } ?: data

    override val evidence: TaskEvidence
        get() = TaskEvidence(synchronized(texts) { texts.toList() }, marker?.let { watched?.seen ?: false })

    override fun toString(): String = "DebugTaskWitness(marker=${marker != null})"

    companion object {
        /** How many of a task's newest `say` texts the witness keeps. */
        const val MAX_SAID = 32

        /** The task option that carries a run's marker (VoiceHooks.taskOptions). */
        const val MARKER = "marker"

        /** The witness of a task with [options]. */
        fun open(options: Map<String, String>): TaskWitness = DebugTaskWitness(options[MARKER]?.takeIf { it.isNotBlank() })
    }
}
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt`, replace:

```kotlin
            tag(graph, given, event, taskOptions(intent.getStringExtra(EXTRA_MODEL), live))
```

with:

```kotlin
            tag(graph, given, event, taskOptions(intent.getStringExtra(EXTRA_MODEL), live, intent.getStringExtra(EXTRA_MARKER)))
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt`, replace:

```kotlin
                listening.taskOptions = tasks::take
```

with:

```kotlin
                listening.taskOptions = tasks::take
                // Each task's witness keeps what it said and watches its data tools for the run's marker (plan 6B-basefix).
                graph.agent.witnesses = { options -> DebugTaskWitness.open(options) }
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt`, replace:

```kotlin
         * The options that a tagged transcript passes to the task that it starts, from the hook's extras: --es model and
         * --ez live, which sub-project 5's AgentRunner reads (its design's "Shared files and anchors", item 2).
         */
        fun taskOptions(model: String?, live: Boolean?): Map<String, String> = buildMap {
            model?.let { put("model", it) }
            live?.let { put("live", it.toString()) }
```

with:

```kotlin
         * The options that a tagged transcript passes to the task that it starts, from the hook's extras: --es model and
         * --ez live, which sub-project 5's AgentRunner reads (its design's "Shared files and anchors", item 2), and --es
         * marker, a run's marker, which the task's [DebugTaskWitness] watches for (plan 6B-basefix).
         */
        fun taskOptions(model: String?, live: Boolean?, marker: String? = null): Map<String, String> = buildMap {
            model?.let { put("model", it) }
            live?.let { put("live", it.toString()) }
            marker?.takeIf { it.isNotBlank() }?.let { put(DebugTaskWitness.MARKER, it) }
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/VoiceHooks.kt`, replace:

```kotlin
        private const val EXTRA_LIVE = "live"
```

with:

```kotlin
        private const val EXTRA_LIVE = "live"
        private const val EXTRA_MARKER = "marker"
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHookAnswers.kt`, replace:

```kotlin
    /**
     * agent-task -Result's answer for a task that [ended]: its report's end and counts, whether its spoken sentence holds
     * [marker], the owner's touches that the watcher reported (`ownerTouches=`) and the signals among them (`signals=`); with
     * [digits] (agent-task -Digits), `digits=1|0` at its end.
     */
    fun result(ended: AgentRunner.Ended, marker: String?, recipient: String?, digits: String? = null): String {
        val report = ended.report
        return "state=done end=${report.end} steps=${report.steps} workMs=${report.workMillis} " +
            "credits=${String.format(Locale.ROOT, "%.2f", report.credits)} confirmed=${report.confirmed} " +
            "unconfirmed=${report.unconfirmed} shareItems=${report.shareItems} recipient=${recipient(report.recipients, recipient)} " +
            "marker=${marker(ended.spoken, marker)} beforeTestMode=${report.beforeTestMode} model=${word(report.modelId)} " +
            "in=${report.inputTokens} out=${report.outputTokens} cacheRead=${report.cacheRead} cacheWrite=${report.cacheWrite} " +
            "ownerTouches=${ended.touches} signals=${ended.signals}" +
            (digits?.let { " digits=${digits(ended.spoken, it)}" } ?: "")
    }
```

with:

```kotlin
    /**
     * agent-task -Result's answer for a task that [ended]: its report's end and counts; `marker=1` when the sentence that
     * Kestrel said at the end or one of its `say` texts holds [marker]'s words in order, because the owner hears both
     * (design D29); the owner's touches that the watcher reported (`ownerTouches=`) and the signals among them
     * (`signals=`); with [marker] also `markerSaid=1|0`, whether a `say` text held it, and `markerInData=1|0|none`, whether
     * a data tool's result held the run's marker (none when the task had none); and with [digits] (agent-task -Digits),
     * `digits=1|0` at its end, for the end's sentence and the `say` texts alike (plan 6B-basefix).
     */
    fun result(ended: AgentRunner.Ended, marker: String?, recipient: String?, digits: String? = null): String {
        val report = ended.report
        val said = ended.evidence.said
        val texts = said + ended.spoken
        val inData = ended.evidence.markerInData?.let { seen -> if (seen) "1" else "0" } ?: "none"
        return "state=done end=${report.end} steps=${report.steps} workMs=${report.workMillis} " +
            "credits=${String.format(Locale.ROOT, "%.2f", report.credits)} confirmed=${report.confirmed} " +
            "unconfirmed=${report.unconfirmed} shareItems=${report.shareItems} recipient=${recipient(report.recipients, recipient)} " +
            "marker=${anyOf(texts) { marker(it, marker) }} beforeTestMode=${report.beforeTestMode} model=${word(report.modelId)} " +
            "in=${report.inputTokens} out=${report.outputTokens} cacheRead=${report.cacheRead} cacheWrite=${report.cacheWrite} " +
            "ownerTouches=${ended.touches} signals=${ended.signals}" +
            (marker?.let { wanted -> " markerSaid=${anyOf(said) { marker(it, wanted) }} markerInData=$inData" } ?: "") +
            (digits?.let { wanted -> " digits=${anyOf(texts) { digits(it, wanted) }}" } ?: "")
    }

    // 1 when one of [texts] gives 1, else 0; each text is compared alone, so that no match spans two of them.
    private fun anyOf(texts: List<String>, test: (String) -> Int): Int = if (texts.any { test(it) == 1 }) 1 else 0
```

In `app/src/debug/kotlin/dev/kestrel/app/debug/AgentHookAnswers.kt`, replace:

```kotlin
    fun marker(spoken: String, marker: String?): Int {
        val wanted = marker?.let { Normalizer.words(it) }.orEmpty()
        if (wanted.isEmpty()) return 0
        val words = Normalizer.words(spoken)
        return if ((0..words.size - wanted.size).any { start -> wanted.indices.all { words[start + it] == wanted[it] } }) 1 else 0
    }
```

with:

```kotlin
    fun marker(spoken: String, marker: String?): Int = if (marker != null && MarkerWatch.holds(spoken, marker)) 1 else 0
```

In `scripts/hook.ps1`, replace:

```powershell
#   voice -Send heard -Text 'text' [-Named] [-Tag k [-Model id] [-Live]]
```

with:

```powershell
#   voice -Send heard -Text 'text' [-Named] [-Tag k [-Model id] [-Live] [-Marker 'words']]
```

In `scripts/hook.ps1`, replace:

```powershell
#                                           voice-state -Tag; -Model and -Live go with a task that the transcript
#                                           starts, for sub-project 5's agent. On a phone, "yes, call" is refused and
#                                           never sent
```

with:

```powershell
#                                           voice-state -Tag; -Model, -Live and -Marker go with a task that the
#                                           transcript starts, for sub-project 5's agent (-Marker for agent-task's
#                                           markerInData=). On a phone, "yes, call" is refused and never sent
```

In `scripts/hook.ps1`, replace:

```powershell
#                                           B's, a cancelled gesture)
```

with:

```powershell
#                                           B's, a cancelled gesture). The marker and the digits count in the spoken
#                                           reply and in what the task said with say; with -Marker also markerSaid=1
#                                           when a say text held it, and markerInData=1 when a data tool's result held
#                                           the marker that voice -Marker gave the task (none when it gave none)
```

- [ ] **Step 5: Put the marker into the notification's body (T4) and the spec's sentence**

In `testapp/src/main/kotlin/dev/kestrel/testapp/NotificationActivity.kt`, replace:

```kotlin
                    .setContentTitle("Kestrel test ${intent.getStringExtra(EXTRA_MARKER)}")
                    .setContentText("A test notification of Kestrel's evaluation.")
```

with:

```kotlin
                    .setContentTitle("Kestrel test ${intent.getStringExtra(EXTRA_MARKER)}")
                    // The marker is in the body too, so that a reply that reads the body passes (plan 6B-basefix T4).
                    .setContentText("A reminder about ${intent.getStringExtra(EXTRA_MARKER)}.")
```

In `testapp/src/main/kotlin/dev/kestrel/testapp/NotificationActivity.kt`, replace:

```kotlin
 * D9 of sub-project 5). With the extra `marker` it posts a notification titled "Kestrel test <marker>", which
 * :tools:eval's notification-read reads back (design D17 of sub-project 6). The tests grant the test app the notification
 * permission.
```

with:

```kotlin
 * D9 of sub-project 5). With the extra `marker` it posts a notification titled "Kestrel test <marker>" whose text is "A
 * reminder about <marker>.", which :tools:eval's notification-read reads back (design D17 of sub-project 6). The tests
 * grant the test app the notification permission.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
and Kestrel's spoken reply, which the hook compares on the phone with the run's marker or number and answers only whether it matched.
```

with:

```text
and what Kestrel said in the task, its spoken reply and every sentence that the agent's `say` spoke, which the debug build's hook compares on the phone with the run's marker or number and answers only whether it matched, whether a `say` sentence held the marker, and whether a data tool's result held it.
```

- [ ] **Step 6: Run the tests to see them pass**

Run: `scripts\gradle.ps1 :app:testDebugUnitTest --tests dev.kestrel.app.debug.DebugTaskWitnessTest --tests dev.kestrel.app.agent.AppOwnerPortTest --tests dev.kestrel.app.agent.AgentRunnerTest --tests dev.kestrel.app.debug.AgentHookAnswersTest --tests dev.kestrel.app.debug.HookRulesTest`
Expected: PASS.

Run: `scripts\gradle.ps1 :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:compileReleaseKotlin :testapp:assembleDebug`
Expected: BUILD SUCCESSFUL, A₀ + 8 tests; the release build compiles without the debug witness.

Run: `scripts\test-scripts.ps1`
Expected: every test passes in both shells, P₀ in each.

Run: `scripts\gradle.ps1 --stop`

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/kotlin/dev/kestrel/app/agent app/src/debug/kotlin/dev/kestrel/app/debug app/src/test/kotlin/dev/kestrel/app/agent app/src/testDebug/kotlin/dev/kestrel/app/debug testapp/src/main/kotlin/dev/kestrel/testapp/NotificationActivity.kt scripts/hook.ps1 docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(debug): a debug-only witness per task keeps the say texts and watches the data tools for the run's marker; agent-task compares and answers both" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 4: `open_deep_link`'s `app` among the apps that open the link (B1')

The system prompt tells the model to pass `app: "Google Maps"`, but Google Maps' launcher label is "Maps" on both devices, and `NamePick` scores "Google Maps" against "Maps" at most 0.48, so `linkApp` failed and the model fell back to a chooser or an `api=1` link that test mode refused (maps-place 0/3 on both devices). `linkApp` now takes only the launcher apps that open the link. A name is an app's package name's words without the generic parts only when it equals them exactly (`com.google.android.apps.maps` gives "google maps"), so that a vendor's word alone ("Google", "Samsung") never picks an app; otherwise the launcher's matcher (`NamePick`) matches the name against those apps' labels. The errors for a name that no such app has stay as they are.

**This task decides which app opens a link. Its review runs at max reasoning effort**, with spec 11.4 and 12.1 in the brief: the reviewer checks that the link still goes only to an app that `resolve` names as its handler, that the safety check still judges that app as the link's target (`LinkCheck.check(…, target, …)` with the package that `linkApp` returned), that a messenger, Kestrel or an agent-free app stays refused or confirmed as before, and that the package words never let a generic part (`com`, `android`, `apps`) name an app. A single near miss of a label among the apps that open the link opens in it, as a single near miss among the launcher apps did before (`NamePick`'s "Which one?" list filtered to the handlers); package words never match near.

**Files:**
- Modify: `core/agent/src/main/kotlin/dev/kestrel/agent/loop/ScreenSteps.kt`, `docs/superpowers/specs/2026-11-12-kestrel-design.md`
- Test: `core/agent/src/test/kotlin/dev/kestrel/agent/AgentToolsTest.kt`

**Interfaces:**
- Consumes: `NamePick.pick` and `NamePick.MAX_CHOICES`, and `Normalizer.normalize` of `:core:commands`; `ScreenPort.resolve(IntentSpec)`.
- Produces: `ScreenSteps.packageWords(packageName: String): String?` (internal, in the companion); `linkApp`'s new matching. Task 8's dry-run script names the app "Google Maps".

Counts: `:core:agent` N + 1 (G₀ + 1).

- [ ] **Step 1: Write the failing tests**

In `core/agent/src/test/kotlin/dev/kestrel/agent/AgentToolsTest.kt`, replace:

```kotlin
    // A deep link with the app that the owner named; Android would ask which app opens a geo: link, so without a package
    // it resolves to none, and each app opens only the links of [handles]. Two apps are called Google Maps, and by default
    // only one of them opens the link.
    private fun namedApp(app: String, link: String = "geo:0,0?q=Kalemegdan", handles: Map<String, String> = mapOf("geo:" to maps)): Harness =
        Harness(
            Screens.app("com.android.launcher3", "Home"),
            listOf(call("open_deep_link", null, null, null, "uri" to link, "reason" to "show the place", "app" to app), stop),
            launcher = FakeApps.DEFAULT + LauncherApp("Google Maps", maps, "11.150") + LauncherApp("Google Maps", "com.google.android.apps.mapslite", "1.0"),
        ).apply {
```

with:

```kotlin
    // A deep link with the app that the owner named; Android would ask which app opens a geo: link, so without a package
    // it resolves to none, and each app opens only the links of [handles]. As on real phones, Google Maps' launcher label
    // is "Maps"; Maps Go is called Maps too, and by default only Google Maps opens the link. The Google app opens none.
    private fun namedApp(app: String, link: String = "geo:0,0?q=Kalemegdan", handles: Map<String, String> = mapOf("geo:" to maps)): Harness =
        Harness(
            Screens.app("com.android.launcher3", "Home"),
            listOf(call("open_deep_link", null, null, null, "uri" to link, "reason" to "show the place", "app" to app), stop),
            launcher = FakeApps.DEFAULT + LauncherApp("Maps", maps, "11.150") + LauncherApp("Maps", "com.google.android.apps.mapslite", "1.0") +
                LauncherApp("Google", "com.google.android.googlequicksearchbox", "17.0"),
        ).apply {
```

In `core/agent/src/test/kotlin/dev/kestrel/agent/AgentToolsTest.kt`, replace:

```kotlin
        for (app in listOf("Google Maps", maps)) {
```

with:

```kotlin
        for (app in listOf("Google Maps", "Maps", maps)) {
```

In `core/agent/src/test/kotlin/dev/kestrel/agent/AgentToolsTest.kt`, replace:

```kotlin
        // Two apps with the name, and both open the link: the model has to name one.
        val both = namedApp("Google Maps")
        both.screen.resolver = { spec -> spec.packageName }
        both.run("Show Kalemegdan on Google Maps")
```

with:

```kotlin
        // Two apps with the label, and both open the link: the model has to name one.
        val both = namedApp("Maps")
        both.screen.resolver = { spec -> spec.packageName }
        both.run("Show Kalemegdan on Maps")
```

In `core/agent/src/test/kotlin/dev/kestrel/agent/AgentToolsTest.kt`, replace:

```kotlin
    @Test
    fun aNamedAppIsTheLinksTargetForEveryRuleOfTheSafetyCheck() = runTest {
```

with:

```kotlin
    @Test
    fun anAppThatOpensTheLinkIsFoundByItsLabelOrByItsPackagesWords() = runTest {
        // Every launcher app opens the link here: "Google Maps" is exactly Google Maps' package words, and no other app's.
        val every = namedApp("Google Maps")
        every.screen.resolver = { spec -> spec.packageName }

        every.run("Show Kalemegdan on Google Maps")

        assertEquals(every.lastRequestText(), listOf(ScreenAction.OpenUri("geo:0,0?q=Kalemegdan", maps, browsable = true)), every.screen.actions.filterIsInstance<ScreenAction.OpenUri>())
        assertEquals("google maps", ScreenSteps.packageWords(maps))
        assertEquals("google mapslite", ScreenSteps.packageWords("com.google.android.apps.mapslite"))
        assertEquals("settings", ScreenSteps.packageWords("com.android.settings"))
        assertEquals("telegram messenger", ScreenSteps.packageWords("org.telegram.messenger"))
        assertEquals(null, ScreenSteps.packageWords("com.android.app"))

        // A vendor's word alone is no name: only Google Maps opens the link, and the Google app, which does not, is "Google".
        val vendor = namedApp("Google")
        vendor.run("Show Kalemegdan on Google")
        assertTrue(vendor.screen.actions.isEmpty())
        assertTrue(vendor.lastRequestText(), vendor.lastRequestText().contains("\"Google\" doesn't open this link"))
    }

    @Test
    fun aNamedAppIsTheLinksTargetForEveryRuleOfTheSafetyCheck() = runTest {
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `scripts\gradle.ps1 :core:agent:test --tests dev.kestrel.agent.AgentToolsTest`
Expected: FAIL, the compiler reports `Unresolved reference 'packageWords'`.

- [ ] **Step 3: Match among the apps that open the link**

In `core/agent/src/main/kotlin/dev/kestrel/agent/loop/ScreenSteps.kt`, replace:

```kotlin
    // The app that `open_deep_link`'s `app` names (spec 11.4): an installed package, or a launcher label that names exactly
    // one app that opens the link. The link then goes to that app alone, so Android shows no chooser, and the safety check
    // judges that app as the link's target.
    private suspend fun linkApp(name: String, link: String): LinkApp {
        suspend fun opens(packageName: String) = run.ports.screen.resolve(IntentSpec(VIEW, link, packageName, browsable = true)) == packageName
        val wanted = name.trim()
        val apps = run.ports.apps.launcherApps()
        apps.firstOrNull { it.packageName == wanted }?.let {
            return if (opens(it.packageName)) LinkApp.Found(it.packageName) else LinkApp.Missing("${quoted(it.label)} doesn't open this link")
        }
        if (PACKAGE_NAME.matches(wanted)) {
            return if (opens(wanted)) LinkApp.Found(wanted) else LinkApp.Missing("no installed app ${quoted(wanted)} opens this link")
        }
        val named = when (val pick = NamePick.pick(wanted, apps, { it.label }, { false })) {
            Pick.None -> return LinkApp.Missing("no app named ${quoted(wanted)}")
            is Pick.One -> listOf(pick.item)
            is Pick.Several -> pick.items
        }
        val handlers = named.filter { opens(it.packageName) }
        return when {
            handlers.size == 1 -> LinkApp.Found(handlers.single().packageName)
            handlers.size > 1 -> LinkApp.Missing("several apps match: " + handlers.joinToString(", ") { quoted(it.label) })
            named.size == 1 -> LinkApp.Missing("${quoted(named.single().label)} doesn't open this link")
            else -> LinkApp.Missing("none of the apps named ${quoted(wanted)} opens this link")
        }
    }
```

with:

```kotlin
    // The app that `open_deep_link`'s `app` names (spec 11.4): an installed package, or a name of exactly one of the
    // launcher apps that open the link: its package's words ([packageWords]) when the name is exactly them, so that "Google
    // Maps" finds Google Maps, whose label is only "Maps" (plan 6B-basefix B1'), else its label as the launcher's matcher
    // reads it. The link then goes to that app alone, so Android shows no chooser, and the safety check judges that app as
    // the link's target. A name that no such app has gets the launcher's errors: no app of that name, or the app of that
    // name does not open the link.
    private suspend fun linkApp(name: String, link: String): LinkApp {
        suspend fun opens(packageName: String) = run.ports.screen.resolve(IntentSpec(VIEW, link, packageName, browsable = true)) == packageName
        val wanted = name.trim()
        val apps = run.ports.apps.launcherApps()
        apps.firstOrNull { it.packageName == wanted }?.let {
            return if (opens(it.packageName)) LinkApp.Found(it.packageName) else LinkApp.Missing("${quoted(it.label)} doesn't open this link")
        }
        if (PACKAGE_NAME.matches(wanted)) {
            return if (opens(wanted)) LinkApp.Found(wanted) else LinkApp.Missing("no installed app ${quoted(wanted)} opens this link")
        }
        val handlers = apps.filter { opens(it.packageName) }
        val folded = Normalizer.normalize(wanted)
        val byPackage = handlers.filter { app -> packageWords(app.packageName)?.let(Normalizer::normalize) == folded }
        val matched = byPackage.ifEmpty {
            when (val pick = NamePick.pick(wanted, handlers, { it.label }, { false })) {
                Pick.None -> emptyList()
                is Pick.One -> listOf(pick.item)
                is Pick.Several -> pick.items
            }
        }
        if (matched.size == 1) return LinkApp.Found(matched.single().packageName)
        if (matched.size > 1) return LinkApp.Missing("several apps match: " + matched.take(NamePick.MAX_CHOICES).joinToString(", ") { quoted(it.label) })
        return when (val pick = NamePick.pick(wanted, apps, { it.label }, { false })) {
            Pick.None -> LinkApp.Missing("no app named ${quoted(wanted)}")
            is Pick.One -> LinkApp.Missing("${quoted(pick.item.label)} doesn't open this link")
            is Pick.Several -> LinkApp.Missing("none of the apps named ${quoted(wanted)} opens this link")
        }
    }
```

In `core/agent/src/main/kotlin/dev/kestrel/agent/loop/ScreenSteps.kt`, replace:

```kotlin
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
```

with:

```kotlin
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

        /**
         * The words of [packageName] without its generic parts, as a second name of the app for `open_deep_link`'s `app`:
         * "google maps" for com.google.android.apps.maps, "settings" for com.android.settings; null when nothing is left.
         */
        internal fun packageWords(packageName: String): String? =
            packageName.split('.').filter { it.isNotEmpty() && it.lowercase() !in GENERIC_PACKAGE_PARTS }.joinToString(" ").ifEmpty { null }

        private val GENERIC_PACKAGE_PARTS = setOf("com", "org", "net", "io", "android", "apps", "app")
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
It takes an optional `app`: the package name of an installed app, or an app's launcher name that names exactly one installed app that opens the link.
```

with:

```text
It takes an optional `app`: the package name of an installed app, or a name of exactly one of the installed apps that open the link, by its launcher name or by exactly the words of its package name without the generic parts `com`, `org`, `net`, `io`, `android`, `apps` and `app` (Google Maps' launcher name is "Maps", and `com.google.android.apps.maps` gives "google maps"; a part of them, such as "Google", names no app).
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `scripts\gradle.ps1 :core:agent:test --tests dev.kestrel.agent.AgentToolsTest`
Expected: PASS.

Run: `scripts\gradle.ps1 :core:agent:test :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL, G₀ + 1 tests.

Run: `scripts\gradle.ps1 --stop`

- [ ] **Step 5: Commit**

```powershell
git add core/agent/src/main/kotlin/dev/kestrel/agent/loop/ScreenSteps.kt core/agent/src/test/kotlin/dev/kestrel/agent/AgentToolsTest.kt docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "fix(agent): open_deep_link's app is matched among the apps that open the link, by label or package words, so Google Maps is found by its name" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 5: The tool's answers (P1), their record and `reason=asked` (T1), and a declined task's reason (T5)

`Answers.reply` follows the owner's decision P1 by agent-state's `question=` (Task 1). The runner records every answer that it sends as `<kind>:<answer>` and the open "Which number?" as `pick-number:none`. A run whose task ended CANCELLED after the run's own answer ended it ("cancel" to a question or a hand-over, or "no" to "Continue the task?") is `reason=asked`, and its run line names the cancelled kinds in `asked=` right before `reason=`, with a warning: this is what hid today's cause on the A71, where calendar-add's task asked for READ_CALENDAR (Kestrel's own permission request, a hand-over of kind `PERMISSION`; notification access is `ACCESS`), the run answered "cancel", and the line said only `end=CANCELLED reason=end`. A `Declined` task whose run never reached its decline is `reason=end`.

**Files:**
- Create: `tools/eval/src/main/kotlin/dev/kestrel/eval/run/Asked.kt`
- Modify: `tools/eval/src/main/kotlin/dev/kestrel/eval/task/Task.kt`, `tools/eval/src/main/kotlin/dev/kestrel/eval/run/Runner.kt`, `tools/eval/src/main/kotlin/dev/kestrel/eval/run/Verdict.kt`, `tools/eval/src/main/kotlin/dev/kestrel/eval/run/RunLine.kt`, `tools/eval/src/main/kotlin/dev/kestrel/eval/hook/Hooks.kt`, `docs/superpowers/specs/2026-11-12-kestrel-design.md`
- Test: `tools/eval/src/test/kotlin/dev/kestrel/eval/task/TaskModelTest.kt`, `tools/eval/src/test/kotlin/dev/kestrel/eval/run/RunnerTest.kt`

**Interfaces:**
- Consumes: agent-state's ` question=<yes-no|free|pick-number|screenshot|none>` (Task 1); `handOver=<HandOverKind name|none>`.
- Produces: `Answers.SCREENSHOT = "screenshot"`, `Answers.PICK_NUMBER = "pick-number"`, `Answers.kind(state: HookAnswer): String?` (`asking=`'s value, `screenshot`, `confirm/<category>`, `hand-over/<kind>`, or null), `Answers.endsTask(kind: String, reply: String): Boolean`; `Asked(kind: String, reply: String?)` with `endsTask` and `toString()` `<kind>:<reply|none>`; `Reason.ASKED` (`asked`); `RunLine(…, credits: String? = null, asked: String? = null)`, whose text has `asked=<kinds> ` before `reason=` only when `asked` is set, and which `RunLine.parse` reads back; `Verdicts.judge(…, declined: Set<String> = emptySet(), cancelledByRun: Boolean = false)`; the runner's `RunState.asked: MutableList<Asked>` (only answers that reached Kestrel, and the deliberately open "Which number?") and `RunState.cancelled(): List<String>` (the kinds whose answer ended the task), which Task 6's detail line reads.

Counts: `:tools:eval` N + 1 (E₀ + 1).

- [ ] **Step 1: Write the failing tests**

In `tools/eval/src/test/kotlin/dev/kestrel/eval/task/TaskModelTest.kt`, replace:

```kotlin
        assertEquals("cancel", reply("phase=running asking=free work=running-task handOver=none confirming=none"))
        assertEquals("cancel", reply("phase=running asking=pick-number work=running-task handOver=none confirming=none"))
        assertEquals("cancel", reply("phase=handed-over asking=none work=idle handOver=CREDENTIALS confirming=none"))
        assertNull(reply("phase=running asking=none work=running-task handOver=none confirming=none"))
        assertEquals("cancel", Answers.NONE.reply(HookAnswer("asking=confirm confirming=sending")))
```

with:

```kotlin
        assertEquals("cancel", reply("phase=running asking=free work=running-task handOver=none confirming=none"))
        assertEquals("cancel", reply("phase=running asking=free work=running-task handOver=none confirming=none question=free"))
        // The owner's decision P1: the screenshot question gets "no", and "Which number should I use?" no answer at all.
        assertEquals("no", reply("phase=running asking=free work=running-task handOver=none confirming=none question=screenshot"))
        assertNull(reply("phase=running asking=pick-number work=running-task handOver=none confirming=none question=pick-number"))
        assertEquals("cancel", reply("phase=handed-over asking=none work=idle handOver=CREDENTIALS confirming=none"))
        assertNull(reply("phase=running asking=none work=running-task handOver=none confirming=none"))
        assertEquals("cancel", Answers.NONE.reply(HookAnswer("asking=confirm confirming=sending")))

        fun kind(state: String) = Answers.kind(HookAnswer(state))
        assertEquals("screenshot", kind("asking=free question=screenshot handOver=none"))
        assertEquals("free", kind("asking=free question=free handOver=none"))
        assertEquals("pick-number", kind("asking=pick-number question=pick-number handOver=none"))
        assertEquals("confirm/deleting", kind("asking=confirm confirming=deleting handOver=none"))
        assertEquals("hand-over/PERMISSION", kind("phase=handed-over asking=none handOver=PERMISSION"))
        assertNull(kind("asking=none handOver=none"))
        assertTrue(Answers.endsTask("free", "cancel") && Answers.endsTask("hand-over/PERMISSION", "cancel") && Answers.endsTask("continue", "no"))
        assertFalse(Answers.endsTask("yes-no", "no") || Answers.endsTask("screenshot", "no"))
```

In `tools/eval/src/test/kotlin/dev/kestrel/eval/task/TaskModelTest.kt`, replace:

```kotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
```

with:

```kotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
```

Add this test to `tools/eval/src/test/kotlin/dev/kestrel/eval/run/RunnerTest.kt`, before its last closing brace:

```kotlin
    @Test
    fun theRunSaysNoToTheScreenshotLeavesTheNumberOpenAndNamesTheAnswerThatEndedTheTask() {
        val open = "phase=running work=running-task handOver=none confirming=none"
        val questions = ArrayDeque(
            listOf("$open asking=free question=screenshot", "$open asking=pick-number question=pick-number", "$open asking=pick-number question=pick-number", "$open asking=free question=free"),
        )
        run.adb.hook("AGENT_STATE") { questions.removeFirstOrNull() ?: "$open asking=none question=none" }
        val cancelled = "state=done end=CANCELLED steps=4 workMs=9000 credits=0.00 confirmed=0 unconfirmed=0 shareItems=0 beforeTestMode=0 " +
            "model=scripted in=10 out=2 cacheRead=0 cacheWrite=0"
        var polls = 0
        run.adb.hook("AGENT_TASK") { if (++polls < 7) "state=running" else cancelled }

        val line = runner().run(task(), "[]", 1, "maple heron")

        assertEquals(listOf("no", "cancel"), run.adb.hookCalls("AGENT_ANSWER").map { it["answer"] })
        assertTrue(line.text(), line.text().endsWith(" cacheWrite=0 asked=free reason=asked"))
        assertEquals(Reason.ASKED, RunLine.parse(line.text())!!.reason)
        assertEquals("free", RunLine.parse(line.text())!!.asked)
        assertTrue(run.console.warnings.toString(), run.console.warnings.contains("Task stand-in-hotspot, run 1: the run's own answer to free ended the task, which ended CANCELLED."))

        // Kestrel's own permission request is a hand-over, which the run cancels: the line names it.
        questions.add("phase=handed-over work=idle handOver=PERMISSION confirming=none asking=none question=none")
        polls = 0
        val handOver = runner().run(task(), "[]", 2, "cedar otter")
        assertTrue(handOver.text(), handOver.text().endsWith(" asked=hand-over/PERMISSION reason=asked"))

        // The run's "no" to "Continue the task?" cancels the task too.
        questions.add("phase=paused work=idle handOver=none confirming=none asking=continue question=none")
        polls = 0
        val paused = runner().run(task(), "[]", 3, "amber lynx")
        assertTrue(paused.text(), paused.text().endsWith(" asked=continue reason=asked"))

        // An answer that never reached Kestrel ended nothing: the end stays Kestrel's.
        run.adb.hookFails("AGENT_ANSWER", "the task asks nothing")
        questions.add("$open asking=free question=free")
        polls = 0
        assertEquals(Reason.END, runner().run(task(), "[]", 4, "willow walnut").reason)
    }
```

In `tools/eval/src/test/kotlin/dev/kestrel/eval/run/RunnerTest.kt`, replace:

```kotlin
        fun judge(rule: VerdictRule, answer: String?, timedOut: Boolean = false, checked: Boolean = true, refusal: String? = null, refused: String? = null) =
            Verdicts.judge(rule, answer?.let(::HookAnswer), timedOut, checked, refusal, refused, false).let { "${it.result.wire} ${it.check.wire} ${it.reason.wire}" }
```

with:

```kotlin
        fun judge(
            rule: VerdictRule,
            answer: String?,
            timedOut: Boolean = false,
            checked: Boolean = true,
            refusal: String? = null,
            refused: String? = null,
            cancelled: Boolean = false,
        ) = Verdicts.judge(rule, answer?.let(::HookAnswer), timedOut, checked, refusal, refused, false, emptySet(), cancelled)
            .let { "${it.result.wire} ${it.check.wire} ${it.reason.wire}" }
```

In `tools/eval/src/test/kotlin/dev/kestrel/eval/run/RunnerTest.kt`, replace:

```kotlin
        assertEquals("fail pass check", judge(VerdictRule.Declined("deleting"), finished))
```

with:

```kotlin
        // A task that ended before its decline was asked (T5), and one that declined something else.
        assertEquals("fail pass end", judge(VerdictRule.Declined("deleting"), finished))
        assertEquals("fail pass check", judge(VerdictRule.Declined("deleting"), "state=done end=DECLINED unconfirmed=0 beforeTestMode=0"))
        // The run's own cancel ended the task (T1); without it, the end is Kestrel's.
        val cancelled = "state=done end=CANCELLED unconfirmed=0 beforeTestMode=0"
        assertEquals("fail pass asked", judge(VerdictRule.Finished, cancelled, cancelled = true))
        assertEquals("fail pass end", judge(VerdictRule.Finished, cancelled))
        assertEquals("fail pass confirm", judge(VerdictRule.Finished, cancelled, refused = "calls", cancelled = true))
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `scripts\gradle.ps1 :tools:eval:test --tests dev.kestrel.eval.task.TaskModelTest --tests dev.kestrel.eval.run.RunnerTest`
Expected: FAIL, the compiler reports `Unresolved reference 'kind'`, `'endsTask'`, `'ASKED'` and `'asked'`, and too many arguments for `judge`.

- [ ] **Step 3: Answer by the question's source**

In `tools/eval/src/main/kotlin/dev/kestrel/eval/task/Task.kt`, replace:

```kotlin
/**
 * A confirmation's categories (the hook's wire names) that a run answers "confirm", and those that the task expects the
 * owner to decline, answered "no" (design D4 of sub-project 6). Any other confirmation is cancelled; the other questions
 * get task-check.ps1's answers, so that a script never answers for the owner.
 */
data class Answers(val confirm: Set<String> = emptySet(), val decline: Set<String> = emptySet()) {
```

with:

```kotlin
/**
 * A confirmation's categories (the hook's wire names) that a run answers "confirm", and those that the task expects the
 * owner to decline, answered "no" (design D4 of sub-project 6). Any other confirmation is cancelled. The other questions
 * get the owner's answers of plan 6B-basefix (P1): "no" to a yes-or-no question, to "Continue the task?" and to
 * look_closer's screenshot question; no answer to look_closer's "Which number should I use?", so that Kestrel's own wait
 * ends and the model reads that the owner did not answer; and "cancel" to the model's own question and to a hand-over.
 * A script never answers for the owner.
 */
data class Answers(val confirm: Set<String> = emptySet(), val decline: Set<String> = emptySet()) {
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/task/Task.kt`, replace:

```kotlin
        "yes-no", "continue" -> "no"
        "free", "pick-number" -> "cancel"
        else -> if (state["handOver"].let { it != null && it != "none" }) "cancel" else null
    }

    companion object {
        val NONE = Answers()
```

with:

```kotlin
        "yes-no", "continue" -> "no"
        "free" -> if (state["question"] == SCREENSHOT) "no" else "cancel"
        PICK_NUMBER -> null
        else -> if (state["handOver"].let { it != null && it != "none" }) "cancel" else null
    }

    companion object {
        val NONE = Answers()

        /** agent-state's `question=` for look_closer's screenshot question, which `asking=free` carries too (plan 6B-basefix). */
        const val SCREENSHOT = "screenshot"

        /** agent-state's `asking=` for look_closer's "Which number should I use?". */
        const val PICK_NUMBER = "pick-number"

        /**
         * The kind of [state]'s open question for a run's record: its `asking=` (`screenshot` for the screenshot question),
         * `confirm/<category>` for a confirmation, or `hand-over/<kind>`, such as `hand-over/PERMISSION` for Kestrel's own
         * permission request; null while it asks nothing.
         */
        fun kind(state: HookAnswer): String? = when (val asking = state["asking"]) {
            null, "none" -> state["handOver"]?.takeIf { it != "none" }?.let { "hand-over/$it" }
            "confirm" -> "confirm/${state["confirming"] ?: "none"}"
            "free" -> if (state["question"] == SCREENSHOT) SCREENSHOT else "free"
            else -> asking
        }

        /**
         * Whether the run's [reply] to a question of [kind] ends the task in Kestrel's rules: "cancel" to any question or
         * hand-over, and "no" to "Continue the task?" after a pause.
         */
        fun endsTask(kind: String, reply: String): Boolean = reply == "cancel" || (kind == CONTINUE && reply == "no")

        private const val CONTINUE = "continue"
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/hook/Hooks.kt`, replace:

```kotlin
    /** agent-state: the task's phase, its open question's kind, the hand-over and the open confirmation's category. */
```

with:

```kotlin
    /**
     * agent-state: the task's phase, its open question's kind, the hand-over, the open confirmation's category and the
     * question's source (`question=`, which tells look_closer's screenshot question from a free one; plan 6B-basefix).
     */
```

- [ ] **Step 4: Record the answers and name the answer that ended the task**

Create `tools/eval/src/main/kotlin/dev/kestrel/eval/run/Asked.kt`:

```kotlin
package dev.kestrel.eval.run

import dev.kestrel.eval.task.Answers

/**
 * One of the task's questions or hand-overs as the run met it (plan 6B-basefix T1): its [kind] ([Answers.kind]) and the
 * answer that reached Kestrel ([reply]), or null for a question that the run left open on purpose, such as look_closer's
 * "Which number should I use?". An answer that the hook did not deliver is never an [Asked].
 */
data class Asked(val kind: String, val reply: String?) {
    /** Whether the run's own answer ended the task ([Answers.endsTask]). */
    val endsTask: Boolean get() = reply != null && Answers.endsTask(kind, reply)

    override fun toString(): String = "$kind:${reply ?: "none"}"
}
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/run/RunLine.kt`, replace:

```kotlin
    CALL("call"), STOPPED("stopped"), SETUP("setup"), STALLED("stalled"),
}
```

with:

```kotlin
    CALL("call"), STOPPED("stopped"), SETUP("setup"), STALLED("stalled"),

    /** The task ended CANCELLED after the run's own answer ended it, such as a "cancel" to a hand-over (plan 6B-basefix T1). */
    ASKED("asked"),
}
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/run/RunLine.kt`, replace:

```kotlin
 * reason=<…>`, with only what the hooks answered. [credits], when given, replaces the answer's credits.
 */
data class RunLine(
    val task: String,
    val run: Int,
    val result: RunResult,
    val answer: HookAnswer?,
    val check: CheckResult,
    val reason: Reason,
    val credits: String? = null,
) {
```

with:

```kotlin
 * reason=<…>`, with only what the hooks answered. [credits], when given, replaces the answer's credits. A run of
 * `reason=asked` names the kinds of the questions and hand-overs that the run cancelled in [asked], written as
 * `asked=<kinds>` right before `reason=` (plan 6B-basefix), such as `asked=hand-over/PERMISSION`; readers that take the
 * line's pairs by name, as a71-agent.ps1's `ConvertFrom-TaskLine` does, read it unchanged.
 */
data class RunLine(
    val task: String,
    val run: Int,
    val result: RunResult,
    val answer: HookAnswer?,
    val check: CheckResult,
    val reason: Reason,
    val credits: String? = null,
    val asked: String? = null,
) {
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/run/RunLine.kt`, replace:

```kotlin
            "out=${field("out", "0")} cacheRead=${field("cacheRead", "0")} cacheWrite=${field("cacheWrite", "0")} reason=${reason.wire}"
```

with:

```kotlin
            "out=${field("out", "0")} cacheRead=${field("cacheRead", "0")} cacheWrite=${field("cacheWrite", "0")} " +
            "${asked?.let { "asked=$it " }.orEmpty()}reason=${reason.wire}"
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/run/RunLine.kt`, replace:

```kotlin
            return RunLine(fields["task"] ?: return null, run, result, fields, check, reason)
```

with:

```kotlin
            return RunLine(fields["task"] ?: return null, run, result, fields, check, reason, asked = fields["asked"])
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/run/Verdict.kt`, replace:

```kotlin
        stalled: Boolean,
        declined: Set<String> = emptySet(),
    ): Verdict {
```

with:

```kotlin
        stalled: Boolean,
        declined: Set<String> = emptySet(),
        cancelledByRun: Boolean = false,
    ): Verdict {
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/run/Verdict.kt`, replace:

```kotlin
                when {
                    passed -> Reason.OK
                    endMatters && answer["end"] != expectedEnd -> Reason.END
                    else -> Reason.CHECK
                },
            )
        }
```

with:

```kotlin
                when {
                    passed -> Reason.OK
                    endMatters && answer["end"] != expectedEnd -> Reason.END
                    // The task ended before the run could decline (plan 6B-basefix T5): its end, not the check, failed it.
                    rule is VerdictRule.Declined && rule.category !in declined && answer["end"] != "DECLINED" -> Reason.END
                    else -> Reason.CHECK
                },
            )
            // The run's own answer ended the task, not Kestrel (plan 6B-basefix T1).
            if (verdict.reason == Reason.END && cancelledByRun && answer["end"] == "CANCELLED") verdict = verdict.copy(reason = Reason.ASKED)
        }
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/run/Verdict.kt`, replace:

```kotlin
 * [declined] are the categories that the run answered "no".
 */
```

with:

```kotlin
 * [declined] are the categories that the run answered "no"; [cancelledByRun] is whether the run's own delivered answer
 * to a question or a hand-over ends the task ([dev.kestrel.eval.task.Answers.endsTask]), which makes a CANCELLED end
 * `reason=asked`.
 */
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/run/Runner.kt`, replace:

```kotlin
        val line = RunLine(
            task.name, number, if (call) RunResult.FAIL else judged.result, state.answer, judged.check,
            if (call) Reason.CALL else judged.reason, end.credits,
        )
```

with:

```kotlin
        val reason = if (call) Reason.CALL else judged.reason
        val line = RunLine(
            task.name, number, if (call) RunResult.FAIL else judged.result, state.answer, judged.check,
            reason, end.credits, asked = state.cancelled().joinToString(",").takeIf { reason == Reason.ASKED },
        )
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/run/Runner.kt`, replace:

```kotlin
        state.refused?.let {
            console.warn("Task ${task.name}, run $number: Kestrel asked to confirm an action of the category $it, which this task does not name; the run said cancel.")
        }
        return Verdicts.judge(task.verdict, answer, state.timedOut, checked, state.refusal, state.refused, state.stalled, state.declined)
```

with:

```kotlin
        state.refused?.let {
            console.warn("Task ${task.name}, run $number: Kestrel asked to confirm an action of the category $it, which this task does not name; the run said cancel.")
        }
        val cancelled = state.cancelled()
        if (answer?.get("end") == "CANCELLED" && cancelled.isNotEmpty()) {
            console.warn("Task ${task.name}, run $number: the run's own answer to ${cancelled.joinToString(", ")} ended the task, which ended CANCELLED.")
        }
        return Verdicts.judge(task.verdict, answer, state.timedOut, checked, state.refusal, state.refused, state.stalled, state.declined, cancelled.isNotEmpty())
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/run/Runner.kt`, replace:

```kotlin
        var quietUntil = Duration.ZERO
        while (elapsed() < LIMIT) {
```

with:

```kotlin
        var quietUntil = Duration.ZERO
        // Whether the open "Which number should I use?" is in the record already.
        var silent = false
        while (elapsed() < LIMIT) {
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/run/Runner.kt`, replace:

```kotlin
            val asked = quietly { hooks.agentState(tag) } ?: continue
            val reply = task.answers.reply(asked) ?: continue
            val confirming = asked["confirming"].takeIf { asked["asking"] == "confirm" }
```

with:

```kotlin
            val asked = quietly { hooks.agentState(tag) } ?: continue
            val kind = Answers.kind(asked) ?: continue
            val reply = task.answers.reply(asked)
            if (reply == null) {
                // "Which number should I use?" stays unanswered (the owner's decision P1), once in the run's record.
                if (kind == Answers.PICK_NUMBER && !silent) state.asked.add(Asked(kind, null))
                silent = kind == Answers.PICK_NUMBER
                continue
            }
            silent = false
            val confirming = asked["confirming"].takeIf { asked["asking"] == "confirm" }
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/run/Runner.kt`, replace:

```kotlin
            quietly { hooks.agentAnswer(tag, reply, confirming) }
            quietUntil = elapsed() + QUIET
```

with:

```kotlin
            // Only an answer that reached Kestrel can have ended anything; one sent again to the same open question is
            // recorded once.
            val delivered = quietly { hooks.agentAnswer(tag, reply, confirming) } != null
            if (delivered) Asked(kind, reply).let { if (state.asked.lastOrNull() != it) state.asked.add(it) }
            quietUntil = elapsed() + QUIET
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/run/Runner.kt`, replace:

```kotlin
        val declined = mutableSetOf<String>()
    }
```

with:

```kotlin
        val declined = mutableSetOf<String>()

        // The run's answers that reached Kestrel, and the questions that it left open on purpose, in their order (T1).
        val asked = mutableListOf<Asked>()

        /** The kinds of the questions and hand-overs whose answer by the run ended the task, each once. */
        fun cancelled(): List<String> = asked.filter { it.endsTask }.map { it.kind }.distinct()
    }
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/run/Runner.kt`, replace:

```kotlin
import dev.kestrel.eval.hook.Hooks
import dev.kestrel.eval.task.RunScope
```

with:

```kotlin
import dev.kestrel.eval.hook.Hooks
import dev.kestrel.eval.task.Answers
import dev.kestrel.eval.task.RunScope
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
and other questions with "no" or "cancel"; it never answers for the owner.
```

with:

```text
and other questions as the owner decided: "no" to a yes-or-no question, to "Continue the task?" and to `look_closer`'s screenshot question, no answer to `look_closer`'s "Which number should I use?", so that Kestrel's own wait ends and the model learns that the owner did not answer, and "cancel" to the model's own question and to a hand-over; a run whose task ended CANCELLED after the run's own answer ended it ("cancel", or "no" to "Continue the task?"), as delivered to Kestrel, ends with `reason=asked` and names the questions' kinds, such as `asked=hand-over/PERMISSION`. It never answers for the owner.
```

- [ ] **Step 5: Run the tests to see them pass**

Run: `scripts\gradle.ps1 :tools:eval:test --tests dev.kestrel.eval.task.TaskModelTest --tests dev.kestrel.eval.run.RunnerTest`
Expected: PASS.

Run: `scripts\gradle.ps1 :tools:eval:test`
Expected: BUILD SUCCESSFUL, E₀ + 1 tests.

Run: `scripts\gradle.ps1 --stop`

- [ ] **Step 6: Commit**

```powershell
git add tools/eval/src docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(eval): the owner's answers to look_closer's questions, every answer recorded, reason=asked naming the cancelled kinds, and a declined task's end" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 6: The run's detail line and the suite's `details.txt`

After each run line, the runner writes one detail line, on the console and, in a suite, into `details.txt` of the results folder: the run's answers (Task 5), the touches and signals that Kestrel counted (Task 2), whether a `say` text and a data tool's result held the marker (Tasks 2 and 3), and the settings that a restore found changed and put back, in effect again or stored only (Task 7 fills `RunScope.putBack`). The line is a type of its own, `RunDetail`, beside `RunLine`. It also gives each transcript the run's marker, which Task 3's Kestrel passes to the task.

**Files:**
- Create: `tools/eval/src/main/kotlin/dev/kestrel/eval/run/RunDetail.kt`, `tools/eval/src/main/kotlin/dev/kestrel/eval/task/PutBack.kt`
- Modify: `tools/eval/src/main/kotlin/dev/kestrel/eval/run/Runner.kt`, `tools/eval/src/main/kotlin/dev/kestrel/eval/run/LineWriter.kt`, `tools/eval/src/main/kotlin/dev/kestrel/eval/hook/Hooks.kt`, `tools/eval/src/main/kotlin/dev/kestrel/eval/task/Steps.kt`, `tools/eval/src/main/kotlin/dev/kestrel/eval/command/SeriesCommand.kt`, `tools/eval/src/main/kotlin/dev/kestrel/eval/report/SuiteFolder.kt`, `docs/superpowers/specs/2026-11-12-kestrel-design.md`
- Test: `tools/eval/src/test/kotlin/dev/kestrel/eval/run/RunnerTest.kt`, `tools/eval/src/test/kotlin/dev/kestrel/eval/command/CommandsTest.kt`

**Interfaces:**
- Consumes: Task 5's `RunState.asked`; agent-task's `ownerTouches=`, `signals=` (Task 2) and, with `-Marker`, `markerSaid=` and `markerInData=` (Tasks 2 and 3).
- Produces: `Hooks.voiceHeard(text: String, tag: String, live: Boolean = false, model: String? = null, marker: String? = null)`; `PutBack` with `recordApplied(name: String)`, `recordStored(name: String)`, `applied: List<String>` and `stored: List<String>`, and `RunScope.putBack: PutBack`; `RunDetail(task: String, run: Int, asked: List<Asked>, end: HookAnswer?, markers: HookAnswer?, applied: List<String>, stored: List<String>)` with `text()`; `LineWriter(console: Console, results: ResultsFile?, detailsFile: ResultsFile? = null)` with `detail(text: String)` and `details: List<String>`; `SuiteFolder.DETAILS = "details.txt"`; the detail line `task=<name> run=<i> asked=<kind>:<answer>,…|none ownerTouches=<n|none> signals=<n|none> markerSaid=<0|1|none> markerInData=<0|1|none> putBack=<name,…|none> byHand=<name,…|none>`, which Tasks 10 and 11 read.

Counts: `:tools:eval` N + 1 (E₀ + 2).

- [ ] **Step 1: Write the failing tests**

Add this test to `tools/eval/src/test/kotlin/dev/kestrel/eval/run/RunnerTest.kt`, before its last closing brace:

```kotlin
    @Test
    fun aRunsDetailLineNamesItsAnswersKestrelsCountsAndWhatARestorePutBack() {
        val questions = ArrayDeque(listOf("phase=running asking=yes-no work=running-task handOver=none confirming=none question=yes-no"))
        run.adb.hook("AGENT_STATE") { questions.removeFirstOrNull() ?: "phase=running asking=none work=running-task handOver=none confirming=none question=none" }
        var polls = 0
        run.adb.hook("AGENT_TASK") { extras ->
            when {
                extras["marker"] != null -> "state=done marker=1 markerSaid=1 markerInData=0"
                ++polls < 3 -> "state=running"
                else -> "$done ownerTouches=2 signals=1"
            }
        }
        val setup = object : Setup {
            override fun apply(scope: RunScope): SetupResult {
                scope.restores.push("the display density", "") { scope.putBack.recordApplied("wm/density") }
                return SetupResult.Ready
            }
        }

        runner().run(task(setup = listOf(setup)), "[]", 1, "maple heron")

        assertEquals(
            listOf("task=stand-in-hotspot run=1 asked=yes-no:no ownerTouches=2 signals=1 markerSaid=1 markerInData=0 putBack=wm/density byHand=none"),
            writer.details,
        )
        assertEquals("maple heron", run.adb.hookCalls("VOICE").single()["marker"])
    }
```

In `tools/eval/src/test/kotlin/dev/kestrel/eval/run/RunnerTest.kt`, replace:

```kotlin
        assertEquals(listOf(line.text()), run.console.lines)
        assertEquals(listOf(mapOf("event" to "heard", "text" to "Kestrel, turn on hotspot", "tag" to "t1")), run.adb.hookCalls("VOICE"))
```

with:

```kotlin
        assertEquals(
            listOf(line.text(), "task=stand-in-hotspot run=1 asked=none ownerTouches=none signals=none markerSaid=none markerInData=none putBack=none byHand=none"),
            run.console.lines,
        )
        assertEquals(listOf(mapOf("event" to "heard", "text" to "Kestrel, turn on hotspot", "tag" to "t1", "marker" to "maple heron")), run.adb.hookCalls("VOICE"))
```

In `tools/eval/src/test/kotlin/dev/kestrel/eval/command/CommandsTest.kt`, replace:

```kotlin
        assertEquals(6, runs.size)
```

with:

```kotlin
        assertEquals(6, runs.size)
        // The detail lines go into their own file, one per run, so that runs.txt keeps only what a71-agent.ps1 reads.
        assertEquals(4, Files.readAllLines(folder.resolve("details.txt")).count { it.contains("\tdetail\ttask=") })
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `scripts\gradle.ps1 :tools:eval:test --tests dev.kestrel.eval.run.RunnerTest --tests dev.kestrel.eval.command.CommandsTest`
Expected: FAIL, the compiler reports `Unresolved reference 'putBack'` and `Unresolved reference 'details'`.

- [ ] **Step 3: Write the detail line**

Create `tools/eval/src/main/kotlin/dev/kestrel/eval/task/PutBack.kt`:

```kotlin
package dev.kestrel.eval.task

/**
 * The settings that a run's restores found changed by the run and put back (plan 6B-basefix T6), by name, for the run's
 * detail line: those that are in effect again at once ([applied]), such as the display density that `wm density` applies,
 * and those whose stored value went back but which Android may show only after a reboot or when their page sets them
 * again ([stored]), which the owner may have to set by hand. A restore may record from the shutdown hook's thread.
 */
class PutBack {
    private val inEffect = mutableListOf<String>()
    private val storedOnly = mutableListOf<String>()

    @Synchronized
    fun recordApplied(name: String) {
        inEffect += name
    }

    @Synchronized
    fun recordStored(name: String) {
        storedOnly += name
    }

    val applied: List<String>
        @Synchronized get() = inEffect.toList()

    val stored: List<String>
        @Synchronized get() = storedOnly.toList()
}
```

Create `tools/eval/src/main/kotlin/dev/kestrel/eval/run/RunDetail.kt`:

```kotlin
package dev.kestrel.eval.run

import dev.kestrel.eval.hook.HookAnswer

/**
 * A run's detail line (plan 6B-basefix), which the runner writes after the run's [RunLine], on the console and into a
 * suite's `details.txt`: the run's answers to the task's questions ([asked]), the owner's touches and the signals among
 * them that Kestrel counted (from agent-task's answer at the task's [end]), whether a `say` text and a data tool's result
 * held the run's marker (agent-task's answer with the marker, [markers]), and the settings that a restore found changed
 * and put back, in effect again ([applied], `putBack=`) or stored only ([stored], `byHand=`). Kinds, counts, flags and
 * setting names only, never text; `none` where the hooks did not answer or nothing was there.
 */
data class RunDetail(
    val task: String,
    val run: Int,
    val asked: List<Asked>,
    val end: HookAnswer?,
    val markers: HookAnswer?,
    val applied: List<String>,
    val stored: List<String>,
) {
    fun text(): String {
        fun list(items: List<Any>) = items.joinToString(",").ifEmpty { "none" }
        fun field(answer: HookAnswer?, name: String) = answer?.get(name) ?: "none"
        return "task=$task run=$run asked=${list(asked)} ownerTouches=${field(end, "ownerTouches")} signals=${field(end, "signals")} " +
            "markerSaid=${field(markers, "markerSaid")} markerInData=${field(markers, "markerInData")} putBack=${list(applied)} byHand=${list(stored)}"
    }
}
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/task/Steps.kt`, replace:

```kotlin
    /** What a setup read for the run's checks, such as the Wi-Fi page's activity or the alarms before the run. */
    val notes: MutableMap<String, String> = mutableMapOf()
}
```

with:

```kotlin
    /** What a setup read for the run's checks, such as the Wi-Fi page's activity or the alarms before the run. */
    val notes: MutableMap<String, String> = mutableMapOf()

    /** The settings that a restore found changed by the run and put back, which the run's detail line names (T6). */
    val putBack: PutBack = PutBack()
}
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/hook/Hooks.kt`, replace:

```kotlin
    /** voice -Send heard: the transcript [text] with [tag], as if Kestrel had heard it; [live] and [model] go with its task. */
    fun voiceHeard(text: String, tag: String, live: Boolean = false, model: String? = null): HookAnswer =
        act(
            "voice",
            Extra.Text("event", "heard"),
            Extra.Text("text", text),
            Extra.Text("tag", tag),
            Extra.Flag("live", true).takeIf { live },
            model?.let { Extra.Text("model", it) },
        )
```

with:

```kotlin
    /**
     * voice -Send heard: the transcript [text] with [tag], as if Kestrel had heard it; [live], [model] and the run's
     * [marker] go with its task, the marker for agent-task's `markerInData=` (plan 6B-basefix).
     */
    fun voiceHeard(text: String, tag: String, live: Boolean = false, model: String? = null, marker: String? = null): HookAnswer =
        act(
            "voice",
            Extra.Text("event", "heard"),
            Extra.Text("text", text),
            Extra.Text("tag", tag),
            Extra.Flag("live", true).takeIf { live },
            model?.let { Extra.Text("model", it) },
            marker?.let { Extra.Text("marker", it) },
        )
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/run/LineWriter.kt`, replace:

```kotlin
class LineWriter(private val console: Console, private val results: ResultsFile?) {
    private val written = mutableSetOf<Pair<String, Int>>()
    private val all = mutableListOf<RunLine>()

    /** Every line that this writer wrote, in its order. */
    val lines: List<RunLine> get() = synchronized(this) { all.toList() }
```

with:

```kotlin
class LineWriter(private val console: Console, private val results: ResultsFile?, private val detailsFile: ResultsFile? = null) {
    private val written = mutableSetOf<Pair<String, Int>>()
    private val all = mutableListOf<RunLine>()
    private val notes = mutableListOf<String>()

    /** Every line that this writer wrote, in its order. */
    val lines: List<RunLine> get() = synchronized(this) { all.toList() }

    /** Every detail line that this writer wrote, in its order. */
    val details: List<String> get() = synchronized(this) { notes.toList() }

    /**
     * A run's detail line (plan 6B-basefix): on the console and, with the step `detail`, in the suite's `details.txt`, so
     * that a run's folder says how the run answered and what Kestrel counted; kinds, counts and flags only, never text.
     * runs.txt keeps only the run lines, which scripts\a71-agent.ps1 and --resume read.
     */
    fun detail(text: String) = synchronized(this) {
        console.line(text)
        detailsFile?.append("detail", text)
        notes.add(text)
        Unit
    }
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/report/SuiteFolder.kt`, replace:

```kotlin
        const val RUNS = "runs.txt"
```

with:

```kotlin
        const val RUNS = "runs.txt"

        /** The runs' detail lines (plan 6B-basefix), which --resume appends to and never rewrites. */
        const val DETAILS = "details.txt"
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/command/SeriesCommand.kt`, replace:

```kotlin
        val writer = LineWriter(session.console, runsFile)
```

with:

```kotlin
        val writer = LineWriter(session.console, runsFile, ResultsFile(folder.resolve(SuiteFolder.DETAILS), environment.clock))
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/run/Runner.kt`, replace:

```kotlin
            hooks.voiceHeard("Kestrel, " + task.request.fill(scope.values), tag, options.live, options.model)
```

with:

```kotlin
            hooks.voiceHeard("Kestrel, " + task.request.fill(scope.values), tag, options.live, options.model, scope.values.marker)
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/run/Runner.kt`, replace:

```kotlin
        await(task, tag, state)
        val answer = state.answer
        var checked = false
```

with:

```kotlin
        await(task, tag, state)
        val answer = state.answer
        // For the detail line: whether a say text and a data tool's result held the run's marker.
        if (answer != null) state.markers = quietly { hooks.agentTask(tag, marker = scope.values.marker) }
        var checked = false
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/run/Runner.kt`, replace:

```kotlin
        writer.writeOnce(task.name, number, line)
        current = null
```

with:

```kotlin
        if (writer.writeOnce(task.name, number, line)) {
            writer.detail(RunDetail(task.name, number, state.asked.toList(), state.answer, state.markers, scope.putBack.applied, scope.putBack.stored).text())
        }
        current = null
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/run/Runner.kt`, replace:

```kotlin
        // The run's answers that reached Kestrel, and the questions that it left open on purpose, in their order (T1).
        val asked = mutableListOf<Asked>()
```

with:

```kotlin
        // The run's answers that reached Kestrel, and the questions that it left open on purpose, in their order (T1).
        val asked = mutableListOf<Asked>()

        // agent-task's answer with the run's marker, read once after the end, for the detail line.
        var markers: HookAnswer? = null
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
Each run prints one line with the end, the check, the steps, the working time, the AI Credits (`unknown` when the end of a live run could not be read) and the tokens, never screen text.
```

with:

```text
Each run prints one line with the end, the check, the steps, the working time, the AI Credits (`unknown` when the end of a live run could not be read) and the tokens, never screen text, and a detail line, which a suite also writes into `details.txt`: the kinds of the questions with the run's answers, the owner's touches and the signals among them that Kestrel counted, whether a `say` sentence or a data tool's result held the marker, and the settings that a restore found changed and put back, in effect again or stored only, which the owner may have to set again by hand.
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `scripts\gradle.ps1 :tools:eval:test --tests dev.kestrel.eval.run.RunnerTest --tests dev.kestrel.eval.command.CommandsTest`
Expected: PASS.

Run: `scripts\gradle.ps1 :tools:eval:test`
Expected: BUILD SUCCESSFUL, E₀ + 2 tests.

Run: `scripts\gradle.ps1 --stop`

- [ ] **Step 5: Commit**

```powershell
git add tools/eval/src docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(eval): a detail line per run in details.txt with the run's answers, Kestrel's touch counts, the marker's places and what a restore put back" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 7: font-size puts the display page's other size controls back as Android applies them (T6)

On the A71, run 1 of font-size failed in all three of today's attempts with a FINISHED end, so the model most likely made another "bigger" control larger, such as Screen zoom, and font-size's restore put back only `font_scale`. The page holds three kinds of control, and each needs its own restore:

- the font scale, `system font_scale`, which Android applies when the setting changes: `SettingsValue` restores it already;
- the display density (Display size, One UI's Screen zoom), which Android applies only through `wm density`: writing `secure display_density_forced` back changes nothing on screen until a reboot (WindowManagerService reads that key only at boot and at a user switch), so `KeepDisplayDensity` reads the override with `wm density` and applies the old one again with `wm density <n>` or `wm density reset`;
- the vendor's other stored keys (One UI's font size index, its font style), whose effect only their own page applies: `KeepStoredSettings` writes each one back that the run changed, deletes each one that it added, and names them as stored only, with a warning that the screen may still show the run's value.

The run's detail line names the first restored kind in `putBack=` and the second in `byHand=` (Task 6's `PutBack`).

**Files:**
- Modify: `tools/eval/src/main/kotlin/dev/kestrel/eval/task/SettingsSteps.kt`, `tools/eval/src/main/kotlin/dev/kestrel/eval/task/SettingsTasks.kt`, `tools/eval/src/test/kotlin/dev/kestrel/eval/device/FakeAdb.kt`
- Test: `tools/eval/src/test/kotlin/dev/kestrel/eval/task/SettingsStepsTest.kt`

**Interfaces:**
- Consumes: `RunScope.setting(namespace, key)`, `shellQuoted(text)` (`CalendarSteps.kt`), `Restores.push(what, byHand, action)`, Task 6's `RunScope.putBack` (`recordApplied`, `recordStored`).
- Produces: `SettingKey(namespace: String, key: String)` (`toString()` is `namespace/key`); `KeepDisplayDensity : Setup` (an object) with `KeepDisplayDensity.NAME = "wm/density"` and `KeepDisplayDensity.override(scope: RunScope): String?`; `KeepStoredSettings(matching: Regex, except: Set<SettingKey> = emptySet()) : Setup` with `KeepStoredSettings.NAMESPACES` and `KeepStoredSettings.DISPLAY_SIZE`; FakeAdb answers `settings list <namespace>`, `wm density` and `wm density <n>|reset` (its `density`, the applied override, apart from the stored key).

Counts: `:tools:eval` N + 2 (E₀ + 4).

- [ ] **Step 1: Write the failing tests**

Add these tests to `tools/eval/src/test/kotlin/dev/kestrel/eval/task/SettingsStepsTest.kt`, before its last closing brace:

```kotlin
    @Test
    fun theDisplayDensityThatARunChangedIsAppliedAgainNotOnlyWrittenBack() {
        val run = FakeRun(DeviceKind.A71)

        assertEquals(mapOf(KeepDisplayDensity.NAME to "none"), KeepDisplayDensity.probe(run.scope))
        assertEquals(SetupResult.Ready, KeepDisplayDensity.apply(run.scope))
        // The run's Screen zoom: Android applies it at once and stores it.
        run.adb.density = "480"
        run.adb.settings["secure/display_density_forced"] = "480"

        assertTrue(run.restores.runAll().isEmpty())
        assertNull(run.adb.density)
        assertNull(run.adb.settings["secure/display_density_forced"])
        assertEquals(listOf(KeepDisplayDensity.NAME), run.scope.putBack.applied)

        // The owner's own override comes back as it was.
        run.adb.density = "400"
        assertEquals(SetupResult.Ready, KeepDisplayDensity.apply(run.scope))
        run.adb.density = "480"
        assertTrue(run.restores.runAll().isEmpty())
        assertEquals("400", run.adb.density)
    }

    @Test
    fun theStoredFontKeysThatARunChangedGoBackAndAreNamedForTheHand() {
        val run = FakeRun(DeviceKind.A71)
        run.adb.on("^settings delete (\\S+) (\\S+)$") {
            run.adb.settings.remove("${it.groupValues[1]}/${it.groupValues[2]}")
            ""
        }
        run.adb.settings.putAll(mapOf("system/font_scale" to "1.1", "system/font_size" to "3", "secure/android_id" to "x"))
        val keep = KeepStoredSettings(KeepStoredSettings.DISPLAY_SIZE, except = setOf(SettingKey("system", "font_scale")))

        assertEquals(mapOf("system/font_size" to "3"), keep.probe(run.scope))
        assertEquals(SetupResult.Ready, keep.apply(run.scope))
        // The run changed One UI's font size index and chose a font style, which One UI stores in a key of its own.
        run.adb.settings["system/font_size"] = "5"
        run.adb.settings["system/flip_font_style"] = "2"
        run.adb.settings["system/font_scale"] = "1.3"

        assertTrue(run.restores.runAll().isEmpty())
        assertEquals(mapOf("system/font_scale" to "1.3", "system/font_size" to "3", "secure/android_id" to "x"), run.adb.settings.toMap())
        assertEquals(listOf("system/font_size", "system/flip_font_style"), run.scope.putBack.stored)
        assertTrue(run.console.warnings.toString(), run.console.warnings.any { it.contains("system/font_size, system/flip_font_style") })
    }
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `scripts\gradle.ps1 :tools:eval:test --tests dev.kestrel.eval.task.SettingsStepsTest`
Expected: FAIL, the compiler reports `Unresolved reference 'KeepDisplayDensity'`, `'KeepStoredSettings'`, `'SettingKey'` and `'density'`.

- [ ] **Step 3: Answer the display commands in the fake device**

In `tools/eval/src/test/kotlin/dev/kestrel/eval/device/FakeAdb.kt`, replace:

```kotlin
    val settings = mutableMapOf<String, String>()
```

with:

```kotlin
    val settings = mutableMapOf<String, String>()

    /**
     * The display's override density that `wm density` prints, which Android applies at once; null without one. `wm density
     * <n>|reset` sets it and its stored key `secure/display_density_forced`, as Android does, while `settings put` changes
     * only the stored key, which Android applies only at boot.
     */
    var density: String? = null
```

In `tools/eval/src/test/kotlin/dev/kestrel/eval/device/FakeAdb.kt`, replace:

```kotlin
        SETTINGS_GET.matchEntire(command)?.let { return settings["${it.groupValues[1]}/${it.groupValues[2]}"] ?: "null" }
```

with:

```kotlin
        if (command == "wm density") return "Physical density: 420" + (density?.let { "\nOverride density: $it" } ?: "")
        WM_DENSITY.matchEntire(command)?.let { match ->
            val value = match.groupValues[1].takeIf { it != "reset" }
            density = value
            if (value == null) settings.remove("secure/display_density_forced") else settings["secure/display_density_forced"] = value
            return ""
        }
        SETTINGS_LIST.matchEntire(command)?.let { match ->
            val prefix = "${match.groupValues[1]}/"
            return settings.filterKeys { it.startsWith(prefix) }.entries.joinToString("\n") { "${it.key.removePrefix(prefix)}=${it.value}" }
        }
        SETTINGS_GET.matchEntire(command)?.let { return settings["${it.groupValues[1]}/${it.groupValues[2]}"] ?: "null" }
```

In `tools/eval/src/test/kotlin/dev/kestrel/eval/device/FakeAdb.kt`, replace:

```kotlin
        private val SETTINGS_GET = Regex("settings get (\\S+) (\\S+)")
```

with:

```kotlin
        private val WM_DENSITY = Regex("wm density (\\d+|reset)")
        private val SETTINGS_LIST = Regex("settings list (\\S+)")
        private val SETTINGS_GET = Regex("settings get (\\S+) (\\S+)")
```

- [ ] **Step 4: Keep the density and the stored keys**

In `tools/eval/src/main/kotlin/dev/kestrel/eval/task/SettingsSteps.kt`, replace:

```kotlin
/** The setting holds what the task asked for. */
data class SettingsIs(val namespace: String, val key: String, val wanted: Wanted) : Check {
    override fun passes(scope: RunScope, answer: HookAnswer): Boolean = wanted.accepts(scope.setting(namespace, key))
}
```

with:

```kotlin
/** The setting holds what the task asked for. */
data class SettingsIs(val namespace: String, val key: String, val wanted: Wanted) : Check {
    override fun passes(scope: RunScope, answer: HookAnswer): Boolean = wanted.accepts(scope.setting(namespace, key))
}

/** A setting by its namespace (`system`, `secure` or `global`) and its key. */
data class SettingKey(val namespace: String, val key: String) {
    override fun toString(): String = "$namespace/$key"
}

/**
 * Keeps the display's density (Display size, One UI's Screen zoom) as it was before the run (plan 6B-basefix T6): it
 * reads the override that `wm density` prints and changes nothing; its restore applies the old override again with `wm
 * density <n>`, or removes the run's with `wm density reset`, and checks `wm density` again. Android applies the density
 * only through the window manager, which also stores it in `secure display_density_forced`; a write of that key alone
 * shows nothing until a reboot. A density that the restore put back is named [NAME] in [RunScope.putBack]'s applied list.
 */
object KeepDisplayDensity : Setup {
    const val NAME = "wm/density"
    private val OVERRIDE = Regex("^Override density: (\\d+)$")

    override fun apply(scope: RunScope): SetupResult {
        val before = override(scope)
        val undo = before?.let { "wm density $it" } ?: "wm density reset"
        scope.restores.push("the display density as before", "Run: adb -s ${scope.device.serial} shell $undo") {
            if (override(scope) != before) {
                scope.device.idempotent(undo)
                check(override(scope) == before) { "The display density is still not as before." }
                scope.putBack.recordApplied(NAME)
            }
        }
        return SetupResult.Ready
    }

    override fun probe(scope: RunScope): Map<String, String> = mapOf(NAME to (override(scope) ?: "none"))

    /** The override density that `wm density` prints, or null when the display has none. */
    fun override(scope: RunScope): String? = scope.device.read("wm density").firstNotNullOfOrNull { OVERRIDE.find(it.trim())?.groupValues?.get(1) }

    override fun toString(): String = "KeepDisplayDensity"
}

/**
 * Keeps the stored settings of [NAMESPACES] whose keys [matching] finds, but [except], as they were before the run (plan
 * 6B-basefix T6): it changes nothing, and its one restore writes back each one that the run changed and deletes each one
 * that the run added. These are a vendor's keys, such as One UI's font size index and font style, whose effect only their
 * own Settings page applies, so the restore names each in [RunScope.putBack]'s stored list and warns that the screen may
 * still show the run's choice until it is set again by hand. Settings whose restore Android applies itself keep their own
 * steps, so they are [except]: the font scale ([SettingsValue]) and the display density ([KeepDisplayDensity]).
 */
data class KeepStoredSettings(val matching: Regex, val except: Set<SettingKey> = emptySet()) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val before = read(scope)
        val values = before.entries.joinToString { "${it.key}=${it.value}" }.ifEmpty { "none" }
        val byHand = "Run: adb -s ${scope.device.serial} shell settings list system (and secure, global), and set the keys that the warning names back to: $values"
        scope.restores.push("the stored display settings as before", byHand) {
            val after = read(scope)
            val changed = (before.keys + after.keys).filter { before[it] != after[it] }
            for (setting in changed) {
                val old = before[setting]
                scope.device.idempotent(
                    if (old == null) "settings delete ${setting.namespace} ${setting.key}" else "settings put ${setting.namespace} ${setting.key} ${shellQuoted(old)}",
                )
                scope.putBack.recordStored(setting.toString())
            }
            if (changed.isNotEmpty()) {
                scope.console.warn(
                    "The run changed ${changed.joinToString()}; the restore wrote them back, and the screen may show them only after " +
                        "a reboot or when they are set again in Settings > Display.",
                )
            }
        }
        return SetupResult.Ready
    }

    override fun probe(scope: RunScope): Map<String, String> = read(scope).mapKeys { it.key.toString() }

    private fun read(scope: RunScope): Map<SettingKey, String> = buildMap {
        for (namespace in NAMESPACES) {
            for (line in scope.device.read("settings list $namespace")) {
                val key = line.substringBefore('=', "").trim()
                val setting = SettingKey(namespace, key)
                if (key.isEmpty() || setting in except || !matching.containsMatchIn(key)) continue
                put(setting, line.substringAfter('=').trim())
            }
        }
    }

    companion object {
        val NAMESPACES = listOf("system", "secure", "global")

        /** The keys of a display page's size controls: the font scale, the display density, and the vendor's font and zoom keys. */
        val DISPLAY_SIZE = Regex("font|density|zoom", RegexOption.IGNORE_CASE)
    }
}
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/task/SettingsTasks.kt`, replace:

```kotlin
    val FONT_SIZE = task(
        "font-size", "make the text bigger",
        listOf(SettingsValue("system", "font_scale", "1.0")),
```

with:

```kotlin
    // The display page's other size controls are kept too, each as Android applies it, because a run may change them
    // instead of the font scale (T6).
    val FONT_SIZE = task(
        "font-size", "make the text bigger",
        listOf(
            KeepDisplayDensity,
            KeepStoredSettings(KeepStoredSettings.DISPLAY_SIZE, except = setOf(SettingKey("system", "font_scale"), SettingKey("secure", "display_density_forced"))),
            SettingsValue("system", "font_scale", "1.0"),
        ),
```

- [ ] **Step 5: Run the tests to see them pass**

Run: `scripts\gradle.ps1 :tools:eval:test --tests dev.kestrel.eval.task.SettingsStepsTest --tests dev.kestrel.eval.task.SettingsTasksTest`
Expected: PASS; font-size's runs and rehearsal in `SettingsTasksTest` still put every setting back.

Run: `scripts\gradle.ps1 :tools:eval:test`
Expected: BUILD SUCCESSFUL, E₀ + 4 tests.

Run: `scripts\gradle.ps1 --stop`

- [ ] **Step 6: Commit**

```powershell
git add tools/eval/src
git commit -m "fix(eval): font-size applies the display density again with wm density, writes the vendor's font keys back and names them for the hand" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 8: maps-place asks for a place that the system prompt does not name (T7)

The request "show Kalemegdan on Google Maps" is word for word the system prompt's example of `open_deep_link`'s `app`, so maps-place tested the example rather than the model. The task now asks for Ada Ciganlija, which is written the same in English and Serbian. Its dry-run script names the app "Google Maps", as the prompt tells the model, so that Task 10's dry runs test B1' on both devices. The system prompt does not change (the owner's decision).

**Files:**
- Modify: `tools/eval/src/main/kotlin/dev/kestrel/eval/task/AppTasks.kt`, `tools/eval/src/main/resources/dev/kestrel/eval/scripts/maps-place.json`
- Test: `tools/eval/src/test/kotlin/dev/kestrel/eval/task/AppTasksTest.kt`

**Interfaces:**
- Consumes: Task 4's `linkApp` (the script's `"app": "Google Maps"`).
- Produces: maps-place with the request "show Ada Ciganlija on Google Maps", the check `ScreenHas(packageName = MAPS, contains = "Ada Ciganlija")` and the rehearsal link `geo:0,0?q=Ada%20Ciganlija`.

Counts: `:tools:eval` unchanged (E₀ + 4).

- [ ] **Step 1: Change the test's fake screen to the new place**

In `tools/eval/src/test/kotlin/dev/kestrel/eval/task/AppTasksTest.kt`, replace:

```kotlin
            bench.adb.hook("SCREEN_HAS") { extras -> "count=${if (front == app && extras["contains"] in setOf("kestrel hovering", "Kalemegdan")) 1 else 0} app=$front" }
```

with:

```kotlin
            bench.adb.hook("SCREEN_HAS") { extras -> "count=${if (front == app && extras["contains"] in setOf("kestrel hovering", "Ada Ciganlija")) 1 else 0} app=$front" }
```

In `tools/eval/src/test/kotlin/dev/kestrel/eval/task/AppTasksTest.kt`, replace:

```kotlin
                    else -> if (words in setOf("kestrel hovering", "Kalemegdan", "1:04")) 1 else 0
```

with:

```kotlin
                    else -> if (words in setOf("kestrel hovering", "Ada Ciganlija", "1:04")) 1 else 0
```

- [ ] **Step 2: Run the test to see it fail**

Run: `scripts\gradle.ps1 :tools:eval:test --tests dev.kestrel.eval.task.AppTasksTest`
Expected: FAIL: `youtubeSearchAndMapsPlacePassWithTheirAppInFrontShowingTheWords` reports `maps-place expected:<OK> but was:<CHECK>`, because the check still looks for Kalemegdan.

- [ ] **Step 3: Ask for Ada Ciganlija**

In `tools/eval/src/main/kotlin/dev/kestrel/eval/task/AppTasks.kt`, replace:

```kotlin
        request = Request("show Kalemegdan on Google Maps"),
```

with:

```kotlin
        request = Request("show Ada Ciganlija on Google Maps"),
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/task/AppTasks.kt`, replace:

```kotlin
        check = listOf(Foreground(setOf(MAPS)), ScreenHas(packageName = MAPS, contains = "Kalemegdan")),
        dryRun = DryRun("maps-place.json"),
        rehearsal = Rehearsal.Steps(listOf(OpenUri("geo:0,0?q=Kalemegdan", MAPS, settle = 10.seconds))),
```

with:

```kotlin
        check = listOf(Foreground(setOf(MAPS)), ScreenHas(packageName = MAPS, contains = "Ada Ciganlija")),
        dryRun = DryRun("maps-place.json"),
        rehearsal = Rehearsal.Steps(listOf(OpenUri("geo:0,0?q=Ada%20Ciganlija", MAPS, settle = 10.seconds))),
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/task/AppTasks.kt`, replace:

```kotlin
 * ([DismissPermissionPrompt]).
 */
object AppTasks {
```

with:

```kotlin
 * ([DismissPermissionPrompt]). maps-place asks for a place that the system prompt's example of open_deep_link's app does
 * not name, so that it tests the model and not the example (plan 6B-basefix T7).
 */
object AppTasks {
```

Replace the whole `tools/eval/src/main/resources/dev/kestrel/eval/scripts/maps-place.json` with:

```json
[
  {"tool": "open_deep_link", "arguments": {"uri": "geo:0,0?q=Ada%20Ciganlija", "app": "Google Maps", "reason": "show the place on Google Maps"}},
  {"tool": "wait", "arguments": {"seconds": 5, "reason": "let Maps search for the place"}},
  {"tool": "wait", "arguments": {"seconds": 5, "reason": "let Maps show the place"}},
  {"tool": "wait", "arguments": {"seconds": 5, "reason": "let Maps show the place"}},
  {"tool": "wait", "arguments": {"seconds": 3, "reason": "let the page settle"}},
  {"tool": "finish", "arguments": {"summary": "Google Maps shows Ada Ciganlija.", "evidence": [{"target": {"contains": "Ada Ciganlija"}}]}}
]
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `scripts\gradle.ps1 :tools:eval:test --tests dev.kestrel.eval.task.AppTasksTest --tests dev.kestrel.eval.task.TasksTest`
Expected: PASS; `TasksTest` parses maps-place's script with every placeholder filled.

Run: `scripts\gradle.ps1 :tools:eval:test`
Expected: BUILD SUCCESSFUL, E₀ + 4 tests.

Run: `scripts\gradle.ps1 --stop`

- [ ] **Step 5: Commit**

```powershell
git add tools/eval/src
git commit -m "fix(eval): maps-place asks for Ada Ciganlija, which the system prompt's example does not name, and its script names the app Google Maps" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 9: One permission set for the evaluation, granted before every live series and kept

Today's live pass on the A71 showed calendar-add's task ending CANCELLED with its event saved, while logcat shows Kestrel starting `dev.kestrel/.app.agent.PermissionActivity` at 15:05:53: READ_CALENDAR was not granted, Kestrel asked for it as a hand-over of kind `PERMISSION`, and the run answered "cancel". The two devices also grant different sets (the A71 only READ_CONTACTS; the emulator READ and WRITE_CONTACTS, READ_MEDIA_IMAGES and location, but not READ_CALENDAR, READ_SMS or READ_CALL_LOG). The owner's decision (2026-11-24): one set for the evaluation on both devices, granted and kept granted: READ_CALENDAR, READ_CONTACTS, WRITE_CONTACTS, READ_SMS, READ_CALL_LOG, READ_MEDIA_IMAGES and READ_MEDIA_VIDEO, Kestrel's data-tool permissions of `app/src/main/AndroidManifest.xml`. Location, phone state and call answering are not in the set and stay as they are. Before a live series (`suite --live`, `injection --live`), the tool grants the set with `pm grant dev.kestrel <permission>` through the device guard, reads it back from `dumpsys package dev.kestrel` (`granted=true`), stops the series before any run when one is missing, and records each state in `suite.json`. There is no restore, by the owner's decision; AGENTS.md says so. The existing `Grant` setup steps of single tasks then find nothing to grant and take nothing back.

**Files:**
- Create: `tools/eval/src/main/kotlin/dev/kestrel/eval/run/EvalPermissions.kt`, `tools/eval/src/test/kotlin/dev/kestrel/eval/run/EvalPermissionsTest.kt`
- Modify: `tools/eval/src/main/kotlin/dev/kestrel/eval/command/SeriesCommand.kt`, `tools/eval/src/main/kotlin/dev/kestrel/eval/report/SuiteFolder.kt`, `tools/eval/src/test/kotlin/dev/kestrel/eval/command/FakeEnvironment.kt`, `AGENTS.md`, `docs/superpowers/specs/2026-11-12-kestrel-design.md`
- Test: `tools/eval/src/test/kotlin/dev/kestrel/eval/command/CommandsTest.kt`

**Interfaces:**
- Consumes: `Device.read`, `Device.idempotent` (both check the device in the same `adb shell` call), `Dumps.grantedPermissions(dump, permissions)`, `Refusal`, `DeviceCommandFailed`.
- Produces: `EvalPermissions.SET: List<String>` (the seven full names), `EvalPermissions.grant(device: Device): Map<String, Boolean>` (each permission's state after the grants, or a `Refusal` naming the missing ones); `SuiteCall.permissions: Map<String, Boolean>?` in `suite.json`; FakeEnvironment's `granted: MutableSet<String>` and `refusedGrant: String?`. Task 11 checks `suite.json`'s `permissions`.

Counts: `:tools:eval` N + 2 (E₀ + 6).

- [ ] **Step 1: Write the failing tests**

Create `tools/eval/src/test/kotlin/dev/kestrel/eval/run/EvalPermissionsTest.kt`:

```kotlin
package dev.kestrel.eval.run

import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.task.FakeRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's permission set for the evaluation, granted before a live series and kept (his decision of 2026-11-24). */
class EvalPermissionsTest {
    @Test
    fun theSetIsGrantedReadBackAndKeptAndAMissingOneStopsTheSeries() {
        val run = FakeRun(DeviceKind.A71)
        val granted = mutableSetOf("android.permission.READ_CONTACTS")
        var refused: String? = null
        run.adb.on("^dumpsys package dev\\.kestrel$") {
            "  Package [dev.kestrel] (316e119):\n    runtime permissions:\n" + granted.joinToString("\n") { "      $it: granted=true, flags=[ USER_SET ]" }
        }
        run.adb.on("^pm grant dev\\.kestrel (\\S+)$") { match -> match.groupValues[1].takeIf { it != refused }?.let { granted.add(it); "" } }

        val states = EvalPermissions.grant(run.device)

        assertEquals(EvalPermissions.SET.associateWith { true }, states)
        assertEquals(
            EvalPermissions.SET.filter { it != "android.permission.READ_CONTACTS" }.map { "pm grant dev.kestrel $it" },
            run.adb.commands.filter { it.startsWith("pm grant") },
        )
        assertTrue(run.adb.commands.none { it.startsWith("pm revoke") })

        // A grant that Android refuses stops the series before any run, and names what is missing.
        granted.remove("android.permission.READ_SMS")
        refused = "android.permission.READ_SMS"
        val refusal = assertThrows(Refusal::class.java) { EvalPermissions.grant(run.device) }
        assertTrue(refusal.message, refusal.message!!.contains("android.permission.READ_SMS"))
        assertTrue(run.adb.commands.none { it.startsWith("pm revoke") })
    }
}
```

Add this test to `tools/eval/src/test/kotlin/dev/kestrel/eval/command/CommandsTest.kt`, before its last closing brace:

```kotlin
    @Test
    fun aLiveSeriesGrantsTheEvaluationsPermissionsFirstAndStopsBeforeAnyRunWhenOneIsMissing() {
        fake.refusedGrant = "android.permission.READ_CALL_LOG"

        assertEquals(Cli.EXIT_REFUSED, fake.cli("suite", "--device", "emulator-5554", "--live", "--runs", "1", "--tasks", "stand-in-hotspot"))

        assertTrue(fake.output, fake.output.contains("android.permission.READ_CALL_LOG"))
        assertTrue(fake.adb.hookCalls("VOICE").isEmpty())
        assertEquals(EvalPermissions.SET.toSet() - "android.permission.READ_CALL_LOG", fake.granted.toSet())
        // A series without a model grants nothing.
        val dry = FakeEnvironment()
        assertEquals(Cli.EXIT_PASSED, dry.cli("suite", "--device", "emulator-5554", "--runs", "1", "--tasks", "stand-in-hotspot"))
        assertTrue(dry.adb.commands.none { it.startsWith("pm grant") })
    }
```

In `tools/eval/src/test/kotlin/dev/kestrel/eval/command/CommandsTest.kt`, replace:

```kotlin
import dev.kestrel.eval.report.Report
```

with:

```kotlin
import dev.kestrel.eval.report.Report
import dev.kestrel.eval.run.EvalPermissions
```

In `tools/eval/src/test/kotlin/dev/kestrel/eval/command/FakeEnvironment.kt`, replace:

```kotlin
    var end = "state=done end=FINISHED steps=3 workMs=9000 credits=0.00 confirmed=0 unconfirmed=0 shareItems=0 beforeTestMode=0 model=scripted"
```

with:

```kotlin
    var end = "state=done end=FINISHED steps=3 workMs=9000 credits=0.00 confirmed=0 unconfirmed=0 shareItems=0 beforeTestMode=0 model=scripted"

    /** Kestrel's runtime permissions on the fake device, which `pm grant` adds to, except [refusedGrant]. */
    val granted = mutableSetOf<String>()
    var refusedGrant: String? = null
```

In `tools/eval/src/test/kotlin/dev/kestrel/eval/command/FakeEnvironment.kt`, replace:

```kotlin
        adb.on("^dumpsys package dev\\.kestrel$") { "  Package [dev.kestrel] (316e119):\n    userId=$KESTREL_UID\n    versionCode=100 minSdk=33 targetSdk=37\n    versionName=1.0.0\n    lastUpdateTime=$installed" }
```

with:

```kotlin
        adb.on("^dumpsys package dev\\.kestrel$") {
            "  Package [dev.kestrel] (316e119):\n    userId=$KESTREL_UID\n    versionCode=100 minSdk=33 targetSdk=37\n    versionName=1.0.0\n    lastUpdateTime=$installed\n" +
                "    runtime permissions:\n" + granted.joinToString("\n") { "      $it: granted=true, flags=[ USER_SET ]" }
        }
        adb.on("^pm grant dev\\.kestrel (\\S+)$") { match -> match.groupValues[1].takeIf { it != refusedGrant }?.let { granted.add(it); "" } }
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `scripts\gradle.ps1 :tools:eval:test --tests dev.kestrel.eval.run.EvalPermissionsTest --tests dev.kestrel.eval.command.CommandsTest`
Expected: FAIL, the compiler reports `Unresolved reference 'EvalPermissions'`.

- [ ] **Step 3: Grant the set before a live series**

Create `tools/eval/src/main/kotlin/dev/kestrel/eval/run/EvalPermissions.kt`:

```kotlin
package dev.kestrel.eval.run

import dev.kestrel.eval.device.Device
import dev.kestrel.eval.device.DeviceCommandFailed
import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.device.Refusal

/**
 * The owner's permission set for the evaluation (his decision of 2026-11-24; plan 6B-basefix): Kestrel's data-tool
 * permissions of its manifest, granted before every live series on both test devices and kept granted afterwards, so that
 * no task stops at Kestrel's own permission request, which a run would cancel, and both devices test the same Kestrel.
 * Location, phone state and call answering are not in the set and stay as they are. There is no restore, by that
 * decision; AGENTS.md says so.
 */
object EvalPermissions {
    private const val KESTREL = "dev.kestrel"

    val SET = listOf(
        "android.permission.READ_CALENDAR",
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.READ_SMS",
        "android.permission.READ_CALL_LOG",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
    )

    /**
     * Grants each permission of [SET] that `dumpsys package` does not show as granted, through the device guard, reads
     * the set back and returns each one's state; throws [Refusal], before any run, when one is still not granted.
     */
    fun grant(device: Device): Map<String, Boolean> {
        val before = granted(device)
        for (permission in SET.filter { it !in before }) {
            try {
                device.idempotent("pm grant $KESTREL $permission")
            } catch (e: DeviceCommandFailed) {
                // The read-back below names it.
            }
        }
        val after = granted(device)
        val states = SET.associateWith { it in after }
        val missing = states.filterValues { !it }.keys
        if (missing.isNotEmpty()) {
            throw Refusal("Kestrel on ${device.serial} still lacks ${missing.joinToString()} after pm grant, so the live series does not run; grant it by hand in Settings > Apps > Kestrel > Permissions.")
        }
        return states
    }

    private fun granted(device: Device): List<String> = Dumps.grantedPermissions(device.read("dumpsys package $KESTREL"), SET)
}
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/report/SuiteFolder.kt`, replace:

```kotlin
data class SuiteCall(val device: String, val live: Boolean, val runs: Int, val tasks: List<String>, val commit: String, val kestrel: KestrelBuild? = null) {
```

with:

```kotlin
data class SuiteCall(
    val device: String,
    val live: Boolean,
    val runs: Int,
    val tasks: List<String>,
    val commit: String,
    val kestrel: KestrelBuild? = null,
    /** The evaluation's permission set and each one's state when the live series began (plan 6B-basefix); null without a model. */
    val permissions: Map<String, Boolean>? = null,
) {
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/command/SeriesCommand.kt`, replace:

```kotlin
            session.prepare(todo.map { byName.getValue(it.first) }, live)
            val build = KestrelBuild.installed(session.device)?.let { KestrelBuild.withState(it, session.hooks.state()) }
            if (recorded != null && !recorded.sameInstall(build)) throw Refusal("Kestrel was installed again during the preflight; run a new suite.")
            call.copy(kestrel = build).write(folder)
```

with:

```kotlin
            session.prepare(todo.map { byName.getValue(it.first) }, live)
            // The owner's permission set for the evaluation, granted and kept before a live series (2026-11-24).
            val permissions = if (live) EvalPermissions.grant(session.device) else null
            val build = KestrelBuild.installed(session.device)?.let { KestrelBuild.withState(it, session.hooks.state()) }
            if (recorded != null && !recorded.sameInstall(build)) throw Refusal("Kestrel was installed again during the preflight; run a new suite.")
            call.copy(kestrel = build, permissions = permissions).write(folder)
```

In `tools/eval/src/main/kotlin/dev/kestrel/eval/command/SeriesCommand.kt`, replace:

```kotlin
import dev.kestrel.eval.report.SuiteFolder
import dev.kestrel.eval.run.LineWriter
```

with:

```kotlin
import dev.kestrel.eval.report.SuiteFolder
import dev.kestrel.eval.run.EvalPermissions
import dev.kestrel.eval.run.LineWriter
```

In `AGENTS.md`, replace:

```text
PCAPdroid, which the egress check needs on both test devices, is the one app that a script installs otherwise, through `scripts\eval.ps1 egress-setup`; it stays installed, with its API key in the git-ignored `eval\local\`.
```

with:

```text
PCAPdroid, which the egress check needs on both test devices, is the one app that a script installs otherwise, through `scripts\eval.ps1 egress-setup`; it stays installed, with its API key in the git-ignored `eval\local\`. Before every live series, `:tools:eval` grants Kestrel the evaluation's permission set on the device (READ_CALENDAR, READ_CONTACTS, WRITE_CONTACTS, READ_SMS, READ_CALL_LOG, READ_MEDIA_IMAGES and READ_MEDIA_VIDEO), and by the owner's decision of 2026-11-24 they stay granted on both test devices: no restore takes them back. Location, phone state and call answering are not in the set and stay as the owner left them.
```

In `docs/superpowers/specs/2026-11-12-kestrel-design.md`, replace:

```text
It puts the device back however the run ends, also after an error or Ctrl+C.
```

with:

```text
It puts the device back however the run ends, also after an error or Ctrl+C. Before a live series, it grants Kestrel its data-tool permissions (calendar, contacts with writing, SMS, call log, photos and videos) on the device and records their states; by the owner's decision they stay granted on both test devices, so that both test the same Kestrel and no task stops at a permission request.
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `scripts\gradle.ps1 :tools:eval:test --tests dev.kestrel.eval.run.EvalPermissionsTest --tests dev.kestrel.eval.command.CommandsTest --tests dev.kestrel.eval.command.ResumeTest --tests dev.kestrel.eval.command.CompareCommandTest`
Expected: PASS.

Run: `scripts\gradle.ps1 :tools:eval:test`
Expected: BUILD SUCCESSFUL, E₀ + 6 tests.

Run: `scripts\gradle.ps1 --stop`

- [ ] **Step 5: Commit**

```powershell
git add tools/eval/src AGENTS.md docs/superpowers/specs/2026-11-12-kestrel-design.md
git commit -m "feat(eval): a live series grants Kestrel the evaluation's permission set first, keeps it by the owner's decision, and records it in suite.json" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

---

### Task 10: The checks on both devices before the baselines (the controller's steps)

These are the controller's own steps, and Step 1 needs the owner's hands for about 3 minutes. They give Kestrel on kestrel-api37 back what the incident of 2026-11-24 wiped, install the plan's build on both devices, run the scripted model on the changed tasks, and read the A71's display settings. No step calls a provider. The controller holds `.superpowers\sdd\emulator.lock`, and no other agent uses a device, Gradle or `scripts\eval.ps1` meanwhile. The A71 must be on USB for Steps 4, 5, 8 and 9; when it is not, those steps and Task 11's A71 part wait for the owner's return.

**The incident.** On 2026-11-24 at about 16:03, a device test of fix-6b-final ran `scripts\connected-test.ps1` without `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`, and Gradle uninstalled Kestrel from kestrel-api37 with its data (`fix-6b-final-report.md`). Kestrel is installed again, but its Copilot profile, its runtime permissions, notification access, the touch rule, test mode's stored settings, memory and approvals are gone; the Google account is unchanged (1). Since 25bafae2, `scripts\connected-test.ps1` always keeps Kestrel and its data, and fails when Kestrel was uninstalled or installed again. Until Steps 1 and 2 pass, nothing runs live on kestrel-api37.

**Files:** none changed; findings go into `.superpowers/sdd/progress.md`.

**Interfaces:**
- Consumes: every earlier task; `scripts\install.ps1`, `scripts\eval.ps1`; the controller's launcher `C:\Users\AleksaLocal\.copilot\session-state\337a39e5-0c45-4e8a-b4a0-4c1d2eef024e\files\a71-eval-copy.ps1`, which checks the A71 with `Assert-TestDevice`, loads `Oracle.ps1`, unlocks it, puts Kestrel on the silent clip microphone and listening, runs the tool from a copy of `tools\eval\build\install\eval` in TEMP with its arguments, and afterwards turns Kestrel off, gives back its microphone, sets "Stay awake" to 0 and locks the A71.
- Produces: the plan's build on both devices, and the confirmed display keys of the A71.

Counts: unchanged.

- [ ] **Step 1: The owner signs Kestrel in to Copilot on kestrel-api37 (about 3 minutes)**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; if ("$(& $adb -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 -Command provider-standin; & $adb -s emulator-5554 shell am start -n dev.kestrel/.app.MainActivity --es dev.kestrel.app.extra.PAGE PROVIDERS`
Expected: `standin=off profile=none copilot=0` (the incident's state), and Kestrel's page "Providers and profiles" opens in the emulator's window. If it already answers `profile=copilot`, skip to Step 2.

The owner then does this, in the emulator's window on this computer, with the mouse: on Kestrel's page "Providers and profiles", click "Add a profile", "GitHub Copilot" and "Sign in"; open github.com/login/device in this computer's browser, enter the code that the page shows, and approve; when Kestrel's page shows the models, choose `gpt-6-luna` as the profile's default (lightweight) model, the model of the A71's profile and of both v1.0 parts, and the versatile model that the A71's profile has; then turn on "Make default" and click "Save". He tells the controller when the page shows the profile.

Run: `pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 -Command provider-standin`
Expected: `standin=off profile=copilot copilot=1`. Any other answer: the owner finishes the sign-in ("Make default", "Save"), and the controller reads it again; the task does not go on without `profile=copilot`.

- [ ] **Step 2: Check what else the incident wiped on kestrel-api37**

Kestrel's state there before the incident: the touch rule CREDITS on branch A (progress.md, sitting 1 of 2026-11-23: "touch rule CREDITS"; Android 17's default), notification access granted, location, the microphone (RECORD_AUDIO) and notifications (POST_NOTIFICATIONS) granted, the speech models with the optional Kokoro voice and look_closer's OCR models in Kestrel's models folder (plan 6B's sittings made Piper and Kokoro clips there), and Kestrel's accessibility service on. The uninstall deleted the models folder too (`scripts\push-models.ps1`: "Uninstalling Kestrel deletes the models from the device").

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; if ("$(& $adb -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; "google accounts: $(@(& $adb -s emulator-5554 shell dumpsys account | Select-String 'type=com\.google\}').Count)"; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 -Command agent-touch-source; "listener: $(& $adb -s emulator-5554 shell settings get secure enabled_notification_listeners)"; "accessibility: $(& $adb -s emulator-5554 shell settings get secure enabled_accessibility_services) enabled=$(& $adb -s emulator-5554 shell settings get secure accessibility_enabled)"; & $adb -s emulator-5554 shell dumpsys package dev.kestrel | Select-String -Pattern 'android\.permission\.(ACCESS_FINE_LOCATION|ACCESS_COARSE_LOCATION|RECORD_AUDIO|POST_NOTIFICATIONS): granted='; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 -Command state; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 -Command agent-settings`
Expected: `google accounts: 1`; `rule=CREDITS branch=A sdk=37`; a `listener:` line that holds `dev.kestrel/dev.kestrel.datatools.notifications.KestrelNotificationListener`; an `accessibility:` line that holds `dev.kestrel/dev.kestrel.app.KestrelAccessibilityService` with `enabled=1`; the four permission lines (both locations, RECORD_AUDIO, POST_NOTIFICATIONS) with `granted=true`; `screen=on` in the state; and agent-settings' counts, which the controller notes (test mode's owner names; the suite's preflight turns test mode on, and each task sets its own aliases).

Put back each one that differs, as it was before the incident, then run the command above again:

- the touch rule: `pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 -Command agent-touch-source -Rule CREDITS -Branch A`;
- location, the microphone and notifications, on kestrel-api37 only (the A71's stay as they are): `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; if ("$(& $adb -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; foreach ($permission in 'ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION', 'RECORD_AUDIO', 'POST_NOTIFICATIONS') { & $adb -s emulator-5554 shell pm grant dev.kestrel android.permission.$permission }`;
- notification access: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; if ("$(& $adb -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; & $adb -s emulator-5554 shell cmd notification allow_listener dev.kestrel/dev.kestrel.datatools.notifications.KestrelNotificationListener`;
- the accessibility service: the owner turns it on in the emulator's window (Settings > Accessibility > Kestrel > Use Kestrel > Allow), because only his hand may grant it; ask him then, about 1 minute.

Kestrel's other runtime permissions are Task 9's set, which Task 11's live suites grant. Location, the microphone and notifications are not in that set, and the owner's rule is that they stay as they were, so this step puts them back as they were before the incident. Memory and screenshot approvals start empty, which no task depends on.

Then put the models back, the speech models with the Kokoro voice and look_closer's OCR models, which the push checks by their SHA-256 hashes:

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; if ("$(& $adb -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; scripts\push-models.ps1 -Serial emulator-5554 -Kokoro`
Expected: `The speech and OCR models are on emulator-5554.`; the first run after the incident copies every file again (about 1.2 GB with Kokoro, from the cache on this computer). Without that line, stop: look_closer would skip its OCR step on kestrel-api37, and the new part would measure another Kestrel than the A71's and the v1.0 part.

Then check that Kestrel reaches listening on the silent clip microphone, as Task 11's launcher starts it, and leave it off afterwards, as the launchers expect:

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; if ("$(& $adb -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; function Hook([string]$Command, [hashtable]$With = @{}) { (& .\scripts\hook.ps1 -Serial emulator-5554 -Command $Command @With 2>&1) -join "`n" }; function Mode { if ((Hook voice-state) -match 'mode=(\w+)') { $Matches[1] } else { 'unknown' } }; & $adb -s emulator-5554 shell am start -n dev.kestrel/.app.MainActivity | Out-Null; Start-Sleep 5; Hook voice @{ Send = 'off' } | Out-Null; $deadline = (Get-Date).AddSeconds(30); while ((Mode) -ne 'off' -and (Get-Date) -lt $deadline) { Start-Sleep 1 }; "before: mode=$(Mode)"; Hook voice-clip @{ Quiet = $true } | Out-Null; try { & $adb -s emulator-5554 shell am start -n dev.kestrel/.app.MainActivity | Out-Null; Hook voice @{ Send = 'toggle' } | Out-Null; Start-Sleep 1; if ((Mode) -eq 'paused') { Hook voice @{ Send = 'resume' } | Out-Null }; $deadline = (Get-Date).AddSeconds(120); while ((Mode) -ne 'listening' -and (Get-Date) -lt $deadline) { Start-Sleep 2 }; "listening: $(Mode)" } finally { Hook voice @{ Send = 'off' } | Out-Null; Start-Sleep 3; Hook voice-clip @{ Clear = $true } | Out-Null; & $adb -s emulator-5554 shell input keyevent KEYCODE_HOME | Out-Null; "after: mode=$(Mode)" }`
Expected: `before: mode=off` (the toggle then turns listening on, as in Task 11's launchers, and does not turn it off), `listening: listening` and `after: mode=off`. Any other mode: stop, because Step 6's preflight would refuse and the emulator's launcher would run the tool anyway; the controller finds why (a model missing, a permission, the voice choice) before Step 6.

Last, the controller opens Kestrel's main page in the emulator's window (`am start -n dev.kestrel/.app.MainActivity`, with the emulator check) and compares its setup checklist with the state before the incident: models downloaded, permissions, the accessibility service, the voice. A choice that only the owner's hand may make, such as the voice when the checklist asks for one, he makes now as it was before the incident, and the controller notes it in Step 10.

- [ ] **Step 3: The whole gate, as CI runs it**

Run: `scripts\gradle.ps1 :build-logic:convention:check check kestrelAudit`
Expected: BUILD SUCCESSFUL. Then the count command of "Before you start" prints A₀ + 8, G₀ + 1 and E₀ + 6.

Run: `scripts\test-scripts.ps1`
Expected: every test passes in both shells, P₀ in each.

Run: `scripts\gradle.ps1 --stop`

- [ ] **Step 4: Check both devices before any change**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; if ("$(& $adb -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; "google accounts: $(@(& $adb -s emulator-5554 shell dumpsys account | Select-String 'type=com\.google\}').Count)"; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 -Command provider-standin; & $adb -s R58N66RVD7Z get-state; & $adb -s R58N66RVD7Z shell getprop ro.product.model; "stay_on: $(& $adb -s R58N66RVD7Z shell settings get global stay_on_while_plugged_in)"; git status --short; git rev-parse HEAD`
Expected: the emulator's count of Google accounts (note it), `profile=copilot`, `device`, `SM-A715F`, `stay_on: 0`, no changed file, and HEAD at Task 9's commit.

- [ ] **Step 5: Install the plan's build on both devices**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; if ("$(& (Get-Adb) -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; scripts\install.ps1 -Serial emulator-5554; scripts\install.ps1 -Serial emulator-5554 -TestApp; scripts\install.ps1 -Serial R58N66RVD7Z; scripts\gradle.ps1 --stop`
Expected: three `Success` lines of `adb install`. `:testapp` is updated on kestrel-api37 for Task 2's notification body; on the A71 each suite's preflight installs `:testapp` from this build and removes it again.

Run: `foreach ($serial in 'emulator-5554', 'R58N66RVD7Z') { pwsh -NoProfile -File scripts\hook.ps1 -Serial $serial -Command state }`
Expected: on each device `commit=<the HEAD of Step 4>` and `dirty=0`, and `provider-standin` on kestrel-api37 still `profile=copilot` (an update keeps Kestrel's data). If the emulator froze at its first voice toggle after the install, as on 2026-11-23 and 2026-11-24, cold-boot it with `scripts\start-emulator.ps1` and check its serial and Step 4's accounts and profile again.

- [ ] **Step 6: The changed tasks with the scripted model on kestrel-api37**

Run: `scripts\eval.ps1 suite --device emulator-5554 --runs 1 --tasks 'maps-place,font-size,notification-read,contact-delete-declined'`
Expected: four lines with `result=pass reason=ok` (contact-delete-declined with `end=DECLINED`), `The results are in eval-results\kestrel-api37-<time>.`, and the command's own checks of the accounts and the profile pass. maps-place passing proves B1' on AOSP's launcher, where Google Maps is labelled "Maps": if it fails with `end=FAILED_BY_MODEL` or `end=TEST_MODE`, stop and ask the controller.

Run: `Get-Content ((Get-ChildItem eval-results -Directory -Filter 'kestrel-api37-2*' | Sort-Object Name | Select-Object -Last 1).FullName + '\details.txt')`
Expected: four detail lines; maps-place and font-size with `asked=none` and `putBack=none`; notification-read with `markerSaid=0 markerInData=1`; contact-delete-declined with `asked=confirm/deleting:no`; each with `ownerTouches=0 signals=0`.

- [ ] **Step 7: The changed rehearsals on kestrel-api37**

Run: `scripts\eval.ps1 rehearse --device emulator-5554 --tasks 'maps-place,font-size'`
Expected: `rehearse=maps-place result=pass reason=ok` and `rehearse=font-size result=pass reason=ok`, with no `Not put back:` warning and no `not as before`.

Then remove the test events that earlier attempts left in the owner's calendar on kestrel-api37, which calendar-read would otherwise read with its own (the analysis, sections 3 and 5): calendar-read's events are titled "Kestrel test <marker>", so only those are deleted, by their `_id`, again while Google Calendar's sync puts one back; the count of tomorrow's events is printed as a count only.

Run (in PowerShell 7): `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; if ("$(& $adb -s emulator-5554 emu avd name)" -notmatch '^kestrel-api37\b') { Write-Error 'emulator-5554 is not kestrel-api37' -ErrorAction Stop }; function Get-TestEvent { @(& $adb -s emulator-5554 shell "content query --uri content://com.android.calendar/events --projection _id --where `"deleted=0 AND title LIKE 'Kestrel test %'`"" | Select-String '_id=(\d+)' | ForEach-Object { $_.Matches[0].Groups[1].Value }) }; "test events before: $((Get-TestEvent).Count)"; foreach ($round in 1..10) { $ids = Get-TestEvent; if ($ids.Count -eq 0) { break }; foreach ($id in $ids) { & $adb -s emulator-5554 shell "content delete --uri content://com.android.calendar/events --where _id=$id" | Out-Null }; Start-Sleep -Seconds 3 }; "test events after: $((Get-TestEvent).Count)"; $from = [DateTimeOffset]::new((Get-Date).Date.AddDays(1)).ToUnixTimeMilliseconds(); "tomorrow's events: $(@(& $adb -s emulator-5554 shell "content query --uri content://com.android.calendar/events --projection _id --where `"deleted=0 AND dtstart>=$from AND dtstart<$($from + 86400000)`"" | Select-String '^Row: ').Count)"`
Expected: `test events before: <n>`, `test events after: 0` and `tomorrow's events: <m>`, counts only. calendar-add's events carry only the marker as their title and cannot be told from the owner's by their title, so they are not deleted here: when `<m>` is above 0, the controller notes it, because calendar-read's reply may then name other events too.

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; "google accounts: $(@(& $adb -s emulator-5554 shell dumpsys account | Select-String 'type=com\.google\}').Count)"; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 -Command provider-standin`
Expected: the count of Step 4 and `profile=copilot`.

- [ ] **Step 8: Read the A71's display keys and permissions, read-only**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; Assert-TestDevice -Serial R58N66RVD7Z | Out-Null; foreach ($ns in 'system', 'secure', 'global') { foreach ($line in @(& $adb -s R58N66RVD7Z shell settings list $ns)) { $key = ($line -split '=', 2)[0]; if ($key -match 'font|density|zoom') { "kept $ns/$key = $(($line -split '=', 2)[1])" } elseif ($key -match 'scale|size|display|text') { "other $ns/$key" } } }; "wm density: $(& $adb -s R58N66RVD7Z shell wm density)"; "wm size: $(& $adb -s R58N66RVD7Z shell wm size)"; & $adb -s R58N66RVD7Z shell dumpsys package dev.kestrel | Select-String -Pattern 'android\.permission\.(READ_CALENDAR|READ_CONTACTS|WRITE_CONTACTS|READ_SMS|READ_CALL_LOG|READ_MEDIA_IMAGES|READ_MEDIA_VIDEO): granted='`
Expected: `kept` lines with the keys that Task 7's pattern finds and their values (display settings only, no owner data), among them `system/font_scale` (1.1 today); `other` lines with key names only; the density and size; and the permission lines that the A71 grants now (READ_CONTACTS only today; Task 11's live suite grants the rest). The step changes nothing. `wm density` must print no `Override density` line: one would be a Screen zoom left over, possibly from run 1 of the v1.0 font-size runs, which `KeepDisplayDensity` would keep as the owner's; then stop, because only the owner can say which density is his, and Task 11 waits for his answer.

A `kept` key of One UI's font style or font size index is expected: `KeepStoredSettings` writes it back and names it in `byHand=`, because only its page applies it. If an `other` key is plainly One UI's font size, font style or Screen zoom control (a Samsung key whose name the pattern `font|density|zoom` misses), stop: the controller extends `KeepStoredSettings.DISPLAY_SIZE` in a fix round, with a test, before Task 11.

- [ ] **Step 9: maps-place's script and the rehearsals on the A71**

Steps 6 and 7 built `tools\eval\build\install\eval`, which the launcher copies.

Run: `pwsh -NoProfile -File 'C:\Users\AleksaLocal\.copilot\session-state\337a39e5-0c45-4e8a-b4a0-4c1d2eef024e\files\a71-eval-copy.ps1' suite --device R58N66RVD7Z --runs 1 --tasks maps-place`
Expected: `task=maps-place run=1 result=pass … reason=ok` and its detail line with `asked=none`, then `eval exit=0` and `after: mode=off stay_on=0`. This proves B1' on One UI's launcher. font-size's script follows AOSP 17's pages, so it has no dry run on the A71.

Run: `pwsh -NoProfile -File 'C:\Users\AleksaLocal\.copilot\session-state\337a39e5-0c45-4e8a-b4a0-4c1d2eef024e\files\a71-eval-copy.ps1' rehearse --device R58N66RVD7Z --tasks 'maps-place,font-size'`
Expected: both `result=pass reason=ok`, no `Not put back:` warning, then `after: mode=off stay_on=0`.

- [ ] **Step 10: Note the findings**

Add to `.superpowers/sdd/progress.md`, under "## Plan 6B-basefix": the build's commit on both devices, Step 6's four results and detail lines, Step 7's counts of test events and of tomorrow's events, Step 8's kept keys with their values and the other keys' names, the A71's density and size, the A71's permissions before Task 11, and Step 9's results.

---

### Task 11: Both baselines again (the controller's steps)

These are the controller's own steps: they call the owner's default Copilot profile, which only the controller's session does (AGENTS.md), and they use the A71 while it is on USB. Each suite runs every task of its device 3 times (a known gap once), about 2 to 3 hours each, about 30 to 80 AI Credits per device, with no ceiling. The A71's suite starts first (the owner's rule that work on the A71 comes first); the emulator's starts beside it. Both run from their own copies of the tool, so that nothing may build or install `:tools:eval` until both have ended.

**Files:**
- Modify: `eval/baseline.json` (written by `--record-baseline`)

**Interfaces:**
- Consumes: Tasks 1 to 10; the launchers `files\a71-eval-copy.ps1` (as in Task 10) and `files\emu-eval-copy.ps1`, which checks that emulator-5554 is kestrel-api37, copies the tool to TEMP, puts Kestrel on the silent clip microphone and listening, runs the tool with its arguments, and afterwards gives back Kestrel's microphone and its listening.
- Produces: both parts of `eval\baseline.json` at the plan's commit; each results folder with `suite.json` (with `permissions`), `runs.txt`, `details.txt` and `report.json`.

Counts: unchanged.

- [ ] **Step 1: Check that the devices run HEAD's build and build the tool once**

Run: `git status --short; git rev-parse HEAD; foreach ($serial in 'emulator-5554', 'R58N66RVD7Z') { pwsh -NoProfile -File scripts\hook.ps1 -Serial $serial -Command state }`
Expected: no changed file, and `commit=<HEAD> dirty=0` on both devices. A commit after Task 10's install (such as a fix round of Task 10, Step 8) needs Task 10, Step 5 again, because `--record-baseline` refuses a build that is not HEAD's.

Run: `scripts\gradle.ps1 :tools:eval:installDist; scripts\gradle.ps1 --stop`
Expected: BUILD SUCCESSFUL. From now on until Step 5, nobody runs `installDist`, `scripts\eval.ps1` or another Gradle task of `:tools:eval` but `test`, `check` or `jar`.

- [ ] **Step 2: Start the A71's suite**

Run (in its own shell, which stays open for the suite's hours): `pwsh -NoProfile -File 'C:\Users\AleksaLocal\.copilot\session-state\337a39e5-0c45-4e8a-b4a0-4c1d2eef024e\files\a71-eval-copy.ps1' suite --device R58N66RVD7Z --live --runs 3 --record-baseline`
Expected at its start: `listening: listening` and `tool copy: …`; then one run line and one detail line per run of the A71's tasks; at its end `The results are in eval-results\a71-<time>.`, `eval\baseline.json now holds the part of a71, commit <HEAD>, model <id>.`, `eval exit=0` or `eval exit=1` (1 when a run failed), and `after: mode=off stay_on=0`.

- [ ] **Step 3: Start kestrel-api37's suite**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; "google accounts: $(@(& $adb -s emulator-5554 shell dumpsys account | Select-String 'type=com\.google\}').Count)"; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 -Command provider-standin`
Expected: Task 10's count and `profile=copilot`.

Run (in its own shell, once Step 2's suite has started): `pwsh -NoProfile -File 'C:\Users\AleksaLocal\.copilot\session-state\337a39e5-0c45-4e8a-b4a0-4c1d2eef024e\files\emu-eval-copy.ps1' suite --device emulator-5554 --live --runs 3 --record-baseline`
Expected: as Step 2 with `kestrel-api37` and its tasks; its own checks of the accounts and the profile pass; at its end `after: mode=… live=…` with the live session off.

As soon as the emulator's suite has written its first run line, compare the two suites' models:

Run: `$models = foreach ($f in 'a71-2*', 'kestrel-api37-2*') { $folder = (Get-ChildItem eval-results -Directory -Filter $f | Sort-Object Name | Select-Object -Last 1).FullName; $line = Get-Content "$folder\runs.txt" | Where-Object { $_ -match "`ttask`ttask=" -and $_ -notmatch 'model=none' } | Select-Object -First 1; "$f $(if ($line -match 'model=(\S+)') { $Matches[1] } else { 'none yet' })" }; $models`
Expected: `a71-2* gpt-6-luna` and `kestrel-api37-2* gpt-6-luna` (the `2` leaves out the `kestrel-api37-injection-*` folders, which sort after a suite's folder). Two different models: stop the emulator's suite with Ctrl+C in its shell (its restores put the device back), have the owner set `gpt-6-luna` in the emulator's Copilot profile as in Task 10, Step 1, and start Step 3 again as a new suite; the two parts must measure one model.

- [ ] **Step 4: Judge each run line while the suites run**

A run with `reason=asked`, `end`, `check` or `confirm` is the owner's policy or Kestrel's result, and stays in the baseline; `reason=asked` names in `asked=` what the run cancelled (such as `asked=hand-over/PERMISSION`, which Task 9's permission set should have removed: note any). A run with `reason=setup`, `stalled`, `stopped` or `call`, or a `Not put back:` warning, is a fault of the test: let the suite end, or stop it with Ctrl+C in its shell when the device is left changed; put the device back by hand as the warning says; fix the fault in a reviewed fix round only after both suites ended. After a fix to the tool alone, continue with the same launcher and `suite --device <serial> --live --resume <folder> --record-baseline`. After a fix to Kestrel, install it with Task 10, Step 5 and run a new suite on that device with Step 2's or Step 3's command: the tool refuses to resume a folder whose runs another install of Kestrel made, because its runs would mix two builds.

- [ ] **Step 5: Check both devices and both parts after the suites**

Run: `. scripts\lib\Kestrel.ps1; Use-KestrelToolchain; $adb = Get-Adb; "google accounts: $(@(& $adb -s emulator-5554 shell dumpsys account | Select-String 'type=com\.google\}').Count)"; pwsh -NoProfile -File scripts\hook.ps1 -Serial emulator-5554 -Command provider-standin; "stay_on: $(& $adb -s R58N66RVD7Z shell settings get global stay_on_while_plugged_in)"; foreach ($f in 'a71-2*', 'kestrel-api37-2*') { $folder = (Get-ChildItem eval-results -Directory -Filter $f | Sort-Object Name | Select-Object -Last 1).FullName; $call = Get-Content "$folder\suite.json" -Raw | ConvertFrom-Json; "$folder permissions: $(@($call.permissions.PSObject.Properties | ForEach-Object { "$($_.Name -replace 'android.permission.', '')=$($_.Value)" }) -join ' ')" }; Get-Content eval\baseline.json -Raw | ConvertFrom-Json | ForEach-Object { $_.devices.PSObject.Properties | ForEach-Object { "$($_.Name): commit=$($_.Value.commit) model=$($_.Value.model) tasks=$(@($_.Value.tasks.PSObject.Properties).Count)" } }`
Expected: Task 10's count, `profile=copilot`, `stay_on: 0`; each folder's seven permissions `=True`; and the parts as the two suites left them.

Each suite read and wrote `eval\baseline.json` at its own end, and the two ends may have met, so both parts are recorded again now, one after the other, from their complete folders; that needs nothing of the device but its guard's check, and no suite runs any more, so `scripts\eval.ps1` may run. If `eval\baseline.json` does not parse (the `ConvertFrom-Json` above failed), first run `git checkout eval/baseline.json`.

Run: `foreach ($pair in @(@('R58N66RVD7Z', 'a71-2*'), @('emulator-5554', 'kestrel-api37-2*'))) { $folder = (Get-ChildItem eval-results -Directory -Filter $pair[1] | Sort-Object Name | Select-Object -Last 1).FullName; scripts\eval.ps1 suite --device $pair[0] --live --resume $folder --record-baseline }; Get-Content eval\baseline.json -Raw | ConvertFrom-Json | ForEach-Object { $_.devices.PSObject.Properties | ForEach-Object { "$($_.Name): commit=$($_.Value.commit) model=$($_.Value.model) tasks=$(@($_.Value.tasks.PSObject.Properties).Count)" } }`
Expected: for each folder `eval\baseline.json now holds the part of <device>, commit <HEAD>, model <id>.` and no run line, then both parts with commit HEAD and the model.

Run: `foreach ($f in 'a71-2*', 'kestrel-api37-2*') { scripts\eval.ps1 compare (Get-ChildItem eval-results -Directory -Filter $f | Sort-Object Name | Select-Object -Last 1).FullName }`
Expected: `compare=a71 result=pass rate=<x> baseline=<x> …` and `compare=kestrel-api37 result=pass …`, each with the same rate twice, because each folder is its part's own.

- [ ] **Step 6: Count what the detail lines say**

Run: `foreach ($f in 'a71-2*', 'kestrel-api37-2*') { $folder = (Get-ChildItem eval-results -Directory -Filter $f | Sort-Object Name | Select-Object -Last 1).FullName; $runs = @(Get-Content "$folder\runs.txt" | Where-Object { $_ -match "`ttask`ttask=" }); $d = @(Get-Content "$folder\details.txt"); "$f runs=$($runs.Count) pass=$(@($runs | Select-String 'result=pass').Count) asked=$(@($runs | Select-String 'reason=asked').Count) end=$(@($runs | Select-String 'reason=end').Count) check=$(@($runs | Select-String 'reason=check').Count) cancelledEnd=$(@($runs | Select-String 'end=CANCELLED.*reason=end').Count)"; "$f screenshotNo=$(@($d | Select-String 'screenshot:no').Count) numberOpen=$(@($d | Select-String 'pick-number:none').Count) permissionCancel=$(@($d | Select-String 'hand-over/PERMISSION:cancel').Count) continueNo=$(@($d | Select-String 'continue:no').Count) touched=$(@($d | Select-String 'ownerTouches=[1-9]').Count) signalled=$(@($d | Select-String 'signals=[1-9]').Count) putBack=$(@($d | Select-String 'putBack=(?!none)').Count) byHand=$(@($d | Select-String 'byHand=(?!none)').Count)" }`
Expected: two pairs of count lines, with counts only. `cancelledEnd` counts the CANCELLED runs that neither the run's own answer nor a refused confirmation explains; their detail lines' `ownerTouches=` and `signals=` are the evidence for B1. A `byHand=` above 0 names a display key that the A71 or kestrel-api37 may still show as the run left it: the controller checks Settings > Display and asks the owner before he uses the A71.

- [ ] **Step 7: Commit the baseline and note the results**

```powershell
git add eval/baseline.json
git commit -m "test(eval): both baselines again, with the owner's answers, the permission set and the fixed tasks (plan 6B-basefix)" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

Add to `.superpowers/sdd/progress.md`, under "## Plan 6B-basefix": each device's folder, commit, model, passes of runs, the untestable tasks and the known gaps, the AI Credits (with the unknown ones) and the hours; Step 6's counts; for each CANCELLED run with `reason=end`, its task, run and `ownerTouches=`/`signals=` values (B1's evidence: a run with `signals=` above 0 and no cancel of the run's own confirms B1 for that task); and each task whose pass count changed against the v1.0 baselines, with the plan's change that explains it.

---

## Counts at the end of plan 6B-basefix

- `:app` JVM (with `app/src/testDebug`): A₀ + 8 (Task 1 + 2, Task 2 + 1, Task 3 + 5).
- `:core:agent`: G₀ + 1 (Task 4).
- `:tools:eval`: E₀ + 6 (Task 5 + 1, Task 6 + 1, Task 7 + 2, Task 9 + 2).
- Pester, in each shell: P₀ (unchanged; `scripts/hook.ps1` changes only in its comments).
- Device tests: none new. Task 10 runs four scripted tasks and two rehearsals on kestrel-api37, and one scripted task and two rehearsals on the A71; Task 11 records both live baselines.

## Left open

- **B1, branch B's signals.** `OwnerSignal` stays as de4eeb7 left it. Task 11's detail lines give each CANCELLED run its `ownerTouches=` and `signals=`; a later plan decides between the A71 on branch A (a new sitting-1 probe) and `OwnerSignal`'s change of the analysis (with a max review), from that evidence.
- **B2.** A chooser of a link that Kestrel itself opened, and Google Maps' `api=1`, stay SENDING in test mode; B1' takes the chooser out of maps-place. A safety review decides it later.
- **The question's source beyond its `AnswerKind`.** The stuck question, the cost question and a routine's questions are all `question=yes-no`; naming them apart needs `:core:agent` to give a reason with each question, which no answer of the tool needs now.
- **`markerInData=` covers the data tools only.** A marker that the agent read from the screen, as read-email's from Gmail, shows only in `marker=` and `markerSaid=`.
- **The A71's `font_scale` of 1.1.** Each run's restore puts back what it read before, so 1.1 is most likely the owner's own value; only the owner can say. If it is not, the controller sets it back with his answer, by hand.
- **One UI's keys outside the pattern.** Task 10, Step 8 reads them by name; a key that One UI's size controls change without a name that holds `font`, `density` or `zoom` would still be missed.
- **The vendor's stored keys.** `KeepStoredSettings` writes One UI's font keys back but cannot apply them; a run that changed one leaves the screen as it was until the key's page sets it again, which the detail line's `byHand=` and a warning say. Applying them would need One UI's own interface, which no public command offers.
- **`run --live`.** Task 9 grants the permission set before a live series (`suite`, `injection`), not before a single live `run`; the single runs of the sittings keep their own `Grant` steps.
- **`details.txt` after a resume.** A resumed suite appends; a run that ran again has two detail lines, and the later one counts.

## Self-review

**The owner's hard rule (AGENTS.md: no workarounds, no smallest-change fixes).** Each concern has its own unit instead of a patch at the nearest line: the debug build's observations of a task are a collaborator of their own (`TaskWitness` in the main code, which a release build never fills, and `DebugTaskWitness` with `MarkerWatch` in the debug source set), not flag checks in release code; the run's answers are values (`Asked`) and the rule of which answer ends a task lives with the answers (`Answers.endsTask`); the detail line is a type beside the run line (`RunDetail`), and the settings that a restore put back are a record of their own (`PutBack`); each of font-size's three kinds of display control has the restore that Android applies (`SettingsValue`, `KeepDisplayDensity` with `wm density`, `KeepStoredSettings` with its warning for the hand); `linkApp` takes the apps that open the link and matches a name exactly against their package words and through the launcher's own matcher against their labels; and the permission set is one object (`EvalPermissions`) that the series uses.

**The writing-plans checklist.** Every task has its **Files**, its **Interfaces** (what it takes from earlier tasks and what later tasks use, with exact Kotlin signatures) and its counts as "N + k" with the running total against the counts measured before Task 1. Each code task writes its tests first, runs them to see the compiler's or the test's failure, gives each change as the exact old and new text or the whole new file, runs the module's tests and `scripts\gradle.ps1 --stop`, and commits with the Co-authored-by line. Each script change runs `scripts\test-scripts.ps1`. Every device step on kestrel-api37 checks the emulator's name in the same command and counts the accounts and reads `provider-standin` around its changes; every A71 step that changes anything runs through the controller's launcher, which checks the device, unlocks it and leaves it locked with "Stay awake" at 0; the read-only A71 step starts with `Assert-TestDevice`.

**The analysis's section 4 and the owner's decisions.** T1 is Task 5 (every answer recorded, `reason=asked` with `asked=<kinds>` in the run line, the warning) with Task 6's detail line; T2 is Task 2 (`ownerTouches=`, `signals=`); T3 is Task 3 (the debug-only witness: the `say` texts, `markerSaid=`, `markerInData=`); T4 is Task 3, Step 5; T5 is Task 5 (`Verdicts.judge`); T6 is Task 7, with Task 10, Step 8's read-only discovery of One UI's keys; T7 is Task 8; B1' is Task 4, reviewed at max; P1 is Task 1 (Kestrel's `question=`) and Task 5 (the tool's answers). The controller's evidence of 15:05 (READ_CALENDAR's hand-over cancelled) and the owner's permission decision are Task 9, before the baselines, and Task 5 names such a hand-over in the run line. B1 and B2 are in "Left open"; `OwnerSignal` and the system prompt do not change. The incident that wiped Kestrel's data on kestrel-api37 is Task 10, Steps 1 and 2 (the owner's sign-in with `gpt-6-luna`, then the profile, the touch rule, notification access, the permissions outside Task 9's set, the models with look_closer's OCR files, listening on the silent clip, the accessibility service and the setup checklist), before any live run there; Task 11, Step 3 stops when the two suites run different models; the plan runs no `:app` device test. Both baselines are recorded again in Task 11 with the TEMP-copy launchers, and Task 11, Step 1 forbids installing the tool while they run.

**Placeholder scan.** No step says "TBD", "TODO", "similar to Task N" or "add error handling". Angle brackets mark only values that a device step prints (`<HEAD>`, `<time>`, `<folder>`, `<serial>`, `<the device's launcher>`) or the format of a line.

**Type and name consistency.** `TaskOwner.asking` and `AgentRunner.asking` (Task 1) feed `AgentHookAnswers.question`; `AgentRunner.Ended` (Task 2, with `touches` and `signals`) gains `evidence: TaskEvidence` in Task 3, whose `result` keeps Task 2's signature; `AgentRunner.witnesses`, `TaskStart.witness`, `AppOwnerPort`'s `witness` and `DebugTaskWitness.open`/`MARKER` (Task 3) match `VoiceHooks.taskOptions`' key and Task 6's `Hooks.voiceHeard(…, marker)`; `Answers.kind`, `Answers.PICK_NUMBER`, `Answers.endsTask`, `Asked`, `RunState.asked` and `RunState.cancelled()` (Task 5) are what Task 6's `RunDetail` reads; `PutBack` and `RunScope.putBack` (Task 6) are what Task 7's `KeepDisplayDensity` and `KeepStoredSettings` fill; `ScreenSteps.packageWords` (Task 4) is what Task 8's script relies on through `linkApp`; `EvalPermissions.SET`, `EvalPermissions.grant` and `SuiteCall.permissions` (Task 9) are what Task 11, Step 5 reads.

**Privacy.** The hooks answer counts, flags and kinds; the `say` texts stay in the memory of a debug build's `DebugTaskWitness` only, and are compared on the phone; a release build's tasks have `TaskWitness.NONE`, which keeps nothing; `MarkerWatch` keeps one flag; the detail lines and `suite.json` hold kinds, counts, flags, setting names and permission states; Task 10, Step 6 prints display settings' values and other keys' names only.

**The re-review of 2026-11-24.** I2 is Task 10, Step 2's permissions, `push-models.ps1 -Kokoro`, the listening check and the setup checklist; M7 is the emulator check in each of Step 2's changing commands; M10 is the listening check's `voice off` and its wait for `mode=off` before the clip and the toggle; M8 is Step 1's `gpt-6-luna` and Task 11, Step 3's comparison of the models; M9 removed Task 4's replacement that changed nothing.

**The review of 2026-11-24.** I1 is Task 7's `KeepDisplayDensity` (applied with `wm density`, compared with its output, a fake that tells the applied density from the stored key) and Task 10, Step 8's check for a left-over override; M1 is Task 3's `TaskWitness` seam with the debug-only `DebugTaskWitness`; M2 is Task 5's `Answers.endsTask` (`continue:no`) and its record of delivered answers only; M3 is Task 11, Step 5's second recording of both parts; M4 is Task 11, Step 4's resume only after a fix to the tool; M5 is Task 10, Step 7's removal of left-over test events; M6 is Task 4's exact match of package words.

**The checker.** `files\plan3x-check.py` ran on this plan with the base that the controller's report names; its result is in that report.
