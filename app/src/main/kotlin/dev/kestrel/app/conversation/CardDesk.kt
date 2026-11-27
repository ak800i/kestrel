package dev.kestrel.app.conversation

import dev.kestrel.app.phone.ScreenReading
import dev.kestrel.app.phone.ScreenStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * The owner's tap on a card's [choice], with [act], its number among his inputs, which the card desk takes when the tap
 * has come back from the card, before any caller runs (`InputIds`; design 6.5 of sub-project 7, plan 7F): one tap is one
 * act, whichever callers it answers.
 */
internal data class CardTap(val choice: CardChoice, val act: Long)

/**
 * What became of a tap on the card at once (plan 7F): it [ANSWERED] its question; it was [HELD] for the owner's unlock
 * (a Continue on the lock screen, which answers by itself once the phone is unlocked within that lock); it was
 * [DROPPED_BY_SCREEN_OFF], because the screen was off at the tap; it was [REFUSED_UNFOLLOWED], a Continue on the lock
 * screen while the desk cannot follow the screen source, which is never held, so the owner taps again after his unlock;
 * or it was [WITHDRAWN], because the card was no longer the overlay's when it came.
 */
internal enum class TapEnd { ANSWERED, HELD, DROPPED_BY_SCREEN_OFF, REFUSED_UNFOLLOWED, WITHDRAWN }

/** The card of each question, whichever channel asks it (design 10.3 of sub-project 7). */
internal interface OwnerCards {
    /**
     * Shows [card] until the owner taps one of its own choices, and returns that choice with his tap's number. "Reply…" is
     * no answer: it calls [onReply], and the question waits for the owner's typed words. Cancelling the call withdraws this
     * caller's card.
     */
    suspend fun ask(card: OwnerCard, onReply: () -> Unit = {}): CardTap
}

/** The cards as the text channel uses them: each question's card, and the open card, which the screen shows (plan 7F). */
internal interface SharedCards : OwnerCards {
    /** The card that waits for the owner, which the conversation screen shows while it is in front. */
    val open: StateFlow<OwnerCard?>
}

/**
 * The one card of each question (design 10.3 of sub-project 7; plan 7D's interfaces: one card per question number,
 * whichever channel asks it). Every caller that asks question [OwnerCard.key] joins one card, drawn with the choices of
 * the caller that has the most, so that the voice's `[Yes, No]` and the text channel's `[Yes, No, Reply…]` for one
 * screenshot question are one card with "Reply…". A tap answers every caller whose card takes that choice, a keypad's
 * number within the pick's range too; a caller whose card lacks it (the voice and "Reply…") keeps waiting, and the card
 * shows again for it, as it does after a number outside the range. The newest question's card shows, and an older one
 * comes back once the newer has ended; a card goes when its last caller leaves.
 *
 * The card shows on the overlay ([OwnerScreen.card]) while the conversation screen is not in front ([screenInFront]);
 * while it is, the screen shows [open] above its field instead, so that the overlay never covers the field and the
 * keyboard, and a confirmation is never answered over Kestrel's own screen (design 12.2). "Reply…" on the overlay hides
 * the card until the screen has come to the front, or for [replyOpenMillis] when it does not come.
 *
 * Every tap reaches the desk through one entry ([tapped]), the owner's on the overlay and the debug hook's press alike,
 * with the screen source's status at the tap ([ScreenTap.status]: its reading, its count of screen-offs and the number
 * of the source's newest reading, taken on the main thread in order with the source's reports). A Continue counts only
 * on an unlocked phone (plan 7E's rule for Continue): tapped on an unlocked phone it answers at once, and tapped with the
 * screen off it counts for nothing. One tapped on the lock screen keeps its tap's number and waits for the owner's unlock
 * within the lock that it was tapped in, while its question still waits for a Continue: [notice] tells him to unlock the
 * phone ([UNLOCK_TO_CONTINUE]), the card shows again, and the tap answers on the first reading after it, by number, that
 * is unlocked within the same count; readings from before the tap never decide it. The desk follows the source
 * ([OwnerScreen.screens]) for as long as a card waits, and shows a card only once it has a reading, so the source counts
 * every screen-off after a tap. A screen-off after the tap drops it, another of the card's choices (Cancel or Stop)
 * answers in its place and drops it, the end of its question drops it, a failure to follow the source drops it, and a
 * newer Continue on the same question replaces it with its own number; each question holds its own, and it lives in
 * Kestrel's process only. Unlike the goal notification's Continue, it cannot ask Android for the unlock, because an
 * overlay cannot. When the source cannot be followed, the desk reports it ([report]), shows the card all the same and
 * tries again every [FOLLOW_RETRY_MILLIS] while a card waits; meanwhile a Continue that is not on an unlocked phone by
 * its own status is never held: the card shows again, [notice] tells the owner to unlock the phone, and he taps again
 * after his unlock, which answers by its own status at once (decision 27).
 *
 * A tap that answers takes its number among the owner's inputs ([inputIds]) when it has come back from the card, before
 * any caller runs, and every caller that it answers gets that one number (plan 7F). A card that got no answer, because
 * no screen session was connected or the session closed, shows again when a screen session connects
 * ([OwnerScreen.connections]).
 */
internal class CardDesk(
    private val screen: OwnerScreen,
    private val scope: CoroutineScope,
    private val report: (String, Throwable) -> Unit,
    private val notice: (String) -> Unit = {},
    private val replyOpenMillis: Long = REPLY_OPEN_MILLIS,
    private val inputIds: () -> Long = InputIds::next,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : SharedCards, OverlayCards {
    private class Caller(val card: OwnerCard, val onReply: () -> Unit) {
        val tapped = CompletableDeferred<CardTap>()
    }

    // The overlay's card, and a count that each tap raises, so that a card whose callers still wait shows again.
    private data class Showing(val card: OwnerCard, val round: Int)

    private val lock = Any()
    private val callers = mutableListOf<Caller>()
    private var round = 0
    private val front = MutableStateFlow(false)
    private val replied = MutableStateFlow<Int?>(null)
    private val openCard = MutableStateFlow<OwnerCard?>(null)
    private val showing = MutableStateFlow<Showing?>(null)
    private var replyWait: Job? = null

    // The Continue of each question that waits for the owner's unlock, by its question's key. Under the lock.
    private val heldContinues = HashMap<Int, Job>()

    // What the desk knows of the screen while a card waits: nothing yet, the source's newest reading, or that following
    // the source failed (null while no card waits); and whether any card waits, which starts and stops following.
    private sealed interface Following {
        data object Starting : Following

        data class Read(val status: ScreenStatus) : Following

        data object Failed : Following
    }

    private val following = MutableStateFlow<Following?>(null)
    private val waiting = MutableStateFlow(false)

    // The card on the overlay, from when the desk gave it to the screen, for the debug hook (Task 13a); under the lock.
    private class Presented(val card: OwnerCard, val at: Long)

    private var presented: Presented? = null

    override val open: StateFlow<OwnerCard?> = openCard.asStateFlow()

    // The card on the overlay in its screen session ([connection]): a new session shows it again.
    private data class Presenting(val shown: Showing, val connection: Int)

    init {
        // The source is followed from before a card can show until no card waits, so that every screen-off after a tap
        // reaches the desk. A source that cannot be followed is reported, and the desk tries again while a card waits;
        // meanwhile the card shows all the same, so that no question loses its card (decision 27).
        scope.launch {
            waiting.collectLatest { any ->
                if (!any) {
                    following.value = null
                    return@collectLatest
                }
                following.value = Following.Starting
                while (true) {
                    try {
                        screen.screens.collect { following.value = Following.Read(it) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        report("Following the screen for the question card", e)
                    }
                    unfollowed()
                    delay(FOLLOW_RETRY_MILLIS)
                }
            }
        }
        scope.launch {
            val known = following.map { it != null && it != Following.Starting }.distinctUntilChanged()
            combine(showing, front, replied, screen.connections, known) { shown, inFront, reply, connection, followed ->
                shown?.takeIf { followed && !inFront && reply != it.card.key }?.let { Presenting(it, connection) }
            }
                .distinctUntilChanged()
                .collectLatest { presenting -> if (presenting != null) present(presenting.shown) }
        }
    }

    // The source is no longer followed: no held Continue may wait across a time whose screen-offs the desk cannot see.
    private fun unfollowed() = synchronized(lock) {
        following.value = Following.Failed
        for (wait in heldContinues.values) wait.cancel()
        heldContinues.clear()
    }

    /** The conversation screen came to the front, or left it. */
    fun screenInFront(inFront: Boolean) {
        if (inFront) replied.value = null
        front.value = inFront
    }

    // The card that the overlay shows and its answer in one block under the lock (review R3), for the debug hook's
    // press (Task 13a): no other card takes the overlay between the hook's check and its answer. The press is the
    // owner's tap: it goes through the one entry for taps, stamped with the source's status at the press.
    override fun <T> withShown(block: (shown: ShownCard?, answer: (CardChoice) -> TapEnd) -> T): T =
        synchronized(lock) {
            val mine = presented?.takeIf { !front.value }
            block(mine?.let { ShownCard(it.card, clock() - it.at) }) { choice ->
                if (mine != null && presented === mine) {
                    presented = null
                    tapped(mine.card.key, choice, screen.screenStatus())
                } else {
                    TapEnd.WITHDRAWN
                }
            }
        }

    override suspend fun ask(card: OwnerCard, onReply: () -> Unit): CardTap {
        val caller = Caller(card, onReply)
        synchronized(lock) {
            callers += caller
            publish()
        }
        try {
            return caller.tapped.await()
        } finally {
            synchronized(lock) {
                if (callers.remove(caller)) publish()
            }
        }
    }

    override fun toString(): String = "CardDesk(open=${openCard.value?.key}, callers=${synchronized(lock) { callers.size }}, front=${front.value})"

    // Under the lock: the newest question's card, with the most choices among its callers.
    private fun publish() {
        val key = callers.lastOrNull()?.card?.key
        val card = callers.filter { it.card.key == key }.maxByOrNull { it.card.choices.size }?.card
        openCard.value = card
        showing.value = card?.let { Showing(it, round) }
        // A card that is no longer the newest question's is no longer the debug hook's to press, even in the moment before
        // the overlay withdraws it (Task 13a).
        if (presented?.card?.key != card?.key) presented = null
        // A Continue waits only while its question still has callers that take it.
        val dropped = heldContinues.keys.filterNot(::takesContinue)
        for (key in dropped) heldContinues.remove(key)?.cancel()
        waiting.value = callers.isNotEmpty()
    }

    // Under the lock: whether a caller of question [key] still takes a Continue.
    private fun takesContinue(key: Int): Boolean =
        callers.any { it.card.key == key && it.card.takes(CardChoice.Continue) }

    // A card without an answer, as when no screen session is connected or its session closed, shows again when the next
    // screen session connects.
    private suspend fun present(shown: Showing) {
        val mine = Presented(shown.card, clock())
        // A newer question may have taken the overlay since this card's turn began: it is not the hook's to press.
        synchronized(lock) { if (showing.value?.card?.key == shown.card.key) presented = mine }
        val tap = try {
            screen.card(shown.card)
        } finally {
            synchronized(lock) { if (presented === mine) presented = null }
        } ?: return
        tapped(shown.card.key, tap.choice, tap.status)
    }

    // The one entry for a tap on the card, the owner's or the debug hook's, with the screen source's status at the tap
    // ([at]). It takes the tap's number among the owner's inputs as it reaches the desk (plan 7F).
    private fun tapped(key: Int, choice: CardChoice, at: ScreenStatus): TapEnd = when (choice) {
        CardChoice.Reply -> {
            replyOpened(key)
            TapEnd.ANSWERED
        }
        CardChoice.Continue -> continueTapped(key, CardTap(choice, inputIds()), at)
        else -> {
            deliver(key, CardTap(choice, inputIds()))
            TapEnd.ANSWERED
        }
    }

    // The owner's Continue on question [key], judged by the screen at the tap ([at]): on an unlocked phone it answers at
    // once, and with the screen off it counts for nothing and the card shows again. On the lock screen it is held while
    // the desk follows the source and its question still waits for a Continue: the card shows again, the bar says to
    // unlock the phone, and the tap waits for the readings after it (hold). While the source is not followed it is not
    // held: the card shows again with the same words, and the owner taps again after his unlock (decision 27). A newer
    // Continue on the same question replaces a held one; another choice or the end of its question drops it (publish).
    private fun continueTapped(key: Int, tap: CardTap, at: ScreenStatus): TapEnd {
        val end = synchronized(lock) {
            when {
                !takesContinue(key) || at.reading == ScreenReading.UNLOCKED -> {
                    deliver(key, tap)
                    return TapEnd.ANSWERED
                }
                at.reading == ScreenReading.OFF -> {
                    showAgain()
                    return TapEnd.DROPPED_BY_SCREEN_OFF
                }
                following.value is Following.Read -> {
                    hold(key, tap, at)
                    TapEnd.HELD
                }
                else -> {
                    heldContinues.remove(key)?.cancel()
                    showAgain()
                    TapEnd.REFUSED_UNFOLLOWED
                }
            }
        }
        notice(UNLOCK_TO_CONTINUE)
        return end
    }

    // Under the lock, while the desk follows the source: holds the Continue of question [key] that was tapped on the lock
    // screen at [at]. Only the source's readings numbered after the tap's decide it, so a reading from before the tap
    // (a report on its way, or the desk's newest when the lock screen came back without a screen-off) never answers or
    // drops it: the lock screen of the tap's count goes on holding it, an unlocked reading of that count answers it, and
    // a higher count (a screen-off since the tap) drops it. A failure to follow the source drops it (unfollowed).
    private fun hold(key: Int, tap: CardTap, at: ScreenStatus) {
        heldContinues.remove(key)?.cancel()
        val wait = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            val end = following.filterIsInstance<Following.Read>().map { it.status }
                .first { now -> now.seq > at.seq && (now.offs != at.offs || now.reading != ScreenReading.LOCKED) }
            synchronized(lock) {
                if (heldContinues[key] !== self) return@launch
                heldContinues.remove(key)
                if (end.offs == at.offs && end.reading == ScreenReading.UNLOCKED) deliver(key, tap)
            }
        }
        heldContinues[key] = wait
        showAgain()
        wait.start()
    }

    // Under the lock.
    private fun showAgain() {
        round++
        publish()
    }

    private fun deliver(key: Int, tap: CardTap) = synchronized(lock) {
        val answered = callers.filter { it.card.key == key && it.card.takes(tap.choice) }
        callers.removeAll(answered)
        for (caller in answered) caller.tapped.complete(tap)
        round++
        publish()
    }

    private fun replyOpened(key: Int) {
        val asked = synchronized(lock) { callers.filter { it.card.key == key && CardChoice.Reply in it.card.choices } }
        replied.value = key
        replyWait?.cancel()
        replyWait = scope.launch {
            delay(replyOpenMillis)
            replied.compareAndSet(key, null)
        }
        for (caller in asked) caller.onReply()
    }

    companion object {
        /** How long a "Reply…" on the overlay waits for the conversation screen before it shows the card again. */
        const val REPLY_OPEN_MILLIS = 5_000L

        /**
         * A Continue tapped on the card on the lock screen, which waits for the unlock, or, while the screen source cannot
         * be followed, is tapped again after it (plan 7E's rule for Continue; decision 27).
         */
        const val UNLOCK_TO_CONTINUE = "Unlock the phone to continue."

        /** How long the desk waits before it follows the screen source again after it could not (decision 27). */
        const val FOLLOW_RETRY_MILLIS = 5_000L
    }
}

/** The card that the overlay shows, and how long ago the card desk gave it to the screen (Task 13a). */
internal class ShownCard(val card: OwnerCard, val shownMillis: Long)

/**
 * The question card over the app, for the debug hook `agent-answer --es channel card` (Task 13a; plan 7G's F1): Kestrel's
 * main code never presses a card, the owner does, so only debug code (`CardPresser`) answers through this (review L3).
 */
internal interface OverlayCards {
    /**
     * Runs [block] under the card desk's lock with the card that the overlay shows, or null while none does (also while
     * Kestrel's screen is in front), and with `answer`, which gives the choice to the desk's one entry for taps, as the
     * owner's tap on a button does, and takes the card from the hook. `answer` returns what became of the tap
     * ([TapEnd]): a Continue on the lock screen is held as the owner's is. No other card takes the overlay while [block]
     * runs.
     */
    fun <T> withShown(block: (shown: ShownCard?, answer: (CardChoice) -> TapEnd) -> T): T
}
