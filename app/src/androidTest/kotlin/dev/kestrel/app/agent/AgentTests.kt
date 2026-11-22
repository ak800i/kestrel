package dev.kestrel.app.agent

import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.app.AppGraph
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.commands.TextSessions
import dev.kestrel.app.listen.VoiceSessions
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.TaskAsk
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.Part

/**
 * Runs the agent's tasks on the device through the same path as speech (design D27 of sub-project 5): listening with
 * [TextSessions], which need no speech models, a transcript "Kestrel, <task>" to the Orchestrator, and plan 5A's
 * ScriptedModel as the task's model, so that no test calls a provider. The tests answer questions as the owner's voice
 * would, with transcripts.
 */
internal object AgentTests {
    val graph: AppGraph
        get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KestrelApplication).graph

    private var previous: VoiceSessions? = null

    /** Turns listening on with [TextSessions]; Kestrel's accessibility service must be on (ScreenServiceRule). */
    fun start() {
        VoiceTests.grantListeningPermissions()
        val listening = graph.listening
        if (listening.state.value.mode != Mode.OFF) turnOff()
        if (previous == null) previous = listening.sessions
        listening.sessions = TextSessions()
        listening.send(Event.Start())
        check(VoiceTests.waitUntil(15_000) { listening.sessionOpen.value && listening.state.value.mode == Mode.LISTENING }) {
            "Listening did not start"
        }
    }

    /** Turns Kestrel off, removes the scripted model, and puts the voice sessions back. */
    fun stop() {
        try {
            turnOff()
        } finally {
            graph.agent.modelOverride = null
            previous?.let { graph.listening.sessions = it }
            previous = null
        }
    }

    private fun turnOff() {
        val listening = graph.listening
        listening.send(Event.TurnOff)
        check(VoiceTests.waitUntil(30_000) { listening.state.value.mode == Mode.OFF && !listening.sessionOpen.value }) { "Listening did not stop" }
    }

    /** Gives [request] as the task "Kestrel, <request>" with [model] as its model, and returns the task's number. */
    fun run(request: String, model: ScriptedModel): Int {
        // After a task, Kestrel speaks its report first; a transcript counts as a task again once the work is idle.
        check(VoiceTests.waitUntil(30_000) { graph.listening.state.value.work == Work.IDLE }) { "Kestrel was still busy" }
        graph.agent.modelOverride = model
        val before = graph.listening.state.value.task
        graph.listening.send(Event.Heard("Kestrel, $request", named = false))
        check(VoiceTests.waitUntil(5_000) { graph.listening.state.value.task > before }) { "The transcript started no task" }
        return graph.listening.state.value.task
    }

    /** Waits until task [task] asks a question of [kind]. */
    fun awaitAsking(task: Int, kind: TaskAsk, timeoutMillis: Long = 30_000) {
        check(VoiceTests.waitUntil(timeoutMillis) {
            val state = graph.listening.state.value
            state.task == task && state.taskAsking == kind
        }) { "The task did not ask" }
    }

    /** Answers the open question with [words], as the owner's voice would. */
    fun say(words: String) = graph.listening.send(Event.Heard(words, named = false))

    /** Waits until task [task] has ended, and returns its end. */
    fun ended(task: Int, timeoutMillis: Long = 90_000): AgentRunner.Ended {
        check(VoiceTests.waitUntil(timeoutMillis) { graph.agent.ended(task) != null && !graph.agent.running(task) }) { "The task did not end" }
        return graph.agent.ended(task)!!
    }

    /** Every text of the requests that [model] received: the system prompt, the context and the messages' texts. */
    fun texts(model: ScriptedModel): String = model.requests.joinToString("\n") { request ->
        (listOfNotNull(request.system, request.context) + request.messages.flatMap { message ->
            when (message) {
                is ChatMessage.User -> message.parts.filterIsInstance<Part.Text>().map { it.text }
                is ChatMessage.ToolResult -> listOf(message.text)
                is ChatMessage.Assistant -> emptyList()
            }
        }).joinToString("\n")
    }

    /** The kinds of the presses that `:testapp`'s fixtures recorded (design D27). */
    fun presses(screen: ScreenServiceRule): List<String> =
        screen.shell("run-as ${ScreenServiceRule.FIXTURES} cat files/presses.log").lines().filter { it.isNotBlank() }.map { it.substringBefore(' ') }

    fun clearPresses(screen: ScreenServiceRule) {
        screen.shell("run-as ${ScreenServiceRule.FIXTURES} rm -f files/presses.log")
    }
}
