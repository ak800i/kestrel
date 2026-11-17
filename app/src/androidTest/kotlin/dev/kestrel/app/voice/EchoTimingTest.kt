package dev.kestrel.app.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.voice.VoiceTests.frames
import dev.kestrel.app.voice.VoiceTests.graph
import dev.kestrel.app.voice.VoiceTests.log
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.VoiceChoice
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.speak.EchoFilter
import dev.kestrel.voice.speak.Spoken
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The echo filter places a keyword in its sentence's playing time in proportion to the characters before it (design
 * spec, section 8.4), and the player takes each sentence's playing time from its samples. This checks that rule against
 * each of Kestrel's voices: it says sentences with keywords in the voice, feeds them to the keyword spotter as the
 * microphone would hear them, and checks that the filter takes every hit for Kestrel's own voice.
 */
@RunWith(AndroidJUnit4::class)
class EchoTimingTest {
    @Before
    fun needsTheModels() = VoiceTests.assumeModels(Engine.KWS, Engine.TTS)

    // Piper's case checks the rule that Kestrel has used since plan 2B; Kokoro's case is new.
    @Test
    fun theFilterKnowsPipersKeywords() = check(VoiceChoice.PIPER)

    @Test
    fun theFilterKnowsKokorosKeywords() = check(VoiceChoice.KOKORO)

    private fun check(choice: VoiceChoice) = runBlocking {
        if (choice == VoiceChoice.KOKORO) VoiceTests.assumeModels(Engine.KWS, Engine.TTS, optional = setOf("tts-kokoro"))
        val filter = EchoFilter()
        var hits = 0
        // The number of hits in each sentence, so that the log shows which keywords the spotter heard where (counts only).
        val perSentence = IntArray(SENTENCES.size)
        graph.voice.synthesizer(VoiceOptions(variation = 0f, voice = choice)).use { voice ->
            graph.voice.keywordSpotter().use { spotter ->
                // The spotter counts its hits' times from the start of all the audio that it got.
                var fedMillis = 0L
                for (text in SENTENCES) {
                    // 0.5 s of silence, the sentence at 16 kHz, and 1 s of silence.
                    val audio = ClipMicrophone.speech(voice, text)
                    val playing = (audio.size - SAMPLE_RATE * 3 / 2) * 1_000L / SAMPLE_RATE
                    // As the player computes it: the sentence lasts as long as its samples.
                    val spoken = Spoken(text, fedMillis + 500, fedMillis + 500 + playing)
                    for (hit in frames(audio, spotter::accept)) {
                        hits++
                        perSentence[SENTENCES.indexOf(text)]++
                        val hitMillis = (hit.seconds * 1_000).toLong()
                        assertTrue(
                            "$choice: a hit of ${hit.keyword} at $hitMillis ms in sentence ${SENTENCES.indexOf(text) + 1} " +
                                "is not Kestrel's own voice",
                            filter.isOwnVoice(hit.keyword, hitMillis, listOf(spoken)),
                        )
                    }
                    fedMillis += audio.size * 1_000L / SAMPLE_RATE
                }
            }
        }
        val counts = perSentence.joinToString()
        log("echo timing: $choice gave $hits keyword hits, all of them Kestrel's own voice; per sentence $counts")
        assertTrue("The spotter heard no keyword in $choice's voice", hits > 0)
    }

    private companion object {
        // Keywords at the start, in the middle and at the end, inside words, across words and after digits.
        val SENTENCES = listOf(
            "Should I stop the timer?",
            "It's top-rated.",
            "That is unstoppable.",
            "I cancelled the alarm at 06:45.",
            "Kestrel is listening, and the code is 4 7 1 9.",
            "Your appointment is at 10:30 on 12 March, so I will stop the reminders now.",
        )
    }
}
