package dev.kestrel.voice.listen

import dev.kestrel.voice.Keyword
import dev.kestrel.voice.KeywordHit
import dev.kestrel.voice.KeywordSpotter
import dev.kestrel.voice.Recognizer
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.SpeechDetector
import dev.kestrel.voice.Transcript
import dev.kestrel.voice.Utterance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
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
        val pipeline = pipeline(boostWords = { listOf("Viber") })

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
        // The owner's "Kestrel" from 0.5 s names the first utterance, and Kestrel answers "Yes?" over it until 1 s.
        pipeline.feedUntil(0.5f)
        spotter.next.addLast(listOf(KeywordHit(Keyword.KESTREL, 0.5f)))
        pipeline.feed()
        pipeline.recognition = Recognition.NONE
        pipeline.feedUntil(1f)
        pipeline.recognition = Recognition.ANSWER

        pipeline.feedUntil(2.2f)
        detector.next.addLast(listOf(utterance(0.5f, 1f)))
        pipeline.feed()
        pipeline.feedUntil(3.8f)
        detector.next.addLast(listOf(utterance(3.1f, 0.8f)))
        pipeline.feed()
        // Each one is held for 1.85 s after its end, and the pause of 1.6 s between them is too long to join them.
        pipeline.feedUntil(6f)

        // The first utterance keeps its half second after 1 s, when Kestrel stopped speaking. The cut removed the name.
        val heard = events.filterIsInstance<VoiceEvent.Heard>()
        assertEquals(listOf("heard 8000", "heard 12800"), heard.map { it.text })
        assertEquals(listOf(false, false), heard.map { it.named })
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
        pipeline.recognition = Recognition.ANSWER
        pipeline.feedUntil(1.5f)
        detector.next.addLast(listOf(utterance(0.4f, 0.4f)))
        pipeline.feed()
        pipeline.feedUntil(3f)

        assertEquals(0, recognizer.calls)
        assertEquals(listOf(VoiceEvent.Spotted(Keyword.KESTREL), VoiceEvent.Dropped), events)
    }

    @Test
    fun anUtteranceThatBeganWithKestrelsVoiceIsDroppedAfterAnInterruption() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.NONE
        pipeline.feedUntil(2.6f)
        spotter.next.addLast(listOf(KeywordHit(Keyword.KESTREL, 2.6f)))
        pipeline.feed()
        pipeline.recognition = Recognition.ANSWER

        // Kestrel's voice from 0.2 s, with the owner's "Kestrel, open the settings" over its end, makes one utterance.
        pipeline.feedUntil(4.7f)
        detector.next.addLast(listOf(utterance(0.2f, 3.8f)))
        pipeline.feed()
        pipeline.feedUntil(7f)

        assertEquals(0, recognizer.calls)
        assertEquals(listOf(VoiceEvent.Spotted(Keyword.KESTREL), VoiceEvent.Dropped), events)
    }

    @Test
    fun theRequestAfterAnInterruptionIsNotJoinedToKestrelsVoice() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.NONE
        pipeline.feedUntil(2.6f)
        spotter.next.addLast(listOf(KeywordHit(Keyword.KESTREL, 2.6f)))
        pipeline.feed()
        // Kestrel stops speaking at 2.9 s and waits for the task.
        pipeline.feedUntil(2.9f)
        pipeline.recognition = Recognition.ANSWER

        // Kestrel's voice with the owner's "Kestrel" over its end, from 0.2 s to 2.9 s, ends after 0.7 s of silence.
        pipeline.feedUntil(3.6f)
        detector.next.addLast(listOf(utterance(0.2f, 2.7f)))
        pipeline.feed()
        // The request follows 1 s after Kestrel stopped, from 3.9 s to 5 s. The detector hears it from 0.3 s after its
        // start until it ends the utterance after 0.7 s of silence.
        pipeline.feedUntil(4.2f)
        detector.hearsSpeech = true
        pipeline.feedUntil(5.7f)
        detector.hearsSpeech = false
        detector.next.addLast(listOf(utterance(3.9f, 1.1f)))
        pipeline.feed()
        pipeline.feedUntil(8f)

        assertEquals(listOf("Spotted", "Dropped", "SpeechStarted", "Heard"), events.map { it::class.simpleName })
        val heard = events.filterIsInstance<VoiceEvent.Heard>().single()
        assertEquals("heard 17600", heard.text)
        assertFalse(heard.named)
    }

    @Test
    fun anUtteranceThatStartsWithTheInterruptingNameCounts() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.NONE
        pipeline.feedUntil(2.6f)
        spotter.next.addLast(listOf(KeywordHit(Keyword.KESTREL, 2.6f)))
        pipeline.feed()
        pipeline.recognition = Recognition.ANSWER

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
    fun anAnswerToYesIsHeldAndJoinedToTheNextUtterance() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.ANSWER

        // "Open the ..." from 0.5 s to 1.5 s, and "... settings" from 2.9 s, after a pause of 1.4 s.
        pipeline.feedUntil(2.2f)
        detector.next.addLast(listOf(utterance(0.5f, 1f)))
        pipeline.feed()
        pipeline.feedUntil(3.2f)
        detector.hearsSpeech = true
        pipeline.feedUntil(4.6f)
        detector.hearsSpeech = false
        detector.next.addLast(listOf(utterance(2.9f, 1f)))
        pipeline.feed()
        pipeline.feedUntil(6.5f)

        // One transcript of both parts and the pause between them: 1 s, 1.4 s and 1 s.
        val heard = events.filterIsInstance<VoiceEvent.Heard>().single()
        assertEquals("heard 54400", heard.text)
        assertFalse(heard.named)
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

    @Test
    fun pauseDropsAHeldNamedUtterance() = runTest {
        val pipeline = pipeline()
        spotter.next.addLast(listOf(KeywordHit(Keyword.KESTREL, 0.02f)))
        pipeline.feed()
        pipeline.feedUntil(1.5f)
        // The endpointer holds this one, in case the owner goes on after a pause.
        detector.next.addLast(listOf(utterance(0f, 0.8f)))
        pipeline.feed()

        pipeline.pause()
        pipeline.feedUntil(5f)

        assertEquals(0, recognizer.calls)
    }

    @Test
    fun anInterruptionCountsWhenRecognitionResumesWithin3Seconds() = runTest {
        interruptedAt(1f, resumingAt = 4f)

        val heard = events.filterIsInstance<VoiceEvent.Heard>().single()
        assertEquals("heard 70400", heard.text)
        assertTrue(heard.named)
    }

    @Test
    fun anInterruptionMoreThan3SecondsOldOnlyCutsAtTheResume() = runTest {
        interruptedAt(1f, resumingAt = 4.1f)

        val heard = events.filterIsInstance<VoiceEvent.Heard>().single()
        assertEquals("heard 8000", heard.text)
        assertFalse(heard.named)
    }

    @Test
    fun speechStartedComesWhenTheDetectorStartsToHearSpeech() = runTest {
        val pipeline = pipeline()
        pipeline.feedUntil(0.5f)
        detector.hearsSpeech = true
        pipeline.feedUntil(1f)
        detector.hearsSpeech = false
        pipeline.feedUntil(1.5f)
        detector.hearsSpeech = true
        pipeline.feedUntil(2f)

        assertEquals(listOf<VoiceEvent>(VoiceEvent.SpeechStarted, VoiceEvent.SpeechStarted), events)
    }

    @Test
    fun speechStartedWaitsWhileKestrelSpeaksAndComesWhenRecognitionResumesDuringSpeech() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.NONE
        detector.hearsSpeech = true
        pipeline.feedUntil(1f)
        assertEquals(emptyList<VoiceEvent>(), events)

        pipeline.recognition = Recognition.ALL
        pipeline.feedUntil(1.5f)

        assertEquals(listOf<VoiceEvent>(VoiceEvent.SpeechStarted), events)
    }

    @Test
    fun speechStartedComesAgainWhenAnEventArrivesWhileTheOwnerStillSpeaks() = runTest {
        val pipeline = pipeline()
        detector.hearsSpeech = true
        pipeline.feedUntil(1f)
        // An earlier utterance ends, and its transcript arrives while the owner goes on speaking.
        detector.next.addLast(listOf(utterance(0f, 0.5f)))
        pipeline.feed()
        pipeline.feedUntil(1.5f)

        assertEquals(listOf("SpeechStarted", "Heard", "SpeechStarted"), events.map { it::class.simpleName })
    }

    @Test
    fun anUtteranceThatTheResumeCutsToNothingIsReportedAsDropped() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.NONE
        pipeline.feedUntil(1f)
        pipeline.recognition = Recognition.ALL
        pipeline.feedUntil(1.6f)
        // Only 0.1 s of it comes after 1 s, when Kestrel stopped speaking: the echo of Kestrel's own voice.
        detector.next.addLast(listOf(utterance(0.3f, 0.8f)))
        pipeline.feed()

        assertEquals(listOf<VoiceEvent>(VoiceEvent.Dropped), events)
        assertEquals(0, recognizer.calls)
    }

    @Test
    fun speechStartedComesAgainAfterADropWhileTheOwnerStillSpeaks() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.NONE
        pipeline.feedUntil(1f)
        pipeline.recognition = Recognition.ALL
        detector.hearsSpeech = true
        pipeline.feedUntil(1.6f)
        // The echo's utterance ends while the detector still hears speech. Silero reports an utterance only after 0.7 s
        // of silence, so a real drop comes on a frame without speech; this checks that the flag outlives the drop.
        detector.next.addLast(listOf(utterance(0.3f, 0.8f)))
        pipeline.feed()
        pipeline.feedUntil(2f)

        assertEquals(listOf<VoiceEvent>(VoiceEvent.SpeechStarted, VoiceEvent.Dropped, VoiceEvent.SpeechStarted), events)
    }

    @Test
    fun anUtteranceThatPausedRecognitionWouldNotTranscribeIsNotReportedAsDropped() = runTest {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.NONE
        pipeline.feedUntil(1f)
        pipeline.recognition = Recognition.NAMED
        pipeline.feedUntil(1.6f)
        // Kestrel's echo, without the name: paused recognition would not have transcribed it anyway.
        detector.next.addLast(listOf(utterance(0.3f, 0.8f)))
        pipeline.feed()

        assertEquals(emptyList<VoiceEvent>(), events)
    }

    @Test
    fun speechStartedComesAgainAfterAFailureWhileTheOwnerStillSpeaks() = runTest {
        val pipeline = pipeline()
        recognizer.failNext = true
        detector.hearsSpeech = true
        pipeline.feedUntil(1f)
        detector.next.addLast(listOf(utterance(0f, 0.5f)))
        pipeline.feed()
        pipeline.feedUntil(1.5f)

        assertEquals(listOf("SpeechStarted", "Failed", "SpeechStarted"), events.map { it::class.simpleName })
    }

    @Test
    fun aStrayCancellationIsReportedAndTheNextUtteranceIsTranscribed() = runTest {
        var timeouts = 1
        val pipeline = pipeline(boostWords = {
            if (timeouts-- > 0) withTimeout(10) { awaitCancellation() }
            emptyList()
        })

        detector.next.addLast(listOf(utterance(0f, 0.5f)))
        pipeline.feed()
        detector.next.addLast(listOf(utterance(0.5f, 0.25f)))
        pipeline.feed()
        // The worker runs in the background scope, so only time running on lets its timeout fire.
        delay(1_000)

        assertTrue((events[0] as VoiceEvent.Failed).error is TimeoutCancellationException)
        assertEquals("heard 4000", (events[1] as VoiceEvent.Heard).text)
    }

    @Test
    fun closeWhileBoostWordsWaitsReportsNoFailure() = runTest {
        val pipeline = pipeline(boostWords = { awaitCancellation() })
        detector.next.addLast(listOf(utterance(0f, 0.5f)))
        pipeline.feed()

        pipeline.close()

        assertEquals(emptyList<VoiceEvent>(), events)
    }

    @Test
    fun closeWaitsForTheTranscriptionInProgressAndDropsTheWaitingOnes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = CountDownLatch(1)
        recognizer.gate = gate
        try {
            val pipeline = ListeningPipeline(
                detector, spotter, recognizer, { emptyList() }, { false }, scope,
                Dispatchers.Default.limitedParallelism(1),
            )
            val heard = scope.async { pipeline.events.toList() }
            detector.next.addLast(listOf(utterance(0f, 0.5f)))
            pipeline.feed()
            assertTrue(recognizer.entered.await(10, TimeUnit.SECONDS))
            detector.next.addLast(listOf(utterance(0.5f, 0.25f)))
            pipeline.feed()
            detector.next.addLast(listOf(utterance(0.75f, 0.25f)))
            pipeline.feed()

            val closing = scope.launch { pipeline.close() }
            delay(300)
            assertFalse(closing.isCompleted)
            gate.countDown()
            withTimeout(10_000) { closing.join() }

            assertEquals(1, recognizer.calls)
            // The transcript in progress still goes out, and then the events end.
            val sent = withTimeout(10_000) { heard.await() }
            assertEquals(listOf("heard 8000"), sent.map { (it as VoiceEvent.Heard).text })
        } finally {
            gate.countDown()
            scope.cancel()
        }
    }

    @Test
    fun closeTwiceAndFramesAfterCloseDoNoHarm() = runTest {
        val pipeline = pipeline(context = StandardTestDispatcher(testScheduler))
        detector.next.addLast(listOf(utterance(0f, 0.5f)))
        pipeline.feed()
        detector.next.addLast(listOf(utterance(0.5f, 0.25f)))
        pipeline.feed()

        pipeline.close()
        pipeline.close()
        detector.next.addLast(listOf(utterance(0.75f, 0.25f)))
        spotter.next.addLast(listOf(KeywordHit(Keyword.STOP, 0.06f)))
        pipeline.feed()

        assertEquals(0, recognizer.calls)
        assertEquals(emptyList<VoiceEvent>(), withTimeout(1_000) { pipeline.events.toList() })
        assertEquals(emptyList<VoiceEvent>(), events)
    }

    /**
     * The owner says "Kestrel" while Kestrel speaks, and the spotter reports it at [hit] + 0.02 s; Kestrel stops speaking
     * at [resumingAt]. The speech detector made one utterance from 0.2 s to 4.6 s, which starts less than 1.5 s before
     * the hit.
     */
    private fun TestScope.interruptedAt(hit: Float, resumingAt: Float) {
        val pipeline = pipeline()
        pipeline.recognition = Recognition.NONE
        pipeline.feedUntil(hit)
        spotter.next.addLast(listOf(KeywordHit(Keyword.KESTREL, hit)))
        pipeline.feed()
        pipeline.feedUntil(resumingAt)
        pipeline.recognition = Recognition.ANSWER
        pipeline.feedUntil(5.3f)
        detector.next.addLast(listOf(utterance(0.2f, 4.4f)))
        pipeline.feed()
        pipeline.feedUntil(7f)
    }

    private fun TestScope.pipeline(
        boostWords: suspend () -> List<String> = { emptyList() },
        context: CoroutineContext? = null,
    ): ListeningPipeline {
        val unconfined = UnconfinedTestDispatcher(testScheduler)
        val pipeline = ListeningPipeline(
            detector, spotter, recognizer, boostWords, { it in ownVoice }, backgroundScope, context ?: unconfined,
        )
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
        override var hearsSpeech = false

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
        @Volatile
        var calls = 0
        var failNext = false
        var blankNext = false
        var gate: CountDownLatch? = null
        val entered = CountDownLatch(1)
        val boostWords = mutableListOf<List<String>>()

        override fun transcribe(samples: FloatArray, boostWords: List<String>): Transcript {
            calls++
            entered.countDown()
            gate?.await(10, TimeUnit.SECONDS)
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
