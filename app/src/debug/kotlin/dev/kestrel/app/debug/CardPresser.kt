package dev.kestrel.app.debug

import dev.kestrel.app.conversation.CardChoice
import dev.kestrel.app.conversation.OverlayCards
import dev.kestrel.app.conversation.TapEnd
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.screen.overlay.CARD_ARMING_MILLIS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * What the debug hook's press of the overlay's card found (Task 13a). [PRESSED] answered the question; [HELD] is a
 * Continue on the lock screen, which the card desk holds as the owner's tap and which answers by itself once the phone
 * is unlocked within that lock. The rest answered nothing and leave the question open: the guard's refusals,
 * [SCREEN_OFF] (the screen was off at the press, so a Continue counts for nothing), [UNFOLLOWED] (a Continue on the lock
 * screen while the card desk cannot follow the screen source, which it does not hold, so the press must come again after
 * the unlock) and [WITHDRAWN] (the card was no longer the overlay's when the press came).
 */
internal enum class CardPress {
    PRESSED,
    HELD,
    NOT_ON_OVERLAY,
    OTHER_CARD,
    NO_BUTTON,
    NOT_ARMED,
    SCREEN_OFF,
    UNFOLLOWED,
    WITHDRAWN,
}

/**
 * Presses a button of the card over the app as the owner's tap, for agent-answer `--es channel card`, which may not tap
 * the screen by coordinates (Task 13a; plan 7G's F1 and review N2). The check and the answer run in one block under the
 * card desk's lock ([OverlayCards.withShown]; review R3 of plan 7F), so no other card takes the overlay between them.
 * The card must be the one that the hook read, question `key` of `kind`, so that a card that replaced it is refused,
 * also one with the same buttons; it must take the choice, never "Reply…", which opens Kestrel's screen; and the desk
 * must have given it to the screen twice the card's arming delay ago, because the overlay may wait one delay before it
 * shows a card, which then ignores taps for another. The press then goes through the desk's one entry for taps, so a
 * Continue on the lock screen is held exactly as the owner's is; the answer says what the desk did with it ([TapEnd]).
 * The press runs on [main], the main thread, which is the screen source's: the desk stamps it with the source's status
 * there, in order with the source's reports, as the owner's tap is stamped (plan 7F's fifth re-check). The press carries
 * its own act's number among the inputs, which [hooksAct] takes and claims as the hooks' before the desk gets the press,
 * so that the answer that it gives is never the owner's act (plan 7G).
 */
internal class CardPresser(
    private val overlay: OverlayCards,
    private val hooksAct: () -> Long,
    private val main: CoroutineContext = Dispatchers.Main.immediate,
) {
    suspend fun press(key: Int, kind: QuestionKind?, choice: CardChoice): CardPress =
        withContext(main) { pressHere(key, kind, choice) }

    private fun pressHere(key: Int, kind: QuestionKind?, choice: CardChoice): CardPress = overlay.withShown { shown, answer ->
        when {
            shown == null -> CardPress.NOT_ON_OVERLAY
            shown.card.key != key || shown.card.kind != kind -> CardPress.OTHER_CARD
            choice == CardChoice.Reply || !shown.card.takes(choice) -> CardPress.NO_BUTTON
            shown.shownMillis < 2 * CARD_ARMING_MILLIS -> CardPress.NOT_ARMED
            else -> when (answer(choice, hooksAct())) {
                TapEnd.ANSWERED -> CardPress.PRESSED
                TapEnd.HELD -> CardPress.HELD
                TapEnd.DROPPED_BY_SCREEN_OFF -> CardPress.SCREEN_OFF
                TapEnd.REFUSED_UNFOLLOWED -> CardPress.UNFOLLOWED
                TapEnd.WITHDRAWN -> CardPress.WITHDRAWN
            }
        }
    }
}
