package dev.kestrel.app.voice

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.voice.VoiceTests.frames
import dev.kestrel.app.voice.VoiceTests.graph
import dev.kestrel.app.voice.VoiceTests.log
import dev.kestrel.app.voice.VoiceTests.silence
import dev.kestrel.app.voice.VoiceTests.speech
import dev.kestrel.app.voice.VoiceTests.voice
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.SpeechDetector
import dev.kestrel.voice.Utterance
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class SpeechDetectorTest {
    @Before
    fun needsTheModels() = VoiceTests.assumeModels(Engine.VAD, Engine.TTS)

    /** The detector hands out the audio unchanged, so each utterance must equal the audio at its position. */
    private fun assertTheAudioAt(audio: FloatArray, utterance: Utterance) {
        val start = utterance.startSample.toInt()
        assertArrayEquals(audio.copyOfRange(start, start + utterance.samples.size), utterance.samples, 0f)
    }

    /** Feeds [audio] in chunks of [size] samples, then finishes. */
    private fun chunks(detector: SpeechDetector, audio: FloatArray, size: Int): List<Utterance> =
        (audio.indices step size).flatMap { start -> detector.accept(audio.copyOfRange(start, minOf(start + size, audio.size))) } +
            detector.finish()

    /** [voice] without the little silence that the voice adds before and after the words. */
    private fun withoutSilence(text: String): FloatArray {
        val samples = voice(text)
        return samples.copyOfRange(samples.indexOfFirst { abs(it) > QUIET }, samples.indexOfLast { abs(it) > QUIET } + 1)
    }

    @Test
    fun aSentenceBetweenSilencesIsOneUtterance() = runBlocking {
        val audio = speech("Scroll down and tap the blue button.")
        val started = SystemClock.elapsedRealtime()
        graph.voice.speechDetector().use { detector ->
            val loaded = SystemClock.elapsedRealtime()
            val heard = mutableListOf<Boolean>()
            val utterances = frames(audio) { frame -> detector.accept(frame).also { heard += detector.hearsSpeech } }
            val utterance = (utterances + detector.finish()).single()
            val seconds = audio.size / SAMPLE_RATE.toFloat()
            log("speech detector: loaded in ${loaded - started} ms; ${"%.1f".format(seconds)} s of audio in ${SystemClock.elapsedRealtime() - loaded} ms")

            // The speech starts after 0.5 s of silence, and the voice adds a little silence of its own.
            assertEquals(0.5f, utterance.startSample / SAMPLE_RATE.toFloat(), 0.3f)
            assertEquals(audio.size / SAMPLE_RATE.toFloat() - 1.5f, utterance.seconds, 0.5f)
            assertTheAudioAt(audio, utterance)
            // It hears the speech while the voice speaks, and the utterance has ended within the 1 s of silence after it.
            assertFalse(heard.first())
            assertTrue(heard.any { it })
            assertFalse(heard.last())
        }
    }

    @Test
    fun anyChunkSizeGivesTheSameUtterances() = runBlocking {
        val audio = speech("Stop.") + speech("Cancel that.")
        graph.voice.speechDetector().use { detector ->
            val expected = frames(audio, detector::accept) + detector.finish()
            assertEquals(2, expected.size)
            for (size in listOf(1, 512, 1_000, audio.size)) {
                val utterances = chunks(detector, audio, size)
                assertEquals("chunks of $size", expected.map { it.startSample }, utterances.map { it.startSample })
                for ((want, got) in expected.zip(utterances)) assertArrayEquals("chunks of $size", want.samples, got.samples, 0f)
                utterances.forEach { assertTheAudioAt(audio, it) }
            }
        }
    }

    @Test
    fun afterFinishTheDetectorCountsFromZeroAgain() = runBlocking {
        val audio = speech("Stop.")
        graph.voice.speechDetector().use { detector ->
            val first = (frames(audio, detector::accept) + detector.finish()).single()
            val second = (frames(audio, detector::accept) + detector.finish()).single()

            assertEquals(first.startSample, second.startSample)
            assertArrayEquals(first.samples, second.samples, 0f)
        }
    }

    @Test
    fun twoSentencesWithAPauseBetweenThemAreTwoUtterances() = runBlocking {
        // 1.5 s of silence lies between the two, longer than the 0.7 s that ends an utterance.
        val audio = speech("Stop.") + speech("Cancel that.")
        graph.voice.speechDetector().use { detector ->
            assertEquals(2, (frames(audio, detector::accept) + detector.finish()).size)
        }
    }

    @Test
    fun aShortPauseDoesNotEndAnUtterance() = runBlocking {
        val audio = silence(0.5f) + withoutSilence("Stop.") + silence(0.4f) + withoutSilence("Cancel.") + silence(1f)
        graph.voice.speechDetector().use { detector ->
            val utterance = (frames(audio, detector::accept) + detector.finish()).single()
            assertTheAudioAt(audio, utterance)
        }
    }

    /** Sentences back to back without silence between them, for about 40 s. */
    private fun continuousSpeech(): FloatArray {
        var speech = FloatArray(0)
        while (speech.size < 40 * SAMPLE_RATE) {
            for (text in SENTENCES) speech += withoutSilence(text)
        }
        return speech
    }

    @Test
    fun longSpeechIsCutIntoUtterancesOfAtMost30Seconds() = runBlocking {
        val audio = silence(0.5f) + continuousSpeech() + silence(1f)
        graph.voice.speechDetector().use { detector ->
            val utterances = frames(audio, detector::accept) + detector.finish()
            log("speech detector: ${"%.1f".format(audio.size / SAMPLE_RATE.toFloat())} s of speech gave utterances of ${utterances.joinToString { "%.2f".format(it.seconds) }} s")

            assertTrue(utterances.size >= 2)
            for (utterance in utterances) {
                assertTrue("${utterance.seconds} s", utterance.samples.isNotEmpty() && utterance.seconds <= 30f)
                assertTheAudioAt(audio, utterance)
            }
            assertTrue("${utterances[0].seconds} s", utterances[0].seconds > 29f)
            // The next utterance starts exactly where the cut was.
            assertEquals(utterances[0].startSample + utterances[0].samples.size, utterances[1].startSample)
        }
    }

    @Test
    fun speechThatEndsJustAfterACutGivesNoUtteranceShorterThanAQuarterSecond() = runBlocking {
        val speech = silence(0.5f) + continuousSpeech()
        val cut = graph.voice.speechDetector().use { detector ->
            val first = (frames(speech + silence(1f), detector::accept) + detector.finish()).first()
            (first.startSample + first.samples.size).toInt()
        }
        val started = SystemClock.elapsedRealtime()
        val counts = mutableMapOf<Int, Int>()
        var shortest = Int.MAX_VALUE
        // The speech ends from 0.3 s before the cut to 0.6 s after it, one window of the model further in each run.
        for (end in cut - (0.3f * SAMPLE_RATE).toInt()..cut + (0.6f * SAMPLE_RATE).toInt() step WINDOW) {
            val audio = speech.copyOfRange(0, end) + silence(2f)
            graph.voice.speechDetector().use { detector ->
                val utterances = frames(audio, detector::accept) + detector.finish()
                counts.merge(utterances.size, 1, Int::plus)
                for (utterance in utterances) {
                    shortest = minOf(shortest, utterance.samples.size)
                    assertTrue("${end - cut} samples after the cut: ${utterance.seconds} s", utterance.samples.size >= SAMPLE_RATE / 4)
                    assertTheAudioAt(audio, utterance)
                }
            }
        }
        log("speech detector: ${counts.values.sum()} runs around the cut in ${SystemClock.elapsedRealtime() - started} ms; utterances per run: ${counts.toSortedMap()}; shortest ${"%.2f".format(shortest / SAMPLE_RATE.toFloat())} s")
        // Some runs end within a quarter second after the cut, and give only the first utterance.
        assertTrue("$counts", (counts[1] ?: 0) > 0 && (counts[2] ?: 0) > 0)
    }

    @Test
    fun anUtteranceThatEndsWhenTheHourlyResetComesIsKept() = runBlocking {
        // The word starts 0.4 s before the hour mark, and its 0.7 s of silence ends after it, so the reset comes in the
        // window in which sherpa-onnx queues the utterance.
        val tenSeconds = silence(10f)
        val before = 359L * tenSeconds.size
        val pause = silence(9.6f)
        val words = withoutSilence("Stop.") + silence(2f)
        val tail = pause + words
        val audio = speech("Stop.")
        graph.voice.speechDetector().use { detector ->
            val silent = (1..359).flatMap { detector.accept(tenSeconds) }
            val atReset = detector.accept(pause) + frames(words, detector::accept)
            val after = frames(audio, detector::accept) + detector.finish()

            assertEquals(0, silent.size)
            val kept = atReset.single()
            assertEquals(3_599.6f, kept.startSample / SAMPLE_RATE.toFloat(), 0.3f)
            assertTheAudioAt(tail, Utterance(kept.startSample - before, kept.samples))
            // The count goes on after this reset too.
            val next = after.single()
            val offset = before + tail.size
            assertEquals(0.5f, (next.startSample - offset) / SAMPLE_RATE.toFloat(), 0.3f)
            assertTheAudioAt(audio, Utterance(next.startSample - offset, next.samples))
        }
    }

    @Test
    fun theCountGoesOnAfterAnHourOfSilence() = runBlocking {
        // The detector restarts sherpa-onnx's 32-bit count during silence once an hour, and keeps counting itself.
        val tenSeconds = silence(10f)
        val silent = 361L * tenSeconds.size
        val audio = speech("Stop.")
        graph.voice.speechDetector().use { detector ->
            val before = (1..361).flatMap { detector.accept(tenSeconds) }
            val utterance = (frames(audio, detector::accept) + detector.finish()).single()

            assertEquals(0, before.size)
            assertEquals(0.5f, (utterance.startSample - silent) / SAMPLE_RATE.toFloat(), 0.3f)
            assertTheAudioAt(audio, Utterance(utterance.startSample - silent, utterance.samples))
        }
    }

    @Test
    fun silenceHasNoUtterance() = runBlocking {
        graph.voice.speechDetector().use { detector ->
            assertEquals(0, frames(silence(2f), detector::accept).size)
            assertFalse(detector.hearsSpeech)
            assertEquals(0, detector.finish().size)
        }
    }

    private companion object {
        // Below this, a sample of the voice counts as its own silence.
        const val QUIET = 0.02f

        // The speech detector's model takes the audio in windows of 512 samples.
        const val WINDOW = 512

        val SENTENCES = listOf(
            "Scroll down and tap the blue button.",
            "Read the first three messages in my inbox.",
            "Turn the volume up and play the next song.",
            "Show me the way to the nearest train station.",
            "Set an alarm for seven thirty tomorrow morning.",
            "Call my sister and tell her that I am running late.",
        )
    }
}
