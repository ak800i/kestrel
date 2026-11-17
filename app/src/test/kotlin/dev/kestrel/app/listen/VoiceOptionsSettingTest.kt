package dev.kestrel.app.listen

import dev.kestrel.voice.ModelsNotReadyException
import dev.kestrel.voice.Synthesizer
import dev.kestrel.voice.VoiceChoice
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class VoiceOptionsSettingTest {
    private class FakeVoice : Synthesizer {
        override val sampleRate = 22_050

        override fun synthesize(text: String) = FloatArray(0)

        override fun close() {}
    }

    private val loaded = mutableListOf<VoiceOptions>()

    @Test
    fun theChosenVoiceLoadsAtTheChosenSpeed() = runTest {
        val voice = FakeVoice()

        val result = loadChosenVoice(VoiceOptionsSetting(VoiceChoice.KOKORO, speed = 1.2f)) { options ->
            loaded += options
            voice
        }

        assertSame(voice, result)
        assertEquals(listOf(VoiceOptions(speed = 1.2f, voice = VoiceChoice.KOKORO)), loaded)
    }

    @Test
    fun aMissingKokoroVoiceGivesWayToPiper() = runTest {
        val piper = FakeVoice()

        val result = loadChosenVoice(VoiceOptionsSetting(VoiceChoice.KOKORO, speed = 0.9f)) { options ->
            loaded += options
            if (options.voice == VoiceChoice.KOKORO) throw ModelsNotReadyException(Engine.TTS, emptyList())
            piper
        }

        assertSame(piper, result)
        assertEquals(listOf(VoiceChoice.KOKORO, VoiceChoice.PIPER), loaded.map { it.voice })
        assertEquals(0.9f, loaded.last().speed)
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

        assertSame(piper, result)
        assertEquals(listOf(VoiceChoice.KOKORO, VoiceChoice.PIPER), loaded.map { it.voice })
        assertEquals(listOf("Loading the Kokoro voice"), reports)
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
