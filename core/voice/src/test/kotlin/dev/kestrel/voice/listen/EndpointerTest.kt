package dev.kestrel.voice.listen

import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.Utterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

class EndpointerTest {
    private val endpointer = Endpointer()

    @Test
    fun anUtteranceWithoutTheNameIsReadyAtOnce() {
        val ready = endpointer.accept(listOf(utterance(0.5f, 1f)), emptyList(), hearsSpeech = false, position = at(2.2f))

        val speech = ready.single()
        assertFalse(speech.named)
        assertEquals(at(0.5f), speech.startSample)
        assertEquals(at(1f).toInt(), speech.samples.size)
    }

    @Test
    fun anUtteranceThatStartsWithTheNameWaitsLongerForThePauseToEnd() {
        assertEquals(none, endpointer.accept(emptyList(), listOf(at(1f)), hearsSpeech = true, position = at(1f)))
        assertEquals(none, endpointer.accept(listOf(utterance(0.5f, 1f)), emptyList(), hearsSpeech = false, position = at(2.2f)))
        assertEquals(none, endpointer.accept(emptyList(), emptyList(), hearsSpeech = false, position = at(3.3f)))

        val ready = endpointer.accept(emptyList(), emptyList(), hearsSpeech = false, position = at(3.35f))

        assertTrue(ready.single().named)
    }

    @Test
    fun theNextUtteranceJoinsWhenItStartsWithinTheLongerPause() {
        endpointer.accept(emptyList(), listOf(at(1f)), hearsSpeech = true, position = at(1f))
        endpointer.accept(listOf(utterance(0.5f, 1f, value = 0.5f)), emptyList(), hearsSpeech = false, position = at(2.2f))
        // The owner speaks again at 2.9 s, 1.4 s after the end, and the detector notices it only about 0.28 s later.
        assertEquals(none, endpointer.accept(emptyList(), emptyList(), hearsSpeech = false, position = at(3.15f)))
        assertEquals(none, endpointer.accept(emptyList(), emptyList(), hearsSpeech = true, position = at(3.2f)))
        assertEquals(none, endpointer.accept(listOf(utterance(2.9f, 1f, value = 0.25f)), emptyList(), hearsSpeech = false, position = at(4.6f)))

        val speech = endpointer.accept(emptyList(), emptyList(), hearsSpeech = false, position = at(5.75f)).single()

        assertTrue(speech.named)
        assertEquals(at(0.5f), speech.startSample)
        assertEquals(at(3.4f).toInt(), speech.samples.size)
        assertEquals(0.5f, speech.samples[0])
        assertEquals(0f, speech.samples[at(1f).toInt()])
        assertEquals(0.25f, speech.samples[at(2.4f).toInt()])
        assertEquals(0.25f, speech.samples.last())
    }

    @Test
    fun anUtteranceThatStartsAfterTheLongerPauseIsSeparate() {
        endpointer.accept(emptyList(), listOf(at(1f)), hearsSpeech = true, position = at(1f))
        endpointer.accept(listOf(utterance(0.5f, 1f)), emptyList(), hearsSpeech = false, position = at(2.2f))

        val first = endpointer.accept(emptyList(), emptyList(), hearsSpeech = false, position = at(3.35f))
        val second = endpointer.accept(listOf(utterance(3.5f, 0.5f)), emptyList(), hearsSpeech = false, position = at(4.7f))

        assertTrue(first.single().named)
        assertFalse(second.single().named)
        assertEquals(at(3.5f), second.single().startSample)
    }

    @Test
    fun theNameMustComeAtTheStart() {
        endpointer.accept(emptyList(), listOf(at(3f)), hearsSpeech = true, position = at(3f))

        val ready = endpointer.accept(listOf(utterance(0.5f, 3.5f)), emptyList(), hearsSpeech = false, position = at(4.7f))

        assertFalse(ready.single().named)
    }

    @Test
    fun aJoinedUtteranceLastsAtMostThirtySeconds() {
        endpointer.accept(emptyList(), listOf(at(1f)), hearsSpeech = true, position = at(1f))
        endpointer.accept(listOf(utterance(0.5f, 20f)), emptyList(), hearsSpeech = false, position = at(21.2f))

        val ready = endpointer.accept(listOf(utterance(21.5f, 15f)), emptyList(), hearsSpeech = false, position = at(37.2f))

        assertEquals(listOf(true, false), ready.map { it.named })
    }

    @Test
    fun aHeldUtteranceIsReadyAfterThirtySecondsEvenWhileTheDetectorHearsSpeech() {
        endpointer.accept(emptyList(), listOf(at(1f)), hearsSpeech = true, position = at(1f))
        endpointer.accept(listOf(utterance(0.5f, 1f)), emptyList(), hearsSpeech = false, position = at(2.2f))

        assertEquals(none, endpointer.accept(emptyList(), emptyList(), hearsSpeech = true, position = at(30f)))

        assertTrue(endpointer.accept(emptyList(), emptyList(), hearsSpeech = true, position = at(30.5f)).single().named)
    }

    @Test
    fun finishReturnsTheHeldUtterance() {
        endpointer.accept(emptyList(), listOf(at(1f)), hearsSpeech = true, position = at(1f))
        endpointer.accept(listOf(utterance(0.5f, 1f)), emptyList(), hearsSpeech = false, position = at(2.2f))

        assertTrue(endpointer.finish().single().named)
        assertEquals(none, endpointer.finish())
    }

    private val none = emptyList<Speech>()

    private fun at(seconds: Float): Long = (seconds * SAMPLE_RATE).roundToLong()

    private fun utterance(start: Float, seconds: Float, value: Float = 0.5f) =
        Utterance(at(start), FloatArray(at(seconds).toInt()) { value })
}
