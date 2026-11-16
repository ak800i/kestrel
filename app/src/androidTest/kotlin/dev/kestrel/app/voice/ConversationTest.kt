package dev.kestrel.app.voice

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import dev.kestrel.app.MainActivity
import dev.kestrel.app.debug.ClipMicrophone
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.State
import dev.kestrel.app.orchestrator.Work
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
 * sections 8.3–8.5): the name and "Yes?", a task, "Stop listening", "Kestrel, wake up" and "Stop Kestrel".
 */
class ConversationTest {
    private val listening = VoiceTests.graph.listening
    private val clip = ClipMicrophone()
    private val states = CopyOnWriteArrayList<State>()

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
            val watcher = launch(Dispatchers.Default) { listening.state.collect { states += it } }
            try {
                // Kestrel is in front, so that Android lets it start a foreground service of the type microphone.
                ActivityScenario.launch(MainActivity::class.java).use {
                    VoiceTests.startListening(clip)

                    clip.play(name)
                    // "Yes?" was spoken, and Kestrel waits for the task.
                    awaitState("the answer to the name") { it.work == Work.AWAITING_TASK && !it.speaking && replies() == 1 }

                    clip.play(task)
                    // Until sub-project 5, every task ends at once with a spoken "I can't run tasks yet."
                    awaitState("the task's reply") {
                        it.task == expectedTask && it.work == Work.IDLE && !it.speaking && replies() == 2
                    }

                    clip.play(pause)
                    awaitState("the pause") { it.mode == Mode.PAUSED }

                    clip.play(wakeUp)
                    awaitState("the wake-up") { it.mode == Mode.LISTENING }

                    clip.play(off)
                    awaitState("the end") { it.mode == Mode.OFF }
                    assertTrue("The voice session did not close", VoiceTests.waitUntil(30_000) { !listening.sessionOpen.value })
                    assertTrue("ListeningService still runs", VoiceTests.waitUntil(10_000) { !VoiceTests.listeningServiceRuns() })
                }
            } finally {
                watcher.cancel()
            }
        }
        VoiceTests.log("Conversation: 5 utterances in ${SystemClock.elapsedRealtime() - started} ms")
    }

    /**
     * How many replies Kestrel has started: the times that speaking turned on. The state is a conflated StateFlow, so a
     * reply that ended at once (refused or stopped) could be missed; this test's replies play for about a second each.
     */
    private fun replies(): Int = states.toList().zipWithNext().count { (before, after) -> !before.speaking && after.speaking }

    private fun awaitState(step: String, condition: (State) -> Boolean) {
        assertTrue("No $step; the state is ${listening.state.value}", VoiceTests.waitUntil(30_000) { condition(listening.state.value) })
    }
}
