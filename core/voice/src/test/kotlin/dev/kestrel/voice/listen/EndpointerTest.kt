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
        // The owner speaks again at 2.9 s, 1.4 s after the end. The detector notices it 0.314 s later, so from 3.214 s it
        // hears speech, and the held utterance waits past 3.35 s.
        assertEquals(none, endpointer.accept(emptyList(), emptyList(), hearsSpeech = false, position = at(3.15f)))
        assertEquals(none, endpointer.accept(emptyList(), emptyList(), hearsSpeech = true, position = at(3.4f)))
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
    fun anUtteranceThatStartsOneAndAHalfSecondsAfterTheEndIsNotJoined() {
        endpointer.accept(emptyList(), listOf(at(1f)), hearsSpeech = true, position = at(1f))
        endpointer.accept(listOf(utterance(0.5f, 1f)), emptyList(), hearsSpeech = false, position = at(2.2f))
        // The owner speaks again at 3 s, 1.5 s after the end. The detector notices it 0.314 s later, so at 3.36 s it hears
        // speech, and the held utterance waits.
        assertEquals(none, endpointer.accept(emptyList(), emptyList(), hearsSpeech = true, position = at(3.36f)))

        val ready = endpointer.accept(listOf(utterance(3f, 1f)), emptyList(), hearsSpeech = false, position = at(4.7f))

        assertEquals(listOf(at(0.5f), at(3f)), ready.map { it.startSample })
        assertEquals(listOf(true, false), ready.map { it.named })
    }

    @Test
    fun aNameAfterAnotherWordStillNamesTheUtterance() {
        // "Hey, Kestrel, ...": the spotter's hit comes 1.8 s after the start.
        endpointer.accept(emptyList(), listOf(at(2.3f)), hearsSpeech = true, position = at(2.3f))
        assertEquals(none, endpointer.accept(listOf(utterance(0.5f, 3f)), emptyList(), hearsSpeech = false, position = at(4.2f)))

        assertTrue(endpointer.accept(emptyList(), emptyList(), hearsSpeech = false, position = at(5.35f)).single().named)
    }

    @Test
    fun theNameMustComeAtTheStart() {
        // 2.05 s after the start, just outside the window.
        endpointer.accept(emptyList(), listOf(at(2.55f)), hearsSpeech = true, position = at(2.55f))

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

    @Test
    fun withTheHoldAnUtteranceWithoutTheNameWaitsAndJoinsTheNextOne() {
        // The answer to "Yes?" ends at 1.5 s, and the owner goes on at 2.9 s, 1.4 s after the end.
        assertEquals(
            none,
            endpointer.accept(listOf(utterance(0.5f, 1f, value = 0.5f)), emptyList(), hearsSpeech = false, position = at(2.2f), holdAll = true),
        )
        assertEquals(none, endpointer.accept(emptyList(), emptyList(), hearsSpeech = true, position = at(3.4f), holdAll = true))
        assertEquals(
            none,
            endpointer.accept(listOf(utterance(2.9f, 1f, value = 0.25f)), emptyList(), hearsSpeech = false, position = at(4.6f), holdAll = true),
        )

        val speech = endpointer.accept(emptyList(), emptyList(), hearsSpeech = false, position = at(5.75f), holdAll = true).single()

        assertFalse(speech.named)
        assertEquals(at(0.5f), speech.startSample)
        assertEquals(at(3.4f).toInt(), speech.samples.size)
        assertEquals(0.5f, speech.samples[0])
        assertEquals(0.25f, speech.samples.last())
    }

    @Test
    fun withTheHoldAnUtteranceWithoutTheNameGoesOutAfterTheLongerPause() {
        endpointer.accept(listOf(utterance(0.5f, 1f)), emptyList(), hearsSpeech = false, position = at(2.2f), holdAll = true)
        assertEquals(none, endpointer.accept(emptyList(), emptyList(), hearsSpeech = false, position = at(3.3f), holdAll = true))

        val ready = endpointer.accept(emptyList(), emptyList(), hearsSpeech = false, position = at(3.35f), holdAll = true)

        assertFalse(ready.single().named)
        assertEquals(at(0.5f), ready.single().startSample)
    }

    @Test
    fun aJoinedUtteranceIsNamedOnlyWhenItsFirstPartWas() {
        endpointer.accept(listOf(utterance(0.5f, 1f)), emptyList(), hearsSpeech = false, position = at(2.2f), holdAll = true)
        // The second part starts with "Kestrel" at 2.9 s, and the spotter reports the name at 3.4 s.
        endpointer.accept(emptyList(), listOf(at(3.4f)), hearsSpeech = true, position = at(3.4f), holdAll = true)
        endpointer.accept(listOf(utterance(2.9f, 1f)), emptyList(), hearsSpeech = false, position = at(4.6f), holdAll = true)

        val speech = endpointer.accept(emptyList(), emptyList(), hearsSpeech = false, position = at(5.75f), holdAll = true).single()

        assertFalse(speech.named)
        assertEquals(at(3.4f).toInt(), speech.samples.size)
    }

    @Test
    fun aHeldAnswerStillWaitsForItsPauseWhenTheHoldEnds() {
        endpointer.accept(listOf(utterance(0.5f, 1f)), emptyList(), hearsSpeech = false, position = at(2.2f), holdAll = true)

        // Kestrel no longer waits for the task, so recognition is no longer ANSWER.
        assertEquals(none, endpointer.accept(emptyList(), emptyList(), hearsSpeech = false, position = at(3.3f)))
        val ready = endpointer.accept(emptyList(), emptyList(), hearsSpeech = false, position = at(3.35f))

        assertFalse(ready.single().named)
    }

    @Test
    fun anUtteranceThatStartsBeforeHoldFromGoesOutAtOnceAndNothingJoinsIt() {
        // After an interruption, the pipeline drops what starts before its cut at 1.1 s: here Kestrel's voice.
        val first = endpointer.accept(
            listOf(utterance(0.2f, 2.7f)), emptyList(), hearsSpeech = false, position = at(3.6f), holdAll = true, holdFrom = at(1.1f),
        )
        assertEquals(at(0.2f), first.single().startSample)

        // The request starts 1 s after it ends, which is within the longer pause, but it is held on its own.
        assertEquals(
            none,
            endpointer.accept(
                listOf(utterance(3.9f, 1.1f)), emptyList(), hearsSpeech = false, position = at(5.7f), holdAll = true, holdFrom = at(1.1f),
            ),
        )
        val request = endpointer.accept(emptyList(), emptyList(), hearsSpeech = false, position = at(6.85f), holdAll = true, holdFrom = at(1.1f))
        assertEquals(at(3.9f), request.single().startSample)
        assertEquals(at(1.1f).toInt(), request.single().samples.size)
    }

    @Test
    fun aHeldUtteranceThatStartsBeforeHoldFromGoesOutAtOnceAndNothingJoinsIt() {
        // A named utterance is held while Kestrel speaks; then the pipeline's cut after an interruption moves past its start.
        endpointer.accept(emptyList(), listOf(at(1f)), hearsSpeech = true, position = at(1f))
        assertEquals(none, endpointer.accept(listOf(utterance(0.5f, 0.5f)), emptyList(), hearsSpeech = false, position = at(1.7f)))

        val ready = endpointer.accept(
            listOf(utterance(1.6f, 0.5f)), emptyList(), hearsSpeech = false, position = at(2.8f), holdAll = true, holdFrom = at(1.2f),
        )

        assertEquals(listOf(at(0.5f)), ready.map { it.startSample })
        val later = endpointer.finish().single()
        assertEquals(at(1.6f), later.startSample)
        assertEquals(at(0.5f).toInt(), later.samples.size)
    }

    private val none = emptyList<Speech>()

    private fun at(seconds: Float): Long = (seconds * SAMPLE_RATE).roundToLong()

    private fun utterance(start: Float, seconds: Float, value: Float = 0.5f) =
        Utterance(at(start), FloatArray(at(seconds).toInt()) { value })
}
