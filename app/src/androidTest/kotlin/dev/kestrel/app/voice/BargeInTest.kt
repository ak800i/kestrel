package dev.kestrel.app.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.orchestrator.Effect
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.Rules
import dev.kestrel.app.orchestrator.State
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.app.voice.VoiceTests.graph
import dev.kestrel.app.voice.VoiceTests.log
import dev.kestrel.voice.Keyword
import dev.kestrel.voice.Recognizer
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.Transcript
import dev.kestrel.voice.VoiceChoice
import dev.kestrel.voice.VoiceOptions
import dev.kestrel.voice.listen.ListeningPipeline
import dev.kestrel.voice.listen.Recognition
import dev.kestrel.voice.listen.VoiceEvent
import dev.kestrel.voice.models.Engine
import dev.kestrel.voice.speak.EchoFilter
import dev.kestrel.voice.speak.Spoken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Barge-in over the phone's loudspeaker (design spec, section 8.4; plan 4E): while Kestrel reads a reply, its own voice
 * reaches the microphone, and the owner's "Kestrel" must still interrupt it. The test feeds the pipeline, with the real
 * speech detector and keyword spotter, a mix of a reply in Kestrel's voice at the echo's level and "Kestrel" in another
 * voice at the level at which the A71 heard the owner, both from plan 4E's measure on the A71 (the decision of its
 * Task 4), as voice-route's frameRms measures them. The spotter must hear the name, the echo filter must not take it for
 * Kestrel's own voice, and the Orchestrator then stops the reply and waits for a task.
 */
@RunWith(AndroidJUnit4::class)
class BargeInTest {
    @Before
    fun needsTheModels() = VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.TTS, optional = setOf("tts-kokoro"))

    @Test
    fun theOwnersNameInterruptsAReplyOverTheLoudspeaker() = runBlocking {
        val reply = VoiceTests.voice(REPLY)
        val name = graph.voice.synthesizer(VoiceOptions(variation = 0f, voice = VoiceChoice.KOKORO)).use { kokoro ->
            ClipMicrophone.speech(kokoro, "Kestrel.")
        }
        val echoDb = echoDbfs()
        // 0.5 s of silence, the reply at the echo's level, and 1 s of silence; "Kestrel" from 2.5 s on, over the reply.
        val audio = VoiceTests.silence(0.5f) + scaled(reply, echoDb) + VoiceTests.silence(1f)
        val start = (NAME_AT * SAMPLE_RATE).toInt()
        scaled(name, NAME_DBFS).forEachIndexed { i, sample -> if (start + i < audio.size) audio[start + i] += sample }
        val spoken = listOf(Spoken(REPLY, 500, 500 + reply.size * 1_000L / SAMPLE_RATE))
        val filter = EchoFilter()
        var fedMillis = 0L
        val events = CopyOnWriteArrayList<VoiceEvent>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val detector = graph.voice.speechDetector()
        try {
            graph.voice.keywordSpotter().use { spotter ->
                val pipeline = ListeningPipeline(
                    detector, spotter, NO_RECOGNIZER, { emptyList() }, { keyword -> filter.isOwnVoice(keyword, fedMillis, spoken) },
                    scope, Dispatchers.Default.limitedParallelism(1),
                )
                val collector = scope.launch { pipeline.events.collect { events += it } }
                // Kestrel speaks, so recognition is off and only the spotter listens (section 8.4).
                pipeline.recognition = Recognition.NONE
                VoiceTests.frames(audio) { frame ->
                    pipeline.accept(frame)
                    fedMillis += frame.size * 1_000L / SAMPLE_RATE
                    emptyList<Unit>()
                }
                pipeline.close()
                collector.join()
            }
        } finally {
            scope.cancel()
            detector.close()
        }
        val heard = events.filterIsInstance<VoiceEvent.Spotted>().map { it.keyword }
        log("barge-in: the reply at $echoDb dBFS, \"Kestrel\" at $NAME_DBFS dBFS: keyword hits $heard")
        assertTrue("The spotter did not hear \"Kestrel\" over Kestrel's reply at $echoDb dBFS", Keyword.KESTREL in heard)
        // What the Orchestrator does with that hit while Kestrel reads its reply: it stops and waits for the task.
        val step = Rules.next(State(mode = Mode.LISTENING, speeches = 1), Event.Spotted(Keyword.KESTREL))
        assertTrue(Effect.StopSaying in step.effects)
        assertEquals(Work.AWAITING_TASK, step.state.work)
    }

    // [samples] scaled so that their loudest 20 ms frame has the RMS level [dbfs], as voice-route's frameRms measures it.
    private fun scaled(samples: FloatArray, dbfs: Float): FloatArray {
        val loudest = (samples.indices step VoiceTests.FRAME).maxOf { from ->
            val to = minOf(from + VoiceTests.FRAME, samples.size)
            var sum = 0.0
            for (i in from until to) sum += samples[i].toDouble() * samples[i]
            sum / (to - from)
        }
        val gain = (10.0.pow(dbfs / 20.0) / sqrt(loudest)).toFloat()
        return FloatArray(samples.size) { samples[it] * gain }
    }
    // The echo at which the A71 was interrupted at the owner's level of "Kestrel".
    private fun echoDbfs(): Float = WORKING_ECHO_DBFS

    private companion object {
        // From the decision of plan 4E's measure on the A71: nameDb and workingEchoDb.
        const val NAME_DBFS = -23.9f
        const val WORKING_ECHO_DBFS = -34.1f

        const val NAME_AT = 2.5f
        const val REPLY = "Here is a long reply, so that you can interrupt it. The news of the day comes one sentence after another, at an even pace."

        val NO_RECOGNIZER = object : Recognizer {
            override fun transcribe(samples: FloatArray, boostWords: List<String>) = Transcript("", pieces = 0, lastWordEnd = 0f)

            override fun close() = Unit
        }
    }
}
