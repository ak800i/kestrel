package dev.kestrel.eval.run

import dev.kestrel.eval.task.FakeRun
import dev.kestrel.eval.task.SetupFailed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Kestrel's conversation around a run (design 6.4, 14.1 and 14.4 of sub-project 7). */
class ConversationGuardTest {
    private val run = FakeRun()
    private val guard = ConversationGuard(run.hooks, run.clock)
    private var open = true
    private var quiet = "held"
    private var goal = "none"
    private var left = "none"

    // Whether the open conversation carries the hooks' mark (typed=), which Kestrel checks again at expire-typed and stop-typed.
    private var typed = true

    // Whether a stop that Kestrel takes ends the goal.
    private var stopEnds = true

    init {
        run.adb.hook("CONVERSATION_STATE") {
            "open=${if (open) "yes" else "no"} continued=no commands=1 goal=$goal quiet=$quiet quietLeft=$left replyChannel=voice testMode=on " +
                "typed=${if (open && typed) "yes" else "no"}"
        }
        run.adb.hook("CONVERSATION_CONTROL") { extras ->
            when (extras["do"]) {
                "delete-typed" -> if (open && typed) "deleted=0 open=1 failed=0" else "deleted=1 open=0 failed=0"
                "expire-typed" -> if (open && typed) expire() else "expire=not-typed"
                else -> expire()
            }
        }
        run.adb.hook("GOAL_CONTROL") { extras ->
            if (extras["do"] == "stop-typed" && open && typed && goal != "none") {
                if (stopEnds) goal = "none"
                "stop=sent"
            } else {
                "stop=none"
            }
        }
    }

    private fun expire(): String {
        if (quiet == "running") open = false
        return "expire=sent"
    }

    @Test
    fun closeWaitsForTheFinalReplyThenEndsTheQuietTimeAndWaitsUntilTheConversationClosed() {
        var sleeps = 0
        // The final reply holds the quiet clock for two reads; then the clock runs.
        run.clock.onSleep = { if (++sleeps == 2) quiet = "running" }

        guard.close()

        assertFalse(open)
        assertEquals(listOf(mapOf("do" to "expire-typed")), run.adb.hookCalls("CONVERSATION_CONTROL"))
        guard.close()
        assertEquals(1, run.adb.hookCalls("CONVERSATION_CONTROL").size)
    }

    @Test
    fun anOpenGoalOrAConversationThatStaysOpenFailsTheStep() {
        goal = "acting"
        assertEquals("Kestrel's conversation has a goal open (acting); say 'Kestrel, stop' first.", assertThrows(SetupFailed::class.java) { guard.close() }.message)

        goal = "none"
        assertEquals("Kestrel's conversation did not close within 60 s.", assertThrows(SetupFailed::class.java) { guard.close() }.message)
        assertTrue(run.adb.hookCalls("CONVERSATION_CONTROL").isEmpty())
    }

    // One rule for every close (plan 7G): before a run, the owner's open conversation, which has no mark, is never ended; the
    // run waits until its own quiet time has closed it, for the quiet time that Kestrel first reports and a margin, or for
    // the close's own 60 s while Kestrel reports none, as while his goal is open. When it is still open then, the owner is
    // busy with Kestrel, and the series stops (OwnerBusy) rather than failing his runs.
    @Test
    fun aRunWaitsForTheOwnersConversationToCloseByItselfForItsQuietTimeAndThenTheSeriesStops() {
        typed = false
        quiet = "running"
        left = "90"
        var sleeps = 0
        // The owner's quiet time closes his conversation after five rounds.
        run.clock.onSleep = { if (++sleeps == 5) open = false }

        guard.close()

        assertTrue(run.adb.hookCalls("CONVERSATION_CONTROL").isEmpty())
        open = true
        run.clock.onSleep = {}
        // The owner keeps talking, so each read reports 90 s again; the wait ends 90 s and the margin after the first.
        val busy = timed {
            assertEquals(
                "The owner's conversation with Kestrel stayed open past its quiet time, and the evaluation never ends it (owner-busy); " +
                    "the series stops, and --resume runs the rest once he is done.",
                assertThrows(OwnerBusy::class.java) { guard.close() }.message,
            )
        }
        assertTrue("$busy", busy > 90.seconds + ConversationGuard.OWNER_MARGIN - 2.seconds && busy <= 90.seconds + ConversationGuard.OWNER_MARGIN)

        goal = "acting"
        quiet = "held"
        left = "none"
        assertEquals(59.seconds, timed { assertThrows(OwnerBusy::class.java) { guard.close() } })
        assertTrue(run.adb.hookCalls("CONVERSATION_CONTROL").isEmpty())
        assertTrue(run.adb.hookCalls("GOAL_CONTROL").isEmpty())
        assertTrue(open)
    }

    // A run's own restore closes only the hooks' conversation, as the series' cleanup does: when the owner continues the
    // run's conversation with his words during the restore's wait, the mark goes and the conversation stays open; when the
    // run's conversation closes by itself and the owner opens his own, his stays open too. Neither gets an expire.
    @Test
    fun aRunsRestoreLeavesTheConversationThatTheOwnerContinuedOrOpenedDuringItsWait() {
        val warnings = mutableListOf<String>()
        var sleeps = 0
        // The run's final reply holds the quiet clock; the owner speaks after two rounds.
        run.clock.onSleep = { if (++sleeps == 2) typed = false }

        guard.deleteRun { warnings += it }

        assertTrue(open)
        assertEquals(listOf("delete-typed"), run.adb.hookCalls("CONVERSATION_CONTROL").map { it["do"] })

        typed = true
        sleeps = 0
        // The run's conversation closes after two rounds, and the owner opens his own, whose quiet clock runs.
        run.clock.onSleep = {
            if (++sleeps == 2) {
                typed = false
                quiet = "running"
            }
        }
        guard.deleteRun { warnings += it }

        assertTrue(open)
        assertTrue(run.adb.hookCalls("CONVERSATION_CONTROL").none { it["do"]!!.startsWith("expire") })
        assertEquals(emptyList<String>(), warnings)
    }

    // Plan 7F's delete-typed, which deletes the conversations that any hook's input opened (its Task 13a and f23bd193): the
    // hooks' own conversations leave Kestrel's past conversations once the run's one is closed. One that a long final reply
    // keeps open is only warned about after a run, and the series' cleanup, which waits longer, fails when it is still open.
    @Test
    fun deleteRunDeletesTheHooksOwnConversationsAndWarnsAboutAnOpenOneWhichTheSeriesCleanupDeletesOrFailsOn() {
        val warnings = mutableListOf<String>()
        quiet = "running"

        guard.deleteRun { warnings += it }

        assertFalse(open)
        assertEquals(listOf(mapOf("do" to "expire-typed"), mapOf("do" to "delete-typed")), run.adb.hookCalls("CONVERSATION_CONTROL"))
        assertEquals(emptyList<String>(), warnings)

        open = true
        quiet = "held"
        guard.deleteRun { warnings += it }
        guard.gave("t1")
        assertEquals(
            listOf("Kestrel's conversation did not close within 60 s. It stays among the hooks' own conversations, which the next run or the series' cleanup deletes."),
            warnings,
        )
        assertEquals("Kestrel's conversation did not close within 300 s.", assertThrows(SetupFailed::class.java) { guard.deleteLeft() }.message)

        quiet = "running"
        guard.deleteLeft()
        assertFalse(open)
        run.adb.hook("CONVERSATION_CONTROL") { "deleted=0 open=0 failed=1" }
        assertEquals("Kestrel could not delete 1 of the hooks' own conversations.", assertThrows(IllegalStateException::class.java) { guard.deleteLeft() }.message)
    }

    // One rule for every stop (plan 7G): only the goal of the hooks' own conversation gets a stop, through stop-typed, which
    // Kestrel checks again on its loop; a goal of the owner's conversation never does, also when the owner's words reach
    // the hooks' conversation between the guard's read and its stop.
    @Test
    fun stopGoalStopsOnlyTheGoalOfTheHooksOwnConversation() {
        assertEquals(GoalStop.ENDED, guard.stopGoal())
        assertTrue(run.adb.hookCalls("GOAL_CONTROL").isEmpty())

        goal = "paused-at-limit"
        assertEquals(GoalStop.ENDED, guard.stopGoal())
        assertEquals(listOf(mapOf("do" to "stop-typed")), run.adb.hookCalls("GOAL_CONTROL"))

        // The owner's conversation: its goal goes on, and no stop is sent.
        goal = "acting"
        typed = false
        assertEquals(GoalStop.OWNERS, guard.stopGoal())
        assertEquals(1, run.adb.hookCalls("GOAL_CONTROL").size)
        assertEquals("acting", goal)

        // The owner speaks right after a read that still shows the mark: Kestrel refuses the stop, and the next read leaves
        // his goal alone.
        typed = true
        run.adb.hook("CONVERSATION_STATE") {
            val shown = typed
            typed = false
            "open=yes continued=no commands=1 goal=$goal quiet=held quietLeft=none replyChannel=voice testMode=on typed=${if (shown) "yes" else "no"}"
        }
        assertEquals(GoalStop.OWNERS, guard.stopGoal())
        assertEquals("acting", goal)
        assertEquals(2, run.adb.hookCalls("GOAL_CONTROL").size)
    }

    // A stop that Kestrel took is not sent again; a goal that it does not end leaves the wait at its 15 s.
    @Test
    fun aGoalThatTheStopDoesNotEndIsStillOpenAfterTheWaitWithOneStop() {
        goal = "interrupted"
        stopEnds = false

        assertEquals(GoalStop.OPEN, guard.stopGoal())

        assertEquals(1, run.adb.hookCalls("GOAL_CONTROL").size)
        assertEquals("interrupted", goal)
    }

    // The series' cleanup ends only what the series' runs opened: nothing at all when no run gave Kestrel a command, as when
    // every run was untestable, and not an open conversation that is not one of the hooks' own, which is the owner's.
    @Test
    fun theSeriesCleanupEndsOnlyAConversationThatTheSeriesRunsOpened() {
        quiet = "running"

        guard.deleteLeft()

        assertEquals(emptyList<String>(), run.adb.commands)
        guard.gave("t1")
        run.adb.hook("CONVERSATION_CONTROL") { "deleted=2 open=0 failed=0" }
        guard.deleteLeft()
        assertEquals(listOf(mapOf("do" to "delete-typed")), run.adb.hookCalls("CONVERSATION_CONTROL"))
        assertTrue(run.adb.hookCalls("CONVERSATION_STATE").isEmpty())
        assertTrue(open)
    }

    // The series' cleanup checks the hooks' mark on every round of its wait: when the owner continues the hooks' conversation
    // with his words during the wait, the mark goes, and the cleanup leaves the conversation open, sends no expire and does
    // not fail; when Kestrel cleared the mark between the cleanup's read and its expire-typed, Kestrel refuses, and the next
    // read ends the wait.
    @Test
    fun theSeriesCleanupLeavesAConversationOpenThatTheOwnerContinuedDuringItsWait() {
        guard.gave("t1")
        var reads = 0
        run.adb.hook("CONVERSATION_STATE") {
            // The owner speaks after the first read.
            if (++reads == 2) typed = false
            "open=yes continued=no commands=2 goal=none quiet=$quiet quietLeft=none replyChannel=voice testMode=on typed=${if (typed) "yes" else "no"}"
        }

        guard.deleteLeft()

        assertTrue(open)
        assertEquals(listOf("delete-typed", "delete-typed"), run.adb.hookCalls("CONVERSATION_CONTROL").map { it["do"] })

        // The mark goes between the read, which still shows it with the quiet clock running, and expire-typed.
        typed = true
        quiet = "running"
        reads = 0
        run.adb.hook("CONVERSATION_STATE") {
            reads++
            "open=yes continued=no commands=2 goal=none quiet=running quietLeft=10 replyChannel=voice testMode=on typed=${if (reads == 1) "yes" else "no"}"
        }
        run.adb.hook("CONVERSATION_CONTROL") { extras ->
            when (extras["do"]) {
                "delete-typed" -> if (reads == 0) "deleted=0 open=1 failed=0" else "deleted=0 open=0 failed=0"
                else -> "expire=not-typed"
            }
        }
        guard.deleteLeft()
        assertTrue(open)
        assertEquals(listOf("delete-typed", "delete-typed", "delete-typed", "expire-typed", "delete-typed"), run.adb.hookCalls("CONVERSATION_CONTROL").map { it["do"] })
    }

    // The hooks' conversation closes by itself during the cleanup's wait, and the owner opens his own: the cleanup sees no
    // mark on it and leaves it open.
    @Test
    fun theSeriesCleanupLeavesTheOwnersNewConversationOpenAfterTheHooksOneClosed() {
        guard.gave("t1")
        var sleeps = 0
        // The hooks' conversation holds its quiet clock for a long final reply; after two rounds it has closed and the
        // owner's own is open, with its quiet clock running.
        run.clock.onSleep = {
            if (++sleeps == 2) {
                typed = false
                quiet = "running"
            }
        }

        guard.deleteLeft()

        assertTrue(open)
        assertTrue(run.adb.hookCalls("CONVERSATION_CONTROL").none { it["do"]!!.startsWith("expire") })
    }

    // The waits measure the clock, so a slow adb cannot stretch them: on a device whose every command takes 7 s, or that
    // never answers, close ends at its 60 s, the series' cleanup at its 300 s and stopGoal's wait at its 15 s.
    @Test
    fun theWaitsEndAtTheirLimitsHoweverSlowAdbIs() {
        for (latency in listOf(7.seconds, 2.minutes)) {
            run.adb.slow(run.clock, latency)
            open = true
            quiet = "held"
            goal = "none"

            assertEquals(60.seconds, timed { assertThrows(SetupFailed::class.java) { guard.close() } })
            assertEquals(5.minutes, timed { assertThrows(SetupFailed::class.java) { guard.close(ConversationGuard.CLEANUP) } })
        }

        run.adb.slow(run.clock, 7.seconds)
        goal = "interrupted"
        stopEnds = false
        val start = run.clock.time
        assertEquals(GoalStop.OPEN, guard.stopGoal())
        // A read and the stop take 7 s each; then a pause of a second no longer fits before the 15 s.
        assertEquals(14.seconds, run.clock.time - start)
    }

    private fun timed(wait: () -> Unit): kotlin.time.Duration {
        val start = run.clock.time
        wait()
        return run.clock.time - start
    }
}
