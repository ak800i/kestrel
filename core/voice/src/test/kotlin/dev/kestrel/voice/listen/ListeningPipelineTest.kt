package dev.kestrel.voice.listen

import dev.kestrel.voice.Keyword
import dev.kestrel.voice.KeywordHit
import dev.kestrel.voice.KeywordSpotter
import dev.kestrel.voice.Recognizer
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.SpeechDetector
import dev.kestrel.voice.Transcript
import dev.kestrel.voice.Utterance
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

@OptIn(ExperimentalCoroutinesApi::class)
class ListeningPipelineTest {
    private val detector = FakeDetector()
    private val spotter = FakeSpotter()
    private val recognizer = FakeRecognizer()
    private var ownVoice = emptySet<Keyword>()
    private val events = mutableListOf<VoiceEvent>()
    private var fed = 0L

    @Test
    fun transcribesEachUtteranceWithTheBoostWords() = runTest {
        val pipeline = pipeline(boostWords = listOf("Viber"))

        detector.next.addLast(listOf(utterance(0f, 1f)))
        pipeline.feed()

        val heard = events.single() as VoiceEvent.Heard
        assertEquals("heard 16000", heard.text)
        assertFalse(heard.named)
        assertEquals(1f, heard.seconds)
        assertEquals(listOf(listOf("Viber")), recognizer.boostWords)
    }

    @Test
    fun pausedTranscribesOnlyUtterancesThatStartWithTheName() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.NAMED

        pipeline.feedUntil(1.4f)
        detector.next.addLast(listOf(utterance(0.2f, 0.5f)))
        pipeline.feed()
        pipeline.feedUntil(2f)
        spotter.next.addLast(listOf(KeywordHit(Keyword.KESTREL, 2f)))
        pipeline.feed()
        pipeline.feedUntil(3.3f)
        detector.next.addLast(listOf(utterance(1.6f, 1f)))
        pipeline.feed()
        pipeline.feedUntil(4.5f)

        assertEquals(listOf(VoiceEvent.Spotted(Keyword.KESTREL)), events.filterIsInstance<VoiceEvent.Spotted>())
        val heard = events.filterIsInstance<VoiceEvent.Heard>().single()
        assertTrue(heard.named)
        assertEquals("heard 16000", heard.text)
    }

    @Test
    fun whileKestrelSpeaksOnlyKeywordsCome() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.NONE

        detector.next.addLast(listOf(utterance(0f, 0.2f)))
        spotter.next.addLast(listOf(KeywordHit(Keyword.STOP, 0.02f)))
        pipeline.feed()

        assertEquals(listOf<VoiceEvent>(VoiceEvent.Spotted(Keyword.STOP)), events)
        assertEquals(0, recognizer.calls)
    }

    @Test
    fun kestrelsOwnKeywordsAreIgnored() = runTest {
        val pipeline = pipeline()
        ownVoice = setOf(Keyword.STOP)
        pipeline.recognition = Recognition.NONE

        spotter.next.addLast(listOf(KeywordHit(Keyword.STOP, 0.02f), KeywordHit(Keyword.CANCEL, 0.02f)))
        pipeline.feed()

        assertEquals(listOf<VoiceEvent>(VoiceEvent.Spotted(Keyword.CANCEL)), events)
    }

    @Test
    fun speechThatBeganWhileKestrelSpokeIsCutWhereKestrelStopped() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.NONE
        pipeline.feedUntil(1f)
        pipeline.recognition = Recognition.ALL

        pipeline.feedUntil(2.2f)
        detector.next.addLast(listOf(utterance(0.5f, 1f)))
        pipeline.feed()
        detector.next.addLast(listOf(utterance(1.6f, 0.8f)))
        pipeline.feed()

        // The first utterance keeps its half second after 1 s, when Kestrel stopped speaking.
        assertEquals(listOf("heard 8000", "heard 12800"), events.filterIsInstance<VoiceEvent.Heard>().map { it.text })
    }

    @Test
    fun kestrelsOwnVoiceBeforeTheResumeIsDropped() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.NONE
        pipeline.feedUntil(1f)
        pipeline.recognition = Recognition.ALL

        detector.next.addLast(listOf(utterance(0.1f, 0.5f)))
        pipeline.feed()
        pipeline.feedUntil(1.6f)
        // Only 0.1 s of this one comes after 1 s.
        detector.next.addLast(listOf(utterance(0.3f, 0.8f)))
        pipeline.feed()

        assertEquals(0, recognizer.calls)
    }

    @Test
    fun aNameHeardBeforeKestrelSpokeDoesNotKeepKestrelsOwnVoice() = runTest {
        val pipeline = pipeline()
        spotter.next.addLast(listOf(KeywordHit(Keyword.KESTREL, 0.02f)))
        pipeline.feed()
        // Kestrel answers "Yes?" until 1 s, and the microphone hears it from 0.4 s.
        pipeline.recognition = Recognition.NONE
        pipeline.feedUntil(1f)
        pipeline.recognition = Recognition.ALL
        pipeline.feedUntil(1.5f)
        detector.next.addLast(listOf(utterance(0.4f, 0.4f)))
        pipeline.feed()

        assertEquals(0, recognizer.calls)
    }

    @Test
    fun anUtteranceThatBeganWithKestrelsVoiceIsDroppedAfterAnInterruption() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.NONE
        pipeline.feedUntil(2.6f)
        spotter.next.addLast(listOf(KeywordHit(Keyword.KESTREL, 2.6f)))
        pipeline.feed()
        pipeline.recognition = Recognition.ALL

        // Kestrel's voice from 0.2 s, with the owner's "Kestrel, open the settings" over its end, makes one utterance.
        pipeline.feedUntil(4.7f)
        detector.next.addLast(listOf(utterance(0.2f, 3.8f)))
        pipeline.feed()

        assertEquals(0, recognizer.calls)
    }

    @Test
    fun anUtteranceThatStartsWithTheInterruptingNameCounts() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.NONE
        pipeline.feedUntil(2.6f)
        spotter.next.addLast(listOf(KeywordHit(Keyword.KESTREL, 2.6f)))
        pipeline.feed()
        pipeline.recognition = Recognition.ALL

        // An echo canceller kept Kestrel's voice out, so the utterance starts with the owner's "Kestrel" at 1.8 s.
        pipeline.feedUntil(4.7f)
        detector.next.addLast(listOf(utterance(1.8f, 2.2f)))
        pipeline.feed()
        pipeline.feedUntil(6f)

        val heard = events.filterIsInstance<VoiceEvent.Heard>().single()
        assertTrue(heard.named)
        assertEquals("heard 35200", heard.text)
    }

    @Test
    fun aFailedTranscriptionIsReportedAndTheNextOneWorks() = runTest {
        val pipeline = pipeline()
        recognizer.failNext = true

        detector.next.addLast(listOf(utterance(0f, 0.5f)))
        pipeline.feed()
        detector.next.addLast(listOf(utterance(0.5f, 0.25f)))
        pipeline.feed()

        assertTrue(events[0] is VoiceEvent.Failed)
        assertEquals("heard 4000", (events[1] as VoiceEvent.Heard).text)
    }

    @Test
    fun anEmptyTranscriptIsPassedOn() = runTest {
        val pipeline = pipeline()
        recognizer.blankNext = true

        detector.next.addLast(listOf(utterance(0f, 0.5f)))
        pipeline.feed()

        assertEquals("", (events.single() as VoiceEvent.Heard).text)
    }

    @Test
    fun pauseDropsTheUtteranceInProgressAndKeepsThePositions() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.NAMED
        pipeline.feedUntil(2f)

        pipeline.pause()
        // After the pause the detector counts from 0 again, so its 0.1 s is 2.1 s since the audio started.
        pipeline.feedUntil(2.4f)
        spotter.next.addLast(listOf(KeywordHit(Keyword.KESTREL, 0.4f)))
        pipeline.feed()
        pipeline.feedUntil(3.2f)
        detector.next.addLast(listOf(utterance(0.1f, 1f)))
        pipeline.feed()
        pipeline.feedUntil(5f)

        assertEquals(1, detector.finished)
        assertTrue(events.filterIsInstance<VoiceEvent.Heard>().single().named)
    }

    private fun TestScope.pipeline(boostWords: List<String> = emptyList()): ListeningPipeline {
        val unconfined = UnconfinedTestDispatcher(testScheduler)
        val pipeline = ListeningPipeline(detector, spotter, recognizer, { boostWords }, { it in ownVoice }, backgroundScope, unconfined)
        backgroundScope.launch(unconfined) { pipeline.events.collect { events += it } }
        return pipeline
    }

    private fun ListeningPipeline.feed() {
        accept(FloatArray(FRAME))
        fed += FRAME
    }

    private fun ListeningPipeline.feedUntil(seconds: Float) {
        while (fed < at(seconds)) feed()
    }

    private fun at(seconds: Float): Long = (seconds * SAMPLE_RATE).roundToLong()

    private fun utterance(start: Float, seconds: Float) = Utterance(at(start), FloatArray(at(seconds).toInt()))

    private class FakeDetector : SpeechDetector {
        val next = ArrayDeque<List<Utterance>>()
        var finished = 0
        override val hearsSpeech = false

        override fun accept(samples: FloatArray): List<Utterance> = next.removeFirstOrNull() ?: emptyList()

        override fun finish(): List<Utterance> {
            finished++
            return emptyList()
        }

        override fun close() = Unit
    }

    private class FakeSpotter : KeywordSpotter {
        val next = ArrayDeque<List<KeywordHit>>()

        override fun accept(samples: FloatArray): List<KeywordHit> = next.removeFirstOrNull() ?: emptyList()

        override fun close() = Unit
    }

    private class FakeRecognizer : Recognizer {
        var calls = 0
        var failNext = false
        var blankNext = false
        val boostWords = mutableListOf<List<String>>()

        override fun transcribe(samples: FloatArray, boostWords: List<String>): Transcript {
            calls++
            this.boostWords += listOf(boostWords)
            if (failNext) {
                failNext = false
                throw IllegalStateException("The decoder failed")
            }
            if (blankNext) {
                blankNext = false
                return Transcript("", pieces = 0, lastWordEnd = 0f)
            }
            return Transcript("heard ${samples.size}", pieces = 2, lastWordEnd = samples.size / SAMPLE_RATE.toFloat())
        }

        override fun close() = Unit
    }

    private companion object {
        const val FRAME = SAMPLE_RATE / 50
    }
}
