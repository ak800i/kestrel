package dev.kestrel.conversation.engine

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.ResumeCause
import dev.kestrel.agent.goal.TraceEntry
import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.channel.ChannelOutput
import dev.kestrel.conversation.channel.ConversationOwner
import dev.kestrel.conversation.channel.OwnerChannel
import dev.kestrel.conversation.channel.OwnerReply
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.context.RecordBuilder
import dev.kestrel.conversation.context.RecordCondenser
import dev.kestrel.conversation.context.RecordText
import dev.kestrel.conversation.context.SteeringMessage
import dev.kestrel.conversation.goal.GoalListener
import dev.kestrel.conversation.goal.GoalRunner
import dev.kestrel.conversation.goal.RoundPlan
import dev.kestrel.conversation.goal.RunningGoal
import dev.kestrel.conversation.local.LocalOutcome
import dev.kestrel.conversation.local.LocalRequests
import dev.kestrel.conversation.local.RecordedOwner
import dev.kestrel.conversation.machine.ConversationEffect
import dev.kestrel.conversation.machine.ConversationEvent
import dev.kestrel.conversation.machine.ConversationMachine
import dev.kestrel.conversation.machine.ConversationState
import dev.kestrel.conversation.machine.MachineStep
import dev.kestrel.conversation.machine.OwnerAct
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
import dev.kestrel.conversation.model.PhoneSignal
import dev.kestrel.conversation.model.ProcessEnd
import dev.kestrel.conversation.model.RoundEnd
import dev.kestrel.conversation.model.RecordRef
import dev.kestrel.conversation.store.ConversationStore
import dev.kestrel.conversation.store.PastUnavailableException
import dev.kestrel.safety.Category
import dev.kestrel.safety.TaskAllowance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The conversation as it runs (design 5.3 and 6 of sub-project 7). Its loop, one coroutine of [scope], applies [machine] to
 * events one at a time and performs each step's effects in order; anything that takes time (a goal's round, a local
 * request, "Continue the goal?", a channel's output, a write to the store) runs in a coroutine of its own and comes back
 * as an event or a message, so that no input waits for the store. The loop keeps the open conversation's records and gives
 * the writer its snapshot at every change; the writer stores the snapshots, the closes and the switches in order, and a
 * round's trace waits until the record that holds it is saved, so that the record survives the end of Kestrel's process
 * (design 6.7), and a round's search, the search of a past conversation and "delete history" wait until the writer has
 * stored every write queued before them. The machine's timers are deadlines on the clock that counts the phone's sleep
 * ([ConversationClock.sinceBootMillis]): before it handles any message, an input and a signal too, the loop ends every
 * timer whose deadline has passed, and a coroutine's `delay`, which stops while the phone sleeps, only wakes it. Work that
 * the machine waits for gets its end on every path, also when its effect failed. Outputs are shown in order, and the end
 * of a final reply's `show` is the machine's `ReplyShown`. [view] is what the voice rules, the screen, the goal service and
 * the hooks read. [restore] must run once at process start; inputs wait for it. [report] gets a failure by its class.
 * Every act of the owner's that lets a goal's round act comes with its number among his inputs, an input with its own and a
 * tap on a card with the one that `:app` took as the tap reached Kestrel (plan 7F), so that the screen's turn can tell
 * whether it came after an instant command started (design 6.5); the engine numbers nothing itself.
 * [onStep] sees every step, for the hooks' tags (plan 7D): every effect that starts work under a new command number
 * (`StartGoal`, `ResumeGoal`, `RunLocal`, `SwitchTo`, and `EndLocal` for "new conversation", which has no work) carries
 * the owner's input that asked for it.
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
    private val timerMillis: (Timer, Long) -> Long = { _, millis -> millis },
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

        /**
         * The running round of record [record] of [conversation] waits behind the owner's instant command, or no longer
         * ([waits]); it only changes the view (plan 7E).
         */
        class BehindCommand(val conversation: ConversationId, val record: Int, val waits: Boolean) : Message

        /** The wake-up of [timer]'s deadline of number [number] came. */
        class Wake(val timer: Timer, val number: Int) : Message

        /** The writer could not save the open conversation: the next step saves it again. */
        data object SaveFailed : Message

        /**
         * The switch's transaction ended: [snapshot] is the open conversation now, or it was gone (null), or the store
         * [failed]. [chosen] is the conversation that it opened as it was before, for a "stop" that came meanwhile.
         */
        class Reopened(val snapshot: ConversationSnapshot?, val chosen: ConversationSnapshot?, val closing: ConversationSnapshot?, val failed: Boolean) : Message

        /** The deletion of the past conversation [id] (plan 7F); [done] gets how it ended. */
        class DeletePast(val id: ConversationId, val done: CompletableDeferred<PastDeletion>) : Message

        /** The settings page's "Delete history" after its own question; [done] gets how it ended. */
        class DeleteHistory(val done: CompletableDeferred<HistoryDeletion>) : Message

        /**
         * The page's deletion ended, [deleted] or not; [closing] is the conversation that the request closed. [settled] once
         * the loop counted the end; only the loop reads and sets it.
         */
        class HistoryDeletionEnded(val deleted: Boolean, val closing: ConversationSnapshot?, val done: CompletableDeferred<HistoryDeletion>) : Message {
            var settled = false
        }
    }

    // What the writer does with the store, in the loop's order, beside the loop.
    private sealed interface Write {
        /** Stores [snapshot]; the traces in [saved] wait until it is written. */
        class Save(val snapshot: ConversationSnapshot, val saved: List<CompletableDeferred<Unit>>) : Write

        /** Stores conversation [id], which a restore closed at once, closed as the store holds it. */
        class CloseStored(val id: ConversationId) : Write

        /** Stores [closing] closed and opens [id] in one transaction (D23). */
        class Reopen(val id: ConversationId, val closing: ConversationSnapshot?, val nowWall: Long, val testMode: Boolean) : Write

        /** Deletes the past conversation [id] (plan 7F); [done] gets how it ended. */
        class DeletePast(val id: ConversationId, val done: CompletableDeferred<PastDeletion>) : Write

        /** Completes [done] once every write before it is stored. */
        class Barrier(val done: CompletableDeferred<Unit>) : Write
    }

    /** Timer [number]'s deadline on the clock that counts sleep, and the coroutine that wakes the loop for it. */
    private class Deadline(val number: Int, val atMillis: Long, val wake: Job)

    private val inbox = Channel<Message>(Channel.UNLIMITED)
    private val outputs = Channel<ChannelOutput>(Channel.UNLIMITED)
    private val writes = Channel<Write>(Channel.UNLIMITED)
    private val started = CompletableDeferred<Unit>()
    private val outcomes = LinkedHashMap<Int, CommandOutcome>()

    @Volatile
    private var state = ConversationState()

    // The open conversation's records and the conversation that they belong to, the running goal, the jobs of a local
    // request and of "Continue the goal?", the timers' deadlines, whether the snapshot needs saving and the traces that
    // wait for that save: only the loop changes them.
    private val records = mutableListOf<CommandRecord>()
    private var recordsOf: ConversationId? = null

    // The owner's tap on a hand-over's card also reaches it, from the round's own coroutine, while the round runs (plan 7F).
    @Volatile
    private var running: RunningGoal? = null
    private var localJob: Job? = null
    private var continueJob: Job? = null
    private val deadlines = LinkedHashMap<Timer, Deadline>()
    private var dirty = false
    private val unsaved = mutableListOf<CompletableDeferred<Unit>>()

    // While the writer switches to a past conversation, the conversation that the switch closes is not saved again: a later
    // save of it would open it again. [switchEnd] is the switch's end only while the machine takes it, so that a "stop"
    // that came meanwhile can undo it; no copy of a conversation stays once the switch has ended.
    private var switching = false

    // The past conversation that the running switch opens, which no deletion may take (plan 7F).
    private var switchingTo: ConversationId? = null

    // The keep-sets of the pending "Delete all" requests (plan 7F, review M1): the loop adds each step's open conversation to
    // every one of them, and the request's coroutine and the store read them, under this list's lock.
    private val keeping = mutableListOf<MutableSet<ConversationId>>()

    // "Delete all" as it stands, and the one that runs, under [allPast]'s lock (review B-M4 of plan 7F).
    private val allPast = MutableStateFlow<AllPastDeletion>(AllPastDeletion.Idle)
    private var allPastRun: Deferred<PastDeletion>? = null
    private var switchEnd: Message.Reopened? = null

    // While the settings page's "Delete history" runs, no write reaches the writer, so that none comes between the writer's
    // barrier that the deletion waits for and the deletion: the open conversation's changes wait as during a switch
    // ([persist]), and the closes, the switches and their undoing wait in [heldWrites], in their order, until the deletion ended.
    private var deletions = 0
    private val heldWrites = mutableListOf<Write>()

    // The running round's newest step, which the view and the snapshot add to the goal's earlier steps (design 10.5).
    private var roundStep: Int? = null

    // Whether the running round waits behind the owner's instant command, for the view (plan 7E).
    private var roundBehindCommand = false

    // How many ledger lines the running goal's record held when its round started; the round's report replaces the rest.
    private var roundLedgerFrom = 0

    // Whether Kestrel is on, for the owner, which adds the cards from the moment it turns off; [restore] reads it first.
    private val voiceNow = MutableStateFlow(false)

    /** The owner as goals and the conversation talk to him; the hooks read its open question, confirmation and hand-over. */
    val owner = ConversationOwner(
        channels,
        permissions,
        voiceNow.asStateFlow(),
        { ConversationOwner.Routing(state.replyChannel, state.settings.speakTypedReplies) },
        // Design 6.5: a tap on a hand-over's card lets the running round act now, as "Kestrel, continue" does (plan 7F).
        acted = { act -> running?.actedFor(act) },
    ) {
        post(ConversationEvent.Dialogue(it))
    }

    private val published = MutableStateFlow(ConversationView())

    val view: StateFlow<ConversationView> = published.asStateFlow()

    /** What [restore] found, for the hook `conversation-control do=restore-check`; null before it ran. */
    @Volatile
    var restored: RestoreResult? = null
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
            started.await()
            for (write in writes) write(write)
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
    suspend fun restore(previous: ProcessEnd = ProcessEnd.Other): RestoreResult {
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
                snapshot == null -> RestoreResult.NOTHING
                state.id == null -> RestoreResult.CLOSED
                state.goal == null -> RestoreResult.OPEN
                state.goal?.ending != null -> RestoreResult.GOAL_ENDED
                else -> RestoreResult.GOAL_HELD
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

    /**
     * Android refused to start the goal service for the goal of command [command], the view's `command` when the start was
     * asked (plan 7E; design 10.6 of sub-project 7). Any thread; never blocks.
     */
    fun backgroundRefused(command: Int) = post(ConversationEvent.BackgroundRefused(command))

    /**
     * Returns once [restore] has run, with the restored conversation in [view] (plan 7E): a tap on the goal notification
     * that started Kestrel's process is judged against the restored goal, not against the empty view before it.
     */
    suspend fun awaitRestored() = started.await()

    /** The conversation screen's field holds text while the screen is in front (design 6.2). */
    fun composing(active: Boolean) = post(ConversationEvent.Composing(active))

    /** Kestrel waits for a command after "Kestrel" alone, the side key or the microphone button (design 6.2). */
    fun awaitingCommand(active: Boolean) = post(ConversationEvent.Awaiting(active))

    /** The owner tapped "Reply…" on question [question]'s card (design 10.3). */
    fun replyOpened(question: Int) = post(ConversationEvent.ReplyOpened(question))

    /** The hook `conversation-control do=expire` (design 14.4). */
    fun endQuietTime() = post(ConversationEvent.EndQuietTime)

    /**
     * The settings page's "Delete history" after the page's own question (design 9.7 of sub-project 7; plan 7D): deletes
     * what a yes to "delete history" deletes and closes the open conversation, with no reply on a channel. While a goal is
     * open or one of Kestrel's own requests runs, it leaves everything alone, as "delete history" by voice does. Waits for
     * [restore].
     */
    suspend fun deleteHistory(): HistoryDeletion {
        val done = CompletableDeferred<HistoryDeletion>()
        inbox.send(Message.DeleteHistory(done))
        return done.await()
    }

    /**
     * Deletes the past conversation [id] (design 9.5 of sub-project 7; plan 7F): the loop checks it between the machine's
     * steps, and the writer deletes it after the writes that the loop gave it before, so that no save or switch of the
     * loop's comes between: [PastDeletion.OPEN] when [id] is the open conversation now, or the one that a running switch
     * opens, which stays. Waits for [restore].
     */
    suspend fun deletePast(id: ConversationId): PastDeletion {
        val done = CompletableDeferred<PastDeletion>()
        inbox.send(Message.DeletePast(id, done))
        return done.await()
    }

    /**
     * Deletes every past conversation (design 9.5; plan 7F), but none that is open at some moment between this call and
     * the deletion's transaction: the one that is open now, and each that the owner starts or continues meanwhile, also
     * when it closes before the deletion runs (review M1). The loop adds each to this call's keep-set ([keeping]) before it
     * queues any write of it, and the store reads the set under its lock. The deletion waits at the writer's barrier until
     * every write that the loop queued before it is stored, so that a conversation that closed before the owner asked is
     * deleted too, and then runs beside the inbox and the writer, never in them: the store may wait minutes for its index
     * to be read or rebuilt, and the conversation goes on meanwhile (review N2). It runs in the engine's scope, so that a
     * caller that goes away stops nothing, and the outcome comes back to the caller once it is done. While one runs, a
     * second call deletes nothing again: it waits for the running one and gets its outcome; [allPastDeletion] says which
     * stands (review B-M4).
     */
    suspend fun deleteAllPast(): PastDeletion = synchronized(allPast) {
        allPastRun?.takeIf { it.isActive } ?: run {
            allPast.value = AllPastDeletion.Running
            // The keep-set holds the conversation that is open now before this call returns: the coroutine may start a
            // little later, on another thread, after a step of the loop has closed it (Task 3's review M1).
            val keep = HashSet<ConversationId>()
            synchronized(keeping) {
                state.id?.let(keep::add)
                keeping.add(keep)
            }
            scope.async {
                stored()
                val outcome = deleteAllPastNow { synchronized(keeping) { keep.toSet() } }
                allPast.value = AllPastDeletion.Ended(outcome)
                outcome
            }.also { deletion ->
                // Also when the coroutine never starts, because the engine's scope ended first.
                deletion.invokeOnCompletion { synchronized(keeping) { keeping.removeAll { it === keep } } }
                allPastRun = deletion
            }
        }
    }.await()

    /** "Delete all past conversations" as it stands in this process: none yet, running, or how the last one ended. */
    val allPastDeletion: StateFlow<AllPastDeletion> = allPast.asStateFlow()

    /** How command [command] ended, among the newest [MAX_OUTCOMES]. */
    fun outcome(command: Int): CommandOutcome? = synchronized(outcomes) { outcomes[command] }

    private fun post(event: ConversationEvent) {
        inbox.trySend(Message.Event(event))
    }

    // Every deadline that has passed ends its timer before the message, so that an input after the phone slept finds the
    // quiet time or a hold over.
    private fun handle(message: Message) {
        try {
            expire()
            when (message) {
                is Message.Event -> process(message.event)
                is Message.Change -> change(message)
                Message.Save -> {
                    dirty = true
                    persist()
                }
                is Message.Step -> if (message.conversation == recordsOf && running?.record == message.record) {
                    roundStep = message.step
                    publish()
                }
                is Message.BehindCommand -> if (message.conversation == recordsOf && running?.record == message.record) {
                    roundBehindCommand = message.waits
                    publish()
                }
                is Message.Wake -> woken(message)
                Message.SaveFailed -> dirty = true
                is Message.Reopened -> reopened(message)
                is Message.DeletePast -> deletion(message)
                is Message.DeleteHistory -> deleteHistory(message.done)
                is Message.HistoryDeletionEnded -> deletionEnded(message)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("The conversation", e)
            settle(message)
        } finally {
            // A trace never waits for a save that a failure skipped.
            unsaved.forEach { it.complete(Unit) }
            unsaved.clear()
        }
    }

    // The step's effects run against the state before it ([before]) where they need the conversation that it leaves; the
    // view shows the step also when an effect failed.
    private fun process(event: ConversationEvent) {
        val before = state
        val step = machine.next(before, event, clock.wallMillis())
        onStep(event, before, step)
        state = step.state
        // Before any write of this step is queued: a pending "Delete all" keeps every conversation that is open meanwhile.
        step.state.id?.let { id -> synchronized(keeping) { keeping.forEach { it += id } } }
        try {
            for (effect in step.effects) {
                try {
                    perform(effect, before)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed(effect, e)
                }
            }
            if (state.goal == null) running = null
            persist()
        } finally {
            publish()
        }
    }

    private fun perform(effect: ConversationEffect, before: ConversationState) {
        when (effect) {
            is ConversationEffect.StartGoal -> startGoal(effect)
            is ConversationEffect.ResumeGoal -> resumeGoal(effect)
            is ConversationEffect.SteerGoal -> steering(effect)
            // The pause is a wait for the owner, which starts now; the round's turn goes once its step can act no more
            // (design 6.5 of sub-project 7; plan 7E).
            ConversationEffect.PauseGoal -> {
                owner.pause()
                goals.paused()
            }
            is ConversationEffect.ResumePausedGoal -> {
                effect.act?.let(::actedFor)
                goals.resumed()
                owner.resume()
            }
            is ConversationEffect.InterruptGoal -> running?.interrupt(effect.cause)
            is ConversationEffect.CancelGoal -> running?.cancel(effect.reason, effect.sentence)
            is ConversationEffect.EndHeldGoal -> endHeld(effect.reason, effect.sentence)
            is ConversationEffect.AnswerQuestion -> owner.answer(effect.number, effect.answer, effect.leftScreen)
            is ConversationEffect.EndHandOver -> {
                effect.act?.let(::actedFor)
                owner.endHandOver(effect.number, effect.end)
            }
            ConversationEffect.AskContinueGoal -> askContinue()
            ConversationEffect.CancelContinueQuestion -> continueJob?.cancel()
            is ConversationEffect.Output -> outputs.trySend(effect.output)
            is ConversationEffect.AddLine -> edit(effect.conversation, effect.record) {
                RecordBuilder.line(it, effect.kind, if (effect.kind.owners) RecordText.of(effect.text) else effect.text, effect.channel)
            }
            is ConversationEffect.EndRecord -> edit(effect.conversation, effect.record) { RecordBuilder.ended(it, effect.end, effect.answer) }
            is ConversationEffect.RunLocal -> runLocal(effect)
            ConversationEffect.CancelLocal -> localJob?.cancel()
            is ConversationEffect.EndLocal -> remember(CommandOutcome(effect.command, TaskReport(effect.end), effect.answer))
            is ConversationEffect.SwitchTo -> switchTo(effect.id, before)
            is ConversationEffect.OpenSwitched -> load(effect.snapshot)
            ConversationEffect.UndoSwitch -> undoSwitch()
            is ConversationEffect.StartTimer -> startTimer(effect)
            is ConversationEffect.Close -> close(effect, before)
            ConversationEffect.Persist -> dirty = true
        }
    }

    // Work that the machine waits for gets its end also when its effect failed, so that the goal or the request ends with
    // Kestrel's words for a failure, and "stop" and the next command still work.
    private fun failed(effect: ConversationEffect, error: Exception) {
        report("A conversation's effect", error)
        when (effect) {
            is ConversationEffect.StartGoal -> couldNot(effect.command, EndReason.KESTREL_ERROR)
            is ConversationEffect.ResumeGoal -> couldNot(effect.command, EndReason.KESTREL_ERROR)
            is ConversationEffect.EndHeldGoal -> state.goal?.let { couldNot(it.command, effect.reason) }
            is ConversationEffect.RunLocal -> post(ConversationEvent.LocalEnded(effect.command, LocalOutcome.Reply(ConversationTexts.COULD_NOT)))
            is ConversationEffect.SwitchTo -> post(ConversationEvent.Switched(null, ConversationTexts.COULD_NOT, failed = true))
            else -> Unit
        }
    }

    // Design 6.5: the owner's act lets the running round act now, so its next screen turn may have an instant command that
    // he started before it cancelled. The act's number is its input's, or his tap's (plan 7F).
    private fun actedFor(act: OwnerAct) {
        act.number?.let { running?.actedFor(it) }
    }

    private fun couldNot(command: Int, end: EndReason) = ended(RoundEnd(command, TaskReport(end), ConversationTexts.COULD_NOT, ConversationTexts.COULD_NOT))

    private fun startGoal(effect: ConversationEffect.StartGoal) {
        val id = checkNotNull(state.id) { "A goal starts in an open conversation" }
        newRecord(id, effect.record, effect.input.channel, effect.text)
        val goal = RunningGoal(effect.record, effect.command, effect.input.options, emptyList(), listener(id, effect.record))
        goal.actedFor(effect.input.id)
        running = goal
        roundStep = null
        roundBehindCommand = false
        roundLedgerFrom = 0
        owner.startWork()
        goals.run(scope, goal, plan(id, goal, effect.text, resume = null), owner)
    }

    // Design 7.6: a held goal's next round, with the options of the input that resumed it (a yes on the card keeps the
    // latest); a steering message that resumes it reaches the run through the inbox, after the round's request was built
    // from the records.
    private fun resumeGoal(effect: ConversationEffect.ResumeGoal) {
        val id = checkNotNull(state.id) { "A goal resumes in an open conversation" }
        val held = checkNotNull(state.goal) { "Only an open goal resumes" }
        val record = goalRecord(id, held)
        val goal = running ?: RunningGoal(held.record, effect.command, emptyMap(), emptyList(), listener(id, held.record)).also { running = it }
        goal.command = effect.command
        effect.input?.let { goal.options = it.options }
        (effect.input?.id ?: effect.tap)?.let(goal::actedFor)
        val plan = plan(id, goal, record.request, effect.cause)
        effect.steer?.let { steer(id, goal, SteeringMessage.of(it.text, it.channel)) }
        roundStep = null
        roundBehindCommand = false
        roundLedgerFrom = record.ledger.size
        owner.startWork()
        goals.run(scope, goal, plan, owner)
    }

    // Design 7.3: the message waits for the goal's next turn, and "Got it." says so. A goal that has given its final words
    // takes no more: Kestrel says at once that the message came too late, and the record keeps it as such. The message's
    // record form is made once, here, where it arrives (review of plan 7B: C1).
    private fun steering(effect: ConversationEffect.SteerGoal) {
        val id = checkNotNull(state.id) { "Steering reaches a goal of the open conversation" }
        val message = SteeringMessage.of(effect.text, effect.channel)
        val goal = running
        if (goal != null && steer(id, goal, message)) {
            outputs.trySend(ChannelOutput.Shown(ConversationTexts.GOT_IT))
            return
        }
        state.goal?.let { open -> edit(id, open.record) { RecordBuilder.line(it, LineKind.OWNER_TOO_LATE, message.recorded, message.channel) } }
        outputs.trySend(ChannelOutput.Said(ConversationTexts.cameTooLate(message)))
    }

    // The record holds a message that the goal's inbox took, in its record's form, and its verbs join the allowance (D16);
    // false when the inbox took none, because the goal has given its final words.
    private fun steer(id: ConversationId, goal: RunningGoal, message: SteeringMessage): Boolean {
        if (!goal.steer(message)) return false
        edit(id, goal.record) { record ->
            val steered = RecordBuilder.line(record, LineKind.OWNER_STEERED, message.recorded, message.channel)
            RecordBuilder.allowed(steered, TaskAllowance.from(message.steer.text, goal.knownNames).categories)
        }
        return true
    }

    // The inbox closes with the goal's final words; what waited in it is named in them as too late.
    private fun endHeld(reason: EndReason, sentence: String?) {
        val held = checkNotNull(state.goal) { "Only an open goal ends" }
        val record = goalRecord(checkNotNull(state.id) { "A goal belongs to an open conversation" }, held)
        ended(goals.endHeld(held.command, record, running?.inbox?.close().orEmpty(), reason, sentence))
    }

    private fun goalRecord(id: ConversationId, goal: GoalState): CommandRecord =
        checkNotNull(records.getOrNull(goal.record - 1)?.takeIf { recordsOf == id }) { "The goal's record is missing" }

    private fun ended(end: RoundEnd) {
        remember(CommandOutcome(end.command, end.report, end.answer))
        post(ConversationEvent.GoalEnded(end))
    }

    private fun askContinue() {
        continueJob?.cancel()
        continueJob = scope.launch {
            val reply = try {
                owner.reply(ConversationTexts.CONTINUE_GOAL, QuestionKind.CONTINUE_GOAL)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Asking to continue the goal", e)
                OwnerReply(ChannelAnswer.Silence, tap = null)
            }
            post(ConversationEvent.ContinueAnswered(reply.answer, reply.tap))
        }
    }

    // The request's job starts atomically, as a goal's round does (`GoalRunner.run`): a "stop" that the loop takes before
    // the job first runs still reaches its body, whose first act ends it, so that `LocalEnded` comes on every path.
    @OptIn(DelicateCoroutinesApi::class)
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
        localJob = scope.launch(start = CoroutineStart.ATOMIC) {
            val outcome = try {
                currentCoroutineContext().ensureActive()
                local.run(effect.request, asked, owner, ::stored)
            } catch (e: CancellationException) {
                post(ConversationEvent.LocalEnded(effect.command, LocalOutcome.Quiet))
                throw e
            } catch (e: Exception) {
                report("A local request", e)
                LocalOutcome.Reply(ConversationTexts.COULD_NOT)
            }
            post(ConversationEvent.LocalEnded(effect.command, outcome))
        }
    }

    // Design 9.5 of sub-project 7: the open conversation is never a past one, nor is the one that a running switch opens.
    // The writer deletes the others after the writes that the loop gave it before, so that no save or switch comes after;
    // while the settings page's "Delete history" runs, the deletion waits with the other writes ([send]).
    private fun deletion(message: Message.DeletePast) {
        if (message.id == state.id || message.id == switchingTo) {
            message.done.complete(PastDeletion.OPEN)
        } else {
            send(Write.DeletePast(message.id, message.done))
        }
    }

    // While the index of past conversations cannot be read, the store deletes nothing and says so; it is no failure. A failure
    // of the archive is reported by its class.
    private suspend fun deleteAllPastNow(keep: () -> Set<ConversationId>): PastDeletion = try {
        store.deleteClosed(keep)
        PastDeletion.DELETED
    } catch (e: PastUnavailableException) {
        PastDeletion.UNAVAILABLE
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        report("Deleting past conversations", e)
        PastDeletion.FAILED
    }

    // D23: the writer closes the open conversation into the list and opens the chosen one, in one transaction of the store.
    private fun switchTo(id: ConversationId, before: ConversationState) {
        val closing = before.id?.takeIf { it == recordsOf }?.let { snapshot(before, open = false) }
        switching = true
        switchingTo = id
        send(Write.Reopen(id, closing, clock.wallMillis(), state.safetyTestMode))
    }

    // The machine decides what the switch's end means (a "stop" may have cancelled it); the records of the conversation
    // that it opened become the open ones only by its effect (OpenSwitched).
    private fun reopened(message: Message.Reopened) {
        switching = false
        switchingTo = null
        val opened = message.snapshot
        val sentence = when {
            message.failed -> ConversationTexts.COULD_NOT
            opened == null -> ConversationTexts.GONE
            else -> ConversationTexts.backIn(ConversationTexts.day(opened.openedAtWall, clock.zone()))
        }
        switchEnd = message
        try {
            process(ConversationEvent.Switched(opened, sentence, failed = message.failed))
        } finally {
            switchEnd = null
        }
    }

    // A "stop" came while the store switched: the conversation that the switch opened is stored closed as it was, and the
    // one that was open, whose records the engine kept, open again.
    private fun undoSwitch() {
        val end = checkNotNull(switchEnd) { "Only a switch's end undoes it" }
        end.chosen?.let { send(Write.Save(it, emptyList())) }
        end.closing?.let { send(Write.Save(it.copy(open = true), emptyList())) }
    }

    // Design 9.7: the page's question stands for the conversation's. The open conversation closes unkept at once, and the
    // deletion runs beside the loop, as "delete history" by voice does, after the writer's barrier ([stored]): so a save
    // that still waits is deleted too. No write reaches the writer until it ended ([send]), so a command after the request
    // opens a conversation that stays. A switch is the end of one of Kestrel's own requests, whose run lasts until the
    // switch ended, so the request is refused then too. The job starts atomically, so that its end comes back on every path.
    private fun deleteHistory(done: CompletableDeferred<HistoryDeletion>) {
        if (state.goal != null || state.local != null) {
            done.complete(HistoryDeletion.REFUSED)
            return
        }
        try {
            val closing = state.id?.takeIf { it == recordsOf }?.let { snapshot(state, open = false) }
            process(ConversationEvent.HistoryDeleted)
            deletions++
            scope.launch(start = CoroutineStart.ATOMIC) {
                var deleted = false
                try {
                    deleted = local.deleteAll(::stored)
                } finally {
                    inbox.trySend(Message.HistoryDeletionEnded(deleted, closing, done))
                }
            }
        } catch (e: Throwable) {
            done.complete(HistoryDeletion.FAILED)
            throw e
        }
    }

    // When nothing was deleted, the conversation that the request closed stays, closed, as any close keeps it. Then the
    // writes that waited go to the writer in their order, and the open conversation's changes are written.
    private fun deletionEnded(message: Message.HistoryDeletionEnded) {
        if (message.settled) return
        message.settled = true
        deletions--
        try {
            if (!message.deleted) message.closing?.let { send(Write.Save(it, emptyList())) }
            if (deletions == 0) {
                heldWrites.forEach { writes.trySend(it) }
                heldWrites.clear()
            }
        } finally {
            message.done.complete(if (message.deleted) HistoryDeletion.DELETED else HistoryDeletion.FAILED)
        }
        persist()
    }

    // The page's request and the end of its deletion are settled also when their message failed before or while it was
    // handled, so that the page never waits for ever and no write stays held.
    private fun settle(message: Message) {
        try {
            when (message) {
                is Message.DeleteHistory -> message.done.complete(HistoryDeletion.FAILED)
                is Message.HistoryDeletionEnded -> deletionEnded(message)
                is Message.DeletePast -> message.done.complete(PastDeletion.FAILED)
                else -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("The conversation", e)
        }
    }

    // A close, a switch or its undoing for the writer; while the settings page's "Delete history" runs, it waits in
    // [heldWrites].
    private fun send(write: Write) {
        if (deletions > 0) heldWrites += write else writes.trySend(write)
    }

    private fun startTimer(effect: ConversationEffect.StartTimer) {
        deadlines.remove(effect.timer)?.wake?.cancel()
        // The device tests' seam shortens the deadline itself: a shorter wake-up alone would end nothing sooner.
        val at = clock.sinceBootMillis() + timerMillis(effect.timer, effect.millis)
        deadlines[effect.timer] = Deadline(effect.number, at, wake(effect.timer, effect.number, at))
    }

    // Only a wake-up: a coroutine's delay does not count the time that the phone sleeps, so the loop reads the deadline.
    private fun wake(timer: Timer, number: Int, at: Long): Job = scope.launch {
        delay(at - clock.sinceBootMillis())
        inbox.trySend(Message.Wake(timer, number))
    }

    // Every timer whose deadline has passed ends, the earliest first; the end of an older timer changes nothing.
    private fun expire() {
        while (true) {
            val now = clock.sinceBootMillis()
            val (timer, deadline) = deadlines.entries.map { it.key to it.value }.filter { it.second.atMillis <= now }.minByOrNull { it.second.atMillis } ?: return
            deadlines.remove(timer)
            deadline.wake.cancel()
            process(ConversationEvent.TimerEnded(timer, deadline.number))
        }
    }

    // A wake-up that came before its deadline, by the clock that counts sleep, waits again.
    private fun woken(message: Message.Wake) {
        val deadline = deadlines[message.timer]?.takeIf { it.number == message.number } ?: return
        deadlines[message.timer] = Deadline(deadline.number, deadline.atMillis, wake(message.timer, deadline.number, deadline.atMillis))
    }

    // A conversation that closes is stored closed: as it was before this step, with every record that the step ended, or,
    // when a restore closed it at once, as the store holds it.
    private fun close(effect: ConversationEffect.Close, before: ConversationState) {
        if (effect.keep && recordsOf == effect.id) {
            send(if (before.id == effect.id) Write.Save(snapshot(before, open = false), emptyList()) else Write.CloseStored(effect.id))
        }
        if (recordsOf == effect.id) {
            records.clear()
            recordsOf = null
        }
        running = null
        dirty = false
        deadlines.remove(Timer.QUIET)?.wake?.cancel()
    }

    private fun change(message: Message.Change) {
        try {
            val index = message.record - 1
            if (message.conversation == recordsOf && index in records.indices) {
                records[index] = message.change(records[index])
                dirty = true
            }
        } finally {
            message.done?.let(unsaved::add)
        }
        persist()
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

    // A record is named with its conversation, so that a step that ends a goal and closes its conversation still ends the
    // goal's record before the close stores it.
    private fun edit(conversation: ConversationId, record: Int, change: (CommandRecord) -> CommandRecord) {
        val index = record - 1
        if (recordsOf != conversation || index !in records.indices) return
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
        stored = ::stored,
    )

    private fun load(snapshot: ConversationSnapshot) {
        records.clear()
        records.addAll(snapshot.records)
        recordsOf = snapshot.id
        dirty = false
        running = snapshot.goal?.let { goal ->
            RunningGoal(goal.record, 0, emptyMap(), snapshot.steering.map(SteeringMessage::restored), listener(snapshot.id, goal.record))
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

        override fun waitsBehindCommand(waits: Boolean) {
            inbox.trySend(Message.BehindCommand(conversation, record, waits))
        }

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

    // The open conversation's snapshot goes to the writer when it changed; the traces that wait for it are released once it
    // is written, or at once when nothing is written. While a switch runs, the conversation that it closes is not saved,
    // and while the settings page's "Delete history" runs, nothing is saved until it ended.
    private fun persist() {
        val waiting = unsaved.toList()
        unsaved.clear()
        val current = state
        val id = current.id
        val holding = switching || deletions > 0
        if (!dirty || holding || id == null || recordsOf != id) {
            if (!holding) dirty = false
            waiting.forEach { it.complete(Unit) }
            return
        }
        dirty = false
        writes.trySend(Write.Save(snapshot(current, open = true), waiting))
    }

    // The writer: a save that fails is reported, and the loop saves the open conversation again at its next step.
    private suspend fun write(write: Write) {
        when (write) {
            is Write.Save -> try {
                store.save(write.snapshot)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Saving the conversation", e)
                if (write.snapshot.open) inbox.trySend(Message.SaveFailed)
            } finally {
                write.saved.forEach { it.complete(Unit) }
            }
            is Write.CloseStored -> try {
                store.get(write.id)?.let { store.save(it.copy(open = false, goal = null, steering = emptyList())) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Closing the conversation", e)
            }
            is Write.Barrier -> write.done.complete(Unit)
            // The chosen conversation is read as it was first, so that a "stop" that comes while the store switches can put
            // it back.
            is Write.Reopen -> {
                val (chosen, opened) = try {
                    val chosen = store.get(write.id)
                    chosen to store.reopen(write.id, write.closing, write.nowWall, write.testMode)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    report("Continuing a conversation", e)
                    inbox.trySend(Message.Reopened(null, null, null, failed = true))
                    return
                }
                inbox.trySend(Message.Reopened(opened, chosen, write.closing, failed = false))
            }
            is Write.DeletePast -> write.done.complete(
                try {
                    store.delete(write.id)
                    PastDeletion.DELETED
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    report("Deleting a past conversation", e)
                    PastDeletion.FAILED
                },
            )
        }
    }

    // Returns once the writer has stored every write that the loop queued before this call, so that a search of the store
    // or "delete history" sees each of them; any coroutine may call it.
    private suspend fun stored() {
        val done = CompletableDeferred<Unit>()
        writes.send(Write.Barrier(done))
        done.await()
    }

    // A closed conversation keeps no goal and no steering: they belong to the open one's goal only. An open one's goal keeps
    // the steps that its rounds have taken so far, the running round's too, so that a goal that a restart cut counts on.
    private fun snapshot(state: ConversationState, open: Boolean): ConversationSnapshot = ConversationSnapshot(
        id = state.id!!,
        openedAtWall = state.openedAtWall,
        lastWall = clock.wallMillis(),
        open = open,
        testMode = state.testMode,
        records = records.toList(),
        continuedAtWall = state.continuedAtWall,
        goal = state.goal.takeIf { open }?.let { it.copy(stepsBefore = steps(it)) },
        steering = if (!open || state.goal == null) emptyList() else running?.inbox?.pending()?.map(SteeringMessage::pending).orEmpty(),
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

    // Without an open conversation, the view's test mode is the Safety page's, which the next conversation takes.
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
            testMode = if (current.id != null) current.testMode else current.safetyTestMode,
            quiet = current.quiet,
            keptForOwner = current.heldReports.isNotEmpty() || current.heldWords != null,
            ownRequest = current.local?.cancelled == false,
            hold = current.goal?.hold,
            goalEnding = current.goal?.ending,
            goalRecord = current.goal?.record,
            goalCommand = current.goal?.command,
            waitsBehindCommand = own && roundBehindCommand && current.goal?.phase in WORKING,
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
