package dev.kestrel.eval.run

import dev.kestrel.eval.config.LocalConfig
import dev.kestrel.eval.config.Redactions
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
