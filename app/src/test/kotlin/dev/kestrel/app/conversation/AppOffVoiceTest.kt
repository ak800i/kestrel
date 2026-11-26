package dev.kestrel.app.conversation

import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.speak.Spoke
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kestrel's voice with listening off, and the one stop rule for it (design 10.6 and 11 of sub-project 7; plan 7F). */
@OptIn(ExperimentalCoroutinesApi::class)
class AppOffVoiceTest {
    private val voice = FakeVoice()
    private var loads = 0
    private var loading: CompletableDeferred<Unit>? = null
    private var applying: CompletableDeferred<Unit>? = null
    private val reports = mutableListOf<Pair<String, Throwable>>()

    private fun TestScope.offVoice() = AppOffVoice(
        load = {
            loads++
            loading?.await()
            voice
        },
        options = { VoiceOptions() },
        foreground = { block ->
            applying?.await()
            block()
        },
        scope = backgroundScope,
        report = { what, error -> reports += what to error },
        idleMillis = IDLE_MILLIS,
    )

    // The idle unload runs in the background scope, so the test lets its time pass and runs what is due.
    private fun TestScope.afterIdle() {
        advanceTimeBy(IDLE_MILLIS + 1)
        runCurrent()
    }

    @Test
    fun aStopWhileTheVoiceLoadsKeepsTheReplyFromStarting() = runTest {
        val offVoice = offVoice()
        loading = CompletableDeferred()
        val speaking = launch { offVoice.speak("Done.") }
        runCurrent()

        offVoice.stop()
        loading!!.complete(Unit)
        runCurrent()

        assertTrue(speaking.isCompleted)
        assertEquals(emptyList<String>(), voice.spoken)
        // The stop held only the reply that was asked for before it.
        voice.finishes = true
        offVoice.speak("Sent.")
        assertEquals(listOf("Sent."), voice.spoken)
        assertEquals(1, loads)
    }

    @Test
    fun aStopWhileTheServiceTakesMediaPlaybackKeepsTheReplyFromStarting() = runTest {
        val offVoice = offVoice()
        applying = CompletableDeferred()
        val speaking = launch { offVoice.speak("Done.") }
        runCurrent()

        offVoice.stop()
        applying!!.complete(Unit)
        runCurrent()

        assertTrue(speaking.isCompleted)
        assertEquals(emptyList<String>(), voice.spoken)
        afterIdle()
        assertEquals(1, voice.closes)
    }

    @Test
    fun aStopEndsTheReplyThatPlaysAndTheVoiceUnloadsOnceIdle() = runTest {
        val offVoice = offVoice()
        val speaking = launch { offVoice.speak("Done.") }
        runCurrent()
        assertEquals(listOf("Done."), voice.spoken)

        offVoice.stop()
        runCurrent()

        assertTrue(speaking.isCompleted)
        assertEquals(1, voice.stops)
        assertEquals(0, voice.closes)
        afterIdle()
        assertEquals(1, voice.closes)
    }

    // The owner's typed answer to a question cancels the question's spoken copy (ConversationOwner.race): its words end, and
    // the voice still unloads.
    @Test
    fun aCancelledReplyEndsAndTheVoiceUnloadsOnceIdle() = runTest {
        val offVoice = offVoice()
        val speaking = launch { offVoice.speak("Shall I send it to Mila?") }
        runCurrent()

        speaking.cancel()
        runCurrent()

        assertEquals(1, voice.stops)
        afterIdle()
        assertEquals(1, voice.closes)
    }

    // A failure is reported by its class only (the privacy rule), and the words stay on the bar.
    @Test
    fun aVoiceThatFailsToSpeakIsReportedAndUnloadsOnceIdle() = runTest {
        val offVoice = offVoice()
        voice.failure = IllegalStateException("The audio output failed")

        offVoice.speak("Done.")

        assertEquals(1, reports.size)
        assertTrue(reports.single().second is IllegalStateException)
        afterIdle()
        assertEquals(1, voice.closes)
    }

    private class FakeVoice : LoadedVoice {
        val spoken = mutableListOf<String>()
        var stops = 0
        var closes = 0
        var failure: Exception? = null

        // While false, a reply plays until stop() ends it.
        var finishes = false
        private var playing: CompletableDeferred<Spoke>? = null

        override fun speak(text: String): Deferred<Spoke> {
            spoken += text
            val reply = CompletableDeferred<Spoke>()
            val failed = failure
            when {
                failed != null -> reply.completeExceptionally(failed)
                finishes -> reply.complete(Spoke.FINISHED)
                else -> playing = reply
            }
            return reply
        }

        override fun stop() {
            stops++
            playing?.complete(Spoke.STOPPED)
            playing = null
        }

        override suspend fun close() {
            closes++
        }
    }

    private companion object {
        const val IDLE_MILLIS = 30_000L
    }
}
