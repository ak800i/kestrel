package dev.kestrel.app.listen

import dev.kestrel.voice.listen.Recognition
import dev.kestrel.voice.speak.Spoke
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BoostWordSourceTest {
    private val reports = mutableListOf<Pair<String, Throwable>>()

    private fun source(labels: AppLabels) = BoostWordSource(labels) { what, error -> reports += what to error }

    private fun installed(label: String) = AppLabel(label, system = false)

    private fun system(label: String) = AppLabel(label, system = true)

    @Test
    fun kestrelComesFirstAndThenTheNamesOfTheApps() = runTest {
        val labels = listOf("Settings", " Maps ", "Kestrel", "settings", "X", "Mi", "TV 2", "A.I.", "Viber").map { installed(it) }

        val words = source { labels }.words()

        // "X", "Mi" and "A.I." have fewer than three letters or digits; Kestrel's own app and "settings" are repeats.
        assertEquals(listOf("Kestrel", "Settings", "Maps", "TV 2", "Viber"), words)
        assertTrue(reports.isEmpty())
    }

    @Test
    fun theOwnersAppsComeBeforeTheSystemAppsSoThatTheCapDropsSystemAppsFirst() = runTest {
        val words = source { listOf(system("Phone"), installed("Revolut"), system("Camera"), installed("Glovo")) }.words()

        // Each group keeps the order in which the package manager lists the apps.
        assertEquals(listOf("Kestrel", "Revolut", "Glovo", "Phone", "Camera"), words)

        // sherpa-onnx keeps the first 100 phrases (BoostWords.MAX_PHRASES in :core:voice): "Kestrel" and 99 of the owner's
        // apps leave no room for a system app, wherever the package manager lists it.
        val owners = (1..99).map { installed("App $it") }
        val kept = source { listOf(system("Phone")) + owners + system("Camera") }.words().take(100)

        assertEquals(listOf("Kestrel") + owners.map { it.label }, kept)
    }

    @Test
    fun namesThatCannotBeReadLeaveKestrelAlone() = runTest {
        val words = source { throw SecurityException("Not allowed") }.words()

        assertEquals(listOf("Kestrel"), words)
        assertEquals(listOf("Reading the names of the apps"), reports.map { it.first })
        // Not the same instance: in Gradle's tests, kotlinx.coroutines hands on a copy of the error across withContext.
        assertTrue(reports.single().second is SecurityException)
    }

    @Test
    fun aCancelledReadIsNotReported() = runTest {
        // A failed engine load cancels the read; only the load's failure is reported.
        val thrown = runCatching { source { throw CancellationException() }.words() }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertTrue(reports.isEmpty())
    }

    @Test
    fun theOpenedSessionGetsTheNamesOfTheApps() = runTest {
        val session = FakeSession()
        var words: List<String>? = null

        val opened = openWithBoostWords(source { listOf(installed("Viber")) }) { boostWords ->
            words = boostWords()
            session
        }

        assertSame(session, opened)
        assertEquals(listOf("Kestrel", "Viber"), words)
        assertEquals(0, session.closes)
    }

    @Test
    fun aSessionThatOpenedJustBeforeACancellationIsClosed() = runTest {
        val session = FakeSession()
        var thrown: Throwable? = null
        lateinit var opening: Job
        opening = launch {
            thrown = runCatching {
                openWithBoostWords(source { emptyList() }) { boostWords ->
                    boostWords()
                    // The session is built, and the coroutine that opens it is cancelled before it gets the session.
                    opening.cancel()
                    session
                }
            }.exceptionOrNull()
        }
        opening.join()

        assertTrue(thrown is CancellationException)
        assertEquals(1, session.closes)
    }

    /** A session that only counts its closes. */
    private class FakeSession : Session {
        var closes = 0

        override var recognition = Recognition.NONE

        override fun startMicrophone() = Unit

        override fun stopMicrophone() = Unit

        override fun speak(text: String): Deferred<Spoke> = throw UnsupportedOperationException()

        override fun stopSpeaking() = Unit

        override suspend fun close() {
            closes++
        }
    }
}
