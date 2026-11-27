package dev.kestrel.app.debug

import dev.kestrel.app.conversation.CardChoice
import dev.kestrel.app.conversation.OverlayCards
import dev.kestrel.app.conversation.OwnerCard
import dev.kestrel.app.conversation.ShownCard
import dev.kestrel.app.conversation.TapEnd
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.screen.overlay.CARD_ARMING_MILLIS
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executors

/**
 * The debug hook's press of the card over the app (Task 13a; plan 7G's F1 and review N2): only the card whose question
 * number and kind the hook read, only a button that the card takes, and only once the card takes taps.
 */
class CardPresserTest {
    // The screen source's thread, the main thread on the phone, on which the press must run.
    private val sourceThread = Executors.newSingleThreadExecutor { Thread(it, "screen-source") }.asCoroutineDispatcher()
    private val overlay = FakeOverlay()
    private val presser = CardPresser(overlay, sourceThread)

    private fun press(key: Int, kind: QuestionKind?, choice: CardChoice) = runBlocking { presser.press(key, kind, choice) }

    @After
    fun closeTheSourceThread() {
        sourceThread.close()
    }
    private val confirmation = OwnerCard(4, "Send the message?", listOf(CardChoice.Confirm, CardChoice.Cancel), question = 4, kind = QuestionKind.CONFIRM)

    @Test
    fun aPressAnswersOnlyTheCardThatTheHookReadWithAButtonThatItTakesOnceItTakesTaps() {
        assertEquals(CardPress.NOT_ON_OVERLAY, press(4, QuestionKind.CONFIRM, CardChoice.Confirm))
        overlay.shown = ShownCard(confirmation, 2 * CARD_ARMING_MILLIS - 1)
        assertEquals(CardPress.NOT_ARMED, press(4, QuestionKind.CONFIRM, CardChoice.Confirm))

        overlay.shown = ShownCard(confirmation, 2 * CARD_ARMING_MILLIS)
        // A card that replaced the one that the hook read is refused, also when its buttons are the same.
        assertEquals(CardPress.OTHER_CARD, press(3, QuestionKind.CONFIRM, CardChoice.Confirm))
        assertEquals(CardPress.OTHER_CARD, press(4, QuestionKind.YES_NO, CardChoice.Confirm))
        assertEquals(CardPress.NO_BUTTON, press(4, QuestionKind.CONFIRM, CardChoice.Yes))
        assertEquals(CardPress.NO_BUTTON, press(4, QuestionKind.CONFIRM, CardChoice.Reply))
        assertEquals(emptyList<CardChoice>(), overlay.answers)

        assertEquals(CardPress.PRESSED, press(4, QuestionKind.CONFIRM, CardChoice.Confirm))
        assertEquals(listOf<CardChoice>(CardChoice.Confirm), overlay.answers)
    }

    // The second re-check's N2-M1 and the third's N3-N2: the press goes through the desk's one entry for taps, and the
    // hook's answer names what the desk did with it: a Continue that the desk holds for the owner's unlock is HELD, which
    // acts at the unlock, one that the screen being off dropped is SCREEN_OFF, and a card that was no longer the overlay's
    // is WITHDRAWN; one on the lock screen that the desk does not hold, because it cannot follow the screen, is UNFOLLOWED.
    @Test
    fun theHooksAnswerForAContinueNamesWhatTheDeskDidWithIt() {
        val handOver = OwnerCard.handOver(-1, "Sign in yourself, then tap Continue.")
        overlay.shown = ShownCard(handOver, 2 * CARD_ARMING_MILLIS)

        overlay.end = TapEnd.HELD
        assertEquals(CardPress.HELD, press(-1, null, CardChoice.Continue))
        overlay.end = TapEnd.DROPPED_BY_SCREEN_OFF
        assertEquals(CardPress.SCREEN_OFF, press(-1, null, CardChoice.Continue))
        overlay.end = TapEnd.REFUSED_UNFOLLOWED
        assertEquals(CardPress.UNFOLLOWED, press(-1, null, CardChoice.Continue))
        overlay.end = TapEnd.WITHDRAWN
        assertEquals(CardPress.WITHDRAWN, press(-1, null, CardChoice.Continue))
        overlay.end = TapEnd.ANSWERED
        assertEquals(CardPress.PRESSED, press(-1, null, CardChoice.Cancel))
        assertEquals(
            listOf(CardChoice.Continue, CardChoice.Continue, CardChoice.Continue, CardChoice.Continue, CardChoice.Cancel),
            overlay.answers,
        )
    }

    // The fifth re-check's N5-M1: the press runs on the screen source's thread, where the desk stamps it, never on the
    // hook's own thread.
    @Test
    fun thePressRunsOnTheScreenSourcesThread() {
        overlay.shown = ShownCard(confirmation, 2 * CARD_ARMING_MILLIS)

        assertEquals(CardPress.PRESSED, press(4, QuestionKind.CONFIRM, CardChoice.Confirm))
        // The coroutines' debug mode adds the coroutine's name to the thread's.
        assertEquals(listOf("screen-source"), overlay.threads.map { it.substringBefore(" @") })
    }

    private class FakeOverlay : OverlayCards {
        var shown: ShownCard? = null
        var end = TapEnd.ANSWERED
        val answers = mutableListOf<CardChoice>()
        val threads = mutableListOf<String>()

        override fun <T> withShown(block: (shown: ShownCard?, answer: (CardChoice) -> TapEnd) -> T): T {
            threads += Thread.currentThread().name
            return block(shown) { choice ->
                answers += choice
                end
            }
        }
    }
}
