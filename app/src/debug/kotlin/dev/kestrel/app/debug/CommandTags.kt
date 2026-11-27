package dev.kestrel.app.debug

import dev.kestrel.app.listen.ListeningTexts
import dev.kestrel.app.orchestrator.Effect
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Feedback
import dev.kestrel.app.orchestrator.Reply
import dev.kestrel.app.orchestrator.State
import dev.kestrel.app.orchestrator.Step
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.conversation.machine.ConversationEffect
import dev.kestrel.conversation.machine.ConversationEvent
import dev.kestrel.conversation.machine.ConversationState
import dev.kestrel.conversation.machine.MachineStep
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The tags of the transcripts that the automatic A71 checks and :tools:eval send (plan 4D, hook H1; design 14.4 of
 * sub-project 7): which command each tagged transcript started, answered or cancelled, or which of the conversation's
 * commands it started, and for each instant command the hashes of what Kestrel said and showed and the route that it took.
 * [onStep] reads every step of the Orchestrator on the Orchestrator's coroutine, and finds a tagged transcript by its
 * identity, so that no other transcript with the same words takes its tag. A tagged transcript that gave the conversation
 * an input gets the hook's options (`model`, `live`, `marker`) on that input ([input]), and [onConversationStep] then
 * notes the number of the conversation's command that the input started: a goal's round, Kestrel's own request or a
 * switch, also when it waited for an earlier one. It also counts the words of the newest goal ([lastGoalWords]) for the
 * A71 session. It keeps the newest [MAX_TAGS] tags and [MAX_COMMANDS] commands, in memory only, and holds tags, numbers,
 * kinds, options and hashes, never words.
 */
internal class CommandTags {
    enum class Role { PENDING, COMMAND, CONVERSE, TASK, ANSWER, CANCEL, NONE }

    /**
     * What a tagged transcript did: its [role] for [command] (0 for none), how Kestrel's Requests read it, and the number
     * of the conversation's command that it started ([task], 0 for none).
     */
    data class Tagged(val role: Role, val command: Int, val read: String, val task: Int = 0)

    /** What a command said and showed, as hashes, oldest first, at most [MAX_TEXTS] each, and its route. */
    data class Record(val said: List<String> = emptyList(), val shown: List<String> = emptyList(), val detail: String? = null)

    private val tags = LinkedHashMap<String, Tagged>()
    private val waiting = IdentityHashMap<Event.Heard, String>()
    private val conversing = IdentityHashMap<Event.Heard, String>()
    private val inputs = LinkedHashMap<Long, String>()
    private val options = HashMap<String, Map<String, String>>()
    private val answered = HashMap<Int, String>()
    private val records = LinkedHashMap<Int, Record>()

    /** How many words the newest goal's command had, or 0 before the first goal (plan 2E's count for the A71 session). */
    @Volatile
    var lastGoalWords: Int = 0
        private set

    /**
     * Gets the conversation that a tagged input's work opened, which the hooks count as their own (Task 13a);
     * [HookConversations.watch] sets it at the process's start. It runs under this object's lock.
     */
    @Volatile
    var ownConversation: (ConversationId) -> Unit = {}

    /**
     * Gets the conversation that the owner's input with his words or his choice reached (a command, an answer or a
     * switch, never a hook's), which is his and never the hooks' (Task 13a, review low risk and review M1);
     * [HookConversations.watch] sets it at the process's start. It runs under this object's lock.
     */
    @Volatile
    var ownerConversation: (ConversationId) -> Unit = {}

    /**
     * Gets, after each of the conversation's steps, whether Kestrel's own request that runs while no conversation is open
     * is the hooks' (plan 7G): [HookConversations.watch] sets it at the process's start. It runs under this object's lock.
     */
    @Volatile
    var ownRequest: (Boolean) -> Unit = {}

    // Kestrel's own request that runs while no conversation is open, by its command's number, and whether it is the hooks'.
    private var request: Pair<Int, Boolean>? = null

    // The inputs that the hooks gave the conversation, tagged or not: never the owner's (review M2 of Task 13a). They stay
    // noted after the step that started their work, because a local request's switch names its input again later.
    private val hookInputs = LinkedHashSet<Long>()

    // The transcripts that a hook sent without a tag, until the Orchestrator's step, and those of them that went on to the
    // conversation, until Listening makes their input.
    private val hookHeard: MutableSet<Event.Heard> = Collections.newSetFromMap(IdentityHashMap())
    private val hookConversing: MutableSet<Event.Heard> = Collections.newSetFromMap(IdentityHashMap())

    /**
     * Notes [input], which a hook gives the conversation without a tag (`conversation-type`, `conversation-control do new`
     * or agent-answer's typed answer), as the hooks' and not the owner's.
     */
    @Synchronized
    fun untagged(input: OwnerInput) = noteHooks(input.id)

    /**
     * Notes [event], a transcript that a hook sends without a tag (voice heard, agent-answer's spoken answer), so that the
     * input that Listening makes of it is the hooks' and not the owner's.
     */
    @Synchronized
    fun untagged(event: Event.Heard) {
        hookHeard += event
    }

    private fun noteHooks(id: Long) {
        hookInputs += id
        while (hookInputs.size > MAX_TAGS) hookInputs.remove(hookInputs.first())
    }

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
        dropOldest()
        return true
    }

    /**
     * Links [tag] to [input], which the hook `conversation-type` gives the conversation as if the owner had typed it on the
     * conversation screen (design 14.4 of sub-project 7), and returns the input with the hook's [options]; null when the
     * tag is among the newest ones already. [read] is the input's kind. The conversation's step that starts work for the
     * input then gives the tag the command's number, as for a transcript's input ([onConversationStep]).
     */
    @Synchronized
    fun typed(tag: String, input: OwnerInput, read: String, options: Map<String, String> = emptyMap()): OwnerInput? {
        if (tag in tags) return null
        tags[tag] = Tagged(Role.CONVERSE, 0, read)
        inputs[input.id] = tag
        noteHooks(input.id)
        dropOldest()
        while (inputs.size > MAX_TAGS) inputs.remove(inputs.keys.first())
        return if (options.isEmpty()) input else input.copy(options = options)
    }

    // Keeps the newest MAX_TAGS tags, with what each one waits for.
    private fun dropOldest() {
        while (tags.size > MAX_TAGS) {
            val oldest = tags.keys.first()
            tags.remove(oldest)
            waiting.values.remove(oldest)
            conversing.values.remove(oldest)
            inputs.values.remove(oldest)
            options.remove(oldest)
        }
    }

    @Synchronized
    fun tagged(tag: String): Tagged? = tags[tag]

    /** [event] never reaches the Orchestrator, as a match that a hook drops: its tag, if it has one, did nothing. */
    @Synchronized
    fun dropped(event: Event.Heard) {
        hookHeard.remove(event)
        val tag = waiting.remove(event) ?: return
        options.remove(tag)
        tags[tag]?.let { tags[tag] = it.copy(role = Role.NONE, command = 0) }
    }

    @Synchronized
    fun record(command: Int): Record = records[command] ?: Record()

    /** Notes what the Orchestrator's [step] after [event], from the state [before], did for the tags and the commands. */
    @Synchronized
    fun onStep(event: Event, before: State, step: Step) {
        val running = if (before.work == Work.RUNNING_COMMAND) before.command else 0
        val started = step.effects.filterIsInstance<Effect.RunCommand>().lastOrNull()
        val heard = event as? Event.Heard
        val conversed = step.effects.filterIsInstance<Effect.Converse>().lastOrNull { it.heard != null && it.heard === heard }
        val tag = heard?.let { waiting.remove(it) }
        if (heard != null && hookHeard.remove(heard) && conversed != null) hookConversing += heard
        val tagged = tag?.let { tags[it] }
        val answer = step.effects.filterIsInstance<Effect.AnswerCommand>().firstOrNull()
        if (tag != null && tagged != null) {
            tags[tag] = when {
                started != null -> tagged.copy(role = Role.COMMAND, command = started.command)
                conversed != null -> tagged.copy(role = Role.CONVERSE, command = 0)
                answer != null -> tagged.copy(role = Role.ANSWER, command = answer.command)
                running > 0 && Effect.CancelCommand in step.effects -> tagged.copy(role = Role.CANCEL, command = running)
                else -> tagged.copy(role = Role.NONE, command = 0)
            }
            // The options wait for the input that Listening makes of the transcript; any other end drops them.
            if (conversed != null) conversing[heard] = tag else options.remove(tag)
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
    }

    /**
     * The input that Listening made of [heard] for the conversation, with the options of its tag, if it has one; the
     * conversation's step that starts work for the input then gives its tag the command's number. The input of a hook's
     * transcript, tagged or not, is the hooks'.
     */
    @Synchronized
    fun input(heard: Event.Heard?, input: OwnerInput): OwnerInput {
        if (heard != null && hookConversing.remove(heard)) noteHooks(input.id)
        val tag = heard?.let { conversing.remove(it) } ?: return input
        inputs[input.id] = tag
        noteHooks(input.id)
        while (inputs.size > MAX_TAGS) inputs.remove(inputs.keys.first())
        return options.remove(tag)?.let { input.copy(options = it) } ?: input
    }

    /**
     * Notes which of the conversation's commands an input of a tagged transcript started (design 14.4 of sub-project 7). An
     * input that started nothing and does not wait for the goal's end, as steering, an answer or a stop, did nothing that
     * the tag counts.
     */
    @Synchronized
    fun onConversationStep(event: ConversationEvent, state: ConversationState, step: MachineStep) {
        // A mark means that only the hooks' inputs are in the conversation (review M1 of Task 13a): the owner's input that
        // carries his words or his choice takes the mark away from the conversation that it reaches, the one that the step
        // leaves open, or for a switch the one that it moves into, also when the switch ends a local request later.
        val given = (event as? ConversationEvent.Input)?.input
        val brought = (listOfNotNull(given) + step.effects.mapNotNull { work(it)?.second }).distinctBy { it.id }.filter(::owners)
        brought.mapNotNull { reached(it, step) }.distinct().forEach(ownerConversation)
        // A request that runs while no conversation is open, as a switch that asks before it moves into a past conversation,
        // has no conversation to carry the mark, so it carries the mark itself (plan 7G): the request that a hook's input
        // started, until an input of the owner's with his words or his choice reaches it.
        val running = step.state.local?.takeIf { !it.cancelled && step.state.id == null }
        request = running?.let { local ->
            val hooks = request?.takeIf { it.first == local.command }?.second ?: (local.input.id in hookInputs)
            local.command to (hooks && brought.isEmpty())
        }
        ownRequest(request?.second == true)
        val started = mutableSetOf<Long>()
        for (effect in step.effects) {
            if (effect is ConversationEffect.StartGoal) lastGoalWords = effect.text.split(WHITESPACE).count { it.isNotEmpty() }
            val (command, input) = work(effect) ?: continue
            if (input != null) started += input.id
            // Only a conversation that a hook's input's work opened is the hooks' own, the input tagged or given without a
            // tag, a goal's work or a local request's: a switch moves into a past conversation, and a follow-up continues the
            // open one, either of which may be the owner's (plan 7G's review N1). This is the one rule that marks the hooks'
            // conversations (review B-I2 of plan 7F).
            val hooks = input != null && input.id in hookInputs
            if (hooks && effect !is ConversationEffect.SwitchTo && step.state.id != state.id) step.state.id?.let(ownConversation)
            val tag = input?.let { inputs.remove(it.id) } ?: continue
            tags[tag]?.let { tags[tag] = it.copy(role = Role.TASK, task = command) }
        }
        val input = given ?: return
        if (input.id in started || step.state.waiting.any { it.id == input.id }) return
        val tag = inputs.remove(input.id) ?: return
        tags[tag]?.let { tags[tag] = it.copy(role = Role.NONE) }
    }

    // The command whose work [effect] starts, with the input that it starts for; null for an effect that starts none.
    private fun work(effect: ConversationEffect): Pair<Int, OwnerInput?>? = when (effect) {
        is ConversationEffect.StartGoal -> effect.command to effect.input
        is ConversationEffect.ResumeGoal -> effect.command to effect.input
        is ConversationEffect.RunLocal -> effect.command to effect.input
        is ConversationEffect.SwitchTo -> effect.command to effect.input
        else -> null
    }

    // The conversation that [input] reaches in [step]: the one that a switch for it moves into, or else the open one.
    private fun reached(input: OwnerInput, step: MachineStep): ConversationId? =
        step.effects.filterIsInstance<ConversationEffect.SwitchTo>().firstOrNull { it.input.id == input.id }?.id ?: step.state.id

    // The owner's input that brings his words or his choice: not the hooks', and not a wordless Stop, Continue or New
    // conversation, which the goal notification's buttons also give, for goal-control's tap in the shade as for his, nor
    // the end of a wait (Silence) or a withdrawn question.
    private fun owners(input: OwnerInput): Boolean = input.id !in hookInputs && when (val intent = input.intent) {
        InputIntent.Stop, InputIntent.Continue, InputIntent.NewConversation -> false
        is InputIntent.Answer -> intent.answer != ChannelAnswer.Silence && intent.answer != ChannelAnswer.Interrupted
        else -> true
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

        private val WHITESPACE = Regex("\\s+")

        /** The first 16 hexadecimal characters of the SHA-256 of [text]'s UTF-8 bytes (2026-11-20-a71-checks-4d-interfaces.md, section 1). */
        fun hash(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).take(8)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
