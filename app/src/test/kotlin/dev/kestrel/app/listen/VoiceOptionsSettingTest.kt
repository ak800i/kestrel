package dev.kestrel.app.listen

import dev.kestrel.voice.ModelsNotReadyException
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.VoiceChoice
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class VoiceOptionsSettingTest {
    private class FakeVoice : Synthesizer {
        override val sampleRate = 22_050
        var closed = false

        override fun synthesize(text: String) = FloatArray(0)

        override fun close() {
            closed = true
        }
    }

    private val loaded = mutableListOf<VoiceOptions>()

    @Test
    fun theChosenVoiceLoadsAtTheChosenSpeed() = runTest {
        val voice = FakeVoice()

        val result = loadChosenVoice(VoiceOptionsSetting(VoiceChoice.KOKORO, speed = 1.2f)) { options ->
            loaded += options
            voice
        }

        assertSame(voice, result.synthesizer)
        assertEquals(listOf(VoiceOptions(speed = 1.2f, voice = VoiceChoice.KOKORO)), loaded)
        assertEquals(loaded.single(), result.options)
        assertFalse(result.fellBack)
    }

    @Test
    fun aMissingKokoroVoiceGivesWayToPiper() = runTest {
        val piper = FakeVoice()

        val result = loadChosenVoice(VoiceOptionsSetting(VoiceChoice.KOKORO, speed = 0.9f)) { options ->
            loaded += options
            if (options.voice == VoiceChoice.KOKORO) throw ModelsNotReadyException(Engine.TTS, emptyList())
            piper
        }

        assertSame(piper, result.synthesizer)
        assertEquals(listOf(VoiceChoice.KOKORO, VoiceChoice.PIPER), loaded.map { it.voice })
        assertEquals(0.9f, loaded.last().speed)
        // The session speaks with Piper, which "Try it" must know, and the page "Voice and speech" says why.
        assertEquals(loaded.last(), result.options)
        assertTrue(result.fellBack)
    }

    @Test
    fun aMissingPiperVoiceStillFails() = runTest {
        try {
            loadChosenVoice(VoiceOptionsSetting()) { options ->
                loaded += options
                throw ModelsNotReadyException(Engine.TTS, emptyList())
            }
            fail("The load did not fail")
        } catch (e: ModelsNotReadyException) {
            assertEquals(1, loaded.size)
        }
    }

    @Test
    fun aKokoroVoiceThatFailsToLoadGivesWayToPiperAndIsReported() = runTest {
        val piper = FakeVoice()
        val reports = mutableListOf<String>()

        val chosen = VoiceOptionsSetting(VoiceChoice.KOKORO)
        val result = loadChosenVoice(chosen, report = { what, _ -> reports += what }) { options ->
            loaded += options
            if (options.voice == VoiceChoice.KOKORO) throw IllegalStateException("The voice could not be created")
            piper
        }

        assertSame(piper, result.synthesizer)
        assertEquals(listOf(VoiceChoice.KOKORO, VoiceChoice.PIPER), loaded.map { it.voice })
        assertEquals(listOf("Loading the Kokoro voice"), reports)
    }

    @Test
    fun aKokoroVoiceThatIsTooSlowOnThePhoneGivesWayToPiper() = runTest {
        val kokoro = FakeVoice()
        val piper = FakeVoice()

        val chosen = VoiceOptionsSetting(VoiceChoice.KOKORO, speed = 1.1f)
        val result = loadChosenVoice(chosen, tooSlow = { it === kokoro }) { options ->
            loaded += options
            if (options.voice == VoiceChoice.KOKORO) kokoro else piper
        }

        assertSame(piper, result.synthesizer)
        assertTrue(kokoro.closed)
        assertEquals(listOf(VoiceChoice.KOKORO, VoiceChoice.PIPER), loaded.map { it.voice })
        assertEquals(VoiceOptions(speed = 1.1f), result.options)
        // The page says that Kokoro is too slow, not that it did not load.
        assertTrue(result.slow)
        assertFalse(result.fellBack)
    }

    @Test
    fun aKokoroVoiceThatThePhoneMeasuredAsTooSlowDoesNotLoadAgain() = runTest {
        val piper = FakeVoice()

        val result = loadChosenVoice(
            VoiceOptionsSetting(VoiceChoice.KOKORO),
            knownTooSlow = { true },
            tooSlow = { throw AssertionError("Kokoro must not load") },
        ) { options ->
            loaded += options
            piper
        }

        assertSame(piper, result.synthesizer)
        assertEquals(listOf(VoiceChoice.PIPER), loaded.map { it.voice })
        assertTrue(result.slow)
    }

    @Test
    fun aMeasureThatFailsKeepsKokoroAndIsReported() = runTest {
        val kokoro = FakeVoice()
        val reports = mutableListOf<String>()

        val result = loadChosenVoice(
            VoiceOptionsSetting(VoiceChoice.KOKORO),
            report = { what, _ -> reports += what },
            tooSlow = { throw IOException("The store could not be written") },
        ) { kokoro }

        assertSame(kokoro, result.synthesizer)
        assertFalse(kokoro.closed)
        assertEquals(listOf("Measuring the Kokoro voice"), reports)
    }

    @Test
    fun aKokoroVoiceThatSaysNothingCountsAsNotLoaded() = runTest {
        val kokoro = FakeVoice()
        val piper = FakeVoice()
        val reports = mutableListOf<String>()

        val result = loadChosenVoice(
            VoiceOptionsSetting(VoiceChoice.KOKORO),
            report = { what, _ -> reports += what },
            tooSlow = { throw KokoroSilentException() },
        ) { options -> if (options.voice == VoiceChoice.KOKORO) kokoro else piper }

        assertSame(piper, result.synthesizer)
        assertTrue(kokoro.closed)
        assertTrue(result.fellBack)
        assertFalse(result.slow)
        assertEquals(listOf("Measuring the Kokoro voice"), reports)
    }

    @Test
    fun theRealTimeFactorIsTheTimeToSpeakAfterAWarmUpOverTheLengthOfTheSpeech() {
        var now = 1_000L
        val said = mutableListOf<String>()
        // 2 s of speech at 22,050 Hz, which takes 3 s to synthesize.
        val voice = object : Synthesizer {
            override val sampleRate = 22_050

            override fun synthesize(text: String): FloatArray {
                said += text
                now += 3_000
                return FloatArray(44_100)
            }

            override fun close() {}
        }

        assertEquals(1.5f, realTimeFactor(voice) { now }, 0.001f)
        assertEquals(listOf(SPEED_WARM_UP, SPEED_SAMPLE), said)
        assertThrows(KokoroSilentException::class.java) { realTimeFactor(FakeVoice()) { now } }
    }

    @Test
    fun aStoredSpeedCountsOnlyForItsModelAndInstallAndAboveOne() {
        val stored = KokoroSpeed("tts-kokoro/model.onnx", installed = 5L, factor = 1.8f)

        assertEquals(1.8f, stored.tooSlow("tts-kokoro/model.onnx", 5L))
        assertNull(stored.tooSlow("tts-kokoro-int8/model.int8.onnx", 5L))
        // An update of Kestrel measures again.
        assertNull(stored.tooSlow("tts-kokoro/model.onnx", 6L))
        assertNull(KokoroSpeed("tts-kokoro/model.onnx", 5L, 0.7f).tooSlow("tts-kokoro/model.onnx", 5L))
        assertTrue(KokoroSpeed("tts-kokoro/model.onnx", 5L, 0.7f).measures("tts-kokoro/model.onnx", 5L))
    }

    @Test
    fun theStoredVoiceLoadsAtTheStoredSpeed() = runTest {
        val result = loadStoredVoice({ VoiceOptionsSetting(VoiceChoice.KOKORO, speed = 1.2f) }, { _, _ -> }) { options ->
            loaded += options
            FakeVoice()
        }

        assertEquals(listOf(VoiceOptions(speed = 1.2f, voice = VoiceChoice.KOKORO)), loaded)
        assertEquals(loaded.single(), result.options)
    }

    @Test
    fun aVoiceSettingThatCannotBeReadCountsAsPiperAndIsReported() = runTest {
        val reports = mutableListOf<String>()

        val result = loadStoredVoice({ throw IOException("The store could not be read") }, { what, _ -> reports += what }) {
            loaded += it
            FakeVoice()
        }

        assertEquals(listOf(VoiceOptions()), loaded)
        assertEquals(VoiceOptions(), result.options)
        assertEquals(listOf("Reading the voice setting"), reports)
    }

    @Test
    fun theSpeedStaysWithinItsLimitsInStepsOfATenth() {
        assertEquals(0.8f, VoiceOptionsSetting(speed = 0.3f).options().speed)
        assertEquals(1.4f, VoiceOptionsSetting(speed = 9f).options().speed)
        assertEquals(0.8f, VoiceOptionsSetting(speed = 0.8f).slower().speed)
        assertEquals(1.4f, VoiceOptionsSetting(speed = 1.4f).faster().speed)
        assertEquals(1.1f, VoiceOptionsSetting().faster().speed)
        var setting = VoiceOptionsSetting(speed = 0.8f)
        repeat(6) { setting = setting.faster() }
        assertEquals(1.4f, setting.speed)
    }
}
