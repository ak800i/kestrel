package dev.kestrel.app.listen

import dev.kestrel.voice.ModelsNotReadyException
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.VoiceChoice
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
    private class FakeVoice(private val closeFailure: Exception? = null) : Synthesizer {
        override val sampleRate = 22_050
        var closed = false

        override fun synthesize(text: String) = FloatArray(0)

        override fun close() {
            closed = true
            closeFailure?.let { throw it }
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
    fun anErrorFromTheMeasureClosesKokoroAndIsThrown() {
        val kokoro = FakeVoice()
        val reports = mutableListOf<String>()

        assertThrows(OutOfMemoryError::class.java) {
            runBlocking {
                loadChosenVoice(
                    VoiceOptionsSetting(VoiceChoice.KOKORO),
                    report = { what, _ -> reports += what },
                    tooSlow = { throw OutOfMemoryError("The phone ran out of memory") },
                ) { kokoro }
            }
        }

        // The session's clean-up does not know the voice yet, so the measure closes it.
        assertTrue(kokoro.closed)
        assertEquals(emptyList<String>(), reports)
    }

    @Test
    fun anErrorFromTheMeasureIsThrownAlsoWhenKokoroFailsToClose() {
        val closing = IllegalStateException("Kokoro could not be freed")
        val kokoro = FakeVoice(closeFailure = closing)
        val error = OutOfMemoryError("The phone ran out of memory")

        val thrown = assertThrows(OutOfMemoryError::class.java) {
            runBlocking {
                loadChosenVoice(VoiceOptionsSetting(VoiceChoice.KOKORO), tooSlow = { throw error }) { kokoro }
            }
        }

        assertSame(error, thrown)
        assertTrue(kokoro.closed)
        assertEquals(listOf<Throwable>(closing), thrown.suppressed.toList())
    }

    @Test
    fun aCancellationDuringTheMeasureClosesKokoroAndIsThrownOnWithoutAReport() = runTest {
        val closing = IllegalStateException("Kokoro could not be freed")
        val kokoro = FakeVoice(closeFailure = closing)
        val reports = mutableListOf<String>()
        val cancellation = CancellationException("Kestrel turned off")

        val thrown = try {
            loadChosenVoice(
                VoiceOptionsSetting(VoiceChoice.KOKORO),
                report = { what, _ -> reports += what },
                tooSlow = { throw cancellation },
            ) { kokoro }
            null
        } catch (e: CancellationException) {
            e
        }

        // A failure of the close must not turn the cancellation into an ordinary failure.
        assertSame(cancellation, thrown)
        assertTrue(kokoro.closed)
        assertEquals(listOf<Throwable>(closing), cancellation.suppressed.toList())
        assertEquals(emptyList<String>(), reports)
    }

    @Test
    fun aTapOnKokoroForgetsItsSpeedAndSlowerOrFasterKeepIt() {
        val kokoro = VoiceOptionsSetting(VoiceChoice.KOKORO, speed = 1.1f)
        val piper = VoiceOptionsSetting(VoiceChoice.PIPER, speed = 1.1f)

        // Choosing Kokoro, and a tap on Kokoro while it is chosen, which stores the same options again.
        assertTrue(forgetsKokoroSpeed(stored = piper, chosen = kokoro))
        assertTrue(forgetsKokoroSpeed(stored = kokoro, chosen = kokoro))
        // "Slower" and "Faster" with Kokoro, and every choice of Piper.
        assertFalse(forgetsKokoroSpeed(stored = kokoro, chosen = kokoro.slower()))
        assertFalse(forgetsKokoroSpeed(stored = kokoro, chosen = kokoro.faster()))
        assertFalse(forgetsKokoroSpeed(stored = kokoro, chosen = piper))
        assertFalse(forgetsKokoroSpeed(stored = piper, chosen = piper.faster()))
    }

    @Test
    fun theFirstMeasureOfAModelAndInstallIsStoredAndLaterOnesReadIt() = runTest {
        val stored = mutableListOf<KokoroSpeed>()
        var measures = 0

        suspend fun tooSlow(known: KokoroSpeed, installed: Long, factor: Float) = measuredTooSlow(
            FakeVoice(),
            "tts-kokoro/model.onnx",
            installed,
            stored = { known },
            store = { stored += it },
            report = { what, _ -> fail("Reported: $what") },
        ) {
            measures++
            factor
        }

        assertTrue(tooSlow(KokoroSpeed(), installed = 5L, factor = 1.8f))
        assertEquals(listOf(KokoroSpeed("tts-kokoro/model.onnx", 5L, 1.8f)), stored)
        // The stored measure of this model and install answers without a second measure.
        assertTrue(tooSlow(stored.last(), installed = 5L, factor = 0.5f))
        assertFalse(tooSlow(KokoroSpeed("tts-kokoro/model.onnx", 5L, 0.7f), installed = 5L, factor = 1.8f))
        assertEquals(1, measures)
        // After an update, Kestrel measures again and stores the new factor.
        assertFalse(tooSlow(stored.last(), installed = 6L, factor = 0.7f))
        assertEquals(KokoroSpeed("tts-kokoro/model.onnx", 6L, 0.7f), stored.last())
        assertEquals(2, measures)
    }

    @Test
    fun aMeasureThatCannotBeStoredIsReportedAndStillDecides() = runTest {
        val reports = mutableListOf<String>()

        suspend fun tooSlow(factor: Float) = measuredTooSlow(
            FakeVoice(),
            "tts-kokoro/model.onnx",
            5L,
            stored = { KokoroSpeed() },
            store = { throw IOException("The store could not be written") },
            report = { what, _ -> reports += what },
        ) { factor }

        assertTrue(tooSlow(1.8f))
        assertFalse(tooSlow(0.7f))
        assertEquals(listOf("Storing Kokoro's speed", "Storing Kokoro's speed"), reports)
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
