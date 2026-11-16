package dev.kestrel.app.voice

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import dev.kestrel.app.MainActivity
import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.State
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.voice.SAMPLE_RATE
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A whole conversation with the real engines, on Kestrel's own voice played through a stand-in microphone (design spec,
 * sections 8.3–8.5): the name and "Yes?", a task, "Stop listening", "Kestrel, wake up" and "Stop Kestrel". Kestrel is in
 * the background after the start, so its replies play only because ListeningService runs (B5).
 */
class ConversationTest {
    private val listening = VoiceTests.graph.listening
    private val clip = ClipMicrophone()
    private val states = CopyOnWriteArrayList<Pair<Long, State>>()

    @Before
    fun setUp() {
        VoiceTests.assumeModels(Engine.VAD, Engine.KWS, Engine.ASR, Engine.TTS)
        VoiceTests.grantListeningPermissions()
    }

    @After
    fun tearDown() {
        VoiceTests.stopListening()
    }

    @Test
    fun kestrelAnswersItsNameRunsATaskPausesWakesUpAndTurnsOff() {
        // Synthesised first, so that each answer comes well within the 8 s that Kestrel waits after "Yes?".
        val name = VoiceTests.speech("Kestrel.")
        val task = VoiceTests.speech("Open the settings.")
        val pause = VoiceTests.speech("Stop listening.")
        val wakeUp = VoiceTests.speech("Kestrel, wake up.")
        val off = VoiceTests.speech("Stop Kestrel.")
        // The task counter belongs to the process, so other tests may have run tasks before this one.
        val expectedTask = listening.state.value.task + 1
        val started = SystemClock.elapsedRealtime()

        runBlocking {
            val watcher = launch(Dispatchers.Default) {
                listening.state.collect { states += SystemClock.elapsedRealtime() to it }
            }
            try {
                // Kestrel is in front for the start, so that Android lets it start a foreground service of the type
                // microphone. The conversation runs in the background, where only the service lets Kestrel speak (B5).
                ActivityScenario.launch(MainActivity::class.java).use { VoiceTests.startListening(clip) }

                clip.play(name)
                // "Yes?" was spoken, and Kestrel waits for the task.
                awaitState("the answer to the name") { it.work == Work.AWAITING_TASK && !it.speaking && replies().size == 1 }

                clip.play(task)
                // Until sub-project 5, every task ends at once with a spoken "I can't run tasks yet."
                awaitState("the task's reply") {
                    it.task == expectedTask && it.work == Work.IDLE && !it.speaking && replies().size == 2
                }

                // The speech detector can miss the short first word of a clip, such as "Stop", and the keyword spotter
                // the name, depending on where the clip falls in their windows. An owner would say it again.
                val plays = listOf(
                    playUntil("the pause", pause) { it.mode == Mode.PAUSED },
                    playUntil("the wake-up", wakeUp) { it.mode == Mode.LISTENING },
                    playUntil("the end", off) { it.mode == Mode.OFF },
                )
                assertTrue("The voice session did not close", VoiceTests.waitUntil(30_000) { !listening.sessionOpen.value })
                assertTrue("ListeningService still runs", VoiceTests.waitUntil(10_000) { !VoiceTests.listeningServiceRuns() })
                VoiceTests.log(
                    "Conversation: ${SystemClock.elapsedRealtime() - started} ms; replies spoke in the background for " +
                        "${replies().joinToString()} ms; the pause, the wake-up and the end took ${plays.joinToString()} plays",
                )
            } finally {
                watcher.cancel()
            }
        }
    }

    /**
     * Plays [speech] until the state meets [condition], at most [PLAYS] times, as an owner says a command again that
     * Kestrel did not catch. Each play waits for the clip and then up to 10 s. Returns how many plays it took.
     */
    private fun playUntil(step: String, speech: FloatArray, condition: (State) -> Boolean): Int {
        val millis = speech.size * 1_000L / SAMPLE_RATE + 10_000
        for (play in 1..PLAYS) {
            clip.play(speech)
            if (VoiceTests.waitUntil(millis) { condition(listening.state.value) }) return play
        }
        throw AssertionError("No $step after $PLAYS plays; the state is ${listening.state.value}")
    }

    /**
     * How long Kestrel spoke each reply that it played, in ms: the times that `speaking` stayed on for at least 200 ms. A
     * refused or failed reply ends within milliseconds; "Yes?" plays for about half a second, and the player then waits
     * for its 0.2 s buffer to drain.
     */
    private fun replies(): List<Long> = speakingPeriods().filter { it >= 200 }

    /** How long `speaking` stayed on each time, in ms, as the watcher saw it. */
    private fun speakingPeriods(): List<Long> {
        val periods = mutableListOf<Long>()
        var since: Long? = null
        for ((time, state) in states.toList()) {
            if (state.speaking && since == null) since = time
            if (!state.speaking && since != null) {
                periods += time - since
                since = null
            }
        }
        return periods
    }

    private fun awaitState(step: String, condition: (State) -> Boolean) {
        assertTrue(
            "No $step; the state is ${listening.state.value}; speaking lasted ${speakingPeriods()} ms",
            VoiceTests.waitUntil(30_000) { condition(listening.state.value) },
        )
    }

    private companion object {
        const val PLAYS = 5
    }
}
