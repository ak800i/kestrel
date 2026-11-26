package dev.kestrel.app.debug

import dev.kestrel.app.conversation.CardChoice
import dev.kestrel.app.conversation.OverlayCards
import dev.kestrel.app.conversation.TapEnd
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.screen.overlay.CARD_ARMING_MILLIS
import kotlinx.coroutines.Deferred

/**
 * What the debug hook's press of the overlay's card found (Task 13a): only [PRESSED] answered it. [LOCKED]: the press
 * was a Continue on the lock screen, which the card desk holds as the owner's tap until his unlock; [SCREEN_OFF]: the
 * screen went off since the press, so the desk dropped it. Both leave the question open.
 */
internal enum class CardPress { PRESSED, NOT_ON_OVERLAY, OTHER_CARD, NO_BUTTON, NOT_ARMED, LOCKED, SCREEN_OFF }

/**
 * Presses a button of the card over the app as the owner's tap, for agent-answer `--es channel card`, which may not tap
 * the screen by coordinates (Task 13a; plan 7G's F1 and review N2). The check and the answer run in one block under the
 * card desk's lock ([OverlayCards.withShown]; review R3 of plan 7F), so no other card takes the overlay between them.
 * The card must be the one that the hook read, question `key` of `kind`, so that a card that replaced it is refused,
 * also one with the same buttons; it must take the choice, never "Reply…", which opens Kestrel's screen; and the desk
 * must have given it to the screen twice the card's arming delay ago, because the overlay may wait one delay before it
 * shows a card, which then ignores taps for another. The press then goes through the desk's one entry for taps, so a
 * Continue on the lock screen is held exactly as the owner's is; the answer reports what became of it ([TapEnd]).
 */
internal class CardPresser(private val overlay: OverlayCards) {
    private sealed interface Checked {
        data class Refused(val press: CardPress) : Checked

        class Tapped(val end: Deferred<TapEnd>) : Checked
    }

    suspend fun press(key: Int, kind: QuestionKind?, choice: CardChoice): CardPress {
        val checked = overlay.withShown { shown, answer ->
            when {
                shown == null -> Checked.Refused(CardPress.NOT_ON_OVERLAY)
                shown.card.key != key || shown.card.kind != kind -> Checked.Refused(CardPress.OTHER_CARD)
                choice == CardChoice.Reply || !shown.card.takes(choice) -> Checked.Refused(CardPress.NO_BUTTON)
                shown.shownMillis < 2 * CARD_ARMING_MILLIS -> Checked.Refused(CardPress.NOT_ARMED)
                else -> Checked.Tapped(answer(choice))
            }
        }
        return when (checked) {
            is Checked.Refused -> checked.press
            is Checked.Tapped -> when (checked.end.await()) {
                TapEnd.ANSWERED -> CardPress.PRESSED
                TapEnd.HELD -> CardPress.LOCKED
                TapEnd.DROPPED -> CardPress.SCREEN_OFF
            }
        }
    }
}
