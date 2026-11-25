package dev.kestrel.app.agent

import android.util.Log
import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.AnswerKind
import dev.kestrel.agent.port.ConfirmEnd
import dev.kestrel.agent.port.Gate
import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.TaskAnswers
import dev.kestrel.app.orchestrator.TaskAsk
import dev.kestrel.commands.SpokenNumbers
import dev.kestrel.safety.Category
import dev.kestrel.safety.Confirmation
import dev.kestrel.safety.HandOver
import dev.kestrel.safety.HandOverKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The screen as [AppOwnerPort] needs it: the confirmation card, the touch watcher's credits and the feedback bar. */
internal interface OwnerScreen {
    /** Shows the confirmation card with [question] until a button answers it; null when no screen session is connected. */
    suspend fun card(question: String): Boolean?

    /** Clears the touch watcher's credits before a question (design D19, rule 3). */
    fun forgetOwnActions()

    /** Lets the feedback bar hide the text that a question or a hand-over kept on it. */
    suspend fun releaseBar()

    /** Starts the touch watcher again: an instant command during a hand-over or a pause stops it when it ends (D19). */
    suspend fun watch()

    /** Whether Kestrel's own screen is in front, by the agent's own rule, `AgentFreeApps.kestrelInFront` (design 10.2 of sub-project 7). */
    suspend fun kestrelInFront(): Boolean
}

/**
 * Another app's audio of a media or game usage (design D32 item 17): Kestrel's own voice uses the assistant usage, so
 * any such player is another app's.
 */
internal interface OtherAudio {
    fun playing(): Boolean

    /** Pauses it with the media key, as `media_control`'s pause does. */
    suspend fun pause()
}

/** What `:app` does around a hand-over besides the Orchestrator (design D15). */
internal interface HandOverSignals {
    /** The hand-over of [kind] has begun; the notification-access hand-over opens Android's page here. */
    suspend fun opened(kind: HandOverKind)

    /**
     * Returns when the hand-over of [kind] ended by itself: notification access when Kestrel's listener connects and
     * Android's page has left the front, a call when the owner placed it. It never returns for the other kinds.
     */
    suspend fun endedByItself(kind: HandOverKind)
}

/**
 * The owner as one task's agent talks to him (design D2, D14, D15 and D19 of sub-project 5; D32 items 17 to 21): it
 * sends the Orchestrator's events for task number [task], and the effects that the Rules send back reach it through
 * [TaskOwner]'s functions. Questions are asked one at a time, each after the touch watcher's credits are cleared. The
 * one answer wait is the Orchestrator's, which ends with an answer of null for silence; a pause withdraws the open
 * question, whose answer is then [Answer.Interrupted]. A confirmation shows the card while Kestrel speaks the question,
 * and the first answer, from the card's buttons or the voice, decides. While another app's audio plays, a confirmation
 * of a send or a delete, and the screenshot question, take the voice only after Kestrel paused that audio; when it still
 * plays, only the card's button counts, and when Kestrel paused it, it says [AUDIO_PAUSED] first. A hand-over or a
 * permission request that a pause met before it began begins after the resume. [askAndroid] shows Android's dialog for
 * Kestrel's own permissions, and [granted] checks them again when the owner says "Kestrel, continue" before the dialog
 * answered. [report] logs a failure of [close], which never throws, by its classes only.
 */
internal class AppOwnerPort(
    private val task: Int,
    private val send: (Event) -> Unit,
    private val screen: OwnerScreen,
    private val audio: OtherAudio,
    private val signals: HandOverSignals,
    private val askAndroid: suspend (List<String>) -> PermissionEnd,
    private val granted: (List<String>) -> PermissionEnd,
    // What the debug build observes of the task, which hears every say text too (plan 6B-basefix).
    private val witness: TaskWitness = TaskWitness.NONE,
    private val audioSettleMillis: Long = AUDIO_SETTLE_MILLIS,
    private val report: (String, Throwable) -> Unit = { what, error -> logFailure(what, error) },
) : TaskOwner {
    private sealed interface Wake {
        data class Answered(val answer: String?) : Wake

        data object Continued : Wake

        data object Expired : Wake

        data object Paused : Wake
    }

    private val questions = Mutex()
    private val lock = Any()
    private var waiter: CompletableDeferred<Wake>? = null
    private var pause: CompletableDeferred<Unit>? = null

    @Volatile
    override var handOverKind: HandOverKind? = null
        private set

    @Volatile
    override var confirming: Category? = null
        private set

    @Volatile
    override var asking: AnswerKind? = null
        private set

    override suspend fun say(text: String) {
        witness.said(text)
        send(Event.TaskSays(task, text))
    }

    override suspend fun ask(question: String, kind: AnswerKind): Answer = questions.withLock {
        screen.forgetOwnActions()
        asking = kind
        try {
            val playing = kind == AnswerKind.SCREENSHOT && audio.playing()
            if (playing && !voiceAllowed()) {
                return@withLock when (askWithCard(question, TaskAsk.FREE, voice = false)) {
                    CardOrVoice.Yes -> Answer.Yes
                    CardOrVoice.No, CardOrVoice.Silence -> Answer.No
                    CardOrVoice.Paused -> Answer.Interrupted
                }
            }
            val spoken = if (playing) "$AUDIO_PAUSED $question" else question
            when (val wake = wait { send(Event.TaskAsks(task, spoken, asked(kind))) }) {
                Wake.Paused -> Answer.Interrupted
                is Wake.Answered -> answer(kind, wake.answer)
                else -> Answer.Silence
            }
        } finally {
            asking = null
            // A cancelled task's coroutine still lets the bar go before the report (D32 item 1).
            withContext(NonCancellable) { screen.releaseBar() }
        }
    }

    override suspend fun confirm(confirmation: Confirmation): ConfirmEnd = questions.withLock {
        screen.forgetOwnActions()
        confirming = confirmation.category
        try {
            val guarded = confirmation.category == Category.SENDING || confirmation.category == Category.DELETING
            val playing = guarded && audio.playing()
            val voice = !guarded || voiceAllowed()
            val spoken = if (playing && voice) "$AUDIO_PAUSED ${confirmation.spoken}" else confirmation.spoken
            when (askWithCard(spoken, TaskAsk.CONFIRM, voice, confirmation.question)) {
                CardOrVoice.Yes -> ConfirmEnd.YES
                CardOrVoice.No -> ConfirmEnd.NO
                CardOrVoice.Silence -> ConfirmEnd.TIMEOUT
                CardOrVoice.Paused -> ConfirmEnd.INTERRUPTED
            }
        } finally {
            confirming = null
            withContext(NonCancellable) { screen.releaseBar() }
        }
    }

    override suspend fun handOver(handOver: HandOver): HandOverEnd = questions.withLock {
        handOverKind = handOver.kind
        try {
            var end: HandOverEnd? = null
            while (end == null) {
                end = coroutineScope {
                    val begun = CompletableDeferred<Unit>()
                    val waking = async {
                        wait {
                            send(Event.TaskHandsOver(task, handOver.text))
                            begun.complete(Unit)
                        }
                    }
                    // Android's notification-access page opens only once the hand-over has begun, never during a pause.
                    val itself = async {
                        begun.await()
                        signals.opened(handOver.kind)
                        signals.endedByItself(handOver.kind)
                    }
                    select<HandOverEnd?> {
                        waking.onAwait {
                            when (it) {
                                Wake.Expired -> HandOverEnd.EXPIRED
                                // A pause came before the Rules began the hand-over: it begins again after the resume.
                                Wake.Paused -> null
                                else -> HandOverEnd.CONTINUED
                            }
                        }
                        itself.onAwait { HandOverEnd.DONE_BY_ITSELF }
                    }.also { currentCoroutineContext().cancelChildren() }
                }
                if (end == null) gate()
            }
            // "Kestrel, continue" has set the task running again; an end by itself has not.
            if (end == HandOverEnd.DONE_BY_ITSELF) send(Event.TaskBusy(task))
            // An instant command during the hand-over stopped the touch watcher when it ended (design D19).
            screen.watch()
            end
        } finally {
            handOverKind = null
            withContext(NonCancellable) { screen.releaseBar() }
        }
    }

    // Kestrel's own permission request is a hand-over of kind PERMISSION (design D12, D15): touches do not cancel it,
    // and it ends by itself when Android's dialog answers.
    override suspend fun askPermission(permissions: List<String>, why: String): PermissionEnd = questions.withLock {
        handOverKind = HandOverKind.PERMISSION
        try {
            var end: PermissionEnd? = null
            while (end == null) {
                end = coroutineScope {
                    val begun = CompletableDeferred<Unit>()
                    val waking = async {
                        wait {
                            send(Event.TaskHandsOver(task, why))
                            begun.complete(Unit)
                        }
                    }
                    // Android's dialog shows only once the hand-over has begun, never during a pause.
                    val asked = async {
                        begun.await()
                        askAndroid(permissions)
                    }
                    select<PermissionEnd?> {
                        asked.onAwait { end ->
                            send(Event.TaskBusy(task))
                            end
                        }
                        waking.onAwait {
                            when (it) {
                                Wake.Expired -> PermissionEnd.REFUSED
                                // A pause came before the Rules began the hand-over: the owner is asked after the
                                // resume, as for any question (design D2), so it never counts as his refusal.
                                Wake.Paused -> null
                                else -> granted(permissions)
                            }
                        }
                    }.also { currentCoroutineContext().cancelChildren() }
                }
                if (end == null) gate()
            }
            screen.watch()
            end
        } finally {
            handOverKind = null
            withContext(NonCancellable) { screen.releaseBar() }
        }
    }

    // Today nothing moves Kestrel's own screen away, so the task waits for the owner to leave it (plan 7A; design 10.2 of
    // sub-project 7); plan 7F's conversation screen leaves by itself after the owner sends a command.
    override suspend fun leaveKestrel(): Boolean =
        withTimeoutOrNull(OwnerPort.KESTREL_LEAVE_MILLIS) {
            while (screen.kestrelInFront()) delay(KESTREL_POLL_MILLIS)
            true
        } ?: false

    override suspend fun gate(): Gate {
        val paused = synchronized(lock) { pause } ?: return Gate.GO
        paused.await()
        // An instant command during the pause stopped the touch watcher when it ended (design D19).
        screen.watch()
        return Gate.RESUMED
    }

    override fun answered(answer: String?) {
        synchronized(lock) { waiter }?.complete(Wake.Answered(answer))
    }

    override fun continued() {
        synchronized(lock) { waiter }?.complete(Wake.Continued)
    }

    override fun handOverExpired() {
        synchronized(lock) { waiter }?.complete(Wake.Expired)
    }

    override fun paused() {
        val open = synchronized(lock) {
            if (pause == null) pause = CompletableDeferred()
            waiter
        }
        open?.complete(Wake.Paused)
    }

    override fun resumed() {
        synchronized(lock) { pause.also { pause = null } }?.complete(Unit)
    }

    // AgentRunner releases the task after this, and the release stops the touch watcher, so this never throws.
    override fun close() {
        try {
            synchronized(lock) { pause.also { pause = null } }?.complete(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("Closing a task's owner port", e)
        }
    }

    private enum class CardOrVoice { Yes, No, Silence, Paused }

    // The card and the spoken question together; the first answer decides and the other is withdrawn (D32 item 18). A
    // card's answer ends the Orchestrator's wait with TaskBusy. Without a card, only the voice can answer.
    private suspend fun askWithCard(spoken: String, kind: TaskAsk, voice: Boolean, shown: String = spoken): CardOrVoice = coroutineScope {
        val waking = async { wait { send(Event.TaskAsks(task, spoken, kind, voice)) } }
        val card = async { screen.card(shown) ?: awaitCancellation() }
        select<CardOrVoice> {
            card.onAwait { yes ->
                send(Event.TaskBusy(task))
                if (yes) CardOrVoice.Yes else CardOrVoice.No
            }
            waking.onAwait { wake ->
                when (wake) {
                    Wake.Paused -> CardOrVoice.Paused
                    is Wake.Answered -> when (wake.answer) {
                        TaskAnswers.YES -> CardOrVoice.Yes
                        null -> CardOrVoice.Silence
                        else -> CardOrVoice.No
                    }
                    else -> CardOrVoice.Silence
                }
            }
        }.also { currentCoroutineContext().cancelChildren() }
    }

    // Design D32 item 17: the voice counts only when no other app's audio plays, or after Kestrel paused it.
    private suspend fun voiceAllowed(): Boolean {
        if (!audio.playing()) return true
        audio.pause()
        delay(audioSettleMillis)
        return !audio.playing()
    }

    private suspend fun wait(start: () -> Unit): Wake {
        val waking = CompletableDeferred<Wake>()
        synchronized(lock) {
            if (pause != null) return Wake.Paused
            waiter = waking
        }
        try {
            start()
            return waking.await()
        } finally {
            synchronized(lock) { if (waiter === waking) waiter = null }
        }
    }

    override fun toString(): String = "AppOwnerPort(task=$task, handOver=$handOverKind)"

    companion object {
        const val AUDIO_PAUSED = "I paused the other app's audio."

        /** How long Android takes to report a paused player as stopped. */
        const val AUDIO_SETTLE_MILLIS = 500L

        /** How often [leaveKestrel] reads whether Kestrel's own screen is still in front. */
        const val KESTREL_POLL_MILLIS = 200L

        fun asked(kind: AnswerKind): TaskAsk = when (kind) {
            AnswerKind.YES_NO, AnswerKind.LIMIT -> TaskAsk.YES_NO
            AnswerKind.PICK_NUMBER -> TaskAsk.PICK_NUMBER
            AnswerKind.FREE, AnswerKind.SCREENSHOT -> TaskAsk.FREE
        }

        /** The agent's answer for the Rules' [answer] to a question of [kind]; null is silence. */
        fun answer(kind: AnswerKind, answer: String?): Answer = when {
            answer == null -> Answer.Silence
            kind == AnswerKind.YES_NO || kind == AnswerKind.LIMIT -> if (answer == TaskAnswers.YES) Answer.Yes else Answer.No
            kind == AnswerKind.PICK_NUMBER -> SpokenNumbers.answer(answer)?.let { Answer.Number(it) } ?: Answer.Silence
            else -> Answer.Text(answer)
        }

        // Only the classes of the error and its causes, because a message can carry screen text (D25).
        private fun logFailure(what: String, error: Throwable) {
            val classes = generateSequence(error) { it.cause }.take(MAX_CAUSES).joinToString(" <- ") { it.javaClass.name }
            Log.w(TAG, "$what failed: $classes")
        }

        private const val TAG = "Kestrel"
        private const val MAX_CAUSES = 8
    }
}
