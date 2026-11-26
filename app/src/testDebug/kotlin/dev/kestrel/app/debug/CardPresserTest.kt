package dev.kestrel.app.debug

import dev.kestrel.app.conversation.CardChoice
import dev.kestrel.app.conversation.OverlayCards
import dev.kestrel.app.conversation.OwnerCard
import dev.kestrel.app.conversation.ShownCard
import dev.kestrel.app.phone.ScreenReading
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.screen.overlay.CARD_ARMING_MILLIS
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

    // Plan 7E: the owner's Continue while the phone is locked waits for his unlock, so the hook's is refused.
    @Test
    fun aContinueWhileThePhoneIsLockedIsRefused() = runTest {
        val held = OwnerCard(9, "Continue the goal?", listOf(CardChoice.Stop, CardChoice.Continue), question = 9, kind = QuestionKind.CONTINUE_GOAL)
        overlay.shown = ShownCard(held, 2 * CARD_ARMING_MILLIS)
        overlay.reading = ScreenReading.LOCKED

        assertEquals(CardPress.LOCKED, presser.press(9, QuestionKind.CONTINUE_GOAL, CardChoice.Continue))
        assertEquals(CardPress.PRESSED, presser.press(9, QuestionKind.CONTINUE_GOAL, CardChoice.Stop))
        assertEquals(listOf<CardChoice>(CardChoice.Stop), overlay.answers)
    }

    private class FakeOverlay : OverlayCards {
        var shown: ShownCard? = null
        var reading = ScreenReading.UNLOCKED
        val answers = mutableListOf<CardChoice>()

        override fun <T> withShown(block: (shown: ShownCard?, answer: (CardChoice) -> Unit) -> T): T = block(shown) { answers += it }

        override suspend fun reading(): ScreenReading = reading
    }
}
