package dev.kestrel.app.agent

import dev.kestrel.agent.AgentPorts
import dev.kestrel.agent.AgentSettings
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.memory.Fact
import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.DataPort
import dev.kestrel.agent.port.Gate
import dev.kestrel.agent.port.NoEyes
import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.agent.port.TaskLog
import dev.kestrel.agent.testing.FakeApps
import dev.kestrel.agent.testing.FakeClock
import dev.kestrel.agent.testing.FakeData
import dev.kestrel.agent.testing.FakeLog
import dev.kestrel.agent.testing.FakeMemory
import dev.kestrel.agent.testing.FakeOwner
import dev.kestrel.agent.testing.FakeScreen
import dev.kestrel.agent.testing.Fixtures
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.app.listen.CallApi
import dev.kestrel.app.orchestrator.Effect
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.PauseCause
import dev.kestrel.app.orchestrator.Request
import dev.kestrel.safety.HandOverKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentRunnerTest {
    private val sent = mutableListOf<Event>()
    private val bar = mutableListOf<String>()
    private val failures = mutableListOf<String>()
    private val starts = mutableListOf<TaskStart>()
    private var latest = 1
    private var live = false
    private var now = 0L
    private val owner = FakeTaskOwner()
    private val memory = FakeMemory()
    private val log = FakeLog()
    private val touches = MutableSharedFlow<OwnerTouch>()
    private val calls = object : CallApi {
        var ended = 0
        var failing = false

        override fun allowed() = true

        override fun answer() = Unit

        override fun end(): Boolean {
            if (failing) throw IllegalStateException("Telecom failed")
            ended++
            return true
        }

        override fun active() = false

        override fun showInCallScreen() = Unit
    }
    private var model: ScriptedModel = ScriptedModel(listOf(fail("No such app")))
    private var environment = AgentEnvironment { start ->
        starts += start
        val chosen = (start.model as? ModelChoice.Override)?.port ?: model
        val ports = AgentPorts(
            FakeScreen(Fixtures.screen("scenario-home")), NoEyes, FakeData(), owner, chosen, FakeApps(), memory, log, FakeClock(),
        )
        Prepared.Ready(ports, AgentSettings(), owner, emptySet(), touches, "Copilot") {}
    }

    private fun TestScope.runner(debuggable: Boolean = false, options: Map<String, String> = emptyMap()) = AgentRunner(
        scope = this,
        environment = { environment.prepare(it) },
        send = { sent += it },
        latestTask = { latest },
        options = { options },
        debuggable = debuggable,
        liveSession = { live },
        deleteHistory = { if (failures.contains("delete")) error("The disk failed") },
        showOnBar = { bar += it },
        backstop = CallBackstop(calls) { now },
        report = { what, _ -> failures += what },
        nowMillis = { now },
        newId = { "task-$it-0a1b2c3d" },
    )

    private fun fail(reason: String) = ScriptedModel.call("fail", null, null, null, "reason" to reason)

    private fun ended(task: Int = 1): Event.TaskEnded? = sent.filterIsInstance<Event.TaskEnded>().singleOrNull { it.task == task }

    @Test
    fun aTaskRunsTheAgentAndEndsWithItsSentence() = runTest {
        val runner = runner()

        runner.run(1, "open Viber")
        advanceUntilIdle()

        assertEquals(Event.TaskEnded(1, "I couldn't do it: No such app. I didn't change anything."), ended())
        assertEquals(ModelChoice.Profile(), starts.single().model)
        assertEquals("task-1-0a1b2c3d", starts.single().id)
        assertEquals(EndReason.FAILED_BY_MODEL, runner.ended(1)!!.report.end)
        assertFalse(runner.running(1))
        assertTrue(owner.closed)
    }

    @Test
    fun aDebugBuildRunsATaskOnlyWithAScriptOrInALiveSitting() = runTest {
        runner(debuggable = true).run(1, "open Viber")
        advanceUntilIdle()
        assertEquals(Event.TaskEnded(1, AgentTexts.couldNotStart(AgentTexts.TEST_BUILD)), ended())
        assertEquals(emptyList<TaskStart>(), starts)

        val scripted = runner(debuggable = true).apply { modelOverride = ScriptedModel(listOf(fail("Scripted"))) }
        scripted.run(2, "open Viber")
        advanceUntilIdle()
        assertTrue(starts.single().model is ModelChoice.Override)

        live = true
        runner(debuggable = true, options = mapOf("live" to "true", "model" to "claude-haiku-5.5")).run(3, "open Viber")
        advanceUntilIdle()
        assertEquals(ModelChoice.Profile("claude-haiku-5.5"), starts.last().model)
    }

    @Test
    fun aTaskThatCannotStartSaysWhy() = runTest {
        environment = AgentEnvironment { Prepared.Refused(AgentTexts.NO_WATCHER) }
        runner().run(1, "open Viber")
        advanceUntilIdle()
        assertEquals(Event.TaskEnded(1, AgentTexts.NO_WATCHER), ended())

        environment = AgentEnvironment { error("Kestrel broke") }
        runner().run(2, "open Viber")
        advanceUntilIdle()
        assertEquals(Event.TaskEnded(2, AgentTexts.COULD_NOT_START), ended(2))
        assertEquals(listOf("A task"), failures)
    }

    @Test
    fun cancelTaskCancelsTheAgentWhichReportsWhatItDid() = runTest {
        owner.gateWait = CompletableDeferred()
        val runner = runner()
        runner.run(1, "open Viber")
        runCurrent()
        assertTrue(runner.running(1))

        runner.steer(Effect.CancelTask)
        advanceUntilIdle()

        assertEquals(Event.TaskEnded(1, "Cancelled. I didn't change anything."), ended())
        assertEquals(EndReason.CANCELLED, runner.ended(1)!!.report.end)
    }

    @Test
    fun theReportOfAReplacedTaskGoesToTheBar() = runTest {
        latest = 2
        runner().run(1, "open Viber")
        advanceUntilIdle()

        assertEquals(listOf("Cancelled: I didn't change anything."), bar)
    }

    @Test
    fun theEffectsSteerTheOwnerPortOfTheSameTaskOnly() = runTest {
        owner.gateWait = CompletableDeferred()
        val runner = runner()
        runner.run(1, "open Viber")
        runCurrent()

        runner.steer(Effect.AnswerTask(1, "yes"))
        runner.steer(Effect.AnswerTask(2, "no"))
        runner.steer(Effect.ContinueTask(1))
        runner.steer(Effect.PauseTask(1, PauseCause.LOCK))
        runner.steer(Effect.ResumeTask(1))
        runner.steer(Effect.ExpireHandOver(1))

        assertEquals(listOf("answered yes", "continued", "paused", "resumed", "expired"), owner.steered)
        runner.steer(Effect.CancelTask)
        advanceUntilIdle()
    }

    @Test
    fun anEffectForATaskThatNoLongerRunsEndsItForTheRules() = runTest {
        val runner = runner()
        runner.run(1, "open Viber")
        advanceUntilIdle()

        // Task 1 has ended already, so the Rules have its TaskEnded; tasks 5 to 7 are unknown, so their questions and
        // hand-overs would otherwise stay open.
        runner.steer(Effect.ExpireHandOver(1))
        runner.steer(Effect.ExpireHandOver(5))
        runner.steer(Effect.ContinueTask(6))
        runner.steer(Effect.AnswerTask(7, "yes"))

        assertEquals(listOf(1, 5, 6, 7), sent.filterIsInstance<Event.TaskEnded>().map { it.task })
        assertEquals(listOf(Event.TaskEnded(5, null), Event.TaskEnded(6, null), Event.TaskEnded(7, null)), sent.filterIsInstance<Event.TaskEnded>().drop(1))
        assertEquals(emptyList<String>(), owner.steered)
    }

    @Test
    fun anOutgoingCallRightAfterAnActionStopsTheTaskAndSaysWhy() = runTest {
        owner.gateWait = CompletableDeferred()
        val runner = runner()
        runner.run(1, "open Viber")
        runCurrent()
        starts.single().acted()
        now = 1_000

        runner.steer(Effect.PauseTask(1, PauseCause.OUTGOING_CALL))
        advanceUntilIdle()

        assertEquals(Event.TaskEnded(1, "${AgentTexts.CALL_STARTED} I didn't change anything."), ended())
        assertEquals(1, calls.ended)
        assertEquals(emptyList<String>(), owner.steered)
    }

    @Test
    fun aCallThatCannotBeEndedStillStopsTheTask() = runTest {
        owner.gateWait = CompletableDeferred()
        calls.failing = true
        val runner = runner()
        runner.run(1, "open Viber")
        runCurrent()
        starts.single().acted()
        now = 1_000

        runner.steer(Effect.PauseTask(1, PauseCause.OUTGOING_CALL))
        advanceUntilIdle()

        assertEquals(Event.TaskEnded(1, "${AgentTexts.CALL_STARTED} I didn't change anything."), ended())
        assertEquals(listOf("Ending the agent's call"), failures)
    }

    @Test
    fun theOwnersTouchCancelsThroughTheOrchestratorAndALateEchoIsLogged() = runTest {
        owner.gateWait = CompletableDeferred()
        val runner = runner()
        runner.run(1, "open Viber")
        runCurrent()

        touches.emit(OwnerTouch(lateEchoMillis = 230))
        runCurrent()

        assertTrue(Event.OwnerTouched in sent)
        assertTrue(log.texts(TaskLog.Kind.ACTION).contains("possible late echo after 230 ms"))
        runner.steer(Effect.CancelTask)
        advanceUntilIdle()
    }

    @Test
    fun rememberAndForgetAskTheOwnerFirst() = runTest {
        val runner = runner()
        owner.owner.answers += Answer.Yes
        runner.runMemory(1, Request.Remember("the gate code", "blue"))
        advanceUntilIdle()
        assertEquals(Event.TaskEnded(1, AgentTexts.REMEMBERED), ended(1))
        assertEquals(listOf("the gate code" to "blue"), memory.facts.map { it.key to it.value })
        assertEquals("Remember that the gate code is blue?", owner.owner.asked.single().first)

        runner.runMemory(2, Request.Forget("the door code"))
        advanceUntilIdle()
        assertEquals(Event.TaskEnded(2, AgentTexts.noFact("the door code")), ended(2))

        owner.owner.answers += Answer.No
        runner.runMemory(3, Request.Forget("The Gate Code"))
        advanceUntilIdle()
        assertEquals(Event.TaskEnded(3, AgentTexts.NOT_SAVED), ended(3))
        assertEquals(listOf(Fact("the gate code", "blue", created = 0)).map { it.key }, memory.facts.map { it.key })
        assertTrue(starts.all { it.local && it.model == null })
    }

    @Test
    fun saveThatAsNeedsATaskThatFinishedInTheLastTenMinutes() = runTest {
        runner().runMemory(1, Request.SaveRoutine("test routine"))
        advanceUntilIdle()

        assertEquals(Event.TaskEnded(1, AgentTexts.NOTHING_TO_SAVE), ended(1))
    }

    @Test
    fun deleteHistoryReportsWhetherItWorked() = runTest {
        val runner = runner()
        runner.steer(Effect.DeleteHistory)
        advanceUntilIdle()
        failures += "delete"
        runner.steer(Effect.DeleteHistory)
        advanceUntilIdle()

        assertEquals(listOf(Event.HistoryDeleted(true), Event.HistoryDeleted(false)), sent.filterIsInstance<Event.HistoryDeleted>())
    }

    @Test
    fun aPauseWhileTheTaskPreparesReachesItsOwnerPort() = runTest {
        owner.gateWait = CompletableDeferred()
        val preparing = CompletableDeferred<Unit>()
        val prepare = environment
        environment = AgentEnvironment { preparing.await(); prepare.prepare(it) }
        val runner = runner()
        runner.run(1, "open Viber")
        runCurrent()

        runner.steer(Effect.PauseTask(1, PauseCause.LOCK))
        preparing.complete(Unit)
        runCurrent()

        assertEquals(listOf("paused"), owner.steered)
        runner.steer(Effect.CancelTask)
        advanceUntilIdle()
    }

    @Test
    fun aNewTaskPreparesOnlyAfterTheCancelledTaskReleased() = runTest {
        val order = mutableListOf<String>()
        owner.gateWait = CompletableDeferred()
        environment = AgentEnvironment { start ->
            order += "prepare ${start.task}"
            val ports = AgentPorts(
                FakeScreen(Fixtures.screen("scenario-home")), NoEyes, FakeData(), owner, model, FakeApps(), memory, log, FakeClock(),
            )
            Prepared.Ready(ports, AgentSettings(), owner, emptySet(), touches, "Copilot") {
                yield()
                order += "release ${start.task}"
            }
        }
        val runner = runner()
        runner.run(1, "open Viber")
        runCurrent()

        // "Stop. Kestrel, <task>": task 1's release stops the touch watcher, so task 2 starts its own only after it.
        runner.steer(Effect.CancelTask)
        runner.run(2, "open Telegram")
        runCurrent()

        assertEquals(listOf("prepare 1", "release 1", "prepare 2"), order.take(3))
        runner.steer(Effect.CancelTask)
        advanceUntilIdle()
    }

    @Test
    fun aFailedCloseOfTheOwnerPortStillReleasesTheTask() = runTest {
        var released = false
        owner.closeFails = true
        val prepare = environment
        environment = AgentEnvironment { start ->
            val ready = prepare.prepare(start) as Prepared.Ready
            Prepared.Ready(ready.ports, ready.settings, ready.owner, ready.knownNames, ready.touches, ready.providerName) { released = true }
        }

        runner().run(1, "open Viber")
        advanceUntilIdle()

        // The release stops the touch watcher, so a throw of the owner port's close must not skip it.
        assertTrue(released)
        assertEquals(listOf("Ending a task"), failures)
        assertEquals(Event.TaskEnded(1, "I couldn't do it: No such app. I didn't change anything."), ended())
    }

    private class FakeTaskOwner(val owner: FakeOwner = FakeOwner()) : TaskOwner, OwnerPort by owner {
        val steered = mutableListOf<String>()
        var gateWait: CompletableDeferred<Unit>? = null
        var closed = false
        var closeFails = false

        override suspend fun gate(): Gate {
            gateWait?.await()
            return owner.gate()
        }

        override fun answered(answer: String?) {
            steered += "answered $answer"
        }

        override fun continued() {
            steered += "continued"
        }

        override fun paused() {
            steered += "paused"
        }

        override fun resumed() {
            steered += "resumed"
        }

        override fun handOverExpired() {
            steered += "expired"
        }

        override val handOverKind: HandOverKind? = null

        override fun close() {
            closed = true
            if (closeFails) error("The card failed")
        }
    }

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
}
