package dev.kestrel.app.voice

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.voice.VoiceTests.graph
import dev.kestrel.app.voice.VoiceTests.log
import dev.kestrel.app.voice.VoiceTests.pssMegabytes
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class SynthesizerTest {
    @Before
    fun needsTheModels() = VoiceTests.assumeModels(Engine.TTS)

    @Test
    fun theVoiceSpeaksASentence() = runBlocking {
        val started = SystemClock.elapsedRealtime()
        graph.voice.synthesizer().use { voice ->
            val loaded = SystemClock.elapsedRealtime()
            val samples = voice.synthesize("Kestrel, open the settings.")
            val seconds = samples.size / voice.sampleRate.toFloat()
            log(
                "voice: loaded in ${loaded - started} ms; ${"%.1f".format(seconds)} s of speech in " +
                    "${SystemClock.elapsedRealtime() - loaded} ms; process PSS ${pssMegabytes()} MB",
            )

            assertEquals(22_050, voice.sampleRate)
            assertTrue("$seconds s", seconds in 1f..4f)
            assertTrue(samples.maxOf { abs(it) } > 0.1f)
        }
    }

    @Test
    fun withoutVariationTheVoiceSaysATextTheSameWay() = runBlocking {
        graph.voice.synthesizer(VoiceOptions(variation = 0f)).use { voice ->
            assertArrayEquals(voice.synthesize("Stop."), voice.synthesize("Stop."), 0f)
        }
    }
}
