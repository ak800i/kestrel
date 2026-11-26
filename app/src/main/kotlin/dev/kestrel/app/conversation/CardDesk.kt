package dev.kestrel.app.conversation

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
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * The owner's tap on a card's [choice], with [act], its number among his inputs, which the card desk takes when the tap
 * has come back from the card, before any caller runs (`InputIds`; design 6.5 of sub-project 7, plan 7F): one tap is one
 * act, whichever callers it answers.
 */
internal data class CardTap(val choice: CardChoice, val act: Long)

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
 * whichever channel asks it). Every caller that asks question [OwnerCard.key] joins one card, drawn with the choices of the
 * caller that has the most, so that the voice's `[Yes, No]` and the text channel's `[Yes, No, Reply…]` for one screenshot
 * question are one card with "Reply…". A tap answers every caller whose card takes that choice, a keypad's number within the
 * pick's range too; a caller whose card lacks it (the voice and "Reply…") keeps waiting, and the card shows again for it,
 * as it does after a number outside the range. The newest question's card shows, and an older
 * one comes back once the newer has ended; a card goes when its last caller leaves.
 *
 * The card shows on the overlay ([OwnerScreen.card]) while the conversation screen is not in front ([screenInFront]); while
 * it is, the screen shows [open] above its field instead, so that the overlay never covers the field and the keyboard,
 * and a confirmation is never answered over Kestrel's own screen (design 12.2). "Reply…" on the overlay hides the card until
 * the screen has come to the front, or for [replyOpenMillis] when it does not come. A Continue that the owner taps while
 * the phone is locked waits for his unlock, as the goal notification's Continue does (plan 7E): it keeps its tap's number,
 * [notice] tells the owner to unlock the phone ([UNLOCK_TO_CONTINUE]), and it answers once the screen is on and the phone
 * unlocked ([OwnerScreen.unlocked]). The card shows again meanwhile, so that another of its choices, such as Stop, can
 * answer in its place, which drops the waiting Continue; a newer Continue replaces it. A tap that answers takes its number
 * among the owner's inputs ([inputIds]) when it has come back from the card, before any caller runs, and every caller that
 * it answers gets that one number (plan 7F). A card that got no answer, because no screen session was connected or the
 * session closed, shows again when a screen session connects ([OwnerScreen.connections]).
 */
internal class CardDesk(
    private val screen: OwnerScreen,
    private val scope: CoroutineScope,
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

    // The Continue that waits for the owner's unlock: its card's question and the wait. Under the lock.
    private class HeldContinue(val key: Int, val wait: Job)

    private var heldContinue: HeldContinue? = null

    // The card on the overlay, from when the desk gave it to the screen, for the debug hook (Task 13a); under the lock.
    private class Presented(val card: OwnerCard, val at: Long)

    private var presented: Presented? = null

    override val open: StateFlow<OwnerCard?> = openCard.asStateFlow()

    // The card on the overlay in its screen session ([connection]): a new session shows it again.
    private data class Presenting(val shown: Showing, val connection: Int)

    init {
        scope.launch {
            combine(showing, front, replied, screen.connections) { shown, inFront, reply, connection ->
                shown?.takeIf { !inFront && reply != it.card.key }?.let { Presenting(it, connection) }
            }
                .distinctUntilChanged()
                .collectLatest { presenting -> if (presenting != null) present(presenting.shown) }
        }
    }

    /** The conversation screen came to the front, or left it. */
    fun screenInFront(inFront: Boolean) {
        if (inFront) replied.value = null
        front.value = inFront
    }

    // The card that the overlay shows and its answer in one block under the lock (review R3), for the debug hook's press
    // (Task 13a): no other card takes the overlay between the hook's check and its answer.
    override fun <T> withShown(block: (shown: ShownCard?, answer: (CardChoice) -> Unit) -> T): T = synchronized(lock) {
        val mine = presented?.takeIf { !front.value }
        block(mine?.let { ShownCard(it.card, clock() - it.at) }) { choice ->
            if (mine != null && presented === mine) {
                presented = null
                // The hook's press is the owner's tap: it takes its number as it reaches the desk (plan 7F).
                deliver(mine.card.key, CardTap(choice, inputIds()))
            }
        }
    }

    override fun locked(): Boolean = screen.locked()

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
        heldContinue?.takeIf { held -> callers.none { it.card.key == held.key && it.card.takes(CardChoice.Continue) } }?.let { held ->
            held.wait.cancel()
            heldContinue = null
        }
    }

    // A card without an answer, as when no screen session is connected or its session closed, shows again when the next
    // screen session connects.
    private suspend fun present(shown: Showing) {
        val mine = Presented(shown.card, clock())
        // A newer question may have taken the overlay since this card's turn began: it is not the hook's to press.
        synchronized(lock) { if (showing.value?.card?.key == shown.card.key) presented = mine }
        val choice = try {
            screen.card(shown.card)
        } finally {
            synchronized(lock) { if (presented === mine) presented = null }
        } ?: return
        when {
            choice == CardChoice.Reply -> replyOpened(shown.card.key)
            choice == CardChoice.Continue && screen.locked() -> holdForUnlock(shown.card.key, CardTap(choice, inputIds()))
            // The tap's number among the owner's inputs, taken as the tap reaches the desk (plan 7F).
            else -> deliver(shown.card.key, CardTap(choice, inputIds()))
        }
    }

    // The owner's Continue on a locked phone: it keeps the number that it took at the tap, and answers once he has unlocked
    // the phone, if its question still waits for it; the card shows again meanwhile.
    private fun holdForUnlock(key: Int, tap: CardTap) {
        notice(UNLOCK_TO_CONTINUE)
        synchronized(lock) {
            heldContinue?.wait?.cancel()
            val wait = scope.launch(start = CoroutineStart.LAZY) {
                screen.unlocked()
                val self = coroutineContext.job
                synchronized(lock) {
                    // Only the Continue that still waits answers: a newer one, or another choice, may have replaced it.
                    if (heldContinue?.wait === self) {
                        heldContinue = null
                        deliver(key, tap)
                    }
                }
            }
            heldContinue = HeldContinue(key, wait)
            round++
            publish()
            wait.start()
        }
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

        /** A Continue tapped on the card while the phone is locked, which waits for the unlock (plan 7E's rule for Continue). */
        const val UNLOCK_TO_CONTINUE = "Unlock the phone to continue."
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
     * Kestrel's screen is in front), and with `answer`, which answers that card's question as the owner's tap on a button
     * does and takes the card from the hook. No other card takes the overlay while [block] runs.
     */
    fun <T> withShown(block: (shown: ShownCard?, answer: (CardChoice) -> Unit) -> T): T

    /** Whether the phone is locked, when a Continue waits for the owner's unlock (plan 7E). */
    fun locked(): Boolean
}
