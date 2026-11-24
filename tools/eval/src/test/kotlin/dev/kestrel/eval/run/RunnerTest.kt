package dev.kestrel.eval.run

import dev.kestrel.eval.config.LocalConfig
import dev.kestrel.eval.config.Redactions
import dev.kestrel.eval.device.DeviceChanged
import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.task.Answers
import dev.kestrel.eval.task.Check
import dev.kestrel.eval.task.FakeRun
import dev.kestrel.eval.task.Rehearsal
import dev.kestrel.eval.task.Request
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

/** The runner against a scripted device (design D5 and D18 of sub-project 6). */
class RunnerTest {
    private val run = FakeRun()
    private val writer = LineWriter(run.console, null)
    private var tagNumber = 0
    private var listening: () -> Unit = {}
    private val done = "state=done end=FINISHED steps=3 workMs=9000 credits=0.00 confirmed=0 unconfirmed=0 shareItems=0 beforeTestMode=0 " +
        "model=scripted in=10 out=2 cacheRead=0 cacheWrite=0"

    private fun runner(live: Boolean = false, restores: Restores = run.restores) = Runner(
        run.device, run.hooks, restores, run.clock, run.console, run.scope.config, run.scope.work, writer, RunOptions(live),
        { listening() }, Markers(Random(1)), { "t${++tagNumber}" },
    )

    private fun task(
        rule: VerdictRule = VerdictRule.Finished,
        answers: Answers = Answers.NONE,
        setup: List<Setup> = emptyList(),
        checked: Boolean = true,
        kind: TaskKind = TaskKind.STAND_IN,
        runsAtMost: Int? = null,
    ) = Task(
        "stand-in-hotspot", kind, Request("turn on hotspot"), setOf(DeviceKind.EMULATOR), answers, rule, setup,
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
        run.adb.hook("VOICE") { extras -> if (extras["text"] == "stop") stopped = true; "sent" }
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
        run.adb.hook("VOICE") { extras -> if (extras["text"] == "stop") stopped = true; "sent" }
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
        run.adb.hook("VOICE") { extras -> if (extras["text"] == "stop") stopped = true; "sent" }
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
        assertEquals(1, run.adb.hookCalls("VOICE").count { it["text"] == "stop" })
    }

    @Test
    fun ctrlCAsTheTranscriptIsSentStopsTheTaskThatKestrelRegistersLate() {
        var stopped = false
        var reads = 0
        // Kestrel registers the tag only after two reads of the stop restore.
        run.adb.on("dev\\.kestrel\\.hook\\.AGENT_TASK") {
            val answer = when {
                stopped -> "-1, data=\"state=done end=STOPPED steps=1 workMs=2000 credits=0.00\""
                ++reads <= 2 -> "1, data=\"no task has the tag t1\""
                else -> "-1, data=\"state=running\""
            }
            "Broadcasting: Intent { act=dev.kestrel.hook.AGENT_TASK }\nBroadcast completed: result=$answer"
        }
        var interrupted = false
        // The shutdown hook runs the restores while the transcript is on its way to Kestrel.
        run.adb.hook("VOICE") { extras ->
            if (extras["text"] == "stop") {
                stopped = true
            } else if (!interrupted) {
                interrupted = true
                run.restores.report(run.restores.runAll())
            }
            "sent"
        }

        runner().runAll(task(), "[]", 1)

        assertTrue(stopped)
        assertEquals(1, run.adb.hookCalls("VOICE").count { it["text"] == "stop" })
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
        run.adb.hook("VOICE") { extras -> if (extras["text"] == "stop") stopped = true; "sent" }
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
        run.adb.hook("VOICE") { extras -> if (extras["text"] == "stop") stopped = true; "sent" }
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
    fun aLostTranscriptIsReadBackAndNeverSentTwice() {
        run.adb.hook("AGENT_TASK") { done }
        run.adb.loseNext("hook.VOICE")

        assertEquals(Reason.OK, runner().run(task(), "[]", 1, "maple heron").reason)
        assertEquals(1, run.adb.hookCalls("VOICE").size)

        run.adb.hookFails("AGENT_TASK", "no task has the tag t2")
        run.adb.loseNext("hook.VOICE")
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
        run.adb.loseNext("hook.AGENT_ANSWER")
        val ended = runner().run(task(), "[]", 1, "maple heron")
        assertEquals(1, answers())
        assertTrue(ended.text(), ended.text().endsWith(" asked=free reason=asked"))

        // The question closed at the next read, so the run did not answer again, and the task ended later.
        sent = answers()
        var polls = 0
        run.adb.hook("AGENT_TASK") { if (answers() > sent && ++polls > 4) cancelled else "state=running" }
        run.adb.loseNext("hook.AGENT_ANSWER")
        val closed = runner().run(task(), "[]", 2, "cedar otter")
        assertEquals(sent + 1, answers())
        assertTrue(closed.text(), closed.text().endsWith(" asked=free reason=asked"))
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
