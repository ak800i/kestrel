package dev.kestrel.app.voice

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.voice.VoiceTests.frames
import dev.kestrel.app.voice.VoiceTests.graph
import dev.kestrel.app.voice.VoiceTests.log
import dev.kestrel.app.voice.VoiceTests.speech
import dev.kestrel.app.voice.VoiceTests.voice
import dev.kestrel.voice.Keyword
import dev.kestrel.voice.KeywordSpotter
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeywordSpotterTest {
    @Before
    fun needsTheModels() = VoiceTests.assumeModels(Engine.KWS, Engine.TTS)

    private fun heard(spotter: KeywordSpotter, text: String): List<Keyword> = frames(speech(text), spotter::accept).map { it.keyword }

    @Test
    fun itHearsItsKeywords() = runBlocking {
        val started = SystemClock.elapsedRealtime()
        graph.voice.keywordSpotter().use { spotter ->
            log("keyword spotter: loaded in ${SystemClock.elapsedRealtime() - started} ms")

            // One spotter hears the phrases one after another, as it hears the microphone.
            assertEquals(listOf(Keyword.KESTREL), heard(spotter, "Kestrel, open the settings."))
            assertEquals(listOf(Keyword.KESTREL), heard(spotter, "Hey Kestrel, what is the weather?"))
            assertEquals(listOf(Keyword.STOP), heard(spotter, "Stop."))
            assertEquals(listOf(Keyword.CANCEL), heard(spotter, "Cancel that."))
        }
    }

    @Test
    fun itIgnoresOtherSpeech() = runBlocking {
        graph.voice.keywordSpotter().use { spotter ->
            for (text in listOf("Scroll down and tap the blue button.", "Chest roll and a pastry, please.", "Open Viber and message Zoran.")) {
                assertEquals(text, emptyList<Keyword>(), heard(spotter, text))
            }
        }
    }

    @Test
    fun aHitComesSoonAfterTheKeyword() = runBlocking {
        graph.voice.keywordSpotter().use { spotter ->
            val audio = speech("Stop.")
            val hit = frames(audio, spotter::accept).single()
            // speech() puts 0.5 s of silence before the voice.
            val voiceEnd = 0.5f + voice("Stop.").size / SAMPLE_RATE.toFloat()
            log("keyword spotter: the hit came ${"%.2f".format(hit.seconds - voiceEnd)} s after the end of the voice")

            // The word starts after 0.5 s of silence, and the audio ends 1 s after the voice.
            assertTrue("${hit.seconds} s", hit.seconds in 0.5f..audio.size / SAMPLE_RATE.toFloat())
        }
    }
}
