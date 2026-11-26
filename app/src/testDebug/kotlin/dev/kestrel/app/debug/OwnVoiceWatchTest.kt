package dev.kestrel.app.debug

import dev.kestrel.app.conversation.LoadedVoice
import dev.kestrel.voice.speak.Spoke
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The device tests' watch on Kestrel's own voice (plan 7F). */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnVoiceWatchTest {
    private val voice = FakeVoice()
    private val watch = OwnVoiceWatch { text -> text.endsWith("?") }
    private val watched = watch.wrap(voice)

    // The whole-plan review's M4: the watch is the one count of what Kestrel's own voice spoke, so a held reply counts
    // as it ended for Kestrel: a stop ends it as stopped, never as heard.
    @Test
    fun aHeldReplyWaitsAfterItsAudioUntilAStopAndCountsAsStopped() {
        val reply = watched.speak("Which time?")
        assertTrue(watch.holding)
        assertTrue(watch.playing)

        voice.end(Spoke.FINISHED)

        assertFalse(reply.isCompleted)
        assertTrue(watch.holding)
        assertFalse(watch.playing)
        assertEquals(0, watch.ended(Spoke.FINISHED))

        watched.stop()

        assertEquals(Spoke.STOPPED, reply.getCompleted())
        assertFalse(watch.holding)
        assertEquals(0, watch.ended(Spoke.FINISHED))
        assertEquals(1, watch.ended(Spoke.STOPPED))
    }

    @Test
    fun aHeldReplyEndsAsSpokenOnRelease() {
        val reply = watched.speak("Which time?")
        voice.end(Spoke.FINISHED)

        watch.release()

        assertEquals(Spoke.FINISHED, reply.getCompleted())
        assertFalse(watch.holding)
        assertEquals(1, watch.ended(Spoke.FINISHED))
    }

    @Test
    fun aStopWhileTheHeldReplyPlaysEndsItAsThePlayerEndsIt() {
        val reply = watched.speak("Which time?")

        watched.stop()

        assertEquals(Spoke.STOPPED, reply.getCompleted())
        assertEquals(1, watch.ended(Spoke.STOPPED))
        assertFalse(watch.holding)
    }

    // Only words that were heard count as finished: a refused or interrupted reply is never held either.
    @Test
    fun otherRepliesEndAsTheirPlayerEndsThemAndCountByHowTheyEnded() {
        val first = watched.speak("On it.")
        voice.end(Spoke.FINISHED)
        val second = watched.speak("Done.")
        voice.end(Spoke.REFUSED)
        val third = watched.speak("Which time?")
        voice.end(Spoke.INTERRUPTED)

        assertEquals(Spoke.FINISHED, first.getCompleted())
        assertEquals(Spoke.REFUSED, second.getCompleted())
        assertEquals(Spoke.INTERRUPTED, third.getCompleted())
        assertEquals(1, watch.ended(Spoke.FINISHED))
        assertEquals(1, watch.ended(Spoke.REFUSED))
        assertEquals(1, watch.ended(Spoke.INTERRUPTED))
        assertFalse(watch.playing)
        assertFalse(watch.holding)
    }

    @Test
    fun afterReleaseAHeldReplyIsNoLongerHeld() {
        watch.release()
        val reply = watched.speak("Which time?")

        voice.end(Spoke.FINISHED)

        assertEquals(Spoke.FINISHED, reply.getCompleted())
    }

    private class FakeVoice : LoadedVoice {
        private var playing: CompletableDeferred<Spoke>? = null

        override fun speak(text: String): Deferred<Spoke> = CompletableDeferred<Spoke>().also { playing = it }

        fun end(spoke: Spoke) {
            checkNotNull(playing).complete(spoke)
            playing = null
        }

        override fun stop() {
            playing?.complete(Spoke.STOPPED)
            playing = null
        }

        override suspend fun close() = Unit
    }
}
