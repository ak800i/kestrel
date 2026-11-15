package dev.kestrel.app.voice

import dev.kestrel.voice.KeywordSpotter
import dev.kestrel.voice.Recognizer
import dev.kestrel.voice.listen.ListeningPipeline
import dev.kestrel.voice.listen.VoiceEvent
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The pipeline with the real engines, on synthetic speech in Kestrel's voice (design spec, sections 8.1–8.3). */
class ListeningTest {
    @Before
    fun models() = VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.ASR, Engine.TTS)

    @Test
    fun aTaskWithTheNameIsNamed() {
        val heard = listen(VoiceTests.speech("Kestrel, open the settings.") + VoiceTests.silence(1.5f))

        assertTrue(heard.named)
        assertTrue(heard.text.lowercase().contains("open the settings"))
    }

    @Test
    fun aPauseAfterTheNameKeepsOneUtterance() {
        val audio = VoiceTests.silence(0.5f) + VoiceTests.voice("Kestrel") + VoiceTests.silence(1f) +
            VoiceTests.voice("open the settings") + VoiceTests.silence(2.5f)

        val heard = listen(audio)

        assertTrue(heard.named)
        assertTrue(heard.text.lowercase().contains("open the settings"))
    }

    @Test
    fun speechWithoutTheNameIsNotNamed() {
        val heard = listen(VoiceTests.speech("Stop listening.") + VoiceTests.silence(1f))

        assertFalse(heard.named)
        assertTrue(heard.text.lowercase().contains("stop listening"))
    }

    /** Feeds [audio] to a pipeline in 20 ms frames, as the microphone would, and returns the first transcript. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun listen(audio: FloatArray): VoiceEvent.Heard = runBlocking {
        val detector = VoiceTests.graph.voice.speechDetector()
        var spotter: KeywordSpotter? = null
        var pipeline: ListeningPipeline? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val keywords = VoiceTests.graph.voice.keywordSpotter().also { spotter = it }
            val listening = ListeningPipeline(
                detector, keywords, recognizer(), { listOf("Kestrel") }, { false }, scope, Dispatchers.Default.limitedParallelism(1),
            ).also { pipeline = it }
            VoiceTests.frames(audio) { frame ->
                listening.accept(frame)
                emptyList<Unit>()
            }
            val event = withTimeout(30_000) { listening.events.first { it is VoiceEvent.Heard || it is VoiceEvent.Failed } }
            if (event is VoiceEvent.Failed) throw AssertionError("Transcribing failed", event.error)
            val heard = event as VoiceEvent.Heard
            VoiceTests.log("Pipeline: ${heard.seconds} s of speech transcribed in ${heard.millis} ms")
            heard
        } finally {
            // The worker must stop before the next test uses the shared recogniser and before the engines close.
            pipeline?.close()
            scope.cancel()
            detector.close()
            spotter?.close()
        }
    }

    companion object {
        // Loading Parakeet takes seconds, so the tests share one recogniser.
        private var shared: Recognizer? = null

        private fun recognizer(): Recognizer = shared ?: runBlocking { VoiceTests.graph.voice.recognizer() }.also { shared = it }

        @AfterClass
        @JvmStatic
        fun closeRecognizer() {
            shared?.close()
            shared = null
        }
    }
}
