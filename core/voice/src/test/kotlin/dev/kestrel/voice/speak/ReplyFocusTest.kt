package dev.kestrel.voice.speak

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyFocusTest {
    private val focus = FakeFocus()
    private val replies = ReplyFocus(focus)

    @Test
    fun aReplyTakesTheFocusAndGivesItUpWhenItEnds() {
        replies.asked()
        assertTrue(replies.take())
        replies.finish()

        assertEquals(listOf("request", "abandon"), focus.calls)
    }

    @Test
    fun theFocusStaysBetweenQueuedReplies() {
        replies.asked()
        replies.asked()
        assertTrue(replies.take())
        replies.finish()
        // The second reply's turn comes while Kestrel still holds the focus.
        assertTrue(replies.take())
        replies.finish()

        assertEquals(listOf("request", "abandon"), focus.calls)
    }

    @Test
    fun theNextReplyAsksAgainAfterARefusal() {
        focus.granted = false
        replies.asked()
        assertFalse(replies.take())
        replies.finish()
        focus.granted = true
        replies.asked()
        assertTrue(replies.take())
        replies.finish()

        // After the refusal there is nothing to give up.
        assertEquals(listOf("request", "request", "abandon"), focus.calls)
    }

    @Test
    fun aTransientOrLastingLossStopsTheReplyAndGivesTheFocusUpOnce() {
        // A call, an app that asks for exclusive transient focus, or another assistant.
        for (loss in listOf(AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) {
            val lost = FakeFocus()
            val reply = ReplyFocus(lost)
            reply.asked()
            reply.take()

            assertEquals("Loss $loss", FocusChange.STOP, reply.changed(loss))
            reply.finish()
            assertEquals(listOf("request", "abandon"), lost.calls)
        }
    }

    @Test
    fun aLossThatLetsKestrelDuckKeepsTheReplyGoingMoreQuietly() {
        replies.asked()
        replies.take()

        // A notification's sound or a navigation prompt: Kestrel speaks on, more quietly, and keeps the focus.
        assertEquals(FocusChange.LOWER, replies.changed(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
        assertEquals(ReplyFocus.QUIET_VOLUME, replies.volume(), 0f)
        assertEquals(listOf("request"), focus.calls)
        assertEquals(FocusChange.RESTORE, replies.changed(AudioManager.AUDIOFOCUS_GAIN))
        assertEquals(1f, replies.volume(), 0f)
        replies.finish()

        assertEquals(listOf("request", "abandon"), focus.calls)
    }

    @Test
    fun aGainOrAChangeWithoutTheFocusStopsNothing() {
        assertEquals(FocusChange.NONE, replies.changed(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        replies.asked()
        replies.take()

        assertEquals(FocusChange.NONE, replies.changed(AudioManager.AUDIOFOCUS_GAIN))
        assertEquals(listOf("request"), focus.calls)
        // After a loss, the next reply asks Android again.
        assertEquals(FocusChange.STOP, replies.changed(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        replies.asked()
        assertTrue(replies.take())
        assertEquals(listOf("request", "abandon", "request"), focus.calls)
    }

    private class FakeFocus : AudioFocus {
        val calls = mutableListOf<String>()
        var granted = true

        override fun request(): Boolean {
            calls += "request"
            return granted
        }

        override fun abandon() {
            calls += "abandon"
        }
    }
}
