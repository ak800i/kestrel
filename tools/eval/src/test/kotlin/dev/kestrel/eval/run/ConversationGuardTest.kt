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

    // Whether the open conversation carries the hooks' mark (typed=), which Kestrel checks again at expire-typed.
    private var typed = true

    init {
        run.adb.hook("CONVERSATION_STATE") {
            "open=${if (open) "yes" else "no"} continued=no commands=1 goal=$goal quiet=$quiet quietLeft=none replyChannel=voice testMode=on " +
                "typed=${if (open && typed) "yes" else "no"}"
        }
        run.adb.hook("CONVERSATION_CONTROL") { extras ->
            when (extras["do"]) {
                "delete-typed" -> if (open && typed) "deleted=0 open=1 failed=0" else "deleted=1 open=0 failed=0"
                "expire-typed" -> if (open && typed) expire() else "expire=not-typed"
                else -> expire()
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
    // run waits until its own quiet time has closed it, and fails its setup when it stays open or holds the owner's goal.
    @Test
    fun aRunWaitsForTheOwnersConversationToCloseByItselfAndNeverEndsIt() {
        typed = false
        quiet = "running"
        var sleeps = 0
        // The owner's quiet time closes his conversation after five rounds.
        run.clock.onSleep = { if (++sleeps == 5) open = false }

        guard.close()

        assertTrue(run.adb.hookCalls("CONVERSATION_CONTROL").isEmpty())
        open = true
        run.clock.onSleep = {}
        assertEquals(
            "The owner's conversation did not close by itself within 60 s; the evaluation never ends it.",
            assertThrows(SetupFailed::class.java) { guard.close() }.message,
        )
        goal = "acting"
        assertEquals(
            "The owner's conversation has a goal open (acting), which the evaluation never ends.",
            assertThrows(SetupFailed::class.java) { guard.close() }.message,
        )
        assertTrue(run.adb.hookCalls("CONVERSATION_CONTROL").isEmpty())
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

    @Test
    fun stopGoalStopsAGoalThatIsOpenAndDoesNothingWithoutOne() {
        var stops = 0
        assertTrue(guard.stopGoal { stops++ })
        assertEquals(0, stops)

        goal = "paused-at-limit"
        assertTrue(guard.stopGoal { stops++; goal = "none" })
        assertEquals(1, stops)

        goal = "interrupted"
        assertFalse(guard.stopGoal { stops++ })
        assertEquals(2, stops)
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
        var stopped = run.clock.time
        assertFalse(guard.stopGoal { stopped = run.clock.time })
        assertEquals(15.seconds, run.clock.time - stopped)
    }

    private fun timed(wait: () -> Unit): kotlin.time.Duration {
        val start = run.clock.time
        wait()
        return run.clock.time - start
    }
}
