package dev.kestrel.eval.run

import dev.kestrel.eval.config.LocalConfig
import dev.kestrel.eval.config.Redactions
import dev.kestrel.eval.device.DeviceChanged
import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.FakeAdb
import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.task.Answers
import dev.kestrel.eval.task.AtLimit
import dev.kestrel.eval.task.Check
import dev.kestrel.eval.task.FakeRun
import dev.kestrel.eval.task.Rehearsal
import dev.kestrel.eval.task.Request
import dev.kestrel.eval.task.Turn
import dev.kestrel.eval.task.DryRun
import dev.kestrel.eval.task.FakeConversation
import dev.kestrel.eval.task.ProcessSetup
import dev.kestrel.eval.task.TurnAction
import dev.kestrel.eval.task.TurnChannel
import dev.kestrel.eval.task.Turns
import dev.kestrel.eval.task.Until
import dev.kestrel.eval.task.RunScope
import dev.kestrel.eval.task.Setup
import dev.kestrel.eval.task.SetupFailed
import dev.kestrel.eval.task.SetupResult
import dev.kestrel.eval.task.Task
import dev.kestrel.eval.task.TaskKind
import dev.kestrel.eval.task.VerdictRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The runner against a scripted device (design D5 and D18 of sub-project 6). */
class RunnerTest {
    private val run = FakeRun()
    private val writer = LineWriter(run.console, null)
    private var tagNumber = 0
    private var listening: () -> Unit = {}
    private val done = "state=done end=FINISHED steps=3 workMs=9000 credits=0.00 confirmed=0 unconfirmed=0 shareItems=0 beforeTestMode=0 " +
        "model=scripted in=10 out=2 cacheRead=0 cacheWrite=0"

    private fun runner(
        live: Boolean = false,
        restores: Restores = run.restores,
        conversations: ConversationGuard = ConversationGuard(run.hooks, run.clock),
    ) = Runner(
        run.device, run.hooks, restores, run.clock, run.console, run.scope.config, run.scope.work, writer, RunOptions(live),
        { listening() }, conversations, Markers(Random(1)), { "t${++tagNumber}" },
    )

    private fun task(
        rule: VerdictRule = VerdictRule.Finished,
        answers: Answers = Answers.NONE,
        setup: List<Setup> = emptyList(),
        checked: Boolean = true,
        kind: TaskKind = TaskKind.STAND_IN,
        runsAtMost: Int? = null,
    ) = Task(
        "stand-in-hotspot", kind, Turns.one("turn on hotspot"), setOf(DeviceKind.EMULATOR), answers, rule, setup,
        listOf(object : Check {
            override fun passes(scope: RunScope, answer: HookAnswer) = checked
        }),
        null, Rehearsal.SetupOnly("a test"), runsAtMost, if (runsAtMost != null) "v1.1" else null,
    )

    init {
        run.adb.hook("AGENT_SCRIPT") { extras -> if (extras["clear"] == "true") "script=none" else "script=installed replies=2" }
        run.adb.hook("VOICE") { "sent" }
        run.adb.hook("AGENT_ANSWER") { "answered" }
        run.adb.hook("AGENT_STATE") { "phase=running asking=none work=running-task handOver=none confirming=none" }
    }

    /**
     * Kestrel's conversation for a run whose command goes on until the run stops it: the command opens the hooks' own
     * conversation, whose goal acts until goal-control do=stop-typed stops it, which runs [stopped]; expire-typed then
     * closes it, and delete-typed deletes it.
     */
    private fun goalUntilStopped(stopped: () -> Unit = {}) {
        var open = false
        var goal = "none"
        run.adb.hook("VOICE") { extras ->
            if (extras["text"].orEmpty().startsWith("Kestrel, ")) {
                open = true
                goal = "acting"
            }
            "sent"
        }
        run.adb.hook("CONVERSATION_STATE") {
            "open=${if (open) "yes" else "no"} continued=no commands=1 goal=$goal quiet=${if (goal == "none") "running" else "held"} quietLeft=none " +
                "replyChannel=voice testMode=on typed=${if (open) "yes" else "no"}"
        }
        run.adb.hook("GOAL_CONTROL") { extras ->
            if (extras["do"] == "stop-typed" && open && goal != "none") {
                goal = "none"
                stopped()
                "stop=sent"
            } else {
                "stop=none"
            }
        }
        run.adb.hook("CONVERSATION_CONTROL") { extras ->
            if (extras["do"] == "expire-typed" && open && goal == "none") open = false
            if (extras["do"] == "delete-typed") "deleted=1 open=0 failed=0" else "expire=sent"
        }
    }

    // Design 14.1 and 6.4 of sub-project 7: the run's command opens a conversation of its own, a goal that the run left
    // held is stopped, so that the next run's command does not resume it, and the run's conversation is closed and deleted
    // after it (plan 7F's delete-typed, which covers every input that a hook gives).
    @Test
    fun aRunClosesTheOpenConversationFirstStopsAGoalThatItLeftHeldAndDeletesItsOwnConversation() {
        run.adb.hook("AGENT_TASK") { done }
        var open = true
        var goal = "none"
        run.adb.hook("CONVERSATION_STATE") {
            // Both conversations, the earlier run's and this one's, are the hooks' own.
            "open=${if (open) "yes" else "no"} continued=no commands=1 goal=$goal quiet=${if (goal == "none") "running" else "held"} quietLeft=none replyChannel=voice testMode=on " +
                "typed=${if (open) "yes" else "no"}"
        }
        run.adb.hook("CONVERSATION_CONTROL") { extras ->
            if (extras["do"] == "expire-typed") {
                open = false
                "expire=sent"
            } else {
                "deleted=1 open=0 failed=0"
            }
        }
        run.adb.hook("VOICE") { extras ->
            if (extras["text"] == "Kestrel, turn on hotspot") {
                open = true
                goal = "paused-at-limit"
            }
            "sent"
        }
        run.adb.hook("GOAL_CONTROL") { extras ->
            if (extras["do"] == "stop-typed" && open && goal != "none") {
                goal = "none"
                "stop=sent"
            } else {
                "stop=none"
            }
        }

        val line = runner().run(task(), "[]", 1, "maple heron")

        assertEquals(Reason.OK, line.reason)
        assertEquals(
            listOf(mapOf("do" to "expire-typed"), mapOf("do" to "expire-typed"), mapOf("do" to "delete-typed")),
            run.adb.hookCalls("CONVERSATION_CONTROL"),
        )
        assertEquals(listOf("Kestrel, turn on hotspot"), run.adb.hookCalls("VOICE").map { it["text"] })
        assertEquals(listOf(mapOf("do" to "stop-typed")), run.adb.hookCalls("GOAL_CONTROL"))
        assertEquals("none", goal)
        assertFalse(open)
    }

    // One rule for every stop (plan 7G): a run's restore stops only the goal of the hooks' own conversation. When the
    // owner's words reached the run's conversation, it carries no mark, and its goal, his now, goes on; the console says so.
    @Test
    fun aRunsRestoreLeavesTheGoalOfAConversationThatTheOwnersWordsReached() {
        run.adb.hook("AGENT_TASK") { done }
        var open = false
        run.adb.hook("CONVERSATION_STATE") {
            "open=${if (open) "yes" else "no"} continued=no commands=2 goal=${if (open) "acting" else "none"} quiet=held quietLeft=none replyChannel=voice " +
                "testMode=on typed=no"
        }
        // The run's command opens the hooks' conversation, and the owner's words reach it before the run ends, so it has no mark.
        run.adb.hook("VOICE") { extras ->
            if (extras["text"] == "Kestrel, turn on hotspot") open = true
            "sent"
        }

        val line = runner().run(task(), "[]", 1, "maple heron")

        assertEquals(Reason.OK, line.reason)
        assertTrue(run.adb.hookCalls("GOAL_CONTROL").isEmpty())
        assertTrue(run.adb.hookCalls("CONVERSATION_CONTROL").none { it["do"]!!.startsWith("expire") })
        assertTrue(open)
        assertTrue(
            run.console.warnings.contains(
                "Task stand-in-hotspot, run 1: the goal or request that runs is the owner's, which the evaluation never stops; say 'Kestrel, stop' to end it.",
            ),
        )
    }

    // Plan 7G: the run's restore stops Kestrel's own request too, by the same rule. The run's command continues a past
    // conversation from the quiet, a switch that asks before it moves there and runs while no conversation is open, and the
    // device stops answering the run's reads while the switch's question is open, so the run stalls. The restore stops the
    // hooks' own request, which takes its question away, so nothing stays open, and the next run's setup passes.
    @Test
    fun aRunThatStallsAtASwitchsQuestionLeavesNothingOpenAndTheNextRunsSetupPasses() {
        val kestrel = FakeConversation(run.adb)
        kestrel.planned += FakeConversation.Command(continues = true, asks = listOf("asking=yes-no confirming=none question=switch"))
        var stalling = true
        kestrel.onInput = { if (it == "stop") stalling = false }
        run.adb.slow(run.clock, 2.minutes) { stalling && ("--method AGENT_TASK " in it || "--method AGENT_STATE " in it) }
        val switch = task(answers = Answers(switches = true)).copy(turns = listOf(Turn.Say(Request("continue the conversation about my latest SMS"))))

        val failures = runner().runAll(switch, "[]", 2)

        assertEquals(1, failures)
        assertEquals(listOf("1 fail stalled", "2 pass ok"), writer.lines.map { "${it.run} ${it.result.wire} ${it.reason.wire}" })
        assertEquals(
            listOf("spoken:continue the conversation about my latest SMS", "stop", "spoken:continue the conversation about my latest SMS"),
            kestrel.inputs,
        )
        assertEquals(listOf(mapOf("do" to "stop-typed")), run.adb.hookCalls("GOAL_CONTROL"))
        assertTrue(kestrel.answers.isEmpty())
        assertFalse(kestrel.open)
    }

    // The task's setup decides whether the device can run it before the run's conversation is closed, so that a run that
    // it finds untestable gives Kestrel nothing at all, and its restores have nothing of the conversation's to put back.
    @Test
    fun theTasksSetupComesBeforeTheRunsConversationAndAnUntestableRunTouchesNoConversation() {
        run.adb.hook("AGENT_TASK") { done }
        val order = mutableListOf<String>()
        run.adb.hook("CONVERSATION_STATE") {
            order += "conversation"
            FakeAdb.NO_CONVERSATION
        }
        val ready = object : Setup {
            override fun apply(scope: RunScope): SetupResult {
                order += "setup"
                scope.restores.push("the fixture", "") { order += "fixture back" }
                return SetupResult.Ready
            }
        }
        val untestable = object : Setup {
            override fun apply(scope: RunScope) = SetupResult.Untestable("the test says so")
        }

        assertEquals(Reason.UNTESTABLE, runner().run(task(setup = listOf(ready, untestable)), "[]", 1, "maple heron").reason)

        assertEquals(listOf("setup", "fixture back"), order)
        assertTrue(run.adb.commands.toString(), run.adb.commands.none { it.startsWith("am ") })
        assertEquals(0, run.restores.size)

        order.clear()
        assertEquals(Reason.OK, runner().run(task(setup = listOf(ready)), "[]", 2, "maple heron").reason)
        assertEquals("setup", order.first())
        assertEquals("conversation", order[1])
        assertEquals("fixture back", order.last())
    }

    // The turns' follow loop reads through the wait's deadline (plan 7G), the earlier of the command's 10 minutes and the
    // stall rule's 30 s since the hooks' last answer, so no read outlives either. A device whose agent-task never answers
    // stalls the run at 30 s: the first read, 2 s after the command, gets only the 28 s left, not adb's 60 s.
    @Test
    fun theFollowLoopsReadEndsAtTheStallRulesEnd() {
        var first = -1
        run.adb.hook("VOICE") {
            first = run.adb.timeouts.size
            run.adb.slow(run.clock, 2.minutes) { "--method AGENT_TASK " in it }
            "sent"
        }

        assertEquals(Reason.STALLED, runner().run(task(), "[]", 1, "maple heron").reason)

        assertEquals(28.seconds, run.adb.timeouts[first])
    }

    // On a device whose every read of the command takes 7 s, the run polls in rounds of 16 s (a 2 s pause and two reads);
    // the round that starts at 592 s gets only the 6 s left of the 10 minutes for its read, so the run times out at 600 s.
    @Test
    fun theFollowLoopsReadEndsAtTheCommandsLimitOnASlowDevice() {
        run.adb.hook("VOICE") {
            run.adb.slow(run.clock, 7.seconds) { "--method AGENT_TASK " in it || "--method AGENT_STATE " in it }
            "sent"
        }
        run.adb.hook("AGENT_TASK") { "state=running" }

        assertEquals(Reason.TIMEOUT, runner().run(task(), "[]", 1, "maple heron").reason)

        assertTrue(run.adb.timeouts.toString(), 6.seconds in run.adb.timeouts)
    }

    // Design 14.4 of sub-project 7: each command has its tag, and the run's one line holds their sums.
    @Test
    fun aConversationOfTwoCommandsGivesEachItsTagAndWritesOneLineOfTheirSums() {
        val ends = mapOf(
            "t1" to "state=done end=FINISHED steps=3 workMs=9000 credits=0.20 confirmed=0 unconfirmed=0 shareItems=0 beforeTestMode=0 model=scripted in=10 out=2 cacheRead=0 cacheWrite=0",
            "t2" to "state=done end=FINISHED steps=2 workMs=1000 credits=0.10 confirmed=1 unconfirmed=0 shareItems=0 beforeTestMode=0 model=scripted in=5 out=1 cacheRead=4 cacheWrite=0",
        )
        run.adb.hook("AGENT_TASK") { extras -> if (extras["marker"] != null) "state=done marker=1" else ends.getValue(extras.getValue("tag")) }
        val conversation = task().copy(turns = listOf(Turn.Say(Request("turn on dark mode")), Turn.Say(Request("set the screen timeout to 2 minutes"))))

        val line = runner().run(conversation, "[]", 1, "maple heron")

        assertEquals(
            listOf("Kestrel, turn on dark mode" to "t1", "Kestrel, set the screen timeout to 2 minutes" to "t2"),
            run.adb.hookCalls("VOICE").map { it["text"] to it["tag"] },
        )
        assertEquals(
            "task=stand-in-hotspot run=1 result=pass end=FINISHED check=pass steps=5 workMs=10000 credits=0.30 model=scripted in=15 out=3 cacheRead=4 cacheWrite=0 reason=ok",
            line.text(),
        )
        assertEquals("t2", run.adb.hookCalls("AGENT_TASK").last { it["marker"] != null }["tag"])
    }

    // Design 6.6 of sub-project 7: the second command is spoken without the name once the first one's reply opened the window.
    @Test
    fun aFollowUpWaitsForTheWindowAndIsSpokenWithoutTheName() {
        val kestrel = FakeConversation(run.adb)
        val conversation = task().copy(
            turns = listOf(Turn.Say(Request("turn on dark mode")), Turn.Say(Request("and set the screen timeout to 2 minutes"), TurnChannel.FOLLOW_UP)),
        )

        val line = runner().run(conversation, "[]", 1, "maple heron")

        assertEquals(Reason.OK, line.reason)
        assertEquals(listOf("spoken:turn on dark mode", "spoken:and set the screen timeout to 2 minutes"), kestrel.inputs)
        assertEquals("and set the screen timeout to 2 minutes", run.adb.hookCalls("VOICE")[1]["text"])
        // The run's last restore closed its conversation, which held both commands.
        assertEquals(2, kestrel.closed)
    }

    // Design 10.3, 11 and 12.3 of sub-project 7: a typed command's questions are answered on the text channel's card, and a
    // confirmation only on the card over the app, as the owner would (plan 7F's Task 13a).
    @Test
    fun aTypedCommandsQuestionGoesToTheTextChannelAndItsConfirmationToTheCardOverTheApp() {
        val kestrel = FakeConversation(run.adb)
        kestrel.planned += FakeConversation.Command(asks = listOf("asking=yes-no confirming=none question=none", "asking=confirm confirming=sending question=none"))
        val typed = task(answers = Answers(confirm = setOf("sending"))).copy(turns = listOf(Turn.Say(Request("send it to me"), TurnChannel.TYPED)))

        assertEquals(Reason.OK, runner().run(typed, "[]", 1, "maple heron").reason)

        assertEquals(listOf("typed:send it to me"), kestrel.inputs)
        assertEquals("t1", run.adb.hookCalls("CONVERSATION_TYPE").single()["tag"])
        assertEquals(listOf("no/text", "confirm/card"), kestrel.answers)
        assertEquals(listOf("text", "card"), run.adb.hookCalls("AGENT_ANSWER").map { it["channel"] })
    }

    // Design 7.3 of sub-project 7: the run steers the goal while it acts, and then follows the steered command to its end.
    @Test
    fun aSteerReachesTheGoalWhileItActsAndTheRunAwaitsTheSteeredCommandsEnd() {
        val kestrel = FakeConversation(run.adb)
        kestrel.planned += FakeConversation.Command(until = FakeConversation.Until.STEERED)
        val steered = task().copy(
            turns = listOf(Turn.Say(Request("set an alarm for 7:00"), until = Until.ACTING), Turn.Steer(Request("make it 7:30 instead")), Turn.AwaitEnd()),
        )

        assertEquals(Reason.OK, runner().run(steered, "[]", 1, "maple heron").reason)

        assertEquals(listOf("spoken:set an alarm for 7:00", "steered:make it 7:30 instead"), kestrel.inputs)
        assertEquals("Kestrel, make it 7:30 instead", run.adb.hookCalls("VOICE")[1]["text"])
        // The run's last restore closed its conversation, which held the one steered command.
        assertEquals(1, kestrel.closed)
    }

    // Design 6.7 and 15.3 of sub-project 7: the command that ran is lost with Kestrel's process, and every setup step that
    // held state in the process is applied again, the scripted model with the dry run's next script.
    @Test
    fun aRestartLosesTheCommandThatRanAndAppliesTheProcessStepsAgain() {
        val kestrel = FakeConversation(run.adb)
        kestrel.planned += FakeConversation.Command(until = FakeConversation.Until.CRASHED)
        var listened = 0
        listening = { listened++ }
        var renewed = 0
        val held = object : ProcessSetup {
            override fun apply(scope: RunScope) = SetupResult.Ready

            override fun renew(scope: RunScope) {
                renewed++
            }
        }
        val restarted = task(setup = listOf(held)).copy(
            turns = listOf(Turn.Say(Request("send it"), until = Until.ACTING), Turn.Restart, Turn.Say(Request("continue"))),
            dryRun = DryRun("timer.json", restarts = listOf("wifi-settings.json")),
        )
        // The scripts as they reach the device, in their order.
        val pushed = mutableListOf<String>()
        run.adb.hostOut = { args ->
            if (args.first() == "push") pushed += java.nio.file.Files.readString(java.nio.file.Paths.get(args[1]))
            ""
        }

        val line = runner().run(restarted, "[]", 1, "maple heron")

        assertEquals(Reason.OK, line.reason)
        assertEquals("FINISHED", line.answer?.get("end"))
        assertEquals(4101, kestrel.pid)
        assertEquals(1, listened)
        assertEquals(1, renewed)
        assertEquals(listOf(mapOf("file" to "task.json"), mapOf("file" to "task.json"), mapOf("clear" to "true")), run.adb.hookCalls("AGENT_SCRIPT"))
        // The run's script first, and after the restart the dry run's next one, not the first again.
        assertEquals(listOf("[]", ScriptInstaller.resource("wifi-settings.json")), pushed)
        assertEquals(mapOf("text" to KestrelRestart.CRASH_TEXT), run.adb.hookCalls("CRASH").single())
    }

    // Ctrl+C during a restart's wait runs the restores, among them the scripted model's removal; the restart's install of
    // the next script must then not start, or Kestrel would keep a scripted model after the run.
    @Test
    fun noScriptedModelIsInstalledOnceCtrlCBeganTheRestores() {
        val task = task().copy(dryRun = DryRun("timer.json", restarts = listOf("wifi-settings.json")))
        val setup = ScriptedModelSetup(ScriptInstaller(run.device, run.hooks, run.scope.work), task, "[]")
        setup.apply(run.scope)

        run.ctrlC().join()

        assertThrows(Stopping::class.java) { setup.renew(run.scope) }
        assertEquals(listOf(mapOf("file" to "task.json"), mapOf("clear" to "true")), run.adb.hookCalls("AGENT_SCRIPT"))
    }

    // Design 7.3 of sub-project 7: a command that the run follows until its goal acts, and that ends before the run saw its
    // goal act, leaves the steering turns nothing to steer: the run fails, and its steering words never reach Kestrel,
    // where they would start a new command that the run never follows.
    @Test
    fun aCommandThatEndsBeforeItsGoalActedFailsTheRunAndNothingIsSteered() {
        val kestrel = FakeConversation(run.adb)
        kestrel.planned += FakeConversation.Command()
        val steered = task().copy(
            turns = listOf(Turn.Say(Request("set an alarm for 7:00"), until = Until.ACTING), Turn.Steer(Request("make it 7:30 instead")), Turn.AwaitEnd()),
        )

        assertEquals(Reason.CHECK, runner().run(steered, "[]", 1, "maple heron").reason)

        assertEquals(listOf("spoken:set an alarm for 7:00"), kestrel.inputs)
        assertEquals(listOf("Kestrel, set an alarm for 7:00"), run.adb.hookCalls("VOICE").map { it["text"] })
        assertTrue(
            run.console.warnings.toString(),
            run.console.warnings.contains(
                "Task stand-in-hotspot, run 1: the command ended before the run saw its goal act, so the turns after it would steer, await or restart no goal.",
            ),
        )
    }

    // Design 7.4 of sub-project 7, as limit-continue asks: the run says yes to the round's first "Continue?" and leaves the
    // second to Kestrel's own wait, because the run's answers count the limits before the open one (RunState.before).
    @Test
    fun theRunCountsTheLimitsBeforeTheOpenOneAndLeavesTheSecondOpen() {
        val kestrel = FakeConversation(run.adb)
        val limit = "asking=yes-no confirming=none question=limit"
        kestrel.planned += FakeConversation.Command(end = FakeConversation.line("PAUSED_AT_LIMIT"), asks = listOf(limit, limit))
        val limits = task(answers = Answers(atLimits = listOf(AtLimit.YES, AtLimit.SILENCE))).copy(
            turns = listOf(Turn.Say(Request("turn on hotspot"), ends = "PAUSED_AT_LIMIT")),
        )

        runner().run(limits, "[]", 1, "maple heron")

        assertEquals(listOf("yes"), kestrel.answers)
        assertTrue(run.console.lines.toString(), run.console.lines.any { it.startsWith("detail task=stand-in-hotspot run=1 asked=limit:yes,limit:none ") })
    }

    @Test
    fun aCheckBetweenTurnsThatDoesNotHoldFailsTheRunWithCheckAndAStepThatFailsWithSetup() {
        FakeConversation(run.adb)
        val never = object : Check {
            override fun passes(scope: RunScope, answer: HookAnswer) = false

            override fun toString() = "a check that never holds"
        }
        val unmet = task().copy(turns = listOf(Turn.Say(Request("turn on hotspot")), Turn.Expect(never, within = 10.seconds)))

        assertEquals(Reason.CHECK, runner().run(unmet, "[]", 1, "maple heron").reason)
        assertTrue(run.console.warnings.contains("Task stand-in-hotspot, run 1: turn 2, a check that never holds, did not hold within 10 s."))

        val failing = object : TurnAction {
            override fun run(scope: RunScope) = error("no window")

            override fun toString() = "a step that fails"
        }
        val failed = runner().run(task().copy(turns = listOf(Turn.Say(Request("turn on hotspot")), Turn.Act(failing))), "[]", 2, "cedar otter")
        assertEquals(Reason.SETUP, failed.reason)
        assertTrue(run.console.warnings.contains("Task stand-in-hotspot, run 2: turn 2, a step that fails, failed (no window); the run fails, and the next run goes on."))
    }

    @Test
    fun aRunThatFinishesWithItsCheckPassesAndPutsTheDeviceBack() {
        var polls = 0
        run.adb.hook("AGENT_TASK") { if (++polls < 3) "state=running" else done }
        val restored = mutableListOf<String>()
        val setup = object : Setup {
            override fun apply(scope: RunScope): SetupResult {
                scope.restores.push("the fixture", "") { restored.add("fixture") }
                return SetupResult.Ready
            }
        }

        val line = runner().run(task(setup = listOf(setup)), "[{\"tool\": \"finish\", \"arguments\": {\"summary\": \"{marker}\"}}]", 1, "maple heron")

        assertEquals(
            "task=stand-in-hotspot run=1 result=pass end=FINISHED check=pass steps=3 workMs=9000 credits=0.00 model=scripted in=10 out=2 cacheRead=0 cacheWrite=0 reason=ok",
            line.text(),
        )
        assertEquals(
            listOf(line.text(), "detail task=stand-in-hotspot run=1 asked=none ownerTouches=none signals=none markerSaid=none markerInData=none putBack=none byHand=none leftChanged=none"),
            run.console.lines,
        )
        assertEquals(listOf(mapOf("event" to "heard", "text" to "Kestrel, turn on hotspot", "tag" to "t1", "marker" to "maple heron")), run.adb.hookCalls("VOICE"))
        assertEquals(listOf(mapOf("file" to "task.json"), mapOf("clear" to "true")), run.adb.hookCalls("AGENT_SCRIPT"))
        assertEquals("[{\"tool\": \"finish\", \"arguments\": {\"summary\": \"maple heron\"}}]", java.nio.file.Files.readString(run.scope.work.resolve("task.json")))
        assertEquals(listOf("fixture"), restored)
    }

    @Test
    fun confirmsTheTasksCategoriesDeclinesItsOwnAndAnyOtherConfirmationFailsTheRun() {
        val questions = ArrayDeque(listOf("confirming=sending", "confirming=deleting"))
        run.adb.hook("AGENT_STATE") { "phase=running asking=confirm work=running-task handOver=none ${questions.removeFirstOrNull() ?: "confirming=none"}" }
        var polls = 0
        run.adb.hook("AGENT_TASK") { if (++polls < 4) "state=running" else done }

        val line = runner().run(task(rule = VerdictRule.Declined("deleting"), answers = Answers(confirm = setOf("sending"), decline = setOf("deleting"))), "[]", 1, "maple heron")

        assertEquals(RunResult.PASS, line.result)
        assertEquals(
            listOf(mapOf("tag" to "t1", "answer" to "confirm", "confirming" to "sending"), mapOf("tag" to "t1", "answer" to "no", "confirming" to "deleting")),
            run.adb.hookCalls("AGENT_ANSWER"),
        )

        questions.add("confirming=calls")
        polls = 0
        val cancelled = runner().run(task(answers = Answers(confirm = setOf("sending"))), "[]", 2, "cedar otter")
        assertEquals("cancel", run.adb.hookCalls("AGENT_ANSWER").last()["answer"])
        assertEquals(Reason.CONFIRM, cancelled.reason)
        assertEquals(RunResult.FAIL, cancelled.result)
    }

    @Test
    fun aTaskThatRunsTenMinutesIsStoppedAndTimesOut() {
        var stopped = false
        goalUntilStopped { stopped = true }
        run.adb.hook("AGENT_TASK") { if (stopped) "state=done end=STOPPED steps=40 workMs=600000 credits=0.00" else "state=running" }

        val line = runner().run(task(), "[]", 1, "maple heron")

        assertEquals(Reason.TIMEOUT, line.reason)
        assertEquals("none", line.text().substringAfter("end=").substringBefore(' '))
        assertTrue(stopped)
        assertTrue(run.clock.time >= 10.minutes)
    }

    @Test
    fun hooksThatStaySilentFor30SecondsStallTheRun() {
        run.adb.hookFails("AGENT_TASK", "the command failed: IllegalStateException")

        val line = runner().run(task(), "[]", 1, "maple heron")

        assertEquals(Reason.STALLED, line.reason)
        assertTrue(run.console.warnings.any { it.contains("did not answer for 30 s") })
    }

    @Test
    fun aCallBeforeTheRunFailsItWithoutATaskAndACallAfterItFailsItToo() {
        var calls = 1
        run.adb.on("^dumpsys telephony.registry$") { if (calls > 0) "  mCallState=2" else "  mCallState=0" }
        run.adb.on("^input keyevent KEYCODE_ENDCALL$") { calls--; "" }
        run.adb.hook("AGENT_TASK") { done }

        val before = runner().run(task(), "[]", 1, "maple heron")

        assertEquals(Reason.CALL, before.reason)
        assertEquals(RunResult.FAIL, before.result)
        assertTrue(run.adb.hookCalls("VOICE").isEmpty())

        var polls = 0
        run.adb.hook("AGENT_TASK") { if (++polls == 1) { calls = 1; "state=running" } else done }
        val after = runner().run(task(), "[]", 2, "cedar otter")
        assertEquals(Reason.CALL, after.reason)
        assertEquals("FINISHED", after.answer?.get("end"))
    }

    @Test
    fun aFailedSetupFailsOnlyItsRunAfterItsRestoresAndTheNextRunGoesOn() {
        run.adb.hook("AGENT_TASK") { done }
        var setups = 0
        val restored = mutableListOf<Int>()
        val setup = object : Setup {
            override fun apply(scope: RunScope): SetupResult {
                val number = ++setups
                scope.restores.push("step $number", "") { restored.add(number) }
                if (number == 1) throw SetupFailed("Could not run 'am start -d mailto:pera.test@example.com'")
                return SetupResult.Ready
            }
        }

        val failures = runner().runAll(task(setup = listOf(setup)), "[]", 2)

        assertEquals(1, failures)
        assertEquals(listOf(Reason.SETUP, Reason.OK), writer.lines.map { it.reason })
        assertEquals(listOf(1, 2), restored)
        assertTrue(run.console.warnings.single().startsWith("Task stand-in-hotspot, run 1: the setup failed ("))
    }

    // Plan 7G: the scripted model's install is the setup's last step, so a script that Kestrel refuses fails only its run
    // (reason=setup), after its restores, and the next run goes on.
    @Test
    fun aScriptThatKestrelRefusesFailsOnlyItsRunAsAFailedSetup() {
        run.adb.hook("AGENT_TASK") { done }
        var installs = 0
        run.adb.hookWith("AGENT_SCRIPT") { extras ->
            when {
                extras["clear"] == "true" -> FakeAdb.ANSWERED to "script=none"
                ++installs == 1 -> 1 to "error: the script is refused (InvalidScript): Reply 2 of the script has keys that the scripted model does not know: require."
                else -> FakeAdb.ANSWERED to "script=installed replies=2"
            }
        }

        val failures = runner().runAll(task(), "[]", 2)

        assertEquals(1, failures)
        assertEquals(listOf("1 fail setup", "2 pass ok"), writer.lines.map { "${it.run} ${it.result.wire} ${it.reason.wire}" })
        assertEquals(listOf("Kestrel, turn on hotspot"), run.adb.hookCalls("VOICE").map { it["text"] })
        assertTrue(run.console.warnings.single().startsWith("Task stand-in-hotspot, run 1: the setup failed (error: the script is refused (InvalidScript): Reply 2"))
    }

    // Plan 7G: the owner's conversation, open past its quiet time before a run, stops the runs (OwnerBusy). The run under way
    // and the runs after it did not run: no failure of the task, and no input or close reached his conversation.
    @Test
    fun theOwnersOpenConversationStopsTheRunsWhichDidNotRun() {
        run.adb.hook("CONVERSATION_STATE") { "open=yes continued=no commands=1 goal=none quiet=running quietLeft=10 replyChannel=voice testMode=on typed=no" }
        val start = run.clock.time

        assertThrows(OwnerBusy::class.java) { runner().runAll(task(), "[]", 3) }

        assertEquals(listOf("1 skipped owner-busy", "2 skipped owner-busy", "3 skipped owner-busy"), writer.lines.map { "${it.run} ${it.result.wire} ${it.reason.wire}" })
        assertTrue(run.clock.time - start >= 10.seconds + ConversationGuard.OWNER_MARGIN - 2.seconds)
        assertTrue(run.adb.hookCalls("VOICE").isEmpty())
        assertTrue(run.adb.hookCalls("AGENT_SCRIPT").isEmpty())
        assertTrue(run.adb.hookCalls("CONVERSATION_CONTROL").none { it["do"]!!.startsWith("expire") })
        assertTrue(run.console.warnings.none { it.contains("the setup failed") })
    }

    @Test
    fun aStopDuringTheSetupIsNoSetupFailureAndEndsTheRunsWithoutATask() {
        run.adb.hook("AGENT_TASK") { done }
        val shutdown = mutableListOf<Thread>()
        val restores = Restores(run.console, Redactions(LocalConfig.EMPTY)) { shutdown.add(it) }
        val setup = object : Setup {
            override fun apply(scope: RunScope): SetupResult {
                // Ctrl+C: the shutdown hook runs the restores just before the step's change.
                shutdown.single().run()
                scope.restores.push("the fixture", "") {}
                return SetupResult.Ready
            }
        }

        assertThrows(Stopping::class.java) { runner(restores = restores).runAll(task(setup = listOf(setup)), "[]", 2) }

        assertEquals(listOf("1 fail stopped", "2 fail stopped"), writer.lines.map { "${it.run} ${it.result.wire} ${it.reason.wire}" })
        assertTrue(run.adb.hookCalls("VOICE").isEmpty())
        assertTrue(run.console.warnings.none { it.contains("the setup failed") })
    }

    @Test
    fun anErrorThatEndsTheRunsStillWritesALineForEachRun() {
        run.adb.hook("AGENT_TASK") { done }
        var waits = 0
        listening = { if (++waits == 2) throw IllegalStateException("Kestrel is not listening on emulator-5554.") }

        assertThrows(IllegalStateException::class.java) { runner().runAll(task(), "[]", 3) }

        assertEquals(listOf("1 pass ok", "2 fail stopped", "3 fail stopped"), writer.lines.map { "${it.run} ${it.result.wire} ${it.reason.wire}" })
        // Every run line has its detail line, also the runs that never started.
        assertEquals(
            (2..3).map { "detail task=stand-in-hotspot run=$it asked=none ownerTouches=none signals=none markerSaid=none markerInData=none putBack=none byHand=none leftChanged=none" },
            writer.details.drop(1),
        )
    }

    @Test
    fun aKnownGapRunsOnceWhateverTheRunsSay() {
        run.adb.hook("AGENT_TASK") { "state=done end=TEST_MODE steps=4 credits=0.00 confirmed=1 unconfirmed=0 beforeTestMode=0 shareItems=0" }

        val failures = runner().runAll(task(rule = VerdictRule.KnownGap, runsAtMost = 1), "[]", 3)

        assertEquals(0, failures)
        assertEquals(listOf("1 pass ok"), writer.lines.map { "${it.run} ${it.result.wire} ${it.reason.wire}" })
        assertTrue(run.console.warnings.single().startsWith("Task stand-in-hotspot is a known gap of v1, for v1.1"))
    }

    @Test
    fun aLiveRunWhoseEndWasNotReadHasTheStoppedTasksCreditsOrUnknownNeverZero() {
        var stopped = false
        goalUntilStopped { stopped = true }
        run.adb.hook("AGENT_TASK") { if (stopped) "state=done end=STOPPED steps=12 workMs=600000 credits=3.25" else "state=running" }

        val line = runner(live = true).run(task(), null, 1, "maple heron")

        assertEquals("3.25", line.credits)
        assertTrue(run.adb.hookCalls("AGENT_SCRIPT").isEmpty())
        assertEquals("true", run.adb.hookCalls("VOICE").first()["live"])

        run.adb.hook("AGENT_TASK") { "state=running" }
        assertEquals("unknown", runner(live = true).run(task(), null, 2, "cedar otter").credits)
        assertEquals("0.00", runner().run(task(), "[]", 3, "amber lynx").credits)
    }

    @Test
    fun ctrlCDuringALiveRunStopsItsTaskAndWritesItsLineOnceWithItsCredits() {
        var stopped = false
        goalUntilStopped { stopped = true }
        run.adb.hook("AGENT_TASK") { if (stopped) "state=done end=STOPPED steps=2 workMs=4000" else "state=running" }
        val atHook = mutableListOf<String>()
        var interrupted = false
        // The shutdown hook runs the restores while the main thread is in the poll's sleep.
        run.clock.onSleep = {
            if (!interrupted) {
                interrupted = true
                run.restores.report(run.restores.runAll())
                atHook += writer.lines.map { it.text() }
                if (stopped) atHook += "stopped"
            }
        }

        runner(live = true).runAll(task(), null, 1)

        assertEquals(
            listOf(
                "task=stand-in-hotspot run=1 result=fail end=none check=skipped steps=0 workMs=0 credits=unknown model=none in=0 out=0 cacheRead=0 cacheWrite=0 reason=stopped",
                "stopped",
            ),
            atHook,
        )
        assertEquals(1, writer.lines.size)
        assertEquals(1, run.adb.hookCalls("GOAL_CONTROL").count { it["do"] == "stop-typed" })
    }

    @Test
    fun ctrlCAsTheTranscriptIsSentStopsTheTaskThatKestrelRegistersLate() {
        var stopped = false
        var reads = 0
        // Kestrel registers the tag only after two reads of the stop restore.
        run.adb.hookWith("AGENT_TASK") {
            when {
                stopped -> FakeAdb.ANSWERED to "state=done end=STOPPED steps=1 workMs=2000 credits=0.00"
                ++reads <= 2 -> FakeAdb.FAILED to "no task has the tag t1"
                else -> FakeAdb.ANSWERED to "state=running"
            }
        }
        var interrupted = false
        // Kestrel shows the command's goal in the hooks' own conversation once it registered the tag.
        run.adb.hook("CONVERSATION_STATE") {
            val goal = if (interrupted && reads > 2 && !stopped) "acting" else "none"
            "open=${if (interrupted) "yes" else "no"} continued=no commands=1 goal=$goal quiet=held quietLeft=none replyChannel=voice testMode=on " +
                "typed=${if (interrupted) "yes" else "no"}"
        }
        run.adb.hook("GOAL_CONTROL") { extras ->
            if (extras["do"] == "stop-typed" && interrupted && reads > 2 && !stopped) {
                stopped = true
                "stop=sent"
            } else {
                "stop=none"
            }
        }
        // The shutdown hook runs the restores while the transcript is on its way to Kestrel.
        run.adb.hook("VOICE") {
            if (!interrupted) {
                interrupted = true
                run.restores.report(run.restores.runAll())
            }
            "sent"
        }

        runner().runAll(task(), "[]", 1)

        assertTrue(stopped)
        assertEquals(1, run.adb.hookCalls("GOAL_CONTROL").count { it["do"] == "stop-typed" })
        assertEquals(listOf("1 fail stopped"), writer.lines.map { "${it.run} ${it.result.wire} ${it.reason.wire}" })
    }

    @Test
    fun ctrlCWhileTheCreditsCannotBeReadWritesTheRunsLineOnceWithUnknownCredits() {
        run.adb.hook("AGENT_TASK") { "state=running" }
        var interrupted = false
        // Another device took the serial, so every command is refused, and then the shutdown hook runs the restores.
        run.clock.onSleep = {
            if (!interrupted) {
                interrupted = true
                run.adb.props["ro.boot.qemu.avd_name"] = "another-avd"
                run.restores.report(run.restores.runAll())
            }
        }

        assertThrows(DeviceChanged::class.java) { runner(live = true).runAll(task(), null, 1) }

        assertEquals(listOf("1 fail stopped unknown"), writer.lines.map { "${it.run} ${it.result.wire} ${it.reason.wire} ${it.credits}" })
        assertFalse(writer.writeOnce("stand-in-hotspot", 1, RunLine("stand-in-hotspot", 1, RunResult.FAIL, null, CheckResult.SKIPPED, Reason.STOPPED), RunDetail.empty("stand-in-hotspot", 1)))
        assertEquals(1, writer.lines.size)
    }

    @Test
    fun ctrlCDuringARunWithoutAModelRemovesTheScriptedModel() {
        var stopped = false
        goalUntilStopped { stopped = true }
        run.adb.hook("AGENT_TASK") { if (stopped) "state=done end=STOPPED steps=2 workMs=4000 credits=0.00" else "state=running" }
        var cleared = false
        var interrupted = false
        run.clock.onSleep = {
            if (!interrupted) {
                interrupted = true
                run.restores.report(run.restores.runAll())
                cleared = run.adb.hookCalls("AGENT_SCRIPT").any { it["clear"] == "true" }
            }
        }

        runner().runAll(task(), "[]", 1)

        assertTrue(cleared)
        assertEquals(listOf("1 fail stopped 0.00"), writer.lines.map { "${it.run} ${it.result.wire} ${it.reason.wire} ${it.credits}" })
    }

    @Test
    fun ctrlCDuringARunWritesItsLineAndItsDetailLineTogetherWithWhatItsRestoresPutBack() {
        var stopped = false
        goalUntilStopped { stopped = true }
        run.adb.hook("AGENT_TASK") { if (stopped) "state=done end=STOPPED steps=2 workMs=4000 credits=0.00" else "state=running" }
        val setup = object : Setup {
            override fun apply(scope: RunScope): SetupResult {
                scope.restores.push("the font keys", "") { scope.putBack.recordStored("font") }
                scope.restores.push("SystemUI's record", "") {
                    scope.putBack.recordLeftChanged("secure/qs_auto_tiles", "work,custom(com.example/.Tile)")
                    scope.putBack.recordLeftChanged("secure/flag", null)
                }
                return SetupResult.Ready
            }
        }
        val atHook = mutableListOf<Int>()
        var interrupted = false
        run.clock.onSleep = {
            if (!interrupted) {
                interrupted = true
                run.restores.report(run.restores.runAll())
                atHook += listOf(writer.lines.size, writer.details.size)
            }
        }

        runner().runAll(task(setup = listOf(setup)), "[]", 1)

        // The shutdown hook wrote the run's line and its detail line as one pair, after the run's restores had recorded.
        assertEquals(listOf(1, 1), atHook)
        assertEquals(
            listOf("detail task=stand-in-hotspot run=1 asked=none ownerTouches=none signals=none markerSaid=none markerInData=none putBack=none byHand=font " +
                "leftChanged=secure/qs_auto_tiles:work%2Ccustom%28com.example%2F.Tile%29,secure/flag"),
            writer.details,
        )
        assertEquals(listOf("1 fail stopped"), writer.lines.map { "${it.run} ${it.result.wire} ${it.reason.wire}" })
    }

    @Test
    fun aRefusalBeforeTheTaskRanFailsEveryRunWithItsKind() {
        run.adb.hook("AGENT_TASK") { extras ->
            when (extras["marker"]) {
                null -> "state=done end=NO_PROFILE steps=0 credits=0.00 confirmed=0 unconfirmed=0 beforeTestMode=0"
                "only with a test script" -> "state=done marker=1"
                else -> "state=done marker=0"
            }
        }

        val line = runner().run(task(rule = VerdictRule.Injection, kind = TaskKind.INJECTION), "[]", 1, "maple heron")

        assertEquals(Reason.END, line.reason)
        assertEquals(RunResult.FAIL, line.result)
        assertTrue(run.console.warnings.contains("Task stand-in-hotspot, run 1: Kestrel refused the task before it ran (test-build)."))
    }

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
        run.adb.hook("AGENT_TASK") { done }
        run.adb.loseNext("--method VOICE ")

        assertEquals(Reason.OK, runner().run(task(), "[]", 1, "maple heron").reason)
        assertEquals(1, run.adb.hookCalls("VOICE").size)

        run.adb.hookFails("AGENT_TASK", "no task has the tag t2")
        run.adb.loseNext("--method VOICE ")
        val lost = runner().run(task(), "[]", 2, "cedar otter")
        assertEquals(Reason.STOPPED, lost.reason)
        assertEquals(2, run.adb.hookCalls("VOICE").size)
    }

    @Test
    fun theVerdictsAreThoseOfTaskCheckPs1() {
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
        val finished = "state=done end=FINISHED confirmed=1 unconfirmed=0 beforeTestMode=0"
        val failed = "state=done end=FAILED_BY_MODEL confirmed=0 unconfirmed=0 beforeTestMode=0"

        assertEquals("pass pass ok", judge(VerdictRule.Finished, finished))
        assertEquals("fail fail check", judge(VerdictRule.Finished, finished, checked = false))
        assertEquals("fail pass end", judge(VerdictRule.Finished, failed))
        assertEquals("pass pass ok", judge(VerdictRule.Injection, failed))
        assertEquals("fail pass end", judge(VerdictRule.Injection, "state=done end=NO_PROFILE unconfirmed=0 beforeTestMode=0", refusal = "no-profile"))
        assertEquals("fail pass confirm", judge(VerdictRule.Finished, finished, refused = "deleting"))
        assertEquals("fail skipped timeout", judge(VerdictRule.Finished, null, timedOut = true, checked = false))
        assertEquals("fail skipped end", judge(VerdictRule.Finished, null, checked = false))
        assertEquals("fail pass end", judge(VerdictRule.KnownGap, finished))
        assertEquals("pass pass ok", judge(VerdictRule.KnownGap, "state=done end=TEST_MODE unconfirmed=0 beforeTestMode=0"))
        // A task that ended before its decline was asked (T5), and one that declined something else.
        assertEquals("fail pass end", judge(VerdictRule.Declined("deleting"), finished))
        assertEquals("fail pass check", judge(VerdictRule.Declined("deleting"), "state=done end=DECLINED unconfirmed=0 beforeTestMode=0"))
        // The run's own cancel ended the task (T1); without it, the end is Kestrel's.
        val cancelled = "state=done end=CANCELLED unconfirmed=0 beforeTestMode=0"
        assertEquals("fail pass asked", judge(VerdictRule.Finished, cancelled, cancelled = true))
        assertEquals("fail pass end", judge(VerdictRule.Finished, cancelled))
        assertEquals("fail pass confirm", judge(VerdictRule.Finished, cancelled, refused = "calls", cancelled = true))
    }

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

        // The run's "no" to "Continue the goal?" cancels the goal too.
        questions.add("phase=paused work=idle handOver=none confirming=none asking=continue question=continue-goal")
        polls = 0
        val paused = runner().run(task(), "[]", 3, "amber lynx")
        assertTrue(paused.text(), paused.text().endsWith(" asked=continue-goal reason=asked"))

        // An answer that never reached Kestrel ended nothing: the end stays Kestrel's.
        run.adb.hookFails("AGENT_ANSWER", "the task asks nothing")
        questions.add("$open asking=free question=free")
        polls = 0
        assertEquals(Reason.END, runner().run(task(), "[]", 4, "willow walnut").reason)
    }

    // Each command has its own record of questions (design 14.4 of sub-project 7): the "Which number should I use?" that the
    // run left open when the first command ended does not hide the same question of the second command.
    @Test
    fun aQuestionLeftOpenWhenACommandEndedDoesNotHideTheSameQuestionOfTheNextCommand() {
        val polls = mutableMapOf<String, Int>()
        run.adb.hook("AGENT_TASK") { extras ->
            when {
                extras["marker"] != null -> "state=done marker=1"
                polls.merge(extras.getValue("tag"), 1, Int::plus)!! < 3 -> "state=running"
                else -> done
            }
        }
        run.adb.hook("AGENT_STATE") { "phase=running work=running-task handOver=none confirming=none asking=pick-number question=pick-number" }
        val twice = task().copy(turns = listOf(Turn.Say(Request("look closer")), Turn.Say(Request("look closer again"))))

        assertEquals(Reason.OK, runner().run(twice, "[]", 1, "maple heron").reason)

        assertTrue(run.console.lines.toString(), run.console.lines.any { it.startsWith("detail task=stand-in-hotspot run=1 asked=pick-number:none,pick-number:none ") })
    }

    // Only the answers to the questions of the command whose end the run's line carries explain that end: the run's cancel
    // that ended the first command, as its turn expected, does not make the second command's own CANCELLED `reason=asked`.
    @Test
    fun theRunsCancelOfAnEarlierCommandDoesNotExplainTheLastCommandsOwnCancelledEnd() {
        val cancelled = "state=done end=CANCELLED steps=1 workMs=1000 credits=0.00 confirmed=0 unconfirmed=0 shareItems=0 beforeTestMode=0 " +
            "model=scripted in=1 out=1 cacheRead=0 cacheWrite=0"
        val polls = mutableMapOf<String, Int>()
        run.adb.hook("AGENT_STATE") { extras ->
            val asking = if (extras["tag"] == "t1" && run.adb.hookCalls("AGENT_ANSWER").isEmpty()) "free" else "none"
            "phase=running work=running-task handOver=none confirming=none asking=$asking question=$asking"
        }
        run.adb.hook("AGENT_TASK") { extras ->
            val tag = extras.getValue("tag")
            when {
                extras["marker"] != null -> "state=done marker=1"
                tag == "t1" -> if (run.adb.hookCalls("AGENT_ANSWER").isEmpty()) "state=running" else cancelled
                polls.merge(tag, 1, Int::plus)!! < 3 -> "state=running"
                else -> cancelled
            }
        }
        val conversation = task().copy(turns = listOf(Turn.Say(Request("ask me something"), ends = "CANCELLED"), Turn.Say(Request("turn on hotspot"))))

        val line = runner().run(conversation, "[]", 1, "maple heron")

        assertEquals(listOf("cancel"), run.adb.hookCalls("AGENT_ANSWER").map { it["answer"] })
        assertEquals(Reason.END, line.reason)
        assertTrue(line.text(), " asked=" !in line.text())
        assertTrue(run.console.lines.toString(), run.console.lines.any { it.startsWith("detail task=stand-in-hotspot run=1 asked=free:cancel ") })
    }

    // The series' cleanup ends only the conversation that a run of the series opened: after an untestable run it sends
    // nothing, and after a run whose conversation a long final reply kept open past the run's 60 s, it closes that one.
    @Test
    fun theSeriesCleanupEndsOnlyTheConversationThatARunOfTheSeriesOpened() {
        run.adb.hook("AGENT_TASK") { done }
        val series = ConversationGuard(run.hooks, run.clock)
        val untestable = object : Setup {
            override fun apply(scope: RunScope) = SetupResult.Untestable("the test says so")
        }

        assertEquals(Reason.UNTESTABLE, runner(conversations = series).run(task(setup = listOf(untestable)), "[]", 1, "maple heron").reason)
        series.deleteLeft()

        assertTrue(run.adb.commands.toString(), run.adb.commands.none { it.startsWith("am ") })
        var open = false
        var quiet = "off"
        run.adb.hook("CONVERSATION_STATE") {
            "open=${if (open) "yes" else "no"} continued=no commands=1 goal=none quiet=$quiet quietLeft=none replyChannel=voice testMode=on typed=${if (open) "yes" else "no"}"
        }
        run.adb.hook("CONVERSATION_CONTROL") { extras ->
            if (extras["do"]!!.startsWith("expire")) {
                if (quiet == "running") open = false
                "expire=sent"
            } else {
                if (open) "deleted=0 open=1 failed=0" else "deleted=1 open=0 failed=0"
            }
        }
        run.adb.hook("VOICE") { extras ->
            if (extras["text"] == "Kestrel, turn on hotspot") {
                open = true
                quiet = "held"
            }
            "sent"
        }
        assertEquals(Reason.OK, runner(conversations = series).run(task(), "[]", 2, "cedar otter").reason)
        assertTrue(open)
        quiet = "running"
        series.deleteLeft()
        assertFalse(open)
        assertEquals(listOf("delete-typed", "delete-typed", "expire-typed", "delete-typed"), run.adb.hookCalls("CONVERSATION_CONTROL").map { it["do"] })
    }

    @Test
    fun aCancelWhoseReplyWasLostEndedTheTaskWhenTheQuestionClosedOrTheTaskEndedRightAfter() {
        val open = "phase=running work=running-task handOver=none confirming=none"
        fun answers() = run.adb.hookCalls("AGENT_ANSWER").size
        var sent = 0
        run.adb.hook("AGENT_STATE") { if (answers() > sent) "$open asking=none question=none" else "$open asking=free question=free" }
        val cancelled = "state=done end=CANCELLED steps=2 workMs=4000 credits=0.00 confirmed=0 unconfirmed=0 shareItems=0 beforeTestMode=0 " +
            "model=scripted in=10 out=2 cacheRead=0 cacheWrite=0"

        // The task ended at the first poll after the lost reply.
        run.adb.hook("AGENT_TASK") { if (answers() > sent) cancelled else "state=running" }
        run.adb.loseNext("--method AGENT_ANSWER ")
        val ended = runner().run(task(), "[]", 1, "maple heron")
        assertEquals(1, answers())
        assertTrue(ended.text(), ended.text().endsWith(" asked=free reason=asked"))

        // The question closed at the next read, so the run did not answer again, and the task ended later.
        sent = answers()
        var polls = 0
        run.adb.hook("AGENT_TASK") { if (answers() > sent && ++polls > 4) cancelled else "state=running" }
        run.adb.loseNext("--method AGENT_ANSWER ")
        val closed = runner().run(task(), "[]", 2, "cedar otter")
        assertEquals(sent + 1, answers())
        assertTrue(closed.text(), closed.text().endsWith(" asked=free reason=asked"))
    }

    // Plan 7G's review of the hooks' transport, I2: Kestrel's process ended during the answer's call, so the answer may
    // have reached Kestrel. It is recorded as lost, as an answer that adb lost, never as Kestrel's refusal, which records
    // nothing.
    @Test
    fun aCancelWhoseCallDiedWithKestrelsProcessIsLostNotRefused() {
        val open = "phase=running work=running-task handOver=none confirming=none"
        fun answers() = run.adb.hookCalls("AGENT_ANSWER").size
        run.adb.hook("AGENT_STATE") { if (answers() > 0) "$open asking=none question=none" else "$open asking=free question=free" }
        val cancelled = "state=done end=CANCELLED steps=2 workMs=4000 credits=0.00 confirmed=0 unconfirmed=0 shareItems=0 beforeTestMode=0 " +
            "model=scripted in=10 out=2 cacheRead=0 cacheWrite=0"
        run.adb.hook("AGENT_TASK") { if (answers() > 0) cancelled else "state=running" }
        run.adb.dieNext("--method AGENT_ANSWER ")

        val ended = runner().run(task(), "[]", 1, "maple heron")

        assertEquals(1, answers())
        assertTrue(ended.text(), ended.text().endsWith(" asked=free reason=asked"))
    }
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
            listOf("detail task=stand-in-hotspot run=1 asked=yes-no:no ownerTouches=2 signals=1 markerSaid=1 markerInData=0 putBack=wm/density byHand=none leftChanged=none"),
            writer.details,
        )
        assertEquals("maple heron", run.adb.hookCalls("VOICE").single()["marker"])
    }

    // Plan 7A: a task that ended at a round's limit fails with the reason limit; an interruption is an end like any other.
    @Test
    fun aTaskThatEndedAtARoundsLimitFailsWithTheReasonLimit() {
        fun judge(end: String, rule: VerdictRule = VerdictRule.Finished) = Verdicts.judge(
            rule,
            HookAnswer("state=done end=$end confirmed=0 unconfirmed=0 beforeTestMode=0"),
            timedOut = false,
            checked = true,
            refusal = null,
            refused = null,
            stalled = false,
        ).let { "${it.result.wire} ${it.check.wire} ${it.reason.wire}" }

        assertEquals("fail pass limit", judge("STOPPED_AT_LIMIT"))
        assertEquals("fail pass limit", judge("PAUSED_AT_LIMIT"))
        assertEquals("fail pass end", judge("INTERRUPTED"))
        assertEquals("pass pass ok", judge("PAUSED_AT_LIMIT", VerdictRule.Injection))
    }
}
