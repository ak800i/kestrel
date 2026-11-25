package dev.kestrel.conversation.engine

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.ResumeCause
import dev.kestrel.agent.goal.Steer
import dev.kestrel.agent.goal.TraceEntry
import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.channel.ChannelOutput
import dev.kestrel.conversation.channel.ConversationOwner
import dev.kestrel.conversation.channel.OwnerChannel
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.context.RecordBuilder
import dev.kestrel.conversation.context.RecordCondenser
import dev.kestrel.conversation.context.RecordText
import dev.kestrel.conversation.goal.GoalListener
import dev.kestrel.conversation.goal.GoalRunner
import dev.kestrel.conversation.goal.RoundEnd
import dev.kestrel.conversation.goal.RoundPlan
import dev.kestrel.conversation.goal.RunningGoal
import dev.kestrel.conversation.goal.toInput
import dev.kestrel.conversation.goal.toSteer
import dev.kestrel.conversation.local.LocalOutcome
import dev.kestrel.conversation.local.LocalRequests
import dev.kestrel.conversation.local.RecordedOwner
import dev.kestrel.conversation.machine.ConversationEffect
import dev.kestrel.conversation.machine.ConversationEvent
import dev.kestrel.conversation.machine.ConversationMachine
import dev.kestrel.conversation.machine.ConversationState
import dev.kestrel.conversation.machine.MachineStep
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.machine.Timer
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSettings
import dev.kestrel.conversation.model.ConversationSnapshot
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.GoalState
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.LineKind
import dev.kestrel.conversation.model.OwnerInput
import dev.kestrel.conversation.model.PendingSteer
import dev.kestrel.conversation.model.PhoneSignal
import dev.kestrel.conversation.model.ProcessEnd
import dev.kestrel.conversation.store.ConversationStore
import dev.kestrel.conversation.store.RecordRef
import dev.kestrel.safety.Category
import dev.kestrel.safety.TaskAllowance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The conversation as it runs (design 5.3 and 6 of sub-project 7). It applies [machine] to events one at a time, on one
 * coroutine of [scope], and performs each step's effects in order; anything that takes time (a goal's round, a local
 * request, "Continue the goal?", a timer, a channel's output) runs in a coroutine of its own and comes back as an event.
 * It keeps the open conversation's records and saves its snapshot at every change: a round's trace waits until the record
 * that holds it is saved, so that the record survives the end of Kestrel's process (design 6.7). Outputs are shown in
 * order, and the end of a final reply's `show` is the machine's `ReplyShown`. [view] is what the voice rules, the screen,
 * the goal service and the hooks read. [restore] must run once at process start; inputs wait for it. [report] gets a
 * failure by its class; [onStep] sees every step, for the hooks' tags (plan 7D): every effect that starts work under a
 * new command number (`StartGoal`, `ResumeGoal`, `RunLocal`, `SwitchTo`) carries the owner's input that asked for it.
 */
class ConversationEngine(
    private val scope: CoroutineScope,
    private val store: ConversationStore,
    private val goals: GoalRunner,
    private val local: LocalRequests,
    channels: Map<InputChannel, OwnerChannel>,
    permissions: (List<String>) -> PermissionEnd,
    private val settings: Flow<ConversationSettings>,
    private val testMode: Flow<Boolean>,
    private val voiceOn: Flow<Boolean>,
    private val clock: ConversationClock,
    private val report: (String, Throwable) -> Unit,
    private val machine: ConversationMachine = ConversationMachine { ConversationId.random() },
    private val onStep: (ConversationEvent, ConversationState, MachineStep) -> Unit = { _, _, _ -> },
) {
    private sealed interface Message {
        class Event(val event: ConversationEvent) : Message

        /** A change of record [record] of [conversation]; [done] completes once it is saved. */
        class Change(
            val conversation: ConversationId,
            val record: Int,
            val change: (CommandRecord) -> CommandRecord,
            val done: CompletableDeferred<Unit>?,
        ) : Message

        data object Save : Message

        /** Step [step] of the running round of record [record] of [conversation] started; it only changes the view. */
        class Step(val conversation: ConversationId, val record: Int, val step: Int) : Message
    }

    private val inbox = Channel<Message>(Channel.UNLIMITED)
    private val outputs = Channel<ChannelOutput>(Channel.UNLIMITED)
    private val started = CompletableDeferred<Unit>()
    private val timers = HashMap<Timer, Job>()
    private val outcomes = LinkedHashMap<Int, CommandOutcome>()

    @Volatile
    private var state = ConversationState()

    // The open conversation's records and the conversation that they belong to, the running goal, the jobs of a local
    // request and of "Continue the goal?", and whether the snapshot needs saving: only the engine's coroutine changes them.
    private val records = mutableListOf<CommandRecord>()
    private var recordsOf: ConversationId? = null
    private var running: RunningGoal? = null
    private var localJob: Job? = null
    private var continueJob: Job? = null
    private var dirty = false

    // The running round's newest step, which the view and the snapshot add to the goal's earlier steps (design 10.5).
    private var roundStep: Int? = null

    // How many ledger lines the running goal's record held when its round started; the round's report replaces the rest.
    private var roundLedgerFrom = 0

    // Whether Kestrel is on, for the owner, which adds the cards from the moment it turns off; [restore] reads it first.
    private val voiceNow = MutableStateFlow(false)

    /** The owner as goals and the conversation talk to him; the hooks read its open question, confirmation and hand-over. */
    val owner = ConversationOwner(channels, permissions, voiceNow.asStateFlow(), { ConversationOwner.Routing(state.replyChannel, state.settings.speakTypedReplies) }) {
        post(ConversationEvent.Dialogue(it))
    }

    private val published = MutableStateFlow(ConversationView())

    val view: StateFlow<ConversationView> = published.asStateFlow()

    /** What [restore] found, for the hook `conversation-control do=restore-check`; null before it ran. */
    @Volatile
    var restored: Restored? = null
        private set

    init {
        scope.launch {
            started.await()
            for (message in inbox) handle(message)
        }
        scope.launch {
            started.await()
            for (output in outputs) show(output)
        }
        scope.launch {
            combine(settings, testMode) { conversations, test -> ConversationEvent.SettingsChanged(conversations, test) }.collect { post(it) }
        }
        scope.launch {
            voiceOn.collect { on ->
                voiceNow.value = on
                post(ConversationEvent.Voice(on))
            }
        }
    }

    /**
     * At process start, before any input: reads the open conversation and applies design 6.7's rules to it, with how the
     * previous process ended ([previous], which `:app` reads from Android's exit records; plan 7E).
     */
    suspend fun restore(previous: ProcessEnd = ProcessEnd.Other): Restored {
        check(!started.isCompleted) { "restore() runs once, before any input" }
        try {
            process(ConversationEvent.SettingsChanged(settings.first(), testMode.first()))
            val on = voiceOn.first()
            voiceNow.value = on
            process(ConversationEvent.Voice(on))
            val snapshot = try {
                store.open()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Reading the open conversation", e)
                null
            }
            if (snapshot != null) load(snapshot)
            process(ConversationEvent.Restored(snapshot, previous))
            val found = when {
                snapshot == null -> Restored.NOTHING
                state.id == null -> Restored.CLOSED
                state.goal == null -> Restored.OPEN
                state.goal?.ending != null -> Restored.GOAL_ENDED
                else -> Restored.GOAL_HELD
            }
            restored = found
            return found
        } finally {
            started.complete(Unit)
        }
    }

    /** Any thread; never blocks. */
    fun submit(input: OwnerInput) = post(ConversationEvent.Input(input))

    fun signal(signal: PhoneSignal) = post(ConversationEvent.Signal(signal))

    /** The touch watcher saw the owner's touch during a goal (D13). */
    fun touched() = post(ConversationEvent.Touched)

    /** The conversation screen's field holds text while the screen is in front (design 6.2). */
    fun composing(active: Boolean) = post(ConversationEvent.Composing(active))

    /** Kestrel waits for a command after "Kestrel" alone, the side key or the microphone button (design 6.2). */
    fun awaitingCommand(active: Boolean) = post(ConversationEvent.Awaiting(active))

    /** The owner tapped "Reply…" on question [question]'s card (design 10.3). */
    fun replyOpened(question: Int) = post(ConversationEvent.ReplyOpened(question))

    /** The hook `conversation-control do=expire` (design 14.4). */
    fun endQuietTime() = post(ConversationEvent.EndQuietTime)

    /** How command [command] ended, among the newest [MAX_OUTCOMES]. */
    fun outcome(command: Int): CommandOutcome? = synchronized(outcomes) { outcomes[command] }

    private fun post(event: ConversationEvent) {
        inbox.trySend(Message.Event(event))
    }

    private suspend fun handle(message: Message) {
        try {
            when (message) {
                is Message.Event -> process(message.event)
                is Message.Change -> change(message)
                Message.Save -> {
                    dirty = true
                    save()
                }
                is Message.Step -> if (message.conversation == recordsOf && running?.record == message.record) {
                    roundStep = message.step
                    publish()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("The conversation", e)
        } finally {
            (message as? Message.Change)?.done?.complete(Unit)
        }
    }

    private suspend fun process(event: ConversationEvent) {
        val before = state
        val step = machine.next(before, event, clock.wallMillis())
        onStep(event, before, step)
        state = step.state
        for (effect in step.effects) {
            try {
                perform(effect, before)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("A conversation's effect", e)
            }
        }
        save()
        publish()
    }

    private suspend fun perform(effect: ConversationEffect, before: ConversationState) {
        when (effect) {
            is ConversationEffect.StartGoal -> startGoal(effect)
            is ConversationEffect.ResumeGoal -> resumeGoal(effect)
            is ConversationEffect.SteerGoal -> running?.let { steer(it, effect.text, effect.channel) }
            ConversationEffect.PauseGoal -> owner.pause()
            ConversationEffect.ResumePausedGoal -> owner.resume()
            is ConversationEffect.InterruptGoal -> running?.interrupt(effect.cause)
            is ConversationEffect.CancelGoal -> running?.cancel(effect.reason, effect.sentence)
            is ConversationEffect.EndHeldGoal -> endHeld(effect.reason)
            is ConversationEffect.AnswerQuestion -> owner.answer(effect.number, effect.answer, effect.leftScreen)
            is ConversationEffect.EndHandOver -> owner.endHandOver(effect.number, effect.end)
            ConversationEffect.AskContinueGoal -> askContinue()
            ConversationEffect.CancelContinueQuestion -> continueJob?.cancel()
            is ConversationEffect.Output -> outputs.trySend(effect.output)
            is ConversationEffect.AddLine -> edit(effect.record) {
                RecordBuilder.line(it, effect.kind, if (effect.kind.owners) RecordText.of(effect.text) else effect.text, effect.channel)
            }
            is ConversationEffect.EndRecord -> edit(effect.record) { RecordBuilder.ended(it, effect.end, effect.answer) }
            is ConversationEffect.RunLocal -> runLocal(effect)
            ConversationEffect.CancelLocal -> localJob?.cancel()
            is ConversationEffect.SwitchTo -> switchTo(effect.id, before)
            is ConversationEffect.StartTimer -> startTimer(effect)
            is ConversationEffect.Close -> close(effect, before)
            ConversationEffect.Persist -> dirty = true
        }
    }

    private fun startGoal(effect: ConversationEffect.StartGoal) {
        val id = state.id ?: return
        newRecord(id, effect.record, effect.input.channel, effect.text)
        val goal = RunningGoal(effect.record, effect.command, effect.input.options, emptyList(), listener(id, effect.record))
        running = goal
        roundStep = null
        roundLedgerFrom = 0
        owner.startWork()
        goals.run(scope, goal, plan(id, goal, effect.text, resume = null), owner)
    }

    // Design 7.6: a held goal's next round, with the options of the input that resumed it (a yes on the card keeps the
    // latest); a steering message that resumes it reaches the run through the inbox, after the round's request was built
    // from the records.
    private fun resumeGoal(effect: ConversationEffect.ResumeGoal) {
        val id = state.id ?: return
        val held = state.goal ?: return
        val goal = running ?: RunningGoal(held.record, effect.command, emptyMap(), emptyList(), listener(id, held.record)).also { running = it }
        goal.command = effect.command
        effect.input?.let { goal.options = it.options }
        val plan = plan(id, goal, records[goal.record - 1].request, effect.cause)
        effect.steer?.let { steer(goal, it.text, it.channel) }
        roundStep = null
        roundLedgerFrom = records[goal.record - 1].ledger.size
        owner.startWork()
        goals.run(scope, goal, plan, owner)
    }

    // Design 7.3: the message waits for the goal's next turn; the record holds it, and its verbs join the allowance (D16).
    private fun steer(goal: RunningGoal, text: String, channel: InputChannel) {
        goal.steer(text, channel)
        edit(goal.record) { record ->
            val steered = RecordBuilder.line(record, LineKind.OWNER_STEERED, RecordText.of(text), channel)
            RecordBuilder.allowed(steered, TaskAllowance.from(text, goal.knownNames).categories)
        }
    }

    private fun endHeld(reason: EndReason) {
        val held = state.goal ?: return
        val record = records.getOrNull(held.record - 1) ?: return
        ended(goals.endHeld(held.command, record, running?.inbox?.drain().orEmpty(), reason))
    }

    private fun ended(end: RoundEnd) {
        remember(CommandOutcome(end.command, end.report, end.answer))
        post(ConversationEvent.GoalEnded(end.command, end.report, end.answer, end.reply))
    }

    private fun askContinue() {
        continueJob?.cancel()
        continueJob = scope.launch {
            val answer = try {
                owner.question(ConversationTexts.CONTINUE_GOAL, QuestionKind.CONTINUE_GOAL)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Asking to continue the goal", e)
                ChannelAnswer.Silence
            }
            post(ConversationEvent.ContinueAnswered(answer))
        }
    }

    private fun runLocal(effect: ConversationEffect.RunLocal) {
        val id = state.id
        val record = effect.record
        val asked: OwnerPort = if (id != null && record != null) {
            newRecord(id, record, effect.input.channel, effect.request.words.orEmpty())
            RecordedOwner(owner) { entry -> changeAndWait(id, record) { RecordBuilder.traced(it, entry, state.replyChannel) } }
        } else {
            owner
        }
        owner.startWork()
        localJob = scope.launch {
            val outcome = try {
                local.run(effect.request, asked, owner)
            } catch (e: CancellationException) {
                post(ConversationEvent.LocalEnded(effect.command, LocalOutcome.Quiet))
                throw e
            } catch (e: Exception) {
                report("A local request", e)
                LocalOutcome.Reply(ConversationTexts.COULD_NOT)
            }
            remember(CommandOutcome(effect.command, TaskReport(EndReason.FINISHED), outcome.sentence))
            post(ConversationEvent.LocalEnded(effect.command, outcome))
        }
    }

    // D23: the store closes the open conversation into the list and opens the chosen one, in one transaction.
    private suspend fun switchTo(id: ConversationId, before: ConversationState) {
        val closing = before.id?.takeIf { it == recordsOf }?.let { snapshot(before, open = false) }
        val opened = try {
            store.reopen(id, closing, clock.wallMillis(), state.safetyTestMode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("Continuing a conversation", e)
            post(ConversationEvent.Switched(null, ConversationTexts.COULD_NOT))
            return
        }
        if (opened != null) load(opened)
        val sentence = opened?.let { ConversationTexts.backIn(ConversationTexts.day(it.openedAtWall, clock.zone())) } ?: ConversationTexts.GONE
        post(ConversationEvent.Switched(opened, sentence))
    }

    private fun startTimer(effect: ConversationEffect.StartTimer) {
        timers.remove(effect.timer)?.cancel()
        timers[effect.timer] = scope.launch {
            delay(effect.millis)
            post(ConversationEvent.TimerEnded(effect.timer, effect.number))
        }
    }

    // A conversation that closes is stored closed: as it was before this step, or, when a restore closed it at once, as the
    // store held it.
    private suspend fun close(effect: ConversationEffect.Close, before: ConversationState) {
        if (effect.keep && recordsOf == effect.id) {
            val closed = if (before.id == effect.id) snapshot(before, open = false) else store.get(effect.id)?.copy(open = false, goal = null, steering = emptyList())
            closed?.let { store.save(it) }
        }
        if (recordsOf == effect.id) {
            records.clear()
            recordsOf = null
        }
        running = null
        dirty = false
        timers.remove(Timer.QUIET)?.cancel()
    }

    private suspend fun change(message: Message.Change) {
        val index = message.record - 1
        if (message.conversation != recordsOf || index !in records.indices) return
        records[index] = message.change(records[index])
        dirty = true
        save()
        publish()
    }

    private suspend fun changeAndWait(conversation: ConversationId, record: Int, change: (CommandRecord) -> CommandRecord) {
        val done = CompletableDeferred<Unit>()
        inbox.send(Message.Change(conversation, record, change, done))
        done.await()
    }

    // A new record for [request]; the earlier records but the newest are condensed once (design 8.3).
    private fun newRecord(id: ConversationId, number: Int, channel: InputChannel, request: String) {
        if (recordsOf != id) {
            records.clear()
            recordsOf = id
        }
        val earlier = RecordCondenser.condense(records.toList())
        records.clear()
        records.addAll(earlier)
        records += RecordBuilder.started(number, clock.wallMillis(), channel, RecordText.of(request))
        dirty = true
    }

    private fun edit(record: Int, change: (CommandRecord) -> CommandRecord) {
        val index = record - 1
        if (recordsOf != state.id || index !in records.indices) return
        records[index] = change(records[index])
        dirty = true
    }

    private fun plan(id: ConversationId, goal: RunningGoal, text: String, resume: ResumeCause?): RoundPlan = RoundPlan(
        record = records[goal.record - 1],
        earlier = records.take(goal.record - 1),
        text = text,
        resume = resume,
        testMode = state.testMode,
        continuedAtWall = state.continuedAtWall,
        limits = state.settings.roundLimits,
        options = goal.options,
        self = RecordRef(id, goal.record),
        zone = clock.zone(),
        waiting = goal.inbox.pending().size,
    )

    private fun load(snapshot: ConversationSnapshot) {
        records.clear()
        records.addAll(snapshot.records)
        recordsOf = snapshot.id
        running = snapshot.goal?.let { goal ->
            RunningGoal(goal.record, 0, emptyMap(), snapshot.steering.map { Steer(it.text, it.channel.toSteer()) }, listener(snapshot.id, goal.record))
        }
    }

    private fun listener(conversation: ConversationId, record: Int): GoalListener = object : GoalListener {
        override fun stepStarted(step: Int) {
            inbox.trySend(Message.Step(conversation, record, step))
        }

        // Data that the run traces before its round's first model step is the replayed routine's (plan 7A).
        override suspend fun traced(entry: TraceEntry) = changeAndWait(conversation, record) {
            RecordBuilder.traced(it, entry, state.replyChannel, routine = roundStep == null, stepsBefore = state.goal?.stepsBefore ?: 0)
        }

        override suspend fun checklist(checklist: Checklist) = changeAndWait(conversation, record) { RecordBuilder.checklist(it, checklist) }

        override fun allowed(categories: Set<Category>) {
            inbox.trySend(Message.Change(conversation, record, { RecordBuilder.allowed(it, categories) }, null))
        }

        override fun steeringChanged() {
            inbox.trySend(Message.Save)
        }

        override fun touched() = this@ConversationEngine.touched()

        // The round's report holds every ledger line of the round, also those that its traces missed (plan 7A); the record
        // takes them before the machine sees the end.
        override fun ended(end: RoundEnd) {
            inbox.trySend(
                Message.Change(conversation, record, { current ->
                    if (running?.record == record) RecordBuilder.roundEnded(current, roundLedgerFrom, end.report.ledger) else current
                }, null),
            )
            this@ConversationEngine.ended(end)
        }
    }

    private suspend fun save() {
        if (!dirty) return
        dirty = false
        val current = state
        val id = current.id ?: return
        if (recordsOf != id) return
        store.save(snapshot(current, open = true))
    }

    // A closed conversation keeps no goal and no steering: they belong to the open one only. An open one's goal keeps the
    // steps that its rounds have taken so far, the running round's too, so that a goal that a restart cut counts on.
    private fun snapshot(state: ConversationState, open: Boolean): ConversationSnapshot = ConversationSnapshot(
        id = state.id!!,
        openedAtWall = state.openedAtWall,
        lastWall = clock.wallMillis(),
        open = open,
        testMode = state.testMode,
        records = records.toList(),
        continuedAtWall = state.continuedAtWall,
        goal = state.goal.takeIf { open }?.let { it.copy(stepsBefore = steps(it)) },
        steering = if (!open) emptyList() else running?.inbox?.pending()?.map { PendingSteer(RecordText.of(it.text), it.channel.toInput()) }.orEmpty(),
        quietSinceWall = (state.quiet as? QuietClock.Running)?.sinceWall,
        replyChannel = state.replyChannel,
    )

    private suspend fun show(output: ChannelOutput) {
        try {
            owner.show(output)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("Showing Kestrel's words", e)
        }
        if (output is ChannelOutput.Report) post(ConversationEvent.ReplyShown(output.command))
    }

    private fun remember(outcome: CommandOutcome) = synchronized(outcomes) {
        outcomes[outcome.command] = outcome
        while (outcomes.size > MAX_OUTCOMES) outcomes.remove(outcomes.keys.first())
    }

    private fun publish() {
        val current = state
        val own = current.id != null && recordsOf == current.id
        val goalRecord = current.goal?.let { records.getOrNull(it.record - 1) }?.takeIf { own }
        published.value = ConversationView(
            id = current.id,
            command = current.command,
            goal = current.goal?.phase,
            step = current.goal?.takeIf { own && it.phase in WORKING && roundStep != null }?.let(::steps),
            asking = current.question?.kind,
            question = current.question?.number,
            replyChannel = current.replyChannel,
            checklist = goalRecord?.checklist?.let(ChecklistSummary::of),
            records = if (own) records.toList() else emptyList(),
            continuedAtWall = current.continuedAtWall,
            testMode = current.testMode,
            quiet = current.quiet,
            keptForOwner = current.heldReport != null || current.heldWords != null,
        )
    }

    // The goal's steps: those of its ended rounds, and the running round's newest step while a round runs (a paused round
    // runs on after the pause; a held goal's round has ended).
    private fun steps(goal: GoalState): Int = goal.stepsBefore + (roundStep?.takeIf { goal.hold?.resume == null } ?: 0)

    override fun toString(): String = "ConversationEngine(${published.value})"

    companion object {
        /** How many commands' outcomes the hooks can read (design 14.4). */
        const val MAX_OUTCOMES = 16

        // The phases in which a goal works and the bar shows its step.
        private val WORKING = setOf(GoalPhase.ACTING, GoalPhase.ASKING, GoalPhase.HANDED_OVER)
    }
}
