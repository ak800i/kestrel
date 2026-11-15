package dev.kestrel.app.voice

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.voice.VoiceTests.graph
import dev.kestrel.app.voice.VoiceTests.log
import dev.kestrel.app.voice.VoiceTests.pssMegabytes
import dev.kestrel.app.voice.VoiceTests.voice
import dev.kestrel.voice.Recognizer
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.Transcript
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The speech detector cuts an utterance where the speech ends, so these tests transcribe the voice without silence around
 * it. With a second of silence after the last word, every transcript with boost words would be decoded a second time
 * without them (TranscriptChoice), and a boost that cuts a transcript short would go unnoticed.
 */
@RunWith(AndroidJUnit4::class)
class RecognizerTest {
    @Before
    fun needsTheModels() = VoiceTests.assumeModels(Engine.ASR, Engine.TTS)

    /** The words of a text in lowercase, without punctuation, for comparing transcripts. */
    private fun words(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N}' ]"), " ").split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    private fun transcribe(text: String, boostWords: List<String> = emptyList()): Transcript {
        val audio = voice(text)
        val recognizer = recognizer()
        val started = SystemClock.elapsedRealtime()
        return recognizer.transcribe(audio, boostWords).also {
            log("recognizer: ${"%.1f".format(audio.size / SAMPLE_RATE.toFloat())} s of audio in ${SystemClock.elapsedRealtime() - started} ms")
        }
    }

    @Test
    fun itTranscribesCommands() {
        assertEquals("scroll down and tap the blue button", words(transcribe("Scroll down and tap the blue button.").text))
        assertEquals("kestrel open the settings", words(transcribe("Kestrel, open the settings.").text))
    }

    @Test
    fun boostWordsDoNotChangeACorrectTranscript() {
        val text = "Scroll down and tap the blue button."
        val boostWords = listOf("Viber", "日本語", "😀 smile", "مرحبا", "O'Brien", "Jean-Luc", "R2-D2", " / ", "a:b") + (1..100).map { "Contact $it" }

        assertEquals(words(transcribe(text).text), words(transcribe(text, boostWords).text))
    }

    @Test
    fun theLastWordEndsWhereTheVoiceStops() {
        val text = "Scroll down and tap the blue button."
        val transcript = transcribe(text)

        // The voice ends with a little silence of its own.
        assertEquals(voice(text).size / SAMPLE_RATE.toFloat(), transcript.lastWordEnd, 0.5f)
    }

    companion object {
        // Loading takes seconds and about 700 MB, so the tests share one recogniser.
        private var shared: Recognizer? = null

        @Synchronized
        private fun recognizer(): Recognizer = shared ?: run {
            val before = pssMegabytes()
            val started = SystemClock.elapsedRealtime()
            runBlocking { graph.voice.recognizer() }.also {
                log("recognizer: loaded in ${SystemClock.elapsedRealtime() - started} ms; memory (PSS) $before MB -> ${pssMegabytes()} MB")
                shared = it
            }
        }

        @JvmStatic
        @AfterClass
        fun closeTheRecognizer() {
            shared?.close()
            shared = null
        }
    }
}
