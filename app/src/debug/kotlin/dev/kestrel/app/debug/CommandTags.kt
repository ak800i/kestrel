package dev.kestrel.app.debug

import dev.kestrel.app.listen.ListeningTexts
import dev.kestrel.app.orchestrator.Effect
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Feedback
import dev.kestrel.app.orchestrator.Reply
import dev.kestrel.app.orchestrator.State
import dev.kestrel.app.orchestrator.Step
import dev.kestrel.app.orchestrator.Work
import java.security.MessageDigest
import java.util.IdentityHashMap

/**
 * The tags of the transcripts that the automatic A71 checks send (plan 4D, hook H1): which command each tagged transcript
 * started, answered or cancelled, or which task it started, and for each command the hashes of what Kestrel said and
 * showed and the route that it took. [onStep] reads every step of the Orchestrator on the Orchestrator's coroutine, and
 * finds a tagged transcript by its identity, so that no other transcript with the same words takes its tag; the hooks read
 * on theirs. A tagged transcript's options (the hook's extras, such as `model` and `live`) come back from [onStep] with
 * the number of the task that it started, before the task runs, for sub-project 5's AgentRunner. It keeps the newest
 * [MAX_TAGS] tags and [MAX_COMMANDS] commands, in memory only, and holds tags, numbers, kinds, options and hashes, never
 * words.
 */
internal class CommandTags {
    enum class Role { PENDING, COMMAND, TASK, ANSWER, CANCEL, NONE }

    /**
     * What a tagged transcript did: its [role] for [command] (0 for none), how Kestrel's Requests read it, and the number
     * of the [task] that it started (0 for none).
     */
    data class Tagged(val role: Role, val command: Int, val read: String, val task: Int = 0)

    /** What a command said and showed, as hashes, oldest first, at most [MAX_TEXTS] each, and its route. */
    data class Record(val said: List<String> = emptyList(), val shown: List<String> = emptyList(), val detail: String? = null)

    /** Task number [task] started from a tagged transcript that carried [options]. */
    data class StartedTask(val task: Int, val options: Map<String, String>)

    private val tags = LinkedHashMap<String, Tagged>()
    private val waiting = IdentityHashMap<Event.Heard, String>()
    private val options = HashMap<String, Map<String, String>>()
    private val answered = HashMap<Int, String>()
    private val records = LinkedHashMap<Int, Record>()

    /**
     * Links [tag] and the hook's [options] to [event] before Kestrel handles it; false when the tag is among the newest
     * ones already.
     */
    @Synchronized
    fun register(tag: String, event: Event.Heard, read: String, options: Map<String, String> = emptyMap()): Boolean {
        if (tag in tags) return false
        tags[tag] = Tagged(Role.PENDING, 0, read)
        waiting[event] = tag
        if (options.isNotEmpty()) this.options[tag] = options
        while (tags.size > MAX_TAGS) {
            val oldest = tags.keys.first()
            tags.remove(oldest)
            waiting.values.remove(oldest)
            this.options.remove(oldest)
        }
        return true
    }

    @Synchronized
    fun tagged(tag: String): Tagged? = tags[tag]

    /** [event] never reaches the Orchestrator, as a match that a hook drops: its tag, if it has one, did nothing. */
    @Synchronized
    fun dropped(event: Event.Heard) {
        val tag = waiting.remove(event) ?: return
        options.remove(tag)
        tags[tag]?.let { tags[tag] = it.copy(role = Role.NONE, command = 0) }
    }

    @Synchronized
    fun record(command: Int): Record = records[command] ?: Record()

    /**
     * Notes what the Orchestrator's [step] after [event], from the state [before], did for the tags and the commands. When
     * a tagged transcript started a task, returns its number with the transcript's options; null otherwise.
     */
    @Synchronized
    fun onStep(event: Event, before: State, step: Step): StartedTask? {
        val running = if (before.work == Work.RUNNING_COMMAND) before.command else 0
        val started = step.effects.filterIsInstance<Effect.RunCommand>().lastOrNull()
        val task = step.effects.filterIsInstance<Effect.RunTask>().lastOrNull()
        val tag = (event as? Event.Heard)?.let { waiting.remove(it) }
        val tagged = tag?.let { tags[it] }
        val answer = step.effects.filterIsInstance<Effect.AnswerCommand>().firstOrNull()
        var startedTask: StartedTask? = null
        if (tag != null && tagged != null) {
            tags[tag] = when {
                started != null -> tagged.copy(role = Role.COMMAND, command = started.command)
                task != null -> tagged.copy(role = Role.TASK, command = 0, task = task.task)
                answer != null -> tagged.copy(role = Role.ANSWER, command = answer.command)
                running > 0 && Effect.CancelCommand in step.effects -> tagged.copy(role = Role.CANCEL, command = running)
                else -> tagged.copy(role = Role.NONE, command = 0)
            }
            val given = options.remove(tag).orEmpty()
            if (task != null && started == null) startedTask = StartedTask(task.task, given)
        }
        // The newest answer to each command: its tag, or none when it was not tagged, so that an answer that is passed on
        // moves only its own tag.
        if (answer != null && answer.text != null) {
            if (tag != null && tags[tag]?.role == Role.ANSWER) answered[answer.command] = tag else answered.remove(answer.command)
        }
        // A cancelled command ends without CommandEnded, so its entry goes here.
        if (running > 0 && Effect.CancelCommand in step.effects) answered.remove(running)
        if (event is Event.CommandEnded) {
            event.result.detail?.let { detail -> update(event.command) { it.copy(detail = detail) } }
            // An answer that was no answer: the Orchestrator read it as a new utterance, which started a command.
            val passed = answered.remove(event.command)
            if (event.result.passOn != null && started != null && passed != null) {
                tags[passed]?.let { tags[passed] = it.copy(role = Role.COMMAND, command = started.command) }
            }
        }
        var about = when (event) {
            is Event.CommandEnded -> event.command
            is Event.CommandSays -> event.command
            is Event.CommandAsks -> event.command
            is Event.CommandCounts -> event.command
            else -> running
        }
        for (effect in step.effects) {
            when {
                // What comes after a command's start in the same step is that command's.
                effect is Effect.RunCommand -> about = effect.command
                effect is Effect.Say -> when (val reply = effect.reply) {
                    is Reply.Command -> add(reply.command, said = reply.text)
                    is Reply.Text -> if (event is Event.CommandEnded) add(about, said = reply.text)
                    else -> Unit
                }
                effect is Effect.Show -> when (val feedback = effect.feedback) {
                    is Feedback.CommandText -> add(about, shown = feedback.text)
                    Feedback.CommandCancelled -> add(about, shown = ListeningTexts.CANCELLED)
                    else -> Unit
                }
            }
        }
        return startedTask
    }

    private fun add(command: Int, said: String? = null, shown: String? = null) {
        if (command <= 0) return
        update(command) { record ->
            record.copy(
                said = said?.let { (record.said + hash(it)).takeLast(MAX_TEXTS) } ?: record.said,
                shown = shown?.let { (record.shown + hash(it)).takeLast(MAX_TEXTS) } ?: record.shown,
            )
        }
    }

    private fun update(command: Int, change: (Record) -> Record) {
        records[command] = change(records[command] ?: Record())
        while (records.size > MAX_COMMANDS) records.remove(records.keys.first())
    }

    companion object {
        const val MAX_TAGS = 64
        const val MAX_COMMANDS = 32
        const val MAX_TEXTS = 8

        /** The first 16 hexadecimal characters of the SHA-256 of [text]'s UTF-8 bytes (2026-11-20-a71-checks-4d-interfaces.md, section 1). */
        fun hash(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).take(8)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
