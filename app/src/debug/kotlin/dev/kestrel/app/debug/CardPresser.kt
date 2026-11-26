package dev.kestrel.app.debug

import dev.kestrel.app.conversation.CardChoice
import dev.kestrel.app.conversation.OverlayCards
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.screen.overlay.CARD_ARMING_MILLIS

/** What the debug hook's press of the overlay's card found (Task 13a); only [PRESSED] answered it. */
internal enum class CardPress { PRESSED, NOT_ON_OVERLAY, OTHER_CARD, NO_BUTTON, NOT_ARMED, LOCKED }

/**
 * Presses a button of the card over the app as the owner's tap, for agent-answer `--es channel card`, which may not tap the
 * screen by coordinates (Task 13a; plan 7G's F1 and review N2). The check and the answer run in one block under the card
 * desk's lock ([OverlayCards.withShown]; review R3 of plan 7F), so no other card takes the overlay between them. The card
 * must be the one that the hook read, question `key` of `kind`, so that a card that replaced it is refused, also one with
 * the same buttons; it must take the choice, never "Reply…", which opens Kestrel's screen; and the desk must have given it
 * to the screen twice the card's arming delay ago, because the overlay may wait one delay before it shows a card, which
 * then ignores taps for another. A Continue while the phone is locked is refused, because the owner's tap would wait for
 * his unlock.
 */
internal class CardPresser(private val overlay: OverlayCards) {
    fun press(key: Int, kind: QuestionKind?, choice: CardChoice): CardPress = overlay.withShown { shown, answer ->
        when {
            shown == null -> CardPress.NOT_ON_OVERLAY
            shown.card.key != key || shown.card.kind != kind -> CardPress.OTHER_CARD
            choice == CardChoice.Reply || !shown.card.takes(choice) -> CardPress.NO_BUTTON
            shown.shownMillis < 2 * CARD_ARMING_MILLIS -> CardPress.NOT_ARMED
            choice == CardChoice.Continue && overlay.locked() -> CardPress.LOCKED
            else -> {
                answer(choice)
                CardPress.PRESSED
            }
        }
    }
}
