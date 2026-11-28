package dev.kestrel.app.debug

import dev.kestrel.app.listen.ListeningTexts
import dev.kestrel.app.orchestrator.Effect
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Feedback
import dev.kestrel.app.orchestrator.Reply
import dev.kestrel.app.orchestrator.State
import dev.kestrel.app.orchestrator.Step
import dev.kestrel.app.orchestrator.Work
import dev.kestrel.conversation.channel.DialogueEvent
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
 * switch, also when its work came in a later step: it waited for an earlier one, or it answered "Continue the goal?" and
 * the goal's round resumed for it. It also counts the words of the newest goal ([lastGoalWords]) for the
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
     * Gets the conversation that an act of the owner's reached, any act whose number the hooks did not claim (a command, an
     * answer, a continue, a stop or a switch, on any channel), which is his and never the hooks' (Task 13a, review low risk
     * and review M1; plan 7G);
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

    // Kestrel's own request that runs, in a conversation or not, and whether it is the owner's.
    private var request: Request? = null

    /**
     * Kestrel's own request that runs, by its [command]'s number: [owners] once an act of the owner's started or reached it
     * (plan 7G).
     */
    private data class Request(val command: Int, val owners: Boolean)

    // The numbers of the hooks' own acts, which they claimed before each act reached Kestrel: their inputs, tagged or not,
    // the inputs that a session made of their clips (voice-clip), their presses of a card and their presses of the goal
    // notification's buttons. Every other act is the owner's (plan 7G). A claim lives as long as its act can still come up
    // in a step, and no count of newer claims ends it (the whole-plan review's M5): it goes after the step in which the
    // conversation took its act, unless the machine holds the act's work for a later step (ConversationState.pendingInputs)
    // or the act started Kestrel's own request that runs, whose switch names it again when the request ends; it then goes
    // after the step in which that work started, ended or was dropped ([release]). Nothing caps them: a claim whose act
    // never reached the conversation, as a press that a card or the notification refused, holds a number that no later act
    // carries (InputIds), so it can never make another act the hooks', and it goes with the process.
    private val claimed = HashSet<Long>()

    // The transcripts that a hook sent without a tag, until the Orchestrator's step, and how many inputs Listening still
    // makes for the conversation of each hook's transcript, tagged or not, which are all the hooks' (a "stop" before a
    // command gives two).
    private val hookHeard: MutableSet<Event.Heard> = Collections.newSetFromMap(IdentityHashMap())
    private val hookConversing = IdentityHashMap<Event.Heard, Int>()

    /**
     * Notes [input], which a hook gives the conversation without a tag (`conversation-type`, `conversation-control do new`,
     * `goal-control do stop-typed` or agent-answer's typed answer), as the hooks' and not the owner's.
     */
    @Synchronized
    fun untagged(input: OwnerInput) = claimAct(input.id)

    /**
     * Claims [act], the number among the inputs that a hook's own act carries when it is no input of theirs, as its press of
     * the card over the app (`CardPresser`) or of a button of the goal notification (`ButtonClaims`), as the hooks' and not
     * the owner's, before the act reaches Kestrel (plan 7G).
     */
    @Synchronized
    fun claim(act: Long) = claimAct(act)

    /**
     * Notes [event], a transcript that a hook sends without a tag (voice heard, agent-answer's spoken answer), so that the
     * inputs that Listening makes of it are the hooks' and not the owner's.
     */
    @Synchronized
    fun untagged(event: Event.Heard) {
        hookHeard += event
    }

    private fun claimAct(id: Long) {
        claimed += id
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
        claimAct(input.id)
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
        // A transcript may give the conversation more than one input, as a stop before a command: its tag goes to the last.
        val converses = step.effects.filterIsInstance<Effect.Converse>().filter { it.heard != null && it.heard === heard }
        val conversed = converses.lastOrNull()
        val tag = heard?.let { waiting.remove(it) }
        val fromHooks = heard != null && hookHeard.remove(heard)
        if (heard != null && (fromHooks || tag != null) && conversed != null) hookConversing[heard] = converses.size
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
     * conversation's step that starts work for the input then gives its tag the command's number. Every input of a hook's
     * transcript, tagged or not, is the hooks', and a tag goes to the transcript's last input. So is every input that a
     * session heard through the hooks' clip microphone ([clip]; voice-clip), the spotter's stop too: Kestrel hears only
     * the clips through it, never the room (plan 7G).
     */
    @Synchronized
    fun input(heard: Event.Heard?, input: OwnerInput, clip: Boolean = false): OwnerInput {
        if (clip) claimAct(input.id)
        val left = heard?.let { hookConversing[it] } ?: return input
        claimAct(input.id)
        if (left > 1) {
            hookConversing[heard] = left - 1
            return input
        }
        hookConversing.remove(heard)
        val tag = conversing.remove(heard) ?: return input
        inputs[input.id] = tag
        while (inputs.size > MAX_TAGS) inputs.remove(inputs.keys.first())
        return options.remove(tag)?.let { input.copy(options = it) } ?: input
    }

    /**
     * Notes which of the conversation's commands an input of a tagged transcript started (design 14.4 of sub-project 7),
     * in its own step or in a later one while its work was still to come ([ConversationState.pendingInputs]): a command
     * that waited for the goal's end, or the answer to "Continue the goal?", whose round resumes with the question's own answer. An
     * input whose work neither started nor is still to come, as steering, a stop or an answer to another question, did
     * nothing that the tag counts, and neither did a held input once the machine dropped it.
     */
    @Synchronized
    fun onConversationStep(event: ConversationEvent, state: ConversationState, step: MachineStep) {
        // A mark means that only the hooks' acts are in the conversation (review M1 of Task 13a). An act of the owner's
        // ([ownersActs]), any act whose number the hooks did not claim, on any channel, reaches Kestrel's own request that
        // runs, and the conversation that the step leaves open, or for an input whose work is a switch, the switch's target
        // instead. A request that the owner started or reached is his, and so is the conversation that its switch moves
        // into, also when the switch comes at the end of a request that a hook's input started, as after his "yes" to the
        // switch's question (plan 7G). The mark goes from every conversation that he reached.
        val given = (event as? ConversationEvent.Input)?.input
        val acts = ownersActs(event, step)
        request = step.state.local?.let { local ->
            val owners = request?.takeIf { it.command == local.command }?.owners ?: owners(local.input)
            Request(local.command, owners || acts.isNotEmpty())
        }
        val switches = step.effects.filterIsInstance<ConversationEffect.SwitchTo>()
        val open = step.state.id?.takeIf { acts.any { act -> switches.none { it.input.id == act } } }
        val targets = switches.filter(::chosen).map { it.id }
        (listOfNotNull(open) + targets).distinct().forEach(ownerConversation)
        // A request that runs while no conversation is open, as a switch that asks before it moves into a past conversation,
        // has no conversation to carry the mark, so it carries the mark itself (plan 7G) while it is not the owner's.
        ownRequest(request?.owners == false && step.state.id == null && step.state.local?.cancelled == false)
        // The conversation that a switch opened in this step is the switch's target, which may be the owner's, so the work
        // of a command that waited for the switch never marks it; a conversation that such work opens after it, as after
        // the hooks' own "new conversation", is marked as any other (plan 7G).
        val switchedTo = step.effects.filterIsInstance<ConversationEffect.OpenSwitched>().lastOrNull()?.snapshot?.id
        val started = mutableSetOf<Long>()
        for (effect in step.effects) {
            if (effect is ConversationEffect.StartGoal) lastGoalWords = effect.text.split(WHITESPACE).count { it.isNotEmpty() }
            val (command, input) = work(effect) ?: continue
            if (input != null) started += input.id
            // Only a conversation that a hook's input's work opened is the hooks' own, the input tagged or given without a
            // tag, a goal's work or a local request's: a switch moves into a past conversation, and a follow-up continues the
            // open one, either of which may be the owner's (plan 7G's review N1). This is the one rule that marks the hooks'
            // conversations (review B-I2 of plan 7F).
            val hooksWork = input != null && hooks(input.id)
            val opened = step.state.id?.takeIf { it != state.id && it != switchedTo }
            if (hooksWork && effect !is ConversationEffect.SwitchTo) opened?.let(ownConversation)
            val tag = input?.let { inputs.remove(it.id) } ?: continue
            tags[tag]?.let { tags[tag] = it.copy(role = Role.TASK, task = command) }
        }
        // The one rule for work still to come (plan 7G): an input keeps its tag while the machine holds its work for a later
        // step (ConversationState.pendingInputs), and the tag goes to that work's command when it starts, above. The step's
        // own input and the inputs that were pending before it are settled here: one whose work neither started nor is
        // still to come, as steering, a stop, an answer to another question or a held input that the step dropped, did
        // nothing that the tag counts.
        val pending = step.state.pendingInputs.mapTo(HashSet()) { it.id }
        for (settled in listOfNotNull(given) + state.pendingInputs) {
            if (settled.id in started || settled.id in pending) continue
            val tag = inputs.remove(settled.id) ?: continue
            tags[tag]?.let { tags[tag] = it.copy(role = Role.NONE) }
        }
        release(event, state, step)
    }

    // The claims' lives (the whole-plan review's M5): every act of the step, every input whose work waited before it
    // (ConversationState.pendingInputs) and the input of the request that ran before it are settled once the step is over,
    // unless the act's work is still to come (pendingInputs after the step) or it started Kestrel's own request that still
    // runs. Only then does its claim go, so a hook's input that waits is never the owner's. Every other act reaches the
    // conversation in one step only: a tap on a card answers its question in the dialogue's answer, also the tap that
    // answers "Continue the goal?", whose goal resumes in that step (its re-checks' R1 and F1).
    private fun release(event: ConversationEvent, before: ConversationState, step: MachineStep) {
        val live = step.state.pendingInputs.mapTo(HashSet()) { it.id } + listOfNotNull(step.state.local?.input?.id)
        val settled = numbers(event, step) + before.pendingInputs.map { it.id } + listOfNotNull(before.local?.input?.id)
        for (number in settled) if (number !in live) claimed.remove(number)
    }

    // The command whose work [effect] starts, with the input that it starts for; null for an effect that starts none.
    private fun work(effect: ConversationEffect): Pair<Int, OwnerInput?>? = when (effect) {
        is ConversationEffect.StartGoal -> effect.command to effect.input
        is ConversationEffect.ResumeGoal -> effect.command to effect.input
        is ConversationEffect.RunLocal -> effect.command to effect.input
        is ConversationEffect.SwitchTo -> effect.command to effect.input
        else -> null
    }

    // Whether the owner chose [switch]: its request is his (the step's [request]), or its input is, for a switch whose request
    // this object never saw run.
    private fun chosen(switch: ConversationEffect.SwitchTo): Boolean =
        request?.takeIf { it.command == switch.command }?.owners ?: owners(switch.input)

    // The numbers of the owner's acts in [step] (plan 7G): each act whose number the hooks did not claim ([hooks]).
    private fun ownersActs(event: ConversationEvent, step: MachineStep): Set<Long> = acts(event, step).filterNot(::hooks).toSet()

    // The numbers of the acts in [step], by what each means and never by its channel (plan 7G): an act answers, continues,
    // stops, steers, switches or asks something else of Kestrel. They are the input of [event], spoken, typed, a tap on the
    // conversation screen or a button of the goal notification, the inputs that the step's work starts for, and a tap on a
    // card over the app, which reaches the conversation with its number and never as an input: the answer of a question
    // (the dialogue's answer, also to "Continue the goal?") or the Continue or Cancel that ended a hand-over. The
    // end of a wait (Silence) and a withdrawn question (Interrupted) answer nothing, so they are no acts.
    private fun acts(event: ConversationEvent, step: MachineStep): List<Long> =
        inputsOf(event, step).filter(::isAct).map { it.id } + listOfNotNull(tapOf(event)?.takeIf { it.answered }?.number)

    // Every number that [step] takes from its acts, also of an input or a tap that answers nothing.
    private fun numbers(event: ConversationEvent, step: MachineStep): List<Long> =
        inputsOf(event, step).map { it.id } + listOfNotNull(tapOf(event)?.number)

    // The input of [event] and the inputs that the step's work starts for.
    private fun inputsOf(event: ConversationEvent, step: MachineStep): List<OwnerInput> =
        listOfNotNull((event as? ConversationEvent.Input)?.input) + step.effects.mapNotNull { work(it)?.second }

    // The tap on a card over the app that [event] carries, with whether it answered: a question's answer, also to "Continue
    // the goal?", or the Continue or Cancel that ended a hand-over.
    private fun tapOf(event: ConversationEvent): Tap? = when (event) {
        is ConversationEvent.Dialogue -> when (val dialogue = event.event) {
            is DialogueEvent.Answered -> dialogue.tap?.let { Tap(it, answers(dialogue.answer)) }
            is DialogueEvent.HandOverDone -> dialogue.act?.let { Tap(it, answered = true) }
            else -> null
        }
        else -> null
    }

    private data class Tap(val number: Long, val answered: Boolean)

    private fun isAct(input: OwnerInput): Boolean = (input.intent as? InputIntent.Answer)?.let { answers(it.answer) } ?: true

    private fun answers(answer: ChannelAnswer?): Boolean = answer != null && answer != ChannelAnswer.Silence && answer != ChannelAnswer.Interrupted

    private fun owners(input: OwnerInput): Boolean = isAct(input) && !hooks(input.id)

    // The one rule (plan 7G): an act is the owner's unless the hooks claimed its number before the act.
    private fun hooks(act: Long): Boolean = act in claimed

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
