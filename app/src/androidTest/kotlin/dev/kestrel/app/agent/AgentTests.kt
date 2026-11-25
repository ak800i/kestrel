package dev.kestrel.app.agent

import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.agent.port.ModelPort
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.app.AppGraph
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.commands.TextSessions
import dev.kestrel.app.listen.VoiceSessions
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.engine.CommandOutcome
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.Part

/**
 * Runs the agent's tasks on the device through the same path as speech (design D27 of sub-project 5): listening with
 * [TextSessions], which need no speech models, a transcript "Kestrel, <task>" to the Orchestrator and from it to the
 * conversation, which runs it as a goal (plan 7D), and plan 5A's ScriptedModel as the round's model, so that no test
 * calls a provider. Each task opens a conversation of its own. The tests answer questions as the owner's voice would,
 * with transcripts.
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

    /** Stops a goal that is still open, turns Kestrel off, removes the scripted model, and puts the voice sessions back. */
    fun stop() {
        try {
            endGoal()
            turnOff()
        } finally {
            graph.roundModels.modelOverride = null
            previous?.let { graph.listening.sessions = it }
            previous = null
        }
    }

    // Plan 7B's decision 10: turning off leaves a goal running, so "stop" ends it first.
    private fun endGoal() {
        val conversations = graph.conversations
        if (conversations.view.value.goal == null) return
        say("stop")
        check(VoiceTests.waitUntil(30_000) { conversations.view.value.goal == null }) { "The goal did not end" }
    }

    /** Turns Kestrel off; a goal that works goes on (plan 7B's decision 10). */
    fun turnOff() {
        val listening = graph.listening
        listening.send(Event.TurnOff)
        check(VoiceTests.waitUntil(30_000) { listening.state.value.mode == Mode.OFF && !listening.sessionOpen.value }) { "Listening did not stop" }
    }

    /**
     * Gives [request] as the task "Kestrel, <request>" with [model] as its model, in a conversation of its own, and returns
     * the number of the conversation's command that runs it.
     */
    fun run(request: String, model: ModelPort): Int {
        // After a task, Kestrel speaks its report first; a transcript counts as a command again once the work is idle.
        check(VoiceTests.waitUntil(30_000) { graph.listening.state.value.work == Work.IDLE }) { "Kestrel was still busy" }
        endGoal()
        closeConversation()
        val conversations = graph.conversations
        graph.roundModels.modelOverride = model
        val before = conversations.view.value.command
        graph.listening.send(Event.Heard("Kestrel, $request", named = false))
        check(VoiceTests.waitUntil(5_000) { conversations.view.value.command > before }) { "The transcript started no command" }
        return conversations.view.value.command
    }

    // A new command in the open conversation would be a follow-up (design 6.2 of sub-project 7). The quiet time ends only
    // while it runs, not while Kestrel still speaks the earlier reply, which holds it.
    private fun closeConversation() {
        val conversations = graph.conversations
        check(VoiceTests.waitUntil(30_000) { !graph.listening.state.value.speaking && conversations.view.value.quiet !is QuietClock.Held }) {
            "The conversation's quiet time stayed held"
        }
        conversations.endQuietTime()
        check(VoiceTests.waitUntil(5_000) { conversations.view.value.id == null }) { "The conversation did not close" }
    }

    /** Waits until command [command]'s goal asks a question of [kind], and the voice reads its answer. */
    fun awaitAsking(command: Int, kind: QuestionKind, timeoutMillis: Long = 30_000) {
        check(VoiceTests.waitUntil(timeoutMillis) {
            val view = graph.conversations.view.value
            view.command == command && view.asking == kind && graph.listening.state.value.openQuestion?.kind == kind
        }) { "The task did not ask" }
    }

    /** Answers the open question with [words], as the owner's voice would. */
    fun say(words: String) = graph.listening.send(Event.Heard(words, named = false))

    /** Waits until command [command] has ended, and returns its outcome: its report and Kestrel's final words. */
    fun ended(command: Int, timeoutMillis: Long = 90_000): CommandOutcome {
        check(VoiceTests.waitUntil(timeoutMillis) { graph.conversations.outcome(command) != null }) { "The task did not end" }
        return graph.conversations.outcome(command)!!
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

    /** What Kestrel said through [TextSessions] since listening started, in order. */
    fun spoken(): List<String> = (graph.listening.sessions as TextSessions).spoken

    /** The kinds of the presses that `:testapp`'s fixtures recorded (design D27). */
    fun presses(screen: ScreenServiceRule): List<String> =
        screen.shell("run-as ${ScreenServiceRule.FIXTURES} cat files/presses.log").lines().filter { it.isNotBlank() }.map { it.substringBefore(' ') }

    fun clearPresses(screen: ScreenServiceRule) {
        screen.shell("run-as ${ScreenServiceRule.FIXTURES} rm -f files/presses.log")
    }
}
