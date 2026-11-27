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

    init {
        run.adb.hook("CONVERSATION_STATE") {
            "open=${if (open) "yes" else "no"} continued=no commands=1 goal=$goal quiet=$quiet quietLeft=none replyChannel=voice testMode=on"
        }
        run.adb.hook("CONVERSATION_CONTROL") { extras ->
            when (extras["do"]) {
                "delete-typed" -> if (open) "deleted=0 open=1 failed=0" else "deleted=1 open=0 failed=0"
                else -> {
                    if (extras["do"] == "expire" && quiet == "running") open = false
                    "expire=sent"
                }
            }
        }
    }

    @Test
    fun closeWaitsForTheFinalReplyThenEndsTheQuietTimeAndWaitsUntilTheConversationClosed() {
        var sleeps = 0
        // The final reply holds the quiet clock for two reads; then the clock runs.
        run.clock.onSleep = { if (++sleeps == 2) quiet = "running" }

        guard.close()

        assertFalse(open)
        assertEquals(listOf(mapOf("do" to "expire")), run.adb.hookCalls("CONVERSATION_CONTROL"))
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

    // Plan 7F's delete-typed, which deletes the conversations that any hook's input opened (its Task 13a and f23bd193): the
    // hooks' own conversations leave Kestrel's past conversations once the run's one is closed. One that a long final reply
    // keeps open is only warned about after a run, and the series' cleanup, which waits longer, fails when it is still open.
    @Test
    fun deleteRunDeletesTheHooksOwnConversationsAndWarnsAboutAnOpenOneWhichTheSeriesCleanupDeletesOrFailsOn() {
        val warnings = mutableListOf<String>()
        quiet = "running"

        guard.deleteRun { warnings += it }

        assertFalse(open)
        assertEquals(listOf(mapOf("do" to "expire"), mapOf("do" to "delete-typed")), run.adb.hookCalls("CONVERSATION_CONTROL"))
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
