package dev.kestrel.eval.run

import dev.kestrel.eval.config.LocalConfig
import dev.kestrel.eval.config.Redactions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds

/** The restores: newest first, each once, a failure does not stop the next one, and the shutdown hook (design D3 of sub-project 6). */
class RestoresTest {
    private val console = FakeConsole()
    private val hooks = mutableListOf<Thread>()
    private val restores = Restores(console, Redactions(LocalConfig.EMPTY)) { hooks.add(it) }

    @Test
    fun restoresRunNewestFirstEachOnceAndAFailureDoesNotStopTheNext() {
        val ran = mutableListOf<String>()
        restores.push("the first", "do one") { ran.add("first") }
        restores.push("the second", "do two") { throw IllegalStateException("adb: device offline for pera.test@example.com") }
        restores.push("the third", "do three") { ran.add("third") }

        val failures = restores.runAll()

        assertEquals(listOf("third", "first"), ran)
        assertEquals(listOf(RestoreFailure("the second", "do two", "adb: device offline for <address>")), failures)
        assertEquals(emptyList<RestoreFailure>(), restores.runAll())
        assertEquals(listOf("third", "first"), ran)
        restores.report(failures)
        assertEquals(listOf("Not put back: the second (adb: device offline for <address>). By hand: do two"), console.warnings)
    }

    @Test
    fun aRunPutsBackOnlyWhatItPushed() {
        val ran = mutableListOf<String>()
        restores.push("the command's", "") { ran.add("command") }
        val mark = restores.mark()
        restores.push("the run's", "") { ran.add("run") }

        restores.runDownTo(mark)

        assertEquals(listOf("run"), ran)
        assertEquals(1, restores.size)
    }

    @Test
    fun theFirstPushRegistersOneShutdownHookThatRunsWhatIsLeft() {
        val ran = mutableListOf<String>()
        restores.push("the hotspot", "Turn the hotspot off.") { ran.add("hotspot") }
        restores.push("the alias", "Remove it.") { ran.add("alias") }

        hooks.single().run()
        restores.runAll()

        assertEquals(1, hooks.size)
        assertEquals(listOf("alias", "hotspot"), ran)
    }

    @Test
    fun aRestoreThatThrowsAnErrorDoesNotStopTheNext() {
        val ran = mutableListOf<String>()
        restores.push("the first", "do one") { ran.add("first") }
        restores.push("the broken", "do two") { throw StackOverflowError() }

        val failures = restores.runAll()

        assertEquals(listOf("first"), ran)
        assertEquals(listOf(RestoreFailure("the broken", "do two", "StackOverflowError")), failures)
    }

    @Test
    fun aPushAfterTheShutdownHookStartedThrows() {
        restores.push("the hotspot", "Turn the hotspot off.") {}

        hooks.single().run()

        assertTrue(restores.stopping)
        assertThrows(Stopping::class.java) { restores.push("the next run's alias", "Remove it.") {} }
        assertEquals(0, restores.size)
    }

    @Test
    fun theShutdownHookWaitsForARestoreThatTheMainThreadIsRunningAndReportsItsFailure() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        restores.push("the task stopped", "Say 'Kestrel, stop'.") {
            started.countDown()
            release.await()
            throw IllegalStateException("the task did not stop")
        }
        val main = thread { restores.runAll() }
        started.await()

        val hook = hooks.single().apply { start() }
        hook.join(300)
        assertTrue(hook.isAlive)
        release.countDown()
        hook.join(5_000)
        main.join(5_000)

        assertFalse(hook.isAlive)
        assertEquals(listOf("Not put back: the task stopped (the task did not stop). By hand: Say 'Kestrel, stop'."), console.warnings)
    }

    @Test
    fun theShutdownHookWaitsForAChangeUnderWayBeforeItRunsTheRestoresAndNoChangeStartsAfterIt() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        restores.push("the test photos", "Delete the folder.") { events.add("restore") }
        val main = thread {
            restores.change("the test photos", "Delete the folder.") {
                started.countDown()
                release.await()
                events.add("card 1")
            }
        }
        started.await()

        val hook = hooks.single().apply { start() }
        hook.join(300)
        assertTrue(hook.isAlive)
        assertEquals(emptyList<String>(), events.toList())
        release.countDown()
        hook.join(5_000)
        main.join(5_000)

        assertEquals(listOf("card 1", "restore"), events.toList())
        assertThrows(Stopping::class.java) { restores.change("the test photos", "Delete the folder.") { events.add("card 2") } }
        assertEquals(listOf("card 1", "restore"), events.toList())
    }

    @Test
    fun aChangeStillUnderWayWhenTheHookStopsWaitingIsNotPutBack() {
        val bounded = Restores(console, Redactions(LocalConfig.EMPTY), 100.milliseconds) { hooks.add(it) }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        bounded.push("the permissions", "Take back READ_MEDIA_VIDEO.") {}
        val main = thread {
            bounded.change("READ_MEDIA_VIDEO granted", "Take back READ_MEDIA_VIDEO.") {
                started.countDown()
                release.await()
            }
        }
        started.await()

        hooks.single().run()
        release.countDown()
        main.join(5_000)

        assertEquals(
            listOf("Not put back: READ_MEDIA_VIDEO granted (it was still being made when :tools:eval stopped). By hand: Take back READ_MEDIA_VIDEO."),
            console.warnings,
        )
    }

    @Test
    fun aRestoreStillRunningWhenTheHookStopsWaitingIsNotPutBack() {
        val bounded = Restores(console, Redactions(LocalConfig.EMPTY), 100.milliseconds) { hooks.add(it) }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        bounded.push("the test location", "Turn the mock location off.") {
            started.countDown()
            release.await()
        }
        val main = thread { bounded.runAll() }
        started.await()

        hooks.single().run()
        release.countDown()
        main.join(5_000)

        assertEquals(
            listOf("Not put back: the test location (it was still running when :tools:eval stopped). By hand: Turn the mock location off."),
            console.warnings,
        )
    }
}
