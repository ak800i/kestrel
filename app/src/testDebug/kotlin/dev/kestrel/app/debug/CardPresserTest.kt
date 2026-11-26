package dev.kestrel.app.debug

import dev.kestrel.app.conversation.CardChoice
import dev.kestrel.app.conversation.OverlayCards
import dev.kestrel.app.conversation.OwnerCard
import dev.kestrel.app.conversation.ShownCard
import dev.kestrel.app.conversation.TapEnd
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.screen.overlay.CARD_ARMING_MILLIS
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The debug hook's press of the card over the app (Task 13a; plan 7G's F1 and review N2): only the card whose question
 * number and kind the hook read, only a button that the card takes, and only once the card takes taps.
 */
class CardPresserTest {
    private val overlay = FakeOverlay()
    private val presser = CardPresser(overlay)
    private val confirmation = OwnerCard(4, "Send the message?", listOf(CardChoice.Confirm, CardChoice.Cancel), question = 4, kind = QuestionKind.CONFIRM)

    @Test
    fun aPressAnswersOnlyTheCardThatTheHookReadWithAButtonThatItTakesOnceItTakesTaps() = runTest {
        assertEquals(CardPress.NOT_ON_OVERLAY, presser.press(4, QuestionKind.CONFIRM, CardChoice.Confirm))
        overlay.shown = ShownCard(confirmation, 2 * CARD_ARMING_MILLIS - 1)
        assertEquals(CardPress.NOT_ARMED, presser.press(4, QuestionKind.CONFIRM, CardChoice.Confirm))

        overlay.shown = ShownCard(confirmation, 2 * CARD_ARMING_MILLIS)
        // A card that replaced the one that the hook read is refused, also when its buttons are the same.
        assertEquals(CardPress.OTHER_CARD, presser.press(3, QuestionKind.CONFIRM, CardChoice.Confirm))
        assertEquals(CardPress.OTHER_CARD, presser.press(4, QuestionKind.YES_NO, CardChoice.Confirm))
        assertEquals(CardPress.NO_BUTTON, presser.press(4, QuestionKind.CONFIRM, CardChoice.Yes))
        assertEquals(CardPress.NO_BUTTON, presser.press(4, QuestionKind.CONFIRM, CardChoice.Reply))
        assertEquals(emptyList<CardChoice>(), overlay.answers)

        assertEquals(CardPress.PRESSED, presser.press(4, QuestionKind.CONFIRM, CardChoice.Confirm))
        assertEquals(listOf<CardChoice>(CardChoice.Confirm), overlay.answers)
    }

    // The second re-check's N2-M1: the press goes through the desk's one entry for taps, and the hook's answer follows
    // what the desk did with it: a Continue that the desk holds for the owner's unlock is LOCKED, one that it dropped
    // because the screen went off is SCREEN_OFF, and the question stays open for both.
    @Test
    fun theHooksAnswerForAContinueFollowsWhatTheDeskDidWithIt() = runTest {
        val handOver = OwnerCard.handOver(-1, "Sign in yourself, then tap Continue.")
        overlay.shown = ShownCard(handOver, 2 * CARD_ARMING_MILLIS)

        overlay.end = TapEnd.HELD
        assertEquals(CardPress.LOCKED, presser.press(-1, null, CardChoice.Continue))
        overlay.end = TapEnd.DROPPED
        assertEquals(CardPress.SCREEN_OFF, presser.press(-1, null, CardChoice.Continue))
        overlay.end = TapEnd.ANSWERED
        assertEquals(CardPress.PRESSED, presser.press(-1, null, CardChoice.Cancel))
        assertEquals(listOf(CardChoice.Continue, CardChoice.Continue, CardChoice.Cancel), overlay.answers)
    }

    private class FakeOverlay : OverlayCards {
        var shown: ShownCard? = null
        var end = TapEnd.ANSWERED
        val answers = mutableListOf<CardChoice>()

        override fun <T> withShown(block: (shown: ShownCard?, answer: (CardChoice) -> Deferred<TapEnd>) -> T): T =
            block(shown) { choice ->
                answers += choice
                CompletableDeferred(end)
            }
    }
}
