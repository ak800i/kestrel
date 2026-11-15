package dev.kestrel.app.voice

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.voice.VoiceTests.frames
import dev.kestrel.app.voice.VoiceTests.graph
import dev.kestrel.app.voice.VoiceTests.log
import dev.kestrel.app.voice.VoiceTests.silence
import dev.kestrel.app.voice.VoiceTests.speech
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechDetectorTest {
    @Before
    fun needsTheModels() = VoiceTests.assumeModels(Engine.VAD, Engine.TTS)

    @Test
    fun aSentenceBetweenSilencesIsOneUtterance() = runBlocking {
        val audio = speech("Scroll down and tap the blue button.")
        val started = SystemClock.elapsedRealtime()
        graph.voice.speechDetector().use { detector ->
            val loaded = SystemClock.elapsedRealtime()
            val utterance = (frames(audio, detector::accept) + detector.finish()).single()
            val seconds = audio.size / SAMPLE_RATE.toFloat()
            log("speech detector: loaded in ${loaded - started} ms; ${"%.1f".format(seconds)} s of audio in ${SystemClock.elapsedRealtime() - loaded} ms")

            // The speech starts after 0.5 s of silence, and the voice adds a little silence of its own.
            assertEquals(0.5f, utterance.startSample / SAMPLE_RATE.toFloat(), 0.3f)
            assertEquals(audio.size / SAMPLE_RATE.toFloat() - 1.5f, utterance.seconds, 0.5f)
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
    fun silenceHasNoUtterance() = runBlocking {
        graph.voice.speechDetector().use { detector ->
            assertEquals(0, (frames(silence(2f), detector::accept) + detector.finish()).size)
            assertFalse(detector.hearsSpeech)
        }
    }
}
